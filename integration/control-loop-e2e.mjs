import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import {
  access,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  writeFile,
} from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0";

const repositoryDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const javaHome = process.env.JAVA_HOME;
assert.ok(javaHome, "JAVA_HOME must point to a Java 21 JDK");

const javaExecutable = join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
const keytoolExecutable = join(javaHome, "bin", process.platform === "win32" ? "keytool.exe" : "keytool");
const serviceJar = join(repositoryDirectory, "workbench-service", "target", "workbench-service-0.5.0-trusted-loop-rc1.jar");
const nodeJar = join(repositoryDirectory, "workbench-node", "target", "workbench-node-0.5.0-trusted-loop-rc1.jar");
const webWorker = join(repositoryDirectory, "workbench-web", "dist", "server", "index.js");

const creatorToken = "creator-local-e2e-token-0000000000000001";
const hermesToken = "hermes-local-e2e-token-00000000000000001";
const sitesToken = "sites-local-e2e-token-000000000000000001";
const nodeToken = "node-local-e2e-token-0000000000000000001";
const nodeId = "node_alpha";
const workspaceRef = "workspace_main";

await Promise.all([access(javaExecutable), access(keytoolExecutable), access(serviceJar), access(nodeJar), access(webWorker)]);

const evidenceRoot = await mkdtemp(join(tmpdir(), "lingfeng-control-loop-e2e-"));
const tls = await createLocalTls(evidenceRoot);
const realWsDevelopmentMode = process.argv.includes("--real-ws-development");
const realWsMode = realWsDevelopmentMode || process.argv.includes("--real-ws");
const realWsBaseUri = realWsMode ? requiredEnvironment("WORKBENCH_WS_BASE_URI") : null;
const realWsExpectedVersion = realWsMode
  ? (process.env.WORKBENCH_WS_EXPECTED_VERSION?.trim() || "0.0.0--202608171122")
  : null;
const realWsObservationMs = realWsMode ? resolveRealWsObservationMs(realWsDevelopmentMode) : null;
const realWsScenario = realWsMode ? resolveRealWsScenario(realWsDevelopmentMode) : null;
if (realWsDevelopmentMode) {
  await access(realWsScenario.workspace);
}
const summary = realWsMode
  ? {
      executedAt: new Date().toISOString(),
      evidenceRoot,
      javaVersion: javaVersionLine(runChecked(javaExecutable, ["-version"]).stderr),
      realWs: await runRealWsScenario(join(evidenceRoot, "real-ws")),
    }
  : {
      executedAt: new Date().toISOString(),
      evidenceRoot,
      javaVersion: javaVersionLine(runChecked(javaExecutable, ["-version"]).stderr),
      task: await runTaskBusinessLoopScenario(join(evidenceRoot, "task-business-loop")),
      flow: await runFlowScenario(join(evidenceRoot, "flow")),
      notify: await runNotificationScenario(join(evidenceRoot, "notify")),
    };
await writeFile(join(evidenceRoot, "summary.json"), JSON.stringify(summary, null, 2), "utf8");
console.log(JSON.stringify(summary, null, 2));
console.log(realWsMode
  ? summary.realWs.blocker
    ? "Real WS control-loop attempt ended without completion; inspect realWs.blocker and blocker evidence."
    : "Real WS control-loop attempt verified persistence across Service restart; inspect realWs.proof."
  : "E2E-TASK, E2E-FLOW, and E2E-NOTIFY passed with real Service/Node JARs and the Web production build.");

async function runRealWsScenario(scenarioDirectory) {
  await mkdir(scenarioDirectory, { recursive: true });
  const port = await reservePort();
  const serviceState = join(scenarioDirectory, "service");
  const nodeState = join(scenarioDirectory, "node");
  const workspace = realWsScenario.workspace || join(scenarioDirectory, "workspace-no-tools");
  await Promise.all([
    mkdir(serviceState),
    mkdir(nodeState),
    ...(realWsScenario.workspace ? [] : [mkdir(workspace)]),
  ]);
  const serviceDatabase = join(serviceState, "service.db");
  let service = await startService({ scenarioDirectory, serviceDatabase, port });
  let node = await startNode({
    scenarioDirectory,
    nodeState,
    workspace,
    port,
    runtimeKind: "ws",
    wsBaseUri: realWsBaseUri,
    wsExpectedVersion: realWsExpectedVersion,
    acceptanceProfile: realWsScenario.acceptanceProfile,
  });

  try {
    await waitForNodeRegistration(port);
    const created = await createTask(port, "real-ws-task-create-key", {
      title: realWsScenario.title,
      runtimeKind: "ws",
      objective: realWsScenario.objective,
      acceptanceSummary: realWsScenario.acceptanceSummary,
      sideEffectSummary: realWsScenario.authorizedSideEffectsSummary,
      executionProfile: realWsScenario.executionProfile,
    });
    let task = await getTask(port, created.taskId);
    task = await taskAction(port, created.taskId, "mark-ready", "real-ws-task-ready-key",
      task.version, "Freeze the real WS canary contract without starting execution");
    const started = await mutateTask(port, created.taskId, "/start", "POST",
      "real-ws-task-start-key", actionBody(task.version, "Explicitly start the real WS canary"));
    const deadline = Date.now() + realWsObservationMs;
    const resolvedInteractionIds = new Set();
    let terminalRun;
    do {
      task = await getTask(port, created.taskId);
      terminalRun = task.runs.find((run) => run.runId === started.runId);
      if (terminalRun
          && ["completed", "failed", "interrupted", "uncertain", "cancelled"].includes(terminalRun.status)) {
        break;
      }
      if (realWsScenario.autoApproveInteractions) {
        await approveBoundedCanaryInteractions(port, started, nodeState, resolvedInteractionIds);
      }
      await sleep(250);
    } while (Date.now() < deadline);

    const runDirectory = join(nodeState, "runs", started.runId);
    const runtimeEvents = await readFile(join(runDirectory, "runtime-events.ndjson"), "utf8").catch(() => "");
    const stderr = await readFile(join(runDirectory, "runtime-stderr.log"), "utf8").catch(() => "");
    const missionSnapshot = await readJson(join(runDirectory, "mission.json"));
    const acceptanceReport = await readJson(join(runDirectory, "acceptance-report.json"));
    const sessionMatch = runtimeEvents.match(/"sessionID"\s*:\s*"([^"]+)"/);
    const wsSessionId = sessionMatch ? sessionMatch[1] : null;
    const terminalStatus = terminalRun?.status ?? "not_terminal";
    const acceptancePassed = acceptanceReport?.status === "PASSED"
      && acceptanceReport.configured === true
      && acceptanceReport.started === true
      && acceptanceReport.finished === true
      && acceptanceReport.exitCode === 0
      && acceptanceReport.requiredArtifacts?.every((artifact) => artifact.present === true);
    const isReviewReady = terminalStatus === "completed"
      && task.businessStatus === "REVIEW"
      && task.acceptanceStatus === "PENDING"
      && acceptancePassed;

    if (!isReviewReady) {
      return {
        taskId: created.taskId,
        workItemId: started.workItemId,
        runId: started.runId,
        missionDigest: missionSnapshot?.missionDigest ?? null,
        status: terminalStatus,
        businessStatus: task.businessStatus,
        acceptanceStatus: task.acceptanceStatus,
        resultSummary: terminalRun?.resultSummary || null,
        localAcceptanceStatus: acceptanceReport?.status ?? null,
        sessionCount: Number(querySqlite(join(nodeState, "node.db"),
          "SELECT COUNT(*) FROM control_agent_session")),
        wsSessionId,
        runtimeEventsBytes: Buffer.byteLength(runtimeEvents),
        runtimeStderrBytes: Buffer.byteLength(stderr),
        observationWindowMs: realWsObservationMs,
        developmentMode: realWsDevelopmentMode,
        approvedInteractionCount: resolvedInteractionIds.size,
        nodeEvidenceDirectory: runDirectory,
        blocker: "WS did not reach a terminal projection within the bounded observation window",
        blockerEvidence: {
          terminalStatus,
          observationWindowExceeded: Date.now() >= deadline,
          runtimeEventsPreview: runtimeEvents.slice(0, 2000),
          stderrPreview: stderr.slice(0, 2000),
        },
        webRenderedBeforeRestart: false,
        webRenderedAfterRestart: false,
        serviceRestarted: false,
        serviceRestarts: 0,
        serviceEvidenceScanned: false,
        serviceEvidenceClean: false,
        proof: {
          claim: "fail-closed",
          reason: "Run did not reach REVIEW/PENDING with an independent PASSED report",
          terminalStatus,
          businessStatus: task.businessStatus,
          acceptanceStatus: task.acceptanceStatus,
          localAcceptanceStatus: acceptanceReport?.status ?? null,
        },
      };
    }

    const webHtmlBeforeRestart = await renderProductionWeb(port, `/tasks/${created.taskId}`);
    assert.ok(webHtmlBeforeRestart.includes(realWsScenario.title),
      "Web render before acceptance did not contain the Task title");
    assert.match(webHtmlBeforeRestart, /待验收/);

    task = await mutateTask(port, created.taskId, "/accept", "POST", "real-ws-task-accept-key", {
      ...actionBody(task.version, "Human-scoped E2E client verified the independent local acceptance report"),
      deliverySummary: "Real WS completed the canary and Node independently passed the configured acceptance profile",
      commitSha: "0000000",
      prUrl: "https://example.test/local-e2e/no-pr",
    });
    assert.equal(task.businessStatus, "DONE");
    assert.equal(task.acceptanceStatus, "ACCEPTED");

    await stopChild(service);
    service = null;
    service = await startService({ scenarioDirectory, serviceDatabase, port });

    const afterRestart = await getTask(port, created.taskId);
    assert.equal(afterRestart.businessStatus, "DONE");
    assert.equal(afterRestart.acceptanceStatus, "ACCEPTED");
    assert.equal(afterRestart.runs.find((run) => run.runId === started.runId)?.status, "completed");

    const webHtmlAfterRestart = await renderProductionWeb(port, `/tasks/${created.taskId}`);
    assert.ok(webHtmlAfterRestart.includes(realWsScenario.title),
      "Web render after restart did not contain the Task title");
    assert.match(webHtmlAfterRestart, /已完成/);

    await stopChild(node);
    node = null;
    await stopChild(service);
    service = null;

    const forbiddenEvidence = [
      wsSessionId,
      workspace,
      "runtime-events.ndjson",
      "conversation.ndjson",
    ].filter(Boolean);
    await assertServiceContainsNoLocalEvidence(serviceState, forbiddenEvidence);

    return {
      taskId: created.taskId,
      workItemId: started.workItemId,
      runId: started.runId,
      missionDigest: missionSnapshot.missionDigest,
      status: terminalStatus,
      businessStatus: afterRestart.businessStatus,
      acceptanceStatus: afterRestart.acceptanceStatus,
      resultSummary: terminalRun.resultSummary || null,
      localAcceptanceStatus: acceptanceReport.status,
      sessionCount: Number(querySqlite(join(nodeState, "node.db"),
        "SELECT COUNT(*) FROM control_agent_session")),
      wsSessionId,
      runtimeEventsBytes: Buffer.byteLength(runtimeEvents),
      runtimeStderrBytes: Buffer.byteLength(stderr),
      observationWindowMs: realWsObservationMs,
      developmentMode: realWsDevelopmentMode,
      approvedInteractionCount: resolvedInteractionIds.size,
      nodeEvidenceDirectory: runDirectory,
      blocker: null,
      webRenderedBeforeRestart: true,
      webRenderedAfterRestart: true,
      serviceRestarted: true,
      serviceRestarts: 1,
      serviceEvidenceScanned: true,
      serviceEvidenceClean: true,
      proof: {
        claim: "verified",
        completedBeforeRestart: terminalStatus,
        completedAfterRestart: afterRestart.runs.find((run) => run.runId === started.runId)?.status,
        reviewBeforeAcceptance: "REVIEW/PENDING",
        acceptedBeforeRestart: "DONE/ACCEPTED",
        acceptedAfterRestart: `${afterRestart.businessStatus}/${afterRestart.acceptanceStatus}`,
        localAcceptanceReport: {
          status: acceptanceReport.status,
          configured: acceptanceReport.configured,
          exitCode: acceptanceReport.exitCode,
          requiredArtifacts: acceptanceReport.requiredArtifacts,
        },
        titleMatchedBeforeRestart: webHtmlBeforeRestart.includes(realWsScenario.title),
        titleMatchedAfterRestart: webHtmlAfterRestart.includes(realWsScenario.title),
        pendingAcceptanceMarkerBeforeRestart: /待验收/.test(webHtmlBeforeRestart),
        completedMarkerAfterRestart: /已完成/.test(webHtmlAfterRestart),
        sameDatabase: toForwardSlashes(serviceDatabase),
        samePort: port,
        forbiddenEvidenceScanned: [
          wsSessionId ? "wsSessionId" : null,
          "workspacePath",
          "runtime-events.ndjson",
          "conversation.ndjson",
        ].filter(Boolean),
      },
    };
  } finally {
    await stopChild(node);
    await stopChild(service);
  }
}

async function approveBoundedCanaryInteractions(port, started, nodeState, resolvedInteractionIds) {
  const detail = await getWorkItem(port, started.workItemId);
  const pending = detail.interactions.filter((interaction) => interaction.state === "pending"
    && interaction.allowedDecisions.includes("APPROVE")
    && !resolvedInteractionIds.has(interaction.interactionId));
  if (pending.length === 0) return;
  const missionSnapshot = await readJson(join(nodeState, "runs", started.runId, "mission.json"));
  if (!missionSnapshot?.missionDigest) return;
  for (const interaction of pending) {
    await requestJson(port, `/api/client/v2/interactions/${interaction.interactionId}/resolution`, {
      token: hermesToken,
      method: "POST",
      headers: { "Idempotency-Key": `real-ws-resolution-${interaction.interactionId}` },
      body: {
        interactionId: interaction.interactionId,
        runId: started.runId,
        checkpointId: interaction.checkpointId,
        missionDigest: missionSnapshot.missionDigest,
        decision: "APPROVE",
        responseSummary: "Approved only for the isolated temporary real-WS canary workspace",
        resolvedBy: "e2e_user",
        resolvedAt: new Date().toISOString(),
      },
    });
    resolvedInteractionIds.add(interaction.interactionId);
  }
}

async function runFlowScenario(scenarioDirectory) {
  await mkdir(scenarioDirectory, { recursive: true });
  const port = await reservePort();
  const serviceState = join(scenarioDirectory, "service");
  const nodeState = join(scenarioDirectory, "node");
  const workspace = join(scenarioDirectory, "workspace-sensitive-local-path");
  await Promise.all([mkdir(serviceState), mkdir(nodeState), mkdir(workspace)]);
  const serviceDatabase = join(serviceState, "service.db");
  let service = await startService({ scenarioDirectory, serviceDatabase, port });
  let node = await startNode({ scenarioDirectory, nodeState, workspace, port, scenario: "FLOW", turnDelay: "PT2S" });

  try {
    await waitForNodeRegistration(port);
    const created = await createWorkItem(port, {
      idempotencyKey: "flow-create-key",
      title: "E2E FLOW with Service outage",
    });
    await waitFor(async () => {
      const detail = await getWorkItem(port, created.workItemId);
      return detail.run.status === "running" ? detail : null;
    }, 30_000, "FLOW Run to start");

    await stopChild(service);
    service = null;
    await waitFor(async () => {
      const outboxCount = Number(querySqlite(join(nodeState, "node.db"),
        "SELECT COUNT(*) FROM control_outbox"));
      const terminalCount = Number(querySqlite(join(nodeState, "node.db"),
        "SELECT COUNT(*) FROM control_local_event WHERE event_type='RUN_TERMINAL'"));
      return terminalCount === 1 && outboxCount > 0 ? { terminalCount, outboxCount } : null;
    }, 30_000, "Runtime and independent acceptance to finish while Service is offline");

    service = await startService({ scenarioDirectory, serviceDatabase, port });
    const completed = await waitFor(async () => {
      const detail = await getWorkItem(port, created.workItemId);
      return detail.run.status === "completed" ? detail : null;
    }, 40_000, "FLOW outbox replay to complete the Run");
    assert.equal(completed.run.resultSummary, "Independent deterministic fake acceptance checks passed");
    assert.equal(completed.run.resumable, false);

    await waitFor(() => Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_outbox")) === 0, 20_000, "FLOW outbox to drain");
    const missionSnapshot = JSON.parse(await readFile(join(nodeState, "runs", created.runId, "mission.json"), "utf8"));
    assert.equal(missionSnapshot.missionDigest, created.missionDigest);
    assert.equal(Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_agent_session")), 1);

    await stopChild(service);
    service = await startService({ scenarioDirectory, serviceDatabase, port });
    const afterRestart = await getWorkItem(port, created.workItemId);
    assert.equal(afterRestart.run.status, "completed");
    const webHtml = await renderProductionWeb(port, `/work-items/${created.workItemId}`);
    assert.match(webHtml, /E2E FLOW with Service outage/);
    assert.match(webHtml, /已完成/);

    await stopChild(node);
    node = null;
    await stopChild(service);
    service = null;
    await assertServiceContainsNoLocalEvidence(serviceState, [workspace, "fake-session:", "runtime-events.ndjson"]);
    const evidenceFiles = await readdir(join(nodeState, "runs", created.runId));
    for (const requiredFile of [
      "mission.json",
      "control-commands.ndjson",
      "normalized-events.ndjson",
      "runtime-events.ndjson",
      "runtime-stderr.log",
      "conversation.ndjson",
      "result.md",
    ]) {
      assert.ok(evidenceFiles.includes(requiredFile), `missing Node evidence file ${requiredFile}`);
    }
    return {
      workItemId: created.workItemId,
      runId: created.runId,
      missionDigest: created.missionDigest,
      missionPrompts: 1,
      finalStatus: afterRestart.run.status,
      webRenderedCompleted: true,
      serviceRestarts: 2,
      nodeEvidenceFiles: evidenceFiles.sort(),
    };
  } finally {
    await stopChild(node);
    await stopChild(service);
  }
}

async function runTaskBusinessLoopScenario(scenarioDirectory) {
  await mkdir(scenarioDirectory, { recursive: true });
  const port = await reservePort();
  const serviceState = join(scenarioDirectory, "service");
  const nodeState = join(scenarioDirectory, "node");
  const workspace = join(scenarioDirectory, "workspace-sensitive-local-path");
  await Promise.all([mkdir(serviceState), mkdir(nodeState), mkdir(workspace)]);
  await writeFile(join(workspace, "context.md"), "Local-only Task context", "utf8");
  const serviceDatabase = join(serviceState, "service.db");
  let service = await startService({ scenarioDirectory, serviceDatabase, port });
  let node = await startNode({
    scenarioDirectory,
    nodeState,
    workspace,
    port,
    scenario: "FLOW",
    turnDelay: "PT0.5S",
  });

  try {
    await waitForNodeRegistration(port);
    const created = await createTask(port, "task-create-key");
    assert.equal(created.businessStatus, "DRAFT");
    assert.equal(Number(querySqlite(serviceDatabase, "SELECT COUNT(*) FROM work_items")), 0);

    let task = await getTask(port, created.taskId);
    task = await mutateTask(port, created.taskId, "", "PUT", "task-edit-key", {
      expectedVersion: task.version,
      title: "E2E Task business loop edited",
      objective: task.objective,
      acceptanceSummary: task.acceptanceSummary,
      sideEffectSummary: task.sideEffectSummary,
      priority: task.priority,
      targetNodeId: task.targetNodeId,
      workspaceRef: task.workspaceRef,
      contextRefs: task.contextRefs,
      runtimeKind: task.runtimeKind,
      executionProfile: task.executionProfile,
      actor: "e2e_user",
      reason: "Exercise the durable edit path before execution",
    });
    assert.equal(task.businessStatus, "DRAFT");
    assert.equal(Number(querySqlite(serviceDatabase, "SELECT COUNT(*) FROM work_items")), 0);

    task = await taskAction(port, created.taskId, "mark-ready", "task-ready-key", task.version,
      "Freeze the Task contract without starting WS");
    assert.equal(task.businessStatus, "READY");
    assert.equal(Number(querySqlite(serviceDatabase, "SELECT COUNT(*) FROM work_items")), 0);

    const firstStartBody = actionBody(task.version, "Explicitly start the first execution");
    const firstStart = await mutateTask(
      port, created.taskId, "/start", "POST", "task-start-first-key", firstStartBody);
    const duplicateStart = await mutateTask(
      port, created.taskId, "/start", "POST", "task-start-first-key", firstStartBody);
    assert.equal(duplicateStart.runId, firstStart.runId);
    assert.equal(Number(querySqlite(serviceDatabase, "SELECT COUNT(*) FROM work_items")), 1);

    task = await waitFor(async () => {
      const detail = await getTask(port, created.taskId);
      return detail.businessStatus === "REVIEW" && detail.acceptanceStatus === "PENDING"
        ? detail
        : null;
    }, 40_000, "first Task Run to enter REVIEW/PENDING");
    assert.equal(task.runs.length, 1);
    assert.ok(task.timeline.filter((event) => event.eventType === "RUN_PROGRESS_UPDATED").length >= 2);
    assert.equal(Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_agent_session")), 1);
    const webBeforeRestart = await renderProductionWeb(port, `/tasks/${created.taskId}`);
    assert.match(webBeforeRestart, /业务状态/);
    assert.match(webBeforeRestart, /验收状态/);
    assert.match(webBeforeRestart, /待验收/);

    task = await taskAction(port, created.taskId, "request-changes", "task-changes-key",
      task.version, "The first delivery needs another execution");
    assert.equal(task.businessStatus, "READY");
    assert.equal(task.acceptanceStatus, "CHANGES_REQUESTED");
    const secondStart = await mutateTask(port, created.taskId, "/start", "POST",
      "task-start-second-key", actionBody(task.version, "Explicitly start the second execution"));
    assert.notEqual(secondStart.runId, firstStart.runId);

    task = await waitFor(async () => {
      const detail = await getTask(port, created.taskId);
      return detail.businessStatus === "REVIEW" && detail.runs.length === 2 ? detail : null;
    }, 40_000, "second Task Run to preserve history and enter REVIEW");
    assert.deepEqual(task.runs.map((run) => run.missionRevision), [2, 1]);
    assert.equal(Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_agent_session")), 2);

    task = await mutateTask(port, created.taskId, "/accept", "POST", "task-accept-key", {
      ...actionBody(task.version, "Human verified delivery, commit, and PR"),
      deliverySummary: "Two durable fake Runs completed and history was preserved",
      commitSha: "abcdef1234567890",
      prUrl: "https://example.com/lingfeng/pull/5",
    });
    assert.equal(task.businessStatus, "DONE");
    assert.equal(task.acceptanceStatus, "ACCEPTED");

    task = await taskAction(port, created.taskId, "archive", "task-archive-first-key",
      task.version, "Archive the accepted Task");
    assert.equal(task.businessStatus, "ARCHIVED");
    assert.equal((await getTask(port, created.taskId)).runs.length, 2);
    task = await taskAction(port, created.taskId, "restore", "task-restore-key",
      task.version, "Verify archive restore without losing history");
    assert.equal(task.businessStatus, "DONE");

    await stopChild(service);
    service = null;
    service = await startService({ scenarioDirectory, serviceDatabase, port });
    const afterRestart = await getTask(port, created.taskId);
    assert.equal(afterRestart.businessStatus, "DONE");
    assert.equal(afterRestart.runs.length, 2);
    assert.ok(afterRestart.timeline.length >= task.timeline.length);
    const webAfterRestart = await renderProductionWeb(port, `/tasks/${created.taskId}`);
    assert.match(webAfterRestart, /E2E Task business loop edited/);
    assert.ok((webAfterRestart.match(/Fake Mission reached its completion checkpoint/g) ?? []).length >= 2);

    const archived = await taskAction(port, created.taskId, "archive", "task-archive-final-key",
      afterRestart.version, "Leave the completed Task archived");
    assert.equal(archived.businessStatus, "ARCHIVED");
    await stopChild(node);
    node = null;
    await stopChild(service);
    service = null;
    await assertServiceContainsNoLocalEvidence(serviceState, [
      workspace,
      "fake-session:",
      "runtime-events.ndjson",
      "conversation.ndjson",
    ]);

    return {
      taskId: created.taskId,
      firstRunId: firstStart.runId,
      secondRunId: secondStart.runId,
      finalBusinessStatus: archived.businessStatus,
      acceptanceStatus: archived.acceptanceStatus,
      runCount: archived.runs.length,
      progressEventCount: archived.timeline.filter(
        (event) => event.eventType === "RUN_PROGRESS_UPDATED").length,
      timelineEventCount: archived.timeline.length,
      serviceRestarts: 1,
      webRenderedBeforeRestart: true,
      webRenderedAfterRestart: true,
      serviceEvidenceClean: true,
    };
  } finally {
    await stopChild(node);
    await stopChild(service);
  }
}

async function runNotificationScenario(scenarioDirectory) {
  await mkdir(scenarioDirectory, { recursive: true });
  const port = await reservePort();
  const serviceState = join(scenarioDirectory, "service");
  const nodeState = join(scenarioDirectory, "node");
  const workspace = join(scenarioDirectory, "workspace-sensitive-local-path");
  await Promise.all([mkdir(serviceState), mkdir(nodeState), mkdir(workspace)]);
  const serviceDatabase = join(serviceState, "service.db");
  let service = await startService({ scenarioDirectory, serviceDatabase, port });
  let node = await startNode({ scenarioDirectory, nodeState, workspace, port, scenario: "INTERACTION", turnDelay: "PT0.5S" });

  try {
    await waitForNodeRegistration(port);
    const created = await createWorkItem(port, {
      idempotencyKey: "notify-create-key",
      title: "E2E NOTIFY same Session resume",
    });
    const pendingInteraction = await waitFor(async () => {
      const interactions = await requestJson(port, "/api/client/v2/interactions?state=pending&limit=100", {
        token: sitesToken,
      });
      return interactions.find((interaction) => interaction.runId === created.runId) ?? null;
    }, 30_000, "Interaction to become pending");

    const notification = await requestJson(port, "/api/client/v2/notifications/poll", {
      token: hermesToken,
      method: "POST",
      body: { requestId: "poll_notify_1", targetAlias: "owner", sentAt: new Date().toISOString() },
    });
    assert.equal(notification.notificationAvailable, true);
    assert.equal(notification.notificationType, "INTERACTION_REQUIRED");
    assert.equal(notification.interactionId, pendingInteraction.interactionId);

    const deliveryBody = {
      deliveryEventId: "delivery_notify_1",
      notificationId: notification.notificationId,
      outcome: "DELIVERED",
      reportedAt: new Date().toISOString(),
    };
    const delivery = await requestJson(port,
      `/api/client/v2/notifications/${notification.notificationId}/delivery-events`, {
        token: hermesToken,
        method: "POST",
        headers: { "Idempotency-Key": "delivery-notify-key" },
        body: deliveryBody,
      });
    const duplicateDelivery = await requestJson(port,
      `/api/client/v2/notifications/${notification.notificationId}/delivery-events`, {
        token: hermesToken,
        method: "POST",
        headers: { "Idempotency-Key": "delivery-notify-key" },
        body: deliveryBody,
      });
    assert.equal(delivery.status, "delivered");
    assert.equal(duplicateDelivery.duplicate, true);

    const baseResolution = {
      interactionId: pendingInteraction.interactionId,
      runId: created.runId,
      checkpointId: pendingInteraction.checkpointId,
      missionDigest: created.missionDigest,
      decision: "APPROVE",
      responseSummary: "Approved by fake Hermes for the frozen local scope",
      resolvedBy: "fake-hermes",
      resolvedAt: new Date().toISOString(),
    };
    await requestJson(port, `/api/client/v2/interactions/${pendingInteraction.interactionId}/resolution`, {
      token: hermesToken,
      method: "POST",
      headers: { "Idempotency-Key": "wrong-digest-key" },
      body: { ...baseResolution, missionDigest: "b".repeat(64) },
      expectedStatus: 409,
    });
    await requestJson(port, `/api/client/v2/interactions/${pendingInteraction.interactionId}/resolution`, {
      token: hermesToken,
      method: "POST",
      headers: { "Idempotency-Key": "wrong-checkpoint-key" },
      body: { ...baseResolution, checkpointId: "cp_wrong" },
      expectedStatus: 409,
    });
    const resolution = await requestJson(port,
      `/api/client/v2/interactions/${pendingInteraction.interactionId}/resolution`, {
        token: hermesToken,
        method: "POST",
        headers: { "Idempotency-Key": "resolution-notify-key" },
        body: baseResolution,
      });
    const duplicateResolution = await requestJson(port,
      `/api/client/v2/interactions/${pendingInteraction.interactionId}/resolution`, {
        token: hermesToken,
        method: "POST",
        headers: { "Idempotency-Key": "resolution-notify-key" },
        body: baseResolution,
      });
    assert.equal(duplicateResolution.duplicate, true);
    assert.equal(duplicateResolution.commandId, resolution.commandId);

    const completed = await waitFor(async () => {
      const detail = await getWorkItem(port, created.workItemId);
      const interaction = detail.interactions.find((candidate) => candidate.interactionId === pendingInteraction.interactionId);
      return detail.run.status === "completed" && interaction?.state === "consumed" ? detail : null;
    }, 40_000, "Interaction response to be consumed by the same Session");
    assert.equal(completed.run.resultSummary, "Independent deterministic fake acceptance checks passed");

    await requestJson(port, `/api/client/v2/interactions/${pendingInteraction.interactionId}/resolution`, {
      token: hermesToken,
      method: "POST",
      headers: { "Idempotency-Key": "wrong-terminal-state-key" },
      body: { ...baseResolution, responseSummary: "Late conflicting resolution" },
      expectedStatus: 409,
    });

    const duplicateAckPayload = JSON.parse(querySqlite(join(nodeState, "node.db"),
      "SELECT payload_json FROM control_local_event WHERE event_type='COMMAND_STORED' ORDER BY local_sequence DESC LIMIT 1"));
    const duplicateAck = await requestJson(port, "/api/node/v2/events", {
      token: nodeToken,
      method: "POST",
      body: duplicateAckPayload,
    });
    assert.equal(duplicateAck.duplicate, true);
    await requestJson(port, "/api/node/v2/events", {
      token: nodeToken,
      method: "POST",
      body: { ...duplicateAckPayload, messageId: "wrong_node_event", nodeId: "node_beta" },
      expectedStatus: 403,
    });

    await waitFor(() => Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_outbox")) === 0, 20_000, "NOTIFY outbox to drain");
    assert.equal(Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_agent_session")), 1);
    assert.equal(Number(querySqlite(join(nodeState, "node.db"),
      "SELECT COUNT(*) FROM control_received_command WHERE command_type='PROVIDE_INTERACTION_RESPONSE'")), 1);
    assert.equal(querySqlite(join(nodeState, "node.db"),
      "SELECT response_state FROM control_interaction_binding LIMIT 1"), "consumed");

    const webHtml = await renderProductionWeb(port, `/work-items/${created.workItemId}`);
    assert.match(webHtml, /E2E NOTIFY same Session resume/);
    assert.match(webHtml, /已消费/);
    await stopChild(node);
    node = null;
    await stopChild(service);
    service = null;
    await assertServiceContainsNoLocalEvidence(serviceState, [workspace, "fake-session:", "conversation.ndjson"]);
    return {
      workItemId: created.workItemId,
      runId: created.runId,
      interactionId: pendingInteraction.interactionId,
      notificationId: notification.notificationId,
      responseCommandId: resolution.commandId,
      finalStatus: completed.run.status,
      interactionState: "consumed",
      duplicateDelivery: duplicateDelivery.duplicate,
      duplicateResolution: duplicateResolution.duplicate,
      duplicateAck: duplicateAck.duplicate,
      sameSessionCount: 1,
      webRenderedConsumed: true,
    };
  } finally {
    await stopChild(node);
    await stopChild(service);
  }
}

async function createLocalTls(directory) {
  const serverStore = join(directory, "service.p12");
  const certificate = join(directory, "service.crt");
  const trustStore = join(directory, "truststore.p12");
  const trustStorePasswordFile = join(directory, "truststore.password");
  const password = "local-e2e-changeit";
  runChecked(keytoolExecutable, [
    "-genkeypair", "-alias", "service", "-keyalg", "RSA", "-keysize", "2048",
    "-validity", "2", "-dname", "CN=localhost",
    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
    "-keystore", serverStore, "-storepass", password, "-keypass", password, "-noprompt",
  ]);
  runChecked(keytoolExecutable, [
    "-exportcert", "-alias", "service", "-keystore", serverStore,
    "-storepass", password, "-rfc", "-file", certificate,
  ]);
  runChecked(keytoolExecutable, [
    "-importcert", "-alias", "service", "-file", certificate, "-keystore", trustStore,
    "-storetype", "PKCS12", "-storepass", password, "-noprompt",
  ]);
  await writeFile(trustStorePasswordFile, password, "utf8");
  return { serverStore, trustStore, trustStorePasswordFile, password };
}

async function startService({ scenarioDirectory, serviceDatabase, port }) {
  const log = createWriteStream(join(scenarioDirectory, "service.log"), { flags: "a" });
  const child = spawn(javaExecutable, [
    "-jar", serviceJar,
    `--server.address=127.0.0.1`,
    `--server.port=${port}`,
    "--server.ssl.enabled=true",
    `--server.ssl.key-store=file:${toForwardSlashes(tls.serverStore)}`,
    "--server.ssl.key-store-type=PKCS12",
    `--server.ssl.key-store-password=${tls.password}`,
    `--workbench.security.node-tokens.${nodeId}=${nodeToken}`,
    "--workbench.node.offline-scan-interval=PT1H",
  ], {
    cwd: scenarioDirectory,
    env: {
      ...process.env,
      WORKBENCH_DATABASE_URL: `jdbc:sqlite:${toForwardSlashes(serviceDatabase)}`,
      WORKBENCH_CREATOR_TOKEN: creatorToken,
      WORKBENCH_HERMES_TOKEN: hermesToken,
      WORKBENCH_SITES_TOKEN: sitesToken,
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  child.once("exit", () => log.end());
  await waitFor(async () => {
    if (child.exitCode !== null) {
      throw new Error(`Service exited before becoming ready; inspect ${join(scenarioDirectory, "service.log")}`);
    }
    try {
      await requestJson(port, "/api/client/v2/nodes", { token: sitesToken });
      return true;
    } catch {
      return false;
    }
  }, 45_000, "Service HTTPS endpoint to become ready");
  return child;
}

async function startNode({
  scenarioDirectory,
  nodeState,
  workspace,
  port,
  scenario,
  turnDelay,
  runtimeKind = "fake-session",
  wsBaseUri,
  wsExpectedVersion,
  acceptanceProfile,
}) {
  const log = createWriteStream(join(scenarioDirectory, "node.log"), { flags: "a" });
  const nodeEnvironment = acceptanceProfile
    ? {
        ...process.env,
        SPRING_APPLICATION_JSON: JSON.stringify({
          workbench: {
            acceptance: {
              profiles: {
                [acceptanceProfile.profileId]: {
                  command: acceptanceProfile.command,
                  timeout: acceptanceProfile.timeout,
                  requiredArtifacts: acceptanceProfile.requiredArtifacts,
                  maxOutputBytes: acceptanceProfile.maxOutputBytes,
                },
              },
            },
          },
        }),
      }
    : process.env;
  const child = spawn(javaExecutable, [
    "-jar", nodeJar,
    `--workbench.node.node-id=${nodeId}`,
    "--workbench.node.display-name=E2E Node",
    `--workbench.node.service-base-uri=https://localhost:${port}/`,
    `--workbench.node.bearer-token=${nodeToken}`,
    `--workbench.node.state-directory=${nodeState}`,
    "--workbench.node.poll-interval=PT0.2S",
    "--workbench.node.heartbeat-interval=PT0.5S",
    "--workbench.node.request-timeout=PT3S",
    "--workbench.node.connect-timeout=PT2S",
    "--workbench.node.backoff-initial=PT0.2S",
    "--workbench.node.backoff-maximum=PT1S",
    `--workbench.node.runtime-kind=${runtimeKind}`,
    ...(runtimeKind === "ws" ? [
      `--workbench.node.ws-base-uri=${wsBaseUri}`,
      `--workbench.node.ws-expected-version=${wsExpectedVersion}`,
    ] : []),
    ...(runtimeKind === "fake-session" ? [
      `--workbench.node.fake-scenario=${scenario}`,
      `--workbench.node.fake-turn-delay=${turnDelay}`,
    ] : []),
    `--workbench.node.trust-store=${tls.trustStore}`,
    `--workbench.node.trust-store-password-file=${tls.trustStorePasswordFile}`,
    `--workbench.node.workspaces.${workspaceRef}=${workspace}`,
    `--workbench.context-registry.entries.context_main=${workspace}`,
    `--workbench.context-registry.allowed-roots[0]=${workspace}`,
  ], {
    cwd: scenarioDirectory,
    env: nodeEnvironment,
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stdout.pipe(log, { end: false });
  child.stderr.pipe(log, { end: false });
  child.once("exit", () => log.end());
  return child;
}

async function waitForNodeRegistration(port) {
  return waitFor(async () => {
    const nodes = await requestJson(port, "/api/client/v2/nodes", { token: sitesToken });
    return nodes.some((node) => node.nodeId === nodeId) ? nodes : null;
  }, 30_000, "Node registration");
}

async function createWorkItem(
  port,
  {
    idempotencyKey,
    title,
    runtimeKind = "fake-session",
    objective = "Complete one deterministic Mission using one local Agent Session",
    acceptanceSummary = "Independent deterministic fake acceptance checks pass",
    authorizedSideEffectsSummary = "Local temporary evidence only",
  },
) {
  return requestJson(port, "/api/client/v2/work-items", {
    token: creatorToken,
    method: "POST",
    expectedStatus: 201,
    headers: { "Idempotency-Key": idempotencyKey },
    body: {
      title,
      objective,
      acceptanceSummary,
      authorizedSideEffectsSummary,
      targetNodeId: nodeId,
      workspaceRef,
      runtimeKind,
      executionProfile: "spm-change-v1",
      priority: 0,
      dataBoundaryAcknowledged: true,
    },
  });
}

async function getWorkItem(port, workItemId) {
  return requestJson(port, `/api/client/v2/work-items/${workItemId}`, { token: sitesToken });
}

async function createTask(port, idempotencyKey, overrides = {}) {
  return requestJson(port, "/api/tasks/v1/tasks", {
    token: creatorToken,
    method: "POST",
    expectedStatus: 201,
    headers: { "Idempotency-Key": idempotencyKey },
    body: {
      title: overrides.title ?? "E2E Task business loop",
      objective: overrides.objective
        ?? "Complete a durable Task through two explicit fake Runtime executions",
      acceptanceSummary: overrides.acceptanceSummary
        ?? "Each successful Run enters REVIEW/PENDING and only a human action enters DONE",
      sideEffectSummary: overrides.sideEffectSummary
        ?? "Write local temporary evidence only; do not commit, push, deploy, or send messages",
      priority: 0,
      targetNodeId: nodeId,
      workspaceRef,
      contextRefs: [{ ref: "context_main", label: "Local E2E context" }],
      runtimeKind: overrides.runtimeKind ?? "fake-session",
      executionProfile: overrides.executionProfile ?? "spm-change-v1",
      dataBoundaryAcknowledged: true,
      actor: "e2e_user",
      reason: "Create a durable Task draft",
    },
  });
}

async function getTask(port, taskId) {
  return requestJson(port, `/api/tasks/v1/tasks/${taskId}`, { token: creatorToken });
}

async function taskAction(port, taskId, action, idempotencyKey, expectedVersion, reason) {
  return mutateTask(port, taskId, `/${action}`, "POST", idempotencyKey,
    actionBody(expectedVersion, reason));
}

async function mutateTask(port, taskId, suffix, method, idempotencyKey, body) {
  return requestJson(port, `/api/tasks/v1/tasks/${taskId}${suffix}`, {
    token: creatorToken,
    method,
    headers: { "Idempotency-Key": idempotencyKey },
    body,
  });
}

function actionBody(expectedVersion, reason) {
  return { expectedVersion, actor: "e2e_user", reason };
}

async function requestJson(port, path, {
  token,
  method = "GET",
  body,
  headers = {},
  expectedStatus = 200,
} = {}) {
  const response = await fetch(`https://localhost:${port}${path}`, {
    method,
    headers: {
      Accept: "application/json",
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(4_000),
  });
  const responseText = await response.text();
  if (response.status !== expectedStatus) {
    throw new Error(`${method} ${path} returned ${response.status}, expected ${expectedStatus}: ${responseText.slice(0, 800)}`);
  }
  return responseText ? JSON.parse(responseText) : null;
}

async function renderProductionWeb(port, path) {
  const previous = {
    baseUrl: process.env.WORKBENCH_SERVICE_BASE_URL,
    readToken: process.env.WORKBENCH_SERVICE_READ_TOKEN,
    writeToken: process.env.WORKBENCH_SERVICE_WRITE_TOKEN,
    timeout: process.env.WORKBENCH_SERVICE_TIMEOUT_MS,
  };
  process.env.WORKBENCH_SERVICE_BASE_URL = `https://localhost:${port}`;
  process.env.WORKBENCH_SERVICE_READ_TOKEN = sitesToken;
  process.env.WORKBENCH_SERVICE_WRITE_TOKEN = creatorToken;
  process.env.WORKBENCH_SERVICE_TIMEOUT_MS = "5000";
  try {
    const workerUrl = pathToFileURL(webWorker);
    workerUrl.searchParams.set("e2e", `${Date.now()}-${Math.random()}`);
    const { default: worker } = await import(workerUrl.href);
    const response = await worker.fetch(new Request(`http://localhost${path}`, {
      headers: {
        Accept: "text/html",
        "oai-authenticated-user-id": "e2e-user",
        "oai-authenticated-user-email": "owner@example.com",
      },
    }), {
      ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) },
    }, {
      waitUntil() {},
      passThroughOnException() {},
    });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("cache-control"), "no-store");
    return response.text();
  } finally {
    restoreEnvironment("WORKBENCH_SERVICE_BASE_URL", previous.baseUrl);
    restoreEnvironment("WORKBENCH_SERVICE_READ_TOKEN", previous.readToken);
    restoreEnvironment("WORKBENCH_SERVICE_WRITE_TOKEN", previous.writeToken);
    restoreEnvironment("WORKBENCH_SERVICE_TIMEOUT_MS", previous.timeout);
  }
}

async function assertServiceContainsNoLocalEvidence(serviceState, forbiddenValues) {
  const stateFiles = (await readdir(serviceState)).filter((name) => name.startsWith("service.db"));
  const combined = Buffer.concat(await Promise.all(stateFiles.map((name) => readFile(join(serviceState, name))))).toString("latin1");
  for (const forbiddenValue of forbiddenValues) {
    assert.equal(combined.includes(forbiddenValue), false, `Service state leaked local evidence: ${forbiddenValue}`);
  }
}

function querySqlite(database, sql) {
  const script = [
    "import sqlite3,sys",
    "connection=sqlite3.connect(sys.argv[1], timeout=5)",
    "row=connection.execute(sys.argv[2]).fetchone()",
    "print('' if row is None or row[0] is None else row[0])",
    "connection.close()",
  ].join(";");
  const result = runChecked("python", ["-c", script, database, sql]);
  return result.stdout.trim();
}

async function waitFor(action, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await action();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await sleep(200);
  }
  throw new Error(`Timed out waiting for ${label}${lastError ? `: ${lastError.message}` : ""}`);
}

function resolveRealWsObservationMs(developmentMode) {
  const defaultTimeoutMs = developmentMode ? 1_800_000 : 120_000;
  const maximumTimeoutMs = developmentMode ? 3_600_000 : 600_000;
  const value = Number.parseInt(
    process.env.WORKBENCH_REAL_WS_TIMEOUT_MS || String(defaultTimeoutMs),
    10,
  );
  assert.ok(
    Number.isSafeInteger(value) && value >= 30_000 && value <= maximumTimeoutMs,
    `WORKBENCH_REAL_WS_TIMEOUT_MS must be an integer from 30000 to ${maximumTimeoutMs}`,
  );
  return value;
}

function resolveRealWsScenario(developmentMode) {
  if (!developmentMode) {
    return {
      workspace: null,
      title: "Real WS shipment-count calculation",
      objective: "Given shipment counts 17, 23, and 40, calculate the item count, total, and arithmetic mean, then verify the arithmetic",
      acceptanceSummary: "The verified result states count=3, total=80, and arithmetic mean=26.6666666667 (approximately 26.67)",
      authorizedSideEffectsSummary: "No tools, file changes, network requests, or external side effects",
      executionProfile: "spm-change-v1",
      acceptanceProfile: null,
    };
  }
  const required = (name) => {
    const value = process.env[name]?.trim();
    assert.ok(value, `${name} is required for --real-ws-development`);
    return value;
  };
  const acceptanceCommand = parseStringArray(
    "WORKBENCH_REAL_WS_ACCEPTANCE_COMMAND_JSON",
    required("WORKBENCH_REAL_WS_ACCEPTANCE_COMMAND_JSON"),
    false,
  );
  const requiredArtifacts = parseStringArray(
    "WORKBENCH_REAL_WS_REQUIRED_ARTIFACTS_JSON",
    process.env.WORKBENCH_REAL_WS_REQUIRED_ARTIFACTS_JSON || "[]",
    true,
  );
  const executionProfile = process.env.WORKBENCH_REAL_WS_ACCEPTANCE_PROFILE?.trim()
    || "trusted-local-e2e-v1";
  const maxOutputBytes = Number.parseInt(
    process.env.WORKBENCH_REAL_WS_ACCEPTANCE_MAX_OUTPUT_BYTES || "262144",
    10,
  );
  assert.ok(Number.isSafeInteger(maxOutputBytes) && maxOutputBytes >= 1024 && maxOutputBytes <= 1_048_576,
    "WORKBENCH_REAL_WS_ACCEPTANCE_MAX_OUTPUT_BYTES must be from 1024 to 1048576");
  return {
    workspace: resolve(required("WORKBENCH_REAL_WS_WORKSPACE")),
    title: required("WORKBENCH_REAL_WS_TITLE"),
    objective: required("WORKBENCH_REAL_WS_OBJECTIVE"),
    acceptanceSummary: required("WORKBENCH_REAL_WS_ACCEPTANCE"),
    authorizedSideEffectsSummary: required("WORKBENCH_REAL_WS_AUTHORIZED_SIDE_EFFECTS"),
    executionProfile,
    acceptanceProfile: {
      profileId: executionProfile,
      command: acceptanceCommand,
      timeout: process.env.WORKBENCH_REAL_WS_ACCEPTANCE_TIMEOUT?.trim() || "PT10M",
      requiredArtifacts,
      maxOutputBytes,
    },
    autoApproveInteractions:
      process.env.WORKBENCH_REAL_WS_AUTO_APPROVE_INTERACTIONS?.trim().toLowerCase() === "true",
  };
}

function parseStringArray(name, value, allowEmpty) {
  let parsed;
  try {
    parsed = JSON.parse(value);
  } catch (error) {
    throw new Error(`${name} must be a JSON string array`, { cause: error });
  }
  assert.ok(Array.isArray(parsed) && (allowEmpty || parsed.length > 0)
    && parsed.every((entry) => typeof entry === "string" && entry.trim()),
  `${name} must be ${allowEmpty ? "a" : "a non-empty"} JSON string array`);
  return parsed;
}

async function readJson(path) {
  const contents = await readFile(path, "utf8").catch(() => null);
  return contents ? JSON.parse(contents) : null;
}

function requiredEnvironment(name) {
  const value = process.env[name]?.trim();
  assert.ok(value, `${name} is required`);
  return value;
}

async function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  if (process.platform === "win32") {
    spawnSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore" });
    await Promise.race([
      new Promise((resolveExit) => child.once("exit", resolveExit)),
      sleep(5_000),
    ]);
    return;
  }
  child.kill();
  await Promise.race([
    new Promise((resolveExit) => child.once("exit", resolveExit)),
    sleep(5_000),
  ]);
}

async function reservePort() {
  const server = createServer();
  await new Promise((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolveListen);
  });
  const address = server.address();
  assert.ok(address && typeof address === "object");
  await new Promise((resolveClose, reject) => server.close((error) => error ? reject(error) : resolveClose()));
  return address.port;
}

function runChecked(command, args) {
  const result = spawnSync(command, args, { encoding: "utf8", windowsHide: true });
  if (result.status !== 0) {
    throw new Error(`${basename(command)} failed with ${result.status}: ${result.stderr || result.stdout}`);
  }
  return result;
}

function javaVersionLine(stderr) {
  const versionLine = stderr.split(/\r?\n/).find((line) => line.includes(" version "));
  assert.ok(versionLine, "java -version did not report a version line");
  return versionLine;
}

function toForwardSlashes(path) {
  return path.replaceAll("\\", "/");
}

function restoreEnvironment(name, previousValue) {
  if (previousValue === undefined) delete process.env[name];
  else process.env[name] = previousValue;
}

function sleep(milliseconds) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, milliseconds));
}
