package com.feibijiubi.backend.video.local.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地剧集（{@code local_video_episode}）：挂在本地作品下的一个条目。
 *
 * <p>{@code localIdentity} 同样由服务端生成，不由上游 ID、集数、标题或地址派生。
 * {@code displayTitle} 可空，只是展示文本，不参与身份与唯一键。</p>
 *
 * <p>草稿创建的INSERT省略durationSeconds/vid，两者初始为NULL。S2来源确认服务可在锁内
 * 设置统一时长；M5导入在锁内写入vid与importRevision。查询返回真实值，不伪造成0或“已就绪”。</p>
 */
@Data
public class LocalVideoEpisode {
    private Long id;
    private Long seriesId;
    private String localIdentity;
    private String displayTitle;
    private Double durationSeconds;
    private Integer vid;
    /** M5首次导入时观察到的来源revision；重试稳定返回，M6线路变更不覆盖。未导入为null。 */
    private Long importRevision;
    private Integer createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
