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
| R2 | 用户与鉴权 | user/auth及必要引用；共享类不强行搬入某个业务 | Login/Admin拦截器装配、Token/用户接口、Bean名稳定 | R1已合入，准备派发 |
| R3 | 视频、分类、审核、互动 | video/interaction及必要引用 | 发布可用性守卫、原事务/幂等/限流、Mapper绑定 | 待R2验收 |
| R4 | 视频统计流水线 | videostats以及专属配置/事件/MQ/指标 | 队列名/消息序列化类型头、锁/事务/调度器Bean名、旧包兼容风险 | 待R3验收 |
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
