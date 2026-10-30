package com.feibijiubi.backend.video.service;

import com.feibijiubi.backend.video.dto.CursorDTO;
import com.feibijiubi.backend.video.dto.VideoSubmitDTO;
import com.feibijiubi.backend.video.vo.CursorPageVO;
import com.feibijiubi.backend.video.vo.VideoDetailVO;
import com.feibijiubi.backend.video.vo.VideoListItemVO;
import com.feibijiubi.backend.video.vo.VideoSubmitVO;
import org.springframework.web.multipart.MultipartFile;


public interface VideoService {
    String uploadCover(Integer currentUserId, MultipartFile file);

    VideoSubmitVO submitVideo(Integer currentUserId, VideoSubmitDTO request);

    VideoDetailVO getVideoDetail(Integer currentUserId, Integer vid);

    CursorPageVO<VideoListItemVO> getVideoFeed(CursorDTO request);

    void deleteVideo(Integer currentUserId, Integer vid);
}
