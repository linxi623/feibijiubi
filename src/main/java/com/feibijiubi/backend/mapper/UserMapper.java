package com.feibijiubi.backend.mapper;

import com.feibijiubi.backend.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    /**
     * 查询当前用户名的数量
     */
    int countByUsername(String username);

    /**
     * 创建一个用户。
     */
    int createUser(User user);

    /**
     * 用户登录
     */
    User selectByUsernameForLogin(String username);

    /**
     * 根据用户 id 查询用户信息。
     */
    User selectById(Integer id);

    /**
     * 根据用户 id 修改用户信息
     */
    int updateProfileById(User user);

    /**
     * 根据用户 id 更改密码
     * @param user
     */
    void updatePassword(User user);

    /**
     * 根据用户 id 更新头像
     * @param currentUserId
     * @param avatarUrl
     */
    void updateAvatar(@Param("currentUserId")Integer currentUserId,
                      @Param("avatarUrl")String avatarUrl);

    int decreaseCoin(@Param("currentUserId")Integer currentUserId,
                     @Param("coin")Byte coin);

    int increaseCoin(@Param("currentUserId")Integer currentUserId,
                    @Param("coin")Byte coin);
}
