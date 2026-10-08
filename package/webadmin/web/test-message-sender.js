const REJECTED_STATUSES = new Set([400, 401, 403, 404, 405, 415]);
const binding = context => JSON.stringify([context.owner, context.channelId]);

export const HL7_SAMPLE = [
    'MSH|^~\\&|DEBUGGER_SOURCE|TEST_FACILITY|ORIGINAL_RECEIVER|TEST_FACILITY|20261008120000||ADT^A01|DEBUGGER-DEMO-001|T|2.5',
    'EVN|A01|20261008120000',
    'PID|1||SYNTHETIC-001^^^DEBUGGER^MR||doe^JANE||19800101|F',
    'PV1|1|O',
    '',
].join('\r');

export function messageSendAccess(host) {
    const supported = typeof host.api?.messages?.processNew === 'function' && typeof host.checkTask === 'function';
    if (!supported) return { supported: false, allowed: false };
    try { return { supported: true, allowed: host.checkTask('message', 'doSendMessage') === true }; }
    catch { return { supported: true, allowed: false }; }
}

export function sendUnavailableReason(context) {
    if (!context.valid || !context.owner) return 'Reopen the debugger after the login or engine changes.';
    if (!context.channelId || context.channelId === 'global') return 'Select a channel to send a test message.';
    if (context.supported === false) return 'This Web Administrator version does not provide the message-sending API and permission check.';
    if (!context.allowed) return 'Your account needs Send Message permission for this action.';
    if (context.messageFilter?.trim()) {
        if (context.session?.status === 'paused') return `This session targets message ${context.messageFilter.trim()}. Continue or end the paused session before arming for a new message.`;
        if (['armed', 'running'].includes(context.session?.status)) return `This session targets message ${context.messageFilter.trim()}. Arm for a new message to send a new one.`;
        return 'Clear Message ID and arm a new session to debug a new test message.';
    }
    if (!context.session || context.session.channelId !== context.channelId || context.session.status === 'closed') return 'Arm the debugger for this channel before sending.';
    if (context.session.status === 'paused') return 'Continue the paused message before sending another.';
    if (!['armed', 'running'].includes(context.session.status)) return 'Wait for the debugger session to become ready.';
    return '';
}

/** A message send is not idempotent. Never retry automatically or cancel engine work. */
export class TestMessageSender {
    constructor({ processMessage, context, changed = () => {} }) {
        this.processMessage = processMessage;
        this.context = context;
        this.changed = changed;
        this.binding = binding(context());
        this.alive = true;
        this.state = { status: 'idle', message: '', messageId: null };
    }
    current() {
        const context = this.context();
        return this.alive && context.valid && context.owner && binding(context) === this.binding;
    }
    emit(state) {
        if (!this.current()) return;
        this.state = { ...this.state, ...state };
        this.changed(this.state);
    }
    async submit(rawData) {
        if (!this.current()) throw new Error('The login or target channel changed. Reopen the debugger.');
        if (this.state.status === 'sending') return;
        if (this.state.status === 'unknown') throw new Error('Check the previous message in the engine before sending again.');
        const context = this.context(), reason = sendUnavailableReason(context);
        if (reason) throw new Error(reason);
        if (!rawData.trim()) throw new Error('Enter a raw message or load the synthetic HL7 sample.');
        this.emit({ status: 'sending', message: 'Message submitted; waiting for the engine. Use the debugger controls while execution is paused.', messageId: null });
        try {
            // The host helper's null destination list means all deployed destinations.
            // Omitted or [] would silently select source-only processing.
            const result = await this.processMessage(context.channelId, rawData, null, []);
            const messageId = (typeof result === 'number' && Number.isFinite(result)) || (typeof result === 'string' && /^\d+$/.test(result)) ? String(result) : null;
            this.emit({ status: 'sent', messageId, message: messageId ? `Message ${messageId} accepted by the engine.` : 'Message accepted by the engine.' });
        } catch (error) {
            const rejected = REJECTED_STATUSES.has(Number(error?.status));
            const detail = error?.message || 'The request failed without a confirmed response.';
            this.emit({ status: rejected ? 'rejected' : 'unknown', messageId: null, message: rejected
                ? `Message rejected: ${detail}`
                : `Send outcome unknown: ${detail} The message may still be processing. Do not resend until you have checked its result.` });
        }
    }
    acknowledgeUnknown() {
        if (this.current() && this.state.status === 'unknown') this.emit({ status: 'idle', message: 'Previous outcome acknowledged. A new send will create another message.', messageId: null });
    }
    dispose() { this.alive = false; }
}
