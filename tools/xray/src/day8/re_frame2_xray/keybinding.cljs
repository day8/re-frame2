(ns day8.re-frame2-xray.keybinding
  "Global Ctrl+Shift+C key listener. Per spec/007-UX-IA.md §Global
  shortcuts the toggle key is `Ctrl+Shift+C`.

  ## Idempotency

  Re-loading the preload namespace (shadow-cljs `:after-load`) must
  not re-attach the listener — a double-attach would fire `toggle!`
  twice per key press. We hold the attached fn under a `defonce`
  sentinel and skip re-attach when the sentinel is already set.

  ## Hot-reload-safe detach (rf2-t2o6o)

  `add`/`removeEventListener` compare listeners by reference, and
  `handle-keydown` is a `defn` — shadow-cljs `:after-load` recompiles
  it to a fresh fn object. So `attach!` stashes the EXACT fn it hands
  to `addEventListener` under a `defonce` atom (`attached-fn`), and
  `detach!` removes that stored reference. Without the stash, a
  `detach!` referencing the bare `handle-keydown` var after a reload
  would `removeEventListener` a fn that was never added — silently
  leaking the original listener (the embed-host detach-after-reload
  hazard rf2-4eyik / rf2-q7who set out to close). Mirrors the
  `mount.cljs` unmount-fn stash.

  ## OS conventions

  Ctrl+Shift+C is the agreed shortcut on every host OS. macOS users
  who prefer Cmd+Shift+C can swap in their browser's keyboard-
  shortcut UI; Phase 1 ships only the Ctrl-modifier path. macOS
  Safari sometimes maps Cmd+Shift+C to dev-tools' Inspect — Xray
  deliberately uses `ctrl` to avoid that collision.

  ## Phase 5 — Cmd/Ctrl+K command palette (rf2-wm7z4)

  Per spec/007-UX-IA.md §Command palette the palette opens on
  Cmd+K (macOS convention) or Ctrl+K (every other host). Unlike the
  other shortcuts this one is a *single* modifier — no Shift — and
  must accept either Meta (Cmd) or Ctrl. The listener routes the
  keypress through `:rf.xray/palette-toggle` dispatched on the
  Xray frame so the modal's open state lives in Xray's app-db.

  The palette modal renders its own ESC handler on the input
  element, so this listener does NOT close the palette on ESC —
  doing so would race the input's onKeyDown and risk
  double-dispatch.

  ## Spine keybindings (rf2-adve5 — spec/018-Event-Spine.md §3 + §6)

  Five unmodified keys drive the spine sub:

      Space  →  :rf.xray/toggle-live-pause      (pause/resume LIVE feed)
      L      →  :rf.xray/follow-head            (snap-LIVE)
      j      →  :rf.xray/focus-event-prev       (step back through events)
      k      →  :rf.xray/focus-event-next       (step forward through events)
      G      →  :rf.xray/follow-head            (fast-forward to head)

  These keys collide with normal typing in any text field, so they
  fire ONLY when:

  1. The Xray shell is currently visible (`mount/visible?` true), AND
  2. The keydown event's target is inside the Xray shell DOM tree
     (`event.target.closest('[data-testid=rf-xray-shell]')` truthy), AND
  3. The target is NOT an editable element (`<input>`, `<textarea>`,
     `[contenteditable]`).

  The closest-ancestor check is the right discriminator: Xray's shell
  carries the `rf-xray-shell` testid; the host app doesn't. The
  editable-element guard means even a future Xray-side input field
  (e.g. the filter-pill edit popup) doesn't fight the user typing."
  (:require [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.mount :as mount]))

(defonce ^:private attached-state
  ;; Sentinel — true once the keydown listener is installed. defonce
  ;; means the value survives shadow-cljs `:after-load` reloads.
  ;; Named `-state` (not `attached?`) so the test-introspection
  ;; predicate can keep the user-facing `attached?` name without
  ;; shadowing.
  (atom false))

(defonce ^:private attached-fn
  ;; rf2-t2o6o — the EXACT fn object handed to `addEventListener`, stashed
  ;; under a `defonce` atom so `detach!` removes the same reference that
  ;; `attach!` added.
  ;;
  ;; `add`/`removeEventListener` compare listeners by reference.
  ;; `handle-keydown` is a `defn`, so shadow-cljs `:after-load`
  ;; recompiles it to a NEW fn object. The `defonce attached-state`
  ;; sentinel correctly keeps `attach!` a no-op across reloads (the
  ;; ORIGINAL closure stays bound — no double-attach), but a `detach!`
  ;; that referenced the bare `handle-keydown` var would pass the
  ;; freshly-recompiled object — never the one actually attached — so
  ;; `removeEventListener` would silently match nothing and leak the
  ;; original listener (the embed-host detach-after-reload hazard).
  ;;
  ;; Stashing the attached fn here (mirroring mount.cljs's unmount-fn
  ;; stash) makes attach/detach reference-stable across reloads: detach
  ;; always removes precisely what attach installed. nil when nothing is
  ;; attached.
  (atom nil))

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

(defn- xray-toggle-key?
  "True when `event` is a Ctrl+Shift+C keydown. Checks the C key via
  both `key` (\"C\" / \"c\") and `code` (\"KeyC\"); some IME-active
  contexts only populate `code`."
  [event]
  (ctrl-shift-key?
    event
    (fn [k code]
      (or (= "C" k) (= "c" k) (= "KeyC" code)))))

(defn- mode-toggle-key?
  "True when `event` is the Xray Dynamic ↔ Static mode toggle —
  Cmd+Shift+M on macOS or Ctrl+Shift+M everywhere else (rf2-o5f5f.1).

  Per the parent epic's architectural-lock decision (2026-05-19):
  Cmd-Shift-M is the chord — a paired letter that doesn't collide
  with the existing Ctrl+Shift+C (toggle shell), Cmd/Ctrl+K (palette),
  or the bare-letter spine bindings (Space / L / j / k / G).

  Accepts EITHER Cmd OR Ctrl as the primary modifier so mac users
  get muscle-memory Cmd and Windows/Linux users get Ctrl — the same
  shape as `palette-toggle-key?` above. Shift is required to keep
  the chord from colliding with Ctrl+M (Firefox 'duplicate tab' on
  some platforms) and Cmd+M (macOS 'minimize window')."
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

  Unlike the xray toggle this predicate is a *single* modifier:
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

(defn- escape-key?
  "True when `event` is a bare Escape keydown (no modifier required —
  Esc is never chorded). Checks both `key` spellings (`\"Escape\"` is
  the modern value; `\"Esc\"` the legacy IE/Edge value some embedded
  webviews still emit). Used to route Esc to the editor-hint toast's
  dismissal (rf2-wpvy6f)."
  [^js event]
  (let [k (.-key event)]
    (or (= "Escape" k) (= "Esc" k))))

(defn- editor-hint-open?
  "True when the open-in-editor hint toast is currently open on the
  Xray shell frame. Read DIRECTLY off the `:rf/xray` frame's app-db
  (`frame/frame-app-db-value`) rather than via a subscription — this
  global keydown listener runs outside any frame/reaction context, so a
  plain app-db read is the correct seam. Returns false when the frame
  does not yet exist (shell never opened) so Esc falls through to the
  host untouched. Per rf2-wpvy6f."
  []
  (boolean (:editor-hint-open? (frame/frame-app-db-value defaults/default-frame-id))))

(defn- target-inside-xray?
  "True when `event.target` is a DOM node inside the Xray shell — the
  ancestor walk hits the `data-testid=\"rf-xray-shell\"` envelope."
  [^js event]
  (when-let [target (.-target event)]
    (when (and target (.-closest target))
      (boolean (.closest target "[data-testid=\"rf-xray-shell\"]")))))

(defn- target-editable?
  "True when `event.target` is a text-input surface where unmodified
  letter keys would otherwise type characters into a field — even
  inside Xray's shell those keys belong to the field, not the spine."
  [^js event]
  (when-let [^js target (.-target event)]
    (let [tag (some-> target .-tagName .toUpperCase)]
      (or (= tag "INPUT")
          (= tag "TEXTAREA")
          (= tag "SELECT")
          ;; rf2-t2o6o tidy — the prior `(and (.-isContentEditable t)
          ;; (boolean (.-isContentEditable t)))` double-read was dead;
          ;; one coerced read carries the same truth value.
          (boolean (.-isContentEditable target))))))

(defn- target-inside-modal?
  "True when `event.target` is a DOM node inside one of Xray's modal
  surfaces (Settings popup, command palette) — identified by the
  `data-rf-xray-mode` attribute set to a known modal value on the
  dialog root. Used to suppress the bare-letter spine bindings
  (`s`, `,`, etc.) while a modal owns the keyboard, so those keys
  can carry their modal-only inner meaning instead of re-toggling
  the parent modal or firing the spine event-bundle. Per rf2-ttnst."
  [^js event]
  (when-let [target (.-target event)]
    (when (and target (.-closest target))
      (let [hit (.closest target "[data-rf-xray-mode=\"settings\"], [data-rf-xray-mode=\"palette\"]")]
        (boolean hit)))))

(defn- spine-key-id
  "Map an unmodified keydown to the spine event id it dispatches, or
  nil when the key is not a spine binding. Per spec/018 §3 + §6 the
  five bindings are Space / L / j / k / G. Bare-key (no modifiers)
  is required so the bindings don't collide with browser shortcuts
  (Cmd+L → focus address bar, Ctrl+Shift+C → Xray toggle, etc.)."
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
        (when-not shift? :rf.xray/toggle-live-pause)

        ;; L (lowercase, unshifted) — snap to LIVE
        (and (not shift?) (or (= "l" k) (= "KeyL" code)))
        :rf.xray/follow-head

        ;; G (uppercase, Shift+G per the spec; mnemonic 'Go to head')
        (and shift? (or (= "G" k) (= "KeyG" code)))
        :rf.xray/follow-head

        ;; j — step backward (vim convention reused)
        (and (not shift?) (or (= "j" k) (= "KeyJ" code)))
        :rf.xray/focus-event-prev

        ;; k — step forward
        (and (not shift?) (or (= "k" k) (= "KeyK" code)))
        :rf.xray/focus-event-next

        ;; , or s — toggle Settings popup. Per spec/007-UX-IA.md
        ;; §Global shortcuts both bindings open the modal (the spec
        ;; lists "`,` or `s`"). The popup carries its own ESC/click-
        ;; outside close handlers so re-pressing the same key while
        ;; the modal is open is not required (and is intercepted by
        ;; the modal's inner tab mnemonic — `s` would otherwise
        ;; re-toggle).
        (and (not shift?) (or (= "," k) (= "Comma" code)))
        :rf.xray/settings-toggle

        (and (not shift?) (or (= "s" k) (= "KeyS" code)))
        :rf.xray/settings-toggle))))

(defn- handle-keydown [^js event]
  (cond
    (xray-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        (mount/toggle!))

    ;; rf2-o5f5f.1 — Cmd-Shift-M flips Dynamic ↔ Static. Always
    ;; wired; the chord owns this keystroke for Xray (per
    ;; rf2-8l3uk — the Static-mode feature gate was removed, Static
    ;; mode is unconditionally available).
    (mode-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        (rf/with-frame :rf/xray
          (rf/dispatch [:rf.xray/toggle-mode])))

    ;; rf2-wpvy6f — Esc dismisses the open-in-editor hint toast. The
    ;; toast is a non-modal `role=status` bottom-corner toast: it must
    ;; NOT trap focus (that would steal it from the host app), so its own
    ;; in-DOM `on-key-down` never receives Esc in the normal click flow
    ;; (focus is wherever the user clicked the chip, not inside the
    ;; toast). The shell-level global listener is the reachable Esc path.
    ;; Gated on the toast actually being open so Esc falls through to the
    ;; host (and to other Esc consumers) whenever the toast is closed —
    ;; the listener only consumes the key it acts on. Dispatched on the
    ;; `:rf/xray` shell frame where the hint state lives.
    (and (escape-key? event) (editor-hint-open?))
    (do (.preventDefault event)
        (.stopPropagation event)
        (rf/dispatch [:rf.xray/editor-hint-dismiss]
                     {:frame defaults/default-frame-id}))

    (palette-toggle-key? event)
    (do (.preventDefault event)
        (.stopPropagation event)
        ;; Per spec/007-UX-IA.md §Command palette — Cmd/Ctrl+K
        ;; toggles the command palette modal. Open the shell first
        ;; if it's not visible so the palette has somewhere to mount;
        ;; the toggle dispatch is routed through Xray's frame so the
        ;; palette open-state lives on :rf/xray.
        (when-not (mount/visible?)
          (mount/open!))
        (rf/with-frame :rf/xray
          (rf/dispatch [:rf.xray/palette-toggle])))

    ;; Spine bindings — only fire inside the Xray shell, never on
    ;; editable elements. Per spec/018 §3 + §6 the keys are
    ;; Space / L / j / k / G.
    ;;
    ;; rf2-ttnst — also gate on "not inside a modal". The Settings
    ;; popup and command palette each carry bare-letter mnemonics
    ;; (g/t/f/k/b/d, fuzzy-typing in palette, etc.) that must NOT
    ;; also drive the spine. The modal markers are read via
    ;; `target-inside-modal?` which closest-walks the event target
    ;; for `data-rf-xray-mode="settings"|"palette"`.
    :else
    (when (and (mount/visible?)
               (target-inside-xray? event)
               (not (target-editable? event))
               (not (target-inside-modal? event)))
      (when-let [event-id (spine-key-id event)]
        (.preventDefault event)
        (.stopPropagation event)
        (rf/with-frame :rf/xray
          (rf/dispatch [event-id]))))))

(defn attach!
  "Install the global Ctrl+Shift+C listener once. No-op on second +
  subsequent calls (the `attached-state` sentinel survives reloads).

  Honours the `:rf.xray/keybinding-enabled?` config slot (rf2-4eyik —
  rf2-q7who Thread A). When the slot is `false` the listener is NOT
  installed: embed hosts (Story RHS, third-party tool surfaces) flip
  the slot before the preload runs so their own global keybindings
  (typically `Cmd/Ctrl+K` for the host's command palette) are not
  swallowed by Xray's capture-phase listener. Standalone Xray
  (default, slot = `true`) attaches as before."
  []
  (when (and (exists? js/document)
             (config/keybinding-attach-enabled?)
             (compare-and-set! attached-state false true))
    ;; rf2-t2o6o — capture the EXACT fn we hand to addEventListener so
    ;; detach! can remove this same object even after a hot reload
    ;; rebinds the `handle-keydown` var to a fresh fn.
    (let [f handle-keydown]
      (reset! attached-fn f)
      (.addEventListener js/document "keydown" f true)))
  nil)

(defn detach!
  "Remove the global keydown listener if one is currently attached.
  Idempotent — safe to call when nothing is attached (no-op), and safe
  to call twice in a row (the second call is a no-op).

  Public embed-host escape hatch (rf2-ycrt2 — rf2-q7who.1 follow-on).
  The `:rf.xray/keybinding-enabled?` config slot suppresses installation
  only when read at attach time; embed hosts whose mount lifecycle
  (e.g. Story's `ensure-xray-mounted!`) flips the slot AFTER Xray's
  preload has already run must call `detach!` to remove the listener
  that the preload installed under the default-true posture. Symmetric
  with `attach!`; calling them in sequence (`attach! → detach! →
  attach!`) flips between attached / not-attached cleanly without
  leaking listeners or stale sentinel state."
  []
  (when (and (exists? js/document)
             (compare-and-set! attached-state true false))
    ;; rf2-t2o6o — remove the EXACT fn attach! stored, not the (possibly
    ;; hot-reloaded) `handle-keydown` var. Falling back to the var keeps
    ;; the call safe if the stash is somehow empty while the sentinel is
    ;; true (defensive; the CAS above guarantees we only reach here when
    ;; attach! ran and set the stash).
    (let [f (or @attached-fn handle-keydown)]
      (.removeEventListener js/document "keydown" f true)
      (reset! attached-fn nil)))
  nil)

(defn attached?
  "Test introspection helper — answers 'is the keydown listener
  currently installed?'. Reads the `attached-state` defonce atom."
  []
  @attached-state)
