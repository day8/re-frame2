# Construction Prompts (AI Agent Templates)

> **Type:** Construction Prompts
> Per-kind AI-scaffolding templates for new code in the re-frame2 pattern. Sibling to [MIGRATION.md](../migration/from-re-frame-v1/README.md) (which covers upgrades of existing code).

CP-10 (story / variant / workspace) is post-v1; its sketch lives in 007-Stories.md (CP-10 sketch) and the full template lands here when `re-frame.stories` ships.

## Purpose

[MIGRATION.md](../migration/from-re-frame-v1/README.md) is the AI prompt for *upgrading* old re-frame code. **Construction Prompts** is the AI prompt for *creating new* code in the re-frame2 pattern. Where MIGRATION rewrites existing shapes, Construction Prompts scaffolds shapes that don't yet exist.

This artefact is intended to be:

- **Per-kind.** Separate templates for events, subscriptions, schema-bound views, state machines, features, routes, effects.
- **Self-contained.** Each prompt provides enough context for an AI to scaffold the kind without needing to read the full Spec set.
- **Shape-aware via the host's idiom.** The pattern requires shape description; the *mechanism* is host-specific. **Dynamic in-scope hosts** (CLJS + Malli, Squint + Zod): the prompts attach `:schema` metadata referring to a schema. **Static in-scope hosts** (TypeScript, Melange / ReScript / Reason, Fable (F#), Scala.js, PureScript, Kotlin/JS): the prompts emit type-annotated registrations whose shapes the compiler enforces. Either way, an AI reading the artefact has a description of the shapes; the prompts adapt to the host.
- **Worked-example-heavy.** Each prompt ends with one or two complete, runnable examples.
- **Aligned with the [Goals in 000](000-Vision.md) and the [nine AI-first properties in Principles.md](Principles.md).** A construction-prompt-generated artefact is, by construction, AI-first.

## Shared pre-flight (applies to every CP)

Every CP below begins with the same mechanics; rather than restate them in each, treat the following as a shared preamble. Each CP's "Pre-flight checks" section calls out only the *delta* — the kind-specific naming convention or extra check — on top of these.

1. **Choose a namespaced id.** Lowercase, kebab-case. The id-prefix matches the feature (per [Conventions §Feature-modularity prefix convention](Conventions.md#feature-modularity-prefix-convention)).
2. **Verify the id is unused.** Query the registry via the public registrar query API for the relevant kind (e.g., `(rf/registrations :event)`, `(rf/registrations :sub)`, `(rf/registrations :fx)`, `(rf/registrations :view)`, `(rf/registrations :route)`).
3. **Consult registered schemas** (`(rf/app-schemas)`, `(rf/app-schema-at <path>)`) so the new artefact aligns with shapes already in use.

## Catalogue

Each entry below is one CP:

### CP-1. Add an event handler

**When to use this prompt:** the user wants the app to react to something — a button click, a server response, a timer tick, a child machine sending a message. There is one event-registration form, `reg-event`: its handler takes coeffects and the event vector and returns a closed effects map. If the reaction modifies state only, return `{:db ...}`. If it also produces side-effects (HTTP call, navigation, dispatch chain, local-storage write), add `:fx` alongside (or instead of) `:db`.

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** `:feature/verb-noun` or `:feature.subfeature/verb-noun`. Examples: `:auth/login`, `:auth.password/reset`, `:cart.item/remove`. The relevant registry kind is `:event`.
- **Call-shape convention** (per [Principles §Name over place](Principles.md#name-over-place) and [002 §Routing](002-Frames.md#routing-the-dispatch-envelope)): `[<id>]` for trivial events, `[<id> <single-scalar>]` for single-argument events, `[<id> {<key> <val> ...}]` for multi-argument events. Multi-positional `[<id> a b c]` is accepted by the runtime; the linter nudges new code toward the map shape.
- **Schema-bound paths.** If the event reads or writes a schema-bound `app-db` path, your handler must produce schema-compliant output (validation runs in dev).

**Template — `reg-event` (state-only):**

```clojure
(rf/reg-event :feature/verb-noun
  {:doc    "One-sentence what-and-why."
   :schema EventSchema}                       ;; optional Malli schema for the event vector
  (fn handler-feature-verb-noun [{:keys [db]} [_ {:keys [field-1 field-2]}]]
    ;; Pure: read db (and any injected cofx) from coeffects, return an effect map.
    ;; Multi-arg events take a single map payload; destructure named keys.
    ;; No side-effects. State change is the explicit :db effect — no db-only return shape.
    {:db (assoc-in db [:feature :path] field-1)}))
```

**Template — `reg-event` (with effects):**

```clojure
(rf/reg-event :feature/verb-noun
  {:doc    "One-sentence what-and-why."
   :schema EventSchema
   ;; Add :interceptors [...] here when this handler needs an event chain.
   }
  (fn handler-feature-verb-noun [{:keys [db] :as cofx} [_ payload]]
    ;; Pure: read db and any injected cofx, return an effect map.
    ;; payload is a single scalar (for single-arg events) or a map (for multi-arg events
    ;; — destructure named keys: [_ {:keys [field-1 field-2]}]).
    ;; Effects are data; the runtime interprets them. Add :fx for side-effects.
    {:db        (assoc-in db [:feature :loading?] true)
     :fx        [[:http {:method :get :url "/api/feature" :on-success [:feature/loaded]}]]}))
```

**Naming the handler fn:** match the id's path and verb. `:cart.item/remove` → `handler-cart-item-remove`. The name shows up in stack traces and tooling; anonymous lambdas hide.

**Smoke test:**

```clojure
(deftest feature-verb-noun-test
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:feature/initialise] {:frame f})   ;; seed via a setup dispatch
    (rf/dispatch-sync [:feature/verb-noun "value"] {:frame f})
    (is (= "value" (get-in (rf/app-db-value f) [:feature :path])))))
```

**AI-first checklist before declaring done:**

- Id is namespaced and unused.
- Handler is pure (no side-effects in the body; all effects — including the `:db` write — are *returned* in the effect map).
- `:doc` is present and one sentence.
- Shape is described by the host's idiom: `:schema` (Malli/Pydantic/Zod) in dynamic hosts, a type definition in static hosts. If neither: shape conformance is checked by tests/fixtures.
- Handler has a meaningful name (not `fn`).
- Smoke test passes.
- If the handler writes a schema-bound `app-db` path (in a schema-bearing implementation), the test asserts the post-state validates.

**Example — full worked artefact:**

```clojure
(ns my-app.cart.events
  (:require [re-frame.core :as rf]
            [malli.core :as m]))

(def CartItemRemoveEvent
  [:tuple [:= :cart.item/remove] :uuid])      ;; [event-id item-id]

(rf/reg-event :cart.item/remove
  {:doc    "Remove an item from the cart by id."
   :schema CartItemRemoveEvent}
  (fn handler-cart-item-remove [{:keys [db]} [_ item-id]]
    {:db (update-in db [:cart :items] (fn [items] (vec (remove #(= item-id (:id %)) items))))}))
```

### CP-2. Add a subscription

**When to use this prompt:** the user wants a derived view of `app-db` — a computed value views can read. A subscription is *not* a place to put side-effects; it is a pure function from state to value.

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** `:feature/property` or `:feature/computed-value`. Examples: `:cart/total`, `:cart/items-count`, `:auth/logged-in?`. The relevant registry kind is `:sub`.
- **Call-shape convention** (per [Principles §Name over place](Principles.md#name-over-place) and [002 §Routing](002-Frames.md#routing-the-dispatch-envelope), same as for events): `[<id>]` for trivial subs, `[<id> <single-scalar>]` for single-argument subs (`[:user-by-id 42]`), `[<id> {<key> <val> ...}]` for multi-argument subs (`[:items-filtered {:status :pending :limit 20}]`). Multi-positional `[<id> a b c]` is accepted by the runtime; the linter nudges new code toward the map shape.
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

- Sub id is namespaced and unused.
- Body is pure.
- Layer-2 subs use `:<-` chains; they don't read `app-db` directly.
- `:doc` is present.
- Smoke test runs headlessly via `compute-sub`.
- If the return value has a schema, the test asserts conformance.

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
   :schema    [:map
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

;; in a test — :fx-overrides and :on-create are record-config keys, so they ride
;; the advanced record-config `re-frame.frame/make-frame`, not the EP-0023 object
;; constructor `rf/make-frame` (which takes :images and fails loud on a record-only key)
(rf/with-new-frame [f (re-frame.frame/make-frame {:fx-overrides {:http :http.canned-200}
                                                  :on-create    [:feature/load]})]
  ...)
```

The override seam is **id-valued at the pattern level**. The CLJS reference also accepts function values for one-off CLJS lambdas, but registered-fx-id is the portable form (works for SSR, serialises across the wire, listable in tooling).

**AI-first checklist:**

- Fx id is namespaced.
- `:platforms` is set explicitly (don't rely on the default).
- `:schema` describes the args shape.
- `:doc` is present.
- Handler dispatches `:on-success`/`:on-error` (or equivalent) on the originating frame.
- Handler does NOT mutate `app-db` — that's events' job.
- A registered test-stub fx exists with a stable id (e.g., `:fx-id.canned`) for tests.

**Example — local storage:**

```clojure
(rf/reg-fx :localstorage/set
  {:doc       "Write a key/value pair to browser localStorage. Client only."
   :platforms #{:client}
   :schema    [:map
               [:key   :string]
               [:value :any]]}
  (fn fx-localstorage-set [_m {:keys [key value]}]
    (.setItem js/localStorage key (pr-str value))))
```

### CP-4. Add a registered view

**When to use this prompt:** the user wants a UI component that renders state and dispatches events. Always prefer `reg-view` over plain Reagent fns for any view that may render under a non-default frame (which, with SSR-per-request, is most views).

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** `:feature/component-name` or `:feature.area/component-name`. Plural component names for lists (`:cart/items`), singular for single-instance (`:cart/total`). The relevant registry kind is `:view`.
- **Identify the subscriptions the view will read.** Each must be already registered (`(rf/registrations :sub)`) or scaffolded as a sibling — if missing, invoke CP-2.
- **Identify the events the view will dispatch.** Each must be already registered (`(rf/registrations :event)`) — if missing, invoke CP-1.

**Template — Form-1 (simple render fn):**

```clojure
(rf/reg-view ^{:doc    "One-sentence what-and-why."
               :schema [:cat :string :int]}  ;; optional Malli schema for the props vector
             component-name [label count-prop]
  (let [items @(subscribe [:feature/items])]   ;; frame-bound; resolves on the surrounding frame
    [:div.feature
     [:h3 label]
     [:p (str "Count: " count-prop)]
     (for [item items]
       ^{:key (:id item)}
       [:div.item
        [:span (:label item)]
        [:button {:on-click #(dispatch [:feature/select (:id item)])} "Select"]])]))
```

**Template — Form-2 (closure for once-on-mount setup):**

```clojure
(rf/reg-view component-name [label]
  ;; Outer body: runs once on mount. Use for setup that should fire once per
  ;; component lifecycle (e.g., dispatching an :on-create-style init event,
  ;; registering a frame, opening a websocket).
  (dispatch [:feature/component-mounted])
  (fn render-feature-component-name [label]
    ;; Inner render fn: runs every render.
    (let [n @(subscribe [:feature/count])]
      [:div label ": " n])))
```

**Pattern-level discipline (per [004](004-Views.md) and [011](011-SSR.md)):**

- Body is **pure** given the inputs (props + frame-bound subs).
- Output is **serialisable data** (hiccup, no embedded JS objects).
- **No render-time side-effects** — no `js/setTimeout`, no DOM mutations, no fetch.
- Side-effects belong in event handlers; the view dispatches.

**Smoke test (headless render):**

```clojure
(deftest feature-component-name-renders
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:feature/initialise] {:frame f})   ;; seed via a setup dispatch
    (let [hiccup [component-name "test-label" 42]
          html   (rf/render-to-string hiccup {:frame f})]
      (is (str/includes? html "test-label"))
      (is (str/includes? html "Count: 42")))))
```

**AI-first checklist:**

- View id is namespaced and unused.
- All subs/events the view uses are registered.
- Body is pure; no render-time side-effects.
- `:on-click`/`:on-change` lambdas dispatch named events (no inline `swap!`/`reset!`).
- Output round-trips through `render-to-string` (smoke test passes both server-side and client-side).
- If the props vector has a schema, validation passes for the test inputs.

**Example — full worked artefact:**

```clojure
(rf/reg-view ^{:doc "Total cost and item count for the current cart."} summary []
  (let [items @(subscribe [:cart/items])
        total @(subscribe [:cart/total])]
    [:div.cart-summary
     [:span (str (count items) " items")]
     [:span (str "$" (format "%.2f" total))]
     [:button {:on-click #(dispatch [:cart/checkout])} "Checkout"]]))
```

### CP-5. Scaffold a state machine

**When to use this prompt:** the user describes a multi-step interaction with discrete, named states — a login flow, a checkout wizard, a video player, a modal lifecycle, a websocket connection. If you can list the states and the events that move between them, you have a machine.

**Key idea: the machine IS the event handler.** A machine is registered as one `reg-event` whose body comes from `make-machine-handler`. Sub-events route in: `(rf/dispatch [:my/machine [:my-input arg ...]])`.

**Default form: named guards and actions in the machine's `:guards` / `:actions` maps.** The transition table references guards and actions by **keyword** (`:under-retry-limit`, `:clear-error`); the bodies live in the machine's own `:guards` / `:actions` maps inside `make-machine-handler`. This is the **default** because the named id is a **name** that is **reusable, addressable, and clearer for humans, tools, and AIs** — visualisers label arrows with the id, AIs and conformance fixtures resolve the id against the machine's `:guards` / `:actions` map, and tests stub by id. (The rationale is *not* source visibility: an inline fn's `:source-code` text is co-located on its enclosing node in dev, per [005 §Source-coord stamping](005-StateMachines.md#source-coord-stamping), so an inline body is inspectable — it just has no public name to address.) Inline fns are an **escape hatch for trivial logic** (one-liners with no branching), not the default form. See [005 §Inspectability bias](005-StateMachines.md#inspectability-bias). **Resolution is machine-local** — there is no global `:machine-guard` / `:machine-action` registry; cross-machine reuse is via Clojure vars referenced from each machine's map.

**Pre-flight checks:**

1. **Choose a machine id.** Convention: `:feature.flow/machine` or `:feature/flow`. Examples: `:auth.login/flow`, `:checkout/flow`, `:video-player/flow`.
2. **Verify the id is unused.** `(rf/registrations :event)` — the machine reuses the `:event` registry kind. (No matching `:sub` registration is needed: machines are read through the framework-registered parametric sub `:rf/machine`; see "Where state lives" below.) `(rf/machines)` enumerates already-registered machines specifically.
3. **List the states.** Discrete, named (`:idle`, `:submitting`, `:authed`, `:error-shown`).
4. **List the inputs (sub-events) that move between states.** Each input triggers exactly one transition.
5. **Identify guards and actions; default to naming them in `:guards` / `:actions`.** Each guard `(fn [{:keys [data event]}] boolean)` and each action `(fn [{:keys [data event]}] {:data {...} :fx [...]})` is a key in the machine's `:guards` / `:actions` map (referenced from transitions by keyword). Per every machine callback receives a single context-map argument with `:data`, `:event`, `:state`, `:meta`. **Inline only when the body is a single non-branching expression.**

**Where state lives.** Every machine's snapshot lives at the runtime-managed path `[:rf.runtime/machines :snapshots <machine-id>]` in the frame's **runtime-db** partition (not app-db). For id `:auth.login/flow`, the snapshot is at `[:rf.runtime/machines :snapshots :auth.login/flow]` and contains `{:state ... :data ...}`. You do not pick the path — `make-machine-handler` does not accept a `:path` key. Per-frame isolation is automatic: each frame has its own runtime-db and thus its own `[:rf.runtime/machines :snapshots]` map. See [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live).

**Reading the snapshot in views.** The framework ships `:rf/machine` as a standard parametric sub. `@(rf/subscribe [:rf/machine :auth.login/flow])` returns the snapshot — no per-machine `reg-sub` needed. Destructure inline, or write a derived sub `:<- [:rf/machine <id>]` for projections. See [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub).

**Strict encapsulation.** Actions and guards see `{:state :data}` only — *no `:db`, no cofx*. Cross-cutting reads pass through the event payload; cross-cutting writes go via `:fx [[:dispatch <named-event>]]`. Action effect maps are `{:data {...} :fx [...]}` — symmetric with `reg-event`'s `{:db :fx}`. The named-bounce-event pattern is a feature, not a tax: it makes the cross-cutting concern visible in the trace, the registry, and 10x's event log (per [005 §Strict encapsulation](005-StateMachines.md#strict-encapsulation--actions-only-see-their-own-data)).

**Worked example — auth login flow (named guards/actions in `:guards` / `:actions` maps):**

```clojure
;; The machine — every guard and action is named in :guards / :actions and
;; referenced from the transition table by keyword. Resolution is
;; machine-local: the runtime calls (get-in spec [:guards :under-retry-limit])
;; etc. There is no global :machine-guard / :machine-action registry.

(rf/reg-event :auth.login/flow
  {:doc "Login flow: idle → submitting → authed / error-shown / locked-out."}
  (rf/make-machine-handler
    {:initial :idle
     :data    {:attempts 0 :error nil}

     :guards
     {:under-retry-limit
      ;; Has this login had fewer than 3 prior attempts?
      (fn [data _event]
        (< (:attempts data) 3))}

     :actions
     {:begin-submit
      ;; Clear the prior error and emit the HTTP request for credential check.
      (fn [_data [_ creds]]
        {:data {:error nil}
         :fx   [[:http {:method     :post
                        :url        "/api/login"
                        :body       creds
                        :on-success [:auth.login/flow [:succeeded]]
                        :on-error   [:auth.login/flow [:failed]]}]]})

      :record-failure
      ;; Bump the attempts counter and surface a credentials error.
      (fn [data _event]
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
- `make-machine-handler` validates every keyword reference against `:guards` / `:actions` at registration time — typos surface immediately as `:rf.error/machine-unresolved-guard` / `:rf.error/machine-unresolved-action`, not at runtime when the transition fires.

**Template — internal vs external self-transitions:**

```clojure
{:editing
 {:on
  {:drag-slider
   ;; internal self-transition — no :target, so :exit and :entry do NOT fire.
   ;; Update :data via an action named in the machine's :actions map.
   {:action :update-preview-radius}

   :poke
   ;; external self-transition — :target :same-state WITH :reenter? true, so
   ;; :exit and :entry DO fire (and a compound re-descends its :initial). The
   ;; :reenter? opt-in is required: a self / ancestor target is INTERNAL by
   ;; default (XState-v5).
   {:target :same-state
    :reenter? true
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

`:raise` is a **reserved fx-id inside `:fx`** — the machine handler routes it locally. `[:raise <ev>]` ≡ "back into THIS machine, processed before the snapshot is committed"; `[:dispatch <ev>]` is the standard runtime-queue dispatch. They have **different ordering semantics** — see [005 §Drain semantics gotchas](005-StateMachines.md#drain-semantics-gotchas). The `:rf.machine/spawn` and `:rf.machine/destroy` fx-ids are registered globally as the canonical actor-lifecycle surface.

**Template — `:rf.machine/spawn` for dynamic actors:**

```clojure
;; ... inside the machine spec:
:actions
{:spawn-fetch
 (fn [_ [_ url]]
   {:fx [[:rf.machine/spawn {:machine-id :request/protocol
                             :id-prefix  :request/protocol
                             :data       {:url url}
                             :on-spawn   (fn [{:keys [data id]}] (assoc data :pending-request id))
                             :start      [:begin]}]]})}

:states
{:idle {:on {:fetch {:target :loading :action :spawn-fetch}}}}
```

After this action, `(:pending-request data)` is the new actor's id; subsequent transitions can dispatch to it. The spawned actor's snapshot lives at `[:rf.runtime/machines :snapshots <gensym'd-id>]` in runtime-db (runtime-managed; the spawn-spec does not pick a location). The `:on-spawn` callback is an inline fn here — that's appropriate; it's a single non-branching `assoc`.

**Deeper guidance — see the appendix.** When you need the inline-fn vs named-action escape-hatch test, the v1 grammar subset, the parallel-regions decision between `:type :parallel` and N-machines-per-region, or first-class history states (`:type :history`), consult [CP-5 Machine Guide](CP-5-MachineGuide.md). It's a sibling appendix to keep CP-5 itself a build-facing prompt rather than a second machine spec.

**Pattern-level discipline:**

- The transition table is **pure data** (per [005](005-StateMachines.md)) — serialisable, AI-readable, visualisable.
- **Default to registered guards and actions.** Inline fns are an escape hatch for trivial logic, not the default form. See [005 §Inspectability bias](005-StateMachines.md#inspectability-bias).
- Action and guard slots are **single fn or single registered id** — no `:actions [a1 a2 a3]` vector form, no `{:and [...]}` compound-guard data form. Multi-step composition is fn composition; reused composition is registered with a meaningful name.
- Action signature: `(fn [{:keys [data event state meta]}] {:data {...} :fx [...]})` — single context-map argument. **Strict encapsulation**: no `:db`, no cofx — the runtime hard-disallows `:db` in the action's effect map (`:rf.error/machine-action-wrote-db`).
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
;; Possible because make-machine-handler is a pure factory.
;; Snapshots live at [:rf.runtime/machines :snapshots <id>] in runtime-db (runtime-managed):
;; the handler reads the snapshot from the :rf.db/runtime cofx and writes it back
;; as a :rf.db/runtime effect — NOT app-db's :db.
(deftest auth-login-happy-path-l2
  (let [handler (rf/make-machine-handler {:initial :idle ...})
        cofx    {:rf.db/runtime {:rf.runtime/machines {:snapshots {:auth.login/flow {:state :idle :data {}}}}}}
        effects (handler cofx [:auth.login/flow [:submit {:email "..."}]])
        runtime (:rf.db/runtime effects)]
    (is (= :submitting (get-in runtime [:rf.runtime/machines :snapshots :auth.login/flow :state])))))

;; Level 3 — registered in a test frame (full integration; required for spawn lifecycle).
(deftest auth-login-happy-path-l3
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:auth/init] {:frame f})   ;; seed via a setup dispatch
    (rf/dispatch-sync [:auth.login/flow [:submit {:email "..."}]] {:frame f})
    ;; Read via the framework-registered :rf/machine sub:
    (is (= :submitting (:state @(rf/subscribe [:rf/machine :auth.login/flow] {:frame f}))))))
```

**Template — view consuming `[:rf/machine <id>]`:**

The framework-registered `:rf/machine` sub returns the snapshot for any machine; the canonical user-facing form is the ordinary `[:rf/machine <id>]` subscription vector:

```clojure
(rf/reg-view login-form []
  (let [{:keys [state data]} @(rf/subscribe [:rf/machine :auth.login/flow])]
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
       nil          [:p "Loading..."])]))             ;; nil before initialisation
```

For projections, compose against `:rf/machine` via `:<-`:

```clojure
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [snap _] (:state snap)))
```

**AI-first checklist:**

- Machine id is namespaced; registered via `reg-event` + `make-machine-handler`.
- No `:path` key in the machine spec — the runtime stores snapshots at `[:rf.runtime/machines :snapshots <id>]` in runtime-db.
- All states are listed in `:states`; no string-based or computed state names.
- Every input the machine listens to is in some state's `:on` map.
- **Non-trivial guards and actions are named in the machine's `:guards` / `:actions` maps and referenced by keyword from the transition table, not inline.** Inline fns are reserved for single non-branching expressions per [005 §Inspectability bias](005-StateMachines.md#inspectability-bias).
- Every keyword reference under `:guard` / `:action` (in `:on`, `:always`, `:entry`, `:exit`) is a key in the spec's `:guards` / `:actions` map — `make-machine-handler` validates this at registration time and raises `:rf.error/machine-unresolved-{guard|action}` on miss.
- No `reg-machine-guard` / `reg-machine-action` calls — those APIs are removed; guards and actions are machine-scoped.
- `:guard` and `:action` are single fns (or single keyword references) — not vectors.
- No `[:assign ...]`, `[:raise ...]`, `[:fx ...]` data forms in transition slots — actions return `{:data {...} :fx [...]}` directly.
- No compound-guard `{:and ...}` / `{:or ...}` / `{:not ...}` data forms — composition is fns or named compounds in `:guards`.
- No `:db` in action effect maps — cross-cutting writes go via `:fx [[:dispatch <named-event>]]`.
- Cross-cutting reads come through the event payload, not from `app-db`.
- Cross-machine reuse of a guard/action is via a Clojure var referenced from each machine's `:guards` / `:actions` map — not via a global registry.
- Views read state via `@(rf/subscribe [:rf/machine <machine-id>])`; no manual `reg-sub` over `[:rf.runtime/machines :snapshots ...]`.
- Transition table conforms to `:rf/transition-table` schema (per [Spec-Schemas](Spec-Schemas.md)).
- Level-1 headless test passes via `machine-transition` (no event dispatch needed).
- If the machine has terminal states, they're marked `:meta {:terminal? true}`.
- Trace events on `:rf.machine/transition` are visible in 10x / re-frame-pair.
- `(rf/machines)` includes the new id; `(rf/machine-meta <id>)` returns its registration metadata (which includes the spec's `:guards` / `:actions` maps).

### CP-6. Scaffold a feature

**When to use this prompt:** the user describes a thing — "add a login form," "add a cart," "let users tag items." Most "build me X" requests land here. A *feature* is a registry slice — events + subs + views + schemas + optional machine — addressable by a shared id-prefix.

**Pre-flight checks (mandatory):**

1. **Choose the feature id-prefix.** A short, namespaced keyword: `:auth`, `:cart`, `:tagging`. Sub-areas use dotted children: `:cart.item`, `:cart.checkout`. Every registration the feature ships uses this prefix.
2. **Verify the prefix is unused.** Query each kind:
   - `(rf/registrations :event)` — none should start with your prefix.
   - `(rf/registrations :sub)` — likewise.
   - `(rf/registrations :view)` — likewise.
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
    events.cljs        ;; reg-event calls
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

(rf/reg-event :cart/initialise
  {:doc "Seed the cart slice."}
  (fn [{:keys [db]} _]
    {:db (assoc db :cart {:items [] :loading? false :checkout-state :idle})}))

(rf/reg-event :cart.item/add
  {:doc    "Add an item to the cart."
   :schema [:cat [:= :cart.item/add] cs/CartItem]}
  (fn [{:keys [db]} [_ item]]
    {:db (update-in db [:cart :items] conj item)}))

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
(rf/reg-view summary [] …)                   ;; via CP-4 (registers :my-app.cart.views/summary)
(rf/reg-view item-list [] …)

;; on app boot
(rf/reg-app-schema [:cart] cs/CartState)
(rf/dispatch [:cart/initialise])
```

**Smoke test — feature happy path:**

```clojure
(deftest cart-feature-happy-path
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:cart/initialise] {:frame f})   ;; seed via a setup dispatch
    (let [item {:id (random-uuid) :sku "ABC-1" :qty 2 :price 9.99}]
      (rf/dispatch-sync [:cart.item/add item] {:frame f})
      (is (= [item] (rf/compute-sub [:cart/items] (rf/app-db-value f))))
      (is (== 19.98 (rf/compute-sub [:cart/total] (rf/app-db-value f)))))))
```

**AI-first checklist for a feature:**

- All registrations use the chosen prefix; nothing else uses it.
- `app-db` slice has a registered schema; init event produces a schema-valid value.
- Every event has `:doc`; structurally-shaped events have `:schema`.
- Every sub has `:doc` and reads from the feature's slice (`[:feature ...]`).
- No view dispatches an unregistered event or reads an unregistered sub.
- Happy-path smoke test runs headlessly (JVM-runnable).
- Feature ships its **public surface** explicitly (in `public.cljs` or via doc) — which events the rest of the app may dispatch, which subs it may read.
- Feature does NOT reach into another feature's `app-db` slice directly; it goes through the other feature's subs and dispatches the other feature's events.

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
5. **Verify the route ids are unused.** `(rf/registrations :route)` enumerates registered routes.

Routing is *state plus events*. The URL is a derivable view of `app-db`; navigation is an event. The runtime ships `:rf.route/navigate`, `:rf.route/handle-url-change`, `:rf.route/transitioned`, `:rf/url-requested` as standard events; user code typically only calls `:rf.route/navigate`.

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

**No need to register `:rf.route/navigate` or `:rf.route/handle-url-change` yourself** — the runtime ships them. Re-register only to override behaviour (e.g. add a guard interceptor; see [012 §Redirects and guards](012-Routing.md#redirects-and-guards)).

**Template — `:on-match` data-loading event:**

```clojure
(rf/reg-event :cart/load-items
  {:doc "Load cart items for the active cart route."}
  (fn handler-cart-load-items [{:keys [db]} _]
    (let [user-id (get-in db [:auth :user :id])]
      {:fx [[:http {:method     :get
                    :url        (str "/api/users/" user-id "/cart")
                    :on-success [:cart/items-loaded]
                    :on-error   [:cart/load-failed]}]]})))
```

The handler reads the route slice — which lives in **runtime-db** at `[:rf.runtime/routing :current]`, NOT app-db — for any path/query params it needs, via `(get-in (rf/runtime-db-value) [:rf.runtime/routing :current])` (the consumer-facing sub-id is `:rf/route`). The slice is already populated when `:on-match` events fire.

**Template — route-aware root view:**

```clojure
(rf/reg-view root-view []
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
       [not-found-page])]))
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
    #(rf/dispatch [:rf.route/handle-url-change (.. js/window -location -href)] {:frame frame-id}))
  (rf/dispatch [:rf.route/handle-url-change (.. js/window -location -href)] {:frame frame-id}))
```

Routing has two co-equal URL-change events. Popstate and the initial sync (above) dispatch `:rf.route/handle-url-change` (default scroll `:restore`); forward navigation — a `route-link` click or programmatic push — dispatches `:rf.route/transitioned` (default scroll `:top`). Both run the identical slice-rewrite; neither delegates to the other.

**Pattern-level discipline:**

- The route is framework-owned state in **runtime-db** (`[:rf.runtime/routing :current]`, read via the `:rf/route` sub); the URL is derivable. Never make routing state live in a parallel router outside the frame.
- Navigation is an event. Don't call browser APIs directly from view code; dispatch `:rf.route/navigate` (or use `route-link`).
- Per-route data loading is **declarative** — list events in `:on-match` on `reg-route`. The runtime dispatches them.
- Server-side renders set the route via `:rf.route/handle-url-change` against the request URL; the same `:on-match` events run server-side.
- Path params and query params are **separate maps** in the runtime-db route slice — `(get-in (rf/runtime-db-value) [:rf.runtime/routing :current :params])` and `(get-in (rf/runtime-db-value) [:rf.runtime/routing :current :query])`.

**AI-first checklist:**

- Route ids are namespaced (`:route/...`).
- Each route's `:path` conforms to the canonical path-pattern grammar.
- Path params are declared in `:params` (schema); query params are declared in `:query` (schema).
- Per-route data dependencies are declared in `:on-match` (vector of event vectors).
- Per-route error handling is declared in `:on-error` (single event vector) where needed.
- All navigation goes through `:rf.route/navigate` (or `route-link`); no inline `pushState`.
- The root view dispatches on `:rf.route/id`; per-page views are registered separately.
- A `:rf.route/not-found` route is registered.
- Nested layouts use `:parent` (or id-prefix-only if no shared loader/chrome is needed); read the chain via `:rf.route/chain`.

### CP-8. Scaffold a schema

**When to use this prompt:** the user wants to describe the shape of data — an event's args, a sub's return value, a slice of `app-db`, a registered fx's args, a request/response payload from an external service.

**Pre-flight checks:**

1. **Identify what shape you're describing.** Event vector? Sub return? `app-db` slice? Fx args?
2. **Decide open vs closed.** Default: **open** (consumers tolerate unknown keys). Use `:closed true` only at *system boundaries* — incoming HTTP request payloads, outgoing API requests, EDN/JSON crossing process boundaries.
3. **Check existing schemas at the same path.** `(app-schema-at [:feature])` — don't shadow.

**Templates by site:**

```clojure
;; Event-vector schema (attached via :schema on reg-event-*)
(def CartItemRemoveEvent
  [:tuple [:= :cart.item/remove] :uuid])      ;; [event-id item-id]

;; Registered to the event:
(rf/reg-event :cart.item/remove
  {:schema CartItemRemoveEvent}
  (fn [{:keys [db]} [_ id]] {:db ...}))

;; Sub-return schema (attached via :schema on reg-sub)
(def CartTotal :double)

(rf/reg-sub :cart/total
  {:schema CartTotal}
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
  {:schema [:map [:method :keyword] [:url :string] [:body {:optional true} :any]]}
  ...)

;; Closed schema for an external boundary
(def IncomingWebhookPayload
  [:map {:closed true}
   [:event_type [:enum "user.created" "user.updated" "user.deleted"]]
   [:user_id    :int]
   [:timestamp  :int]])

(rf/reg-event :webhook/handle
  {:schema [:cat [:= :webhook/handle] IncomingWebhookPayload]
   :interceptors [:rf.schema/at-boundary]}                   ;; ref by id (EP-0022) — rejects payload at boundary if invalid
  ...)
```

**Pattern-level discipline (per [010](010-Schemas.md) and [000-Vision.md](000-Vision.md)):**

- **Open by default.** Don't add `:closed true` unless the data crosses a process boundary.
- **Don't model object hierarchies.** A schema describes the *shape* of an open map. There are no classes.
- **Schemas grow additively.** Once a schema ships, you can add new optional keys; you cannot remove or rename existing keys without bumping a version (Spec-ulation).
- **Validation runs in dev, elides in prod** by default. Reference the `:rf.schema/at-boundary` interceptor by id (`{:interceptors [:rf.schema/at-boundary]}`, EP-0022 ref form — the `rf/validate-at-boundary-interceptor` Var is the registration-boundary input, not a chain entry) for runtime validation in prod at system boundaries.

**AI-first checklist:**

- Schema describes shape, not types of objects.
- Open by default (no `:closed true` unless at a boundary).
- Path-based for `app-db` slices.
- Attached as `:schema` on the relevant `reg-*` if it describes a registration's input/output.
- Schema is *named* — `(def CartState ...)` — not inline.
- Conforms to [010](010-Schemas.md) registration shape.

### CP-9. Scaffold an SSR setup

**When to use this prompt:** the user wants the app to render server-side — for SEO, fast first paint, social-media link previews, or simply "no client JS until interaction."

**Pre-flight checks:**

1. **Identify the per-request setup events.** What does the server need to dispatch before rendering? Typically: `:auth/load-session`, `:rf.route/handle-url-change`, feature-specific `:feature/load-initial-data`.
2. **Identify the fx that need server platforms.** HTTP for sure. Anything else? Confirm with `(rf/registrations :fx)` and audit each fx's `:platforms` metadata.
3. **Identify the views that may render under SSR.** All of them, in principle. Confirm none use Form-2 outer-fn-side-effects (they don't run on the server).
4. **Confirm the root view is registered** via `reg-view`, not a plain Reagent fn.

**Server-side template:**

```clojure
(defn handle-request [request]
  (let [frame-id (gensym :ssr-frame)]
    (rf/with-new-frame [f (rf/make-frame
                       {:id     frame-id
                        :images [app-image]})]
      ;; rebound to f. EP-0023 object constructor takes :images; run the
      ;; per-request setup via a dispatch (not the record-only :on-create key).
      (rf/dispatch-sync [:rf/server-init request] {:frame f})
      (let [final-db (rf/app-db-value f)
            hiccup   ((rf/view :app/root))                ;; the registered root view
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
(rf/reg-event :rf/server-init
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
                [(rf/view :app/root)])))

(boot!)
```

**Pattern-level discipline (per [011](011-SSR.md)):**

- All server-side fx have `:platforms` containing `:server`. Anything else is skipped (with a `:rf.fx/skipped-on-platform` trace event).
- The root view is registered (`reg-view`), pure, returns a serialisable render-tree.
- The render-tree → string emitter is JVM-runnable; no React on the server.
- The hydration payload is open-map-shaped and conforms to `:rf/hydration-payload`.
- Mismatch detection runs on first client render; the runtime emits `:rf.ssr/hydration-mismatch` traces on divergence.

**AI-first checklist:**

- Per-request frame is created and destroyed within `with-frame`.
- All setup events have `:platforms` set or are universal (no `:platforms` key, runs everywhere).
- Render-tree → string is pure; no React, no DOM, no JS APIs on the server.
- Hydration payload includes `:rf/version`, `:rf/frame-id`, `:rf/app-db`, optional `:rf/render-hash`.
- Client `[:rf/hydrate ...]` event seeds before first render.
- First-client-render hash matches server hash (test in dev with mismatch detection on).
- Page template injects the payload as a `<script>` element with `id="__rf_payload"`.
- All client-only effects (DOM mutation, localStorage) are tagged `:platforms #{:client}`.

### CP-11. Register an interceptor

**When to use this prompt:** the user wants a piece of **full-context program behaviour** that runs around event handlers — an auth gate, an audit log, trace-payload redaction, a coeffect injection, an effect rewrite, a focus on an `app-db` sub-slice. Since [EP-0018](../docs/EP/EP-0018-one-event-registration.md) collapsed event registration to one `reg-event` form and moved `context -> context` work to interceptors, an interceptor is **load-bearing program structure** — and per [EP-0022](../docs/EP/EP-0022-registered-interceptors.md) it is a first-class **registered** member, authored once with `reg-interceptor` and referenced from event/frame `:interceptors` chains by id. Reach for this whenever a behaviour must wrap *more than one* handler, or must be named, addressable, override-able, and visible in tooling. A one-off transform that belongs to a single handler stays inside that handler's body — interceptors are for cross-cutting, reusable behaviour.

**Pre-flight delta (in addition to the shared preamble above):**

- **Id-shape convention:** a single namespaced keyword. Examples: `:auth/required`, `:audit/record-event`, `:app/unwrap`. The relevant registry kind is `:interceptor` — verify the id is unused via `(rf/registrations :interceptor)`. Application ids are application-owned; the `:rf.interceptor/*` namespace is reserved for framework standard refs (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).
- **Decide static vs parameterized.** A **static** interceptor has fixed behaviour (`{:before}` / `{:after}` / `{:before :after}`). A **parameterized** interceptor (`{:factory}`) is a *family* — the factory receives one arg and builds a static interceptor for it; it is referenced as `[id arg]`. Reach for `:factory` only when the same behaviour needs per-reference configuration (the framework's standard `:rf.interceptor/path` is the canonical example).
- **Reference, never inline.** Event and frame `:interceptors` chains carry interceptor **references** — a bare keyword or an `[id arg]` 2-vector — **never inline interceptor values** ([002 §Event and frame chain grammar](002-Frames.md#event-and-frame-chain-grammar)). An inline interceptor map / Var / `->interceptor` result in a public chain is a registration error (`:rf.error/inline-interceptor-removed`). `->interceptor` is **not** the public authoring form — `reg-interceptor` is.

**The descriptor shapes:**

`reg-interceptor` takes `(id ?metadata descriptor)`. The `descriptor` is exactly one of:

```clojure
{:before before-fn}            ;; static: runs before the handler
{:after  after-fn}             ;; static: runs after the handler (reverse chain order)
{:before before-fn :after after-fn}
{:factory factory-fn}          ;; parameterized family; factory-fn takes ONE arg
```

A descriptor mixing `:factory` with `:before` / `:after` is ambiguous and rejected; any other malformed descriptor is `:rf.error/invalid-interceptor` at registration. Each `:before` / `:after` fn is `(context) -> context`: read inputs from the context's coeffects (`get-coeffect`), write outputs to its effects (`assoc-effect`), or rewrite coeffects with `assoc-coeffect` / `update-coeffect` (per [002 §Interceptor chain execution](002-Frames.md#interceptor-chain-execution--before-short-circuit-after-always-runs)).

**Template — static `{:before}` (read/rewrite coeffects):**

```clojure
(rf/reg-interceptor :audit/record-event
  {:doc "Append each handled event's id to the app-db audit trail."}
  {:before
   (fn before-audit-record-event [ctx]
     ;; Read the dispatched event from coeffects; rewrite the :db coeffect.
     (let [[event-id] (rf/get-coeffect ctx :event)]
       (rf/update-coeffect ctx :db update :audit/events (fnil conj []) event-id)))})
```

**Template — static `{:after}` (inspect/rewrite effects):**

```clojure
(rf/reg-interceptor :metrics/count-writes
  {:doc "Increment a write counter whenever a handler returned a :db effect."}
  {:after
   (fn after-metrics-count-writes [ctx]
     (if (rf/get-effect ctx :db)
       (rf/assoc-effect ctx :db (vary-meta (rf/get-effect ctx :db) update :write-count (fnil inc 0)))
       ctx))})
```

**Template — parameterized `{:factory}` (an `[id arg]` family):**

```clojure
;; A factory that gates a handler on a role read from the event payload.
;; Referenced as [:app/role {:role :admin :redirect [:login/show]}].
(rf/reg-interceptor :app/role
  {:doc "Require the dispatched event carry the named role; else dispatch :redirect."}
  {:factory
   (fn factory-app-role [{:keys [role redirect]}]      ;; ONE arg — a composite map
     {:before
      (fn before-app-role [ctx]
        (let [[_ payload] (rf/get-coeffect ctx :event)]
          (if (= role (:role payload))
            ctx
            (rf/assoc-effect ctx :fx [[:dispatch redirect]]))))})})
```

The factory takes **exactly one** argument. A behaviour needing several inputs takes them as a single composite arg (a vector or map), as above. A bare-keyword reference to a `:factory` id — or an `[id arg]` reference to a static id — is `:rf.error/interceptor-factory-arity`.

**Referencing the interceptor in a chain:**

```clojure
;; Bare keyword references a static interceptor; [id arg] references a factory.
(rf/reg-event :cart/add
  {:interceptors [:auth/required
                  :audit/record-event
                  [:rf.interceptor/path [:cart]]]}        ;; standard path interceptor (a factory)
  (fn handler-cart-add [{:keys [db]} [_ sku]]
    {:db (update db :items conj sku)}))

;; Frame-level chains prepend to every event handled in the frame:
(rf/reg-frame :story/cart
  {:interceptors [:story/record-events]})
```

The standard **`:rf.interceptor/path`** is the one framework-shipped interceptor (a `:factory`): `[:rf.interceptor/path [:cart :items]]` focuses a handler on an `app-db` sub-slice and re-widens the result — you do not register it ([002 §Standard `:rf.interceptor/path`](002-Frames.md#standard-rfinterceptorpath)). There is no standard `unwrap` / `trim-v`; ordinary handler destructuring covers those (and keeps the `:event` coeffect stable for tracing/replay).

**Per-frame / per-call override (reference-based):**

```clojure
;; In a story/test, swap or remove an interceptor by its exact reference:
(rf/dispatch [:cart/add "ABC-1"]
  {:frame f
   :interceptor-overrides {:auth/required :story/skip-auth   ;; replace with another ref
                           :audit/record-event nil}})         ;; nil removes it
```

Override keys are interceptor **references**, matched by exact canonical reference (a bare keyword matches that keyword or an entry's id; an `[id arg]` vector matches only that exact reference). Values are another reference (replace) or `nil` (remove) — **no inline interceptor values** ([002 §`:interceptor-overrides`](002-Frames.md#interceptor-overrides--exact-reference-substitution)).

**Pattern-level discipline (per [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar) and [002 §Registered interceptors and the chain grammar](002-Frames.md#registered-interceptors-and-the-chain-grammar)):**

- **Reference-only chains.** Authored behaviour lives in exactly two homes — event metadata and frame metadata — and both carry refs. Dispatch opts do **not** accept an additive `:interceptors` key; per-dispatch variation is `:interceptor-overrides`.
- **`:before` short-circuits, `:after` always runs.** A `:before` may queue the rest of the chain off (skipping later `:before`s and the handler); every entered interceptor's `:after` still runs, in reverse order. The execution model is unchanged from v1.
- **The reference resolves at dispatch time** — re-registering an interceptor id with a new descriptor takes effect on the next dispatch of any event whose chain references it; the event does not have to be re-registered (hot reload).
- **Migration boundary only:** `reg-interceptor` also accepts an existing interceptor **value** carrying implementation-private slots (an `:id`, if present, must match the registration id). This is confined to the `reg-interceptor` call site; public chains still carry refs.

**Smoke test (headless via a test frame):**

```clojure
(deftest audit-record-event-test
  (rf/reg-interceptor :audit/record-event
    {:doc "Append each handled event's id to the audit trail."}
    {:before
     (fn [ctx]
       (let [[event-id] (rf/get-coeffect ctx :event)]
         (rf/update-coeffect ctx :db update :audit/events (fnil conj []) event-id)))})
  (rf/reg-event :feature/touch
    {:interceptors [:audit/record-event]}
    (fn [{:keys [db]} _] {:db db}))
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:feature/touch] {:frame f})
    (is (= [:feature/touch] (:audit/events (rf/app-db-value f))))))
```

**AI-first checklist:**

- Interceptor id is a single namespaced keyword and unused (`(rf/registrations :interceptor)`).
- The descriptor is exactly one of `{:before}` / `{:after}` / `{:before :after}` / `{:factory}` — no `:factory`+`:before`/`:after` mix.
- A `:factory` factory takes exactly **one** argument (a composite vector/map when it needs several inputs).
- `:before` / `:after` fns are `(context) -> context`, pure with respect to the context map; side-effects are expressed as effects (`assoc-effect` / `:fx`), never performed in the body.
- `:doc` is present and one sentence.
- The interceptor is **referenced** from chains by id — bare keyword (static) or `[id arg]` (factory) — never inlined as a value.
- Overrides (in stories/tests) are reference-valued (another ref or `nil`), never inline values.
- `:before` / `:after` fns have meaningful names (not `fn`); the name shows up in stack traces and tooling.
- Smoke test asserts the interceptor's effect on a real dispatch through a test frame.

**Example — auth gate referenced across several events:**

```clojure
(ns my-app.auth.interceptors
  (:require [re-frame.core :as rf]))

(rf/reg-interceptor :auth/required
  {:doc "Redirect to login when no user is authenticated; let the handler run otherwise."}
  {:before
   (fn before-auth-required [ctx]
     (if (get-in (rf/get-coeffect ctx :db) [:auth :user])
       ctx
       ;; Unauthenticated: stage a redirect. (Throwing from :before short-circuits
       ;; the remaining :before stages and the handler — see 002 §Interceptor chain
       ;; execution; a redirect-and-continue gate stages an :fx instead, as here.)
       (rf/assoc-effect ctx :fx [[:dispatch [:rf.route/navigate :route/login]]])))})

;; Any event that needs the gate references it by id:
(rf/reg-event :account/update-profile
  {:doc          "Update the signed-in user's profile."
   :interceptors [:auth/required]}
  (fn handler-account-update-profile [{:keys [db]} [_ patch]]
    {:db (update db :profile merge patch)}))
```

## Cross-references

- [Principles.md §Construction prompts as a deliverable](Principles.md#construction-prompts-as-a-deliverable) — why this artefact exists.
- [000-Vision.md](000-Vision.md) — the goals and contract.
- [MIGRATION.md](../migration/from-re-frame-v1/README.md) — the sibling artefact for upgrades.
- [API.md](API.md) — signatures the prompts produce calls against.

## Worked examples (each prompt, in action)

The [7GUIs example series](../examples/reagent/seven_guis/README.md) and the [login example](../examples/reagent/login/core.cljs) demonstrate every prompt in working code (the `examples/` tree is test-free per [`examples/README.md`](../examples/README.md); real-regression coverage lives in the substrate contract tests, the framework gates, and adapter-level smoke tests):

| Prompt | Example |
|---|---|
| CP-1 (event handler) | All examples; especially the bookkeeping events in [Flight Booker](../examples/reagent/seven_guis/flight_booker/core.cljs) and the undo events in [Circle Drawer](../examples/reagent/seven_guis/circle_drawer/core.cljs) |
| CP-2 (subscription) | [Temperature Converter](../examples/reagent/seven_guis/temperature/core.cljs) shows `:<-` chains; [Flight Booker](../examples/reagent/seven_guis/flight_booker/core.cljs) shows multi-input chains for derived enabled-state |
| CP-3 (registered fx) | [Login](../examples/reagent/login/core.cljs) shows `:platforms` metadata + a stub fx for tests; [Timer](../examples/reagent/seven_guis/timer/core.cljs) shows `:dispatch-later`; [Flight Booker](../examples/reagent/seven_guis/flight_booker/core.cljs) shows a custom `:notify` fx |
| CP-4 (registered view) | All examples use Var-reference Form-1 (canonical) |
| CP-5 (state machine) | [Login](../examples/reagent/login/core.cljs) — full transition table with guards, actions, terminal states |
| CP-6 (feature scaffold) | [Login](../examples/reagent/login/core.cljs) is a full feature: schema + events + subs + views + machine + tests |
| CP-7 (route) | [Routing example](../examples/reagent/routing/core.cljs) — three-page app (home / articles / article-detail / 404), `:rf.route/navigate`, `:rf.route/handle-url-change`, `route-link`, server-and-client-shared handler |
| CP-8 (schema) | All examples register `app-db` slice schemas; [Login](../examples/reagent/login/core.cljs) and [Flight Booker](../examples/reagent/seven_guis/flight_booker/core.cljs) also attach event schemas |
| CP-9 (SSR setup) | [SSR example](../examples/reagent/ssr/core.cljc) — single `.cljc` file demonstrating both server (`handle-request` returning HTML+payload) and client (`:rf/hydrate` seeding) flows; JVM-runnable smoke test |
| CP-11 (interceptor) | [Circle Drawer](../examples/reagent/seven_guis/circle_drawer/core.cljs) registers the `:undoable` static interceptor (`{:before :after}`) and references it by id from undoable events; [RealWorld](../examples/reagent/realworld/routing.cljs) registers a `:realworld.routing/auth-guard` and references it from the frame's `:interceptors` |
