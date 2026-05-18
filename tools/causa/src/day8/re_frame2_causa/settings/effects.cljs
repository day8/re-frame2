(ns day8.re-frame2-causa.settings.effects
  "Side-effect appliers for the Causa Settings popup (rf2-9poxq).

  Each setting that needs DOM / shell-state plumbing has a 1-arg
  `apply-*!` fn here. The popup's events handler (and the preload's
  initial restoration path) call these directly on every setting
  change.

  ## What each setting effects

  - **text-size** — written as a CSS custom property
    (`--rf-causa-text-size`) on the Causa shell root + on the
    `<html>` root so any Causa-owned surface (even out-of-tree
    portals) inherits the value.

  - **theme** — toggled as a CSS class on the Causa shell root.
    `rf-causa-theme-light` vs `rf-causa-theme-dark`. Default panels
    sit on the dark tokens; the light-theme variant is a follow-on
    styling pass — the class lands so a future stylesheet can
    target it.

  - **panel-position** — routes to existing mount-layer fns:
    `:right-rail` is the default inline mount; `:popout` opens the
    popout window via `mount/popout!`; `:fullscreen` mounts as the
    overlay via `mount/open-overlay!`. Switching position does not
    tear down the current mount — the user can sit in two surfaces
    at once if they choose. A follow-on bead can lock the
    enumeration to single-mount-at-a-time.

  - **auto-open-on-error?** — a re-frame subscription watcher set
    up by `install-auto-open-watcher!`. When the issues-ribbon sub
    flips from empty → non-empty AND the toggle is on AND Causa is
    not already visible, dispatches `mount/open!`. The watcher is
    installed lazily from `mount/ensure-causa-frame!` (on first
    Causa open, IFF the persisted toggle is on) and on flip-on
    inside `:rf.causa/settings-update`. Detached on flip-off. Both
    paths are idempotent; the install is a defensive no-op when
    the `:rf/causa` frame isn't yet registered (a pre-mount call
    would `(add-watch nil ...)` and throw under Story testbeds
    that never open Causa).

  ## Why a separate ns

  The popup's `events.cljs` writes through to the in-memory atom +
  app-db; the DOM/shell-state effects belong to a layer the events
  ns shouldn't drag in (mount.cljs imports shell.cljs which imports
  every panel — pulling that chain into events would inflate the
  popup's compile-time graph for no benefit). Keeping the effects
  here means events.cljs stays pure-data + reactive.

  ## Why we late-bind mount via the browser API export

  A direct `[day8.re-frame2-causa.mount]` require here would form
  the cycle: `mount → shell → registry → settings/popup →
  settings/events → settings/effects → mount`. The shell mounts the
  settings modal; the modal events drive these effects; the effects
  lower into mount actions; mount requires the shell to render.
  Same break the palette uses (palette.events §mount-popout!): reach
  mount fns via the browser API exports the preload installs on
  `window.day8.re_frame2_causa.{open_BANG_, popout_BANG_,
  open_overlay_BANG_, ...}`. Late-bound at apply-time, not at
  ns-load time — preload runs BEFORE the user can dispatch a
  settings-update so the export is always present in production
  paths."
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-causa.config :as config]))

;; ---- mount late-bind helpers --------------------------------------------

(defn- mount-fn
  "Late-bind one of the mount-layer fns via the browser API export
  the preload installs on `window.day8.re_frame2_causa.<name>`.
  Returns nil when the export is unreachable (no browser, preload
  not loaded — e.g. JVM tests). Caller is responsible for the
  no-op-on-nil semantics."
  [export-name]
  (try
    (when (exists? js/window)
      (let [day8  (gobj/get js/window "day8")
            causa (when day8 (gobj/get day8 "re_frame2_causa"))]
        (when causa (gobj/get causa export-name))))
    (catch :default _ nil)))

(defn- call-mount-fn!
  "Invoke a late-bound mount fn by export name. Swallows throws so a
  popup-blocked / mount-disabled failure never poisons the dispatch
  chain that drove the setting update."
  [export-name]
  (try
    (when-let [f (mount-fn export-name)]
      (f))
    (catch :default _ nil)))

(defn- visible-shell?
  "Late-bind `mount/visible?` via the status export. The preload
  exposes `mount/status` as `window.day8.re_frame2_causa.status` —
  a zero-arg fn returning a map with `:visible?`. Returns false
  when the export is unreachable."
  []
  (try
    (when-let [status-fn (mount-fn "status")]
      (boolean (:visible? (js->clj (status-fn) :keywordize-keys true))))
    (catch :default _ false)))

;; ---- DOM helpers ---------------------------------------------------------

(defn- shell-root-element
  "Resolve the `#rf-causa-root` element if Causa is mounted. Returns
  nil otherwise — every effect that touches the shell root must
  no-op on nil so pre-mount calls remain harmless."
  []
  (when (exists? js/document)
    (.getElementById js/document "rf-causa-root")))

(defn- html-root-element
  "The `<html>` element. Always present in a real browser; nil under
  Node test runtimes that don't simulate a document."
  []
  (when (exists? js/document)
    (.-documentElement js/document)))

;; ---- text-size ----------------------------------------------------------

(def text-size-css-var
  "Name of the CSS custom property the text-size slider writes. Causa
  surfaces that want to honour the slider read
  `var(--rf-causa-text-size, 13px)`. Mirrors the
  `default-layout-host-css-var` / `default-accent-css-var` naming
  pattern from `config.cljc`."
  "--rf-causa-text-size")

(defn apply-text-size!
  "Write `px` as the value of `--rf-causa-text-size` on both the
  Causa shell root (so descendant Causa styles inherit it) and the
  `<html>` element (so popout / fullscreen mounts that may not be
  inside the inline shell root still inherit). No-op when neither
  element is present (test runtimes)."
  [px]
  (let [value (str px "px")]
    (when-let [root (shell-root-element)]
      (.setProperty (.-style root) text-size-css-var value))
    (when-let [html (html-root-element)]
      (.setProperty (.-style html) text-size-css-var value)))
  nil)

;; ---- theme --------------------------------------------------------------

(def theme-class-prefix
  "CSS class prefix the theme switch toggles. `<root>.classList`
  carries either `rf-causa-theme-dark` or `rf-causa-theme-light`.
  Future themes (high-contrast, etc.) extend the enumeration."
  "rf-causa-theme-")

(defn apply-theme!
  "Replace any existing `rf-causa-theme-*` class on the shell root +
  `<html>` element with `rf-causa-theme-<name>`. No-op when neither
  element is present."
  [theme]
  (let [klass (str theme-class-prefix (name (or theme :dark)))]
    (doseq [el [(shell-root-element) (html-root-element)]
            :when el]
      (let [cl (.-classList el)]
        ;; Drop any existing theme class so the toggle is exclusive.
        (doseq [c [(str theme-class-prefix "dark")
                   (str theme-class-prefix "light")]]
          (try (.remove cl c) (catch :default _ nil)))
        (try (.add cl klass) (catch :default _ nil)))))
  nil)

;; ---- panel width (rf2-x8h9y resize handle) ------------------------------

(def panel-width-css-var
  "Name of the CSS custom property the resize handle drives. Mirrors
  `config/default-layout-host-css-var` — the inline-host snippet
  reads `var(--rf-causa-inline-width, 560px)` for its `flex-basis`,
  so writing to this property on `:root` (and the host element)
  resizes the panel in lockstep. Published here as a constant so the
  apply fn + tests reference one spelling."
  "--rf-causa-inline-width")

(defn- layout-host-element
  "Resolve the configured layout host element if Causa is mounted in
  the right-rail. Falls back to nil under :overlay/:popout/test
  runtimes — `apply-panel-width!` then writes to `:root` only, which
  is harmless because the host element doesn't exist in those modes."
  []
  (when (and (exists? js/document) (.-querySelector js/document))
    (try
      (.querySelector js/document (config/get-layout-host-selector))
      (catch :default _ nil))))

(defn apply-panel-width!
  "Write `px` as the value of `--rf-causa-inline-width` on both the
  configured layout host (so the host's `flex-basis` re-evaluates
  immediately) and the `<html>` root (so the cascade still resolves
  for any consumer reading `var(--rf-causa-inline-width, 560px)`
  further up the tree). No-op when neither element is present —
  matches the apply-text-size! / apply-theme! pattern."
  [px]
  (let [value (str (long (or px config/default-panel-width-px)) "px")]
    (when-let [host (layout-host-element)]
      (.setProperty (.-style host) panel-width-css-var value))
    (when-let [html (html-root-element)]
      (.setProperty (.-style html) panel-width-css-var value)))
  nil)

;; ---- panel position -----------------------------------------------------

(defn apply-panel-position!
  "Route to the matching mount-layer fn. `:right-rail` is the default
  inline mount; `:popout` opens the popout window; `:fullscreen`
  mounts the overlay. Switching to a position that's already mounted
  is a no-op via the mount fns' singleton guards. The mount fns are
  late-bound via the browser API exports (see ns docstring §Why we
  late-bind mount via the browser API export)."
  [position]
  (case position
    :right-rail (call-mount-fn! "open_BANG_")
    :popout     (call-mount-fn! "popout_BANG_")
    :fullscreen (call-mount-fn! "open_overlay_BANG_")
    ;; Unknown — log and skip. The popup's radio enumeration is the
    ;; sole emit point so an unknown value indicates a programmer
    ;; mistake somewhere upstream.
    (when (and (exists? js/console) (.-warn js/console))
      (.warn js/console
             (str "[rf2-causa] settings: unknown panel-position "
                  (pr-str position)))))
  nil)

;; ---- auto-open-on-error -------------------------------------------------
;;
;; The auto-open-on-error wiring is a sub-watcher: we subscribe to the
;; existing `:rf.causa/issues-ribbon` sub (the same one the Issues
;; panel reads) and dispatch `mount/open!` on the first transition
;; from empty → non-empty IFF the toggle is on AND Causa is not
;; already visible. Two install triggers, both idempotent: (1)
;; `mount/ensure-causa-frame!` on first Causa open when the persisted
;; toggle is on, (2) `:rf.causa/settings-update` on toggle flip-on.
;; Detached on flip-off.
;;
;; ## Why subscribe rather than register a trace-cb
;;
;; The issues-ribbon sub already does the severity classification,
;; the filter application, the empty-state classification — wiring
;; the watcher to its output means we react to the exact shape the
;; Issues panel renders, not the raw trace stream. A new severity
;; that the helpers classify differently (or a new filter axis)
;; rides through this watcher for free.
;;
;; ## Why not install at preload
;;
;; The watcher subscribes to a sub that reads from `:rf/causa`'s
;; app-db; but `:rf/causa` is lazy-registered by
;; `mount/ensure-causa-frame!` on first open (see mount.cljs §Why
;; here, not at preload time). Subscribing into a non-existent frame
;; returns nil, and `(add-watch nil ...)` throws under Story
;; testbeds that never open Causa. The frame-presence guard below
;; makes the install a defensive no-op pre-mount; the install
;; retries from the two trigger paths above as soon as the gates
;; align.

(defonce ^:private auto-open-watcher
  ;; Holds the `add-watch` reaction object so re-installation is
  ;; idempotent under shadow-cljs `:after-load`. `nil` before
  ;; install.
  (atom nil))

(defonce ^:private last-issue-count
  ;; Tracks the most recent observed issue count so we only act on
  ;; the empty → non-empty edge, not on every subsequent push.
  (atom 0))

(defn install-auto-open-watcher!
  "Subscribe to `:rf.causa/issues-ribbon` and arrange for the watcher
  to dispatch the late-bound `mount/open!` on the first transition
  from empty → non-empty issue list IFF `:auto-open-on-error?` is on
  AND Causa is not already visible.

  The sub is created via `rf/subscribe` inside a `with-frame
  :rf/causa` so the watcher reads the Causa frame's app-db (where
  the issues feed lives). The reaction is held in a `defonce` atom
  so re-install on `:after-load` does not double-watch."
  []
  ;; Defensive: the `:rf/causa` frame is lazy-registered by
  ;; `mount.cljs/ensure-causa-frame!` on the first `open!` call (see
  ;; mount.cljs §Why here, not at preload time). If we land here
  ;; before that registration — e.g. preload runs in a Story testbed
  ;; where the user never opens Causa — `rf/subscribe` returns nil
  ;; and `(add-watch nil ...)` throws
  ;; `No protocol method IWatchable.-add-watch defined for type null`.
  ;; The frame guard makes pre-mount calls a silent no-op; the install
  ;; is retried from `mount.cljs/ensure-causa-frame!` (when the user
  ;; has previously persisted the toggle on) and from
  ;; `:rf.causa/settings-update` on every toggle flip, so the watcher
  ;; lands as soon as both gates are satisfied.
  (when (and (nil? @auto-open-watcher)
             (exists? js/window)
             (some? (frame/frame :rf/causa)))
    (when-let [reaction (rf/with-frame :rf/causa
                          (rf/subscribe [:rf.causa/issues-ribbon]))]
      (let [watch-fn (fn [_k _r _old new-val]
                       (let [n      (count (:issues new-val))
                             prev   @last-issue-count
                             toggle (config/get-setting
                                      :general :auto-open-on-error?)]
                         (reset! last-issue-count n)
                         (when (and toggle
                                    (pos? n)
                                    (zero? prev)
                                    (not (visible-shell?)))
                           (call-mount-fn! "open_BANG_"))))]
        (add-watch reaction ::auto-open-on-error watch-fn)
        (reset! auto-open-watcher reaction))))
  nil)

(defn detach-auto-open-watcher!
  "Tear down the auto-open watcher. Test-only."
  []
  (when-let [reaction @auto-open-watcher]
    (try (remove-watch reaction ::auto-open-on-error) (catch :default _ nil))
    (reset! auto-open-watcher nil)
    (reset! last-issue-count 0))
  nil)

;; ---- bulk apply ---------------------------------------------------------

(defn apply-all!
  "Apply every persisted setting to the live shell. Called from the
  preload after `load-settings-from-storage!` so the user's saved
  text-size + theme land BEFORE first paint. Per-knob effects are
  individually no-op-safe pre-mount."
  []
  (let [s (config/get-settings)]
    (apply-text-size!  (get-in s [:general :text-size]))
    (apply-theme!      (get s :theme))
    ;; rf2-x8h9y — restore the persisted panel width so the user's
    ;; saved drag survives reload BEFORE first paint. No-op-safe
    ;; pre-mount (writes to `<html>` only when the layout host hasn't
    ;; mounted yet; the host pickup happens at next paint via the
    ;; var cascade).
    (apply-panel-width! (get-in s [:general :panel-width-px]))
    ;; Panel-position is intentionally NOT applied at boot — the
    ;; preload's auto-open already handles the default `:right-rail`
    ;; case, and reopening into the saved position would surprise a
    ;; user who closed Causa from popout last session and now expects
    ;; their app to load clean. The setting still applies when the
    ;; user changes it from the popup.
    )
  nil)
