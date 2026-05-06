# Construction Prompts (AI Agent Templates)

> Status: Drafting. CP-1 through CP-9 are all filled in with full templates (pre-flight checks, registration form, idiomatic naming, tests, AI-first checklists, worked examples). Cross-linked to the 7GUIs example series and the login feature for "this prompt, in working code" references.
>
> Per [reorient.md](reorient.md): the AI-first stance demands a complementary artefact to MIGRATION.md — *construction prompts* that tell an AI how to build new code in this pattern. Templates per kind. Worked examples for CP-7 (route) and CP-9 (SSR setup) are still pending — see beads rf2-2nq and rf2-2yh.

## Purpose

[MIGRATION.md](MIGRATION.md) is the AI prompt for *upgrading* old re-frame code. **Construction Prompts** is the AI prompt for *creating new* code in the re-frame2 pattern. Where MIGRATION rewrites existing shapes, Construction Prompts scaffolds shapes that don't yet exist.

This artefact is intended to be:

- **Per-kind.** Separate templates for events, subscriptions, schema-bound views, state machines, features, routes, effects.
- **Self-contained.** Each prompt provides enough context for an AI to scaffold the kind without needing to read the full EP set.
- **Shape-aware via the host's idiom.** The pattern requires shape description; the *mechanism* is host-specific. **Dynamic hosts** (CLJS + Malli, Python + Pydantic, JS + Zod): the prompts attach `:spec` metadata referring to a schema. **Static hosts** (TypeScript, Kotlin): the prompts emit type-annotated registrations whose shapes the compiler enforces. Either way, an AI reading the artefact has a description of the shapes; the prompts adapt to the host.
- **Worked-example-heavy.** Each prompt ends with one or two complete, runnable examples.
- **Aligned with the [Goals in 000](000-Vision.md) and the [eight AI-first properties in reorient.md](reorient.md).** A construction-prompt-generated artefact is, by construction, AI-first.

## Catalogue (planned)

Each entry will become its own section or sub-document:

### CP-1. Add an event handler

**When to use this prompt:** the user wants the app to react to something — a button click, a server response, a timer tick, a child machine sending a message. If the reaction modifies state only, prefer `reg-event-db`. If it also produces side-effects (HTTP call, navigation, dispatch chain, local-storage write), use `reg-event-fx`.

**Pre-flight checks (mandatory):**

1. **Choose an id.** Convention: `:feature/verb-noun` or `:feature.subfeature/verb-noun`. Lowercase, kebab-case, namespaced. Examples: `:auth/login`, `:auth.password/reset`, `:cart.item/remove`.
2. **Verify the id is unused.** Query the registry: `(rf/handlers :event)` returns the map of registered events. If your id collides, pick another or coordinate with the user.
3. **Identify the relevant `app-db` paths.** Query `(rf/app-schemas)` to see registered schemas. If the event reads or writes a schema-bound path, your handler must produce schema-compliant output (validation runs in dev).

**Template — `reg-event-db` (state-only):**

```clojure
(rf/reg-event-db :feature/verb-noun
  {:doc  "One-sentence what-and-why."
   :spec EventSchema}                         ;; optional Malli schema for the event vector
  (fn handler-feature-verb-noun [db [_ arg1 arg2]]
    ;; Pure: read db and event args, return the new db.
    ;; No side-effects. No dispatching from inside the handler — return effects from reg-event-fx if needed.
    (assoc-in db [:feature :path] arg1)))
```

**Template — `reg-event-fx` (with effects):**

```clojure
(rf/reg-event-fx :feature/verb-noun
  {:doc          "One-sentence what-and-why."
   :spec         EventSchema
   :interceptors []}                          ;; optional; usually empty
  (fn handler-feature-verb-noun [{:keys [db] :as cofx} [_ arg1]]
    ;; Pure: read db and any injected cofx, return an effect map.
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

**Pre-flight checks:**

1. **Choose an id.** Convention: `:feature/property` or `:feature/computed-value`. Examples: `:cart/total`, `:cart/items-count`, `:auth/logged-in?`.
2. **Verify the id is unused.** `(rf/handlers :sub)`.
3. **Decide the input.** Either reads `app-db` directly (Layer 1 sub) or composes other subs (Layer 2 / signal-graph chained sub via `:<-`).
4. **Check schemas.** If the sub's return value has a registered schema (rare for layer-1, common for layer-2), align the output shape.

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

**Pre-flight checks:**

1. **Choose an id.** Convention: a single namespaced keyword. Examples: `:http`, `:localstorage`, `:nav/replace`, `:websocket/send`.
2. **Verify the id is unused.** `(rf/handlers :fx)`.
3. **Decide the platform.** Server-only? Client-only? Both? Default `#{:client}` if absent.
4. **Identify the args shape.** What does the effect map's value look like? Does it need a schema?

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

**Pre-flight checks (mandatory):**

1. **Choose a view id.** Convention: `:feature/component-name` or `:feature.area/component-name`. Plural component names for lists (`:cart/items`), singular for single-instance (`:cart/total`).
2. **Verify the id is unused.** `(rf/handlers :view)`.
3. **Identify the subscriptions the view will read.** Each must be already registered (`(rf/handlers :sub)`) or scaffolded as a sibling — if missing, invoke CP-2.
4. **Identify the events the view will dispatch.** Each must be already registered (`(rf/handlers :event)`) — if missing, invoke CP-1.

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

**Pre-flight checks:**

1. **Choose a machine id.** Convention: `:feature.flow/machine` or `:feature/flow`. Examples: `:auth.login/flow`, `:checkout/flow`, `:video-player/flow`.
2. **Verify the id is unused.** `(rf/handlers :machine)`.
3. **Choose the `:machine-path`.** Where in `app-db` the snapshot lives. Convention: `[:feature :machine :flow]`. The snapshot has shape `{:state ... :context ...}`.
4. **List the states.** Discrete, named, named in past tense or noun form (`:idle`, `:loading`, `:loaded`, `:error`).
5. **List the events that move between states.** Each event triggers exactly one transition.
6. **Identify guards (predicates) and actions (side-effects-as-data).** Each is a registered id.

**Template — transition table (xstate-flavoured):**

```clojure
(def auth-login-flow
  {:id      :auth.login/flow
   :initial :idle
   :context {:attempts 0 :error nil}
   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :actions [:auth.login/clear-error]}}}

    :submitting
    {:entry [:auth.login/issue-request]
     :on    {:auth.login/success {:target  :authed
                                  :actions [:auth.login/store-session]}
             :auth.login/failure {:target  :error-shown
                                  :cond    :auth.login/under-retry-limit
                                  :actions [:auth.login/record-error]}
             :auth.login/failure {:target  :locked-out
                                  :actions [:auth.login/lock-account]}}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting}}}

    :authed
    {:meta {:terminal? true}}

    :locked-out
    {:meta {:terminal? true}}}})
```

**Template — guards and actions (registered separately, definition/implementation split):**

```clojure
(rf/reg-machine-guard :auth.login/under-retry-limit
  {:doc "Did this login flow have fewer than 3 prior attempts?"}
  (fn guard-under-retry-limit [{:keys [context]} _event]
    (< (:attempts context) 3)))

(rf/reg-machine-action :auth.login/clear-error
  {:doc "Reset error and increment attempts in context."}
  (fn action-clear-error [{:keys [context]} _event]
    {:context (assoc context :error nil)}))

(rf/reg-machine-action :auth.login/issue-request
  {:doc "Issue the HTTP login request. Returns effects for the runner."}
  (fn action-issue-request [_snapshot [_ creds]]
    {:fx [[:http {:method :post :url "/api/login" :body creds
                  :on-success [:auth.login/success]
                  :on-error   [:auth.login/failure]}]]}))
```

**Template — registering as an event handler:**

```clojure
(rf/reg-event-fx :auth.login/event-handler
  {:doc          "All :auth.login/* events route through here, interpreted as a machine."
   :machine-path [:auth :login :flow]
   :machine      auth-login-flow}
  (rf/machine-handler [:auth :login :flow] auth-login-flow))
```

The `:machine-path` metadata + `machine-handler` body means the runtime treats this handler as a machine: it reads the snapshot at `[:auth :login :flow]`, applies `machine-transition`, writes the new snapshot, and emits the action effects.

**Pattern-level discipline:**

- The transition table is **pure data** (per [005](005-StateMachines.md)) — serialisable, AI-readable, visualisable.
- Guards and actions are **registered separately** by id; the table references them. (Definition/implementation split.)
- `machine-transition` is a **pure function** — `(definition, snapshot, event) → [next-snapshot, effects]`. JVM-runnable, headless-testable.
- The actor system boundary is the frame; cross-machine messages within a frame settle via run-to-completion drain.

**Headless test:**

```clojure
(deftest auth-login-flow-happy-path
  (let [snapshot {:state :idle :context {:attempts 0 :error nil}}]
    (let [[s1 _fx] (rf/machine-transition auth-login-flow snapshot
                     [:auth.login/submit {:email "a@b.com" :password "..."}])]
      (is (= :submitting (:state s1))))
    (let [[s2 _fx] (rf/machine-transition auth-login-flow {:state :submitting :context {:attempts 0}}
                     [:auth.login/success {:user {:id 1}}])]
      (is (= :authed (:state s2))))))
```

**AI-first checklist:**

- [ ] Machine id is namespaced.
- [ ] All states are listed in `:states`; no string-based or computed state names.
- [ ] Every event a state listens to is in its `:on` map.
- [ ] Guards and actions are registered by id, referenced in the table; no inline lambdas.
- [ ] Transition table conforms to `:rf/transition-table` schema (per [Spec-Schemas](Spec-Schemas.md)).
- [ ] Headless test passes via `machine-transition` (no event dispatch needed).
- [ ] If the machine has terminal states, they're marked `:meta {:terminal? true}`.
- [ ] Trace events on `:machine/transition` are visible in 10x / re-frame-pair.

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
2. **Identify the URL pattern for each.** `/` → `:route/home`, `/cart` → `:route/cart`, `/cart/items/:id` → `:route/cart.item-detail`.
3. **Verify the route ids are unused.** `(rf/handlers :route)` if your impl registers routes; otherwise check the route-table data structure.

**Per [reorient.md](reorient.md):** routing is *state plus events*, not a separate subsystem. The URL is a derivable view of `app-db`; navigation is an event.

**Template — route registry (data):**

```clojure
(def routes
  [{:id   :route/home
    :path "/"}
   {:id   :route/cart
    :path "/cart"}
   {:id   :route/cart.item-detail
    :path "/cart/items/:id"
    :params {:id :uuid}}])

(rf/reg-app-schema [:route]
  [:map
   [:id     :keyword]
   [:params {:optional true} :map]])
```

**Template — `:route/navigate` event:**

```clojure
(rf/reg-event-fx :route/navigate
  {:doc  "Navigate to a registered route. Updates app-db and the URL."
   :spec [:cat [:= :route/navigate] :keyword [:? :map]]}
  (fn handler-route-navigate [{:keys [db]} [_ route-id params]]
    (let [route (route-by-id route-id)]
      {:db        (assoc db :route {:id route-id :params (or params {})})
       :fx        [[:nav/push-url (route-url route params)]]})))
```

**Template — `:route/handle-url-change` event (browser back/forward, deep links):**

```clojure
(rf/reg-event-fx :route/handle-url-change
  {:doc "Triggered by URL change (popstate or initial load). Sets app-db's route slice from the URL."}
  (fn handler-route-handle-url-change [{:keys [db]} [_ url]]
    (let [{:keys [route-id params]} (parse-url url routes)]
      {:db (assoc db :route {:id route-id :params params})})))
```

**Template — `:route` sub:**

```clojure
(rf/reg-sub :route
  {:doc "The current route map: {:id ... :params ...}"}
  (fn sub-route [db _] (:route db)))

(rf/reg-sub :route/id
  :<- [:route]
  (fn [route _] (:id route)))
```

**Template — route-aware root view:**

```clojure
(def root-view
  (rf/reg-view :app/root
    (fn render-app-root []
      (let [route-id @(subscribe [:route/id])]
        (case route-id
          :route/home             [home-page]
          :route/cart             [cart-page]
          :route/cart.item-detail [cart-item-detail-page]
          [not-found-page])))))
```

**Template — `:nav/push-url` fx (CLJS reference, client only):**

```clojure
(rf/reg-fx :nav/push-url
  {:doc       "Push a URL onto the browser history."
   :platforms #{:client}}
  (fn fx-nav-push-url [_m url]
    (.pushState js/history nil "" url)))
```

**Template — wiring (called once at app boot):**

```clojure
(defn install-router! [frame-id]
  (.addEventListener js/window "popstate"
    #(rf/dispatch [:route/handle-url-change (.. js/window -location -pathname)] {:frame frame-id}))
  (rf/dispatch [:route/handle-url-change (.. js/window -location -pathname)] {:frame frame-id}))
```

**Pattern-level discipline:**

- The route is in `app-db`; the URL is derivable. Never make routing state live outside `app-db`.
- Navigation is an event. Don't call browser APIs directly from view code; dispatch `:route/navigate`.
- Server-side renders set the route from the request URL via a server cofx; the same `:route/handle-url-change` handler runs on the server (it has no `:platforms` marker, so it's universal).

**AI-first checklist:**

- [ ] Route ids are namespaced (`:route/...`).
- [ ] The route table is data; route registration is `reg-app-schema` + a data structure.
- [ ] All navigation goes through `:route/navigate` (no inline `pushState` in view bodies).
- [ ] `:route/handle-url-change` resolves URL → route id (works on both server and client).
- [ ] The root view dispatches on `:route/id`; per-page views are registered separately.
- [ ] Server-side renders set the route via the request cofx before `:on-create` finishes.

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

**Pattern-level discipline (per [010](010-Schemas.md) and [reorient.md](reorient.md)):**

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

1. **Identify the per-request setup events.** What does the server need to dispatch before rendering? Typically: `:auth/load-session`, `:route/handle-url-change`, feature-specific `:feature/load-initial-data`.
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

## How an AI uses these

The intended workflow:

1. The user describes the thing they want (e.g., "add a login form with validation").
2. The AI selects the relevant construction prompt(s) — typically **CP-6 (feature)** as the umbrella, possibly invoking **CP-1**, **CP-2**, **CP-4**, and **CP-8** internally.
3. The AI consults the existing registry (via the public registrar query API — see [002 §The public registrar query API](002-Frames.md)) to choose an id namespace that doesn't collide.
4. The AI consults existing schemas to align with the shapes already in use.
5. The AI generates the artefact — registration calls, view fn, schema, smoke test — adhering to the prompt's conventions.
6. The AI runs the smoke test (or asks the user to) and reports.

## What construction prompts do *not* cover

- Migration of existing code (use [MIGRATION.md](MIGRATION.md)).
- Architectural decisions (use the EP docs and [reorient.md](reorient.md)).
- Choice of host language or substrate (the prompts are CLJS-flavoured by default; non-CLJS variants would need their own).

## Cross-references

- [reorient.md §Construction prompts as a deliverable](reorient.md) — why this artefact exists.
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
| CP-7 (route) | [Routing example](../../examples/routing/core.cljs) — three-page app (home / articles / article-detail / 404), `:route/navigate`, `:route/handle-url-change`, `route-link`, server-and-client-shared handler |
| CP-8 (schema) | All examples register `app-db` slice schemas; [Login](../../examples/login/core.cljs) and [Flight Booker](../../examples/7guis/03_flight_booker.cljs) also attach event schemas |
| CP-9 (SSR setup) | [SSR example](../../examples/ssr/core.cljc) — single `.cljc` file demonstrating both server (`handle-request` returning HTML+payload) and client (`:rf/hydrate` seeding) flows; JVM-runnable smoke test |
