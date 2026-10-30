package com.feibijiubi.backend.video.service;

import com.feibijiubi.backend.shared.BusinessException;
import com.feibijiubi.backend.video.entity.Video;
import com.feibijiubi.backend.video.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * “这个视频现在能不能被互动”的唯一判定处（原来散落在 {@code UserVideoServiceImpl} 的私有方法里）。
 *
 * <p>抽出来的原因：T15 第二阶段把播放/分享的入口挪到了
 * {@code VideoInteractionAdmissionService}，但产品要求<b>保持原有校验约束不变</b>
 * （参数 400 / 未过审或不存在 404 / 时长数据异常 500）。如果两边各写一份，就会出现
 * “老接口 404、新入口 500”这种只有对比调用才能发现的分歧。因此规则只留一份，两边都调它。</p>
 *
 * <p>本类只读，不加锁、不缓存：视频可见性/审核状态可以在任意时刻变化，把它缓存进 Redis 会引入
 * 一个新的失效问题，而这里每次只是一条主键查询。</p>
 */
@Component
@RequiredArgsConstructor
public class PublishedVideoGuard {

    private final VideoMapper videoMapper;

    /**
     * 读取“已过审且未删除”的视频，并校验后续统计更新依赖的时长字段。
     *
     * @throws BusinessException 400 vid 非法；404 视频不存在/未过审/已删除；500 时长数据异常
     */
    public Video requirePublishedPlayable(Integer vid) {
        if (vid == null || vid <= 0) {
            throw new BusinessException(400, "视频参数不合法");
        }

        Video video = videoMapper.selectPublishedByVid(vid);
        if (video == null) {
            throw new BusinessException(404, "视频不存在");
        }
        if (video.getDuration() == null || video.getDuration() <= 0) {
            throw new BusinessException(500, "视频时长数据异常");
        }
        return video;
    }
}
