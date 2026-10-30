package com.feibijiubi.backend.video.local.service;

import com.feibijiubi.backend.video.local.vo.LocalVideoEpisodeVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoPageVO;
import com.feibijiubi.backend.video.local.vo.LocalVideoSeriesVO;

/**
 * 本地作品/剧集的管理员同步创建与 keyset 分页查询。
 *
 * <h2>本片（S1）明确做什么</h2>
 * <ul>
 *   <li>创建本地作品（{@code title}）与本地剧集（{@code displayTitle} 可空），身份由服务端随机生成；</li>
 *   <li>按 {@code id > afterId ORDER BY id} 分页读作品，按 {@code series_id} + {@code id > afterId}
 *       分页读剧集；查询剧集前<b>必须</b>确认父作品存在，否则 404。</li>
 * </ul>
 *
 * <h2>本片明确不做什么</h2>
 * <ul>
 *   <li>没有 update / delete / 合并 / 导入 / 建视频：{@code duration_seconds} 与 {@code vid}
 *       没有任何赋值入口，响应里恒为 {@code null}；</li>
 *   <li>没有 operationId、操作表、审计平台、租约或后台任务；“创建”不是幂等创建：
 *       同标题可以重复创建出多个空草稿（它们都不是 video），重试可能多一个草稿；</li>
 *   <li>不按标题去重，也不建 title 唯一键；{@code vid} 的并发唯一性留给将来的导入切片用
 *       episode 行锁 + {@code vid IS NULL} CAS 解决，本片不预先编造那套机制。</li>
 * </ul>
 *
 * <h2>身份归属</h2>
 * <p>{@code createdBy} 是<b>创建者</b>（登录管理员 {@code currentUserId}），不是未来
 * {@code video.owner}：将来“首次导入该集”的管理员才是视频作者，后续加源不改作者。</p>
 *
 * <h2>错误码约定</h2>
 * <ul>
 *   <li>缺少登录态 uid → 401；</li>
 *   <li>参数非法（空白/超长标题、非正 id、{@code afterId < 0}、{@code limit} 不在 1..100）→ 400；</li>
 *   <li>父作品不存在 → 404；</li>
 *   <li>写入行数不为 1、未取得生成主键、回读为空 → 500（并回滚本次写事务）。</li>
 * </ul>
 * <p>数据库异常<b>不</b>统一翻译成 409：本片没有版本冲突语义，唯一的唯一键冲突来自服务端随机身份，
 * 属于真正的内部错误，由全局异常处理器按 500 处理。</p>
 */
public interface LocalVideoCatalogService {

    /** 分页下界（含）。 */
    int MIN_PAGE_LIMIT = 1;
    /** 分页上界（含）。 */
    int MAX_PAGE_LIMIT = 100;

    /** 作品标题最大长度（trim 后）。 */
    int MAX_TITLE_LENGTH = 200;
    /** 剧集展示标题最大长度（trim 后）。 */
    int MAX_DISPLAY_TITLE_LENGTH = 255;

    /**
     * 创建本地作品。
     *
     * @param operatorId 登录态 uid；为 {@code null} 时 401（不伪造“匿名管理员”）
     * @param title      展示标题，trim 后必须非空且不超过 {@value #MAX_TITLE_LENGTH}
     */
    LocalVideoSeriesVO createSeries(Integer operatorId, String title);

    /** 按 {@code id} 升序 keyset 分页读作品（{@code afterId} 不含）。 */
    LocalVideoPageVO<LocalVideoSeriesVO> listSeries(long afterId, int limit);

    /**
     * 在既有本地作品下创建剧集。
     *
     * @param operatorId   登录态 uid；为 {@code null} 时 401
     * @param seriesId     父作品 id，必须为正且真实存在（否则 400 / 404）
     * @param displayTitle 可空；非 {@code null} 时 trim 后必须非空且不超过
     *                     {@value #MAX_DISPLAY_TITLE_LENGTH}
     */
    LocalVideoEpisodeVO createEpisode(Integer operatorId, long seriesId, String displayTitle);

    /**
     * 按 {@code series_id} + {@code id} 升序 keyset 分页读剧集；父作品不存在时 404
     * （空页是合法的“这个作品还没有剧集”，与“作品不存在”必须区分开）。
     */
    LocalVideoPageVO<LocalVideoEpisodeVO> listEpisodes(long seriesId, long afterId, int limit);
}
