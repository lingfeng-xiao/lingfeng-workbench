import { WorkbenchServiceError } from "../_lib/workbench-service";

const PROBLEM_COPY = {
  configuration: {
    eyebrow: "CONFIGURATION",
    title: "站点尚未连接 Service",
    description: "只读服务地址或凭证尚未配置。完成 Sites 运行时配置后再刷新。",
  },
  access_denied: {
    eyebrow: "ACCESS DENIED",
    title: "Service 拒绝了只读请求",
    description: "站点凭证无效或没有读取权限。业务数据没有返回到浏览器。",
  },
  not_found: {
    eyebrow: "NOT FOUND",
    title: "没有找到这项工作",
    description: "它可能已被移出当前控制视图，或链接中的标识无效。",
  },
  unavailable: {
    eyebrow: "SERVICE UNAVAILABLE",
    title: "暂时无法读取控制状态",
    description: "Service 没有及时返回可用响应。这里不会展示缓存的旧状态。",
  },
  invalid_response: {
    eyebrow: "CONTRACT MISMATCH",
    title: "Service 响应不符合合同",
    description: "为避免误读状态，页面已停止展示这次响应。",
  },
} as const;

export function ServiceProblem({ error }: { error: unknown }) {
  const kind = error instanceof WorkbenchServiceError ? error.kind : "unavailable";
  const copy = PROBLEM_COPY[kind];

  return (
    <section className="problem-panel" role="alert">
      <p>{copy.eyebrow}</p>
      <h2>{copy.title}</h2>
      <span>{copy.description}</span>
    </section>
  );
}
