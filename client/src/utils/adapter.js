import { DEFAULT_AVATAR, resolveMediaUrl } from '@/utils/media';

/**
 * 菲比啾比后端 VO -> 旧版前端模板期望形状的适配层。
 *
 * 旧版前端大量模板依赖历史字段命名（user.uid / user.avatar_url /
 * video.descr / 嵌套的 {video, user, stats} 包装对象等）。
 * 为了避免大面积改动模板，统一在数据获取边界做一次形状转换。
 */

/**
 * 适配用户对象。
 * 后端 UserVO: {id, username, nickname, avatarUrl, backgroundUrl, gender, description,
 *   experience, coin, vip, status, role, auth, authMsg, createdAt,
 *   userCount: {fansCount, starCount, loveCount, videoCount}, subscribed}
 * 前端期望: {uid, nickname, avatar_url, bg_url, gender, description, exp, coin, vip,
 *   state, auth, authMsg, fansCount, followsCount, loveCount, videoCount, ...}
 */
export function adaptUser(data) {
    if (!data) return {};
    const count = data.userCount || {};
    return {
        ...data,
        uid: data.id ?? data.uid,
        avatar_url: resolveMediaUrl(data.avatarUrl || data.avatar_url) || DEFAULT_AVATAR,
        bg_url: resolveMediaUrl(data.backgroundUrl || data.background_url),
        exp: data.experience || 0,
        state: data.status,
        fansCount: count.fansCount || 0,
        followsCount: count.starCount || 0,
        loveCount: count.loveCount || 0,
        videoCount: count.videoCount || 0,
    };
}

/**
 * 适配视频列表项。
 * 后端 VideoListItemVO: {vid, uid, title, coverUrl, duration, playTimes,
 *   commentTimes, createdAt, nickname}
 * 前端期望: {video: {...}, user: {...}, stats: {...}}
 */
export function adaptVideoItem(item) {
    if (!item) return null;
    return {
        video: {
            vid: item.vid,
            uid: item.uid,
            title: item.title,
            coverUrl: item.coverUrl,
            duration: item.duration,
            uploadDate: formatDateTime(item.createdAt),
            status: 1, // 公开 feed 只返回已过审视频
            descr: "",
        },
        user: {
            uid: item.uid,
            nickname: item.nickname,
        },
        stats: {
            play: item.playTimes || 0,
            danmu: 0, // 后端暂未提供弹幕数
            comment: item.commentTimes || 0,
        },
    };
}

/**
 * 适配分区列表。
 * 后端 CategoryParentVO: {mcId, mcName, children: [{scId, scName, description, rcmTags}]}
 * 前端期望: {mcId, mcName, scList: [{mcId, scId, scName, descr, rcmTag}]}
 */
export function adaptChannels(list) {
    if (!Array.isArray(list)) return [];
    return list.map(parent => ({
        mcId: parent.mcId,
        mcName: parent.mcName,
        scList: (parent.children || []).map(child => ({
            mcId: parent.mcId,
            scId: child.scId,
            scName: child.scName,
            descr: child.description,
            rcmTag: child.rcmTags || [],
        })),
    }));
}

/** ISO 时间 -> "yyyy-MM-dd HH:mm:ss" */
export function formatDateTime(value) {
    if (!value) return "";
    return String(value).replace("T", " ").slice(0, 19);
}
