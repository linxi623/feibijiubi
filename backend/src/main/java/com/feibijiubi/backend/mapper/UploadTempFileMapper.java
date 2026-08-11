package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.UploadTempFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UploadTempFileMapper {
    int insert(UploadTempFile uploadTempFile);

    UploadTempFile selectByObjectKey(String objectKey);

    int markSubmitted(@Param("objectKey") String objectKey,
                      @Param("uid") Integer uid);

    int markCleaned(@Param("objectKey") String objectKey);

    List<UploadTempFile> selectExpiredTempFiles(@Param("now") LocalDateTime now,
                                                @Param("limit") Integer limit);

}
