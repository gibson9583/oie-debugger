# JavaScript Debugger

Engine compatibility for the prepared 1.0.2 release: **OIE 4.6.0 and 4.7.0 only**. See the [release preparation notes](oie-4.6-4.7-release.md) for validation status.

Pause channel JavaScript at breakpoints, step through execution, and inspect variables, maps, and expressions in OIE Web Administrator.

## Requirements

- OIE 4.6.0 and Java 17 or newer.
- Web Administrator with plugin API 4.7 or newer (provided by Web Administrator 1.0.1).
- The [Web Support extension](https://github.com/gibson9583/oie-web-support-plugin).

## Install or upgrade

Install the debugger ZIP, restart the engine, and reload Web Administrator. Debugging is disabled by default; explicitly saved enabled settings remain enabled on upgrade. Set `enabled=true` in the JavaScript Debugger extension properties and redeploy the channels you want to debug.

Open **Monitor → Debugger**, choose a permitted channel, select **Break on entry**, and arm the debugger. Send a test message to pause and inspect its execution. Choose **End session** to release the execution.

Enabling debugging adds JavaScript observation across the engine, even without an armed session. Set `enabled=false` when finished, especially on shared or production engines. Expression evaluation runs with the engine's permissions.

See the [usage guide](https://github.com/gibson9583/oie-debugger/wiki) for permissions, breakpoints, watches, value editing, and session recovery. See [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases) for changes and signed downloads.
