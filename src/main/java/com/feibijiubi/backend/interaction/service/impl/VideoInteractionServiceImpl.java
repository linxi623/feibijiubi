package com.feibijiubi.backend.interaction.service.impl;

import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.interaction.entity.VideoInteractionRequest;
import com.feibijiubi.backend.interaction.entity.VideoInteractionWindow;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.interaction.mapper.VideoInteractionMapper;
import com.feibijiubi.backend.mapper.VideoStatusMapper;
import com.feibijiubi.backend.interaction.service.VideoInteractionAction;
import com.feibijiubi.backend.interaction.service.VideoInteractionCommand;
import com.feibijiubi.backend.interaction.service.VideoInteractionResult;
import com.feibijiubi.backend.interaction.service.VideoInteractionService;
import com.feibijiubi.backend.interaction.service.VideoInteractionValidation;
import com.feibijiubi.backend.service.video.videostatus.VideoStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;

/**
 * T15 第一阶段：播放/分享交互的<b>请求幂等 + 滚动窗口限计</b>的 DB 核心（第二阶段起由
 * {@code VideoInteractionAdmissionService} 接线）。
 *
 * <p>本类只管 MySQL：Redis 准入在调用方（{@code VideoInteractionRateLimitService}），
 * Redis 不参与任何去重。旧的无 actor/无幂等键的 play/share 入口已删除，没有绕过本类的计数路径。</p>
 *
 * <h2>一个请求的完整流程</h2>
 * <ol>
 *   <li><b>幂等读</b>（事务外）：按 {@code request_key} 查请求行。命中则校验形状（actor/vid/action
 *       必须一致），一致就重放历史结果，不一致 409。</li>
 *   <li><b>占位</b>：同一事务插入 {@code state=0 PENDING} 的请求行。唯一键冲突说明同一
 *       {@code request_key} 已被并发请求占用。</li>
 *   <li><b>窗口保证行</b>：ODKU 只保证 {@code (actor_type, actor_hash, vid, action)} 行存在，
 *       绝不初始化/推进 {@code last_counted_at}。</li>
 *   <li><b>行锁</b>：{@code SELECT ... FOR UPDATE} 锁住该窗口行，同一 actor+vid+action 的并发请求
 *       在 DB 层串行。</li>
 *   <li><b>到期判定 + 推进</b>：一条守卫更新，条件是
 *       {@code last_counted_at IS NULL OR last_counted_at <= NOW(3) - 30min}（DB 时钟）。
 *       影响行数 1 = 获准计数；0 = 抑制，<b>且不写任何列</b>（窗口不推进）。</li>
 *   <li><b>计数</b>（仅获准）：异步走既有 {@code createEvent}（同一个事务、同一个
 *       {@code Propagation.MANDATORY}，拿到 eventId 存进请求行）；同步走既有的
 *       {@code increasePlayTimes} / {@code increaseShareTimes} 守卫更新。</li>
 *   <li><b>终态</b>：CAS 把请求行从 PENDING 改成 COUNTED/SUPPRESSED 并写 {@code completed_at}，
 *       与计数同一事务提交。</li>
 * </ol>
 *
 * <h2>为什么第一条请求一定会计数</h2>
 * <p>窗口行插入时 {@code last_counted_at = NULL}，守卫更新的条件是
 * {@code last_counted_at IS NULL OR ...}，所以 NULL 必然获准。如果图省事用 {@code NOW(3)} 初始化，
 * 第一条请求就会被自己的初始值抑制，这是本类最关键的反例，单元与真实 DB 测试都覆盖。</p>
 *
 * <h2>并发同 request_key 的处理</h2>
 * <p>唯一键冲突（{@link DuplicateKeyException}）会从 {@link TransactionTemplate} 回调里<b>抛出</b>，
 * 让本次尝试事务整体回滚；<b>绝不</b>在回调里捕获后继续使用已 rollback-only 的事务。回滚完成后在
 * 事务外重新读取赢家行并重放其结果。代价是这条路径多一次事务，收益是不会出现
 * {@code UnexpectedRollbackException} 或“半提交”。</p>
 *
 * <h2>原子性边界</h2>
 * <p>请求占位、窗口推进、计数、请求终态在<b>同一个</b> MySQL 事务里，任一步失败整体回滚，
 * 不会出现“占位成功但没计数”或“计数成功但请求仍是 PENDING”。这是 MySQL 事务本身提供的，
 * 不是 Mockito 能证明的：真实回滚由 {@code VideoInteractionIsolatedTests} /
 * {@code VideoInteractionSyncIsolatedTests} 在隔离 MySQL 上验证。异步路径的 Outbox 事件同样落在
 * 这个事务里，所以事件与请求行同生共死。</p>
 */
@Slf4j
@Service
public class VideoInteractionServiceImpl implements VideoInteractionService {

    /**
     * 同一 actor + vid + action 的滚动窗口（分钟）。已批准的产品语义，不是可调参数：
     * 做成配置项会让“防刷语义”随环境漂移，且没有对应的产品决策记录。
     */
    static final int WINDOW_MINUTES = 30;

    /** {@code video_interaction_request.state}：占位，未提交（只存在于未提交事务里）。 */
    static final int REQUEST_STATE_PENDING = 0;
    /** {@code video_interaction_request.state}：本次真的计了一次数。 */
    static final int REQUEST_STATE_COUNTED = 1;
    /** {@code video_interaction_request.state}：本次落在窗口内，未计数。 */
    static final int REQUEST_STATE_SUPPRESSED = 2;

    private final VideoInteractionMapper interactionMapper;
    private final VideoStatusMapper videoStatusMapper;
    private final VideoStatusService videoStatusService;
    private final VideoStatusProperties properties;
    private final TransactionTemplate transactionTemplate;

    public VideoInteractionServiceImpl(
            VideoInteractionMapper interactionMapper,
            VideoStatusMapper videoStatusMapper,
            VideoStatusService videoStatusService,
            VideoStatusProperties properties,
            PlatformTransactionManager transactionManager
    ) {
        this.interactionMapper = interactionMapper;
        this.videoStatusMapper = videoStatusMapper;
        this.videoStatusService = videoStatusService;
        this.properties = properties;
        // 传播契约：本服务要求调用方不在事务里，所以这里用默认的 REQUIRED——
        // 没有外层事务时它自己开一个，行为可预测；一旦有人加了外层事务，
        // record() 入口的断言会立刻失败，而不是在唯一键冲突时把外层事务标成 rollback-only。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public VideoInteractionResult record(VideoInteractionCommand command) {
        requireNoEnclosingTransaction();
        validate(command);

        // 1) 幂等读（事务外）：已存在的请求直接重放，不再做窗口判定。
        VideoInteractionRequest existing =
                interactionMapper.selectRequestByKey(command.requestKey());
        if (existing != null) {
            return replay(existing, command);
        }

        // 2) 首次请求：占位 → 窗口判定 → 计数 → 终态，同一事务。
        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> applyFirstRequest(command)),
                    "交互计数事务没有返回结果");
        } catch (DuplicateKeyException e) {
            // 3) 同一 request_key 的并发请求赢了：本次事务已整体回滚，只能在事务外读赢家结果。
            //    这里绝不能复用刚刚回滚的事务，也不重试写入（重试只会再次撞唯一键）。
            VideoInteractionRequest winner =
                    interactionMapper.selectRequestByKey(command.requestKey());
            if (winner == null) {
                // 唯一键冲突也可能来自 Outbox 等后续写入；没有赢家不能认定为请求重放。
                // 不记录底层异常文本，避免泄露 SQL 参数或客户端请求键。
                log.warn("video_interaction_unique_conflict_without_winner vid={} action={}",
                        command.vid(), command.action());
                throw new BusinessException(500, "交互计数失败，请稍后再试");
            }
            // 不打 requestKey：它是客户端可控的业务键，属于“标识类”数据，日志只需要知道
            // “发生了并发同键重放”和它的目标视频即可。
            log.debug("video_interaction_request_duplicate_key vid={} action={}",
                    command.vid(), command.action());
            return replay(winner, command);
        }
    }

    // --------------------------------------------------------------- first request

    /** 在调用方已开启的事务里执行；返回 {@code null} 视为编程错误（由调用方断言）。 */
    private VideoInteractionResult applyFirstRequest(VideoInteractionCommand command) {
        VideoInteractionRequest request = new VideoInteractionRequest();
        request.setRequestKey(command.requestKey());
        request.setActorType(command.actorType().name());
        request.setActorHash(command.actorHash());
        request.setVid(command.vid());
        request.setAction(command.action().name());
        request.setState(REQUEST_STATE_PENDING);
        if (interactionMapper.insertRequest(request) != 1) {
            throw new BusinessException(500, "交互请求占位写入失败，本次交互整体回滚");
        }
        Long requestId = request.getId();
        if (requestId == null) {
            throw new BusinessException(500, "交互请求占位未返回主键，本次交互整体回滚");
        }

        // 只保证窗口行存在：命中已存在行时什么都不改（last_counted_at 保持原值/NULL）。
        interactionMapper.ensureWindowRow(command.actorType().name(), command.actorHash(),
                command.vid(), command.action().name());
        VideoInteractionWindow window = interactionMapper.selectWindowForUpdate(
                command.actorType().name(), command.actorHash(),
                command.vid(), command.action().name());
        if (window == null || window.getId() == null) {
            throw new BusinessException(500, "交互窗口行保证存在后仍读取不到，本次交互整体回滚");
        }

        // 到期判定 + 推进：一条守卫更新，DB 时钟；0 行表示抑制且未写任何列。
        boolean counted = interactionMapper.advanceWindowIfDue(window.getId(), WINDOW_MINUTES) == 1;
        String eventId = counted ? countOnce(command) : null;
        if (!counted) {
            log.debug("video_interaction_suppressed vid={} action={} lastCountedAt={}",
                    command.vid(), command.action(), window.getLastCountedAt());
        }

        int state = counted ? REQUEST_STATE_COUNTED : REQUEST_STATE_SUPPRESSED;
        if (interactionMapper.completeRequest(requestId, state, eventId) != 1) {
            throw new BusinessException(500, "交互请求终态写入失败，本次交互整体回滚");
        }
        return new VideoInteractionResult(command.requestKey(), requestId,
                counted ? STATE_COUNTED : STATE_SUPPRESSED, counted, false, eventId);
    }

    /**
     * 真正计数一次。两条路径都必须“要么计入、要么抛异常让整个事务回滚”：
     * 异步走既有 {@code createEvent}（{@code Propagation.MANDATORY}，加入当前事务，返回 eventId）；
     * 同步走既有守卫更新（{@code video_status} 缺行或已达 Long.MAX 时影响行数为 0 → 回滚）。
     *
     * @return 异步路径的统计事件 eventId；同步路径 {@code null}
     */
    private String countOnce(VideoInteractionCommand command) {
        if (properties.isAsyncEnabled()) {
            VideoStatusChangedEvent event = videoStatusService.createEvent(
                    command.vid(), command.action().eventType(), 1);
            if (event == null || event.eventId() == null) {
                throw new BusinessException(500, "统计事件创建未返回 eventId，本次交互整体回滚");
            }
            return event.eventId();
        }
        int rows = switch (command.action()) {
            case PLAY -> videoStatusMapper.increasePlayTimes(command.vid());
            case SHARE -> videoStatusMapper.increaseShareTimes(command.vid());
        };
        if (rows != 1) {
            // 与 UserVideoServiceImpl 的同步路径保持一致：影响行数不为 1 视为失败。
            // 包括 video_status 缺行、以及已达 Long.MAX_VALUE 无法再 +1（守卫更新 0 行）。
            throw new BusinessException(500,
                    "同步统计更新失败（影响行数=" + rows + "），本次交互整体回滚");
        }
        return null;
    }

    // --------------------------------------------------------------------- replay

    @Override
    public Optional<VideoInteractionResult> lookupReplay(VideoInteractionCommand command) {
        validate(command);
        // 唯一一次读：数据库异常必须原样抛出去（在准入层变成 500），
        // 绝不能 catch 后返回 empty，否则一次数据库故障会被当成“新请求”。
        VideoInteractionRequest existing =
                interactionMapper.selectRequestByKey(command.requestKey());
        if (existing == null) {
            return Optional.empty();
        }
        return Optional.of(replay(existing, command));
    }

    /**
     * 重放已存在的请求行：形状必须完全一致，否则 409（同一 requestKey 不能换目标复用）。
     * 已提交的行不可能是 PENDING；读到 PENDING 说明并发请求尚未提交，返回 409 让调用方重试，
     * 不猜结果。
     */
    private VideoInteractionResult replay(VideoInteractionRequest existing,
                                          VideoInteractionCommand command) {
        requireSameShape(existing, command);
        Integer state = existing.getState();
        if (state == null || state == REQUEST_STATE_PENDING) {
            throw new BusinessException(409,
                    "同一 requestKey 的并发请求尚未完成（state=PENDING），请重试");
        }
        if (state != REQUEST_STATE_COUNTED && state != REQUEST_STATE_SUPPRESSED) {
            throw new BusinessException(500, "交互请求终态不可识别（state=" + state + "）");
        }
        boolean counted = state == REQUEST_STATE_COUNTED;
        return new VideoInteractionResult(existing.getRequestKey(), existing.getId(),
                counted ? STATE_COUNTED : STATE_SUPPRESSED, counted, true,
                existing.getEventId());
    }

    private void requireSameShape(VideoInteractionRequest existing,
                                  VideoInteractionCommand command) {
        boolean same = Objects.equals(existing.getActorType(), command.actorType().name())
                && Objects.equals(existing.getActorHash(), command.actorHash())
                && Objects.equals(existing.getVid(), command.vid())
                && Objects.equals(existing.getAction(), command.action().name());
        if (!same) {
            // 不回显已存储的 actor_hash/vid：requestKey 复用方不应从错误响应里读到别人的标识。
            throw new BusinessException(409,
                    "requestKey 已被不同形状的请求占用（actor/vid/action 不一致），"
                            + "同一 requestKey 不能复用在不同请求上");
        }
    }

    // ----------------------------------------------------------------- validation

    /**
     * 字段规则统一在 {@link VideoInteractionValidation}：准入层必须在 Redis 计数之前做同一套校验，
     * 两处各写一份只会在某个字段上慢慢分叉。
     */
    private void validate(VideoInteractionCommand command) {
        if (command == null) {
            throw new BusinessException(400, "交互请求不能为空");
        }
        VideoInteractionValidation.requireValidActorType(command.actorType());
        VideoInteractionValidation.requireValidActorHash(command.actorHash());
        VideoInteractionValidation.requireValidVid(command.vid());
        VideoInteractionValidation.requireValidAction(command.action());
        VideoInteractionValidation.requireValidRequestKey(command.requestKey());
    }

    private void requireNoEnclosingTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "VideoInteractionService#record 不能在已有事务中调用："
                            + "同一 requestKey 的并发冲突要求本次尝试事务整体回滚后再读取赢家结果，"
                            + "外层事务会让回滚标记传播出去");
        }
    }
}
