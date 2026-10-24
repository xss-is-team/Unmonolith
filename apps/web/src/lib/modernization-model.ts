export type ModernizationScenarioId = string;
export type ModernizationScenarioKind = "java-migration" | "decomposition" | "optimization";
export type PlanStepKind = "start" | "task" | "gateway" | "call" | "end";
export type JavaTarget = "11" | "16" | "17" | "21";
export type ModernizationModuleId = string;

export type ModernizationOperation =
  | { id: "migration.set_target_java"; parameters: { targetJava: JavaTarget } }
  | { id: "decomposition.extract_modules"; parameters: { modules: ModernizationModuleId[] } }
  | { id: "optimization.index_by_key"; parameters: { key: "Order.id" } };

export type ModernizationModule = {
  id: ModernizationModuleId;
  label: string;
};

export type ModernizationFilePreview = {
  path: string;
  change: "modified" | "added";
  before: string;
  after: string;
};

export type ModernizationPlanStep = {
  id: string;
  label: string;
  kind: PlanStepKind;
  detail: string;
  operation?: ModernizationOperation;
};

export type ModernizationScenario = {
  id: ModernizationScenarioId;
  kind: ModernizationScenarioKind;
  title: string;
  shortTitle: string;
  summary: string;
  scope: string;
  rationale: string;
  expectedChanges: string[];
  preview: { files: ModernizationFilePreview[]; metric: string };
  moduleBoundaries?: ModernizationModule[];
  steps: ModernizationPlanStep[];
  edges: { source: string; target: string; label?: string }[];
};

/** Normalized frontend catalog; API data should be adapted to this shape when its contract exists. */
export type ModernizationCatalog = {
  scenarios: ModernizationScenario[];
};
