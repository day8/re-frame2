# Spec 002 — Frames

> **What this Spec is about.** A *frame* is an isolated runtime boundary — multi-instance widget, per-test fixture, per-request server-side render, per-session — all the same shape. The pattern's contract is **explicit-frame addressing**: every dispatch and subscribe targets a specific frame at the call site. The CLJS reference's React-context-driven view injection (in §View ergonomics) is an *ergonomic optimisation* atop that contract, not a pattern-level commitment.
>
> For the bird's-eye view of where the frame container, router, drain loop, and `do-fx` sit in relation to the registrar, sub-cache, substrate adapter, and trace bus, see [Runtime-Architecture](Runtime-Architecture.md).

## Abstract

A **frame** is an **isolated runtime boundary**, identified by keyword, that owns the runtime state of a re-frame application: its `app-db`, its event router/queue, and its subscription cache. Multiple frames can coexist — multi-instance on a page (devcards, isolated widgets, serial test instances), per server-side request, per session — and live independently.

> **Terminology:** "isolated runtime boundary" is the canonical definition. Other Specs sometimes describe a frame in terms of a particular role it plays — *actor-system boundary* (Spec 005, when describing message-passing semantics), *frame contract* (Spec 006, when describing what the substrate-agnostic core requires from an adapter), *per-request runtime* (Spec 011, when describing SSR). All refer to the same thing under different aspects.

All frames share **one global handler registrar**. Multi-frame means "multiple instances of the same app's handlers" — devcards, isolated widgets, story variants, test fixtures — not "multiple different apps with different handler sets on one page." The latter use case (micro-frontends, embedded white-label widgets) is out of scope; iframes already serve it.

**Single-frame is one shape of multi-frame.** A pre-registered `:rf/default` catches every dispatch and subscription that doesn't specify a frame. An app that only ever uses `:rf/default` is a multi-frame app with one frame in play — same runtime, same routing, same drain loop. The default isn't a migration shim; it's the no-ceremony case of the canonical addressing model.

## Goals

This Spec inherits the constraints and goals from 000 and adds two frame-specific design rules:

- **Frame plurality is invisible to single-frame apps.** No new API surfaces in user code unless the user opts in.
- **Frame identity is a value, not a reference.** Frames are addressed by keyword in user code; runtime frame *records* are an internal detail.

## API at a glance

```clojure
;; Registration (lifecycle)
(rf/reg-frame   :todo {:on-create [:todo/initialise]})   ;; create + register, atomic; app-db starts {}
(rf/reg-frame   :todo {:on-create [:todo/initialise]})   ;; against existing — surgical update (config replaced; runtime state preserved)
(rf/reset-frame :todo)                                    ;; explicit full replace — app-db cleared, :on-create re-fires
(rf/destroy-frame :todo)                                  ;; tear down — remove from registry

;; View ergonomics
[rf/frame-provider {:frame :todo}       ;; React context: keyword in, not value
 [todo-list]]
(rf/reg-view counter [label] ,,,)               ;; defn-shape; injects frame-bound `dispatch`/`subscribe`

;; Plain (non-view) APIs — frame-aware variants
(rf/dispatch      [:foo])                          ;; defaults to :rf/default
(rf/dispatch      [:foo] {:frame :todo             ;; opts map extends the dispatch envelope
                          :fx-overrides {:my-app/http stub-fn}})
(rf/dispatch-sync [:foo] {:fx-overrides {...}})    ;; same opts-arg shape, sync variant
(rf/subscribe     [:bar])                          ;; defaults to :rf/default
(rf/subscribe     [:bar] {:frame :todo})           ;; opts arg targets a specific frame

;; Test/REPL helper
(rf/with-frame :todo
  (rf/dispatch-sync [:init])
  @(rf/subscribe [:status]))
```

## What lives in a frame

```clojure
{:id           :todo                    ;; the keyword identifier
 :app-db       <atom>                   ;; this frame's app-db
 :router       {...}                    ;; this frame's event queue/scheduler state
 :sub-cache    {...}                    ;; this frame's signal-graph cache
 :lifecycle    {:created-at <ts>
                :destroyed? false
                :listeners  [...]}
 :config       {...}}                   ;; whatever was passed to `reg-frame`
```

Within `:app-db`, a small number of root keys are runtime-managed (per [Conventions.md §Reserved app-db keys](Conventions.md#reserved-app-db-keys)). The set:

- `[:rf/machines]` — `{<machine-id> <:rf/machine-snapshot>}` for every active machine in this frame (per [005-StateMachines.md §Where snapshots live](005-StateMachines.md#where-snapshots-live)).
- `[:rf/system-ids]` — the per-frame **reverse-index** for `:system-id` named addressing: `{<system-id> <gensym'd-machine-id>}`. Allocated lazily (only present when a spawn binds a name); cleared on destroy. Per [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) and rf2-suue.
- `[:rf/route]` — the route slice for url-bound frames (per [012-Routing.md](012-Routing.md)).
- `[:rf/pending-navigation]` — the pending-navigation slot, populated when a `:can-leave` guard rejects a navigation; cleared by `:rf.route/continue` or `:rf.route/cancel`. Allocated lazily. Per [012 §Navigation blocking — pending-nav protocol](012-Routing.md#navigation-blocking--pending-nav-protocol).

The reserved set is **fixed-and-additive** per [Conventions.md §Reserved app-db keys](Conventions.md#reserved-app-db-keys): names already in the table cannot be repurposed, and new keys are added only by Spec change.

Three observations:

1. **Handlers are not in the frame.** The handler registrar is global, shared across all frames. Frames isolate *state*, not *behaviour*.
2. **The signal graph is per-frame.** Two frames running the same `:total` subscription compute against their own `app-db`s, cache against their own sub-caches; they are independent.
3. **Frames are mutable runtime objects.** They are not values. User code holds keywords; the framework holds frame records.

## Frame lifecycle

### `reg-frame` — atomic create-and-register and the canonical metadata grammar

```clojure
(rf/reg-frame :todo {:on-create [:todo/initialise]})
;; creates a frame record (app-db starts {}), registers it under :todo,
;; dispatch-syncs :todo/initialise into it, returns the keyword.
```

Atomic create-and-register. There is no way to obtain an unregistered frame; this matches the rest of re-frame's `reg-*` family and avoids orphan-frame states. The return value (the registered frame keyword) follows the family-wide [`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

This section is the **canonical grammar** for `reg-frame` metadata. Subsequent sections — [§Re-registration — surgical update](#re-registration--surgical-update), [§Frame presets](#frame-presets--capability-bundles-for-common-configurations), [§Per-instance frames](#per-instance-frames--anonymous-make-frame) — refer to the keys defined here; they do not re-define them.

`reg-frame` accepts a metadata map mirroring other registrations:

```clojure
(rf/reg-frame :todo
  {:doc          "..."                          ;; like all reg-*
   :on-create    [:todo/initialise]             ;; single event dispatched after creation
   :on-destroy   [:todo/cleanup]                ;; single event dispatched before teardown
   :fx-overrides {:my-app/http http-stub-fn}    ;; per-frame fx replacements
   :interceptors [recorder validator]           ;; prepended to every event in this frame
   :drain-depth  100                            ;; depth limit for run-to-completion drain
   :on-error     :rf.error/server-projection    ;; error-handler policy per [009 §Error-handler policy](009-Instrumentation.md#error-handler-policy-on-error-per-frame); typically preset-supplied (e.g. `:ssr-server`)
   :platform     :server                        ;; active platform for this frame per [011-SSR.md](011-SSR.md); typically preset-supplied
   :ns :line :file})                            ;; auto-supplied
```

The full set of metadata keys — `:doc`, `:on-create`, `:on-destroy`, `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:drain-depth`, `:on-error`, `:platform`, plus the auto-supplied `:ns`/`:line`/`:file` — is the canonical surface; the `:rf/frame-meta` schema in [Spec-Schemas](Spec-Schemas.md#rfframe-meta) is the normative reference. `:on-error` and `:platform` are framework-supplied via presets in the v1 closed set (`:ssr-server` wires both); user code may set them directly for non-preset configurations.

**Frames always start with `app-db = {}`.** There is no `:db` config key — initialisation happens via the `:on-create` event. This keeps "events are the unit of state change" as a single, consistent mechanism: the initial state is built by the same dispatch pipeline that handles all subsequent state changes.

`:on-create` accepts a **single** event vector. The framework dispatch-syncs it into the freshly-created frame, draining to fixed point per run-to-completion. By the time `reg-frame` returns, the cascade has settled and `app-db` is in whatever state the cascade produced.

If the frame's initialisation needs to fire multiple events, the single `:on-create` event's handler does so via its effect map:

```clojure
(rf/reg-event-fx :todo/initialise
  (fn [{:keys [db]}]
    {:db (assoc db :items [] :status :idle)
     :fx [[:dispatch [:todo/restore-session]]
          [:dispatch [:todo/load-preferences]]]}))
```

`:on-destroy` is symmetric: a single event dispatched before teardown.

The framework stamps the dispatch envelope with the frame's id automatically — the user doesn't write `dispatch` or specify `:frame`. If the event handler needs the frame-id at runtime, it reads `(:frame m)` from its context.

[Spec 007 — Stories](007-Stories.md) builds on this for variants but uses its own multi-event setup sequence (not desugared to `:on-create`, which is single-event by design).

**`reg-frame` / `make-frame` called from inside a handler.** `reg-frame` is normally called at namespace load time, but the spec does not forbid calling it (or `make-frame`) from inside an event handler — a handler may legitimately spawn a child frame mid-cascade. When this happens, the `:on-create` event is **not** dispatch-sync'd into the new frame (per [§dispatch-sync inside a handler is an error](#calling-dispatch-sync-inside-a-handler-is-an-error)); instead it is dispatched (async-via-router-queue) into the new frame. The new frame's queue picks up `:on-create` on its own next-tick drain. By the time the *creating* handler's outer cascade settles, the child frame's `:on-create` has either drained or been queued for its own drain — the two drains do not interleave (per the no-cross-frame-drain rule in [§Run-to-completion](#run-to-completion-dispatch-drain-semantics)).

### Destroy

```clojure
(rf/destroy-frame :todo)
```

- Drops the frame from the registry.
- Disposes the sub-cache (each cached reactive is torn down so nothing leaks listeners).
- Stops the router.
- Fires `:on-destroy` events before teardown if specified.
- Subsequent `(dispatch [...] {:frame :todo})` / `(subscribe [...] {:frame :todo})` to a destroyed frame throws a clear, machine-readable error: `{:reason :frame-destroyed :frame :todo}`.
- Tool-Pair surfaces against the destroyed frame route off their own contract (read returns empty / `nil`; mutate raises `:rf.error/no-such-handler` (kind `:frame`); listener silencing emits a one-shot trace) — see [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames).

### Re-registration — surgical update

`reg-frame` against an already-registered keyword performs a **surgical update**: existing runtime state (`app-db`, sub-cache, router queue, in-flight events) is preserved; only the metadata/config is replaced. This is what makes hot-reload Just Work — figwheel/shadow-cljs recompile triggers re-evaluation of `reg-frame` forms, the page doesn't blink, the user's state survives. The contract for re-registration of every other registry kind (events, subs, fx, cofx, machine actions/guards, views, routes, heads, error projectors) is owned by [001 §Hot-reload semantics](001-Registration.md#hot-reload-semantics).

**What gets replaced on surgical update:**

- `:fx-overrides` map — applied to envelopes built *after* re-registration.
- `:interceptor-overrides` map — applied to envelopes built after re-registration.
- `:interceptors` vector — applied to events handled after re-registration.
- `:doc`, `:ns`/`:line`/`:file` metadata.
- `:drain-depth` — applied to subsequent drains.
- `:on-create` / `:on-destroy` — recorded for future `reset-frame` / `destroy-frame` calls; not re-fired on surgical update.

**What does NOT change on surgical update:**

- The live `app-db` keeps its current value.
- `:on-create` events do not re-fire (they fired on the original creation and don't re-run on re-registration).
- `:on-destroy` events do not fire (they only fire on `destroy-frame`).
- Sub-cache, router queue, in-flight events all remain.

**Absent-key semantics on re-registration:** the re-registered metadata map is the **complete replacement** of the previous map's replaceable slots, *not* a merge. A key absent from the new map clears the previous binding; a key present overwrites. So if the original `reg-frame` set `:fx-overrides {:my-app/http stub-fn}` and the re-registration omits `:fx-overrides`, the overrides map clears (no overrides apply going forward). This matches every other `reg-*` shape (re-registering a `reg-event-fx` replaces the handler entirely; metadata behaves the same way), and keeps the on-disk source the single source of truth — the runtime doesn't accumulate state the source no longer mentions. The slots that follow this rule are the same ones listed in *What gets replaced*: `:fx-overrides`, `:interceptor-overrides`, `:interceptors`, `:doc`/`:ns`/`:line`/`:file`, `:drain-depth`, `:on-create`, `:on-destroy`. Live runtime state (`app-db`, sub-cache, queue) is preserved regardless of what the metadata map says.

**Trade-off:** there's some "config drift" between what `reg-frame` literally says and what's running. A developer who edits `:on-create` and re-saves will not see the new init event re-fire — they need to call `reset-frame` to apply it. This matches today's re-frame: `app-db` doesn't reset when you save a file, and developers expect that.

**Trace emission on surgical update.** Each surgical re-registration emits a `:frame/re-registered` trace event (per [009-Instrumentation §Frame lifecycle traces](009-Instrumentation.md#core-fields-required-on-every-event)). The trace fires *after* the metadata swap is visible to subsequent dispatches — a test fixture that asserts "the new `:fx-overrides` are in effect by the time the trace fires" can rely on this ordering. Tools (10x, re-frame-pair) listen for this op to refresh their per-frame state.

**Worked-example gotcha — `:on-destroy` clears on omit.** The absent-key rule above applies to `:on-destroy` too. If the original `reg-frame` set `:on-destroy [:todo/cleanup]` and the developer subsequently edits the source to *remove* the `:on-destroy` key (rather than replace its event vector), the next hot-reload re-registration clears the recorded teardown event. A subsequent `(destroy-frame :todo)` then runs without firing `:todo/cleanup`. This is mostly invisible in production (frames are rarely destroyed) but bites in tests and REPL workflows that destroy frames between cases — a teardown that "used to work" silently stops running after a source edit. The fix is the same as for `:on-create`: re-register with the desired keys present, or call `reset-frame` to re-establish from the current source.

### `reset-frame` — full replace, opt-in

For developers who want a fresh start (a test fixture, an explicit "reset to initial state" action, or a story that re-runs setup on demand):

```clojure
(rf/reset-frame :todo)
```

Equivalent to `(destroy-frame :todo)` followed by `(reg-frame :todo <current-config>)`:

- Existing `app-db` is reset to `{}`.
- Sub-cache is disposed; live subscriptions re-materialise on next deref.
- Router queue is cleared; any unprocessed events are dropped.
- The configured `:on-create` event re-fires as if it were a fresh creation, draining its cascade synchronously.

`reset-frame` is the right tool for "I want this back to its initial state." Tests use it between test cases. Story tools use it for "reset" buttons.

`destroy-frame` (covered above) goes one step further — the frame keyword is removed from the registry; subsequent dispatch/subscribe with that frame throws `:reason :frame-destroyed`.

### `:rf/default`

Registered by re-frame at load time, under the keyword `:rf/default`, as a regular registry entry. No special-casing in the lookup path. Listable in tooling. Overridable by re-registration: a user who really wants different default behaviour calls `(rf/reg-frame :rf/default <metadata>)` like any other frame; the surgical-update rules above apply, the metadata reflects the user-supplied keys, and the runtime emits `:frame/re-registered` so tooling can detect the override. (Rare in practice; the only common case is widening `:drain-depth` for an app-wide debug session.)

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
| `:fx-overrides` | `{:rf.http/managed :rf.http/managed-canned-success}` | The canonical Spec 014 HTTP fx is redirected to its canned-success stub so test frames don't reach the network. Test code that needs richer stubbing (navigation no-ops, etc.) supplies its own `:fx-overrides` per-call or per-frame; the framework does not ship `:rf.test/*` fxs in the v1 closed set. |
| `:drain-depth` | `100` | Explicit value matches the framework default. Surfaced on the expansion so tooling can read "this is a test frame, drain bounded at 100" from `(frame-meta <id>)` without inspecting the global default. |

**Port-omission carve-out.** The `:fx-overrides` entry above redirects a Spec 014 fx-id. Implementations that omit Spec 014 do not register `:rf.http/managed` and therefore cannot redirect it — on such ports the `:test` preset's `:fx-overrides` expansion is `{}` (empty map). The `:drain-depth` entry is unaffected. Conformance: a port that ships Spec 014 MUST expand `:test`'s `:fx-overrides` to the exact pair above; a port that omits Spec 014 MUST expand it to `{}`. Either way, user-supplied metadata wins on conflict per [§Expansion algorithm](#expansion-algorithm).

**Clock stubbing is host-interop, not preset-level.** Tests that need deterministic time replace the interop layer's `now-ms` provider (per [§Interop layer — clock primitives](#interop-layer--clock-primitives--see-spec-005)) — they do not override an fx-id. Machine `:after` timer wake-ups are not registered as a redirectable fx-id, so `:fx-overrides` cannot reach them; the preset deliberately stays silent on time control.

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
| `:on-error` | `:rf.error/server-projection` | Server-side handler exceptions surface through the dedicated server error projection so the request handler can reconstruct an error response from the trace stream rather than crashing the SSR drain. |

The `:on-create` event is **user-supplied** rather than preset-defaulted. The standard pattern is `(rf/make-frame {:preset :ssr-server :on-create [:rf/server-init request]})` — the user owns the init event so the request payload can be threaded through (see [011-SSR](011-SSR.md)). The framework does not ship a `:rf/server-init` handler.

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

This is a deliberate constraint — it prevents preset proliferation and makes the four presets canonical for AI scaffolding (an AI reading the spec sees the closed set and chooses from it).

#### `:preset` works on `make-frame` too

Anonymous frames (per [§Per-instance frames](#per-instance-frames--anonymous-make-frame)) accept `:preset` in their opts map identically:

```clojure
(rf/make-frame {:preset :test
                :on-create [:counter/init]})
;; → :rf.frame/<gensym>  with the :test preset's expansion applied
```

Symmetric with `reg-frame`; same expansion algorithm; same conflict-resolution rule.

### Per-instance frames — anonymous `make-frame`

Some use cases need a frame *per mount* rather than a named singleton — devcards, modal stacks, multiple live instances of a `[counter-widget]`, dynamic tabs. The keyword-identity scheme would make per-mount unique IDs awkward without a helper, so re-frame2 ships `make-frame` alongside `reg-frame`:

```clojure
(rf/make-frame opts) → :rf.frame/123     ;; gensyms a unique keyword, registers, returns it
(rf/destroy-frame :rf.frame/123)         ;; same destroy as named frames

(rf/reg-event-db :counter/init (fn [_ _] {:count 0}))    ;; init event registered once

(defn counter-widget [label]
  (r/with-let [f (rf/make-frame {:on-create [:counter/init]})]
    [rf/frame-provider {:frame f}
     [counter-view label]]
    (finally
      (rf/destroy-frame f))))
```

`make-frame` shares the `reg-frame` code path; the only difference is the generated keyword (with a `:rf.frame/` namespace to avoid colliding with user-chosen names). The naming pun parallels `gensym` vs. explicit symbols. Lifecycle is the user's responsibility — pair `make-frame` with a `destroy-frame` in `:finally` of `r/with-let` (or equivalent unmount hook).

Tests use this pattern as their fixture lifecycle:

```clojure
(rf/reg-event-db :auth/init-idle (fn [_ _] {:auth/state :idle}))

(deftest auth-flow
  (let [f (rf/make-frame {:on-create [:auth/init-idle]})]
    (try
      (rf/dispatch-sync [:auth/login-pressed] {:frame f})
      (is (= :validating (get-in (rf/get-frame-db f) [:auth :state])))
      (finally
        (rf/destroy-frame f)))))
```

## Routing: the dispatch envelope

The mechanism that gets a dispatch to the right frame is **frame identity carried on the in-flight event**.

User-facing event shape is a vector — `[:add-todo "milk"]` — id-first, polymorphic on the head keyword. The **canonical call shapes** are:

| Arity | Canonical | Tolerated (discouraged) |
|---|---|---|
| Trivial — id only | `[:counter/inc]` | (same) |
| Single argument | `[:user-by-id 42]` | (same) |
| Multi-argument | `[:user/login {:email e :password p}]` (single map payload) | `[:user/login e p]` (multi-positional; linter nudges) |

The hybrid `[<id> <map>]` shape for non-trivial events is canonical. Subscribe takes the same shape (`[:items-filtered {:status :pending :limit 20}]`). The full rationale is in [Principles §Name over place](Principles.md#name-over-place); the migration rule for v1 multi-positional code is [MIGRATION §M-19](MIGRATION.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in). The v1 `unwrap` interceptor (which required this exact `[event-id payload-map]` shape) ships in v2 as opt-in handler-side sugar; v1 `trim-v` is dropped because its purpose was the multi-positional form v2 leaves behind.

*Internally*, every dispatch becomes a **dispatch envelope**:

```clojure
{:event        [:add-todo "milk"]      ;; the user-facing vector, unchanged
 :frame        :todo                   ;; resolved frame keyword
 :fx-overrides {:my-app/http stub-fn}  ;; per-dispatch fx replacements (master's dispatch-with)
 :trace-id     "..."                   ;; tooling/agent fields
 :source       :ui                     ;; trigger kind — the canonical enum is `:rf/dispatch-envelope`'s `:source` in [Spec-Schemas](Spec-Schemas.md#rfdispatch-envelope) (`:ui :timer :http :machine :repl :ssr-hydration :test :other`)
 :origin       :pair                   ;; actor identity — open vocabulary, defaults to `:app`; e.g. `:pair`, `:claude`, `:story`, `:test`
 :dispatched-at <ts>}
```

The envelope is just a map. Any field can be set by:

- **The two-arg dispatch form** — `(dispatch [:foo] {:frame :todo :fx-overrides {...}})`. The opts map's keys flow into the envelope. `dispatch-sync` takes the same opts arg. The opts map's schema is `:rf/dispatch-opts` in [Spec-Schemas](Spec-Schemas.md#rfdispatch-opts) — a strict subset of the envelope (the runtime supplies `:event` and may add `:dispatched-at`).
- **Frame-level config** — `reg-frame` keys (`:fx-overrides`, `:interceptor-overrides`, etc.) are merged into the envelope by the routing layer when an event is routed to that frame.
- **Lexical injection** — `reg-view`-injected `dispatch` closures carry `:frame` from React context.

The two-arg `dispatch` form is the single mechanism for setting envelope fields per call: `(dispatch event {:frame :todo :fx-overrides {...}})`. Per-event override variants like `dispatch-to`, `dispatch-with`, and `dispatch-sync-with` are not part of the API. Event-vector metadata is not an opt-channel in v2; use the two-arg `(dispatch event opts)` form. (The one v1 metadata case — `^:flush-dom` — is rewritten to `:dispatch-later {:ms 0}`; see [MIGRATION.md §M-16](MIGRATION.md#m-16-flush-dom-event-vector-metadata-removed--replace-with-dispatch-later-ms-0).)

The router reads the envelope's `:frame`, looks up the frame in the registry, and runs the interceptor pipeline against that frame's `app-db`/router context. Handlers receive the same shape they always have (`db`+`event-vec` for `reg-event-db`, context map for `reg-event-fx`); the envelope is not exposed to user handlers.

### How `:frame` gets attached

In priority order, where the frame keyword comes from:

1. **Explicit `:frame` in the dispatch opts map.** `(dispatch [:foo] {:frame :todo})` always wins. The opts map's keys flow straight into the dispatch envelope.
2. **Lexical `dispatch` injected by `reg-view`.** The closure carries the frame keyword resolved from React context at render. (See View Ergonomics, below.) Internally, the injected `dispatch` is `(fn [event] (dispatch event {:frame <captured>}))`.
3. **Dynamic binding.** Inside `(with-frame :todo ...)` (test/REPL helper), a Clojure dynamic var carries the frame; the bare `(rf/dispatch [:foo])` in the body picks it up. This makes `(with-frame :todo (rf/dispatch [:foo]))` Just Work without an opts map. **The router establishes the same binding around every running handler** (per [§Dispatches issued from inside a handler body](#dispatches-issued-from-inside-a-handler-body) below), so a synchronous `(rf/dispatch [:foo])` from inside a handler running on `:todo` also resolves to `:todo` — not `:rf/default`.
4. **Default.** `:rf/default`.

### Dispatches issued from inside a handler body

The router binds the dynamic-var tier of the resolution chain to the in-flight event's `:frame` for the duration of `process-event!`. The contract is:

- **Synchronous dispatch from inside a handler body routes to the handler's frame.** A `reg-event-fx` whose body calls `(rf/dispatch [:child])` and returns `{}` dispatches `:child` to the same frame the parent is running on. The same applies to `(rf/dispatcher)`, `(rf/subscriber)`, and `(rf/current-frame)` — all of which read the dynamic-var tier first.
- **Async callbacks escape the binding.** When a handler defers work via `js/setTimeout`, `js/Promise.then`, `requestAnimationFrame`, or any other host-level async primitive, the deferred callback fires on a fresh stack with no dynamic binding. A bare `(rf/dispatch [:child])` from inside the callback falls through to `:rf/default`. This is a fundamental property of dynamic scope — not a bug.

The three frame-safe affordances for async callbacks are, in canonical-first order:

1. **`:fx [[:dispatch event-vec]]`** — the fx walker (`re-frame.fx/do-fx`) calls `(dispatch! event-vec {:frame frame-id})` with `frame-id` already resolved from the in-flight envelope. The dispatch is synchronous with the enclosing handler's drain, so any timer / promise the user wants to schedule should be modelled as a returned effect, not a manual `js/setTimeout`. This is the canonical multi-frame pattern.

2. **`:fx [[:dispatch-later {:ms <n> :event event-vec}]]`** — the `:dispatch-later` fx captures `frame-id` in its closure before scheduling the timer, so the deferred dispatch carries the correct frame regardless of when the timer fires.

3. **`(rf/dispatcher)`** — captures the active frame at call time and returns a closure `(fn [event] (dispatch event {:frame <captured>}))`. Use when the handler must hand a dispatch fn to a non-fx async library (a websocket subscription, a third-party SDK that takes a callback) where neither `:fx` nor `:dispatch-later` fits. (The earlier `bound-dispatcher` alias was cut under rf2-knz3l; the verb-form name already implies capture-at-call-time semantics.)

The contract is regression-tested by `re-frame.dispatch-frame-capture-cljs-test` (rf2-l5q3). Pattern-LongRunningWork and Pattern-WebSocket both rely on it.

### Dispatch origin tagging

The dispatch opts map accepts an optional `:origin` key — a tag identifying the *actor* that issued the dispatch:

```clojure
(rf/dispatch [:user/login {:email e}] {:origin :pair})
```

`:origin` is unconstrained at the framework level — tools and applications agree on values (`:pair`, `:claude`, `:story`, `:test`, etc.). The value flows into the dispatch envelope and is lifted by the trace surface onto every `:event/dispatched` trace event under `:tags :origin` (per [009 §Origin tagging](009-Instrumentation.md#origin-tagging-origin)). The default when the opt is omitted is `:app`.

Pair-shaped tools and other tooling surfaces set `:origin` to filter their own activity in post-mortem trace views — "show me only the dispatches the pair tool issued during this session" becomes a one-key filter on the trace stream. User application code typically omits the opt; framework code (the SSR boot path, the router, the machine timer) sets it to a runtime-reserved value (`:rf/router`, `:rf/ssr`, etc.) where the distinction is useful.

`:origin` is **distinct from `:source`** (the existing envelope key). `:source` describes the trigger kind — what *woke* the runtime; the canonical enum is `:rf/dispatch-envelope`'s `:source` in [Spec-Schemas](Spec-Schemas.md#rfdispatch-envelope) (`:ui`, `:timer`, `:http`, `:machine`, `:repl`, `:ssr-hydration`, `:test`, `:other`). `:origin` describes the actor identity — *who* issued the dispatch. Both can be set independently; tools commonly set `:origin :pair` and let `:source` default to `:repl`.

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

Naming convention: **unqualified `dispatch`/`subscribe` inside `reg-view` are the frame-bound locals.** Qualified `re-frame.core/dispatch` continues to refer to the global function (defaults to `:rf/default`, also useful at the REPL).

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

The `read-frame-from-context` function is implemented as a tiered lookup, with the dynamic-binding tier and default tier flanking the actual context read. The middle tier — the React-context read — is **substrate-specific** and each adapter publishes its own impl through the `:adapter/current-frame` late-bind hook (per [006 §Frame-provider via React context](006-ReactiveSubstrate.md#frame-provider-via-react-context)). The dynamic-var tier and the `:rf/default` tier are shared.

```clojure
(defonce ^:private frame-context
  (.createContext js/React :rf/default))

;; Reagent (class components, `:contextType` machinery)
(defn- read-frame-from-context-reagent []
  (or *current-frame*                                ;; tier: dynamic var (set by `with-frame`)
      (when-let [cmp (reagent.core/current-component)]
        (let [ctx (.-context cmp)]                   ;; tier: closest enclosing `frame-provider`
          (cond                                      ;; — class-component path: surfaces value
            (keyword? ctx)                  ctx      ;;   only to components whose `:contextType`
            (and (string? ctx) (not= "" ctx)) (keyword ctx))))
      :rf/default))                                  ;; tier: default

;; UIx / Helix (function components, hook-driven)
(defn- read-frame-from-context-fn-component []
  (or *current-frame*                                ;; tier: dynamic var
      (when-some [v (.-_currentValue frame-context)] ;; tier: function-component path —
        (cond                                        ;;   `_currentValue` is what React mutates
          (keyword? v)                  v            ;;   as Provider boundaries are entered /
          (and (string? v) (not= "" v)) (keyword v))) ;;  exited during render. No `(.-context cmp)`
      :rf/default))                                  ;; tier: default
```

How the React-context tier wires up:

1. `frame-provider` is a React Context Provider whose `value` is the **keyword** (`:todo`), not a frame record. The shared context object lives in `re-frame.adapter.context/frame-context`; every adapter (Reagent, UIx, Helix) reads and writes the same `createContext` object, so a tree mixing substrates resolves to a single frame chain.
2. `subscribe` and `dispatch` reach the resolution chain through the `:adapter/current-frame` late-bind hook. The active adapter's namespace registers the hook at load time, so `re-frame.subs` / `re-frame.router` (CLJC) stay free of a static dep on this CLJS-only file.
3. **Reagent's class-component path** (`(.-context cmp)`) is intentionally narrow: Reagent's class-component machinery surfaces context only to components whose `:contextType` matches the context object — that is the wiring `reg-view*` attaches via `{:contextType frame-context}`. Plain Reagent fns lack the `:contextType` and therefore route to `:rf/default`. This narrowness is what makes the `:rf.warning/plain-fn-under-non-default-frame-once` warning [(rf2-d3k3)](#) meaningful.
4. **UIx / Helix's function-component path** (`_currentValue`) reflects the closest enclosing Provider regardless of any class-static metadata, because function components have no `(.-context cmp)` slot. UIx's `use-context` and Helix's `use-context` are both sugar over this read, so subscribe / dispatch and the substrate-native hook agree on the active frame.

The context's value is the **keyword**, not the frame record: each consumer resolves the keyword against the global frame registry on every read, so re-registering a frame (including `:rf/default`) is picked up automatically on next render with no React-side invalidation.

#### Edge cases

- **No `frame-provider` in scope.** Reagent's `(.-context cmp)` returns the React empty default (`#js {}`); the keyword/string check fails and the lookup falls through to `:rf/default`. Function-component substrates read `_currentValue` directly, which equals the createContext default (`:rf/default`).
- **Render fn invoked outside Reagent** (REPL, tests). `reagent.core/current-component` returns `nil`; the React-context tier is skipped. `with-frame` covers tests that need a non-default frame; bare invocations get `:rf/default`.
- **Reagent prop-conversion of named values** (rf2-d4sf). Stock Reagent's `convert-prop-value` (`reagent.impl.template`) stringifies named values when they pass as React props. The canonical user-facing surface (`rf/frame-provider`) sidesteps this by mounting the Provider via Reagent's `:r>` interop head — the props map flows to React as a raw JS object, so `:value :foo/bar` reaches React as the original keyword and the namespace is preserved across the React-context round trip on every adapter. A user who writes `[:> (.-Provider frame-context) {:value :foo}]` directly (raw `:>` interop, not `rf/frame-provider`) still passes through stock Reagent's prop-conversion under the classic adapter: `convert-prop-value` rewrites `:foo` to `"foo"`, and `re-frame.adapter.context/coerce-context-value` rounds the string back to a keyword. Note that `(name kw)` is lossy for namespaced keywords (`(name :auth/main)` → `"main"`); raw-hiccup mounts that need a namespaced frame-id should switch to `rf/frame-provider` or `re-frame.adapter.context/provider-element`.
- **Concurrent rendering.** React 18 may render the same component multiple times before commit. The context read is idempotent — same provider value across re-renders — so this is safe. Closures captured during render hold the keyword by value; re-render produces a new closure with the same keyword. See [§Open questions — Concurrent React rendering](#concurrent-react-rendering).

### View-side details — see Spec 004

Form-1/2/3 component handling, plain Reagent fns and the `(rf/dispatcher)`/`(rf/subscriber)` affordance, and composing registered views across nested `frame-provider`s — all live in [004-Views.md](004-Views.md). 002 owns the frame-side mechanics; 004 owns the view registration surface.

### `bound-fn` for non-callback async closures (CLJS reference)

Sometimes a function created during render isn't a hiccup callback but is invoked later — an async result handler set up inside `r/with-let`, an interval handle, a websocket subscription. For these, `bound-fn` captures the surrounding frame at definition time and re-establishes it when the fn is later invoked:

```clojure
(rf/bound-fn [msg]
  (rf/dispatch [:incoming msg]))    ;; closure carries the captured frame
```

`bound-fn` produces a `(fn ...)` that, when called, runs in a `binding [*current-frame* <captured-frame>]` block — `*current-frame*` is the dynamic-binding tier of the resolution chain (above), so plain `dispatch`/`subscribe` inside the closure pick up the right frame.

The dynamic var (`*current-frame*`) is the primary mechanism for `bound-fn`, `with-frame`, and the router's per-handler binding: these constructs deliberately use the dynamic-binding tier as their definition, so synchronous dispatches inside their bodies pick up the right frame without an explicit `:frame` opt at the call site. Async callbacks that escape the binding (timers, promises, websocket messages) need an explicit hand-off — capture `(rf/dispatcher)` inside the body or thread `{:frame frame}` into the callback.

### Subscriptions composing across the signal graph

`reg-sub` is the **only** sub-registration form in v2. The v1 `reg-sub-raw` escape hatch is not shipped (per [MIGRATION §M-18](MIGRATION.md)); the use cases it covered now have explicit answers in the architecture: non-app-db sources route through Pattern-AsyncEffect and registered fx, lifecycle-bearing reactive computations become state machines (per [005](005-StateMachines.md)), and bridging external reactive sources is the [006](006-ReactiveSubstrate.md) adapter contract's job.

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
(rf/reg-event-fx :load-todo
  (fn [{:keys [db event]}]
    {:fx [[:my-app/http {:url "/todo/1"
                         :on-success [:todo-loaded]}]]}))
```

When `:load-todo` is dispatched in frame `:todo`, the `:my-app/http` effect fires (`:my-app/http` here is a placeholder for a user-supplied fx; the framework ships `:rf.http/managed` — see [014-HTTPRequests](014-HTTPRequests.md)). Some time later, the HTTP machinery dispatches `[:todo-loaded ...]`. **It must dispatch into `:todo`, not `:rf/default`** — otherwise the response lands in the wrong app-db.

The mechanism is symmetric with how event handlers receive their context: **fx handlers receive the same `m` that the originating event handler received**, including `:frame`. Routing follows from explicit data, not implicit state.

#### The binary fx-handler signature

`reg-fx`'s primary signature in re-frame2 is binary:

```clojure
;; re-frame2's standard :dispatch fx, frame-aware
(reg-fx :dispatch
  (fn [m event]
    (rf/dispatch event {:frame (:frame m)})))

;; multiple dispatches are expressed via :fx (nested pairs) — :dispatch-n is deprecated
;; e.g., handler returns:
;;   {:fx [[:dispatch [:event-1]]
;;         [:dispatch [:event-2]]]}
```

`m` is the same map the originating event handler received — same `:db`, `:event`, `:frame`, `:trace-id`, `:source`, plus any cofx. fx handlers ignore the keys they don't care about.

For sync fx that dispatch (or otherwise need to know the frame), the pattern is `(rf/dispatch event {:frame (:frame m)})`.

The runtime needs to resolve fx-handlers against the frame record (for `:fx-overrides`) and to thread the originating envelope through to reserved fxs that queue children (`:dispatch`, `:dispatch-later`, per [§Cascade propagation](#cascade-propagation)). Both reach the fx-handler as fields of `m` (`:frame` is already documented above; the parent envelope is available at `(:envelope m)` for reserved-fx implementations). User fxs typically read only `(:frame m)`; the `(:envelope m)` slot is a runtime-internal handle that the four reserved fx defmethods consume — see [§Drain-loop pseudocode](#drain-loop-pseudocode).

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
- **Pass `:frame` explicitly** in callbacks — `(rf/dispatch ev {:frame frame})` — or capture a frame-bound dispatch fn via `(rf/dispatcher)` inside the binary handler body (where `*current-frame*` is bound to `(:frame m)`). Don't rely on plain `dispatch` in callbacks; the binding is gone.

`(rf/dispatcher)` (capture-at-call-time, used in fx and views) and `bound-fn` (the macro form, used in view callbacks) are the same idea applied at different boundaries: capture the frame at definition time, re-establish it when the closure fires.

### What `frame-provider` is (CLJS reference)

`frame-provider` is the CLJS reference's mechanism for scoping a frame to a subtree. At the **pattern level**, every dispatch and subscribe targets a specific frame — that's the contract. `frame-provider` is a CLJS-specific *ergonomic shortcut*: it puts a frame keyword into React context, so registered views inside the subtree implicitly target that frame without having to thread it through every call.

```clojure
[rf/frame-provider {:frame :todo}
 [counter "Hello"]]
```

A thin wrapper over the rendering library's React context. It puts the **keyword** `:todo` into context, so any `reg-view`-registered descendant resolves to `:todo` at render time.

Implementation skeleton (Reagent flavour):

```clojure
(defonce ^:private frame-context (js/React.createContext :rf/default))

(defn frame-provider [props & children]
  (let [frame (or (:frame props) :rf/default)]
    ;; `:r>` bypasses Reagent's `convert-prop-value`; the props map flows
    ;; to React as a raw JS object. That bypass preserves the namespace
    ;; of namespaced frame keywords (`:tenant/admin`), which stock
    ;; Reagent's `convert-prop-value` would otherwise strip via
    ;; `(name kw)`. Children remain hiccup.
    (into [:r> (.-Provider frame-context) #js {:value frame}] children)))
```

A missing or `nil` `:frame` falls through to `:rf/default` — matches the no-provider case (defensive default). An explicit `(rf/frame-provider {} ...)` is therefore equivalent to no provider at all; tooling-generated trees that elide the prop don't blow up.

`rf/frame-provider` is the canonical user-facing API (rf2-41la); the lower-level `re-frame.views/build-frame-provider` factory remains as the **substrate hook** (per [Spec 006 §`(register-context-provider frame-keyword)`](006-ReactiveSubstrate.md#register-context-provider-frame-keyword--component)) — adapter implementors register a context-provider component through it, and `rf/frame-provider` delegates to whatever the active adapter returned.

Other React-on-CLJS adapters (UIx, Helix) use the same shape with their host's React-context primitive — adapter-style. Other in-scope JS-cross-compile-language ports realise this through their host's React binding's context primitive: TypeScript-React's `React.createContext`, Fable's Feliz / Fable.React `createContext`, PureScript's `React.Basic.Hooks` `createContext`, Kotlin-React's `createContext`, ReasonReact / Melange's `React.createContext`. Mechanism varies by binding; the *contract* — every view targets a specific frame, threaded through a context value carrying the frame-id keyword — survives all of these. See [000-Vision §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic) and the View Ergonomics top-of-section banner above.

## REPL and test ergonomics

### Testing — see Spec 008

The foundation primitives this Spec defines (`make-frame`, `destroy-frame`, `with-frame`, `dispatch-sync` with opts, per-frame and per-call overrides, registrar query API) are what [008-Testing.md](008-Testing.md) composes into the test API: fixture lifecycle, per-test stubbing, headless evaluation, framework adapters. `machine-transition` (defined in [005](005-StateMachines.md)) and `compute-sub` (defined in [008 §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm)) round out the JVM-runnable surface for headless testing; both are referenced here only as pointers.

### Frame-targeted dispatch and subscribe (no provider needed)

Always available, frame-keyword-targeted via the opts arg:

```clojure
(rf/dispatch  [:add-todo "milk"] {:frame :todo})
@(rf/subscribe [:items]          {:frame :todo})
```

These are also the right APIs from non-Reagent contexts (server-side, headless tests, agents). No `dispatch-to` / `subscribe-to` sugar functions exist — the two-arg form is the one mechanism. On the JVM, `subscribe` cannot return a deref-able reactive (no Reagent) — the headless equivalent for "compute a sub against an `app-db` value" is `compute-sub`, defined in [008-Testing §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm). JVM tests typically read `(rf/get-frame-db <id>)` and pass that into `(rf/compute-sub query-v db)`; `subscribe` on the JVM is supported only when the substrate adapter provides a value-shape implementation.

### `with-frame`

A helper macro for tests/REPL that establishes an implicit current frame for a block. It has **two shapes**:

#### Shape 1 — bare keyword (operate on an existing frame)

```clojure
(rf/with-frame :scratch
  (rf/dispatch-sync [:init])
  @(rf/subscribe [:status]))
```

Used when the frame already exists (registered via `reg-frame` or created earlier via `make-frame`). The macro binds the dynamic-frame var for the body's duration; plain `dispatch`/`subscribe` route to `:scratch` via the dynamic-binding tier of the resolution chain. The frame is **not** created or destroyed by the macro.

Use case: REPL sessions, tests that share a fixture across multiple `deftest` blocks.

#### Shape 2 — let-binding (create, use, destroy)

```clojure
(rf/with-frame [f (rf/make-frame {:on-create [:auth/init]})]
  (rf/dispatch-sync [:auth/login])
  (is (= :authenticated (get-in (rf/get-frame-db f) [:auth :state]))))
```

Used when the frame's lifetime is exactly the body. The macro creates the frame from the given expression, binds the resulting frame keyword to the local symbol, runs the body in that frame's dynamic context, and **destroys** the frame on exit (success or exception).

The expression may be `(make-frame opts)`, `(reg-frame :id opts)` (returns the keyword), or any expression returning a frame keyword. The macro destroys whatever was bound on exit.

Use case: per-test fixtures, devcard widgets, REPL sessions where you want a guaranteed clean frame and guaranteed teardown.

#### Discriminator

The macro inspects its first argument:

- Keyword → Shape 1 (bare keyword form).
- Vector `[sym expr]` → Shape 2 (let-binding form, with create-and-destroy).

#### Async work outliving `with-frame`

For async closures that fire after the body returns, capture the frame keyword explicitly via `bound-fn` (above) — the `with-frame` body's dynamic binding has unwound by then. Shape 2's `destroy-frame` runs immediately on body exit; an outstanding async callback that fires after that will hit a destroyed frame.

### `dispatch-sync`

`dispatch-sync` is the entry point for synchronously running an event cascade to completion from *outside* the run-to-completion drain — typically tests, REPL exploration, and event-bootstrapping at app startup. It runs the event through the same RtC drain as `dispatch`; the difference is that the call returns only after the drain settles. Inside an event handler the drain is already running, so calling `dispatch-sync` there is rejected (see "Calling `dispatch-sync` *inside* a handler" below). It accepts the same opts-arg shape as `dispatch`:

```clojure
(rf/dispatch-sync [:foo] {:frame :todo
                          :fx-overrides {:my-app/http stub-fn}})
```

#### Calling `dispatch-sync` *inside* a handler is an error

Under run-to-completion (per [§Run-to-completion dispatch](#run-to-completion-dispatch-drain-semantics)), the cascade is already running synchronously, so `dispatch-sync` from inside a handler conveys no extra meaning over `dispatch`. Calling `dispatch-sync` inside an event handler's interceptor pipeline is **rejected**: the runtime emits `:rf.error/dispatch-sync-in-handler` (per [009 §Error contract](009-Instrumentation.md#error-contract)) and the call is dropped (default recovery `:no-recovery`).

The shape that drains as part of the surrounding cascade is `:fx [[:dispatch event]]` in the effect map. See [MIGRATION.md §M-9](MIGRATION.md) for the migration rule.

#### Cross-frame `dispatch-sync` during a sibling drain warns but proceeds

The same-frame check above is strict: a `dispatch-sync!` against the caller's own frame during its drain is rejected. The cross-frame case is *not* rejected. A `dispatch-sync!` against a **different** frame while the caller's frame is mid-drain interleaves the cascades — frame B runs to settled, then frame A continues. This is intentional (frames are independent state machines per [§Rules rule 1](#rules) — no cross-frame drain), but rarely the caller's intent, so the runtime emits `:rf.warning/cross-frame-dispatch-sync-during-drain` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) so observability tools spot the pattern. The dispatch proceeds; `:recovery :no-recovery`. For fire-and-forget cross-frame coordination prefer the async form `(rf/dispatch event {:frame other})` — it queues on the target frame's router and drains on a later cycle, after the caller's cascade settles. Per rf2-fp97.

### Preserved low-level APIs are per-frame

The router/queue helpers preserved from v1 operate on a *specific* frame's router state. Multi-frame routing made them under-specified in v1; v1.x of re-frame2 locks the rule:

> **Every preserved low-level router helper takes an explicit `frame-id` argument. The zero-arg form targets `:rf/default`.**

`make-restore-fn` is the v1 surface that survives this rule today: it captures a named frame's runtime state (`app-db`, sub-cache snapshot) and returns a closure that restores it. The captured state is per-frame; restoring `:todo`'s state does not touch `:rf/default`. Tests use this for fixture rollback. The single-arg form `(rf/make-restore-fn)` targets `:rf/default`; the explicit-frame form `(rf/make-restore-fn :todo)` targets a specific frame. (The v1 helpers `add-post-event-callback` / `remove-post-event-callback` / `purge-event-queue` are dropped in v2 — see [MIGRATION.md §M-26](MIGRATION.md#m-26-drift-sweep-drops--v1-surfaces-with-no-v2-equivalent-or-absorbed-by-canonical-surfaces).)

**What `make-restore-fn` actually restores.** The captured snapshot is the frame's **value-shape**: `app-db` plus the frame-local registry tier (so a test that spawned dynamic actors during the cascade sees those registrations roll back when the closure runs). The **sub-cache** is *not* snapshotted — restoration clears it, and live subscriptions re-materialise lazily on next deref against the restored `app-db`. This matches the pre-deref behaviour of an unrestored frame: the cache is a derivative of `app-db`, not part of the frame's identity. Headless test code that asserts on subscription values after `(restore!)` should re-deref, not expect cached values from the pre-restore session. (The same constraint applies to substrate-agnostic disposal — see [§Open questions — Sub-cache disposal on frame destroy](#sub-cache-disposal-on-frame-destroy).)

These helpers are not part of the dispatch envelope and don't propagate across frames — they are operational helpers, not data-flow primitives. Tools (10x, re-frame-pair) call them per-frame as part of session management.

## Run-to-completion dispatch (drain semantics)

re-frame2 dispatches **run to completion**: when an external event is processed, every event dispatched (synchronously) during its handler — and every event those handlers dispatch in turn — drains to fixed point before any further external event is processed *for this frame*, and before any view re-renders.

This is the dispatch semantics, not a mode. There is no opt-out. The guarantee gives actor-style machine composition determinism for free ([Spec 005](005-StateMachines.md), when drafted) and removes a class of "flash" intermediate renders that today's async dispatch can cause. It is also load-bearing for [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility): every settled, between-event state of a frame is a snapshottable boundary, and no async mutation escapes the dispatch loop to leave the frame's value inconsistent with its registered handlers.

### Terminology

- **Domain events** — dispatches whose source is the outside world (user input, timer fire, websocket message, REPL). These are the "external events" that drive re-frame.
- **Actor messages** (or just "messages") — dispatches one machine emits to another within a single domain-event's processing. Same `(rf/dispatch [...])` API, distinguished only by the envelope's `:source` field (`:source :machine`) and by naming convention. There is no separate `message` primitive.

The distinction is documentary and conceptual, not technical. One dispatch pipeline, one event shape; "message" is a role a dispatched event plays in a particular context.

### Rules

1. **No cross-frame drain.** Drain runs against the frame's own router queue. A dispatch tagged with a *different* frame goes through the ordinary async path — drain does not span frames. Cross-frame coordination uses regular async `(dispatch ev {:frame other})`.
2. **Every actor message sent during a domain-event's processing drains before the next domain event for that frame.** Once drain is engaged, no further external events are processed for that frame until the cascade settles.
3. **Depth-limited (dynamic), atomic on abort.** The drain enforces a configurable depth limit (`:drain-depth`). When exceeded, drain aborts with a machine-readable error: `{:reason :drain-depth-exceeded :frame :auth :event [...] :depth N}`. The limit is per-frame and runtime-overridable for debugging. **Atomic rollback.** A depth-exceeded drain composes many handlers whose collective effect on `:db` is a partial cascade — the runtime restores `app-db` to its pre-drain snapshot before emitting the error, so no caller observes a state no single completed event would produce. The rolled-back error tag carries `:rollback? true`. The remaining queued events are discarded; epoch history records nothing for the failed drain. Rationale: re-frame's "events are atomic" principle extends to the drain boundary — preserving partial writes from a failed cascade is the worst of both worlds (caller sees inconsistency *and* gets an error). Rolling back keeps the frame at the last settled state, which is always reachable by replay.

   **Rollback boundary — what reverts.** The pre-drain snapshot covers the **frame value** as defined for [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility): both `app-db` AND any frame-local registry mutations made during the failed cascade. A handler that ran `(rf/dispatch [:rf.machine/spawn ...])` inside the cascade — registering a frame-local handler in the spawned actor's `[:rf/machines <id>]` slot — has that registration reverted along with the `app-db` rollback; otherwise an aborted drain would leave orphaned handlers attached to a frame at a value that never references them. Out-of-band side effects already committed to *external* substrates (an HTTP request that flew, a `dispatch-later` timer that was scheduled) are not undone — the rollback is a value-shape revert, not a "rewind real-world side effects" guarantee.

```clojure
(rf/reg-frame :auth
  {:on-create   [:auth/initialise]
   :drain-depth 100})       ;; default and runtime-overridable
```

### Single-drainer invariant (concurrent hosts)

The drain operates under a **single-drainer invariant**: only one thread executes `drain!` at a time. Concurrent dispatch attempts enqueue and wake the executor, which no-ops if a drain is already running — the active drainer picks up newly-queued envelopes before returning. Per rf2-ynk7.

On single-threaded hosts (CLJS) this is trivially true. On the JVM the runtime's `interop/next-tick` executor can fire its callback concurrently with the calling thread (typically `dispatch-sync` on the main thread), so the implementation must CAS-acquire a per-frame drain-lock at every `drain!` entry; the loser of the CAS returns without touching the queue. `dispatch-sync` spin-waits for the lock and performs its seed-push under the lock so the prepend does not interleave with another drainer's `peek+pop`. The release of the drain-lock and the clearing of the per-router `:scheduled?` flag happen under the same `locking` block that the submit path uses for its scheduling check — that single seam closes the orphan-envelope window (an envelope queued between the inner empty-check and the lock release would otherwise be visible to neither the outgoing drainer's loop nor the next submitter's scheduling decision).

### What is and isn't drained

- **Synchronous re-dispatches (machine-to-machine messages)** are drained.
- **Async effects** — `:http`, timer-based, websocket-flavoured — are *not*. Their responses arrive later as fresh domain events, which then re-engage drain for their own cascade.
- **Domain events from outside the frame** wait until the current drain cascade settles.

### Render boundaries

Under run-to-completion, a dispatched event runs synchronously *before* the originator returns; views do not render any intermediate state of the cascade. Render happens once, after the cascade settles. (Code that requires a render between two events in a cascade is incompatible with this contract — see [MIGRATION.md](MIGRATION.md).)

`dispatch-sync` means "skip the router queue when called from outside any handler." Calling it from inside a handler raises `:rf.error/dispatch-sync-in-handler` (per [§dispatch-sync](#dispatch-sync) above); the in-handler shape is `[[:dispatch event]]` under `:fx`.

### `:fx` ordering and atomicity guarantees

When an `event-fx` handler returns `{:db <new-db> :fx [[a 1] [b 2] [c 3]]}`, the runtime processes the effect map under four locked rules. Apps may rely on them; conformant implementations must produce them.

1. **`:db` is the first side effect (when present).** The snapshot transitions atomically in one step before any `:fx` entry is processed. No external observer ever sees a half-written `app-db`.
2. **`:fx` entries are processed in source order.** `[a 1]` runs before `[b 2]` runs before `[c 3]`. The order in which the handler wrote the entries is the order in which they reach `do-fx`.
3. **Each `:fx` entry is processed serially before the next.** No interleaving. The fx-handler for entry N completes (synchronously, from `do-fx`'s perspective) before entry N+1 begins. *Asynchronous* work an fx kicks off (an outbound HTTP request, a `dispatch-later` timer) is not awaited; "complete" means the fx-handler function has returned.
4. **Subscriptions observe the post-`:db` state.** When the first `:fx` entry fires, `app-db` has already transitioned and sub-cache invalidation has happened. A handler may legitimately return `{:db <new-state> :fx [[:dispatch [:react-to-new-state]]]}` and the dispatched event's handler will see the new state.

From the *handler's* perspective, the handler returns once with the full effects map; sequencing of `:fx` entries is deterministic; the handler doesn't observe the side effects firing — it just declares them.

**Composition with the dispatch queue.** When `:fx` entries include `:dispatch`, the dispatched events are appended to the runtime FIFO queue in source order — preserving source-order all the way down a chain. `:dispatch-later` schedules timers in source order; actual delivery depends on each timer's delay.

**Composition with state machines.** Machine action effect maps (`{:data :fx}`) follow the same rule per [005 §Drain semantics §Level 1](005-StateMachines.md#level-1--within-a-single-actions-effect-map): `:data` merges first (lowered to one `:db` write at `[:rf/machines <id>]`), then `:fx` entries process in source order with `:raise` routed locally to the machine's pre-commit queue and the rest (including `:rf.machine/spawn` / `:rf.machine/destroy`) forwarded to the standard fx pipeline.

**Error during `:fx`.** If the fx-handler for `[a 1]` throws, subsequent entries `[b 2]` and `[c 3]` **continue to run.** Each thrown error is traced independently as `:rf.error/fx-handler-exception`. The `:db` commit is preserved (it happened before any `:fx` entry). Rationale: `:fx` entries are by design independent; ordering means *order*, not *dependency*. An fx that genuinely depends on a prior fx succeeding should be lifted to a `:dispatch` chain — observe the result via cofx in the dispatched handler. Halting on first error would conflate the two concerns.

Conformance fixtures: [`fx-db-first.edn`](conformance/fixtures/fx-db-first.edn), [`fx-ordering-source-order.edn`](conformance/fixtures/fx-ordering-source-order.edn).

### Drain-loop pseudocode

The rules above (the four `:fx` ordering rules, run-to-completion, depth-limited drain) compose into one execution loop. This subsection writes that loop down. v1's `re-frame.router` is the implementation reference — the loop below tracks v1's working router closely; what is *new* in re-frame2 is per-frame queuing, the `:raise` pre-commit primitive, and the machine microstep interleave from [005 §Drain semantics](005-StateMachines.md#drain-semantics).

The loop has two layers — an **outer drain** (Level 4 in [005's terms](005-StateMachines.md#level-4--across-the-runtime)) that pumps events FIFO from the router, and a **per-event drain** that runs one event end-to-end through interceptor chain, `do-fx`, and (for machine events) the Level 3 cascade.

```clojure
;; ============================================================================
;; OUTER DRAIN — per-frame Level-4 loop
;; ============================================================================
;; Triggered when an event arrives in an empty queue. Schedules itself via the
;; interop layer's next-tick so the host event loop interleaves rendering.

(defn dispatch [frame envelope]
  (let [router (:router frame)]
    (swap! (:queue router) conj envelope)              ;; FIFO append
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
      (when (> depth (:drain-depth (:config frame)))
        (raise! :rf.error/drain-depth-exceeded
                {:frame (:id frame) :depth depth})
        (throw ::halt))
      (when-let [envelope (peek-and-pop! (:queue (:router frame)))]
        (process-event! frame envelope)                ;; per-event drain
        (recur (inc depth))))
    (catch :default _ nil)
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
    ;;    Throws inside :before / :after / handler are recorded into the
    ;;    chain context under two paired keys — `:rf/interceptor-error`
    ;;    (singleton, the FIRST throw) and `:rf/interceptor-errors` (vector,
    ;;    ALL throws in order). The :after pass always runs in full so
    ;;    cleanup-on-:after interceptors fire even after a :before failure.
    ;;    Trace stream emits one `:rf.error/handler-exception` per chain
    ;;    execution, keyed off the singleton. See
    ;;    [Spec-Schemas §InterceptorContextErrorKeys](Spec-Schemas.md#interceptorcontexterrorkeys--post-chain-interceptor-context-error-contract).
    (let [effects (run-interceptor-chain
                    frame envelope handler-meta)]

      ;; 2. Apply :db FIRST. Atomic single replace-container! call.
      ;;    This is the moment sub-cache invalidation fires (per :fx ordering
      ;;    rule 4 above and per [006 §Subscription cache invalidation]).
      (when (contains? effects :db)
        (substrate/replace-container! (:app-db frame) (:db effects))
        (sub-cache/invalidate! frame))

      ;; 3. Walk :fx in source order. Each entry's handler returns
      ;;    synchronously before the next begins. Errors trace and continue.
      ;;    The fx-handler is invoked with the binary `(m args)` contract
      ;;    documented in [§The binary fx-handler signature](#the-binary-fx-handler-signature):
      ;;    `m` is the same context map the originating event handler received,
      ;;    carrying `:frame`, `:envelope`, `:event`, plus cofx. The runtime
      ;;    needs the frame record (to resolve `:fx-overrides`) and the parent
      ;;    envelope (so reserved fxs that queue children — `:dispatch`,
      ;;    `:dispatch-later` — can copy envelope fields onto the child envelope,
      ;;    per [§Cascade propagation](#cascade-propagation)); both reach the
      ;;    fx-handler as fields of `m`, not as separate positional arguments.
      (let [m (handler-context frame envelope)]      ;; same `m` the event handler saw
        (doseq [[fx-id args] (:fx effects)]
          (try
            (let [fx-handler (lookup-fx frame fx-id)]  ;; honors :fx-overrides
              (fx-handler m args))                     ;; binary contract: (m, args)
            (catch :default e
              (raise! :rf.error/fx-handler-exception
                      {:fx-id fx-id :event event :frame (:id frame) :ex e})))))

      (trace! :event/run-end {:event event :frame (:id frame)}))))

;; ============================================================================
;; do-fx for the FOUR reserved fx-ids the runtime owns
;; ============================================================================
;; :dispatch       — append to back of router queue; the outer drain picks
;;                   it up in this same drain cycle (run-to-completion).
;; :dispatch-later — schedule via interop/set-timeout!; the timer fires a
;;                   fresh dispatch later, re-engaging the drain loop.
;; :db             — handled inline in process-event! step 2; not seen here.
;; :raise          — machine-internal; routed by create-machine-handler to
;;                   its local raise-queue BEFORE :fx reaches do-fx (see
;;                   machine pseudocode below).
;; :rf.machine/spawn / :rf.machine/destroy — registered globally by
;;                   re-frame.machines and reach do-fx like any other fx.

;; Inheritable envelope fields — copied from parent to child when :dispatch /
;; :dispatch-later queue a new envelope. This is the "envelope-field-copying
;; when queueing children" mechanism named in [§Cascade propagation]
;; (#cascade-propagation). `:event` and `:dispatched-at` are NOT inherited —
;; the child gets its own. `:source` is preserved unless the queueing fx
;; sets a more specific value (e.g. a timer fx might set `:source :timer`).
(def ^:private inheritable-envelope-keys
  [:frame :fx-overrides :interceptor-overrides :trace-id :origin :source])

(defn- child-envelope [parent-envelope event]
  (-> (select-keys parent-envelope inheritable-envelope-keys)
      (assoc :event event)))

;; Reserved-fx defmethods follow the same binary `(m args)` contract as
;; user fxs. They reach the frame record and the parent envelope through
;; `m` — `(:frame m)` and `(:envelope m)` — rather than as separate
;; positional arguments. This keeps reserved and user fxs uniform: they
;; are all `(fn [m args] ...)` to the resolver.

(defmethod do-fx :dispatch [m ev]
  (let [frame           (:frame m)
        parent-envelope (:envelope m)]
    (dispatch frame (child-envelope parent-envelope ev)))) ;; back of queue, FIFO

(defmethod do-fx :dispatch-later [m {:keys [ms event]}]
  (let [frame           (:frame m)
        parent-envelope (:envelope m)
        child           (child-envelope parent-envelope event)]
    (interop/set-timeout!
      (fn [] (dispatch frame child))
      ms)))
```

For machine events, `process-event!` step 1 lands inside the machine handler, which runs the Level-3 cascade before returning effects. The cascade is Level 3 in [005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event):

```clojure
;; ============================================================================
;; MACHINE EVENT — Level-3 cascade (called from process-event! step 1)
;; ============================================================================
;; create-machine-handler returns this as a regular event handler. From the
;; outer drain's perspective, it returns an effects-map like any other handler.

(defn machine-event-handler [machine-def]
  (fn [frame envelope]
    (let [snapshot-path [:rf/machines (:id machine-def)]
          db            (substrate/read-container (:app-db frame))
          snapshot      (get-in db snapshot-path)]
      (loop [in-flight    snapshot
             accum-fx     []
             raise-queue  [(:event envelope)]
             always-depth 0]
        (when (> always-depth (:always-depth-limit machine-def 16))
          (raise! :rf.error/machine-always-depth-exceeded ...)
          (throw ::halt))

        (cond
          ;; Drain the local raise queue first — depth-first, pre-commit.
          (seq raise-queue)
          (let [[ev & rest-q] raise-queue
                {:keys [data-after fx]} (run-transition machine-def in-flight ev)]
            (recur (assoc in-flight :data data-after)
                   (into accum-fx fx)                  ;; non-:raise fx
                   (into (vec rest-q) (extract-raises fx))
                   always-depth))

          ;; Microstep loop — check :always; loop back into raise-drain on match.
          (let [matched (resolve-always machine-def in-flight)]
            (some? matched))
          (let [{:keys [data-after fx target]} (apply-always machine-def in-flight)]
            (recur (-> in-flight
                       (assoc :state target)
                       (assoc :data data-after))
                   (into accum-fx fx)
                   (extract-raises fx)
                   (inc always-depth)))

          ;; Fixed point reached. Commit ONE :db write at [:rf/machines <id>].
          :else
          {:db (assoc-in db snapshot-path in-flight)
           :fx accum-fx})))))
```

The handler returns its `{:db :fx}`; the outer `process-event!` then runs the `:fx` walk that ships the cascade's accumulated effects to `do-fx`. The whole macrostep — raise drain, microstep loop, snapshot commit — appears as one logical step to external observers. Sub-cache invalidation fires once (in `process-event!` step 2), not on every microstep.

#### Interaction map

This per-event drain is the canonical place every other piece of the runtime hooks in.

| Phase | Interacts with |
|---|---|
| `process-event!` step 1 | [Registrar](Runtime-Architecture.md#1-registrar) — handler resolution; [001-Registration §Registry kind taxonomy](001-Registration.md#registry-model--the-canonical-kind-keyword-set) |
| `process-event!` step 2 | [Substrate adapter §replace-container!](006-ReactiveSubstrate.md#read-container-container--value-and-replace-container-container-new-value--nil); [Sub-cache invalidation](006-ReactiveSubstrate.md#subscription-cache--contract-and-operational-semantics) |
| `process-event!` step 3 | `do-fx`; per-frame and per-call `:fx-overrides` (per [§Per-frame and per-call overrides](#per-frame-and-per-call-overrides)) |
| Trace emission | [009 §Core fields](009-Instrumentation.md#core-fields-required-on-every-event); error events use the `:rf.error/*` namespace per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) |
| Error trapping (`raise!` calls) | The structured-error contract per [009 §Error contract](009-Instrumentation.md#error-contract); the per-frame `:on-error` slot fires the user-defined projector |
| Machine cascade | [005 §Drain semantics §Level 3](005-StateMachines.md#level-3--within-a-single-machine-event); `:raise` is routed by `create-machine-handler` *before* `:fx` reaches `do-fx`; `:rf.machine/spawn` / `:rf.machine/destroy` reach `do-fx` like any other fx (per [Conventions §Reserved fx-ids](Conventions.md#reserved-fx-ids)) |

#### Edge cases worth pinning

1. **`:raise` inside an `:always` action.** The microstep that fires the action accumulates its `:fx` (including `:raise`) into the same Level-3 accumulator; the next iteration of the cascade drains the new raise-queue before re-checking `:always`. Same loop, no special case. Tracked via the same depth limits.
2. **Re-entrant dispatch from a render.** A view fn calling `(rf/dispatch ...)` during render lands in the router queue. The current drain has already settled before render started (run-to-completion); the dispatched event is processed in the *next* drain cycle, after the host gives time back to the JS event loop. Calling `dispatch-sync` from inside any handler raises `:rf.error/dispatch-sync-in-handler` (per [§dispatch-sync](#dispatch-sync)).
3. **`:dispatch` to self in a handler.** Goes to the back of the runtime FIFO, runs against the post-commit snapshot. **Different** from `:raise`, which runs pre-commit, depth-first. The two are not interchangeable — see [005 §Drain semantics gotchas](005-StateMachines.md#drain-semantics-gotchas).
4. **Frame disposal mid-drain.** The drain loop checks `(:destroyed? (:lifecycle frame))` before each dequeue; on detect, it stops, drops the remaining queue, and emits `:rf.frame/drain-interrupted` with the dropped count. In-flight events finish (run-to-completion); only events not yet dequeued are dropped.
5. **Effect handler kicks off async work and returns.** Handler returns synchronously; the async work runs against future ticks; its eventual reply is a fresh `dispatch` per [Pattern-AsyncEffect](Pattern-AsyncEffect.md). The drain loop is non-blocking — `:fx` "complete" means the fx-handler fn has returned, not that its observable side effects have settled.

## Per-frame and per-call overrides

> **Expected use case: testing.** Overrides are designed for tests, story fixtures, REPL exploration, and dev-time scenarios. They are *not* a production behaviour-routing mechanism — production code should use ordinary fx and interceptors registered globally. Overrides exist so tests can run without monkey-patching the global registry; they leave no trace once the test ends.
>
> **Pattern-level contract vs. CLJS reference (locked):** at the **pattern level**, override values are *registered ids* — `{:my-app/http :my-app/http.canned-200}` swaps one registered fx for another by id. Functions don't serialise across the wire; an SSR-capable architecture (Spec 011) requires id-valued overrides. The **CLJS reference v1** additionally supports function-valued overrides (`{:my-app/http (fn [m args] ...)}`) as a client-only convenience for tests and story fixtures where the override is a one-off lambda. Both forms accepted; id-valued is the portable shape, function-valued is CLJS-only sugar.
>
> **Asymmetry (explicit, locked):** other-language implementations need only support **id-valued** overrides — that's the conformance contract. The CLJS reference accepting function values is a local ergonomic affordance, not a pattern-level contract. AI scaffolding (Construction-Prompts) and the conformance corpus generate id-valued overrides. The `:rf/dispatch-envelope` schema's `:fx-overrides` value is `[:map-of :keyword :any]` rather than `[:map-of :keyword :keyword]` precisely because the CLJS reference admits the function-valued form; non-CLJS implementations narrow the value type to id-only.

Three things can be overridden per-call (via the dispatch opts map) and per-frame (via `reg-frame` keys):

| Envelope key             | What it does                                  | Source: per-call            | Source: per-frame                       |
|--------------------------|-----------------------------------------------|-----------------------------|------------------------------------------|
| `:fx-overrides`          | Replace registered fx handlers (by id)        | dispatch opts               | `reg-frame :fx-overrides`                |
| `:interceptor-overrides` | Replace interceptors in the event's chain (by `:id`) | dispatch opts        | `reg-frame :interceptor-overrides`       |
| `:interceptors`          | *Add* interceptors to the chain (prepend)     | dispatch opts (rare)        | `reg-frame :interceptors`                |

All three flow through the dispatch envelope. Per-call and per-frame merge with **per-call winning** on key conflict.

### `:fx-overrides` — replace fx handlers

The pattern-level form is **id-valued** — replace one registered fx with another. Functions don't serialise across the wire, so id-valued is the only form SSR can use. The CLJS reference also accepts function values for one-off CLJS lambdas (test fixtures, story decorators) where registering a stub feels like overkill.

```clojure
;; per-call — id-valued (canonical, portable)
(rf/dispatch [:user/login {:email "..."}]
             {:fx-overrides {:my-app/http  :my-app/http.canned-200
                             :localstorage nil}})                       ;; nil = noop

;; per-frame — id-valued
(rf/reg-frame :story.auth.login-form/loading
  {:on-create    [:auth/show-loading]
   :fx-overrides {:my-app/http :my-app/http.pending-stub}})

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
      (nil? override)        (get-fx-handler effect-key)              ;; no override
      (keyword? override)    (get-fx-handler override)                ;; id-valued: redirect
      (fn? override)         override                                  ;; CLJS reference: function value
      :else                  (throw (ex-info "Invalid override" {:effect-key effect-key :override override})))))
```

### `:interceptor-overrides` — replace interceptors in the chain by id

```clojure
;; per-call — turn off the logging interceptor for this dispatch
(rf/dispatch [:user/login {:email "..."}]
             {:interceptor-overrides {:my-app/logging nil}})

;; per-frame — disable logging for everything in a test frame
(rf/reg-frame :Test.Auth/silent
  {:on-create             [:auth/test-init]
   :interceptor-overrides {:my-app/logging nil}})
```

When the router builds the interceptor chain for the event, a small step walks it and substitutes by `:id`:

```clojure
(defn- apply-icpt-overrides [chain overrides]
  (->> chain
       (mapv #(if (contains? overrides (:id %))
                (get overrides (:id %))
                %))
       (filter some?)))   ;; nil-substituted entries are removed
```

Use cases (all testing-flavoured):

- **Turn off a logging interceptor in tests** — `{:my-app/logging nil}` removes it for the test's events.
- **Swap a real-clock cofx-injector for a fixed-time one** — `{:rf/inject-cofx-now (constantly fixed-time-icpt)}`.
- **Replace a remote-call validator with a relaxed one** for stories that intentionally violate the schema for visualisation.
- **Wrap a specific interceptor with timing** for a perf test.

Caveat: **interceptors must have stable `:id`s** for override-by-id to find them. Anonymous interceptors (created via `->interceptor` without `:id`) cannot be overridden. Tooling can warn when an override targets an id that isn't present in any chain.

### `:interceptors` — *add* interceptors to a frame's events

Distinct from override: `:interceptors` *prepends* interceptors to the chain rather than replacing existing ones. Useful for monitoring/recording without modifying registered behaviour.

```clojure
(rf/reg-frame :Dev.Recorder/active
  {:interceptors [event-recorder-icpt
                  app-db-validator-icpt]})
```

Use cases:

- **Action recorder** — capture every dispatched event for a story's "actions" panel.
- **App-db schema validator** — run Malli check after every event.
- **Tracing decorator** — emit fine-grained trace events scoped to a particular frame.
- **Effect recorder** — capture but don't fire effects, for dry-run/documentation modes (often combined with `:fx-overrides` to also disable real firing).

**Frame-level `:interceptors` is the canonical "global within this frame" mechanism.** There is no cross-frame interceptor concept in v2 — the v1 `reg-global-interceptor` / `clear-global-interceptor` surface is not shipped (per [MIGRATION §M-17](MIGRATION.md)). For cross-frame *observation* (audit logging, performance instrumentation, schema-validation-via-trace) use `register-trace-cb!` per [009-Instrumentation](009-Instrumentation.md). For cross-frame *behaviour modification* (rare, usually an architectural smell), declare the interceptor on each frame's `:interceptors` vector explicitly. Single-frame apps (only `:rf/default` in play) recover v1's global semantics by adding the interceptor to the default frame's `:interceptors`.

### Cascade propagation

All three override types propagate transitively through any depth of `:fx [:dispatch ...]` cascade. When a handler returns an effect map containing `:dispatch`, the dispatched child inherits the parent envelope's overrides (and `:frame`, `:trace-id`, etc.). One mechanism: envelope-field-copying when queueing children; same as `:frame` propagation.

### Discoverability

`(rf/frame-meta :my-frame)` returns the override and interceptor maps, so 10x and agents can see what's been scoped and why a particular fx or interceptor didn't behave as expected.

## State machines are just event handlers

The drain semantics above were motivated by actor-style machine composition. The unifying insight:

> **A state machine has the same contract as an event handler.** Given current state + an event, it produces new state + effects — exactly what `reg-event-fx` is. A machine is an event handler whose *body* happens to be a transition-table interpreter.

Machines therefore reuse the existing event registry, dispatch pipeline, and effect substrate. Co-locating machine snapshots in `app-db` (rather than in a parallel substrate) is what makes machine state inherit [Goal 3 — Frame state revertibility](000-Vision.md#frame-state-revertibility) for free; spawn-time registrations live in the **frame-local** tier of the two-tier registry (per [005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)). The two tiers — **central** (process-global, shared across frames; populated by namespace-load `reg-*` calls) and **frame-local** (per-frame, populated by spawn-time registrations and reverted by atomic rollback) — are defined in [000-Vision §Frame state revertibility](000-Vision.md#frame-state-revertibility). The foundation hooks defined here are:

- A registered event handler whose body comes from `create-machine-handler` *is* the machine. Tools filter by the `:rf/machine?` metadata exposed in `(handler-meta :event <id>)` to enumerate machines.
- Snapshots live at the **reserved per-frame path `[:rf/machines <machine-id>]`** in each frame's `app-db` (see [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live)). The shape is `{:state ... :data ...}`: `:state` is the discrete FSM-keyword; `:data` is the machine's extended state (the term used in FSM literature and `gen_statem`; xstate calls it "context"). **Per-frame isolation is automatic** — each frame's `app-db` has its own `:rf/machines` map, so the same machine id can exist in multiple frames without collision; their snapshots live in each frame's own `[:rf/machines]`. Because `:rf/machine` reads from the active frame's `app-db`, per-frame isolation extends transparently to subscription reads as well.
- Reads happen through the framework-registered parametric sub `:rf/machine` (or its `sub-machine` wrapper). `@(rf/sub-machine <machine-id>)` resolves on the surrounding frame and reads from that frame's `[:rf/machines <id>]`. See [005 §Subscribing to machines via `sub-machine`](005-StateMachines.md#subscribing-to-machines-via-sub-machine).
- Two thin helpers: `(machine-transition definition snapshot event) → [next-snapshot effects]` (pure, JVM-runnable) and `(create-machine-handler spec) → fn` (a *pure factory* — no registration side effects, no global-state lookups, no self-id capture; the returned fn is suitable as a `reg-event-fx` body).
- One reserved machine-internal fx-id (`:raise`) the machine handler routes locally inside the action's returned `:fx` vector; the canonical actor-lifecycle fx-ids `:rf.machine/spawn` / `:rf.machine/destroy` are registered globally and reach the standard `do-fx` resolver like any other fx.
- Inspection trace events with `:source :machine` (`:rf.machine.lifecycle/created`, `:rf.machine/transition`, `:rf.machine/snapshot-updated`, etc.) ride the standard trace stream.
- Composition via ordinary `dispatch`. Run-to-completion drain guarantees deterministic settling within a frame.
- A frame is the actor-system boundary; cross-frame dispatch is async (per the no-cross-frame-drain rule above).

Full design — three-way conceptual split, snapshot shape, transition-table grammar, drain semantics across the four nested levels, spawn lifecycle, testing pyramid, library packaging — lives in [005-StateMachines.md](005-StateMachines.md).

### Interop layer — clock primitives — see Spec 005

Clock primitives (`now-ms`, `schedule-after!`, `cancel-scheduled!`) live in `re-frame.interop` and are owned by [005 §Clock abstraction](005-StateMachines.md#clock-abstraction) — they are a substrate concern shared by `:after` transitions, `:dispatch-later`, and any future timing-sensitive feature, not a frame concern. The standard `:dispatch-later` fx delegates to the same primitives so tests can swap the clock at the namespace level.

## Interaction with libraries

Library authors **do not need to know about frames** if they only register handlers and interceptors:

- **re-frame-undo** registers an interceptor that records pre/post `db` snapshots. When the interceptor runs, the context's `:db` is whichever frame's `app-db` is in play; undo state lives at some path inside *that* frame's app-db. Each frame ends up with its own independent undo history. The library does no extra work.
- **re-frame-async-flow** schedules events via the standard `:dispatch` effect; frame propagation is automatic per the rule above.
- **re-pressed**, **re-frame-http-fx**, etc. — same story, provided their fx implementations use the standard dispatch effect or capture a frame-bound dispatch fn via `(rf/dispatcher)`.

Authors of fx that escape into async land *do* have to forward the frame — either by capturing `(rf/dispatcher)` inside the binary handler body or by threading `{:frame frame}` through every callback's dispatch. This is a small, well-defined obligation; documented in [§Async effects and frame propagation](#async-effects-and-frame-propagation) and as required rule M-51 in [MIGRATION.md](MIGRATION.md#m-51-reg-fx-handlers-are-binary--rewrite-unary-handlers-to-take-an-unused-first-arg).

## Tooling and agent-amenability

### The public registrar query API

re-frame2 commits to a queryable public registrar for every kind of registered entity (frames, events, subs, fx, cofx, views, interceptors). [Goal 10 (Strong introspection surface)](000-Vision.md#goals) says this is first-class. **The contract for registry queries (`handlers`, `handler-meta`, `frame-ids`, `frame-meta`) is owned by [001 §The query API](001-Registration.md#the-query-api).** The table below restates that surface alongside the frame-runtime queries (`get-frame-db`, `snapshot-of`, `sub-topology`, `sub-cache`) that 002 owns:

| Query | Returns | JVM-runnable? |
|---|---|---|
| `(rf/handlers kind)` | Map of id → metadata for every handler of the given kind. The kind keyword set is canonicalised in [001 §The query API](001-Registration.md#the-query-api): `:event` (all of `reg-event-db`/`-fx`/`-ctx`), `:sub`, `:fx`, `:cofx`, `:view`, `:frame`, `:route`, `:app-schema`. Machines themselves register under `:event` (per [005](005-StateMachines.md)) — filter by `:rf/machine?` metadata to enumerate them. Machine guards and actions are **machine-scoped** (declared in each machine's `:guards` / `:actions` map) — there is no `:machine-guard` / `:machine-action` registry kind. | Yes |
| `(rf/handlers kind pred-fn)` | Same, filtered by `pred-fn` applied to each metadata map. | Yes |
| `(rf/handler-meta kind id)` | Metadata for a single handler (config, source coords, doc, spec, etc.). | Yes |
| `(rf/frame-ids)` | Seq of all registered frame keywords. | Yes |
| `(rf/frame-ids prefix)` | Seq filtered by namespace prefix (e.g., `(rf/frame-ids :story)` returns all `:story.*` frames). | Yes |
| `(rf/frame-meta id)` | Metadata for a single frame (config, source coords, lifecycle, doc, override maps, interceptor list). | Yes |
| `(rf/get-frame-db id)` | Current `app-db` value (a plain map) for the named frame. Returns nil if the frame is not registered. | Yes |
| `(rf/snapshot-of path)` / `(rf/snapshot-of path opts)` | Snapshot value at a path in a frame's `app-db` (typically a machine snapshot). One-arg form uses `:rf/default` frame; two-arg accepts `{:frame frame-id}`. | Yes |
| `(rf/sub-topology)` | **Static** dependency graph from `:<-` declarations: a map of `sub-id → {:inputs [<input-sub-ids>], :doc, :ns/:line/:file}`. Pure data derived from the registrar at registration time. | Yes |
| `(rf/sub-cache id)` | **Runtime** cache state for a frame: which subs are currently materialised, their current cached values, dependent components if any. Requires the reactive runtime. | **No** — CLJS-only |

Most queries are JVM-runnable because they read from the registrar (which is data) and from `app-db` (which is data). One query is not, and the table marks it: `sub-cache` reads runtime state from the reactive substrate (currently Reagent-specific). Static topology and snapshot reads stay pure-data.

The metadata maps returned by `handler-meta` and `frame-meta` follow a documented shape — see [001 §Registration grammar](001-Registration.md#registration-grammar) for handler metadata, and [§reg-frame is atomic](#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar) above for frame metadata. Tools (10x, re-frame-pair, agents, story tools) read these and present them however they want.

### Per-frame and trace surface

- **Per-frame app-db inspection** — covered by `get-frame-db` above.
- **Trace per frame.** Each frame's router emits a stream of trace events (event in, interceptors, effects out) that 10x and other tools subscribe to. Coordination point with 10x: epochs are tagged with their frame.
- **Hot-reload notifications.** `reg-frame`/`reg-event-*`/etc. re-registration fires notifications on a re-frame-internal pub/sub that tools can listen to and refresh their state.

## Story-tool foundation hooks — see Spec 007

Stories/variants/workspaces consume foundation primitives this Spec defines (frames per variant, per-frame fx/interceptor overrides, `make-frame` for per-mount isolation, the registrar query API). The story-tool surface lives in [007-Stories.md](007-Stories.md); 002 owns the foundation it consumes.

## Migration

See [MIGRATION.md](MIGRATION.md) for the migration rules. Single-frame apps need no changes; private-namespace access (`re-frame.db/app-db` etc.) breaks; everything else is additive opt-in.

## Open questions

### Event-id collisions on re-registration

Hot-reloading the same handler under the same id is normal and expected. But re-registering the same id with a *different* handler function — accidentally, e.g. two namespaces colliding — is silent last-write-wins. Should re-frame2 warn at registration time when an id is being re-registered with a function whose source coords don't match the previous registration? Probably yes, with a configurable threshold.

### Sub-cache invalidation across frames

If two frames depend on a shared piece of *registry* state (handler definitions), and a sub is hot-reloaded, both frames' caches need invalidation for any cached reactives derived from that sub. Mechanism: registry change fires a notification that frame sub-caches subscribe to. Detail-level design; flagged here so it is not forgotten.

### Concurrent React rendering

React 18's concurrent rendering can render the same component multiple times before committing. `reg-view`'s injected `dispatch` is a value, so it survives this fine. But any `dispatch` *executed during render* (Form-2's outer fn, `:on-create`-style patterns) may run more than once. Confirm with the substrate (Reagent today; possibly UIx tomorrow) and document.

### Sub-cache disposal on frame destroy

When `destroy-frame` runs, every cached reactive needs its `dispose!`-equivalent called. With Reagent reactions today, this is direct. With a future substrate-agnostic substrate, the disposal contract becomes part of the adapter API. Flagged so the adapter-layer design includes it.

### Transducer-shaped event processing (substrate-agnostic router)

> **Status: post-v1 deferred.** v1 ships the existing drain loop; the Spec-level design pass on a transducer-shaped router is deferred to v1.1 to keep v1 scope tight and avoid coupling the router redesign to other v1-critical work. Tracked in rf2-cl8me.

pure-frame implements event processing as a transducer parameterised by the frame: `(frame-transducer-factory frame) → transducer`, with the reducing function determining how state flows (sync, queued, batch). The transducer captures the per-event step (resolve handler → run interceptor pipeline → produce new state); the reducing function decides how successive states are accumulated and committed.

Originally flagged as worth considering for v1. A transducer-shaped router is reusable, testable, and extensible without exposing rendering or scheduling primitives at the public API — but the design pass is non-trivial and overlaps with the router work in [012-Routing.md](012-Routing.md), so the call for v1 is to keep the drain loop and revisit the transducer formulation post-v1.

### Frame presets — initial list and expansion (RESOLVED)

Resolved: the closed v1 set is **`:default`**, **`:test`**, **`:story`**, **`:ssr-server`** with the precise expansions documented in [§Frame presets](#frame-presets--capability-bundles-for-common-configurations). Adding a fifth preset is a Spec-change-only operation; the runtime emits `:rf.error/unknown-preset` for unrecognised preset values at registration time. `:preset` works identically on `make-frame`. The expansion algorithm is `(merge expansion user-supplied-metadata)` with user keys winning on conflict. Candidates considered and not adopted in v1: `:devcards` (subsumed by `:story`), `:repl` (subsumed by `:default`), `:replay` (too coupled to Tool-Pair to stabilise).

## Resolved decisions

A pointer-only index of decisions taken in this Spec. Each entry's load-bearing prose lives in the linked section above (or in the linked sibling Spec).

| Decision | Pointer |
|---|---|
| `reg-frame` re-registration is a surgical update by default; `reset-frame` is the opt-in full replace; `destroy-frame` removes from registry | [§Re-registration — surgical update](#re-registration--surgical-update), [§reset-frame — full replace, opt-in](#reset-frame--full-replace-opt-in) |
| `reg-frame` takes no `:db` config — frames always start with `app-db = {}`; initialisation runs through `:on-create` | [§reg-frame is atomic](#reg-frame--atomic-create-and-register-and-the-canonical-metadata-grammar) |
| Frame-aware events outside views use the two-arg dispatch form `(rf/dispatch [:foo] {:frame :todo})`; `dispatch-to` / `dispatch-with` are not shipped | [§Routing: the dispatch envelope](#routing-the-dispatch-envelope) |
| The CLJS reference's `frame-provider` (React context) is an *ergonomic optimisation* atop the pattern-level explicit-frame contract; observable behaviour matches explicit-frame addressing; SSR bypasses context | [§View ergonomics](#view-ergonomics-the-hard-part), [011-SSR.md](011-SSR.md) |
| Plain Reagent fns under a non-default frame fire a one-shot warning per `(component-id, frame-id)`, elided in production | [004-Views §Plain Reagent fns](004-Views.md#plain-reagent-fns-staged-adoption-with-a-loud-footgun-warning) |
| Per-instance frames via anonymous `make-frame` for per-mount lifecycles | [§Per-instance frames — anonymous `make-frame`](#per-instance-frames--anonymous-make-frame) |
| Per-frame and per-call overrides via `:fx-overrides`, `:interceptor-overrides`, `:interceptors` | [§Per-frame and per-call overrides](#per-frame-and-per-call-overrides) |
