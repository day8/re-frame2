# 07 — Testing: the testability contract

**Status:** final · 2026-07-11. Three structural facts do the work: views are pure
AST→output functions; handlers are data; ownership begins only at commit.

## 1. The pyramid

| Tier | What | Runs on | Speed |
|---|---|---|---|
| 1 | Headless view tests — structural tree + event-vector intent | JVM (and node) | ms, no DOM |
| 2 | Pure dataflow — `compute-sub`, handlers, machines (unchanged re-frame2) | JVM | ms |
| 3 | Mounted contract fixtures against real React | browser/jsdom CI | s |
| 4 | Story variants (CLJS-unit-test shape per repo ruling) | CLJS | s |
| 5 | Gates — parity, elision, bundle, benchmarks, equivalence | CI | build |

The trio's ×3 adapter matrix collapses into the **new-UI conformance suite**, pinned
once; the frozen-Reagent compatibility tier keeps its own distinct **compatibility
suite + one smoke** (08 §5 Adapters) — two named suites, not one.

## 2. `ui.test` — the contract (one namespace, one table)

| Fn | Contract |
|---|---|
| `(render root-or-view opts)` | run the real view against a real frame on the JVM → structural tree. `opts`: `{:frame f}` or `{:app-db v}` (test frame minted), `{:props p}`, **`{:sub-overrides {query value}}`** (the explicit JVM override door — 03 §3; not "the same mechanism" as the CLJS context, and not pretended to be) |
| `(find tree selector)` / `(find-all …)` | structural queries over Tier-1 trees (tag, view id, attr predicates) |
| `(query root css-selector)` | **Tier-3 DOM query** on a mounted root (the live-DOM counterpart of `find`) |
| `(text node)` / `(attrs node)` | projections |
| `(frame opts)` | mint a test frame (app-db seed, registrations from the loaded namespaces) |
| `(dispatch! frame event)` | real dispatch + drain |
| `(with-root [r root-form] …)` | CLJS Tier-3: return a Promise; await the initial real-React mount, the body value/Promise, and total teardown of the connected test-owned root/container on every exit. The Promise resolves to the awaited body value |
| `(flush!)` / `(flush! thunk)` | CLJS: return a Promise; run the optional thunk inside React 19 `act`, then alternate framework drains and React commits to a fixed point — the sole public test flush. JVM: synchronously drain the headless ViewCell registry and return nil. It is global across test roots; there is no public production `ui/flush!`. The open-drain guard throws synchronously before Promise construction |
| `(flush-presence!)` | advance presence transitions without wall-clock (02 §7) |

At S2, Tier-3 tests drive framework state with `dispatch!`. DOM mechanics already owned
by the host or a foreign component use platform properties and native events directly;
compiled event-vector delivery through `dispatchEvent` becomes live with S3's committed
handlers. No gesture DSL sits between a test and the browser.

Every CLJS mounted-test Promise is awaited before assertions or another mounted
operation begins. A forgotten await fails loudly on the next public `with-root`/`flush!`
with `:rf.error/ui-test-overlapping-act`; it never creates overlapping React `act`
scopes. Owner cleanup is still serialized so reporting that misuse cannot strand a
root. If both the body and teardown fail, the body error stays primary and carries the
cleanup error as diagnostic evidence.

Naming is uniform everywhere. **The `.cljc` constraint is stated:** Tier-1 requires the
events/subs a view touches to be `.cljc` — an authoring constraint the guide teaches,
not a surprise.

**JVM semantics under test follow the 06 §1 subset:** a Tier-1 render of a view using
`local` sees initial values; invoking a `local` setter or expecting effects in Tier 1 is
a typed error pointing at Tier 3. Structure, subs, branches, lists, and event intent are
fully faithful — which is what Tier 1 is *for*.

## 3. Tier-3 contract fixtures (the ownership walkthrough as tests)

`flush!` makes them deterministic. The matrix:

abandoned first mount retains nothing · interrupted update keeps committed set ·
render→commit gap corrects before paint · conditional attach/detach at commit only ·
sibling shared nodes without callback clobber · first-mount fan-out: N rows share
parent computation via the slice-scoped memo table (03 §3) · parent-deletes-child never
runs a stale query · frame swap acquires-before-releases, handlers retarget at commit ·
StrictMode settles to one owner per site · Activity hide/reveal and real-unmount
fixtures (both kept) assert the same immediate cleanup fact —
`:disconnected {:reason :unknown}` — then the retroactive-annotation flow (03 §4):
reveal reacquires + corrects and proves the prior interval was a hide; explicit
host/root teardown proves `:unmounted`; no fixture asserts distinct immediate end
states · `dispatch-fn` fails in every
non-connected state · observation-target races: override→real and real→override
between render and commit · override records show `:owned? false` and real ownership
absent; sub assertions bypass overrides · frame ENSURE preflight: conditional/list
sites rejected at compile; initial events exactly once across abandoned render,
StrictMode, HMR, error recovery · root/frame matrix: 1×1, 1×N, N×1, N×N, nested
providers, out-of-order hydration, one failed root isolated · registration replacement
leaves no pinned cell · HMR matrix (03 §10) — runs Stage 2 · adapter/frame disposal
idempotent + total (10k-cycle leak checks) · late callbacks across unmount, Activity,
frame destroy, adapter disposal, HMR replacement, root teardown · presence: exit
retention, re-entry interruption, reduced motion, hydration no-fabricated-enter, inert
exits, terminal exactly-once cleanup, fake-clock completion · `ui/html`: escaping
bypass exactly at the call, both emitters agree, everything else still escapes ·
controlled-input synchrony: caret stability, IME composition, rapid typing under the
sync door (02 §3) · interop set (foreign components, render props, refs, portals
retaining frame context, error boundaries incl. reset-key + on-error timing, lazy
suspension) · Promise boundary: awaited body result, body rejection, cleanup-only
rejection, body+cleanup primary preservation, exact act-environment restoration,
forgotten-await overlap before allocation, nested-root LIFO reclamation · recursive
fixed point where a commit marks more framework work and one `flush!` Promise waits for
the second drain/commit cycle.

S-3 validated this matrix's ownership semantics against a stand-in derivation graph;
**grafting the pure cold probe onto the real sub-cache (memo/trace/dispose machinery)
remains a named open gate** — the matrix reruns against the real cache in Stage 2
before any conformance claim.

## 4. Generative parity (scoped)

Props schemas generate **props**; they cannot generate an app-db satisfying arbitrary
subs — apps supply state generators/fixtures for sub-reading views (the harness makes
this a one-liner around `ui.test/frame`). With inputs supplied: JVM tree vs CLJS
`react-dom/server`-equivalent compare as normalized semantic nodes over the structural
subset; memo invariants (`rf=` props ⇒ no **prop-driven** re-render and identical
output); value-stabilization invariants (equal results ⇒ identical references).
Fingerprints pin build identity; the corpus doubles as the hydration-parity suite,
including the multi-root failure-isolation fixture.

## 5. The gate roster

| Gate | Asserts |
|---|---|
| G-1 direct-render parity | pure view within 10% of hand-written JSX CLJS (p50/p95), output inspected; µs-scale p95 is environment-dominated (S-1-measured), so the gate uses a noise-robust estimator (alternating interleaved rounds, median-of-rounds); the S-1 feasibility PASS was produced under the earlier best-round/min estimator, so a rerun under this revised estimator is a named open gate — plus an **emitted-JS golden test** pinning direct `jsx` calls (a CLJS-var-bound jsx fn silently reintroduces IFn dispatch under `:advanced`, ~5–8% — S-1's trap) |
| G-2 AOT peer | ≥ UIx-AOT parity on pure views; reactive one-read ≤ 15% update-p95 over raw correct `useSyncExternalStore` |
| G-3 multi-read scaling | 1/4/8/16 sites: one hook, one invocation, one notification per epoch |
| G-4 equality no-op | `rf=` results ⇒ zero revisions, zero **prop/sub-driven** renders, stable references |
| G-5 epoch fan-in | 8 deps in one event ⇒ 1 render; 8 epochs ⇒ 8 |
| G-6 abandonment/disposal | 10k abandoned / mount-unmount / Activity cycles ⇒ baseline owners, zero retention |
| G-7 dev/prod equivalence | per generated shape + pairwise capabilities + high-risk triples (not powersets); committed DOM/events/owners/cleanup/hydration agree, debug off; StrictMode-dev settles to prod outcomes |
| G-8 input latency & correctness | **caret/IME correctness first** under the sync door; then event→commit within 10% of hand-written React p95; one commit per input; the real-browser matrix (Chromium/WebKit IME, caret-on-restore, paint timing) is a named open gate — S-5's evidence is jsdom-only |
| G-9 list updates | keyed 1k rows: one entity change renders its row + true dependents; stable handler identity; no retained lazy seqs |
| G-10 bundle | kernel ≤ 4 KB gz; counter ≤ React + 6 KB gz; relative targets vs UIx-adapter / slim; symbol-reachability evidence |
| G-11 elision | exact absence of debug + absence rosters, including `re-frame.ui.test` and its direct React-`act` boundary from an advanced production bundle |
| G-12 dependency isolation | no Reagent/UIx/Helix/slim at Maven/npm; no JVM renderer reachable from browser entries |
| G-13 push falsification | the 500-view fan-out bench — exists to *falsify* the committed push economics (05 §3); its failure reopens the design, it does not toggle a fork |
| G-14 compile budget | `defview` expansion p95; watch-loop rebuild delta on the dashboard fixture; guide-fixtures CI cost bounded |

Methodology: identical fixtures, distributions not best runs, pinned browsers,
cold+warm, dev overhead separate, no precomputed-props cheating.

## 6. Debug-quality fixtures

The 04 §8 roster runs as tests, including: occurrence-path row disambiguation,
loss-accounting presence on every buffer, restore causes naming operation+target with no
history rewrite, override honesty, and the no-monkey-patch grep.

## 7. The guide as a test surface

Every guide example compiles and runs as a fixture; an example needing internals
explained is an API defect. The examples corpus feeds the demand-bar table (08 §3 —
produced **before Stage 1**). Guide heuristics ("flush twice = two tests") are labeled
heuristics, not contracts.
