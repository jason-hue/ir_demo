# Vector Retrieval Timeout Fix

## TL;DR
> **Summary**: Bound the vector branch in non-demo hybrid retrieval so `/query/hybrid` and `/chat/ask` never hang on slow Ollama embedding or Qdrant search. Keep lexical retrieval as the guaranteed floor, and degrade to lexical-only within a fixed **2-minute** budget while recording explicit timeout/fallback signals.
> **Deliverables**:
> - bounded vector-retrieval execution path
> - bounded vector-sync path for startup ingestion
> - explicit timeout/fallback configuration and observability
> - deterministic tests plus fresh non-demo runtime proof
> **Effort**: Medium
> **Parallel**: YES - 2 waves
> **Critical Path**: 1 → 2 → 3/4 → 5

## Context
### Original Request
用户明确要求：`你需要修复向量检索超时这个bug`

### Interview Summary
- 用户先要求“同时启用向量和词法”，后续真实运行时已经证明 **hybrid 模式本身可用**，问题不再是“没开向量”，而是**向量分支会卡死请求**。
- 用户要求 `[search-mode] MAXIMIZE SEARCH EFFORT`，因此本计划建立在并行本地审计、运行证据、以及外部 Spring AI/Qdrant 资料之上。
- 用户未要求改 UI、改 chat 产品语义、改 demo 行为；因此计划严格限于 **非 demo 混合检索超时 bug**。

### Metis Review (gaps addressed)
- Metis指出必须把契约写死：当向量分支超时/失败时，系统应**快速降级为 lexical-only**，而不是挂死。
- Metis要求明确超时预算层级：整体向量分支预算、向量检索内部预算、以及是否把启动灌注中的向量写入阻塞也纳入同一修复。
- 本计划已据此固定：**查询路径和启动灌注路径都要加边界**，因为当前证据显示两者共享相同的 embedding/Qdrant 阻塞面。

## Work Objectives
### Core Objective
修复非 demo 混合检索中由向量分支引起的超时/挂死问题，使 `/query/hybrid` 与 `/chat/ask` 在向量路径变慢或阻塞时仍能在 **2 分钟内** 返回 lexical-only 结果，而不是无限等待。

### Deliverables
- `HybridRetrievalService` 中带预算的向量分支执行与 lexical-only 降级逻辑
- `AiProperties` / `application.properties` 中明确的向量检索超时配置（固定为 2 分钟）
- `ArticleChunkVectorSyncService` 中带预算的向量同步等待逻辑，避免 startup ingestion 长期卡在 `RUNNING`
- 针对请求级 timeout/fallback 的测试
- 针对启动灌注向量次级失败的测试/证据
- 一次 fresh non-demo hybrid runtime 真实验证

### Definition of Done (verifiable conditions with commands)
- `mvn '-Dmaven.compiler.testIncludes=**/HybridRetrievalServiceTest.java,**/ProviderStatusServiceDegradedTest.java' -Dtest=HybridRetrievalServiceTest,ProviderStatusServiceDegradedTest test` returns `BUILD SUCCESS`
- `mvn '-Dmaven.compiler.testIncludes=**/ArticleChunkVectorSyncOutageTest.java,**/ProductionStatusEndpointTest.java' -Dtest=ArticleChunkVectorSyncOutageTest,ProductionStatusEndpointTest test` returns `BUILD SUCCESS`
- `mvn '-Dmaven.compiler.testIncludes=**/HybridChatEndpointDegradedTest.java,**/ChatAnswerServiceTest.java' -Dtest=HybridChatEndpointDegradedTest,ChatAnswerServiceTest test` returns `BUILD SUCCESS`
- `mvn -Dmaven.test.skip=true clean compile` returns `BUILD SUCCESS`
- Fresh runtime command below starts successfully and `/status/production` returns HTTP 200:
  - `mvn -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.arguments="--server.port=18082 --irdemo.dir.home=/home/knifefire/work/ir_demo/workspace/vector-timeout-fix-runtime --irdemo.dir.startCrawler=true --irdemo.ai.qdrant.enabled=true --irdemo.ai.qdrant.host=127.0.0.1 --irdemo.ai.qdrant.http-port=6333 --irdemo.ai.qdrant.grpc-port=6334 --irdemo.ai.qdrant.collection-name=news_article_chunks_hybrid_20260423_1706 --irdemo.ai.qdrant.initialize-schema=false"`
- In that fresh runtime:
  - `curl --max-time 10 -fsS -X POST "http://127.0.0.1:18082/query/hybrid" -H "Content-Type: application/json" -d '{"question":"腾讯新闻","pageNo":1,"pageSize":5}'` returns JSON with `success=true`
  - If vector path is healthy: `mode="hybrid"`
  - If vector path is slow/blocked: request still returns within bound and degrades cleanly with `mode="lexical_only"` plus non-null `degradedReason`
- It must **not** hang past the fixed 2-minute request timeout budget.

### Must Have
- Lexical retrieval always starts and remains the guaranteed floor.
- Vector retrieval runs best-effort under an explicit **2-minute** time budget.
- On vector timeout/failure, request returns lexical-only rather than hanging.
- Startup vector sync cannot leave categories permanently `RUNNING` due only to blocked vector I/O.
- Logs/evidence distinguish timeout degradation from provider-unavailable and from pure lexical config-disable mode.

### Must NOT Have
- No demo-profile behavior changes.
- No UI/product-text redesign.
- No generic circuit-breaker framework rollout beyond the minimal fix seam.
- No rewrite of ranking/fusion semantics beyond timeout-triggered lexical fallback.
- No force-enabling vector path when provider status is genuinely unavailable.

## Verification Strategy
> ZERO HUMAN INTERVENTION - all verification is agent-executed.
- Test decision: tests-after + focused regression
- QA policy: Every task includes automated checks; runtime task includes direct HTTP verification against a fresh non-demo process
- Evidence: `.sisyphus/evidence/vector-timeout-fix-task-{N}-{slug}.{ext}`

## Execution Strategy
### Parallel Execution Waves
Wave 1: timeout contract + request-path implementation
- Task 1: lock timeout/degrade contract in focused tests
- Task 2: implement bounded vector branch in `HybridRetrievalService`

Wave 2: startup vector-sync containment + runtime proof
- Task 3: bound vector-sync waits in `ArticleChunkVectorSyncService`
- Task 4: align status/degraded semantics and focused regression
- Task 5: fresh non-demo runtime QA and evidence capture

### Dependency Matrix (full, all tasks)
- 1 blocks 2
- 2 blocks 4 and 5
- 3 blocks 4 and 5
- 4 blocks 5
- 5 blocks F1-F4

### Agent Dispatch Summary (wave → task count → categories)
- Wave 1 → 2 tasks → unspecified-high / deep
- Wave 2 → 3 tasks → unspecified-high / deep
- Final wave → 4 tasks → oracle / unspecified-high / deep

## TODOs
> Implementation + Test = ONE task. Never separate.
> EVERY task MUST have: Agent Profile + Parallelization + QA Scenarios.

- [x] 1. Lock timeout/degrade contract in focused tests

  **What to do**: Add or update focused tests so the intended behavior is explicit before changing runtime logic. The contract to lock is: when vector retrieval is slow or blocked but lexical retrieval succeeds, `/query/hybrid` returns within a bounded time as lexical-only with a concrete `degradedReason`, not a hung request; when non-empty retrieval still happens, chat remains governed by existing provenance rules.
  **Must NOT do**: Do not add broad UI tests, do not change demo-profile tests, do not redesign answer text.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` - Reason: focused test-surface changes across a few existing files
  - Skills: [`code-debugger`] - diagnose exact missing seam before editing tests
  - Omitted: [`refactor-planner`] - no large structural refactor at this step

  **Parallelization**: Can Parallel: NO | Wave 1 | Blocks: [2] | Blocked By: []

  **References**:
  - Pattern: `src/test/java/cn/edu/bistu/cs/ir/ai/HybridRetrievalServiceTest.java:19-130` - current retrieval happy/degraded seams
  - Pattern: `src/test/java/cn/edu/bistu/cs/ir/HybridChatEndpointDegradedTest.java:67-165` - machine-checkable degraded payload expectations
  - Pattern: `src/test/java/cn/edu/bistu/cs/ir/ai/ChatAnswerServiceTest.java:331-363` - non-empty retrieval invalid-citation normalization remains unchanged
  - API/Type: `src/main/java/cn/edu/bistu/cs/ir/ai/HybridRetrievalService.java:66-115` - request path to pin in tests
  - Evidence: `.sisyphus/evidence/hybrid-enable-18082-restart-status.json` - prior hybrid-ready runtime

  **Acceptance Criteria**:
  - [ ] Focused tests fail before Task 2 if vector branch still hangs indefinitely.
  - [ ] Tests assert lexical-only degradation on vector timeout rather than generic failure.
  - [ ] Tests preserve existing non-empty chat provenance semantics.

  **QA Scenarios**:
  ```
  Scenario: Retrieval timeout degrades to lexical-only
    Tool: Bash
    Steps: Run `mvn '-Dmaven.compiler.testIncludes=**/HybridRetrievalServiceTest.java,**/HybridChatEndpointDegradedTest.java' -Dtest=HybridRetrievalServiceTest,HybridChatEndpointDegradedTest test`
    Expected: BUILD SUCCESS after implementation; before implementation, new timeout-focused seam should fail or be absent
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-1-timeout-tests.txt

  Scenario: Chat provenance unchanged for non-empty retrieval
    Tool: Bash
    Steps: Run `mvn '-Dmaven.compiler.testIncludes=**/ChatAnswerServiceTest.java' -Dtest=ChatAnswerServiceTest test`
    Expected: BUILD SUCCESS; existing lexical/hybrid provenance assertions still pass
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-1-chat-regression.txt
  ```

  **Commit**: NO | Message: `fix(ai): lock vector timeout degradation contract` | Files: [test files only]

- [x] 2. Implement bounded vector branch in `HybridRetrievalService`

  **What to do**: Change `HybridRetrievalService.retrieve()` so lexical retrieval remains the guaranteed floor and vector retrieval becomes best-effort under a fixed **2-minute** budget. Replace unbounded `vectorFuture.join()` usage with an explicit bounded wait (`get(2, MINUTES)` or equivalent configurable 120s budget) and convert vector timeout/failure into lexical-only degradation with a concrete `degradedReason`. Keep the existing successful hybrid path unchanged when vector returns within budget.
  **Must NOT do**: Do not redesign fusion logic, do not remove lexical-first availability, do not introduce a broad framework-level circuit breaker here.

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: logic-heavy change in the main request path with multiple failure modes
  - Skills: [`code-debugger`] - root-cause-driven minimal change
  - Omitted: [`performance-investigator`] - already used during planning; implementation now needs precise code edits

  **Parallelization**: Can Parallel: NO | Wave 1 | Blocks: [4,5] | Blocked By: [1]

  **References**:
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/ai/HybridRetrievalService.java:66-115` - current unbounded request orchestration
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/ai/HybridRetrievalService.java:184-203` - current blocking `vectorStore.similaritySearch(...)`
  - API/Type: `src/main/java/cn/edu/bistu/cs/ir/config/AiProperties.java` - add explicit vector retrieval timeout property near existing AI timeout settings, fixed to a 2-minute default
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/controller/QueryController.java:79-95` - endpoint surface to preserve
  - Research: `bg_6da5f140` summary - upstream recommends deadlines per remote hop plus outer vector budget

  **Acceptance Criteria**:
  - [ ] `/query/hybrid` no longer hangs indefinitely when vector path stalls and instead returns or degrades within 2 minutes.
  - [ ] Successful fast vector path still returns `mode="hybrid"`.
  - [ ] Timed-out vector path returns `mode="lexical_only"` with non-null `degradedReason` within 2 minutes.

  **QA Scenarios**:
  ```
  Scenario: Vector branch timeout bounded
    Tool: Bash
    Steps: Run `mvn '-Dmaven.compiler.testIncludes=**/HybridRetrievalServiceTest.java' -Dtest=HybridRetrievalServiceTest test`
    Expected: BUILD SUCCESS with explicit timeout degradation assertions
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-2-hybrid-service.txt

  Scenario: Simpler hybrid query remains successful when vector is healthy
    Tool: Bash
    Steps: Later in runtime task, call `POST /query/hybrid` for `腾讯新闻`
    Expected: `success=true`; `mode` is either `hybrid` or safely degraded lexical-only within timeout budget, but request never hangs
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-2-runtime-query.txt
  ```

  **Commit**: NO | Message: `fix(ai): bound vector retrieval latency` | Files: [`HybridRetrievalService.java`, related tests/config]

- [x] 3. Bound startup vector-sync waits in `ArticleChunkVectorSyncService`

  **What to do**: Add explicit time budgets around Qdrant delete/upsert/collection-exists waits so startup ingestion cannot remain indefinitely `RUNNING` due only to blocked vector operations. Preserve Lucene-primary semantics: Lucene success remains committed; vector timeout/failure degrades to partial success / vector failure recording.
  **Must NOT do**: Do not roll back Lucene writes, do not make startup ingestion synchronous on vector success, do not broaden into distributed coordination.

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: this is the secondary but related blocking seam affecting startup lifecycle
  - Skills: [`code-debugger`] - precise timeout insertion without semantic drift
  - Omitted: [`refactor-planner`] - no staged multi-module redesign needed

  **Parallelization**: Can Parallel: YES | Wave 2 | Blocks: [4,5] | Blocked By: []

  **References**:
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/ai/ArticleChunkVectorSyncService.java:79-128` - blocking delete/upsert waits
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/ai/ArticleChunkVectorSyncService.java:145-173` - blocking collection existence / creation waits
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/index/LucenePipeline.java:60-70` - Lucene-first success semantics
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/crawler/IngestionObservabilityService.java:244-257` - current `PARTIAL_SUCCESS` semantics
  - Evidence: `.sisyphus/evidence/hybrid-chatfix-restart.log:200-245` and current status snapshots - startup categories can remain `RUNNING` long after indexing 1 doc each

  **Acceptance Criteria**:
  - [ ] Startup categories do not remain indefinitely `RUNNING` when only vector I/O is blocked.
  - [ ] Vector timeout/failure records partial/degraded semantics without losing Lucene docs.
  - [ ] Existing Task 3 status semantics remain intact.

  **QA Scenarios**:
  ```
  Scenario: Vector sync timeout degrades but does not wedge startup state
    Tool: Bash
    Steps: Run `mvn '-Dmaven.compiler.testIncludes=**/ArticleChunkVectorSyncOutageTest.java,**/ProductionStatusEndpointTest.java' -Dtest=ArticleChunkVectorSyncOutageTest,ProductionStatusEndpointTest test`
    Expected: BUILD SUCCESS; vector outage/timeout results in degraded/partial state, not indefinite RUNNING
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-3-vector-sync.txt

  Scenario: Lucene-primary semantics preserved
    Tool: Bash
    Steps: Inspect resulting test output for `PARTIAL_SUCCESS` assertions and non-zero indexed counts
    Expected: Lucene docs still present after vector-side failure
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-3-partial-success.txt
  ```

  **Commit**: NO | Message: `fix(ai): bound vector sync waits during ingestion` | Files: [`ArticleChunkVectorSyncService.java`, related tests]

- [x] 4. Align status/degraded semantics and focused regression

  **What to do**: Ensure runtime status and degraded reasons remain truthful after Tasks 2 and 3. If provider status still reports `AVAILABLE` while actual vector execution is timing out, update only the minimal semantics/documentation/tests needed so operators can distinguish “provider reachable” from “vector execution degraded.” Prefer preserving existing `/status/production` schema; do not redesign it.
  **Must NOT do**: Do not turn this into a status API redesign, and do not change unrelated provider semantics.

  **Recommended Agent Profile**:
  - Category: `unspecified-high` - Reason: cross-check / regression task across service and status surfaces
  - Skills: [`regression-risk-assessor`] - protect adjacent behaviors while tightening semantics
  - Omitted: [`docs-writer`] - operator docs are not the primary deliverable here

  **Parallelization**: Can Parallel: NO | Wave 2 | Blocks: [5] | Blocked By: [2,3]

  **References**:
  - Pattern: `src/main/java/cn/edu/bistu/cs/ir/ai/ProviderStatusService.java:97-152` - current readiness semantics are probe-based
  - Pattern: `src/test/java/cn/edu/bistu/cs/ir/ai/ProviderStatusServiceDegradedTest.java` - provider status degraded tests
  - Pattern: `src/test/java/cn/edu/bistu/cs/ir/HybridChatEndpointDegradedTest.java:102-165` - machine-checkable degraded chat payload shape
  - Evidence: current thread dump / direct `/api/embed` timeout vs `/status/production` AVAILABLE mismatch

  **Acceptance Criteria**:
  - [ ] Focused regression suite stays green.
  - [ ] Degraded reason on timeout path is machine-checkable and not confused with config-disabled lexical mode.
  - [ ] Timeout-related degraded behavior consistently uses the fixed 2-minute budget.
  - [ ] No unrelated chat/UI semantics drift.

  **QA Scenarios**:
  ```
  Scenario: Degraded status remains machine-checkable
    Tool: Bash
    Steps: Run `mvn '-Dmaven.compiler.testIncludes=**/ProviderStatusServiceDegradedTest.java,**/HybridChatEndpointDegradedTest.java' -Dtest=ProviderStatusServiceDegradedTest,HybridChatEndpointDegradedTest test`
    Expected: BUILD SUCCESS
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-4-status-regression.txt

  Scenario: No unrelated chat behavior drift
    Tool: Bash
    Steps: Run `mvn '-Dmaven.compiler.testIncludes=**/ChatAnswerServiceTest.java' -Dtest=ChatAnswerServiceTest test`
    Expected: BUILD SUCCESS
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-4-chat-regression.txt
  ```

  **Commit**: NO | Message: `fix(ai): clarify timeout degradation semantics` | Files: [minimal service/tests only]

- [x] 5. Execute fresh non-demo runtime proof for bounded hybrid behavior

  **What to do**: Start a fresh non-demo backend from cleanly built classes on port 18082 with the same isolated-home pattern already used successfully. Capture decisive evidence that the request path is now bounded: either hybrid returns quickly with vector-backed results, or vector times out and the endpoint returns lexical-only with explicit degraded reason within the fixed 2-minute budget. Also confirm startup ingestion categories do not remain indefinitely `RUNNING` after vector-side timeout containment.
  **Must NOT do**: Do not use demo profile, do not reuse stale runtime homes, do not rely on an already-noisy process for final proof.

  **Recommended Agent Profile**:
  - Category: `deep` - Reason: runtime orchestration plus API verification under realistic conditions
  - Skills: [`code-debugger`] - disciplined runtime validation and evidence capture
  - Omitted: [`playwright`] - backend/API proof is primary; browser optional only if API is clean and user-facing confirmation is still needed

  **Parallelization**: Can Parallel: NO | Wave 2 | Blocks: [F1,F2,F3,F4] | Blocked By: [2,3,4]

  **References**:
  - Pattern: `.sisyphus/evidence/task-6-runtime-launch.txt` and `.sisyphus/evidence/task-6-runtime-summary.txt` - existing non-demo runtime launch style
  - Evidence: `.sisyphus/evidence/hybrid-chatfix-restart.log` - current noisy target to avoid reusing as final proof
  - API: `src/main/java/cn/edu/bistu/cs/ir/controller/StatusController.java`
  - API: `src/main/java/cn/edu/bistu/cs/ir/controller/QueryController.java`
  - API: `src/main/java/cn/edu/bistu/cs/ir/controller/ChatController.java`

  **Acceptance Criteria**:
  - [ ] Fresh runtime starts on a non-demo isolated home and `/status/production` returns HTTP 200.
  - [ ] `/query/hybrid` for `腾讯新闻` returns within the fixed 2-minute budget and never hangs indefinitely.
  - [ ] If vector path is healthy, response is `mode="hybrid"` with non-null `vectorRank`.
  - [ ] If vector path times out, response still returns within 2 minutes as lexical-only with explicit degraded reason.
  - [ ] `/chat/ask` also returns within the fixed 2-minute budget; it may degrade, but it must not hang indefinitely due to vector retrieval.

  **QA Scenarios**:
  ```
  Scenario: Fresh non-demo hybrid query completes within budget
    Tool: Bash
    Steps: Launch `mvn -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.arguments="--server.port=18082 --irdemo.dir.home=/home/knifefire/work/ir_demo/workspace/vector-timeout-fix-runtime --irdemo.dir.startCrawler=true --irdemo.ai.qdrant.enabled=true --irdemo.ai.qdrant.host=127.0.0.1 --irdemo.ai.qdrant.http-port=6333 --irdemo.ai.qdrant.grpc-port=6334 --irdemo.ai.qdrant.collection-name=news_article_chunks_hybrid_20260423_1706 --irdemo.ai.qdrant.initialize-schema=false"`; then call `POST /query/hybrid` for `腾讯新闻`
    Expected: HTTP 200 within 2 minutes; no indefinite hang
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-5-runtime-query.json

  Scenario: Fresh non-demo chat completes within budget
    Tool: Bash
    Steps: Call `POST /chat/ask` for `高德地图在台湾爆火讲了什么` and one prior troublesome prompt such as `请根据检索结果总结高德地图在台湾爆火的原因，并带上引用编号`
    Expected: HTTP 200 within 2 minutes; if answer degrades, it does so without hanging indefinitely
    Evidence: .sisyphus/evidence/vector-timeout-fix-task-5-runtime-chat.json
  ```

  **Commit**: NO | Message: `fix(ai): verify bounded hybrid runtime behavior` | Files: [evidence only]

## Final Verification Wave (MANDATORY — after ALL implementation tasks)
> 4 review agents run in PARALLEL. ALL must APPROVE. Present consolidated results to user and get explicit "okay" before completing.
> **Do NOT auto-proceed after verification. Wait for user's explicit approval before marking work complete.**
> **Never mark F1-F4 as checked before getting user's okay.** Rejection or user feedback -> fix -> re-run -> present again -> wait for okay.
- [x] F1. Plan Compliance Audit — oracle
- [x] F2. Code Quality Review — unspecified-high
- [x] F3. Real Manual QA — unspecified-high (+ playwright if UI)
- [x] F4. Scope Fidelity Check — deep

## Commit Strategy
- Only commit after implementation verification, not during planning.
- Preferred atomic sequence if work is executed later:
  1. `fix(ai): bound vector retrieval timeout and lexical fallback`
  2. `test(ai): cover hybrid timeout degradation and startup vector sync bounds`
  3. `docs(ai): note non-demo hybrid timeout runtime proof` (only if documentation is actually changed)

## Success Criteria
- Non-demo hybrid retrieval never hangs indefinitely due to vector branch slowness, and request-path timeout containment is fixed at 2 minutes.
- Lexical retrieval remains available as the floor under vector timeout/failure.
- Startup ingestion no longer leaves categories indefinitely `RUNNING` solely because vector sync blocked.
- Existing lexical-only, hybrid happy-path, and chat provenance tests remain green.
- Fresh runtime proof demonstrates bounded response behavior on both `/query/hybrid` and `/chat/ask`.
