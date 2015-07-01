/**
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.repository.git.filter;

import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.tmatesoft.svn.core.SVNException;
import svnserver.repository.git.GitObject;

import java.io.IOException;
import java.io.InputStream;

/**
 * File fiter interface.
 * Used for implementing "clean" and "smudge" git filter functionality (https://git-scm.com/book/en/v2/Customizing-Git-Git-Attributes).
 *
 * @author Artem V. Navrotskiy <bozaro@users.noreply.github.com>
 */
public interface GitFilter {
  /**
   * Filter name.
   *
   * @return Filter name.
   */
  @NotNull
  String getName();

  /**
   * Get object stream.
   *
   * @param objectId Object reference.
   * @return Object stream.
   */
  @NotNull
  InputStream openStream(@NotNull GitObject<ObjectId> objectId) throws IOException;

  /**
   * Get object content hash.
   *
   * @param objectId Object reference.
   * @return Object content hash.
   */
  @NotNull
  default String getContentHash(@NotNull GitObject<ObjectId> objectId) throws IOException, SVNException {
    return getName() + " " + objectId.getObject().getName();
  }

  /**
   * Get object md5 sum.
   *
   * @param objectId Object reference.
   * @return Object md5 sum.
   */
  @NotNull
  String getMd5(@NotNull GitObject<ObjectId> objectId) throws IOException, SVNException;

  /**
   * Get object size.
   *
   * @param objectId Object reference.
   * @return Object size in bytes.
   */
  long getSize(@NotNull GitObject<ObjectId> objectId) throws IOException, SVNException;

}
