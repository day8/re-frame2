(ns re-frame.bench.hicasso.arm2.dogfood-screen
  "THE DOGFOOD SCREEN, RENDERED BY THE PATCH RUNTIME (rf2-2rtt6.10).

  **Mounting this screen starts the six-week clock** (HD-014; K7). The
  operator is on record accepting that, and the clock is never extended
  silently.

  validation.md's specification for the screen is *one list + one
  controlled field + sub reads*. The state it sits on —
  [[re-frame.bench.hicasso.front.dogfood]] — is the shared front half and
  is **read-only to this arm**: every event, every subscription and the
  intent spellings come from there unchanged, so the two arms' dogfood
  screens differ in the view layer and in nothing else. That is the whole
  point of the state layer having been built once.

  ## What this rendering is, and what it is not

  This is the **PATCH runtime's** rendering. HD-002's three renderings —
  the collector surface, the grouped `use-subs` surface, and raw UIx —
  are an *ergonomics* comparison ridden on the comparator spine, and they
  belong to Arm 1. Arm 2's job with this screen is the runtime half: that
  an own renderer can carry a real screen with a controlled field in it,
  that a narrow write re-runs one row, and that a keyed reorder moves
  nodes instead of rebuilding them.

  ## Drafts are not a fallback

  A row shows its title as text and carries its own draft field, bound
  directly to `[:dogfood/draft id]` — there is no
  `(if (= \"\" draft) title draft)` anywhere. That spelling would be a
  reset **by value equality**, which HD-019 rules out: a draft is cleared
  by explicit caller revision (`:dogfood/commit`, `:dogfood/cancel`) and
  never because a value looked empty. Writing the screen the ruled way
  costs one more element per row and removes a class of bug that the
  predecessor's controls work spent a decision on."
  (:require [re-frame.bench.hicasso.arm2.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The row — one boundary, three reads
;; ---------------------------------------------------------------------------

(def row-view
  (rt/view ::row
           (fn [{:keys [id]}]
             (let [todo  (rt/sub [:dogfood/todo id])
                   done? (rt/sub [:dogfood/done? id])
                   draft (rt/sub [:dogfood/draft id])
                   {:keys [on-click-toggle on-click-remove on-input-title on-key-down]}
                   (dogfood/row-intents id)]
               [:li.todo {:class (when done? "done") :data-id id}
                [:input.toggle {:id        (str "toggle-" id)
                                :type      "checkbox"
                                :checked   (boolean done?)
                                :on-change on-click-toggle}]
                [:span.title {:id (str "title-" id)} (:title todo)]
                [:input.draft {:id          (str "draft-" id)
                               :type        "text"
                               :value       draft
                               :placeholder "edit…"
                               :on-input    on-input-title
                               :on-key-down on-key-down}]
                [:button.up {:id (str "up-" id) :on-click [:dogfood/move id 0]} "↑"]
                [:button.remove {:id (str "remove-" id) :on-click on-click-remove} "×"]]))))

;; ---------------------------------------------------------------------------
;; The header — the broad write's boundary
;; ---------------------------------------------------------------------------

(def header-view
  (rt/view ::header
           (fn [_]
             (let [remaining (rt/sub [:dogfood/remaining])
                   filter'   (rt/sub [:dogfood/filter])]
               [:header.head
                [:h1.count {:id "remaining"} (str remaining " left")]
                [:nav.filters
                 (for [f [:all :active :done]]
                   [:button.filter {:key      f
                                    :id       (str "filter-" (name f))
                                    :class    (when (= f filter') "selected")
                                    :on-click [:dogfood/set-filter f]}
                    (name f)])]]))))

;; ---------------------------------------------------------------------------
;; The one controlled field at the head of the screen
;; ---------------------------------------------------------------------------

(def new-item-view
  (rt/view ::new-item
           (fn [_]
             (let [draft (rt/sub [:dogfood/draft dogfood/new-draft-key])
                   {:keys [on-input on-submit on-key-down]} (dogfood/new-item-intents)]
               [:form.new {:on-submit on-submit}
                [:input.new-todo {:id          "new-todo"
                                  :type        "text"
                                  :value       draft
                                  :placeholder "What needs doing?"
                                  :on-input    on-input
                                  :on-key-down on-key-down}]]))))

;; ---------------------------------------------------------------------------
;; The list — the keyed witness
;; ---------------------------------------------------------------------------

(def list-view
  (rt/view ::list
           (fn [_]
             (let [ids (rt/sub [:dogfood/visible-ids])]
               [:ul.todos {:role "list"}
                (for [id ids]
                  [row-view {:key id :id id}])]))))

(def screen-view
  (rt/view ::screen
           (fn [_]
             [:main.dogfood
              [header-view {}]
              [new-item-view {}]
              [list-view {}]])))

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(def frame-id ::dogfood)

(defn mount!
  "Create the frame, seed `n` to-dos, and mount the screen into
  `container`. Returns the teardown.

  **This is the call HD-014 hangs the six-week clock on.**"
  ([container] (mount! container 20))
  ([container n]
   (dogfood/make-frame! frame-id n)
   (rt/mount-root! {:container container :frame frame-id :element [screen-view {}]})))

(defn row-nodes [container] (vec (array-seq (.querySelectorAll container "li.todo"))))

(defn row-ids [container]
  (mapv (fn [n] (js/parseInt (.getAttribute n "data-id") 10)) (row-nodes container)))
