# OIE JavaScript Debugger

Follow a message through an Open Integration Engine channel, pause at the code you want to understand, and see how its values change. The debugger brings deployed JavaScript, execution controls, the call stack, variables, and message sending together in Web Administrator.

Start with [Getting started](./Getting-started.md), then follow [Debugging a message](./Debugging-a-message.md). The walkthrough uses a small JSON order and calculates its total, so you can compare the input, local variables, and channel map at each step.

## What you can do

- Pause when a script starts, at a chosen line, or when an exception occurs.
- Step through statements and into or out of JavaScript functions.
- Follow the deployed source and select named script steps.
- Inspect local variables, global variables and maps, XML values, and the current object.
- Evaluate expressions, keep a list of watches, and try a different local value during a pause.
- Focus a session on a channel, message, connector, script kind, or exact deployed source.
- Send a test message directly from the debugger.

## Guides

| Guide | Use it to |
| --- | --- |
| [Getting started](./Getting-started.md) | Install the plugin, check access, and prepare a sample channel. |
| [Debugging a message](./Debugging-a-message.md) | Arm a session, send an order, inspect the calculation, and finish processing. |
| [Breakpoints and stepping](./Breakpoints-and-stepping.md) | Choose where to pause and move through the code. |
| [Inspecting values](./Inspecting-values.md) | Explore scopes, evaluate expressions, add watches, and edit a local variable. |
| [Access and settings](./Access-and-settings.md) | Set permissions, choose execution filters, and configure installation properties. |

## Open the debugger

In Web Administrator, choose **Monitor → Debugger**. You can also use **Debug Channel** from a channel action or **Debug this message** from a message action to open the debugger with the relevant target already selected.

Use a development or test channel for the walkthrough, and keep the Debugger page open while you work. Choose **End session** when you are ready to let the message continue through the channel.

The plugin ZIP is available from [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases). Project source and issue reporting are in the [OIE Debugger repository](https://github.com/gibson9583/oie-debugger).
