# OIE JavaScript Debugger

Debug JavaScript running in Open Integration Engine channels from Web Administrator.

## Features

- Pause at breakpoints, script entry, or exceptions.
- Continue execution or step into, over, and out of functions.
- Inspect deployed scripts, the call stack, variables, message content, and maps.
- Use watch expressions, conditional breakpoints, and variable editing with evaluation enabled.
- Send a message in the format expected by the selected channel.
- Limit debugging to a channel, message, connector, or script.

## Installation

Requires Java 17+, OIE 4.6.0, and Web Administrator plugin API 4.7 or newer. Plugin discovery requires native web-support endpoints or the Web Support extension.

1. Download the plugin ZIP from [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases).
2. Install it through the engine's extension installer.
3. Set `rhino.optimizationlevel=-1` in the engine configuration and restart the engine.
4. Reload Web Administrator and open **Monitor → Debugger**.

Redeploy channels that were compiled while the debugger was disabled so their scripts become available.

## Debug a message

1. Select a channel. Leave **Message ID** empty to send a new message.
2. Select **Break on entry** and click **Arm debugger**.
3. Enter your message under **Send a message**, then click **Send message**. The message runs through the selected channel and all enabled destinations.
4. When execution pauses, inspect **Variables and maps** and use the controls below.
5. Choose **End session** when finished. End or clear the session before changing its settings.

| Control | Action |
| --- | --- |
| Continue | Run until the next stop or the script finishes. |
| Step over | Advance to the next executable line in the current function, running called functions along the way. |
| Step into | Advance to the next executable line, entering a called JavaScript function when possible. |
| Step out | Run until the current function returns. |
| Pause next line | Request a pause at the next executable line. |
| Run to selected step | Set a breakpoint at the selected script step and continue toward it. Earlier breakpoints or control flow may stop execution elsewhere. |

Breakpoints and exceptions can interrupt stepping. **Break on entry** can pause separately in source and destination scripts, even without breakpoints. **Armed** means the debugger is waiting for another matching script; it does not confirm that the entire message has finished.

Stay on the Debugger screen while debugging; leaving it ends the session. A session debugs one execution at a time, so concurrent messages may run without pausing. The displayed source is read-only and comes from the deployed channel.

Selecting an existing **Message ID** does not send or reprocess that message. Choose **Arm for a new message** to remove that filter before sending a new message.

## Access and settings

Users need **View JavaScript Debugger** to inspect scripts, **Control JavaScript Debugger** to control execution, and **Evaluate JavaScript Debugger** for expression features. Sending requires **Send Message** permission. Existing channel permissions also apply.

The **JavaScript Debugger** extension properties provide additional restrictions:

| Property | Setting |
| --- | --- |
| `enabled` | Set to `false` to disable debugger access. |
| `evaluationEnabled` | Set to `false` to disable watches, conditional expressions, evaluation, and value editing. |
| `allowedUserIds` | Comma-separated numeric engine user IDs allowed to use the debugger. |
| `allowedChannelIds` | Comma-separated channel IDs, or `*` for no additional channel restriction. |
| `evaluationUserIds` | Additional user restriction for expression features. |
| `globalUserIds` | Additional user restriction for global and lifecycle scripts. |

Blank lists add no restriction. Nonempty lists also restrict administrators and do not grant engine permissions. User lists do not support wildcards. Global and lifecycle debugging requires unrestricted channel access, control permission, and session opt-in. Evaluation must also be enabled for the session. Changing access settings closes existing sessions.

## Limits

Use development or test traffic. Pausing can hold transactions and cause upstream timeouts or retries. Pauses last at most 120 seconds; a lost connection also releases paused execution after the session expires.

Watches, conditions, and value edits run JavaScript with engine privileges and may change data or call external systems. Blocking Java calls and scripts without debugger callbacks cannot always be interrupted. The debugger cannot undo processing, reverse execution, or step through Java code. Shared maps may change while another worker runs.

Licensed under the [Mozilla Public License 2.0](LICENSE).
