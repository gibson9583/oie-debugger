import { platform } from '@oie/web-shell';
import css from './debugger.css';
import { DebuggerClient, jsonTransport, ownerContext, sourceMatchesFrame, authoredStepLine } from './debugger-client.js';
import { SendMessage } from './send-message.jsx';
import { PropertiesPreview, ValuePreview } from './value-preview.jsx';
import { ExecutionStatus } from './execution-status.jsx';
import { SessionSettings } from './armed-settings.jsx';

const React = platform.React;
const { useCallback, useEffect, useRef, useState } = React;
const STYLE_ID = 'oie-debugger-style';
const show = value => JSON.stringify(value, null, 2);

function SourceEditor({ source, frame, pauseKey, breakpoints, disabled, toggle, revealLine }) {
    const host = useRef(null), editor = useRef(null), decorations = useRef(null);
    const latest = useRef({ source, frame, breakpoints, disabled, toggle });
    latest.current = { source, frame, breakpoints, disabled, toggle };
    const [monacoReady, setMonacoReady] = useState(false);
    useEffect(() => {
        if (!source || !host.current) return;
        let mouse, tries = 0, timer;
        const instance = platform.createCodeEditor({ value: source.text, language: 'javascript', readOnly: true, minHeight: '390px', ariaLabel: 'Deployed JavaScript source' });
        editor.current = instance;
        host.current.appendChild(instance.el);
        setMonacoReady(false);
        const connect = () => {
            if (instance.monaco) {
                instance.monaco.updateOptions({ readOnly: true, glyphMargin: true });
                decorations.current = instance.monaco.createDecorationsCollection([]);
                mouse = instance.monaco.onMouseDown(event => {
                    const current = latest.current;
                    if (event.target.type === 2 && event.target.position && !current.disabled && current.source.breakableLines.includes(event.target.position.lineNumber)) {
                        current.toggle(event.target.position.lineNumber);
                    }
                });
                setMonacoReady(true);
            } else if (++tries < 100) timer = setTimeout(connect, 100);
        };
        connect();
        return () => { clearTimeout(timer); mouse?.dispose(); decorations.current?.clear(); decorations.current = null; instance.dispose(); instance.el.remove(); editor.current = null; };
    }, [source?.id, source?.hash, source?.generation]);
    useEffect(() => {
        if (!source || !monacoReady || !decorations.current) return;
        const lineRange = line => ({ startLineNumber: line, endLineNumber: line, startColumn: 1, endColumn: 1 });
        const next = breakpoints.filter(p => p.enabled).map(p => ({ range: lineRange(p.line), options: { glyphMarginClassName: 'dbg-breakpoint', glyphMarginHoverMessage: { value: `Breakpoint${p.condition ? ': ' + p.condition : ''}` } } }));
        if (sourceMatchesFrame(source, frame)) {
            next.push({ range: lineRange(frame.line), options: { isWholeLine: true, className: 'dbg-current-line', linesDecorationsClassName: 'dbg-current-glyph' } });
        }
        decorations.current.set(next);
    }, [source, frame, breakpoints, monacoReady]);
    // Polls replace frame objects every 500ms. Only a new execution location may
    // move the viewport; keep manual scrolling and authored navigation intact.
    useEffect(() => {
        if (sourceMatchesFrame(source, frame) && editor.current?.monaco)
            editor.current.monaco.revealLineInCenterIfOutsideViewport(frame.line);
    }, [source?.id, source?.hash, source?.generation, frame?.sourceId, frame?.sourceHash, frame?.sourceGeneration, frame?.index, frame?.line, pauseKey, monacoReady]);
    useEffect(() => {
        if (!revealLine || !editor.current) return;
        if (editor.current.monaco) editor.current.monaco.revealLineInCenter(revealLine);
        else if (editor.current.area) {
            const area = editor.current.area;
            const start = source.text.split('\n').slice(0, revealLine - 1).reduce((n, text) => n + text.length + 1, 0);
            area.focus(); area.setSelectionRange(start, start);
            area.scrollTop = Math.max(0, revealLine - 4) * (parseFloat(getComputedStyle(area).lineHeight) || 20);
        }
    }, [revealLine, source?.id, monacoReady]);
    return <>
        <div ref={host} className="dbg-editor" />
        <div className="dbg-sourceinfo dbg-muted">{monacoReady ? 'Click the gutter to toggle an executable breakpoint.' : 'Read-only source. Use the line controls below to set breakpoints.'} Deployed source only; editor drafts are not used.</div>
    </>;
}

function Variables({ inspection, frame, paused, disabled, refresh }) {
    const [scope, setScope] = useState('locals');
    if (!paused) return <p className="dbg-empty">Variables appear when a message pauses.</p>;
    if (!inspection || (inspection.frameIndex ?? 0) !== frame?.index) return <p className="dbg-empty">Loading variables for this call frame…</p>;
    return <div className="dbg-variables">
        <div className="dbg-scopebar"><label htmlFor="dbg-scope">Scope</label><select id="dbg-scope" value={scope} onChange={event => setScope(event.target.value)}><option value="locals">Local variables</option><option value="globals">Global variables and maps</option><option value="this">This object</option></select><button disabled={disabled} onClick={refresh}>Refresh</button></div>
        <p className="dbg-muted dbg-scopehint">{frame?.functionName || 'Script'} · before the highlighted line</p>
        <div className="dbg-value-scroll"><PropertiesPreview value={inspection[scope]} emptyText="No values in this scope." /></div>
        <p className="dbg-muted dbg-scopehint">Captured values only. Getters are not invoked.</p>
    </div>;
}

function DebuggerView() {
    const initial = useRef(platform.store.getState('debuggerTarget') || {});
    const [state, setState] = useState(null), [channels, setChannels] = useState([]);
    const [channelId, setChannelId] = useState(initial.current.channelId || '');
    const [messageId, setMessageId] = useState(String(initial.current.messageId ?? ''));
    const [connectorId, setConnectorId] = useState(String(initial.current.connectorId ?? ''));
    const [maxPause, setMaxPause] = useState(120);
    const [entry, setEntry] = useState(true), [exceptions, setExceptions] = useState(false), [lifecycle, setLifecycle] = useState(false), [evaluation, setEvaluation] = useState(false);
    const [scriptKind, setScriptKind] = useState(''), [targetSource, setTargetSource] = useState('');
    const [sourceId, setSourceId] = useState(''), [frameIndex, setFrameIndex] = useState(0);
    const [authoredIndex, setAuthoredIndex] = useState('');
    const [line, setLine] = useState('1'), [condition, setCondition] = useState(''), [hitCount, setHitCount] = useState('1');
    const [watchText, setWatchText] = useState(''), [expression, setExpression] = useState(''), [name, setName] = useState(''), [valueExpression, setValueExpression] = useState('');
    const [localError, setLocalError] = useState('');
    const clientRef = useRef(null), lastPause = useRef('');
    const valid = useCallback(() => !!clientRef.current?.valid(), []);
    const getSession = useCallback(() => {
        const current = clientRef.current?.state;
        return current && !current.busy && !current.lost && !current.pending ? current.session : null;
    }, []);
    const context = () => ownerContext(platform.store, document.cookie);
    useEffect(() => {
        let active = true;
        const client = new DebuggerClient({ transport: jsonTransport(platform.api), context, newId: () => platform.oie.uuid(), changed: next => active && setState(next) });
        clientRef.current = client;
        setState(client.state);
        client.initialize();
        platform.api.channels.list().then(list => { if (active && client.valid()) setChannels(Array.isArray(list) ? list : []); }).catch(e => { if (active) setLocalError('Cannot load channels: ' + e.message); });
        const invalidate = () => {
            client.dispose();
            if (active) {
                setState(client.state); setChannels([]); setChannelId(''); setSourceId(''); setWatchText('');
                setExpression(''); setName(''); setValueExpression(''); setCondition(''); setMessageId(''); setConnectorId('');
                platform.store.setState('debuggerTarget', null);
                setLocalError('The login or engine changed. Reopen the debugger to create a new session.');
            }
        };
        const unsubscribe = platform.store.subscribe('user', () => { if (context() !== client.owner) invalidate(); });
        const expired = platform.api.onSessionExpired(invalidate);
        const unknown = platform.api.onEngineUnknown(invalidate);
        window.addEventListener('oie-session-changed', invalidate);
        window.addEventListener('pagehide', invalidate);
        return () => { active = false; unsubscribe(); expired(); unknown(); window.removeEventListener('oie-session-changed', invalidate); window.removeEventListener('pagehide', invalidate); client.dispose(); };
    }, []);
    useEffect(() => {
        setSourceId(''); setTargetSource('');
        clientRef.current?.source('', channelId);
        if (channelId) clientRef.current?.sources(channelId);
    }, [channelId]);
    useEffect(() => { setAuthoredIndex(''); clientRef.current?.source(sourceId, channelId); }, [sourceId, channelId]);
    useEffect(() => {
        if (sourceId || !state?.sources?.length) return;
        const candidate = state.sources.find(s => s.kind === 'transformer' && (connectorId === '' || s.name.endsWith('_' + connectorId))) || (state.sources.length === 1 ? state.sources[0] : null);
        if (candidate) setSourceId(candidate.id);
    }, [state?.sources, sourceId, connectorId]);

    const session = state?.session, source = state?.source;
    const paused = session?.status === 'paused', closed = session?.status === 'closed';
    const activeSession = !!session && !closed;
    const client = clientRef.current;
    const blocked = !client?.valid() || !!state?.busy || !!state?.lost;
    const controlsBlocked = blocked || !!state?.pending || state?.capabilities?.controlAllowed === false;
    const frame = paused ? session.frames?.find(f => f.index === frameIndex) || session.frames?.[0] : null;
    useEffect(() => {
        if (!source?.authoredMappings?.length) return;
        const current = sourceMatchesFrame(source, frame) ? source.authoredMappings.findIndex(m => m.kind !== 'code-template' && frame.line >= m.generatedStartLine && frame.line <= m.generatedEndLine) : -1;
        const first = source.authoredMappings.findIndex(m => m.kind === 'transformer');
        if (current >= 0) setAuthoredIndex(String(current));
        else setAuthoredIndex(previous => previous === '' && first >= 0 ? String(first) : previous);
    }, [source?.id, source?.hash, source?.generation, frame?.index, frame?.line, session?.pauseGeneration]);
    const breakpoints = source ? state?.breakpoints[source.id] || [] : [];
    const capabilities = state?.capabilities;
    const available = capabilities?.available === true && capabilities.apiVersion === 1;
    const evaluationUnavailable = capabilities?.evaluationEnabled === false ? 'Evaluation is disabled in the engine settings.'
        : capabilities?.evaluationAllowed === false ? 'Evaluation is not available for your account.' : '';
    const expressions = () => watchText.split('\n').map(s => s.trim()).filter(Boolean);
    const act = async fn => { setLocalError(''); try { await fn(); } catch (e) { setLocalError(e.message); } };
    const command = (action, fields = {}) => act(() => client.command(action, { frameIndex: frame?.index ?? 0, ...fields }));
    const applyWatches = () => act(async () => {
        await client.command('setWatches', { expressions: expressions() });
        if (client.valid() && client.state.session?.status === 'paused' && !client.state.pending && !client.state.error) {
            await client.command('inspect', { frameIndex: frame?.index ?? 0 });
        }
    });
    useEffect(() => {
        if (!paused || !session.frames?.length) return;
        const pauseKey = `${session.id}:${session.pauseGeneration}`;
        if (pauseKey === lastPause.current) return;
        lastPause.current = pauseKey;
        const top = session.frames?.[0];
        setFrameIndex(top?.index ?? 0);
        if (top?.sourceId) {
            setSourceId(top.sourceId);
            // Arming fences older requests. If source selection already matches,
            // React will not rerun the sourceId effect to replace an abandoned GET.
            if (sourceId === top.sourceId && !sourceMatchesFrame(client.state.source, top)) client.source(top.sourceId, channelId);
        }
        client.sources(channelId);
    }, [session?.id, session?.pauseGeneration, session?.frames?.length, paused]);
    const selectFrame = f => { if (client.state.busy || client.state.pending || client.state.lost) return; setFrameIndex(f.index); setSourceId(f.sourceId); command('inspect', { frameIndex: f.index }); };
    const authored = authoredIndex === '' ? null : source?.authoredMappings?.[Number(authoredIndex)];
    let runLine = null;
    try { if (authored) runLine = authoredStepLine(source, authored); } catch { /* Unverified mappings cannot be run. */ }
    const toggleBreakpoint = targetLine => {
        if (controlsBlocked || !activeSession || !source) return;
        const exists = breakpoints.some(p => p.line === targetLine);
        act(() => client.setBreakpoints(source, exists ? breakpoints.filter(p => p.line !== targetLine) : [...breakpoints, { line: targetLine }]));
    };
    const addBreakpoint = event => {
        event.preventDefault();
        if (!source) return;
        act(() => client.setBreakpoints(source, [...breakpoints.filter(p => p.line !== Number(line)), { line: Number(line), condition: evaluation ? condition : '', hitCount: Number(hitCount), enabled: true }]));
    };
    const armOptions = () => ({
            channelId, ...(messageId.trim() ? { messageId: messageId.trim() } : {}), ...(connectorId.trim() ? { connectorId: Number(connectorId) } : {}),
            ...(scriptKind ? { scriptKind } : {}), ...(targetSource ? { sourceId: targetSource } : {}),
            breakOnEntry: entry, pauseOnExceptions: exceptions, pauseOnDebugger: true,
            includeLifecycle: lifecycle, includeGlobal: channelId === 'global' && lifecycle,
            evaluationEnabled: evaluation, watches: evaluation ? expressions() : [], maxPauseMillis: Number(maxPause) * 1000, leaseMillis: 15000,
    });
    const arm = event => {
        event.preventDefault();
        act(() => client.arm(armOptions()));
    };
    const canArmNewMessage = available && !controlsBlocked && !!messageId.trim() && ['armed', 'running'].includes(session?.status);
    const armNewMessage = () => act(async () => {
        const currentClient = clientRef.current, current = currentClient?.state;
        if (!currentClient?.valid() || !current?.capabilities?.available || current.capabilities.controlAllowed === false
                || current.busy || current.pending || current.lost || !['armed', 'running'].includes(current.session?.status)
                || current.session.channelId !== channelId || !messageId.trim()) return;
        const options = armOptions();
        delete options.messageId;
        const disarmEpoch = currentClient.epoch + 1;
        await currentClient.disarm();
        // A failed DELETE is ambiguous. Retain the old filter and never create
        // a second lease until the first session is confirmed released.
        if (clientRef.current !== currentClient || !currentClient.valid(disarmEpoch) || currentClient.state.session
                || currentClient.state.busy || currentClient.state.lost || currentClient.state.pending) return;
        setMessageId('');
        const target = platform.store.getState('debuggerTarget');
        if (target?.channelId === channelId) {
            const next = { ...target };
            delete next.messageId;
            platform.store.setState('debuggerTarget', next);
        }
        await currentClient.arm(options);
    });
    return <main className="oie-debugger">
        <header className="dbg-header"><div className="dbg-row"><h1>JavaScript Debugger</h1><span className="dbg-status" data-state={state?.lost ? 'disconnected' : session?.status || 'idle'}>{state?.lost ? 'Disconnected' : session?.status || 'Not armed'}</span></div>
            <div className="dbg-row">
                <button disabled={!paused || controlsBlocked} onClick={() => command('continue')}>Continue</button>
                <button disabled={!activeSession || paused || controlsBlocked} onClick={() => command('pause')}>Pause next line</button>
                <button disabled={!paused || controlsBlocked} onClick={() => command('stepOver')}>Step over</button>
                <button disabled={!paused || controlsBlocked} onClick={() => command('stepInto')}>Step into</button>
                <button disabled={!paused || controlsBlocked} onClick={() => command('stepOut')}>Step out</button>
                {session && <button disabled={!!state?.busy || !client?.valid()} onClick={() => act(() => client.disarm())}>{closed ? 'Clear session' : 'End session'}</button>}
            </div>
            <ExecutionStatus state={state} channelName={channels.find(c => c.id === session?.channelId)?.name} />
        </header>
        <div className="dbg-body" role="region" aria-label="Debugger content" tabIndex={0}>
        {(localError || state?.error) && <div className="dbg-notice dbg-error" role="alert">{localError || state.error}</div>}
        {session?.lastError && <div className="dbg-notice dbg-warning" role="alert">{session.lastError}</div>}
        {!capabilities && !state?.error && <p role="status" className="dbg-muted">Checking debugger compatibility and access…</p>}
        {capabilities && !available && <div className="dbg-notice dbg-warning" role="status">Debugging is unavailable. {capabilities.reason || capabilities.reasons?.join(' ') || 'Check the plugin access policy and engine compatibility.'} <button onClick={() => client.initialize()}>Check again</button></div>}
        {available && capabilities.controlAllowed === false && <div className="dbg-notice" role="status">Your account can read deployed sources. Debugger control permission is required to arm, pause or change an execution.</div>}
        {state?.pending && <div className="dbg-notice" role="status">The command is pending or its response was interrupted. Checking reuses its original command ID. <button disabled={blocked} onClick={() => act(() => client.command('', {}, true))}>Check command result</button></div>}
        {session ? <SessionSettings state={state} channelName={channels.find(c => c.id === session.channelId)?.name} /> : <form onSubmit={arm}>
            <fieldset className="dbg-target" disabled={blocked || !available || !!session || capabilities?.controlAllowed === false}><legend>Target execution</legend>
                <div className="dbg-fields">
                    <label className="dbg-field">Channel<select required value={channelId} onChange={e => setChannelId(e.target.value)}><option value="">Select a channel</option>{channels.map(c => <option key={c.id} value={c.id}>{c.name || c.id}</option>)}{channelId && channelId !== 'global' && !channels.some(c => c.id === channelId) && <option value={channelId}>{channelId}</option>}<option value="global" disabled={!capabilities?.globalAllowed}>Global scripts</option></select></label>
                    <label className="dbg-field">Message ID (optional)<input value={messageId} onChange={e => setMessageId(e.target.value)} inputMode="numeric" placeholder="Next matching message" /></label>
                    <label className="dbg-field">Connector ID (optional)<input type="number" min="0" step="1" value={connectorId} onChange={e => setConnectorId(e.target.value)} placeholder="Any connector" /></label>
                    <label className="dbg-field">Maximum pause (seconds)<input type="number" min="1" max="120" required value={maxPause} onChange={e => setMaxPause(e.target.value)} /></label>
                </div>
                <div className="dbg-checks"><label><input type="checkbox" checked={entry} onChange={e => setEntry(e.target.checked)} />Break on entry</label><label><input type="checkbox" checked={exceptions} onChange={e => setExceptions(e.target.checked)} />Pause on exceptions</label><label title={capabilities?.globalAllowed ? '' : 'Global or lifecycle debugging is unavailable for this account or installation.'}><input type="checkbox" checked={lifecycle} disabled={!capabilities?.globalAllowed} onChange={e => setLifecycle(e.target.checked)} />Include lifecycle and scripts without a message</label><label title={evaluationUnavailable}><input type="checkbox" checked={evaluation} disabled={!capabilities?.evaluationAllowed || !!evaluationUnavailable} aria-describedby={evaluationUnavailable ? 'dbg-evaluation-help' : undefined} onChange={e => setEvaluation(e.target.checked)} />Enable evaluation and value editing</label></div>
                {evaluationUnavailable && <p id="dbg-evaluation-help" className="dbg-muted dbg-options-help">{evaluationUnavailable}</p>}
                <details><summary>Limit the script surface</summary><div className="dbg-fields" style={{ marginTop: 10 }}><label className="dbg-field">Script kind<input value={scriptKind} onChange={e => setScriptKind(e.target.value)} list="dbg-kinds" placeholder="All JavaScript surfaces" /><datalist id="dbg-kinds">{[...new Set(state?.sources.map(s => s.kind).filter(Boolean) || [])].map(kind => <option key={kind} value={kind} />)}</datalist></label><label className="dbg-field">Exact deployed source<select value={targetSource} onChange={e => setTargetSource(e.target.value)}><option value="">Any source in the target</option>{state?.sources.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label></div></details>
                {(lifecycle || evaluation) && <p className="dbg-notice dbg-warning" style={{ marginTop: 12 }}>{lifecycle ? 'Lifecycle and global pauses can delay deploy, undeploy and administration. ' : ''}{evaluation ? 'Watches, conditions and value edits execute JavaScript with engine privileges and may change data or call external systems. Elevated access is required.' : ''}</p>}
                <div className="dbg-row" style={{ marginTop: 14 }}><button type="submit" className="dbg-primary" disabled={!channelId || (channelId === 'global' && !lifecycle)}>Arm debugger</button><p className="dbg-muted">Use development or test channels. Pausing can hold transactions and cause senders to retry.</p></div>
            </fieldset>
        </form>}
        {closed && <p role="status">Session ended: {session.closeReason || session.reason || 'execution released'}. Clear the session to arm again.</p>}
        <SendMessage channelId={channelId} channelName={channels.find(c => c.id === channelId)?.name || channelId} session={session} getSession={getSession} valid={valid} disabled={blocked || !available} messageFilter={messageId} canArmNewMessage={canArmNewMessage} onArmNewMessage={armNewMessage} />
        <section className="dbg-workspace" aria-label="Source and call stack">
            <div className="dbg-source"><div className="dbg-sourcebar"><label htmlFor="dbg-source">Deployed source</label><select id="dbg-source" value={sourceId} onChange={e => setSourceId(e.target.value)}><option value="">Select source</option>{state?.sources.map(s => <option value={s.id} key={s.id}>{s.name} · {s.kind}</option>)}{sourceId && !state?.sources.some(s => s.id === sourceId) && <option value={sourceId}>Paused source</option>}</select><button disabled={!channelId || blocked} onClick={() => { client.sources(channelId); if (sourceId) client.source(sourceId, channelId); }}>Refresh</button></div>
                {source ? <>{source.authoredMappings?.length > 0 && <div className="dbg-sourcebar"><label htmlFor="dbg-authored">Script step</label><select id="dbg-authored" value={authoredIndex} onChange={e => setAuthoredIndex(e.target.value)}><option value="">Choose a script step</option>{source.authoredMappings.map((mapping, index) => <option key={mapping.authoredId || index} value={index}>{mapping.name}</option>)}</select><button className="dbg-primary" disabled={!paused || controlsBlocked || !runLine} onClick={() => act(() => client.runToAuthoredStep(source, authored))}>Run to selected step</button></div>}
                    {paused && authored && <p className="dbg-sourceinfo">Run to selected step sets a breakpoint at this step's first executable line and continues to it. Then use Step over to execute each assignment and see its value.</p>}
                    <SourceEditor source={source} frame={frame} pauseKey={`${session?.id}:${session?.pauseGeneration}`} breakpoints={breakpoints} disabled={!activeSession || controlsBlocked} toggle={toggleBreakpoint} revealLine={authored?.generatedStartLine} />
                    <details className="dbg-sourceinfo"><summary>Deployed source details</summary><p className="dbg-mono">SHA-256 {source.hash} · generation {source.generation} · {source.lineCount} lines{authored ? ` · ${authored.name}: generated lines ${authored.generatedStartLine}–${authored.generatedEndLine}` : ''}</p></details>{frame && source.id === frame.sourceId && !sourceMatchesFrame(source, frame) && <div role="alert" className="dbg-notice dbg-warning">The displayed source does not match this paused frame. Resume and arm a new session after redeploy.</div>}</> : <div className="dbg-empty">{sourceId ? 'Loading verified deployed source…' : 'Arm the debugger, then send a test message here. Its deployed script will appear when the message pauses.'}</div>}
            </div>
            <aside className="dbg-stack"><h2>Variables and maps</h2><Variables inspection={session?.inspection} frame={frame} paused={paused} disabled={controlsBlocked} refresh={() => command('inspect')} /><h2>Call stack</h2>{paused && session.frames?.length ? <ol>{session.frames.map(f => <li key={f.index}><button disabled={controlsBlocked} aria-pressed={frame?.index === f.index} onClick={() => selectFrame(f)}>{f.functionName || '(script)'}<span className="dbg-muted dbg-mono">Frame {f.index} · line {f.line}</span></button></li>)}</ol> : <p className="dbg-empty">Call frames appear when an execution pauses.</p>}</aside>
        </section>
        <div className="dbg-panels">
            <section className="dbg-panel"><div className="dbg-row"><h2>Breakpoints</h2><span className="dbg-muted">{breakpoints.length ? `${breakpoints.length} in this source` : 'None in this source'}</span></div>
                <ul className="dbg-bplist">{breakpoints.map(bp => <li key={bp.line}><input aria-label={`Enable breakpoint at line ${bp.line}`} type="checkbox" checked={bp.enabled} disabled={controlsBlocked || !activeSession} onChange={() => act(() => client.setBreakpoints(source, breakpoints.map(p => p.line === bp.line ? { ...p, enabled: !p.enabled } : p)))} /><code>Line {bp.line}{bp.hitCount > 1 ? ` · hit ${bp.hitCount}` : ''}{bp.condition ? ` · ${bp.condition}` : ''}</code><button disabled={controlsBlocked || !activeSession} onClick={() => toggleBreakpoint(bp.line)} aria-label={`Remove breakpoint at line ${bp.line}`}>Remove</button></li>)}</ul>
                <details className="dbg-disclosure"><summary>Add breakpoint by line</summary><form onSubmit={addBreakpoint} className="dbg-form"><div className="dbg-bpfields"><label className="dbg-field">Line<input type="number" min="1" required value={line} onChange={e => setLine(e.target.value)} /></label><label className="dbg-field">Hit count<input type="number" min="1" required value={hitCount} onChange={e => setHitCount(e.target.value)} /></label>{evaluation && <label className="dbg-field">Condition<input value={condition} onChange={e => setCondition(e.target.value)} placeholder="Optional expression" /></label>}</div><div className="dbg-row"><button disabled={!source || !activeSession || controlsBlocked}>Set breakpoint</button></div></form></details>
            </section>
            <section className="dbg-panel"><h2>Watches and evaluation</h2>{!evaluation ? <p className="dbg-muted">{capabilities?.evaluationEnabled === false ? 'Evaluation is disabled in the engine settings.' : capabilities?.evaluationAllowed === false ? 'Evaluation is not available for your account.' : 'Enable evaluation before arming to add watches or run expressions.'}</p> : <>
                <label className="dbg-field">Watch expressions (one per line)<textarea value={watchText} onChange={e => setWatchText(e.target.value)} placeholder={'msg\nchannelMap'} disabled={blocked} /></label><div className="dbg-row" style={{ marginTop: 8 }}><button disabled={!activeSession || controlsBlocked} onClick={applyWatches}>Apply watches</button></div>
                {paused && session.watches?.map((watch, index) => <div className="dbg-watch" key={index}>{watch.error ? <p role="status"><code>{watch.expression}</code>: {watch.error}</p> : <ValuePreview name={watch.expression} value={watch.result} />}</div>)}
                <form className="dbg-form" onSubmit={e => { e.preventDefault(); command('evaluate', { expression }); }}><label className="dbg-field">Evaluate in selected frame<input value={expression} onChange={e => setExpression(e.target.value)} placeholder="JavaScript expression" /></label><button disabled={!evaluation || !paused || controlsBlocked || !expression.trim()}>Evaluate</button></form>
                {paused && state?.receipt?.result?.type && <div className="dbg-result"><h3>Result</h3><ValuePreview value={state.receipt.result} /></div>}
                <details><summary>Edit a variable</summary><form className="dbg-form" onSubmit={e => { e.preventDefault(); command('setValue', { name, valueExpression }); }}><label className="dbg-field">Variable name<input value={name} onChange={e => setName(e.target.value)} placeholder="msg" /></label><label className="dbg-field">New value expression<input value={valueExpression} onChange={e => setValueExpression(e.target.value)} placeholder="JavaScript expression" /></label><button disabled={!evaluation || !paused || controlsBlocked || !name.trim() || !valueExpression.trim()}>Set value</button></form></details>
                </>}
            </section>
        </div>
        <details className="dbg-diagnostics"><summary>Debugger diagnostics</summary><p className="dbg-muted">Raw captured data and source metadata for troubleshooting.</p>
            {source && <details><summary>Executable lines ({source.breakableLines?.length || 0})</summary><pre>{source.breakableLines?.join(', ') || 'No executable lines.'}</pre></details>}
            {paused && session.inspection && <details><summary>Inspection data</summary><pre>{show(session.inspection)}</pre></details>}
            {state?.receipt && <details><summary>Last command: {state.receipt.status}</summary><pre>{show(state.receipt)}</pre></details>}
        </details>
        </div>
    </main>;
}

export function register(host = platform) {
    if (!document.getElementById(STYLE_ID)) { const style = document.createElement('style'); style.id = STYLE_ID; style.textContent = css; document.head.appendChild(style); }
    host.registerIcon('debugger', 'M8 4h8v4H8z M6 10h12v10H6z M3 12h3 M18 12h3 M3 17h3 M18 17h3 M10 12v5 M14 12v5');
    host.registerView('/debugger', host.reactView(DebuggerView), { title: 'JavaScript Debugger' });
    host.registerNavItem({ id: 'debugger', label: 'Debugger', icon: 'debugger', path: '/debugger', section: 'Monitor', order: 80 });
    const open = target => { host.store.setState('debuggerTarget', target); host.router.navigate('/debugger'); };
    host.registerChannelAction({ id: 'debugger-channel', label: 'Debug Channel', icon: 'debugger', onInvoke: channel => open({ channelId: channel.id }) });
    host.registerMessageAction({ id: 'debugger-message', label: 'Debug this message', icon: 'debugger', onInvoke: (message, context) => open({ channelId: context.channelId, messageId: message.messageId ?? message.id, connectorId: context.metaDataId }) });
}
