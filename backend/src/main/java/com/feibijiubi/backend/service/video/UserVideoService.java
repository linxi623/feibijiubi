package com.feibijiubi.backend.service.video;

public interface UserVideoService {
    void increasePlayCount(Integer vid);

    void savePlayProgress(Integer vid, Integer currentUserId, Double playTime);

    void recordLike(Integer currentUserId, Integer vid, Boolean islike, Boolean isSet);

    void increaseCoin(Integer currentUserId, Integer vid, Byte coin);

    void increaseShare(Integer vid);

    void Collect(Integer currentUserId, Integer vid, Boolean isCollect);
}
