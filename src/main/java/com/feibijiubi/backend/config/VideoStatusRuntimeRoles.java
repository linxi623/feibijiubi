package com.feibijiubi.backend.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 视频统计链路的运行角色契约：把 {@code app.video-status.*} 的开关翻译成
 * “本实例实际承担哪些角色”，并在启动期给出诊断。
 *
 * <p>角色矩阵（四个角色互相独立，可以分别部署到不同实例）：</p>
 * <table border="1">
 *   <caption>角色与开关</caption>
 *   <tr><th>角色</th><th>开关</th><th>关闭后的效果</th></tr>
 *   <tr>
 *     <td>业务发布</td><td>{@code async-enabled}</td>
 *     <td>业务事务直接同步更新统计，不写 Outbox</td>
 *   </tr>
 *   <tr>
 *     <td>Outbox 投递</td><td>{@code outbox-relay-enabled}</td>
 *     <td>不领取、不恢复租约、不发布；事件留在 Outbox 表</td>
 *   </tr>
 *   <tr>
 *     <td>消费</td>
 *     <td>{@code spring.rabbitmq.listener.simple.auto-startup}</td>
 *     <td>不消费；消息留在队列（不属本配置组）</td>
 *   </tr>
 *   <tr>
 *     <td>落库/恢复</td><td>{@code scheduling-enabled}</td>
 *     <td>不执行批量刷库与 dirty 恢复；Redis 增量不落 DB</td>
 *   </tr>
 * </table>
 *
 * <p>这里只做“诊断 + 提示”，不做拒绝：{@code async=true/scheduling=false} 是多角色部署和
 * 维护窗口的合法组合（计划 §1 第 3 条），是否有人落库取决于其他实例或当前维护流程，
 * 代码不能代替运维判断。参数本身的非法取值由 {@link VideoStatusProperties#validate()} 在启动期拒绝。</p>
 */
@Slf4j
@Component
public class VideoStatusRuntimeRoles {

    private final VideoStatusProperties properties;
    private final boolean listenerAutoStartup;

    public VideoStatusRuntimeRoles(
            VideoStatusProperties properties,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:false}")
            boolean listenerAutoStartup
    ) {
        this.properties = properties;
        this.listenerAutoStartup = listenerAutoStartup;
    }

    /**
     * 本实例实际承担的角色。
     *
     * @param publish       业务侧是否写 Outbox（异步发布）
     * @param relay         Outbox → RabbitMQ 投递
     * @param consume       RabbitMQ 监听器是否随应用启动
     * @param flushRecovery 批量落库 + dirty 恢复调度
     */
    public record RoleMatrix(
            boolean publish,
            boolean relay,
            boolean consume,
            boolean flushRecovery
    ) {
        @Override
        public String toString() {
            return "publish=" + publish
                    + ", relay=" + relay
                    + ", consume=" + consume
                    + ", flushRecovery=" + flushRecovery;
        }
    }

    public RoleMatrix matrix() {
        boolean async = properties.isAsyncEnabled();
        return new RoleMatrix(
                async,
                async && properties.isOutboxRelayEnabled(),
                listenerAutoStartup,
                async && properties.isSchedulingEnabled()
        );
    }

    /**
     * 返回当前组合需要提示的运维信息。空列表表示“四角色齐全，无需额外确认”。
     */
    public List<String> diagnostics() {
        List<String> messages = new ArrayList<>();
        boolean async = properties.isAsyncEnabled();
        boolean relay = properties.isOutboxRelayEnabled();
        boolean scheduling = properties.isSchedulingEnabled();

        if (!async) {
            if (relay) {
                messages.add("async-enabled=false：业务不写 Outbox，outbox-relay-enabled=true 不会实际投递；"
                        + "请确认 Outbox 表没有历史积压后再视为已停投。");
            }
            if (scheduling) {
                messages.add("async-enabled=false：批量落库与 dirty 恢复调度不会执行（既有含义：调度器服务异步链路），"
                        + "统计由业务事务同步写入。");
            }
            if (listenerAutoStartup) {
                messages.add("async-enabled=false 但 RabbitMQ 监听器自动启动：本实例仍会消费残留消息，"
                        + "切换前必须按维护流程排空队列。");
            }
            return messages;
        }

        if (!relay) {
            messages.add("outbox-relay-enabled=false：本实例不领取、不恢复租约、不发布 Outbox。"
                    + "必须有其他实例承担投递角色，或当前处于停投维护窗口；否则事件只会滞留在 Outbox 表。"
                    + "消费与批量落库不受此开关影响。");
        }
        if (!scheduling) {
            messages.add("scheduling-enabled=false：本实例不执行批量落库与 dirty 恢复。"
                    + "必须有其他实例承担落库角色；单机部署会出现“无人落库”："
                    + "Redis 已应用增量、消费状态停在 1，而 video_status 表不再前进。");
        }
        if (!listenerAutoStartup) {
            messages.add("RabbitMQ 监听器未自动启动：若无其他实例消费，消息会在队列积压。");
        }
        return messages;
    }

    @PostConstruct
    public void reportAtStartup() {
        log.info("视频统计运行角色：{}", matrix());
        for (String message : diagnostics()) {
            log.warn("视频统计配置提示：{}", message);
        }
    }
}
