# 菲比啾比（feibijiubi）

单仓库结构：

- `backend/`：Java 17 + Spring Boot API 服务
- `admin/`：Vue 3 管理端
- `client/`：Vue 3 客户端

## 开发命令

```powershell
backend\mvnw.cmd test
npm.cmd --prefix admin install
npm.cmd --prefix admin run serve
npm.cmd --prefix client install
npm.cmd --prefix client run serve
```

前端开发服务器默认代理到 `http://localhost:8080/api`。
