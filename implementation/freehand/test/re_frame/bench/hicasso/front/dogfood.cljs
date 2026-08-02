(ns re-frame.bench.hicasso.front.dogfood
  "THE DOGFOOD SCREEN'S SHARED APP AND STATE (rf2-2rtt6.8) — the state
  layer only.

  **There is no screen in this namespace, and mounting one is not this
  bead's work.** HD-014 starts the six-week clock at the first
  Hicasso-arm commit that mounts the dogfood screen; the arms
  (rf2-2rtt6.9 and rf2-2rtt6.10) own that commit. What is here is
  everything the screen will sit on: the app-db shape, the events, the
  subscriptions, and the frame lifecycle. Nothing renders, nothing
  imports React, and no root is created.

  ## Why the state layer is shared rather than written three times

  HD-002 says the dogfood screen is written in **three renderings** — the
  collector surface, the grouped `use-subs` surface, and raw UIx — and
  judged on diff and preference by its authors. That verdict is only
  about the view layer if the three renderings sit on one identical set
  of events and subscriptions. Three hand-written state layers would put
  a second variable into an ergonomics comparison whose whole value is
  that it has one.

  ## The screen, described

  One list and one controlled field, which is validation.md's whole
  specification for it. Concretely: a to-do list with a header count, a
  filter, a per-row toggle and delete, a keyed reorder, and one draft
  field per editable thing.

  ## Where the drafts live, and why that is the interesting part

  HD-009 admits no component-local reactive cell — no `local`, no
  ratom-equivalent, no `useState` for application state. The draft of a
  half-typed to-do is exactly the state a Reagent author would reach for
  `r/atom` to hold, so it is the honest test of the ruling rather than a
  place to quietly make an exception. It lives in app-db under `:drafts`,
  keyed, with **one parametric subscription and one named event serving
  every instance** — which is HD-009's claim that the tax is per-concern
  and not per-instance, stated as code that either is or is not one
  declaration per concern.

  The reset law is kept as HD-019 keeps it: a draft is cleared **by
  explicit caller revision**, never by value equality. `:dogfood/commit`
  and `:dogfood/cancel` say so by name.

  ## The write shapes, which are also the bulk witnesses

  `:dogfood/toggle` is the narrow write — one row's subscription dirties,
  and under a correct index exactly one boundary re-runs.
  `:dogfood/set-filter` is the broad write — the visible-ids
  subscription changes and the list rebuilds. The two are here rather
  than in a bench file because the same pair drives both the dogfood
  screen and the bulk witness rows, and they must be the same events in
  both or the ergonomics screen is not the thing being measured."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The app-db shape
;; ---------------------------------------------------------------------------

(defn seed-db
  "`n` to-dos, no drafts, no filter. The one place the shape is written
  out; every assertion about the shape reads it from here."
  [n]
  {:todos  (into {} (map (fn [i] [i {:id i :title (str "todo " i) :done? false}])) (range n))
   :order  (vec (range n))
   :drafts {}
   :filter :all
   :next-id n})

(def new-draft-key
  "The draft key for the not-yet-created to-do. A to-do being edited uses
  its own id, so one parametric subscription serves both."
  ::new)

;; ---------------------------------------------------------------------------
;; Subscriptions — what a boundary reads
;; ---------------------------------------------------------------------------

(rf/reg-sub :dogfood/filter (fn [db _] (:filter db)))

(rf/reg-sub :dogfood/todo (fn [db [_ id]] (get-in db [:todos id])))

(rf/reg-sub :dogfood/done? (fn [db [_ id]] (get-in db [:todos id :done?])))

(rf/reg-sub :dogfood/draft (fn [db [_ k]] (get-in db [:drafts k] "")))

(rf/reg-sub :dogfood/visible-ids
  (fn [db _]
    (let [f (:filter db)]
      (if (= :all f)
        (:order db)
        (let [want (= :done f)]
          (filterv #(= want (boolean (get-in db [:todos % :done?]))) (:order db)))))))

(rf/reg-sub :dogfood/remaining
  (fn [db _] (count (remove #(get-in db [:todos % :done?]) (:order db)))))

;; ---------------------------------------------------------------------------
;; Events — what an intent dispatches
;; ---------------------------------------------------------------------------

(rf/reg-event :dogfood/seed
  (fn [_ [_ n]] {:db (seed-db n)}))

(rf/reg-event :dogfood/edit-draft
  (fn [{:keys [db]} [_ k v]] {:db (assoc-in db [:drafts k] v)}))

(rf/reg-event :dogfood/cancel
  (fn [{:keys [db]} [_ k]] {:db (update db :drafts dissoc k)}))

(rf/reg-event :dogfood/create
  (fn [{:keys [db]} _]
    (let [title (get-in db [:drafts new-draft-key] "")]
      (if (= "" title)
        {:db db}
        (let [id (:next-id db)]
          {:db (-> db
                   (assoc-in [:todos id] {:id id :title title :done? false})
                   (update :order conj id)
                   (update :drafts dissoc new-draft-key)
                   (assoc :next-id (inc id)))})))))

(rf/reg-event :dogfood/commit
  (fn [{:keys [db]} [_ id]]
    (let [title (get-in db [:drafts id])]
      (if (or (nil? title) (= "" title))
        {:db db}
        {:db (-> db (assoc-in [:todos id :title] title) (update :drafts dissoc id))}))))

(rf/reg-event :dogfood/toggle
  (fn [{:keys [db]} [_ id]] {:db (update-in db [:todos id :done?] not)}))

(rf/reg-event :dogfood/remove
  (fn [{:keys [db]} [_ id]]
    {:db (-> db
             (update :todos dissoc id)
             (update :order (fn [o] (filterv #(not= id %) o)))
             (update :drafts dissoc id))}))

(rf/reg-event :dogfood/set-filter
  (fn [{:keys [db]} [_ f]] {:db (assoc db :filter f)}))

(rf/reg-event :dogfood/move
  (fn [{:keys [db]} [_ id to]]
    (let [without (filterv #(not= id %) (:order db))
          to      (max 0 (min to (count without)))]
      {:db (assoc db :order (into (conj (subvec without 0 to) id) (subvec without to)))})))

;; ---------------------------------------------------------------------------
;; The frame lifecycle
;; ---------------------------------------------------------------------------

(defn make-frame!
  "Create (or idempotently replace) `frame-id`'s frame, seeded with `n`
  to-dos. Runs outside any measured window."
  [frame-id n]
  (rf/make-frame {:id frame-id :initial-events [[:dogfood/seed n]]})
  frame-id)

(defn reseed!
  "Return the frame to its seeded state, so one witness never measures a
  page a previous one already mutated."
  [frame-id n]
  (rf/with-frame frame-id (rf/dispatch-sync [:dogfood/seed n]))
  nil)

;; ---------------------------------------------------------------------------
;; The intents the three renderings all write
;; ---------------------------------------------------------------------------

(defn row-intents
  "The event vectors a to-do row's markup carries, in the one spelling
  all three renderings use. Returned as data so the three renderings
  cannot drift on the events while claiming to differ only on the view."
  [id]
  {:on-click-toggle [:dogfood/toggle id]
   :on-click-remove [:dogfood/remove id]
   :on-input-title  [:dogfood/edit-draft id :re-frame.hicasso/value]
   :on-key-down     {"Enter"  [:dogfood/commit id]
                     "Escape" [:dogfood/cancel id]}})

(defn new-item-intents
  "The same, for the one controlled field at the head of the screen. The
  submit intent carries no `::h/prevent` head, so it takes the position's
  census-weighted default."
  []
  {:on-input    [:dogfood/edit-draft new-draft-key :re-frame.hicasso/value]
   :on-submit   [:dogfood/create]
   :on-key-down {"Enter"  [:dogfood/create]
                 "Escape" [:dogfood/cancel new-draft-key]}})
