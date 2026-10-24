import type {
  ProjectGraph,
  ProjectGraphEdge,
  ProjectGraphNode,
  SourceRange,
} from "@/lib/api";

export type ProjectOverviewMethod = {
  id: string;
  methodId?: string;
  name: string;
  kind: "method" | "constructor";
  sourceRange: SourceRange;
};

export type ProjectOverviewTypeKind = Extract<
  ProjectGraphNode["kind"],
  "class" | "interface" | "enum" | "record" | "annotation_type"
>;

export type ProjectOverviewType = {
  id: string;
  name: string;
  qualifiedName: string;
  packageName: string;
  sourceFile?: string;
  kind: ProjectOverviewTypeKind;
  sourceRange: SourceRange;
  methods: ProjectOverviewMethod[];
  externalSupertypes: { name: string; relation: "extends" | "implements" }[];
};

export type ProjectOverviewRelationship = {
  from: string;
  to: string;
  kind: Exclude<ProjectGraphEdge["kind"], "contains">;
  count: number;
};

export type ProjectOverviewModel = {
  scope: ProjectGraph["scope"];
  types: ProjectOverviewType[];
  relationships: ProjectOverviewRelationship[];
  unresolvedCalls: {
    id: string;
    expression: string;
    reason: string;
    sourceRange: SourceRange;
  }[];
  stats: ProjectGraph["stats"];
  externalCallCount: number;
};

const TYPE_KINDS = new Set<ProjectOverviewTypeKind>([
  "class", "interface", "enum", "record", "annotation_type",
]);

function isProjectType(node: ProjectGraphNode): node is ProjectGraphNode & { kind: ProjectOverviewTypeKind } {
  return TYPE_KINDS.has(node.kind as ProjectOverviewTypeKind);
}

/** Convert the parser/API graph into the smaller model used by the project overview UI. */
export function toProjectOverviewModel(graph: ProjectGraph): ProjectOverviewModel {
  const nodeById = new Map(graph.nodes.map((node) => [node.id, node]));
  const methodsByType = new Map<string, ProjectOverviewMethod[]>();
  const ownerById = new Map<string, string>();

  for (const edge of graph.edges) {
    if (edge.kind !== "contains") continue;
    const parent = nodeById.get(edge.from);
    const child = nodeById.get(edge.to);
    if (!parent || !isProjectType(parent) || !child) continue;

    if (child.kind === "method" || child.kind === "constructor") {
      ownerById.set(child.id, parent.id);
      methodsByType.set(parent.id, [...(methodsByType.get(parent.id) ?? []), {
        id: child.id,
        methodId: child.method_id ?? undefined,
        name: child.name,
        kind: child.kind,
        sourceRange: child.source_range,
      }]);
    }
  }

  const types = graph.nodes
    .filter(isProjectType)
    .map((node) => ({
      id: node.id,
      name: node.name,
      qualifiedName: node.qualified_name ?? node.name,
      packageName: "",
      sourceFile: node.source_range.file ?? undefined,
      kind: node.kind,
      sourceRange: node.source_range,
      methods: methodsByType.get(node.id) ?? [],
      externalSupertypes: (node.external_supertypes ?? []).map(({ name, relation }) => ({ name, relation })),
    }));

  // A file's top-level type gives us its Java package without guessing from the
  // physical source-root path. Nested types inherit that package by file.
  const packageByFile = new Map<string, string>();
  for (const type of types) {
    const fileName = type.sourceFile?.split(/[\\/]/).at(-1)?.replace(/\.java$/i, "");
    if (fileName && type.name === fileName && type.sourceFile) {
      const segments = type.qualifiedName.split(".");
      packageByFile.set(type.sourceFile, segments.slice(0, -1).join("."));
    }
  }
  for (const type of types) {
    if (type.sourceFile && packageByFile.has(type.sourceFile)) {
      type.packageName = packageByFile.get(type.sourceFile) ?? "";
    }
  }

  const typeIds = new Set(types.map((type) => type.id));
  const relationshipsByKey = new Map<string, ProjectOverviewRelationship>();

  for (const edge of graph.edges) {
    if (edge.kind === "contains") continue;
    const fromNode = nodeById.get(edge.from);
    const toNode = nodeById.get(edge.to);
    const from = fromNode && isProjectType(fromNode) ? fromNode.id : ownerById.get(edge.from);
    const to = toNode && isProjectType(toNode) ? toNode.id : ownerById.get(edge.to);
    if (!from || !to || !typeIds.has(from) || !typeIds.has(to)) continue;

    const key = `${from}:${to}:${edge.kind}`;
    const current = relationshipsByKey.get(key);
    if (current) current.count += edge.count;
    else relationshipsByKey.set(key, { from, to, kind: edge.kind, count: edge.count });
  }

  return {
    scope: graph.scope,
    types,
    relationships: [...relationshipsByKey.values()],
    unresolvedCalls: graph.unresolved_calls.map((call) => ({
      id: call.id,
      expression: call.expression,
      reason: call.reason,
      sourceRange: call.source_range,
    })),
    stats: graph.stats,
    externalCallCount: graph.stats.external_calls,
  };
}
