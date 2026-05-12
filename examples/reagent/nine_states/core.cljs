(ns nine-states.core
  "Worked example: the **Nine States of UI**, modelled as a single
   parallel state machine.

   Most apps only design the happy path and leave the eight other states
   visually undefined. This example demonstrates all nine canonical UI
   states for a single small domain — a **todos list** — using one
   `:type :parallel` machine with three regions, plus `:fsm/tags` to
   carry the per-axis intent.

   The nine states (per the well-known UX taxonomy):

     1. Nothing      — never fetched; the data region is at `:nothing`.
     2. Loading      — first fetch in flight; the data region is at `:loading`.
     3. Empty        — fetched, but the result is the empty list.
     4. One          — exactly one todo. Focused single-item layout.
     5. Some         — a small, manageable list.
     6. Too Many     — overwhelming amount; needs filtering / pagination.
     7. Incorrect    — invalid form input; per-field validation error.
     8. Correct      — a happy-path success after a valid submit.
     9. Done/Frozen  — terminal state; the mode region is at `:done`.

   The machine declaration carries the **whole** model:

     - `:data` region    — six cardinality states (Nothing / Loading /
                            Empty / One / Some / Too Many) plus an `:error`
                            branch. An `:always`-cascade picks the
                            cardinality bucket after a successful fetch.
     - `:form` region    — three states (Neutral / Incorrect / Correct)
                            mirroring Pattern-Forms' lifecycle.
     - `:mode` region    — two states (Active / Done); `:done` is
                            terminal and read-only.

   Every state carries `:tags` describing its per-axis intent
   (`:data/loading`, `:form/invalid`, `:mode/done`, ...). One render-priority
   table in data + one selector sub (`:ui/render`) collapse the tag
   union into a single render-model keyword. The root view's `case`
   over `:ui/render` replaces what the legacy variant did with nine
   boolean discriminator subs + a priority `cond`.

   What this example demonstrates:

   - **Parallel regions + tags** (`spec/Pattern-NineStates.md`,
     `spec/005-StateMachines.md` §Parallel regions / §State tags) —
     three orthogonal axes in one machine, with tag-shaped queries
     against the active configuration.
   - **Pattern-RemoteData**-shaped lifecycle (`spec/Pattern-RemoteData.md`)
     — folded into the `:data` region; the region's state-keyword IS
     the status, so the slice's separate `:status` field disappears.
   - **Pattern-Forms** (`spec/Pattern-Forms.md`) — the
     `{:draft :submitted :errors :touched}` slice carries the form
     runtime; the form region's state tracks the validation/submission
     lifecycle.
   - **Inspectability bias** — non-trivial guards / actions are named
     entries in the machine's `:guards` / `:actions` maps; only trivial
     transitions use inline fns.
   - **Headless tests** — every state has a fixture that drives `app-db`
     into that state and asserts against tags + `:ui/render`. Browserless
     via `compute-sub` / `dispatch-sync`. The fixtures live in a sibling
     `test/nine_states/core_test.cljs` (ns `nine-states.core-test`) so
     this source file stays test-free.

   Layout follows the single-file style of `examples/reagent/login/core.cljs`
   and `examples/reagent/7Guis/circle_drawer/circle_drawer.cljs`. In a real
   codebase this would split per CP-6 conventions across schema / events /
   subs / views / machines / tests files."
  (:require [reagent2.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; The Spec 010 schema-attachment ns lives in
            ;; the day8/re-frame2-schemas artefact. Loading the ns
            ;; here registers its late-bind hooks so the
            ;; rf/reg-app-schema calls below resolve.
            [re-frame.schemas]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine
            ;; (called below at ns-load) and the `:rf/machine` /
            ;; `:rf/machine-has-tag?` framework subs resolve.
            [re-frame.machines]
            ;; Managed-HTTP ships in day8/re-frame2-http.
            ;; Requiring re-frame.http-managed at app boot triggers its
            ;; load-time fx registrations (`:rf.http/managed` and
            ;; family); without it, dispatching `:rf.http/managed`
            ;; (used below in the load-todos fx) would fail with
            ;; :rf.error/no-such-fx.
            [re-frame.http-managed]
            [re-frame.views]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ============================================================================
;; CONSTANTS
;; ============================================================================

(def too-many-threshold
  "A 'small list' is up to TOO-MANY-THRESHOLD items; beyond that we render
   the 'Too Many' UI (search + truncation). Lifted to a named const so
   the machine's :too-many? guard and the test fixtures reference the
   same value."
  7)

(def new-todo-defaults
  {:draft    {:title ""}
   :errors   {}
   :touched  #{}})

;; ============================================================================
;; SCHEMAS  (Spec 010)
;; ============================================================================
;;
;; The :ui/nine-states machine's `:data` slot is its own self-documenting
;; record (see the machine declaration). The form slice describes only
;; what the form *collects* + per-field validation state — no `:status`
;; field, because the form region's state-keyword IS the status.

(def Todo
  [:map
   [:id     :uuid]
   [:title  [:string {:min 1}]]
   [:done?  {:default false} :boolean]])

(def NewTodoForm
  [:map
   [:title [:string {:min 3 :max 80}]]])

(def NewTodoSlice
  [:map
   [:draft   :map]
   [:errors  {:default {}} [:map-of :keyword [:vector :string]]]
   [:touched {:default #{}} [:set :keyword]]])

(rf/reg-app-schema [:new-todo] NewTodoSlice)

;; ============================================================================
;; FX  (test-friendly stubs)
;; ============================================================================
;;
;; Real apps would issue a single `:rf.http/managed` request (Spec 014) and
;; override at test time via the id-valued seam (per Spec 002). For this
;; self-contained example we ship one per-app stub that delegates to the
;; framework-shipped canned-success / canned-failure fxs (Spec 014 §Testing)
;; so the control panel can drive the data region into Empty / One / Some /
;; Too Many without a server.

(defn- gen-todos [n]
  (vec (for [i (range n)]
         {:id (random-uuid) :title (str "Todo #" (inc i)) :done? false})))

(rf/reg-fx :nine-states.http/managed-demo
  {:doc       "Demo override for `:rf.http/managed`. Routes by URL:
               `/api/todos` → success with N synthetic todos (N from
               `:request :query :n`); `/api/todos/fail` → transport
               failure. Delegates to the framework-shipped
               `:rf.http/managed-canned-success` /
               `:rf.http/managed-canned-failure` per Spec 014 §Testing
               so the canonical reply shape is preserved."
   :platforms #{:client :server}}
  (fn fx-managed-demo [frame-ctx args-map]
    (let [url (-> args-map :request :url str)]
      (cond
        (= url "/api/todos/fail")
        (let [stub (registrar/handler :fx :rf.http/managed-canned-failure)]
          (stub frame-ctx (assoc args-map
                                 :kind :rf.http/transport
                                 :tags {:message "Network unreachable."})))

        :else
        (let [n    (or (-> args-map :request :query :n) 0)
              stub (registrar/handler :fx :rf.http/managed-canned-success)]
          (stub frame-ctx (assoc args-map :value (gen-todos n))))))))

;; ============================================================================
;; THE MACHINE — :ui/nine-states  (one machine, three regions)
;; ============================================================================
;;
;; Three orthogonal axes, one declaration:
;;
;;   :data — the request-lifecycle / cardinality axis (states 1-6, plus
;;           an :error branch). After a successful fetch lands in
;;           :resolving, an :always-cascade picks the cardinality bucket
;;           by reading :items out of the shared :data.
;;
;;   :form — Pattern-Forms' Neutral / Incorrect / Correct lifecycle
;;           (states 7 & 8). Driven by :submit-valid / :submit-invalid /
;;           :edit events; transitions are pure (the slice's :errors /
;;           :touched live in app-db, not in the machine's :data).
;;
;;   :mode — the Active / Done axis (state 9). :done is terminal and
;;           tagged :mode/read-only — the view inspects that tag to
;;           disable the form and the control buttons.
;;
;; Per Spec 005 §Parallel regions the snapshot's :state is a map keyed
;; by region name; :data is shared across all regions; :tags is the
;; union of every active state's tag set.

(def nine-states-machine
  {:type :parallel

   ;; Shared :data: the data region's :items live here, plus the
   ;; mode region's :archived-at stamp. Shared rather than per-region
   ;; because the regions share a domain (per Spec 005 §When to reach
   ;; for parallel regions); see the rewrite design doc §9.4.
   :data {:items       []
          :error       nil
          :archived-at nil}

   :guards
   {:empty?
    (fn guard-empty? [data _event]
      (zero? (count (:items data))))

    :one?
    (fn guard-one? [data _event]
      (= 1 (count (:items data))))

    :too-many?
    (fn guard-too-many? [data _event]
      (> (count (:items data)) too-many-threshold))}

   :actions
   {:set-items
    ;; :fetch-succeeded carries the new items as the action's event
    ;; payload's second element: [:fetch-succeeded {:items [...]}].
    (fn action-set-items [data [_ {:keys [items]}]]
      {:data (-> data
                 (assoc :items (vec items))
                 (assoc :error nil))})

    :set-error
    (fn action-set-error [data [_ {:keys [failure]}]]
      {:data (assoc data :error failure)})

    :stamp-archived
    (fn action-stamp-archived [data [_ {:keys [now]}]]
      {:data (assoc data :archived-at (or now 0))})}

   :regions
   {;; ---- :data region — Pattern-RemoteData lifecycle + cardinality ----
    :data
    {:initial :nothing
     :states
     {:nothing
      ;; State 1 — never fetched. The :nothing tag (+ the absent
      ;; :mode/done) drives the welcome view.
      {:tags #{:data/nothing}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :loading
      ;; State 2 — first fetch in flight. The :data/loading tag drives
      ;; the spinner view.
      {:tags #{:data/loading :data/transient}
       :on   {:fetch-succeeded {:target :resolving
                                :action :set-items}
              :fetch-failed    {:target :error
                                :action :set-error}}}

      :resolving
      ;; Eventless microstep: after :set-items writes :items into
      ;; :data, the cardinality guards pick a bucket. First match wins.
      {:always [{:guard :empty?    :target :empty}
                {:guard :one?      :target :one}
                {:guard :too-many? :target :too-many}
                {:target :some}]}

      :empty
      {:tags #{:data/empty}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :one
      {:tags #{:data/one}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :some
      {:tags #{:data/some}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :too-many
      {:tags #{:data/too-many}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :error
      {:tags #{:data/error}
       :on   {:fetch-started :loading
              :reset         :nothing}}}}

    ;; ---- :form region — Pattern-Forms lifecycle ----
    :form
    {:initial :neutral
     :states
     {:neutral
      {:tags #{:form/neutral}
       :on   {:submit-valid   :correct
              :submit-invalid :incorrect
              :edit           :neutral
              :reset          :neutral}}

      :incorrect
      ;; State 7 — invalid input visible on a touched field. The
      ;; :form/invalid tag drives the inline error view.
      {:tags #{:form/invalid}
       :on   {:submit-valid :correct
              :edit         :neutral
              :reset        :neutral}}

      :correct
      ;; State 8 — happy-path acknowledgement. Transient; the next
      ;; :edit returns the region to :neutral.
      {:tags #{:form/success :form/transient}
       :on   {:edit  :neutral
              :reset :neutral}}}}

    ;; ---- :mode region — Active / Done ----
    :mode
    {:initial :active
     :states
     {:active
      {:tags #{:mode/active}
       :on   {:archive {:target :done
                        :action :stamp-archived}
              :reset   :active}}

      :done
      ;; State 9 — terminal. :mode/read-only is the tag the form and
      ;; control panel inspect to disable inputs.
      {:tags #{:mode/done :mode/read-only :mode/terminal}}}}}})

(rf/reg-machine :ui/nine-states nine-states-machine)

;; ============================================================================
;; EVENTS — top-level demo wrappers
;; ============================================================================
;;
;; The control panel buttons dispatch high-level demo events that
;; coordinate the form slice's app-db state with broadcasts into the
;; :ui/nine-states machine. Splitting them out (rather than dispatching
;; the machine directly) keeps the imperative bits (clear the form,
;; bump app-db) out of the view.

(rf/reg-event-fx :nine-states.app/initialise
  {:doc "Seed the form slice + reset the machine to its initial state."}
  (fn handler-app-initialise [{:keys [db]} _]
    {:db (assoc db :new-todo new-todo-defaults)
     :fx [[:dispatch [:ui/nine-states [:reset]]]]}))

(rf/reg-event-fx :nine-states.demo/load
  {:doc "Drive a synthetic fetch through the demo HTTP stub. The reply
         folds back via :nine-states.demo/loaded → the machine's
         :fetch-succeeded event; the :data region's :always-cascade
         picks the cardinality bucket from the count."}
  (fn handler-demo-load [_ [_ {:keys [n]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           {:request    {:method :get
                         :url    "/api/todos"
                         :query  {:n (or n 0)}}
            :decode     :json
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

(rf/reg-event-fx :nine-states.demo/load-with-failure
  {:doc "Drive a synthetic failing fetch — the :data region lands in
         :error."}
  (fn handler-demo-load-with-failure [_ _]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           {:request    {:method :get :url "/api/todos/fail"}
            :decode     :json
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

(rf/reg-event-fx :nine-states.demo/loaded
  {:doc "Successful fetch. Forwards the items to the machine via
         :fetch-succeeded; the :always-cascade picks the bucket."}
  (fn handler-demo-loaded [_ [_ {:keys [value]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-succeeded {:items value}]]]]}))

(rf/reg-event-fx :nine-states.demo/load-failed
  {:doc "Failed fetch. Forwards the failure category to the machine."}
  (fn handler-demo-load-failed [_ [_ {:keys [failure]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-failed {:failure failure}]]]]}))

;; ---- form-slice events ----

(defn- validate-new-todo
  "Pure validator: returns a {field [errors...]} map. Empty when valid."
  [{:keys [title]}]
  (cond-> {}
    (or (nil? title) (< (count title) 3))
    (assoc :title ["Title must be at least 3 characters."])

    (and title (> (count title) 80))
    (assoc :title ["Title must be at most 80 characters."])))

(rf/reg-event-fx :new-todo/edit-field
  {:doc  "User edited a form field. Updates :draft and marks the field
          touched; broadcasts :edit into the machine so the :form region
          returns from :correct or :incorrect to :neutral."
   :spec [:cat [:= :new-todo/edit-field] :keyword :string]}
  (fn handler-new-todo-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:new-todo :draft field] value)
             (update-in [:new-todo :touched]    conj field))
     :fx [[:dispatch [:ui/nine-states [:edit]]]]}))

(rf/reg-event-fx :new-todo/submit
  {:doc "Validate the draft. If invalid → :submit-invalid (the :form
         region lands in :incorrect). If valid → append to the
         machine's :data items via :fetch-succeeded, clear the draft,
         and broadcast :submit-valid (the :form region lands in
         :correct)."}
  (fn handler-new-todo-submit [{:keys [db]} _]
    (let [draft  (get-in db [:new-todo :draft])
          errors (validate-new-todo draft)
          items  (get-in db [:rf/machines :ui/nine-states :data :items])]
      (if (seq errors)
        ;; Incorrect — populate :errors, touch all error fields so they show.
        {:db (-> db
                 (assoc-in [:new-todo :errors]  errors)
                 (assoc-in [:new-todo :touched] (set (keys errors))))
         :fx [[:dispatch [:ui/nine-states [:submit-invalid]]]]}
        ;; Correct — append the todo to the machine's items, clear the form.
        ;; The :data region's lifecycle is the canonical path for changing
        ;; items: bump through :loading → :resolving so the :always-cascade
        ;; re-picks the cardinality bucket against the new count.
        (let [new-items (conj (vec items)
                              {:id     (random-uuid)
                               :title  (:title draft)
                               :done?  false})]
          {:db (-> db
                   (assoc-in [:new-todo :draft]   {:title ""})
                   (assoc-in [:new-todo :errors]  {})
                   (assoc-in [:new-todo :touched] #{}))
           :fx [[:dispatch [:ui/nine-states [:fetch-started]]]
                [:dispatch [:ui/nine-states [:fetch-succeeded {:items new-items}]]]
                [:dispatch [:ui/nine-states [:submit-valid]]]]})))))

;; ============================================================================
;; SUBSCRIPTIONS — slice readers + the render-model selector
;; ============================================================================

(rf/reg-sub :new-todo
  (fn sub-new-todo [db _] (get db :new-todo)))

(rf/reg-sub :new-todo/draft   :<- [:new-todo] (fn [s _] (:draft s)))
(rf/reg-sub :new-todo/errors  :<- [:new-todo] (fn [s _] (:errors s)))
(rf/reg-sub :new-todo/touched :<- [:new-todo] (fn [s _] (:touched s)))

(rf/reg-sub :new-todo/field-error
  {:doc "Per-field error, only after the user has touched the field."}
  :<- [:new-todo/errors]
  :<- [:new-todo/touched]
  (fn sub-new-todo-field-error [[errs touched] [_ field-id]]
    (when (touched field-id)
      (first (get errs field-id)))))

;; The machine's :data items — read straight off the snapshot.
(rf/reg-sub :todos/items
  :<- [:rf/machine :ui/nine-states]
  (fn sub-todos-items [snap _]
    (get-in snap [:data :items])))

(rf/reg-sub :todos/error
  :<- [:rf/machine :ui/nine-states]
  (fn sub-todos-error [snap _]
    (get-in snap [:data :error])))

;; ---- render-priority + :ui/render selector ----
;;
;; The render-priority table is plain data: a vector of {:tag :render}
;; pairs consulted in order. The :ui/render sub reads the machine's tag
;; union and returns the first :render whose :tag is present. This is
;; the **single** place the page's render priorities live; the root
;; view's `case` just maps the resolved keyword to a view fn.
;;
;; Priority rationale: :mode wins outright (the archived view replaces
;; everything); :form wins next (the success / inline-error
;; acknowledgement is transient and overlays whatever the :data
;; region happens to be at); :data picks the cardinality bucket as a
;; fallback.

(def render-priority
  [;; mode region — read-only / terminal wins
   {:tag :mode/done       :render :done}
   ;; form region — transient acknowledgements (Correct/Incorrect)
   {:tag :form/success    :render :correct}
   {:tag :form/invalid    :render :incorrect}
   ;; data region — error first (also transient), then lifecycle, then cardinality
   {:tag :data/loading    :render :loading}
   {:tag :data/error      :render :error}
   {:tag :data/nothing    :render :nothing}
   {:tag :data/empty      :render :empty}
   {:tag :data/one        :render :one}
   {:tag :data/some       :render :some}
   {:tag :data/too-many   :render :too-many}])

(rf/reg-sub :ui/render
  {:doc "Resolve the page's render-model keyword by consulting the
         render-priority table against the machine's tag union. The
         root view's `case` is the only branch site; everything else
         reads tags directly."}
  :<- [:rf/machine :ui/nine-states]
  (fn sub-ui-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

;; ============================================================================
;; VIEWS — one per render-model keyword
;; ============================================================================

(reg-view ^{:doc "State 1 — Nothing: blank slate with a 'Get started' CTA."}
          view-nothing []
  [:div.state.state-nothing
   [:h2 "Welcome"]
   [:p "You haven't loaded any todos yet."]
   [:button {:on-click #(dispatch [:nine-states.demo/load {:n 0}])} "Get started"]])

(reg-view ^{:doc "State 2 — Loading: spinner / skeleton."}
          view-loading []
  [:div.state.state-loading
   [:p "Loading todos…"]])

(reg-view ^{:doc "Error branch — transport / server failure (Pattern-RemoteData :error)."}
          view-error []
  (let [err @(subscribe [:todos/error])]
    [:div.state.state-error
     [:p.error (str "Couldn't load: " (:message err))]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 4}])} "Retry"]]))

(reg-view ^{:doc "State 3 — Empty: 'No todos yet' + CTA to add one."}
          view-empty []
  [:div.state.state-empty
   [:h2 "No todos yet"]
   [:p "Add your first todo using the form below."]])

(reg-view ^{:doc "State 4 — One: focused single-item layout."}
          view-one []
  (let [todo (first @(subscribe [:todos/items]))]
    [:div.state.state-one
     [:h2 "Your todo"]
     [:p.title (:title todo)]]))

(reg-view ^{:doc "State 5 — Some: standard list."}
          view-some []
  (let [todos @(subscribe [:todos/items])]
    [:div.state.state-some
     [:h2 (str (count todos) " todos")]
     [:ul (for [t todos] ^{:key (:id t)} [:li (:title t)])]]))

(reg-view ^{:doc "State 6 — Too Many: search + truncation."}
          view-too-many []
  (let [todos    @(subscribe [:todos/items])
        shown    (take too-many-threshold todos)
        overflow (- (count todos) too-many-threshold)]
    [:div.state.state-too-many
     [:h2 (str (count todos) " todos (showing first " too-many-threshold ")")]
     [:input {:type "search" :placeholder "Search todos…"}]
     [:ul (for [t shown] ^{:key (:id t)} [:li (:title t)])]
     (when (pos? overflow)
       [:p.overflow (str "…and " overflow " more.")])]))

(reg-view ^{:doc "State 7 — Incorrect: per-field validation error + recovery path."}
          view-incorrect []
  (let [err @(subscribe [:new-todo/field-error :title])]
    [:div.state.state-incorrect
     [:p.error (str "We can't add that todo: " err)]
     [:p "Please fix the title field below and submit again."]]))

(reg-view ^{:doc "State 8 — Correct: success feedback (toast / checkmark)."}
          view-correct []
  [:div.state.state-correct
   [:p.success "✓ Todo added."]])

(reg-view ^{:doc "State 9 — Done/Frozen: archived list. Read-only."}
          view-done []
  (let [todos @(subscribe [:todos/items])]
    [:div.state.state-done
     [:h2 "Archived"]
     [:p "This list has been archived. It is read-only."]
     [:ul (for [t todos] ^{:key (:id t)} [:li (:title t)])]]))

;; ============================================================================
;; VIEWS — control panel + form + root
;; ============================================================================
;;
;; The form and control panel use `(rf/has-tag? :ui/nine-states
;; :mode/read-only)` to disable themselves when the :mode region is
;; :done. The legacy variant queried `:ui.state/done?` for the same
;; thing; tags are cleaner because the view doesn't need to know
;; *which* state of which region carries the read-only intent.

(reg-view ^{:doc "Form for adding a todo. Drives the form region (states 7 & 8)."}
          new-todo-form []
  (let [draft       @(subscribe [:new-todo/draft])
        field-err   @(subscribe [:new-todo/field-error :title])
        read-only?  @(rf/has-tag? :ui/nine-states :mode/read-only)]
    [:form.new-todo
     {:on-submit (fn [e]
                   (.preventDefault e)
                   (dispatch [:new-todo/submit]))}
     [:input {:type        "text"
              :placeholder "What needs doing?"
              :value       (:title draft)
              :disabled    read-only?
              :on-change   #(dispatch [:new-todo/edit-field :title
                                       (.. % -target -value)])}]
     [:button {:type "submit" :disabled read-only?} "Add"]
     (when field-err [:p.error field-err])]))

(reg-view ^{:doc "Buttons to drive the demo into each of the nine states."}
          control-panel []
  (let [read-only? @(rf/has-tag? :ui/nine-states :mode/read-only)]
    [:div.control-panel
     [:h3 "Drive the demo"]
     [:button {:on-click #(dispatch [:nine-states.app/initialise])
               :disabled read-only?} "1. Nothing"]
     [:button {:on-click #(dispatch [:nine-states.demo/load-with-failure])
               :disabled read-only?} "Trigger error"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 0}])
               :disabled read-only?} "3. Empty"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 1}])
               :disabled read-only?} "4. One"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 4}])
               :disabled read-only?} "5. Some"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 25}])
               :disabled read-only?} "6. Too Many"]
     [:button {:on-click #(dispatch [:ui/nine-states [:archive {}]])
               :disabled read-only?} "9. Archive (Done)"]]))

(reg-view ^{:doc "Root view: one `case` over :ui/render picks exactly one
                  per-state view; the form and control panel render
                  alongside."}
          root-view []
  [:div.app
   [:h1 "Nine States of UI — todos"]
   [control-panel]
   [:hr]
   (case @(subscribe [:ui/render])
     :done      [view-done]
     :correct   [view-correct]
     :incorrect [view-incorrect]
     :nothing   [view-nothing]
     :loading   [view-loading]
     :error     [view-error]
     :empty     [view-empty]
     :one       [view-one]
     :some      [view-some]
     :too-many  [view-too-many]
     [:p "(unrecognised state)"])
   [:hr]
   [new-todo-form]])

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================
;;
;; The DOM mount is gated on (exists? js/document) so the namespace can
;; load in non-DOM CLJS hosts (shadow-cljs :node-test, headless test
;; harnesses, JVM). The headless fixtures live in
;; `test/nine_states/core_test.cljs` and run in any CLJS host without
;; touching React.

(defonce react-root
  (when (exists? js/document)
    (rdc/create-root (js/document.getElementById "app"))))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-slim-adapter/adapter)
  ;; Install the demo override so `:rf.http/managed` calls route to the
  ;; in-process canned-stub fxs above. The example runs standalone — no
  ;; backend required.
  (rf/reg-frame :rf/default
    {:doc          "Nine-states demo frame."
     :fx-overrides {:rf.http/managed :nine-states.http/managed-demo}})
  (rf/dispatch-sync [:nine-states.app/initialise])
  (when react-root
    (rdc/render react-root [root-view])))
