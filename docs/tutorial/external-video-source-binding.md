# S2：同步选择来源、探测与确认（草稿阶段 + M6-B 已导入加线）

## 1. 本片做到了什么

接在S1本地作品/剧集草稿之后，管理员可以选择配置中的来源作品和线路位置，服务端重新抓取详情、探测一份媒体清单、保存候选，再由管理员确认统一时长和来源关联。草稿阶段**不会创建video/video_status，不会发布视频，也不提供播放地址**。

S2 首版只支持未导入草稿。M6-B 起，同一组接口也允许**已导入剧集**（`local_video_episode.vid != NULL`）新增独立线路或明确替换当前线路，但确认条件更严格：必须有成功HLS证据、来源仍合规、地址摘要正确，且人工确认时长等于剧集与原视频的统一时长。已导入后不能通过本接口修改标题、vid、作者、video/status、播放进度或统计，也不能调用 `setDuration`。已导入的抓取仍可保存探测失败候选用于诊断，但失败候选不能替换当前有效线路。详见 [人工导入进度](external-video-source-manual-import.md) 的 M6-B 记录。

## 2. 为什么不用旧A2四张表

按已确认的同步简化决定，本片使用三张小表，不接旧M4-A2的抓取目录/历史版本体系：

- `local_episode_source_state`：每剧集一个revision；缺行等价于0，第一次确认时创建。
- `local_episode_media_candidate`：一次人工选择的服务端快照，包含地址及摘要、探测结果、基准版本、操作者/时间。确认时只补确认时长、操作人/时间/理由，不覆盖原地址或探测结果。
- `local_episode_source_binding`：稳定的本地来源关联ID，指向当前已确认候选；替换时只更新此指针，旧候选和确认信息保留。

迁移：`database/migrations/s2-local-episode-source.sql`，已镜像至初始化SQL。前置只有users及M4-A1本地身份表；**不依赖E1-A来源目录表或M4-A2四表**。sourceCode引用部署配置，不是数据库source_id。旧表文件保留，初始化全量SQL仍包含它们；没有执行迁移，也没有删除任何旧表。

MySQL要求8.0.16+。IF NOT EXISTS不校验已有表形状，DDL/外键/CHECK/锁行为尚未在真实MySQL验证。没有清理任务或保留期承诺。

## 3. 管理员操作顺序

所有路径前缀：`/api/admin/local-video-episodes/{episodeId}/sources`。必须登录且为管理员，操作人只取登录态。写操作一律POST。

### 3.1 查询当前状态

`GET <前缀>`返回：

```json
{"revision":0,"durationSeconds":null,"bindings":[]}
```

以上是ApiResponse.data。revision是**整个本地剧集的版本**，不是某条线路自己的计数。读操作在一个REPEATABLE_READ事务内读取，最多32条关联及其候选，不联网、不消费Redis配额。

### 3.2 抓取并探测候选

`POST <前缀>/candidates`

```json
{
  "sourceCode":"demo",
  "workId":"7",
  "lineOrdinal":1,
  "entryOrdinal":1,
  "expectedRevision":0,
  "targetBindingId":null
}
```

- null targetBindingId = 新增来源；替换已有关联必须明确填写其ID，且保持原sourceCode/workId。不根据标题、序号或URL自动合并。
- lineOrdinal为1..16，entryOrdinal为1..1000。**只选择本次新抓取快照中的位置，不代表上一次预览的持久身份。** 上游可能重排，管理员必须核对本次返回的label再确认，不能自动沿用旧预览的判断。
- workId为1..191位、无前导零的正整数字符串。没有客户端URL、probeSuccess、候选时长或操作者输入。
- UID/参数/开关/独立媒体策略/基准版本/目标关联先检查，再获得本实例探测许可、消费共享Redis管理员配额。若剧集已导入，预检还要校验原 video 存在、未软删、`video_url/video_key` 均为NULL，以及剧集/视频时长为一致的正有限秒数。
- 最多一次CMS详情请求和一次媒体清单请求；不递归master、不取分片/密钥、不重试。两次请求均在数据库事务外；最后短事务锁剧集（已导入时再锁原video）并**再次核对版本、导入状态/原vid和目标**后才保存候选。期间发生其他确认、首次导入或vid变化，结果409且不保存候选；已花的配额不退。
- 与现有预览使用同一个Redis配额，但探测和预览各有一个独立的实例内Semaphore，容量均取max-concurrent-requests。因此**不能把该值宣传成两类接口合计的并发上限**；最多各N个请求。两次HTTP分别沿用协作式20秒预算，整个操作不是严格20秒上限，DNS/阻塞IO仍不能被硬中断。
- 探测失败分为`UNSUPPORTED_MANIFEST`（解析器拒绝）、`FETCH_FAILED`（传输失败）、`PROBE_REJECTED`（请求路径等契约拒绝）。候选时长和清单摘要保持NULL，不伪造0；未知运行错误则502，不保存候选。
- 成功为`SUCCEEDED`，返回候选秒数；底层解析仍BigDecimal精确求和，本片落库及业务确认沿用本地剧集的Double秒数，不承诺持久化十进制无损。

返回候选字段：id、episodeId、expectedRevision、targetBindingId、sourceCode、workId、lineOrdinal、entryOrdinal、label、probeStatus、candidateSeconds、capturedAt，以及确认后的confirmedSeconds/confirmedBy/confirmedAt/reason。`capturedAt`是数据库保存候选的时间，不是精确的远端响应到达时刻。

**不返回address、addressHash、manifestHash、lineKey。** 自由文本label/reason不是可信HTML，前端必须转义；不能保证上游自由文本没有夹带URL。候选实体不生成包含地址的toString。

### 3.3 人工确认

`POST <前缀>/candidates/{candidateId}/confirm`

```json
{"expectedRevision":0,"durationSeconds":1250.5,"reason":"已核对该来源为同一集，统一使用无片头版本时长"}
```

- 秒数必须有限且大于0；理由1..500字符且非空白，正常确认和修正都要求填写。
- **草稿**既允许确认探测结果，也允许人工核验后修正，或在探测失败后人工填写；**这不把失败探测改成成功，更不证明媒体能播放**。**已导入剧集不适用人工补偿**：必须具有与 M6-A 相同的成功HLS证据（`probe_status=SUCCEEDED`、64位小写`manifest_hash`、正有限`candidate_seconds`），且人工确认时长等于剧集与原视频的统一时长；探测秒数不强制等于统一时长，由必填理由记录“同一版本/同一时间轴”的人工判断。
- 锁本地剧集→检查当前revision、候选的expectedRevision及未确认状态→重新检查来源启用和当前媒体允许列表、地址摘要→检查目标关联→revision CAS递增→记录确认→新增/替换关联→**仅草稿首次**设置剧集统一时长。已导入时在同一短事务中按 `episode → video` 顺序锁原video后完成上述核对，不写video、不调用`setDuration`。全部不联网、不消费Redis配额。
- 候选只属于一个episode，不能拿另一剧集的candidateId确认。新增候选/关联、revision CAS、确认、关联替换及首次时长更新都要求影响1行；生成主键缺失或回读失败也会抛错并要求事务回滚。仅“确保state行存在”的upsert允许已有行无变化，不要求影响1行。没有自动死锁重试。
- 一个剧集最多32条来源关联。草稿第一次确认确定统一时长；后续新增/替换必须提供同一时长，不能静默覆盖已有时间轴。本片没有“修改已确认统一时长”的接口。已导入剧集沿用已导入时的统一时长，不一致直接409。
- 两个候选基于同一revision：第一个确认后，第二个必须409并重新抓取。抓取候选本身不增加revision。确认不是透明幂等重放；响应丢失时先GET当前状态，不盲目重复POST。
- 返回新的revision、统一时长、当前bindings（稳定id＋脱敏候选）。导入/已导入的线路变更均不返回地址或摘要；外部播放资格仍由 M6-A 独立判定。

### 3.4 查询候选历史

`GET <前缀>/candidates?afterId=0&limit=20`，limit 1..100，按id升序keyset查询，无OFFSET。返回数组；下一页用本页最后id，空页结束。用于定位响应丢失后的已保存候选和查看历史确认，不返回敏感地址。

## 4. 错误、开关与安全边界

- 400：非法参数、时长、理由或JSON；415：非JSON；401/403：真实登录/管理员拦截器。
- 404：剧集、候选、目标关联或启用来源不存在。
- 409：版本过期、重复确认、统一时长冲突、来源数量上限、替换来源归属变化；M6-B 还包括首次导入/原vid变化、已导入视频已删除/不是外部稿件、剧集与视频时长不一致、候选缺少成功HLS证据、确认时长不等于统一时长。
- 429：探测并发/管理员配额；503：默认关闭或媒体策略缺失（Redis异常沿现有503）。
- 502：详情/地址/未知探测失败；500：持久化失败。JDBC及事务异常转为固定业务文案且不携带cause，避免全局异常日志打印含地址的SQL参数。
- 复用`app.external-video-source.preview-enabled`和来源enabled，默认关闭；确认也检查来源仍启用。播放开关`playback-enabled`**不**参与管理端抓取/确认。当前状态和候选历史GET允许管理员查询已保存记录，不受预览开关限制。
- controller在服务调用前设置no-store；不宣称拦截器401/403和绑定前失败都带此header。
- **签名地址仍是敏感明文**：真实部署前限制数据库/备份权限，禁止对新mapper开启SQL参数日志，不把地址写操作理由。不自建加密/签名/归档平台。
- 内容指纹不是认证；同URL的上游内容可变，管理员确认只针对保存的快照。没有更强SSRF/媒体真实性/浏览器可播放保证。

## 5. 本轮验证范围

纯Mockito服务测试、真实登录/管理员拦截器MockMvc、真实MyBatis XML解析、迁移与镜像的静态比较。事务测试使用测试事务管理器，证明边界与回滚请求，**不是实际MySQL回滚或并发锁验收**。

没有读取application.yml，没有外网/真实Redis/DB/容器/迁移操作，没有本机TLS重跑，没有创建视频或前端联调。离线回归日志`/tmp/s2-related-final.log`：391项，零失败/错误，11项本机TLS测试跳过，即380项执行通过；其中新增43项（服务33、管理接口6、Mapper/静态schema4）。S2仍是离线候选，不是完整视频源功能上线。

M6-B 普通后端离线回归已在 `Java_video` 隔离工作区通过（1114 通过、228 跳过，零失败或错误）。结果见 [人工导入进度](external-video-source-manual-import.md)。未做真实 MySQL 并发/回滚或上游验收。
