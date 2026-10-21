# 改进计划阶段交接（2026-10-21）

> 以下为第一轮退回时的历史记录，保留失败证据。**最新状态：T02/T03/T04 已修正、父代理验收并合入**（a9a3ad9 / c4d6e99 / fe11d03），87单测通过，62隔离用例0失败1可选跳过。最新入口以 `video-status-improvement-plan.md` 表格与最终验收记录为准；下一项T05。

## 已验收并合入

T01：`65fa3fd`。主分支仅合入测试、基线报告与执行计划，没有合入业务变更。父代理独立复验：31 个无基础设施单测通过；25 个隔离用例中 24 通过、1 个可选 benchmark 跳过。真实 MQ 消费者的 +1 状态提交用 latch 暂停，100 轮均为 pending 分支，依赖确认后 DB=Redis=0，两个合法事件均 FLUSHED。详见基线报告 §8。

## T02：退回，未合入

- 实现模型：`my-workbuddy/cn:deepseek-v4.1-flash`；交叉审查：`my-workbuddy/global:deepseek-v4.1-flash`。
- 候选工作树：`D:/feibijiubi/backend-improvement-t02`，检查点 `6b95cf6`（明确 WIP，不是验收完成）。cn 子代理后续任务超时，未提交完整交付报告，父代理保存候选以免丢失。
- 父代理命令：`./mvnw '-Dtest=*Tests,!BackendApplicationTests,!VideoStatusIsolatedIntegrationTests,!VideoStatusSqlBaselineTests,!VideoStatusCounterIsolatedTests' test`。
- 结果：38 tests，1 failure，0 errors。失败为 `VideoStatusCounterRangeTests.cumulativeAdditionUsesAddExact:73`：`addExact(0,-1,...)` 没有抛出测试期望的范围异常。需明确该方法是“有符号增量累计”还是“非负当前值更新”，不能机械改断言掩盖契约问题。
- 交叉审查指出 Lua 首次写入前校验仍需加强：TTL 只检查正数，未拒绝小数/过大值；若末尾 SET EX 报错，前面的 HINCRBY 不会回滚。需结合实际 Java 入口约束复核，并新增原子失败测试。当前/增量的前导零及加号也可能通过自定义校验但被 Redis 整数解析拒绝；应统一规范十进制格式。热度必须复核有限值与现存 score 相加产生 NaN 的失败窗口。
- 计数隔离测试尚未由父代理执行，不能声称 DB/Redis 大计数全链路通过。同步 SQL 影响 0 行后的业务回滚、缺行/下界/上限分类、重建累计范围、迁移/回滚文档、API 与 AGENTS/CLAUDE 同步仍需完成验收。
- T02 修为返回 0 行之后会暴露现有 T03 误隔离路径，不能单独上线。

## T04：候选已交付，待依赖与最终验收

- 实现模型：`my-workbuddy/global:deepseek-v4.1-flash`。
- 独立工作树：`D:/feibijiubi/backend-improvement-t04`；候选提交 `746c50e`，未合入主分支。
- 提前准备依据：计划允许 T01 后独立准备配置补丁；仍按 T03 后顺序交付，不代表绕过正确性门槛。
- 父代理复跑：`./mvnw '-Dtest=VideoStatusOutboxRelayTests,VideoStatusRuntimeRoleContractTests' test`，19 tests 全通过。
- 剩余：轻量 Spring 配置绑定/启动校验验证（不启动业务基础设施）、实际角色诊断核对、同步/异步维护切换演练、文档一致性验收。不能仅凭 Mockito 单测把 T04 标为完成。

## 下一步与安全边界

1. 在 T02 工作树修复契约与 Lua 原子失败路径，跑新计数隔离测试，交父代理复验。
2. 实施 T03：可暂态的负增量延后、退避、核查；将 T01 特征化测试转为明确收敛回归断言，再决定 T02/T03 合入与上线资格。
3. 验收 T04 后才继续 T05—T16；不得直接启用补偿、归档或部署。
4. 本轮测试只用 `compose.test.yaml` 的 `feibijiubi-test`（13307/16379/15673/15674）；保留测试数据与卷。用户原有暂存文档删除未被修改或提交。
