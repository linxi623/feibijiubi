package com.feibijiubi.backend.service.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.feibijiubi.backend.common.BusinessException;
import com.feibijiubi.backend.config.VideoStatusProperties;
import com.feibijiubi.backend.entity.VideoStatusConsumptionRepairLog;
import com.feibijiubi.backend.entity.VideoStatusOutbox;
import com.feibijiubi.backend.event.VideoStatusChangedEvent;
import com.feibijiubi.backend.event.VideoStatusEventType;
import com.feibijiubi.backend.mapper.VideoStatusOutboxRepairMapper;
import com.feibijiubi.backend.service.admin.VideoStatusOutboxReplayService;
import com.feibijiubi.backend.vo.VideoStatusOutboxReplayVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T07 重放工具的<b>纯单元测试</b>：不启动 Spring 上下文、不连 MySQL/Redis/MQ，
 * 用 JDK 动态代理冒充 {@link VideoStatusOutboxRepairMapper}。
 *
 * <p>只覆盖本轮新增/收紧的边界（安全窗口负年龄、审计唯一键冲突、payload 校验不回传底层异常文本、
 * 审计元数据完整性校验），并断言“拒绝请求不产生审计/不写状态”。</p>
 */
class VideoStatusOutboxReplayBoundaryTests {

    private static final String METADATA_SCHEMA = "T07_REPLAY_META_V2";
    private static final String LEGACY_METADATA_SCHEMA = "T07_REPLAY_META_V1";
    private static final String OPERATOR = "42";
    private static final int VID = 777;
    private static final long MAX_AGE_SECONDS = 600L;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private FakeRepairMapper fake;
    private VideoStatusOutboxReplayService service;

    @BeforeEach
    void setUp() {
        fake = new FakeRepairMapper();
        VideoStatusProperties properties = new VideoStatusProperties();
        properties.setConsumerRecoveryAutoReplayMaxAgeSeconds((int) MAX_AGE_SECONDS);
        service = new VideoStatusOutboxReplayService(fake.proxy(), properties, objectMapper);
    }

    // ------------------------------------------------- 1) created_at 在未来

    @Test
    @DisplayName("created_at 年龄为负（DB 时钟在未来）→ 409，且不写状态、不写审计")
    void negativeCreatedAgeIsRejectedWithoutAnyWrite() {
        String eventId = "t07b-" + UUID.randomUUID();
        fake.row = failedRow(eventId, validPayload(eventId));
        fake.createdAgeSeconds = -1L;

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.replay(eventId, "op-" + UUID.randomUUID(), "负年龄重放", OPERATOR));

        assertEquals(409, rejected.getCode());
        assertTrue(rejected.getMessage().contains("created_at"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("未来"), rejected.getMessage());
        assertNoWrite();
    }

    @Test
    @DisplayName("created_at 年龄为 0 且 occurredAt 新鲜 → 正常放行（负年龄拒绝不是误杀 0 秒）")
    void zeroCreatedAgeIsStillAccepted() {
        String eventId = "t07b-" + UUID.randomUUID();
        fake.row = failedRow(eventId, validPayload(eventId));
        fake.createdAgeSeconds = 0L;

        VideoStatusOutboxReplayVO vo = service.replay(
                eventId, "op-" + UUID.randomUUID(), "零年龄重放", OPERATOR);

        assertTrue(vo.isReplayApplied());
        assertEquals(List.of("selectByEventIdForUpdate", "selectRepairLogByOperationId",
                "selectCreatedAgeSeconds", "selectConsumedProcessStatus",
                "resetFailedToPending", "insertCompletedRepairLog"), fake.calls);
        assertEquals(3, vo.getConfirmWritebackCountBefore(),
                "confirm 回写预算的 before 值必须进入响应");
        assertEquals(0, vo.getConfirmWritebackCountAfter());
        assertTrue(fake.insertedLog.getLastError().contains(METADATA_SCHEMA));
        assertTrue(fake.insertedLog.getLastError().contains("confirmWritebackBefore"));
        assertTrue(fake.insertedLog.getLastError().contains("confirmWritebackAfter"));
    }

    // ------------------------------------------------- 2) 审计唯一键冲突

    @Test
    @DisplayName("审计 insert 抛 DuplicateKeyException → 转 409 BusinessException，异常不外泄")
    void duplicateKeyOnAuditInsertIsConvertedTo409() {
        String eventId = "t07b-" + UUID.randomUUID();
        fake.row = failedRow(eventId, validPayload(eventId));
        fake.createdAgeSeconds = 5L;
        fake.duplicateKeyOnInsert = true;

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.replay(eventId, "op-" + UUID.randomUUID(), "唯一键冲突", OPERATOR));

        assertEquals(409, rejected.getCode());
        assertTrue(rejected.getMessage().contains("唯一键"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("回滚"), rejected.getMessage());
        // 状态 CAS 已执行（同一事务内），冲突后必须靠事务整体回滚，而不是继续返回成功 VO。
        assertTrue(fake.calls.contains("resetFailedToPending"));
        assertTrue(fake.calls.contains("insertCompletedRepairLog"));
    }

    @Test
    @DisplayName("replay 声明 rollbackFor=Exception，保证 409 BusinessException 也整体回滚")
    void replayIsDeclaredToRollbackOnAnyException() throws Exception {
        Method replay = VideoStatusOutboxReplayService.class
                .getMethod("replay", String.class, String.class, String.class, String.class);

        Transactional transactional = replay.getAnnotation(Transactional.class);
        assertTrue(transactional != null, "replay 必须带 @Transactional");
        assertTrue(List.of(transactional.rollbackFor()).contains(Exception.class),
                "@Transactional 必须声明 rollbackFor = Exception.class");
    }

    // ------------------------------------------------- 3) payload 校验文本

    @Test
    @DisplayName("event.validate() 失败 → 422，且错误信息不回传底层异常细节")
    void invalidEventMessageDoesNotLeakUnderlyingDetail() throws Exception {
        String eventId = "t07b-" + UUID.randomUUID();
        // vid <= 0 让 validate() 抛出 “vid 不合法”，用该文本作为“泄露标记”。
        VideoStatusChangedEvent invalid = new VideoStatusChangedEvent(
                eventId, -1, VideoStatusEventType.PLAY, 1L,
                VideoStatusEventType.PLAY.calculateHotScore(1L),
                LocalDateTime.now().minusSeconds(1), 2, "t07b-unit");
        fake.row = failedRow(eventId, objectMapper.writeValueAsString(invalid));
        fake.createdAgeSeconds = 5L;

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.replay(eventId, "op-" + UUID.randomUUID(), "非法事件", OPERATOR));

        assertEquals(422, rejected.getCode());
        assertTrue(rejected.getMessage().contains("EVENT_INVALID"), rejected.getMessage());
        assertFalse(rejected.getMessage().contains("vid 不合法"), rejected.getMessage());
        assertNoWrite();
    }

    // ------------------------------------------------- 4) 审计元数据完整性

    @Test
    @DisplayName("审计元数据字段为 null / 状态非法 / 计数为负 → 当作无元数据，不拼凑 before/after")
    void incompleteOrIllegalAuditMetadataIsNotReplayed() throws Exception {
        List<String> badMetadata = new ArrayList<>();
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        LEGACY_METADATA_SCHEMA, 3, 2, 1, null, 0, 0)));
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        LEGACY_METADATA_SCHEMA, 3, 2, 1, 0, 0, null)));
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        LEGACY_METADATA_SCHEMA, 9, 2, 1, 0, 0, 0)));
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        LEGACY_METADATA_SCHEMA, 3, 2, 1, -1, 0, 0)));
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        LEGACY_METADATA_SCHEMA, 3, -2, 1, 0, 0, 0)));
        // V2 要求 confirmWriteback 两项非 null：缺字段不得当作合法历史回放。
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        METADATA_SCHEMA, 3, 2, 1, 0, 0, 0)));
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        METADATA_SCHEMA, 3, 2, 1, -1, 0, 0, 0, 0)));
        badMetadata.add(objectMapper.writeValueAsString(
                new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        "T07_REPLAY_META_UNKNOWN", 3, 2, 1, 0, 0, 0, 0, 0)));

        for (String lastError : badMetadata) {
            String eventId = "t07b-" + UUID.randomUUID();
            String operationId = "op-" + UUID.randomUUID();
            fake.row = failedRow(eventId, validPayload(eventId));
            fake.existingLog = existingLog(operationId, eventId, "历史原因", lastError);

            VideoStatusOutboxReplayVO vo =
                    service.replay(eventId, operationId, "历史原因", OPERATOR);

            assertTrue(vo.isDuplicate(), lastError);
            assertFalse(vo.isReplayApplied(), lastError);
            assertNull(vo.getRetryCountBefore(), "非法元数据不得回放 before：" + lastError);
            assertNull(vo.getRetryCountAfter(), "非法元数据不得回放 after：" + lastError);
            assertNull(vo.getLeaseRecoveryCountBefore(), lastError);
            assertNull(vo.getLeaseRecoveryCountAfter(), lastError);
            assertNull(vo.getStatusNameBefore(), lastError);
            assertNull(vo.getStatusNameAfter(), lastError);
            assertTrue(vo.getMessage().contains("没有操作元数据"), vo.getMessage());
            assertNoWrite();
        }
    }

    @Test
    @DisplayName("旧 V1 元数据完整且合法 → duplicate 仍能重放 retry/lease，confirmWriteback 保持 null")
    void completeLegacyAuditMetadataIsStillReplayed() throws Exception {
        String eventId = "t07b-" + UUID.randomUUID();
        String operationId = "op-" + UUID.randomUUID();
        fake.row = failedRow(eventId, validPayload(eventId));
        fake.existingLog = existingLog(operationId, eventId, "历史原因",
                objectMapper.writeValueAsString(new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        LEGACY_METADATA_SCHEMA, 3, 2, 1, 0, 0, 0)));

        VideoStatusOutboxReplayVO vo = service.replay(eventId, operationId, "历史原因", OPERATOR);

        assertTrue(vo.isDuplicate());
        assertEquals(2, vo.getRetryCountBefore());
        assertEquals(0, vo.getRetryCountAfter());
        assertEquals(1, vo.getLeaseRecoveryCountBefore());
        assertEquals(0, vo.getLeaseRecoveryCountAfter());
        assertNull(vo.getConfirmWritebackCountBefore(),
                "V1 时 confirm 预算还不存在，绝不能补造一个看似历史的值");
        assertNull(vo.getConfirmWritebackCountAfter());
        assertEquals("FAILED", vo.getStatusNameBefore());
        assertEquals("PENDING", vo.getStatusNameAfter());
        assertNoWrite();
    }

    @Test
    @DisplayName("V2 元数据完整且合法 → duplicate 重放包含 confirmWriteback before/after")
    void completeV2AuditMetadataIsStillReplayed() throws Exception {
        String eventId = "t07b-" + UUID.randomUUID();
        String operationId = "op-" + UUID.randomUUID();
        fake.row = failedRow(eventId, validPayload(eventId));
        fake.existingLog = existingLog(operationId, eventId, "历史原因",
                objectMapper.writeValueAsString(new VideoStatusOutboxReplayService.ReplayAuditMetadata(
                        METADATA_SCHEMA, 3, 2, 1, 4, 0, 0, 0, 0)));

        VideoStatusOutboxReplayVO vo = service.replay(eventId, operationId, "历史原因", OPERATOR);

        assertTrue(vo.isDuplicate());
        assertEquals(4, vo.getConfirmWritebackCountBefore());
        assertEquals(0, vo.getConfirmWritebackCountAfter());
        assertNoWrite();
    }

    // ---------------------------------------------------------------- helpers

    private void assertNoWrite() {
        assertFalse(fake.calls.contains("resetFailedToPending"), "拒绝路径不得重置状态：" + fake.calls);
        assertFalse(fake.calls.contains("insertCompletedRepairLog"), "拒绝路径不得写审计：" + fake.calls);
    }

    private VideoStatusOutbox failedRow(String eventId, String payload) {
        VideoStatusOutbox row = new VideoStatusOutbox();
        row.setId(1L);
        row.setEventId(eventId);
        row.setAggregateId(VID);
        row.setEventType(VideoStatusEventType.PLAY.name());
        row.setPayload(payload);
        row.setStatus(3);
        row.setRetryCount(2);
        row.setLeaseRecoveryCount(1);
        row.setConfirmWritebackCount(3);
        row.setLastError("PUBLISH_CONFIRM_TIMEOUT");
        row.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return row;
    }

    private VideoStatusConsumptionRepairLog existingLog(String operationId,
                                                        String eventId,
                                                        String reason,
                                                        String lastError) {
        VideoStatusConsumptionRepairLog log = new VideoStatusConsumptionRepairLog();
        log.setOperationId(operationId);
        log.setEventId(eventId);
        log.setAction(VideoStatusOutboxReplayService.ACTION_REPLAY_TO_PENDING);
        log.setOperationStatus(1);
        log.setReason(reason);
        log.setOperator(OPERATOR);
        log.setLastError(lastError);
        log.setCompletedAt(LocalDateTime.now().minusMinutes(30));
        return log;
    }

    private String validPayload(String eventId) {
        long delta = 1L;
        VideoStatusChangedEvent event = new VideoStatusChangedEvent(
                eventId, VID, VideoStatusEventType.PLAY, delta,
                VideoStatusEventType.PLAY.calculateHotScore(delta),
                LocalDateTime.now().minusSeconds(1), 2, "t07b-unit");
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 纯内存假 mapper：记录调用顺序，按脚本返回；不接触任何外部资源。 */
    private final class FakeRepairMapper {

        final List<String> calls = new ArrayList<>();
        VideoStatusOutbox row;
        VideoStatusConsumptionRepairLog existingLog;
        Long createdAgeSeconds = 5L;
        int resetResult = 1;
        int insertResult = 1;
        boolean duplicateKeyOnInsert;
        VideoStatusConsumptionRepairLog insertedLog;

        VideoStatusOutboxRepairMapper proxy() {
            return (VideoStatusOutboxRepairMapper) Proxy.newProxyInstance(
                    VideoStatusOutboxRepairMapper.class.getClassLoader(),
                    new Class<?>[]{VideoStatusOutboxRepairMapper.class},
                    (p, method, args) -> {
                        calls.add(method.getName());
                        switch (method.getName()) {
                            case "selectByEventIdForUpdate":
                                return row;
                            case "selectSafeByEventId":
                                return row;
                            case "selectLastErrorPresent":
                                return Boolean.TRUE;
                            case "selectRepairLogByOperationId":
                                return existingLog;
                            case "selectConsumedProcessStatus":
                                return null;
                            case "selectCreatedAgeSeconds":
                                return createdAgeSeconds;
                            case "resetFailedToPending":
                                return resetResult;
                            case "insertCompletedRepairLog":
                                if (duplicateKeyOnInsert) {
                                    throw new DuplicateKeyException("simulated duplicate operation_id");
                                }
                                insertedLog = (VideoStatusConsumptionRepairLog) args[0];
                                return insertResult;
                            case "toString":
                                return "FakeRepairMapper";
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                throw new InvocationTargetException(
                                        new UnsupportedOperationException(method.getName()));
                        }
                    });
        }
    }
}
