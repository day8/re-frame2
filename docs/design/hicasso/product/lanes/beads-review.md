# Review of the proposed Hicasso implementation bead set

## Verdict

Do not file the set unchanged. The decomposition has a strong spine: it follows the product phases, gives correctness work real adversarial witnesses, keeps optional products caller-shaped, and usually distinguishes evidence from implementation. The current set nevertheless has four filing blockers:

1. The live bead briefs disagree with the current normative specification at load-bearing API and SSR points.
2. The dependency graph does not enforce several dependencies stated by the briefs, and its broad source fences permit concurrent edits to the same files.
3. The “final” checkpoint can complete with definition-of-done bullets still red; corrective beads, dormant pilots, and the donor-surface disposition have no closure path into a later release decision.
4. The new naming tail directly violates the governing execution requirement. The README says, “No bead waits on an operator ruling,” while `hic-065` says, “This is the set's one deliberate operator gate,” and `hic-066`, documentation, artifacts, and the final audit wait behind it.

The reviewed live snapshot contains **60 bead documents plus the README**, not the 57 in the review request. `hic-065` and `hic-066`, plus related edits to the README and several existing beads, arrived during this review and are included here.

## 1. Coverage against the programme

Most Phase 0–6 capabilities have at least a plausible owner. The following obligations have no complete accountable bead, or are placed so that the phase checkpoint can pass without them.

### 1.1 Phase deliverables and definition-of-done gaps

| Programme obligation | Apparent owner | Gap |
|---|---|---|
| Record the actual K1 sitting outcome and effective revision | `hic-003` | `hic-003` only drafts the proposal and deliberately leaves the effective revision blank. No non-blocking bead records ratification or rejection and its consequences after the sitting. |
| Explicit K3 disposition preserving the Reagent, UIx-parent, and author-preference scoreboards | `hic-006`, `hic-018` | Neither brief owns the three-scoreboard K3 instrument. `hic-006` transcribes budgets; `hic-018` chooses the substrate. The Phase 0 K3 deliverable has no record owner. |
| Freeze the byte-exact shell line before measuring or disposing it | `hic-006`, `hic-018` | Both repeat ambiguous `1 KB`. `hic-006` says “R=0 ≈ 1.1 KB vs the registered 1 KB paper-fail line”; `hic-018` says the substrate “either brings R=0 under 1 KB.” Neither freezes 1,000 B versus 1,024 B as required by the specification. |
| Close all eight Phase 1 kernel-risk rows | `hic-019` | Callback identity and retirement is assigned to `hic-036`, after the kernel checkpoint. `hic-019` nevertheless requires every kernel row to be green. `hic-013` covers reincarnation, not the risk row's memoized retaining-host population and ordinary-path cost question. |
| Ship the complete L0–L3 testing facade in Phase 2 | `hic-020` | Despite its title, its deliverables specify L2, L3, and comparison helpers. There is no explicit L0 public contract or L1 codec/intent/native-expansion surface and acceptance suite. |
| Implement the current native grammar and ABI | `hic-030` | The brief omits `n/props`, the raw-JavaScript props-object ABI, canonical-slot collision refusal, and `n/defcomponent`'s server declaration. It says “dynamic props take the runtime path with identical results,” but the normative grammar requires the explicit `(n/props expression)` marker so a dynamic React element cannot be misclassified as props. It also says “the spec lacks” an example; the current spec contains one. |
| One virtualizer and one imperative SDK witness before the Phase 3 exit | `hic-047` | The bead is in Phase 4 and is not a predecessor of `hic-038`; the Phase 3 checkpoint can pass without a deliverable that Phase 3 explicitly lists. |
| Extend the product application with pagination and runtime content | — | No bead names pagination or runtime-selected content. The Phase 4 beads are separate witnesses; none owns the required integrated application extension. |
| A public-package four-field editor and 100-cell grid witness | `hic-045` | Its fence is documentation plus “the two witness pages.” It does not own the applications or tests, so it can publish numbers from the frozen bench prototypes without proving that the installable package expresses the §13 controlled-grid witness through public surfaces. |
| Qualified bulk release gate at every named operation and size | `hic-036` | The tournament exists, but `hic-048` neither depends on it nor audits it. Phase 4 can close without the bulk/economic suite that its exit requires. |
| Counterfactual topology advice | — | The primary innovation portfolio says **Spike**; no bead owns its blinded calibration protocol. |
| Capability receipts | — | The primary portfolio says **Spike**; no bead owns its perturbation/privacy deciding protocol. |
| Replayable view capsules after L2 | — | The primary portfolio says **Spike after L2**; no bead owns the one-shot, commit-owned, redacted experiment. |
| Shared read-set notification-group census and conditional spike | — | The portfolio says **Census, then spike**; no bead owns either stage. |
| Keep the requirements mine current as witnesses and public names land | `hic-004` | `hic-004` seeds the ledger and says rows update, but no consolidation bead owns those updates. The final audit can inspect a permanently “owed by hic-NNN” snapshot. |
| Move every live Xray/Story/Pair consumer to the adapter-neutral provider before donor disposition | `hic-023`, `hic-062` | `hic-023` covers Xray/Pair consumption; `hic-062` records what remains. No bead inventories and migrates **Story** consumers or proves that all primary tool paths have left donor surfaces. |
| Xray privacy projection as a release contract | `hic-023`, `hic-059` | `hic-023` specifies loss and erasure but not the privacy projector. `hic-059` tests privacy only for an optional MCP spike and is not on the final-audit path. |
| Enforce the user-visible, regression, and escape-benefit budgets | `hic-006`, `hic-033`, `hic-036`, `hic-045` | No bead owns the 50 ms p95/100 ms p99 discrete-interaction gate, the 100 ms p95 broad-operation gate, the 5% same-instrument regression gate, or the rule that an escape stays only after recovering 20%, 2 ms, or a failed budget. Transcribing them into `budgets.md` is not enforcement. |
| Report native-code share as a census, never a quota | — | The performance contract requires the post-implementation census; no bead produces it. |
| Prove warm allocation carries no product claim until its instrument qualifies | — | No final docs/release scan owns the explicit non-claim. This matters because §13 permits release without an allocation series but forbids an unsupported allocation claim. |
| Close checkpoint corrections before a release recommendation | — | Checkpoints file proposed corrections, but no bead resolves their roster or reruns the affected checkpoint. `hic-064` does not require zero unresolved corrective beads. |

Conditional follow-up work is acceptable where the programme itself is conditional: `hic-050` may file a resource implementation bead, `hic-055` may file a codemod bead, `hic-056` correctly remains caller-gated, and `hic-063` remains pilot-gated. Any activated or graduated work still needs to be inserted into the naming, documentation, erasure, compatibility, and final-audit dependency rosters; the current graph has no such dynamic-tail rule.

### 1.2 Beads without specification grounding

- `hic-000` is execution infrastructure rather than a product deliverable. It is justified by the gitignored `ai/` tree and worker-worktree model, so it should remain, preferably as a generated publication/synchronization step.
- `hic-019`, `hic-026`, `hic-038`, `hic-048`, and `hic-064` are not product features but are grounded in the requested independent-checkpoint design.
- The purpose of `hic-065` and `hic-066`—settling provisional names—is grounded. Their **single late operator gate and repo-wide delayed rename strategy is not**. It replaces the specification's Phase 2 ordinary-surface and Phase 3 host/native freeze schedule, contradicts the explicit ungated-flow requirement, and is not executable by a context-free background worker.
- Every other bead has a recognizable grounding in Part III, §12, §7, §11, or a named risk/checkpoint obligation.

### 1.3 §7 rows that can remain unwitnessed when `hic-064` completes

There is no defined meaning of “`hic-064` passes.” Its output is a report plus corrective beads; it can close successfully after reporting red bullets. Even if “passes” is interpreted as completing the current protocol, these rows can still lack their specified proof:

| §7 row | Remaining witness gap |
|---|---|
| Ordinary pages, conditional UI, dynamic lists | `hic-025` is one small article flow. No Todo flow is named, and pagination/runtime content have no bead, so the required Todo and RealWorld-class coverage is not demonstrably complete. |
| Forms and controlled fields | Browser controls are covered, but no bead owns the public-package four-field editor and 100-cell grid applications; `hic-045` owns only their explanatory pages. |
| Errors | Load/error/retry paths exist, but no bead explicitly witnesses a nested error region, one of §7's required proofs. |
| Motion and high-rate input | `hic-053` witnesses interruption, rapid toggle, and teardown, but its acceptance never measures the required frame budget. |
| Accessibility | `hic-043` names focus order and axe, but does not explicitly witness keyboard conduct. Its virtualizer and overlay clauses say “with hic-047's” and “with hic-052's when it lands” without dependencies or a later integration run. |
| i18n and theming | No bead owns runtime locale and theme-change evidence. |
| Testing | The L0/L1 hole in `hic-020` leaves the full supported ladder and positive/sabotage controls at every tier unproved. |
| Diagnostics | Loss and erasure are present; the core privacy projection has no mandatory witness. |
| Migration | `hic-055` permits “the in-repo Reagent examples if externals are unavailable,” which is weaker than the required three representative repositories. |

The remaining §7 rows have at least a claimed bead-level witness, although several are endangered by the dependency and file-fence defects below.

## 2. Dependency correctness

### 2.1 The graph and the briefs disagree mechanically

The README says its graph is authoritative, but it does not encode the briefs' own dependency declarations consistently.

- `hic-001`–`hic-005` each say `Depends: —`, while the README has `hic000 --> hic001 & ... & hic005` and says no worker can resolve its reading set before `hic-000`. The bead metadata must name `hic-000`; otherwise the filed issue and graph tell different stories.
- Seven stated edges are absent from the README graph: `019→026`, `030→036`, `023→037`, `030→041`, `020→043`, `006→045`, and `005→046`. For example, `hic-026` says `Depends: hic-025, hic-019`, but the graph contains only `hic025 --> hic026`; `hic-041` says `Depends: hic-035, hic-030`, but the graph contains only `hic035 --> hic041`.
- The README adds direct `hic-001` edges to `hic-011`, `hic-013`, `hic-014`, and `hic-017`, while their briefs name narrower predecessors; conversely, `hic-025` names `hic-001` directly while the graph supplies it only transitively through `007/020`. These do not alter reachability, but the drift makes automated filing unverifiable.
- `hic-000` acceptance still checks references only through “hic-001…hic-064,” omitting the newly added `hic-065` and `hic-066`.

Generate the filed graph from one machine-readable dependency roster and validate each Markdown `Depends` line against it; do not hand-maintain both forms across 60 files.

### 2.2 Missing semantic or build edges

These are not optional ordering preferences: the dependent brief either names the predecessor's artifact or mutates its surface.

| Required ordering | Evidence in the dependent brief |
|---|---|
| `011→014` and `013→014` | `hic-014` says its surface runs “after hic-010/011 merge” and that retained intents obey “the hic-013 incarnation rule,” yet it formally depends only on `hic-010`. |
| `010/011→018` | `hic-018` says it will “reuse hic-010/011 witnesses” and “coordinate with hic-010” on collector wiring. Coordination is not a dependency or fence. |
| `007→021` | `hic-021` says “hic-007 lands the shape; this catalogues it,” but the README gives it only `hic-001`. |
| `007,020,023→024` | `hic-024` says it “strengthens as 007/020/023 land” while promising sentinels in all of their surfaces. Run first, it can green on an incomplete sentinel roster; edit them itself, it violates their fences. |
| `021,022,023,024→026` | `hic-026` requires “all landed (hic-020…024)” but depends only on `hic-025` and `hic-019`. |
| `007,015→030` | `hic-030` promises the `hic-007` source/error shape and documented HMR conduct but does not wait for either contract. |
| `013,023→031` | `hic-031` says `n/use-frame` “obeys the hic-013 incarnation rule” and acceptance asserts Xray through “hic-023's projection,” yet depends only on `hic-030`. |
| `007,013→035` | `hic-035` exposes new refusals and says retained callbacks follow `hic-013`; neither predecessor is listed. |
| `016→040` | `hic-040` says “hic-016 established the browser rig” but formally depends on `hic-025`. Replace the false application dependency with the browser-rig dependency. |
| `032→041` | `hic-041` says marker/props ABI are preserved using “hic-032 helpers,” but does not depend on `hic-032`. |
| `012,034→046` | `hic-046` includes overlapping hydrated roots and “the native tier (`n/` components under SSR)” but waits on neither the root-isolation implementation nor the completed native tier. |
| `036→048` | Phase 4 exit requires qualified bulk/economic evidence, but the coverage checkpoint waits only on `040…047`. |
| split `043` into core helpers and post-integration checks | `hic-043` needs virtualizer and overlay artifacts while `hic-047` and `hic-052` say they use its helpers. Adding edges in either direction creates a conceptual cycle. Land the helpers first; run separate virtualizer/overlay keyboard-focus witnesses after those products. |
| pre-registration bead `→044→050` | `hic-044` records data “against the pre-registered criteria in hic-050,” while `hic-050` depends on `hic-044` and claims criteria are stated “before reading the hic-044 report.” A commit made after the report exists is not pre-registration. |
| `042,050→054` | `hic-054` is “shaped by hic-050's verdict” and implements the guard on “hic-042's wiring point,” but formally depends only on `hic-025`. |
| `021,024,025→059` | `hic-059` exposes complaints, diagnoses a “real slice-app bug,” and asserts production erasure unchanged, but waits only for `hic-023`. |
| `026,038,050` and every graduating public-surface bead `→065` | `hic-065` says it uses checkpoint findings and covers every public name, yet does not wait for either freeze/checkpoint, the resource verdict, or any public surface created by a successful `057`–`059` spike or conditional follow-up. |
| `066→062`, then serialize its docs sweep with `060` | `hic-066` renames “any docs already written”; `hic-062` concurrently edits docs/nav/READMEs and does not depend on final names. |
| `021,050→060` | Troubleshooting requires the complaint catalogue and async docs must reflect the resource verdict. Neither is currently on the docs bead's dependency path. |
| `023,038,060→062` (`066` is then transitive through `060`) | `hic-062` decides live-tool residue and the taught native story but waits only on `hic-026`; it can run before the live evidence provider, native contract, final names, and new documentation. |
| `050,062→064` | The final protocol requires the resource verdict and donor independence, but the final graph includes neither owner. |
| `057,058,059→064`, plus conditional `056/063→064` when activated | Phase 5's selected spikes otherwise float outside the release tail. An activated server or pilot bead can also finish after an audit that purports to report its current release status. These edges need not gate ordinary implementation: activation should insert them into the audit roster. |

If `hic-050`, `hic-055`, `hic-056`, or a spike files or activates consequential work, that work must be inserted before naming, docs, erasure revalidation, compatibility, and the final audit. If pilots activate, their result must precede the release recommendation. “File a follow-up” is not enough unless the graph has a dynamic-tail rule.

### 2.3 False or avoidable serialization

- The largest false edge is `hic-065` itself: an unbounded human wait on the only path to final names, docs, artifacts, and audit. It violates the required ungated design.
- `hic-040` does not use the slice app; its own surface is the control testbed. Waiting on `hic-025` delays a browser lane that should follow `hic-016` directly.
- `hic-047` can prove a foreign virtualizer and imperative SDK through `defhost` after `hic-035`; requiring the full native three-way parity bead `hic-034` is unnecessary unless the chosen witness explicitly uses the Hicasso-native route.
- The README's direct `hic-001` edges to beads already behind `hic-010` or `hic-012` are redundant. They do not lengthen the current path but should be removed so one graph means one thing.

### 2.4 File-fence collisions the graph does not prevent

The current prototype matters here. `hic-001` is a mechanical copy and does not split the 2,000-line `arm1/runtime.cljs`; that file currently contains adoption, frame operations, generation/HMR, the evidence sink, collector, commit, shells, and retained inventory. The proposed labels do not create separate files.

| Shared mutating surface | Concurrent beads |
|---|---|
| Copied `runtime.cljs` | `hic-010`, `011`, `013`, `014`, `015`, `018`, and the projection hooks in `023`; `017` may also repair globals there. `hic-010` says the others “sequence behind it,” but the graph makes `011`, `013`, and `014` siblings and leaves `018`/`023` independent. |
| Copied `front/codec.cljs` | `hic-007` generalizes `fail!`; `hic-033` changes boundary result handling; `hic-035` changes the host path; `hic-040` allows support-policy fixes “in the codec.” Their fences name sections, not files, and the graph permits overlap. |
| `product/invariants.md` | `hic-002` creates it; `hic-011`, `013`, `015`, and `018` each promise to add/freeze text there but do not declare the file in their surface. Several run concurrently. |
| `product/dispositions.md` | `hic-005` creates it; `hic-040` writes per-control rows and `hic-046` upgrades SSR rows in the same wave. |
| `product/budgets.md` | `hic-006` creates it; `hic-033`, `hic-036`, and `hic-045` all publish results or update rows and can overlap. |
| `product/naming-ledger.md` | `hic-000` seeds it; `hic-026` and `hic-038` append findings on independent branches; `hic-065` depends on neither. |
| Routing integration files | `hic-042` creates the wiring point and `hic-054` consumes/extends it without an edge. |
| User-facing docs/nav/READMEs | `hic-060`, `hic-062`, and repo-wide `hic-066` have overlapping sweeps; only `060` is ordered after `066`. |

Replace “coordinate,” “when it lands,” “if not already,” and “where a gap is found” with exact files plus graph edges. For the shared ledgers, prefer per-bead evidence fragments and one consolidation owner rather than concurrent edits to a central Markdown file.

## 3. Parallelism and critical path

The current longest release path is approximately:

`000 → 001 → 030 → 031 → 032 → 034 → 047 → 048 → 065 → 066 → 060 → 064`

The single largest throughput limiter is now **hic-065**, because its duration is external and unbounded. Remove that gate. Among autonomous beads, **hic-001** is the dominant fan-out bottleneck: one L-sized PR blocks all 16 Wave-B jobs and several later lanes. The README also schedules “up to 16 workers” despite the available eight-worker ceiling; it needs an eight-slot priority order, not merely a wide wave.

Concrete changes:

1. Make naming ungated. Apply recommended defaults automatically, permit asynchronous operator overrides before release, and keep the ledger as evidence—not as a dependency on a sitting. Prefer ordinary-name settlement after the slice evidence and native-name settlement after native evidence so there is no repo-wide Phase 6 rename.
2. Split `hic-001` into package/source extraction plus a compiling public facade, test migration, and tiny-consumer/HMR/release wiring. Only the first minimal compiling package should block source/test workers; keep the hot-zone wiring in one owner.
3. Either split `runtime.cljs` into owned modules before Wave B, or let Phase 1 workers land test-only witnesses in parallel and serialize red-path fixes through one runtime owner. The current “parallel fixes to sections of one file” plan is not viable.
4. Split `hic-020` into L0–L2 pure kit and L3 mounted kit behind a tiny shared contract.
5. Split `hic-035` into host declarations/ReactNode/render-prop ABI and portal/server-policy work, with exact source files.
6. Split `hic-036` into the 48-cell topology tournament and the retaining-host callback verdict. The former is a release-economics programme; the latter is a focused semantic experiment.
7. Split `hic-047` into virtualizer and imperative-SDK beads and run them in parallel after the shared host seam.
8. Split `hic-060` by reference/cookbook, troubleshooting, and performance/escape documentation, followed by one nav/link integration bead.
9. `hic-057` cannot be both an M bead and “one week equivalent.” Reduce it to a two-hour bounded pilot or classify it as a larger research task outside the small-bead claim.

With eight slots, prioritize the runtime critical chain, the testing kit, package diagnostics, and the native ABI foundation; queue optional docs and spikes behind those rather than presenting a theoretical 16-worker wave.

## 4. Bead quality as context-free worker briefs

The sample below spans every phase and more than the requested eight beads.

| Bead | Assessment |
|---|---|
| `hic-001` | Objective package/consumer checks, but not small: runtime copy, namespace migration, metadata, hot-zone builds, app, HMR, production release, and all existing tests are one L bead. “Move/point the existing CLJS test suites” also leaves copy versus relocation underdetermined. |
| `hic-006` | Good provenance requirements, but “make ... budgets ratified” gives a background worker authority the specification assigns to the product operator. It also carries ambiguous `1 KB` and does not name the K3 record. |
| `hic-010` | One of the best briefs: real abandonment populations, residue before reset, and a leak mutation that must red are objectively checkable. Its only material defect is the non-exclusive “runtime collector file” fence. |
| `hic-019` | Strong three-lens review and real-path warning. Its surface says it files corrective proposals under gitignored `ai/findings/.../beads/`, which a worker checkout cannot see or land. Re-running only two sabotages also cannot validate eight newly introduced gate families. |
| `hic-020` | Honest opacity and no fake hooks are excellent. “`implementation/hicasso/test-kit/` or equivalent” is not an unambiguous fence, and the advertised L0–L3 scope omits L0/L1 deliverables. |
| `hic-024` | Excellent fail-closed positive-control and sentinel sabotage. It is scheduled before the surfaces whose sentinels it must inventory, making an otherwise strong acceptance test vacuously incomplete. |
| `hic-030` | A plausible-but-wrong PR is likely: “dynamic props take the runtime path” can produce an unmarked dynamic-map grammar, while the current contract requires `n/props`; server policy and raw-JS ABI are absent. “The spec lacks” an example is a direct stale-corpus marker. |
| `hic-034` | Strong three-way comparator, equality typing, and bundle positive control. Its acceptance says “island band met **or the miss published un-softened**.” Publication is not a pass: the canonical checklist says a red row blocks the native namespace until fixed or the surface shrinks. |
| `hic-036` | Populations and operations are concrete, but two large experiments are bundled and there is no sabotage/eligibility control for either instrument. “No open-ended benchmark programme follows” does not enforce the bulk kill line or escape-benefit threshold. |
| `hic-040` | The browser/control roster is objective. “Sabotage one policy” is underspecified: a worker could alter a table rather than the runtime behavior. The fence permits unplanned codec edits and conflicts with `hic-046` on the disposition table. |
| `hic-044` / `hic-050` | The witness and adopt/stop separation is good. The pre-registration claim is impossible under the graph: the criteria-owning bead starts only after the report exists. This is exactly the instrument-self-flattery failure the corpus warns about. |
| `hic-046` | “Every facade surface” makes coverage countable, which is good. It inherits stale taxonomy—“the three `defhost` `:ssr` policies”—where the canonical matrix has two policies, Render and Client-only, with deterministic fallback/recovery inside Client-only. It also lacks native/root dependencies. |
| `hic-055` | Determinism plus a seeded shadow difference are meaningful controls. The fallback “in-repo Reagent examples if externals are unavailable” permits a pass that does not satisfy the three-repository proof. |
| `hic-060` | “Examples are the witness code, not invented” is a strong rule. Five substantial documents in one L bead are not a small deliverable, and “an outsider can” is not checkable without a named fresh-reader protocol. |
| `hic-064` | Good fresh-checkout, fresh-reader, random-complaint, and anti-cathedral ideas. It does not define a green acceptance state, omits several exact §13 lines, samples only one sabotage per domain, and can complete while pilots, donor disposition, resource verdict, or corrective beads remain open. |
| `hic-065` | The public-surface census is valuable, but a grep needs a seeded omitted-export positive control. More fundamentally, “one operator sitting” is neither autonomous nor ungated, and its graph omits several evidence/public-surface predecessors. |
| `hic-066` | The rename grep and full suite are checkable. A repo-wide mechanical sweep over source, tests, examples, recipes, and docs is the opposite of an isolated file fence and creates the largest late merge-conflict surface in the programme. |

## 5. The defaults taken

The README now contains eight defaults, not six.

| Default | Assessment | Recommendation |
|---|---|---|
| 1. New package; bench tree frozen to Phase 6 | The package location is right. Keeping a second live source tree for six phases invites evidence/code drift, and “frozen” has no mechanical guard. | Freeze with a hash manifest immediately. After `hic-006` re-pins package baselines, retain cited fixtures/data plus the source revision, not a second editable runtime. Add a no-import/no-mutation gate. |
| 2. Kebab props through shared `slot/prop-name`; camelCase legal | Keep, with constraints. Literal CLJS maps get ergonomic zero-runtime lowering; raw JS objects remain the true dynamic fast path. The current bead is incomplete. | Require `n/props` for dynamic operands; pass raw JS objects by identity; convert dynamic CLJS maps shallowly and explicitly; strings/raw JS names stay exact; refuse normalized-slot collisions, namespaced-key ambiguity, `:children`, and Hiccup semantics. Macro/runtime conversion must share one total rule and parity corpus, not necessarily call the existing Hiccup helper blindly for every key kind. |
| 3. Operator Windows machine + GitHub CI runner class | A “runner class” is not a sufficiently pinned hardware identity for distributional budget ratification, and neither choice guarantees the named low-/mid-tier product profiles. | Use a pinned physical/calibrated low-tier and mid-tier profile for product budgets. Use hosted CI for correctness, eligibility controls, and same-run relative drift unless its observed hardware/runtime identity is recorded and accepted for that estimand. |
| 4. Criteria decide resource demand; async veto | The authority model fits an ungated programme, but the implementation violates its own pre-registration. | Split criteria registration before `hic-044`; run the witness; let `hic-050` apply the frozen rule. Default to STOP on ambiguity, file ADOPT implementation separately, and insert that bead into naming/docs/erasure/audit automatically. |
| 5. No Node SSR caller; service dormant | Correct. It preserves the core React-server/hydration contract without building unused operations. | Keep. Add a final caller-census statement so “none” is an observed state, not a forgotten dormant bead. |
| 6. Proceed without pilots | Correct for keeping implementation work moving; incorrect for a green §13/release claim. | Keep the engineering line ungated, but make the final release decision explicitly red until `hic-063` supplies two pilots. Distinguish “implementation audit complete” from “product definition of done.” |
| 7. Tracked snapshot; `ai/` remains living home | This already produced drift: current bead prose references a missing §3.7, omits `n/props`, and uses old SSR taxonomy. “Refresh on material change” has no owner or mechanical definition. | Prefer one tracked living normative home. If dual homes remain temporarily, generate the snapshot, record source hashes, and fail a freshness check before filing or dispatch. |
| 8. One end-of-line naming ruling and rename sweep | Reject under the stated requirements. It creates the only human gate, delays docs/artifacts/audit, invalidates evidence/docs late, and cannot account for dynamically graduated features. | Apply recommended names as defaults without waiting. Keep a ledger and permit asynchronous overrides. If late consolidation remains useful, make it an autonomous consistency check, not a sitting dependency. |

## 6. Checkpoint design

The five checkpoints are placed at sensible product boundaries, and the explicit completeness/correctness/quality lenses are better than one generic “review” bead. They are not yet independent or strong enough for this corpus.

| Checkpoint | Problem |
|---|---|
| `hic-019` kernel | Correct boundary and good real-path emphasis. It cannot file its proposed `ai/` documents from a worker checkout, samples only two sabotages across eight risk families, and is guaranteed to find the late callback-identity row missing. |
| `hic-026` slice | Correct moment to review authoring evidence. It also writes the facade freeze, so the reviewer is performing a governance mutation rather than only auditing and filing corrections. It may start before `021…024`, despite requiring them. |
| `hic-038` native | Correct adoption boundary, but its abbreviated completeness list is weaker than the canonical eight-row native checklist. It does not independently sabotage dependency/rent, frame/store lifecycle, HMR residue, or server refusal. “Contracts freeze here” again combines audit and governance. |
| `hic-048` coverage | Row-by-row and surface-by-surface walks are good. It omits the bulk/economic exit, runs before optional products that some rows name, and re-runs only one control and one SSR sabotage. |
| `hic-064` final | It is an audit producer, not a final green gate. Its completeness paraphrase omits the exact shell disposition, qualified bulk populations, warm-allocation non-claim, donor dependency, and pilots as a required green condition. One fresh run on one profile does not establish every ratified distributional budget. |

Strengthen all five without halting the implementation line:

1. Checkpoints create real `bd` issues, not ignored Markdown proposals, and record the resulting ids in the tracked report.
2. Maintain a checkpoint-correction ledger with open/resolved status. The final audit requires zero unresolved correctness/coverage corrections or reports a non-release verdict.
3. Separate freeze/ruling records from independent review workers. The reviewer reports evidence; a small deterministic follow-up applies a pre-resolved default.
4. Give every gate a roster/population count, positive control, targeted negative mutation, restore proof, and runner-root check. A checkpoint mechanically verifies those records rather than sampling a few and assuming the rest.
5. Add explicit budget-line reconciliation: registered line, current value, status, authority, and any prospective disposition. This catches silently normalized threshold breaches.
6. When corrective beads land, rerun the affected checkpoint protocol or a small closure bead. Filing a fix is not evidence that the miss is fixed.

## 7. Risks, kill rules, and budgets without an enforcement home

| Obligation | Current partial home | Missing enforcement |
|---|---|---|
| Callback identity/retirement kernel risk | `hic-013`, `hic-036` | The risk is not closed before the Phase 1 checkpoint and no ordinary-cost verdict is in `hic-019`'s dependency set. |
| Instrument self-flattery risk | `hic-006`, measurement beads | The `044/050` order defeats pre-registration; `033`, `036`, and `045` do not each name an eligibility sabotage before publishing product data. |
| Experimental residue risk | `hic-023`, `hic-062` | No mandatory Story/Pair/Xray consumer census and migration proof; `hic-062` is absent from the final graph. |
| Registered shell budget | `hic-006`, `hic-018` | No byte-exact line freeze; no checkpoint line item that prevents ambiguous `1 KB` from being silently carried. |
| K3 scoreboards and per-read regression | `hic-006`, `hic-018` | No explicit three-scoreboard decision record and no 10% same-witness enforcement owner. |
| User-visible 50/100 ms budgets | `hic-045` partially | No representative discrete/broad interaction gate over the slice/vendor applications. |
| Same-instrument 5% regression | — | No baseline comparison gate or disposition owner. |
| Escape keeps its place only at 20%, 2 ms, or budget recovery | `hic-033`, `hic-036`, `hic-037` | Results are published, but no acceptance removes an escape that misses. |
| Bulk kill rule | `hic-036` | The brief publishes any result and says only that the programme ends. It does not stop/narrow after the allowed iterations or define those iterations. `hic-048` does not inspect it. |
| Warm-allocation non-claim | — | No release/doc scan forbids an allocation claim before instrument qualification. |
| Native-code percentage census | — | No post-implementation census bead. |
| Standing-cost/no-paying-use-case kill rule | Optional beads, `hic-034` | Individual optional products have reachability checks, but there is no final aggregate over all Phase 5/graduated modules and no boundary-shell comparison after they land. |
| No ViewCell-class graph or second emitter | Design docs only | No final architecture/dependency census checks that resource/pull/generator/tooling work did not introduce either rejected mechanism. |
| Tools without a real consumer build no retained machinery | `hic-023`, `hic-059` | No consumer/retention decision is attached to every new tool feature, and the final audit does not enumerate retained tooling state. |

## Ranked top 10 changes before filing

1. **Remove the `hic-065` operator gate.** Preserve the ledger, apply recommended defaults automatically, and eliminate the late human wait and mass-rename funnel.
2. **Synchronize every brief with the current normative set.** Fix `n/props`/raw-JS/server ABI in `hic-030`, the two-policy SSR model in `hic-035`/`046`, the missing §3.7 references, the native roster in `hic-000`, and every stale “spec lacks” claim.
3. **Generate one dependency manifest and validate it against all `Depends` fields.** Add the missing build/semantic edges and dynamic-tail rule before creating real beads.
4. **Resolve the Wave-B runtime and shared-ledger fence collisions.** Split owned modules or separate parallel witnesses from serialized fixes; never rely on “coordinate.”
5. **Make resource-demand pre-registration real.** Freeze criteria before `hic-044`, then apply them in `hic-050`; thread any ADOPT bead through naming, erasure, docs, compatibility, and audit.
6. **Give the final programme a real closure model.** Track checkpoint corrections, rerun affected audits, require `hic-050` and `hic-062`, and separate an ungated implementation audit from a pilot-dependent release decision.
7. **Fill the uncovered programme/use-case witnesses.** Add pagination/runtime content, i18n/theme change, nested errors, public editor/grid, motion frame budget, keyboard/accessibility integration, Xray privacy, and three-repository migration evidence.
8. **Add the missing performance governance.** Own K3, freeze the byte-exact shell line, enforce user-visible/regression/escape/bulk rules, prohibit unsupported allocation claims, and publish the native-share census.
9. **Split the oversized critical beads.** At minimum split `001`, `020`, `035`, `036`, `047`, `057`, `060`, and—if it survives—`066`; publish an eight-worker priority schedule.
10. **Eliminate the dual-source and frozen-twin drift hazards.** Make the tracked normative set generated/hash-checked, mechanically freeze the bench tree, and retire the duplicate runtime after package baselines are re-pinned while retaining cited evidence and provenance.
