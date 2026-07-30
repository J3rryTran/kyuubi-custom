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

import java.util.Locale

import org.apache.ranger.plugin.policyengine.RangerPolicyEngine
import org.apache.spark.SparkConf

import org.apache.kyuubi.plugin.spark.authz.{AccessControlException, ObjectType, OperationType, PrivilegeObject}
import org.apache.kyuubi.plugin.spark.authz.ObjectType._
import org.apache.kyuubi.plugin.spark.authz.OperationType._
import org.apache.kyuubi.plugin.spark.authz.PrivilegeObjectActionType._
import org.apache.kyuubi.plugin.spark.authz.PrivilegeObjectType.{DFS_URI, FUNCTION => FUNCTION_PRIVILEGE, LOCAL_URI, TABLE_OR_VIEW}
import org.apache.kyuubi.plugin.spark.authz.ranger.AccessType.AccessType

private[ranger] case class RangerAuthorizationSpec(
    objectType: ObjectType,
    firstLevelResource: String,
    secondLevelResource: String,
    thirdLevelResource: String,
    owner: Option[String],
    catalog: Option[String],
    accessType: String)

sealed trait RangerServiceProfile {
  def serviceType: String
  def appId: String
  def normalizeCatalog(catalog: Option[String]): Option[String]

  def authorization(
      obj: PrivilegeObject,
      opType: OperationType.OperationType,
      isInput: Boolean): Option[RangerAuthorizationSpec]

  def accessType(
      accessType: AccessType,
      opType: OperationType.OperationType,
      objectType: ObjectType): String
}

object RangerServiceProfile {
  val SERVICE_TYPE_KEY = "spark.kyuubi.authz.ranger.service.type"
  val DEFAULT_CATALOG_KEY = "spark.kyuubi.authz.ranger.starrocks.default.catalog"
  val CATALOG_MAPPING_KEY = "spark.kyuubi.authz.ranger.starrocks.catalog.mapping"

  val DEFAULT_CATALOG = "default_catalog"

  def apply(conf: SparkConf): RangerServiceProfile = {
    conf.get(SERVICE_TYPE_KEY, "spark").trim.toLowerCase(Locale.ROOT) match {
      case "spark" => SparkRangerServiceProfile
      case "starrocks" =>
        val defaultCatalog = conf.get(DEFAULT_CATALOG_KEY, DEFAULT_CATALOG).trim
        if (defaultCatalog.isEmpty) {
          throw new IllegalArgumentException(s"$DEFAULT_CATALOG_KEY must not be empty")
        }
        StarRocksRangerServiceProfile(
          defaultCatalog,
          parseCatalogMapping(conf.getOption(CATALOG_MAPPING_KEY)))
      case other =>
        throw new IllegalArgumentException(
          s"Unsupported value '$other' for $SERVICE_TYPE_KEY; expected spark or starrocks")
    }
  }

  private[ranger] def parseCatalogMapping(value: Option[String]): Map[String, String] = {
    value.filter(_.trim.nonEmpty).map { raw =>
      raw.split(",", -1).foldLeft(Map.empty[String, String]) { (mapping, entry) =>
        val parts = entry.split("=", -1).map(_.trim)
        if (parts.length != 2 || parts.exists(_.isEmpty)) {
          throw new IllegalArgumentException(
            s"Invalid $CATALOG_MAPPING_KEY entry '$entry'; expected sparkCatalog=starrocksCatalog")
        }
        if (mapping.contains(parts(0))) {
          throw new IllegalArgumentException(
            s"Duplicate Spark catalog '${parts(0)}' in $CATALOG_MAPPING_KEY")
        }
        mapping + (parts(0) -> parts(1))
      }
    }.getOrElse(Map.empty)
  }
}

case object SparkRangerServiceProfile extends RangerServiceProfile {
  override val serviceType: String = "spark"
  override val appId: String = "sparkSql"

  override def normalizeCatalog(catalog: Option[String]): Option[String] = catalog

  override def authorization(
      obj: PrivilegeObject,
      opType: OperationType.OperationType,
      isInput: Boolean): Option[RangerAuthorizationSpec] = {
    val legacyAccessType = AccessType(obj, opType, isInput)
    if (legacyAccessType == AccessType.NONE) {
      None
    } else {
      Some(RangerAuthorizationSpec(
        ObjectType(obj, opType),
        obj.dbname,
        obj.objectName,
        obj.columns.mkString(","),
        obj.owner,
        obj.catalog,
        accessType(legacyAccessType, opType, ObjectType(obj, opType))))
    }
  }

  override def accessType(
      accessType: AccessType,
      opType: OperationType.OperationType,
      objectType: ObjectType): String = accessType match {
    case AccessType.USE => RangerPolicyEngine.ANY_ACCESS
    case _ => accessType.toString.toLowerCase(Locale.ROOT)
  }
}

case class StarRocksRangerServiceProfile(
    defaultCatalog: String,
    catalogMapping: Map[String, String]) extends RangerServiceProfile {
  override val serviceType: String = "starrocks"
  override val appId: String = "starrocks"

  override def normalizeCatalog(catalog: Option[String]): Option[String] = {
    val source = catalog.map(_.trim).filter(_.nonEmpty)
    Some(source.flatMap(catalogMapping.get).orElse(source).getOrElse(defaultCatalog))
  }

  private def unsupported(opType: OperationType.OperationType, detail: String): Nothing = {
    throw new AccessControlException(
      s"Unsupported authorization operation [$opType] for Ranger service type " +
        s"[$serviceType]: $detail")
  }

  private def tableType(obj: PrivilegeObject): ObjectType = {
    if (obj.columns.nonEmpty) COLUMN else TABLE
  }

  private def spec(
      obj: PrivilegeObject,
      objectType: ObjectType,
      accessType: String): RangerAuthorizationSpec = {
    val (first, second, third) = objectType match {
      case CATALOG => (null, null, null)
      case DATABASE => (obj.dbname, null, null)
      case FUNCTION | TABLE | VIEW | INDEX => (obj.dbname, obj.objectName, null)
      case COLUMN => (obj.dbname, obj.objectName, obj.columns.mkString(","))
      case URI => (obj.dbname, null, null)
    }
    RangerAuthorizationSpec(
      objectType,
      first,
      second,
      third,
      obj.owner,
      normalizeCatalog(obj.catalog),
      accessType)
  }

  override def authorization(
      obj: PrivilegeObject,
      opType: OperationType.OperationType,
      isInput: Boolean): Option[RangerAuthorizationSpec] = {
    if (obj.privilegeObjectType == DFS_URI || obj.privilegeObjectType == LOCAL_URI) {
      unsupported(
        opType,
        "URI and filesystem resources are not present in the StarRocks service definition")
    }
    if (opType == CREATEINDEX || opType == DROPINDEX || opType == ALTERINDEX_REBUILD ||
      opType == SHOWINDEXES) {
      unsupported(opType, "index resources are not present in the StarRocks service definition")
    }

    obj.actionType match {
      case INSERT | INSERT_OVERWRITE => Some(spec(obj, TABLE, "insert"))
      case UPDATE => Some(spec(obj, TABLE, "update"))
      case DELETE => Some(spec(obj, TABLE, "delete"))
      case OTHER =>
        if (isInput && obj.privilegeObjectType == TABLE_OR_VIEW) {
          Some(spec(obj, tableType(obj), "select"))
        } else if (isInput && obj.privilegeObjectType == FUNCTION_PRIVILEGE) {
          Some(spec(obj, FUNCTION, "usage"))
        } else {
          opType match {
            case ADD => unsupported(opType, "ADD JAR and ADD FILE have no StarRocks equivalent")
            case CREATEDATABASE => Some(spec(obj, CATALOG, "create database"))
            case CREATETABLE | CREATETABLE_AS_SELECT =>
              Some(spec(obj, DATABASE, "create table"))
            case CREATEVIEW => Some(spec(obj, DATABASE, "create view"))
            case CREATEFUNCTION => Some(spec(obj, DATABASE, "create function"))
            case ALTERDATABASE | ALTERDATABASE_LOCATION => Some(spec(obj, DATABASE, "alter"))
            case ALTERVIEW_AS | ALTERVIEW_RENAME => Some(spec(obj, VIEW, "alter"))
            case ALTERTABLE_ADDCOLS | ALTERTABLE_ADDPARTS | ALTERTABLE_COMPACT |
                ALTERTABLE_DROPPARTS | ALTERTABLE_LOCATION | ALTERTABLE_RENAME |
                ALTERTABLE_PROPERTIES | ALTERTABLE_RENAMECOL | ALTERTABLE_RENAMEPART |
                ALTERTABLE_REPLACECOLS | ALTERTABLE_SERDEPROPERTIES | MSCK =>
              Some(spec(obj, TABLE, "alter"))
            case DROPDATABASE => Some(spec(obj, DATABASE, "drop"))
            case DROPTABLE => Some(spec(obj, TABLE, "drop"))
            case DROPVIEW => Some(spec(obj, VIEW, "drop"))
            case DROPFUNCTION => Some(spec(obj, FUNCTION, "drop"))
            case LOAD => Some(spec(obj, TABLE, "insert"))
            case QUERY | SHOW_CREATETABLE | SHOW_TBLPROPERTIES | SHOWPARTITIONS |
                ANALYZE_TABLE | SHOWCOLUMNS | DESCTABLE =>
              Some(spec(obj, tableType(obj), "select"))
            case SHOWDATABASES | SWITCHDATABASE | DESCDATABASE | SHOWTABLES =>
              Some(spec(obj, DATABASE, RangerPolicyEngine.ANY_ACCESS))
            case SHOWFUNCTIONS => Some(spec(obj, FUNCTION, RangerPolicyEngine.ANY_ACCESS))
            case DESCFUNCTION => Some(spec(obj, FUNCTION, "usage"))
            case TRUNCATETABLE => Some(spec(obj, TABLE, "update"))
            case RELOADFUNCTION =>
              unsupported(opType, "reload function has no StarRocks equivalent")
            case _ => unsupported(opType, "no StarRocks privilege mapping is defined")
          }
        }
    }
  }

  override def accessType(
      accessType: AccessType,
      opType: OperationType.OperationType,
      objectType: ObjectType): String = accessType match {
    case AccessType.SELECT => "select"
    case AccessType.USE => RangerPolicyEngine.ANY_ACCESS
    case AccessType.UPDATE => "update"
    case AccessType.DROP => "drop"
    case AccessType.ALTER => "alter"
    case AccessType.CREATE => opType match {
        case CREATEDATABASE => "create database"
        case CREATEVIEW => "create view"
        case CREATEFUNCTION => "create function"
        case _ => "create table"
      }
    case AccessType.NONE => unsupported(opType, "access type NONE must not be sent to Ranger")
    case AccessType.READ | AccessType.WRITE =>
      unsupported(opType, "URI access types are not supported")
    case AccessType.INDEX => unsupported(opType, "index access is not supported")
    case AccessType.TEMPUDFADMIN =>
      unsupported(opType, "temporary UDF administration is not supported")
    case other => other.toString.toLowerCase(Locale.ROOT)
  }
}
