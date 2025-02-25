/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.genomics.dataflow.functions;

import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import com.google.cloud.genomics.utils.Contig;
import com.google.genomics.v1.Read;

/*
 * Takes a collection of reads and shards them out by Contig.
 * Can be used to prepare reads for being written to disk in parallel.
 */
public class ShardReadsTransform extends PTransform<PCollection<Read>, PCollection<KV<Contig, Iterable<Read>>>> {

  static public interface Options extends KeyReadsFn.Options {}

  @Override
  public PCollection<KV<Contig, Iterable<Read>>> expand(PCollection<Read> reads) {
    return reads
      .apply("KeyReads", ParDo.of(new KeyReadsFn()))
      .apply(GroupByKey.<Contig, Read>create());
  }

  public static PCollection<KV<Contig, Iterable<Read>>> shard(PCollection<Read> reads) {
    return (new ShardReadsTransform()).expand(reads);
  }
}
