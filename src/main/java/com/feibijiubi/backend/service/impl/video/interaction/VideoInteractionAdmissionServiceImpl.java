package com.feibijiubi.backend.service.impl.video.interaction;

import com.feibijiubi.backend.service.video.PublishedVideoGuard;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionAction;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionActor;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionAdmissionService;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionCommand;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionRateLimitService;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionResult;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionService;
import com.feibijiubi.backend.service.video.interaction.VideoInteractionValidation;
import com.feibijiubi.backend.vo.VideoInteractionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * T15 第二阶段：{@code POST /api/videos/{vid}/play-count} 与 {@code /share} 的唯一编排实现。
 *
 * <p>顺序与理由写在接口 {@link VideoInteractionAdmissionService} 的 javadoc 里；本类只补充两条
 * 实现层面的约束：</p>
 * <ul>
 *   <li><b>不加 {@code @Transactional}</b>：{@code VideoInteractionService#record} 要求无外层事务；</li> *   <li><b>重放查询命中时不碰 Redis</b>：既不计额度，也不会因为 Redis 故障而让一个已知结果的
 *       请求变成 503。反过来，重放查询未命中才进入限流，因此“重复请求”不会消耗额外额度。</li>
 * </ul>
 *
 * <p>竞态说明：查重放与 {@code record} 之间有窗口期，两个并发同 {@code requestKey} 的请求可能都
 * 走到限流（各消耗一份额度），但 {@code record} 内部会再查一次并由唯一键保证只计一次。额度多消耗的
 * 影响只到“提前触发 429”，不会多计业务次数。</p>
 */
@Service
@RequiredArgsConstructor
public class VideoInteractionAdmissionServiceImpl implements VideoInteractionAdmissionService {

    private final VideoInteractionActorResolver actorResolver;
    private final PublishedVideoGuard publishedVideoGuard;
    private final VideoInteractionRateLimitService rateLimitService;
    private final VideoInteractionService interactionService;

    @Override
    public VideoInteractionVO record(VideoInteractionAction action,
                                     Integer currentUserId,
                                     Integer vid,
                                     String requestKey,
                                     String visitorId) {
        // 1) 参数校验：全部在 Redis 计数之前。
        VideoInteractionValidation.requireValidAction(action);
        VideoInteractionValidation.requireValidVid(vid);
        VideoInteractionValidation.requireValidRequestKey(requestKey);
        VideoInteractionActor actor = actorResolver.resolve(currentUserId, visitorId);

        // 2) 视频公开有效性：保持原有 validateVideo 的 400/404/500 约束。
        publishedVideoGuard.requirePublishedPlayable(vid);

        VideoInteractionCommand command = new VideoInteractionCommand(
                actor.type(), actor.hash(), vid, action, requestKey);

        // 3) 已提交请求重放：命中就返回，Redis 完全不参与。
        Optional<VideoInteractionResult> replay = interactionService.lookupReplay(command);
        if (replay.isPresent()) {
            return toVO(replay.get());
        }

        // 4) 仅新请求限流（fail-closed）。
        rateLimitService.check(actor.hash(), action);

        // 5) 同一 MySQL 事务：占位 → 窗口判定 → 计数 → 终态。
        return toVO(interactionService.record(command));
    }

    private VideoInteractionVO toVO(VideoInteractionResult result) {
        return result.counted()
                ? VideoInteractionVO.counted(result.duplicate())
                : VideoInteractionVO.windowSuppressed(result.duplicate());
    }
}
