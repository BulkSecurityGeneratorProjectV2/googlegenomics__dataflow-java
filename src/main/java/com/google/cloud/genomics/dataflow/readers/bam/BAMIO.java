/*
 * Copyright (C) 2015 Google Inc.
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
package com.google.cloud.genomics.dataflow.readers.bam;

import com.google.api.services.storage.Storage;

import htsjdk.samtools.DefaultSAMRecordFactory;
import htsjdk.samtools.SamInputResource;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.SeekingBAMFileReader;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.seekablestream.SeekableStream;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Utility methods for opening BAM files from GCS storage using HTSJDK.
 * For sharding in pipelines we need access to the guts of BAM index,
 * so openBAMAndExposeIndex provides a convenient way to get both SamReader and
 * a stream for an index file.
 */
public class BAMIO {
  public static final String BAM_INDEX_FILE_MIME_TYPE = "application/octet-stream";
  public static final String BAM_FILE_SUFFIX = ".bam";
  public static class ReaderAndIndex {
    public SamReader reader;
    public SeekableStream index;
  }
  private static final Logger LOG = Logger.getLogger(BAMIO.class.getName());

  public static ReaderAndIndex openBAMAndExposeIndex(Storage.Objects storageClient, String gcsStoragePath, ValidationStringency stringency) throws IOException {
    ReaderAndIndex result = new ReaderAndIndex();
    result.index = openIndexForPath(storageClient, gcsStoragePath);
    result.reader = openBAMReader(
        openBAMFile(storageClient, gcsStoragePath,result.index), stringency, false, 0);
    return result;
  }

  public static SamReader openBAM(Storage.Objects storageClient, String gcsStoragePath,
      ValidationStringency stringency, boolean includeFileSource) throws IOException {
    return openBAMReader(openBAMFile(storageClient, gcsStoragePath,
        openIndexForPath(storageClient, gcsStoragePath)), stringency, includeFileSource, 0);
  }

  public static SamReader openBAM(Storage.Objects storageClient, String gcsStoragePath,
      ValidationStringency stringency, boolean includeFileSource, long offset) throws IOException {
    return openBAMReader(openBAMFile(storageClient, gcsStoragePath,
        null), stringency, includeFileSource, offset);
  }

  public static SamReader openBAM(Storage.Objects storageClient, String gcsStoragePath, ValidationStringency stringency) throws IOException {
    return openBAM(storageClient, gcsStoragePath, stringency, false);
  }

  private static SeekableStream openIndexForPath(Storage.Objects storageClient,String gcsStoragePath) {
    final String indexPath = gcsStoragePath + ".bai";
    try {
      return new SeekableGCSStream(storageClient, indexPath);
    } catch (IOException ex) {
      LOG.info("No index for " + indexPath);
      // Ignore if there is no bai file
    }
    return null;
  }

  private static SamInputResource openBAMFile(Storage.Objects storageClient, String gcsStoragePath, SeekableStream index) throws IOException {
    SeekableGCSStream s = new SeekableGCSStream(storageClient, gcsStoragePath);
    SamInputResource samInputResource =
        SamInputResource.of(s);

    if (index != null) {
      samInputResource.index(index);
    }

    LOG.info("getReadsFromBAMFile - got input resources");
    return samInputResource;
  }

  public static class SeekingReaderAdapter extends SamReader.PrimitiveSamReaderToSamReaderAdapter {
     SeekingBAMFileReader underlyingReader;
     public SeekingReaderAdapter(SeekingBAMFileReader reader, SamInputResource resource){
         super(reader, resource);
         underlyingReader = reader;
     }

     public SeekingBAMFileReader underlyingSeekingReader() {
       return underlyingReader;
     }
  }

  private static SamReader openBAMReader(SamInputResource resource, ValidationStringency stringency, boolean includeFileSource, long offset) throws IOException {
    SamReaderFactory samReaderFactory = SamReaderFactory
        .makeDefault()
        .validationStringency(stringency)
        .enable(SamReaderFactory.Option.CACHE_FILE_BASED_INDEXES);
    if (includeFileSource) {
      samReaderFactory.enable(SamReaderFactory.Option.INCLUDE_SOURCE_IN_RECORDS);
    }
    if (offset == 0) {
      return samReaderFactory.open(resource);
    }
    LOG.info("Initializing seeking reader with the offset of " + offset);
    SeekingBAMFileReader primitiveReader = new SeekingBAMFileReader(resource,
        false,
        stringency,
        DefaultSAMRecordFactory.getInstance(),
        offset);
    final SeekingReaderAdapter reader =
        new SeekingReaderAdapter(primitiveReader, resource);
    samReaderFactory.reapplyOptions(reader);
    return reader;
  }
}
