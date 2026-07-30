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

package org.apache.kyuubi.plugin.spark.authz.ranger

import java.util.{Collection => JCollection, Set => JSet}
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

import org.apache.hadoop.util.ShutdownHookManager
import org.apache.ranger.plugin.contextenricher.RangerUserStoreEnricher
import org.apache.ranger.plugin.model.RangerServiceDef
import org.apache.ranger.plugin.policyengine.{RangerAccessRequest, RangerAccessResult, RangerAccessResultProcessor}
import org.apache.ranger.plugin.service.RangerBasePlugin
import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

import org.apache.kyuubi.plugin.spark.authz.AccessControlException

object SparkRangerAdminPlugin extends RangerConfigProvider {
  final private val LOG = LoggerFactory.getLogger(getClass)

  private case class PluginState(profile: RangerServiceProfile, plugin: RangerBasePlugin)

  @volatile private var state: PluginState = _
  private val shutdownHookRegistered = new AtomicBoolean(false)

  def profile: RangerServiceProfile = Option(state)
    .map(_.profile)
    .getOrElse(SparkRangerServiceProfile)

  override protected def rangerPlugin: RangerBasePlugin = ensureInitialized().plugin

  def initialize(spark: SparkSession): Unit =
    initialize(RangerServiceProfile(spark.sparkContext.getConf))

  private def initialize(requestedProfile: RangerServiceProfile): Unit = synchronized {
    Option(state) match {
      case Some(existing) if existing.profile != requestedProfile =>
        throw new IllegalStateException(
          s"Ranger plugin is already initialized for service type " +
            s"[${existing.profile.serviceType}] with a different configuration")
      case Some(_) =>
      case None =>
        val plugin = new RangerBasePlugin(requestedProfile.serviceType, requestedProfile.appId)
        plugin.init()
        if (requestedProfile.serviceType == "starrocks") {
          validateStarRocksServiceDef(plugin.getServiceDef)
        }
        state = PluginState(requestedProfile, plugin)
        registerCleanupShutdownHook()
    }
  }

  private def ensureInitialized(): PluginState = {
    if (state == null) {
      initialize(SparkRangerServiceProfile)
    }
    state
  }

  private def validateStarRocksServiceDef(serviceDef: RangerServiceDef): Unit = {
    if (serviceDef == null || serviceDef.getName != "starrocks") {
      throw new IllegalStateException(
        "Ranger service must provide the StarRocks service definition in starrocks mode")
    }
    val resources = Option(serviceDef.getResources).toSeq.flatMap(_.asScala).map(_.getName).toSet
    val accesses = Option(serviceDef.getAccessTypes).toSeq.flatMap(_.asScala).map(_.getName).toSet
    val requiredResources = Set("catalog", "database", "table", "column", "view", "function")
    val requiredAccesses = Set(
      "select",
      "usage",
      "create database",
      "create table",
      "create view",
      "create function",
      "insert",
      "update",
      "delete",
      "drop",
      "alter")
    val missingResources = requiredResources -- resources
    val missingAccesses = requiredAccesses -- accesses
    if (missingResources.nonEmpty || missingAccesses.nonEmpty) {
      throw new IllegalStateException(
        s"StarRocks Ranger service definition is incompatible; missing resources " +
          s"[${missingResources.toSeq.sorted.mkString(",")}] and access types " +
          s"[${missingAccesses.toSeq.sorted.mkString(",")}]")
    }
  }

  def getServiceType: String = ensureInitialized().plugin.getServiceType
  def getAppId: String = ensureInitialized().plugin.getAppId
  def getServiceDef: RangerServiceDef = ensureInitialized().plugin.getServiceDef
  private[ranger] def getServiceDefOption: Option[RangerServiceDef] =
    Option(state).flatMap(current => Option(current.plugin.getServiceDef))
  def getClusterName: String = ensureInitialized().plugin.getClusterName
  def getUserStoreEnricher: RangerUserStoreEnricher =
    ensureInitialized().plugin.getUserStoreEnricher
  def getRolesFromUserAndGroups(user: String, groups: JSet[String]): JSet[String] =
    ensureInitialized().plugin.getRolesFromUserAndGroups(user, groups)

  def isAccessAllowed(request: RangerAccessRequest): RangerAccessResult =
    ensureInitialized().plugin.isAccessAllowed(request)

  def isAccessAllowed(
      requests: JCollection[RangerAccessRequest],
      processor: RangerAccessResultProcessor): JCollection[RangerAccessResult] =
    ensureInitialized().plugin.isAccessAllowed(requests, processor)

  def evalRowFilterPolicies(
      request: RangerAccessRequest,
      processor: RangerAccessResultProcessor): RangerAccessResult =
    ensureInitialized().plugin.evalRowFilterPolicies(request, processor)

  def evalDataMaskPolicies(
      request: RangerAccessRequest,
      processor: RangerAccessResultProcessor): RangerAccessResult =
    ensureInitialized().plugin.evalDataMaskPolicies(request, processor)

  /**
   * For a Spark SQL query, it may contain 0 or more privilege objects to verify, e.g. a typical
   * JOIN operator may have two tables and their columns to verify.
   *
   * This configuration controls whether to verify the privilege objects in single call or
   * to verify them one by one.
   */
  def authorizeInSingleCall: Boolean = getRangerConf.getBoolean(
    s"ranger.plugin.${getServiceType}.authorize.in.single.call",
    false)

  /**
   * This configuration controls whether to override user's usergroups
   * by the mapping fetched from Ranger's UserStore.
   *
   * It relies on Ranger's UserStore is a feature supported since Ranger 2.1.
   *
   * If true, user bound usergroups will be looked up in in Ranger's UserStore
   * and the usergroups of AccessRequest is overriden.
   *
   * Please make sure configs in Ranger set properly:
   * 1. set `ranger.plugin.spark.enable.implicit.userstore.enricher` to true
   * 2. set cache path for UserStore in `ranger.plugin.hive.policy.cache.dir`
   * 3. at least one condition of policies containing scripts, e.g. {{USER.attr}} in row-filter
   */
  def useUserGroupsFromUserStoreEnabled: Boolean = getRangerConf.getBoolean(
    s"ranger.plugin.$getServiceType.use.usergroups.from.userstore.enabled",
    false)

  private def registerCleanupShutdownHook(): Unit = {
    if (shutdownHookRegistered.compareAndSet(false, true)) {
      ShutdownHookManager.get().addShutdownHook(
        () => cleanupCurrentPlugin(),
        Integer.MAX_VALUE)
    }
  }

  private def cleanupCurrentPlugin(): Unit = synchronized {
    Option(state).foreach { current =>
      LOG.info(s"clean up ranger plugin, appId: ${current.plugin.getAppId}")
      current.plugin.cleanup()
      current.plugin.getAuditProviderFactory.shutdown()
    }
  }

  private[ranger] def resetForTesting(): Unit = synchronized {
    cleanupCurrentPlugin()
    state = null
  }

  def getFilterExpr(req: AccessRequest): Option[String] = {
    val result = evalRowFilterPolicies(req, null)
    Option(result)
      .filter(_.isRowFilterEnabled)
      .map(_.getFilterExpr)
      .filter(fe => fe != null && fe.nonEmpty)
  }

  def getMaskingExpr(req: AccessRequest): Option[String] = {
    val col = req.getResource.asInstanceOf[AccessResource].getColumn
    val result = evalDataMaskPolicies(req, null)
    Option(result).filter(_.isMaskEnabled).map { res =>
      if ("MASK_NULL".equalsIgnoreCase(res.getMaskType)) {
        "NULL"
      } else if ("CUSTOM".equalsIgnoreCase(result.getMaskType)) {
        val maskVal = res.getMaskedValue
        if (maskVal == null) {
          "NULL"
        } else {
          s"${maskVal.replace("{col}", col)}"
        }
      } else if (result.getMaskTypeDef != null) {
        result.getMaskTypeDef.getName match {
          case "MASK" => regexp_replace(col)
          case "MASK_SHOW_FIRST_4" =>
            regexp_replace(col, hasLen = true)
          case "MASK_SHOW_LAST_4" =>
            val left = regexp_replace(s"left($col, length($col) - 4)")
            s"concat($left, right($col, 4))"
          case "MASK_HASH" => s"md5(cast($col as string))"
          case "MASK_DATE_SHOW_YEAR" => s"date_trunc('YEAR', $col)"
          case _ => result.getMaskTypeDef.getTransformer match {
              case transformer if transformer != null && transformer.nonEmpty =>
                s"${transformer.replace("{col}", col)}"
              case _ => null
            }
        }
      } else {
        null
      }
    }
  }

  private def regexp_replace(expr: String, hasLen: Boolean = false): String = {
    val pos = if (hasLen) ", 5" else ""
    val upper = s"regexp_replace($expr, '[A-Z]', 'X'$pos)"
    val lower = s"regexp_replace($upper, '[a-z]', 'x'$pos)"
    val digits = s"regexp_replace($lower, '[0-9]', 'n'$pos)"
    val other = s"regexp_replace($digits, '[^A-Za-z0-9]', 'U'$pos)"
    other
  }

  /**
   * batch verifying RangerAccessRequests
   * and throws exception with all disallowed privileges
   * for accessType and resources
   */
  def verify(
      requests: Seq[RangerAccessRequest],
      auditHandler: SparkRangerAuditHandler): Unit = {
    if (requests.nonEmpty) {
      val results = SparkRangerAdminPlugin.isAccessAllowed(requests.asJava, auditHandler)
      if (results != null) {
        val indices = results.asScala.zipWithIndex.filter { case (result, idx) =>
          result != null && !result.getIsAllowed
        }.map(_._2)
        if (indices.nonEmpty) {
          val user = requests.head.getUser
          val accessTypeToResource =
            indices.foldLeft(LinkedHashMap.empty[String, ArrayBuffer[String]])((m, idx) => {
              val req = requests(idx)
              val accessType = req.getAccessType
              val resource = req.getResource.getAsString
              m.getOrElseUpdate(accessType, ArrayBuffer.empty[String])
                .append(resource)
              m
            })
          val errorMsg = accessTypeToResource
            .map { case (accessType, resources) =>
              s"[$accessType] ${resources.mkString("privilege on [", ",", "]")}"
            }.mkString(", ")
          throw new AccessControlException(
            s"Permission denied: user [$user] does not have $errorMsg")
        }
      }
    }
  }
}
