# 菲比啾比

<p align="center">
  <img src="README.assets/logo.png" width="128" height="128" alt="菲比啾比 Logo">
</p>

<p align="center">
  <strong>一个面向学习与实践的视频社区后端。</strong><br>
  提供账户、视频、互动、审核与数据统计能力，为用户端和管理端提供统一 API。
</p>

<p align="center">
  <a href="#这是什么">这是什么？</a> ·
  <a href="#功能亮点">功能亮点</a> ·
  <a href="#安装与运行">安装</a> ·
  <a href="#项目结构">项目结构</a> ·
  <a href="#相关项目">相关项目</a>
</p>

---

<p align="center">
  <img src="README.assets/home-banner.jpg" width="100%" alt="菲比啾比首页横幅">
</p>

## 功能亮点

- **账户体系**：支持注册、登录、退出、资料维护、密码修改和用户主页
- **身份与权限**：使用 JWT 完成登录校验，并区分普通用户与管理员接口
- **视频投稿**：通过腾讯云 COS 临时凭证直传视频，支持封面上传、投稿和删除
- **视频审核**：提供管理员审核列表、稿件详情和审核状态流转
- **内容浏览**：提供视频详情、推荐 Feed、游标分页和分区筛选
- **用户互动**：支持播放、点赞、点踩、投币、收藏、分享、关注和播放进度
- **视频统计**：通过 Redis、RabbitMQ 与 Outbox 汇总互动数据，并提供失败恢复能力
- **统一接口规范**：统一响应结构、参数校验、业务异常处理和 VO 数据返回
- **学习文档**：包含 JWT、Redis、Lua、消息队列、COS 上传等专题说明

## 这是什么？

**菲比啾比（feibijiubi）** 是一个类 B 站视频社区的 Spring Boot 后端项目。面向用户端与管理端提供 HTTP API，并以规范化、可维护的后端工程实践为主要学习目标。


## 当前状态

### 已实现

- 用户注册、登录、退出和 Token 管理
- 用户资料、头像、主页背景与密码修改
- 用户公开主页、关注与取消关注
- 视频分区、推荐 Feed 和视频详情
- 视频播放、点赞、点踩、投币、收藏、分享与播放进度
- COS 视频直传凭证、封面上传、投稿与删除
- 管理员视频审核与状态查询
- Redis 分类缓存和 Lua 接口限流
- RabbitMQ 视频统计异步聚合、重试、恢复与批量落库

### 计划完善

- 弹幕系统
- 评论与回复
- 私信和消息系统
- 多收藏夹管理
- 推荐策略优化
- 密码哈希存储
- 更完整的集成测试与压力测试

## 安装与运行

### 环境要求

- Docker 与 Docker Compose
- 腾讯云 COS 存储桶及访问配置

### 使用通用配置模板

复制 `src/main/resources/application.yml.example` 为 `application.yml`，再通过环境变量填写数据库、Redis、RabbitMQ、JWT 和腾讯云 COS 配置。`application.yml` 已被 Git 忽略，不要把真实密码、密钥或 COS 凭证提交到仓库。

### 启动依赖服务

```bash
docker compose up -d --build
```

Docker Compose 会一次启动 MySQL、Redis、RabbitMQ 和 Spring Boot 后端。默认访问地址：

- API：`http://localhost:8080`
- MySQL：`127.0.0.1:3307`（容器内部为 `mysql:3306`）
- Redis：`127.0.0.1:6379`
- RabbitMQ AMQP：`127.0.0.1:25672`
- RabbitMQ 管理台：`http://localhost:25673`，默认账号密码为 `guest/guest`

#### 注意事项
- 可在项目根目录创建 `.env` 覆盖 `MYSQL_ROOT_PASSWORD`、`MYSQL_DATABASE` 和 `BACKEND_PORT` 等变量。
- 需要重新初始化数据库时，使用 `docker compose down -v`，该命令会删除 Docker 数据卷，请先确认已完成备份。
- 启动时docker映射的本地端口可能会被占有，可在`compose.yaml`或者`.env`中修改



## 项目结构

```text
feibijiubi/
├── database/                      # 数据库脚本与迁移
├── docs/                          # API、设计与学习文档
├── src/main/java/com/feibijiubi/backend/
│   ├── BackendApplication.java    # 启动类（保持根包）
│   ├── auth/                      # 登录、令牌、鉴权注解与拦截器
│   ├── user/                      # 用户资料、账户资料、关注关系
│   ├── video/                     # 视频、分类、审核、本地作品/剧集身份
│   ├── interaction/               # 点赞/投币/收藏/播放/分享/进度与请求准入
│   ├── videostats/                # 统计模型、Outbox、消费、落库、恢复、监控
│   ├── videosource/               # 外部来源预览与草稿来源绑定
│   ├── shared/                    # 跨模块复用的响应、异常、JSON 工具
│   ├── infrastructure/            # 通用 Redis/限流/COS 基础能力
│   ├── config/                    # 全应用装配（如 MVC 拦截器注册）
│   └── event/                     # 统计消息事件（类名是 MQ 类型头的一部分，固定保留）
├── src/main/resources/
│   ├── com/feibijiubi/backend/mapper/  # MyBatis XML（集中存放，namespace 指向各模块）
│   ├── lua/                       # Redis Lua 脚本
│   └── application.yml.example    # 配置示例
├── compose.yaml                   # Redis 与 RabbitMQ
└── pom.xml
```

按业务模块分包，模块内部按需保留 `controller`/`service`/`service/impl`/`mapper`/`entity`/`dto`/`vo` 等子包；
不强制每个模块都齐全。Mapper XML 仍集中放在 `src/main/resources/com/feibijiubi/backend/mapper/`，
由 `mybatis.mapper-locations` 统一加载，不随模块拆到各自的资源目录。分批重构与遗留例外见
[`docs/plans/package-by-feature-refactor.md`](docs/plans/package-by-feature-refactor.md)。

完整接口说明见 [`docs/api.md`](docs/api.md)，功能设计与学习记录见 [`docs/`](docs/)。

## 相关项目

- 用户端：<https://github.com/linxi623/feibijiubi-client>
- 管理端：<https://github.com/linxi623/feibijiubi-admin>

## 开发说明

- 后端基于 Spring Boot 3、MyBatis、MySQL、Redis 和 RabbitMQ 开发
- HTTP 接口遵循统一响应格式，写操作统一使用 `POST`
- 项目仍处于持续学习与迭代阶段，新增能力会同步补充接口和设计文档
