# guided-interceptors-subs

Type B walkthroughs covering global interceptors, `reg-sub-raw`, opt-in map-payload migration, the surviving v1 interceptors (`on-changes` / `enrich` / `after`), the `:re-frame/lifecycle` annotation, and post-event callbacks. Each section gives the **identification**, the **risk explanation**, and the **decision shape**. The agent identifies and explains; the author decides; the agent then applies.

For handler- / view- / db-seeding- / error-handler-shaped Type B rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).

## Contents

- M-17 — `reg-global-interceptor` in a multi-frame app
- M-18 — `reg-sub-raw` rewrite-path picking
- M-18, signal-fn case — the v1 signal-function `reg-sub` form (3-arity)
- M-19 — opt-in map-payload migration (only if user asked)
- M-21 — `on-changes` / `enrich` / `after`
- M-23 — `:re-frame/lifecycle` annotation drop
- M-26 — `add-post-event-callback` / `remove-post-event-callback`

---

## M-17 — `reg-global-interceptor` in a multi-frame app

**Trigger**: only Type B when the codebase has more than one frame. Single-frame codebases hit the Type A rewrite (move to `:rf/default` `:interceptors`).

> **Correctness trap — all global interceptors for a frame fold into ONE `reg-frame` call.** `reg-frame` re-registration is a **complete replacement** of the metadata map's replaceable slots, not a merge — and `:interceptors` is one of those slots (per [`spec/002-Frames.md` §Re-registration — surgical update](../../../spec/002-Frames.md#re-registration--surgical-update): "the re-registered metadata map is the **complete replacement** of the previous map's replaceable slots, *not* a merge"). So you **cannot** translate N `reg-global-interceptor` calls into N separate `(rf/reg-frame :rf/default {:interceptors [...]})` calls — the **last** call wins and silently wipes every earlier `:interceptors` vector. Fold ALL of a frame's global interceptors into a **single** `reg-frame` call's `:interceptors` vector:
>
> ```clojure
> ;; v1 — two global interceptors
> (rf/reg-global-interceptor my-audit-icpt)
> (rf/reg-global-interceptor recorder-icpt)
>
> ;; v2 — ONE reg-frame, BOTH in the same vector (NOT two reg-frame calls)
> (rf/reg-frame :rf/default
>   {:interceptors [my-audit-icpt recorder-icpt]})
> ```
>
> This applies to the single-frame Type-A rewrite too (it's a correctness fact about re-registration, not a multi-frame concern) — but it's stated here because a multi-frame fold makes the trap easy to walk into when you're replicating "globals" across several `reg-frame` sites.

> **Judgement call — multi-namespace or multi-lifecycle globals are NOT pure Type-A.** When the v1 `reg-global-interceptor` calls are spread across **multiple namespaces**, or registered at **different lifecycles** (e.g. one at ns-load, another *deferred* until after some external dependency has initialised — its interceptor body depends on that init having run), folding them into a single `reg-frame` `:interceptors` vector forces a **single registration site and a single activation moment**. That can change ordering (activating a deferred interceptor too early) — a behavioural decision, not a mechanical rewrite. **Surface it to the author** (where the combined `reg-frame` should live, when it should run relative to the external init), rather than auto-applying. Even a single-frame app trips this judgement case when the globals had staggered lifecycles.

**Identify**: every `(rf/reg-global-interceptor ...)` AND the codebase has any non-default `reg-frame`.

**Risk**: "global" meant "every frame" in v1 because there was only one frame. In v2 the right scope depends on intent:

- If the interceptor was meant to **apply to every frame** (genuinely cross-frame behaviour modification): replicate in each `reg-frame` `:interceptors` vector. Usually an architectural smell.
- If the interceptor was **observer-shaped** (audit, telemetry, schema-validation-via-trace): wrong tool; convert to `register-listener!`.
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

## M-18, signal-fn case — the v1 signal-function `reg-sub` form (3-arity)

(This is an extension of **M-18**, not a separate rule — `MIGRATION.md` M-18 owns the architectural answer for subs whose inputs aren't a static read. It is cited as **M-18** in reports.)

**`reg-sub` is NOT a fully-preserved API.** The breaking-changes "what stays the same" list keeps `reg-sub`'s **layer-1** `(fn [db q])` shape and the **static** `:<-` chains — but v1 had a third shape that v2 dropped: the **3-arity signal-function form**.

```clojure
;; v1 — the signal-function (a.k.a. "signal-fn") form. The MIDDLE fn returns
;; the input subscriptions; the LAST fn is the computation over their values.
(rf/reg-sub :item-detail
  (fn [[_ id] _]            ;; signal-fn — returns the input subs
    [(rf/subscribe [:item id])
     (rf/subscribe [:selected])])
  (fn [[item selected] [_ id]]  ;; computation-fn — over the deref'd inputs
    (assoc item :selected? (= selected id))))
```

**Why this is a runtime trap (not a compile error).** v2 `reg-sub` accepts **only** the layer-1 `(fn [db q])` form or **static** `:<-` chains. Its arg-parser (`re-frame.subs/parse-reg-sub-args`) sees the signal-fn form as *two trailing fns with no `:<-`* and **throws `:rf.error/reg-sub-bad-args` at registration time** (`:recovery :fix-registration`). Because the throw is at *registration / namespace-load*, the code **compiles clean** and only blows up when the namespace loads at runtime — exactly the failure a real migration hit. A blind sweep that treats every `reg-sub` as "preserved, no rewrite" produces this.

**Identify**: every `reg-sub` call with **two trailing fn forms** (a signal-fn followed by a computation-fn) — i.e. the arity that does NOT match `(reg-sub :id (fn [db q] …))` or `(reg-sub :id :<- […] … (fn […] …))`. Grep target: `reg-sub` sites where the form after the id is `(fn …)` and there is a *second* `(fn …)` after it with no intervening `:<-`.

**Risk — the two sub-cases differ sharply:**

- **Static inputs** (the signal-fn's returned subs don't depend on the query vector): mechanical. Maps **1:1 onto a static `:<-` chain** — list each input sub as a `:<-`, keep the computation-fn as-is.
- **Query-DEPENDENT inputs** (the signal-fn closes over the query args to *build* the input subs — `(rf/subscribe [:item id])` where `id` came from `[_ id]`): **there is NO static `:<-` equivalent.** v2's `:input-signals` are fixed query-vectors resolved at registration (per [`spec/006-ReactiveSubstrate.md` §Subscription cache](../../../spec/006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics)); they never receive the consuming sub's query args, and reaching across to another frame's subs is an anti-pattern ([`spec/002-Frames.md`](../../../spec/002-Frames.md)). This is the case M-18 owns architecturally — same gap as `reg-sub-raw` (inputs that aren't a static read), so it takes the **same rewrite paths**.

**Decision shape** (per call site):

1. **Static-inputs signal-fn → static `:<-` chain** (mechanical; author confirms it's truly static):

   ```clojure
   (rf/reg-sub :dashboard
     :<- [:totals]
     :<- [:alerts]
     (fn [[totals alerts] _]
       {:totals totals :alerts alerts}))
   ```

2. **Query-dependent signal-fn → fold to a single layer-1 `reg-sub`** (the common case; the inputs ultimately read `app-db`). Read the query arg in the body and index `db` directly — the whole signal chain collapses:

   ```clojure
   (rf/reg-sub :item-detail
     (fn [db [_ id]]
       (let [item     (get-in db [:items id])
             selected (:selected db)]
         (assoc item :selected? (= selected id)))))
   ```

3. **Query-dependent signal-fn where an input must stay a sub → static `:<-` to the WHOLE collection, index in the body.** Chain `:<-` to a sub that returns the full collection (a fixed query-vector — no query arg), then index by the consuming sub's query arg inside the computation-fn:

   ```clojure
   ;; :all-items is an ordinary layer-1 sub returning the id→item map.
   (rf/reg-sub :item-detail
     :<- [:all-items]
     :<- [:selected]
     (fn [[items selected] [_ id]]
       (assoc (get items id) :selected? (= selected id))))
   ```

4. **The input was a non-app-db reactive source / managed a reaction lifecycle / had side effects**: this is not really a `reg-sub` concern at all — route to the matching **M-18** path (fx-driven, state machine, or anti-pattern-move-to-handler).

Path 2 is the default recommendation for the query-dependent case; path 3 when a genuinely shared, separately-cached intermediate sub must be preserved. **Flag rather than auto-apply** — the agent cannot tell statically whether the signal-fn's inputs are query-dependent, nor whether a preserved intermediate sub is worth keeping. Present the categorisation and the proposed path; the author confirms before each is applied.

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
2. **Post-handler validation** → registered `:schema` per Spec 010 (Malli schema on the registration's metadata map; `:spec` is no longer accepted — see M-54).
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

**Risk**: the v2 sub-cache uses a single algorithm — synchronous ref-counting (dispose on derefer-count → 0, per rf2-cmfln). The four v1 lifecycle policies don't exist; specific edge cases that genuinely needed `:no-cache` or `:forever` are uncovered.

**Decision shape**:

1. Drop the annotation; the default policy almost always covers the case.
2. If the author confirms a real need for the non-default policy (the call site explanation matters), **flag it for the author** and — with their approval — file a GitHub issue against `day8/re-frame2` naming the use case (see [`SKILL.md`](../SKILL.md) cardinal rule 7 for the `--body-file` filing recipe). Don't invent a v2 API.

---

## M-26 — `add-post-event-callback` / `remove-post-event-callback`

**Identify**: every `(rf/add-post-event-callback ...)` / `(rf/remove-post-event-callback ...)`.

**Risk**: the v1 per-frame post-event hook is subsumed by the trace listener API in most cases, but if the callback was behaviour-modifying (rare; should have been a frame-level interceptor), the trace listener is the wrong tool.

**Decision shape**:

1. **Observer-shaped callback**: convert to a listener. `(rf/register-listener! key cb)` is **dev-only** (production-elided); if the callback must keep firing in production (off-box telemetry, error egress) use the always-on `register-error-listener!` / `register-event-listener!` instead — see [`error-events.md` §Production elision](error-events.md#production-elision--what-elides-and-what-stays-always-on). Listeners see every dispatched event; filter on `:operation` / `:op-type` for the equivalent. The closed catalogue of `:operation` keywords and `:op-type` values lives in [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue) — see [`error-events.md`](error-events.md) for the pointer.
2. **Behaviour-modifying callback**: convert to a frame-level interceptor declared in `reg-frame` metadata.

Read the callback body; categorise; propose; author confirms.

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every `reg-sub-raw` that only reads `app-db`, just rewrite to `reg-sub`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
