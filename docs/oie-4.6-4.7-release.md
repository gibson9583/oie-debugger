# Debugger 1.0.4

- Patch update from 1.0.3, supporting OIE 4.6.0 and 4.7.0.
- Add the Manage JavaScript Debugger permission. Enable debugger / Disable debugger requires View JavaScript Debugger and Manage JavaScript Debugger. With the RBAC plugin, 1.0.3 let any user with View JavaScript Debugger use the control.
- Manage JavaScript Debugger also covers changes to the debugger's extension properties through the engine's extension API. View JavaScript Debugger covers reading them. With the RBAC plugin, earlier versions let any user with a role read and change them.
- Register an engine request filter that rejects unknown extension identities before the generic property handlers run. This closes the trailing-space RBAC/Derby bypass on released OIE 4.6.0 and 4.7.0 without an engine or RBAC upgrade. While the debugger extension is loaded, the generic properties API requires exact installed extension names for every extension, even when debugger observation is disabled.
- Existing roles do not include Manage JavaScript Debugger. Grant it to the users who enable or disable debugging. RBAC administrator roles keep full access.
- Keep compile baseline 4.6.0 and unchanged Web Administrator API requirements. Maven, engine descriptor, catalog and Web Administrator package versions are synchronized.

Validation includes JVM and JavaScript tests, all browser regressions, package checks, and installed-engine settings regressions against OIE 4.6.0/RBAC 1.1.4 and OIE 4.7.0/RBAC 1.2.0. The settings harness covers upgrade, permission changes, alias reads and merge/replacement writes, concurrency and restart persistence. CI runs both released stacks with pinned input hashes and preserves evidence after removing owned engines and databases.

See [Debugger validation](Testing.md) for the behavior matrix and checks. Signed release assets and release notes are published through [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases/tag/v1.0.4).
