# videosource/applecms 合成 fixture

这里的 JSON **全部是合成的**：用于 E2-B 离线解析器的单元测试，不含任何真实站点内容。

- 结构（字段名、`code=1`、`$$$` 线路分隔、`#` 分集分隔、`$` 标签/地址分隔、`limit` 是字符串、
  `page`/`pagecount`/`total` 是整数、`vod_duration` 为空、`vod_total` 与播放条目数矛盾）
  参照 `docs/tutorial/external-video-source-probe.md` 里记录的一次性只读探测结果。
- 标题、线路键、分类名、演员、简介、备注、地址**全部为编造**，域名只用 `example.invalid` /
  `media.example.invalid` / `alt.example.invalid`（保留域名，不可解析）。没有复制任何上游文本、
  签名参数或媒体地址。
- 因此这些 fixture **不证明**任何真实站点可用，也**不**代表"兼容所有苹果 CMS / 海洋 CMS 版本"：
  它们只锁定本适配器声明的结构契约。

## 文件

| 文件 | 内容 | 刻意保留的陷阱 |
|---|---|---|
| `list-two-items.json` | 2 条目录条目、`limit` 为字符串、第 1 条自带 `vod_play_*` 与嵌套未知对象、第 2 条没有播放地址并带 `vod_duration: "45分钟"` | 列表里的播放字段必须被忽略；`vod_id` 一条是整数、一条是字符串 |
| `detail-two-lines.json` | `vod_play_from` / `vod_play_url` 两条线路、每线 3 条条目 | `vod_duration: ""`（未知，不是 0）；`vod_total: 1` 与 3 条播放条目矛盾；两条线路使用相同标签（不得去重）；第 3 条地址 query 里出现 `$`（只按第一个 `$` 切分）；`%2F` 编码的 query 原样保留 |

两处 `example.invalid` 端点都必须在测试里显式配置进允许列表；解析器不会因为响应里出现某个域名就放行它。

## 不在 fixture 里的用例

上限/轰炸类用例（512 KiB body 边界、`$$$` 与 `#` 分隔符轰炸、线路 16 条与分集 1000 条的恰好边界、
超长文本、重复字段、脱敏 `toString`）都在 `AppleCmsJsonAdapterTests` / `AppleCmsSnapshotModelTests`
里**内联构造**，因为它们是“边界数字”而不是“一份真实响应形状”；放进 fixture 反而会把上限与文件字节数
耦在一起。fixture 只锁定结构与字段语义，不锁定上限。
