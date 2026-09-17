#!/usr/bin/env python
"""飞书 Wiki 归档一键配置（NewsClaw 主线专用，幂等可重复运行）。

路线：**用户身份 OAuth**。飞书规定知识库（Wiki）的新建空间/节点必须由用户
身份发起，机器人 tenant token 会被拒（99991663）；因此流程是：

  1. 校验 .env 机器人凭证；
  2. 用户授权：未授权时打印授权链接并等待你在浏览器点同意（回调自动落盘令牌）；
  3. 以**用户身份**找到或创建名为「AI 早报」的知识空间；
  4. 空间 ID 写入 .env 的 FEISHU_WIKI_SPACE_ID；
  5. 真实归档冒烟：用户身份建 Wiki 节点（文档）+ markdown 转块 + 插入。

用法：
    cd /home/song/code/newsclaw && .venv/bin/python scripts/feishu_wiki_setup.py

前置（一次性）：飞书开发者后台
  · 「权限管理」为**用户身份**勾选 wiki:wiki、docx:document
    （offline_access 可选：本方案把机器人加为空间管理员后长期走应用身份，
     不再依赖 refresh token；若后台能勾上更好，勾不上可跳过）；
  · 「安全设置 → 重定向 URL」填 http://127.0.0.1:18900/api/feishu/oauth/callback
    （须与 .env 的 FEISHU_OAUTH_REDIRECT_URI 完全一致）；
  · 「版本管理与发布」创建版本并发布（只勾权限不发布不生效）。
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

import httpx  # noqa: E402

API = "https://open.feishu.cn/open-apis"
ENV_PATH = ROOT / ".env"


async def _tenant_token(client: httpx.AsyncClient) -> str:
    from newsclaw.config import settings

    app_id = (settings.feishu_app_id or "").strip()
    secret = (settings.feishu_app_secret or "").strip()
    if not app_id or not secret:
        print("✗ .env 缺少 FEISHU_APP_ID / FEISHU_APP_SECRET")
        sys.exit(2)
    resp = await client.post(
        f"{API}/auth/v3/tenant_access_token/internal",
        json={"app_id": app_id, "app_secret": secret},
    )
    data = resp.json()
    if data.get("code") != 0:
        print(f"✗ 获取 token 失败：{data.get('msg')}")
        sys.exit(2)
    return str(data["tenant_access_token"])


async def _ensure_user_authorized(timeout_seconds: int = 300) -> str:
    """确保完成用户授权，返回可用 user_access_token。"""
    from newsclaw.integrations import feishu_oauth

    if feishu_oauth.is_authorized():
        try:
            token = await feishu_oauth.user_access_token()
            status = feishu_oauth.authorization_status()
            left = status.get("refresh_days_left")
            left_txt = (
                f"refresh 剩余约 {left} 天"
                if left
                else f"用户令牌剩余 {status.get('access_minutes_left')} 分钟"
            )
            print(f"✓ 已有授权（{status.get('user_name') or '用户'}，{left_txt}）")
            return token
        except RuntimeError as exc:
            print(f"! 现有授权不可用：{exc}\n  将重新引导授权…")

    url = feishu_oauth.build_authorize_url(state="newsclaw-setup")
    print("\n请在浏览器打开以下链接并同意授权（授权后会自动继续）：\n")
    print(f"  {url}\n")
    print(f"（等待回调，最多 {timeout_seconds} 秒；回调地址：{feishu_oauth.redirect_uri()}）")

    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        await asyncio.sleep(3)
        if feishu_oauth.is_authorized():
            token = await feishu_oauth.user_access_token()
            status = feishu_oauth.authorization_status()
            print(f"✓ 授权完成：{status.get('user_name') or '用户'}")
            return token
    print(
        "✗ 等待授权超时。请确认：① 服务在运行（newsclaw serve）；"
        "② 重定向 URL 已在开发者后台登记；③ 已发布版本。"
    )
    sys.exit(4)


async def _find_space_as_user(client: httpx.AsyncClient, token: str, name: str) -> str:
    headers = {"Authorization": f"Bearer {token}"}
    resp = await client.get(f"{API}/wiki/v2/spaces", params={"page_size": 50}, headers=headers)
    data = resp.json()
    if data.get("code") != 0:
        print(f"✗ 以用户身份列知识空间失败：{str(data.get('msg'))[:200]}")
        sys.exit(3)
    for item in (data.get("data") or {}).get("items") or []:
        if item.get("name") == name:
            print(f"✓ 找到知识空间：{item.get('space_id')} ({name})")
            return str(item["space_id"])
    return ""


async def _create_space_as_user(client: httpx.AsyncClient, token: str, name: str) -> str:
    resp = await client.post(
        f"{API}/wiki/v2/spaces",
        json={"name": name, "description": "AI 早报每日产物归档（NewsClaw 自动生成）"},
        headers={"Authorization": f"Bearer {token}"},
    )
    data = resp.json()
    if data.get("code") != 0:
        print(f"✗ 创建知识空间失败：{str(data.get('msg'))[:200]}")
        sys.exit(3)
    space_id = str(((data.get("data") or {}).get("space") or {}).get("space_id") or "")
    print(f"✓ 已创建知识空间：{space_id} ({name})")
    return space_id


async def _grant_app_space_admin(client: httpx.AsyncClient, user_token: str, space_id: str) -> None:
    """用用户身份把机器人（应用）加为该知识空间管理员——一次性动作。

    飞书支持 ``member_type=appid``（官方「添加知识空间成员」接口）。把应用加为
    空间管理员后，机器人即可用**应用身份**长期创建节点，**不再依赖用户
    refresh_token**（也就绕开了 offline_access 权限在后台不可选的问题）。

    幂等：已在空间内（重复添加报错）视为成功。
    """
    from newsclaw.config import settings

    app_id = (settings.feishu_app_id or "").strip()
    resp = await client.post(
        f"{API}/wiki/v2/spaces/{space_id}/members",
        params={"need_notification": "false"},
        json={"member_type": "appid", "member_id": app_id, "member_role": "admin"},
        headers={"Authorization": f"Bearer {user_token}"},
    )
    data = resp.json()
    if data.get("code") == 0:
        print(f"✓ 已将机器人（{app_id}）加入知识空间并授予管理员")
        return
    msg = str(data.get("msg") or "")
    # 重复添加 / 已是成员：同样是我们想要的状态
    if "exist" in msg.lower() or "already" in msg.lower() or data.get("code") == 131007:
        print("✓ 机器人已在知识空间中（跳过重复添加）")
        return
    print(
        f"! 把机器人加为空间成员失败（code={data.get('code')}）：{msg[:160]}\n"
        "  影响：知识库归档将退回「每次用用户 token」的模式——只要用户授权有效仍可工作，"
        "但无法长期无人值守。建议检查开发者后台是否已开通 wiki:member:create。"
    )


def _write_env(key: str, value: str, comment: str = "") -> None:
    text = ENV_PATH.read_text(encoding="utf-8") if ENV_PATH.is_file() else ""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith(f"{key}="):
            if line.split("=", 1)[1].strip() == value:
                print(f"✓ .env 中 {key} 已是该值")
                return
            lines[i] = f"{key}={value}"
            ENV_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
            print(f"✓ .env 已更新 {key}")
            return
    if lines and lines[-1].strip():
        lines.append("")
    if comment:
        lines.append(f"# {comment}")
    lines.append(f"{key}={value}")
    ENV_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"✓ .env 已写入 {key}")


async def _smoke(space_id: str) -> None:
    """真实归档冒烟（用户身份）：建 Wiki 节点 → 写 markdown → 校验回执。"""
    import json as _json

    from newsclaw.tools.handlers.feishu_doc import FeishuDocHandler

    result = await FeishuDocHandler().handle(
        "feishu_doc",
        {
            "action": "create",
            "title": "AI 早报 · 归档联通测试",
            "markdown": (
                "# 归档联通测试\n\n这条文档由 setup 脚本创建，**可安全删除**。\n\n"
                "- 用户身份建 Wiki 节点\n- markdown 转原生块\n- 每日管线将复用此链路"
            ),
            "wiki_space_id": space_id,
        },
    )
    data = _json.loads(result)
    if not data.get("ok"):
        print(f"✗ 冒烟失败：{data.get('error')}")
        sys.exit(5)
    print(f"✓ 文档已创建（身份={data.get('identity')}）：{data.get('url')}")
    print(f"  写入 {data.get('blocks_inserted')} 个块 | wiki_node={data.get('wiki_node_token')}")
    if data.get("wiki_error"):
        print(f"! 但 Wiki 挂载未完成：{data['wiki_error'][:200]}")
        sys.exit(5)
    print(f"\n完成：重启服务后（cd {ROOT} && nohup .venv/bin/newsclaw serve &），")
    print("每日管线会把公众号稿自动归档进该知识空间，链接写入当天 manifest 的 wiki_entries。")


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--space-name", default="AI 早报")
    parser.add_argument("--auth-timeout", type=int, default=300)
    parser.add_argument(
        "--code",
        default="",
        help="可选：手动粘贴授权回调 URL 里的 code（回调页打不开时用）",
    )
    args = parser.parse_args()

    async with httpx.AsyncClient(timeout=45) as client:
        tenant = await _tenant_token(client)  # 顺带校验应用凭证可用
        print(f"✓ 应用凭证可用（tenant token {tenant[:10]}…）")
        if args.code:
            from newsclaw.integrations import feishu_oauth

            status = await feishu_oauth.exchange_code(args.code)
            print(f"✓ 已用 --code 完成授权：{status.get('user_name') or '用户'}")
        user_token = await _ensure_user_authorized(args.auth_timeout)
        space_id = await _find_space_as_user(client, user_token, args.space_name)
        if not space_id:
            space_id = await _create_space_as_user(client, user_token, args.space_name)
        await _grant_app_space_admin(client, user_token, space_id)
        _write_env(
            "FEISHU_WIKI_SPACE_ID",
            space_id,
            "飞书 Wiki 空间：早报产物自动归档到此（scripts/feishu_wiki_setup.py 写入）",
        )
        await _smoke(space_id)


if __name__ == "__main__":
    asyncio.run(main())
