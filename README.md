# Delete After Share

这是一个使用 Modern Xposed API 102 的模块，同时适配本次提供的新旧 APK 版本：

- `com.oplus.screenshot`
- `com.coloros.gallery3d`

其中 `-old`、`-new` 只是基线 APK 文件名后缀，运行时包名仍是上面两个包名，Xposed scope 不需要添加带后缀的名称。

## 行为

编辑截图后通过“分享”发送时，模块把编辑前原图 URI 放进截图应用发往图库的分享 Intent。图库进程在图库原有的删除入队调用点，把原图对应的图库路径加入同一次待删除队列调用。

传给分享目标的真实选择集合保持不变，因此分享内容仍只有编辑后的图片。原图和编辑后的图片由图库现有的 `ShareUtils` 队列在同一时机处理，并继续使用图库的回收站逻辑。

## 安装

1. 在支持 Modern Xposed API 的 LSPosed/兼容实现中安装构建出的 APK。
2. 对 `com.oplus.screenshot` 和 `com.coloros.gallery3d` 启用模块。
3. 重启这两个应用，必要时重启系统。

Modern Xposed API 102 的最低 Android 版本为 8.0（API 26）。

模块以当前提供的新旧 APK 为基线。图库新版本迁移了内部路径/数据模型类型，模块会根据运行时实际 DEX 中的类型图选择对应解析；截图新旧基线相同。系统更新或 APK 变化后，需要重新提供对应 APK 并检查反编译逻辑。

模块使用 DexKit 2.2.0 在目标 APK 的真实 DEX 定义中解析成员：方法同时匹配声明类、完整参数列表、返回值，以及必要的调用关系或字符串；字段同时匹配声明类、类型和访问关系。每个查询必须恰好得到一个结果，0 个或多个结果都会跳过对应 Hook，因此不会把 JADX 的阅读名称当作 Hook 名。代码中只有 Android 生命周期回调使用固定的公开方法名；厂商业务方法（包括混淆方法）统一通过 DexKit 查找后再 Hook。

`com.oplus.screenshot` 和 `com.coloros.gallery3d` 都属于功能链路的一部分，两个 APK 都必须在 Xposed scope 中启用。`ScreenShotShareActivity` 成功启动用户选择的目标 App 后，模块才会通知截图进程将 `EditorActivity` 所属任务标记为不显示在多任务界面并结束调用方 Activity；原图路径仍会加入图库现有队列，由图库自己的回收方法处理，回收成功回调作为跨进程清理兜底。

## 构建

本项目不要求本地构建。GitHub Actions 会在每次 push 时构建 Release APK，并更新固定的 `pre-release` prerelease tag；APK 会作为该 release 的 asset 上传并覆盖同名旧文件。

工作流使用 `io.github.libxposed:api:102.0.0` 的 `compileOnly` 依赖，模块本身不把 Xposed API 打进 APK。

模块入口和作用域分别声明在 `app/src/main/resources/META-INF/xposed/java_init.list`
和 `scope.list`，模块配置在 `module.prop`；不再使用 Legacy 的 `assets/xposed_init`
或 Manifest `xposed*` 元数据。
