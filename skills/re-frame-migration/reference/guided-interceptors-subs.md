# guided-interceptors-subs

Type B walkthroughs covering global interceptors, `reg-sub-raw`, opt-in map-payload migration, the surviving v1 interceptors (`on-changes` / `enrich` / `after`), the `:re-frame/lifecycle` annotation, and post-event callbacks. Each section gives the **identification**, the **risk explanation**, and the **decision shape**. The agent identifies and explains; the author decides; the agent then applies.

For handler- / view- / db-seeding- / error-handler-shaped Type B rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../spec/MIGRATION.md).

## Contents

- M-17 — `reg-global-interceptor` in a multi-frame app
- M-18 — `reg-sub-raw` rewrite-path picking
- M-19 — opt-in map-payload migration (only if user asked)
- M-21 — `on-changes` / `enrich` / `after`
- M-23 — `:re-frame/lifecycle` annotation drop
- M-26 — `add-post-event-callback` / `remove-post-event-callback`

---

## M-17 — `reg-global-interceptor` in a multi-frame app

**Trigger**: only Type B when the codebase has more than one frame. Single-frame codebases hit the Type A rewrite (move to `:rf/default` `:interceptors`).

**Identify**: every `(rf/reg-global-interceptor ...)` AND the codebase has any non-default `reg-frame`.

**Risk**: "global" meant "every frame" in v1 because there was only one frame. In v2 the right scope depends on intent:

- If the interceptor was meant to **apply to every frame** (genuinely cross-frame behaviour modification): replicate in each `reg-frame` `:interceptors` vector. Usually an architectural smell.
- If the interceptor was **observer-shaped** (audit, telemetry, schema-validation-via-trace): wrong tool; convert to `register-trace-cb!`.
- If "global" really meant **"the default frame's events"** (a common single-frame habit that shouldn't apply to story/test/SSR frames): scope to `:rf/default` `:interceptors` only.

`clear-global-interceptor` has no v2 replacement: re-register the frame with an updated `:interceptors` vector.

**Decision shape** (per interceptor):

1. Read the interceptor body. Modifies behaviour? Observes only? Both?
2. Present the three rewrite paths with a recommendation based on the body.
3. Author confirms; apply.

---

## M-18 — `reg-sub-raw` rewrite-path picking

**Identify**: every `(rf/reg-sub-raw :id ...)`.

**Risk**: `reg-sub-raw` is gone. The substrate has explicit replacements for each legitimate use; some patterns are anti-patterns that v2 deliberately removes (subs with side effects, subs that hold state outside `app-db`).

**Decision shape** — read the raw body and pick:

1. **Body reads only `app-db`**: convert to `reg-sub`. Most call sites hit here. Mechanical when the body is straightforward.
2. **Body subscribes to a non-app-db reactive source** (WebSocket, timer, external pub/sub): convert to a registered fx that dispatches events; the sub reads `app-db`. See Pattern-AsyncEffect.
3. **Body manages reaction lifecycle** (explicit track/dispose, on-mount/on-dispose hooks): convert to a state machine. The machine has entry/exit/data lifecycle; the snapshot lives in `app-db`.
4. **Body has side effects** (writes to `app-db`, fires `dispatch`, mutates external state): anti-pattern. Move the side effect into an event handler; the sub reads the resulting `app-db` state. **Flag as a code-quality finding** alongside the rewrite.

Present the categorisation per call site with the proposed rewrite; the author confirms before each is applied.

---

## M-19 — Opt-in map-payload migration

**Trigger**: **only if the author has explicitly asked for opt-in modernisations.** Never as part of a routine v1→v2 migration.

**Identify**: every multi-positional dispatch / subscribe call. The trigger is intent (the author chooses per-event-id when to migrate); the rewrite is mechanical given good information.

**Risk**: rewriting one side (dispatch site) without the other (registration destructure) breaks the runtime. Rewrites must be atomic per event-id.

**Decision shape** (per event-id):

1. Find the registration for the id. Read the handler's positional destructure: `[_ [_ email password]]` → parameter names `email`, `password`.
2. Walk every dispatch / subscribe call site for the id.
3. Propose the rewrite: `(rf/dispatch [:user/login email password])` → `(rf/dispatch [:user/login {:email email :password password}])`; registration's destructure changes to `[_ [_ {:keys [email password]}]]`.
4. **Flag rather than guess** when:
   - The handler's destructure is anonymous (`[_ event]` with no inner shape) — agent can't infer names.
   - The dispatch is built dynamically (`(rf/dispatch (cons :user/login args))`).
   - Mixed-arity dispatches for the same id (some 2-arg, some 3-arg).
   - Trivial-arity (`[:counter/inc]`) and single-arg (`[:user-by-id 42]`) — do **not** migrate; they stay as-is.

`unwrap` users are pre-canonical at the call site; only the destructure may need a cleanup.

`trim-v` users: drop `trim-v` from the interceptor list and rewrite the destructure to skip the id slot manually.

---

## M-21 — `on-changes` / `enrich` / `after`

**Identify**: each of these three interceptors in any registration's interceptor list. Apply the M-21 mechanical drops for `debug` / `trim-v` first (in [`auto-cross-cutting.md`](auto-cross-cutting.md)); these three are Type B.

### `on-changes`

**Risk**: the v1 interceptor is gone. v2 ships flows as the registered, toggleable replacement.

**Decision shape**: rewrite `(rf/on-changes f out-path & in-paths)` as a flow:

```clojure
(rf/reg-flow
  {:id     <picked-id>
   :inputs <in-paths>
   :output f
   :path   <out-path>})
```

The author picks the flow's `:id`; the agent suggests `:legacy/<original-event-id>` as a starting point. Also: add `day8/re-frame2-flows` dep + `(:require [re-frame.flows])`.

### `enrich`

**Risk**: ran an arbitrary fn `:after` the handler; could modify `db`. Three replacement paths:

1. **Computing derived state** → Spec 013 flow. Same rewrite as `on-changes`.
2. **Post-handler validation** → registered `:spec` per Spec 010 (Malli schema on the registration's metadata map).
3. **Imperative escape hatch** → custom `->interceptor` with the original body.

Read the `enrich` body and propose the path; author confirms.

### `after`

**Risk**: ran an arbitrary fn `:after` for side effects. Three replacement paths:

1. **Pure side effect, event-shaped** (analytics, logging, telemetry): canonical replacement is a registered fx returned from the handler: `:fx [[:analytics/track ...]]`.
2. **Must run for every event of a kind**: user-defined `(rf/->interceptor :id :my-thing :after (fn [ctx] ...))`. Named, addressable, queryable.
3. **Vendor-from-v1**: copy `re-frame.std-interceptors/after` into the project as a 7-line utility. Acceptable if the codebase uses it widely as convention.

Read the body and propose; author confirms.

---

## M-23 — `:re-frame/lifecycle` annotation drop

**Identify**: `:re-frame/lifecycle` keys in `reg-sub` metadata (pre-v1 alpha-namespace usage). The mechanical alpha → core rewrite drops these. Type B comes in if the annotation was non-default (`:no-cache`, `:forever`, `:reactive`).

**Risk**: the v2 sub-cache uses a single algorithm — deferred ref-counting with grace-period. The four v1 lifecycle policies don't exist; specific edge cases that genuinely needed `:no-cache` or `:forever` are uncovered.

**Decision shape**:

1. Drop the annotation; the default policy almost always covers the case.
2. If the author confirms a real need for the non-default policy (the call site explanation matters), **file a follow-up bead** naming the use case. Don't invent a v2 API. Surface to Mike.

---

## M-26 — `add-post-event-callback` / `remove-post-event-callback`

**Identify**: every `(rf/add-post-event-callback ...)` / `(rf/remove-post-event-callback ...)`.

**Risk**: the v1 per-frame post-event hook is subsumed by the trace listener API in most cases, but if the callback was behaviour-modifying (rare; should have been a frame-level interceptor), the trace listener is the wrong tool.

**Decision shape**:

1. **Observer-shaped callback**: convert to `(rf/register-trace-cb! key cb)`. Trace listeners see every dispatched event; filter on `:operation` / `:op-type` for the equivalent. The closed catalogue of `:operation` keywords and `:op-type` values lives in [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue) — see [`error-events.md`](error-events.md) for the pointer.
2. **Behaviour-modifying callback**: convert to a frame-level interceptor declared in `reg-frame` metadata.

Read the callback body; categorise; propose; author confirms.

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every `reg-sub-raw` that only reads `app-db`, just rewrite to `reg-sub`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
