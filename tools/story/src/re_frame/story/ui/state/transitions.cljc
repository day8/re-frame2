(ns re-frame.story.ui.state.transitions
  "Pure shell-state transition fns (data → data). Split from
  `re-frame.story.ui.state` to honor the leaf-size ceiling.

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
  "Set the focused variant id (or nil to deselect). Clears
  `:selected-story` — variant focus is mutually exclusive with the
  story-rollup view."
  [state variant-id]
  (-> state
      (assoc :selected-variant variant-id)
      (cond-> variant-id (assoc :selected-story nil))))

(defn select-workspace
  "Set the focused workspace id (or nil to deselect). Clears
  `:selected-story` — workspace focus and the story-rollup view are
  mutually exclusive shell modes."
  [state workspace-id]
  (-> state
      (assoc :selected-workspace workspace-id)
      (cond-> workspace-id (assoc :selected-story nil))))

(defn select-story
  "Set the focused parent-story id (or nil to deselect). Mutually
  exclusive with `:selected-variant` + `:selected-workspace` — clicking
  a story HEADER swaps the main pane to the rollup docs view."
  [state story-id]
  (-> state
      (assoc :selected-story story-id)
      (cond-> story-id (-> (assoc :selected-variant   nil)
                           (assoc :selected-workspace nil)))))

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

(defn- vivify-for-key
  "Coerce `v` into a collection `next-key` can address into (rf2-mzfh9c).
  An integer `next-key` means the nested Malli walker is addressing a
  `:vector`/`:set`/`:tuple` repeater ENTRY, so `v` needs to be indexable:
  `nil` (no override established yet) becomes `[]`; a `set` becomes a
  STABLE vector via the same `(sort-by str …)` projection
  `ui/controls.cljs`'s `vector-coerce` uses for rendering, so index `i`
  addresses the exact entry the panel is currently showing at row `i`.
  A non-integer `next-key` means a `:map` group, so `nil` becomes `{}`.
  Anything already the right shape (a vector already, a map already)
  passes through unchanged — this is a VIVIFY step, not a re-normalise."
  [v next-key]
  (if (int? next-key)
    (cond
      (nil? v) []
      (set? v) (vec (sort-by str v))
      :else    v)
    (if (nil? v) {} v)))

(defn- assoc-in-kind-aware
  "Like `assoc-in`, but vivifies a MISSING or wrong-shaped intermediate
  value into the collection kind the NEXT path segment actually needs
  (`vivify-for-key`) instead of letting plain `assoc-in` mint an
  int-keyed MAP for a missing vector/set, or throwing when it tries to
  `assoc` a set by index (rf2-mzfh9c). When `coll` (or any intermediate
  value walked along `path`) was a `set`, the walked result at that
  level is coerced BACK into a set before returning — the vector
  projection `vivify-for-key` uses is an addressing convenience, not a
  change of the arg's declared collection kind."
  [coll path value]
  (if (empty? path)
    value
    (let [[k & more] path
          was-set?   (set? coll)
          coll'      (vivify-for-key coll k)
          updated    (assoc coll' k (assoc-in-kind-aware (get coll' k) more value))]
      (if was-set? (set updated) updated))))

(defn set-cell-override
  "Set a single arg override for `variant-id`. `path` is a vector
  `[arg-key & sub-path]` — the first element is the top-level arg-key,
  the remaining elements address into the nested value. Used by the
  nested Malli walker.

  A non-empty `sub-path` walks `assoc-in-kind-aware` against the
  ARG-KEY's current override (or `base` when no override exists yet)
  rather than raw `assoc-in` against `state` — the collection at
  `[:cell-overrides variant-id arg-key]` may be absent (no override
  established yet) or, for a `:set`-kind repeater, a real `set`; plain
  `assoc-in` would either mint an int-keyed MAP for the absent case or
  throw trying to `assoc` a set by index (rf2-mzfh9c). `base` — typically
  the arg's current resolved value (the SAME 'saved' value the controls
  panel already computes for its diff-from-saved affordance,
  active-modes applied, cell-overrides excluded) — seeds the walk so an
  edit to ONE entry of a not-yet-overridden `:vector`/`:set` preserves
  every sibling entry instead of silently truncating to a singleton.

  An empty path is a no-op (caller error; the state is returned
  unchanged). For top-level scalar overrides use `set-cell-override-
  scalar`, a thin wrapper that wraps the arg-key in a singleton vector."
  ([state variant-id path value] (set-cell-override state variant-id path value nil))
  ([state variant-id path value base]
   (if (seq path)
     (let [top-key  (first path)
           sub-path (vec (rest path))]
       (if (empty? sub-path)
         (assoc-in state [:cell-overrides variant-id top-key] value)
         (assoc-in state [:cell-overrides variant-id top-key]
                   (assoc-in-kind-aware
                     (get-in state [:cell-overrides variant-id top-key] base)
                     sub-path value))))
     state)))

(defn set-cell-override-scalar
  "Set a top-level arg override for `variant-id`. Thin wrapper around
  `set-cell-override` for the scalar case (no nested walk). Equivalent
  to `(set-cell-override state variant-id [arg-key] value)`."
  [state variant-id arg-key value]
  (set-cell-override state variant-id [arg-key] value))

(defn clear-cell-overrides
  "Drop every override for `variant-id`. Also drops any repeater row-id
  bookkeeping under the same variant so the next render
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

(defn clear-cell-override
  "Drop the override for a single top-level `arg-key` under `variant-id`,
  reverting that one arg to its saved (declared) value while leaving
  every other override intact. Backs the controls panel's per-arg
  'reset' affordance.

  Also drops any repeater row-id bookkeeping anchored on a path whose
  head is `arg-key` so a reset collection re-syncs its row
  ids from scratch against the reverted entry count. Other args' row
  ids are untouched. When the last override for the variant is cleared
  the empty `:cell-overrides` entry is pruned so callers reading
  `(seq (get-in state [:cell-overrides variant-id]))` observe the same
  'no overrides' shape `clear-cell-overrides` leaves."
  [state variant-id arg-key]
  (-> state
      (update-in [:cell-overrides variant-id] dissoc arg-key)
      (update :cell-overrides
              (fn [m]
                (if (empty? (get m variant-id))
                  (dissoc m variant-id)
                  m)))
      (update :rf.story/repeater-row-ids
              (fn [m]
                (if (seq m)
                  (into {}
                        (remove (fn [[[v path] _ids]]
                                  (and (= v variant-id)
                                       (= arg-key (first path)))))
                        m)
                  m)))))

;; ---- repeater stable row-ids ---------------------------------------------
;;
;; The controls-panel `repeater-widget` (vector / set) renders one DOM row
;; per entry, keyed on a stable monotonic id rather than the positional
;; index. Position-keyed rows leak focus / selection on a mid-list delete:
;; React's reconciler matches by key + component type and reuses the DOM
;; node at each position with the next entry's value, so an input that had
;; focus at index i+1 would display index i's value with the SAME focus
;; state. For `:set`-kind repeaters `vector-coerce` re-sorts on every
;; render, so editing any entry would shuffle keys against values on every
;; keystroke.
;;
;; A parallel vector of monotonically-allocated ids rides alongside the
;; entries vector, keyed by `[variant-id path]`. The renderer keys each row
;; on `(str "r:" id)`; add appends a fresh id, delete drops the id at
;; position i in lockstep with the entry. The counter is a single int held
;; on the shell-state — pure data → data, JVM-testable. The ids are
;; render-internal and never persisted to a variant or args slot.

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

;; ---- Xray-embed collapse -------------------------------------------------
;;
;; Lazy Xray-diff mounting (spec/018 §10): the RHS Xray embed defers its
;; panel MOUNT — and therefore the expensive diff compute the panel runs
;; (app-db structural diff, epoch timeline) — until the embed is expanded.
;; The slot defaults to expanded (false) so the out-of-the-box RHS still
;; paints the panel; the user can collapse the band to halt compute when
;; they're not inspecting. Collapsing unmounts the panel-host component,
;; which releases the Xray React root via the existing microtask path —
;; no duplicate teardown lives here.

(defn xray-embed-collapsed?
  "Whether the RHS Xray embed is collapsed (panel mount + diff compute
  deferred). Defaults to false (expanded) when the slot is unset. Pure
  data → data; JVM-testable."
  [state]
  (boolean (:xray-embed-collapsed? state)))

(defn toggle-xray-embed-collapsed
  "Flip the RHS Xray embed's collapsed state. A nil/unset slot reads as
  expanded (false), so the first toggle collapses. Pure data → data."
  [state]
  (assoc state :xray-embed-collapsed? (not (xray-embed-collapsed? state))))

(defn set-xray-embed-collapsed
  "Set the RHS Xray embed's collapsed state to `value`. Pure data → data."
  [state value]
  (assoc state :xray-embed-collapsed? (boolean value)))

;; ---- chrome visibility ---------------------------------------------------

(def chrome-visibility-defaults
  "Canonical default shape for the `:chrome-visibility` slot. Used by
  state hydration + tests so a missing slot reads the same as a
  full-defaults map.

  - `:full-screen?` is the `f`-key toggle: true → hide sidebar + RHS +
    toolbar; canvas fills the viewport.
  - `:sidebar?` / `:rhs?` / `:toolbar?` are per-panel toggles
    (`s` / `a` / `t` keys). Each defaults to true.
  - `:embed?` is hydrated from `?embed=1`. When true the
    shell renders canvas-only — overrides every individual pane toggle.
    Stateless / non-persisted (embeds are stateless by intent)."
  {:full-screen? false
   :sidebar?     true
   :rhs?         true
   :toolbar?     true
   :embed?       false})

(defn chrome-visibility
  "Read the chrome-visibility map from `state`, merged over the default
  shape so a fresh state (or one persisted before this slot existed)
  still returns a well-formed map. Pure data → data."
  [state]
  (merge chrome-visibility-defaults (:chrome-visibility state)))

(defn toggle-chrome-visibility
  "Flip the boolean at `slot` (one of `:full-screen?` / `:sidebar?` /
   `:rhs?` / `:toolbar?` / `:embed?`) on the chrome-visibility map.
  Pure data → data."
  [state slot]
  (update-in state [:chrome-visibility slot]
             (fn [v]
               (let [defaults chrome-visibility-defaults
                     prev     (if (some? v) v (get defaults slot))]
                 (not prev)))))

(defn set-chrome-visibility
  "Set the boolean at `slot` on the chrome-visibility map to `value`.
  Pure data → data."
  [state slot value]
  (assoc-in state [:chrome-visibility slot] (boolean value)))

;; ---- effective per-pane visibility derivations --------------------------
;;
;; Embed-mode wins absolutely: every chrome pane hides.
;; Full-screen hides every chrome pane but leaves canvas +
;; in-canvas affordances. Per-pane toggles win when neither
;; absolute mode is on.

(defn chrome-pane-visible?
  "Resolve whether `pane` (`:sidebar` / `:rhs` / `:toolbar`) should render
  given the chrome-visibility map. Pure data → data; JVM-testable.

  Resolution: embed-mode > full-screen > per-pane toggle.

  `:full-screen?` and `:embed?` both elide chrome; the difference is
  intent — full-screen is a per-shell toggle the user drives via `f`,
  embed is an outer-context signal carried in the URL. Both project to
  'every chrome pane hidden' here."
  [pane state]
  (let [v (chrome-visibility state)
        slot-key (case pane
                   :sidebar :sidebar?
                   :rhs     :rhs?
                   :toolbar :toolbar?
                   nil)]
    (cond
      (:embed? v)       false
      (:full-screen? v) false
      (nil? slot-key)   true
      :else             (boolean (get v slot-key true)))))

;; ---- mode-tab ------------------------------------------------------------

(def mode-tabs
  "Ordered vector of canonical render-shell mode tabs. Stable id order
  drives the chip strip's left-to-right layout. `:dev` is the canvas
  view (rendered by `re-frame.story.ui.canvas`), `:docs` is the
  read-only AutoDocs-equivalent, `:test` is the in-canvas aggregated
  pass/fail view."
  [:dev :docs :test])

(def mode-tab-labels
  "Human-readable label per mode-tab id. Used by the chip strip."
  {:dev  "Canvas"
   :docs "Docs"
   :test "Tests"})

(def default-mode-tab
  "Default mode-tab when no per-variant selection is recorded. `:dev`
  renders the variant in the canvas as soon as it's selected."
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
