(ns re-frame.story.ui.mode-tabs
  "Render-shell `:dev` / `:docs` / `:test` mode-tabs primitive (rf2-9hc8).

  Storybook's chrome ships a top-of-canvas tab strip that switches the
  variant view between three canonical modes:

  - `:dev`  — Canvas. The interactive variant render (Story v1 default).
  - `:docs` — Docs. The read-only AutoDocs-equivalent: prose + args
              table + decorator stack + parameters + tags. Implemented
              in `re-frame.story.ui.docs` (rf2-rodx); this primitive
              owns the chip strip only.
  - `:test` — Tests. The in-canvas aggregated pass/fail summary for the
              variant's interactions / assertions. Implemented in
              `re-frame.story.ui.test-mode.view` (rf2-qmjo, split per rf2-8n2fz);
              this primitive
              owns the chip strip only.

  ## Primitive surface

  This namespace exposes one rendering entry point:

      (mode-tabs-strip variant-id)

  which renders a horizontal chip strip at the top of the render shell.
  Selection is per-variant — switching variants does not clear another
  variant's selection — and persists across reloads via localStorage
  under the key `re-frame.story/active-mode-tab/<variant-id>`.

  The shell's `main-pane` consults `(state/active-mode-tab state vid)`
  to decide which pane renders below the strip:

  - `:dev`  → existing canvas / workspace path (unchanged).
  - `:docs` → `re-frame.story.ui.docs/docs-view` (rf2-rodx).
  - `:test` → `re-frame.story.ui.test-mode.view/test-view` (rf2-qmjo).

  ## Visual style

  Matches the existing render-shell chrome (rf2-2uwv contrast fixes):
  `#b0b0b0` inactive foreground, white active foreground, `#1e1e1e`
  active background. Reuses the shape of the tab-bar/tab/tab-active
  styles already defined in `re-frame.story.ui.shell`."
  (:require [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :refer [sans-stack mono-stack]]))

;; ---- localStorage persistence -------------------------------------------
;;
;; Per-variant — the key embeds the variant id so two variants on the
;; same page can hold distinct mode-tab selections. Mirrors the help
;; overlay's `safe-local-storage` pattern (defensive against private-mode
;; browsers, file://, embedded contexts) so a localStorage failure
;; degrades silently to in-memory-only state.

(def ^:const ls-key-prefix
  "Prefix for the localStorage key used to persist the active mode-tab
  per variant. The full key is `<prefix><variant-id-as-string>`."
  "re-frame.story/active-mode-tab/")

(defn- safe-local-storage
  "Return `js/window.localStorage` if available, otherwise nil."
  []
  (when (and (exists? js/window) (.-localStorage js/window))
    (try (.-localStorage js/window)
         (catch :default _ nil))))

(defn- ls-key
  "Build the localStorage key for `variant-id`. Variant ids are
  qualified keywords; `str` round-trips them as `\":ns/name\"` which is
  stable across reloads."
  [variant-id]
  (str ls-key-prefix variant-id))

(defn load-mode-tab!
  "Read the persisted mode-tab for `variant-id` from localStorage.
  Returns one of `state/mode-tabs` or nil if no record / unparseable."
  [variant-id]
  (when-let [ls (safe-local-storage)]
    (try
      (let [raw (.getItem ls (ls-key variant-id))]
        (when (string? raw)
          (let [tab (keyword raw)]
            (when (state/valid-mode-tab? tab) tab))))
      (catch :default _ nil))))

(defn save-mode-tab!
  "Persist `tab` for `variant-id` in localStorage. Silently no-ops if
  localStorage is unavailable or `tab` is not a valid mode-tab."
  [variant-id tab]
  (when (and (state/valid-mode-tab? tab) variant-id)
    (when-let [ls (safe-local-storage)]
      (try (.setItem ls (ls-key variant-id) (name tab))
           (catch :default _ nil)))))

(defn hydrate-from-storage!
  "Seed the shell state's `:active-mode-tab` entry for `variant-id` from
  localStorage if a persisted value exists and the state has no
  in-memory entry yet. Idempotent — safe to call on every render of the
  tab strip; only writes when the in-memory slot is empty."
  [variant-id]
  (when variant-id
    (let [shell @state/shell-state-atom]
      (when-not (contains? (:active-mode-tab shell) variant-id)
        (when-let [persisted (load-mode-tab! variant-id)]
          (state/swap-state! state/set-active-mode-tab variant-id persisted))))))

;; ---- selection helper ----------------------------------------------------

(defn select-mode-tab!
  "Switch the active mode-tab for `variant-id` to `tab`, updating both
  shell state and localStorage. Public so tests / programmatic callers
  can drive the switcher without going through the DOM."
  [variant-id tab]
  (when (and variant-id (state/valid-mode-tab? tab))
    (state/swap-state! state/set-active-mode-tab variant-id tab)
    (save-mode-tab! variant-id tab)))

;; ---- styling -------------------------------------------------------------
;;
;; Same visual register as the existing render-shell chrome — see
;; rf2-2uwv for the contrast fixes that landed `#b0b0b0` as the standard
;; inactive foreground.

(def ^:private styles
  {:strip       {:display          "flex"
                 :background       "#2d2d30"
                 :border-bottom    "1px solid #444"
                 :font-family      mono-stack
                 :font-size        "11px"
                 :padding          "0"}
   ;; rf2-c4m8x: longhand-only border sides on every chip. The
   ;; `:tab-active` overlay below adds a `:border-bottom`; mixing the
   ;; shorthand `:border "none"` with longhand `:border-bottom` on the
   ;; same React element across renders triggers React's reconcile
   ;; warning ("Removing a style property during rerender (borderBottom)
   ;; when a conflicting property is set (border) can lead to styling
   ;; bugs"). Spell every side longhand so React reconciles a stable
   ;; set of keys. Same shape as the trace.cljs fix in rf2-fq1yg.
   :tab         {:padding             "6px 14px"
                 :cursor              "pointer"
                 :color               "#b0b0b0"
                 :background          "#2d2d30"
                 :border-top          "none"
                 :border-right        "1px solid #444"
                 :border-bottom       "none"
                 :border-left         "none"
                 :font-family         mono-stack
                 :font-size           "11px"
                 :letter-spacing      "0.3px"}
   :tab-active  {:color            "white"
                 :background       "#1e1e1e"
                 :border-bottom    "1px solid #1e1e1e"
                 :margin-bottom    "-1px"
                 :font-weight      "bold"}
   :placeholder {:padding          "32px"
                 :color            "#9a9a9a"
                 :font-style       "italic"
                 :font-family      sans-stack
                 :text-align       "center"
                 :background       "#1e1e1e"
                 :flex             "1"}})

;; ---- chip strip ----------------------------------------------------------

(defn mode-tabs-strip
  "Render the per-variant mode-tab chip strip. Renders nothing when
  `variant-id` is nil (no variant selected = no canvas to switch).

  Three chips: Canvas (`:dev`) | Docs (`:docs`) | Tests (`:test`). The
  active chip carries an `aria-current=\"page\"` attribute; each chip
  carries a `data-mode-tab=\"<id>\"` attribute so the Playwright spec
  can target chips without coupling to the visible label.

  Per rf2-9hc8 the strip lives directly above the canvas/workspace pane
  inside `<main>`."
  [variant-id]
  (when variant-id
    ;; Hydrate from localStorage on first render of this variant's
    ;; strip — idempotent on subsequent renders.
    (hydrate-from-storage! variant-id)
    (let [shell  @state/shell-state-atom
          active (state/active-mode-tab shell variant-id)]
      [:div {:style       (:strip styles)
             :role        "tablist"
             :aria-label  "Story mode"
             :data-test   "story-mode-tabs"}
       (for [tab state/mode-tabs]
         ^{:key tab}
         [:button
          {:style          (merge (:tab styles)
                                  (when (= tab active) (:tab-active styles)))
           :role           "tab"
           :aria-selected  (if (= tab active) "true" "false")
           :aria-current   (when (= tab active) "page")
           :data-mode-tab  (name tab)
           :on-click       #(select-mode-tab! variant-id tab)}
          (get state/mode-tab-labels tab (name tab))])])))

;; ---- placeholder panes ---------------------------------------------------
;;
;; rf2-rodx (Docs) is implemented in `re-frame.story.ui.docs`; rf2-qmjo
;; (Tests) is implemented in `re-frame.story.ui.test-mode.view` (split
;; per rf2-8n2fz into pure/state/view). Both
;; placeholders have been removed — the shell routes the `:docs` /
;; `:test` cases directly at the dedicated panes. The `:placeholder`
;; style entry below stays in the styles map in case a future pane
;; needs an empty-state shape, but no placeholder fn is registered.
