import type { SourceRange } from "@/lib/api";

export type ProjectFlowNodeKind = "start" | "end" | "process" | "decision" | "input_output" | "subprocess";

export type ProjectFlowNode = {
  id: string;
  label: string;
  kind: ProjectFlowNodeKind;
  description: string;
  childFlowId?: string;
  source?: { file: string; range: SourceRange };
};

export type ProjectFlow = {
  id: string;
  title: string;
  scope: string;
  description: string;
  nodes: ProjectFlowNode[];
  edges: { from: string; to: string; label?: string }[];
};

/** Normalized frontend model. API responses should be validated and mapped here. */
export type ProjectFlowCatalog = {
  rootFlowId: string;
  flows: Record<string, ProjectFlow>;
};
