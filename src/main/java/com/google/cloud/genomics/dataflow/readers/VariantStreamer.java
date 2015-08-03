/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.genomics.dataflow.readers;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.genomics.utils.grpc.Channels;
import com.google.genomics.v1.StreamVariantsRequest;
import com.google.genomics.v1.StreamVariantsResponse;
import com.google.genomics.v1.StreamingVariantServiceGrpc;
import com.google.genomics.v1.Variant;

/**
 * Class with tools for streaming variants using gRPC within dataflow pipelines.
 */
public class VariantStreamer {
  
  /**
   * PTransform for streaming variants via gRPC.
   */
  public static class StreamVariants extends
      PTransform<PCollection<StreamVariantsRequest>, PCollection<Variant>> {

    @Override
    public PCollection<Variant> apply(PCollection<StreamVariantsRequest> input) {
      return input.apply(ParDo.of(new RetrieveVariants()))
          .apply(ParDo.of(new ConvergeVariantsList()));
    }
  }

  private static class RetrieveVariants extends DoFn<StreamVariantsRequest, List<Variant>> {

    protected Aggregator<Integer, Integer> initializedShardCount;
    protected Aggregator<Integer, Integer> finishedShardCount;

    public RetrieveVariants() {
      initializedShardCount = createAggregator("Initialized Shard Count", new Sum.SumIntegerFn());
      finishedShardCount = createAggregator("Finished Shard Count", new Sum.SumIntegerFn());
    }

    @Override
    public void processElement(ProcessContext c) throws IOException {
      initializedShardCount.addValue(1);
      StreamingVariantServiceGrpc.StreamingVariantServiceBlockingStub variantStub =
          StreamingVariantServiceGrpc.newBlockingStub(Channels.fromDefaultCreds());
      Iterator<StreamVariantsResponse> iter = variantStub.streamVariants(c.element());
      while (iter.hasNext()) {
        StreamVariantsResponse variantResponse = iter.next();
        c.output(variantResponse.getVariantsList());
      }
      finishedShardCount.addValue(1);
    }
  }

  /**
   * This step exists to emit the individual variants in a parallel step to the StreamVariants step
   * in order to increase throughput.
   */
  private static class ConvergeVariantsList extends DoFn<List<Variant>, Variant> {

    protected Aggregator<Long, Long> itemCount;

    public ConvergeVariantsList() {
      itemCount = createAggregator("Number of variants", new Sum.SumLongFn());
    }

    @Override
    public void processElement(ProcessContext c) {
      for (Variant v : c.element()) {
        c.output(v);
        itemCount.addValue(1L);
      }
    }
  }

}
