#!/usr/bin/env python3
"""
ClaudeDriver managed-session companion (Claude Agent SDK).

Runs a Claude Code session under the Agent SDK and bridges it to the on-machine ClaudeDriver agent
over line-delimited JSON on stdio, so the operator can answer arbitrary questions and read the full
transcript.

Bridge protocol
  companion -> agent (stdout, one JSON object per line):
    {"kind":"transcript","role":"assistant|user|tool|system","text":"..."}
    {"kind":"question","questionId":"<uuid>","text":"..."}
    {"kind":"ended"}
  agent -> companion (stdin, one JSON object per line):
    {"kind":"answer","questionId":"<uuid>","text":"..."}
    {"kind":"cancel","questionId":"<uuid>"}

Environment: CLAUDEDRIVER_SESSION_ID, CLAUDEDRIVER_INSTRUCTION, and the usual Claude auth
(ANTHROPIC_API_KEY or a logged-in Claude Code). Requires `pip install claude-agent-sdk`.

NOTE: this file is validated at deploy/CI time with the real SDK + credentials; it is not run in the
build environment. The agent's tests use a fake companion speaking the same protocol.
"""
import asyncio
import json
import os
import sys
import uuid


def emit(obj: dict) -> None:
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


async def read_answer(question_id: str) -> str | None:
    """Block until the agent sends an answer/cancel for question_id; return the text or None (cancel)."""
    loop = asyncio.get_event_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            return None
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            continue
        if msg.get("questionId") != question_id:
            continue
        if msg.get("kind") == "cancel":
            return None
        if msg.get("kind") == "answer":
            return msg.get("text")


async def main() -> None:
    instruction = os.environ.get("CLAUDEDRIVER_INSTRUCTION", "")
    try:
        # Import lazily so the bridge protocol is documented even without the SDK installed.
        from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions  # type: ignore
    except Exception as e:  # pragma: no cover - deploy/CI only
        emit({"kind": "transcript", "role": "system", "text": f"Agent SDK unavailable: {e}"})
        emit({"kind": "ended"})
        return

    async def can_use_tool(tool_name, tool_input, context):  # pragma: no cover - deploy/CI only
        """Escalate a tool-permission as a question; allow on answer, deny on cancel."""
        qid = str(uuid.uuid4())
        emit({"kind": "question", "questionId": qid, "text": f"Allow {tool_name}? {json.dumps(tool_input)}"})
        answer = await read_answer(qid)
        return {"behavior": "allow" if answer is not None else "deny"}

    options = ClaudeAgentOptions(can_use_tool=can_use_tool)  # type: ignore
    async with ClaudeSDKClient(options=options) as client:  # pragma: no cover - deploy/CI only
        await client.query(instruction)
        async for message in client.receive_response():
            text = getattr(message, "text", None) or str(message)
            role = getattr(message, "role", "assistant")
            emit({"kind": "transcript", "role": role, "text": text})
            # A free-form question posed by the model surfaces as an assistant message ending in '?';
            # the operator's answer is fed back as the next query turn.
            if isinstance(text, str) and text.rstrip().endswith("?"):
                qid = str(uuid.uuid4())
                emit({"kind": "question", "questionId": qid, "text": text})
                reply = await read_answer(qid)
                if reply is None:
                    break
                await client.query(reply)

    emit({"kind": "ended"})


if __name__ == "__main__":
    asyncio.run(main())
