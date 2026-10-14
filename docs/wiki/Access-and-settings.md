# Access and settings

Engine permissions determine which debugger tools a user can open and use. Extension properties provide an additional way to select the users and channels that participate in debugging.

These settings describe version 1.0.1. When upgrading from 1.0.0, reload Web Administrator to use the current command-retry protocol. Explicitly saved enabled settings remain in effect.

## Grant the required permissions

Use the engine's permission management to assign:

| Permission | Enables |
| --- | --- |
| **View JavaScript Debugger** | Opening the debugger and reading deployed sources for permitted channels. |
| **Control JavaScript Debugger** | Arming and managing the user's own debug sessions, pausing, stepping, inspecting paused values, and setting breakpoints. |
| **Evaluate JavaScript Debugger** | Expression evaluation, watches, conditional breakpoints, and local value editing. |
| **Send Message** | Submitting a message through **Send a message**. |

Give a debugging user View and Control, plus access to the channels they will work on. Add Evaluate for expression tools, and Send Message for the built-in sender. Existing engine channel permissions continue to determine channel access.

Expression tools also use the session's **Enable evaluation and value editing** checkbox. This lets the user choose those tools each time they arm a session.

## Configure the extension

Open the **JavaScript Debugger** extension properties in the engine's extension settings.

| Property | How to use it |
| --- | --- |
| `enabled` | Set to `true` to enable JavaScript observation and debugging. Default: `false`. Redeploy target channels after enabling. |
| `evaluationEnabled` | Set to `true` to make expression tools available to users with Evaluate permission. Default: `true`. |
| `allowedUserIds` | Enter comma-separated numeric engine user IDs to select users, for example `7,12`. |
| `allowedChannelIds` | Enter comma-separated channel IDs to select channels. Use `*` to include all channels allowed by the user's engine permissions. |
| `evaluationUserIds` | Enter comma-separated numeric engine user IDs to select who may use expression tools. |
| `globalUserIds` | Enter comma-separated numeric engine user IDs to select who may debug global and lifecycle scripts. |

An empty list delegates that choice to the engine's permissions. A populated list adds an installation-wide filter and applies to administrators as well. Use numeric user IDs in user lists and channel IDs in the channel list.

For example, `allowedUserIds=7,12` together with `evaluationUserIds=7` allows both selected users to use their granted debugger tools, while expression features are available to user `7` with Evaluate permission. Each user's engine permissions still apply.

After saving access-property changes, arm a fresh session to use the updated settings.

Enabling the debugger adds observation hooks across the engine, even without an armed session. Idle observation reuses scope observers and avoids repeated source-registry locking, but observation and active debugging still add processing cost. User and channel restrictions control access; they do not remove hooks from other channels. Set `enabled=false` outside debugging windows on shared or production engines. Disabling releases paused messages. Re-enable and redeploy the target channels before the next debugging session.

An explicitly saved `enabled=true` setting remains enabled after an upgrade. Reload Web Administrator after upgrading so commands use the current retry protocol.

## Choose a target execution

The **Target execution** form lets you narrow a session before arming:

| Setting | Use it for |
| --- | --- |
| **Channel** | The channel whose JavaScript you want to follow. |
| **Message ID (optional)** | A specific message that you will run through the engine's reprocessing tools. Leave empty when sending a fresh test message. |
| **Connector ID (optional)** | The source connector (`0`) or a particular destination connector ID. Leave empty to include matching connectors in the channel. |
| **Maximum pause (seconds)** | The duration available for each stop, from `1` to `120` seconds. The form starts at `120`. |
| **Break on entry** | A pause at the beginning of each matching script execution. |
| **Pause on exceptions** | A pause when JavaScript throws an exception, so you can inspect its frame. |
| **Enable evaluation and value editing** | Watches, expression evaluation, conditional breakpoints, and local variable edits for this session. |

Expand **Limit the script surface** to choose a **Script kind** or **Exact deployed source**. Use the offered kinds and source names to focus on a script that has run in your deployed channel.

Once armed, **Session settings** records the selections in use. Choose **End session**, or **Clear session** for an ended session, to return to the target form and choose a new target.

## Follow global or lifecycle scripts

Use an account with View and Control permissions, unrestricted engine channel access, and access through the `globalUserIds` setting.

Select a channel and **Include lifecycle and scripts without a message** to include execution contexts used by deploy, undeploy, and other scripts outside message processing.

To inspect a channel's **Deploy** script:

1. Prepare a test channel with its Deploy script saved and its status **Undeployed**.
2. Open **Monitor → Debugger**, clear any ended session, and select the channel.
3. Leave **Message ID**, **Connector ID**, and **Script kind** blank. Set **Exact deployed source** to **Any source in the target** so the session can follow the newly compiled deploy script.
4. Select **Include lifecycle and scripts without a message** and **Break on entry**, then choose **Arm debugger**.
5. Keep the Debugger tab open. In a second Web Administrator tab, deploy the channel.
6. Return to the Debugger tab to inspect and step through the Deploy script, then choose **Continue** to finish it.

After the channel finishes deploying, the session ends. Choose **Clear session** before arming for the next execution you want to follow.

For global scripts, select **Global scripts** in **Channel** and select **Include lifecycle and scripts without a message**. Arm a session for the global execution you want to inspect.

Keep the Debugger tab open and use a separate administration tab for engine actions. Global-script sessions follow the global target independently of individual channel sessions.

## Recognize the session state

- **Armed · Waiting for next execution**: the session is ready for another matching script. Send a test message or trigger the target execution.
- **Running · Waiting for the next stop**: execution is proceeding. Use **Pause next line** to request a stop.
- **Paused**: inspect the highlighted source location and values, then choose a stepping control or **Continue**.
- **Session ended**: choose **Clear session** to prepare another run.

Keep the Debugger page open in the same browser tab while working. The page maintains the session connection as you inspect and step. Choose **End session** to finish the session and release execution.

There is no lifetime command-count limit for a session. The server keeps the latest 64 completed command receipts plus pending work. The browser serializes commands and reuses the same sequence and request on a retry. A retry whose receipt has expired is rejected without executing again; end the session and arm a new one if its result cannot be recovered. Pause-duration and connection limits still apply.

Auditable requests record the action (such as inspect, evaluate, or set-value), session and channel once per HTTP request. A receipt poll is a separate request. Expressions, values and request bodies are excluded; an accepted queued request is not proof that its action completed. Internal debugger failures produce a first warning with sanitized stack information in the engine log, with repeat counts in capabilities.

The debugger also ends the session when its maximum pause is reached or its connection expires. Use **Clear session** to prepare a fresh run.

Return to [Debugging a message](./Debugging-a-message.md) for a complete example.
