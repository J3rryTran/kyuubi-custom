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

package org.apache.kyuubi.server.api.v1

import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}

import io.swagger.v3.oas.annotations.tags.Tag

import org.apache.kyuubi.server.notebook.api._

/**
 * Python environments owned by the calling user.
 *
 * Every route resolves the environment through the service's ownership check, so an id belonging
 * to another user reads as missing rather than forbidden. Package requests carry requirement
 * names only; the pip command line is built by the server.
 */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
@Consumes(Array(MediaType.APPLICATION_JSON))
private[v1] class PythonEnvironmentsResource extends NotebookApiSupport {

  private def environments = notebooks.pythonEnvironments

  private def view(environment: PythonEnvironment): PythonEnvironmentView =
    PythonEnvironmentView(
      environment,
      environments.activeRevision(environment).map(_.revisionNumber))

  @POST
  def create(request: CreatePythonEnvironmentRequest): PythonEnvironmentView =
    view(environments.create(principal, request))

  @GET
  def list(): Seq[PythonEnvironmentView] = environments.list(principal).map(view)

  @GET
  @Path("{environmentId: [^:/]+}")
  def get(@PathParam("environmentId") environmentId: String): PythonEnvironmentView =
    view(environments.require(principal, environmentId))

  @PATCH
  @Path("{environmentId: [^:/]+}")
  def update(
      @PathParam("environmentId") environmentId: String,
      request: UpdatePythonEnvironmentRequest): PythonEnvironmentView =
    view(environments.rename(principal, environmentId, request))

  @DELETE
  @Path("{environmentId: [^:/]+}")
  def delete(@PathParam("environmentId") environmentId: String): Response = {
    environments.delete(principal, environmentId)
    Response.noContent().build()
  }

  @GET
  @Path("{environmentId: [^:/]+}/revisions")
  def revisions(
      @PathParam("environmentId") environmentId: String): Seq[PythonEnvironmentRevisionView] =
    environments.revisions(principal, environmentId)

  @GET
  @Path("{environmentId: [^:/]+}/revisions/{revisionNumber}")
  def revision(
      @PathParam("environmentId") environmentId: String,
      @PathParam("revisionNumber") revisionNumber: Long): PythonEnvironmentRevisionView =
    environments.revisions(principal, environmentId)
      .find(_.revisionNumber == revisionNumber)
      .getOrElse(throw NotebookException.notFound(
        NotebookErrorCode.PYTHON_ENVIRONMENT_NOT_FOUND,
        s"revision $revisionNumber was not found"))

  @GET
  @Path("{environmentId: [^:/]+}/packages")
  def packages(@PathParam("environmentId") environmentId: String): PythonPackageListView =
    environments.packages(principal, environmentId)

  @POST
  @Path("{environmentId: [^:/]+}/packages:install")
  def install(
      @PathParam("environmentId") environmentId: String,
      request: PackageOperationRequest): PythonPackageOperationView =
    PythonPackageOperationView(environments.install(principal, environmentId, request))

  @POST
  @Path("{environmentId: [^:/]+}/packages:uninstall")
  def uninstall(
      @PathParam("environmentId") environmentId: String,
      request: PackageOperationRequest): PythonPackageOperationView =
    PythonPackageOperationView(environments.uninstall(principal, environmentId, request))

  @POST
  @Path("{environmentId: [^:/]+}:rebuild")
  def rebuild(
      @PathParam("environmentId") environmentId: String): PythonPackageOperationView =
    PythonPackageOperationView(environments.rebuild(principal, environmentId))
}

/** Progress of a build. The log is redacted of anything that looks like a credential. */
@Tag(name = "Notebook")
@Produces(Array(MediaType.APPLICATION_JSON))
private[v1] class PythonPackageOperationsResource extends NotebookApiSupport {

  @GET
  @Path("{operationId: [^:/]+}")
  def get(@PathParam("operationId") operationId: String): PythonPackageOperationView =
    PythonPackageOperationView(notebooks.pythonEnvironments.operation(principal, operationId))

  @GET
  @Path("{operationId: [^:/]+}/logs")
  def logs(@PathParam("operationId") operationId: String): PythonOperationLogView =
    notebooks.pythonEnvironments.operationLog(principal, operationId)

  @POST
  @Path("{operationId: [^:/]+}:cancel")
  def cancel(@PathParam("operationId") operationId: String): PythonPackageOperationView =
    PythonPackageOperationView(notebooks.pythonEnvironments.cancel(principal, operationId))
}
