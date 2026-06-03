(ns edn-inspector.core
  "EDN-INSPECTOR testbed (rf2-74u2s → rf2-1niob → rf2-yucxn) — a
  standard-epochs-style driving surface that exercises the Xray edn-inspector
  THROUGH its primary use case: the Epoch and App-db PANELS.

  ## Shape (rework — rf2-yucxn: the full datatype matrix)

  ONE tall column of NUMBERED buttons grouped into the diff-audit case
  matrix, top to bottom — the `standard_epochs` shape. Each button
  DISPATCHES one event that makes ONE clean, meaningful app-db transition,
  and the Xray SIDECAR mounted INLINE on the right (`[data-rf-xray-host]`,
  like `standard_epochs`) shows the change via the EPOCH panel (db-before /
  db-after) and the APP-DB panel (the diff).

  Both panels render their CLJS values THROUGH the edn-inspector
  (`day8.re-frame2-xray.views.edn-inspector`) — the single value renderer
  behind every Xray panel. So the inspector + its DIFF projection are
  demonstrated where they earn their keep: a button writes a stressing shape
  into app-db, and the inspector renders that shape and its diff inside the
  panels.

  ## The matrix (rf2-yucxn — senior-dev base-case audit)

  The deck is organised SIMPLE → COMPLEX along the audit matrix; each
  control drives ONE case. Press the buttons within a section top-to-bottom
  to walk the case ladder. `0. Reset` re-seeds the baseline shape.

    MAPS                — key added · key removed · value changed
    SEQUENTIALS         — vector/list/set: entry added · removed · changed
    EMPTY vs REMOVAL    — a collection emptied (KEY INTACT) vs the KEY
                          removed, for set / vector / list / map — these
                          must render DISTINCTLY (members removed inside an
                          intact, now-empty container vs a struck-through
                          removed key)
    SCALARS             — keyword/string/number/bool/nil/symbol changes;
                          nil↔value; type changes (number→string, R7
                          scalar→collection / map→vector)
    MULTI-ADJUST        — add AND remove in ONE collection in ONE diff
                          (map, vector, set)
    DEEP / MIXED        — change several levels deep through mixed container
                          kinds; ancestors must stay `:children`, never
                          promote to a whole-key replace
    SENTINELS           — :rf/redacted · :rf.size/large-elided
    SHOWCASE            — large collection (elision) · deep nesting (collapse
                          summary) · mixed scalar + tagged-literal vector

  ## Per-press delta (standard_epochs pattern)

  Every action event bumps a shared `:baseline` counter (the `bump` helper),
  so the App-db / Epoch panels always show a delta on every press; the
  per-button matrix case is the ADDITIONAL change layered on top.

  ## Inline Xray sidecar (no preload edit)

  Unlike the `_epochs` trio (standard_epochs / routes_epochs / machine_epochs)
  which wire `day8.re-frame2-xray.preload` in shadow-cljs, this deck mounts the
  inline shell from `run` via the public `day8.re-frame2-xray.core/init!` +
  `open!` — the documented manual alternative to the `:preloads` wiring (see
  `core.cljs` §init!). That keeps the inline-host behaviour identical
  (`[data-rf-xray-host]`, the same shell, the same Epoch + App-db panels)
  WITHOUT touching the hot-zone `implementation/shadow-cljs.edn` (the
  `:examples/edn-inspector` build-id already exists from #2702).

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: no deliberate bugs, no teaching
  layers, no anti-pattern demos. Each button writes a clean, correct
  inspector-stressing shape; captions are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; the edn-inspector's
  regression coverage lives in the engine + renderer unit tests
  (`diff/engine_cljs_test`, `views/edn_inspector_cljs_test`) + the
  `panel_gallery` Story gallery (gated by the Xray feature-matrix gate) +
  the substrate contract tests. This deck is the manual LIVE driver of the
  inspector inside the real Epoch + App-db panels. The events / subs / views
  are OWNED here."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's public mount facade — `init!` wires the four
            ;; foundation side-effects (registry, trace-cb, epoch-cb,
            ;; keybinding) and `open!` mounts the inline shell into
            ;; `[data-rf-xray-host]`. The documented manual alternative to
            ;; the `:preloads` wiring, so no shadow-cljs.edn edit is needed.
            [day8.re-frame2-xray.core :as xray]
            ;; Xray's `configure!` to seed `:project-root` so the Event
            ;; lens 'open' chip resolves a classpath-relative `:file` to
            ;; an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:baseline` is the shared counter every button
;; bumps (so App-db / Epoch always show a delta on every press). The other
;; slots are the real, meaningful paths the per-button events write the
;; matrix-case shapes into — the App-db panel renders them (and their diffs)
;; THROUGH the edn-inspector.
;;
;; The seed is chosen so EVERY matrix case has something clean to act on:
;; a map with keys to add/remove/change, sequentials with entries, a set,
;; nested structure for the deep cases, and secure / cache slots for the
;; sentinel cases.

(def initial-db
  {:baseline   0
   ;; MAPS — keys to add / remove / change.
   :profile    {:name "Ada" :role :engineer}
   ;; SEQUENTIALS — a vector, a list, a set with entries.
   :queue      [:task-1 :task-2 :task-3]
   :history    '(:login :browse)
   :tags       #{:alpha :beta :gamma}
   ;; EMPTY vs REMOVAL — a single-member of each kind to empty (key intact)
   ;; alongside a sibling key to remove wholesale.
   :one-vec    [:only]
   :one-list   '(:only)
   :one-set    #{:only}
   :one-map    {:k 1}
   :doomed     {:goodbye true}
   ;; SCALARS — a leaf of each kind to flip in place.
   :scalar     5
   ;; MULTI-ADJUST — collections to add-and-remove in one diff.
   :flags      {:a 1 :b 2}
   :slots      [1 2 3]
   :labels     #{:x :y :z}
   ;; DEEP / MIXED — nested several levels through mixed kinds.
   :deep       {:a {:b {:c {:d 1}}}}
   :mixed      {:a {:b [#{:x}]}}
   ;; SENTINELS — secure + cache slots.
   :secure     {:user {:id 7}}
   :cache      {}})

(rf/reg-event-db :edn-inspector/reset
  {:doc "Button 0 — re-seed app-db to the matrix baseline."}
  (fn handler-reset [_db _ev]
    initial-db))

;; ============================================================================
;; A small shared helper: every action event bumps the baseline counter.
;; ============================================================================
;;
;; Kept as a plain db->db fn (not an interceptor) so the baseline bump is
;; visible inline in each handler body — the App-db / Epoch delta on every
;; press comes from here, and the per-button matrix case is the ADDITIONAL
;; change layered on top (the standard_epochs pattern).

(defn- bump [db] (update db :baseline inc))

;; ============================================================================
;; EVENTS — the button ladder, grouped by the audit matrix
;; ============================================================================

;; -- MAPS --------------------------------------------------------------------

(rf/reg-event-db :edn-inspector/map-key-added
  {:doc "Map: a KEY ADDED. assoc a new `:team` key under :profile — the
         App-db diff paints `+:team` (green, key-anchored)."}
  (fn [db _] (-> db bump (assoc-in [:profile :team] :platform))))

(rf/reg-event-db :edn-inspector/map-key-removed
  {:doc "Map: a KEY REMOVED. dissoc `:role` from :profile — the diff paints
         a struck-through `−:role` (red, key-anchored)."}
  (fn [db _]
    (let [db (bump db)]
      (if (contains? (:profile db) :role)
        (update db :profile dissoc :role)
        (assoc-in db [:profile :role] :engineer)))))

(rf/reg-event-db :edn-inspector/map-value-changed
  {:doc "Map: a VALUE CHANGED in place. Bump :profile/name — the value-side
         `~` glyph + `← was \"…\"` annotation, key intact."}
  (fn [db _]
    (-> db bump
        (update-in [:profile :name]
                   #(if (= % "Ada") "Ada Lovelace" "Ada")))))

;; -- SEQUENTIALS (vector / list / set) ---------------------------------------

(rf/reg-event-db :edn-inspector/seq-entry-added
  {:doc "Sequential: ENTRY ADDED. conj a new task onto the :queue vector —
         the appended entry paints `+` green."}
  (fn [db _]
    (-> db bump
        (update :queue conj (keyword (str "task-" (inc (count (:queue db)))))))))

(rf/reg-event-db :edn-inspector/seq-entry-removed
  {:doc "Sequential: ENTRY REMOVED. pop the tail off the :queue vector — the
         dropped entry renders struck-through (member-level, not a whole-key
         replace). Re-seeds when down to one so there is always a tail."}
  (fn [db _]
    (let [db (bump db)]
      (if (> (count (:queue db)) 1)
        (update db :queue pop)
        (assoc db :queue [:task-1 :task-2 :task-3])))))

(rf/reg-event-db :edn-inspector/set-member-changed
  {:doc "Set: a MEMBER CHANGED (swap). Replace one member of :tags — the
         diff paints member-level `−:alpha +:delta` with the :tags KEY
         INTACT (not a 'sea of red' whole-key strike). Cycles members."}
  (fn [db _]
    (let [db   (bump db)
          tags (:tags db)]
      (assoc db :tags
             (cond
               (contains? tags :alpha) (-> tags (disj :alpha) (conj :delta))
               (contains? tags :delta) (-> tags (disj :delta) (conj :alpha))
               :else                   #{:alpha :beta :gamma})))))

;; -- EMPTY vs REMOVAL (the subtle one) ---------------------------------------
;;
;; Each "empty" button drops the single member of a one-member collection so
;; the container goes EMPTY with its KEY INTACT — the inspector renders the
;; now-empty bracket pair with the dropped member struck inside. The
;; "remove key" button dissocs the :doomed key wholesale — a struck-through
;; removed KEY (the collapsed removed-ghost). The two must read DISTINCTLY.

(rf/reg-event-db :edn-inspector/empty-vector
  {:doc "Empty a VECTOR, key intact. `:one-vec [:only]` → `[]` — the element
         removal renders member-level inside the intact (now-empty) vector,
         NOT a whole-key `~` modify (rf2-yucxn BUG A). Re-seeds when empty."}
  (fn [db _]
    (let [db (bump db)]
      (assoc db :one-vec (if (seq (:one-vec db)) [] [:only])))))

(rf/reg-event-db :edn-inspector/empty-list
  {:doc "Empty a LIST, key intact. `:one-list (:only)` → `()` — member-level
         removal inside the intact list (rf2-yucxn BUG A). Re-seeds."}
  (fn [db _]
    (let [db (bump db)]
      (assoc db :one-list (if (seq (:one-list db)) '() '(:only))))))

(rf/reg-event-db :edn-inspector/empty-set
  {:doc "Empty a SET, key intact. `:one-set #{:only}` → `#{}` — member-level
         removal inside the intact set (l0us2 empty-edge expansion). Re-seeds."}
  (fn [db _]
    (let [db (bump db)]
      (assoc db :one-set (if (seq (:one-set db)) #{} #{:only})))))

(rf/reg-event-db :edn-inspector/empty-map
  {:doc "Empty a MAP, key intact. `:one-map {:k 1}` → `{}` — member-level
         removal inside the intact map (rf2-9d4j8 empty-map expansion).
         Re-seeds."}
  (fn [db _]
    (let [db (bump db)]
      (assoc db :one-map (if (seq (:one-map db)) {} {:k 1})))))

(rf/reg-event-db :edn-inspector/remove-key
  {:doc "Remove a KEY wholesale. dissoc `:doomed` — a struck-through removed
         KEY (collapsed removed-ghost), DISTINCT from emptying a collection
         whose key stays. Re-seeds the key when gone."}
  (fn [db _]
    (let [db (bump db)]
      (if (contains? db :doomed)
        (dissoc db :doomed)
        (assoc db :doomed {:goodbye true})))))

;; -- SCALARS -----------------------------------------------------------------

(rf/reg-event-db :edn-inspector/scalar-number
  {:doc "Scalar: NUMBER changed in place. Increment :scalar — `~` + `← was N`."}
  (fn [db _] (-> db bump (update :scalar #(if (number? %) (inc %) 5)))))

(rf/reg-event-db :edn-inspector/scalar-nil-toggle
  {:doc "Scalar: NIL ↔ VALUE. Toggle :scalar between a value and nil — both
         transitions are `:modified` leaves (nil is a real value, not absence)."}
  (fn [db _] (-> db bump (update :scalar #(if (nil? %) 5 nil)))))

(rf/reg-event-db :edn-inspector/scalar-type-flip
  {:doc "Scalar: TYPE CHANGE. Flip :scalar number↔string (`5` ↔ `\"five\"`)
         and scalar↔map — R7 renders `~` + `← was <type>` type-change suffix."}
  (fn [db _]
    (-> db bump
        (update :scalar
                (fn [v]
                  (cond
                    (number? v) "five"
                    (string? v) {:was :string}
                    :else       5))))))

;; -- MULTI-ADJUST (add AND remove in one diff) -------------------------------

(rf/reg-event-db :edn-inspector/map-multi-adjust
  {:doc "Map: ADD + REMOVE in ONE diff. Swap :flags `{:a 1 :b 2}` ↔
         `{:a 1 :c 3}` — `:b` removed AND `:c` added simultaneously, `:a`
         unchanged."}
  (fn [db _]
    (-> db bump
        (update :flags
                (fn [m]
                  (if (contains? m :b) {:a 1 :c 3} {:a 1 :b 2}))))))

(rf/reg-event-db :edn-inspector/vector-multi-adjust
  {:doc "Vector: CHANGE + APPEND in ONE diff. Swap :slots `[1 2 3]` ↔
         `[1 9 3 4]` — index 1 modified (2→9) AND index 3 appended (4)."}
  (fn [db _]
    (-> db bump
        (update :slots
                (fn [v] (if (= v [1 2 3]) [1 9 3 4] [1 2 3]))))))

(rf/reg-event-db :edn-inspector/set-multi-adjust
  {:doc "Set: MULTI-MEMBER swap in ONE diff. Swap :labels `#{:x :y :z}` ↔
         `#{:x :p :q}` — `:y :z` removed AND `:p :q` added, `:x` kept,
         member-level, key intact (rf2-4vp8c)."}
  (fn [db _]
    (-> db bump
        (update :labels
                (fn [s] (if (contains? s :y) #{:x :p :q} #{:x :y :z}))))))

;; -- DEEP / MIXED ------------------------------------------------------------

(rf/reg-event-db :edn-inspector/deep-change
  {:doc "Deep: a scalar change FIVE levels deep at `[:deep :a :b :c :d]`.
         Every ancestor reads `:children` (◴ + rail); none promotes to a
         whole-key replace."}
  (fn [db _] (-> db bump (update-in [:deep :a :b :c :d] (fnil inc 0)))))

(rf/reg-event-db :edn-inspector/mixed-deep-change
  {:doc "Mixed: a SET swap through map→map→vector→set at
         `[:mixed :a :b 0]`. Diffs member-level at the set; no ancestor
         (map or vector) is falsely promoted to a whole-key replace
         (the l0us2 ancestor-non-promotion property across kinds). Cycles."}
  (fn [db _]
    (-> db bump
        (update-in [:mixed :a :b 0]
                   (fn [s] (if (contains? s :x) #{:y} #{:x}))))))

;; -- SENTINELS ---------------------------------------------------------------

(rf/reg-event-db :edn-inspector/redacted
  {:doc "Sentinel: :rf/redacted. Write the sentinel three levels deep at
         `[:secure :user :password]` — the inspector renders a `redacted`
         chip, never the raw value."}
  (fn [db _] (-> db bump (assoc-in [:secure :user :password] :rf/redacted))))

(rf/reg-event-db :edn-inspector/large-elided
  {:doc "Sentinel: :rf.size/large-elided (Spec 015). Write the sentinel at
         `[:cache :report-42]` — routed through the size-chip renderer."}
  (fn [db _]
    (-> db bump
        (assoc-in [:cache :report-42]
                  {:rf.size/large-elided {:source        :app-db
                                          :path          [:cache :report-42]
                                          :original-size 12480293
                                          :bytes         12480293
                                          :handle        :report/payload-42
                                          :reason        :over-budget}}))))

;; -- SHOWCASE (rendering capabilities the diff matrix doesn't exercise) ------

(rf/reg-event-db :edn-inspector/large-collection
  {:doc "Showcase: LARGE collection → elision. Write a 50-key map at
         `[:cache :grid]` — the App-db inspector elides past its threshold +
         the body scrolls."}
  (fn [db _]
    (-> db bump
        (assoc-in [:cache :grid]
                  (into {} (for [i (range 50)]
                             [(keyword (str "metric-" i)) (str "value-" i)]))))))

(rf/reg-event-db :edn-inspector/deeply-nested
  {:doc "Showcase: DEEP nesting → path render / collapse. Write a six-level
         nested map at `:cache/tenant` — deep nodes render `▸ {…N keys}`
         summaries past the depth ceiling."}
  (fn [db _]
    (-> db bump
        (assoc-in [:cache :tenant]
                  {:acme {:department {:engineering {:team {:platform
                                                            {:project :xray
                                                             :status  :active
                                                             :leads   ["Ada" "Grace"]}}}}}}))))

(rf/reg-event-db :edn-inspector/mixed-types
  {:doc "Showcase: MIXED scalar kinds + tagged literals. Write a vector of
         every scalar kind PLUS `#uuid` + `#inst` at `:cache/scalar-mix` —
         every syntax-palette token + the default formatters' compact
         headers."}
  (fn [db _]
    (-> db bump
        (assoc-in [:cache :scalar-mix]
                  [nil true false
                   42 -7 3.14159
                   "hello" "with \"escaped\" quotes"
                   :simple :ns/qualified
                   'plain-sym 'my.ns/qualified-sym
                   (random-uuid)
                   (js/Date.)]))))

;; ============================================================================
;; SUBSCRIPTIONS — the on-page mirror of the baseline counter
;; ============================================================================

(rf/reg-sub :edn-inspector/baseline (fn [db _] (:baseline db)))

;; ============================================================================
;; THE BUTTON LADDER — grouped by the audit matrix
;; ============================================================================

(def ^:private ladder
  "The ordered button ladder. Each row: [n label caption event]. `event` is
  the dispatch vector; `:section` rows are matrix-group separators."
  [[:section "Maps — key add / remove / value change"]
   ["1a" "Map: key ADDED"   "assoc :profile/:team — `+:team`, key-anchored"
    [:edn-inspector/map-key-added]]
   ["1b" "Map: key REMOVED" "dissoc :profile/:role — struck `−:role`"
    [:edn-inspector/map-key-removed]]
   ["1c" "Map: value CHANGED" "bump :profile/:name — `~` + `← was \"…\"`"
    [:edn-inspector/map-value-changed]]

   [:section "Sequentials — vector / list / set entry"]
   ["2a" "Vector: entry ADDED"   "conj a task onto :queue — `+` green"
    [:edn-inspector/seq-entry-added]]
   ["2b" "Vector: entry REMOVED" "pop :queue tail — struck member, key intact"
    [:edn-inspector/seq-entry-removed]]
   ["2c" "Set: member CHANGED"   "swap a :tags member — `−:alpha +:delta`, key intact"
    [:edn-inspector/set-member-changed]]

   [:section "Empty-result vs key-removal (must read DISTINCTLY)"]
   ["3a" "Empty a VECTOR (key intact)" ":one-vec [:only] → [] — member removal, key stays"
    [:edn-inspector/empty-vector]]
   ["3b" "Empty a LIST (key intact)"   ":one-list (:only) → () — member removal, key stays"
    [:edn-inspector/empty-list]]
   ["3c" "Empty a SET (key intact)"    ":one-set #{:only} → #{} — member removal, key stays"
    [:edn-inspector/empty-set]]
   ["3d" "Empty a MAP (key intact)"    ":one-map {:k 1} → {} — member removal, key stays"
    [:edn-inspector/empty-map]]
   ["3e" "Remove a KEY (wholesale)"    "dissoc :doomed — struck removed KEY (ghost), distinct from emptying"
    [:edn-inspector/remove-key]]

   [:section "Scalars — value / nil / type change"]
   ["4a" "Scalar: NUMBER changed" "increment :scalar — `~` + `← was N`"
    [:edn-inspector/scalar-number]]
   ["4b" "Scalar: nil ↔ value"    "toggle :scalar value↔nil — both `:modified`"
    [:edn-inspector/scalar-nil-toggle]]
   ["4c" "Scalar: TYPE change"    "flip :scalar number↔string↔map — R7 `← was <type>`"
    [:edn-inspector/scalar-type-flip]]

   [:section "Multiple adjustments in ONE diff"]
   ["5a" "Map: add AND remove"    "swap :flags {:a 1 :b 2}↔{:a 1 :c 3} — `−:b +:c`"
    [:edn-inspector/map-multi-adjust]]
   ["5b" "Vector: change AND append" "swap :slots [1 2 3]↔[1 9 3 4] — `~`@1 + `+`@3"
    [:edn-inspector/vector-multi-adjust]]
   ["5c" "Set: multi-member swap"  "swap :labels #{:x :y :z}↔#{:x :p :q} — `−:y −:z +:p +:q`"
    [:edn-inspector/set-multi-adjust]]

   [:section "Deep hierarchy / mixed-type nesting"]
   ["6a" "Deep scalar change"     "bump [:deep :a :b :c :d] — ancestors `:children`, none promoted"
    [:edn-inspector/deep-change]]
   ["6b" "Mixed-kind deep swap"   "swap set at [:mixed :a :b 0] (map→map→vec→set) — no ancestor promotion"
    [:edn-inspector/mixed-deep-change]]

   [:section "Sentinels — redaction / size-elision"]
   ["7a" "Sensitive → :rf/redacted" "assoc [:secure :user :password] :rf/redacted — `redacted` chip"
    [:edn-inspector/redacted]]
   ["7b" "Large-elided sentinel"    "assoc [:cache :report-42] a :rf.size/large-elided sentinel"
    [:edn-inspector/large-elided]]

   [:section "Showcase — elision / collapse / tagged literals"]
   ["8a" "Large collection → elision" "50-key map at [:cache :grid] — elides past threshold"
    [:edn-inspector/large-collection]]
   ["8b" "Deeply nested → collapse"   "six-level nested map at [:cache :tenant] — `▸ {…N keys}` summaries"
    [:edn-inspector/deeply-nested]]
   ["8c" "Mixed types / tagged literals" "scalar-kind vector + #uuid + #inst at [:cache :scalar-mix]"
    [:edn-inspector/mixed-types]]])

(defn- testid-for [event]
  (-> (first event) name (str "-button")))

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on the
  right. Pressing it dispatches the row's event — a REAL app-db change the
  Epoch + App-db panels render through the inspector."
  [n label caption event]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (testid-for event)
             :on-click    #(dispatch event)
             :style {:min-width "20em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view section-heading
  "A matrix-group separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view root []
  [:div {:data-testid "edn-inspector-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "820px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "edn-inspector — diff case matrix"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     [:code "edn-inspector"] " is the value renderer used inside "
     [:code "xray"] " panels like " [:code "Epoch"] " and " [:code "app-db"]
     ". This deck walks its DIFF projection across the full datatype matrix."]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "Press the buttons top-to-bottom within each section. Each makes ONE "
     [:code "app-db"] " change; the Epoch + App-db panels on the right "
     "render the before/after + diff through the inspector."]]
   ;; Button 0 — Reset.
   [:button {:data-testid "edn-inspector-reset"
             :on-click #(dispatch [:edn-inspector/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — re-seed the matrix baseline"]
   ;; The ladder.
   (for [row ladder]
     (if (= :section (first row))
       ^{:key (second row)} [section-heading (second row)]
       (let [[n label caption event] row]
         ^{:key n} [ladder-button n label caption event])))])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; rf2-5dphw — open-in-editor project-root is derived from the build
;; environment, not a hardcoded personal path (mirrors standard_epochs).
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so any source-coord chip a panel
  ;; surfaces resolves its classpath-relative `:file` to an on-disk URI.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Seed app-db on the single, plain default frame — no URL machinery.
  (rf/dispatch-sync [:edn-inspector/reset])
  (rdc/render react-root [root])
  ;; Mount the inline Xray sidecar (Epoch + App-db panels) into
  ;; `[data-rf-xray-host]`, standard_epochs-style. `init!` is the public
  ;; manual alternative to the `:preloads` wiring (so no shadow-cljs.edn
  ;; edit); `open!` finds the host, registers the `:rf/xray` frame, and
  ;; renders the shell via the installed Reagent adapter. Called AFTER
  ;; `rf/init!` so the substrate adapter is present.
  (xray/init!)
  (xray/open!))
