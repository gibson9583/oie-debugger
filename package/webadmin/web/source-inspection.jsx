import { platform } from "@oie/web-shell";
import { sourceMatchesFrame } from "./debugger-client.js";
import { PropertiesPreview } from "./value-preview.jsx";
const React = platform.React;
const { useEffect, useRef, useState } = React;

export function SourceEditor({
  source,
  frame,
  pauseKey,
  breakpoints,
  disabled,
  toggle,
  revealLine,
}) {
  const host = useRef(null),
    editor = useRef(null),
    decorations = useRef(null);
  const latest = useRef({ source, frame, breakpoints, disabled, toggle });
  latest.current = { source, frame, breakpoints, disabled, toggle };
  const [monacoReady, setMonacoReady] = useState(false);
  useEffect(() => {
    if (!source || !host.current) return;
    let mouse,
      tries = 0,
      timer;
    const instance = platform.createCodeEditor({
      value: source.text,
      language: "javascript",
      readOnly: true,
      minHeight: "390px",
      ariaLabel: "Deployed JavaScript source",
    });
    editor.current = instance;
    host.current.appendChild(instance.el);
    setMonacoReady(false);
    const connect = () => {
      if (instance.monaco) {
        instance.monaco.updateOptions({ readOnly: true, glyphMargin: true });
        decorations.current = instance.monaco.createDecorationsCollection([]);
        mouse = instance.monaco.onMouseDown((event) => {
          const current = latest.current;
          if (
            event.target.type === 2 &&
            event.target.position &&
            !current.disabled &&
            current.source.breakableLines.includes(
              event.target.position.lineNumber,
            )
          ) {
            current.toggle(event.target.position.lineNumber);
          }
        });
        setMonacoReady(true);
      } else if (++tries < 100) timer = setTimeout(connect, 100);
    };
    connect();
    return () => {
      clearTimeout(timer);
      mouse?.dispose();
      decorations.current?.clear();
      decorations.current = null;
      instance.dispose();
      instance.el.remove();
      editor.current = null;
    };
  }, [source?.id, source?.hash, source?.generation]);
  useEffect(() => {
    if (!source || !monacoReady || !decorations.current) return;
    const lineRange = (line) => ({
      startLineNumber: line,
      endLineNumber: line,
      startColumn: 1,
      endColumn: 1,
    });
    const next = breakpoints
      .filter((p) => p.enabled)
      .map((p) => ({
        range: lineRange(p.line),
        options: {
          glyphMarginClassName: "dbg-breakpoint",
          glyphMarginHoverMessage: {
            value: `Breakpoint${p.condition ? ": " + p.condition : ""}`,
          },
        },
      }));
    if (sourceMatchesFrame(source, frame)) {
      next.push({
        range: lineRange(frame.line),
        options: {
          isWholeLine: true,
          className: "dbg-current-line",
          linesDecorationsClassName: "dbg-current-glyph",
        },
      });
    }
    decorations.current.set(next);
  }, [source, frame, breakpoints, monacoReady]);
  // Polls replace frame objects every 500ms. Only a new execution location may
  // move the viewport; keep manual scrolling and authored navigation intact.
  useEffect(() => {
    if (sourceMatchesFrame(source, frame) && editor.current?.monaco)
      editor.current.monaco.revealLineInCenterIfOutsideViewport(frame.line);
  }, [
    source?.id,
    source?.hash,
    source?.generation,
    frame?.sourceId,
    frame?.sourceHash,
    frame?.sourceGeneration,
    frame?.index,
    frame?.line,
    pauseKey,
    monacoReady,
  ]);
  useEffect(() => {
    if (!revealLine || !editor.current) return;
    if (editor.current.monaco)
      editor.current.monaco.revealLineInCenter(revealLine);
    else if (editor.current.area) {
      const area = editor.current.area;
      const start = source.text
        .split("\n")
        .slice(0, revealLine - 1)
        .reduce((n, text) => n + text.length + 1, 0);
      area.focus();
      area.setSelectionRange(start, start);
      area.scrollTop =
        Math.max(0, revealLine - 4) *
        (parseFloat(getComputedStyle(area).lineHeight) || 20);
    }
  }, [revealLine, source?.id, monacoReady]);
  return (
    <>
      <div ref={host} className="dbg-editor" />
      <div className="dbg-sourceinfo dbg-muted">
        {monacoReady
          ? "Click the gutter to toggle an executable breakpoint."
          : "Read-only source. Use the line controls below to set breakpoints."}{" "}
        Deployed source only; editor drafts are not used.
      </div>
    </>
  );
}

export function Variables({ inspection, frame, paused, disabled, refresh }) {
  const [scope, setScope] = useState("locals");
  if (!paused)
    return <p className="dbg-empty">Variables appear when a message pauses.</p>;
  if (!inspection || (inspection.frameIndex ?? 0) !== frame?.index)
    return <p className="dbg-empty">Loading variables for this call frame…</p>;
  return (
    <div className="dbg-variables">
      <div className="dbg-scopebar">
        <label htmlFor="dbg-scope">Scope</label>
        <select
          id="dbg-scope"
          value={scope}
          onChange={(event) => setScope(event.target.value)}
        >
          <option value="locals">Local variables</option>
          <option value="globals">Global variables and maps</option>
          <option value="this">This object</option>
        </select>
        <button disabled={disabled} onClick={refresh}>
          Refresh
        </button>
      </div>
      <p className="dbg-muted dbg-scopehint">
        {frame?.functionName || "Script"} · before the highlighted line
      </p>
      <div className="dbg-value-scroll">
        <PropertiesPreview
          value={inspection[scope]}
          emptyText="No values in this scope."
        />
      </div>
      <p className="dbg-muted dbg-scopehint">
        Captured values only. Getters are not invoked.
      </p>
    </div>
  );
}
