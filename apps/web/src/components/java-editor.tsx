"use client";

import { useEffect, useRef } from "react";
import { EditorView } from "@codemirror/view";
import CodeMirror from "@uiw/react-codemirror";
import { java } from "@codemirror/lang-java";

export function JavaEditor({
  value,
  onChange,
  readOnly = false,
  focusRange,
}: {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  focusRange?: { start: number; length: number } | null;
}) {
  const view = useRef<EditorView | null>(null);

  useEffect(() => {
    if (!view.current) return;
    if (!focusRange) {
      const selection = view.current.state.selection.main;
      if (!selection.empty) view.current.dispatch({ selection: { anchor: selection.head } });
      return;
    }
    const from = Math.max(0, Math.min(focusRange.start, view.current.state.doc.length));
    const to = Math.max(from, Math.min(from + focusRange.length, view.current.state.doc.length));
    view.current.dispatch({
      selection: { anchor: from, head: to },
      effects: EditorView.scrollIntoView(from, { y: "center" }),
    });
    view.current.focus();
  }, [focusRange]);

  return (
    <CodeMirror
      value={value}
      height="100%"
      theme="dark"
      extensions={[java()]}
      onChange={onChange}
      onCreateEditor={(editorView) => { view.current = editorView; }}
      readOnly={readOnly}
      basicSetup={{
        foldGutter: true,
        highlightActiveLine: false,
        highlightSelectionMatches: true,
      }}
      className="java-editor"
      aria-label="Исходный код Java"
    />
  );
}
