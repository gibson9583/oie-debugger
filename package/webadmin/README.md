# Debugger web plugin development

The plugin adds the **Debugger** view and channel/message actions using the host's React, authenticated API helper, theme tokens and public code editor factory. React and Monaco are supplied by Web Administrator.

```sh
npm ci
npm test
npx playwright install --with-deps chromium
npm run test:browser
```

This builds `web/plugin.js`, the single ESM entry, and runs focused state/transport tests. The Maven package build runs the same build and tests.

`npm run test:browser` runs mock-host browser regressions for the main execution flow, sending subsequent messages, value inspection, execution status, session settings and checkbox behavior in both themes. React, ReactDOM and Playwright are pinned development dependencies used only by the test fixtures; the production bundle still uses the host's React. The fixtures do not require another repository or a live engine, and send no live messages.

## Ownership and transport

Each mounted view owns an ephemeral tab token and requests a 15-second lease, renewed every three seconds while its engine/login context remains valid. Leaving the view ends ownership; failed connectivity stops renewal. Mount, session and source generations fence late responses, and identity changes erase inspected data.

Commands reuse their immutable ID when checking queued completion or an ambiguous response. Queued receipts poll for up to ten seconds, after which the UI offers an explicit result check. Server session/pause generations remain authoritative.

Submitted target filters and settings are retained separately from the current execution's message and connector identity. Armed and closed sessions display their accepted settings; options become editable after the session is ended or cleared.

## Editing and presentation

Generated source is read-only and remains authoritative. Verified authored mappings provide navigation within that source. Breakpoints target executable lines and the source hash/generation; the fallback editor exposes labeled line controls when Monaco is unavailable.

Use host fonts, theme tokens and native labeled controls. Preserve the host's checkbox/radio styling, which lives in a CSS cascade layer; unlayered generic input rules can hide their selected state. Keep desktop/mobile layouts and keyboard interaction covered by the browser suite.

The [main README](../../README.md) describes installation, permissions, message debugging and execution limits.
