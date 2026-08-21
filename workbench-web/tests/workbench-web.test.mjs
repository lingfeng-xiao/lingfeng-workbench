import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { createServer } from "node:http";
import test from "node:test";

const AUTHENTICATED_HEADERS = {
  accept: "text/html",
  "oai-authenticated-user-id": "user_test",
  "oai-authenticated-user-email": "owner@example.com",
};
const READ_TOKEN = "test-sites-read-token";
const WRITE_TOKEN = "test-sites-write-token";
const workerUrl = new URL("../dist/server/index.js", import.meta.url);
workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
const { default: worker } = await import(workerUrl.href);

test("requires both Sites identity headers before calling Service", async (context) => {
  for (const headers of [
    { accept: "text/html" },
    { accept: "text/html", "oai-authenticated-user-id": "user_test" },
    { accept: "text/html", "oai-authenticated-user-email": "owner@example.com" },
  ]) {
    await context.test(`rejects identity variant ${JSON.stringify(headers)}`, async () => {
      let serviceCalls = 0;
      const fakeService = await startFakeService(() => {
        serviceCalls += 1;
        return jsonResponse([]);
      });
      try {
        const response = await renderWorker("/", { headers, baseUrl: fakeService.baseUrl });
        assert.ok([302, 303, 307, 308].includes(response.status));
        assert.equal(response.headers.get("location"), "/signin-with-chatgpt?return_to=%2F");
        assert.equal(response.headers.get("cache-control"), "no-store");
        assert.equal(serviceCalls, 0);
      } finally {
        await fakeService.close();
      }
    });
  }
});

test("uses the Task v1 read endpoint and a server-side read credential", async () => {
  const requests = [];
  const response = await renderWithFakeService("/", ({ method, path, authorization }) => {
    requests.push({ method, path, authorization });
    if (path === "/api/tasks/v1/tasks?limit=100") return jsonResponse(tasks());
    return jsonResponse(apiError(), 404);
  });

  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /以 Task 为中心的工作池/);
  assert.doesNotMatch(html, new RegExp(READ_TOKEN));
  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })),
    [{ method: "GET", path: "/api/tasks/v1/tasks?limit=100" }],
  );
  assert.ok(requests.every(({ authorization }) => authorization === `Bearer ${READ_TOKEN}`));
});

test("renders Task business, attention, Node, stale, and last-observed projections", async () => {
  const response = await renderWithFakeService("/", ({ path }) => {
    if (path === "/api/tasks/v1/tasks?limit=100") return jsonResponse(tasks());
    return jsonResponse(apiError(), 404);
  });
  const html = await response.text();

  assert.match(html, /执行中/);
  assert.match(html, /待验收/);
  assert.match(html, /执行失败/);
  assert.match(html, /Node 离线/);
  assert.match(html, /lastObservedAt/);
  assert.match(html, /STALE/);
});

test("renders a completed v2 detail with timeline, interaction, and dead-letter notification", async () => {
  const response = await renderWithFakeService("/work-items/wi_completed", ({ path }) => {
    assert.equal(path, "/api/client/v2/work-items/wi_completed");
    return jsonResponse(workItemDetail());
  });
  const html = await response.text();

  assert.equal(response.status, 200);
  assert.match(html, /已完成/);
  assert.match(html, /验收状态为 PASSED/);
  assert.match(html, /BUILD_VALIDATION/);
  assert.match(html, /第三个 Turn 产生可信终态/);
  assert.match(html, /等待中/);
  assert.match(html, /已消费/);
  assert.match(html, /RUN_COMPLETED/);
  assert.match(html, /投递失败/);
  assert.match(html, /投递状态只表示通知结果，不改变 Run 结果|重要通知投影/);
});

test("distinguishes waiting Interaction and uncertain Run states from failure", async (context) => {
  await context.test("waiting Interaction", async () => {
    const detail = workItemDetail();
    detail.status = "in_progress";
    detail.mission.status = "waiting_interaction";
    detail.run.status = "waiting_interaction";
    detail.run.resumable = true;
    const response = await renderWithFakeService("/work-items/wi_completed", () =>
      jsonResponse(detail),
    );
    const html = await response.text();
    assert.match(html, /等待输入/);
    assert.match(html, /同一 Session 可恢复/);
    assert.doesNotMatch(html, />失败</);
  });

  await context.test("uncertain Run", async () => {
    const detail = workItemDetail();
    detail.status = "attention_required";
    detail.mission.status = "uncertain";
    detail.run.status = "uncertain";
    detail.run.resultSummary = "缺少可信结构化终态";
    const response = await renderWithFakeService("/work-items/wi_completed", () =>
      jsonResponse(detail),
    );
    const html = await response.text();
    assert.match(html, /结果不可信，需要关注/);
    assert.doesNotMatch(html, />已完成</);
  });
});

test("renders all interaction lifecycle states without write controls", async () => {
  const states = ["pending", "resolved", "delivered", "consumed", "expired", "cancelled"];
  const response = await renderWithFakeService("/interactions", ({ method, path }) => {
    assert.equal(method, "GET");
    assert.equal(path, "/api/client/v2/interactions?limit=100");
    return jsonResponse(states.map((state, index) => interaction(state, index)));
  });
  const html = await response.text();

  for (const label of ["等待中", "已解决", "已送达", "已消费", "已过期", "已取消"]) {
    assert.match(html, new RegExp(label));
  }
  assert.match(html, /APPROVE \/ REJECT/);
  assert.doesNotMatch(html, /<button|批准此请求|提交回复/);
});

test("renders online and offline Nodes with current Run and synchronization times", async () => {
  const response = await renderWithFakeService("/nodes", ({ path }) => {
    assert.equal(path, "/api/client/v2/nodes");
    return jsonResponse(nodes());
  });
  const html = await response.text();

  assert.match(html, /Office PC/);
  assert.match(html, /在线/);
  assert.match(html, /Home PC/);
  assert.match(html, /离线/);
  assert.match(html, /run_active/);
  assert.match(html, /无活动 Run/);
  assert.match(html, /最后同步/);
});

test("renders empty states on every read-only surface", async (context) => {
  for (const [path, expected] of [
    ["/", "现在没有 Task"],
    ["/interactions", "没有 Interaction"],
    ["/nodes", "还没有注册 Node"],
  ]) {
    await context.test(path, async () => {
      const response = await renderWithFakeService(path, () => jsonResponse([]));
      assert.match(await response.text(), new RegExp(expected));
    });
  }
});

test("maps 401, 403, timeout, and 502 to bounded errors", async (context) => {
  for (const status of [401, 403]) {
    await context.test(`upstream ${status}`, async () => {
      const response = await renderWithFakeService("/nodes", () => jsonResponse(apiError(), status));
      assert.match(await response.text(), /Service 拒绝了只读请求/);
    });
  }

  await context.test("read-only scope rejected with 403", async () => {
    const response = await renderWithFakeService("/nodes", ({ authorization }) => {
      assert.equal(authorization, `Bearer ${READ_TOKEN}`);
      return jsonResponse(apiError("forbidden"), 403);
    });
    assert.match(await response.text(), /没有读取权限/);
  });

  await context.test("502", async () => {
    const response = await renderWithFakeService("/nodes", () => jsonResponse(apiError(), 502));
    assert.match(await response.text(), /暂时无法读取控制状态/);
  });

  await context.test("timeout", async () => {
    const response = await renderWithFakeService(
      "/nodes",
      () => new Promise((resolve) => setTimeout(() => resolve(jsonResponse(nodes())), 250)),
      { timeoutMs: 100 },
    );
    assert.match(await response.text(), /暂时无法读取控制状态/);
  });
});

test("fails closed for non-JSON, oversized, unknown-field, and unknown-state responses", async (context) => {
  const cases = [
    ["non-JSON", () => new Response("not json", { headers: { "content-type": "text/plain" } })],
    ["over 64 KiB", () => jsonResponse({ padding: "x".repeat(65 * 1024) })],
    ["unknown field", () => jsonResponse([{ ...tasks()[0], runtimeSessionId: "session-secret" }])],
    ["unknown state", () => jsonResponse([{ ...tasks()[0], businessStatus: "invented" }])],
  ];

  for (const [name, respond] of cases) {
    await context.test(name, async () => {
      const response = await renderWithFakeService("/", ({ path }) => {
        if (path.includes("/api/tasks/v1/tasks")) return respond();
        return jsonResponse(apiError(), 404);
      });
      const html = await response.text();
      assert.match(html, /Service 响应不符合合同/);
      assert.doesNotMatch(html, /session-secret|xxxxxxxxxxxxxxxxxxxxxxxx/);
    });
  }
});

test("never renders sensitive fields injected at detail or Node boundaries", async (context) => {
  await context.test("detail", async () => {
    const response = await renderWithFakeService("/work-items/wi_completed", () =>
      jsonResponse({
        ...workItemDetail(),
        missionDigest: "a".repeat(64),
        workspaceRef: "workspace-secret",
        localAbsolutePath: "C:\\secret\\workspace",
        runtimeSessionId: "runtime-secret",
        rawRuntimeEvents: "raw-secret",
        diff: "diff-secret",
      }),
    );
    const html = await response.text();
    assert.match(html, /Service 响应不符合合同/);
    assert.doesNotMatch(html, /workspace-secret|secret\\workspace|runtime-secret|raw-secret|diff-secret/);
  });

  await context.test("node", async () => {
    const response = await renderWithFakeService("/nodes", () =>
      jsonResponse([{ ...nodes()[0], localAbsolutePath: "C:\\secret", runtimeSessionId: "session-secret" }]),
    );
    const html = await response.text();
    assert.match(html, /Service 响应不符合合同/);
    assert.doesNotMatch(html, /C:\\secret|session-secret/);
  });
});

test("renders Task, Run, and Acceptance as independent axes with two-run history and timeline", async () => {
  const response = await renderWithFakeService("/tasks/task_review", ({ path, authorization }) => {
    assert.equal(path, "/api/tasks/v1/tasks/task_review");
    assert.equal(authorization, `Bearer ${READ_TOKEN}`);
    return jsonResponse(taskDetail());
  });
  const html = await response.text();
  for (const expected of [
    "业务状态",
    "执行状态",
    "验收状态",
    "REVIEW / PENDING",
    "第一次执行失败",
    "第二次执行完成",
    "lastObservedAt",
    "Run 完成，等待人工验收",
    "Node",
  ]) assert.match(html, new RegExp(expected));
  assert.doesNotMatch(html, /C:\\|runtimeSessionId|rawRuntimeEvents|diff-secret|log-secret/);
});

test("Task detail polling forwards ETag and preserves Service 304", async () => {
  const response = await renderWithFakeService("/api/tasks/task_review", ({ path, ifNoneMatch, authorization }) => {
    assert.equal(path, "/api/tasks/v1/tasks/task_review");
    assert.equal(ifNoneMatch, '"5"');
    assert.equal(authorization, `Bearer ${READ_TOKEN}`);
    return new Response(null, { status: 304, headers: { etag: '"5"' } });
  }, {
    requestInit: { headers: { "if-none-match": '"5"', accept: "application/json" } },
  });
  assert.equal(response.status, 304);
  assert.equal(response.headers.get("etag"), '"5"');
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("Task mutations fail closed without identity, same-origin proof, and CSRF header", async (context) => {
  const validBody = JSON.stringify({ expectedVersion: 5, reason: "explicit action" });
  const variants = [
    ["missing identity", { origin: "http://localhost", "x-workbench-csrf": "1", "content-type": "application/json", "idempotency-key": "web_test" }, 401, false],
    ["missing origin", { ...AUTHENTICATED_HEADERS, "x-workbench-csrf": "1", "content-type": "application/json", "idempotency-key": "web_test" }, 403, true],
    ["cross origin", { ...AUTHENTICATED_HEADERS, origin: "https://evil.example", "x-workbench-csrf": "1", "content-type": "application/json", "idempotency-key": "web_test" }, 403, true],
    ["missing custom header", { ...AUTHENTICATED_HEADERS, origin: "http://localhost", "content-type": "application/json", "idempotency-key": "web_test" }, 403, true],
    ["cross-site metadata", { ...AUTHENTICATED_HEADERS, origin: "http://localhost", "sec-fetch-site": "cross-site", "x-workbench-csrf": "1", "content-type": "application/json", "idempotency-key": "web_test" }, 403, true],
  ];
  for (const [name, headers, status, identityIncluded] of variants) {
    await context.test(name, async () => {
      let serviceCalls = 0;
      const response = await renderWithFakeService("/api/tasks/task_review/start", () => {
        serviceCalls += 1;
        return jsonResponse({});
      }, { includeAuthentication: identityIncluded, requestInit: { method: "POST", headers, body: validBody } });
      assert.equal(response.status, status);
      assert.equal(serviceCalls, 0);
    });
  }
});

test("create injects actor and boundary confirmation while using only the write credential", async () => {
  let serviceRequest;
  const response = await renderWithFakeService("/api/tasks", (request) => {
    serviceRequest = request;
    return jsonResponse({ taskId: "task_created", version: 1, businessStatus: "DRAFT", createdAt: "2026-08-21T08:00:00Z" }, 201);
  }, { requestInit: mutationRequest({
    title: "Task create does not execute",
    objective: "Create a durable Task draft",
    acceptanceSummary: "No WorkItem before explicit start",
    sideEffectSummary: "No external effects",
    priority: 0,
    targetNodeId: "office-pc",
    workspaceRef: "lingfeng-workbench",
    contextRefs: [{ ref: "product-freeze", label: "Frozen design" }],
    runtimeKind: "opencode",
    executionProfile: "default",
    dataBoundaryAcknowledged: true,
    reason: "create draft",
  }, "web_create") });
  assert.equal(response.status, 201);
  assert.equal(serviceRequest.path, "/api/tasks/v1/tasks");
  assert.equal(serviceRequest.authorization, `Bearer ${WRITE_TOKEN}`);
  assert.equal(serviceRequest.idempotencyKey, "web_create");
  const body = JSON.parse(serviceRequest.body);
  assert.equal(body.dataBoundaryAcknowledged, true);
  assert.match(body.actor, /^sites_user:[a-f0-9]{24}$/);
  assert.doesNotMatch(serviceRequest.body, /user_test|owner@example.com/);
});

test("edit and explicit actions forward expectedVersion, reason, idempotency, and fixed paths", async (context) => {
  await context.test("edit", async () => {
    let captured;
    const detail = taskDetail({ businessStatus: "DRAFT", acceptanceStatus: "NOT_REQUESTED", allowedActions: ["EDIT", "MARK_READY", "CANCEL"] });
    const response = await renderWithFakeService("/api/tasks/task_review", (request) => {
      captured = request;
      return jsonResponse(detail);
    }, { requestInit: { ...mutationRequest({
      expectedVersion: 5,
      title: detail.title,
      objective: detail.objective,
      acceptanceSummary: detail.acceptanceSummary,
      sideEffectSummary: detail.sideEffectSummary,
      priority: detail.priority,
      targetNodeId: detail.targetNodeId,
      workspaceRef: detail.workspaceRef,
      contextRefs: detail.contextRefs,
      runtimeKind: detail.runtimeKind,
      executionProfile: detail.executionProfile,
      reason: "edit contract",
    }, "web_edit"), method: "PUT" } });
    assert.equal(response.status, 200);
    assert.equal(captured.path, "/api/tasks/v1/tasks/task_review");
    assert.equal(captured.authorization, `Bearer ${WRITE_TOKEN}`);
    assert.equal(JSON.parse(captured.body).expectedVersion, 5);
  });

  for (const action of ["mark-ready", "start", "accept", "request-changes", "archive", "restore"]) {
    await context.test(action, async () => {
      let captured;
      const upstream = action === "start"
        ? { taskId: "task_review", version: 6, workItemId: "wi_new", missionId: "mi_new", runId: "run_new", businessStatus: "IN_PROGRESS", startedAt: "2026-08-21T08:01:00Z" }
        : taskDetail();
      const actionBody = action === "accept"
        ? { expectedVersion: 5, reason: "accept delivery", deliverySummary: "Verified locally", commitSha: "abcdef1", prUrl: "https://example.com/pr/1" }
        : { expectedVersion: 5, reason: `explicit ${action}` };
      const response = await renderWithFakeService(`/api/tasks/task_review/${action}`, (request) => {
        captured = request;
        return jsonResponse(upstream);
      }, { requestInit: mutationRequest(actionBody, `web_${action}`) });
      assert.equal(response.status, 200);
      assert.equal(captured.path, `/api/tasks/v1/tasks/task_review/${action}`);
      assert.equal(captured.authorization, `Bearer ${WRITE_TOKEN}`);
      assert.equal(captured.idempotencyKey, `web_${action}`);
      assert.equal(JSON.parse(captured.body).expectedVersion, 5);
    });
  }
});

test("BFF maps conflict, invalid JSON, oversized responses, and unknown fields to bounded errors", async (context) => {
  await context.test("conflict", async () => {
    const response = await renderWithFakeService("/api/tasks/task_review/start", () => jsonResponse({ message: "database details must not escape" }, 409), { requestInit: mutationRequest({ expectedVersion: 5, reason: "start" }, "web_conflict") });
    assert.equal(response.status, 409);
    assert.match(await response.text(), /数据已更新，请重新加载/);
  });
  for (const [name, upstream] of [
    ["invalid JSON", new Response("not-json", { headers: { "content-type": "application/json" } })],
    ["oversized", jsonResponse({ padding: "x".repeat(65 * 1024) })],
    ["unknown field", jsonResponse({ ...taskDetail(), localAbsolutePath: "C:\\secret" })],
  ]) {
    await context.test(name, async () => {
      const response = await renderWithFakeService("/api/tasks/task_review", () => upstream, { requestInit: { headers: { accept: "application/json" } } });
      assert.equal(response.status, 502);
      const body = await response.text();
      assert.match(body, /Task Service 暂时不可用/);
      assert.doesNotMatch(body, /C:\\secret|xxxxxx/);
    });
  }
});

test("keeps business routes no-store and preserves Sites metadata and Worker ESM", async () => {
  const response = await renderWithFakeService("/nodes", () => jsonResponse(nodes()));
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("pragma"), "no-cache");

  const [hostingSource, builtHostingSource, workerSource] = await Promise.all([
    readFile(new URL("../.openai/hosting.json", import.meta.url), "utf8"),
    readFile(new URL("../dist/.openai/hosting.json", import.meta.url), "utf8"),
    readFile(new URL("../dist/server/index.js", import.meta.url), "utf8"),
  ]);
  const expectedHosting = {
    project_id: "appgprj_6a841dad1a8881919399cc5bced2c838",
    d1: null,
    r2: null,
  };
  assert.deepEqual(JSON.parse(hostingSource), expectedHosting);
  assert.deepEqual(JSON.parse(builtHostingSource), expectedHosting);
  assert.match(workerSource, /export\s*\{[^}]*default/);
});

test("keeps Service configuration and business state out of browser assets and storage", async () => {
  const browserAssetDirectory = new URL("../dist/client/", import.meta.url);
  const assetNames = await readdir(browserAssetDirectory, { recursive: true });
  const browserSources = await Promise.all(
    assetNames.filter((name) => name.endsWith(".js")).map((name) =>
      readFile(new URL(name, browserAssetDirectory), "utf8"),
    ),
  );
  const combinedBrowserSource = browserSources.join("\n");
  assert.doesNotMatch(
    combinedBrowserSource,
    /WORKBENCH_SERVICE_BASE_URL|WORKBENCH_SERVICE_READ_TOKEN|WORKBENCH_SERVICE_WRITE_TOKEN|test-sites-read-token|test-sites-write-token/,
  );

  const applicationSources = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/interactions/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/nodes/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/work-items/[id]/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/_lib/workbench-service.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/_lib/task-service.ts", import.meta.url), "utf8"),
    readFile(new URL("../app/_components/TaskForm.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/_components/TaskDetailClient.tsx", import.meta.url), "utf8"),
  ]);
  assert.doesNotMatch(applicationSources.join("\n"), /localStorage|sessionStorage/);
  assert.match(applicationSources.join("\n"), /4_000/);
});

async function renderWorker(path, { headers = AUTHENTICATED_HEADERS, baseUrl = "https://unused.example" } = {}) {
  return withServiceEnvironment({ baseUrl, timeoutMs: 500 }, () =>
    worker.fetch(
      new Request(`http://localhost${path}`, { headers }),
      workerEnvironment(),
      workerContext(),
    ),
  );
}

async function renderWithFakeService(path, respond, { timeoutMs = 500, requestInit = {}, includeAuthentication = true } = {}) {
  const fakeService = await startFakeService(respond);
  try {
    return await withServiceEnvironment({ baseUrl: fakeService.baseUrl, timeoutMs }, () =>
      worker.fetch(
        new Request(`http://localhost${path}`, {
          ...requestInit,
          headers: { ...(includeAuthentication ? AUTHENTICATED_HEADERS : {}), ...requestInit.headers },
        }),
        workerEnvironment(),
        workerContext(),
      ),
    );
  } finally {
    await fakeService.close();
  }
}

async function withServiceEnvironment({ baseUrl, timeoutMs }, action) {
  const previous = {
    baseUrl: process.env.WORKBENCH_SERVICE_BASE_URL,
    readToken: process.env.WORKBENCH_SERVICE_READ_TOKEN,
    writeToken: process.env.WORKBENCH_SERVICE_WRITE_TOKEN,
    timeout: process.env.WORKBENCH_SERVICE_TIMEOUT_MS,
  };
  process.env.WORKBENCH_SERVICE_BASE_URL = baseUrl;
  process.env.WORKBENCH_SERVICE_READ_TOKEN = READ_TOKEN;
  process.env.WORKBENCH_SERVICE_WRITE_TOKEN = WRITE_TOKEN;
  process.env.WORKBENCH_SERVICE_TIMEOUT_MS = String(timeoutMs);
  try {
    return await action();
  } finally {
    restoreEnvironment("WORKBENCH_SERVICE_BASE_URL", previous.baseUrl);
    restoreEnvironment("WORKBENCH_SERVICE_READ_TOKEN", previous.readToken);
    restoreEnvironment("WORKBENCH_SERVICE_WRITE_TOKEN", previous.writeToken);
    restoreEnvironment("WORKBENCH_SERVICE_TIMEOUT_MS", previous.timeout);
  }
}

async function startFakeService(respond) {
  const server = createServer(async (request, response) => {
    try {
      const serviceResponse = await respond({
        method: request.method,
        path: request.url,
        authorization: request.headers.authorization,
        idempotencyKey: request.headers["idempotency-key"],
        ifNoneMatch: request.headers["if-none-match"],
        body: await readRequestBody(request),
      });
      response.writeHead(serviceResponse.status, Object.fromEntries(serviceResponse.headers));
      response.end(Buffer.from(await serviceResponse.arrayBuffer()));
    } catch (error) {
      response.destroy(error);
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");

  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: async () => {
      server.closeAllConnections();
      await new Promise((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()));
      });
    },
  };
}

async function readRequestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf8");
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function mutationRequest(payload, idempotencyKey) {
  return {
    method: "POST",
    headers: {
      accept: "application/json",
      "content-type": "application/json",
      origin: "http://localhost",
      "sec-fetch-site": "same-origin",
      "x-workbench-csrf": "1",
      "idempotency-key": idempotencyKey,
    },
    body: JSON.stringify(payload),
  };
}

function tasks() {
  return [
    taskSummary("task_ready", "等待显式开始", "READY", "NOT_REQUESTED", "NONE"),
    taskSummary("task_running", "正在执行闭环", "IN_PROGRESS", "NOT_REQUESTED", "NODE_OFFLINE", {
      runStatus: "running",
      progressSummary: "已完成合同解析",
      lastObservedAt: "2026-08-21T07:10:00Z",
      stale: true,
      nodeStatus: "offline",
    }),
    taskSummary("task_review", "等待人工验收", "REVIEW", "PENDING", "RUN_FAILED", {
      runStatus: "completed",
      progressSummary: "执行已结束，等待验收",
      lastObservedAt: "2026-08-21T07:12:00Z",
    }),
  ];
}

function taskSummary(taskId, title, businessStatus, acceptanceStatus, attentionState, optional = {}) {
  return {
    taskId,
    title,
    priority: 0,
    targetNodeId: "office-pc",
    businessStatus,
    acceptanceStatus,
    attentionState,
    version: 3,
    runStatus: null,
    progressSummary: null,
    lastObservedAt: null,
    stale: false,
    nodeStatus: "online",
    ...optional,
    updatedAt: "2026-08-21T07:15:00Z",
  };
}

function taskDetail(overrides = {}) {
  return {
    taskId: "task_review",
    title: "等待人工验收",
    objective: "闭合 Task 到验收的最小链路",
    acceptanceSummary: "两次进度后进入 REVIEW/PENDING",
    sideEffectSummary: "只修改本地工作区，不提交或推送",
    priority: 1,
    targetNodeId: "office-pc",
    workspaceRef: "lingfeng-workbench",
    contextRefs: [{ ref: "product-freeze", label: "v0.5 冻结设计" }],
    runtimeKind: "opencode",
    executionProfile: "default",
    businessStatus: "REVIEW",
    acceptanceStatus: "PENDING",
    attentionState: "NONE",
    deliverySummary: null,
    commitSha: null,
    prUrl: null,
    version: 5,
    allowedActions: ["ACCEPT", "REQUEST_CHANGES", "ARCHIVE"],
    nodeStatus: "online",
    nodeLastHeartbeatAt: "2026-08-21T07:14:00Z",
    runs: [
      {
        workItemId: "wi_first",
        missionId: "mi_first",
        runId: "run_first",
        missionRevision: 1,
        status: "failed",
        phaseCode: "IMPLEMENTATION",
        progressSummary: "第一次执行失败",
        resultSummary: "需要修改",
        lastObservedAt: "2026-08-21T07:11:00Z",
        stale: false,
        createdAt: "2026-08-21T07:00:00Z",
      },
      {
        workItemId: "wi_second",
        missionId: "mi_second",
        runId: "run_second",
        missionRevision: 2,
        status: "completed",
        phaseCode: "REPORTING",
        progressSummary: "第二次执行完成",
        resultSummary: "等待验收",
        lastObservedAt: "2026-08-21T07:13:00Z",
        stale: false,
        createdAt: "2026-08-21T07:12:00Z",
      },
    ],
    timeline: [
      { eventId: "event_1", sequence: 1, eventType: "TASK_CREATED", summary: "Task 已创建", actor: "sites_user:abc", source: "USER", workItemId: null, missionId: null, runId: null, occurredAt: "2026-08-21T07:00:00Z" },
      { eventId: "event_2", sequence: 2, eventType: "RUN_COMPLETED", summary: "Run 完成，等待人工验收", actor: "NODE", source: "NODE", workItemId: "wi_second", missionId: "mi_second", runId: "run_second", occurredAt: "2026-08-21T07:13:00Z" },
    ],
    createdAt: "2026-08-21T07:00:00Z",
    updatedAt: "2026-08-21T07:15:00Z",
    archivedAt: null,
    ...overrides,
  };
}

function workItemDetail() {
  return {
    workItemId: "wi_completed",
    title: "可信终态",
    status: "completed",
    priority: 1,
    mission: {
      missionId: "mi_completed",
      revision: 2,
      objective: "用三个 Turn 完成 fake Runtime 联调",
      acceptanceSummary: "验收状态为 PASSED",
      status: "completed",
    },
    run: {
      runId: "run_completed",
      nodeId: "office-pc",
      status: "completed",
      phaseCode: "BUILD_VALIDATION",
      progressSummary: "第三个 Turn 完成",
      resultSummary: "SUCCEEDED / PASSED",
      resumable: false,
      lastSyncedAt: "2026-08-20T08:14:00Z",
    },
    interactions: [
      interaction("pending", 0),
      interaction("consumed", 1),
    ],
    notifications: [
      {
        notificationId: "ntf_complete",
        notificationType: "RUN_COMPLETED",
        status: "delivered",
        createdAt: "2026-08-20T08:14:00Z",
      },
      {
        notificationId: "ntf_warning",
        notificationType: "RUN_UNCERTAIN",
        status: "dead_letter",
        createdAt: "2026-08-20T08:13:00Z",
      },
    ],
    timeline: [
      {
        eventId: "evt_terminal",
        eventType: "RUN_TERMINAL",
        summary: "第三个 Turn 产生可信终态",
        createdAt: "2026-08-20T08:14:00Z",
      },
    ],
    updatedAt: "2026-08-20T08:15:00Z",
  };
}

function interaction(state, index) {
  const resolved = ["resolved", "delivered", "consumed"].includes(state);
  return {
    interactionId: `int_${index + 1}`,
    runId: "run_completed",
    checkpointId: `checkpoint_${index + 1}`,
    state,
    promptSummary: `短提示 ${index + 1}`,
    allowedDecisions: ["APPROVE", "REJECT"],
    ...(resolved ? { responseSummary: "已确认", resolvedAt: "2026-08-20T08:12:00Z" } : {}),
    ...(state === "consumed" ? { consumedAt: "2026-08-20T08:13:00Z" } : {}),
    createdAt: "2026-08-20T08:10:00Z",
  };
}

function nodes() {
  return [
    {
      nodeId: "office-pc",
      displayName: "Office PC",
      status: "online",
      capabilities: ["runtime.ws", "resume"],
      currentRunId: "run_active",
      lastHeartbeatAt: "2026-08-20T08:15:00Z",
      lastSyncedAt: "2026-08-20T08:15:01Z",
    },
    {
      nodeId: "home-pc",
      displayName: "Home PC",
      status: "offline",
      capabilities: [],
      lastHeartbeatAt: "2026-08-19T12:00:00Z",
      lastSyncedAt: "2026-08-19T12:00:02Z",
    },
  ];
}

function apiError(code = "denied") {
  return { code, message: "bounded error", requestId: "request_test" };
}

function workerEnvironment() {
  return {
    ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) },
  };
}

function workerContext() {
  return { waitUntil() {}, passThroughOnException() {} };
}

function restoreEnvironment(name, previousValue) {
  if (previousValue === undefined) delete process.env[name];
  else process.env[name] = previousValue;
}
