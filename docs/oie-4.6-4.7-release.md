# Debugger 1.0.4

- Supports OIE 4.6.0 and 4.7.0, with Java 17 or newer and Web Administrator plugin API 4.7 or newer.
- **Enable debugger / Disable debugger** requires both **View JavaScript Debugger** and **Manage JavaScript Debugger**.
- Reading debugger extension properties requires View; changing them requires Manage.
- While the debugger extension is loaded, the engine's generic extension-properties API requires the exact installed extension name. The request filter covers all extensions' generic properties and remains active when observation is disabled through the debugger screen.
- Disabling the extension in **Extensions** and restarting unloads its permissions and request filter. The filter depends on the debugger being loaded and does not replace authorization in the engine and RBAC.
- Grant Manage to users who manage debugger settings. Existing roles do not gain it automatically on upgrade; RBAC administrator roles retain full access. Explicit saved settings remain in effect. Reload Web Administrator after upgrading.

Validation includes JVM and JavaScript tests, browser regressions, package checks, and installed-engine settings regressions against OIE 4.6.0/RBAC 1.1.4 and OIE 4.7.0/RBAC 1.2.0. The settings harness covers upgrade, read/write authorization, permission changes, concurrency and restart persistence. CI runs both released stacks with pinned input hashes and preserves evidence after removing owned engines and databases.

See [Debugger validation](Testing.md) for the behavior matrix and checks. Signed release assets and release notes are published through [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases/tag/v1.0.4).
