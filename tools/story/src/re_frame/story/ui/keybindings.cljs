(ns re-frame.story.ui.keybindings
  "Story global keyboard-shortcut registry (rf2-g8l8x / rf2-p3i0t).

  Owns the single `window#keydown` capture-phase listener that backs
  every chrome-level muscle-memory hotkey:

      f  →  toggle full-screen      (rf2-p3i0t)
      s  →  toggle sidebar          (rf2-g8l8x)
      a  →  toggle RHS / addons     (rf2-g8l8x)
      t  →  toggle toolbar          (rf2-g8l8x)

  Cmd-K / Ctrl-K (the command palette, rf2-9hc8) ships its own
  listener in `re-frame.story.ui.command-palette.view` — palette is a
  modal surface, hotkeys here are inline chrome toggles. The two
  listeners co-exist; both install on the capture phase so they
  survive focused inputs.

  ## Why a registry

  Pre-rf2-g8l8x the chrome had ONE global hotkey (Cmd-K) wired
  inline in `command_palette/view.cljs`. Adding `f` / `s` / `a` / `t`
  as inline listeners on shell.cljs would scatter four nearly-
  identical capture-phase listeners across the codebase with no
  central inventory of bound keys. The registry pattern centralises:

  - The canonical `[key → handler]` table (one map, one source of
    truth for what's bound).
  - Modifier discrimination (we ignore keys when a modifier is held,
    so Cmd-S / Ctrl-S / Alt-T etc. pass through to the browser /
    palette).
  - Input-focus discrimination (we ignore keys when an `<input>` /
    `<textarea>` / `[contenteditable]` is focused, so typing `f` into
    the sidebar search doesn't toggle full-screen).
  - Install / teardown semantics (one capture-phase listener; nothing
    leaks across re-mounts).

  ## Shape

  Bindings are a `{key-string → handler-fn}` map. Each handler is a
  zero-arg fn — it owns its side effect (typically a `swap-state!`
  on the chrome-visibility slot). The dispatcher does the
  modifier / focus discrimination and calls the handler when it
  matches.

  ## Persistence

  The chrome-visibility toggles persist to localStorage under
  `re-frame.story/chrome-visibility` so a refresh keeps the user's
  layout intent. Hydration runs once at shell mount. `:embed?` is
  intentionally excluded — embed-mode is URL-driven (rf2-pucku) and
  must not carry across navigations.

  ## Elision

  Production builds with `re-frame.story.config/enabled?` false never
  install the listener — Closure DCE drops the lot."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.story.config :as config]
            [re-frame.story.ui.state :as state]))

;; ---- localStorage persistence -------------------------------------------

(def ^:const ls-key
  "localStorage key for the chrome-visibility map. Stored as a
  `pr-str`-encoded map of the boolean slots; `read-string` on load."
  "re-frame.story/chrome-visibility")

(defn- safe-local-storage []
  (when (and (exists? js/window) (.-localStorage js/window))
    (try (.-localStorage js/window)
         (catch :default _ nil))))

(defn load-from-storage
  "Read the persisted chrome-visibility map. Returns a map merged over
  the canonical defaults, or `nil` when no persisted value exists.
  `:embed?` is intentionally dropped on read — embed-mode is URL-driven
  and must not survive across navigations."
  []
  (when-let [ls (safe-local-storage)]
    (try
      (let [raw (.getItem ls ls-key)]
        (when (string? raw)
          (let [parsed (edn/read-string raw)]
            (when (map? parsed)
              (-> state/chrome-visibility-defaults
                  (merge (select-keys parsed
                                      [:full-screen?
                                       :sidebar?
                                       :rhs?
                                       :toolbar?])))))))
      (catch :default _ nil))))

(defn save-to-storage!
  "Persist the chrome-visibility map. `:embed?` is stripped — embed
  is URL-driven (rf2-pucku) and persisting it would leak between
  navigations. Idempotent; silent on storage unavailability."
  [chrome-vis]
  (when-let [ls (safe-local-storage)]
    (try
      (.setItem ls ls-key
                (pr-str (select-keys chrome-vis
                                     [:full-screen?
                                      :sidebar?
                                      :rhs?
                                      :toolbar?])))
      (catch :default _ nil))))

(defn hydrate!
  "Seed `:chrome-visibility` from localStorage on shell mount. Idempotent
  — only writes when the persisted shape differs from the current state
  so we don't bounce a watcher save back onto storage."
  []
  (when-let [persisted (load-from-storage)]
    (let [shell @state/shell-state-atom
          current (state/chrome-visibility shell)
          merged  (merge current
                         (select-keys persisted
                                      [:full-screen?
                                       :sidebar?
                                       :rhs?
                                       :toolbar?]))]
      (when (not= current merged)
        (state/swap-state! assoc :chrome-visibility merged)))))

;; ---- dispatcher predicate ----------------------------------------------

(defn- target-is-input?
  "True when the keydown's target is an editable element — text input,
  textarea, contenteditable region. Hotkeys must yield to typing.

  We accept the focus-on-editable predicate verbatim from the standard
  pattern used by Storybook + VS Code: tag is `INPUT` / `TEXTAREA` /
  `SELECT`, OR the element carries `contenteditable=\"true\"`."
  [^js evt]
  (let [t (.-target evt)]
    (when t
      (let [tag (some-> (.-tagName t) str/upper-case)
            ce  (when (.-isContentEditable t) true)]
        (or ce
            (= tag "INPUT")
            (= tag "TEXTAREA")
            (= tag "SELECT"))))))

(defn- has-modifier?
  "True when any modifier key is held. The chrome hotkeys are deliberately
  modifier-less — meta / ctrl / alt are the palette / browser space."
  [^js evt]
  (or (.-metaKey evt) (.-ctrlKey evt) (.-altKey evt)))

(defn dispatch-key?
  "Pure predicate: should the given `(key, modifier?, editable?)`
  triple trigger a registered handler? Returns true only when no
  modifier is held AND the focus is not in an editable region. The
  bindings table itself is consulted by the caller — this fn lets
  the JVM corpus pin the discrimination logic.

  `key` arrives as the lowercase string from `event.key`."
  [key modifier? editable?]
  (boolean (and (not modifier?)
                (not editable?)
                (string? key)
                (= 1 (count key)))))

;; ---- canonical hotkey table --------------------------------------------

(defn full-screen-toggle!
  "Handler for the `f` key (rf2-p3i0t). Flips
  `[:chrome-visibility :full-screen?]` + persists. Escape exits via the
  separate `Escape`-listener in the canvas's full-screen overlay (see
  `canvas/full-screen-overlay`)."
  []
  (state/swap-state! state/toggle-chrome-visibility :full-screen?)
  (save-to-storage! (state/chrome-visibility (state/get-state))))

(defn sidebar-toggle!
  "Handler for the `s` key (rf2-g8l8x). Flips
  `[:chrome-visibility :sidebar?]` + persists."
  []
  (state/swap-state! state/toggle-chrome-visibility :sidebar?)
  (save-to-storage! (state/chrome-visibility (state/get-state))))

(defn rhs-toggle!
  "Handler for the `a` key (rf2-g8l8x — `a` for 'addons' per Storybook
  convention). Flips `[:chrome-visibility :rhs?]` + persists."
  []
  (state/swap-state! state/toggle-chrome-visibility :rhs?)
  (save-to-storage! (state/chrome-visibility (state/get-state))))

(defn toolbar-toggle!
  "Handler for the `t` key (rf2-g8l8x). Flips
  `[:chrome-visibility :toolbar?]` + persists."
  []
  (state/swap-state! state/toggle-chrome-visibility :toolbar?)
  (save-to-storage! (state/chrome-visibility (state/get-state))))

(defn exit-full-screen!
  "Public escape handler — clears `:full-screen?` regardless of prior
  state. Bound to `Escape` while full-screen is on."
  []
  (state/swap-state! state/set-chrome-visibility :full-screen? false)
  (save-to-storage! (state/chrome-visibility (state/get-state))))

(def bindings
  "Canonical `{key → handler}` table. Lowercase letters only — the
  dispatcher lowercases `event.key` before lookup. Cmd-K / Ctrl-K is
  excluded because the palette ships its own listener (it needs the
  modifier discrimination, so the registry pattern doesn't fit)."
  {"f" full-screen-toggle!
   "s" sidebar-toggle!
   "a" rhs-toggle!
   "t" toolbar-toggle!})

(defn shortcut-keys
  "Pure data → data: the sorted list of bound keys. Used by the help
  overlay's shortcut-table section so the rendered cheat-sheet stays
  in lockstep with the registry."
  []
  (sort (keys bindings)))

;; ---- listener install / teardown ---------------------------------------

(defonce ^:private listener-handle (atom nil))

(defn- dispatch!
  "The single capture-phase keydown handler. Looks up the lowercase key
  in `bindings`; calls the handler when the discrimination predicate
  passes. Also handles `Escape` → exit-full-screen when full-screen is
  active."
  [^js evt]
  (let [raw-key   (.-key evt)
        editable? (target-is-input? evt)
        modifier? (has-modifier? evt)
        shell     (state/get-state)
        chrome    (state/chrome-visibility shell)]
    (cond
      ;; Escape exits full-screen (rf2-p3i0t acceptance criterion). We
      ;; gate the Escape branch on `editable?` so typing Escape into a
      ;; focused input (e.g. the sidebar search) cancels the input
      ;; rather than exiting full-screen — that's the search's intent.
      ;; When full-screen is active there's NO visible input that would
      ;; need Escape anyway, so the gating is conservative.
      (and (= "Escape" raw-key)
           (:full-screen? chrome)
           (not editable?))
      (do (.preventDefault evt)
          (exit-full-screen!))

      :else
      (let [k (some-> raw-key str/lower-case)]
        (when (and (dispatch-key? k modifier? editable?)
                   (contains? bindings k))
          (.preventDefault evt)
          ((get bindings k)))))))

(defn install!
  "Install the global keydown listener. Idempotent — re-installing
  replaces the previous handler. Gated behind
  `re-frame.story.config/enabled?` so production builds DCE the lot."
  []
  (when config/enabled?
    (when-let [prev @listener-handle]
      (try (.removeEventListener js/window "keydown" prev true)
           (catch :default _ nil)))
    (let [h (fn [evt] (dispatch! evt))]
      (.addEventListener js/window "keydown" h true)
      (reset! listener-handle h))))

(defn remove!
  "Tear down the global keydown listener. Mirrors `install!`."
  []
  (when-let [h @listener-handle]
    (try (.removeEventListener js/window "keydown" h true)
         (catch :default _ nil))
    (reset! listener-handle nil)))
