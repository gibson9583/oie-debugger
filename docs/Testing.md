# Debugger validation

Run `mvn -B -ntp verify`, then `npm run test:browser` from the webadmin package. JVM tests cover runtime ownership, lifecycle, authorization, receipt ordering, malformed commands and idle source observation across scopes, channel changes and source replacement. Browser regressions use mock host fixtures for deterministic UI races.

For channel authorization, grant debugger operations while restricting the account to channel A. A compiled script shared by channels A and B must report only the selected, authorized channel in both source-list and source-detail responses. Reading or arming B directly must fail even when the user knows its channel and source IDs. Recheck session reads, heartbeat, close, commands and both breakpoint paths after revocation; denied requests must release only their owned session. Arm and command retries must reauthorize before returning an old result. Global and lifecycle debugging still require unrestricted channel access. Authorization lookup failures must not return protected data or renew a lease.

The debugger's Channel dropdown must contain only channels returned by the host's authorized channel list, plus Global scripts when explicitly permitted. A saved target cannot add an excluded option or trigger source reads or Arm before access is confirmed. Returning focus to the window refreshes channels and capabilities; failed or superseded refreshes must not restore stale options. Test revocation during Arm's capability request, and keep existing session cleanup, unknown Arm recovery and in-flight message results usable while target access refreshes. The channel-access browser regression covers these UI states with deferred host responses; client tests cover request fencing and exact-request retries.

The installed-engine check uses the pinned OIE 4.6.0 tarball and Web Support 1.0.5 ZIP, including its real Web Administrator WAR. It installs Web Support and the candidate debugger through the engine's extension installer, restarts, verifies the disabled default, enables the extension and drives the documented debugger path in Chromium. It repeats after disabling, enabling and redeploying, and verifies browser-disconnect lease release, transformed/sent messages, edited values, and persisted audit event counts. Global Arm, control and close requests and their retries must retain an explicit `debugTarget=global` in the stored events, without expressions or request bodies.

With Java 17, Python 3.12+, Node and Playwright Chromium installed:

```sh
python3 scripts/test-engine.py \
  --engine /path/to/oie_unix_4_6_0.tar.gz \
  --web-support /path/to/websupport-1.0.5.zip \
  --package package/target/oie-debugger-1.0.0.zip \
  --output test-results/engine-run \
  --artifacts /path/to/shared-runtime-inputs/sha256 \
  --benchmark
```

Set `JAVA_HOME` to the JDK. The output directory must be new. The optional benchmark runs a function 100,000 times per message, with 40 samples per channel for enabled/disabled/enabled modes and the first 10 dropped. It tests one channel and four concurrent channels. Raw timings, script and end-to-end medians are saved. The integration regression threshold is less than twice the disabled script median; this is a regression ceiling, not a production latency guarantee.

The harness owns only a new temporary installation and Derby database. It binds to loopback and records process receipts. After processes exit, it captures engine logs, database rows and runtime hashes, deduplicates immutable JARs into the supplied artifact directory, and removes the installation and database. Evidence and shared inputs remain. Cleanup refuses to proceed if ownership, process exit or complete evidence capture cannot be established; the cleanup manifest identifies remaining paths.

Command protocol 2 uses a positive, increasing `commandSequence` scoped to the session. The client retries the exact request, including its sequence and command ID. The server retains 64 completed receipts plus bounded pending work and remembers the greatest accepted sequence. An absent receipt with an old sequence cannot execute again. The client may skip sequences after a rejected request. Reload the UI after upgrading; old clients without a sequence are rejected before mutation.

Review changes against the whole runtime flow: attachment and source discovery, Arm publication and release fences, per-frame session ownership, pause generation, receipt admission, worker execution and completion, lease expiry, and shutdown. Exercise the same mutation through both command POST and breakpoint PUT. Audit tests must use actual endpoint metadata and the engine's event encoder, and must check failed prerequisites as well as successful requests. Checking the encoder's input map alone does not establish what reaches the audit log.
