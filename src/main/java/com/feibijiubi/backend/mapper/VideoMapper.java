package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.vo.UnpubVideoListItemVO;
import com.feibijiubi.backend.vo.VideoListItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VideoMapper {
    int insert(Video record);

    Video selectByVid(Integer vid);

    Video selectPublishedByVid(Integer vid);

    List<Video> selectPublishedList();

    List<VideoListItemVO> selectFeed(@Param("cursorCreatedAt")LocalDateTime cursorCreatedAt,
                                     @Param("cursorVid")Integer cursorVid,
                                     @Param("size")Integer size);

    List<UnpubVideoListItemVO> selectUnpub(Byte status);

    int updateReviewStatus(
            @Param("vid") Integer vid,
            @Param("oldStatus") Integer oldStatus,
            @Param("newStatus") Integer newStatus
    );

    int countVideoByUid(Integer uid);
}
