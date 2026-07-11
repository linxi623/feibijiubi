package com.feibijiubi.backend.service.impl.storage;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.TencentCosProperties;
import com.feibijiubi.backend.dto.VideoUploadPrepareDTO;
import com.feibijiubi.backend.entity.UploadTempFile;
import com.feibijiubi.backend.mapper.UploadTempFileMapper;
import com.feibijiubi.backend.service.storage.FileStorageService;
import com.feibijiubi.backend.vo.VideoUploadPrepareVO;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.utils.Jackson;
import com.tencent.cloud.CosStsClient;
import com.tencent.cloud.Policy;
import com.tencent.cloud.Response;
import com.tencent.cloud.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TencentCosStorageServiceImpl implements FileStorageService{
    private final COSClient cosClient;
    private final TencentCosProperties properties;
    private final UploadTempFileMapper uploadTempFileMapper;
    private static final long MAX_IMAGE_SIZE = 2 * 1024 * 1024;
    private static final long MAX_VIDEO_SIZE = 2L * 1024 * 1024 * 1024;
    private static final Set<String> ALLOWED_IMAGE_CONTENT_EXTENSIONS = Set.of("image/jpg", "image/jpeg", "image/png");
    private static final Set<String> ALLOWED_VIDEO_CONTENT_EXTENSIONS = Set.of("video/mp4", "video/3gp", "video/mpeg");
    @Override
    public String uploadImage(Integer currentUserId, MultipartFile file, String directory) {
        validateImage(file);

        String objectKey = buildImageKey(currentUserId, file, directory);

        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(file.getContentType());

            PutObjectRequest request = new PutObjectRequest(
                    properties.getBucket(),
                    objectKey,
                    file.getInputStream(),
                    metadata
            );

            cosClient.putObject(request);
            return buildUrl(objectKey);
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            throw new BusinessException(500, "文件上传失败");
        } catch (CosServiceException e) {
            log.error("腾讯云 COS 服务异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "文件上传失败");
        } catch (CosClientException e) {
            log.error("腾讯云 COS 客户端异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "文件上传失败");
        }
    }

    @Override
    public VideoUploadPrepareVO uploadPrepare(Integer currentUserId, VideoUploadPrepareDTO request) {
        if(currentUserId == null) {
            throw new BusinessException(403, "登录失效");
        }
        validateVideo(request);
        String tempKey = buildVideoKey(currentUserId, request);
        Response response = buildTempSecretKey(tempKey);

        LocalDateTime expireAt = LocalDateTime.ofEpochSecond(response.expiredTime, 0, ZoneOffset.ofHours(8));

        UploadTempFile tempFile = new UploadTempFile();
        tempFile.setUid(currentUserId);
        tempFile.setFileType((byte) 1);
        tempFile.setObjectKey(tempKey);
        tempFile.setOriginalFilename(request.getFileName());
        tempFile.setContentType(request.getContentType());
        tempFile.setFileSize(request.getFileSize());
        tempFile.setStatus((byte) 0);
        tempFile.setExpireAt(expireAt);

        uploadTempFileMapper.insert(tempFile);


        VideoUploadPrepareVO vo = new VideoUploadPrepareVO();

        vo.setTempKey(tempKey);
        vo.setBucket(properties.getBucket());
        vo.setRegion(properties.getRegion());
        vo.setTmpSecretId(response.credentials.tmpSecretId);
        vo.setTmpSecretKey(response.credentials.tmpSecretKey);
        vo.setSessionToken(response.credentials.sessionToken);
        vo.setStartTime(response.startTime);
        vo.setExpiredTime(response.expiredTime);
        vo.setMaxFileSize(MAX_VIDEO_SIZE);

        return vo;
    }

    @Override
    public String Url2Key(String url) {
        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new BusinessException(500, "COS baseUrl 未配置");
        }

        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        if (!url.startsWith(normalizedBaseUrl + "/")) {
            throw new BusinessException(400, "文件 URL 不属于当前 COS 域名");
        }

        return url.substring(normalizedBaseUrl.length() + 1);
    }

    @Override
    public String Key2Url(String key) {
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(400, "文件 key 不能为空");
        }

        String baseUrl = properties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new BusinessException(500, "COS baseUrl 未配置");
        }

        if (baseUrl.endsWith("/")) {
            return baseUrl + key;
        }

        return baseUrl + "/" + key;
    }

    @Override
    public boolean objectExists(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BusinessException(400, "文件 key 不能为空");
        }

        try {
            return cosClient.doesObjectExist(properties.getBucket(), objectKey);
        } catch (CosServiceException e) {
            log.error("检查 COS 文件是否存在失败：{}", e.getMessage(), e);
            throw new BusinessException(500, "检查文件失败");
        } catch (CosClientException e) {
            log.error("腾讯云 COS 客户端异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "检查文件失败");
        }
    }

    @Override
    public void copyObject(String sourceKey, String targetKey) {
        if (!StringUtils.hasText(sourceKey) || !StringUtils.hasText(targetKey)) {
            throw new BusinessException(400, "文件 key 不能为空");
        }

        try {
            cosClient.copyObject(properties.getBucket(), sourceKey, properties.getBucket(), targetKey);
        } catch (CosServiceException e) {
            log.error("复制 COS 文件失败：{} -> {}，{}", sourceKey, targetKey, e.getMessage(), e);
            throw new BusinessException(500, "复制文件失败");
        } catch (CosClientException e) {
            log.error("腾讯云 COS 客户端异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "复制文件失败");
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            throw new BusinessException(400, "文件 key 不能为空");
        }

        try {
            cosClient.deleteObject(properties.getBucket(), objectKey);
        } catch (CosServiceException e) {
            log.error("删除 COS 文件失败：{}，{}", objectKey, e.getMessage(), e);
            throw new BusinessException(500, "删除文件失败");
        } catch (CosClientException e) {
            log.error("腾讯云 COS 客户端异常：{}", e.getMessage(), e);
            throw new BusinessException(500, "删除文件失败");
        }
    }

    @Override
    public String buildFormalVideoKey(Integer currentUserId, String tempVideoKey) {
        return buildFormalKey("videos", currentUserId, tempVideoKey);
    }

    @Override
    public String buildFormalCoverKey(Integer currentUserId, String tempCoverKey) {
        return buildFormalKey("covers", currentUserId, tempCoverKey);
    }


    private void validateVideo(VideoUploadPrepareDTO request) {
        if (request == null) {
            throw new BusinessException(400, "请求参数不能为空");
        }
        if (!ALLOWED_VIDEO_CONTENT_EXTENSIONS.contains(request.getContentType())) {
            throw new BusinessException(400, "该视频文件格式不支持");
        }
        if (request.getFileSize() == null || request.getFileSize() <= 0) {
            throw new BusinessException(400, "文件大小不合法");
        }
        if (request.getFileSize() > MAX_VIDEO_SIZE) {
            throw new BusinessException(400, "该文件超出限制");
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "图片不能为空");
        }

        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException(400, "图片大小不能超过2MB");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_IMAGE_CONTENT_EXTENSIONS.contains(contentType)) {
            throw new BusinessException(400, "图片格式不支持");
        }
    }

    private String buildVideoKey(Integer currentUserId, VideoUploadPrepareDTO request) {
        String extension = getExtension(request.getFileName());

        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

        return "temp/videos/" + currentUserId + "/" + date + "/" + UUID.randomUUID().toString().replace("-", "") + extension;
    }

    private String buildImageKey(Integer currentUserId, MultipartFile file, String directory) {
        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename);

        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;

        return directory + currentUserId + "/" + date + "/" + filename;
    }

    private String buildFormalKey(String directory, Integer currentUserId, String tempKey) {
        if (currentUserId == null) {
            throw new BusinessException(401, "请先登录");
        }
        String extension = getExtension(tempKey);
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return directory + "/" + currentUserId + "/" + date + "/" + UUID.randomUUID().toString().replace("-", "") + extension;
    }

    private String getExtension(String filename) {
        if (!StringUtils.hasText(filename) || !filename.contains(".")) {
            throw new BusinessException(400, "文件后缀不能为空");
        }

        return filename.substring(filename.lastIndexOf(".")).toLowerCase();
    }

    private String buildUrl(String objectKey) {
        String baseUrl = properties.getBaseUrl();

        if (baseUrl.endsWith("/")) {
            return baseUrl + objectKey;
        }
        return baseUrl + "/" + objectKey;
    }

    private Response buildTempSecretKey(String objectKey) {
        TreeMap<String, Object> config = new TreeMap<String, Object>();
        try {
            config.put("secretId", properties.getSecretId());
            config.put("secretKey", properties.getSecretKey());
            config.put("bucket", properties.getBucket());
            config.put("region", properties.getRegion());
            config.put("durationSeconds", 1800);

            Policy policy = new Policy();

            Statement statement = new Statement();

            statement.setEffect("allow");

            statement.addActions(new String[]{
                    "cos:PutObject",
                    // 表单上传、小程序上传
                    "cos:PostObject",
                    // 分块上传
                    "cos:InitiateMultipartUpload",
                    "cos:ListMultipartUploads",
                    "cos:ListParts",
                    "cos:UploadPart",
                    "cos:CompleteMultipartUpload",
            });


            statement.addResources(new String[]{"qcs::cos:" + properties.getRegion() + ":uid/" + properties.getAppId() +
                    ":" + properties.getBucket() + "/" + objectKey});

            policy.addStatement(statement);
            config.put("policy", Jackson.toJsonPrettyString(policy));

            Response response = CosStsClient.getCredential(config);
            return response;

        } catch (Exception e) {
            throw new BusinessException(500, e.getMessage());
        }
    }
}
