package com.feibijiubi.backend.videostats.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 重建门闩（rebuild gate）的只读观测结果。
 *
 * <p>门闩是无 TTL 的字符串，本身不携带创建时间。本轮把持有者 token 约定为
 * {@code <epochMillis>-<uuid>}：老格式（纯 UUID）没有时间戳，年龄只能返回
 * {@code null} + {@code LEGACY_UNKNOWN}，而不是猜一个值。给门闩补 TTL 会
 * 破坏重建安全性，因此本接口只读，绝不改写键或 TTL。</p>
 */
@Data
@AllArgsConstructor
public class VideoStatusGateVO {
    private final Integer vid;
    /** 门闩是否存在；false 时其余时间字段为 null。 */
    private final boolean present;
    /** 门闩值的格式：EPOCH_MILLIS_TOKEN / LEGACY_UNKNOWN。 */
    private final String tokenFormat;
    /** 门闩已经存在多久（毫秒）；老格式无法得知时为 null。 */
    private final Long tokenAgeMillis;
    /** 观测边界说明，便于运维判断“未知”是设计而不是故障。 */
    private final String note;
}
