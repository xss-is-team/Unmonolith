"use client";

import { forwardRef, useEffect, useImperativeHandle, useRef } from "react";
import { EditorState, RangeSet, StateEffect, StateField, Text } from "@codemirror/state";
import {
  EditorView,
  Decoration,
  GutterMarker,
  ViewPlugin,
  gutter,
  hoverTooltip,
  keymap,
  lineNumbers,
  highlightSpecialChars,
  drawSelection,
  dropCursor,
  rectangularSelection,
  crosshairCursor,
  highlightActiveLine,
  type Tooltip,
} from "@codemirror/view";
import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { foldGutter, foldKeymap, indentOnInput, syntaxHighlighting, defaultHighlightStyle, bracketMatching, HighlightStyle } from "@codemirror/language";
import { searchKeymap, highlightSelectionMatches } from "@codemirror/search";
import { closeBrackets, closeBracketsKeymap, completionKeymap } from "@codemirror/autocomplete";
import { java } from "@codemirror/lang-java";
import { tags } from "@lezer/highlight";
import type { Diagnostic, SourceRange } from "@/lib/api";

export type JavaEditorHandle = {
  getValue: () => string;
  markSaved: (savedValue: string) => void;
};

// Custom light theme matching the existing editor styles
const javaTheme = EditorView.theme({
  "&": { height: "100%", fontSize: "12px", fontFamily: "var(--font-code), monospace" },
  ".cm-scroller": { minHeight: "100%", padding: "13px 0 25px", lineHeight: "1.72", fontFamily: "inherit" },
  ".cm-content": { color: "#283b37" },
  ".cm-gutters": { border: "0", background: "#fbfaf6", color: "#a2a79d", fontFamily: "var(--font-code), monospace", fontSize: "10px" },
  ".cm-lineNumbers .cm-gutterElement": { minWidth: "43px", padding: "0 12px 0 10px" },
  ".cm-activeLine": { background: "rgba(222,231,213,.28)" },
  ".cm-cursor": { borderLeftColor: "#226a58" },
  ".cm-selectionBackground, ::selection": { background: "rgba(167,201,74,.25) !important" },
  ".cm-focused .cm-selectionBackground": { background: "rgba(167,201,74,.3) !important" },
  ".cm-selectionMatch": { background: "rgba(23,76,67,.12)" },
  ".cm-foldGutter .cm-gutterElement": { padding: "0 4px" },
  ".cm-diagnosticMark": { textDecoration: "underline wavy", textUnderlineOffset: "3px", cursor: "help" },
  ".cm-diagnosticMark--error": { textDecorationColor: "#b6533d" },
  ".cm-diagnosticMark--warning": { textDecorationColor: "#c18a3d" },
  ".cm-diagnosticMark--info": { textDecorationColor: "#4a7790" },
  ".cm-diagnostic-gutter": { width: "12px" },
  ".cm-diagnostic-gutter .cm-gutterElement": { width: "12px", padding: "0", display: "flex", justifyContent: "center" },
  ".cm-diagnostic-marker": { display: "block", width: "6px", height: "6px", borderRadius: "50%", background: "#4a7790" },
  ".cm-diagnostic-marker--error": { background: "#b6533d" },
  ".cm-diagnostic-marker--warning": { background: "#c18a3d" },
  ".cm-diagnostic-tooltip": { maxWidth: "360px", padding: "9px 11px", border: "1px solid #d8d9cf", borderRadius: "6px", color: "#34453a", background: "#fffefa", boxShadow: "0 8px 24px rgba(34,50,42,.12)", font: "12px/1.45 system-ui, sans-serif" },
  ".cm-diagnostic-tooltip strong": { display: "block", marginBottom: "3px", fontFamily: "var(--font-code), monospace", fontSize: "10px" },
}, { dark: false });

const javaHighlight = HighlightStyle.define([
  { tag: tags.keyword, color: "#a8d8bf", fontWeight: "600" },
  { tag: tags.typeName, color: "#a9cbe0" },
  { tag: tags.className, color: "#a9cbe0", fontWeight: "600" },
  { tag: tags.definition(tags.variableName), color: "#e4e9dd" },
  { tag: tags.string, color: "#e6ca91" },
  { tag: tags.number, color: "#f0ad7b" },
  { tag: tags.lineComment, color: "#a5b6a8", fontStyle: "italic" },
  { tag: tags.blockComment, color: "#a5b6a8", fontStyle: "italic" },
  { tag: tags.operator, color: "#c1d5c9" },
  { tag: tags.punctuation, color: "#c1d5c9" },
  { tag: tags.annotation, color: "#e9bd85" },
]);

type MarkedDiagnostic = Diagnostic & { from: number; to: number; prefixEnd: number };
const diagnosticsField = StateField.define<readonly MarkedDiagnostic[]>({
  create: () => [],
  update: (diagnostics, transaction) => {
    if (transaction.docChanged) return [];
    const replacement = transaction.effects.find((effect) => effect.is(setDiagnostics));
    return replacement ? replacement.value : diagnostics;
  },
});
const setDiagnostics = StateEffect.define<readonly MarkedDiagnostic[]>();

function firstVisibleDiagnostic(diagnostics: readonly MarkedDiagnostic[], offset: number) {
  let low = 0;
  let high = diagnostics.length;
  while (low < high) {
    const middle = (low + high) >>> 1;
    if (diagnostics[middle].prefixEnd < offset) low = middle + 1;
    else high = middle;
  }
  return low;
}

class DiagnosticMarker extends GutterMarker {
  constructor(private readonly severity: Diagnostic["severity"]) { super(); }
  toDOM() {
    const marker = document.createElement("span");
    marker.className = `cm-diagnostic-marker cm-diagnostic-marker--${this.severity}`;
    marker.setAttribute("aria-hidden", "true");
    return marker;
  }
  eq(other: GutterMarker) { return other instanceof DiagnosticMarker && this.severity === other.severity; }
}

const diagnosticGutter = gutter({
  class: "cm-diagnostic-gutter",
  markers(view) {
    const ranges = [];
    const seen = new Set<number>();
    const diagnostics = view.state.field(diagnosticsField);
    for (const visible of view.visibleRanges) {
      for (let index = firstVisibleDiagnostic(diagnostics, visible.from); index < diagnostics.length; index += 1) {
        const item = diagnostics[index];
        if (item.from >= visible.to) break;
        const line = view.state.doc.lineAt(item.from);
        if (seen.has(line.from)) continue;
        seen.add(line.from);
        ranges.push(new DiagnosticMarker(item.severity).range(line.from));
      }
    }
    return RangeSet.of(ranges);
  },
});

const diagnosticOverviewRuler = (container: HTMLDivElement) => ViewPlugin.fromClass(class {
  private readonly resizeObserver: ResizeObserver;
  private readonly onClick = (event: MouseEvent) => {
    const marker = (event.target as HTMLElement).closest<HTMLButtonElement>("[data-diagnostic-line]");
    if (!marker) return;
    const line = Number(marker.dataset.diagnosticLine);
    if (!Number.isInteger(line) || line < 1 || line > this.view.state.doc.lines) return;
    this.view.dispatch({ effects: EditorView.scrollIntoView(this.view.state.doc.line(line).from, { y: "center" }) });
    this.view.focus();
  };

  constructor(private view: EditorView) {
    container.addEventListener("click", this.onClick);
    this.resizeObserver = new ResizeObserver(() => this.build());
    this.resizeObserver.observe(container);
    this.build();
  }

  update(update: import("@codemirror/view").ViewUpdate) {
    if (update.docChanged || update.geometryChanged
        || update.transactions.some((transaction) => transaction.effects.some((effect) => effect.is(setDiagnostics)))) {
      this.build();
    }
  }

  destroy() {
    container.removeEventListener("click", this.onClick);
    this.resizeObserver.disconnect();
    container.replaceChildren();
  }

  private build() {
    const height = Math.max(1, Math.floor(container.clientHeight));
    const lineCount = this.view.state.doc.lines;
    const buckets = new Map<number, { firstLine: number; lastLine: number; count: number; severities: Set<Diagnostic["severity"]> }>();
    for (const diagnostic of this.view.state.field(diagnosticsField)) {
      const line = this.view.state.doc.lineAt(diagnostic.from).number;
      const bucket = Math.min(height - 1, Math.floor(((line - 1) / lineCount) * height));
      const current = buckets.get(bucket);
      if (current) {
        current.lastLine = line;
        current.count += 1;
        current.severities.add(diagnostic.severity);
      } else {
        buckets.set(bucket, { firstLine: line, lastLine: line, count: 1, severities: new Set([diagnostic.severity]) });
      }
    }

    const colors: Record<Diagnostic["severity"], string> = {
      error: "#b6533d",
      warning: "#c18a3d",
      info: "#4a7790",
    };
    const severityLabels: Record<Diagnostic["severity"], string> = {
      error: "ошибка",
      warning: "предупреждение",
      info: "информация",
    };
    const fragment = document.createDocumentFragment();
    for (const [position, bucket] of buckets) {
      const marker = document.createElement("button");
      marker.type = "button";
      marker.className = "cm-diagnostic-overview-marker";
      marker.dataset.diagnosticLine = String(bucket.firstLine);
      marker.style.top = `${Math.min(position, Math.max(0, height - 3))}px`;
      const severities = ["error", "warning", "info"].filter((severity) => bucket.severities.has(severity as Diagnostic["severity"])) as Diagnostic["severity"][];
      const stops = severities.map((severity, index) => {
        const from = Math.round((index / severities.length) * 100);
        const to = Math.round(((index + 1) / severities.length) * 100);
        return `${colors[severity]} ${from}% ${to}%`;
      });
      marker.style.background = `linear-gradient(to right, ${stops.join(", ")})`;
      const lineLabel = bucket.firstLine === bucket.lastLine ? `строка ${bucket.firstLine}` : `строки ${bucket.firstLine}–${bucket.lastLine}`;
      const count = bucket.count;
      const noun = count % 10 === 1 && count % 100 !== 11
        ? "диагностика"
        : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14)
          ? "диагностики"
          : "диагностик";
      marker.title = `${lineLabel}: ${severities.map((severity) => severityLabels[severity]).join(", ")} · ${count} ${noun}`;
      marker.setAttribute("aria-label", marker.title);
      fragment.append(marker);
    }
    container.replaceChildren(fragment);
  }
});

const diagnosticDecorations = ViewPlugin.fromClass(class {
  decorations;
  constructor(private view: EditorView) { this.decorations = this.build(); }
  update(update: import("@codemirror/view").ViewUpdate) {
    if (update.docChanged || update.viewportChanged || update.transactions.some((transaction) => transaction.effects.some((effect) => effect.is(setDiagnostics)))) {
      this.decorations = this.build();
    }
  }
  private build() {
    const diagnostics = this.view.state.field(diagnosticsField);
    const ranges = [];
    for (const visible of this.view.visibleRanges) {
      for (let index = firstVisibleDiagnostic(diagnostics, visible.from); index < diagnostics.length; index += 1) {
        const item = diagnostics[index];
        if (item.from >= visible.to) break;
        const from = Math.max(item.from, visible.from);
        const to = Math.min(item.to, visible.to);
        if (to > from) ranges.push(Decoration.mark({ class: diagnosticMarkClass(item.severity) }).range(from, to));
      }
    }
    return Decoration.set(ranges, true);
  }
}, { decorations: (plugin) => plugin.decorations });

const diagnosticHover = hoverTooltip((view, position): Tooltip | null => {
  const diagnostics = view.state.field(diagnosticsField);
  let diagnostic: MarkedDiagnostic | undefined;
  for (let index = firstVisibleDiagnostic(diagnostics, position); index < diagnostics.length; index += 1) {
    const candidate = diagnostics[index];
    if (candidate.from > position) break;
    if (position <= Math.max(candidate.from, candidate.to)) {
      diagnostic = candidate;
      break;
    }
  }
  if (!diagnostic) return null;
  return {
    pos: diagnostic.from,
    end: Math.max(diagnostic.from + 1, diagnostic.to),
    above: true,
    create() {
      const dom = document.createElement("div");
      dom.className = "cm-diagnostic-tooltip";
      const code = document.createElement("strong");
      code.textContent = `${diagnostic.severity.toUpperCase()} · ${diagnostic.code}`;
      const message = document.createElement("span");
      message.textContent = diagnostic.message;
      dom.append(code, message);
      return { dom };
    },
  };
}, { hoverTime: 350 });

function diagnosticMarkClass(severity: Diagnostic["severity"]) {
  return `cm-diagnosticMark cm-diagnosticMark--${severity}`;
}

function buildExtensions(overviewRuler: HTMLDivElement) {
  return [
    lineNumbers(), diagnosticGutter, diagnosticOverviewRuler(overviewRuler), diagnosticsField, diagnosticDecorations, diagnosticHover,
    EditorView.contentAttributes.of({ "aria-label": "Исходный код Java" }),
    highlightSpecialChars(), history(), foldGutter(), drawSelection(), dropCursor(),
    EditorState.allowMultipleSelections.of(true), indentOnInput(),
    syntaxHighlighting(javaHighlight), syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    bracketMatching(), closeBrackets(), rectangularSelection(), crosshairCursor(),
    highlightActiveLine(), highlightSelectionMatches(),
    keymap.of([...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, ...foldKeymap, ...completionKeymap]),
    java(), javaTheme,
  ];
}

function mapDiagnostics(diagnostics: readonly Diagnostic[], doc: Text): MarkedDiagnostic[] {
  const mapped: Omit<MarkedDiagnostic, "prefixEnd">[] = [];
  for (const diagnostic of diagnostics) {
    const from = Math.max(0, Math.min(diagnostic.source_range.start, doc.length));
    const to = Math.max(from, Math.min(from + diagnostic.source_range.length, doc.length));
    mapped.push({ ...diagnostic, from, to });
  }
  mapped.sort((a, b) => a.from - b.from || a.to - b.to);
  let prefixEnd = -1;
  return mapped.map((item) => {
    prefixEnd = Math.max(prefixEnd, item.to);
    return { ...item, prefixEnd };
  });
}

export const JavaEditor = forwardRef<JavaEditorHandle, {
  value: string;
  diagnostics?: readonly Diagnostic[];
  readOnly?: boolean;
  focusRange?: SourceRange | null;
  onDirtyChange?: (dirty: boolean) => void;
  onMetricsChange?: (metrics: { lineCount: number; byteLength: number }) => void;
  onSourceSelection?: (range: SourceRange | null) => void;
}>(function JavaEditor({ value, diagnostics = [], readOnly = false, focusRange, onDirtyChange, onMetricsChange, onSourceSelection }, ref) {
  const containerRef = useRef<HTMLDivElement>(null);
  const overviewRulerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const savedDocRef = useRef<Text | null>(null);
  const metricsTimerRef = useRef<number | undefined>(undefined);
  const callbacksRef = useRef({ onDirtyChange, onMetricsChange, onSourceSelection });
  callbacksRef.current = { onDirtyChange, onMetricsChange, onSourceSelection };

  useImperativeHandle(ref, () => ({
    getValue: () => viewRef.current?.state.doc.toString() ?? value,
    markSaved: (savedValue: string) => {
      if (!viewRef.current) return;
      savedDocRef.current = savedValue === viewRef.current.state.doc.toString()
        ? viewRef.current.state.doc
        : Text.of(savedValue.split("\n"));
      callbacksRef.current.onDirtyChange?.(!viewRef.current.state.doc.eq(savedDocRef.current));
    },
  }), [value]);

  useEffect(() => {
    if (!containerRef.current || !overviewRulerRef.current) return;
    const view = new EditorView({
      state: EditorState.create({
        doc: value,
        extensions: [
          ...buildExtensions(overviewRulerRef.current),
          EditorState.readOnly.of(readOnly),
          EditorView.updateListener.of((update) => {
            const callbacks = callbacksRef.current;
            if (update.docChanged) {
              callbacks.onDirtyChange?.(!update.state.doc.eq(savedDocRef.current ?? update.startState.doc));
              callbacks.onSourceSelection?.(null);
              // The editor owns source text while typing. React only receives compact stats.
              if (metricsTimerRef.current !== undefined) window.clearTimeout(metricsTimerRef.current);
              metricsTimerRef.current = window.setTimeout(() => {
                const source = update.view.state.doc.toString();
                callbacksRef.current.onMetricsChange?.({
                  lineCount: update.view.state.doc.lines,
                  byteLength: new TextEncoder().encode(source).byteLength,
                });
              }, 180);
            }
            if (update.selectionSet && !update.docChanged) {
              const selection = update.state.selection.main;
              callbacks.onSourceSelection?.(selection.empty ? null : { start: selection.from, length: selection.to - selection.from });
            }
          }),
        ],
      }),
      parent: containerRef.current,
    });
    viewRef.current = view;
    savedDocRef.current = view.state.doc;
    return () => {
      if (metricsTimerRef.current !== undefined) window.clearTimeout(metricsTimerRef.current);
      view.destroy();
      viewRef.current = null;
    };
    // The editor instance is stable for the lifetime of this mounted file.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const mapped = mapDiagnostics(diagnostics, view.state.doc);
    view.dispatch({ effects: setDiagnostics.of(mapped) });
  }, [diagnostics]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const from = Math.max(0, Math.min(focusRange?.start ?? 0, view.state.doc.length));
    if (!focusRange) return;
    const to = Math.max(from, Math.min(from + focusRange.length, view.state.doc.length));
    view.dispatch({ selection: { anchor: from, head: to }, effects: EditorView.scrollIntoView(from, { y: "center" }) });
    view.focus();
  }, [focusRange]);

  return (
    <div className="java-editor" aria-label="Исходный код Java" style={{ height: "100%" }}>
      <div ref={containerRef} className="java-editor-surface" />
      <div ref={overviewRulerRef} className="cm-diagnostic-overview" aria-label="Обзор диагностик по файлу" />
    </div>
  );
});
