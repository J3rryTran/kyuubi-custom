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

package org.apache.kyuubi.server.notebook.service

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.server.notebook.api.PermissionRole.PermissionRole
import org.apache.kyuubi.server.notebook.store.NotebookStore

/** The authenticated caller. Never built from a request body. */
case class NotebookPrincipal(user: String, admin: Boolean)

/**
 * Resolves what a caller may do with a notebook, and owns the permission table.
 *
 * Runtime, execution and Python-environment access are deliberately *not* derived from these
 * roles: sharing a notebook grants access to its saved content, not to the owner's compute or
 * private environment. Those checks live with their own services.
 */
class NotebookPermissionService(store: NotebookStore) {

  def effectiveRole(notebook: Notebook, principal: NotebookPrincipal): Option[PermissionRole] = {
    if (principal.admin || notebook.owner == principal.user) {
      Some(PermissionRole.OWNER)
    } else {
      store.listPermissions(notebook.id)
        .find(p => p.principalType == PrincipalType.USER && p.principalId == principal.user)
        .map(_.role)
    }
  }

  def requireRead(notebook: Notebook, principal: NotebookPrincipal): PermissionRole =
    effectiveRole(notebook, principal).getOrElse {
      // Same message as a missing notebook, so that probing ids cannot enumerate them.
      throw NotebookException.notFound(
        NotebookErrorCode.NOTEBOOK_NOT_FOUND,
        s"notebook ${notebook.id} was not found")
    }

  def requireWrite(notebook: Notebook, principal: NotebookPrincipal): PermissionRole = {
    val role = requireRead(notebook, principal)
    if (role == PermissionRole.VIEWER) {
      throw NotebookException.accessDenied("editor or owner role is required to modify a notebook")
    }
    role
  }

  def requireOwner(notebook: Notebook, principal: NotebookPrincipal): PermissionRole = {
    val role = requireRead(notebook, principal)
    if (role != PermissionRole.OWNER) {
      throw NotebookException.accessDenied("owner role is required for this operation")
    }
    role
  }

  def requireFolderAccess(folder: NotebookFolder, principal: NotebookPrincipal): Unit = {
    if (!principal.admin && folder.owner != principal.user) {
      throw NotebookException.notFound(
        NotebookErrorCode.FOLDER_NOT_FOUND,
        s"folder ${folder.id} was not found")
    }
  }

  def list(notebook: Notebook, principal: NotebookPrincipal): Seq[NotebookPermissionView] = {
    requireRead(notebook, principal)
    store.listPermissions(notebook.id).map(NotebookPermissionView.apply)
  }

  def replace(
      notebook: Notebook,
      principal: NotebookPrincipal,
      request: SetPermissionsRequest): Seq[NotebookPermissionView] = {
    requireOwner(notebook, principal)
    val entries = Option(request.getPermissions).map(_.asScala.toSeq).getOrElse(Seq.empty)
    val now = System.currentTimeMillis()
    val permissions = entries.map { entry =>
      val principalType = parseEnum(
        PrincipalType,
        entry.getPrincipalType,
        "principalType",
        PrincipalType.USER)
      if (principalType == PrincipalType.GROUP) {
        // Accepting a grant that is never evaluated would silently under-deliver access.
        throw new NotebookException(
          NotebookErrorCode.INVALID_REQUEST,
          "group principals are not supported yet; grant access to individual users")
      }
      val principalId = Option(entry.getPrincipalId).map(_.trim).filter(_.nonEmpty).getOrElse {
        throw NotebookException.invalid("principalId must not be empty")
      }
      val role = parseEnum(PermissionRole, entry.getRole, "role", PermissionRole.VIEWER)
      if (role == PermissionRole.OWNER) {
        throw NotebookException.invalid(
          "the OWNER role is implied by the notebook owner and cannot be granted")
      }
      if (principalId == notebook.owner) {
        throw NotebookException.invalid("the notebook owner already has full access")
      }
      NotebookPermission(notebook.id, principalType, principalId, role, now, principal.user)
    }
    val duplicates = permissions.groupBy(p => (p.principalType, p.principalId)).filter {
      case (_, grants) => grants.size > 1
    }
    if (duplicates.nonEmpty) {
      throw NotebookException.invalid("a principal must appear at most once")
    }
    store.replacePermissions(notebook.id, permissions)
    permissions.map(NotebookPermissionView.apply)
  }

  private def parseEnum[E <: Enumeration](
      enumeration: E,
      raw: String,
      field: String,
      default: E#Value): E#Value = {
    Option(raw).map(_.trim).filter(_.nonEmpty) match {
      case None => default
      case Some(value) =>
        enumeration.values.find(_.toString.equalsIgnoreCase(value)).getOrElse {
          throw NotebookException.invalid(
            s"$field must be one of ${enumeration.values.mkString(", ")}")
        }
    }
  }
}
