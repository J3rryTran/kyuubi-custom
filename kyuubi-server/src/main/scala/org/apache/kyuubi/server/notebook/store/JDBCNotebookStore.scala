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

package org.apache.kyuubi.server.notebook.store

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.sql.{Connection, PreparedStatement, ResultSet}
import java.util.stream.Collectors

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import org.apache.kyuubi.{KyuubiException, Logging, Utils}
import org.apache.kyuubi.config.KyuubiConf
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType
import org.apache.kyuubi.server.metadata.jdbc.DatabaseType._
import org.apache.kyuubi.server.metadata.jdbc.JDBCMetadataStoreConf._
import org.apache.kyuubi.server.notebook.api._
import org.apache.kyuubi.util.JdbcUtils
import org.apache.kyuubi.util.reflect.ReflectUtils

/**
 * JDBC-backed notebook store.
 *
 * It deliberately reuses `kyuubi.metadata.store.jdbc.*` so a deployment has a single database to
 * provision and back up; the notebook tables are namespaced by their `notebook_` prefix.
 */
class JDBCNotebookStore(conf: KyuubiConf) extends NotebookStore with Logging {
  import JDBCNotebookStore._

  private val dbType = DatabaseType.withName(conf.get(METADATA_STORE_JDBC_DATABASE_TYPE))

  private val driverClass = {
    val configured = conf.get(METADATA_STORE_JDBC_DRIVER)
    dbType match {
      case SQLITE => configured.getOrElse("org.sqlite.JDBC")
      case MYSQL => configured.getOrElse {
          if (ReflectUtils.isClassLoadable("com.mysql.cj.jdbc.Driver")) {
            "com.mysql.cj.jdbc.Driver"
          } else {
            "com.mysql.jdbc.Driver"
          }
        }
      case POSTGRESQL => configured.getOrElse("org.postgresql.Driver")
      case CUSTOM => configured.getOrElse(
          throw new IllegalArgumentException("No jdbc driver defined"))
    }
  }

  private val hikariConfig = {
    val properties = getMetadataStoreJDBCDataSourceProperties(conf)
    val config = new HikariConfig(properties)
    config.setDriverClassName(driverClass)
    config.setJdbcUrl(getMetadataStoreJdbcUrl(conf))
    config.setUsername(conf.get(METADATA_STORE_JDBC_USER))
    config.setPassword(conf.get(METADATA_STORE_JDBC_PASSWORD))
    config.setPoolName("jdbc-notebook-store-pool")
    config
  }

  implicit private val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  override def initSchema(): Unit = {
    SCHEMA_RESOURCES.foreach(applySchema)
  }

  private def applySchema(name: String): Unit = {
    val resource = s"sql/notebook/${dbType.toString.toLowerCase}/" +
      s"$name-$SCHEMA_VERSION.${dbType.toString.toLowerCase}.sql"
    val classLoader = Utils.getContextOrKyuubiClassLoader
    val stream = classLoader.getResourceAsStream(resource)
    if (stream == null) {
      throw new KyuubiException(s"Notebook schema resource not found: $resource")
    }
    val ddl =
      try {
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .lines().collect(Collectors.joining("\n"))
      } finally {
        stream.close()
      }
    // Comment lines are stripped before splitting so a ';' inside a comment cannot cut a
    // statement in half.
    val statements = ddl.split("\n")
      .filterNot(_.trim.startsWith("--"))
      .mkString("\n")
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
    JdbcUtils.withConnection { connection =>
      statements.foreach { statement =>
        JdbcUtils.withCloseable(connection.prepareStatement(statement))(_.execute())
      }
    }
    info(s"Applied notebook schema $resource")
  }

  override def healthCheck(): Unit = {
    JdbcUtils.executeQuery("SELECT 1 FROM notebook_folder WHERE 1 = 0")()(_ => ())
  }

  override def close(): Unit = dataSource.close()

  private def inTransaction[T](block: Connection => T): T = JdbcUtils.withConnection { conn =>
    val previous = conn.getAutoCommit
    conn.setAutoCommit(false)
    try {
      val result = block(conn)
      conn.commit()
      result
    } catch {
      case e: Throwable =>
        try conn.rollback()
        catch { case rollbackError: Throwable => warn("Rollback failed", rollbackError) }
        throw e
    } finally {
      conn.setAutoCommit(previous)
    }
  }

  private def update(conn: Connection, sql: String)(setParams: PreparedStatement => Unit): Int =
    JdbcUtils.withCloseable(conn.prepareStatement(sql)) { stmt =>
      setParams(stmt)
      stmt.executeUpdate()
    }

  private def query[T](conn: Connection, sql: String)(setParams: PreparedStatement => Unit)(
      rowMapper: ResultSet => T): Seq[T] =
    JdbcUtils.withCloseable(conn.prepareStatement(sql)) { stmt =>
      setParams(stmt)
      JdbcUtils.withCloseable(stmt.executeQuery())(rs => JdbcUtils.mapResultSet(rs)(rowMapper))
    }

  private def toJson(map: Map[String, String]): String =
    if (map == null || map.isEmpty) null else mapper.writeValueAsString(map)

  private def fromJson(json: String): Map[String, String] = {
    if (json == null || json.trim.isEmpty) {
      Map.empty
    } else {
      mapper.readValue(json, classOf[java.util.HashMap[String, String]]).asScala.toMap
    }
  }

  private def optString(rs: ResultSet, column: String): Option[String] =
    Option(rs.getString(column)).filter(_.nonEmpty)

  private def optLong(rs: ResultSet, column: String): Option[Long] = {
    val value = rs.getLong(column)
    if (rs.wasNull()) None else Some(value)
  }

  // ---------------------------------------------------------------------------------------------
  // Folders
  // ---------------------------------------------------------------------------------------------

  private def folderMapper(rs: ResultSet): NotebookFolder = NotebookFolder(
    id = rs.getString("id"),
    parentId = optString(rs, "parent_id"),
    name = rs.getString("name"),
    path = rs.getString("path"),
    owner = rs.getString("owner"),
    createdAt = rs.getLong("created_at"),
    createdBy = rs.getString("created_by"),
    updatedAt = rs.getLong("updated_at"),
    updatedBy = rs.getString("updated_by"),
    version = rs.getLong("version"),
    deleted = rs.getInt("deleted") != 0)

  override def createFolder(folder: NotebookFolder): Unit = {
    val sql =
      """INSERT INTO notebook_folder(id, parent_id, name, path, path_hash, owner, created_at,
        | created_by, updated_at, updated_by, version, deleted)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, folder.id)
      stmt.setString(2, folder.parentId.orNull)
      stmt.setString(3, folder.name)
      stmt.setString(4, folder.path)
      stmt.setString(5, NotebookPaths.hash(folder.path))
      stmt.setString(6, folder.owner)
      stmt.setLong(7, folder.createdAt)
      stmt.setString(8, folder.createdBy)
      stmt.setLong(9, folder.updatedAt)
      stmt.setString(10, folder.updatedBy)
      stmt.setLong(11, folder.version)
      stmt.setInt(12, if (folder.deleted) 1 else 0)
    }
  }

  override def getFolder(id: String): Option[NotebookFolder] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_folder WHERE id = ? AND deleted = 0") { stmt =>
      stmt.setString(1, id)
    }(folderMapper).headOption

  override def getFolderByPathHash(pathHash: String): Option[NotebookFolder] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_folder WHERE path_hash = ? AND deleted = 0") { stmt =>
      stmt.setString(1, pathHash)
    }(folderMapper).headOption

  override def listFolders(filter: FolderFilter): Seq[NotebookFolder] = {
    val conditions = ArrayBuffer("deleted = 0", "owner = ?")
    if (filter.parentIdSpecified) {
      conditions += (if (filter.parentId.isDefined) "parent_id = ?" else "parent_id IS NULL")
    }
    val sql = s"SELECT * FROM notebook_folder WHERE ${conditions.mkString(" AND ")} ORDER BY path"
    JdbcUtils.executeQueryWithRowMapper(sql) { stmt =>
      stmt.setString(1, filter.owner)
      filter.parentId.filter(_ => filter.parentIdSpecified).foreach(stmt.setString(2, _))
    }(folderMapper)
  }

  override def listFoldersUnder(pathPrefix: String, owner: String): Seq[NotebookFolder] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_folder WHERE deleted = 0 AND owner = ? AND path LIKE ? " +
        "ESCAPE '~' ORDER BY path") { stmt =>
      stmt.setString(1, owner)
      stmt.setString(2, escapeLike(pathPrefix) + "/%")
    }(folderMapper)

  override def updateFolder(folder: NotebookFolder, expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE notebook_folder SET parent_id = ?, name = ?, path = ?, path_hash = ?,
        | updated_at = ?, updated_by = ?, version = ? WHERE id = ? AND version = ?
        | AND deleted = 0""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, folder.parentId.orNull)
      stmt.setString(2, folder.name)
      stmt.setString(3, folder.path)
      stmt.setString(4, NotebookPaths.hash(folder.path))
      stmt.setLong(5, folder.updatedAt)
      stmt.setString(6, folder.updatedBy)
      stmt.setLong(7, folder.version)
      stmt.setString(8, folder.id)
      stmt.setLong(9, expectedVersion)
    } == 1
  }

  override def deleteFolder(id: String, expectedVersion: Long, tombstone: Tombstone): Boolean = {
    val sql =
      """UPDATE notebook_folder SET deleted = 1, path = ?, path_hash = ?, updated_at = ?,
        | updated_by = ?, version = version + 1 WHERE id = ? AND version = ?
        | AND deleted = 0""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, tombstone.path)
      stmt.setString(2, tombstone.pathHash)
      stmt.setLong(3, tombstone.updatedAt)
      stmt.setString(4, tombstone.updatedBy)
      stmt.setString(5, id)
      stmt.setLong(6, expectedVersion)
    } == 1
  }

  override def moveFolderSubtree(
      folder: NotebookFolder,
      expectedVersion: Long,
      folderPathUpdates: Seq[PathUpdate],
      notebookPathUpdates: Seq[PathUpdate]): Boolean = inTransaction { conn =>
    val moved = update(
      conn,
      "UPDATE notebook_folder SET parent_id = ?, name = ?, path = ?, path_hash = ?, " +
        "updated_at = ?, updated_by = ?, version = ? WHERE id = ? AND version = ? " +
        "AND deleted = 0") { stmt =>
      stmt.setString(1, folder.parentId.orNull)
      stmt.setString(2, folder.name)
      stmt.setString(3, folder.path)
      stmt.setString(4, NotebookPaths.hash(folder.path))
      stmt.setLong(5, folder.updatedAt)
      stmt.setString(6, folder.updatedBy)
      stmt.setLong(7, folder.version)
      stmt.setString(8, folder.id)
      stmt.setLong(9, expectedVersion)
    }
    if (moved != 1) {
      false
    } else {
      folderPathUpdates.foreach { pathUpdate =>
        update(
          conn,
          "UPDATE notebook_folder SET path = ?, path_hash = ?, version = version + 1 " +
            "WHERE id = ?") { stmt =>
          stmt.setString(1, pathUpdate.path)
          stmt.setString(2, NotebookPaths.hash(pathUpdate.path))
          stmt.setString(3, pathUpdate.id)
        }
      }
      notebookPathUpdates.foreach { pathUpdate =>
        update(
          conn,
          "UPDATE notebook SET path = ?, path_hash = ?, version = version + 1 " +
            "WHERE id = ?") { stmt =>
          stmt.setString(1, pathUpdate.path)
          stmt.setString(2, NotebookPaths.hash(pathUpdate.path))
          stmt.setString(3, pathUpdate.id)
        }
      }
      true
    }
  }

  override def deleteFolderSubtree(
      folder: NotebookFolder,
      expectedVersion: Long,
      descendantFolders: Seq[NotebookFolder],
      descendantNotebooks: Seq[Notebook],
      updatedBy: String,
      now: Long): Boolean = inTransaction { conn =>
    val deleteFolderSql =
      "UPDATE notebook_folder SET deleted = 1, path = ?, path_hash = ?, updated_at = ?, " +
        "updated_by = ?, version = version + 1 WHERE id = ? AND deleted = 0"
    val deleted = update(
      conn,
      deleteFolderSql + " AND version = ?") { stmt =>
      val tombstone = NotebookPaths.tombstonePath(folder.path, folder.id)
      stmt.setString(1, tombstone)
      stmt.setString(2, NotebookPaths.hash(tombstone))
      stmt.setLong(3, now)
      stmt.setString(4, updatedBy)
      stmt.setString(5, folder.id)
      stmt.setLong(6, expectedVersion)
    }
    if (deleted != 1) {
      false
    } else {
      descendantFolders.foreach { descendant =>
        update(conn, deleteFolderSql) { stmt =>
          val tombstone = NotebookPaths.tombstonePath(descendant.path, descendant.id)
          stmt.setString(1, tombstone)
          stmt.setString(2, NotebookPaths.hash(tombstone))
          stmt.setLong(3, now)
          stmt.setString(4, updatedBy)
          stmt.setString(5, descendant.id)
        }
      }
      descendantNotebooks.foreach { descendant =>
        update(
          conn,
          "UPDATE notebook SET deleted = 1, path = ?, path_hash = ?, updated_at = ?, " +
            "updated_by = ?, version = version + 1 WHERE id = ? AND deleted = 0") { stmt =>
          val tombstone = NotebookPaths.tombstonePath(descendant.path, descendant.id)
          stmt.setString(1, tombstone)
          stmt.setString(2, NotebookPaths.hash(tombstone))
          stmt.setLong(3, now)
          stmt.setString(4, updatedBy)
          stmt.setString(5, descendant.id)
        }
      }
      true
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Notebooks
  // ---------------------------------------------------------------------------------------------

  private def notebookMapper(rs: ResultSet): Notebook = Notebook(
    id = rs.getString("id"),
    folderId = optString(rs, "folder_id"),
    path = rs.getString("path"),
    name = rs.getString("name"),
    description = optString(rs, "description"),
    owner = rs.getString("owner"),
    defaultCatalog = optString(rs, "default_catalog"),
    defaultSchema = optString(rs, "default_schema"),
    runtimeProfile = optString(rs, "runtime_profile"),
    formatVersion = rs.getInt("format_version"),
    createdAt = rs.getLong("created_at"),
    createdBy = rs.getString("created_by"),
    updatedAt = rs.getLong("updated_at"),
    updatedBy = rs.getString("updated_by"),
    version = rs.getLong("version"),
    deleted = rs.getInt("deleted") != 0)

  private val insertNotebookSql =
    """INSERT INTO notebook(id, folder_id, path, path_hash, name, description, owner,
      | default_catalog, default_schema, runtime_profile, format_version, created_at, created_by,
      | updated_at, updated_by, version, deleted)
      | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin

  private def bindNotebook(stmt: PreparedStatement, notebook: Notebook): Unit = {
    stmt.setString(1, notebook.id)
    stmt.setString(2, notebook.folderId.orNull)
    stmt.setString(3, notebook.path)
    stmt.setString(4, NotebookPaths.hash(notebook.path))
    stmt.setString(5, notebook.name)
    stmt.setString(6, notebook.description.orNull)
    stmt.setString(7, notebook.owner)
    stmt.setString(8, notebook.defaultCatalog.orNull)
    stmt.setString(9, notebook.defaultSchema.orNull)
    stmt.setString(10, notebook.runtimeProfile.orNull)
    stmt.setInt(11, notebook.formatVersion)
    stmt.setLong(12, notebook.createdAt)
    stmt.setString(13, notebook.createdBy)
    stmt.setLong(14, notebook.updatedAt)
    stmt.setString(15, notebook.updatedBy)
    stmt.setLong(16, notebook.version)
    stmt.setInt(17, if (notebook.deleted) 1 else 0)
  }

  private val insertCellSql =
    """INSERT INTO notebook_cell(id, notebook_id, cell_position, cell_type, language, source,
      | metadata, configuration, created_at, updated_at, version)
      | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin

  private def bindCell(stmt: PreparedStatement, cell: NotebookCell): Unit = {
    stmt.setString(1, cell.id)
    stmt.setString(2, cell.notebookId)
    stmt.setInt(3, cell.position)
    stmt.setString(4, cell.cellType.toString)
    stmt.setString(5, cell.language.toString)
    stmt.setString(6, cell.source)
    stmt.setString(7, toJson(cell.metadata))
    stmt.setString(8, toJson(cell.configuration))
    stmt.setLong(9, cell.createdAt)
    stmt.setLong(10, cell.updatedAt)
    stmt.setLong(11, cell.version)
  }

  override def createNotebook(notebook: Notebook, cells: Seq[NotebookCell]): Unit =
    inTransaction { conn =>
      update(conn, insertNotebookSql)(bindNotebook(_, notebook))
      cells.foreach(cell => update(conn, insertCellSql)(bindCell(_, cell)))
    }

  override def getNotebook(id: String): Option[Notebook] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook WHERE id = ? AND deleted = 0") { stmt =>
      stmt.setString(1, id)
    }(notebookMapper).headOption

  override def getNotebookByPathHash(pathHash: String): Option[Notebook] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook WHERE path_hash = ? AND deleted = 0") { stmt =>
      stmt.setString(1, pathHash)
    }(notebookMapper).headOption

  override def listNotebooks(filter: NotebookFilter): Seq[Notebook] = {
    val conditions = ArrayBuffer[String]("n.deleted = 0")
    val params = ArrayBuffer[(PreparedStatement, Int) => Unit]()

    filter.accessibleTo.foreach { user =>
      conditions += "(n.owner = ? OR EXISTS (SELECT 1 FROM notebook_permission p " +
        "WHERE p.notebook_id = n.id AND p.principal_type = 'USER' AND p.principal_id = ?))"
      params += ((stmt, i) => stmt.setString(i, user))
      params += ((stmt, i) => stmt.setString(i, user))
    }
    filter.owner.foreach { owner =>
      conditions += "n.owner = ?"
      params += ((stmt, i) => stmt.setString(i, owner))
    }
    filter.folderId.foreach { folderId =>
      conditions += "n.folder_id = ?"
      params += ((stmt, i) => stmt.setString(i, folderId))
    }
    filter.nameContains.foreach { name =>
      conditions += "n.name LIKE ? ESCAPE '~'"
      params += ((stmt, i) => stmt.setString(i, "%" + escapeLike(name) + "%"))
    }
    filter.search.foreach { term =>
      conditions += "(n.name LIKE ? ESCAPE '~' OR n.description LIKE ? ESCAPE '~' " +
        "OR EXISTS (SELECT 1 FROM notebook_cell c WHERE c.notebook_id = n.id " +
        "AND c.source LIKE ? ESCAPE '~'))"
      val like = "%" + escapeLike(term) + "%"
      params += ((stmt, i) => stmt.setString(i, like))
      params += ((stmt, i) => stmt.setString(i, like))
      params += ((stmt, i) => stmt.setString(i, like))
    }
    filter.language.foreach { language =>
      conditions += "EXISTS (SELECT 1 FROM notebook_cell c WHERE c.notebook_id = n.id " +
        "AND c.language = ?)"
      params += ((stmt, i) => stmt.setString(i, language))
    }
    filter.afterPath.foreach { path =>
      conditions += "n.path > ?"
      params += ((stmt, i) => stmt.setString(i, path))
    }
    val sql = s"SELECT n.* FROM notebook n WHERE ${conditions.mkString(" AND ")} " +
      s"ORDER BY n.path LIMIT ${filter.limit}"
    JdbcUtils.executeQueryWithRowMapper(sql) { stmt =>
      params.zipWithIndex.foreach { case (binder, index) => binder(stmt, index + 1) }
    }(notebookMapper)
  }

  override def listNotebooksUnder(pathPrefix: String, owner: String): Seq[Notebook] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook WHERE deleted = 0 AND owner = ? AND path LIKE ? " +
        "ESCAPE '~' ORDER BY path") { stmt =>
      stmt.setString(1, owner)
      stmt.setString(2, escapeLike(pathPrefix) + "/%")
    }(notebookMapper)

  override def updateNotebook(notebook: Notebook, expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE notebook SET folder_id = ?, path = ?, path_hash = ?, name = ?, description = ?,
        | default_catalog = ?, default_schema = ?, runtime_profile = ?, updated_at = ?,
        | updated_by = ?, version = ? WHERE id = ? AND version = ? AND deleted = 0""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, notebook.folderId.orNull)
      stmt.setString(2, notebook.path)
      stmt.setString(3, NotebookPaths.hash(notebook.path))
      stmt.setString(4, notebook.name)
      stmt.setString(5, notebook.description.orNull)
      stmt.setString(6, notebook.defaultCatalog.orNull)
      stmt.setString(7, notebook.defaultSchema.orNull)
      stmt.setString(8, notebook.runtimeProfile.orNull)
      stmt.setLong(9, notebook.updatedAt)
      stmt.setString(10, notebook.updatedBy)
      stmt.setLong(11, notebook.version)
      stmt.setString(12, notebook.id)
      stmt.setLong(13, expectedVersion)
    } == 1
  }

  override def deleteNotebook(id: String, expectedVersion: Long, tombstone: Tombstone): Boolean = {
    val sql =
      """UPDATE notebook SET deleted = 1, path = ?, path_hash = ?, updated_at = ?, updated_by = ?,
        | version = version + 1 WHERE id = ? AND version = ? AND deleted = 0""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, tombstone.path)
      stmt.setString(2, tombstone.pathHash)
      stmt.setLong(3, tombstone.updatedAt)
      stmt.setString(4, tombstone.updatedBy)
      stmt.setString(5, id)
      stmt.setLong(6, expectedVersion)
    } == 1
  }

  // ---------------------------------------------------------------------------------------------
  // Cells
  // ---------------------------------------------------------------------------------------------

  private def cellMapper(rs: ResultSet): NotebookCell = NotebookCell(
    id = rs.getString("id"),
    notebookId = rs.getString("notebook_id"),
    position = rs.getInt("cell_position"),
    cellType = CellType.withName(rs.getString("cell_type")),
    language = CellLanguage.withName(rs.getString("language")),
    source = rs.getString("source"),
    metadata = fromJson(rs.getString("metadata")),
    configuration = fromJson(rs.getString("configuration")),
    createdAt = rs.getLong("created_at"),
    updatedAt = rs.getLong("updated_at"),
    version = rs.getLong("version"))

  override def listCells(notebookId: String): Seq[NotebookCell] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_cell WHERE notebook_id = ? ORDER BY cell_position") { stmt =>
      stmt.setString(1, notebookId)
    }(cellMapper)

  override def getCell(notebookId: String, cellId: String): Option[NotebookCell] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_cell WHERE notebook_id = ? AND id = ?") { stmt =>
      stmt.setString(1, notebookId)
      stmt.setString(2, cellId)
    }(cellMapper).headOption

  override def countCells(notebookId: String): Int =
    JdbcUtils.executeQuery(
      "SELECT COUNT(*) AS total FROM notebook_cell WHERE notebook_id = ?") { stmt =>
      stmt.setString(1, notebookId)
    } { rs => if (rs.next()) rs.getInt("total") else 0 }

  /** Bumps the notebook row, failing the whole transaction when it moved on. */
  private def touchNotebook(
      conn: Connection,
      notebookId: String,
      expectedVersion: Long,
      updatedBy: String,
      now: Long): Boolean = {
    update(
      conn,
      "UPDATE notebook SET version = version + 1, updated_at = ?, updated_by = ? " +
        "WHERE id = ? AND version = ? AND deleted = 0") { stmt =>
      stmt.setLong(1, now)
      stmt.setString(2, updatedBy)
      stmt.setString(3, notebookId)
      stmt.setLong(4, expectedVersion)
    } == 1
  }

  override def insertCell(
      cell: NotebookCell,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean = inTransaction { conn =>
    if (!touchNotebook(conn, cell.notebookId, notebookVersion, updatedBy, now)) {
      false
    } else {
      update(
        conn,
        "UPDATE notebook_cell SET cell_position = cell_position + 1 " +
          "WHERE notebook_id = ? AND cell_position >= ?") { stmt =>
        stmt.setString(1, cell.notebookId)
        stmt.setInt(2, cell.position)
      }
      update(conn, insertCellSql)(bindCell(_, cell))
      true
    }
  }

  override def updateCell(
      cell: NotebookCell,
      expectedVersion: Long,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean = inTransaction { conn =>
    if (!touchNotebook(conn, cell.notebookId, notebookVersion, updatedBy, now)) {
      false
    } else {
      update(
        conn,
        "UPDATE notebook_cell SET cell_type = ?, language = ?, source = ?, metadata = ?, " +
          "configuration = ?, updated_at = ?, version = ? WHERE id = ? AND notebook_id = ? " +
          "AND version = ?") { stmt =>
        stmt.setString(1, cell.cellType.toString)
        stmt.setString(2, cell.language.toString)
        stmt.setString(3, cell.source)
        stmt.setString(4, toJson(cell.metadata))
        stmt.setString(5, toJson(cell.configuration))
        stmt.setLong(6, cell.updatedAt)
        stmt.setLong(7, cell.version)
        stmt.setString(8, cell.id)
        stmt.setString(9, cell.notebookId)
        stmt.setLong(10, expectedVersion)
      } == 1
    }
  }

  override def deleteCell(
      notebookId: String,
      cellId: String,
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean = inTransaction { conn =>
    if (!touchNotebook(conn, notebookId, notebookVersion, updatedBy, now)) {
      false
    } else {
      val positions = query(
        conn,
        "SELECT cell_position FROM notebook_cell WHERE notebook_id = ? AND id = ?") { stmt =>
        stmt.setString(1, notebookId)
        stmt.setString(2, cellId)
      }(_.getInt("cell_position"))
      if (positions.isEmpty) {
        false
      } else {
        update(conn, "DELETE FROM notebook_cell WHERE notebook_id = ? AND id = ?") { stmt =>
          stmt.setString(1, notebookId)
          stmt.setString(2, cellId)
        }
        update(
          conn,
          "UPDATE notebook_cell SET cell_position = cell_position - 1 " +
            "WHERE notebook_id = ? AND cell_position > ?") { stmt =>
          stmt.setString(1, notebookId)
          stmt.setInt(2, positions.head)
        }
        true
      }
    }
  }

  override def reorderCells(
      notebookId: String,
      orderedCellIds: Seq[String],
      notebookVersion: Long,
      updatedBy: String,
      now: Long): Boolean = inTransaction { conn =>
    if (!touchNotebook(conn, notebookId, notebookVersion, updatedBy, now)) {
      false
    } else {
      // Two passes with an offset, because a direct assignment would transiently collide with
      // positions that have not been rewritten yet.
      val offset = orderedCellIds.size + 1
      orderedCellIds.zipWithIndex.foreach { case (cellId, index) =>
        update(
          conn,
          "UPDATE notebook_cell SET cell_position = ? WHERE notebook_id = ? AND id = ?") { stmt =>
          stmt.setInt(1, index + offset)
          stmt.setString(2, notebookId)
          stmt.setString(3, cellId)
        }
      }
      update(
        conn,
        "UPDATE notebook_cell SET cell_position = cell_position - ? WHERE notebook_id = ?") {
        stmt =>
          stmt.setInt(1, offset)
          stmt.setString(2, notebookId)
      }
      true
    }
  }

  override def replaceCells(
      notebookId: String,
      cells: Seq[NotebookCell],
      notebook: Notebook,
      expectedVersion: Long): Boolean = inTransaction { conn =>
    val updated = update(
      conn,
      "UPDATE notebook SET name = ?, description = ?, default_catalog = ?, default_schema = ?, " +
        "runtime_profile = ?, updated_at = ?, updated_by = ?, version = ? " +
        "WHERE id = ? AND version = ? AND deleted = 0") { stmt =>
      stmt.setString(1, notebook.name)
      stmt.setString(2, notebook.description.orNull)
      stmt.setString(3, notebook.defaultCatalog.orNull)
      stmt.setString(4, notebook.defaultSchema.orNull)
      stmt.setString(5, notebook.runtimeProfile.orNull)
      stmt.setLong(6, notebook.updatedAt)
      stmt.setString(7, notebook.updatedBy)
      stmt.setLong(8, notebook.version)
      stmt.setString(9, notebookId)
      stmt.setLong(10, expectedVersion)
    }
    if (updated != 1) {
      false
    } else {
      update(conn, "DELETE FROM notebook_cell WHERE notebook_id = ?")(_.setString(1, notebookId))
      cells.foreach(cell => update(conn, insertCellSql)(bindCell(_, cell)))
      true
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Revisions
  // ---------------------------------------------------------------------------------------------

  private def revisionMapper(rs: ResultSet): NotebookRevision = NotebookRevision(
    id = rs.getString("id"),
    notebookId = rs.getString("notebook_id"),
    revisionNumber = rs.getLong("revision_number"),
    documentSnapshot = rs.getString("document_snapshot"),
    createdAt = rs.getLong("created_at"),
    createdBy = rs.getString("created_by"),
    reason = optString(rs, "reason"),
    protectedRevision = rs.getInt("protected_revision") != 0)

  override def createRevision(revision: NotebookRevision): Unit = {
    val sql =
      """INSERT INTO notebook_revision(id, notebook_id, revision_number, document_snapshot,
        | created_at, created_by, reason, protected_revision)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, revision.id)
      stmt.setString(2, revision.notebookId)
      stmt.setLong(3, revision.revisionNumber)
      stmt.setString(4, revision.documentSnapshot)
      stmt.setLong(5, revision.createdAt)
      stmt.setString(6, revision.createdBy)
      stmt.setString(7, revision.reason.orNull)
      stmt.setInt(8, if (revision.protectedRevision) 1 else 0)
    }
  }

  override def listRevisions(
      notebookId: String,
      limit: Int,
      afterRevisionNumber: Option[Long]): Seq[NotebookRevision] = {
    val condition = afterRevisionNumber.map(_ => " AND revision_number < ?").getOrElse("")
    val sql = s"SELECT * FROM notebook_revision WHERE notebook_id = ?$condition " +
      s"ORDER BY revision_number DESC LIMIT $limit"
    JdbcUtils.executeQueryWithRowMapper(sql) { stmt =>
      stmt.setString(1, notebookId)
      afterRevisionNumber.foreach(stmt.setLong(2, _))
    }(revisionMapper)
  }

  override def getRevision(notebookId: String, revisionNumber: Long): Option[NotebookRevision] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_revision WHERE notebook_id = ? AND revision_number = ?") { stmt =>
      stmt.setString(1, notebookId)
      stmt.setLong(2, revisionNumber)
    }(revisionMapper).headOption

  override def nextRevisionNumber(notebookId: String): Long =
    JdbcUtils.executeQuery(
      "SELECT MAX(revision_number) AS current FROM notebook_revision WHERE notebook_id = ?") {
      stmt => stmt.setString(1, notebookId)
    } { rs => if (rs.next()) rs.getLong("current") + 1 else 1L }

  override def deleteRevision(notebookId: String, revisionNumber: Long): Boolean =
    JdbcUtils.executeUpdate(
      "DELETE FROM notebook_revision WHERE notebook_id = ? AND revision_number = ? " +
        "AND protected_revision = 0") { stmt =>
      stmt.setString(1, notebookId)
      stmt.setLong(2, revisionNumber)
    } == 1

  override def trimRevisions(notebookId: String, keep: Int): Int = inTransaction { conn =>
    val keepable = query(
      conn,
      "SELECT revision_number FROM notebook_revision WHERE notebook_id = ? " +
        "AND protected_revision = 0 ORDER BY revision_number DESC") { stmt =>
      stmt.setString(1, notebookId)
    }(_.getLong("revision_number"))
    val doomed = keepable.drop(keep)
    doomed.foreach { number =>
      update(
        conn,
        "DELETE FROM notebook_revision WHERE notebook_id = ? AND revision_number = ?") { stmt =>
        stmt.setString(1, notebookId)
        stmt.setLong(2, number)
      }
    }
    doomed.size
  }

  // ---------------------------------------------------------------------------------------------
  // Permissions
  // ---------------------------------------------------------------------------------------------

  private def permissionMapper(rs: ResultSet): NotebookPermission = NotebookPermission(
    notebookId = rs.getString("notebook_id"),
    principalType = PrincipalType.withName(rs.getString("principal_type")),
    principalId = rs.getString("principal_id"),
    role = PermissionRole.withName(rs.getString("principal_role")),
    createdAt = rs.getLong("created_at"),
    createdBy = rs.getString("created_by"))

  override def listPermissions(notebookId: String): Seq[NotebookPermission] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_permission WHERE notebook_id = ? ORDER BY principal_id") { stmt =>
      stmt.setString(1, notebookId)
    }(permissionMapper)

  override def replacePermissions(
      notebookId: String,
      permissions: Seq[NotebookPermission]): Unit = inTransaction { conn =>
    update(conn, "DELETE FROM notebook_permission WHERE notebook_id = ?") {
      _.setString(1, notebookId)
    }
    permissions.foreach { permission =>
      update(
        conn,
        "INSERT INTO notebook_permission(notebook_id, principal_type, principal_id, " +
          "principal_role, created_at, created_by) VALUES(?, ?, ?, ?, ?, ?)") { stmt =>
        stmt.setString(1, permission.notebookId)
        stmt.setString(2, permission.principalType.toString)
        stmt.setString(3, permission.principalId)
        stmt.setString(4, permission.role.toString)
        stmt.setLong(5, permission.createdAt)
        stmt.setString(6, permission.createdBy)
      }
    }
  }

  override def deletePermissions(notebookId: String): Unit =
    JdbcUtils.executeUpdate("DELETE FROM notebook_permission WHERE notebook_id = ?") {
      _.setString(1, notebookId)
    }

  // ---------------------------------------------------------------------------------------------
  // Schedules
  // ---------------------------------------------------------------------------------------------

  private def scheduleMapper(rs: ResultSet): NotebookSchedule = NotebookSchedule(
    id = rs.getString("id"),
    notebookId = rs.getString("notebook_id"),
    cronExpression = rs.getString("cron_expression"),
    timezone = rs.getString("timezone"),
    enabled = rs.getInt("enabled") != 0,
    runtimeProfile = optString(rs, "runtime_profile"),
    failurePolicy = FailurePolicy.withName(rs.getString("failure_policy")),
    overlapPolicy = OverlapPolicy.withName(rs.getString("overlap_policy")),
    lastRunAt = optLong(rs, "last_run_at"),
    nextRunAt = optLong(rs, "next_run_at"),
    createdAt = rs.getLong("created_at"),
    createdBy = rs.getString("created_by"),
    updatedAt = rs.getLong("updated_at"),
    updatedBy = rs.getString("updated_by"),
    version = rs.getLong("version"))

  override def getSchedule(notebookId: String): Option[NotebookSchedule] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_schedule WHERE notebook_id = ?") { stmt =>
      stmt.setString(1, notebookId)
    }(scheduleMapper).headOption

  override def upsertSchedule(
      schedule: NotebookSchedule,
      expectedVersion: Option[Long]): Boolean = expectedVersion match {
    case None =>
      val sql =
        """INSERT INTO notebook_schedule(id, notebook_id, cron_expression, timezone, enabled,
          | runtime_profile, failure_policy, overlap_policy, last_run_at, next_run_at, created_at,
          | created_by, updated_at, updated_by, version)
          | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
      JdbcUtils.executeUpdate(sql) { stmt =>
        stmt.setString(1, schedule.id)
        stmt.setString(2, schedule.notebookId)
        stmt.setString(3, schedule.cronExpression)
        stmt.setString(4, schedule.timezone)
        stmt.setInt(5, if (schedule.enabled) 1 else 0)
        stmt.setString(6, schedule.runtimeProfile.orNull)
        stmt.setString(7, schedule.failurePolicy.toString)
        stmt.setString(8, schedule.overlapPolicy.toString)
        setNullableLong(stmt, 9, schedule.lastRunAt)
        setNullableLong(stmt, 10, schedule.nextRunAt)
        stmt.setLong(11, schedule.createdAt)
        stmt.setString(12, schedule.createdBy)
        stmt.setLong(13, schedule.updatedAt)
        stmt.setString(14, schedule.updatedBy)
        stmt.setLong(15, schedule.version)
      } == 1
    case Some(version) =>
      val sql =
        """UPDATE notebook_schedule SET cron_expression = ?, timezone = ?, enabled = ?,
          | runtime_profile = ?, failure_policy = ?, overlap_policy = ?, next_run_at = ?,
          | updated_at = ?, updated_by = ?, version = ? WHERE notebook_id = ?
          | AND version = ?""".stripMargin
      JdbcUtils.executeUpdate(sql) { stmt =>
        stmt.setString(1, schedule.cronExpression)
        stmt.setString(2, schedule.timezone)
        stmt.setInt(3, if (schedule.enabled) 1 else 0)
        stmt.setString(4, schedule.runtimeProfile.orNull)
        stmt.setString(5, schedule.failurePolicy.toString)
        stmt.setString(6, schedule.overlapPolicy.toString)
        setNullableLong(stmt, 7, schedule.nextRunAt)
        stmt.setLong(8, schedule.updatedAt)
        stmt.setString(9, schedule.updatedBy)
        stmt.setLong(10, schedule.version)
        stmt.setString(11, schedule.notebookId)
        stmt.setLong(12, version)
      } == 1
  }

  override def deleteSchedule(notebookId: String): Boolean =
    JdbcUtils.executeUpdate("DELETE FROM notebook_schedule WHERE notebook_id = ?") {
      _.setString(1, notebookId)
    } == 1

  override def listEnabledSchedules(): Seq[NotebookSchedule] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_schedule WHERE enabled = 1")(_ => ())(scheduleMapper)

  // ---------------------------------------------------------------------------------------------
  // Sessions
  // ---------------------------------------------------------------------------------------------

  private def sessionMapper(rs: ResultSet): NotebookSession = NotebookSession(
    id = rs.getString("id"),
    notebookId = rs.getString("notebook_id"),
    owner = rs.getString("owner"),
    state = NotebookSessionState.withName(rs.getString("state")),
    runtimeProfile = optString(rs, "runtime_profile"),
    createdAt = rs.getLong("created_at"),
    lastActivityAt = rs.getLong("last_activity_at"),
    stoppedAt = optLong(rs, "stopped_at"),
    failureMessage = optString(rs, "failure_message"),
    kyuubiInstance = optString(rs, "kyuubi_instance"),
    version = rs.getLong("version"))

  override def createSession(session: NotebookSession): Unit = {
    val sql =
      """INSERT INTO notebook_session(id, notebook_id, owner, state, runtime_profile, created_at,
        | last_activity_at, stopped_at, failure_message, kyuubi_instance, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, session.id)
      stmt.setString(2, session.notebookId)
      stmt.setString(3, session.owner)
      stmt.setString(4, session.state.toString)
      stmt.setString(5, session.runtimeProfile.orNull)
      stmt.setLong(6, session.createdAt)
      stmt.setLong(7, session.lastActivityAt)
      setNullableLong(stmt, 8, session.stoppedAt)
      stmt.setString(9, session.failureMessage.orNull)
      stmt.setString(10, session.kyuubiInstance.orNull)
      stmt.setLong(11, session.version)
    }
  }

  override def getSession(id: String): Option[NotebookSession] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM notebook_session WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(sessionMapper).headOption

  override def listSessions(notebookId: String): Seq[NotebookSession] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_session WHERE notebook_id = ? ORDER BY created_at DESC") { stmt =>
      stmt.setString(1, notebookId)
    }(sessionMapper)

  override def listLiveSessions(): Seq[NotebookSession] = {
    val terminal = NotebookSessionState.terminal.map(state => s"'$state'").mkString(", ")
    JdbcUtils.executeQueryWithRowMapper(
      s"SELECT * FROM notebook_session WHERE state NOT IN ($terminal)")(_ => ())(sessionMapper)
  }

  override def updateSession(session: NotebookSession, expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE notebook_session SET state = ?, runtime_profile = ?, last_activity_at = ?,
        | stopped_at = ?, failure_message = ?, kyuubi_instance = ?, version = ?
        | WHERE id = ? AND version = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, session.state.toString)
      stmt.setString(2, session.runtimeProfile.orNull)
      stmt.setLong(3, session.lastActivityAt)
      setNullableLong(stmt, 4, session.stoppedAt)
      stmt.setString(5, session.failureMessage.orNull)
      stmt.setString(6, session.kyuubiInstance.orNull)
      stmt.setLong(7, session.version)
      stmt.setString(8, session.id)
      stmt.setLong(9, expectedVersion)
    } == 1
  }

  // ---------------------------------------------------------------------------------------------
  // Runtimes
  // ---------------------------------------------------------------------------------------------

  private def runtimeMapper(rs: ResultSet): NotebookRuntime = NotebookRuntime(
    id = rs.getString("id"),
    notebookSessionId = rs.getString("notebook_session_id"),
    runtimeSpecId = rs.getString("runtime_spec_id"),
    runtimeType = rs.getString("runtime_type"),
    language = CellLanguage.withName(rs.getString("language")),
    owner = rs.getString("owner"),
    state = RuntimeState.withName(rs.getString("state")),
    generation = rs.getLong("generation"),
    environmentRevisionId = optString(rs, "environment_revision_id"),
    createdAt = rs.getLong("created_at"),
    lastActivityAt = rs.getLong("last_activity_at"),
    stoppedAt = optLong(rs, "stopped_at"),
    failureMessage = optString(rs, "failure_message"),
    internalRuntimeHandle = optString(rs, "internal_runtime_handle"),
    internalRuntimeLocation = optString(rs, "internal_runtime_location"),
    version = rs.getLong("version"))

  override def createRuntime(runtime: NotebookRuntime): Unit = {
    val sql =
      """INSERT INTO notebook_runtime(id, notebook_session_id, runtime_spec_id, runtime_type,
        | language, owner, state, generation, environment_revision_id, created_at,
        | last_activity_at, stopped_at, failure_message, internal_runtime_handle,
        | internal_runtime_location, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, runtime.id)
      stmt.setString(2, runtime.notebookSessionId)
      stmt.setString(3, runtime.runtimeSpecId)
      stmt.setString(4, runtime.runtimeType)
      stmt.setString(5, runtime.language.toString)
      stmt.setString(6, runtime.owner)
      stmt.setString(7, runtime.state.toString)
      stmt.setLong(8, runtime.generation)
      stmt.setString(9, runtime.environmentRevisionId.orNull)
      stmt.setLong(10, runtime.createdAt)
      stmt.setLong(11, runtime.lastActivityAt)
      setNullableLong(stmt, 12, runtime.stoppedAt)
      stmt.setString(13, runtime.failureMessage.orNull)
      stmt.setString(14, runtime.internalRuntimeHandle.orNull)
      stmt.setString(15, runtime.internalRuntimeLocation.orNull)
      stmt.setLong(16, runtime.version)
    }
  }

  override def getRuntime(id: String): Option[NotebookRuntime] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM notebook_runtime WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(runtimeMapper).headOption

  override def listRuntimes(notebookSessionId: String): Seq[NotebookRuntime] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_runtime WHERE notebook_session_id = ? ORDER BY created_at") { stmt =>
      stmt.setString(1, notebookSessionId)
    }(runtimeMapper)

  override def updateRuntime(runtime: NotebookRuntime, expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE notebook_runtime SET state = ?, generation = ?, environment_revision_id = ?,
        | last_activity_at = ?, stopped_at = ?, failure_message = ?, internal_runtime_handle = ?,
        | internal_runtime_location = ?, version = ? WHERE id = ? AND version = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, runtime.state.toString)
      stmt.setLong(2, runtime.generation)
      stmt.setString(3, runtime.environmentRevisionId.orNull)
      stmt.setLong(4, runtime.lastActivityAt)
      setNullableLong(stmt, 5, runtime.stoppedAt)
      stmt.setString(6, runtime.failureMessage.orNull)
      stmt.setString(7, runtime.internalRuntimeHandle.orNull)
      stmt.setString(8, runtime.internalRuntimeLocation.orNull)
      stmt.setLong(9, runtime.version)
      stmt.setString(10, runtime.id)
      stmt.setLong(11, expectedVersion)
    } == 1
  }

  // ---------------------------------------------------------------------------------------------
  // Executions
  // ---------------------------------------------------------------------------------------------

  private def executionMapper(rs: ResultSet): CellExecution = CellExecution(
    id = rs.getString("id"),
    notebookId = rs.getString("notebook_id"),
    notebookSessionId = rs.getString("notebook_session_id"),
    runtimeId = rs.getString("runtime_id"),
    runtimeGeneration = rs.getLong("runtime_generation"),
    cellId = optString(rs, "cell_id"),
    cellVersion = optLong(rs, "cell_version"),
    language = CellLanguage.withName(rs.getString("language")),
    sourceSnapshot = rs.getString("source_snapshot"),
    state = ExecutionState.withName(rs.getString("state")),
    submittedAt = rs.getLong("submitted_at"),
    startedAt = optLong(rs, "started_at"),
    finishedAt = optLong(rs, "finished_at"),
    submittedBy = rs.getString("submitted_by"),
    errorCode = optString(rs, "error_code"),
    errorMessage = optString(rs, "error_message"),
    clientRequestId = optString(rs, "client_request_id"),
    notebookRunId = optString(rs, "notebook_run_id"),
    internalOperationHandle = optString(rs, "internal_operation_handle"),
    version = rs.getLong("version"))

  override def createExecution(execution: CellExecution): Unit = {
    val sql =
      """INSERT INTO notebook_execution(id, notebook_id, notebook_session_id, runtime_id,
        | runtime_generation, cell_id, cell_version, language, source_snapshot, state,
        | submitted_at, started_at, finished_at, submitted_by, error_code, error_message,
        | client_request_id, notebook_run_id, internal_operation_handle, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, execution.id)
      stmt.setString(2, execution.notebookId)
      stmt.setString(3, execution.notebookSessionId)
      stmt.setString(4, execution.runtimeId)
      stmt.setLong(5, execution.runtimeGeneration)
      stmt.setString(6, execution.cellId.orNull)
      setNullableLong(stmt, 7, execution.cellVersion)
      stmt.setString(8, execution.language.toString)
      stmt.setString(9, execution.sourceSnapshot)
      stmt.setString(10, execution.state.toString)
      stmt.setLong(11, execution.submittedAt)
      setNullableLong(stmt, 12, execution.startedAt)
      setNullableLong(stmt, 13, execution.finishedAt)
      stmt.setString(14, execution.submittedBy)
      stmt.setString(15, execution.errorCode.orNull)
      stmt.setString(16, execution.errorMessage.orNull)
      stmt.setString(17, execution.clientRequestId.orNull)
      stmt.setString(18, execution.notebookRunId.orNull)
      stmt.setString(19, execution.internalOperationHandle.orNull)
      stmt.setLong(20, execution.version)
    }
  }

  override def getExecution(id: String): Option[CellExecution] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM notebook_execution WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(executionMapper).headOption

  override def findExecutionByRequestId(
      submittedBy: String,
      clientRequestId: String): Option[CellExecution] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_execution WHERE submitted_by = ? AND client_request_id = ?") {
      stmt =>
        stmt.setString(1, submittedBy)
        stmt.setString(2, clientRequestId)
    }(executionMapper).headOption

  override def listExecutions(filter: ExecutionFilter): Seq[CellExecution] = {
    val conditions = ArrayBuffer[String]()
    val params = ArrayBuffer[(PreparedStatement, Int) => Unit]()
    filter.notebookId.foreach { value =>
      conditions += "notebook_id = ?"
      params += ((stmt, i) => stmt.setString(i, value))
    }
    filter.notebookSessionId.foreach { value =>
      conditions += "notebook_session_id = ?"
      params += ((stmt, i) => stmt.setString(i, value))
    }
    filter.runtimeId.foreach { value =>
      conditions += "runtime_id = ?"
      params += ((stmt, i) => stmt.setString(i, value))
    }
    filter.notebookRunId.foreach { value =>
      conditions += "notebook_run_id = ?"
      params += ((stmt, i) => stmt.setString(i, value))
    }
    if (filter.states.nonEmpty) {
      conditions += s"state IN (${filter.states.map(state => s"'$state'").mkString(", ")})"
    }
    val where = if (conditions.isEmpty) "" else s"WHERE ${conditions.mkString(" AND ")} "
    val sql = s"SELECT * FROM notebook_execution ${where}ORDER BY submitted_at DESC " +
      s"LIMIT ${filter.limit}"
    JdbcUtils.executeQueryWithRowMapper(sql) { stmt =>
      params.zipWithIndex.foreach { case (binder, index) => binder(stmt, index + 1) }
    }(executionMapper)
  }

  override def updateExecution(execution: CellExecution, expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE notebook_execution SET state = ?, started_at = ?, finished_at = ?, error_code = ?,
        | error_message = ?, internal_operation_handle = ?, version = ?
        | WHERE id = ? AND version = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, execution.state.toString)
      setNullableLong(stmt, 2, execution.startedAt)
      setNullableLong(stmt, 3, execution.finishedAt)
      stmt.setString(4, execution.errorCode.orNull)
      stmt.setString(5, execution.errorMessage.orNull)
      stmt.setString(6, execution.internalOperationHandle.orNull)
      stmt.setLong(7, execution.version)
      stmt.setString(8, execution.id)
      stmt.setLong(9, expectedVersion)
    } == 1
  }

  // ---------------------------------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------------------------------

  private def eventMapper(rs: ResultSet): ExecutionEvent = ExecutionEvent(
    executionId = rs.getString("execution_id"),
    sequence = rs.getLong("event_sequence"),
    eventType = rs.getString("event_type"),
    payload = optString(rs, "payload"),
    createdAt = rs.getLong("created_at"))

  override def appendEvent(event: ExecutionEvent): Unit =
    JdbcUtils.executeUpdate(
      "INSERT INTO notebook_execution_event(execution_id, event_sequence, event_type, payload, " +
        "created_at) VALUES(?, ?, ?, ?, ?)") { stmt =>
      stmt.setString(1, event.executionId)
      stmt.setLong(2, event.sequence)
      stmt.setString(3, event.eventType)
      stmt.setString(4, event.payload.orNull)
      stmt.setLong(5, event.createdAt)
    }

  override def nextEventSequence(executionId: String): Long =
    JdbcUtils.executeQuery(
      "SELECT MAX(event_sequence) AS current FROM notebook_execution_event " +
        "WHERE execution_id = ?") { stmt =>
      stmt.setString(1, executionId)
    } { rs => if (rs.next()) rs.getLong("current") + 1 else 1L }

  override def listEvents(
      executionId: String,
      afterSequence: Long,
      limit: Int): Seq[ExecutionEvent] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_execution_event WHERE execution_id = ? AND event_sequence > ? " +
        s"ORDER BY event_sequence LIMIT $limit") { stmt =>
      stmt.setString(1, executionId)
      stmt.setLong(2, afterSequence)
    }(eventMapper)

  // ---------------------------------------------------------------------------------------------
  // Runs
  // ---------------------------------------------------------------------------------------------

  private def runMapper(rs: ResultSet): NotebookRun = NotebookRun(
    id = rs.getString("id"),
    notebookId = rs.getString("notebook_id"),
    notebookSessionId = rs.getString("notebook_session_id"),
    state = RunState.withName(rs.getString("state")),
    submittedAt = rs.getLong("submitted_at"),
    startedAt = optLong(rs, "started_at"),
    finishedAt = optLong(rs, "finished_at"),
    submittedBy = rs.getString("submitted_by"),
    requestedCellIds = Option(rs.getString("requested_cell_ids"))
      .filter(_.nonEmpty)
      .map(value => mapper.readValue(value, classOf[Array[String]]).toSeq)
      .getOrElse(Seq.empty),
    currentCellId = optString(rs, "current_cell_id"),
    failurePolicy = FailurePolicy.withName(rs.getString("failure_policy")),
    version = rs.getLong("version"))

  override def createRun(run: NotebookRun): Unit = {
    val sql =
      """INSERT INTO notebook_run(id, notebook_id, notebook_session_id, state, submitted_at,
        | started_at, finished_at, submitted_by, requested_cell_ids, current_cell_id,
        | failure_policy, version) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, run.id)
      stmt.setString(2, run.notebookId)
      stmt.setString(3, run.notebookSessionId)
      stmt.setString(4, run.state.toString)
      stmt.setLong(5, run.submittedAt)
      setNullableLong(stmt, 6, run.startedAt)
      setNullableLong(stmt, 7, run.finishedAt)
      stmt.setString(8, run.submittedBy)
      stmt.setString(9, mapper.writeValueAsString(run.requestedCellIds))
      stmt.setString(10, run.currentCellId.orNull)
      stmt.setString(11, run.failurePolicy.toString)
      stmt.setLong(12, run.version)
    }
  }

  override def getRun(id: String): Option[NotebookRun] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM notebook_run WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(runMapper).headOption

  override def listRuns(notebookId: String, limit: Int): Seq[NotebookRun] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM notebook_run WHERE notebook_id = ? ORDER BY submitted_at DESC " +
        s"LIMIT $limit") { stmt =>
      stmt.setString(1, notebookId)
    }(runMapper)

  override def updateRun(run: NotebookRun, expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE notebook_run SET state = ?, started_at = ?, finished_at = ?, current_cell_id = ?,
        | version = ? WHERE id = ? AND version = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, run.state.toString)
      setNullableLong(stmt, 2, run.startedAt)
      setNullableLong(stmt, 3, run.finishedAt)
      stmt.setString(4, run.currentCellId.orNull)
      stmt.setLong(5, run.version)
      stmt.setString(6, run.id)
      stmt.setLong(7, expectedVersion)
    } == 1
  }

  // ---------------------------------------------------------------------------------------------
  // Python environments
  // ---------------------------------------------------------------------------------------------

  private def stringList(value: String): Seq[String] =
    Option(value).filter(_.nonEmpty)
      .map(v => mapper.readValue(v, classOf[Array[String]]).toSeq)
      .getOrElse(Seq.empty)

  private def environmentMapper(rs: ResultSet): PythonEnvironment = PythonEnvironment(
    id = rs.getString("id"),
    owner = rs.getString("owner"),
    name = rs.getString("name"),
    runtimeSpecId = rs.getString("runtime_spec_id"),
    pythonVersion = optString(rs, "python_version"),
    activeRevisionId = optString(rs, "active_revision_id"),
    state = PythonEnvironmentState.withName(rs.getString("state")),
    createdAt = rs.getLong("created_at"),
    createdBy = rs.getString("created_by"),
    updatedAt = rs.getLong("updated_at"),
    updatedBy = rs.getString("updated_by"),
    version = rs.getLong("version"))

  override def createEnvironment(environment: PythonEnvironment): Unit = {
    val sql =
      """INSERT INTO python_environment(id, owner, name, runtime_spec_id, python_version,
        | active_revision_id, state, created_at, created_by, updated_at, updated_by, version)
        | VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, environment.id)
      stmt.setString(2, environment.owner)
      stmt.setString(3, environment.name)
      stmt.setString(4, environment.runtimeSpecId)
      stmt.setString(5, environment.pythonVersion.orNull)
      stmt.setString(6, environment.activeRevisionId.orNull)
      stmt.setString(7, environment.state.toString)
      stmt.setLong(8, environment.createdAt)
      stmt.setString(9, environment.createdBy)
      stmt.setLong(10, environment.updatedAt)
      stmt.setString(11, environment.updatedBy)
      stmt.setLong(12, environment.version)
    }
  }

  override def getEnvironment(id: String): Option[PythonEnvironment] =
    JdbcUtils.executeQueryWithRowMapper("SELECT * FROM python_environment WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(environmentMapper).headOption

  override def listEnvironments(owner: String): Seq[PythonEnvironment] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_environment WHERE owner = ? ORDER BY name") { stmt =>
      stmt.setString(1, owner)
    }(environmentMapper)

  override def updateEnvironment(
      environment: PythonEnvironment,
      expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE python_environment SET name = ?, python_version = ?, active_revision_id = ?,
        | state = ?, updated_at = ?, updated_by = ?, version = ?
        | WHERE id = ? AND version = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, environment.name)
      stmt.setString(2, environment.pythonVersion.orNull)
      stmt.setString(3, environment.activeRevisionId.orNull)
      stmt.setString(4, environment.state.toString)
      stmt.setLong(5, environment.updatedAt)
      stmt.setString(6, environment.updatedBy)
      stmt.setLong(7, environment.version)
      stmt.setString(8, environment.id)
      stmt.setLong(9, expectedVersion)
    } == 1
  }

  override def deleteEnvironment(id: String): Boolean = inTransaction { conn =>
    update(conn, "DELETE FROM python_package_operation WHERE environment_id = ?") {
      _.setString(1, id)
    }
    update(conn, "DELETE FROM python_environment_revision WHERE environment_id = ?") {
      _.setString(1, id)
    }
    update(conn, "DELETE FROM python_environment WHERE id = ?")(_.setString(1, id)) == 1
  }

  private def revisionMapperPython(rs: ResultSet): PythonEnvironmentRevision =
    PythonEnvironmentRevision(
      id = rs.getString("id"),
      environmentId = rs.getString("environment_id"),
      revisionNumber = rs.getLong("revision_number"),
      state = PythonEnvironmentState.withName(rs.getString("state")),
      requirements = stringList(rs.getString("requirements")),
      resolvedPackages = stringList(rs.getString("resolved_packages")),
      createdAt = rs.getLong("created_at"),
      createdBy = rs.getString("created_by"),
      activatedAt = optLong(rs, "activated_at"),
      failureMessage = optString(rs, "failure_message"),
      internalEnvironmentLocation = optString(rs, "internal_environment_location"))

  override def createEnvironmentRevision(revision: PythonEnvironmentRevision): Unit = {
    val sql =
      """INSERT INTO python_environment_revision(id, environment_id, revision_number, state,
        | requirements, resolved_packages, created_at, created_by, activated_at, failure_message,
        | internal_environment_location) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, revision.id)
      stmt.setString(2, revision.environmentId)
      stmt.setLong(3, revision.revisionNumber)
      stmt.setString(4, revision.state.toString)
      stmt.setString(5, mapper.writeValueAsString(revision.requirements))
      stmt.setString(6, mapper.writeValueAsString(revision.resolvedPackages))
      stmt.setLong(7, revision.createdAt)
      stmt.setString(8, revision.createdBy)
      setNullableLong(stmt, 9, revision.activatedAt)
      stmt.setString(10, revision.failureMessage.orNull)
      stmt.setString(11, revision.internalEnvironmentLocation.orNull)
    }
  }

  override def getEnvironmentRevision(id: String): Option[PythonEnvironmentRevision] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_environment_revision WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(revisionMapperPython).headOption

  override def getEnvironmentRevisionByNumber(
      environmentId: String,
      revisionNumber: Long): Option[PythonEnvironmentRevision] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_environment_revision WHERE environment_id = ? " +
        "AND revision_number = ?") { stmt =>
      stmt.setString(1, environmentId)
      stmt.setLong(2, revisionNumber)
    }(revisionMapperPython).headOption

  override def listEnvironmentRevisions(environmentId: String): Seq[PythonEnvironmentRevision] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_environment_revision WHERE environment_id = ? " +
        "ORDER BY revision_number DESC") { stmt =>
      stmt.setString(1, environmentId)
    }(revisionMapperPython)

  override def updateEnvironmentRevision(revision: PythonEnvironmentRevision): Boolean = {
    val sql =
      """UPDATE python_environment_revision SET state = ?, resolved_packages = ?,
        | activated_at = ?, failure_message = ?, internal_environment_location = ?
        | WHERE id = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, revision.state.toString)
      stmt.setString(2, mapper.writeValueAsString(revision.resolvedPackages))
      setNullableLong(stmt, 3, revision.activatedAt)
      stmt.setString(4, revision.failureMessage.orNull)
      stmt.setString(5, revision.internalEnvironmentLocation.orNull)
      stmt.setString(6, revision.id)
    } == 1
  }

  override def deleteEnvironmentRevision(id: String): Boolean =
    JdbcUtils.executeUpdate("DELETE FROM python_environment_revision WHERE id = ?") {
      _.setString(1, id)
    } == 1

  override def nextEnvironmentRevisionNumber(environmentId: String): Long =
    JdbcUtils.executeQuery(
      "SELECT MAX(revision_number) AS current FROM python_environment_revision " +
        "WHERE environment_id = ?") { stmt =>
      stmt.setString(1, environmentId)
    } { rs => if (rs.next()) rs.getLong("current") + 1 else 1L }

  private def packageOperationMapper(rs: ResultSet): PythonPackageOperation =
    PythonPackageOperation(
      id = rs.getString("id"),
      environmentId = rs.getString("environment_id"),
      baseRevisionId = optString(rs, "base_revision_id"),
      targetRevisionId = optString(rs, "target_revision_id"),
      action = PackageAction.withName(rs.getString("action")),
      requestedPackages = stringList(rs.getString("requested_packages")),
      state = PackageOperationState.withName(rs.getString("state")),
      submittedAt = rs.getLong("submitted_at"),
      startedAt = optLong(rs, "started_at"),
      finishedAt = optLong(rs, "finished_at"),
      submittedBy = rs.getString("submitted_by"),
      clientRequestId = optString(rs, "client_request_id"),
      errorCode = optString(rs, "error_code"),
      errorMessage = optString(rs, "error_message"),
      operationLog = optString(rs, "operation_log"),
      version = rs.getLong("version"))

  override def createPackageOperation(operation: PythonPackageOperation): Unit = {
    val sql =
      """INSERT INTO python_package_operation(id, environment_id, base_revision_id,
        | target_revision_id, action, requested_packages, state, submitted_at, started_at,
        | finished_at, submitted_by, client_request_id, error_code, error_message, operation_log,
        | version) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, operation.id)
      stmt.setString(2, operation.environmentId)
      stmt.setString(3, operation.baseRevisionId.orNull)
      stmt.setString(4, operation.targetRevisionId.orNull)
      stmt.setString(5, operation.action.toString)
      stmt.setString(6, mapper.writeValueAsString(operation.requestedPackages))
      stmt.setString(7, operation.state.toString)
      stmt.setLong(8, operation.submittedAt)
      setNullableLong(stmt, 9, operation.startedAt)
      setNullableLong(stmt, 10, operation.finishedAt)
      stmt.setString(11, operation.submittedBy)
      stmt.setString(12, operation.clientRequestId.orNull)
      stmt.setString(13, operation.errorCode.orNull)
      stmt.setString(14, operation.errorMessage.orNull)
      stmt.setString(15, operation.operationLog.orNull)
      stmt.setLong(16, operation.version)
    }
  }

  override def getPackageOperation(id: String): Option[PythonPackageOperation] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_package_operation WHERE id = ?") { stmt =>
      stmt.setString(1, id)
    }(packageOperationMapper).headOption

  override def findPackageOperationByRequestId(
      submittedBy: String,
      clientRequestId: String): Option[PythonPackageOperation] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_package_operation WHERE submitted_by = ? " +
        "AND client_request_id = ?") { stmt =>
      stmt.setString(1, submittedBy)
      stmt.setString(2, clientRequestId)
    }(packageOperationMapper).headOption

  override def listPackageOperations(
      environmentId: String,
      limit: Int): Seq[PythonPackageOperation] =
    JdbcUtils.executeQueryWithRowMapper(
      "SELECT * FROM python_package_operation WHERE environment_id = ? " +
        s"ORDER BY submitted_at DESC LIMIT $limit") { stmt =>
      stmt.setString(1, environmentId)
    }(packageOperationMapper)

  override def updatePackageOperation(
      operation: PythonPackageOperation,
      expectedVersion: Long): Boolean = {
    val sql =
      """UPDATE python_package_operation SET target_revision_id = ?, state = ?, started_at = ?,
        | finished_at = ?, error_code = ?, error_message = ?, operation_log = ?, version = ?
        | WHERE id = ? AND version = ?""".stripMargin
    JdbcUtils.executeUpdate(sql) { stmt =>
      stmt.setString(1, operation.targetRevisionId.orNull)
      stmt.setString(2, operation.state.toString)
      setNullableLong(stmt, 3, operation.startedAt)
      setNullableLong(stmt, 4, operation.finishedAt)
      stmt.setString(5, operation.errorCode.orNull)
      stmt.setString(6, operation.errorMessage.orNull)
      stmt.setString(7, operation.operationLog.orNull)
      stmt.setLong(8, operation.version)
      stmt.setString(9, operation.id)
      stmt.setLong(10, expectedVersion)
    } == 1
  }

  override def listUnfinishedPackageOperations(): Seq[PythonPackageOperation] = {
    val terminal = PackageOperationState.terminal.map(state => s"'$state'").mkString(", ")
    JdbcUtils.executeQueryWithRowMapper(
      s"SELECT * FROM python_package_operation WHERE state NOT IN ($terminal)")(_ => ())(
      packageOperationMapper)
  }

  private def setNullableLong(stmt: PreparedStatement, index: Int, value: Option[Long]): Unit =
    value match {
      case Some(v) => stmt.setLong(index, v)
      case None => stmt.setNull(index, java.sql.Types.BIGINT)
    }
}

object JDBCNotebookStore {
  val SCHEMA_VERSION = "1.0.0"

  /** Document tables first: the runtime tables reference notebooks and cells. */
  val SCHEMA_RESOURCES =
    Seq("notebook-store-schema", "notebook-runtime-schema", "notebook-python-schema")

  /**
   * Escape character for LIKE patterns. Backslash is avoided because the three supported
   * dialects disagree on whether it is already special inside a string literal; every LIKE in
   * this class pairs with `ESCAPE '~'`.
   */
  val LIKE_ESCAPE = "~"

  def escapeLike(value: String): String =
    value.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
      .replace("%", LIKE_ESCAPE + "%")
      .replace("_", LIKE_ESCAPE + "_")
}
