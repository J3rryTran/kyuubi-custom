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

package org.apache.kyuubi.server.notebook

import scala.collection.JavaConverters._

import org.apache.kyuubi.server.notebook.api._

class NotebookPermissionSuite extends NotebookTestBase {

  private def grant(notebookId: String, principalId: String, role: String): Unit = {
    val entry = new PermissionEntryRequest
    entry.setPrincipalType("USER")
    entry.setPrincipalId(principalId)
    entry.setRole(role)
    val request = new SetPermissionsRequest
    request.setPermissions(Seq(entry).asJava)
    permissions.replace(documents.loadNotebook(notebookId), alice, request)
  }

  test("a viewer may read but not modify") {
    val (notebook, _) = createNotebook(alice, "viewer-shared")
    grant(notebook.id, "bob", "VIEWER")

    assert(documents.getNotebook(bob, notebook.id).id === notebook.id)
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      addCell(bob, notebook.id, "select 1")
    }
  }

  test("an editor may modify but not share or delete") {
    val (notebook, _) = createNotebook(alice, "editor-shared")
    grant(notebook.id, "bob", "EDITOR")

    addCell(bob, notebook.id, "select 1")
    assert(documents.listCells(notebook.id).size === 1)

    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      permissions.replace(documents.loadNotebook(notebook.id), bob, new SetPermissionsRequest)
    }
    interceptNotebook(NotebookErrorCode.ACCESS_DENIED) {
      documents.deleteNotebook(bob, notebook.id, None)
    }
  }

  test("only the owner changes permissions") {
    val (notebook, _) = createNotebook(alice, "owner-only")
    grant(notebook.id, "bob", "EDITOR")
    assert(permissions.list(documents.loadNotebook(notebook.id), alice).size === 1)
  }

  test("the OWNER role cannot be granted away") {
    val (notebook, _) = createNotebook(alice, "no-owner-grant")
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST)(grant(notebook.id, "bob", "OWNER"))
  }

  test("group principals are refused rather than silently ignored") {
    val (notebook, _) = createNotebook(alice, "no-groups")
    val entry = new PermissionEntryRequest
    entry.setPrincipalType("GROUP")
    entry.setPrincipalId("analysts")
    entry.setRole("VIEWER")
    val request = new SetPermissionsRequest
    request.setPermissions(Seq(entry).asJava)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      permissions.replace(documents.loadNotebook(notebook.id), alice, request)
    }
  }

  test("a principal may not appear twice") {
    val (notebook, _) = createNotebook(alice, "no-duplicates")
    val entries = Seq("VIEWER", "EDITOR").map { role =>
      val entry = new PermissionEntryRequest
      entry.setPrincipalType("USER")
      entry.setPrincipalId("bob")
      entry.setRole(role)
      entry
    }
    val request = new SetPermissionsRequest
    request.setPermissions(entries.asJava)
    interceptNotebook(NotebookErrorCode.INVALID_REQUEST) {
      permissions.replace(documents.loadNotebook(notebook.id), alice, request)
    }
  }

  test("grants do not survive a notebook that reuses the path") {
    val (notebook, _) = createNotebook(alice, "recycled")
    grant(notebook.id, "bob", "EDITOR")
    documents.deleteNotebook(alice, notebook.id, None)

    val (replacement, _) = createNotebook(alice, "recycled")
    assert(permissions.list(documents.loadNotebook(replacement.id), alice).isEmpty)
    interceptNotebook(NotebookErrorCode.NOTEBOOK_NOT_FOUND) {
      documents.getNotebook(bob, replacement.id)
    }
  }

  test("an administrator sees every notebook as owner") {
    val (notebook, _) = createNotebook(alice, "admin-visible")
    assert(permissions.effectiveRole(documents.loadNotebook(notebook.id), root)
      .contains(PermissionRole.OWNER))
  }

  test("a shared notebook is listed for the grantee") {
    val (notebook, _) = createNotebook(alice, "listed-for-grantee")
    grant(notebook.id, "bob", "VIEWER")
    val listed = documents.listNotebooks(
      bob,
      None,
      None,
      Some("listed-for-grantee"),
      None,
      None,
      None,
      Some(10))
    assert(listed.items.map(_.id) === Seq(notebook.id))
    assert(listed.items.head.role.contains("VIEWER"))
  }
}
