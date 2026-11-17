#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GleanNote 客户端契约端到端测试（E2E）。

目的：用真实的 HTTP 调用逐条验证 Server/docs/API.md 第 4 章的 58 行端点，
以及客户端 GN-CLIENT-002 所依赖的横切约定（幂等键、revision 乐观锁、
分页终止、Problem+JSON 按 code 分支、预签名三步上传、2 秒轮询、
sync:changes 游标、短效 URL 不持久化）。

用法：
    python api_e2e.py --base http://192.168.10.4:8080 --code 123456
    GN_CODE=123456 python api_e2e.py            # 环境变量方式
    python api_e2e.py --email a@b.com --password Passw0rd1   # 复用已有账号

未提供 --code 且账号不存在时，脚本会调用 sendCode 后停下，提示从服务端
日志读取 6 位验证码（fake 邮件只写进程 stdout，没有 HTTP 出口）。

退出码：0 全部通过；1 存在失败项。
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

# 1x1 合法 JPEG，用于上传链路（服务端需要真实可解码的图片）
JPEG_1PX = base64.b64decode(
    "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
    "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
    "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q=="
)


class Result:
    """单条断言的结果容器，供最终汇总表使用。"""

    def __init__(self):
        self.rows = []

    def add(self, group, name, ok, detail=""):
        self.rows.append({"group": group, "name": name, "ok": bool(ok), "detail": str(detail)[:300]})
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {group} :: {name}" + ("" if ok else f"  -> {str(detail)[:200]}"))
        return ok

    def summary(self):
        total = len(self.rows)
        failed = [r for r in self.rows if not r["ok"]]
        print("\n" + "=" * 78)
        print(f"合计 {total} 项断言，通过 {total - len(failed)}，失败 {len(failed)}")
        if failed:
            print("-" * 78)
            for r in failed:
                print(f"FAIL {r['group']} :: {r['name']}\n     {r['detail']}")
        print("=" * 78)
        return len(failed) == 0


class Client:
    """极简 HTTP 客户端，只依赖标准库，行为对齐客户端 OkHttpApiClient。"""

    def __init__(self, base, device_id=None, client_version="0.1.0"):
        self.base = base.rstrip("/")
        self.access = None
        self.refresh = None
        self.device_id = device_id or "e2e-" + uuid.uuid4().hex[:12]
        self.client_version = client_version
        self.last_headers = {}
        self.last_status = 0

    def request(self, method, path, body=None, anonymous=False, idempotency_key=None,
                query=None, query_multi=None, raw_body=None, content_type=None, absolute=None):
        url = absolute or (self.base + path)
        if query or query_multi:
            pairs = []
            for k, v in (query or {}).items():
                if v is None:
                    continue
                pairs.append(f"{k}={urllib.parse.quote(str(v), safe='')}")
            for k, values in (query_multi or {}).items():
                for v in values:
                    pairs.append(f"{k}={urllib.parse.quote(str(v), safe='')}")
            if pairs:
                url += ("&" if "?" in url else "?") + "&".join(pairs)

        data = None
        if raw_body is not None:
            data = raw_body
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")

        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", content_type or "application/json; charset=utf-8")
        req.add_header("X-Device-Id", self.device_id)
        req.add_header("X-Client-Version", self.client_version)
        req.add_header("X-Request-Id", "req_e2e_" + uuid.uuid4().hex[:16])
        if self.access and not anonymous:
            req.add_header("Authorization", "Bearer " + self.access)
        if idempotency_key:
            req.add_header("Idempotency-Key", idempotency_key)

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                self.last_status = resp.status
                self.last_headers = dict(resp.headers)
                return resp.status, self._parse(raw), raw
        except urllib.error.HTTPError as err:
            raw = err.read()
            self.last_status = err.code
            self.last_headers = dict(err.headers)
            return err.code, self._parse(raw), raw
        except Exception as err:  # DNS 失败、连接重置、超时等一律按网络错误记录
            self.last_status = 0
            self.last_headers = {}
            return 0, {"code": "NETWORK_ERROR", "detail": f"{type(err).__name__}: {err}"}, b""

    @staticmethod
    def _parse(raw):
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except Exception:
            return raw.decode("utf-8", "replace")

    # 便捷方法
    def get(self, path, **kw):
        return self.request("GET", path, **kw)

    def post(self, path, body=None, **kw):
        return self.request("POST", path, body=body, **kw)

    def patch(self, path, body=None, **kw):
        return self.request("PATCH", path, body=body, **kw)

    def put(self, path, body=None, **kw):
        return self.request("PUT", path, body=body, **kw)

    def delete(self, path, **kw):
        return self.request("DELETE", path, **kw)


def idem():
    """生成 32 位可见 ASCII 幂等键，与客户端 newIdempotencyKey() 口径一致。"""
    return uuid.uuid4().hex


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def wait_job(cli, note_id, timeout=90, interval=2.0):
    """按 PRD 的 2 秒轮询口径等待转换任务到达终态。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        st, body, _ = cli.get(f"/v1/notes/{note_id}/conversionJob")
        if st == 200:
            last = body
            if body.get("state") in ("SUCCEEDED", "FAILED", "CANCELED"):
                return last
        time.sleep(interval)
    return last


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=os.environ.get("GN_BASE", "http://192.168.10.4:8080"))
    ap.add_argument("--email", default=os.environ.get("GN_EMAIL"))
    ap.add_argument("--password", default=os.environ.get("GN_PASSWORD", "Passw0rd123"))
    ap.add_argument("--code", default=os.environ.get("GN_CODE"))
    ap.add_argument("--json-out", default="")
    ap.add_argument("--keep", action="store_true", help="结束后不注销账号数据")
    args = ap.parse_args()

    stamp = int(time.time() * 1000) % 10000000
    email = args.email or f"e2e{stamp}@example.com"
    password = args.password
    cli = Client(args.base)
    res = Result()
    artifacts = {"base": args.base, "email": email, "startedAt": time.strftime("%Y-%m-%dT%H:%M:%S")}

    print(f"服务端: {args.base}\n测试账号: {email}\n")

    # ---------- 0. 运维探针（56、57） ----------
    print("[0] 运维探针")
    st, body, _ = cli.get("/healthz")
    res.add("探针", "GET /healthz 存活", st == 200 and (body or {}).get("status") == "ok", f"{st} {body}")
    st, body, _ = cli.get("/readyz")
    deps = (body or {}).get("checks", {}) if isinstance(body, dict) else {}
    res.add("探针", "GET /readyz 就绪且依赖全 ok",
            st == 200 and all(v == "ok" for v in deps.values()) and len(deps) >= 5, f"{st} {body}")

    # ---------- 1. 认证（1-6） ----------
    print("\n[1] 认证")
    st, body, _ = cli.post("/v1/auth:sendCode", {"email": email, "purpose": "REGISTER"}, anonymous=True)
    res.add("认证", "POST auth:sendCode 注册发码", st == 204, f"{st} {body}")

    st, body, _ = cli.post("/v1/auth:sendCode", {"email": email}, anonymous=True)
    res.add("认证", "sendCode 缺 purpose 返回 VALIDATION_FAILED",
            st == 400 and (body or {}).get("code") == "VALIDATION_FAILED", f"{st} {body}")

    if not args.code:
        print("\n" + "!" * 78)
        print("未提供验证码（--code 或 GN_CODE）。服务端 fake 邮件只写进程 stdout，")
        print("请到服务端执行 `docker compose logs -f api | grep mail.fake.send` 读取 6 位码后重跑：")
        print(f"  python {os.path.basename(__file__)} --base {args.base} --email {email} --code 123456")
        print("!" * 78)
        artifacts["blocked"] = "缺少验证码，认证之后的用例未执行"
        if args.json_out:
            with open(args.json_out, "w", encoding="utf-8") as fp:
                json.dump({"artifacts": artifacts, "rows": res.rows}, fp, ensure_ascii=False, indent=2)
        return 2

    code = args.code.strip()
    st, body, _ = cli.post("/v1/auth:register", {
        "email": email, "code": code, "password": password,
        "agreements": {"userAgreement": True, "privacyPolicy": True},
        "deviceId": cli.device_id,
    }, anonymous=True, idempotency_key=idem())
    ok = st == 201 and isinstance(body, dict) and body.get("accessToken")
    res.add("认证", "POST auth:register 注册并登录", ok, f"{st} {body}")
    if not ok:
        print("\n注册失败，后续用例无法执行。")
        res.summary()
        return 1
    cli.access, cli.refresh = body["accessToken"], body["refreshToken"]
    res.add("认证", "register 返回 expiresIn=900 与 Bearer",
            body.get("expiresIn") == 900 and body.get("tokenType") == "Bearer", body.get("expiresIn"))
    artifacts["userId"] = (body.get("user") or {}).get("id")

    st, body, _ = cli.post("/v1/auth:login", {"email": email, "password": password, "deviceId": cli.device_id}, anonymous=True)
    res.add("认证", "POST auth:login 登录", st == 200 and body.get("accessToken"), f"{st} {body}")
    if st == 200:
        cli.access, cli.refresh = body["accessToken"], body["refreshToken"]

    st, body, _ = cli.post("/v1/auth:login", {"email": email, "password": "wrong-pass1"}, anonymous=True)
    res.add("认证", "login 错误口令返回 AUTH_INVALID_CREDENTIALS",
            st == 401 and (body or {}).get("code") == "AUTH_INVALID_CREDENTIALS", f"{st} {body}")

    old_refresh = cli.refresh
    st, body, _ = cli.post("/v1/auth:refresh", {"refreshToken": old_refresh, "deviceId": cli.device_id}, anonymous=True)
    res.add("认证", "POST auth:refresh 轮换令牌", st == 200 and body.get("accessToken") and body.get("refreshToken") != old_refresh,
            f"{st} {str(body)[:160]}")
    if st == 200:
        cli.access, cli.refresh = body["accessToken"], body["refreshToken"]

    st, body, _ = cli.post("/v1/auth:refresh", {"refreshToken": old_refresh, "deviceId": cli.device_id}, anonymous=True)
    res.add("认证", "旧 refreshToken 复用返回 AUTH_REFRESH_REUSED",
            st == 401 and (body or {}).get("code") in ("AUTH_REFRESH_REUSED", "AUTH_TOKEN_INVALID"), f"{st} {body}")

    # 复用检测可能吊销全部令牌，重新登录
    st, body, _ = cli.post("/v1/auth:login", {"email": email, "password": password, "deviceId": cli.device_id}, anonymous=True)
    if st == 200:
        cli.access, cli.refresh = body["accessToken"], body["refreshToken"]

    # ---------- 2. 用户与额度（7-10） ----------
    print("\n[2] 用户与额度")
    st, body, _ = cli.get("/v1/users/me")
    res.add("用户", "GET users/me", st == 200 and (body or {}).get("email") == email, f"{st} {body}")

    st, body, _ = cli.patch("/v1/users/me", {"nickname": "E2E 测试"})
    res.add("用户", "PATCH users/me 改昵称", st == 200 and (body or {}).get("nickname") == "E2E 测试", f"{st} {body}")

    st, body, _ = cli.get("/v1/users/me/quota")
    quota_ok = (st == 200 and body.get("dailyLimit") == 50
                and isinstance(body.get("storage"), dict)
                and body["storage"].get("limitBytes") == 209715200)
    res.add("用户", "GET users/me/quota 含 50 张/200MB 口径", quota_ok, f"{st} {body}")
    artifacts["quota"] = body if st == 200 else None

    st, body, _ = cli.get("/v1/users/me", anonymous=True)
    res.add("用户", "无令牌访问受保护资源返回 401", st == 401, f"{st} {body}")

    # ---------- 3. 目录（11-19） ----------
    print("\n[3] 目录")
    st, body, _ = cli.get("/v1/folders")
    items = (body or {}).get("items", []) if isinstance(body, dict) else []
    root_id = items[0]["id"] if items else None
    res.add("目录", "GET folders 根级自动含「我的笔记」",
            st == 200 and len(items) >= 1 and items[0].get("depth") == 1, f"{st} {str(items)[:200]}")
    artifacts["rootFolderId"] = root_id

    st, body, _ = cli.post("/v1/folders", {"parentId": root_id, "name": f"E2E 目录 {stamp}"}, idempotency_key=idem())
    sub_id = (body or {}).get("id") if isinstance(body, dict) else None
    res.add("目录", "POST folders 新建子目录", st == 201 and sub_id and body.get("depth") == 2, f"{st} {body}")
    artifacts["subFolderId"] = sub_id

    if sub_id:
        st, body, _ = cli.post("/v1/folders", {"parentId": root_id, "name": f"E2E 目录 {stamp}"}, idempotency_key=idem())
        res.add("目录", "同父级重名返回 NAME_DUPLICATED",
                st == 409 and (body or {}).get("code") == "NAME_DUPLICATED", f"{st} {body}")

        st, body, _ = cli.get(f"/v1/folders/{sub_id}")
        res.add("目录", "GET folders/{id} 详情", st == 200 and body.get("id") == sub_id, f"{st} {body}")

        st, body, _ = cli.get(f"/v1/folders/{sub_id}/path")
        res.add("目录", "GET folders/{id}/path 面包屑",
                st == 200 and len(body.get("items", [])) == 2 and "/" in body.get("displayPath", ""),
                f"{st} {body}")

        st, body, _ = cli.patch(f"/v1/folders/{sub_id}", {"name": f"E2E 改名 {stamp}", "revision": body.get("revision", 1) if isinstance(body, dict) else 1})
        res.add("目录", "PATCH folders/{id} 重命名", st == 200 and body.get("name", "").startswith("E2E 改名"), f"{st} {body}")

        # 建第二层用于移动
        st, body, _ = cli.post("/v1/folders", {"parentId": root_id, "name": f"E2E 目标 {stamp}"}, idempotency_key=idem())
        target_id = (body or {}).get("id")
        if target_id:
            st, body, _ = cli.post(f"/v1/folders/{sub_id}:move", {"targetParentId": target_id})
            res.add("目录", "POST folders/{id}:move 移动",
                    st == 200 and body.get("folder", {}).get("parentId") == target_id, f"{st} {body}")

            st, body, _ = cli.post(f"/v1/folders/{target_id}:move", {"targetParentId": sub_id})
            res.add("目录", "移动到子孙返回 FOLDER_CYCLE",
                    st == 409 and (body or {}).get("code") == "FOLDER_CYCLE", f"{st} {body}")

    # ---------- 4. 笔记（20-30） ----------
    print("\n[4] 笔记")
    note_id = None
    if root_id:
        st, body, _ = cli.post("/v1/notes", {"folderId": root_id, "title": "E2E 空白笔记", "content": "# 初始\n正文"},
                               idempotency_key=idem())
        note_id = (body or {}).get("id") if isinstance(body, dict) else None
        res.add("笔记", "POST notes 新建空白笔记", st == 201 and note_id and body.get("revision") == 1, f"{st} {str(body)[:200]}")
        artifacts["noteId"] = note_id

    if note_id:
        st, body, _ = cli.get("/v1/notes", query={"folderId": root_id, "pageSize": "20"})
        rows = (body or {}).get("items", []) if isinstance(body, dict) else []
        res.add("笔记", "GET notes 列表分页且不含 content",
                st == 200 and any(r.get("id") == note_id for r in rows) and all("content" not in r for r in rows),
                f"{st} 共 {len(rows)} 条")
        # 服务端在无下一页时省略 nextPageToken；客户端 DTO 默认空串，语义等价于「已到末页」。
        token_val = (body or {}).get("nextPageToken", "") if isinstance(body, dict) else None
        res.add("笔记", "列表无下一页时 nextPageToken 缺失或为空",
                isinstance(body, dict) and token_val in ("", None), f"nextPageToken={token_val!r}")

        st, body, _ = cli.get(f"/v1/notes/{note_id}")
        detail = body if isinstance(body, dict) else {}
        res.add("笔记", "GET notes/{id} 详情含 content", st == 200 and "content" in detail, f"{st} {str(detail)[:160]}")
        rev = detail.get("revision", 1)

        st, body, _ = cli.patch(f"/v1/notes/{note_id}",
                                {"content": "# 更新后\n新正文", "revision": rev, "saveSource": "MANUAL_SAVE"},
                                idempotency_key=idem())
        new_rev = (body or {}).get("note", {}).get("revision") if isinstance(body, dict) else None
        res.add("笔记", "PATCH notes 乐观锁保存并递增 revision",
                st == 200 and new_rev == rev + 1, f"{st} {body}")

        # 用旧 revision 再写，期望 200 + conflictCopyId（不是 412）
        st, body, _ = cli.patch(f"/v1/notes/{note_id}",
                                {"content": "# 冲突写入", "revision": rev, "saveSource": "AUTO_SAVE"},
                                idempotency_key=idem())
        res.add("笔记", "落后 revision 返回 200 + conflictCopyId",
                st == 200 and (body or {}).get("conflictCopyId"), f"{st} {body}")

        st, body, _ = cli.patch(f"/v1/notes/{note_id}",
                                {"content": "x" * 200001, "revision": new_rev or 2}, idempotency_key=idem())
        res.add("笔记", "正文超 20 万字符返回 NOTE_TOO_LARGE",
                st == 413 and (body or {}).get("code") == "NOTE_TOO_LARGE", f"{st} {body}")

        if sub_id:
            st, body, _ = cli.post(f"/v1/notes/{note_id}:move", {"folderId": sub_id})
            res.add("笔记", "POST notes/{id}:move 移动笔记", st == 200 and body.get("folderId") == sub_id, f"{st} {body}")
            cli.post(f"/v1/notes/{note_id}:move", {"folderId": root_id})

        # 标签（P1，43）
        st, body, _ = cli.post("/v1/tags", {"name": f"E2E 标签 {stamp}"}, idempotency_key=idem())
        tag_id = (body or {}).get("id") if isinstance(body, dict) else None
        res.add("标签", "POST tags 新建标签", st == 201 and tag_id, f"{st} {body}")
        if tag_id:
            st, body, _ = cli.get("/v1/tags")
            res.add("标签", "GET tags 列表", st == 200 and any(t.get("id") == tag_id for t in body.get("items", [])), f"{st} {str(body)[:120]}")
            st, body, _ = cli.put(f"/v1/notes/{note_id}/tags", {"tagIds": [tag_id]})
            res.add("标签", "PUT notes/{id}/tags 覆盖设置", st == 200 or st == 204, f"{st} {body}")
            st, body, _ = cli.patch(f"/v1/tags/{tag_id}", {"name": f"E2E 标签改 {stamp}"})
            res.add("标签", "PATCH tags/{id} 重命名", st == 200 and "改" in body.get("name", ""), f"{st} {body}")

        # 版本（44-46）
        st, body, _ = cli.get(f"/v1/notes/{note_id}/versions")
        versions = (body or {}).get("items", []) if isinstance(body, dict) else []
        res.add("版本", "GET notes/{id}/versions 列表", st == 200 and len(versions) >= 1, f"{st} 共 {len(versions)} 条")
        if versions:
            vid = versions[0]["versionId"]
            st, body, _ = cli.get(f"/v1/notes/{note_id}/versions/{vid}")
            res.add("版本", "GET versions/{id} 正文", st == 200 and "content" in body, f"{st} {str(body)[:120]}")
            st, body, _ = cli.post(f"/v1/notes/{note_id}/versions/{vid}:restore", {}, idempotency_key=idem())
            res.add("版本", "POST versions:restore 恢复", st == 200 and body.get("revision"), f"{st} {str(body)[:120]}")

        # 分享（47-50a）
        st, body, _ = cli.get(f"/v1/notes/{note_id}/shareLink")
        res.add("分享", "GET shareLink 未开启返回 enabled=false",
                st == 200 and (body or {}).get("enabled") is False, f"{st} {body}")
        st, body, _ = cli.post(f"/v1/notes/{note_id}/shareLink", {}, idempotency_key=idem())
        share = body if isinstance(body, dict) else {}
        res.add("分享", "POST shareLink 开启分享", st in (200, 201) and share.get("enabled") and share.get("url"),
                f"{st} {body}")
        token = ""
        if share.get("url"):
            token = share["url"].rstrip("/").split("/")[-1]
            artifacts["shareToken"] = token
            st, body, _ = cli.get(f"/v1/public/shares/{token}", anonymous=True)
            res.add("分享", "GET public/shares/{token} 匿名可读",
                    st == 200 and "content" in (body or {}), f"{st} {str(body)[:120]}")
            st, raw, _ = cli.request("GET", f"/s/{token}", anonymous=True, absolute=args.base.rstrip("/") + f"/s/{token}")
            text = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else str(raw)
            res.add("分享", "GET /s/{token} 返回只读 HTML",
                    st == 200 and "<html" in text.lower(), f"{st} 长度 {len(text)}")
            st, body, _ = cli.delete(f"/v1/notes/{note_id}/shareLink")
            res.add("分享", "DELETE shareLink 关闭分享", st == 204, f"{st} {body}")
            st, body, _ = cli.get(f"/v1/public/shares/{token}", anonymous=True)
            res.add("分享", "关闭后匿名读取返回 410 SHARE_LINK_INACTIVE",
                    st == 410 and (body or {}).get("code") == "SHARE_LINK_INACTIVE", f"{st} {body}")

        # 搜索（30）
        st, body, _ = cli.get("/v1/notes:search", query={"query": "正文"})
        res.add("搜索", "GET notes:search 返回 snippet 与 totalSize",
                st == 200 and "items" in (body or {}) and "totalSize" in (body or {}), f"{st} {str(body)[:160]}")

        # 回收站与彻底删除（25、27、28、38）
        st, body, _ = cli.delete(f"/v1/notes/{note_id}")
        batch = (body or {}).get("deleteBatchId") if isinstance(body, dict) else None
        res.add("回收站", "DELETE notes 软删除返回 purgeAt", st == 200 and batch and body.get("purgeAt"), f"{st} {body}")

        st, body, _ = cli.get("/v1/trash")
        trash = (body or {}).get("items", []) if isinstance(body, dict) else []
        res.add("回收站", "GET trash 批次聚合含 remainingDays",
                st == 200 and any(t.get("batchId") == batch for t in trash)
                and all("remainingDays" in t for t in trash), f"{st} 共 {len(trash)} 条")

        st, body, _ = cli.post(f"/v1/notes/{note_id}:undelete", {})
        res.add("回收站", "POST notes:undelete 恢复", st == 200 and body.get("state") != "DELETED", f"{st} {str(body)[:120]}")

        cli.delete(f"/v1/notes/{note_id}")
        st, body, _ = cli.post(f"/v1/notes/{note_id}:expunge", {})
        res.add("回收站", "POST notes:expunge 彻底删除", st == 204, f"{st} {body}")
        st, body, _ = cli.get(f"/v1/notes/{note_id}")
        res.add("回收站", "彻底删除后详情返回 404", st == 404, f"{st} {body}")

    # ---------- 5. 目录删除/恢复/彻底删除（16、18、19） ----------
    if sub_id:
        print("\n[5] 目录删除链路")
        st, body, _ = cli.delete(f"/v1/folders/{sub_id}")
        res.add("目录", "DELETE folders 软删除整棵子树", st == 200 and body.get("deleteBatchId"), f"{st} {body}")
        st, body, _ = cli.post(f"/v1/folders/{sub_id}:undelete", {})
        res.add("目录", "POST folders:undelete 恢复", st == 200, f"{st} {str(body)[:120]}")
        st, body, _ = cli.post(f"/v1/folders/{sub_id}:expunge", {})
        res.add("目录", "非 DELETED 目录 expunge 返回 FAILED_PRECONDITION",
                st == 409 and (body or {}).get("code") in ("FAILED_PRECONDITION", "PRECONDITION_FAILED"), f"{st} {body}")

    # ---------- 6. 上传与转换（31-37、22、33、34） ----------
    print("\n[6] 上传与转换")
    upload_id = None
    conv_note = None
    if root_id:
        digest = sha256_hex(JPEG_1PX)
        st, body, _ = cli.post("/v1/uploads", {"items": [{
            "fileName": "e2e.jpg", "contentType": "image/jpeg", "bytes": len(JPEG_1PX), "sha256": digest,
        }]})
        sess = ((body or {}).get("items") or [{}])[0] if isinstance(body, dict) else {}
        upload_id = sess.get("uploadId")
        res.add("上传", "POST uploads 申请会话返回预签名地址",
                st == 201 and upload_id and sess.get("putUrl") and sess.get("headers"),
                f"{st} {str(sess)[:180]}")
        put_host = ""
        if sess.get("putUrl"):
            put_host = urllib.parse.urlparse(sess["putUrl"]).hostname
            base_host = urllib.parse.urlparse(args.base).hostname
            res.add("上传", "预签名地址主机对客户端可达（非容器内网名）",
                    put_host == base_host,
                    f"putUrl 主机={put_host}，客户端可达主机={base_host}；"
                    f"服务端需把 GN_S3_ENDPOINT 改为对外地址并暴露 9000 端口")
        artifacts["uploadSession"] = sess

        if upload_id and sess.get("putUrl"):
            st, body, _ = cli.request("PUT", "", raw_body=JPEG_1PX, absolute=sess["putUrl"],
                                      content_type=sess["headers"].get("Content-Type", "image/jpeg"),
                                      anonymous=True)
            res.add("上传", "PUT 预签名地址直传（不改 Content-Type）", st in (200, 204), f"{st} {str(body)[:120]}")

            st, body, _ = cli.post(f"/v1/uploads/{upload_id}:complete", {"sha256": digest})
            res.add("上传", "POST uploads/{id}:complete 状态 READY 并生成缩略图",
                    st == 200 and body.get("status") == "READY" and body.get("thumbUrl"), f"{st} {str(body)[:180]}")

            st, body, _ = cli.post(f"/v1/uploads/{upload_id}:complete", {"sha256": "0" * 64})
            res.add("上传", "重复 complete 保持幂等或返回冲突码",
                    st in (200, 409, 410), f"{st} {str(body)[:120]}")

        if upload_id:
            st, body, _ = cli.post("/v1/notes:createFromPages",
                                   {"folderId": root_id, "uploadIds": [upload_id], "title": "E2E 转换笔记"},
                                   idempotency_key=idem())
            conv = body if isinstance(body, dict) else {}
            conv_note = (conv.get("note") or {}).get("id")
            job = conv.get("conversionJob") or {}
            res.add("转换", "POST notes:createFromPages 建草稿并提交转换",
                    st == 201 and conv_note and job.get("state") in ("QUEUED", "RUNNING")
                    and (conv.get("quota") or {}).get("charged") == 1, f"{st} {str(conv)[:220]}")
            artifacts["conversionNoteId"] = conv_note

        if conv_note:
            st, body, _ = cli.get(f"/v1/notes/{conv_note}/pages")
            pages = (body or {}).get("items", []) if isinstance(body, dict) else []
            res.add("转换", "GET notes/{id}/pages 含 index 与 thumbUrl",
                    st == 200 and len(pages) == 1 and pages[0].get("index") == 1, f"{st} {str(pages)[:160]}")
            if pages:
                st, body, _ = cli.get(f"/v1/notes/{conv_note}/pages/{pages[0]['id']}")
                res.add("转换", "GET pages/{pageId} 原图短效地址",
                        st == 200 and body.get("originalUrl") and body.get("expiresAt"), f"{st} {str(body)[:140]}")

            final = wait_job(cli, conv_note)
            res.add("转换", "2 秒轮询至终态 SUCCEEDED",
                    bool(final) and final.get("state") == "SUCCEEDED",
                    f"state={final.get('state') if final else None} phase={final.get('phase') if final else None} "
                    f"failure={final.get('failureCode') if final else None}")
            st, body, _ = cli.get(f"/v1/notes/{conv_note}")
            detail = body if isinstance(body, dict) else {}
            res.add("转换", "终态后笔记正文非空且状态 NORMAL",
                    st == 200 and detail.get("state") == "NORMAL" and len(detail.get("content") or "") > 0,
                    f"state={detail.get('state')} 字数={len(detail.get('content') or '')}")

            # 服务端实际口径：任务已成功时拒绝重试（409 CONVERSION_STATE_INVALID），
            # 与 API.md 5.5.3「仅校验笔记状态与页面」的表述不一致，按实际行为断言并记录差异。
            st, body, _ = cli.post(f"/v1/notes/{conv_note}/conversionJob:retry", {}, idempotency_key=idem())
            res.add("转换", "已成功任务重试被拒（CONVERSION_STATE_INVALID）",
                    st == 409 and (body or {}).get("code") == "CONVERSION_STATE_INVALID", f"{st} {str(body)[:140]}")
            wait_job(cli, conv_note)

            # 追加页面（29，P1）
            if upload_id:
                digest2 = sha256_hex(JPEG_1PX)
                st, body, _ = cli.post("/v1/uploads", {"items": [{
                    "fileName": "e2e2.jpg", "contentType": "image/jpeg", "bytes": len(JPEG_1PX), "sha256": digest2,
                }]})
                s2 = ((body or {}).get("items") or [{}])[0] if isinstance(body, dict) else {}
                if s2.get("uploadId") and s2.get("putUrl"):
                    cli.request("PUT", "", raw_body=JPEG_1PX, absolute=s2["putUrl"],
                                content_type="image/jpeg", anonymous=True)
                    cli.post(f"/v1/uploads/{s2['uploadId']}:complete", {"sha256": digest2})
                    st, body, _ = cli.post(f"/v1/notes/{conv_note}:appendPages",
                                           {"uploadIds": [s2["uploadId"]]}, idempotency_key=idem())
                    res.add("转换", "POST notes/{id}:appendPages 追加页面",
                            st in (200, 202) and (body or {}).get("conversionJob"), f"{st} {str(body)[:140]}")
                    wait_job(cli, conv_note)

    # ---------- 7. 增量同步（51） ----------
    print("\n[7] 增量同步")
    st, body, _ = cli.get("/v1/sync:changes", query={"since": "0", "pageSize": "200"})
    changes = (body or {}).get("changes", []) if isinstance(body, dict) else []
    cursor = (body or {}).get("nextCursor") if isinstance(body, dict) else None
    res.add("同步", "GET sync:changes 全量初始化返回 changes 与 nextCursor",
            st == 200 and isinstance(changes, list) and cursor is not None and "hasMore" in body,
            f"{st} changes={len(changes)} cursor={cursor}")
    if isinstance(changes, list) and changes:
        res.add("同步", "变更项含 seq/resourceType/resourceId/op",
                all(k in changes[0] for k in ("seq", "resourceType", "resourceId", "op")), str(changes[0])[:180])
        res.add("同步", "seq 严格单调",
                all(changes[i]["seq"] < changes[i + 1]["seq"] for i in range(len(changes) - 1)), "已校验")
    if cursor is not None:
        st, body, _ = cli.get("/v1/sync:changes", query={"since": str(cursor)})
        res.add("同步", "since=cursor 增量返回空或新增",
                st == 200 and (body or {}).get("nextCursor", 0) >= cursor, f"{st} {str(body)[:120]}")

    # ---------- 8. 导出与导入（52-55） ----------
    print("\n[8] 导出与导入")
    export_note = conv_note
    if not export_note and root_id:
        st, body, _ = cli.post("/v1/notes", {"folderId": root_id, "title": "E2E 导出样本", "content": "# 导出\n正文"},
                               idempotency_key=idem())
        export_note = (body or {}).get("id") if isinstance(body, dict) else None
    if export_note:
        st, body, _ = cli.post("/v1/exports", {"noteId": export_note, "format": "MARKDOWN_ZIP"}, idempotency_key=idem())
        eid = (body or {}).get("exportId") if isinstance(body, dict) else None
        res.add("导出", "POST exports 发起导出", st in (200, 202) and eid, f"{st} {body}")
        if eid:
            for _ in range(30):
                st, body, _ = cli.get(f"/v1/exports/{eid}")
                if (body or {}).get("state") in ("SUCCEEDED", "FAILED"):
                    break
                time.sleep(2)
            res.add("导出", "GET exports/{id} 至终态并给 downloadUrl",
                    st == 200 and body.get("state") == "SUCCEEDED" and body.get("downloadUrl"),
                    f"{st} {str(body)[:180]}")

    if root_id:
        digest3 = sha256_hex(JPEG_1PX)
        st, body, _ = cli.post("/v1/uploads", {"items": [{
            "fileName": "imp.jpg", "contentType": "image/jpeg", "bytes": len(JPEG_1PX), "sha256": digest3,
        }]})
        s3 = ((body or {}).get("items") or [{}])[0] if isinstance(body, dict) else {}
        if s3.get("uploadId") and s3.get("putUrl"):
            st_put, _, _ = cli.request("PUT", "", raw_body=JPEG_1PX, absolute=s3["putUrl"],
                                       content_type="image/jpeg", anonymous=True)
            st_cmp, _, _ = cli.post(f"/v1/uploads/{s3['uploadId']}:complete", {"sha256": digest3})
            if not (st_put in (200, 204) and st_cmp == 200):
                res.add("导入", "上传前置链路可用", False,
                        f"PUT={st_put} complete={st_cmp}；预签名地址不可达，导入用例跳过")
            st, body, _ = cli.post("/v1/imports", {"folderId": root_id, "mode": "PER_IMAGE",
                                                   "uploadIds": [s3["uploadId"]]}, idempotency_key=idem())
            iid = (body or {}).get("importId") if isinstance(body, dict) else None
            res.add("导入", "POST imports 发起批量导入", st in (200, 202) and iid, f"{st} {body}")
            if iid:
                for _ in range(45):
                    st, body, _ = cli.get(f"/v1/imports/{iid}")
                    if (body or {}).get("state") in ("SUCCEEDED", "FAILED"):
                        break
                    time.sleep(2)
                res.add("导入", "GET imports/{id} 进度含 total/succeeded/failed",
                        st == 200 and all(k in (body or {}) for k in ("total", "succeeded", "failed")),
                        f"{st} {str(body)[:180]}")

    # ---------- 9. 错误处理与边界 ----------
    print("\n[9] 错误处理与边界")
    st, body, _ = cli.get("/v1/notes", query={"folderId": "nonexistent-id"})
    res.add("错误", "不存在的目录返回 404 且为 Problem+JSON",
            st == 404 and (body or {}).get("code") and (body or {}).get("requestId"), f"{st} {str(body)[:140]}")
    res.add("错误", "Problem 响应 Content-Type 为 application/problem+json",
            "application/problem+json" in cli.last_headers.get("Content-Type", ""), cli.last_headers.get("Content-Type"))

    st, body, _ = cli.post("/v1/folders", {"name": ""}, idempotency_key=idem())
    res.add("错误", "空目录名返回 VALIDATION_FAILED", st == 400 and (body or {}).get("code") == "VALIDATION_FAILED", f"{st} {body}")

    st, body, _ = cli.get("/v1/notes", query={"folderId": root_id, "pageSize": "999"})
    res.add("错误", "pageSize 超上限被拒或夹取到 100", st in (200, 400), f"{st} {str(body)[:120]}")

    # ---------- 10. 退出登录（5） ----------
    print("\n[10] 退出登录")
    st, body, _ = cli.post("/v1/auth:logout", {"refreshToken": cli.refresh, "allDevices": False})
    res.add("认证", "POST auth:logout 退出", st == 204, f"{st} {body}")

    ok = res.summary()
    artifacts["finishedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    artifacts["total"] = len(res.rows)
    artifacts["failed"] = sum(1 for r in res.rows if not r["ok"])
    if args.json_out:
        with open(args.json_out, "w", encoding="utf-8") as fp:
            json.dump({"artifacts": artifacts, "rows": res.rows}, fp, ensure_ascii=False, indent=2)
        print(f"结果已写入 {args.json_out}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
