(ns nine-states.core
  "The **Nine States of UI** — one small todos list, rendered every way it
   can possibly be.

   Most apps lavish attention on the happy path and quietly forget the
   other eight states. The empty list, the spinner, the validation error,
   the frozen archive — they get noticed when a user hits them, not
   before. This example takes the whole taxonomy seriously and renders all
   nine, driven by a single `:type :parallel` state machine.

   The nine states (the well-known UX taxonomy):

     1. Nothing      — never fetched; the data region is at `:nothing`.
     2. Loading      — first fetch in flight; the data region is at `:loading`.
     3. Empty        — fetched, but the result is the empty list.
     4. One          — exactly one todo. Focused single-item layout.
     5. Some         — a small, manageable list.
     6. Too Many     — more than a person wants to scan; needs search / paging.
     7. Incorrect    — invalid form input; per-field validation error.
     8. Correct      — a happy-path success after a valid submit.
     9. Done/Frozen  — terminal state; the mode region is at `:done`.

   The trick is that these nine aren't one axis — they're three, running
   at once. A list can be *loading* while the form is *invalid* while the
   page is still *active*. So the machine has three parallel regions, each
   minding its own axis:

     - `:data` region    — the request-lifecycle / cardinality axis. Six
                            states (Nothing / Loading / Empty / One / Some /
                            Too Many) plus an `:error` branch. After a
                            successful fetch an `:always`-cascade counts the
                            items and picks the cardinality bucket.
     - `:form` region    — the form lifecycle: Neutral / Incorrect / Correct.
     - `:mode` region    — the page lifecycle: Active / Done. `:done` is
                            terminal and read-only.

   Each state wears `:tags` announcing its intent (`:data/loading`,
   `:form/invalid`, `:mode/done`, ...). Three axes means several tags are
   live at once — so how does the page decide what to draw? One
   render-priority table (plain data) plus one selector sub (`:ui/render`)
   collapse that tag union down to a single keyword, and the root view's
   `case` over it is the only place the UI ever branches. Nine states, one
   `case`.

   What this example demonstrates:

   - **Parallel regions + state tags** — three orthogonal axes in one
     machine, asked by tag rather than by state name. See the machines
     guide on [parallel regions]
     (../../../docs/machines/concepts.md#when-the-machine-grows) and
     [state tags]
     (../../../docs/machines/concepts.md#guards-actions-tags-and-after--the-recognition-kit).
   - **A managed-HTTP lifecycle** folded right into the `:data` region:
     the region's state-keyword *is* the request status, so there's no
     separate `:status` field to keep in sync with it. See [managed HTTP]
     (../../../docs/resources/glossary.md#managed-http) and the
     [resource-status lifecycle]
     (../../../docs/resources/glossary.md#resource-status).
   - **A form slice** — `{:draft :errors :touched}` holds the form's
     working state in app-db; the form region's state tracks where it is
     in the validate/submit lifecycle. See [Build a form]
     (../../../docs/core/how-to/build-a-form.md).
   - **Inspectability** — guards and actions worth a name get one, as
     entries in the machine's `:guards` / `:actions` maps. Only the truly
     trivial transitions stay inline. Future-you, reading a trace, will
     thank present-you.
   - **Headless tests** — every state has a fixture that drives the
     machine there and checks its tags + `:ui/render`, no browser
     required. The example tree itself is test-free; the fixtures live in
     the framework test tree at
     `implementation/adapters/reagent/test/re_frame/nine_states_cljs_test.cljs`.

   It all lives in one file so you can read it top to bottom. A real
   codebase would spread it across schema / events / subs / views /
   machines files."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            ;; Schemas ship as their own artefact, day8/re-frame2-schemas.
            ;; Requiring this ns wires up the hooks the `rf/reg-app-schema`
            ;; call below leans on.
            [re-frame.schemas]
            ;; Likewise machines, from day8/re-frame2-machines: requiring
            ;; this registers `rf/reg-machine` and the `:rf/machine` /
            ;; `:rf/machine-has-tag?` subs we use throughout.
            [re-frame.machines]
            ;; Managed-HTTP ships in day8/re-frame2-http. Requiring it
            ;; registers the `:rf.http/managed` fx (and its family) that
            ;; the load events dispatch.
            [re-frame.http.managed]
            ;; The framework's canned-reply stubs
            ;; (`:rf.http/managed-canned-success` / `-failure`). Our little
            ;; per-app demo stub below just delegates to these.
            [re-frame.http.test-support]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; CONSTANTS
;; ============================================================================

(def too-many-threshold
  "Where 'a manageable list' ends and 'too many' begins. Up to this many
   items renders plainly; beyond it we switch to the search + truncation
   UI. It's a named def so the machine's `:too-many?` guard and the test
   fixtures all agree on the same number."
  7)

(def new-todo-defaults
  {:draft    {:title ""}
   :errors   {}
   :touched  #{}})

;; ============================================================================
;; SCHEMAS
;; ============================================================================
;;
;; The machine carries its own `:data` shape (see the declaration below), so
;; the only thing left to describe here is the form slice: what the form
;; collects plus its per-field validation state. Note there's no `:status`
;; field — the form region's state-keyword already IS the status.

;; A subtle but important call: `:draft` is a bare `:map`, not the stricter
;; "this is a valid submission" shape. A draft is meant to hold
;; in-progress, half-typed, not-yet-valid input — an empty title before the
;; user types anything is perfectly normal. Put a min-length constraint
;; here and dev-mode schema validation would reject that very first write.
;; So validity lives where validity belongs: in the validate fn (see
;; `validate-new-todo`), not the slice schema. Drafts and submissions are
;; genuinely different shapes, and conflating them is a classic form bug.
(def NewTodoSlice
  [:map
   [:draft   :map]
   [:errors  {:default {}} [:map-of :keyword [:vector :string]]]
   [:touched {:default #{}} [:set :keyword]]])

;; `reg-app-schema` is frame-local — it needs a frame in scope or it raises
;; `:rf.error/no-frame-context`. This example lives in `:rf/default` (the
;; same id `run`'s `frame-provider {:id …}` sets up), so we name it here.
;; Frame identity is carried, not magically found; see the frames glossary:
;; ../../../docs/core/glossary.md#frame-identity-is-carried-not-found
(with-frame :rf/default
  (rf/reg-app-schema [:new-todo] {:schema NewTodoSlice}))

;; ============================================================================
;; RECORDABLE COEFFECTS
;; ============================================================================
;;
;; Here's a small problem with a tidy solution. A submitted todo needs an
;; `:id` — it goes into durable machine data and doubles as the React
;; `:key`. The obvious move is `(random-uuid)` right where we build the
;; todo. The trouble: that's a side effect hiding inside a "pure" handler.
;; Replay the event stream and it mints a *different* id every time, so the
;; snapshot and keys never reproduce. Time-travel quietly breaks.
;;
;; The fix is to treat the fresh id like any other fact the handler needs
;; from the outside world: feed it in as a coeffect. Marking the cofx
;; `:recordable?` means it runs once for real, the value gets recorded, and
;; on replay re-frame2 hands back the *same* value instead of minting a new
;; one. `:new-todo/submit` asks for it via `:rf.cofx/requires` and reads it
;; out of the coeffects map — pure, and replayable. See the
;; recordable-vs-ambient coeffects entry:
;; ../../../docs/core/glossary.md#recordable-vs-ambient-coeffects
(rf/reg-cofx :new-todo/todo-id
  {:recordable? true
   :doc "Replayable fresh id for a newly-submitted todo."}
  (fn [] (random-uuid)))

;; ============================================================================
;; FX  (test-friendly stubs)
;; ============================================================================
;;
;; A real app fires one `:rf.http/managed` request and swaps in a stub at
;; test time via the frame's `:fx-overrides`. This example has no server to
;; talk to, so it ships its own little stub that delegates to the
;; framework's canned-success / canned-failure fxs. That's what lets the
;; control panel conjure Empty / One / Some / Too Many on demand, no
;; backend required. See [managed HTTP](../../../docs/resources/http.md).

(defn- gen-todos [n]
  (vec (for [i (range n)]
         {:id (random-uuid) :title (str "Todo #" (inc i)) :done? false})))

(rf/reg-fx :nine-states.http/managed-demo
  {:doc       "Demo override for `:rf.http/managed`. A tiny fake server,
               routing by URL: `/api/todos` succeeds with N synthetic
               todos (N read from `:request :query :n`); `/api/todos/fail`
               fails with a transport error. Either way it hands off to the
               framework's `:rf.http/managed-canned-success` /
               `:rf.http/managed-canned-failure`, so the reply has exactly
               the shape a real managed request would produce."
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
;; This is the whole model. Three independent axes, all in one declaration,
;; each ticking along on its own:
;;
;;   :data — the request-lifecycle / cardinality axis (states 1-6, plus an
;;           :error branch). When a fetch succeeds it lands in :resolving,
;;           and an :always-cascade counts :items and picks the right
;;           cardinality bucket. The count decides; we never do.
;;
;;   :form — the Neutral / Incorrect / Correct lifecycle (states 7 & 8),
;;           driven by :submit-valid / :submit-invalid / :edit. These
;;           transitions are pure — the slice's :errors / :touched live in
;;           app-db, not in the machine's :data.
;;
;;   :mode — the Active / Done axis (state 9). :done is terminal and wears
;;           :mode/read-only, which is the tag the view reads to grey out
;;           the form and the control buttons.
;;
;; Because the regions run in parallel, a snapshot's :state is a map keyed
;; by region name (one current state each), :data is shared across all
;; three, and :tags is the union of every active state's tags. That union
;; is the thing the render selector reads. See the machines guide:
;; ../../../docs/machines/concepts.md#when-the-machine-grows

(def nine-states-machine
  {:type :parallel

   ;; The machine's shared :data — :items for the data region, plus the
   ;; mode region's :archived-at stamp. One shared bag rather than
   ;; per-region pockets because these axes are facets of one domain. (If
   ;; your axes don't share any data, that's usually a sign you want N
   ;; separate machines, not one with parallel regions.)
   :data {:items       []
          :error       nil
          :archived-at nil}

   :guards
   {:empty?
    (fn guard-empty? [{:keys [data]}]
      (zero? (count (:items data))))

    :one?
    (fn guard-one? [{:keys [data]}]
      (= 1 (count (:items data))))

    :too-many?
    (fn guard-too-many? [{:keys [data]}]
      (> (count (:items data)) too-many-threshold))}

   :actions
   {:set-items
    ;; The :fetch-succeeded event carries the new items in its second
    ;; element — [:fetch-succeeded {:items [...]}] — so we pull them
    ;; straight out of :event.
    (fn action-set-items [{data :data [_ {:keys [items]}] :event}]
      {:data (-> data
                 (assoc :items (vec items))
                 (assoc :error nil))})

    :set-error
    (fn action-set-error [{data :data [_ {:keys [failure]}] :event}]
      {:data (assoc data :error failure)})

    :stamp-archived
    (fn action-stamp-archived [{data :data [_ {:keys [now]}] :event}]
      {:data (assoc data :archived-at (or now 0))})}

   :regions
   {;; ---- :data region — managed-HTTP lifecycle + cardinality ----
    :data
    {:initial :nothing
     :states
     {:nothing
      ;; State 1 — never fetched. The welcome screen.
      {:tags #{:data/nothing}
       :on   {:fetch-started :loading
              :reset         :nothing}}

      :loading
      ;; State 2 — a fetch is in flight. The :data/loading tag is what
      ;; lights up the spinner.
      {:tags #{:data/loading :data/transient}
       :on   {:fetch-succeeded {:target :resolving
                                :action :set-items}
              :fetch-failed    {:target :error
                                :action :set-error}}}

      :resolving
      ;; A blink-and-you-miss-it microstep. :set-items has just written the
      ;; new :items; now the cardinality guards race to claim it and the
      ;; first match wins. The machine doesn't rest here — :always means it
      ;; immediately moves on to whichever bucket fits.
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

    ;; ---- :form region — form lifecycle ----
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
      ;; State 7 — the user submitted something invalid. The :form/invalid
      ;; tag drives the inline error message.
      {:tags #{:form/invalid}
       :on   {:submit-valid :correct
              :edit         :neutral
              :reset        :neutral}}

      :correct
      ;; State 8 — the brief "nice, that worked" moment. It's transient: as
      ;; soon as the user edits again, an :edit drops the region back to
      ;; :neutral.
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
      ;; State 9 — the end of the line. Once archived there's no event out
      ;; of here. :mode/read-only is the tag the form and control panel
      ;; read to disable every input.
      {:tags #{:mode/done :mode/read-only :mode/terminal}}}}}})

(rf/reg-machine :ui/nine-states nine-states-machine)

;; ============================================================================
;; EVENTS — top-level demo wrappers
;; ============================================================================
;;
;; The control-panel buttons dispatch friendly, high-level demo events.
;; Each one does two things at once: it nudges the form slice in app-db and
;; it broadcasts into the :ui/nine-states machine. Could the view dispatch
;; the machine directly? Sure — but then the view would be juggling
;; "clear the form, bump app-db, poke the machine" itself. Wrapping that up
;; behind one named event keeps the imperative housekeeping out of the view,
;; where it doesn't belong.

(rf/reg-event :nine-states.app/initialise
  {:doc "Back to square one: seed the form slice and reset the machine to
         its initial state."}
  (fn handler-app-initialise [{:keys [db]} _]
    {:db (assoc db :new-todo new-todo-defaults)
     :fx [[:dispatch [:ui/nine-states [:reset]]]]}))

(rf/reg-event :nine-states.demo/load
  {:doc "Kick off a (synthetic) fetch through the demo HTTP stub. The reply
         folds back through :nine-states.demo/loaded into the machine's
         :fetch-succeeded event, and from there the :data region's
         :always-cascade reads the count and picks the cardinality bucket.
         Ask for n items, get the matching state."}
  (fn handler-demo-load [_ [_ {:keys [n]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           {:request    {:method :get
                         :url    "/api/todos"
                         :query  {:n (or n 0)}}
            :decode     :json
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

(rf/reg-event :nine-states.demo/load-with-failure
  {:doc "Same idea, but the fetch is rigged to fail — the :data region
         lands in :error."}
  (fn handler-demo-load-with-failure [_ _]
    {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
          [:rf.http/managed
           {:request    {:method :get :url "/api/todos/fail"}
            :decode     :json
            :on-success [:nine-states.demo/loaded]
            :on-failure [:nine-states.demo/load-failed]}]]}))

(rf/reg-event :nine-states.demo/loaded
  {:doc "The fetch came back. Hand the items to the machine via
         :fetch-succeeded and let the :always-cascade sort out the bucket."}
  (fn handler-demo-loaded [_ [_ {:keys [value]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-succeeded {:items value}]]]]}))

(rf/reg-event :nine-states.demo/load-failed
  {:doc "The fetch fell over. Pass the failure category along to the
         machine, which routes the :data region to :error."}
  (fn handler-demo-load-failed [_ [_ {:keys [failure]}]]
    {:fx [[:dispatch [:ui/nine-states [:fetch-failed {:failure failure}]]]]}))

;; ---- form-slice events ----

(defn- validate-new-todo
  "Pure validator. Hand it a draft, get back a {field [errors...]} map —
   empty when everything checks out. No app-db, no side effects, just
   data in and data out, which makes it trivial to unit-test in isolation."
  [{:keys [title]}]
  (cond-> {}
    (or (nil? title) (< (count title) 3))
    (assoc :title ["Title must be at least 3 characters."])

    (and title (> (count title) 80))
    (assoc :title ["Title must be at most 80 characters."])))

(rf/reg-event :new-todo/edit-field
  {:doc  "The user typed in a field. Update the :draft, mark the field as
          touched (so its errors are allowed to show), and broadcast :edit
          into the machine — which gently returns the :form region from
          :correct or :incorrect back to :neutral."
   :schema [:cat [:= :new-todo/edit-field] :keyword :string]}
  (fn handler-new-todo-edit-field [{:keys [db]} [_ field value]]
    {:db (-> db
             (assoc-in  [:new-todo :draft field] value)
             (update-in [:new-todo :touched]    conj field))
     :fx [[:dispatch [:ui/nine-states [:edit]]]]}))

(rf/reg-event :new-todo/submit
  {:doc "The submit button. Validate the draft and fork:
         invalid → :submit-invalid and the :form region lands in
         :incorrect. Valid → append the new todo to the machine's items
         (via :fetch-succeeded), clear the draft, and broadcast
         :submit-valid so the :form region lands in :correct."
   ;; The new todo's id comes from a recordable coeffect, not a
   ;; `(random-uuid)` read here at the write site (see the
   ;; `:new-todo/todo-id` reg-cofx above). That's what keeps replay
   ;; handing back the same id every time.
   :rf.cofx/requires [:new-todo/todo-id]}
  ;; A machine snapshot is durable runtime-db state, so we read it from the
  ;; `:rf.db/runtime` coeffect rather than from app-db.
  (fn handler-new-todo-submit [{:keys [db] rt :rf.db/runtime new-id :new-todo/todo-id} _]
    (let [draft  (get-in db [:new-todo :draft])
          errors (validate-new-todo draft)
          items  (get-in rt [:rf.runtime/machines :snapshots :ui/nine-states :data :items])]
      (if (seq errors)
        ;; Invalid. Stash the errors and mark every offending field as
        ;; touched, which is what lets those errors actually show.
        {:db (-> db
                 (assoc-in [:new-todo :errors]  errors)
                 (assoc-in [:new-todo :touched] (set (keys errors))))
         :fx [[:dispatch [:ui/nine-states [:submit-invalid]]]]}
        ;; Valid. Append the todo and clear the form. Note we don't quietly
        ;; poke :items — we go the long way round, through the data region's
        ;; real lifecycle (:loading → :resolving), so the :always-cascade
        ;; re-counts and re-picks the cardinality bucket. One canonical path
        ;; for changing items means one place the cardinality is decided.
        (let [new-items (conj (vec items)
                              {:id     new-id
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

(rf/reg-sub :new-todo/slice
  (fn sub-new-todo [db _] (get db :new-todo)))

(rf/reg-sub :new-todo/draft   :<- [:new-todo/slice] (fn [s _] (:draft s)))
(rf/reg-sub :new-todo/errors  :<- [:new-todo/slice] (fn [s _] (:errors s)))
(rf/reg-sub :new-todo/touched :<- [:new-todo/slice] (fn [s _] (:touched s)))

(rf/reg-sub :new-todo/field-error
  {:doc "The error for one field — but only once the user has actually
         touched it. Nobody likes a form that scolds you for leaving a
         field blank before you've even reached it."}
  :<- [:new-todo/errors]
  :<- [:new-todo/touched]
  (fn sub-new-todo-field-error [[errs touched] [_ field-id]]
    (when (touched field-id)
      (first (get errs field-id)))))

;; The todos themselves — read straight off the machine's snapshot.
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
;; This is the keystone: the spot where three parallel axes collapse into
;; one render decision. Several tags are live at once, but the page can only
;; draw one thing, so we need a tie-breaker — and we make it plain data.
;;
;; `render-priority` is just a vector of {:tag :render} pairs read in order.
;; The :ui/render sub walks it and returns the first :render whose :tag is
;; present in the snapshot's tag union. That's the whole decision: a pure
;; function over a data table, living in exactly one readable place. The
;; root view's `case` does nothing cleverer than map the keyword to a view.
;;
;; The order encodes the priority, top to bottom:
;;   :mode wins outright — once archived, that view replaces everything.
;;   :form wins next — the success / error acknowledgement is a quick,
;;     transient overlay on top of whatever the list is doing.
;;   :data is the fallback — the steady-state cardinality bucket.

(def render-priority
  [;; mode region — read-only / terminal trumps all
   {:tag :mode/done       :render :done}
   ;; form region — the transient Correct / Incorrect acknowledgements
   {:tag :form/success    :render :correct}
   {:tag :form/invalid    :render :incorrect}
   ;; data region — error first (also transient), then loading, then the
   ;; resting cardinality buckets
   {:tag :data/loading    :render :loading}
   {:tag :data/error      :render :error}
   {:tag :data/nothing    :render :nothing}
   {:tag :data/empty      :render :empty}
   {:tag :data/one        :render :one}
   {:tag :data/some       :render :some}
   {:tag :data/too-many   :render :too-many}])

(rf/reg-sub :ui/render
  {:doc "Boil the whole page down to one keyword: walk the render-priority
         table against the machine's tag union and return the first match.
         This sub feeds the root view's `case` — the one and only place the
         UI branches. Everything else just asks the tags directly."}
  :<- [:rf/machine :ui/nine-states]
  (fn sub-ui-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

;; ============================================================================
;; VIEWS — one per render-model keyword
;; ============================================================================

(reg-view ^{:doc "State 1 — Nothing: a blank slate with a 'Get started' nudge."}
          view-nothing []
  [:div.state.state-nothing
   [:h2 "Welcome"]
   [:p "You haven't loaded any todos yet."]
   [:button {:on-click #(dispatch [:nine-states.demo/load {:n 0}])} "Get started"]])

(reg-view ^{:doc "State 2 — Loading: spinner / skeleton."}
          view-loading []
  [:div.state.state-loading
   [:p "Loading todos…"]])

(reg-view ^{:doc "Error branch — the fetch failed; apologise and offer a retry."}
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

(reg-view ^{:doc "State 6 — Too Many: more than anyone wants to scroll, so
                  show a search box and truncate the rest."}
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
;; The form and control panel grey themselves out once the page is archived,
;; using `(rf/machine-has-tag? :ui/nine-states :mode/read-only)`. Notice
;; what they DON'T ask: "are we in the :done state of the :mode region?"
;; They ask "is this read-only?" and leave it at that. The view states an
;; intent and lets the machine decide which state happens to carry it —
;; ask, don't tell. Move that tag to another state tomorrow and these views
;; don't change a line.

(reg-view ^{:doc "The add-a-todo form — the thing that drives the form
                  region through states 7 (Incorrect) and 8 (Correct)."}
          new-todo-form []
  (let [draft       @(subscribe [:new-todo/draft])
        field-err   @(subscribe [:new-todo/field-error :title])
        read-only?  @(rf/machine-has-tag? :ui/nine-states :mode/read-only)]
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

(reg-view ^{:doc "Your remote control: one button per state, so you can
                  jump the demo straight to any of the nine."}
          control-panel []
  (let [read-only? @(rf/machine-has-tag? :ui/nine-states :mode/read-only)]
    [:div.control-panel
     [:h3 "Drive the demo"]
     [:button {:on-click #(dispatch [:nine-states.app/initialise])
               :data-testid "ns-button-nothing"
               :disabled read-only?} "1. Nothing"]
     [:button {:on-click #(dispatch [:nine-states.demo/load-with-failure])
               :data-testid "ns-button-trigger-error"
               :disabled read-only?} "Trigger error"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 0}])
               :data-testid "ns-button-empty"
               :disabled read-only?} "3. Empty"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 1}])
               :data-testid "ns-button-one"
               :disabled read-only?} "4. One"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 4}])
               :data-testid "ns-button-some"
               :disabled read-only?} "5. Some"]
     [:button {:on-click #(dispatch [:nine-states.demo/load {:n 25}])
               :data-testid "ns-button-too-many"
               :disabled read-only?} "6. Too Many"]
     [:button {:on-click #(dispatch [:ui/nine-states [:archive {}]])
               :data-testid "ns-button-archive"
               :disabled read-only?} "9. Archive (Done)"]]))

(reg-view ^{:doc "The root view, and the punchline of the whole example: a
                  single `case` over :ui/render picks exactly one per-state
                  view. The control panel and form sit alongside it,
                  always on. Nine states, one branch."}
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
;; The mount lives inside `run`, deliberately not at namespace-load time.
;; Here's why: the browser-test bundle requires several example namespaces
;; at once, and they all share one `#app` element. If each grabbed it with
;; `rdc/create-root` the moment its ns loaded, they'd trample each other —
;; React's "createRoot called on the same container twice" warning, plus
;; cross-example leakage. Keeping `create-root` inside `run` means ns-load
;; has zero DOM side effects, and the namespaces coexist in one build
;; without a fuss.

(defonce react-root (atom nil))

(defn run []
  ;; Tell the runtime which substrate to render through. Just the adapter —
  ;; no frame yet.
  (rf/init! reagent-adapter/adapter)
  ;; The `frame-provider {:id …}` below is what actually owns the frame. On
  ;; the first mount it creates `:rf/default`, applies the config, and fires
  ;; `:initial-events` once; on a hot reload it finds the frame already
  ;; standing and skips the re-seed. Two config keys do the work:
  ;;
  ;; - `:fx-overrides` points `:rf.http/managed` at our in-process stub, so
  ;;   the example runs all on its own with no backend in sight.
  ;; - `:initial-events` boots the app via `[:nine-states.app/initialise]`.
  ;;
  ;; The provider also scopes the frame into React — that's what lets the
  ;; `dispatch`/`subscribe` injected into each `reg-view` (and `root-view`'s
  ;; subs) find `:rf/default`. Same `:rf/default` the `reg-app-schema` up top
  ;; attached to.
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id             :rf/default
                                    :doc            "Nine-states demo frame."
                                    :fx-overrides   {:rf.http/managed :nine-states.http/managed-demo}
                                    :initial-events [[:nine-states.app/initialise]]}
                 [root-view]])))
