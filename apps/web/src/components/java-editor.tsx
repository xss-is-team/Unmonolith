"use client";

import CodeMirror from "@uiw/react-codemirror";
import { java } from "@codemirror/lang-java";

export function JavaEditor({
  value,
  onChange,
  readOnly = false,
}: {
  value: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
}) {
  return (
    <CodeMirror
      value={value}
      height="100%"
      theme="light"
      extensions={[java()]}
      onChange={onChange}
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
