package com.feibijiubi.backend.user.vo;

import lombok.Data;

@Data
public class UserCountVO {
    private Integer fansCount;
    private Integer starCount;
    /** 个人获赞总数：跨视频 SUM(like_times)，同样使用 Long。 */
    private Long loveCount;
    private Integer videoCount;
}
