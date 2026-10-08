import { platform } from '@oie/web-shell';

const React = platform.React;
const flag = (value, fallback = false) => typeof value === 'boolean' ? value : fallback;
const filterText = value => typeof value === 'string' && value.trim() ? value
    : typeof value === 'number' && Number.isFinite(value) ? String(value) : null;
const enabled = value => value ? 'On' : 'Off';

/** Original target filters are distinct from the session's current execution identity. */
export function sessionSettingsModel(state, channelName) {
    const session = state?.session;
    if (!session) return null;
    const title = state.lost ? 'Last confirmed session settings'
        : session.status === 'closed' ? 'Ended session settings' : 'Session settings';
    const help = state.lost ? 'These settings were last confirmed before the connection was lost.'
        : session.status === 'closed' ? 'Clear the session to choose settings and arm again.' : 'End the session to change these settings. Leaving this screen releases the session.';
    const options = state.armOptions;
    if (!options) return { title, help, fields: null };

    const connector = filterText(options.connectorId), sourceId = filterText(options.sourceId);
    const source = sourceId ? (state.sources?.find(item => item.id === sourceId)
        || (state.source?.id === sourceId ? state.source : null)) : null;
    const requestedPause = typeof options.maxPauseMillis === 'number' && Number.isFinite(options.maxPauseMillis)
        ? Math.trunc(options.maxPauseMillis) : 60000;
    const maxPause = typeof session.maxPauseMillis === 'number' && Number.isFinite(session.maxPauseMillis)
        ? session.maxPauseMillis : Math.max(1000, Math.min(120000, requestedPause));
    const lifecycle = flag(session.includeLifecycle, flag(options.includeLifecycle) || options.channelId === 'global');
    const evaluation = flag(session.evaluationEnabled, flag(options.evaluationEnabled));
    const kind = filterText(options.scriptKind);
    return { title, help, fields: [
        ['Channel', options.channelId === 'global' ? 'Global scripts' : channelName || filterText(options.channelId) || 'Unavailable'],
        ['Message filter', filterText(options.messageId) || 'Any message'],
        ['Connector filter', connector === '0' ? 'Source (0)' : connector && /^[1-9]\d*$/.test(connector)
            ? `Destination ${connector}` : connector ? `Connector ${connector}` : 'Any connector'],
        ['Script kind', kind === 'javascript' ? 'JavaScript' : kind ? kind.replace(/[-_]/g, ' ') : 'Any kind'],
        ['Deployed source', sourceId ? source?.name || sourceId : 'Any script'],
        ['Break on entry', enabled(flag(options.breakOnEntry, true))],
        ['Pause on exceptions', enabled(flag(options.pauseOnExceptions))],
        ['Lifecycle / scripts without a message', enabled(lifecycle)],
        ['Evaluation', enabled(evaluation)],
        ['Maximum pause', `${maxPause / 1000} ${maxPause === 1000 ? 'second' : 'seconds'}`],
    ] };
}

export function SessionSettings({ state, channelName }) {
    const model = sessionSettingsModel(state, channelName);
    if (!model) return null;
    return <section className="dbg-session-settings">
        <h2>{model.title}</h2>
        {model.fields ? <dl className="dbg-settings-grid">
            {model.fields.map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}
        </dl> : <p>Settings unavailable for this session.</p>}
        <p className="dbg-muted">{model.help}</p>
    </section>;
}
