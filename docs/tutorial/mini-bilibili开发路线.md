
## 1. 项目定位

Mini-Bilibili 不是简单的视频上传网站，而是一个包含「内容生产、内容消费、社交互动、搜索推荐、后台治理」的视频社区系统。

建议先做成一个 **可落地的中大型练手项目**，不要一开始就追求完全复刻 B 站。

### 1.1 项目目标

第一阶段目标：

- 用户可以注册、登录、修改资料
- 用户可以上传视频
- 视频可以被转码、存储、播放
- 用户可以浏览视频列表、搜索视频、查看视频详情
- 用户可以点赞、收藏、投币、评论
- 用户可以关注 UP 主
- 首页可以展示推荐视频
- 管理员可以审核视频、管理用户和评论

进阶目标：

- 弹幕系统
- 分片上传
- 视频转码队列
- 消息通知
- 排行榜
- 个性化推荐
- 数据统计看板
- 微服务拆分
- 容器化部署

---

## 2. 推荐技术栈

你做过苍穹外卖，大概率已经熟悉 Java 后端路线，所以建议继续走 Java 技术栈，这样学习曲线更平滑。

### 2.1 后端

| 模块 | 推荐技术 |
|---|---|
| 基础框架 | Spring Boot 3.x |
| Web | Spring MVC |
| ORM | MyBatis-Plus / MyBatis |
| 数据库 | MySQL 8 |
| 缓存 | Redis |
| 消息队列 | RabbitMQ / RocketMQ / Kafka，初期推荐 RabbitMQ |
| 对象存储 | MinIO，本地开发比 OSS 更方便 |
| 视频处理 | FFmpeg |
| 搜索 | Elasticsearch，初期可以先用 MySQL LIKE |
| 鉴权 | JWT + Spring Security，或者先自定义拦截器 |
| 文档 | Knife4j / Swagger |
| 定时任务 | Spring Scheduler / XXL-JOB，初期用 Spring Scheduler |
| 日志 | Logback + SLF4J |
| 部署 | Docker + Docker Compose |

### 2.2 前端

| 模块 | 推荐技术 |
|---|---|
| 框架 | Vue 3 |
| 构建工具 | Vite |
| 语言 | TypeScript，可选；如果基础一般可以先 JS |
| UI 组件库 | Element Plus / Naive UI / Ant Design Vue |
| 状态管理 | Pinia |
| 路由 | Vue Router |
| 请求 | Axios |
| 播放器 | xgplayer / DPlayer / video.js |
| 弹幕 | Danmaku.js / 自己实现简化版 |

### 2.3 开发工具

- JDK 17+
- Maven / Gradle，建议 Maven
- MySQL 8
- Redis
- MinIO
- FFmpeg
- Docker Desktop
- Postman / Apifox
- Git
- VS Code / IntelliJ IDEA

---

## 3. 项目整体架构

初期建议采用 **单体分层架构**，不要一上来就微服务。

```text
mini-bilibili
├── mini-bilibili-backend
│   ├── controller       # 接口层
│   ├── service          # 业务层
│   ├── mapper           # 数据访问层
│   ├── domain/entity    # 数据库实体
│   ├── domain/dto       # 请求参数对象
│   ├── domain/vo        # 响应对象
│   ├── common           # 通用返回、异常、常量
│   ├── config           # 配置类
│   ├── security/auth    # 登录鉴权
│   ├── mq               # 消息队列
│   ├── task             # 定时任务
│   └── utils            # 工具类
│
├── mini-bilibili-frontend
│   ├── src
│   │   ├── api          # 接口请求
│   │   ├── views        # 页面
│   │   ├── components   # 公共组件
│   │   ├── router       # 路由
│   │   ├── stores       # Pinia 状态
│   │   └── utils        # 工具函数
│
└── docker-compose.yml
```

等单体项目稳定后，再考虑拆分为：

```text
用户服务 user-service
视频服务 video-service
互动服务 interaction-service
评论服务 comment-service
搜索服务 search-service
消息服务 message-service
网关 gateway
```

---

## 4. 核心业务模块拆解

## 4.1 用户模块

### 功能

- 注册
- 登录
- 退出登录
- 修改昵称、头像、简介
- 查看用户主页
- 关注用户
- 取消关注
- 粉丝列表
- 关注列表

### 数据表建议

```sql
user
user_profile
user_follow
```

### 接口示例

```text
POST   /api/auth/register
POST   /api/auth/login
GET    /api/users/me
PUT    /api/users/me
GET    /api/users/{id}
POST   /api/users/{id}/follow
DELETE /api/users/{id}/follow
GET    /api/users/{id}/followers
GET    /api/users/{id}/following
```

### 难点

- 登录态维护
- 密码加密
- 用户名唯一性
- 关注关系的唯一约束
- 粉丝数、关注数统计

---

## 4.2 视频模块

### 功能

- 上传视频
- 上传封面
- 保存视频基本信息
- 视频转码
- 视频审核
- 视频播放
- 视频列表
- 视频详情
- 修改视频信息
- 删除视频

### 数据表建议

```sql
video
video_file
video_category
video_tag
video_tag_relation
```

### 视频状态设计

```text
UPLOADING     上传中
TRANSCODING   转码中
PENDING       待审核
PUBLISHED     已发布
REJECTED      审核拒绝
DELETED       已删除
FAILED        处理失败
```

### 接口示例

```text
POST   /api/videos/upload
POST   /api/videos
GET    /api/videos
GET    /api/videos/{id}
PUT    /api/videos/{id}
DELETE /api/videos/{id}
GET    /api/videos/{id}/play-url
```

### 难点

- 大文件上传
- 视频存储
- 视频转码
- 视频状态流转
- 视频播放地址安全
- 视频封面生成

### 推荐实现顺序

1. 先实现普通文件上传到本地目录
2. 再改成上传到 MinIO
3. 再接入 FFmpeg 获取视频时长、生成封面
4. 再使用消息队列异步转码
5. 最后实现分片上传和断点续传

---

## 4.3 播放模块

### 功能

- 视频详情页播放
- 记录播放量
- 记录观看历史
- 支持试看、暂停、继续播放

### 数据表建议

```sql
video_view
user_watch_history
```

### 难点

- 播放量不能每次刷新都加一
- 未登录用户如何统计
- 播放进度如何保存
- 热门视频如何统计

### 初期方案

- 用户打开视频详情页时，写入一次播放记录
- 使用 Redis 对同一个用户/同一 IP 做短时间去重
- 定时把 Redis 中的播放量同步到 MySQL

---

## 4.4 互动模块

### 功能

- 点赞
- 取消点赞
- 收藏
- 取消收藏
- 投币
- 分享数统计

### 数据表建议

```sql
video_like
video_favorite
favorite_folder
video_coin
```

### 接口示例

```text
POST   /api/videos/{id}/like
DELETE /api/videos/{id}/like
POST   /api/videos/{id}/favorite
DELETE /api/videos/{id}/favorite
POST   /api/videos/{id}/coin
```

### 难点

- 防止重复点赞
- 点赞数、收藏数如何高效统计
- 用户是否点赞过如何快速判断
- 高并发下计数一致性

### 初期方案

- MySQL 记录明细
- 视频表中冗余 like_count、favorite_count、coin_count
- 通过事务保证明细和计数同步

### 进阶方案

- Redis 记录计数
- 消息队列异步落库
- 定时任务修正计数

---

## 4.5 评论模块

### 功能

- 发表评论
- 回复评论
- 删除评论
- 评论点赞
- 评论分页
- 热门评论

### 数据表建议

```sql
comment
comment_like
```

### 评论结构

建议先做二级评论，不要一开始就做无限层级。

```text
一级评论
  ├── 二级回复
  ├── 二级回复
一级评论
  ├── 二级回复
```

### 难点

- 评论分页
- 二级评论查询
- 热门评论排序
- 删除评论后的展示策略
- 评论敏感词过滤

---

## 4.6 弹幕模块

### 功能

- 发送弹幕
- 根据视频时间加载弹幕
- 设置弹幕颜色、字体、位置
- 屏蔽弹幕

### 数据表建议

```sql
danmu
```

### 字段建议

```text
id
video_id
user_id
content
time_point   # 视频第几秒
color
font_size
position
created_at
```

### 接口示例

```text
POST /api/videos/{id}/danmu
GET  /api/videos/{id}/danmu?start=0&end=60
```

### 初期方案

- 播放器每隔一段时间请求某一时间段的弹幕
- 前端根据 time_point 渲染

### 进阶方案

- WebSocket 实时弹幕
- Redis 缓存热门视频弹幕

---

## 4.7 搜索模块

### 功能

- 按标题搜索视频
- 按标签搜索视频
- 搜索用户
- 搜索结果排序
- 搜索历史
- 热搜榜

### 初期方案

使用 MySQL：

```sql
WHERE title LIKE CONCAT('%', #{keyword}, '%')
```

### 进阶方案

接入 Elasticsearch：

- 视频发布后同步到 ES
- 视频修改后更新 ES
- 视频删除后从 ES 删除
- 支持标题、简介、标签联合搜索
- 支持高亮

---

## 4.8 推荐模块

推荐系统不要一上来就做复杂算法，建议分阶段。

### 第一版：规则推荐

按照以下指标排序：

```text
score = 播放量 * 0.4 + 点赞数 * 0.3 + 收藏数 * 0.2 + 评论数 * 0.1
```

### 第二版：分类推荐

根据用户常看的分类推荐。

### 第三版：协同过滤

根据相似用户行为推荐。

### 第四版：召回 + 排序

更接近真实推荐系统：

```text
候选召回：热门、同分类、同标签、关注 UP 主
排序：综合点击率、互动率、发布时间、用户兴趣
```

---

## 4.9 消息通知模块

### 功能

- 被点赞通知
- 被评论通知
- 被关注通知
- 视频审核结果通知
- 系统消息

### 数据表建议

```sql
message
```

### 初期方案

- 互动行为发生后直接写 message 表
- 用户进入消息中心分页查询

### 进阶方案

- MQ 异步创建消息
- WebSocket 实时推送
- 未读数 Redis 缓存

---

## 4.10 后台管理模块

### 功能

- 用户管理
- 视频审核
- 评论管理
- 分类管理
- 标签管理
- 数据统计

### 角色

```text
普通用户 USER
管理员 ADMIN
审核员 AUDITOR
```

### 接口示例

```text
GET  /api/admin/users
PUT  /api/admin/users/{id}/status
GET  /api/admin/videos/pending
POST /api/admin/videos/{id}/approve
POST /api/admin/videos/{id}/reject
GET  /api/admin/comments
DELETE /api/admin/comments/{id}
```

---

## 5. 数据库设计建议

## 5.1 核心表清单

第一阶段建议先设计这些表：

```text
user
user_follow
video
video_file
category
tag
video_tag_relation
video_like
favorite_folder
video_favorite
video_coin
comment
comment_like
danmu
user_watch_history
message
```

## 5.2 设计原则

- 所有表都要有 id、created_at、updated_at
- 重要业务表加 deleted 字段做逻辑删除
- 计数字段可以冗余在 video 表中，例如 like_count、comment_count
- 关联表加唯一索引，防止重复点赞、重复收藏、重复关注
- 状态字段用枚举值，不要用含义不清的数字

## 5.3 示例：video 表

```sql
CREATE TABLE video (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    description TEXT,
    cover_url VARCHAR(500),
    video_url VARCHAR(500),
    duration INT DEFAULT 0,
    category_id BIGINT,
    status VARCHAR(30) NOT NULL,
    view_count BIGINT DEFAULT 0,
    like_count BIGINT DEFAULT 0,
    favorite_count BIGINT DEFAULT 0,
    coin_count BIGINT DEFAULT 0,
    comment_count BIGINT DEFAULT 0,
    published_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    deleted TINYINT DEFAULT 0,
    INDEX idx_user_id (user_id),
    INDEX idx_category_id (category_id),
    INDEX idx_status_created_at (status, created_at)
);
```

---

## 6. 开发阶段路线图

## 阶段 0：准备阶段

### 目标

把项目边界想清楚，不急着写代码。

### 要做的事

- 明确项目名称、功能范围
- 画出核心业务流程
- 设计数据库初稿
- 搭建 Git 仓库
- 准备 README
- 准备接口文档工具
- 准备 Docker 环境

### 产出物

```text
README.md
数据库 ER 图
接口文档初稿
功能模块清单
项目目录结构
```

---

## 阶段 1：基础工程搭建

### 后端

- 创建 Spring Boot 项目
- 配置 MySQL
- 配置 MyBatis-Plus
- 配置 Redis
- 统一返回结果 Result
- 全局异常处理
- 参数校验
- 日志配置
- Swagger/Knife4j 接口文档

### 前端

- 创建 Vue 3 + Vite 项目
- 配置路由
- 配置 Axios
- 配置 Pinia
- 配置 UI 组件库
- 搭建登录页、首页基础布局

### 验收标准

- 后端可以启动
- 前端可以启动
- 前端能调用后端测试接口
- Swagger 可以访问
- 数据库连接正常

---

## 阶段 2：用户与鉴权

### 后端任务

- 用户注册
- 用户登录
- JWT 生成与校验
- 登录拦截器
- 获取当前用户信息
- 修改个人资料
- 上传头像

### 前端任务

- 注册页面
- 登录页面
- 用户信息展示
- 个人中心页面
- 登录态保存
- 路由鉴权

### 验收标准

- 用户可以注册登录
- 登录后可以访问个人中心
- 未登录不能访问需要登录的接口
- 用户可以修改头像和昵称

---

## 阶段 3：视频上传与播放 MVP

### 后端任务

- 视频上传接口
- 封面上传接口
- 视频信息保存接口
- 视频列表接口
- 视频详情接口
- 视频播放地址接口

### 前端任务

- 视频上传页面
- 视频列表页面
- 视频详情播放页
- UP 主主页基础版

### 验收标准

- 用户可以上传一个视频
- 视频可以在首页列表展示
- 点击视频可以进入详情页播放
- 视频详情页展示标题、简介、UP 主信息

---

## 阶段 4：互动能力

### 后端任务

- 点赞/取消点赞
- 收藏/取消收藏
- 创建收藏夹
- 投币
- 关注/取消关注
- 观看历史

### 前端任务

- 视频详情页展示点赞、收藏、投币按钮
- 关注按钮
- 收藏夹弹窗
- 观看历史页面

### 验收标准

- 同一个用户不能重复点赞
- 点赞数、收藏数正确变化
- 用户可以关注 UP 主
- 用户可以查看观看历史

---

## 阶段 5：评论与弹幕

### 后端任务

- 一级评论
- 二级评论
- 删除评论
- 评论点赞
- 弹幕发送
- 弹幕查询

### 前端任务

- 评论列表
- 评论输入框
- 回复评论
- 弹幕输入框
- 弹幕展示

### 验收标准

- 用户可以发表评论和回复
- 用户可以删除自己的评论
- 视频播放时可以显示弹幕
- 弹幕能按视频时间出现

---

## 阶段 6：审核与后台管理

### 后端任务

- 管理员角色
- 视频审核接口
- 评论管理接口
- 用户封禁接口
- 分类和标签管理接口

### 前端任务

- 后台登录
- 用户管理页面
- 视频审核页面
- 评论管理页面
- 分类标签页面

### 验收标准

- 普通用户不能访问管理接口
- 管理员可以审核视频
- 被拒绝视频不能公开展示
- 管理员可以删除违规评论

---

## 阶段 7：搜索、推荐、排行榜

### 后端任务

- 视频搜索
- 用户搜索
- 热门视频榜
- 分类视频列表
- 首页推荐接口
- 搜索历史

### 前端任务

- 搜索框
- 搜索结果页
- 分类页
- 排行榜页
- 首页推荐流

### 验收标准

- 可以搜索视频和用户
- 首页有推荐视频
- 排行榜按热度排序
- 分类页展示对应分类视频

---

## 阶段 8：工程化提升

### 任务

- 接入 MinIO
- 接入 FFmpeg
- 接入 RabbitMQ
- 视频异步转码
- Docker Compose 一键启动
- 日志文件输出
- 接口限流
- 统一错误码
- 单元测试和接口测试

### 验收标准

- 视频上传后自动转码
- 视频文件存储在 MinIO
- 本地可以通过 Docker Compose 启动 MySQL、Redis、MinIO、RabbitMQ
- 项目具备部署说明

---

## 阶段 9：进阶优化

### 可选方向

- 分片上传
- 断点续传
- WebSocket 实时通知
- Elasticsearch 搜索
- 推荐算法优化
- 微服务拆分
- CI/CD
- Prometheus + Grafana 监控
- Nginx 反向代理
- CDN 思路模拟

---

## 7. 推荐开发顺序

如果你不知道从哪里开始，严格按照下面顺序做：

```text
1. 后端基础工程
2. 前端基础工程
3. 用户注册登录
4. 个人中心
5. 视频上传
6. 视频列表
7. 视频播放页
8. 点赞收藏关注
9. 评论
10. 弹幕
11. 后台审核
12. 搜索
13. 推荐
14. 消息通知
15. MinIO + FFmpeg + MQ
16. Docker 部署
17. 项目文档和简历包装
```

---

## 8. 第一版 MVP 范围

不要第一版就做所有功能，第一版只做最小闭环。

### MVP 功能

```text
用户注册登录
用户上传视频
首页视频列表
视频详情播放
点赞
评论
个人主页
后台审核
```

### MVP 不做

```text
分片上传
复杂推荐
微服务
WebSocket
Elasticsearch
CDN
复杂权限系统
```

先做出能跑通的闭环，再逐步升级。

---

## 9. 每周开发计划

## 第 1 周：需求与基础工程

- 明确功能范围
- 画 ER 图
- 建表
- 创建后端项目
- 创建前端项目
- 接通前后端
- 完成统一返回和异常处理

## 第 2 周：用户系统

- 注册登录
- JWT 鉴权
- 个人资料
- 头像上传
- 路由鉴权
- 个人中心页面

## 第 3 周：视频 MVP

- 视频上传
- 封面上传
- 视频信息保存
- 视频列表
- 视频详情
- 视频播放

## 第 4 周：互动模块

- 点赞
- 收藏
- 投币
- 关注
- 观看历史
- 用户主页

## 第 5 周：评论和弹幕

- 一级评论
- 二级评论
- 评论点赞
- 评论删除
- 弹幕发送
- 弹幕展示

## 第 6 周：后台管理

- 管理员权限
- 视频审核
- 用户管理
- 评论管理
- 分类标签管理

## 第 7 周：搜索和推荐

- 视频搜索
- 用户搜索
- 分类页
- 排行榜
- 首页推荐

## 第 8 周：工程化和部署

- MinIO
- FFmpeg
- RabbitMQ
- Docker Compose
- 部署文档
- README 完善
- 简历项目描述

---

## 10. 学习重点

做这个项目时，不要只关注页面和 CRUD。真正能体现成熟度的是这些点：

### 10.1 状态流转

视频不是上传后立刻发布，而是有状态：

```text
上传中 -> 转码中 -> 待审核 -> 已发布
                      -> 审核拒绝
转码中 -> 处理失败
```

### 10.2 异步处理

视频转码是耗时任务，不应该在上传接口里同步完成。

推荐流程：

```text
用户上传视频
后端保存视频记录
发送转码消息到 MQ
转码消费者执行 FFmpeg
转码成功后更新视频状态
进入审核流程
```

### 10.3 缓存与计数

播放量、点赞数、收藏数这种数据读多写多，需要考虑：

- 是否直接写 MySQL
- 是否先写 Redis
- 如何防止重复计数
- 如何定时同步
- 如何修复计数不一致

### 10.4 权限控制

至少要区分：

- 游客
- 普通用户
- 管理员
- 审核员

### 10.5 可维护性

成熟项目要有：

- 清晰的包结构
- 统一异常处理
- 统一返回格式
- 参数校验
- 统一错误码
- 接口文档
- 日志
- 测试
- 部署文档

---

## 11. 简历包装建议

项目名称可以写：

> 基于 Spring Boot + Vue3 的视频社区平台

项目描述示例：

> 本项目是一个仿 Bilibili 的视频社区平台，支持用户注册登录、视频上传播放、点赞收藏、评论弹幕、关注、视频审核、搜索推荐等功能。后端基于 Spring Boot、MyBatis-Plus、Redis、RabbitMQ、MinIO、FFmpeg 实现，前端基于 Vue3、Vite、Pinia、Element Plus 实现。

亮点可以写：

- 使用 JWT 实现用户认证与接口权限控制
- 使用 MinIO 实现视频和封面对象存储
- 使用 FFmpeg 实现视频转码和封面截取
- 使用 RabbitMQ 实现视频转码异步化，提高上传接口响应速度
- 使用 Redis 对播放量、点赞数、热门视频进行缓存优化
- 设计视频状态机，支持上传、转码、审核、发布、拒绝等完整流程
- 实现二级评论和弹幕功能，提升视频社区互动体验
- 使用 Docker Compose 编排 MySQL、Redis、MinIO、RabbitMQ 等基础服务

---

## 12. 最终交付物清单

一个成熟项目最终至少应该有：

```text
后端源码
前端源码
数据库 SQL
接口文档
README.md
部署文档
Docker Compose 配置
项目截图
演示视频
简历描述
```

README 建议包含：

- 项目介绍
- 技术栈
- 功能列表
- 项目架构图
- 数据库设计
- 本地启动方式
- Docker 启动方式
- 接口文档地址
- 项目截图
- 后续优化方向

---

## 13. 你现在应该先做什么

如果你今天就要开始，建议按这个顺序：

1. 新建项目目录
2. 写 README 初稿
3. 画功能模块图
4. 画数据库 ER 图
5. 创建 Spring Boot 后端项目
6. 创建 Vue3 前端项目
7. 先做登录注册
8. 再做视频上传和播放

不要一开始纠结微服务、推荐算法、分布式架构。先把核心闭环做出来：

```text
用户登录 -> 上传视频 -> 审核发布 -> 首页浏览 -> 播放视频 -> 点赞评论
```

这个闭环完成后，你的 mini-bilibili 就已经有项目雏形了。
