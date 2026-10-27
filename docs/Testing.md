# Debugger validation

Run `mvn -B -ntp verify`, then `npm run test:browser` from the webadmin package. JVM tests cover runtime ownership, lifecycle, authorization, receipt ordering, malformed commands and idle source observation across scopes, channel changes and source replacement. Browser regressions use mock host fixtures for deterministic UI races.

For channel authorization, grant debugger operations while restricting the account to channel A. A compiled script shared by channels A and B must report only the selected, authorized channel in both source-list and source-detail responses. Reading or arming B directly must fail even when the user knows its channel and source IDs. Recheck session reads, heartbeat, close, commands and both breakpoint paths after revocation; denied requests must release only their owned session. Arm and command retries must reauthorize before returning an old result. Global and lifecycle debugging still require unrestricted channel access. Authorization lookup failures must not return protected data or renew a lease.

The debugger's Channel dropdown must contain only channels returned by the host's authorized channel list, plus Global scripts when explicitly permitted. A saved target cannot add an excluded option or trigger source reads or Arm before access is confirmed. Returning focus to the window refreshes channels and capabilities; failed or superseded refreshes must not restore stale options. Test revocation during Arm's capability request, and keep existing session cleanup, unknown Arm recovery and in-flight message results usable while target access refreshes. The channel-access browser regression covers these UI states with deferred host responses; client tests cover request fencing and exact-request retries.

The installed-engine check uses the pinned OIE 4.6.0 tarball and Web Support 1.0.5 ZIP, including its real Web Administrator WAR. It installs Web Support and the candidate debugger through the engine's extension installer, restarts, verifies the enabled default, toggles the setting through the debugger UI and drives the documented debugger path in Chromium. It repeats after disabling, enabling and redeploying, and verifies browser-disconnect lease release, transformed/sent messages, edited values, and persisted audit event counts. Global Arm, control and close requests and their retries must retain an explicit `debugTarget=global` in the stored events, without expressions or request bodies.

With Java 17, Python 3.12+, Node and Playwright Chromium installed:

```sh
python3 scripts/test-engine.py \
  --engine /path/to/oie_unix_4_6_0.tar.gz \
  --web-support /path/to/websupport-1.0.5.zip \
  --package package/target/oie-debugger-1.0.4.zip \
  --output test-results/engine-run \
  --artifacts /path/to/shared-runtime-inputs/sha256 \
  --benchmark
```

Set `JAVA_HOME` to the JDK. The output directory must be new. The optional benchmark runs a function 100,000 times per message, with 40 samples per channel for enabled/disabled/enabled modes and the first 10 dropped. It tests one channel and four concurrent channels. Raw timings, script and end-to-end medians are saved. The integration regression threshold is less than twice the disabled script median; this is a regression ceiling, not a production latency guarantee.

The harness owns only a new temporary installation and Derby database. It binds to loopback and records process receipts. After processes exit, it captures engine logs, database rows and runtime hashes, deduplicates immutable JARs into the supplied artifact directory, and removes the installation and database. Evidence and shared inputs remain. Cleanup refuses to proceed if ownership, process exit or complete evidence capture cannot be established; the cleanup manifest identifies remaining paths.

Command protocol 2 uses a positive, increasing `commandSequence` scoped to the session. The client retries the exact request, including its sequence and command ID. The server retains 64 completed receipts plus bounded pending work and remembers the greatest accepted sequence. An absent receipt with an old sequence cannot execute again. The client may skip sequences after a rejected request. Reload the UI after upgrading; old clients without a sequence are rejected before mutation.

Review changes against the whole runtime flow: attachment and source discovery, Arm publication and release fences, per-frame session ownership, pause generation, receipt admission, worker execution and completion, lease expiry, and shutdown. Exercise the same mutation through both command POST and breakpoint PUT. Audit tests must use actual endpoint metadata and the engine's event encoder, and must check failed prerequisites as well as successful requests. Checking the encoder's input map alone does not establish what reaches the audit log.

## Engine-wide enable/disable behavior matrix

| State or trigger | Required behavior | Coverage |
| --- | --- | --- |
| Fresh install / absent enable property | Enabled; UI can Arm after choosing a channel | Policy and settings JVM tests; installed-engine scenario |
| Saved `enabled=false` on upgrade | Stay disabled; status and Enable control remain readable | Settings JVM and browser tests; restart persistence |
| Disable while paused | Persist false, close every owner's session, release message execution | Settings JVM and installed-engine UI scenario |
| Enable after disabling | Persist true; redeploy channels, then Arm successfully | Installed-engine UI scenario |
| No Manage JavaScript Debugger permission / revoked after render | Hide control; direct and engine-properties writes denied before saving | RBAC-keyed operation mapping, settings JVM and browser tests |
| Unknown extension identity / trailing-space alias | GET, HEAD and merge/replacement PUT return 404 before authorization or SQL; no durable or runtime mutation after restart | Request-filter JVM tests and both released OIE/RBAC stacks |
| Alternate encoding of the exact installed name / matrix parameter | Normal View/Manage authorization still applies | Installed-engine settings harness |
| Registry unavailable | Fail closed before any property read/write | Request-filter JVM test |
| Debugging disabled in settings | Identity guard still protects generic properties, including after restart | Installed-engine settings harness |
| Explicit installation restrictions | Preserve every unrelated property; exclude source/session access | Settings and servlet JVM tests |
| Invalid or extra request fields | Reject with 400; no writes | Settings JVM tests |
| Save fails or read-back cannot confirm | Return 500; keep prior runtime state | Settings JVM tests |
| Repeat current state / retry acknowledged write | No session closure on a no-op | Settings JVM tests; repeated REST requests |
| Lost write response | Show uncertainty, reload actual state; no automatic reverse write | Browser test |
| Overlapping refresh / disable during Arm | Ignore old capabilities and old Arm/source/session responses | Client and browser tests |
| Login/engine changes during write | Ignore reply and invalidate the old UI | Browser test |

The setting endpoint accepts exactly one JSON boolean and requires View and Manage
JavaScript Debugger. Manage also covers the engine's `setPluginProperties` operation
for this extension, and View covers `getPluginProperties`. The engine authorizes
extension operations as `<extension>#<operation>`, and RBAC allows operations without
a mapping. The settings tests use that lookup with the plugin's registered permissions,
so an unmapped check fails the test. The endpoint writes only the `enabled` database
property and reads it back before applying runtime settings. ConfigurationController's engine crypto and configuration types are
present only on the JVM test classpath; they are not bundled in the plugin ZIP.

## Released-engine settings authorization

The request filter is registered as a server API provider through the engine's
supported extension metadata. It binds only to the core extension-property GET/PUT
handlers, using their actual resource class and method, rather than matching a URL
string. It checks Jersey's decoded `extensionName` against the exact installed plugin
and connector metadata keys. Unknown names are rejected instead of trying to emulate
a database's string comparison. Canonical requests retain the engine's authentication,
RBAC permission checks, auditing and property application. This guard is supplied by
the loaded debugger extension; disabling debugger observation does not unload it.

CI runs `scripts/test-settings-rbac.py` against the released OIE 4.6.0/RBAC 1.1.4
and OIE 4.7.0/RBAC 1.2.0 distributions. It verifies input hashes, installs through
the real API, exercises the stock engine without RBAC, then upgrades debugger 1.0.3
with saved settings and existing roles. No engine or RBAC JARs are patched.

```sh
python3 scripts/test-settings-rbac.py --version 4.6.0 \
  --engine /path/to/oie_unix_4_6_0.tar.gz --rbac /path/to/rbac-1.1.4.zip \
  --old-package /path/to/oie-debugger-1.0.3.zip \
  --package package/target/oie-debugger-1.0.4.zip \
  --output test-results/settings-4.6.0 --artifacts /path/to/shared-runtime-inputs/sha256
```

For 4.7.0, use its engine archive and RBAC 1.2.0. The harness checks no-role,
empty-role, View, ordinary debugging, core extension management, Manage-only,
View+Manage and administrator accounts. Ten unknown-name variants are tested for
GET and both merge and replacement PUT; implicit HEAD and legitimate alternative
URL encodings are covered too. Denied writes must leave all properties unchanged
before and after restart. Same-login revocation/regrant, malformed requests,
concurrent identical disables, no-op retries and preserved installation restrictions
exercise interactions with the rest of the permission change. The regular browser
regressions cover stale render/action state and lost or reordered replies.

The harness retains HTTP receipts, behavior results, final configuration/event rows,
logs, process-exit receipts and content-addressed runtime inputs, then removes only
its owned engine and Derby database. Package verification also requires the guard's
provider registration and both compiled guard classes, so a packaging omission
cannot pass the release gate.
