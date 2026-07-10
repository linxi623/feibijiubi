# Git 开发常用指令与使用场景

> 本文面向刚接触 Git 的后端开发者，重点总结日常开发中最常用的指令、典型使用场景以及容易踩坑的操作。

## 1. Git 是什么

Git 是一个**分布式版本控制系统**，主要用于：

- 记录代码的每次修改；
- 查看代码由谁、在什么时候修改；
- 在不同功能之间切换；
- 多人协作开发；
- 在出现问题时恢复到历史版本；
- 配合 GitHub、GitLab、Gitee 等平台进行代码托管和评审。

可以把 Git 理解为项目代码的“存档系统”。但它不只是简单地复制文件，而是会保存每次提交之间的变化和完整的开发历史。

---

## 2. Git 中的几个重要区域

理解 Git 指令之前，应先认识 Git 的几个区域。

```text
工作区（Working Directory）
        │ git add
        ▼
暂存区（Staging Area）
        │ git commit
        ▼
本地仓库（Local Repository）
        │ git push
        ▼
远程仓库（Remote Repository）
```

### 2.1 工作区

工作区就是当前可以直接看到和编辑的项目文件。

例如修改了：

```text
src/main/java/com/feibijiubi/backend/service/VideoService.java
```

此时修改只存在于工作区，还没有进入 Git 的下一次提交。

### 2.2 暂存区

暂存区用于保存“准备在下一次提交中包含的修改”。

使用以下命令将修改放入暂存区：

```bash
git add 文件名
```

暂存区可以帮助开发者精确控制一次提交中包含哪些修改。

### 2.3 本地仓库

执行 `git commit` 后，暂存区中的修改会被保存到本地 Git 仓库，形成一条提交记录。

```bash
git commit -m "完成视频点赞功能"
```

### 2.4 远程仓库

远程仓库通常位于 GitHub、GitLab 或 Gitee 等平台。

执行 `git push` 后，本地提交才会被上传到远程仓库：

```bash
git push
```

> `git commit` 只提交到本地仓库；`git push` 才是上传到远程仓库。

---

## 3. 第一次使用 Git 时的配置

## 3.1 配置用户名

```bash
git config --global user.name "你的名字"
```

例如：

```bash
git config --global user.name "Win-linxi"
```

## 3.2 配置邮箱

```bash
git config --global user.email "你的邮箱"
```

例如：

```bash
git config --global user.email "example@qq.com"
```

用户名和邮箱会记录在每次提交中，用于标识提交者。

## 3.3 查看全部 Git 配置

```bash
git config --list
```

## 3.4 查看某一项配置

```bash
git config user.name
git config user.email
```

## 3.5 配置的作用范围

Git 配置通常有以下两个常用范围：

| 范围 | 参数 | 说明 |
|---|---|---|
| 全局配置 | `--global` | 对当前电脑用户的所有仓库生效 |
| 当前仓库 | `--local` | 只对当前 Git 仓库生效 |

如果公司项目和个人项目需要使用不同邮箱，可以进入公司项目后执行：

```bash
git config --local user.name "公司用户名"
git config --local user.email "公司邮箱"
```

---

## 4. 创建或获取 Git 仓库

## 4.1 在现有项目中初始化 Git

```bash
git init
```

### 使用场景

本地已经创建了一个项目，现在希望使用 Git 管理它。

执行后，项目目录中会生成一个隐藏的 `.git` 目录。这个目录保存了 Git 仓库的版本历史和配置，不要手动删除或修改。

## 4.2 克隆远程仓库

```bash
git clone <仓库地址>
```

例如：

```bash
git clone https://github.com/example/backend.git
```

也可以指定克隆后的目录名：

```bash
git clone https://github.com/example/backend.git my-backend
```

### 使用场景

- 第一次将远程项目下载到本地；
- 加入一个已有项目；
- 在新电脑上获取项目代码。

`git clone` 通常会同时完成：

1. 下载项目文件；
2. 下载提交历史；
3. 自动添加远程仓库地址；
4. 检出默认分支。

---

## 5. 查看仓库状态

## 5.1 查看当前状态

```bash
git status
```

这是开发过程中最常用、也最安全的 Git 指令之一。

它可以显示：

- 当前所在分支；
- 哪些文件被修改；
- 哪些文件尚未被 Git 跟踪；
- 哪些修改已经进入暂存区；
- 哪些文件存在冲突；
- 当前分支与远程分支的关系。

### 推荐使用时机

- 开始开发前；
- 执行 `git add` 前后；
- 执行 `git commit` 前；
- 切换分支前；
- 解决冲突时；
- 不清楚当前仓库状态时。

简洁显示状态：

```bash
git status --short
```

常见标记：

| 标记 | 含义 |
|---|---|
| `??` | 未被 Git 跟踪的新文件 |
| `M` | 文件被修改 |
| `A` | 新文件已加入暂存区 |
| `D` | 文件被删除 |
| `UU` | 文件存在合并冲突 |

---

## 6. 查看代码修改

## 6.1 查看工作区中尚未暂存的修改

```bash
git diff
```

### 使用场景

写完代码后，检查自己具体修改了哪些内容。

## 6.2 查看某个文件的修改

```bash
git diff -- 文件路径
```

例如：

```bash
git diff -- src/main/java/com/feibijiubi/backend/service/VideoService.java
```

`--` 用于告诉 Git：后面的内容是文件路径，而不是命令参数或分支名称。

## 6.3 查看已经暂存的修改

```bash
git diff --staged
```

也可以写成：

```bash
git diff --cached
```

### 使用场景

执行 `git add` 后、执行 `git commit` 前，确认下一次提交究竟会包含哪些修改。

## 6.4 查看某次提交的内容

```bash
git show <提交ID>
```

例如：

```bash
git show 6cf9610
```

只查看某次提交的概要：

```bash
git show --stat <提交ID>
```

---

## 7. 将修改放入暂存区

## 7.1 暂存指定文件

```bash
git add <文件名>
```

例如：

```bash
git add src/main/java/com/feibijiubi/backend/service/VideoService.java
```

### 使用场景

只希望将某个文件放入下一次提交。

## 7.2 暂存多个指定文件

```bash
git add 文件1 文件2
```

例如：

```bash
git add VideoController.java VideoService.java
```

## 7.3 暂存当前目录下的全部修改

```bash
git add .
```

它通常会包含：

- 新增的文件；
- 修改的文件；
- 删除的文件。

### 注意

`git add .` 很方便，但不要执行后直接提交。应先运行：

```bash
git status
git diff --staged
```

确认没有把日志、密码、临时文件或无关修改加入提交。

## 7.4 交互式暂存部分修改

```bash
git add -p
```

### 使用场景

一个文件中包含两类修改，但只想提交其中一部分。

常见选项：

| 选项 | 含义 |
|---|---|
| `y` | 暂存当前修改块 |
| `n` | 不暂存当前修改块 |
| `s` | 将当前修改块继续拆分 |
| `q` | 退出 |
| `?` | 查看帮助 |

这是保持提交内容单一、清晰的实用命令。

---

## 8. 提交代码

## 8.1 创建一次提交

```bash
git commit -m "提交说明"
```

例如：

```bash
git commit -m "实现视频点赞接口"
```

一次提交应尽量只解决一个明确的问题。

推荐：

```text
实现视频点赞接口
修复重复投币导致计数错误的问题
补充视频上传参数校验
重构用户登录异常处理
```

不推荐：

```text
修改代码
更新一下
fix bug
完成项目
```

## 8.2 查看即将提交的内容后再提交

```bash
git commit -v
```

该命令会打开文本编辑器，并在编辑提交说明时显示本次修改内容。

## 8.3 修改最近一次提交

```bash
git commit --amend
```

如果只是修改最近一次提交说明：

```bash
git commit --amend -m "新的提交说明"
```

如果最近一次提交漏掉了文件：

```bash
git add 遗漏的文件
git commit --amend --no-edit
```

`--no-edit` 表示保留原来的提交说明。

### 注意

`git commit --amend` 会改写最近一次提交的提交 ID。

如果该提交已经推送到共享远程分支，不建议随意使用，否则可能影响其他开发者。

---

## 9. 查看提交历史

## 9.1 查看完整提交历史

```bash
git log
```

通常可以看到：

- 提交 ID；
- 作者；
- 提交时间；
- 提交说明。

按 `q` 退出日志查看界面。

## 9.2 单行显示提交历史

```bash
git log --oneline
```

输出示例：

```text
6cf9610 修复并发计数问题
02c3e00 修复点赞和点踩问题
6730b48 完成视频互动功能
```

## 9.3 图形化显示分支历史

```bash
git log --oneline --graph --decorate --all
```

该命令适合查看：

- 分支从哪里创建；
- 分支在哪里合并；
- 本地分支和远程分支分别指向哪里。

## 9.4 查看某个文件的提交历史

```bash
git log -- 文件路径
```

例如：

```bash
git log -- src/main/java/com/feibijiubi/backend/service/VideoService.java
```

同时查看每次修改的具体内容：

```bash
git log -p -- 文件路径
```

## 9.5 查看每一行最后由谁修改

```bash
git blame 文件路径
```

### 使用场景

- 理解某段代码的修改背景；
- 找到引入某段逻辑的提交；
- 配合 `git show` 查看当时为什么这样修改。

`git blame` 更适合用于追踪上下文，而不是“追责”。

---

## 10. 分支管理

分支可以让不同功能在相互隔离的环境中开发。

例如：

```text
main                  稳定主分支
├── feature/video-like  视频点赞功能
├── feature/comment     评论功能
└── fix/login-error     登录问题修复
```

## 10.1 查看本地分支

```bash
git branch
```

当前分支前会带有 `*`：

```text
* main
  feature/video-like
```

## 10.2 查看本地和远程分支

```bash
git branch -a
```

## 10.3 创建新分支

```bash
git branch <分支名>
```

例如：

```bash
git branch feature/video-comment
```

该命令只创建分支，不会自动切换过去。

## 10.4 切换分支

推荐使用：

```bash
git switch <分支名>
```

例如：

```bash
git switch feature/video-comment
```

传统写法是：

```bash
git checkout feature/video-comment
```

## 10.5 创建并立即切换分支

推荐使用：

```bash
git switch -c <新分支名>
```

例如：

```bash
git switch -c feature/video-comment
```

传统写法：

```bash
git checkout -b feature/video-comment
```

## 10.6 重命名当前分支

```bash
git branch -m <新分支名>
```

## 10.7 删除已经合并的本地分支

```bash
git branch -d <分支名>
```

例如：

```bash
git branch -d feature/video-comment
```

`-d` 会检查分支是否已合并，比较安全。

## 10.8 强制删除本地分支

```bash
git branch -D <分支名>
```

### 注意

`-D` 会忽略分支是否已经合并，可能导致尚未合并的提交难以找回。除非确定分支内容不再需要，否则优先使用 `-d`。

## 10.9 常见分支命名

| 类型 | 示例 | 用途 |
|---|---|---|
| 功能开发 | `feature/video-comment` | 开发新功能 |
| Bug 修复 | `fix/video-like-count` | 修复普通问题 |
| 紧急修复 | `hotfix/login-failure` | 修复线上紧急问题 |
| 重构 | `refactor/video-service` | 重构代码 |
| 文档 | `docs/git-guide` | 修改文档 |
| 测试 | `test/video-service` | 补充或调整测试 |

团队应统一分支命名规范。

---

## 11. 合并分支

假设已经在 `feature/video-comment` 分支完成开发，现在需要合并到 `main`。

## 11.1 先切换到接收修改的分支

```bash
git switch main
```

## 11.2 更新本地主分支

```bash
git pull
```

## 11.3 合并功能分支

```bash
git merge feature/video-comment
```

这里的含义是：把 `feature/video-comment` 合并到当前所在的 `main` 分支。

### 记忆方式

```text
先站到“接收代码”的分支，再执行 git merge “提供代码”的分支。
```

## 11.4 删除已经合并的功能分支

```bash
git branch -d feature/video-comment
```

### 团队项目中的常见做法

实际团队开发中，通常不是直接在本地合并到 `main`，而是：

1. 创建功能分支；
2. 在功能分支开发并提交；
3. 推送功能分支；
4. 创建 Pull Request 或 Merge Request；
5. 通过代码评审和自动化测试；
6. 在代码托管平台合并。

---

## 12. 远程仓库管理

## 12.1 查看远程仓库

```bash
git remote -v
```

输出示例：

```text
origin  https://github.com/example/backend.git (fetch)
origin  https://github.com/example/backend.git (push)
```

`origin` 是克隆仓库时默认使用的远程仓库名称，但它并不是固定关键字。

## 12.2 添加远程仓库

```bash
git remote add origin <仓库地址>
```

例如：

```bash
git remote add origin https://github.com/example/backend.git
```

### 使用场景

本地使用 `git init` 创建了仓库，现在需要关联远程仓库。

## 12.3 修改远程仓库地址

```bash
git remote set-url origin <新地址>
```

## 12.4 删除远程仓库关联

```bash
git remote remove origin
```

该命令只删除本地记录的远程地址，不会删除远程平台上的仓库。

## 12.5 查看远程仓库详细信息

```bash
git remote show origin
```

---

## 13. 获取和推送远程代码

## 13.1 获取远程仓库信息但不自动合并

```bash
git fetch
```

也可以指定远程仓库：

```bash
git fetch origin
```

### 使用场景

想先看看远程仓库发生了什么变化，但暂时不修改当前分支。

`git fetch` 会更新类似以下的远程跟踪分支：

```text
origin/main
origin/feature/video-comment
```

但不会自动将这些变化合并到当前分支。

## 13.2 拉取远程代码并合并

```bash
git pull
```

它通常相当于：

```bash
git fetch
git merge
```

指定远程仓库和分支：

```bash
git pull origin main
```

### 使用场景

将远程分支上的最新提交同步到当前本地分支。

### 推荐习惯

拉取前先执行：

```bash
git status
```

尽量保证工作区干净。如果当前存在未提交修改，`git pull` 可能造成合并困难或被 Git 拒绝。

## 13.3 使用 rebase 方式拉取

```bash
git pull --rebase
```

### 使用场景

希望把自己的本地提交移动到远程最新提交之后，减少不必要的合并提交。

假设原来是：

```text
A---B---C  origin/main
     \
      D---E  main
```

执行 `git pull --rebase` 后，可能变成：

```text
A---B---C---D'---E'  main
```

因为 rebase 会重写提交，初学阶段应先理解原理，并遵守团队规范。

## 13.4 推送当前分支

```bash
git push
```

## 13.5 第一次推送新分支

```bash
git push -u origin <分支名>
```

例如：

```bash
git push -u origin feature/video-comment
```

`-u` 会建立本地分支与远程分支的跟踪关系。之后通常可以直接使用：

```bash
git push
git pull
```

## 13.6 删除远程分支

```bash
git push origin --delete <分支名>
```

例如：

```bash
git push origin --delete feature/video-comment
```

### 注意

删除远程分支会影响团队成员。执行前应确认该分支已合并且不再需要。

---

## 14. `fetch`、`pull` 和 `push` 的区别

| 指令 | 数据方向 | 是否修改当前代码 | 作用 |
|---|---|---|---|
| `git fetch` | 远程 → 本地 | 通常不会 | 获取远程最新状态 |
| `git pull` | 远程 → 本地 | 会 | 获取并合并远程代码 |
| `git push` | 本地 → 远程 | 修改远程仓库 | 上传本地提交 |

可以简单记忆：

```text
fetch：只获取，先看看
pull：拉下来并整合
push：推到远程
```

---

## 15. 撤销尚未提交的修改

撤销操作需要非常谨慎。执行前建议先运行：

```bash
git status
git diff
```

## 15.1 撤销某个文件在工作区中的修改

```bash
git restore <文件名>
```

例如：

```bash
git restore VideoService.java
```

该命令会把文件恢复成暂存区或最近一次提交中的状态。

### 注意

未提交的修改可能会永久丢失。

传统写法：

```bash
git checkout -- <文件名>
```

新项目更推荐使用语义清晰的 `git restore`。

## 15.2 撤销工作区全部未暂存修改

```bash
git restore .
```

该命令具有破坏性，执行前必须确认所有未暂存修改都不再需要。

## 15.3 将文件移出暂存区，但保留工作区修改

```bash
git restore --staged <文件名>
```

例如：

```bash
git restore --staged VideoService.java
```

### 使用场景

误执行了 `git add`，但仍想保留代码修改，只是不希望它进入下一次提交。

移出全部暂存内容：

```bash
git restore --staged .
```

## 15.4 删除未被 Git 跟踪的文件

先预览将被删除的内容：

```bash
git clean -n
```

确认后再删除：

```bash
git clean -f
```

同时删除未跟踪目录：

```bash
git clean -fd
```

### 注意

`git clean` 删除的是未被 Git 跟踪的文件，这些文件通常无法通过 Git 恢复。务必先使用 `git clean -n` 预览。

---

## 16. 撤销已经提交的修改

## 16.1 使用 `revert` 创建反向提交

```bash
git revert <提交ID>
```

例如：

```bash
git revert 6cf9610
```

它不会删除原提交，而是创建一个新提交，用相反的修改抵消目标提交。

原历史：

```text
A---B---C
```

撤销 `C` 后：

```text
A---B---C---D
```

其中 `D` 是撤销 `C` 的新提交。

### 使用场景

- 撤销已经推送的提交；
- 撤销共享分支上的错误修改；
- 希望保留完整、可追踪的历史。

### 推荐原则

对于已经推送到共享分支的提交，优先使用 `git revert`。

## 16.2 使用 `reset` 移动分支指针

### 保留工作区和暂存区修改

```bash
git reset --soft HEAD~1
```

效果：

- 撤销最近一次提交；
- 修改仍保留在暂存区；
- 可以重新整理后提交。

### 保留工作区修改，清空暂存区

```bash
git reset --mixed HEAD~1
```

`--mixed` 是 `git reset` 的默认模式。

效果：

- 撤销最近一次提交；
- 修改保留在工作区；
- 修改不在暂存区。

### 同时丢弃提交和代码修改

```bash
git reset --hard HEAD~1
```

效果：

- 删除最近一次提交；
- 丢弃暂存区修改；
- 丢弃工作区修改。

### 高风险警告

`git reset --hard` 可能造成代码丢失。除非明确知道要删除什么，否则不要使用。

已经推送到共享分支的提交，不要随意通过 `reset` 和强制推送改写历史。

## 16.3 `revert` 和 `reset` 的区别

| 对比项 | `git revert` | `git reset` |
|---|---|---|
| 是否保留原提交 | 保留 | 可能从当前分支历史中移除 |
| 是否创建新提交 | 是 | 否 |
| 是否改写历史 | 否 | 是 |
| 是否适合共享分支 | 适合 | 通常不适合 |
| 风险 | 较低 | 较高 |

---

## 17. 临时保存未完成的修改

## 17.1 保存当前修改

```bash
git stash
```

更推荐添加说明：

```bash
git stash push -m "暂存未完成的视频评论功能"
```

### 使用场景

正在开发一个功能，但突然需要：

- 切换分支修复紧急 Bug；
- 拉取远程代码；
- 临时测试另一个分支；
- 保持工作区干净。

## 17.2 查看 stash 列表

```bash
git stash list
```

输出示例：

```text
stash@{0}: On feature/comment: 暂存未完成的评论功能
stash@{1}: On main: 临时修改配置
```

## 17.3 恢复最近一次 stash，并保留 stash 记录

```bash
git stash apply
```

恢复指定 stash：

```bash
git stash apply stash@{1}
```

## 17.4 恢复最近一次 stash，并删除 stash 记录

```bash
git stash pop
```

`pop` 可以理解为：

```text
apply + drop
```

如果恢复时发生冲突，需要先解决冲突；stash 记录也可能不会被自动删除。

## 17.5 删除指定 stash

```bash
git stash drop stash@{0}
```

## 17.6 删除所有 stash

```bash
git stash clear
```

该操作通常难以恢复，执行前应运行 `git stash list` 确认。

## 17.7 同时保存未跟踪文件

默认情况下，`git stash` 通常不会保存未被 Git 跟踪的新文件。需要同时保存时使用：

```bash
git stash -u
```

---

## 18. 解决合并冲突

当两个分支修改了同一文件的同一位置，并且 Git 无法自动判断应该保留哪一份内容时，就会发生冲突。

冲突内容可能如下：

```text
<<<<<<< HEAD
当前分支的代码
=======
被合并分支的代码
>>>>>>> feature/video-comment
```

含义：

- `<<<<<<< HEAD` 到 `=======`：当前分支的内容；
- `=======` 到 `>>>>>>>`：另一个分支的内容。

## 18.1 解决冲突的基本流程

### 第一步：查看冲突文件

```bash
git status
```

### 第二步：手动修改文件

需要删除冲突标记，并根据业务需求保留正确代码。

不能机械地选择“全部保留当前版本”或“全部保留对方版本”，必须理解双方修改的业务含义。

### 第三步：将已解决文件加入暂存区

```bash
git add <冲突文件>
```

### 第四步：完成合并

```bash
git commit
```

如果是 rebase 冲突，通常使用：

```bash
git rebase --continue
```

### 第五步：运行测试

例如本项目使用：

```bash
./mvnw test
```

没有冲突标记并不代表业务逻辑一定正确，因此合并后必须编译或测试。

## 18.2 放弃本次 merge

```bash
git merge --abort
```

### 使用场景

冲突过于复杂，想返回合并前的状态，重新分析后再合并。

## 18.3 放弃本次 rebase

```bash
git rebase --abort
```

---

## 19. Rebase 的基本使用

`rebase` 可以把当前分支上的提交重新应用到另一个分支的最新提交之后。

假设：

```text
A---B---C  main
     \
      D---E  feature
```

在 `feature` 分支执行：

```bash
git rebase main
```

结果类似：

```text
A---B---C---D'---E'  feature
```

## 19.1 功能分支同步主分支最新代码

```bash
git switch main
git pull
git switch feature/video-comment
git rebase main
```

发生冲突时：

```bash
# 手动解决冲突
git add 冲突文件
git rebase --continue
```

放弃 rebase：

```bash
git rebase --abort
```

## 19.2 Rebase 的注意事项

Rebase 会重写提交历史，提交 ID 会发生变化。

建议遵循：

> 可以 rebase 自己尚未共享的功能分支，不要随意 rebase 多人共同使用的公共分支。

如果分支已经推送，rebase 后可能需要强制推送。即使必须使用，也优先使用：

```bash
git push --force-with-lease
```

而不是：

```bash
git push --force
```

`--force-with-lease` 会检查远程分支是否被其他人更新，相对更安全，但它仍然会改写远程历史，团队开发中必须谨慎使用。

---

## 20. 挑选某个提交

```bash
git cherry-pick <提交ID>
```

### 使用场景

只想把另一个分支中的某一个提交应用到当前分支，而不是合并整个分支。

例如：

```bash
git switch main
git cherry-pick a1b2c3d
```

发生冲突时：

```bash
# 解决冲突后
git add 冲突文件
git cherry-pick --continue
```

放弃本次操作：

```bash
git cherry-pick --abort
```

### 注意

不要为了方便而大量使用 `cherry-pick` 替代正常分支合并，否则提交关系可能变得难以理解。

---

## 21. 标签管理

标签通常用于标记发布版本。

## 21.1 查看标签

```bash
git tag
```

## 21.2 创建附注标签

```bash
git tag -a v1.0.0 -m "发布 1.0.0 版本"
```

## 21.3 为指定提交创建标签

```bash
git tag -a v1.0.0 <提交ID> -m "发布 1.0.0 版本"
```

## 21.4 查看标签信息

```bash
git show v1.0.0
```

## 21.5 推送指定标签

```bash
git push origin v1.0.0
```

## 21.6 推送所有标签

```bash
git push origin --tags
```

## 21.7 删除本地标签

```bash
git tag -d v1.0.0
```

## 21.8 删除远程标签

```bash
git push origin --delete v1.0.0
```

发布后的标签通常不应随意移动或删除。

---

## 22. Git 忽略文件

项目根目录中的 `.gitignore` 用于告诉 Git：哪些文件不应该纳入版本控制。

Java Spring Boot 项目常见内容：

```gitignore
# Maven 构建产物
target/

# IntelliJ IDEA
.idea/
*.iml

# VS Code
.vscode/

# 日志
*.log
logs/

# 操作系统文件
.DS_Store
Thumbs.db

# 本地环境配置
.env
application-local.properties
application-local.yml
```

### 通常不应提交的内容

- 编译产物；
- IDE 临时文件；
- 日志文件；
- 本地缓存；
- 密码、Token、密钥；
- 只属于个人电脑的配置；
- 可通过依赖管理工具重新下载的文件。

## 22.1 检查文件为什么被忽略

```bash
git check-ignore -v <文件路径>
```

## 22.2 文件已经被跟踪后再写入 `.gitignore`

`.gitignore` 只对尚未被 Git 跟踪的文件生效。

如果文件已经被提交，需要先停止跟踪，但保留本地文件：

```bash
git rm --cached <文件路径>
```

如果是目录：

```bash
git rm -r --cached <目录路径>
```

然后提交修改：

```bash
git add .gitignore
git commit -m "停止跟踪本地配置文件"
```

### 安全提醒

如果密码或密钥曾经被提交，即使后来删除文件，它仍可能存在于 Git 历史中。此时应立即：

1. 废弃并更换已经泄露的凭据；
2. 清理仓库历史；
3. 通知相关团队成员；
4. 检查远程平台是否提供密钥泄露告警。

仅把文件加入 `.gitignore` 并不能让已经泄露的密钥失效。

---

## 23. 查看和恢复误删提交

## 23.1 查看引用操作历史

```bash
git reflog
```

`reflog` 会记录本地分支指针和 `HEAD` 的移动历史，例如：

- commit；
- reset；
- rebase；
- 分支切换。

### 使用场景

误执行 `reset` 或 rebase 后，想寻找之前的提交 ID。

找到提交后，可以先创建一个恢复分支：

```bash
git branch recovery-branch <提交ID>
```

例如：

```bash
git branch recovery-branch a1b2c3d
```

这样比立刻执行另一次 `reset` 更安全。

### 注意

`reflog` 主要是本地记录，不是永久备份，也不会自动同步给其他开发者。

---

## 24. 常用的文件操作

## 24.1 使用 Git 移动或重命名文件

```bash
git mv 原路径 新路径
```

例如：

```bash
git mv OldService.java VideoService.java
```

## 24.2 使用 Git 删除文件

```bash
git rm <文件路径>
```

该操作会：

- 删除工作区文件；
- 将删除操作加入暂存区。

## 24.3 只停止跟踪但保留本地文件

```bash
git rm --cached <文件路径>
```

常用于本地配置文件已经被 Git 跟踪的情况。

---

## 25. 日常功能开发的推荐流程

以下是一套比较规范的开发流程。

## 25.1 开始开发前

```bash
git status
git switch main
git pull
git switch -c feature/video-comment
```

说明：

1. 确认工作区状态；
2. 切换到主分支；
3. 获取远程最新代码；
4. 从最新主分支创建功能分支。

## 25.2 开发过程中

随时查看状态和修改：

```bash
git status
git diff
```

完成一个相对独立的小功能后：

```bash
git add 相关文件
git diff --staged
git commit -m "实现视频评论发布接口"
```

## 25.3 推送功能分支

```bash
git push -u origin feature/video-comment
```

之后在 GitHub、GitLab 或 Gitee 创建 Pull Request / Merge Request。

## 25.4 合并后清理分支

```bash
git switch main
git pull
git branch -d feature/video-comment
```

如果远程分支没有被平台自动删除，可以在确认不再需要后执行：

```bash
git push origin --delete feature/video-comment
```

---

## 26. 紧急修复的推荐流程

假设正在功能分支开发，但临时收到一个紧急 Bug。

## 26.1 保存未完成工作

如果当前修改还不适合提交：

```bash
git status
git stash push -u -m "暂存未完成的视频评论功能"
```

## 26.2 从最新主分支创建修复分支

```bash
git switch main
git pull
git switch -c fix/video-like-count
```

## 26.3 完成修复并提交

```bash
git add 相关文件
git diff --staged
git commit -m "修复视频点赞计数不一致问题"
git push -u origin fix/video-like-count
```

创建 Pull Request / Merge Request 并完成合并。

## 26.4 恢复之前的开发

```bash
git switch feature/video-comment
git stash pop
```

恢复后应运行 `git status`，检查是否产生冲突。

---

## 27. 常见问题和处理方式

## 27.1 执行了 `git add .`，但不想全部提交

将所有内容移出暂存区：

```bash
git restore --staged .
```

然后重新添加真正需要提交的文件：

```bash
git add 文件1 文件2
git diff --staged
```

## 27.2 最近一次提交漏了一个文件

如果提交还没有推送：

```bash
git add 遗漏的文件
git commit --amend --no-edit
```

如果提交已经推送到共享分支，更稳妥的方式通常是再创建一次新提交。

## 27.3 提交说明写错了

尚未推送时：

```bash
git commit --amend -m "正确的提交说明"
```

已经推送到共享分支时，通常没有必要仅为提交说明强制改写历史。

## 27.4 想撤销某个已经推送的错误提交

```bash
git revert <提交ID>
git push
```

## 27.5 切换分支时 Git 提示本地修改会被覆盖

可以选择：

1. 完成并提交当前修改；
2. 使用 `git stash` 临时保存；
3. 确认修改无用后使用 `git restore` 丢弃。

不要为了切换分支直接使用高风险命令清空修改。

## 27.6 `git pull` 后出现冲突

```bash
git status
```

然后：

1. 打开冲突文件；
2. 理解双方修改；
3. 删除冲突标记并保留正确代码；
4. 执行 `git add`；
5. 完成 merge 或 rebase；
6. 运行测试。

## 27.7 推送被拒绝，提示远程包含本地没有的提交

通常说明其他人已经向远程分支推送了新提交。

先同步远程代码：

```bash
git pull --rebase
```

解决可能出现的冲突并完成测试后：

```bash
git push
```

不要一看到推送失败就执行 `git push --force`。

## 27.8 不小心执行了 `git reset --hard`

先停止继续进行大量 Git 操作，然后查看：

```bash
git reflog
```

找到操作前的提交 ID 后，先创建恢复分支：

```bash
git branch recovery-branch <提交ID>
```

需要注意：未提交、且没有被 stash 或其他工具保存的工作区修改，Git 不一定能够恢复。

## 27.9 Windows 和 Linux 换行符不同

Windows 常用 `CRLF`，Linux 常用 `LF`。换行符配置不一致时，可能出现“整个文件都被修改”的假象。

团队应通过 `.gitattributes` 统一规则，例如：

```gitattributes
* text=auto
*.java text eol=lf
*.xml text eol=lf
*.yml text eol=lf
*.yaml text eol=lf
*.sh text eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
```

不要在不了解团队约定时随意批量转换整个项目的换行符。

---

## 28. 危险指令清单

以下命令不是不能用，而是执行前必须理解其影响。

## 28.1 `git reset --hard`

```bash
git reset --hard <提交ID>
```

可能丢弃提交、暂存区修改和工作区修改。

## 28.2 `git clean -fd`

```bash
git clean -fd
```

会删除未被 Git 跟踪的文件和目录。先使用：

```bash
git clean -nd
```

预览删除内容。

## 28.3 `git push --force`

```bash
git push --force
```

可能覆盖其他人已经推送的提交。即使确实需要强制推送，也优先使用：

```bash
git push --force-with-lease
```

## 28.4 `git branch -D`

```bash
git branch -D <分支名>
```

会强制删除尚未合并的分支。

## 28.5 `git stash clear`

```bash
git stash clear
```

会删除所有 stash 记录。

### 执行危险命令前的检查清单

1. 当前位于哪个分支？
2. `git status` 显示了什么？
3. 是否有未提交修改？
4. 是否有其他人正在使用该远程分支？
5. 是否已经备份或创建临时分支？
6. 是否可以使用更安全的替代命令？

---

## 29. Git 提交规范建议

良好的提交应该具备以下特点：

- 一次提交只完成一个明确目标；
- 提交后项目能够编译，重要测试能够通过；
- 不混入无关格式化或临时调试代码；
- 提交说明准确描述“做了什么”；
- 不提交密码、密钥和个人配置。

## 29.1 Conventional Commits 示例

团队可以采用以下格式：

```text
<类型>(可选作用域): <简短说明>
```

常见类型：

| 类型 | 含义 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat(video): 增加视频评论接口` |
| `fix` | Bug 修复 | `fix(video): 修复点赞计数不一致` |
| `docs` | 文档修改 | `docs: 补充 Git 常用指令指南` |
| `refactor` | 重构 | `refactor(user): 拆分登录业务逻辑` |
| `test` | 测试 | `test(video): 补充点赞服务测试` |
| `chore` | 构建或辅助工作 | `chore: 更新 Maven 依赖` |
| `style` | 不影响逻辑的格式调整 | `style: 统一代码缩进` |
| `perf` | 性能优化 | `perf(video): 优化视频列表查询` |

是否使用英文类型并不是最重要的，关键是团队保持一致。

---

## 30. 常用命令速查表

| 使用场景 | 命令 |
|---|---|
| 初始化仓库 | `git init` |
| 克隆仓库 | `git clone <地址>` |
| 查看状态 | `git status` |
| 简洁查看状态 | `git status --short` |
| 查看未暂存修改 | `git diff` |
| 查看已暂存修改 | `git diff --staged` |
| 暂存指定文件 | `git add <文件>` |
| 暂存当前目录全部修改 | `git add .` |
| 交互式暂存 | `git add -p` |
| 提交代码 | `git commit -m "说明"` |
| 修改最近一次提交 | `git commit --amend` |
| 查看提交历史 | `git log --oneline` |
| 图形化查看分支历史 | `git log --oneline --graph --decorate --all` |
| 查看某次提交 | `git show <提交ID>` |
| 查看本地分支 | `git branch` |
| 创建并切换分支 | `git switch -c <分支名>` |
| 切换分支 | `git switch <分支名>` |
| 合并分支 | `git merge <分支名>` |
| 删除已合并本地分支 | `git branch -d <分支名>` |
| 查看远程仓库 | `git remote -v` |
| 获取远程状态 | `git fetch` |
| 拉取并合并远程代码 | `git pull` |
| 拉取并 rebase | `git pull --rebase` |
| 第一次推送分支 | `git push -u origin <分支名>` |
| 后续推送 | `git push` |
| 移出暂存区 | `git restore --staged <文件>` |
| 丢弃工作区修改 | `git restore <文件>` |
| 临时保存修改 | `git stash push -m "说明"` |
| 查看临时保存列表 | `git stash list` |
| 恢复并删除最近 stash | `git stash pop` |
| 安全撤销已提交修改 | `git revert <提交ID>` |
| 查看本地引用历史 | `git reflog` |
| 挑选某次提交 | `git cherry-pick <提交ID>` |
| 创建版本标签 | `git tag -a v1.0.0 -m "说明"` |

---

## 31. 初学者最应该养成的习惯

### 31.1 经常执行 `git status`

不确定仓库状态时，不要猜，先执行：

```bash
git status
```

### 31.2 提交前检查修改

```bash
git diff
git diff --staged
```

防止提交调试代码、临时文件、密码或无关修改。

### 31.3 小步提交

不要积累几天代码后一次性提交。一个提交只解决一个相对独立的问题，更容易：

- 代码评审；
- 定位 Bug；
- 撤销错误修改；
- 理解开发历史。

### 31.4 开发前同步主分支

从过期的主分支创建功能分支，会增加后续冲突概率。

```bash
git switch main
git pull
git switch -c feature/xxx
```

### 31.5 不直接在主分支开发

对于团队项目，应在功能分支上开发，通过 Pull Request 或 Merge Request 合并。

### 31.6 不提交敏感信息

特别注意：

- 数据库密码；
- JWT 密钥；
- 云服务密钥；
- Access Token；
- 私钥；
- 真实生产环境配置。

### 31.7 不盲目复制危险命令

看到 Git 报错时，应先理解：

- 当前分支是什么；
- 本地和远程分别有什么提交；
- 工作区是否存在修改；
- 想保留什么、丢弃什么。

然后再选择命令，而不是直接搜索并复制 `reset --hard` 或 `push --force`。

---

## 32. 一套适合初学者的记忆模型

日常开发首先记住下面这条主线：

```text
查看状态 → 查看修改 → 暂存修改 → 检查暂存区 → 提交 → 推送
```

对应命令：

```bash
git status
git diff
git add <相关文件>
git diff --staged
git commit -m "清晰的提交说明"
git push
```

开始新功能时记住：

```bash
git switch main
git pull
git switch -c feature/功能名
```

需要撤销时先判断修改处于哪个阶段：

```text
工作区修改          → git restore
已经进入暂存区      → git restore --staged
本地尚未共享的提交  → git reset 或 git commit --amend
已经推送的共享提交  → git revert
```

只要先判断修改所在区域，再选择命令，Git 操作就会清晰很多。

---

## 33. 总结

Git 的核心并不是背诵大量指令，而是理解以下几个概念：

1. 工作区、暂存区、本地仓库和远程仓库之间的关系；
2. `add`、`commit` 和 `push` 分别完成什么；
3. 分支是如何创建、切换和合并的；
4. `fetch`、`pull` 和 `push` 的区别；
5. 撤销操作是否会改写历史或丢失代码；
6. 共享分支上的历史不能随意修改；
7. 提交前检查、小步提交、功能分支开发是良好习惯。

对于初学者，优先熟练掌握以下命令即可覆盖大多数日常开发场景：

```bash
git status
git diff
git add
git commit
git log
git switch
git branch
git fetch
git pull
git push
git merge
git restore
git stash
git revert
```

遇到不确定的撤销操作时，先暂停操作并检查仓库状态。Git 中很多问题都可以恢复，但前提是不要在不了解影响的情况下连续执行多个高风险命令。
