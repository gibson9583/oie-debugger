#!/usr/bin/env python3
"""Debugger settings authorization against pinned, released OIE/RBAC stacks.

Owns and cleans a temporary engine/Derby installation; retains HTTP, database,
process and artifact evidence. Requires Java 17+ and Python 3.12+.
"""
import argparse
import concurrent.futures
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import ssl
import traceback
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

SOURCE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("engine", SOURCE / "scripts/test-engine.py")
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
VIEW = "View JavaScript Debugger"
CONTROL = "Control JavaScript Debugger"
EVALUATE = "Evaluate JavaScript Debugger"
MANAGE = "Manage JavaScript Debugger"
CAPS = "/extensions/debugger/capabilities"
ENABLE = "/extensions/debugger/enabled"
PROPS = "/extensions/JavaScript%20Debugger/properties"


def opener():
    return urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))


class SettingsRun(engine.EngineRun):
    def __init__(self, args):
        super().__init__(args)
        self.matrix = []

    def call(self, client, method, path, body=None, kind="application/json", accept="application/json", expected=200):
        payload = json.dumps(body).encode() if isinstance(body, (dict, list)) else body.encode() if isinstance(body, str) else body
        request = urllib.request.Request(self.url + "/api" + path, data=payload, method=method,
            headers={"Accept": accept, "Content-Type": kind, "X-Requested-With": "OpenIntegrationEngine"})
        try:
            with client.open(request, timeout=30) as response:
                status, raw = response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read().decode()
        if "_login" not in path:
            self.receipts.append({"method": method, "path": path, "status": status, "response": raw})
        assert status == expected, (method, path, status, expected, raw)
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return raw

    def login(self, username):
        client = opener()
        answer = self.call(client, "POST", "/users/_login", f"username={username}&password=SettingsTest123!Local",
            "application/x-www-form-urlencoded", "application/xml")
        assert ET.fromstring(answer).findtext("status") == "SUCCESS", answer
        return client

    def role(self, name, grants, role_id=None):
        data = ("<com.diridium.rbac.Role><name>" + escape(name) + "</name><description>Isolated settings validation</description><permissions>"
            + "".join("<string>" + escape(grant) + "</string>" for grant in grants)
            + "</permissions><channelIds/><isAdmin>false</isAdmin></com.diridium.rbac.Role>")
        path = "/extensions/rbac/roles" + (f"/{role_id}" if role_id else "")
        result = self.call(self.opener, "PUT" if role_id else "POST", path, data,
            "application/xml", "application/xml", expected=204 if role_id else 200)
        return role_id if role_id else int(ET.fromstring(result).findtext("id"))

    def user(self, name, grants=None):
        self.call(self.opener, "POST", "/users", f"<user><username>{name}</username></user>", "application/xml", expected=204)
        users = ET.fromstring(self.call(self.opener, "GET", "/users", accept="application/xml"))
        user_id = next(int(user.findtext("id")) for user in users.findall("user") if user.findtext("username") == name)
        self.call(self.opener, "PUT", f"/users/{user_id}/password", "SettingsTest123!Local", "text/plain", expected=204)
        role_id = None
        if grants is not None:
            role_id = self.role(name, grants)
            self.call(self.opener, "POST", f"/extensions/rbac/users/{user_id}/role/{role_id}", expected=204)
        return self.login(name), user_id, role_id

    def properties(self):
        root = ET.fromstring(self.call(self.opener, "GET", PROPS, accept="application/xml"))
        return {p.attrib["name"]: p.text or "" for p in root.findall("property")}

    def write_properties(self, client, values, expected=204):
        data = "<properties>" + "".join(f'<property name="{escape(k)}">{escape(v)}</property>' for k, v in values.items()) + "</properties>"
        return self.call(client, "PUT", PROPS + "?mergeProperties=true", data, "application/xml", expected=expected)

    def record(self, name, value):
        self.matrix.append({"case": name, "result": value})
        self.save("behavior-results.json", self.matrix)
        print(name + ": " + json.dumps(value), flush=True)

    def aliases(self, clients):
        before = self.properties()
        aliases = ["JavaScript%20Debugger%20", "JavaScript%20Debugger%20%20",
            "%20JavaScript%20Debugger", "javascript%20debugger", "JavaScript%20%20Debugger",
            "JavaScript%20Debugger%09", "JavaScript%20Debugger%C2%A0",
            "JavaScript%2520Debugger", "JavaScript%20Debugger%20;probe=1", "unknown-plugin"]
        payload = '<properties><property name="enabled">false</property></properties>'
        for client in [self.opener, *(entry[0] for entry in clients.values())]:
            for alias in aliases:
                path = "/extensions/" + alias + "/properties"
                self.call(client, "GET", path, accept="application/xml", expected=404)
                # Replacement must be rejected before deleting any canonical rows.
                for merge in ("true", "false"):
                    self.call(client, "PUT", path + "?mergeProperties=" + merge,
                        payload, "application/xml", expected=404)
            self.call(client, "HEAD", "/extensions/JavaScript%20Debugger%20/properties", expected=404)
        # Percent encoding and matrix parameters that decode to the exact installed
        # identity are allowed, with the ordinary permission check still in force.
        for name in ("JavaScript%20%44ebugger", "JavaScript%20Debugger;probe=1"):
            path = "/extensions/" + name + "/properties?propertyKeys=enabled"
            self.call(clients["view"][0], "GET", path, accept="application/xml")
            self.call(clients["emptyrole"][0], "GET", path, expected=403)
        assert self.properties() == before
        assert self.call(self.opener, "GET", CAPS)["configuredEnabled"] is True
        self.stop()
        self.start()
        assert self.properties() == before
        assert self.call(self.opener, "GET", CAPS)["configuredEnabled"] is True
        for name, (_, user_id, role_id) in clients.items():
            clients[name] = (self.login(name), user_id, role_id)
        self.record("all unknown identities rejected; merge and replace leave settings intact after restart",
            {"aliases": len(aliases), "identities": len(clients) + 1})

    def run(self):
        assert engine.sha(self.args.old_package) == "bd1ed9da107b14649da9a0f5807dddcab716d327cbf8fb244e9249321c31e061"
        assert engine.sha(self.args.rbac) == {"4.6.0": "7e3ce2a9c55c399b4f7db04e73a4b5d4d8dab8f9f9522d3d44c2ae0ed1d7749d",
            "4.7.0": "b622861432b16739bd2c9b94e30ba529ecf6c343addbe91ed57cfe53646f3488"}[self.args.version]
        self.prepare()
        self.save("release-inputs.json", {"rbacSha256": engine.sha(self.args.rbac),
            "oldDebuggerSha256": engine.sha(self.args.old_package)})
        self.start()
        self.install_zip(self.args.old_package)
        self.stop()
        self.start()
        # Retain the stock engine's allow-all contract without RBAC.
        self.install_zip(self.args.package)
        self.stop()
        self.start()
        assert self.call(self.opener, "GET", CAPS)["managementAllowed"] is True
        self.call(self.opener, "PUT", ENABLE, {"enabled": False})
        self.write_properties(self.opener, {"enabled": "true"})
        self.call(self.opener, "GET", PROPS, accept="application/xml")
        self.record("stock engine canonical read and both writes remain usable without RBAC", True)
        # Restore 1.0.3 before testing an actual upgrade with persisted settings/roles.
        self.install_zip(self.args.old_package)
        self.install_zip(self.args.rbac)
        self.stop()
        self.start()
        view, _, old_role = self.user("preupgrade", [VIEW])
        caps = self.call(view, "GET", CAPS)
        assert caps["managementAllowed"] is True
        self.call(view, "PUT", ENABLE, {"enabled": False})
        self.write_properties(view, {"settingsSentinel": "preserved", "enabled": "false"})
        self.record("1.0.3 View user can change both routes", True)
        self.install_zip(self.args.package)
        self.stop()
        self.start()
        props = self.properties()
        assert props["enabled"] == "false" and props["settingsSentinel"] == "preserved", props
        catalog = self.call(self.opener, "GET", "/extensions/rbac/permissions", accept="application/xml")
        assert MANAGE in catalog
        old = self.call(self.opener, "GET", f"/extensions/rbac/roles/{old_role}", accept="application/xml")
        assert MANAGE not in old
        view = self.login("preupgrade")
        assert self.call(view, "GET", CAPS)["managementAllowed"] is False
        self.call(view, "PUT", ENABLE, {"enabled": True}, expected=403)
        self.write_properties(view, {"enabled": "true"}, expected=403)
        self.record("upgrade preserves false, properties, roles; new Manage is discoverable", True)
        self.call(self.opener, "PUT", ENABLE, {"enabled": True})

        cases = [
            ("norole", None, False, False, False),
            ("emptyrole", [], False, False, False),
            ("view", [VIEW], True, False, False),
            ("debuggeruser", [VIEW, CONTROL, EVALUATE], True, False, False),
            ("coremanager", [VIEW, "manageExtensions"], True, False, False),
            ("manageonly", [MANAGE], False, True, False),
            ("viewmanage", [VIEW, MANAGE], True, True, True),
        ]
        clients = {}
        for name, grants, can_view, can_manage, can_toggle in cases:
            client, user_id, role_id = self.user(name, grants)
            clients[name] = (client, user_id, role_id)
            caps = self.call(client, "GET", CAPS, expected=200 if can_view else 403)
            if can_view:
                assert caps["managementAllowed"] is can_manage, caps
            self.call(client, "GET", PROPS, accept="application/xml", expected=200 if can_view else 403)
            self.call(client, "PUT", ENABLE, {"enabled": True}, expected=200 if can_toggle else 403)
            self.write_properties(client, {"settingsSentinel": "preserved"}, expected=204 if can_manage else 403)
            self.record(name, {"view": can_view, "manage": can_manage, "toggle": can_toggle})

        self.aliases(clients)

        client, _, role_id = clients["viewmanage"]
        assert self.call(client, "GET", CAPS)["managementAllowed"] is True
        self.role("viewmanage", [VIEW], role_id)
        self.call(client, "PUT", ENABLE, {"enabled": False}, expected=403)
        self.write_properties(client, {"enabled": "false"}, expected=403)
        assert self.call(client, "GET", CAPS)["managementAllowed"] is False
        assert self.properties()["enabled"] == "true"
        self.role("viewmanage", [MANAGE], role_id)
        self.call(client, "PUT", ENABLE, {"enabled": False}, expected=403)
        self.call(client, "GET", CAPS, expected=403)
        self.role("viewmanage", [VIEW, CONTROL, MANAGE], role_id)
        assert self.call(client, "GET", CAPS)["managementAllowed"] is True
        self.record("same-session revoke Manage, revoke View, regrant Manage", True)

        before = self.properties()
        for body in [{}, {"enabled": "false"}, {"enabled": None}, {"enabled": False, "allowedUserIds": ""}]:
            self.call(client, "PUT", ENABLE, body, expected=400)
        assert self.properties() == before
        self.record("malformed requests do not mutate settings", True)

        channel = ET.parse(SOURCE / "scripts/engine/demo-channel.xml").getroot()
        channel.find("id").text = str(uuid.uuid4())
        self.deploy(channel)
        second, _, _ = self.user("secondmanager", [VIEW, CONTROL, MANAGE])
        sessions = []
        for owner in (client, second):
            caps = self.call(owner, "GET", CAPS)
            session = self.call(owner, "POST", "/extensions/debugger/sessions", {
                "channelId": channel.findtext("id"), "tabId": "settings-tab", "requestId": str(uuid.uuid4()),
                "requestExpiresAt": caps["armRequestExpiresAt"]})
            sessions.append((owner, session["id"]))
        noop = self.call(client, "PUT", ENABLE, {"enabled": True})
        assert noop["configurationChanged"] is False
        for owner, sid in sessions:
            assert self.call(owner, "GET", f"/extensions/debugger/sessions/{sid}?tabId=settings-tab")["status"] == "armed"
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            replies = list(pool.map(lambda owner: self.call(owner, "PUT", ENABLE, {"enabled": False}), (client, second)))
        assert sorted(reply["configurationChanged"] for reply in replies) == [False, True], replies
        assert self.properties()["enabled"] == "false"
        self.call(client, "PUT", ENABLE, {"enabled": True})
        for owner, sid in sessions:
            assert self.call(owner, "GET", f"/extensions/debugger/sessions/{sid}?tabId=settings-tab")["status"] == "closed"
        self.record("no-op preserves sessions; concurrent disable applies once and closes all owners", True)

        self.write_properties(client, {"allowedUserIds": "1"})
        caps = self.call(client, "GET", CAPS)
        assert caps["userAllowed"] is False and caps["managementAllowed"] is True
        self.call(client, "PUT", ENABLE, {"enabled": False})
        assert self.properties()["allowedUserIds"] == "1"
        self.call(client, "GET", "/extensions/debugger/sources?channelId=" + channel.findtext("id"), expected=403)
        self.record("installation restrictions survive management; excluded user cannot read source", True)
        self.write_properties(self.opener, {"allowedUserIds": "", "enabled": "false"})
        self.stop()
        self.start()
        assert self.call(self.opener, "GET", CAPS)["configuredEnabled"] is False
        props = self.properties()
        assert props["enabled"] == "false" and props["settingsSentinel"] == "preserved"
        # The guard remains installed when debugger observation is disabled.
        self.call(self.opener, "GET", "/extensions/JavaScript%20Debugger%20/properties", expected=404)
        self.record("final restart preserves disabled setting and unrelated property", True)
        self.result.update(passed=True, cases=len(self.matrix))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", choices=["4.6.0", "4.7.0"], required=True)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--old-package", type=Path, required=True, help="Published debugger 1.0.3 ZIP")
    parser.add_argument("--rbac", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    args = parser.parse_args()
    engine.ENGINE_SHA = {"4.6.0": "7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea",
        "4.7.0": "ab08558873dc6be1e474afaacc46efe94d29c945cd3381c10d0ae548ecb429de"}[args.version]
    run = SettingsRun(args)
    try:
        run.run()
    except BaseException:
        run.result.update(passed=False, failure=traceback.format_exc())
        print(run.result["failure"], flush=True)
    finally:
        run.cleanup()
    return 0 if run.result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
