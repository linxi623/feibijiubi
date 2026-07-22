package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.VideoStatusFlushBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface VideoStatusFlushBatchMapper {

    int insert(VideoStatusFlushBatch batch);

    VideoStatusFlushBatch selectByBatchIdForUpdate(String batchId);

    List<VideoStatusFlushBatch> selectCleanupPending(
            @Param("limit") int limit
    );

    int markCleaned(String batchId);

    int markGenerationSkipped(String batchId);

    int recordCleanupFailure(
            @Param("batchId") String batchId,
            @Param("lastError") String lastError
    );

    int markCleanupRepairRequired(
            @Param("batchId") String batchId,
            @Param("lastError") String lastError
    );
}
