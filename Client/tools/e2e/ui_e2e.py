#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GleanNote 客户端 UI 端到端驱动（adb + uiautomator）。

用途：在模拟器/真机上按 PRD 第 6 章的界面顺序走查主链路，逐步截图并断言
关键文案，产出可复核的走查记录。与 api_e2e.py 互补：前者验契约，后者验界面。

用法：
    python ui_e2e.py --base http://192.168.10.4:8080
    python ui_e2e.py --scenario auth      # 只跑登录/注册
    python ui_e2e.py --scenario library   # 目录与笔记
"""

import argparse
import os
import re
import subprocess
import sys
import time
import urllib.parse
import uuid

ADB = os.environ.get(
    "ADB",
    r"D:\DevelopmentTools\Android\Sdk\platform-tools\adb.exe",
)
PKG = "com.example.gleannote"
ACT = f"{PKG}/.MainActivity"
SHOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                        ".workbuddy-ai", "shots")
SHOT_DIR = os.path.abspath(SHOT_DIR)

_NODE = re.compile(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
_CLICKABLE = re.compile(r'clickable="true"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
_DESC = re.compile(r'content-desc="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def sh(*args, timeout=60):
    """执行 adb 命令，返回 stdout 文本。"""
    proc = subprocess.run([ADB, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)
    return proc.stdout or ""


def wait_device(timeout=180):
    """等待设备上线且系统启动完成。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if "device" in sh("devices") and sh("shell", "getprop", "sys.boot_completed").strip() == "1":
            return True
        time.sleep(3)
    return False


def dump():
    """导出当前界面层级，返回 [(text, (x1,y1,x2,y2))]。"""
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    raw = sh("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in _NODE.finditer(raw):
        text = m.group(1).strip()
        if text:
            out.append((text, tuple(int(m.group(i)) for i in range(2, 6))))
    return out


def clickables():
    """返回所有可点击区域的中心点，供无语义文本的按钮兜底定位。"""
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    raw = sh("shell", "cat", "/sdcard/ui.xml")
    pts = []
    for m in _CLICKABLE.finditer(raw):
        x1, y1, x2, y2 = (int(m.group(i)) for i in range(1, 5))
        pts.append(((x1 + x2) // 2, (y1 + y2) // 2))
    return pts


def tap(x, y):
    sh("shell", "input", "tap", str(x), str(y))
    time.sleep(0.6)


def tap_text(text, contains=True, timeout=8.0):
    """按文本定位并点击；contains=True 时做子串匹配。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t, (x1, y1, x2, y2) in dump():
            hit = (text in t) if contains else (t == text)
            if hit:
                tap((x1 + x2) // 2, (y1 + y2) // 2)
                return True
        time.sleep(0.5)
    return False


def type_text(value):
    """向当前焦点输入文本；空格用 %s 转义（input text 约定）。"""
    sh("shell", "input", "text", value.replace(" ", "%s"))
    time.sleep(0.5)


def clear_field():
    """清空当前输入框：移到行尾后连续退格。"""
    sh("shell", "input", "keyevent", "123")
    for _ in range(60):
        sh("shell", "input", "keyevent", "67")


def hide_ime():
    sh("shell", "input", "keyevent", "111")
    time.sleep(0.4)


def shot(name):
    os.makedirs(SHOT_DIR, exist_ok=True)
    path = os.path.join(SHOT_DIR, name if name.endswith(".png") else name + ".png")
    with open(path, "wb") as fp:
        proc = subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=subprocess.PIPE)
        fp.write(proc.stdout)
    return path


def has_text(text, timeout=6.0):
    """等待文本出现。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if any(text in t for t, _ in dump()):
            return True
        time.sleep(0.5)
    return False


class Report:
    def __init__(self):
        self.rows = []

    def check(self, step, ok, detail=""):
        self.rows.append((step, ok, detail))
        print(f"  [{'PASS' if ok else 'FAIL'}] {step}" + ("" if ok else f"  -> {detail}"))
        return ok

    def summary(self):
        bad = [r for r in self.rows if not r[1]]
        print("\n" + "=" * 70)
        print(f"界面走查：{len(self.rows)} 步，通过 {len(self.rows) - len(bad)}，失败 {len(bad)}")
        for step, _, detail in bad:
            print(f"FAIL {step} -> {detail}")
        print("=" * 70)
        return not bad


def launch_fresh():
    sh("shell", "am", "force-stop", PKG)
    sh("shell", "pm", "clear", PKG)
    sh("shell", "am", "start", "-n", ACT)
    time.sleep(4)


def scenario_auth(rep, email, password):
    """SCR-01 启动/登录 → 注册 → 进入笔记库。"""
    print("\n[A] 准入与注册")
    launch_fresh()
    rep.check("启动页显示产品名「拾页笔记」", has_text("拾页笔记"))

    if not tap_text("注册账号"):
        rep.check("进入注册页", False, "未找到「注册账号」")
        return False
    rep.check("进入注册页（出现验证码输入）", has_text("验证码"))

    # 邮箱
    for t, (x1, y1, x2, y2) in dump():
        if "邮箱" in t:
            tap((x1 + x2) // 2, (y1 + y2) // 2)
            break
    type_text(email)
    hide_ime()

    # 密码：用标签定位（注册页密码框标签较长）
    for t, (x1, y1, x2, y2) in dump():
        if "密码" in t:
            tap((x1 + x2) // 2, (y1 + y2) // 2)
            break
    type_text(password)
    hide_ime()

    # 协议勾选：复选框在文案左侧，点文字本身不会切换状态
    agreed = False
    for t, (x1, y1, x2, y2) in dump():
        if "我已阅读并同意" in t:
            tap(max(x1 - 45, 30), (y1 + y2) // 2)
            agreed = True
            break
    rep.check("勾选用户协议", agreed, "未找到协议文案")

    shot("ui_10_register_filled")
    if not tap_text("注册并登录"):
        rep.check("提交注册", False, "未找到注册按钮")
        return False

    ok = has_text("我的笔记", timeout=25) or has_text("笔记库", timeout=5) or has_text("新建", timeout=5)
    rep.check("注册成功进入笔记库（出现根目录「我的笔记」）", ok, "未出现笔记库特征文案")
    shot("ui_11_library")
    return ok


def scenario_library(rep, folder_name):
    """SCR-02 笔记库：新建目录、进入、新建空白笔记。"""
    print("\n[B] 目录与笔记列表")
    nodes = dump()
    rep.check("笔记库显示根目录", any("我的笔记" in t for t, _ in nodes), str(nodes[:6]))

    created = False
    for label in ("新建目录", "新建文件夹", "新建"):
        if tap_text(label):
            created = True
            break
    if not created:
        # 兜底：点击可点击区域里的 FAB（最靠右下的圆钮）
        pts = clickables()
        if pts:
            tap(*max(pts, key=lambda p: p[1] * 1000 + p[0]))
            created = True
    rep.check("触发新建目录入口", created)

    time.sleep(1)
    if has_text("目录名称", timeout=3) or has_text("名称", timeout=2):
        for t, (x1, y1, x2, y2) in dump():
            if "名称" in t:
                tap((x1 + x2) // 2, (y1 + y2) // 2)
                break
        type_text(folder_name)
        hide_ime()
        tap_text("确定") or tap_text("创建") or tap_text("保存")
        ok = has_text(folder_name, timeout=15)
        rep.check(f"新建目录「{folder_name}」出现在列表", ok)
    else:
        rep.check("新建目录弹窗出现", False, "未出现名称输入")
        ok = False
    shot("ui_12_folder_created")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://192.168.10.4:8080")
    ap.add_argument("--scenario", default="all",
                    choices=["all", "auth", "library"])
    ap.add_argument("--email", default=f"ui{int(time.time()) % 1000000}@example.com")
    ap.add_argument("--password", default="Passw0rd123")
    args = ap.parse_args()

    if not wait_device():
        print("设备未就绪：请先启动 AVD 并确认 adb devices 可见")
        return 2

    rep = Report()
    print(f"设备: {sh('devices').strip().splitlines()[-1]}\n账号: {args.email}")

    ok = True
    if args.scenario in ("all", "auth"):
        ok = scenario_auth(rep, args.email, args.password) and ok
    if args.scenario in ("all", "library") and ok:
        scenario_library(rep, "UI 测试目录")

    rep.summary()
    return 0 if rep.summary() else 1


if __name__ == "__main__":
    sys.exit(main())
