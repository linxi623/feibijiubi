package com.feibijiubi.backend.interaction.service.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.interaction.service.VideoInteractionActor;
import com.feibijiubi.backend.interaction.service.VideoInteractionActorType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * T15 第二阶段：把“当前登录用户 / 匿名访客标识”解析成数据库与 Redis 只看到的伪名
 * （{@link VideoInteractionActor}）。
 *
 * <h2>规则</h2>
 * <ol>
 *   <li><b>登录优先</b>：{@code currentUserId != null}（来自 {@code LoginInterceptor} 写入的
 *       {@code currentUserId} 请求属性，即 JWT 解析结果）时身份固定为 {@code USER}，
 *       {@code X-Visitor-Id} 请求头<b>整个忽略</b>——包括它是非法值时。否则客户端只要带一个
 *       非法头就能把已登录用户降级成匿名，或者反过来用一个 visitor 头伪造第二个身份。</li>
 *   <li><b>匿名必填</b>：没有登录态时必须提供 {@code X-Visitor-Id}，且必须是 canonical UUID
 *       （{@code 8-4-4-4-12} 十六进制，大小写归一为小写后再哈希）。客户端负责随机生成
 *       （如 {@code crypto.randomUUID()}），服务端<b>不</b>生成、<b>不</b>下发 Cookie。</li>
 *   <li><b>域分离</b>：哈希输入带固定前缀（{@code video-interaction:actor:v1:user:} /
 *       {@code ...:anonymous:}），因此“uid=12 的登录用户”和“UUID 恰好拼成 12 的匿名访客”
 *       不可能得到同一个哈希。前缀是协议常量，不是密钥。</li>
 * </ol>
 *
 * <h2>诚实边界（文档必须与这里一致）</h2>
 * <ul>
 *   <li>这是<b>伪名化</b>，不是“不可逆匿名化”：用户 id 空间很小，看到 64 hex 就能枚举
 *       全部 uid 反查（哈希前缀公开，且没有密钥）。</li>
 *   <li>匿名访客可以随时换一个 UUID 获得新身份，因此“同一访客 30 分钟一次”只是
 *       合作客户端的约束，不是不可绕过的防刷墙；绕过成本很低这一点必须在文档里写明。</li>
 *   <li>不采集 IP、User-Agent、Canvas/设备指纹：它们是跨站稳定标识，与“最小化存储”冲突。
 *       代价是“同一自然人换 UUID”不可识别。</li>
 * </ul>
 */
@Component
public class VideoInteractionActorResolver {

    /** 登录用户哈希域前缀（版本化：换算法必须换版本号，否则新旧哈希会混在同一列里）。 */
    static final String USER_DOMAIN = "video-interaction:actor:v1:user:";
    /** 匿名访客哈希域前缀。 */
    static final String ANONYMOUS_DOMAIN = "video-interaction:actor:v1:anonymous:";

    /** canonical UUID：{@code 8-4-4-4-12} 十六进制；大小写都接受，归一化为小写后再哈希。 */
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * 解析本次请求的身份。
     *
     * @param currentUserId 登录态 uid（{@code LoginInterceptor} 写入）；{@code null} 表示匿名
     * @param visitorId     {@code X-Visitor-Id} 请求头原始值；登录态下完全不使用
     * @throws BusinessException 400 匿名但缺少/非法 {@code X-Visitor-Id}
     */
    public VideoInteractionActor resolve(Integer currentUserId, String visitorId) {
        if (currentUserId != null) {
            return new VideoInteractionActor(VideoInteractionActorType.USER,
                    sha256Hex(USER_DOMAIN + currentUserId));
        }
        if (visitorId == null || visitorId.isBlank()) {
            throw new BusinessException(400,
                    "匿名请求必须提供 X-Visitor-Id 请求头（客户端随机生成的 canonical UUID）");
        }
        if (!CANONICAL_UUID.matcher(visitorId).matches()) {
            throw new BusinessException(400,
                    "X-Visitor-Id 必须是 canonical UUID（8-4-4-4-12 十六进制）");
        }
        return new VideoInteractionActor(VideoInteractionActorType.ANONYMOUS,
                sha256Hex(ANONYMOUS_DOMAIN + visitorId.toLowerCase()));
    }

    /** 十六进制小写 64 位；{@code SHA-256} 是 JDK 必带算法，缺失只可能是运行环境被裁剪。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JRE 缺少 SHA-256，无法生成交互 actor 哈希", e);
        }
    }
}
