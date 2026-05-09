(ns crud.crud
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
            ;; Per rf2-p7va, re-frame.schemas ships in
            ;; day8/re-frame-2-schemas. Loading the ns here registers
            ;; its late-bind hooks so rf/reg-app-schema resolves.
            [re-frame.schemas]
            [re-frame.substrate.reagent :as reagent-adapter])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(def Person
  [:map
   [:id      :uuid]
   [:name    :string]
   [:surname :string]])

(def CrudState
  [:map
   [:people       [:vector Person]]
   [:filter-text  :string]
   [:selected-id  [:maybe :uuid]]
   [:draft        [:map
                   [:name    :string]
                   [:surname :string]]]])

(rf/reg-app-schema [:crud] CrudState)

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-db :crud/initialise
  {:doc "Seed the list with the 7GUIs reference data."}
  (fn handler-crud-initialise [db _]
    (assoc db :crud {:people      [{:id (random-uuid) :name "Hans"  :surname "Emil"}
                                   {:id (random-uuid) :name "Max"   :surname "Mustermann"}
                                   {:id (random-uuid) :name "Roman" :surname "Tisch"}]
                     :filter-text ""
                     :selected-id nil
                     :draft       {:name "" :surname ""}})))

(rf/reg-event-db :crud/set-filter
  {:doc "User typed in the filter input."}
  (fn handler-crud-set-filter [db [_ s]]
    (assoc-in db [:crud :filter-text] s)))

(rf/reg-event-db :crud/select
  {:doc "User clicked a list entry. Populates the draft from the selected person."
   :spec [:cat [:= :crud/select] :uuid]}
  (fn handler-crud-select [db [_ id]]
    (let [people (get-in db [:crud :people])
          person (first (filter #(= id (:id %)) people))]
      (-> db
          (assoc-in [:crud :selected-id] id)
          (assoc-in [:crud :draft]       (select-keys person [:name :surname]))))))

(rf/reg-event-db :crud/edit-name
  (fn handler-crud-edit-name [db [_ s]]
    (assoc-in db [:crud :draft :name] s)))

(rf/reg-event-db :crud/edit-surname
  (fn handler-crud-edit-surname [db [_ s]]
    (assoc-in db [:crud :draft :surname] s)))

(rf/reg-event-db :crud/create
  {:doc "Add a new person from the draft. Selects the new entry."}
  (fn handler-crud-create [db _]
    (let [new-id (random-uuid)
          {:keys [name surname]} (get-in db [:crud :draft])]
      (-> db
          (update-in [:crud :people] conj {:id new-id :name name :surname surname})
          (assoc-in  [:crud :selected-id] new-id)))))

(rf/reg-event-db :crud/update
  {:doc "Apply the draft to the selected person."}
  (fn handler-crud-update [db _]
    (let [{:keys [selected-id draft]} (:crud db)]
      (if selected-id
        (update-in db [:crud :people]
                   (fn [people]
                     (mapv #(if (= selected-id (:id %)) (merge % draft) %) people)))
        db))))

(rf/reg-event-db :crud/delete
  {:doc "Remove the selected person."}
  (fn handler-crud-delete [db _]
    (let [{:keys [selected-id]} (:crud db)]
      (if selected-id
        (-> db
            (update-in [:crud :people] (fn [ps] (vec (remove #(= selected-id (:id %)) ps))))
            (assoc-in  [:crud :selected-id] nil)
            (assoc-in  [:crud :draft]       {:name "" :surname ""}))
        db))))

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
  :<- [:crud/selected-id]
  (fn [id _] (some? id)))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view crud-view []
  (let [people      @(subscribe [:crud/filtered-people])
        selected-id @(subscribe [:crud/selected-id])
        d-name      @(subscribe [:crud/draft-name])
        d-surname   @(subscribe [:crud/draft-surname])
        can-update? @(subscribe [:crud/can-update?])]
    [:div.crud
     [:div.row
      [:label "Filter prefix: "]
      [:input {:type      "text"
               :on-change #(dispatch [:crud/set-filter (.. % -target -value)])}]]
     [:div.row
      [:select.list {:size      6
                     :value     (or selected-id "")
                     :on-change #(dispatch [:crud/select (uuid (.. % -target -value))])}
       (for [{:keys [id name surname]} people]
         ^{:key id}
         [:option {:value id} (str surname ", " name)])]

      [:div.inputs
       [:div [:label "Name: "]
        [:input {:type      "text"
                 :value     d-name
                 :on-change #(dispatch [:crud/edit-name (.. % -target -value)])}]]
       [:div [:label "Surname: "]
        [:input {:type      "text"
                 :value     d-surname
                 :on-change #(dispatch [:crud/edit-surname (.. % -target -value)])}]]]]
     [:div.row.buttons
      [:button {:on-click #(dispatch [:crud/create])} "Create"]
      [:button {:on-click #(dispatch [:crud/update]) :disabled (not can-update?)} "Update"]
      [:button {:on-click #(dispatch [:crud/delete]) :disabled (not can-update?)} "Delete"]]]))

;; ============================================================================
;; HEADLESS TESTS
;; ============================================================================

(defn crud-tests []
  (with-frame [f (rf/make-frame {:on-create [:crud/initialise]})]
    ;; Initial seed: 3 people.
    (assert (= 3 (count (rf/compute-sub [:crud/people] (rf/get-frame-db f)))))

    ;; Filter: "M" matches Mustermann only.
    (rf/dispatch-sync [:crud/set-filter "M"] {:frame f})
    (assert (= 1 (count (rf/compute-sub [:crud/filtered-people] (rf/get-frame-db f)))))

    ;; Create: edit draft, click Create, list grows by 1.
    (rf/dispatch-sync [:crud/set-filter "" ] {:frame f})
    (rf/dispatch-sync [:crud/edit-name    "Anna"]    {:frame f})
    (rf/dispatch-sync [:crud/edit-surname "Sonnen"] {:frame f})
    (rf/dispatch-sync [:crud/create]                 {:frame f})
    (assert (= 4 (count (rf/compute-sub [:crud/people] (rf/get-frame-db f)))))

    ;; Update: select Anna, change surname, list reflects.
    (let [anna-id (->> (rf/compute-sub [:crud/people] (rf/get-frame-db f))
                       (filter #(= "Anna" (:name %))) first :id)]
      (rf/dispatch-sync [:crud/select anna-id]              {:frame f})
      (rf/dispatch-sync [:crud/edit-surname "Sunnybaum"]    {:frame f})
      (rf/dispatch-sync [:crud/update]                      {:frame f})
      (let [updated (->> (rf/compute-sub [:crud/people] (rf/get-frame-db f))
                         (filter #(= anna-id (:id %))) first)]
        (assert (= "Sunnybaum" (:surname updated))))

      ;; Delete: list shrinks by 1.
      (rf/dispatch-sync [:crud/delete] {:frame f})
      (assert (= 3 (count (rf/compute-sub [:crud/people] (rf/get-frame-db f))))))))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is named `react-root` (not `root`) so it does NOT
;; collide with the `crud-view` reg-view above; reg-view defs vars in
;; this ns and any name-collision would shadow the rdc root handle.
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  ;; rf2-84po: re-frame.substrate.reagent ns-load auto-registers as default.
  (rf/init!)
  (rf/dispatch-sync [:crud/initialise])
  (rdc/render react-root [crud-view]))
