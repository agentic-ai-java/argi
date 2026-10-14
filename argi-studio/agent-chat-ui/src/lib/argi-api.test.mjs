import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const ts = require("typescript");

function loadApiModule() {
  const sourcePath = path.resolve("src/lib/argi-api.ts");
  const source = fs.readFileSync(sourcePath, "utf8");
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
      esModuleInterop: true,
    },
  });
  const module = { exports: {} };
  vm.runInNewContext(outputText, {
    AbortController,
    Headers,
    Response,
    TextDecoder,
    console,
    exports: module.exports,
    fetch: (...args) => globalThis.fetch(...args),
    module,
    require,
    window: globalThis.window,
  });
  return module.exports;
}

function installSessionStorage(entries = {}) {
  const values = new Map(Object.entries(entries));
  globalThis.window = {
    sessionStorage: {
      getItem: (key) => values.get(key) ?? null,
      setItem: (key, value) => values.set(key, value),
      removeItem: (key) => values.delete(key),
    },
  };
}

function installFetchRecorder(responseBody = {}) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url, init });
    return new Response(JSON.stringify(responseBody), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };
  return calls;
}

function streamResponse(payload) {
  return new Response(
    new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode(payload));
        controller.close();
      },
    }),
    {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    },
  );
}

async function drain(generator) {
  const values = [];
  for await (const value of generator) {
    values.push(value);
  }
  return values;
}

test("thread CRUD requests send the Studio execution token", async () => {
  installSessionStorage({ "agentic:studio:executionAuthToken": "secret" });
  const api = loadApiModule();
  const calls = installFetchRecorder({ thread_id: "thread-1", values: {} });
  const client = new api.default("http://studio");

  await client.listSessions("assistant", "alice");
  await client.getSession("assistant", "alice", "thread-1");
  await client.createSession("assistant", "alice", {});
  await client.createSessionWithId("assistant", "alice", "thread-1", {});
  await client.deleteSession("assistant", "alice", "thread-1");
  await client.listGraphSessions("research", "alice");
  await client.getGraphSession("research", "alice", "thread-1");
  await client.createGraphSession("research", "alice", {});
  await client.createGraphSessionWithId("research", "alice", "thread-1", {});
  await client.deleteGraphSession("research", "alice", "thread-1");

  assert.equal(calls.length, 10);
  for (const call of calls) {
    assert.equal(
      new Headers(call.init.headers).get(api.STUDIO_EXECUTION_AUTH_HEADER),
      "secret",
    );
  }
});

test("Studio discovery requests send the execution token", async () => {
  installSessionStorage({ "agentic:studio:executionAuthToken": "secret" });
  const api = loadApiModule();
  const calls = installFetchRecorder([]);
  const client = new api.default("http://studio");

  await client.listApps();
  await client.listGraphs();
  await client.getGraphRepresentation("research");

  assert.equal(calls.length, 3);
  for (const call of calls) {
    assert.equal(
      new Headers(call.init.headers).get(api.STUDIO_EXECUTION_AUTH_HEADER),
      "secret",
    );
  }
});

test("agent SSE error events are surfaced to callers", async () => {
  installSessionStorage();
  const api = loadApiModule();
  const client = new api.default("http://studio");
  const response = streamResponse(
    'event: error\ndata: {"error":true,"errorMessage":"boom"}\n\n',
  );

  await assert.rejects(() => drain(client._processSSEStream(response)), /boom/);
});

test("graph SSE error events are surfaced to callers", async () => {
  installSessionStorage();
  const api = loadApiModule();
  const client = new api.default("http://studio");
  const response = streamResponse(
    'event: error\ndata: {"error":true,"errorMessage":"graph failed"}\n\n',
  );

  await assert.rejects(
    () => drain(client._processGraphSSEStream(response)),
    /graph failed/,
  );
});

test("Studio settings are reachable from agent and graph workspaces", () => {
  const threadSource = fs.readFileSync(
    path.resolve("src/components/thread/index.tsx"),
    "utf8",
  );
  const graphSource = fs.readFileSync(
    path.resolve("src/components/graph/GraphWorkspace.tsx"),
    "utf8",
  );

  assert.match(threadSource, /StudioSettingsButton/);
  assert.match(graphSource, /StudioSettingsButton/);
});
