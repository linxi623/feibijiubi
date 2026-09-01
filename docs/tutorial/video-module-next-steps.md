# 菲比啾比视频模块：三张表写好后从哪里开始做

你现在已经有了视频模块最核心的三张表：

```text
video          视频基础信息表
video_status   视频数据统计表
user_video     用户-视频关系表
```

接下来不要急着一口气把“播放、点赞、投币、收藏、评论、弹幕、举报、分享”全部做完。标准后端开发更推荐按功能闭环一点点推进。

建议开发顺序是：

```text
实体类 Entity
  ↓
Mapper 接口
  ↓
Mapper XML / MyBatis 注解 SQL
  ↓
Service 业务层
  ↓
Controller 接口层
  ↓
DTO / VO 请求响应对象
  ↓
接口测试
```

---

## 一、先明确这三张表分别负责什么

### 1. `video`：视频基础信息

负责存一个视频本身的信息。

例如：

```text
视频ID
投稿用户ID
标题
来源类型
可见性
时长
主分区
子分区
标签
简介
封面地址
视频地址
审核状态
创建时间
删除时间
```

它对应实体：

```java
Video
```

它解决的问题是：

> 这个视频是什么？是谁发的？标题是什么？视频文件在哪？现在是什么状态？

---

### 2. `video_status`：视频统计信息

负责存一个视频整体的数据统计。

例如：

```text
播放数
点赞数
点踩数
评论数
投币数
分享数
收藏数
弹幕数
```

它对应实体：

```java
VideoStatus
```

它解决的问题是：

> 这个视频整体表现怎么样？有多少播放、点赞、收藏？

---

### 3. `user_video`：用户对视频的个人状态

负责存某个用户对某个视频的行为状态。

例如：

```text
用户是否点赞
用户投了几个币
用户是否收藏
用户观看了多久
用户最近什么时候观看
用户最近什么时候点赞
用户最近什么时候投币
```

它对应实体：

```java
UserVideo
```

它解决的问题是：

> 当前登录用户和这个视频之间是什么关系？有没有点赞？有没有收藏？投币了吗？

---

## 二、接下来最推荐先做哪个功能？

建议先做：

```text
视频投稿 + 视频详情查询
```

原因是：

1. `video` 表是核心表，其他表都依赖它。
2. 用户只有先投稿视频，才会有点赞、收藏、投币、播放等行为。
3. 视频详情页会把三张表都串起来，是最适合练习后端分层的功能。

不要一开始就做点赞、收藏、投币。因为这些功能都依赖视频已经存在。

不过这里要补充一个关键点：

> 真正的视频投稿不是只保存标题、简介这些文字信息，还要先把封面文件和视频文件上传到服务器或对象存储，拿到 `coverUrl` 和 `videoUrl` 后，再插入 `video` 表。

所以更完整的流程应该是：

```text
先上传封面和视频文件
  ↓
拿到 coverUrl 和 videoUrl
  ↓
提交视频投稿信息
  ↓
保存 video 表
  ↓
初始化 video_status 表
```

也就是说，`POST /api/videos` 这个“投稿接口”本质上保存的是视频元数据；真正的文件上传可以单独做成上传接口。

---

## 三、推荐开发路线总览

建议按照这个顺序做：

```text
第 1 步：完善 Entity 和数据库字段对应关系
第 2 步：写 VideoMapper / VideoStatusMapper / UserVideoMapper
第 3 步：做视频投稿接口
第 4 步：做视频详情接口
第 5 步：做视频列表接口
第 6 步：做播放记录接口
第 7 步：做点赞接口
第 8 步：做投币接口
第 9 步：做收藏接口
第 10 步：再考虑评论、弹幕、举报、分享
```

---

# 第 1 步：先检查 Entity 是否和表字段一致

## 1. `Video` 实体

你的 `Video` 应该大致对应：

```java
public class Video {
    private Integer vid;
    private Integer uid;
    private String title;
    private Integer sourceType;
    private Integer visibility;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String coverUrl;
    private String videoUrl;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
}
```

数据库字段是下划线命名：

```text
source_type
mc_id
sc_id
cover_url
video_url
created_at
deleted_at
```

Java 字段是驼峰命名：

```text
sourceType
mcId
scId
coverUrl
videoUrl
createdAt
deletedAt
```

如果你用了 MyBatis，需要确认配置里开启了下划线转驼峰：

```properties
mybatis.configuration.map-underscore-to-camel-case=true
```

否则数据库的 `source_type` 不会自动映射到 Java 的 `sourceType`。

---

## 2. `VideoStatus` 实体

建议命名保持和数据库一致的驼峰形式：

```java
public class VideoStatus {
    private Integer vid;
    private Integer playTimes;
    private Integer likeTimes;
    private Integer unlikeTimes;
    private Integer commentTimes;
    private Integer coinTimes;
    private Integer shareTimes;
    private Integer collectTimes;
    private Integer danmuTimes;
}
```

---

## 3. `UserVideo` 实体

建议保持：

```java
public class UserVideo {
    private Integer id;
    private Integer vid;
    private Integer uid;
    private Double playTime;
    private Boolean like;
    private Byte coin;
    private Boolean collect;
    private LocalDateTime playedAt;
    private LocalDateTime likedAt;
    private LocalDateTime coinedAt;
}
```

注意：

```java
private Boolean like;
```

数据库字段如果叫：

```sql
`like` TINYINT(1)
```

虽然可以用，但 `like` 是 SQL 关键字，写 SQL 时一定要加反引号：

```sql
`like`
```

新手阶段为了少踩坑，也可以把字段改成：

```text
liked
```

Java：

```java
private Boolean liked;
```

数据库：

```sql
`liked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否点赞 0否 1是'
```

不过如果你现在已经写成 `like`，也可以先不改，只要 SQL 里记得加反引号。

---

# 第 2 步：写 Mapper 层

Mapper 层负责和数据库交互。

建议创建：

```text
src/main/java/com/feibijiubi/backend/mapper/VideoMapper.java
src/main/java/com/feibijiubi/backend/mapper/VideoStatusMapper.java
src/main/java/com/feibijiubi/backend/mapper/UserVideoMapper.java
```

---

## 1. `VideoMapper`

应该先提供这些方法：

```java
@Mapper
public interface VideoMapper {
    int insert(Video video);

    Video selectById(Integer vid);

    List<Video> selectPublishedList();
}
```

先不要写太多方法。

初期够用就行。

---

## 2. `VideoStatusMapper`

建议先提供：

```java
@Mapper
public interface VideoStatusMapper {
    int insertDefault(Integer vid);

    VideoStatus selectByVid(Integer vid);

    int increasePlayTimes(Integer vid);

    int increaseLikeTimes(Integer vid);

    int decreaseLikeTimes(Integer vid);

    int increaseCoinTimes(Integer vid, Integer coin);

    int increaseCollectTimes(Integer vid);

    int decreaseCollectTimes(Integer vid);
}
```

注意：

视频投稿成功后，应该立刻给这个视频创建一条默认统计记录。

也就是：

```text
video 插入成功
  ↓
video_status 插入一条 vid 相同、统计值全是 0 的记录
```

---

## 3. `UserVideoMapper`

建议先提供：

```java
@Mapper
public interface UserVideoMapper {
    UserVideo selectByUidAndVid(Integer uid, Integer vid);

    int insert(UserVideo userVideo);

    int updatePlay(UserVideo userVideo);

    int updateLike(UserVideo userVideo);

    int updateCoin(UserVideo userVideo);

    int updateCollect(UserVideo userVideo);
}
```

`user_video` 表的核心查询就是：

```sql
WHERE uid = ? AND vid = ?
```

因为它表示：

> 某个用户对某个视频的状态。

---

# 第 3 步：先做文件上传接口

## 为什么要先做上传？

你说得对，如果只写：

```http
POST /api/videos
```

然后请求体里直接传：

```json
{
  "coverUrl": "...",
  "videoUrl": "..."
}
```

这只是“保存视频投稿信息”，并没有真正上传文件。

真实流程应该是：

```text
1. 用户选择封面图片和视频文件
2. 前端调用上传接口，把文件传给后端
3. 后端把文件保存到本地 / OSS / COS
4. 后端返回文件访问地址
5. 前端拿到 coverUrl 和 videoUrl
6. 前端再调用投稿接口，把标题、简介、分区、coverUrl、videoUrl 一起提交
7. 后端保存 video 表，并初始化 video_status 表
```

所以视频模块至少需要两个阶段：

```text
文件上传
  ↓
投稿入库
```

---

## 推荐先做两个上传接口

建议先做：

```http
POST /api/files/images
POST /api/files/videos
```

或者如果你想按业务命名，也可以做：

```http
POST /api/videos/cover/upload
POST /api/videos/file/upload
```

我更推荐第一种：

```http
POST /api/files/images
POST /api/files/videos
```

原因是文件上传不一定只给视频模块用，以后头像、背景图、举报截图也可以复用。

---

## 上传封面接口

```http
POST /api/files/images
Content-Type: multipart/form-data
```

请求参数：

```text
file: 图片文件
```

返回：

```json
{
  "code": 200,
  "message": "上传成功",
  "data": "https://xxx.com/cover/xxx.jpg"
}
```

返回的字符串就是后面投稿接口要用的：

```java
coverUrl
```

---

## 上传视频接口

```http
POST /api/files/videos
Content-Type: multipart/form-data
```

请求参数：

```text
file: 视频文件
```

返回：

```json
{
  "code": 200,
  "message": "上传成功",
  "data": "https://xxx.com/video/xxx.mp4"
}
```

返回的字符串就是后面投稿接口要用的：

```java
videoUrl
```

---

## 上传接口应该放在哪一层？

推荐结构：

```text
controller
  FileController.java

service
  file
    FileStorageService.java

service/impl
  file
    LocalFileStorageServiceImpl.java
    或 TencentCosFileStorageServiceImpl.java
```

你项目里现在已经有头像上传相关逻辑，所以视频上传最好复用已有的文件存储服务，而不是在 `VideoController` 里直接写文件保存代码。

---

## 上传接口和投稿接口的关系

上传接口只负责：

```text
接收文件
保存文件
返回 URL
```

投稿接口负责：

```text
接收标题、简介、分区、coverUrl、videoUrl
保存 video 表
初始化 video_status 表
```

这两个职责不要混在一起。

---

# 第 4 步：做视频投稿接口

## 目标

用户提交视频信息，后端保存到 `video` 表，并初始化 `video_status`。

---

## 请求 DTO

建议创建：

```text
src/main/java/com/feibijiubi/backend/dto/VideoCreateDTO.java
```

示例：

```java
@Data
public class VideoCreateDTO {
    private String title;
    private Integer sourceType;
    private Integer visibility;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String coverUrl;
    private String videoUrl;
}
```

注意：

这里不要让前端传：

```java
private Integer uid;
private Integer status;
private LocalDateTime createdAt;
```

原因：

- `uid` 应该从登录 token 里取
- `status` 应该由后端设置为审核中
- `createdAt` 应该由数据库或后端自动生成

---

## Controller 接口

建议路径：

```http
POST /api/videos
```

示例：

```java
@PostMapping
public ApiResponse<Integer> createVideo(HttpServletRequest request,
                                        @RequestBody VideoCreateDTO dto) {
    Integer currentUserId = (Integer) request.getAttribute("currentUserId");
    Integer vid = videoService.createVideo(currentUserId, dto);
    return ApiResponse.success("投稿成功，等待审核", vid);
}
```

---

## Service 业务逻辑

投稿时大概做这些事：

```text
1. 校验用户是否登录
2. 校验标题不能为空
3. 校验封面 URL 不能为空
4. 校验视频 URL 不能为空
5. 组装 Video 实体
6. status 设置为 0：审核中
7. 插入 video 表
8. 插入 video_status 默认记录
9. 返回 vid
```

伪代码：

```java
@Transactional
public Integer createVideo(Integer currentUserId, VideoCreateDTO dto) {
    if (currentUserId == null) {
        throw new BusinessException(401, "请先登录");
    }

    Video video = new Video();
    video.setUid(currentUserId);
    video.setTitle(dto.getTitle());
    video.setSourceType(dto.getSourceType());
    video.setVisibility(dto.getVisibility());
    video.setDuration(dto.getDuration());
    video.setMcId(dto.getMcId());
    video.setScId(dto.getScId());
    video.setTags(dto.getTags());
    video.setDescription(dto.getDescription());
    video.setCoverUrl(dto.getCoverUrl());
    video.setVideoUrl(dto.getVideoUrl());
    video.setStatus(0);

    videoMapper.insert(video);
    videoStatusMapper.insertDefault(video.getVid());

    return video.getVid();
}
```

这里建议加 `@Transactional`。

原因是：

```text
video 插入成功，但是 video_status 插入失败
```

这种情况会导致数据不完整。

加了事务后，只要中间一步失败，整个投稿都会回滚。

---

# 第 4 步：做视频详情接口

## 目标

查询一个视频详情时，通常需要三部分数据：

```text
Video        视频基础信息
VideoStatus  视频统计数据
UserVideo    当前登录用户对这个视频的状态
```

所以不要直接返回 `Video` 实体。

应该返回一个 VO。

---

## 响应 VO

建议创建：

```text
src/main/java/com/feibijiubi/backend/vo/VideoDetailVO.java
```

示例：

```java
@Data
public class VideoDetailVO {
    private Integer vid;
    private Integer uid;
    private String title;
    private Integer sourceType;
    private Integer visibility;
    private Double duration;
    private String mcId;
    private String scId;
    private String tags;
    private String description;
    private String coverUrl;
    private String videoUrl;
    private Integer status;
    private LocalDateTime createdAt;

    private Integer playTimes;
    private Integer likeTimes;
    private Integer coinTimes;
    private Integer collectTimes;
    private Integer commentTimes;
    private Integer danmuTimes;
    private Integer shareTimes;

    private Boolean liked;
    private Byte coin;
    private Boolean collected;
}
```

注意这里可以用更适合前端理解的字段名：

```java
private Boolean liked;
private Boolean collected;
```

而不一定完全照搬数据库字段。

---

## Controller 接口

建议路径：

```http
GET /api/videos/{vid}
```

示例：

```java
@GetMapping("/{vid}")
public ApiResponse<VideoDetailVO> getVideoDetail(HttpServletRequest request,
                                                 @PathVariable Integer vid) {
    Integer currentUserId = (Integer) request.getAttribute("currentUserId");
    VideoDetailVO detail = videoService.getVideoDetail(currentUserId, vid);
    return ApiResponse.success("查询成功", detail);
}
```

如果你希望未登录用户也能看视频详情，就不能强制走登录拦截器。可以后面再优化成：

```text
登录用户：返回是否点赞、是否收藏
未登录用户：liked=false, collected=false, coin=0
```

---

## Service 业务逻辑

大概流程：

```text
1. 根据 vid 查询 video
2. 如果视频不存在，抛出异常
3. 如果视频不是已过审状态，普通用户不能看
4. 根据 vid 查询 video_status
5. 如果用户已登录，根据 uid + vid 查询 user_video
6. 组装 VideoDetailVO 返回
```

---

# 第 5 步：做视频列表接口

## 目标

首页、分区页需要查询视频列表。

建议先做最简单版本：

```http
GET /api/videos
```

只查已过审公开视频：

```sql
WHERE status = 1
ORDER BY created_at DESC
```

这正好会用到你建的索引：

```sql
KEY `idx_video_status_created_at` (`status`, `created_at`)
```

---

## 响应 VO

建议创建：

```text
VideoListItemVO
```

字段不要太多：

```java
@Data
public class VideoListItemVO {
    private Integer vid;
    private Integer uid;
    private String title;
    private String coverUrl;
    private Double duration;
    private Integer playTimes;
    private Integer likeTimes;
    private LocalDateTime createdAt;
}
```

列表页不要返回 `videoUrl`。

原因：

> 列表只展示封面和标题，不需要提前把真实视频地址暴露给前端。

---

# 第 6 步：做播放记录接口

## 目标

用户播放视频后：

1. `video_status.play_times + 1`
2. 更新或插入 `user_video` 的观看记录

建议接口：

```http
POST /api/videos/{vid}/play
```

请求体：

```java
@Data
public class VideoPlayDTO {
    private Double playTime;
}
```

含义：

```text
用户当前观看到第几秒 / 或本次观看时长
```

业务逻辑：

```text
1. 检查视频是否存在
2. video_status.play_times + 1
3. 查询 user_video 是否存在
4. 不存在则插入
5. 存在则更新 play_time 和 played_at
```

---

# 第 7 步：做点赞接口

## 目标

用户点赞视频。

建议接口：

```http
POST /api/videos/{vid}/like
DELETE /api/videos/{vid}/like
```

含义：

```text
POST   点赞
DELETE 取消点赞
```

点赞时：

```text
1. 查询 user_video
2. 如果不存在，先创建
3. 如果原来没有点赞：
   - user_video.like = true
   - user_video.liked_at = now
   - video_status.like_times + 1
4. 如果已经点赞，不重复增加统计
```

取消点赞时：

```text
1. 如果原来点赞了：
   - user_video.like = false
   - video_status.like_times - 1
2. 如果原来没点赞，不重复减少统计
```

重点：

> 统计数一定要根据用户状态变化来更新，不能用户狂点接口就一直 +1。

---

# 第 8 步：做投币接口

## 目标

用户给视频投币。

建议接口：

```http
POST /api/videos/{vid}/coin
```

请求体：

```java
@Data
public class VideoCoinDTO {
    private Byte coin;
}
```

业务规则：

```text
1. 单次只能投 1 或 2 个币
2. 一个用户对一个视频最多投 2 个币
3. 用户自己的硬币余额要足够
4. 不能给自己的视频投币，这个规则可以后面再加
```

流程：

```text
1. 查询视频
2. 查询用户硬币余额
3. 查询 user_video 当前已投币数
4. 判断是否超过 2
5. 扣用户硬币
6. 更新 user_video.coin
7. 更新 user_video.coined_at
8. video_status.coin_times 增加对应数量
```

这个接口建议一定加事务：

```java
@Transactional
```

因为它同时修改：

```text
users.coin
user_video.coin
video_status.coin_times
```

---

# 第 9 步：做收藏接口

## 目标

用户收藏或取消收藏视频。

建议接口：

```http
POST /api/videos/{vid}/collect
DELETE /api/videos/{vid}/collect
```

收藏时：

```text
1. user_video.collect = true
2. video_status.collect_times + 1
```

取消收藏时：

```text
1. user_video.collect = false
2. video_status.collect_times - 1
```

同样要注意：

> 已经收藏的情况下重复调用收藏接口，不应该重复 +1。

---

# 第 10 步：再考虑分享和举报

## 分享

简单版：

```text
只做 video_status.share_times + 1
```

接口：

```http
POST /api/videos/{vid}/share
```

如果以后你想记录谁分享了、分享到哪里，再单独建：

```text
video_share
```

---

## 举报

举报不建议放进 `user_video`。

建议单独建：

```text
video_report
```

因为举报需要：

```text
举报原因
举报说明
举报人
被举报视频
处理状态
处理时间
管理员处理结果
```

这不是一个简单的用户视频关系状态。

---

# 四、推荐的代码包结构

建议视频模块按下面这样放：

```text
src/main/java/com/feibijiubi/backend
├── controller
│   └── VideoController.java
├── service
│   └── video
│       └── VideoService.java
├── service
│   └── impl
│       └── video
│           └── VideoServiceImpl.java
├── mapper
│   ├── VideoMapper.java
│   ├── VideoStatusMapper.java
│   └── UserVideoMapper.java
├── entity
│   ├── Video.java
│   ├── VideoStatus.java
│   └── UserVideo.java
├── dto
│   ├── VideoCreateDTO.java
│   ├── VideoPlayDTO.java
│   └── VideoCoinDTO.java
├── vo
│   ├── VideoDetailVO.java
│   └── VideoListItemVO.java
└── converter
    └── VideoConverter.java
```

---

# 五、为什么要有 DTO、VO、Entity？

## Entity

Entity 对应数据库表。

例如：

```java
Video
VideoStatus
UserVideo
```

它们主要用于数据库读写。

---

## DTO

DTO 表示前端请求后端时传来的数据。

例如投稿请求：

```java
VideoCreateDTO
```

前端不应该直接传完整的 `Video`。

因为有些字段不能让前端决定：

```text
uid
status
createdAt
deletedAt
```

这些应该由后端控制。

---

## VO

VO 表示后端返回给前端的数据。

例如：

```java
VideoDetailVO
VideoListItemVO
```

前端需要什么，VO 就返回什么。

不要直接把 Entity 暴露给前端。

---

# 六、视频详情接口为什么要组装三张表？

因为视频详情页需要的数据不是一张表能完全表达的。

例如：

```text
标题、简介、封面、视频地址       来自 video
播放数、点赞数、收藏数           来自 video_status
当前用户是否点赞、是否收藏、投币  来自 user_video
```

所以 Service 层要做数据组装：

```text
Video + VideoStatus + UserVideo -> VideoDetailVO
```

这就是后端业务层的价值。

Controller 不应该写这些组装逻辑。

Mapper 也不应该负责业务判断。

---

# 七、每一层负责什么

## Controller

负责：

```text
接收请求
获取路径参数 / 请求体 / 当前登录用户ID
调用 Service
返回 ApiResponse
```

不负责复杂业务。

---

## Service

负责：

```text
业务规则
事务控制
数据校验
调用多个 Mapper
组装返回结果
```

比如点赞时判断是否重复点赞，就应该在 Service。

---

## Mapper

负责：

```text
执行 SQL
查询数据库
插入数据库
更新数据库
```

不要把业务规则塞进 Mapper。

---

## Entity

负责：

```text
承载数据库表数据
```

---

## DTO / VO

负责：

```text
前后端数据传输
```

---

# 八、最小可行版本 MVP

你现在不要一次做太大。

建议第一阶段只完成这 4 个接口：

```http
POST /api/videos
GET  /api/videos/{vid}
GET  /api/videos
POST /api/videos/{vid}/play
```

也就是：

```text
投稿视频
查询视频详情
查询视频列表
记录播放
```

这 4 个接口完成后，你的视频模块就有基本闭环了。

然后第二阶段再做：

```http
POST   /api/videos/{vid}/like
DELETE /api/videos/{vid}/like
POST   /api/videos/{vid}/coin
POST   /api/videos/{vid}/collect
DELETE /api/videos/{vid}/collect
```

---

# 九、推荐你下一步马上做什么

你现在下一步应该做：

```text
视频投稿接口
```

具体任务：

```text
1. 创建 VideoCreateDTO
2. 创建 VideoMapper
3. 创建 VideoStatusMapper
4. 创建 VideoService
5. 创建 VideoServiceImpl
6. 创建 VideoController
7. 实现 POST /api/videos
8. 投稿成功后同时插入 video 和 video_status
9. 使用 @Transactional 保证事务
10. 用 Postman / Apifox 测试接口
```

---

# 十、投稿接口完成标准

你写完后，可以用下面这个标准检查自己：

## 1. 前端请求

```json
{
  "title": "菲比啾比第一个视频",
  "sourceType": 1,
  "visibility": 0,
  "duration": 120.5,
  "mcId": "knowledge",
  "scId": "programming",
  "tags": "Java,Spring Boot,后端",
  "description": "这是菲比啾比的视频投稿测试",
  "coverUrl": "https://example.com/cover.jpg",
  "videoUrl": "https://example.com/video.mp4"
}
```

---

## 2. 后端自动设置

```text
uid        当前登录用户ID
status     0 审核中
created_at 当前时间 / 数据库默认时间
```

---

## 3. 数据库结果

`video` 表新增一条视频：

```text
vid = 新视频ID
uid = 当前用户ID
status = 0
```

`video_status` 表也新增一条统计记录：

```text
vid = 新视频ID
play_times = 0
like_times = 0
coin_times = 0
collect_times = 0
...
```

---

# 十一、注意事项

## 1. 不要让前端传用户 ID

错误做法：

```java
private Integer uid;
```

放在 `VideoCreateDTO` 里让前端传。

正确做法：

```java
Integer currentUserId = (Integer) request.getAttribute("currentUserId");
```

从登录拦截器里拿当前用户 ID。

---

## 2. 不要直接返回 Entity

不推荐：

```java
public ApiResponse<Video> getDetail(...)
```

推荐：

```java
public ApiResponse<VideoDetailVO> getDetail(...)
```

---

## 3. 涉及多张表修改时加事务

例如投稿：

```text
video
video_status
```

例如投币：

```text
users
user_video
video_status
```

都建议加：

```java
@Transactional
```

---

## 4. 统计数不能无脑加减

点赞、收藏这种接口要先判断用户原来的状态。

例如：

```text
用户已经点赞了，再点一次点赞接口，不应该 like_times + 1
```

否则数据会被刷坏。

---

## 5. 先做简单，再做复杂

不要一开始就追求：

```text
分页
搜索
推荐算法
审核后台
对象存储上传
视频转码
消息通知
```

先把基本 CRUD 和用户行为接口做通。

---

# 十二、最终路线图

```text
第一阶段：视频基础闭环
1. 投稿视频
2. 初始化视频统计
3. 查询视频详情
4. 查询视频列表
5. 记录播放

第二阶段：用户互动
6. 点赞 / 取消点赞
7. 投币
8. 收藏 / 取消收藏
9. 分享统计

第三阶段：内容扩展
10. 评论
11. 弹幕
12. 举报
13. 审核

第四阶段：工程优化
14. 分页查询
15. 参数校验
16. 统一异常处理
17. 接口文档
18. 单元测试
19. 文件上传
20. 视频存储和转码
```

---

# 总结

你现在最应该做的不是继续加表，而是开始把三张表用起来。

推荐从：

```text
POST /api/videos 投稿接口
```

开始。

它是视频模块的起点，因为它会同时用到：

```text
video
video_status
```

等投稿完成后，再做：

```text
GET /api/videos/{vid} 视频详情接口
```

这个接口会把：

```text
video + video_status + user_video
```

组合起来返回给前端。

这两个接口做完，你就真正理解这三张表为什么要拆开了。