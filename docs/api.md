# 菲比啾比后端 API 文档

> 本文档依据当前 Controller、DTO、VO、Service 和拦截器实现整理，用于 Postman、Apifox、curl 以及前后端联调。
>
> 文档只描述当前已经存在的接口；代码发生变化后，应同步更新本文档。

## S2 新增：本地剧集来源候选与确认（离线候选，迁移未执行）

统一前缀：`/api/admin/local-video-episodes/{episodeId}/sources`。全部管理员专用；写操作的操作者只从登录态获取。前置为M4-A1及`database/migrations/s2-local-episode-source.sql`，不依赖未使用的M4-A2表。已有库缺表会500，不能直接启用。

本组接口最初只服务未导入草稿；M6-B 起，**已导入剧集（`vid`非空）复用同一套抓取/确认接口**新增独立线路或明确替换现有线路，确认条件更严格（见下文“M6-B 新增”）。没有新增路由，也没有新增DDL。

| 方法 | 相对路径 | 用途 |
| --- | --- | --- |
| GET | 空 | 当前`revision`、`durationSeconds`、`bindings[{id,candidate}]` |
| POST | `/candidates` | 事务外同步取详情/探测，短事务保存候选 |
| GET | `/candidates?afterId=0&limit=20` | 历史候选数组，limit 1..100，按id keyset分页 |
| POST | `/candidates/{candidateId}/confirm` | 锁剧集并核对版本，确认时长、新增/替换来源关联 |

抓取请求：`{"sourceCode":"demo","workId":"7","lineOrdinal":1,"entryOrdinal":1,"expectedRevision":0,"targetBindingId":null}`。
`workId`为1..191位无前导零正整数字符串；位置范围1..16/1..1000，只对应**本次新抓取快照**，不是历史预览的持久身份。返回后须人工核对label。targetBindingId为空新增，非空明确替换已有来源关联（sourceCode/workId不能改变）。不接受客户端URL或探测成功声明。

确认请求：`{"expectedRevision":0,"durationSeconds":1250.5,"reason":"已人工核验该集统一时长"}`。秒数有限且>0，reason非空白且最多500字符。只接受属于该剧集的服务端候选；**草稿**探测失败可人工核验填写，但不改写原失败状态。草稿首次确认设置统一时长，后续来源必须采用相同时长；不允许通过此接口变更已确认时间轴。每剧集最多32条关联。**已导入剧集不能使用草稿的人工补偿规则**：只能确认具有成功HLS证据的候选，确认时长必须等于剧集与原视频的统一时长，详见 M6-B。

候选字段：`id/episodeId/expectedRevision/targetBindingId/sourceCode/workId/lineOrdinal/entryOrdinal/label/probeStatus/candidateSeconds/capturedAt/confirmedSeconds/confirmedBy/confirmedAt/reason`。probeStatus为`SUCCEEDED/UNSUPPORTED_MANIFEST/FETCH_FAILED/PROBE_REJECTED`，失败时candidateSeconds为null。无地址、地址摘要、清单摘要或线路键字段；label/reason是需前端转义的自由文本。

确认后revision加1；版本冲突/旧候选/重复确认返回409，不承诺透明幂等重放。响应丢失先GET当前状态/候选列表。GET不联网，不消耗配额；POST抓取消费与预览共享的Redis管理员配额（不退还），使用独立实例内并发限制，最多一详情请求和一媒体请求。确认不联网。抓取/确认受默认关闭的preview-enabled及source enabled控制，历史查询不受此开关限制。

错误HTTP与业务code一致：400参数/JSON、401/403鉴权、404不存在、409冲突、415内容类型、429配额/并发、503关闭/策略缺失/Redis故障、502上游、500持久化。控制器在服务调用前设置no-store，不覆盖拦截器及绑定前失败。本片**无video创建、无发布/播放地址、无真实数据库验收**。详细步骤及敏感地址部署注意事项：[S2教程](tutorial/external-video-source-binding.md)。

## M5 新增：管理员单集导入（离线候选，迁移未执行）

```http
POST /api/admin/local-video-episodes/{episodeId}/import
Content-Type: application/json
Authorization: Bearer <管理员 token>
```

管理员专用；操作者只取登录态，请求体没有 uid / vid / sourceType / status / duration / videoUrl / videoKey。已有库须具备 M4-A1、S2 三表，再执行一次 `database/migrations/m5-local-episode-import.sql`（**本轮未执行**）。使用新版 `database/feibijiubi.sql` 新建的库已含这些变更，不要再执行该迁移。

请求体：

```json
{
  "expectedRevision": 0,
  "tempCoverKey": "temp/covers/7/20250101/cover.png",
  "title": "导入视频标题",
  "mcId": "douga",
  "scId": "mad",
  "visibility": 1
}
```

- `expectedRevision` 必填且 >= 0。仅首次导入检查它等于当前 `local_episode_source_state.revision`，版本缺行等价于0；已绑定的请求复用原 `vid`，不重新比较版本。
- `tempCoverKey` 必填，必须符合当前管理员的 `temp/covers/{uid}/...` 格式。仅首次导入检查临时记录存在、属于当前管理员、是封面、未过期且未认领；已绑定的请求只校验字段格式，不读取封面记录或访问 COS。
- `title` 必填，trim 后 1..80 字符（`video.title` 列宽）；`mcId/scId` 必填，trim 后各 1..20 字符，首次导入要求 `category` 表中存在该二级组合，并写入表中的规范分类 ID。
- `visibility` 省略时默认1（私密）；只接受0（公开）/1（私密）。
- 不接受的字段被忽略且不生效，不能覆盖时长、作者、审核状态或播放地址。

响应 data：

```json
{"episodeId":1,"vid":55,"durationSeconds":1250.5,"importRevision":0,"status":0,"reused":false}
```

只返回稳定业务标识、统一时长、首次导入来源 revision、审核状态与是否复用；**不返回** cover key、cover URL、video URL、video key、媒体地址或摘要。`status=0` 为PENDING；首次导入固定 `source_type=2`（转载），作者为执行导入的管理员，时长取剧集已确认的统一时长，不读取探测候选秒数。

事务与重复语义：

- 与S2相同，使用 `local_video_episode` 行 `FOR UPDATE`；事务内先锁再判定。
- 已绑定且 `video.deleted_at IS NULL`：返回原 `vid`、原状态与原 `importRevision`，`reused=true`；不改作者、video、统计或来源，且该判定发生在任何COS I/O之前。
- 已绑定但视频已软删：固定409，不复活、不新建第二个video。
- 首次导入：校验版本、已确认且仍启用的来源（至少一个；`preview-enabled`关闭不影响导入已确认草稿，来源停用仍拒绝）、有限且>0的剧集统一时长；事务外检查临时COS对象并复制到唯一正式key；随后同一短事务写PENDING video、初始化 `video_status`、以 `vid IS NULL` CAS写 `vid` 与 `import_revision`、最后以 `upload_temp_file.status=0→1` CAS认领封面，同时在 UPDATE 中检查所有者、封面类型和过期时间。任一必需写入影响行数不是1即回滚。
- 外部稿件 `video_url/video_key` 均为NULL，不写假COS key或外链；`cover_url/cover_key` 承接认领的COS封面，仍为NOT NULL。
- 封面补偿：仅在确认数据库回滚、或复制后尚未进入数据库事务（该copy确定未被稿件引用）时删除正式copy；提交结果未知时不删除任何对象。临时对象只在认领事务提交后删除。
- 重试不会重置video/status/统计/封面状态；不承诺任意请求透明重放。

错误：400参数/标题/分类/可见性/临时封面不合法或JSON错误；401未登录；403非管理员，或首次导入时数据库里的封面所有者与当前管理员不符（错误的 key 前缀先返回400）；404剧集不存在；409首次导入版本冲突、已软删绑定、无已确认/启用来源、无有效统一时长、临时封面状态冲突或认领条件已变化；415内容类型；500持久化或COS失败。控制器在服务调用前设置 `Cache-Control:no-store`，不覆盖拦截器及参数绑定前错误。

迁移文件 `database/migrations/m5-local-episode-import.sql` 仅新增 `local_video_episode.import_revision BIGINT NULL` 并把 `video.video_url/video_key` 放宽为NULL；初始化SQL末尾已镜像，**本轮未执行**，不代表真实MySQL回滚/并发或COS验收。审核路径同时阻止无播放媒体的外部稿件进入APPROVED（见8.1）；M6-A 新增默认关闭的 `playback-enabled` 与只读播放/选集接口，详见下文“M6-A 新增”；M6-B 允许已导入剧集通过 S2 来源接口加线/换线，详见“M6-B 新增”。M5新增测试与相关回归已在 `Java_video` 隔离环境运行，详见 [M5 验证记录](tutorial/external-video-source-manual-import.md)。

## M6-A 新增：只读播放与选集（后端通过离线回归）

两个公开接口均为 `@OptionalLogin`、GET 纯读，响应头设置 `Cache-Control: no-store`。不联网、不探测媒体、不计播放、不写 Redis/MySQL、不修改 `PublishedVideoGuard` 或进度契约。前置为 M4-A1、S2 三表及 M5 迁移；缺表时返回固定500，不能直接启用。**本轮没有新增 DDL**。

### GET /api/videos/{vid}/playback

```http
GET /api/videos/{vid}/playback?lineId=12
```

权限与现有详情一致：`vid<=0` → 400 `视频参数不合法`；不存在/未过审/已软删 → 404 `视频不存在`；`visibility=1` 且不是作者本人 → 403 `你无权查看此视频`。管理员在公共侧**没有**额外绕过。

- `lineId` 可省略，值是本地 `local_episode_source_binding.id`（BIGINT，正整数）。缺省时按本地 binding id 升序取**第一条可用线路**。指定线路必须属于当前 `vid` 所绑定的本地剧集；非法、未知或其他剧集的 lineId 一律 400 `播放线路不合法`，**不会静默回退**到默认线路。
- 旧 COS 视频没有本地剧集绑定：返回 200 `playable=false / NOT_BOUND`，**不**回退到旧详情的 `videoUrl`，也不改写 `video.video_url`。
- 无本地绑定但携带 `lineId` 仍先做归属校验：400。

可用响应示例：

```json
{
  "vid": 55,
  "playable": true,
  "reason": "OK",
  "format": "HLS",
  "url": "https://media.example.invalid/hls/index.m3u8",
  "lineId": 12,
  "episodeLabel": "第01集",
  "lines": [
    {"lineId": 12, "episodeLabel": "第01集", "available": true},
    {"lineId": 13, "episodeLabel": "第1集", "available": false}
  ]
}
```

不可播放示例：

```json
{"vid":55,"playable":false,"reason":"NOT_BOUND","format":null,"url":null,"lineId":null,"episodeLabel":null,"lines":[]}
```

`reason` 固定集合：`OK`、`NOT_BOUND`（无本地剧集/线路绑定，含旧COS）、`PLAYBACK_DISABLED`（`playback-enabled=false`）、`SOURCE_DISABLED`（全部线路或指定线路的来源已停用）、`NO_PLAYABLE_ADDRESS`（有绑定但没有线路通过资格判定）。

线路资格判定（播放接口与审核闸门共用同一实现）：

1. 部署显式开启 `app.external-video-source.playback-enabled=true`（默认 false，与 `preview-enabled` 独立）；
2. binding 当前指针所指候选已人工确认（`confirmed_at/confirmed_seconds` 非空）；
3. 仅有 HLS 探测成功证据：`probe_status=SUCCEEDED`、`manifest_hash` 为64位小写十六进制、`candidate_seconds` 有限且>0；失败/未知格式不会被假称 HLS；
4. 确认时长与剧集统一时长、视频统一时长都精确相等；
5. 来源当前存在且启用，来源媒体允许列表重新校验通过；
6. 存储地址重新计算的 SHA-256 与现有 `address_hash` 完全一致；不 trim、不规范化、不改写原始 URL 字节。

`url` **只给当前选中线路的一条**已确认地址；`lines[]` 只给本地 id、标签和按配置计算的可用性，不返回地址、地址/清单摘要、来源配置、凭据引用或未批准候选。`episodeLabel` 是上游的分集标签，不是线路名称；它与选集 `displayTitle` 都是自由文本，前端需按纯文本渲染。除选中线路的 `url` 外，其他响应字段和对象 `toString` 不输出地址；服务端异常只返回固定文案，不保留可能含 SQL 参数/结果的原始异常。

### GET /api/videos/{vid}/episodes

```http
GET /api/videos/{vid}/episodes?cursor=11&size=20
```

- 权限与前置校验同 playback：`vid<=0` 400、不存在/未过审/软删 404、私密非本人 403。
- `size` 默认20，范围1..50；`cursor` 是上一页 `nextCursor`，基于本地 `local_video_episode.id`；非法参数 400 `分页参数不合法`。
- 返回同一本地作品下**可见**剧集：`video.status=1 AND video.deleted_at IS NULL AND (visibility=0 OR uid=当前用户)`，按本地 episode.id keyset 升序，取 `size+1` 行判断 `hasMore`；没有 OFFSET 或总数查询。
- 响应 `{"items":[{"vid":56,"displayTitle":"第二集"}],"hasMore":true,"nextCursor":"11"}`。`displayTitle` 是本地展示标题，可为 null；不返回总量、不可见集数量、线路或地址。
- 当前 `vid` 没有本地剧集绑定时返回空页（`items=[]`、`hasMore=false`、`nextCursor=null`），不是 404。

### 审核闸门更新

`POST /api/admin/videos/{vid}/review` 的 APPROVED 分支：

- 旧 COS 行（`video_url`/`video_key` 非空）行为不变，**不查询** S2/M5 表；
- 外部稿件（两列均为 NULL）只有在 `playback-enabled=true` **且**上述同一资格判定确认至少一条可用 HLS 线路时才允许通过；仅打开开关、任意 URL、未确认候选、失败探测、时长不一致、来源停用或摘要不匹配都仍返回 409 `视频尚无可用播放媒体，不能审核通过`。
- 外部稿件仍保持 `video_url/video_key=NULL`；不放宽私密权限，也不新增绕过审核的写入口。

### 敏感数据与验证边界

- `local_episode_media_candidate.address` 是可能带签名的上游播放凭据：生产环境必须限制数据库/备份访问，并关闭 MyBatis SQL 参数/结果 TRACE 日志（本仓库不读取或编辑实际 `application.yml`）；播放 SQL 不把地址作为参数，但结果日志仍可能打印整行。
- 本接口只保证“按当前数据库和部署配置判定”，**不保证**上游地址当时可用、不过期或内容不变；关闭 `playback-enabled` 或停用来源只影响之后的响应，**不撤回**已经发给浏览器的上游地址，也不删除已导入数据。
- 后端普通离线回归已在 `Java_video` 通过，见 [M6-A 验证记录](tutorial/external-video-source-manual-import.md)。用户端已接入，组件回归、隔离构建和 Chromium 合成 HLS/MP4 实播已通过，详见同一计划文档。尚未验收真实上游及地址时效、生产跨域、MySQL、Redis 或生产环境。

## M6-B 新增：已导入剧集的管理员加线/换线（通过后端离线回归）

复用 S2 的四个管理员接口，不新增路由、不新增表或列：

| 方法 | 相对路径 | M6-B 行为 |
| --- | --- | --- |
| GET | 空 | 返回当前剧集 `revision`、统一时长和已有线路，不区分草稿/已导入 |
| POST | `/candidates` | 已导入剧集也可抓取并保存候选；候选仅入库，不改线路指针 |
| GET | `/candidates?afterId=0&limit=20` | 历史候选查询不变 |
| POST | `/candidates/{candidateId}/confirm` | 已导入剧集可新增独立线路或明确替换 `targetBindingId` |

### 抓取：预检与最终短事务

已导入剧集（`local_video_episode.vid` 非空）抓取前必须满足：

1. 绑定的 `video` 存在且 `deleted_at IS NULL`；
2. 该视频是外部稿件：`video_url`、`video_key` 均为 NULL；
3. 剧集统一时长与视频时长都是有限且大于0的秒数，并且精确相等。

预检在事务内读取剧集/来源版本/目标关联后结束，之后才做有界 CMS 详情和媒体探测；不持有行锁跨网络。网络结束后重新锁 `local_video_episode`（已导入时再按 `episode → video` 顺序锁原 `video`），再次核对导入状态、原 `vid`、来源版本和目标关联；若预检后发生首次导入或原 `vid` 变化，返回 409 `EPISODE_IMPORT_STATE_CHANGED`，不保存候选。

候选保存阶段只插入 `local_episode_media_candidate`，**不**修改 binding 指针、剧集时长、video、video_status、审核状态或统计。探测失败仍可保存 `UNSUPPORTED_MANIFEST/FETCH_FAILED/PROBE_REJECTED` 候选供管理员诊断，但这类候选不能用于已导入剧集的确认/替换。

### 确认：已导入剧集的严格条件

确认事务先锁剧集行，再按固定顺序锁原 video 行（`episode → video`），随后核对：

1. 请求 `expectedRevision` 等于当前剧集来源 revision，候选属于本剧集、未确认且基准版本一致；
2. 来源仍存在且启用，媒体允许列表重新校验通过，存储地址重新计算的 SHA-256 等于 `address_hash`；
3. 目标 `targetBindingId` 为空表示新增独立线路；非空必须先存在且属于本剧集，并保持原 `sourceCode/workId`。换到另一来源是新增，不是替换；
4. 候选具备与 M6-A **完全相同**的成功 HLS 证据：`probe_status=SUCCEEDED`、`manifest_hash` 为64位小写十六进制、`candidate_seconds` 有限且>0；
5. 人工确认时长与剧集统一时长、原视频时长精确相等。候选探测秒数不要求和人工统一时长相同；管理员通过必填 `reason` 明确“同一版本/同一时间轴”的人工判断。

只有全部满足才在同一短事务中执行 revision CAS、候选 write-once 确认、新增/替换 binding；任一写入影响行数不是1即回滚。已导入剧集**不会**调用 `setDuration`，不修改 `vid`、作者、标题、视频列、审核状态、进度或计数。失败候选、来源停用、地址摘要不符或时长不一致都会在写 binding 前被拒绝（按 S2 的固定 HTTP/业务码），当前有效线路保持不变。

播放开关 `app.external-video-source.playback-enabled` 不参与本管理流程；抓取/确认仍受默认关闭的 `preview-enabled` 和来源 `enabled` 控制。M6-A 播放与审核闸门的资格条件没有放宽。

### 错误与安全

- 409：版本过期、首次导入/原vid变化、已导入视频已删除、不是外部稿件、剧集与视频时长不一致、候选缺少成功HLS证据、确认时长不等于统一时长、替换目标不符、32条上限。
- 500：绑定视频缺失/数据异常、持久化失败；固定文案不携带原始异常或地址。
- 400/401/403/404/415/429/502/503/500 的其余含义与 S2 相同。
- 候选地址仍只在服务端保存；本流程不返回地址、地址/清单摘要或来源配置，不记录含地址的日志。真实部署仍须限制数据库/备份访问并关闭 MyBatis SQL 参数/结果 TRACE。

M6-B 后端回归已在 `Java_video` 隔离工作区通过，见 [人工导入进度](tutorial/external-video-source-manual-import.md)。管理端已接入已导入剧集的新增/替换线路，Chromium 模拟 API 验证通过。未新增或执行迁移，未做真实 MySQL 并发/回滚、真实上游或生产验收。

## 0. Docker Compose 启动

在项目根目录执行以下命令，可一次启动 MySQL、Redis、RabbitMQ 和 Spring Boot 后端：

```bash
docker compose up -d --build
```

后端默认监听 `http://localhost:8080`。MySQL 初始化脚本会在 `mysql-data` 为空时自动执行；已有数据卷不会重复执行。可通过根目录 `.env` 文件覆盖 `MYSQL_ROOT_PASSWORD`、`MYSQL_DATABASE`、`BACKEND_PORT` 等 Compose 变量。

---

## 1. 通用约定

### 1.1 基础地址

仓库没有固定配置 `server.port`。如果本地配置或环境变量未覆盖，Spring Boot 默认地址为：

```text
http://localhost:8080
```

如果修改了 Spring Boot 端口，以实际配置为准。

### 1.2 HTTP 方法约定

当前代码统一采用：**`GET` 用于读取，`POST` 用于一切写操作**（新增、修改、删除、互动动作）。
目前没有使用 `PUT` / `DELETE`。因此像“修改资料”“删除视频”“审核视频”这类接口也都是 `POST`。

### 1.3 Content-Type

| 请求类型 | Content-Type |
|---|---|
| JSON 请求体 | `application/json` |
| 文件上传 | `multipart/form-data` |
| Query / Path 参数 | 通常不需要设置 Content-Type |

### 1.4 通用响应结构

所有 Controller 使用 `ApiResponse<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | number | 业务状态码 |
| `message` | string | 响应提示信息 |
| `data` | object / array / string / null | 响应数据，无数据时为 `null` |

> 当前全局异常处理器没有显式设置 HTTP Status，因此已处理的业务异常通常仍返回 HTTP 200；调用方应主要根据响应 JSON 中的 `code` 判断结果。

#### 视频互动统计计数与一致性（T02—T04）

- 视频八项计数（详情、列表已有对应字段、管理员详情）以及用户 `loveCount` 使用非负 64 位整数，范围 `0..9223372036854775807`；`vid` / `uid` 等标识类型不变。
- JSON 仍输出 **number**，未改为 string。`3000000000` 可精确传输；超过 `9007199254740991` 时前端必须使用无损 JSON 解析/BigInt，不能先用默认 `JSON.parse` 转成 Number 再转 BigInt。
- 主要 HTTP 查询仍读 MySQL 最终统计，不承诺所有接口实时；异步事件在消费/刷库期间可能尚未体现在响应里。Redis 坏字段不再被解释为 0，明确返回数据错误。
- 同步取消或上限拒绝会回滚同事务内的关系更新。异步刷库的暂态下界/上界不足保留事件并延后，不强制清零，不立即隔离合法取消。
- `outbox-relay-enabled=false` 仅停止本实例 Outbox 领取/投递，不停止消费和落库。运行角色合法性、启动校验和同步/异步维护切换见 [角色契约](tutorial/video-status-runtime-roles.md)；没有新增切换或强制重建 HTTP 接口。
- 部署/回滚范围盘点见 [计数范围](tutorial/video-status-counter-range.md)，时序与退避见 [刷库延后](tutorial/video-status-flush-defer.md)。T02/T03 必须配套部署，不能混跑不兼容写入者。

#### 测试环境同步准入基线（T16-B0，无 HTTP 变更）

本轮仅新增 opt-in 测试，没有新增或修改 HTTP 路由。父代理在隔离 MySQL/Redis 上实测同步准入：3 项测试通过；单视频/十视频 admission P95 为 34.886/24.199 ms，重放为 2.952 ms。口径为单 JVM 串行服务调用（含 Redis 准入），**不是 HTTP 延迟、异步端到端延迟或生产 SLA**。测试数据保留，未迁移或删除数据，清理默认关闭。详见 [基线范围与结果](tutorial/video-interaction-test-baseline.md)。

#### 统计读路径与口径（T13：模式 B，MySQL 为唯一来源）

面向用户的统计读**一律**以 MySQL `video_status` 为唯一来源，不存在“某个接口读 Redis、另一个读 MySQL”的混合状态；统计字段也不从 Redis `current` 取。因此延迟与“统计行缺失”的语义要按接口分别理解，不能笼统说成“全站统计”：

| 接口 | 返回的统计字段 | 统计范围 | 统计行缺失时 |
|---|---|---|---|
| `GET /api/videos/{vid}` 详情 | `playTimes`、`likeTimes`、`coinTimes`、`collectTimes`、`commentTimes`、`danmuTimes`、`shareTimes`（7 项，**无** `unlikeTimes`） | 视频需已过审且未删除；私密视频仅作者本人可看 | `code=500 视频统计数据异常`（不返回 0） |
| `GET /api/videos/feed` 列表 | `playTimes`、`commentTimes`（2 项） | 已过审 + 公开（`visibility=0`）+ 未删除 | 该视频整条不出现在列表里（JOIN 丢弃，不显示 0） |
| `GET /api/users/me`、`GET /api/users/{uid}` 的 `userCount.loveCount` | `SUM(video_status.like_times)` | 只聚合已过审 + 公开 + 未删除的视频；**不减去** `unlikeTimes` | 没有符合条件的视频时为 `0`（空集聚合值，不是解析失败兜底） |
| 管理员视频详情 | 8 项（含 `unlikeTimes`），原样透传数据库值（包括超过 2^53 的整数，客户端需无损解析 JSON） | 不做可见性过滤（私密视频同样返回统计） | `code=404 视频状态为空`（与用户详情的 500 文案不同），且不再查作者/粉丝 |

- **延迟是最终一致，没有健康时延 SLA**。正常路径的量级是“约 1～2 秒”（Outbox 轮询 1 s + 刷库调度 0.5 s 加处理时间）；失败重试（10/30/120 s）或工作堆积时会到分钟级。T05 的 15 s 采样间隔、120 s 最老待处理年龄、30 s 调度心跳都是**诊断阈值**，不是延迟保证。
- 数据库异常一律向上抛出（`code=500`），不会被解释成 0 或空页；Redis 统计 Hash `current` 不参与以上任何用户可见响应。Redis 坏字段不会被解释成 0 的规则只作用于内部的只读入口，不改变 HTTP 语义。
- 注意：Redis `current` 的读取方**不止一个**，所以不能说“某个方法就是唯一的 Redis 统计读”。管理端单视频只读对账 `GET /api/admin/video-status/reconciliation?vid=...` 会做一次只读采样（见 §10.4），那是**诊断用**读数，不构成用户读入口，也不改变本节的用户可见语义；`VideoStatusServiceImpl#getByVid` 仍然**没有调用方**，不能把它当作已接线的用户读入口。
- 热榜 ZSet `feed:hot:videos:v1` 当前**没有任何读取方**：本阶段延期保留，既不接入也不停止写入，因此不要把它当成已实现的“热门列表”。取舍与成本见 [读路径一致性](tutorial/video-status-read-consistency.md)。

#### 视频统计 dirty 恢复公平性（T11）

T11 只改恢复调度器的扫描顺序和失败退避，**不新增或修改任何 HTTP 接口**，响应字段与状态码均不变。
它使用 keyset 游标（`vid > cursor`）轮转并在末尾回绕，失败 vid 进入进程内 2 秒/4096 条 LRU 退避；
恢复路径仍然只 `SADD` dirty，不引入 `SREM`。游标和退避都是单实例内存状态，重启丢失只影响效率。

运行说明（不连接隔离环境时只跑公平性单测）：

```powershell
.\mvnw.cmd "-Dtest=VideoStatusDirtyRecoveryFairnessTests" test
```

真实 MySQL/Redis 验收（需先启动独立 `compose.test.yaml`）：

```powershell
.\mvnw.cmd -DvideoStatusIT=true "-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests" test
```

验收证据见 [dirty 恢复公平性教程](tutorial/video-status-dirty-recovery-fairness.md)；该结果是测试环境
（专用 SADD 故障注入）验收，不是进程崩溃或生产环境验收。

#### 事件归档与容量核查（T14-A：**没有新增 HTTP 接口**）

T14-A 只交付测试作用域的**只读**容量核查 + 归档 dry-run 工具（`src/test/.../service/video/capacity/`），
因此本文件不新增任何请求/响应字段、状态码或路由：

- 没有新的 `/api/**` 接口，也没有新增管理员入口；唯一的运行方式是 opt-in 隔离测试
  `./mvnw -DvideoStatusIT=true -Dtest=VideoStatusCapacityAuditIsolatedTests test`；
- 工具只读：`information_schema` 体积估算（**不执行 `COUNT(*)`**）、`FORCE INDEX` + 时间窗 + `LIMIT`
  的终态 dry-run（只列 id/时间/状态，不读 payload/last_error/eventId）、Redis `INFO memory` /
  `CONFIG GET` / `DBSIZE` / 有界 `SCAN` + `MEMORY USAGE` 抽样；
- 不删除、不归档、不改数据/Redis key/TTL/配置，没有 schema 或迁移变化（**保留期政策已于 2026-10-23 批准，
  起算点与归档存储已由本轮用户确认，但这不改变本文件：仍无新接口**）；
- 只有终态（outbox `SENT=2`、consumed `FLUSHED=2`）进入 dry-run 候选，`status0/1/3` 与 Outbox
  `FAILED` 永不列为可删除；**即使是终态也一律标 `NOT_AUTHORIZED_FOR_DELETION`**——没有归档成功校验、
  没有旧消息硬拒绝/去重墓碑，`processed` 的 TTL 不构成删除 DB 幂等证据的依据。终态时间
  （`sent_at` / `flushed_at`）缺失的行不得猜成创建/接收时间，只能保守排除。
- 已批准政策（仅决策，无实现）：成功终态在线 90 天 + 归档副本 275 天；DLQ 30 天处置 SLA（不是 TTL，
  未解决不自动删除）；操作审计 365 天；普通事件重放窗口 30 天（超过转人工核查）。
- 本轮用户确认的起算点与存储（仍未实现）：在线 90 天从**终态完成**起算（outbox `sent_at` / consumed
  `flushed_at`，**不是** `created_at` / `consumed_at`）；归档副本 275 天从**归档副本校验成功**起算，
  归档延后时实际总保留可超过 365 天（不得写成从事件创建固定 365 天）；审计 365 天从审计行创建起算；
  DLQ 30 天 SLA 从首次进入死信队列起算，重投不重置、未解决不自动删除；归档使用独立私有 COS 归档桶，
  文件完整性 + 记录数量校验通过才允许删除在线数据，开发阶段仅隔离测试存储。
- 归档、删除都未实现，30 天守卫已接入消费登记侧候选切片（T14-S1）与三个管理入口（T14-S2，见下），
  但**仍无归档执行器、无删除代码、无新资源（未建归档桶）**，因此除 T14-S2 在既有 T09 preview 响应里新增
  一个只读字段 `refusalCode` 外，**没有任何调用方式、请求/响应字段或状态码变化**；字段缺口与下一步切片建议见教程 §9.2—§9.3。

T14-S1（2026-10-24 候选，**仍未新增任何 HTTP 接口**）：消费登记前统一做 30 天绝对年龄拒绝
（`VideoStatusEventAgeGuard` + `VideoStatusConsumptionServiceImpl#register`）。它对客户端可见的唯一影响是：
超过 30 天的旧事件被抛 `NonRetryableMessageException`、由消费者既有 NonRetryable → 死信队列 + ACK 边界处理，
**不进 retry 队列、也不是静默 ACK**；这发生在 MQ 消费侧，不经过任何 `/api/**` 路由，因此本文件不新增
请求/响应字段、错误码或状态码。边界为严格 `occurredAt.isBefore(now - 30 天)`（**恰好 30 天允许**），
固定常量、无配置开关。

T14-S2（2026-10-24 候选）把同一固定上界接到三个管理入口：T07 Outbox 单事件重放、T10-B1 consumed status3
重放的 `precheck` 与 `replay`、T09 status0 补偿的 `preview` 与 `runOnce`。**没有新增路由**：T07/T10-B1/T09 的
写入口仍返回原有的 `409`/`422`，只是多了一条固定原因码 `EVENT_OVER_NORMAL_REPLAY_WINDOW`；T09 preview 的
`items[]` 新增只读字段 `refusalCode`（见 §8.8）。默认 7 天窗口更严，因此默认配置下的超龄事件仍然返回原来的
`CONSUMED_OVER_SAFE_WINDOW` / `STATUS0_OVER_SAFE_WINDOW` / occurredAt 窗口说明，新码只在运维把
`consumer-recovery-auto-replay-max-age-seconds` 调到 30 天以上时兜底。本守卫不构成归档授权，
dry-run 仍 `NOT_AUTHORIZED_FOR_DELETION`；旧消费者仍会放行旧事件，因此归档安全要求全体消费者同版本部署。
细节见 [容量核查教程](tutorial/video-status-capacity-audit.md) §9.3.1—§9.3.2。

细节、容量公式与 noeviction 安全恢复见 [容量核查教程](tutorial/video-status-capacity-audit.md)。

#### 外部视频源基础表（E1-A：**没有新增 HTTP 接口**）

E1-A 是外部视频源接入（E0 契约仍为候选）里父代理单独批准的最小切片，只交付**两张空表**的 DDL 与离线结构测试：

- **没有新增或修改任何 `/api/**` 路由、请求/响应字段或状态码**，也没有生产 Java / mapper / 配置 / 调度器改动，
  因此这两张表当前在代码里既没有读写方，也没有可调用的入口；本文其余章节不受影响。
- `video_source`（来源站）：`code` 大小写敏感唯一（`utf8mb4_bin`）；`enabled TINYINT NOT NULL DEFAULT 0`（**默认停用**）；
  只有 `credential_ref VARCHAR(128) NULL` 这个凭据**引用名**，**不存任何凭据值**；
  **没有** `active_job_id` / `active_job_expire_at`（源级租约留给后续切片），也**没有** `uid` / `owner_uid` / `created_by` 等归属列。
- `video_source_series`（上游作品目录）：作品身份 = `(source_id, upstream_series_id)` **大小写敏感**唯一；
  额外 `(source_id, id)` 唯一键只为将来的复合外键提供被引用键；外键只有 `series → video_source`，无 `ON DELETE CASCADE`；
  **没有** `import_uid`。
- `utf8mb4_bin` **大小写敏感，但不等于字节严格身份**：它是 **PAD SPACE** 排序规则，比较时忽略尾部空格，
  所以 `'abc'` 与 `'abc '` 在两个唯一键上是同一行（第二次写入得到显式 1062 冲突，不是静默合并）。
  因此后续导入必须**拒绝**首尾带空白的来源编码 / 上游作品 ID（显式报错、进人工处理），
  **不得静默 trim 后合并**；本轮不改 collation（换 `utf8mb4_0900_bin` 需单独决定），也不实现任何校验代码。
- `enabled` / `sync_state` 的取值用 `CHECK` 限定，但 **MySQL 8.0.16+** 才真正强制；更低版本会解析后静默忽略，
  因此部署前提是 8.0.16+，且不得把 `CHECK` 当作唯一防线。
- 本轮**未执行**任何迁移、未连库、未启动基础设施；`CREATE TABLE IF NOT EXISTS` 重跑安全，但**不校验旧表形状**，
  执行后请按迁移文件末尾的只读 `information_schema.columns` 查询核对**列**（列名 / 类型 / 可空 / 默认值）；
  该查询**只查列**，索引与外键请另用 `SHOW CREATE TABLE` 或 `information_schema.statistics` / `key_column_usage` 核对。
- 未实现、也不得据此宣称：剧集/线路/地址/导入任务/审计等其它表、未知时长语义、播放与选集接口、
  任何 CMS 兼容性。E1 整体仍未通过。

迁移：`database/migrations/e1a-external-video-source-catalog.sql`（已镜像到 `database/feibijiubi.sql` 末尾）；
离线结构测试：`ExternalVideoSourceCatalogSchemaTests`（只读两份 `.sql` 文本，不执行 SQL）。
它是一组静态、有限的文本结构检查：钉住列集合、每列的类型/NULL/默认值、单列主键
`INT NOT NULL AUTO_INCREMENT`、唯一键内部列序、单个外键、两个 `CHECK` 取值范围（比较时去掉全部空白）、
`ENGINE=InnoDB` + `utf8mb4_unicode_ci` 表选项、`video_source` 先于 `video_source_series` 的建表顺序，
以及迁移↔镜像一致。它**不**证明 MySQL 语义：DDL 是否被接受、CHECK 是否被强制、唯一键冲突行为都未验证；
列声明顺序与 SQL 注释刻意不作为契约。本轮修复后未执行任何 Maven/测试，需父代理复跑。

#### 外部视频 URL 语法策略（E2-A：没有新增 HTTP 接口）

新增 `ExternalVideoUrlPolicy` 纯函数组件，当前没有业务调用方、Spring 注册或配置项。
仅校验 HTTPS 与显式传入的精确域名/端口允许列表，保留签名 URL 原串，拒绝信息不包含原始地址。
它不访问网络、不做 DNS 或重定向处理，也不构成完整 SSRF 防护；后续接线时需将拒绝异常转换为适当业务错误。
父级离线验证为 URL 23 项 + E1-A 11 项，共 34 项通过；不代表 CMS 兼容、网络安全或真实播放验收。

#### 苹果 CMS 离线解析（E2-B：没有新增 HTTP 接口）

`AppleCmsJsonAdapter` 提供列表及单作品详情的有界 JSON 解析，无网络、Spring 注册、数据库写入或业务调用方。
解析出的线路地址仍为 `UNVERIFIED`，不生成剧集 ID 或单集时长；快照序号不是持久身份。
任一非法条目使整份响应失败，不返回部分成功页。上游 `class` 当前只限量检查、不输出完整分类字典；调用方还需核对详情作品 ID 与请求 ID。
父级定向离线验证共 77 项通过（解析34、模型9、URL23、基础表11），不是实际播放或完整 CMS 兼容验收。

#### IP/DNS 结果校验（E2-C1：没有新增 HTTP 接口）

新增保守 IP 字节分类与受信 lookup 结果整批校验，仍无 Spring 注册或业务调用方。
lookup 实现由调用方注入，可能联网；组件没有默认网络实现，也不创建 socket。
混合公网/非公网结果整批拒绝。父级相关离线回归100项通过，不代表已实现连接期地址固定或完整 SSRF 防护。

#### 受限 CMS HTTP 客户端（E2-C2：没有新增 HTTP 接口）

`RestrictedCmsHttpClient` 仅构造批准 API base 的固定目录/详情 GET；无 Spring 注册或业务调用方。
DNS 校验钩子直接交给连接管理器；保留严格 TLS，底层使用 `Proxy.NO_PROXY`，禁自动重定向/重试/认证/解压。
仅接受200，响应上限512KiB；20秒总预算是协作检查，不是DNS/阻塞IO的硬截止。
父级141项离线测试通过；实际socket目标、TLS/SNI、代理与关闭行为未做集成验证，不能视为完整SSRF验收。

#### 本机 TLS 验证（E2-C3：没有新增 HTTP 接口）

用户授权后，以 `-DvideoSourceLocalTls=true` 运行回环随机端口、临时证书测试：9项本机TLS用例及141项相关回归，共150项通过。
验证了本机Host/SNI、正确/错误信任及主机名、禁止跳转、超限关闭、SOCKS旁路和测试服务清理。
没有修改系统信任库或访问外网。DNS分类器在此套件中被测试替身替换，因此不构成完整SSRF、上游稳定性或浏览器播放验收。

#### CMS 组合客户端（E2-D：没有新增 HTTP 接口）

`AppleCmsSourceClient` 组合受限请求与解析，校验自报页码、指定分类时每条的分类 ID、详情作品 ID 精确一致；错配整份拒绝，不返回过滤后的部分结果。无入库、自动翻页或重试。
`categoryId=null` 才表示不指定分类；`0` 仅作为合法数值，不推断上游含义。父分类展开为子分类不受支持；字段一致不证明响应真实性，空页也不能证明上游应用了分类条件。
父级154项离线测试通过（新增13项）；未重复本机TLS或外网验证。没有增加播放或管理路由。

#### M1：候选时长解析基础（没有新增接口）

离线 HLS 媒体清单解析生成候选秒数、分片数和原始内容指纹；不下载分片/密钥、不探测网络、不确认或导入视频。管理员确认后才能进入后续导入流程。跨来源同内容映射同一本地剧集/vid，来源只提供线路，不自动按标题合并。父级190项离线测试通过，管理端映射/探测/确认接口仍未实现。

#### M2：受限清单请求与探测组合（没有新增接口）

`RestrictedManifestHttpClient` 使用显式传入的媒体地址策略，与 CMS 共用受限 GET 执行器；`HlsDurationProbe` 单次获取清单后解析候选时长。无递归master/分片/密钥获取。M2本身不做确认或入库；S2现经RestrictedExternalMediaProbe调用，显式使用来源媒体策略，外层服务保存候选并接受人工确认。

抽取后父级重新验证：225项通过，包括11项本机TLS，日志 `/tmp/m2-parent-local-final.log`。本机实测签名路径/查询参数保留、固定Host/SNI和master拒绝；未访问外部源，不构成完整SSRF或实际播放验收。

#### M3-A：批准源注册表（没有新增接口）

配置及不可变注册表已实现，尚未Spring注册；默认预览关闭。使用部署字符串 `sourceCode`，不是数据库ID。API/media白名单独立，空媒体白名单无回退；并发/频率参数当前仅校验，尚未执行限流。初跑236项通过，审查修复后针对22项通过，无联网、DB或本机TLS重跑。

#### M3-B：管理员外部源预览（已实现，默认关闭）

沿用 `ApiResponse<T>`，需要登录且具备管理员权限：

| GET 路径 | 参数 | data |
| --- | --- | --- |
| `/api/admin/video-sources` | 无 | `[{code,name}]` |
| `/api/admin/video-sources/{sourceCode}/catalog` | `page=1`（1..100000）、可选非负 `categoryId` | `sourceCode,page,pageCount,total,items[{workIdentity,title,categoryId,categoryName}]` |
| `/api/admin/video-sources/{sourceCode}/detail` | 必填规范正整数 `workId` 字符串（无前导零，最多191字符） | `sourceCode,workIdentity,title,categoryId,categoryName,hasDurationHint,lines[{lineIndex,entries[{entryIndex,label}]}]` |

`sourceCode` 是部署配置引用键，不是DB ID；页码、分类和作品身份按已有CMS约束核对。位置从1开始，仅为本次快照展示，不是持久剧集/线路ID。`hasDurationHint` 不表示时长可信。标题、分类名及label是不可信文本，前端必须转义；不返回播放地址、封面外链、线路键或时长提示原文，但不保证自由文本本身不含URL。

HTTP错误：未登录401、非管理员403；参数缺失/格式/取值错误400；来源不存在、禁用或不是批准引用键统一404；默认关闭503 `PREVIEW_DISABLED`；详情缺媒体允许列表503 `MEDIA_POLICY_REQUIRED`；并发/配额429；Redis失败503；上游传输/解析/自报字段错配502（固定文案）。不回显上游错误原文或URL。

配置 `app.external-video-source`：`preview-enabled=false`；来源也默认disabled，最多32个；`max-concurrent-requests=2`（1..8，本实例）、`per-admin-requests-per-minute=30`（1..120，Redis跨实例同管理员共享60秒窗口）。目录和详情共用额度；参数/来源检查后才申请并发许可和消费额度，超并发不排队，失败释放许可但不返还已花额度。根列表不访问上游且不消耗额度。

所有路由不写业务库/不创建视频，但目录详情会写Redis配额计数。控制器执行后响应带 `Cache-Control:no-store`，包括服务拒绝；拦截器提前返回401/403和绑定前错误不在此保证内。这些M3路由没有写入口；新增草稿探测/确认见本文S2，单集导入见本文M5（离线候选，迁移未执行）。父级273项离线测试通过（`/tmp/m3b-parent-final.log`），没有真实上游或Redis端到端验收。

#### M4-A1：本地身份表文件（没有新增接口）

新增 `local_video_series` / `local_video_episode` 迁移及初始化SQL镜像，为多来源共享本地剧集/vid提供结构基础。该表文件切片当时无业务接线；后续S1已接草稿Mapper及接口，S2已接来源确认，M5已接单集导入（离线候选，迁移未执行）；没有执行SQL。25项静态结构测试通过（`/tmp/m4a1-parent-final2.log`），不代表真实MySQL约束或并发验收。

#### M4-A2：来源证据与绑定历史表文件（没有新增接口）

四表迁移 `m4a2-external-capture-binding.sql` 与初始化SQL镜像已完成，仅静态验收：42项通过（A2 17+A1 14+目录11，`/tmp/m4a2-parent-final.log`）。历史revision持有证据唯一约束；不可变历史仍须未来业务层维护，不是数据库禁止UPDATE/DELETE。未执行SQL，A2四表仍没有业务调用方。S2采用另一套三表简化方案，不使用A2；签名地址按最新决定采用常规数据库/备份权限保护，不建设自定义加密归档平台。不得把静态验收当作真实部署授权。

#### S1：管理员本地作品／剧集草稿（已实现，数据库前置未执行）

四个接口均需登录管理员，返回ApiResponse；独立于来源预览开关。使用前必须具备M4-A1两张表，缺表返回500。本轮未执行迁移。

| 方法/路径 | 请求 | data |
| --- | --- | --- |
| POST `/api/admin/local-video-series` | JSON `{"title":"作品标题"}` | 作品VO |
| GET `/api/admin/local-video-series` | `afterId=0&limit=20` | 分页VO |
| POST `/api/admin/local-video-series/{seriesId}/episodes` | JSON `{"displayTitle":"第1集"}`，可用`{}`不设标题 | 剧集VO |
| GET `/api/admin/local-video-series/{seriesId}/episodes` | `afterId=0&limit=20` | 分页VO |

作品VO：`id,localIdentity,title,createdBy,createdAt`；剧集VO：`id,localIdentity,seriesId,displayTitle,vid,durationSeconds,createdBy,createdAt`。新增剧集的vid/durationSeconds为空，不能由请求赋值。createdBy来自登录态，不等于未来首次导入video的作者；localIdentity是服务器生成的36字符小写UUIDv4，无`m:`前缀。

title必填非空，原文最多200字符；displayTitle可省略/null，原文最多255字符，非null时trim后不能为空。标题存储前trim，展示须转义。seriesId为正数；afterId>=0，limit为1..100。分页返回`items,afterId,limit,hasMore,nextAfterId`，按id升序，多取一条判断hasMore，末页nextAfterId=null。BIGINT id以JSON数字输出，客户端超过2^53时须无损解析。

创建不是幂等：重复POST可产生两个空草稿，不按标题合并，但不创建video。操作失败或响应丢失后应先查询，不承诺请求重放。非法参数/缺请求体/错误JSON400，不支持Content-Type415，未登录401，非管理员403，父作品不存在404，数据库/插入回读异常500；不统一改成409。控制器执行后的响应no-store，权限拦截等前置错误不保证该头。仅声明创建事务，未做真实DB回滚测试。

S1历史离线验收134项通过（`/tmp/s1-parent-final.log`，新增33项）。后续来源关联/探测确认见S2；S2确认后剧集查询的durationSeconds可非null。视频导入见本文M5（离线候选，迁移未执行）。

### 1.5 常见业务状态码

| code | 说明 |
|---:|---|
| `200` | 请求成功 |
| `400` | 参数错误或业务规则不满足 |
| `401` | 未登录、Token 无效或登录状态异常 |
| `403` | 没有权限或账号状态异常 |
| `404` | 资源不存在 |
| `409` | 资源状态冲突或并发操作冲突 |
| `429` | 触发限流（登录失败过多、上传凭证请求过于频繁等） |
| `500` | 服务端内部错误或数据异常 |

### 1.6 登录认证

除注册、登录以及标记为“可选登录”的接口外，`/api/**` 默认都经过登录拦截器。当前拦截器只对
`/api/auth/register` 和 `/api/auth/login` 放行，其余路径（包括 `/api/category`）都需要有效 Token。

需要登录时，请携带：

```http
Authorization: Bearer <token>
```

缺少请求头、格式错误或 Token 无效时，通常返回：

```json
{
  "code": 401,
  "message": "请先登录",
  "data": null
}
```

修改密码会提升服务端 `token_version`，退出登录会把该 Token 的 `jti` 写入 Redis 黑名单，因此旧 Token
在这两种情况下会立即失效。

### 1.7 权限等级

| 权限 | 说明 |
|---|---|
| 公开 | 不需要 Token |
| 可选登录 | 可以不带 Token；如果携带，Token 必须合法 |
| 登录 | 必须携带有效 Token |
| 管理员 | 必须登录，且角色为管理员或超级管理员 |

管理员路径 `/api/admin/**` 会经过管理员拦截器。

### 1.8 参数位置说明

| 名称 | 说明 |
|---|---|
| Path | URL 路径参数，例如 `/api/videos/{vid}` |
| Query | URL 查询参数，例如 `?size=15` |
| Body | JSON 请求体 |
| Form | `multipart/form-data` 表单字段 |

缺少必填 Query 参数或参数类型转换失败时，通常返回：

```json
{
  "code": 400,
  "message": "请求参数格式不正确",
  "data": null
}
```

> 当前没有单独统一处理所有 JSON 解析错误；请求体缺失或 JSON 格式错误时，可能返回通用错误响应。

---

## 2. 接口总览

当前 Controller 共提供 31 个接口。

### 2.1 账号与用户

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 账号 | POST | `/api/auth/register` | 公开 |
| 账号 | POST | `/api/auth/login` | 公开 |
| 账号 | POST | `/api/auth/logout` | 登录 |
| 用户 | GET | `/api/users/me` | 登录 |
| 用户 | GET | `/api/users/{uid}` | 可选登录 |
| 用户 | POST | `/api/users/me` | 登录 |
| 用户 | POST | `/api/users/me/password` | 登录 |
| 用户 | POST | `/api/users/me/avatar` | 登录 |
| 用户 | POST | `/api/users/me/background` | 登录 |
| 关注 | POST | `/api/users/{uid}/subscribe` | 登录 |

### 2.2 分类

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 分类 | GET | `/api/category` | 登录 |

### 2.3 视频与互动

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 视频 | POST | `/api/videos/upload-url` | 登录 |
| 视频 | POST | `/api/videos/cover` | 登录 |
| 视频 | POST | `/api/videos` | 登录 |
| 视频 | POST | `/api/videos/{vid}/delete` | 登录且为作者 |
| 视频 | GET | `/api/videos/{vid}` | 可选登录 |
| 视频 | GET | `/api/videos/feed` | 可选登录 |
| 互动 | POST | `/api/videos/{vid}/play-count` | 可选登录 |
| 互动 | POST | `/api/videos/{vid}/progress` | 登录 |
| 互动 | POST | `/api/videos/{vid}/islike` | 登录 |
| 互动 | POST | `/api/videos/{vid}/coin` | 登录 |
| 互动 | POST | `/api/videos/{vid}/share` | 可选登录 |
| 互动 | POST | `/api/videos/{vid}/collect` | 登录 |

> `/play-count` 与 `/share` 现在必填 `Idempotency-Key`，匿名调用还必须带 `X-Visitor-Id`（缺失/非法 → 400）；
> 两者都返回 `data{counted,duplicate,reason}`，详见 §7.1 / §7.5。

### 2.4 管理员视频审核

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 审核 | POST | `/api/admin/videos/{vid}/review` | 管理员 |
| 审核 | GET | `/api/admin/videos/{vid}` | 管理员 |
| 审核 | GET | `/api/admin/videos/page` | 管理员 |

### 2.5 管理员视频统计观测（T05）

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 观测 | GET | `/api/admin/video-status/metrics` | 管理员 |
| 观测 | GET | `/api/admin/video-status/gate?vid={vid}` | 管理员 |

> 两个接口都只读：`/metrics` 只返回采样缓存，不在请求线程查库；`/gate` 只做一次 Redis GET，
> 不写、不删、不刷新 TTL。没有新增 actuator 或未鉴权指标端点。详见
> [可观测能力教程](tutorial/video-status-observability.md)。

### 2.6 管理员 Outbox FAILED 查询与重放（T07 临时运维 / T10 极窄子集）

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 运维 | GET | `/api/admin/video-status/outbox/{eventId}` | 管理员 |
| 运维 | POST | `/api/admin/video-status/outbox/{eventId}/replay` | 管理员 |

> 仅供单个 `status=3 (FAILED)` 事件的只读定位与受审计重放，不是完整 T10：
> 不支持批量、DLQ、consumed `process_status=3` 修复或对账。GET 不返回 payload、leaseToken
> 与 `last_error` 原文；POST 的操作人只能来自登录态。详见
> [Outbox 重放 Runbook](tutorial/video-status-outbox-replay-runbook.md)。
>
> T07 已于 2026-10-22 在测试环境验收（实现主分支 `95af60d`，全链路回归 87 tests，0 失败/错误，
> 1 skipped，日志 `/tmp/improvement-main-regression3.log`）。该结论不等于生产验收、真实进程崩溃
> 演练或两 JVM 接管验证，这些仍归 T16。

### 2.7 消费重试队列与 ACK 边界（T08）

T08 **不新增或修改任何 HTTP 接口**，请求/响应字段与状态码均不变。它只改变消费失败后的重试与
确认行为，因此 HTTP 调用方无需改动；以下为运行契约：

- 重试使用三个固定 TTL 队列：`video.status.retry.queue.v1`（10 秒，名称/参数不变）、
  `video.status.retry.queue.30s.v1`（30 秒）、`video.status.retry.queue.120s.v1`（120 秒）。
  第 1/2/3 次及以后重试分别路由到 10/30/120 秒队列，每个队列只有一种 TTL，避免队头阻塞。
- 转发失败时不 ACK、不 NACK，由专用单线程恢复任务异步 `stop()` 容器并在
  `app.video-status.consumer-recovery-delay-ms`（默认 10000 毫秒）后 `start()`；消费线程不会
  无限 sleep 或立即 requeue。未确认消息由 broker 重投。
- 业务成功但 ACK 抛错时，不转发 retry/DLQ、不 NACK，只请求一次受控暂停；连接恢复后的重复
  投递由 eventId + 指纹幂等吸收。仍是至少一次投递，不承诺跨 RabbitMQ/MySQL exactly-once。
- 验收为测试环境结果：真实 10/30/120 秒 TTL 到期、真实容器 stop/start、Redis 服务端
  `CLIENT PAUSE 120000`（进程与网络连接始终存活）。**不是** Redis 进程重启、网络断连或任意
  Redis 数据丢失保证，这些仍归 T16。详见
  [消费重试与 ACK 边界教程](tutorial/video-status-consumer-retry-ack.md)。

---

### 2.8 管理员 status0 补偿只读预览（T09）

| 模块 | 方法 | 路径 | 权限 |
|---|---|---|---|
| 观测 | GET | `/api/admin/video-status/status0-compensation/preview` | 管理员 |

> 只有这一个只读 GET；不领取租约、不发送 MQ、不写 Redis/DB，默认关闭时仍可诊断。没有运行期
> pause/启停写接口。T09 实现已合入 `97c7357`，但主分支完整回归尚未执行，状态为**待最终验收**。
---

# 3. 账号接口

## 3.1 用户注册

```http
POST /api/auth/register
Content-Type: application/json
```

权限：**公开**

### 请求体

```json
{
  "username": "linxi",
  "password": "123456",
  "confirmedPassword": "123456"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名，不能为空且不能重复 |
| `password` | string | 是 | 密码，不能为空 |
| `confirmedPassword` | string | 是 | 必须与 `password` 一致 |

### 成功响应

```json
{
  "code": 200,
  "message": "恭喜你成功注册F站",
  "data": null
}
```

### 常见错误

| code | message | 触发条件 |
|---:|---|---|
| 400 | `用户名已存在` | 用户名重复 |
| 400 | `前后两次密码输入不一致` | 两次密码不同 |
| 400 | DTO 校验提示 | 必填字段为空 |

### curl

```bash
curl -X POST "http://localhost:8080/api/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"linxi","password":"123456","confirmedPassword":"123456"}'
```

---

## 3.2 用户登录

```http
POST /api/auth/login
Content-Type: application/json
```

权限：**公开**

### 请求体

```json
{
  "username": "linxi",
  "password": "123456"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 | 用户名 |
| `password` | string | 是 | 密码 |

### 成功响应

当前 `UserLoginVO` 只返回 Token，不返回用户对象：

```json
{
  "code": 200,
  "message": "登录成功",
  "data": {
    "token": "eyJhbGciOi..."
  }
}
```

### 登录失败限流

登录接口带有基于 Redis 的固定窗口失败限流：连续登录失败达到上限后，在冷却时间内会直接拒绝登录并返回
`429`；登录成功后会清空该用户名的失败计数。

### 常见错误

| code | message | 触发条件 |
|---:|---|---|
| 400 | `用户名或密码错误` | 用户不存在或密码错误 |
| 403 | `账号状态异常，无法登录` | 账号不是正常状态 |
| 429 | `尝试次数过多，请稍后再试` | 登录失败次数达到上限 |

### curl

```bash
curl -X POST "http://localhost:8080/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"linxi","password":"123456"}'
```

---

## 3.3 退出登录

```http
POST /api/auth/logout
Authorization: Bearer <token>
```

权限：**登录**

### 请求参数

无请求体。当前用户身份和 Token 上下文由登录拦截器写入请求。

### 成功响应

```json
{
  "code": 200,
  "message": "退出成功",
  "data": null
}
```

### 当前实现说明

- 退出登录会把当前 Token 的 `jti` 写入 Redis 黑名单，剩余 TTL 与 Token 过期时间对齐，之后该 Token 无法
  再通过登录拦截器（服务端立即失效）。
- 该接口标记为“允许已撤销 Token 访问”（`@AllowRevokedToken`），因此已在黑名单中的 Token 再次调用退出会
  幂等地直接返回成功。
- 客户端退出后仍应删除本地 Token。

### 常见错误

| code | message |
|---:|---|
| 401 | `登录状态失效` |
| 401 | `登录状态已失效` |

### curl

```bash
curl -X POST "http://localhost:8080/api/auth/logout" \
  -H "Authorization: Bearer <token>"
```

---

# 4. 用户接口

## 4.1 获取当前用户信息

```http
GET /api/users/me
Authorization: Bearer <token>
```

权限：**登录**

### 成功响应

```json
{
  "code": 200,
  "message": "查询成功",
  "data": {
    "id": 1,
    "username": "linxi",
    "nickname": "用户linxi",
    "avatarUrl": null,
    "backgroundUrl": null,
    "gender": 2,
    "description": null,
    "experience": 0,
    "coin": 0,
    "vip": 0,
    "status": 0,
    "role": 0,
    "auth": 0,
    "authMsg": null,
    "createdAt": "2026-07-09T10:00:00",
    "userCount": {
      "fansCount": 0,
      "starCount": 0,
      "loveCount": 0,
      "videoCount": 0
    },
    "subscribed": false
  }
}
```

> `userCount.loveCount`（获赞总数）是 `SUM(video_status.like_times)`：只计已过审、公开、未删除的视频，私密 / 未过审 / 已删除视频的点赞不计入，也不减 `unlikeTimes`；没有符合条件的视频时为 `0`。它是 MySQL 最终统计，刚发生的点赞可能还未体现（见 §1.4 的「统计读路径与口径（T13）」）。

### 常见错误

| code | message |
|---:|---|
| 401 | `请先登录` |
| 401 | `查询用户异常` |
| 403 | `账号状态异常，无法访问` |

### curl

```bash
curl "http://localhost:8080/api/users/me" \
  -H "Authorization: Bearer <token>"
```

---

## 4.2 获取指定用户信息

```http
GET /api/users/{uid}
```

权限：**可选登录**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `uid` | integer | 是 | 目标用户 ID |

### 响应

响应结构与 `GET /api/users/me` 的 `UserVO` 相同。

> `userCount.loveCount`（获赞总数）是 `SUM(video_status.like_times)`，只计已过审、公开、未删除的视频：私密 / 未过审 / 已删除视频的点赞不计入，也**不减** `unlikeTimes`。没有符合条件的视频时为 `0`。该值读 MySQL 最终统计，刚发生的点赞可能还未体现（延迟口径见 §1.4 的「统计读路径与口径（T13）」）。

### 当前实现说明

携带有效 Token 时，`subscribed` 表示当前访问者是否已关注目标用户；未登录或查询本人时返回 `false`。`GET /api/users/me` 的 `subscribed` 固定为 `false`。

### curl

```bash
curl "http://localhost:8080/api/users/2"
```

携带可选登录状态：

```bash
curl "http://localhost:8080/api/users/2" \
  -H "Authorization: Bearer <token>"
```

---

## 4.3 修改当前用户资料

```http
POST /api/users/me
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

### 请求体

```json
{
  "nickname": "新的昵称",
  "gender": 1,
  "description": "这是我的个人简介"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `nickname` | string | 否 | 昵称 |
| `gender` | number | 否 | `0` 女，`1` 男，`2` 未知 |
| `description` | string | 否 | 个人简介 |

> 具体可接受范围以 DTO 校验、Service 逻辑和数据库约束为准。

### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

### curl

```bash
curl -X POST "http://localhost:8080/api/users/me" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"nickname":"新的昵称","gender":1,"description":"这是我的个人简介"}'
```

---

## 4.4 修改当前用户密码

```http
POST /api/users/me/password
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

### 请求体

```json
{
  "oldPassword": "123456",
  "newPassword": "654321",
  "confirmedPassword": "654321"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `oldPassword` | string | 是 | 当前密码 |
| `newPassword` | string | 是 | 新密码，不能与旧密码相同 |
| `confirmedPassword` | string | 是 | 必须与 `newPassword` 相同 |

### 成功响应

```json
{
  "code": 200,
  "message": "修改成功",
  "data": null
}
```

修改成功后会提升 `token_version`，此前签发的所有 Token 立即失效，需要重新登录。

### 常见错误

| code | message |
|---:|---|
| 400 | `旧密码错误` |
| 400 | `两次密码不一致` |
| 400 | `新密码不能与旧密码一致` |
| 403 | `账号状态异常，无法修改` |

### curl

```bash
curl -X POST "http://localhost:8080/api/users/me/password" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"123456","newPassword":"654321","confirmedPassword":"654321"}'
```

---

## 4.5 修改当前用户头像

```http
POST /api/users/me/avatar
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

权限：**登录**

### Form 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file | 是 | 头像文件 |

### 文件限制

| 限制 | 当前实现 |
|---|---|
| 最大大小 | 2MB |
| MIME 类型 | `image/jpg`、`image/jpeg`、`image/png` |
| 文件名 | 必须包含后缀 |

### 成功响应

`data` 直接是头像 URL 字符串：

```json
{
  "code": 200,
  "message": "修改成功",
  "data": "https://example-cos-domain/avatar/1/xxx.png"
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `图片不能为空` |
| 400 | `图片大小不能超过2MB` |
| 400 | `图片格式不支持` |
| 400 | `文件后缀不能为空` |
| 500 | `文件上传失败` |

### curl

```bash
curl -X POST "http://localhost:8080/api/users/me/avatar" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/avatar.png"
```

---

## 4.6 修改当前用户主页背景图

```http
POST /api/users/me/background
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

权限：**登录**

表单字段：`file`，支持 JPG/PNG，最大 2 MB。成功响应的 `data` 为背景图存储地址，并同步写入用户的 `backgroundUrl`。

```bash
curl -X POST "http://localhost:8080/api/users/me/background" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/background.jpg"
```

---

## 4.7 关注或取消关注用户

```http
POST /api/users/{uid}/subscribe?isSet={boolean}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `uid` | Path | integer | 是 | 被关注用户 ID |
| `isSet` | Query | boolean | 是 | `true` 关注，`false` 取消关注 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `请求参数不能为空` |
| 400 | `不能重复关注` |
| 400 | `不能重复取消关注` |
| 500 | `关注失败` |
| 500 | `取消关注失败` |

### curl

关注：

```bash
curl -X POST "http://localhost:8080/api/users/2/subscribe?isSet=true" \
  -H "Authorization: Bearer <token>"
```

取消关注：

```bash
curl -X POST "http://localhost:8080/api/users/2/subscribe?isSet=false" \
  -H "Authorization: Bearer <token>"
```

---

# 5. 分类接口

## 5.1 获取分类树

```http
GET /api/category
Authorization: Bearer <token>
```

权限：**登录**

返回全部主分区及其子分区（两级结构），结果使用 Redis Cache-Aside 缓存。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "mcId": "douga",
      "mcName": "动画",
      "children": [
        {
          "scId": "mad",
          "scName": "MAD·AMV",
          "description": "具有一定制作程度的动画或静画的二次创作视频",
          "rcmTags": ["MAD", "AMV"]
        }
      ]
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `mcId` | string | 主分区 ID |
| `mcName` | string | 主分区名称 |
| `children` | array | 子分区列表 |
| `children[].scId` | string | 子分区 ID |
| `children[].scName` | string | 子分区名称 |
| `children[].description` | string | 子分区描述 |
| `children[].rcmTags` | array\<string> | 推荐标签 |

### curl

```bash
curl "http://localhost:8080/api/category" \
  -H "Authorization: Bearer <token>"
```

---

# 6. 视频接口

## 6.1 获取视频直传临时凭证

```http
POST /api/videos/upload-url
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

该接口生成视频临时 Object Key 和腾讯云 COS 临时凭证，前端随后直接上传视频到 COS。接口带有基于 Redis 的
上传凭证请求限流，短时间内请求过多会返回 `429`。

### 请求体

```json
{
  "fileName": "demo.mp4",
  "contentType": "video/mp4",
  "fileSize": 10485760
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileName` | string | 是 | 原始文件名，必须包含后缀 |
| `contentType` | string | 是 | 视频 MIME 类型 |
| `fileSize` | long | 是 | 文件大小，单位字节，必须大于 0 |

### 文件限制

| 限制 | 当前实现 |
|---|---|
| 最大大小 | 2GB |
| MIME 类型 | `video/mp4`、`video/3gp`、`video/mpeg` |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "tempKey": "temp/videos/1/20260709/xxx.mp4",
    "bucket": "your-bucket-name",
    "region": "ap-shenzhen-fsi",
    "tmpSecretId": "临时 SecretId",
    "tmpSecretKey": "临时 SecretKey",
    "sessionToken": "临时 sessionToken",
    "startTime": 1783580000,
    "expiredTime": 1783581800,
    "maxFileSize": 2147483648
  }
}
```

> 后续投稿时，`data.tempKey` 应作为 `tempVideoKey`。

### 常见错误

| code | message |
|---:|---|
| 400 | `请求参数不能为空` |
| 400 | `该视频文件格式不支持` |
| 400 | `文件大小不合法` |
| 400 | `该文件超出限制` |
| 400 | `文件后缀不能为空` |
| 429 | `请求过于频繁，请稍后再试` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/upload-url" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"fileName":"demo.mp4","contentType":"video/mp4","fileSize":10485760}'
```

---

## 6.2 上传视频封面

```http
POST /api/videos/cover
Authorization: Bearer <token>
Content-Type: multipart/form-data
```

权限：**登录**

### Form 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `file` | file | 是 | 视频封面图片 |

### 文件限制

| 限制 | 当前实现 |
|---|---|
| 最大大小 | 2MB |
| MIME 类型 | `image/jpg`、`image/jpeg`、`image/png` |
| 文件名 | 必须包含后缀 |

### 成功响应

`data` 是临时封面 Object Key：

```json
{
  "code": 200,
  "message": "success",
  "data": "temp/covers/1/20260709/xxx.png"
}
```

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/cover" \
  -H "Authorization: Bearer <token>" \
  -F "file=@/path/to/cover.png"
```

---

## 6.3 投稿视频

```http
POST /api/videos
Authorization: Bearer <token>
Content-Type: application/json
```

权限：**登录**

投稿前必须已经：

1. 调用 `/api/videos/upload-url` 并将视频上传至返回的 `tempKey`。
2. 调用 `/api/videos/cover` 获得临时封面 Key。

### 请求体

```json
{
  "title": "我的第一个视频",
  "sourceType": 1,
  "visibility": 0,
  "duration": 120.5,
  "mcId": "douga",
  "scId": "mad",
  "tags": "测试,菲比啾比,投稿",
  "description": "这是我的第一个投稿视频",
  "tempCoverKey": "temp/covers/1/20260709/xxx.png",
  "tempVideoKey": "temp/videos/1/20260709/xxx.mp4"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 视频标题 |
| `sourceType` | integer | 是 | 来源类型约定：`1` 自制、`2` 转载；当前 Service 未校验取值范围 |
| `visibility` | integer | 是 | `0` 公开，`1` 私密 |
| `duration` | double | 是 | 时长，单位秒，必须大于 0 |
| `mcId` | string | 是 | 主分区 ID；当前仅校验非空，未校验分区是否存在 |
| `scId` | string | 是 | 子分区 ID；当前仅校验非空，未校验分区是否存在或是否属于主分区 |
| `tags` | string | 否 | 标签，可使用逗号分隔 |
| `description` | string | 否 | 视频简介 |
| `tempCoverKey` | string | 是 | 当前用户未过期、未提交的临时封面 Key |
| `tempVideoKey` | string | 是 | 当前用户未过期、未提交的临时视频 Key |

### 成功响应

```json
{
  "code": 200,
  "message": "投稿成功",
  "data": {
    "vid": 1,
    "title": "我的第一个视频",
    "coverUrl": "https://example-cos-domain/covers/1/xxx.png",
    "videoUrl": "https://example-cos-domain/videos/1/xxx.mp4"
  }
}
```

投稿成功后，视频状态为 `0`（审核中），但当前响应不返回 `status`。

### 视频状态

| status | 枚举 | 说明 |
|---:|---|---|
| 0 | `PENDING` | 审核中 |
| 1 | `APPROVED` | 审核通过 |
| 2 | `REJECTED` | 打回整改 |
| 3 | `REMOVED` | 违规删除 |

### 常见错误

| code | message |
|---:|---|
| 400 | `视频时长不合法` |
| 400 | `视频可见性不合法` |
| 400 | `视频临时文件路径不合法` |
| 400 | `封面临时文件路径不合法` |
| 400 | `视频临时文件记录不存在` |
| 400 | `封面临时文件记录不存在` |
| 403 | `视频临时文件不属于当前用户` |
| 403 | `封面临时文件不属于当前用户` |
| 500 | `视频投稿失败` |
| 500 | `视频统计初始化失败` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"title":"我的第一个视频","sourceType":1,"visibility":0,"duration":120.5,"mcId":"douga","scId":"mad","tags":"测试,菲比啾比,投稿","description":"这是我的第一个投稿视频","tempCoverKey":"temp/covers/1/xxx.png","tempVideoKey":"temp/videos/1/xxx.mp4"}'
```

---

## 6.4 投稿者删除自己的视频

```http
POST /api/videos/{vid}/delete
Authorization: Bearer <token>
```

权限：**登录且必须是视频作者**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

### 成功响应

```json
{
  "code": 200,
  "message": "删除成功",
  "data": null
}
```

### 当前删除行为

- 使用 `deleted_at` 逻辑删除视频。
- SQL 同时校验视频 ID、作者 ID 和未删除状态。
- 数据库事务提交后，再清理腾讯云 COS 中的正式视频和封面文件。
- 不物理删除 `video_status` 和 `user_video` 数据。

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数不合法` |
| 403 | `你无权删除该视频` |
| 404 | `视频不存在` |
| 404 | `视频已经删除` |
| 409 | `视频已经被删除，请勿重复操作` |
| 500 | `视频删除事务未正确开启` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/delete" \
  -H "Authorization: Bearer <token>"
```

---

## 6.5 获取视频详情

```http
GET /api/videos/{vid}
```

权限：**可选登录**

接口只查询审核通过且未删除的视频。私密视频仅作者本人可以查看。

> 统计字段来自 MySQL `video_status`，异步聚合落库前可能尚未体现刚发生的互动（最终一致，约 1～2 秒量级，无 SLA）。统计行缺失返回 `code=500 视频统计数据异常`，不会显示成 0；`unlikeTimes`（踩）不在本接口返回。逐接口口径见 §1.4 的「统计读路径与口径（T13）」。

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "vid": 1,
    "uid": 2,
    "title": "我的第一个视频",
    "sourceType": 1,
    "duration": 120.5,
    "mcId": "douga",
    "scId": "mad",
    "tags": "测试,菲比啾比,投稿",
    "description": "这是视频简介",
    "coverUrl": "https://example-cos-domain/covers/1/cover.png",
    "videoUrl": "https://example-cos-domain/videos/1/video.mp4",
    "createdAt": "2026-07-12T10:00:00",
    "playTimes": 100,
    "likeTimes": 20,
    "coinTimes": 3,
    "collectTimes": 8,
    "commentTimes": 0,
    "danmuTimes": 0,
    "shareTimes": 5,
    "liked": false,
    "coin": 0,
    "collected": false,
    "playTime": 0.0,
    "avatarUrl": "https://example-cos-domain/avatar/2.png",
    "nickname": "投稿用户",
    "videoCount": 1,
    "fansCount": 0,
    "subscribed": false
  }
}
```

未登录或不存在用户互动记录时，当前转换器使用以下默认值：

```text
liked = false
coin = 0
collected = false
playTime = 0.0
```

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数不合法` |
| 403 | `你无权查看此视频` |
| 404 | `视频不存在` |
| 500 | `视频统计数据异常` |
| 500 | `视频作者数据异常` |

### curl

游客：

```bash
curl "http://localhost:8080/api/videos/1"
```

登录用户：

```bash
curl "http://localhost:8080/api/videos/1" \
  -H "Authorization: Bearer <token>"
```

---

## 6.6 获取视频 Feed

```http
GET /api/videos/feed?cursor={cursor}&size={size}&mcId={mcId}&scId={scId}
```

权限：**可选登录**

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `cursor` | string | 否 | 无 | 下一页游标，首次请求不传 |
| `size` | integer | 否 | `15` | 每页数量，取值范围 `1`～`25` |
| `mcId` | string | 否 | 无 | 主分区 ID，按主分区过滤 |
| `scId` | string | 否 | 无 | 子分区 ID，按子分区过滤 |

`size` 由 Bean Validation 约束在 `1`～`25`，越界会返回 `400`（如 `每页数量不能小于1`、`每页数量不能多余25`）。

内部游标格式为：

```text
{createdAt}_{vid}
```

客户端应直接使用响应中的 `nextCursor`，不要自行拼接。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "items": [
      {
        "vid": 1,
        "uid": 2,
        "title": "我的第一个视频",
        "coverUrl": "https://example-cos-domain/covers/1/cover.png",
        "duration": 120.5,
        "playTimes": 100,
        "commentTimes": 0,
        "createdAt": "2026-07-12T10:00:00",
        "nickname": "投稿用户"
      }
    ],
    "nextCursor": "2026-07-12T10:00:00_1",
    "hasMore": true
  }
}
```

### 当前实现注意

- 游标格式错误当前可能被包装为 `500`，而不是更合理的 `400`。
- 该接口可携带 Token，但当前 Feed 业务逻辑不使用当前登录用户。
- 列表统计只有 `playTimes` / `commentTimes` 两项，来自与列表同一条 SQL 的 `video_status` JOIN；不会为每条视频再查一次统计，也不读 Redis。统计行缺失的视频会被 JOIN 丢弃（不显示为 0），私密视频不出现在 Feed。延迟与缺失语义见 §1.4 的「统计读路径与口径（T13）」。

### curl

首次请求：

```bash
curl "http://localhost:8080/api/videos/feed?size=15"
```

按分区过滤 + 下一页：

```bash
curl "http://localhost:8080/api/videos/feed?cursor=<nextCursor>&size=15&mcId=douga&scId=mad"
```

---

# 7. 用户视频互动接口

## 7.1 增加播放量

```http
POST /api/videos/{vid}/play-count
Idempotency-Key: <业务请求幂等键>
X-Visitor-Id: <客户端随机 UUID>      # 匿名必填；登录态完全忽略
Authorization: Bearer <token>        # 可选
```

权限：**可选登录**

### 请求头

| 头 | 必填 | 规则 | 说明 |
|---|---|---|---|
| `Idempotency-Key` | 是 | `[A-Za-z0-9._:-]{1,64}`，**大小写敏感** | 同一次业务请求的重试必须复用同一个值；缺失或非法 → 400 |
| `X-Visitor-Id` | 匿名必填，登录态忽略 | canonical UUID（`8-4-4-4-12` 十六进制，大小写不敏感） | 客户端自行随机生成（如 `crypto.randomUUID()`），服务端不生成、不下发 Cookie；缺失或非法 → 400 |
| `Authorization` | 否 | `Bearer <token>` | 带合法 Token 时按登录用户计数；Token 非法仍 401（不因带了 `X-Visitor-Id` 而放行） |

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 已过审且未删除的视频 ID |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "counted": true,
    "duplicate": false,
    "reason": "COUNTED"
  }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `counted` | boolean | 这个 `Idempotency-Key` 对应的请求是否**真的计了一次数**（重放时描述的是原始请求的结果） |
| `duplicate` | boolean | `true` = 命中了已存在的 `Idempotency-Key`，本次是幂等重放，没有再计一次 |
| `reason` | string | `COUNTED` = 计了一次；`WINDOW_SUPPRESSED` = 同一 actor 对同一视频在滚动 30 分钟内已经计过，本次未计数 |

响应**不**返回 `requestId`、`eventId`、`actorHash`、窗口时间或剩余额度。

### 计数语义

- 同一 actor（登录用户 = uid；匿名 = `X-Visitor-Id`）对同一视频的 `PLAY`，**滚动 30 分钟内最多计一次**；
  被抑制的请求不推进窗口。
- 同一 `Idempotency-Key` 在**记录保留期内**（自 `completed_at` 起 30 天）只计一次：重复提交返回原始结果
  （包括“被抑制”），不会因为过了 30 分钟就重新判定。保留期外（记录已被清理任务删掉）同一键按**新请求**
  处理，仍旧受 30 分钟滚动窗口约束，所以可能重新计数。
  注意：清理**默认关闭**，且是**按批推进的维护任务**，不是 30 天到点的 TTL——一条 31 天前的记录在下一轮
  清理读到它之前依然会重放。细节见 `docs/tutorial/video-interaction-idempotency.md` §12。
- 计数仍走既有统计管道（异步 Outbox → 消费者 → 刷库，或同步守卫更新），因此
  `GET /api/videos/{vid}` 的 `playTimes` 是最终一致，不保证立即可见（见 §1.4）。
- 视频校验发生在幂等重放**之前**：视频被下架/删除后，即便用同一个 `Idempotency-Key` 重试也返回 **404**，
  不会返回历史结果。
- 防刷边界：匿名身份由客户端随机 UUID 决定，客户端可随时换一个新的 UUID 获得新身份，所以这是对合作
  客户端的约束，不是不可绕过的防刷墙；服务端不采集 IP、User-Agent 或设备指纹。

### 错误响应

HTTP 状态与响应体 `code` 相同（与 §1.4 的通用说明不同：`GlobalExceptionHandler` 按 `BusinessException.code`
设置 HTTP Status，所以这两个接口的 400/404/409/429 都是**真实 HTTP 状态**）：

| HTTP / code | 场景 | `message` 示例 |
|---|---|---|
| 400 | 缺/非法 `Idempotency-Key` | `requestKey 必须是 1..64 位字母/数字/点/下划线/冒号/连字符`（错误文案里的 `requestKey` 指 `Idempotency-Key`） |
| 400 | 匿名但缺/非法 `X-Visitor-Id` | `匿名请求必须提供 X-Visitor-Id 请求头（客户端随机生成的 canonical UUID）` / `X-Visitor-Id 必须是 canonical UUID（8-4-4-4-12 十六进制）` |
| 400 | `vid` 非法 | `视频参数不合法` |
| 401 | 带了非法/已失效 Token | `请先登录` / `登录已失效` |
| 404 | 视频不存在 / 未过审 / 已删除 | `视频不存在` |
| 409 | 同一 `Idempotency-Key` 被不同 actor/vid/action 复用，或并发同键尚未提交 | `requestKey 已被不同形状的请求占用（actor/vid/action 不一致），同一 requestKey 不能复用在不同请求上` |
| 429 | 触发准入限流（同一 actor 单动作 60 次/60 s 或合计 120 次/60 s） | `操作过于频繁，请稍后再试` |
| 500 | 计数或写入异常（整个事务回滚） | `同步统计更新失败（影响行数=0），本次交互整体回滚` 等 |
| 503 | Redis 准入不可用（fail-closed，可重试） | `缓存服务暂不可用，请稍后再试` |

> 限流额度在数据库事务之前消耗，事务失败**不退还**额度；两个维度（单动作、actor 合计）不是原子的。

### curl

```bash
# 登录用户
curl -X POST "http://localhost:8080/api/videos/1/play-count" \
  -H "Idempotency-Key: 3f1c9b7e-6b1e-4b3a-9f0d-2a6c5d4e8f10" \
  -H "Authorization: Bearer <token>"

# 匿名访客
curl -X POST "http://localhost:8080/api/videos/1/play-count" \
  -H "Idempotency-Key: 3f1c9b7e-6b1e-4b3a-9f0d-2a6c5d4e8f10" \
  -H "X-Visitor-Id: 8f14e45f-ceea-467a-9b7a-0b3c9d1e2f34"
```

> **破坏性变更**：两个头是必填的，旧前端不带 `Idempotency-Key`（匿名还不带 `X-Visitor-Id`）会直接收到 400。
> 没有兼容窗口，前端需与本接口同步发布。

---

## 7.2 保存播放进度

```http
POST /api/videos/{vid}/progress?playTime={seconds}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `playTime` | Query | double | 是 | 当前播放秒数，必须有限、非负且不超过视频总时长 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `播放进度不合法` |
| 400 | `播放进度不能超过视频时长` |
| 404 | `视频不存在` |
| 500 | `用户视频关系创建失败` |
| 500 | `播放进度保存失败` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/progress?playTime=35.5" \
  -H "Authorization: Bearer <token>"
```

---

## 7.3 点赞、取消点赞、点踩或取消点踩

```http
POST /api/videos/{vid}/islike?islike={boolean}&isSet={boolean}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `islike` | Query | boolean | 是 | `true` 操作点赞，`false` 操作点踩 |
| `isSet` | Query | boolean | 是 | `true` 设置，`false` 取消 |

| islike | isSet | 操作 |
|---|---|---|
| true | true | 点赞 |
| true | false | 取消点赞 |
| false | true | 点踩 |
| false | false | 取消点踩 |

重复设置相同状态时，当前 Service 直接返回成功，不重复修改统计。

> 当前点赞和点踩状态尚未实现自动互斥：设置点赞不会自动取消已有点踩，设置点踩也不会自动取消已有点赞。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/islike?islike=true&isSet=true" \
  -H "Authorization: Bearer <token>"
```

---

## 7.4 视频投币

```http
POST /api/videos/{vid}/coin?coin={number}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `coin` | Query | byte | 是 | 只能传 `1` 或 `2` |

当前实现不允许同一用户对同一视频重复投币。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `单次只能投一个或两个硬币` |
| 400 | `硬币数量不够` |
| 400 | `用户无法对同一个视频多次投币` |
| 404 | `用户不存在` |
| 404 | `视频不存在` |

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/coin?coin=1" \
  -H "Authorization: Bearer <token>"
```

---

## 7.5 增加分享量

```http
POST /api/videos/{vid}/share
Idempotency-Key: <业务请求幂等键>
X-Visitor-Id: <客户端随机 UUID>      # 匿名必填；登录态完全忽略
Authorization: Bearer <token>        # 可选
```

权限：**可选登录**

请求头规则、Path 参数、成功响应结构（`data{counted,duplicate,reason}`）与错误码与 §7.1 **完全一致**，
只是 `action` 维度为 `SHARE`（窗口按 actor + vid + `SHARE` 单独计算，与播放互不影响）。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "counted": false,
    "duplicate": false,
    "reason": "WINDOW_SUPPRESSED"
  }
}
```

- 同一 actor 对同一视频的分享，滚动 30 分钟内最多计一次；同一 `Idempotency-Key` 在记录保留期内
  （30 天，自 `completed_at` 起算）只计一次，保留期外按新请求处理（仍受 30 分钟窗口约束）。
- 视频校验在幂等重放之前：视频下架/删除后，同一个 `Idempotency-Key` 重试返回 404。
- 计数同样走既有统计管道，`shareTimes` 是最终一致。

### curl

```bash
curl -X POST "http://localhost:8080/api/videos/1/share" \
  -H "Idempotency-Key: 7c2d4e6f-1a3b-4c5d-8e9f-0a1b2c3d4e5f" \
  -H "X-Visitor-Id: 8f14e45f-ceea-467a-9b7a-0b3c9d1e2f34"
```

---

## 7.6 收藏或取消收藏

```http
POST /api/videos/{vid}/collect?isCollect={boolean}
Authorization: Bearer <token>
```

权限：**登录**

### 参数

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `vid` | Path | integer | 是 | 已发布视频 ID |
| `isCollect` | Query | boolean | 是 | `true` 收藏，`false` 取消收藏 |

重复设置相同状态时，当前 Service 直接返回成功，不重复修改统计。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### curl

收藏：

```bash
curl -X POST "http://localhost:8080/api/videos/1/collect?isCollect=true" \
  -H "Authorization: Bearer <token>"
```

取消收藏：

```bash
curl -X POST "http://localhost:8080/api/videos/1/collect?isCollect=false" \
  -H "Authorization: Bearer <token>"
```

> 互动计数（播放/点赞/投币/收藏/分享）由业务事务写入 outbox，再经 RabbitMQ 消费更新 Redis，
> 最后由批量聚合任务异步写入 `video_status`。正常 Redis 更新使用 Lua 幂等快路径，不获取 vid
> 分布式锁；缓存缺失/重建时才进入持锁慢路径，并使用令牌门闩与刷库协调。HTTP 接口和响应不变，
> 统计仍为最终一致。详见 `docs/tutorial/video-status-lock-free-fast-path.md`。
>
> 内部行为（T12-A 候选，无 HTTP/响应变化）：重建分类只对 `process_status=0` 的候选按固定
> 256 条一批做 pipeline `EXISTS` 查询 `processed` 证据，逐 key 结果按快照顺序回填；`process_status=1`
> 不再查询 Redis。证据查询失败/条数不符/未知结果一律 fail closed，不发布半份初始化，门闩与
> 初始化协议不变。
>
> 内部行为（T12-B 候选，无 HTTP/响应变化）：重建候选改为 `load()` 同一个
> `REPEATABLE_READ` 只读事务内的 keyset 分页（`id > afterId`，每页 <= 1000 行，不用 `OFFSET`），
> 基线与所有分页共享同一份一致性快照。候选仍是当前 DTO 的 list，上界由
> `app.video-status.rebuild-max-candidates`（默认 100000）显式给出；超过上限抛
> `RetryableMessageException`，绝不返回截断快照。快照读取预算
> `rebuild-snapshot-budget-ms` 用单调时钟计量，在页间与逐条转换时检查，超预算同样失败，
> 但不会中断已发出的 SQL。两者都发生在 `initializeRedis` 之前，因此没有半份初始化，
> 门闩仍由 `finally` 用同一 token 释放。“已存在但错误”的强制重建接口仍未实现。
>
> 内部原语（T12-C1 候选，**没有新增任何入口**）：新增 `video-status-replace.lua` 与
> `RedisScriptConfig#videoStatusReplaceScript`，可在持有门闩时整体覆盖“已存在且完整”的统计 Hash。
> 它**没有 HTTP 接口、没有 Java 业务调用者、也没有注册自动调用**，本文件不新增任何请求/响应
> 字段；强制重建仍需权限、审计、并发协调、崩溃恢复与完整性校验，见
> `docs/tutorial/video-status-replace-primitive.md`。

---

# 8. 管理员视频审核接口

以下接口均要求：

```http
Authorization: Bearer <管理员或超级管理员 token>
```

## 8.1 审核视频

```http
POST /api/admin/videos/{vid}/review
Content-Type: application/json
```

权限：**管理员**

### Path 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

### 请求体

审核通过：

```json
{
  "result": "APPROVED",
  "reason": null
}
```

审核驳回：

```json
{
  "result": "REJECTED",
  "reason": "标题或投稿信息不符合要求"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `result` | string | 是 | 当前只支持 `APPROVED` 或 `REJECTED`，大小写不敏感 |
| `reason` | string | 条件必填 | `REJECTED` 时必须填写 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数不合法` |
| 400 | `审核结果只能是 APPROVED 或 REJECTED` |
| 400 | `驳回视频必须存在原因` |
| 403 | `你的权限不足` |
| 404 | `视频不存在` |
| 409 | 状态不允许流转的动态提示 |
| 409 | `视频尚无可用播放媒体，不能审核通过`（外部稿件 `video_url/video_key` 均为NULL，且 `playback-enabled` 关闭或没有线路通过 M6-A 资格判定时） |
| 409 | `视频已经由其他管理员审核，请刷新后重试` |

> M5导入的外部稿件没有本站播放对象（`video_url`/`video_key` 均为NULL）。审核服务在唯一的状态写入口阻止其进入 `APPROVED`；仍可驳回。M6-A 只有显式开启 `app.external-video-source.playback-enabled` 且同一资格判定确认至少一条可用 HLS 线路时才允许外部稿件通过；仅打开开关不等于可用。旧COS视频两列非空，审核行为不变且不查询 S2/M5 表。

### curl

```bash
curl -X POST "http://localhost:8080/api/admin/videos/1/review" \
  -H "Authorization: Bearer <管理员token>" \
  -H "Content-Type: application/json" \
  -d '{"result":"APPROVED","reason":null}'
```

---

## 8.2 查询管理员视频详情

```http
GET /api/admin/videos/{vid}
```

权限：**管理员**

该接口用于审核场景，不受公开视频审核通过条件限制。

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "vid": 1,
    "uid": 2,
    "title": "待审核视频",
    "sourceType": 1,
    "visibility": 0,
    "duration": 120.5,
    "mcId": "douga",
    "scId": "mad",
    "tags": "测试,投稿",
    "description": "视频简介",
    "coverUrl": "https://example-cos-domain/covers/1/cover.png",
    "videoUrl": "https://example-cos-domain/videos/1/video.mp4",
    "status": 0,
    "createdAt": "2026-07-12T10:00:00",
    "playTimes": 0,
    "likeTimes": 0,
    "unlikeTimes": 0,
    "commentTimes": 0,
    "coinTimes": 0,
    "shareTimes": 0,
    "collectTimes": 0,
    "danmuTimes": 0,
    "avatarUrl": "https://example-cos-domain/avatar/2.png",
    "nickname": "投稿用户",
    "videoCount": 1,
    "fansCount": 0
  }
}
```

### 常见错误

| code | message |
|---:|---|
| 400 | `视频参数无效` |
| 403 | `你的权限不足` |
| 404 | `视频不存在` |
| 404 | `视频状态为空` |
| 500 | `视频作者数据异常` |

### curl

```bash
curl "http://localhost:8080/api/admin/videos/1" \
  -H "Authorization: Bearer <管理员token>"
```

---

## 8.3 分页查询指定状态的视频

```http
GET /api/admin/videos/page?page={page}&status={status}&quantity={quantity}
```

权限：**管理员**

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `page` | integer | 是 | 无 | 页码，从 1 开始 |
| `status` | byte | 否 | `1` | 视频审核状态 |
| `quantity` | integer | 否 | `10` | 每页数量 |

> Controller 当前将 `page` 声明为必填参数，因此 HTTP 请求不能省略。
>
> HTTP 请求省略 `status` 时，Controller 会传入默认值 `1`。
>
> 当前未校验 `page`、`quantity` 必须为正数，也未限制最大 `quantity`。`status` 约定为 `0`～`3`，但当前没有范围校验。查询结果按 `created_at` 升序排列。

### 状态值

| status | 说明 |
|---:|---|
| 0 | 待审核 |
| 1 | 审核通过 |
| 2 | 审核驳回 |
| 3 | 违规删除 |

### 成功响应

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "vid": 1,
      "uid": 2,
      "title": "待审核视频",
      "coverUrl": "https://example-cos-domain/covers/1/cover.png",
      "duration": 120.5,
      "createdAt": "2026-07-12T10:00:00"
    }
  ]
}
```

### curl

```bash
curl "http://localhost:8080/api/admin/videos/page?page=1&status=0&quantity=10" \
  -H "Authorization: Bearer <管理员token>"
```

---

## 8.4 查询视频统计链路指标与积压告警

```http
GET /api/admin/video-status/metrics
```

权限：**管理员**（路径经过登录拦截器 + 管理员拦截器，方法另有 `@AdminOnly`）。

该接口返回**最近一次采样的缓存快照**，不在请求线程查 MySQL，也不写 Redis。响应分为两部分，
必须分开理解：

- `backlog`：来自共享 MySQL 的**全局**积压采样值，可能 `stale`；
- `instanceTelemetry`：**当前实例**的 operation 计数与耗时，不是全局值，不能与 backlog 相加，
  进程重启后归零。

### 采样与状态说明

| 字段 / 状态 | 说明 |
|---|---|
| `backlog.state=OK` | 本次采样成功 |
| `backlog.state=STALE` | 本次失败，或快照超过 45 秒没有新尝试；保留上次成功数值，`stale=true` |
| `backlog.state=UNKNOWN` | 从未成功采样；计数为 `null`，不是 0 |
| `backlog.state=DEGRADED` | 缺少 T05 观测索引，主动跳过计数并给诊断；先执行迁移 |
| `countCapped` | `true` 表示 `count >= 10001`，是封顶值而不是精确值 |
| `oldestAgeMillis` | 该状态最老一条的年龄（毫秒）；`null` 表示没有待处理行或未知 |
| `sampledAtEpochMillis` | 最近一次采样（成功、失败或 DEGRADED）的时间 |
| `lastSuccessAtEpochMillis` | 最近一次成功采样的时间；从未成功时为 `null` |

`backlog.backlog[]` 覆盖 Outbox 状态 `0/1/2/3` 和消费事件状态 `0/1/3`；`outbox SENT(2)` 也会返回，
但不参与 `PENDING_TOO_OLD`。`schedulerHeartbeats[]` 只覆盖 `RELAY_TICK`、`FLUSH_TICK`、
`RECOVERY_TICK` 三个关键调度心跳，状态为 `OK / STALLED / UNKNOWN`。

测试告警阈值为：最老待处理（状态 0/1）超过 120 秒、关键调度心跳超过 30 秒；生产阈值由后续
容量/演练任务确认。告警只出现在日志和本 HTTP 快照中，**没有外部告警投递**。

### 成功响应（节选）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "scopeNote": "instanceTelemetry 仅代表本实例 operation 计数；backlog 为共享 MySQL 的全局积压采样值。两者不可相加；stale=true 时数字来自上次成功采样。",
    "backlog": {
      "state": "OK",
      "stale": false,
      "sampledAtEpochMillis": 1761080000000,
      "lastSuccessAtEpochMillis": 1761080000000,
      "lastError": null,
      "diagnostics": [],
      "backlog": [
        {
          "source": "OUTBOX",
          "status": 0,
          "statusLabel": "PENDING",
          "count": 3,
          "countCapped": false,
          "oldestAgeMillis": 1500
        }
      ],
      "schedulerHeartbeats": [
        {
          "operation": "FLUSH_TICK",
          "roleEnabledOnThisInstance": true,
          "lastHeartbeatAtEpochMillis": 1761079999000,
          "ageMillis": 1000,
          "state": "OK"
        }
      ],
      "alarms": []
    },
    "instanceTelemetry": {
      "sampledAtEpochMillis": 1761080000100,
      "operations": [
        {
          "operation": "PUBLISH",
          "outcomes": {"SUCCESS": 12, "FAILURE": 0, "DEFERRED": 0, "TIMEOUT": 0},
          "recorded": 12,
          "elapsedNanosTotal": 24000000,
          "elapsedNanosMax": 5000000,
          "heartbeatCount": 30,
          "lastHeartbeatAtEpochMillis": 1761079999500,
          "lastHeartbeatAgeMillis": 600
        }
      ],
      "endToEnd": {
        "samples": 10,
        "unknown": 0,
        "avgMillis": 850,
        "maxMillis": 2100,
        "lastMillis": 700
      }
    }
  }
}
```

> 示例只展示一条 `backlog` 和一条 `operations`；实际响应包含全部受跟踪状态与固定 10 个
> operation，没有样本的耗时/端到端字段为 `null`。

### 常见错误

| 场景 | 结果 |
|---|---|
| 未登录 / 非管理员 | 由既有登录、管理员拦截器返回相应业务错误 |
| 老库未执行 T05 索引迁移 | `backlog.state=DEGRADED`，`diagnostics` 指向迁移脚本，不执行全表扫描 |
| 采样失败且从未成功 | `backlog.state=UNKNOWN`，计数为 `null` |

### curl

```bash
curl "http://localhost:8080/api/admin/video-status/metrics" \
  -H "Authorization: Bearer <管理员token>"
```

---

## 8.5 查询重建门闩停留时间

```http
GET /api/admin/video-status/gate?vid={vid}
```

权限：**管理员**。

### Query 参数

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `vid` | integer | 是 | 视频 ID，必须大于 0 |

重建门闩是**无 TTL** 的字符串；为观测给门闩加自动过期会破坏重建安全性，因此本接口只读，
不写、不删、不设置或刷新 TTL。当前写者使用 `<epochMillis>-<uuid>` 格式，读侧据此计算停留时间；
历史纯 UUID 门闩无法得知创建时间，明确返回“未知”。

| tokenFormat | 含义 | `tokenAgeMillis` |
|---|---|---|
| `ABSENT` | 当前没有门闩，`present=false` | `null` |
| `EPOCH_MILLIS_TOKEN` | 新格式，可计算停留时间 | 毫秒 |
| `LEGACY_UNKNOWN` | 老格式纯 UUID，年龄未知 | `null` |

### 成功响应（存在新格式门闩）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "vid": 123,
    "present": true,
    "tokenFormat": "EPOCH_MILLIS_TOKEN",
    "tokenAgeMillis": 4500,
    "note": "门闩无 TTL；崩溃后由下一位持锁者接管，不做自动过期"
  }
}
```

### 成功响应（无门闩）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "vid": 123,
    "present": false,
    "tokenFormat": "ABSENT",
    "tokenAgeMillis": null,
    "note": "当前没有重建门闩"
  }
}
```

### 常见错误

| 场景 | 结果 |
|---|---|
| `vid` 缺失或不是合法整数 | Spring 参数绑定错误 |
| `vid <= 0` | 业务 `code=400`，`message="vid 不合法"` |
| 未登录 / 非管理员 | 由既有登录、管理员拦截器返回相应业务错误 |

### curl

```bash
curl "http://localhost:8080/api/admin/video-status/gate?vid=123" \
  -H "Authorization: Bearer <管理员token>"
```

---

## 8.6 查询单个 Outbox 事件的安全状态

```http
GET /api/admin/video-status/outbox/{eventId}
```

权限：**管理员**（`/api/admin/**` + 方法级 `@AdminOnly`）。

该接口只读，返回安全状态：`status/statusName`、`retryCount`、`leaseRecoveryCount`、
`confirmWritebackCount`、`createdAt/nextRetryAt/sendingAt/sentAt`、
`lastErrorPresent`/`lastErrorHint`、`consumedProcessStatus`、`replayEligible` 与
`replayBlockedReason`。

**不返回** `payload`、`leaseToken`、`last_error` 原文。历史 `last_error` 可能混入 payload
片段或凭据，定位只能给布尔值和固定提示；需要原文时由管理员走受控查库。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "eventId": "evt-123",
    "vid": 123,
    "eventType": "LIKE",
    "status": 3,
    "statusName": "FAILED",
    "retryCount": 10,
    "leaseRecoveryCount": 0,
    "confirmWritebackCount": 0,
    "lastErrorPresent": true,
    "lastErrorHint": "失败原因原文需受控查库，不在 HTTP 返回",
    "consumedProcessStatus": null,
    "replayEligible": false,
    "replayBlockedReason": "status 不是 FAILED…"
  }
}
```

常见错误：`404` eventId 不存在；`400` eventId 不合法；未登录/非管理员由既有拦截器处理。

---

## 8.7 受审计重放单个 Outbox FAILED 事件

```http
POST /api/admin/video-status/outbox/{eventId}/replay
Content-Type: application/json
```

权限：**管理员**。请求体只有幂等键与原因，**没有 operator 字段**：

```json
{
  "operationId": "OPS-2026-000123",
  "reason": "publish confirm 超时耗尽预算，根因已修复，需重投原事件"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `operationId` | string | 是 | 工单/幂等键，≤64；同 operationId 的 eventId/action/operator/reason 全部相同才返回 duplicate |
| `reason` | string | 是 | 操作原因，≤1000；换 reason 复用旧 operationId 会被 409 拒绝 |

执行条件（全部满足）：`status=3 (FAILED)`；payload 可解析且 `event.validate()` 通过；
payload 的 eventId/vid/type 与数据库行一致；没有任何消费账本行（0/1/2/3 都拒绝）；
`created_at` 与 payload `occurredAt` 都在安全窗口内；payload `occurredAt` 未超过固定的 30 天普通重放上界
（T14-S2，超出时 `409` 并给原因码 `EVENT_OVER_NORMAL_REPLAY_WINDOW`）；operationId 未被冲突占用。
默认 7 天窗口比 30 天更严，所以默认配置下超龄事件仍然只看到原来的 occurredAt 窗口说明。

成功时在同一 MySQL 事务内把状态重置为 PENDING、三份预算归零，并写 `COMPLETED` 审计；
before/after 元数据（含 `confirmWritebackCountBefore/After`）持久化在审计记录中。
响应关键字段：`replayApplied`、`duplicate`、`retryCountBefore/After`、
`leaseRecoveryCountBefore/After`、`confirmWritebackCountBefore/After`、
`statusNameBefore/After`，以及查询时的 `current*` 实时状态。

常见错误：`400` 参数不合法；`401` 缺少登录操作人；`404` eventId 不存在；
`409` 状态/并发/幂等冲突；`422` payload 非法或与行不一致。

```bash
curl -X POST "http://localhost:8080/api/admin/video-status/outbox/evt-123/replay" \
  -H "Authorization: Bearer <管理员token>" \
  -H "Content-Type: application/json" \
  -d '{"operationId":"OPS-2026-000123","reason":"根因已修复，重投原事件"}'
```

> T10 之前只允许这种单事件、可审计、可拒绝的重放；不要用裸 SQL 改状态，不要新建 eventId。

---

## 8.8 预览 status0 补偿候选（T09，只读）

```http
GET /api/admin/video-status/status0-compensation/preview?limit={limit}&afterId={afterId}
```

权限：**管理员**（`/api/admin/**` + 方法级 `@AdminOnly`）。

### Query 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `limit` | integer | 否 | `10` | 返回条数，范围 `1`～`100`；越界返回业务 `400` |
| `afterId` | long | 否 | `0` | keyset 起点，只返回 `id > afterId` 的 RECEIVED 行；必须 `>= 0` |

### 响应字段

`data` 为 `PreviewResult`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `overSafeWindow` | long | 超过安全窗口的 RECEIVED 数量；受扫描上限约束 |
| `overSafeWindowLowerBound` | boolean | `true` 表示计数达到上限，只能声称“至少这么多” |
| `compensationEnabled` | boolean | 当前实例总开关；默认 `false` |
| `items` | array | 预览行 |

每个 `items[]` 包含：`eventId`、`vid`、`eventType`、`processStatus`、`consumedAgeSeconds`、
`compensationAttempts`、`lastAttemptAt`、`nextAttemptAt`、`leaseUntil`、`withinGrace`、
`withinSafeWindow`、`leaseFree`、`cooldownElapsed`、`attemptsRemaining`、`eligible`、
`evidenceStatus`、`decisionGate`、`refusalCode`。

- `evidenceStatus` 固定为 `EVIDENCE_UNKNOWN`：preview 不访问 Redis，DB 行本身不能证明 current 是否已应用。
- `decisionGate` 为 `PREVIEW_ONLY_MASTER_SWITCH_OFF`、`BLOCKED_INTEGRITY_NOT_CONFIRMED`、
  `PROVEN_PROCESSED_EVIDENCE_ONLY` 或 `ALLOW_UNPROVEN_REPLAY`。
- 静默期/租约/冷却比较使用同一条 SQL 返回的数据库 `NOW(3)`，不与 JVM 时钟混用。
- `withinSafeWindow` 与 `refusalCode` 来自与 `runOnce` **同一个**年龄/内容判定：`withinSafeWindow` 表示
  DB 年龄与 payload `occurredAt` 都落在可配置安全窗口内（不再是只看 DB 年龄），`refusalCode` 为
  `null` 时年龄/内容判定放行。可能的值：`STATUS0_OVER_SAFE_WINDOW`（可配置窗口）、
  `EVENT_OVER_NORMAL_REPLAY_WINDOW`（T14-S2 固定 30 天普通重放上界）、
  `STATUS0_PAYLOAD_INVALID` / `STATUS0_EVENT_INVALID` / `STATUS0_EVENT_MISMATCH`。
  Redis `processed` 证据类原因**不会**出现在这里（preview 不读 Redis）。
- payload 无法解析、`validate()` 失败或与账本行（`eventId`/`vid`/`delta`/`eventType`）不一致时，
  `withinSafeWindow` 为 `false`（此时 `occurredAt` 无从证明），`refusalCode` 只可能是上面三个固定内容码之一，
  `eligible` 为 `false`。服务**不会**回显原始异常文本；未识别的内容类消息一律回落 `STATUS0_EVENT_INVALID`。
- 指纹不符（写侧 `STATUS0_FINGERPRINT_MISMATCH`）**不在** preview 判定内：它由 `runOnce` 在读取 Redis
  证据之前单独校验，所以 `eligible=true` 仍可能被写侧以指纹原因拒绝（同理，写侧的 `processed` 证据类原因
  也不可见）。
- `eligible` 同时要求静默期/租约/冷却/预算与上述年龄/内容判定全部通过；本接口仍**不**访问 Redis，
  所以 `eligible=true` 仍不代表写侧一定发送。

### 成功响应（节选）

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "overSafeWindow": 0,
    "overSafeWindowLowerBound": false,
    "compensationEnabled": false,
    "items": [
      {
        "eventId": "evt-123",
        "vid": 123,
        "eventType": "LIKE",
        "processStatus": 0,
        "consumedAgeSeconds": 120,
        "compensationAttempts": 0,
        "lastAttemptAt": "2026-10-22T10:00:00",
        "nextAttemptAt": null,
        "leaseUntil": null,
        "withinGrace": true,
        "withinSafeWindow": true,
        "leaseFree": true,
        "cooldownElapsed": true,
        "attemptsRemaining": true,
        "eligible": true,
        "evidenceStatus": "EVIDENCE_UNKNOWN",
        "decisionGate": "PREVIEW_ONLY_MASTER_SWITCH_OFF",
        "refusalCode": null
      }
    ]
  }
}
```

### 常见错误

| 场景 | 结果 |
|---|---|
| `limit < 1` 或 `limit > 100` | 业务 `code=400`，提示 `limit 必须在 1 到 100 之间` |
| `afterId < 0` | 业务 `code=400`，提示 `afterId 必须 >= 0` |
| 未登录 / 非管理员 | 由既有登录、管理员拦截器返回相应业务错误 |

```bash
curl "http://localhost:8080/api/admin/video-status/status0-compensation/preview?limit=10&afterId=0" \
  -H "Authorization: Bearer <管理员token>"
```
---

# 9. 推荐联调顺序

## 9.1 用户功能

1. `POST /api/auth/register` 注册。
2. `POST /api/auth/login` 登录。
3. 保存响应中的 `data.token`。
4. `GET /api/users/me` 验证 Token。
5. `POST /api/users/me` 修改资料。
6. `POST /api/users/me/avatar` 修改头像。
7. `POST /api/users/me/background` 修改主页背景图。
8. `POST /api/users/me/password` 修改密码（旧 Token 随之失效）。
9. 使用新密码重新登录。
10. `GET /api/users/{uid}` 查询其他用户。
11. `POST /api/users/{uid}/subscribe` 测试关注和取消关注。
12. `POST /api/auth/logout` 退出，确认旧 Token 立即失效。

## 9.2 视频投稿

1. 登录并取得 Token。
2. `GET /api/category` 获取可用分区，选择 `mcId` / `scId`。
3. 调用 `POST /api/videos/upload-url` 获取临时上传凭证和 `tempKey`。
4. 使用临时凭证将视频上传到 COS 的 `tempKey`。
5. 调用 `POST /api/videos/cover` 上传封面并取得临时封面 Key。
6. 调用 `POST /api/videos` 完成投稿。
7. 使用管理员接口查询待审核视频。
8. 使用管理员接口审核通过。
9. 调用 `GET /api/videos/{vid}` 查询公开视频。
10. 调用 `GET /api/videos/feed` 验证 Feed（可带 `mcId` / `scId`）。

## 9.3 视频互动

1. `POST /api/videos/{vid}/play-count` 增加播放量（必填 `Idempotency-Key`，匿名另需 `X-Visitor-Id`）。
2. `POST /api/videos/{vid}/progress` 保存登录用户播放进度。
3. `POST /api/videos/{vid}/islike` 测试点赞、取消点赞、点踩和取消点踩。
4. `POST /api/videos/{vid}/coin` 测试投币。
5. `POST /api/videos/{vid}/share` 增加分享量（请求头与 play-count 相同）。
6. `POST /api/videos/{vid}/collect` 测试收藏和取消收藏。
7. 等待聚合任务落库后，再次查询视频详情，核对统计和当前用户互动状态。

## 9.4 视频删除

1. 使用视频作者 Token 调用 `POST /api/videos/{vid}/delete`。
2. 确认公开详情和 Feed 不再返回该视频。
3. 确认数据库 `video.deleted_at` 已写入。
4. 确认 COS 视频和封面在事务提交后被清理。
5. 使用非作者 Token 删除视频，预期返回 `403`。
6. 重复或并发删除，预期返回 `404` 或 `409`。

---

# 10. 当前实现注意事项

1. 当前密码仍以明文形式保存和比对，仅适合学习阶段；正式环境应使用 BCrypt 等密码哈希算法。
2. 用户角色：`0` 普通用户、`1` 管理员、`2` 超级管理员。
3. 用户状态：`0` 正常、`1` 封禁、`2` 注销。
4. 退出登录已支持服务端 Token 撤销：`jti` 写入 Redis 黑名单后旧 Token 立即失效；修改密码会提升
   `token_version`，同样使此前所有 Token 失效。
5. 视频投稿依赖腾讯云 COS，临时文件数据库记录和 COS 对象都必须真实存在。
6. 视频详情和公开 Feed 只处理审核通过且未逻辑删除的视频。
7. 播放量和分享量暂未实现用户、IP 或时间窗口去重。
8. 互动统计经 Redis + RabbitMQ 异步聚合后才落库，读取到的统计值可能有短暂延迟。
9. 管理员分页接口的 `page` 当前为必填参数，`status` 的 Controller 默认值为 `1`。
10. 全局异常处理器会把未单独处理的异常包装为 `code = 500`，具体提示以当前实现为准。
11. 登录失败限流与上传凭证限流基于 Redis 固定窗口实现，触发时返回 `429`。

## T10 失败事件诊断、确认已阅、只读对账与 status3 受控重放（T10 未完成）

> 第一阶段（只读诊断/受审计 `acknowledge`/只读对账）已合入主分支 `6fc5e7f`。
> T10-B1（consumed status3 单事件 proven-only 受控重放，见 10.5）为**候选实现，等待父代理验收**；
> **DLQ 摄入/处置、批量操作、刷库型 status3 修复、自动修复/收敛仍未实现**。T10 整项仍未完成，
> 以下也不构成生产验收。
>
> 使用失败列表前必须先执行 `database/migrations/t10-video-status-failure-admin-indexes.sql`；
> 使用单视频对账前必须先执行 `t10-video-status-reconciliation-indexes.sql`。两个迁移的索引都已
> 镜像到 `database/feibijiubi.sql`。

所有接口都在 `/api/admin/**` 下并标注 `@AdminOnly`，未登录/非管理员由既有拦截器拒绝。
响应只返回安全字段，不返回 payload、leaseToken 或 `last_error` 原文。

### 10.1 失败事件分页

`GET /api/admin/video-status/failures?source=OUTBOX|CONSUMED&afterId=0&limit=20`

- `source`：默认 `OUTBOX`，大小写不敏感，只接受 `OUTBOX` / `CONSUMED`。
  `OUTBOX` 只列 `status=3 (FAILED)`；`CONSUMED` 只列 `process_status=0 (RECEIVED)` 和
  `process_status=3 (REPAIR_REQUIRED)`，其中 status0 仅用于诊断。
- `afterId`：键集分页游标，取上一页最后一条 `id`，必须 `>= 0`，默认 `0`。
- `limit`：`1..100`，默认 `20`。服务端最多读取 `limit+1` 行判断 `hasMore`，返回最多 `limit` 条。
- 响应 `Page`：`source`、`afterId`、`limit`、`hasMore`、`nextAfterId`、`items`。
  `nextAfterId` 仅在 `hasMore=true` 时为最后一条的 `id`，否则为 `null`。
- `FailureItem` 安全字段：`source`、`id`、`eventId`、`vid`、`eventType`、`status`、
  `statusName`、`reasonCode`、`lastErrorPresent`、`lastErrorHint`、`retryCount`、
  `consumerRetryCount`、`leaseRecoveryCount`、`confirmWritebackCount`、`createdAt`、
  `consumedAt`、`lastAttemptAt`、`redisAppliedAt`、`flushedAt`。
- 缺少精确 `(status,id)`（OUTBOX）或 `(process_status,id)`（CONSUMED）索引时返回 HTTP 503、
  `code=503`，并明确提示迁移文件；此时**不执行列表扫描**。索引存在但顺序不符同样拒绝。
- `reasonCode` 只是安全原因码；Outbox 的预算计数（如 `retry_count`）可能是历史累计，只能作为
  诊断参考，不能证明本次失败的真实原因。

### 10.2 失败详情与预检查

- `GET /api/admin/video-status/failures/{eventId}?source=OUTBOX|CONSUMED`：返回安全 `item`、
  `precheck` 与说明。
- `GET /api/admin/video-status/failures/{eventId}/precheck?source=OUTBOX|CONSUMED`：只返回预检查。
- `eventId` 必填、长度 `<=64`；目标不是可诊断状态时返回 404。
- OUTBOX 预检查固定返回 `decision=REQUIRES_CONTROLLED_REPAIR`，原因码为
  `OUTBOX_CONSUMED_LEDGER_EXISTS` 或 `OUTBOX_REQUIRES_CONTROLLED_REPAIR`，`canAcknowledge=true`。
- CONSUMED status3 固定返回 `REQUIRES_CONTROLLED_REPAIR`；`redis_applied_at` 为空不证明未应用，
  超龄也不等于安全，原因码为 `CONSUMED_REDIS_APPLIED_EVIDENCE_MISSING`、
  `CONSUMED_OVER_SAFE_WINDOW`、二者组合或 `CONSUMED_REQUIRES_CONTROLLED_REPAIR`。
- CONSUMED status0 返回 `DIAGNOSTIC_ONLY_EVIDENCE_UNKNOWN`，`canAcknowledge=false`，
  不能领取、重发或转 status3。
- 预检查字段：`source`、`eventId`、`decision`、`reasonCode`、
  `redisAppliedEvidencePresent`、`withinSafeWindow`、`consumedAgeSeconds`、
  `canAcknowledge`、`replay`、`note`。预检查永远不会返回 `SAFE`。`replay` 仅在 status3 时非空，
  与 `POST .../replay` 共用同一判定（见 10.5）。

### 10.3 受审计确认已阅（acknowledge）

`POST /api/admin/video-status/failures/{eventId}/acknowledge?source=OUTBOX|CONSUMED`

```json
{
  "operationId": "ticket-20261022-001",
  "reason": "已核对业务事实，保留证据并确认已阅"
}
```

- `operationId` 必填、`<=64`；`reason` 必填、`<=1000`；操作人只取登录态 `currentUserId`，
  请求体不能伪造。
- `acknowledge` **不是忽略或修复**：只允许 OUTBOX FAILED（status3）与 consumed status3
  （REPAIR_REQUIRED）。consumed status0/1/2 或其它目标返回 409，且不写审计。
- 只写 `video_status_consumption_repair_log`：`action=ACKNOWLEDGE_FAILURE`、
  `operation_status=1`，审计元数据 `T10_ACKNOWLEDGE_META_V1` 记录 source 与 before/after；
  **before 必须等于 after**，证明未修改目标状态。不修改计数/Redis，不删除 payload 或失败证据，
  不创建新 eventId，也不是重放或对账。
- 整个确认在一个事务中；审计写入失败或唯一键冲突整体回滚。
- 相同 operationId、相同目标/来源/动作/操作人/原因重复提交返回成功且
  `duplicate=true`、`acknowledgedApplied=false`。同目标并发请求在目标行锁后再次读取审计，
  可被吸收为 duplicate；不同目标，或来源/动作/操作人/原因不一致的 operationId 复用返回 409。
- 响应字段：`operationId`、`action`、`source`、`eventId`、`operator`、`operationStatus`、
  `completedAt`、`duplicate`、`acknowledgedApplied`、`statusBefore`、`statusAfter`、
  `processStatusBefore`、`processStatusAfter`、`message`。调用方应核对 before==after。

### 10.4 单视频只读对账

`GET /api/admin/video-status/reconciliation?vid=<正整数>&limit=100`

- `vid` 必填且必须为正整数；非法返回 400。
- `limit` 为 `1..1000`，默认 `100`，非法返回 400。所有账本/事件/关系查询最多读取
  `limit+1` 行。
- 响应顶层字段：`vid`、`limit`、`mode`（固定 `READ_ONLY_DIAGNOSTIC`）、`readOnly=true`、
  `writePerformed=false`、`automaticRepair=false`、`observationBoundary`
  （固定 `CONCURRENT_OBSERVATION_NOT_ATOMIC`）、`incomplete`、`uncertainties`、`verdict`
  （固定 `NO_SAFE_CONCLUSION_AUTOMATIC_REPAIR_DISABLED`）、`db`、`ledger`、`eventProjection`、
  `relations`、`redis`、`note`。
- `db`：`video_status` 的 `rowPresent` 与八项 `Long` 计数。统计行不存在时计数为 `null` 并加入
  `DB_STATUS_ROW_MISSING`，**不按 0 处理**。
- `ledger`：`cap=limit+1`；status0/1/2/3 四个状态分别独立封顶计数，并有独立
  `status0Capped`…`status3Capped`；任一状态达到 cap 时该状态只是下界，汇总 `truncated=true`。
  不能把四个状态相加当作历史账本总量或 DB 真值。
- `eventProjection`：`requestedLimit`、`rowCount`、`truncated`、`indexAvailable`、
  `deltaByType`（BigInteger 汇总，包含全部安全事件类型键）、`unknownTypeCount`、
  `nullDeltaCount`、`unknownTypeDelta`、`minEventId`、`maxEventId`。最多读取 `limit+1` 行，
  第 `limit+1` 行只用于判断截断，不进入汇总；截断时只是窗口下界。
  缺 `(vid,id)` 索引时只跳过该昂贵投影：`indexAvailable=false`、窗口为空、
  `uncertainties` 含 `INDEX_MISSING`、`incomplete=true`；其它有界 SQL 仍执行。
- `relations`：`sampled`、`truncated`、`lowerBound`、`usableForConclusion`、`sampledRows`、
  `likedRows`、`unlikedRows`、`coinSum`、`collectRows`。达到 cap 即截断，数值只是下界且
  `usableForConclusion=false`。
- `redis`：`status` 为 `SAMPLED`、`NOT_SAMPLED_OR_MISSING`、`INCOMPLETE_FIELDS`、
  `INVALID_COUNTER` 或 `READ_FAILED`，并返回八项计数与 `invalidFields`。只有 `SAMPLED` 时计数
  非空；字段缺失、非 canonical 非负十进制 Long、溢出或读取异常时计数统一为 `null`，
  **绝不伪造 0**。Redis 读取异常被捕获为 `READ_FAILED` + `REDIS_READ_FAILED` 不确定项，
  接口仍返回诊断结果。
- `incomplete=true` 的常见原因：DB 统计行缺失、任一面截断、索引缺失、未知事件类型/null delta、
  关系不可用或 Redis 非 `SAMPLED`。
- 成功响应不代表对账通过或已收敛：DB、账本、事件窗口、关系真值和 Redis 是分别读取的
  非原子观测；本接口不写 MySQL/Redis、不自动修复、不创建/重放事件。未处理的数据库异常按
  全局规则包装为 HTTP 500；不存在的索引不是 500，而是上述 incomplete 诊断。

### 10.5 consumed status3 受控重放（T10-B1，候选）

> 候选实现，等待父代理验收；T10 整项仍未完成。DLQ、批量处置、刷库型 status3 修复与自动修复
> 均不在本接口范围内。完整协议与拒绝矩阵见 `docs/tutorial/video-status-status3-replay.md`。

`POST /api/admin/video-status/failures/{eventId}/replay?source=CONSUMED`

```json
{
  "operationId": "ticket-20261023-007",
  "reason": "已核对 processed 幂等证据仍在，受控重放一次"
}
```

- `source` 只接受 `CONSUMED`（大小写不敏感）；`OUTBOX` 返回 400，并提示改用
  `POST /api/admin/video-status/outbox/{eventId}/replay`。
- `eventId` 必填、长度 `<=64`；`operationId` 必填、`<=64`；`reason` 必填、`<=1000`；
  请求体**没有** operator / eventId / vid / payload 字段，操作人只取登录态 `currentUserId`
  （缺失返回 401，无法由请求体伪造），事件内容只能取数据库中的原 payload。
- 只处理 `process_status=3 (REPAIR_REQUIRED)` 且 `redis_applied_at IS NULL` 的消费账本行。
  必须同时满足：consumed 年龄（DB 时钟 `TIMESTAMPDIFF`）与 payload `occurredAt`（JVM 时钟，
  未来容忍 300s）都在 `consumer-recovery-auto-replay-max-age-seconds` 内；payload `occurredAt`
  未超过固定的 30 天普通重放上界（T14-S2）；同一 eventId 的
  Outbox 行存在且为 `2 SENT`；Outbox payload 可解析、`validate()` 通过、eventId/vid/type/delta
  与账本一致、语义指纹等于账本 `payload_hash`；processed 幂等 Key 的剩余 TTL 不小于
  `MQ 最大重试退避 + max(outbox 租约, 补偿租约, confirm 超时)`。
- 拒绝语义：目标非 status3、刷库型 status3（`redis_applied_at` 非空）、超龄、Outbox 缺失或非
  SENT、processed 缺失/无 TTL/TTL 不足/读取异常、重放预算耗尽均返回 409；payload 非法或身份
  不一致返回 422；目标行不存在返回 404。所有拒绝都在写之前发生，不修改
  `process_status`/Outbox/计数/Redis，也不写审计。`reasonCode` 见：
  `CONSUMED_TARGET_NOT_REPAIR_REQUIRED`、`CONSUMED_STATUS3_FLUSH_ORIGIN_NOT_REPLAYABLE`、
  `CONSUMED_OVER_SAFE_WINDOW`、`CONSUMED_OUTBOX_LEDGER_MISSING`、`CONSUMED_OUTBOX_NOT_SENT`、
  `CONSUMED_PAYLOAD_INVALID`、`CONSUMED_EVENT_INVALID`、`CONSUMED_EVENT_MISMATCH`、
  `CONSUMED_FINGERPRINT_MISMATCH`、`CONSUMED_PROCESSED_EVIDENCE_MISSING`、
  `CONSUMED_PROCESSED_TTL_UNKNOWN`、`CONSUMED_PROCESSED_TTL_INSUFFICIENT`、
  `CONSUMED_EVIDENCE_CHECK_FAILED`、`CONSUMED_REPLAY_BUDGET_EXHAUSTED`、
  `EVENT_OVER_NORMAL_REPLAY_WINDOW`（T14-S2 固定 30 天上界；默认 7 天窗口更严，因此默认配置下
  超龄事件仍返回 `CONSUMED_OVER_SAFE_WINDOW`）（出现在响应 message 与
  预检查 `replay.refusalCode` 中）。
- 通过后在**同一 MySQL 事务**内完成三件事：消费账本 `3 REPAIR_REQUIRED → 0 RECEIVED`
  （CAS：`process_status=3 AND redis_applied_at IS NULL AND payload_hash=?`，影响行数必须为 1）、
  Outbox `2 SENT → 0 PENDING`（清 `next_retry_at`/`sending_at`/`lease_token`，影响行数必须为 1）、
  写入 `COMPLETED` 审计（`action=REPLAY_CONSUMED_TO_RECEIVED`，元数据
  `T10_STATUS3_REPLAY_META_V1`）。任一步失败整体回滚。
- 投递**不在请求线程**：事务提交后由既有 Outbox relay 领取、publisher confirm、失败退避与
  租约恢复投递同一 eventId/同一 payload；消费者判 `DUPLICATE` 后推进到 `1`，再由批量落库推进到
  `2 FLUSHED`。响应 `deliveryMode=OUTBOX_PENDING_RELAY` 明示这一点；relay 未启用时事件停在
  PENDING 是可继续领取的可见状态。
- 不改 payload、不新建 eventId、不清零 `consumer_retry_count`、不刷新 `consumed_at`、
  不删除 `last_error`、不清零 Outbox 三份历史预算。
- 三份 Outbox 预算（`retry_count`/`lease_recovery_count`/`confirm_writeback_count`，各 10 次上限）
  保留为历史失败证据，因此**临近耗尽时重放后的这次投递只要再失败一次就会快速进入 FAILED**
  （发布预算耗尽沿用当次发布失败原因作 `last_error`，另两份为 `lease recovery budget exhausted` /
  `confirm writeback budget exhausted`）。此时没有安全自动兜底：**不能**改用 T07 的
  `POST /api/admin/video-status/outbox/{eventId}/replay`（该工具对任何已存在消费账本的 eventId
  一律 409，而本接口刚把账本从 3 改成 0），**不能**清零预算、直接改状态或换 eventId 重灌。
  正确动作是停下诊断根因（发布层/confirm 回写/租约接管/刷库），修复后再按后续块处置。
- `status3-replay-max-attempts`（默认 3，启动期校验 1..100）限制同一 eventId 的人工重放次数，
  计数查询按 `idx_video_status_repair_event(event_id,id)` 有界（`LIMIT maxAttempts+1`）。
- 响应字段：`operationId`、`action`、`source`、`eventId`、`vid`、`eventType`、`operator`、
  `operationStatus`、`completedAt`、`replayApplied`、`duplicate`、`processStatusBefore`、
  `processStatusAfter`、`consumerRetryCountBefore`、`consumerRetryCountAfter`、
  `outboxStatusNameBefore`、`outboxStatusNameAfter`、`consumedAgeSeconds`、`replayAttemptNo`、
  `processedTtlSecondsAtReplay`、`deliveryMode`、`currentProcessStatusName`、
  `currentConsumerRetryCount`、`currentOutboxStatusName`、`message`。
  不含 payload、lease token 或 `last_error` 原文。
- 幂等：相同 `operationId` + 相同 eventId/动作/操作人/原因重复提交返回 `duplicate=true`、
  `replayApplied=false`，before/after 从审计元数据原样重放；operationId 被其它动作/目标/原因
  复用时返回 409，元数据不完整同样 409（不猜测）。

预检查扩展：`GET /api/admin/video-status/failures/{eventId}/precheck?source=CONSUMED`
对 status3 额外返回 `replay` 子结构（`decision`、`eligible`、`refusalCode`、
`redisAppliedEvidencePresent`、`withinSafeWindow`、`consumedAgeSeconds`、`outboxStatus`、
`processedTtlSeconds`、`replayAttempts`、`maxReplayAttempts`），与 POST 共用同一判定；
`redisAppliedEvidencePresent` 只表示 `redis_applied_at` 是否存在（存在即拒绝），所以
`eligible=true` 时它必为 `false`，不是放行条件；
status0 的 `replay` 为 `null`，`acknowledge`/列表行为不变。

### 10.6 交互请求幂等与 30 分钟滚动窗口（T15 第二阶段：已接线，仍为候选、未验收）

唯一键冲突后若无法读到已提交请求，返回 HTTP/code **500**（`交互计数失败，请稍后再试`），
不将无赢家的数据库冲突误报为客户端 409；本次事务整体回滚。已有请求的形状冲突仍为 409。

> 本节描述**当前代码**：`POST /api/videos/{vid}/play-count` 与 `/share` 已接入请求幂等 + 滚动 30 分钟窗口 +
> Redis 准入，返回 `data{counted,duplicate,reason}`，对外契约见 §7.1 / §7.5。T15 仍是候选、未上线验收，
> 本节也不是生产防刷验收。

已接线（`VideoInteractionAdmissionService#record` 是唯一编排点，固定顺序不可重排）：

1. **参数校验**（`VideoInteractionValidation`，与 DB 核心同一份规则）：`Idempotency-Key`、`vid`、动作、身份；
   全部发生在任何 Redis 计数之前，非法请求不会先吃掉一份额度。
2. **身份解析**（`VideoInteractionActorResolver`）：登录态 → `USER` +
   `SHA-256("video-interaction:actor:v1:user:" + uid)`；匿名 → 必须 canonical UUID，
   `SHA-256("...:anonymous:" + 小写 UUID)`；登录态下 `X-Visitor-Id` 整个忽略（含非法值）。
   不采集 IP / User-Agent / 设备指纹。
3. **视频校验**（`PublishedVideoGuard#requirePublishedPlayable`，与点赞/投币/收藏/进度同一份规则）：
   400 / 404 / 500。
4. **只读重放查询**（MySQL）：命中直接返回历史结果，**不访问 Redis**，所以重试不消耗额度，也不会把
   “上次被抑制”变成一次新判定。
5. **Redis 准入**（`VideoInteractionRateLimitServiceImpl`，复用 `lua/fixed-window-rate-limit.lua`）：
   同一 actor 两个维度，单动作默认 60 次/60 s、合计默认 120 次/60 s（`app.video-interaction.*`，
   启动期校验，`application.yml` 无条目时就是默认值）。超限 429 统一文案；脚本返回 `null`/负数/异常
   → 503（fail-closed，绝不 fail-open）。
6. **`VideoInteractionService#record`**：同一 MySQL 事务完成占位 → 窗口判定 → 计数 → 终态（细节见 §4）。

已批准并落地的决策：

- 两个请求头**强制**：缺 `Idempotency-Key`（匿名还缺 `X-Visitor-Id`）→ 400，**不兼容旧客户端**，
  没有灰度/兼容窗口。
- 视频校验**先于**幂等重放：视频下架/删除后，同一个 `Idempotency-Key` 重试返回 **404**，不是历史结果。
- 匿名身份用**客户端随机 UUID**（非指纹），服务端不下发；可随时更换，故防刷只对合作客户端有效。
- 30 分钟窗口**不可配置**（`VideoInteractionServiceImpl.WINDOW_MINUTES`）。
- 额度在 DB 事务之前消耗、事务失败**不退还**；两个维度不是原子的（取舍见
  `docs/tutorial/video-interaction-idempotency.md` §11）。

已知未做 / 未决：两表**保留期与清理已由用户批准并实现本切片**：`video_interaction_request` 自
`completed_at` 起保留 30 天、`video_interaction_window` 连续 30 天无计数即可清理；保留期写死为常量、
不可配置；清理**默认关闭**（`app.video-interaction.cleanup-enabled=false`），每轮按批（默认 500 行/批、
一分钟一轮）删除，**不是** 30 天到点的 TTL，也没有新 HTTP 接口。开启前必须先执行
`database/migrations/t15-video-interaction-cleanup-indexes.sql`（缺索引时清理明确报错、不降级全表扫描）。
所有运行参数、残余风险与测试矩阵见 `docs/tutorial/video-interaction-idempotency.md` §12。仍欠：生产死锁重试；
容量/压测；服务端下发或签名的访客标识；旧客户端兼容窗口；任何生产防刷验收。两表清理**不使用** T14 的
90/275 天归档政策（那是 `video_status_outbox` / `video_status_consumed_event` 的事，与 T15 无关）。
教程：`docs/tutorial/video-interaction-idempotency.md`。
