#!/usr/bin/env python3
"""Install the candidate into an owned OIE/Derby instance and exercise the real UI.

Requires Java 17, Node/Playwright Chromium, and Python 3.12+. Inputs are read-only.
Never starts, stops, or modifies an existing engine. Evidence survives cleanup.
"""
import argparse
import concurrent.futures
import copy
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import ssl
import statistics
import subprocess
import tarfile
import tempfile
import time
import traceback
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET

ENGINE_SHA = "7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea"
WEB_SUPPORT_SHA = "ee7952e42bd1282727ee77cad529c0676557b1dc615c03548bd6676373214755"
REPO = Path(__file__).resolve().parent.parent


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def group_members(pid):
    table = subprocess.check_output(["ps", "-axo", "pid=,pgid=,command="], text=True)
    return [line for line in table.splitlines() if len(line.split(None, 2)) >= 2 and line.split(None, 2)[1] == str(pid)]


class EngineRun:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.root = None
        self.process = None
        self.console = None
        self.starts = 0
        self.browser_groups = []
        self.receipts = []
        self.owner = str(uuid.uuid4())
        self.result = {"passed": False}
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
            urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))

    def save(self, name, value):
        (self.output / name).write_text(json.dumps(value, indent=2) + "\n")

    def request(self, method, path, body=None, kind="application/json", accept="application/json", record=True):
        data = json.dumps(body).encode() if isinstance(body, (dict, list)) else body.encode() if isinstance(body, str) else body
        request = urllib.request.Request(self.url + "/api" + path, data=data, method=method,
                headers={"Accept": accept, "Content-Type": kind, "X-Requested-With": "OpenIntegrationEngine"})
        with self.opener.open(request, timeout=30) as response:
            text = response.read().decode()
            if record and "_login" not in path:
                self.receipts.append({"method": method, "path": path, "status": response.status, "response": text})
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return text

    def prepare(self):
        assert sha(self.args.engine) == ENGINE_SHA, "OIE archive checksum mismatch"
        assert sha(self.args.web_support) == WEB_SUPPORT_SHA, "Web Support checksum mismatch"
        self.root = Path(tempfile.mkdtemp(prefix="oie-debugger-integration-"))
        (self.root / ".owner").write_text(self.owner)
        with tarfile.open(self.args.engine) as archive:
            archive.extractall(self.root, filter="data")
        self.install = self.root / "oie"
        http_port, https_port = free_port(), free_port()
        while http_port == https_port:
            https_port = free_port()
        self.url = f"https://127.0.0.1:{https_port}"
        path = self.install / "conf/mirth.properties"
        properties = path.read_text()
        for key, value in {"http.port": http_port, "https.port": https_port, "http.host": "127.0.0.1",
                "https.host": "127.0.0.1", "dir.appdata": self.install / "appdata", "database": "derby",
                "database.url": "jdbc:derby:${dir.appdata}/mirthdb;create=true", "server.initialadminpassword": "admin",
                "server.startupdeploy": "false", "rhino.optimizationlevel": "-1"}.items():
            properties = re.sub(r"^\s*" + re.escape(key) + r"\s*=.*$", "", properties, flags=re.MULTILINE)
            properties += f"\n{key} = {value}"
        path.write_text(properties + "\n")
        shutil.copy2(path, self.output / "isolated-mirth.properties")
        self.save("inputs.json", {"engineSha256": ENGINE_SHA, "webSupportSha256": WEB_SUPPORT_SHA,
                "packageSha256": sha(self.args.package), "javaHome": os.environ["JAVA_HOME"]})

    def start(self):
        self.starts += 1
        self.console = (self.output / f"engine-console-{self.starts}.log").open("wb")
        self.process = subprocess.Popen([str(self.install / "oieserver")], cwd=self.install,
                stdout=self.console, stderr=subprocess.STDOUT, start_new_session=True,
                env=dict(os.environ, INSTALL4J_JAVA_HOME_OVERRIDE=os.environ["JAVA_HOME"]))
        self.save(f"process-start-{self.starts}.json", {"pid": self.process.pid, "cwd": str(self.install),
                "command": str(self.install / "oieserver"), "startedAt": time.time()})
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            assert self.process.poll() is None, "Engine exited during startup"
            try:
                self.request("POST", "/users/_login", "username=admin&password=admin", "application/x-www-form-urlencoded")
                return
            except (OSError, urllib.error.URLError):
                time.sleep(.5)
        raise AssertionError("Engine login readiness deadline exceeded")

    def stop(self):
        if self.process is None:
            return
        assert (self.root / ".owner").read_text() == self.owner, "Refusing to signal an unowned process"
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            self.process.wait(timeout=45)
        survivors = group_members(self.process.pid)
        self.save(f"process-exit-{self.starts}.json", {"pid": self.process.pid,
                "exitCode": self.process.returncode, "survivors": survivors})
        assert not survivors, "Owned process group still has descendants; preserving installation"
        self.console.close()
        self.process = None

    def install_zip(self, path):
        boundary = "debugger-" + uuid.uuid4().hex
        payload = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
                'Content-Type: application/zip\r\n\r\n').encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        self.request("POST", "/extensions/_install", payload, f"multipart/form-data; boundary={boundary}")

    def configure(self, enabled):
        properties = f'<properties><property name="enabled">{str(enabled).lower()}</property></properties>'
        self.request("PUT", "/extensions/JavaScript%20Debugger/properties?mergeProperties=true", properties, "application/xml")

    def deploy(self, channel):
        channel_id = channel.findtext("id")
        self.request("PUT", f"/channels/{channel_id}?override=true", ET.tostring(channel, encoding="unicode"), "application/xml")
        self.request("POST", f"/channels/{channel_id}/_deploy?returnErrors=true")

    def browser(self, channel_id, name):
        output = self.output / name
        output.mkdir()
        if self.starts > 2 or name != "browser":
            (output / "onboarding-complete").write_text("done")
        connection = {"baseUrl": self.url, "webadminUrl": self.url + "/oie-webadmin/",
                "channelId": channel_id, "attempt": str(output)}
        path = output / "connection.json"
        path.write_text(json.dumps(connection))
        with (output / "browser.log").open("w") as log:
            browser = subprocess.Popen(["node", str(REPO / "package/webadmin/test/engine.browser.mjs"), str(path)],
                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            self.browser_groups.append(browser.pid)
            self.save(name + "-process.json", {"pid": browser.pid, "startedAt": time.time()})
            try:
                assert browser.wait(timeout=180) == 0, "Browser scenario failed; inspect browser.log"
            finally:
                if browser.poll() is None:
                    os.killpg(browser.pid, signal.SIGTERM)
                    browser.wait(timeout=15)
                survivors = group_members(browser.pid)
                self.save(name + "-process-exit.json", {"pid": browser.pid, "exitCode": browser.returncode, "survivors": survivors})
                assert not survivors, "Browser process group still has descendants"
        result = json.loads((output / "browser-results.json").read_text())
        assert result["passed"], result
        for message_id in result["messageIds"]:
            text = self.request("GET", f"/channels/{channel_id}/messages/{message_id}", accept="application/xml")
            (output / f"message-{message_id}.xml").write_text(text)
            tree = ET.fromstring(text)
            assert tree.findtext("processed") == "true", text
            statuses = [element.text for element in tree.findall(".//status")]
            assert "TRANSFORMED" in statuses and "SENT" in statuses and "ERROR" not in statuses, statuses
            assert any(entry[0].text == "total" and float(entry[1].text) == 30
                    for entry in tree.findall(".//channelMapContent/content/entry")), text
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if self.request("GET", "/extensions/debugger/capabilities")["activeSessions"] == 0:
                break
            time.sleep(.25)
        else:
            raise AssertionError("Browser disappearance did not expire the paused lease")
        self.save(name + "-verified.json", {"passed": True, "editedMessages": result["messageIds"], "leaseReleased": True})

    def benchmark(self, channel):
        script = ('var begin = java.lang.System.nanoTime();\nfunction hot(n) {\nreturn n + 1;\n}\n'
                'var value = 0;\nfor (var i = 0; i < 100000; i++) value = hot(value);\n'
                'channelMap.put("benchmarkNs", java.lang.System.nanoTime() - begin);\n')
        channel.find("./sourceConnector/transformer/elements/*/script").text = script
        data, ratios = [], []

        def sample(channel_id):
            start = time.perf_counter()
            message = self.request("POST", f"/channels/{channel_id}/messages", "benchmark", "text/plain", record=False)
            elapsed = (time.perf_counter() - start) * 1000
            message_id = next(iter(message.values())) if isinstance(message, dict) else message
            tree = ET.fromstring(self.request("GET", f"/channels/{channel_id}/messages/{message_id}", accept="application/xml", record=False))
            values = [float(entry[1].text) / 1_000_000 for entry in tree.findall(".//channelMapContent/content/entry")
                    if entry[0].text == "benchmarkNs"]
            assert values, "Benchmark script timing missing"
            return {"scriptMs": values[0], "endToEndMs": elapsed, "messageId": message_id, "channelId": channel_id}

        for concurrency in [1, 4]:
            channels = [channel]
            for index in range(1, concurrency):
                other = copy.deepcopy(channel)
                other.find("id").text = str(uuid.uuid4())
                other.find("name").text = f"Debugger benchmark {index}"
                channels.append(other)
            modes = []
            for enabled in [True, False, True]:
                self.configure(enabled)
                for target in channels:
                    self.deploy(target)
                samples = []
                with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as workers:
                    for index in range(40):
                        for row in workers.map(sample, [target.findtext("id") for target in channels]):
                            samples.append(dict(row, sampleIndex=index))
                retained = [row for row in samples if row["sampleIndex"] >= 10]
                mode = {"enabled": enabled, "concurrentChannels": concurrency, "samples": samples, "warmupDroppedPerChannel": 10,
                        "medianScriptMs": statistics.median(row["scriptMs"] for row in retained),
                        "medianEndToEndMs": statistics.median(row["endToEndMs"] for row in retained)}
                data.append(mode)
                modes.append(mode)
                self.save("benchmark.json", data)
            ratios.append(max(modes[0]["medianScriptMs"], modes[2]["medianScriptMs"]) / modes[1]["medianScriptMs"])
        self.result["idleSlowdownByConcurrency"] = dict(zip(["1", "4"], ratios))
        assert max(ratios) < 2, "Idle debugger overhead regressed beyond the integration threshold"

    def global_audit(self):
        capabilities = self.request("GET", "/extensions/debugger/capabilities")
        options = {"channelId": "global", "includeGlobal": True, "tabId": "global-audit",
                "requestId": str(uuid.uuid4()), "requestExpiresAt": capabilities["armRequestExpiresAt"]}
        session = self.request("POST", "/extensions/debugger/sessions", options)
        recovered = self.request("POST", "/extensions/debugger/sessions", options)
        assert recovered["id"] == session["id"], "Arm retry created a different global session"
        path = f'/extensions/debugger/sessions/{session["id"]}'
        command = {"tabId": options["tabId"], "commandId": str(uuid.uuid4()), "commandSequence": 1,
                "expectedPauseGeneration": 0, "action": "pause", "expression": "PRIVATE AUDIT PAYLOAD"}
        receipt = self.request("POST", path + "/commands", command)
        assert self.request("POST", path + "/commands", command) == receipt, "Global command retry changed its receipt"
        for _ in range(2):
            assert self.request("DELETE", path + "?tabId=" + options["tabId"])["status"] == "closed"
        self.save("global-audit-requests.json", {"sessionId": session["id"],
                "actions": ["arm", "arm", "pause", "pause", "close", "close"]})

    def run(self):
        self.prepare()
        self.start()
        self.install_zip(self.args.web_support)
        self.install_zip(self.args.package)
        self.stop()
        self.start()
        settings = self.request("GET", "/extensions/JavaScript%20Debugger/properties", accept="application/xml")
        assert ET.fromstring(settings).find(".//property[@name='enabled']").text == "false", settings
        self.configure(True)
        capabilities = self.request("GET", "/extensions/debugger/capabilities")
        assert capabilities["available"] and capabilities["commandProtocol"] == 2, capabilities
        self.save("capabilities.json", capabilities)
        channel = ET.parse(REPO / "scripts/engine/demo-channel.xml").getroot()
        channel.find("id").text = str(uuid.uuid4())
        self.deploy(channel)
        self.browser(channel.findtext("id"), "browser")
        self.configure(False)
        self.configure(True)
        self.deploy(channel)
        self.browser(channel.findtext("id"), "browser-reenabled")
        self.global_audit()
        if self.args.benchmark:
            self.benchmark(channel)
        self.result["passed"] = True

    def cleanup(self):
        cleanup = {"installation": str(self.root), "removed": False}
        try:
            self.stop()
            for pid in self.browser_groups:
                assert not group_members(pid), f"Browser process group {pid} still active; resources retained"
            if self.root is not None:
                assert (self.root / ".owner").read_text() == self.owner
                if hasattr(self, "install") and self.install.exists():
                    if (self.install / "logs").exists():
                        shutil.copytree(self.install / "logs", self.output / "logs")
                    # Deduplicate immutable runtime inputs; retain hashes and observations.
                    cas = self.args.artifacts.resolve()
                    cas.mkdir(parents=True, exist_ok=True)
                    inputs = []
                    for path in sorted([*self.install.rglob("*.jar"), *self.install.rglob("*.war")]):
                        digest = sha(path)
                        artifact = cas / digest
                        if not artifact.exists():
                            shutil.copy2(path, artifact)
                        assert sha(artifact) == digest
                        inputs.append({"path": str(path.relative_to(self.install)), "sha256": digest})
                    self.save("runtime-inputs.json", inputs)
                    database = self.install / "appdata/mirthdb"
                    if database.exists():
                        derby = next(self.install.rglob("derby-*.jar"))
                        observed = subprocess.run([str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "-cp", str(derby),
                                str(REPO / "scripts/engine/ObserveDatabase.java"), str(database)],
                                cwd=self.output, capture_output=True, text=True, timeout=30)
                        (self.output / "database-observations.txt").write_text(observed.stdout + "\n" + observed.stderr)
                        assert observed.returncode == 0, "Final database observation failed; resources retained"
                        events = observed.stdout.partition("TABLE EVENT")[2]
                        message_rows = observed.stdout.partition("TABLE EVENT")[0]
                        processed = "PROCESSED=false" not in message_rows and "STATUS=E\n" not in message_rows and "STATUS=R\n" not in message_rows
                        self.save("message-row-verification.json", {"passed": processed})
                        if not processed:
                            self.result.update(passed=False, messageFailure="Unprocessed or failed message rows; see database observations")
                        requests = []
                        for path in self.output.glob("browser*/browser-http-receipts.json"):
                            requests.extend(json.loads(path.read_text()))
                        requests.extend(self.receipts)
                        commands = [item for item in requests if "/commands" in item.get("url", item.get("path", "")) and item["method"] == "POST"]
                        control_rows = [row for row in events.split("END ROW") if "NAME=Control debugger execution invoked through JavaScript Debugger" in row]
                        if commands:
                            passed = (len(control_rows) == len(commands)
                                    and all("<string>action</string>" in row for row in control_rows)
                                    and "quantity * unitPrice" not in "\n".join(control_rows))
                            self.save("audit-verification.json", {"passed": passed, "requests": len(commands), "events": len(control_rows)})
                            if not passed:
                                self.result.update(passed=False, auditFailure="Request/event counts or redaction differed; see audit-verification.json")
                        global_requests = self.output / "global-audit-requests.json"
                        if global_requests.exists():
                            expected = json.loads(global_requests.read_text())
                            global_events = []
                            for row in events.split("END ROW"):
                                encoded = row.partition("ATTRIBUTES=")[2].partition("\nUSER_ID=")[0]
                                if not encoded.strip():
                                    continue
                                attributes = {entry[0].text: (entry[1].text or "").strip()
                                        for entry in ET.fromstring(encoded).findall("entry")}
                                if attributes.get("debugTarget") == "global":
                                    global_events.append({"attributes": attributes, "success": "OUTCOME=SUCCESS\n" in row})
                            passed = (sorted(event["attributes"].get("action", "") for event in global_events) == sorted(expected["actions"])
                                    and all(event["success"] and "channel" not in event["attributes"]
                                        and "PRIVATE" not in str(event["attributes"]) for event in global_events)
                                    and all(event["attributes"].get("debugSessionId") == expected["sessionId"]
                                        for event in global_events if event["attributes"].get("action") != "arm"))
                            self.save("global-audit-verification.json", {"passed": passed, "events": global_events})
                            if not passed:
                                self.result.update(passed=False, globalAuditFailure="Global targets or request outcomes differed; see global-audit-verification.json")
                shutil.rmtree(self.root)
                cleanup["removed"] = not self.root.exists()
        except BaseException:
            cleanup["refused"] = traceback.format_exc()
            self.result["passed"] = False
        finally:
            self.save("http-receipts.json", self.receipts)
            self.save("cleanup.json", cleanup)
            self.save("result.json", self.result)
            print(json.dumps({"result": self.result, "cleanup": cleanup, "evidence": str(self.output)}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--web-support", type=Path, required=True)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--benchmark", action="store_true")
    args = parser.parse_args()
    run = EngineRun(args)
    try:
        run.run()
    except BaseException:
        run.result["failure"] = traceback.format_exc()
    finally:
        run.cleanup()
    return 0 if run.result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
