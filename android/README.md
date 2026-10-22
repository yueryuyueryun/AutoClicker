# AutoClicker 安卓版

这是 AutoClicker 仓库的 **android/** 目录，为连点器的安卓版本（Windows 版在 `windows/`，命令行脚本版在 `script/`）。安卓版基于**无障碍服务（AccessibilityService）**实现点击注入，**无需 root**。

当前版本 **1.1.2**（`versionCode 2`），与电脑版 `v1.1.2` 同版本号，界面、配色、页转与宏结构都按电脑版对齐。本目录已附可直接安装的 [`AutoClicker.apk`](AutoClicker.apk)（debug 签名，1.5 MB）。

## 功能清单

- **单界面 + 分段切换**：「连续点击 / 宏回放」两页用分段控件切换，页间为 260ms `InOutCubic` 水平平移，与电脑版 `_slide_panels` 同款；卡片圆角、浅色/深色配色沿用同一套常量。
- **连续点击**：输入间隔（毫秒）与循环次数（0 = 无限），点「开始」后注入到当前前台应用。
- **宏录制 / 回放**：录制时全屏半透明捕获层记录每次按下的坐标与距上一步的毫秒间隔，点击会**穿透给下层应用**（既记录又能操作真实界面）；回放按记录间隔依次注入，支持循环次数与循环间隔（gap）。
- **多存档**：宏以 JSON 存进 SharedPreferences，结构与 Windows 版 `macros.json` 完全一致：
  `[{"name":..,"steps":[{"x":..,"y":..,"interval":..}],"gap":..,"loops":..}]`
  可新建、切换、删除多个存档，手机录的宏可以在电脑上回放（坐标是各设备自己的物理像素）。
- **悬浮胶囊**：执行与录制时主界面收起为可拖动的胶囊，含实时状态与「停止」；普通状态胶囊可直接开始。
- **页面适配**：两页内容各自套 `ScrollView`（`fillViewport = true`），空间富余时撑满、底部留白照旧，空间不足时整页滚动——小屏、横屏、分屏都不会裁掉下面的控件；存档列表高度按屏高自适应（`min(190dp, 屏高×0.28)`，不低于 `110dp`）。

## 安装与首次使用

直接安装本目录的 `AutoClicker.apk`（Android 7.0 / API 24 及以上）：

1. **开启无障碍**：设置 → 无障碍 → 已下载的服务 → AutoClicker → 打开。应用顶部有「开启无障碍服务」按钮可直达该页面（本服务不读取任何窗口内容，只用 `dispatchGesture` 注入手势）。
2. **授予悬浮窗**：允许「显示在其他应用上层」，否则执行/录制时悬浮胶囊弹不出来。
3. **开始使用**：连续点击直接调间隔后点「开始」；宏则先「开始录制」→ 点屏幕上的目标位置 →「停止录制」→（可保存为存档）→ 回放。

> 部分厂商系统（MIUI / EMUI / ColorOS / OriginOS 等）需要额外允许「后台弹出界面」「自启动」，否则切到游戏后服务会被冻结。

## 无需 root 的原理

Android 7.0（API 24）起，无障碍服务可以调用 `AccessibilityService.dispatchGesture()`，向系统注入合成触摸手势（`GestureDescription.StrokeDescription` 描述路径、起点时刻与时长）。系统把这些手势当作真实触摸派发给前台窗口，因此无需 root、无需 adb。本工程中每次点击是一个约 60ms 的点按手势，连续点击与宏回放由服务内的调度器按毫秒间隔注入。

## 构建步骤

需要 **JDK 17** 与 **Android SDK Platform 34 / Build-Tools 34.0.0**。

1. 在 `android/` 下新建 `local.properties`，写 SDK 路径（该文件已被 `.gitignore` 忽略）：
   ```properties
   sdk.dir=D:/tools/android/sdk
   ```
2. 构建：
   - **命令行（自带 wrapper）**：`gradlew.bat assembleDebug`（Windows）/ `./gradlew assembleDebug`；首次运行会自动下载 Gradle 8.7。
   - **本机已装 Gradle 8.7**：`gradle assembleDebug --console=plain --no-daemon`（设置 `JAVA_HOME`、`ANDROID_HOME`）。
   - **Android Studio**：`File → Open` 选本目录，按提示同步后直接 Run。
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`。
4. 快速验包：
   ```bash
   aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | head -3
   apksigner verify --print-certs app/build/outputs/apk/debug/app-debug.apk
   ```

发布正式版请自行配置签名（`Build → Generate Signed Bundle / APK`，或在 `app/build.gradle` 里加 `signingConfigs`）。仓库内 `AutoClicker.apk` 为 **debug 签名**，适合自用与侧载；它的证书与旧版 1.0 相同，可直接覆盖升级。

环境要求：`compileSdk 34`、`minSdk 24`（`dispatchGesture` 的最低要求）、`targetSdk 34`、AGP 8.1.4、Kotlin 1.9.22、Gradle 8.7。

## 使用限制与合规提醒

- 依赖无障碍权限注入，**系统重启后无障碍可能被清理**，需要重新开启；
- 部分厂商系统会清理后台服务，建议把本应用加入电池优化白名单；
- 带反作弊的联网手游（腾讯 ACE、部分竞技类游戏）可能检测到模拟点击；自动化工具通常违反游戏用户协议，**请先确认游戏规则**，账号风险由使用者自负；
- 坐标按物理像素记录，跨分辨率设备回放位置会有偏差，建议同一台设备录制与回放。

## 目录结构

```
android/
├── AutoClicker.apk                     # 已编好的安装包（v1.1.2，debug 签名）
├── build.gradle / settings.gradle / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
└── app/
    ├── build.gradle                    # versionCode 2 / versionName 1.1.2
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/xml/accessibility_service_config.xml
        ├── res/values/strings.xml
        ├── res/drawable-nodpi/         # logo 等图形资源
        └── java/com/yueryuyueryun/autoclicker/
            ├── MainActivity.kt         # 主界面：分段页、参数卡片、存档列表、弹窗
            ├── ClickerService.kt       # 无障碍服务：dispatchGesture 注入与调度（连点/宏回放）
            ├── OverlayController.kt    # 悬浮胶囊、准星选点层、录制捕获层（穿透）
            ├── MacroStore.kt           # SharedPreferences + org.json 宏存档
            └── UiKit.kt                # 主题/调色板、SegView、pillButton、CardDialog
```

## 更新记录

- **1.1.2**（`versionCode 2`）：页面改为可滚动（小屏/横屏/分屏不再裁切）、存档列表高度自适应、录制提示文案明确会穿透给下层应用、版本号与电脑版同步。
- **1.0**（`versionCode 1`）：首个可用版本，连点、宏录制回放、多存档、悬浮胶囊。
