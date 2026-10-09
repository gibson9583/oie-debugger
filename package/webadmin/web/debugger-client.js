const ROOT = '/extensions/debugger';
const enc = encodeURIComponent;

// The host API's normal response path unwraps XStream roots and its object
// request path rewrites engine JSON. These endpoints deliberately use plain JSON.
export function jsonTransport(api) {
    const parse = value => typeof value === 'string' ? (value ? JSON.parse(value) : null) : value;
    const options = { raw: true, timeoutMs: 12000 };
    return {
        get: (path, params) => api.get(ROOT + path, params, options).then(parse),
        post: (path, body) => api.post(ROOT + path, JSON.stringify(body), options).then(parse),
        put: (path, body) => api.put(ROOT + path, JSON.stringify(body), options).then(parse),
        del: (path, params) => api.del(ROOT + path, params, options).then(parse),
    };
}

export function ownerContext(store, cookie = '') {
    const user = store.getState('user');
    if (!user) return null;
    const cookies = cookie.split(';').map(p => p.trim()).filter(p => /^(oie-engine|oie-engine-url|oie-login)=/.test(p)).sort();
    return JSON.stringify([String(user.id ?? user.username), ...cookies]);
}

export function sourceMatchesFrame(source, frame) {
    return !!source && !!frame && source.id === frame.sourceId && source.hash === frame.sourceHash && source.generation === frame.sourceGeneration;
}

export function breakpointLines(source, lines) {
    if (!source || !Array.isArray(source.breakableLines)) throw new Error('The engine has not verified executable lines for this source.');
    return lines.map(entry => {
        const line = Number(entry.line);
        const hitCount = Number(entry.hitCount || 1);
        if (!Number.isInteger(line) || !source.breakableLines.includes(line)) throw new Error(`Line ${line} is not executable in this deployed source.`);
        if (!Number.isInteger(hitCount) || hitCount < 1) throw new Error('Hit count must be a positive whole number.');
        return { line, condition: String(entry.condition || '').trim(), hitCount, enabled: entry.enabled !== false };
    });
}

/** Resolve only a mapping delivered with this exact deployed-source response. */
export function authoredStepLine(source, mapping) {
    if (!source?.id || !source.hash || !Number.isInteger(source.generation) || !source.authoredMappings?.includes(mapping) || mapping?.sourceId !== source.id)
        throw new Error('Select a verified authored step from the displayed deployed source.');
    const start = mapping.generatedStartLine, end = mapping.generatedEndLine;
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 1 || end < start)
        throw new Error('The selected authored step has no verified generated line range.');
    const lines = source.breakableLines?.filter(line => Number.isInteger(line) && line >= start && line <= end) || [];
    if (!lines.length) throw new Error('The selected authored step has no executable line in this deployed source.');
    return lines.reduce((first, line) => Math.min(first, line), Infinity);
}

/** One mounted debugger owns one lease. No tokens or paused data are persisted. */
export class DebuggerClient {
    constructor({ transport, context, newId, changed = () => {}, timers = globalThis }) {
        this.api = transport;
        this.context = context;
        this.newId = newId;
        this.changed = changed;
        this.timers = timers;
        this.tabId = newId();
        this.owner = context();
        this.alive = true;
        this.epoch = 0;
        this.sourceEpoch = 0;
        this.listEpoch = 0;
        this.snapshotRevision = 0;
        this.state = { capabilities: null, sources: [], source: null, session: null, armOptions: null, error: '', busy: false, lost: false, receipt: null, pending: null, breakpoints: {} };
    }
    emit(patch) { this.state = { ...this.state, ...patch }; if (this.alive) this.changed(this.state); }
    valid(epoch = this.epoch) { return this.alive && epoch === this.epoch && this.owner !== null && this.owner === this.context(); }
    assert() { if (!this.valid()) throw new Error('The engine or login changed. Reopen the debugger.'); }
    async initialize() {
        const epoch = this.epoch;
        try { this.assert(); const capabilities = await this.api.get('/capabilities'); if (this.valid(epoch)) this.emit({ capabilities, error: '' }); }
        catch (e) { if (this.valid(epoch)) this.emit({ error: e.message }); }
    }
    async sources(channelId) {
        const epoch = this.epoch, request = ++this.listEpoch;
        if (this.channelId !== channelId) { this.channelId = channelId; this.sourceEpoch++; this.emit({ sources: [], source: null }); }
        try {
            this.assert();
            const sources = await this.api.get('/sources', { channelId });
            if (this.valid(epoch) && request === this.listEpoch) this.emit({ sources: Array.isArray(sources) ? sources : sources?.sources || [] });
        } catch (e) { if (this.valid(epoch) && request === this.listEpoch) this.emit({ error: e.message }); }
    }
    async source(id, channelId) {
        const epoch = this.epoch, request = ++this.sourceEpoch;
        this.emit({ source: null });
        if (!id) return;
        try {
            this.assert();
            const session = this.state.session;
            const source = await this.api.get(`/sources/${enc(id)}`, {
                channelId,
                ...(session && session.status !== 'closed' ? { debugSessionId: session.id, tabId: this.tabId } : {}),
            });
            if (this.valid(epoch) && request === this.sourceEpoch) this.emit({ source });
        } catch (e) { if (this.valid(epoch) && request === this.sourceEpoch) this.emit({ error: e.message }); }
    }
    async arm(options) {
        this.assert();
        if (this.state.busy || this.state.session) return;
        // Keep the submitted target and flags separate from live execution identity.
        const armOptions = { ...options };
        if (Array.isArray(armOptions.watches)) armOptions.watches = [...armOptions.watches];
        const epoch = ++this.epoch;
        this.emit({ busy: true, lost: false, error: '', receipt: null, pending: null, breakpoints: {}, armOptions: null });
        try {
            const session = await this.api.post('/sessions', { ...armOptions, tabId: this.tabId });
            if (!session?.id || !['armed', 'running', 'paused'].includes(session.status)) throw new Error('The debugger returned an incompatible session response.');
            if (!this.valid(epoch)) {
                // A navigation while POST was in flight must not orphan a lease.
                if (this.owner === this.context()) await this.api.del(`/sessions/${enc(session.id)}`, { tabId: this.tabId }).catch(() => {});
                return;
            }
            this.emit({ session, armOptions });
            this.startTimers();
        } catch (e) { if (this.valid(epoch)) this.emit({ error: e.message }); }
        finally { if (this.valid(epoch)) this.emit({ busy: false }); }
    }
    startTimers() {
        this.clearTimers();
        this.pollTimer = this.timers.setTimeout(() => this.poll(), 500);
        this.leaseTimer = this.timers.setTimeout(() => this.heartbeat(), 3000);
    }
    clearTimers() {
        this.timers.clearTimeout(this.pollTimer);
        this.timers.clearTimeout(this.leaseTimer);
        this.timers.clearTimeout(this.commandTimer);
    }
    lose(error) {
        this.clearTimers();
        this.emit({ lost: true, error: `${error.message} Lease renewal stopped; the engine will release the execution when its lease expires.` });
    }
    async poll() {
        const epoch = this.epoch, id = this.state.session?.id, revision = this.snapshotRevision;
        if (!id || !this.valid(epoch) || this.state.lost) return;
        try {
            const session = await this.api.get(`/sessions/${enc(id)}`, { tabId: this.tabId });
            if (!this.valid(epoch)) return;
            if (revision === this.snapshotRevision) this.emit({ session });
            if (session.status === 'closed') {
                this.clearTimers(); this.epoch++;
                this.emit({ session, pending: null, busy: false });
                return;
            }
            this.pollTimer = this.timers.setTimeout(() => this.poll(), 500);
        } catch (e) { if (this.valid(epoch)) this.lose(e); }
    }
    async heartbeat() {
        const epoch = this.epoch, id = this.state.session?.id;
        if (!id || !this.valid(epoch) || this.state.lost) return;
        try {
            await this.api.post(`/sessions/${enc(id)}/heartbeat`, { tabId: this.tabId });
            if (this.valid(epoch) && this.state.session?.status !== 'closed') this.leaseTimer = this.timers.setTimeout(() => this.heartbeat(), 3000);
        } catch (e) { if (this.valid(epoch)) this.lose(e); }
    }
    async disarm() {
        this.assert();
        const session = this.state.session;
        if (!session?.id) return;
        this.clearTimers();
        const epoch = ++this.epoch;
        this.emit({ busy: true, pending: null });
        try {
            // A confirmed closed session is already released and may have been
            // evicted from the engine's short-lived session history.
            if (session.status !== 'closed') await this.api.del(`/sessions/${enc(session.id)}`, { tabId: this.tabId });
            if (this.valid(epoch)) this.emit({ session: null, armOptions: null, receipt: null, error: '', lost: false, breakpoints: {} });
        } catch (e) { if (this.valid(epoch)) this.lose(e); }
        finally { if (this.valid(epoch)) this.emit({ busy: false }); }
    }
    async command(action, fields = {}, retry = false) {
        this.assert();
        const session = this.state.session;
        if (!session || this.state.lost || this.state.busy || session.status === 'closed') return;
        if (!retry && this.state.pending) throw new Error('Resolve the pending command before sending another.');
        const body = retry ? this.state.pending : { ...fields, tabId: this.tabId, commandId: this.newId(), expectedPauseGeneration: session.pauseGeneration, action };
        if (!body) return;
        this.timers.clearTimeout(this.commandTimer);
        if (!retry) this.commandAttempts = 0;
        const epoch = this.epoch;
        this.emit({ busy: true, error: '', pending: body });
        // A GET sent before a command cannot restore its older execution state.
        this.snapshotRevision++;
        try {
            const receipt = await this.api.post(`/sessions/${enc(session.id)}/commands`, body);
            if (!this.valid(epoch)) return;
            if (!receipt || !['pending', 'completed'].includes(receipt.status)) throw new Error('The debugger returned an incompatible command receipt.');
            this.snapshotRevision++;
            this.emit({ receipt, pending: receipt.status === 'pending' ? body : null });
            // The worker consumes queued commands after the servlet returns.
            // Reposting the same receipt ID observes completion without replay.
            if (receipt.status === 'pending' && ++this.commandAttempts <= 40) {
                this.commandTimer = this.timers.setTimeout(() => {
                    if (this.valid(epoch) && this.state.pending?.commandId === body.commandId) this.command('', {}, true).catch(e => this.emit({ error: e.message }));
                }, 250);
            }
            if (receipt.error) this.emit({ error: typeof receipt.error === 'string' ? receipt.error : JSON.stringify(receipt.error) });
            else if (receipt.status === 'completed' && ['continue', 'stepInto', 'stepOver', 'stepOut'].includes(body.action) && this.state.session?.pauseGeneration === body.expectedPauseGeneration) this.emit({ session: { ...this.state.session, status: 'running' } });
            else if (receipt.status === 'completed' && body.action === 'inspect') this.emit({ session: { ...this.state.session, inspection: receipt.result } });
            else if (receipt.status === 'completed' && body.action === 'setBreakpoints') this.emit({ breakpoints: { ...this.state.breakpoints, [body.sourceId]: body.lines } });
            return receipt;
        } catch (e) {
            // A transport failure is ambiguous: retain the ID/body for a safe retry.
            if (this.valid(epoch)) this.emit({ error: `${e.message} Check the command result before issuing another command.`, pending: e.status && e.status < 500 ? null : body });
        } finally { if (this.valid(epoch)) this.emit({ busy: false }); }
    }
    async setBreakpoints(source, entries) {
        this.assert();
        if (!this.state.session || this.state.busy || this.state.lost || this.state.pending || this.state.session.status === 'closed') return false;
        const lines = breakpointLines(source, entries);
        // Breakpoint writes share the command receipt: a timeout cannot expose
        // a newer mutation until the exact original write has been resolved.
        const receipt = await this.command('setBreakpoints', {
            sourceId: source.id, sourceHash: source.hash, sourceGeneration: source.generation, lines,
        });
        return receipt?.status === 'completed' && !receipt.error;
    }
    async runToAuthoredStep(source, mapping) {
        this.assert();
        if (this.state.pending) throw new Error('Resolve the pending command before running to a step.');
        if (this.state.busy || this.state.lost) throw new Error('Wait for the current request or reconnect before running to a step.');
        const session = this.state.session, epoch = this.epoch;
        if (!session || session.status !== 'paused') throw new Error('Pause an execution before running to an authored step.');
        const line = authoredStepLine(source, mapping);
        const displayedMatches = () => sourceMatchesFrame(source, {
            sourceId: this.state.source?.id, sourceHash: this.state.source?.hash, sourceGeneration: this.state.source?.generation,
        });
        if (!displayedMatches() || !session.frames?.some(frame => sourceMatchesFrame(source, frame)))
            throw new Error('The selected source does not match this paused execution. Refresh the source and select the step again.');
        const top = session.frames[0];
        if (sourceMatchesFrame(source, top) && top.line === line) return { line, alreadyPaused: true };
        const entries = this.state.breakpoints[source.id] || [];
        const existing = entries.find(entry => entry.line === line);
        if (existing && (existing.enabled === false || String(existing.condition || '').trim() || Number(existing.hitCount || 1) !== 1))
            throw new Error(`Line ${line} already has a disabled, conditional or hit-count breakpoint. Change it to an enabled unconditional breakpoint before running to this step.`);
        // Keep the added breakpoint visible, and preserve every user-configured breakpoint.
        if (!existing) {
            const updated = await this.setBreakpoints(source, [...entries, { line }]);
            if (!updated) return null;
        }
        this.assert();
        const current = this.state.session;
        if (!this.valid(epoch) || !current || current.id !== session.id || current.status !== 'paused' || current.pauseGeneration !== session.pauseGeneration)
            throw new Error('Execution state changed while setting the breakpoint. Refresh the pause before continuing.');
        if (!displayedMatches()) throw new Error('The displayed source changed while setting the breakpoint. Select the step again before continuing.');
        if (this.state.pending || this.state.busy || this.state.lost)
            throw new Error('Execution control changed while setting the breakpoint. Resolve the pending request before continuing.');
        await this.command('continue');
        return { line, alreadyPaused: false };
    }
    dispose() {
        if (!this.alive) return;
        this.clearTimers();
        this.alive = false;
        this.epoch++;
        const session = this.state.session;
        this.state = { capabilities: null, sources: [], source: null, session: null, armOptions: null, error: '', busy: false, lost: true, receipt: null, pending: null, breakpoints: {} };
        if (session && session.status !== 'closed' && this.owner === this.context()) {
            this.api.del(`/sessions/${enc(session.id)}`, { tabId: this.tabId }).catch(() => {});
        }
    }
}
