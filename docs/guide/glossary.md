# Glossary

Welcome to the lost-and-found. This is the page you keep open in a second tab — the one you flick to when a doc says "the cascade" or "a coeffect" and you want the one-line truth without re-reading a whole chapter. Every entry gives you the canonical word (re-frame2 is fussy about names, on purpose), a plain-English definition, a tiny snippet so you can see it in the wild, and a link to where it's taught for real. The snippets here are illustrative, not runnable — read them as sketches.

## The objects

**adapter** — The concrete per-substrate binding value you hand to `init!` so the framework knows how to talk to your React layer. It is a *value*, not the layer itself.

```clojure
(rf/init! reagent-adapter/adapter)   ;; the adapter is the thing you pass
```

See [Views](concepts/views.md). (Keep distinct from **substrate**, the layer.)

**app-db** — Your app's single immutable state map; one per frame, the one source of truth your code owns.

```clojure
{:cart {:items [{:sku "BK-1" :qty 2}]} :user/name "Ada"}
```

See [app-db](concepts/app-db.md).

**coeffect** — A declared input the framework injects into a handler so the handler can stay pure; the world enters only through these.

```clojure
(rf/reg-event :order/place
  {:rf.cofx/requires [:now]}           ;; :now is a declared coeffect
  (fn [{:keys [now db]} _] ...))
```

See [Effects & Coeffects](concepts/effects-and-coeffects.md). The key-form is `cofx`.

**effect** — A described side-effect returned as data and run by the runtime, never performed by the handler itself.

```clojure
{:fx [[:http {:url "/api/cart" :method :post}]]}
```

See [Effects & Coeffects](concepts/effects-and-coeffects.md). Reserve `fx` for the `:fx` key / fx-id.

**effect map** — The whole `{:db … :fx …}` value a handler returns; the closed top-level key set for app code is `#{:db :fx}`.

```clojure
{:db (assoc db :cart/open? true)
 :fx [[:dispatch [:cart/track-open]]]}
```

See [Events & the cascade](concepts/events-and-the-cascade.md). Singular: **effect map**.

**epoch** — The before/after state record one cascade leaves behind; the unit of time-travel.

```clojure
;; one dispatch = one cascade = one epoch (the record)
```

See [Observability](concepts/observability.md).

**error record / `:rf.error/*` category** — A failure surfaced as a structured map keyed by a reserved category keyword; branch on the category, never the human-readable `:reason`.

```clojure
{:rf.error/category :rf.error/no-frame-context :reason "no frame in scope"}
```

See [Errors](concepts/errors.md).

**event** — An inert data vector — a fact that something happened, shaped `[id]`, `[id scalar]`, or `[id {map}]`.

```clojure
[:cart/add-item {:sku "BK-1" :qty 1}]
```

See [Events & the cascade](concepts/events-and-the-cascade.md).

**flow** — A pure derivation materialised *into* app-db so handlers can read it as plain state.

```clojure
(rf/reg-flow
  {:id :cart/total :inputs [[:cart :items]]
   :derive (fn [items] (reduce + (map :price items)))
   :output-path [:cart :total]})
```

See [Flows](concepts/flows.md).

**frame** — One isolated, running instance of your app, with its own app-db, event queue, and subscription cache.

```clojure
(rf/reg-frame :app {:initial-events [[:rf/set-db {}]]})
```

See [Frames](concepts/frames.md). Frames isolate **state, not registrations**.

**image** — The selected, sealed set of registrations a frame resolves against; its resolved result is a **generation**.

```clojure
;; a frame resolves its handlers against a fixed image; the resolved set is a generation
```

See [Images](concepts/images.md). Use **image** for the source, **generation** for the resolved set.

**interceptor** — A named, by-id `context → context` decorator that wraps a handler.

```clojure
(rf/reg-interceptor :my-app/logger
  {:before (fn [ctx] ...) :after (fn [ctx] ctx)})
```

See [Interceptors](concepts/interceptors.md).

**machine** — A state machine registered as an event handler via `reg-machine`; its live value is a **snapshot**.

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

See [Machines](concepts/machines.md).

**mutation** — A declared server-state write whose cache consequences (what it invalidates) are declared once on the registration.

```clojure
(rf/reg-mutation :article/favorite {:scope :rf.scope/global} request-fn)
```

See [Server state](concepts/server-state.md). The reply field is `:value`; the instance sub field is `:result`.

**resource** — A declared, cached server-state read registered with `reg-resource`; its `:scope` is a required leak boundary.

```clojure
(rf/reg-resource :article {:scope :rf.scope/global} request-fn)
```

See [Server state](concepts/server-state.md). "Server state" is the category, not the noun.

**runtime-db** — The framework-owned state partition beside app-db (machine snapshots, route slice, resource cache, mutation status); read-don't-write for app code.

```clojure
[:rf.db/runtime :rf.runtime/machines :auth.login/flow]   ;; a snapshot's address
```

See [app-db](concepts/app-db.md). Paths: `:rf.db/runtime`, children `:rf.runtime/*`.

**subscription** — A named, registered, pure, cached, read-only derivation of state.

```clojure
(rf/reg-sub :cart/count (fn [db _] (count (:items (:cart db)))))
```

See [Subscriptions](concepts/subscriptions.md). Casual "sub" is fine; not as a headword.

**substrate** — The React-family rendering layer your app runs on (Reagent / UIx / Helix / reagent-slim).

```clojure
;; "substrate" = the layer; the value you pass to init! is the adapter
```

See [Views](concepts/views.md).

**view** — A pure render function from subscription values to hiccup.

```clojure
(rf/reg-view :cart-badge (fn [] [:span.badge @(rf/subscribe [:cart/count])]))
```

See [Views](concepts/views.md). Use "component" for React-analogy callouts only.

## The processes

**commit** — The single, deferred, all-or-nothing app-db write at the end of an event; no observer ever sees a half-written app-db.

```clojure
;; the :db you return is staged; it's committed once, at the end, atomically
```

See [Events & the cascade](concepts/events-and-the-cascade.md).

**dispatch** — Send an event into a frame; returns immediately and never mutates inline.

```clojure
(rf/dispatch [:cart/add-item {:sku "BK-1"}])
```

See [Events & the cascade](concepts/events-and-the-cascade.md).

**drain / run-to-completion** — The runtime drains the whole event queue to a fixed point before subscriptions recompute and views render.

```clojure
;; all queued events run to completion, THEN subs recompute, THEN views render
```

See [Events & the cascade](concepts/events-and-the-cascade.md). Hyphenate **run-to-completion** consistently.

**elide** — Compile dev-only code out of production via one flag (`goog.DEBUG` / `-Dre-frame.debug`).

```clojure
;; goog.DEBUG=false removes the dev trace surface and schema checks
```

See [Observability](concepts/observability.md). Name DCE once, then use **elide**.

**the event cascade** — The fixed, ordered run one dispatch sets off: handler → effect map → effects → derivations → view → DOM.

```clojure
;; dispatch → handler → effect map → effects → derivations → view → DOM
```

See [Events & the cascade](concepts/events-and-the-cascade.md). Reserve "the loop" for the whole repeating machine, "the six dominoes" for the first-contact mnemonic, "epoch" for the record.

**invalidate** — A mutation declares, as data on its registration, which cached reads it makes stale.

```clojure
{:invalidates (fn [{:keys [slug]} _result]
                {:tags #{[:article slug]}})}     ;; declared once, on the mutation
```

See [Server state](concepts/server-state.md).

**navigate** — Change the route by dispatching navigation; the URL is an input, the route is a sub.

```clojure
(rf/dispatch [:rf.route/navigate :article {:id "abc"}])
```

See [Routing](concepts/routing.md).

**project (egress)** — Run a value through redaction before it leaves the app, via `project-egress`; direct reads are not auto-projected.

```clojure
(rf/project-egress value {:frame :app/main :path [:auth]})  ;; redacts at the sink
```

See [Keep secrets out of traces](how-to/keep-secrets-out-of-traces.md).

**the reg-\* registrars** — The boot-time registration calls that name your handlers and machinery: `reg-event`, `reg-sub`, `reg-view`, `reg-fx`, `reg-cofx`, `reg-interceptor`, `reg-machine`, `reg-flow`, `reg-resource`, `reg-mutation`, `reg-route`, `reg-app-schema`.

```clojure
(rf/reg-event :cart/clear (fn [{:keys [db]} _] {:db (dissoc db :cart)}))
```

See [Events & the cascade](concepts/events-and-the-cascade.md). There is **one** `reg-event` — `reg-event-db`/`-fx`/`-ctx` are gone.

**subscribe / derive** — Read derived state by name through a subscription; `@(subscribe …)` both reads and subscribes.

```clojure
@(rf/subscribe [:cart/count])
```

See [Subscriptions](concepts/subscriptions.md).

## The big ideas

**Effects are data** — A handler returns a *description* of side-effects and the runtime performs them; a pure handler plus data effects is what makes replay, test, and trace possible.

```clojure
{:fx [[:http {:url "/api/login"}] [:dispatch [:ui/spinner true]]]}
```

See [Effects & Coeffects](concepts/effects-and-coeffects.md).

**Fail loud, not silent** — A recognised input that can't be honoured raises a structured `:rf.error/*`, never a nil or no-op. Keep **fail-loud** (raise instead of swallow) distinct from **fail-closed** (deny by default at a boundary).

```clojure
;; unregistered id, missing cofx, unknown fx → raises :rf.error/*, never returns nil
```

See [Errors](concepts/errors.md).

**Frame identity is carried, not found** — An operation reads its frame from its scope (provider / running handler / captured handle); the runtime never invents one. A rootless call is `:rf.error/no-frame-context`.

```clojure
[rf/frame-provider-existing {:frame :app} [app-root]]   ;; scope carries the frame downward
```

See [Frames](concepts/frames.md).

**generation** — The resolved registration set an image produces; what a frame actually runs against.

```clojure
;; image (the source) resolves to a generation (the sealed result a frame uses)
```

See [Images](concepts/images.md).

**The four homes (where state lives)** — Subscription → flow → resource → machine: pick the cheapest that fits, decided by the where-state-lives router. Every other concept defers here for "which one do I use?".

```clojure
;; cart total → sub (or flow); the article → resource; checkout → machine
```

See [Where state lives](where-state-lives.md).

**snapshot** — A machine's live value at any moment: which state it's in plus its `:data`, read through a subscription addressed by the machine's id.

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])   ;; {:state :authed :data {...}}
```

See [Machines](concepts/machines.md).

**The two partitions** — A frame holds **app-db** (yours) and **runtime-db** (the framework's), addressed by the projection paths `:rf.db/app` and `:rf.db/runtime`; runtime subsystems live under `:rf.runtime/*`.

```clojure
[:rf.db/runtime :rf.runtime/resources]   ;; the resource cache IS a runtime-db subsystem
```

See [app-db](concepts/app-db.md).

**The uniform reply** — Every managed async surface (HTTP, resources, mutations, route loaders, machine async) completes by *dispatching an event carrying a reply map*, never by an awaited value. The HTTP envelope's discriminator is `:kind` (`:success`/`:failure`); the resource view-model's `:status` (`:ok/:partial/:error/:cancelled/:stale`) is a different field, with `:kind :success` ≡ `:status :ok`.

```clojure
[:auth/login-reply {:kind :success :value {:token "…"}}]
```

See [Managed HTTP](concepts/http.md).

**Xray** — The dev inspector: an in-app panel that reads the trace stream and per-frame epoch history so you debug the loop, not the DOM.

```clojure
;; open Xray to step through epochs, inspect app-db, and read the cascade
```

See [Debug with Xray](how-to/debug-with-xray.md).
