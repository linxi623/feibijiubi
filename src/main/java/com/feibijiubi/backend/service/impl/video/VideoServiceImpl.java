package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.dto.VideoSubmitDTO;
import com.feibijiubi.backend.entity.UploadTempFile;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.feibijiubi.backend.service.video.VideoService;
import com.feibijiubi.backend.vo.VideoSubmitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;


@Slf4j
@Service
public class VideoServiceImpl implements VideoService {
    private static final byte FILE_TYPE_VIDEO = 1;
    private static final byte FILE_TYPE_COVER = 2;
    private static final byte TEMP_STATUS_WAIT_SUBMIT = 0;
    private static final int VIDEO_STATUS_REVIEWING = 0;

    private final UploadTempFileMapper uploadTempFileMapper;
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final FileStorageService fileStorageService;

    public VideoServiceImpl(UploadTempFileMapper uploadTempFileMapper,
                            VideoMapper videoMapper,
                            VideoStatusMapper videoStatusMapper,
                            FileStorageService fileStorageService) {
        this.uploadTempFileMapper = uploadTempFileMapper;
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public String uploadCover(Integer currentUserId, MultipartFile file) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        String directory = "temp/covers/";
        String url = fileStorageService.uploadImage(currentUserId, file, directory);
        String objectKey = fileStorageService.Url2Key(url);

        UploadTempFile uploadTempFile = new UploadTempFile();
        uploadTempFile.setUid(currentUserId);
        uploadTempFile.setFileType(FILE_TYPE_COVER);
        uploadTempFile.setObjectKey(objectKey);
        uploadTempFile.setOriginalFilename(file.getOriginalFilename());
        uploadTempFile.setContentType(file.getContentType());
        uploadTempFile.setFileSize(file.getSize());
        uploadTempFile.setStatus(TEMP_STATUS_WAIT_SUBMIT);
        uploadTempFile.setExpireAt(LocalDateTime.now().plusDays(1));
        uploadTempFileMapper.insert(uploadTempFile);
        return url;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public VideoSubmitVO submitVideo(Integer currentUserId, VideoSubmitDTO request) {
        validateSubmitRequest(currentUserId, request);

        String tempVideoKey = request.getTempVideoKey();
        String tempCoverKey = request.getTempCoverKey();

        UploadTempFile tempVideo = uploadTempFileMapper.selectByObjectKey(tempVideoKey);
        UploadTempFile tempCover = uploadTempFileMapper.selectByObjectKey(tempCoverKey);

        validateTempFile(tempVideo, currentUserId, FILE_TYPE_VIDEO, "视频临时文件");
        validateTempFile(tempCover, currentUserId, FILE_TYPE_COVER, "封面临时文件");

        if (!fileStorageService.objectExists(tempVideoKey)) {
            throw new BusinessException(400, "视频临时文件不存在");
        }
        if (!fileStorageService.objectExists(tempCoverKey)) {
            throw new BusinessException(400, "封面临时文件不存在");
        }

        String formalVideoKey = fileStorageService.buildFormalVideoKey(currentUserId, tempVideoKey);
        String formalCoverKey = fileStorageService.buildFormalCoverKey(currentUserId, tempCoverKey);
        String copiedVideoKey = null;
        String copiedCoverKey = null;

        try {
            fileStorageService.copyObject(tempVideoKey, formalVideoKey);
            copiedVideoKey = formalVideoKey;

            fileStorageService.copyObject(tempCoverKey, formalCoverKey);
            copiedCoverKey = formalCoverKey;

            Video video = buildVideo(currentUserId, request, formalVideoKey, formalCoverKey);
            int videoRows = videoMapper.insert(video);
            if (videoRows != 1 || video.getVid() == null) {
                throw new BusinessException(500, "视频投稿失败");
            }

            int statusRows = videoStatusMapper.insertDefault(video.getVid());
            if (statusRows != 1) {
                throw new BusinessException(500, "视频统计初始化失败");
            }

            int videoTempRows = uploadTempFileMapper.markSubmitted(tempVideoKey, currentUserId);
            int coverTempRows = uploadTempFileMapper.markSubmitted(tempCoverKey, currentUserId);
            if (videoTempRows != 1 || coverTempRows != 1) {
                throw new BusinessException(500, "临时文件状态更新失败");
            }

            deleteTempObjectQuietly(tempVideoKey);
            deleteTempObjectQuietly(tempCoverKey);

            return buildSubmitVO(video);
        } catch (RuntimeException e) {
            deleteFormalObjectQuietly(copiedVideoKey);
            deleteFormalObjectQuietly(copiedCoverKey);
            throw new BusinessException(500, e.getMessage());
        }
    }

    private void validateSubmitRequest(Integer currentUserId, VideoSubmitDTO request) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (!StringUtils.hasText(request.getTitle())) {
            throw new BusinessException(400, "标题不能为空");
        }
        if (!StringUtils.hasText(request.getMcId()) || !StringUtils.hasText(request.getScId())) {
            throw new BusinessException(400, "分区不能为空");
        }
        if (request.getDuration() == null || request.getDuration() <= 0) {
            throw new BusinessException(400, "视频时长不合法");
        }
        if (!StringUtils.hasText(request.getTempVideoKey())) {
            throw new BusinessException(400, "视频不能为空");
        }
        if (!StringUtils.hasText(request.getTempCoverKey())) {
            throw new BusinessException(400, "封面不能为空");
        }

        String videoPrefix = "temp/videos/" + currentUserId + "/";
        if (!request.getTempVideoKey().startsWith(videoPrefix)) {
            throw new BusinessException(400, "视频临时文件路径不合法");
        }

        String coverPrefix = "temp/covers/" + currentUserId + "/";
        if (!request.getTempCoverKey().startsWith(coverPrefix)) {
            throw new BusinessException(400, "封面临时文件路径不合法");
        }
    }

    private void validateTempFile(UploadTempFile tempFile,
                                  Integer currentUserId,
                                  byte fileType,
                                  String fileName) {
        if (tempFile == null) {
            throw new BusinessException(400, fileName + "记录不存在");
        }
        if (!currentUserId.equals(tempFile.getUid())) {
            throw new BusinessException(403, fileName + "不属于当前用户");
        }
        if (tempFile.getFileType() == null || tempFile.getFileType() != fileType) {
            throw new BusinessException(400, fileName + "类型不正确");
        }
        if (tempFile.getStatus() == null || tempFile.getStatus() != TEMP_STATUS_WAIT_SUBMIT) {
            throw new BusinessException(400, fileName + "已提交或已清理");
        }
        if (tempFile.getExpireAt() == null || !tempFile.getExpireAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(400, fileName + "已过期");
        }
    }

    private Video buildVideo(Integer currentUserId,
                             VideoSubmitDTO request,
                             String formalVideoKey,
                             String formalCoverKey) {
        Video video = new Video();
        video.setUid(currentUserId);
        video.setTitle(request.getTitle());
        video.setSourceType(request.getSourceType());
        video.setVisibility(request.getVisibility());
        video.setDuration(request.getDuration());
        video.setMcId(request.getMcId());
        video.setScId(request.getScId());
        video.setTags(request.getTags());
        video.setDescription(request.getDescription());
        video.setCoverKey(formalCoverKey);
        video.setCoverUrl(fileStorageService.Key2Url(formalCoverKey));
        video.setVideoKey(formalVideoKey);
        video.setVideoUrl(fileStorageService.Key2Url(formalVideoKey));
        video.setStatus(VIDEO_STATUS_REVIEWING);
        video.setCreatedAt(LocalDateTime.now());
        return video;
    }

    private VideoSubmitVO buildSubmitVO(Video video) {
        VideoSubmitVO vo = new VideoSubmitVO();
        vo.setVid(video.getVid());
        vo.setTitle(video.getTitle());
        vo.setCoverUrl(video.getCoverUrl());
        vo.setVideoUrl(video.getVideoUrl());
        vo.setStatus(video.getStatus());
        return vo;
    }

    private void deleteFormalObjectQuietly(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }
        try {
            fileStorageService.deleteObject(objectKey);
        } catch (RuntimeException e) {
            log.warn("回滚正式文件失败：{}", objectKey, e);
        }
    }

    private void deleteTempObjectQuietly(String objectKey) {
        try {
            fileStorageService.deleteObject(objectKey);
        } catch (RuntimeException e) {
            log.warn("删除临时文件失败：{}", objectKey, e);
        }
    }
}
