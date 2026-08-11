"""完整链路测试：后端代理 → Agent → LLM，含记忆层 + 任务层验证。"""

import json

import httpx

BASE = "http://localhost:8080/api/v1"


def login():
    r = httpx.post(
        f"{BASE}/auth/login", json={"username": "agenttest", "password": "test1234"}
    )
    return r.json()["data"]["token"]


def main():
    token = login()
    headers = {"Authorization": f"Bearer {token}"}

    # 1. 创建 session
    r = httpx.post(f"{BASE}/agent/chat/sessions", headers=headers, json={})
    sid = r.json()["data"]["id"]
    print(f"[1] 创建 session: {sid}")

    # 2. 测试记忆层：让 Agent 记住用户偏好
    print("\n[2] 测试记忆层：'记住我平时用英文文档'")
    msg = {"message": "记住我平时用英文文档，我是数据分析师"}
    with httpx.stream(
        "POST",
        f"{BASE}/agent/chat/sessions/{sid}/messages",
        headers=headers,
        json=msg,
        timeout=60,
    ) as resp:
        print(f"    status={resp.status_code}")
        full = ""
        for line in resp.iter_lines():
            if line.startswith("data: ") and line != "data: [DONE]":
                event = json.loads(line[6:])
                if event["type"] == "text":
                    full += event["content"]
                elif event["type"] == "tool":
                    print(f"    [tool] {event['name']}: {str(event['result'])[:60]}")
        print(f"    回复: {full[:120]}")

    # 3. 验证记忆已写入 Redis（通过 agent 内部接口）
    print("\n[3] 验证记忆写入（直接查 agent Redis）")
    prof = httpx.get(f"{BASE}/auth/profile", headers=headers).json()["data"]
    uid = prof["id"]
    print(f"    user_id = {uid}")
    import redis

    r = redis.from_url("redis://localhost:6379/0")
    mem = r.lrange(f"user:{uid}:memory", 0, -1)
    print(f"    user {uid} 记忆: {mem}")

    # 4. 验证任务层消息已持久化
    r2 = httpx.get(f"{BASE}/agent/chat/sessions/{sid}/messages", headers=headers)
    msgs = r2.json()["data"]
    print(f"\n[4] 任务层消息数: {len(msgs)}")
    for m in msgs:
        print(f"    role={m['role']} content={m['content'][:50]}")


if __name__ == "__main__":
    main()
