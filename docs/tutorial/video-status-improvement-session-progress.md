# Improvement 续作记录（2026-10-22）

目标仍是完成 T07—T16 全部任务；本记录不替代主计划验收表，不代表生产上线许可。

## 本轮落地

- 主分支 `95af60d`：合入 T07 发布/confirm/回写错误分类、三份独立预算及窄范围管理员审计重放。确认成功后回写持续失败不再无界重投，第 10 次进入 FAILED。人工重放仍保留原 eventId，拒绝已有 consumed 等不能证明安全的情形。
- 父代理在候选和主分支各运行一次 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusOutbox*Tests' test`：每次 61 tests，0 failures/errors/skipped。主分支日志 `/tmp/improvement-t07-main.log`。只使用独立 compose.test.yaml。
- 审查修正：confirm-writeback 迁移去掉 `AFTER lease_recovery_count`，避免不必要的脚本顺序依赖；发布前仍必须执行全部 T07 迁移。重放预算归零有真实 SQL 和隔离测试行值断言，审查提出的“只返回零但数据库未清预算”假设不成立。
- T07 主计划仍待最终验收，应补全全链路回归并检查验收逐项覆盖，不能因合入就自动勾选。

## 保留候选与下步

1. T08：`D:/feibijiubi/backend-improvement-t08`，`b796fc8`，基于 T07 `eec1e01`。三级 10/30/120s TTL 队列、异步容器停止/恢复、ACK 与业务异常隔离已有候选及单测。尚缺父代理审查、真实容器恢复/TTL/120秒Redis故障验收。不要重复合入其 T07 基线，只取 T08 提交。
2. T11：`D:/feibijiubi/backend-improvement-t11`，`410a6b8` + `3046a2b`，基于 `22977ed`。keyset 公平扫描、4096 项有界本地失败退避、只 SADD 不 SREM。允许在 T06 后提前，原因是独立正确性/公平性改进。父代理运行 `./mvnw -DvideoStatusIT=true '-Dtest=VideoStatusDirtyRecoveryFairnessIsolatedTests,VideoStatusDirtyRecoveryFairnessTests,VideoStatusDirtySchedulingTests' test`：首次2个IT因测试用户名超过schema限制报错，修正后6 tests全部通过，日志 `/tmp/improvement-t11-verify2.log`。真实MySQL，Redis SADD 使用测试桩；尚未证明真实Redis健康视频落库收敛，不能当全验收。
3. T09/T10 尚未实施；应在 T08 验收后推进安全补偿、受审计失败处置，不能盲目重放超龄或丢失幂等证据的事件。
4. T12—T16 设计调研只有建议，不是实现或验收；读路径选择、归档保留、防刷产品语义、压测目标必须在实施前固定并记录理由。尤其删除消费账本不能仅靠 processed TTL 判安全。

## 执行约束

- cn/global DeepSeek 均衡使用；独立任务可并发4路，编辑任务使用独立工作树。
- 子代理报告需父代理查代码/复跑；未验证的API建议不可直接照搬。
- 不连接正式资源、不部署生产、不清除幂等/门闩/失败证据。隔离数据保留。
