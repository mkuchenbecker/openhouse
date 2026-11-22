package com.linkedin.openhouse.datalayout.config;

import lombok.Builder;
import lombok.Data;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.actions.RewriteDataFiles;

@Data
@Builder
public final class DataCompactionConfig {
  public static final long TARGET_BYTE_SIZE_DEFAULT =
      TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT;
  // This value, the next two and DELETE_FILE_THRESHOLD_DEFAULT equal Iceberg's bin-pack defaults
  // (BinPackStrategy on Iceberg 1.2 and 1.5; SizeBasedFileRewritePlanner on 1.12, which no longer
  // has BinPackStrategy). They are written out so this class compiles on every Iceberg line; javac
  // inlined the same constant values before.
  public static final double MIN_BYTE_SIZE_RATIO_DEFAULT = 0.75d;
  public static final double MAX_BYTE_SIZE_RATIO_DEFAULT = 1.8d;
  public static final int MIN_INPUT_FILES_DEFAULT = 5;
  public static final int MAX_CONCURRENT_FILE_GROUP_REWRITES_DEFAULT =
      RewriteDataFiles.MAX_CONCURRENT_FILE_GROUP_REWRITES_DEFAULT;
  public static final int PARTIAL_PROGRESS_MAX_COMMITS_DEFAULT =
      RewriteDataFiles.PARTIAL_PROGRESS_MAX_COMMITS_DEFAULT;
  public static final long MAX_FILE_GROUP_SIZE_BYTES_DEFAULT =
      RewriteDataFiles.MAX_FILE_GROUP_SIZE_BYTES_DEFAULT;

  public static final int DELETE_FILE_THRESHOLD_DEFAULT = Integer.MAX_VALUE;

  @Builder.Default private long targetByteSize = TARGET_BYTE_SIZE_DEFAULT;
  @Builder.Default private double minByteSizeRatio = MIN_BYTE_SIZE_RATIO_DEFAULT;
  @Builder.Default private double maxByteSizeRatio = MAX_BYTE_SIZE_RATIO_DEFAULT;
  @Builder.Default private int minInputFiles = MIN_INPUT_FILES_DEFAULT;

  @Builder.Default
  private int maxConcurrentFileGroupRewrites = MAX_CONCURRENT_FILE_GROUP_REWRITES_DEFAULT;

  @Builder.Default private boolean partialProgressEnabled = true;
  @Builder.Default private int partialProgressMaxCommits = PARTIAL_PROGRESS_MAX_COMMITS_DEFAULT;
  @Builder.Default private long maxFileGroupSizeBytes = MAX_FILE_GROUP_SIZE_BYTES_DEFAULT;
  @Builder.Default private int deleteFileThreshold = DELETE_FILE_THRESHOLD_DEFAULT;
}
