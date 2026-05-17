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
  (:require [re-frame.story.config                :as config]
            [re-frame.story.predicates            :as pred]
            [re-frame.story.ui.state.filters      :as state.filters]
            [re-frame.story.ui.state.snapshot     :as state.snapshot]
            [re-frame.story.ui.state.tests        :as state.tests]
            [re-frame.story.ui.state.transitions  :as state.transitions]
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
  - `:rail-widths`       — {:left px :right px}. User-resized Story shell
                           rail widths, hydrated from localStorage by the
                           CLJS shell. The defaults keep the canvas usable.
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
   ;; rf2-c8kfy: stable monotonic ids for repeater rows so React keys
   ;; survive mid-list deletes (no focus / cursor leakage onto the
   ;; surviving rows). Counter allocates fresh ids; the row-ids map
   ;; carries `{[variant-id path] → [id0 id1 ...]}` in lockstep with
   ;; the entries vector at `:cell-overrides`.
   :rf.story/repeater-id-counter 0
   :rf.story/repeater-row-ids    {}
   :substrate           :reagent
   :hot-reload-tick     0
   :fingerprints        {}
   :pinned-snapshots    {}
   ;; rf2-q9kv5 — `:dispatch-console` slot. Default is nil (not false)
   ;; so the per-story `:dispatch-console?` body flag is the effective
   ;; default. The shell's right-panel checks the per-story flag first
   ;; and falls back to true when nothing is declared.
   :panel-visibility    {:trace true :scrubber true :controls true :actions true}
   :active-mode-tab     {}
   :rail-widths         {:left 260 :right 320}
   :tests               {:runs           {}
                         :watch-mode?    false
                         :content-hashes {}}})

;; ---- pure transitions (extracted to state.transitions, rf2-gcpon) -------

(def select-variant            state.transitions/select-variant)
(def select-workspace          state.transitions/select-workspace)
(def toggle-tag-filter         state.transitions/toggle-tag-filter)
(def set-active-modes          state.transitions/set-active-modes)
(def toggle-mode               state.transitions/toggle-mode)
(def clear-active-modes        state.transitions/clear-active-modes)
(def group-modes-by-axis       state.transitions/group-modes-by-axis)
(def set-cell-override         state.transitions/set-cell-override)
(def set-cell-override-scalar  state.transitions/set-cell-override-scalar)
(def clear-cell-overrides      state.transitions/clear-cell-overrides)
(def ensure-repeater-row-ids   state.transitions/ensure-repeater-row-ids)
(def repeater-row-ids          state.transitions/repeater-row-ids)
(def append-repeater-row-id    state.transitions/append-repeater-row-id)
(def remove-repeater-row-id    state.transitions/remove-repeater-row-id)
(def bump-hot-reload-tick      state.transitions/bump-hot-reload-tick)
(def record-fingerprints       state.transitions/record-fingerprints)
(def pin-snapshot              state.transitions/pin-snapshot)
(def toggle-panel              state.transitions/toggle-panel)

;; ---- mode-tab (rf2-9hc8) re-exports --------------------------------------

(def mode-tabs                 state.transitions/mode-tabs)
(def mode-tab-labels           state.transitions/mode-tab-labels)
(def default-mode-tab          state.transitions/default-mode-tab)
(def valid-mode-tab?           state.transitions/valid-mode-tab?)
(def active-mode-tab           state.transitions/active-mode-tab)
(def set-active-mode-tab       state.transitions/set-active-mode-tab)

;; ---- pure derivations (extracted to state.filters, rf2-gcpon) -----------

(def group-tags-by-axis           state.filters/group-tags-by-axis)
(def axis-display-order           state.filters/axis-display-order)
(def ordered-axes                 state.filters/ordered-axes)
(def partition-tag-filter-by-axis state.filters/partition-tag-filter-by-axis)
(def variant-tag-match?           state.filters/variant-tag-match?)
(def filter-variants              state.filters/filter-variants)
(def group-variants-by-story      state.filters/group-variants-by-story)

(def parent-story-id
  "Cheap parent-story derivation. Canonical definition lives in
  `re-frame.story.predicates`; aliased here so existing sidebar call
  sites keep their `state/parent-story-id` shape."
  pred/parent-story-id)

;; ---- registry snapshot (extracted to state.snapshot, rf2-gcpon) ---------

(def registry-snapshot
  "Re-export of `re-frame.story.ui.state.snapshot/registry-snapshot`."
  state.snapshot/registry-snapshot)

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

;; ---- test-runs + watch-mode (extracted to state.tests, rf2-gcpon) -------
;;
;; The cross-variant test-run aggregation surface (rf2-q0irb) and the
;; watch-mode helpers (rf2-z1h0f) live in `re-frame.story.ui.state.tests`
;; — split out per rf2-gcpon to honor the rf2-zkca8 leaf-size ceiling.
;; Re-exported here so consumer requires of `re-frame.story.ui.state`
;; keep working unchanged.

(def test-run-statuses           state.tests/test-run-statuses)
(def mark-test-running           state.tests/mark-test-running)
(def aggregate-summary           state.tests/aggregate-summary)
(def record-test-run             state.tests/record-test-run)
(def clear-test-run              state.tests/clear-test-run)
(def variant-test-status         state.tests/variant-test-status)
(def test-summary                state.tests/test-summary)
(def testable-variant-ids        state.tests/testable-variant-ids)
(def set-test-watch-mode         state.tests/set-test-watch-mode)
(def test-watch-mode?            state.tests/test-watch-mode?)
(def record-test-content-hashes  state.tests/record-test-content-hashes)
(def watch-mode-drift            state.tests/watch-mode-drift)
