/*
 * Copyright 2020 Intel Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.oap.sql.shims.spark311

import com.intel.oap.sql.shims.{ShimDescriptor, SparkShims}
import org.apache.spark.shuffle.IndexShuffleBlockResolver

class Spark311Shims extends SparkShims {

  override def getShimDescriptor: ShimDescriptor = SparkShimProvider.DESCRIPTOR

  override def shuffleBlockResolverWriteAndCommit(shuffleBlockResolver: IndexShuffleBlockResolver,
                                                  shuffleID: int, mapID: long, partitionLengths: Array[Long], dataTmp: File) =
  shuffleBlockResolver.writeIndexFileAndCommit(dep.shuffleId, mapId, partitionLengths, dataTmp)
}