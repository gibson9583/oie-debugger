# OIE JavaScript Debugger

A headless Rhino debugger for Open Integration Engine with a dedicated Web Administrator screen. Version **1.0.0 is unreleased**.

The plugin uses the engine's Rhino, Jackson and authorization APIs and the Web Administrator's React and Monaco editor. No engine or web-client source changes are required, and no duplicate Rhino or React runtime is packaged.

## Features

- Executable-line breakpoints, hit counts, pause on entry or exceptions, and continue/step into/over/out.
- Exact compiled source, call frames, and bounded previews of locals, messages, E4X XML/XMLList and maps. Inspection does not invoke JavaScript getters or arbitrary Java conversion methods.
- Optional watches, evaluation, breakpoint conditions and variable editing on the paused worker, with separate permission and session opt-in.
- Channel, message, connector and source filters; channel/message actions; verified navigation to authored scripts and transformer steps.
- Owner/tab isolation, generation-checked commands, idempotent retries, pause limits and renewable ownership leases.

See the [HL7v2 playground](examples/hl7v2-debugger/README.md) for an importable channel and synthetic message.

## Build and install

Requires Java 17+, Maven, OIE 4.6.0 build artifacts or an installed distribution, Web Administrator plugin API 4.7 or newer, and access to Maven/npm dependency repositories. Maven installs a private Node runtime and builds/tests the browser bundle.

```sh
ENGINE_DIR=/path/to/oie bash scripts/install-engine-jars.sh
mvn clean package
```

Install `package/target/oie-debugger-1.0.0.zip` through the engine's extension installer, restart the engine, and reload Web Administrator. Automatic discovery requires native web-support endpoints or the Web Support extension.

The engine must use `rhino.optimizationlevel=-1`. Compatibility checks refuse interception when the engine internals or interpreter mode cannot be verified, or another enabled extension controls context factories. The build targets the engine's Rhino 1.7.13 API; validate compatibility again when upgrading the engine. Native Swing debug factories are excluded.

If channels compiled while the debugger was disabled, redeploy them after enabling it so their exact compiled sources can be captured.

## Debug a message

1. Open **Monitor → Debugger**, or use **Debug Channel** from the Channels page. Select a test channel and optional connector. Leave Message ID empty for a new message.
2. Keep **Break on entry** selected and click **Arm debugger**. Paste a payload under **Send a test message**, or use **Load HL7 sample**, then send it without leaving the screen.
3. When paused, select a **Script step** and choose **Run to selected step**. This sets an ordinary breakpoint at the step's first executable line and continues to it. Earlier breakpoints may stop first, and control flow may skip the step.
4. Use **Step over** to execute the highlighted assignment and inspect its result in **Variables and maps**. Continue through remaining stops until processing finishes.
5. Send another message using the same armed session, or choose **End session**. To change settings, end the session first; **Clear session** restores the options after a session has expired or closed.

Stay on the Debugger screen while debugging: navigation releases the session. Selecting an existing Message ID does not send or reprocess it. The inline sender uses the native message API and sends through all enabled destinations.

Break on entry applies to each newly claimed matching execution, including subsequent messages. Nested function calls do not add entry stops. A session owns one execution at a time; concurrent messages can run through while it is occupied.

Generated source remains authoritative and read-only. Authored mappings are navigation hints supplied only when verifiable. Monaco gutter breakpoints and accessible line controls both target the compiled source hash and generation. If a command response is lost, resolve its pending receipt before another command; retries reuse the original command ID.

## Access and configuration

Engine operation permissions and channel authorization govern access. With RBAC installed, administrators receive the debugger permissions automatically; other roles need **View JavaScript Debugger**, **Control JavaScript Debugger**, and, for expression features, **Evaluate JavaScript Debugger**.

The **JavaScript Debugger** extension properties can impose additional restrictions:

| Property | Meaning |
| --- | --- |
| `enabled` | `false` denies all debugger access. |
| `evaluationEnabled` | `false` disables expression features for everyone. New installations enable the feature, subject to permission and session opt-in. |
| `allowedUserIds` | Optional comma-separated numeric engine user IDs permitted to use the debugger. |
| `allowedChannelIds` | Optional comma-separated channel IDs; `*` removes this additional channel restriction. |
| `evaluationUserIds` | Optional additional user restriction for expression features. |
| `globalUserIds` | Optional additional user restriction for global/lifecycle debugging. |

Blank lists impose no additional restriction. Nonempty lists also restrict administrators and never grant otherwise-denied engine permissions. User wildcards are unsupported. Global/lifecycle access requires control permission, no engine channel restrictions, and explicit opt-in. A message filter never matches a script without message identity.

Manage these properties through the engine's standard extension properties API, for example `PUT /api/extensions/JavaScript%20Debugger/properties?mergeProperties=true`. Reinstallation preserves stored settings, including evaluation being disabled. Policy changes close existing sessions. Every operation rechecks authorization and session ownership.

## Execution limits

Pausing may hold a transaction or processing permit, and upstream timeouts can trigger retries. Use selected development/test traffic. The debugger cannot undo sends, roll back effects, replace running code, reverse execution or step Java/JVM instructions.

The ownership lease defaults to 15 seconds. The UI defaults to a 120-second maximum pause; the server default is 60 seconds and hard maximum is 120 seconds. Connection loss stops lease renewal. Channel stop/pause notifications close existing sessions so queued work can drain. To debug an undeploy script, stop the channel, arm a new lifecycle-enabled session, then undeploy.

Watches, conditions, evaluation and value edits execute JavaScript with engine privileges and may change data or call external systems. Their Rhino callback/time budget is best effort: tight loops without callbacks and Java/native calls cannot be forcibly cancelled.

Source, inspection and breakpoints belong to specific source/pause generations. Redeployment, resource changes, expired leases or changed permissions can require a new session. Exact source remains available while its compiled script is live; extra recent-source retention is capped at 256 records or 16 million characters. Live source memory scales with live compiled scripts. Dynamic `eval`/`Function` source access requires the owning tab's active pause. Shared-map previews are bounded observations, not an engine-wide consistent snapshot.

## Development

```sh
mvn -pl server -am test
cd package/webadmin
npm ci
npm test
npx playwright install --with-deps chromium
npm run test:browser
```

Tests cover real Rhino execution, source retention, lifecycle attachment, API permissions and ownership, source mapping, inspection and frontend ordering. See the [web plugin development guide](package/webadmin/README.md) for browser regressions. Development screenshots, deployment receipts and machine-specific validation logs are kept outside this repository.

## Releases and license

GitHub Actions builds and tests pull requests and `main`. Version tags run the same checks, sign both plugin JARs with SSL.com, and publish the installable ZIP with checksums. See [building and releasing](docs/releasing.md) for signing configuration and publication steps.

Licensed under the [Mozilla Public License 2.0](LICENSE).
