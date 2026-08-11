# teriteri-client 项目结构与技术介绍

> 本项目基于开源项目 teriteri 的用户端二次开发，现已对接**菲比啾比（feibijiubi）后端**
> （Spring Boot，`http://localhost:8080`）。本文档描述当前真实状态，
> 包括哪些功能已接通后端、哪些功能因后端尚未实现而处于降级状态。

---

## 1. 项目定位

菲比啾比视频平台的**用户端 SPA**（类 B 站）：首页推荐、视频播放、互动（点赞/投币/收藏/分享）、
投稿（COS 直传）、个人空间、账户设置等。

## 2. 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.2（Options API） | 核心框架 |
| Vue CLI | 5.x | 脚手架 / 开发服务器 / 构建 |
| Vue Router | 4.x | 路由（history 模式，`meta.requestAuth` 声明需登录页面） |
| Vuex | 4.x | 全局状态（登录态、用户、分区、视频互动态度、加载遮罩等） |
| Element Plus | 2.3（中文语言包） | UI 组件库，图标全量注册 |
| Axios | 1.4 | HTTP 请求（`$get`/`$post` 封装 + 登录等处裸用） |
| **cos-js-sdk-v5** | 1.8+ | **腾讯云 COS 视频直传**（菲比啾比新上传链路，改造新增） |
| video.js | 8.5 | 视频播放器基础 |
| ECharts | 5.4 | 图表（占位功能） |
| Less | 4.x | 样式预处理 |

Node.js 建议 16.x，包管理用 npm。改造中已移除 `spark-md5`（旧分片上传的哈希依赖）。

## 3. 目录结构

```text
teriteri-client/
├── public/                    # index.html、favicon、emoji 表情包
├── src/
│   ├── main.js                # 入口：Element Plus / Router / Vuex；挂载 $axios、$get、$post
│   ├── App.vue                # 根组件：恢复会话、加载分区；全局加载遮罩
│   ├── assets/
│   │   ├── css/ font/ img/    # 全局样式、图标字体、图片
│   │   ├── json/              # 静态数据（轮播图 carousel.json 等）
│   │   └── video/             # 登录页背景视频
│   ├── components/
│   │   ├── avatar/ carousel/ categorySelect/ cropper/ tagInput/  # 通用小组件
│   │   ├── comment/ danmu/ emoji/ favorite/ message/             # 【暂无后端】相关组件
│   │   ├── headerBar/ headerChannel/ navbar/ popover/ slider/    # 布局组件
│   │   ├── loginRegister/     # 登录注册弹窗（已对接 /api/auth/*）
│   │   ├── player/            # 自研播放器（PlayerWrapper：播放量上报 + 进度保存）
│   │   ├── search/            # 搜索组件【暂无后端】
│   │   └── UserCard/          # 用户信息卡片
│   ├── network/request.js     # $get/$post 封装：baseURL=/api、30s 超时、统一错误提示
│   ├── router/index.js        # 全部懒加载路由；守卫检查 localStorage.teri_token
│   ├── store/index.js         # Vuex：用户/分区/互动态度/弹幕列表/未读消息等
│   │                          #   actions: getPersonalInfo、loadChannels、logout（已对接）
│   ├── utils/
│   │   ├── utils.js           # 时间/数字格式化、linkify 等
│   │   └── adapter.js         # ★ 后端 VO -> 旧模板形状 适配层（本次改造新增）
│   └── views/
│       ├── IndexVue.vue       # 首页（已对接 /api/videos/feed 游标分页）
│       ├── detail/VideoDetail.vue    # 视频详情（已对接详情/点赞/投币/收藏/分享）
│       ├── platform/          # 创作中心（外壳 + 占位页）
│       │   └── children/uploadChildren/VideoUpload.vue  # ★ 投稿页（COS 直传，已重写）
│       ├── account/           # 账户设置（资料/头像/密码，已对接）
│       ├── space/             # 个人空间（用户信息已对接；投稿列表等暂无后端）
│       ├── search/            # 搜索【暂无后端】
│       └── message/           # 消息/私信【暂无后端】
├── vue.config.js              # 端口 8787；/api 代理到 http://localhost:8080（不重写路径）
├── api.md                     # 菲比啾比后端 API 文档（与后端仓库保持同步）
└── package.json
```

## 4. 与菲比啾比后端的对接

### 4.1 请求链路

- 开发服务器把 `/api/*` **原样**代理到 `http://localhost:8080`（后端路径本身以 `/api` 开头，
  **不做路径重写**——与原版 teriteri 不同）。
- 统一响应 `{code, message, data}`；认证 `Authorization: Bearer <token>`（`localStorage.teri_token`）。
- 后端约定：读 `GET`、写一律 `POST`。

### 4.2 适配层 `src/utils/adapter.js`（关键设计）

旧模板大量依赖 teriteri 的字段命名（`user.uid`、`user.avatar_url`、`video.descr`、
`{video, user, stats}` 嵌套包装）。为避免大面积改模板，在**数据获取边界**做一次形状转换：

| 函数 | 输入（后端 VO） | 输出（旧模板形状） |
|---|---|---|
| `adaptUser` | `UserVO`（`id/avatarUrl/userCount{...}`） | `uid/avatar_url/bg_url/exp/fansCount/followsCount/...` |
| `adaptVideoItem` | `VideoListItemVO`（扁平） | `{video:{...}, user:{...}, stats:{play,danmu,comment}}` |
| `adaptChannels` | `CategoryParentVO`（`children/rcmTags`） | `{mcId, mcName, scList:[{scId, scName, descr, rcmTag}]}` |

新增页面请优先复用这些适配函数，不要在模板里直接消费后端字段。

### 4.3 已对接的后端接口

| 功能 | 方法 | 路径 |
|---|---|---|
| 注册 / 登录 / 退出 | POST | `/api/auth/register`、`/api/auth/login`、`/api/auth/logout` |
| 当前用户 / 指定用户 | GET | `/api/users/me`、`/api/users/{uid}` |
| 修改资料 / 密码 / 头像 | POST | `/api/users/me`、`/api/users/me/password`、`/api/users/me/avatar` |
| 分区列表 | GET | `/api/category`（需登录） |
| 首页/推荐 Feed | GET | `/api/videos/feed?cursor&size&mcId&scId`（游标分页） |
| 视频详情 | GET | `/api/videos/{vid}`（可选登录；带 token 时返回本人互动状态） |
| 播放量 / 进度 | POST | `/api/videos/{vid}/play-count`、`/api/videos/{vid}/progress?playTime=` |
| 点赞 / 投币 / 收藏 / 分享 | POST | `/api/videos/{vid}/islike`、`/coin`、`/collect`、`/share`（Query 传参） |
| 视频直传凭证 | POST | `/api/videos/upload-url` |
| 封面上传 | POST | `/api/videos/cover` |
| 投稿 | POST | `/api/videos`（JSON） |

完整接口文档见本仓库根目录 [api.md](api.md)。

### 4.4 视频上传链路（本次改造重写）

原版 teriteri 是"前端分片 → 后端收分片"（spark-md5 + ask-chunk/upload-chunk）。
菲比啾比改为**腾讯云 COS 直传**（`VideoUpload.vue`）：

```text
1. POST /api/videos/upload-url  { fileName, contentType, fileSize }
      └─ 返回 { tempKey, bucket, region, tmpSecretId, tmpSecretKey,
                sessionToken, startTime, expiredTime }
2. cos-js-sdk-v5 uploadFile 直传 COS（SliceSize 10MB 自动分块，
   onProgress 更新进度条；pauseTask/restartTask/cancelTask 实现 暂停/继续/取消）
3. 投稿时：
   POST /api/videos/cover (multipart file)  → tempCoverKey
   POST /api/videos 提交 JSON：
     { title, sourceType, visibility, duration, mcId, scId,
       tags(逗号分隔), description, tempCoverKey, tempVideoKey }
4. 投稿后视频进入待审核状态（管理端审核通过后公开可见）
```

限制（后端强制）：视频 ≤ 2GB（mp4/3gp/mpeg），封面 ≤ 2MB（jpg/jpeg/png）。
取消上传只需取消本地 COS 任务，服务端临时记录会自行过期清理。

### 4.5 暂无后端支持的功能（降级说明）

以下模块的后端在菲比啾比中**尚未实现**，前端保留 UI，入口降级为空实现或会提示"暂未开放"：

- **弹幕**：详情页弹幕列表为空、弹幕 WebSocket 不连接（`getDanmuList`/`initWebsocket` 空实现）
- **评论**：评论区组件保留，但请求会失败（后端无 `/comment/*`）
- **消息/私信/IM**：不再连接 IM WebSocket（`.env.development` 的 WS 地址已无实际用途）
- **搜索 / 热搜**：无 `/search/*` 接口，热搜列表为空
- **收藏夹**：无收藏夹分组，收藏按钮改为对单一视频直接 收藏/取消（`/api/videos/{vid}/collect}`）
- **空间页投稿/点赞列表**：`/video/user-works`、`/video/user-love` 等无对应接口，相应板块无数据
- **关注**：详情页/用户卡片的关注按钮是静态占位（后端有 `/api/users/{uid}/subscribe`，前端待接入）

给这些功能接后端时，参考 4.2 的适配层模式和后端仓库的开发规范。

## 5. 启动与开发

```bash
npm install     # 首次改造后需重新安装（新增 cos-js-sdk-v5，移除 spark-md5）
npm run serve   # http://localhost:8787，需先启动菲比啾比后端 (8080)
npm run build
npm run lint
```

## 6. 约定与注意事项

- 登录成功后必须依次：存 token → `dispatch("getPersonalInfo")` → `dispatch("loadChannels")`
  （登录接口只返回 token；分区接口需要登录）。
- 修改密码/退出登录后旧 token 会被服务端立即失效（token_version / Redis 黑名单）。
- 播放器在 **暂停** 和 **组件卸载** 时向后端保存播放进度（仅登录用户）。
- 互动状态（liked/coin/collected/playTime）由 `GET /api/videos/{vid}` 携带 token 时返回，
  互动写接口本身不回传状态，前端本地更新。
- 部分旧代码直接改 `store.state.*`，修改时保持周边风格。
