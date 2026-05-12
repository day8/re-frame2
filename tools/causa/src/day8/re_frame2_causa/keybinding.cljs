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
  deliberately uses `ctrl` to avoid that collision."
  (:require [day8.re-frame2-causa.mount :as mount]))

(defonce ^:private attached?
  ;; Sentinel — true once the keydown listener is installed. defonce
  ;; means the value survives shadow-cljs `:after-load` reloads.
  (atom false))

(defn- causa-toggle-key?
  "True when `event` is a Ctrl+Shift+C keydown. Checks the C key via
  both `key` (\"C\" / \"c\") and `code` (\"KeyC\"); some IME-active
  contexts only populate `code`."
  [event]
  (let [^js e event
        k         (.-key e)
        code      (.-code e)
        ctrl?     (.-ctrlKey e)
        shift?    (.-shiftKey e)
        meta?     (.-metaKey e)
        alt?      (.-altKey e)]
    (and ctrl?
         shift?
         (not meta?)
         (not alt?)
         (or (= "C" k)
             (= "c" k)
             (= "KeyC" code)))))

(defn- handle-keydown [^js event]
  (when (causa-toggle-key? event)
    (.preventDefault event)
    (.stopPropagation event)
    (mount/toggle!)))

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
