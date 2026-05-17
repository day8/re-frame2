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
  host's).

  ## Phase 5 — Cmd/Ctrl+K command palette (rf2-wm7z4)

  Per spec/007-UX-IA.md §Command palette the palette opens on
  Cmd+K (macOS convention) or Ctrl+K (every other host). Unlike the
  other shortcuts this one is a *single* modifier — no Shift — and
  must accept either Meta (Cmd) or Ctrl. The listener routes the
  keypress through `:rf.causa/palette-toggle` dispatched on the
  Causa frame so the modal's open state lives in Causa's app-db.

  The palette modal renders its own ESC handler on the input
  element, so this listener does NOT close the palette on ESC —
  doing so would race the input's onKeyDown and risk
  double-dispatch."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.mount :as mount]))

(defonce ^:private attached-state
  ;; Sentinel — true once the keydown listener is installed. defonce
  ;; means the value survives shadow-cljs `:after-load` reloads.
  ;; Named `-state` (not `attached?`) so the test-introspection
  ;; predicate can keep the user-facing `attached?` name without
  ;; shadowing.
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

(defn- palette-toggle-key?
  "True when `event` is a Cmd+K (macOS) or Ctrl+K (every other host)
  keydown. Per spec/007-UX-IA.md §Command palette this is the
  industry-standard 'open command palette' shortcut (VS Code, Linear,
  GitHub, Slack).

  Unlike the causa / copilot toggles this predicate is a *single*
  modifier:
  - meta XOR ctrl (exactly one) — Cmd on macOS, Ctrl elsewhere.
  - no Shift / no Alt.

  Allowing both Cmd and Ctrl gives mac users their muscle-memory
  Cmd+K while keeping Ctrl+K live on Windows/Linux where there is
  no Cmd key. Rejecting Shift / Alt means we don't collide with
  printable-character shortcuts (Ctrl+Shift+K is browser dev-tools
  on Firefox; Ctrl+Alt+K is some IME compositions). Checks the K
  key via both `.key` and `.code`."
  [event]
  (let [^js e event
        ctrl?  (.-ctrlKey e)
        meta?  (.-metaKey e)
        shift? (.-shiftKey e)
        alt?   (.-altKey e)
        k      (.-key e)
        code   (.-code e)]
    (and (or (and ctrl? (not meta?))
             (and meta? (not ctrl?)))
         (not shift?)
         (not alt?)
         (or (= "k" k) (= "K" k) (= "KeyK" code)))))

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
          (rf/dispatch [:rf.causa/copilot-toggle])))

    (palette-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        ;; Per spec/007-UX-IA.md §Command palette — Cmd/Ctrl+K
        ;; toggles the command palette modal. Open the shell first
        ;; if it's not visible so the palette has somewhere to mount;
        ;; the toggle dispatch is routed through Causa's frame so the
        ;; palette open-state lives on :rf/causa.
        (when-not (mount/visible?)
          (mount/open!))
        (rf/with-frame :rf/causa
          (rf/dispatch [:rf.causa/palette-toggle])))))

(defn attach!
  "Install the global Ctrl+Shift+C listener once. No-op on second +
  subsequent calls (the `attached-state` sentinel survives reloads)."
  []
  (when (and (exists? js/document)
             (compare-and-set! attached-state false true))
    (.addEventListener js/document "keydown" handle-keydown true))
  nil)

(defn detach!
  "Remove the listener. Intended for tests; production sessions never
  call this."
  []
  (when (and (exists? js/document)
             (compare-and-set! attached-state true false))
    (.removeEventListener js/document "keydown" handle-keydown true))
  nil)

(defn attached?
  "Test introspection helper — answers 'is the keydown listener
  currently installed?'. Reads the `attached-state` defonce atom."
  []
  @attached-state)
