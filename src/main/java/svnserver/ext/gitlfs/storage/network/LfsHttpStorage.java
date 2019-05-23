/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.gitlfs.storage.network;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.bozaro.gitlfs.client.Client;
import ru.bozaro.gitlfs.client.exceptions.RequestException;
import ru.bozaro.gitlfs.client.io.StreamProvider;
import ru.bozaro.gitlfs.common.LockConflictException;
import ru.bozaro.gitlfs.common.VerifyLocksResult;
import ru.bozaro.gitlfs.common.data.*;
import svnserver.TemporaryOutputStream;
import svnserver.auth.User;
import svnserver.ext.gitlfs.storage.LfsReader;
import svnserver.ext.gitlfs.storage.LfsStorage;
import svnserver.ext.gitlfs.storage.LfsWriter;
import svnserver.repository.git.GitBranch;
import svnserver.repository.locks.LockDesc;
import svnserver.repository.locks.LockTarget;
import svnserver.repository.locks.UnlockTarget;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * HTTP remote storage for LFS files.
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public abstract class LfsHttpStorage implements LfsStorage {

  @NotNull
  private static final Logger log = LoggerFactory.getLogger(LfsHttpStorage.class);

  @Nullable
  final ObjectRes getMeta(@NotNull String hash) throws IOException {
    final Client lfsClient = lfsClient(User.getAnonymous());
    return lfsClient.getMeta(hash);
  }

  @NotNull
  protected abstract Client lfsClient(@NotNull User user);

  public abstract void invalidate(@NotNull User user);

  // Theoretically, we could submit async batch requests here while user is uploading deltas to us and wait for them before finishing commit instead of doing synchronous requests
  final void putObject(@NotNull User user, @NotNull StreamProvider streamProvider, @NotNull String sha, long size) throws IOException {
    final Client lfsClient = lfsClient(user);

    final BatchRes batchRes = lfsClient.postBatch(new BatchReq(Operation.Upload, Collections.singletonList(new Meta(sha, size))));
    if (batchRes.getObjects().isEmpty())
      throw new IOException(String.format("Empty batch response while uploading %s", sha));

    for (BatchItem batchItem : batchRes.getObjects()) {
      if (batchItem.getError() != null)
        throw new IOException(String.format("LFS error[%s]: %s", batchItem.getError().getCode(), batchItem.getError().getMessage()));

      if (!lfsClient.putObject(streamProvider, batchItem, batchItem))
        throw new IOException("Failed to upload LFS object");
    }
  }

  @NotNull
  final InputStream getObject(@NotNull Links links) throws IOException {
    final Client lfsClient = lfsClient(User.getAnonymous());
    return lfsClient.getObject(null, links, TemporaryOutputStream::new).toInputStream();
  }

  @Nullable
  public final LfsReader getReader(@NotNull String oid) throws IOException {
    return getReader(oid, User.getAnonymous());
  }

  @Nullable
  public final LfsReader getReader(@NotNull String oid, @NotNull User user) throws IOException {
    try {
      if (!oid.startsWith(OID_PREFIX))
        return null;

      final String hash = oid.substring(OID_PREFIX.length());
      final Client lfsClient = lfsClient(user);
      BatchRes res = lfsClient.postBatch(new BatchReq(Operation.Download, Collections.singletonList(new Meta(hash, -1))));
      if (res.getObjects().isEmpty())
        return null;
      BatchItem item = res.getObjects().get(0);
      if (item.getError() != null) return null;
      final ObjectRes meta = new ObjectRes(item.getOid(), item.getSize(), item.getLinks());
      return new LfsHttpReader(this, meta.getMeta(), meta);
    } catch (RequestException e) {
      log.error("HTTP request error:" + e.getMessage(), e);
      throw e;
    }
  }

  @NotNull
  @Override
  public final LfsWriter getWriter(@NotNull User user) {
    return new LfsHttpWriter(this, user);
  }

  @NotNull
  @Override
  public LockDesc[] lock(@NotNull User user, @Nullable GitBranch branch, @Nullable String comment, boolean stealLock, @NotNull LockTarget[] targets) throws IOException, LockConflictException {
    final Ref ref = branch == null ? null : new Ref(branch.getShortBranchName());
    final Client client = lfsClient(user);

    // TODO: this is not atomic :( Waiting for batch LFS locking API

    final List<LockDesc> result = new ArrayList<>();
    for (LockTarget target : targets) {
      Lock lock;
      try {
        lock = client.lock(target.getPath(), ref);
      } catch (LockConflictException e) {
        if (stealLock) {
          client.unlock(e.getLock(), true, ref);
          lock = client.lock(target.getPath(), ref);
        } else {
          throw e;
        }
      }

      result.add(LockDesc.toLockDesc(lock));
    }

    return result.toArray(LockDesc.emptyArray);
  }

  @NotNull
  @Override
  public LockDesc[] unlock(@NotNull User user, @Nullable GitBranch branch, boolean breakLock, @NotNull UnlockTarget[] targets) throws IOException {
    final Ref ref = branch == null ? null : new Ref(branch.getShortBranchName());
    final Client client = lfsClient(user);

    final List<LockDesc> result = new ArrayList<>();
    for (UnlockTarget target : targets) {
      final String lockId;
      if (target.getToken() == null) {
        final LockDesc[] locks = getLocks(user, branch, target.getPath(), null);

        if (locks.length > 1 && locks[0].getPath().equals(target.getPath()))
          lockId = locks[0].getToken();
        else
          continue;
      } else {
        lockId = target.getToken();
      }

      final Lock lock = client.unlock(lockId, breakLock, ref);
      if (lock != null)
        result.add(LockDesc.toLockDesc(lock));
    }

    return result.toArray(LockDesc.emptyArray);
  }

  @Override
  public boolean cleanupInvalidLocks(@NotNull GitBranch branch) {
    return false;
  }

  @Override
  public void renewLocks(@NotNull GitBranch branch, @NotNull LockDesc[] lockDescs) {
  }

  @NotNull
  @Override
  public LockDesc[] getLocks(@NotNull User user, @Nullable GitBranch branch, @Nullable String path, @Nullable String lockId) throws IOException {
    final Ref ref = branch == null ? null : new Ref(branch.getShortBranchName());
    final List<Lock> locks = lfsClient(user).listLocks(path, lockId, ref);
    final List<LockDesc> result = locks.stream().map(LockDesc::toLockDesc).collect(Collectors.toList());
    return result.toArray(LockDesc.emptyArray);
  }

  @NotNull
  @Override
  public VerifyLocksResult verifyLocks(@NotNull User user, @Nullable GitBranch branch) throws IOException {
    final Ref ref = branch == null ? null : new Ref(branch.getShortBranchName());
    return lfsClient(user).verifyLocks(ref);
  }
}
