# guided-interceptors-subs

Type B walkthroughs covering global interceptors, `reg-sub-raw`, the v1 signal-function `reg-sub` form (→ v2 `input-fn`s), opt-in map-payload migration, the surviving v1 interceptors (`on-changes` / `enrich` / `after`), the `:re-frame/lifecycle` annotation, and post-event callbacks. Each section gives the **identification**, the **risk explanation**, and the **decision shape**. The agent identifies and explains; the author decides; the agent then applies.

For handler- / view- / db-seeding- / error-handler-shaped Type B rewrites, see [`guided-handlers-state.md`](guided-handlers-state.md). For Type A patterns, see [`auto-call-site-rewrites.md`](auto-call-site-rewrites.md) and [`auto-cross-cutting.md`](auto-cross-cutting.md). For full rule rationale, see [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).

## Contents

- M-17 — `reg-global-interceptor` in a multi-frame app
- M-18 — `reg-sub-raw` rewrite-path picking
- M-71 — the v1 signal-function `reg-sub` form (3-arity) → v2 `input-fn`s
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
> ;; v2 — register each value once, then ONE reg-frame referencing BOTH by id,
> ;; in the same vector (NOT two reg-frame calls).
> ;; A frame `:interceptors` chain carries references, never inline values (EP-0022) —
> ;; an inline value here throws :rf.error/inline-interceptor-removed.
> (rf/reg-interceptor :app/audit  my-audit-icpt)
> (rf/reg-interceptor :app/record recorder-icpt)
> (rf/reg-frame :rf/default
>   {:interceptors [:app/audit :app/record]})
> ```
>
> This applies to the single-frame Type-A rewrite too (it's a correctness fact about re-registration, not a multi-frame concern) — but it's stated here because a multi-frame fold makes the trap easy to walk into when you're replicating "globals" across several `reg-frame` sites.

> **Judgement call — multi-namespace or multi-lifecycle globals are NOT pure Type-A.** When the v1 `reg-global-interceptor` calls are spread across **multiple namespaces**, or registered at **different lifecycles** (e.g. one at ns-load, another *deferred* until after some external dependency has initialised — its interceptor body depends on that init having run), folding them into a single `reg-frame` `:interceptors` vector forces a **single registration site and a single activation moment**. That can change ordering (activating a deferred interceptor too early) — a behavioural decision, not a mechanical rewrite. **Surface it to the author** (where the combined `reg-frame` should live, when it should run relative to the external init), rather than auto-applying. Even a single-frame app trips this judgement case when the globals had staggered lifecycles.

> **An interceptor id-reference is NOT a load-order dependency.** Folding `reg-global-interceptor` values into a frame's `{:interceptors [:app/audit :app/record]}` replaces direct **value** references (which made the defining ns a `:require` dependency) with **id lookups** — keywords that create **no** load-order edge to the ns whose `reg-interceptor` registers them. If the migration then drops that ns's `:require` because the value "looks dead", the `reg-interceptor` may not have run when this `reg-frame` validates its refs at registration — the boot throws `:rf.error/unregistered-interceptor`. Keep a side-effecting `:require` of the interceptor-registry ns (or load all interceptor-registering nses early from a foundational ns the whole app requires), before any `reg-event` / `reg-frame` references them. "Dropping the require because the value isn't used anymore" is the trap. Full version (and the same hazard for the M-70 chain-shape rewrite): [`auto-cross-cutting.md` §M-70](auto-cross-cutting.md#event-interceptor-chains--metadata-interceptors-m-70--mechanical-loud-at-runtime-not-loud-at-compile).

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
3. **Body manages reaction lifecycle** (explicit track/dispose, on-mount/on-dispose hooks): convert to a state machine. The machine has entry/exit/data lifecycle; its snapshot lives in the **runtime-db** partition at `[:rf.runtime/machines :snapshots <id>]` (not in app-db) and is read via `sub-machine`.
4. **Body has side effects** (writes to `app-db`, fires `dispatch`, mutates external state): anti-pattern. Move the side effect into an event handler; the sub reads the resulting `app-db` state. **Flag as a code-quality finding** alongside the rewrite.

Present the categorisation per call site with the proposed rewrite; the author confirms before each is applied.

---

## M-71 — the v1 signal-function `reg-sub` form (3-arity) → v2 `input-fn`s

**Mental model — v1 *signal function* → v2 *`input-fn`*.** v1's two-function
`reg-sub` form took a **signal function**: a fn from the outer query vector to
**live `subscribe` reactions** that the runtime then deref'd for the
computation fn. re-frame2 keeps the two-function form but redefines that first
fn as an **`input-fn`**: a pure fn from the outer query vector to a **vector of
query vectors** (plain data — *not* reactions). The runtime resolves those
query vectors in the same frame and hands the resolved **values** to the
computation fn. The shape of the call site is the same; only what the first fn
*returns* changes — reactions become query-vector data.

```clojure
;; v1 signal fn — returns live reactions
(fn [[_ id]] [(rf/subscribe [:x id]) (rf/subscribe [:y])])

;; v2 input-fn — returns query vectors (data)
(fn [[_ id]] [[:x id] [:y]])
```

This is **intentionally breaking** vs v1, and it is the dedicated rule
**M-71** — cite it as **M-71** in reports (it is *not* the `reg-sub-raw`
removal, which is **M-18** above). The authoritative rule text + rationale is
[`MIGRATION.md` §M-71](../../../migration/from-re-frame-v1/README.md#m-71-v1-signal-functions--v2-input-fns-vector-of-query-vectors);
the design rationale is the [Parametric Subscription Inputs spec](../../../spec/006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn).

**The `input-fn` contract** (all four facts are the break — a v1 signal fn
could violate every one):

- It **receives only the outer query vector** — no `db` arg, no extra args. (v1
  signal fns sometimes took extra args; v2 `input-fn`s do not.)
- It **must return a vector of query vectors** — `[[:a x] [:b]]`. A bare
  query vector (`[:a x]`), a bare keyword (`:a`), or a map is rejected.
- It **must not call `subscribe`**, deref `app-db`, dispatch, mutate, or do IO —
  it returns *descriptions* of inputs, and the runtime resolves them.
- It **must not choose its dependency topology from `app-db`** — that would
  break the fixed-topology-per-cache-entry invariant. Thread any state-derived
  parameter through the **outer query vector** at the call site instead.

**Identify:** every `reg-sub` call with **two trailing fn forms and no `:<-`
between them** — `(rf/reg-sub :id (fn [q] …) (fn [inputs q] …))`. The first fn
is the v1 signal fn. (A single trailing fn is the unchanged layer-1
`(fn [db q] …)` app-db reader; a `:<- [q] :<- [q]` chain is the unchanged
static form — neither trips M-71.)

> **A signal fn coming from `re-frame.alpha` lands here too.** An alpha-namespace
> `reg-sub` whose signal fn called `(sub [:x id])` is removed by **M-23** at the
> namespace level (`re-frame.alpha` → `re-frame.core`), but its signal-fn body is
> **M-71's** reshape, **not** the uniform `(sub <vector>) → (subscribe <vector>)`
> rewrite in [`auto-call-site-rewrites.md` §M-23](auto-call-site-rewrites.md#m-23--re-framealpha-removal-mechanical-half).
> Inside the signal fn, `(sub [:x id])` becomes the bare query **vector** `[:x id]`
> (returned inside the input-fn's vector), never a `(subscribe [:x id])` call —
> that would throw `:rf.error/sub-input-fn-bad-return`. Classify and reshape per
> the cases below.

**Why it is silent at compile — and where it actually fails.** The two-function
shape *parses* fine, so the compiler says nothing. It also **registers** fine:
the v2 runtime reads `(reg-sub :id (fn …) (fn …))` (two trailing fns, no `:<-`)
as a **valid `:parametric` registration** — both trailing args are functions, so
there is **no `:rf.error/reg-sub-bad-args` at registration / namespace load**.
The break surfaces later, at the **first `subscribe` / materialization**: the v1
signal fn returns live reactions (or a bare reaction), which are not query
vectors, so the runtime throws `:rf.error/sub-input-fn-bad-return` and the sub
**recovers to a `nil`-yielding reaction** (it is *never* silently treated as
no-inputs — the error rides the always-on error listener + the dev trace). Under
M-71 the same shape becomes **valid** once you swap the reactions for query
vectors. `:rf.error/reg-sub-bad-args` is reserved for a genuinely unparseable
registration *shape* (e.g. three trailing fns, or a leading `:<-` with no query
vector) — **not** for a v1-style signal-fn body. Either way the compiler is no
help — grep every signal-fn site exhaustively up front (this is a silent-fail
rule; see
[`breaking-changes.md` §silent-fail register](breaking-changes.md#failure-visibility-axis--loud-fail-vs-silent-fail-orthogonal-to-type-ab)),
never march-the-wall.

**Decision-shape — first prefer `:<-` for static inputs.** If the signal fn's
inputs do **not** depend on the outer query vector, the inputs are static —
prefer a `:<-` chain, the same as v1's preferred static form. `:<-` is sugar
for a constant `input-fn`; it is the best style whenever it applies.

```clojure
(rf/reg-sub :dashboard
  :<- [:totals]
  :<- [:alerts]
  (fn [[totals alerts] _]
    {:totals totals :alerts alerts}))
```

When the inputs **do** depend on the outer query vector, rewrite the signal fn
to an `input-fn`. **Classify by what the v1 signal fn returns** — the three
v1 return shapes each rewrite differently:

### 1. Vector-returning (the common case) — drop the `subscribe`, return query vectors

Strip the `(rf/subscribe …)` wrappers; return the bare query vectors. The
computation fn already destructures a vector of inputs in the same order — it is
**unchanged**.

```clojure
;; v1 — signal fn returns a vector of live reactions
(rf/reg-sub :item/detail
  (fn [[_ id]]
    [(rf/subscribe [:item/by-id id])
     (rf/subscribe [:selection/current])])
  (fn [[item selected] [_ id]]
    (assoc item :selected? (= selected id))))

;; v2 — input-fn returns a vector of query vectors
(rf/reg-sub :item/detail
  (fn [[_ id]]
    [[:item/by-id id]
     [:selection/current]])
  (fn [[item selected] [_ id]]
    (assoc item :selected? (= selected id))))
```

### 2. Map-returning — pick an EXPLICIT input order, switch to vector destructuring

v2 does **not** accept a map return. Choose an explicit input order, return a
**vector of query vectors** in that order, and change the computation fn from
**map destructuring to vector destructuring** to match.

> **Do NOT rely on source-map iteration order.** A v1 map of signals had no
> meaningful order — Clojure map iteration order is not a contract. Pick a
> deliberate order yourself and preserve it across the `input-fn` *and* the
> computation fn's destructure. Reading the order off the source map's literal
> key sequence is a latent bug.

```clojure
;; v1 — signal fn returns a MAP of live reactions
(rf/reg-sub :item/detail
  (fn [[_ id]]
    {:item     (rf/subscribe [:item/by-id id])
     :selected (rf/subscribe [:selection/current])})
  (fn [{:keys [item selected]} [_ id]]      ;; map destructuring
    (assoc item :selected? (= selected id))))

;; v2 — explicit input order + vector destructuring
(rf/reg-sub :item/detail
  (fn [[_ id]]
    [[:item/by-id id]                        ;; chosen order: item, then selected
     [:selection/current]])
  (fn [[item selected] [_ id]]              ;; vector destructuring, same order
    (assoc item :selected? (= selected id))))
```

### 3. Single-signal-returning — wrap in a vector of ONE query vector

v2 has **no scalar single-input form**. A v1 signal fn that returned one bare
reaction becomes an `input-fn` returning `[[:item/by-id id]]` — a **vector of
one query vector**, not the bare query vector. The computation fn destructures
a one-element vector: `(fn [[item] _] …)`.

```clojure
;; v1 — signal fn returns ONE bare reaction
(rf/reg-sub :item/title
  (fn [[_ id]]
    (rf/subscribe [:item/by-id id]))
  (fn [item _]
    (:title item)))

;; v2 — input-fn returns a vector of ONE query vector
(rf/reg-sub :item/title
  (fn [[_ id]]
    [[:item/by-id id]])
  (fn [[item] _]                            ;; destructure the one-element vector
    (:title item)))
```

The two extra brackets are load-bearing: `[:item/by-id id]` is **one query
vector** (rejected as a scalar return); `[[:item/by-id id]]` is **a vector
containing one query vector** (the only accepted single-input spelling). `[:x :y]`
is *never* read as an `input-fn` return — only as a single query vector *inside*
`[[:x :y]]`.

### The BREAK — what v2 rejects

v1 signal functions could do all of the following; v2 `input-fn`s reject every
one. These are the shapes to flag, not silently "fix":

| v1 signal-fn shape | v2 status |
|---|---|
| Returns a live reaction (`(rf/subscribe …)`) | **Rejected** — return the query vector instead (cases 1–3 above). |
| Returns a **map** of inputs | **Rejected** — pick an explicit order + vector destructure (case 2). |
| Returns a **bare keyword** (`:viewer/current`) | **Rejected** — no shorthand; spell it `[[:viewer/current]]`. |
| Returns a **scalar query vector** (`[:item id]`) | **Rejected** — wrap it: `[[:item id]]` (case 3). |
| Receives **extra args** beyond the outer query vector | **Rejected** — the `input-fn` receives only `query-v`. |
| Reads `app-db` to choose inputs | **Rejected** — thread the parameter through the outer query vector (below). |

A bad return signals `:rf.error/sub-input-fn-bad-return`; an `input-fn` that
throws signals `:rf.error/sub-input-fn-exception`; a malformed `reg-sub`
registration shape signals `:rf.error/reg-sub-bad-args` (see
[`error-events.md`](error-events.md) →
[Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)).

### `app-db`-reading signal fn — FLAG, don't auto-rewrite

If the v1 signal fn **derefs `app-db`** (or otherwise picks inputs from state),
the `input-fn` cannot read `app-db`. **Flag for human review.** The rewrite is
to thread the state-derived parameter through the **outer query vector at the
call site** — so each concrete cache entry has stable dependencies:

```clojure
;; at the call site, the param comes from another subscribe
(let [article-id @(rf/subscribe [:current-route/article-id])
      page       @(rf/subscribe [:article/page article-id])]
  …)
```

The graph stays dynamic at the view boundary (where React already manages
subscription lifecycle), and each `[:article/page article-id]` cache entry has
fixed inputs for its lifetime. See
[Spec 006 §No app-db-dependent topology](../../../spec/006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn).

### Other-substrate cases (non-app-db reactive source, lifecycle, side effects)

If the signal-fn body is doing something a `reg-sub-raw` would (subscribing to a
non-app-db reactive source, managing reaction lifecycle, or side-effecting),
that is the **M-18** `reg-sub-raw` decision tree above — route to the matching
path (fx-driven state, state machine, or move the side effect into a handler),
not an `input-fn`.

**Do not auto-apply M-71 rewrites blindly.** It is **Type B**: the
vector-returning case is mechanical, but a **map-returning** signal fn forces
the explicit-order choice (case 2) and an **`app-db`-reading** signal fn must
thread the parameter through the outer query vector — both are intent the agent
cannot recover statically. Classify the return shape, present the proposed
rewrite, and let the author confirm.

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
  {:id          <picked-id>
   :inputs      <in-paths>
   :derive      f
   :output-path <out-path>})
```

The author picks the flow's `:id`; the agent suggests `:legacy/<original-event-id>` as a starting point. Also: add `day8/re-frame2-flows` dep + `(:require [re-frame.flows])`.

### `enrich`

**Risk**: ran an arbitrary fn `:after` the handler; could modify `db`. Three replacement paths:

1. **Computing derived state** → Spec 013 flow. Same rewrite as `on-changes`.
2. **Post-handler validation** → registered `:schema` per Spec 010 (Malli schema on the registration's metadata map; `:spec` is no longer accepted — see M-54).
3. **Imperative escape hatch** → register the original body with `reg-interceptor` (the public `context -> context` authoring form; `->interceptor` is internal-only under EP-0022) and reference it by id from the event's `:interceptors` chain.

Read the `enrich` body and propose the path; author confirms.

### `after`

**Risk**: ran an arbitrary fn `:after` for side effects. Three replacement paths:

1. **Pure side effect, event-shaped** (analytics, logging, telemetry): canonical replacement is a registered fx returned from the handler: `:fx [[:analytics/track ...]]`.
2. **Must run for every event of a kind**: register the behaviour with `(rf/reg-interceptor :my/thing {:after (fn [ctx] ...)})` and reference `:my/thing` from each affected event's (or the frame's) `:interceptors` chain. Named, addressable, queryable — `reg-interceptor` is the public authoring form (EP-0022), not `->interceptor`.
3. **Vendor-from-v1**: copy `re-frame.std-interceptors/after` into the project as a 7-line utility. Acceptable if the codebase uses it widely as convention.

Read the body and propose; author confirms.

---

## M-23 — `:re-frame/lifecycle` annotation drop

**Identify**: `:re-frame/lifecycle` keys in `reg-sub` metadata (pre-v1 alpha-namespace usage). The mechanical alpha → core rewrite drops these. Type B comes in if the annotation was non-default (`:no-cache`, `:forever`, `:reactive`).

**Risk**: the v2 sub-cache uses a single algorithm — synchronous ref-counting (dispose on derefer-count → 0). The four v1 lifecycle policies don't exist; specific edge cases that genuinely needed `:no-cache` or `:forever` are uncovered.

**Decision shape**:

1. Drop the annotation; the default policy almost always covers the case.
2. If the author confirms a real need for the non-default policy (the call site explanation matters), **flag it for the author** and — with their approval — file a GitHub issue against `day8/re-frame2` naming the use case (see the shared [`issue-filing.md`](../../shared/issue-filing.md) recipe for the `--body-file` filing shape). Don't invent a v2 API.

---

## M-26 — `add-post-event-callback` / `remove-post-event-callback`

**Identify**: every `(rf/add-post-event-callback ...)` / `(rf/remove-post-event-callback ...)`.

**Risk**: the v1 per-frame post-event hook is subsumed by the trace listener API in most cases, but if the callback was behaviour-modifying (rare; should have been a frame-level interceptor), the trace listener is the wrong tool.

**Decision shape**:

1. **Observer-shaped callback**: convert to a listener. `(rf/register-listener! :trace key cb)` is **dev-only** (production-elided); if the callback must keep firing in production (off-box telemetry, error egress) use the always-on streams of the same verb — `(rf/register-listener! :events key cb)` / `(rf/register-listener! :errors key cb)` — instead — see [`error-events.md` §Production elision](error-events.md#production-elision--what-elides-and-what-stays-always-on). Listeners see every dispatched event; filter on `:operation` / `:op-type` for the equivalent. The closed catalogue of `:operation` keywords and `:op-type` values lives in [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue) — see [`error-events.md`](error-events.md) for the pointer.
2. **Behaviour-modifying callback**: convert to a frame-level interceptor declared in `reg-frame` metadata.

Read the callback body; categorise; propose; author confirms.

---

## Anti-pattern: silent rewrites

The Type B rules exist because the rewrite **cannot** be inferred from the call site alone. If you find yourself wanting to "just rewrite" one of these without asking — stop. The whole point of Type B is that asking is cheaper than rolling back a wrong rewrite.

The only Type B item the agent can apply without asking is when the author has pre-authorised a specific decision shape upfront (e.g. "for every `reg-sub-raw` that only reads `app-db`, just rewrite to `reg-sub`; flag the rest"). Bank those pre-authorisations in the report so the author can audit.
