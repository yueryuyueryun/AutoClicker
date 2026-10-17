# AutoClicker 脚本版

纯 Python 命令行连点器，无 GUI、免打包，Windows / macOS / Linux 通用。
与 Windows 图形版共用同一套宏存档 JSON 结构，两边录的存档可以互换回放。

## 安装

```bash
pip install pynput
```

Linux 下录制/回放需要 X11 会话（Wayland 受限于 pynput）。

## 用法

### 连续点击

在光标当前位置按固定间隔连点，`--count 0` 为无限，Ctrl+C 停止：

```bash
python autoclicker_cli.py click --interval 100 --count 50 --button left
```

### 录制宏

运行后你的每次真实点击（位置 / 按键 / 与上一步的间隔）都会被记录，
Ctrl+C 结束并写盘：

```bash
python autoclicker_cli.py record --out macro.json --gap 500 --loops 3
```

### 回放宏

按存档原样回放，`--loops` 可覆盖存档里的轮数：

```bash
python autoclicker_cli.py replay macro.json
python autoclicker_cli.py replay macro.json --loops 10
```

## 存档格式

```json
{
  "name": "macro.json",
  "steps": [
    { "x": 960, "y": 540, "button": "left", "interval": 120 }
  ],
  "gap": 500,
  "loops": 3
}
```

- `interval`：该步点击完成后到下一步的等待毫秒数（最后一步无意义）。
- `gap`：两轮回放之间的间隔毫秒数。
- `loops`：默认回放轮数，0 为无限。

## 参数速查

| 子命令 | 参数 | 说明 |
| ------ | ---- | ---- |
| click | `--interval` | 点击间隔毫秒，默认 100 |
| click | `--count` | 次数，0 无限，默认 0 |
| click | `--button` | left / right / middle |
| record | `--out` | 存档路径，默认 macro.json |
| record | `--gap` | 轮间隔毫秒，默认 500 |
| record | `--loops` | 默认轮数，0 无限，默认 1 |
| replay | `file` | 存档路径 |
| replay | `--loops` | 覆盖轮数，0 沿用存档 |
