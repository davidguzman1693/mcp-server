#!/usr/bin/env python3
"""Smoke test: speaks MCP over stdio to the jar, like an AI client would."""
import json, subprocess, sys

jar, target_pid = sys.argv[1], int(sys.argv[2])
p = subprocess.Popen(["java", "-jar", jar], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
_id = 0

def send(method, params=None, notify=False):
    global _id
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None: msg["params"] = params
    if not notify:
        _id += 1; msg["id"] = _id
    p.stdin.write(json.dumps(msg) + "\n"); p.stdin.flush()
    if notify: return None
    while True:
        line = p.stdout.readline()
        if not line: raise SystemExit("server closed stdout")
        resp = json.loads(line)  # any non-JSON on stdout would fail here
        if resp.get("id") == _id: return resp

init = send("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "smoke", "version": "1"}})
print("server:", init["result"]["serverInfo"])
send("notifications/initialized", notify=True)
print("tools:", [t["name"] for t in send("tools/list")["result"]["tools"]])
print("prompts:", [t["name"] for t in send("prompts/list")["result"]["prompts"]])

def call(name, args):
    r = send("tools/call", {"name": name, "arguments": args})["result"]
    text = r["content"][0]["text"]
    print(f"\n== {name} {args} isError={r.get('isError')}\n{text[:600]}")

call("list_jvms", {})
call("heap_usage", {"pid": target_pid})
call("gc_stats", {"pid": target_pid})
call("hot_threads", {"pid": target_pid, "sampleMillis": 500, "limit": 3})
call("thread_dump", {"pid": target_pid, "state": "TIMED_WAITING", "maxFrames": 4})
call("find_deadlocks", {"pid": target_pid})
call("heap_usage", {"pid": 999999})
pr = send("prompts/get", {"name": "diagnose_jvm", "arguments": {"pid": str(target_pid)}})
print("\n== prompt:", json.dumps(pr)[:300])
p.terminate()
