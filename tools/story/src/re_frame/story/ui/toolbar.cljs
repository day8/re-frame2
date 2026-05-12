(ns re-frame.story.ui.toolbar
  "Chrome-level toolbar — the horizontal strip above the three-pane row
  that exposes every registered `reg-mode` tuple as a toggle chip.

  Per spec/010 (rf2-p0mv) Storybook 8's `theme` / `viewport` / `locale`
  toolbar refactored to re-frame2 idioms: one registry (`:mode`), one
  shell-state slot (`:active-modes`), one persistence key
  (`re-frame.story/active-modes`), one URL deep-link key (`modes=`).

  ## Surface

  - `(toolbar-strip)`            — Reagent component; renders the
                                   horizontal strip with chips per
                                   registered mode + a `[reset]` button.
  - `toggle-mode!`               — programmatic toggle for tests.
  - `hydrate-modes-from-storage!`/`hydrate-modes-from-url!` — idempotent
                                   one-shot hydrators run at shell
                                   mount.
  - `save-modes-to-storage!`     — persist after every change.

  ## Selection semantics

  Per spec/010 §Selection semantics — by axis the `:axis` slot on a
  `reg-mode` body governs the chip's toggle behaviour:

  - `:axis` present → single-select within axis. Toggling a mode in
    `:axis :theme` deactivates any sibling tagged with the same axis
    before adding the toggled mode.
  - `:axis` absent → multi-select. Any subset can be active.

  The pure logic lives in `re-frame.story.ui.state/toggle-mode` (JVM-
  testable); this ns wires the impure surfaces — localStorage,
  `js/window.location.search`, and the Reagent ratom — around it.

  ## Persistence

  Per spec/010 §Persistence — chrome-wide localStorage the toolbar's
  selection is **chrome-wide** (one selection for the whole shell
  instance). Persistence is a single localStorage key:

      re-frame.story/active-modes → \"[:Mode.app/dark :Mode.app/mobile]\"

  Stored as a `pr-str`-encoded vector of mode ids; `read-string` on
  load. The URL deep-link (`?modes=...`) takes precedence over
  localStorage on hydrate (last-shared wins over last-used).

  Mode ids that no longer resolve at the registrar (stale storage after
  a `reg-mode` rename) are silently dropped at hydrate time."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.state :as state]))

;; ---- localStorage --------------------------------------------------------

(def ^:const ls-key
  "Chrome-wide localStorage key for the active-modes vector. Spec/010
  §Persistence — chrome-wide localStorage."
  "re-frame.story/active-modes")

(defn- safe-local-storage
  "Return `js/window.localStorage` if available, otherwise nil. Mirrors
  the defensive pattern in `mode-tabs` — private mode / file:// /
  embedded contexts can throw on touch."
  []
  (when (and (exists? js/window) (.-localStorage js/window))
    (try (.-localStorage js/window)
         (catch :default _ nil))))

(defn load-modes-from-storage
  "Read the persisted active-modes vector from localStorage. Returns
  a vector of mode-id keywords on success, nil on missing /
  unparseable. Pure: a `nil`-on-failure read."
  []
  (when-let [ls (safe-local-storage)]
    (try
      (let [raw (.getItem ls ls-key)]
        (when (string? raw)
          (let [parsed (edn/read-string raw)]
            (when (and (vector? parsed)
                       (every? keyword? parsed))
              parsed))))
      (catch :default _ nil))))

(defn save-modes-to-storage!
  "Persist `modes` (a vector of mode-id keywords) to localStorage.
  Silently no-ops if storage is unavailable. Idempotent."
  [modes]
  (when-let [ls (safe-local-storage)]
    (try (.setItem ls ls-key (pr-str (vec modes)))
         (catch :default _ nil))))

;; ---- URL deep-link -------------------------------------------------------

(defn parse-modes-param
  "Parse a `modes=` URL query-param value into a vector of mode-id
  keywords. Each id arrives as `\"<ns>/<name>\"` (the canonical
  `(name kw)` form share.cljc emits via `kw->str`) — split on `,` and
  reconstruct via `keyword`. Pure data → data; JVM-testable.

  Returns nil if `s` is blank, an empty vector if no ids parse."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (->> (str/split s #",")
         (map str/trim)
         (remove str/blank?)
         (map (fn [part]
                (if-let [slash (str/index-of part "/")]
                  (keyword (subs part 0 slash) (subs part (inc slash)))
                  (keyword part))))
         vec)))

(defn modes-from-current-url
  "Extract the `modes=` deep-link from `js/window.location.search`, if
  any. Returns a vector of mode-id keywords or nil. CLJS-only — the
  pure parsing fn `parse-modes-param` does the heavy lifting and is
  JVM-testable."
  []
  (when (exists? js/window)
    (let [search (some-> js/window .-location .-search)]
      (when (and (string? search) (seq search))
        (try
          (let [params (js/URLSearchParams. search)
                raw   (.get params "modes")]
            (when (some? raw)
              (parse-modes-param raw)))
          (catch :default _ nil))))))

;; ---- registrar-pruning ---------------------------------------------------

(defn prune-unregistered
  "Drop mode ids from `modes` that no longer resolve at the
  registrar (stale storage / share-URL pointing at a renamed mode).
  Pure data → data; JVM-testable. `registered?` defaults to the live
  registrar; tests may inject."
  ([modes]
   (prune-unregistered modes (fn [mid] (registrar/registered? :mode mid))))
  ([modes registered?]
   (vec (filter registered? (or modes [])))))

;; ---- hydration -----------------------------------------------------------

(defn hydrate-modes-from-storage!
  "Seed the shell-state's `:active-modes` from localStorage on first
  shell mount. Idempotent: only writes when the slot is the default
  empty vector — so an already-populated state (deep-link, programmatic
  test fixture) is never clobbered. Spec/010 §Persistence — chrome-wide
  localStorage."
  []
  (let [shell @state/shell-state-atom]
    (when (empty? (:active-modes shell))
      (when-let [persisted (load-modes-from-storage)]
        (let [pruned (prune-unregistered persisted)]
          (when (seq pruned)
            (state/swap-state! state/set-active-modes pruned)))))))

(defn hydrate-modes-from-url!
  "Seed the shell-state's `:active-modes` from the `?modes=...` query
  param, if present. URL beats localStorage — spec/010 §URL deep-link
  'last-shared wins over last-used'. Idempotent and one-shot at shell
  mount."
  []
  (when-let [from-url (modes-from-current-url)]
    (let [pruned (prune-unregistered from-url)]
      (when (seq pruned)
        (state/swap-state! state/set-active-modes pruned)))))

(defn hydrate!
  "One-shot hydration entry-point: URL takes precedence over storage.
  Called from the shell's `:component-did-mount`. Spec/010 §URL deep-
  link — already wired."
  []
  ;; URL first — clobbers localStorage on hydrate per spec/010.
  (let [url-modes (modes-from-current-url)
        pruned    (when (seq url-modes) (prune-unregistered url-modes))]
    (if (seq pruned)
      (state/swap-state! state/set-active-modes pruned)
      (hydrate-modes-from-storage!))))

;; ---- programmatic toggle -------------------------------------------------

(defn toggle-mode!
  "Flip `mode-id` in the active-modes vector + persist. Public so tests
  / programmatic callers can drive the toolbar without going through
  the DOM."
  [mode-id]
  (state/swap-state!
    (fn [s]
      (state/set-active-modes s (state/toggle-mode (:active-modes s) mode-id))))
  (save-modes-to-storage! (:active-modes (state/get-state))))

(defn reset-modes!
  "Clear every active mode + persist. The toolbar's `[reset]` action."
  []
  (state/swap-state! state/clear-active-modes)
  (save-modes-to-storage! []))

;; ---- styling -------------------------------------------------------------
;;
;; Matches the existing controls-panel chip vocabulary + the mode-tabs
;; strip's chrome register (rf2-2uwv contrast lock). Spec/010 §Visual
;; style: `#252526` background, `#444` bottom border, 8/12px padding,
;; 6px chip gap, 10/11px text.

(def ^:private styles
  {:strip       {:display        "flex"
                 :align-items    "center"
                 :gap            "10px"
                 :padding        "6px 12px"
                 :background     "#252526"
                 :border-bottom  "1px solid #444"
                 :font-family    "monospace"
                 :font-size      "11px"
                 :min-height     "32px"
                 :box-sizing     "border-box"
                 :flex-wrap      "wrap"}
   :axis-label  {:font-size       "10px"
                 :text-transform  "uppercase"
                 :color           "#9a9a9a"
                 :letter-spacing  "0.5px"
                 :margin-right    "4px"}
   :axis-group  {:display     "flex"
                 :align-items "center"
                 :gap         "4px"}
   :chip-row    {:display   "flex"
                 :gap       "4px"
                 :flex-wrap "wrap"}
   :chip        {:padding         "3px 8px"
                 :background      "#37373d"
                 :color           "#cccccc"
                 :border          "none"
                 :border-radius   "10px"
                 :cursor          "pointer"
                 :font-family     "monospace"
                 :font-size       "11px"
                 :max-width       "20em"
                 :overflow        "hidden"
                 :text-overflow   "ellipsis"
                 :white-space     "nowrap"
                 :user-select     "none"}
   :chip-active {:background "#0e639c"
                 :color      "white"}
   :spacer      {:flex "1"}
   :reset       {:padding       "3px 8px"
                 :background    "transparent"
                 :color         "#cccccc"
                 :border        "1px solid #444"
                 :border-radius "3px"
                 :cursor        "pointer"
                 :font-family   "monospace"
                 :font-size     "10px"}
   :empty       {:color       "#9a9a9a"
                 :font-style  "italic"
                 :font-size   "11px"}})

;; ---- chip rendering ------------------------------------------------------

(defn- truncate-label
  "Spec/010 §Chip visual contract — chip label is `(str mode-id)`
  truncated at 28 chars. The full id + `:doc` sits on `title=`."
  [s]
  (if (> (count s) 28)
    (str (subs s 0 27) "…")
    s))

(defn chip
  "Render a single chip for `mode-id`. Pure-hiccup view; click handler
  delegates to `toggle-mode!`. Public so tests can introspect the
  chip-level hiccup without driving the full strip."
  [mode-id body active?]
  [:button
   {:style              (merge (:chip styles)
                               (when active? (:chip-active styles)))
    :role               "button"
    :aria-pressed       (if active? "true" "false")
    :title              (if-let [d (:doc body)]
                          (str (pr-str mode-id) " — " d)
                          (pr-str mode-id))
    :data-toolbar-mode  (pr-str mode-id)
    :on-click           (fn [_] (toggle-mode! mode-id))}
   (truncate-label (pr-str mode-id))])

(defn- axis-label
  "Render the axis-group label. Pure-hiccup."
  [axis]
  [:span {:style (:axis-label styles)} (str/upper-case (name axis))])

;; ---- public component ----------------------------------------------------

(defn toolbar-strip
  "Render the chrome-level toolbar strip. Reads
  `(registrar/handlers :mode)` per render — newly-registered modes
  appear immediately. Renders an empty-state placeholder when the
  registry has no `:mode` entries.

  Spec/010 §Placement in the shell chrome — the strip lives ABOVE the
  three-pane row. Caller (`shell/shell`) wraps the strip in a
  `<header role=\"toolbar\">` landmark — the strip itself is a plain
  hiccup `<div>` so axe-core's region rule sees the landmark."
  []
  (let [shell  @state/shell-state-atom
        active (set (:active-modes shell))
        modes  (registrar/handlers :mode)
        groups (state/group-modes-by-axis modes)]
    [:header
     {:style      (:strip styles)
      :role       "toolbar"
      :aria-label "Story modes"
      :data-test  "story-toolbar"}
     (if (empty? modes)
       [:span {:style (:empty styles)} "no modes registered"]
       (doall
         (for [[axis ids] groups]
           ^{:key (str axis)}
           [:span {:style (:axis-group styles)}
            (when (not= axis :re-frame.story.ui.state/unaxed)
              [axis-label axis])
            [:span {:style (:chip-row styles)}
             (for [mid ids]
               ^{:key mid}
               [chip mid (get modes mid) (contains? active mid)])]])))
     [:span {:style (:spacer styles)}]
     (when (seq (:active-modes shell))
       [:button
        {:style     (:reset styles)
         :data-test "story-toolbar-reset"
         :on-click  (fn [_] (reset-modes!))}
        "reset"])]))
