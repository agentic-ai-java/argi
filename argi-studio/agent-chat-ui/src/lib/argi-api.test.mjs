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

function chunkedStreamResponse(chunks) {
  return new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(new TextEncoder().encode(chunk));
        }
        controller.close();
      },
    }),
    {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    },
  );
}

const TRICKY_MESSAGE =
  'quote " backslash \\ path C:\\temp\\new tab\tcr\rlf\n unicode 中文 ✓';

for (const [label, method] of [
  ["agent", "_processSSEStream"],
  ["graph", "_processGraphSSEStream"],
]) {
  test(`${label} SSE error with special characters round-trips`, async () => {
    installSessionStorage();
    const api = loadApiModule();
    const client = new api.default("http://studio");
    const data = JSON.stringify({
      error: true,
      errorType: "IllegalStateException",
      errorMessage: TRICKY_MESSAGE,
    });
    const response = streamResponse(`event:error\ndata:${data}\n\n`);

    await assert.rejects(
      () => drain(client[method](response)),
      (error) => error.message === TRICKY_MESSAGE,
    );
  });

  test(`${label} SSE malformed error event fails instead of ending normally`, async () => {
    installSessionStorage();
    const api = loadApiModule();
    const client = new api.default("http://studio");
    const response = streamResponse(
      'data:{"node":"a"}\n\nevent:error\ndata:{"error":true,"errorMessage":"bad\tjson\n\n',
    );

    await assert.rejects(
      () => drain(client[method](response)),
      /malformed error event/,
    );
  });

  test(`${label} SSE skips malformed data but keeps empty and normal events`, async () => {
    installSessionStorage();
    const api = loadApiModule();
    const client = new api.default("http://studio");
    const response = streamResponse(
      'data:{not json}\n\ndata:{}\n\ndata:{"node":"a"}\n\n',
    );

    const values = await drain(client[method](response));
    assert.deepEqual(JSON.parse(JSON.stringify(values)), [{}, { node: "a" }]);
  });

  test(`${label} SSE parses events split across network chunks`, async () => {
    installSessionStorage();
    const api = loadApiModule();
    const client = new api.default("http://studio");
    const data = JSON.stringify({ error: true, errorMessage: TRICKY_MESSAGE });
    const payload = `data:{"node":"a"}\r\n\r\nevent:error\r\ndata:${data}\r\n\r\n`;
    const bytes = new TextEncoder().encode(payload);
    const chunks = [];
    // Split into 3-byte slices to also cut through multi-byte UTF-8 characters.
    for (let i = 0; i < bytes.length; i += 3) {
      chunks.push(bytes.slice(i, i + 3));
    }
    const response = new Response(
      new ReadableStream({
        start(controller) {
          for (const chunk of chunks) {
            controller.enqueue(chunk);
          }
          controller.close();
        },
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } },
    );

    const seen = [];
    await assert.rejects(
      async () => {
        for await (const value of client[method](response)) {
          seen.push(value);
        }
      },
      (error) => error.message === TRICKY_MESSAGE,
    );
    assert.deepEqual(JSON.parse(JSON.stringify(seen)), [{ node: "a" }]);
  });

  test(`${label} SSE yields a final event without trailing newline`, async () => {
    installSessionStorage();
    const api = loadApiModule();
    const client = new api.default("http://studio");
    const response = chunkedStreamResponse(['data:{"node"', ':"last"}']);

    const values = await drain(client[method](response));
    assert.deepEqual(JSON.parse(JSON.stringify(values)), [{ node: "last" }]);
  });
}
