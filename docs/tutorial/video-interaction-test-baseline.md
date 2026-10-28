# T16-B0：同步准入的测试环境基线

本轮用户决定：T16 仅做测试环境基线测量，不设生产验收结论。本切片只测同步计数准入，不代表整个 T16 完成。

## 范围与安全边界

- 测试：`VideoInteractionAdmissionBaselineIsolatedTests`，仅显式 `-DvideoStatusBaseline=true` 启用；普通测试跳过。
- 实际服务调用，包含 Redis 两次固定窗口准入脚本与 MySQL 同步计数事务；非 HTTP、无 RabbitMQ、无异步投递、无统计或清理调度器。
- 复用隔离守卫，仅 MySQL 13307、Redis 16379；基础表与 T15 表缺失时失败，不自动迁移。
- 单 JVM、串行、小样本诊断，不是吞吐、容量、并发或 SLA 验收。
- 两个计数场景各 20 次独立视频预热，排除在采样之外。分位数采用 nearest-rank。
- `admission` 是服务调用开始到返回的耗时。`post-return read` 是返回后到数据库查询结束的耗时，不包含 commit 到服务返回的部分，**不是 commit 耗时或 commit→可见延迟上界**。可见性由读取值与独立预设值相等证明。

## 父代理实测（2026-10-25）

启动现有隔离 MySQL/Redis 的首次尝试曾遇 Docker API HTTP 500，MySQL 退出 255，根因未确认；经用户批准仅重试一次，两个容器健康。RabbitMQ 始终停止，无容器重建、数据卷删除或迁移。

命令（受限堆，Maven 256 MiB、测试 512 MiB，启用 self-attach）：

```bash
./mvnw -DvideoStatusBaseline=true -DvideoStatusIT=false -DvideoStatusScale=false \
  -Dtest=VideoInteractionAdmissionBaselineIsolatedTests test
```

结果：**3 tests，0 failures/errors/skipped，BUILD SUCCESS**。日志 `/tmp/t16-b0-parent-baseline.log`，单次运行，不重复造数。

| 场景 / 指标 | 样本 | P50 ms | P95 ms | P99 ms |
|---|---:|---:|---:|---:|
| 单视频 admission | 200 | 22.760 | 34.886 | 46.806 |
| 单视频 post-return read | 200 | 1.031 | 1.769 | 2.032 |
| 十视频 admission | 200 | 17.673 | 24.199 | 30.591 |
| 十视频 post-return read | 200 | 0.769 | 1.292 | 1.541 |
| 已完成请求重放 admission | 100 | 1.744 | 2.952 | 3.344 |

这些是本机、本次、串行样本的诊断数字，不能推导生产 QPS 或异步数据库可见延迟。

## 独立结果与保留数据

- 单视频计数 200，十视频各 20，另有两组各 20 次预热。
- 请求行 440，COUNTED 440，SUPPRESSED 0，Outbox 0。
- 100 次重放不新增计数、请求或推进窗口时间。
- 本次保留 1 用户、13 视频、440 请求行、440 窗口行及准入 Redis key；没有删除 DB 行/Redis key，没有改动 dirty/gate/processed，没有 purge 队列。
- 不要为恢复任务重复运行已成功基线。再次测量会留下新数据，应明确作为新一轮采样。

## 未覆盖

异步 MQ 链路、HTTP、并发压力、真实故障恢复、生产容量与上线验收均未覆盖。T15 清理隔离测试仍未运行，新清理索引迁移仍未执行，自动清理保持默认关闭；本次基线不替代清理正确性验收。
