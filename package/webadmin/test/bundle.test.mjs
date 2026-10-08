import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

test('shipped plugin is one ESM file with host React and no runtime relative imports', async () => {
    const manifest = JSON.parse(await readFile(new URL('../plugin.json', import.meta.url), 'utf8'));
    const code = await readFile(new URL('../' + manifest.client.entry, import.meta.url), 'utf8');
    assert.match(code, /from "@oie\/web-shell"/);
    assert.doesNotMatch(code, /(?:from\s*|import\s*\()["']\.\.?\//);
    assert.doesNotMatch(code, /react\.production|react-dom\.production/);
    assert.match(code, /registerChannelAction/);
    assert.match(code, /registerMessageAction/);
    assert.match(code, /createCodeEditor/);
    assert.match(code, /readOnly: true/);
});
