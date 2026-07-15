# Spec 009 — ui-program error-catalogue rows, pre-drafted paste batches

**Status:** prep batch · 2026-07-12 09:19 AUSEST · local-only working artefact (never `git add`).
**Target file:** `spec/009-Instrumentation.md` §Error event catalogue (THE busiest hot-zone file — this document exists so each implementing PR's catalogue dwell collapses to a paste).
**Sources (all read 2026-07-12 against the checked-in `spec/009-Instrumentation.md` and the sibling synthesis drafts):** the checked-in catalogue (rows 1870–2294); [03-reactivity-and-ownership.md](../03-reactivity-and-ownership.md) §3/§11; [spec-006-observation-port-amendment.md](spec-006-observation-port-amendment.md) §Error contract + §Cross-reference updates; [spec-004-rewrite-draft.md](spec-004-rewrite-draft.md) §View identity + the 009 ripple row; [root-identity-and-mount.md](root-identity-and-mount.md) §7; [jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md) §SSR boundary; [ui-react-interop-contract.md](ui-react-interop-contract.md) §3/§4 + [S3-CONFIRM] roster; [reagent-compat-boundary.md](reagent-compat-boundary.md) §3/§9.
House style: the catalogue's exact six-column format; British "serialisable"; `[CONFIRM-AT-LANDING]` marks a cell whose detail is genuinely unconfirmable before the implementing slice exists (typically exact evidence-payload keys or a recovery-keyword spelling) — the rest of the row is final.

## Protocol — how these batches land

Each implementing PR pastes **only its own batch** (rows + that batch's amendments + that batch's always-on-enumeration edit) into `spec/009-Instrumentation.md` in the same PR that mints the runtime emit — the catalogue's co-edit invariant (009 §Error event catalogue: "same PR as the owning Spec change that emits it") and the program plan's "catalogue rows land in small batches with their features". This file, in the mayor checkout, is the source of truth for the paste; the pasting worker copies from here, applies any `[CONFIRM-AT-LANDING]` resolutions against the actual implementation, and deletes nothing from the batches it does not own. Because 009 is a hot-zone file, batch-carrying PRs are **sequential, never parallel** — two ui-program PRs both carrying rows must be sequenced, second waits for the first's merge (the CLAUDE.md hot-zone rule). Every new row additionally needs its per-category Malli `:tags` schema in `spec/Spec-Schemas.md` §Per-category `:tags` schemas (one schema per row — a co-edit in the same PR, but not a 009 dwell), and every new **always-on** row needs (a) its category added to the always-on enumeration paragraph directly above the catalogue table, and (b) at least one test exercising it through the error-emit listener (the 009 conformance rule: promotion is real, not documentary).

---

## 1. Landed inventory — ui-program ids that ALREADY have catalogue rows on main

Verified by grep against checked-in `spec/009-Instrumentation.md` 2026-07-12. **No future PR may re-add any of these as a new row** — the S5-arm ones take the amendments in §2.4 instead.

### 1a. ui-program rows (17), all Channel `diagnostic`, in the compiled-ui catalogue block

| Id | Landed via | Note |
|---|---|---|
| `:rf.error/ui-tree-malformed` | rf2-vxgfnd S1 (+ S1d consumer surfaces) | gains the S5 `emit-ui-tree` emit surface — amendment §2.4 |
| `:rf.error/ui-duplicate-key` | S1b | — |
| `:rf.error/jvm-host-op` | S1 | conditional S3 emit-surface extension — §2.3 |
| `:rf.error/ui-sub-unavailable` | S1 STAGING | **yes, the S1b-question row DID land**; its own text retires it at S2 — §2.1 |
| `:rf.error/ui-lease-unavailable` | S1 STAGING | retires at S2 — §2.1 |
| `:rf.error/ui-dispatch-unwired` | S1 STAGING | retires at S2/S3 (committed-frame dispatch) — §2.2 |
| `:rf.error/ui-spread-outside-template` | S1 | — |
| `:rf.error/ui-test-tier-mismatch` | S1d | — |
| `:rf.error/ui-test-bad-selector` | S1d | — |
| `:rf.error/ui-test-overlapping-act` | S2 Tier-3 Promise contract | forgotten-await guard; public overlap fails before a second React `act` or `with-root` allocation |
| `:rf.error/ui-test-bad-opts` | S1d | — |
| `:rf.error/ui-frame-root-outside-root-form` | S1c | — |
| `:rf.error/duplicate-root-id` | S1c (build + client tiers) | Layer-2 server tier lands S5 — clause amendment §2.4 |
| `:rf.error/root-container-missing` | S1c | S5 hydration-locator arm — clause amendment §2.4 |
| `:rf.error/root-container-in-use` | S1c | — |
| `:rf.error/root-manifest-invalid` | S1c (S1 arm only) | S5 arms — full amendment §2.4 |
| `:rf.error/frame-payload-conflict` | S1c (BUILD tier only) | S5 runtime-preflight arm — full amendment §2.4 |

### 1b. Pre-program rows the ui program reuses (3) — existing rows, never new ids

| Id | Status | ui-program relationship |
|---|---|---|
| `:rf.error/no-such-sub` | landed, always-on (1 catalogue row; 11 file mentions) | gains the observation port's throwing emit surface at S2a — amendment §2.1. The spike's `:rf.error/no-sub` spelling is superseded and must not survive anywhere |
| `:rf.error/frame-destroyed` | landed, always-on (1 catalogue row; 9 file mentions) | gains the port's throwing emit surface at S2a — amendment §2.1 |
| `:rf.error/no-frame-context` | landed, always-on | reused verbatim by the compiled substrate (resolve-target with no ambient frame; the honest `ui.test/render`-with-no-frame path). No draft mandates a row amendment; if the S2 slice adds a distinct emit-surface sentence, that is a discretionary clause edit, not a new id |
| `:rf.error/frame-provider-frame-absent` | landed, diagnostic (1 catalogue row, line 1893; the rf2-nyea0r frame-boundary family) | referenced by the 004A compat appendix's retained-surface table (the frozen Reagent `frame-provider` realisation). No ui-program action: the row's owning spec stays 002 (frame mechanics are tier-independent); the compiled substrate's own `frame-provider` path raises `:rf.error/no-frame-context` per the resolution chain, not this id. At S7 the frame-boundary family rows' emit-surface prose loses the Helix path but retains/re-statuses UIx as frozen compatibility — freeze/deletion-wave housekeeping, rides the §2.5 S7 notes |

**Anchor findings against the task brief:** `:rf.error/root-hydration-mismatch` has **zero** occurrences in the checked-in catalogue (root-identity-and-mount §7 calls it an "existing 03 §11 row" — existing in the *taxonomy doc*, not in 009), so it is a **new S5 row** (§2.4), not an amendment. `:rf.error/ssr-ui-tree-version-unsupported` occurs once — inside the `ui-tree-malformed` row's prose ("lands with the S5 serialiser") — and has no row of its own: new S5 row.

---

## 2. The prep batches

Catalogue table header, for reference (paste rows under the existing table — never a second header):

```
| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
```

### 2.1 S2a — the observation-port batch

Pastes with the S2 observation-slice PR that lands `re-frame.substrate.observation` (the six-operation port). Source: [spec-006-observation-port-amendment.md](spec-006-observation-port-amendment.md) §Error contract + §Cross-reference updates; 03 §3/§11.

**New rows (3):**

| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
| `:rf.error/read-after-release` | `:error` | always-on | The observation port's `read` was called on a RELEASED lease (rf2-vxgfnd S2a) — a SUBSTRATE bug, never an app error: the port's callers are generated commit/render machinery, the commit path checks `current?` first and the render path falls back to `probe`, so the throw is unreachable in correct generated code. Always thrown (dev AND production — a shipped substrate defect must reach off-box shippers, so it rides the always-on error-emit axis (surface #4) alongside the DCE'd dev trace). Thrown typed by `re-frame.substrate.observation/read`. Per [006 §The internal observation port](006-ReactiveSubstrate.md#the-internal-observation-port-adapter-internal) | `:no-recovery` — the throw propagates; transactional commit staging keeps the prior committed dependency set installed, and the ViewCell maps port throws to the view error boundary (the Spec 004 surface). Report it — this is framework-owned code failing | `:frame`, `:query` (the lease's stabilised query) `[CONFIRM-AT-LANDING — exact evidence keys; the lease itself is an opaque identity token and never rides the payload]`, `:recovery` |
| `:rf.error/reentrant-graph-op` | `:error` | diagnostic | `acquire!` or `release!` was called from INSIDE the owner-notification fan-out (rf2-vxgfnd S2a) — `on-change` is constant-work mark-dirty and must never drive graph ops (port invariant 6 / I-5). React-driven acquire/release during the read/render commits *caused by* the drain-quiescence notification batch are outside the fan-out and always legal. Dev-asserted; thrown typed by `re-frame.substrate.observation`. Per [006 §Callback and reentrancy rules](006-ReactiveSubstrate.md#the-internal-observation-port-adapter-internal) | `:no-recovery` — fix the callback: mark dirty and return; graph ops belong to the commit path, never the notification fan-out | `:op` (`:acquire!` / `:release!`) `[CONFIRM-AT-LANDING — exact evidence keys]`, `:recovery` |
| `:rf.error/observation-port-version-mismatch` | `:error` | always-on | `day8/re-frame2-ui` loaded against a core whose `re-frame.substrate.observation/port-abi-version` differs from the version the UI artifact compiled against — the R-6 lockstep-release-train drift guard. Asserted once at load and fails loudly: artifact drift is a BOOT error, never undefined behaviour. Production-reachable (a mis-deployed artifact pair boots under `goog.DEBUG=false`), so the record rides the always-on error-emit axis before the throw. Per [006 §Scope — outside the closed public adapter contract](006-ReactiveSubstrate.md#the-internal-observation-port-adapter-internal) | `:no-recovery` — align the artifact pair: core and `day8/re-frame2-ui` release together on the lockstep train; no skewed pair is supported | `:expected` (the compiled-against integer), `:got` (the loaded core's integer) `[CONFIRM-AT-LANDING — exact key spellings]`, `:recovery` |

**Row amendments (2) — existing rows gain the port's throwing emit surface. Recovery and `:tags` cells are UNCHANGED in both (the 006 amendment's explicit rule: "the public surfaces' `:replaced-with-default` recovery column is unchanged").**

**(a) `:rf.error/no-such-sub`** — anchor verified: the row-start `| `:rf.error/no-such-sub`` occurs **exactly once** in checked-in 009 (line 1878; the id has 11 prose mentions elsewhere — none are rows).

Current trigger cell (quoted in full):

> A subscription's `:<-` input refers to an unregistered sub (or a `subscribe` targets an unregistered sub-id). Production-survivable through the always-on error-emit listener (surface #4); recovery is the framework's built-in default for an invalid op

Amended trigger cell (append the bolded sentence block; nothing deleted):

> A subscription's `:<-` input refers to an unregistered sub (or a `subscribe` targets an unregistered sub-id). Production-survivable through the always-on error-emit listener (surface #4); recovery is the framework's built-in default for an invalid op. **Observation-port surface (rf2-vxgfnd S2):** the compiled UI substrate's internal port throws TYPED on this SAME id when a target's OWN query names an unregistered sub, at `probe` or `acquire!` (`re-frame.substrate.observation` — internal fail-loud; transactional commit staging keeps the failure non-corrupting and the ViewCell maps the throw to the view error boundary). A sub BODY's `:<-` reference to an unregistered input keeps this row's replaced-with-default behaviour identically under `probe` (including cold probes) and under public `subscribe`. One condition, one catalogue id, two emit surfaces — `:rf.error/no-sub` does not exist. Per [006 §The internal observation port](006-ReactiveSubstrate.md#the-internal-observation-port-adapter-internal)

**(b) `:rf.error/frame-destroyed`** — anchor verified: the row-start `| `:rf.error/frame-destroyed`` occurs **exactly once** in checked-in 009 (line 2052; 9 prose mentions elsewhere).

Current trigger cell (quoted in full):

> A `dispatch` / `dispatch-sync` / `subscribe` arrived against a frame that is unregistered or whose `(:lifecycle frame-record)` carries `:destroyed? true`. Per [002 §Frame lifecycle](002-Frames.md#frame-lifecycle). The runtime **recovers** (dispatch / dispatch-sync no-op — the event is not enqueued; subscribe returns `nil`) and emits a **production-survivable** record through the always-on error-emit listener (surface #4) — NOT just the dev trace. Recovery (not throwing) is race-safe (teardown / hot-reload races vs. real use-after-destroy bugs are indistinguishable) while the broad listener keeps the diagnostic observable in production. Emitted from router.cljc and subs.cljc

Amended trigger cell (append the bolded sentence block; the final emit sentence gains the port namespace; nothing deleted):

> A `dispatch` / `dispatch-sync` / `subscribe` arrived against a frame that is unregistered or whose `(:lifecycle frame-record)` carries `:destroyed? true`. Per [002 §Frame lifecycle](002-Frames.md#frame-lifecycle). The runtime **recovers** (dispatch / dispatch-sync no-op — the event is not enqueued; subscribe returns `nil`) and emits a **production-survivable** record through the always-on error-emit listener (surface #4) — NOT just the dev trace. Recovery (not throwing) is race-safe (teardown / hot-reload races vs. real use-after-destroy bugs are indistinguishable) while the broad listener keeps the diagnostic observable in production. **Observation-port surface (rf2-vxgfnd S2):** `probe`/`acquire!` against a destroyed frame throws TYPED on this same id (`re-frame.substrate.observation` — internal fail-loud; the ViewCell maps the throw to the view error boundary); the public dispatch/subscribe recovery in this row is unchanged. Emitted from router.cljc and subs.cljc, and thrown from `re-frame.substrate.observation` (S2)

**Batch housekeeping (same paste):**

- **Staging retirements.** The `:rf.error/ui-sub-unavailable` and `:rf.error/ui-lease-unavailable` rows retire in this PR per their own row text ("Retires at S2" / "The row retires when S2 replaces the stub"). Precedent for the mechanics: the retired `:rf.warning/plain-fn-under-non-default-frame-once` strikethrough row + the OUT-OF-CATALOGUE paragraph — the category vocabulary is stable, so retire by strikethrough-with-pointer, not deletion. `[CONFIRM-AT-LANDING — strikethrough vs. deletion; follow whatever the plain-fn precedent's exact form is at paste time]`
- **Always-on enumeration paragraph** (the single long paragraph directly above the catalogue table): add `:rf.error/read-after-release` and `:rf.error/observation-port-version-mismatch` to the enumerated always-on set, with their one-line promotion rationale (substrate-bug reachability under `goog.DEBUG=false`; boot-time drift on production deploys). Both must be exercised through the error-emit listener in at least one test.
- **Spec-Schemas co-edit:** three new per-category `:tags` schemas.

### 2.2 S2b–d — the cell/drain batch

Pastes with the ViewCell/commit-reconciler + drain-quiescence/flush slice and the S2 frame-chain slice. Source: [spec-006-observation-port-amendment.md](spec-006-observation-port-amendment.md) §Drain-quiescence finalization; 03 §3/§8/§11.

**New rows (3):**

| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
| `:rf.error/flush-in-open-epoch` | `:error` | diagnostic | `ui.test/flush!` was called re-entrantly while a frame's run-to-completion event drain was still open. Rendering there could expose a partially settled queued write side; every queued write must reach drain quiescence before the one read/render batch begins. The retained operation id names the historical catalogue entry; its boundary is the open drain, not an epoch/render bijection. The call throws before draining ViewCell notifications or entering React `act`; dev/test only. Per [006 §Drain-quiescence finalization — the adapter-internal final phase](006-ReactiveSubstrate.md#the-internal-observation-port-adapter-internal) | `:no-recovery` — let the event drain reach quiescence, then flush once | `:frame`, `:frame-epoch`, `:recovery` |
| `:rf.error/ui-test-overlapping-act` | `:error` | diagnostic | A public CLJS `with-root`/`flush!` operation began while a prior Promise-backed React `act` was pending — normally a forgotten await. Throws synchronously before a second `act`; `with-root` checks before allocation. Private teardown remains serialized so the misuse cannot strand an owner | `:await-the-prior-operation` — await every mounted-test Promise before asserting or starting another operation | `:active-where`, `:recovery` |
| `:rf.warning/cross-frame-carried-op` | `:warning` | diagnostic | A CARRIED frame ops map's `subscribe` ran under a DIFFERENT ambient frame than the one it was captured from — the `(frame)` hold's honesty rule (03 §8): a carried ops map *can* be used under a foreign frame's subtree, so the frames-are-isolated doctrine is held by this diagnostic, by teaching subtree scoping, and by the absence of any cross-frame read *spelling* — never by a false impossibility claim. Per the Spec 004 rewrite §Roots and mounting + [002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant) `[CONFIRM-AT-LANDING — final cross-ref anchor once the rewrite merges]` | `:warned-and-continued` — the op proceeds against the CAPTURED (origin) frame; advisory only. Restructure with `frame-provider` subtree scoping, or pass values not ops `[CONFIRM-AT-LANDING — recovery keyword]` | `:origin-frame`, `:ambient-frame`, `:rf.sub/query-v` `[CONFIRM-AT-LANDING]` |

**Batch housekeeping:** `:rf.error/ui-dispatch-unwired` retires when committed-frame dispatch lands ("Replaced by committed-frame dispatch at S2/S3" — its own row text). If dispatch wiring rides the S3 slice instead, the retirement moves to the §2.3 paste; whichever PR deletes the stub carries the retirement. Same strikethrough mechanics as §2.1.

### 2.3 S3 — the handlers/local/effects/interop batch

Pastes with the S3 committed-behaviour slice (`ui/dispatch-fn`, dev safety nets, dynamic handler classification, `local`, the `re-frame.ui.react` tier). Source: 03 §11; [spec-004-rewrite-draft.md](spec-004-rewrite-draft.md) §Handlers/§Local state/§Effects; [ui-react-interop-contract.md](ui-react-interop-contract.md).

**New rows (5 live + 1 parked):**

| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
| `:rf.error/dispatch-disconnected` | `:error` | always-on | The stable committed-frame dispatcher returned by `(ui/dispatch-fn)` was invoked while its owning cell was in a NON-CONNECTED state (`:disconnected` or `:dead` — the three-state lifecycle) — the leaked-listener detector (03 §4/§6): an external callback retained past disconnect fired into a torn-down view. Production-reachable by nature (retention leaks are exactly a production bug class) and the dropped intent is otherwise silent, so it rides the always-on error-emit axis. Per the Spec 004 rewrite §Effects and leases `[CONFIRM-AT-LANDING — final cross-ref anchor]` | `:no-recovery` — the dispatch is rejected, never routed to a stale or destroyed frame; fix the leak (unregister the external listener in the `effect` cleanup that created it) | `:view-id`, `:render-key`, `:state` (`:disconnected` / `:dead`), `:event-id` `[CONFIRM-AT-LANDING — exact evidence keys]`, `:recovery` |
| `:rf.warning/unregistered-event-id` | `:warning` | diagnostic | A DATA handler — an event vector in an `:on-*` position — names an event id with no registered handler; warned at RENDER with the element's source coordinates, before anyone clicks (Spec 004 rewrite §Dev safety nets). The registrar is process-global (frames isolate state, not behaviour), so a lazily-loaded module that registers later can produce a false positive; the warning text names that possibility | `:warned-and-continued` — the site renders and dispatches normally at interaction time; register the handler (or fix the id typo) `[CONFIRM-AT-LANDING — recovery keyword]` | `:event-id`, `:view-id`, `:source-coord` `[CONFIRM-AT-LANDING]` |
| `:rf.warning/placeholder-in-dynamic-vector` | `:warning` | diagnostic | A runtime-forwarded (non-literal) handler vector carries an `:rf.ui/*` placeholder keyword — placeholders are COMPILED, recognised in literal vectors only, so the keyword will dispatch as an ordinary keyword argument instead of splicing (Spec 004 rewrite §Dynamic handler expressions) | `:warned-and-continued` — the dispatch proceeds with the literal keyword; make the vector literal at the site, or splice the value before forwarding `[CONFIRM-AT-LANDING — recovery keyword]` | `:event` (the forwarded vector), `:placeholder` (the `:rf.ui/*` keyword found) `[CONFIRM-AT-LANDING]` |
| `:rf.warning/render-phase-dispatch` | `:warning` | diagnostic | A dispatch was attempted DURING a render pass — a render-purity violation (I-1: a render may run, restart, or be abandoned; it must not dispatch). App intent belongs in event-vector handlers / `ui/event`; setup-on-mount work belongs in a frame's `:initial-events` or a route/domain transition, never a render-phase dispatch (Spec 004 rewrite §Removed forms rationale) | `[CONFIRM-AT-LANDING — whether the render-phase dispatch is dropped or proceeds; the synthesis pins only the warning]` — move the dispatch out of the render body | `:view-id`, `:rf.event/v` `[CONFIRM-AT-LANDING]` |
| `:rf.warning/render-phase-set!` | `:warning` | diagnostic | A `local` `set!` was invoked during a render pass — "`set!` during render is a dev error" (Spec 004 rewrite §Local state). Renders are speculative; committed state mutates from handlers and effects only | `[CONFIRM-AT-LANDING — drop vs. proceed, mirroring the `-dispatch` row's resolution]` — call `set!` from a committed handler or an `effect` | `:view-id` `[CONFIRM-AT-LANDING]` |

**Parked row — include ONLY when `ui/view` ships (WAVE-2, demand-gated; no v1 existence). Do NOT paste with the S3 batch:**

| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
| `:rf.error/view-not-found` | `:error` | always-on | **[WAVE-2 — PARKED: lands only with `ui/view`.]** `(ui/view id)` resolved an id with no registry entry — pre-React, fail-loud. Production use requires production registry entries (dev-only string ids cannot serve prod lookup) (03 §11; Spec 004 rewrite §Interop [WAVE-2] rows) | `:no-recovery` — register the view (with a production registry entry) or fix the id | `:view-id` `[CONFIRM-AT-LANDING]`, `:recovery` |

**Batch notes:**

- **Position-law ids are NOT catalogue rows — RULED.** [ui-react-interop-contract.md](ui-react-interop-contract.md) §3 proposed `:rf.ui.compile/react-hook-in-loop` / `-in-branch` / `-in-fn` with "Spec 009 catalogue rows at promotion". **Resolved (shepherd ruling, rf2-kvtn97 NOTES, 2026-07-12): compile-time diagnostic ids get NO 009 catalogue rows** — the checked-in precedent stands (`:rf.ui.compile/*` ids appear in 009 only inside other rows' prose — 2 mentions, 0 rows; S1's analyzer ids landed prose-only; root-identity-and-mount §1: compile diagnostics "join the compile-error roster, not the runtime catalogue"). No rows are drafted here and none may be added; the S3d brief carries the ruling verbatim.
- **Conditional `:rf.error/jvm-host-op` amendment.** If interop [S3-CONFIRM] #6 resolves to ID REUSE (JVM `use-effect-event` invocation; JVM `use-context` with no provided test value), the landed `jvm-host-op` row's emit-surface sentence gains the `re-frame.ui.react` JVM stubs, and its trigger's "later-stage host ops (`local` setters at S3+)" clause goes live-tense. Clause edit only; `[CONFIRM-AT-LANDING]`. If a dedicated id is ruled instead, draft its row at that ruling.
- **Always-on enumeration paragraph:** add `:rf.error/dispatch-disconnected` (+ listener-exercise test). **Spec-Schemas co-edit:** five new `:tags` schemas.

### 2.4 S5 — the SSR/hydration batch

Pastes with the S5 server-rendering slice (Root Manifest v1, hydrate preflight, payload install, `emit-ui-tree`). Source: [root-identity-and-mount.md](root-identity-and-mount.md) §2/§4/§5/§7; [jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md) §SSR boundary; 03 §11.

**New rows (5):**

| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
| `:rf.error/root-hydration-mismatch` | `:error` | always-on | A hydrating root's manifest validation failed against the client build — `render-fingerprint` / `build-digest` disagreement (root-identity-and-mount contract §7; supersedes the FNV-1a `:rf/render-hash` + `:first-diff-path` detection for compiled-substrate roots; there is NO `suppressHydrationWarning`-style escape). Failure is scoped to exactly that root (per-root failure isolation) — sibling roots hydrate normally. Production-reachable by construction (a stale SSR page served against a freshly deployed client build), so it rides the always-on error-emit axis | `:client-fresh-render` — the root abandons hydration LOUDLY and takes the client-fresh render path; align server and client builds `[CONFIRM-AT-LANDING — recovery keyword + whether the fresh render is automatic or app-invoked]` | `:root-id`, `:which` (`:render-fingerprint` / `:build-digest`), `:expected`, `:got` `[CONFIRM-AT-LANDING — exact evidence keys]`, `:recovery` |
| `:rf.error/frame-payload-invalid` | `:error` | always-on | A referenced frame payload failed VALIDATION at install (hydrate preflight; root-identity-and-mount §6–§7) — malformed or unreadable payload data. Distinct from `:rf.error/frame-payload-conflict` (a VALID payload colliding with an already-installed one). Fails exactly the roots referencing that payload; installed frames and other roots are untouched (the 06 §2 failure scoping). Production-reachable on hydrating clients, so always-on (03 §11) | `:no-recovery` — that root fails loud with no partial install; fix the server-side payload emission | `:frame-id`, `:root-id`, `:reason` `[CONFIRM-AT-LANDING — exact evidence keys]`, `:recovery` |
| `:rf.error/ssr-ui-tree-version-unsupported` | `:error` | diagnostic | `re-frame.ssr/emit-ui-tree` (the tree→SSR consumption boundary) validated `:rf.ui/tree-version` FIRST, before any emission, and found a missing field, a non-integer, or an unsupported version — fail-loud at the seam, matching the artifact's construction-time posture (`:rf.error/ssr-missing-payload-policy` style). The version-gate sibling of `:rf.error/ui-tree-malformed`, which covers malformed NODES past the gate (jvm-tree-and-conversion contract §The SSR consumption boundary). Thrown ex-info by `re-frame.ssr` | `:no-recovery` — regenerate the tree with a supported emitter, or upgrade the SSR artifact (the lockstep release train) | `:got`, `:supported` (e.g. `#{1}`) |
| `:rf.ssr/root-render-failed` | `:error` | diagnostic | One root's render threw during multi-root page assembly — caught at THAT root's render step, per-root failure isolation (spec-011 amendment §Per-root failure isolation): the failed root's container position ships the host-declared static fallback or the deterministic error comment `<!-- rf2-root-failed: <root-id-slug> -->` (slug only — exception detail rides this trace, never the wire); NO manifest script and NO frame payloads are emitted for the failed root; a hydration-ledger row records `:outcome :failed`; sibling roots are untouched. The multi-root sibling of `:rf.ssr/suspense-boundary-failed`. Id spelling `[CONFIRM-AT-LANDING — [S5-CONFIRM] per the 011 amendment roster item 5]`. Emitted by the `re-frame2-ssr` page-assembly path | `:static-fallback` — the root ships its declared fallback (else the error comment); fix the root's render; the page and sibling roots proceed `[CONFIRM-AT-LANDING — recovery keyword]` | `:root-id`, `:exception`, `:recovery` |
| `:rf.error/ssr-static-root-requires-runtime` | `:error` | diagnostic | A root DECLARED static (`render-static` entry, or root-manifest policy) failed the compiler's transitive `requires-client-runtime?` proof — the explicit static-root policy (06 §3; spec-011 amendment §`render-static`): hydration is elided only when the proof passes AND the host declares; a failed proof throws rather than shipping an inert root that looks interactive (no silent elision, in either direction). Ex-data names the blocking capability bits. Id spelling `[CONFIRM-AT-LANDING — [S5-CONFIRM] per the 011 amendment roster item 5]`. Thrown ex-info at server render / `render-static` construction | `:no-recovery` — remove the blocking capability from the subtree (or a `client-only` island around it), or withdraw the static declaration and let the root hydrate | `:root-id`, `:capabilities` (the blocking bits) `[CONFIRM-AT-LANDING — exact evidence keys]`, `:recovery` |

**Row amendments (3 full + 2 clause-level):**

**(a) `:rf.error/ui-tree-malformed`** — anchor verified: the id occurs **exactly once** in checked-in 009 (its row, line 2279). Two edits inside the trigger cell; recovery and `:tags` unchanged.

Current clause 1: `…(hiccup is compiled, not interpreted; the fix is a child view, `ui/raw`, or `(for …)` with `:key`), … or a list row that lost its `:key`. The id is contract-named by the jvm-tree-and-conversion-contract draft (§node discrimination — shared by every tree consumer; the SSR seam's version-gate sibling `:rf.error/ssr-ui-tree-version-unsupported` lands with the S5 serialiser).`

Amended clause 1 (tense flip only): `…The id is contract-named by the jvm-tree-and-conversion contract (§node discrimination — shared by every tree consumer; the SSR seam's version-gate sibling is `:rf.error/ssr-ui-tree-version-unsupported`, its own row).`

Current clause 2 (the emit sentence): `Emitted by `re-frame.ui.tree` / `re-frame.ui.rules` / `re-frame.ui.runtime` / `re-frame.ui` / `re-frame.ui.test` (the S1d tree consumers — `find`/`find-all`/`attrs`/`text` fail loud on malformed nodes, non-node children, and text content passed where a node is required)`

Amended clause 2 (append one surface): `Emitted by `re-frame.ui.tree` / `re-frame.ui.rules` / `re-frame.ui.runtime` / `re-frame.ui` / `re-frame.ui.test` (the S1d tree consumers — `find`/`find-all`/`attrs`/`text` fail loud on malformed nodes, non-node children, and text content passed where a node is required) / `re-frame.ssr` (the S5 `emit-ui-tree` serialiser — malformed nodes past the version gate)`

**(b) `:rf.error/root-manifest-invalid`** — anchor verified: exactly one row (line 2293). The landed row is the S1 arm ("at S1 every hydrate fails loud rather than guessing identity"); at S5 the full arm roster goes live. Replacement row (whole row; the S1 arm becomes one case among peers):

| `:rf.error/root-manifest-invalid` | `:error` | diagnostic | The root-manifest contract was violated at a hydrating root or at server emit (root-identity-and-mount contract §2/§4/§5/§7; rf2-vxgfnd S1c arm + the S5 arms). The arms: no discoverable manifest adjacent to the container at `ui/hydrate-root` (data `{:missing :manifest}` — the S1-landed arm); a manifest present but unreadable at hydrate; `:rf.root/schema-version` incompatibility; a server-emit prop the Spec 011 EDN-safe encoder cannot carry (data `{:unserialisable-prop <k>}` — fails the server render for that root; never a silently truncated manifest); a host-authored container without an id at server render (data `{:missing :container-id}` — never a synthesised locator on a host-owned element); an identifier-prefix conflict across one page's roots (data `{:conflict :identifier-prefix}` — a shared prefix would collide `use-id` output); identity opts supplied client-side to `hydrate-root` (data names the conflicting key — hydrating identity is manifest-authored). (Client-side identity opts at a `hydrate-root` SITE remain statically checkable and reject at compile time, `:rf.ui.compile/identity-opts-at-hydrate`.) Failure is scoped to that root per the failure-isolation contract. Thrown ex-info (canonical builder). Emitted by `re-frame.ui.client` (hydrate arms) / the `re-frame2-ssr` page assembly (server-emit arms) | `:fix-the-manifest-contract` — the data map names the offending arm (`:missing` / `:unserialisable-prop` / `:conflict` / the conflicting identity key); client-only roots that need no manifest mount with `ui/mount` `[CONFIRM-AT-LANDING — recovery keyword; the landed S1 keyword is `:use-ui-mount`]` | `:root-id`, `:missing` / `:unserialisable-prop` / `:conflict` (per arm) `[CONFIRM-AT-LANDING]`, `:recovery` |

**(c) `:rf.error/frame-payload-conflict`** — anchor verified: exactly one row (line 2294). The landed row is the BUILD-tier arm with the runtime arm forward-declared ("lands S5 with hydrate preflight"). Replacement row:

| `:rf.error/frame-payload-conflict` | `:error` | diagnostic | The payload/frame-config conflict roster (root-identity-and-mount contract §7; rf2-vxgfnd S1c + S5). BUILD tier: two static frame plans for ONE frame-id with DIFFERING config fingerprints, rejected at macro expansion (within one root form, or across root sites in one build's entry closure). One frame, one plan: frame config belongs in one boot/root site. RUNTIME preflight tier (S5): at any root's preflight (hydration or client mount), BEFORE install/hydrate — a referenced payload id already installed with a DIFFERENT content digest, or a frame plan whose `:config-fingerprint` differs from the installed frame's recorded plan fingerprint — fails exactly the ARRIVING root, data `{:frame-id … :installed {:digest … :installed-by <root-id>} :arriving {:digest … :root-id …}}`; the installed frame and the roots already using it are untouched (a bad frame payload affects exactly the roots referencing it). No first-wins silent merge, no last-wins overwrite; a matching digest/fingerprint is the ratified idempotent no-op. Thrown ex-info (canonical builder). Emitted by `re-frame.ui.compiler.root` (build) / `re-frame.ui.client` (runtime preflight) | `:align-frame-plan-config` — keep the frame's config in one root site, or align the configs/payload emission across the conflicting parties | `:frame-id`, `:fingerprints`, `:sites` (build arm) / `:installed`, `:arriving` (runtime arm), `:recovery` |

**(d + e) Clause-level amendments — flip the forward-declared "lands S5" prose to live tense at paste time (no semantic change):**

- `:rf.error/root-container-missing` (one row, line 2291): "The S5 hydration arm — a manifest element-locator resolving to no element (fragment-composition bugs) — **lands with server rendering**, scoped to that root per the failure-isolation contract" → "…**is live (S5)**: a manifest element-locator resolving to no element (fragment-composition bugs) fails that root only, per the failure-isolation contract". Emit sentence gains the hydrate path if a distinct namespace emits it `[CONFIRM-AT-LANDING]`.
- `:rf.error/duplicate-root-id` (one row, line 2290): "The Layer-2 server page registry **lands S5**" → live tense: "The SERVER tier (Layer 2, S5): page assembly registers each root — manifest AND `render-static` roots (static roots hold identity too) — in a per-response registry; a second equal root-id registration fails the render (server tier, projected per Spec 011), catching independently rendered page fragments composed into one response". Tags gain the server-arm payload keys `[CONFIRM-AT-LANDING]`.

**Batch housekeeping:** always-on enumeration paragraph gains `:rf.error/root-hydration-mismatch` and `:rf.error/frame-payload-invalid` (+ listener-exercise tests); note both are CLIENT-side hydration errors, unlike the existing JVM-projected SSR always-on set — state that in the paragraph edit. (`root-render-failed` and `ssr-static-root-requires-runtime` stay diagnostic — server-side, mirroring the landed `suspense-boundary-failed` / `ssr-missing-payload-policy` channel posture; `[CONFIRM-AT-LANDING]` if the S5 slice rules otherwise.) **Spec-Schemas co-edit:** five new `:tags` schemas + updates for the two rewritten rows.

### 2.5 S6–S7 — the compat-boundary batch

Pastes with the S6 migration-wave slice (`ui/->react`). Source: [reagent-compat-boundary.md](reagent-compat-boundary.md) §3 + §9 item 5.

**New row (1) — the id itself is PROPOSED [S6-CONFIRM]; confirm the spelling before paste:**

| `:operation` | `:op-type` | Channel | Trigger / meaning | Default `:recovery` | `:tags` |
|---|---| --- |---|---|---|
| `:rf.warning/compat-camelised-prop` | `:warning` | diagnostic | An exported view (`ui/->react`) received a prop slot matching NO declared slot whose DE-CAMELISATION does match a declared slot — Reagent's `[:> Exported {…}]` `convert-prop-value` camelisation crossing the outward compat boundary (`:on-select` arriving as `onSelect` against the namespace+name-preserving props ABI). Dev-only diagnosis (reagent-compat boundary contract §3). Blessed spellings the message names: single-segment unqualified prop names; the conversion-bypassing `[:r> Exported #js {…}]` head; renaming hyphenated/namespaced props at the boundary | `:warned-and-continued` — the received slot is NOT silently remapped (the view sees the declared slot as absent); respell at the boundary `[CONFIRM-AT-LANDING — recovery keyword + drop-vs-remap behaviour]` | `:view-id`, `:received-slot`, `:declared-slot` `[CONFIRM-AT-LANDING]` |

**S7 notes (no new rows):** the deletion-wave PR carries catalogue RE-HOMING only, per the Spec 004 rewrite ripple row — the reagent-slim template-error rows are deleted/re-homed with their adapter; the rows thrown from `re-frame.core-reg-view-macro` and the `:>`-head SSR error freeze into `spec/004A-Reagent-Compat.md` (relocated, not removed — the compat appendix is their live home). Draft those edits from the checked-in rows at S7 dispatch; they are not pre-draftable here because 004A does not exist yet.

---

## 3. Amendment register (summary)

| Existing row | Batch | Kind | Anchor (occurrences of the row-start in checked-in 009) |
|---|---|---|---|
| `:rf.error/no-such-sub` | S2a | + port throwing emit surface (trigger cell only) | exactly 1 (line 1878) |
| `:rf.error/frame-destroyed` | S2a | + port throwing emit surface (trigger cell only) | exactly 1 (line 2052) |
| `:rf.error/jvm-host-op` | S3 | CONDITIONAL emit-surface extension ([S3-CONFIRM] #6) | exactly 1 (line 2281) |
| `:rf.error/ui-tree-malformed` | S5 | + `emit-ui-tree` emit surface; tense flip | exactly 1 (line 2279) |
| `:rf.error/root-manifest-invalid` | S5 | full row rewrite (S5 arms go live) | exactly 1 (line 2293) |
| `:rf.error/frame-payload-conflict` | S5 | full row rewrite (runtime preflight arm goes live) | exactly 1 (line 2294) |
| `:rf.error/root-container-missing` | S5 | clause-level tense flip | exactly 1 (line 2291) |
| `:rf.error/duplicate-root-id` | S5 | clause-level: Layer-2 server tier goes live | exactly 1 (line 2290) |

`:rf.error/root-hydration-mismatch` is NOT in this register — zero occurrences in checked-in 009; it is a new S5 row (§2.4), despite the taxonomy doc's "existing row" phrasing (existing in 03 §11, not in the catalogue).

## 4. Verification appendix

Grep run 2026-07-12 09:19 AUSEST against checked-in `spec/009-Instrumentation.md` (row-start pattern `^\| \`:<id>`):

- **Zero occurrences at draft time (safe to add as new rows):** `read-after-release`, `reentrant-graph-op`, `observation-port-version-mismatch`, `flush-in-open-epoch`, `ui-test-overlapping-act`, `dispatch-disconnected`, `view-not-found`, `frame-payload-invalid`, `root-hydration-mismatch`, `root-render-failed`, `ssr-static-root-requires-runtime`, `compat-camelised-prop`, `unregistered-event-id`, `placeholder-in-dynamic-vector`, `cross-frame-carried-op`, `render-phase-dispatch`, `render-phase-set!` (the two S5 SSR spellings re-verified 2026-07-12, completeness pass: 0 occurrences each in checked-in 009; `:rf.ssr/suspense-boundary-failed` row at line 1911 is the channel/shape precedent for `root-render-failed`). `flush-in-open-epoch` and `ui-test-overlapping-act` have since landed in the checked-in S2 catalogue.
- **Mentioned in prose, no row (safe to add):** `ssr-ui-tree-version-unsupported` (1 mention, inside the `ui-tree-malformed` row).
- **Exactly one row each (amend, never re-add):** the eight register entries above.
- **`:rf.ui.compile/*`:** 2 prose mentions, 0 rows — the compile-error-ids-are-not-catalogue-rows precedent (§2.3 note).
