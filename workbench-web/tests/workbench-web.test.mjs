import assert from "node:assert/strict";
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

const workItems = [
  workItem("wi_open", "准备联调", "open"),
  workItem("wi_running", "验证无工具任务", "in_progress"),
  workItem("wi_done", "合同基线", "completed"),
  workItem("wi_attention", "检查未知终态", "attention_required"),
  workItem("wi_cancelled", "停止的试验", "cancelled"),
];

const nodes = [
  {
    nodeId: "office-pc",
    displayName: "Office PC",
    status: "online",
    capabilities: ["runtime.ws", "resume"],
    lastHeartbeatAt: "2026-08-19T08:15:00Z",
    localAbsolutePath: "C:\\secret\\workspace",
    runtimeSessionId: "session-should-not-render",
  },
  {
    nodeId: "home-pc",
    displayName: "Home PC",
    status: "offline",
    capabilities: [],
    lastHeartbeatAt: "2026-08-18T12:00:00Z",
  },
];

test("rejects a browser request without Sites identity headers", async () => {
  const response = await renderWorker("/", { authenticated: false });

  assert.ok([302, 303, 307, 308].includes(response.status));
  assert.equal(
    response.headers.get("location"),
    "/signin-with-chatgpt?return_to=%2F",
  );
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("renders the overview statuses without leaking unexpected service fields", async () => {
  const response = await renderWithFakeService("/", ({ path, authorization }) => {
    assert.equal(authorization, `Bearer ${READ_TOKEN}`);
    if (path === "/api/client/v1/work-items?limit=50") {
      return jsonResponse(workItems);
    }
    if (path === "/api/client/v1/nodes") {
      return jsonResponse(nodes);
    }
    return jsonResponse({ code: "not_found" }, 404);
  });

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const html = await response.text();
  assert.match(html, /工作正在什么位置/);
  assert.match(html, /待执行/);
  assert.match(html, /进行中/);
  assert.match(html, /已完成/);
  assert.match(html, /需关注/);
  assert.match(html, /已取消/);
  assert.doesNotMatch(html, /session-should-not-render/);
  assert.doesNotMatch(html, /secret\\workspace/);
});

test("renders an empty state on each read-only surface", async () => {
  const emptyWorkItem = {
    ...workItem("wi_empty", "空工作项", "open"),
    missions: [],
  };
  const fakeService = ({ path }) => {
    if (path === "/api/client/v1/work-items?limit=50") return jsonResponse([]);
    if (path === "/api/client/v1/nodes") return jsonResponse([]);
    if (path === "/api/client/v1/interactions?state=pending") {
      return jsonResponse([]);
    }
    if (path === "/api/client/v1/work-items/wi_empty") {
      return jsonResponse(emptyWorkItem);
    }
    return jsonResponse({}, 404);
  };

  const home = await renderWithFakeService("/", fakeService);
  const interactions = await renderWithFakeService("/interactions", fakeService);
  const nodeList = await renderWithFakeService("/nodes", fakeService);
  const detail = await renderWithFakeService("/work-items/wi_empty", fakeService);
  const htmlPages = await Promise.all(
    [home, interactions, nodeList, detail].map((response) => response.text()),
  );

  assert.match(htmlPages[0], /现在没有活动工作/);
  assert.match(htmlPages[1], /没有待处理输入/);
  assert.match(htmlPages[2], /还没有注册 Node/);
  assert.match(htmlPages[3], /没有 Mission/);
});

test("renders WorkItem, Mission, Run, Interaction, and Node projections", async () => {
  const detail = {
    ...workItem("wi_running", "验证无工具任务", "in_progress"),
    missions: [
      {
        missionId: "mi_01",
        workItemId: "wi_running",
        revision: 1,
        missionDigest: "a".repeat(64),
        objective: "让 WS 返回结构化终态",
        acceptanceSummary: "验收状态必须为 PASSED",
        authorizedSideEffectsSummary: "不调用任何工具",
        targetNodeId: "office-pc",
        workspaceRef: "workspace_ref",
        runtimeKind: "ws",
        executionProfile: "no-tools",
        status: "waiting_interaction",
        runs: [
          {
            runId: "run_01",
            missionId: "mi_01",
            nodeId: "office-pc",
            status: "uncertain",
            progressSummary: "Runtime 已退出",
            resultSummary: "缺少结构化验收终态",
            resumable: true,
            updatedAt: "2026-08-19T08:20:00Z",
            runtimeSessionId: "runtime-secret",
            rawRuntimeEvents: "raw-secret",
          },
        ],
        createdAt: "2026-08-19T08:00:00Z",
        updatedAt: "2026-08-19T08:20:00Z",
      },
    ],
  };
  const interactions = [
    {
      interactionId: "int_01",
      runId: "run_01",
      checkpointId: "checkpoint_01",
      missionDigest: "a".repeat(64),
      state: "pending",
      promptSummary: "是否接受这次无工具运行？",
      createdAt: "2026-08-19T08:19:00Z",
      artifact: "artifact-secret",
    },
  ];
  const fakeService = ({ path }) => {
    if (path === "/api/client/v1/work-items/wi_running") {
      return jsonResponse(detail);
    }
    if (path === "/api/client/v1/interactions?state=pending") {
      return jsonResponse(interactions);
    }
    if (path === "/api/client/v1/nodes") return jsonResponse(nodes);
    return jsonResponse({}, 404);
  };

  const detailResponse = await renderWithFakeService(
    "/work-items/wi_running",
    fakeService,
  );
  const interactionResponse = await renderWithFakeService(
    "/interactions",
    fakeService,
  );
  const nodeResponse = await renderWithFakeService("/nodes", fakeService);
  const [detailHtml, interactionHtml, nodeHtml] = await Promise.all([
    detailResponse.text(),
    interactionResponse.text(),
    nodeResponse.text(),
  ]);

  assert.match(detailHtml, /让 WS 返回结构化终态/);
  assert.match(detailHtml, /等待输入/);
  assert.match(detailHtml, /结果未知/);
  assert.match(detailHtml, /可恢复/);
  assert.doesNotMatch(detailHtml, /runtime-secret|raw-secret|workspace_ref/);
  assert.match(interactionHtml, /是否接受这次无工具运行/);
  assert.match(interactionHtml, /checkpoint_01/);
  assert.doesNotMatch(interactionHtml, /artifact-secret/);
  assert.match(nodeHtml, /Office PC/);
  assert.match(nodeHtml, /在线/);
  assert.match(nodeHtml, /离线/);
});

test("shows bounded error states for upstream authorization and availability failures", async (context) => {
  for (const status of [401, 403]) {
    await context.test(`upstream ${status}`, async () => {
      const response = await renderWithFakeService("/", () =>
        jsonResponse({ code: "denied" }, status),
      );
      assert.match(await response.text(), /Service 拒绝了只读请求/);
    });
  }

  await context.test("upstream 502", async () => {
    const response = await renderWithFakeService("/", () =>
      jsonResponse({ code: "unavailable" }, 502),
    );
    assert.match(await response.text(), /暂时无法读取控制状态/);
  });

  await context.test("upstream timeout", async () => {
    const response = await renderWithFakeService(
      "/",
      () => new Promise((resolve) => setTimeout(() => resolve(jsonResponse([])), 250)),
      { timeoutMs: 100 },
    );
    assert.match(await response.text(), /暂时无法读取控制状态/);
  });
});

test("rejects a malformed upstream payload instead of guessing state", async () => {
  const response = await renderWithFakeService("/", ({ path }) => {
    if (path.includes("work-items")) {
      return jsonResponse([{ workItemId: "wi_bad", status: "invented" }]);
    }
    return jsonResponse([]);
  });

  assert.match(await response.text(), /Service 响应不符合合同/);
});

test("keeps the generated site free of business persistence and browser storage", async () => {
  const { readFile } = await import("node:fs/promises");
  const [hosting, sources] = await Promise.all([
    readFile(new URL("../.openai/hosting.json", import.meta.url), "utf8"),
    Promise.all([
      readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
      readFile(new URL("../app/_lib/workbench-service.ts", import.meta.url), "utf8"),
      readFile(new URL("../worker/index.ts", import.meta.url), "utf8"),
    ]),
  ]);

  assert.deepEqual(JSON.parse(hosting), {
    project_id: "appgprj_6a841dad1a8881919399cc5bced2c838",
    d1: null,
    r2: null,
  });
  assert.doesNotMatch(sources.join("\n"), /localStorage|sessionStorage|D1Database|R2Bucket/);
});

async function renderWorker(path, { authenticated = true } = {}) {
  process.env.WORKBENCH_SERVICE_BASE_URL = "https://unused.example";
  process.env.WORKBENCH_SERVICE_READ_TOKEN = READ_TOKEN;
  return worker.fetch(
    new Request(`http://localhost${path}`, {
      headers: authenticated ? AUTHENTICATED_HEADERS : { accept: "text/html" },
    }),
    workerEnvironment(),
    workerContext(),
  );
}

async function renderWithFakeService(path, respond, { timeoutMs = 500 } = {}) {
  const fakeService = await startFakeService(respond);
  const previousBaseUrl = process.env.WORKBENCH_SERVICE_BASE_URL;
  const previousReadToken = process.env.WORKBENCH_SERVICE_READ_TOKEN;
  const previousTimeout = process.env.WORKBENCH_SERVICE_TIMEOUT_MS;
  process.env.WORKBENCH_SERVICE_BASE_URL = fakeService.baseUrl;
  process.env.WORKBENCH_SERVICE_READ_TOKEN = READ_TOKEN;
  process.env.WORKBENCH_SERVICE_TIMEOUT_MS = String(timeoutMs);

  try {
    return await worker.fetch(
      new Request(`http://localhost${path}`, { headers: AUTHENTICATED_HEADERS }),
      workerEnvironment(),
      workerContext(),
    );
  } finally {
    restoreEnvironment("WORKBENCH_SERVICE_BASE_URL", previousBaseUrl);
    restoreEnvironment("WORKBENCH_SERVICE_READ_TOKEN", previousReadToken);
    restoreEnvironment("WORKBENCH_SERVICE_TIMEOUT_MS", previousTimeout);
    await fakeService.close();
  }
}

async function startFakeService(respond) {
  const server = createServer(async (request, response) => {
    const serviceResponse = await respond({
      path: request.url,
      authorization: request.headers.authorization,
    });
    response.writeHead(serviceResponse.status, Object.fromEntries(serviceResponse.headers));
    response.end(await serviceResponse.text());
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

function workItem(workItemId, title, status) {
  return {
    workItemId,
    title,
    status,
    priority: 0,
    updatedAt: "2026-08-19T08:00:00Z",
  };
}

function workerEnvironment() {
  return {
    ASSETS: {
      fetch: async () => new Response("Not found", { status: 404 }),
    },
  };
}

function workerContext() {
  return {
    waitUntil() {},
    passThroughOnException() {},
  };
}

function restoreEnvironment(name, previousValue) {
  if (previousValue === undefined) {
    delete process.env[name];
  } else {
    process.env[name] = previousValue;
  }
}
