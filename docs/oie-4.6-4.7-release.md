# Debugger 1.0.3

- Patch update from 1.0.2, supporting OIE 4.6.0 and 4.7.0.
- Restore the enabled default for fresh installations. Explicit saved settings remain in effect on upgrade.
- Add Enable debugger / Disable debugger at the left of the execution buttons, using `PUT /api/extensions/debugger/enabled` with one explicit JSON boolean.
- Require View JavaScript Debugger and the engine's native Manage Extensions permission. Preserve independent user/channel access restrictions and other extension properties.
- Confirm persistence before updating runtime settings. Disabling releases paused executions across all owners; repeating the current setting preserves sessions.
- Keep compile baseline 4.6.0 and unchanged Web Administrator API requirements. Maven, engine descriptor, catalog and Web Administrator package versions are synchronized.

Validation includes 263 JVM tests, 72 JavaScript tests, 10 browser regression suites and 12 Python package tests. The exact unsigned candidate was installed and exercised on an isolated stock OIE 4.6.0 engine: enabled default, saved-disabled restart, UI/REST toggling, malformed requests, idempotence, paused-message release, re-enabling/redeploying, value edits, lease expiry, message completion and persisted audit events. Owned installations and databases were removed after process exit and evidence capture. The maintainer also verified the candidate on their OIE 4.7.0 installation.

See [Debugger validation](Testing.md) for the behavior matrix and checks. Signed release assets and release notes are published through [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases/tag/v1.0.3).
