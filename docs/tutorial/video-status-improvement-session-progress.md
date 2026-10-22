# Improvement 续作记录（2026-10-22）

目标仍是完成 T07—T16 全部任务；本记录不替代主计划验收表，不代表生产上线许可。

## 本轮落地

- 主分支 `95af60d`：合入 T07 发布/confirm/回写错误分类、三份独立预算及窄范围管理员审计重放。确认成功后回写持续失败不再无界重投，第 10 次进入 FAILED。人工重放仍保留原 eventId，拒绝已有 consumed 等不能证明安全的情形。
- 父代理在候选和主分支各运行一次 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test`：每次 61 tests，0 failures/errors/skipped。主分支日志 `/tmp/improvement-t07-main.log`。只使用独立 compose.test.yaml。
- 审查修正：confirm-writeback 迁移去掉 `AFTER lease_recovery_count`，避免不必要的脚本顺序依赖；发布前仍必须执行全部 T07 迁移。重放预算归零有真实 SQL 和隔离测试行值断言，审查提出的“只返回零但数据库未清预算”假设不成立。
- T07 主计划仍待最终验收，应补全全链路回归并检查验收逐项覆盖，不能因合入就自动勾选。
- T11 已在主仓库基线 `dc41477` 上 `git merge --squash be7d836` 暂存，并于 2026-10-22 完成测试环境验收。父代理实跑 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryRealConvergenceTests,VideoStatusDirtyRecoveryFairnessIsolatedTests' test`：3 tests，0 failures/errors/skipped，日志 `/tmp/t11-real-convergence.log`；输出 `T11 real convergence vids=250 head=100 tail=150 flushRounds=1 oracleTotal=250`。真实 MySQL/Redis，专用头部 SADD 故障注入，四轮尾部获机会，最终 250 收敛且无 status3。候选 `be7d836` 修复了预算在首个 vid 未执行时回退/丢失游标的缺陷。该故障不是进程崩溃，结果不是生产验收。

## 保留候选与下步

1. T07：仍为**待验收**。实现提交 `eec1e01`，父代理已复跑 61 tests 0 失败，但全链路回归和验收逐项覆盖尚未完成，不勾选。
2. T08：**实施中**。候选 `D:/feibijiubi/backend-improvement-t08`，`b796fc8`，基于 T07 `eec1e01`。三级 10/30/120s TTL 队列、异步容器停止/恢复、ACK 与业务异常隔离已有候选及单测。尚缺父代理审查、真实容器恢复/TTL/120 秒 Redis 故障验收。不要重复合入其 T07 基线，只取 T08 提交。
3. T09：**设计中**。仅有安全补偿的设计调研，尚无实现或验收证据；需在 T08 验收后推进，不能盲目重放超龄或丢失幂等证据的事件。T10 尚未开始。
4. T11：**已完成（测试环境验收）**，按计划允许在 T06 后提前交付，原因是独立的 dirty 恢复公平性/失败退避改进，不依赖 T07—T10。验收后主计划已勾选，详细证据见 `video-status-improvement-plan.md` 的 T11 验收记录。
5. T12—T16 设计调研只有建议，不是实现或验收；读路径选择、归档保留、防刷产品语义、压测目标必须在实施前固定并记录理由。尤其删除消费账本不能仅靠 processed TTL 判安全。

## 执行约束

- cn/global DeepSeek 均衡使用；独立任务可并发4路，编辑任务使用独立工作树。
- 子代理报告需父代理查代码/复跑；未验证的API建议不可直接照搬。
- 不连接正式资源、不部署生产、不清除幂等/门闩/失败证据。隔离数据保留。
