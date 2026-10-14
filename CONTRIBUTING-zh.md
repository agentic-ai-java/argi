## 如何贡献

## 感谢你为 ARGI 贡献！

ARGI 从开源建设以来，受到了很多社区同学的关注。社区的每一个 Issue ，每一个 PR，都是对整个项目的帮助，都在为建设更好用的 ARGI 添砖加瓦。

我们真心地感谢为这个项目提出过 Issue 和 PR 的开发者。我们希望有更多社区的开发者加入进来，一起把项目做好。

## 贡献流程

在贡献代码之前，请您稍微花一些时间了解为 ARGI 贡献代码的流程。

### 贡献什么？

我们随时都欢迎任何贡献，无论是简单的错别字修正，BUG 修复还是增加新功能。请踊跃提出问题或发起 PR。我们同样重视文档以及与其它开源项目的整合，欢迎在这方面做出贡献。

如果是一个比较复杂的修改，建议先在 Issue 中添加一个 Feature 标识，并简单描述一下设计和修改点。

### 从哪里入手？

如果您是初次贡献，可以先从 [good first issue](https://github.com/agentic-ai-java/argi/labels/good%20first%20issue) 和 [help wanted](https://github.com/agentic-ai-java/argi/labels/help%20wanted) 中认领一个比较简单的任务。

### Fork 仓库，并将其 Clone 到本地

- 点击 [本项目](https://github.com/agentic-ai-java/argi) 右上角的 `Fork` 图标 将 agentic-ai-java/argi  fork 到自己的空间。
- 将自己账号下的 argi 仓库 clone 到本地，例如我的账号是 `chickenlj`，那就是执行 `git clone https://github.com/chickenlj/argi.git` 进行 clone 操作。

### 配置 Github 信息

- 在自己的机器执行 `git config --list` ，查看 git 的全局用户名和邮箱。
- 检查显示的 user.name 和 user.email 是不是与自己 github 的用户名和邮箱相匹配。
- 如果公司内部有自己的 gitlab 或者使用了其他商业化的 gitlab，则可能会出现不匹配的情况。这时候，你需要为 argi 项目单独设置用户名和邮箱。
- 设置用户名和邮箱的方式请参考 github 官方文档，[设置用户名](https://help.github.com/articles/setting-your-username-in-git/#setting-your-git-username-for-a-single-repository)，[设置邮箱](https://help.github.com/articles/setting-your-commit-email-address-in-git/)。

### Merge 最新代码

fork 出来的代码后，原仓库 main 分支可能出现了新的提交，这时候为了避免提交的 PR 和 Main 中的提交出现冲突，需要及时 merge main 分支。

- 在你本机的 argi 目录下，执行 `git remote add upstream https://github.com/agentic-ai-java/argi` 将原始仓库地址配置为 upstream。
- 在你本机的 argi 目录下，执行 `git fetch upstream` 从 upstream 拉取更新。
- 在你本机的 argi 目录下，执行 `git checkout main` 切换到 main 分支。
- 在你本机的 argi 目录下，执行 `git rebase upstream/main` rebase 最新代码。

### 配置代码格式

开发前请在 IDE 中配置本仓库的 Java 格式规范，并在提交代码前运行格式与 Checkstyle 检查。

### 开发

开发自己的功能，**开发完毕后建议使用 `mvn clean package` 命令确保修改后的代码能在本地编译通过。执行该命令的同时还能以 spring 的方式自动格式化代码**。然后再提交代码，提交代码之前请注意创建一个新的有关本特性的分支，用该分支进行代码提交。

### 本地CI

本地开发完成后，强烈建议在提交 PR 之前执行项目`tools\make`提供的 `make` 命令进行本地持续集成（CI）检查，以确保代码符合项目的标准和规范。如果对于本地CI有任何疑问，可以在控制台输入 `make help` 了解具体信息。

### 本地Checkstyle

为了减少一些不必要的代码风格问题，ARGI 提供了本地 Checkstyle 检查功能。可以在项目根目录下执行 `mvn checkstyle:check` 命令来检查代码风格是否符合规范。

### 删除未使用的导入

为了确保代码的整洁，请删除 Java 文件中未使用的导入。可以通过执行 `mvn spotless:apply` 命令来自动删除未使用的导入。

### 提交最新代码

提交信息和 PR 标题使用 `type(scope): description` 格式，`scope` 可省略。常用类型包括 `fix`、`feat`、`refactor`、`docs`、`chore`、`perf`、`infra`、`revert`、`release`、`test` 和 `style`。

提交或更新 PR 时不运行 GitHub Actions 检查，也不自动发送欢迎消息或添加标签。审核通过并合并到 `main` 后，CI 通过 `push` 事件运行构建、测试、Lint、许可证和密钥检查；直接推送到 `main` 也会触发这些检查。审核要求由仓库分支保护或规则集管理。

Commit 规范: git commit -m "类型(模块): 空格 符合规范的提交信息",例如 `feat(docs): contribute-zh 更新` 

### Merge 最新代码

- 同样，提交 PR 前，需要 rebase main 分支的代码（如果您的目标分支不是 main 分支，则需要 rebase 对应目标分支），具体操作步骤请参考之前的章节。
- 如果出现冲突，需要先解决冲突。

### 提交 PR

提交 PR，根据 `Pull request template` 写明修改点和实现的功能，等待 code review 和 合并，成为 ARGI Contributor，为更好用的 ARGI 做出贡献。
