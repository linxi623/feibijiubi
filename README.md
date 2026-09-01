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

项目覆盖从用户注册到视频发布、审核和互动统计的主要链路：

```text
注册 / 登录
    -> 获取视频 Feed -> 查看详情 -> 点赞 / 投币 / 收藏 / 分享
    -> 获取 COS 临时凭证 -> 上传视频与封面 -> 提交稿件
    -> 管理员审核 -> 视频发布 -> 互动数据异步汇总
```

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

- JDK 17
- MySQL
- Docker 与 Docker Compose
- 腾讯云 COS 存储桶及访问配置

项目已提供 Maven Wrapper，无需单独安装 Maven。

### 初始化数据库

在 MySQL 中创建 `feibijiubi` 数据库，并执行：

```text
database/feibijiubi.sql
```

### 准备配置

以 `src/main/resources/application.yml.example` 为模板创建本地 `application.yml`，填写 MySQL、Redis、RabbitMQ、JWT 和腾讯云 COS 配置。

请勿将密码、密钥等敏感信息提交到仓库。

### 启动依赖服务

```bash
docker compose up -d
```

### 启动后端

Windows PowerShell 或 CMD：

```powershell
.\mvnw.cmd spring-boot:run
```

Git Bash、Linux 或 macOS：

```bash
./mvnw spring-boot:run
```

服务默认运行在 `http://localhost:8080`，接口统一使用 `/api` 前缀。

## 项目结构

```text
feibijiubi/
├── database/                      # 数据库脚本
├── docs/                          # API、设计与学习文档
├── src/main/java/com/feibijiubi/backend/
│   ├── controller/                # HTTP 接口
│   ├── service/                   # 业务逻辑
│   ├── mapper/                    # MyBatis Mapper
│   ├── dto/                       # 请求参数
│   ├── vo/                        # 响应数据
│   ├── entity/                    # 数据库实体
│   ├── interceptor/               # 登录与权限校验
│   ├── mq/                        # RabbitMQ 消息处理
│   └── config/                    # 应用配置
├── src/main/resources/
│   ├── com/feibijiubi/backend/mapper/  # MyBatis XML
│   ├── lua/                       # Redis Lua 脚本
│   └── application.yml.example    # 配置示例
├── compose.yaml                   # Redis 与 RabbitMQ
└── pom.xml
```

完整接口说明见 [`docs/api.md`](docs/api.md)，功能设计与学习记录见 [`docs/`](docs/)。

## 相关项目

- 用户端：<https://github.com/linxi623/feibijiubi-client>
- 管理端：<https://github.com/linxi623/feibijiubi-admin>

## 开发说明

- 后端基于 Spring Boot 3、MyBatis、MySQL、Redis 和 RabbitMQ 开发
- HTTP 接口遵循统一响应格式，写操作统一使用 `POST`
- 项目仍处于持续学习与迭代阶段，新增能力会同步补充接口和设计文档
