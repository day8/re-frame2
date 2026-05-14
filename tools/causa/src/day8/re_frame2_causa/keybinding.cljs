(ns day8.re-frame2-causa.keybinding
  "Global Ctrl+Shift+C key listener. Per spec/007-UX-IA.md §Global
  shortcuts the toggle key is `Ctrl+Shift+C`.

  ## Idempotency

  Re-loading the preload namespace (shadow-cljs `:after-load`) must
  not re-attach the listener — a double-attach would fire `toggle!`
  twice per key press. We hold the attached fn under a `defonce`
  sentinel and skip re-attach when the sentinel is already set.

  ## OS conventions

  Ctrl+Shift+C is the agreed shortcut on every host OS. macOS users
  who prefer Cmd+Shift+C can swap in their browser's keyboard-
  shortcut UI; Phase 1 ships only the Ctrl-modifier path. macOS
  Safari sometimes maps Cmd+Shift+C to dev-tools' Inspect — Causa
  deliberately uses `ctrl` to avoid that collision.

  ## Phase 5 — Ctrl+Shift+/ co-pilot toggle (rf2-rccf3)

  Per spec/009-AI-CoPilot.md §Default state the co-pilot rail toggles
  on `Ctrl+Shift+/`. The listener routes the keypress through the
  `:rf.causa/copilot-toggle` event dispatched on the Causa frame, so
  the panel's open / closed state lives in Causa's app-db (not the
  host's)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.mount :as mount]))

(defonce ^:private attached?
  ;; Sentinel — true once the keydown listener is installed. defonce
  ;; means the value survives shadow-cljs `:after-load` reloads.
  (atom false))

(defn- ctrl-shift-key?
  "True when `event` is a Ctrl+Shift keydown (no Meta, no Alt) whose
  key payload satisfies `match?`, a `(fn [key code] truthy)` predicate
  over `KeyboardEvent.key` and `KeyboardEvent.code`. Factored so new
  global shortcuts can drop in without re-stating the modifier checks."
  [event match?]
  (let [^js e event]
    (and (.-ctrlKey e)
         (.-shiftKey e)
         (not (.-metaKey e))
         (not (.-altKey e))
         (match? (.-key e) (.-code e)))))

(defn- causa-toggle-key?
  "True when `event` is a Ctrl+Shift+C keydown. Checks the C key via
  both `key` (\"C\" / \"c\") and `code` (\"KeyC\"); some IME-active
  contexts only populate `code`."
  [event]
  (ctrl-shift-key?
    event
    (fn [k code]
      (or (= "C" k) (= "c" k) (= "KeyC" code)))))

(defn- copilot-toggle-key?
  "True when `event` is a Ctrl+Shift+/ keydown. Per spec/009-AI-
  CoPilot.md §Default state. Checks the slash key via both `key`
  (\"/\" / \"?\") and `code` (\"Slash\") — the Shift modifier shifts
  the printable character on most layouts."
  [event]
  (ctrl-shift-key?
    event
    (fn [k code]
      (or (= "/" k) (= "?" k) (= "Slash" code)))))

(defn- handle-keydown [^js event]
  (cond
    (causa-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        (mount/toggle!))

    (copilot-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        ;; Per spec/009-AI-CoPilot.md §Default state — Ctrl+Shift+/
        ;; toggles the co-pilot rail. Routed through Causa's frame so
        ;; the panel's open / closed state lands on :rf/causa, not the
        ;; host's :rf/default.
        (rf/with-frame :rf/causa
          (rf/dispatch [:rf.causa/copilot-toggle])))))

(defn attach!
  "Install the global Ctrl+Shift+C listener once. No-op on second +
  subsequent calls (the `attached?` sentinel survives reloads)."
  []
  (when (and (exists? js/document)
             (compare-and-set! attached? false true))
    (.addEventListener js/document "keydown" handle-keydown true))
  nil)

(defn detach!
  "Remove the listener. Intended for tests; production sessions never
  call this."
  []
  (when (and (exists? js/document)
             (compare-and-set! attached? true false))
    (.removeEventListener js/document "keydown" handle-keydown true))
  nil)

(defn attached?* []
  ;; Test introspection helper — answers 'is the keydown listener
  ;; currently installed?'. The arity-renamed name (`attached?*`)
  ;; avoids shadowing the `attached?` defonce atom.
  @attached?)
