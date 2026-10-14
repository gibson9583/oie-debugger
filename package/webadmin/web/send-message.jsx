import { platform } from '@oie/web-shell';
import { ownerContext } from './debugger-client.js';
import { TestMessageSender, messageSendAccess, sendUnavailableReason } from './test-message-sender.js';

const React = platform.React;
const { useEffect, useRef, useState } = React;

/** Inline submission keeps the mounted debugger and its lease alive. */
export function SendMessage({ channelId, channelName, session, getSession, valid, canSend = () => true, targetAllowed = true, disabled = false, messageFilter = '', canArmNewMessage = false, onArmNewMessage }) {
    const [draft, setDraft] = useState('');
    const [state, setState] = useState({ status: 'idle', message: '', messageId: null });
    const [error, setError] = useState('');
    const sender = useRef(null), context = useRef(null), callbacks = useRef(null);
    const owner = ownerContext(platform.store, document.cookie);
    callbacks.current = { valid, getSession };
    context.current = {
        owner, channelId, session, messageFilter,
        valid: !!valid(),
        ...messageSendAccess(platform),
    };
    // Recheck identity and client state for actions and completions even before React rerenders.
    const readContext = () => ({
        ...context.current,
        owner: ownerContext(platform.store, document.cookie),
        valid: !!callbacks.current.valid(),
        session: callbacks.current.getSession ? callbacks.current.getSession() : context.current.session,
        ...messageSendAccess(platform),
    });
    useEffect(() => {
        const instance = new TestMessageSender({
            processMessage: (...args) => platform.api.messages.processNew(...args),
            context: readContext,
            changed: setState,
        });
        sender.current = instance;
        setDraft(''); setError(''); setState(instance.state);
        return () => { instance.dispose(); if (sender.current === instance) sender.current = null; };
    }, [owner, channelId]);

    const reason = sendUnavailableReason(context.current);
    const sending = state.status === 'sending', unknown = state.status === 'unknown';
    const locked = disabled || !targetAllowed || !context.current.valid || !channelId || channelId === 'global' || sending || unknown;
    const submit = async event => {
        event.preventDefault();
        setError('');
        if (disabled || !canSend() || !sender.current) return;
        const instance = sender.current;
        try { await instance.submit(draft); }
        catch (failure) { if (instance.current()) setError(failure.message); }
    };

    return <section className="dbg-send" aria-labelledby="dbg-send-title">
        <h2 id="dbg-send-title">Send a message</h2>
        <p className="dbg-muted">Send through {channelName || 'the selected channel'} and all enabled destinations. Stay on this screen to keep the debugger armed.</p>
        <form onSubmit={submit} className="dbg-form">
            <label className="dbg-field">Message<textarea rows={5} value={draft} onChange={event => setDraft(event.target.value)} disabled={locked} spellCheck={false} placeholder="Enter or paste a message in the format expected by this channel." /></label>
            <div className="dbg-row">
                <button type="submit" className="dbg-primary" disabled={locked || !!reason || !draft.trim()}>{sending ? 'Sending…' : 'Send message'}</button>
                {messageFilter.trim() && onArmNewMessage && <button type="button" disabled={disabled || sending || unknown || !canArmNewMessage} title="End this session and remove its Message ID filter. Your draft is kept." onClick={onArmNewMessage}>Arm for a new message</button>}
                {!sending && !unknown && reason && <span className="dbg-muted">{reason}</span>}
            </div>
        </form>
        {state.message && <p className={`dbg-notice${unknown ? ' dbg-warning' : state.status === 'rejected' ? ' dbg-error' : ''}`} role={unknown || state.status === 'rejected' ? 'alert' : 'status'}>{state.message}</p>}
        {unknown && <button type="button" disabled={disabled || !context.current.valid} onClick={() => sender.current?.acknowledgeUnknown()}>I checked the previous message result</button>}
        {error && <p className="dbg-notice dbg-error" role="alert">{error}</p>}
    </section>;
}
