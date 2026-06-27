(ns seven-guis.crud.core
  "7GUIs #5 — CRUD.

   A name list with a prefix filter, two text inputs (surname / name), and
   three buttons: Create, Update, Delete. Selecting a list entry populates
   the inputs; Update writes back; Create adds a new row; Delete removes the
   selected row.

   This is a master/detail interaction. The inputs are a subscription on the
   selection, not their own React state. Editing them dispatches into a
   `:draft` slice; the list shows committed values. Selection lives in app-db,
   so it never falls out of sync with the inputs.

   The frame is established in one spot: the render root's
   `frame-provider {:id …}` creates the app frame, configures it, and runs its
   `:initial-events` seed once.

   Shows:
   - List operations (add / update / delete)
   - Selection held as state, not as React component identity
   - A derived filtered list (a subscription on two other subscriptions)
   - A schema-bound entity"
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Requiring `re-frame.schemas` wires up schema validation so
            ;; `rf/reg-app-schema` works. See the guide: docs/guide/how-to/validate-with-schemas.md.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; A person's `:id` is written into durable app-db (`[:crud :people]`) and used
;; as the selection and React `:key`. A durable id must be reproducible on
;; replay, so it can't come from an ambient `(random-uuid)` read at the write
;; site. We mint it as a monotonic `:int` from a db-held `:next-id` counter
;; instead. See docs/guide/glossary.md#recordable-vs-ambient-coeffects.
(def Person
  [:map
   [:id      :int]
   [:name    :string]
   [:surname :string]])

(def CrudState
  [:map
   [:people       [:vector Person]]
   [:next-id      :int]                      ;; deterministic id allocator
   [:filter-text  :string]
   [:selected-id  [:maybe :int]]
   [:draft        [:map
                   [:name    :string]
                   [:surname :string]]]])

;; Schemas register per frame, so the registration has to name a frame.
;; `with-frame` supplies that name: `:rf/default`, the frame the render-root
;; `frame-provider` creates and whose commits this schema validates. The frame
;; need not exist yet — the name is enough.
;; See docs/guide/how-to/validate-with-schemas.md.
(with-frame :rf/default
  (rf/reg-app-schema [:crud] {:schema CrudState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :crud/initialise
  {:doc "Seed the list with the 7GUIs reference data. Ids are monotonic ints
         so a replay reproduces the same people. `:next-id` is the allocator
         for later Create."}
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
  {:doc "Add a new person from the draft, and select the new entry. The id
         comes from the db-held `:next-id` counter, which is then bumped."}
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
  {:doc "True when the selected row is visible under the active filter. A row
         hidden by the filter must not be actionable, or Update/Delete would
         touch a row you can't see. This is derived from the filtered list, so
         the selection is kept and re-enables itself once the filter clears."}
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

;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. ns-load must do no DOM work, so co-required example namespaces
;; don't race each other onto the shared `#app`. See examples/TESTING.md
;; (Example mount-isolation).
(defonce react-root (atom nil))

;; The frame lifecycle lives in one spot: the `frame-provider {:id app-frame …}`
;; below. On first mount it creates the frame, applies its config, and runs
;; `:initial-events` once to seed app-db (`:crud/initialise`). After that, every
;; `dispatch`/`subscribe` in the tree resolves to that frame. On hot reload the
;; provider reuses the existing frame and skips re-seeding, so the list keeps its
;; rows across re-mounts. See docs/guide/glossary.md#frame-provider.
;;
;; `app-frame` is just an id we pick. `:rf/default` is an ordinary frame id with
;; no special privilege — the runtime won't infer it, so we name it here and hand
;; it to the provider like any other id.
(def app-frame :rf/default)

(defn run []
  ;; `init!` installs the reactive adapter for the process.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :initial-events [[:crud/initialise]]}
                 [crud-view]])))
