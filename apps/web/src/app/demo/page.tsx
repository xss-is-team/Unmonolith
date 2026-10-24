import type { Metadata } from "next";
import { ProjectDemoWorkspace } from "@/components/project-demo-workspace";

export const metadata: Metadata = {
  title: "Интерактивное демо",
  description: "Демонстрация вложенных блок-схем и плана модернизации Java-проекта.",
};

export default function DemoPage() {
  return <ProjectDemoWorkspace />;
}
