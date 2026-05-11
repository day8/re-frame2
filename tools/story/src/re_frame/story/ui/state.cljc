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
  - `set-cell-override`  — set a single arg override.
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
  (:require [re-frame.story.config    :as config]
            [re-frame.story.registrar :as registrar]
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
                           scrubber / controls); Stage 6 will register
                           more via reg-story-panel."
  {:selected-variant   nil
   :selected-workspace nil
   :tag-filter         #{}
   :active-modes       []
   :cell-overrides     {}
   :substrate          :reagent
   :hot-reload-tick    0
   :fingerprints       {}
   :pinned-snapshots   {}
   :panel-visibility   {:trace true :scrubber true :controls true}})

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

(defn set-cell-override
  "Set a single arg override for `variant-id`."
  [state variant-id arg-key value]
  (assoc-in state [:cell-overrides variant-id arg-key] value))

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

(defn parent-story-id
  "Cheap parent-story derivation — mirrors `re-frame.story.args/parent-
  story-id` so the sidebar can group without crossing the args ns
  boundary. The variant id `:story.foo/bar` has namespace `\"story.foo\"`;
  its parent is `:story.foo`."
  [variant-id]
  (when (and (keyword? variant-id) (namespace variant-id))
    (keyword (namespace variant-id))))

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
