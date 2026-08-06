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

import scala.beans.BeanProperty

/**
 * Request bodies. These are mutable JavaBeans rather than case classes because Jackson has to
 * construct them from partial JSON, and a `PATCH` must distinguish "field absent" from
 * "field set to null" - an absent field leaves the corresponding property null and the service
 * treats it as "unchanged".
 *
 * No request carries `owner`, `createdBy`, `updatedBy` or `submittedBy`: ownership is always
 * derived from the authenticated caller, and a client-supplied value would be an escalation path.
 */
class CreateFolderRequest {
  @BeanProperty var name: String = _
  @BeanProperty var parentId: String = _
}

class UpdateFolderRequest {
  @BeanProperty var name: String = _
  @BeanProperty var parentId: String = _

  /** Boxed so that a missing field is distinguishable from an explicit value. */
  @BeanProperty var version: java.lang.Long = _
}

class CreateNotebookRequest {
  @BeanProperty var name: String = _
  @BeanProperty var folderId: String = _
  @BeanProperty var description: String = _
  @BeanProperty var defaultCatalog: String = _
  @BeanProperty var defaultSchema: String = _
  @BeanProperty var runtimeProfile: String = _
  @BeanProperty var cells: java.util.List[CreateCellRequest] = _
}

class UpdateNotebookRequest {
  @BeanProperty var name: String = _
  @BeanProperty var description: String = _
  @BeanProperty var defaultCatalog: String = _
  @BeanProperty var defaultSchema: String = _
  @BeanProperty var runtimeProfile: String = _
  @BeanProperty var version: java.lang.Long = _
}

class CloneNotebookRequest {
  @BeanProperty var name: String = _
  @BeanProperty var folderId: String = _
}

class MoveNotebookRequest {
  @BeanProperty var folderId: String = _
  @BeanProperty var name: String = _
  @BeanProperty var version: java.lang.Long = _
}

class CreateCellRequest {
  @BeanProperty var cellType: String = _
  @BeanProperty var language: String = _
  @BeanProperty var source: String = _
  @BeanProperty var position: java.lang.Integer = _
  @BeanProperty var metadata: java.util.Map[String, String] = _
  @BeanProperty var configuration: java.util.Map[String, String] = _
}

class UpdateCellRequest {
  @BeanProperty var cellType: String = _
  @BeanProperty var language: String = _
  @BeanProperty var source: String = _
  @BeanProperty var metadata: java.util.Map[String, String] = _
  @BeanProperty var configuration: java.util.Map[String, String] = _
  @BeanProperty var version: java.lang.Long = _
}

class UpdateCellConfigRequest {
  @BeanProperty var configuration: java.util.Map[String, String] = _
  @BeanProperty var version: java.lang.Long = _
}

class ReorderCellsRequest {
  @BeanProperty var cellIds: java.util.List[String] = _
  @BeanProperty var version: java.lang.Long = _
}

class CreateRevisionRequest {
  @BeanProperty var reason: String = _
}

class SetPermissionsRequest {
  @BeanProperty var permissions: java.util.List[PermissionEntryRequest] = _
}

class PermissionEntryRequest {
  @BeanProperty var principalType: String = _
  @BeanProperty var principalId: String = _
  @BeanProperty var role: String = _
}

class ImportNotebookRequest {

  /** `KYUUBI` or `IPYNB`; inferred from the payload when absent. */
  @BeanProperty var format: String = _
  @BeanProperty var name: String = _
  @BeanProperty var folderId: String = _

  /** The document itself, as raw JSON text, so both formats share one endpoint. */
  @BeanProperty var content: String = _
}

class SetScheduleRequest {
  @BeanProperty var cronExpression: String = _
  @BeanProperty var timezone: String = _
  @BeanProperty var enabled: java.lang.Boolean = _
  @BeanProperty var runtimeProfile: String = _
  @BeanProperty var failurePolicy: String = _
  @BeanProperty var overlapPolicy: String = _
  @BeanProperty var version: java.lang.Long = _
}
