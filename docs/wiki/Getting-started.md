# Getting started

Install the debugger, prepare a small test channel, and open your first session. The example uses a JSON order with a quantity of `2` and a unit price of `12.5`, producing a total of `25`.

## Install the plugin

Use this setup:

- Open Integration Engine **4.6.0** running **Java 17 or newer**.
- Web Administrator with **plugin API 4.7 or newer**.
- Plugin discovery provided by the engine's native web-support endpoints or the **Web Support** extension.

1. Download the debugger plugin ZIP from [GitHub Releases](https://github.com/gibson9583/oie-debugger/releases).
2. Install the ZIP through the engine's extension installer.
3. Restart the engine.
4. Reload Web Administrator and choose **Monitor → Debugger**.

The debugger uses Rhino's interpreted mode. OIE uses the required setting by default when the property is absent. If your `conf/mirth.properties` file contains an explicit setting, make sure it reads:

```properties
rhino.optimizationlevel=-1
```

Restart the engine after changing that property. The debugger defaults to disabled: set `enabled=true` in the **JavaScript Debugger** extension properties, then deploy your test channel so its JavaScript is ready to inspect. For an existing channel that was compiled while the debugger was disabled, redeploy it before starting the walkthrough. Set `enabled=false` when debugging is finished to remove observation overhead from subsequent executions.

## Check your permissions

For the full walkthrough, use an account with access to the test channel and these permissions:

- **View JavaScript Debugger**
- **Control JavaScript Debugger**
- **Evaluate JavaScript Debugger**
- **Send Message**

View and Control provide the main pause, step, and inspection workflow. Evaluate adds watches, expression evaluation, conditional breakpoints, and local value editing. See [Access and settings](./Access-and-settings.md) for administrator setup.

## Create the sample channel

In the channel editor, create a development channel named **Order Total — Debugger Demo**:

1. Choose **Channel Reader** as the source connector.
2. Set the source transformer's inbound and outbound data types to **Raw**.
3. Add a **JavaScript** source transformer step named **Calculate order total**.
4. Paste the following script into that step.

```javascript
var order = JSON.parse(String(msg));
var orderId = String(order.orderId);
var quantity = Number(order.quantity);
var unitPrice = Number(order.unitPrice);
var total = quantity * unitPrice;
channelMap.put("orderId", orderId);
channelMap.put("total", total);
logger.info("Debugger demo " + orderId + " total=" + total);
```

For the destination, use a **Channel Writer** and select **\<None>** as its target channel to complete this example locally. Save, deploy, and start the channel.

The script reads an order, calculates its total, and stores the order ID and total in the channel map. Those values give you useful checkpoints as you step through the code.

## Prepare the message

Use this JSON as the walkthrough's input:

```json
{
  "orderId": "DEMO-1001",
  "quantity": 2,
  "unitPrice": 12.5
}
```

Next, follow [Debugging a message](./Debugging-a-message.md) to arm the debugger and send it.
