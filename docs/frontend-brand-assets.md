# 菲比啾比前端品牌素材指南

所有品牌素材位于 `client/src/assets/img/feibijiubi/` 与 `admin/src/assets/img/feibijiubi/`。

## 当前素材槽位

| 槽位 | 推荐尺寸 | 格式 | 用途 |
| --- | ---: | --- | --- |
| `hero-banner.jpg` | 3000×231 | JPG | 客户端首页头部，中心区域保持低细节 |
| `carousel/feibijiubi-carousel-*.jpg` | 1280×720 | JPG | 客户端首页轮播，左侧预留标题空间 |
| `feibijiubi-logo.png` | 650×650 | PNG | Logo、头像和空状态角色 |
| `login-bg.jpg` | 2970×1620 | JPG | 管理端登录页，右侧预留表单空间 |

## 替换方向

后续更换时保持菲比的帽子、奶油金头发、紫色眼睛、蓝色发夹和黑白蓝服饰；首页头图与登录背景要保留导航/表单安全区。若尺寸不变可直接替换同名文件，尺寸变化时同步调整引用页面的 `background-size` 和容器比例。

## 验收

替换后运行 `npm.cmd --prefix client run build` 与 `npm.cmd --prefix admin run build`，并检查首页、登录、轮播、空状态和窄屏裁切。
