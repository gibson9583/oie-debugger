import test from 'node:test';
import assert from 'node:assert/strict';
import { TestMessageSender, messageSendAccess, sendUnavailableReason } from '../web/test-message-sender.js';

const MESSAGE = '{"id": 4, "value": "hello"}\n';
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function fixture(processMessage = async () => 4) {
    let context = { owner: 'engine-a/user-1', valid: true, allowed: true, channelId: 'channel-1', session: { id: 'session-1', channelId: 'channel-1', status: 'armed' } };
    const calls = [], updates = [];
    const sender = new TestMessageSender({ processMessage: (...args) => { calls.push(args); return processMessage(...args); }, context: () => context, changed: state => updates.push(state) });
    return { sender, calls, updates, context: patch => { context = { ...context, ...patch }; }, getContext: () => context };
}

test('message submission preserves user text without parsing or changing its format', async () => {
    for (const message of [
        '  {"id":4,"value":"hello"}\n',
        '<message id="4">Hello &amp; goodbye</message>\n',
        'id,value\r\n4,"hello, world"\r\n',
        'record|4|hello\rrecord|5|goodbye\r',
        '  Plain text: café 日本語 😀\nSecond line\t  ',
        '{intentionally not valid JSON',
    ]) {
        const f = fixture();
        await f.sender.submit(message);
        assert.deepEqual(f.calls, [['channel-1', message, null, []]]);
        assert.equal(f.sender.state.status, 'sent');
    }
});

test('older host capabilities and permission failures disable sending without throwing', () => {
    for (const host of [{}, { api: { messages: { processNew() {} } } }, { checkTask: () => true }]) {
        assert.deepEqual(messageSendAccess(host), { supported: false, allowed: false });
    }
    const seen = [], host = { api: { messages: { processNew() {} } }, checkTask: (...args) => { seen.push(args); return true; } };
    assert.deepEqual(messageSendAccess(host), { supported: true, allowed: true });
    assert.deepEqual(seen, [['message', 'doSendMessage']]);
    host.checkTask = () => { throw new Error('Permissions unavailable'); };
    assert.deepEqual(messageSendAccess(host), { supported: true, allowed: false });
    const f = fixture(); f.context({ supported: false });
    assert.match(sendUnavailableReason(f.getContext()), /Web Administrator version/);
});

test('message-targeted sessions offer the explicit switch without hiding higher-priority send restrictions', () => {
    const f = fixture(); f.context({ messageFilter: '17' });
    assert.match(sendUnavailableReason(f.getContext()), /Arm for a new message/);
    f.context({ session: { channelId: 'channel-1', status: 'paused' } });
    assert.match(sendUnavailableReason(f.getContext()), /Continue or end/);
    f.context({ allowed: false });
    assert.match(sendUnavailableReason(f.getContext()), /Send Message permission/);
    f.context({ supported: false });
    assert.match(sendUnavailableReason(f.getContext()), /Web Administrator version/);
    f.context({ valid: false });
    assert.match(sendUnavailableReason(f.getContext()), /login or engine changes/);
});

test('message submission uses the host API and null for all destinations; duplicate clicks send once', async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    const first = f.sender.submit(MESSAGE);
    await f.sender.submit(MESSAGE);
    assert.deepEqual(f.calls, [['channel-1', MESSAGE, null, []]]);
    assert.equal(f.sender.state.status, 'sending');
    f.context({ session: { id: 'session-1', channelId: 'channel-1', status: 'paused' } });
    pending.resolve(4); await first;
    assert.equal(f.sender.state.status, 'sent');
    assert.equal(f.sender.state.messageId, '4');
});

test('action-time channel, permission, pause and message-ID guards prevent unintended submissions', async () => {
    const cases = [
        { allowed: false }, { valid: false }, { owner: null }, { channelId: 'global' }, { channelId: 'other' },
        { messageFilter: '17' }, { session: null },
        { session: { channelId: 'other', status: 'armed' } },
        { session: { channelId: 'channel-1', status: 'paused' } },
        { session: { channelId: 'channel-1', status: 'closed' } },
    ];
    for (const patch of cases) {
        const f = fixture(); f.context(patch);
        assert.notEqual(sendUnavailableReason(f.getContext()), '');
        await assert.rejects(f.sender.submit(MESSAGE));
        assert.equal(f.calls.length, 0);
    }
    const f = fixture(); await assert.rejects(f.sender.submit(' \n '), /Enter a message/);
    assert.equal(f.calls.length, 0);
});

test('transport uncertainty never retries and blocks another send until explicit acknowledgement', async () => {
    const f = fixture(async () => { throw new Error('No response after 120 seconds'); });
    await f.sender.submit(MESSAGE);
    assert.equal(f.sender.state.status, 'unknown');
    await assert.rejects(f.sender.submit(MESSAGE), /Check the previous message/);
    assert.equal(f.calls.length, 1);
    f.sender.acknowledgeUnknown();
    assert.equal(f.sender.state.status, 'idle');
    assert.equal(f.calls.length, 1);
});

test('explicit API rejections allow correction; server failures remain ambiguous', async () => {
    for (const status of [400, 401, 403, 404, 405, 415, 500, 502, 504]) {
        const f = fixture(async () => { throw Object.assign(new Error('Request failed'), { status }); });
        await f.sender.submit(MESSAGE);
        assert.equal(f.sender.state.status, status < 500 ? 'rejected' : 'unknown');
        assert.equal(f.calls.length, 1);
    }
});

test('an in-flight send reports its own result after disarm without restoring a debug session', async () => {
    const pending = deferred(), f = fixture(() => pending.promise);
    const first = f.sender.submit(MESSAGE);
    f.context({ session: null });
    await f.sender.submit(MESSAGE);
    assert.equal(f.calls.length, 1);
    pending.resolve(4); await first;
    assert.equal(f.sender.state.status, 'sent');
    assert.equal(f.getContext().session, null);
    await assert.rejects(f.sender.submit(MESSAGE), /Arm the debugger/);
    assert.equal(f.calls.length, 1);
});

test('engine, target, logout and unmount fence late success and failure without extra network calls', async () => {
    for (const patch of [{ owner: 'engine-b/user-1' }, { channelId: 'channel-2' }, { valid: false }, { owner: null }, null]) {
        for (const fail of [false, true]) {
            const pending = deferred(), f = fixture(() => pending.promise);
            const first = f.sender.submit(MESSAGE);
            if (patch) f.context(patch); else f.sender.dispose();
            const count = f.updates.length;
            if (fail) pending.reject(new Error('offline')); else pending.resolve(7);
            await first;
            assert.equal(f.updates.length, count);
            assert.equal(f.calls.length, 1);
        }
    }
});
