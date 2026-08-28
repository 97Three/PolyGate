from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import threading
import time

attempts = {}
attempt_lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, value, streaming=False):
        body = value.encode() if streaming else json.dumps(value).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream" if streaming else "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        if self.path == "/v1/models":
            self.reply(200, {"object": "list", "data": [{"id": "mock-model"}, {"id": "deepseek-flash"}]})
        else:
            self.reply(404, {"error": {"message": "mock endpoint not found"}})

    def do_POST(self):
        try:
            request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        except (ValueError, json.JSONDecodeError):
            self.reply(400, {"error": {"message": "invalid mock request"}})
            return
        if self.path.startswith('/unauthorized/'):
            self.reply(401, {"error": {"type": "authentication_error", "message": "mock credential rejected"}})
            return
        if self.path.startswith('/unavailable/'):
            self.reply(503, {"error": {"type": "overloaded_error", "message": "mock route unavailable"}})
            return
        if self.path in ("/v1/messages/count_tokens", "/messages/count_tokens"):
            self.reply(200, {"input_tokens": 3})
            return
        if self.path not in ("/v1/messages", "/v1/chat/completions"):
            self.reply(404, {"error": {"message": "mock endpoint not found"}})
            return
        payload = json.dumps(request)
        retry = re.search(r"retry503:([A-Za-z0-9_-]+)", payload)
        reject = "always503" in payload
        if retry:
            with attempt_lock:
                count = attempts.get(retry.group(1), 0)
                attempts[retry.group(1)] = count + 1
            reject = reject or count == 0
        if reject:
            self.reply(503, {"error": {"type": "overloaded_error", "message": "mock unavailable"}})
            return
        if "slow:" in payload:
            time.sleep(0.5)
        missing_usage = "missing_usage" in payload
        tool = "call_tool" in payload
        if "inspect_generation" in payload:
            text=json.dumps({k:request[k] for k in ("chat_template_kwargs","thinking_budget_tokens","temperature","min_p","enable_thinking","thinking","reasoning_effort") if k in request})
            self.reply(200,{"id":"chatcmpl-inspect","object":"chat.completion","model":request["model"],"choices":[{"index":0,"message":{"role":"assistant","content":text},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}})
            return
        if self.path == "/v1/chat/completions":
            self.openai(request, payload, missing_usage, tool)
        else:
            self.anthropic(request, payload, missing_usage, tool)

    def anthropic(self, request, payload, missing_usage, tool):
        content = ({"type": "tool_use", "id": "tool_1", "name": "echo", "input": {"value": "hi"}}
                   if tool else {"type": "text", "text": "hello"})
        if not request.get("stream"):
            response = {"id": "msg_mock", "type": "message", "role": "assistant", "model": request["model"],
                        "content": [content], "stop_reason": "tool_use" if tool else "end_turn"}
            if not missing_usage:
                response["usage"] = {"input_tokens": 3, "output_tokens": 1}
            self.reply(200, response)
            return
        message = {"id": "msg_mock", "type": "message", "role": "assistant", "model": request["model"], "content": []}
        if not missing_usage:
            message["usage"] = {"input_tokens": 3, "output_tokens": 0}
        block = dict(content)
        if tool:
            block["input"] = {}
        else:
            block["text"] = ""
        delta = ({"type": "input_json_delta", "partial_json": '{"value":"hi"}'}
                 if tool else {"type": "text_delta", "text": "hello"})
        final = {"type": "message_delta", "delta": {"stop_reason": "tool_use" if tool else "end_turn"}}
        if not missing_usage:
            final["usage"] = {"output_tokens": 1}
        frames = [
            ("message_start", {"type": "message_start", "message": message}),
            ("content_block_start", {"type": "content_block_start", "index": 0, "content_block": block}),
            ("content_block_delta", {"type": "content_block_delta", "index": 0, "delta": delta}),
            ("content_block_stop", {"type": "content_block_stop", "index": 0}),
            ("message_delta", final),
        ]
        if "truncate" not in payload:
            frames.append(("message_stop", {"type": "message_stop"}))
        body = "".join(f"event: {name}\ndata: {json.dumps(data)}\n\n" for name, data in frames)
        self.reply(200, body, streaming=True)

    def openai(self, request, payload, missing_usage, tool):
        arguments = json.dumps({"value": 1 if "PolyGate tool argument repair:" in payload else "hi"})
        call = {"id": "tool_1", "type": "function", "function": {"name": "echo", "arguments": arguments}}
        if not request.get("stream"):
            message = {"role": "assistant", "content": None if tool else "hello"}
            if tool:
                message["tool_calls"] = [call]
            response = {"id": "chat_mock", "object": "chat.completion", "model": request["model"],
                        "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if tool else "stop"}]}
            if not missing_usage:
                response["usage"] = {"prompt_tokens": 3, "completion_tokens": 1}
            self.reply(200, response)
            return
        delta = {"role": "assistant"}
        if tool:
            call["index"] = 0
            delta["tool_calls"] = [call]
        else:
            delta["content"] = "hello"
        frames = [
            {"id": "chat_mock", "object": "chat.completion.chunk", "model": request["model"], "choices": [{"index": 0, "delta": delta, "finish_reason": None}]},
            {"id": "chat_mock", "model": request["model"], "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls" if tool else "stop"}]},
        ]
        if not missing_usage:
            frames.append({"id": "chat_mock", "choices": [], "usage": {"prompt_tokens": 3, "completion_tokens": 1}})
        body = "".join(f"data: {json.dumps(value)}\n\n" for value in frames)
        if "truncate" not in payload:
            body += "data: [DONE]\n\n"
        self.reply(200, body, streaming=True)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 19090), Handler).serve_forever()
