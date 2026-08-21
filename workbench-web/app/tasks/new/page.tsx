import type { Metadata } from "next";
import { AppShell } from "../../_components/AppShell";
import { TaskForm } from "../../_components/TaskForm";
import { requireChatGPTUser } from "../../chatgpt-auth";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "新建 Task" };

export default async function NewTaskPage() {
  await requireChatGPTUser("/tasks/new");
  return <AppShell currentPath="/" eyebrow="NEW TASK" title="建立可执行的业务合同" description="Task 先保存为 DRAFT；编辑和标记 READY 都不会启动 WS。"><TaskForm /></AppShell>;
}
