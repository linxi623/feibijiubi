# JMeter 入门与 Apifox 接口测试指南

> 面向菲比啾比后端（`http://localhost:8080`）。
> 一句话区分两个工具：**Apifox 管"接口对不对"（功能测试），JMeter 管"接口扛不扛得住"（性能测试）**。
> 日常开发联调用 Apifox；想给简历上写真实性能数字时用 JMeter。

---

# 第一部分：JMeter 是什么

## 1.1 定位

Apache JMeter 是 Apache 基金会的**开源性能测试（压力测试）工具**，Java 编写，免费跨平台。
它的核心能力是：**模拟成百上千个用户同时请求你的接口**，然后统计吞吐量、响应时间、错误率。

你手动用 Apifox 点一次"发送"，是 1 个用户请求 1 次；
JMeter 可以模拟 500 个用户，每人连续请求 100 次，共 5 万次请求在几十秒内打到你的接口上——
这就是"压测"。

## 1.2 为什么你的项目需要它

你的后端为高并发场景做了大量设计（Redis 计数、MQ 削峰、批量落库），但这些设计到底带来了
多大提升，**没有数字就只是"我觉得"**。JMeter 能给你拿到：

| 指标 | 含义 | 简历用途 |
|---|---|---|
| **吞吐量 (Throughput / QPS)** | 每秒处理多少请求 | "播放量接口单机 QPS xxxx" |
| **响应时间 (P50/P95/P99)** | 50%/95%/99% 的请求在多少毫秒内返回 | "P99 延迟 xx ms" |
| **错误率 (Error %)** | 失败请求占比 | 验证限流/降级是否正常工作 |

一个很有说服力的实验：**对比"互动计数直接 UPDATE MySQL"和"走 Redis+MQ 链路"两种实现的 QPS**，
这个对比数字放在简历和面试里都是硬通货。

## 1.3 核心概念（5 个就够入门）

JMeter 的测试计划是一棵树，从上到下：

```text
测试计划 (Test Plan)
└── 线程组 (Thread Group)          ← 模拟多少用户、循环几次、多久启动完
    ├── HTTP 请求 (HTTP Sampler)   ← 具体请求哪个接口、什么方法、什么参数
    ├── HTTP 信息头管理器           ← 加 Authorization: Bearer xxx 等请求头
    ├── 断言 (Assertion)           ← 判断响应是否符合预期（如包含 "code":200）
    └── 监听器 (Listener)          ← 看结果：聚合报告、结果树、图表
```

| 概念 | 类比 | 关键参数 |
|---|---|---|
| 线程组 | "多少个虚拟用户" | 线程数（并发用户数）、Ramp-up（多少秒内启动完所有线程）、循环次数 |
| HTTP 取样器 | "每个用户干什么" | 协议/IP/端口/路径/方法/请求体 |
| 断言 | "怎么算成功" | 响应文本包含 `"code":200`（注意你的后端业务失败也返回 HTTP 200！） |
| 聚合报告 | "成绩单" | 吞吐量、平均值、P90/P95/P99、错误率 |
| CSV 数据文件 | "每个用户不同数据" | 让 1000 个线程用 1000 个不同账号的 token |

## 1.4 安装与最小上手示例

1. 前置：已装 JDK（你有 Java 17 ✔）。
2. 官网 https://jmeter.apache.org/download_jmeter.cgi 下载 Binaries 的 zip，解压。
3. 运行 `bin/jmeter.bat`（Windows）打开图形界面。
   - 界面语言：Options → Choose Language → Chinese (Simplified)。

**5 分钟压一次播放量接口**（这个接口可选登录，游客可直接压，最适合入门）：

1. 右键 Test Plan → 添加 → 线程(用户) → 线程组：
   线程数 `100`，Ramp-up `10` 秒，循环次数 `50`（= 总共 5000 次请求）。
2. 右键线程组 → 添加 → 取样器 → HTTP 请求：
   - 协议 `http`，服务器 `localhost`，端口 `8080`
   - 方法 `POST`，路径 `/api/videos/1/play-count`（vid 换成库里真实存在且已过审的视频）
3. 右键线程组 → 添加 → 断言 → 响应断言：
   "要测试的模式"里添加 `"code":200`。
4. 右键线程组 → 添加 → 监听器 → **聚合报告** 和 **察看结果树**。
5. 点绿色 ▶ 启动。看聚合报告的 Throughput（就是 QPS）和 99% Line。

**注意事项（重要）：**

- 压测时**关掉"察看结果树"的记录**（它很吃内存），只留聚合报告；正式跑数用命令行模式：
  `jmeter -n -t 计划.jmx -l result.jtl -e -o report/`（生成 HTML 报告）。
- 压测机和被压机最好不是同一台电脑；都在你本机时数字会偏保守，简历里注明"单机本地压测"即可。
- 压需要登录的接口时：先用 Apifox 登录拿 token → JMeter 的"HTTP 信息头管理器"里加
  `Authorization: Bearer <token>`；多用户 token 用 CSV 数据文件参数化。
- **不要压登录接口本身**——你自己写的登录失败限流会把压测流量当攻击拦掉（返回 429），
  这本身倒是验证限流生效的好方法。
- 只压自己的服务。压测公网他人服务是违法行为。

---

# 第二部分：用 Apifox 做接口测试（详细流程）

## 2.1 Apifox 是什么、为什么选它

Apifox = 接口文档 + 接口调试（Postman 的活）+ 自动化测试 + Mock 的一体化国产工具，
中文界面，免费版够用。相比 Postman：不用翻墙、文档和调试一体、对国内开发者友好。

下载：https://apifox.com/ （桌面版，Windows 直接装）。

## 2.2 第 0 步：建项目和环境

1. 新建团队/项目：`菲比啾比`。
2. 右上角"环境管理" → 新建环境 `本地开发`：
   - **前置 URL**：`http://localhost:8080`
   - 添加环境变量（先建空的，后面自动填）：
     | 变量名 | 初始值 | 用途 |
     |---|---|---|
     | `token` | （空） | 普通用户登录 token |
     | `admin_token` | （空） | 管理员 token |
     | `vid` | （空） | 测试用视频 id |

以后所有接口的路径只写 `/api/xxx`，Apifox 自动拼上前置 URL——换服务器时只改环境，不改接口。

## 2.3 第 1 步：录入接口

按 `docs/api.md` 的 25 个接口录入（对照文档抄一遍本身就是极好的复习）。建议按模块建目录：

```text
📁 账号        POST /api/auth/register | login | logout
📁 用户        GET /api/users/me | GET /api/users/{uid} | POST /api/users/me | me/password | me/avatar | {uid}/subscribe
📁 分类        GET /api/category
📁 视频        POST /api/videos/upload-url | cover | /api/videos | {vid}/delete
              GET /api/videos/{vid} | /api/videos/feed
📁 互动        POST /api/videos/{vid}/play-count | progress | islike | coin | share | collect
📁 管理员审核   POST /api/admin/videos/{vid}/review | GET {vid} | GET page
```

录入要点：

- **Path 参数**：路径里写 `/api/videos/{vid}`，Apifox 自动识别 `vid` 为路径参数，值可填 `{{vid}}` 引用环境变量。
- **Body 类型**：JSON 接口选 `application/json`；头像/封面上传选 `form-data`，字段名 `file`，类型选"文件"。
- **Query 参数**：互动类接口的参数在 Params 页签填（如 `islike=true&isSet=true`）。

## 2.4 第 2 步：登录后自动保存 token（核心技巧）

手动复制粘贴 token 又烦又容易过期，让 Apifox 自动干：

1. 打开 `POST /api/auth/login` 接口 → **后置操作** → 添加"自定义脚本"：

```javascript
// 登录成功后自动把 token 写入环境变量
const res = pm.response.json();
if (res.code === 200 && res.data && res.data.token) {
    pm.environment.set("token", res.data.token);
    console.log("token 已更新");
}
```

2. 管理员账号登录也一样，脚本里改成 `pm.environment.set("admin_token", ...)`。

3. **统一挂 Authorization**：不要每个接口手填。在目录（如 `📁 用户`）上右键 → 设置auth →
   类型选 `Bearer Token`，值填 `{{token}}`；`📁 管理员审核` 目录填 `{{admin_token}}`。
   目录下所有接口自动继承。公开接口（注册/登录/feed/视频详情）所在目录设为"无需鉴权"。

以后流程就是：跑一次登录 → token 自动更新 → 其它接口直接点发送。

## 2.5 第 3 步：给接口加断言（让"测试"自动判断对错）

每个接口的**后置操作 → 断言**，至少加两条：

| 断言对象 | 条件 | 说明 |
|---|---|---|
| 响应 JSON `$.code` | 等于 `200` | ⚠️ 你的后端业务失败也返回 HTTP 200，**必须断言业务 code 而不是 HTTP 状态码** |
| 响应时间 | 小于 `1000` ms | 基本性能兜底 |

关键接口再加数据断言，例如登录接口断言 `$.data.token` 存在、feed 接口断言 `$.data.items` 是数组。

**也要测失败分支**（用"用例"功能，一个接口存多组参数）：

- 注册：两次密码不一致 → 断言 `$.code = 400`，`$.message = 前后两次密码输入不一致`
- 登录：错密码连打 6 次 → 第 6 次断言 `$.code = 429`（验证你的限流！）
- 无 token 访问 `/api/users/me` → 断言 `$.code = 401`
- 普通用户 token 调管理员接口 → 断言 `$.code = 403`
- 重复投币同一视频 → 断言 `$.code = 400`

## 2.6 第 4 步：编排自动化测试场景（一键回归）

左侧"自动化测试" → 新建测试场景 `核心链路回归`，把接口按依赖顺序拖进来：

```text
1. POST /api/auth/register        （用随机变量当用户名，见下）
2. POST /api/auth/login           → 后置脚本存 token
3. GET  /api/users/me             → 断言 username 一致
4. POST /api/users/me             → 改昵称，断言 code=200
5. GET  /api/category             → 断言返回数组非空
6. GET  /api/videos/feed?size=5   → 后置脚本把 items[0].vid 存入环境变量 vid
7. GET  /api/videos/{{vid}}       → 断言详情字段
8. POST /api/videos/{{vid}}/play-count
9. POST /api/videos/{{vid}}/islike?islike=true&isSet=true
10. POST /api/videos/{{vid}}/coin?coin=1
11. POST /api/videos/{{vid}}/collect?isCollect=true
12. POST /api/auth/logout
13. GET  /api/users/me            → 断言 code=401（验证黑名单生效！）
```

两个实用技巧：

- **随机测试数据**：注册用户名填 `user_{{$string.alpha(8)}}`（Apifox 内置 mock 变量），
  每次跑都是新用户，可无限重复执行。
- **步骤间传数据**：第 6 步后置脚本
  ```javascript
  const res = pm.response.json();
  if (res.data && res.data.items && res.data.items.length > 0) {
      pm.environment.set("vid", res.data.items[0].vid);
  }
  ```

以后每次改完后端代码，点一次"运行"，13 步全绿 = 核心链路没被改坏。这就是**回归测试**。

**投稿链路的特殊说明**：`upload-url → 前端直传 COS → cover → 投稿` 中间那步直传发生在
浏览器端，Apifox 里可以简化验证——测 `upload-url` 断言返回了临时密钥字段即可；
完整投稿建议直接用前端页面走一遍，或先手动在 COS 控制台放一个测试文件。

## 2.7 第 5 步（进阶）：定时跑 + 测试报告

- 自动化测试场景可以设置**循环次数、间隔、并发数**（轻量压测 Apifox 也能凑合，但正经压测用 JMeter）。
- 运行完自动生成测试报告（通过率、每步耗时），可导出分享。
- 进阶玩法：Apifox CLI 可以把测试场景放进 CI 里跑，属于以后接触 CI/CD 时的选修内容。

## 2.8 与后端联调的日常节奏建议

1. 写完一个新接口 → Apifox 录入 + 加断言 → 手动调通正常/异常分支。
2. 把它补进自动化场景 → 跑一遍全量回归。
3. 涉及性能敏感改动（如你后续优化互动链路）→ JMeter 压前后对比，留下数字。
4. `docs/api.md` 与 Apifox 保持同步（谁改接口谁更新，两边都要动）。

---

# 附：常见坑速查

| 现象 | 原因 |
|---|---|
| Apifox 断言 HTTP 200 全过，但功能其实是坏的 | 你的后端业务错误也返回 HTTP 200，**必须断言 `$.code`** |
| 登录几次后一直 429 | 你自己的登录失败限流生效了，等冷却或清 Redis 里的 `login:fail:*` key |
| 带着旧 token 一直 401 | 改过密码（token_version 失效）或调过 logout（黑名单），重新登录 |
| 互动接口成功但数据库数字没变 | 正常！统计走 Redis+MQ 异步聚合，等批量落库后才入库（看 Redis 或稍后再查） |
| 上传接口 400"文件后缀不能为空" | form-data 里 file 字段选成了"文本"类型，要选"文件" |
| JMeter 压测时后端疯狂 429 | 压到了带限流的接口（upload-url），换 play-count/feed 这类没限流的压 |
