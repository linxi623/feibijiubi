package com.feibijiubi.backend.videosource.vo;

import java.util.List;

/**
 * M3-B：上游单个作品详情的<b>脱敏</b>响应体 —— 只回答"这个作品有哪些线路、每条线路有哪些条目"，
 * 不提供任何可播放凭据。
 *
 * <h2>为什么没有播放地址、线路键和时长原文</h2>
 * <ul>
 *   <li><b>地址</b>：上游播放地址可能带签名 token，且尚未经过任何探测；返回它等于把未验证的直链交给
 *       浏览器。本切片只做"目录/详情预览"，不是播放能力。</li>
 *   <li><b>线路键</b>：上游自由文本（可能塞 URL/签名），且只是上游自己的命名，不证明格式。</li>
 *   <li><b>时长原文</b>：作品级文本提示（例如 {@code "45分钟"}），不是单集秒数，也不是可确认时长。
 *       这里只暴露 {@code hasDurationHint} 表示"上游是否给了这段文本"，值本身不返回。</li>
 * </ul>
 *
 * <h2>{@code lineIndex} / {@code entryIndex} 是什么</h2>
 * <p>它们只是<b>本次快照里的位置</b>（从 1 开始），用于展示与排查。<b>不是</b>剧集 ID、不是线路 ID，
 * 也不能当持久键：上游调整一次顺序，同一个数字就指向另一条。本地身份、映射与时长确认是后续切片的事，
 * 本接口不生成任何候选 token。</p>
 *
 * <h2>前端必须做的处理</h2>
 * <p>{@code title} / {@code categoryName} / 条目 {@code label} 都是<b>上游不可信文本</b>，渲染必须转义。
 * 这里只保证不返回结构性敏感字段（地址、线路键、时长原文），不能承诺所有自由文本里都不含 URL。</p>
 *
 * @param sourceCode      部署白名单引用键（回显请求参数）
 * @param workIdentity    上游作品身份（规范十进制字符串），不是本地 {@code vid}
 * @param title           标题快照（上游不可信文本）
 * @param categoryId      上游分类标识，未给出时为 {@code null}
 * @param categoryName    上游分类名（上游不可信文本），未给出时为 {@code null}
 * @param hasDurationHint 上游是否提供了作品级时长文本提示；<b>不</b>代表时长已知或可信
 * @param lines           播放线路（上游顺序），至少一条
 */
public record ExternalVideoSourceDetailVO(
        String sourceCode,
        String workIdentity,
        String title,
        String categoryId,
        String categoryName,
        boolean hasDurationHint,
        List<Line> lines
) {

    public ExternalVideoSourceDetailVO {
        lines = List.copyOf(lines);
    }

    /**
     * 一条播放线路。
     *
     * @param lineIndex 本线路在快照中的位次（1 起），仅展示用，不是线路 ID
     * @param entries   该线路的条目（上游顺序）
     */
    public record Line(int lineIndex, List<Entry> entries) {

        public Line {
            entries = List.copyOf(entries);
        }
    }

    /**
     * 一个分集条目。
     *
     * @param entryIndex 该条目在本线路快照中的位次（1 起），仅展示用，<b>不是</b>剧集身份
     * @param label      上游分集标签（上游不可信文本；同名标签允许重复，本接口不去重）
     */
    public record Entry(int entryIndex, String label) {
    }
}
