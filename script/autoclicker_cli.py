# -*- coding: utf-8 -*-
# AutoClicker 脚本版 · 作者 cui-huajin / yueryuyueryun · MIT License
"""纯 Python 命令行连点器（无 GUI），Windows / macOS / Linux 均可运行。

子命令：
  click   在光标当前位置连续点击
  record  录制点击动作，存为 JSON 存档
  replay  按 JSON 存档原样回放

依赖：pip install pynput
停止：Ctrl+C（无限模式下唯一停止方式）
"""
import argparse
import json
import sys
import time

from pynput import mouse

BUTTONS = {
    "left": mouse.Button.left,
    "right": mouse.Button.right,
    "middle": mouse.Button.middle,
}


def _controller():
    return mouse.Controller()


def cmd_click(args):
    ctl = _controller()
    btn = BUTTONS[args.button]
    done = 0
    print(f"开始连点：间隔 {args.interval}ms，按键 {args.button}，"
          f"次数 {'无限' if args.count == 0 else args.count}，Ctrl+C 停止")
    try:
        while args.count == 0 or done < args.count:
            x, y = ctl.position
            ctl.click(btn, 1)
            done += 1
            if args.count == 0 or done < args.count:
                time.sleep(args.interval / 1000.0)
    except KeyboardInterrupt:
        pass
    print(f"已停止，共点击 {done} 次")


def cmd_record(args):
    steps = []
    last = [None]

    def on_click(x, y, button, press):
        if not press:
            return
        now = time.perf_counter()
        interval = 0 if last[0] is None else round((now - last[0]) * 1000)
        last[0] = now
        name = next((k for k, v in BUTTONS.items() if v == button), "left")
        steps.append({"x": int(x), "y": int(y), "button": name,
                      "interval": interval})
        print(f"  记录 #{len(steps)}: ({x}, {y}) {name} +{interval}ms")

    print(f"录制中：你的每次真实点击都会被记录，Ctrl+C 结束并保存到 {args.out}")
    listener = mouse.Listener(on_click=on_click)
    listener.start()
    try:
        while listener.is_alive():
            time.sleep(0.1)
    except KeyboardInterrupt:
        pass
    listener.stop()
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"name": args.out, "steps": steps,
                   "gap": args.gap, "loops": args.loops}, f,
                  ensure_ascii=False, indent=2)
    print(f"已保存 {len(steps)} 步到 {args.out}")


def cmd_replay(args):
    with open(args.file, encoding="utf-8") as f:
        data = json.load(f)
    steps = data.get("steps", [])
    if not steps:
        print("存档里没有动作，退出")
        return 1
    loops = args.loops if args.loops > 0 else data.get("loops", 1)
    gap = data.get("gap", 0)
    ctl = _controller()
    print(f"回放 {args.file}：{len(steps)} 步 × "
          f"{'无限' if loops == 0 else loops} 轮，Ctrl+C 停止")
    done = 0
    try:
        while loops == 0 or done < loops:
            for i, st in enumerate(steps):
                btn = BUTTONS.get(st.get("button", "left"), mouse.Button.left)
                ctl.position = (st["x"], st["y"])
                ctl.click(btn, 1)
                if i < len(steps) - 1:
                    time.sleep(st.get("interval", 0) / 1000.0)
            done += 1
            if loops == 0 or done < loops:
                time.sleep(gap / 1000.0)
    except KeyboardInterrupt:
        pass
    print(f"已停止，共回放 {done} 轮")
    return 0


def main():
    p = argparse.ArgumentParser(prog="autoclicker",
                                description="纯 Python 命令行连点器")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("click", help="在光标当前位置连续点击")
    c.add_argument("--interval", type=float, default=100,
                   help="点击间隔，毫秒（默认 100）")
    c.add_argument("--count", type=int, default=0,
                   help="点击次数，0 为无限（默认 0）")
    c.add_argument("--button", choices=sorted(BUTTONS), default="left",
                   help="鼠标按键（默认 left）")
    c.set_defaults(func=cmd_click)

    r = sub.add_parser("record", help="录制点击动作到 JSON 存档")
    r.add_argument("--out", default="macro.json", help="存档路径")
    r.add_argument("--gap", type=int, default=500,
                   help="回放时两轮之间的间隔，毫秒（默认 500）")
    r.add_argument("--loops", type=int, default=1,
                   help="回放默认轮数，0 为无限（默认 1）")
    r.set_defaults(func=cmd_record)

    pl = sub.add_parser("replay", help="按 JSON 存档回放")
    pl.add_argument("file", help="record 生成的 JSON 存档")
    pl.add_argument("--loops", type=int, default=0,
                   help="覆盖存档轮数，0 表示沿用存档（默认 0）")
    pl.set_defaults(func=cmd_replay)

    args = p.parse_args()
    sys.exit(args.func(args) or 0)


if __name__ == "__main__":
    main()
