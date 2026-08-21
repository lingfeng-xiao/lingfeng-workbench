"use client";

import type { FormEvent } from "react";
import { useRef, useState } from "react";
import type { TaskDetail } from "../_lib/task-contracts";

export function TaskForm({ task }: { task?: TaskDetail }) {
  const idempotencyKey = useRef<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const editing = Boolean(task);

  async function submitTask(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    setSubmitting(true);
    setProblem(null);
    const form = new FormData(event.currentTarget);
    try {
      const contextRefs = parseContextRefs(String(form.get("contextRefs") ?? ""));
      const payload = {
        ...(task ? { expectedVersion: task.version } : {}),
        title: requiredText(form, "title"),
        objective: requiredText(form, "objective"),
        acceptanceSummary: requiredText(form, "acceptanceSummary"),
        sideEffectSummary: requiredText(form, "sideEffectSummary"),
        priority: Number(form.get("priority")),
        targetNodeId: requiredText(form, "targetNodeId"),
        workspaceRef: requiredText(form, "workspaceRef"),
        contextRefs,
        runtimeKind: requiredText(form, "runtimeKind"),
        executionProfile: requiredText(form, "executionProfile"),
        ...(!task ? { dataBoundaryAcknowledged: form.get("dataBoundaryAcknowledged") === "yes" } : {}),
        reason: requiredText(form, "reason"),
      };
      idempotencyKey.current ??= `web_${crypto.randomUUID()}`;
      const response = await fetch(task ? `/api/tasks/${encodeURIComponent(task.taskId)}` : "/api/tasks", {
        method: task ? "PUT" : "POST",
        headers: mutationHeaders(idempotencyKey.current),
        body: JSON.stringify(payload),
      });
      const responsePayload = await response.json() as { taskId?: string; message?: string };
      if (!response.ok) throw new Error(responsePayload.message ?? "Task 保存失败");
      idempotencyKey.current = null;
      const taskId = task?.taskId ?? responsePayload.taskId;
      if (!taskId) throw new Error("Service 未返回 Task 标识");
      window.location.assign(`/tasks/${encodeURIComponent(taskId)}`);
    } catch (error) {
      setProblem(error instanceof Error ? error.message : "Task 保存失败");
      setSubmitting(false);
    }
  }

  return (
    <form className="task-form panel" onSubmit={submitTask}>
      <div className="form-grid">
        <label className="field field--wide">
          <span>标题</span>
          <input name="title" required maxLength={200} defaultValue={task?.title} />
        </label>
        <label className="field field--wide">
          <span>目标</span>
          <textarea name="objective" required maxLength={800} rows={4} defaultValue={task?.objective} />
        </label>
        <label className="field">
          <span>验收摘要</span>
          <textarea name="acceptanceSummary" required maxLength={800} rows={4} defaultValue={task?.acceptanceSummary} />
        </label>
        <label className="field">
          <span>允许的副作用</span>
          <textarea name="sideEffectSummary" required maxLength={800} rows={4} defaultValue={task?.sideEffectSummary} />
        </label>
        <label className="field">
          <span>目标 Node</span>
          <input name="targetNodeId" required pattern="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}" defaultValue={task?.targetNodeId ?? "office-pc"} />
        </label>
        <label className="field">
          <span>优先级（-100 至 100）</span>
          <input name="priority" required type="number" min={-100} max={100} defaultValue={task?.priority ?? 0} />
        </label>
        <label className="field">
          <span>Workspace alias</span>
          <input name="workspaceRef" required pattern="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}" defaultValue={task?.workspaceRef ?? "lingfeng-workbench"} />
        </label>
        <label className="field">
          <span>Runtime kind</span>
          <input name="runtimeKind" required pattern="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}" defaultValue={task?.runtimeKind ?? "opencode"} />
        </label>
        <label className="field field--wide">
          <span>Context aliases（每行 ref | label）</span>
          <textarea
            name="contextRefs"
            required
            rows={4}
            defaultValue={task ? task.contextRefs.map((item) => `${item.ref} | ${item.label}`).join("\n") : "product-freeze | v0.5 冻结设计"}
          />
          <small>只填写 Node ContextRegistry 中的安全别名，不填写本机绝对路径。</small>
        </label>
        <label className="field">
          <span>Execution profile</span>
          <input name="executionProfile" required pattern="[A-Za-z0-9][A-Za-z0-9._:-]{0,127}" defaultValue={task?.executionProfile ?? "default"} />
        </label>
        <label className="field">
          <span>变更原因</span>
          <input name="reason" required maxLength={800} defaultValue={editing ? "更新 Task 合同" : "创建 Task 草稿"} />
        </label>
      </div>
      {!editing ? (
        <label className="boundary-check">
          <input name="dataBoundaryAcknowledged" type="checkbox" value="yes" required />
          <span>我确认这里只保存安全别名和短摘要；路径、Session、日志、diff 与产物留在 Node。</span>
        </label>
      ) : null}
      {problem ? <p className="form-problem" role="alert">{problem}</p> : null}
      <div className="form-actions">
        <button className="button button--primary" type="submit" disabled={submitting}>
          {submitting ? "正在保存…" : editing ? "保存修改" : "创建 Task"}
        </button>
      </div>
    </form>
  );
}

function requiredText(form: FormData, name: string): string {
  const value = String(form.get(name) ?? "").trim();
  if (!value) throw new Error(`${name} 不能为空`);
  return value;
}

function parseContextRefs(source: string): Array<{ ref: string; label: string }> {
  const references = source.split(/\r?\n/).filter((line) => line.trim()).map((line) => {
    const separator = line.indexOf("|");
    if (separator < 1) throw new Error("Context alias 每行必须使用 ref | label");
    const ref = line.slice(0, separator).trim();
    const label = line.slice(separator + 1).trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(ref) || !label) {
      throw new Error("Context alias 或 label 格式无效");
    }
    return { ref, label };
  });
  if (references.length < 1 || references.length > 16) throw new Error("需要 1–16 个 Context aliases");
  if (new Set(references.map(({ ref }) => ref)).size !== references.length) throw new Error("Context aliases 不可重复");
  return references;
}

function mutationHeaders(idempotencyKey: string): HeadersInit {
  return {
    "content-type": "application/json",
    "idempotency-key": idempotencyKey,
    "x-workbench-csrf": "1",
  };
}
