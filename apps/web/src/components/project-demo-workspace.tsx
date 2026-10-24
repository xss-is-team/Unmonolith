"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { useState } from "react";
import { ArrowLeft, CircleHelp } from "lucide-react";
import { Brand } from "@/components/brand";
import { ModernizationDemo } from "@/components/modernization-demo";
import { ProjectFlowchartDemo } from "@/components/project-flowchart-demo";
import graphFixtureData from "@/fixtures/project-graph.json";
import { demoModernizationCatalog } from "@/fixtures/modernization-catalog";
import { demoProjectFlowCatalog } from "@/fixtures/project-flows";
import { parseProjectGraph } from "@/lib/api";

const demo = {
  graph: parseProjectGraph(graphFixtureData),
  flows: demoProjectFlowCatalog,
  modernization: demoModernizationCatalog,
};

const ProjectGraphViewer = dynamic(
  () => import("@/components/project-graph-viewer").then((module) => module.ProjectGraphViewer),
  { ssr: false, loading: () => <section className="project-graph-panel project-graph-panel--loading">Открываем демонстрацию…</section> },
);

export function ProjectDemoWorkspace() {
  const [view, setView] = useState<"flows" | "structure" | "modernization">("flows");

  return (
    <main className="project-demo-screen">
      <header className="project-demo-topbar">
        <Brand />
        <div className="project-demo-topbar__label">ИНТЕРАКТИВНЫЙ ПРОТОТИП <i>·</i> ДЕМО-ДАННЫЕ</div>
        <Link className="project-demo-help" href="/">
          <ArrowLeft size={15} /> К загрузке Java
        </Link>
      </header>
      <div className="project-demo-disclaimer" role="note">
        <CircleHelp size={15} />
        <span>Это демонстрационный сценарий: он не читает исходный код и не выполняет миграцию.</span>
      </div>
      <div className="project-demo-content">
        {view === "flows" ? (
          <ProjectFlowchartDemo
            catalog={demo.flows}
            onBackToStructure={() => setView("structure")}
            onOpenModernization={() => setView("modernization")}
          />
        ) : view === "modernization" ? (
          <ModernizationDemo catalog={demo.modernization} onBackToFlows={() => setView("flows")} />
        ) : (
          <div className="project-demo-graph">
            <div className="project-demo-switch"><span>ПРИМЕР СТРУКТУРЫ</span><button type="button" onClick={() => setView("flows")}><ArrowLeft size={13} /> К блок-схемам</button></div>
            <ProjectGraphViewer
              projectId="demo"
              revision={0}
              methods={[]}
              onOpenMethod={() => undefined}
              onSourceRangeSelect={() => undefined}
              graphOverride={demo.graph}
              sourceLabel="ДЕМО · ИСХОДНИК НЕДОСТУПЕН"
            />
          </div>
        )}
      </div>
    </main>
  );
}
