# -*- coding: utf-8 -*-
"""AutoClicker — 桌面连点器（PySide6 + pynput）

支持两种模式：
- 连续点击：按固定频率点击光标所在位置
- 宏回放：录制点击动作（位置/顺序/次数/间隔）后按录入参数自动回放
"""
import sys
import os
import json
import time
import math
import shutil
import ctypes
from ctypes import wintypes
from threading import Thread, Event
from collections import deque

from PySide6.QtCore import (
    Qt, Signal, QTimer, QEvent, QPoint, QPointF, QRect, QSize,
    QPropertyAnimation, QEasingCurve, QParallelAnimationGroup,
)
from PySide6.QtGui import (
    QFont, QColor, QIcon, QGuiApplication, QPainter,
    QRadialGradient,
)
from PySide6.QtWidgets import (
    QApplication, QWidget, QLabel, QPushButton, QDoubleSpinBox, QSpinBox,
    QComboBox, QVBoxLayout, QHBoxLayout, QButtonGroup,
    QGraphicsDropShadowEffect, QAbstractSpinBox, QLineEdit, QTableWidget,
    QTableWidgetItem,
    QScrollArea, QDialog, QMessageBox,
)
from pynput import mouse

import icon_rc  # noqa: F401  注册 :/app.ico 资源

_user32 = ctypes.windll.user32
# HWND_TOPMOST / HWND_NOTOPMOST 是指针宽度的 -1 / -2，必须按 64 位 HWND 传参；
# 无 argtypes 时按 32 位 int 传入，x64 下高位未定义，置顶会静默失效
_user32.SetWindowPos.argtypes = [wintypes.HWND, wintypes.HWND, ctypes.c_int,
                                 ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                 wintypes.UINT]
_user32.SetWindowPos.restype = wintypes.BOOL

MACRO_H = 648
CONT_H = MACRO_H  # 两页同高：切换时窗口高度不变，页转只做纯水平移动
PILL_H = 72
PILL_W_EXEC = 384
PILL_W_RECORD = 208

BTN_NAMES = {mouse.Button.left: "左", mouse.Button.right: "右", mouse.Button.middle: "中"}
BTN_FROM_NAME = {"左": mouse.Button.left, "右": mouse.Button.right, "中": mouse.Button.middle}

# 水波柔光颜色（随主题切换），_Ripple 绘制时读取
RIPPLE = [150, 160, 255]

PALETTES = {
    "dark": {
        "card": "#1b1b26", "card_border": "#2e2e3e",
        "text": "#e8e8f0", "dim": "#8a8aa0",
        "field": "#242432", "field_border": "#34344a", "field_text": "#f0f0f8",
        "hover": "#2a2a3a", "row_hover": "#232332",
        "table_bg": "#20202c", "grid": "#2e2e3e", "list_bg": "#14141c",
        "shadow": (0, 0, 0, 140), "ripple": (150, 160, 255),
    },
    "light": {
        "card": "#f6f6fb", "card_border": "#dfdfea",
        "text": "#25253c", "dim": "#71718c",
        "field": "#ffffff", "field_border": "#d9d9e6", "field_text": "#2b2b42",
        "hover": "#e9e9f4", "row_hover": "#e6e6f2",
        "table_bg": "#fdfdff", "grid": "#e7e7f1", "list_bg": "#eeeef6",
        "shadow": (110, 110, 160, 90), "ripple": (90, 110, 240),
    },
}

STYLE = """
QWidget#card {
    background-color: %(card)s;
    border: 1px solid %(card_border)s;
    border-radius: 18px;
}
QLabel { color: %(text)s; background: transparent; border: none; }
QLabel#title { font-size: 15px; font-weight: 600; }
QLabel#section { color: %(dim)s; font-size: 12px; }
QLabel#fieldLabel { color: %(text)s; font-size: 13px; }
QLabel#unitLabel { color: %(dim)s; font-size: 13px; }
QLabel#logo {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #5b7cfa, stop:1 #8a5bfa);
    border-radius: 5px;
}
QPushButton#logoBtn {
    background: transparent; border: none; border-radius: 6px;
}
QPushButton#logoBtn:hover { background: %(hover)s; }
QPushButton#winBtn {
    background: transparent; border: none; border-radius: 8px;
    color: %(dim)s; font-size: 14px;
}
QPushButton#winBtn:hover { background: %(hover)s; color: %(text)s; }
QPushButton#winBtn[close="true"]:hover { background: #ff5f56; color: white; }

QComboBox {
    background: %(field)s; color: %(field_text)s;
    border: 1px solid %(field_border)s; border-radius: 9px;
    padding: 7px 12px; font-size: 13px;
}
QComboBox:focus { border: 1px solid #5b7cfa; }
QComboBox::drop-down { border: none; width: 26px; }
QComboBox QAbstractItemView {
    background: %(field)s; color: %(field_text)s;
    border: 1px solid %(field_border)s; border-radius: 8px;
    selection-background-color: %(hover)s; outline: none;
    padding: 4px;
}
QWidget#spinFrame {
    background: %(field)s; border: 1px solid %(field_border)s; border-radius: 9px;
}
QWidget#innerSpin {
    background: %(list_bg)s; border: 1px solid %(field_border)s; border-radius: 7px;
}
QDoubleSpinBox, QSpinBox {
    background: transparent; color: %(field_text)s; font-size: 14px;
}
QDoubleSpinBox QLineEdit, QSpinBox QLineEdit {
    color: %(field_text)s; background: transparent; border: none;
    padding: 7px 12px;
}

QWidget#seg {
    background: %(field)s; border: 1px solid %(field_border)s; border-radius: 10px;
}
QPushButton#segBtn {
    border: none; background: transparent; color: %(dim)s;
    padding: 7px 0; border-radius: 8px; font-size: 12px;
}
QPushButton#segBtn:hover { color: %(text)s; }
QPushButton#segBtn:checked {
    color: white; font-weight: 600;
}
QWidget#segIndicator {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:1, stop:0 #5b7cfa, stop:1 #7a5bfa);
    border-radius: 9px;
}

QPushButton#startBtn {
    border: none; border-radius: 12px; padding: 14px;
    font-size: 15px; font-weight: 600; color: white;
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #5b7cfa, stop:1 #8a5bfa);
}
QPushButton#startBtn:hover { background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #6b8cff, stop:1 #9a6bff); }
QPushButton#startBtn[running="true"] {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #fa5b6e, stop:1 #fa8a5b);
}
QPushButton#startBtn[running="true"]:hover {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #ff6b7e, stop:1 #ff9a6b);
}

QPushButton#recordBtn {
    border: none; border-radius: 10px; padding: 9px 14px;
    font-size: 13px; font-weight: 600; color: white;
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #22c1a3, stop:1 #5b7cfa);
}
QPushButton#recordBtn:hover { background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #32d1b3, stop:1 #6b8cff); }
QPushButton#recordBtn[recording="true"] {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #fa5b6e, stop:1 #fa8a5b);
}
QPushButton#ghostBtn {
    background: %(field)s; border: 1px solid %(field_border)s; border-radius: 10px;
    color: %(dim)s; padding: 9px 14px; font-size: 13px;
}
QPushButton#ghostBtn:hover { color: %(text)s; border: 1px solid #5b7cfa; }

QPushButton#pillBtn {
    border: none; border-radius: 9px; padding: 7px 12px;
    font-size: 12px; font-weight: 600; color: white;
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #5b7cfa, stop:1 #8a5bfa);
}
QPushButton#pillBtn:hover { background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #6b8cff, stop:1 #9a6bff); }
QPushButton#pillBtn[danger="true"] {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #fa5b6e, stop:1 #fa8a5b);
}
QPushButton#pillBtn[danger="true"]:hover {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #ff6b7e, stop:1 #ff9a6b);
}
QPushButton#pillBtn:disabled { background: %(hover)s; color: %(dim)s; }
QPushButton#pillGhost {
    background: %(field)s; border: 1px solid %(field_border)s; border-radius: 9px;
    color: %(dim)s; padding: 7px 12px; font-size: 12px;
}
QPushButton#pillGhost:hover { color: %(text)s; border: 1px solid #5b7cfa; }
QPushButton#pillGhost:disabled { color: %(dim)s; }

QTableWidget {
    background: %(table_bg)s; border: 1px solid %(field_border)s; border-radius: 10px;
    gridline-color: %(grid)s; color: %(text)s; font-size: 12px;
    selection-background-color: %(hover)s; outline: none;
}
QTableWidget::item { padding: 3px 6px; }
QHeaderView::section {
    background: transparent; color: %(dim)s; border: none;
    border-bottom: 1px solid %(field_border)s; padding: 6px 6px; font-size: 12px;
}
QTableWidget QTableCornerButton::section { background: transparent; border: none; }
QTableWidget QLineEdit { background: %(card)s; color: %(field_text)s; border: 1px solid #5b7cfa; }

QScrollArea#slotScroll {
    background: %(list_bg)s; border: 1px solid %(field_border)s; border-radius: 12px;
}
QWidget#slotHost { background: transparent; }
QWidget#slotRow { background: transparent; border-radius: 10px; }
QWidget#slotRow:hover { background: %(row_hover)s; }
QWidget#slotRow[selected="true"] {
    background: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #5b7cfa, stop:1 #8a5bfa);
}
QLabel#slotName { font-size: 13px; font-weight: 600; color: %(text)s; }
QLabel#slotMeta { font-size: 11px; color: %(dim)s; }
QWidget#slotRow[selected="true"] QLabel#slotName { color: #ffffff; }
QWidget#slotRow[selected="true"] QLabel#slotMeta { color: #e4e4ff; }
QLabel#slotEmpty { color: %(dim)s; font-size: 12px; padding: 20px 0; }
QScrollArea#slotScroll QScrollBar:vertical {
    background: transparent; width: 10px; margin: 4px 2px;
}
QScrollArea#slotScroll QScrollBar::handle:vertical {
    background: %(field_border)s; border-radius: 4px; min-height: 24px;
}
QScrollArea#slotScroll QScrollBar::add-line:vertical,
QScrollArea#slotScroll QScrollBar::sub-line:vertical { height: 0; }

QDialog { background: transparent; }
QLineEdit {
    background: %(field)s; color: %(field_text)s;
    border: 1px solid %(field_border)s; border-radius: 9px;
    padding: 8px 12px; font-size: 13px; selection-background-color: #5b7cfa;
}
QLineEdit:focus { border: 1px solid #5b7cfa; }
QLabel#dlgHint { color: %(dim)s; font-size: 12px; }
"""


def style_for(pal):
    return STYLE % pal


def repolish(widget):
    widget.style().unpolish(widget)
    widget.style().polish(widget)


class _Ripple(QWidget):
    """水波：自点击处扩散并淡出的一圈柔光。"""

    def __init__(self, parent):
        super().__init__(parent)
        self.setAttribute(Qt.WA_TransparentForMouseEvents)
        self.center = QPoint()
        self._p = 0.0
        self._timer = QTimer(self)
        self._timer.setInterval(16)
        self._timer.timeout.connect(self._tick)

    def start(self):
        self._t0 = time.perf_counter()
        self._timer.start()

    def _tick(self):
        self._p = min((time.perf_counter() - self._t0) / 0.45, 1.0)
        self.update()
        if self._p >= 1.0:
            self._timer.stop()
            self.deleteLater()

    def paintEvent(self, event):
        if self._p <= 0.0:
            return
        pr = QPainter(self)
        pr.setRenderHint(QPainter.Antialiasing)
        radius = max(1.0, self._p * max(self.width(), self.height()) * 0.7)
        grad = QRadialGradient(QPointF(self.center), radius)
        a = int(110 * (1.0 - self._p))
        r, g, b = RIPPLE
        grad.setColorAt(0.0, QColor(r, g, b, a))
        grad.setColorAt(0.7, QColor(r, g, b, a // 2))
        grad.setColorAt(1.0, QColor(r, g, b, 0))
        pr.setPen(Qt.NoPen)
        pr.setBrush(grad)
        pr.drawEllipse(QPointF(self.center), radius, radius)
        pr.end()


class SegRow(QWidget):
    """分段按钮行：选中高亮块以水波+形变拉伸从旧按钮流转到新按钮，不瞬跳。"""

    def __init__(self, options, checked_idx):
        super().__init__(objectName="seg")
        self.setFixedHeight(36)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(3, 3, 3, 3)
        lay.setSpacing(3)
        self.group = QButtonGroup(self)
        self.group.setExclusive(True)
        self.indicator = QWidget(self)
        self.indicator.setObjectName("segIndicator")
        self.indicator.setAttribute(Qt.WA_TransparentForMouseEvents)
        for i, text in enumerate(options):
            b = QPushButton(text)
            b.setObjectName("segBtn")
            b.setCheckable(True)
            b.setCursor(Qt.PointingHandCursor)
            if i == checked_idx:
                b.setChecked(True)
            self.group.addButton(b, i)
            lay.addWidget(b)
            b.clicked.connect(lambda _=False, btn=b: self._on_click(btn))
        self.indicator.lower()
        self._start = None
        self._end = None
        self._t0 = 0.0
        self._anim = QTimer(self)
        self._anim.setInterval(16)
        self._anim.timeout.connect(self._anim_tick)

    def showEvent(self, ev):
        super().showEvent(ev)
        if self.indicator.width() == 0:
            btn = self.group.checkedButton()
            if btn is not None:
                self.indicator.setGeometry(btn.geometry())

    def resizeEvent(self, ev):
        super().resizeEvent(ev)
        # 布局在 resize 之后才结算按钮几何，延一拍对齐高亮块
        QTimer.singleShot(0, self._sync_indicator)

    def _sync_indicator(self):
        anim = getattr(self, "_anim", None)
        if anim is not None and anim.isActive():
            return
        btn = self.group.checkedButton()
        if btn is not None and btn.geometry() != self.indicator.geometry():
            self.indicator.setGeometry(btn.geometry())

    def _on_click(self, btn):
        self._start = self.indicator.geometry()
        self._end = btn.geometry()
        self._t0 = time.perf_counter()
        self._anim.start()

    def _anim_tick(self):
        t = min((time.perf_counter() - self._t0) / 0.26, 1.0)
        p = QEasingCurve(QEasingCurve.Type.InOutCubic).valueForProgress(t)
        # 流转途中横向鼓胀再收回，模拟水波形变
        bulge = int(abs(self._end.x() - self._start.x()) * 0.45 * math.sin(math.pi * p))
        x = int(self._start.x() + (self._end.x() - self._start.x()) * p) - bulge // 2
        w = int(self._start.width() + (self._end.width() - self._start.width()) * p) + bulge
        self.indicator.setGeometry(x, self._end.y(), w, self._end.height())
        if t >= 1.0:
            self._anim.stop()
            self.indicator.setGeometry(self._end)


class SlotRow(QWidget):
    """存档行：名称 + 步数/时长摘要；点击带水波并触发选中。"""
    clicked = Signal(int)

    def __init__(self, idx, name, meta, selected):
        super().__init__()
        self.setObjectName("slotRow")
        self.idx = idx
        self.setAttribute(Qt.WA_StyledBackground, True)
        self.setProperty("selected", selected)
        self.setFixedHeight(52)
        self.setCursor(Qt.PointingHandCursor)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 0, 14, 0)
        lay.setSpacing(8)
        name_lab = QLabel(name)
        name_lab.setObjectName("slotName")
        meta_lab = QLabel(meta)
        meta_lab.setObjectName("slotMeta")
        lay.addWidget(name_lab)
        lay.addStretch()
        lay.addWidget(meta_lab)

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            rip = _Ripple(self)
            rip.resize(self.size())
            rip.center = event.position().toPoint()
            rip.show()
            rip.start()
            self.clicked.emit(self.idx)
        super().mousePressEvent(event)


class SlotList(QScrollArea):
    """回放存档可视化列表：自绘行，规避 QListWidget 样式表布局缓存错乱。"""
    rowClicked = Signal(int)

    def __init__(self):
        super().__init__()
        self.setObjectName("slotScroll")
        self.setFrameShape(QScrollArea.NoFrame)
        self.setWidgetResizable(True)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self._host = QWidget()
        self._host.setObjectName("slotHost")
        self._lay = QVBoxLayout(self._host)
        self._lay.setContentsMargins(5, 5, 5, 5)
        self._lay.setSpacing(4)
        self._lay.addStretch(1)
        self.setWidget(self._host)

    def set_slots(self, slots, selected):
        while self._lay.count():
            item = self._lay.takeAt(0)
            if item.widget() is not None:
                item.widget().deleteLater()
        if not slots:
            empty = QLabel("暂无存档 · 点「新建存档」创建第一个回放")
            empty.setObjectName("slotEmpty")
            empty.setAlignment(Qt.AlignCenter)
            self._lay.addWidget(empty)
        for i, s in enumerate(slots):
            steps = s.get("steps", [])
            secs = sum(st.get("interval", 0) for st in steps) / 1000.0
            meta = f"{len(steps)} 步" + (f" · {secs:.1f}s" if secs else "")
            row = SlotRow(i, s["name"], meta, i == selected)
            row.clicked.connect(self.rowClicked)
            self._lay.addWidget(row)
        self._lay.addStretch(1)

    def set_selected(self, idx):
        for i in range(self._lay.count()):
            w = self._lay.itemAt(i).widget()
            if isinstance(w, SlotRow):
                w.setProperty("selected", w.idx == idx)
                repolish(w)
                for lab in w.findChildren(QLabel):
                    repolish(lab)


class PromptDialog(QDialog):
    """与应用同风格的无边框卡片弹窗：命名输入 / 删除确认共用。"""

    def __init__(self, parent, title, message, default="",
                 prompt=True, confirm_text="确认", danger=False, alert=False):
        super().__init__(parent)
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Dialog)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setFixedWidth(300)
        self._closing = False
        self._anim = None

        card = QWidget(self)
        card.setObjectName("card")
        shadow = QGraphicsDropShadowEffect(card)
        shadow.setBlurRadius(30)
        shadow.setOffset(0, 8)
        shadow.setColor(QColor(*parent.pal["shadow"]))
        card.setGraphicsEffect(shadow)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 16, 16, 22)
        outer.addWidget(card)

        lay = QVBoxLayout(card)
        lay.setContentsMargins(18, 14, 18, 16)
        lay.setSpacing(12)

        head = QHBoxLayout()
        head.setSpacing(8)
        logo = QLabel()
        logo.setObjectName("logo")
        logo.setFixedSize(10, 10)
        ttl = QLabel(title)
        ttl.setObjectName("title")
        close = QPushButton("✕")
        close.setObjectName("winBtn")
        close.setProperty("close", True)
        close.setFixedSize(26, 26)
        close.clicked.connect(self.reject)
        head.addWidget(logo)
        head.addWidget(ttl)
        head.addStretch(1)
        head.addWidget(close)
        lay.addLayout(head)

        hint = QLabel(message)
        hint.setObjectName("dlgHint")
        hint.setWordWrap(True)
        lay.addWidget(hint)

        self.field = None
        if prompt:
            self.field = QLineEdit(default)
            self.field.setFixedHeight(36)
            self.field.returnPressed.connect(self.accept)
            lay.addWidget(self.field)

        row = QHBoxLayout()
        row.setSpacing(10)
        row.addStretch(1)
        if not alert:
            cancel = QPushButton("取消")
            cancel.setObjectName("ghostBtn")
            cancel.clicked.connect(self.reject)
            row.addWidget(cancel)
        ok = QPushButton(confirm_text)
        ok.setObjectName("pillBtn")
        if danger:
            ok.setProperty("danger", True)
        ok.clicked.connect(self.accept)
        ok.setDefault(True)
        row.addWidget(ok)
        lay.addLayout(row)

        if self.field is not None:
            self.field.setFocus()
            self.field.selectAll()
        else:
            ok.setFocus()

    def showEvent(self, event):
        super().showEvent(event)
        self.setWindowOpacity(0.0)
        self._anim = QPropertyAnimation(self, b"windowOpacity", self)
        self._anim.setDuration(160)
        self._anim.setStartValue(0.0)
        self._anim.setEndValue(1.0)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self._anim.start()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape:
            self.reject()
        else:
            super().keyPressEvent(event)

    def accept(self):
        self._fade_close(QDialog.Accepted)

    def reject(self):
        self._fade_close(QDialog.Rejected)

    def _fade_close(self, code):
        if self._closing:
            return
        self._closing = True
        self._anim = QPropertyAnimation(self, b"windowOpacity", self)
        self._anim.setDuration(120)
        self._anim.setStartValue(self.windowOpacity())
        self._anim.setEndValue(0.0)
        self._anim.setEasingCurve(QEasingCurve.InCubic)
        self._anim.finished.connect(lambda: QDialog.done(self, code))
        self._anim.start()

    def popup(self):
        p = self.parentWidget()
        self.adjustSize()
        if p is not None:
            g = p.geometry()
            self.move(g.center().x() - self.width() // 2,
                      g.center().y() - self.height() // 2)
        return self.exec()


class AboutDialog(QDialog):
    """关于卡片：Logo、版本、作者署名与开源地址。"""

    VERSION = "1.0.0"

    def __init__(self, parent):
        super().__init__(parent)
        self.setWindowFlags(Qt.FramelessWindowHint | Qt.Dialog)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setFixedWidth(320)
        self._closing = False
        self._anim = None

        card = QWidget(self)
        card.setObjectName("card")
        shadow = QGraphicsDropShadowEffect(card)
        shadow.setBlurRadius(30)
        shadow.setOffset(0, 8)
        shadow.setColor(QColor(*parent.pal["shadow"]))
        card.setGraphicsEffect(shadow)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 16, 16, 22)
        outer.addWidget(card)

        lay = QVBoxLayout(card)
        lay.setContentsMargins(22, 20, 22, 18)
        lay.setSpacing(8)

        logo = QLabel()
        logo.setPixmap(QIcon(":/assets/logo128.png").pixmap(56, 56))
        logo.setAlignment(Qt.AlignCenter)
        lay.addWidget(logo)
        lay.addSpacing(2)
        ttl = QLabel("AutoClicker 连点器")
        ttl.setObjectName("title")
        ttl.setAlignment(Qt.AlignCenter)
        lay.addWidget(ttl)
        ver = QLabel("版本 " + self.VERSION)
        ver.setObjectName("dlgHint")
        ver.setAlignment(Qt.AlignCenter)
        lay.addWidget(ver)
        author = QLabel("作者 · cui-huajin / yueryuyueryun")
        author.setObjectName("dlgHint")
        author.setAlignment(Qt.AlignCenter)
        lay.addWidget(author)
        repo = QLabel("开源地址\ngithub.com/yueryuyueryun/AutoClicker\n"
                      "gitee.com/cui-huajin/AutoClicker")
        repo.setObjectName("dlgHint")
        repo.setAlignment(Qt.AlignCenter)
        lay.addWidget(repo)
        lay.addSpacing(6)
        ok = QPushButton("知道了")
        ok.setObjectName("pillBtn")
        ok.clicked.connect(self.accept)
        lay.addWidget(ok)
        ok.setFocus()

    def showEvent(self, event):
        super().showEvent(event)
        self.setWindowOpacity(0.0)
        self._anim = QPropertyAnimation(self, b"windowOpacity", self)
        self._anim.setDuration(160)
        self._anim.setStartValue(0.0)
        self._anim.setEndValue(1.0)
        self._anim.setEasingCurve(QEasingCurve.OutCubic)
        self._anim.start()

    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape:
            self.reject()
        else:
            super().keyPressEvent(event)

    def accept(self):
        self._fade_close(QDialog.Accepted)

    def reject(self):
        self._fade_close(QDialog.Rejected)

    def _fade_close(self, code):
        if self._closing:
            return
        self._closing = True
        self._anim = QPropertyAnimation(self, b"windowOpacity", self)
        self._anim.setDuration(120)
        self._anim.setStartValue(self.windowOpacity())
        self._anim.setEndValue(0.0)
        self._anim.setEasingCurve(QEasingCurve.InCubic)
        self._anim.finished.connect(lambda: QDialog.done(self, code))
        self._anim.start()

    def popup(self):
        p = self.parentWidget()
        self.adjustSize()
        if p is not None:
            g = p.geometry()
            self.move(g.center().x() - self.width() // 2,
                      g.center().y() - self.height() // 2)
        return self.exec()


class MainWindow(QWidget):
    sig_finished = Signal()

    def __init__(self):
        super().__init__()
        self.setWindowFlags(
            Qt.FramelessWindowHint | Qt.Window | Qt.WindowMinimizeButtonHint
        )
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setWindowOpacity(0.0)
        self.setFixedWidth(384)
        self.setMinimumHeight(CONT_H)
        self.setMaximumHeight(CONT_H)
        self.setWindowTitle("AutoClicker 连点器")

        self.mode = "continuous"
        self.running = False
        self.recording = False
        self.click_count = 0
        self.interval_sec = 0.1
        self.mouse_button = mouse.Button.left
        self.clicks_per_press = 1
        self.cont_target = 0
        self.compact = None
        self._saved_geo = None
        self.stop_event = Event()
        self.worker = None
        self.record_thread = None
        self._record_queue = deque()
        self._record_drain = QTimer(self)
        self._record_drain.setInterval(30)
        self._record_drain.timeout.connect(self._drain_record_queue)
        self._last_record_t = None
        self._drag_pos = None
        self._closing = False
        self._minimizing = False
        self._restoring = False
        self._fade_refs = []
        self._pre_min_geo = None
        self._ghosts = []
        self._xfade = None
        self._xfade_geo = None
        self._page_anim = None
        self.slots = []
        self.slot_idx = -1
        self.theme = self._load_theme()
        self.pal = PALETTES[self.theme]

        self._init_ui()
        self._init_animations()

        self.sig_finished.connect(self._on_finished)

    # ---------------- UI ----------------
    def _init_ui(self):
        outer = QVBoxLayout(self)
        outer.setContentsMargins(12, 12, 12, 12)

        card = QWidget(objectName="card")
        shadow = QGraphicsDropShadowEffect(card)
        shadow.setBlurRadius(32)
        shadow.setOffset(0, 8)
        self.card_shadow = shadow
        card.setGraphicsEffect(shadow)
        outer.addWidget(card)

        root = QVBoxLayout(card)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        self.body = QWidget()
        root.addWidget(self.body)
        self.pill_record = QWidget()
        self.pill_exec = QWidget()
        self.pill_record.hide()
        self.pill_exec.hide()
        root.addWidget(self.pill_record)
        root.addWidget(self.pill_exec)
        self._build_pills()

        root = QVBoxLayout(self.body)
        root.setContentsMargins(22, 16, 22, 20)
        root.setSpacing(12)

        # 标题栏
        title_bar = QHBoxLayout()
        title_bar.setSpacing(10)
        logo = QPushButton()
        logo.setObjectName("logoBtn")
        logo.setFixedSize(24, 24)
        logo.setIcon(QIcon(":/assets/logo128.png"))
        logo.setIconSize(QSize(20, 20))
        logo.setCursor(Qt.PointingHandCursor)
        logo.setToolTip("关于 AutoClicker")
        logo.clicked.connect(self._show_about)
        title = QLabel("AutoClicker 连点器")
        title.setObjectName("title")
        btn_min = QPushButton("─")
        btn_min.setObjectName("winBtn")
        btn_min.setFixedSize(28, 28)
        btn_min.setCursor(Qt.PointingHandCursor)
        btn_min.clicked.connect(self._minimize)
        btn_close = QPushButton("✕")
        btn_close.setObjectName("winBtn")
        btn_close.setProperty("close", True)
        btn_close.setFixedSize(28, 28)
        btn_close.setCursor(Qt.PointingHandCursor)
        btn_close.clicked.connect(self.close)
        self.theme_btn = QPushButton("☾")
        self.theme_btn.setObjectName("winBtn")
        self.theme_btn.setFixedSize(28, 28)
        self.theme_btn.setCursor(Qt.PointingHandCursor)
        self.theme_btn.setToolTip("切换浅色 / 深色主题")
        self.theme_btn.clicked.connect(self._toggle_theme)
        title_bar.addWidget(logo)
        title_bar.addWidget(title)
        title_bar.addStretch()
        title_bar.addWidget(self.theme_btn)
        title_bar.addWidget(btn_min)
        title_bar.addWidget(btn_close)
        root.addLayout(title_bar)

        # 模式切换
        seg_mode, self.mode_group = self._make_seg(["连续点击", "宏回放"], 0)
        seg_mode.setFixedHeight(36)
        self.mode_group.idClicked.connect(self._switch_mode)
        root.addWidget(seg_mode)

        # 页容器：两页面板绝对定位在其中，页转时卡片/窗口完全不动，只有内容滑入滑出
        self.page_host = QWidget()
        root.addWidget(self.page_host, 1)
        self.panel_continuous = QWidget(self.page_host)
        self.panel_macro = QWidget(self.page_host)
        self._build_continuous_panel(self.panel_continuous)
        self._build_macro_panel(self.panel_macro)
        self.panel_macro.hide()

        self.slots = self._load_slots()
        self.slot_idx = 0 if self.slots else -1
        self._refresh_slot_list()
        if self.slots:
            self._load_slot_to_table(self.slots[0])

        # 开始按钮
        self.start_btn = QPushButton("开始连点")
        self.start_btn.setObjectName("startBtn")
        self.start_btn.setCursor(Qt.PointingHandCursor)
        self.start_btn.clicked.connect(self.toggle)
        root.addWidget(self.start_btn)

        self._apply_theme()

    def _build_pills(self):
        rec_lay = QHBoxLayout(self.pill_record)
        rec_lay.setContentsMargins(10, 8, 10, 8)
        rec_lay.setSpacing(8)
        self.pill_stop_record = QPushButton("停止录制")
        self.pill_stop_record.setObjectName("pillBtn")
        self.pill_stop_record.setProperty("danger", True)
        self.pill_stop_record.setFixedHeight(32)
        self.pill_stop_record.setCursor(Qt.PointingHandCursor)
        # clicked 会带 checked 参数，直连会被当成 ask_save=False 跳过保存
        self.pill_stop_record.clicked.connect(lambda: self.toggle_recording())
        rec_lay.addWidget(self.pill_stop_record)

        ex_lay = QHBoxLayout(self.pill_exec)
        ex_lay.setContentsMargins(10, 8, 10, 8)
        ex_lay.setSpacing(6)
        self.exec_stop_btn = QPushButton("终止执行")
        self.exec_stop_btn.setObjectName("pillBtn")
        self.exec_stop_btn.setProperty("danger", True)
        self.exec_stop_btn.setFixedHeight(32)
        self.exec_stop_btn.setCursor(Qt.PointingHandCursor)
        self.exec_stop_btn.setToolTip("停止当前执行")
        self.exec_stop_btn.clicked.connect(self.stop_running)
        self.exec_back_btn = QPushButton("返回应用")
        self.exec_back_btn.setObjectName("pillGhost")
        self.exec_back_btn.setFixedHeight(32)
        self.exec_back_btn.setCursor(Qt.PointingHandCursor)
        self.exec_back_btn.setToolTip("展开回主界面（执行不中断）")
        self.exec_back_btn.clicked.connect(self._exit_compact)
        self.exec_again_btn = QPushButton("再执行一次")
        self.exec_again_btn.setObjectName("pillBtn")
        self.exec_again_btn.setFixedHeight(32)
        self.exec_again_btn.setCursor(Qt.PointingHandCursor)
        self.exec_again_btn.setToolTip("按当前执行次数再跑一轮")
        self.exec_again_btn.clicked.connect(self.toggle)
        self.pill_spin = QSpinBox()
        self.pill_spin.setButtonSymbols(QAbstractSpinBox.NoButtons)
        self.pill_spin.setFrame(False)
        self.pill_spin.setRange(0, 9999)
        self.pill_spin.setSpecialValueText("无限")
        self.pill_spin.setToolTip("执行次数：宏回放为循环次数，连续点击为总点击数；0 = 无限")
        self.pill_spin.valueChanged.connect(self._on_pill_spin_changed)
        spin_line_edit = self.pill_spin.findChild(QLineEdit)
        if spin_line_edit is not None:
            spin_line_edit.setFrame(False)
            spin_line_edit.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        self.pill_spin.setStyleSheet("QLineEdit { padding: 7px 2px; }")
        pill_spin_frame = QWidget(objectName="spinFrame")
        pill_spin_frame.setFixedSize(84, 32)
        ps_lay = QHBoxLayout(pill_spin_frame)
        ps_lay.setContentsMargins(10, 0, 10, 0)
        ps_lay.setSpacing(4)
        ps_lay.addWidget(self.pill_spin)
        self.pill_unit = QLabel("次")
        self.pill_unit.setObjectName("unitLabel")
        ps_lay.addWidget(self.pill_unit)
        self.pill_spin.valueChanged.connect(
            lambda v: self.pill_unit.setVisible(v != 0))
        for w in (self.exec_stop_btn, self.exec_back_btn, self.exec_again_btn):
            ex_lay.addWidget(w)
        ex_lay.addStretch()
        ex_lay.addWidget(pill_spin_frame)

    def _build_continuous_panel(self, panel):
        lay = QVBoxLayout(panel)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(8)

        lay.addWidget(self._section("点击频率"))
        rate_row = QHBoxLayout()
        rate_row.setSpacing(8)
        self.value_spin = QDoubleSpinBox()
        self.value_spin.setButtonSymbols(QAbstractSpinBox.NoButtons)
        self.value_spin.setFrame(False)
        self.value_spin.setRange(1, 60000)
        self.value_spin.setDecimals(0)
        self.value_spin.setSingleStep(10)
        self.value_spin.setValue(100)
        self.value_spin.setKeyboardTracking(False)
        self.value_spin.valueChanged.connect(self._update_interval)
        spin_frame = QWidget(objectName="spinFrame")
        spin_frame.setFixedHeight(36)
        spin_lay = QHBoxLayout(spin_frame)
        spin_lay.setContentsMargins(0, 0, 0, 0)
        spin_lay.addWidget(self.value_spin)
        spin_line_edit = self.value_spin.findChild(QLineEdit)
        if spin_line_edit is not None:
            spin_line_edit.setFrame(False)
        self.unit_combo = QComboBox()
        self.unit_combo.addItems(["毫秒 / 次", "次 / 秒"])
        self.unit_combo.setFixedWidth(110)
        self.unit_combo.setCursor(Qt.PointingHandCursor)
        self.unit_combo.currentIndexChanged.connect(self._on_unit_changed)
        rate_row.addWidget(spin_frame, 1)
        rate_row.addWidget(self.unit_combo)
        lay.addLayout(rate_row)
        lay.addSpacing(12)

        lay.addWidget(self._section("鼠标按键"))
        seg_btn, self.btn_group = self._make_seg(["左键", "右键", "中键"], 0)
        self.btn_group.idClicked.connect(self._on_button_changed)
        lay.addWidget(seg_btn)
        lay.addSpacing(12)

        lay.addWidget(self._section("点击方式"))
        seg_type, self.type_group = self._make_seg(["单击", "双击"], 0)
        self.type_group.idClicked.connect(
            lambda i: setattr(self, "clicks_per_press", 2 if i == 1 else 1)
        )
        lay.addWidget(seg_type)
        lay.addStretch(1)

    def _build_macro_panel(self, panel):
        lay = QVBoxLayout(panel)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(12)

        rec_row = QHBoxLayout()
        rec_row.setSpacing(8)
        self.record_btn = QPushButton("开始录制")
        self.record_btn.setObjectName("recordBtn")
        self.record_btn.setCursor(Qt.PointingHandCursor)
        self.record_btn.clicked.connect(lambda: self.toggle_recording())
        rec_row.addWidget(self.record_btn)
        self.new_slot_btn = QPushButton("新建存档")
        self.new_slot_btn.setObjectName("ghostBtn")
        self.new_slot_btn.setCursor(Qt.PointingHandCursor)
        self.new_slot_btn.clicked.connect(self._new_slot)
        rec_row.addWidget(self.new_slot_btn)
        rec_row.addStretch()
        self.slot_del_btn = QPushButton("删除")
        self.slot_del_btn.setObjectName("ghostBtn")
        self.slot_del_btn.setCursor(Qt.PointingHandCursor)
        self.slot_del_btn.clicked.connect(self._delete_slot)
        rec_row.addWidget(self.slot_del_btn)
        btn_w = max(self.record_btn.sizeHint().width(),
                    self.new_slot_btn.sizeHint().width())
        for b in (self.record_btn, self.new_slot_btn, self.slot_del_btn):
            b.setFixedWidth(btn_w)
        lay.addLayout(rec_row)
        lay.addSpacing(4)

        lay.addWidget(self._section("回放存档（点选加载）"))

        self.slot_view = SlotList()
        self.slot_view.rowClicked.connect(self._on_slot_changed)
        self.slot_view.setFixedHeight(208)
        lay.addWidget(self.slot_view)
        lay.addSpacing(4)

        # 步骤数据只保留在隐藏模型里，页面不再展示表格（简约布局）
        self.step_table = QTableWidget(0, 5)

        gap_frame = QWidget(objectName="spinFrame")
        gap_frame.setFixedHeight(40)
        gap_lay = QHBoxLayout(gap_frame)
        gap_lay.setContentsMargins(14, 0, 12, 0)
        gap_lay.setSpacing(6)
        gap_title = QLabel("循环间隔")
        gap_title.setObjectName("fieldLabel")
        gap_lay.addWidget(gap_title)
        gap_lay.addStretch()
        self.loop_gap_spin = QDoubleSpinBox()
        self.loop_gap_spin.setButtonSymbols(QAbstractSpinBox.NoButtons)
        self.loop_gap_spin.setFrame(False)
        self.loop_gap_spin.setDecimals(1)
        self.loop_gap_spin.setRange(0, 600)
        self.loop_gap_spin.setSingleStep(0.1)
        self.loop_gap_spin.setValue(0.5)
        self.loop_gap_spin.setFixedWidth(56)
        gap_line_edit = self.loop_gap_spin.findChild(QLineEdit)
        if gap_line_edit is not None:
            gap_line_edit.setFrame(False)
            gap_line_edit.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        self.loop_gap_spin.setStyleSheet("QLineEdit { padding: 2px 6px; }")
        gap_inner = QWidget(objectName="innerSpin")
        gap_inner.setFixedSize(64, 28)
        gi_lay = QHBoxLayout(gap_inner)
        gi_lay.setContentsMargins(4, 0, 4, 0)
        gi_lay.setSpacing(0)
        gi_lay.addWidget(self.loop_gap_spin)
        gap_lay.addWidget(gap_inner)
        gap_unit = QLabel("秒")
        gap_unit.setObjectName("unitLabel")
        gap_lay.addWidget(gap_unit)
        self.loop_gap_spin.valueChanged.connect(self._on_gap_changed)
        lay.addWidget(gap_frame)
        lay.addSpacing(4)

        loop_frame = QWidget(objectName="spinFrame")
        loop_frame.setFixedHeight(40)
        loop_lay = QHBoxLayout(loop_frame)
        loop_lay.setContentsMargins(14, 0, 12, 0)
        loop_lay.setSpacing(6)
        loop_title = QLabel("循环次数")
        loop_title.setObjectName("fieldLabel")
        loop_lay.addWidget(loop_title)
        loop_lay.addStretch()
        self.loop_spin = QSpinBox()
        self.loop_spin.setButtonSymbols(QAbstractSpinBox.NoButtons)
        self.loop_spin.setFrame(False)
        self.loop_spin.setRange(0, 9999)
        self.loop_spin.setValue(1)
        self.loop_spin.setSpecialValueText("无限")
        self.loop_spin.setFixedWidth(56)
        loop_line_edit = self.loop_spin.findChild(QLineEdit)
        if loop_line_edit is not None:
            loop_line_edit.setFrame(False)
            loop_line_edit.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        self.loop_spin.setStyleSheet("QLineEdit { padding: 2px 6px; }")
        inner_spin = QWidget(objectName="innerSpin")
        inner_spin.setFixedSize(64, 28)
        in_lay = QHBoxLayout(inner_spin)
        in_lay.setContentsMargins(4, 0, 4, 0)
        in_lay.setSpacing(0)
        in_lay.addWidget(self.loop_spin)
        loop_lay.addWidget(inner_spin)
        self.loop_unit = QLabel("次")
        self.loop_unit.setObjectName("unitLabel")
        loop_lay.addWidget(self.loop_unit)
        self.loop_spin.valueChanged.connect(
            lambda v: self.loop_unit.setVisible(v != 0))
        lay.addWidget(loop_frame)
        lay.addStretch(1)

    def _section(self, text):
        label = QLabel(text)
        label.setObjectName("section")
        return label

    def _make_seg(self, options, checked_idx):
        row = SegRow(options, checked_idx)
        return row, row.group

    # ---------------- 模式切换 ----------------
    def _switch_mode(self, idx):
        self.stop_all()
        new_mode = "continuous" if idx == 0 else "macro"
        if new_mode == self.mode:
            return
        old_panel = self.panel_macro if idx == 0 else self.panel_continuous
        new_panel = self.panel_continuous if idx == 0 else self.panel_macro
        self.mode = new_mode
        self._sync_start_btn()
        self.fade_in_anim.stop()
        self.fade_out_anim.stop()
        self._slide_panels(old_panel, new_panel, 1 if idx == 1 else -1)

    def _layout_pages(self):
        if self._page_anim is not None:
            return
        r = self.page_host.rect()
        self.panel_continuous.setGeometry(r)
        self.panel_macro.setGeometry(r)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._layout_pages()

    def showEvent(self, event):
        super().showEvent(event)
        self._layout_pages()
        # 首帧布局尚未结算时 host 尺寸可能为 0，事件循环跑一轮后再对齐一次
        QTimer.singleShot(0, self._layout_pages)

    def _slide_panels(self, old_panel, new_panel, direction=1, duration=260):
        """页转：与分段按钮高亮块同款水滴流转——纯水平平移、同曲线同节奏，
        方向跟随切换目标（去宏回放向左流、回连续点击向右流）。"""
        if self._page_anim is not None:
            self._page_anim.stop()
            self._page_anim = None
            self._snap_pages()
        r = self.page_host.rect()
        w = r.width()
        old_panel.setGeometry(r)
        new_panel.setGeometry(r.translated(direction * w, 0))
        old_panel.show()
        new_panel.show()
        curve = QEasingCurve(QEasingCurve.Type.InOutCubic)
        start = time.perf_counter()
        timer = QTimer(self)
        timer.setInterval(16)

        def tick():
            t = min((time.perf_counter() - start) * 1000.0 / duration, 1.0)
            p = curve.valueForProgress(t)
            old_panel.move(int(-direction * w * p), 0)
            new_panel.move(int(direction * w * (1.0 - p)), 0)
            if t >= 1.0:
                timer.stop()
                self._page_anim = None
                old_panel.hide()
                old_panel.move(0, 0)
                new_panel.move(0, 0)
                self._layout_pages()

        timer.timeout.connect(tick)
        self._page_anim = timer
        timer.start()

    def _snap_pages(self):
        cur = self.panel_macro if self.mode == "macro" else self.panel_continuous
        other = self.panel_continuous if cur is self.panel_macro else self.panel_macro
        other.hide()
        other.move(0, 0)
        cur.show()
        cur.setGeometry(self.page_host.rect())

    # ---------------- 紧凑悬浮条 ----------------
    def _pill_spin_get(self):
        return self.loop_spin.value() if self.mode == "macro" else self.cont_target

    def _on_pill_spin_changed(self, v):
        if self.mode == "macro":
            self.loop_spin.setValue(v)
        else:
            self.cont_target = v

    def _sync_exec_pill(self):
        self.exec_stop_btn.setEnabled(self.running)
        self.exec_again_btn.setEnabled(not self.running)

    def _set_topmost(self, on):
        """录制/执行收起为悬浮条时置顶，新开窗/全屏页都不能遮挡。"""
        if on:
            self._apply_topmost(True)
            if not hasattr(self, "_topmost_timer"):
                self._topmost_timer = QTimer(self)
                self._topmost_timer.setInterval(150)
                self._topmost_timer.timeout.connect(self._assert_topmost)
            self._topmost_timer.start()
            # 全屏切换要几百毫秒才稳定 Z 序，错峰补置顶避免竞态输掉
            for ms in (120, 400, 1000):
                QTimer.singleShot(ms, self._assert_topmost)
        else:
            if hasattr(self, "_topmost_timer"):
                self._topmost_timer.stop()
            self._apply_topmost(False)

    def _apply_topmost(self, on=True):
        SWP_NOSIZE, SWP_NOMOVE, SWP_NOACTIVATE = 0x0001, 0x0002, 0x0010
        _user32.SetWindowPos(
            wintypes.HWND(int(self.winId())),
            wintypes.HWND(ctypes.c_void_p(-1 if on else -2).value),
            0, 0, 0, 0,
            SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE)

    def _assert_topmost(self):
        if self.compact:
            self._apply_topmost(True)

    def _enter_compact(self, kind, on_shown=None):
        if self.compact == kind:
            if kind == "exec":
                self._sync_exec_pill()
            if on_shown is not None:
                on_shown()
            return
        self.compact = kind
        self._set_topmost(True)
        if self._saved_geo is None:
            self._saved_geo = self.geometry()

        def morph():
            self.body.hide()
            self.pill_record.setVisible(kind == "record")
            self.pill_exec.setVisible(kind == "exec")
            if kind == "exec":
                self.pill_spin.blockSignals(True)
                self.pill_spin.setValue(self._pill_spin_get())
                self.pill_spin.blockSignals(False)
                self.pill_unit.setVisible(self.pill_spin.value() != 0)
                self._sync_exec_pill()
            screen = (QGuiApplication.screenAt(self._saved_geo.center())
                      or QGuiApplication.primaryScreen())
            ag = screen.availableGeometry()
            w = min(PILL_W_EXEC if kind == "exec" else PILL_W_RECORD, ag.width() - 16)
            self.setFixedWidth(w)
            self.setMinimumHeight(PILL_H)
            self.setMaximumHeight(PILL_H)
            self.move(ag.x() + (ag.width() - w) // 2, ag.y() + 8)
            if on_shown is not None:
                on_shown()

        self._crossfade(morph, 300)

    def _exit_compact(self):
        if not self.compact:
            return
        self.compact = None
        self._set_topmost(False)

        def morph():
            self.pill_record.hide()
            self.pill_exec.hide()
            self.body.show()
            h = MACRO_H if self.mode == "macro" else CONT_H
            self.setFixedWidth(384)
            self.setMinimumHeight(h)
            self.setMaximumHeight(h)
            if self._saved_geo is not None:
                self.move(self._saved_geo.x(), self._saved_geo.y())
                self._saved_geo = None

        self._crossfade(morph, 300)

    # ---------------- 参数 ----------------
    def _update_interval(self):
        v = self.value_spin.value()
        if self.unit_combo.currentIndex() == 0:
            self.interval_sec = max(v / 1000.0, 0.001)
        else:
            self.interval_sec = 1.0 / max(v, 0.1)

    def _on_unit_changed(self, idx):
        spin = self.value_spin
        spin.blockSignals(True)
        if idx == 0:
            spin.setRange(1, 60000)
            spin.setDecimals(0)
            spin.setSingleStep(10)
            spin.setValue(100)
        else:
            spin.setRange(0.1, 1000)
            spin.setDecimals(1)
            spin.setSingleStep(1)
            spin.setValue(10)
        spin.blockSignals(False)
        self._update_interval()

    def _on_button_changed(self, idx):
        self.mouse_button = (mouse.Button.left, mouse.Button.right, mouse.Button.middle)[idx]

    # ---------------- 启动 / 停止 ----------------
    def toggle(self):
        if self._closing or self.recording:
            return
        if self.running:
            self.stop_running()
            return
        if self.mode == "continuous":
            self.start_clicking()
        else:
            self.start_replay()

    def start_clicking(self):
        self.running = True
        self.click_count = 0
        self.stop_event = Event()
        self.worker = Thread(target=self._click_loop, daemon=True)
        self._sync_start_btn()
        self._sync_exec_pill()
        self._enter_compact("exec", on_shown=self._worker_starter())

    def start_replay(self):
        steps = self._read_steps()
        if not steps:
            dlg = PromptDialog(self, "无法回放",
                               "还没有录制任何动作，先点「开始录制」录一段回放。",
                               prompt=False, confirm_text="知道了", alert=True)
            dlg.popup()
            return
        self.running = True
        self.click_count = 0
        self.stop_event = Event()
        self.worker = Thread(target=self._replay_loop, args=(steps,), daemon=True)
        self._sync_start_btn()
        self._sync_exec_pill()
        self._enter_compact("exec", on_shown=self._worker_starter())

    def _worker_starter(self):
        worker = self.worker

        def start():
            if self.running and self.worker is worker:
                worker.start()

        return start

    def stop_running(self):
        self.running = False
        self.stop_event.set()
        self._sync_start_btn()
        self._sync_exec_pill()

    def _on_finished(self):
        if self.running:
            self.stop_running()

    def stop_all(self):
        if self.running:
            self.stop_running()
        if self.recording:
            self.toggle_recording(ask_save=False)

    def _click_loop(self):
        ctrl = mouse.Controller()
        dpr = self.devicePixelRatioF() or 1.0
        btn = self.mouse_button
        n = self.clicks_per_press
        reached = False
        while not self.stop_event.is_set():
            pos = ctrl.position
            lx, ly = int(pos[0] / dpr), int(pos[1] / dpr)
            if self.frameGeometry().contains(QPoint(lx, ly)):
                if self.stop_event.wait(self.interval_sec):
                    break
                continue
            try:
                ctrl.click(btn, n)
            except Exception:
                break
            self.click_count += 1
            if self.cont_target and self.click_count >= self.cont_target:
                reached = True
                break
            if self.stop_event.wait(self.interval_sec):
                break
        if reached:
            self.sig_finished.emit()

    def _replay_loop(self, steps):
        ctrl = mouse.Controller()
        dpr = self.devicePixelRatioF() or 1.0
        done = 0
        while not self.stop_event.is_set():
            loops = self.loop_spin.value()
            seq_ok = True
            for i, st in enumerate(steps):
                if self.stop_event.is_set():
                    seq_ok = False
                    break
                # 表格存逻辑坐标；Controller 在 DPI 感知进程里按物理坐标解释
                ctrl.position = (round(st["x"] * dpr), round(st["y"] * dpr))
                for _ in range(st["count"]):
                    ctrl.click(st["button"])
                    self.click_count += 1
                    if self.stop_event.wait(0.05):
                        break
                # 末步间隔由循环间隔统一控制，避免两轮之间贴在一起
                if i < len(steps) - 1:
                    if self.stop_event.wait(st["interval"] / 1000.0):
                        seq_ok = False
                        break
            if not seq_ok:
                break
            done += 1
            if loops and done >= loops:
                break
            gap = int(self.loop_gap_spin.value() * 1000)
            if gap and self.stop_event.wait(gap / 1000.0):
                break
        if not self.stop_event.is_set():
            self.sig_finished.emit()

    # ---------------- 录制 ----------------
    def toggle_recording(self, ask_save=True):
        if self.running:
            self.stop_running()
        self.recording = not self.recording
        if self.recording:
            # 新录制只保留本次动作，否则会把当前存档的旧行一起录进去
            self.step_table.setRowCount(0)
            self._last_record_t = None
            # 游戏全屏/提权窗口常使鼠标低级钩子被系统摘除，改轮询全局输入状态，录制更稳
            self.record_thread = Thread(target=self._record_poll_loop, daemon=True)
            self.record_thread.start()
            time.sleep(0.05)
            if not self.record_thread.is_alive():
                self.record_thread = None
                self.recording = False
                QMessageBox.warning(self, "录制失败",
                                    "鼠标监听服务启动失败，请重试。")
                return
            self._record_drain.start()
            self.record_btn.setProperty("recording", True)
            repolish(self.record_btn)
            self.record_btn.setText("停止录制")
            self._enter_compact("record")
        else:
            hook_alive = self.record_thread.is_alive() \
                if self.record_thread else False
            if self.record_thread is not None:
                self.record_thread.join(timeout=0.5)
                self.record_thread = None
            self._record_drain.stop()
            self._drain_record_queue()
            self.record_btn.setProperty("recording", False)
            repolish(self.record_btn)
            self.record_btn.setText("开始录制")
            self._exit_compact()
            if ask_save:
                # 自动填入：最后一次点击到停止录制之间的自然停顿作为循环间隔
                if self._last_record_t:
                    pause = (time.perf_counter() - self._last_record_t)
                    self.loop_gap_spin.setValue(
                        max(0.0, min(round(pause, 1), 600.0)))
                idx = self.slot_idx
                if 0 <= idx < len(self.slots):
                    if self.step_table.rowCount() > 0:
                        # 已选中存档：录制结果直接写入该存档
                        self.slots[idx]["steps"] = self._steps_for_save()
                        self._save_slots()
                        self._refresh_slot_list()
                    else:
                        # 一个点击都没录到不清空旧存档，避免静默丢数据
                        reason = "" if hook_alive else "（录制钩子中途失效）"
                        QMessageBox.information(
                            self, "录制结束",
                            f"未录制到任何点击{reason}，存档内容保持不变。")
                else:
                    self._prompt_save_slot()

    # 轮询全局鼠标状态录制触点：低级钩子在游戏全屏/提权窗口下会被系统摘除，
    # 而 GetAsyncKeyState 读的是全局输入状态，不受钩子失效与完整性级别影响。
    # 轮询线程只做无锁入队，跨线程 Qt 信号无法投递，改由主线程 QTimer 排空队列。
    def _record_poll_loop(self):
        user32 = ctypes.windll.user32
        vks = ((mouse.Button.left, 0x01),
               (mouse.Button.right, 0x02),
               (mouse.Button.middle, 0x04))
        prev = {vk: False for _, vk in vks}
        pt = wintypes.POINT()
        while self.recording:
            for btn, vk in vks:
                down = bool(user32.GetAsyncKeyState(vk) & 0x8000)
                if down != prev[vk]:
                    prev[vk] = down
                    if user32.GetCursorPos(ctypes.byref(pt)):
                        self._record_queue.append(
                            (pt.x, pt.y, btn, down, time.perf_counter()))
            time.sleep(0.004)

    def _drain_record_queue(self):
        while self._record_queue:
            x, y, button, pressed, t = self._record_queue.popleft()
            self._handle_record_click(x, y, button, pressed, now=t)

    def _handle_record_click(self, x, y, button, pressed, now=None):
        if not pressed:
            return
        dpr = self.devicePixelRatioF() or 1.0
        x, y = round(x / dpr), round(y / dpr)
        if self.frameGeometry().contains(QPoint(x, y)):
            return
        if now is None:
            now = time.perf_counter()
        table = self.step_table
        last = table.rowCount() - 1
        # 每次点击独立成行并记录与上一次点击的真实间隔，回放才能复现节奏
        if last >= 0 and self._last_record_t:
            dt = int((now - self._last_record_t) * 1000)
            table.item(last, 4).setText(str(max(dt, 10)))
        self._last_record_t = now
        self._add_step_row(x, y, button, 1, 200)

    def _add_step_row(self, x, y, button, count, interval):
        table = self.step_table
        row = table.rowCount()
        table.insertRow(row)
        vals = [str(row + 1), f"{x}, {y}", BTN_NAMES.get(button, "左"), str(count), str(interval)]
        for col, text in enumerate(vals):
            item = QTableWidgetItem(text)
            item.setTextAlignment(Qt.AlignCenter)
            if col in (0, 1, 2):
                item.setFlags(Qt.ItemIsEnabled | Qt.ItemIsSelectable)
            table.setItem(row, col, item)

    def _row_pos(self, row):
        item = self.step_table.item(row, 1)
        if item is None:
            return None
        try:
            x, y = item.text().split(",")
            return int(x.strip()), int(y.strip())
        except ValueError:
            return None

    def _row_button(self, row):
        item = self.step_table.item(row, 2)
        return BTN_FROM_NAME.get(item.text() if item else "", mouse.Button.left)

    def _read_steps(self):
        steps = []
        table = self.step_table
        for r in range(table.rowCount()):
            pos = self._row_pos(r)
            if pos is None:
                continue
            try:
                count = max(1, int(table.item(r, 3).text()))
                interval = max(0, int(table.item(r, 4).text()))
            except (ValueError, AttributeError):
                continue
            steps.append({
                "x": pos[0], "y": pos[1],
                "button": self._row_button(r),
                "count": count, "interval": interval,
            })
        return steps

    # ---------------- 回放存档 ----------------
    def _data_base(self):
        if not getattr(sys, "frozen", False):
            return os.path.dirname(os.path.abspath(__file__))
        base = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")),
                            "AutoClicker")
        os.makedirs(base, exist_ok=True)
        # 旧版数据在 exe 旁，重打包会被清空，这里一次性迁移到数据目录
        for name in ("macros.json", "settings.json"):
            old = os.path.join(os.path.dirname(sys.executable), name)
            new = os.path.join(base, name)
            if os.path.exists(old) and not os.path.exists(new):
                try:
                    shutil.copy2(old, new)
                except OSError:
                    pass
        return base

    def _macro_file(self):
        return os.path.join(self._data_base(), "macros.json")

    # ---------------- 主题 ----------------
    def _settings_file(self):
        return os.path.join(self._data_base(), "settings.json")

    def _load_theme(self):
        try:
            with open(self._settings_file(), "r", encoding="utf-8") as f:
                theme = json.load(f).get("theme", "light")
            return theme if theme in PALETTES else "light"
        except (OSError, ValueError):
            return "light"

    def _toggle_theme(self):
        self.theme = "light" if self.theme == "dark" else "dark"
        try:
            with open(self._settings_file(), "w", encoding="utf-8") as f:
                json.dump({"theme": self.theme}, f)
        except OSError:
            pass
        self._apply_theme()

    def _show_about(self):
        AboutDialog(self).popup()

    def _apply_theme(self):
        pal = PALETTES[self.theme]
        self.pal = pal
        RIPPLE[:] = pal["ripple"]
        self.setStyleSheet(style_for(pal))
        self.card_shadow.setColor(QColor(*pal["shadow"]))
        self.theme_btn.setText("☾" if self.theme == "dark" else "☀")
        self.update()

    def _load_slots(self):
        try:
            with open(self._macro_file(), "r", encoding="utf-8") as f:
                data = json.load(f)
            return [s for s in data
                    if isinstance(s, dict) and s.get("name")
                    and isinstance(s.get("steps"), list)]
        except (OSError, ValueError):
            return []

    def _save_slots(self):
        try:
            with open(self._macro_file(), "w", encoding="utf-8") as f:
                json.dump(self.slots, f, ensure_ascii=False, indent=2)
        except OSError:
            pass

    def _steps_for_save(self):
        out = []
        for st in self._read_steps():
            out.append({
                "x": st["x"], "y": st["y"],
                "button": BTN_NAMES.get(st["button"], "左"),
                "count": st["count"], "interval": st["interval"],
            })
        return out

    def _refresh_slot_list(self, select=None):
        if select is not None:
            self.slot_idx = select
        if self.slot_idx >= len(self.slots):
            self.slot_idx = len(self.slots) - 1
        self.slot_view.set_slots(self.slots, self.slot_idx)
        self._sync_slot_btns()

    def _sync_slot_btns(self):
        has = 0 <= self.slot_idx < len(self.slots)
        self.slot_del_btn.setEnabled(has)

    def _on_gap_changed(self, v):
        # 循环间隔随存档自动保存（存毫秒），切档时再读回
        if 0 <= self.slot_idx < len(self.slots):
            self.slots[self.slot_idx]["gap"] = int(round(v * 1000))
            self._save_slots()

    def _on_slot_changed(self, idx):
        self.slot_idx = idx
        self.slot_view.set_selected(idx)
        self._sync_slot_btns()
        if 0 <= idx < len(self.slots):
            self._load_slot_to_table(self.slots[idx])

    def _load_slot_to_table(self, slot):
        table = self.step_table
        table.setRowCount(0)
        for st in slot.get("steps", []):
            try:
                self._add_step_row(int(st["x"]), int(st["y"]),
                                   BTN_FROM_NAME.get(st.get("button", "左"), mouse.Button.left),
                                   max(1, int(st.get("count", 1))),
                                   max(0, int(st.get("interval", 200))))
            except (TypeError, ValueError):
                continue
        self.loop_gap_spin.blockSignals(True)
        self.loop_gap_spin.setValue(
            max(0.0, min(int(slot.get("gap", 500)) / 1000.0, 600.0)))
        self.loop_gap_spin.blockSignals(False)

    def _new_slot(self):
        dlg = PromptDialog(self, "新建存档", "为新存档命名：",
                           default=f"回放 {len(self.slots) + 1}")
        if dlg.popup() != QDialog.Accepted:
            return
        name = dlg.field.text().strip()
        if not name:
            return
        self.slots.append({"name": name, "steps": []})
        self._save_slots()
        self._refresh_slot_list(select=len(self.slots) - 1)
        self._load_slot_to_table(self.slots[-1])

    def _prompt_save_slot(self):
        if self.step_table.rowCount() == 0:
            return
        dlg = PromptDialog(self, "保存回放", "为这段回放命名：",
                           default=f"回放 {len(self.slots) + 1}")
        if dlg.popup() != QDialog.Accepted:
            return
        name = dlg.field.text().strip()
        if not name:
            return
        self.slots.append({"name": name, "steps": self._steps_for_save(),
                           "gap": int(round(self.loop_gap_spin.value() * 1000))})
        self._save_slots()
        self._refresh_slot_list(select=len(self.slots) - 1)

    def _delete_slot(self):
        idx = self.slot_idx
        if not (0 <= idx < len(self.slots)):
            return
        name = self.slots[idx]["name"]
        dlg = PromptDialog(self, "删除回放",
                           f"确认删除回放「{name}」？此操作不可恢复。",
                           prompt=False, confirm_text="确认删除", danger=True)
        if dlg.popup() != QDialog.Accepted:
            return
        self.slots.pop(idx)
        self._save_slots()
        self._refresh_slot_list(select=min(idx, len(self.slots) - 1))
        if 0 <= self.slot_idx < len(self.slots):
            self._load_slot_to_table(self.slots[self.slot_idx])
        else:
            self.step_table.setRowCount(0)

    def _sync_start_btn(self):
        if self.mode == "continuous":
            text = "停止连点" if self.running else "开始连点"
        else:
            text = "停止回放" if self.running else "开始回放"
        self.start_btn.setProperty("running", self.running)
        repolish(self.start_btn)
        self.start_btn.setText(text)

    # ---------------- 动画 ----------------
    def _init_animations(self):
        self.fade_in_anim = QPropertyAnimation(self, b"windowOpacity", self)
        self.fade_in_anim.setDuration(380)
        self.fade_in_anim.setStartValue(0.0)
        self.fade_in_anim.setEndValue(1.0)
        self.fade_in_anim.setEasingCurve(QEasingCurve.OutCubic)

        self.fade_out_anim = QPropertyAnimation(self, b"windowOpacity", self)
        self.fade_out_anim.setDuration(220)
        self.fade_out_anim.setStartValue(1.0)
        self.fade_out_anim.setEndValue(0.0)
        self.fade_out_anim.setEasingCurve(QEasingCurve.InCubic)
        self.fade_out_anim.finished.connect(self._on_fade_out_finished)

    def fade_in(self):
        self.fade_out_anim.stop()
        self.fade_in_anim.start()

    # ---- 快照残影：所有过渡都靠一张静态截图变形，避免实时重绘复杂控件导致卡顿 ----
    def _make_ghost(self, pix, geo, scaled):
        g = QLabel(
            None,
            Qt.FramelessWindowHint | Qt.Tool | Qt.WindowStaysOnTopHint
            | Qt.WindowDoesNotAcceptFocus | Qt.WindowTransparentForInput,
        )
        g.setAttribute(Qt.WA_TranslucentBackground)
        g.setAttribute(Qt.WA_ShowWithoutActivating)
        g.setScaledContents(scaled)
        g.setPixmap(pix)
        g.setGeometry(geo)
        g.show()
        g.repaint()
        self._ghosts.append(g)
        return g

    def _drop_ghost(self, g):
        if g in self._ghosts:
            self._ghosts.remove(g)
        g.hide()
        g.deleteLater()

    def _cancel_xfade(self):
        # 残影只是视觉层：真实窗口在 morph() 里已同步到最新状态，取消即恢复显示
        if self._xfade is not None:
            self._xfade.stop()
            self._xfade = None
        for g in list(self._ghosts):
            self._drop_ghost(g)
        if self._xfade_geo is not None:
            self.setGeometry(self._xfade_geo)
            self._xfade_geo = None
        if not self._minimizing and self.windowOpacity() < 1.0 and not self.isMinimized():
            self.setWindowOpacity(1.0)

    def _crossfade(self, morph, duration=300):
        """底面恒不透明的交叉溶解：新内容作为不透明底层平滑变形，旧内容在其上淡出。
        底层始终不透明，桌面不会透出，避免半透明叠加导致的发白/过曝闪烁。"""
        self._cancel_xfade()
        old_geo = self.geometry()
        old_pix = self.grab()

        # 顶层残影先出现（与当前窗口完全重合）再隐藏真实窗口换状态，消除空帧闪白
        top = self._make_ghost(old_pix, old_geo, scaled=True)
        self.setWindowOpacity(0.0)
        morph()
        QApplication.processEvents()
        new_geo = self.geometry()
        new_pix = self.grab()
        self._xfade_geo = new_geo

        base = self._make_ghost(new_pix, old_geo, scaled=True)   # 不透明底层（新内容）
        top.raise_()

        ease = QEasingCurve.InOutSine
        fo = QPropertyAnimation(top, b"windowOpacity", self)
        fo.setDuration(duration); fo.setStartValue(1.0); fo.setEndValue(0.0)
        fo.setEasingCurve(ease)
        g_base = QPropertyAnimation(base, b"geometry", self)
        g_base.setDuration(duration); g_base.setStartValue(old_geo); g_base.setEndValue(new_geo)
        g_base.setEasingCurve(ease)
        g_top = QPropertyAnimation(top, b"geometry", self)
        g_top.setDuration(duration); g_top.setStartValue(old_geo); g_top.setEndValue(new_geo)
        g_top.setEasingCurve(ease)
        grp = QParallelAnimationGroup(self)
        grp.addAnimation(fo); grp.addAnimation(g_base); grp.addAnimation(g_top)

        def done():
            self.setGeometry(new_geo)
            self.setWindowOpacity(1.0)
            self.repaint()
            self._xfade = None
            self._xfade_geo = None
            # 等真实窗口的合成帧呈现后再撤残影，避免撤早了露出背后桌面闪白
            QTimer.singleShot(
                40, lambda: (self._drop_ghost(base), self._drop_ghost(top)))

        grp.finished.connect(done)
        self._xfade = grp
        self._fade_refs.append(grp)
        grp.start()

    def _minimize(self):
        if self._minimizing:
            return
        self._minimizing = True
        self._cancel_xfade()
        self._pre_min_geo = self.geometry()
        small = self._small_rect(self._taskbar_anchor())
        pix = self.grab()
        ghost = self._make_ghost(pix, self._pre_min_geo, scaled=True)

        # 真实窗口立即隐形并最小化（原生动画在 opacity 0 下不可见），残影负责平滑收缩
        self.setWindowOpacity(0.0)
        self.showMinimized()

        geo = QPropertyAnimation(ghost, b"geometry", self)
        geo.setDuration(170)
        geo.setStartValue(self._pre_min_geo)
        geo.setEndValue(small)
        geo.setEasingCurve(QEasingCurve.OutCubic)
        fade = QPropertyAnimation(ghost, b"windowOpacity", self)
        fade.setDuration(170)
        fade.setStartValue(1.0)
        fade.setEndValue(0.0)
        fade.setEasingCurve(QEasingCurve.InQuad)
        group = QParallelAnimationGroup(self)
        group.addAnimation(geo)
        group.addAnimation(fade)

        def done():
            self._drop_ghost(ghost)
            self._minimizing = False

        group.finished.connect(done)
        self._fade_refs.append(group)
        group.start()

    def _restore_from_min(self):
        if self._restoring:
            return
        self._restoring = True
        target = self._pre_min_geo or self.geometry()
        small = self._small_rect(self._taskbar_anchor())
        self._release_size_constraints()
        self.setWindowOpacity(0.0)
        self.setGeometry(target)
        self.showNormal()
        QApplication.processEvents()
        pix = self.grab()
        self._apply_size_constraints()

        ghost = self._make_ghost(pix, small, scaled=True)

        geo = QPropertyAnimation(ghost, b"geometry", self)
        geo.setDuration(200)
        geo.setStartValue(small)
        geo.setEndValue(target)
        geo.setEasingCurve(QEasingCurve.OutCubic)
        group = QParallelAnimationGroup(self)
        group.addAnimation(geo)

        def done():
            self.setGeometry(target)
            self.setWindowOpacity(1.0)
            self._drop_ghost(ghost)
            self._restoring = False

        group.finished.connect(done)
        self._fade_refs.append(group)
        group.start()

    def _release_size_constraints(self):
        self.setMinimumWidth(0)
        self.setMaximumWidth(16777215)
        self.setMinimumHeight(0)
        self.setMaximumHeight(16777215)

    def _apply_size_constraints(self):
        if self.compact == "exec":
            w = PILL_W_EXEC
        elif self.compact == "record":
            w = PILL_W_RECORD
        else:
            w = 384
        h = PILL_H if self.compact else (MACRO_H if self.mode == "macro" else CONT_H)
        self.setFixedWidth(w)
        self.setMinimumHeight(h)
        self.setMaximumHeight(h)

    def _small_rect(self, anchor):
        # 收缩目标停在任务栏上沿的正上方，而非显示器最底边
        return QRect(anchor.x() - 48, anchor.y() - 24, 96, 24)

    def _taskbar_anchor(self):
        screen = (QGuiApplication.screenAt(self.geometry().center())
                  or QGuiApplication.primaryScreen())
        ag = screen.availableGeometry()
        user32 = ctypes.windll.user32
        rect = wintypes.RECT()
        x = ag.center().x()
        tray = user32.FindWindowW("Shell_TrayWnd", None)
        if tray:
            btn = self._find_taskbar_button(user32, tray)
            if user32.GetWindowRect(btn or tray, ctypes.byref(rect)):
                x = (rect.left + rect.right) // 2
        # y 取可用区域底边 = 任务栏上沿，收缩停在其上方（计入任务栏高度）
        return QPoint(x, ag.bottom())

    def _find_taskbar_button(self, user32, tray):
        # Win10 任务按钮是真实 HWND 且带窗口标题；Win11 为 XAML 绘制，找不到时回退任务栏中心
        rebar = user32.FindWindowExW(tray, 0, "ReBarWindow32", None)
        tasksw = user32.FindWindowExW(rebar, 0, "MSTaskSwWClass", None) if rebar else 0
        tasklist = user32.FindWindowExW(tasksw, 0, "MSTaskListW32", None) if tasksw else 0
        if not tasklist:
            return 0
        title = self.windowTitle()
        found = []
        enum_proc = ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)

        def callback(hwnd, _lparam):
            buf = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(hwnd, buf, 256)
            if buf.value == title:
                found.append(hwnd)
                return False
            return True

        user32.EnumChildWindows(tasklist, enum_proc(callback), 0)
        return found[0] if found else 0

    def changeEvent(self, event):
        if event.type() == QEvent.WindowStateChange:
            if event.oldState() & Qt.WindowMinimized and not self.isMinimized():
                self._restore_from_min()
        elif event.type() == QEvent.WindowDeactivate:
            # 失去激活的瞬间正是被压 Z 序的时机，立刻抢回顶层
            self._assert_topmost()
        super().changeEvent(event)

    def nativeEvent(self, eventType, message):
        # 点任务栏图标 / Alt+空格最小化时，Windows 直接发 SC_MINIMIZE，
        # 不走我们的 ─ 按钮，这里拦截下来改走自定义收缩动画
        if eventType == b"windows_generic_MSG":
            try:
                msg = wintypes.MSG.from_address(int(message))
            except (ValueError, TypeError):
                return super().nativeEvent(eventType, message)
            if msg.message == 0x0112 and (msg.wParam & 0xFFF0) == 0xF020:
                if not self._minimizing:
                    self._minimize()
                return True, 0
        return super().nativeEvent(eventType, message)

    def closeEvent(self, event):
        if self._closing:
            event.accept()
            return
        event.ignore()
        self.fade_out_anim.start()

    def _on_fade_out_finished(self):
        self._closing = True
        self.stop_event.set()
        self.recording = False
        if self.record_thread is not None:
            self.record_thread.join(timeout=0.5)
            self.record_thread = None
        self.close()
        QApplication.quit()

    # ---------------- 拖拽 ----------------
    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton:
            self._drag_pos = event.globalPosition().toPoint() - self.frameGeometry().topLeft()
            event.accept()

    def mouseMoveEvent(self, event):
        if self._drag_pos is not None and event.buttons() & Qt.LeftButton:
            self.move(event.globalPosition().toPoint() - self._drag_pos)
            event.accept()

    def mouseReleaseEvent(self, event):
        self._drag_pos = None
        event.accept()


def main():
    app = QApplication(sys.argv)
    app.setFont(QFont("Microsoft YaHei UI", 10))
    app.setWindowIcon(QIcon(":/app.ico"))
    win = MainWindow()
    win.show()
    win.fade_in()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
