/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.llap.io.metadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.hadoop.hive.llap.IncrementalObjectSizeEstimator;
import org.apache.hadoop.hive.llap.IncrementalObjectSizeEstimator.ObjectEstimator;
import org.apache.hadoop.hive.llap.cache.EvictionDispatcher;
import org.apache.hadoop.hive.llap.cache.LlapCacheableBuffer;
import org.apache.hadoop.hive.llap.io.api.impl.LlapIoImpl;
import org.apache.hadoop.hive.ql.io.orc.CompressionKind;
import org.apache.hadoop.hive.ql.io.orc.FileMetadata;
import org.apache.hadoop.hive.ql.io.orc.OrcInputFormat;
import org.apache.hadoop.hive.ql.io.orc.OrcProto;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.BucketStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.ColumnStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.StringStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.StripeStatistics;
import org.apache.hadoop.hive.ql.io.orc.OrcProto.Type;
import org.apache.hadoop.hive.ql.io.orc.Reader;
import org.apache.hadoop.hive.ql.io.orc.ReaderImpl.StripeInformationImpl;
import org.apache.hadoop.hive.ql.io.orc.StripeInformation;

import com.google.common.annotations.VisibleForTesting;

/** ORC file metadata. Currently contains some duplicate info due to how different parts
 * of ORC use different info. Ideally we would get rid of protobuf structs in code beyond reading,
 * or instead use protobuf structs everywhere instead of the mix of things like now.
 */
public final class OrcFileMetadata extends LlapCacheableBuffer implements FileMetadata {
  private final List<StripeInformation> stripes;
  private final List<Integer> versionList;
  private final List<StripeStatistics> stripeStats;
  private final List<Type> types;
  private final List<ColumnStatistics> fileStats;
  private final long fileId;
  private final CompressionKind compressionKind;
  private final int rowIndexStride;
  private final int compressionBufferSize;
  private final int metadataSize;
  private final int writerVersionNum;
  private final long contentLength;
  private final long numberOfRows;
  private final boolean isOriginalFormat;

  private final int estimatedMemUsage;

  private final static HashMap<Class<?>, ObjectEstimator> SIZE_ESTIMATORS;
  private final static ObjectEstimator SIZE_ESTIMATOR;
  static {
    OrcFileMetadata ofm = createDummy(0);
    SIZE_ESTIMATORS = IncrementalObjectSizeEstimator.createEstimators(ofm);
    IncrementalObjectSizeEstimator.addEstimator(
        "com.google.protobuf.LiteralByteString", SIZE_ESTIMATORS);
    SIZE_ESTIMATOR = SIZE_ESTIMATORS.get(OrcFileMetadata.class);
  }

  @VisibleForTesting
  public static OrcFileMetadata createDummy(int fileId) {
    OrcFileMetadata ofm = new OrcFileMetadata(fileId);
    ofm.stripes.add(new StripeInformationImpl(
        OrcProto.StripeInformation.getDefaultInstance()));
    ofm.fileStats.add(ColumnStatistics.getDefaultInstance());
    ofm.stripeStats.add(StripeStatistics.newBuilder().addColStats(createStatsDummy()).build());
    ofm.types.add(Type.newBuilder().addFieldNames("a").addSubtypes(0).build());
    ofm.versionList.add(0);
    return ofm;
  }

  static ColumnStatistics.Builder createStatsDummy() {
    return ColumnStatistics.newBuilder().setBucketStatistics(
            BucketStatistics.newBuilder().addCount(0)).setStringStatistics(
            StringStatistics.newBuilder().setMaximum("zzz"));
  }

  // Ctor for memory estimation and tests
  private OrcFileMetadata(int fileId) {
    this.fileId = fileId;
    stripes = new ArrayList<StripeInformation>();
    versionList = new ArrayList<Integer>();
    fileStats = new ArrayList<>();
    stripeStats = new ArrayList<>();
    types = new ArrayList<>();
    writerVersionNum = metadataSize = compressionBufferSize = rowIndexStride = 0;
    contentLength = numberOfRows = 0;
    estimatedMemUsage = 0;
    isOriginalFormat = false;
    compressionKind = CompressionKind.NONE;
  }

  public OrcFileMetadata(long fileId, Reader reader) {
    this.fileId = fileId;
    this.stripeStats = reader.getOrcProtoStripeStatistics();
    this.compressionKind = reader.getCompression();
    this.compressionBufferSize = reader.getCompressionSize();
    this.stripes = reader.getStripes();
    this.isOriginalFormat = OrcInputFormat.isOriginal(reader);
    this.writerVersionNum = reader.getWriterVersion().getId();
    this.versionList = reader.getVersionList();
    this.metadataSize = reader.getMetadataSize();
    this.types = reader.getTypes();
    this.rowIndexStride = reader.getRowIndexStride();
    this.contentLength = reader.getContentLength();
    this.numberOfRows = reader.getNumberOfRows();
    this.fileStats = reader.getOrcProtoFileStatistics();

    this.estimatedMemUsage = SIZE_ESTIMATOR.estimate(this, SIZE_ESTIMATORS);
  }

  // LlapCacheableBuffer
  @Override
  public void notifyEvicted(EvictionDispatcher evictionDispatcher) {
    evictionDispatcher.notifyEvicted(this);
  }

  @Override
  protected boolean invalidate() {
    return true; // relies on GC, so it can always be evicted now.
  }

  @Override
  public long getMemoryUsage() {
    return estimatedMemUsage;
  }

  @Override
  protected boolean isLocked() {
    return false;
  }

  // FileMetadata
  @Override
  public List<OrcProto.Type> getTypes() {
    return types;
  }

  @Override
  public boolean isOriginalFormat() {
    return isOriginalFormat;
  }

  @Override
  public List<StripeInformation> getStripes() {
    return stripes;
  }

  @Override
  public CompressionKind getCompressionKind() {
    return compressionKind;
  }

  @Override
  public int getCompressionBufferSize() {
    return compressionBufferSize;
  }

  @Override
  public int getRowIndexStride() {
    return rowIndexStride;
  }

  @Override
  public int getColumnCount() {
    return types.size();
  }

  @Override
  public int getFlattenedColumnCount() {
    return types.get(0).getSubtypesCount();
  }

  @Override
  public long getFileId() {
    return fileId;
  }

  @Override
  public List<Integer> getVersionList() {
    return versionList;
  }

  @Override
  public int getMetadataSize() {
    return metadataSize;
  }

  @Override
  public int getWriterVersionNum() {
    return writerVersionNum;
  }

  @Override
  public List<StripeStatistics> getStripeStats() {
    return stripeStats;
  }

  @Override
  public long getContentLength() {
    return contentLength;
  }

  @Override
  public long getNumberOfRows() {
    return numberOfRows;
  }

  @Override
  public List<ColumnStatistics> getFileStats() {
    return fileStats;
  }
}
