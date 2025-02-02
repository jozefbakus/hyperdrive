/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.hyperdrive.compatibility.impl.writer.delta.snapshot

import org.apache.spark.sql.execution.streaming.MemoryStream
import org.apache.spark.sql.streaming.Trigger
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import za.co.absa.commons.io.TempDirectory
import za.co.absa.hyperdrive.compatibility.impl.writer.delta.FileUtils
import za.co.absa.hyperdrive.shared.utils.SparkTestBase

import java.sql.Timestamp
import scala.reflect.io.Path

class TestDeltaCDCToSnapshotWriter extends FlatSpec with MockitoSugar with Matchers with BeforeAndAfterEach with SparkTestBase {
  private val baseDir: TempDirectory = TempDirectory("TestDeltaCDCToSnapshotWriter").deleteOnExit()
  private val destinationPath = s"${baseDir.path.toAbsolutePath.toString}/destination"
  private val checkpointPath = s"${baseDir.path.toAbsolutePath.toString}/checkpoint"

  import spark.implicits._

  private val memoryStream = MemoryStream[CDCEvent](1, spark.sqlContext)

  override def beforeEach(): Unit = {
    Path(destinationPath).deleteRecursively()
    Path(checkpointPath).deleteRecursively()
    memoryStream.reset()
  }

  behavior of "DeltaCDCToSnapshotWriter"


  it should "merge cdc events and create latest snapshot table" in {
    writeOneInput("/test-data/first-input.csv")
    getResult should contain theSameElementsAs loadCDCEvents("/test-data/first-expected.csv")

    writeOneInput("/test-data/second-input.csv")
    getResult should contain theSameElementsAs loadCDCEvents("/test-data/second-expected.csv")
  }

  def writeOneInput(testedInputPath: String): Unit = {
    val writer = createDeltaCDCToSnapshotWriter()

    memoryStream.addData(loadCDCEvents(testedInputPath))
    writer.write(memoryStream.toDF()).processAllAvailable()
  }

  private def createDeltaCDCToSnapshotWriter(): DeltaCDCToSnapshotWriter = new DeltaCDCToSnapshotWriter(
      destination = destinationPath,
      trigger = Trigger.Once(),
      checkpointLocation = checkpointPath,
      partitionColumns = Seq.empty,
      keyColumn = "id",
      operationColumn = "eventType",
      operationDeleteValues = Seq("DL", "FD"),
      precombineColumns = Seq("timestamp","eventType"),
      precombineColumnsCustomOrder = Map("eventType" -> Seq("PT", "FI", "RR", "UB", "UP", "DL", "FD")),
      extraConfOptions = Map.empty[String, String]
  )

  private def loadCDCEvents(path: String): Seq[CDCEvent] =
    CDCEvent.loadFromFile(getClass.getResource(s"/delta-cdc-to-snapshot$path").getPath)

  private def getResult: Seq[CDCEvent] = {
    import spark.implicits._
    spark.read.format("delta").load(destinationPath).as[CDCEvent].collect().toSeq
  }
}

case class CDCEvent(id: String, value: String, timestamp: Timestamp, eventType: String)
object CDCEvent {
  def loadFromFile(path: String): Seq[CDCEvent] = {
    val lines = FileUtils.readFileLines(path)
    for {
      line <- lines
      values = line.split(",").map(_.trim)
    } yield CDCEvent(values(0), values(1), Timestamp.valueOf(values(2)), values(3))
  }
}
