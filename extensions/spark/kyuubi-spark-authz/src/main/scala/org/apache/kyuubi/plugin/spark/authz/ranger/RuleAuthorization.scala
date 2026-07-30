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

import scala.collection.mutable

import org.apache.ranger.plugin.policyengine.RangerAccessRequest
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

import org.apache.kyuubi.plugin.spark.authz._
import org.apache.kyuubi.plugin.spark.authz.ObjectType._
import org.apache.kyuubi.plugin.spark.authz.ranger.SparkRangerAdminPlugin._
import org.apache.kyuubi.plugin.spark.authz.rule.Authorization
import org.apache.kyuubi.plugin.spark.authz.util.AuthZUtils._

case class RuleAuthorization(spark: SparkSession) extends Authorization(spark) {
  override def checkPrivileges(spark: SparkSession, plan: LogicalPlan): Unit = {
    val normalizedPlanName = (plan.nodeName + plan.getClass.getSimpleName)
      .replaceAll("[^A-Za-z]", "")
      .toLowerCase(java.util.Locale.ROOT)
    if (SparkRangerAdminPlugin.profile.serviceType == "starrocks" &&
      normalizedPlanName.contains("materializedview")) {
      throw new AccessControlException(
        s"Unsupported authorization operation [${plan.nodeName}] for Ranger service type " +
          "[starrocks]: materialized views are not mapped")
    }

    val auditHandler = new SparkRangerAuditHandler
    val ugi = getAuthzUgi(spark.sparkContext)
    val (inputs, outputs, opType) = PrivilegesBuilder.build(plan, spark)

    // Use a HashSet to deduplicate the same AccessResource and AccessType, the requests will be all
    // the non-duplicate requests and in the same order as the input requests.
    val requests = new mutable.ArrayBuffer[AccessRequest]()
    val requestsSet = new mutable.HashSet[(AccessResource, String)]()

    def addAccessRequest(objects: Iterable[PrivilegeObject], isInput: Boolean): Unit = {
      objects.foreach { obj =>
        SparkRangerAdminPlugin.profile.authorization(obj, opType, isInput).foreach { spec =>
          val resource = AccessResource(spec)
          if (!requestsSet.contains((resource, spec.accessType))) {
            requests += AccessRequest(resource, ugi, opType, spec.accessType)
            requestsSet.add(resource -> spec.accessType)
          }
        }
      }
    }

    addAccessRequest(inputs, isInput = true)
    addAccessRequest(outputs, isInput = false)

    val requestArrays = requests.map { request =>
      val resource = request.getResource.asInstanceOf[AccessResource]
      resource.objectType match {
        case ObjectType.COLUMN if resource.getColumns.nonEmpty =>
          resource.getColumns.map { col =>
            val cr =
              AccessResource(
                COLUMN,
                resource.getDatabase,
                resource.getTable,
                col,
                Option(resource.getOwnerUser),
                resource.catalog)
            AccessRequest(cr, ugi, opType, request.accessType).asInstanceOf[RangerAccessRequest]
          }
        case _ => Seq(request)
      }
    }.toSeq

    if (authorizeInSingleCall) {
      verify(requestArrays.flatten, auditHandler)
    } else {
      requestArrays.flatten.foreach { req =>
        verify(Seq(req), auditHandler)
      }
    }
  }
}
