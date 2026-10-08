import { platform } from '@oie/web-shell';

const React = platform.React;
const owns = (value, key) => Object.hasOwn(value, key);
const properties = value => value.properties && typeof value.properties === 'object' && !Array.isArray(value.properties) ? value.properties : null;

function preview(value) {
    if (value && typeof value === 'object') return typeof value.type === 'string' ? value : { type: 'unavailable' };
    return { type: value === null ? 'null' : typeof value, value };
}

function rows(value) {
    if (value.type === 'xml') {
        const result = [];
        if (Array.isArray(value.attributes)) value.attributes.forEach((item, index) => result.push({ key: `attribute:${index}`, name: `@${item?.name || index}`, value: item }));
        if (owns(value, 'text')) result.push({ key: 'text', name: 'Text', value: value.text });
        if (Array.isArray(value.children)) value.children.forEach((item, index) => result.push({ key: `child:${index}`, name: item?.name || `[${index}]`, value: item }));
        return result;
    }
    return Object.entries(properties(value) || {}).map(([name, child]) => ({ key: name, name, value: child }));
}

function summary(value) {
    switch (value.type) {
        case 'string': return owns(value, 'value') ? JSON.stringify(value.value) : `String${owns(value, 'length') ? ` (${value.length} characters)` : ''} · Value not captured`;
        case 'number':
        case 'boolean': return owns(value, 'value') ? String(value.value) : 'Value not captured';
        case 'null': return 'null';
        case 'undefined': return 'undefined';
        case 'accessor': return 'Getter not invoked';
        case 'circular': return 'Already shown';
        case 'truncated': return 'Preview truncated';
        case 'function': return 'Function · Not inspected';
        case 'opaque': return `${value.className?.split('.').pop() || 'Object'} · Not inspected`;
        case 'object': {
            const label = owns(value, 'length') ? `Array(${value.length})` : 'Object';
            const captured = properties(value);
            if (!captured) return `${label} · Not inspected`;
            if (!Object.keys(captured).length && !value.truncated && !value.notice) return `${label} · Empty`;
            return label;
        }
        case 'xml-list': return properties(value) ? 'XML list' : 'XML list · Not inspected';
        case 'xml': {
            if (owns(value, 'value')) return JSON.stringify(value.value);
            return `<${value.name || 'XML'}${rows(value).length || value.truncated ? '' : ' /'}>`;
        }
        default: return 'Value not captured';
    }
}

function Notes({ value }) {
    return <>
        {value.truncated && value.type !== 'truncated' && <span className="dbg-value-note">Preview truncated</span>}
        {value.notice && <span className="dbg-value-note">{String(value.notice)}</span>}
    </>;
}

/** Expand only the bounded DTO already supplied by the paused worker. */
export function ValuePreview({ name, value }) {
    const item = preview(value), children = rows(item);
    const text = summary(item);
    const fullText = (item.type === 'string' || item.type === 'xml') && typeof item.value === 'string'
        && (text.length > 120 || /[\r\n]/.test(item.value)) ? item.value : null;
    const shortText = fullText !== null && text.length > 120 ? `${text.slice(0, 117)}…` : text;
    const label = <>
        {name !== undefined && name !== '' && <code className="dbg-value-name">{String(name)}</code>}
        <code className="dbg-value-summary" title={item.className || undefined}>{shortText}</code>
        <Notes value={item} />
    </>;
    if (!children.length && fullText === null) return <div className="dbg-value dbg-value-row">{label}</div>;
    return <details className="dbg-value">
        <summary className="dbg-value-row">{label}</summary>
        {fullText !== null && <pre className="dbg-value-full">{fullText}</pre>}
        {children.length > 0 && <div className="dbg-value-children">
            {children.map(child => <ValuePreview key={child.key} name={child.name} value={child.value} />)}
        </div>}
    </details>;
}

/** A scope's captured properties, retaining explicit unavailable/truncated states. */
export function PropertiesPreview({ value, emptyText = 'No properties.' }) {
    const item = preview(value), children = rows(item), captured = properties(item);
    if (!children.length && !captured) return <div className="dbg-properties"><ValuePreview value={item} /></div>;
    return <div className="dbg-properties">
        {children.length ? children.map(child => <ValuePreview key={child.key} name={child.name} value={child.value} />)
            : <p className="dbg-value-note">{item.truncated || item.notice ? 'No captured properties.' : emptyText}</p>}
        <Notes value={item} />
    </div>;
}
