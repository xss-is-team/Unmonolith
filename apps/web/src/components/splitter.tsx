"use client";

import { useCallback, useRef } from "react";

/** A drag handle between two panes: reports the pointer movement along its axis, double click resets. */
export function Splitter({ axis, label, onResize, onReset, className = "" }: {
  axis: "x" | "y";
  label: string;
  onResize: (delta: number) => void;
  onReset?: () => void;
  className?: string;
}) {
  const last = useRef<number | null>(null);
  const onPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    last.current = axis === "x" ? event.clientX : event.clientY;
  }, [axis]);
  const onPointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (last.current === null) return;
    const now = axis === "x" ? event.clientX : event.clientY;
    const delta = now - last.current;
    if (delta !== 0) {
      last.current = now;
      onResize(delta);
    }
  }, [axis, onResize]);
  const stop = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    last.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }, []);
  return (
    <div
      role="separator"
      aria-orientation={axis === "x" ? "vertical" : "horizontal"}
      aria-label={label}
      title={`${label}: потяните, двойной клик — по умолчанию`}
      className={`group relative z-20 touch-none select-none ${axis === "x" ? "w-[5px] cursor-col-resize" : "h-[5px] cursor-row-resize"} bg-slate-800/60 hover:bg-cyan-600/60 active:bg-cyan-500 ${className}`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={stop}
      onPointerCancel={stop}
      onDoubleClick={onReset}
    />
  );
}

export function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}
