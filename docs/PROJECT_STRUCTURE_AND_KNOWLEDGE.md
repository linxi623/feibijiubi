# Teriteri 后端项目结构与知识点梳理

> 本文档用于快速理解 `teriteri-backend` 项目的整体结构、技术栈、核心业务模块、运行依赖、学习知识点，以及一个更推荐的长期维护目录结构。  
> 事实来源主要包括：`README.md`、`pom.xml`、`src/main/resources/application`、`src/main/java/com/teriteri/backend/**`、`database/teriteri.sql`、`elasticsearch.md`。

## 1. 项目概览

Teriteri 是一个使用 Spring Boot + Vue 开发的仿 B 站弹幕视频网站项目。本仓库是后端服务，前端客户端和管理员端在独立仓库中。

后端主要负责：

- 用户注册、登录、退出、管理员登录。
- 用户个人资料、头像、个人空间。
- 视频投稿、分片上传、封面上传、视频审核。
- 视频浏览、播放统计、点赞、收藏、评论、弹幕。
- 视频和用户搜索、热搜词维护。
- 私信聊天、未读消息、在线状态。
- MySQL 主数据、Redis 缓存/索引、Elasticsearch 搜索、Aliyun OSS 文件存储之间的数据同步。

项目是一个单体 Spring Boot 应用，但内部同时启动了两个实时通信服务：

- HTTP REST API：默认端口 `7070`。
- Netty 私信 WebSocket：默认端口 `7071`，路径 `/im`。
- Javax WebSocket 弹幕服务：路径 `/ws/danmu/{vid}`，跟随 Spring Boot 容器运行。

## 2. 技术栈总览

| 分类 | 技术 | 项目中的作用 |
|---|---|---|
| 语言与构建 | Java 8、Maven | 后端开发语言和依赖构建工具 |
| Web 框架 | Spring Boot 2.7.15、Spring MVC | REST API、配置管理、组件装配 |
| 安全认证 | Spring Security、JJWT、BCrypt | 登录认证、JWT 签发/校验、接口鉴权 |
| ORM | MyBatis-Plus 3.5.x | Mapper、CRUD、条件查询、分页思路 |
| 数据库 | MySQL、Druid | 业务主数据存储与连接池 |
| 缓存/索引 | Redis、Jedis、RedisTemplate | 登录态、缓存、集合、ZSet、热搜、状态集合 |
| 搜索 | Elasticsearch Java Client 7.17.16 | 视频、用户、搜索词索引和查询 |
| 文件存储 | Aliyun OSS | 视频、封面等对象存储 |
| 实时通信 | Netty、Spring WebSocket/Javax WebSocket | 私信 IM、弹幕广播 |
| 异步任务 | CompletableFuture、ThreadPoolTaskExecutor | 视频合并、缓存更新、并行查询 |
| 定时任务 | `@Scheduled` | 热搜归一化、分片清理、Redis 数据修复 |
| JSON | Fastjson2、Jackson | 请求/响应、Redis 序列化、WebSocket 消息 |
| 其他 | Lombok、Commons IO/Lang/FileUpload | 简化实体、文件处理、工具类 |
| 预留能力 | RabbitMQ | 依赖和配置类存在，但当前投稿主流程主要使用线程池异步处理，RabbitMQ 配置被注释 |

关键版本可在 `pom.xml` 中确认：

- Spring Boot：`2.7.15`
- Java：`1.8`
- Elasticsearch：`7.17.16`
- Netty：`4.1.66.Final`
- Aliyun OSS：`3.17.1`

## 3. 当前完整目录结构说明

以下目录树尽量覆盖当前项目的主要业务文件，并对每个目录/文件给出简短职责说明。

```text
.
├── .gitignore                                      # Git 忽略规则
├── .mvn/wrapper/                                  # Maven Wrapper 相关文件
│   ├── maven-wrapper.jar
│   └── maven-wrapper.properties
├── LICENSE                                        # 开源许可证
├── README.md                                      # 项目介绍、部署说明、前后端仓库链接
├── README.assets/                                 # README 展示图片资源
├── database/
│   └── teriteri.sql                               # MySQL 表结构和 category 初始数据
├── elasticsearch.md                               # Elasticsearch 7.17.16 安装与配置笔记
├── mvnw                                           # Linux/macOS Maven Wrapper 启动脚本
├── mvnw.cmd                                       # Windows Maven Wrapper 启动脚本
├── pom.xml                                        # Maven 依赖、Java/Spring Boot/ES 版本配置
└── src/
    ├── main/
    │   ├── java/com/teriteri/backend/
    │   │   ├── BackendApplication.java            # Spring Boot 启动类；启用定时任务；启动 Netty IM 服务
    │   │   ├── component/                         # Spring 组件和 WebSocket 端点
    │   │   │   ├── StartupRunner.java             # 启动时创建/检查分片目录
    │   │   │   └── danmu/
    │   │   │       └── DanmuWebSocketServer.java  # 弹幕 WebSocket：/ws/danmu/{vid}
    │   │   ├── config/                            # 基础设施与 Spring 配置
    │   │   │   ├── CorsConfig.java                # 跨域配置
    │   │   │   ├── DruidConfig.java               # Druid 数据源属性绑定/配置
    │   │   │   ├── ElasticSearchConfig.java       # Elasticsearch 客户端配置
    │   │   │   ├── FileUploadConfig.java          # 文件上传配置
    │   │   │   ├── OSSConfig.java                 # Aliyun OSS 配置属性
    │   │   │   ├── RabbitMQConfig.java            # RabbitMQ 交换机/队列预留配置，目前主体注释
    │   │   │   ├── RedisConfig.java               # RedisTemplate/Jedis 连接与序列化配置
    │   │   │   ├── SecurityConfig.java            # Spring Security、白名单、JWT 过滤器配置
    │   │   │   ├── ThreadPoolConfig.java          # taskExecutor 线程池配置
    │   │   │   ├── WebSocketConfig.java           # WebSocket ServerEndpointExporter 配置
    │   │   │   └── filter/
    │   │   │       └── JwtAuthenticationTokenFilter.java # JWT 请求过滤器
    │   │   ├── controller/                        # REST API 控制器层
    │   │   │   ├── CategoryController.java        # 分区/分类接口
    │   │   │   ├── ChatController.java            # 最近聊天、会话接口
    │   │   │   ├── ChatDetailedController.java    # 聊天详情/消息记录接口
    │   │   │   ├── CommentController.java         # 评论查询、发布、回复接口
    │   │   │   ├── DanmuController.java           # 弹幕列表查询接口
    │   │   │   ├── FavoriteController.java        # 收藏夹接口
    │   │   │   ├── FavoriteVideoController.java   # 收藏夹-视频关系接口
    │   │   │   ├── MsgUnreadController.java       # 未读消息接口
    │   │   │   ├── SearchController.java          # 视频/用户/热搜搜索接口
    │   │   │   ├── UserAccountController.java     # 注册、登录、退出、管理员登录接口
    │   │   │   ├── UserCommentController.java     # 用户对评论的点赞/点踩接口
    │   │   │   ├── UserController.java            # 用户资料、头像、个人信息接口
    │   │   │   ├── UserVideoController.java       # 用户与视频行为关系接口
    │   │   │   ├── VideoController.java           # 视频列表、详情、播放等接口
    │   │   │   ├── VideoReviewController.java     # 管理端视频审核接口
    │   │   │   ├── VideoStatsController.java      # 视频统计数据接口
    │   │   │   └── VideoUploadController.java     # 视频投稿、分片上传接口
    │   │   ├── im/                                # Netty 私信 IM 服务
    │   │   │   ├── IMServer.java                  # Netty 服务启动，绑定 7071 和 /im
    │   │   │   └── handler/
    │   │   │       ├── ChatHandler.java           # 私信发送、撤回等业务处理
    │   │   │       ├── TokenValidationHandler.java # IM 连接 token 校验
    │   │   │       └── WebSocketHandler.java      # WebSocket 帧处理、在线状态维护
    │   │   ├── mapper/                            # MyBatis-Plus 数据访问层
    │   │   │   ├── CategoryMapper.java            # category 表 Mapper
    │   │   │   ├── ChatDetailedMapper.java        # chat_detailed 表 Mapper
    │   │   │   ├── ChatMapper.java                # chat 表 Mapper
    │   │   │   ├── CommentMapper.java             # comment 表 Mapper
    │   │   │   ├── DanmuMapper.java               # danmu 表 Mapper
    │   │   │   ├── FavoriteMapper.java            # favorite 表 Mapper
    │   │   │   ├── FavoriteVideoMapper.java       # favorite_video 表 Mapper
    │   │   │   ├── MsgUnreadMapper.java           # msg_unread 表 Mapper
    │   │   │   ├── UserMapper.java                # user 表 Mapper
    │   │   │   ├── UserVideoMapper.java           # user_video 表 Mapper
    │   │   │   ├── VideoMapper.java               # video 表 Mapper
    │   │   │   └── VideoStatsMapper.java          # video_stats 表 Mapper
    │   │   ├── pojo/                              # 实体、DTO、响应对象、ES 文档、IM 命令
    │   │   │   ├── Category.java                  # 分区实体
    │   │   │   ├── Chat.java                      # 会话实体
    │   │   │   ├── ChatDetailed.java              # 聊天消息实体
    │   │   │   ├── Command.java                   # IM 命令消息体
    │   │   │   ├── CommandType.java               # IM 命令类型枚举/常量
    │   │   │   ├── Comment.java                   # 评论实体
    │   │   │   ├── CommentTree.java               # 评论树节点对象
    │   │   │   ├── CustomResponse.java            # 统一响应对象
    │   │   │   ├── Danmu.java                     # 弹幕实体
    │   │   │   ├── ESSearchWord.java              # ES 搜索词文档
    │   │   │   ├── ESUser.java                    # ES 用户文档
    │   │   │   ├── ESVideo.java                   # ES 视频文档
    │   │   │   ├── Favorite.java                  # 收藏夹实体
    │   │   │   ├── FavoriteVideo.java             # 收藏夹-视频关系实体
    │   │   │   ├── HotSearch.java                 # 热搜词展示对象
    │   │   │   ├── IMResponse.java                # IM 响应对象
    │   │   │   ├── MsgUnread.java                 # 未读消息实体
    │   │   │   ├── User.java                      # 用户实体
    │   │   │   ├── UserVideo.java                 # 用户-视频行为关系实体
    │   │   │   ├── Video.java                     # 视频实体
    │   │   │   ├── VideoStats.java                # 视频统计实体
    │   │   │   └── dto/
    │   │   │       ├── CategoryDTO.java           # 分区 DTO
    │   │   │       ├── UserDTO.java               # 用户 DTO
    │   │   │       └── VideoUploadInfoDTO.java    # 视频投稿信息 DTO
    │   │   ├── service/                           # 服务接口与部分辅助服务
    │   │   │   ├── category/
    │   │   │   │   └── CategoryService.java       # 分区服务接口
    │   │   │   ├── comment/
    │   │   │   │   ├── CommentService.java        # 评论服务接口
    │   │   │   │   └── UserCommentService.java    # 用户评论互动服务接口
    │   │   │   ├── danmu/
    │   │   │   │   └── DanmuService.java          # 弹幕服务接口
    │   │   │   ├── impl/                          # 服务实现层
    │   │   │   │   ├── category/
    │   │   │   │   │   └── CategoryServiceImpl.java
    │   │   │   │   ├── comment/
    │   │   │   │   │   ├── CommentServiceImpl.java
    │   │   │   │   │   └── UserCommentServiceImpl.java
    │   │   │   │   ├── danmu/
    │   │   │   │   │   └── DanmuServiceImpl.java
    │   │   │   │   ├── message/
    │   │   │   │   │   ├── ChatDetailedServiceImpl.java
    │   │   │   │   │   ├── ChatServiceImpl.java
    │   │   │   │   │   └── MsgUnreadServiceImpl.java
    │   │   │   │   ├── search/
    │   │   │   │   │   └── SearchServiceImpl.java
    │   │   │   │   ├── user/
    │   │   │   │   │   ├── UserAccountServiceImpl.java
    │   │   │   │   │   ├── UserDetailsImpl.java
    │   │   │   │   │   ├── UserDetailsServiceImpl.java
    │   │   │   │   │   └── UserServiceImpl.java
    │   │   │   │   └── video/
    │   │   │   │       ├── DirectVideoUploadConsumer.java # RabbitMQ 投稿消费旧方案/保留代码
    │   │   │   │       ├── FavoriteServiceImpl.java
    │   │   │   │       ├── FavoriteVideoServiceImpl.java
    │   │   │   │       ├── UserVideoServiceImpl.java
    │   │   │   │       ├── VideoReviewServiceImpl.java
    │   │   │   │       ├── VideoServiceImpl.java
    │   │   │   │       ├── VideoStatsServiceImpl.java
    │   │   │   │       └── VideoUploadServiceImpl.java
    │   │   │   ├── message/
    │   │   │   │   ├── ChatDetailedService.java
    │   │   │   │   ├── ChatService.java
    │   │   │   │   └── MsgUnreadService.java
    │   │   │   ├── search/
    │   │   │   │   └── SearchService.java
    │   │   │   ├── user/
    │   │   │   │   ├── UserAccountService.java
    │   │   │   │   └── UserService.java
    │   │   │   ├── utils/
    │   │   │   │   ├── CurrentUser.java           # 当前登录用户辅助类
    │   │   │   │   └── EventListenerService.java  # 定时任务：热搜、分片清理、Redis 同步
    │   │   │   └── video/
    │   │   │       ├── FavoriteService.java
    │   │   │       ├── FavoriteVideoService.java
    │   │   │       ├── UserVideoService.java
    │   │   │       ├── VideoReviewService.java
    │   │   │       ├── VideoService.java
    │   │   │       ├── VideoStatsService.java
    │   │   │       └── VideoUploadService.java
    │   │   └── utils/                             # 基础设施工具封装
    │   │       ├── ESUtil.java                    # ES 索引、搜索、文档增删改查工具
    │   │       ├── JwtUtil.java                   # JWT 生成、解析、Redis token 校验
    │   │       ├── OssUtil.java                   # OSS 图片/视频上传、追加上传、删除
    │   │       └── RedisUtil.java                 # Redis 值、对象、Set、ZSet、Hash 等操作封装
    │   └── resources/
    │       ├── application                        # YAML 风格运行配置模板；README 建议改为 application.yml
    │       ├── application.properties             # 当前基本为空/占位，不建议写正式配置
    │       └── static/esindex/
    │           ├── search_word.json               # ES 搜索词索引 mapping
    │           ├── user.json                      # ES 用户索引 mapping
    │           └── video.json                     # ES 视频索引 mapping
    └── test/java/com/teriteri/backend/
        ├── BackendApplicationTests.java           # 集成/运维辅助测试：ES 建索引、OSS/Redis/MySQL 操作等
        └── comment/
            └── TestComment.java                   # 评论相关测试
```

## 4. 更推荐的项目结构

当前项目按传统分层组织：`controller`、`service`、`mapper`、`pojo`、`config`、`utils`。这种结构适合入门理解，但随着业务增多，容易出现几个问题：

- `pojo` 同时混放数据库实体、DTO、ES 文档、IM 消息对象，边界不清晰。
- `utils` 中封装了 Redis、ES、OSS、JWT，既有基础设施能力，也有安全能力。
- WebSocket 弹幕和 Netty IM 分散在 `component`、`im`、`config`、`service/message` 中。
- 测试类中包含较多运维/初始化脚本，运行 `mvn test` 时容易误触发外部副作用。
- 配置文件使用 `application` 无后缀，不利于标准 Spring Boot 识别和团队协作。

更推荐的长期维护结构如下：

```text
.
├── docs/                                      # 项目文档集中目录
│   ├── PROJECT_STRUCTURE_AND_KNOWLEDGE.md     # 项目结构与知识点
│   ├── deployment.md                          # 部署说明
│   ├── elasticsearch.md                       # ES 安装、mapping、同步说明
│   ├── api.md                                 # 接口说明或 OpenAPI 导出
│   └── operations.md                          # 运维脚本、索引重建、数据修复说明
├── database/
│   └── teriteri.sql
├── src/main/java/com/teriteri/backend/
│   ├── BackendApplication.java
│   ├── common/                                # 通用能力
│   │   ├── response/                          # 统一响应，如 CustomResponse
│   │   ├── exception/                         # 全局异常与业务异常
│   │   ├── constant/                          # 常量
│   │   └── enums/                             # 枚举，如 CommandType
│   ├── security/                              # 认证授权边界
│   │   ├── config/                            # SecurityConfig
│   │   ├── filter/                            # JwtAuthenticationTokenFilter
│   │   ├── jwt/                               # JwtUtil
│   │   └── context/                           # CurrentUser
│   ├── infrastructure/                        # 外部基础设施适配层
│   │   ├── mysql/                             # Druid/MyBatis 配置
│   │   ├── redis/                             # RedisConfig、RedisUtil
│   │   ├── elasticsearch/                     # ElasticSearchConfig、ESUtil、document
│   │   └── oss/                               # OSSConfig、OssUtil
│   ├── scheduler/                             # 定时任务集中管理
│   │   ├── HotSearchScheduler.java
│   │   ├── ChunkCleanupScheduler.java
│   │   └── RedisRepairScheduler.java
│   ├── realtime/                              # 实时通信统一入口
│   │   ├── danmu/                             # 弹幕 WebSocket
│   │   └── im/                                # Netty IM 服务和 Handler
│   ├── module/                                # 按业务领域聚合
│   │   ├── user/
│   │   │   ├── controller/
│   │   │   ├── service/
│   │   │   ├── mapper/
│   │   │   ├── entity/
│   │   │   ├── dto/
│   │   │   └── vo/
│   │   ├── video/
│   │   ├── comment/
│   │   ├── danmu/
│   │   ├── favorite/
│   │   ├── search/
│   │   └── message/
│   └── upload/                                # 如果投稿复杂，可单独聚合上传/分片/OSS 编排
├── src/main/resources/
│   ├── application.yml.example                # 可提交的示例配置，不放真实密钥
│   ├── application-dev.yml                    # 本地开发配置，建议加入 .gitignore
│   └── static/esindex/
└── src/test/java/com/teriteri/backend/
    ├── unit/                                  # 纯单元测试，不依赖外部服务
    ├── integration/                           # 集成测试，显式启用 profile
    └── operations/                            # ES 建索引、批量同步等运维辅助，避免默认 mvn test 执行
```

推荐迁移优先级：

1. **先做低风险整理**：新增 `docs/`、把说明文档集中；增加 `application.yml.example`，真实配置使用本地文件或环境变量。
2. **再做边界清晰化**：把 `CustomResponse`、异常、常量、枚举放入 `common`；把 JWT/Security/CurrentUser 聚合到 `security`。
3. **再抽基础设施层**：Redis、ES、OSS、Druid 配置和工具类放到 `infrastructure`，减少 `utils` 变成万能包。
4. **最后考虑领域重组**：如果项目继续扩展，再按 `module/user`、`module/video`、`module/message` 等业务域聚合 controller/service/mapper/entity/dto/vo。
5. **测试拆分**：把会创建 ES 索引、访问 OSS、清 Redis 的辅助脚本移出默认测试路径，避免运行测试时产生外部副作用。

## 5. 启动流程与运行配置

### 5.1 启动流程

`BackendApplication` 是启动入口：

1. `SpringApplication.run(BackendApplication.class, args)` 启动 Spring Boot 应用。
2. `@EnableScheduling` 开启定时任务。
3. Spring 启动完成后，新开线程执行 `new IMServer().start()`，启动 Netty 私信服务。
4. `StartupRunner` 在应用启动阶段创建配置的分片目录。

### 5.2 端口与路径

| 服务 | 默认端口/路径 | 说明 |
|---|---:|---|
| Spring Boot HTTP API | `7070` | `src/main/resources/application` 中 `server.port` |
| Netty IM WebSocket | `7071` + `/im` | `IMServer` 中硬编码绑定端口和路径 |
| Danmu WebSocket | `/ws/danmu/{vid}` | `DanmuWebSocketServer` 中 `@ServerEndpoint` |

### 5.3 配置文件说明

- 当前仓库中存在 `src/main/resources/application`，内容是 YAML 风格配置。
- `README.md` 提示应将该文件加上 `.yml` 后缀作为 `application.yml` 使用。
- `application.properties` 不建议写正式配置。
- 配置中包含 MySQL、Redis、OSS、Elasticsearch 等敏感项，实际部署时应自行填写，并避免提交真实密钥。

## 6. 分层架构说明

当前项目整体调用链如下：

```text
HTTP 请求
  ↓
controller 控制器
  ↓
service 服务接口
  ↓
service/impl 服务实现
  ↓
mapper / RedisUtil / ESUtil / OssUtil / JwtUtil / WebSocket / Netty
  ↓
MySQL / Redis / Elasticsearch / OSS / WebSocket 客户端
```

各层职责：

- `controller`：接收请求、绑定参数、调用服务、返回 `CustomResponse`。
- `service`：定义业务接口。
- `service/impl`：实现业务流程，是项目中最核心的层，通常会同时操作 MySQL、Redis、ES、OSS。
- `mapper`：基于 MyBatis-Plus `BaseMapper` 访问数据库。
- `pojo`：当前混合存放实体、DTO、ES 文档、响应对象、IM 对象。
- `config`：定义 Spring Security、Redis、ES、OSS、Druid、线程池、WebSocket 等配置。
- `utils`：封装 JWT、Redis、ES、OSS 操作。
- `component`：启动辅助组件和弹幕 WebSocket。
- `im`：独立 Netty 私信服务。

## 7. 核心业务模块

| 模块 | 主要控制器 | 主要服务/组件 | 主要依赖 | 职责 |
|---|---|---|---|---|
| 用户账号 | `UserAccountController` | `UserAccountServiceImpl`、`UserDetailsServiceImpl` | `UserMapper`、`JwtUtil`、`RedisUtil`、Spring Security | 注册、登录、登出、管理员登录、JWT 签发 |
| 用户资料 | `UserController` | `UserServiceImpl` | `UserMapper`、`OssUtil`、`ESUtil`、`RedisUtil` | 个人资料、头像、用户缓存/ES 更新 |
| 分类 | `CategoryController` | `CategoryServiceImpl` | `CategoryMapper`、Redis | 视频分区/分类数据 |
| 视频浏览 | `VideoController` | `VideoServiceImpl` | `VideoMapper`、`RedisUtil`、`ESUtil` | 推荐、详情、用户作品、播放记录相关 |
| 视频统计 | `VideoStatsController` | `VideoStatsServiceImpl` | `VideoStatsMapper`、Redis | 播放、点赞、收藏、评论、弹幕等统计 |
| 视频投稿 | `VideoUploadController` | `VideoUploadServiceImpl` | `OssUtil`、`VideoMapper`、`VideoStatsMapper`、`ESUtil`、`RedisUtil`、线程池 | 分片上传、封面上传、合并分片、写库、缓存和索引更新 |
| 视频审核 | `VideoReviewController` | `VideoReviewServiceImpl` | `VideoMapper`、`RedisUtil`、`ESUtil` | 待审核列表、审核状态流转、Redis 状态集合维护 |
| 搜索 | `SearchController` | `SearchServiceImpl` | `ESUtil`、`RedisUtil` | 视频搜索、用户搜索、搜索词、热搜 |
| 评论 | `CommentController`、`UserCommentController` | `CommentServiceImpl`、`UserCommentServiceImpl` | `CommentMapper`、Redis、线程池 | 评论树、回复、点赞/点踩 |
| 收藏 | `FavoriteController`、`FavoriteVideoController` | `FavoriteServiceImpl`、`FavoriteVideoServiceImpl` | `FavoriteMapper`、`FavoriteVideoMapper`、Redis | 收藏夹、收藏视频关系 |
| 弹幕 | `DanmuController`、`DanmuWebSocketServer` | `DanmuServiceImpl`、`VideoStatsService` | `DanmuMapper`、`RedisUtil`、`JwtUtil` | 弹幕列表、实时写入、统计更新、广播 |
| 私信 | `ChatController`、`ChatDetailedController`、`MsgUnreadController`、`IMServer` | `ChatServiceImpl`、`ChatDetailedServiceImpl`、`MsgUnreadServiceImpl`、Netty Handler | Redis、Mapper、Netty Channel | 私信会话、消息记录、未读数、在线状态 |

## 8. 数据库与实体关系

数据库脚本位于 `database/teriteri.sql`，当前主要表包括：

| 表 | 对应实体/对象 | 说明 |
|---|---|---|
| `category` | `Category` | 视频主分区/子分区 |
| `chat` | `Chat` | 用户会话列表 |
| `chat_detailed` | `ChatDetailed` | 私信消息明细 |
| `comment` | `Comment`、`CommentTree` | 视频评论和回复 |
| `danmu` | `Danmu` | 弹幕数据 |
| `favorite` | `Favorite` | 收藏夹 |
| `favorite_video` | `FavoriteVideo` | 收藏夹与视频的关系 |
| `msg_unread` | `MsgUnread` | 未读消息计数 |
| `user` | `User`、`UserDTO`、`ESUser` | 用户账号与资料 |
| `user_video` | `UserVideo` | 用户与视频行为关系，例如点赞/播放/收藏等 |
| `video` | `Video`、`VideoUploadInfoDTO`、`ESVideo` | 视频主表 |
| `video_stats` | `VideoStats` | 视频统计表 |

简化关系：

```text
user 1---N video
video 1---1 video_stats
category 1---N video
video 1---N comment
video 1---N danmu
user 1---N favorite
favorite N---N video，通过 favorite_video 关联
user N---N user，通过 chat/chat_detailed/msg_unread 实现私信
user N---N video，通过 user_video 记录用户视频行为
```

## 9. 专题知识点

### 9.1 Spring Boot 与 Spring MVC

项目中大量控制器使用 Spring MVC 提供 REST API，适合学习：

- `@SpringBootApplication`
- `@RestController`
- `@GetMapping`、`@PostMapping`
- `@RequestParam`、`@PathVariable`、`@RequestBody`
- Multipart 文件上传
- 统一响应对象 `CustomResponse`

代表路径：`src/main/java/com/teriteri/backend/controller/`

### 9.2 MyBatis-Plus

Mapper 层基本继承 MyBatis-Plus 的 `BaseMapper<T>`，服务实现中常用：

- `QueryWrapper`
- `UpdateWrapper`
- `selectById`、`selectList`、`insert`、`updateById`
- 实体类和表字段映射
- 多表关系在业务层手动组合

代表路径：

- `src/main/java/com/teriteri/backend/mapper/`
- `src/main/java/com/teriteri/backend/pojo/`
- `database/teriteri.sql`

### 9.3 Spring Security + JWT

认证链路核心文件：

- `SecurityConfig.java`
- `JwtAuthenticationTokenFilter.java`
- `JwtUtil.java`
- `UserDetailsServiceImpl.java`
- `UserDetailsImpl.java`
- `CurrentUser.java`

流程概括：

1. 登录接口校验用户名密码。
2. 使用 BCrypt 匹配密码。
3. 生成 JWT，并在 Redis 中保存 token。
4. 请求受保护接口时，`JwtAuthenticationTokenFilter` 读取 `Authorization: Bearer <token>`。
5. 校验 JWT，并从 Redis 读取 `security:{role}:{uid}` 用户对象。
6. 认证成功后写入 `SecurityContextHolder`。
7. 业务代码通过 `CurrentUser` 获取当前用户 ID 或权限信息。

注意点：JWT 在这里不是完全无状态的，项目会通过 Redis 中的 token/session 状态进一步校验登录有效性。

### 9.4 Redis

Redis 在项目中不是单纯缓存，而是承担了很多派生索引和状态维护职责：

- `token:{role}:{uid}`：登录 token。
- `security:{role}:{uid}`：登录用户对象。
- `login_member`：在线用户集合。
- `video:{vid}`：视频缓存。
- `videoStats:{vid}`：视频统计缓存。
- `video_status:{status}`：按审核状态维护视频 ID 集合。
- `danmu_idset:{vid}`：视频对应弹幕 ID 集合。
- `search_word`：搜索词热度 ZSet。
- `chat_detailed_zset:{uid}:{anotherId}`：聊天记录有序集合。

代表文件：

- `RedisConfig.java`
- `RedisUtil.java`
- `EventListenerService.java`

### 9.5 Elasticsearch

项目使用 Elasticsearch 7.17.16 做搜索，主要索引：

- `video`：视频搜索。
- `user`：用户搜索。
- `search_word`：搜索词、联想/热搜相关。

相关文件：

- `ElasticSearchConfig.java`
- `ESUtil.java`
- `SearchServiceImpl.java`
- `src/main/resources/static/esindex/video.json`
- `src/main/resources/static/esindex/user.json`
- `src/main/resources/static/esindex/search_word.json`
- `elasticsearch.md`

测试类 `BackendApplicationTests` 中还包含创建索引、删除索引、批量同步数据等辅助方法。

### 9.6 Aliyun OSS 与分片上传

视频投稿流程大致为：

1. 前端询问某个视频 hash 的下一个分片序号。
2. 分片上传到本地 `directory.chunk` 配置目录。
3. 投稿提交时上传封面到 OSS。
4. 使用 `CompletableFuture` 走异步线程池执行 `mergeChunks`。
5. 通过 `OssUtil.appendUploadVideo` 将分片追加上传到 OSS。
6. 写入 `video` 和 `video_stats`。
7. 同步 ES 视频文档。
8. 异步更新 Redis 视频缓存、统计缓存、审核状态集合。

代表文件：

- `VideoUploadController.java`
- `VideoUploadServiceImpl.java`
- `OssUtil.java`
- `FileUploadConfig.java`
- `OSSConfig.java`
- `StartupRunner.java`

### 9.7 WebSocket 弹幕

弹幕使用 Javax WebSocket：

- 端点：`@ServerEndpoint("/ws/danmu/{vid}")`
- 连接时按视频 ID 维护 session 集合。
- 收到消息后手动校验 Bearer JWT。
- 从 Redis 获取登录用户。
- 写入 `danmu` 表。
- 更新视频统计。
- 写入 Redis 的 `danmu_idset:{vid}`。
- 广播给同一个视频下的所有连接。

代表文件：`src/main/java/com/teriteri/backend/component/danmu/DanmuWebSocketServer.java`

### 9.8 Netty 私信 IM

私信聊天使用独立 Netty 服务：

- `IMServer` 绑定端口 `7071`。
- WebSocket 路径 `/im`。
- Pipeline 包含：
  - `HttpServerCodec`
  - `ChunkedWriteHandler`
  - `HttpObjectAggregator`
  - `TokenValidationHandler`
  - `WebSocketServerProtocolHandler("/im")`
  - `WebSocketHandler`
- `IMServer.userChannel` 维护用户到 Channel 集合的映射。
- `TokenValidationHandler` 校验 token。
- `WebSocketHandler` 维护连接生命周期。
- `ChatHandler` 处理具体聊天命令。

代表文件：`src/main/java/com/teriteri/backend/im/`

### 9.9 定时任务与异步线程池

定时任务集中在 `EventListenerService`：

| 方法 | 触发方式 | 作用 |
|---|---|---|
| `updateHotSearch` | 每小时 | 归一化 Redis `search_word` 热度分数 |
| `deleteChunks` | 每天 4:00 | 删除三天前未使用的分片文件 |
| `updateVideoStatus` | 每 24 小时 | 从 MySQL 重建 Redis `video_status:*` 集合 |
| `updateChatDetailedZSet` | 每天 4:15 | 从 MySQL 重建聊天记录 ZSet |

线程池配置在 `ThreadPoolConfig`：

- Bean 名称：`taskExecutor`
- 核心线程数：20
- 最大线程数：100
- 队列容量：`Integer.MAX_VALUE`
- 线程名前缀：`teriteri`

### 9.10 RabbitMQ 当前状态

项目引入了 RabbitMQ 依赖，也存在 `RabbitMQConfig` 和 `DirectVideoUploadConsumer`，但需要谨慎理解：

- `RabbitMQConfig` 中交换机、队列、绑定 Bean 当前被注释。
- `src/main/resources/application` 中 RabbitMQ 配置也被注释。
- `VideoUploadServiceImpl` 中投稿发送 RabbitMQ 的逻辑被注释。
- 当前投稿主流程使用 `CompletableFuture.runAsync(..., taskExecutor)` 进行异步处理。

因此文档中应表述为：RabbitMQ 是预留/历史方案，当前主要业务流程未启用。

## 10. 数据一致性模式

这个项目的核心工程难点之一是多存储系统一致性：

- MySQL：主数据源。
- Redis：缓存、集合、ZSet、登录态、派生索引。
- Elasticsearch：搜索索引。
- OSS：视频/图片对象存储。

典型写入链路：

### 视频投稿

```text
上传分片 → 上传封面 OSS → 异步合并视频到 OSS → 写 video/video_stats → 写 ES → 写 Redis
```

### 视频审核

```text
更新 MySQL 状态 → 更新 ES 视频文档 → 移动 Redis video_status 集合 → 刷新/删除缓存
```

### 弹幕发送

```text
WebSocket 收消息 → 校验 JWT/Redis 登录态 → 写 danmu → 更新 video_stats → 写 Redis danmu_idset → 广播
```

### 搜索词

```text
用户搜索 → Redis search_word ZSet 记录热度 → ES search_word 索引支持搜索建议 → 定时任务归一化热度
```

项目中通过以下方式缓解不一致：

- 部分数据库写操作使用 `@Transactional`。
- 写库后同步更新 Redis/ES。
- 使用 `CompletableFuture` 异步更新缓存或执行耗时操作。
- 定时任务重建 Redis 状态集合。
- 测试类中提供 ES 索引重建/批量同步辅助方法。

## 11. 测试与初始化辅助

测试目录：

```text
src/test/java/com/teriteri/backend/
├── BackendApplicationTests.java
└── comment/TestComment.java
```

需要注意：

- 这些测试更像集成测试/运维辅助脚本，不是隔离的纯单元测试。
- `BackendApplicationTests` 会依赖 MySQL、Redis、OSS、Elasticsearch 等外部服务。
- 部分方法可能创建/删除 ES 索引、上传/删除 OSS 文件或批量同步数据。
- 运行 `mvn test` 前应确认本地配置和外部服务可用，避免误操作。

## 12. 部署运行注意事项

1. 安装 JDK 8 和 Maven。
2. 准备 MySQL，导入 `database/teriteri.sql`。
3. 准备 Redis。
4. 准备 Elasticsearch `7.17.16`，并根据 `elasticsearch.md` 安装相关分词插件。
5. 准备 Aliyun OSS，并填写 bucket、endpoint、access key 等配置。
6. 将 `src/main/resources/application` 按 README 提示改为/复制为 `application.yml`，并填写本地真实配置。
7. 不要把真实数据库、Redis、OSS、ES 密钥提交到仓库。
8. 后端 HTTP 默认 `7070`，Netty IM 默认 `7071`。
9. ES 索引 mapping 位于 `src/main/resources/static/esindex/`，可参考测试类中的 `createIndex` 方法创建。
10. 注册普通用户后，如果需要管理员账号，可按 README 说明将 `user.role` 改为 `1` 或 `2`。

常用命令：

```bash
# 编译并运行测试；注意测试依赖外部服务，可能有副作用
mvn test

# 只运行指定测试类
mvn -Dtest=BackendApplicationTests test

# 打包
mvn package

# 通过 Maven 启动
mvn spring-boot:run

# 运行打包后的 jar
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

## 13. 适合学习的知识点清单

### Java 与 Spring

- Java 8 基础语法、集合、并发集合。
- Spring Boot 自动配置和启动流程。
- Spring MVC REST API。
- Bean 注入与配置类。
- `@Scheduled` 定时任务。
- `CompletableFuture` 和线程池。

### 数据访问

- MyBatis-Plus CRUD。
- `QueryWrapper` / `UpdateWrapper`。
- Mapper 与实体映射。
- Druid 数据库连接池。
- MySQL 表设计与关系建模。

### 缓存与搜索

- Redis String / Set / ZSet / Hash。
- RedisTemplate 序列化配置。
- 缓存和派生索引设计。
- Elasticsearch 索引、文档、mapping、查询。
- 搜索词热度、搜索建议、热搜维护。

### 安全认证

- Spring Security 过滤器链。
- JWT 生成、解析、过期校验。
- BCrypt 密码加密。
- 无状态认证和 Redis 登录态结合。
- `SecurityContextHolder` 与当前用户上下文。

### 实时通信

- Javax WebSocket。
- Netty WebSocket。
- Channel 管理。
- 弹幕广播。
- IM 在线状态和消息处理。

### 文件与对象存储

- `MultipartFile` 文件上传。
- 视频分片上传。
- OSS 图片/视频上传。
- OSS append upload。
- 临时文件清理。

### 工程实践

- 前后端分离。
- 统一响应结构。
- 配置隔离和敏感信息保护。
- MySQL/Redis/ES/OSS 数据一致性。
- 集成测试与运维脚本隔离。

## 14. 后续可优化方向

1. **配置标准化**：将 `src/main/resources/application` 改为 `application.yml.example`，真实配置通过本地文件、环境变量或配置中心管理。
2. **Netty 配置化**：将 `IMServer` 的 `7071` 和 `/im` 改为配置项，避免硬编码。
3. **包结构清晰化**：拆分 `pojo` 为 `entity`、`dto`、`vo`、`document`、`message`。
4. **基础设施边界**：将 `RedisUtil`、`ESUtil`、`OssUtil` 放到 `infrastructure`，减少 `utils` 混杂。
5. **安全模块独立**：将 `SecurityConfig`、JWT 过滤器、`JwtUtil`、`CurrentUser` 聚合到 `security` 包。
6. **异常处理**：增加全局异常处理和统一错误码，减少业务层手动拼响应。
7. **接口文档**：引入 OpenAPI/Swagger，生成接口文档。
8. **测试隔离**：把会访问外部服务的运维辅助方法移出默认测试路径或加 profile 保护。
9. **RabbitMQ 去留明确**：如果不用则移除依赖和保留类；如果使用则完善异步投稿队列、失败重试和补偿机制。
10. **一致性增强**：对 MySQL、Redis、ES、OSS 的多系统写入引入消息队列、补偿任务或 outbox 模式。
11. **WebSocket 并发安全**：弹幕连接集合可以考虑线程安全 Set，并加强连接异常清理。
12. **密钥安全**：JWT 签名密钥、OSS Key、ES 密码等应改为配置项或环境变量，不应硬编码。

## 15. 快速阅读建议

如果是第一次接手该项目，建议按以下顺序阅读：

1. `README.md`：先理解项目背景、功能和运行要求。
2. `pom.xml`：确认技术栈和版本。
3. `src/main/resources/application`：理解运行依赖和配置项。
4. `BackendApplication.java`：理解启动流程。
5. `SecurityConfig.java`、`JwtAuthenticationTokenFilter.java`、`JwtUtil.java`：理解认证流程。
6. `VideoUploadServiceImpl.java`、`OssUtil.java`：理解最复杂的视频投稿链路。
7. `ESUtil.java`、`SearchServiceImpl.java`：理解搜索链路。
8. `RedisUtil.java`、`EventListenerService.java`：理解 Redis 使用和数据修复任务。
9. `DanmuWebSocketServer.java`、`im/`：理解实时通信。
10. `database/teriteri.sql`：对照实体和业务模块理解数据模型。
