
# 实体类
## entity（对应DB）
- User：用来表示每一个用户（普通、管理员）
- Video：用来表示每一个视频
- VideoStats：用来记录每一个视频的点赞，硬币，评论之类的状态
- UserVideo：用来记录用户对某一视频的交互转态（观看次数、点赞、硬币数）
- Favourite：收藏夹
- Comment：评论
- Chat：右上角的会话，相当于消息列表
- ChatDetailed：一个Chat下面会包含很多个ChatDetailed
- Danmu：弹幕
- HotSearch：热搜列表
- MsgUnread：未读消息
- Category：分类
## dto（接收前端更新请求）
>前端返回信息与数据库的表不一致
- UserDTO
- CategoryDTO
- VideoUploadInfoDTO
## vo（返回前端需要的字段）
>前端页面需要展示的内容才返回，可以整合多个表的内容

## ES（搜索引擎文档对象）
- VideoESDoc：视频搜索
- UserEsDoc：用户搜索
- SearchWordEsDoc：关键词搜索