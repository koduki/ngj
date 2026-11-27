# .agents/skills/ship/workflows/build_and_verify.py
# Antigravity Dynamic Workflow (Desktop App / CLI counterpart to /ship S3a + S3b).
# Implements:
# - Phase 1 (S3a): Clean-Room Oracle Test Author (completely separate agent process,
#   spec-only before implementation begins, pre-impl Red check, then lock-oracle)
# - Phase 2/3 (S3b): Bounded parallel Wave 1 (1 file = 1 worker) + sequential Wave 2
#   with locked oracle tests (coding agents cannot edit oracle tests)
# - Phase 4: Serial critical-section verification (guards + oracle + unit tests, max 2 retries)
# - Clean main context: returns ONLY map, changed files, and structured results

import asyncio

MAX_CONCURRENCY = 3
MAX_RETRIES = 2

spec_path = args.get("spec_path", "docs/SPECIFICATION.md")
acceptance_path = args.get("acceptance_path", "")
skip_oracle = bool(args.get("oracle_already_locked", False))

oracle_files = []
if not skip_oracle:
    phase("1. S3a Clean-Room Spec-Derived Oracle Test Authoring (Before Implementation)")
    oracle_result = await agent(
        "You are an independent CLEAN-ROOM ORACLE TEST AUTHOR running BEFORE implementation.\n"
        f"Read ONLY `{spec_path}`, `{acceptance_path}`, `AGENTS.md`, and public contract/test entrypoints.\n"
        "DO NOT plan or modify production code (PreToolUse hook blocks non-oracle edits during S3a).\n"
        "Author behavioral E2E / integration / contract oracle tests strictly from the specification:\n"
        "- Java JUnit 5 E2E/contract: `compiler/src/test/java/ngj/oracle/<Name>OracleTest.java`\n"
        "- Node E2E/contract: `tests/oracle/<slug>.oracle.test.cjs`\n"
        "- Python contract: `tests/oracle/test_<slug>_oracle.py`\n"
        "Tag each test with its acceptance criterion ID (AC-1, AC-2, ...). Run the new oracle tests to verify "
        "pre-implementation Red failure, then lock them via `node .agents/skills/ship/scripts/state.mjs lock-oracle --files <csv> --red-verified`.",
        schema={
            "type": "object",
            "properties": {
                "oracle_files": {"type": "array", "items": {"type": "string"}},
                "pre_impl_red_verified": {"type": "boolean"},
                "covered_criteria": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["oracle_files", "pre_impl_red_verified", "covered_criteria"],
        },
        role="Clean-Room Oracle Author",
        model="pro",
    )
    oracle_files = oracle_result["oracle_files"]
    log("Oracle tests locked before implementation:", oracle_result)

phase("2. Plan NGJ Compiler Layers & Disjoint File Ownership")
plan = await agent(
    f"Read ONLY `{spec_path}`, `AGENTS.md`, and relevant file paths (do not dump full files). "
    "Decompose the approved contract into NGJ compiler implementation layers:\n"
    "- Wave 1 (parallel, strictly disjoint owned_files): Parser/AST (`ngj.parser`, `ngj.ast`), TypeChecker (`ngj.typechecker`), CodeGen/IR (`ngj.ir`, `ngj.codegen`), Diagnostics (`ngj.diagnostic`), or fixtures/scripts.\n"
    "- Wave 2 (sequential after Wave 1): Compiler/CLI integration wiring (`ngj.Compiler`, `ngj.cli.Main`).\n"
    f"NEVER assign locked oracle files ({oracle_files}), contracts, guards, or CI workflows to implementers.",
    schema={
        "type": "object",
        "properties": {
            "wave1": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "layer": {"type": "string"},
                        "owned_files": {"type": "array", "items": {"type": "string"}},
                        "instructions": {"type": "string"},
                    },
                    "required": ["id", "layer", "owned_files", "instructions"],
                },
            },
            "wave2": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "id": {"type": "string"},
                        "layer": {"type": "string"},
                        "owned_files": {"type": "array", "items": {"type": "string"}},
                        "instructions": {"type": "string"},
                    },
                    "required": ["id", "layer", "owned_files", "instructions"],
                },
            },
        },
        "required": ["wave1", "wave2"],
    },
    role="NGJ Layer Planner",
    model="pro",
)

# Enforce exclusive file ownership lock: 1 file -> at most 1 Wave 1 worker
ownership_map = {}
for task in plan["wave1"]:
    for f in task["owned_files"]:
        if f in ownership_map:
            raise RuntimeError(f"File ownership conflict in Wave 1: {f} assigned to both {ownership_map[f]} and {task['id']}")
        ownership_map[f] = task["id"]

phase("3. S3b Wave 1 Parallel Implementation (Bounded Concurrency, Oracles Locked)")
sem = asyncio.Semaphore(MAX_CONCURRENCY)
workers = {}

async def run_wave1_worker(task):
    async with sem:
        worker = agent(
            f"Implement task `{task['id']}` ({task['layer']}) strictly following `{spec_path}` and `.agents/rules/`.\n"
            f"EXCLUSIVE OWNERSHIP LOCK: Edit ONLY these files: {task['owned_files']}.\n"
            "Do NOT edit locked oracle tests, contracts, CI workflows, or guards.\n"
            f"Instructions:\n{task['instructions']}",
            schema={
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "changed_files": {"type": "array", "items": {"type": "string"}},
                    "summary": {"type": "string"},
                },
                "required": ["task_id", "changed_files", "summary"],
            },
            role=f"Wave1: {task['id']}",
        )
        workers[task["id"]] = worker
        return await worker

wave1_results = await parallel(plan["wave1"], run_wave1_worker) if plan["wave1"] else []

phase("4. S3b Wave 2 Sequential Wiring")
async def run_wave2_worker(task):
    worker = agent(
        f"Implement Wave 2 task `{task['id']}` ({task['layer']}) integrating Wave 1 outputs per `{spec_path}`.\n"
        f"EXCLUSIVE OWNERSHIP LOCK: Edit ONLY these files: {task['owned_files']}.\n"
        f"Wave 1 summaries: {[{'id': r['task_id'], 'files': r['changed_files'], 'summary': r['summary']} for r in wave1_results]}\n"
        f"Instructions:\n{task['instructions']}",
        schema={
            "type": "object",
            "properties": {
                "task_id": {"type": "string"},
                "changed_files": {"type": "array", "items": {"type": "string"}},
                "summary": {"type": "string"},
            },
            "required": ["task_id", "changed_files", "summary"],
        },
        role=f"Wave2: {task['id']}",
    )
    workers[task["id"]] = worker
    for f in task["owned_files"]:
        ownership_map[f] = task["id"]
    return await worker

wave2_results = await pipeline(plan["wave2"], run_wave2_worker) if plan["wave2"] else []

all_changed_files = sorted({
    f
    for r in (wave1_results + wave2_results)
    for f in r["changed_files"]
})

phase("5. Serial Critical-Section Verification (Guards + Locked Oracles + Unit Tests, Max 2 Retries)")
verification_verdict = None
for attempt in range(MAX_RETRIES + 1):
    verification_verdict = await agent(
        "Run NGJ's full local verification suite ONCE in serial (do NOT edit any code):\n"
        "1. `node scripts/guards/run-all-guards.mjs`\n"
        "2. `node scripts/guards/run-oracle-tests.mjs`\n"
        "3. `bash -n scripts/*.sh .githooks/pre-commit`\n"
        "4. `node --test tests/*.test.cjs`\n"
        "5. If JDK 25 toolchain is available locally and Java/Gradle files changed, run `NGJ_BUILD_JDK_VERSION=25 ./gradlew test`.\n"
        f"Known file ownership map: {ownership_map}\n"
        "Return strict JSON with pass/fail, oracle_passed, concise failed_items, responsible_task_id if failed, and gradle_status.",
        schema={
            "type": "object",
            "properties": {
                "passed": {"type": "boolean"},
                "oracle_passed": {"type": "boolean"},
                "failed_items": {"type": "array", "items": {"type": "string"}},
                "responsible_task_id": {"type": "string"},
                "gradle_status": {"type": "string"},
            },
            "required": ["passed", "oracle_passed", "failed_items", "gradle_status"],
        },
        role=f"Serial Verifier #{attempt + 1}",
        model="flash",
    )
    if verification_verdict["passed"] and verification_verdict["oracle_passed"]:
        log(f"Serial verification + locked oracle tests PASSED on attempt {attempt + 1}.")
        break
    if attempt == MAX_RETRIES:
        raise RuntimeError(f"ESCALATE: Verification/Oracle failed after {MAX_RETRIES} retries: {verification_verdict['failed_items']}")

    target_id = verification_verdict.get("responsible_task_id", "")
    target_worker = workers.get(target_id)
    if target_worker is None:
        raise RuntimeError(f"ESCALATE: Could not route failure cleanly ({target_id}): {verification_verdict['failed_items']}")

    log(f"Routing verification failure back to {target_id} (retry {attempt + 1}/{MAX_RETRIES}).")
    await target_worker(
        f"Verification or locked oracle test failed:\n{verification_verdict['failed_items']}\n"
        "Fix ONLY within your owned production files. Locked oracle tests cannot be modified.",
        schema={"type": "string"},
    )

log("BUILD_AND_VERIFY_RESULT", {
    "spec_path": spec_path,
    "oracle_files": oracle_files,
    "changed_files": all_changed_files,
    "verification": verification_verdict,
})
