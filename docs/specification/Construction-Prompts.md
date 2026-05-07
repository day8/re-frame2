# Construction Prompts (AI Agent Templates)

> **Type:** Construction Prompts
> Per-kind AI-scaffolding templates for new code in the re-frame2 pattern. Sibling to [MIGRATION.md](MIGRATION.md) (which covers upgrades of existing code).

CP-10 (story / variant / workspace) is post-v1; its sketch lives in [007-Stories.md §Construction prompt (CP-10)](007-Stories.md#construction-prompt-cp-10) and the full template lands here when `re-frame.stories` ships.

## Purpose

[MIGRATION.md](MIGRATION.md) is the AI prompt for *upgrading* old re-frame code. **Construction Prompts** is the AI prompt for *creating new* code in the re-frame2 pattern. Where MIGRATION rewrites existing shapes, Construction Prompts scaffolds shapes that don't yet exist.

This artefact is intended to be:

- **Per-kind.** Separate templates for events, subscriptions, schema-bound views, state machines, features, routes, effects.
- **Self-contained.** Each prompt provides enough context for an AI to scaffold the kind without needing to read the full Spec set.
- **Shape-aware via the host's idiom.** The pattern requires shape description; the *mechanism* is host-specific. **Dynamic hosts** (CLJS + Malli, Python + Pydantic, JS + Zod): the prompts attach `:spec` metadata referring to a schema. **Static hosts** (TypeScript, Kotlin): the prompts emit type-annotated registrations whose shapes the compiler enforces. Either way, an AI reading the artefact has a description of the shapes; the prompts adapt to the host.
- **Worked-example-heavy.** Each prompt ends with one or two complete, runnable examples.
- **Aligned with the [Goals in 000](000-Vision.md) and the [nine AI-first properties in Principles.md](Principles.md).** A construction-prompt-generated artefact is, by construction, AI-first.

## Shared pre-flight (applies to every CP)

Every CP below begins with the same mechanics; rather than restate them in each, treat the following as a shared preamble. Each CP's "Pre-flight checks" section calls out only the *delta* — the kind-specific naming convention or extra check — on top of these.

1. **Choose a namespaced id.** Lowercase, kebab-case. The id-prefix matches the feature (per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention)).
2. **Verify the id is unused.** Query the registry via the public registrar query API for the relevant kind (e.g., `(rf/handlers :event)`, `(rf/handlers :sub)`, `(rf/handlers :fx)`, `(rf/handlers :view)`, `(rf/handlers :route)`).
3. **Consult registered schemas** (`(rf/app-schemas)`, `(rf/app-schema-at <path>)`) so the new artefact aligns with shapes already in use.

## Catalogue

Each entry below is one CP:

### CP-1. Add an event handler

**When to use this prompt:** the user wants the app to react to something — a button click, a server response, a timer tick, a child machine sending a message. If the reaction modifies state only, prefer `reg-event-db`. If it also produces side-effects (HTTP call, navigation, dispatch chain, local-storage write), use `reg-event-fx`.

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** `:feature/verb-noun` or `:feature.subfeature/verb-noun`. Examples: `:auth/login`, `:auth.password/reset`, `:cart.item/remove`. The relevant registry kind is `:event`.
- **Call-shape convention** (per [Principles §Name over place](Principles.md#name-over-place) and [002 §Routing](002-Frames.md#routing-the-dispatch-envelope)): `[<id>]` for trivial events, `[<id> <single-scalar>]` for single-argument events, `[<id> {<key> <val> ...}]` for multi-argument events. Multi-positional `[<id> a b c]` is tolerated for v1 compatibility but not what new code should emit.
- **Schema-bound paths.** If the event reads or writes a schema-bound `app-db` path, your handler must produce schema-compliant output (validation runs in dev).

**Template — `reg-event-db` (state-only):**

```clojure
(rf/reg-event-db :feature/verb-noun
  {:doc  "One-sentence what-and-why."
   :spec EventSchema}                         ;; optional Malli schema for the event vector
  (fn handler-feature-verb-noun [db [_ {:keys [field-1 field-2]}]]
    ;; Pure: read db and event payload, return the new db.
    ;; Multi-arg events take a single map payload; destructure named keys.
    ;; No side-effects. No dispatching from inside the handler — return effects from reg-event-fx if needed.
    (assoc-in db [:feature :path] field-1)))
```

**Template — `reg-event-fx` (with effects):**

```clojure
(rf/reg-event-fx :feature/verb-noun
  {:doc          "One-sentence what-and-why."
   :spec         EventSchema
   :interceptors []}                          ;; optional; usually empty
  (fn handler-feature-verb-noun [{:keys [db] :as cofx} [_ payload]]
    ;; Pure: read db and any injected cofx, return an effect map.
    ;; payload is a single scalar (for single-arg events) or a map (for multi-arg events
    ;; — destructure named keys: [_ {:keys [field-1 field-2]}]).
    ;; Effects are data; the runtime interprets them.
    {:db        (assoc-in db [:feature :loading?] true)
     :fx        [[:http {:method :get :url "/api/feature" :on-success [:feature/loaded]}]]}))
```

**Naming the handler fn:** match the id's path and verb. `:cart.item/remove` → `handler-cart-item-remove`. The name shows up in stack traces and tooling; anonymous lambdas hide.

**Smoke test:**

```clojure
(deftest feature-verb-noun-test
  (rf/with-frame [f (rf/make-frame {:on-create [:feature/initialise]})]
    (rf/dispatch-sync [:feature/verb-noun "value"] {:frame f})
    (is (= "value" (get-in @(rf/get-frame-db f) [:feature :path])))))
```

**AI-first checklist before declaring done:**

- [ ] Id is namespaced and unused.
- [ ] Handler is pure (no side-effects in the body of `reg-event-db`; effects are *returned* by `reg-event-fx`).
- [ ] `:doc` is present and one sentence.
- [ ] Shape is described by the host's idiom: `:spec` (Malli/Pydantic/Zod) in dynamic hosts, a type definition in static hosts. If neither: shape conformance is checked by tests/fixtures.
- [ ] Handler has a meaningful name (not `fn`).
- [ ] Smoke test passes.
- [ ] If the handler writes a schema-bound `app-db` path (in a schema-bearing implementation), the test asserts the post-state validates.

**Example — full worked artefact:**

```clojure
(ns my-app.cart.events
  (:require [re-frame.core :as rf]
            [malli.core :as m]))

(def CartItemRemoveEvent
  [:tuple [:= :cart.item/remove] :uuid])      ;; [event-id item-id]

(rf/reg-event-db :cart.item/remove
  {:doc  "Remove an item from the cart by id."
   :spec CartItemRemoveEvent}
  (fn handler-cart-item-remove [db [_ item-id]]
    (update-in db [:cart :items] (fn [items] (vec (remove #(= item-id (:id %)) items))))))
```

### CP-2. Add a subscription

**When to use this prompt:** the user wants a derived view of `app-db` — a computed value views can read. A subscription is *not* a place to put side-effects; it is a pure function from state to value.

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** `:feature/property` or `:feature/computed-value`. Examples: `:cart/total`, `:cart/items-count`, `:auth/logged-in?`. The relevant registry kind is `:sub`.
- **Call-shape convention** (per [Principles §Name over place](Principles.md#name-over-place) and [002 §Routing](002-Frames.md#routing-the-dispatch-envelope), same as for events): `[<id>]` for trivial subs, `[<id> <single-scalar>]` for single-argument subs (`[:user-by-id 42]`), `[<id> {<key> <val> ...}]` for multi-argument subs (`[:items-filtered {:status :pending :limit 20}]`). Multi-positional `[<id> a b c]` is tolerated for v1 compatibility but not what new code should emit.
- **Decide the input.** Either reads `app-db` directly (Layer 1 sub) or composes other subs (Layer 2 / signal-graph chained sub via `:<-`).
- **Check schemas.** If the sub's return value has a registered schema (rare for layer-1, common for layer-2), align the output shape.

**Template — Layer 1 (reads app-db directly):**

```clojure
(rf/reg-sub :feature/items
  {:doc "All items currently in the feature's slice."}
  (fn sub-feature-items [db _query]
    (get-in db [:feature :items])))
```

**Template — Layer 2 (chained via `:<-`):**

```clojure
(rf/reg-sub :feature/total
  {:doc "Aggregate computed from :feature/items."}
  :<- [:feature/items]
  (fn sub-feature-total [items _query]
    (reduce + (map :amount items))))
```

**Template — multi-input chain:**

```clojure
(rf/reg-sub :feature/summary
  {:doc "Joins items, user, and pricing rules to produce a display summary."}
  :<- [:feature/items]
  :<- [:auth/current-user]
  :<- [:pricing/active-rule]
  (fn sub-feature-summary [[items user rule] _query]
    {:item-count (count items)
     :discount-eligible? (and rule (>= (count items) (:min-items rule)))
     :user-tier (:tier user)}))
```

**Pattern-level discipline:**

- Body is **pure** — `(state, query) → value`. No side-effects, no mutation, no I/O.
- Layer 2 subs **don't** read `app-db` directly. They compose layer-1 subs via `:<-`. (This keeps the signal graph topology static and queryable via `(sub-topology)`.)
- Sub computations should be **fast**. Heavy work belongs in event handlers that pre-compute and store in `app-db`.

**Smoke test (headless via `compute-sub`):**

```clojure
(deftest feature-total-test
  (let [db {:feature {:items [{:amount 10} {:amount 25} {:amount 5}]}}]
    (is (= 40 (rf/compute-sub [:feature/total] db)))))
```

**AI-first checklist:**

- [ ] Sub id is namespaced and unused.
- [ ] Body is pure.
- [ ] Layer-2 subs use `:<-` chains; they don't read `app-db` directly.
- [ ] `:doc` is present.
- [ ] Smoke test runs headlessly via `compute-sub`.
- [ ] If the return value has a schema, the test asserts conformance.

**Example — full worked artefact:**

```clojure
(rf/reg-sub :cart/total
  {:doc "Sum of qty × price across cart items."}
  :<- [:cart/items]
  (fn sub-cart-total [items _]
    (reduce + (map #(* (:qty %) (:price %)) items))))
```

### CP-3. Add a registered effect

**When to use this prompt:** the user wants a side-effect — HTTP, local-storage, navigation, websocket message, timer, log. Effects are id'd, registered, and platform-tagged for SSR (per [011](011-SSR.md)).

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** a single namespaced keyword. Examples: `:http`, `:localstorage`, `:rf.nav/replace`, `:websocket/send`. The relevant registry kind is `:fx`.
- **Decide the platform.** Server-only? Client-only? Both? Default `#{:server :client}` (universal) if absent — set explicitly to `#{:client}` if the fx genuinely cannot run server-side (DOM mutation, `js/window`, `localStorage`).
- **Identify the args shape.** What does the effect map's value look like? Does it need a schema?

**Template:**

```clojure
(rf/reg-fx :http
  {:doc       "Issue an HTTP request. On completion, dispatch :on-success or :on-error."
   :platforms #{:server :client}                        ;; both server (SSR data fetch) and client
   :spec      [:map
               [:method  [:enum :get :post :put :delete]]
               [:url     :string]
               [:body         {:optional true} :any]
               [:on-success   {:optional true} [:vector :any]]
               [:on-error     {:optional true} [:vector :any]]]}
  (fn fx-http [m args]
    ;; m: the dispatch envelope (the active frame, trace id, etc.)
    ;; args: the value the user put under :http in their effect map
    (let [{:keys [method url body on-success on-error]} args
          frame-id (:frame m)]
      (-> (perform-http-request method url body)
          (.then  (fn [resp] (when on-success (rf/dispatch (conj on-success resp) {:frame frame-id}))))
          (.catch (fn [err]  (when on-error  (rf/dispatch (conj on-error err)   {:frame frame-id}))))))))
```

**Pattern-level discipline:**

- The handler is `(envelope-map, args) → side-effect`. The args are *data*; the side-effect is performed at the boundary.
- The handler is responsible for dispatching follow-up events (`:on-success`/`:on-error`) on the originating frame so the state-change cascade resumes.
- The handler **may not modify `app-db` directly** — only via dispatched events.
- Server-only effects (`:platforms #{:server}`) are skipped on the client; client-only on the server. The runtime emits `:rf.fx/skipped-on-platform` traces so it's visible.

**Test stub (id-valued override):**

```clojure
(rf/reg-fx :http.canned-200
  {:doc       "Test stub: every :http call resolves to a canned 200 response."
   :platforms #{:client :server}}
  (fn fx-http-canned-200 [_m args]
    (when-let [on-success (:on-success args)]
      (rf/dispatch (conj on-success {:status 200 :body "test"})))))

;; in a test
(rf/with-frame [f (rf/make-frame {:fx-overrides {:http :http.canned-200}
                                  :on-create [:feature/load]})]
  ...)
```

The override seam is **id-valued at the pattern level**. The CLJS reference also accepts function values for one-off CLJS lambdas, but registered-fx-id is the portable form (works for SSR, serialises across the wire, listable in tooling).

**AI-first checklist:**

- [ ] Fx id is namespaced.
- [ ] `:platforms` is set explicitly (don't rely on the default).
- [ ] `:spec` describes the args shape.
- [ ] `:doc` is present.
- [ ] Handler dispatches `:on-success`/`:on-error` (or equivalent) on the originating frame.
- [ ] Handler does NOT mutate `app-db` — that's events' job.
- [ ] A registered test-stub fx exists with a stable id (e.g., `:fx-id.canned`) for tests.

**Example — local storage:**

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a key/value pair to browser localStorage. Client only."
   :platforms #{:client}
   :spec      [:map
               [:key   :string]
               [:value :any]]}
  (fn fx-localstorage-set [_m {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

### CP-4. Add a registered view

**When to use this prompt:** the user wants a UI component that renders state and dispatches events. Always prefer `reg-view` over plain Reagent fns for any view that may render under a non-default frame (which, with SSR-per-request, is most views).

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** `:feature/component-name` or `:feature.area/component-name`. Plural component names for lists (`:cart/items`), singular for single-instance (`:cart/total`). The relevant registry kind is `:view`.
- **Identify the subscriptions the view will read.** Each must be already registered (`(rf/handlers :sub)`) or scaffolded as a sibling — if missing, invoke CP-2.
- **Identify the events the view will dispatch.** Each must be already registered (`(rf/handlers :event)`) — if missing, invoke CP-1.

**Template — Form-1 (simple render fn):**

```clojure
(rf/reg-view :feature/component-name
  {:doc  "One-sentence what-and-why."
   :spec [:cat :string :int]}                 ;; optional Malli schema for the props vector (after the view id)
  (fn render-feature-component-name [label count-prop]
    (let [items @(subscribe [:feature/items])]    ;; frame-bound; resolves on the surrounding frame
      [:div.feature
       [:h3 label]
       [:p (str "Count: " count-prop)]
       (for [item items]
         ^{:key (:id item)}
         [:div.item
          [:span (:label item)]
          [:button {:on-click #(dispatch [:feature/select (:id item)])} "Select"]])])))
```

**Template — Form-2 (closure for once-on-mount setup):**

```clojure
(rf/reg-view :feature/component-name
  (fn outer-feature-component-name [label]
    ;; Outer fn: runs once on mount. Use for setup that should fire once per
    ;; component lifecycle (e.g., dispatching an :on-create-style init event,
    ;; registering a frame, opening a websocket).
    (dispatch [:feature/component-mounted])
    (fn render-feature-component-name [label]
      ;; Render fn: runs every render.
      (let [n @(subscribe [:feature/count])]
        [:div label ": " n]))))
```

**Pattern-level discipline (per [004](004-Views.md) and [011](011-SSR.md)):**

- Body is **pure** given the inputs (props + frame-bound subs).
- Output is **serialisable data** (hiccup, no embedded JS objects).
- **No render-time side-effects** — no `js/setTimeout`, no DOM mutations, no fetch.
- Side-effects belong in event handlers; the view dispatches.

**Smoke test (headless render):**

```clojure
(deftest feature-component-name-renders
  (rf/with-frame [f (rf/make-frame {:on-create [:feature/initialise]})]
    (let [hiccup ((rf/get-view :feature/component-name) "test-label" 42)
          html   (rf/render-to-string hiccup {:frame f})]
      (is (str/includes? html "test-label"))
      (is (str/includes? html "Count: 42")))))
```

**AI-first checklist:**

- [ ] View id is namespaced and unused.
- [ ] All subs/events the view uses are registered.
- [ ] Body is pure; no render-time side-effects.
- [ ] `:on-click`/`:on-change` lambdas dispatch named events (no inline `swap!`/`reset!`).
- [ ] Output round-trips through `render-to-string` (smoke test passes both server-side and client-side).
- [ ] If the props vector has a schema, validation passes for the test inputs.

**Example — full worked artefact:**

```clojure
(rf/reg-view :cart/summary
  {:doc "Total cost and item count for the current cart."}
  (fn render-cart-summary []
    (let [items @(subscribe [:cart/items])
          total @(subscribe [:cart/total])]
      [:div.cart-summary
       [:span (str (count items) " items")]
       [:span (str "$" (format "%.2f" total))]
       [:button {:on-click #(dispatch [:cart/checkout])} "Checkout"]])))
```

### CP-5. Scaffold a state machine

**When to use this prompt:** the user describes a multi-step interaction with discrete, named states — a login flow, a checkout wizard, a video player, a modal lifecycle, a websocket connection. If you can list the states and the events that move between them, you have a machine.

**Key idea: the machine IS the event handler.** A machine is registered as one `reg-event-fx` whose body comes from `create-machine-handler`. Sub-events route in: `(rf/dispatch [:my/machine [:my-input arg ...]])`.

**Default form: named guards and actions in the machine's `:guards` / `:actions` maps.** The transition table references guards and actions by **keyword** (`:under-retry-limit`, `:clear-error`); the bodies live in the machine's own `:guards` / `:actions` maps inside `create-machine-handler`. This is the **default** because the named id carries semantic meaning that visualisers, AIs, conformance fixtures, and humans all read; an inline `(fn [snap ev] ...)` is opaque to inspection. Inline fns are an **escape hatch for trivial logic** (one-liners with no branching), not the default form. See [005 §Inspectability bias](005-StateMachines.md#inspectability-bias). **Resolution is machine-local** — there is no global `:machine-guard` / `:machine-action` registry; cross-machine reuse is via Clojure vars referenced from each machine's map.

**Pre-flight checks:**

1. **Choose a machine id.** Convention: `:feature.flow/machine` or `:feature/flow`. Examples: `:auth.login/flow`, `:checkout/flow`, `:video-player/flow`.
2. **Verify the id is unused.** `(rf/handlers :event)` — the machine reuses the `:event` registry kind. (No matching `:sub` registration is needed: machines are read through the framework-registered parametric sub `:rf/machine`; see "Where state lives" below.) `(rf/machines)` enumerates already-registered machines specifically.
3. **List the states.** Discrete, named (`:idle`, `:submitting`, `:authed`, `:error-shown`).
4. **List the inputs (sub-events) that move between states.** Each input triggers exactly one transition.
5. **Identify guards and actions; default to naming them in `:guards` / `:actions`.** Each guard `(fn [snapshot event] boolean)` and each action `(fn [snapshot event] {:data {...} :fx [...]})` is a key in the machine's `:guards` / `:actions` map (referenced from transitions by keyword). **Inline only when the body is a single non-branching expression.**

**Where state lives.** Every machine's snapshot lives at the runtime-managed path `[:rf/machines <machine-id>]` in the frame's `app-db`. For id `:auth.login/flow`, the snapshot is at `[:rf/machines :auth.login/flow]` and contains `{:state ... :data ...}`. You do not pick the path — `create-machine-handler` does not accept a `:path` key. Per-frame isolation is automatic: each frame has its own `app-db` and thus its own `:rf/machines` map. See [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live).

**Reading the snapshot in views.** The framework ships `:rf/machine` as a standard parametric sub. `@(rf/sub-machine :auth.login/flow)` returns the snapshot (sugar over `@(rf/subscribe [:rf/machine :auth.login/flow])`) — no per-machine `reg-sub` needed. Destructure inline, or write a derived sub `:<- [:rf/machine <id>]` for projections. See [005 §Subscribing to machines via `sub-machine`](005-StateMachines.md#subscribing-to-machines-via-sub-machine).

**Strict encapsulation.** Actions and guards see `{:state :data}` only — *no `:db`, no cofx*. Cross-cutting reads pass through the event payload; cross-cutting writes go via `:fx [[:dispatch <named-event>]]`. Action effect maps are `{:data {...} :fx [...]}` — symmetric with `reg-event-fx`'s `{:db :fx}`. The named-bounce-event pattern is a feature, not a tax: it makes the cross-cutting concern visible in the trace, the registry, and 10x's event log (per [005 §Strict encapsulation](005-StateMachines.md#strict-encapsulation--actions-only-see-their-own-data)).

**Worked example — auth login flow (named guards/actions in `:guards` / `:actions` maps):**

```clojure
;; The machine — every guard and action is named in :guards / :actions and
;; referenced from the transition table by keyword. Resolution is
;; machine-local: the runtime calls (get-in spec [:guards :under-retry-limit])
;; etc. There is no global :machine-guard / :machine-action registry.

(rf/reg-event-fx :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
  (rf/create-machine-handler
    {:initial :idle
     :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      ;; Has this login had fewer than 3 prior attempts?
      (fn [{:keys [data]} _event]
        (< (:attempts data) 3))}

     :actions
     {:begin-submit
      ;; Clear the prior error and emit the HTTP request for credential check.
      (fn [_snap [_ creds]]
        {:data {:error nil}
         :fx   [[:http {:method     :post
                        :url        "/api/login"
                        :body       creds
                        :on-success [:auth.login/flow [:succeeded]]
                        :on-error   [:auth.login/flow [:failed]]}]]})

      :record-failure
      ;; Bump the attempts counter and surface a credentials error.
      (fn [{:keys [data]} _event]
        {:data {:attempts (inc (:attempts data))
                :error    :credentials}})

      :lock-out
      ;; Lock the account after exceeding the retry limit.
      (fn [_snap _event]
        {:data {:error :locked}})

      :clear-error
      ;; Reset the error before re-submitting.
      (fn [_snap _event]
        {:data {:error nil}})

      :clear-and-record-success
      ;; On successful auth, clear any residual error state.
      (fn [_snap _event]
        {:data {:error nil}})}

     :states
     {:idle
      {:on
       {:submit
        {:target :submitting
         :action :begin-submit}}}                            ;; resolves to :actions :begin-submit

      :submitting
      {:on
       {:succeeded
        {:target :authed
         :action :clear-and-record-success}

        :failed
        ;; multiple candidates with guards — first match wins
        [{:target :error-shown
          :guard  :under-retry-limit                         ;; resolves to :guards :under-retry-limit
          :action :record-failure}
         {:target :locked-out
          :action :lock-out}]}}

      :error-shown
      {:on
       {:dismiss {:target :idle}
        :submit  {:target :submitting
                  :action :clear-error}}}

      :authed     {:meta {:terminal? true}}
      :locked-out {:meta {:terminal? true}}}}))
```

**What "named in `:guards` / `:actions` by default" buys:**

- A reviewer scanning the transition table sees `:guard :under-retry-limit` and immediately knows what gates the transition.
- An AI proposing a change to "the retry-limit guard" can resolve the id against the machine's `:guards` map (visible in `(machine-meta :auth.login/flow)`).
- A diagram exporter can label the transition arrow with the guard's name.
- A Level-1 test can stub the spec's `:actions :begin-submit` for deterministic HTTP behaviour by re-defining one entry in the spec — no need to re-register a global handler.
- A conformance fixture can assert "the `:failed` event in `:submitting` runs the `:record-failure` action."
- `create-machine-handler` validates every keyword reference against `:guards` / `:actions` at registration time — typos surface immediately as `:rf.error/machine-unresolved-guard` / `:rf.error/machine-unresolved-action`, not at runtime when the transition fires.

**Template — internal vs external self-transitions:**

```clojure
{:editing
 {:on
  {:drag-slider
   ;; internal self-transition — no :target, so :exit and :entry do NOT fire.
   ;; Update :data via an action named in the machine's :actions map.
   {:action :update-preview-radius}

   :poke
   ;; external self-transition — :target :same-state, so :exit and :entry DO fire.
   {:target :same-state
    :action :randomise-poke-count}}}}
```

**Template — `:raise` for transition chaining (atomic, pre-commit):**

```clojure
;; ... inside the machine spec:
:actions
{:notify-and-audit
 (fn [_ _]
   {:fx [[:raise    [:notify-listeners]]      ;; same machine, atomic, pre-commit
         [:dispatch [:audit/login-ok]]]})}    ;; runtime queue, post-commit

:states
{:submitting
 {:on {:succeeded
       {:target :idle
        :action :notify-and-audit}}}}
```

`:raise` and `:spawn` are **reserved fx-ids inside `:fx`** — the machine handler routes them locally. `[:raise <ev>]` ≡ "back into THIS machine, processed before the snapshot is committed"; `[:dispatch <ev>]` is the standard runtime-queue dispatch. They have **different ordering semantics** — see [005 §Drain semantics gotchas](005-StateMachines.md#drain-semantics-gotchas).

**Template — `:spawn` for dynamic actors:**

```clojure
;; ... inside the machine spec:
:actions
{:spawn-fetch
 (fn [_ [_ url]]
   {:fx [[:spawn {:machine-id :request/protocol
                  :id-prefix  :request/protocol
                  :data       {:url url}
                  :on-spawn   (fn [data id] (assoc data :pending-request id))
                  :start      [:begin]}]]})}

:states
{:idle {:on {:fetch {:target :loading :action :spawn-fetch}}}}
```

After this action, `(:pending-request data)` is the new actor's id; subsequent transitions can dispatch to it. The spawned actor's snapshot lives at `[:rf/machines <gensym'd-id>]` (runtime-managed; the spawn-spec does not pick a location). The `:on-spawn` callback is an inline fn here — that's appropriate; it's a single non-branching `assoc`.

**Deeper guidance — see the appendix.** When you need the inline-fn vs named-action escape-hatch test, the v1 grammar subset, or substitutes for parallel-regions / history-states, consult [CP-5 Machine Guide](CP-5-MachineGuide.md). It's a sibling appendix to keep CP-5 itself a build-facing prompt rather than a second machine spec.

**Pattern-level discipline:**

- The transition table is **pure data** (per [005](005-StateMachines.md)) — serialisable, AI-readable, visualisable.
- **Default to registered guards and actions.** Inline fns are an escape hatch for trivial logic, not the default form. See [005 §Inspectability bias](005-StateMachines.md#inspectability-bias).
- Action and guard slots are **single fn or single registered id** — no `:actions [a1 a2 a3]` vector form, no `{:and [...]}` compound-guard data form. Multi-step composition is fn composition; reused composition is registered with a meaningful name.
- Action signature: `(fn [snapshot event] {:data {...} :fx [...]})`. **Strict encapsulation**: no `:db`, no cofx — the runtime hard-disallows `:db` in the action's effect map (`:rf.error/machine-action-wrote-db`).
- `machine-transition` is a **pure function** — `(definition, snapshot, event) → [next-snapshot, effects]`. JVM-runnable, headless-testable.
- The actor system boundary is the frame; cross-machine messages within a frame settle via run-to-completion drain.

**Headless tests — three levels:**

```clojure
;; Level 1 — pure transition function (fastest; FSM logic only).
(deftest auth-login-happy-path-l1
  (let [snap   {:state :idle :data {:attempts 0 :error nil}}
        [s1 _] (rf/machine-transition auth-login-table snap [:submit {:email "..."}])]
    (is (= :submitting (:state s1)))))

;; Level 2 — unregistered handler fn (handler-level wiring; still no test frame).
;; Possible because create-machine-handler is a pure factory.
;; Snapshots live at [:rf/machines <id>] in app-db (runtime-managed).
(deftest auth-login-happy-path-l2
  (let [handler (rf/create-machine-handler {:initial :idle ...})]
    (let [{:keys [db]} (handler {:db {:rf/machines {:auth.login/flow {:state :idle :data {}}}}}
                                [:auth.login/flow [:submit {:email "..."}]])]
      (is (= :submitting (get-in db [:rf/machines :auth.login/flow :state]))))))

;; Level 3 — registered in a test frame (full integration; required for spawn lifecycle).
(deftest auth-login-happy-path-l3
  (rf/with-frame [f (rf/make-frame {:on-create [:auth/init]})]
    (rf/dispatch-sync [:auth.login/flow [:submit {:email "..."}]] {:frame f})
    ;; Read via the framework-registered :rf/machine sub:
    (is (= :submitting (:state @(rf/subscribe [:rf/machine :auth.login/flow] {:frame f}))))))
```

**Template — view consuming `sub-machine`:**

The framework-registered `:rf/machine` sub returns the snapshot for any machine; the wrapper `sub-machine` is the canonical user-facing form:

```clojure
(rf/reg-view :auth.login/form
  (fn render-auth-login-form []
    (let [{:keys [state data]} @(rf/sub-machine :auth.login/flow)]
      [:form
       (case state
         :idle        [submit-button]
         :submitting  [spinner]
         :error-shown [:<>
                       [:p (str "Error: " (:error data))]
                       [:button {:on-click #(rf/dispatch [:auth.login/flow [:dismiss]])}
                        "Try again"]]
         :authed      [:p "Welcome!"]
         :locked-out  [:p "Account locked."]
         nil          [:p "Loading..."])])))           ;; nil before initialisation
```

For projections, compose against `:rf/machine` via `:<-`:

```clojure
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snap _] (:state snap)))
```

**AI-first checklist:**

- [ ] Machine id is namespaced; registered via `reg-event-fx` + `create-machine-handler`.
- [ ] No `:path` key in the machine spec — the runtime stores snapshots at `[:rf/machines <id>]`.
- [ ] All states are listed in `:states`; no string-based or computed state names.
- [ ] Every input the machine listens to is in some state's `:on` map.
- [ ] **Non-trivial guards and actions are named in the machine's `:guards` / `:actions` maps and referenced by keyword from the transition table, not inline.** Inline fns are reserved for single non-branching expressions per [005 §Inspectability bias](005-StateMachines.md#inspectability-bias).
- [ ] Every keyword reference under `:guard` / `:action` (in `:on`, `:always`, `:entry`, `:exit`) is a key in the spec's `:guards` / `:actions` map — `create-machine-handler` validates this at registration time and raises `:rf.error/machine-unresolved-{guard|action}` on miss.
- [ ] No `reg-machine-guard` / `reg-machine-action` calls — those APIs are removed; guards and actions are machine-scoped.
- [ ] `:guard` and `:action` are single fns (or single keyword references) — not vectors.
- [ ] No `[:assign ...]`, `[:raise ...]`, `[:fx ...]` data forms in transition slots — actions return `{:data {...} :fx [...]}` directly.
- [ ] No compound-guard `{:and ...}` / `{:or ...}` / `{:not ...}` data forms — composition is fns or named compounds in `:guards`.
- [ ] No `:db` in action effect maps — cross-cutting writes go via `:fx [[:dispatch <named-event>]]`.
- [ ] Cross-cutting reads come through the event payload, not from `app-db`.
- [ ] Cross-machine reuse of a guard/action is via a Clojure var referenced from each machine's `:guards` / `:actions` map — not via a global registry.
- [ ] Views read state via `@(rf/sub-machine <machine-id>)` (or the explicit `@(rf/subscribe [:rf/machine <machine-id>])`); no manual `reg-sub` over `[:rf/machines ...]`.
- [ ] Transition table conforms to `:rf/transition-table` schema (per [Spec-Schemas](Spec-Schemas.md)).
- [ ] Level-1 headless test passes via `machine-transition` (no event dispatch needed).
- [ ] If the machine has terminal states, they're marked `:meta {:terminal? true}`.
- [ ] Trace events on `:rf.machine/transition` are visible in 10x / re-frame-pair.
- [ ] `(rf/machines)` includes the new id; `(rf/machine-meta <id>)` returns its registration metadata (which includes the spec's `:guards` / `:actions` maps).

### CP-6. Scaffold a feature

**When to use this prompt:** the user describes a thing — "add a login form," "add a cart," "let users tag items." Most "build me X" requests land here. A *feature* is a registry slice — events + subs + views + schemas + optional machine — addressable by a shared id-prefix.

**Pre-flight checks (mandatory):**

1. **Choose the feature id-prefix.** A short, namespaced keyword: `:auth`, `:cart`, `:tagging`. Sub-areas use dotted children: `:cart.item`, `:cart.checkout`. Every registration the feature ships uses this prefix.
2. **Verify the prefix is unused.** Query each kind:
   - `(rf/handlers :event)` — none should start with your prefix.
   - `(rf/handlers :sub)` — likewise.
   - `(rf/handlers :view)` — likewise.
   - `(rf/app-schemas)` — your `app-db` paths must be free.
3. **Identify the feature's `app-db` shape.** Pick a single root key matching the prefix: `:cart`, `:auth`, etc. All feature state lives under that key. No exceptions.
4. **Identify external dependencies.** Other features (e.g., `:auth` reads `:user`), registered fx (e.g., `:http`, `:localstorage`), schemas the feature consumes.

**Feature shape (minimum viable):**

A feature ships these artefacts as a coherent bundle:

| Artefact | Required | Convention |
|---|---|---|
| **Schema** for the feature's `app-db` slice | yes | `(rf/reg-app-schema [:feature] FeatureSchema)` |
| **`:on-create`-style init event** | yes | `:feature/initialise` — sets the slice to its initial value |
| **State events** (the feature's instruction set) | yes | `:feature/verb-noun`, `:feature.subarea/verb-noun` |
| **Subscriptions** | yes | `:feature/property` reading from `[:feature ...]` |
| **Views** | usually | `:feature/root-view` plus child views |
| **Machine** for stateful flows | optional | `:feature.flow/machine` if the feature has multi-step interactions |
| **Routes** | optional | If the feature has its own URL surface |
| **Smoke test** covering the happy path | yes | Drives feature events, asserts state and renders |

**Directory / namespace convention (CLJS reference):**

```
src/my_app/
  feature/
    schema.cljc        ;; Malli schema definitions
    events.cljs        ;; reg-event-* calls
    subs.cljs          ;; reg-sub calls
    views.cljs         ;; reg-view calls
    machines.cljs      ;; (optional) state machines
    routes.cljs        ;; (optional) route bindings
    public.cljs        ;; (optional) re-exports of the feature's public surface
test/my_app/
  feature/
    happy_path_test.cljs
```

**Template — happy-path scaffold:**

```clojure
;; my-app/cart/schema.cljc
(ns my-app.cart.schema)

(def CartItem
  [:map
   [:id   :uuid]
   [:sku  :string]
   [:qty  pos-int?]
   [:price :double]])

(def CartState
  [:map
   [:items     [:vector CartItem]]
   [:loading?  :boolean]
   [:checkout-state [:enum :idle :submitting :error]]])

;; my-app/cart/events.cljs
(ns my-app.cart.events
  (:require [re-frame.core :as rf]
            [my-app.cart.schema :as cs]))

(rf/reg-event-db :cart/initialise
  {:doc "Seed the cart slice."}
  (fn [db _]
    (assoc db :cart {:items [] :loading? false :checkout-state :idle})))

(rf/reg-event-db :cart.item/add
  {:doc  "Add an item to the cart."
   :spec [:cat [:= :cart.item/add] cs/CartItem]}
  (fn [db [_ item]]
    (update-in db [:cart :items] conj item)))

;; my-app/cart/subs.cljs
(rf/reg-sub :cart/items
  {:doc "All items in the cart."}
  (fn [db _] (get-in db [:cart :items])))

(rf/reg-sub :cart/total
  {:doc "Sum of qty × price across all items."}
  :<- [:cart/items]
  (fn [items _]
    (reduce + (map #(* (:qty %) (:price %)) items))))

;; my-app/cart/views.cljs
(rf/reg-view :cart/summary {…})              ;; via CP-4
(rf/reg-view :cart/item-list {…})

;; on app boot
(rf/reg-app-schema [:cart] cs/CartState)
(rf/dispatch [:cart/initialise])
```

**Smoke test — feature happy path:**

```clojure
(deftest cart-feature-happy-path
  (rf/with-frame [f (rf/make-frame {:on-create [:cart/initialise]})]
    (let [item {:id (random-uuid) :sku "ABC-1" :qty 2 :price 9.99}]
      (rf/dispatch-sync [:cart.item/add item] {:frame f})
      (is (= [item] @(rf/compute-sub [:cart/items] @(rf/get-frame-db f))))
      (is (== 19.98 @(rf/compute-sub [:cart/total] @(rf/get-frame-db f)))))))
```

**AI-first checklist for a feature:**

- [ ] All registrations use the chosen prefix; nothing else uses it.
- [ ] `app-db` slice has a registered schema; init event produces a schema-valid value.
- [ ] Every event has `:doc`; structurally-shaped events have `:spec`.
- [ ] Every sub has `:doc` and reads from the feature's slice (`[:feature ...]`).
- [ ] No view dispatches an unregistered event or reads an unregistered sub.
- [ ] Happy-path smoke test runs headlessly (JVM-runnable).
- [ ] Feature ships its **public surface** explicitly (in `public.cljs` or via doc) — which events the rest of the app may dispatch, which subs it may read.
- [ ] Feature does NOT reach into another feature's `app-db` slice directly; it goes through the other feature's subs and dispatches the other feature's events.

**Why feature-modularity matters for AI use:**

When the user says "delete the cart feature," an AI scaffolding correctly is one `git rm -r src/my_app/cart/ test/my_app/cart/` plus removing the `(require ...)` lines that pull the feature in — no other code references `:cart/...` anywhere. The id-prefix discipline turns features into truly excisable units.

When the user says "duplicate this feature for wishlists," the AI runs the same prompt with `:wishlist` as the prefix and reuses the same shape.

### CP-7. Scaffold a route

**When to use this prompt:** the user wants the URL to reflect application state and vice versa — deep-linkable pages, browser back/forward, shareable links.

**Pre-flight checks:**

1. **Choose route ids.** Convention: `:route/page-name`. Examples: `:route/home`, `:route/cart`, `:route/cart.item-detail`.
2. **Identify the URL pattern for each.** Use the canonical grammar — [012 §Path-pattern grammar](012-Routing.md#path-pattern-grammar-canonical) is the single source of truth (literal segments, `:name` path params, `{...}?` optional groups, `*name` splats). Do not restate the grammar in handler comments or auxiliary docs; cross-reference 012.
3. **Distinguish path params from query params.**
   - Path: captured by `:name` / `*name` segments — declared in `:params` schema.
   - Query: parsed from `?key=value&...` — declared in `:query` schema, with `:query-defaults` and `:query-retain` for ergonomics.
4. **Identify per-route data dependencies.** Use `:on-match` (vector of events the runtime dispatches when this route becomes active, server- and client-side).
5. **Verify the route ids are unused.** `(rf/handlers :route)` enumerates registered routes.

Routing is *state plus events*. The URL is a derivable view of `app-db`; navigation is an event. The runtime ships `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf/url-changed`, `:rf/url-requested` as standard events; user code typically only calls `:rf.route/navigate`.

**Template — register routes (declarative; the runtime owns dispatch):**

```clojure
(rf/reg-route :route/home
  {:doc  "Landing page."
   :path "/"})

(rf/reg-route :route/cart
  {:doc      "The cart."
   :path     "/cart"
   :on-match [[:cart/load-items]                          ;; runtime dispatches on match (server + client)
              [:user/load-prefs]]
   :on-error [:route/cart-load-failed]                    ;; if any :on-match event errors
   :scroll   :top})                                       ;; scroll-to-top on entering this route

(rf/reg-route :route/cart.item-detail
  {:doc    "Detail page for a single cart item."
   :path   "/cart/items/:id"
   :params [:map [:id :uuid]]
   :parent :route/cart})                                  ;; nested-layout convention

(rf/reg-route :route/search
  {:doc            "Search results."
   :path           "/search"
   :query          [:map [:q :string] [:page {:optional true} :int]]
   :query-defaults {:page 1}
   :query-retain   #{:theme :locale}                      ;; carry through subsequent navigations
   :on-match       [[:search/run]]})

(rf/reg-route :rf.route/not-found
  {:doc  "Default 404."
   :path "/404"})
```

**No need to register `:rf.route/navigate` or `:rf.route/handle-url-change` yourself** — the runtime ships them. Re-register only to override behaviour (e.g. add a guard interceptor; see [012 §Redirects and guards](../specification/012-Routing.md#redirects-and-guards)).

**Template — `:on-match` data-loading event:**

```clojure
(rf/reg-event-fx :cart/load-items
  {:doc "Load cart items for the active cart route."}
  (fn handler-cart-load-items [{:keys [db]} _]
    (let [user-id (get-in db [:auth :user :id])]
      {:fx [[:http {:method     :get
                    :url        (str "/api/users/" user-id "/cart")
                    :on-success [:cart/items-loaded]
                    :on-error   [:cart/load-failed]}]]})))
```

The handler reads `(:route db)` for any path/query params it needs — the `:route` slice is already populated when `:on-match` events fire.

**Template — route-aware root view:**

```clojure
(def root-view
  (rf/reg-view :app/root
    (fn render-app-root []
      (let [route-id   @(subscribe [:rf.route/id])
            transition @(subscribe [:rf.route/transition])]
        [:div
         (when (= transition :loading) [progress-bar])
         (case route-id
           :route/home              [home-page]
           :route/cart              [cart-page]
           :route/cart.item-detail  [cart-item-detail-page]
           :route/search            [search-page]
           :rf.route/not-found         [not-found-page]
           [not-found-page])]))))
```

**Template — links (use the registered `route-link` view):**

```clojure
[rf/route-link {:to :route/cart} "Cart"]
[rf/route-link {:to :route/cart.item-detail :params {:id item-id}} "View"]
[rf/route-link {:to :route/search :query {:q "clojure" :page 2}} "Search"]
```

`route-link` dispatches `:rf/url-requested` on click; the runtime's default handler classifies internal vs external and dispatches `:rf.route/navigate` for matched routes.

**Template — wiring (called once at app boot):**

```clojure
(defn install-router! [frame-id]
  (.addEventListener js/window "popstate"
    #(rf/dispatch [:rf/url-changed (.. js/window -location -href)] {:frame frame-id}))
  (rf/dispatch [:rf/url-changed (.. js/window -location -href)] {:frame frame-id}))
```

`:rf/url-changed` is the runtime's URL-change event; its default handler is `:rf.route/handle-url-change`.

**Pattern-level discipline:**

- The route is in `app-db`; the URL is derivable. Never make routing state live outside `app-db`.
- Navigation is an event. Don't call browser APIs directly from view code; dispatch `:rf.route/navigate` (or use `route-link`).
- Per-route data loading is **declarative** — list events in `:on-match` on `reg-route`. The runtime dispatches them.
- Server-side renders set the route via `:rf/url-changed` against the request URL; the same `:on-match` events run server-side.
- Path params and query params are **separate maps** — `(:params (:route db))` and `(:query (:route db))`.

**AI-first checklist:**

- [ ] Route ids are namespaced (`:route/...`).
- [ ] Each route's `:path` conforms to the canonical path-pattern grammar.
- [ ] Path params are declared in `:params` (schema); query params are declared in `:query` (schema).
- [ ] Per-route data dependencies are declared in `:on-match` (vector of event vectors).
- [ ] Per-route error handling is declared in `:on-error` (single event vector) where needed.
- [ ] All navigation goes through `:rf.route/navigate` (or `route-link`); no inline `pushState`.
- [ ] The root view dispatches on `:rf.route/id`; per-page views are registered separately.
- [ ] A `:rf.route/not-found` route is registered.
- [ ] Nested layouts use `:parent` (or id-prefix-only if no shared loader/chrome is needed); read the chain via `:rf.route/chain`.

### CP-8. Scaffold a schema

**When to use this prompt:** the user wants to describe the shape of data — an event's args, a sub's return value, a slice of `app-db`, a registered fx's args, a request/response payload from an external service.

**Pre-flight checks:**

1. **Identify what shape you're describing.** Event vector? Sub return? `app-db` slice? Fx args?
2. **Decide open vs closed.** Default: **open** (consumers tolerate unknown keys). Use `:closed true` only at *system boundaries* — incoming HTTP request payloads, outgoing API requests, EDN/JSON crossing process boundaries.
3. **Check existing schemas at the same path.** `(app-schema-at [:feature])` — don't shadow.

**Templates by site:**

```clojure
;; Event-vector schema (attached via :spec on reg-event-*)
(def CartItemRemoveEvent
  [:tuple [:= :cart.item/remove] :uuid])      ;; [event-id item-id]

;; Registered to the event:
(rf/reg-event-db :cart.item/remove
  {:spec CartItemRemoveEvent}
  (fn [db [_ id]] ...))

;; Sub-return schema (attached via :spec on reg-sub)
(def CartTotal :double)

(rf/reg-sub :cart/total
  {:spec CartTotal}
  ...)

;; app-db slice schema (registered separately via reg-app-schema)
(def CartState
  [:map
   [:items     [:vector CartItem]]
   [:loading?  :boolean]
   [:checkout-state [:enum :idle :submitting :error]]])

(rf/reg-app-schema [:cart] CartState)

;; Whole-app-db root schema (path = [])
(rf/reg-app-schema [] AppDbRoot)

;; Fx args schema
(rf/reg-fx :http
  {:spec [:map [:method :keyword] [:url :string] [:body {:optional true} :any]]}
  ...)

;; Closed schema for an external boundary
(def IncomingWebhookPayload
  [:map {:closed true}
   [:event_type [:enum "user.created" "user.updated" "user.deleted"]]
   [:user_id    :int]
   [:timestamp  :int]])

(rf/reg-event-fx :webhook/handle
  {:spec [:cat [:= :webhook/handle] IncomingWebhookPayload]
   :interceptors [(rf/spec/validate-at-boundary)]}    ;; rejects payload at boundary if invalid
  ...)
```

**Pattern-level discipline (per [010](010-Schemas.md) and [000-Vision.md](000-Vision.md)):**

- **Open by default.** Don't add `:closed true` unless the data crosses a process boundary.
- **Don't model object hierarchies.** A schema describes the *shape* of an open map. There are no classes.
- **Schemas grow additively.** Once a schema ships, you can add new optional keys; you cannot remove or rename existing keys without bumping a version (Spec-ulation).
- **Validation runs in dev, elides in prod** by default. Use `:spec/validate-at-boundary` interceptor for runtime validation in prod at system boundaries.

**AI-first checklist:**

- [ ] Schema describes shape, not types of objects.
- [ ] Open by default (no `:closed true` unless at a boundary).
- [ ] Path-based for `app-db` slices.
- [ ] Attached as `:spec` on the relevant `reg-*` if it describes a registration's input/output.
- [ ] Schema is *named* — `(def CartState ...)` — not inline.
- [ ] Conforms to [010](010-Schemas.md) registration shape.

### CP-9. Scaffold an SSR setup

**When to use this prompt:** the user wants the app to render server-side — for SEO, fast first paint, social-media link previews, or simply "no client JS until interaction."

**Pre-flight checks:**

1. **Identify the per-request setup events.** What does the server need to dispatch before rendering? Typically: `:auth/load-session`, `:rf.route/handle-url-change`, feature-specific `:feature/load-initial-data`.
2. **Identify the fx that need server platforms.** HTTP for sure. Anything else? Confirm with `(rf/handlers :fx)` and audit each fx's `:platforms` metadata.
3. **Identify the views that may render under SSR.** All of them, in principle. Confirm none use Form-2 outer-fn-side-effects (they don't run on the server).
4. **Confirm the root view is registered** via `reg-view`, not a plain Reagent fn.

**Server-side template:**

```clojure
(defn handle-request [request]
  (let [frame-id (gensym :ssr-frame)]
    (rf/with-frame [f (rf/make-frame
                       {:id           frame-id
                        :on-create    [:rf/server-init request]})]
      ;; rebound to f
      (let [final-db @(rf/get-frame-db f)
            hiccup   ((rf/get-view :app/root))                ;; the registered root view
            html     (rf/render-to-string hiccup {:frame f})
            payload  {:rf/version "1.0"
                      :rf/frame-id frame-id
                      :rf/app-db   final-db
                      :rf/render-hash (hash hiccup)}]
        {:status 200
         :headers {"Content-Type" "text/html"}
         :body (page-template html (pr-str payload))}))))

(defn page-template [body-html serialised-payload]
  (str "<!DOCTYPE html>"
       "<html><head>...</head>"
       "<body>"
       "<div id='app'>" body-html "</div>"
       "<script id='__rf_payload'>" serialised-payload "</script>"
       "<script src='/main.js'></script>"
       "</body></html>"))
```

**Server-side `:rf/server-init` handler:**

```clojure
(rf/reg-event-fx :rf/server-init
  {:doc       "Server-side per-request init. Runs setup events from the request context."
   :platforms #{:server}}
  (fn handler-rf-server-init [{:keys [db]} [_ request]]
    {:db (assoc db
                :session (:session request)
                :route   (parse-url (:uri request) routes))
     :fx [[:http {:method :get :url "/api/auth/me" :on-success [:auth/me-loaded]}]
          ;; ... feature-specific data fetches
          ]}))
```

The drain settles before `with-frame` returns; the final state is captured.

**Client-side bootstrap:**

```clojure
(defonce client-frame
  (rf/reg-frame :app/main {:on-create [:client/bootstrap]}))

(defn read-server-payload []
  (-> (.getElementById js/document "__rf_payload")
      .-textContent
      cljs.reader/read-string))

(defn boot! []
  (let [payload (read-server-payload)]
    (when payload
      (rf/dispatch-sync [:rf/hydrate payload] {:frame :app/main}))
    (rdc/render (.getElementById js/document "app")
                [(rf/get-view :app/root)])))

(boot!)
```

**Pattern-level discipline (per [011](011-SSR.md)):**

- All server-side fx have `:platforms` containing `:server`. Anything else is skipped (with a `:rf.fx/skipped-on-platform` trace event).
- The root view is registered (`reg-view`), pure, returns a serialisable render-tree.
- The render-tree → string emitter is JVM-runnable; no React on the server.
- The hydration payload is open-map-shaped and conforms to `:rf/hydration-payload`.
- Mismatch detection runs on first client render; the runtime emits `:rf.ssr/hydration-mismatch` traces on divergence.

**AI-first checklist:**

- [ ] Per-request frame is created and destroyed within `with-frame`.
- [ ] All setup events have `:platforms` set or are universal (no `:platforms` key, runs everywhere).
- [ ] Render-tree → string is pure; no React, no DOM, no JS APIs on the server.
- [ ] Hydration payload includes `:rf/version`, `:rf/frame-id`, `:rf/app-db`, optional `:rf/render-hash`.
- [ ] Client `[:rf/hydrate ...]` event seeds before first render.
- [ ] First-client-render hash matches server hash (test in dev with mismatch detection on).
- [ ] Page template injects the payload as a `<script>` element with `id="__rf_payload"`.
- [ ] All client-only effects (DOM mutation, localStorage) are tagged `:platforms #{:client}`.

## Cross-references

- [Principles.md §Construction prompts as a deliverable](Principles.md#construction-prompts-as-a-deliverable) — why this artefact exists.
- [000-Vision.md](000-Vision.md) — the goals and contract.
- [MIGRATION.md](MIGRATION.md) — the sibling artefact for upgrades.
- [API.md](API.md) — signatures the prompts produce calls against.

## Worked examples (each prompt, in action)

The [7GUIs example series](../../examples/7guis/README.md) and the [login example](../../examples/login/core.cljs) demonstrate every prompt in working code:

| Prompt | Example |
|---|---|
| CP-1 (event handler) | All examples; especially the bookkeeping events in [Flight Booker](../../examples/7guis/03_flight_booker.cljs) and the undo events in [Circle Drawer](../../examples/7guis/06_circle_drawer.cljs) |
| CP-2 (subscription) | [Temperature Converter](../../examples/7guis/02_temperature.cljs) shows `:<-` chains; [Flight Booker](../../examples/7guis/03_flight_booker.cljs) shows multi-input chains for derived enabled-state |
| CP-3 (registered fx) | [Login](../../examples/login/core.cljs) shows `:platforms` metadata + a stub fx for tests; [Timer](../../examples/7guis/04_timer.cljs) shows `:dispatch-later`; [Flight Booker](../../examples/7guis/03_flight_booker.cljs) shows a custom `:notify` fx |
| CP-4 (registered view) | All examples use Var-reference Form-1 (canonical) |
| CP-5 (state machine) | [Login](../../examples/login/core.cljs) — full transition table with guards, actions, terminal states |
| CP-6 (feature scaffold) | [Login](../../examples/login/core.cljs) is a full feature: schema + events + subs + views + machine + tests |
| CP-7 (route) | [Routing example](../../examples/routing/core.cljs) — three-page app (home / articles / article-detail / 404), `:rf.route/navigate`, `:rf.route/handle-url-change`, `route-link`, server-and-client-shared handler |
| CP-8 (schema) | All examples register `app-db` slice schemas; [Login](../../examples/login/core.cljs) and [Flight Booker](../../examples/7guis/03_flight_booker.cljs) also attach event schemas |
| CP-9 (SSR setup) | [SSR example](../../examples/ssr/core.cljc) — single `.cljc` file demonstrating both server (`handle-request` returning HTML+payload) and client (`:rf/hydrate` seeding) flows; JVM-runnable smoke test |
