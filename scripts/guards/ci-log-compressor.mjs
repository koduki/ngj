#!/usr/bin/env node
/**
 * LogSage-inspired CI failure log preprocessor for NGJ (ASE 2025 pattern).
 *
 * Strips timestamps, ANSI escapes, and download/progress noise from raw CI logs,
 * classifies the failure category, and emits a bounded structured JSON or Markdown
 * triage report so coding agents never consume raw multi-megabyte logs.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ANSI_RE = /\x1B\[[0-9;]*[A-Za-z]/g;
const GHA_TS_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s+/;
const NOISE_LINE_RE = /^(Download https?:\/\/|> Task :(?:compiler:)?\S+\s+UP-TO-DATE|Calculating task graph|Configuration cache|^\s*\d+%\s+|^\s*#\s+duration_ms|^\s*ok\s+\d+\s+-)/i;

const ERROR_LINE_RE = /(GUARD FAILED|FAIL:|ERROR:|::error::|FAILURE:|Execution failed for task|AssertionError|AssertionFailedError|Exception:|Error:|not ok \d+|error:\s+|SyntaxError:|TypeError:|ReferenceError:|FAILED \(|Traceback \(most recent call last\)|cannot find symbol|incompatible types|Cannot find a Java installation|UnsupportedClassVersionError|Preview features are not enabled)/i;

const CATEGORIES = [
  { id: "guard_violation", re: /GUARD FAILED|\[no-lint-or-type-bypass\]|\[no-test-skip-or-ignore\]|\[no-new-java-suppress\]|\[valhalla-and-build-invariants\]|\[no-compiler-llm-dependency\]|\[custom-subagent-frontmatter\]|\[oracle-test-integrity\]/i },
  { id: "valhalla_jdk_check", re: /UnsupportedClassVersionError|Preview features are not enabled|\bTARGET-02:|\brequires\s+JDK\s*28\b|Valhalla\s+JDK[^\n]*(?:required|missing|not found|failed|error)/i },
  { id: "gradle_toolchain", re: /Cannot find a Java installation[^\n]*matching:|Toolchain download repositories have not been configured/i },
  { id: "gradle_compile", re: /compile(?:Test)?Java|cannot find symbol|incompatible types|\.java:\d+:\s*error:/i },
  { id: "gradle_test", re: /:compiler:test\b|AssertionFailedError|AssertionError|\bTest\s*>\s*\S+\s+FAILED/i },
  { id: "docker_build", re: /ERROR\s+\[[^\]]+\]|failed to solve:|docker build[^\n]*failed/i },
  { id: "node_test", re: /not ok \d+|tests\/.*\.test\.cjs|node --check/i },
  { id: "python_test", re: /test_.*_oracle\.py|Traceback \(most recent call last\)/i },
];

function classifyLines(lines) {
  for (const c of CATEGORIES) {
    if (lines.some((line) => c.re.test(line))) {
      return c.id;
    }
  }
  return "unknown";
}

export function compressCiLog(rawText, options = {}) {
  const { maxSnippets = 45, maxTailLines = 25, maxChars = 6000 } = options;
  const cleaned = rawText
    .replace(ANSI_RE, "")
    .split(/\r?\n/)
    .map((line) => line.replace(GHA_TS_RE, "").trimEnd())
    .filter((line) => line.length > 0 && !NOISE_LINE_RE.test(line));

  const matchedIndexes = new Set();
  for (let i = 0; i < cleaned.length; i++) {
    if (ERROR_LINE_RE.test(cleaned[i])) {
      for (let j = Math.max(0, i - 1); j <= Math.min(cleaned.length - 1, i + 2); j++) {
        matchedIndexes.add(j);
      }
    }
  }

  const keyLines = Array.from(matchedIndexes)
    .sort((a, b) => a - b)
    .map((idx) => cleaned[idx]);

  // Deduplicate adjacent identical lines and enforce strict token budget.
  const deduped = [];
  for (const line of keyLines) {
    if (deduped[deduped.length - 1] !== line) {
      deduped.push(line);
    }
    if (deduped.length >= maxSnippets) break;
  }

  const tailLines = cleaned.slice(-maxTailLines);

  // Two-phase classification: prioritize focused error & tail lines, fall back to full cleaned log only if unknown.
  let category = classifyLines([...deduped, ...tailLines]);
  if (category === "unknown") {
    category = classifyLines(cleaned);
  }

  // Extract candidate repository file paths mentioned in error lines.
  const fileSet = new Set();
  const pathRe = /\b((?:compiler|scripts|tests|docs|examples|\.github|\.agents)\/[A-Za-z0-9_./-]+\.[A-Za-z0-9]+)(?::(\d+))?/g;
  for (const line of [...deduped, ...tailLines]) {
    let m;
    while ((m = pathRe.exec(line)) !== null) {
      fileSet.add(m[2] ? `${m[1]}:${m[2]}` : m[1]);
    }
  }

  let report = {
    schema: 1,
    category,
    rawLineCount: rawText.split(/\r?\n/).length,
    filteredLineCount: cleaned.length,
    suspectedFiles: Array.from(fileSet).slice(0, 15),
    keyErrorLines: deduped,
    tailLines,
  };

  // Hard cap serialized size.
  while (JSON.stringify(report).length > maxChars && report.keyErrorLines.length > 5) {
    report.keyErrorLines.pop();
  }
  while (JSON.stringify(report).length > maxChars && report.tailLines.length > 5) {
    report.tailLines.shift();
  }

  return report;
}

export function formatMarkdown(report) {
  const lines = [
    "### CI Failure Triage (Compressed / LogSage-style)",
    "",
    `- **Category:** \`${report.category}\``,
    `- **Log compression:** ${report.rawLineCount} raw lines -> ${report.keyErrorLines.length} key error lines`,
    `- **Suspected files:** ${report.suspectedFiles.length ? report.suspectedFiles.map((f) => `\`${f}\``).join(", ") : "_none detected_"}`,
    "",
    "#### Key Error Lines",
    "```text",
    ...(report.keyErrorLines.length ? report.keyErrorLines : ["(no explicit error keyword matched; see tail below)"]),
    "```",
    "",
    "#### Log Tail",
    "```text",
    ...report.tailLines,
    "```",
  ];
  return lines.join("\n");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const asJson = args.includes("--json");
  const fileArg = args.find((a) => !a.startsWith("-"));
  const raw = fileArg ? fs.readFileSync(fileArg, "utf8") : fs.readFileSync(0, "utf8");
  const report = compressCiLog(raw);
  console.log(asJson ? JSON.stringify(report, null, 2) : formatMarkdown(report));
}
