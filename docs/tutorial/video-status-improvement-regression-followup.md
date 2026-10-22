# 2026-10-22 回归续作（未完成项必须保留）

## 已完成

- T11 `f494106` 已合入，父代理真实 MySQL/Redis 3 IT 通过（`/tmp/t11-real-convergence.log`）；250 vid 前100注入SADD故障，后150四轮内获机会，解除后总计数250，未误入status3。
- 主分支排除 contextLoads 普通回归：`./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`，289 tests，0 failures/errors，95 skipped（opt-in集成），`/tmp/improvement-main-unit.log`。

## 尚未通过的整组回归

父运行：
`./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests,VideoStatusIsolatedIntegrationTests,VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests,VideoStatusDirtyRecoveryRealConvergenceTests' test`
结果87 tests，3 failures，0 errors，1 skipped，日志 `/tmp/improvement-main-regression.log`。
失败均旧 IsolatedIntegrationTests：维护排空、真实Outbox全链路、负依赖重试。均30秒超时，不能宣布T07最终验收。

`8e81d55` 修正一个测试观测错误：重试后等待 status1 应同时允许已快速落库的status2；没有放宽最终计数/队列清空要求。尚未独立复跑该修改。

诊断方向：多个Spring上下文的真实listener共享测试队列、缓存context仍消费、异步恢复和测试手动stop/start竞态、T08新增30/120s队列遗留及旧类队列清理范围。必须用broker consumer/队列数据证明，不要只凭猜测改生产协议。

子代理尝试在markRedisApplied事务提交前补SADD；父已撤销该未提交改动：提交前SADD仍可能被flush提前取走，并未封闭竞态，而且已有DB反哺恢复契约，不能用未经证明的业务改动修测试。原生产类未变。

## T08 候选保留

工作树 `D:/feibijiubi/backend-improvement-t08`，HEAD `ff8dc57`，有3个未提交文件：恢复控制器isRecoveryInProgress可见性、LifecycleIT清理/等待、旧IT DirtiesContext。
父首次运行22 tests，2fail1error1skip，`/tmp/t08-real-lifecycle.log`。子代理后续复跑超时退出，无最终报告；最新Lifecycle XML显示5tests0fail，但不能据此宣称整组通过。`/tmp/t08-final-combined.log` 等为PowerShell UTF-16，需正确解码；旧IT XML仍有100轮顺序用例round0未命中spy的超时。
子代理均已退出，jps确认无残留测试JVM后已停止独立compose环境，数据保留。下次先查git diff、日志及XML时戳，再复验。不要削减100轮或把阻塞钩子移离指定时序来通过测试。

## T09 协议设计边界

Lua中current/processed对其它客户端原子可见，但Redis运行时错误不保证回滚，不能称任何数据丢失都安全。status0缺processed既可能未应用也可能证据丢失，不能一律误隔离合法事件，也不能盲目重放。默认关闭、preview、完整性确认/事故闸门、安全年龄、原eventId+指纹、短事务租约后发布是候选方向，尚未实现/验收。单独丢processed应进入受控修复边界，不能靠推断自动恢复。

T10/T12—T16仍未实现；用户总目标未变，不把本轮阶段结果当全计划完成。
