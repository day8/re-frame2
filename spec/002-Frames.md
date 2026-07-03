# Spec 002 — Frames

> **What this Spec is about.** A *frame* is an isolated runtime boundary — multi-instance widget, per-test fixture, per-request server-side render — all the same shape. The pattern's contract is **explicit-frame addressing**: every dispatch and subscribe targets a specific frame at the call site. The CLJS reference's React-context-driven view injection (in §View ergonomics) is an *ergonomic optimisation* atop that contract, not a pattern-level commitment.
>
> For the bird's-eye view of where the frame container, router, drain loop, and `do-fx` sit in relation to the registrar, sub-cache, substrate adapter, and trace bus, see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

A **frame** is an **isolated runtime boundary**, identified by keyword, that owns the runtime state of a re-frame application: its `app-db`, its event router/queue, and its subscription cache. Multiple frames can coexist — multi-instance on a page (devcards, isolated widgets, serial test instances), per server-side request — and live independently.

> **Terminology:** "isolated runtime boundary" is the canonical definition. Other Specs sometimes describe a frame in terms of a particular role it plays — *actor-system boundary* (Spec 005, when describing message-passing semantics), *frame contract* (Spec 006, when describing what the substrate-agnostic core requires from an adapter), *per-request runtime* (Spec 011, when describing SSR). All refer to the same thing under different aspects.

> **Not designed: long-lived server-side *session* frames.** The backed frame use cases are all short-lived or client-local: multi-instance widgets on a page, per-test fixtures, and per-request SSR frames (created, rendered, and destroyed within one request — see [Spec 011](011-SSR.md)). A frame held open across many requests to back a *server-side user session* is **out of scope and undesigned**: its lifetime (when does it end?), eviction (what bounds memory across N concurrent sessions?), and JVM concurrency (frames are single-threaded drain loops — a session frame touched by concurrent request threads has no defined contract) are all open questions with no ruling here. Do **not** build a port or a consumer on a session-scoped frame as though it were an advertised capability; it has no contract. Server-side session *state* belongs in the request/response cycle (per-request frame seeded from session storage), not in a resident frame.

All frames share **one global handler registrar**. Multi-frame means "multiple instances of the same app's handlers" — devcards, isolated widgets, story variants, test fixtures — not "multiple different apps with different handler sets on one page." The latter use case (micro-frontends, embedded white-label widgets) is out of scope; iframes already serve it.

**Single-frame is one shape of multi-frame.** An app with one frame in play is a multi-frame app — same runtime, same routing, same drain loop — that has established exactly one frame scope. Inside that scope every dispatch and subscription stays ambient and ergonomic; the single frame is invisible *inside its own scope*. There is **no** process-global `:rf/default` that catches operations issued under **no** scope at all: per [§Frame target resolution](#frame-target-resolution--the-carried-invariant) below (EP-0002), the runtime never synthesises a frame from absence. `:rf/default` remains a legal frame id a small app or test may **explicitly** choose; it is not a fallback the runtime infers.

## Goals

This Spec inherits the constraints and goals from 000 and adds three frame-specific design rules:

- **Frame plurality is invisible inside a frame's scope.** Once a root, provider, or lexical binding has established a frame, no new API surfaces in user code — dispatch and subscribe stay ambient and ergonomic inside that scope. What is **not** invisible is the absence of *any* scope: a frame-scoped operation issued with no carried frame and no established scope is a loud error, not a silent write to a conventional default (per [§Frame target resolution](#frame-target-resolution--the-carried-invariant), EP-0002). Plurality is invisible *inside* a scope; there is no rootless invisibility.
- **Frame identity is carried, not found.** Frame identity is a value that travels with every causal token — a dispatch, an fx context, a captured callback, an epoch record, an SSR payload. An operation reads its frame from the token it holds; it never discovers one from the ambient world. This is the EP-0002 carried invariant, normatively stated in [§Frame target resolution](#frame-target-resolution--the-carried-invariant).
- **Frame identity is a value, not a reference.** Frames are addressed by **frame id** (a keyword) in user code; that id is the whole public routing address. The live **frame value** the constructor hands back is a lifecycle token — it owns the id, the durable partitions, the resolved image generation, and the lifecycle hooks — but its representation is hidden, and it is not the app-facing operation target (per [EP-0024 §Operation target grammar](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)). One live frame value backed by one live registry; there is no second backing-record registry to keep coherent.

## API at a glance

```clojure
;; Lifecycle — one constructor, one teardown (EP-0024)
(rf/reg-frame   :todo {:initial-events [[:todo/initialise]]})  ;; named create + register, atomic; app-db starts {}
(rf/reg-frame   :todo {:initial-events [[:todo/initialise]]})  ;; against existing — surgical update (config replaced; runtime state preserved)
(def f (rf/make-frame {:id :todo :images [todo-image]    ;; the one constructor: image-selection + record-config opts
                       :initial-events [[:rf/set-db {}]] :fx-overrides {…}}));; returns the live frame VALUE; read its id via the accessor
(rf/reset-frame! :todo)                                    ;; explicit full replace — re-dispatches the recorded :initial-events
(rf/destroy-frame! :todo)                                  ;; tear down — remove the unified value from the one registry

;; View ergonomics — ensure a named frame vs scope to an existing one (EP-0024 amended)
[rf/frame-provider {:id :todo :images [todo-image]}     ;; ENSURE: create-if-absent, reuse-no-reseed, provide id, NO destroy-on-unmount
 [todo-list]]
[rf/frame-provider {:frame :todo} [todo-list]]          ;; SCOPE an existing frame into a React subtree (fails loud if absent)
[rf/with-frame :todo [todo-list]]                       ;; SCOPE to an existing frame (lexical / non-React): just establishes context
(rf/reg-view counter [label] ,,,)               ;; defn-shape; injects frame-bound `dispatch`/`subscribe`

;; Plain (non-view) APIs — frame-aware variants
(rf/dispatch      [:foo])                          ;; ambient — requires an established frame scope (else :rf.error/no-frame-context)
(rf/dispatch      [:foo] {:frame :todo             ;; opts map extends the dispatch envelope; explicit override always works
                          :fx-overrides {:my-app/http stub-fn}})
(rf/dispatch-sync [:foo] {:fx-overrides {...}})    ;; same opts-arg shape, sync variant
(rf/subscribe     [:bar])                          ;; ambient — requires an established frame scope
(rf/subscribe     [:bar] {:frame :todo})           ;; opts arg targets a specific frame

;; Test/REPL helper
(rf/with-frame :todo
  (rf/dispatch-sync [:init])
  @(rf/subscribe [:status]))
```

## Frame target resolution — the carried invariant

> **What this section is.** This is the normative core of frame addressing — the
> contract that decides *which frame a frame-scoped operation acts on*. It is the
> spec realisation of [EP-0002 (Explicit Frame Target Resolution)](../docs/EP/EP-0002-frame-target-resolution.md).
> It is small: the rule is one invariant and a short derivation. The
> migration that retires the `:rf/default` fallback is the EP's
> implementation chain, not this section.

### The invariant

> **Frame identity is carried, not found.** Frame identity is a value that travels
> with every **causal token** — a dispatch envelope, an fx context, a captured
> callback, an epoch record, a trace event, an SSR payload. A frame-scoped operation
> reads its frame from the token it is holding. **It never discovers one from the
> ambient world, and it never synthesises one from absence.**

A *frame-scoped operation* is any API that reads, writes, clears, registers,
projects, or dispatches against frame-local state — app-db, runtime-db, the
subscription cache, route, machine, HTTP, SSR, trace, epoch, mark, or elision
state. (Process-global registrar enumeration — `frame-ids`, `frame-meta`,
`registrations` — is **frame-neutral**: it does not invent a current frame and is
not routed through this resolver.)

**Absence is the corollary error.** A causal token that carries no frame stamp, in
no established scope, cannot be honoured. The operation fails with
`:rf.error/no-frame-context` rather than repairing absence by selecting a
conventional default. This is the whole rule; everything below is its shape.

### How a frame is carried — scope / hold / override

Frame identity reaches an operation through exactly the three intents the Views
chapter already teaches ([`docs/api/re-frame.core.md`](../docs/api/re-frame.core.md)). There is
**no separate priority-list of "ambient places to search"** — that was the old
four-tier chain, and removing its `:rf/default` floor is what this EP does.

| Intent | Surfaces | What it is |
|---|---|---|
| **scope** | `with-frame` / `with-new-frame`, `frame-provider`, the router's per-handler binding | a frame established for a **synchronous** region — a render subtree, a lexical block, a handler invocation. Ambient *inside* the region. (`with-frame` scopes to an *existing* frame; `frame-provider` and `with-new-frame` *own* a lifetime — they create the frame whose scope they then establish, and destroy it on exit — per [§Scope, carry, and ownership](#the-multi-frame-surface--choose-by-intent) and [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md).) |
| **hold** | `capture-frame`, `frame-bound-fn` / `frame-bound-fn*`, the dispatch envelope threaded through a cascade, a captured frame stamp on any deferred callback | the frame **reified as a value** and carried across boundaries — async hops, tool sessions, fx closures. |
| **override** | the per-call `{:frame …}` opt | the frame named **explicitly at the call site**. Always wins; the right shape for callbacks, tools, tests, SSR. |

At a boundary the three collapse to the only distinction that matters:

- **carried as a value** — *hold* + *override*. Survives any boundary by
  construction; nothing to evaporate.
- **ambient in an established scope** — *scope*. Honest *because the scope is
  explicit*; the danger was never "ambient", it was "ambient with an invented
  floor". Remove the floor and ambient-from-an-explicit-scope is sound.

**`hold` is the primary mental model; `scope` is sugar.** A *scope* evaporates at
the first async hop — `with-frame` "supplies a frame only for the synchronous
evaluation" — which is the same silent-absence failure family this contract
abolishes. So the robust carrier is the **captured handle** (*hold*): `capture-frame`
hands back `{:frame :dispatch :dispatch-sync :subscribe}` *bound to a frame by
construction*; the frame is the functions you are holding, not something ambient
that can unwind. Author async and tooling paths with *hold*; reach for *scope*
(`with-frame`) only inside synchronous roots, never near an async boundary. See
[§capture-frame](#capture-frame--the-keystone-affordance-cljs-reference) and
[§React click-handler routing](#react-click-handler-routing--the-canonical-pattern)
for the worked patterns.

### One carrier, one name — the frame stamp

Frame identity travels under **one canonical, inspectable shape** wherever a causal
token flows: the **frame stamp**. The historically fragmented spellings — `:frame`
(dispatch opt), `:rf.frame/id` (event context, per EP-0001), `:rf/frame-id` (SSR
payload), and the tooling keys `url-owner-frame-id` / `:target-frame` /
`:own-frame` / `default-target-frame` — are unified into this one carrier. The two
public spellings users type are unchanged: **`:frame`** is the dispatch/subscribe
**opt**; **`:rf.frame/id`** is its event-context spelling. Both name the same
stamp.

The bare `:frame` spelling survives only at **sanctioned** sites: the public
dispatch/subscribe **opt**, the dispatch **envelope** key, the binary
**fx-handler ctx** (see [§The binary fx-handler signature](#the-binary-fx-handler-signature))
and the HTTP-interceptor ctx (per [014-HTTPRequests.md](014-HTTPRequests.md)),
and **trace / error-record tags**. It is *retired* as an **event-context
coeffect**: the running frame reaches a handler body under `:rf.frame/id`
only (see [§Event context threads both partitions](#event-context-threads-both-partitions)) —
there is no parallel bare `:frame` coeffect.

The genuinely distinct cases are **roles**, not unrelated keys, and they are
expressed as **qualified** stamps:

| Role | Meaning | Carries |
|---|---|---|
| target | the frame an operation acts on (the default, unqualified stamp) | the frame id |
| URL owner | the frame that owns the browser URL and receives popstate (per [012-Routing.md](012-Routing.md)) | a qualified stamp |
| inspected target | the host frame a tool inspects, distinct from the tool's own frame | a qualified stamp |
| tool own | a tool's own state frame (e.g. `:rf/xray`) | a qualified stamp |

Resolution is then "read the stamp on the token I hold"; `:rf.error/no-frame-context`
is "this token carries no stamp"; conformance is "every causal token either carries
a well-formed stamp or is explicitly classified frameless." One key to teach, one
shape to validate, one thing for tools to render.

### The error and its ladder

`:rf.error/no-frame-context` is reserved for the **absence of a target**, not for a
**bad** target. The two are distinct:

- **Absent target.** No carried stamp, no established scope. Resolution fails with
  `:rf.error/no-frame-context` **before** any frame-registry lookup — so a missing
  context is never mis-reported as `:rf.error/frame-destroyed` for a synthesised
  default.
- **Bad explicit target.** A caller supplies `{:frame :ghost}` explicitly.
  Resolution has *succeeded* (a stamp was carried); the registry lookup then reports
  `:rf.error/frame-destroyed` or another no-such-frame shape per [§Destroy](#destroy).

The frameless error is itself frameless: it is emitted through the **always-on error
axis** (the production-survivable error-emit listener, surface #4 per
[009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production)),
not per-frame epoch capture. It carries **capture-site ancestry** through the
existing `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` correlation graph,
so the hardest case — a callback captured at handler X in frame Y whose continuation
fires with no stamp after the cascade ended — is fully attributed even though the
error has no frame of its own.

A representative payload:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :where       :re-frame.router/dispatch!
 :event-id    :todo/add
 :recovery    :supply-frame}
```

### Two layers — strict core, tiered discovery

The contract is **scoped to where each formulation is correct**. This is the key
reconciliation: the embedded application path and the interactive tool path have
different operator-presence, so they get different rules — *the same stamp*, a
different policy on absence.

- **Embedded app / runtime path — strict (Option C).** No operator is present, a
  wrong target writes silently, and the operation must replay. Absence is
  `:rf.error/no-frame-context`. The justification is **replay determinism +
  temporal non-locality**, not purity: a silently-defaulted frame poisons replay
  (`restore-epoch!`, time-travel, Story / Causa determinism all become unsound), and
  "sole live frame" is true only until a second frame appears — so an ambient floor
  would let adding Xray, Story, or an SSR frame silently change the meaning of
  distant, untouched application code.
- **Interactive discovery layer — tiered (Tool-Pair / Xray / pair-MCP).** An
  operator or agent is driving; ambiguity can prompt. This layer **keeps** its
  proven four-tier operating-frame contract: ① explicit override → ② session-pinned
  selection → ③ **sole registered *app* frame** (reserved `:rf/*` tool frames
  excluded from the count) → ④ refuse. Tier 3 is **unique resolution, not
  synthesis** — inventing a frame from nothing is unsound; observing that exactly
  one registered app frame *is* the answer is a total, honest function — so it is
  **not reconciled away**; it is scoped to the operator-present layer. The full
  contract is owned by [Tool-Pair §Operating-frame resolution](Tool-Pair.md).

**One ladder across both layers.** The three vocabularies that meet at this seam —
`:rf.error/no-frame-context` (core, absent), `:ambiguous-frame` (tool, plural), and
`:rf.tool/no-frame-selected` (tool, unselected) — are reconciled into **one ordered
ladder**: **absent → ambiguous → unselected**. A core mutation that finds *absent*
raises; a tool read that finds *plural* returns `:ambiguous-frame`; a tool surface
with no pinned target returns `:rf.tool/no-frame-selected`. Same stamp, one ladder,
different layer.

### `:rf/default` is an ordinary id

`:rf/default` carries **no framework privilege**. It is not created by `init!`, not
the React-context default, not a lookup tier, and not a request the runtime infers
from a missing `:rf.frame/id`. It remains a perfectly legal frame id that a small
app, example, or test may **explicitly** register and select:

```clojure
(rf/reg-frame :rf/default {:doc "The app frame for this program."})

(rf/with-frame :rf/default
  (rf/dispatch [:app/boot]))
```

A migration may choose `:rf/default` as its explicit app-frame id; the runtime will
not infer it.

### What this revokes — and what it does not

This contract **refines** the earlier goal "frame plurality is invisible to
single-frame apps" rather than wholesale revoking it. A single-frame app under this
contract still has exactly **one** root `frame-provider` / `with-frame`; *inside that
scope* every call stays ambient and ergonomic — no `{:frame …}` typing, no ceremony.
What dies is not in-scope invisibility but **rootless** invisibility: bare calls with
no established scope at all — exactly the async-callback, tool, test-fixture, and SSR
cases this EP rightly calls dangerous. The values ranking that makes this coherent is
recorded once: **explicit, carried frame identity outranks v1 call-shape fidelity**
(per [EP-0002 §Resolved Decisions R7](../docs/EP/EP-0002-frame-target-resolution.md#resolved-decisions)).

### Resolver surface (contract)

The central frame readers separate **reading** absence from **requiring** a frame.
Low-level readers may return `nil` so detection, frame pickers, and tooling can model
"no context" without throwing; public frame-scoped operations call the *require*
helper and fail outside context — so the nil-returning reader never becomes a
second, softer fallback.

```clojure
(frame/current-frame)
;; the lexical/dynamic scope frame, or nil — a reader, does not repair absence

(frame/resolve-current-frame)
;; the dynamic-or-adapter-context scope frame, or nil — a reader, does not repair absence

(frame/require-current-frame! operation payload)
;; the frame stamp, or raises/emits :rf.error/no-frame-context
```

Public frame-scoped operations that resolve ambiently — `rf/dispatch`,
`rf/subscribe`, `rf/current-frame-id`, no-arg `rf/capture-frame`, no-arg
`rf/frame-bound-fn*`, and the context-defaulting read/clear helpers — call the
*require* helper and fail outside context. The no-arg *hold*-capture forms
(`capture-frame`, `frame-bound-fn*`) capture **only** when a real scope exists at
capture time; capturing outside any scope is `:rf.error/no-frame-context`, never a
captured default. The full migration of each call site — router envelope
construction, the subscription/read surfaces, the React-context default, the
framework-fx defaults, SSR, trace/elision projection, and the tool layer — is the
EP-0002 implementation chain (see [EP-0002 §Bead Structure](../docs/EP/EP-0002-frame-target-resolution.md#bead-structure)).

## What lives in a frame

```clojure
{:id           :todo                    ;; the keyword identifier
 :frame-state  <atom>                   ;; this frame's ONE physical durable container —
                                        ;;   holds both partitions: {:rf.db/app <app-db>
                                        ;;   :rf.db/runtime <runtime-db>}. app-db and
                                        ;;   runtime-db are PROJECTION REACTIONS over it
                                        ;;   (per §One physical container, two projection reactions)
 :router       {...}                    ;; this frame's event queue/scheduler state
 :sub-cache    {...}                    ;; this frame's signal-graph cache
 :epoch-history [...]                   ;; this frame's per-cascade :rf/epoch-record ring (per Tool-Pair §Time-travel)
 :resolved-image-generation <gen>       ;; the sealed registration generation this frame runs against
                                        ;;   (EP-0024) — a slot on the unified frame value, not a
                                        ;;   second registry; per [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md)
 :trace-ring   {...}                    ;; this frame's per-frame trace ring — cascade-keyed,
                                        ;;   sized by :rf.trace/cascades-retained (default 50),
                                        ;;   per Spec 009 §Per-frame trace rings
 :lifecycle    {:created-at <ts>
                :destroyed? false
                :listeners  [...]}
 :config       {...}}                   ;; whatever was passed to `reg-frame` / `make-frame`
```

The map above is a **single unified frame value**: one live value owns the id, both
durable partitions, the runtime subsystems, the queue/drain state, the caches, the
lifecycle hooks, and its **resolved image generation** (EP-0024). It is found by frame
id in **one** live frame registry; there is no separate backing-record registry that
the runtime has to keep coherent. The implementation MAY split storage internally for
layering or performance, but the public and conceptual owner is one value reached by
one frame id (per [EP-0024 §One live frame registry](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)).

The frame's durable state is **two partitions** (per §The two-partition frame contract below): user **app-db** (`:db`) and framework **runtime-db** (`:rf.db/runtime`). App-db holds nothing but application data. The framework-owned runtime state — machine snapshots, route slice, elision declarations, SSR hydration metadata — lives in **runtime-db**, addressed by the `:rf.runtime/*` children (per [Conventions.md §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys)). The runtime-managed slots:

- `[:rf.runtime/machines :snapshots]` — `{<machine-id> <:rf/machine-snapshot>}` for every active machine in this frame (per [005-StateMachines.md §Where snapshots live](005-StateMachines.md#where-snapshots-live)).
- `[:rf.runtime/machines :system-ids]` — the per-frame **reverse-index** for `:system-id` named addressing: `{<system-id> <gensym'd-machine-id>}`. Allocated lazily (only present when a spawn binds a name); cleared on destroy. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and.
- `[:rf.runtime/routing :current]` — the route slice for url-bound frames (per [012-Routing.md](012-Routing.md)).
- `[:rf.runtime/routing :pending-navigation]` — the pending-navigation slot, populated when a `:can-leave` guard rejects a navigation; cleared by `:rf.route/continue` or `:rf.route/cancel`. Allocated lazily. Per [012 §Navigation blocking — pending-nav protocol](012-Routing.md#navigation-blocking--pending-nav-protocol).

The reserved runtime-db set is **fixed-and-additive** per [Conventions.md §Reserved runtime-db keys](Conventions.md#reserved-runtime-db-keys): names already in the table cannot be repurposed, and new children are added only by Spec change.

Three observations:

1. **Handlers are not in the frame.** The handler registrar is **process-global**, not per-frame — every frame in the process resolves against the one registrar (per [§Frame addressing](#frames-reference-realms) below). Frames isolate *state*, not *behaviour*: two frames running the same app share every event / sub / fx / cofx registration and differ only in their app-db and runtime-db.
2. **The signal graph is per-frame.** Two frames running the same `:total` subscription compute against their own app-db projections, cache against their own sub-caches; they are independent.
3. **A live frame is one unified runtime value.** User code holds and routes by **frame id** (a keyword); the framework holds **one** live frame value per id, in **one** registry (EP-0024). That value is a mutable lifecycle token — it owns the resolved image generation and the teardown bookkeeping — but its representation is hidden behind a single id accessor and it is not the app-facing operation target. There is no image-loaded frame *object* paired with a separate backing frame *record*: a live frame is the one value (per [EP-0024 §One live frame registry](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)).

<a id="frames-reference-realms"></a>
### Frame addressing — the frame id is the whole public address

> **Public model: `image → frame → event stream` ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md)).** A frame is addressed by its **id alone** — there is no second routing coordinate. There is no `(realm, frame)` two-part address and no multi-realm installation substrate; there is no realm constructor / install / query facade. The frame record keys by its bare frame-id, and the dispatch envelope carries `:rf.frame/id` only.

**Public frame ids are unique in the one process-local frame-id space** ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Id Spaces) — the frame id is the whole public address. A migrating codebase whose frames shared an id across containers gives its live frames distinct public ids (`rf/assert-process-local-frame-id!` surfaces the collision — see [EP-0023 §Backwards Compatibility](../docs/EP/EP-0023-image-loaded-frames.md)).

**Resolution is process-global.** When an event is dispatched to (or a subscription resolved against) a frame, the runtime resolves the **event handler, every coeffect supplier, every effect, AND every subscription** from the **one process-global registrar** — coherently, all from the same table. A frame supplies the *state* (which app-db / runtime-db the resolved handlers run against) and the *resolved image generation* it was assembled with; it does not supply a separate registrar. There is no per-frame handler routing: two frames running the same app resolve identical handlers and differ only in their state.

What a frame **is** carried for is its [resolved image generation](Conventions.md#frame-vocabulary--one-name-per-frame-fact) — the registration set the frame was assembled against. The frame target follows the carried invariant **verbatim**: a frame is carried (an explicit `:frame` dispatch argument, the carried frame value, or inherited by a child dispatch from its parent's envelope) or scoped by an explicitly established frame scope — **never resolved from a dynamic binding** as an ambient default (see [§Frame target resolution](#frame-target-resolution--the-carried-invariant)). The frame stamp is `:rf.frame/id`, carried on the dispatch envelope — see [§The frame stamp on the envelope](#routing-the-dispatch-envelope).

### The two-partition frame contract

A frame owns **two durable partitions**, committed coherently by one **pipeline run** (per [Conventions §Event-pipeline vocabulary](Conventions.md#event-pipeline-vocabulary--the-terms-one-event-traverses) — both partitions cross the single **commit** seam together):

| Partition | Owner | Event-context key | What it holds |
|---|---|---|---|
| **app-db** | the application | `:db` (coeffect/effect) | user application data — and nothing else |
| **runtime-db** | the framework | `:rf.db/runtime` (coeffect/effect) | machine / routing / elision / SSR subsystem state (the `:rf.runtime/*` children) |

A **frame-state** value is the coherent projection of both:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

This split removes the v1→early-v2 ownership footgun where framework runtime state sat under a `:rf/runtime` root *inside* user app-db, so an ordinary fresh `:db` return could silently delete machine, routing, elision, or SSR state. Under the partition, an ordinary `:db` effect replaces **only** app-db — runtime-db is a partition the handler never holds. The footgun is structurally gone, not merely warned against.

The split is also an **AI-legibility win**: once app-db holds nothing but app data, `reg-app-schema` describes a *pure* application contract an agent can read without framework noise — the spec-is-the-artefact payoff (per [Principles.md](Principles.md)).

#### One physical container, two projection reactions

The frame holds **one physical frame-state container** (the `:frame-state` atom above). App-db and runtime-db are **projection reactions** layered over it:

```clojure
frame-state  (one signal: {:rf.db/app <app-db> :rf.db/runtime <runtime-db>})
   ├── app-db     = (reaction (:rf.db/app @frame-state))     ; layer-1 input for app subs
   └── runtime-db = (reaction (:rf.db/runtime @frame-state)) ; layer-1 input for framework subs
```

This is **pattern contract**, not just one acceptable representation. Ports MAY differ only if they preserve the projection-equality semantics below; the reference impl commits to the single container. The full substrate realisation **and the normative projection-equality pattern-contract** are owned by [006 §Frame-state container and partition projections](006-ReactiveSubstrate.md#frame-state-container-and-partition-projections); the prose here states the split at the frame contract.

The model buys partition-aware sub-cache invalidation **for free** from existing reaction-deref equality — no explicit dirty flags unless an adapter needs them:

- A **runtime-only commit** bumps `frame-state`; the `app-db` projection reaction recomputes, finds `(:rf.db/app …)` `identical?` to the prior value, and **does not propagate** — app subs neither re-render nor recompute.
- An **app-only commit** is symmetric — the `runtime-db` projection does not propagate, so framework route/machine subs are untouched, and app authors never carry runtime paths in their schemas or sub code.
- A commit that touches **both** partitions propagates to both projections.

### The event handler contract

There is **one public event-registration form** — `reg-event` ([EP-0018](../docs/EP/EP-0018-one-event-registration.md); the registrar contract is owned by [001 §The one event form](001-Registration.md#the-one-event-form--coeffects-in-effects-out)). Every event handler is **coeffects in, effects out**:

```clojure
(rf/reg-event :id ?metadata
  (fn [coeffects event-vec] effects-map-or-nil))
```

- The handler is **two-arg** (D4): the coeffects map and the event vector. Handlers that do not need the event vector use `_`. `(:event coeffects)` is the same value as the second argument.
- It returns the **closed effects map** (`{:db … :fx [...] …}`, top-level keys `#{:db :fx :rf.db/runtime}`, `:rf.db/runtime` framework-authority only) or `nil`. The db write is an explicit `:db` effect like any other; there is no db-only return shape.
- The three-form family (`reg-event-db` / `reg-event-fx` / `reg-event-ctx`) is off the public surface: `reg-event-db` / `reg-event-fx` are removed (`reg-event` replaces both); `reg-event-ctx` is a framework-internal `context -> context` primitive — application full-context work is expressed with interceptors. Retired public names raise their naming hard errors (per [001 §The retired event-registration names](001-Registration.md#the-retired-event-registration-names)).

The effects-map and coeffects model are as elsewhere in this spec; the rest of this section details how the coeffects map is assembled and threaded.

#### Event context threads both partitions

A standard event context threads both partitions plus the frame id and the handler's declared recordable coeffects (the canonical shape is pinned in [Spec-Schemas §`:rf/handler-context`](Spec-Schemas.md#rfhandler-context-the-map-handlers-receive--event-context-and-fx-handler-ctx) — this is the event-context half; the fx-handler ctx is a separate, smaller map):

```clojure
{:coeffects
 {:db              {:todo/items []}             ;; app-db (the inherited bare key)
  :event           [:todo/add "Write spec"]
  :rf.db/runtime   {:rf.runtime/machines {}      ;; runtime-db (reserved)
                    :rf.runtime/routing  {}}
  :rf.frame/id     :todo                         ;; the running frame's id
  :rf.cofx         {:rf/time-ms 1781078400123}   ;; the envelope's recordable-coeffect map (framework base key)
  :rf/time-ms      1781078400123}                ;; a declared recordable coeffect, delivered flat
 :effects {}}
```

The runtime-db coeffect is injected **by reference** (the persistent runtime-db value, no copy); an app-only commit performs no runtime re-commit, so a pure app event pays nothing for a partition it never touches. `:rf.frame/id` is the *runtime-context* spelling of the frame id; it is distinct from the public `:frame` dispatch/subscribe opt, which is unchanged (the `:frame` → `:rf.frame/id` *context-key* concern is owned by the frame-target-resolution work, not this contract).

The event context stages two distinct facts about recordable coeffects, and the distinction is load-bearing:

- **The framework always stages the envelope's `:rf.cofx` map** as a base context key (alongside `:db`, `:event`, `:rf.db/runtime`, `:rf.frame/id`) — the *canonical complete record* of every recordable fact on this causal token, regardless of what any handler declared. It is **a framework context key, reachable exactly how `:event` is reachable** — not a registered coeffect supplier (the [`:cofx/envelope-preserved`](conformance/README.md#fixtures) cross-port fixture pins this: a handler reading the whole `:rf.cofx` record is reading a framework context key, *not* a declarable coeffect id). Generic code (transition helpers, interceptors, the framework-internal `context -> context` primitive) reads the whole record there. This is the framework's own access path: the framework's durable writers (resource freshness, work-ledger rows, mutation instances, epoch records) read `:rf/time-ms` from this map, not by declaring it.
- **The recordable coeffects a handler declares** (here `:rf/time-ms`) are *additionally* delivered **flat** into the coeffects map under their own ids — never grouped in a sub-map. This *user-declared spread* is **declared-only**: a handler receives `:db`, `:event` (the fold's own arguments), the framework context keys above (including the `:rf.cofx` map), and *exactly* the leaves it named in `:rf.cofx/requires` ([§Recordable coeffects](#recordable-coeffects)). A leaf on the token but undeclared is **not** delivered as a flat key.

So a declared `:rf/time-ms` appears twice, at two layers: once inside the always-staged `:rf.cofx` envelope map (the framework record) and once as a flat top-level key (the handler's declared delivery). One home per layer.

> **"Declared-only delivery" governs the *flat spread*, not the staged record.** It is a frequently-misread distinction: the `:rf.cofx` map is *always* in the coeffects, exactly how `:event` is — "declared-only" never excludes it. **Ordinary application handlers SHOULD read declared facts flat (`(:rf/time-ms coeffects)`), not reach into the whole record (`(:rf/time-ms (:rf.cofx coeffects))`).** Reaching into the record is *allowed* — it is a plain framework context key, and the values it carries are replay-safe by construction (replay re-presents the same token, same values, so folding one into durable state is deterministic) — but it is **discouraged for app code** because it **bypasses declaration hygiene**: a fact consumed off the record never appears in `:rf.cofx/requires`, so `handler-meta` and tooling cannot see the dependency, and the handler is not held to the recordable-coeffect rule for that fact. This is **developer discipline, not a runtime-enforced invariant** — the framework leaves the safer recorded-token read open (it lints only the genuinely dangerous *ambient* host read — §Recordable coeffects). Reading the whole record is for **generic / framework code** that legitimately needs the complete causal record; ordinary handlers declare what they consume.

#### An ordinary `:db` return replaces only app-db

A handler's `:db` effect targets app-db, never runtime-db. If the frame currently holds:

```clojure
{:rf.db/app     {:session/status :authenticated :user/id 42}
 :rf.db/runtime {:rf.runtime/machines {…} :rf.runtime/routing {…}}}
```

then `(rf/reg-event :session/reset (fn [_ _] {:db {:session/status :anonymous}}))` commits:

```clojure
{:rf.db/app     {:session/status :anonymous}      ;; only app-db replaced
 :rf.db/runtime {:rf.runtime/machines {…} :rf.runtime/routing {…}}}  ;; runtime-db untouched
```

No preservation code is needed; the handler cannot touch runtime-db through `:db`.

#### The `:db` commit / no-op return family

The closed effects map is the only return; there is no db-only return shape. The app-db commit semantics of every return follow one table — stated here for the effect-map contract, enforced at the commit step (§Run-to-completion below):

| Handler return | App-db effect |
|---|---|
| `nil` | no-op (nothing committed) |
| `{}` | no-op |
| `{:db <new>}` (new ≠ current) | commit `<new>` as app-db |
| `{:db db}` (the unchanged db) | **no-op** — `identical?` short-circuit, no container write |
| `{:db nil}` | **coerced to `{:db {}}`** — app-db is always a map, never nil (+ a dev-mode `:rf.warning/db-nil-coerced` diagnostic; `{:db {}}` is the clean explicit clear) |

Two rows are correctness contracts apps may rely on:

- **The `identical?` commit no-op.** When a `:db` effect carries the *same object* the frame already holds (`identical?`, not merely `=`), the commit step skips the physical container write entirely — no `:rf.event/db-changed` signal, and the projection reactions do not propagate. This is what keeps the common `{:db (if cond (assoc db …) db)}` shape cheap: the `else` arm returns the same object and costs nothing. Deeper change-detection stays value equality (`=`) — a different-object-but-equal-value commit still writes, and downstream `=`-memoisation collapses it; the cheap fast-path is reference identity. This same no-op is the optimization the standard `path` interceptor's Rule 4 preserves (§The standard path interceptor) and the partition-projection equality model (§One physical container, two projection reactions) buys for free.
- **The `{:db nil}` → `{:db {}}` coercion.** App-db is always a map, never nil. A `:db nil` effect is coerced to `{}` at the `:db` effect → app-db commit boundary so the partition layer never sees a nil app-db. The coercion is usually masking a bug (a handler accidentally computed `nil`), so it emits the dev-mode diagnostic above; to clear app-db, return the explicit `{:db {}}`.

These commit semantics apply to **every** event — they are independent of the registration-surface collapse (per [EP-0018 §Commit / no-op family](../docs/EP/EP-0018-one-event-registration.md)).

#### Write authority is by convention

`:rf.db/runtime` is reserved **by convention, NOT as a security boundary**. Because it rides in `:coeffects`, app code can technically read it, and the closed top-level effect map is widened from `#{:db :fx}` to include the reserved `:rf.db/runtime` state effect, so app code can technically *emit* it too. The rule — documented and surfaced through dev diagnostics, not enforced as a capability — is:

> `:rf.db/runtime` is for framework and runtime-extension code. Ordinary application code does not write it directly; it reaches subsystem state through public framework subscriptions and effects.

Framework subsystems (machines, routing, elision, SSR) write runtime-db through `:rf.db/runtime` effects, privileged runtime APIs, or internal interceptors. Both **whole-value replacement** AND **operation-style writes** are supported: normal subsystem writes prefer operations/helpers; restore / hydration / reset may replace the whole runtime-db (or the whole frame-state). A runtime write and an app-db write in the same cascade install as **one atomic frame-state transition** (per [§Run-to-completion](#run-to-completion-dispatch-drain-semantics) and [006](006-ReactiveSubstrate.md)).

##### Minting framework-write authority

A subsystem whose runtime-db writes ride through an **event handler** (one returning a `:rf.db/runtime` effect — e.g. routing's `:rf.route/navigate`, SSR's `:rf/hydrate`, a machine's snapshot-commit handler) declares its authority via a single **general** registration-meta key: `:rf/framework-authority? true` (a reserved registration-meta key, per [Conventions §Reserved registration metadata](Conventions.md#reserved-registration-metadata-framework-owned)). The registration site stamps it; the runtime reads it when assembling the event context and uses it to decide whether a returned `:rf.db/runtime` effect is in-bounds or should fire the `:rf.warning/app-handler-runtime-effect` dev diagnostic. It is **not** a capability gate — the effect applies either way; the flag governs only the diagnostic.

Which registrars mint authority:

- **routing** — the routing façade stamps `:rf/framework-authority? true` on every `reg-event` it registers (`:rf.route/navigate`, `:rf.route/transitioned` / `:rf.route/handle-url-change`, `:rf/url-requested` / `:rf.route/continue` / `:rf.route/cancel`, `:rf.route.internal/settle-transition`, …) — every one reads and returns the reserved route slice.
- **SSR** — the SSR façade stamps it on `:rf/hydrate`, which installs the hydration metadata into the runtime-db partition.
- **machines** — machine handlers carry the framework-owned `:rf/machine? true` stamp (minted by the machine registrar). The runtime folds that stamp into the authority check, so a machine implies framework-write authority **without** a separate `:rf/framework-authority?` key — its existing contract is unchanged.
- **elision** and **SSR's non-event writes** — these subsystems write runtime-db through **privileged frame-state helpers** (`swap-runtime-db!` / `replace-frame-state!`), not through event handlers returning a `:rf.db/runtime` effect, so they never reach the event-handler diagnostic and mint no event-handler authority. (Elision's per-frame declaration registry and any full-frame install / restore path are in this category.)

#### Commit-plane data-classification effects (EP-0025)

The closed top-level effect map carries **four further commit-plane effects** beyond `:db` / `:rf.db/runtime` / `:fx` — the EP-0025 data-classification effects, reserved in [Conventions §Reserved commit-plane classification effects](Conventions.md#reserved-commit-plane-classification-effects):

```clojure
:sensitive       [[path] …]   ; classify a path sensitive (redact at egress)
:large           [[path] …]   ; classify a path large    (size marker at egress)
:clear-sensitive [[path] …]   ; un-classify sensitive
:clear-large     [[path] …]   ; un-classify large
```

They are **commit-plane** effects, applied **WITH the `:db` write** at the commit step (a frame-state transform into the per-frame elision declaration registry, `[:rf.runtime/elision …]` in runtime-db) — **not** routed through the `:fx` do-fx plane, and **not** [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids) (which catalogues `do-fx`-dispatched fx, a different plane). Because the registry lives in runtime-db, a classification effect commits as a runtime-db partition write folded into the **same atomic frame-state transition** as `:db` (per §The two-partition frame contract). Consequences:

- **Same-event ordering.** A path classified in an event is redacted from its *first* egress — the classification lands at the same commit boundary the value does. (A classification made earlier — at init, or any time before the value lands — trivially covers it.)
- **Value-independent.** Classify a path *before* any value exists there; the classification redacts whatever later occupies the path. A classification over an absent / differently-shaped value is a harmless no-op.
- **Read only at egress.** The application — handlers, subs, views — always sees real values while events run; redaction happens only at the mediated-egress projection. The two axes (`:sensitive` / `:large`) are **independent** — clearing one never touches the other; clearing removes only the named paths.
- **Fail-loud, pre-commit.** A malformed payload (a non-vector value, or a non-`:rf/path` entry) is rejected at the router's **FINAL-effects boundary** (in `commit-and-flow!`, immediately before the commit) with `:rf.error/classification-effect-shape` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)). The shape is checked by a **pure, non-throwing** validator (`re-frame.elision/classification-effect-defect`); the router emits the error **in-band** (not a throw — a throw here would escape the drain) and aborts the event with **no `:db` commit** and no classification install (no partial commit), mirroring the in-band legacy-runtime-root rejection at the same boundary. A *forgotten* classification is fail-open (the value ships raw); a malformed one is fail-loud.

The same `:sensitive` / `:large` vocabulary is reserved at the registration layer (transient event / effect / coeffect payloads and subsystem instance declarations) per [Spec-Schemas](Spec-Schemas.md); these four handler effects are the **durable app-db** lowering of that vocabulary. The full classification model — egress projection, subsystem declarations, failure posture — lives in [015 §Data Classification](015-Data-Classification.md).

Host handles remain **outside** frame-state. Timers, AbortControllers, listeners, promise handles, and substrate objects are teardown resources, not serializable runtime-db values. Runtime-db records enough durable facts to reconstitute or clean up those handles; it does not store the handles themselves (per §Durable vs transient below).

#### Subscriptions read the partition they belong to

- Ordinary layer-1 app subscriptions read **app-db** (`(rf/reg-sub :todo/items (fn [db _] (:todo/items db)))` — `db` is the app-db projection).
- Framework subscriptions read **runtime-db** through framework helpers: `[:rf/machine :door/main]`, `[:rf.route/id]`, `[:rf.route/params]`. App code uses these public subs; it does not reach into runtime-db paths directly.
- A sub composed from both partitions reruns when either input changes; the projection-equality model (above) makes runtime-only changes visible to framework subs and invisible to app subs, and vice versa.

#### Frame-state value accessors and mutators

| Surface | Returns / does |
|---|---|
| `(rf/app-db-value frame-id)` | the app-db partition value (a plain map) |
| `(rf/runtime-db-value frame-id)` | the runtime-db partition value |
| `(rf/frame-state-value frame-id)` | `{:rf.db/app … :rf.db/runtime …}` |
| `(rf/replace-app-db! frame-id app-db)` | replace **only** the app-db partition |
| `(rf/replace-runtime-db! frame-id runtime-db)` | replace **only** the runtime-db partition |
| `(rf/replace-frame-state! frame-id frame-state)` | replace **both** partitions atomically (full-frame install) |

App-facing APIs return app-db by default; tools and privileged runtime code request runtime-db or frame-state explicitly. Full-frame operations (epoch restore, time travel, SSR hydration, frame reset, test-fixture install) use the full-frame surfaces — never ordinary `:db` effects. The Tool-Pair db-injection surfaces are `replace-app-db!` / `reset-app-db!` for app-db-only injection, and the distinct `replace-frame-state!` for full-frame tools; a tool API named as a db replacement does not silently replace runtime-db.

**The mutators above are a privileged state-surgery lane, distinct from the canonical app data-flow lane.** The canonical lane — the only one application code uses — moves a frame from one value to the next through the event pipeline: a handler returns a `:db` (and optionally `:rf.db/runtime`) effect, the commit step installs it as one atomic frame-state transition, the trace and epoch ledgers record the cascade, and dev-mode schema validation runs. The surgery lane (`replace-app-db!`, `replace-runtime-db!`, `replace-frame-state!`, `reset-app-db!`, and the epoch-restore operation `restore-epoch!` it composes with) overwrites partition values directly: no handler runs, no effects fire, no cascade is emitted, and no validation gate intervenes. The whole point of these surfaces is to set up or rewind frame state *from outside the running app* — test fixtures, tooling (Xray time-travel, the pair MCP — see [Tool-Pair](Tool-Pair.md)), and privileged framework subsystems (restore, SSR hydration, frame reset). They are NOT an application mutation API: app code that wants to change state dispatches an event, so that every change keeps a recorded cause. Documentation and guide prose MUST present these surfaces as privileged tooling/test operations and keep the canonical app path centred on handlers, effects, subscriptions, and snapshots; an example using a `replace*` / `restore` surface is labelled for tests, tooling, or debug restore, never as ordinary app mutation.

#### Durable vs transient

A fact lives in **runtime-db iff it must survive epoch-restore and SSR-hydration** — i.e. it is a serializable durable fact. Everything else is **transient**: frame-scoped, torn down on destroy, never serialized.

- **Durable (runtime-db):** machine snapshots + system-ids + spawn registry + spawn-counter; the route slice + pending-nav; elision declarations; SSR hydration metadata (e.g. a server render hash).
- **Transient (NOT runtime-db):** host handles (timers, AbortControllers, listeners, promises); in-flight HTTP registries; the saved scroll-position cache + nav-token / pending-nav counters (host-derived, meaningless after a restore — held outside the frame value so an epoch restore cannot rewind + recycle a token; cleared by `:routing/on-frame-destroyed!`); SSR request/response accumulators, head snapshots, streaming continuation registries, pending-error buffers; trace rings; epoch capture buffers; sub-cache entries; flow registries + `last-inputs` dirty-check caches.

Transient state is frame-scoped and torn down on `destroy-frame!` (per [§Destroy](#destroy)) but full-frame serialization, hydration, restore, and time-travel MUST NOT pull it in.

**The trace surface is per-frame too.** Each frame owns its own cascade-keyed trace ring alongside its `epoch-history`. Trace events that ride inside an in-flight cascade route to the frame whose drain loop, reactive recompute, or view render is running — they never cross frames. The ring unit is the cascade (one `:rf.trace/dispatch-id` = one slot), retained at the per-frame `:rf.trace/cascades-retained` knob (default 50). Cross-frame consumers (pair tools, multi-frame stories) merge by `:dispatch-id` across rings; frameless emits (registration, REPL evals, lifecycle outside any cascade) bypass the rings entirely and stream live to listeners only. See [Spec 009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-cascade-keyed-dev-only) for the full retention contract.

## Frame lifecycle

### `reg-frame` — atomic create-and-register and the canonical metadata grammar

```clojure
(rf/reg-frame :todo {:initial-events [[:todo/initialise]]})
;; creates a frame record (app-db starts {}), registers it under :todo,
;; dispatch-syncs each :initial-events step into it in order, returns the keyword.
```

Atomic create-and-register. There is no way to obtain an unregistered frame; this matches the rest of re-frame's `reg-*` family and avoids orphan-frame states. The return value (the registered frame keyword) follows the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

This section is the **canonical grammar** for `reg-frame` metadata. Subsequent sections — [§Re-registration — surgical update](#re-registration--surgical-update), [§Frame presets](#frame-presets--capability-bundles-for-common-configurations), [§Per-instance frames](#per-instance-frames--make-frame-the-ep-0023-object-constructor) — refer to the keys defined here; they do not re-define them.

`reg-frame` accepts a metadata map mirroring other registrations:

```clojure
(rf/reg-frame :todo
  {:doc          "..."                          ;; like all reg-*
   :initial-events [[:todo/initialise]]         ;; ordered setup events dispatched synchronously after creation
   :on-destroy   [:todo/cleanup]                ;; single event dispatched before teardown
   :fx-overrides {:my-app/http http-stub-fn}    ;; per-frame fx replacements
   :interceptors [:my-app/recorder              ;; interceptor REFS prepended to every event in this frame
                  :my-app/validator]            ;;   (refs, never inline interceptor values — EP-0022)
   :drain-depth  100                            ;; depth limit for run-to-completion drain
   :platform     :server                        ;; active platform for this frame per [011-SSR.md](011-SSR.md); typically preset-supplied
   :rf.trace/cascades-retained 200              ;; per-frame trace-ring retention: one slot per EVENT/pipeline run (default 50); per [009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-cascade-keyed-dev-only); ruled rename to :rf.trace/events-retained (landed atomically with the impl downstream)
   :observability {:errors [{:sink :my-app.sinks/sentry}]} ;; frame-owned sink policy per [015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy)
   :ns :line :file})                            ;; auto-supplied
```

The full set of metadata keys — `:doc`, `:initial-events`, `:on-destroy`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:drain-depth`, `:platform`, `:rf.trace/cascades-retained`, the frame-owned policy key `:observability`, plus the auto-supplied `:ns`/`:line`/`:file` — is the canonical surface; the `:rf/frame-meta` schema in [Spec-Schemas](Spec-Schemas.md#rfframe-meta) is the normative reference. (The retention knob `:rf.trace/cascades-retained` is **ruled to rename** to `:rf.trace/events-retained` per [Conventions §The `event-*` noun family](Conventions.md#the-event--noun-family-ruled-key-renames); the key spelling still reads `:rf.trace/cascades-retained` here because the reference implementation still emits it — the atomic key rename, spec plus impl in one PR, is a downstream bead.)

**Frame-owned policy (`:observability`).** A frame owns its production observability sinks (EP-0015 §9; the full model is normative in [015 §Data Classification](015-Data-Classification.md)). The key, summarised here for the grammar:

- `:observability {:handled-events [<sink-entry>…] :errors [<sink-entry>…]}` — production observation sink policy; each `<sink-entry>` is a map naming a user/library-owned `:sink` keyword id with an optional `:rf.egress/profile` and `:opts` map.

**The frame `:sensitive` / `:large` classification keys are REMOVED.** Durable app-db data classification is not a `reg-frame` annotation (a frame is not app-db's definition site); it is the four commit-plane classification effects (`:sensitive` / `:large` / `:clear-sensitive` / `:clear-large`) a handler returns alongside its `:db` write (normative in [015 §Durable app-db — the four commit-plane effects](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects)). HTTP carrier classification (the `:sensitive {:http …}` block) is not a frame annotation either — it lives on the `:rf.http/managed` `reg-fx` registration's `:carriers` block (the transient-payload case, normative in [014-HTTPRequests §HTTP carriers](014-HTTPRequests.md#http-carriers-ep-0025)). A `reg-frame` carrying `:sensitive` (or the top-level `:large` frame key) **fails loudly at frame registration** (`:rf.error/bad-frame-classification`). **Unknown classification keys and malformed observability entries also fail loudly** (`:rf.error/bad-frame-classification`, the canonical thrown-error shape) — before any state mutates and before any setup event runs, so a bad declaration leaves no half-registered frame. `:platform` is framework-supplied via presets in the v1 closed set (`:ssr-server` sets it); user code may set it directly for non-preset configurations. `:rf.trace/cascades-retained` defaults to 50 when omitted; per-frame override is useful for inspector frames (e.g. `:rf/xray` may want 200 for deep diagnostic walks) and transient story-variant frames (which may want fewer). (There is no `:on-error` recovery-policy slot; recovery is framework-owned. Error observability is the always-on [`register-listener!` (`:errors` stream)](009-Instrumentation.md#what-is-available-in-production) surface.)

**Frames always start with `app-db = {}`.** There is no `:db` config key — seeding app-db is itself an event, `[:rf/set-db {…}]`, dispatched as the first `:initial-events` step. This keeps "events are the unit of state change" as a single, consistent mechanism: the initial state is built by the same dispatch pipeline that handles all subsequent state changes (per [EP-0027](../docs/EP/EP-0027-frame-initial-events.md)).

`:initial-events` is an **ordered vector of setup steps** dispatched synchronously into the freshly-created frame, in order, each drained to fixed point per run-to-completion before the next. By the time `reg-frame` returns, the setup cascade has settled and `app-db` is in whatever state it produced. Omitting `:initial-events` and supplying `[]` both mean "no setup events." A step is a **bare event vector** (`[:todo/initialise]`), or a **map** when it needs dispatch opts (`{:event [:todo/add "milk"] :opts {:rf.cofx {:rf/time-ms …}}}` — the ordinary `dispatch-sync` opts, with `:frame` forced to the constructed frame and forbidden in `:opts`). A **bare event vector is not a valid top-level value**: `{:initial-events [[:todo/init]]}` is one one-step vector; `{:initial-events [:todo/init]}` is rejected (the diagnostic names the fix — wrap it as `[[:todo/init]]`).

If the frame's initialisation needs to fire multiple events, list them as separate steps (or have one setup event's handler emit `:fx`):

```clojure
;; declarative — the readable form
{:initial-events [[:rf/set-db {}]
                  [:todo/restore-session]
                  [:todo/load-preferences]]}

;; or one event that fans out via :fx
(rf/reg-event :todo/initialise
  (fn [{:keys [db]} _]
    {:db (assoc db :items [] :status :idle)
     :fx [[:dispatch [:todo/restore-session]]
          [:dispatch [:todo/load-preferences]]]}))
```

`:on-destroy` remains a **single** event dispatched before teardown.

The framework stamps each setup dispatch with the frame's id automatically — the user doesn't write `dispatch` or specify `:frame`. If a setup handler needs the frame-id at runtime, it reads `(:rf.frame/id m)` from its context — a setup handler is an ordinary event handler, so it reads the event-context spelling of the frame stamp, not a bare `:frame` coeffect (per [§One carrier, one name](#one-carrier-one-name--the-frame-stamp)). Each setup step carries construction provenance (`:source :frame-init` plus its step index) so the trace and tools can tell frame-init events apart from ordinary runtime events.

[Spec 007 — Stories](007-Stories.md) keeps its own richer setup grammar (loaders, `:rf.story/*`) and neither desugars to nor couples to `:initial-events` (per [EP-0027 §Out of scope](../docs/EP/EP-0027-frame-initial-events.md)).

**Handler-time frame construction is an error — handlers mutate app-db; views and top-level materialize frames.** Frame creation is a **view / top-level concern**, not a handler one. Constructing a frame (`reg-frame` / `make-frame`) **inside an event handler is not supported** and fails loudly with `:rf.error/frame-construction-in-handler`. This is a foundational Spec 002 principle (EP-0027): a handler changes app-db; the view (or top-level boot / SSR-per-request) materializes frames *from* app-db state. There is no mid-cascade frame-creation path and no queued-creation-event allowance — a handler that wants a child frame to exist writes app-db, and the view tree creates the frame in response (e.g. via `frame-provider`). Construction therefore runs only at **top level** (tests, boot, SSR per request) or in the **view tree** (`frame-provider`).

### Destroy

```clojure
(rf/destroy-frame! :todo)     ;; by id …
(rf/destroy-frame! frame)     ;; … or by the frame value (its id is read off the value)
```

**One ownership path (EP-0024).** `destroy-frame!` removes the **one unified frame value** from the **one** live registry and runs per-subsystem teardown exactly once. Because a live frame is a single value (not an image-loaded object paired with a separate backing record), there is **no second public registry whose cleanup can succeed or fail independently** — teardown walks one structure. Teardown remains best-effort where individual cleanup hooks are host-transient, but the ownership path is one path (per [EP-0024 §Teardown](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md#teardown)).

- Drops the frame from the registry.
- Disposes the sub-cache (each cached reactive is torn down so nothing leaks listeners).
- Stops the router.
- Fires `:on-destroy` events before teardown if specified.
- **Releases every per-feature artefact's frame-scoped state.** `destroy-frame!` is the single normative teardown boundary every per-feature artefact (flows, machines, schemas, SSR side-channels, epoch history, …) MUST hang its frame-scoped cleanup off. Each artefact publishes a teardown hook the core invokes during destroy; an artefact that holds frame-scoped state without publishing such a hook leaks definitions and cached state on every `destroy-frame!`. Per-artefact contracts: flows tear down per [013 §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown); machines tear down per [005 §Cross-Spec Interactions §1](005-StateMachines.md); schemas, SSR, and epoch tear down per their respective specs.
- Subsequent `(dispatch [...] {:frame :todo})` / `(subscribe [...] {:frame :todo})` to a destroyed frame **recovers** — `dispatch` / `dispatch-sync` no-op (the event is not enqueued), `subscribe` returns `nil` — **and emits a production-survivable `:rf.error/frame-destroyed`** through the always-on error-emit listener (surface #4 per [009-Instrumentation §What IS available in production](009-Instrumentation.md#what-is-available-in-production)). The record carries `{:error :rf.error/frame-destroyed :frame :todo :event <attempted-vector> :event-id <head> …}`. Recovery (rather than throwing) is the framework default: the runtime cannot distinguish a benign teardown / hot-reload **race** from a real use-after-destroy bug, so it recovers (race-safe) while keeping the diagnostic observable on the production-watched stream — a real bug surfaces in your error monitor rather than going silent. Recovery here is the framework's typed default (no-op); there is no app-steering recovery policy.

**Teardown invocation order — normative.** `destroy-frame!` invokes the per-feature teardown hooks in the strict order below. The ordering is pattern contract — a conformant port MUST mirror it. Re-ordering breaks composition: machine `:exit` cascades emit `:fx` that runs through `do-fx`, so machines MUST tear down **before** the sub-cache disposes (a disposed sub-cache cannot service a sub a final `:exit` action reads); SSR / schemas / flows hold registry slots a re-registered frame must start clean, so they tear down **before** the `:rf.frame/destroyed` trace fires (tools listening for the trace see a fully-cleaned frame); registrar-dissoc happens **after** the trace so the trace itself carries the frame's still-resolvable metadata; epoch notification fires **last** so 10x / re-frame-pair listeners receive `:rf.epoch.cb/silenced-on-frame-destroy` against an already-vanished frame and silence their event buffers in one pass.

1. **Fire user `:on-destroy`** event (if any) — runs against the still-live frame. Throws are caught and converted to `:rf.error/on-destroy-handler-exception` traces (per the throw-semantics note below); teardown continues.
2. **Machines teardown** via `:machines/teardown-on-frame-destroy!` — walks active machines in reverse-creation order, runs each `:exit` cascade against a still-live container, applies the unified teardown projection (snapshot + `:rf/system-ids` + spawn-slot prune), unregisters the per-actor handlers, and emits `:rf.machine.lifecycle/destroyed` per actor with `:reason :parent-frame-destroyed`. Per [005 §Cross-Spec Interactions §1](005-StateMachines.md) + [Cross-Spec-Interactions §1](Cross-Spec-Interactions.md).
3. **Mark frame `:destroyed?`** — flips the lifecycle flag. Subsequent dispatch / subscribe against the frame now recovers (dispatch no-ops, subscribe returns nil) and emits a production-survivable `:rf.error/frame-destroyed` (see the destroy-contract bullet above).
4. **Sub-cache disposal** — every cached reactive's `dispose!` (or substrate-equivalent) fires; listeners detach.
5. **Auxiliary cleanup hooks**, in declaration order: SSR (`:ssr/on-frame-destroyed` — clears per-request side-channels; chains `:ssr.head/on-frame-destroyed`), machines `:after` timer-table (`:machines/on-frame-destroyed!`), routing (`:routing/on-frame-destroyed!` — clears the host-side scroll-position cache + nav-token / pending-nav counters; the durable route slice rides the frame value and needs no separate pass), schemas (`:schemas/on-frame-destroyed!` — drops every schema registered against the destroyed frame), flows (`:flows/teardown-on-frame-destroy!` — drops flow registry slots + `last-inputs` rows + dead `:flow` registrar entries), and — when the optional Resources artefact is loaded — resources (`:resources/on-frame-destroyed!` — cancels resource/mutation timers and clears the `[frame-id work-id]` host-handle side tables). Plus per-feature warn-cache resets (privacy-suppression, elision) which are observability-only and ordering-insensitive.
6. **Emit `:rf.frame/destroyed` trace** — the canonical observability signal that teardown is complete. Fires AFTER every cleanup hook so listeners see a fully-cleaned frame.
7. **Dissoc the frame** from the frames map.
8. **Unregister** from the registrar.
9. **Notify epoch listeners** — fires the `:rf.epoch.cb/silenced-on-frame-destroy` hook so tools (10x, re-frame-pair) silence their per-frame event buffers in one pass.

Hooks 2 / 5's per-artefact entries are **best-effort**: an artefact whose hook is not registered (e.g. a build that omits `re-frame.flows`) silently no-ops at that step; the rest of the recipe runs unchanged. The ordering between the registered hooks holds regardless of which subset is present.
- Tool-Pair surfaces against the destroyed frame route off their own contract (read returns empty / `nil`; mutate raises `:rf.error/no-such-handler` (kind `:frame`); listener silencing emits a one-shot trace) — see [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames).

<a id="two-destroy-hook-verbs"></a>
#### Two destroy-hook verbs — a naming rule

The per-artefact destroy hooks above carry **two distinct verb forms**, and the distinction is **semantic, not stylistic** — they name two different kinds of work, so an artefact may legitimately publish both (the machines artefact does):

- **`<feature>/on-frame-destroyed!`** — a **destroyed-frame cleanup callback**. It drops host-transient side-table entries keyed to the now-gone frame (timer registries, nav/scroll caches, validator caches, SSR side-channels). It is fired in step 5's auxiliary-cleanup pass, runs against an already-marked-`:destroyed?` frame, and is pure side-table bookkeeping.
- **`<feature>/teardown-on-frame-destroy!`** — an **artefact-owned teardown recipe** with lifecycle/registrar-consistency invariants. It runs an ordered teardown (a reverse-order machine `:exit` cascade against a still-live container; a re-point of `:flow` registrar slots to a surviving owner) that MUST execute before the frame is marked destroyed (step 2) so the cascade's effects resolve coherently. It is not a side-table sweep — it preserves a cross-frame registrar invariant.

So **machines publishes both**: `:machines/teardown-on-frame-destroy!` (step 2) runs the actor `:exit` cascade and unregisters per-actor handlers; `:machines/on-frame-destroyed!` (step 5) cancels the per-frame `:after` timer registry and epoch counters. The two names are distinct teardown kinds. Flows' `:flows/teardown-on-frame-destroy!` uses the recipe verb (its `:flow` registrar re-point is a cross-frame invariant, not a side-table sweep); schemas / routing / SSR / resources use the callback verb because their cleanup is side-table-only.

**`:on-destroy` handler throw semantics — trace-and-continue.** A throw from the user-supplied `:on-destroy` event handler (or any handler in its dispatch cascade) MUST NOT abort teardown. The runtime catches the throw, emits a `:rf.error/on-destroy-handler-exception` error trace (`:tags` `{:frame <id> :rf.event/v <on-destroy-event-vector> :exception <ex> :where :fire-on-destroy-event!}`, `:op-type :error`), and continues with every downstream teardown step — machine cascade, sub-cache disposal, cleanup hooks, `:rf.frame/destroyed` emission, registry dissoc, registrar unregister, epoch notification. A frame that began destruction MUST end fully destroyed; throw-propagation was never a "abort teardown" signal (a half-torn-down frame leaks reactions and registrar entries and is the worse failure mode by far). User code that needs to react to the exception consumes the error trace; user code that wants to *prevent* destruction must guard the caller of `destroy-frame!`, not throw from inside `:on-destroy`.

**Re-entrant `destroy-frame!` is a silent no-op.** If the user's `:on-destroy` handler (or any code reachable from it — a machine `:exit` cascade, a cleanup hook) calls `(destroy-frame! <same-id>)` while the outer destroy is still on the stack, the re-entrant call MUST silently no-op. The outer call's teardown is already in flight; re-running the recipe would re-fire `:on-destroy`, re-walk the machine cascade against an already-cleared snapshot, and corrupt half-torn-down state. Idempotent destroy is the existing pattern (a subsequent `destroy-frame!` against an already-destroyed-and-dissoc'd frame short-circuits because `(frame id)` returns nil); the re-entrancy guard closes the window BEFORE `:destroyed?` flips to true. No trace event is emitted for the re-entrant no-op — silent idempotency matches the broader "destroy is a single normative event" contract.

### Re-registration — surgical update

`reg-frame` against an already-registered keyword performs a **surgical update**: existing runtime state (`app-db`, sub-cache, router queue, in-flight events) is preserved; only the metadata/config is replaced. This is what makes hot-reload Just Work — figwheel/shadow-cljs recompile triggers re-evaluation of `reg-frame` forms, the page doesn't blink, the user's state survives. The contract for re-registration of every other registry kind (events, subs, fx, cofx, machine actions/guards, views, routes, heads, error projectors) is owned by [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics).

**What gets replaced on surgical update:**

- `:fx-overrides` map — applied to envelopes built *after* re-registration.
- `:interceptor-overrides` map — applied to envelopes built after re-registration.
- `:interceptors` vector of interceptor refs — applied to events handled after re-registration.
- `:doc`, `:ns`/`:line`/`:file` metadata.
- `:drain-depth` — applied to subsequent drains.
- `:initial-events` / `:on-destroy` — recorded for future `reset-frame!` / `destroy-frame!` calls; not re-fired on surgical update.
- `:sensitive` / `:large` / `:observability` — the frame-owned classification (EP-0015 §3). Re-registration **replaces** the frame-owned classification: the declaration *is* the frame's policy (no additive merge). The new `:sensitive` / `:large` `:app-db` paths replace the prior frame-owned ones in the durable elision registry; schema-sourced and imperative-mark-sourced declarations (a different source) survive untouched. Replacement applies *before* the `:rf.frame/re-registered` trace fires.

**What does NOT change on surgical update:**

- The live `app-db` keeps its current value.
- `:initial-events` do not re-fire (they fired on the original creation and don't re-run on re-registration).
- `:on-destroy` events do not fire (they only fire on `destroy-frame!`).
- Sub-cache, router queue, in-flight events all remain.

**Absent-key semantics on re-registration:** the re-registered metadata map is the **complete replacement** of the previous map's replaceable slots, *not* a merge. A key absent from the new map clears the previous binding; a key present overwrites. So if the original `reg-frame` set `:fx-overrides {:my-app/http stub-fn}` and the re-registration omits `:fx-overrides`, the overrides map clears (no overrides apply going forward). This matches every other `reg-*` shape (re-registering a `reg-event` replaces the handler entirely; metadata behaves the same way), and keeps the on-disk source the single source of truth — the runtime doesn't accumulate state the source no longer mentions. The slots that follow this rule are the same ones listed in *What gets replaced*: `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:doc`/`:ns`/`:line`/`:file`, `:drain-depth`, `:initial-events`, `:on-destroy`, and the frame-owned classification keys `:sensitive` / `:large` / `:observability`. The absent-key rule applies to classification too: dropping `:sensitive` on re-registration clears the prior frame-owned sensitive declarations (the source is the single source of truth). Live runtime state (`app-db`, sub-cache, queue) is preserved regardless of what the metadata map says.

**Trade-off:** there's some "config drift" between what `reg-frame` literally says and what's running. A developer who edits `:initial-events` and re-saves will not see the new setup re-run — they need to call `reset-frame!` to apply it. This matches today's re-frame: `app-db` doesn't reset when you save a file, and developers expect that.

**Trace emission on surgical update.** Each surgical re-registration emits a `:rf.frame/re-registered` trace event (per [009-Instrumentation §Frame lifecycle traces](009-Instrumentation.md#core-fields-required-on-every-event)). The trace fires *after* the metadata swap is visible to subsequent dispatches — a test fixture that asserts "the new `:fx-overrides` are in effect by the time the trace fires" can rely on this ordering. Tools (10x, re-frame-pair) listen for this op to refresh their per-frame state.

**Worked-example gotcha — `:on-destroy` clears on omit.** The absent-key rule above applies to `:on-destroy` too. If the original `reg-frame` set `:on-destroy [:todo/cleanup]` and the developer subsequently edits the source to *remove* the `:on-destroy` key (rather than replace its event vector), the next hot-reload re-registration clears the recorded teardown event. A subsequent `(destroy-frame! :todo)` then runs without firing `:todo/cleanup`. This is mostly invisible in production (frames are rarely destroyed) but bites in tests and REPL workflows that destroy frames between cases — the teardown silently stops running after a source edit. The fix is the same as for `:initial-events`: re-register with the desired keys present, or call `reset-frame!` to re-establish from the current source.

### `reset-frame!` — full replace, opt-in

For developers who want a fresh start (a test fixture, an explicit "reset to initial state" action, or a story that re-runs setup on demand):

```clojure
(rf/reset-frame! :todo)
```

Equivalent to `(destroy-frame! :todo)` followed by `(reg-frame :todo <current-config>)`:

- **The WHOLE frame is reset** — lifecycle AND *both* durable partitions. Existing `app-db` is reset to `{}` and runtime-db is cleared (every machine snapshot, route slice, elision declaration, and SSR metadata is dropped); the physical frame-state container starts fresh.
- Sub-cache is disposed; live subscriptions re-materialise on next deref.
- Router queue is cleared; any unprocessed events are dropped.
- The recorded `:initial-events` are **re-dispatched** as if it were a fresh creation, draining each step's cascade synchronously in order. This is the only thing that replays the setup — a **best-effort re-run through the current handlers**: no snapshot, no replay tape, no atomicity. Because construction is events-only, the setup script *is* the constructed state; there is no separate baseline to restore.

`reset-frame!` is the right tool for "I want this back to its initial state." Tests use it between test cases. Story tools use it for "reset" buttons. It resets the whole frame, not just app-db — for an app-db-only reset that preserves live runtime state, use `reset-app-db!` (per [§Frame-state value accessors and mutators](#frame-state-value-accessors-and-mutators)).

`destroy-frame!` (covered above) goes one step further — the frame keyword is removed from the registry; subsequent dispatch/subscribe with that frame recovers (dispatch no-ops, subscribe returns nil) and emits a production-survivable `:rf.error/frame-destroyed` through the always-on error-emit listener (see the [§Destroy](#destroy) contract).

### `:rf/default`

`:rf/default` is an **ordinary frame id** with no framework privilege (per
[§Frame target resolution §`:rf/default` is an ordinary id](#rfdefault-is-an-ordinary-id)).
The runtime does **not** register it at load time, does **not** create it from
`init!`, and does **not** infer it from a missing frame stamp. It is a perfectly
legal keyword a small app, example, or test may **explicitly** register and select
via `(rf/reg-frame :rf/default <metadata>)`, like any other frame — the
surgical-update rules above apply, and the runtime emits `:rf.frame/re-registered`
on re-registration. A migration may adopt `:rf/default` as its explicit app-frame
id; the runtime will not infer it. Absence of any frame is `:rf.error/no-frame-context`,
not a silent selection of `:rf/default`.

### Frame presets — capability bundles for common configurations

A `:preset` key on the metadata expands at registration time into a fixed bundle of metadata keys the user could otherwise write by hand. User-supplied keys win on conflict. Presets exist to make declarative intent — "this is a test frame," "this is a story frame" — visible at the call site and machine-readable from `(rf/frame-meta <id>)`.

```clojure
;; Concise; intent visible at the call site.
(rf/reg-frame :test/auth-flow
  {:preset :test})

;; The `:preset` expands; user-supplied keys override individual expansion entries.
(rf/reg-frame :test/long-running
  {:preset :test
   :drain-depth 1000})        ;; overrides the :test preset's drain-depth default
```

The closed canonical set of four presets, with their exact expansions. The expansion *table itself* is normatively captured in [Spec-Schemas §`:rf/preset-expansion`](Spec-Schemas.md#rfpreset-expansion); the four sub-sections below mirror that schema for human readability.

#### `:default`

No expansion — explicitly the empty preset. `{:preset :default}` is identical to omitting `:preset`. Acts as documentation: the user is declaring "I have considered the preset list and chosen the default."

| Expansion key | Value |
|---|---|
| (none) | (none) |

Use case: production single-frame app; multi-instance widgets.

#### `:test`

| Expansion key | Value | Why |
|---|---|---|
| `:fx-overrides` | `{:rf.http/managed :rf.http/managed-canned-success}` | The canonical Spec 014 HTTP fx is redirected to its canned-success stub so test frames don't reach the network. Test code that needs richer stubbing supplies its own `:fx-overrides` per-call or per-frame; the framework does not ship `:rf.test/*` fxs in the v1 closed set. **The reserved navigation primitives `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` / `:rf.nav/capture-scroll` are OVERRIDABLE** (host-API wrappers, no frame runtime-db write — per [§Reserved fx-ids are tiered against override](#reserved-fx-id-override-tier)), so a test stubs them to no-op navigation without touching the host. Note that the *state-installing* reserved fxs (`:rf.machine/spawn`, `:rf.fx/reg-flow`, `:rf.route/with-nav-token`, …) are HARD-REJECTED — a test cannot stub those (the override is ignored and the reserved body runs). |
| `:drain-depth` | `100` | Explicit value matches the framework default. Surfaced on the expansion so tooling can read "this is a test frame, drain bounded at 100" from `(frame-meta <id>)` without inspecting the global default. |
| `:rf.cofx/mint-policy` | `:strict` | The cofx **mint policy** ([§Mint policies](#mint-policies)) defaults to `:strict` under a test frame: a declared-absent generator-backed recordable fact is `:rf.error/missing-required-cofx`, never a freshly-minted per-run value. Strict-by-default is **core, not polish** — a determinism feature whose path of least resistance is a fresh random per run would degrade the test culture it exists to serve, so the `:test` preset makes the deterministic path the default and nondeterminism opt-in. A test that has *declared* it accepts nondeterminism opts back in with `{:rf.cofx/mint-policy :explicit-live}` (per-call dispatch opt, or as a per-frame override — user-supplied keys win on conflict per [§Expansion algorithm](#expansion-algorithm)). |

**Port-omission carve-out.** The `:fx-overrides` entry above redirects a Spec 014 fx-id. Implementations that omit Spec 014 do not register `:rf.http/managed` and therefore cannot redirect it — on such ports the `:test` preset's `:fx-overrides` expansion is `{}` (empty map). The `:drain-depth` entry is unaffected. Conformance: a port that ships Spec 014 MUST expand `:test`'s `:fx-overrides` to the exact pair above; a port that omits Spec 014 MUST expand it to `{}`. Either way, user-supplied metadata wins on conflict per [§Expansion algorithm](#expansion-algorithm).

**Clock stubbing is host-interop, not preset-level.** Tests that need deterministic time replace the interop layer's `now-ms` provider (per [§Interop layer — clock primitives](#interop-layer--clock-primitives--see-spec-005)) — they do not override an fx-id. Machine `:after` timer wake-ups are not registered as a redirectable fx-id, so `:fx-overrides` cannot reach them; the preset stays silent on time control.

Use case: per-test fixture frames (per [008-Testing](008-Testing.md)).

#### `:story`

| Expansion key | Value | Why |
|---|---|---|
| `:fx-overrides` | `{:rf.http/managed :rf.http/managed-canned-success}` | Network stubbed via the canonical Spec 014 redirect. **Time-based fxs are NOT stubbed** — stories animate in real time. Story-specific stubs (navigation no-op, etc.) are user-supplied; not shipped in the v1 closed set. |
| `:drain-depth` | `16` | Tighter bound than the framework default (100). Stories are interactive demos; a runaway dispatch cascade should fail fast under a story rather than spinning up to the production limit. |

Use case: story / variant frames (per the post-v1 [007-Stories](007-Stories.md) library).

#### `:ssr-server`

| Expansion key | Value | Why |
|---|---|---|
| `:platform` | `:server` | The frame runs on the `:server` platform. `:server`-gated fxs run; non-`:server` fxs no-op via the `:platforms` mechanism on `reg-fx` (per [011-SSR](011-SSR.md)). Single keyword — one active platform per frame. |

Server-side handler exceptions surface through the dedicated server error projection (per [011 §Server error projection](011-SSR.md#server-error-projection)) — driven by the registered error projector consuming the always-on error stream, not by any frame-config slot.

The `:initial-events` are **user-supplied** rather than preset-defaulted. The standard pattern is `(rf/reg-frame :ssr/request {:preset :ssr-server :initial-events [[:rf/server-init request]]})` — the user owns the setup so the request payload can be threaded through (see [011-SSR](011-SSR.md)). The framework does not ship a `:rf/server-init` handler. (`:preset` and `:initial-events` are **record-config** keys: under EP-0024 they ride both `reg-frame` and the one `make-frame` constructor, alongside the image-selection keys, in one call — see [§Per-instance frames](#per-instance-frames--make-frame-the-ep-0023-object-constructor).)

Use case: per-request server-side render frame (per [011-SSR.md](011-SSR.md)).

#### Expansion algorithm

At registration time, the runtime:

1. Reads the `:preset` key from the user's metadata (if any).
2. Looks up the expansion table (above).
3. Constructs an effective metadata map: `(merge expansion user-supplied-metadata)`. **User keys win on conflict** — the preset is a default, not a closed bundle.
4. The effective metadata is what `(frame-meta <id>)` returns; the original `:preset` is preserved as a metadata field for inspection. The returned shape conforms to [Spec-Schemas §`:rf/frame-meta`](Spec-Schemas.md#rfframe-meta); the table-itself shape is [§`:rf/preset-expansion`](Spec-Schemas.md#rfpreset-expansion).

Reading `(rf/frame-meta :test/auth-flow)` returns the *effective* map; the `:preset` key is preserved verbatim so tools can inspect which preset was applied:

```clojure
(rf/frame-meta :test/auth-flow)
;; → {:preset      :test
;;    :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
;;    :drain-depth 100}
```

#### Adding presets

The four above are the **closed v1 set**. Adding a fifth preset is a Spec-change-only operation: presets are fixed and additive. The framework will *not* recognise unknown preset values; passing `:preset :devcards` to a runtime that doesn't ship that preset emits `:rf.error/unknown-preset` at registration time.

The preset set is closed; the four presets are canonical for AI scaffolding (an AI reading the spec sees the closed set and chooses from it).

#### `:preset` is a record-config key

`:preset` is a **record-config** key, so it rides both `reg-frame` (the front-porch named path) and `make-frame` (the one constructor), applied with the same expansion algorithm and the same conflict-resolution rule. `make-frame` honours record-config keys alongside the image-selection keys (it does not reject them — see [§Per-instance frames](#per-instance-frames--make-frame-the-ep-0023-object-constructor)):

```clojure
(rf/make-frame {:preset :test
                :images [counter-image]
                :initial-events [[:counter/init]]})
;; → a frame VALUE with the :test preset's expansion applied
```

`:preset` is recognised on `make-frame`, not rejected; there is no `:rf.error/make-frame-record-only-key` redirect fencing record-config keys off the constructor.

### Per-instance frames — `make-frame` (the EP-0023 object constructor)

> **One constructor.** `make-frame` is the one constructor (image-selection + record-config opts in one call) that returns the live frame **value**. The heading keeps the EP-0023 anchor for inbound links.

Some use cases need a frame *per mount* rather than a named singleton — devcards, modal stacks, multiple live instances of a `[counter-widget]`, dynamic tabs. For these, re-frame2 ships `make-frame` alongside `reg-frame`. Per [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md), **`make-frame` is the single public constructor for a live frame** — it accepts image-selection options *and* frame-configuration (record-config) options in one call, and **returns the live frame value**. No caller has to create a backing frame first and then create an image-loaded object for the same id: one frame, one call.

```clojure
(rf/make-frame {:images [counter-image]}) → <frame value>   ;; the live frame VALUE (representation hidden)
(rf/frame-value->id frame)                   → :rf.frame/…   ;; read the id via the one accessor
(rf/destroy-frame! frame)                                    ;; the value (or its id) destroys it

(rf/reg-frame :counter {:initial-events [[:counter/init]]})  ;; the front-porch named path is unchanged

(defn counter-widget [label]
  ;; A view that needs a named frame for its mounted lifetime uses the merged
  ;; provider's ENSURE shape — create-if-absent, reuse-no-reseed, no
  ;; destroy-on-unmount (EP-0024 amended). True ownership (explicit teardown)
  ;; stays make-frame + destroy-frame! in a create-class.
  [rf/frame-provider {:id :counter/widget :images [counter-image] :initial-events [[:rf/set-db {:count 0}]]}
   [counter-view label]])
```

`opts` is a **map** — a non-map `opts` (`nil`, a keyword, a vector, …) is rejected at the public boundary with `:rf.error/make-frame-bad-opts` (the all-defaults frame is `(make-frame {})`, never `(make-frame nil)`). The opts map accepts **image-selection** keys — `:images` (a **non-empty** vector — the assembled registration set the frame resolves against; `:images []` is an error and **omitting `:images` resolves the default image** over the whole source store, see [§Image resolution and composition](#image-resolution-and-composition)), `:id` (optional — registers the frame in the one live-frame registry; duplicate-id is **idempotent replacement**, not a blanket fail-loud — see [§Duplicate id](#duplicate-id--idempotent-replacement)), and `:adapter` — **and** the **record-config** keys `:initial-events` (seed app-db via a leading `[:rf/set-db {…}]` step), `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`, `:interceptors`, `:drain-depth`, in the same call. (There is no `:capabilities` image-selection key — image-declared host capabilities are not supported ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md)); a `:capabilities` key is not special-cased and flows through as ordinary record-config.) A frame created **without** an `:id` is a direct local reference that bypasses the registry — appropriate for local tests and harnesses where the frame is created, used, and discarded in one scope (per [EP-0023 §Frame](../docs/EP/EP-0023-image-loaded-frames.md): a registration id like `:counter/inc` can be reused across images; a live frame id cannot name two live registered frames at once).

**The frame value's representation is hidden; route by id.** `make-frame` returns the value, but the public routing address is the frame **id** — read it from the value via the one accessor (`rf/frame-value->id`) and pass the id to `dispatch` / `subscribe` / providers / tools. Internal normalization may accept a frame value where it is useful for tests or tools, but the API teaches one routing address: the frame id (per [EP-0024 §Operation target grammar](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md#operation-target-grammar)).

> **Single constructor.** `make-frame` is one constructor honouring both image-selection and record-config key families. The advanced `re-frame.frame/make-frame` is internal or absent. There is no `:rf.error/make-frame-record-only-key` redirect.

**Lifecycle ownership.** A `make-frame` call you make directly is paired with an explicit `destroy-frame!` (creation and teardown are explicit ownership operations — see [§The multi-frame surface](#the-multi-frame-surface--choose-by-intent)). For a view that just needs a named frame to exist while it is mounted, prefer the merged `frame-provider`'s ENSURE shape (`{:id …}`), which creates-if-absent and reuses-without-reseeding — but does **not** destroy on unmount (see [§the merged `frame-provider`](#the-merged-config-shaped-frame-provider-cljs-reference)). When a view genuinely owns a frame's teardown (a modal that should drop its frame when it closes), pair `make-frame` with `destroy-frame!` explicitly inside a `create-class`. The canonical signature row is [API §Registration — `make-frame`](API.md#registration); the `image → frame → event stream` model it constructs is owned by [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) and [§Frame addressing](#frames-reference-realms).

Tests use the direct-constructor pattern as their fixture lifecycle:

```clojure
(rf/reg-event :auth/init-idle (fn [_ _] {:db {:auth/state :idle}}))

(deftest auth-flow
  (let [f (rf/make-frame {:images [auth-image] :initial-events [[:rf/set-db {:auth/state :idle}]]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame (rf/frame-value->id f)})
      (is (= :validating (get-in (rf/app-db-value (rf/frame-value->id f)) [:auth :state])))
      (finally
        (rf/destroy-frame! f)))))
```

#### Duplicate id — idempotent replacement

`make-frame` (and `reg-frame`) with an `:id` that already names a live frame performs **idempotent replacement** (EP-0024): re-evaluating the same frame declaration updates frame configuration and the resolved image generation **without destroying durable state**, unless the caller explicitly asks for `reset-frame!` or `destroy-frame!`. This is hot-reload-friendly and Story-re-evaluation-friendly — re-mounting under the same id preserves the live app-db and runtime-db. Conflict cases that cannot be reconciled still **fail loud**.

> **Note.** Idempotent replacement here is the same contract the UI-owned `frame-provider` relies on for its idempotent re-mount (per [EP-0024 §Duplicate id policy](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md#duplicate-id-policy)).

### Image resolution and composition

`:images` is **ordered data** ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Layered Resolution). `make-frame` resolves the `:images` vector into one sealed [resolved image generation](Conventions.md#frame-vocabulary--one-name-per-frame-fact) — selecting each image's descriptors, layering them in image order, validating, and sealing — before the frame runs any event. The `:rf.image/*` source-key grammar lives in [Conventions §The public `rf/image` source keys](Conventions.md#the-public-rfimage-source-keys); this section governs how a composition of those images resolves.

**The later image wins.** A frame created with `{:images [base product overrides]}` resolves by one rule: a `[kind id]` present in more than one image resolves to the **last** image that defines it. Image order is the **only** precedence — there is no within-image winner rule, because an image must resolve cleanly to one descriptor per `[kind id]`. So every override is **between** images: a later image **shadows** an earlier one. To override a registration, put the winner in a later image and compose.

**Within-image collisions fail loud.** Any `[kind id]` that resolves two ways inside a single image is an **error**, not an override:

- two **selected** descriptors for one `[kind id]` (different source namespaces) — ambiguous; `:rf.error/image-duplicate-id`. (The ordinary same-source hot-reload replacement of one namespace's own descriptor is not a collision — it dedupes.)
- an **inline** entry colliding with a **selected** one, or two **inline** entries — `:rf.error/image-within-image-collision`. (`:select-ns` and `:registrations` in one image must be disjoint.)

**The shadow report.** When a later image shadows an earlier one, composition **records** it. The generation carries the report at `:rf.gen/shadows`, read by the public **`rf/frame-shadows`** accessor. Each entry is exactly three keys — the registration, the image it was defined in, and the image that shadowed it:

```clojure
(let [frame (rf/make-frame {:images [app-image test-doubles]})]
  (rf/frame-shadows frame))
;; =>
[{:registration [:fx :checkout.http/post]
  :image        :app/main
  :shadowed-by  :test/doubles}]
```

A cross-image shadow **resolves** (later wins) and is reported — it does **not** fail assembly. The programmer reads the report and applies whatever policy they want (assert none, assert a known set, log, ignore); there is no upfront acknowledgement key. When images form a **chain** for one `[kind id]` (`[base override-a override-b]`), the report names the **final** winner for every loser (`base` and `override-a` are both `:shadowed-by override-b`), so an assertion never walks a chain. Every shadow is cross-image — there is no within-image case and no scope tag. Images are named by their composition-unique id, so two images sharing an `:id` in one composition fail loud (`:rf.error/image-duplicate-image-id`).

**Framework standards are protected.** Standards are not part of ordinary app image layer order. An app descriptor whose `[kind id]` collides with a framework standard's **fails loud** (`:rf.error/image-standard-replacement-forbidden`) — a standard encodes an execution invariant (e.g. `:rf.interceptor/path` and the app-db commit no-op), so shadowing it is a correctness violation, not an app policy choice. There is no public standard-replacement opt-in.

**The `:images` boundary — absent, empty, explicit** ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Default Image). `make-frame` resolves `:images` by three cases:

- **absent `:images`** (`(make-frame {})`) resolves the **default image generation** — the implicit selector over the **whole active source store** plus the framework standards. The default projection **fails loud** on a cross-namespace same-`[kind id]` collision (`:rf.error/image-duplicate-id`) exactly as an explicit image does: load order never silently decides the survivor, and there is no last-writer-wins on the default path.
- **empty `:images []`** is an **error** (`:rf.error/make-frame-bad-images`): pass at least one image, or omit `:images` for the default. To create a frame with no app registrations, pass a real empty image — `(rf/make-frame {:images [(rf/image {:id :test/empty})]})`.
- **explicit non-empty `:images`** resolves the selected, layered composition (above).

App isolation — a frame that must see only its own registrations — is requested through the **same image API as every other selection** (`rf/image` + `:select-ns`), not through a separate scope concept and not through the absence of `:images`. A frame that omits `:images` resolves the whole store; a frame that wants a narrower set names it with an explicit image.

**Caching.** Resolved generations are immutable and may be physically shared across frames. The reference implementation caches by the image **inputs** — the ordered normalized `:images` vector (which carries the `:select-ns` selection and the inline descriptors by value, and whose **order** decides composition) plus the registration source-store generation and the framework-standard generation. Two compositions differing only in their selection **or their image order** occupy different cache slots, so a re-seal happens on any change to a selected, standard, or inline input or to the image order; an unchanged composition returns the same sealed object (the SSR fast path).

## Routing: the dispatch envelope

The mechanism that gets a dispatch to the right frame is **frame identity carried on the in-flight event**.

User-facing event shape is a vector — `[:add-todo "milk"]` — id-first, polymorphic on the head keyword. The **canonical call shapes** are:

| Arity | Canonical | Tolerated (discouraged) |
|---|---|---|
| Trivial — id only | `[:counter/inc]` | (same) |
| Single argument | `[:user-by-id 42]` | (same) |
| Multi-argument | `[:user/login {:email e :password p}]` (single map payload) | `[:user/login e p]` (multi-positional; linter nudges) |

The hybrid `[<id> <map>]` shape for non-trivial events is canonical. Subscribe takes the same shape (`[:items-filtered {:status :pending :limit 20}]`). The full rationale is in [Principles §Name over place](Principles.md#name-over-place); the migration rule for v1 multi-positional code is [MIGRATION §M-19](../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in). The v1 `unwrap` interceptor (which required this exact `[event-id payload-map]` shape) ships in v2 as opt-in handler-side sugar; v1 `trim-v` is not provided.

*Internally*, every dispatch becomes a **dispatch envelope**:

```clojure
{:event        [:add-todo "milk"]      ;; the user-facing vector, unchanged
 :frame        :todo                   ;; resolved frame keyword
 :fx-overrides {:my-app/http stub-fn}  ;; per-dispatch fx replacements (master's dispatch-with)
 :trace-id     "..."                   ;; tooling/agent fields
 :source       :ui                     ;; trigger kind — the canonical closed enum lives on `:rf/dispatch-envelope`'s `:source` row in [Spec-Schemas](Spec-Schemas.md#rfdispatch-envelope) (the single source of the value set); defaults to `:unknown` (previously `:ui`); substrate-internal dispatch sites stamp the matching value (`:after-timer`, `:machine-spawn`, `:machine-action`, `:fx-dispatch`, `:fx-dispatch-later`). See [§Dispatch origin tagging](#dispatch-origin-tagging) below for the `:source` vs `:origin` distinction and the full inventory.
 :origin       :pair                   ;; actor identity — open vocabulary, defaults to `:app`; e.g. `:pair`, `:claude`, `:story`, `:test`
 :rf.cofx      {:rf/time-ms 1781078400123} ;; recordable coeffects (flat, fact-name→value) — see §Recordable coeffects
 }
```

> **The envelope carries `:rf.frame/id` only — there is no second routing coordinate.** The frame id is the whole address ([§Frame addressing](#frames-reference-realms)). There is no `:rf.realm/id` realm stamp and no multi-realm substrate ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md)): event / subscription / fx / cofx handlers all resolve from the one process-global registrar, and the frame supplies state and its resolved image generation, not a separate registrar. `:rf.frame/id` rides on the envelope **beside** the shipped causal-token carriers (EP-0010 replay records, EP-0011 reply maps, EP-0016 continuation payloads), and the frame is **carried** — an explicit `:frame` dispatch opt, the carried frame value, or inherited by a child dispatch — never inferred from ambient process state (the EP-0002 carried invariant; there is no `with-realm`).

The envelope is just a map. Any field can be set by:

- **The two-arg dispatch form** — `(dispatch [:foo] {:frame :todo :fx-overrides {...}})`. The opts map's keys flow into the envelope. `dispatch-sync` takes the same opts arg. The opts map's schema is `:rf/dispatch-opts` in [Spec-Schemas](Spec-Schemas.md#rfdispatch-opts) — a strict subset of the envelope (the runtime supplies `:event` and stamps `:rf.cofx` `:rf/time-ms` when absent). A caller MAY supply exact recordable facts via the `:rf.cofx` opt (`(dispatch [:e] {:rf.cofx {...}})`) for replay, tests, SSR hydration, and host integrations — supplied values are preserved verbatim and never overwritten (see [§Recordable coeffects](#recordable-coeffects)).
- **Frame-level config** — `reg-frame` keys (`:fx-overrides`, `:interceptor-overrides`, etc.) are merged into the envelope by the routing layer when an event is routed to that frame.
- **Lexical injection** — `reg-view`-injected `dispatch` closures carry `:frame` from React context.

The two-arg `dispatch` form is the single mechanism for setting envelope fields per call: `(dispatch event {:frame :todo :fx-overrides {...}})`. Per-event override variants like `dispatch-to`, `dispatch-with`, and `dispatch-sync-with` are not part of the API. Event-vector metadata is not an opt-channel in v2; use the two-arg `(dispatch event opts)` form. (The one v1 metadata case — `^:flush-dom` — is rewritten to `:dispatch-later {:ms 0}`; see [MIGRATION.md §M-16](../migration/from-re-frame-v1/README.md#m-16-flush-dom-event-vector-metadata-removed--replace-with-dispatch-later-ms-0-inside-effect-maps-or-the-top-level-rewrite-outside-event-handlers).)

The router reads the envelope's `:frame`, looks up the frame in the registry, and runs the interceptor pipeline against that frame's `app-db`/router context. Handlers receive the one shape — the coeffects map plus the event vector (`(fn [coeffects event-vec] …)`); the envelope is not exposed to user handlers.

### How `:frame` gets attached

The frame stamp on the envelope is supplied through the **scope / hold / override**
intents of [§Frame target resolution](#frame-target-resolution--the-carried-invariant) —
**not** a priority list of ambient places that bottoms out in a default. There is no
`:rf/default` fallback rung; absence is `:rf.error/no-frame-context`.

1. **override** — explicit `:frame` in the dispatch opts map. `(dispatch [:foo] {:frame :todo})` always wins; the opts map's keys flow straight into the dispatch envelope.
2. **hold** — a carried stamp. `reg-view`-injected `dispatch` carries the frame captured from React context at render (internally `(fn [event] (dispatch event {:frame <captured>}))`); a `capture-frame` / `frame-bound-fn*` op carries the frame captured at creation; a cascade child inherits `:frame` from the parent envelope threaded through the queue.
3. **scope** — an established synchronous region. Inside `(with-frame :todo …)` a dynamic var carries the frame, so a bare `(rf/dispatch [:foo])` in the body resolves to `:todo`. **The router establishes the same scope around every running handler** (per [§Dispatches issued from inside a handler body](#dispatches-issued-from-inside-a-handler-body) below), so a synchronous `(rf/dispatch [:foo])` from inside a handler running on `:todo` resolves to `:todo`.
4. **absent** — none of the above. `(rf/dispatch [:foo])` with no carried stamp and no established scope fails with `:rf.error/no-frame-context` (per [§The error and its ladder](#the-error-and-its-ladder)). The runtime does not synthesise a frame.

### Dispatches issued from inside a handler body

The router binds the dynamic-var tier of the resolution chain to the in-flight event's `:frame` for the duration of `process-event!`. The contract is:

- **Synchronous dispatch from inside a handler body routes to the handler's frame.** A `reg-event` handler whose body calls `(rf/dispatch [:child])` and returns `{}` dispatches `:child` to the same frame the parent is running on. The same applies to `(rf/capture-frame)` and `(rf/current-frame-id)` — both read the dynamic-var tier first.
- **Async callbacks escape the scope.** When a handler defers work via `js/setTimeout`, `js/Promise.then`, `requestAnimationFrame`, or any other host-level async primitive, the deferred callback fires on a fresh stack with no dynamic binding — the *scope* has evaporated. A bare `(rf/dispatch [:child])` from inside the callback carries no stamp and fails with `:rf.error/no-frame-context` (it does **not** fall through to `:rf/default`). This is why async paths must use *hold*: capture the frame as a value before the boundary. The escape is a fundamental property of dynamic scope; the loud failure is the contract working as designed (per [§Frame target resolution](#frame-target-resolution--the-carried-invariant)).

The three frame-safe affordances for async callbacks are, in canonical-first order:

1. **`:fx [[:dispatch event-vec]]`** — the fx walker (`re-frame.fx/do-fx`) calls `(dispatch! event-vec {:frame frame-id})` with `frame-id` already resolved from the in-flight envelope. The dispatch is synchronous with the enclosing handler's drain, so any timer / promise the user wants to schedule should be modelled as a returned effect, not a manual `js/setTimeout`. This is the canonical multi-frame pattern.

2. **`:fx [[:dispatch-later {:ms <n> :event event-vec}]]`** — the `:dispatch-later` fx captures `frame-id` in its closure before scheduling the timer, so the deferred dispatch carries the correct frame regardless of when the timer fires.

3. **`(rf/capture-frame)`** — captures the active frame at creation and returns an OPERATION BUNDLE `{:frame :dispatch :dispatch-sync :subscribe}` whose ops route to the captured frame. Use when the handler must hand a dispatch / subscribe fn to a non-fx async library (a websocket subscription, a third-party SDK that takes a callback) where neither `:fx` nor `:dispatch-later` fits: `(let [{:keys [dispatch]} (rf/capture-frame)] (sdk/on-message dispatch))`.

The contract is regression-tested by `re-frame.dispatch-frame-capture-cljs-test`. Pattern-LongRunningWork and Pattern-WebSocket both rely on it.

### Dispatch origin tagging

The dispatch opts map accepts an optional `:origin` key — a tag identifying the *actor* that issued the dispatch:

```clojure
(rf/dispatch [:user/login {:email e}] {:origin :pair})
```

`:origin` is unconstrained at the framework level — tools and applications agree on values (`:pair`, `:claude`, `:story`, `:test`, etc.). The value flows into the dispatch envelope and is lifted by the trace surface onto every `:rf.event/dispatched` trace event under `:tags :rf.event/origin` (per [009 §Origin tagging](009-Instrumentation.md#origin-tagging-rfeventorigin)). The default when the opt is omitted is `:app`.

Pair-shaped tools and other tooling surfaces set `:origin` to filter their own activity in post-mortem trace views — "show me only the dispatches the pair tool issued during this session" becomes a one-key filter on the trace stream. User application code typically omits the opt; framework code (the SSR boot path, the router, the machine timer) sets it to a runtime-reserved value (`:rf/router`, `:rf/ssr`, etc.) where the distinction is useful.

`:origin` is **distinct from `:source`** (the existing envelope key). `:source` describes the trigger kind / functional origin — what *woke* the runtime; the canonical enum is `:rf/dispatch-envelope`'s `:source` in [Spec-Schemas](Spec-Schemas.md#rfdispatch-envelope) (`:ui`, `:frame-init`, `:machine-spawn`, `:machine-action`, `:always`, `:after-timer`, `:fx-dispatch`, `:fx-dispatch-later`, `:http`, `:router`, `:ssr-hydration`, `:test`, `:tool`, `:websocket`, `:repl`, `:unknown`, `:other`). `:origin` describes the actor identity — *who* issued the dispatch. Both can be set independently; tools commonly set `:origin :pair` and let `:source` default to `:unknown` (or stamp `:source :tool`).

The default `:source` value is **`:unknown`**. Frame-init dispatches (the `:initial-events` steps fired by `reg-frame`) explicitly stamp `:source :frame-init` (plus the step index) and carry the `reg-frame` call-site coord under `:rf.trace/call-site` so click-to-source jumps to the `(rf/reg-frame ...)` line. UI handler call-sites either explicitly stamp `:source :ui` or render as `:unknown` — the framework does not assume UI provenance.

Substrate-internal dispatch sites stamp their own specific `:source` kind so the Epoch panel's DISPATCH step renders the precise trigger rather than the prior aggregate (the broad `:fx` / `:machine` / `:dispatch-later` / `:timer` aliases were dropped — every dispatch site stamps the matching specific kind):

| `:source` value | Stamped by | When |
|---|---|---|
| `:after-timer` | machine substrate's `:after` timer-fire path | timer's delay elapses + the substrate dispatches the synthetic `:rf.machine.timer/after-elapsed` trigger |
| `:always` | machine substrate's `:always` microstep loop | per-microstep marker on `:rf.machine.microstep/transition`; `:always` does not produce its own envelope (it runs intra-macrostep) but the value is reserved on the closed set so tools have a consistent vocabulary |
| `:machine-spawn` | spawn-fx (`:rf.machine/spawn`) | a machine spawns + the substrate dispatches the spawned actor's `:start` (or synthetic `[:rf.machine.spawn/spawned]`) initial-entry trigger |
| `:machine-action` | `:dispatch` / `:dispatch-later` fx handler when the emitting handler is a machine (`:rf.machine/internal? true` on the parent envelope) | a machine-handler-issued `(rf/dispatch …)` — the actor-message path. Carries the same `:source-detail {:ms <ms>}` when emitted via `:dispatch-later`. |
| `:fx-dispatch` | `:dispatch` fx handler (non-machine parent) | the `:dispatch` reserved fx executes and enqueues a child dispatch from an ordinary event handler |
| `:fx-dispatch-later` | `:dispatch-later` fx handler (non-machine parent) | the `:dispatch-later` reserved fx fires after its delay from an ordinary event handler |
| `:http` | `re-frame.http_encoding/dispatch-reply-via-late-bind!` | managed-HTTP reply settle — `:on-success` / `:on-failure` cascade entry |
| `:router` | `re-frame.routing` internal dispatches | routing-internal cascade (route-link click, on-match-error). |
| `:ssr-hydration` | hydration boot site | the `:rf/hydrate` cascade per Spec 011 |
| `:test` | `re-frame.test_support/dispatch-sequence` | test-harness opt-in |
| `:tool` | tooling adapters (Xray controls, Story play scripts, pair-MCP write surface) | tool-issued dispatch. |
| `:websocket` | application-level websocket adapters | reserved closed-set slot; apps opt in. |

The naming preserves the spec's own terminology — `:after`, `:always`, `:dispatch-later` — so panel labels grep back to spec/005 and spec/002 directly. `:source` is the single closed-enum functional-origin axis on the dispatch envelope; there is no separate `:rf/dispatch-origin` axis. `:source` is **not inherited** through `:fx [[:dispatch ...]]` cascades — each child dispatch's `:source` reflects its *immediate* trigger (`:fx-dispatch` / `:fx-dispatch-later` / `:machine-action`), not the originating user event's. Inheritance applies to `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:origin`, and `:frame`. See [009 §Dispatch source as the functional-origin axis](009-Instrumentation.md#dispatch-source-as-the-functional-origin-axis-source) for the full canonical inventory + consumer expectations.

<a id="causal-world-inputs"></a>

## Recordable coeffects

re-frame2's core model is a causal fold: `next-frame-state = transition(previous-frame-state, causal-token)`. That model is only literal when a transition's durable result is determined by prior frame-state plus the token being folded. An event handler, resource reducer, work-ledger writer, machine action, or routing reducer that calls the host directly for facts — "what time is it?", "give me a UUID", "what URL is the browser on?" — and writes the result into app-db or runtime-db produces state that is correct for the live session but **not replayable as a value**. The dispatch envelope therefore carries a canonical `:rf.cofx` map of **recordable coeffects**, delivered to handlers that declare them, so those host facts enter the fold as causal input data rather than as ambient reads at the write site. The discipline in one sentence: **durable state folds facts, never reads.** The recording rule is specified in [EP-0010](../docs/EP/EP-0010-causal-world-inputs.md) (with the diagnostic/host-transient classification, the randomness/UUID/browser/storage rules, and the restore/replay/hydration semantics); the authoring surface — the `:rf.cofx` envelope field, the `:rf.cofx/requires` declaration, the graded `reg-cofx` registrar, and the removal of `inject-cofx` — is specified in [EP-0017](../docs/EP/EP-0017-recordable-coeffects.md). The two coeffect grades and the registrar contract are owned by [Spec 001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded); the envelope + delivery + stamping rules are normative here.

### The two grades (summary)

Every coeffect id is **registered** ([001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded)) and carries a grade:

- **Ambient** (the default) — its value-returning supplier runs at context assembly, the value is delivered to declaring handlers and **never recorded**; replay re-runs the supplier. Permitted only where no durable write depends on the value (display preferences, diagnostics, host-transient measurements).
- **Recordable** (`:recordable? true`) — the fact is **ensured onto the causal token** before the fold consumes it, recorded with the token, and re-presented verbatim by replay. Required for any fact that can affect durable frame-state.

### The recordable-coeffect rule (the durable-write rule)

A transition that performs a **durable write** MUST be deterministic with respect to prior frame-state and the causal token being folded. Therefore:

> If a world fact can affect a durable write, the transition MUST read that fact from a recordable coeffect on the causal token (or from the event payload). It MUST NOT read the host ambiently at the durable write site.

A **durable write** is any write to app-db, runtime-db, a resource entry, a work-ledger row, a machine snapshot, durable routing state, an epoch record, or a hydration payload — anything that rides epoch-restore, SSR/hydration projection, or a replay log (per [§Durable vs transient](#durable-vs-transient)). A **world fact** is a host fact not determined by prior frame-state and the current causal token: wall-clock time, monotonic time, randomness, generated UUIDs, browser location/visibility/online status, storage reads, and asynchronous-completion facts such as a network reply's receipt time.

Ambient host reads remain allowed for **diagnostics** (dev-only trace timestamps, performance spans, always-on error metadata), **host-transient** scheduling and side tables (timers, AbortControllers, caches, monotonic high-water allocators), effect interpretation, and clock-skew measurement — provided their values do not directly decide a durable write. The rule is not "no host"; it is "no hidden host facts in durable writes". It applies equally to application handlers and framework internals.

#### Durable join keys are recordable, even when their allocator is host-transient

The host-transient exception above covers the **allocator** (a monotone high-water counter held outside the frame value), not necessarily the **value it allocates**. A generated identity is itself a world fact: re-running its ambient allocator on a different occasion yields a *different* value. So the durable-write rule has a corollary that is easy to miss because the allocator and the value are read at the same site:

> A generated identity that is either **written into durable state** *or* **later used as a join key** — gating async replies, dispatching continuations, suppressing stale results, or matching a pending slot — MUST be folded from a recordable coeffect (or the event payload). This holds **even when its allocator stays host-transient**.

The two halves pull in opposite directions:

- **The allocator stays host-transient and monotone.** A counter whose values can be carried by an out-of-frame, uncancellable continuation (a reply already on the wire) MUST NOT be rewound by epoch-restore, or a post-restore allocation could collide with a pre-restore identity a late reply still carries. Recording the *counter* into durable state is therefore not required — and is usually **wrong**, because restore would rewind it and recycle a live identity. (This is the opposite discipline from a snapshot-local allocator whose identities never leave the frame — Spec 005's `:rf/spawn-counter` — which is safely snapshot-resident and replay-deterministic; see [§The minting ladder](#the-minting-ladder), below.)
- **The allocated value is recorded.** What MUST be recordable is the *value* that was minted — the identity written into the entry/instance/slot and stamped onto the reply token. Mint it from an ambient coeffect and write it durably and recorded events that reference the original identity will not reproduce it under replay: the replayed handler re-runs the ambient allocator and mints a *different* identity, so a recorded reply that was *accepted* (its join key matched the live identity) replays as *stale-suppressed* (the re-minted identity no longer matches), or vice versa. The recorded log and the replayed state diverge — the exact failure this rule exists to kill.

The shape of the fix (when a subsystem trips this) is a **split recordable allocation coeffect**: keep the host-transient allocator, but deliver the minted value (and, where a restore must re-establish the host high-water, the allocator position) as a *recordable* fact on the causal token, write only that value into durable state, and advance the host counter with `max` so replay/restore cannot rewind it. Strict replay then fails loudly on a missing allocation rather than silently re-minting.

<a id="the-minting-ladder"></a>

#### The minting ladder

When a handler or machine needs a generated identity "from the world", [EP-0017 §7](../docs/EP/EP-0017-recordable-coeffects.md) gives a preference order — recorded coeffects are the **last** rung, not the default:

1. **Derive from recorded state** where possible — Spec 005's `:rf/spawn-counter` is the exemplar: deterministic identity from a snapshot-resident counter, so nothing new is recorded and replay is deterministic for free.
2. **Ride the event payload** where the dispatch site owns the fact's meaning — e.g. an optimistic-create id the view must render immediately.
3. **Recorded coeffect** only for genuinely fold-internal identities that none of the above can supply.

A subsystem that already satisfies rung 1 or 2 has no hole; one that mints a durable join key from an ambient read at the write site is on neither rung and trips the rule above.

<a id="the-rfworldinputs-envelope-field"></a>

### The `:rf.cofx` envelope field

`:rf.cofx` is an EDN map on every dispatch and reply envelope — **flat**, fact-name → value, no grouping sub-maps:

```clojure
{:event   [:counter/inc]
 :rf.cofx {:rf/time-ms    1781078400123    ;; framework, provided (enqueue stamp)
           :counter/delta 4}}              ;; app, generated at processing-start (slice B)
```

- It is serializable after the same projection, elision, and privacy rules as other replayable event data ([EP-0015](../docs/EP/EP-0015-frame-owned-egress-policy.md) applies per leaf; `:rf/time-ms` is always safe to surface).
- It is stamped **unconditionally in production** — recordable coeffects are durable causal data, not diagnostics that elide under `goog.DEBUG=false`.
- The dispatch-opts key is `:rf.cofx` (`(rf/dispatch [:e] {:rf.cofx {...}})`); supplied values are preserved verbatim and never overwritten (§Supplied values win, below).
- Each fact's name is **owner-qualified** (`:rf/*` framework, subsystem roots for subsystem facts, app namespaces for app facts) — per [Conventions §Recordable-coeffect fact naming](Conventions.md#recordable-coeffect-fact-naming-rfcofx).

The schema is registered as [`:rf.cofx`](Spec-Schemas.md#rfcofx) in [Spec-Schemas](Spec-Schemas.md); it is an optional key of [`:rf/dispatch-opts`](Spec-Schemas.md#rfdispatch-opts) and a (runtime-guaranteed) key of [`:rf/dispatch-envelope`](Spec-Schemas.md#rfdispatch-envelope). `:rf.world/inputs` is **retired** with no alias and no coexistence window: the flat recordable-coeffect map is supplied under `:rf.cofx`. `:rf.world/inputs` only ever named this fact in the spec's own **drafts** — it never shipped in a released artefact — so it earns **no dedicated retired-name error id** (per [Conventions §The tombstone rule](Conventions.md#the-tombstone-rule--dedicated-retired-name-error-ids-only-for-shipped-names)). Supplying it in dispatch opts is caught by the generic unrecognised-opt surface (`:rf.warning/unknown-dispatch-opt`), whose message appends a **did-you-mean** naming `:rf.cofx` as the replacement — the same closed-key rejection any misspelt opt receives, kept actionable by the targeted hint.

**Two sampling moments are normative.** `:rf.cofx` is one causal record, not one sampling instant: `:rf/time-ms` is sampled at *enqueue* (queue latency is real causal time); generator-backed facts are sampled at *processing-start* — the declaration is only knowable once the handler is resolved, late registration is legal (the generation step runs under the active [mint policy](#mint-policies)). Both precede the fold; a generated value postdating its token's `:rf/time-ms` is correct behavior, not a bug.

<a id="envelope-stamping"></a>

#### Envelope stamping (`:rf/time-ms`)

`:rf/time-ms` is the framework's **one built-in** coeffect registration — recordable, provided ([001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded)). When a dispatch envelope is built, the router MUST ensure `:rf.cofx` exists and contains `:rf/time-ms`:

- **Caller omitted it** → the router stamps `:rf.cofx {:rf/time-ms (epoch-now-ms)}`. The wall-clock epoch-ms read (`interop/epoch-now-ms` — `js/Date.now()` / `System/currentTimeMillis`, **not** the origin-relative `interop/now-ms` / `performance.now()`) happens **once, at the causal boundary** — it is not repeated inside the handler, flow transform, resource reducer, work-ledger writer, or commit path. The durable timestamp must be wall-clock epoch ms so it stays comparable with `js/Date`-based freshness checks (resource `:stale-at`, invalidation). `:rf/time-ms` is the canonical durable wall-clock fact: app entity `:created-at` / `:updated-at`, resource `:loaded-at` / `:stale-at` / `:invalidated-at`, work-ledger `:started-at` / `:deadline-at` / `:completed-at`, mutation `:started-at` / `:settled-at`, durable routing timestamps, machine snapshot times, and epoch record causal time all read it **from the envelope's `:rf.cofx`** — the framework's own durable writers are envelope consumers, not handler-declaration consumers.
- **Caller supplied it** → the router preserves the supplied map verbatim and fills only the framework-required `:rf/time-ms` when absent (it never overwrites a supplied `:rf/time-ms`). This is how tests, replay fixtures, SSR hydration, and host integrations provide exact world facts.

Child dispatches produced by `:dispatch` / `:dispatch-later` get their **own** `:rf.cofx` map. They MUST NOT inherit the parent's `:rf/time-ms` — each is a distinct causal token — so `:rf.cofx` is absent from the cascade's `inheritable-envelope-keys` (per [§Cascade propagation](#cascade-propagation)) and is stamped fresh per child. Timer-fire events, HTTP replies, router events, machine timer events, SSR hydration events, and tool-issued events are all dispatch envelopes for this purpose; each stamps or supplies its own `:rf.cofx`.

Unlike the dev-only `:dispatch-id` correlation slot, `:rf.cofx` is stamped **unconditionally** (in production as well as dev): recordable coeffects are durable causal data that durable writes fold, not a diagnostic that may elide under `goog.DEBUG=false`.

#### Declaration and delivery — `:rf.cofx/requires`

A handler declares the coeffects it consumes via the registration-metadata key **`:rf.cofx/requires`** ([001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)) — a vector of registered coeffect ids on `reg-event`. With the one event form (EP-0018) every handler can declare coeffects uniformly; there is no db-only form exempt from the declaration surface. Handlers read declared facts directly, flat:

```clojure
(rf/reg-event :todo/create
  {:doc "Create a todo, stamping its creation time."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [text]}]]
    (let [todo-id (random-uuid)]                 ;; slice A: id rides the payload or derives from state
      {:db (assoc-in db [:todos todo-id]
                     {:id todo-id :text text :created-at time-ms})})))
```

**Delivery is flat and declared-only.** The initial event context stages the framework base keys — `:db`, `:event`, `:rf.db/runtime`, `:rf.frame/id`, and the envelope's `:rf.cofx` recordable-coeffect map (the canonical complete record, always staged regardless of declarations) — **plus exactly the declared leaves** — recordable values read from the token's `:rf.cofx`, ambient values from running their suppliers at context assembly, each flat under its own id. A leaf on the token but **undeclared by this handler is not delivered as a flat key** (no silent green-in-test / nil-in-prod coupling; `handler-meta` becomes the complete record of the handler's *flat* consumption — though every recordable fact remains reachable through the always-staged `:rf.cofx` map, which is a framework context key, *not* a declarable coeffect supplier). **Declared-only governs this flat spread only; it never excludes the `:rf.cofx` record itself, which is staged exactly how `:event` is.** Ordinary app handlers SHOULD consume facts through their flat declared leaves, not by reaching into `(:rf.cofx coeffects)` — reading the whole record is *allowed* (and replay-safe, since it carries only facts already on the token) but **discouraged for app code**, because a fact taken off the record never lands in `:rf.cofx/requires` and so is invisible to `handler-meta` and tooling (declaration hygiene). Reading the whole record is for **generic / framework code** that needs the complete causal record. The *flat declared spread* carries no nested map — no `:cofx` key, no `:rf.world/inputs` successor, and no second flat copy of `:rf/time-ms` beyond the one its declaration delivers; the `:rf.cofx` envelope map is the one nested record, and is not a `:rf.world/inputs` successor. The satisfaction algorithm, the error family, and the registrar grades are owned by [001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded).

**Coeffects are context assembly, not chain members.** Operationally, satisfaction behaves like an implicit interceptor at the head of the chain; normatively it is the *construction of the chain's input*: envelope finalization → context assembly → `:before` pass → handler → `:after` pass. Consequences: there is no cofx ordering question (v1's wart — an early interceptor blind to a later injection — cannot be expressed); every interceptor observes the complete input; the chain stays homogeneous. Interceptors that *modify* coeffects remain legal as ordinary transformations of an assembled context. Generic code that wants the whole record (transition helpers, interceptors, the framework-internal `context -> context` primitive) reads the envelope's `:rf.cofx` map through the context — exactly how `:event` is reachable at both layers.

**Supplied values win.** Dispatch opts, replay fixtures, SSR hydration, and host integrations supply exact values via the `:rf.cofx` opt; the runtime fills only what is missing and never overwrites. A registration's `:schema` is thereby a *contract*, not merely a generation instruction — it is the type of the replay hole.

<a id="mint-policies"></a>

#### Mint policies

The **mint policy** governs **recordable generation only** (ambient suppliers run at context assembly in *every* mode — they are reads, not record entries). A generator-backed recordable fact (a `reg-cofx` carrying a value-returning supplier, not `:provided?`) that a handler declares but is absent from the token consults the policy:

| Mode | Behavior | Normative binding |
|---|---|---|
| `:live` | generate declared-absent recordable values at processing-start | the **router's default** (when no binding point selects otherwise) |
| `:strict` | required-but-absent is `:rf.error/missing-required-cofx`; no generator runs, no host reads | **hard-wired for replay** (Tool-Pair surface); the **default of the `:test` preset** ([§Frame presets](#frame-presets--capability-bundles-for-common-configurations)) |
| `:explicit-live` | generates, but the caller has *declared* it accepts nondeterminism | opt-in escape hatch in tests |

Replay is unconditionally strict — an incomplete record MUST fail loudly rather than silently re-read the host. A *provided* recordable fact (no generator) that is absent from the token is `:rf.error/missing-required-cofx` in **every** mode; `:rf/time-ms` always succeeds (the stamp guarantees it).

**Binding points.** The effective policy for a dispatch resolves **most-specific-wins**, at context assembly:

1. **Per-call** — the `:rf.cofx/mint-policy` **dispatch opt** (`(rf/dispatch [:e] {:rf.cofx/mint-policy :strict})`). This is the lever a **Tool-Pair replay** uses (it re-dispatches a recorded event supplying the recorded `:rf.cofx` *and* `:rf.cofx/mint-policy :strict`, so an incomplete record fails loudly instead of minting a fresh value — see [Tool-Pair §Replay](Tool-Pair.md#replay-mint-policy)), and the lever a test uses to opt back into generation (`:explicit-live`).
2. **Per-frame** — the `:rf.cofx/mint-policy` key on the frame's `reg-frame` / `make-frame` config (the `:test` preset expands to `:strict`).
3. **Default** — `:live`, the router default, when neither is present.

An **unrecognised** policy value is treated conservatively as **non-generating** (equivalent to `:strict`) — an unknown policy MUST NOT silently mint a nondeterministic value into the durable ledger. The policy threads through the satisfaction algorithm (the `deliver-declared-cofx` seam owned by [001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded)); it changes only the declared-absent generator-backed branch, never the present-on-token (supplied / replayed) or ambient branches.

#### `:dispatched-at` is retired

There is no `:dispatched-at` envelope field — one name per fact ([EP-0007](../docs/EP/EP-0007-one-name-per-fact.md)), per [EP-0010 disposition 5](../docs/EP/EP-0010-causal-world-inputs.md#open-issues). The *durable* causal-time fact is `(:rf/time-ms (:rf.cofx envelope))`, and the *diagnostic* dispatch-time need is the trace event's own `:time` stamp (Spec 009 — ambient). Durable code reads `:rf.cofx`.

`:dispatched-at`, like `:rf.world/inputs`, only ever named a fact in the spec's own **drafts** — it never shipped in a released artefact. Under the [shipped-names-only tombstone rule](Conventions.md#the-tombstone-rule--dedicated-retired-name-error-ids-only-for-shipped-names) a dedicated retired-name error id is reserved for names that *shipped* (and so carry wide training-data exposure that a stale generator might reproduce); a draft-era name earns none. So supplying `:dispatched-at` in a `dispatch` / `dispatch-sync` opts map is caught by the **generic** unrecognised-opt surface (`:rf.warning/unknown-dispatch-opt`) — the same closed-key rejection any misspelt opt receives — with a **did-you-mean** appended to the warning message that names `(:rf/time-ms (:rf.cofx envelope))` as the durable causal-time fact. There is no silent alias: the key is unrecognised, so the no-silent-swallow warning fires and the targeted hint keeps the fix actionable.

This is the whole cost of a draft-era rename: no per-key error id, no always-on production detection path each conforming port must implement, no catalogue row. The generic unknown-dispatch-opt warning already fails loud on any unrecognised opt; the retired-draft names simply ride it with a replacement suggestion. Names that *did* ship — the removed v1 registration API `reg-event-db` / `reg-event-fx` / `reg-event-ctx`, whose wide training-data exposure means a stale generator may still emit them — keep their dedicated permanent tombstone error ids; draft-only names do not, so tombstone accumulation stays bounded to the shipped surface.

## View ergonomics (the hard part)

> **Pattern vs. CLJS reference:**
>
> - **Pattern-level contract:** every dispatch and every subscribe carries an explicit frame identity. Views are pure `(state, props) → render-tree`; their dispatch/subscribe targets a specific frame. Callbacks created during render close over the frame by value at construction time.
> - **CLJS reference realisation:** React context carries the frame keyword through the component tree; `reg-view` reads it during render and injects frame-bound `dispatch`/`subscribe` as lexical locals so the *call site* doesn't need to thread the frame explicitly. This is an *ergonomic optimisation* atop the explicit-frame contract — observable behaviour is identical to passing the frame as a parameter, with less ceremony.
>
> Other-language implementations would resolve this with their own equivalents — function arguments, dependency injection, signals/observables, hooks-flavoured contexts. The pattern is satisfied as long as: (a) every dispatch/subscribe is associated with a specific frame at the point of call, and (b) callbacks created during render carry the frame they were rendered under, not whatever frame happens to be active when they fire.

### The problem (CLJS-specific framing)

A view inside a `frame-provider` for `:todo` writes:

```clojure
[:button {:on-click #(dispatch [:inc])} "+"]
```

The lambda is **constructed during render** but **invoked at click time** — long after render has unwound. Whatever mechanism re-frame uses to know "the surrounding frame is `:todo`" must survive that boundary.

The mechanisms available in CLJS:

- **React context** is read via `useContext`-like hooks — render-only. Gone by the time `:on-click` fires.
- **Clojure dynamic binding** (`*current-frame*`) — also render-only. Unwound when the binding form returns.
- **Closures** — survive arbitrarily. If render-time code captures the frame keyword into a closure, the callback that closes over that closure has the frame.

So the CLJS reference has to **convert render-time frame knowledge into a closure** that the callback closes over. The question is *who does the conversion*. (At the pattern level the answer is uninteresting: explicit-frame addressing means the call site already has the frame in scope as a value. The closure-conversion problem is an artefact of the React-context optimisation.)

### Resolution: `reg-view` is the boundary (CLJS reference)

`reg-view` is the registered, frame-aware view abstraction. Inside a registered view's body, `dispatch` and `subscribe` are **lexically bound locals** — closures pre-bound to the frame resolved from React context at render time. Callbacks that close over these locals automatically carry the frame.

```clojure
(rf/reg-view ^{:doc "A counter widget with isolated state."} counter [label]
  (let [n @(subscribe [:count])]                  ;; frame-bound subscribe
    [:button {:on-click #(dispatch [:inc])}        ;; frame-bound dispatch closed over
     (str label ": " n)]))
```

Naming convention: **unqualified `dispatch`/`subscribe` inside `reg-view` are the frame-bound locals.** Qualified `re-frame.core/dispatch` refers to the global function — its ambient 1-arity form resolves the carried-invariant scope/hold chain (and raises `:rf.error/no-frame-context` outside any scope, EP-0002 — there is no `:rf/default` default); the explicit `(rf/dispatch [...] {:frame :id})` form is the REPL/test shape.

This is the *implicit lexical injection* style chosen in 000 (the (α) option). It reads identically to today's re-frame view code. No env-arg change to view signatures.

### Pattern-level alternative: explicit-frame views

For comparison — what the same view looks like without the CLJS reference's lexical injection. This is what other in-scope JS-cross-compile-language implementations realise (TypeScript, Fable (F#), Scala.js, PureScript, Kotlin/JS, Melange / ReScript / Reason, Squint) and what JVM-side test code can opt into:

```clojure
;; pattern-level shape: frame is an explicit parameter; dispatch/subscribe take a frame argument
(defn render-counter [{:keys [frame label]}]
  (let [n @(rf/subscribe [:count] {:frame frame})]
    [:button {:on-click #(rf/dispatch [:inc] {:frame frame})}
     (str label ": " n)]))
```

Both shapes satisfy the contract: a view *does* render against an explicit frame; the frame *does* travel with each dispatch and subscribe; callbacks created during render *do* carry the frame they were rendered under. The CLJS reference's lexical injection is sugar over this shape — observable behaviour is identical.

A non-CLJS implementation might use:
- **TypeScript-React with hooks:** `const dispatch = useDispatch(); const value = useSubscribe(['count']);` — `useDispatch`/`useSubscribe` read frame from a `React.createContext` value.
- **Fable (F#) with Feliz / Fable.React hooks:** `let dispatch = useDispatch() in let value = useSubscribe ["count"] in …` — same React-context shape, F# syntax.
- **PureScript with React.Basic.Hooks:** `do dispatch <- useDispatch; value <- useSubscribe ["count"]; …` — same React-context shape, PureScript syntax.
- **Kotlin/JS with kotlin-react:** `val dispatch = useDispatch(); val value = useSubscribe(arrayOf("count"))` — same React-context shape.

The point: the *pattern* is "every dispatch/subscribe targets a specific frame"; the *implementation* chooses how the frame is plumbed.

**Conformance obligation (non-CLJS hosts).** The list above is illustrative; the *normative* contract is what every conformant implementation MUST provide. A non-CLJS host MUST satisfy two conditions: (a) every dispatch and every subscribe in a view's body resolves to the frame the view was rendered under (whatever mechanism — explicit parameter, dependency injection, hooks, signal context — the host picks), and (b) closures or callbacks created during render carry the frame *captured at render time*, not whatever frame happens to be active at fire time. The CLJS reference satisfies (a) via React-context-driven lexical injection and (b) via closure-capture in `reg-view`'s injected locals; other hosts satisfy them however their substrate allows. An implementation that fails (a) routes dispatches to the wrong frame; one that fails (b) leaks state across frames when callbacks fire after render unwinds.

### What `reg-view` injects

On each invocation, the macro wraps the user's render fn in a `let` that binds three names from the current frame keyword (resolved via `read-frame-from-context`, below):

- `dispatch` — frame-bound closure building an envelope tagged with the surrounding frame's id.
- `subscribe` — frame-bound closure consulting the surrounding frame's sub-cache.
- `frame-id` — the keyword itself.

The user's body runs inside that `let`. The full API surface (worked example, the registration shape, Form-1/2/3 handling, Var-style invocation) is documented in [004-Views.md](004-Views.md).

### Reading the frame from React context (CLJS implementation detail)

Everything in this subsection is **CLJS-implementation detail**, not pattern contract. The pattern requires only that views render with an explicit frame identity; how that identity is plumbed through is implementation-specific.

The `read-frame-from-context` function is implemented as a tiered lookup: the dynamic-binding tier, then the React-context read, **bottoming out at nil** (no scope). The middle tier — the React-context read — is **substrate-specific** and each adapter publishes its own impl through the `:adapter/current-frame` late-bind hook (per [006 §Frame-provider via React context](006-ReactiveSubstrate.md#frame-provider-via-react-context)). The dynamic-var tier is shared. **There is no `:rf/default` tier** (EP-0002): the reader returns nil when no scope names a frame, and the public frame-scoped operation turns that nil into `:rf.error/no-frame-context` via `require-current-frame!`.

```clojure
;; The createContext default is the NO-PROVIDER SENTINEL, not :rf/default —
;; absence of a provider must be detectable as absence (per EP-0002), so the
;; read tier returns nil and the resolver fails loudly rather than synthesise
;; a default. (See [§What `frame-provider` is].)
(defonce ^:private frame-context
  (.createContext js/React ::no-provider))

;; Reagent (class components, `:contextType` machinery)
(defn- read-frame-from-context-reagent []
  (or *current-frame*                                ;; tier: dynamic var (set by `with-frame`)
      (when-let [cmp (reagent.core/current-component)]
        (let [ctx (.-context cmp)]                   ;; tier: closest enclosing `frame-provider`
          (when-not (= ctx ::no-provider)            ;; — class-component path: surfaces value
            (cond                                    ;;   only to components whose `:contextType`
              (keyword? ctx)                  ctx    ;;   matches. A plain fn lacks the wiring, so
              (and (string? ctx) (not= "" ctx)) (keyword ctx)))))))
                                                     ;; tier: nil (no scope — NOT :rf/default)

;; UIx / Helix (function components, hook-driven)
(defn- read-frame-from-context-fn-component []
  (or *current-frame*                                ;; tier: dynamic var
      (let [v (.-_currentValue frame-context)]       ;; tier: function-component path —
        (cond                                        ;;   `_currentValue` is what React mutates
          (= v ::no-provider)           nil          ;;   as Provider boundaries are entered /
          (keyword? v)                  v            ;;   exited during render. No enclosing
          (and (string? v) (not= "" v)) (keyword v) ;;   Provider → the sentinel → nil.
          :else                         (do (emit-frame-context-corrupted! v) nil)))))
                                                     ;; tier: nil (no scope — NOT :rf/default)
```

How the React-context tier wires up:

1. `frame-provider` is a React Context Provider whose `value` is the **keyword** (`:todo`), not a frame record. The shared context object lives in `re-frame.adapter.context/frame-context`; every adapter (Reagent, UIx, Helix) reads and writes the same `createContext` object, so a tree mixing substrates resolves to a single frame chain.
2. `subscribe` and `dispatch` reach the resolution chain through the `:adapter/current-frame` late-bind hook. The active adapter's namespace registers the hook at load time, so `re-frame.subs` / `re-frame.router` (CLJC) stay free of a static dep on this CLJS-only file.
3. **Reagent's class-component path** (`(.-context cmp)`) is narrow: Reagent's class-component machinery surfaces context only to components whose `:contextType` matches the context object — that is the wiring `reg-view*` attaches via `{:contextType frame-context}`. Plain Reagent fns lack the `:contextType`, so their `(.-context cmp)` is the no-provider sentinel — the reader returns **nil** (no scope), and a public frame-scoped op then raises `:rf.error/no-frame-context` (EP-0002). This narrowness is what makes the plain-fn footgun a *loud error* rather than a silent wrong-frame read (per [004 §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-the-footgun-is-now-a-loud-error)).
4. **UIx / Helix's function-component path** (`_currentValue`) reflects the closest enclosing Provider regardless of any class-static metadata, because function components have no `(.-context cmp)` slot. UIx's `use-context` and Helix's `use-context` are both sugar over this read, so subscribe / dispatch and the substrate-native hook agree on the active frame.

The context's value is the **keyword**, not the frame record: each consumer resolves the keyword against the global frame registry on every read, so re-registering a frame (including a registered `:rf/default`) is picked up automatically on next render with no React-side invalidation.

#### Edge cases

- **No `frame-provider` in scope.** Reagent's `(.-context cmp)` returns the no-provider sentinel; the reader returns nil (no scope). Function-component substrates read `_currentValue` directly, which equals the createContext sentinel default → nil. Either way a public frame-scoped op raises `:rf.error/no-frame-context` — there is no `:rf/default` fall-through (EP-0002).
- **Non-keyword `:frame` on the public provider.** Frame ids are keywords ("keyword in"), so a non-nil but non-keyword `:frame` (a string `{:frame "app"}`, a number, …) is a *bad public provider argument*, not a carried frame. The public Reagent / UIx / Helix / shared-spine provider entry points reject it BEFORE writing React Context, emitting the distinct `:rf.error/bad-frame-provider-arg` (recovery `:supply-keyword-frame`) and throwing — separate from both absence (`:rf.error/no-frame-context`) and a disturbed reader-side read (`:rf.error/frame-context-corrupted`). Without this guard the reader's prop-stringified-keyword coercion (next bullet) would silently round `{:frame "app"}` to `:app` and scope descendants to a registered `:app` frame. The reader-side coercion is cover for raw `[:> Provider …]` hiccup mounts only — the public surfaces never write a non-keyword value.
- **Render fn invoked outside Reagent** (REPL, tests). `reagent.core/current-component` returns `nil`; the React-context tier is skipped. `with-frame` (or an explicit `{:frame …}` opt) covers tests that need a frame; a bare invocation with no scope raises `:rf.error/no-frame-context`.
- **Reagent prop-conversion of named values**. Stock Reagent's `convert-prop-value` (`reagent.impl.template`) stringifies named values when they pass as React props. The canonical user-facing surface (`rf/frame-provider`) sidesteps this by mounting the Provider via Reagent's `:r>` interop head — the props map flows to React as a raw JS object, so `:value :foo/bar` reaches React as the original keyword and the namespace is preserved across the React-context round trip on every adapter. A user who writes `[:> (.-Provider frame-context) {:value :foo}]` directly (raw `:>` interop, not `rf/frame-provider`) still passes through stock Reagent's prop-conversion under the classic adapter: `convert-prop-value` rewrites `:foo` to `"foo"`, and `re-frame.adapter.context/coerce-context-value` rounds the string back to a keyword. Note that `(name kw)` is lossy for namespaced keywords (`(name :auth/main)` → `"main"`); raw-hiccup mounts that need a namespaced frame-id should switch to `rf/frame-provider` or `re-frame.adapter.context/provider-element`.
- **Concurrent rendering.** React 19 (and the React 18 concurrent renderer before it) may render the same component multiple times before commit. The context read is idempotent — same provider value across re-renders — so this is safe. Closures captured during render hold the keyword by value; re-render produces a new closure with the same keyword. See [§Open questions — Concurrent React rendering](#concurrent-react-rendering).

### View-side details — see Spec 004

Form-1/2/3 component handling, plain Reagent fns and the `capture-frame` affordance, and composing registered views across nested `frame-provider`s — all live in [004-Views.md](004-Views.md). 002 owns the frame-side mechanics; 004 owns the view registration surface.

### The multi-frame surface — choose by intent

The frame affordances are organised by **what you are trying to do**, not by mechanism (a front-porch / back-room split). They are the three carried-invariant intents of [§Frame target resolution](#frame-target-resolution--the-carried-invariant) plus the read surface:

- **Ambient (inside an established scope):** `dispatch`, `dispatch-sync`, `subscribe` — resolve the active frame from the surrounding *scope*. They require a scope: with none established, they fail with `:rf.error/no-frame-context` (they do not select a default).
- **Scope** — establish context for an **existing** frame (no lifecycle): `with-frame` (pin to an existing frame in a lexical/non-React region; synchronous, evaporates at an async hop) and `frame-provider {:frame …}` (scope an *existing* frame into a React subtree — the scope-into-React counterpart `with-frame` cannot serve because a dynamic var cannot cross React's render boundary; fails loud if the frame is absent).
- **Ensure** — create-if-absent + reuse-if-present (no re-seed), no teardown: `frame-provider {:id …}` (the merged provider's ENSURE shape — ensures a named frame for as long as the subtree is mounted; durable state and `:initial-events` survive a remount; **no destroy-on-unmount**).
- **Own** — create + destroy a frame lifetime (explicit teardown): `make-frame` + `destroy-frame!`, `with-new-frame` (create + bind + destroy on block exit), or `make-frame` + `destroy-frame!` inside a `create-class` for a view that owns a frame's lifetime. (Teardown is always explicit; there is no owned `frame-provider` destroy-on-unmount.)
- **Hold (carry a frame's ops as a value, across async):** `capture-frame` (the public carry primitive). The robust carrier — the **primary** model for async and tooling. (`frame-bound-fn` / `frame-bound-fn*` are internal namespaces — `capture-frame` is the public carry surface; see [§frame-bound-fn](#frame-bound-fn--frame-bound-fn--frame-capturing-closures-cljs-reference).)
- **Override:** the `{:frame …}` opt — first-class explicit routing for tools / tests / SSR / fx handlers.
- **Reads:** `app-db-value`, `runtime-db-value`, `frame-state-value`, `current-frame-id`, `frame-value->id` (value → id accessor), `snapshot-of`, `frame-ids`, `frame-meta`.

**`scope` vs `ensure` vs `own` vs `carry` are separate jobs, one spelling each:**

| Job | Public spelling | Contract |
|---|---|---|
| **Scope** descendants to an **existing** frame (lexical / non-React) | `with-frame` | Does not create or destroy the frame. Establishes context only. |
| **Scope** an **existing** frame into a React subtree | `frame-provider {:frame …}` | Provides an already-created frame id through React context. Creates / refreshes / destroys nothing. `:frame` only. **Fails loud when the frame is absent** (`:rf.error/frame-provider-frame-absent`). |
| **Ensure** a named frame for a React subtree's mounted lifetime | `frame-provider {:id …}` | Creates the frame if absent, reuses it without re-seeding if present, provides its id to descendants. **No destroy-on-unmount.** Same constructor opts as `make-frame`. |
| **Carry** a frame across async callback boundaries | `capture-frame` | Captures operations targeted at the current or explicit frame. |
| **Own** a frame lifetime (explicit teardown) | `make-frame` + `destroy-frame!`, `with-new-frame`, or `make-frame`/`destroy-frame!` inside a `create-class` | Creation and teardown are explicit ownership operations. |

The user's question stays small: *"I already have a frame; how do I scope children?"* → `with-frame` (lexical) or `frame-provider {:frame …}` (into a React subtree). *"This subtree needs a named frame to exist while it's mounted."* → `frame-provider {:id …}` (ENSURE). *"This component owns a frame lifetime, teardown included."* → `make-frame` + `destroy-frame!` (in a `create-class`). *"This callback will fire later."* → `capture-frame`. (Per [EP-0024 §Scope, carry, and ownership are separate](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md#scope-carry-and-ownership-are-separate). The merged `frame-provider` serves both the scope job (`{:frame …}`) and the ensure job (`{:id …}`); teardown is explicit.)

### `capture-frame` — the keystone affordance (CLJS reference)

Ambient *scope* lookup (dynamic var → React context) does **not** survive async boundaries — and there is no `:rf/default` floor underneath it, so a bare ambient call after the scope unwinds fails rather than silently targeting a default. `capture-frame` is the answer — the *hold* primitive: it captures the frame at CREATION time and returns an OPERATION BUNDLE whose ops always target the captured frame.

```clojure
(rf/capture-frame)            ;; capture the ambient frame (current-frame-id)
(rf/capture-frame :rf/xray)   ;; bundle locked to an explicit frame-id
;; =>
{:frame         <id>
 :dispatch      (fn ([event] [event opts]))
 :dispatch-sync (fn ([event] [event opts]))
 :subscribe     (fn [query-v])}
```

- The frame is captured at CREATION; every op targets the captured frame and survives async (`setTimeout`, `Promise.then`, websocket `onmessage`, observer callbacks).
- A per-call `:frame` in the dispatch opts MUST NOT override the captured frame — the handle is **locked** to one frame.
- It is an OPERATION BUNDLE, not a container: read the frame's app-db value via `(rf/app-db-value (:frame handle))`, not the handle itself.

```clojure
(rf/reg-view StreamView [_]
  (let [{:keys [dispatch]} (rf/capture-frame)]   ;; captures the render frame
    (ws/subscribe! (fn [msg] (dispatch [:ws/incoming msg])))
    [:div "streaming…"]))
```

### `frame-bound-fn` / `frame-bound-fn*` — frame-capturing closures (CLJS reference)

> **Internal carry helpers.** `capture-frame` is the **one public carry primitive**. `frame-bound-fn`, `frame-bound-fn*`, the lower-level `make-capture-frame`, the frame-first operation arities, and `subscribe*` are **internal namespaces** — the mechanism below is used by implementation code, not app-facing carry API. Author async and tooling paths with `capture-frame` or an explicit `{:frame …}` opt.

Sometimes the value you must carry across the boundary isn't a dispatch/subscribe op but an arbitrary fn that itself re-establishes the frame for its body — an async result handler set up inside `r/with-let`, an interval handle, a fn that calls `current-frame-id` internally. `frame-bound-fn` (the macro, `fn`-syntax) and `frame-bound-fn*` (the `*`-twin fn, for wrapping an existing fn value) cover this internally:

```clojure
;; Macro form — convenient `(fn ...)` syntax built in.
(rf/frame-bound-fn [msg]
  (rf/dispatch [:incoming msg]))          ;; closure carries the captured frame

;; *-twin fn form — wrap an existing fn (or one returned by another helper / lib).
(rf/frame-bound-fn*
  (fn [msg] (rf/dispatch [:incoming msg])))

;; *-twin fn form with explicit frame-id — no surrounding `with-frame` / provider
;; needed at wrap time. Useful at module top level, in install! routines.
(rf/frame-bound-fn* :rf/xray
  (fn [_e mode] (rf/dispatch [:set-mode mode])))
```

Both produce a fn that, when called, runs in a `binding [*current-frame* <captured-frame>]` block — `*current-frame*` is the dynamic-binding tier of the resolution chain (above), so plain `dispatch`/`subscribe` inside the wrapped body pick up the right frame regardless of when the call fires. For the common dispatch / subscribe case prefer `capture-frame`; reach for `frame-bound-fn` / `frame-bound-fn*` when you need to re-establish the dynamic binding around an arbitrary fn body.

The dynamic var (`*current-frame*`) is the primary mechanism for `frame-bound-fn` / `frame-bound-fn*`, `with-frame`, and the router's per-handler binding: these constructs use the dynamic-binding tier as their definition, so synchronous dispatches inside their bodies pick up the right frame without an explicit `:frame` opt at the call site.

#### React click-handler routing — the canonical pattern

A React onClick / onKeyDown / onChange callback is built during render but fires LATER, on a fresh JS turn after React has popped its render commit. Whatever mechanism a view uses to know "the surrounding frame is `:rf/xray`" must survive that boundary. Four routing patterns satisfy the contract — each captures the frame at a synchronous moment when it's still resolvable, so the click-time dispatch carries the right frame regardless of when React invokes it:

| Pattern | Where the frame is captured | Best for |
|---|---|---|
| `(rf/capture-frame)` / `(rf/capture-frame frame-id)` | creation time | the common case — `:dispatch` / `:subscribe` ops handed to a callback or async library |
| `(rf/frame-bound-fn* frame-id f)` | wrap-time, explicit frame-id | top-level utilities (install! routines, module helpers); wrapping a fn whose body re-establishes the frame |
| `(rf/frame-bound-fn [args] body)` | lex-binding moment | when you want `(fn ...)` syntax and frame-capture in one form |
| `(rf/dispatch [...] {:frame :id})` | dispatch call time, explicit envelope | one-off dispatches where wrapping the whole callback would be heavier than threading a single opt |

All four feed `:frame` into the dispatch envelope synchronously (during the capture window). The router queue carries `:frame` on the envelope through the microtask boundary — the drain reads frame off the envelope, never re-resolves the dynamic var at drain time — so the dispatch is routed correctly even after React has popped its render and unwound the binding.

What does NOT survive: a raw `(rf/dispatch [...])` from inside a React click handler where the frame is not explicitly captured at the call site. The dynamic var is gone, the React-context tier reads through `current-component` which is nil outside render, so the resolution returns nil and the dispatch raises `:rf.error/no-frame-context` (EP-0002 — there is no `:rf/default` fall-through to silently absorb it). The "I'm running under a `frame-provider`" knowledge is render-only — converting it to closure-bound state is the wrap step every robust callback takes, and the loud error is what forces it.

Example (Xray's HANDLER `:db` view-mode toggle, the bead's bug-class instance):

```clojure
(rf/reg-view DbViewModeToggle [mode]
  (let [{:keys [dispatch]} (rf/capture-frame)]   ;; captures the render frame
    [:span
     (for [m [:diff :all]]
       ^{:key (name m)}
       [:button {:on-click (fn [e]
                             (.stopPropagation e)
                             (dispatch [:rf.xray.epoch/set-db-view-mode m]))}
        (name m)])]))
```

Without a captured handle, every dispatch site needs `{:frame :rf/xray}` opt explicitly — verbose at every callsite, and brittle in any case where `*current-frame*` is genuinely lost across an async boundary that fires after the surrounding `with-frame` / `frame-provider` has unwound. The handle captures the frame at creation time and locks its ops to it: the callback ALWAYS dispatches in the captured frame, regardless of how many dispatches happen inside it or how deep the async / call chain goes.

> **See also**: [006 §Lazy-seq deref tracking (Reagent adapter)](006-ReactiveSubstrate.md#lazy-seq-deref-tracking-reagent-adapter) for an adjacent but DIFFERENT bug class — "view doesn't update on click" that looks superficially like "frame lost across React onClick" but is actually a Reagent reactive-tracking failure. Reach for `capture-frame` / `frame-bound-fn` when you have a genuine async-boundary case (timer, promise, websocket); reach for `doall` / `mapv` when a `(for …)` in a `reg-view` body holds the deref. The two failure modes are not interchangeable.

#### Other async callbacks (timers, promises, websocket messages)

For non-React async callbacks — `setTimeout`, `setInterval`, `Promise.then`, websocket `onmessage`, intersection-observer callbacks, and **raw `window.addEventListener` handlers** (e.g. a drag flow that registers `pointermove` / `pointerup` on `window` during an `:on-pointer-down`, so the move/up handlers fire OUTSIDE the React tree after render has unwound) — the same `capture-frame` / `frame-bound-fn` pattern applies. Capture the frame at render time (inside the `reg-view` body, via the injected `dispatch` / `subscribe` or `(rf/capture-frame)`) and thread the captured ops into the listener — never a bare global `rf/dispatch` and never a hardcoded `{:frame :id}` literal (a literal silently locks every instance to one frame). Alternative affordances:

- **`(rf/capture-frame)`** — captures the frame at creation and returns `{:frame :dispatch :dispatch-sync :subscribe}`. Build inside a render body or under `with-frame`, store the handle, invoke its ops from any later async context.
- **`:fx [[:dispatch ...]]`** — the canonical pattern for handler-emitted dispatches; the fx-walker threads the frame through automatically.
- **`:fx [[:dispatch-later ...]]`** — closure-captured frame, survives the timer.

All five (`capture-frame`, `frame-bound-fn`, `frame-bound-fn*`, `:dispatch`, `:dispatch-later`) share the same shape: render/handler-time capture of the frame as a closure value, which then rides through to call time. The bare-dispatch-from-an-async-callback case is the *only* one where the scope has unwound with no carried stamp — and under [§Frame target resolution](#frame-target-resolution--the-carried-invariant) it **fails loudly** with `:rf.error/no-frame-context` rather than falling through to a default. There is no `:rf/default` fall-through and no tolerated-but-warned outcome.

### Subscriptions composing across the signal graph

`reg-sub` is the **only** sub-registration form in v2. The v1 `reg-sub-raw` escape hatch is not shipped (per [MIGRATION §M-18](../migration/from-re-frame-v1/README.md)); the use cases it covered now have explicit answers in the architecture: non-app-db sources route through Pattern-AsyncEffect and registered fx, lifecycle-bearing reactive computations become state machines (per [005](005-StateMachines.md)), and bridging external reactive sources is the [006](006-ReactiveSubstrate.md) adapter contract's job.

Subs can compose via `:<-`. All composition stays within a single frame's sub-cache and `app-db`:

```clojure
(rf/reg-sub :all-todos
  (fn [db _] (:items db)))

(rf/reg-sub :pending
  :<- [:all-todos]
  (fn [items _] (filter pending? items)))
```

When a view in frame `:todo` derefs `[:pending]`:

1. The frame-bound `subscribe` resolves `[:pending]` against `:todo`'s sub-cache.
2. The cache, on miss, builds the reactive chain — `[:all-todos]` is also resolved within `:todo`.
3. Both reactives close over `:todo`'s `app-db`.
4. A different frame `:other` has its own independent chain.

The signal graph is therefore per-frame. Sub-caches do not leak across frames, even though the *handler functions* (the registered `(fn [db _] ...)` bodies) are shared globally.

### Async effects and frame propagation

> The canonical "register fx → return `:fx` → post work → async reply → dispatch → commit" shape that every async-effecting feature follows is named in [Pattern-AsyncEffect](Pattern-AsyncEffect.md). This section specifies the frame-routing rule that makes the shape work across multiple frames.

The trickiest correctness question. Consider:

```clojure
(rf/reg-event :load-todo
  (fn [{:keys [db]} _]
    {:fx [[:my-app/http {:url "/todo/1"
                         :on-success [:todo-loaded]}]]}))
```

When `:load-todo` is dispatched in frame `:todo`, the `:my-app/http` effect fires (`:my-app/http` here is a placeholder for a user-supplied fx; the framework ships `:rf.http/managed` — see [014-HTTPRequests](014-HTTPRequests.md)). Some time later, the HTTP machinery dispatches `[:todo-loaded ...]`. **It must dispatch into `:todo`, not `:rf/default`** — otherwise the response lands in the wrong app-db.

The mechanism is symmetric with how event handlers receive their context: **an fx handler receives the frame id in its ctx `m` under `:frame`** — the same frame the originating event ran in, carried as explicit data. Routing follows from explicit data, not implicit state. (The fx ctx is a small map distinct from the event handler's coeffects map — see [§The binary fx-handler signature](#the-binary-fx-handler-signature).)

#### The binary fx-handler signature

`reg-fx`'s primary signature in re-frame2 is binary:

```clojure
;; re-frame2's standard :dispatch fx, frame-aware
(reg-fx :dispatch
  (fn [m event]
    (rf/dispatch event {:frame (:frame m)})))

;; multiple dispatches are expressed via :fx (nested pairs); the v1 :dispatch-n top-level key is gone
;; e.g., handler returns:
;;   {:fx [[:dispatch [:event-1]]
;;         [:dispatch [:event-2]]]}
```

`m` is the fx-handler ctx — a **small map distinct from the event handler's coeffects map**: it carries `:frame` (the active frame **id** — a keyword, never the live record), `:event` (the originating event vector), and a runtime-internal `:envelope` (the parent dispatch envelope). It does **not** carry the event handler's `:db` / `:rf.cofx` / declared-flat coeffects — an fx reaches app-db only through a dispatched event. Through `(:envelope m)` a user fx MAY observe the envelope's `:trace-id` / `:origin` / `:source`, but SHOULD NOT depend on the slot. The canonical shape of both maps is pinned in [Spec-Schemas §`:rf/handler-context`](Spec-Schemas.md#rfhandler-context-the-map-handlers-receive--event-context-and-fx-handler-ctx). fx handlers ignore the keys they don't care about.

For sync fx that dispatch (or otherwise need to know the frame), the pattern is `(rf/dispatch event {:frame (:frame m)})` — `(:frame m)` is the frame **id**, fed straight into the public `:frame` opt (schema `:keyword`, per [§`:rf/dispatch-opts`](Spec-Schemas.md#rfdispatch-opts)).

The runtime needs to resolve the frame **record** (for `:fx-overrides`, and so reserved fxs can queue children — `:dispatch`, `:dispatch-later`, per [§Cascade propagation](#cascade-propagation)) and to thread the originating envelope through to those reserved fxs. It resolves the record **from the id** at the do-fx choke point (an O(1) registry lookup — the registry is the source of truth for live frames); the record itself never rides in `m`. The parent envelope is available at `(:envelope m)` for reserved-fx implementations. User fxs typically read only `(:frame m)`; the `(:envelope m)` slot is a runtime-internal handle that the four reserved fx defmethods consume — see [§Drain-loop pseudocode](#drain-loop-pseudocode).

#### Async fx capture the frame in a closure

When the actual dispatching happens after the fx handler has returned (HTTP callback, websocket message, timer, deferred promise), the fx handler captures `(:frame m)` into the closure that fires later:

```clojure
(reg-fx :my-app/http
  (fn [m {:keys [url on-success on-failure]}]
    (let [frame (:frame m)]
      (-> (js/fetch url)
          (.then  #(rf/dispatch on-success {:frame frame}))
          (.catch #(rf/dispatch on-failure {:frame frame}))))))
```

A closure over `(:frame m)` keeps each call site terse:

```clojure
(reg-fx :my-app/http
  (fn [m {:keys [url on-success on-failure]}]
    (let [frame (:frame m)
          d     (fn [ev] (rf/dispatch ev {:frame frame}))]
      (-> (js/fetch url)
          (.then  #(d on-success))
          (.catch #(d on-failure))))))
```

#### What library authors of async fx have to know

- **Update to binary signature** when targeting re-frame2 multi-frame.
- **Read `(:frame m)` once** at handler entry; pass it into closures.
- **Pass `:frame` explicitly** in callbacks — `(rf/dispatch ev {:frame frame})` — or capture a frame-locked dispatch op via `(:dispatch (rf/capture-frame))` inside the binary handler body (where `*current-frame*` is bound to `(:frame m)`). Don't rely on plain `dispatch` in callbacks; the binding is gone.

`(rf/capture-frame)` (capture-at-creation, used in fx and views) and `frame-bound-fn` (the macro form, used in view callbacks) are the same idea applied at different boundaries: capture the frame at definition time, re-establish it when the closure fires.

### The merged config-shaped `frame-provider` (CLJS reference)

> **The merged config-shaped `frame-provider`.** `rf/frame-provider` is **ONE config-shaped** component with two shapes dispatched on the prop map; there is **no owned destroy-on-unmount**:
>
> - **`rf/frame-provider {:frame existing-id}`** — **SCOPE-only.** Provides an **already-created** frame id through React context and creates / refreshes / destroys nothing. **Fails loud when the frame is absent** (`:rf.error/frame-provider-frame-absent`) — the guardrail Story + Xray rely on. `:frame` must be a keyword; a missing `:frame` is `:rf.error/no-frame-context`, a non-keyword `:frame` is `:rf.error/bad-frame-provider-arg`.
> - **`rf/frame-provider {:id the-id …}`** — **ENSURE.** Creates the frame if absent (via `make-frame`), **reuses it WITHOUT re-seeding if present**, and provides its id to descendants. There is **NO destroy-on-unmount**. It takes the **same constructor opts as `make-frame`** (`:id` / `:images` / `:initial-events` / `:url-bound?` / record-config). `:id` is required and must be a keyword (a missing/non-keyword `:id` → `:rf.error/ensure-frame-provider-missing-id`).
>
> The shape is selected by the presence of `:frame` vs `:id`. `with-frame` binds a dynamic var, which **cannot cross React's render boundary**, so scope-into-React uses a React-context component; `with-frame` is for lexical / non-React ambient scoping.
>
> **True ownership is explicit, not the provider's job.** The lifetime-owning use case (modals, multi-instance widgets) is expressible as `rf/make-frame` + `rf/destroy-frame!` inside a `create-class` (where the component declares it owns the lifetime). The merged provider serves the two non-owning jobs every product call site needs: scope an existing frame, and ensure a named frame.
>
> There is no separate `frame-provider-existing` and no namespace-safe twin. The merged `rf/frame-provider {:frame …}` shape is the one scope-only surface (its `:r>` interop head preserves a namespaced frame keyword across the React-context round trip).

**SCOPE-only shape.** Scope a React subtree to a frame that already exists (Story canvas mounting an app's frame, an Xray panel reading a tool frame, a child subtree reusing the parent's frame):

```clojure
;; The frame :rf/xray was created elsewhere (e.g. at shell mount); the
;; provider only SCOPES the panel subtree to it — no lifecycle. A
;; never-created / destroyed :rf/xray fails loud (:rf.error/frame-provider-frame-absent).
[rf/frame-provider {:frame :rf/xray}
 [panel-a]
 [panel-b]]
```

The SCOPE-only shape is the **scope-into-React** counterpart to `rf/with-frame`. `with-frame` binds a dynamic var, so it serves a lexical / non-React region but **cannot cross React's render boundary** (a descendant component renders after the `with-frame` form has returned, so the dynamic binding is already gone). `rf/frame-provider {:frame …}` carries the scope down through React context, which descendant renders read. `:frame` must be a keyword frame id (EP-0002 carried invariant): a missing `:frame` is `:rf.error/no-frame-context` (no `:rf/default` floor), a non-nil non-keyword `:frame` is the distinct `:rf.error/bad-frame-provider-arg`.

**ENSURE shape.** Ensure a named frame exists for as long as the subtree is mounted — create it the first time, reuse it on every subsequent mount/remount without re-running its setup. This is the answer for comparison pages, Story canvases, embedded widgets, and hot-reload-safe view-driven frame lifetimes:

```clojure
[rf/frame-provider {:id :todo/left
                    :images [todo-image]
                    :initial-events [[:rf/set-db {}]]}
 [todo-root]]
```

The ENSURE shape **creates the frame if absent** (via `make-frame`), **reuses it without re-seeding if present**, and **provides its frame id to descendants** (so `reg-view`-registered children resolve to it at render time without threading `{:frame …}`). There is **no destroy-on-unmount** — a genuine unmount leaves the frame live. Lifecycle teardown, when wanted, is an explicit ownership operation (`destroy-frame!`), not a side effect of the provider unmounting.

**`:initial-events` runs once per frame-id lifetime.** The ENSURE shape dispatches the recorded `:initial-events` only on the **first creation** of a frame id. On a genuine **remount / re-acquire** under the same id (React StrictMode, a true unmount→mount, hot reload, Story re-eval) the setup is re-recorded but **not** replayed, and durable state (app-db, sub-cache, queue) is preserved (idempotent re-registration); an ordinary prop-change re-render does not re-call `make-frame` at all, so it neither re-records nor replays. A setup step that throws during ensure **destroys the just-created frame, then rethrows**. Because provider setup runs during React render, keep it **effect-light** (seed app-db, light init), and drive heavier side-effecting init from app-db state via the view (per [EP-0027 §Frame provider](../docs/EP/EP-0027-frame-initial-events.md)).

**`frame-provider` is realized per-adapter, against a shared contract.** It is **not** a single component: each substrate (Reagent / UIx / Helix) ships its own `frame-provider` that dispatches on the prop shape and hooks the substrate's native render. What this spec fixes is the **shared contract** every adapter realisation MUST satisfy:

- **dispatch on shape** — `{:frame …}` selects SCOPE-only; otherwise the ENSURE `:id` shape applies;
- **SCOPE-only: provide-or-fail** — provide the named frame's id through the host's React-context primitive; **fail loud** (`:rf.error/frame-provider-frame-absent`) when no live frame is registered under that id;
- **ENSURE: ensure-on-render** — `make-frame` runs during render (idempotent), so a descendant that subscribes/dispatches on first render observes a live frame; the resolved id is cached so re-renders reuse it and the context `:value` is stable;
- **ENSURE: reuse-without-reseed** — re-mounting under the same `:id` is **idempotent replacement** (per [§Duplicate id](#duplicate-id--idempotent-replacement)), not destroy-then-recreate: durable app-db / runtime-db / sub-cache / queue survive, and `:initial-events` are re-recorded but **not** replayed;
- **no destroy-on-unmount** — the ENSURE shape arms **no** teardown effect; a genuine unmount leaves the frame live.

Each adapter's ENSURE realisation MUST tolerate its substrate's render concerns against that contract:

- **StrictMode double-invoke in dev** (mount → unmount → mount) — the render-phase `make-frame` is idempotent, so the extra cycle neither double-creates nor corrupts durable state, and (there being no destroy effect) nothing tears the frame down between the two mounts;
- **hot-reload-under-the-same-id** — re-mount is idempotent replacement, so durable app-db / runtime-db survive a recompile and the re-seeding `:initial-events` of the recompiled form are re-recorded but not replayed;
- **concurrent rendering** — the cached resolved id keeps the provider's context `:value` stable across React's re-renders.

Implementation skeleton (Reagent flavour — dispatch on shape; ENSURE creates-on-render with no teardown):

```clojure
;; The React-context default is a NO-PROVIDER SENTINEL, not :rf/default —
;; absence of a provider must be detectable as absence (per EP-0002), so the
;; read tier can return nil and the resolver can fail loudly rather than
;; synthesise a default. See [§Frame target resolution].
(defonce ^:private frame-context (js/React.createContext ::no-frame))

(defn frame-provider [props & children]
  (if (contains? props :frame)
    ;; SCOPE-only: provide an EXISTING frame's id; fail loud if absent.
    (let [id (:frame props)]
      (when-not (rf/frame id)
        (throw (ex-info ":rf.error/frame-provider-frame-absent" {:frame id})))
      (into [:r> (.-Provider frame-context) #js {:value id}] children))
    ;; ENSURE: create-if-absent / reuse-no-reseed; NO destroy-on-unmount.
    ;; `make-frame` runs on every render but is idempotent replacement, so a
    ;; remount reuses the live frame (durable state preserved, :initial-events
    ;; NOT replayed). `:r>` bypasses Reagent's `convert-prop-value`, preserving
    ;; namespaced frame keywords (`:tenant/admin`) the way `(name kw)` would strip.
    (let [id (rf/frame-value->id (rf/make-frame props))]
      (into [:r> (.-Provider frame-context) #js {:value id}] children))))
```

A SCOPE-only provider naming an absent frame, or an ENSURE provider whose opts establish no usable frame (an empty props map with neither `:frame` nor `:id`), is a configuration error — it fails loud rather than scoping descendants to a conventional default. There is no `:rf/default` floor.

`rf/frame-provider` is the canonical user-facing API; the lower-level `re-frame.views/build-frame-provider` factory remains as the **substrate hook** (per [Spec 006 §`(register-context-provider frame-keyword)`](006-ReactiveSubstrate.md#register-context-provider-frame-keyword--component)) — adapter implementors register the scope-only context-provider component through it, and both the SCOPE-only shape and the ENSURE shape's provide tier delegate to whatever the active adapter returned.

Other React-on-CLJS adapters (UIx, Helix) realise the same dispatch-on-shape / scope-or-fail / ensure-no-reseed contract with their host's React-context + render primitives — adapter-style, modelling React's `Context.Provider`. Other in-scope JS-cross-compile-language ports realise it through their host's React binding's context primitives: TypeScript-React's `React.createContext`, Fable's Feliz / Fable.React `createContext`, PureScript's `React.Basic.Hooks` `createContext`, Kotlin-React's `createContext`, ReasonReact / Melange's `React.createContext`. Mechanism varies by binding; the *contract* — a UI boundary that scopes an existing frame (failing loud if absent) or ensures a named frame (create-if-absent, reuse-no-reseed, no destroy-on-unmount) — survives all of these. All three CLJS adapters read the **same** `React.createContext` object, so a subtree under any `frame-provider` (of either shape) resolves the right frame regardless of which substrate rendered it. See [000-Vision §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic) and the View Ergonomics top-of-section banner above.

## REPL and test ergonomics

### Testing — see Spec 008

The foundation primitives this Spec defines (`make-frame`, `destroy-frame!`, `with-frame`, `dispatch-sync` with opts, per-frame and per-call overrides, registrar query API) are what [008-Testing.md](008-Testing.md) composes into the test API: fixture lifecycle, per-test stubbing, headless evaluation, framework adapters. `machine-transition` (defined in [005](005-StateMachines.md)) and `compute-sub` (defined in [008 §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm)) round out the JVM-runnable surface for headless testing; both are referenced here only as pointers.

### Frame-targeted dispatch and subscribe (no provider needed)

Always available, frame-keyword-targeted via the opts arg:

```clojure
(rf/dispatch  [:add-todo "milk"] {:frame :todo})
@(rf/subscribe [:items]          {:frame :todo})
```

These are also the right APIs from non-Reagent contexts (server-side, headless tests, agents). No `dispatch-to` / `subscribe-to` sugar functions exist — the two-arg form is the one mechanism. On the JVM, `subscribe` cannot return a deref-able reactive (no Reagent) — the headless equivalent for "compute a sub against an `app-db` value" is `compute-sub`, defined in [008-Testing §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm). JVM tests typically read `(rf/app-db-value <id>)` and pass that into `(rf/compute-sub query-v db)`; `subscribe` on the JVM is supported only when the substrate adapter provides a value-shape implementation.

### `with-frame` and `with-new-frame`

Two sibling macros for tests/REPL that establish an implicit current frame for a block. They are split per concern — the macro name telegraphs the intent.

#### `with-frame` — pin to an existing frame

```clojure
(rf/with-frame :scratch
  (rf/dispatch-sync [:init])
  @(rf/subscribe [:status]))
```

Used when the frame already exists (registered via `reg-frame` or created earlier via `make-frame`). The macro binds the dynamic-frame var for the body's duration; plain `dispatch`/`subscribe` route to `:scratch` via the dynamic-binding tier of the resolution chain. The frame is **not** created or destroyed by the macro.

`with-frame` rejects a vector argument at compile time (`:rf.error/with-frame-vector-form`) — pass a keyword (or a symbol that resolves to one). If you want eval-bind-run-destroy, reach for `with-new-frame`.

Use case: REPL sessions, tests that share a fixture across multiple `deftest` blocks.

#### `with-new-frame` — create, bind, use, destroy

```clojure
(rf/with-new-frame [f (rf/make-frame {:images [auth-image]})]
  (rf/dispatch-sync [:auth/login])
  (is (= :authenticated (get-in (rf/app-db-value f) [:auth :state]))))
```

Used when the frame's lifetime is exactly the body. The macro evaluates `expr`, binds the resulting frame (`make-frame` returns the live frame **value**) to `sym`, runs the body in that frame's dynamic context, and **destroys** the frame on exit (success or exception). `with-new-frame` *owns* the lifetime — it is one of the ownership affordances per [§Scope, carry, and ownership](#the-multi-frame-surface--choose-by-intent).

The expression may be `(make-frame opts)` (returns a frame value), `(reg-frame :id opts)` (returns the keyword), or any expression returning a frame value or frame keyword. The macro destroys whatever was bound on exit. Inside the body, ambient `dispatch`/`subscribe` resolve to the bound frame; reads like `app-db-value` may take the bound value directly — the tests-and-harness use of a frame value (route public ops by id elsewhere).

`with-new-frame` rejects a keyword argument at compile time (`:rf.error/with-new-frame-keyword-form`) — pass a `[sym expr]` vector. If you only want to pin to an existing frame-id, reach for `with-frame`.

Use case: per-test fixtures, devcard widgets, REPL sessions where you want a guaranteed clean frame and guaranteed teardown.

#### Async work outliving `with-frame` / `with-new-frame`

For async closures that fire after the body returns, capture the frame explicitly via `capture-frame` / `frame-bound-fn` (above) — the body's dynamic binding has unwound by then. `with-new-frame`'s `destroy-frame!` runs immediately on body exit; an outstanding async callback that fires after that will hit a destroyed frame.

### `dispatch-sync`

`dispatch-sync` is the entry point for synchronously running an event cascade to completion from *outside* the run-to-completion drain — typically tests, REPL exploration, and event-bootstrapping at app startup. It runs the event through the same RtC drain as `dispatch`; the difference is that the call returns only after the drain settles. Inside an event handler the drain is already running, so calling `dispatch-sync` there is rejected (see "Calling `dispatch-sync` *inside* a handler" below). It accepts the same opts-arg shape as `dispatch`:

```clojure
(rf/dispatch-sync [:foo] {:frame :todo
                          :fx-overrides {:my-app/http stub-fn}})
```

#### Calling `dispatch-sync` *inside* a handler is an error

Under run-to-completion (per [§Run-to-completion dispatch](#run-to-completion-dispatch-drain-semantics)), the cascade is already running synchronously, so `dispatch-sync` from inside a handler conveys no extra meaning over `dispatch`. Calling `dispatch-sync` inside an event handler's interceptor pipeline is **rejected**: the runtime emits `:rf.error/dispatch-sync-in-handler` (per [009 §Error contract](009-Instrumentation.md#error-contract)) and the call is dropped (default recovery `:no-recovery`).

The shape that drains as part of the surrounding cascade is `:fx [[:dispatch event]]` in the effect map. See [MIGRATION.md §M-9](../migration/from-re-frame-v1/README.md) for the migration rule.

#### Cross-frame `dispatch-sync` during a sibling drain warns but proceeds

The same-frame check above is strict: a `dispatch-sync!` against the caller's own frame during its drain is rejected. The cross-frame case is *not* rejected. A `dispatch-sync!` against a **different** frame while the caller's frame is mid-drain interleaves the cascades — frame B runs to settled, then frame A continues. This interleaving is allowed (frames are independent state machines per [§Rules rule 1](#rules) — no cross-frame drain), but rarely the caller's intent, so the runtime emits `:rf.warning/cross-frame-dispatch-sync-during-drain` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) so observability tools spot the pattern. The dispatch proceeds; `:recovery :no-recovery`. For fire-and-forget cross-frame coordination prefer the async form `(rf/dispatch event {:frame other})` — it queues on the target frame's router and drains on a later cycle, after the caller's cascade settles.

## Run-to-completion dispatch (drain semantics)

re-frame2 dispatches **run to completion**: when an external event is processed, every event dispatched (synchronously) during its handler — and every event those handlers dispatch in turn — drains to fixed point before any further external event is processed *for this frame*, and before any view re-renders.

This is the dispatch semantics, not a mode. There is no opt-out. The guarantee gives actor-style machine composition determinism for free ([Spec 005](005-StateMachines.md), when drafted) and removes a class of "flash" intermediate renders that today's async dispatch can cause. It is also load-bearing for [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): every settled, between-event state of a frame is a snapshottable boundary, and no async mutation escapes the dispatch loop to leave the frame's value inconsistent with its registered handlers.

### Drain versus event — the epoch unit

> **Vocabulary.** This section and the two below define the **drain / dispatch** half of the [event-pipeline vocabulary](Conventions.md#event-pipeline-vocabulary--the-terms-one-event-traverses) (the authoritative home). One dequeued event is one **pipeline run** — a single traversal of the fixed stage sequence **assemble → transform → commit → perform** (write side, per event) then, at drain settle, **derive → render** (read side, once per drain). A **pipeline run** is the formal term for a single event's traversal (retiring "event cascade" in the event-traversal sense); a **drain** is the run-to-fixed-point family of such runs. The "six dominoes" framing survives as a **first-contact mnemonic** only, never as formal vocabulary.

A **drain** and an **event** are distinct units, and the distinction is normative:

- A **drain** is one turn of the outer loop (`drain!`). It may dequeue and process *several* events back-to-back — the originating event plus every event its handlers `:fx`-dispatch, and so on, until the queue is empty. A drain is a *scheduling* unit: it bounds when the host event loop gets time back and when the read side runs (**derive → render**, once, at settle).
- An **event** is one dequeued envelope. Each dequeued event runs its **own full pipeline run** — its write side (**assemble → transform → commit → perform**) end-to-end before the next event is dequeued — and **yields its own epoch**: one [`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) per dequeued event. (First-contact mnemonic: the run's write side is the "six dominoes" — event → effects → dispatch → handler → effects → view.)

**One epoch per dequeued event — every origin.** The epoch boundary is *per top-level dequeue*, irrespective of how the event arrived in the queue: a UI `(rf/dispatch …)`, an `:fx [[:dispatch …]]` child queued by another handler, or a frame-creation setup step (an `:initial-events` element, dispatch-synced at `reg-frame` — see [§reg-frame is atomic](#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar)). Each of these is its own dequeued event, so each is its own epoch with its own **pipeline run** and its own trace. A drain that processes a parent event and the child it `:fx`-dispatched therefore produces **two** epoch records, not one — even though both settled inside the same drain.

**Microsteps ride the triggering event's epoch.** A machine's `:raise` sub-events and `:always` microsteps are **not** dequeued events — they are in-memory microsteps inside a single machine **macrostep**, drained pre-commit within the triggering event's handler invocation and never routed through the per-frame queue (per [005 §`:raise`](005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx) / [§Eventless `:always` transitions](005-StateMachines.md#eventless-always-transitions)). They stay **inside the triggering event's epoch**; they do not start a new one. Only a separately *dequeued* event — including an `:fx [[:dispatch …]]` child that round-trips through the queue — opens a fresh epoch. (`:dispatch` to self round-trips the queue as a separate dequeued event — at the back from a plain handler, at the front from a machine handler per [005 §Level 4](005-StateMachines.md#level-4--across-the-runtime); either way a fresh epoch; `:raise` is not dequeued and stays in the same epoch — see [§Edge cases worth pinning](#edge-cases-worth-pinning) #3.)

### Terminology

- **Domain events** — dispatches whose source is the outside world (user input, timer fire, websocket message, REPL). These are the "external events" that drive re-frame.
- **Actor messages** (or just "messages") — dispatches one machine emits to another within a single domain-event's processing. Same `(rf/dispatch [...])` API, distinguished only by the envelope's `:source` field (`:source :machine-action`, stamped by the `:dispatch` / `:dispatch-later` fx handler when the emitting handler is a machine) and by naming convention. There is no separate `message` primitive.

The distinction is documentary and conceptual, not technical. One **event pipeline** (per [Conventions §Event-pipeline vocabulary](Conventions.md#event-pipeline-vocabulary--the-terms-one-event-traverses)), one event shape; "message" is a role a dispatched event plays in a particular context.

### Rules

1. **No cross-frame drain.** Drain runs against the frame's own router queue. A dispatch tagged with a *different* frame goes through the ordinary async path — drain does not span frames. Cross-frame coordination uses regular async `(dispatch ev {:frame other})`.
2. **Every actor message sent during a domain-event's processing drains before the next domain event for that frame.** Once drain is engaged, no further external events are processed for that frame until the cascade settles.
3. **Depth-limited (dynamic), halt at the event boundary — no whole-drain rollback.** The drain enforces a configurable depth limit (`:drain-depth`). When exceeded, drain stops with a machine-readable error: `{:reason :drain-depth-exceeded :frame :auth :event [...] :depth N}`. The limit is per-frame and runtime-overridable for debugging. **The unit of atomicity is the *event*, not the drain** (per [§Drain versus event — the epoch unit](#drain-versus-event--the-epoch-unit)). Every event the drain already settled committed its own `:db` write and its own durable `:ok` epoch — those are **kept**, exactly as if the drain had ended after each one. There is **no** whole-drain rollback and no pre-drain snapshot: rolling back already-settled, already-epoched events would discard durable history and contradict the per-event epoch boundary. When the limit trips, the runtime (a) **discards the remaining queued events** (the next, *halting* event never runs), (b) emits the `:rf.error/drain-depth-exceeded` error trace carrying `:rollback? false` (no state was reverted), and (c) commits a single trailing `:halted-depth` [`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) for the halting event so devtools (Xray's epoch panel, re-frame2-pair's `cascade-of`) get a clear "drain halted here" marker following the durable `:ok` records. Because the halting event never ran, that record's `:db-before` and `:db-after` **both equal the durable last-settled `app-db`** (per [Spec-Schemas §`:rf/epoch-record` §Outcomes](Spec-Schemas.md#outcomes) and the halted-cascade listener contract in [009 §`register-epoch-listener!`](009-Instrumentation.md#register-epoch-listener--assembled-epoch-listener)). The frame is left at the last settled state — which, being the value after a completed event, is exactly the kind of between-event boundary that is always reachable by replay. Conformance fixture: [`drain-depth-limit.edn`](conformance/fixtures/drain-depth-limit.edn).

   **Halt boundary — what does and doesn't commit.** Atomicity is enforced *at the event boundary*, so there is no multi-event drain state to revert. Each settled event is atomic on its own: a handler's `:db` write either commits in full (when the event settles, yielding its `:ok` epoch) or not at all (the event's own partial work never reaches `app-db` if the event itself fails — see [§Interceptor chain execution](#interceptor-chain-execution--before-short-circuit-after-always-runs)). The halting event makes **no** writes — it was never dequeued into a handler invocation — so nothing of its needs reverting. Frame-local registry mutations follow the same per-event grammar: a `(rf/dispatch [:rf.machine/spawn ...])` that **settled** as its own event durably registered the spawned actor's frame-local handler in its `[:rf.runtime/machines :snapshots <id>]` slot, and that registration is kept along with that event's durable `app-db` — there is no orphaning, because the kept `app-db` is the very value (post that event) that references the registration. Out-of-band side effects already committed to *external* substrates (an HTTP request that flew, a `dispatch-later` timer that was scheduled) are likewise not touched. (The sibling halt case, [§Edge cases worth pinning §Frame disposal mid-drain](#edge-cases-worth-pinning), behaves identically: settled events are durable, only not-yet-dequeued events are dropped, and a `:halted-destroy` marker records the halt.)

```clojure
(rf/reg-frame :auth
  {:initial-events [[:auth/initialise]]
   :drain-depth    100})    ;; default and runtime-overridable
```

### Single-drainer invariant (concurrent hosts)

The drain operates under a **single-drainer invariant**: only one thread executes `drain!` at a time. Concurrent dispatch attempts enqueue and wake the executor, which no-ops if a drain is already running — the active drainer picks up newly-queued envelopes before returning.

On single-threaded hosts (CLJS) this is trivially true. On the JVM the runtime's `interop/next-tick` executor can fire its callback concurrently with the calling thread (typically `dispatch-sync` on the main thread), so the implementation must CAS-acquire a per-frame drain-lock at every `drain!` entry; the loser of the CAS returns without touching the queue. `dispatch-sync` spin-waits for the lock and performs its seed-push under the lock so the prepend does not interleave with another drainer's `peek+pop`. The release of the drain-lock and the clearing of the per-router `:scheduled?` flag happen under the same `locking` block that the submit path uses for its scheduling check — that single seam closes the orphan-envelope window (an envelope queued between the inner empty-check and the lock release would otherwise be visible to neither the outgoing drainer's loop nor the next submitter's scheduling decision).

### What is and isn't drained

- **Synchronous re-dispatches (machine-to-machine messages)** are drained.
- **Async effects** — `:http`, timer-based, websocket-flavoured — are *not*. Their responses arrive later as fresh domain events, which then re-engage drain for their own cascade.
- **Domain events from outside the frame** wait until the current drain cascade settles.

### Drain scheduling — microtask, not timer

A drain runs to **fixed point** in one go: once engaged, the outer loop dequeues and processes every synchronously-cascaded event (the originating event plus every event its handlers `:fx`-dispatch, transitively) until the queue is empty, *then* yields. This is the run-to-completion guarantee above expressed as a scheduling property — one drain, one settle.

**Drains are scheduled on the microtask queue.** When a `dispatch` lands on an empty queue, the runtime schedules the drain via the interop layer's `next-tick` — `goog.async.nextTick` in the CLJS reference, **a microtask**, *not* `setTimeout` and *not* `requestAnimationFrame`. (See the [§Drain-loop pseudocode](#drain-loop-pseudocode) `dispatch` / `interop/next-tick` seam, and [Runtime-Architecture §Router](Runtime-Architecture.md).) Microtask scheduling gives the drain the earliest possible turn after the current synchronous stack unwinds, with no minimum-delay clamp.

**Background-throttle property.** Because the drain is microtask-scheduled, **the event loop is not throttled in a backgrounded or CPU-throttled tab.** Browsers throttle *timers* (`setTimeout`, `setInterval`) and *animation frames* (`requestAnimationFrame`) in background tabs; they do **not** throttle the microtask queue. So an app's event-processing cadence — dispatches, machine cascades, async-effect responses re-engaging the drain — continues at full rate whether the tab is foreground or background. What *does* stall in the background is **rendering**: rendering is the adapter's `:adapter/after-render` hook (an rAF-shaped, host-throttled step), which is **decoupled** from the event drain (per [§Render boundaries](#render-boundaries) below and [Runtime-Architecture §Interop layer](Runtime-Architecture.md)). A backgrounded tab keeps computing and keeps its `app-db` current; it simply does not paint until foregrounded.

**No `:flush-dom` — no queue-pause-for-render state.** There is no `:flush-dom` lever and no queue-pause-for-render state: the drain never pauses mid-cascade to wait on a paint. Post-render needs — "show this, *then* run the heavy block" — are served by **`after-render` effects** (the adapter's post-render hook) and by `:dispatch-later {:ms 0}`, **not** by pausing the queue. See [MIGRATION.md §M-16](../migration/from-re-frame-v1/README.md#m-16-flush-dom-event-vector-metadata-removed--replace-with-dispatch-later-ms-0-inside-effect-maps-or-the-top-level-rewrite-outside-event-handlers) and [Pattern — Long-Running Work](Pattern-LongRunningWork.md#one-shot-heavy-work--replaces-flush-dom) for the migration and the canonical pattern that subsumes the v1 flush-DOM use case.

### Render boundaries

Under run-to-completion, a dispatched event runs synchronously *before* the originator returns; views do not render any intermediate state of the cascade. Render happens once, after the cascade settles. (Code that requires a render between two events in a cascade is incompatible with this contract — see [MIGRATION.md](../migration/from-re-frame-v1/README.md).)

`dispatch-sync` means "skip the router queue when called from outside any handler." Calling it from inside a handler raises `:rf.error/dispatch-sync-in-handler` (per [§dispatch-sync](#dispatch-sync) above); the in-handler shape is `[[:dispatch event]]` under `:fx`.

### `:fx` ordering and atomicity guarantees

When an event handler returns `{:db <new-db> :fx [[a 1] [b 2] [c 3]]}`, the runtime processes the effect map under four locked rules. Apps may rely on them; conformant implementations must produce them.

1. **`:db` is the first side effect (when present).** The snapshot transitions atomically in one step before any `:fx` entry is processed. No external observer ever sees a half-written `app-db`.
2. **`:fx` entries are processed in source order.** `[a 1]` runs before `[b 2]` runs before `[c 3]`. The order in which the handler wrote the entries is the order in which they reach `do-fx`.
3. **Each `:fx` entry is processed serially before the next.** No interleaving. The fx-handler for entry N completes (synchronously, from `do-fx`'s perspective) before entry N+1 begins. *Asynchronous* work an fx kicks off (an outbound HTTP request, a `dispatch-later` timer) is not awaited; "complete" means the fx-handler function has returned.
4. **Subscriptions observe the post-`:db` state.** When the first `:fx` entry fires, `app-db` has already transitioned and sub-cache invalidation has happened. A handler may legitimately return `{:db <new-state> :fx [[:dispatch [:react-to-new-state]]]}` and the dispatched event's handler will see the new state.

From the *handler's* perspective, the handler returns once with the full effects map; sequencing of `:fx` entries is deterministic; the handler doesn't observe the side effects firing — it just declares them.

**Composition with the dispatch queue.** When `:fx` entries include `:dispatch`, the dispatched events enter the runtime queue in source order — preserving source-order all the way down a chain. From a plain (non-machine) handler they append to the back (FIFO); from a *machine* handler they are inserted at the front, still in source order, per [005 §Level 4](005-StateMachines.md#level-4--across-the-runtime). `:dispatch-later` schedules timers in source order; actual delivery depends on each timer's delay.

**Composition with state machines.** Machine action effect maps (`{:data :fx}`) follow the same rule per [005 §Drain semantics §Level 1](005-StateMachines.md#level-1--within-a-single-actions-effect-map): `:data` merges first (lowered to one `:rf.db/runtime` write at `[:rf.runtime/machines :snapshots <id>]` — snapshots are runtime-db), then `:fx` entries process in source order with `:raise` routed locally to the machine's pre-commit queue and the rest (including `:rf.machine/spawn` / `:rf.machine/destroy`) forwarded to the standard fx pipeline.

**Error during `:fx`.** If the fx-handler for `[a 1]` throws, subsequent entries `[b 2]` and `[c 3]` **continue to run.** Each thrown error is traced independently as `:rf.error/fx-handler-exception`. The `:db` commit is preserved (it happened before any `:fx` entry). `:fx` entries are independent; ordering means *order*, not *dependency*. An fx that genuinely depends on a prior fx succeeding should be lifted to a `:dispatch` chain — observe the result via cofx in the dispatched handler.

**Post-install asymmetry.** Pre-install throws abort the event cleanly (no `:db` install, no `:fx`, `app-db` unchanged); post-install `:fx` throws do NOT wind `app-db` back, and do NOT undo side effects already fired. The full rationale and the compensating-event saga escape-valve guidance — including a worked example — lives at [013 §Why this asymmetry?](013-Flows.md#why-this-asymmetry--db-is-atomic-fx-is-best-effort).

Conformance fixtures: [`fx-db-first.edn`](conformance/fixtures/fx-db-first.edn), [`fx-ordering-source-order.edn`](conformance/fixtures/fx-ordering-source-order.edn).

### Interceptor chain execution — `:before` short-circuit, `:after` always-runs

The per-event interceptor chain runs `:before` stages in declaration order, then the handler, then `:after` stages in **reverse** declaration order. Two rules govern how throws compose with the chain:

1. **A `:before` (or handler) throw short-circuits subsequent `:before` stages and the handler.** Once any `:before` stage throws, the runtime skips every remaining `:before` stage and the handler itself — the chain context never reaches a meaningful effects map. The throw is recorded into the context via the `:rf/interceptor-error` (singleton, first throw) and `:rf/interceptor-errors` (vector, all throws) keys per [Spec-Schemas §InterceptorContextErrorKeys](Spec-Schemas.md#interceptorcontexterrorkeys--post-chain-interceptor-context-error-contract).
2. **The `:after` pass ALWAYS runs in full**, regardless of whether a `:before` or handler throw occurred. Every `:after` stage on every interceptor in the chain executes — in reverse declaration order — so cleanup-on-`:after` interceptors fire even after a `:before` failure. An `:after` throw appends to `:rf/interceptor-errors` (so post-hoc inspection sees every failure) but does NOT abort the remaining `:after` stages.

This pair is pattern contract — a conformant port MUST mirror both rules. Rule 1 keeps the chain from running the handler against a half-assembled context (a `:before` that was meant to inject a cofx has already thrown; the handler would observe a corrupt context); rule 2 keeps user-installed interceptors safe to allocate resources in `:before` and release them in `:after` (a chain that skipped `:after` on a `:before` failure would leak whatever the surviving `:before` stages allocated). The most common case is an interceptor that mutates host state in `:before` and restores it in `:after` (e.g. a debug `pp` interceptor, or a Story snapshot capturer) — the always-runs rule means the restoration fires regardless of where in the chain a failure occurred.

Trace emission tracks the singleton: the trace stream emits exactly one error event per chain execution, keyed off `:rf/interceptor-error` and **attributed to the true failing component**. The captured singleton's identity drives the category — `:rf.error/handler-exception` when the event handler itself threw (the terminal `:before`), `:rf.error/coeffect-exception` when a coeffect supplier threw during context assembly (an ambient supplier run, or — slice B — a recordable generator), and `:rf.error/interceptor-exception` when a user interceptor's `:before`/`:after` threw (the `:phase` tag discriminates the two). The `:failing-id` carries the true component (event id / cofx id / interceptor id). Tools wanting every failure (Xray, Story) read `:rf/interceptor-errors` from the post-drain context directly. See [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) for the per-category shapes.

### Drain-loop pseudocode

The rules above (the four `:fx` ordering rules, run-to-completion, depth-limited drain) compose into one execution loop. This subsection writes that loop down. v1's `re-frame.router` is the implementation reference — the loop below tracks v1's working router closely; what is *new* in re-frame2 is per-frame queuing, the `:raise` pre-commit primitive, and the machine microstep interleave from [005 §Drain semantics](005-StateMachines.md#drain-semantics).

The loop has two layers — an **outer drain** (Level 4 in [005's terms](005-StateMachines.md#level-4--across-the-runtime)) that pumps events FIFO from the router, and a **per-event drain** that runs one event end-to-end through interceptor chain, `do-fx`, and (for machine events) the Level 3 cascade.

```clojure
;; ============================================================================
;; OUTER DRAIN — per-frame Level-4 loop
;; ============================================================================
;; Triggered when an event arrives in an empty queue. Schedules itself via the
;; interop layer's next-tick so the host event loop interleaves rendering.

;; Queue position by the envelope's ORIGIN mark (per [005 §Level 4]): a
;; machine-internal continuation (`:rf.machine/internal?`, stamped by the
;; machine registrar and copied onto the child by `do-fx :dispatch`)
;; leap-frogs ahead of any already-queued EXTERNAL events; every other
;; origin is a plain FIFO append at the back. The internal envelope splices
;; in AFTER any sibling internal envelopes already queued this macrostep, so
;; source order is preserved among siblings (first emitted is dequeued
;; first) while the whole internal run still precedes every external event.
(defn- enqueue [queue envelope]
  (if (:rf.machine/internal? envelope)
    (let [[internal external] (split-with :rf.machine/internal? queue)]
      (into empty-queue (concat internal [envelope] external)))  ;; front-of-queue splice
    (conj queue envelope)))                                       ;; FIFO append (default)

(defn dispatch [frame envelope]
  (let [router (:router frame)]
    (swap! (:queue router) enqueue envelope)
    (when-not (:scheduled? @router)
      (swap! router assoc :scheduled? true)
      (interop/next-tick (fn [] (drain! frame))))))

(defn drain! [frame]
  (try
    (loop [depth 0]
      ;; Destroyed-frame check fires BEFORE dequeue (per Edge cases #4 below):
      ;; on detect, drop the remaining queue, emit `:rf.frame/drain-interrupted`
      ;; with the dropped count, and stop. In-flight events finish
      ;; (run-to-completion); only events not yet dequeued are dropped.
      (when (:destroyed? (:lifecycle frame))
        (let [dropped (count @(:queue (:router frame)))]
          (reset! (:queue (:router frame)) (clojure.lang.PersistentQueue/EMPTY))
          (trace! :rf.frame/drain-interrupted
                  {:frame (:id frame) :dropped dropped}))
        (throw ::halt))
      ;; `>=` not `>`: `:drain-depth` is the MAX number of events a single
      ;; drain processes. The loop enters with `depth` = the
      ;; count of events already processed this drain; events run at depths
      ;; 0,1,…,(drain-depth-1) — exactly `drain-depth` events — and the
      ;; halt fires when `depth` first reaches `drain-depth` (the
      ;; (drain-depth+1)th event never runs). This matches the `:test`
      ;; preset's "drain bounded at 100" = at-most-100 reading and the
      ;; `:halted-depth` epoch's `:depth` tag (= `drain-depth`).
      (when (>= depth (:drain-depth (:config frame)))
        ;; Per-event epochs (rule 3): already-settled events kept their own
        ;; durable :ok epochs + db writes — there is NO whole-drain rollback,
        ;; so :rollback? is false. Drop the remaining queue (the next, halting
        ;; event never runs) and commit ONE trailing :halted-depth epoch record
        ;; for it so devtools get a halt marker; its :db-before/:db-after both
        ;; equal the durable last-settled db.
        (let [halt-reason {:operation :rf.error/drain-depth-exceeded
                           :frame (:id frame) :depth depth
                           :queue-size (count @(:queue (:router frame)))
                           :last-event (peek @(:queue (:router frame)))}]
          (reset! (:queue (:router frame)) (clojure.lang.PersistentQueue/EMPTY))
          (raise! :rf.error/drain-depth-exceeded
                  (assoc halt-reason :rollback? false))
          (commit-halt-record! frame :halted-depth halt-reason))  ;; trailing epoch
        (throw ::halt))
      (when-let [envelope (peek-and-pop! (:queue (:router frame)))]
        (process-event! frame envelope)                ;; per-event drain
        (recur (inc depth))))
    ;; Catch ONLY the `::halt` control-flow sentinel — the two `(throw ::halt)`
    ;; sites above (destroyed-frame drop, drain-depth-exceeded) use it to break
    ;; the loop after they have already emitted their diagnostic. Any OTHER
    ;; escaping throw is a genuine bug — it must NOT be swallowed here; it
    ;; propagates so the host surfaces it. (`process-event!` already traps
    ;; per-event handler / fx / interceptor throws internally per steps 1–3;
    ;; anything reaching this level is outside that contract.)
    (catch ::halt _ nil)
    (finally
      (swap! (:router frame) assoc :scheduled? false)
      ;; render-tick: the substrate adapter's reactions fire on next read.
      ;; Per the run-to-completion rule, no view re-renders observed any
      ;; intermediate state of this drain.
      )))

;; ============================================================================
;; PER-EVENT DRAIN — one envelope, end-to-end
;; ============================================================================

(defn process-event! [frame envelope]
  (let [{:keys [event opts]} envelope
        handler-id           (first event)
        handler-meta         (registrar/lookup :event handler-id)]
    (trace! :event/run-start {:event event :frame (:id frame)})
    (when (nil? handler-meta)
      (raise! :rf.error/no-such-handler
              {:event event :frame (:id frame)})
      (return-from process-event!))

    ;; 1. Run the interceptor chain — :before steps in order, then handler,
    ;;    then :after steps in reverse. The chain produces an effects map.
    ;;
    ;;    THE FLOW TRANSFORM IS THE OUTERMOST :after (per [013 §Drain
    ;;    integration](013-Flows.md#drain-integration)). Because :after runs
    ;;    outermost-LAST, the framework's flow-transform :after fires after
    ;;    the rest of the :after chain has reshaped the `:db` effect into the
    ;;    complete app-db form (in particular after a `(path :slice)`
    ;;    interceptor splices the handler's slice back into the full db —
    ;;    flows read full-app-db `:inputs` paths, so they MUST run after that
    ;;    reshape). It rewrites the PENDING `:db` effect in the chain context
    ;;    (NOT the installed app-db). This is the moment `:rf.flow/computed`
    ;;    / `:rf.flow/skip` / `:rf.flow/failed` emit.
    ;;
    ;;    A FLOW THROW is a PRE-INSTALL throw (per [013 §Failure semantics]
    ;;    (013-Flows.md#failure-semantics) — the atomicity contract): the
    ;;    flow-transform :after DISCARDS the pending `:db` effect (drops it
    ;;    from `effects`) and records the throw. With no `:db` effect, the
    ;;    install at step 2 is a no-op — app-db unchanged, no
    ;;    `:rf.event/db-changed`, and step 3 skips `:fx`. No partial commit:
    ;;    neither the handler's `:db` nor any prior flow's write lands.
    ;;
    ;;    Throws inside :before / :after / handler are recorded into the
    ;;    chain context under two paired keys — `:rf/interceptor-error`
    ;;    (singleton, the FIRST throw) and `:rf/interceptor-errors` (vector,
    ;;    ALL throws in order). The :after pass always runs in full so
    ;;    cleanup-on-:after interceptors fire even after a :before failure.
    ;;    Trace stream emits one error event per chain execution, keyed off
    ;;    the singleton and attributed to the true failing component:
    ;;    `:rf.error/handler-exception` (the handler),
    ;;    `:rf.error/coeffect-exception` (a coeffect supplier threw at context
    ;;    assembly), or
    ;;    `:rf.error/interceptor-exception` (a user interceptor). See
    ;;    [Spec-Schemas §InterceptorContextErrorKeys](Spec-Schemas.md#interceptorcontexterrorkeys--post-chain-interceptor-context-error-contract).
    (let [effects (run-interceptor-chain      ;; flow-transform is outermost :after
                    frame envelope handler-meta)]

      ;; THE :db INSTALL IS THE SINGLE, DEFERRED, ALL-OR-NOTHING COMMIT
      ;; BOUNDARY. ANY pre-install throw — cofx, handler, interceptor :after,
      ;; or the flow transform — aborts the event: no install, app-db
      ;; UNCHANGED, no `:rf.event/db-changed`, no `:fx`. The mechanism is
      ;; uniform and FREE: a handler / interceptor throw never produced a
      ;; `:db` effect, and the flow-throw path DISCARDS the one it had — so
      ;; in every pre-install-throw case `effects` carries no `:db`, and the
      ;; guarded install below installs nothing. (`:fx` is the only
      ;; POST-install stage; an fx throw at step 3 does NOT wind back the
      ;; installed `:db` — its side effects may already have fired.)

      ;; 2. Commit the FRAME-STATE transition FIRST — the FLOW-AUGMENTED `:db`
      ;;    effect (app-db) AND any `:rf.db/runtime` effect (runtime-db),
      ;;    installed as ONE atomic frame-state write. A cascade may produce
      ;;    an app-db change, a runtime-db change, or both; the frame installs
      ;;    the combined result as one coherent transition into the single
      ;;    physical frame-state container (per §One physical container, two
      ;;    projection reactions). Installs ONLY when at least one partition
      ;;    effect is present — so a pre-install throw (which leaves neither
      ;;    `:db` nor `:rf.db/runtime`) installs nothing. By this point the
      ;;    flow-transform :after has already rewritten `(:db effects)`
      ;;    (step 1), so the app-db value installed here is the flow-derived
      ;;    db. This is the moment sub-cache invalidation fires (per :fx
      ;;    ordering rule 4 above and per [006 §Subscription cache
      ;;    invalidation]); the projection-equality model means an app-only
      ;;    commit propagates only to app subs and a runtime-only commit only
      ;;    to framework subs. Change traces fire AFTER flows (per [013
      ;;    §Drain integration](013-Flows.md#drain-integration) and [009
      ;;    §Canonical per-event trace sequence](009-Instrumentation.md#canonical-per-event-trace-sequence)):
      ;;    an app-db change emits `:rf.event/db-changed` (APP-DB-ONLY — Mike
      ;;    ruling #6); a partition change of EITHER kind additionally emits
      ;;    the frame-level `:rf.event/frame-state-changed` carrying the
      ;;    changed-partition tag(s). `contains?` is the WHOLE guard: a
      ;;    pre-install throw leaves no partition effect, so this is a no-op
      ;;    and the event aborts with both partitions unchanged.
      ;;    `commit-frame-transition!` applies the §The `:db` commit / no-op
      ;;    return family rules at this boundary: a `:db` effect carrying the
      ;;    CURRENT object unchanged (`identical?`, not merely `=`) skips the
      ;;    physical container write (the commit no-op short-circuit) — no
      ;;    `:rf.event/db-changed`, no projection propagation; a `{:db nil}`
      ;;    effect is coerced to `{:db {}}` (app-db is always a map) with a
      ;;    dev-mode `:rf.warning/db-nil-coerced` diagnostic. Deeper
      ;;    change-detection is value equality (`=`); the cheap fast-path is
      ;;    reference identity.
      (when (or (contains? effects :db) (contains? effects :rf.db/runtime))
        (substrate/commit-frame-transition!                ;; one atomic frame-state install
          (:frame-state frame)
          (cond-> {}
            (contains? effects :db)            (assoc :rf.db/app     (:db effects))
            (contains? effects :rf.db/runtime) (assoc :rf.db/runtime (:rf.db/runtime effects))))
        (sub-cache/invalidate! frame))

      ;; 3. Walk :fx in source order — SKIPPED on any pre-install throw
      ;;    (handler / interceptor :after / flow): the event aborted at the
      ;;    commit boundary, so no `:fx` runs. (:fx is the only POST-install
      ;;    stage.) On a clean settle, each entry's handler returns
      ;;    synchronously before the next begins. Errors trace and continue.
      ;;    The fx-handler is invoked with the binary `(m args)` contract
      ;;    documented in [§The binary fx-handler signature](#the-binary-fx-handler-signature):
      ;;    `m` is the fx-handler ctx — a SMALL map DISTINCT from the event
      ;;    handler's coeffects map, carrying `:frame` (the frame ID, never the
      ;;    record), `:event`, and the runtime-internal `:envelope`. It does NOT
      ;;    carry `:db` / cofx. The runtime needs the frame RECORD (to resolve
      ;;    `:fx-overrides`) and the parent envelope (so reserved fxs that queue
      ;;    children — `:dispatch`, `:dispatch-later` — can copy envelope fields
      ;;    onto the child envelope, per [§Cascade propagation](#cascade-propagation));
      ;;    the record is resolved FROM `(:frame m)` at the do-fx choke point
      ;;    (registry lookup), and the envelope reaches the reserved fx as
      ;;    `(:envelope m)` — neither rides as a separate positional argument,
      ;;    and neither puts the live record into the handler-visible map.
      ;;    Per Spec-Schemas §`:rf/handler-context`.
      ;;    Skipped on a pre-install throw — the chain context records the
      ;;    throw under `:rf/interceptor-error` (handler / interceptor) or
      ;;    `:rf/flow-error` (flow transform); either suppresses the walk.
      ;;    (An `:fx` effect CAN still be present — a handler may produce
      ;;    `:fx` before a later interceptor `:after` throws — so unlike the
      ;;    `:db` install this guard cannot rely on effect-absence alone.)
      ;;    PORTING NOTE: these two error markers live at the TOP LEVEL of the
      ;;    interceptor-chain CONTEXT, NOT inside the `:effects` map. This
      ;;    pseudocode threads only `effects` for brevity; in the reference
      ;;    impl `run-chain` returns the full `final-ctx` and the router reads
      ;;    `(:rf/interceptor-error final-ctx)` / `(:rf/flow-error final-ctx)`.
      ;;    A literal port MUST read these off the chain context, not effects.
      (when-not (or (:rf/interceptor-error effects) (:rf/flow-error effects))
       (let [m (fx-handler-context frame envelope)]   ;; the SMALL fx ctx: {:frame <id> :event <ev> :envelope <envelope>}
                                                      ;; — NOT the event handler's coeffects map; `:frame` is the id, resolved
                                                      ;; from `(:id frame)`, never the record (Spec-Schemas §`:rf/handler-context`)
        (doseq [[fx-id args] (:fx effects)]
          (try
            (let [fx-handler (lookup-fx frame fx-id)]  ;; honors :fx-overrides
              (fx-handler m args))                     ;; binary contract: (m, args)
            (catch :default e
              (raise! :rf.error/fx-handler-exception
                      {:fx-id fx-id :event event :frame (:id frame) :ex e}))))))

      (trace! :event/run-end {:event event :frame (:id frame)}))))

;; ============================================================================
;; do-fx for the FOUR reserved fx-ids the runtime owns
;; ============================================================================
;; :dispatch       — append to back of router queue; the outer drain picks
;;                   it up in this same drain cycle (run-to-completion).
;; :dispatch-later — schedule via interop/set-timeout!; the timer fires a
;;                   fresh dispatch later, re-engaging the drain loop.
;; :db             — handled inline in process-event! step 2; not seen here.
;; :raise          — machine-internal; routed by make-machine-handler to
;;                   its local raise-queue BEFORE :fx reaches do-fx (see
;;                   machine pseudocode below).
;; :rf.machine/spawn / :rf.machine/destroy — registered globally by
;;                   re-frame.machines and reach do-fx like any other fx.

;; Inheritable envelope fields — copied from parent to child when :dispatch /
;; :dispatch-later queue a new envelope. This is the "envelope-field-copying
;; when queueing children" mechanism named in [§Cascade propagation]
;; (#cascade-propagation). `:event` is NOT inherited — the child gets its
;; own. `:rf.cofx` is NOT inherited either — a child dispatch is a
;; DISTINCT causal token, so it gets a freshly-stamped recordable-coeffect map
;; (no `:rf/time-ms` inheritance — see [§Recordable coeffects]). `:source` is NOT
;; inherited either —
;; each child dispatch's `:source` reflects its IMMEDIATE trigger
;; (`:fx-dispatch` / `:fx-dispatch-later`), stamped by the queueing fx
;; handler. Inheriting `:source` mis-attributed every fx-emitted dispatch
;; as carrying the originating user-event's trigger (e.g. a `:dispatch` fx
;; deep in a cascade kept reporting `:source :ui`).
(def ^:private inheritable-envelope-keys
  [:frame :fx-overrides :interceptor-overrides :trace-id :origin])

(defn- child-envelope [parent-envelope event]
  (-> (select-keys parent-envelope inheritable-envelope-keys)
      (assoc :event event)))

;; Reserved-fx defmethods follow the same binary `(m args)` contract as
;; user fxs. They read the frame ID and the parent envelope off `m` —
;; `(:frame m)` (the id keyword, NOT the record) and `(:envelope m)` —
;; rather than as separate positional arguments. Where the reserved fx
;; needs the live frame RECORD (the router queue / state container), it
;; RESOLVES it from the id via the registry (`resolve-frame`, O(1)) at
;; this choke point — the record never rides in `m`. This keeps reserved
;; and user fxs uniform: they are all `(fn [m args] ...)` to the resolver,
;; and every handler-visible map stays portable (Spec-Schemas §`:rf/handler-context`).

(defmethod do-fx :dispatch [m ev]
  (let [frame           (resolve-frame (:frame m))  ;; record ← id (registry lookup)
        parent-envelope (:envelope m)
        ;; Queue position is the dispatch's ORIGIN, not its target: a
        ;; child emitted from a MACHINE handler's own processing
        ;; front-inserts so the machine drives its macrostep's follow-on
        ;; to quiescence before the next external event; every other
        ;; origin goes to the back (plain FIFO). The origin is read off
        ;; the parent envelope's `:rf.machine/internal?` mark — set by the
        ;; machine registrar when the in-flight handler carries the
        ;; `:rf/machine?` registration stamp — and copied onto the child
        ;; here, which `enqueue` (above) reads to choose front vs back.
        ;; [005 §Level 4] owns the observable ORDERING and where the mark is
        ;; set; this branch + the `enqueue` splice are the queue mechanism.
        child           (cond-> (child-envelope parent-envelope ev)
                          (:rf.machine/internal? parent-envelope)
                          (assoc :rf.machine/internal? true))]  ;; front iff machine-origin, else back (FIFO)
    (dispatch frame child)))

(defmethod do-fx :dispatch-later [m {:keys [ms event]}]
  (let [frame           (resolve-frame (:frame m))  ;; record ← id (registry lookup)
        parent-envelope (:envelope m)
        child           (child-envelope parent-envelope event)]
    (interop/set-timeout!
      (fn [] (dispatch frame child))
      ms)))
```

For machine events, `process-event!` step 1 lands inside the machine handler. From the outer drain's perspective the machine handler is **just a handler**: it returns an effects-map like any other, and steps 2–3 above process it unchanged. What runs *inside* that one handler call — the raise drain, the `:always` microstep loop, and the single `:rf.db/runtime` snapshot commit at `[:rf.runtime/machines :snapshots <id>]` — is the **Level-3 cascade**, and **[005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event) is its single normative description**. This spec does not restate the loop: the raise-before/-after-`:always` ordering, the FIFO raise-queue, the microstep fixed point, the depth limits, and the atomic post-drain commit all live in 005 (an earlier draft duplicated the loop here in pseudocode that encoded the *retired* raise-before-`:always` order, contradicting 005 and the [`always-settles-before-raise`](conformance/fixtures/always-settles-before-raise.edn) fixture — collapsed to this pointer per the [spec/README drift rule](README.md#ownership-matrix)).

Only the drain-integration facts 002 owns are stated here: the whole macrostep — raise drain, microstep loop, snapshot commit — appears as **one logical step (one epoch)** to external observers, so sub-cache invalidation fires **once** (in `process-event!` step 2 after the macrostep commits), not on every microstep. Machine snapshots are runtime-db, so the commit is an `:rf.db/runtime` partition write, authorised by the machine registrar's `:rf/machine?` stamp (see [§Minting framework-write authority](#minting-framework-write-authority)); it is never an app-db `:db` write. Continuation events the machine dispatches from *inside* that macrostep — `:fx [[:dispatch …]]` — front-insert on the router queue per the `do-fx :dispatch` origin branch above and [005 §Level 4](005-StateMachines.md#level-4--across-the-runtime).

**`process-event!` is the epoch unit.** One run of `process-event!` — one dequeued event, its full six-domino cascade, and (for machine events) its entire macrostep — is exactly one epoch (per [§Drain versus event](#drain-versus-event--the-epoch-unit) above). The raise drain and microstep loop ride **inside** that single epoch; they are not separate dequeues and do not open new ones. The next iteration of the outer `drain!` loop dequeues the next event and opens the next epoch — even when that next event is an `:fx`-dispatched child of the one that just settled.

#### Interaction map

This per-event drain is the canonical place every other piece of the runtime hooks in.

| Phase | Interacts with |
|---|---|
| `process-event!` step 1 | [Registrar](Runtime-Architecture.md#1-registrar) — handler resolution; [001-Registration §Registry kind taxonomy](001-Registration.md#registry-model--the-canonical-kind-keyword-set) |
| `process-event!` step 2 | [Substrate adapter §replace-container!](006-ReactiveSubstrate.md#read-container-container--value-and-replace-container-container-new-value--nil); [Sub-cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics) |
| `process-event!` step 3 | `do-fx`; per-frame and per-call `:fx-overrides` (per [§Per-frame and per-call overrides](#per-frame-and-per-call-overrides)) |
| Trace emission | [009 §Core fields](009-Instrumentation.md#core-fields-required-on-every-event); error events use the `:rf.error/*` namespace per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) |
| Error trapping (`raise!` calls) | The structured-error contract per [009 §Error contract](009-Instrumentation.md#error-contract); errors fan out through the always-on [`register-listener!` (`:errors` stream)](009-Instrumentation.md#what-is-available-in-production) surface |
| Machine cascade | [005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event); `:raise` is routed by `make-machine-handler` *before* `:fx` reaches `do-fx`; `:rf.machine/spawn` / `:rf.machine/destroy` reach `do-fx` like any other fx (per [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids)) |

#### Edge cases worth pinning

1. **`:raise` inside an `:always` action.** The microstep that fires the action accumulates its `:fx` (including `:raise`) into the same Level-3 accumulator; the next iteration of the cascade drains the new raise-queue before re-checking `:always`. Same loop, no special case. Tracked via the same depth limits.
2. **Re-entrant dispatch from a render.** A view fn calling `(rf/dispatch ...)` during render lands in the router queue. The current drain has already settled before render started (run-to-completion); the dispatched event is processed in the *next* drain cycle, after the host gives time back to the JS event loop. Calling `dispatch-sync` from inside any handler raises `:rf.error/dispatch-sync-in-handler` (per [§dispatch-sync](#dispatch-sync)).
3. **`:dispatch` to self in a handler.** Round-trips the runtime queue as a **separate dequeued event** (its own epoch), running against the *post-commit* snapshot — from a plain handler it lands at the back (FIFO); from a *machine* handler it leap-frogs to the front (per [005 §Level 4](005-StateMachines.md#level-4--across-the-runtime)). Either way it is **different** from `:raise`, which runs pre-commit, FIFO, inside the same macrostep/epoch. The two are not interchangeable — see [005 §Drain semantics gotchas](005-StateMachines.md#drain-semantics-gotchas).
4. **Frame disposal mid-drain.** The drain loop checks `(:destroyed? (:lifecycle frame))` before each dequeue; on detect, it stops, drops the remaining queue, and emits `:rf.frame/drain-interrupted` with the dropped count. In-flight events finish (run-to-completion); only events not yet dequeued are dropped.
5. **Effect handler kicks off async work and returns.** Handler returns synchronously; the async work runs against future ticks; its eventual reply is a fresh `dispatch` per [Pattern-AsyncEffect](Pattern-AsyncEffect.md). The drain loop is non-blocking — `:fx` "complete" means the fx-handler fn has returned, not that its observable side effects have settled.

## Per-frame and per-call overrides

> **Expected use case: testing.** Overrides are designed for tests, story fixtures, REPL exploration, and dev-time scenarios. They are *not* a production behaviour-routing mechanism — production code should use ordinary fx and interceptors registered globally. Overrides exist so tests can run without monkey-patching the global registry; they leave no trace once the test ends.
>
> **Pattern-level contract vs. CLJS reference (locked):** at the **pattern level**, override values are *registered ids* — `{:my-app/http :my-app/http.canned-200}` swaps one registered fx for another by id. Functions don't serialise across the wire; an SSR-capable architecture (Spec 011) requires id-valued overrides. The **CLJS reference v1** additionally supports function-valued overrides (`{:my-app/http (fn [m args] ...)}`) as a client-only convenience for tests and story fixtures where the override is a one-off lambda. Both forms accepted; id-valued is the portable shape, function-valued is CLJS-only sugar.
>
> **Asymmetry (explicit, locked):** other-language implementations need only support **id-valued** overrides — that's the conformance contract. The CLJS reference accepting function values is a local ergonomic affordance, not a pattern-level contract. AI scaffolding (Construction-Prompts) and the conformance corpus generate id-valued overrides. The `:rf/dispatch-envelope` schema's `:fx-overrides` value is `[:map-of :keyword :any]` rather than `[:map-of :keyword :keyword]` precisely because the CLJS reference admits the function-valued form; non-CLJS implementations narrow the value type to id-only.

Two things can be overridden per-call (via the dispatch opts map) and per-frame (via `reg-frame` keys); a third — the authored interceptor chain — is a **frame-only** addition, not a per-call one (EP-0022):

| Envelope key             | What it does                                                              | Source: per-call            | Source: per-frame                       |
|--------------------------|--------------------------------------------------------------------------|-----------------------------|------------------------------------------|
| `:fx-overrides`          | Replace registered fx handlers (by id)                                    | dispatch opts               | `reg-frame :fx-overrides`                |
| `:interceptor-overrides` | Replace / remove interceptors in the event's chain (by **exact reference**) | dispatch opts             | `reg-frame :interceptor-overrides`       |
| `:interceptors`          | The frame-level interceptor **ref** chain prepended to every event in the frame ("global within this frame") | — (not a dispatch opt — EP-0022) | `reg-frame :interceptors`                |

`:fx-overrides` and `:interceptor-overrides` flow through the dispatch envelope and merge per-call over per-frame on key conflict. **Additive per-dispatch `:interceptors` is removed** ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md)): authored behaviour has two homes (event metadata and frame metadata), and per-call variation is expressed by `:interceptor-overrides` (substitute or remove a named ref). Supplying `:interceptors` in a dispatch opts map is not an honoured key — it falls through the generic unknown-dispatch-opt surface (warned, dispatch proceeds unchanged) — see [§Registered interceptors and the chain grammar §Dispatch-option restrictions](#dispatch-option-restrictions).

### `:fx-overrides` — replace fx handlers

The pattern-level form is **id-valued** — replace one registered fx with another. Functions don't serialise across the wire, so id-valued is the only form SSR can use. The CLJS reference also accepts function values for one-off CLJS lambdas (test fixtures, story decorators) where registering a stub feels like overkill.

```clojure
;; per-call — id-valued (canonical, portable)
(rf/dispatch [:user/login {:email "..."}]
             {:fx-overrides {:my-app/http  :my-app/http.canned-200
                             :localstorage nil}})  ;; nil = NO override — the original :localstorage fx runs

;; per-frame — id-valued
(rf/reg-frame :story.auth.login-form/loading
  {:initial-events [[:auth/show-loading]]
   :fx-overrides   {:my-app/http :my-app/http.pending-stub}})

;; per-call — function-valued (CLJS reference convenience for tests)
(rf/dispatch [:user/login {:email "..."}]
             {:fx-overrides {:my-app/http (fn [m args] (canned-response args))}})
```

Where the id-valued form points: a separate `reg-fx` registration. The id-valued form composes with the registry — the override is itself a queryable, schema'd, source-coordinated artefact:

```clojure
(rf/reg-fx :my-app/http.canned-200
  {:doc       "Test stub: every :my-app/http call resolves to a canned 200 response."
   :platforms #{:client :server}}
  (fn [_m args]
    (when-let [on-success (:on-success args)]
      (rf/dispatch (conj on-success {:status 200 :body "test"})))))
```

A standard interceptor in re-frame2's default chain reads `:fx-overrides` from the envelope and consults it before the global fx registrar at fx-resolution time:

```clojure
;; effect-handler resolution (conceptual)
(defn- effect-handler [effect-key envelope]
  (let [override (get (:fx-overrides envelope) effect-key)]
    (cond
      (nil? override)        (get-fx-handler effect-key)              ;; no override — the original fx runs
      (keyword? override)    (get-fx-handler override)                ;; id-valued: redirect
      (fn? override)         override                                  ;; CLJS reference: function value
      :else                  (throw (ex-info "Invalid override" {:effect-key effect-key :override override})))))
```

<a id="reserved-fx-id-override-tier"></a>

#### Reserved fx-ids are tiered against override

A `:fx-overrides` entry may target a **reserved** fx-id, and the framework tiers the reserved set against override by the **state-installation criterion** (per [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids)):

- **OVERRIDABLE** — `:dispatch`, `:dispatch-later`, `:rf.machine/dispatch-to-system`, and the navigation primitives `:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/capture-scroll`. Their bodies only *route* dispatches or *touch host/browser state* — they do not write the frame runtime-db. An override (fn-value **or** keyword-redirect) of one of these is honoured exactly as for a user fx-id: the override pre-empts the reserved body. This is the legitimate test/story affordance — capture a dispatch without queueing it, no-op a navigation.

- **HARD-REJECTED** — `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`, `:rf.route/with-nav-token`. Their bodies *install or clear* durable frame runtime state (machine snapshots, flow registry entries) or thread a correctness-critical nav-token; an override that stubs them out would break framework behaviour *far from the override site* (a spawned actor's later dispatches become `:rf.error/no-such-handler`; a dropped nav-token silently defeats stale-result suppression). An override targeting one of these is **ignored** — the runtime emits [`:rf.error/reserved-fx-override`](009-Instrumentation.md#error-event-catalogue) and runs the real reserved body. In production builds the effective override map (per-frame ⋈ per-call) is **stripped** of these keys loudly before the fx walk; the rejected keys are also **excluded from cascade inheritance** so a per-call override never propagates into a `[:dispatch …]` child cascade.

(`:raise` is machine-internal — it never reaches the effect interpreter — so it is not in either tier.)

### `:interceptor-overrides` — replace or remove interceptors by exact reference

Override matching is by **canonical interceptor reference**, not by an interceptor value's `:id` (EP-0022). Keys are interceptor references (a bare keyword matches that keyword; a parameterized reference matches the full `[id arg]` vector); values are either another reference (to replace the matched interceptor) or `nil` (to remove it). The full grammar and the exact-reference rationale live in [§`:interceptor-overrides` — exact-reference substitution](#interceptor-overrides--exact-reference-substitution); the per-frame / per-call placement and precedence are summarised here.

```clojure
;; per-call — turn off the logging interceptor for this dispatch
(rf/dispatch [:user/login {:email "..."}]
             {:interceptor-overrides {:my-app/logging nil}})

;; per-frame — disable logging for everything in a test frame
(rf/reg-frame :Test.Auth/silent
  {:initial-events        [[:auth/test-init]]
   :interceptor-overrides {:my-app/logging nil}})
```

Use cases (all testing-flavoured):

- **Turn off a logging interceptor in tests** — `{:my-app/logging nil}` removes it for the test's events.
- **Swap a custom audit interceptor for a recording stub** — `{:my-app/audit :story/record-events}` (the replacement is itself a registered ref, resolved through the same registrar). (Deterministic *time* is not an interceptor override under EP-0017 — supply the exact fact in the envelope: `(dispatch-sync [:e] {:rf.cofx {:rf/time-ms fixed-ms}})`; see [§Recordable coeffects](#recordable-coeffects).)
- **Replace a remote-call validator with a relaxed one** for stories that intentionally violate the schema for visualisation.
- **Remove one instance of a parameterized interceptor** — `{[:rf.interceptor/path [:cart]] nil}` removes only that exact reference, leaving any other `[:rf.interceptor/path …]` in the chain intact.

Precedence: **frame overrides < dispatch-opts overrides** — per-call overrides win on key conflict. A `nil` value removes the matching ref; a replacement ref is resolved through the same registrar before execution. The serializable, exact-reference shape keeps SSR, story, test, and tool override state inspectable; value-valued overrides are not accepted on public surfaces.

<a id="interceptors--add-interceptors-to-a-frames-events"></a>

### `:interceptors` — the frame-level interceptor ref chain ("global within this frame")

`:interceptors` is the frame-level interceptor **ref** chain prepended to every event handled in the frame. It carries interceptor references (bare keywords or `[id arg]` parameterized refs), never inline interceptor values — the full grammar is in [§Event and frame chain grammar](#event-and-frame-chain-grammar).

```clojure
(rf/reg-frame :Dev.Recorder/active
  {:interceptors [:dev/event-recorder
                  :dev/app-db-validator]})
```

Use cases:

- **Action recorder** — capture every dispatched event for a story's "actions" panel.
- **App-db schema validator** — run Malli check after every event.
- **Tracing decorator** — emit fine-grained trace events scoped to a particular frame.
- **Effect recorder** — capture but don't fire effects, for dry-run/documentation modes (often combined with `:fx-overrides` to also disable real firing).

Each behaviour is registered once with `reg-interceptor` ([001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)) and referenced here by id.

**Frame-level `:interceptors` is the canonical "global within this frame" mechanism.** There is no cross-frame or process-global interceptor concept in v2 — the v1 `reg-global-interceptor` / `clear-global-interceptor` surface is not shipped (per [MIGRATION §M-17](../migration/from-re-frame-v1/README.md)). For cross-frame *observation* (audit logging, performance instrumentation, schema-validation-via-trace) use `register-listener!` per [009-Instrumentation](009-Instrumentation.md). For cross-frame *behaviour modification* (rare, usually an architectural smell), declare the interceptor ref on each frame's `:interceptors` vector explicitly. Single-frame apps (only `:rf/default` in play) recover v1's global feel by adding the interceptor ref to the default frame's `:interceptors`.

### Cascade propagation

All three override types propagate transitively through any depth of `:fx [:dispatch ...]` cascade. When a handler returns an effect map containing `:dispatch`, the dispatched child inherits the parent envelope's overrides (and `:frame`, `:trace-id`, `:origin`). One mechanism: envelope-field-copying when queueing children; same as `:frame` propagation.

`:source` is **excluded from the inheritance set** — each child dispatch's `:source` reflects its *immediate* trigger. The `:dispatch` fx handler stamps `:source :fx-dispatch`; the `:dispatch-later` fx handler stamps `:source :fx-dispatch-later`. Inheriting `:source` mis-attributed every fx-emitted dispatch as carrying the originating user event's trigger (a `:dispatch` fx deep in a cascade kept reporting `:source :ui`). The actor-identity axis (`:origin`) still propagates so post-mortem filters like "show me only the dispatches I (the pair tool) issued" remain effective end-to-end.

### Discoverability

`(rf/frame-meta :my-frame)` returns the override and interceptor-ref maps, so 10x and agents can see what's been scoped and why a particular fx or interceptor didn't behave as expected.

## Registered interceptors and the chain grammar

Interceptors are the public application full-context (`context -> context`) mechanism ([EP-0018](../docs/EP/EP-0018-one-event-registration.md)); they are first-class **registered** image members and the event/frame `:interceptors` surfaces carry interceptor **references**, not inline interceptor values ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md)). The registrar half — the `:interceptor` registry kind, `reg-interceptor`, descriptors, metadata, and `handler-meta :interceptor` — is owned by [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar). This section owns the runtime half: the reference shape, the event/frame chain grammar, dispatch-option restrictions, `:interceptor-overrides`, effective ordering, validation/resolution timing, the standard `:rf.interceptor/path` interceptor, and the no-standard-`unwrap` rule. The interceptor **execution model** is the standard one (`:before` in order, handler, `:after` in reverse; the short-circuit / always-runs rules) — see [§Interceptor chain execution](#interceptor-chain-execution--before-short-circuit-after-always-runs).

### Interceptor references

An interceptor reference is one of two shapes:

```clojure
:auth/required                    ;; bare keyword — references a static registered interceptor
[:rf.interceptor/path [:cart]]    ;; [id arg] vector — references a parameterized interceptor factory
```

A bare keyword references a static interceptor (`{:before}` / `{:after}` / `{:before :after}` descriptor). A two-element `[interceptor-id arg]` vector references a `:factory` interceptor; **parameterized references take exactly one argument**. A factory that needs multiple inputs takes them as a single composite arg (a vector or map):

```clojure
[:rf.interceptor/path [:cart :items]]
[:app/role {:role :admin :redirect [:login/show]}]
```

The `arg` MUST be EDN-serializable when the reference appears in any serialized program-description surface — an app value, frame config, story, replay fixture, or SSR artifact. Exact-reference matching (for overrides, below) uses the EP-0012 / CEDN-1 canonical form ([Conventions §Canonical byte encoding](Conventions.md#canonical-byte-encoding-cedn-1)); the reference shape is [`:rf/interceptor-ref`](Spec-Schemas.md#rfinterceptor-ref-the-interceptor-reference-ep-0022) in Spec-Schemas. A chain entry that is neither a keyword id nor an `[id arg]` 2-vector is `:rf.error/invalid-interceptor-ref`; a parameterized ref whose id is not a `:factory` interceptor (or whose factory cannot build for the arg) is `:rf.error/interceptor-factory-arity`.

### Event and frame chain grammar

The two public `:interceptors` surfaces are **event metadata** and **frame metadata**. Both accept a vector of interceptor references:

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]
                  :auth/required]}
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))

(rf/reg-frame :story/cart
  {:interceptors [:story/record-events]})
```

Inline interceptor maps, interceptor values, or Vars in a public `:interceptors` chain are a registration error — `:rf.error/inline-interceptor-removed`. The recovery is to register the behaviour with `reg-interceptor` and reference it by id. (The chain carries refs only. The malformed-`:interceptors`-value and bare-interceptor `reg-event` errors are owned by [001 §Allowed forms of the middle slot](001-Registration.md#allowed-forms-of-the-middle-slot).)

### Dispatch-option restrictions

Dispatch opts do **not** accept an additive `:interceptors` key under EP-0022 ([§Per-frame and per-call overrides](#per-frame-and-per-call-overrides)). Authored interceptor behaviour has exactly two homes — event metadata and frame metadata — and per-dispatch variation is expressed through `:interceptor-overrides`, which substitutes or removes named refs. `:interceptors` is not among the keys `build-envelope` reads, so supplying it is unhonourable input: it surfaces through the **generic unknown-dispatch-opt surface** — a dev-only `:rf.warning/unknown-dispatch-opt` (recovery `:no-recovery`; the dispatch proceeds unchanged with the key ignored), honouring [Conventions §No silent swallow](Conventions.md#no-silent-swallow--recognised-input-must-signal) by refusing to swallow the key quietly. The warning is DCE-elided under production (`:advanced` + `goog.DEBUG=false`), in line with every dev-diagnostic surface.
>
> **Note — possible future tightening.** Today `:interceptors` is caught by the generic unknown-opt warning, the same path that catches a typo'd key. A dedicated **always-on** rejection — a distinct error-id that names `:interceptor-overrides` as the replacement and survives production elision — is a possible future tightening if the looser dev-only signal proves insufficient; it is not the current behaviour.

### `:interceptor-overrides` — exact-reference substitution

`:interceptor-overrides` remains the per-frame / per-call mechanism for replacing or removing interceptors in a chain, but the map is **reference-based** (EP-0022):

```clojure
{:interceptor-overrides
 {:auth/required :story/skip-auth      ;; replace the matched interceptor with another ref
  :audit/record-event nil}}            ;; remove the matched interceptor
```

Keys are interceptor references. Values are either another interceptor reference (replace) or `nil` (remove). **Value-valued overrides are not accepted on public surfaces** — keeping SSR, story, test, and tool override state serializable and inspectable.

Matching is by **canonical interceptor reference**, not just by id. A bare keyword matches that keyword; a parameterized reference matches the full `[id arg]` vector (canonicalized under CEDN-1). This disambiguates the case where one chain holds multiple instances of the same factory:

```clojure
{:interceptors [[:rf.interceptor/path [:cart]]
                [:rf.interceptor/path [:cart :items]]
                :auth/required]
 :interceptor-overrides
 {[:rf.interceptor/path [:cart]] nil   ;; removes ONLY the exact [:rf.interceptor/path [:cart]] reference
  :auth/required :story/skip-auth}}
```

An id-only override could not say which `[:rf.interceptor/path …]` it meant; exact-reference matching is slightly more verbose but precise, serializable, and stable under CEDN-1. Precedence is unchanged — **frame overrides < dispatch-opts overrides** (per-call wins). A malformed key or replacement is `:rf.error/interceptor-override-invalid`.

### Effective chain ordering

The effective interceptor chain for one dispatch is assembled in this order:

```text
1. frame metadata :interceptors refs
2. event metadata :interceptors refs
3. the framework event-handler wrapper
4. framework dispatch-time interceptors owned by their specs
```

Groups 1 and 2 are **authored references** and resolve through the same process-global registrar that resolved the event handler (per [§Frame addressing](#frames-reference-realms) — resolution is process-global; the frame supplies state, not a separate registrar). This EP records the lookup direction without adding a registrar-patching API.

After refs resolve, the runtime applies the merged override map (frame `:interceptor-overrides` < dispatch-opts `:interceptor-overrides`): a replacement ref is resolved through the same registrar before execution; a `nil` replacement removes the matching ref from the chain.

Framework dispatch-time interceptors that are **not** authored image members are governed by their owning specs — flow transformation, for instance, wraps after the authored chain in the position [013](013-Flows.md) requires (the outermost `:after`, per [§Drain-loop pseudocode](#drain-loop-pseudocode)). The reference-based naming applies to authored interceptors, not subsystem-owned dispatch machinery.

### Validation and resolution timing

Event and frame metadata store interceptor **references**, not resolved interceptor maps. The runtime resolves references when assembling the dispatch chain.

- **Registration-time validation.** A live `reg-event` / `reg-frame` that references an interceptor id with no registration fails at registration — `:rf.error/unregistered-interceptor`. Typos die before dispatch semantics apply.
- **Image-assembly validation.** Image values ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md)) validate refs during assembly of the resolved image generation — the explicit phase that selects descriptors, validates collisions and references, and seals a generation before the frame runs.
- **Dispatch-time guard.** A dispatch-time unknown-ref failure exists only as a defensive guard against corrupt state or a hot-reload race.

Resolving at dispatch time preserves **hot reload**: re-registering `:auth/required` with a new descriptor takes effect on the next dispatch of any event whose chain references it — the event does not have to be re-registered just because an interceptor implementation changed ([001 §Interceptors — Hot reload](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)). Implementations MAY cache resolved chains, but cache invalidation MUST observe interceptor re-registration, event re-registration, frame re-registration, and per-call override changes.

### Standard `:rf.interceptor/path`

v2 keeps exactly **one** framework-standard interceptor, referenced as:

```clojure
[:rf.interceptor/path path-vector]
```

`path-vector` is an EDN vector naming a concrete app-db path. It is the canonical standard `:factory` consumer (the factory receives the path-vector as its one arg) — there is no public `rf/path` value constructor; the public chain language stays uniform (keywords and `[id arg]` refs). It focuses an event handler on an app-db sub-slice and re-widens the returned slice back into full app-db:

```clojure
(rf/reg-event :cart/add
  {:interceptors [[:rf.interceptor/path [:cart]]]}
  (fn [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))
```

The standard path interceptor:

1. records the original full app-db object **and** the original focused slice;
2. stages the focused slice as the handler's `:db` coeffect;
3. if the handler emits **no** `:db` effect, emits no synthetic `:db` effect;
4. if the handler emits a `:db` effect whose focused value is **`identical?`** to the original focused slice, rewrites the effect back to the **original full app-db object** (not an `assoc-in` allocation);
5. otherwise widens the focused value into the original app-db at `path-vector`.

**Root path (`[]`).** The empty path-vector `[]` is the root path: the `:before` focuses the **whole** app-db (`(get-in db []) = db`) as the handler's `:db` coeffect, and rule 5's widen **replaces** the whole app-db with the emitted value (`(assoc-in db [] x)` is ill-defined, so the root case is special-cased to install the emitted value directly). Rules 3 and 4 still hold — a handler emitting no `:db` writes nothing, and an `identical?`-to-original emitted value re-emits the original app-db object. This `[]` semantics mirrors the `:rf/path` algebra's root-path laws (`get(s, []) = s`, `put(s, [], x) = x`; [Conventions §The `:rf/path` algebra](Conventions.md#the-rfpath-algebra)); it is impl-defined and test-pinned but not otherwise spec-mandated.

**Rule 4 is normative.** It preserves the frame-commit `identical?` no-op optimization (per [§One physical container, two projection reactions](#one-physical-container-two-projection-reactions) and the commit no-op family documented in [EP-0018 §Commit / no-op family](../docs/EP/EP-0018-one-event-registration.md)): when an event returns the same app-db object, the runtime skips the container write and the projection reactions do not propagate. A naive widen — `(assoc-in original-db path unchanged-slice)` — allocates a new top-level map, defeating the `identical?` check even though the handler did no real work. Because the standard path interceptor knows both the original full app-db object and the original focused slice, an unchanged focused slice widens back to the original app-db object, keeping the no-op commit no-op. `path` is a framework-standard interceptor (an app copy is likely to preserve value equality but miss the identity fast path).

A non-vector or otherwise malformed `path-vector` argument is `:rf.error/path-interceptor-bad-path`.

### No standard `unwrap`

There is **no** standard `unwrap` interceptor (EP-0022 §No standard `unwrap`). The same local ergonomics are available through ordinary handler destructuring, which keeps the `:event` coeffect **stable** as the original dispatched event vector throughout the chain — and stability matters for tracing, replay, diagnostics, and other interceptors:

```clojure
(rf/reg-event :cart/add
  (fn [{:keys [db]} [_ {:keys [sku qty]}]]
    {:db (add-cart-line db sku qty)}))
```

A project that genuinely wants chain-wide event reshaping registers its own interceptor (`(rf/reg-interceptor :app/unwrap {:doc "…"} {:before … :after …})`); it does not need to be a framework standard. (The retirement of the v1 `unwrap` / `trim-v` standard helpers is catalogued in [API.md §Standard interceptors](API.md#standard-interceptors) and [MIGRATION §M-21](../migration/from-re-frame-v1/README.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors).)

### Images carry interceptor descriptors

An image's registration set may carry interceptor descriptors, so an image owns interceptors exactly as it owns events and subs ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md)). A registration set declares its interceptor alongside the events that reference it:

```clojure
(rf/reg-interceptor :cart/auth-required
  {:doc "Cart auth gate."}
  {:before require-cart-auth})

(rf/reg-event :cart/add
  {:interceptors [:cart/auth-required
                  [:rf.interceptor/path [:cart]]]}
  cart-add)
```

An image built over these namespaces (via `:select-ns`, or by listing them as inline `:registrations`) carries both members; the resolved image generation a frame runs validates the refs at assembly. This keeps the image-as-named-members rule intact: the registration set contains named members, not anonymous runtime objects embedded in other members.

### Tooling and metadata

`handler-meta` for an event exposes its authored interceptor **refs** (not resolved values):

```clojure
(rf/handler-meta :event :cart/add)
;; => {:interceptors [:auth/required [:rf.interceptor/path [:cart]]] ...}
```

`handler-meta :interceptor` exposes a referenced interceptor's own metadata and source coordinate ([001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)). Trace / Xray surfaces SHOULD distinguish: authored refs; the resolved executable chain; per-frame override substitutions; per-call override substitutions; removed refs; and missing-ref failures (per [009 §Instrumentation](009-Instrumentation.md)).

<a id="interceptor-error-model"></a>

### Error model

The structured-error sites for registered interceptors. These land on the always-on / dev surfaces with the runtime slice (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)); they are catalogued here so the contract is pinned at spec time:

| Error | Meaning |
|---|---|
| `:rf.error/invalid-interceptor` | `reg-interceptor` received a malformed descriptor (owned by [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)). |
| `:rf.error/unregistered-interceptor` | A chain references an interceptor id not present in the registrar. |
| `:rf.error/invalid-interceptor-ref` | A chain entry is neither a keyword id nor `[id arg]`. |
| `:rf.error/inline-interceptor-removed` | A public chain contains an interceptor map / value / Var. |
| `:rf.error/interceptor-override-invalid` | An override map contains a malformed key or replacement. |
| `:rf.error/interceptor-factory-arity` | A parameterized ref targets a non-factory interceptor, or a factory cannot build for the arg. |
| `:rf.error/path-interceptor-bad-path` | `:rf.interceptor/path` received a non-vector or malformed path argument. |

## State machines are just event handlers

The drain semantics above were motivated by actor-style machine composition. The unifying insight:

> **A state machine has the same contract as an event handler.** Given current state + an event, it produces new state + effects — exactly what `reg-event` is. A machine is an event handler whose *body* happens to be a transition-table interpreter.

Machines therefore reuse the existing event registry, dispatch pipeline, and effect substrate. Locating machine snapshots in the frame's **runtime-db** partition (rather than in a parallel substrate or in app-db) is what makes machine state inherit [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) for free — runtime-db is part of the one frame-state container, so a frame-state rewind walks snapshots back atomically; spawn-time registrations live in the **frame-local** tier of the two-tier registry (per [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)). The two tiers — **central** (process-global, shared across frames; populated by namespace-load `reg-*` calls) and **frame-local** (per-frame, populated by spawn-time registrations, and revertible as part of the frame value — a frame-state rewind to a prior settled epoch restores its `[:rf.runtime/machines :snapshots …]` registrations along with the rest of frame-state) — are defined in [000-Vision §Frame state revertibility](000-Vision.md#frame-state-revertibility). The foundation hooks defined here are:

- A registered event handler whose body comes from `make-machine-handler` *is* the machine. Tools filter by the `:rf/machine?` metadata exposed in `(handler-meta :event <id>)` to enumerate machines.
- Snapshots live at the **reserved per-frame path `[:rf.runtime/machines :snapshots <machine-id>]`** in each frame's **runtime-db** (see [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live)). The shape is `{:state ... :data ...}`: `:state` is the discrete FSM-keyword; `:data` is the machine's extended state (the term used in FSM literature and `gen_statem`; xstate calls it "context"). **Per-frame isolation is automatic** — each frame's runtime-db has its own `:rf.runtime/machines` map, so the same machine id can exist in multiple frames without collision; their snapshots live in each frame's own `[:rf.runtime/machines :snapshots]`. Because `:rf/machine` reads from the active frame's runtime-db, per-frame isolation extends transparently to subscription reads as well.
- Reads happen through the framework-registered parametric sub `:rf/machine`. `@(rf/subscribe [:rf/machine <machine-id>])` resolves on the surrounding frame and reads from that frame's `[:rf.runtime/machines :snapshots <id>]`. See [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub).
- Two thin helpers: `(machine-transition definition snapshot event) → [next-snapshot effects]` (pure, JVM-runnable) and `(make-machine-handler spec) → fn` (a *pure factory* — no registration side effects, no global-state lookups, no self-id capture; the returned fn is suitable as a `reg-event` body).
- One reserved machine-internal fx-id (`:raise`) the machine handler routes locally inside the action's returned `:fx` vector; the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` are registered globally and reach the standard `do-fx` resolver like any other fx.
- Inspection trace events for machine lifecycle/transition (`:rf.machine.lifecycle/created`, `:rf.machine/transition`, `:rf.machine/snapshot-updated`, etc.) ride the standard trace stream — discriminated by their `:rf.machine.*` `:operation` keyword. Machine-emitted dispatches additionally carry `:source :machine-action` on the envelope (the actor-message path).
- Composition via ordinary `dispatch`. Run-to-completion drain guarantees deterministic settling within a frame.
- A frame is the actor-system boundary; cross-frame dispatch is async (per the no-cross-frame-drain rule above).

Full design — three-way conceptual split, snapshot shape, transition-table grammar, drain semantics across the four nested levels, spawn lifecycle, testing pyramid, library packaging — lives in [005-StateMachines.md](005-StateMachines.md).

### Interop layer — clock primitives — see Spec 005

Clock primitives (`now-ms`, `schedule-after!`, `cancel-scheduled!`) live in `re-frame.interop` and are owned by [005 §Clock abstraction](005-StateMachines.md#clock-abstraction) — they are a substrate concern shared by `:after` transitions, `:dispatch-later`, and any future timing-sensitive feature, not a frame concern. The standard `:dispatch-later` fx delegates to the same primitives so tests can swap the clock at the namespace level.

## Interaction with libraries

Library authors **do not need to know about frames** if they only register handlers and interceptors:

- **re-frame-undo** registers an interceptor that records pre/post `db` snapshots. When the interceptor runs, the context's `:db` is whichever frame's `app-db` is in play; undo state lives at some path inside *that* frame's app-db. Each frame ends up with its own independent undo history. The library does no extra work.
- **re-frame-async-flow** schedules events via the standard `:dispatch` effect; frame propagation is automatic per the rule above.
- **re-pressed**, **re-frame-http-fx**, etc. — same story, provided their fx implementations use the standard dispatch effect or capture a frame-locked dispatch op via `(:dispatch (rf/capture-frame))`.

Authors of fx that escape into async land *do* have to forward the frame — either by capturing `(rf/capture-frame)` inside the binary handler body or by threading `{:frame frame}` through every callback's dispatch. This is a small, well-defined obligation; documented in [§Async effects and frame propagation](#async-effects-and-frame-propagation) and as required rule M-51 in [MIGRATION.md](../migration/from-re-frame-v1/README.md#m-51-reg-fx-handlers-are-binary--rewrite-unary-handlers-to-take-an-unused-first-arg).

## Tooling and agent-amenability

### The public registrar query API

re-frame2 commits to a queryable public registrar for every kind of registered entity (frames, events, subs, fx, cofx, views, interceptors). [Goal 10 (Strong introspection surface)](000-Vision.md#goals) says this is first-class. **The contract for registry queries (`registrations`, `handler-meta`, `frame-ids`, `frame-meta`) is owned by [001 §The query API](001-Registration.md#the-query-api).** The table below restates that surface alongside the frame-runtime queries (`app-db-value`, `snapshot-of`, `sub-topology`, `sub-cache`) that 002 owns:

| Query | Returns | JVM-runnable? |
|---|---|---|
| `(rf/registrations kind)` | Map of id → metadata for every handler of the given kind. The kind keyword set is canonicalised in [001 §The query API](001-Registration.md#the-query-api): `:event` (every `reg-event` handler), `:sub`, `:fx`, `:cofx`, `:interceptor` (every `reg-interceptor` handler — per [EP-0022](../docs/EP/EP-0022-registered-interceptors.md)), `:view`, `:frame`, `:route`. Machines themselves register under `:event` (per [005](005-StateMachines.md)) — filter by `:rf/machine?` metadata to enumerate them. Machine guards and actions are **machine-scoped** (declared in each machine's `:guards` / `:actions` map) — there is no `:machine-guard` / `:machine-action` registry kind. App-db schemas are **not** a registrar kind either — introspect via `schemas/app-schemas` / `schemas/app-schema-meta-at`. | Yes |
| `(rf/registrations kind pred-fn)` | Same, filtered by `pred-fn` applied to each metadata map. | Yes |
| `(rf/handler-meta kind id)` | Metadata for a single handler (config, source coords, doc, spec, etc.). | Yes |
| `(rf/frame-ids)` | Seq of all registered frame keywords. | Yes |
| `(rf/frame-ids prefix)` | Seq filtered by namespace prefix (e.g., `(rf/frame-ids :story)` returns all `:story.*` frames). | Yes |
| `(rf/frame-meta id)` | Metadata for a single frame (config, source coords, lifecycle, doc, override maps, interceptor list). | Yes |
| `(rf/app-db-value id)` | Current **app-db** partition value (a plain map) for the named frame. Returns nil if the frame is not registered. | Yes |
| `(rf/runtime-db-value id)` | Current **runtime-db** partition value for the named frame (framework-owned subsystem state). Returns nil if the frame is not registered. Tooling / privileged-runtime read. | Yes |
| `(rf/frame-state-value id)` | The coherent **frame-state** projection `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. Returns nil if the frame is not registered. The full-frame read for SSR / epoch / time-travel / Xray. | Yes |
| `(rf/snapshot-of path)` / `(rf/snapshot-of path opts)` | Snapshot value at a path in a frame's `app-db` (convenience over `(get-in (rf/app-db-value frame-id) path)`). Machine snapshots are NOT here — they live in runtime-db at `[:rf.runtime/machines :snapshots <id>]`; read them via `[:rf/machine <id>]` / `runtime-db-value`. One-arg form resolves the frame from the surrounding *scope* (and fails with `:rf.error/no-frame-context` outside any scope — it does not default to `:rf/default`); two-arg accepts `{:frame frame-id}`. | Yes |
| `(rf/sub-topology)` | **Static** dependency graph over the registrar: a map of `sub-id → {:input-kind <kind>, :inputs <inputs>, :doc, :ns/:line/:file}`. `:input-kind` is `:db` / `:static` / `:parametric`; `:inputs` carries the literal `:<-` input query vectors for `:static`, `[]` for `:db`, and `:parametric` for an `input-fn` sub (whose realized edge set is not statically enumerable — realized edges per concrete query vector live in `sub-cache`). Pure data derived from the registrar at registration time. | Yes |
| `(rf/sub-cache id)` | **Runtime** cache state for a frame: which subs are currently materialised, their current cached values, dependent components if any. Requires the reactive runtime. | **No** — CLJS-only |

Most queries are JVM-runnable because they read from the registrar (which is data) and from `app-db` (which is data). One query is not, and the table marks it: `sub-cache` reads runtime state from the reactive substrate (currently Reagent-specific). Static topology and snapshot reads stay pure-data.

The metadata maps returned by `handler-meta` and `frame-meta` follow a documented shape — see [001 §Registration grammar](001-Registration.md#registration-grammar) for handler metadata, and [§reg-frame is atomic](#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar) above for frame metadata. Tools (10x, re-frame-pair, agents, story tools) read these and present them however they want.

### Per-frame and trace surface

- **Per-frame app-db inspection** — covered by `app-db-value` above.
- **Trace per frame.** Each frame owns its own cascade-keyed trace ring. Trace events emitted inside an in-flight cascade route to the frame whose router / reactive substrate / view wrapper is running — they never cross into sibling frames. Each frame's ring is sized independently via `:rf.trace/cascades-retained` (default 50; per-frame override on `reg-frame`); `(rf/trace-buffer frame-id)` reads cascade bundles from that frame's ring; cross-frame consumers (pair tools, multi-frame story sessions) merge by `:dispatch-id` across rings. Frameless emits stream live to listeners only and bypass every ring. See [Spec 009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-cascade-keyed-dev-only) for the full contract.
- **Hot-reload notifications.** `reg-frame`/`reg-event`/etc. re-registration fires notifications on a re-frame-internal pub/sub that tools can listen to and refresh their state. Hot-reload re-emits are deduplicated by shape — unchanged re-registrations do not fire a trace event; only shape changes (handler-fn identity or metadata content) emit. The dedup table is process-scoped and dev-only.

## Story-tool foundation hooks — see Spec 007

Stories/variants/workspaces consume foundation primitives this Spec defines (frames per variant, per-frame fx/interceptor overrides, `make-frame` for per-mount isolation, the registrar query API). The story-tool surface lives in [007-Stories.md](007-Stories.md); 002 owns the foundation it consumes.

## Migration

See [MIGRATION.md](../migration/from-re-frame-v1/README.md) for the migration rules. Single-frame apps need no changes; private-namespace access (`re-frame.db/app-db` etc.) breaks; everything else is additive opt-in.

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): "Event-id collisions on re-registration", "Sub-cache invalidation across frames", "Concurrent React rendering", "Sub-cache disposal on frame destroy" classify as **`:still-blocking`** for design polish (file a bead to drive each decision); "Transducer-shaped event processing" classifies as **`:post-v1 tracked`** (already tracked at). The Frame-presets `(RESOLVED)` entry that previously lived here was migrated to `## Resolved decisions` per SA-4's migration rule.

### Event-id collisions on re-registration

Hot-reloading the same handler under the same id is normal and expected. But re-registering the same id with a *different* handler function — accidentally, e.g. two namespaces colliding — is silent last-write-wins. Should re-frame2 warn at registration time when an id is being re-registered with a function whose source coords don't match the previous registration? Probably yes, with a configurable threshold.

### Sub-cache invalidation across frames

If two frames depend on a shared piece of *registry* state (handler definitions), and a sub is hot-reloaded, both frames' caches need invalidation for any cached reactives derived from that sub. Mechanism: registry change fires a notification that frame sub-caches subscribe to. Detail-level design; flagged here so it is not forgotten.

### Concurrent React rendering

React 19's concurrent rendering can render the same component multiple times before committing. `reg-view`'s injected `dispatch` is a value, so it survives this fine. But any `dispatch` *executed during render* (Form-2's outer fn, render-time setup patterns) may run more than once. Confirm with the substrate (Reagent today; possibly UIx tomorrow) and document.

### Sub-cache disposal on frame destroy

When `destroy-frame!` runs, every cached reactive needs its `dispose!`-equivalent called. With Reagent reactions today, this is direct. With a future substrate-agnostic substrate, the disposal contract becomes part of the adapter API. Flagged so the adapter-layer design includes it.

### Transducer-shaped event processing (substrate-agnostic router)

> **Status: post-v1 deferred — v1.1 design pass landed.** v1 ships the existing drain loop; the Spec-level design pass on a transducer-shaped router lives at [Design-TransducerRouter.md](Design-TransducerRouter.md) with a Phase-1 reference scaffold at `implementation/core/src/re_frame/router_transducer.cljc`. The design is non-normative for v1 — the runtime does **not** consume the scaffold yet. Tracked in.

pure-frame implements event processing as a transducer parameterised by the frame: `(frame-transducer-factory frame) → transducer`, with the reducing function determining how state flows (sync, queued, batch). The transducer captures the per-event step (resolve handler → run interceptor pipeline → produce new state); the reducing function decides how successive states are accumulated and committed. The full v1.1 design — primitive contract, reducing-function presets (`sync-rf` / `queued-rf` / `batch-rf`), driver model, two-stage compatibility plan with the v1 drain loop, and interactions with Specs 005 / 009 / 011 / 012 — lives at [Design-TransducerRouter.md](Design-TransducerRouter.md).

Originally flagged as worth considering for v1. A transducer-shaped router is reusable, testable, and extensible without exposing rendering or scheduling primitives at the public API — but the design pass is non-trivial, so the call for v1 was to keep the drain loop and revisit the transducer formulation post-v1. On the v1.1 re-examination, the suspected overlap with [012-Routing.md](012-Routing.md) (URL routing) was non-existent — the two specs live on orthogonal axes.

## Resolved decisions

A pointer-only index of decisions taken in this Spec. Each entry's load-bearing prose lives in the linked section above (or in the linked sibling Spec).

| Decision | Pointer |
|---|---|
| Unified frame identity & lifecycle (EP-0024, accepted 2026-06-18) — **one live frame value backed by one registry** (it owns the id, both partitions, runtime subsystems, queue/drain, caches, lifecycle hooks, and the **resolved image generation** — no second backing-record registry); the **frame id is the public routing address** (`{:frame id}` / `with-frame`), the frame value is a lifecycle token with a hidden representation read via **one id accessor**; **scope vs ensure vs own vs carry** are separate jobs — `with-frame` (lexical) / `frame-provider {:frame …}` (into a React subtree) scope to an *existing* frame (no lifecycle; **amended** — fails loud if absent), `frame-provider {:id …}` is the per-adapter **ENSURE** boundary (create-if-absent / reuse-no-reseed / provide id / **no destroy-on-unmount** — **amended**, owned destroy-on-unmount retired), explicit `make-frame` + `destroy-frame!` owns teardown, `capture-frame` carries across async; **one `make-frame`** over image-selection + record-config opts (the old two-constructor split + `:rf.error/make-frame-record-only-key` redirect are gone); **duplicate-id is idempotent replacement** (behaviour change from blanket fail-loud — preserves durable state on re-mount, irreconcilable conflicts still fail loud); `frame-bound-fn`/`frame-bound-fn*`/`make-capture-frame`/`subscribe*`/frame-first arities retiered to internal; **one teardown ownership path** | [§Per-instance frames — `make-frame`](#per-instance-frames--make-frame-the-ep-0023-object-constructor), [§What `frame-provider` is](#the-merged-config-shaped-frame-provider-cljs-reference), [§Destroy](#destroy), [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md) |
| Frame target resolution — the carried invariant (EP-0002) — frame identity is **carried, not found**: it travels with every causal token as one canonical **frame stamp**, read via the **scope / hold / override** triad (no ambient priority-list, no `:rf/default` floor); absence is `:rf.error/no-frame-context` (emitted always-on, with capture-site ancestry); `hold` is the primary async-safe carrier, `scope` is sync sugar; `:rf/default` is an ordinary id the runtime never infers; **strict embedded core vs tiered interactive discovery** (Tool-Pair keeps tier-3 unique resolution) reconciled into one **absent → ambiguous → unselected** ladder; rationale leads with **replay determinism** | [§Frame target resolution](#frame-target-resolution--the-carried-invariant), [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md), [Tool-Pair](Tool-Pair.md) |
| Two-partition frame contract (EP-0001, 14 rulings) — a frame owns user **app-db** (`:db`) + framework **runtime-db** (`:rf.db/runtime`), held as ONE physical frame-state container with app-db/runtime-db projection reactions; an ordinary `:db` return replaces only app-db; `:rf.db/runtime` reserved by convention (not a security boundary); both whole-value and operation-style runtime writes; partition-aware invalidation falls out of projection-equality (no dirty flags); `reset-frame!` resets the whole frame; accessors `app-db-value`/`runtime-db-value`/`frame-state-value`, mutators `replace-app-db!`/`replace-runtime-db!`/`replace-frame-state!` | [§The two-partition frame contract](#the-two-partition-frame-contract), [Conventions §The two-partition frame contract](Conventions.md#the-two-partition-frame-contract) |
| Frame presets — closed v1 set `:default` / `:test` / `:story` / `:ssr-server`; expansion is `(merge expansion user-supplied-metadata)` with user keys winning on conflict; adding a fifth preset is a Spec-change-only operation; `:devcards` (subsumed by `:story`), `:repl` (subsumed by `:default`), `:replay` (too coupled to Tool-Pair to stabilise) considered and not adopted in v1 | [§Frame presets](#frame-presets--capability-bundles-for-common-configurations) |
| `reg-frame` re-registration is a surgical update by default; `reset-frame!` is the opt-in full replace; `destroy-frame!` removes from registry | [§Re-registration — surgical update](#re-registration--surgical-update), [§reset-frame! — full replace, opt-in](#reset-frame--full-replace-opt-in) |
| `reg-frame` takes no `:db` config — frames always start with `app-db = {}`; initialisation runs through `:initial-events` (seed app-db via a leading `[:rf/set-db {…}]` step) | [§reg-frame is atomic](#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar) |
| Frame-aware events outside views use the two-arg dispatch form `(rf/dispatch [:foo] {:frame :todo})`; `dispatch-to` / `dispatch-with` are not shipped | [§Routing: the dispatch envelope](#routing-the-dispatch-envelope) |
| The CLJS reference's `frame-provider` (React context) is an *ergonomic optimisation* atop the pattern-level explicit-frame contract; observable behaviour matches explicit-frame addressing; SSR bypasses context. **EP-0024 (amended — provider collapse)**: ONE merged config-shaped `frame-provider` dispatched on the prop map — `{:frame …}` scopes an *existing* frame into a React subtree (fails loud if absent), `{:id …}` is the per-adapter **ENSURE** boundary (create-if-absent / reuse-no-reseed / provide id / **no destroy-on-unmount**). Owned destroy-on-unmount is retired (zero product consumers); true ownership is explicit `make-frame` + `destroy-frame!`. The pre-amendment name family (the scope-only `frame-provider-existing` + a namespace-safe twin) is fully retired — the `{:frame …}` shape is the one scope-only surface | [§View ergonomics](#view-ergonomics-the-hard-part), [§the merged `frame-provider`](#the-merged-config-shaped-frame-provider-cljs-reference), [011-SSR.md](011-SSR.md) |
| Plain Reagent fns can't read the surrounding `frame-provider`'s frame; an ambient `subscribe`/`dispatch` in one raises `:rf.error/no-frame-context` (EP-0002 — no `:rf/default` fall-through; supersedes the old warn-once) | [004-Views §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-the-footgun-is-now-a-loud-error) |
| Per-instance frames via the one `make-frame` constructor (EP-0024 — image-selection + record-config opts in one call; returns the frame value; id via one accessor) for per-mount lifecycles | [§Per-instance frames — `make-frame`](#per-instance-frames--make-frame-the-ep-0023-object-constructor) |
| Per-frame and per-call overrides via `:fx-overrides`, `:interceptor-overrides`; frame-level `:interceptors` ref chain (per-call additive `:interceptors` removed — EP-0022) | [§Per-frame and per-call overrides](#per-frame-and-per-call-overrides) |
| Registered interceptors (EP-0022): event/frame `:interceptors` carry serializable interceptor **refs** (bare keyword or `[id arg]`), not inline values; `:interceptor-overrides` matches by exact canonical reference (replace with another ref, or `nil` to remove); effective ordering frame-refs → event-refs → handler-wrapper → subsystem dispatch-time interceptors; refs resolve at chain assembly (registration-time + app-value validation; dispatch-time defensive guard); one standard interceptor `[:rf.interceptor/path path-vector]` (the canonical `:factory` consumer) preserving the frame-commit `identical?` no-op; no standard `unwrap` | [§Registered interceptors and the chain grammar](#registered-interceptors-and-the-chain-grammar), [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar) |
| `destroy-frame!` is the single normative teardown boundary every per-feature artefact (flows, machines, schemas, SSR, epoch) hangs its frame-scoped cleanup off; each artefact publishes a teardown hook the core invokes during destroy | [§Destroy](#destroy), [013 §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown) |
| Per-frame trace rings, cascade-keyed retention — each frame owns an independent ring sized by cascade count (`:rf.trace/cascades-retained`, default 50); trace events route to the in-flight frame; frameless events bypass rings and stream live only; hot-reload re-emits dedup by shape | [§What lives in a frame](#what-lives-in-a-frame), [009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-cascade-keyed-dev-only) |
