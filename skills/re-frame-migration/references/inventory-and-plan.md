# inventory-and-plan

Phase 0a — the **inventory-and-plan pre-flight**. Run it **before** any dep edit, before any compile, before the React-19 floor gate (Phase 0b) and M-0 (Phase 2). The output is a single written table: every v1 re-frame add-on library and every v1 re-frame feature the app uses, each mapped to its disposition + the rule(s) that govern it + whether acting is forced or optional. The migrator then acts in **one planned sweep** instead of marching the wall.

## Why a complete inventory comes first

re-frame2 removes and moves surfaces that v1 add-on libraries and v1 app code reach into. A compile reaches those breakages **one namespace at a time** — so a "swap the coord, compile, fix the first error, recompile, fix the next" loop turns into whack-a-mole, because the breakages live in **dependency source** (add-on jars, git/source deps, vendored code) the compiler only loads as it walks the require graph: each fix unblocks the compiler to reach the *next* broken namespace, which you had no warning about.

A complete up-front inventory collapses that loop. Read the dep tree and the relevant add-on **source** once, list every surface that will break, and produce a plan that fixes them all in a single pass — the compile then either passes or surfaces only the genuinely-unforeseeable.

**This phase is the umbrella that the other pre-flights feed into.** It does not restate them — it **drives** them as per-item checks:

- the **React-19 / Reagent-2 floor gate** (Phase 0b) → the downstream-JS/React-lib dimension of the inventory ([`setup.md` §The React-19 / Reagent-2 floor gate](setup.md#the-react-19--reagent-2-floor-gate-pre-flight--run-before-m-0));
- the **off-contract-namespace principle** (M-1) → the source-scan rule for "which `re-frame.*` namespaces does this add-on/app reach into?";
- the **add-on `re-frame.core/console` compile-gate** → the disposition for any add-on whose source `:refer`s / calls the removed `console` ([`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in));
- the **classpath-clean verification** → the proof, after the plan's removals/exclusions, that no v1 `re-frame/re-frame` jar still roots the classpath ([`setup.md` §Edge cases](setup.md#edge-cases)).

The inventory **enumerates** the items; those existing checks **classify** each one.

## Step 1 — Inventory the v1 add-on libraries on the classpath

Read the **full resolved dependency tree**, not just the top-level dep file — the breaking add-on is frequently a transitive (a git/source dep or a deeper edge), not a direct coord. Per cardinal rule 5 the **author** runs the tree command; the skill prints it and reads the pasted output:

- **tools.deps:** `clojure -Stree` (and `clojure -A:dev -Stree` / `:test` under each build alias).
- **Leiningen:** `lein deps :tree` (and `lein with-profile +dev deps :tree`).
- **shadow-cljs:** `npx shadow-cljs classpath`, or rely on the `deps.edn` tree if shadow reads from it.

From that tree, list every **re-frame add-on** — any library that depends on `re-frame/re-frame` or extends a re-frame surface. The common ones to expect (this is an EXAMPLE list of things to look for — **not** a fixed set, and **not** a set of per-library migration paths):

| v1 add-on (example) | What it is |
|---|---|
| `day8.re-frame/http-fx` (`:http-xhrio`) | the de-facto v1 HTTP effect layer |
| `day8.re-frame/async-flow-fx` (`:async-flow`) | async boot/wizard orchestration |
| `day8.re-frame/forward-events-fx` | event forwarding (transitive of async-flow-fx) |
| `day8.re-frame/undo` | undo/redo via app-db snapshots |
| `day8.re-frame/re-frame-10x` | the v1 devtools panel (dev dep) |
| `day8.re-frame/re-frame-test` / `day8/re-frame-test` | the v1 test harness |
| `re-frame-utils` / `re-frame-fx` and friends | community effect/cofx grab-bags |
| `shipclojure/re-frame-query` (the `re-frame-query` ns) | declarative server-state / query cache (a community query lib — the re-frame analogue of TanStack Query) |
| any other library on the tree that depends on `re-frame/re-frame` | a re-frame consumer that may reach a moved/removed surface |

> **Also a *pattern* to inventory, not just a coord: hand-rolled server-state caches.** An `app-db` slice shaped `{<id> {:status … :data … :error …}}` driven by an HTTP event + a slice-reading sub ([Pattern-RemoteData](../../../spec/Pattern-RemoteData.md)) is the no-lib equivalent of `re-frame-query`. Neither it nor `re-frame-query` is a *forced* item — nothing in re-frame2 breaks a query lib or a hand-rolled cache (they use only preserved `reg-fx` / `reg-sub` / `subscribe`; the lib's `reg-event-fx` *handlers* migrate under M-73 like any other) — so they migrate **only if the operator opts into modernisation**. The opt-in target is re-frame2 **resources** (`reg-resource` / `:rf.resource/*`, [Spec 016](../../../spec/016-Resources.md)); the conversion guide (a Type-B, ask-first semantic re-modelling — query keys → scoped resource identity, `invalidateQueries` → tag invalidation, component observers → owner leases) lives in the corpus at [`re-frame-query-to-resources.md`](../../../migration/from-re-frame-v1/re-frame-query-to-resources.md). Record both as **optional** dispositions in the plan; flag the hand-rolled-cache case as a *candidate* (lower-confidence detection) for the operator.

The principle is **scan ALL of them**, including any you don't recognise — a community add-on you've never heard of is exactly the one whose source reaches a removed surface and stalls the compile.

## Step 2 — Scan each add-on's SOURCE for v2-broken surfaces

This is the load-bearing step the march-the-wall loop skips. For each add-on from Step 1, read **its source** (the jar's `.cljc`/`.cljs`, the git-dep checkout, the vendored files on `:paths`) and grep for the v2-broken surfaces. The generic principles that classify a hit are already in the skill — apply them to the add-on's source, not just to the app's:

- **Off-contract `re-frame.*` requires (M-1).** The compatibility commitment covers **only** `re-frame.core` (the public façade) and the per-feature `re-frame.<feature>` artefact namespaces. **Any other `re-frame.*` require is off-contract and breaks on v2** — e.g. `re-frame.interceptor`, `re-frame.utils`, `re-frame.db`, `re-frame.router`, `re-frame.registrar`, `re-frame.loggers`. An add-on that `(:require [re-frame.interceptor ...])` or `(:require [re-frame.utils ...])` will not compile. Grep each add-on's source for `re-frame\.` requires outside `re-frame.core` / the artefact namespaces. → M-1 (this is the **principle**, not a fixed list — see [`breaking-changes.md`](breaking-changes.md) M-1 row).
- **The removed `re-frame.core/console` (add-on compile-gate).** v2's public façade defines **no `console`**, and there is **no back-compat shim**. Any add-on whose source `:refer`s `console` from `re-frame.core` or calls `(re-frame.core/console ...)` **fails to compile the moment re-frame2 is on the classpath** — independent of whether the app converted its own call sites. Grep each add-on's source for `console`. → [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in).
- **`unwrap` migrates to handler destructuring or a project-registered interceptor.** An add-on (or app) that `:refer`s `unwrap` from `re-frame.core` breaks — there is no public `re-frame.core/unwrap`. Grep for `unwrap`. The v2 destination is **not** a bare Var rename: an `unwrap`-using handler is already at the M-19 canonical `[<id> <payload-map>]` dispatch shape, so the simplest fix is plain handler destructuring (`(fn [_ {:keys [...]}] ...)`, no interceptor). A project that wants chain-wide event reshaping registers the shipped `unwrap-interceptor` value once and references it by id under `:interceptors` (chains are reference-only — EP-0022; `unwrap-interceptor` is never an inline chain entry). The `unwrap` → `unwrap-interceptor` *Var rename* (M-59) is only the pre-rename upgrade path, not the v1→v2 path.
- **React-19 / Reagent-2 downstream coupling (Phase 0b).** An add-on that ships **views** (a component kit, a Reagent-based widget lib) is subject to the same React-19 / Reagent-2 floor as the app. Bucket it React-19-ready / needs-bump / needs-replacement exactly as the floor gate does. → [`setup.md` §The React-19 / Reagent-2 floor gate](setup.md#the-react-19--reagent-2-floor-gate-pre-flight--run-before-m-0).
- **Transitive v1-`re-frame/re-frame` coord (classpath collision).** A v1-built add-on declares its own `re-frame/re-frame` dep, which collides with `day8/re-frame2` on the classpath. This is a **separate** failure mode from the `console` compile error (an add-on can hit both). The plan's disposition records the exclusion/upgrade, and the classpath-clean verification proves it cleared. → [`setup.md` §Edge cases](setup.md#edge-cases).

The point: walk this list against **every** inventoried add-on's source in **one pass**, so you know the full set of broken add-ons before the first compile — not one per recompile.

## Step 3 — Inventory the app's own v1 re-frame features

Separately from the add-ons, grep the **app's own source** for the v1 feature surfaces that trip an M/O-rule. **Scan committed source only** — drive the grep off git-tracked files (`git ls-files` / `git grep`), never a raw filesystem walk: gitignored build output (`compiled_dev/`, `run/`, `target/`, `resources/public/compiled*`, a prior attempt's built `main.js`) is **noise**, and on a migration RE-RUN a leftover compiled tree can falsely signal the work is already done — a stale `create-root` baked into a built `main.js`, or a "machines/Xray already present" hit that is really an abandoned branch's build output. This is the same trigger-surface scan `references/breaking-changes.md` indexes — run it up front rather than discovering each on a compile. The high-frequency ones:

> **This step is where the SILENT-fail rules get caught — and the only place.** Several surfaces below (`{:db fresh}` boots → M-15b, top-level `:dispatch` keys → M-8, the signal-fn `reg-sub` → M-71, `^:flush-dom` → M-16, **unary `reg-fx` handlers → M-51**) **compile clean** and break only at runtime ([`breaking-changes.md` §Failure-visibility axis](breaking-changes.md#failure-visibility-axis--loud-fail-vs-silent-fail-orthogonal-to-type-ab)). They live in **application code**, not dependency surfaces — so a dependency scan never sees them, and the compile gives you no wall to hit. This up-front app-source grep is the exhaustive sweep that finds them; the [boot smoke-test](runtime-smoke-test.md) is the only thing that later confirms the fix landed. Grep **every** site for each silent surface in one pass — do not march the wall.
>
> **One special case rides this same up-front sweep but is loud-at-runtime, not silent: M-70** (event interceptor chains outside metadata `:interceptors`). It compiles clean (march-the-wall misses it), but bare or positional-vector shapes throw at ns-load — aborting the offending ns (a boot machine's `reg-machine` after it never registers → the app hangs at boot). It needs the **same structural up-front grep** as the silent rows — a shape scan of every `reg-event-(db|fx|ctx)` call, flagging bare interceptors, positional vectors, and metadata-plus-vector forms regardless of interceptor identity — and the boot smoke-test ([`runtime-smoke-test.md`](runtime-smoke-test.md) row #6) catches any survivor. Shared detector, only the failure mode differs. See [`auto-cross-cutting.md` §M-70](auto-cross-cutting.md#event-interceptor-chains--metadata-interceptors-m-70--mechanical-loud-at-runtime-not-loud-at-compile).

| v1 feature surface in the app | Rule |
|---|---|
| Direct `re-frame.db` / `re-frame.utils` / other off-contract `re-frame.*` requires; `@re-frame.db/app-db` | **M-1** |
| `reg-global-interceptor` / `clear-global-interceptor` | **M-17** |
| `reg-sub-raw` | **M-18** |
| `reg-sub` with **two trailing fns** (the v1 two-function signal-function form) — the v1 signal fn returned `subscribe` reactions; the v2 `input-fn` returns a vector of query vectors. The two-fn shape **registers cleanly as a parametric sub**; the live-reaction return is only rejected at **first materialization** with `:rf.error/sub-input-fn-bad-return` (not at registration / ns-load) | **M-71** |
| `^:flush-dom` event metadata — classify each hit by **location**: inside a `reg-event-fx` effect map (**M-16a**, Type A automatic `:fx` rewrite) vs. a top-level `(rf/dispatch ^:flush-dom …)` in init / a callback / the REPL (**M-16b**, flag for human review — no `:fx` rewrite applies and `(rf/dispatch-later …)` throws at runtime). Grep is the same; the rewrite is not | **M-16a / M-16b** |
| Unary `reg-fx` handler `(fn [args] …)` — v2 invokes every fx with **two** args `(fn [_ args] …)`; the unary form binds the runtime's context-map to `args` and **silently drops** the real fx args (CLJS) — **silent** | **M-51** |
| Event interceptor chains outside metadata `:interceptors` — bare `(rf/reg-event-db :id mw/x handler)`, positional vector `(rf/reg-event-db :id [mw/x] handler)`, or metadata-plus-vector `(rf/reg-event-db :id {:doc "..."} [mw/x] handler)`. Compile-invisible — **loud-at-runtime, NOT silent.** Scan the **shape** of every `reg-event-*` call; flag any bare, vector, or metadata-plus-vector chain shape and migrate it to `{:interceptors [...]}` | **M-70** |
| Shared interceptor-**definition** namespace — a `mw/`-style ns that DEFINES interceptor *values* with the dropped v1 helpers (`(rf/enrich …)`, `(rf/after …)`, `(rf/on-changes …)`, `rf/debug`, `rf/trim-v`) and is `:require`d by many event nss. Scan the **definition** site (not just the chain entries that reference it); give the ns its own row and enumerate each defined interceptor id + the per-helper rule (re-author as a registered `reg-interceptor`, or fold into a flow / schema per the helper) | **M-21** |
| Shared coeffect-**definition** namespace — a `mw/`-style ns that DEFINES coeffects with `(rf/reg-cofx :id …)` suppliers (consumed at `(rf/inject-cofx :id …)` injection sites across many event nss). Scan the `reg-cofx` **supplier** site (not just the `inject-cofx` call sites); give the ns its own row and enumerate each defined cofx id + its reshape (value-returning supplier; each consumer re-declares the fact via `:rf.cofx/requires`) | **M-72** |
| top-level `:dispatch` / `:dispatch-n` / `:http` / user-fx keys in effect maps | **M-8** |
| `(reset! re-frame.db/app-db ...)` top-level seeding | **M-15** |
| `:initialize-db` / `:bootstrap` / `:app/reset` returning a wholesale `{:db fresh}` that carries a `:rf/runtime` key — **loud hard error** (`:rf.error/legacy-runtime-root`) | **M-15b** |
| `re-frame.alpha` requires | **M-23** |
| `re-frame.test` / `day8.re-frame.test` requires | **M-25** |
| `reg-event-error-handler` | **M-13** |
| `(rf/init!)` with no adapter — or no `init!` at all | **M-40** |

This list is illustrative; the authoritative index is [`breaking-changes.md`](breaking-changes.md) — grep it for any surface you find. The inventory's job is to run that scan **once, comprehensively**, not to re-derive the rules.

> **A shared namespace that DEFINES interceptors or coeffects is its OWN inventory row — never an aside.** One `mw/`-style namespace routinely *defines* several interceptors (M-21 — interceptor values built from the dropped `enrich` / `after` / `on-changes` / `debug` / `trim-v` helpers) **and** several coeffects (M-72 — `reg-cofx` suppliers, consumed by `inject-cofx`), and is then `:require`d by many event namespaces. That makes it **high-leverage**: one definition feeds N consumers, so the blast radius of a single missed or mis-reshaped conversion scales with the fan-out — a silently-dropped or wrongly-reshaped `time` / `timestamp` coeffect corrupts the durable state of *every* event that injects it. The consumer-side sweeps above only find the *uses* — the M-70 chain-shape scan sees the `mw/x` chain entries, an `inject-cofx` grep sees the injection sites — and merely **imply** the definition namespace, which under-counts it. So scan the **definition sites directly**: every `(rf/reg-cofx …)` supplier and every interceptor *value* authored from a dropped M-21 helper. Give each definition namespace its **own enumerated row(s)** — list every interceptor / coeffect id it defines, the rule that governs each (M-21 → re-author as a registered `reg-interceptor` / flow / schema; M-72 → value-returning supplier + each consumer's `:rf.cofx/requires` declaration), and the **consumer count** (how many files `:require` it). That fan-out is the row's true weight: the definition namespace feeding the most consumers is exactly the entry whose silently-dropped member corrupts the widest surface.

## Step 4 — Produce the per-item migration plan

Emit a single table — one row per inventoried add-on and per app feature — with, for each:

1. **Item** — the add-on coord (`day8.re-frame/http-fx`) or the app feature (`reg-global-interceptor` at `src/app/core.cljs:42`).
2. **What breaks on v2** — the scanned surface (e.g. *"`:refer`s removed `re-frame.core/console`"*, *"`:require [re-frame.interceptor]` off-contract"*, *"views target React 18"*).
3. **Rule(s)** — the governing `M-N` / `O-N` id(s), or the named principle (off-contract-ns / console-gate / classpath-collision / floor-gate) where no `M-N` applies.
4. **Forced vs optional** — does the project **compile** with the item unchanged? A `console`-referencing add-on or an off-contract require is **forced** (compile-blocker). An opt-in modernisation (O-16 conversion path) is **optional**. The forced/optional split is the one most worth getting right — it's what separates "must do before compile" from "do at leisure."
5. **Disposition** — **CONVERT** (to a v2-native effect/machine — e.g. http-fx → `:rf.http/managed`, async-flow-fx → `reg-machine`), **PATCH** (mechanical M-rule fix to a kept lib — e.g. swap the off-contract require, drop the `console` `:refer`), **DROP** (the feature is unused — remove the add-on), **REPLACE/REWRITE** (no drop-in successor — re-implement against a v2 surface), **UPSTREAM** (PR the add-on to a v2-compatible release), or **FIX-IN-PLACE** (the app's own source).
6. **Replacement target** — the v2 surface the disposition lands on (`:rf.http/managed`, `reg-machine`, `clojure.core/update-vals`, Xray, …).

Then state the **recommended ordering** — which removals/exclusions unblock the compile (they go first), so the post-M-0 compile gate is actually reachable. A `console`-referencing add-on and a classpath-colliding transitive both must clear before the compile can surface real application-code breakage.

> **Stay generic — no per-library migration sections.** The conversion details for the *named* add-ons live in their own opt-in leaves (`http-fx` → [`http-fx-to-managed-http.md`](http-fx-to-managed-http.md), `async-flow-fx` → [`async-flow-to-machines.md`](async-flow-to-machines.md)). For every **other** add-on — including any community library not in the example list above — the disposition is driven by the **generic** rules: scan its source, classify each hit by the principle (off-contract-ns / console-gate / M-59 / floor-gate / classpath-collision), and pick a disposition. The inventory does **not** carry a bespoke migration path per library; a library may appear in the example "scan these" list, but the rules that move it are the generic ones.

## What the plan unblocks

With the table in hand the migrator runs M-0 + the React-19 bump, applies every **forced** PATCH/DROP/CONVERT in one sweep, and only then asks the author to compile. The expected result is the compile passes (or surfaces only genuinely-novel breakage) instead of marching one broken namespace at a time. The plan also becomes the spine of the Phase-6 report — every disposition is already written down.
