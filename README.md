<p align="center">
  <img src="windows/assets/logo.png" width="120" alt="AutoClicker Logo">
</p>

<h1 align="center">AutoClicker 连点器</h1>

<p align="center">
  一款开箱即用的桌面连点器：连续点击 + 宏录制回放，免安装、无广告、开源。<br>
  作者 · <b>cui-huajin / yueryuyueryun</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/license-MIT-green.svg" alt="MIT License">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20Android%20%7C%20CLI-blue.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Python-3.10+-yellow.svg" alt="Python">
  <img src="https://img.shields.io/badge/UI-PySide6-8b5cf6.svg" alt="PySide6">
</p>

<p align="center">
  <img src="windows/assets/shot_continuous.png" width="300" alt="连续点击页">
  &nbsp;&nbsp;
  <img src="windows/assets/shot_macro.png" width="300" alt="宏回放页">
</p>

## 功能特性

- **连续点击** — 设定频率（毫秒/次）、鼠标按键（左/右/中）、单双击后自动连点，执行中可实时改次数。
- **宏录制回放** — 录下真实点击的位置、按键与间隔，之后原样回放；支持循环次数与两轮之间的循环间隔。
- **多存档管理** — 宏回放页的存档列表可视化展示全部存档（步数/时长），点选即加载；支持新建、覆盖、删除。
- **紧凑悬浮条** — 开始录制或执行后，窗口收缩为屏幕顶部的悬浮控制条并**永久置顶**，全屏游戏或新开窗都遮不住它；停止后展开还原。
- **灵动界面** — 无边框圆角卡片、浅色/深色双主题、水滴流转的分段切换与页转动画，全程无闪烁无残影。
- **免安装** — Windows 版为绿色文件夹，拷到任意 Windows 电脑双击即用，不需要 Python 环境。

## 三个可用版本

| 版本 | 目录 | 形态 | 适用场景 |
| ---- | ---- | ---- | -------- |
| **Windows 图形版** | [`windows/`](windows/) | PySide6 桌面应用，打包为免安装 exe | 日常使用，功能最全 |
| **命令行脚本版** | [`script/`](script/README.md) | 纯 Python 单文件，无 GUI | 服务器/远程桌面/自动化脚本串联 |
| **Android 版** | [`android/`](android/README.md) | Kotlin 无障碍服务 App 工程 | 手机端连点与宏回放，无需 root |

三个版本共用同一套宏存档 JSON 结构（`steps` / `gap` / `loops`），录制的存档可跨版本互换回放。

### Windows 图形版

全部源码与打包配置在 [`windows/`](windows/dist) 目录。

### 命令行脚本版

```bash
pip install pynput

python script/autoclicker_cli.py click --interval 100 --count 50     # 光标处连点 50 次
python script/autoclicker_cli.py record --out macro.json             # 录制，Ctrl+C 存盘
python script/autoclicker_cli.py replay macro.json --loops 10        # 回放 10 轮
```

详见 [`script/README.md`](script/README.md)。

### Android 版

基于无障碍服务（`AccessibilityService.dispatchGesture`）注入点击手势，**无需 root**。
用 Android Studio 打开 `android/` 目录编译安装后：开启无障碍服务 → 授予悬浮窗权限 → 悬浮控制条即可连点或回放宏。

详见 [`android/README.md`](android/README.md)。

## 宏存档格式

```json
{
  "name": "回放 1",
  "steps": [
    { "x": 960, "y": 540, "button": "left", "interval": 120 }
  ],
  "gap": 500,
  "loops": 3
}
```

- `interval`：该步点击完成后到下一步的等待毫秒数；
- `gap`：两轮回放之间的循环间隔毫秒数；
- `loops`：默认回放轮数，0 为无限。

## 从源码打包（Windows 版）

需 conda 环境（PySide6、pynput、pyinstaller）：

```bash
conda create -n autoclicker python=3.10
conda activate autoclicker
pip install pyside6 pynput pyinstaller

cd windows
python -m PyInstaller --distpath dist AutoClicker.spec
```

产物输出到 `windows/dist/AutoClicker/`，整个文件夹即交付物，请勿拆散目录结构。

## 目录结构

```
.
├─ windows/                                   Windows 图形版（源码 / 图标 / 打包配置 / 截图）
├─ script/                                    命令行脚本版（纯 Python）
├─ android/                                   Android 无障碍服务 App 工程（Kotlin）
├─ LICENSE                                    MIT 开源协议
└─ README.md                                  本文件
```

## 开源协议

本项目以 [MIT License](LICENSE) 开源，可自由使用、修改与分发，惟需保留版权声明。

---

<p align="center">
  <b>AutoClicker</b> · Made by cui-huajin / yueryuyueryun · 2026
</p>
