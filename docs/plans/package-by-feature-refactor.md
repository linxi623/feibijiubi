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
| R3 | 视频、分类、审核、互动 | video/interaction及必要引用 | 发布可用性守卫、原事务/幂等/限流、Mapper绑定 | 父级离线验收通过，纳入本批本地提交（见R3验收记录） |
| R4 | 视频统计流水线 | videostats以及专属配置/事件/MQ/指标 | 队列名/消息序列化类型头、锁/事务/调度器Bean名、旧包兼容风险 | R3已验收，准备派发；先过消息类型兼容闸门 |
| R5 | 通用能力收口 | shared/infrastructure/全局装配 | 避免新的大杂烩、全局异常/脚本/存储Bean仍可装配 | 待R4验收 |
| R6 | 全局扫描、目录说明、最终验收 | 空目录/残留引用/架构测试/文档 | 测试数量守恒、包路径一致、XML解析装配、打包和运行边界说明 | 待R5验收 |

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
