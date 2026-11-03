import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const ts = require("typescript");

function loadGraphOutputModule() {
  const sourcePath = path.resolve("src/components/graph/graph-output.ts");
  const source = fs.readFileSync(sourcePath, "utf8");

  const { outputText } = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
    },
  });

  const module = { exports: {} };

  vm.runInNewContext(outputText, {
    exports: module.exports,
    module,
  });

  return module.exports;
}

test("preserves legacy string message content", () => {
  const { extractTextContent } = loadGraphOutputModule();

  assert.equal(extractTextContent("Hello ARGI"), "Hello ARGI");
});

test("extracts text from structured content parts", () => {
  const { extractTextContent } = loadGraphOutputModule();
  const content = [
    { type: "TEXT", text: "Hello " },
    { type: "TEXT", text: "ARGI" },
  ];

  assert.equal(extractTextContent(content), "Hello ARGI");
});

test("uses textual state result when no message text exists", () => {
  const { extractTextFromState } = loadGraphOutputModule();
  const state = { result: "echo: hello" };

  assert.equal(extractTextFromState(state), "echo: hello");
});

test("uses state result when messages contain only user content", () => {
  const { extractTextFromState } = loadGraphOutputModule();
  const state = {
    result: "echo: hello",
    messages: [
      {
        role: "USER",
        content: [{ type: "TEXT", text: "hello" }],
      },
    ],
  };

  assert.equal(extractTextFromState(state), "echo: hello");
});
