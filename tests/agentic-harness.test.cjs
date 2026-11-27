'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');

const execFileAsync = promisify(execFile);
const REPO_ROOT = path.resolve(__dirname, '..');

function makeTempDir(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

test('ship.config.json defines cloud-first Remote Agents, 1-gate, one-by-one grilling, Clean-Room Oracle, and Codex 10-min policy for NGJ', async () => {
  const { loadShipConfig } = await import('../.agents/skills/ship/scripts/state.mjs');
  const cfg = loadShipConfig(REPO_ROOT);
  assert.equal(cfg.project.name, 'NGJ');
  assert.equal(cfg.project.repository, 'koduki/ngj');
  assert.equal(cfg.execution.primaryEnvironment, 'remote-agent');
  assert.equal(cfg.execution.subagentWorkspaceMode, 'inherit');
  assert.deepEqual(cfg.execution.agents, {
    recon: 'ship-recon',
    architect: 'ship-architect',
    oracleAuthor: 'oracle-test-author',
    implementer: 'ship-implementer',
    verifier: 'ship-verifier',
    reviewer: 'ship-reviewer',
  });
  assert.deepEqual(cfg.execution.models, {
    orchestrator: 'inherit',
    recon: 'flash',
    architect: 'pro',
    oracleAuthor: 'pro',
    implementer: 'inherit',
    verifier: 'flash',
    reviewer: 'pro',
  });
  assert.deepEqual(cfg.execution.implementerTiers, {
    standard: 'flash',
    complex: 'inherit',
  });
  assert.ok(cfg.boundaries.protectedPatterns.includes('(^|/)\\.agents/agents/'));
  assert.ok(cfg.boundaries.oraclePatterns.includes('OracleTest\\.java$'));
  assert.equal(cfg.grilling.mode, 'one-by-one');
  assert.equal(cfg.grilling.autoSkipWhenClear, true);
  assert.equal(cfg.grilling.maxQuestions, 20);
  assert.deepEqual(cfg.humanGates, ['S2_CONTRACT_LOCK']);
  assert.equal(cfg.oracleTesting.enabled, true);
  assert.equal(cfg.oracleTesting.requireSeparateAgentProcess, true);
  assert.equal(cfg.oracleTesting.authorBeforeImplementation, true);
  assert.equal(cfg.oracleTesting.forbidReadingImplementationDiff, true);
  assert.equal(cfg.oracleTesting.lockOracleDuringImplementation, true);
  assert.equal(cfg.codexReview.primarySurface, 'github-app');
  assert.equal(cfg.codexReview.timeoutSeconds, 600);
  assert.equal(cfg.codexReview.runningGraceSeconds, 300);
  assert.equal(cfg.codexReview.maxTotalTimeoutSeconds, 900);
  assert.equal(cfg.codexReview.onTimeoutOrQuota, 'escalate');
  assert.equal(cfg.ciSelfHeal.track, 'subscription-only');
  assert.equal(cfg.ciSelfHeal.microFixMaxLines, 50);
});

test('run-all-guards passes cleanly on the live NGJ repository', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');
  const report = runGuards(REPO_ROOT, { changedOnly: false });
  assert.equal(report.passed, true, `Unexpected guard violations: ${JSON.stringify(report.violations, null, 2)}`);
  assert.ok(report.checkedFiles > 20);
});

test('run-all-guards blocks suppressions, skips, Java suppressions, Valhalla/build drift, LLM dependencies, secrets, invalid JSON, and weak oracle tests', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');
  const tmp = makeTempDir('ngj-guard-test-');
  try {
    const write = (rel, content) => {
      const full = path.join(tmp, rel);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content, 'utf8');
    };

    write('scripts/bad.mjs', '// eslint-disable-next-line\nconst x = 1;\n');
    write('tests/bad.test.cjs', 'test.skip("weakened", () => {});\n');
    write('compiler/src/test/java/ngj/BadTest.java', '@Disabled\nclass BadTest {}\n');
    write('tests/oracle/empty.oracle.test.cjs', 'const test = require("node:test"); test("no ac or assert", () => {});\n');
    write(
      'compiler/src/main/java/ngj/Foo.java',
      '@SuppressWarnings("rawtypes")\nclass Foo { String url = "https://api.openai.com/v1/chat"; String tok = "ghp_1234567890abcdefghijklmno"; }\n',
    );
    write('build.gradle.kts', 'val VALHALLA_JDK_VERSION = 21\n');
    write('compiler/build.gradle.kts', 'plugins { id("application") }\n');
    write('Dockerfile', 'FROM debian:bookworm-slim\nENTRYPOINT ["java", "-jar", "/app/ngjc.jar"]\n');
    write('.agents/hooks.json', '{ invalid-json }\n');
    write('release.keystore', 'dummy');

    const report = runGuards(tmp, { changedOnly: false });
    assert.equal(report.passed, false);
    const ids = new Set(report.violations.map((v) => v.ruleId));

    for (const expected of [
      'no-lint-or-type-bypass',
      'no-test-skip-or-ignore',
      'no-new-java-suppress',
      'no-compiler-llm-dependency',
      'valhalla-and-build-invariants',
      'no-secret-or-keystore-commit',
      'oracle-test-integrity',
      'agents-config-json-validity',
    ]) {
      assert.ok(ids.has(expected), `Expected violation ${expected}, got: ${Array.from(ids).join(', ')}`);
    }
    assert.ok(report.violations.every((v) => v.remediation && v.remediation.length > 10));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('custom-subagent-frontmatter guard validates .agents/agents/*.md allowlists, model tiers, and read-only enforcement', async () => {
  const { runGuards } = await import('../scripts/guards/run-all-guards.mjs');

  const liveAgentsDir = path.join(REPO_ROOT, '.agents', 'agents');
  const liveAgentFiles = fs.readdirSync(liveAgentsDir).filter((f) => f.endsWith('.md')).sort();
  assert.deepEqual(liveAgentFiles, [
    'oracle-test-author.md',
    'ship-architect.md',
    'ship-implementer.md',
    'ship-recon.md',
    'ship-reviewer.md',
    'ship-verifier.md',
  ]);

  const tmp = makeTempDir('ngj-subagent-guard-');
  try {
    const write = (rel, content) => {
      const full = path.join(tmp, rel);
      fs.mkdirSync(path.dirname(full), { recursive: true });
      fs.writeFileSync(full, content, 'utf8');
    };

    write('.agents/agents/bad-tool.md', [
      '---',
      'name: bad-tool',
      'description: Agent with unmapped tool',
      'tools:',
      '  - view_file',
      '  - read_file_typo',
      'subagent: true',
      'model: flash',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    write('.agents/agents/bad-model.md', [
      '---',
      'name: bad-model',
      'description: Agent with invalid model tier',
      'tools: [view_file, send_message]',
      'subagent: true',
      'model: gpt-99',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    write('.agents/agents/ship-verifier.md', [
      '---',
      'name: ship-verifier',
      'description: Read-only verifier with forbidden write tool',
      'tools:',
      '  - view_file',
      '  - write_to_file',
      '  - run_command',
      'subagent: true',
      'model: flash',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    write('.agents/agents/ship-reviewer.md', [
      '---',
      'name: ship-reviewer',
      'description: Read-only reviewer with forbidden write tool',
      'tools:',
      '  - view_file',
      '  - write_to_file',
      '  - run_command',
      'subagent: true',
      'model: pro',
      'commandExecutionPolicy: sandbox',
      '---',
      '# Prompt',
    ].join('\n'));

    const report = runGuards(tmp, { changedOnly: false });
    assert.equal(report.passed, false);
    const guardViolations = report.violations.filter((v) => v.ruleId === 'custom-subagent-frontmatter');
    assert.equal(guardViolations.length, 4, `Expected 4 subagent violations, got: ${JSON.stringify(guardViolations, null, 2)}`);
    assert.ok(guardViolations.some((v) => v.file.endsWith('bad-tool.md') && /read_file_typo/.test(v.detail)));
    assert.ok(guardViolations.some((v) => v.file.endsWith('bad-model.md') && /gpt-99/.test(v.detail)));
    assert.ok(guardViolations.some((v) => v.file.endsWith('ship-verifier.md') && /write_to_file/.test(v.detail)));
    assert.ok(guardViolations.some((v) => v.file.endsWith('ship-reviewer.md') && /write_to_file/.test(v.detail)));
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('guard-boundaries enforces two-way Clean-Room Oracle isolation, S2 allowedProtectedPaths, and never-unlockable secrets', async () => {
  const { evaluateBoundaryWrite } = await import('../.agents/skills/ship/scripts/guard-boundaries.mjs');
  const { loadShipConfig } = await import('../.agents/skills/ship/scripts/state.mjs');
  const config = loadShipConfig(REPO_ROOT);

  assert.equal(evaluateBoundaryWrite('/repo/.github/workflows/ci.yml', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/Dockerfile', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/scripts/guards/run-all-guards.mjs', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/.agents/ship.config.json', { config }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/.agents/agents/ship-verifier.md', { config }).decision, 'deny');
  assert.equal(
    evaluateBoundaryWrite('/repo/.agents/agents/ship-verifier.md', {
      config,
      allowedProtectedPaths: ['.agents/agents/ship-verifier.md'],
    }).decision,
    'allow',
  );

  // Explicit S2 approval unlocks only the approved protected path
  assert.equal(
    evaluateBoundaryWrite('/repo/Dockerfile', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      allowedProtectedPaths: ['Dockerfile'],
    }).decision,
    'allow',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/.github/workflows/ci.yml', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      allowedProtectedPaths: ['Dockerfile'],
    }).decision,
    'deny',
  );

  // Secrets & signing files are NEVER unlockable even if listed in allowedProtectedPaths
  assert.equal(
    evaluateBoundaryWrite('/repo/.env.production', {
      config,
      allowedProtectedPaths: ['.env.production'],
    }).decision,
    'deny',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/release.keystore', {
      config,
      allowedProtectedPaths: ['release.keystore'],
    }).decision,
    'deny',
  );

  // Two-way Clean-Room Oracle Isolation:
  // 1) During S3a_ORACLE_TEST_GEN: ONLY oracle test files may be written; production code is blocked!
  assert.equal(
    evaluateBoundaryWrite('/repo/compiler/src/test/java/ngj/oracle/ValhallaOracleTest.java', {
      config,
      currentState: 'S3a_ORACLE_TEST_GEN',
      contractLocked: true,
      oracleLocked: false,
    }).decision,
    'allow',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/tests/oracle/cli-contract.oracle.test.cjs', {
      config,
      currentState: 'S3a_ORACLE_TEST_GEN',
      contractLocked: true,
      oracleLocked: false,
    }).decision,
    'allow',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/compiler/src/main/java/ngj/typechecker/TypeChecker.java', {
      config,
      currentState: 'S3a_ORACLE_TEST_GEN',
      contractLocked: true,
      oracleLocked: false,
    }).decision,
    'deny',
  );

  // 2) After lock-oracle (S3b onward): production code is allowed, but ALL oracle tests are hard-locked!
  assert.equal(
    evaluateBoundaryWrite('/repo/compiler/src/test/java/ngj/oracle/ValhallaOracleTest.java', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      oracleTestFiles: ['compiler/src/test/java/ngj/oracle/ValhallaOracleTest.java'],
    }).decision,
    'deny',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/tests/oracle/cli-contract.oracle.test.cjs', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
    }).decision,
    'deny',
  );
  assert.equal(
    evaluateBoundaryWrite('/repo/compiler/src/main/java/ngj/typechecker/TypeChecker.java', {
      config,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
    }).decision,
    'allow',
  );

  // Contracts editable before S2 approval, locked after
  assert.equal(evaluateBoundaryWrite('/repo/docs/specs/new-feat/spec.md', { config, contractLocked: false }).decision, 'allow');
  assert.equal(evaluateBoundaryWrite('/repo/docs/specs/new-feat/spec.md', { config, contractLocked: true }).decision, 'deny');
  assert.equal(evaluateBoundaryWrite('/repo/docs/SPECIFICATION.md', { config, contractLocked: true }).decision, 'deny');
});

test('guard-commands blocks hook bypass, force push, destructive reset/clean, root rm, and secret prints', async () => {
  const { evaluateCommand } = await import('../.agents/skills/ship/scripts/guard-commands.mjs');

  assert.equal(evaluateCommand('git commit -m "x" --no-verify').decision, 'deny');
  assert.equal(evaluateCommand('git push --force origin feat/x').decision, 'deny');
  assert.equal(evaluateCommand('git reset --hard HEAD~1').decision, 'deny');
  assert.equal(evaluateCommand('rm -rf /').decision, 'deny');
  assert.equal(evaluateCommand('gh auth token').decision, 'deny');
  assert.equal(evaluateCommand('printenv GH_TOKEN').decision, 'deny');

  assert.equal(evaluateCommand('NGJ_BUILD_JDK_VERSION=25 ./gradlew test').decision, 'allow');
  assert.equal(evaluateCommand('node --test tests/agentic-harness.test.cjs').decision, 'allow');
});

test('/ship state machine enforces S3a oracle-before-impl lock, --oracle-passed, CI+Codex preconditions, retry caps, resume, and Stop hook', async () => {
  const stateMod = await import('../.agents/skills/ship/scripts/state.mjs');
  const stopMod = await import('../.agents/skills/ship/scripts/stop-driver.mjs');
  const sessionInitMod = await import('../.agents/skills/ship/scripts/session-init.mjs');
  const tmp = makeTempDir('ngj-ship-state-');
  const statePath = path.join(tmp, '.ship', 'runs', 'feat_demo.json');
  const stateCli = path.join(REPO_ROOT, '.agents', 'skills', 'ship', 'scripts', 'state.mjs');

  try {
    stateMod.initRun(statePath, 'Implement check-patch CLI command', { cwd: REPO_ROOT });
    assert.throws(() => stateMod.transitionState(statePath, 'S3a_ORACLE_TEST_GEN'), /contractLocked=true/);

    stateMod.transitionState(statePath, 'S2_CONTRACT_LOCK');
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');

    // Approve contract -> enters S3a_ORACLE_TEST_GEN first (NOT S3b)
    const approved = stateMod.approveContract(statePath, {
      specPath: 'docs/specs/demo/spec.md',
      deployTarget: 'merge-only',
      allowedProtectedPaths: ['Dockerfile'],
    });
    assert.equal(approved.contractLocked, true);
    assert.equal(approved.oracleLocked, false);
    assert.equal(approved.currentState, 'S3a_ORACLE_TEST_GEN');
    assert.equal(approved.gate, 'AUTO_PROCEED');

    // Cannot skip straight to S3b_IMPL_WORKFLOW before locking independent oracle tests!
    assert.throws(() => stateMod.transitionState(statePath, 'S3b_IMPL_WORKFLOW'), /oracle tests are authored and locked/);

    // lock-oracle rejects empty or unverified-Red oracle tests
    assert.throws(() => stateMod.lockOracle(statePath, { files: [] }), /requires at least one oracle test file/);
    assert.throws(
      () => stateMod.lockOracle(statePath, { files: ['compiler/src/test/java/ngj/oracle/DemoOracleTest.java'], preImplRedVerified: false }),
      /pre-implementation Red failure/,
    );

    // Lock oracle with Red verification -> enters S3b_IMPL_WORKFLOW
    const locked = stateMod.lockOracle(statePath, {
      files: ['compiler/src/test/java/ngj/oracle/DemoOracleTest.java'],
      preImplRedVerified: true,
    });
    assert.equal(locked.oracleLocked, true);
    assert.deepEqual(locked.oracleTestFiles, ['compiler/src/test/java/ngj/oracle/DemoOracleTest.java']);
    assert.equal(locked.currentState, 'S3b_IMPL_WORKFLOW');

    // SessionStart hook surfaces active state
    const prevEnv = process.env.NGJ_SHIP_STATE_FILE;
    process.env.NGJ_SHIP_STATE_FILE = statePath;
    try {
      const initOut = sessionInitMod.runSessionInit(tmp);
      assert.equal(initOut.injectSteps.length, 1);
      assert.match(initOut.injectSteps[0].systemMessage.systemMessage, /S3b_IMPL_WORKFLOW/);
    } finally {
      if (prevEnv === undefined) delete process.env.NGJ_SHIP_STATE_FILE;
      else process.env.NGJ_SHIP_STATE_FILE = prevEnv;
    }

    // Stop hook auto-continues up to MAX_STALLS_PER_STATE then escalates
    for (let i = 0; i < stopMod.MAX_STALLS_PER_STATE; i++) {
      assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'continue');
    }
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');
    assert.equal(stateMod.loadState(statePath).currentState, 'ESCALATED');

    // resumeRun enforces oracleStatus precondition before resuming into S5
    assert.throws(() => stateMod.resumeRun(statePath, { targetState: 'S5_PR_CI_HEAL_LOOP' }), /oracleStatus/);

    // Resume via CLI into default target (S3b)
    const { stdout: s3bStdout } = await execFileAsync(process.execPath, [stateCli, 'resume'], {
      cwd: REPO_ROOT,
      env: { ...process.env, NGJ_SHIP_STATE_FILE: statePath },
    });
    assert.equal(JSON.parse(s3bStdout).currentState, 'S3b_IMPL_WORKFLOW');

    // Cannot advance to S5_PR_CI_HEAL_LOOP without --oracle-passed
    assert.throws(() => stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { prNumber: 99 }), /locked oracle tests pass/);

    // Advance with --oracle-passed -> S6 requires BOTH --ci-passed AND --codex-passed
    stateMod.transitionState(statePath, 'S5_PR_CI_HEAL_LOOP', { prNumber: 99, oraclePassed: true });
    assert.throws(() => stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY'), /CI status is passed/);
    assert.throws(
      () => stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY', { ciPassed: true }),
      /Codex PR review completes cleanly/,
    );

    // Retry cap (max 2), CLI selective retry reset on resume, & 2-strikes guard promotion
    assert.equal(stateMod.recordRetry(statePath, 'impl', REPO_ROOT).exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci', REPO_ROOT).exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci', REPO_ROOT).exceeded, false);
    assert.equal(stateMod.recordRetry(statePath, 'ci', REPO_ROOT).exceeded, true);
    stateMod.escalateRun(statePath, 'CI retries exceeded');

    const { stdout: s5Stdout } = await execFileAsync(
      process.execPath,
      [stateCli, 'resume', 'S5_PR_CI_HEAL_LOOP', '--reset-retries', 'ci,codex'],
      { cwd: REPO_ROOT, env: { ...process.env, NGJ_SHIP_STATE_FILE: statePath } },
    );
    const resumedS5 = JSON.parse(s5Stdout);
    assert.equal(resumedS5.currentState, 'S5_PR_CI_HEAL_LOOP');
    assert.equal(resumedS5.gate, 'AUTO_PROCEED');
    assert.equal(resumedS5.escalationReason, null);
    assert.deepEqual(resumedS5.retries, { impl: 1, codex: 0, ci: 0 });

    assert.equal(stateMod.recordFinding(statePath, 'valhalla-acc-identity', 'first hit').promoteToGuard, false);
    assert.equal(stateMod.recordFinding(statePath, 'valhalla-acc-identity', 'second hit').promoteToGuard, true);

    // Both gates passed -> S6 -> DONE (and cannot resume from DONE)
    stateMod.transitionState(statePath, 'S6_AUTO_MERGE_AND_DEPLOY', { ciPassed: true, codexPassed: true });
    stateMod.transitionState(statePath, 'DONE');
    assert.equal(stopMod.evaluateStop({ fullyIdle: true }, statePath).decision, 'allow');
    assert.throws(() => stateMod.resumeRun(statePath, { targetState: 'S5_PR_CI_HEAL_LOOP' }), /completed/);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('run-oracle-tests discovers Java, Node, and Python clean-room oracle suites and supports --require-non-empty', async () => {
  const { runOracleSuites } = await import('../scripts/guards/run-oracle-tests.mjs');
  const tmp = makeTempDir('ngj-oracle-runner-');
  try {
    assert.equal(runOracleSuites(tmp, { requireNonEmpty: false }).passed, true);
    assert.equal(runOracleSuites(tmp, { requireNonEmpty: true }).passed, false);

    const oracleDir = path.join(tmp, 'tests', 'oracle');
    fs.mkdirSync(oracleDir, { recursive: true });
    fs.writeFileSync(
      path.join(oracleDir, 'demo.oracle.test.cjs'),
      "const test = require('node:test');\nconst assert = require('node:assert/strict');\ntest('[AC-1] contract passes', () => { assert.equal(1 + 1, 2); });\n",
      'utf8',
    );
    const javaOracleDir = path.join(tmp, 'compiler', 'src', 'test', 'java', 'ngj', 'oracle');
    fs.mkdirSync(javaOracleDir, { recursive: true });
    fs.writeFileSync(
      path.join(javaOracleDir, 'SampleOracleTest.java'),
      'class SampleOracleTest { /* AC-2 */ void ok() { assertEquals(1, 1); } }\n',
      'utf8',
    );

    const res = runOracleSuites(tmp, { requireNonEmpty: true });
    assert.equal(res.passed, true);
    assert.equal(res.discovered.nodeFiles.length, 1);
    assert.equal(res.discovered.javaFiles.length, 1);
    assert.match(res.javaNote, /gradlew :compiler:test/);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('LogSage CI log compressor filters noise and classifies NGJ Gradle compile, test, toolchain, and Valhalla failures within token budget', async () => {
  const { compressCiLog, formatMarkdown } = await import('../scripts/guards/ci-log-compressor.mjs');
  const rawCompileLog = [
    ...Array.from({ length: 120 }, (_, i) => `2026-11-26T01:00:00.000Z Download https://repo.maven.apache.org/pkg-${i}.jar`),
    '2026-11-26T01:01:00.000Z > Task :compiler:compileJava FAILED',
    '2026-11-26T01:01:00.100Z compiler/src/main/java/ngj/typechecker/TypeChecker.java:42: error: cannot find symbol',
    '2026-11-26T01:01:00.200Z FAILURE: Build failed with an exception.',
  ].join('\n');

  const compileReport = compressCiLog(rawCompileLog);
  assert.equal(compileReport.category, 'gradle_compile');
  assert.ok(compileReport.rawLineCount > 120);
  assert.ok(compileReport.keyErrorLines.length < 15);
  assert.ok(compileReport.suspectedFiles.some((f) => f.includes('TypeChecker.java:42')));
  const md = formatMarkdown(compileReport);
  assert.ok(md.includes('gradle_compile'));
  assert.ok(md.length < 4000);

  const testLog = [
    '> Task :compiler:test FAILED',
    'ValhallaValidationTest > value01_recordIsValueClass FAILED',
    '    org.opentest4j.AssertionFailedError: expected: <false> but was: <true>',
  ].join('\n');
  assert.equal(compressCiLog(testLog).category, 'gradle_test');

  const valhallaLog = 'Error: Preview features are not enabled for ngj/cli/Main (class file version 72.65535).';
  assert.equal(compressCiLog(valhallaLog).category, 'valhalla_jdk_check');

  const toolchainLog = 'Cannot find a Java installation on your machine matching: {languageVersion=25}.';
  assert.equal(compressCiLog(toolchainLog).category, 'gradle_toolchain');
});

test('check-staged-boundaries and .githooks/pre-commit enforce secondary boundary locks in a real git repo', async () => {
  const stagedMod = await import('../.agents/skills/ship/scripts/check-staged-boundaries.mjs');
  const config = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, '.agents', 'ship.config.json'), 'utf8'));

  // 1) C100 copy + M .env in NUL-delimited output parses cleanly without desync and denies .env
  const copied = stagedMod.parseNameStatusOutput('C100\0src.java\0dst.java\0M\0.env\0', true);
  assert.equal(copied.length, 2);
  assert.equal(copied[0].status, 'C100');
  assert.equal(copied[1].path, '.env');
  const envResult = stagedMod.evaluateStagedEntries(copied, { hasActiveShipRun: false, overrideEnv: true, config });
  assert.equal(envResult.decision, 'deny');
  assert.ok(envResult.violations.some((v) => v.path === '.env'));

  // 2) R100 rename moving a protected file or locked oracle file out of its guarded path is denied during active /ship run
  const activeLocked = {
    hasActiveShipRun: true,
    currentState: 'S3b_IMPL_WORKFLOW',
    contractLocked: true,
    oracleLocked: true,
    oracleTestFiles: ['compiler/src/test/java/ngj/oracle/ValhallaOracleTest.java'],
    allowedProtectedPaths: [],
    config,
    overrideEnv: false,
  };
  assert.equal(
    stagedMod.evaluateStagedEntries(
      stagedMod.parseNameStatusOutput('R100\0scripts/guards/run-all-guards.mjs\0scripts/tmp.mjs\0', true),
      activeLocked,
    ).decision,
    'deny',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries(
      stagedMod.parseNameStatusOutput(
        'R100\0compiler/src/test/java/ngj/oracle/ValhallaOracleTest.java\0compiler/src/test/java/ngj/TmpTest.java\0',
        true,
      ),
      activeLocked,
    ).decision,
    'deny',
  );
  assert.equal(
    stagedMod.evaluateStagedEntries(
      stagedMod.parseNameStatusOutput('R100\0notes/draft.md\0docs/specs/new-feat/spec.md\0', true),
      activeLocked,
    ).decision,
    'allow',
  );

  // 3) Real temporary git repository + .githooks/pre-commit E2E test
  const preCommitText = fs.readFileSync(path.join(REPO_ROOT, '.githooks', 'pre-commit'), 'utf8');
  assert.ok(
    preCommitText.indexOf('check-staged-boundaries.mjs') < preCommitText.indexOf('run-all-guards.mjs'),
    '.githooks/pre-commit must run check-staged-boundaries.mjs before run-all-guards.mjs',
  );

  const tmpRepo = makeTempDir('ngj-staged-git-');
  const tmpState = path.join(tmpRepo, '.ship', 'runs', 'test.json');
  try {
    const copyIntoRepo = (rel) => {
      const dst = path.join(tmpRepo, rel);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(path.join(REPO_ROOT, rel), dst);
    };

    copyIntoRepo('.agents/ship.config.json');
    copyIntoRepo('.agents/skills/ship/scripts/state.mjs');
    copyIntoRepo('.agents/skills/ship/scripts/guard-boundaries.mjs');
    copyIntoRepo('.agents/skills/ship/scripts/check-staged-boundaries.mjs');
    copyIntoRepo('.githooks/pre-commit');
    fs.chmodSync(path.join(tmpRepo, '.githooks', 'pre-commit'), 0o755);

    const oracleRel = 'tests/oracle/tracked.oracle.test.cjs';
    fs.mkdirSync(path.join(tmpRepo, 'tests', 'oracle'), { recursive: true });
    fs.writeFileSync(
      path.join(tmpRepo, oracleRel),
      "const test = require('node:test');\nconst assert = require('node:assert/strict');\ntest('[AC-1] ok', () => { assert.equal(1, 1); });\n",
      'utf8',
    );

    await execFileAsync('git', ['init'], { cwd: tmpRepo });
    await execFileAsync('git', ['config', 'user.name', 'Oracle Test'], { cwd: tmpRepo });
    await execFileAsync('git', ['config', 'user.email', 'oracle@example.com'], { cwd: tmpRepo });
    await execFileAsync('git', ['add', '.'], { cwd: tmpRepo });
    await execFileAsync('git', ['commit', '-m', 'initial tracked oracle'], { cwd: tmpRepo });
    await execFileAsync('git', ['config', 'core.hooksPath', '.githooks'], { cwd: tmpRepo });

    fs.mkdirSync(path.dirname(tmpState), { recursive: true });
    fs.writeFileSync(tmpState, JSON.stringify({
      schema: 3,
      currentState: 'S3b_IMPL_WORKFLOW',
      contractLocked: true,
      oracleLocked: true,
      oracleTestFiles: [oracleRel],
      allowedProtectedPaths: [],
    }), 'utf8');

    fs.appendFileSync(path.join(tmpRepo, oracleRel), '// weakened\n', 'utf8');
    await execFileAsync('git', ['add', oracleRel], { cwd: tmpRepo });

    const commitBlocked = await execFileAsync(
      'git',
      ['commit', '-m', 'tamper tracked oracle'],
      { cwd: tmpRepo, env: { ...process.env, NGJ_SHIP_STATE_FILE: tmpState } },
    ).then(() => ({ blocked: false }), (err) => ({ blocked: true, code: err.code }));

    assert.equal(commitBlocked.blocked, true, '.githooks/pre-commit must block staged M edit to locked oracle file');
    assert.notEqual(commitBlocked.code, 0);

    // Direct CLI check with staged A on a new oracle file -> allowed
    await execFileAsync('git', ['reset', '--hard', 'HEAD'], { cwd: tmpRepo });
    const newOracleRel = 'tests/oracle/new-feature.oracle.test.cjs';
    fs.writeFileSync(
      path.join(tmpRepo, newOracleRel),
      "const test = require('node:test');\nconst assert = require('node:assert/strict');\ntest('[AC-2] ok', () => { assert.equal(2, 2); });\n",
      'utf8',
    );
    await execFileAsync('git', ['add', newOracleRel], { cwd: tmpRepo });

    const checkCli = path.join(REPO_ROOT, '.agents', 'skills', 'ship', 'scripts', 'check-staged-boundaries.mjs');
    await execFileAsync(
      process.execPath,
      [checkCli, '--cwd', tmpRepo],
      { cwd: REPO_ROOT, env: { ...process.env, NGJ_SHIP_STATE_FILE: tmpState } },
    );

    // Staging .env -> always blocked even with NGJ_ALLOW_BOUNDARY_EDIT=1
    fs.writeFileSync(path.join(tmpRepo, '.env'), 'DUMMY_CONFIG=local\n', 'utf8');
    await execFileAsync('git', ['add', '.env'], { cwd: tmpRepo });
    const secretBlocked = await execFileAsync(
      process.execPath,
      [checkCli, '--cwd', tmpRepo],
      {
        cwd: REPO_ROOT,
        env: { ...process.env, NGJ_SHIP_STATE_FILE: tmpState, NGJ_ALLOW_BOUNDARY_EDIT: '1' },
      },
    ).then(() => false, () => true);
    assert.equal(secretBlocked, true, 'Staged .env must always be blocked');
  } finally {
    fs.rmSync(tmpRepo, { recursive: true, force: true });
  }
});

test('Codex review parser and wait-pr-gates handle state markers, running grace, retries, mention dedup, not_configured, and fail-closed verdicts', async () => {
  const { parseCodexReviewOutput } = await import('../.agents/skills/ship/scripts/run-codex-review.mjs');
  const gates = await import('../.agents/skills/ship/scripts/wait-pr-gates.mjs');

  const localParsed = parseCodexReviewOutput('P1: Missing NGJ-NULL-001 check in TypeChecker.java\nVerdict: patch is incorrect');
  assert.equal(localParsed.verdict, 'issues_found');
  assert.equal(localParsed.p1Count, 1);

  const sinceIso = '2026-11-26T12:00:00.000Z';
  const headSha = 'abcdef1234567890abcdef1234567890abcdef12';
  const oldSha = '0000111122223333444455556666777788889999';

  // Nested running marker -> running, never clean
  const running = gates.extractCodexFindings([], [], [
    {
      author: { login: 'chatgpt-codex-connector[bot]' },
      createdAt: '2026-11-26T11:55:00.000Z',
      updatedAt: '2026-11-26T12:02:00.000Z',
      body: `Codex is reviewing...\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","status":"running","has_any_findings":null,"has_unresolved_findings":null}} -->`,
    },
  ], { sinceIso, headRefOid: headSha });
  assert.equal(running.status, 'running');
  assert.equal(running.isRunning, true);
  assert.equal(gates.evaluateGateVerdict({ ciPassed: true, codex: running, timedOut: false }).nextAction, 'escalate_human');

  // Unresolved findings flag -> blocking_findings & fix_codex_findings
  const unresolved = gates.extractCodexFindings([], [], [
    {
      author: { login: 'chatgpt-codex-connector[bot]' },
      updatedAt: '2026-11-26T12:03:00.000Z',
      body: `Summary\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","status":"completed","has_any_findings":true,"has_unresolved_findings":true}} -->`,
    },
  ], { sinceIso, headRefOid: headSha });
  assert.equal(unresolved.status, 'blocking_findings');
  assert.equal(gates.evaluateGateVerdict({ ciPassed: true, codex: unresolved, timedOut: false }).nextAction, 'fix_codex_findings');

  // Not-configured message -> escalate_human
  const notCfg = gates.extractCodexFindings([], [], [
    {
      author: { login: 'chatgpt-codex-connector[bot]' },
      createdAt: '2026-11-26T12:03:00.000Z',
      body: 'To use Codex here, [create an environment for this repo](https://chatgpt.com/codex/cloud/settings/environments).',
    },
  ], { sinceIso, headRefOid: headSha });
  assert.equal(notCfg.status, 'not_configured');
  assert.equal(gates.evaluateGateVerdict({ ciPassed: true, codex: notCfg, timedOut: false }).nextAction, 'escalate_human');

  // Quota limited -> escalate_human
  const quota = gates.extractCodexFindings([], [], [
    { author: { login: 'chatgpt-codex-connector[bot]' }, body: 'Codex reached your ChatGPT Plus usage limit for code review.' },
  ]);
  assert.equal(gates.evaluateGateVerdict({ ciPassed: true, codex: quota, timedOut: false }).nextAction, 'escalate_human');

  // Stale SHA ignored
  const stale = gates.extractCodexFindings([], [], [
    {
      author: { login: 'chatgpt-codex-connector[bot]' },
      updatedAt: '2026-11-26T12:04:00.000Z',
      body: `No major issues.\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${oldSha}","status":"completed","has_any_findings":false,"has_unresolved_findings":false}} -->`,
    },
  ], { sinceIso, headRefOid: headSha });
  assert.equal(stale.status, 'pending');

  // Running grace period extends up to +300s capped at 900s
  const startMs = 1_000_000;
  const extended = gates.computeCodexDeadline({
    nowMs: startMs + 590_000,
    baseDeadlineMs: startMs + 600_000,
    startMs,
    codex: { isRunning: true, status: 'running', hasEyesReaction: false },
    runningGraceSeconds: 300,
    maxTotalTimeoutSeconds: 900,
    graceGranted: false,
  });
  assert.equal(extended.graceGranted, true);
  assert.equal(extended.deadlineMs, startMs + 890_000);

  // runGhWithRetry retries 502 and 403 rate limit, fails fast on 404
  let retryAttempts = 0;
  const retryRes = await gates.runGhWithRetry(['pr', 'view', '1'], {
    maxRetries: 3,
    baseDelayMs: 10,
    runner: () => {
      retryAttempts += 1;
      if (retryAttempts === 1) throw new Error('gh: HTTP 502 Bad Gateway');
      if (retryAttempts === 2) throw new Error('gh: HTTP 403: API rate limit already exceeded');
      return '{"ok":true}';
    },
    sleepFn: async () => {},
  });
  assert.equal(retryRes, '{"ok":true}');
  assert.equal(retryAttempts, 3);

  let notFoundAttempts = 0;
  await assert.rejects(() =>
    gates.runGhWithRetry(['pr', 'view', '999'], {
      maxRetries: 3,
      baseDelayMs: 10,
      runner: () => {
        notFoundAttempts += 1;
        throw new Error('gh: HTTP 404: Not Found');
      },
      sleepFn: async () => {},
    }),
  );
  assert.equal(notFoundAttempts, 1);

  // Clean pollPr completing inside running grace without duplicating @codex review
  const t0 = Date.parse('2026-11-26T12:00:00.000Z');
  let nowMs = t0;
  const prCommentCalls = [];
  const logs = [];
  const report = await gates.pollPr(17, {
    repo: 'koduki/ngj',
    pollSeconds: 100,
    heartbeatSeconds: 100,
    autoMentionAfterSec: 90,
    codexTimeoutSeconds: 600,
    runningGraceSeconds: 300,
    maxTotalTimeoutSeconds: 900,
    nowFn: () => nowMs,
    sleepFn: async (ms) => {
      nowMs += ms;
    },
    logFn: (line) => logs.push(String(line)),
    ghRunner: (args) => {
      if (args[0] === 'pr' && args[1] === 'comment') {
        prCommentCalls.push(args);
        return '';
      }
      if (args[0] === 'pr' && args[1] === 'view') {
        const elapsedSec = (nowMs - t0) / 1000;
        const completed = elapsedSec >= 700;
        const body = completed
          ? `No major issues found.\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","status":"completed","has_any_findings":false,"has_unresolved_findings":false}} -->`
          : `Codex is reviewing...\n<!-- codex-review-state:v1 {"code_review":{"commit_sha":"${headSha}","status":"running","has_any_findings":null,"has_unresolved_findings":null}} -->`;
        return JSON.stringify({
          url: 'https://github.com/koduki/ngj/pull/17',
          headRefOid: headSha,
          commits: [{ oid: headSha, committedDate: new Date(t0 - 10_000).toISOString() }],
          statusCheckRollup: [{ name: 'build', status: 'COMPLETED', conclusion: 'SUCCESS' }],
          reviews: [],
          comments: [
            { author: { login: 'ngj-bot' }, createdAt: new Date(t0 + 1000).toISOString(), body: '@codex review' },
            { author: { login: 'chatgpt-codex-connector[bot]' }, createdAt: new Date(t0 + 2000).toISOString(), updatedAt: new Date(nowMs).toISOString(), body },
          ],
        });
      }
      if (args[0] === 'api' && String(args[1]).includes('/comments')) return '[]';
      if (args[0] === 'api' && String(args[1]).includes('/reactions')) {
        return JSON.stringify([{ user: { login: 'chatgpt-codex-connector[bot]' }, content: 'eyes' }]);
      }
      throw new Error(`Unexpected gh invocation: ${args.join(' ')}`);
    },
  });

  assert.equal(prCommentCalls.length, 0);
  assert.equal(report.readyToAutoMerge, true);
  assert.equal(report.exitCode, 0);
  assert.equal(report.nextAction, 'auto_merge');
  assert.equal(report.codex.runningGraceGranted, true);
  assert.ok(logs.length >= 1);
});

test('Agent prompts, rules, SKILL.md, and AGENTS.md (<= 90 lines) encode hardened /ship contracts for NGJ', () => {
  const read = (rel) => fs.readFileSync(path.join(REPO_ROOT, rel), 'utf8');

  const skillMd = read('.agents/skills/ship/SKILL.md');
  assert.match(skillMd, /ship-reviewer/);
  assert.match(skillMd, /implementerTiers|tiered/i);
  assert.match(skillMd, /micro[- ]fix|microFixMaxLines|50\s+lines/i);
  assert.match(skillMd, /state\.mjs\s+resume/);
  assert.match(skillMd, /check-staged-boundaries\.mjs/);

  const oracleAuthorMd = read('.agents/agents/oracle-test-author.md');
  assert.match(oracleAuthorMd, /OracleTest\.java/);
  assert.match(oracleAuthorMd, /execFileSync/);

  const architectMd = read('.agents/agents/ship-architect.md');
  assert.match(architectMd, /\bstandard\b[\s\S]*\bflash\b/i);
  assert.match(architectMd, /\bcomplex\b[\s\S]*\binherit\b/i);

  const agentsMd = read('AGENTS.md');
  assert.match(agentsMd, /ship-reviewer/);
  assert.match(agentsMd, /NOT RUN/);
  const lineCount = agentsMd.trimEnd().split(/\r?\n/).length;
  assert.ok(lineCount <= 90, `AGENTS.md must stay <= 90 lines (got ${lineCount})`);
});
