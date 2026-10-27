# JavaScript Debugger

Version **1.0.4** supports **OIE 4.6.0 and 4.7.0**. Debugging is enabled by default, with an engine-wide enable/disable control in Web Administrator.

Pause channel JavaScript at breakpoints, step through execution, and inspect variables, maps, and expressions in OIE Web Administrator.

## Requirements

- OIE 4.6.0 or 4.7.0 and Java 17 or newer.
- Web Administrator with plugin API 4.7 or newer (provided by Web Administrator 1.0.1).
- The [Web Support extension](https://github.com/gibson9583/oie-web-support-plugin).

## Install or upgrade

Install the debugger ZIP, restart the engine, and reload Web Administrator. Debugging is enabled by default; explicit saved settings remain in effect on upgrade. Use **Enable debugger / Disable debugger** in **Monitor → Debugger** with **Manage JavaScript Debugger** permission. After enabling, redeploy the channels you want to debug.

While the debugger extension is loaded, the engine's generic extension-properties API requires the exact installed extension name. Debugger properties require **View JavaScript Debugger** to read and **Manage JavaScript Debugger** to change. The debugger screen's **Disable debugger** control keeps settings protection active. Disabling the extension in **Extensions** and restarting unloads its permissions and request filter.

Open **Monitor → Debugger**, choose a permitted channel, select **Break on entry**, and arm the debugger. Send a test message to pause and inspect its execution. Choose **End session** to release the execution.

Enabling debugging adds JavaScript observation across the engine, even without an armed session. Choose **Disable debugger** to stop observation and release paused executions engine-wide. Expression evaluation runs with the engine's permissions.

See the [usage guide](https://github.com/gibson9583/oie-debugger/wiki) for permissions, breakpoints, watches, value editing, and session recovery. See [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases) for changes and signed downloads.
