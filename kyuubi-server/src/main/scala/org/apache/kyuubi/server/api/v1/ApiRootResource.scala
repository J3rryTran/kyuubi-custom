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

import javax.ws.rs.{GET, Path, Produces}
import javax.ws.rs.core.{MediaType, Response}

import com.google.common.annotations.VisibleForTesting
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.eclipse.jetty.servlet.{ServletContextHandler, ServletHolder}
import org.glassfish.jersey.server.ResourceConfig
import org.glassfish.jersey.servlet.ServletContainer

import org.apache.kyuubi.KYUUBI_VERSION
import org.apache.kyuubi.client.api.v1.dto._
import org.apache.kyuubi.config.KyuubiConf._
import org.apache.kyuubi.server.KyuubiRestFrontendService
import org.apache.kyuubi.server.api.{ApiRequestContext, EngineUIProxyServlet, FrontendServiceContext, OpenAPIConfig}
import org.apache.kyuubi.server.api.v1.ApiRootResource.{JWT_ISSUER, OIDC_UI_CLIENT_ID, OIDC_UI_SCOPE}
import org.apache.kyuubi.service.authentication.AuthTypes

@Path("/v1")
private[v1] class ApiRootResource extends ApiRequestContext {

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Get the version of Kyuubi server.")
  @GET
  @Path("version")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def version(): VersionInfo = new VersionInfo(KYUUBI_VERSION)

  @GET
  @Path("ping")
  @Produces(Array(MediaType.TEXT_PLAIN))
  def ping(): String = "pong"

  @ApiResponse(
    responseCode = "200",
    content = Array(new Content(mediaType = MediaType.APPLICATION_JSON)),
    description = "Get the authentication configuration the Web UI should use.")
  @GET
  @Path("authentication/config")
  @Produces(Array(MediaType.APPLICATION_JSON))
  def authenticationConfig(): Map[String, Any] = {
    val conf = fe.getConf
    val authTypes = conf.get(AUTHENTICATION_METHOD)
    val oidcEnabled = authTypes.exists(_.equalsIgnoreCase(AuthTypes.OIDC.toString))
    val base = Map[String, Any](
      "authType" -> authTypes.headOption.getOrElse("NONE"),
      "oidcEnabled" -> oidcEnabled)
    if (!oidcEnabled) {
      base
    } else {
      // Only public discovery inputs are exposed here; never any secret.
      base ++ Map[String, Any](
        "issuer" -> conf.getOption(JWT_ISSUER).orNull,
        "clientId" -> conf.getOption(OIDC_UI_CLIENT_ID).orNull,
        "scope" -> conf.getOption(OIDC_UI_SCOPE).getOrElse("openid profile email"))
    }
  }

  @Path("sessions")
  def sessions: Class[SessionsResource] = classOf[SessionsResource]

  @Path("operations")
  def operations: Class[OperationsResource] = classOf[OperationsResource]

  @Path("batches")
  def batches: Class[BatchesResource] = classOf[BatchesResource]

  @Path("admin")
  def admin: Class[AdminResource] = classOf[AdminResource]

  @Path("notebook-folders")
  def notebookFolders: Class[NotebookFoldersResource] = classOf[NotebookFoldersResource]

  @Path("notebooks")
  def notebooks: Class[NotebooksResource] = classOf[NotebooksResource]

  // Collection-level actions are siblings of "notebooks", not children, so they need their own
  // locators rather than a path inside NotebooksResource.
  @Path("notebooks:import")
  def notebooksImport: Class[NotebookImportResource] = classOf[NotebookImportResource]

  @Path("notebooks:search")
  def notebooksSearch: Class[NotebookSearchResource] = classOf[NotebookSearchResource]

  @Path("me")
  def currentUser: Class[CurrentUserResource] = classOf[CurrentUserResource]

  @Path("notebook-status")
  def notebookStatus: Class[NotebookStatusResource] = classOf[NotebookStatusResource]

  @Path("runtime-specs")
  def runtimeSpecs: Class[RuntimeSpecsResource] = classOf[RuntimeSpecsResource]

  @Path("notebook-sessions")
  def notebookSessions: Class[NotebookSessionsResource] = classOf[NotebookSessionsResource]

  @Path("notebook-runtimes")
  def notebookRuntimes: Class[NotebookRuntimesResource] = classOf[NotebookRuntimesResource]

  @Path("executions")
  def executions: Class[ExecutionsResource] = classOf[ExecutionsResource]

  @Path("python-environments")
  def pythonEnvironments: Class[PythonEnvironmentsResource] = classOf[PythonEnvironmentsResource]

  @Path("python-package-operations")
  def pythonPackageOperations: Class[PythonPackageOperationsResource] =
    classOf[PythonPackageOperationsResource]

  @GET
  @Path("exception")
  @Produces(Array(MediaType.TEXT_PLAIN))
  @VisibleForTesting
  def test(): Response = {
    1 / 0
    Response.ok().build()
  }
}

private[server] object ApiRootResource {

  /** Set by the OIDC auth extension; re-read here to tell the Web UI which issuer to use. */
  private[v1] val JWT_ISSUER = "kyuubi.authentication.jwt.issuer"

  /**
   * The OIDC client the Web UI authenticates as. It is a browser-based public client,
   * so it is registered separately from the JDBC one and never carries a secret.
   */
  private[v1] val OIDC_UI_CLIENT_ID = "kyuubi.authentication.oidc.ui.client.id"
  private[v1] val OIDC_UI_SCOPE = "kyuubi.authentication.oidc.ui.scope"

  def getServletHandler(fe: KyuubiRestFrontendService): ServletContextHandler = {
    val openapiConf: ResourceConfig = new OpenAPIConfig
    val holder = new ServletHolder(new ServletContainer(openapiConf))
    val handler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    handler.setContextPath("/api")
    FrontendServiceContext.set(handler, fe)
    handler.addServlet(holder, "/*")
    handler
  }

  def getEngineUIProxyHandler(fe: KyuubiRestFrontendService): ServletContextHandler = {
    val proxyServlet = new EngineUIProxyServlet()
    val holder = new ServletHolder(proxyServlet)
    val conf = fe.getConf
    holder.setInitParameter(
      "idleTimeout",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_IDLE_TIMEOUT).toString)
    holder.setInitParameter(
      "maxConnections",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_MAX_CONNECTIONS).toString)
    holder.setInitParameter(
      "maxThreads",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_MAX_THREADS).toString)
    holder.setInitParameter(
      "requestBufferSize",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_REQUEST_BUFFER_SIZE).toString)
    holder.setInitParameter(
      "responseBufferSize",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_RESPONSE_BUFFER_SIZE).toString)
    holder.setInitParameter(
      "timeout",
      conf.get(FRONTEND_REST_PROXY_JETTY_CLIENT_TIMEOUT).toString)
    val proxyHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    proxyHandler.setContextPath("/engine-ui")
    proxyHandler.addServlet(holder, "/*")
    proxyHandler
  }
}
