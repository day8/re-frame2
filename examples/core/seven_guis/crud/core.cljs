(ns seven-guis.crud.core
  "7GUIs #5 — CRUD.

   A name list, a prefix filter, two text inputs (surname / name), and three
   buttons: Create, Update, Delete. Click a name and the inputs fill in. Update
   writes your edits back, Create adds a row, Delete removes the selected one.
   The classic master/detail screen.

   The trick that makes it feel solid: the inputs are not their own React state.
   They're a subscription on a `:draft` slice in app-db, and typing dispatches
   an edit event. The list shows committed values; the draft holds what you're
   editing. Because the selection lives in app-db too, the inputs and the
   highlighted row can never drift out of sync — there's only one source of
   truth, and both read from it.

   The whole frame is set up in one place: the render root's
   `frame-provider {:id …}` creates the app frame, configures it, and runs its
   `:initial-events` seed exactly once.

   Worth watching here:
   - List operations — add / update / delete
   - Selection kept as state, not as React component identity
   - A filtered list derived from two other subscriptions
   - A schema-bound entity"
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Pulling in `re-frame.schemas` switches on schema validation, which
            ;; is what makes `rf/reg-app-schema` below mean anything. Guide:
            ;; docs/guide/how-to/validate-with-schemas.md.
            [re-frame.schemas]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; SCHEMA
;; ============================================================================

;; A person's `:id` does real work: it's the selection, and it's the React
;; `:key`. It also lives in durable app-db (`[:crud :people]`) forever, which
;; means it has to survive a replay unchanged — replay a recording and you must
;; get the *same* ids back. That rules out `(random-uuid)` at the write site (a
;; fresh random number every time is the opposite of reproducible). So we count
;; instead: a monotonic `:int` handed out from a `:next-id` counter that lives in
;; the db, where replay can see it.
;; See docs/guide/glossary.md#recordable-vs-ambient-coeffects.
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

;; A schema belongs to a frame, so registering one has to say which frame.
;; `with-frame` is how we say it: `:rf/default`, the same frame the render-root
;; `frame-provider` will create, and the one whose commits this schema then
;; checks. Note we register against the frame before it exists — the name is all
;; the machinery needs to hang the schema on.
;; See docs/guide/how-to/validate-with-schemas.md.
(with-frame :rf/default
  (rf/reg-app-schema [:crud] {:schema CrudState}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event :crud/initialise
  {:doc "Seed the list with the three people from the 7GUIs reference. Their ids
         are plain counted ints, so a replay rebuilds the exact same list.
         `:next-id` starts at 4 — the next id Create will hand out."}
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
  {:doc "User clicked a name in the list. Remember the selection, and copy that
         person's name/surname into the draft so the inputs show them."
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
  {:doc "Add a new person from the draft and select them. They get the next id
         from the `:next-id` counter, which we then bump for whoever's after."}
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
  {:doc "True when the selected row is actually visible under the current filter.
         The point: you shouldn't be able to Update or Delete a row you can't
         see. We don't drop the selection when the filter hides it, though — we
         just ask the *filtered* list whether the selection is in it. Clear the
         filter and the row reappears, and the buttons light back up on their
         own."}
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

;; We stash the React root in an atom and create it lazily inside `run`, never at
;; ns-load time. The rule: loading this namespace must touch no DOM. Several
;; example namespaces get required into one test page, and if each grabbed the
;; shared `#app` on load they'd trample each other. Deferring to `run` keeps them
;; out of each other's way. See examples/TESTING.md (Example mount-isolation).
(defonce react-root (atom nil))

;; The frame's whole life happens in one place: the `frame-provider {:id app-frame …}`
;; down in `run`. On the first mount it creates the frame, applies its config,
;; and fires `:initial-events` once to seed app-db (that's `:crud/initialise`).
;; From then on, every `dispatch` and `subscribe` in the tree below it lands in
;; that frame. Hot-reload is the nice part: the provider finds the frame already
;; there, reuses it, and skips the seed — so your list keeps its rows (and your
;; edits) across a save. See docs/guide/glossary.md#frame-provider.
;;
;; `app-frame` is just an id we picked. `:rf/default` sounds special but isn't —
;; it's an ordinary frame id with no privileges. The runtime never conjures a
;; frame for you, so we name one here and hand it to the provider like any other.
(def app-frame :rf/default)

(defn run []
  ;; `init!` tells the runtime to render through Reagent — once, for the whole
  ;; process. It picks the substrate; it does not create a frame.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id             app-frame
                                    :initial-events [[:crud/initialise]]}
                 [crud-view]])))
