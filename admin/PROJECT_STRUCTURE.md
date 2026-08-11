# teriteri-admin 项目结构与技术介绍

> 本项目基于开源项目 teriteri 的管理端二次开发，现已对接**菲比啾比（feibijiubi）后端**
> （Spring Boot，`http://localhost:8080`）。本文档描述当前真实状态。

---

## 1. 项目定位

菲比啾比视频平台的**管理员后台 SPA**，核心功能是**视频审核**：

- 管理员登录（需要后端 `user.role` 为 `1` 管理员 或 `2` 超级管理员）
- 按审核状态分页浏览视频（待审核 / 已过审 / 未过审）
- 查看单个视频详情并进行审核（通过 / 驳回，驳回必须填写原因）

内容管理、案件、数据、系统设置等菜单目前都是**占位页面**，尚未实现。

## 2. 技术栈

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.2（Options API） | 核心框架 |
| Vue CLI | 5.x | 脚手架 / 开发服务器 / 构建 |
| Vue Router | 4.x | 前端路由（HTML5 history 模式） |
| Vuex | 4.x | 全局状态（登录态、用户、分区、加载遮罩） |
| Element Plus | 2.4（中文语言包） | UI 组件库，图标全量注册 |
| Axios | 1.5 | HTTP 请求（`$get`/`$post` 封装 + 裸用） |
| ECharts | 5.4 | 数据图表（数据页占位，暂未接真实数据） |
| video.js | 8.6 | 视频播放（审核详情页直接用原生 `<video>`） |
| tsparticles / vue3-particles | 2.12 | 登录页粒子特效 |
| Less | 4.x | 样式预处理 |

Node.js 建议 16.x（与 `package-lock.json` 一致），包管理用 npm。

## 3. 目录结构

```text
teriteri-admin/
├── public/                  # 静态资源（index.html、favicon）
├── src/
│   ├── main.js              # 入口：安装 Element Plus / Router / Vuex / 粒子库，
│   │                        #   挂载 $axios、$get、$post
│   ├── App.vue              # 根组件：全局加载遮罩；启动时恢复会话并加载分区
│   ├── assets/
│   │   ├── css/             # base.css 全局样式入口、global.css CSS 变量与共享类
│   │   ├── font/            # 图标字体（iconfont）
│   │   └── img/             # 图片素材
│   ├── components/
│   │   └── popover/         # VPopover 自定义气泡（页头用户菜单）
│   ├── network/
│   │   └── request.js       # $get/$post 封装：baseURL=/api、30s 超时、
│   │                        #   非 200 业务码统一弹错、登录失效跳转 /login
│   ├── router/
│   │   └── index.js         # 懒加载路由；全局守卫只检查 localStorage 的 teri_token
│   ├── store/
│   │   └── index.js         # Vuex：isLoading / isLogin / user / channels
│   │                        #   actions: getPersonalInfo、loadChannels、logout
│   ├── utils/
│   │   └── utils.js         # 工具函数（linkify 超链接化等）
│   └── views/
│       ├── LoginVue.vue     # 登录页（粒子背景；登录后校验管理员身份）
│       ├── IndexVue.vue     # 已登录后台外壳：页头 + 侧边栏 + 嵌套路由出口
│       ├── homePage/        # 首页（占位）
│       ├── content/         # 内容管理（占位）
│       ├── review/
│       │   ├── VideoReview.vue        # 视频审核列表（按状态 + 上一页/下一页翻页）
│       │   └── detail/VideoDetail.vue # 审核详情（播放、通过/驳回）
│       ├── case/            # 案件管理（占位）
│       ├── data/            # 数据中心（占位，ECharts）
│       └── system/          # 系统设置（占位）
├── vue.config.js            # 端口 8788；/api 代理到 http://localhost:8080（不重写路径）
├── api.md                   # 菲比啾比后端 API 文档（与后端仓库保持同步）
└── package.json
```

## 4. 与菲比啾比后端的对接

### 4.1 请求链路

- 开发服务器把 `/api/*` **原样**代理到 `http://localhost:8080`（后端接口路径本身以
  `/api` 开头，**不做路径重写**——这点与原版 teriteri 不同）。
- 所有响应为统一的 `ApiResponse<T>`：`{code, message, data}`，业务异常通常也是 HTTP 200，
  以 `code` 为准。
- 认证方式：`Authorization: Bearer <token>`，token 存 `localStorage.teri_token`。

### 4.2 使用的后端接口

| 用途 | 方法 | 路径 |
|---|---|---|
| 登录 | POST | `/api/auth/login`（data 只返回 `{token}`） |
| 获取当前用户 | GET | `/api/users/me`（登录后校验 `role >= 1`） |
| 退出登录 | POST | `/api/auth/logout`（服务端黑名单，旧 token 立即失效） |
| 分区列表 | GET | `/api/category`（需要登录，登录成功后才加载） |
| 审核分页 | GET | `/api/admin/videos/page?page&status&quantity` |
| 审核详情 | GET | `/api/admin/videos/{vid}` |
| 审核操作 | POST | `/api/admin/videos/{vid}/review`，body `{result: "APPROVED"\|"REJECTED", reason}` |

完整接口文档见本仓库根目录 [api.md](api.md)。

### 4.3 数据形状适配

- 后端管理端列表项是**扁平**的 `AdminVideoListItemVO`（`vid/uid/title/coverUrl/duration/createdAt`），
  不再有 `{video, user, category}` 嵌套；列表页直接消费扁平字段，投稿人只展示 UID。
- 后端**没有查询总数的接口**，列表页用"上一页 / 下一页"翻页：当前页满员（== quantity）即认为可能有下一页。
- 详情返回扁平的 `AdminVideoDetailVO`；分区名称由前端用 `mcId/scId` 从 Vuex 分区列表解析。
- 分区接口返回 `{mcId, mcName, children:[{scId, scName, description, rcmTags}]}`，
  在 `store.loadChannels` 中适配为旧组件期望的 `{mcId, mcName, scList:[{scId, scName, descr, rcmTag}]}`。
- 标签为**逗号分隔**字符串（原版 teriteri 是 `\r\n` 分隔）。
- 用户头像字段为 `avatarUrl`（原版是 `avatar_url`）。

### 4.4 与原版 teriteri 的差异（重要）

| 原版行为 | 现状 |
|---|---|
| `/api/admin/account/login` 返回 token+user | `/api/auth/login` 只返回 token，用户信息需再查 `/api/users/me` |
| 登录接口区分管理员 | 登录接口不区分，前端登录后校验 `role`，非管理员拒绝进入 |
| `/review/video/total` 查询总数 | 无对应接口，改为无总数翻页 |
| 审核操作传数字状态（含 3=永久删除） | 只支持 `APPROVED`/`REJECTED`，**无"永久删除"**，按钮已移除 |
| 驳回不需要理由 | 驳回**必须**填写原因（弹窗输入） |
| Netty 7071 / Elasticsearch | 菲比啾比后端无此依赖，相关文档已删除 |

## 5. 启动与开发

```bash
npm install
npm run serve   # http://localhost:8788，需先启动菲比啾比后端 (8080)
npm run build
npm run lint
```

前置条件：

1. 菲比啾比后端已启动（MySQL、Redis 就绪；RabbitMQ 视后端配置）。
2. 数据库中已有 `role` 为 1 或 2 的账号（普通注册账号需手动改库提权）。

## 6. 约定与注意事项

- 后端写操作一律 `POST`、读操作 `GET`（后端项目约定，无 PUT/DELETE）。
- `request.js` 的响应拦截器对 `code !== 200` 统一弹错；"您不是管理员，无权访问"会清除登录态。
- 部分旧代码直接改 `store.state.*` 而不是走 mutation，修改时保持周边风格即可。
- `VideoDetail.vue` 简介用 `v-html` 渲染（经 `utils.js` 的 `linkify`），改动时注意 XSS。
