# decision-record

A fill-in template for the **Phase 1 locked-decision record**. The engineer copies this template into their port's repo (typically as `DECISIONS.md` at the repo root), fills in each block before any implementation code is written, and commits it.

Every Phase 2 step references this record. If a Phase 2 step forces a Phase 1 decision to change, the record is **revised in writing** before Phase 2 resumes — no silent overrides.

---

## Template — copy from here

```markdown
# DECISIONS — <port name>

> Phase 1 decision record for the re-frame2 port at <port-repo-url>.
> Captured before Phase 2 implementation began.
> Locked: <YYYY-MM-DD>.

## Spec pin (load-bearing — record before D1)

- **Upstream:** `https://github.com/day8/re-frame2`
- **Pinned commit / tag:** <SHA-or-tag>
- **Pin verified:** `git -C <path-to-re-frame2> rev-parse HEAD` == `<SHA-or-tag>` on <YYYY-MM-DD>
- **Origin verified:** `git -C <path-to-re-frame2> remote get-url origin` == `https://github.com/day8/re-frame2(.git)`

Every spec citation in this record (and in subsequent code) is against the pinned hash. If the engineer later pulls a newer `day8/re-frame2` HEAD, that's a deliberate retarget event — add a Revision log entry and re-walk the affected decisions.

## D1. Target host language

- **Host:** <language + version, e.g. "TypeScript 5.4">
- **Runtime targets:** <e.g. "browser (Chrome 100+), Node 20+">
- **Build tool:** <e.g. "Vite + tsc">

## D2. Substrate / React binding

- **Substrate:** React + VDOM (fixed — every in-scope host binds against React).
- **React binding:** <e.g. "Reagent atop React" / "UIx" / "Helix" / "Feliz / Fable.React" / "kotlin-wrappers React" / "rescript-react" / "scalajs-react / Slinky" / "purescript-react-basic">
- **Reactive container library:** <e.g. "Reagent ratom" / "useSyncExternalStore-backed atom store" / "signal cell" / "MutableStateFlow-shaped cell">
- **Render-tree shape:** <e.g. "hiccup" / "JSX-as-data / snabbdom vnodes" / "Feliz Html.div DSL" / "R.div [...]" — must serialise for SSR + tooling>

## D3. Scope — which EPs ship in v1

| EP | In v1? | Notes |
|---|---|---|
| **Required core** (000 / 001 / 002 / 004 / 006 / 009 / 015) | yes | non-negotiable — 015 Data Classification is v1-required, not D3-gated (see D5b) |
| **Q1 — State machines (005)** | <yes / no> | <which sub-capabilities — flat / hierarchical / always / after / tags / parallel-regions> |
| **Q2 — Routing (012)** | <yes / no> | |
| **Q3 — SSR (011)** | <yes / no> | |
| **Q4 — Schemas (010)** | <yes-runtime-schema / yes-via-host-types / no> | <which library if runtime-schema> |
| **Q5 — Stories (007)** | <yes / no> | usually no for v1 |
| **Q6 — Tool-Pair adapters** | <yes / no> | gates the optional derivation/process **graph-inspection** surface (EP-0014, `spec/Derivations.md`) — the algebra view of subs/flows/resources/routes/machines as one `{:mode :nodes :edges}` graph. No new authoring primitive; only built if you ship inspection tooling. |
| **Q7 — AI-Audit grading** | <yes / no> | |
| **Q8 — Flows (013)** | <yes / no> | gates the `:flow/*` conformance family |
| **Q9 — Managed HTTP (014)** | <yes / no> | gates the `:rf.http/managed` conformance family |
| **Q10 — Resources (016)** | <yes / no> | post-v1; **presupposes Q9** (resource/mutation `:request` lowers onto `:rf.http/managed`). Gates the `:rf.resource/*` / `:rf.mutation/*` family — **corpus-behind** (spec-mandated but no fixtures yet; verify against `spec/016-Resources.md` + own unit tests). usually no for v1 |

## D4. Always-required realisation decisions

> Sub-ids mirror Implementor-Checklist Part 2 1:1 — Foundation F1–F6, State storage S1–S3, Subscriptions Sub1–Sub2, Views V1–V3, Tracing T1–T3, Errors E1–E2. Fill in every block; all are always required (T2's bridge may be omitted — record the choice).

### Foundation (F1–F6)

#### F1 Identity primitive

- **Mechanism:** <e.g. "branded string types with naming convention" / "polymorphic variants" / "sealed-class hierarchies">
- **Required properties verified:** stable / namespaceable / value-equal / cheap / serialisable / human-readable / reflective — <confirm all seven, name the helper(s) that provide each>

#### F2 Persistent data structures

- **Library:** <e.g. "Immer" / "Immutable.js" / "native Clojure persistent collections" / "native F# Map/Set">
- **Snapshot mechanism:** <e.g. "pointer swap" / "Immer's `produce` drafts" / "deep-copy fallback for X cases">

#### F3 Reactive substrate

- (Locked in D2.)

#### F4 Effect-handling primitive

- **Sync default:** <yes — fx run inline when the handler returns / no — different model>
- **Async re-entry:** <e.g. "Promise.then → :dispatch" / "queueMicrotask → schedule dispatch">
- **Reserved framework fx the port ships:** the three unqualified reserved fx-ids — `:dispatch`, `:dispatch-later`, `:raise` — registered exactly as-is (bare, not under `:rf/*`; per Conventions §Reserved fx-ids and `cardinal-rules.md` §10). `:raise` only resolves inside a machine action's `:fx`; outside one it is unbound (`:rf.error/no-such-handler`). <Any optional framework fx in scope per D3 — e.g. managed HTTP ships `:rf.http/managed` & family under `:rf.http/*` (Q9), routing ships `:rf.nav/*` (Q2). Note: there is **no** reserved bare `:http` fx — HTTP is the optional `:rf.http/*` surface, or an app-level `reg-fx` the port doesn't own.>

#### F5 Concurrency model

- **Model:** <e.g. "single-threaded JS event loop (browser / Node main thread)">
- **Cross-frame serialisation:** <how dispatch is serialised per frame in multi-frame setups>
- **No core.async confirmation:** <confirm the directive — no channels in the public dispatch contract>

#### F6 Hot-reload primitive

- **Mechanism:** <e.g. "Vite HMR module boundary at reg-* call sites" / "figwheel/shadow-cljs reload">
- **State-preservation contract:** <how frame state survives re-registration of `reg-frame`>

### State storage (S1–S3)

#### S1 Frame-state container (two partitions)

> EP-0001 shipped a **two-partition** frame. The physical durable state is **one** `frame-state` container value `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`; **app-db and runtime-db are read-only derived projections** over that one container (one physical container, two projection reactions — pattern contract per [`spec/002-Frames.md` §One physical container, two projection reactions](https://day8.github.io/re-frame2/spec/002-Frames/) and [`spec/006-ReactiveSubstrate.md` §Frame-state container and partition projections](https://day8.github.io/re-frame2/spec/006-ReactiveSubstrate/)). app-db holds user data and nothing else; runtime-db holds framework subsystem state (machine snapshots, route slice, elision declarations, SSR metadata) under the `:rf.runtime/*` children. The former app-db root `:rf/runtime` is RETIRED — a stray `:rf/runtime` root in a `:db` effect hard-errors (`:rf.error/legacy-runtime-root`); never store framework runtime state under an app-db root.

- **Frame-state container:** <e.g. "Reagent ratom holding `{:rf.db/app … :rf.db/runtime …}`" / "useSyncExternalStore-backed atom store" / "MutableStateFlow-shaped cell"> — ONE physical container for the whole frame-state value.
- **Partition projections:** <how `app-db` / `runtime-db` are derived as read-only projections over the one container — e.g. "two Reagent `r/reaction`s projecting `:rf.db/app` / `:rf.db/runtime`"; confirm equality-based partition invalidation: a runtime-only commit must NOT invalidate app subs, an app-only commit must NOT touch framework subs>
- **Write authority:** <confirm an ordinary `:db` effect replaces only app-db; `:rf.db/runtime` effects (framework/runtime-extension only, by convention) replace only runtime-db; a cascade emitting both installs them as one atomic frame-state transition>
- **Revertibility check:** <confirm no non-derivable adapter state lives outside the frame-state container — both partitions are revertible by one value swap; restore / hydration / time-travel reinstall a coherent frame-state, never one partition alone>

#### S2 Snapshot/restore mechanism

- **Mechanism:** <e.g. "pointer swap (persistent collections)" / "value capture + replace-container!"> — depends on F2. Snapshots the whole `frame-state` value (both partitions together), not app-db alone.

#### S3 Path-access primitive

- **Mechanism:** <e.g. "native assoc-in/update-in/get-in" / "Immer produce + lodash.get" / "lens helpers over Belt.Map">

### Subscriptions (Sub1–Sub2)

#### Sub1 Signal graph + caching

- **Graph backing:** <e.g. "Reagent reactions" / "useSyncExternalStore-driven memo" / "hand-rolled signal DAG over the React binding">
- **Cache key:** <confirm `=`-by-value equality for invalidation; identity-only equality is out>

#### Sub2 Lifecycle (when to dispose)

- **Disposal policy:** <e.g. "last-deref-disposes after a delay (Reagent)" / "explicit subscribe/unsubscribe ref-count">

### Views (V1–V3)

#### V1 Render-tree shape

- **Shape:** <e.g. "hiccup" / "JSX-as-data / snabbdom vnodes" / "Feliz Html.div DSL"> — must serialise for SSR + tooling.

#### V2 Render trigger

- **Trigger:** <how a subscribed-value change re-renders the view> — falls out of F3.

#### V3 Mount/unmount

- **Lifecycle hooks:** <how mount runs the frame's `:initial-events` / unmount fires `:on-destroy` on the surrounding frame>

### Tracing & instrumentation (T1–T3)

#### T1 Trace-event delivery

- **Registry shape:** <e.g. "single listener-registry atom + separate ring-buffer atom">
- **Ring buffer:** <retain-N for tools that attach after events fired; N = ?>

#### T2 Performance API equivalent

- **Bridge:** <ships the perf bridge (`performance.mark`/`measure`) / omits it — the bridge is optional; T1 is the contract>

#### T3 Production elision

- **Dev-trace-surface mechanism:** <e.g. "Closure DCE via debug-enabled?" / "Vite define + tree-shake" / "#if !DEBUG"> — elides the `register-listener!` registry, rich trace emit sites, ring buffer, perf bridge.
- **Always-on substrates kept live:** <how the `:events` / `:errors` emit substrates — reached publicly via the stream-parameterized `register-listener! :events|:errors` verb, internally `re-frame.event-emit` / `re-frame.error-emit` — survive the production build on a separate ungated path; these are NOT elided (they back the always-on observation streams + the frame `:observability` sinks built on top)>
- **CI verifier:** <sentinel-string scan asserting dev-only strings absent from production bundles AND a positive assertion that the always-on substrates remain present (catch over-aggressive DCE)>

### Errors (E1–E2)

#### E1 Error capture / recover

- **Capture sites:** <try/catch around handler bodies / fx invocations / sub computations>
- **No-silent-swallow:** <confirm every catch fires `:operation :rf.error/<category>` + `:op-type :error`>

#### E2 Error reporting to tools

- **Recovery:** <framework-owned typed per-category default — NO app-steering recovery policy; genuine recovery is local-at-source (managed-HTTP retry, optional-read fallback)>
- **Production error path — frame-owned sink (normal) vs corpus-wide listener (advanced):** <the NORMAL off-box error path is the frame-owned `:observability :errors` sink declared on `reg-frame` + wired with `register-observability-sink!` (already-PROJECTED records — Sentry / Rollbar / SSR fail-closed); the ADVANCED corpus-wide hook is `register-listener! :errors`, fanning `:rf.error/*` records across EVERY frame UNPROJECTED (`:exception` RAW) for a cross-frame post-mortem shipper. Both survive production elision, exception-isolated — the single error-observability substrate, NOT only via the dev-elided trace stream (T1). The obsolete `register-(event|error)-listener!` facade pairs were collapsed into `register-listener!`; bare names survive only as internal `re-frame.event-emit` / `re-frame.error-emit` fns.>

## D5. Schema mechanism

- **Answer:** <yes-runtime-schema / yes-via-host-types / no>
- **Library (if runtime-schema):** <e.g. "Malli (CLJS)" / "Zod (TS / Squint)">
- **Validation timing:** <e.g. "boundary-only, dev-build only, elided by Vite define" / "boundary-only, JIT-compiled to no-op in release">
- **Open-shape verification:** <how the port enforces open shapes — additive growth, unknown-key tolerance>

## D5b. Data classification (Sensitive + Large) — v1-required (not D3-gated)

- **Frame-owned durable classification:** <where the frame's `:sensitive {:app-db […] :http {…}}` / `:large {:app-db […]}` path maps land and how they're installed — e.g. "atomically at frame creation, before `:initial-events` run, into a per-frame registry"; re-registering a frame REPLACES its classification (no additive merge); malformed paths / unknown keys fail loudly at registration. (`add-marks` / `set-marks` and `declare-sensitive-header!` / `declare-sensitive-query-param!` are removed from the public façade — frame config replaces both.)>
- **Schema-prop owners (machines / resources / HTTP bodies):** <confirm owner-local schema'd data classifies via `:sensitive?` / `:large?` Malli props on the owning schema — machine `:data-schema` (rooted under `[:data …]`), resource `:data-schema` / `:params-schema`, an HTTP request's `:decode` schema. The schema-first route is the ONLY route for these shapes: `reg-machine` is `(reg-machine machine-id machine-spec)` / `(reg-machine machine-id opts machine-spec)`, the optional `opts` carrying an event-vector `:schema` and NO top-level `:sensitive` / `:large` keys — neither `opts` nor the machine spec carries sensitivity/large metadata (EP-0005 stands — see Spec 015 §Machine-owned / Spec 005 §Privacy)>
- **Registration-owned transient classification:** <confirm `reg-event` (the one public event registrar — EP-0018) / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow` accept `{:sensitive [paths] :large [paths]}` indexing into that registration's primary shape (`[[]]` marks the whole shape); derived outputs declassify via `:rf.egress/output-sensitivity` (`:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public`), NOT a `:sensitive false` boolean>
- **Propagation mechanism:** <write-time taint-tracking OR emit-time path-graph union — both conform; covers the framework-known dataflow (events → app-db → subs → flows → machine `:data` → fx inputs); arbitrary handler-body provenance is NOT tracked; `:rf.egress/public` is trusted by design>
- **Boundary projection — `project-egress`:** <the one public record-level boundary primitive every off-box sink routes through — it dispatches on a record's `:kind` to a private per-kind projector, delegates tree-shaped slots to the low-level `elide-wire-value` walker, and takes an `:rf.egress/profile` from the closed six-member enum (`:rf.egress/off-box-observability` / `off-box-tool` / `local-redacted` / `local-raw` / `ssr-hydration` / `public-error`). The wire markers it writes are `:rf/redacted` (sensitive) and `:rf.size/large-elided {:bytes N …}` (large) per Spec 009 — the `:rf/large {:bytes N :head}` / `:rf/redacted {:bytes N}` forms are the Spec 015 *display* renderings layered on top, not the wire shape; sensitive wins over large; real values flow through the runtime unchanged>
- **Frame-owned observability + fail-closed:** <how production sinks are wired — the frame's `:observability` policy + `register-observability-sink!`, always-on (survives `:advanced` + `goog.DEBUG=false`), sinks consume already-projected records (no sink-local redaction); routing/projection fail closed — an unresolved frame or frameless projection redacts rather than leaks, never synthesizing `:rf/default` (EP-0002)>

## D6. Integration story

- **Model:** <standalone library / framework integration / embedded>
- **Downstream consumer:** <name the framework / app / process the port plugs into>
- **Wiring boundary:** <where the consumer's app code meets the port — e.g. "React provider component" / "React Native root component" / "library API only">

## D7. Conformance capability tag set

The set of capability tags this port claims:

```
:core/*           (always)
:identity/*       (always — v1-required: EP-0012 :rf/path algebra + CEDN-1 identity, cardinal rule 11)
:data-classification/*  (always — v1-required: Spec 015 egress/redaction, D5b; enumerate sub-tags from fixtures)
:fsm/flat         <yes / no>
:fsm/hierarchical <yes / no>
:fsm/eventless-always <yes / no>
:fsm/delayed-after <yes / no>
:fsm/tags         <yes / no>
:fsm/parallel-regions <yes / no>
:fsm/final-states <yes / no>
:fsm/history      <yes / no — yes if you implement :type :history pseudo-states (shallow / deep / default-target); first-class v1 capability per Spec 005>
:fsm/registration-validation <yes / no — yes if you validate machine specs at registration time>
:actor/own-state  <yes / no>
:actor/spawn-destroy <yes / no>
:actor/cross-actor-fx <yes / no>
:actor/declarative-spawn <yes / no>
:actor/spawn-and-join <yes / no>
:actor/system-id  <yes / no>
:flow/*           <yes / no — yes if D3 Q8 = yes; the claim is the whole namespace, expanded to the current fixture sub-tags (grep ':flow/[a-z-]*' spec/conformance/fixtures/ at the pinned commit) — basic / trace / init / reg-v / poke / toggle / topo / dirty-check / frame-scoped / hot-reload / lifecycle-emits-traces / … ; sub-behaviours you don't implement go on known-skipped-capabilities>
:rf.http/managed  <yes / no — yes if D3 Q9 = yes>
:routing/*        <yes / no>
:ssr/*            <yes / no>
:schemas/*        <yes / no — pick yes if D5 ≠ no, regardless of mechanism; a static yes-via-host-types host puts the runtime-trace sub-tags (:schemas/runtime, :schemas/event-payload) on known-skipped-capabilities — the :fixture/dynamic-host-only? fixtures can't produce a runtime trace. See conformance.md §Static hosts and dynamic-host-only fixtures>
:rf.resource/*    <yes / no — yes if D3 Q10 = yes (presupposes Q9). CORPUS-BEHIND: spec-mandated but no fixtures yet; verify against spec/016-Resources.md + own unit tests, claim when fixtures land>
:rf.mutation/*    <yes / no — yes if D3 Q10 = yes; the named-causal-write half of Resources>
:derivation/algebra-graph                <yes / no — yes if D3 Q6 = yes AND you ship the full subs/flows/resources/routes/machines graph>
:derivation/algebra-graph-subs-machines  <yes / no — the subs+machines static subset; a graph host spanning only those claims this and known-skips the broad :derivation/algebra-graph>
```

> **The derivation/process algebra (EP-0014) mints no authoring capability — only an optional graph-inspection one.** [`spec/Derivations.md`](https://day8.github.io/re-frame2/spec/Derivations/) names the one view subs / flows / resources / routes / machines lower to (inputs / output / storage class / evaluation policy / lifecycle; superkinds `:derivation` / `:process`) — but it mints **no new authoring primitive** and **no public accessor**, so there is no `:derivation/*` tag for the *algebra behaviour* itself. That behaviour is verified *through* the source-form families you already claim (`:core/*` subs, `:flow/*`, resources / `:routing/*` / `:fsm/*`). The **one** EP-0014-specific conformance surface is the optional **graph-inspection** check, and it DOES carry concrete fixture tags — the split pair `:derivation/algebra-graph` (broad) + `:derivation/algebra-graph-subs-machines` (subs+machines subset) listed above. Claim them only if D3 Q6 = yes (you ship Tool-Pair inspection); if you ship no inspection surface, record a `known-skipped-capabilities` reason rather than a claimed tag. A graph host spanning only subs+machines claims the subset and known-skips the broad one (so a host cannot overclaim the EP-0014 graph surface).

The capability families above track the **conformance corpus** (the `spec/conformance/fixtures/*` files, which are the acceptance test). Both the Implementor-Checklist's family list and the conformance README's prose enumeration usually lag the fixtures (they omit `:flow/*` and its sub-tags, `:rf.http/managed`, `:fsm/final-states`, `:fsm/history`, `:fsm/registration-validation`); when a prose list and the fixtures diverge **for scoring** — what actually runs — the fixtures win. **But the divergence can go the other way for the *vocabulary*:** `:actor/*` is corpus-behind — `spec/conformance/README.md` + Spec 005 declare six actor tags, the fixtures back only four (`:actor/own-state` and `:actor/cross-actor-fx` are spec-mandated but fixture-less today). So `grep`-the-fixtures *under-claims* the actor axis; enumerate `:actor/*` from the README + Spec 005, and a fixture-less spec capability goes on `known-skipped-capabilities` only if you don't implement it. Enumerate the rest of the claimable vocabulary from `spec/conformance/fixtures/*` at the pinned commit (`grep -rho ':fsm/[a-z-]*' spec/conformance/fixtures/ | sort -u`, same for `:flow/`), cross-checked against the README capability table.

Score reporting: this port's score is `passed / claimed-applicable` against the above set. A capability the port deliberately doesn't claim goes on the harness's `known-skipped-capabilities` allowlist (see [`conformance.md` §The two out-of-claim flavours](conformance.md#the-two-out-of-claim-flavours)); a fixture carrying a capability in neither the claim nor the allowlist must FAIL the suite, not skip silently.

---

## Open questions parked for Phase 2

<For any decision that you can't lock without seeing the implementation play out, note it here. Better to mark a decision "deferred to Phase 2 step N" than to over-commit at lock time. Each deferred decision must be resolved before the matching Phase 2 step starts.>

- D<n> — <description> — *deferred until Phase 2 step <N: implement EP M>*

---

## Revision log

<Append-only. Each entry: date, the decision that changed, the Phase 2 step that surfaced the need, the new lock.>

- <YYYY-MM-DD> — initial lock.
```

---

## How to use the template

1. Copy the block between the `## Template — copy from here` heading and the `---` after it.
2. Paste into `DECISIONS.md` (or equivalent) at the root of the port's repo.
3. Fill in every `<...>` placeholder. Don't leave any blank — if you don't know, mark it "deferred to Phase 2 step N" in the *Open questions* section and call it out explicitly.
4. Commit. The record is now load-bearing.

## When to revise the record

Phase 2 will occasionally surface a foundation decision that won't survive contact with the implementation — typically because a host idiom doesn't fit the chosen mechanism cleanly. When this happens:

1. Stop the Phase 2 step that surfaced the issue.
2. Re-open `DECISIONS.md`.
3. Append to the *Revision log* with the date, the changed decision, and the Phase 2 step that surfaced it.
4. Update the decision body to the new lock.
5. Re-walk the affected portions of Phase 1's downstream decisions if they depended on the changed lock.
6. Resume Phase 2.

This costs ~30 minutes when caught early. Skipping the revise step and patching the code in flight costs days of cleanup later.

## Why the record matters

Three reasons:

1. **Phase 2 context.** Every Phase 2 step asks "given Phase 1, what does EP N look like in this port?" Without the record written down, the engineer (or their Claude session) re-derives the answer every time — and the answer can drift across sessions.
2. **Onboarding context.** Future contributors to the port read `DECISIONS.md` before reading code. The decisions are how the code makes sense.
3. **Conformance reporting.** The port's conformance score is *against the claimed capability set*. The claimed set lives in D7 of this record; the score has no meaning without it.

The record is the contract between Phase 1 and Phase 2.
