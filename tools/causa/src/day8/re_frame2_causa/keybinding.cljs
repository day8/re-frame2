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
  double-dispatch.

  ## Spine keybindings (rf2-adve5 — spec/018-Event-Spine.md §3 + §6)

  Five unmodified keys drive the spine sub:

      Space  →  :rf.causa/toggle-live-pause      (pause/resume LIVE feed)
      L      →  :rf.causa/follow-head            (snap-LIVE)
      j      →  :rf.causa/focus-cascade-prev     (step back through events)
      k      →  :rf.causa/focus-cascade-next     (step forward through events)
      G      →  :rf.causa/follow-head            (fast-forward to head)

  These keys collide with normal typing in any text field, so they
  fire ONLY when:

  1. The Causa shell is currently visible (`mount/visible?` true), AND
  2. The keydown event's target is inside the Causa shell DOM tree
     (`event.target.closest('[data-testid=rf-causa-shell]')` truthy), AND
  3. The target is NOT an editable element (`<input>`, `<textarea>`,
     `[contenteditable]`).

  The closest-ancestor check is the right discriminator: Causa's shell
  carries the `rf-causa-shell` testid; the host app doesn't. The
  editable-element guard means even a future Causa-side input field
  (e.g. the filter-pill edit popup) doesn't fight the user typing."
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

(defn- palette-toggle-key?
  "True when `event` is a Cmd+K (macOS) or Ctrl+K (every other host)
  keydown. Per spec/007-UX-IA.md §Command palette this is the
  industry-standard 'open command palette' shortcut (VS Code, Linear,
  GitHub, Slack).

  Unlike the causa toggle this predicate is a *single* modifier:
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

(defn- target-inside-causa?
  "True when `event.target` is a DOM node inside the Causa shell — the
  ancestor walk hits the `data-testid=\"rf-causa-shell\"` envelope."
  [^js event]
  (when-let [target (.-target event)]
    (when (and target (.-closest target))
      (boolean (.closest target "[data-testid=\"rf-causa-shell\"]")))))

(defn- target-editable?
  "True when `event.target` is a text-input surface where unmodified
  letter keys would otherwise type characters into a field — even
  inside Causa's shell those keys belong to the field, not the spine."
  [^js event]
  (when-let [^js target (.-target event)]
    (let [tag (some-> target .-tagName .toUpperCase)]
      (or (= tag "INPUT")
          (= tag "TEXTAREA")
          (= tag "SELECT")
          (and (.-isContentEditable target)
               (boolean (.-isContentEditable target)))))))

(defn- spine-key-id
  "Map an unmodified keydown to the spine event id it dispatches, or
  nil when the key is not a spine binding. Per spec/018 §3 + §6 the
  five bindings are Space / L / j / k / G; per spec/018 §10 + §11 the
  Causality popover binds unmodified `c`. Bare-key (no modifiers)
  is required so the bindings don't collide with browser shortcuts
  (Cmd+L → focus address bar, Ctrl+Shift+C → Causa toggle, etc.)."
  [^js event]
  (when (and (not (.-ctrlKey event))
             (not (.-metaKey event))
             (not (.-altKey event)))
    (let [k     (.-key event)
          code  (.-code event)
          shift? (.-shiftKey event)]
      (cond
        ;; Space — pause/resume LIVE feed
        (or (= " " k) (= "Space" code) (= "Spacebar" k))
        (when-not shift? :rf.causa/toggle-live-pause)

        ;; L (lowercase, unshifted) — snap to LIVE
        (and (not shift?) (or (= "l" k) (= "KeyL" code)))
        :rf.causa/follow-head

        ;; G (uppercase, Shift+G per the spec; mnemonic 'Go to head')
        (and shift? (or (= "G" k) (= "KeyG" code)))
        :rf.causa/follow-head

        ;; j — step backward (vim convention reused)
        (and (not shift?) (or (= "j" k) (= "KeyJ" code)))
        :rf.causa/focus-cascade-prev

        ;; k — step forward
        (and (not shift?) (or (= "k" k) (= "KeyK" code)))
        :rf.causa/focus-cascade-next

        ;; c — toggle Causality popover. Per spec/018 §10 §11 the
        ;; popover replaces the dropped Causality tab; `c` from any
        ;; tab opens it, second `c` closes it. The toggle event lives
        ;; in popover/causality_events.cljs.
        (and (not shift?) (or (= "c" k) (= "KeyC" code)))
        :rf.causa/causality-popover-toggle))))

(defn- handle-keydown [^js event]
  (cond
    (causa-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        (mount/toggle!))

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
          (rf/dispatch [:rf.causa/palette-toggle])))

    ;; Spine bindings — only fire inside the Causa shell, never on
    ;; editable elements. Per spec/018 §3 + §6 the keys are
    ;; Space / L / j / k / G.
    :else
    (when (and (mount/visible?)
               (target-inside-causa? event)
               (not (target-editable? event)))
      (when-let [event-id (spine-key-id event)]
        (.preventDefault event)
        (.stopPropagation event)
        (rf/with-frame :rf/causa
          (rf/dispatch [event-id]))))))

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
