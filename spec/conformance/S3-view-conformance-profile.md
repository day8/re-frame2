# S3 view-substrate conformance profile

**Status:** Reference · owned by `rf2-vxgfnd.95.10` (the S3 conformance gate).

This is the single authoritative catalogue of the frozen S3 surface of
`re-frame.ui` — the compiled-view substrate's events + debugging-as-consumer
stage. It fixes *what* S3 froze (the verbs, the host behaviour, the callback and
batching laws, the evidence projections, and the explicit non-surfaces) and
names, for each row, the gate that proves it. The runtime behaviour is proven by
those gates (browser / JVM / elision suites), not re-asserted in prose here.

The frozen surface and the gate references are kept honest by an executable drift
guard, `implementation/ui/test/re_frame/ui/s3_conformance_profile_jvm_test.clj`:
its `frozen-s3-verbs`, `frozen-s3-view`, and `frozen-react-wrappers` maps are the
source of truth this document restates. All three rosters are bound row by row:
the guard fails if a §1 row is missing or duplicated, or if a row stops naming
the error, gate, view-id, host primitive, kind, contract fragments, or proof
homes its map entry fixes — so deleting a row, or swapping a contract or proof
home between rows, is red.

## 1. Frozen S3 surface

The frozen S3 surface is three closed rosters: the ten compiler-owned **verbs**
of the `re-frame.ui` facade, the one framework-authored **view** (`route-link`),
and the seven-wrapper **React interop tier** (`re-frame.ui.react`). Each row names
its kind and contract, its direct-call behaviour where applicable, and the gate or
proof home that proves its real runtime behaviour.

### 1.1 Compiler-owned verbs

Each S3 verb is a compiler-owned form recognised structurally in a `defview`
body. Its `re-frame.ui` facade entry is a `(defn …)` that exists for symbol
resolution only — a **direct call fails loud** with the listed error id, because
the real work happens at compile time in the analyzer/emitters and at runtime in
`hooks`/`runtime`/`rules`.

| Verb | Contract | Direct-call error | Proven by |
|---|---|---|---|
| `ui/local` | `[value set! update!]` three-tuple; `set!` stores exactly (a stored fn is a value); `update!` applies `(f current & args)` to the **latest host state** so same-turn writers compose; host-only (render-phase use is a dev error); JVM exposes the initial value, `set!`/`update!` raise `:rf.error/jvm-host-op` | `:rf.error/ui-tree-malformed` | **G-15** |
| `ui/effect` | `(effect [deps…] …)` / `(effect :connect …)`; value-deps compared `rf=`; return fn is the cleanup; StrictMode replay must be idempotent-safe; JVM records capability metadata only | `:rf.error/ui-tree-malformed` | **G-6** |
| `ui/dispatch-fn` | stable per-view committed-frame dispatcher for imperative/foreign callbacks; retargets at commit; fails loud in every non-connected state (`:rf.error/dispatch-disconnected`) | `:rf.error/ui-tree-malformed` | **G-8** |
| `ui/event` | committed `:on-*` handler with the live native event; result is the event vector to dispatch or `nil` (a filter); at a compiler-proven controlled site a synchronous vector result rides the **sync door**; any other synchronous result is a loud diagnostic | `:rf.error/ui-tree-malformed` | **G-8** |
| `ui/handler` | imperative committed callback (result ignored); per-site stable; the explicit spelling of the bare-fn shorthand and the C-13a internal-view/foreign fn-prop | `:rf.error/ui-tree-malformed` | **G-8** |
| `ui/render-fn` | pure compiled render-slot callback for an internal library seam; body is lexically visible and compiled by both emitters; `sub`/`local`/`effect`/dispatch/hooks inside are compile errors | `:rf.error/ui-tree-malformed` | **G-16** |
| `ui/slot` | compiler-owned invocation of a `ui/render-fn` value (or `nil`); a non-render-fn value is a loud didactic error; output joins the surrounding children like any child | `:rf.error/ui-tree-malformed` | **G-16** |
| `ui/spread-safe` | the literal safe-spread policy: owned/structural/identity keys (`:key` `:ref` `:value` `:checked`, owned `:on-*`) are denied in **every build**; `aria-*`/`data-*`/`title`/class/style pass; a controlled site under the policy form **retains** the sync door | `:rf.error/ui-spread-outside-template` | **G-17** |
| `ui/error-boundary` | explicit error component; `:fallback` view renders on a caught render/lifecycle throw; `:on-error` dispatches after the failing commit through a live frame; `:reset-key` (compared `rf=`) clears the error | `:rf.error/ui-tree-malformed` | **G-6** |
| `ui/client-only` | browser-only subtree with a **mandatory capability-free fallback** (compiler-checked); JVM/SSR render the fallback, the browser renders the client subtree | `:rf.error/ui-tree-malformed` | **G-7** |

### 1.2 The framework-authored view: `route-link`

`route-link` is the one framework-authored S3 view: an ordinary compiled
`defview` over the routing-owned late-bound link seam (`:routing/link-model` /
`:routing/activate-link!`) — **not** a compiler intrinsic and **not** a fail-loud
stub. Its var carries `:rf.ui/view` metadata and the registered view-id
`:re-frame.ui/route-link`, so it is consumed like any compiled view and a direct
call does **not** fail loud. It renders a real `<a href>`; a plain left-click
dispatches with `:source :router`, while native/modifier clicks defer to the
browser. Rendering it without the routing artefact installed fails loud with
`:rf.error/routing-artefact-missing`. The click/href law and full conformance
matrix live in Spec 012 (routing owns the law); the JVM/SSR shell and
client-render proof homes are named here.

| View | Kind | Contract | Absent-artefact error | Proven by |
|---|---|---|---|---|
| `ui/route-link` | ordinary compiled `defview` (`:rf.ui/view`, view-id `:re-frame.ui/route-link`); not a fail-loud stub | real `<a href>`; plain left-click dispatches `:source :router`; native/modifier clicks defer to the browser | `:rf.error/routing-artefact-missing` | JVM `re-frame.ui.route-link-jvm-test`; CLJS `re-frame.ui.route-link-dom-cljs-test` |

### 1.3 The React interop tier (`re-frame.ui.react`)

The frozen `re-frame.ui.react` interop tier is six wrappers (Spec 004 §The
React interop tier) for compiled views that must participate in a *foreign* React
world — an exported `defview` living inside a foreign parent, or a foreign widget
whose API demands contexts, ids, effects, or code-splitting. It is **not** a second
state or reactivity model, and the roster is closed. Five of the six
(`use-effect` … `use-id`) are **host hooks**: `(defn …)` stubs the compiler
recognises by resolved head and lowers to `re-frame.ui.hooks/*` under the
position law (a hook site is legal only where it evaluates unconditionally,
exactly once per render); like the verbs, a **direct call fails loud** with
`:rf.error/ui-tree-malformed`. `lazy` is a **def-level macro** (a `React.lazy`
constructor), exempt from the position law but a compile error inside a view
body/template. All six ride the same proof homes:
`re-frame.ui.react-interop-jvm-test` (JVM structural subset + compile-time
position law + HMR signature) and `re-frame.ui.react-interop-dom-cljs-test` (real
React client behaviour).

The everyday DOM-node ref is **not** in this tier — it is the substrate-native
`ui/ref` (a `re-frame.ui` public, promoted out of the interop tier, rf2-u53yy.9;
its lowering target stays the `re-frame.ui.hooks/use-ref` runtime fn). It is a
`re-frame.ui` non-verb public (catalogued in the facade exact-set), lowered by
the same finite-site machinery and proven by the same two react-interop suites.

| Wrapper | Kind | Contract | Direct-call behaviour |
|---|---|---|---|
| `react/use-effect` | host hook (`useEffect`) | passive after-paint effect; setup returns a cleanup; per-slot `rf=` deps (the one effect-dependency doctrine — value semantics, shared with `effect`); `[]` = connect-only; StrictMode-replay-idempotent — the `effect` contract in a foreign spelling | fails loud `:rf.error/ui-tree-malformed` |
| `react/use-layout-effect` | host hook (`useLayoutEffect`) | after DOM mutation, **before** paint — the measure-before-paint door; same setup/cleanup/per-slot-`rf=`-deps contract as `use-effect` | fails loud `:rf.error/ui-tree-malformed` |
| `react/use-effect-event` | host hook (`useEffectEvent`, React 19.2) | returned fn always sees the latest render's committed values; its identity is **not** stable (a fresh fn each render); takes no deps; must never appear in a deps vector nor be called during render | fails loud `:rf.error/ui-tree-malformed` |
| `react/use-context` | host hook (`useContext`) | reads a **foreign** Context object's current value (theme/i18n/router); internal state uses `frame-provider`/`sub`, never React context | fails loud `:rf.error/ui-tree-malformed` |
| `react/use-id` | host hook (`useId`) | host-generated tree-positional id token; SSR determinism rides the root contract; JVM yields a deterministic inert string | fails loud `:rf.error/ui-tree-malformed` |
| `react/lazy` | def-level macro (`React.lazy`) | code-splitting over a foreign `load-thunk`, legal only where foreign heads are; optional `{:fallback tpl}`; not a loading-orchestration surface | compile error if called inside a view body/template (not a direct-call stub) |

The S1/S2 forms S3 builds on — `defview`, `custom-element`, `sub`,
`frame-provider`, `mount`/`create-root`/`hydrate-root`, `html`, `raw`, `spread`
— keep their earlier contracts unchanged and are catalogued in the Spec 004
family; the verbs, the `route-link` view, and the React interop tier above are
what froze at S3.

## 2. Host behaviour

**JVM structural render (Tier 1, 06 §1 subset).** A `defview` renders to a
versioned canonical tree; `sub`/props/branches/lists/fragments and event
*intent* are faithful. `local` exposes its initial value; `effect`/`dispatch-fn`
record capability metadata and their invocation raises `:rf.error/jvm-host-op`;
`slot` output participates structurally. Handlers are recorded as intent and
dropped by normalization N.

**CLJS mounted render (Tier 3).** Real React via `react-dom/client`; committed
callbacks read the winning commit and retarget only at commit; the controlled
input door commits synchronously before paint. Proven by the mounted DOM suites
and, in real Chromium **and** WebKit, by **G-8**.

**Cross-host parity.** The compiled-view parity corpus
(`parity_fixtures.cljc` + the JVM/CLJS corpus tests) proves normalized
structural equivalence of every grammar form — including the S3 render-slot
shape — across the JVM tree and the CLJS `react-dom/server` emitter. The
component-library **proof pack** (`implementation/ui/proof-pack/`) is rendered
and exercised by its own mounted DOM suite (`library-dom-cljs-test`), not this
corpus — four of its six views are host-bearing and have no JVM side to
compare, so the corpus keeps only the dual-emitter grammar shapes it genuinely
owns.

## 3. HMR signature, event batching, callback law

**HMR signature.** A view's hook signature (its ordered `sub`/`local`/
`effect`/slot sites) rides the Fast-Refresh stable shell in dev; a
hook-incompatible edit remounts. The dev shell and its revision/listener/
descriptor/inner-Fiber machinery are `goog.DEBUG`-gated and elide in production
(the `hmr-*` sentinels, **G-11**).

**Event batching.** Ordinary handlers queue on the batched path: N native events
run all writes, then **one** read/render batch — the batch closing at the next host
checkpoint, not at drain quiescence, so a drain's epochs always render together while
drains reaching the same checkpoint may share a batch (rf2-vxgfnd.166). The
**controlled-input sync door** is the exception — at a compiler-proven controlled
site a literal vector or a synchronous `ui/event` vector result drains inside the
native `dispatchEvent`, committing before paint so the caret/IME are safe. The
sync door and its counterexample (the batched path) are proven by **G-8**.

**Callback law.** Every committed callback (`ui/event`, `ui/handler`,
`ui/dispatch-fn`) is **per-site stable** (attach once) and reads the **committed**
values (the stale-closure boundary law); it retargets only at commit and fails
loud after disconnect. A per-row committed callback that captures a loop binding
is a compile error — extract a keyed child view and pass the datum as a prop.

## 4. Evidence projections

S3 view evidence is a dev/test-only projection with **no production egress**
(proven under **G-7/G-11**). The versioned view-manifest projection
(`re-frame.ui.tool/view-manifest`, `:rf.ui.tool/version`) exposes the literal
props schema, per-prop docs/defaults, declared render-slot sites, interop sites,
capability cost, and site counts — the one projection Story, docs, Xray, and
agents consume. Sibling projections: `mounted-views`, `explain-render` (the
`:subscription`/`:hmr`/`:disposed` render-cause set), `view-dependencies`. The certified S3
cumulative cause union is exactly `#{:subscription :hmr :disposed}`; host-state writers
(`local`) are proven through G-15's writer-composition and render-count evidence,
**not** a cumulative-set `:local-state` cause row. A `:local-state` render cause DID
ship at S6 (`rf2-vxgfnd.98.1`) — as one kind in the per-commit `:rf.view/causes`
vector on the reactive substrate's committed-instance record, under
`re-frame.ui.tool/schema-version` 3 (EP-0033 §S6 view-evidence delta) — but this S3
`explain-render` cumulative set stays the certified S3 surface. All of it is
absent from advanced production bundles (`tool_evidence_elision_prod_test`,
`tool_view_elision_prod_test`, `sub_overrides_elision_prod_test`).

## 5. Non-surfaces (the wall)

S3 deliberately does **not** ship, and a consumer that appears to need one gets
an API redesign or a named interop boundary, never a substrate exception:

- no ratoms/cursors/reactions or generic `IDeref` observation;
- no runtime hiccup interpreter in core, no parts interpreter, no `ui/tpl` over
  runtime data;
- no arbitrary dynamic heads / blanket `ui/element`; no registered runtime-open
  `ui/view` invocation;
- no Form-2/Form-3, `component-did-*`, `:on-mount`/`:on-unmount`; no render-phase
  mutation or unmanaged async work; no memo opt-out;
- no fn-overloaded `set!` (a stored fn is a value); no reset-key/derived `local`
  (a scheduled spike);
- no second handler language (the compiled event-template projection is demoted);
- no substrate theme registry / widget controllers / async loaders / focus
  policy; no CSS/Bootstrap ownership;
- no production egress of any view evidence, manifest, or diagnostic string.

## 6. The gate roster (S3 arms)

The full G-1..G-18 roster lives in `docs/EP/EP-0034` §4 and the 07 §5 gate
roster. The S3-specific arms this profile certifies:

| Gate | S3 arm |
|---|---|
| **G-6** | `effect`/`error-boundary` lifecycle + StrictMode/Activity ownership baselines |
| **G-7** | dev↔prod **structural** equivalence over the generated corpus (Layer 1 — wired by the advanced parity-corpus prod-elision proof, debug off); `client-only` fallback; committed-DOM/events/owner **mounted** behaviour named-open under the S6 leaf (`rf2-55zsd`) |
| **G-8** | real Chromium **and** WebKit controlled-input matrix — IME composition, caret restoration, event ordering, pre-paint — through the reusable event-prefix component, with the deliberate async-door regression as the tooth |
| **G-11** | exact production absence of the debug + absence rosters (view evidence, manifest, HMR shell, source-coord, `re-frame.ui.test`) from advanced bundles |
| **G-15** | atomic-local writer matrix — N same-turn `update!` writers land; fn-value `set!` stores exactly; **mixed `update!`+dispatch**; StrictMode replay; JVM typed failure; writer composition + render count (not a cumulative-set `:local-state` cause row — that cause ships at S6 in the per-commit `:rf.view/causes` vector); HMR ride |
| **G-16** | render-slot parity across both emitters; keyed reorder under slots; purity diagnostics inside slot bodies; manifest slot sites |
| **G-17** | safe-spread owned-key rejection in dev **and** advanced builds; `aria-*`/`data-*` pass; the policy form retains the sync door where general `spread` forfeits it |
| **G-18** | library façade isolation — an advanced build importing one view retains no unused siblings (fixture-first; a substrate packaging change is justified only if it fails structurally) |

## 7. Conformance grading

An S3-conforming host emits, for the frozen surface above: the direct-call
fail-loud contracts (§1), the JVM structural subset and CLJS mounted behaviour
(§2), the HMR/batching/callback laws (§3), the dev-only evidence projections with
proven production absence (§4), and none of the non-surfaces (§5). The gates in
§6 are the executable acceptance; `scripts/test-fast-pr.sh` plus the S3 CI matrix
run them.
