(ns seven-guis.crud.core
  "7GUIs #5 — CRUD.

   A name list with a prefix filter, two text inputs (surname / name), and
   three buttons: Create, Update, Delete. Selecting a list entry populates
   the inputs; Update writes back; Create adds a new row; Delete removes the
   selected row.

   The 7GUIs page calls this out as a test of *master/detail interaction*.
   The classic trap is to keep the inputs as their own React state, separate
   from the list — they fall out of sync when selection changes. The
   re-frame2 approach: the inputs *are* a sub of the selection. Editing
   them dispatches into a 'draft' slice; the list shows committed values.

   Demonstrates:
   - List operations (add / update / delete)              (CP-1)
   - Selection as state, not as React component identity  (P8: low hidden context)
   - Derived filtered list                                 (CP-2 with :<-)
   - Schema-bound entity                                   (CP-8)"
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; `re-frame.schemas` ships in day8/re-frame2-schemas.
            ;; Loading the ns here registers its late-bind hooks so
            ;; rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; EP-0010 (Causal World Inputs): a person's `:id` is written into durable
;; app-db (`[:crud :people]`) and used as the selection / React `:key`, so it
;; must be a function of prior frame-state — never an ambient `(random-uuid)` at
;; the durable-write site (an event-stream replay would mint different ids). The
;; id is an internal handle, so we mint it deterministically as a monotonic
;; `:int` from a db-held `:next-id` counter — the todomvc `allocate-next-id`
;; idiom, matching sibling 7GUIs `circle_drawer` — rather than a uuid.
(def Person
  [:map
   [:id      :int]
   [:name    :string]
   [:surname :string]])

(def CrudState
  [:map
   [:people       [:vector Person]]
   [:next-id      :int]                      ;; deterministic id allocator (EP-0010)
   [:filter-text  :string]
   [:selected-id  [:maybe :int]]
   [:draft        [:map
                   [:name    :string]
                   [:surname :string]]]])

;; EP-0002 (rf2-5q7um6): reg-app-schema is context-required frame-local; a
;; bare ns-load call raises :rf.error/no-frame-context. This example runs in
;; :rf/default (see `run`/`reg-frame app-frame`), so name it explicitly so the
;; schema binds to the app frame whose commits it validates.
(with-frame :rf/default
  (rf/reg-app-schema [:crud] {:schema CrudState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :crud/initialise
  {:doc "Seed the list with the 7GUIs reference data. Ids are deterministic
         monotonic ints (EP-0010) so a fresh-event-stream replay reproduces
         the same durable people. `:next-id` is the allocator for subsequent
         Create."}
  (fn handler-crud-initialise [{:keys [db]} _]
    {:db (assoc db :crud {:people      [{:id 1 :name "Hans"  :surname "Emil"}
                                   {:id 2 :name "Max"   :surname "Mustermann"}
                                   {:id 3 :name "Roman" :surname "Tisch"}]
                     :next-id     4
                     :filter-text ""
                     :selected-id nil
                     :draft       {:name "" :surname ""}})}))

(rf/reg-event :crud/set-filter
  {:doc "User typed in the filter input."}
  (fn handler-crud-set-filter [{:keys [db]} [_ s]]
    {:db (assoc-in db [:crud :filter-text] s)}))

(rf/reg-event :crud/select
  {:doc "User clicked a list entry. Populates the draft from the selected person."
   :schema [:cat [:= :crud/select] :int]}
  (fn handler-crud-select [{:keys [db]} [_ id]]
    {:db (let [people (get-in db [:crud :people])
          person (first (filter #(= id (:id %)) people))]
      (-> db
          (assoc-in [:crud :selected-id] id)
          (assoc-in [:crud :draft]       (select-keys person [:name :surname]))))}))

(rf/reg-event :crud/edit-name
  (fn handler-crud-edit-name [{:keys [db]} [_ s]]
    {:db (assoc-in db [:crud :draft :name] s)}))

(rf/reg-event :crud/edit-surname
  (fn handler-crud-edit-surname [{:keys [db]} [_ s]]
    {:db (assoc-in db [:crud :draft :surname] s)}))

(rf/reg-event :crud/create
  {:doc "Add a new person from the draft. Selects the new entry. The id is
         minted deterministically from the db-held `:next-id` counter (EP-0010
         causal world inputs — a durable id must be a function of prior
         frame-state, not an ambient `(random-uuid)` read), then the counter is
         bumped."}
  (fn handler-crud-create [{:keys [db]} _]
    {:db (let [new-id (get-in db [:crud :next-id])
          {:keys [name surname]} (get-in db [:crud :draft])]
      (-> db
          (update-in [:crud :people] conj {:id new-id :name name :surname surname})
          (assoc-in  [:crud :next-id] (inc new-id))
          (assoc-in  [:crud :selected-id] new-id)))}))

(rf/reg-event :crud/update
  {:doc "Apply the draft to the selected person."}
  (fn handler-crud-update [{:keys [db]} _]
    {:db (let [{:keys [selected-id draft]} (:crud db)]
      (if selected-id
        (update-in db [:crud :people]
                   (fn [people]
                     (mapv #(if (= selected-id (:id %)) (merge % draft) %) people)))
        db))}))

(rf/reg-event :crud/delete
  {:doc "Remove the selected person."}
  (fn handler-crud-delete [{:keys [db]} _]
    {:db (let [{:keys [selected-id]} (:crud db)]
      (if selected-id
        (-> db
            (update-in [:crud :people] (fn [ps] (vec (remove #(= selected-id (:id %)) ps))))
            (assoc-in  [:crud :selected-id] nil)
            (assoc-in  [:crud :draft]       {:name "" :surname ""}))
        db))}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================

(rf/reg-sub :crud/people      (fn [db _] (get-in db [:crud :people])))
(rf/reg-sub :crud/filter-text (fn [db _] (get-in db [:crud :filter-text])))
(rf/reg-sub :crud/selected-id (fn [db _] (get-in db [:crud :selected-id])))
(rf/reg-sub :crud/draft-name    (fn [db _] (get-in db [:crud :draft :name])))
(rf/reg-sub :crud/draft-surname (fn [db _] (get-in db [:crud :draft :surname])))

(rf/reg-sub :crud/filtered-people
  {:doc "People whose surname starts with the filter prefix (case-insensitive)."}
  :<- [:crud/people]
  :<- [:crud/filter-text]
  (fn sub-crud-filtered-people [[people prefix] _]
    (let [pfx (str/lower-case (or prefix ""))]
      (if (str/blank? pfx)
        people
        (filterv #(str/starts-with? (str/lower-case (:surname %)) pfx)
                 people)))))

(rf/reg-sub :crud/can-update?
  {:doc "Update/Delete are enabled only when the selected row is *visible*
         under the active filter. A selection hidden by the filter must not
         be actionable — Update/Delete would otherwise touch an invisible row.
         This is the master/detail edge the 7GUIs CRUD task is meant to model:
         the answer is derived state (a sub of the filtered list), so the
         selection is preserved and re-enables itself when the filter clears."}
  :<- [:crud/selected-id]
  :<- [:crud/filtered-people]
  (fn sub-crud-can-update? [[id visible-people] _]
    (boolean (and id (some #(= id (:id %)) visible-people)))))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view crud-view []
  (let [people      @(subscribe [:crud/filtered-people])
        filter-text @(subscribe [:crud/filter-text])
        selected-id @(subscribe [:crud/selected-id])
        d-name      @(subscribe [:crud/draft-name])
        d-surname   @(subscribe [:crud/draft-surname])
        can-update? @(subscribe [:crud/can-update?])]
    [:div.crud
     [:div.row
      [:label "Filter prefix: "]
      [:input {:type      "text"
               :data-testid "crud-filter"
               :value     filter-text
               :on-change #(dispatch [:crud/set-filter (.. % -target -value)])}]]
     [:div.row
      [:select.list {:size      6
                     :data-testid "crud-list"
                     :value     (or selected-id "")
                     :on-change #(dispatch [:crud/select (js/parseInt (.. % -target -value) 10)])}
       (for [{:keys [id name surname]} people]
         ^{:key id}
         [:option {:value id} (str surname ", " name)])]

      [:div.inputs
       [:div [:label "Name: "]
        [:input {:type      "text"
                 :value     d-name
                 :data-testid "crud-name"
                 :on-change #(dispatch [:crud/edit-name (.. % -target -value)])}]]
       [:div [:label "Surname: "]
        [:input {:type      "text"
                 :value     d-surname
                 :data-testid "crud-surname"
                 :on-change #(dispatch [:crud/edit-surname (.. % -target -value)])}]]]]
     [:div.row.buttons
      [:button {:on-click #(dispatch [:crud/create])
                :data-testid "crud-create"} "Create"]
      [:button {:on-click #(dispatch [:crud/update])
                :data-testid "crud-update"
                :disabled (not can-update?)} "Update"]
      [:button {:on-click #(dispatch [:crud/delete])
                :data-testid "crud-delete"
                :disabled (not can-update?)} "Delete"]]]))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; EP-0002 (rf2-9o48ih): under the carried invariant the runtime never
;; synthesises a frame from absence — an app must establish its frame
;; explicitly. `init!` installs the adapter (it does NOT create the frame),
;; `reg-frame` registers the app frame, the boot dispatch runs under
;; `with-frame`, and the render is wrapped in a `frame-provider` so every
;; in-tree `dispatch`/`subscribe` resolves to the app frame. Matches the
;; canonical mount in examples/reagent/counter/core.cljs.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:crud/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [crud-view]])))
