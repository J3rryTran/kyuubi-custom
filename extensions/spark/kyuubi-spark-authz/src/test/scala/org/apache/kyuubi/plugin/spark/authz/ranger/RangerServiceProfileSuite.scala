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

import org.apache.ranger.plugin.policyengine.RangerPolicyEngine
import org.apache.spark.SparkConf
// scalastyle:off
import org.scalatest.funsuite.AnyFunSuite

import org.apache.kyuubi.plugin.spark.authz._
import org.apache.kyuubi.plugin.spark.authz.ObjectType._
import org.apache.kyuubi.plugin.spark.authz.OperationType._
import org.apache.kyuubi.plugin.spark.authz.PrivilegeObjectActionType._
import org.apache.kyuubi.plugin.spark.authz.PrivilegeObjectType.{DATABASE => DATABASE_PRIV, DFS_URI, FUNCTION => FUNCTION_PRIV, TABLE_OR_VIEW}

class RangerServiceProfileSuite extends AnyFunSuite {
// scalastyle:on

  private val profile = StarRocksRangerServiceProfile(
    "default_catalog",
    Map("spark_catalog" -> "lakehouse"))

  private def table(
      action: PrivilegeObjectActionType = OTHER,
      columns: Seq[String] = Nil,
      catalog: Option[String] = Some("spark_catalog")): PrivilegeObject = {
    PrivilegeObject(TABLE_OR_VIEW, action, "db", "tbl", columns, None, catalog)
  }

  private def database: PrivilegeObject =
    PrivilegeObject(DATABASE_PRIV, OTHER, "db", "db", Nil, None, Some("spark_catalog"))

  private def function: PrivilegeObject =
    PrivilegeObject(FUNCTION_PRIV, OTHER, "db", "fn", Nil, None, Some("spark_catalog"))

  private def spec(
      obj: PrivilegeObject,
      opType: OperationType,
      isInput: Boolean = false): RangerAuthorizationSpec = {
    profile.authorization(obj, opType, isInput).get
  }

  test("select StarRocks profile from Spark configuration") {
    assert(RangerServiceProfile(new SparkConf(false)) === SparkRangerServiceProfile)

    val conf = new SparkConf(false)
      .set(RangerServiceProfile.SERVICE_TYPE_KEY, "starrocks")
      .set(RangerServiceProfile.DEFAULT_CATALOG_KEY, "fallback")
      .set(RangerServiceProfile.CATALOG_MAPPING_KEY, "spark_catalog=lakehouse,iceberg=prod")
    val selected = RangerServiceProfile(conf).asInstanceOf[StarRocksRangerServiceProfile]
    assert(selected.defaultCatalog === "fallback")
    assert(selected.normalizeCatalog(None) === Some("fallback"))
    assert(selected.normalizeCatalog(Some("spark_catalog")) === Some("lakehouse"))
    assert(selected.normalizeCatalog(Some("unmapped")) === Some("unmapped"))

    intercept[IllegalArgumentException] {
      RangerServiceProfile(new SparkConf(false)
        .set(RangerServiceProfile.SERVICE_TYPE_KEY, "unknown"))
    }
    intercept[IllegalArgumentException] {
      RangerServiceProfile.parseCatalogMapping(Some("broken"))
    }
  }

  test("build native StarRocks resources") {
    val catalog = AccessResource.build(profile, CATALOG, null, null, null)
    assert(catalog.getCatalog === "default_catalog")

    val db = AccessResource.build(profile, DATABASE, "db", null, null, catalog = None)
    assert(db.getCatalog === "default_catalog")
    assert(db.getDatabase === "db")

    val table = AccessResource.build(
      profile,
      TABLE,
      "db",
      "tbl",
      null,
      catalog = Some("spark_catalog"))
    assert(table.getCatalog === "lakehouse")
    assert(table.getTable === "tbl")

    val column = AccessResource.build(
      profile,
      COLUMN,
      "db",
      "tbl",
      "c1,c2",
      catalog = Some("spark_catalog"))
    assert(column.getColumn === "c1,c2")

    val view = AccessResource.build(
      profile,
      VIEW,
      "db",
      "v",
      null,
      catalog = Some("spark_catalog"))
    assert(view.getValue("view") === "v")
    assert(view.getTable === null)

    val function = AccessResource.build(
      profile,
      FUNCTION,
      "db",
      "fn",
      null,
      catalog = Some("spark_catalog"))
    assert(function.getValue("function") === "fn")
    assert(function.getValue("udf") === null)
  }

  test("map StarRocks operations to native access and resource level") {
    val cases = Seq(
      (spec(table(columns = Seq("c")), QUERY, isInput = true), COLUMN, "select"),
      (spec(table(INSERT), QUERY), TABLE, "insert"),
      (spec(table(UPDATE), QUERY), TABLE, "update"),
      (spec(table(DELETE), QUERY), TABLE, "delete"),
      (spec(database, CREATEDATABASE), CATALOG, "create database"),
      (spec(table(), CREATETABLE), DATABASE, "create table"),
      (spec(table(), CREATEVIEW), DATABASE, "create view"),
      (spec(function, CREATEFUNCTION), DATABASE, "create function"),
      (spec(database, ALTERDATABASE), DATABASE, "alter"),
      (spec(table(), ALTERTABLE_PROPERTIES), TABLE, "alter"),
      (spec(table(), DROPTABLE), TABLE, "drop"),
      (spec(table(), DROPVIEW), VIEW, "drop"),
      (spec(function, DROPFUNCTION), FUNCTION, "drop"),
      (spec(function, DESCFUNCTION), FUNCTION, "usage"),
      (spec(database, SHOWDATABASES), DATABASE, RangerPolicyEngine.ANY_ACCESS))

    cases.foreach { case (actual, resourceType, access) =>
      assert(actual.objectType === resourceType)
      assert(actual.accessType === access)
      assert(actual.catalog === Some("lakehouse"))
    }
  }

  test("fail closed for resources and operations absent from StarRocks") {
    val uri = PrivilegeObject(DFS_URI, OTHER, "hdfs:///tmp/a", null, Nil, None, None)
    intercept[AccessControlException](profile.authorization(uri, QUERY, isInput = true))
    intercept[AccessControlException](profile.authorization(table(), ADD, isInput = false))
    intercept[AccessControlException](profile.authorization(table(), CREATEINDEX, isInput = false))
    intercept[AccessControlException] {
      profile.accessType(AccessType.NONE, QUERY, TABLE)
    }
  }
}
