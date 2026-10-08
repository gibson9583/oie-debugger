import { platform } from '@oie/web-shell';
import { sourceMatchesFrame } from './debugger-client.js';

const React = platform.React;
const reasons = {
    entry: 'Paused on entry', breakpoint: 'Paused at breakpoint', step: 'Paused after step',
    pause: 'Paused by request', exception: 'Paused on exception', debugger: 'Paused at debugger statement',
};
const controlActions = new Set(['continue', 'stepInto', 'stepOver', 'stepOut', 'pause']);
const verified = (source, frame) => !!frame?.sourceId && !!frame.sourceHash
    && Number.isInteger(frame.sourceGeneration) && sourceMatchesFrame(source, frame);
const detailed = (source, frame) => verified(source, frame) && Array.isArray(source.authoredMappings);

function mappingAt(source, line) {
    return source?.authoredMappings?.find(mapping => mapping.sourceId === source.id
        && Number.isInteger(mapping.generatedStartLine) && mapping.generatedStartLine > 0
        && Number.isInteger(mapping.generatedEndLine) && mapping.generatedEndLine >= mapping.generatedStartLine
        && line >= mapping.generatedStartLine && line <= mapping.generatedEndLine);
}

function stageLabel(session, source, mapping) {
    const connector = String(session.connectorId ?? '');
    const side = connector === '0' ? 'Source' : /^[1-9]\d*$/.test(connector) ? `Destination ${connector}` : '';
    let kind = mapping?.kind && mapping.kind !== 'lifecycle' ? mapping.kind : source?.kind;
    // The deployed transformer source also contains the generated filter wrapper.
    if (kind === 'transformer' && mapping?.kind !== 'transformer') kind = 'filter / transformer';
    const label = kind === 'javascript' ? 'JavaScript' : kind === 'response' ? 'response transformer'
        : kind ? kind.replace(/[-_]/g, ' ') : 'processing';
    return side ? `${side} ${label}` : source ? label.charAt(0).toUpperCase() + label.slice(1) : '';
}

/** Only a matching top-frame source can describe the current stop. */
export function executionStatusModel(state, channelName, cachedSource = null) {
    const session = state?.session;
    if (!session || session.status === 'closed') return null;
    if (state.lost) return { status: 'lost', title: 'Connection lost · Execution state unknown', location: '',
        help: 'Reconnect to check execution state. The engine releases a paused execution when its lease expires.' };
    if (controlActions.has(state.pending?.action)) return { status: 'pending', title: 'Execution state pending', location: '',
        help: 'Check the pending command result before sending another execution command.' };
    if (session.status === 'running') return { status: 'running', title: 'Running · Waiting for the next stop', location: '',
        help: 'Execution is running. Pause requests a stop at the next eligible line.' };
    if (session.status === 'armed') return { status: 'armed', title: 'Armed · Waiting for next execution', location: '',
        help: session.reason === 'Execution completed' ? 'Last execution completed. The debugger is waiting for the next matching execution.'
            : session.reason === 'Execution threw an exception' ? 'Last execution threw an exception. The debugger is waiting for the next matching execution.'
                : 'Send a message or trigger a matching execution to reach the next stop.' };
    if (session.status !== 'paused') return null;

    const frame = session.frames?.[0];
    const detail = detailed(state.source, frame) ? state.source : detailed(cachedSource, frame) ? cachedSource : null;
    const source = detail || (verified(state.source, frame) ? state.source : state.sources?.find(item => verified(item, frame)));
    const line = Number.isInteger(frame?.line) && frame.line > 0 ? frame.line : null;
    const mapping = detail && line ? mappingAt(detail, line) : null;
    const stage = stageLabel(session, source, mapping);
    const title = [reasons[session.reason] || 'Paused', stage].filter(Boolean).join(' · ');
    const location = [mapping?.name || (detail && session.reason === 'entry' ? 'Generated script entry' : ''),
        line ? `Generated line ${line}` : 'Pause location unavailable',
        channelName,
        session.messageId != null ? `Message ${session.messageId}` : '',
        Number(session.sendAttempts) > 1 ? `Attempt ${session.sendAttempts}` : '',
        frame && !detail ? 'Loading script details…' : !frame ? 'Waiting for paused frame details…' : '',
    ].filter(Boolean).join(' · ');
    const help = [session.reason === 'exception' && session.exception,
        'Continue runs until the next stop. Step over advances one line.',
    ].filter(Boolean).join(' · ');
    return { status: 'paused', title, location, sourceName: source?.name, help };
}

export function ExecutionStatus({ state, channelName }) {
    const cached = React.useRef(null);
    const frame = !state?.lost && state?.session?.status === 'paused' ? state.session.frames?.[0] : null;
    const key = frame ? JSON.stringify([state.session.id, frame.sourceId, frame.sourceHash, frame.sourceGeneration]) : null;
    const source = detailed(state?.source, frame) ? state.source : cached.current?.key === key ? cached.current.source : null;
    React.useEffect(() => { cached.current = key && source ? { key, source } : null; }, [key, source]);
    const model = executionStatusModel(state, channelName, source);
    if (!model) return null;
    return <div className={`dbg-execution dbg-execution-${model.status}`} role="status" aria-live="polite" aria-atomic="true">
        <p className="dbg-execution-title"><strong>{model.title}</strong></p>
        {model.location && <p className="dbg-execution-location">{model.location}</p>}
        {model.sourceName && <details className="dbg-execution-source"><summary>Deployed script</summary><code>{model.sourceName}</code></details>}
        <p className="dbg-execution-help">{model.help}</p>
    </div>;
}
