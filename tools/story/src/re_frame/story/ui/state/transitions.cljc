(ns re-frame.story.ui.state.transitions
  "Pure shell-state transition fns (data → data). Split from
  `re-frame.story.ui.state` per rf2-gcpon (leaf-size ceiling rf2-zkca8).

  ## What lives here

  Every selection / filter / mode / cell-override / fingerprint /
  panel-visibility transition the shell uses. Every fn is pure
  (state → state, or pure data → data) so the JVM test corpus can
  exercise them without booting Reagent.

  The parent ns `re-frame.story.ui.state` re-exports every Var here,
  so consumer requires keep working unchanged."
  (:require [re-frame.story.registrar :as registrar]))

;; ---- selection / filters -------------------------------------------------

(defn select-variant
  "Set the focused variant id (or nil to deselect)."
  [state variant-id]
  (assoc state :selected-variant variant-id))

(defn select-workspace
  "Set the focused workspace id (or nil to deselect)."
  [state workspace-id]
  (assoc state :selected-workspace workspace-id))

(defn toggle-tag-filter
  "Flip `tag` in the `:tag-filter` set."
  [state tag]
  (update state :tag-filter
          (fn [s] (if (contains? s tag) (disj s tag) (conj (or s #{}) tag)))))

;; ---- mode bookkeeping ----------------------------------------------------

(defn set-active-modes
  "Replace the active modes vector."
  [state modes]
  (assoc state :active-modes (vec modes)))

(defn- mode-axis
  "Resolve `mode-id`'s `:axis` (if any) via the registrar. Pure data →
  data; isolated as a helper so `toggle-mode` is trivially JVM-
  testable (the helper consults the registrar; pass an explicit
  `axis-fn` to bypass it in pure tests)."
  [mode-id]
  (:axis (registrar/handler-meta :mode mode-id)))

(defn toggle-mode
  "Toggle `mode-id` against the current `active-modes` vector. Honors
  `:axis` semantics per spec/010 §Selection semantics — by axis:

  - Currently active → deactivate (regardless of axis).
  - Axis-grouped     → drop siblings sharing the axis, then add.
  - Un-grouped       → multi-select, append.

  Pure data → data; JVM-testable. The `axis-fn` arity injects the
  axis-lookup for tests that don't want a live registrar.

  Returns the new active-modes vector — caller is responsible for
  writing it back via `set-active-modes`."
  ([active-modes mode-id]
   (toggle-mode active-modes mode-id mode-axis))
  ([active-modes mode-id axis-fn]
   (let [active (vec (or active-modes []))]
     (cond
       (some #(= % mode-id) active)
       (vec (remove #(= % mode-id) active))

       (some? (axis-fn mode-id))
       (let [axis     (axis-fn mode-id)
             siblings (set (filter
                             (fn [mid] (= axis (axis-fn mid)))
                             active))]
         (conj (vec (remove siblings active)) mode-id))

       :else
       (conj active mode-id)))))

(defn clear-active-modes
  "Drop every active mode — implements the toolbar's `[reset]` action."
  [state]
  (assoc state :active-modes []))

(defn group-modes-by-axis
  "Build the toolbar's chip layout. Pure data → data; JVM-testable.

  `id->body` is the `{mode-id → mode-body}` map from
  `(registrar/registrations :mode)`. Returns

      {:axes   [[axis [mode-id ...]] ...]  ; sorted alphabetically by axis-name
       :unaxed [mode-id ...]}              ; un-grouped modes, sorted alphabetically

  — an explicit two-slot map (no sentinels). The caller renders the
  axis-tagged groups left-to-right and the un-grouped chips at the
  trailing edge. Within each bucket the ids are sorted alphabetically."
  [id->body]
  (let [axed   (->> id->body
                    (filter (fn [[_ b]] (some? (:axis b))))
                    (group-by (fn [[_ b]] (:axis b)))
                    (map (fn [[axis pairs]]
                           [axis (vec (sort (map first pairs)))]))
                    (sort-by (fn [[axis _]] (str axis)))
                    vec)
        unaxed (->> id->body
                    (filter (fn [[_ b]] (nil? (:axis b))))
                    (map first)
                    sort
                    vec)]
    {:axes axed :unaxed unaxed}))

;; ---- cell overrides ------------------------------------------------------

(defn set-cell-override
  "Set a single arg override for `variant-id`. `path` is a vector
  `[arg-key & sub-path]` — the first element is the top-level arg-key,
  the remaining elements address into the nested value via `assoc-in`
  semantics. Used by the nested Malli walker per rf2-agshe.

  An empty path is a no-op (caller error; the state is returned
  unchanged). For top-level scalar overrides use `set-cell-override-
  scalar`, a thin wrapper that wraps the arg-key in a singleton vector."
  [state variant-id path value]
  (if (seq path)
    (assoc-in state (into [:cell-overrides variant-id] path) value)
    state))

(defn set-cell-override-scalar
  "Set a top-level arg override for `variant-id`. Thin wrapper around
  `set-cell-override` for the scalar case (no nested walk). Equivalent
  to `(set-cell-override state variant-id [arg-key] value)`."
  [state variant-id arg-key value]
  (set-cell-override state variant-id [arg-key] value))

(defn clear-cell-overrides
  "Drop every override for `variant-id`. Also drops any repeater row-id
  bookkeeping under the same variant (rf2-c8kfy) so the next render
  re-syncs from scratch against the resolved entry count.

  Row-id storage is keyed on `[variant-id path]` tuples (one entry per
  repeater path within the variant), so the cleanup walks the row-ids
  map and drops every entry whose first tuple element matches."
  [state variant-id]
  (-> state
      (update :cell-overrides dissoc variant-id)
      (update :rf.story/repeater-row-ids
              (fn [m]
                (if (seq m)
                  (into {}
                        (remove (fn [[[v _path] _ids]]
                                  (= v variant-id)))
                        m)
                  m)))))

;; ---- repeater stable row-ids (rf2-c8kfy) ---------------------------------
;;
;; The controls-panel `repeater-widget` (vector / set) renders one DOM row
;; per entry. Pre-fix it keyed rows positionally (`^{:key i}`), so a
;; mid-list delete shifted every surviving row's key up by one. React's
;; reconciler matched by key + component type and reused the DOM node at
;; each position with the next entry's value — an input that had focus /
;; selection at index i+1 displayed index i's value with the SAME focus
;; state. For `:set`-kind repeaters `vector-coerce` re-sorts on every
;; render, so editing any entry shuffled keys against values and the
;; bug fired on every keystroke. Same focus-leak class as rf2-kgn0c
;; (workspace cells) / rf2-z4fza (causa trace ribbon) / rf2-c56hr
;; (story workspace cell re-init).
;;
;; The fix carries a parallel vector of monotonically-allocated ids
;; alongside the entries vector, keyed by `[variant-id path]`. The
;; renderer keys each row on `(str "r:" id)`; add appends a fresh id,
;; delete drops the id at position i in lockstep with the entry. The
;; counter is a single int held on the shell-state — pure data → data,
;; JVM-testable. The ids are render-internal and never persisted to a
;; variant or args slot.

(defn- next-repeater-id
  "Allocate the next monotonic repeater row id and return
  `[state' id]`. The counter lives on `:rf.story/repeater-id-counter`."
  [state]
  (let [id (or (:rf.story/repeater-id-counter state) 0)]
    [(assoc state :rf.story/repeater-id-counter (inc id)) id]))

(defn- alloc-repeater-ids
  "Allocate `n` fresh repeater row ids. Returns `[state' ids]`."
  [state n]
  (loop [state state ids (transient []) remaining n]
    (if (zero? remaining)
      [state (persistent! ids)]
      (let [[s id] (next-repeater-id state)]
        (recur s (conj! ids id) (dec remaining))))))

(defn ensure-repeater-row-ids
  "Ensure the row-id vector at `[variant-id path]` has exactly `n`
  entries — append fresh ids when short; truncate when long. Used by
  `repeater-widget` on render to keep ids in lockstep with the
  resolved entries vector (e.g. on first render, after `:reset
  overrides`, or when the underlying args change shape).

  Returns the updated state. Pure data → data."
  [state variant-id path n]
  (let [k       [variant-id (vec path)]
        current (get-in state [:rf.story/repeater-row-ids k] [])
        have    (count current)]
    (cond
      (= have n) state
      (< have n) (let [[s ids] (alloc-repeater-ids state (- n have))]
                   (assoc-in s [:rf.story/repeater-row-ids k]
                             (into current ids)))
      :else      (assoc-in state [:rf.story/repeater-row-ids k]
                           (subvec current 0 n)))))

(defn repeater-row-ids
  "Read the row-id vector at `[variant-id path]`. Returns `[]` when
  unset. Pure data → data."
  [state variant-id path]
  (get-in state [:rf.story/repeater-row-ids [variant-id (vec path)]] []))

(defn append-repeater-row-id
  "Allocate a fresh id and append it to the row-id vector at
  `[variant-id path]`. Called when the repeater's `[+]` button adds a
  new entry. Returns the updated state."
  [state variant-id path]
  (let [[s id] (next-repeater-id state)
        k      [variant-id (vec path)]]
    (update-in s [:rf.story/repeater-row-ids k] (fnil conj []) id)))

(defn remove-repeater-row-id
  "Drop the row id at position `i` in the row-id vector at
  `[variant-id path]`. Called when the repeater's `[-]` button deletes
  entry `i`. Returns the updated state. Out-of-range `i` is a no-op."
  [state variant-id path i]
  (let [k       [variant-id (vec path)]
        current (get-in state [:rf.story/repeater-row-ids k] [])]
    (if (and (nat-int? i) (< i (count current)))
      (assoc-in state [:rf.story/repeater-row-ids k]
                (into (subvec current 0 i)
                      (subvec current (inc i))))
      state)))

;; ---- hot-reload + fingerprints + snapshots + panels ---------------------

(defn bump-hot-reload-tick
  "Increment the hot-reload tick — variant components observe this slot
  and re-mount on change. Returns the new state map."
  [state]
  (update state :hot-reload-tick (fnil inc 0)))

(defn record-fingerprints
  "Stamp the current decorator fingerprints for `variant-id`. Stage 4's
  hot-reload trigger reads the previous map and compares against the
  current registry; a mismatch bumps `:hot-reload-tick`."
  [state variant-id fingerprints]
  (assoc-in state [:fingerprints variant-id] fingerprints))

(defn pin-snapshot
  "Record a pinned snapshot label/epoch pair for `variant-id`."
  [state variant-id label epoch-id]
  (update-in state [:pinned-snapshots variant-id]
             (fnil conj [])
             {:label label :epoch-id epoch-id}))

(defn toggle-panel
  "Flip a panel's visibility."
  [state panel-id]
  (update-in state [:panel-visibility panel-id] not))

;; ---- mode-tab (rf2-9hc8) -------------------------------------------------

(def mode-tabs
  "Ordered vector of canonical render-shell mode tabs. Stable id order
  drives the chip strip's left-to-right layout. `:dev` is the canvas
  view (rendered by `re-frame.story.ui.canvas`), `:docs` is the
  read-only AutoDocs-equivalent (rf2-rodx), `:test` is the
  in-canvas aggregated pass/fail view (rf2-qmjo)."
  [:dev :docs :test])

(def mode-tab-labels
  "Human-readable label per mode-tab id. Used by the chip strip."
  {:dev  "Canvas"
   :docs "Docs"
   :test "Tests"})

(def default-mode-tab
  "Default mode-tab when no per-variant selection is recorded. `:dev`
  preserves Story v1's existing behaviour — the variant renders in the
  canvas as soon as it's selected."
  :dev)

(defn valid-mode-tab?
  "Is `tab` one of the canonical mode-tabs?"
  [tab]
  (boolean (some #{tab} mode-tabs)))

(defn active-mode-tab
  "Look up the currently-active mode-tab for `variant-id`. Falls back
  to `default-mode-tab` when no selection is recorded."
  [state variant-id]
  (or (get-in state [:active-mode-tab variant-id])
      default-mode-tab))

(defn set-active-mode-tab
  "Record the active mode-tab for `variant-id`. `tab` MUST be one of
  `mode-tabs`; an unrecognised value is silently ignored so callers
  can't poison the state."
  [state variant-id tab]
  (if (valid-mode-tab? tab)
    (assoc-in state [:active-mode-tab variant-id] tab)
    state))
