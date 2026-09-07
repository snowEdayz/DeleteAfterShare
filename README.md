# Delete After Share

这是一个 Xposed Legacy 模块，针对本次提供的两个 APK 版本：

- `com.oplus.screenshot`
- `com.coloros.gallery3d`

## 行为

编辑截图后通过“分享”发送时，模块把编辑前原图 URI 放进截图应用发往图库的分享 Intent。图库进程在现有 `ShareInnerViewModel.m33828e0` 调用点，把原图对应的图库路径加入同一次待删除队列调用。

传给分享目标的真实选择集合保持不变，因此分享内容仍只有编辑后的图片。原图和编辑后的图片由图库现有的 `ShareUtils` 队列在同一时机处理，并继续使用图库的回收站逻辑。

## 安装

1. 在 Xposed Legacy 或兼容实现中安装构建出的 APK。
2. 对 `com.oplus.screenshot` 和 `com.coloros.gallery3d` 启用模块。
3. 重启这两个应用，必要时重启系统。

模块依赖反编译结果中的方法名和类名，当前基线对应本目录提供的 APK。系统更新后如果这些方法被重命名，需要重新检查反编译代码。

## 构建

本项目不要求本地构建。GitHub Actions 会在每次 push 时构建 Release APK，并更新固定的 `pre-release` prerelease tag；APK 会作为该 release 的 asset 上传并覆盖同名旧文件。

工作流使用 Xposed API 82 的 `compileOnly` 依赖，模块本身不把 Xposed API 打进 APK。
