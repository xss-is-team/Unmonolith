"use client";

import { memo } from "react";
import { BaseEdge, getSmoothStepPath, type Edge, type EdgeProps } from "@xyflow/react";

/**
 * An edge drawn along the orthogonal route ELK computed for it (start, bend points, end), so parallel edges keep the
 * spacing of the layout instead of being redrawn on top of each other. Without a route it falls back to a step path.
 */
export type Point = { x: number; y: number };
export type RoutedData = { points?: Point[] | null };

/** Path through the bend points ELK routed for this edge, with small rounded corners. */
function routedPath(points: Point[], radius = 8) {
  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const [prev, at, next] = [points[i - 1], points[i], points[i + 1]];
    const r = Math.min(radius, Math.hypot(at.x - prev.x, at.y - prev.y) / 2, Math.hypot(next.x - at.x, next.y - at.y) / 2);
    const before = { x: at.x - Math.sign(at.x - prev.x) * r, y: at.y - Math.sign(at.y - prev.y) * r };
    const after = { x: at.x + Math.sign(next.x - at.x) * r, y: at.y + Math.sign(next.y - at.y) * r };
    d += ` L ${before.x} ${before.y} Q ${at.x} ${at.y} ${after.x} ${after.y}`;
  }
  const last = points[points.length - 1];
  return `${d} L ${last.x} ${last.y}`;
}

/** Midpoint of the longest segment: where a label is least likely to sit on another line. */
function labelPoint(points: Point[]) {
  let best = { x: points[0].x, y: points[0].y, length: -1 };
  for (let i = 1; i < points.length; i++) {
    const length = Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
    if (length > best.length) best = { x: (points[i].x + points[i - 1].x) / 2, y: (points[i].y + points[i - 1].y) / 2, length };
  }
  return best;
}

export const RoutedEdge = memo(function RoutedEdge(props: EdgeProps<Edge<RoutedData>>) {
  const points = props.data?.points;
  if (points && points.length >= 2) {
    const at = labelPoint(points);
    return <BaseEdge path={routedPath(points)} markerStart={props.markerStart} markerEnd={props.markerEnd} style={props.style} label={props.label} labelX={at.x} labelY={at.y}
      labelStyle={props.labelStyle} labelShowBg labelBgStyle={props.labelBgStyle} labelBgPadding={props.labelBgPadding} labelBgBorderRadius={props.labelBgBorderRadius} />;
  }
  const [path, labelX, labelY] = getSmoothStepPath(props);
  return <BaseEdge path={path} markerStart={props.markerStart} markerEnd={props.markerEnd} style={props.style} label={props.label} labelX={labelX} labelY={labelY}
    labelStyle={props.labelStyle} labelShowBg labelBgStyle={props.labelBgStyle} labelBgPadding={props.labelBgPadding} labelBgBorderRadius={props.labelBgBorderRadius} />;
});


/** Routes of an ELK layout result by edge id: [start, ...bends, end]. */
export function elkRoutes(edges: unknown[] | undefined) {
  const routes = new Map<string, Point[]>();
  for (const edge of (edges ?? []) as { id: string; sections?: { startPoint: Point; endPoint: Point; bendPoints?: Point[] }[] }[]) {
    const section = edge.sections?.[0];
    if (section) routes.set(edge.id, [section.startPoint, ...(section.bendPoints ?? []), section.endPoint]);
  }
  return routes;
}
