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

package za.co.absa.hyperdrive.compatibility.impl

import za.co.absa.hyperdrive.compatibility.api.{CompatibleDeltaCDCToSnapshotWriter, DeltaCDCToSnapshotWriterConfiguration}
import io.delta.tables.{DeltaMergeBuilder, DeltaTable}
import org.apache.spark.sql.{Column, DataFrame, Row, functions}
import org.apache.spark.sql.catalyst.expressions.objects.AssertNotNull
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.streaming.{OutputMode, StreamingQuery, Trigger}
import org.slf4j.LoggerFactory

class DeltaCDCToSnapshotWriter(configuration: DeltaCDCToSnapshotWriterConfiguration) extends CompatibleDeltaCDCToSnapshotWriter {
  private val logger = LoggerFactory.getLogger(this.getClass)

  override def write(dataFrame: DataFrame): StreamingQuery = {
    dataFrame.writeStream
      .trigger(configuration.trigger)
      .outputMode(OutputMode.Append())
      .option(configuration.checkpointLocationPropName, configuration.checkpointLocation)
      .options(configuration.extraConfOptions)
      .foreachBatch((df: DataFrame, batchId: Long) => {
        if(!DeltaTable.isDeltaTable(df.sparkSession, configuration.destination)) {
          df.sparkSession
            .createDataFrame(df.sparkSession.sparkContext.emptyRDD[Row], df.schema)
            .write
            .format("delta")
            .mode("overwrite")
            .option("overwriteSchema", "true")
            .partitionBy(configuration.partitionColumns :_*)
            .save(configuration.destination)
        }

        logger.info(s"Writing batchId: $batchId")

        val sortFieldsPrefix = "tmp_hyperdrive_"

        val dataFrameWithSortColumns = getDataFrameWithSortColumns(df, sortFieldsPrefix)

        val originalFieldNames = df.schema.fieldNames.mkString(",")
        val sortColumnsWithPrefix = configuration.sortColumns.map(sortColumn => s"$sortFieldsPrefix$sortColumn")

        val latestChangeForEachKey = dataFrameWithSortColumns
          .selectExpr(s"${configuration.keyColumn}", s"struct(${sortColumnsWithPrefix.mkString(",")}, $originalFieldNames) as otherCols" )
          .groupBy(s"${configuration.keyColumn}")
          .agg(functions.max("otherCols").as("latest"))
          .filter(col("latest").isNotNull)
          .withColumn("latest", new Column(AssertNotNull(col("latest").expr)))
          .selectExpr( "latest.*")
          .drop(sortColumnsWithPrefix :_*)

        generateDeltaMerge(latestChangeForEachKey).execute()
      })
      .start()
  }

  private def generateDeltaMerge(latestChanges: DataFrame): DeltaMergeBuilder = {
    val initialDeltaBuilder = DeltaTable
      .forPath(configuration.destination)
      .as("currentTable")
      .merge(latestChanges.as("changes"), s"currentTable.${configuration.keyColumn} = changes.${configuration.keyColumn}")
      .whenMatched(s"changes.${configuration.opColumn} = '${configuration.deletedValue}'")
      .delete()

    val deltaBuilderWithSortColumns = configuration.sortColumns.foldLeft(initialDeltaBuilder) { (builder, sortColumn) =>
      val order = configuration.sortColumnsCustomOrder.getOrElse(sortColumn, Seq.empty[String])
      order match {
        case o if o.isEmpty =>
          builder
            .whenMatched(s"changes.$sortColumn > currentTable.$sortColumn")
            .updateAll()
        case o =>
          val orderString = o.mkString("#")
          builder
            .whenMatched(s"""locate(changes.$sortColumn, "$orderString") > locate(currentTable.$sortColumn, "$orderString")""")
            .updateAll()
      }
    }

    deltaBuilderWithSortColumns
      .whenNotMatched(s"changes.${configuration.opColumn} != '${configuration.deletedValue}'")
      .insertAll()
  }

  private def getDataFrameWithSortColumns(dataFrame: DataFrame, sortFieldsPrefix: String): DataFrame = {
    configuration.sortColumns.foldLeft(dataFrame) { (df, sortColumn) =>
      val order = configuration.sortColumnsCustomOrder.getOrElse(sortColumn, Seq.empty[String])
      order match {
        case o if o.isEmpty =>
          df.withColumn(s"$sortFieldsPrefix$sortColumn", col(s"$sortColumn"))
        case o =>
          val orderString = o.mkString("#")
          df.withColumn(s"$sortFieldsPrefix$sortColumn", functions.expr(s"""locate(value, "$orderString")"""))
      }
    }
  }
}
