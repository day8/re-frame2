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
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

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

;; ---- density (rf2-i40us) ------------------------------------------------
;;
;; The density radio (Compact / Cosy in v1; Comfy is the spec's third
;; tier kept here for forward compat) writes a px value to the canonical
;; `--rf-causa-font-size` CSS custom property that anchors the whole
;; `theme/tokens/type-scale`. Per rf2-n8i2c every `type-scale` entry
;; resolves to `calc(var(--rf-causa-font-size, 13px) * <multiplier>)`;
;; flipping the var rescales every typographic surface in lockstep on
;; the next paint (no re-render needed). This completes the "one knob
;; per density" loop the radio shipped without — until this commit the
;; radio only persisted the value + drove the `:rf.causa/density` sub.
;;
;; ## Why we write here AND `theme/global-styles/motion-css` already
;; publishes a default on `:root`
;;
;; `global-styles` writes the *default* value (`13px`) into the
;; injected `<style>` block once at install time, so the var resolves
;; for any descendant from first paint even before Causa mounts. The
;; settings-driven apply fn here writes an *inline* declaration on
;; `:root` / shell-root which OVERRIDES that selector-based default
;; (inline > selector in the cascade). Removing the inline declaration
;; on a return-to-cosy is therefore optional — re-asserting 13px is
;; idempotent — but we re-assert anyway so the apply fn's contract is
;; one-write-one-paint regardless of the prior state.

(def density->font-size-px
  "Pure-data map from density keyword → font-size pixel value to write
  into `--rf-causa-font-size`. The v1 radio surfaces only `:compact`
  and `:cosy` (Mike 2026-05-19); `:comfy` is catalogued so a future
  un-drop of the third tier wires through without a code change.

  - `:compact` → 12px (one step tighter than the historic baseline)
  - `:cosy`    → 13px (the historic baseline; matches
                 `tokens/font-size-default`)
  - `:comfy`   → 14px (one step looser; spec/007 §Typography catalogues
                 it as the third tier on the ±1px density knob)

  JVM-portable pure data so the JVM test surface can assert the
  mapping without touching the browser."
  {:compact 12
   :cosy    13
   :comfy   14})

(defn density->px
  "Resolve a density keyword to its px value via `density->font-size-px`.
  Falls back to the cosy default (13px) when the keyword is unknown
  (a persisted `:comfy` payload pre-Mike-2026-05-19 lands here; the
  `:rf.causa/density` sub coerces unknown values to `:cosy` for the
  reactive surface, and this helper mirrors that posture)."
  [density]
  (or (get density->font-size-px density)
      (get density->font-size-px :cosy)))

(defn apply-density-font-size!
  "Write the px value for `density` into `--rf-causa-font-size` on both
  the Causa shell root (so the inline-style call sites that resolve
  `calc(var(--rf-causa-font-size, 13px) * N)` from `type-scale` pick
  it up via inheritance) and the `<html>` root (so popout / fullscreen
  mounts that may not be inside the inline shell root still inherit).

  No-op when neither element is present (test runtimes without a
  `document`). Matches the `apply-text-size!` / `apply-theme!` /
  `apply-panel-width!` write pattern — write to both roots so every
  Causa-owned surface (inline, popout, fullscreen) honours the knob."
  [density]
  (let [value (str (density->px density) "px")]
    (when-let [root (shell-root-element)]
      (.setProperty (.-style root) tokens/font-size-var-name value))
    (when-let [html (html-root-element)]
      (.setProperty (.-style html) tokens/font-size-var-name value)))
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

;; ---- reduced-motion override (rf2-ybjkx) --------------------------------

(def motion-override-class-prefix
  "CSS class prefix the reduced-motion override writes onto `<html>`.
  Three states:

    :os      — no class set; OS media query alone drives the seam
               (the historic behaviour preserved when the user has
               never opted in).
    :always  — `rf-causa-motion-override-always` — force the
               vanishingly-small motion-scale regardless of OS pref.
    :never   — `rf-causa-motion-override-never` — restore full motion
               even when the OS prefers `reduce`.

  The selector in `theme/global-styles/motion-css` matches a single
  class on `:where(html, body)` so the override outranks the
  media-query `:root` rule (single class beats element selector) and
  authoring order plus `:where` keeps specificity low enough that
  consumer styles can still override the var if they want to."
  "rf-causa-motion-override-")

(defn apply-reduced-motion-override!
  "Drive the body / `<html>` class that overrides the OS-level
  `prefers-reduced-motion: reduce` media query. Accepted values:

    :os      — clear any prior override (defer to OS pref)
    :always  — write `rf-causa-motion-override-always`
    :never   — write `rf-causa-motion-override-never`

  Idempotent — repeated calls with the same value leave the
  classList in the same state. No-op when neither the shell root nor
  `<html>` is present (test runtimes without a `document`)."
  [override]
  (let [target (or override :os)
        klass  (when (contains? #{:always :never} target)
                 (str motion-override-class-prefix (name target)))]
    (doseq [el [(shell-root-element) (html-root-element)]
            :when el]
      (let [cl (.-classList el)]
        ;; Drop both options first so the assertion below is exclusive.
        (doseq [c [(str motion-override-class-prefix "always")
                   (str motion-override-class-prefix "never")]]
          (try (.remove cl c) (catch :default _ nil)))
        (when klass
          (try (.add cl klass) (catch :default _ nil))))))
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

(defn apply-panel-width!
  "Drive `--rf-causa-inline-width` on the `<html>` root from the user's
  resize-handle setting.

  - When `px` differs from `config/default-panel-width-px`, write the
    value as an inline style on `<html>`. The recommended host CSS
    reads `var(--rf-causa-inline-width, 560px)` for its `flex-basis`;
    CSS custom properties inherit, so a declaration on `<html>`
    resolves at every descendant — including the host — on the very
    next paint.
  - When `px` IS the default (or `nil`), REMOVE any existing inline
    declaration rather than re-asserting it. This is the critical
    move: keeping the property unset on `<html>` lets the consumer's
    own override (e.g. `:root { --rf-causa-inline-width: 720px; }`
    in the host stylesheet) resolve at the host via the documented
    cascade. Asserting the default would otherwise shadow that
    override — inline declarations on `<html>` beat any author-normal
    selector-based rule, including `:root { ... }` in `<style>`
    (rf2-6fqr5).

  No-op when `<html>` is absent (test runtimes without a `document`
  root). Matches the apply-text-size! / apply-theme! pattern, except
  for the default-as-clear behaviour, which is unique to this
  property because only this property is part of a documented
  consumer-cascade contract.

  ## Why we do NOT also write to the host element

  An earlier draft (rf2-x8h9y) wrote the custom property on BOTH the
  host element AND `<html>`. The host write trapped the cascade even
  harder than the `<html>`-default trap above: inline style on the
  host beats ALL selector-based declarations regardless of layer,
  including the consumer's `:root` rule. Removed for the same
  rf2-6fqr5 reason; the host's `var(...)` inherits from `<html>`
  (or `:root`) on the next paint without any per-element write."
  [px]
  (when-let [html (html-root-element)]
    (let [px      (or px config/default-panel-width-px)
          default config/default-panel-width-px]
      (if (= (long px) (long default))
        ;; Default value — clear any prior inline write so the
        ;; consumer's cascade override (or the host CSS's `var(...)`
        ;; fallback) wins.
        (.removeProperty (.-style html) panel-width-css-var)
        ;; Explicit user setting (drag handle / numeric input) —
        ;; write inline on `<html>` so it overrides the consumer's
        ;; baseline. The user's gesture is the strongest signal.
        (.setProperty (.-style html) panel-width-css-var
                      (str (long px) "px")))))
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
    ;; rf2-i40us — restore the persisted density-driven font-size so
    ;; the user's saved choice rescales the type scale BEFORE first
    ;; paint. Default (`:cosy`) re-asserts 13px (the same value
    ;; `theme/global-styles/motion-css` publishes on `:root` at install
    ;; time); the inline write is idempotent on the cosy case and the
    ;; cost of writing-anyway is one DOM setProperty per boot.
    (apply-density-font-size!
      (get-in s [:general :density]))
    ;; rf2-ybjkx — restore the reduced-motion override so the user's
    ;; saved choice survives reload BEFORE first paint. The default
    ;; `:os` writes nothing (the OS media query alone drives the
    ;; seam); `:always` / `:never` write the override class.
    (apply-reduced-motion-override!
      (get-in s [:general :reduced-motion-override]))
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
