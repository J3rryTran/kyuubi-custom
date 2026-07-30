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

import java.io.File
import java.util

import scala.language.implicitConversions

import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl

import org.apache.kyuubi.plugin.spark.authz.{ObjectType, PrivilegeObject}
import org.apache.kyuubi.plugin.spark.authz.ObjectType._
import org.apache.kyuubi.plugin.spark.authz.OperationType.OperationType

class AccessResource private (val objectType: ObjectType, val catalog: Option[String])
  extends RangerAccessResourceImpl {
  implicit def asString(obj: Object): String = if (obj != null) obj.asInstanceOf[String] else null
  def getCatalog: String = getValue("catalog")
  def getDatabase: String = getValue("database")
  def getTable: String = getValue("table")
  def getColumn: String = getValue("column")
  def getColumns: Seq[String] = {
    val columnStr = getColumn
    if (columnStr == null) Nil else columnStr.split(",").filter(_.nonEmpty)
  }
}

object AccessResource {

  def apply(
      objectType: ObjectType,
      firstLevelResource: String,
      secondLevelResource: String,
      thirdLevelResource: String,
      owner: Option[String] = None,
      catalog: Option[String] = None): AccessResource = {
    build(
      SparkRangerAdminPlugin.profile,
      objectType,
      firstLevelResource,
      secondLevelResource,
      thirdLevelResource,
      owner,
      catalog)
  }

  private[ranger] def build(
      profile: RangerServiceProfile,
      objectType: ObjectType,
      firstLevelResource: String,
      secondLevelResource: String,
      thirdLevelResource: String,
      owner: Option[String] = None,
      catalog: Option[String] = None): AccessResource = {
    val normalizedCatalog = profile.normalizeCatalog(catalog)
    val resource = new AccessResource(objectType, normalizedCatalog)

    def setCatalog(): Unit = normalizedCatalog.foreach(resource.setValue("catalog", _))

    resource.objectType match {
      case CATALOG => setCatalog()
      case DATABASE =>
        if (profile.serviceType == "starrocks") setCatalog()
        resource.setValue("database", firstLevelResource)
      case FUNCTION =>
        if (profile.serviceType == "starrocks") setCatalog()
        resource.setValue("database", Option(firstLevelResource).getOrElse(""))
        resource.setValue(
          if (profile.serviceType == "starrocks") "function" else "udf",
          secondLevelResource)
      case COLUMN =>
        if (profile.serviceType == "starrocks") setCatalog()
        resource.setValue("database", firstLevelResource)
        resource.setValue("table", secondLevelResource)
        resource.setValue("column", thirdLevelResource)
      case TABLE =>
        if (profile.serviceType == "starrocks") setCatalog()
        resource.setValue("database", firstLevelResource)
        resource.setValue("table", secondLevelResource)
      case VIEW =>
        if (profile.serviceType == "starrocks") setCatalog()
        resource.setValue("database", firstLevelResource)
        resource.setValue(
          if (profile.serviceType == "starrocks") "view" else "table",
          secondLevelResource)
      case INDEX =>
        if (profile.serviceType == "starrocks") {
          throw new org.apache.kyuubi.plugin.spark.authz.AccessControlException(
            "Index resources are unsupported for Ranger service type [starrocks]")
        }
        resource.setValue("database", firstLevelResource)
        resource.setValue("table", secondLevelResource)
      case URI =>
        if (profile.serviceType == "starrocks") {
          throw new org.apache.kyuubi.plugin.spark.authz.AccessControlException(
            "URI resources are unsupported for Ranger service type [starrocks]")
        }
        val objectList = new util.ArrayList[String]
        Option(firstLevelResource)
          .filter(_.nonEmpty)
          .foreach { path =>
            val s = path.stripSuffix(File.separator)
            objectList.add(s)
            objectList.add(s + File.separator)
          }
        resource.setValue("url", objectList)
    }
    SparkRangerAdminPlugin.getServiceDefOption.foreach(resource.setServiceDef)
    owner.foreach(resource.setOwnerUser)
    resource
  }

  def apply(
      objectType: ObjectType,
      firstLevelResource: String,
      catalog: Option[String]): AccessResource = {
    apply(objectType, firstLevelResource, null, null, catalog = catalog)
  }

  def apply(
      obj: PrivilegeObject,
      opType: OperationType): AccessResource = {
    apply(
      ObjectType(obj, opType),
      obj.dbname,
      obj.objectName,
      obj.columns.mkString(","),
      obj.owner,
      obj.catalog)
  }

  private[ranger] def apply(spec: RangerAuthorizationSpec): AccessResource = {
    apply(
      spec.objectType,
      spec.firstLevelResource,
      spec.secondLevelResource,
      spec.thirdLevelResource,
      spec.owner,
      spec.catalog)
  }
}
