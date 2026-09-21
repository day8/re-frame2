# Resources evaluation plan

Initial criteria recorded before experimental results on 2026-09-14. Preserve this version in `evidence/initial-evaluation-plan.md` before refining the protocol. Compare ordinary SPA outcomes; importance is not a numerical weight.

| ID | Job | Importance | Passing outcome |
|---|---|---|---|
| R01 | First useful read and next feature | Essential | A fresh consumer can follow public instructions to render data; additional reads and writes have a discoverable path. |
| R02 | Identity, cache reuse and deduplication | Essential | Same key shares one in-flight request; distinct params/scopes remain separate; fresh reads reuse data. |
| R03 | Demand, ownership and navigation | Essential | Work has a useful lifetime; another consumer retains its entry; departed identities cannot corrupt current projections. |
| R04 | Refresh and failure with existing data | Essential | Stale data remains usable and refresh failure is visible; default and matched freshness policies are distinguished. |
| R05 | Retry, focus, reconnect and polling | Important | Documented policies recover without accidental duplicate writes; transport and cache ownership are explicit. |
| R06 | Mutation results and cross-view consequences | Essential | Accepted writes update affected views, membership and counts; completion drives the next workflow step. |
| R07 | Optimistic writes under overlap | Essential | Fast feedback, truthful rejection and eventual authoritative convergence under controlled completion order. |
| R08 | Paged navigation and previous data | Important | Changing params preserves a useful view without representing old data as new. |
| R09 | Infinite feeds | Important | Load-more, terminal state and refresh obey one coherent page model; retention limitations are explicit. |
| R10 | Viewer and unresolved-session isolation | Essential | Alice's delayed result never appears in Bob's read; correct requests still work. |
| R11 | SSR and hydration | Important | Request isolation, projection and freshness hold across a real serialization boundary. |
| R12 | Persistence and offline writes | Important | A documented supported route or an explicit parity gap, including restart and conflict limits. |
| R13 | Push, cross-tab and entity coherence | Important | Existing integrations are credited; broad parity gaps are not hidden by Conduit's narrower needs. |
| R14 | Schema, editor and contract changes | Important | Parameter/key changes are discoverable and understandable with ordinary tools; runtime validation and static inference are distinct. |
| R15 | Diagnose, repair and preserve a regression | Essential | A real application mistake is observable, its repair passes, and reintroducing the same fault makes the assertion fail. |
| R16 | Reproduction and assistant authoring | Important | Public declarations, tools and fixtures support an actual repair or authoring task without hidden runtime assumptions. |

For every row retain separate contract, implementation, discoverability, observed behavior and comparative judgment. Evidence labels: executed, source-traced, documented, inferred, unverified. A passing internal test does not establish a browser journey or ergonomic advantage.

The initial executable set uses the Resources module's focused existing suites, Conduit's deterministic backend and a compact TanStack counterpart. Record source hashes and command exit codes. For key claims, add specific fault controls in isolated research fixtures. Establish the request ledger before asserting deduplication; control response ordering rather than waiting an arbitrary interval. Compare default policies, then equivalent explicit policies. Keep SSR, infinite-feed and classified-export checks at their actual tested layer.

Research runs may read shared source but must keep their fixture, output, ports and processes separate. No product changes or permanent benchmark infrastructure. Record setup failures as failures of the attempted route, not library-wide absence. Runtime and UI evidence will be marked separately in the final execution table.

## First measurement and rerun protocol

The initial criteria above remain unchanged. Results are in [the matrix](parity-matrix.md), [scorecard crosswalk](scorecard-audit.md) and [execution ledger](evidence.md). Executed layers comprise the Resources JVM module, selected Conduit/Xray Node tests, the actual Conduit browser with controlled transport delivery, a TanStack React/Devtools slice and TanStack core cases.

Conduit's first browser smoke uses the original deterministic backend, signs in with the demo account, opens the global feed, toggles a favorite and visits its detail. Additional controlled cases then use the same app/backend, with independent reply delivery and synthetic failures. The favorite membership control uses `second-article`, initially absent from the demo viewer's favorites; the asserted outcome is collection membership after an accepted write. Faulting the app's invalidation helper gives failure, restoring it gives success, reinstating it gives failure. This result is recorded explicitly, not inferred from a timeout.

The overlap case starts two separate favorite mutation instances, rejects the older request without a server write, commits the newer, then delivers newer success before older rejection. Passing means the newer accepted value/count survives and converges to the backend. This is distinct from interpreting the last click as the last server commit. The scope case tags one old representation, changes the viewer with the required route replan, releases the old reply, and verifies that the new viewer first sees an empty key and can subsequently fetch normally.

The TanStack counterpart uses the same article/favorites domain and visibility requirement, but fewer records and a different compact backend. Consequently aggregate request counts, code size and total UI time are not compared. Its shared-cache optimistic strategy reconciles on settle; its core suite separately checks serialized mutations. Resources' immediate conflict-free inverse rollback and TanStack's refetch-based recovery have different recovery latency and offline implications.

### Rerun commands

Paths below assume this checkout. Use a fresh attempt number for each log/exit artifact. PowerShell must capture each command's own `$LASTEXITCODE` immediately; do not pipe test output through a filter. Existing installs/caches affect setup time.

```powershell
$repoRoot = '<HOME>/code/re-frame2'
$evidenceRoot = Join-Path $repoRoot 'ai/findings/Resources/astra/evidence'

# Resources module (run from this absolute module directory).
Set-Location -LiteralPath (Join-Path $repoRoot 'implementation/resources')
clojure -M:test

# Regenerate the isolated CLJS build configuration from current source.
Set-Location -LiteralPath $repoRoot
clojure -M (Join-Path $evidenceRoot 'prepare-build.clj')
Set-Location -LiteralPath $evidenceRoot
node (Join-Path $repoRoot 'implementation/node_modules/shadow-cljs/cli/runner.js') compile conduit research-tests

# Node's module search path is needed for this detached generated runner.
$priorNodePath = $env:NODE_PATH
try {
  $env:NODE_PATH = Join-Path $repoRoot 'implementation/node_modules'
  node (Join-Path $evidenceRoot 'research-tests.js')
} finally { $env:NODE_PATH = $priorNodePath }

# Browser server ports are allocated dynamically; each runner closes its own processes.
node (Join-Path $evidenceRoot 'browser-probe.cjs') resources
node (Join-Path $evidenceRoot 'tanstack/core-cases.mjs')
node (Join-Path $evidenceRoot 'browser-probe.cjs') tanstack
```

For a clean TanStack dependency install run `npm.cmd ci --no-audit --no-fund` in `evidence/tanstack/`, then rebuild `app.tsx` using its installed esbuild. Keep the whole `--outfile=...` argument a single string on PowerShell. `prepare-fixtures.py` restores the initial isolated dependency inputs, including the intentionally incomplete tutorial dependency set; use it deliberately, not after hand-editing a fixture. Run `consumer/probe.clj` with the literal and repaired dependency sets separately to reproduce the missing-artifact result.

On this machine the isolated CLJS compilation reported about 28 seconds for Conduit and 13 seconds for the selected tests; the final Node integration run took about 7.5 seconds; final browser runs took a few seconds. Those are single observations with warm shared dependency caches. Allow several minutes for a focused repeat and more for fresh dependencies. No claim about relative development speed follows.

### Evidence still required for stronger claims

| Job | Discriminating next experiment | Evidence required |
|---|---|---|
| R01, R14 | Start from the merged tutorial repair and its reported developer consumer proof; observe an unassisted read, mutation and related-read extension | Stalls, explanation needed and changed coordination code; distinguish the existing developer walkthrough from novice/assistant productivity evidence |
| R03, R05 | Navigate away with/without another owner, return around a scheduled GC check; derive B from A, then change A before its old continuation arrives; test focus/reconnect and actual abort separately | Correct B params, retained-data/request behavior and stale-continuation suppression. Equal GC numbers do not mean equal inactivity retention. `:after` is not data readiness; stubs do not prove wire cancellation |
| R08, R09 | Change offset/cursor pages while inserting/removing an item ahead of the window; compare default and all-page refresh | Wait for requested page identity or known item IDs, not list visibility: keep-previous can satisfy visibility immediately. Check order, missing/duplicate items, previous-state cue and retention; no retained tail counted as fully refreshed |
| R10 | Start a new browser with a stored token, hold restore, then resolve/reject it and change accounts | No wrongly scoped request while unresolved; the intended route eventually loads after resolution; correct requests must still occur |
| R11 | Render two server requests, serialize projected cache, hydrate two browsers and observe first paint plus request ledger | No inter-request data, no unintended fresh duplicate request, declared classified-field treatment; streamed path only if actually used |
| R12, R13 | Test one supported persistence/live-update integration on an actual consumer requirement | Reload survival, identity changes, queued write replay/idempotency and collection coherence; keep unimplemented routes as gaps |
| R15, R16 | Reproduce the membership defect with Xray/Pair attached and compare the supported Devtools/editor workflow | Successful host/frame discovery, source/consequence identification and verified repair; record interaction/context changes before claiming time savings |

### What invalidates a reading

Tutorial/dependency changes invalidate R01; scope/key or canonicalization changes invalidate R02/R10/R11; owner/route policy changes invalidate R03/R05/R08; mutation/reply/optimistic changes invalidate R06/R07/R15; infinite policy changes invalidate R09; persistence or stream integrations invalidate R12/R13; schema/registrar/skill/tool changes invalidate R14–R16. A TanStack dependency update invalidates its affected executable rows; a moving documentation page changes only documented evidence until execution is repeated.

Existing Resources, Conduit and Xray suites should retain the implementation assertions. Human onboarding/diagnosis judgments should remain a documented walkthrough, not a mandatory CI percentage or live-network benchmark. Re-run only affected rows unless a changed common primitive justifies broader testing. Keep old receipts and mark changed conclusions throughout the report and matrix.

## Refinements after sibling review

The [independent draft](evidence/independent-draft/receipt.json) predates sibling consultation. Additions above strengthen R03 and R08 without changing the sixteen-job denominator. Newly executed checks are the [global-policy counterexample](evidence/scope-counterexample.json) and [facade/portability probe](evidence/facade-portability-re-frame2-1.log). Re-run them with `node evidence/scope-browser-probe.cjs` from this folder, and `clojure -M <absolute consumer/facade-portability-probe.clj>` from the repaired consumer directory. Each command needs a fresh log/exit attempt number.

For R01, retain the tutorial's compiler and development alias when applying additive dependency changes; distinguish omitted boot requires from intentionally incomplete exercises. Verify that Part 5 tests the code actually taught, on the stated JVM/CLJS target. For R10, deliberately wrong global metadata should demonstrate the programmer's responsibility, while the correctly scoped control remains usable. Do not redefine every retained old-scope cache entry as a cross-viewer leak: prove which projection can read it.

For R15, establish an actual development tool host before timing diagnosis; absence of a production HTML mount is insufficient evidence of a missing dev integration. Do not compare a richer Resources trace against a deliberately unsupported comparator workflow. For R06/R07/R13, every request-count comparison must match freshness, membership, observers and recovery behavior; a fewer-refetch example alone does not establish an unavoidable competitor cost.

For R07, also record the value immediately after an older rejection and before its recovery refetch. Introduce a distinct newer server count, so restoring an old snapshot is observable even when the boolean happens to agree. Final convergence must not hide a stale interval. Measure any rendered interval separately from core cache observations, and keep pending-state semantics explicit when a mutation awaits its settlement invalidation.

A migration-policy recipe should state per-registration freshness and frame revalidation separately; the Conduit fixture already demonstrates their composition. There is no frame-wide `:stale-after-ms` default. For request-count assertions, use the public `rf/with-fx-overrides` seam on which `with-request-stubs` is built; the canned helper does not itself return a ledger. Preserve the captured request/reply middleware chain when testing transport behavior, since an override that only appends arguments bypasses those guarantees. A concise recording recipe is the first intervention; introduce a helper only if the concrete test remains awkward. [Supported test implementation](evidence/sibling-review/round-6/verification/implementation/http/src/re_frame/http/test_support.cljc).

## Second review: repaired tutorial and measured retention policy

The [current source receipt](evidence/sibling-review/round-5/current-baseline.json) supersedes the original tutorial pages for present-tense judgments. PR #9838 supplies a reported 25-check consumer browser walkthrough and six JVM tests; Astra independently repeated the dependency-load probe using the revised Part 2 map. A full novice or assistant authoring task remains outstanding. Do not reintroduce the historical dependency/interop failures into the current app simply to rerun an obsolete onboarding verdict.

The GC comparison uses controlled scheduling, not wall-clock sleeps. Resources settles at logical time 0 with an interval of 1,000; a check at 1,000 finds an owner and re-arms for 2,000; releasing at 1,990 does not re-arm and the 2,000 check collects. TanStack's final observer leaves at 1,990, arming collection at 2,990. This proves a retention-policy distinction, not actual memory usage or a browser's scheduling precision. Test real back-navigation before recommending a runtime policy change.

Reproduce with `clojure -M <absolute evidence/gc-review-probe.clj>` from `evidence/sibling-review/round-5/gc-consumer`, and `node <absolute evidence/tanstack/gc-review.mjs>`. For the current dependency check, run the existing `consumer/probe.clj` from `evidence/sibling-review/round-5/current-consumer`. Capture new numbered log/exit files. The original full module tests were not repeated: the runtime files they tested did not change in the 160-file source comparison; the new bounded probes address the new question.
