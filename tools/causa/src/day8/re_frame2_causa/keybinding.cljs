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
            [day8.re-frame2-causa.config :as config]
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

(defn- mode-toggle-key?
  "True when `event` is the Causa Runtime ↔ Static mode toggle —
  Cmd+Shift+M on macOS or Ctrl+Shift+M everywhere else (rf2-o5f5f.1).

  Per the parent epic's architectural-lock decision (2026-05-19):
  Cmd-Shift-M is the chord — a paired letter that doesn't collide
  with the existing Ctrl+Shift+C (toggle shell), Cmd/Ctrl+K (palette),
  or the bare-letter spine bindings (Space / L / j / k / G).

  Accepts EITHER Cmd OR Ctrl as the primary modifier so mac users
  get muscle-memory Cmd and Windows/Linux users get Ctrl — the same
  shape as `palette-toggle-key?` above. Shift is required to keep
  the chord from colliding with Ctrl+M (Firefox 'duplicate tab' on
  some platforms) and Cmd+M (macOS 'minimize window').

  Honours the `:rf.causa/static-mode?` flag — the listener only
  fires when the flag is on so a host that hasn't opted into Static
  mode never sees the chord intercepted (the chord falls through to
  whatever the host or browser binding would otherwise do)."
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
         shift?
         (not alt?)
         (or (= "M" k) (= "m" k) (= "KeyM" code)))))

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

(defn- target-inside-modal?
  "True when `event.target` is a DOM node inside one of Causa's modal
  surfaces (Settings popup, command palette) — identified by the
  `data-rf-causa-mode` attribute set to a known modal value on the
  dialog root. Used to suppress the bare-letter spine bindings
  (`s`, `,`, etc.) while a modal owns the keyboard, so those keys
  can carry their modal-only inner meaning instead of re-toggling
  the parent modal or firing the spine cascade. Per rf2-ttnst."
  [^js event]
  (when-let [target (.-target event)]
    (when (and target (.-closest target))
      (let [hit (.closest target "[data-rf-causa-mode=\"settings\"], [data-rf-causa-mode=\"palette\"]")]
        (boolean hit)))))

(defn- spine-key-id
  "Map an unmodified keydown to the spine event id it dispatches, or
  nil when the key is not a spine binding. Per spec/018 §3 + §6 the
  five bindings are Space / L / j / k / G. Bare-key (no modifiers)
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

        ;; , or s — toggle Settings popup. Per spec/007-UX-IA.md
        ;; §Global shortcuts both bindings open the modal (the spec
        ;; lists "`,` or `s`"). The popup carries its own ESC/click-
        ;; outside close handlers so re-pressing the same key while
        ;; the modal is open is not required (and is intercepted by
        ;; the modal's inner tab mnemonic — `s` would otherwise
        ;; re-toggle).
        (and (not shift?) (or (= "," k) (= "Comma" code)))
        :rf.causa/settings-toggle

        (and (not shift?) (or (= "s" k) (= "KeyS" code)))
        :rf.causa/settings-toggle

        ;; Esc — clear focus-set (rf2-a1z3b). The focus primitive is a
        ;; lens (NOT a filter); Esc is the universal 'undo the lens'
        ;; gesture. Modals + popovers register their own Escape
        ;; handlers on their input elements (the palette / settings
        ;; handlers preventDefault + stopPropagation before this
        ;; listener fires), so Esc here only reaches the focus clear
        ;; when no modal is open. When no focus-set is active the event
        ;; handler is a no-op (`clear-focus-reducer` dissocs an absent
        ;; slot).
        (and (not shift?) (or (= "Escape" k) (= "Escape" code) (= "Esc" k)))
        :rf.causa/clear-focus))))

(defn- handle-keydown [^js event]
  (cond
    (causa-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        (mount/toggle!))

    ;; rf2-o5f5f.1 — Cmd-Shift-M flips Runtime ↔ Static. Gated on
    ;; `:rf.causa/static-mode?` so hosts that haven't opted in
    ;; never see the chord intercepted. When the flag is OFF the
    ;; chord falls through to whatever else would consume it
    ;; (browser / host binding). When the flag is ON we
    ;; `preventDefault` + `stopPropagation` because the chord owns
    ;; this keystroke for Causa.
    (and (config/static-mode-enabled?)
         (mode-toggle-key? event))
    (do (.preventDefault event)
        (.stopPropagation event)
        (rf/with-frame :rf/causa
          (rf/dispatch [:rf.causa/toggle-mode])))

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
    ;;
    ;; rf2-ttnst — also gate on "not inside a modal". The Settings
    ;; popup and command palette each carry bare-letter mnemonics
    ;; (g/t/f/k/b/d, fuzzy-typing in palette, etc.) that must NOT
    ;; also drive the spine. The modal markers are read via
    ;; `target-inside-modal?` which closest-walks the event target
    ;; for `data-rf-causa-mode="settings"|"palette"`.
    :else
    (when (and (mount/visible?)
               (target-inside-causa? event)
               (not (target-editable? event))
               (not (target-inside-modal? event)))
      (when-let [event-id (spine-key-id event)]
        (.preventDefault event)
        (.stopPropagation event)
        (rf/with-frame :rf/causa
          (rf/dispatch [event-id]))))))

(defn attach!
  "Install the global Ctrl+Shift+C listener once. No-op on second +
  subsequent calls (the `attached-state` sentinel survives reloads).

  Honours the `:rf.causa/keybinding-enabled?` config slot (rf2-4eyik —
  rf2-q7who Thread A). When the slot is `false` the listener is NOT
  installed: embed hosts (Story RHS, third-party tool surfaces) flip
  the slot before the preload runs so their own global keybindings
  (typically `Cmd/Ctrl+K` for the host's command palette) are not
  swallowed by Causa's capture-phase listener. Standalone Causa
  (default, slot = `true`) attaches as before."
  []
  (when (and (exists? js/document)
             (config/keybinding-attach-enabled?)
             (compare-and-set! attached-state false true))
    (.addEventListener js/document "keydown" handle-keydown true))
  nil)

(defn detach!
  "Remove the global keydown listener if one is currently attached.
  Idempotent — safe to call when nothing is attached (no-op), and safe
  to call twice in a row (the second call is a no-op).

  Public embed-host escape hatch (rf2-ycrt2 — rf2-q7who.1 follow-on).
  The `:rf.causa/keybinding-enabled?` config slot suppresses installation
  only when read at attach time; embed hosts whose mount lifecycle
  (e.g. Story's `ensure-causa-mounted!`) flips the slot AFTER Causa's
  preload has already run must call `detach!` to remove the listener
  that the preload installed under the default-true posture. Symmetric
  with `attach!`; calling them in sequence (`attach! → detach! →
  attach!`) flips between attached / not-attached cleanly without
  leaking listeners or stale sentinel state."
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
