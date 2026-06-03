(ns edn-inspector.core
  "EDN-INSPECTOR testbed (rf2-74u2s → rf2-1niob → rf2-yucxn → rf2-rrqku) —
  a queued-step driving surface that exercises the Xray edn-inspector
  THROUGH its primary use case: the Epoch and App-db PANELS.

  ## Shape (rf2-rrqku — adopt the shared queued-step RUNNER)

  ONE purple `Step` button walks the diff-audit case matrix top to
  bottom while the operator watches how the Xray panels render each
  step. The runner (`runner.core`, the rf2-8pbjr pilot) is the shared
  harness; this deck supplies a `steps` vector (CODE DATA) + a testid
  `prefix`. Each step DISPATCHES one event that makes ONE clean,
  meaningful app-db transition, and the Xray SIDECAR mounted INLINE on
  the right (`[data-rf-xray-host]`) shows the change via the EPOCH panel
  (db-before / db-after) and the APP-DB panel (the diff). Title:
  `Xray Testbed: edn-inspector`.

  Replaces the bespoke numbered-button ladder: same events, same app-db
  transitions, same coverage — but driven by the shared runner so the
  operator presses ONE button and reads each step's per-occurrence
  `:watch` note while the panels render. After each auto-advance dispatch
  the runner pins Xray focus to the latest `:rf/default` epoch.

  Both panels render their CLJS values THROUGH the edn-inspector
  (`day8.re-frame2-xray.views.edn-inspector`) — the single value renderer
  behind every Xray panel. So the inspector + its DIFF projection are
  demonstrated where they earn their keep: a step writes a stressing shape
  into app-db, and the inspector renders that shape and its diff inside the
  panels.

  ## The matrix (rf2-yucxn — senior-dev base-case audit)

  The step vector is organised SIMPLE → COMPLEX along the audit matrix;
  each step drives ONE case, in order:

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

  ## Per-step delta (standard_epochs pattern)

  Every action event bumps a shared `:baseline` counter (the `bump` helper),
  so the App-db / Epoch panels always show a delta on every step; the
  per-step matrix case is the ADDITIONAL change layered on top.

  ## Runner state = LOCAL ATOM (rf2-8pbjr contract)

  The runner cursor / status / pace live in `runner-state` — a LOCAL
  Reagent atom in THIS ns. It is NEVER written to the inspected app-db
  (that would pollute the App-db panel under inspection) and is NOT a
  second frame (only the inspected app frame, `:rf/default`, is
  Xray-relevant).

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
  layers, no anti-pattern demos. Each step writes a clean, correct
  inspector-stressing shape; the `:watch` notes are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; the edn-inspector's
  regression coverage lives in the engine + renderer unit tests
  (`diff/engine_cljs_test`, `views/edn_inspector_cljs_test`) + the
  `panel_gallery` Story gallery (gated by the Xray feature-matrix gate) +
  the substrate contract tests. This deck is the manual LIVE driver of the
  inspector inside the real Epoch + App-db panels. The events / subs / views
  are OWNED here. It is NOT referenced by `feature_matrix/scenarios.cjs`, so
  the step `data-testid`s (`edn-inspector-step-<n>`) are deck-internal."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
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
            [re-frame.testbed.config :as testbed-config]
            ;; The shared queued-step runner (rf2-8pbjr pilot). This deck
            ;; supplies a `steps` vector + a testid prefix; the runner
            ;; drives the ONE-button series.
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; The inspected app frame (rf2-8pbjr — only the app frame is Xray-relevant)
;; ============================================================================

(def host-frame :rf/default)

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:baseline` is the shared counter every step
;; bumps (so App-db / Epoch always show a delta on every step). The other
;; slots are the real, meaningful paths the per-step events write the
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
   ;; SCATTERED removal repro (rf2-vu42n) — a four-element lane the
   ;; scattered-removal step thins to [:a :c] (drops :b@1 + :d@3).
   :lane       [:a :b :c :d]
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
  {:doc "Re-seed app-db to the matrix baseline."}
  (fn handler-reset [_db _ev]
    initial-db))

;; ============================================================================
;; A small shared helper: every action event bumps the baseline counter.
;; ============================================================================
;;
;; Kept as a plain db->db fn (not an interceptor) so the baseline bump is
;; visible inline in each handler body — the App-db / Epoch delta on every
;; step comes from here, and the per-step matrix case is the ADDITIONAL
;; change layered on top (the standard_epochs pattern).

(defn- bump [db] (update db :baseline inc))

;; ============================================================================
;; EVENTS — the step ladder, grouped by the audit matrix
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

(rf/reg-event-db :edn-inspector/seq-scattered-removed
  {:doc "Sequential: SCATTERED / MID-VECTOR removal (rf2-vu42n). Thin :lane
         `[:a :b :c :d]` → `[:a :c]` — drop :b@1 AND :d@3. The genuinely-
         removed :b and :d render struck IN PLACE; the surviving-shifted :c
         (was index 2 → now 1) must NOT be struck and carries a `(was N)`
         suffix. Pre-fix the index-aligning renderer struck :c and dropped
         :b. Toggles back to the full lane so the diff alternates."}
  (fn [db _]
    (let [db (bump db)]
      (if (= (:lane db) [:a :b :c :d])
        (assoc db :lane [:a :c])
        (assoc db :lane [:a :b :c :d])))))

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
;; Each "empty" step drops the single member of a one-member collection so
;; the container goes EMPTY with its KEY INTACT — the inspector renders the
;; now-empty bracket pair with the dropped member struck inside. The
;; "remove key" step dissocs the :doomed key wholesale — a struck-through
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
;; THE STEP VECTOR — code data (rf2-8pbjr: the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :settle-ms N
;;             :label "<short row label>"}. The runner renders :watch per
;; STEP (per-occurrence narration), dispatches :event, focuses the latest
;; :rf/default epoch, then waits :settle-ms before advancing. The matrix
;; walks SIMPLE → COMPLEX; the settle is short (these are synchronous
;; db-only transitions — no async cascade to wait on), just enough for the
;; operator's eye to land on the rendered diff before the next step.

(def steps
  [;; -- MAPS --
   {:label     "Map: key ADDED"
    :event     [:edn-inspector/map-key-added]
    :watch     "App-db diff: assoc :profile/:team paints `+:team` (green, key-anchored). Epoch: the db-before/db-after of a single key add."
    :settle-ms 450}
   {:label     "Map: key REMOVED"
    :event     [:edn-inspector/map-key-removed]
    :watch     "App-db diff: dissoc :profile/:role paints a struck-through `−:role` (red, key-anchored) — distinct from emptying a collection."
    :settle-ms 450}
   {:label     "Map: value CHANGED"
    :event     [:edn-inspector/map-value-changed]
    :watch     "App-db diff: :profile/:name flips in place — the value-side `~` glyph + `← was \"…\"` annotation, the KEY intact."
    :settle-ms 450}

   ;; -- SEQUENTIALS --
   {:label     "Vector: entry ADDED"
    :event     [:edn-inspector/seq-entry-added]
    :watch     "App-db diff: a task conj'd onto :queue — the appended entry paints `+` green at the tail."
    :settle-ms 450}
   {:label     "Vector: entry REMOVED"
    :event     [:edn-inspector/seq-entry-removed]
    :watch     "App-db diff: :queue tail popped — the dropped entry renders struck-through (member-level, NOT a whole-key replace)."
    :settle-ms 450}
   {:label     "Vector: SCATTERED removal"
    :event     [:edn-inspector/seq-scattered-removed]
    :watch     ":lane [:a :b :c :d] → [:a :c]: −:b@1 and −:d@3 render struck IN PLACE; the surviving-shifted :c (was index 2 → 1) is NOT struck and carries a `(was N)` suffix."
    :settle-ms 450}
   {:label     "Set: member CHANGED"
    :event     [:edn-inspector/set-member-changed]
    :watch     "App-db diff: one :tags member swapped — member-level `−:alpha +:delta` with the :tags KEY intact (not a sea-of-red whole-key strike)."
    :settle-ms 450}

   ;; -- EMPTY vs REMOVAL (the subtle one) --
   {:label     "Empty a VECTOR (key intact)"
    :event     [:edn-inspector/empty-vector]
    :watch     ":one-vec [:only] → []: the element removal renders member-level inside the intact (now-empty) vector, NOT a whole-key `~` modify (rf2-yucxn BUG A)."
    :settle-ms 450}
   {:label     "Empty a LIST (key intact)"
    :event     [:edn-inspector/empty-list]
    :watch     ":one-list (:only) → (): member-level removal inside the intact list — the key stays, the bracket pair goes empty."
    :settle-ms 450}
   {:label     "Empty a SET (key intact)"
    :event     [:edn-inspector/empty-set]
    :watch     ":one-set #{:only} → #{}: member-level removal inside the intact set (l0us2 empty-edge expansion)."
    :settle-ms 450}
   {:label     "Empty a MAP (key intact)"
    :event     [:edn-inspector/empty-map]
    :watch     ":one-map {:k 1} → {}: member-level removal inside the intact map (rf2-9d4j8 empty-map expansion)."
    :settle-ms 450}
   {:label     "Remove a KEY (wholesale)"
    :event     [:edn-inspector/remove-key]
    :watch     "dissoc :doomed: a struck-through removed KEY (collapsed removed-ghost) — read it AGAINST the empty-collection steps above; they must look DISTINCT."
    :settle-ms 450}

   ;; -- SCALARS --
   {:label     "Scalar: NUMBER changed"
    :event     [:edn-inspector/scalar-number]
    :watch     "App-db diff: :scalar incremented — `~` + `← was N` on a number leaf."
    :settle-ms 400}
   {:label     "Scalar: nil ↔ value"
    :event     [:edn-inspector/scalar-nil-toggle]
    :watch     ":scalar toggles value ↔ nil — both transitions are `:modified` leaves (nil is a real value, not absence)."
    :settle-ms 400}
   {:label     "Scalar: TYPE change"
    :event     [:edn-inspector/scalar-type-flip]
    :watch     ":scalar flips number↔string↔map — R7 renders `~` + `← was <type>` type-change suffix (scalar→collection promotes without a false whole-key strike)."
    :settle-ms 400}

   ;; -- MULTI-ADJUST --
   {:label     "Map: add AND remove"
    :event     [:edn-inspector/map-multi-adjust]
    :watch     ":flags {:a 1 :b 2} ↔ {:a 1 :c 3}: `−:b` removed AND `+:c` added in ONE diff, `:a` unchanged."
    :settle-ms 450}
   {:label     "Vector: change AND append"
    :event     [:edn-inspector/vector-multi-adjust]
    :watch     ":slots [1 2 3] ↔ [1 9 3 4]: index 1 modified (`~`@1: 2→9) AND index 3 appended (`+`@3) in ONE diff."
    :settle-ms 450}
   {:label     "Set: multi-member swap"
    :event     [:edn-inspector/set-multi-adjust]
    :watch     ":labels #{:x :y :z} ↔ #{:x :p :q}: `−:y −:z +:p +:q` member-level, key intact (rf2-4vp8c)."
    :settle-ms 450}

   ;; -- DEEP / MIXED --
   {:label     "Deep scalar change"
    :event     [:edn-inspector/deep-change]
    :watch     "[:deep :a :b :c :d] bumped FIVE levels deep: every ancestor reads `:children` (◴ + rail); none promotes to a whole-key replace."
    :settle-ms 450}
   {:label     "Mixed-kind deep swap"
    :event     [:edn-inspector/mixed-deep-change]
    :watch     "Set swap at [:mixed :a :b 0] through map→map→vector→set: diffs member-level at the set; no ancestor (map or vector) is falsely promoted (l0us2 across kinds)."
    :settle-ms 450}

   ;; -- SENTINELS --
   {:label     "Sensitive → :rf/redacted"
    :event     [:edn-inspector/redacted]
    :watch     "[:secure :user :password] := :rf/redacted: the inspector renders a `redacted` chip, NEVER the raw value."
    :settle-ms 450}
   {:label     "Large-elided sentinel"
    :event     [:edn-inspector/large-elided]
    :watch     "[:cache :report-42] := a :rf.size/large-elided sentinel (Spec 015) — routed through the size-chip renderer, not rendered as a literal map."
    :settle-ms 450}

   ;; -- SHOWCASE --
   {:label     "Large collection → elision"
    :event     [:edn-inspector/large-collection]
    :watch     "A 50-key map at [:cache :grid]: the App-db inspector elides past its threshold and the body scrolls."
    :settle-ms 500}
   {:label     "Deeply nested → collapse"
    :event     [:edn-inspector/deeply-nested]
    :watch     "A six-level nested map at [:cache :tenant]: deep nodes render `▸ {…N keys}` summaries past the depth ceiling."
    :settle-ms 500}
   {:label     "Mixed types / tagged literals"
    :event     [:edn-inspector/mixed-types]
    :watch     "A vector of every scalar kind + #uuid + #inst at [:cache :scalar-mix]: every syntax-palette token + the default formatters' compact headers."
    :settle-ms 500}])

;; ============================================================================
;; RUNNER STATE — LOCAL ATOM (rf2-8pbjr: not app-db, not a 2nd frame)
;; ============================================================================

(defonce runner-state (r/atom (runner/initial-state)))

;; ============================================================================
;; VIEWS
;; ============================================================================

(reg-view root []
  [:div {:data-testid "edn-inspector-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "820px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:data-testid "edn-inspector-title" :style {:margin 0}}
     "Xray Testbed: edn-inspector"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "This is a test runner for the " [:code "edn-inspector"] " component "
     "used by " [:code "xray"] " to show data diffs. Click " [:strong "Step"]
     " below to move from one test case to another, observing the diffs "
     "shown in xray on the right."]]
   [runner/runner "edn-inspector" runner-state steps host-frame]])

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
