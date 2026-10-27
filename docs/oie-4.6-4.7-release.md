# Debugger 1.0.4

- Patch update from 1.0.3, supporting OIE 4.6.0 and 4.7.0.
- Add the Manage JavaScript Debugger permission. Enable debugger / Disable debugger requires View JavaScript Debugger and Manage JavaScript Debugger. With the RBAC plugin, 1.0.3 let any user with View JavaScript Debugger use the control.
- Manage JavaScript Debugger also covers changes to the debugger's extension properties through the engine's extension API. View JavaScript Debugger covers reading them. With the RBAC plugin, earlier versions let any user with a role read and change them.
- Existing roles do not include Manage JavaScript Debugger. Grant it to the users who enable or disable debugging. RBAC administrator roles keep full access.
- Keep compile baseline 4.6.0 and unchanged Web Administrator API requirements. Maven, engine descriptor, catalog and Web Administrator package versions are synchronized.

Validation includes 265 JVM tests, 72 JavaScript tests and the enable/disable browser regression. New settings tests apply RBAC's operation lookup to the registered permissions. They fail on 1.0.3 and pass on 1.0.4.

See [Debugger validation](Testing.md) for the behavior matrix and checks. Signed release assets and release notes are published through [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases/tag/v1.0.4).
