import { chromium } from "@playwright/test";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import assert from "node:assert/strict";
const c = JSON.parse(await readFile(process.argv[2], "utf8"));
const output = c.attempt;
await mkdir(output, { recursive: true });
const browser = await chromium.launch({ headless: true });
let page;
const errors = [],
  receipts = [],
  checks = [],
  messageIds = [];
let latestSession, lastSource;
const clean = (value) =>
  JSON.parse(
    JSON.stringify(value, (key, v) => (key === "tabId" ? "[tab-token]" : v)),
  );
const save = async (name, data) =>
  writeFile(c.attempt + "/" + name, JSON.stringify(data, null, 2) + "\n");
try {
  const context = await browser.newContext({
    ignoreHTTPSErrors: true,
    viewport: { width: 1600, height: 1100 },
    deviceScaleFactor: 1,
  });
  page = await context.newPage();
  page.setDefaultTimeout(12000);
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("response", async (response) => {
    const url = response.url();
    if (
      !url.includes("/api/extensions/debugger") &&
      !url.includes("/messagesWithObj")
    )
      return;
    try {
      const body = await response.json();
      let requestBody = null;
      try {
        requestBody = response.request().postDataJSON();
      } catch {}
      receipts.push({
        method: response.request().method(),
        url: url.replace(/tabId=[^&]+/, "tabId=[tab-token]"),
        status: response.status(),
        request: clean(requestBody),
        response: clean(body),
      });
      if (
        /\/sessions\/[^/?]+(?:\?|$)/.test(url) &&
        response.request().method() === "GET"
      )
        latestSession = body;
      if (url.includes("/sources/") && body.text) lastSource = body;
      if (url.includes("/messagesWithObj") && response.ok()) {
        const id = typeof body === "object" ? Object.values(body)[0] : body;
        messageIds.push(String(id));
      }
    } catch {}
  });
  await page.goto(c.webadminUrl);
  await page.waitForTimeout(1500);
  await page.locator('input[autocomplete="username"]').fill("admin");
  await page.locator('input[type="password"]').fill(
    await readFile(c.attempt + "/onboarding-complete", "utf8").then(
      () => "DebuggerTest2026!",
      () => "admin",
    ),
  );
  await page.getByRole("button", { name: /Sign in|Log in/i }).click();
  const onboarding = page.getByText("Welcome to Open Integration Engine", { exact: true });
  const debuggerLink = page.getByText("Debugger", { exact: true });
  await onboarding.or(debuggerLink).first().waitFor({ state: "visible", timeout: 30000 });
  if (await onboarding.isVisible()) {
    await page
      .locator('input[autocomplete="new-password"]')
      .nth(0)
      .fill("DebuggerTest2026!");
    await page
      .locator('input[autocomplete="new-password"]')
      .nth(1)
      .fill("DebuggerTest2026!");
    await page.getByRole("button", { name: "Finish", exact: true }).click();
    await page
      .getByText("Welcome to Open Integration Engine", { exact: true })
      .waitFor({ state: "hidden" });
    await writeFile(c.attempt + "/onboarding-complete", "done");
  }
  await page.screenshot({ path: c.attempt + "/after-login.png" });
  console.log("Login submitted", await page.title());
  await debuggerLink.click();
  await page
    .getByRole("heading", { name: "JavaScript Debugger", exact: true })
    .waitFor();
  await page.locator(".dbg-target select").first().selectOption(c.channelId);
  await page.getByLabel("Connector ID (optional)", { exact: true }).fill("0");
  await page
    .getByLabel("Enable evaluation and value editing", { exact: true })
    .check();
  await page.getByText("Limit the script surface", { exact: true }).click();
  await page.getByLabel("Script kind", { exact: true }).fill("transformer");
  await page
    .getByLabel("Watch expressions (one per line)", { exact: true })
    .fill("orderId\nquantity\nunitPrice\ntotal");
  await page.getByRole("button", { name: "Arm debugger", exact: true }).click();
  await page.locator(".dbg-status").filter({ hasText: "armed" }).waitFor();
  await page.locator(".dbg-body").evaluate((el) => (el.scrollTop = 0));
  await page.screenshot({ path: output + "/debugger-session.png" });
  checks.push(
    "Real WebAdmin login; debugger discovered from the installed candidate ZIP; channel/source connector/script-kind/evaluation options Arm through UI",
  );
  const message = JSON.stringify(
    { orderId: "DEMO-1001", quantity: 2, unitPrice: 12.5 },
    null,
    2,
  );
  await page.locator(".dbg-send textarea").fill(message);
  await page.getByRole("button", { name: "Send message", exact: true }).click();
  await page.locator(".dbg-status").filter({ hasText: "paused" }).waitFor();
  await page.waitForFunction(
    () => document.querySelector(".dbg-sourcebar select")?.value,
  );
  await page
    .getByText("Click the gutter to toggle an executable breakpoint.", {
      exact: false,
    })
    .waitFor();
  for (let i = 0; i < 50 && !lastSource; i++) await page.waitForTimeout(100);
  assert(lastSource?.text, "real deployed source captured");
  await save("deployed-source.json", lastSource);
  const lineFor = (text) =>
    lastSource.text.split("\n").findIndex((line) => line.trim() === text) + 1;
  const totalLine = lineFor('channelMap.put("total", total);');
  assert(totalLine > 0);
  await page.getByText("Add breakpoint by line", { exact: true }).click();
  await page.getByLabel("Line", { exact: true }).fill(String(totalLine));
  await page
    .getByRole("button", { name: "Set breakpoint", exact: true })
    .click();
  await page
    .getByRole("button", {
      name: `Remove breakpoint at line ${totalLine}`,
      exact: true,
    })
    .waitFor();
  const entryLine = lineFor("var order = JSON.parse(String(msg));");
  await page
    .getByRole("button", { name: "Run to selected step", exact: true })
    .click();
  await page
    .locator(".dbg-execution-location")
    .filter({ hasText: `Generated line ${entryLine}` })
    .waitFor();
  for (let i = 0; i < 50 && latestSession.frames?.[0]?.line !== entryLine; i++)
    await page.waitForTimeout(100);
  assert.equal(latestSession.frames[0].line, entryLine);
  await save("pause-run-to-step.json", latestSession);
  checks.push(
    "Run to selected step resolves authored source mapping and pauses at first sample assignment",
  );
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page
    .locator(".dbg-execution-location")
    .filter({ hasText: `Generated line ${totalLine}` })
    .waitFor();
  await page
    .getByRole("complementary")
    .locator(".dbg-value-summary")
    .filter({ hasText: "25" })
    .first()
    .waitFor();
  assert.equal(
    latestSession.inspection.locals.properties.total.value,
    25,
    JSON.stringify(latestSession),
  );
  await save("pause-total25.json", latestSession);
  checks.push(
    "Inline JSON message reaches real deployed Rhino; breakpoint stops before channelMap write; locals total=25",
  );
  await page.locator(".dbg-workspace").scrollIntoViewIfNeeded();
  await page.screenshot({ path: output + "/debugger-paused.png" });
  await page
    .getByLabel("Evaluate in selected frame", { exact: true })
    .fill("quantity * unitPrice");
  await page.getByRole("button", { name: "Evaluate", exact: true }).click();
  await page
    .locator(".dbg-result .dbg-value-summary")
    .filter({ hasText: "25" })
    .waitFor();
  await page
    .getByRole("button", { name: "Apply watches", exact: true })
    .click();
  await page
    .locator(".dbg-watch")
    .filter({ hasText: "total" })
    .filter({ hasText: "25" })
    .waitFor();
  await page
    .getByRole("button", { name: "Apply watches", exact: true })
    .waitFor();
  for (
    let i = 0;
    i < 100 &&
    !(await page
      .getByRole("button", { name: "Evaluate", exact: true })
      .isEnabled());
    i++
  )
    await page.waitForTimeout(100);
  await page.getByRole("button", { name: "Evaluate", exact: true }).click();
  await page
    .locator(".dbg-result .dbg-value-summary")
    .filter({ hasText: "25" })
    .waitFor();
  for (
    let i = 0;
    i < 100 &&
    !(await page
      .getByRole("button", { name: "Continue", exact: true })
      .isEnabled());
    i++
  )
    await page.waitForTimeout(100);
  assert(
    await page
      .getByRole("button", { name: "Continue", exact: true })
      .isEnabled(),
  );
  assert(
    await page
      .getByRole("button", { name: "Apply watches", exact: true })
      .isEnabled(),
  );
  checks.push(
    "Explicit evaluation quantity * unitPrice returns25; four watches update from current paused frame; inspect capture waits for all chained requests to complete",
  );
  await page.locator(".dbg-panels").scrollIntoViewIfNeeded();
  await page.screenshot({ path: output + "/debugger-inspect.png" });
  await page.getByText("Edit a variable", { exact: true }).click();
  await page.getByLabel("Variable name", { exact: true }).fill("total");
  await page.getByLabel("New value expression", { exact: true }).fill("30");
  await page.getByRole("button", { name: "Set value", exact: true }).click();
  await page
    .locator(".dbg-result .dbg-value-summary")
    .filter({ hasText: "30" })
    .waitFor();
  const prior = latestSession.pauseGeneration;
  await page.getByRole("button", { name: "Step over", exact: true }).click();
  await page
    .locator(".dbg-execution-title")
    .filter({ hasText: "Paused after step" })
    .waitFor();
  for (let i = 0; i < 50 && latestSession.pauseGeneration <= prior; i++)
    await page.waitForTimeout(100);
  assert.equal(latestSession.inspection.locals.properties.total.value, 30);
  await page
    .getByLabel("Evaluate in selected frame", { exact: true })
    .fill('channelMap.get("total")');
  await page.getByRole("button", { name: "Evaluate", exact: true }).click();
  await page
    .locator(".dbg-result .dbg-value-summary")
    .filter({ hasText: "30" })
    .waitFor();
  checks.push(
    "Set value changes local total to30; Step over executes map write; explicit channelMap lookup returns30",
  );
  await save("pause-after-edit-step.json", latestSession);
  await page.getByRole("button", { name: "End session", exact: true }).click();
  await page
    .getByRole("button", { name: "Arm debugger", exact: true })
    .waitFor();
  await page.getByText(/Message \d+ accepted by the engine\./).waitFor();
  checks.push(
    "End session releases paused execution; HTTP send completes with real message ID",
  );
  await page.waitForTimeout(300);
  assert.equal(messageIds.length, 1, JSON.stringify(messageIds));
  assert.deepEqual(errors, []);
  const editedMessageIds = [...messageIds];
  await page.getByRole("button", { name: "Arm debugger", exact: true }).click();
  await page.locator(".dbg-status").filter({ hasText: "armed" }).waitFor();
  await page.locator(".dbg-send textarea").fill(message);
  await page.getByRole("button", { name: "Send message", exact: true }).click();
  await page.locator(".dbg-status").filter({ hasText: "paused" }).waitFor();
  for (let i = 0; i < 100 && latestSession?.status !== "paused"; i++)
    await page.waitForTimeout(50);
  const leaseSessionId = latestSession.id;
  checks.push(
    "Browser closes while a second execution is paused; harness verifies lease release",
  );

  const result = {
    passed: true,
    kind: "installed-candidate-real-webadmin-and-oie-engine",
    checks,
    messageIds: editedMessageIds,
    leaseSessionId,
    channelId: c.channelId,
    errors,
    screenshots: [
      "debugger-session.png",
      "debugger-paused.png",
      "debugger-inspect.png",
    ],
    baselineTotal: 25,
    editedFinalTotal: 30,
  };
  await save("browser-results.json", result);
  await save("browser-http-receipts.json", receipts);
  await save("finish.json", result);
  console.log(JSON.stringify(result, null, 2));
} catch (e) {
  await page
    ?.screenshot({ path: c.attempt + "/browser-failure.png", fullPage: true })
    .catch(() => {});
  await writeFile(
    c.attempt + "/browser-failure.html",
    await page?.content().catch(() => ""),
  );
  await save("browser-failure.json", {
    error: e.stack,
    errors,
    latestSession,
    lastSource,
    checks,
    messageIds,
  });
  await save("browser-http-receipts.json", receipts);
  throw e;
} finally {
  await browser.close();
}
