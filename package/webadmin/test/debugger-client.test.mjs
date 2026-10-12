import test from 'node:test';
import assert from 'node:assert/strict';
import { DebuggerClient, authoredStepLine, breakpointLines, jsonTransport, ownerContext, sourceMatchesFrame } from '../web/debugger-client.js';

const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const session = (patch = {}) => ({ id: 's-1', status: 'paused', channelId: 'channel-1', pauseGeneration: 2, frames: [], ...patch });
function fixture(overrides = {}) {
    let owner = 'engine-a/user-a', serial = 0;
    const calls = [], capabilityCalls = [], scheduled = new Map();
    const transport = Object.fromEntries(['get', 'post', 'put', 'del'].map(method => [method, async (...args) => {
        if (method === 'get' && args[0] === '/capabilities') {
            capabilityCalls.push(args);
            return overrides.capabilities ? overrides.capabilities(...args) : { available: true, apiVersion: 1, controlAllowed: true, armRequestExpiresAt: 30000 };
        }
        calls.push([method, ...args]);
        if (method === 'post' && args[1]?.action === 'setBreakpoints' && overrides.breakpointCommand) return overrides.breakpointCommand(...args);
        if (overrides[method]) return overrides[method](...args);
        return method === 'post' && args[0] === '/sessions' ? session() : { status: 'completed' };
    }]));
    const client = new DebuggerClient({ transport, newId: () => `uuid-${++serial}`, context: () => owner, timers: {
        setTimeout: (fn, ms) => { const id = ++serial; scheduled.set(id, { fn, ms }); return id; },
        clearTimeout: id => scheduled.delete(id),
    } });
    return { client, calls, capabilityCalls, scheduled, owner: value => { owner = value; } };
}

test('plain JSON transport preserves singleton objects and literal request payloads', async () => {
    const seen = [];
    const api = {
        get: async (...args) => { seen.push(args); return '{"sources":[]}'; },
        post: async (...args) => { seen.push(args); return '{"result":{"value":"x"}}'; },
        put: async (...args) => { seen.push(args); return '{}'; },
        del: async (...args) => { seen.push(args); return ''; },
    };
    const apiJson = jsonTransport(api);
    assert.deepEqual(await apiJson.get('/sources', { channelId: 'x' }), { sources: [] });
    assert.deepEqual(await apiJson.post('/sessions', { watches: ['a', 'b'] }), { result: { value: 'x' } });
    await apiJson.put('/sessions/s/breakpoints', { lines: [1, 2] });
    assert.equal(await apiJson.del('/sessions/s', { tabId: 'token' }), null);
    assert.equal(seen[0][2].raw, true);
    assert.equal(seen[1][1], '{"watches":["a","b"]}');
    assert.equal(seen[2][1], '{"lines":[1,2]}');
});

test('owner context changes on user, engine or login changes but not unrelated cookies', () => {
    let user = { id: 1 };
    const store = { getState: () => user };
    const a = ownerContext(store, 'oie-engine=a; other=1; oie-login=one');
    assert.equal(a, ownerContext(store, 'other=2; oie-login=one; oie-engine=a'));
    assert.notEqual(a, ownerContext(store, 'oie-engine=b; oie-login=one'));
    assert.notEqual(a, ownerContext(store, 'oie-engine=a; oie-login=two'));
    user = { id: 2 };
    assert.notEqual(a, ownerContext(store, 'oie-engine=a; oie-login=one'));
    user = null;
    assert.equal(ownerContext(store), null);
});

test('source identity requires id, hash and deployment generation', () => {
    const source = { id: 's', hash: 'hash', generation: 2 };
    const frame = { sourceId: 's', sourceHash: 'hash', sourceGeneration: 2 };
    assert.equal(sourceMatchesFrame(source, frame), true);
    assert.equal(sourceMatchesFrame(source, { ...frame, sourceHash: 'old' }), false);
    assert.equal(sourceMatchesFrame(source, { ...frame, sourceGeneration: 1 }), false);
});

test('breakpoints reject non-executable lines and fractional hit counts', () => {
    const source = { breakableLines: [2, 4] };
    assert.throws(() => breakpointLines(source, [{ line: 3 }]), /not executable/);
    assert.throws(() => breakpointLines(source, [{ line: 2, hitCount: 1.5 }]), /positive whole/);
    assert.throws(() => breakpointLines({}, []), /not verified/);
    assert.deepEqual(breakpointLines(source, [{ line: 2, condition: ' x > 3 ', hitCount: 4, enabled: false }]), [{ line: 2, condition: 'x > 3', hitCount: 4, enabled: false }]);
});

test('source selection and channel list responses are fenced independently', async () => {
    const first = deferred(), second = deferred();
    const { client } = fixture({ get: path => path.endsWith('/one') ? first.promise : second.promise });
    const a = client.source('one', 'channel-1'), b = client.source('two', 'channel-1');
    second.resolve({ id: 'two' }); await b;
    first.resolve({ id: 'one' }); await a;
    assert.equal(client.state.source.id, 'two');
    client.dispose();
});

test('source detail carries the current session and tab only while the session is active', async () => {
    const { client, calls } = fixture({ get: () => ({ id: 'dynamic-source', text: 'value;' }) });
    await client.source('deployed-source', 'channel-1');
    assert.deepEqual(calls[0][2], { channelId: 'channel-1' });
    await client.arm({ channelId: 'channel-1' });
    await client.source('dynamic-source', 'channel-1');
    assert.deepEqual(calls[2], ['get', '/sources/dynamic-source', { channelId: 'channel-1', debugSessionId: 's-1', tabId: client.tabId }]);
    client.emit({ session: session({ status: 'closed' }) });
    await client.source('deployed-source', 'channel-1');
    assert.deepEqual(calls[3][2], { channelId: 'channel-1' });
    client.dispose();
});

test('confirmed arm settings copy submitted options and survive live identity updates', async () => {
    const pending = deferred(), states = [];
    const { client, calls } = fixture({ post: () => pending.promise,
        get: () => session({ messageId: '99', connectorId: '3' }) });
    client.changed = next => states.push(next);
    const options = { channelId: 'channel-1', messageId: '17', connectorId: 0,
        breakOnEntry: false, pauseOnExceptions: true, includeLifecycle: false,
        evaluationEnabled: true, watches: ['msg'], maxPauseMillis: 45000 };
    const expected = { ...options, watches: [...options.watches] };
    const arming = client.arm(options);
    assert.equal(client.state.armOptions, null);
    options.messageId = 'changed'; options.breakOnEntry = true; options.watches.push('newWatch');
    await Promise.resolve(); // Fresh capability preflight completes before POST.
    assert.deepEqual(calls[0][2], { ...expected, tabId: client.tabId, requestId: calls[0][2].requestId, requestExpiresAt: 30000 });
    pending.resolve(session({ status: 'armed' })); await arming;
    assert.deepEqual(client.state.armOptions, expected);
    assert(states.filter(next => next.session).every(next => next.armOptions === client.state.armOptions),
        'the session and its confirmed settings are published together');
    options.watches[0] = 'changedAgain';
    await client.poll();
    assert.equal(client.state.session.messageId, '99');
    assert.equal(client.state.session.connectorId, '3');
    assert.deepEqual(client.state.armOptions, expected, 'live execution identity does not replace target filters');
    client.dispose();
    assert.equal(client.state.armOptions, null);
});

test('failed or incompatible arm responses do not confirm settings', async () => {
    for (const response of [null, {}, new Error('request failed')]) {
        const { client } = fixture({ post: () => {
            if (response instanceof Error) throw response;
            return response;
        } });
        await client.arm({ channelId: 'channel-1', breakOnEntry: false });
        assert.equal(client.state.session, null);
        assert.equal(client.state.armOptions, null);
        assert.notEqual(client.state.error, '');
        client.dispose();
    }
});

test('confirmed settings survive failed close and connection loss until successful close', async () => {
    let fail = true;
    const { client } = fixture({ del: () => {
        if (fail) throw new Error('close outcome unknown');
        return { status: 'closed' };
    } });
    await client.arm({ channelId: 'channel-1', messageId: '17', breakOnEntry: false });
    const confirmed = client.state.armOptions;
    await client.disarm();
    assert.equal(client.state.lost, true);
    assert.equal(client.state.session.id, 's-1');
    assert.equal(client.state.armOptions, confirmed);
    fail = false; await client.disarm();
    assert.equal(client.state.session, null);
    assert.equal(client.state.armOptions, null);
    client.dispose();
});

test('server closure retains historical settings and rearming replaces the message filter', async () => {
    let nextArm = null;
    const { client, calls } = fixture({ get: () => session({ status: 'closed' }),
        post: () => nextArm ? nextArm.promise : session({ status: 'armed' }) });
    const original = { channelId: 'channel-1', messageId: '17', connectorId: 0, breakOnEntry: false };
    await client.arm(original);
    const confirmed = client.state.armOptions;
    await client.poll();
    assert.equal(client.state.session.status, 'closed');
    assert.equal(client.state.armOptions, confirmed);
    await client.disarm();
    nextArm = deferred();
    const replacement = { ...original }; delete replacement.messageId;
    const arming = client.arm(replacement);
    assert.equal(client.state.armOptions, null);
    nextArm.resolve(session({ id: 's-2', status: 'armed' })); await arming;
    assert.deepEqual(client.state.armOptions, replacement);
    assert.equal(Object.hasOwn(client.state.armOptions, 'messageId'), false);
    assert.deepEqual(calls.filter(call => call[0] === 'post').map(call => { const { requestId, requestExpiresAt, ...body } = call[2]; return body; }),
        [{ ...original, tabId: client.tabId }, { ...replacement, tabId: client.tabId }]);
    client.dispose();
    assert.equal(client.state.armOptions, null);
});

test('confirmed closed sessions clear locally after server eviction and can rearm without old state', async () => {
    let arms = 0;
    const { client, calls, scheduled } = fixture({
        post: (path, body) => path === '/sessions' ? session({ id: `s-${++arms}` })
            : { status: body.action === 'evaluate' ? 'pending' : 'completed' },
        get: () => session({ status: 'closed', closeReason: 'Maximum pause duration reached' }),
        del: () => { throw Object.assign(new Error('Debug session is unavailable to this owner/tab'), { status: 403 }); },
    });
    await client.arm({ channelId: 'channel-1', messageId: '17', evaluationEnabled: true });
    await client.setBreakpoints({ id: 'source', hash: 'hash', generation: 1, breakableLines: [2] }, [{ line: 2 }]);
    await client.command('evaluate', { expression: 'value', frameIndex: 0 });
    assert.equal(client.state.pending.action, 'evaluate');
    assert.equal(scheduled.size, 3);
    await client.poll();
    assert.equal(client.state.session.status, 'closed');
    assert.equal(client.state.receipt.status, 'pending');
    assert.equal(client.state.breakpoints.source.length, 1);
    const closedEpoch = client.epoch, count = calls.length;

    await client.disarm();
    assert.equal(calls.length, count, 'Clear must not depend on the evicted server record');
    assert.equal(client.epoch, closedEpoch + 1, 'Clear fences work started against the old session');
    assert.equal(scheduled.size, 0);
    assert.equal(client.state.session, null);
    assert.equal(client.state.armOptions, null);
    assert.equal(client.state.receipt, null);
    assert.equal(client.state.pending, null);
    assert.deepEqual(client.state.breakpoints, {});
    assert.equal(client.state.error, '');
    assert.equal(client.state.lost, false);
    assert.equal(client.state.busy, false);
    await client.disarm();
    assert.equal(calls.length, count, 'Repeated Clear is a local no-op');
    await client.arm({ channelId: 'channel-1' });
    assert.equal(client.state.session.id, 's-2');
    assert.deepEqual(client.state.armOptions, { channelId: 'channel-1' });
    assert.equal(scheduled.size, 2);
    assert.equal(calls.filter(call => call[0] === 'del').length, 0);
    client.dispose();
});

test('local clearing still rejects a changed owner and fences old source responses after rearm', async () => {
    const detail = deferred();
    const { client, calls, owner } = fixture({ get: path => path.includes('/sources/') ? detail.promise : session({ status: 'closed' }) });
    await client.arm({ channelId: 'channel-1' });
    await client.poll();
    const closed = client.state, closedEpoch = client.epoch, count = calls.length;
    owner('engine-b/user-a');
    await assert.rejects(client.disarm(), /engine or login changed/);
    assert.equal(client.state, closed);
    assert.equal(client.epoch, closedEpoch);
    assert.equal(calls.length, count);
    owner('engine-a/user-a');
    const loading = client.source('source', 'channel-1');
    await client.disarm();
    await client.arm({ channelId: 'channel-1' });
    detail.resolve({ id: 'source', text: 'old session source' });
    await loading;
    assert.equal(client.state.source, null, 'A source response from before Clear cannot update the new session');
    assert.equal(client.state.session.status, 'paused');
    assert.equal(calls.filter(call => call[0] === 'del').length, 0);
    client.dispose();
});

test('active session close failures retain the lease and prevent a second arm even for an unavailable response', async () => {
    for (const status of ['armed', 'running', 'paused']) {
        for (const failure of [new Error('close outcome unknown'), Object.assign(new Error('Debug session is unavailable to this owner/tab'), { status: 403 })]) {
            const { client, calls, scheduled } = fixture({
                post: () => session({ status }),
                del: () => { throw failure; },
            });
            await client.arm({ channelId: 'channel-1' });
            const active = client.state.session, options = client.state.armOptions;
            await client.disarm();
            assert.equal(calls.filter(call => call[0] === 'del').length, 1, 'Active sessions still require DELETE');
            assert.equal(client.state.session, active);
            assert.equal(client.state.armOptions, options);
            assert.equal(client.state.lost, true);
            assert.equal(client.state.busy, false);
            assert.equal(scheduled.size, 0);
            await client.arm({ channelId: 'channel-1' });
            assert.equal(calls.filter(call => call[0] === 'post' && call[1] === '/sessions').length, 1);
            client.dispose();
        }
    }
});

test('late successful arming after unmount releases the created session without reviving it', async () => {
    const pending = deferred();
    const { client, calls, scheduled } = fixture({ post: () => pending.promise });
    const arm = client.arm({ channelId: 'channel-1' });
    await Promise.resolve();
    client.dispose();
    pending.resolve(session()); await arm;
    assert.equal(client.state.session, null);
    assert.equal(client.state.armOptions, null);
    assert.equal(scheduled.size, 0);
    assert.deepEqual(calls[1], ['del', '/sessions/s-1', { tabId: client.tabId }]);
});

test('engine changes fence in-flight arming and never send cleanup to a different engine', async () => {
    const pending = deferred();
    const { client, calls, owner } = fixture({ post: () => pending.promise });
    const arm = client.arm({ channelId: 'channel-1' });
    await Promise.resolve();
    owner('engine-b/user-a');
    pending.resolve(session()); await arm;
    assert.equal(client.state.armOptions, null);
    client.dispose();
    assert.equal(client.state.session, null);
    assert.equal(calls.length, 1);
});

test('double arm is suppressed and failed arm never starts lease renewal', async () => {
    const pending = deferred();
    const { client, calls, scheduled } = fixture({ post: () => pending.promise });
    const arm = client.arm({ channelId: 'channel-1' });
    await client.arm({ channelId: 'channel-1' });
    assert.equal(calls.length, 1);
    pending.reject(new Error('access denied')); await arm;
    assert.equal(client.state.session, null);
    assert.equal(client.state.busy, false);
    assert.equal(scheduled.size, 0);
    client.dispose();
});

test('lost arm response retains one creation request and recovers the original paused session', async () => {
    const created = new Map();
    let loseResponse = true;
    const { client, calls, capabilityCalls, scheduled } = fixture({ post: (path, body) => {
        assert.equal(path, '/sessions');
        if (!created.has(body.requestId)) created.set(body.requestId, session({ id: `session-${created.size + 1}` }));
        if (loseResponse) { loseResponse = false; throw new Error('connection reset after creation'); }
        return created.get(body.requestId);
    } });
    const options = { channelId: 'channel-1', evaluationEnabled: true, watches: ['msg'] };
    await client.arm(options);
    options.channelId = 'channel-2'; options.watches.push('changed');
    assert.equal(client.state.session, null);
    assert.equal(client.state.busy, false);
    assert.match(client.state.error, /arm result is unknown/);
    assert.equal(scheduled.size, 0, 'an unknown session cannot renew its lease');
    const original = structuredClone(client.state.pendingArm.request);
    await assert.rejects(client.arm(options), /previous arm result/);
    await client.arm(null, true);
    assert.equal(client.state.session.id, 'session-1');
    assert.equal(client.state.session.status, 'paused');
    assert.deepEqual(client.state.armOptions, { channelId: 'channel-1', evaluationEnabled: true, watches: ['msg'] });
    assert.equal(client.state.pendingArm, null);
    assert.equal(capabilityCalls.length, 1, 'retries must not extend the creation deadline');
    assert.equal(created.size, 1);
    assert.deepEqual(calls.filter(c => c[0] === 'post').map(c => c[2]), [original, original]);
    await client.disarm();
    assert.deepEqual(calls.at(-1), ['del', '/sessions/session-1', { tabId: client.tabId }]);
    assert.equal(client.state.session, null);
    client.dispose();
});

test('recovering a closed arm result never renews or recreates the expired session', async () => {
    let attempt = 0;
    const { client, calls, scheduled } = fixture({ post: () => {
        if (++attempt === 1) throw new Error('response lost');
        return session({ status: 'closed', closeReason: 'Owner lease expired' });
    } });
    await client.arm({ channelId: 'channel-1' });
    await client.arm(null, true);
    assert.equal(client.state.session.status, 'closed');
    assert.equal(client.state.pendingArm, null);
    assert.equal(scheduled.size, 0);
    await client.disarm();
    assert.equal(calls.filter(c => c[0] === 'del').length, 0);
    assert.equal(client.state.session, null);
    client.dispose();
});

test('expired absent arm requests terminate recovery and the next deliberate arm uses a new request', async () => {
    let attempt = 0, deadline = 30000;
    const { client, calls, capabilityCalls } = fixture({
        capabilities: () => ({ available: true, apiVersion: 1, armRequestExpiresAt: deadline }),
        post: (path, body) => {
            if (++attempt === 1) throw new Error('response lost');
            if (attempt === 2) throw Object.assign(new Error('Arm request expired'), { status: 409,
                body: JSON.stringify({ code: 'arm_request_rejected', requestId: body.requestId }) });
            return session({ status: 'armed' });
        },
    });
    await client.arm({ channelId: 'channel-1' });
    const first = client.state.pendingArm.request;
    await client.arm(null, true);
    assert.equal(client.state.pendingArm, null);
    assert.equal(client.state.session, null);
    deadline = 90000;
    await client.arm({ channelId: 'channel-1' });
    assert.equal(capabilityCalls.length, 2);
    const last = calls.filter(c => c[0] === 'post').at(-1)[2];
    assert.notEqual(last.requestId, first.requestId);
    assert.equal(last.requestExpiresAt, deadline);
    client.dispose();
});

test('a recovery HTTP rejection without matching proof cannot discard a possibly live arm', async () => {
    for (const status of [400, 401, 403, 404, 409, 429]) {
        for (const body of [undefined, '<html>proxy error</html>', JSON.stringify({ code: 'arm_request_rejected', requestId: 'another-request' })]) {
            let attempt = 0;
            const { client, calls, capabilityCalls } = fixture({ post: () => {
                if (++attempt === 1) throw new Error('response lost');
                if (attempt === 2) throw Object.assign(new Error('request rejected before recovery'), { status, body });
                return session();
            } });
            await client.arm({ channelId: 'channel-1' });
            const original = client.state.pendingArm.request;
            await client.arm(null, true);
            assert.equal(client.state.pendingArm.request, original);
            await assert.rejects(client.arm({ channelId: 'channel-1' }), /previous arm result/);
            await client.arm(null, true);
            assert.equal(client.state.session.id, 's-1');
            assert.equal(client.state.pendingArm, null);
            assert.equal(capabilityCalls.length, 1);
            assert(calls.filter(c => c[0] === 'post').every(c => c[2] === original));
            client.dispose();
        }
    }
});

test('confirmed authorization rejection releases pending arm settings for a fresh request', async () => {
    let attempt = 0;
    const { client } = fixture({ post: (path, body) => {
        if (++attempt === 1) throw new Error('response lost');
        throw Object.assign(new Error('Evaluation permission was revoked'), { status: 403,
            body: JSON.stringify({ code: 'arm_request_rejected', requestId: body.requestId }) });
    } });
    await client.arm({ channelId: 'channel-1', evaluationEnabled: true });
    await client.arm(null, true);
    assert.equal(client.state.pendingArm, null);
    assert.equal(client.state.session, null);
    assert.equal(client.state.busy, false);
    assert.equal(client.state.error, 'Evaluation permission was revoked');
    client.dispose();
});

test('failed or denied capability preflight cannot create an unknown lease', async () => {
    for (const capabilities of [new Error('offline'), { available: false, apiVersion: 1 },
            { available: true, apiVersion: 1, controlAllowed: false, armRequestExpiresAt: 30000 },
            { available: true, apiVersion: 1 }]) {
        const { client, calls } = fixture({ capabilities: () => {
            if (capabilities instanceof Error) throw capabilities;
            return capabilities;
        } });
        await client.arm({ channelId: 'channel-1' });
        assert.equal(calls.length, 0);
        assert.equal(client.state.pendingArm, null);
        assert.equal(client.state.busy, false);
        assert.notEqual(client.state.error, '');
        client.dispose();
    }
});

test('disposal or owner change during capability preflight prevents session creation', async () => {
    for (const dispose of [false, true]) {
        const pending = deferred();
        const { client, calls, owner } = fixture({ capabilities: () => pending.promise });
        const arm = client.arm({ channelId: 'channel-1' });
        if (dispose) client.dispose(); else owner('engine-b/user-a');
        pending.resolve({ available: true, apiVersion: 1, armRequestExpiresAt: 30000 });
        await arm;
        assert.equal(calls.length, 0);
        assert.equal(client.state.pendingArm, null);
        client.dispose();
    }
});

test('arm recovery suppresses duplicate checks and cleans up late success after disposal', async () => {
    let attempt = 0;
    const pending = deferred();
    const { client, calls, scheduled } = fixture({ post: () => {
        if (++attempt === 1) throw new Error('response lost');
        return pending.promise;
    } });
    await client.arm({ channelId: 'channel-1' });
    const recovering = client.arm(null, true);
    await client.arm(null, true);
    assert.equal(attempt, 2);
    client.dispose();
    pending.resolve(session()); await recovering;
    assert.equal(client.state.pendingArm, null);
    assert.equal(client.state.session, null);
    assert.equal(scheduled.size, 0);
    assert.deepEqual(calls.at(-1), ['del', '/sessions/s-1', { tabId: client.tabId }]);
});

test('changed owners cannot recover an ambiguous arm or send cleanup to another engine', async () => {
    const { client, calls, owner } = fixture({ post: () => { throw new Error('response lost'); } });
    await client.arm({ channelId: 'channel-1' });
    const count = calls.length;
    owner('engine-b/user-a');
    await assert.rejects(client.arm(null, true), /engine or login changed/);
    client.dispose();
    assert.equal(calls.length, count);
    assert.equal(client.state.pendingArm, null);
});

test('lease renewal and polling stop on unmount or a failed heartbeat', async () => {
    const { client, calls, scheduled } = fixture({ post: path => path.endsWith('/heartbeat') ? Promise.reject(new Error('offline')) : session() });
    await client.arm({ channelId: 'channel-1' });
    assert.equal(scheduled.size, 2);
    await client.heartbeat();
    assert.equal(scheduled.size, 0);
    assert.equal(client.state.lost, true);
    const count = calls.length;
    await client.heartbeat(); await client.poll();
    assert.equal(calls.length, count);
    client.dispose();
});

test('ambiguous command failures retain their exact command ID and body for retry', async () => {
    let attempt = 0;
    const { client, calls } = fixture({ post: path => path === '/sessions' ? session() : ++attempt === 1 ? Promise.reject(new Error('timeout')) : { status: 'completed', result: 7 } });
    await client.arm({ channelId: 'channel-1' });
    await client.command('evaluate', { expression: 'a++', frameIndex: 0 });
    const original = client.state.pending;
    assert.equal(original.expectedPauseGeneration, 2);
    await assert.rejects(client.command('evaluate', { expression: 'a++' }), /pending command/);
    await client.command('', {}, true);
    assert.deepEqual(calls[1][2], calls[2][2]);
    assert.equal(client.state.pending, null);
    assert.equal(client.state.receipt.result, 7);
    client.dispose();
});

test('queued commands automatically resolve by polling the original receipt and disposal cancels polling', async () => {
    let attempt = 0;
    const { client, calls, scheduled } = fixture({ post: path => path === '/sessions' ? session() : ++attempt === 1 ? { status: 'pending' } : { status: 'completed', result: 42 } });
    await client.arm({ channelId: 'channel-1' });
    await client.command('evaluate', { expression: 'answer', frameIndex: 0 });
    const pollReceipt = [...scheduled.values()].find(timer => timer.ms === 250);
    assert(pollReceipt);
    pollReceipt.fn();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(calls[1][2], calls[2][2]);
    assert.equal(client.state.pending, null);
    assert.equal(client.state.receipt.result, 42);
    client.dispose();
    assert.equal(scheduled.size, 0);
});

test('dispose erases paused source and inspection data before any later response', async () => {
    const { client } = fixture();
    await client.arm({ channelId: 'channel-1' });
    client.emit({ source: { text: 'sensitive' }, sources: [{ id: 'secret' }], receipt: { result: 'private' } });
    client.dispose();
    assert.equal(client.state.session, null);
    assert.equal(client.state.source, null);
    assert.equal(client.state.receipt, null);
    assert.deepEqual(client.state.sources, []);
});

test('a newer channel source listing wins when previous channel response arrives late', async () => {
    const first = deferred(), second = deferred();
    const { client } = fixture({ get: (path, params) => params.channelId === 'first' ? first.promise : second.promise });
    const a = client.sources('first'), b = client.sources('second');
    second.resolve([{ id: 'source-b' }]); await b;
    first.resolve([{ id: 'source-a' }]); await a;
    assert.deepEqual(client.state.sources, [{ id: 'source-b' }]);
    client.dispose();
});

test('a completed step hides paused controls and stale GET cannot undo the step', async () => {
    const oldPoll = deferred();
    const { client } = fixture({ get: () => oldPoll.promise });
    await client.arm({ channelId: 'channel-1' });
    const poll = client.poll();
    await client.command('stepOver');
    assert.equal(client.state.session.status, 'running');
    oldPoll.resolve(session()); await poll;
    assert.equal(client.state.session.status, 'running');
    client.dispose();
});

test('late command response cannot restore a disarmed session', async () => {
    const receipt = deferred();
    const { client } = fixture({ post: path => path === '/sessions' ? session() : receipt.promise });
    await client.arm({ channelId: 'channel-1' });
    const command = client.command('evaluate', { expression: '1' });
    await client.disarm();
    receipt.resolve({ status: 'completed', result: '1' }); await command;
    assert.equal(client.state.session, null);
    assert.equal(client.state.receipt, null);
    client.dispose();
});

test('server closure fences in-flight commands and clears pending controls', async () => {
    const receipt = deferred();
    const { client } = fixture({ post: path => path === '/sessions' ? session() : receipt.promise, get: () => session({ status: 'closed' }) });
    await client.arm({ channelId: 'channel-1' });
    const command = client.command('continue');
    await client.poll();
    receipt.resolve({ status: 'completed' }); await command;
    assert.equal(client.state.session.status, 'closed');
    assert.equal(client.state.pending, null);
    assert.equal(client.state.busy, false);
    client.dispose();
});

test('breakpoint mutation carries compiled identity and confirms only after server success', async () => {
    const receipt = deferred();
    const { client, calls } = fixture({ breakpointCommand: () => receipt.promise });
    await client.arm({ channelId: 'channel-1' });
    const source = { id: 'src', hash: 'hash', generation: 3, breakableLines: [4] };
    const write = client.setBreakpoints(source, [{ line: 4 }]);
    assert.deepEqual(client.state.breakpoints, {});
    assert.equal(calls[1][0], 'post');
    assert.equal(calls[1][1], '/sessions/s-1/commands');
    assert.deepEqual(calls[1][2], { tabId: client.tabId, commandId: client.state.pending.commandId, expectedPauseGeneration: 2, action: 'setBreakpoints', sourceId: 'src', sourceHash: 'hash', sourceGeneration: 3, lines: [{ line: 4, hitCount: 1, enabled: true, condition: '' }] });
    receipt.resolve({ status: 'completed' }); await write;
    assert.equal(client.state.breakpoints.src[0].line, 4);
    client.dispose();
});

test('breakpoint uncertainty blocks further changes and reuses its receipt before any newer write', async () => {
    for (const alreadyApplied of [false, true]) {
        const receipts = new Map();
        let serverLines = [], applications = 0, requests = 0, delayedOriginal;
        // Model the server contract: cached command IDs never apply twice, even
        // if the original timed-out request arrives after its retry/newer write.
        const server = body => {
            if (receipts.has(body.commandId)) return receipts.get(body.commandId);
            applications++;
            serverLines = body.lines;
            const receipt = { status: 'completed', result: { sourceId: body.sourceId, count: body.lines.length } };
            receipts.set(body.commandId, receipt);
            return receipt;
        };
        const { client, calls } = fixture({
            get: () => session(),
            breakpointCommand: (path, body) => {
                if (++requests === 1) {
                    delayedOriginal = () => server(body);
                    if (alreadyApplied) delayedOriginal();
                    throw new Error('No confirmed response');
                }
                return server(body);
            },
        });
        await client.arm({ channelId: 'channel-1' });
        const source = { id: 'src', hash: 'hash', generation: 3, breakableLines: [4, 8] };
        assert.equal(await client.setBreakpoints(source, [{ line: 4 }]), false);
        const pending = client.state.pending;
        assert.equal(pending.action, 'setBreakpoints');
        assert.deepEqual(client.state.breakpoints, {});
        assert.equal(await client.setBreakpoints(source, [{ line: 8 }]), false);
        await assert.rejects(client.command('continue'), /pending command/);
        assert.equal(requests, 1);
        await client.poll();
        assert.equal(client.state.pending, pending);
        await client.command('', {}, true);
        assert.equal(client.state.pending, null);
        assert.deepEqual(client.state.breakpoints.src, serverLines);
        assert.equal(applications, 1);
        const writes = calls.filter(call => call[2]?.action === 'setBreakpoints');
        assert.deepEqual(writes[0][2], writes[1][2]);
        assert.equal(await client.setBreakpoints(source, [{ line: 8 }]), true);
        delayedOriginal();
        assert.equal(applications, 2);
        assert.equal(serverLines[0].line, 8);
        assert.deepEqual(client.state.breakpoints.src, serverLines);
        client.dispose();
    }
});

test('malformed breakpoint receipts remain unresolved, and failed receipts never confirm a breakpoint', async () => {
    for (const receipt of [{}, { status: 'unexpected' }, null]) {
        const { client } = fixture({ breakpointCommand: () => receipt });
        await client.arm({ channelId: 'channel-1' });
        assert.equal(await client.setBreakpoints({ id: 'src', breakableLines: [4] }, [{ line: 4 }]), false);
        assert.equal(client.state.pending.action, 'setBreakpoints');
        assert.match(client.state.error, /incompatible command receipt/);
        assert.deepEqual(client.state.breakpoints, {});
        client.dispose();
    }
    const { client } = fixture({ breakpointCommand: () => ({ status: 'completed', error: 'Source generation changed' }) });
    await client.arm({ channelId: 'channel-1' });
    assert.equal(await client.setBreakpoints({ id: 'src', breakableLines: [4] }, [{ line: 4 }]), false);
    assert.equal(client.state.pending, null);
    assert.deepEqual(client.state.breakpoints, {});
    assert.equal(client.state.error, 'Source generation changed');
    client.dispose();
});

function authoredSource() {
    return { id: 'src', hash: 'compiled-hash', generation: 3, breakableLines: [2, 8, 6], authoredMappings: [
        { sourceId: 'src', authoredId: 'step-1', name: 'Normalize family name', generatedStartLine: 5, generatedEndLine: 8 },
    ] };
}
function displayRunTarget(client, source, patch = {}) {
    client.emit({ source, session: session({ frames: [{ index: 0, sourceId: source.id, sourceHash: source.hash, sourceGeneration: source.generation, line: 2 }], ...patch }) });
}

test('authored step target is first executable line in its verified range, not the first text line', () => {
    const source = authoredSource(), mapping = source.authoredMappings[0];
    assert.equal(authoredStepLine(source, mapping), 6);
    assert.throws(() => authoredStepLine(source, { ...mapping }), /verified authored step/);
    assert.throws(() => authoredStepLine({ ...source, id: 'other-source' }, mapping), /verified authored step/);
    assert.throws(() => authoredStepLine({ ...source, breakableLines: [2, 9] }, mapping), /no executable line/);
    const invalid = { ...mapping, generatedStartLine: 0 };
    assert.throws(() => authoredStepLine({ ...source, authoredMappings: [invalid] }, invalid), /verified generated line range/);
});

test('run to authored step confirms additive breakpoint before continuing exact pause', async () => {
    const installed = deferred(), { client, calls } = fixture({ breakpointCommand: () => installed.promise });
    await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    const original = [{ line: 2, condition: 'keepMe', hitCount: 3, enabled: true }, { line: 8, condition: '', hitCount: 1, enabled: false }];
    client.emit({ breakpoints: { src: original } });
    const running = client.runToAuthoredStep(source, source.authoredMappings[0]);
    assert.equal(calls.length, 2); assert.equal(calls[1][0], 'post'); assert.equal(calls[1][2].action, 'setBreakpoints');
    assert.deepEqual(calls[1][2], { tabId: client.tabId, commandId: client.state.pending.commandId, expectedPauseGeneration: 2, action: 'setBreakpoints', sourceId: 'src', sourceHash: 'compiled-hash', sourceGeneration: 3,
        lines: [...original, { line: 6, condition: '', hitCount: 1, enabled: true }] });
    assert.equal(client.state.session.status, 'paused');
    installed.resolve({ status: 'completed' }); assert.deepEqual(await running, { line: 6, alreadyPaused: false });
    assert.equal(calls[2][1], '/sessions/s-1/commands');
    assert.equal(calls[2][2].action, 'continue'); assert.equal(calls[2][2].expectedPauseGeneration, 2);
    assert.equal(client.state.session.status, 'running'); assert.equal(client.state.breakpoints.src.length, 3);
    client.dispose();
});

test('run to step reuses a user unconditional breakpoint and refuses to overwrite conflicting settings', async () => {
    for (const conflicting of [{ enabled: false }, { condition: 'x > 1' }, { hitCount: 2 }]) {
        const { client, calls } = fixture(); await client.arm({ channelId: 'channel-1' });
        const source = authoredSource(); displayRunTarget(client, source);
        const entries = [{ line: 6, enabled: true, hitCount: 1, condition: '', ...conflicting }];
        client.emit({ breakpoints: { src: entries } });
        await assert.rejects(client.runToAuthoredStep(source, source.authoredMappings[0]), /already has/);
        assert.equal(calls.length, 1); assert.deepEqual(client.state.breakpoints.src, entries); client.dispose();
    }
    const { client, calls } = fixture(); await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    client.emit({ breakpoints: { src: [{ line: 6, enabled: true, hitCount: 1, condition: '' }] } });
    await client.runToAuthoredStep(source, source.authoredMappings[0]);
    assert.equal(calls.length, 2); assert.equal(calls[1][0], 'post'); client.dispose();
});

test('already paused at the authored target never resumes past it', async () => {
    const { client, calls } = fixture(); await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    client.state.session.frames[0].line = 6;
    assert.deepEqual(await client.runToAuthoredStep(source, source.authoredMappings[0]), { line: 6, alreadyPaused: true });
    assert.equal(calls.length, 1); assert.equal(client.state.session.status, 'paused'); client.dispose();
});

test('breakpoint rejection or ambiguous transport failure never triggers continue', async () => {
    for (const error of [Object.assign(new Error('source replaced'), { status: 409 }), new Error('timeout')]) {
        const { client, calls } = fixture({ breakpointCommand: () => Promise.reject(error) });
        await client.arm({ channelId: 'channel-1' });
        const source = authoredSource(); displayRunTarget(client, source);
        assert.equal(await client.runToAuthoredStep(source, source.authoredMappings[0]), null);
        assert.equal(calls.length, 2); assert.equal(client.state.session.status, 'paused');
        assert.match(client.state.error, new RegExp(error.message)); client.dispose();
    }
});

test('pending commands and stale source identity prevent run-to-step mutations', async () => {
    const { client, calls } = fixture(); await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    client.emit({ pending: { commandId: 'unresolved' } });
    await assert.rejects(client.runToAuthoredStep(source, source.authoredMappings[0]), /pending command/);
    client.emit({ pending: null }); client.state.session.frames[0].sourceHash = 'different-compiled-source';
    await assert.rejects(client.runToAuthoredStep(source, source.authoredMappings[0]), /does not match/);
    assert.equal(calls.length, 1); client.dispose();
});

test('a changed pause generation during breakpoint installation cannot be continued', async () => {
    const installed = deferred(), { client, calls } = fixture({ breakpointCommand: () => installed.promise });
    await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    const running = client.runToAuthoredStep(source, source.authoredMappings[0]);
    displayRunTarget(client, source, { pauseGeneration: 3 }); installed.resolve({ status: 'completed' });
    await assert.rejects(running, /Execution state changed/);
    assert.equal(calls.length, 2); assert.equal(client.state.session.pauseGeneration, 3); client.dispose();
});

test('source selection changed during breakpoint installation cannot silently resume another selection', async () => {
    const installed = deferred(), { client, calls } = fixture({ breakpointCommand: () => installed.promise });
    await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    const running = client.runToAuthoredStep(source, source.authoredMappings[0]);
    client.emit({ source: { ...source, hash: 'new-hash' } }); installed.resolve({ status: 'completed' });
    await assert.rejects(running, /displayed source changed/); assert.equal(calls.length, 2); client.dispose();
});

test('ownership change or disposal while installing run breakpoint never sends a resume', async () => {
    for (const invalidation of ['owner', 'dispose']) {
        const installed = deferred(), { client, calls, owner } = fixture({ breakpointCommand: () => installed.promise });
        await client.arm({ channelId: 'channel-1' });
        const source = authoredSource(); displayRunTarget(client, source);
        const running = client.runToAuthoredStep(source, source.authoredMappings[0]);
        if (invalidation === 'owner') owner('engine-b/user-a'); else client.dispose();
        installed.resolve({ status: 'completed' }); await running;
        assert.equal(calls.filter(call => call[2]?.action === 'continue').length, 0); client.dispose();
    }
});

test('run-to-step queued continue retains its original command ID for receipt polling', async () => {
    let attempts = 0;
    const { client, calls } = fixture({ post: (path, body) => path === '/sessions' ? session() : body.action === 'setBreakpoints' ? { status: 'completed' } : ++attempts === 1 ? { status: 'pending' } : { status: 'completed' } });
    await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    await client.runToAuthoredStep(source, source.authoredMappings[0]);
    const pending = client.state.pending; assert.equal(pending.action, 'continue'); assert.equal(pending.expectedPauseGeneration, 2);
    await assert.rejects(client.runToAuthoredStep(source, source.authoredMappings[0]), /pending command/);
    await client.command('', {}, true);
    assert.deepEqual(calls[2][2], calls[3][2]); assert.equal(client.state.session.status, 'running');
    assert.equal(calls.filter(call => call[2]?.action === 'setBreakpoints').length, 1); client.dispose();
});

test('a queued run-to-step breakpoint confirms on retry without automatically resuming', async () => {
    let requests = 0;
    const { client, calls, scheduled } = fixture({ breakpointCommand: () => ++requests === 1 ? { status: 'pending' } : { status: 'completed' } });
    await client.arm({ channelId: 'channel-1' });
    const source = authoredSource(); displayRunTarget(client, source);
    assert.equal(await client.runToAuthoredStep(source, source.authoredMappings[0]), null);
    assert.equal(client.state.pending.action, 'setBreakpoints');
    assert.deepEqual(client.state.breakpoints, {});
    const retry = [...scheduled.values()].find(timer => timer.ms === 250);
    assert(retry); retry.fn();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(client.state.pending, null);
    assert.equal(client.state.breakpoints.src[0].line, 6);
    assert.equal(client.state.session.status, 'paused');
    assert.equal(calls.filter(call => call[2]?.action === 'continue').length, 0);
    await client.runToAuthoredStep(source, source.authoredMappings[0]);
    assert.equal(requests, 2);
    assert.equal(calls.filter(call => call[2]?.action === 'continue').length, 1);
    client.dispose();
});
