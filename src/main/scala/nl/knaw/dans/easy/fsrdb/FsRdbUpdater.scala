/**
 * Copyright (C) 2015-2016 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.fsrdb

import java.io.FileInputStream
import java.sql.{Connection, DriverManager}

import com.yourmediashelf.fedora.client.FedoraClient
import com.yourmediashelf.fedora.client.request.FedoraRequest
import org.apache.commons.io.IOUtils._
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}
import scala.xml.{NodeSeq, Elem, XML}
import scalaj.http.Http
import scala.io.Source

object FsRdbUpdater {
  val loadDriver = classOf[org.postgresql.Driver]
  val log = LoggerFactory.getLogger(getClass)

  val NS_EASY_FILE = "easy-file"
  val NS_EASY_FOLDER = "easy-folder"
  val namespaces = List(NS_EASY_FILE, NS_EASY_FOLDER)

  def run(implicit s: Settings): Try[Unit] = {
    log.info(s"$s")
    if (s.input.isDefined)
      updateDatasets(Source.fromFile(s.input.get).getLines.toList)
    else if (s.datasetPids.isDefined)
      updateDatasets(s.datasetPids.get)
    else
      throw new IllegalArgumentException("No datasets specified to update")
  }

  private def updateDatasets(datasetPids: List[String])(implicit s: Settings): Try[Unit] =
    for {
      _ <- Try { FedoraRequest.setDefaultClient(new FedoraClient(s.fedoraCredentials)) }
      _ = log.info(s"Start updating ${datasetPids.size} dataset(s)")
      _ = datasetPids.foreach(datasetPid => updateDataset(datasetPid))
    } yield log.info("Completed succesfully")

  private def updateDataset(datasetPid: String)(implicit s: Settings): Try[Unit] = Try {
    log.info(s"Checking if dataset ${datasetPid} exists")
    for {
      _ <- existsDataset(datasetPid)
      _ = log.info("Getting digital objects")
      pids <- findPids(datasetPid)
      _ = pids.foreach(pid => log.debug(s"Found digital object: $pid"))
      _ = log.info("Sorting items by path")
      items <- getItems(datasetPid)(pids).sequence.map(_.sortBy(_.path))
      _ = log.info("Updating database")
      _ <- updateDB(items)
    } yield log.info("Dataset updated succesfully")
  }

  // Note Settings not needed
  private def existsDataset(datasetPid: String)(implicit s: Settings): Try[Unit] = Try {
    if(FedoraClient.findObjects()
      .pid().query(s"pid~${datasetPid}").execute().getPids.isEmpty)
      throw new RuntimeException(s"Dataset not found: ${datasetPid}")
  }

  private def getItems(datasetPid: String)(pids: List[String])(implicit s: Settings): List[Try[Item]] = {
    pids.map(pid =>
      if (pid.startsWith(NS_EASY_FILE))
        getObjectXML(pid).flatMap(getFileItem(datasetPid)(pid))
      else if (pid.startsWith(NS_EASY_FOLDER))
        getObjectXML(pid).flatMap(getFolderItem(datasetPid)(pid))
      else
        Failure(new RuntimeException(s"Unknown namespace for PID: $pid")))
  }

  private def getObjectXML(pid: String): Try[Elem] = Try {
    XML.load(FedoraClient.getObjectXML(pid).execute().getEntityInputStream)
  }

  private def getFileItem(datasetPid: String)(pid: String)(objectXML: Elem)(implicit s: Settings): Try[FileItem] = Try {
    val result = for {
      metadataDS <- objectXML \ "datastream"
      if (metadataDS \ "@ID").text == "EASY_FILE_METADATA"
      metadata <- metadataDS \ "datastreamVersion" \ "xmlContent" \ "file-item-md"
      relsExtDS <- objectXML \ "datastream"
      if (relsExtDS \ "@ID").text == "RELS-EXT"
      isMemberOf <- relsExtDS \ "datastreamVersion" \ "xmlContent" \ "RDF" \ "Description" \ "isMemberOf"
      parentSid = isMemberOf.attribute("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "resource").get
      fileDS <- objectXML \ "datastream"
      if (fileDS \ "@ID").text == "EASY_FILE"
      digest = fileDS \ "datastreamVersion" \ "contentDigest" \ "@DIGEST"
    } yield FileItem(
        pid = pid,
        parentSid = parentSid.text.replace("info:fedora/", ""),
        datasetSid = datasetPid,
        path = (metadata \ "path").text,
        filename = (metadata \ "name").text,
        size = (metadata \ "size").text.toInt,
        mimetype = (metadata \ "mimeType").text,
        creatorRole = (metadata \ "creatorRole").text,
        visibleTo = (metadata \ "visibleTo").text,
        accessibleTo = (metadata \ "accessibleTo").text,
        sha1Checksum = if(digest.size > 0) digest.text else null)
    if (result.size != 1)
      throw new RuntimeException(s"Inconsistent file digital object, please inspect $pid manually.")
    result.head
  }

  private def getFolderItem(datasetPid: String)(pid: String)(objectXML: Elem)(implicit s: Settings): Try[FolderItem] = Try {
    val result = for {
      metadataDS <- objectXML \ "datastream"
      if (metadataDS \ "@ID").text == "EASY_ITEM_CONTAINER_MD"
      metadata <- metadataDS \ "datastreamVersion" \ "xmlContent" \ "item-container-md"

      relsExtDS <- objectXML \ "datastream"
      if (relsExtDS \ "@ID").text == "RELS-EXT"
      isMemberOf <- relsExtDS \ "datastreamVersion" \ "xmlContent" \ "RDF" \ "Description" \ "isMemberOf"
      parentSid = isMemberOf.attribute("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "resource").get
    } yield FolderItem(
        pid = pid,
        parentSid = parentSid.text.replace("info:fedora/", ""),
        datasetSid = datasetPid,
        path = (metadata \ "path").text,
        name = (metadata \ "name").text)
    if (result.size != 1)
      throw new RuntimeException(s"Inconsistent folder digital object, please inspect $pid manually.")
    result.head
  }

  private def findPids(datasetPid: String)(implicit s: Settings): Try[List[String]] = Try {
    val url = s"${s.fedoraCredentials.getBaseUrl}/risearch"
    val response = Http(url)
      .timeout(connTimeoutMs = 10000, readTimeoutMs = 50000)
      .param("type", "tuples")
      .param("lang", "sparql")
      .param("format", "CSV")
      .param("query",
        s"""
           |select ?s
           |from <#ri>
           |where { ?s <http://dans.knaw.nl/ontologies/relations#isSubordinateTo> <info:fedora/${datasetPid}> . }
        """.stripMargin)
      .asString
    if (response.code != 200)
      throw new RuntimeException(s"Failed to query fedora resource index ($url), response code: ${response.code}")
    response.body.lines.toList.drop(1)
      .map(_.replace("info:fedora/", ""))
      .filter(pid => namespaces.exists(pid.startsWith))
  }

  private def updateDB(items: List[Item])(implicit s: Settings): Try[Unit] = Try {
    val conn = DriverManager.getConnection(s.postgresURL)
    conn.setAutoCommit(false); //transaction
    try {
      items.foreach {
        case folder: FolderItem => updateOrInsertFolder(conn, folder).get
        case file: FileItem => updateOrInsertFile(conn, file).get
      }
      conn.commit(); //transaction
    }
    catch {
      case t: Throwable => conn.rollback(); Failure(t); //transaction
    } finally {
      conn.close()
    }
  }

  private def updateOrInsertFolder(conn: Connection, folder: FolderItem): Try[Unit] = {
    try {
      log.debug(s"Attempting to update ${folder.pid} with $folder")
      val statement = conn.prepareStatement("UPDATE easy_folders SET path = ?, name = ?, parent_sid = ?, dataset_sid = ? WHERE pid = ?")
      statement.setString(1, folder.path)
      statement.setString(2, folder.name)
      statement.setString(3, folder.parentSid)
      statement.setString(4, folder.datasetSid)
      statement.setString(5, folder.pid)
      val result = statement.executeUpdate()
      statement.closeOnCompletion()
      if (result == 1)
        Success(Unit)
      else
        insertFolder(conn, folder)
    } catch {
      case t: Throwable => Failure(t)
    }
  }

  private def insertFolder(conn: Connection, folder: FolderItem): Try[Unit] = Try {
    log.debug(s"Attempting to insert ${folder.pid}")
    val statement = conn.prepareStatement("INSERT INTO easy_folders (pid,path,name,parent_sid,dataset_sid) VALUES (?,?,?,?,?)")
    statement.setString(1, folder.pid)
    statement.setString(2, folder.path)
    statement.setString(3, folder.name)
    statement.setString(4, folder.parentSid)
    statement.setString(5, folder.datasetSid)
    statement.executeUpdate()
    statement.closeOnCompletion()
  }

  private def updateOrInsertFile(conn: Connection, file: FileItem): Try[Unit] = {
    try {
      log.debug(s"Attempting to update ${file.pid} with $file")
      val statement = conn.prepareStatement("""
        UPDATE easy_files
        SET parent_sid = ?,
            dataset_sid = ?,
            path  = ?,
            filename = ?,
            size = ?,
            mimetype = ?,
            creator_role = ?,
            visible_to = ?,
            accessible_to = ?,
            sha1checksum = ?
        WHERE pid = ?
      """)
      statement.setString(1, file.parentSid)
      statement.setString(2, file.datasetSid)
      statement.setString(3, file.path)
      statement.setString(4, file.filename)
      statement.setInt(5, file.size)
      statement.setString(6, file.mimetype)
      statement.setString(7, file.creatorRole)
      statement.setString(8, file.visibleTo)
      statement.setString(9, file.accessibleTo)
      statement.setString(10, file.sha1Checksum)
      statement.setString(11, file.pid)
      val result = statement.executeUpdate()
      statement.closeOnCompletion()
      if (result == 1)
        Success(Unit)
      else
        insertFile(conn, file)
    } catch {
      case t: Throwable => Failure(t)
    }
  }

  private def insertFile(conn: Connection, file: FileItem): Try[Unit] = Try {
    log.debug(s"Attempting to insert ${file.pid}")
    val statement = conn.prepareStatement("""
      INSERT INTO easy_files
        (pid, parent_sid, dataset_sid, path, filename, size, mimetype,
         creator_role, visible_to, accessible_to, sha1checksum)
      VALUES (?,?,?,?,?,?,?,?,?,?,?)
      """   )
    statement.setString(1, file.pid)
    statement.setString(2, file.parentSid)
    statement.setString(3, file.datasetSid)
    statement.setString(4, file.path)
    statement.setString(5, file.filename)
    statement.setInt(6, file.size)
    statement.setString(7, file.mimetype)
    statement.setString(8, file.creatorRole)
    statement.setString(9, file.visibleTo)
    statement.setString(10, file.accessibleTo)
    statement.setString(11, file.sha1Checksum)
    statement.executeUpdate()
    statement.closeOnCompletion()
  }

}
