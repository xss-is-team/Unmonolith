import type { Metadata } from "next";
import { ProjectWorkspace } from "@/components/project-workspace";

export const metadata: Metadata = {
  title: "Проект",
};

export default async function ProjectPage({
  params,
  searchParams,
}: {
  params: Promise<{ projectId: string }>;
  searchParams: Promise<{ legacy?: string }>;
}) {
  const { projectId } = await params;
  const { legacy } = await searchParams;
  return <ProjectWorkspace projectId={projectId} legacySingleFile={legacy === "1"} />;
}
