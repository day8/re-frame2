(ns re-frame.story.ui.state
  "Shell-local state — pure data + helpers (`.cljc`) plus the CLJS-only
  reactive ratom bag. Per Stage 4 (rf2-ekai) the UI shell carries its
  own selection / filter / mode state independently of the Story
  registrar (the registrar is the source of truth for *what's
  registered*; this ns is the source of truth for *what's selected*).

  ## Public surface

  - `default-shell-state` — the initial map shape (pure data).
  - `select-variant`     — change the focused variant id.
  - `select-workspace`   — change the focused workspace id.
  - `toggle-tag-filter`  — flip a tag on/off in the filter set.
  - `set-active-modes`   — replace the active mode set.
  - `set-cell-override`  — set a single arg override at a path
                           `[arg-key & sub-path]`; the nested-path
                           form backs the nested-schema controls
                           walker (rf2-agshe). The `-scalar` wrapper
                           handles the bare top-level arg-key case.
  - `clear-cell-overrides` — drop all overrides for the focused variant.
  - `filter-variants`    — pure fn: given a set of variant bodies + the
                           shell state, return the visible subset.
  - `group-variants-by-story` — pure fn: build the sidebar tree.

  ## The reactive bag (CLJS-only)

  On CLJS the shell needs a Reagent ratom so any change re-renders the
  three panes. `shell-state-atom` is that ratom — a `r/atom` holding a
  map of the same shape `default-shell-state` returns. On JVM the same
  atom is a plain `clojure.core/atom` so pure-logic tests don't pull in
  Reagent.

  The reactive surface is gated behind `re-frame.story.config/enabled?`
  — production builds with the flag false see an empty state map and
  the shell entry point short-circuits before any subscription / mount
  call. Per IMPL-SPEC §6.3."
  (:require [re-frame.story.config     :as config]
            [re-frame.story.predicates :as pred]
            [re-frame.story.registrar  :as registrar]
            #?(:cljs [reagent.core :as r])))

;; ---- pure data: the default shape ----------------------------------------

(def default-shell-state
  "The initial shell state. Stage 4 keeps the shape small and additive.

  - `:selected-variant`  — variant id under focus, or nil.
  - `:selected-workspace` — workspace id under focus, or nil.
  - `:tag-filter`        — set of tag keywords; empty set means 'show all'.
  - `:active-modes`      — vector of mode ids active for the rendered variant.
  - `:cell-overrides`    — {variant-id → {arg-key → value}} — runtime
                           overrides emitted by the controls panel.
  - `:substrate`         — `:reagent` at v1; v2 may add UIx / Helix.
  - `:hot-reload-tick`   — integer that increments when the shell detects
                           a registrar mutation; variant components watch
                           this slot to know they must re-mount.
  - `:fingerprints`      — {variant-id → {decorator-id → hash}} the last-
                           observed decorator fingerprints. Stale entries
                           trigger a hot-reload tick.
  - `:pinned-snapshots`  — {variant-id → [{:label ... :epoch-id ...}]}.
  - `:panel-visibility`  — {panel-id → boolean}. Determines whether a
                           registered :story-panel renders in the chrome.
                           Stage 4 ships a vanilla set of panels (trace /
                           scrubber / controls / actions per rf2-5yriz);
                           Stage 6 will register more via reg-story-panel.
  - `:active-mode-tab`   — {variant-id → :dev | :docs | :test}. Per-variant
                           mode-tab selection for the render-shell's top
                           Canvas | Docs | Tests switcher (rf2-9hc8).
                           Unspecified → :dev (canvas). Persisted in
                           localStorage under `re-frame.story/active-
                           mode-tab/<variant-id>`.
  - `:tests` — sub-map grouping every chrome-test-widget slot under
               one root (rf2-uefbk). Holds:

      `:runs`           — {variant-id → run-state}. Per-variant test-run
                          record fed both by the `:test` mode pane's
                          Re-run button and by the chrome-level test
                          widget's 'Run all'. Each run-state is one of:
                             :pending  — no run recorded
                             :running  — run in flight
                             :pass     — last run: all assertions passed
                             :fail     — last run: ≥1 assertion failed
                          plus pass/fail/skip/total counts and timing.
                          Powers both the chrome widget's aggregate
                          summary and the sidebar's per-variant dots
                          (rf2-q0irb).
      `:watch-mode?`    — boolean. When true the chrome test widget's
                          eye-icon toggle is on and the shell auto-
                          re-runs testable variants whose snapshot
                          identity drifted since the last observation
                          (rf2-z1h0f). Default false — explicit re-run
                          is the v1 contract; watch-mode is opt-in.
      `:content-hashes` — {variant-id → hex-hash} the last-observed
                          snapshot-identity content hash per testable
                          variant. The watch-mode detector compares the
                          current registry's hashes against this slot;
                          a delta triggers an auto-rerun for the
                          affected variants. Empty when watch mode is
                          off (the detector seeds it on toggle-on)."
  {:selected-variant    nil
   :selected-workspace  nil
   :tag-filter          #{}
   :active-modes        []
   :cell-overrides      {}
   :substrate           :reagent
   :hot-reload-tick     0
   :fingerprints        {}
   :pinned-snapshots    {}
   :panel-visibility    {:trace true :scrubber true :controls true :actions true}
   :active-mode-tab     {}
   :tests               {:runs           {}
                         :watch-mode?    false
                         :content-hashes {}}})

;; ---- pure transition fns (JVM-testable) ----------------------------------

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
  `(registrar/handlers :mode)`. Returns

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
  "Drop every override for `variant-id`."
  [state variant-id]
  (update state :cell-overrides dissoc variant-id))

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

;; ---- pure derivations ----------------------------------------------------

(defn variant-tag-match?
  "True iff `variant-body`'s `:tags` set intersects the `tag-filter`,
  or the filter is empty. Bare-bones inclusion-tag logic per spec/007
  §Inclusion tags. Stage 4 ignores the `:!`-prefix removal syntax —
  that's resolved at registration time in `re-frame.story.registrar`
  via `validate-tag-membership!`."
  [variant-body tag-filter]
  (or (empty? tag-filter)
      (let [tset (or (:tags variant-body) #{})]
        (some #(contains? tset %) tag-filter))))

(defn filter-variants
  "Return the subset of `id->body` whose `:tags` match the filter.
  Pure data → data; JVM-testable."
  [id->body tag-filter]
  (into {}
        (filter (fn [[_ body]] (variant-tag-match? body tag-filter)))
        id->body))

(def parent-story-id
  "Cheap parent-story derivation. Canonical definition lives in
  `re-frame.story.predicates`; aliased here so existing sidebar call
  sites keep their `state/parent-story-id` shape."
  pred/parent-story-id)

(defn group-variants-by-story
  "Build a sorted vector of `{:story-id ... :variants [...]}` entries
  from the variants map. Variants whose parent story is unregistered
  still appear under their derived parent id — the sidebar surfaces them
  with a 'no story' indicator.

  Sorted by story id (alphabetic on the keyword name) so the sidebar is
  stable across re-renders."
  [id->body]
  (let [by-story (group-by (comp parent-story-id key) id->body)]
    (->> by-story
         (map (fn [[story-id variants]]
                {:story-id story-id
                 :variants (vec (sort-by key variants))}))
         (sort-by (fn [{:keys [story-id]}]
                    (if story-id (str story-id) "")))
         vec)))

;; ---- registry snapshot ---------------------------------------------------
;;
;; The shell takes a fresh snapshot of the Story registrar on every
;; render — variants/stories/workspaces/modes/decorators/panels/tags are
;; what's currently registered, and the sidebar / control panel /
;; workspace panes consume the snapshot.

(defn registry-snapshot
  "Return a single map containing every Story-side artefact kind. Used
  by the shell's render fns to walk the registry without N atom-deref
  calls (and to support future memoisation if perf becomes an issue)."
  []
  {:stories      (registrar/handlers :story)
   :variants     (registrar/handlers :variant)
   :workspaces   (registrar/handlers :workspace)
   :modes        (registrar/handlers :mode)
   :decorators   (registrar/handlers :decorator)
   :story-panels (registrar/handlers :story-panel)
   :tags         (registrar/handlers :tag)})

;; ---- the reactive bag (CLJS-only) ----------------------------------------

#?(:cljs
   (defonce ^{:doc "The shell's reactive state ratom. One singleton per
                    process (v1; v2 supports multi-shell). Reagent-aware
                    components deref it directly; pure-logic helpers
                    above take a state map argument."}
     shell-state-atom
     (r/atom default-shell-state))
   :clj
   (defonce ^{:doc "JVM mirror of `shell-state-atom` — a plain atom so
                    JVM-side tests can exercise the pure-logic helpers
                    without pulling Reagent into the test classpath."}
     shell-state-atom
     (atom default-shell-state)))

(defn reset-shell-state!
  "Reset the shell state to its initial shape. Used by test fixtures
  and `unmount-shell!`."
  []
  (reset! shell-state-atom default-shell-state)
  nil)

(defn get-state
  "Return the current shell state map."
  []
  @shell-state-atom)

(defn swap-state!
  "Apply `f` (plus optional args) to the shell state. The pure fns above
  are the recommended `f` values."
  [f & args]
  (when config/enabled?
    (apply swap! shell-state-atom f args)))

;; ---- test-runs (rf2-q0irb) ----------------------------------------------
;;
;; Cross-variant aggregation surface: each variant's last `run-variant`
;; outcome is folded into `[:tests :runs]`. The chrome-level test
;; widget reads it as a summary; the sidebar's per-variant rows read
;; individual entries as a status dot. Both surfaces are pure
;; derivations of this one slot.
;;
;; The test-mode pane's local `results-atom` (in
;; `re-frame.story.ui.test-mode.state`) keeps the full result-map
;; (assertion records + expanded-row UI state); this shell-state slot
;; carries only the aggregate counts the chrome widget + sidebar dots
;; need. Two stores, two read paths, no contention — the pane's local
;; atom drives the detail view, the shell-state slot drives the global
;; surfaces.

(def test-run-statuses
  "Canonical run-state ids, in render order.

  - `:pass`     last run: every assertion passed (and at least one assertion).
  - `:fail`     last run: ≥1 assertion failed.
  - `:running`  run currently in flight.
  - `:pending`  no run recorded yet (or run produced zero assertions)."
  [:pass :fail :running :pending])

(defn mark-test-running
  "Stamp `variant-id` as :running. Idempotent."
  [state variant-id]
  (assoc-in state [:tests :runs variant-id] {:status :running}))

(defn aggregate-summary
  "Walk `assertions` (the vector pulled off a `run-variant` result map)
  and produce the aggregated pass/fail/skip counts:

      {:total       <n>
       :passed      <n>
       :failed      <n>
       :skipped     <n>
       :all-passed? <bool>}

  `:skipped` counts records carrying `:assertion :rf.assert/skipped` —
  re-frame2's v1 runtime doesn't emit this id, but the slot stays open
  so spec/004 additions flow through without a pane refactor.
  `:all-passed?` is true iff `:total > 0 AND :failed = 0 AND :skipped = 0`.

  Lives in `state.cljc` (not `test-mode.pure`) so both the test-mode
  pane AND the sidebar / chrome-level test widget can call one
  canonical fold without a require cycle (sidebar can't require
  test-mode, which would loop back through shell-state). Pure data →
  data; JVM-testable."
  [assertions]
  (let [items     (or assertions [])
        skipped?  (fn [r] (= :rf.assert/skipped (:assertion r)))
        skipped   (count (filter skipped? items))
        active    (remove skipped? items)
        passed    (count (filter :passed? active))
        failed    (- (count active) passed)
        total     (count items)]
    {:total       total
     :passed      passed
     :failed      failed
     :skipped     skipped
     :all-passed? (and (pos? total) (zero? failed) (zero? skipped))}))

(defn record-test-run
  "Write the aggregate of a `run-variant` result into `[:tests :runs]`.

  `summary` is the map returned by `aggregate-summary` —
  `{:total :passed :failed :skipped :all-passed?}` — extended with
  optional `:ran-at-ms` and `:elapsed-ms`. A run that recorded zero
  assertions lands as `:pending` (rather than `:pass`/`:fail`) so the
  sidebar dot reads grey — the variant ran but produced no signal."
  [state variant-id summary]
  (let [{:keys [total passed failed skipped all-passed?
                ran-at-ms elapsed-ms]} (or summary {})
        status (cond
                 (zero? (or total 0)) :pending
                 all-passed?          :pass
                 :else                :fail)]
    (assoc-in state [:tests :runs variant-id]
              {:status     status
               :total      (or total 0)
               :passed     (or passed 0)
               :failed     (or failed 0)
               :skipped    (or skipped 0)
               :ran-at-ms  ran-at-ms
               :elapsed-ms elapsed-ms})))

(defn clear-test-run
  "Drop the run record for `variant-id`."
  [state variant-id]
  (update-in state [:tests :runs] dissoc variant-id))

(defn variant-test-status
  "Return the canonical status keyword for `variant-id` (one of
  `test-run-statuses`). Variants with no recorded run read `:pending`.
  Pure data → data; JVM-testable."
  [state variant-id]
  (or (get-in state [:tests :runs variant-id :status])
      :pending))

(defn test-summary
  "Aggregate the chrome-level test widget's headline counts across the
  given seq of variant-ids — the variants tagged `:test` registered at
  the time of call. Returns:

      {:total      <count of variant-ids>
       :passed     <count whose last run was :pass>
       :failed     <count whose last run was :fail>
       :running    <count currently in flight>
       :pending    <count with no recorded run>
       :all-green? <bool — total > 0 AND failed = 0 AND running = 0
                          AND pending = 0>}

  Pure data → data; the JVM corpus exercises it against a fixture map
  without booting Reagent. `all-green?` mirrors `aggregate-summary`'s
  `:all-passed?` — true only when every variant has a recorded green
  run; a sea of `:pending` reads as 'not green yet', not 'all green'."
  [state variant-ids]
  (let [runs    (get-in state [:tests :runs])
        ;; Single O(N) frequencies pass — read each variant's status
        ;; once and bucket by keyword. Missing entries default to :pending.
        buckets (frequencies
                  (map (fn [vid] (or (get-in runs [vid :status]) :pending))
                       variant-ids))
        total   (count variant-ids)
        passed  (get buckets :pass    0)
        failed  (get buckets :fail    0)
        running (get buckets :running 0)
        pending (get buckets :pending 0)]
    {:total      total
     :passed     passed
     :failed     failed
     :running    running
     :pending    pending
     :all-green? (and (pos? total)
                      (zero? failed)
                      (zero? running)
                      (zero? pending))}))

(defn testable-variant-ids
  "Return the seq of variant-ids tagged `:test`, in stable (alphabetical)
  order. The chrome widget + sidebar dots key off this seq.

  Variants are testable iff (a) their `:tags` contains `:test`, AND
  (b) they declare a non-empty `:play` slot. The second filter prunes
  variants tagged `:test` but without any assertions to run — those
  contribute neither to the headline counts nor to the 'Run all'
  iteration. Pure data → data; JVM-testable. `id->body` is the
  `{variant-id → body}` map from `(registrar/handlers :variant)`."
  [id->body]
  (->> id->body
       (filter (fn [[_ body]]
                 (and (contains? (or (:tags body) #{}) :test)
                      (seq (or (:play body) [])))))
       (map first)
       sort
       vec))

;; ---- watch mode (rf2-z1h0f) ---------------------------------------------
;;
;; Storybook 9 ships a Vitest-addon watch-mode toggle (eye icon) that
;; re-runs the changed stories on file save. Story's parity surface is
;; this: an opt-in toggle on the chrome-level test widget that
;; subscribes to per-variant snapshot-identity drift and re-fires
;; `run-variant` for the variants whose identity changed. The detection
;; signal is the variant's snapshot-identity content-hash
;; (re-frame.story.identity/snapshot-identity); a delta against the
;; recorded [:tests :content-hashes] slot triggers the re-run.

(defn set-test-watch-mode
  "Toggle/set the chrome-level watch-mode flag. When `on?` is true the
  shell auto-re-runs testable variants whose snapshot identity drifts;
  when false the toggle is off and the recorded hashes are cleared (the
  next toggle-on seeds them fresh from the current registry). Pure data
  → data; JVM-testable."
  [state on?]
  (if on?
    (assoc-in state [:tests :watch-mode?] true)
    (update state :tests assoc
            :watch-mode?    false
            :content-hashes {})))

(defn test-watch-mode?
  "Return `true` iff watch mode is currently on. Pure."
  [state]
  (boolean (get-in state [:tests :watch-mode?])))

(defn record-test-content-hashes
  "Stamp the current snapshot-identity content hashes for every testable
  variant. `id->hash` is `{variant-id → hex-string}`. The detector
  reads this slot on the next tick to decide which variants drifted."
  [state id->hash]
  (assoc-in state [:tests :content-hashes] (or id->hash {})))

(defn watch-mode-drift
  "Pure data → data: given the previous `[:tests :content-hashes]` map and a
  freshly-computed `current` `{variant-id → hex}` map, return the
  ordered vector of variant-ids whose hash differs from `prev` (i.e.
  the variants the watch-mode detector should re-run on this tick).

  Variants present in `current` but absent from `prev` are treated as
  drifted — the seed call to `record-test-content-hashes` happens on
  toggle-on so a missing prev entry signals a fresh registration that
  the user wants exercised. Variants present in `prev` but absent from
  `current` (deregistered) are silently dropped — there's nothing to
  re-run. JVM-testable."
  [prev current]
  (let [prev    (or prev {})
        current (or current {})]
    (->> current
         (filter (fn [[vid hex]] (not= hex (get prev vid))))
         (map first)
         sort
         vec)))
