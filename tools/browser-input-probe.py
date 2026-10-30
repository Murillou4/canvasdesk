"""Invoked by the bounded PowerShell runner; genuine OSR, no desktop input/audio."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, os, subprocess, sys, threading, time

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ["CANVASDESK_PROBE_OUTPUT"])
OUT.mkdir(parents=True, exist_ok=True)
DEADLINE = time.monotonic() + 90

class Page(BaseHTTPRequestHandler):
    def do_GET(self):
        body = b"<!doctype html><meta charset=utf-8><textarea id=probe style='position:absolute;left:20px;top:20px;width:700px;height:300px;font-size:22px'></textarea>"
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args): pass

http = ThreadingHTTPServer(("127.0.0.1", 0), Page)
threading.Thread(target=http.serve_forever, daemon=True).start()
session = OUT / "session"
profile = OUT / "profile"
url = f"http://127.0.0.1:{http.server_port}/"
command = [str(Path(os.environ["JAVA_HOME"]) / "bin/javaw.exe"),
    "-Dcanvasdesk.browserSilent=true", "-Dcanvasdesk.browserProbe=true",
    "-Dcanvasdesk.browserProfile=" + str(profile), "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.httpserver", "-jar", str(ROOT / "build/browser/canvasdesk-browser.jar"),
    str(ROOT / ".codex-temp/browser-native-152"), str(session), url, "web"]
log = (OUT / "worker.log").open("w", encoding="utf-8")
process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
    text=True, encoding="utf-8", creationflags=subprocess.CREATE_NO_WINDOW)
latest, events, stream, seq = {}, [], None, 0
send_lock = threading.Lock()
stop_updates = threading.Event()

def send(data):
    global seq
    with send_lock:
        seq += 1
        process.stdin.write(json.dumps({**data, "seq": seq}, ensure_ascii=False) + "\n")
        process.stdin.flush()

def poll():
    global stream
    if stream is None and (session / "events.jsonl").exists():
        stream = (session / "events.jsonl").open(encoding="utf-8")
    if stream:
        while True:
            pos = stream.tell()
            line = stream.readline()
            if not line: break
            if not line.endswith("\n"):
                stream.seek(pos); break
            event = json.loads(line)
            latest[event["kind"]] = event
            events.append(event)
    if "inputError" in latest: raise AssertionError(latest.get("testInputError", latest["inputError"]))
    if time.monotonic() > DEADLINE: raise TimeoutError("Native input probe exceeded 90 seconds")
    if process.poll() is not None: raise RuntimeError(f"Helper exited: {process.returncode}")

def wait(predicate):
    while True:
        poll()
        if predicate(): return
        time.sleep(.03)

def key(code, down=True, modifiers=0):
    send({"cmd": "key", "code": code, "down": down, "modifiers": modifiers})

def text(value): send({"cmd": "key", "code": 0, "down": True, "modifiers": 0, "text": value})

def assert_value(expected):
    latest.pop("inputProbe", None)
    send({"cmd": "probeInput"})
    wait(lambda: "inputProbe" in latest)
    actual = latest["inputProbe"]["value"]
    assert actual == expected, f"DOM input differs: expected {len(expected)} chars, actual {len(actual)}; prefix={actual[:60]!r}"

report = {"status": "RUNNING", "silent": True, "osInput": False}
try:
    wait(lambda: "ready" in latest and "loaded" in latest)
    send({"cmd": "state", "editable": True, "localInteractive": True, "state": {
        "mediaSession": "input-probe", "mediaRevision": 0, "navigationRevision": 0,
        "navigationUrl": url, "playing": False, "position": 0, "volume": 0, "audioAllowed": False}})
    send({"cmd": "mouse", "x": 100, "y": 60, "button": 0, "down": True, "wheel": 0})
    send({"cmd": "mouse", "x": 100, "y": 60, "button": 0, "down": False, "wheel": 0})
    wait(lambda: "textArea" in latest)
    def updates():
        while not stop_updates.wait(.1):
            try: send({"cmd": "audio", "allowed": False, "volume": 0})
            except Exception: return
    threading.Thread(target=updates, daemon=True).start()
    text("ação ç — 日本語🙂")
    key(10);key(10, False);text("segunda linha")
    assert_value("ação ç — 日本語🙂\nsegunda linha")
    key(17);key(65, True, 128);key(65, False, 128);key(17, False)
    value = "ação " * 600 + "fimX"
    for character in value: text(character)
    key(8);key(8, False)
    assert_value(value[:-1])
    key(17);key(65, True, 128);key(65, False, 128);key(17, False)
    latest.pop("clipboard", None);send({"cmd": "copy"});wait(lambda: "clipboard" in latest)
    assert latest["clipboard"]["text"] == value[:-1]
    stop_updates.set()
    latest.pop("audioGate", None);send({"cmd": "audio", "allowed": True, "volume": 1})
    wait(lambda: "audioGate" in latest and latest["audioGate"]["allowed"])
    assert latest["audioGate"]["silentTest"]
    latest.pop("audioGate", None);send({"cmd": "audio", "allowed": False, "volume": 0})
    wait(lambda: "audioGate" in latest and latest["audioGate"]["blocked"])
    send({"cmd": "resetInput"})
    assert_value(value[:-1])
    report.update(status="PASS", characters=len(value)-1, ready=latest["ready"], audioGate=latest.get("audioGate"),
        diagnostics=latest.get("diagnostics"), checks=["native textarea accents/unicode/newline", "Ctrl+A and ordered long text", "Backspace and focus reset", "native audio blocked before page load"])
except Exception as error:
    report.update(status="FAIL", error=str(error), events=events[-8:])
    raise
finally:
    stop_updates.set()
    (OUT / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    try: send({"cmd": "close"}); process.wait(timeout=4)
    except Exception: process.kill()
    http.shutdown()
    if stream: stream.close()
    log.close()
print(json.dumps(report, ensure_ascii=False))
