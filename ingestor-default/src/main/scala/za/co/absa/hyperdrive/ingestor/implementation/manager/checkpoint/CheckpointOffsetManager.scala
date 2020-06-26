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

package za.co.absa.hyperdrive.ingestor.implementation.manager.checkpoint

import org.apache.commons.configuration2.Configuration
import org.apache.commons.lang3.StringUtils
import org.apache.logging.log4j.LogManager
import org.apache.spark.sql.Row
import org.apache.spark.sql.streaming.{DataStreamReader, DataStreamWriter}
import za.co.absa.hyperdrive.ingestor.api.manager.{StreamManager, StreamManagerFactory}

private[manager] class CheckpointOffsetManager(val checkpointLocation: String,
                                               val startingOffsets: Option[String]) extends StreamManager {

  override def configure(streamReader: DataStreamReader, configuration: org.apache.hadoop.conf.Configuration): DataStreamReader = {
    streamReader
  }

  override def configure(streamWriter: DataStreamWriter[Row], configuration: org.apache.hadoop.conf.Configuration): DataStreamWriter[Row] = {
    streamWriter
  }
}

object CheckpointOffsetManager extends StreamManagerFactory with CheckpointOffsetManagerAttributes {
  override def apply(config: Configuration): StreamManager = {
    new CheckpointOffsetManager("", None)
  }
}
