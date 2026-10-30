package com.feibijiubi.backend.video.local.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地作品（{@code local_video_series}）：一条与任何上游来源无关的本地目录行。
 *
 * <p>本片（S1）只有“管理员创建一个空草稿 / 分页读”两条路径，没有 update、delete、合并或导入。
 * {@code localIdentity} 由服务端生成（随机 UUIDv4 规范小写 36 字符），请求体不能提供；
 * {@code title} 不是身份，服务器不做同标题合并，也不建唯一键。</p>
 *
 * <p>{@code createdBy} 是<b>创建者</b>（登录管理员 uid），不是未来 {@code video.owner}：
 * 只有将来真正导入并创建视频的那一次才是作者，后续加源不改作者。</p>
 *
 * <p>{@code createdAt} / {@code updatedAt} 只用于容纳数据库回读的真实时间戳：本实体<b>不是</b>
 * 插入输入，INSERT 语句在列级省略这两列，由数据库默认值填写。</p>
 */
@Data
public class LocalVideoSeries {
    private Long id;
    private String localIdentity;
    private String title;
    private Integer createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
