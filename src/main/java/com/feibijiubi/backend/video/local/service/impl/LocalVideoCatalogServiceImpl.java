package com.feibijiubi.backend.video.local.service.impl;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.local.entity.LocalVideoEpisode;
import com.feibijiubi.backend.video.local.entity.LocalVideoSeries;
import com.feibijiubi.backend.video.local.mapper.LocalVideoCatalogMapper;
import com.feibijiubi.backend.video.local.service.LocalVideoCatalogService;
import com.feibijiubi.backend.video.local.vo.LocalVideoEpisodeVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoPageVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoSeriesVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@link LocalVideoCatalogService} 的实现：管理员同步创建 + keyset 分页读，没有后台任务、没有操作表。
 *
 * <h2>事务边界</h2>
 * <p>两个创建方法标注 {@code @Transactional(rollbackFor = Exception.class)}：插入、生成主键回读与
 * “写入行数不是 1 / 未取得 id / 回读为空”的 500 判定在同一个事务里，判定失败就整体回滚，不留半条草稿。
 * 两个查询方法不加事务：单条 SELECT 各自提交即可，读到的就是数据库当前状态（不做快照承诺）。</p>
 *
 * <p>必须说准：注解只证明“声明了回滚规则”，编译期与离线测试都<b>不能</b>证明真实数据库 rollback 发生过；
 * 真实回滚需要连库演练（本片不连库）。</p>
 *
 * <h2>没有客户端可控的写入字段</h2>
 * <p>身份用服务端 {@link UUID#randomUUID()}（JDK 保证规范小写 8-4-4-4-12，正好 36 字符），创建者取登录态，
 * 剧集的 {@code duration_seconds} / {@code vid} 连 INSERT 列清单都没有，所以本类不存在给它们赋值的入口。
 * 请求里多带的 {@code vid} / {@code durationSeconds} / {@code createdBy} / {@code localIdentity}
 * 既没有对应 DTO 字段，也不会被这里读取。</p>
 */
@Service
public class LocalVideoCatalogServiceImpl implements LocalVideoCatalogService {

    private final LocalVideoCatalogMapper catalogMapper;

    public LocalVideoCatalogServiceImpl(LocalVideoCatalogMapper catalogMapper) {
        this.catalogMapper = catalogMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LocalVideoSeriesVO createSeries(Integer operatorId, String title) {
        int createdBy = requireOperator(operatorId);
        String normalizedTitle = requireTitle(title);

        LocalVideoSeries series = new LocalVideoSeries();
        series.setLocalIdentity(newLocalIdentity());
        series.setTitle(normalizedTitle);
        series.setCreatedBy(createdBy);

        int inserted = catalogMapper.insertSeries(series);
        if (inserted != 1) {
            throw new BusinessException(500, "创建本地作品失败：写入行数不是 1");
        }
        Long id = series.getId();
        if (id == null || id <= 0L) {
            throw new BusinessException(500, "创建本地作品失败：未取得生成的 id");
        }
        LocalVideoSeries stored = catalogMapper.selectSeriesById(id);
        if (stored == null) {
            throw new BusinessException(500, "创建本地作品失败：回读为空");
        }
        return toSeriesVO(stored);
    }

    @Override
    public LocalVideoPageVO<LocalVideoSeriesVO> listSeries(long afterId, int limit) {
        requirePaging(afterId, limit);
        List<LocalVideoSeries> rows = catalogMapper.selectSeriesAfter(afterId, limit + 1);
        return page(rows, afterId, limit, LocalVideoSeries::getId, this::toSeriesVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LocalVideoEpisodeVO createEpisode(Integer operatorId, long seriesId, String displayTitle) {
        int createdBy = requireOperator(operatorId);
        long parentId = requirePositiveId(seriesId, "seriesId");
        String normalizedDisplayTitle = normalizeDisplayTitle(displayTitle);

        // 父存在性检查与插入在同一个事务里；本片没有删除接口，所以不需要为“假未来并发删除”加锁。
        if (catalogMapper.selectSeriesById(parentId) == null) {
            throw new BusinessException(404, "本地作品不存在");
        }

        LocalVideoEpisode episode = new LocalVideoEpisode();
        episode.setSeriesId(parentId);
        episode.setLocalIdentity(newLocalIdentity());
        episode.setDisplayTitle(normalizedDisplayTitle);
        episode.setCreatedBy(createdBy);
        // durationSeconds / vid 刻意不赋值：INSERT 列清单里也没有它们，由数据库 NULL 默认值决定。

        int inserted = catalogMapper.insertEpisode(episode);
        if (inserted != 1) {
            throw new BusinessException(500, "创建本地剧集失败：写入行数不是 1");
        }
        Long id = episode.getId();
        if (id == null || id <= 0L) {
            throw new BusinessException(500, "创建本地剧集失败：未取得生成的 id");
        }
        LocalVideoEpisode stored = catalogMapper.selectEpisodeById(id);
        if (stored == null) {
            throw new BusinessException(500, "创建本地剧集失败：回读为空");
        }
        return toEpisodeVO(stored);
    }

    @Override
    public LocalVideoPageVO<LocalVideoEpisodeVO> listEpisodes(long seriesId, long afterId, int limit) {
        long parentId = requirePositiveId(seriesId, "seriesId");
        requirePaging(afterId, limit);
        if (catalogMapper.selectSeriesById(parentId) == null) {
            throw new BusinessException(404, "本地作品不存在");
        }
        List<LocalVideoEpisode> rows = catalogMapper.selectEpisodesAfter(parentId, afterId, limit + 1);
        return page(rows, afterId, limit, LocalVideoEpisode::getId, this::toEpisodeVO);
    }

    // ---------------------------------------------------------------- 校验

    private static int requireOperator(Integer operatorId) {
        if (operatorId == null) {
            throw new BusinessException(401, "未登录：创建者必须来自登录态，不能由请求指定");
        }
        return operatorId;
    }

    private static String requireTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(400, "title 不能为空");
        }
        if (normalized.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(400, "title 长度不能超过 " + MAX_TITLE_LENGTH);
        }
        return normalized;
    }

    /** {@code null} 保持 {@code null}；非 {@code null} 一律 trim 后判非空与长度。 */
    private static String normalizeDisplayTitle(String displayTitle) {
        if (displayTitle == null) {
            return null;
        }
        String normalized = displayTitle.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(400, "displayTitle 不能为空字符串（不设标题请直接省略该字段）");
        }
        if (normalized.length() > MAX_DISPLAY_TITLE_LENGTH) {
            throw new BusinessException(400,
                    "displayTitle 长度不能超过 " + MAX_DISPLAY_TITLE_LENGTH);
        }
        return normalized;
    }

    private static long requirePositiveId(long id, String name) {
        if (id <= 0L) {
            throw new BusinessException(400, name + " 必须是正整数");
        }
        return id;
    }

    private static void requirePaging(long afterId, int limit) {
        if (afterId < 0L) {
            throw new BusinessException(400, "afterId 必须 >= 0");
        }
        if (limit < MIN_PAGE_LIMIT || limit > MAX_PAGE_LIMIT) {
            throw new BusinessException(400,
                    "limit 必须在 " + MIN_PAGE_LIMIT + " 到 " + MAX_PAGE_LIMIT + " 之间");
        }
    }

    // ---------------------------------------------------------------- 映射

    /**
     * 服务端生成的身份：{@link UUID#randomUUID()} 的规范文本（小写 8-4-4-4-12，36 字符）。
     *
     * <p>与标题/顺序/上游 ID 无关；插入后不接受修改。唯一键冲突只会来自随机源，不属于业务冲突。</p>
     */
    private static String newLocalIdentity() {
        return UUID.randomUUID().toString();
    }

    /**
     * “请求 limit + 1 行”的通用分页：多取的那一行只用于 {@code hasMore}，不进 {@code items}；
     * 没有下一页时 {@code nextAfterId} 明确为 {@code null}（不伪造末页游标）。
     */
    private static <T, V> LocalVideoPageVO<V> page(List<T> rows, long afterId, int limit,
                                                   Function<T, Long> idOf,
                                                   Function<T, V> toVO) {
        boolean hasMore = rows.size() > limit;
        int size = Math.min(rows.size(), limit);
        List<V> items = rows.subList(0, size).stream().map(toVO).toList();
        Long nextAfterId = hasMore && size > 0 ? idOf.apply(rows.get(size - 1)) : null;
        return new LocalVideoPageVO<>(items, afterId, limit, hasMore, nextAfterId);
    }

    private LocalVideoSeriesVO toSeriesVO(LocalVideoSeries series) {
        return new LocalVideoSeriesVO(
                series.getId(),
                series.getLocalIdentity(),
                series.getTitle(),
                series.getCreatedBy(),
                series.getCreatedAt());
    }

    private LocalVideoEpisodeVO toEpisodeVO(LocalVideoEpisode episode) {
        return new LocalVideoEpisodeVO(
                episode.getId(),
                episode.getLocalIdentity(),
                episode.getSeriesId(),
                episode.getDisplayTitle(),
                episode.getVid(),
                episode.getDurationSeconds(),
                episode.getCreatedBy(),
                episode.getCreatedAt());
    }
}
