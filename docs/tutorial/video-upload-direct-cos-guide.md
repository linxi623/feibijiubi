# 菲比啾比：视频直传 COS 与临时文件处理开发步骤

这份文档不是让你一次性把所有代码写完，而是告诉你：如果要采用更标准的视频投稿流程，应该按什么顺序做，每一步写哪些文件、解决什么问题。

目标流程是：

```text
视频文件：前端直传腾讯云 COS，后端不接收整个视频文件
封面图片：前端传给后端，后端上传到 COS
投稿信息：前端把标题、简介、分区、tempVideoKey、tempCoverKey 提交给后端
临时文件：用户上传后不投稿，后端定时清理
```

---

# 一、为什么视频不建议走后端中转？

如果视频走后端中转，流程是：

```text
前端 -> 后端 -> 腾讯云 COS
```

比如用户上传一个 500MB 视频：

```text
前端先传 500MB 给后端
后端再传 500MB 给 COS
```

这样后端服务器会承担：

- 大文件上传压力
- 带宽压力
- 请求超时风险
- 临时文件/内存压力
- 多用户同时上传时的性能压力

所以更标准的视频上传流程是：

```text
前端 -> 腾讯云 COS
```

后端只负责：

```text
生成临时上传地址 / 临时 key
校验用户身份
记录临时文件
投稿时校验 key
把临时文件转成正式文件
保存 video 表
清理过期临时文件
```

一句话：

> 视频文件本体不经过后端，后端只负责授权、校验、记录和管理。

---

# 二、完整业务流程

最终流程应该是：

```text
1. 前端选择视频文件
2. 前端调用后端：申请视频上传地址
3. 后端生成 tempVideoKey 和 uploadUrl，并记录临时文件
4. 前端用 uploadUrl 直接 PUT 上传视频到 COS
5. 前端选择封面图片
6. 前端调用后端：上传封面
7. 后端接收封面 MultipartFile，上传到 COS 临时目录，并记录临时文件
8. 前端填写标题、简介、分区、标签等投稿信息
9. 前端调用投稿接口，提交 tempVideoKey 和 tempCoverKey
10. 后端校验两个临时文件是否有效
11. 后端把临时 COS 对象复制到正式目录
12. 后端删除临时 COS 对象
13. 后端插入 video 表
14. 后端插入 video_status 表
15. 后端把临时文件记录标记为已提交
```

如果用户在第 4 步或第 7 步之后退出页面，没有投稿：

```text
临时文件会留在 COS 的 temp/ 目录
```

后面由定时任务清理。

---

# 三、目录设计

建议 COS 对象 key 这样设计。

## 1. 临时视频目录

```text
temp/videos/{uid}/{yyyyMMdd}/{uuid}.mp4
```

例如：

```text
temp/videos/8/20260708/9f3a2c.mp4
```

含义：

```text
用户 8 在 2026-07-08 上传的临时视频
```

---

## 2. 临时封面目录

```text
temp/covers/{uid}/{yyyyMMdd}/{uuid}.jpg
```

例如：

```text
temp/covers/8/20260708/a12b3c.jpg
```

---

## 3. 正式视频目录

```text
videos/{uid}/{yyyyMMdd}/{uuid}.mp4
```

例如：

```text
videos/8/20260708/9f3a2c.mp4
```

---

## 4. 正式封面目录

```text
covers/{uid}/{yyyyMMdd}/{uuid}.jpg
```

例如：

```text
covers/8/20260708/a12b3c.jpg
```

---

# 四、为什么要分临时目录和正式目录？

因为上传成功不代表投稿成功。

用户可能：

```text
上传了视频
上传了封面
但是没有点击投稿
直接关闭页面
```

如果直接上传到正式目录，会产生很多没人引用的垃圾文件。

所以标准做法是：

```text
上传阶段：放 temp/ 临时目录
投稿成功：复制到正式目录
投稿失败或超时未投稿：定时清理 temp/ 文件
```

---

# 五、数据库怎么改？

你现在已有三张核心表：

```text
video
video_status
user_video
```

要支持“前端直传 + 临时文件处理”，还需要做两件事：

```text
1. video 表增加 cover_key 和 video_key
2. 新增 upload_temp_file 表
```

---

## 1. 为什么 `video` 表要增加 key？

现在你有：

```sql
cover_url
video_url
```

它们是给前端访问用的 URL。

但是后端管理 COS 文件时，最好用 key。

比如：

```text
video_key = videos/8/20260708/abc.mp4
video_url = https://xxx.cos.ap-shenzhen.myqcloud.com/videos/8/20260708/abc.mp4
```

区别：

| 字段 | 作用 |
|---|---|
| `video_key` | 后端复制、删除、校验 COS 对象 |
| `video_url` | 前端播放视频 |
| `cover_key` | 后端管理封面对象 |
| `cover_url` | 前端展示封面 |

所以 `video` 表建议加：

```sql
`cover_key` VARCHAR(500) NOT NULL COMMENT '封面COS对象key',
`video_key` VARCHAR(500) NOT NULL COMMENT '视频COS对象key',
```

---

## 2. 新增 `upload_temp_file` 表

这张表专门记录临时上传文件。

```sql
CREATE TABLE `upload_temp_file` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '临时文件ID',
    `uid` INT NOT NULL COMMENT '上传用户ID',
    `file_type` TINYINT NOT NULL COMMENT '文件类型 1视频 2封面',
    `object_key` VARCHAR(500) NOT NULL COMMENT 'COS临时对象key',
    `original_filename` VARCHAR(255) DEFAULT NULL COMMENT '原始文件名',
    `content_type` VARCHAR(100) DEFAULT NULL COMMENT '文件类型',
    `file_size` BIGINT DEFAULT NULL COMMENT '文件大小',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态 0临时 1已提交 2已清理',
    `expire_at` DATETIME NOT NULL COMMENT '过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_upload_temp_file_object_key` (`object_key`),
    KEY `idx_upload_temp_file_uid_status_expire` (`uid`, `status`, `expire_at`),
    CONSTRAINT `fk_upload_temp_file_uid` FOREIGN KEY (`uid`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='上传临时文件表';
```

---

## 3. `upload_temp_file` 字段解释

| 字段 | 含义 |
|---|---|
| `id` | 临时文件记录 ID |
| `uid` | 谁上传的 |
| `file_type` | 1 视频，2 封面 |
| `object_key` | COS 临时文件 key |
| `original_filename` | 用户原始文件名 |
| `content_type` | 文件 MIME 类型 |
| `file_size` | 文件大小 |
| `status` | 0 临时，1 已提交，2 已清理 |
| `expire_at` | 过期时间 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |

---

# 六、第一步：写申请视频上传地址接口

这个接口不接收视频文件。

它只做一件事：

```text
后端生成一个临时视频 key，并生成一个可以上传到这个 key 的 uploadUrl
```

---

## 1. 接口设计

```http
POST /api/videos/upload-url
```

请求体：

```json
{
  "filename": "demo.mp4",
  "contentType": "video/mp4",
  "fileSize": 104857600
}
```

返回：

```json
{
  "code": 200,
  "message": "获取上传地址成功",
  "data": {
    "tempKey": "temp/videos/8/20260708/abc.mp4",
    "uploadUrl": "https://xxx.cos.xxx.com/temp/videos/8/20260708/abc.mp4?...签名参数...",
    "method": "PUT",
    "expireAt": "2026-07-08T15:30:00",
    "maxFileSize": 524288000
  }
}
```

---

## 2. 创建 DTO

文件：

```text
src/main/java/com/feibijiubi/backend/dto/VideoUploadPrepareDTO.java
```

```java
@Data
public class VideoUploadPrepareDTO {
    private String filename;
    private String contentType;
    private Long fileSize;
}
```

---

## 3. 创建 VO

文件：

```text
src/main/java/com/feibijiubi/backend/vo/VideoUploadPrepareVO.java
```

```java
@Data
public class VideoUploadPrepareVO {
    private String tempKey;
    private String uploadUrl;
    private String method;
    private LocalDateTime expireAt;
    private Long maxFileSize;
}
```

---

## 4. Service 里要做什么？

方法名建议：

```java
VideoUploadPrepareVO prepareVideoUpload(Integer currentUserId, VideoUploadPrepareDTO request);
```

业务逻辑：

```text
1. 校验 currentUserId 不为空
2. 校验 filename 不为空
3. 校验 contentType 是 video/mp4 等允许类型
4. 校验 fileSize > 0
5. 校验 fileSize 不超过最大视频大小
6. 从 filename 中解析后缀，比如 .mp4
7. 生成 tempVideoKey
8. 生成预签名上传 URL
9. 插入 upload_temp_file 表，status = 0
10. 返回 tempKey 和 uploadUrl
```

---

## 5. 为什么要记录到 `upload_temp_file`？

因为前端拿到上传地址后，可能上传了，也可能没上传，也可能上传完不投稿。

后端需要知道：

```text
这个临时 key 是谁申请的
什么时候过期
后面投稿时能不能使用
后面清理时要不要删除
```

所以申请上传地址时就要插入一条临时文件记录。

---

# 七、第二步：前端直传视频到 COS

这一步是前端做的，但你作为后端要知道它怎么工作。

前端拿到：

```json
{
  "tempKey": "temp/videos/8/20260708/abc.mp4",
  "uploadUrl": "https://xxx..."
}
```

然后直接调用：

```http
PUT uploadUrl
Content-Type: video/mp4

视频二进制内容
```

这一步：

```text
视频文件不会经过后端
```

后端只是提前生成了：

```text
tempKey
uploadUrl
```

前端上传成功后，要把 `tempKey` 保存起来，最终投稿时传给后端。

---

# 八、第三步：写封面上传接口

封面图比较小，可以走后端中转。

流程：

```text
前端 -> 后端 -> COS
```

---

## 1. 接口设计

```http
POST /api/videos/cover
Content-Type: multipart/form-data
```

参数：

```text
file: 封面图片
```

返回：

```json
{
  "code": 200,
  "message": "封面上传成功",
  "data": {
    "tempKey": "temp/covers/8/20260708/abc.jpg",
    "url": "https://xxx.cos.xxx.com/temp/covers/8/20260708/abc.jpg",
    "expireAt": "2026-07-08T15:30:00"
  }
}
```

---

## 2. 创建 VO

文件：

```text
src/main/java/com/feibijiubi/backend/vo/TempFileVO.java
```

```java
@Data
public class TempFileVO {
    private String tempKey;
    private String url;
    private LocalDateTime expireAt;
}
```

---

## 3. Service 里做什么？

方法名建议：

```java
TempFileVO uploadCover(Integer currentUserId, MultipartFile file);
```

业务逻辑：

```text
1. 校验用户登录
2. 校验图片不能为空
3. 校验图片大小，比如不超过 2MB
4. 校验图片格式 jpg / jpeg / png
5. 上传到 COS 临时封面目录 temp/covers/{uid}/{date}/{uuid}.jpg
6. 插入 upload_temp_file 表，file_type = 2，status = 0
7. 返回 tempCoverKey 和 URL
```

---

# 九、第四步：写投稿接口

这个接口才是真正创建视频记录。

注意：

```text
投稿接口不接收视频文件
投稿接口也不接收封面文件
投稿接口只接收 tempVideoKey 和 tempCoverKey
```

---

## 1. 接口设计

```http
POST /api/videos
```

请求体：

```json
{
  "title": "菲比啾比第一个视频",
  "sourceType": 1,
  "visibility": 0,
  "duration": 120.5,
  "mcId": "knowledge",
  "scId": "programming",
  "tags": "Java,Spring Boot,后端",
  "description": "这是一个测试视频",
  "tempCoverKey": "temp/covers/8/20260708/abc.jpg",
  "tempVideoKey": "temp/videos/8/20260708/def.mp4"
}
```

返回：

```json
{
  "code": 200,
  "message": "投稿成功，等待审核",
  "data": {
    "vid": 1001,
    "title": "菲比啾比第一个视频",
    "coverUrl": "https://xxx/covers/8/20260708/abc.jpg",
    "videoUrl": "https://xxx/videos/8/20260708/def.mp4",
    "status": 0
  }
}
```

---

## 2. 创建 DTO

文件：

```text
src/main/java/com/feibijiubi/backend/dto/VideoSubmitDTO.java
```

```java
@Data
public class VideoSubmitDTO {
    private String title;
    private Integer sourceType;
    private Integer visibility;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String tempCoverKey;
    private String tempVideoKey;
}
```

---

## 3. 创建 VO

文件：

```text
src/main/java/com/feibijiubi/backend/vo/VideoSubmitVO.java
```

```java
@Data
public class VideoSubmitVO {
    private Integer vid;
    private String title;
    private String coverUrl;
    private String videoUrl;
    private Integer status;
}
```

---

## 4. Service 里做什么？

方法名建议：

```java
VideoSubmitVO submitVideo(Integer currentUserId, VideoSubmitDTO request);
```

这个方法应该加事务：

```java
@Transactional
```

业务逻辑：

```text
1. 校验用户登录
2. 校验标题不能为空
3. 校验分区不能为空
4. 校验 duration > 0
5. 校验 tempVideoKey 不能为空
6. 校验 tempCoverKey 不能为空
7. 校验 tempVideoKey 必须以 temp/videos/{uid}/ 开头
8. 校验 tempCoverKey 必须以 temp/covers/{uid}/ 开头
9. 查询 upload_temp_file，确认视频临时文件记录存在
10. 查询 upload_temp_file，确认封面临时文件记录存在
11. 确认两条记录 uid 都是当前用户
12. 确认 status = 0，还未提交
13. 确认 expire_at 没过期
14. 调 COS 检查临时视频对象是否真实存在
15. 调 COS 检查临时封面对象是否真实存在
16. 生成正式 videoKey 和 coverKey
17. 把临时视频复制到正式视频 key
18. 把临时封面复制到正式封面 key
19. 删除临时视频对象
20. 删除临时封面对象
21. 插入 video 表
22. 插入 video_status 表，统计值默认为 0
23. 把两条 upload_temp_file 标记为已提交 status = 1
24. 返回 VideoSubmitVO
```

---

# 十、为什么投稿时还要检查 COS 对象是否存在？

因为申请上传地址不代表前端真的上传成功。

可能发生：

```text
1. 前端申请了 uploadUrl
2. 但上传视频失败了
3. 前端却仍然调用投稿接口
```

如果后端不检查 COS 对象是否存在，就会保存一条不能播放的视频。

所以投稿前必须检查：

```text
tempVideoKey 对应的 COS 对象是否存在
tempCoverKey 对应的 COS 对象是否存在
```

---

# 十一、为什么要校验 key 属于当前用户？

前端提交：

```json
{
  "tempVideoKey": "temp/videos/8/20260708/abc.mp4"
}
```

如果当前登录用户是 9，但他传了用户 8 的 key，这就有安全问题。

所以后端要检查：

```text
tempVideoKey 必须以 temp/videos/{currentUserId}/ 开头
tempCoverKey 必须以 temp/covers/{currentUserId}/ 开头
```

例如当前用户是 9，则只能使用：

```text
temp/videos/9/...
temp/covers/9/...
```

不能使用：

```text
temp/videos/8/...
```

---

# 十二、第五步：写临时文件清理任务

如果用户上传了文件但不投稿，这些文件不能一直留在 COS。

所以需要一个定时任务。

---

## 1. 开启定时任务

在启动类：

```text
src/main/java/com/feibijiubi/backend/BackendApplication.java
```

加：

```java
@EnableScheduling
```

---

## 2. 创建清理 Service

文件：

```text
src/main/java/com/feibijiubi/backend/service/impl/storage/TempUploadCleanupService.java
```

它负责：

```text
定时查找过期临时文件
删除 COS 对象
更新数据库状态
```

---

## 3. 清理逻辑

每 30 分钟执行一次：

```text
1. 查询 upload_temp_file
2. 条件：status = 0
3. 条件：expire_at < 当前时间
4. 每次最多查 100 条
5. 遍历这些记录
6. 调 COS deleteObject 删除 object_key
7. 如果对象不存在，也算清理成功
8. 更新 status = 2
```

伪代码：

```java
@Scheduled(fixedDelay = 30 * 60 * 1000)
public void cleanExpiredTempFiles() {
    List<UploadTempFile> files = uploadTempFileMapper.selectExpiredTempFiles(LocalDateTime.now(), 100);

    for (UploadTempFile file : files) {
        try {
            fileStorageService.deleteObject(file.getObjectKey());
        } finally {
            uploadTempFileMapper.markCleaned(file.getObjectKey());
        }
    }
}
```

---

# 十三、需要写哪些文件？

## 1. Entity

```text
src/main/java/com/feibijiubi/backend/entity/UploadTempFile.java
```

还要修改：

```text
src/main/java/com/feibijiubi/backend/entity/Video.java
```

给 `Video` 加：

```java
private String coverKey;
private String videoKey;
```

---

## 2. DTO

```text
src/main/java/com/feibijiubi/backend/dto/VideoUploadPrepareDTO.java
src/main/java/com/feibijiubi/backend/dto/VideoSubmitDTO.java
```

---

## 3. VO

```text
src/main/java/com/feibijiubi/backend/vo/VideoUploadPrepareVO.java
src/main/java/com/feibijiubi/backend/vo/TempFileVO.java
src/main/java/com/feibijiubi/backend/vo/VideoSubmitVO.java
```

---

## 4. Mapper

```text
src/main/java/com/feibijiubi/backend/mapper/UploadTempFileMapper.java
src/main/resources/com/feibijiubi/backend/mapper/UploadTempFileMapper.xml
```

同时需要完善已有：

```text
src/main/java/com/feibijiubi/backend/mapper/VideoMapper.java
src/main/resources/com/feibijiubi/backend/mapper/VideoMapper.xml
src/main/java/com/feibijiubi/backend/mapper/VideoStatusMapper.java
src/main/resources/com/feibijiubi/backend/mapper/VideoStatusMapper.xml
```

---

## 5. Service

```text
src/main/java/com/feibijiubi/backend/service/video/VideoService.java
src/main/java/com/feibijiubi/backend/service/impl/video/VideoServiceImpl.java
```

---

## 6. Controller

```text
src/main/java/com/feibijiubi/backend/controller/VideoController.java
```

---

## 7. Storage

修改：

```text
src/main/java/com/feibijiubi/backend/service/storage/FileStorageService.java
src/main/java/com/feibijiubi/backend/service/impl/storage/TencentCosStorageServiceImpl.java
```

需要支持：

```java
TempFileVO uploadTempCover(MultipartFile file, Integer uid);
VideoUploadPrepareVO prepareVideoUpload(Integer uid, VideoUploadPrepareDTO request);
boolean objectExists(String objectKey);
String buildUrl(String objectKey);
String promoteTempObject(String tempKey, String finalKey);
void deleteObject(String objectKey);
```

---

# 十四、推荐开发顺序

不要一次全写。

建议按这个顺序：

```text
第 1 步：改数据库，增加 cover_key / video_key / upload_temp_file
第 2 步：写 UploadTempFile 实体
第 3 步：写 UploadTempFileMapper
第 4 步：扩展 TencentCosProperties 配置
第 5 步：扩展 FileStorageService
第 6 步：实现申请视频上传 URL
第 7 步：用 Postman / curl 测试 uploadUrl 是否能上传视频
第 8 步：实现封面上传接口
第 9 步：实现投稿接口
第 10 步：实现临时文件清理任务
```

---

# 十五、每一步完成标准

## 第 1 步完成标准

数据库中有：

```text
video.cover_key
video.video_key
upload_temp_file 表
```

---

## 第 2-3 步完成标准

可以通过 Mapper：

```text
插入临时文件记录
根据 object_key 查询临时文件
标记已提交
标记已清理
查询过期临时文件
```

---

## 第 6 步完成标准

调用：

```http
POST /api/videos/upload-url
```

能得到：

```text
tempKey
uploadUrl
expireAt
```

数据库 `upload_temp_file` 多一条：

```text
file_type = 1
status = 0
object_key = temp/videos/...
```

---

## 第 7 步完成标准

用返回的 `uploadUrl` 上传小视频后，COS 里能看到：

```text
temp/videos/{uid}/{date}/{uuid}.mp4
```

---

## 第 8 步完成标准

调用：

```http
POST /api/videos/cover
```

能得到：

```text
tempCoverKey
url
expireAt
```

COS 里有：

```text
temp/covers/{uid}/{date}/{uuid}.jpg
```

数据库 `upload_temp_file` 多一条：

```text
file_type = 2
status = 0
```

---

## 第 9 步完成标准

调用：

```http
POST /api/videos
```

后：

```text
1. video 表新增一条记录
2. video_status 表新增一条记录
3. video_key 不以 temp/ 开头
4. cover_key 不以 temp/ 开头
5. upload_temp_file 对应记录 status = 1
6. COS 正式目录下有视频和封面
7. COS 临时目录下对应文件被删除
```

---

## 第 10 步完成标准

如果只申请上传地址或上传封面，但不投稿：

```text
过期后定时任务会删除 COS 临时对象
upload_temp_file.status 会变成 2
```

---

# 十六、接口最终汇总

## 1. 申请视频上传地址

```http
POST /api/videos/upload-url
```

用途：

```text
后端生成临时视频 key 和预签名上传 URL
```

---

## 2. 上传封面

```http
POST /api/videos/cover
```

用途：

```text
后端接收封面图片并上传到 COS 临时目录
```

---

## 3. 投稿

```http
POST /api/videos
```

用途：

```text
提交标题、简介、分区、标签、tempVideoKey、tempCoverKey，创建视频记录
```

---

# 十七、你要注意的坑

## 1. 不要把腾讯云永久密钥返回给前端

错误做法：

```text
把 secretId / secretKey 返回给前端
```

这样非常危险。

正确做法：

```text
返回短期有效的预签名 uploadUrl
```

---

## 2. 不要相信前端传来的 URL

投稿时不要让前端传：

```json
{
  "videoUrl": "https://xxx.com/xxx.mp4"
}
```

更推荐传：

```json
{
  "tempVideoKey": "temp/videos/8/20260708/abc.mp4"
}
```

然后后端自己根据 key 构造正式 URL。

---

## 3. 不要把 temp key 存进正式 video 表

错误：

```text
video.video_key = temp/videos/8/xxx.mp4
```

正确：

```text
video.video_key = videos/8/xxx.mp4
```

正式视频表里应该存正式目录。

---

## 4. 不要在 Controller 写复杂逻辑

Controller 只负责：

```text
接收请求
取 currentUserId
调用 Service
返回 ApiResponse
```

这些逻辑应该放 Service：

```text
校验临时文件
复制 COS 对象
删除 COS 对象
插入 video
插入 video_status
标记临时文件状态
```

---

## 5. COS 操作不能被数据库事务自动回滚

`@Transactional` 只能回滚数据库。

如果你已经把 COS 临时文件复制到正式目录，但后面数据库插入失败，数据库会回滚，但 COS 文件不会自动删除。

所以 Service 里要考虑：

```text
如果数据库失败，尽量删除已经复制出来的正式 COS 文件
```

---

# 十八、总结

你要做的不是“后端接收整个视频文件”，而是：

```text
后端生成临时 key 和上传 URL
前端直接把视频上传到 COS
后端记录这个临时 key
投稿时后端校验临时 key
校验成功后转成正式文件
写入 video 和 video_status
过期未投稿的临时文件定时清理
```

最终你要形成的核心认知是：

```text
上传成功 ≠ 投稿成功
```

上传只是创建了一个临时资源。

只有投稿接口成功执行后，视频才正式进入系统。