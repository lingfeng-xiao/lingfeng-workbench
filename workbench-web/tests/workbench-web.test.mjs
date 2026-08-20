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

test("uses only v2 read endpoints and a server-side bearer credential", async () => {
  const requests = [];
  const response = await renderWithFakeService("/", ({ method, path, authorization }) => {
    requests.push({ method, path, authorization });
    if (path === "/api/client/v2/work-items?limit=50") return jsonResponse(workItems());
    if (path === "/api/client/v2/nodes") return jsonResponse(nodes());
    return jsonResponse(apiError(), 404);
  });

  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /工作正在什么位置/);
  assert.doesNotMatch(html, new RegExp(READ_TOKEN));
  assert.deepEqual(
    requests.map(({ method, path }) => ({ method, path })).sort((left, right) => left.path.localeCompare(right.path)),
    [
      { method: "GET", path: "/api/client/v2/nodes" },
      { method: "GET", path: "/api/client/v2/work-items?limit=50" },
    ],
  );
  assert.ok(requests.every(({ authorization }) => authorization === `Bearer ${READ_TOKEN}`));
});

test("renders running, waiting, completed, uncertain, offline, and last-sync projections", async () => {
  const response = await renderWithFakeService("/", ({ path }) => {
    if (path === "/api/client/v2/work-items?limit=50") return jsonResponse(workItems());
    if (path === "/api/client/v2/nodes") return jsonResponse(nodes());
    return jsonResponse(apiError(), 404);
  });
  const html = await response.text();

  assert.match(html, /执行中/);
  assert.match(html, /等待输入/);
  assert.match(html, /合同校验完成/);
  assert.match(html, /Service 网络恢复后等待终态确认/);
  assert.match(html, /最后同步于/);
  assert.match(html, /1\/2/);
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
    ["/", "现在没有活动工作"],
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
    const response = await renderWithFakeService("/", ({ authorization }) => {
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
    ["unknown field", () => jsonResponse([{ ...workItems()[0], runtimeSessionId: "session-secret" }])],
    ["unknown state", () => jsonResponse([{ ...workItems()[0], status: "invented" }])],
  ];

  for (const [name, respond] of cases) {
    await context.test(name, async () => {
      const response = await renderWithFakeService("/", ({ path }) => {
        if (path.includes("work-items")) return respond();
        return jsonResponse(nodes());
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
    /WORKBENCH_SERVICE_BASE_URL|WORKBENCH_SERVICE_READ_TOKEN|test-sites-read-token/,
  );

  const applicationSources = await Promise.all([
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/interactions/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/nodes/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/work-items/[id]/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/_lib/workbench-service.ts", import.meta.url), "utf8"),
  ]);
  assert.doesNotMatch(applicationSources.join("\n"), /localStorage|sessionStorage/);
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

async function renderWithFakeService(path, respond, { timeoutMs = 500 } = {}) {
  const fakeService = await startFakeService(respond);
  try {
    return await withServiceEnvironment({ baseUrl: fakeService.baseUrl, timeoutMs }, () =>
      worker.fetch(
        new Request(`http://localhost${path}`, { headers: AUTHENTICATED_HEADERS }),
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
    timeout: process.env.WORKBENCH_SERVICE_TIMEOUT_MS,
  };
  process.env.WORKBENCH_SERVICE_BASE_URL = baseUrl;
  process.env.WORKBENCH_SERVICE_READ_TOKEN = READ_TOKEN;
  process.env.WORKBENCH_SERVICE_TIMEOUT_MS = String(timeoutMs);
  try {
    return await action();
  } finally {
    restoreEnvironment("WORKBENCH_SERVICE_BASE_URL", previous.baseUrl);
    restoreEnvironment("WORKBENCH_SERVICE_READ_TOKEN", previous.readToken);
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

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function workItems() {
  return [
    workItem("wi_running", "验证多轮执行", "in_progress", {
      phaseCode: "IMPLEMENTATION",
      progressSummary: "执行中",
      lastSyncedAt: "2026-08-20T08:10:00Z",
    }),
    workItem("wi_waiting", "等待审批恢复", "in_progress", {
      phaseCode: "CONTRACT_REVIEW",
      progressSummary: "等待输入",
      waitingInteractionCount: 1,
      lastSyncedAt: "2026-08-20T08:11:00Z",
    }),
    workItem("wi_completed", "可信终态", "completed", {
      phaseCode: "REPORTING",
      progressSummary: "合同校验完成",
      lastSyncedAt: "2026-08-20T08:12:00Z",
    }),
    workItem("wi_uncertain", "待核对终态", "attention_required", {
      phaseCode: "BUILD_VALIDATION",
      progressSummary: "Service 网络恢复后等待终态确认",
      lastSyncedAt: "2026-08-20T08:13:00Z",
    }),
  ];
}

function workItem(workItemId, title, status, optional = {}) {
  return {
    workItemId,
    title,
    status,
    priority: 0,
    ...optional,
    updatedAt: "2026-08-20T08:15:00Z",
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
