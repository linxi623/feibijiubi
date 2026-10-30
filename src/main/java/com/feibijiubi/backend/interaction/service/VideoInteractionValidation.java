package com.feibijiubi.backend.interaction.service;

import com.feibijiubi.backend.common.BusinessException;

import java.util.regex.Pattern;

/**
 * T15：交互请求字段规则的<b>唯一</b>出处，DB 核心与 HTTP 准入层共用。
 *
 * <p>为什么不是两处各写一份校验：准入层的顺序契约要求“参数校验 → 视频有效性 → 查重放 →
 * 限流 → record”，也就是说参数校验必须发生在任何 Redis 计数之前。如果只有 DB 核心（位于
 * 限流之后）校验，一个非法的 {@code requestKey} 会先消耗掉一份准入额度再返回 400。因此规则被
 * 抽到这里：准入层与 DB 核心调用同一个方法，错误码与文案也只有一个版本。</p>
 *
 * <p>错误码固定：字段非法一律 400（{@code BusinessException}），不做静默默认值。</p>
 */
public final class VideoInteractionValidation {

    /** 幂等键长度上限（与 {@code video_interaction_request.request_key VARCHAR(64)} 一致）。 */
    public static final int MAX_REQUEST_KEY_LENGTH = 64;

    /** 幂等键：1..64 个安全字符，<b>大小写敏感</b>（DB 列排序规则 utf8mb4_bin）。 */
    private static final Pattern REQUEST_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** actor 标识哈希：规范化小写 64 位十六进制（大写不接受，避免同一 actor 出现两种写法）。 */
    private static final Pattern ACTOR_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private VideoInteractionValidation() {
    }

    public static void requireValidRequestKey(String requestKey) {
        if (requestKey == null || !REQUEST_KEY_PATTERN.matcher(requestKey).matches()) {
            throw new BusinessException(400,
                    "requestKey 必须是 1..64 位字母/数字/点/下划线/冒号/连字符");
        }
    }

    public static void requireValidActorHash(String actorHash) {
        if (actorHash == null || !ACTOR_HASH_PATTERN.matcher(actorHash).matches()) {
            throw new BusinessException(400,
                    "actorHash 必须是规范化小写 64 位十六进制串");
        }
    }

    public static void requireValidVid(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new BusinessException(400, "视频参数不合法");
        }
    }

    public static void requireValidAction(VideoInteractionAction action) {
        if (action == null) {
            throw new BusinessException(400, "action 不合法，只接受 PLAY / SHARE");
        }
    }

    public static void requireValidActorType(VideoInteractionActorType actorType) {
        if (actorType == null) {
            throw new BusinessException(400, "actorType 不合法，只接受 USER / ANONYMOUS");
        }
    }
}
