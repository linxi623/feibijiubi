# 外部视频源接入 E0 契约（数据 / API）

- 分支：`feature/external-video-source`，起点 `429bdbf`。
- 本文是 **E0 的唯一产物**，只写契约，不含生产代码、不含已执行迁移。
- 依据：`docs/tutorial/external-video-source-plan.md`（§1 范围、§4 必须固定细节）＋ 用户已确认的范围（见 §0.2）＋ 对当前源码的核对（`PublishedVideoGuard`、`UserVideoServiceImpl#savePlayProgress`、`VideoServiceImpl`（`submitVideo` / `uploadCover` / `deleteVideo`）、`VideoConverter`、`VideoMapper.xml`、`VideoStatusMapper.xml`、`FileStorageService`、`UploadTempFileMapper`、`database/feibijiubi.sql`）。

---

## 0. 文档状态与范围

### 0.1 状态：**候选（父评审中）**

**后续决策覆盖说明**：以下初稿状态是历史描述，不代表当前仓库没有实现。E1-A、E2 的实际进展以 plan 为准。用户本轮已确认人工映射剧集和“系统探测候选时长→管理员确认/修正”，并授权实施；具体分阶段计划见 [人工导入计划](external-video-source-manual-import.md)。人工身份采用系统生成并永久保存的本地键、管理员负责映射，覆盖本文旧的“管理员显式给出人工键”提案；探测值不直接批准导入，覆盖旧的“上游元数据即可作为已核实时长”理解。其余旧候选条款未因此整体获批。

> 本文是 **候选契约，正在父代理评审中**。它**不是**已批准的契约，**不是**已实现的代码，**不是**验收通过，也**没有**被用户批准。
>
> 逐条说明：
> 1. 仓库内目前**没有**任何外部源相关代码或表；本文所有表、列、约束、接口都是**待实现提议**。
> 2. 本文中所有"默认值"（HTTP 200 + `playable=false`、长度上限 2048/500、分页 `size` 上限、租约秒数、`import_state` 取值等）都是 **worker 提议，未获用户批准**，父代理可以整条推翻。上一版把其中若干项写成"已裁定"，是本轮要修的缺陷之一。
> 3. **真实 CMS 协议未验证**：无任何授权站点的 API 文档或脱敏样例，E2 只能按显式标注的 fixture 开发，不得声称兼容任何苹果 CMS / 海洋 CMS 版本。
> 4. **前端不在本仓库**：`GET /api/videos/{vid}/playback`、`GET /api/videos/{vid}/episodes` 的响应形状是后端提议，尚无消费方确认。
> 5. **未执行任何迁移、未连库、未联网、未启动基础设施、未运行测试**。本文中所有关于 MySQL 行为（锁、算法、CHECK 支持、外键校验）的描述都是**设计意图，未在真实 MySQL 上验证**。

**跨来源模型追加决策**：用户确认同一视频内容在不同来源下应共用一个 `vid`。本地作品/剧集必须独立于 `video_source_series`，由管理员将多个来源候选映射至本地剧集。本文旧的 source-series-owned episode 表形状因此不能直接实施；已有 E1-A 上游目录表仍仅作目录，无需据此改动现有数据。详情见人工导入计划。时长探测属于线路，本地剧集使用人工确认的统一时长；同标题或同秒数不能自动证明同内容。

### 0.2 用户已确认的范围（不得改变，也不得被本契约悄悄扩大）

以下来自 plan §1 与 plan §7 执行记录，是**已确认的范围**，实现时不得改变：

1. 只替换**播放源**；保留 COS 头像、背景、封面能力；关闭视频投稿与视频上传凭据入口。
2. 导入视频归属于**执行导入的管理员账号**；沿用作者详情、投稿列表、获赞等既有计算语义，不伪造用户。
3. **一集对应一个本地 `vid`**；互动、收藏、播放进度、统计仍以 `vid` 为单位，不引入作品级进度/计数。
4. 保留审核、可见性、分类、用户权限、播放/分享幂等与限流，以及现有 Redis/RabbitMQ 统计协议（outbox → MQ → Redis Lua → 刷盘 → 重建门闩）。
5. 首版限授权 **MP4/HLS 直链**；不接解析站、不执行外部脚本、不代理视频流、不下载视频到 COS。
6. 不按标题自动合并不同来源作品或跨线路剧集；无法证明稳定身份时**拒绝自动关联**，进入人工处理。
7. 既有 COS 视频数据**不删除、不批量转换**；新播放路径**不回退**到 COS。
8. **未知时长的资源只暂存在上游目录，不创建可互动的 `video`**；不改 `PublishedVideoGuard`，不改 `savePlayProgress` 的时长校验。
9. 不读取 `application.yml` 中的密钥、不连接真实站点、不启动基础设施、不执行迁移。

### 0.3 本轮修订索引（父评审 12 项 → 落点）

| 父评审问题 | 本轮落点 | 结论摘要 |
|---|---|---|
| 1 同集并发导入可各建一个 `video` 再覆盖 `episode.vid` | §5.1、§5.2、§4.13.6 | 改为"锁既有 episode 行 → 检查 `vid` → 建 `video`/`video_status` → CAS 绑定"，全在一个事务；首次目录行创建走独立唯一冲突路径，明确**不套用** T15 形状 |
| 2 租约 CAS 只查检查点不够；缺跨 job 同 source 串行机制 | §5.3、§5.4、§4.8、§4.1 | 每批业务写事务首语句锁 job 行并校验 `status`/token/`lease_epoch`/DB 期限；新增 `lease_epoch`；新增源级活动作业列 + 明确的全局加锁顺序 |
| 3 归属写成 series 的 `import_uid` | §1.2 P1、§4.2、§4.8 | 删除 `series.import_uid`；归属只来自不可伪造的 `job.created_by` 或人工操作的登录上下文；定时任务无显式固定 owner 时**只能同步** |
| 4 缺独立播放来源字段 | §4.11、§4.13.6 | `video.playback_source`（1=COS 历史 / 2=EXTERNAL），加列 + 保留原记录语义，**禁止 UPDATE 回填**；说明与"只放宽三列"的关系 |
| 5 地址变更自动绕过审核 | §6.2、§4.6 | 改为"当前已批准值 + 单一待审候选"（显式列 + `revision` CAS），新线路默认未启用/未批准；不改 `video.status`，播放只用批准值；旧地址核验结果不能沿用 |
| 6 缺 HTTPS / 域名端口白名单 | §4.15 | 只允许 HTTPS + 部署配置里的精确 host:port 白名单；后台访问另需 DNS/重定向/出口约束；长度是工程上限，不宣称协议上限 |
| 7 剧集导航泄露未审核/私密/删除的 vid/count；砍掉选集 | §7.1、§7.2 | 导航与详情用**同一可见性过滤**；新增有界分页选集接口，不返回任何不可见集的数量；不声称 prev/next 一定可播放 |
| 8 无"未导入封面暂存字段"，且会清空整部作品封面 | §4.3、§7.3.1 | episode 新增明确的 `cover_object_key/uid/adopted_at`；同一 COS 对象不共享可删除所有权；COS 与 MySQL 非原子的四种边界逐条写出，不宣称补偿可靠 |
| 9 duration 更新会重置 `import_state`；自创 86400 上限 | §7.3.2、§4.13.4 | 已导入剧集**拒绝**补齐接口（409），不重置 `import_state`，不造双真值；删除 86400 上限，只要求 `finite && > 0` |
| 10 无稳定作品 ID 仍可人工放宽 | §4.13.1、§4.13.3、§4.4 | 无稳定作品 ID **直接拒绝**（不建 series 行、无人工作品名例外）；缺稳定剧集身份用独立候选暂存表 + 人工绑定，后续同步**不得**按 label/URL 自动重映射 |
| 11 外键声明不完整、job.series/source 一致性、身份命名空间、迁移与测试纪律 | §4.12、§4.9、§4.13.2、§9.2、§10.1 | 补全 audit/`created_by`/`owner_uid` 等外键；`(source_id, series_id)` 复合外键；`u:`/`m:` 命名空间；明确 `CREATE TABLE IF NOT EXISTS` 不等于结构校验、ALTER 幂等不等于无锁；SQL 文本测试改为语句级解析 |
| 12 `Cache-Control: no-store`、`lineId` 稳定 400、停用不可撤销已发 URL、错误交叉引用、顶部状态 | §7.1、§6.3、全文 | 已补；§4.4.x 一类错误引用已全部改为实际章节号；顶部状态改为"候选（父评审中）" |

---

## 1. 裁定：已确认 vs 本契约提议

### 1.1 用户已确认（可直接作为实现前提）

见 §0.2 的九条。这些条目**不是**本文的发明，实现时不得削弱。

### 1.2 本契约提议（父评审中，**未获批准**）

| 编号 | 提议 | 落点 |
|---|---|---|
| P1 | 归属机制：删除 `series.import_uid`；`video.uid` 只取自 `video_source_import_job.created_by`（人工创建任务时的登录上下文）或人工操作的登录上下文；定时任务只有在 job 上显式固定 `owner_uid` 时才允许导入，否则该任务**只能同步目录/线路，不创建 `video`** | §4.2、§4.8 |
| P2 | `video` 新增 `playback_source`（1=COS 历史 / 2=EXTERNAL），与 `source_type` 完全分离 | §4.11 |
| P3 | 线路地址采用"当前已批准 + 单一待审候选"两级状态，新线路默认 `enabled=0` | §4.6、§6.2 |
| P4 | 外部 URL 只允许 HTTPS + 部署配置中的精确 `host:port` 白名单 | §4.15 |
| P5 | 剧集导航与选集列表使用与详情完全一致的可见性过滤，并新增有界分页选集接口 | §7.1、§7.2 |
| P6 | episode 持有显式可删除所有权的封面对象字段，导入时所有权原子移交 | §4.3、§7.3.1 |
| P7 | 时长只要求 `finite && > 0`（不设 86400 上限）；已导入剧集拒绝补齐接口 | §4.13.4、§7.3.2 |
| P8 | 无稳定作品 ID 直接拒绝；缺稳定剧集 ID 用候选暂存表 + 人工绑定 | §4.13.1、§4.13.3、§4.4 |
| P9 | 导入事务协议与作业租约协议（锁 episode 行 + 锁 job 行 + 源级活动作业） | §5 |
| P10 | 不可播放用 HTTP 200 + `playable=false`；播放 URL 长度上限 2048；选集分页 `size` 1..50 | §7.1、§7.2、§4.15 |

> 上表每一条都可以被父代理整条推翻。推翻时只改对应章节，不要把"提议"改成"已批准"。

---

## 2. 明确保持不变的既有契约（实现时不得改动）

以下全部经源码核对，E1–E7 不得修改其语义：

1. **`PublishedVideoGuard#requirePublishedPlayable`**（`service/video/PublishedVideoGuard.java`）：`vid` 非法 → 400 `视频参数不合法`；`selectPublishedByVid` 为空 → 404 `视频不存在`；`duration == null || duration <= 0` → 500 `视频时长数据异常`。**调用方不变**：`UserVideoServiceImpl#validateVideo`（点赞/投币/收藏/播放进度）与 `VideoInteractionAdmissionServiceImpl#record`（play-count/share）。
2. **`UserVideoServiceImpl#savePlayProgress`**：401 → 400（`playTime` 非有限/负数）→ `validateVideo` → `playTime > duration` 时 400 `播放进度不能超过视频时长`。`user_video.play_time` 仍 `DOUBLE NOT NULL DEFAULT 0`。
3. **互动统计协议**：`video_status` 八计数器、outbox → RabbitMQ → Redis Lua → 批量刷盘 → 重建门闩，全部不读 `video_url` / `video_key` / COS。
4. **T15 幂等与限流**：`/play-count` 与 `/share` 仍必填 `Idempotency-Key`（匿名另需 `X-Visitor-Id`），准入顺序不变。
5. **Feed**：`VideoMapper.xml#selectFeed` 只投影 `cover_url/duration/计数`，**不含 `video_url`**，无需改动。
6. **审核状态机**：`VideoReviewStatus.canTransitionTo`、`/api/admin/videos/**` 的 `@AdminOnly` 拦截器。
7. **作者/获赞计数**：`VideoMapper#countVideoByUid`、`VideoStatusMapper#countLikeByUid` 口径不变。
8. **分类缓存**：`CategoryServiceImpl` 的 Cache-Aside 不变。
9. **`video.duration` 保持 `DOUBLE NOT NULL DEFAULT 0`**：不新增 `duration_known` 列，不允许 NULL（未知时长不会进入 `video` 表，见 §4.13.4）。
10. **`VideoServiceImpl#deleteVideo`** 的权限与软删除语义不变：只有 `video.uid` 本人可删（导入管理员即 owner），软删除不清理 `video_status`/`user_video`/`video_interaction_*`；`afterCommit` 里会删除 `video.cover_key` 与 `video.video_key` 指向的 COS 对象（这条决定了 §4.3 的封面所有权规则）。

> 结构约束提醒：`VideoStatusReadConsistencyTests` 用反射/构造器断言把"`VideoServiceImpl`/`UserServiceImpl`/`VideoReviewImpl` 不依赖 Redis/Redisson"当作契约。E5 的播放链路**不得**在这些类里注入 Redis。

---

## 3. 命名与单位约定

| 概念 | 唯一拼写 | 说明 |
|---|---|---|
| 来源站 | `video_source` | 一个上游 CMS 站点 |
| 上游作品分组 | `video_source_series` | 对应上游一部作品 |
| 剧集绑定 | `video_source_episode` | 一行 = 一个上游剧集；导入后持有 `vid` |
| 未定身份的剧集候选 | `video_source_episode_candidate` | 上游目录暂存，**不是身份**（§4.4） |
| 线路 | `video_source_line` | 一集内的一条播放线路 |
| 线路地址 | `video_source_play_address` | 线路的**当前已批准地址 + 单一待审候选**（1:1 行） |
| 分类映射 | `video_source_category_map` | 上游分类 → 本地 `mc_id/sc_id` |
| 同步/导入任务 | `video_source_import_job` | 分页、检查点、租约 |
| 审计 | `video_source_audit` | 人工/系统写操作留痕 |

- 时间列：普通时间用 `DATETIME`；需要毫秒并发排序/期限判断的用 `DATETIME(3)`（与 T15 一致）。
- 身份列（`code` / `upstream_series_id` / `episode_identity` / `upstream_episode_id` / `line_key` / 上游分类键 / `payload_hash` / `url_hash`）一律 `VARCHAR(...) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`：`video` 表默认 `utf8mb4_unicode_ci` 会把大小写/重音不同的两个上游 ID 判为相等，导致串集（T15 `video_interaction_request.request_key` 已有 `utf8mb4_bin` 先例）。
- 表默认排序规则仍为 `utf8mb4_unicode_ci`，只对身份列显式 `bin`。
- 所有表 `ENGINE=InnoDB`，无 `ON DELETE CASCADE`（理由见 §4.10）。
- **剧集身份命名空间**（§4.13.2）：`episode_identity` 必须带前缀，`u:<上游剧集ID>` 或 `m:<人工稳定键>`。前缀是命名空间而不是"生成身份"：`u:` 段的值必须原样来自上游，`m:` 段的值由管理员显式给出并永久落库。

---

## 4. 数据契约

### 4.1 `video_source`（来源站）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `INT AUTO_INCREMENT` | PK | |
| `code` | `VARCHAR(64)` `utf8mb4_bin` | NOT NULL | 站内稳定编码，人工指定；也是 §4.15 白名单的配置键 |
| `name` | `VARCHAR(100)` | NOT NULL | 展示名 |
| `adapter_type` | `VARCHAR(32)` | NOT NULL | 适配器标识；取值由 E2 适配器注册表定义，**E1 不枚举业务值** |
| `base_url` | `VARCHAR(500)` | NOT NULL | 上游根地址（仅服务端使用，不对外返回） |
| `enabled` | `TINYINT` | NOT NULL DEFAULT 0 | 0 停用 / 1 启用。停用 → 拒绝新播放（§6.3）与新导入 |
| `credential_ref` | `VARCHAR(128)` | NULL | 凭据**引用名**，见 §4.15.7；NULL = 无需凭据 |
| `active_job_id` | `BIGINT` | NULL | 当前持有**源级串行权**的 job（§5.4）；无 FK，理由见下 |
| `active_job_expire_at` | `DATETIME(3)` | NULL | 源级串行权到期（DB 时钟）；NULL 或 `<= NOW(3)` 视为无人持有 |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_code (code)`
- `KEY idx_video_source_active_job (active_job_id)` —— 供释放/心跳按 job 定位。
- 不存明文凭据、不存任何密钥列。
- **`active_job_id` 故意不加外键**：`video_source` 与 `video_source_import_job` 互相引用，用 `CREATE TABLE` 无法表达循环外键，只能事后 `ALTER TABLE ... ADD CONSTRAINT`，而该语句默认不幂等（需先查 `information_schema`）。悬空 `active_job_id` 无害：有效性必须同时满足 `active_job_expire_at > NOW(3)`，并且 job 行自身状态在 §5.3 被单独校验。这条**不**等于"外键声明完整"，只是说明了缺口与理由。

### 4.2 `video_source_series`（上游作品分组）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `INT AUTO_INCREMENT` | PK | |
| `source_id` | `INT` | NOT NULL | FK → `video_source(id)` |
| `upstream_series_id` | `VARCHAR(191)` `utf8mb4_bin` | NOT NULL | **作品身份**（§4.13.1）；必须来自上游持久标识 |
| `title_snapshot` | `VARCHAR(255)` | NULL | 仅展示/快照，**不是身份**，可变 |
| `mc_id` / `sc_id` | `VARCHAR(20)` | NULL | 本地分类（由 §4.14 映射得出）；NULL = 尚未映射 |
| `cover_url_external` | `VARCHAR(500)` | NULL | 上游外链封面，§4.15 限制；**只由同步写**，任何认领接口都不得清空它 |
| `sync_state` | `TINYINT` | NOT NULL DEFAULT 0 | 0 未同步 / 1 同步中 / 2 已同步 / 3 同步失败 |
| `last_synced_at` | `DATETIME` | NULL | |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_series_identity (source_id, upstream_series_id)`
- `UNIQUE KEY uk_video_source_series_source_id (source_id, id)` —— 为 §4.8 的 `(source_id, series_id)` 复合外键提供被引用键（`id` 已是 PK，此键只是让复合外键成立）。
- `CONSTRAINT fk_video_source_series_source FOREIGN KEY (source_id) REFERENCES video_source (id)`
- **没有 `import_uid`**：作品级归属字段已删除。归属只存在于 job 行（`created_by` / `owner_uid`，§4.8），理由见 §1.2 P1 与 §5.1。任何人不得在本表重新引入"归属人"语义的列。

### 4.3 `video_source_episode`（剧集绑定 + 上游目录暂存）

这一张表同时承担"上游目录"和"本地剧集绑定"两个角色：未导入的剧集以 `vid IS NULL` 的形式存在（未知时长就停在这里，见 §4.13.4）。

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | PK | |
| `series_id` | `INT` | NOT NULL | FK → `video_source_series(id)` |
| `episode_identity` | `VARCHAR(191)` `utf8mb4_bin` | NOT NULL | **剧集身份**（§4.13.2），带 `u:`/`m:` 前缀；一旦写入**永不 UPDATE** |
| `identity_origin` | `TINYINT` | NOT NULL | 1 = `UPSTREAM_ID` / 2 = `MANUAL_MAP` |
| `upstream_episode_id` | `VARCHAR(191)` `utf8mb4_bin` | NULL | 原始上游剧集 ID；`identity_origin=1` 时 NOT NULL 且 `episode_identity = CONCAT('u:', upstream_episode_id)`；`identity_origin=2` 时必须 NULL |
| `manual_assigned_by` | `INT` | NULL | `identity_origin=2` 时 NOT NULL；FK → `users(id)` |
| `manual_assigned_at` | `DATETIME(3)` | NULL | `identity_origin=2` 时 NOT NULL |
| `episode_no` | `INT` | NULL | **仅展示**，不参与任何唯一键、不参与身份 |
| `title_snapshot` | `VARCHAR(255)` | NULL | 仅展示/快照，**不是身份** |
| `duration_seconds` | `DOUBLE` | NULL | 上游元数据或管理员核实值；NULL = 未知 |
| `duration_source` | `TINYINT` | NULL | 1 = 上游元数据 / 2 = 管理员核实；与 `duration_seconds` 同生同灭 |
| `duration_verified_by` | `INT` | NULL | FK → `users(id)` |
| `duration_verified_at` | `DATETIME(3)` | NULL | |
| `cover_object_key` | `VARCHAR(500)` | NULL | **本站已认领的封面对象正式 key**（§7.3.1）；导入时移交给 `video.cover_key` 并清空本列 |
| `cover_object_uid` | `INT` | NULL | 认领该对象的操作者 uid；FK → `users(id)`；与 `cover_object_key` 同生同灭 |
| `cover_object_adopted_at` | `DATETIME(3)` | NULL | 认领时间（DB 时钟）；与 `cover_object_key` 同生同灭 |
| `vid` | `INT` | NULL | 导入后指向 `video(vid)`；NULL = 尚未导入 |
| `import_state` | `TINYINT` | NOT NULL DEFAULT 0 | 0 已收录未导入 / 1 已导入 / 2 阻断 / 3 冲突待人工 / 4 跳过 |
| `block_reason` | `VARCHAR(64)` | NULL | `import_state=2/3` 时的固定码：`DURATION_UNKNOWN`、`DURATION_UNTRUSTED`、`ADDRESS_MISSING`、`COVER_MISSING`、`CATEGORY_UNMAPPED`、`IDENTITY_CONFLICT`；其它状态必须为 NULL |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_episode_identity (series_id, episode_identity)`
- `UNIQUE KEY uk_video_source_episode_vid (vid)` —— MySQL 唯一索引允许多个 NULL，所以未导入行可以共存。**它只保证"一个 vid 只属于一集"，不能阻止两个事务各建一个 `video` 再互相覆盖 `episode.vid`**；同集并发必须靠 §5.1 的行锁 + CAS，不能靠这个唯一键。
- `KEY idx_video_source_episode_state (import_state, id)` —— 供 E4 任务分页
- `CONSTRAINT fk_video_source_episode_series FOREIGN KEY (series_id) REFERENCES video_source_series (id)`
- `CONSTRAINT fk_video_source_episode_vid FOREIGN KEY (vid) REFERENCES video (vid)`（`video` 只做软删除，不会阻塞）
- `CONSTRAINT fk_video_source_episode_manual_by FOREIGN KEY (manual_assigned_by) REFERENCES users (id)`
- `CONSTRAINT fk_video_source_episode_duration_by FOREIGN KEY (duration_verified_by) REFERENCES users (id)`
- `CONSTRAINT fk_video_source_episode_cover_by FOREIGN KEY (cover_object_uid) REFERENCES users (id)`
- `CONSTRAINT ck_video_source_episode_identity_shape CHECK (...)`：见 §4.12 的 CHECK 清单。
- **为什么只存 key、不存 url 列**：封面 URL 由 `FileStorageService#Key2Url(cover_object_key)` 派生，落两列会产生第二个真值；因此本表只有 key，没有 `cover_object_url`。导入时 `video.cover_url = Key2Url(cover_object_key)`、`video.cover_key = cover_object_key`，两者在同一条 INSERT 里写定。
- **封面所有权规则**（与 `deleteVideo` 相关）：`cover_object_key` 非空时，该 COS 对象**只能**由这一行持有；导入事务把它交给 `video.cover_key` 的同时必须把 `cover_object_key/cover_object_uid/cover_object_adopted_at` 置 NULL。任何时刻同一个可删除对象**只允许一个所有者列非空**（要么 `episode.cover_object_key`，要么 `video.cover_key`，绝不同时）。

### 4.4 `video_source_episode_candidate`（缺稳定剧集身份的暂存候选）

上游目录里**没有稳定剧集 ID** 的行，不得直接变成 `video_source_episode`（§4.13.3），而是落到这张暂存表，供管理员在 E3 做人工绑定。这张表**不是身份库**。

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | PK | 管理端列表/绑定的稳定引用 |
| `series_id` | `INT` | NOT NULL | FK → `video_source_series(id)` |
| `payload_hash` | `CHAR(64)` `utf8mb4_bin` | NOT NULL | 规范化后上游行内容的 `SHA-256` 小写十六进制；**只用于暂存内去重，永远不得升级为 `episode_identity`** |
| `episode_no` | `INT` | NULL | 上游序号，仅展示 |
| `title_snapshot` | `VARCHAR(255)` | NULL | 上游标题，仅展示 |
| `format` | `VARCHAR(16)` | NULL | `MP4` / `HLS`；未知为 NULL |
| `address_hash` | `CHAR(64)` `utf8mb4_bin` | NULL | 候选地址的 `SHA-256`；**不存原始 URL** |
| `status` | `TINYINT` | NOT NULL DEFAULT 0 | 0 `NEW` / 1 `MAPPED` / 2 `IGNORED` / 3 `CONFLICT` |
| `mapped_episode_id` | `BIGINT` | NULL | 人工绑定后的 `video_source_episode(id)`；FK → `video_source_episode(id)`；`status=1` 时 NOT NULL |
| `first_seen_at` / `last_seen_at` | `DATETIME(3)` | NOT NULL | 首次/最近一次在同步结果中出现 |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_episode_candidate (series_id, payload_hash)`
- `KEY idx_video_source_episode_candidate_state (series_id, status, id)`
- `CONSTRAINT fk_..._candidate_series FOREIGN KEY (series_id) REFERENCES video_source_series (id)`
- `CONSTRAINT fk_..._candidate_episode FOREIGN KEY (mapped_episode_id) REFERENCES video_source_episode (id)`
- `CONSTRAINT ck_..._candidate_mapped CHECK ((status = 1 AND mapped_episode_id IS NOT NULL) OR (status <> 1 AND mapped_episode_id IS NULL))`
- 限制（必须写进实现与文档，不得省略）：
  1. `payload_hash` 会随上游标题/顺序变化而变化，因此**同一集可能产生多条候选**；系统**不合并**，由人工把多余的标为 `IGNORED`。
  2. 绑定是一次性动作，绑定后**同步不得**再用 `title_snapshot` / `episode_no` / `address_hash` 自动改绑或重映射（§4.13.3）。
  3. 本表不参与播放、不参与计数、不对外暴露地址。

### 4.5 `video_source_line`（线路）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | PK | |
| `episode_id` | `BIGINT` | NOT NULL | FK → `video_source_episode(id)`；线路**只能**属于一集 |
| `line_key` | `VARCHAR(191)` `utf8mb4_bin` | NOT NULL | 上游线路标识（如 `play_from`）规范化后的值 |
| `line_label` | `VARCHAR(100)` | NULL | 展示名 |
| `line_order` | `INT` | NOT NULL DEFAULT 0 | 默认线路选择依据（§7.1.5） |
| `enabled` | `TINYINT` | NOT NULL DEFAULT 0 | 0 未启用 / 1 已启用。**新建线路默认 0**，必须由管理员显式启用（`ENABLE_LINE` 审计） |
| `approved_at` / `approved_by` | `DATETIME(3)` / `INT` | NULL | 启用时的操作者；FK → `users(id)` |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_line_key (episode_id, line_key)`
- `CONSTRAINT fk_video_source_line_episode FOREIGN KEY (episode_id) REFERENCES video_source_episode (id)`
- `CONSTRAINT fk_video_source_line_approved_by FOREIGN KEY (approved_by) REFERENCES users (id)`
- 同步发现的新线路一律 `enabled = 0`：新线路**不会**自动可播放。后果必须承认：一条剧集在管理员批准线路与首个地址之前是不可播放的，即使它已经导入并过审。

### 4.6 `video_source_play_address`（线路的当前已批准地址 + 单一待审候选）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | PK | |
| `line_id` | `BIGINT` | NOT NULL | FK → `video_source_line(id)` |
| `approved_url` | `TEXT` | NULL | **当前已批准**地址；NULL = 尚无批准地址（线路不可播放） |
| `approved_url_hash` | `CHAR(64)` `utf8mb4_bin` | NULL | `SHA-256(approved_url)` 小写十六进制 |
| `approved_format` | `VARCHAR(16)` | NULL | `MP4` / `HLS` |
| `approved_at` / `approved_by` | `DATETIME(3)` / `INT` | NULL | 批准时间（DB 时钟）/ 批准人；FK → `users(id)` |
| `approved_revision` | `BIGINT` | NOT NULL DEFAULT 0 | 每批准一次 +1；审计与乐观并发用 |
| `pending_url` | `TEXT` | NULL | **单一待审候选**地址；NULL = 无候选 |
| `pending_url_hash` | `CHAR(64)` `utf8mb4_bin` | NULL | |
| `pending_format` | `VARCHAR(16)` | NULL | |
| `pending_seen_at` | `DATETIME(3)` | NULL | 最近一次同步看到该候选的时间 |
| `pending_revision` | `BIGINT` | NOT NULL DEFAULT 0 | 每写入新候选 +1；批准时的 CAS 依据 |
| `verify_status` | `TINYINT` | NOT NULL DEFAULT 0 | 0 `UNKNOWN` / 1 `OK` / 2 `FAILED`；**本切片没有任何写入者**（见下） |
| `verified_url_hash` | `CHAR(64)` `utf8mb4_bin` | NULL | 该核验结论对应的地址哈希；`verify_status<>0` 时 NOT NULL |
| `verified_at` | `DATETIME(3)` | NULL | |
| `last_seen_at` | `DATETIME` | NULL | 最近一次在同步结果中出现（不论是否批准） |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_play_address_line (line_id)` —— 一条线路**只有一行**：当前已批准值 + 至多一个待审候选。**不保留历史版本、不实现多清晰度/备用地址库**（plan §4 执行记录、A14）。
- `KEY idx_video_source_play_address_verify (verify_status, id)` —— 为将来授权的核验任务预留；当前无调用者。
- `CONSTRAINT fk_video_source_play_address_line FOREIGN KEY (line_id) REFERENCES video_source_line (id)`
- `CONSTRAINT fk_video_source_play_address_approved_by FOREIGN KEY (approved_by) REFERENCES users (id)`
- 状态一致性由 CHECK 保证（§4.12）：`approved_*` 三元同生同灭、`pending_*` 三元同生同灭。
- **`verify_status` 的写入者**：本契约（E1–E5）**不实现**任何地址核验任务，`verify_status` 恒为 0 `UNKNOWN`。任何将来的写入者必须是显式授权的任务，且必须同时写 `verified_url_hash`；写 `OK`/`FAILED` 而不写哈希是违约。
- **核验结论与地址绑定**：`verify_status` 只在 `verified_url_hash = approved_url_hash` 时对当前地址有效。哈希不匹配的旧结论一律按 `UNKNOWN` 处理，**不得**沿用到新地址（§6.2.4）。
- 没有地址的行就是"无地址"，不写空串、不写占位 URL。

### 4.7 `video_source_category_map`（分类映射）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `INT AUTO_INCREMENT` | PK | |
| `source_id` | `INT` | NOT NULL | FK → `video_source(id)` |
| `upstream_category_key` | `VARCHAR(191)` `utf8mb4_bin` | NOT NULL | 上游分类的稳定键（有 ID 用 ID；只有名字的 CMS 才退化为名字，且这是**分类**不是作品身份，允许） |
| `mc_id` / `sc_id` | `VARCHAR(20)` | NOT NULL | 本地分类，必须存在于 `category` 表 |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `UNIQUE KEY uk_video_source_category_map (source_id, upstream_category_key)`
- `CONSTRAINT fk_video_source_category_map_source FOREIGN KEY (source_id) REFERENCES video_source (id)`
- **不加**指向 `category(mc_id, sc_id)` 的外键：与既有 `video` 表一致（`video.mc_id/sc_id` 本来就没有 FK）。合法性由应用层用 `category` 查询校验；`CategoryMapper` 目前只有 `categoryList()`，E3 需要补一个按 `(mc_id, sc_id)` 的查询或复用 `CategoryServiceImpl` 的缓存树。**因此"外键完整"这句话不适用于本表**，不得声称分类已被数据库约束。
- 未映射 = `series.mc_id/sc_id IS NULL` → 导入被阻断（`CATEGORY_UNMAPPED`），**绝不**写入任意字符串。

### 4.8 `video_source_import_job`（同步/导入任务）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | PK | |
| `source_id` | `INT` | NOT NULL | FK → `video_source(id)` |
| `series_id` | `INT` | NULL | FK（复合）→ `video_source_series(source_id, id)`；站点级同步为 NULL |
| `job_type` | `VARCHAR(32)` | NOT NULL | `SYNC_SERIES`（站点级）/ `SYNC_EPISODES` / `IMPORT_EPISODES` |
| `status` | `TINYINT` | NOT NULL DEFAULT 0 | 0 `PENDING` / 1 `RUNNING` / 2 `SUCCEEDED` / 3 `FAILED` / 4 `CANCELLED` |
| `created_by` | `INT` | NULL | 创建任务的登录管理员；FK → `users(id)`。**只有调度器创建的任务允许 NULL** |
| `owner_uid` | `INT` | NULL | 导入时写入 `video.uid` 的归属人；FK → `users(id)`。`job_type='IMPORT_EPISODES'` 时 **NOT NULL** |
| `checkpoint_page` | `INT` | NULL | 已完成的最后一页 |
| `checkpoint_cursor` | `VARCHAR(191)` | NULL | 上游游标/分页键（只存上游自己的键，不存地址） |
| `lease_owner` | `VARCHAR(64)` | NULL | 执行者令牌，**每次授予租约都必须重新随机生成**（UUID），不得复用 |
| `lease_epoch` | `INT` | NOT NULL DEFAULT 0 | 每次成功接管 +1；与 `lease_owner` 组成不可复现的 `(token, epoch)` 对 |
| `lease_expire_at` | `DATETIME(3)` | NULL | 租约到期（DB 时钟 `NOW(3)`） |
| `discovered_count` / `imported_count` / `skipped_count` / `failed_count` | `INT` | NOT NULL DEFAULT 0 | 计数 |
| `last_error_code` | `VARCHAR(64)` | NULL | **安全码**，不存原始异常文本、不存地址、不存凭据。站点级身份缺失用 `SERIES_IDENTITY_MISSING` |
| `started_at` / `finished_at` | `DATETIME` | NULL | |
| `created_at` / `updated_at` | `DATETIME` | NOT NULL | |

- `KEY idx_video_source_import_job_source (source_id, id)`
- `KEY idx_video_source_import_job_lease (status, lease_expire_at)`
- `CONSTRAINT fk_video_source_import_job_source FOREIGN KEY (source_id) REFERENCES video_source (id)`
- `CONSTRAINT fk_video_source_import_job_series FOREIGN KEY (source_id, series_id) REFERENCES video_source_series (source_id, id)` —— **这条复合外键才是"job.series 必须属于 job.source"的可执行约束**。MATCH SIMPLE 语义：`series_id IS NULL`（站点级同步）时不校验，正是我们要的。
- `CONSTRAINT fk_video_source_import_job_created_by FOREIGN KEY (created_by) REFERENCES users (id)`
- `CONSTRAINT fk_video_source_import_job_owner FOREIGN KEY (owner_uid) REFERENCES users (id)`
- CHECK：`job_type='IMPORT_EPISODES' ⇒ owner_uid IS NOT NULL`；`created_by IS NOT NULL AND owner_uid IS NOT NULL ⇒ created_by = owner_uid`；`job_type='SYNC_SERIES' ⇒ series_id IS NULL`，其它两种 `⇒ series_id IS NOT NULL`（§4.12）。
- **归属语义（P1）**：
  - 人工创建的任务：`created_by` = 登录管理员（来自 `@AdminOnly` 拦截器写入的登录上下文，**绝不**接受请求体里的 `uid`），`owner_uid` 必须等于 `created_by`。
  - 调度器创建的任务：`created_by IS NULL`，且只有在**显式固定**了 `owner_uid`（部署配置里为该调度任务指定的固定管理员 uid，写审计）时才允许 `job_type='IMPORT_EPISODES'`；否则调度任务只能是 `SYNC_*`，**不得创建任何 `video` 行**。
  - 任何路径都不得让 `video.uid` 来自请求参数、上游响应或 `series` 行。

### 4.9 `video_source_audit`（审计）

| 列 | 类型 | NULL | 说明 |
|---|---|---|---|
| `id` | `BIGINT AUTO_INCREMENT` | PK | |
| `source_id` / `series_id` / `episode_id` / `line_id` | `INT` / `INT` / `BIGINT` / `BIGINT` | NULL | 目标定位，按动作填；**不是层级关系**（见下） |
| `action` | `VARCHAR(48)` | NOT NULL | `IMPORT_EPISODE`、`ADOPT_COVER`、`REPLACE_COVER`、`SET_DURATION`、`BIND_MANUAL_EPISODE`、`APPROVE_ADDRESS`、`ENABLE_LINE`、`DISABLE_LINE`、`ENABLE_SOURCE`、`DISABLE_SOURCE`、`MAP_CATEGORY`、`JOB_TAKEOVER` |
| `operator_uid` | `INT` | NOT NULL | FK → `users(id)` |
| `before_value` / `after_value` | `VARCHAR(500)` | NULL | **只允许**非敏感标量：`duration_seconds`、`enabled`、`line_key`、`mc_id/sc_id`、`url_hash`、`format`、`approved_revision`、`pending_revision`。**禁止**写入原始播放 URL、原始外链封面 URL、COS 临时/正式 key、`credential_ref` 的解析值 |
| `reason` | `VARCHAR(255)` | NULL | 人工说明 |
| `created_at` | `DATETIME(3)` | NOT NULL | 审计时间（DB 时钟） |

- `KEY idx_video_source_audit_episode (episode_id, id)`、`KEY idx_video_source_audit_created (created_at)`
- 外键（本版补全，上一版缺失）：
  - `CONSTRAINT fk_video_source_audit_operator FOREIGN KEY (operator_uid) REFERENCES users (id)`
  - `CONSTRAINT fk_video_source_audit_source FOREIGN KEY (source_id) REFERENCES video_source (id)`
  - `CONSTRAINT fk_video_source_audit_series FOREIGN KEY (series_id) REFERENCES video_source_series (id)`
  - `CONSTRAINT fk_video_source_audit_episode FOREIGN KEY (episode_id) REFERENCES video_source_episode (id)`
  - `CONSTRAINT fk_video_source_audit_line FOREIGN KEY (line_id) REFERENCES video_source_line (id)`
- **这些外键只保证"被指向的行存在"，不保证四个定位列彼此一致**（例如 `series_id` 与 `episode_id` 可以指向不同作品）。审计表是 append-only 的动作留痕，不做层级一致性证明，也不得声称做了。
- 审计表**只 INSERT**：没有 `UPDATE`、没有 `DELETE`。

### 4.10 为什么没有 `ON DELETE CASCADE`

源/作品/剧集删除是**显式应用操作**，不能用级联删除把"某个已发布 `vid` 的唯一线路记录"顺手删掉。级联会让一个可播放的剧集在无人审计的情况下变成不可播放。所有子表删除必须走应用层并写 `video_source_audit`。

### 4.11 `video` 表迁移（E1 执行，本文不执行）

现状（`database/feibijiubi.sql:171-193`，已核对）：`duration DOUBLE NOT NULL DEFAULT 0`、`video_url VARCHAR(500) NOT NULL`、`video_key VARCHAR(500) NOT NULL`、`cover_url VARCHAR(500) NOT NULL`、`cover_key VARCHAR(500) NOT NULL`、`source_type TINYINT NOT NULL DEFAULT 1`（1 自制 / 2 转载）。表里**没有** `updated_at`。

#### 4.11.1 新增 `playback_source`（独立播放来源字段）

```sql
-- 语句 1：只加列，追加在表尾（不写 AFTER），以便 MySQL 8.0.12+ 走 INSTANT
ALTER TABLE `video`
    ADD COLUMN `playback_source` TINYINT NOT NULL DEFAULT 1
        COMMENT '播放来源 1=COS（历史行） 2=EXTERNAL（外部源导入），写入后不再变更';
```

- 取值只有两个：`1 = COS`、`2 = EXTERNAL`。写入时机：`VideoServiceImpl#submitVideo` 一类本站投稿路径显式写 1；外部源导入显式写 2。**插入后不再 UPDATE**（与 §8 的"COS 行不得变成外部源剧集"一致）。
- **为什么默认 1 不是"回填"**：迁移里只有 `ADD COLUMN ... NOT NULL DEFAULT 1` 这一条 DDL，**没有**任何 `UPDATE` 语句。默认值就是"历史行的语义声明"：按 §8，本次迁移之前存在的每一行都必然是本站 COS 行，因此默认 1 不需要猜测、也不需要数据修正。禁止用 `UPDATE video SET playback_source = ...` 做任何"补数据"。
- **为什么必须新增这一列，而不能靠"三列放宽"推断**：`video_url/video_key/cover_key` 变成 NULL-able 只说明"这一行可以没有本站对象 key"，它**不能**表达"这一行属于哪种播放协议"。用 `video_key IS NULL` 当枚举是 NULL 语义反模式：一行 COS 记录只要 key 被清空就会被误判为外部源，而且将来出现第三种来源时无法表达。`playback_source` 与 `source_type`（1 自制 / 2 转载）是**两个正交维度**，不得合并、不得互相复用。
- 播放读路径（§7.1）必须先看 `playback_source`：`1` → 直接 `NOT_BOUND`（无 COS 回退）；`2` → 走剧集绑定。E5 需要在 `VideoMapper#selectPublishedByVid` 的投影里加 `playback_source`（现有投影含 `video_url` 但**不含** `video_key`/`cover_key`/`playback_source`）。
- 不新增 `playback_source` 索引：当前没有按它过滤的热路径；将来若管理端需要按来源筛选，再单独评估。

#### 4.11.2 只放宽三列（`duration` 与 `cover_url` 不动）

```sql
-- 语句 2：三列放宽为 NULL。与语句 1 分开，避免把 ADD COLUMN 拖出 INSTANT 路径
ALTER TABLE `video`
    MODIFY COLUMN `video_url` VARCHAR(500) NULL
        COMMENT '视频URL；外部来源不写本站地址，历史 COS 行保持原值',
    MODIFY COLUMN `video_key` VARCHAR(500) NULL
        COMMENT 'COS 对象 key；外部来源为 NULL（不写假 key），历史行保持原值',
    MODIFY COLUMN `cover_key` VARCHAR(500) NULL
        COMMENT '本站封面对象 key；外链封面为 NULL，历史行保持原值';
```

- **无 UPDATE / 无 DELETE / 无回填 / 无默认值改写**：历史行的 `video_url`/`video_key`/`cover_key`/`cover_url`/`duration` 保持原值。
- `duration` 保持 `NOT NULL DEFAULT 0`（§2 第 9 条）。未知时长**不会**出现在 `video` 表里，见 §4.13.4。
- `cover_url` 保持 `NOT NULL`：导入必须产出一个封面（外链 URL 或承接的 COS 图），没有封面就阻断导入（`COVER_MISSING`），不写假 URL。
- 代码兼容性（已核对，无需额外改造）：
  - `VideoServiceImpl#deleteFormalObjectQuietly` 用 `StringUtils.hasText` 判空，`NULL` key 直接返回。
  - `VideoConverter` 的 `setVideoUrl(...)` 在 E5 移除后，`NULL videoUrl` 不会进入任何响应。
  - `selectPublishedByVid` / `selectByVid` 的 `video_url` 映射为 `null`，`Video` 实体字段本可空。

### 4.12 外键、CHECK 与一致性约束汇总（含"它们不证明什么"）

| 约束 | 目标 | 保证 | **不**保证 |
|---|---|---|---|
| `fk_video_source_series_source` | `video_source(id)` | 作品的来源存在 | 来源是否启用 |
| `uk_video_source_series_identity` | `(source_id, upstream_series_id)` | 同源内作品身份唯一 | 身份是否真的来自上游（应用层规则） |
| `uk_video_source_episode_identity` | `(series_id, episode_identity)` | 同作品内剧集身份唯一 | 身份不可变（应用层拒绝 UPDATE） |
| `uk_video_source_episode_vid` | `(vid)` | 一个 `vid` 只属于一集 | **同集并发不重复建 `video`**（靠 §5.1） |
| `fk_video_source_episode_vid` | `video(vid)` | 绑定的 `vid` 存在 | 该 `vid` 是否已删除/未过审 |
| `fk_video_source_episode_cover_by` | `users(id)` | 认领人存在 | 该 key 是否真属于该 uid（应用层 + `upload_temp_file` 校验） |
| `fk_video_source_import_job_series` | `(source_id, series_id)` → `video_source_series(source_id, id)` | job 的 series 属于 job 的 source（`series_id` 非空时） | `series_id IS NULL` 的站点级任务不受此约束（MATCH SIMPLE） |
| `fk_video_source_import_job_created_by` / `_owner` | `users(id)` | 归属人存在 | 该 uid 是否为管理员、是否等于 `created_by`（靠 CHECK + 应用层） |
| `fk_video_source_play_address_line` | `video_source_line(id)` | 地址属于线路 | 线路是否启用、地址是否已批准 |
| `fk_video_source_audit_*` | 五张表 | 定位列指向的行存在 | 四个定位列彼此一致（§4.9） |

CHECK 清单（MySQL **8.0.16+** 才强制；实例版本未验证，若不支持则退化为服务层校验，风险是直连 SQL 可绕过——此风险必须写进 §11）：

1. `video_source_episode`：`(identity_origin = 1 AND episode_identity LIKE 'u:%' AND upstream_episode_id IS NOT NULL AND episode_identity = CONCAT('u:', upstream_episode_id) AND manual_assigned_by IS NULL AND manual_assigned_at IS NULL) OR (identity_origin = 2 AND episode_identity LIKE 'm:%' AND upstream_episode_id IS NULL AND manual_assigned_by IS NOT NULL AND manual_assigned_at IS NOT NULL)`
2. `video_source_episode`：`(cover_object_key IS NULL AND cover_object_uid IS NULL AND cover_object_adopted_at IS NULL) OR (cover_object_key IS NOT NULL AND cover_object_uid IS NOT NULL AND cover_object_adopted_at IS NOT NULL)`
3. `video_source_episode`：`(import_state IN (2,3) AND block_reason IS NOT NULL) OR (import_state NOT IN (2,3) AND block_reason IS NULL)`
4. `video_source_episode`：`(duration_seconds IS NULL AND duration_source IS NULL) OR (duration_seconds IS NOT NULL AND duration_source IN (1,2))`
5. `video_source_play_address`：`(approved_url IS NULL AND approved_url_hash IS NULL AND approved_format IS NULL) OR (approved_url IS NOT NULL AND approved_url_hash IS NOT NULL AND approved_format IS NOT NULL)`
6. `video_source_play_address`：`(pending_url IS NULL AND pending_url_hash IS NULL AND pending_format IS NULL) OR (pending_url IS NOT NULL AND pending_url_hash IS NOT NULL AND pending_format IS NOT NULL)`
7. `video_source_play_address`：`verify_status IN (0,1,2) AND (verify_status = 0 OR verified_url_hash IS NOT NULL)`
8. `video_source_import_job`：`(job_type <> 'IMPORT_EPISODES' OR owner_uid IS NOT NULL)` 且 `(created_by IS NULL OR owner_uid IS NULL OR created_by = owner_uid)` 且 `(job_type = 'SYNC_SERIES' AND series_id IS NULL) OR (job_type IN ('SYNC_EPISODES','IMPORT_EPISODES') AND series_id IS NOT NULL)`
9. `video_source_episode_candidate`：`(status = 1 AND mapped_episode_id IS NOT NULL) OR (status <> 1 AND mapped_episode_id IS NULL)`

> 明确不过度声称：即使上面全部生效，也**不能**证明"剧集身份来自上游""地址已审核""同一 COS 对象只有一个所有者"这类跨表/跨系统性质；那些只能由 §5 的事务协议与 §6.2 的 CAS 保证，并且都只在应用层成立。

### 4.13 身份规则（E1/E4 的实现约束）

#### 4.13.1 作品身份（无稳定 ID 直接拒绝）

- 身份 = `(source_id, upstream_series_id)`，唯一键 `uk_video_source_series_identity`。
- `upstream_series_id` 必须来自上游**持久标识**（详情接口里的作品 ID，或上游自身稳定编码），并落库。
- **禁止**：用作品名、`title_snapshot`、`base_url + 标题`、列表页数组下标、请求顺序号生成 `upstream_series_id`。
- 上游只提供名称、没有稳定作品 ID 时：**直接拒绝**。该作品**不创建 `series` 行**，同步只在 job 上记 `last_error_code = SERIES_IDENTITY_MISSING` 并计入 `skipped_count`。
- **没有"人工作品名例外"**：不存在"管理员给作品起一个稳定键"的入口。任何这类入口都等于用名字放宽 A11，本条明确禁止。人工介入只允许发生在**剧集层**（§4.13.3），且前置条件是作品本身已有稳定上游 ID。

#### 4.13.2 剧集身份与命名空间

- 首选：上游稳定剧集 ID → `identity_origin = 1 (UPSTREAM_ID)`，`episode_identity = 'u:' || upstream_episode_id`，`upstream_episode_id = episode_identity` 去掉前缀后的原值（CHECK 1 保证）。
- 缺失时：**不猜**。由管理员建立**持久人工映射** → `identity_origin = 2 (MANUAL_MAP)`，`episode_identity = 'm:' || <管理员显式给出的稳定键>`，`upstream_episode_id = NULL`，`manual_assigned_by`/`manual_assigned_at` NOT NULL，写审计 `BIND_MANUAL_EPISODE`。
- 前缀是**命名空间**，解决"人工键与上游 ID 撞字符串"的冲突：上游 ID `x` 与人工键 `x` 得到 `u:x` 与 `m:x`，永不相等。它不改变"A11：身份必须来自上游"——`u:` 段的值原样来自上游。
- 唯一键 `uk_video_source_episode_identity (series_id, episode_identity)`；`episode_identity` **一旦写入不再 UPDATE**（无数据库级不可变约束，必须在 service 层拒绝）。
- **禁止**用 `episode_no`、`title_snapshot`、播放 URL、`url_hash`、`payload_hash`、线路内序号、数组下标作为剧集身份。
- 已知冲突（必须承认，不自动解决）：上游后来开始提供稳定 ID 时，同一物理剧集可能已经存在一条 `m:` 行，同步会按 `u:` 再建一条 → 同集两个 `vid`。自动路径**不得**做任何"看起来是同一集"的合并（那需要标题/URL 匹配，正是 A12 禁止的）。处理方式只有人工：把 `u:` 行或 `m:` 行标 `import_state = 3 / block_reason = IDENTITY_CONFLICT`，由管理员决定保留哪一个。最小可执行辅助：管理端提供只读列表，列出"同作品内 `identity_origin=2` 且 `import_state IN (0,2)`"的行与"该作品下 `status=0 NEW` 的候选"，只显示 `episode_no`/`title_snapshot`/时间，不显示地址、不做自动匹配。

#### 4.13.3 缺稳定剧集身份的暂存与人工映射（最小可执行方案）

1. 同步遇到没有稳定剧集 ID 的上游行 → 写 `video_source_episode_candidate`（§4.4），`status = 0 NEW`，**不创建** `video_source_episode` 行、不创建 `video`。
2. 展示：管理端 `GET /api/admin/video-sources/{sourceId}/episode-candidates?status=NEW&afterId&limit`（只读，keyset 分页，`limit` 1..100）返回 `{candidateId, episodeNo, titleSnapshot, format, firstSeenAt, lastSeenAt}`；**不返回** `address_hash`，也不返回任何原始地址。
3. 人工绑定：`POST /api/admin/video-sources/episode-candidates/{candidateId}/bind`，入参 `{ "manualKey": "<管理员给出的稳定键>" }`。同一事务内：创建 `video_source_episode`（`identity_origin=2`、`episode_identity='m:'||manualKey`）→ `UPDATE ... SET status=1, mapped_episode_id=? WHERE id=? AND status=0`（CAS，0 行即 409）→ 写审计 `BIND_MANUAL_EPISODE`。重复绑定同一候选 → 409。
4. 后续同步的边界（硬约束）：
   - 同步**只能**刷新候选的 `last_seen_at`/`title_snapshot`/`address_hash`，**不得**用它去改绑、重映射、合并或删除已绑定行。
   - 上游行内容变化会产生新的 `payload_hash` → 新候选；旧候选保持 `MAPPED`。系统不判断"是不是同一集"。
   - 人工键一旦落库即为该剧集身份，**不得**因为上游后来出现 ID 而自动迁移到 `u:`。
5. 限制（必须写进文档与实现注释）：候选表可能长期堆积；`payload_hash` 相同的行才去重，标题微调即产生新行；没有任何自动清理，也没有"候选→剧集"的自动规则。

#### 4.13.4 未知时长（`PublishedVideoGuard` 零改动的前提）

- `duration_seconds IS NULL` 的剧集**只能**停留在 `video_source_episode`（`vid IS NULL`，`import_state = 2`，`block_reason = DURATION_UNKNOWN`）。这是"只暂存上游目录"的落地形式。
- **不创建** `video` 行 → 不可被详情/feed/互动命中 → `PublishedVideoGuard` 与 `savePlayProgress` 完全不需要改。
- 导入前置条件（缺一即阻断，**不写假值**）：
  1. `duration_seconds IS NOT NULL`；
  2. `Double.isFinite(duration_seconds)`；
  3. `duration_seconds > 0`；
  4. `duration_source` ∈ {1 上游元数据, 2 管理员核实}。
- **没有 86400 秒（24 小时）上限**。上一版的 86400 是 worker 自行发明、没有任何产品授权，本轮删除。数值上界只由"有限且大于 0"和 `video.duration` 的 `DOUBLE` 类型决定；不截断、不 clamp、不做"合理性"判断。
- 上游上报 `0` / 负数 / `NaN` / `Infinity` / 缺失 → 一律视为**未知**（`DURATION_UNKNOWN`）或**不可信**（`DURATION_UNTRUSTED`），绝不当成合法时长。
- 管理员补齐走 §7.3.2，写入 `duration_source = 2` + `duration_verified_by/at` + 审计。
- **禁止**从客户端上报覆盖时长：任何面向普通用户的接口都不接受 `duration` 写入。

#### 4.13.5 线路与地址身份

- 线路身份 = `(episode_id, line_key)`；`line_key` 是上游线路标识规范化后的值。
- 地址**不是**身份：它是线路的可替换属性，1:1 存在 `video_source_play_address`，靠 `UNIQUE (line_id)` 保证"每线一行"。
- **跨线路不自动融合**：一条线路的地址只写回该线路自己的行；无法确认属于某集的地址一律丢弃/标记，不做"就近合并"、不做"按 `url_hash` 跨线路认领"。
- 不实现清晰度/备用地址历史库：同一线路出现新地址 = 写入 `pending_*`（§6.2），批准后替换 `approved_*`，旧值不保留。

#### 4.13.6 幂等（详见 §5）

- 绑定表命中即复用 `vid`；`video_status.insertDefault` 是普通 `INSERT`（`VideoStatusMapper.xml`，已核对），重复导入会 `DuplicateKeyException`，所以幂等必须由 `video_source_episode` 的行锁 + `vid` 判定（§5.1），**绝不靠 upsert 重置计数**。
- `video.playback_source` 与 `video.uid` 只在 INSERT 时写一次，重导入不得修改。

### 4.14 分类校验

- `mc_id`/`sc_id` 必须能在 `category` 表查到（当前 `CategoryMapper` 只有 `categoryList()`，E3 需补查询）。
- 非法分类 → 阻断（`CATEGORY_UNMAPPED`），不写入任意字符串，也不截断。

### 4.15 URL 安全、长度与封面所有权

#### 4.15.1 协议：只允许 HTTPS

- 播放地址与外链封面**只允许** `https`（scheme 比较前先 `toLowerCase(Locale.ROOT)`）。`http`、`javascript:`、`data:`、`file:`、协议相对 `//host/...`（无 scheme）一律拒绝。
- 不提供"http 例外"开关。若某个源只有 http，该源不可接入，不通过放宽校验解决。

#### 4.15.2 精确域名 + 端口白名单（部署配置）

- 白名单是**部署配置**，不是数据库内容：按 `video_source.code` 配置一组精确的 `(host, port)` 条目（例如 `upstream.example.com:443`）。启动/保存来源时校验白名单存在；白名单为空即拒绝该源的所有地址写入。
- 匹配规则（必须逐条实现）：
  1. host 先做 `toLowerCase(Locale.ROOT)` + IDNA/punycode 规范化，然后与白名单条目**全等比较**；**不允许**通配符、后缀匹配、子域自动放行。
  2. 端口**必须显式匹配**：URL 无端口按 443 处理，并要求存在 `port = 443` 的条目；URL 带非 443 端口时必须有对应 `(host, port)` 条目。
  3. host 不允许是 IP 字面量（IPv4/IPv6 全部拒绝）：白名单只收 DNS 名。
  4. 拒绝 userinfo（`user:pass@`）、拒绝空白/控制字符、拒绝空 host。
- 这条同时约束**后端自己**的访问（E2 受限 HTTP 客户端、连接测试）：同一份白名单、同一套匹配规则，避免"浏览器受限、后端不限"。
- **明确承认的边界**：白名单只能保证"我们只把经批准域名的地址交给浏览器"，**不能**保证该域名解析到公网地址——浏览器自己解析 DNS，域名被劫持或指向内网时我们无法拦截。降低风险的手段只有"白名单由运维显式维护"，不宣称解决了 DNS 重绑定。

#### 4.15.3 后端网络访问（E2 受限客户端，不是读路径）

- 允许发起网络请求的只有：同步任务、`connection-test`。**读路径（§7.1/§7.2）永不探测**。
- 受限客户端必须：解析后拒绝 loopback / 私有 / link-local / CGNAT / ULA / 组播地址；重定向次数上限且**每一跳**重新按 §4.15.1/§4.15.2 校验 scheme/host/port；响应体大小上限、连接/读超时；错误信息不含地址与凭据。
- 明确不声称：这套出口约束能防住 DNS 重绑定（解析结果与实际连接之间的窗口仍是风险），也不构成完整 SSRF 防护；这些都需要在 E2 里单独设计与测试。

#### 4.15.4 长度上限是工程上限

- `video.cover_url` 与 `video_source_series.cover_url_external` 为 `VARCHAR(500)`；外链封面规范化后长度 **> 500 直接拒绝**，不截断。
- 播放地址 DB 用 `TEXT`；对外响应中单条 `url` 上限 **2048** 字符，超出即拒绝该地址（不截断、不写入 `approved_*`）。
- 这两个数字是**我们自己的列宽与响应体工程上限**，不是 HTTP/HLS 协议上限：协议没有这个限制。不得在文档或错误码里宣称"协议规定 2048"。

#### 4.15.5 封面所有权分离

1. **所有权分离**：外链封面只写 `video.cover_url`（`video.cover_key IS NULL`），**绝不**把外部 URL 转成 COS key；本站封面才写 `cover_key`。
2. **不共享可删除所有权**：`deleteVideo` 的 `afterCommit` 会删除 `video.cover_key` 指向的对象。因此**任何两个可删除所有者不得指向同一个 COS 对象**：认领（§7.3.1）总是 `copyObject` 到新 key，禁止把同一 key 同时挂到 `video` 和 `episode`，禁止多个 `video` 行共享一个 key。
3. **禁止后端抓取外链封面**：后端不下载、不转存外链封面（plan §4.8）。

#### 4.15.6 播放地址内部的子资源不在校验范围

HLS 清单里的分片、密钥、子清单 URL 由浏览器按清单内容自行请求，**我们不解析、不校验**。因此"整条播放链路只有 HTTPS"这句话**不成立**，只能说"我们交给浏览器的入口 URL 是 HTTPS + 白名单域名"。这是本契约明确承认的残留风险。

#### 4.15.7 来源凭据（受限引用）

1. DB 只存 `video_source.credential_ref`（引用名），**永不存明文**；本仓库无加密设施，因此也不存"加密后的明文"。
2. `credential_ref` 的取值必须命中**固定的配置白名单**（例如 `app.video-source.credentials.<name>` 下的已配置项）；启动/保存时校验，未命中即拒绝。
3. **不允许客户端任意读取环境变量**：没有任何接口接受"变量名"作为参数去读取环境；`credential_ref` 只是白名单内一个名字，解析只发生在 E2 受限客户端内部。
4. 任何响应（含管理端）都不返回 `credential_ref` 的解析值；管理端最多回显 `credential_ref` 这个名字本身。
5. 日志/审计/异常信息不得包含解析后的凭据。

---

## 5. 并发、事务与租约

> 本节全部是**设计协议**，没有任何一行代码，也没有在真实 MySQL 上验证过锁行为（§11）。协议的目的是让"同一集被导入两次"和"旧执行者覆盖新结果"这两件事在**应用层**不可发生，而不是声称数据库自动保证了它们。

### 5.1 导入事务协议（锁既有 episode 行 → 检查 → 创建 → CAS 绑定）

**上一版的错误**：依赖 `uk_video_source_episode_vid` 与 `uk_video_source_episode_identity` 做最终仲裁。这不成立——两个事务各插一条 `video`（`vid` 不同），再各自 `UPDATE episode SET vid = <自己的>`，唯一键不会冲突，后写者静默覆盖前写者，留下一个孤儿 `video`。

**协议**（HTTP/上游调用一律在事务之外；事务内只有本地 SQL）：

```
BEGIN;
-- 0) 批次前置：按 §5.3 锁 video_source 行 + job 行并校验 (token, epoch, 期限)
-- 1) 串行化点：锁既有 episode 行
SELECT id, series_id, vid, import_state, block_reason, episode_identity, identity_origin,
       duration_seconds, duration_source, cover_object_key, cover_object_uid
  FROM video_source_episode
 WHERE id = :episodeId
   FOR UPDATE;
-- 2) 锁内判定（不得在加锁前判定）
--    2a) series_id 必须等于 job.series_id，否则 ABORT（跨集/跨作品写入一律拒绝）
--    2b) vid IS NOT NULL  -> 已导入：提交空事务并返回既有 vid（幂等，不建 video、不 insertDefault）
--    2c) import_state = 4   -> ABORT 跳过
--    2d) import_state = 3   -> ABORT 冲突待人工
--    2e) import_state = 2   -> 仅当 block_reason 已消除（时长/封面/分类/地址全部就绪）才继续，否则 ABORT
--    2f) duration / cover / category / address 前置条件逐条复核（§4.13.4、§7.3.1、§4.14、§6.2）
-- 3) 创建 video（uid = job.created_by 或 job.owner_uid，playback_source = 2，duration = duration_seconds）
INSERT INTO video (...) VALUES (...);
-- 4) 创建 video_status（普通 INSERT，不 upsert）
INSERT INTO video_status (vid) VALUES (:newVid);
-- 5) CAS 绑定（把第 2 步的判定固定下来）
UPDATE video_source_episode
   SET vid = :newVid, import_state = 1, block_reason = NULL,
       cover_object_key = NULL, cover_object_uid = NULL, cover_object_adopted_at = NULL
 WHERE id = :episodeId
   AND vid IS NULL
   AND import_state IN (0, 2)
   AND series_id = :jobSeriesId;
-- 影响行数必须为 1，否则 ABORT（整笔回滚，刚插入的 video/video_status 一并回滚）
-- 6) 首条 line / play_address（pending_*，不写 approved_*），enabled = 0
-- 7) 审计 INSERT_EPISODE（before/after 只写标量）
COMMIT;
```

关键点（每条都必须写进实现注释）：

1. **第 1 步的行锁是唯一串行化点**。第 2 步的"是否已导入"判断必须**在锁内**完成；在加锁之前先查一次 `vid` 只能当快路径优化，不能作为正确性依据（否则就是上一版的缺陷）。
2. **所有操作在一个事务里**：`video` + `video_status` + `episode` 绑定 + 首条 line/address + 审计。第 5 步 CAS 返回 0 行时，整笔回滚，不会留下"建了 `video` 但没绑定"的孤儿。因此 `uk_video_source_episode_vid` 只是"一个 `vid` 只属于一集"的结构性兜底，**不是**并发仲裁手段。
3. **`uk_video_source_episode_vid` 仍然保留**：它挡住"同一个 `vid` 被两集绑定"这类编程错误，也让"一集一 vid"可被数据库复核。
4. **事务内禁止任何网络调用**：上游分页/详情在事务外取完，事务内只用已取到的本地数据；否则行锁会跨网络持有，锁等待与死锁风险不可控。
5. **影响行数不是 1 就 ABORT**：`videoMapper.insert`、`insertDefault`、CAS 绑定三处都校验 1，与既有 `submitVideo` 的写法一致。
6. **批次前置校验（第 0 步）不是可选项**：没有 §5.3 的 job 校验，一个被接管的旧执行者仍能走到第 1 步并提交业务写。

### 5.2 首次目录行创建：独立的唯一冲突路径（**不套用 T15 形状**）

当 `video_source_episode` 行**尚不存在**时，两个并发同步可能同时 `INSERT` 同一 `(series_id, episode_identity)`。

- 处理方式：`INSERT` 命中 `DuplicateKeyException` 时，**整个事务回滚**，调用方在**新事务**里重新走"锁既有行 → §5.1 协议"这条路径（此时行已存在，必然走 `FOR UPDATE` 分支）。
- **明确不套用 T15 `record()` 的形状**。T15 的"`DuplicateKeyException` 逃出事务回调 → 事务外重读赢家 → 重放赢家结果"之所以成立，是因为那里冲突行**就是**幂等结果行，赢家写下的就是完整结果。这里不成立：
  1. 冲突发生在**目录行**上，不是导入结果行；
  2. 导入没有"按冲突键存储的幂等结果"可重放——结果是一个可能已存在也可能不存在的 `video`；
  3. 目录行的赢家可能**还没开始导入**，重放它会得到半成品状态。
  所以这里的规则是"回滚 + 新事务 + 重新加锁判定"，**不是**"重读赢家并重放"。
- 也**不得**把 `DuplicateKeyException` 在回调内捕获后继续执行：MySQL 里该语句失败后事务仍可用，但"失败语句的部分副作用"不构成可依赖的语义，继续执行会让"这一笔到底写了什么"不可判定。
- 目录行 `INSERT` 与后续绑定**不放在同一个事务**里也可以，但那样必须由调用方保证"先建行、再开新事务走 §5.1"；本契约选择"同一事务内 `INSERT` 后立刻 `SELECT ... FOR UPDATE` 同一行"的写法，减少往返，同时保留上面的回滚重试语义。

### 5.3 每批业务写事务内的租约校验（令牌失效 = 零业务写）

**上一版的错误**：只说"`lease_owner` + `lease_expire_at` CAS；只有持锁者可写检查点"。这只保护了检查点，没有保护业务写，也没有和"接管"互斥。

**每个业务写事务的第一组语句**（在 §5.1 第 1 步之前）：

```sql
BEGIN;
-- A) 源级串行权（顺序见 §5.4：先 video_source，再 job）
SELECT id, active_job_id, active_job_expire_at
  FROM video_source WHERE id = :sourceId FOR UPDATE;
-- 校验：active_job_id = :jobId 且 active_job_expire_at > NOW(3)；否则 ABORT（零业务写）

-- B) 作业租约
SELECT status, lease_owner, lease_epoch, lease_expire_at, series_id, owner_uid, created_by, job_type
  FROM video_source_import_job WHERE id = :jobId FOR UPDATE;
-- 校验（任一不满足即 ABORT，事务内没有任何业务写）：
--   status = 1 (RUNNING)
--   lease_owner = :token
--   lease_epoch = :epoch
--   lease_expire_at > NOW(3)
--   job_type 允许创建 video（IMPORT_EPISODES 且 owner 已定）或允许写目录（SYNC_*）
```

**接管（takeover）必须是一条条件 UPDATE，不能拆成"先 SELECT 再 UPDATE"**：

```sql
UPDATE video_source_import_job
   SET status = 1,
       lease_owner = :newToken,
       lease_epoch = lease_epoch + 1,
       lease_expire_at = TIMESTAMPADD(SECOND, :leaseSeconds, NOW(3)),
       started_at = COALESCE(started_at, NOW(3))
 WHERE id = :jobId
   AND status = 1
   AND lease_expire_at <= NOW(3);
-- 影响 1 行 = 接管成功；0 行 = 别人还持有，返回 409 JOB_LEASE_HELD
```

**为什么这样就"与接管互斥"**：

1. 业务事务从 A/B 两个 `FOR UPDATE` 起手，一直持有 job 行锁到 `COMMIT`。
2. 接管 UPDATE 需要同一行锁，因此它只能等业务事务提交后才执行；执行时它的 `WHERE lease_expire_at <= NOW(3)` 是对**最新已提交行**的条件判断（UPDATE 是当前读，不会读快照），所以只要业务事务在租约内提交，接管就必然 0 行。
3. 反过来，接管一旦提交，旧执行者的下一个事务在 B 步就会看到 `lease_owner`/`lease_epoch` 变化而 ABORT，**在写任何业务数据之前**退出。
4. 唯一剩下的窗口是"旧执行者的事务已经开始、租约随后到期、接管被阻塞在行锁上"：此时旧执行者已经在租约内提交，接管在它之后才生效——业务结果仍然全部来自当时合法的令牌。
5. `lease_epoch` 存在的意义：`lease_owner` 是字符串，同一进程重新取租约时可能复用同一个 token 值；`(token, epoch)` 对不可复现，校验必须两者都比。

**续租**：每轮/每批开始前（在**同一事务**里，顺序同 §5.4）：

```sql
UPDATE video_source_import_job
   SET lease_expire_at = TIMESTAMPADD(SECOND, :leaseSeconds, NOW(3))
 WHERE id = :jobId AND status = 1 AND lease_owner = :token AND lease_epoch = :epoch;
-- 0 行 -> 立即停止，不做任何业务写
```

**期限一律用 DB `NOW(3)`**：应用 JVM 时钟不参与任何期限判断（T14 的教训：JVM 时钟不得成为判定阈值）。心跳同时刷新 `video_source.active_job_expire_at`（同一事务，顺序见 §5.4）。

**令牌失效零业务写**这条要能成立，还依赖两点，必须写进实现：B 步必须是事务里的**第一条业务语句**（任何 `SELECT`/`INSERT` 业务数据都排在它之后）；事务内不得出现"先写再校验"。

### 5.4 源级活动作业约束与全局加锁顺序

**跨 job 同 source 串行**：同一 `video_source` 上不允许两个活动 job 同时写目录。实现用 `video_source` 上的持久指针（§4.1 `active_job_id` / `active_job_expire_at`），而不是内存锁：

```sql
-- 获取（作业启动 / 接管，一个短事务）
BEGIN;
SELECT id, active_job_id, active_job_expire_at FROM video_source WHERE id = :sourceId FOR UPDATE;
UPDATE video_source
   SET active_job_id = :jobId,
       active_job_expire_at = TIMESTAMPADD(SECOND, :sourceLeaseSeconds, NOW(3))
 WHERE id = :sourceId
   AND (active_job_id IS NULL
        OR active_job_expire_at <= NOW(3)
        OR active_job_id = :jobId);
-- 1 行 = 获取/续期成功；0 行 = 409 SOURCE_JOB_BUSY
COMMIT;
```

- 释放：`UPDATE video_source SET active_job_id = NULL, active_job_expire_at = NULL WHERE id = :sourceId AND active_job_id = :jobId`（终态时执行；崩溃时靠过期自然释放，不需要清理任务）。
- 崩溃后：源级指针过期即可被下一个 job 取得；job 行的租约各自独立过期。两者都不是"立刻可接管"，而是"到期后可接管"，这个延迟必须写进运维说明。

**全局加锁顺序**（任何事务需要多把锁时必须按此**递增**顺序获取；可以跳过，但不能回头）：

| 顺序 | 对象 | 谁需要 |
|---|---|---|
| 1 | `video_source`（按 `id`） | 作业启动/接管、每批业务事务、心跳 |
| 2 | `video_source_import_job`（按 `id`） | 每批业务事务、接管、心跳 |
| 3 | `video_source_episode`（按 `id`） | 导入事务（§5.1） |
| 4 | `video_source_episode_candidate`（按 `id`） | 人工绑定 |
| 5 | `video_source_line` / `video_source_play_address`（按 `episode_id` / `line_id`） | 导入、同步、批准 |
| 6 | `video` / `video_status`（新插入行） | 导入事务（INSERT 不取既有行锁） |
| 7 | `video_source_audit`（INSERT） | 所有写操作，最后追加 |

- **一个事务最多锁一行 `video_source_episode`**：每次导入只处理一集，这消除了最主要的多行交叉持锁场景。
- **禁止**在已持有顺序 3 的锁之后再回头去锁顺序 1/2：这就是死锁的典型来源。
- 锁等待超时必须显式设短（每会话 `innodb_lock_wait_timeout`），并且 1205（锁等待超时）/1213（死锁牺牲者）都以**有界次数 + 退避**重试整笔事务，重试仍在同一 `(token, epoch)` 下；重试耗尽即把 job 标 `FAILED` 并记安全码，不半途放弃持锁。

### 5.5 失败分类与重试

| 情况 | 分类 | 行为 |
|---|---|---|
| 1205 锁等待超时 / 1213 死锁 | 可重试 | 回滚整笔，按 §5.4 有界退避重试；令牌不变 |
| 唯一键冲突（目录行） | 可重试 | §5.2：回滚 + 新事务重新加锁判定 |
| CAS 影响行数 0（绑定/批准/候选） | 不可重试 | 409 + 安全码，交给上层重新读 |
| job 令牌/状态/期限校验失败 | 不可重试（本执行者终止） | 零业务写退出，不标 job 失败（job 可能已被别人接管） |
| 时长/封面/分类/地址前置条件缺失 | 不可重试 | `import_state = 2` + 固定 `block_reason`，计入 `skipped_count` |

---

## 6. 审核与可见性边界

### 6.1 同步**绝对不覆盖**的字段（A19）

对已存在的 `video` 行，同步/重导入**不得** UPDATE：

- `status`（审核状态）、`visibility`、`deleted_at`、`uid`、`source_type`、`playback_source`
- `duration`、`title`、`description`、`tags`、`mc_id`、`sc_id`、`cover_url`、`cover_key`
- `video_status` 全部八计数器、`user_video` 全部列、`video_interaction_request`/`video_interaction_window` 全部行

另外：
- **不复活** `deleted_at IS NOT NULL` 的 `vid`：命中已删除视频的剧集，`import_state = 4 (跳过)`，不新建 `vid`、不清空 `deleted_at`。
- 人工下架（`status=3` 或 `visibility=1`）不被同步恢复成公开。
- 人工改过的标题/简介/分类不被同步回滚（同步只写 `*_snapshot` 与线路/地址的 `pending_*`）。
- 已导入剧集的上游标题变化只更新 `title_snapshot`，不动 `video.title`。

### 6.2 线路地址的批准模型（地址变更**不得**自动绕过审核）

**上一版的错误**：写成"同线路新地址 → 直接 UPDATE 生效 + 审计，不触发重新审核"。这等于地址变更自动绕过审核，与本轮要求冲突。

#### 6.2.1 状态形状

每条线路只有一行地址记录（`UNIQUE (line_id)`），同时容纳：

- **当前已批准值**：`approved_url` / `approved_url_hash` / `approved_format` / `approved_at` / `approved_by` / `approved_revision`；
- **至多一个待审候选**：`pending_url` / `pending_url_hash` / `pending_format` / `pending_seen_at` / `pending_revision`。

这不是"无限历史库"：没有第二张表、没有版本链、没有清晰度/备用地址库（A14）。旧值在被新值批准时**直接丢弃**，只在审计表留 `url_hash` 标量。

#### 6.2.2 同步写入（只写 `pending_*`）

```sql
-- 只在候选哈希变化时写；已批准值一个字都不动
UPDATE video_source_play_address
   SET pending_url = :url, pending_url_hash = :urlHash, pending_format = :format,
       pending_seen_at = NOW(3), pending_revision = pending_revision + 1
 WHERE line_id = :lineId
   AND (pending_url_hash IS NULL OR pending_url_hash <> :urlHash);
-- 影响 0 行 = 候选未变，只刷新 last_seen_at（见下）
UPDATE video_source_play_address SET last_seen_at = NOW(3) WHERE line_id = :lineId;
```

- 同步**永不**写 `approved_*`，**永不**写 `video.status`/`video.visibility`，**永不**写 `verify_status`。
- 同步发现"地址消失"：只停止刷新 `pending_seen_at`，**不 DELETE、不清空**任何列（删除会让已发布剧集在无人决定的情况下变成不可播放）。

#### 6.2.3 批准（管理员显式动作，带 revision CAS）

```sql
UPDATE video_source_play_address
   SET approved_url = pending_url,
       approved_url_hash = pending_url_hash,
       approved_format = pending_format,
       approved_at = NOW(3),
       approved_by = :operatorUid,
       approved_revision = pending_revision,
       pending_url = NULL, pending_url_hash = NULL, pending_format = NULL,
       pending_seen_at = NULL,
       verify_status = 0, verified_url_hash = NULL, verified_at = NULL
 WHERE line_id = :lineId
   AND pending_url_hash = :expectedHash
   AND pending_revision = :expectedRevision;
-- 1 行 = 批准成功；0 行 = 409 ADDRESS_CANDIDATE_CHANGED（候选在读取与写入之间变了）
```

- `expectedHash` / `expectedRevision` 来自管理端读取（`GET .../address`），这是**唯一**的乐观并发依据；不用"最后写入者赢"。
- 批准同时**清空核验结论**（`verify_status = 0`），见 6.2.4。
- 写审计 `APPROVE_ADDRESS`，`before/after` 只写 `url_hash` + `approved_revision`。
- **批准地址不改变 `video.status`**：地址审核是**地址级**状态机，视频审核状态既不因此重新进入审核，也不因此被下架。两个状态机互不触发，这一点必须写在实现注释里，避免以后有人"顺手同步一下审核状态"。
- **不依赖任何上游协议**：我们**不**假设上游提供"官方线路/推荐线路/版本号/白名单"语义，批准完全是本地状态。任何"因为上游标记为官方所以自动批准"的实现都属于违约。

#### 6.2.4 核验结论不跨地址复用

- `verify_status` 只在 `verified_url_hash = approved_url_hash` 时对当前地址有效。
- 批准新地址时强制清零（6.2.3 的 UPDATE 已包含），因此**旧地址的 `OK`/`FAILED` 永远不会被新地址沿用**。
- 读取侧：`verified_url_hash <> approved_url_hash` 的结论按 `UNKNOWN` 处理（不阻断播放，见 §7.1.1 第 9 步与 §7.1.5）；只有 `verify_status = 2 (FAILED)` 且 `verified_url_hash = approved_url_hash` 才阻断。
- 本切片没有核验任务的写入者（§4.6），所以实际恒为 `UNKNOWN`。

#### 6.2.5 新线路默认未启用/未批准

- 同步新建的 `video_source_line` 一律 `enabled = 0`；地址只有 `pending_*`，没有 `approved_*`。
- 因此新线路**默认不可播放**：必须由管理员先 `POST .../lines/{lineId}/enabled`（`ENABLE_LINE` 审计），再 `POST .../lines/{lineId}/address/approve` 批准首个地址，两者都完成后才可能出现在 `lines[]` 的可选集合里。
- 后果必须承认：一条刚导入、已过审的剧集可能长期 `playable=false / NO_PLAYABLE_ADDRESS`，直到管理员完成上述两步。这不是缺陷，是审核边界。

#### 6.2.6 其余同步行为（固定）

| 情况 | 行为 |
|---|---|
| 同线路、候选哈希未变 | 只更新 `last_seen_at` |
| 同线路、新哈希 | 覆盖 `pending_*` 并 `pending_revision + 1`（不碰 `approved_*`） |
| 地址消失 | 不更新 `pending_seen_at`；不删除、不清空 |
| 新线路出现 | 插入 `video_source_line`（`enabled = 0`）+ 一行只有 `pending_*` 的地址 |
| 线路消失 | `enabled = 0`，不删除行（保留审计与历史可读性） |
| `verify_status` | 同步与只读接口都不写、不探测 |

### 6.3 来源停用（A21）

- `video_source.enabled = 0`：
  - `GET /api/videos/{vid}/playback` → `playable = false`，`reason = SOURCE_DISABLED`（§7.1.1 第 8 步）。
  - 导入任务拒绝新建 `video`（已存在的 `video` 不删除、不下架、不改 `status/visibility`）。
  - 同步任务拒绝启动（新建 job 直接 409 + 安全码）。
- **停用不能撤销已经发出的地址**：`enabled=0` 只影响"之后的新响应"。任何已经交给浏览器的 URL 仍可继续播放，浏览器也可能继续请求清单/分片；本仓库不做封禁、不改 ACL、不私有化 COS 对象（A8）。不得宣称"停用即下架/即失效"。
- 停用**不**是"撤销 COS 对象"、不是"批量下架"，也不是删除线路数据。

### 6.4 执行器隔离

同步/导入任务使用独立、有限并发的执行器，**不得**复用 T06 的 `videoStatusRelayScheduler` / `videoStatusFlushScheduler` / `videoStatusRecoveryScheduler` / T15 的 `videoInteractionCleanupTaskScheduler`。源站失败时本地目录继续服务（已导入的 `vid` 照常可播放）。

---

## 7. API 契约

### 7.1 `GET /api/videos/{vid}/playback`（E5 实现）

- 权限：`@OptionalLogin`（可选登录）。GET 只读。
- **不写** MySQL、**不写** Redis、**不**计播放、**不**写 `user_video`、**不**触发统计事件、**不**做任何网络探测。
- 响应头必须包含 `Cache-Control: no-store`（地址是逐次授予、可能变化，不得被浏览器或中间代理缓存）。注意：`no-store` 只阻止缓存，**不能**让地址变成秘密——浏览器本来就拿到了它。
- 计数仍由既有 `POST /api/videos/{vid}/play-count`（需 `Idempotency-Key`）负责，前端在真正起播时调用。

#### 7.1.1 校验顺序（严格按此顺序，码不新造）

| 顺序 | 条件 | 结果 |
|---|---|---|
| 1 | `vid == null \|\| vid <= 0` | 400 `视频参数不合法` |
| 2 | `lineId` 存在但不是正整数（非数字、`<= 0`、溢出） | 400 `播放线路不合法` |
| 3 | `videoMapper.selectPublishedByVid(vid) == null`（未过审 / 已删除 / 不存在同码） | 404 `视频不存在` |
| 4 | `video.visibility == 1 && video.uid != currentUserId` | 403 `你无权查看此视频` |
| 5 | `lineId` 存在但不属于本 `vid` 的线路集合（含：本 `vid` 无剧集绑定、线路属于别的剧集、线路已禁用） | 400 `播放线路不合法` |
| 6 | `video.playback_source == 1 (COS)` | 200 + `playable=false`，`reason=NOT_BOUND` |
| 7 | 按 `vid` 查 `video_source_episode`：无行 | 200 + `playable=false`，`reason=NOT_BOUND` |
| 8 | `video_source.enabled = 0` | 200 + `playable=false`，`reason=SOURCE_DISABLED` |
| 9 | 无已启用线路 / 线路无已批准地址 / 当前批准地址的核验为 `FAILED` | 200 + `playable=false`，`reason=NO_PLAYABLE_ADDRESS` |
| 10 | 正常 | 200 + `playable=true`，`reason=OK` + 地址 |

- **`lineId` 的 400 必须稳定**：第 5 步对所有携带 `lineId` 的请求都执行，且**在**任何 `playable` 计算之前。也就是说，同一非法 `lineId` 在"来源已停用""地址缺失""地址未批准"等任何状态下都必须返回同一个 400 `播放线路不合法`，不能因为这些状态而变成 200 + `playable=false`，也不能被静默忽略而回落到默认线路。第 5 步在第 3/4 步之后，因此不存在的 `vid` 仍返回 404（不构成探测）。
- **不调用 `PublishedVideoGuard`**：它不做私密可见性判定，且它的 500「时长数据异常」分支不属于只读播放语义；播放读路径也不依赖 `duration`（导入已保证 `duration > 0`）。这是"不改 guard"而不是"绕开 guard"——六个互动入口仍然只走 guard。
- **管理员不绕过**：第 4 步只比较 `video.uid`，与 `getVideoDetail` 完全一致（A9）。管理员要看别人的私密剧集线路，**不在本契约范围**，需另立受审计的管理端只读接口；不得靠放宽本接口实现。
- **无 COS 回退**：第 6/7 步就是历史 COS 记录的归宿（`playback_source = 1`，且它们没有任何 `video_source_episode` 行）→ `NOT_BOUND`。

#### 7.1.2 成功响应（`data` 内）

```json
{
  "vid": 1024,
  "playable": true,
  "reason": "OK",
  "format": "HLS",
  "url": "https://upstream.example.com/hls/1024/index.m3u8",
  "expiresAt": null,
  "lineId": 88,
  "lineLabel": "线路一",
  "lines": [
    { "lineId": 88, "lineLabel": "线路一", "available": true },
    { "lineId": 89, "lineLabel": "线路二", "available": false }
  ],
  "series": {
    "episodeNo": 3,
    "totalVisibleEpisodes": 12,
    "prevVid": 1023,
    "prevPlayable": true,
    "nextVid": 1025,
    "nextPlayable": false
  }
}
```

- `url` **只给当前选中线路的一条已批准地址**；`lines[]` 只给 `{lineId, lineLabel, available}`，不批量吐全部原始地址。`available` 的含义固定为"该线路已启用且存在已批准地址（且该批准地址的核验不是 `FAILED`）"，与 §7.1.1 第 9 步同一套规则。
- 不返回：`source_id`、`base_url`、`credential_ref`、上游作品/剧集原始 ID、`url_hash`、`verify_status`、`pending_*`、COS key。
- `expiresAt` 恒为 `null`（直链无本站签名）；将来若有签名地址，再定义。
- `series` 字段的可见性规则见 §7.1.3。

#### 7.1.3 剧集导航：与详情完全一致的可见性过滤（不得泄露）

- `prevVid` / `nextVid` 只返回**相邻且对当前调用者可见**的剧集 `vid`；`totalVisibleEpisodes` 只统计**可见**剧集数。
- "可见"= 与 §7.1.1 第 3/4 步完全相同的谓词：`video.status = 1 AND video.deleted_at IS NULL AND (video.visibility = 0 OR video.uid = :currentUserId)`。必须由**同一条**过滤 SQL 产出（`video_source_episode` 与 `video` 的 join，谓词与 `selectPublishedByVid` 的语义逐字对齐），不得用 `import_state = 1` 之类的本地标记代替。
- **禁止**返回不可见剧集的 `vid`，也**禁止**返回任何"不可见集数量"（例如 `hiddenCount`、`totalEpisodes` 之类的全量计数）。`totalVisibleEpisodes` 是可见计数，不泄露隐藏行。
- `episodeNo` 来自上游 `episode_no`（展示元数据），不是本地行数：编号出现空档只说明上游编号不连续，**不**返回本地隐藏集的数量或 `vid`。
- **不声称 `prevVid`/`nextVid` 一定可播放**：`prevPlayable`/`nextPlayable` 用的是同一套**数据库规则**（§7.1.1 第 6–9 步），它只说明"按我们的数据看应当可播放"，不保证上游地址当时可用（地址可能已过期、源站可能 404）。字段名必须保持 `playable` 语义（"按同一规则判定"），不得在文档里写成"保证可播放"。
- 排序：`ORDER BY (episode_no IS NULL) ASC, episode_no ASC, id ASC`（NULL 排最后），`prev`/`next` 在该序上取相邻的**可见**行。

#### 7.1.4 不可播放响应（`data` 内）

```json
{ "vid": 1024, "playable": false, "reason": "NOT_BOUND", "format": null, "url": null,
  "expiresAt": null, "lineId": null, "lineLabel": null, "lines": [], "series": null }
```

- `reason` 取值固定集合：`OK`、`NOT_BOUND`、`SOURCE_DISABLED`、`NO_PLAYABLE_ADDRESS`。新增值必须改本契约。
- **HTTP 200 + `playable=false`** 是**提议（P10，未批准）**，不是笔误：资源存在、请求合法，"不可播放"是该资源的一种正常状态；用 409/404 会让前端无法与真实错误区分。若父代理要求改为 4xx，请只改这一处并同步 `docs/api.md`。

#### 7.1.5 线路选择（确定性，无网络）

- 默认：在 `line.enabled = 1` 且存在 `approved_url`（且该批准地址的核验不是 `FAILED`）的线路中，按 `line_order ASC, line.id ASC` 取第一条。
- 可选查询参数 `lineId`：见 §7.1.1 第 2/5 步；非法一律 400 `播放线路不合法`，不返回 403/404（避免用线路 ID 探测他人资源）。
- `verify_status = 0 (UNKNOWN)` **不阻断**播放：本接口不探测，`UNKNOWN` 只表示"未验证"，不是"不可用"。

### 7.2 `GET /api/videos/{vid}/episodes`（有界选集列表，E5 实现）

**上一版把它砍掉了**（理由是"避免 `vid` 枚举"）。这是错的：没有选集就没有可用的剧集导航。正确做法是**有界 + 与详情一致的可见性**，而不是删功能。

- 权限：`@OptionalLogin`；GET 只读；不写 MySQL/Redis、不计播放、不探测；响应头同样 `Cache-Control: no-store`。
- 查询参数：`cursor`（不透明游标，来自上一页 `nextCursor`）、`size`（1..50，默认 20，超出 → 400 `分页参数不合法`）。
- 前置校验与 §7.1.1 第 1/3/4 步完全相同（`vid` 非法 400 / 不存在 404 / 私密非本人 403）。
- 本 `vid` 无剧集绑定（含 `playback_source = 1`）→ 200 + `items = []`、`hasMore = false`（不返回 404，避免把"没绑定"和"不存在"混在一起）。
- 返回：

```json
{
  "items": [ { "vid": 1023, "episodeNo": 2, "playable": true } ],
  "hasMore": true,
  "nextCursor": "3_1023"
}
```

- `items[]` 只包含**可见**剧集（同一谓词），`playable` 用同一套 DB 规则（§7.1.1 第 6–9 步）。
- 游标 = 上一页最后一行的 `(episode_no, id)` 编码；排序同 §7.1.3。keyset 分页，不使用 `OFFSET`。
- **不返回总数**：本接口没有任何 count 字段，因此无法从它推断不可见集数量。
- 每条只给 `vid` / `episodeNo` / `playable`；不给标题、地址、线路、上游 ID。

### 7.3 管理员接口（E3 实现；此处只定形）

所有新管理端路由必须挂在 `/api/admin/**` 下，才能自动获得 `AdminInterceptor` 的 `@AdminOnly` 判定（已核对 `WebMvcConfig`）。写操作一律 POST（项目约定，不用 PUT/DELETE）。所有写操作的 `operator_uid` 一律取自登录上下文，**不接受**请求体中的 uid。

#### 7.3.1 `POST /api/admin/video-sources/episodes/{episodeId}/cover` —— 承接临时封面（A16）

关闭投稿后，`POST /api/videos/cover`（`VideoServiceImpl#uploadCover`，仅图片、写 `upload_temp_file` `file_type=2` `status=0` `expire_at=+1天`、前缀 `temp/covers/{uid}/`）会失去唯一消费者。本接口给它一个**明确消费者**，并且**只写 episode 自己的封面字段**。

- 入参：`{ "tempCoverKey": "temp/covers/7/20261023/xxx.png" }`
- 校验（逐条复用既有 `submitVideo`/`validateTempFile` 的规则，不新造）：
  1. key 以 `temp/covers/{operatorUid}/` 开头；
  2. `uploadTempFileMapper.selectByObjectKey(key)` 存在；
  3. `upload_temp_file.uid == operatorUid`（**归属校验**）；
  4. `file_type == 2`；
  5. `status == 0`（未提交/未清理）；
  6. `expire_at > now`（未过期）；
  7. `fileStorageService.objectExists(tempCoverKey)` 为 true（**唯一允许的 COS 调用**，且只读）。
- 写入顺序（CAS 认领 + 复制 + 单事务落库）：
  1. `copyObject(tempCoverKey → buildFormalCoverKey(operatorUid, tempCoverKey))` → 得到**新的、唯一的**正式 key；
  2. `markSubmitted(tempCoverKey, operatorUid)`（`status 0→1`，**CAS**）；影响行数 ≠ 1 → 说明已被别人认领 → 回滚，并尽力删除刚复制的正式对象；
  3. 一个 MySQL 事务：
     - **未导入**（`vid IS NULL`）：`UPDATE video_source_episode SET cover_object_key = :newKey, cover_object_uid = :operatorUid, cover_object_adopted_at = NOW(3) WHERE id = :episodeId AND vid IS NULL AND (cover_object_key IS NULL OR cover_object_key <> :newKey)`；影响 1 行 = 成功；0 行 = 409。
     - **已导入**（`vid IS NOT NULL`）：`UPDATE video SET cover_url = :newUrl, cover_key = :newKey WHERE vid = :vid AND cover_key = :oldKey`（`oldKey` 可为 NULL，用 `<=>` 比较）；影响 1 行 = 成功；0 行 = 409。**同时**把 episode 的 `cover_object_*` 保持为 NULL（已导入行不应再持有暂存所有权）。
     - 写审计 `ADOPT_COVER`（未导入）或 `REPLACE_COVER`（已导入），`before/after` 只写 key 的 `SHA-256` 或 NULL，**不写 key 原文**；
  4. `afterCommit` 删除临时对象；**并且**删除"刚刚不再拥有"的旧对象（未导入：旧 `cover_object_key`；已导入：旧 `video.cover_key`）。每次只删一个我们刚刚放弃所有权的对象。
- **绝不触碰 `series.cover_url_external`**：上一版在这里写"`series.cover_url_external = null` + 暂存 key"，既没有真实存在的暂存字段，又会把整部作品的外链封面清空。本轮修正为：认领只写 `video_source_episode.cover_object_*`，`series.cover_url_external` 只由同步写（§4.2）。
- **同一 COS 对象不共享可删除所有权**：见 §4.3 与 §4.15.5；`copyObject` 保证每次认领得到新 key；导入时在同一个事务里把所有权从 episode 列移交给 `video.cover_key`。
- **COS 与 MySQL 非原子：四种边界必须写进实现注释，且不得声称补偿可靠**
  1. 复制成功 → 事务提交 → `afterCommit` 删临时对象失败：临时对象成为**孤儿**（DB 无引用）。只记录日志，不做重试，不声称会自愈。
  2. 复制成功 → 事务回滚（CAS 失败/校验失败/DB 异常）→ 尝试删除刚复制的正式对象；这次删除**也可能失败**，于是正式对象成为孤儿。
  3. 进程在"复制成功、事务未提交"之间崩溃 → 临时对象与新正式对象都成为孤儿。
  4. 进程在"事务已提交、`afterCommit` 未执行"之间崩溃 → 临时对象成为孤儿。
  - 结论：**没有** COS↔MySQL 的原子性，**没有**可靠的补偿机制；孤儿对象需要带外清扫，本切片不提供，也不声称存在。
- **不动**头像（`avatar/`）与背景（`background/`）的任何代码路径。
- **不扩大为全局清理项目**：本接口不新增定时任务、不做全表扫描、不调用 `selectExpiredTempFiles`/`markCleaned`（这两个方法当前无调用者，见 §11）。

#### 7.3.2 `POST /api/admin/video-sources/episodes/{episodeId}/duration` —— 补齐可信时长

- 入参 `{ "durationSeconds": 1425.0 }`；校验 `Double.isFinite(d) && d > 0`，否则 400。**没有 86400 上限**（§4.13.4）。
- **已导入的剧集一律拒绝**：`vid IS NOT NULL` → 409 `EPISODE_ALREADY_IMPORTED`，**零写入**，尤其**不得**把 `import_state` 改回 0。理由：导入后 `video.duration` 是唯一真值，让 episode 也继续可写就出现双真值。需要修正已导入时长属于独立变更（要同时定义 `video.duration` 与 `user_video.play_time` 的关系），不在本契约。
- 未导入时：`UPDATE video_source_episode SET duration_seconds = :d, duration_source = 2, duration_verified_by = :operatorUid, duration_verified_at = NOW(3), import_state = CASE WHEN import_state = 2 AND block_reason IN ('DURATION_UNKNOWN','DURATION_UNTRUSTED') THEN 0 ELSE import_state END, block_reason = CASE WHEN block_reason IN ('DURATION_UNKNOWN','DURATION_UNTRUSTED') THEN NULL ELSE block_reason END WHERE id = :episodeId AND vid IS NULL AND import_state IN (0, 2)`；影响 0 行 → 409（状态已变，例如并发导入）。
- 写审计 `SET_DURATION`（`before/after` 只写 `duration_seconds`）。
- 补齐后剧集回到 `import_state = 0`，由导入任务（E4）创建 `video`。

#### 7.3.3 线路与地址

- `POST /api/admin/video-sources/lines/{lineId}/enabled`：`{ "enabled": true|false }`；`ENABLE_LINE` / `DISABLE_LINE` 审计。启用前必须已有批准地址，否则 409 `ADDRESS_NOT_APPROVED`。
- `GET /api/admin/video-sources/lines/{lineId}/address`：只读，返回 `{approvedFormat, approvedUrlHash, approvedRevision, approvedAt, pendingFormat, pendingUrlHash, pendingRevision, pendingSeenAt}`；**不回传原始 URL**（管理端要看地址由人工在受审计页面单独读取，本契约不定义该页面）。
- `POST /api/admin/video-sources/lines/{lineId}/address/approve`：入参 `{ "expectedHash": "...", "expectedRevision": 3 }`，按 §6.2.3 的 CAS 执行，审计 `APPROVE_ADDRESS`。
- 注意：`GET .../address` 不返回原始 URL，因此"批准前人工看一眼地址"需要另立接口；这是本契约的已知缺口（§11）。

#### 7.3.4 来源 / 分类映射 / 候选 / 导入任务

- `POST /api/admin/video-sources`（创建）、`POST /api/admin/video-sources/{id}/enabled`（启停）、`GET /api/admin/video-sources`（列表，**不回传 `credential_ref` 的解析值**，也不回传 `base_url`）。
- `POST /api/admin/video-sources/{sourceId}/category-mappings`、`GET .../category-mappings`。
- `POST /api/admin/video-sources/{sourceId}/connection-test`（唯一允许发起上游网络请求的管理端动作，走 E2 受限客户端与 §4.15 白名单；失败只回安全错误码）。
- `GET /api/admin/video-sources/{sourceId}/episode-candidates`（§4.13.3 第 2 步，只读）。
- `POST /api/admin/video-sources/episode-candidates/{candidateId}/bind`（§4.13.3 第 3 步）。
- `POST /api/admin/video-sources/import-jobs`（创建任务；`created_by` 取自登录上下文；`job_type='IMPORT_EPISODES'` 时 `owner_uid = created_by`）、`GET /api/admin/video-sources/import-jobs/{jobId}`（进度，只回安全码与计数）。
- 以上全部 `@AdminOnly`、全部参数校验、**GET 只读**。

### 7.4 字段移除（E5 执行，A6）

| 位置 | 现状（已核对） | 变更 |
|---|---|---|
| `VideoDetailVO.videoUrl` | `VideoConverter#toVideoDetailVO` 中 `vo.setVideoUrl(video.getVideoUrl())` | 删除字段与赋值 |
| `AdminVideoDetailVO.videoUrl` | `VideoConverter#toAdminVideoDetailVO` 中 `vo.setVideoUrl(video.getVideoUrl())` | 删除字段与赋值 |
| `docs/api.md` | 三处示例含 `videoUrl`（`:1079`、`:1208`、`:1804`） | 同步删除 |

- 影响：**历史 COS 视频在接口层变成不可播放**（它们的 `video_url` 仍在库里，但不再被任何接口输出），这正是 A6/A7/A8 的边界。COS 对象本身仍公开可读，本仓库不做封禁/私有化。
- `VideoSubmitVO.videoUrl` 随投稿入口关闭一起废弃。
- 管理员此后**无法**从任何接口看到历史 COS 地址。若后续需要，必须新增独立的受审计只读接口，**不得**恢复详情字段。
- `selectPublishedList`（含 `video_url`）与 `VideoMapper#updateVideo`（可改 `video_url`）当前**无调用者**（已核对），保持死代码不动，E6 再决定是否删除。

---

## 8. 历史数据与不可播放语义（E0 结论）

| 对象 | 处置 |
|---|---|
| 历史 `video` 行（COS） | **不删除、不回填**；`video_url/video_key/cover_key/cover_url/duration` 的值保持不变（仅允许迁移放宽 NULL 与新增 `playback_source` 默认 1） |
| 历史行的 `playback_source` | 迁移后为 1（`ADD COLUMN ... DEFAULT 1`，无 `UPDATE`）；此后不再变更 |
| 历史行的 `status`/`visibility`/`deleted_at` | 不变（不批量下架） |
| 历史行的接口可见性 | 仍出现在 feed / 详情；`GET .../playback` 返回 `playable=false, reason=NOT_BOUND` |
| 历史行的 `videoUrl` 输出 | **停止**（普通详情 + 管理员详情，§7.4） |
| COS 对象本身 | 仍公开可读；本仓库不做封禁、不改 ACL、不转私有（A8）。已知 URL 的人仍能播放，这是本契约**明确承认**的边界 |
| 历史行能否变成外部源剧集 | **不能**。`video_source_episode.vid` 只指向新建的 `vid`；禁止"同一 `vid` 从 COS 迁移到外链"（否则删除时 `video_key` 该不该删会变得不可判定） |

---

## 9. E1 最小切片（SQL-only，无生产 Java）

**E1 只做数据层，不写任何业务代码**，避免投机性脚手架（实体/Mapper 在各自第一个真实调用者切片里落地并配测试）。

### 9.1 交付物

1. `database/migrations/e1-external-video-source-schema.sql`
   - 8 张新表（`video_source`、`video_source_series`、`video_source_episode`、`video_source_episode_candidate`、`video_source_line`、`video_source_play_address`、`video_source_category_map`、`video_source_import_job`）＋ `video_source_audit`，全部 `CREATE TABLE IF NOT EXISTS`；全部 `utf8mb4_bin` 身份列；唯一键/外键/CHECK 严格按 §4.1–§4.9 与 §4.12。
   - §4.11 的 `ALTER TABLE video`：两条独立语句（先 `ADD COLUMN playback_source`，再 `MODIFY COLUMN` 三列）。
   - 文件末尾附 `information_schema` 核对查询（列、唯一键、外键、CHECK 是否存在，与 T15 迁移风格一致）。
   - **禁止**出现数据修改语句：不得有 `UPDATE`/`DELETE`/`DROP`/`TRUNCATE`/回填。（检测方式见 §10.1，必须语句级，不能整文件禁关键字。）
2. `database/feibijiubi.sql` 同步：新增 9 张表的 `CREATE TABLE`，`video` 表三列改为 `NULL` 并新增 `playback_source`（镜像迁移结果，新库直接正确）。
3. 本轮**不执行**迁移、不连库、不启动基础设施。

### 9.2 迁移纪律（不得夸大）

1. **`CREATE TABLE IF NOT EXISTS` 不是结构校验**：表已存在时它是 no-op，**不会**比对列、索引、外键、排序规则。因此迁移必须附 `information_schema` 核对查询，运维执行后要人工比对输出；`database/feibijiubi.sql` 只对**新建库**负责。不得把 `IF NOT EXISTS` 描述成"幂等且验证既有结构正确"。
2. **`ALTER` 可重复执行 ≠ 线上无锁**：`MODIFY COLUMN` 通常需要重建表（COPY/INPLACE）并取元数据锁；`ADD COLUMN` 只有在追加到表尾且版本支持时才可能走 INSTANT；`ADD CONSTRAINT FOREIGN KEY` 需要校验既有行。这些在本轮**全部未测量**，执行时间与锁影响**未验证**。迁移文档必须写"需在低峰窗口执行并预先评估锁"，不得写"无锁"。
3. **CHECK 的强制依赖 MySQL 8.0.16+**；实例版本未验证。若不支持，CHECK 被解析但忽略，退化到服务层校验，风险是直连 SQL 可绕过（§11）。
4. 复合外键依赖被引用列上的唯一键（`uk_video_source_series_source_id`），建表顺序必须先 series 后 job；循环引用（`video_source.active_job_id`）故意不建外键（§4.1）。

### 9.3 E1 验收点（可离线判定）

- 一集一个 `vid`：`uk_video_source_episode_vid`（并注明它**不是**并发仲裁手段，§5.1）。
- 稳定身份唯一约束：`uk_video_source_series_identity`、`uk_video_source_episode_identity`、`uk_video_source_line_key`、`uk_video_source_play_address_line`、`uk_video_source_episode_candidate`。
- 归属只存在于 job：`created_by` / `owner_uid` + CHECK；`series` 无归属列。
- 播放来源独立：`video.playback_source` 存在且默认 1，迁移中无 `UPDATE`。
- `source_type` 未被复用为存储/来源类型（无任何新表写 `source_type`）。
- 历史数据零改动（无 `UPDATE`/`DELETE`）。

---

## 10. 纯离线测试方案（无 DB / 无网络 / 无基础设施）

全部在 `src/test/java` 内、只读文件或用 Mockito，可在普通 `./mvnw test` 下运行（无需 `-DvideoStatusIT`）。本轮**未运行**任何测试（§11）。

| 切片 | 测试类（拟） | 覆盖 |
|---|---|---|
| E1 | `ExternalVideoSourceSchemaMigrationTests` | 语句级解析（§10.1）：每张表都是 `CREATE TABLE IF NOT EXISTS`；必需唯一键齐全；身份列 `utf8mb4_bin`；`episode_identity`/`upstream_series_id` NOT NULL；`vid` 唯一；FK 目标正确；CHECK 存在；**语句级**无 `UPDATE`/`DELETE`/`DROP`/`TRUNCATE` |
| E1 | `ExternalVideoSourceSchemaMirrorTests` | 迁移文件与 `database/feibijiubi.sql` 的表名/列名/唯一键集合一致（防漂移）；`video` 三列为 `NULL` 且存在 `playback_source` |
| E1 | `VideoSchemaRelaxationTests` | `video` 只放宽 `video_url`/`video_key`/`cover_key`；`duration` 仍 `NOT NULL`、`cover_url` 仍 `NOT NULL`；`ADD COLUMN playback_source` 是独立语句、默认 1、无 `AFTER`；无默认值改写与回填 |
| E1 | `VideoSourceConstraintDeclarationTests` | §4.12 表格里的每条 FK/CHECK 都能在迁移文本中找到；`series` 不含 `import_uid`；`active_job_id` 明确无 FK（防"偷偷补一个循环 FK"） |
| E2 | `ExternalSourceAdapterFixtureTests` | 标注 fixture（JSON/XML）：分类/分页/详情/线路/剧集解析；缺字段、畸形、重复页、未知格式显式拒绝 |
| E2 | `ExternalSourceUrlValidatorTests` | scheme 只 `https`；host:port 精确白名单（无端口=443 需 443 条目、通配/后缀拒绝、IP 字面量拒绝）；userinfo/控制字符拒绝；`cover_url > 500` 拒绝且不截断；播放 URL `> 2048` 拒绝 |
| E2 | `ExternalSourceCredentialRedactionTests` | 任何日志/异常/响应文本都不含解析后的凭据；`credential_ref` 白名单外拒绝 |
| E3 | `VideoSourceAdminControllerTests`（Mockito） | `@AdminOnly` 存在；GET 只读 / POST 写入；创建来源不回显凭据解析值；分类映射非法 `mc_id/sc_id` → 400；`operator_uid` 只来自登录上下文 |
| E3 | `VideoSourceCoverAdoptionTests`（Mockito） | 七条校验逐条失败矩阵（前缀/不存在/uid 不符/类型不符/status≠0/已过期/`objectExists=false`）；`markSubmitted` CAS 失败时回滚且尽力删新对象；未导入写 episode 列、已导入写 `video.cover_key` 两条分支；**`series.cover_url_external` 不被写**；不触碰 avatar/background |
| E3 | `VideoSourceDurationCompletionTests`（Mockito） | 已导入 → 409 且零写入、`import_state` 不变；未导入 → 写 2/`verified_by/at`；CAS 0 行 → 409；无 86400 上限（`1e9` 合法）；`NaN`/`Infinity`/`0`/负数 → 400 |
| E4 | `VideoSourceImportServiceTests`（Mockito） | §5.1 顺序：先 `FOR UPDATE` 再判 `vid`；已导入 → 不建 `video`、不 `insertDefault`；CAS 0 行 → 整笔回滚；时长/封面/分类/地址缺失 → 阻断且零写入 |
| E4 | `VideoSourceImportConcurrencyTests`（Mockito） | 同集两事务：第二个在锁内看到 `vid` 非空即幂等返回（构造"两事务各建 video"的旧行为使其失败）；目录行 `DuplicateKeyException` → 回滚 + 新事务重走加锁路径，**不**重放赢家 |
| E4 | `VideoSourceJobLeaseTests`（Mockito） | 每批首语句是 job 行校验；`status`/`token`/`epoch`/期限任一不符 → 零业务写；接管条件 UPDATE 只发一条语句；续租 0 行 → 立即停止；源级 `active_job_id` 获取/释放/过期 |
| E5 | `VideoPlaybackServiceTests`（Mockito） | 400/404/403 矩阵；`lineId` 非法在**任何** playable 状态下都是同一个 400；`NOT_BOUND`（COS 与未绑定两种）；`SOURCE_DISABLED`；`NO_PLAYABLE_ADDRESS`（线路禁用/无批准地址/核验 FAILED）；线路选择确定性（`line_order, id`）；响应不含 `source_id/base_url/credential_ref/url_hash/verify_status`；`Cache-Control: no-store`；**结构断言：播放服务不依赖 Redis/Redisson** |
| E5 | `VideoEpisodeNavigationTests`（Mockito） | prev/next/`totalVisibleEpisodes` 只用同一可见性谓词；未过审/私密他人/已删除的 `vid` 不出现在任何字段；`playable` 与 §7.1.1 规则一致；`episodes` 接口分页边界、无 count 字段、`size` 越界 400 |
| E5 | `VideoDetailFieldRemovalTests` | 两个 VO 不再有 `videoUrl`；`VideoConverter` 不再赋值；`selectFeed` 仍不含 `video_url` |

- **不做的测试**：任何连真实 DB / Redis / RabbitMQ 的隔离测试、任何真实站点联调、任何真实播放验收。这些需要单独授权（plan §5、§6）。
- 现有回归：`VideoStatusReadConsistencyTests` 用构造器直接 `new` 三个 service，E5 若改其依赖会编译失败——必须按契约同步更新测试，**不得**为了让旧测试通过而改变实现。

### 10.1 SQL 文本测试的解析规则（取代"整文件禁关键字"）

上一版要求"文件内**无** `UPDATE`/`DELETE`/`DROP`/`TRUNCATE`"，这是脆弱的整文件关键字断言：它会把合法语法误判为违规，也无法区分"注释里提到"和"真的执行"。

正确做法（实现约束）：

1. **先剥离、再切句**：
   - 去掉 `--` 行注释、`#` 行注释、`/* ... */` 块注释（注意 `/*! ... */` 版本注释也整体剥离）；
   - 去掉字符串字面量内容（`'...'`、`"..."`，处理 `\'`/`''` 转义），保留占位；
   - 按未转义的 `;` 切分为语句列表。
2. **按语句首关键字判定**，而不是按文件里是否出现某个词：
   - 允许的首关键字集合：`CREATE`、`ALTER`、`SELECT`（`information_schema` 核对查询）、`SET`（会话变量，若需要）。
   - 出现 `UPDATE`/`DELETE`/`DROP`/`TRUNCATE`/`INSERT`/`REPLACE` 作为**语句首关键字**即失败，并打印该语句前 120 字符。
3. **必须放行的合法形态**（用测试固定住，防止以后"修测试"时误伤）：
   - `ON UPDATE CURRENT_TIMESTAMP`（列定义）；
   - `ON DUPLICATE KEY UPDATE`（若将来出现）；
   - 注释/字符串里出现的任何关键字；
   - `information_schema` 的 `SELECT` 核对语句。
4. **正向断言用规范化文本**：把"去注释 + 压缩空白"后的文本用于断言列清单、唯一键名、FK 目标、CHECK 名称（而不是对原文做脆弱的跨行正则）。
5. **不声称已执行**：这些测试是**文本级**契约，不能证明 MySQL 真的接受了这些 DDL。真实执行结果属于 §11 的未验证项。

---

## 11. 未验证 / 未实现 / 需父确认

### 11.1 未验证（不得写成已完成）

1. **真实 CMS 协议未验证**：无授权站点的文档或脱敏样例；`adapter_type` 取值、分类/分页/详情/线路/剧集的真实字段名全部未知。E2/E7 只能按标注 fixture 推进，不得声称兼容任何版本。
2. **前端不在本仓库**：§7.1/§7.2 的响应形状无消费方；`playable=false` 的展示、线路切换 UI、选集 UI 都未经联调。
3. 真实播放（MP4、原生 HLS / hls.js、拖动、切片/密钥跨域、地址过期）未验证。
4. **未执行任何迁移、未连库、未启动基础设施、未联网、未运行测试**。
5. **MySQL 行为未验证**：`FOR UPDATE` 的锁范围、`ADD COLUMN` 是否走 INSTANT、`MODIFY COLUMN` 的锁与耗时、复合外键与 CHECK 是否被实例版本接受、1205/1213 的实际发生率——全部是设计意图，没有一次真实执行。
6. **§5 的并发协议没有并发测试证据**：协议本身的正确性只在纸面上，需要 §10 的 Mockito 测试 + 后续授权的隔离测试（真实 MySQL）才能验证。

### 11.2 未实现（契约已定，代码为零）

- 9 张新表与 `video` 迁移（E1）。
- 受限 HTTP 客户端、适配器（E2）。
- 管理端来源/分类/候选/导入/封面/时长/线路/地址接口（E3）。
- 分页同步、检查点、租约、源级串行、导入事务（E4）。
- `GET /api/videos/{vid}/playback`、`GET /api/videos/{vid}/episodes`（E5）。
- 关闭投稿与 STS、删除侧 key 归属校验（E6）。
- 地址核验任务（本契约**故意不实现**，`verify_status` 恒 0）。
- `docs/api.md` 与对应功能计划/教程同步（E9）；不向 AGENTS.md 写进度，不维护 CLAUDE.md。

### 11.3 仍存在的阻塞/缺口（父代理必须裁定）

| 编号 | 问题 | 当前状态 |
|---|---|---|
| B1 | 不可播放用 HTTP 200 + `playable=false` 还是 4xx（P10） | worker 提议 200，**未批准**；若改 4xx 只改 §7.1.4 |
| B2 | 是否接受"历史 COS 视频在接口层不可播放"（feed/详情仍可见但 `NOT_BOUND`） | worker 认为这是 A6/A7/A8 的直接后果，**需父确认** |
| B3 | 管理员查看他人私密剧集线路是否需要独立受审计接口 | 本契约不提供，需另立 |
| B4 | 导入管理员 `videoCount`/`loveCount` 被整站剧集放大的口径 | 沿用现状，**需父确认** |
| B5 | 未被认领的 `temp/covers/` 对象与 §7.3.1 的四种孤儿对象仍会堆积（本仓库无清理任务，`selectExpiredTempFiles`/`markCleaned` 无调用者） | 本切片不解决，仅记录 |
| B6 | 管理端"批准地址前人工查看原始 URL"没有接口（§7.3.3 不返回 URL） | 已知缺口，需父决定是否另立受审计只读接口 |
| B7 | `m:` 与 `u:` 同一物理剧集可能双行双 `vid`（§4.13.2） | 只提供人工处理与只读辅助列表，**不自动合并**；需父确认是否接受 |
| B8 | 白名单是部署配置的具体载体（配置文件键名/热加载语义）尚未定义 | 需父与 E2 一并裁定；本轮只固定"精确 host:port + 只 https" |
| B9 | CHECK 强制依赖 MySQL 8.0.16+，实例版本未验证 | 需在执行迁移前确认，否则退化为服务层校验 |
| B10 | 本契约整体 | **候选，父评审中**；不构成验收通过，也不构成用户批准 |

---

## 12. 文档同步责任

- `docs/api.md`：新增路由（`GET /api/videos/{vid}/playback`、`GET /api/videos/{vid}/episodes`、管理端 `video-sources/**`）与两处 `videoUrl` 字段移除，由 E5/E9 同步；本契约不修改 `docs/api.md`。
- 对应功能计划/教程：迁移落地后记录实际迁移前提和范围，不将候选E1方案写成已实现。AGENTS.md仅保留基本约束，CLAUDE.md已删除，不再要求两份同步。
- 本文件是 E0 契约，**只描述契约**；任何"已实现/已通过"的表述都必须来自对应切片的实际执行记录。当前状态见 §0.1：候选（父评审中）。
