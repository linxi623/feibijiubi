# 按业务模块整理目录：执行与验收计划

## 1. 目标与边界

将顶层按技术层分组改为“业务模块优先，模块内部保留Controller/Service/Mapper分层”。仍然是一个Spring Boot应用、一个Maven工程，不引入微服务、DDD框架或新业务能力。

本次只迁移目录、包名及必要引用；不改变HTTP路径/JSON、SQL语义、表结构、Redis键、MQ名称/消息格式、事务传播、锁顺序、重试和调度行为。发现业务缺陷另列问题，不夹带修复。未来comment/danmaku只约定归属，未实现前不建空目录/空壳代码。

## 2. 目标Java结构

根包保持`com.feibijiubi.backend`，启动类不移动。

```text
backend/
  auth/            登录、令牌、鉴权及专属拦截器/注解
  user/            用户资料、账户资料、关注关系
  video/           视频、分类、审核、本地作品/剧集身份
  interaction/     点赞/投币/收藏/播放/分享/进度及请求准入
  videostats/      统计模型、Outbox、消费、落库、恢复、监控
  videosource/     外部来源预览/受限客户端/草稿来源绑定与确认
  shared/          确实跨模块复用的响应、异常等少量类型
  infrastructure/ 通用Redis/MQ/COS/限流基础能力
  config/          全应用装配（保留少量跨模块配置）
```

- 每个模块按实际需要保留controller/service/service.impl/mapper/entity/dto/vo/config等子包；不强制空目录齐全。
- 外部HTTP/解析/媒体客户端放`videosource.integration`，不是全局integration。
- `LocalVideoSeries`/`LocalVideoEpisode`等本地身份不属于外部来源：R1将这一小块归入`video.local`，来源候选和绑定归`videosource`。只是归位，不新增跨模块门面或修改现有调用链。
- “视频审核状态”归video；统计流水线归videostats，不用含混的status模块名。
- 统计专属MQ消费者/事件/指标/调度器归videostats，不因为用MQ/Redis就放公共基础设施。
- 本轮不为消除现有跨模块Mapper调用而改业务。先显式记录依赖，服务边界治理另做；不能宣称移动包名就完成架构解耦。
- 测试Java包随被测模块迁移，保留测试类名、断言和opt-in条件。

### 资源路径策略

R1–R5保留现有`src/main/resources/com/feibijiubi/backend/mapper/*.xml`物理路径，更新namespace、resultType、parameterType及Java引用；这样不需要读取/修改敏感application.yml，不破坏现有mapper-locations约定。XML物理目录不是业务边界，允许集中存放。

Lua、测试fixture、SQL迁移路径不变。R6只有在不修改敏感配置、且用DB-free测试证明资源发现兼容后才考虑XML物理归组，否则明确保留该例外，不假称“所有资源都已搬迁”。不能凭编译通过认定Mapper运行时会绑定。

## 3. 分批派发

| 批次 | 工作 | 执行边界 | 验收重点 | 状态 |
|---|---|---|---|---|
| R0 | 基线、范围清单、风险清单 | 父级处理，无业务改动 | 干净提交、测试数字、敏感/产物排除 | 已完成 |
| R1 | 外部视频源＋本地身份归位 | videosource及video.local；其他代码仅修受影响引用 | HTTP权限/响应不变、受限客户端语义、两Mapper绑定、旧包残留 | 已离线验收并合入29ff843 |
| R2 | 用户与鉴权 | user/auth及必要引用；共享类不强行搬入某个业务 | Login/Admin拦截器装配、Token/用户接口、Bean名稳定 | 父级离线验收通过，已本地提交`e451467`并fast-forward合入主工作区（未推送远程） |
| R3 | 视频、分类、审核、互动 | video/interaction及必要引用 | 发布可用性守卫、原事务/幂等/限流、Mapper绑定 | 父级离线验收通过，本地提交`0215ce4`并fast-forward合入主工作区（见R3验收记录） |
| R4 | 视频统计流水线 | videostats以及专属配置/MQ/指标 | 队列名/消息序列化类型头、锁/事务/调度器Bean名、旧包兼容风险 | 父级离线验收通过，本地提交`d566541`并fast-forward合入主工作区（未推送）；`event`包按消息协议例外保留 |
| R5 | 通用能力收口 | shared/infrastructure/全局装配 | 避免新的大杂烩、全局异常/脚本/存储Bean仍可装配 | 父级离线验收通过，本地提交`ac7ad98`并合入主工作区 |
| R6 | 全局扫描、目录说明、最终验收 | 空目录/残留引用/架构测试/文档 | 测试数量守恒、包路径一致、XML解析装配、打包和运行边界说明 | 已完成源码复审、父级回归、正式打包及JAR内容验收，随本批本地提交；真实运行不在本轮范围 |

分批不是并行写入：R2+都须基于前一批父级接受后的结果，不从同一个旧HEAD并行迁移交叉引用。

## 4. 每批流程与角色

1. 执行代理：先列旧→新类名/路径映射，按批准范围迁移；只在分配工作树中写；不commit/stage/push，不再委派。
2. 执行验证：先test-compile，再相关测试，最后普通回归（见下节），串行运行。保存日志，记录测试类/方法数量，不能以减少断言或改跳过条件过关。
3. 独立审查代理：执行者结束后才进入同一工作树；只读审查基线到候选的全部差异（包括未跟踪新文件），核对映射、业务语义、运行时扫描和遗漏风险。报告PASS/CHANGES_REQUIRED/BLOCKED与定位证据，不修改代码，不跑第二套并行Maven。
4. 父级验收：检查差异和审查结果，亲自复跑测试；未通过不能合入或进入下一批。必要修复沿用执行者，之后再审。
5. 只有接受的结果才允许集成。用户已明确授权：后续每批验收通过后由父级本地提交、合入并推进，直到重构完成，无须逐批再问；不推送远程。提交/合入/工作树清理由父级管理；不强制删除含未验收修改的工作树。运行故障、业务/协议变更及额外环境权限仍须停下报告。

所有子代理仅使用cn/sg/global提供的DeepSeek Flash 4.1。当前注册的准确ID可用`my-cpa/cn:deepseek-v4.1-flash`。模型或启动运行失败就停并报告，不偷偷换模型/CLI。审查结果只是证据，不代替父级验收。

## 5. 必须满足的验收条件

### 所有批次

- 所有生产/测试Java编译通过；旧文件按映射消失，新文件路径与package一致；同一类只存在一份。
- 业务方法体、常量、注解参数、可见性、请求路径/字段、SQL/Lua语义不变。仅允许包引用相关机械改动及必要的结构测试更新。
- 检查import之外的全限定名：XML namespace/resultType、反射/Class.forName、`@MapperScan`/`@ComponentScan`、测试资源和源文件路径、字符串形式的服务/Bean引用。
- 保持Bean名字和显式@Qualifier/@Scheduled关联。重命名包不允许悄悄改变组件名称或制造冲突。
- MyBatis新增DB-free装配测试：不是只看XML文本，须检查迁移后的Mapper绑定与MappedStatement可用；不连接真实数据库。
- 既有测试迁移后数量不减少，跳过原因不变化；新增结构测试另计。历史目录只作历史记录，不为了搜索“零命中”批量改旧日志。
- 普通回归为离线证据，不代表真实数据库、MQ、TLS或线上发布已验证。

### R4额外闸门

- Java类包名可能成为RabbitMQ JSON类型头或其他序列化协议的一部分。必须先查实际converter/typeMapper/trustedPackages/消息头用法，不能单凭JSON字段没改就声称协议不变。
- 若搬类会破坏已有消息消费，停止该类迁移，报告兼容方案，取得父级决策后再做；不在本批偷偷改队列、清空消息或弱化反序列化限制。

## 6. 验证命令与环境限制

使用Maven Wrapper、离线模式、串行、约束堆；不读application.yml，不启动应用/容器，不联网，不执行迁移。

迁包后的复验必须使用无旧class残留的构建目录，否则旧FQCN不存在断言可能假失败。可在确认target仅为本工作树生成产物后清除target再编译；离线clean插件不可用时不要联网补依赖，不宣称clean命令通过。

```bash
MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -o -DskipTests test-compile

MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -o \
  '-Dtest=*Tests,!BackendApplicationTests' \
  -DvideoSourceLocalTls=false -DvideoStatusIT=false \
  -DvideoStatusScale=false -DvideoStatusBaseline=false -DvideoStatusBenchmark=false \
  '-DargLine=-Xms64m -Xmx512m -Djdk.attach.allowAttachSelf=true' test
```

测试编译会覆盖opt-in测试源码，但普通回归不运行这些用例。若缺离线依赖则报告，不自行联网下载。真实基础设施/浏览器验证另需授权。

## 7. 基线与执行记录

- 分支：`feature/external-video-source`；重构前基线：`dafc3d5`（本地提交，未推送）。
- R0：258个生产Java文件、119个测试Java文件（测试辅助类包括在内）。
- R0普通回归：1149项、0失败/错误、228跳过；日志`/tmp/layout-r0-baseline.log`。这是921项执行通过，不是1149项全执行。
- 提交前检查排除application.yml、日志、target、密钥文件和本地备份；两处userinfo匹配确认为保留域名的拒绝测试样例，不是真实凭据。
- 本地范围快照：`../cleanup-backups/layout-refactor-20261030-112027/`，仅作为意外恢复辅助；Git基线才是迁移对照。无数据库备份或运行环境更改。
- 该计划是进度记录位置；AGENTS.md只保留基本约束，不再维护CLAUDE.md。

### R1 验收记录

- 工作树：`D:/feibijiubi/backend-layout-r1`，基线`dafc3d5`。父级验收后按用户授权提交`29ff843`，已fast-forward合入主工作区，未推送远程。
- 派发：工作流`9b534537-6ec5-4c38-b83f-b9b6fba27754`；执行`d5da9eb6-2412-464f-aa45-9f9b95371b89`，独立审查`c927c988-4567-4c2d-83c9-0f830a8aed8d`。均使用`my-cpa/cn:deepseek-v4.1-flash`。
- 迁移65个生产Java＋32个测试Java；新增两个Mapper绑定测试类（4个测试方法）。两XML只改namespace/resultType，物理路径保留。映射清单为候选工作树`docs/refactoring/r1-package-map.json`，属于父级明确要求的交付物，纳入后续集成。
- 独立审查PASS，无阻塞问题；父级移除一条冗余同包import。其余报告项：schema测试子包命名保留；R2+须继续核查opt-in测试MapperScan范围；新增装配测试与SQL结构测试部分重合但有独立接口注册/逐方法/resultType断言，不删除。
- 父级逐一比对97项映射：旧路径消失，新package与路径一致；归一化已列明FQCN/源码路径并忽略package/import后，全部正文与基线一致。确认暂存区为空。
- 父级`test-compile`通过（`/tmp/layout-r1-parent-compile.log`）；普通全回归1153项，0失败/错误，228跳过，925项执行通过（`/tmp/layout-r1-parent-full.log`）。
- 父级进一步对照两侧Surefire XML中的类名/方法名和结果：1149项基线测试全部保留，0缺失、0结果变化，仅新增4项Mapper绑定测试；不是只比较总数。
- 离线验收通过不代表真实应用启动/数据库/MQ/TLS验收；未读取application.yml，未执行迁移，未访问基础设施。R1现已完成本地集成；后续批次提交/合入已获用户授权，继续推进R2。

### R2 执行记录（执行侧自验，非父级验收）

- 工作树：`D:/feibijiubi/backend-layout-r2`，基线`ff93161`（detached），保留R1合入后的未提交R2改动，未reset/清理、未动主工作区。前一次执行进程17728以退出码3221226505结束且无结果，其遗留日志不作为验收证据；本节数字全部来自本次独立重跑。
- 迁移范围：`auth`16个生产类（注解3、拦截器2、JwtProperties、JwtUtils、TokenService/TokenContext/UserAccountService及其实现、UserAccountController、登录DTO/VO）、`user`12个生产类（UserController/UserService(+impl)/User/UserFollow/UserMapper/UserFollowMapper/UserConverter/资料DTO/UserVO/UserCountVO）；测试随模块迁移3个（LoginInterceptorTests、JwtTokenServiceImplTests、UserAccountServiceImplTests）。共31个Java文件，逐文件与`git show ff93161:旧路径`在不计package/import行后正文完全一致。旧包目录为空且已无残留类，同一类只存在一份。
- 映射清单：`docs/refactoring/r2-package-map.json`（31条迁移＋3个新增测试类＋6条非机械改动＋opt-in MapperScan审计）；审查可按该清单逐文件对照。
- Mapper XML：`UserMapper.xml`（namespace/parameterType/resultType）与`UserFollowMapper.xml`（namespace）只改FQCN，物理路径仍在`src/main/resources/com/feibijiubi/backend/mapper/`。`@MapperScan`在主代码中不存在，靠`BackendApplication`包根的`@Mapper`自动扫描。
- 新增DB-free装配测试9项：`UserMapperBindingTests`（3，含真实MyBatis解析XML→新namespace、逐方法MappedStatement、resultType/parameterType指向`user.entity.User`、根包扫描含两个user mapper且旧FQCN类不存在）、`UserFollowMapperBindingTests`（2）、`AuthEndpointAuthorizationTests`（4，控制器路径/`@OptionalLogin`/`@AllowRevokedToken`保留、真实`LoginInterceptor`对匿名的放行与401、`WebMvcConfig`拦截路径与免鉴权排除项不变）。均不连数据库、不启动Web容器。
- opt-in测试路径：只有`VideoStatusCounterTransactionTests`（`@SpringBootTest(classes=...)`直接new UserVideoServiceImpl/UserServiceImpl）和`VideoStatusIsolatedIntegrationTests`（`@Import(UserVideoServiceImpl)`）需要新包；已分别追加`@MapperScan("com.feibijiubi.backend.user.mapper")`与在原有范围上追加该包，旧范围未改。其余13个仍扫旧包的opt-in上下文经静态核对不依赖被迁走的两Mapper，未改动。
- 验证（离线、串行、未联网、未读application.yml、未启动应用/容器/数据库）：
  - 离线`maven-clean-plugin`不可用，改为删除`target/`后强制全量编译：258个生产源文件＋124个测试源文件，BUILD SUCCESS；日志`/tmp/layout-r2-retry-compile-clean.log`。
  - 相关测试17个类91项，0失败/错误/跳过；日志`/tmp/layout-r2-retry-related.log`。
  - 普通回归1162项、0失败/错误、228跳过（934项执行通过），BUILD SUCCESS；日志`/tmp/layout-r2-retry-full.log`。与R1父级全回归1153项逐类对照（按映射把3个测试类FQCN换成新名后）：1153项全部保留、各类测试数与跳过数0变化，仅新增上述3类9项。
- 未验证/限制：opt-in用例（需真实MySQL/Redis）与`VideoStatusCounterTransactionTests`/`VideoStatusIsolatedIntegrationTests`未运行；真实数据库、MQ、TLS、应用启动未验证；`application.yml`未读取。
- 本次仅执行侧自验：不声称父级验收通过，未stage/commit/push。

### R2 父级验收记录

- 重试工作流`d8d76352-4cbe-47dd-86c0-a764a9919b0d`；执行`80905776-3871-4f6b-9e12-2153f238605a`、独立审查`9a407750-169c-41bf-8d6b-8e2fecdca38b`，均使用`my-cpa/cn:deepseek-v4.1-flash`。审查PASS，无阻塞项。
- 父级逐项核验31条迁移：旧路径消失、package与新路径匹配，去除package/import后正文全部与ff93161一致；审阅全部其余源码差异及3个新增装配测试，XML仅修改FQCN。
- 父级离线`test-compile`和普通全回归通过，日志`/tmp/layout-r2-parent-compile.log`、`/tmp/layout-r2-parent-full.log`；1162项，0失败/错误，228跳过，934项执行通过。对比R1与本批Surefire XML的类名/方法名/结果（规范化3个迁移测试类名），1153项原测试全部保留、结果不变，仅新增9项。
- 历史失败补查：`layout-r2-worker-compile-1.log`为3个测试尚未迁包导致同包类无法解析；`compile-2.log`为ResponseConverterTests缺少迁移后的UserConverter import。候选已分别迁移测试包、补充import，父级核对修复位置；后续编译与回归成功。此前runner退出3221226505的运行故障原因仍未查明，不能由重试成功认定已根治。离线clean插件缺失记录保留，不宣称clean命令通过。父级审计脚本初次因Windows默认GBK读取UTF-8映射失败，显式UTF-8后完成，不涉及业务测试失败。
- 审查备注处理：保留既有无用import及util命名，不夹带风格清理；`src/test/resources/video-status-isolated.properties`对旧mapper包的WARN日志配置未覆盖迁移后的user.mapper，留R6统一补齐并验证，真实opt-in运行仍未验收。
- 本批仅离线验收，不代表应用启动、真实MySQL/Redis/RabbitMQ/TLS或生产验收；未读application.yml，未联网、未执行迁移。父级按用户授权单独本地提交本批并fast-forward集成，不推送远程。
- R3本地提交：`0215ce4`（`refactor: group video and interaction by feature`），已fast-forward合入主工作区，未推送远程；R4即以该提交为基线。

### R3 执行记录（执行侧自验，非父级验收）

- 工作树：`D:/feibijiubi/backend-layout-r3`，干净 detached 基线`e451467`（R2 父级提交，已合入主工作区）。本批只在该工作树写入，未stage/commit/push，未改动 R1/R2 工作树，未再委派。
- 范围与映射：迁移58个生产Java＋14个测试Java；逐文件旧→新路径/FQCN、模块归属裁定、新增测试、非机械改动、opt-in 扫描审计全部列在`docs/refactoring/r3-package-map.json`（先写映射再迁移，迁移后回填实际改动）。
  - `video`：`controller`（Video/Category/VideoReview）、`converter.VideoConverter`、`dto`（CursorDTO/VideoSubmitDTO/VideoReviewDTO/VideoUploadPrepareDTO）、`entity`（Video/Category/UploadTempFile）、`enums.VideoReviewStatus`、`mapper`（Video/Category/UploadTempFile）、`service`（VideoService/VideoReviewService/PublishedVideoGuard/`category.CategoryService`）、`service.impl`（VideoServiceImpl/VideoReviewImpl/`category.CategoryServiceImpl`）、`vo`（VideoDetail/VideoListItem/VideoSubmit/VideoUploadPrepare/AdminVideoDetail/AdminVideoListItem/CategoryParent/CategoryChildren/CursorPage）。
  - `interaction`：`UserVideoController`、`entity`（UserVideo/VideoInteractionRequest/VideoInteractionWindow）、`mapper`（UserVideo/VideoInteraction）、`UserVideoService(+Impl)`、`service`（10个交互接口/枚举/值类型）、`service.impl`（6个交互实现/调度器）、`vo.VideoInteractionVO`、`config`（VideoInteractionProperties/VideoInteractionCleanupSchedulingConfig）。
  - 归属裁定（父级确认）：上传准备DTO/VO与UploadTempFile实体+Mapper随视频上传/发布流程归video，接受未来infrastructure存储实现import video；CursorDTO/CursorPageVO经核实无其他业务消费者，归video而非shared；interaction内部去掉冗余`interaction`包段，类名/Bean名/配置前缀/调度不变。
- 逐文件正文核对：58+14个迁移文件与`git show e451467:旧路径`在不计package/import行后正文一致，仅6个测试文件的MapperScan/路径常量/FQCN字符串不同（均已在映射文件列明）；另有无需迁移但仅改import的调用点（BackendApplication、FileStorageService/TencentCosStorageServiceImpl、UserServiceImpl、若干VideoStatus*测试）。
- Mapper XML：`VideoMapper.xml`、`CategoryMapper.xml`、`UploadTempFileMapper.xml`（namespace/parameterType/resultType）、`UserVideoMapper.xml`、`VideoInteractionMapper.xml`只改FQCN，物理路径仍在`src/main/resources/com/feibijiubi/backend/mapper/`；孤立测试用的`mybatis.mapper-locations=classpath:com/feibijiubi/backend/mapper/*.xml`因此保持有效，全程未读application.yml。
- 新增DB-free装配测试5类12项（不连数据库、不启动Web容器）：`VideoMapperBindingTests`（3，含真实MyBatis解析XML→新namespace、逐方法MappedStatement、resultType/parameterType指向video实体与VO、根包扫描含3个video mapper、旧FQCN类不存在）、`CategoryMapperBindingTests`（2）、`UploadTempFileMapperBindingTests`（2）、`UserVideoMapperBindingTests`（3，含两个interaction mapper的扫描与旧包残留）、`VideoInteractionMapperBindingTests`（2）。
- opt-in 上下文：7个按字符串扫描的上下文补扫新包（仅追加，不删旧范围），其余14个静态核对不依赖迁走的mapper，未改动；该审计与已知缺口（`video-status-isolated.properties`的WARN日志仍只覆盖旧mapper包）记录在映射文件。
- 验证（离线、串行、未联网、未读application.yml、未启动应用/容器/数据库）：
  - 最终交付证据用干净全量编译：删除`target/`后`test-compile`，258个生产源文件＋129个测试源文件（124基线＋5新增），BUILD SUCCESS；日志`/tmp/layout-r3-final-compile.log`。过程日志`layout-r3-compile-1..6.log`保留每次失败与修复（1：VideoController的旧`vo.*`通配import随VO迁移失效；2：迁移后同包引用需显式import——PublishedVideoGuard/UserVideoService、`config.*`不再覆盖VideoInteractionProperties；3：交互隔离用例缺VideoStatusIsolatedIntegrationTests import；4：baseline隔离用例缺PublishedVideoGuard import；5：ResponseConverterTests缺VideoConverter import；6：通过），不代表最终状态。离线`maven-clean-plugin`仍不可用，用`rm -rf target`代替，不宣称clean命令通过。
  - 新增装配测试：12项，0失败/错误/跳过；日志`/tmp/layout-r3-binding-2.log`（首次`layout-r3-binding.log`为`updateVideo`无显式parameterType导致1项断言过强，已删除该断言并复跑通过）。
  - 相关测试272项（interaction/video/converter/video-status读一致性/各Mapper装配/auth端点），0失败/错误/跳过63（均为opt-in跳过）；日志`/tmp/layout-r3-related.log`。
  - 普通回归1174项、0失败/错误、228跳过（946项执行通过），BUILD SUCCESS；最终日志`/tmp/layout-r3-final-full.log`（同一数字的中间日志`/tmp/layout-r3-full.log`保留）。
- 与R2父级全回归1162项逐方法对照（按映射把14个迁移测试类FQCN换成新名；R2Surefire报告只读）：1162项全部保留、0缺失、0结果变化，仅新增12项装配测试；跳过数228不变。
- 未验证/限制：opt-in用例（需真实MySQL/Redis/RabbitMQ）未运行；真实数据库、MQ、TLS、应用启动与打包未验证；`application.yml`未读取，其对包名的任何引用（如`type-aliases-package`）未核对，本批XML全部使用FQCN，不依赖类型别名；`logging.level`缺口留R6。
- 本批仅执行侧自验：不声称父级验收通过，未stage/commit/push，未删除任何工作树。

### R3 父级验收记录

- 执行工作流`21560224-2678-4ac7-9a05-1e89d9c94f03`；执行`5c7bb7c5-9141-4b7f-86ae-496e922a952f`、独立审查`6ee1afcb-b0f6-45f8-a392-0756feb5e61e`，均使用`my-cpa/cn:deepseek-v4.1-flash`；独立审查PASS。
- 父级逐项比对72条映射：旧路径消失、新package匹配；剔除package/import后正文仅6个测试的扫描范围/路径/namespace字符串变化，以及父级补全1处原同包Javadoc引用。所有业务正文保持不变。另核对未迁移文件差异：测试类及IsolationGuard公开可见性仅供跨包复用，隔离逻辑和跳过条件不变；5个XML只改FQCN。
- 父级补齐记录：video→interaction的UserVideo实体/Mapper依赖，interaction→video的Video/PublishedVideoGuard依赖，user→video的VideoMapper依赖均为既有调用的显式归组，不宣称架构解耦。R4必须同步5个互动隔离测试对VideoStatusIsolatedIntegrationTests的import，以及互动调度配置Javadoc中的VideoStatusSchedulingConfig全限定名。日志WARN配置对user/video/interaction mapper的覆盖缺口仍留R6。
- 父级复跑离线`test-compile`及普通回归通过：1174项、0失败/错误、228跳过，946项执行通过；日志`/tmp/layout-r3-parent-compile.log`、`/tmp/layout-r3-parent-full.log`。逐方法对比R2/R3 Surefire XML并规范化迁移类名：1162项原测试全部保留、结果不变，只新增12项。
- 父级核查执行侧历史编译失败位置与对应import/跨包可见性修复。新增绑定测试曾错误要求updateVideo声明parameterType，实际XML未声明，删除该新增错误断言未修改任何既有断言；保留逐statement绑定验证，不额外把未声明类型固定为Object（当前解析结果为null）。历史失败日志保留，未以最终通过抹去失败。
- 父级验收仅限离线；真实数据库/Redis/RabbitMQ/TLS、应用启动和生产行为未验证，不读取application.yml、不访问基础设施。按用户授权单独本地提交并fast-forward集成，不推送远程。

### R4 执行记录（执行侧自验，非父级验收）

- 工作树：`D:/feibijiubi/backend-layout-r4`，干净 detached 基线`0215ce4`（R3 父级提交，已合入主工作区）。本批只在该工作树写入，未 stage/commit/push，未改动主工作区与 R1–R3 工作树，未再委派，未读取 `application.yml`，未联网、未启动应用/容器/数据库/MQ、未执行迁移。
- **消息协议闸门（先审计后迁移）**：`VideoStatusRabbitConfig#rabbitMessageConverter` 是应用内唯一 `MessageConverter`（`Jackson2JsonMessageConverter` + `DefaultJackson2JavaTypeMapper`，`setTrustedPackages("*")`，无 `idClassMapping`），Spring Boot `RabbitTemplateConfigurer` 用 `getIfUnique()` 把它装到自动配置的 `RabbitTemplate`，因此 `VideoStatusEventPublisher` 的 `convertAndSend` 会写入 `__TypeId__=com.feibijiubi.backend.event.VideoStatusChangedEvent`。消费端 `@RabbitListener` 的 `(org.springframework.amqp.core.Message, Channel)` 参数在容器适配器里不算 payload 参数（`isEligibleParameter` 排除），converter 因此按 `__TypeId__` 解析类；类不存在时实测抛 `MessageConversionException: failed to resolve class name. Class not found` → `ListenerExecutionFailedException`，业务 handler 不会被调用，配合 `defaultRequeueRejected=false` + 主队列 DLX 会被直接拒进死信。测试先行探针日志`/tmp/r4-probe2.log`。结论：迁移该 record 的包会改变出站类型头并破坏在途/滚动期消息。已按闸门要求上报证据与 A/B/C 方案，**父级裁定 A**：`event` 三个类（`VideoStatusChangedEvent`/`VideoStatusEventType`/`VideoStatusDelta`）保留原路径与 FQCN，本轮不迁、不加别名类、不加 `idClassMapping`、不改 converter/`trustedPackages`/队列、不排空或操作真实消息；R6 也保留该例外，后续协议迁移另行设计/授权。未选方案 B 关于 `idClassMapping` 反向映射的说法本轮未验证，不记为已证事实。
- 范围与映射：迁移80个生产Java＋63个测试Java；逐文件旧→新路径/FQCN、归属裁定、非机械改动、`@MapperScan`/opt-in 审计、例外与遗留项全部列在`docs/refactoring/r4-package-map.json`（先写映射再迁移，迁移后回填实际改动）。
  - `videostats`子包：`config`（5：`VideoStatusProperties`/`VideoStatusRabbitConfig`/`VideoStatusMetricsConfig`/`VideoStatusRuntimeRoles`/`VideoStatusSchedulingConfig`）、`controller`（5）、`dto`（3）、`entity`（6）、`enums`（2：`VideoStatusConsumeProcessStatus`/`RegistrationResult`）、`mapper`（6）、`metrics`（3，含只读`VideoStatusMetricsMapper`）、`mq`（10＋`RabbitConstants`）、`service`（11）、`service.impl`（9）、`service.admin`（6）、`vo`（6）、`support.VideoStatusCounterRange`、`exception`（6：3个`VideoStatus*`异常＋`RetryableMessageException`/`NonRetryableMessageException`/`RepairRequiredMessageException`）。
  - 父级边界裁定执行：边界1 `RabbitConstants` 迁`videostats.mq`（常量值/队列/交换机/路由键/头名/监听器 id 一字未改）；边界2 三个消息异常经逐个核对引用（只服务统计消息解析、分类与重试/死信/人工修复语义，无跨业务消费者）后一并迁`videostats.exception`，未改继承、抛出/捕获、重试与错误处理行为；四个`VideoStatus`专属 support/exception 按拟定归属迁移。
  - 测试随模块迁移并保持类名/断言/opt-in 条件：`config`3、`controller`5、`metrics`3、`mq`4、`service.admin`3、`service`37、`capacity`7（统计容量审计工具，测试专用）、`support`1；两个同名`VideoStatusSchedulingIsolationTests`分别落在`videostats.config`与`videostats.service`，不重命名、不合并。
- 逐文件正文核对：80＋63个迁移文件与`git show 0215ce4:旧路径`在不计 package/import 行后正文一致；唯一例外是13个文件中的扫描范围/namespace 字面量/Javadoc FQCN（已在映射文件逐条列明），另加2处因拆包新增的同包→跨包 import（`VideoStatusCounterRange`与其测试）。改动但未迁移的16个 Java 文件里，10个只改 import；1个只改 Javadoc（`interaction/config/VideoInteractionCleanupSchedulingConfig` 的 `VideoStatusSchedulingConfig` FQCN）；5个改 import、`@MapperScan` 扫描项与注释（互动隔离测试）。
- Mapper XML：7个统计 XML 只改 namespace/resultType/resultMap type，物理路径仍在`src/main/resources/com/feibijiubi/backend/mapper/`；`mybatis.mapper-locations`（`application.yml.example` 中可见，未读 `application.yml`）因此保持有效；已审计的非敏感源码与示例配置未发现`type-aliases-package`（未读取真实application.yml），XML 全部使用 FQCN。SQL 文本、语句 id、resultMap 结构未改。
- 扫描/反射审计：主代码无`@MapperScan`，mapper 由 `@SpringBootApplication` 根包的 `@Mapper` 自动扫描注册（`videostats.mapper` 仍在根包下）；R4 后旧包`com.feibijiubi.backend.mapper`已无任何接口，空目录已删除。15处字符串 `@MapperScan` 更新（14处把旧包项按等价集合改为`videostats.mapper`，1处`VideoStatusCounterTransactionTests`追加该包），4处`basePackageClasses`按接口引用自动跟随；无`Class.forName`/反射包名依赖。5个互动隔离测试对`VideoStatusIsolatedIntegrationTests`的引用与互动调度配置 Javadoc 的 FQCN 已同步（R3 验收记录的必备项）。
- 新增DB-free测试9项（不连数据库、不启动Web容器/broker）：`videostats.mapper.VideoStatusMapperBindingTests`（3：真实 MyBatis 解析6个XML→新namespace、逐方法 MappedStatement、resultType/resultMap 指向迁移后实体与 admin 行类型、根包扫描含6个统计 mapper 且旧 FQCN 类不存在）、`videostats.metrics.VideoStatusMetricsMapperBindingTests`（2：namespace/语句/`resultType`仍为 Integer/Long）、`videostats.config.VideoStatusRabbitMessageTypeHeaderTests`（4：出站`__TypeId__`固定为原 FQCN、携带原 FQCN 的在途消息仍能反序列化回事件对象、不存在的类名仍失败、converter 未新增 idClassMapping 且消息体不含包名）。
- 验证（离线、串行、约束堆；日志保留在`/tmp`）：
  - `rm -rf target` 后全量 `test-compile`：258个生产源文件＋132个测试源文件（129基线＋3新增），BUILD SUCCESS；日志`/tmp/layout-r4-final-compile.log`。离线`maven-clean-plugin`仍不可用，用删除`target/`代替，不宣称 clean 命令通过。
  - 相关测试820项，0失败/错误，217跳过；日志`/tmp/layout-r4-final-related.log`。
  - 新增测试9项，0失败/错误/跳过；日志`/tmp/layout-r4-new-tests-1.log`。
  - 普通回归1183项、0失败/错误、228跳过（955项执行通过），BUILD SUCCESS；日志`/tmp/layout-r4-final-full.log`。
  - 与R3父级全回归1174项逐方法对照（只读R3 `target/surefire-reports`，按映射把63个迁移测试类 FQCN 规范化）：1174项全部保留、0缺失、0结果变化，跳过数228不变，仅新增9项。
  - 过程失败日志全部保留：`layout-r4-compile-1..5.log`（拆包后 `VideoStatusCounterRange` 缺 import → 通配 import 未随迁移更新 → 改写后丢 `RedisScriptConfig` 引用），最终通过日志`layout-r4-compile-6.log`/`layout-r4-final-compile.log`；协议审计探针`r4-probe.log`、`r4-probe2.log`。
- 未验证/限制：opt-in 隔离/集成用例（需真实 MySQL/Redis/RabbitMQ）未运行，其 `@MapperScan` 变更只做静态核对；真实 broker 的类型头收发、滚动发布、DLQ 重放、应用启动/打包未验证；`application.yml` 未读取；`video-status-isolated.properties` 的 `logging.level.com.feibijiubi.backend.mapper=WARN` 已不覆盖迁移后的统计 mapper 包，按约束留R6统一补齐；`docs/tutorial/video-status-batch-aggregation-implementation.md` 第89行的示例 FQCN 与测试树`service/video/baseline/*`（交互内容）留R6处理。
- 本批仅执行侧自验：不声称父级验收通过，未 stage/commit/push，未删除任何工作树。

### R4 父级验收记录

- 工作流`2ad5db2e-f7b7-45ee-a47b-097e50dc5534`；执行`9981d581-745c-45ca-a488-a679b6c57098`、独立审查`c1d15168-1880-458f-b483-01b9f4a588e2`，均使用指定DeepSeek Flash 4.1；独立审查OK with notes，无阻塞问题。
- 父级逐文件核对143条映射：旧路径消失、新package符合路径；剔除package/import后的14处正文差异均为扫描范围、类型/namespace引用、Javadoc或空行，业务正文未变。核对全部16个未迁移Java差异及7个XML，XML按映射规范化后与基线完全一致。
- 审查计数纠偏：实际videostats生产类为80，不是审查正文一处所写83；未迁移修改Java为16（10个仅import、1个Javadoc、5个import＋扫描注释），审查建议14/8漏计了interaction的两个生产服务，故不采纳该建议。映射中的“其余12个”实列8个，已修正为8；探针日志索引与过期扫描注释已修正。
- 父级离线`test-compile`及普通回归通过：1183项、0失败/错误、228跳过，955项执行通过；日志`/tmp/layout-r4-parent-compile.log`、`/tmp/layout-r4-parent-full.log`。逐方法对照R3/R4 Surefire XML并规范化测试类名：1174项全部保留、结果不变，仅新增9项。
- event三类FQCN保持，VideoStatusChangedEvent/VideoStatusEventType无变化，VideoStatusDelta仅import适配；Rabbit配置/常量/事件发布与消费正文保持。新增类型头回归通过。真实broker与滚动发布未验收，Spring自动配置将唯一converter装配到RabbitTemplate的结论仍为静态/字节码证据，未增加实际应用启动验证。
- R6明确待办：教程`docs/tutorial/video-status-batch-aggregation-implementation.md`的旧实体FQCN、`service/video/baseline/*`交互测试归位、全部新mapper包的测试日志WARN覆盖；event兼容例外及XML集中物理路径不能被“零残留”清理掉。
- 执行侧历史编译失败与修复记录继续保留。父级映射审计脚本首次误按数组遍历files对象而失败，修正分组遍历后完成143项核验；不涉及业务测试失败。按用户授权单独本地提交`d566541`并fast-forward合入，不推送；真实基础设施、生产及应用启动未验收，未读application.yml。

### R5 执行记录（执行侧自验，非父级验收）

- 工作树：`D:/feibijiubi/backend-layout-r5`，干净 detached 基线`d566541`（R4 父级提交，已合入主工作区）。本批只在该工作树写入，未 stage/commit/push，未改动主工作区与 R1–R4 工作树，未再委派，未读取 `application.yml`，未联网、未启动应用/容器/数据库/Redis/MQ/COS，未执行迁移。
- 范围与映射：迁移22个生产Java＋3个测试Java；逐文件旧→新路径/FQCN、归属裁定、非机械改动、扫描/资源/反射审计、新测试与遗留项全部列在`docs/refactoring/r5-package-map.json`（先写映射再迁移，迁移后回填实际改动）。
  - `shared`（4，平坦包）：`ApiResponse`、`BusinessException`、`GlobalExceptionHandler`、`JsonUtils`。
  - `infrastructure.redis`（5＋3）：`RedisConstants`/`RedisKeyUtils`/`RedisUtils`/`RedisOperationException`＋`config.RedisScriptConfig`＋`operation.{RedisHashOperations,RedisSetOperations,RedisZSetOperations}`；`infrastructure.ratelimit.service(.impl)`（2）：`RateLimitService`/`RateLimitServiceImpl`；`infrastructure.storage`（4）：`config.{TencentCosConfig,TencentCosProperties}`＋`service.FileStorageService`＋`service.impl.TencentCosStorageServiceImpl`。
  - `user.enums`（4）：`UserAuth`/`UserGender`/`UserRole`/`UserVip`。核实消费者后按真实领域归 user：前三者对应 User 实体的 auth/gender/vip 列且生产代码零消费者；`UserRole` 唯一消费者是 auth 的 `AdminInterceptor`，role 是用户账号列且 auth 已依赖 user，故没有把任何枚举塞进 shared 或 auth。`config.WebMvcConfig` 保留 config；`event` 三类按 R4 协议例外不迁。
- 归属裁定要点：`GlobalExceptionHandler` 进 `shared`（它就是“共享异常→共享响应”的实现点，与所处理类型同包），`RedisOperationException` 进 `infrastructure.redis`（语义是 Redis 基础设施故障→503，避免把 Redis 概念带进通用层，shared→infrastructure 是唯一方向、无循环）；`JsonUtils` 进 shared 而非 infrastructure；存储实现→video 上传 DTO/实体/Mapper 的既有依赖按任务口径明确接受，未拆门面、未改存储签名。
- 逐文件正文核对：22＋3个迁移文件与`git show d566541:旧路径`在不计 package/import 行后正文 25/25 完全一致；112个修改未迁移的 Java 文件里，非 import/非注释的改动行数为 0（只改 import 与 Javadoc 里的全限定名）。唯一通配 import 变更：`LocalEpisodeSourceAdminControllerTests` 的 `common.*` 拆包后失效，换成两条显式 import（未用 ApiResponse）。
- Bean/扫描/资源审计：类名、`@Service`/`@Component`/`@Bean` 方法名不变，因此 bean 名（`globalExceptionHandler`、`jsonUtils`、`redisUtils`、`redisHashOperations`/`redisSetOperations`/`redisZSetOperations`、`rateLimitServiceImpl`、`tencentCosStorageServiceImpl`、`COSClient`、5个脚本 bean）全部保持；13处`@Qualifier`/`@Resource(name=...)`零改动；19处`@MapperScan`注解（含`basePackageClasses`，另有2处Javadoc引用）零改动（本批无 Mapper 迁移）；无反射包名依赖；`src/main/resources`（16 个 mapper XML、5 个 Lua、`application.yml.example`）与`src/test/resources`零改动；旧`common/enums/utils/service`目录已空并删除。
- 新增DB-free装配测试4类11项（不连数据库/Redis/MQ/COS、不启动 Web 容器，也不加载`BackendApplication`）：
  - `shared.GlobalExceptionHandlerWiringTests`（3）：shared 包扫描后 `globalExceptionHandler` 唯一；`ExceptionHandlerMethodResolver` 对 BusinessException/RedisOperationException/兜底 Exception 的映射不变；根包扫描只发现这一份 advice，旧 FQCN 不存在。
  - `infrastructure.redis.config.RedisScriptConfigWiringTests`（3）：5个脚本 bean 的名字/结果类型/脚本文本逐一等于 classpath `lua/*.lua` 内容（证明路径与内容都没搬）；`@Primary` 仍只在 `fixedWindowRateLimitScript` 与 `videoStatusIncrementScript`；旧 `config.RedisScriptConfig`、旧 `utils.redis.RedisUtils` 不存在。
  - `infrastructure.ratelimit.service.impl.RateLimitServiceWiringTests`（3）：按生产路径组件扫描后 `rateLimitServiceImpl` 唯一并实现 `RateLimitService`；注入的脚本与 `@Primary` 的 `fixedWindowRateLimitScript` 同一实例（泛型注入没被统计脚本抢走）；旧 `service/service.impl` FQCN 不存在。
  - `infrastructure.storage.TencentCosStorageWiringTests`（2）：`tencent.cos.*` 仍绑定到 `TencentCosProperties`；`COSClient`/`tencentCosStorageServiceImpl` 按原名注册、`FileStorageService` 唯一实现；`Key2Url`/`Url2Key` 互逆且 URL 约定不变；`BackendApplication` 的 `@EnableConfigurationProperties` 仍登记搬包后的配置类、前缀仍为 `tencent.cos`。COSClient 只用假配置构造（构造期不解析 DNS/不联网），测试结束 `shutdown()`。
  - 异常处理、限流、存储三类测试断言上下文中 DataSource/RedisConnectionFactory/RabbitMQ ConnectionFactory 计数为0；脚本配置测试仅导入没有外部连接依赖的RedisScriptConfig，不包含这三项计数断言。
- 验证（离线、串行、约束堆；日志保留在`/tmp`）：
  - `rm -rf target` 后全量 `test-compile`：258个生产源文件＋136个测试源文件（132基线＋4新增），BUILD SUCCESS；日志`/tmp/layout-r5-final-compile.log`。离线`maven-clean-plugin`仍不可用，用删除`target/`代替，不宣称 clean 命令通过。
  - 相关测试619项（67个直接 import shared/infrastructure 的测试类），0失败/错误，184跳过；日志`/tmp/layout-r5-related.log`。
  - 新增测试11项，0失败/错误/跳过；日志`/tmp/layout-r5-new-tests-2.log`。
  - 普通回归1194项、0失败/错误、228跳过（966项执行通过），BUILD SUCCESS；日志`/tmp/layout-r5-final-full.log`。
  - 与R4父级全回归1183项逐方法对照（只读`D:/feibijiubi/backend-layout-r4/target/surefire-reports`，按映射规范化3个迁移测试类FQCN）：1183项全部保留、0缺失、0结果变化，跳过数228不变，仅新增11项。
- 历史失败与新测试修正（日志保留）：`/tmp/layout-r5-new-tests-1.log`为6失败＋1错误，全部来自新测试自身的错误假设——原以为只有`fixedWindowRateLimitScript`带`@Primary`（实际`videoStatusIncrementScript`迁移前也带）、`@Import(实现类)`注册的 bean 名是全限定名而非`@Service`驼峰名、扫描 shared 包时`JsonUtils`需要`ObjectMapper`。修正是改新测试（改为按生产路径组件扫描并断言两个既有`@Primary`、补一个空`ObjectMapper`），没有削弱或改写任何既有项目断言，也没有改生产代码。另两个新增配置扫描测试原用精确集合等式，但扫描包含测试自身嵌套Configuration，改为包含生产配置类的断言；该证据只证明生产配置可被发现，不证明集合仅有生产配置。`layout-r5-compile-1/2.log`、`layout-r5-related.log`、`layout-r5-full.log`为过程/首轮通过证据。
- 未验证/限制：opt-in 隔离/集成用例（需真实 MySQL/Redis/RabbitMQ）未运行，其 import 只做静态核对；真实 Redis 脚本执行、broker 类型头收发、COS 上传、应用启动与打包未验证；候选工作树无application.yml，仅有.example；未读取主工作区或部署期真实配置，其按包名的配置（如`type-aliases-package`、`logging.level`）仍未核对（本批未改XML，XML全部使用FQCN）。
- R6 交接（不扩大本批范围）：`src/test/resources/video-status-isolated.properties`的`logging.level`仍只覆盖旧 mapper 包；`docs/tutorial/video-status-batch-aggregation-implementation.md`的旧实体 FQCN；`src/test/java/.../service/video/baseline/*`交互基线测试与`converter/ResponseConverterTests`所在测试包归位；`event`三类与 mapper XML 集中物理路径不得被“零残留”清理掉。
- 本批仅执行侧自验：不声称父级验收通过，未 stage/commit/push，未删除任何工作树。执行侧结论：READY_FOR_REVIEW。

### R5 父级验收记录

- 工作流`ee754733-6867-4c37-87ea-b2dfdbc42d3e`；执行`31367619-72e9-4b35-a0c9-d26e7905d5fd`、独立审查`e466eb04-0b38-46b4-9ce9-68671ca4094b`，均使用指定DeepSeek Flash 4.1；审查OK with notes。
- 父级逐项核对25条迁移：旧路径消失、package匹配，忽略package/import后所有正文与d566541一致。112个未迁移Java文件的正文变化只有5个接口的Javadoc类型引用，其余仅import；event及XML/Lua资源未变。
- 父级修正证据文字：19处MapperScan注解与2处Javadoc区分；基础设施不存在断言仅覆盖三类测试；脚本/COS新增扫描断言的调整及第四类失败原因明确披露。修正新增COS测试finally避免未启动上下文再次getBean掩盖原错误，脚本测试注释“其余四个”改为三个，无既有断言改动。
- shared.GlobalExceptionHandler依赖infrastructure.redis.RedisOperationException，而infrastructure.redis.operation又依赖shared.JsonUtils，因此模块级仍双向依赖；已纠正映射中“无循环”表述，不做业务边界治理、不宣称解耦。
- 父级清除本候选树生成的target后全量离线test-compile及普通回归通过：258生产/136测试源码；1194项、0失败/错误、228跳过，966项执行通过。日志`/tmp/layout-r5-parent-compile.log`、`/tmp/layout-r5-parent-full.log`；逐方法比较R4/R5 Surefire XML，1183项原测试全部保留、结果不变，仅新增11项。
- 历史新增测试6失败＋1错误已对照日志及修正位置核查并保留，不以最终通过抹去；真实Redis/MQ/COS/数据库、完整应用启动/生产行为仍未验证。按用户授权单独本地提交并fast-forward集成，不推送远程。
- R5本地提交：`ac7ad98`（`refactor: consolidate shared and infrastructure packages`），已fast-forward合入主工作区，未推送远程；R6即以该提交为detached基线。

### R6 执行记录（执行侧自验，非父级验收）

- 工作树：`D:/feibijiubi/backend-layout-r6`，干净 detached 基线`ac7ad98`（R5 父级提交，已合入主工作区）。本批只在该工作树写入，未 stage/commit/push，未改动主工作区与 R1–R5 工作树，未再委派，未读取 `application.yml`，未联网、未启动应用/容器/数据库/Redis/MQ/COS，未执行迁移。
- 范围与映射：迁移3个测试Java、新增2个DB-free结构回归类（9项）、补齐隔离测试的 mapper 日志级别、同步现行文档；逐项旧→新路径/FQCN、归属裁定、脚本/资源/反射/扫描审计、跨模块依赖、协议与资源例外、遗留项全部列在`docs/refactoring/r6-package-map.json`（先写映射再迁移，迁移后回填实测证据）。
  - `interaction.baseline`（测试作用域，2）：`VideoInteractionAdmissionBaselineSummary`及其`Tests`从已不存在的顶层`service/video/baseline`归位；沿用工程内已有的测试作用域子包先例`videostats.capacity`（同样是测量工具），不塞进`interaction.service`与 service 测试混排。`VideoInteractionAdmissionBaselineIsolatedTests`只跟着改一行 import。
  - `contract`（根测试包，1）：`ResponseConverterTests`同时断言user/video/videostats三个模块的输出映射，不归任一单模块，移到根包下并保持类名/断言/反射辅助方法不变。
  - 目录说明：README「项目结构」由旧技术分层改为按业务模块的现结构，并写清启动类、`event`例外、Mapper XML 集中路径；`docs/tutorial/video-status-batch-aggregation-implementation.md`的 XML 示例`resultType`与`docs/tutorial/video-status-capacity-audit.md`的工具目录同步为现路径；`docs/tutorial/video-upload-direct-cos-guide.md`仅在文首加“重构前设计示例、不可直接照抄”提示（正文未改写）。历史归档与 R1–R5 映射审计未批量改写。
- 逐文件正文核对：3个迁移文件与`git show ac7ad98:旧路径`在不计package/import行后正文完全一致（101/125/155行逐行相同）；唯一非迁移改动是 `VideoInteractionAdmissionBaselineIsolatedTests` 的一行 import。生产Java零改动。
- 日志级别补齐：`src/test/resources/video-status-isolated.properties`只新增`logging.level.com.feibijiubi.backend.{user.mapper,video.mapper,video.local.mapper,interaction.mapper,videosource.mapper,videostats.mapper,videostats.metrics}=WARN`（Maven 发现的7个 mapper 包逐包显式，因为`logging.level`是包前缀匹配，`video.mapper`不覆盖`video.local.mapper`），保留旧`...backend.mapper=WARN`（旧包已无接口）；其中`video.local.mapper`/`videosource.mapper`两行为审查反馈后补入；`spring.datasource`/`spring.data.redis`/`spring.rabbitmq`/`mybatis.*`/`app.video-status.*`逐字节未变。
- 新增DB-free结构回归9项（不连数据库、不启动Spring容器、不读`application.yml`）：
  - `architecture.PackageLayoutTests`（4）：全部生产与测试源文件的`package`声明逐个等于目录路径（本轮实测258/138个，数量不是硬编码断言）；包根集合严格等于8个批准模块＋`event`/`config`例外＋启动类；旧顶层技术分层目录在两侧源码根都不得出现。
  - `architecture.MyBatisMapperResourceTests`（5）：用与`mybatis.mapper-locations`相同的`PathMatchingResourcePatternResolver`模式解析`classpath:com/feibijiubi/backend/mapper/*.xml`，16个XML的集合、URL路径后缀与逐字节内容都与`src/main/resources`一致；16个XML用真实`XMLMapperBuilder`解析进同一个`Configuration`，16个namespace全部注册成`@Mapper`接口、接口每个方法都有同名MappedStatement；逐statement校验namespace属于已解析集合、SQL source非空、resultMap/parameterMap类型已解析且不指向旧集中包；`@Mapper`扫描根找到的接口集合与XML namespace集合一一对应（16对16）；由XML namespace推导出的每个mapper包都必须在隔离测试配置里有显式`logging.level.<包>=WARN`（读仓库内测试资源，不读`application.yml`/外部配置）。
  - 非空洞性验证（两项）：临时把`UserMapper.xml`的一个resultType改回旧包名后，结构测试2项失败（`Could not resolve type alias 'com.feibijiubi.backend.entity.User'`）；临时删掉`video.local.mapper`的WARN行后，`MyBatisMapperResourceTests`1项失败并点名缺失键。两次均按备份逐字节还原、`git status`确认资源无改动；日志`/tmp/layout-r6-mutation-probe.log`、`/tmp/layout-r6-fix-logger-probe.log`。
- 验证（离线、串行、约束堆；日志在`/tmp`）：
  - 本树开批时`target/`不存在（无旧class残留），全量`test-compile`：258个生产源文件＋138个测试源文件（136基线＋2新增），BUILD SUCCESS；日志`/tmp/layout-r6-compile-1.log`。
  - 首轮新增测试8项，0失败/错误/跳过；日志`/tmp/layout-r6-new-tests-1.log`（修复轮又增1个方法，见下方修正记录）。
  - 相关测试50项（16个XML对应的模块绑定测试＋迁移测试＋新结构测试），0失败/错误，3跳过（opt-in基线隔离用例）；日志`/tmp/layout-r6-related-1.log`。
  - 普通回归1202项、0失败/错误、228跳过（974项执行通过），BUILD SUCCESS；日志`/tmp/layout-r6-full-1.log`。
  - 与R5全回归1194项逐方法对照（只读`D:/feibijiubi/backend-layout-r5/target/surefire-reports`，规范化3个迁移测试类FQCN）：1194项全部保留、0缺失、0结果变化，跳过数228不变，首轮仅新增8项（修复轮累计9项）。
  - 离线`-DskipTests package`**未完成**：本地仓库缺`maven-jar-plugin:3.4.2`（`Cannot access central ... has not been downloaded`）。按约束未联网下载、未修改pom规避、未重复重试，也不把package写成通过，日志`/tmp/layout-r6-package-1.log`。此前用JDK `jar` 从`target/classes`生成的临时jar只能说明目录里有16个XML/5个Lua，**不作为** package 验收证据；jar内容与运行时装配仍未验证，需在具备插件的环境另行执行正式package。
- 跨模块依赖（如实记录，不断言无环）：`shared↔infrastructure`、`video↔interaction`、`video↔user`、`event↔videostats`等既有双向依赖保持原样；本批只搬测试包名与文档，不做服务边界治理，不宣称解耦。
- 例外保持：`event`三类FQCN原样（RabbitMQ类型头），未加alias/`idClassMapping`/不改converter；16个Mapper XML物理路径集中保留；Lua/fixture/SQL路径未动。
- 未验证/限制：opt-in隔离/集成用例（需真实MySQL/Redis/RabbitMQ）未运行；真实broker类型头收发、Redis脚本执行、COS、应用启动与jar打包均未验证；`application.yml`未读取；`docs/tutorial/video-upload-direct-cos-guide.md`正文仍留重构前`src`路径（已在其文首加“重构前设计示例、不可直接照抄”提示，未批量改写），`video-status-improvement-*.md`等历史交付/进度记录同样保留旧路径。
- 本批仅执行侧自验：不声称父级验收通过，未 stage/commit/push，未删除任何工作树。执行侧结论：READY_FOR_REVIEW。

### R6 审查反馈修正记录（执行侧自验，非父级验收）

- 触发：独立审查结论 CHANGES_REQUIRED（1×P1 + 3×P2），修复仍在原工作树、原授权边界内进行；未读取 `application.yml`，未联网、未启动真实服务，未 stage/commit/push。
- P1（隔离测试 mapper 日志只覆盖 7 个包中的 5 个）：`video-status-isolated.properties`追加`logging.level.com.feibijiubi.backend.video.local.mapper=WARN`与`...videosource.mapper=WARN`（仅日志，连接属性零改动），现覆盖 Maven 发现的全部 7 个 mapper 包；另新增 DB-free 回归`MyBatisMapperResourceTests#everyDiscoveredMapperPackageHasAnExplicitWarnLoggerInTheIsolatedTestProperties`，由 XML namespace 推导包集合并要求逐个显式 WARN（不读 `application.yml`/外部配置），现有结构断言一条未改、未削弱。
- P2（映射里 `PackageLayoutTests` 方法名与实际不符 4/4、第4项描述错）：`docs/refactoring/r6-package-map.json` 按实际方法名重写 `newTests`，并明确 `legacyTopLevelDirectoriesAreGoneFromBothSourceRoots` 检查的是生产与测试**两侧**源码根的14个旧目录名、与 event/config 例外无关；同时实测更新计数（新增测试方法 8→9）并删除已闭合的日志覆盖缺口。
- P2（结构测试硬编码当前规模）：两个结构测试类的 Javadoc 增「清单维护」段，写明这些清单是防回退护栏、新增模块/mapper 时应同步更新，而不是永久的业务限制。
- P2（`video-upload-direct-cos-guide.md` 仍留旧路径）：仅在该文首加阅读提示（重构前设计示例、旧 `src` 路径与已不存在类型不可直接照抄、现行结构以 README 与重构计划为准），正文24处旧路径与功能设计未批量改写。
- 打包证据（禁止用手工 jar 替代正式 package）：撤回以临时手工 jar 作打包验收证据的表述，明确 `-o -DskipTests package` 因离线缺 `maven-jar-plugin:3.4.2` 未完成；不联网、不改 pom、不重复尝试缺插件命令，jar 内容与运行时装配列为未验证。
- 复验（按父级要求只串行跑相关结构测试，未复跑全回归）：`PackageLayoutTests`(4) + `MyBatisMapperResourceTests`(5) = **9项，0失败/错误/跳过，BUILD SUCCESS**；日志`/tmp/layout-r6-fix-structural-final.log`（首次`/tmp/layout-r6-fix-new-tests-1.log`）。全回归上一轮1202项/228跳过，本轮新增1个方法，预期1203项，由父级复跑确认。
- 执行侧结论：**READY_FOR_REVIEW**（源码修复可审；`package` 仍缺离线插件依赖，未完成）。

### R6 父级验证与环境阻塞历史（阻塞现已解除，见下节）

- 首轮工作流`28f558cf-b7db-4d55-80e8-3b741cda9045`；修正/复审工作流`3a47bfcc-f0b6-43f2-87b7-d06fbafe93bb`。修正执行`752e269a-9e81-4269-a74e-487f899b79ab`，复审`1f166c69-fe53-4035-ba05-8aaede74aaec`；均为指定DeepSeek Flash 4.1。复审源码/目录PASS，正式打包NOT PASSED；未以复审通过覆盖首轮CHANGES_REQUIRED。
- 父级逐文件核对3条测试迁移正文完全一致；README/现行教程/日志配置差异符合范围；生产Java、XML/Lua、database零改动。已核对两个新增结构测试，修正日志级别解释：包级日志配置可以被子包继承，但旧backend.mapper及video.mapper不覆盖兄弟路径video.local.mapper，不得误写成普遍的“父包不覆盖子包”。
- 父级清除本候选树生成target后全量离线test-compile通过：258生产/138测试源码，日志`/tmp/layout-r6-parent-compile.log`；普通全回归1203项、0失败/错误、228跳过，975项执行通过，日志`/tmp/layout-r6-parent-full.log`；最终文字修正后再跑普通全回归仍为同一数字，日志`/tmp/layout-r6-parent-final-full.log`。按映射规范化类名后逐方法核对：R5的1194项全部保留、结果不变，仅新增9项；进一步累计核对R1的1153项全部保留、结果不变，新增50项。结合R1已核实的R0保留证据，总体1149→1203（新增54），228项跳过不变。
- 父级亲自执行离线`-DskipTests package`仍失败：本地缺`maven-jar-plugin:3.4.2`，日志`/tmp/layout-r6-parent-package.log`。未联网补插件、未修改pom绕过、未把临时手工jar当作正式产物。R6源码/测试验证可接受，但打包门禁未通过，因此暂不stage/commit/合入，保留候选树全部改动，等待允许补齐打包依赖或提供离线依赖环境。
- 父级累计映射审计脚本首次把非迁移条目当作FQCN映射而触发KeyError；过滤具备oldFqcn/newFqcn的条目后完成逐方法比对，不涉及业务测试失败。结构故意破坏探针失败记录保留，资源已恢复；先前R2 runner崩溃根因仍未查明。
- 当时已提交并合入的后续板块：R2 `e451467`、R3 `0215ce4`、R4 `d566541`、R5 `ac7ad98`；R1既有提交`29ff843`。均未推送远程。真实MySQL/Redis/RabbitMQ/COS/TLS、应用启动与生产验收仍未执行；event消息类FQCN与XML物理路径继续保留兼容例外。

### R6 打包阻塞解除与最终父级验收

- 用户明确授权联网补齐Maven打包依赖后，父级执行`MAVEN_OPTS='-Xms64m -Xmx256m' ./mvnw -DskipTests package`成功，日志`/tmp/layout-r6-parent-online-package.log`。联网范围仅为构建依赖解析，未启动应用/容器或连接真实数据库/Redis/MQ/COS，未读取application.yml，未推送远程。
- 随后按第6节同一测试选择与opt-in关闭参数，串行执行离线`./mvnw -o ... package`（本次没有skipTests）：普通全回归1203项、0失败/错误、228跳过，975项执行通过，正式Maven JAR打包及Spring Boot repackage全部BUILD SUCCESS，日志`/tmp/layout-r6-parent-offline-package.log`。原先离线缺插件失败保留，不再是当前阻塞。
- 正式产物：候选树`target/backend-0.0.1-SNAPSHOT.jar`。父级静态检查全部258个生产顶层class均存在且class major=61（Java 17目标）；16个Mapper XML、5个Lua在BOOT-INF/classes中与源码逐字节一致；原event消息FQCN保持、旧生产类目录及测试类未混入；Boot JarLauncher、启动类清单及BOOT-INF/lib依赖齐备。日志`/tmp/layout-r6-parent-jar-audit.log`。未运行JAR，不能据此宣称真实基础设施或生产验收通过。
- 最终接受范围：R1–R6目录拆分、源码路径与包名、离线测试守恒、DB-free装配、文档同步、正式打包及产物内容。按用户授权R6单独本地提交并fast-forward集成；不删除保留工作树、不推送远程。
- 保留的设计例外：event三类原FQCN用于消息兼容；Mapper XML集中物理路径用于部署配置兼容；少量全局config装配；已知跨模块双向依赖没有借机重构。真实服务、应用启动、外部部署配置、线上滚动兼容仍未验证。
