import type { Metadata } from "next";
import { ProjectWorkspace } from "@/components/project-workspace";

export const metadata: Metadata = {
  title: "Проект",
};

export default async function ProjectPage({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;
  return <ProjectWorkspace projectId={projectId} />;
}
