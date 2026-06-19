(ns edn-inspector.core
  "EDN-INSPECTOR testbed — a queued-step driving surface that exercises
  the Xray edn-inspector THROUGH its primary use case: the Epoch and
  App-db PANELS.

  ## Shape — the shared queued-step RUNNER

  ONE purple `Step` button walks the diff-audit case matrix top to
  bottom while the operator watches how the Xray panels render each
  step. The runner (`runner.core`) is the shared harness; this deck
  supplies a `steps` vector (CODE DATA) + a testid `prefix`. Each step
  DISPATCHES one event that makes ONE clean, meaningful app-db
  transition, and the Xray SIDECAR mounted INLINE on the right
  (`[data-rf-xray-host]`) shows the change via the EPOCH panel
  (db-before / db-after) and the APP-DB panel (the diff). Title:
  `Xray Testbed: edn-inspector`.

  The runner is manual-step only: the operator presses Step (or a per-row
  RUN button) to drive ONE step at a time and reads its `:watch` note
  while the panels render. There is no auto-advance and no timer, and the
  runner does NOT pin Xray focus — the operator focuses each epoch.

  Both panels render their CLJS values THROUGH the edn-inspector
  (`day8.re-frame2-xray.views.edn-inspector`) — the single value renderer
  behind every Xray panel. So the inspector + its DIFF projection are
  demonstrated where they earn their keep: a step writes a stressing shape
  into app-db, and the inspector renders that shape and its diff inside the
  panels.

  ## The matrix

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

  ## Per-step delta — app-db `:step`

  The runner sets app-db `:step = n` on the run-step epoch; that churn is
  the per-step App-db / Epoch delta the panels show. The per-step matrix
  case is the ADDITIONAL change the run-step epoch's child event lands.

  ## Runner cursor = app-db `:step`

  The runner cursor lives in app-db's `:step` slot, written by
  `runner.core`'s run-step event — NOT a Reagent atom. The deck reads as an
  ordinary re-frame2 app: app-db + events + subs.

  ## Inline Xray sidecar (no preload edit)

  Unlike the `_epochs` trio (standard_epochs / routes_epochs / machine_epochs)
  which wire `day8.re-frame2-xray.preload` in shadow-cljs, this deck mounts the
  inline shell from `run` via the public `day8.re-frame2-xray.core/init!` +
  `open!` — the documented manual alternative to the `:preloads` wiring (see
  `core.cljs` §init!). That keeps the inline-host behaviour identical
  (`[data-rf-xray-host]`, the same shell, the same Epoch + App-db panels)
  WITHOUT touching the hot-zone `implementation/shadow-cljs.edn` (the
  `:examples/edn-inspector` build-id already exists there).

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
            ;; Shared testbed-config helper: derives the open-in-editor
            ;; project-root from the build env.
            [re-frame.testbed.config :as testbed-config]
            ;; The shared step-driver runner. This deck supplies a `steps`
            ;; vector + registers `:edn-inspector/run-step` via
            ;; `runner/reg-runner!`; the runner drives the ONE-button series
            ;; + the per-step RUN buttons off app-db `:step`.
            [runner.core :as runner])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; The inspected app frame — only the app frame is Xray-relevant.
;; ============================================================================

(def host-frame :rf/default)

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:step` is the runner cursor — the index of the
;; last-run step, written by `runner.core`'s run-step event; its churn
;; every step IS the per-step App-db / Epoch delta. The other slots are
;; the real, meaningful paths the per-step events write the matrix-case
;; shapes into — the App-db panel renders them (and their diffs) THROUGH
;; the edn-inspector.
;;
;; The seed is chosen so EVERY matrix case has something clean to act on:
;; a map with keys to add/remove/change, sequentials with entries, a set,
;; nested structure for the deep cases, and secure / cache slots for the
;; sentinel cases.

(def initial-db
  {:step       nil
   ;; MAPS — keys to add / remove / change.
   :profile    {:name "Ada" :role :engineer}
   ;; SEQUENTIALS — a vector, a list, a set with entries.
   :queue      [:task-1 :task-2 :task-3]
   :history    '(:login :browse)
   :tags       #{:alpha :beta :gamma}
   ;; SCATTERED removal — a four-element lane the scattered-removal step
   ;; thins to [:a :c] (drops :b@1 + :d@3).
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

(rf/reg-event :edn-inspector/reset
  {:doc "Re-seed app-db to the matrix baseline."}
  (fn handler-reset [_ _ev]
    {:db initial-db}))

;; ============================================================================
;; EVENTS — the step ladder, grouped by the audit matrix
;; ============================================================================
;;
;; The per-step App-db / Epoch delta is the runner's `:step` write on the
;; parent run-step epoch; each event below writes ONLY its matrix-case
;; shape, the ADDITIONAL change the App-db diff renders.

;; -- MAPS --------------------------------------------------------------------

(rf/reg-event :edn-inspector/map-key-added
  {:doc "Map: a KEY ADDED. assoc a new `:team` key under :profile — the
         App-db diff paints `+:team` (green, key-anchored)."}
  (fn [{:keys [db]} _] {:db (assoc-in db [:profile :team] :platform)}))

(rf/reg-event :edn-inspector/map-key-removed
  {:doc "Map: a KEY REMOVED. dissoc `:role` from :profile — the diff paints
         a struck-through `−:role` (red, key-anchored)."}
  (fn [{:keys [db]} _]
    {:db (if (contains? (:profile db) :role)
           (update db :profile dissoc :role)
           (assoc-in db [:profile :role] :engineer))}))

(rf/reg-event :edn-inspector/map-value-changed
  {:doc "Map: a VALUE CHANGED in place. Bump :profile/name — the value-side
         `~` glyph + `← was \"…\"` annotation, key intact."}
  (fn [{:keys [db]} _]
    {:db (update-in db [:profile :name]
               #(if (= % "Ada") "Ada Lovelace" "Ada"))}))

;; -- SEQUENTIALS (vector / list / set) ---------------------------------------

(rf/reg-event :edn-inspector/seq-entry-added
  {:doc "Sequential: ENTRY ADDED. conj a new task onto the :queue vector —
         the appended entry paints `+` green."}
  (fn [{:keys [db]} _]
    {:db (update db :queue conj (keyword (str "task-" (inc (count (:queue db))))))}))

(rf/reg-event :edn-inspector/seq-entry-removed
  {:doc "Sequential: ENTRY REMOVED. pop the tail off the :queue vector — the
         dropped entry renders struck-through (member-level, not a whole-key
         replace). Re-seeds when down to one so there is always a tail."}
  (fn [{:keys [db]} _]
    {:db (if (> (count (:queue db)) 1)
           (update db :queue pop)
           (assoc db :queue [:task-1 :task-2 :task-3]))}))

(rf/reg-event :edn-inspector/seq-scattered-removed
  {:doc "Sequential: SCATTERED / MID-VECTOR removal. Thin :lane
         `[:a :b :c :d]` → `[:a :c]` — drop :b@1 AND :d@3. The genuinely-
         removed :b and :d render struck IN PLACE; the surviving-shifted :c
         (was index 2 → now 1) must NOT be struck and carries a `(was N)`
         suffix. Toggles back to the full lane so the diff alternates."}
  (fn [{:keys [db]} _]
    {:db (if (= (:lane db) [:a :b :c :d])
           (assoc db :lane [:a :c])
           (assoc db :lane [:a :b :c :d]))}))

(rf/reg-event :edn-inspector/set-member-changed
  {:doc "Set: a MEMBER CHANGED (swap). Replace one member of :tags — the
         diff paints member-level `−:alpha +:delta` with the :tags KEY
         INTACT (not a 'sea of red' whole-key strike). Cycles members."}
  (fn [{:keys [db]} _]
    {:db (let [tags (:tags db)]
      (assoc db :tags
             (cond
               (contains? tags :alpha) (-> tags (disj :alpha) (conj :delta))
               (contains? tags :delta) (-> tags (disj :delta) (conj :alpha))
               :else                   #{:alpha :beta :gamma})))}))

;; -- EMPTY vs REMOVAL (the subtle one) ---------------------------------------
;;
;; Each "empty" step drops the single member of a one-member collection so
;; the container goes EMPTY with its KEY INTACT — the inspector renders the
;; now-empty bracket pair with the dropped member struck inside. The
;; "remove key" step dissocs the :doomed key wholesale — a struck-through
;; removed KEY (the collapsed removed-ghost). The two must read DISTINCTLY.

(rf/reg-event :edn-inspector/empty-vector
  {:doc "Empty a VECTOR, key intact. `:one-vec [:only]` → `[]` — the element
         removal renders member-level inside the intact (now-empty) vector,
         NOT a whole-key `~` modify. Re-seeds when empty."}
  (fn [{:keys [db]} _]
    {:db (assoc db :one-vec (if (seq (:one-vec db)) [] [:only]))}))

(rf/reg-event :edn-inspector/empty-list
  {:doc "Empty a LIST, key intact. `:one-list (:only)` → `()` — member-level
         removal inside the intact list. Re-seeds."}
  (fn [{:keys [db]} _]
    {:db (assoc db :one-list (if (seq (:one-list db)) '() '(:only)))}))

(rf/reg-event :edn-inspector/empty-set
  {:doc "Empty a SET, key intact. `:one-set #{:only}` → `#{}` — member-level
         removal inside the intact set. Re-seeds."}
  (fn [{:keys [db]} _]
    {:db (assoc db :one-set (if (seq (:one-set db)) #{} #{:only}))}))

(rf/reg-event :edn-inspector/empty-map
  {:doc "Empty a MAP, key intact. `:one-map {:k 1}` → `{}` — member-level
         removal inside the intact map. Re-seeds."}
  (fn [{:keys [db]} _]
    {:db (assoc db :one-map (if (seq (:one-map db)) {} {:k 1}))}))

(rf/reg-event :edn-inspector/remove-key
  {:doc "Remove a KEY wholesale. dissoc `:doomed` — a struck-through removed
         KEY (collapsed removed-ghost), DISTINCT from emptying a collection
         whose key stays. Re-seeds the key when gone."}
  (fn [{:keys [db]} _]
    {:db (if (contains? db :doomed)
           (dissoc db :doomed)
           (assoc db :doomed {:goodbye true}))}))

;; -- SCALARS -----------------------------------------------------------------

(rf/reg-event :edn-inspector/scalar-number
  {:doc "Scalar: NUMBER changed in place. Increment :scalar — `~` + `← was N`."}
  (fn [{:keys [db]} _] {:db (-> db (update :scalar #(if (number? %) (inc %) 5)))}))

(rf/reg-event :edn-inspector/scalar-nil-toggle
  {:doc "Scalar: NIL ↔ VALUE. Toggle :scalar between a value and nil — both
         transitions are `:modified` leaves (nil is a real value, not absence)."}
  (fn [{:keys [db]} _] {:db (-> db (update :scalar #(if (nil? %) 5 nil)))}))

(rf/reg-event :edn-inspector/scalar-type-flip
  {:doc "Scalar: TYPE CHANGE. Flip :scalar number↔string (`5` ↔ `\"five\"`)
         and scalar↔map — R7 renders `~` + `← was <type>` type-change suffix."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (update :scalar
                (fn [v]
                  (cond
                    (number? v) "five"
                    (string? v) {:was :string}
                    :else       5))))}))

;; -- MULTI-ADJUST (add AND remove in one diff) -------------------------------

(rf/reg-event :edn-inspector/map-multi-adjust
  {:doc "Map: ADD + REMOVE in ONE diff. Swap :flags `{:a 1 :b 2}` ↔
         `{:a 1 :c 3}` — `:b` removed AND `:c` added simultaneously, `:a`
         unchanged."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (update :flags
                (fn [m]
                  (if (contains? m :b) {:a 1 :c 3} {:a 1 :b 2}))))}))

(rf/reg-event :edn-inspector/vector-multi-adjust
  {:doc "Vector: CHANGE + APPEND in ONE diff. Swap :slots `[1 2 3]` ↔
         `[1 9 3 4]` — index 1 modified (2→9) AND index 3 appended (4)."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (update :slots
                (fn [v] (if (= v [1 2 3]) [1 9 3 4] [1 2 3]))))}))

(rf/reg-event :edn-inspector/set-multi-adjust
  {:doc "Set: MULTI-MEMBER swap in ONE diff. Swap :labels `#{:x :y :z}` ↔
         `#{:x :p :q}` — `:y :z` removed AND `:p :q` added, `:x` kept,
         member-level, key intact."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (update :labels
                (fn [s] (if (contains? s :y) #{:x :p :q} #{:x :y :z}))))}))

;; -- DEEP / MIXED ------------------------------------------------------------

(rf/reg-event :edn-inspector/deep-change
  {:doc "Deep: a scalar change FIVE levels deep at `[:deep :a :b :c :d]`.
         Every ancestor reads `:children` (◴ + rail); none promotes to a
         whole-key replace."}
  (fn [{:keys [db]} _] {:db (-> db (update-in [:deep :a :b :c :d] (fnil inc 0)))}))

(rf/reg-event :edn-inspector/mixed-deep-change
  {:doc "Mixed: a SET swap through map→map→vector→set at
         `[:mixed :a :b 0]`. Diffs member-level at the set; no ancestor
         (map or vector) is falsely promoted to a whole-key replace
         (the ancestor-non-promotion property across kinds). Cycles."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (update-in [:mixed :a :b 0]
                   (fn [s] (if (contains? s :x) #{:y} #{:x}))))}))

;; -- SENTINELS ---------------------------------------------------------------

(rf/reg-event :edn-inspector/redacted
  {:doc "Sentinel: :rf/redacted. Write the sentinel three levels deep at
         `[:secure :user :password]` — the inspector renders a `redacted`
         chip, never the raw value."}
  (fn [{:keys [db]} _] {:db (-> db (assoc-in [:secure :user :password] :rf/redacted))}))

(rf/reg-event :edn-inspector/large-elided
  {:doc "Sentinel: :rf.size/large-elided (Spec 015). Write the sentinel at
         `[:cache :report-42]` — routed through the size-chip renderer."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (assoc-in [:cache :report-42]
                  {:rf.size/large-elided {:source        :app-db
                                          :path          [:cache :report-42]
                                          :original-size 12480293
                                          :bytes         12480293
                                          :handle        :report/payload-42
                                          :reason        :over-budget}}))}))

;; -- SHOWCASE (rendering capabilities the diff matrix doesn't exercise) ------

(rf/reg-event :edn-inspector/large-collection
  {:doc "Showcase: LARGE collection → elision. Write a 50-key map at
         `[:cache :grid]` — the App-db inspector elides past its threshold +
         the body scrolls."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (assoc-in [:cache :grid]
                  (into {} (for [i (range 50)]
                             [(keyword (str "metric-" i)) (str "value-" i)]))))}))

(rf/reg-event :edn-inspector/deeply-nested
  {:doc "Showcase: DEEP nesting → path render / collapse. Write a six-level
         nested map at `:cache/tenant` — deep nodes render `▸ {…N keys}`
         summaries past the depth ceiling."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (assoc-in [:cache :tenant]
                  {:acme {:department {:engineering {:team {:platform
                                                            {:project :xray
                                                             :status  :active
                                                             :leads   ["Ada" "Grace"]}}}}}}))}))

(rf/reg-event :edn-inspector/mixed-types
  {:doc "Showcase: MIXED scalar kinds + tagged literals. Write a vector of
         every scalar kind PLUS `#uuid` + `#inst` at `:cache/scalar-mix` —
         every syntax-palette token + the default formatters' compact
         headers."}
  (fn [{:keys [db]} _]
    {:db (-> db
        (assoc-in [:cache :scalar-mix]
                  [nil true false
                   42 -7 3.14159
                   "hello" "with \"escaped\" quotes"
                   :simple :ns/qualified
                   'plain-sym 'my.ns/qualified-sym
                   (random-uuid)
                   (js/Date.)]))}))

;; ============================================================================
;; THE STEP VECTOR — code data (the single source of truth)
;; ============================================================================
;;
;; Each step: {:event [...] :watch "<what to look for>" :label "<short row
;; label>"}. The runner renders :watch per STEP; pressing Step (or a per-row
;; RUN button) dispatches `[:edn-inspector/run-step n]`, which sets app-db
;; `:step = n` (the per-step delta the panels show) and dispatches the
;; step's `:event` into the host-frame. The matrix walks SIMPLE → COMPLEX.
;; These are synchronous db-only transitions — no async cascade, so manual
;; stepping needs no pacing.

(def steps
  [;; -- MAPS --
   {:label "Map: key ADDED"
    :event [:edn-inspector/map-key-added]
    :watch "App-db diff: assoc :profile/:team paints `+:team` (green, key-anchored). Epoch: the db-before/db-after of a single key add."}
   {:label "Map: key REMOVED"
    :event [:edn-inspector/map-key-removed]
    :watch "App-db diff: dissoc :profile/:role paints a struck-through `−:role` (red, key-anchored) — distinct from emptying a collection."}
   {:label "Map: value CHANGED"
    :event [:edn-inspector/map-value-changed]
    :watch "App-db diff: :profile/:name flips in place — the value-side `~` glyph + `← was \"…\"` annotation, the KEY intact."}

   ;; -- SEQUENTIALS --
   {:label "Vector: entry ADDED"
    :event [:edn-inspector/seq-entry-added]
    :watch "App-db diff: a task conj'd onto :queue — the appended entry paints `+` green at the tail."}
   {:label "Vector: entry REMOVED"
    :event [:edn-inspector/seq-entry-removed]
    :watch "App-db diff: :queue tail popped — the dropped entry renders struck-through (member-level, NOT a whole-key replace)."}
   {:label "Vector: SCATTERED removal"
    :event [:edn-inspector/seq-scattered-removed]
    :watch ":lane [:a :b :c :d] → [:a :c]: −:b@1 and −:d@3 render struck IN PLACE; the surviving-shifted :c (was index 2 → 1) is NOT struck and carries a `(was N)` suffix."}
   {:label "Set: member CHANGED"
    :event [:edn-inspector/set-member-changed]
    :watch "App-db diff: one :tags member swapped — member-level `−:alpha +:delta` with the :tags KEY intact (not a sea-of-red whole-key strike)."}

   ;; -- EMPTY vs REMOVAL (the subtle one) --
   {:label "Empty a VECTOR (key intact)"
    :event [:edn-inspector/empty-vector]
    :watch ":one-vec [:only] → []: the element removal renders member-level inside the intact (now-empty) vector, NOT a whole-key `~` modify."}
   {:label "Empty a LIST (key intact)"
    :event [:edn-inspector/empty-list]
    :watch ":one-list (:only) → (): member-level removal inside the intact list — the key stays, the bracket pair goes empty."}
   {:label "Empty a SET (key intact)"
    :event [:edn-inspector/empty-set]
    :watch ":one-set #{:only} → #{}: member-level removal inside the intact set."}
   {:label "Empty a MAP (key intact)"
    :event [:edn-inspector/empty-map]
    :watch ":one-map {:k 1} → {}: member-level removal inside the intact map."}
   {:label "Remove a KEY (wholesale)"
    :event [:edn-inspector/remove-key]
    :watch "dissoc :doomed: a struck-through removed KEY (collapsed removed-ghost) — read it AGAINST the empty-collection steps above; they must look DISTINCT."}

   ;; -- SCALARS --
   {:label "Scalar: NUMBER changed"
    :event [:edn-inspector/scalar-number]
    :watch "App-db diff: :scalar incremented — `~` + `← was N` on a number leaf."}
   {:label "Scalar: nil ↔ value"
    :event [:edn-inspector/scalar-nil-toggle]
    :watch ":scalar toggles value ↔ nil — both transitions are `:modified` leaves (nil is a real value, not absence)."}
   {:label "Scalar: TYPE change"
    :event [:edn-inspector/scalar-type-flip]
    :watch ":scalar flips number↔string↔map — R7 renders `~` + `← was <type>` type-change suffix (scalar→collection promotes without a false whole-key strike)."}

   ;; -- MULTI-ADJUST --
   {:label "Map: add AND remove"
    :event [:edn-inspector/map-multi-adjust]
    :watch ":flags {:a 1 :b 2} ↔ {:a 1 :c 3}: `−:b` removed AND `+:c` added in ONE diff, `:a` unchanged."}
   {:label "Vector: change AND append"
    :event [:edn-inspector/vector-multi-adjust]
    :watch ":slots [1 2 3] ↔ [1 9 3 4]: index 1 modified (`~`@1: 2→9) AND index 3 appended (`+`@3) in ONE diff."}
   {:label "Set: multi-member swap"
    :event [:edn-inspector/set-multi-adjust]
    :watch ":labels #{:x :y :z} ↔ #{:x :p :q}: `−:y −:z +:p +:q` member-level, key intact."}

   ;; -- DEEP / MIXED --
   {:label "Deep scalar change"
    :event [:edn-inspector/deep-change]
    :watch "[:deep :a :b :c :d] bumped FIVE levels deep: every ancestor reads `:children` (◴ + rail); none promotes to a whole-key replace."}
   {:label "Mixed-kind deep swap"
    :event [:edn-inspector/mixed-deep-change]
    :watch "Set swap at [:mixed :a :b 0] through map→map→vector→set: diffs member-level at the set; no ancestor (map or vector) is falsely promoted."}

   ;; -- SENTINELS --
   {:label "Sensitive → :rf/redacted"
    :event [:edn-inspector/redacted]
    :watch "[:secure :user :password] := :rf/redacted: the inspector renders a `redacted` chip, NEVER the raw value."}
   {:label "Large-elided sentinel"
    :event [:edn-inspector/large-elided]
    :watch "[:cache :report-42] := a :rf.size/large-elided sentinel (Spec 015) — routed through the size-chip renderer, not rendered as a literal map."}

   ;; -- SHOWCASE --
   {:label "Large collection → elision"
    :event [:edn-inspector/large-collection]
    :watch "A 50-key map at [:cache :grid]: the App-db inspector elides past its threshold and the body scrolls."}
   {:label "Deeply nested → collapse"
    :event [:edn-inspector/deeply-nested]
    :watch "A six-level nested map at [:cache :tenant]: deep nodes render `▸ {…N keys}` summaries past the depth ceiling."}
   {:label "Mixed types / tagged literals"
    :event [:edn-inspector/mixed-types]
    :watch "A vector of every scalar kind + #uuid + #inst at [:cache :scalar-mix]: every syntax-palette token + the default formatters' compact headers."}])

;; ============================================================================
;; RUNNER WIRING — register the deck's run-step event
;; ============================================================================

(runner/reg-runner! {:id         :edn-inspector/run-step
                     :steps      steps
                     :host-frame host-frame})

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
   [runner/runner {:run-step-event :edn-inspector/run-step
                   :steps          steps
                   :prefix         "edn-inspector"
                   :host-frame     host-frame}]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; The open-in-editor project-root is derived from the build environment,
;; not a hardcoded personal path (mirrors standard_epochs).
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so any source-coord chip a panel
  ;; surfaces resolves its classpath-relative `:file` to an on-disk URI.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from
  ;; absence — register the single, plain host frame, scope the boot
  ;; dispatch, and wrap the render in a `frame-provider-existing` (the
  ;; frame is already created by `reg-frame`; carried invariant).
  (rf/reg-frame host-frame {})
  (rf/with-frame host-frame
    (rf/dispatch-sync [:edn-inspector/reset]))
  (rdc/render react-root [rf/frame-provider-existing {:frame host-frame} [root]])
  ;; Mount the inline Xray sidecar (Epoch + App-db panels) into
  ;; `[data-rf-xray-host]`, standard_epochs-style. `init!` is the public
  ;; manual alternative to the `:preloads` wiring (so no shadow-cljs.edn
  ;; edit); `open!` finds the host, registers the `:rf/xray` frame, and
  ;; renders the shell via the installed Reagent adapter. Called AFTER
  ;; `rf/init!` so the substrate adapter is present.
  (xray/init!)
  (xray/open!))
