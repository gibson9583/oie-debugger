# Breakpoints and stepping

Breakpoints choose where execution stops. Stepping lets you follow what happens next, one JavaScript location at a time.

## Choose an initial stop

Select **Break on entry** before arming to pause at the beginning of each matching script execution. This is a useful starting point for a new channel: send a message, inspect the deployed source, then choose a more specific line or named script step.

For a focused session, enter **Connector ID** `0` to follow the source connector, or the destination's connector ID to follow that destination. You can further select a **Script kind** or **Exact deployed source** under **Limit the script surface**.

## Set a breakpoint

While a session is active, select the script in **Deployed source**. Click the gutter beside an executable line to toggle a breakpoint.

For a line-number entry:

1. Expand **Add breakpoint by line**.
2. Enter the line number shown in the deployed source.
3. Leave **Hit count** at `1` for an immediate stop when that line is reached.
4. Choose **Set breakpoint**.

The **Breakpoints** list shows the selected source's breakpoints. Use a breakpoint's checkbox to turn it on or off, or choose **Remove** to delete it. Breakpoints belong to the current debug session.

In the order example, place a breakpoint on:

```javascript
channelMap.put("total", total);
```

When execution reaches this line, `total` has already been calculated and is ready to inspect before being written to the channel map.

## Use a hit count

A **Hit count** of `3` starts pausing when the enabled breakpoint has been reached three times. It remains eligible to pause on later visits as well. This helps you examine a later loop iteration or a later matching execution within the same session.

To focus on breakpoint stops, end the current session and arm a new one with **Break on entry** cleared.

## Add a condition

Select **Enable evaluation and value editing** before arming to make the **Condition** field available. Enter an expression that is true when you want to pause, such as:

```javascript
total > 100
```

Place this condition on a line after `total` has been assigned. The expression uses the values in the frame that reaches the breakpoint. You can combine a condition with a hit count: the breakpoint begins checking its condition when the count has been reached.

Use simple value comparisons for conditions so each check answers the question you are investigating.

## Move through the script

| Control | What it does |
| --- | --- |
| **Continue** | Runs until another stop is reached or the current execution finishes. |
| **Step over** | Advances to the next executable line in the current function, running called functions along the way. |
| **Step into** | Advances to the next executable line and follows a JavaScript function call into that function. |
| **Step out** | Runs toward the caller and pauses when execution returns to a shallower frame. |
| **Pause next line** | Requests a pause at the next matching executable line while the session is armed or running. |
| **End session** | Releases the execution and ends the debug session. |

Active breakpoints and exception stops also apply while stepping. The status banner explains each stop, and **Call stack** shows the function frames available for inspection.

## Run to a named step

When **Script step** offers a named step, select it and choose **Run to selected step** while paused. The debugger places a breakpoint at its first executable line and continues toward that line.

For the walkthrough, choose **Calculate order total**. Once there, use **Step over** to follow the assignments and inspect their results. If another active breakpoint is reached along the way, **Continue** moves execution onward toward the selected step.

## Pause on an exception

Select **Pause on exceptions** before arming to inspect the frame where JavaScript throws an exception. At the stop, the status identifies the exception, and you can inspect variables and the call stack before choosing **Continue** to follow the script's exception handling.

The debugger also recognizes a JavaScript `debugger;` statement as a stop point in a matching execution.

## Work with an updated channel

Edit the channel's script in the channel editor, save, and redeploy. Then choose **Clear session** if an ended session is still displayed, arm again, and send a fresh test message. Select the new deployed source when setting breakpoints for the updated script.

Continue with [Inspecting values](./Inspecting-values.md) for scopes, watches, and expression tools.
