package com.feibijiubi.backend.service.impl.video;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.converter.VideoConverter;
import com.feibijiubi.backend.dto.VideoSubmitDTO;
import com.feibijiubi.backend.entity.UploadTempFile;
import com.feibijiubi.backend.entity.User;
import com.feibijiubi.backend.entity.UserVideo;
import com.feibijiubi.backend.entity.Video;
import com.feibijiubi.backend.entity.VideoStatus;
import com.feibijiubi.backend.enums.VideoReviewStatus;
import com.feibijiubi.backend.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.mapper.UserFollowMapper;
import com.feibijiubi.backend.mapper.UserMapper;
import com.feibijiubi.backend.mapper.UserVideoMapper;
import com.feibijiubi.backend.mapper.VideoMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.feibijiubi.backend.service.video.VideoService;
import com.feibijiubi.backend.vo.CursorPageVO;
import com.feibijiubi.backend.vo.VideoDetailVO;
import com.feibijiubi.backend.vo.VideoListItemVO;
import com.feibijiubi.backend.vo.VideoSubmitVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;


@Slf4j
@Service
public class VideoServiceImpl implements VideoService {
    private static final byte FILE_TYPE_VIDEO = 1;
    private static final byte FILE_TYPE_COVER = 2;
    private static final byte TEMP_STATUS_WAIT_SUBMIT = 0;


    private final UploadTempFileMapper uploadTempFileMapper;
    private final VideoMapper videoMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final FileStorageService fileStorageService;
    private final UserVideoMapper userVideoMapper;
    private final UserMapper userMapper;
    private final UserFollowMapper userFollowMapper;

    public VideoServiceImpl(UploadTempFileMapper uploadTempFileMapper,
                            VideoMapper videoMapper,
                            VideoStatusMapper videoStatusMapper,
                            FileStorageService fileStorageService,
                            UserVideoMapper userVideoMapper,
                            UserMapper userMapper,
                            UserFollowMapper userFollowMapper) {
        this.uploadTempFileMapper = uploadTempFileMapper;
        this.videoMapper = videoMapper;
        this.videoStatusMapper = videoStatusMapper;
        this.fileStorageService = fileStorageService;
        this.userVideoMapper = userVideoMapper;
        this.userMapper = userMapper;
        this.userFollowMapper = userFollowMapper;
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
        return objectKey;
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

            return VideoConverter.toVideoSubmitVO(video);
        } catch (RuntimeException e) {
            deleteFormalObjectQuietly(copiedVideoKey);
            deleteFormalObjectQuietly(copiedCoverKey);
            throw new BusinessException(500, e.getMessage());
        }
    }

    @Override
    public VideoDetailVO getVideoDetail(Integer currentUserId, Integer vid) {
        if (vid == null || vid <= 0) {
            throw new BusinessException(400, "视频参数不合法");
        }

        Video video = videoMapper.selectPublishedByVid(vid);
        if (video == null) {
            throw new BusinessException(404, "视频不存在");
        }

        if (Objects.equals(video.getVisibility(), (byte) 1)
                && !Objects.equals(video.getUid(), currentUserId)) {
            throw new BusinessException(403, "你无权查看此视频");
        }

        VideoStatus videoStatus = videoStatusMapper.selectByVid(vid);
        if (videoStatus == null) {
            throw new BusinessException(500, "视频统计数据异常");
        }

        UserVideo userVideo = currentUserId == null
                ? null
                : userVideoMapper.selectByUidAndVid(currentUserId, vid);
        User author = userMapper.selectById(video.getUid());
        if (author == null) {
            throw new BusinessException(500, "视频作者数据异常");
        }
        Integer videoCount = videoMapper.countVideoByUid(video.getUid());
        Integer fansCount = userFollowMapper.countFans(video.getUid());
        boolean subscribed = currentUserId != null
                && !Objects.equals(currentUserId, video.getUid())
                && Boolean.TRUE.equals(userFollowMapper.checkExist(currentUserId, video.getUid()));

        return VideoConverter.toVideoDetailVO(
                video, videoStatus, userVideo, author, videoCount, fansCount, subscribed
        );
    }

    @Override
    public CursorPageVO<VideoListItemVO> getVideoFeed(String cursor, Integer size) {
        String[] string = parseCursor(cursor);

        LocalDateTime cursorCreatedAt = null;
        Integer cursorVid = null;

        if(cursor != null) {
            cursorCreatedAt = LocalDateTime.parse(string[0]);
            cursorVid = Integer.parseInt(string[1]);
        }

        List<VideoListItemVO> list = videoMapper.selectFeed(cursorCreatedAt, cursorVid, size + 1);

        boolean hasMore = list.size() > size;
        if(hasMore) {
            list = list.subList(0, size);
        }
        String nextCursor = null;
        if(hasMore && !list.isEmpty()) {
            VideoListItemVO last = list.get(list.size() - 1);

            nextCursor = last.getCreatedAt().toString() + "_" + last.getVid().toString();
        }

        CursorPageVO<VideoListItemVO> cursorPageVO = new CursorPageVO<>();
        cursorPageVO.setItems(list);
        cursorPageVO.setNextCursor(nextCursor);
        cursorPageVO.setHasMore(hasMore);

        return cursorPageVO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVideo(Integer currentUserId, Integer vid) {
        if(currentUserId == null) {
            throw new BusinessException(401, "请重新登录");
        }
        if(vid == null || vid <= 0) {
            throw new BusinessException(400, "视频参数不合法");
        }
        Video video = videoMapper.selectByVid(vid);
        if (video == null) {
            throw new BusinessException(404, "视频不存在");
        }
        if (video.getDeletedAt() != null) {
            throw new BusinessException(404, "视频已经删除");
        }
        if(!Objects.equals(currentUserId, video.getUid())) {
            throw new BusinessException(403, "你无权删除该视频");
        }
        // 防范并发错误
        int rows = videoMapper.softDeleteByOwner(vid, currentUserId, LocalDateTime.now());
        if (rows != 1) {
            throw new BusinessException(409, "视频已经被删除，请勿重复操作");
        }
        // 事务提交后再删除cos的视频资源
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new BusinessException(500, "视频删除事务未正确开启");
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteFormalObjectQuietly(video.getCoverKey());
                        deleteFormalObjectQuietly(video.getVideoKey());
                    }
                }
        );
    }

    private String[] parseCursor(String cursor) {
        if(!StringUtils.hasText(cursor)) {
            return null;
        }

        try {
            int separatorIndex = cursor.lastIndexOf("_");

            if(separatorIndex <= 0 || separatorIndex == cursor.length() - 1) {
                throw new BusinessException(500, "游标格式有误");
            }

            String createdAtText = cursor.substring(0, separatorIndex);
            String vidText = cursor.substring(separatorIndex + 1);
            if(!StringUtils.hasText(createdAtText)) {
                throw new BusinessException(500, "加载视频的创建时间不能为空");
            }
            if(!StringUtils.hasText(vidText)) {
                throw new BusinessException(500, "加载视频的vid不能为空");
            }

            return new String[]{createdAtText, vidText};

        } catch (Exception e) {
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
        if (request.getDuration() == null || request.getDuration() <= 0) {
            throw new BusinessException(400, "视频时长不合法");
        }
        if (request.getVisibility() == null
                || (request.getVisibility() != 0 && request.getVisibility() != 1)) {
            throw new BusinessException(400, "视频可见性不合法");
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
        video.setVisibility(request.getVisibility().byteValue());
        video.setDuration(request.getDuration());
        video.setMcId(request.getMcId());
        video.setScId(request.getScId());
        video.setTags(request.getTags());
        video.setDescription(request.getDescription());
        video.setCoverKey(formalCoverKey);
        video.setCoverUrl(fileStorageService.Key2Url(formalCoverKey));
        video.setVideoKey(formalVideoKey);
        video.setVideoUrl(fileStorageService.Key2Url(formalVideoKey));
        video.setStatus((byte) VideoReviewStatus.PENDING.getCode());
        video.setCreatedAt(LocalDateTime.now());
        return video;
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
