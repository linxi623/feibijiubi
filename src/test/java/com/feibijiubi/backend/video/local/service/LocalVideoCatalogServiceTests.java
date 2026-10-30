package com.feibijiubi.backend.video.local.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.local.dto.LocalVideoEpisodeCreateDTO;
import com.feibijiubi.backend.video.local.dto.LocalVideoSeriesCreateDTO;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.local.entity.LocalVideoSeries;
import com.feibijiubi.backend.video.local.mapper.LocalVideoCatalogMapper;
import com.feibijiubi.backend.video.local.service.impl.LocalVideoCatalogServiceImpl;
import com.feibijiubi.backend.video.local.vo.LocalVideoEpisodeVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoPageVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoSeriesVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * S1：本地作品/剧集服务的纯 Mockito 单测（不启动 Spring、不连数据库、不访问 Redis/MQ）。
 *
 * <p>断言的重点：</p>
 * <ul>
 *   <li>身份、创建者、时长、{@code vid} 都不是请求可控字段；</li>
 *   <li>校验失败（401/400）与父不存在（404）在<b>任何</b> mapper 调用之前拒绝；</li>
 *   <li>insert 返回非 1、未取得生成 id、回读为空都是 500（并处在回滚事务内）；</li>
 *   <li>分页是 “limit + 1” 的 keyset：多取的一行只用于 {@code hasMore}，末页不伪造游标。</li>
 * </ul>
 *
 * <p>必须说准的边界：这里证明的是<b>服务层行为与声明</b>。{@code @Transactional(rollbackFor =
 * Exception.class)} 的注解检查只证明"声明了回滚规则"，<b>不</b>证明真实数据库 rollback 发生过 ——
 * 真实回滚、外键/唯一键行为都需要连库演练，本片（离线切片）没有做，也不在结论里声称做了。</p>
 */
class LocalVideoCatalogServiceTests {

    private static final Integer ADMIN_UID = 7;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000);

    /** 规范小写 8-4-4-4-12 且版本位为 4。 */
    private static final String UUID_V4_PATTERN =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    private LocalVideoCatalogMapper mapper;
    private LocalVideoCatalogServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(LocalVideoCatalogMapper.class);
        service = new LocalVideoCatalogServiceImpl(mapper);
    }

    // ---------------------------------------------------------------- 创建作品

    @Test
    void createSeriesPersistsServerIdentityAndReturnsTheDatabaseReadback() {
        LocalVideoSeries[] saved = new LocalVideoSeries[1];
        when(mapper.insertSeries(any(LocalVideoSeries.class))).thenAnswer(invocation -> {
            LocalVideoSeries input = invocation.getArgument(0);
            input.setId(41L);
            LocalVideoSeries stored = seriesRow(41L, input.getTitle(), input.getCreatedBy(), CREATED_AT);
            stored.setLocalIdentity(input.getLocalIdentity());
            saved[0] = stored;
            return 1;
        });
        when(mapper.selectSeriesById(41L)).thenAnswer(invocation -> saved[0]);

        LocalVideoSeriesVO vo = service.createSeries(ADMIN_UID, "  示例作品  ");

        ArgumentCaptor<LocalVideoSeries> captor = ArgumentCaptor.forClass(LocalVideoSeries.class);
        verify(mapper).insertSeries(captor.capture());
        LocalVideoSeries inserted = captor.getValue();
        assertEquals("示例作品", inserted.getTitle(), "title 必须在写库前 trim");
        assertEquals(ADMIN_UID, inserted.getCreatedBy(), "createdBy 只能来自登录态");
        assertTrue(inserted.getLocalIdentity().matches(UUID_V4_PATTERN),
                "身份必须是服务端生成的 UUIDv4：" + inserted.getLocalIdentity());
        assertNull(inserted.getCreatedAt(),
                "创建时间由数据库默认值填写：实体不是 INSERT 的输入");

        assertEquals(Long.valueOf(41L), vo.id());
        assertEquals(inserted.getLocalIdentity(), vo.localIdentity(),
                "回显的身份必须是这次真正写进去的那个");
        assertEquals("示例作品", vo.title());
        assertEquals(ADMIN_UID, vo.createdBy());
        assertEquals(CREATED_AT, vo.createdAt(), "createdAt 必须来自回读，不能在应用层捏造");
    }

    @Test
    void createSeriesWithoutLoginUidIs401AndNeverTouchesTheMapper() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createSeries(null, "示例作品"));

        assertEquals(401, error.getCode().intValue());
        verifyNoInteractions(mapper);
    }

    @Test
    void createSeriesRejectsBlankOrTooLongTitleWith400AndNoMapperInteraction() {
        for (String invalid : Arrays.asList(null, "", "   ", "\t\n", "长".repeat(201))) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.createSeries(ADMIN_UID, invalid),
                    "应当拒绝的 title：" + invalid);
            assertEquals(400, error.getCode().intValue(), "应当拒绝的 title：" + invalid);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void theSameTitleCreatesTwoIndependentDraftsInsteadOfMerging() {
        when(mapper.insertSeries(any(LocalVideoSeries.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, LocalVideoSeries.class).setId(61L);
                    return 1;
                })
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, LocalVideoSeries.class).setId(62L);
                    return 1;
                });
        when(mapper.selectSeriesById(61L)).thenReturn(seriesRow(61L, "同名", ADMIN_UID, CREATED_AT));
        when(mapper.selectSeriesById(62L)).thenReturn(seriesRow(62L, "同名", ADMIN_UID, CREATED_AT));

        LocalVideoSeriesVO first = service.createSeries(ADMIN_UID, "同名");
        LocalVideoSeriesVO second = service.createSeries(ADMIN_UID, "同名");

        ArgumentCaptor<LocalVideoSeries> captor = ArgumentCaptor.forClass(LocalVideoSeries.class);
        verify(mapper, times(2)).insertSeries(captor.capture());
        assertNotEquals(captor.getAllValues().get(0).getLocalIdentity(),
                captor.getAllValues().get(1).getLocalIdentity(),
                "每次创建都是新的随机身份（创建不是幂等创建）");
        assertNotEquals(first.id(), second.id(),
                "同标题允许存在两个空草稿：本片不做自动合并，也不建 title 唯一键");
    }

    @Test
    void createSeriesTreatsInsertCountGeneratedIdAndReadbackAsRequired() {
        when(mapper.insertSeries(any(LocalVideoSeries.class))).thenReturn(0);
        assertEquals(500, assertThrows(BusinessException.class,
                () -> service.createSeries(ADMIN_UID, "作品甲")).getCode().intValue(),
                "写入行数不是 1 必须失败");

        LocalVideoCatalogMapper noKeyMapper = mock(LocalVideoCatalogMapper.class);
        when(noKeyMapper.insertSeries(any(LocalVideoSeries.class))).thenReturn(1);
        LocalVideoCatalogServiceImpl noKeyService = new LocalVideoCatalogServiceImpl(noKeyMapper);
        assertEquals(500, assertThrows(BusinessException.class,
                () -> noKeyService.createSeries(ADMIN_UID, "作品甲")).getCode().intValue(),
                "未取得生成 id 必须失败（事务回滚，不留半条草稿）");

        givenSeriesInsertSucceeds(41L);
        when(mapper.selectSeriesById(41L)).thenReturn(null);
        assertEquals(500, assertThrows(BusinessException.class,
                () -> service.createSeries(ADMIN_UID, "作品甲")).getCode().intValue(),
                "回读为空必须失败");
    }

    // ---------------------------------------------------------------- 创建剧集

    @Test
    void createEpisodeRequiresAnExistingParentSeries() {
        when(mapper.selectSeriesById(9L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createEpisode(ADMIN_UID, 9L, "第1集"));

        assertEquals(404, error.getCode().intValue());
        verify(mapper, never()).insertEpisode(any());
    }

    @Test
    void createEpisodeTrimsDisplayTitleLeavesFutureFieldsNullAndWritesNullWhenOmitted() {
        when(mapper.selectSeriesById(9L)).thenReturn(seriesRow(9L, "作品甲", ADMIN_UID, CREATED_AT));
        givenEpisodeInsertSucceeds(51L);
        when(mapper.selectEpisodeById(51L)).thenReturn(episodeRow(51L, 9L));

        LocalVideoEpisodeVO vo = service.createEpisode(ADMIN_UID, 9L, "  第1集  ");

        // 第二次不带标题：应写 NULL，而不是空串。
        givenEpisodeInsertSucceeds(52L);
        when(mapper.selectEpisodeById(52L)).thenReturn(episodeRow(52L, 9L));
        service.createEpisode(ADMIN_UID, 9L, null);

        ArgumentCaptor<LocalVideoEpisode> captor = ArgumentCaptor.forClass(LocalVideoEpisode.class);
        verify(mapper, times(2)).insertEpisode(captor.capture());
        LocalVideoEpisode inserted = captor.getAllValues().get(0);
        assertEquals("第1集", inserted.getDisplayTitle(), "displayTitle 必须在写库前 trim");
        assertEquals(Long.valueOf(9L), inserted.getSeriesId());
        assertEquals(ADMIN_UID, inserted.getCreatedBy());
        assertTrue(inserted.getLocalIdentity().matches(UUID_V4_PATTERN));
        assertNull(inserted.getVid(), "本片没有 vid 赋值入口");
        assertNull(inserted.getDurationSeconds(), "本片没有时长赋值入口");

        assertNull(captor.getAllValues().get(1).getDisplayTitle(),
                "displayTitle 可空：不设标题就写 NULL，不能写空串");

        assertEquals(Long.valueOf(51L), vo.id());
        assertNull(vo.vid(), "vid 当前必须为 null，不能伪造成 0 或“已导入”");
        assertNull(vo.durationSeconds(), "时长当前必须为 null");
    }

    @Test
    void createEpisodeRejectsInvalidInputBeforeAnyMapperCall() {
        assertEquals(401, assertThrows(BusinessException.class,
                () -> service.createEpisode(null, 9L, null)).getCode().intValue());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.createEpisode(ADMIN_UID, 0L, null)).getCode().intValue());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.createEpisode(ADMIN_UID, -3L, null)).getCode().intValue());
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.createEpisode(ADMIN_UID, 9L, "   ")).getCode().intValue(),
                "空串/纯空白不是“不设标题”：要设就设非空，不设就省略");
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.createEpisode(ADMIN_UID, 9L, "长".repeat(256))).getCode().intValue());

        verifyNoInteractions(mapper);
    }

    // ---------------------------------------------------------------- 分页

    @Test
    void listSeriesFetchesOneExtraRowForTheKeysetCursor() {
        when(mapper.selectSeriesAfter(10L, 3)).thenReturn(List.of(
                seriesRow(11L, "甲", ADMIN_UID, CREATED_AT),
                seriesRow(12L, "乙", ADMIN_UID, CREATED_AT),
                seriesRow(13L, "丙", ADMIN_UID, CREATED_AT)));

        LocalVideoPageVO<LocalVideoSeriesVO> page = service.listSeries(10L, 2);

        verify(mapper).selectSeriesAfter(10L, 3);
        assertEquals(2, page.items().size(),
                "多取的第 3 行只用于 hasMore，不能出现在 items 里");
        assertTrue(page.hasMore());
        assertEquals(Long.valueOf(12L), page.nextAfterId(), "游标是本页最后一条的 id");
        assertEquals(10L, page.afterId());
        assertEquals(2, page.limit());
    }

    @Test
    void listSeriesLastPageAndEmptyPageReportNoNextCursorWithoutAnyWrite() {
        when(mapper.selectSeriesAfter(5L, 3)).thenReturn(List.of(
                seriesRow(6L, "甲", ADMIN_UID, CREATED_AT)));
        LocalVideoPageVO<LocalVideoSeriesVO> lastPage = service.listSeries(5L, 2);
        assertFalse(lastPage.hasMore());
        assertNull(lastPage.nextAfterId(), "没有下一页时不得伪造末页游标");
        assertEquals(1, lastPage.items().size());

        when(mapper.selectSeriesAfter(0L, 3)).thenReturn(List.of());
        LocalVideoPageVO<LocalVideoSeriesVO> emptyPage = service.listSeries(0L, 2);
        assertTrue(emptyPage.items().isEmpty(), "空页是合法结果");
        assertFalse(emptyPage.hasMore());
        assertNull(emptyPage.nextAfterId());

        verify(mapper).selectSeriesAfter(5L, 3);
        verify(mapper).selectSeriesAfter(0L, 3);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void invalidPagingAndNonPositiveIdsAreRejectedBeforeAnyMapperCall() {
        for (long afterId : List.of(-1L, -100L)) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.listSeries(afterId, 20)).getCode().intValue());
        }
        for (int limit : List.of(0, -1, 101, 1000)) {
            assertEquals(400, assertThrows(BusinessException.class,
                    () -> service.listSeries(0L, limit)).getCode().intValue());
        }
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.listEpisodes(0L, 0L, 20)).getCode().intValue(),
                "非正 seriesId 是参数错误（400），不是“作品不存在”（404）");
        verifyNoInteractions(mapper);
    }

    @Test
    void listEpisodesRequiresAnExistingParentAndScansBySeriesKeyset() {
        when(mapper.selectSeriesById(9L)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.listEpisodes(9L, 0L, 20)).getCode().intValue());
        verify(mapper, never()).selectEpisodesAfter(anyLong(), anyLong(), anyInt());

        when(mapper.selectSeriesById(9L)).thenReturn(seriesRow(9L, "作品甲", ADMIN_UID, CREATED_AT));
        when(mapper.selectEpisodesAfter(9L, 3L, 3)).thenReturn(List.of(
                episodeRow(4L, 9L), episodeRow(5L, 9L), episodeRow(6L, 9L)));

        LocalVideoPageVO<LocalVideoEpisodeVO> page = service.listEpisodes(9L, 3L, 2);

        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertEquals(Long.valueOf(5L), page.nextAfterId());
    }

    // ---------------------------------------------------------------- 结构与声明

    @Test
    void onlyCreateAndReadEntryPointsExistBecauseThisSliceHasNoUpdateDeleteOrImport() {
        Set<String> names = new TreeSet<>();
        for (Method method : LocalVideoCatalogService.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        assertEquals(new TreeSet<>(List.of(
                        "createSeries", "listSeries", "createEpisode", "listEpisodes")),
                names, "本片只有创建与分页读：没有 update / delete / 合并 / 导入入口");

        for (Method method : LocalVideoCatalogMapper.class.getDeclaredMethods()) {
            assertFalse(method.getName().toLowerCase().contains("title"),
                    "mapper 不得按标题查询：本片没有同标题自动合并：" + method.getName());
            assertFalse(method.getName().toLowerCase().contains("update"),
                    "mapper 不得有 update：" + method.getName());
            assertFalse(method.getName().toLowerCase().contains("delete"),
                    "mapper 不得有 delete：" + method.getName());
        }
    }

    @Test
    void requestBodiesOnlyCarryFreeTextAndNeverIdentityOwnerVidOrDuration() throws Exception {
        assertEquals(List.of("title"), fieldNames(LocalVideoSeriesCreateDTO.class),
                "创建作品的请求体只有 title");
        assertEquals(List.of("displayTitle"), fieldNames(LocalVideoEpisodeCreateDTO.class),
                "创建剧集的请求体只有 displayTitle；seriesId 只来自路径");

        // 签名本来就没有 vid / duration / createdBy / localIdentity 参数，也没有可绕过的重载。
        assertEquals(List.of(Integer.class, String.class), List.of(
                LocalVideoCatalogService.class
                        .getMethod("createSeries", Integer.class, String.class)
                        .getParameterTypes()));
        assertEquals(List.of(Integer.class, long.class, String.class), List.of(
                LocalVideoCatalogService.class
                        .getMethod("createEpisode", Integer.class, long.class, String.class)
                        .getParameterTypes()));
    }

    @Test
    void createMethodsDeclareRollbackForExceptionWhileQueriesDeclareNoTransaction() {
        for (String name : List.of("createSeries", "createEpisode")) {
            Transactional declared = transactionalOf(name);
            assertTrue(declared != null,
                    name + " 必须声明 @Transactional，否则“未取得 id / 回读为空”无法回滚草稿");
            assertTrue(Arrays.asList(declared.rollbackFor()).contains(Exception.class),
                    name + " 必须 rollbackFor = Exception.class");
        }
        assertNull(transactionalOf("listSeries"), "单条 SELECT 不需要事务");
        assertNull(transactionalOf("listEpisodes"), "单条 SELECT 不需要事务");
        // 只证明声明：真实 rollback 需要连库演练，本片未做。
    }

    // ---------------------------------------------------------------- 小工具

    private static Transactional transactionalOf(String methodName) {
        for (Method method : LocalVideoCatalogServiceImpl.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && !method.isSynthetic()) {
                return method.getAnnotation(Transactional.class);
            }
        }
        throw new AssertionError("找不到方法：" + methodName);
    }

    private static List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }

    private void givenSeriesInsertSucceeds(long id) {
        when(mapper.insertSeries(any(LocalVideoSeries.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, LocalVideoSeries.class).setId(id);
            return 1;
        });
    }

    private void givenEpisodeInsertSucceeds(long id) {
        when(mapper.insertEpisode(any(LocalVideoEpisode.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, LocalVideoEpisode.class).setId(id);
            return 1;
        });
    }

    private static LocalVideoSeries seriesRow(long id, String title, int createdBy,
                                              LocalDateTime createdAt) {
        LocalVideoSeries row = new LocalVideoSeries();
        row.setId(id);
        row.setLocalIdentity("11111111-1111-4111-8111-111111111111");
        row.setTitle(title);
        row.setCreatedBy(createdBy);
        row.setCreatedAt(createdAt);
        row.setUpdatedAt(createdAt);
        return row;
    }

    private static LocalVideoEpisode episodeRow(long id, long seriesId) {
        LocalVideoEpisode row = new LocalVideoEpisode();
        row.setId(id);
        row.setSeriesId(seriesId);
        row.setLocalIdentity("22222222-2222-4222-8222-222222222222");
        row.setCreatedBy(ADMIN_UID);
        row.setCreatedAt(CREATED_AT);
        row.setUpdatedAt(CREATED_AT);
        return row;
    }
}
