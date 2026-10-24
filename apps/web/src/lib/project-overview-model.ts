import type {
  ProjectGraph,
  ProjectGraphEdge,
  ProjectGraphNode,
  SourceRange,
} from "@/lib/api";

export type ProjectOverviewMethod = {
  id: string;
  methodId?: string;
  /** Entry node id of the method's CFG ("m<k>.n0"); null for abstract methods or a failed CFG build. */
  cfgEntry?: string | null;
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

export type ProjectOverviewRelationshipKind = Exclude<ProjectGraphEdge["kind"], "contains">;

export type ProjectOverviewRelationshipDirection = {
  from: string;
  to: string;
  total: number;
  kinds: { kind: ProjectOverviewRelationshipKind; count: number }[];
};

export type ProjectOverviewRelationship = {
  /** Stable endpoints for the single visual edge between this unordered type pair. */
  from: string;
  to: string;
  /** Sum of counts across all relationship kinds and both directions. */
  total: number;
  kinds: { kind: ProjectOverviewRelationshipKind; count: number }[];
  /** One entry for each direction represented by the visual edge. */
  directions: ProjectOverviewRelationshipDirection[];
};

export type ProjectOverviewModel = {
  scope: ProjectGraph["scope"];
  types: ProjectOverviewType[];
  /** Aggregated type-to-type relationships: one entry per unordered type pair. */
  relationships: ProjectOverviewRelationship[];
  /** Undirected type adjacency used for neighbor highlighting. */
  neighbors: Map<string, Set<string>>;
  typeById: Map<string, ProjectOverviewType>;
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

const RELATIONSHIP_KIND_ORDER: Record<ProjectOverviewRelationshipKind, number> = {
  calls: 0,
  constructs: 1,
  depends_on: 2,
  extends: 3,
  implements: 4,
};

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
        cfgEntry: child.cfg_entry ?? null,
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
  const pairDirections = new Map<string, {
    first: string;
    second: string;
    directions: Map<string, {
      from: string;
      to: string;
      kinds: Map<ProjectOverviewRelationshipKind, number>;
    }>;
  }>();

  for (const edge of graph.edges) {
    if (edge.kind === "contains") continue;
    const fromNode = nodeById.get(edge.from);
    const toNode = nodeById.get(edge.to);
    const from = fromNode && isProjectType(fromNode) ? fromNode.id : ownerById.get(edge.from);
    const to = toNode && isProjectType(toNode) ? toNode.id : ownerById.get(edge.to);
    if (!from || !to || !typeIds.has(from) || !typeIds.has(to) || from === to) continue;

    const [first, second] = from.localeCompare(to) <= 0 ? [from, to] : [to, from];
    const pairKey = JSON.stringify([first, second]);
    const pair = pairDirections.get(pairKey) ?? { first, second, directions: new Map() };
    const directionKey = JSON.stringify([from, to]);
    const direction = pair.directions.get(directionKey) ?? { from, to, kinds: new Map() };
    direction.kinds.set(edge.kind, (direction.kinds.get(edge.kind) ?? 0) + edge.count);
    pair.directions.set(directionKey, direction);
    pairDirections.set(pairKey, pair);
  }

  const relationships: ProjectOverviewRelationship[] = [...pairDirections.values()]
    .map((pair) => {
      const directions = [...pair.directions.values()]
        .map((direction) => ({
          from: direction.from,
          to: direction.to,
          total: [...direction.kinds.values()].reduce((sum, count) => sum + count, 0),
          kinds: sortedKinds(direction.kinds),
        }))
        .sort((left, right) => left.from.localeCompare(right.from, "ru") || left.to.localeCompare(right.to, "ru"));
      const combinedKinds = new Map<ProjectOverviewRelationshipKind, number>();
      for (const direction of directions) {
        for (const item of direction.kinds) {
          combinedKinds.set(item.kind, (combinedKinds.get(item.kind) ?? 0) + item.count);
        }
      }
      const onlyDirection = directions.length === 1 ? directions[0] : null;
      return {
        from: onlyDirection?.from ?? pair.first,
        to: onlyDirection?.to ?? pair.second,
        total: directions.reduce((sum, direction) => sum + direction.total, 0),
        kinds: sortedKinds(combinedKinds),
        directions,
      };
    })
    .sort((left, right) => left.from.localeCompare(right.from, "ru") || left.to.localeCompare(right.to, "ru"));

  const neighbors = new Map<string, Set<string>>();
  const addNeighbor = (a: string, b: string) => {
    (neighbors.get(a) ?? neighbors.set(a, new Set()).get(a)!).add(b);
    (neighbors.get(b) ?? neighbors.set(b, new Set()).get(b)!).add(a);
  };
  for (const relationship of relationships) addNeighbor(relationship.from, relationship.to);

  return {
    scope: graph.scope,
    types,
    relationships,
    neighbors,
    typeById: new Map(types.map((type) => [type.id, type])),
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

/**
 * Resolve a project-graph method node to the selector of `/methods`.
 * `method_id` is not unique for methods of local classes, so the CFG entry
 * ("m<k>.n0" → selector "m<k>") is authoritative when present.
 */
export function resolveCfgSelector(
  method: ProjectOverviewMethod,
  methods: { selector: string; id: string }[],
): { selector: string } | "no_cfg" | "ambiguous" {
  if (method.cfgEntry) {
    const selector = method.cfgEntry.split(".")[0] ?? "";
    if (methods.some((item) => item.selector === selector)) return { selector };
  }
  const matches = methods.filter((item) => item.id === method.methodId);
  if (matches.length === 1) return { selector: matches[0].selector };
  if (matches.length > 1) return "ambiguous";
  return method.cfgEntry ? "ambiguous" : "no_cfg";
}

function sortedKinds(kinds: Map<ProjectOverviewRelationshipKind, number>) {
  return [...kinds.entries()]
    .map(([kind, count]) => ({ kind, count }))
    .sort((left, right) => RELATIONSHIP_KIND_ORDER[left.kind] - RELATIONSHIP_KIND_ORDER[right.kind]);
}
