# HL7v2 debugger playground

[Importable channel](channel.xml) · [Synthetic sample message](sample.hl7)

Import `channel.xml` into a development OIE 4.6.0 engine, then deploy and start it. If you already imported this example, update that channel or assign a new ID when importing another copy. The fixed fixture ID is `b9aec86d-6244-4489-a0b5-edb2c12fe871`.

**Debugger - HL7v2 Playground** uses a Channel Reader and one Channel Writer whose target is **None**. It opens no listener and routes to no other channel or external system. Both transformers use HL7v2, and development message storage retains transformed content and maps for inspection. Importing the example does not change debugger permissions or enable evaluation.

The sample uses fictional patient `doe^JANE`, ID `SYNTHETIC-001`, and carriage-return segment separators.

| Connector | ID | JavaScript step | Expected result |
| --- | --- | --- | --- |
| Source | `0` | Normalize patient family name | PID.5.1 becomes `DOE`; channel map contains `originalFamilyName=doe` and `normalizedFamilyName=DOE`. |
| Destination | `1` | Stamp receiving application | MSH.5 becomes `DEBUGGER_DEMO`; connector map contains `debuggerSummary=Prepared synthetic patient: DOE`. |

## Walkthrough

Install the debugger and a Web Administrator host supporting plugin API 4.7 or newer, as described in the [main README](../../README.md).

1. Open **Monitor → Debugger**, select **Debugger - HL7v2 Playground**, and set Connector ID to `0`. Leave Message ID empty, keep **Break on entry** selected, and leave evaluation off. Click **Arm debugger**.
2. Under **Send a test message**, use **Load HL7 sample** or paste `sample.hl7`, then send. Both source and destination run while the debugger remains on screen.
3. At the entry pause, select **Normalize patient family name** under **Script step** and click **Run to selected step**.
4. In **Variables and maps**, inspect the local variables. **Step over** once assigns `originalFamilyName` to `"doe"`; the next step assigns `normalizedFamilyName` to `"DOE"`. The highlighted line has not executed yet, so an unassigned variable initially displays as undefined.
5. **Continue** through remaining stops until the message completes. Send another sample to repeat with the same session and breakpoint, or choose **End session**. Review the stored transformed/encoded content and maps for the expected results above.
6. To inspect the destination, arm a new session with Connector ID `1`. Send the sample, select **Stamp receiving application**, and use **Run to selected step** and **Step over** again.

Stay on this screen while a session is active: navigation releases it. The sender may be waiting while execution is paused; debugger controls remain available. A selected Message ID does not resend that message. Each pause defaults to 120 seconds; after expiry, clear the ended session and arm again.

**Run to selected step** leaves an ordinary breakpoint in place and preserves existing breakpoints. Earlier stops can still occur, and filters or control flow may skip the selected step. Ordinary inspection and stepping require no evaluation permission. Use the scope selector to switch between locals, globals/maps and `this`.

## Fixture provenance

The connector and HL7v2 serialization configuration derives from the engine's `Config 1.xml` controller-test fixture, with a separate UUID/name, one JavaScript step per transformer, and archive pruning disabled. All example message content is synthetic.
