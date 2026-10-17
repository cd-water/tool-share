# Git 协作规范（GitHub Flow）

先记住三个词：

| 词 | 一句话解释 |
| --- | --- |
| 分支（branch） | 平行世界。`main` 是正式版，你在自己的分支上改，不影响别人 |
| 提交（commit） | 存档点。像游戏存档，随时可以回到过去 |
| 合并（merge） | 把分支上的改动并入 `main`，合并后再推送到远程 |

工作方式：**在自己的分支写代码 → 完成后本地合并进 `main` → 推送 `main` 到远程。**
不需要发 Pull Request。

下面按场景给出命令，**照着敲即可**（`#` 后面是注释，不用敲）。

---

## 场景 1：拿到任务，第一次开工

```bash
# 1. 切回 main 分支，拉取最新代码
git switch main
git pull

# 2. 从 main 创建自己的分支并切换过去（分支名找架构师/分工文档）
git switch -c feature/be-tool-rental

# 3. 正常写代码……
```

> 检查自己在哪个分支：`git branch`，当前分支前面带 `*`。

## 场景 2：开发中，随时保存进度

```bash
# 写完一部分代码，存档（建议每完成一小块就存一次）
git add src/                        # 把改动的文件加入暂存区（可写具体文件名）
git commit -m "feat: 租赁预约时段冲突校验"

# 推送到远程备份（首次推送用下面第一条，以后只敲 git push）
git push -u origin feature/be-tool-rental
git push
```

提交信息格式：**`类型: 中文描述`**

| 类型 | 什么时候用 |
| --- | --- |
| `feat` | 写了新功能 |
| `fix` | 修了 bug |
| `docs` | 只改了文档 |
| `refactor` | 重构，功能没变 |
| `chore` | 配置、脚本等杂项 |

## 场景 3：功能做完了，合并进 main 并推送

```bash
# 1. 先确保自己的改动都提交了
git status                          # 应显示 "nothing to commit"（没有红字文件名）

# 2. 切回 main，拉取最新代码（别人可能刚推过）
git switch main
git pull

# 3. 把自己的分支合并进 main
git merge feature/be-tool-rental

# 4. 推送 main 到远程
git push

# 5. 删除本地已完成的分支
git branch -d feature/be-tool-rental
```

合并成功后，下一个任务从场景 1 重新开始（起个新的分支名）。

> 如果第 3 步出现 `Merge made by the 'ort' strategy.` 等提示、没有报错，就是成功了。
> 如果出现 **冲突（conflict）**，见场景 5。

## 场景 4：刚推送完 main，发现漏提交了东西

```bash
git add <漏掉的文件>
git commit -m "fix: 补充 xxx"
git push
```

> 不要用 `--amend` 修改已推送的提交；正常追加一条提交即可。

## 场景 5：合并时提示冲突

通常是别人先改了同一个文件。处理步骤：

1. 终端里会列出冲突文件，打开它们，搜 `<<<<<<<`
2. 冲突区长这样，保留正确内容、删掉标记行：

```
<<<<<<< HEAD               ← main 上的版本
原来的代码
=======
你分支上的代码
>>>>>>> feature/be-tool-rental
```

3. 所有冲突都改完后：

```bash
git add <改过的文件>
git commit                  # 直接保存（信息用默认的即可）
git push
```

拿不准怎么取舍就把冲突内容截图发群里，别硬猜。

## 场景 6：切 main 时提示本地有未提交的改动

```bash
git stash                   # 把未完成的改动先收起来
git switch main
# ……处理别的事……
git switch feature/be-tool-rental
git stash pop               # 把改动放回来
```

---

## 救急命令

| 情况 | 命令 |
| --- | --- |
| 刚才的 commit 信息写错了，还没 push | `git commit --amend -m "正确的信息"` |
| 想撤销最近一次 commit，但保留改动的代码 | `git reset --soft HEAD~1` |
| 某个文件改乱了，想恢复成上次提交的样子 | `git restore <文件>`（⚠️ 改动不可恢复） |

## 四条红线（违反会影响所有人）

1. ❌ 不把没写完、没自测的代码合并进 `main` —— 合并前先跑通自己的功能
2. ❌ 不对 `main` 使用 `push -f`
3. ❌ 合并推送前**一定先** `git pull`，否则会被拒绝推送
4. ❌ 不提交 `logs/`、`node_modules/`、`.env`、`application-local.yml`、任何密钥
