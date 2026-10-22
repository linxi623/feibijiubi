# Improvement 续作记录（2026-10-22）

目标仍是完成 T09—T16 全部任务（T07/T08/T09 已验收，T10 进行中）；本记录不替代主计划验收表，不代表生产上线许可。

## 本轮落地

- 主分支 `95af60d`：合入 T07 发布/confirm/回写错误分类、三份独立预算及窄范围管理员审计重放。确认成功后回写持续失败不再无界重投，第 10 次进入 FAILED。人工重放仍保留原 eventId，拒绝已有 consumed 等不能证明安全的情形。
- 父代理在候选和主分支各运行一次 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test`：每次 61 tests，0 failures/errors/skipped。主分支日志 `/tmp/improvement-t07-main.log`。只使用独立 compose.test.yaml。
- 审查修正：confirm-writeback 迁移去掉 `AFTER lease_recovery_count`，避免不必要的脚本顺序依赖；发布前仍必须执行全部 T07 迁移。重放预算归零有真实 SQL 和隔离测试行值断言，审查提出的“只返回零但数据库未清预算”假设不成立。
- T07 已在 2026-10-22 完成测试环境验收：父代理在主分支实跑 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests,VideoStatusIsolatedIntegrationTests,VideoStatusMetricsIsolatedTests,VideoStatusSchedulingRealFlowTests,VideoStatusDirtyRecoveryRealConvergenceTests' test`，结果 87 tests，0 失败/错误，1 skipped（可选 benchmark），日志 `/tmp/improvement-main-regression3.log`。早前整组 3 failures 的历史保留在回归续作文档。
- T11 已在主仓库基线 `dc41477` 上 `git merge --squash be7d836` 暂存，并于 2026-10-22 完成测试环境验收。父代理实跑 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests' test`：3 tests，0 failures/errors/skipped，日志 `/tmp/t11-real-convergence.log`；输出 `T11 real convergence vids=250 head=100 tail=150 flushRounds=1 oracleTotal=250`。真实 MySQL/Redis，专用头部 SADD 故障注入，四轮尾部获机会，最终 250 收敛且无 status3。候选 `be7d836` 修复了预算在首个 vid 未执行时回退/丢失游标的缺陷。该故障不是进程崩溃，结果不是生产验收。

- T09 第一阶段已合入主分支 `97c7357`（候选 `aab1c64`），并于 2026-10-22 完成**测试环境最终验收**。父代理在主分支实跑普通回归 `./mvnw '-Dtest=*Tests,!BackendApplicationTests' test`：356 tests，0 失败/错误，108 skipped，日志 `/tmp/t09-main-unit.log`；opt-in 隔离回归 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusStatus0CompensationTests,VideoStatusStatus0CompensationAuthorizationTests,VideoStatusStatus0CompensationIsolatedIntegrationTests,VideoStatusIsolatedIntegrationTests,VideoStatusOutboxReliabilityIsolatedTests' test`：62 tests，0 失败/错误，1 skipped，日志 `/tmp/t09-main-it.log`。候选早前 43 tests，0 失败/错误/跳过（含 8 真实 IT，`/tmp/t09-parent-final.log`）。默认关闭 enabled/integrity/allow-unproven 三闸门；processed 缺失是 `EVIDENCE_UNKNOWN`，不能判未应用；无证据模式仍受控/默认关，不保证任意 Redis 数据丢失；yaml 非热更新，事故需停全部补偿角色并等待 inflight/租约，不强杀 confirm。早前错开关跳过测试、mapper 参数/时区失败历史已保留，未记录凭证。
## 保留候选与下步

1. T07：**已完成（测试环境验收）**。主分支 `95af60d`，全链路回归 87 tests，0 失败/错误，1 skipped（`/tmp/improvement-main-regression3.log`）；T07 单组 61 tests 0 失败/错误/跳过。非生产验收、非真实进程崩溃/两 JVM 演练。
2. T08：**已完成（测试环境验收）**。已 cherry-pick 到主分支 `0dd98ad`、`b846032`、`92eb8c1`（候选 `b796fc8`）。父代理在独立候选父复验 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusIsolatedIntegrationTests,VideoStatusConsumerRetryLifecycleIsolatedTests' test`：22 tests，0 失败/错误，1 skipped，日志 `/tmp/t08-parent-final.log`；真实 10/30/120s TTL、容器 stop/start、Redis `CLIENT PAUSE 120000`（非进程/网络断连）。
3. T09：**已完成（测试环境验收）**。主分支 `97c7357`（候选 `aab1c64`）；普通回归 356 tests，0 失败/错误，108 skipped（`/tmp/t09-main-unit.log`）；opt-in 隔离回归 62 tests，0 失败/错误，1 skipped（`/tmp/t09-main-it.log`）。无证据模式保持受控/默认关，不能盲目重放超龄或丢失幂等证据的事件，也不保证任意 Redis 数据丢失。非生产验收。
4. T10：**进行中**。独立工作树已有第一阶段 15 个单测候选，**未合入**主分支；DLQ/Outbox FAILED/status3 对账整项尚无完成证据，不能勾选或宣称闭环完成。
5. T11：**已完成（测试环境验收）**，按计划允许在 T06 后提前交付，原因是独立的 dirty 恢复公平性/失败退避改进，不依赖 T07—T10。验收后主计划已勾选，详细证据见 `video-status-improvement-plan.md` 的 T11 验收记录。
6. T12—T16 设计调研只有建议，不是实现或验收；读路径选择、归档保留、防刷产品语义、压测目标必须在实施前固定并记录理由。尤其删除消费账本不能仅靠 processed TTL 判安全。

## 执行约束

- cn/global DeepSeek 均衡使用；独立任务可并发4路，编辑任务使用独立工作树。
- 子代理报告需父代理查代码/复跑；未验证的API建议不可直接照搬。
- 不连接正式资源、不部署生产、不清除幂等/门闩/失败证据。隔离数据保留。
