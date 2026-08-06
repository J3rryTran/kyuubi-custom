/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kyuubi.server.notebook.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Notebook paths are derived, never client-supplied: a notebook or folder always lives at
 * `/<owner>/<ancestor names...>/<own name>`. Rooting every path at the owner is what makes a
 * cross-user namespace collision impossible, and keeps "move" from being able to relocate an
 * object into somebody else's tree.
 */
object NotebookPaths {

  val SEPARATOR = "/"

  private val MAX_NAME_LENGTH = 255

  /** Path separators and control characters, which would make a derived path ambiguous. */
  private val ILLEGAL_NAME_PATTERN = """[/\\\x00-\x1F]""".r

  def validateName(name: String): String = {
    val trimmed = Option(name).map(_.trim).getOrElse("")
    if (trimmed.isEmpty) {
      throw NotebookException.invalid("name must not be empty")
    }
    if (trimmed.length > MAX_NAME_LENGTH) {
      throw NotebookException.invalid(s"name must be at most $MAX_NAME_LENGTH characters")
    }
    if (ILLEGAL_NAME_PATTERN.findFirstIn(trimmed).isDefined) {
      throw NotebookException.invalid(
        "name must not contain a path separator or control character")
    }
    if (trimmed == "." || trimmed == "..") {
      throw NotebookException.invalid("name must not be a relative path segment")
    }
    trimmed
  }

  def rootPath(owner: String): String = SEPARATOR + owner

  def childPath(parentPath: String, name: String): String = parentPath + SEPARATOR + name

  /**
   * Owner of the tree a path belongs to, used to reject a move that would cross into another
   * user's namespace.
   */
  def ownerOf(path: String): Option[String] =
    path.split(SEPARATOR).find(_.nonEmpty)

  /**
   * Uniqueness key for a path. Hashing keeps the unique index at a fixed width, which matters
   * because a utf8mb4 `varchar(1024)` does not fit InnoDB's index limit.
   */
  def hash(path: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(path.getBytes(StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xFF}%02x").mkString
  }

  /**
   * Path written over a soft-deleted row. Mixing the id in frees the live path for reuse while
   * the row is retained, and the marker keeps tombstones recognisable in the database.
   */
  def tombstonePath(path: String, id: String): String = s"$path#deleted:$id"
}
