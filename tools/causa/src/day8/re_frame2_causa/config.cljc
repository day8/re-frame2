(ns day8.re-frame2-causa.config
  "Compile-time and runtime configuration for Causa.

  Phase 1 held a single config concern: the 'Open in editor'
  preference (rf2-evgf5). Causa now also exposes the default inline
  layout-host selector, default auto-open switch (rf2-eehov), and the
  ribbon filter pill seed + persistence key (rf2-ak4ms). Future phases
  extend this with theme defaults, buffer depth, etc.

  ## Why a separate config ns

  The host application sets the Causa editor preference via
  `(day8.re-frame2-causa.config/set-editor! :cursor)` at boot. Holding
  the preference behind an atom in a dedicated ns lets the UI shell
  read it without importing the host's boot code.

  Causa's editor preference is **independent** of Story's. Hosts that
  run both tools may want different editors (e.g. VS Code for app
  development, IntelliJ for the test reading workflow); two atoms,
  two `configure!` surfaces.

  ## Production posture

  The atom is a plain Clojure data store. Production builds DCE the
  Causa shell (gated on `interop/debug-enabled?` in preload.cljs); the
  atom survives but is never read. CLJC so the JVM test corpus can
  cover the round-trip without a CLJS runtime."
  (:require [re-frame.privacy :as privacy]
            [re-frame.source-coords.editor-uri :as editor-uri]
            #?@(:cljs [[cljs.reader]
                       [re-frame.core :as rf]
                       [re-frame.frame :as frame]])))

;; ---- editor preference ---------------------------------------------------

(def default-layout-host-selector
  "[data-rf-causa-host]")

;; ---- Inline-host resize contract (rf2-um813) ----------------------------
;;
;; The host page owns sizing for `[data-rf-causa-host]` (per
;; spec/011-Launch-Modes.md §Layout host contract). To let developers
;; tweak the width WITHOUT forking the layout rule or falling back to
;; overlay/body-padding dock modes, the recommended host rule reads
;; one CSS custom property — Causa documents the property name and
;; default; the host's CSS uses it via `var(--rf-causa-inline-width,
;; 560px)`. Overriding the property anywhere up the cascade (`:root`,
;; an ancestor, the host itself, or a user stylesheet) resizes the
;; panel. App content to the right (`#app { flex: 1; min-width: 0 }`)
;; remains in normal flow — no hit-test occlusion, no overlay.
;;
;; The contract is intentionally JS-free: the host CSS is the single
;; source of truth for sizing. Causa itself does NOT read the variable
;; (the panel fills its host) — the variable is the host's knob.

(def default-layout-host-css-var
  "Name of the CSS custom property the recommended host snippet reads
  for its `flex-basis`. Hosts that follow the recommended snippet can
  resize the inline Causa panel by overriding this property in their
  own stylesheet:

      :root { --rf-causa-inline-width: 720px; }

  Causa never reads this property — sizing is owned by the host's
  layout rule (per spec/011-Launch-Modes.md §Layout host contract).
  The constant is published so tooling (story-mode chrome, docs
  generators) can refer to the exact spelling without forking the
  string."
  "--rf-causa-inline-width")

(def default-layout-host-width
  "Default value Causa recommends for `--rf-causa-inline-width` when the
  host does not override it. Bumped from 420px → 560px per rf2-9ovfb
  (Pitch8 field feedback: event vectors with map payloads wrap awkwardly
  at 420px; 560px reads much better for the Event Detail panel)."
  "560px")

(def default-panel-width-px
  "Default panel width in pixels (rf2-x8h9y). Mirrors
  `default-layout-host-width` (`\"560px\"`) without the unit so the
  numeric Settings slot, the resize handle's drag math, and the
  double-click reset can compose against one source of truth. The
  resize handle writes the live value back through
  `--rf-causa-inline-width` so the inline-host contract above (the
  documented `var(--rf-causa-inline-width, 560px)` cascade) keeps
  working unchanged — drag is simply a UX surface that drives that
  same CSS custom property reactively."
  560)

(def min-panel-width-px
  "Lower clamp for the resize handle (rf2-x8h9y). 320px matches the
  inline-host snippet's `min-width: 320px` floor — below this the
  L1 ribbon clusters start to wrap and the chrome becomes unusable."
  320)

(def max-panel-width-fraction
  "Upper clamp for the resize handle expressed as a fraction of the
  viewport width (rf2-x8h9y). 0.9 leaves a 10% sliver for the host
  app so the user always has a visual anchor back to their content;
  full-viewport coverage is the dedicated `:fullscreen` panel-position
  rather than a side-effect of dragging the handle off the left edge."
  0.9)

(def default-accent-css-var
  "Name of the CSS custom property carrying Causa's brand-accent
  colour (the violet `#7C5CFF` from `theme/tokens.cljc` /
  `spec/007-UX-IA.md` §Colour system / `:accent-violet`). Published
  per rf2-9ovfb so:

    - Host application stylesheets can colour their own dev chrome
      to match Causa (e.g. a resize-handle inset shadow, a dock-
      mode separator, a Story chip pill) without forking the hex.

          .my-resize-handle { box-shadow: inset 0 0 0 1px var(--rf-causa-accent); }

    - Consumers that prefer the colour with translucency can layer
      a manual alpha:

          background: rgb(from var(--rf-causa-accent) r g b / 0.35);

      (or pre-CSS-4: `rgba(124, 92, 255, 0.35)` — equivalent to the
      published hex at 35% alpha, the value Pitch8 eyeballed for
      resize-drag accents).

  Causa publishes the property on `:root` in the recommended host
  snippet so the lookup resolves anywhere in the cascade. The host
  can override it (e.g. for a tinted brand variant) by setting
  the property on `:root` or any ancestor of the consumer rule.

  Constant exists so tooling (docs generators, Code Connect templates)
  can refer to the exact spelling without forking the string."
  "--rf-causa-accent")

(def default-accent
  "Default value Causa publishes for `--rf-causa-accent` — the
  violet `#7C5CFF` from `theme/tokens.cljc` (`:accent-violet`).
  Matches the brand accent catalogued in `spec/007-UX-IA.md`
  §Colour system."
  "#7C5CFF")

(def default-layout-host-snippet
  ;; DOM order is `<main>` first, host `<aside>` second — flex flow
  ;; lays the aside on the right of the app column (per spec/011-
  ;; Launch-Modes.md §Layout host contract, rf2-e07yk). CSS uses
  ;; `var(--rf-causa-inline-width, 560px)` so a host that pastes the
  ;; snippet verbatim gets:
  ;;   - default geometry (560px flex-basis per rf2-9ovfb, 320px floor)
  ;;   - a single one-line override path:
  ;;       :root { --rf-causa-inline-width: 720px; }
  ;;   - a user-draggable resize handle via the browser-native
  ;;     `resize: horizontal` + `overflow: auto` on the host
  ;;   - app content to the left stays in normal flex flow
  ;;     (no overlay, no body padding).
  ;;   - `--rf-causa-accent` published on `:root` (rf2-9ovfb) so
  ;;     host stylesheets can colour their own dev chrome (resize
  ;;     handles, dock separators, story chips) to match Causa
  ;;     without forking the hex. Override on `:root` for a tinted
  ;;     brand variant.
  ;;
  ;; `box-sizing: border-box` is required so the documented width
  ;; (the `var(--rf-causa-inline-width, 560px)` value) is the actual
  ;; rendered width INCLUDING the 1px `border-left` separator. Without
  ;; it, the host renders one pixel wider than the documented value —
  ;; an off-by-one that surfaces in any pixel-exact contract (see
  ;; tools/causa/testbeds/inline_resize/spec.cjs).
  "<div class=\"app-shell\">
  <main id=\"app\"></main>
  <aside data-rf-causa-host></aside>
</div>

<style>
  :root { --rf-causa-accent: #7C5CFF; }
  body { margin: 0; }
  .app-shell { display: flex; min-height: 100vh; }
  [data-rf-causa-host] {
    flex: 0 0 var(--rf-causa-inline-width, 560px);
    min-width: 320px;
    box-sizing: border-box;
    border-left: 1px solid #2a2a2a;
    resize: horizontal;
    overflow: auto;
  }
  #app { flex: 1; min-width: 0; }
</style>")

(defonce
  ^{:doc "Atom holding the CSS selector for the app-provided normal-flow
         Causa layout host. The default is `[data-rf-causa-host]`."}
  layout-host-selector
  (atom default-layout-host-selector))

(defn set-layout-host-selector!
  "Set the selector Causa uses for its default true-inline shell mount.
  `nil` resets to `[data-rf-causa-host]`."
  [selector]
  (reset! layout-host-selector (or selector default-layout-host-selector))
  nil)

(defn get-layout-host-selector
  "Return the CSS selector for the Causa layout host."
  []
  @layout-host-selector)

(defonce
  ^{:doc "Atom controlling whether the Causa preload auto-opens the
         default true-inline shell once the substrate adapter is ready.
         Defaults to true. Tool-owned Story pages that intentionally
         do not allocate app real estate for Causa may set this false
         before `rf/init!`; explicit open!/toggle! calls still retain
         the normal missing-host diagnostic."}
  auto-open?
  (atom true))

(defn set-auto-open!
  "Replace the `:launch/auto-open?` flag. `nil` resets to the default
  (`true`)."
  [v]
  (reset! auto-open? (if (nil? v) true (boolean v)))
  nil)

(defn auto-open-enabled?
  "Return true when the preload should auto-open the default inline
  Causa shell after the substrate adapter is ready."
  []
  @auto-open?)

;; ---- *keybinding-enabled?* (rf2-4eyik — embed-host opt-out for the
;; global keydown listener) ------------------------------------------------
;;
;; Causa attaches a window-level capture-phase `keydown` listener
;; (`keybinding/attach!`) that handles `Ctrl+Shift+C` (shell toggle),
;; `Cmd/Ctrl+K` (command palette), and the unmodified spine bindings
;; (`Space` / `L` / `j` / `k` / `G` / `c` / `Esc`). The handler calls
;; `stopPropagation()` for keys it consumes so host bindings further down
;; the event path don't double-fire.
;;
;; Embed hosts (Story mounts Causa as its right-hand-side panel)
;; routinely register their own `Cmd/Ctrl+K` palette + their own
;; bindings. With Causa's listener attached at the capture phase the
;; host's own bindings never fire — the embed silently swallows
;; keystrokes that belong to the host (rf2-q7who Thread A, observed
;; via rf2-drprn).
;;
;; The flag is the host's surrender switch: set it to `false` BEFORE
;; the Causa preload runs (typically inside the host's boot sequence
;; via `(causa-config/configure! {:launch.keybinding/enabled? false})`)
;; and `keybinding/attach!` short-circuits to a no-op. The shell is
;; still mountable / dispatchable / inspectable via Causa's other
;; surfaces (the host owns its own open-Causa affordance); only the
;; global listener is suppressed.
;;
;; Standalone Causa (the default) keeps the listener attached — the
;; default is `true` so existing hosts that never set the flag observe
;; no behaviour change.

(defonce
  ^{:doc "Atom controlling whether `keybinding/attach!` installs the
         global window-level keydown listener. Default `true` (the
         standalone Causa shell needs its Ctrl+Shift+C / Cmd-K / spine
         bindings). Set to `false` by embed hosts (Story RHS, third-
         party tool surfaces) whose own keybindings collide with
         Causa's — `attach!` short-circuits to a no-op. Per rf2-4eyik
         (rf2-q7who Thread A)."}
  keybinding-enabled?
  (atom true))

(defn set-keybinding-enabled!
  "Replace the `:launch.keybinding/enabled?` flag. `nil` resets to the
  default (`true`). Hosts MUST set this BEFORE the Causa preload runs
  (the preload calls `keybinding/attach!` at adapter-ready time);
  setting it afterwards is a no-op on the already-attached listener
  unless the host explicitly calls `keybinding/detach!`."
  [v]
  (reset! keybinding-enabled? (if (nil? v) true (boolean v)))
  nil)

(defn keybinding-attach-enabled?
  "Return true when `keybinding/attach!` should install the global
  keydown listener. Read by `keybinding/attach!` at attach time. Per
  rf2-4eyik (rf2-q7who Thread A)."
  []
  @keybinding-enabled?)

(defonce
  ^{:doc "Atom holding Causa's 'Open in editor' preference. Default
         `:vscode`. Accepts the keywords `:vscode` / `:cursor` /
         `:windsurf` / `:zed` / `:idea` plus the
         `{:custom \"<uri-template>\"}` form (see
         `re-frame.source-coords.editor-uri/editor-uri`)."}
  editor
  (atom :vscode))

(defn set-editor!
  "Set Causa's 'Open in editor' preference. Hosts call this once at
  boot (typically inside their `app.core` ns alongside any Causa-
  specific setup).

  Accepts:
    - `:vscode` (default) — `vscode://file/<path>:<line>:<column>`
    - `:cursor`           — `cursor://file/<path>:<line>:<column>`
    - `:windsurf`         — `windsurf://file/<path>:<line>:<column>`
    - `:zed`              — `zed://file/<path>:<line>:<column>`
    - `:idea`             — `idea://open?file=<path>&line=<line>&column=<column>`
    - `{:custom <uri-template>}` — user template with `{path}` / `{file}` /
                                   `{line}` / `{column}` placeholders.
    - `nil`               — reset to `:vscode` default.

  Returns nothing."
  [e]
  (reset! editor (or e :vscode))
  nil)

(defn get-editor
  "Return the current editor preference."
  []
  @editor)

;; ---- *project-root* (rf2-5m5n2 — 'Open in editor' path prefix) ----------
;;
;; Per rf2-zfy1e (Story) / rf2-5m5n2 (Causa): source-coords stamped at
;; registration time are classpath-relative (the form-meta `:file` slot,
;; e.g. `"panel_gallery/event_detail_stories.cljs"`). Editor URI handlers
;; (`vscode://file/<path>...`, `cursor://...`, `idea://...`, etc.) resolve
;; `<path>` against the filesystem; a relative path fails with "Path does
;; not exist". Causa's 'Open' chip + the `:rf.editor/open` reg-fx
;; therefore need to know the on-disk root to prepend before the URI
;; ships.
;;
;; The host application sets this once at boot via
;; `(causa-config/configure! {:project-root "C:/Users/me/code/my-app/src"})`.
;; Default is nil — when unset, the source-coord file ships verbatim and
;; the Open chip behaves exactly as it did pre-rf2-5m5n2 (useful for hosts
;; whose source-paths are already absolute, and for tests).
;;
;; Causa's project-root is **independent** of Story's. Hosts that run
;; both tools may want different roots (e.g. an app-source root for
;; Causa, a stories root for Story); two atoms, two `configure!` surfaces.
;;
;; The atom is plain data; production builds DCE the Causa shell that
;; reads it, so the value is harmless if it survives into a release
;; bundle.

(defonce
  ^{:doc "Atom holding the project-root prefix for Causa's 'Open in
         editor'. Default `nil` (no prefix; ship the source-coord file
         verbatim). Set by `day8.re-frame2-causa.config/configure!` via
         the `:project-root` key. Read by `open-in-editor/resolve-uri`
         — prepended to the source-coord's `:file` slot when building
         the editor URI."}
  project-root
  (atom nil))

(defn set-project-root!
  "Replace the project-root prefix. Accepts a string (the on-disk root,
  typically the directory above the classpath source-paths, joined to
  source-coords via `/`), or `nil` (clears the prefix). Causa's
  `configure!` calls this. Blank strings normalise to nil."
  [p]
  (reset! project-root (when (and (string? p) (seq p)) p))
  nil)

(defn get-project-root
  "Return the current project-root string, or nil when unset."
  []
  @project-root)

;; ---- *show-sensitive?* (rf2-azls9 — :sensitive? trace-event policy) ------
;;
;; Per Spec 009 §Privacy (resolved by rf2-a32kd): framework-published
;; trace-consuming integrations MUST default-suppress `:sensitive? true`
;; events. Causa is a framework-published consumer — its ring buffer
;; feeds every panel (event-detail, causality, trace, machine
;; inspector, etc.) — so the collector body gates on this flag before
;; the event reaches the buffer.
;;
;; Default is `false` (suppress sensitive events). An engineer debugging
;; redaction policy flips this on via
;; `(causa-config/configure! {:trace/show-sensitive? true})`.
;;
;; The flag is read at the head of the collector body, so toggling it
;; takes effect on the next trace event without re-registering the
;; listener.

(defonce
  ^{:doc "Atom holding the `:trace/show-sensitive?` flag. Default
         `false`. When `false` (default), Causa's trace collector
         short-circuits on events whose `:sensitive?` field is true,
         and the UI surface tracks how many were suppressed. When
         `true`, the collector receives every event unchanged. Per
         Spec 009 §Privacy + bead rf2-azls9."}
  show-sensitive?
  (atom false))

;; ---- toggle-off callbacks (rf2-lqmje — retroactive scrub) ----------------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
;; false (rf2-lqmje), toggling the flag from true → false MUST clear
;; the trace buffer — the flag is NOT a one-way trapdoor. The collector
;; only gates at ingest time (`suppress-sensitive?`), so without this
;; scrub a sensitive cascade emitted while the flag was true would
;; remain visible in every panel after the user flipped the flag back
;; to false expecting privacy to be restored.
;;
;; The cost: non-sensitive history that was buffered alongside the
;; sensitive cascade is also lost. This is the documented trade-off —
;; selective scrubbing is unsafe because a single sensitive event can
;; have caused later non-sensitive cascades (subs, renders, fx) whose
;; payloads structurally reveal the redacted value (per the Spec 009
;; rationale block). Clearing the whole buffer is the simplest correct
;; semantic.
;;
;; The hook design avoids a circular require — `trace-bus.cljc`
;; depends on `config.cljc` to read the flag and bump the counter, so
;; `config.cljc` cannot directly invoke `trace-bus/clear-buffer!`.
;; Instead, `trace-bus.cljc` registers its clear-buffer fn into this
;; atom at load time; `set-show-sensitive!` walks the atom on every
;; true → false transition. CLJC-pure so the registration shape is
;; testable under the JVM target.

(defonce
  ^{:doc "Atom holding `{id → (fn [] ...)}` callbacks invoked when
         `set-show-sensitive!` transitions the flag from true → false.
         `trace-bus.cljc` registers its `clear-buffer!` at load time.
         Internal — host applications should not register here."}
  toggle-off-callbacks
  (atom {}))

(defn register-toggle-off-callback!
  "Register `f` (a zero-arg fn) under `id` to be invoked when
  `set-show-sensitive!` transitions the flag from `true` → `false`.
  Replaces any existing entry under the same id. Internal API — Causa
  modules use it to wire their buffer-clear hooks; host applications
  should NOT register here.

  Returns `id`."
  [id f]
  (swap! toggle-off-callbacks assoc id f)
  id)

(defn unregister-toggle-off-callback!
  "Remove the toggle-off callback registered under `id`. Idempotent."
  [id]
  (swap! toggle-off-callbacks dissoc id)
  nil)

(defn- run-toggle-off-callbacks!
  "Invoke every registered toggle-off callback. Each callback's
  exception is swallowed (logged via `tap>`) so one buggy hook does
  not prevent the others from running — privacy is the load-bearing
  concern, and a partial clear is strictly better than no clear."
  []
  (doseq [[id f] @toggle-off-callbacks]
    (try
      (f)
      (catch #?(:clj Throwable :cljs :default) e
        (tap> {:tag ::toggle-off-callback-failed :id id :error e})))))

(defn set-show-sensitive!
  "Replace the `:trace/show-sensitive?` flag. Causa's `configure!`
  calls this. `nil` resets to the default (`false`).

  Per rf2-lqmje (Spec 009 §Privacy §Retroactive-scrub): when the call
  transitions the flag from `true` → `false`, the trace buffer is
  cleared by invoking every registered `toggle-off-callbacks` entry.
  The trade-off — non-sensitive history buffered alongside the
  sensitive cascade is also lost — is intentional: clearing the whole
  buffer is the simplest correct semantic because a sensitive event
  emitted while the flag was true can have caused later cascades whose
  payloads structurally reveal the redacted value. The flag is NOT a
  one-way trapdoor; toggling it false MUST restore privacy fully.
  true → true and false → ANY transitions do NOT invoke the
  callbacks."
  [v]
  (let [prev @show-sensitive?
        next (boolean v)]
    (reset! show-sensitive? next)
    (when (and prev (not next))
      (run-toggle-off-callbacks!)))
  nil)

(defn get-show-sensitive
  "Return the current `:trace/show-sensitive?` flag value."
  []
  @show-sensitive?)

(defn sensitive-event?
  "True iff the trace event `ev` carries `:sensitive? true` at the top
  level. Thin alias over the framework-published `re-frame.privacy/sensitive?`
  predicate (re-exported as `re-frame.core/sensitive?`) — per rf2-sqxjn
  / rf2-iwqu9, every consumer of `:sensitive?` (Causa, Story,
  story-mcp, re-frame2-pair-mcp, causa-mcp) composes against ONE framework
  primitive rather than reimplementing the five-token check. Per Spec
  009 §Privacy."
  [ev]
  (privacy/sensitive? ev))

(defn suppress-sensitive?
  "Should this trace event be suppressed by Causa's trace collector
  under the current `:trace/show-sensitive?` setting?

  Returns `true` iff (a) the event is `:sensitive? true` AND (b) the
  show-sensitive flag is `false`. The collector wraps its body in
  `(when-not (suppress-sensitive? ev) ...)`."
  [ev]
  (and (sensitive-event? ev)
       (not @show-sensitive?)))

;; ---- *suppressed-counters* (rf2-azls9 — UI redaction indicator) ----------
;;
;; The shell's bottom rail renders a `[● REDACTED N]` hint when sensitive
;; events were suppressed. The hint tells the user "you're seeing fewer
;; events than the runtime emitted because the privacy gate is on" —
;; useful when a sensitive cascade would otherwise vanish silently.
;;
;; The counter is per-target-frame (keyed by the event's `:tags :frame`),
;; with a `:global` bucket for events that have no frame scope
;; (registration-time emits, outermost-dispatch lookup failures —
;; `:sensitive?` is rarely set on those, but we keep the bucket so a
;; count is never lost).
;;
;; The counter is also mirrored into Causa's app-db at
;; `[:rf/causa db :suppressed-counters]` via dispatch (CLJS-only) so
;; the `:rf.causa/suppressed-sensitive-count` sub fires on the
;; standard reactive write path — the `[● REDACTED N]` indicator
;; updates IMMEDIATELY on every bump, with no dependency on sibling
;; subs recomputing. The atom here remains the JVM-runnable data
;; primitive so `config.cljc`'s shape is testable without a CLJS
;; runtime + re-frame frame.

(defonce
  ^{:doc "Atom: `{frame-id → suppressed-count}`. Causa's trace
         collector bumps the counter for the event's `:tags :frame`
         when it drops a sensitive event. The UI's redaction hint
         reads this counter; `reset-suppressed-count!` clears it
         (e.g. on `clear-buffer!` or via a 'clear' button)."}
  suppressed-counters
  (atom {}))

(defn note-suppressed!
  "Bump the suppressed-events counter for the frame the event
  targeted. Called by Causa's trace collector when
  `suppress-sensitive?` returned true. `frame-id` may be `nil` /
  absent — those count under `:global`.

  In CLJS, also dispatches `:rf.causa/note-sensitive-suppressed`
  into `:rf/causa` so the bottom-rail's `[● REDACTED N]` indicator
  updates IMMEDIATELY via the reactive sub-graph (the event handler
  updates Causa's app-db `:suppressed-counters` slot; the
  `:rf.causa/suppressed-sensitive-count` sub reads off the same
  db). The atom-bump stays as the JVM-runnable data primitive
  (CLJC tests assert it directly); the dispatch is the reactive
  surface for CLJS.

  Per rf2-1barg: dispatch is explicit `{:frame :rf/causa}` (was
  active-frame-chain pre-rf2-in6l2, which routed to `:rf/default`
  back when the bottom-rail was a plain Reagent fn). Post-rf2-in6l2
  the bottom-rail is `reg-view`-wrapped so its subscribe routes
  through React-context to `:rf/causa`; the dispatch's target now
  matches the sub's target. Guarded on `:rf/causa`'s existence so
  pre-mount callers (Causa shell not yet opened) bump the atom
  without emitting an `:rf.error/frame-destroyed` trace into the
  bus — the seed in `ensure-causa-frame!` lifts the atom's contents
  on first Ctrl+Shift+C."
  [frame-id]
  (let [k (or frame-id :global)]
    (swap! suppressed-counters update k (fnil inc 0)))
  #?(:cljs
     (when (frame/frame :rf/causa)
       (rf/dispatch [:rf.causa/note-sensitive-suppressed frame-id]
                    {:frame :rf/causa})))
  nil)

(defn suppressed-count
  "Return the count of sensitive trace events that have been
  suppressed for `frame-id`. Zero if no events have been suppressed
  for that frame. With no arg, returns the *total* across every
  bucket — the bottom-rail indicator uses the total because the
  user cares about 'is the gate hiding things from me right now',
  not per-frame breakdown."
  ([]
   (reduce + 0 (vals @suppressed-counters)))
  ([frame-id]
   (or (get @suppressed-counters (or frame-id :global)) 0)))

(defn reset-suppressed-count!
  "Reset the suppressed-events counter. With no arg, clears all.
  With a `frame-id`, clears just that bucket. Called from
  `trace-bus/clear-buffer!` and from test fixtures.

  In CLJS, also dispatches `:rf.causa/reset-suppressed-counters`
  into `:rf/causa` so the reactive copy in Causa's app-db drops in
  lockstep with the atom. Guarded on the frame existing — this is
  called from test fixtures before any frame is registered."
  ([]
   (reset! suppressed-counters {})
   #?(:cljs
      (when (frame/frame :rf/causa)
        (rf/with-frame :rf/causa
          (rf/dispatch [:rf.causa/reset-suppressed-counters]))))
   nil)
  ([frame-id]
   (swap! suppressed-counters dissoc (or frame-id :global))
   #?(:cljs
      (when (frame/frame :rf/causa)
        (rf/with-frame :rf/causa
          (rf/dispatch [:rf.causa/reset-suppressed-counters frame-id]))))
   nil))

;; ---- settings popup defaults + persistence (rf2-9poxq) ------------------
;;
;; The Settings popup modal (`settings/popup.cljs`) reads + writes a
;; settings map carrying user preferences for general / theme knobs.
;; Defaults are catalogued here; the live values round-trip through
;; localStorage under the key below so they survive reload (CLJS only
;; — the JVM target reads/writes the in-memory atom).
;;
;; ## Why a single nested map
;;
;; One atom rather than one-atom-per-knob means the localStorage
;; round-trip is a single `JSON.stringify` / `JSON.parse` of one EDN
;; payload; serialisation drift between knobs is structurally
;; impossible. The popup's `[section key]` addressing (event +
;; subscription contract) folds onto `get-in` / `assoc-in` over the
;; same shape.
;;
;; ## Locked decisions (rf2-9poxq R3, rf2-jh9ws)
;;
;; - auto-open-on-error default OFF (the user is in their app, not
;;   asking for Causa to interrupt them)
;; - panel-position default `:right-rail` (matches the existing
;;   `:layout/host-selector` inline-host posture)
;; - theme default `:dark` (Causa is a dev tool — the canvas-and-
;;   chrome palette in `theme/tokens.cljc` is the dark one)
;; - text-size default 13 (matches `theme/tokens.cljc :type-scale
;;   :body` — the popup's slider is the one knob that scales every
;;   subsequent inline-style reads via the published CSS custom
;;   property `--rf-causa-text-size`).
;;
;; ## Migration (rf2-jh9ws)
;;
;; Settings persisted with `:telemetry` key from prior sessions
;; should NOT break. `load-settings-from-storage!` deep-merges over
;; `default-settings` per-known-section, so any legacy `:telemetry`
;; key in the persisted payload is silently dropped (the merge target
;; no longer carries the slot). Same for `update-setting!` —
;; `valid-section-key?` rejects unknown `[section key]` paths so a
;; rogue `[:telemetry :opt-in?]` write is a no-op + a tap>.

(def settings-storage-key
  "localStorage key the settings round-trip uses. Versioned so a future
  schema change can ignore stale payloads without colliding with the
  old shape."
  "re-frame2.causa.settings.v1")

(def default-settings
  "Default settings map — the shape `configure! :settings` / the
  `settings/popup` modal / the `:rf.causa/setting` sub all read
  against. See the comment block above for the rationale on each
  default.

  ## :diff section (rf2-i39w2 — hiccup-diff micro-engine, Phase 3 of
  rf2-abts7)

  `:highlight-fn-ref-changes?` — opt-in toggle for the hiccup-diff
  engine's fn-ref classification (`ai/findings/2026-05-18-difftastic-
  in-causa.md` §4.5). Default `false`: function-valued props with
  identity-different references render as `:same` so idiomatic fresh-
  per-render closures don't drown the actual hiccup diff. Flip to
  `true` when diagnosing memoization issues — child re-renders because
  the parent passes a new fn every time."
  {:general   {:text-size           13              ; px — slider range 10–18
               :panel-position      :right-rail     ; :right-rail | :popout | :fullscreen
               :panel-width-px      default-panel-width-px ; rf2-x8h9y resize handle
               :auto-open-on-error? false}
   :theme     :dark                                  ; :dark | :light
   :diff      {:highlight-fn-ref-changes? false}})

(defn clamp-panel-width-px
  "Pure helper: clamp `px` to the resize handle's [min, viewport×0.9]
  range (rf2-x8h9y). Pass `viewport-width-px` explicitly so the helper
  stays CLJC-pure and JVM-testable (no implicit `window.innerWidth`
  reach). Non-numeric input falls back to `default-panel-width-px` so
  a malformed persisted payload never leaves the panel at an unusable
  size."
  [px viewport-width-px]
  (if-not (number? px)
    default-panel-width-px
    (let [max-px (long (* (or viewport-width-px 2000) max-panel-width-fraction))
          floor  min-panel-width-px
          ceil   (max floor max-px)]
      (long (-> px (max floor) (min ceil))))))

(defonce
  ^{:doc "Atom holding the live settings map. Seeded with
         `default-settings`; loaded from localStorage at init time
         (CLJS only) by `load-settings-from-storage!`. Reads via
         `get-settings` / `get-setting`; writes via
         `update-setting!` (the popup's events surface)."}
  settings
  (atom default-settings))

(defn get-settings
  "Return the current full settings map. Mostly useful for tests +
  for the bulk persistence write. UI consumers read individual slots
  via `get-setting`."
  []
  @settings)

(defn get-setting
  "Return one setting slot — `(get-setting :general :text-size)` →
  `13`. Reads from the in-memory atom; never throws on a missing
  slot (returns nil). The `:theme` slot is flat at the top level,
  not nested under a key — `(get-setting :theme nil)` returns the
  current theme keyword."
  [section key]
  (if (and (= section :theme) (nil? key))
    (:theme @settings)
    (get-in @settings [section key])))

(defn- valid-section-key?
  "Reject writes whose `[section key]` is not part of `default-
  settings` — the modal's event surface only emits known paths, so an
  unknown path is a programmer mistake the test corpus should catch."
  [section key]
  (let [base (get default-settings section)]
    (cond
      (and (map? base) (contains? base key)) true
      ;; `:theme` is the only non-map top-level slot. The popup's
      ;; event sends `[:theme nil <new-theme>]` for that case so
      ;; both arities address the same updater.
      (and (= section :theme) (nil? key))    true
      :else                                  false)))

;; ---- storage shim -------------------------------------------------------
;;
;; CLJS production reads/writes `window.localStorage`. Node test runtimes
;; have no window.localStorage; rather than skip the round-trip tests
;; there we degrade to an in-process atom that mimics the same get/set
;; surface. Production calls hit localStorage as expected (because the
;; localStorage branch wins when it's present); Node tests exercise
;; the same atom-write code paths the production payload roundtrips
;; through.

#?(:cljs
   (defonce ^:private memory-storage
     ;; Test-runtime fallback. Always created; only consulted when
     ;; `window.localStorage` is unreachable.
     (atom {})))

#?(:cljs
   (defn- localStorage-available?
     "True when the host runtime exposes a usable `window.localStorage`.
     False under Node test runtimes, in browsers with storage disabled,
     or in cross-origin documents that refuse access."
     []
     (try
       (and (exists? js/window)
            (boolean (.-localStorage js/window)))
       (catch :default _ false))))

#?(:cljs
   (defn- storage-set! [k v]
     (if (localStorage-available?)
       (try
         (.setItem (.-localStorage js/window) k v)
         (catch :default _ (swap! memory-storage assoc k v)))
       (swap! memory-storage assoc k v))))

#?(:cljs
   (defn- storage-get [k]
     (if (localStorage-available?)
       (try
         (.getItem (.-localStorage js/window) k)
         (catch :default _ (get @memory-storage k)))
       (get @memory-storage k))))

#?(:cljs
   (defn- storage-remove! [k]
     (if (localStorage-available?)
       (try
         (.removeItem (.-localStorage js/window) k)
         (catch :default _ (swap! memory-storage dissoc k)))
       (swap! memory-storage dissoc k))))

#?(:cljs
   (defn- write-storage!
     "Round-trip the current settings map into the storage shim. Wraps
     access in a try/catch — quota errors, private-mode refusals, etc.
     degrade silently (the setting still applies in memory; reload will
     reset to defaults). Stored as pr-str so re-frame keywords round-
     trip without bespoke encoding."
     []
     (try
       (storage-set! settings-storage-key (pr-str @settings))
       (catch :default _ nil))))

#?(:cljs
   (defn load-settings-from-storage!
     "Read the persisted settings map out of localStorage (if any) and
     deep-merge over the in-memory defaults. Idempotent — safe to call
     more than once. Failures (no window, no localStorage, malformed
     payload) degrade silently to the defaults the atom already holds.
     Called from the preload's side-effect block on CLJS startup.

     Per rf2-jh9ws: legacy `:telemetry` keys (from sessions prior to
     the Telemetry section's removal) are silently dropped — the
     per-section merge below only knows about known slots, so any
     unknown top-level key in the persisted payload falls on the
     floor without throwing."
     []
     (try
       (when-let [raw (storage-get settings-storage-key)]
         (let [parsed (cljs.reader/read-string raw)]
           (when (map? parsed)
             (reset! settings
                     (-> default-settings
                         (update :general merge (:general parsed))
                         (assoc  :theme  (or (:theme parsed)
                                             (:theme default-settings)))
                         (update :diff      merge (:diff parsed)))))))
       (catch :default _ nil))
     nil))

(defn update-setting!
  "Write `value` into the settings slot at `[section key]`. CLJS calls
  also round-trip the whole map through localStorage so the change
  survives reload. `:theme` is the special case — the modal addresses
  it as `[:theme nil <kw>]` because the slot is a flat keyword, not a
  nested map; `assoc-in` on a `nil` key is unsafe, so we special-case.

  Unknown `[section key]` paths are a no-op (and log a tap> so tests
  can assert the rejection)."
  [section key value]
  (cond
    (not (valid-section-key? section key))
    (do (tap> {:tag    ::reject-unknown-setting
               :section section
               :key     key})
        nil)

    (and (= section :theme) (nil? key))
    (do (swap! settings assoc :theme value)
        #?(:cljs (write-storage!))
        nil)

    :else
    (do (swap! settings assoc-in [section key] value)
        #?(:cljs (write-storage!))
        nil)))

(defn reset-settings!
  "Reset the in-memory settings map to `default-settings` and clear
  the localStorage payload. CLJS-only on the storage side; the JVM
  target just resets the atom. Useful from test fixtures + from a
  future 'reset to defaults' affordance in the popup."
  []
  (reset! settings default-settings)
  #?(:cljs
     (try
       (storage-remove! settings-storage-key)
       (catch :default _ nil)))
  nil)

;; ---- *filter-pills* (rf2-ak4ms — ribbon filter seeds + storage key) -----
;;
;; Per `tools/causa/spec/018-Event-Spine.md` §7 ribbon pills persist
;; via localStorage per host-app under a Causa-namespaced key. Two
;; configure! axes:
;;
;;   - `:filters/seed` — initial pill set hosts can ship as a default
;;     (Story testbeds use this to inject a known starting point for
;;     reproducibility). Default `nil` — no seed; the user surfaces
;;     filters themselves per spec/018 §7 'Empty defaults'.
;;
;;   - `:filters/storage-key` — localStorage key the persistence layer
;;     reads / writes. Default `"re-frame2.causa.filters.v1"`. Hosts
;;     that run multiple Causa instances (Story testbeds) override so
;;     each instance keeps its own pill state.
;;
;; The atoms here are the data primitives; the CLJS-only
;; `filters.persistence` ns thunks them through localStorage. CLJC so
;; the JVM test corpus can exercise the configure! round-trip without
;; a CLJS runtime.

(def default-filters
  "Default ribbon filter set Causa ships with — empty, per spec/018 §7
  'Empty defaults' / rf2-ak4ms: first-session honesty beats first-
  session quietness. Shipping a default `:mouse-move` filter would
  silently hide events the user didn't know they were emitting."
  {:in [] :out []})

(defonce
  ^{:doc "Atom holding the host-supplied seed filter set Causa
         hydrates the slot with on FIRST install (when localStorage
         is empty). Default `nil` — no seed; the registry's default
         empty shape wins."}
  filter-seed
  (atom nil))

(defn set-filter-seed!
  "Set the seed filter set the registry hydrates `:active-filters`
  with on first install when localStorage is empty. `nil` clears the
  seed. Shape: `{:in [{:pattern <kw-or-str>}] :out [{:pattern <…>}]}`."
  [seed]
  (reset! filter-seed seed)
  nil)

(defn get-filter-seed
  "Return the current host-supplied filter seed, or nil when unset."
  []
  @filter-seed)

(defonce
  ^{:doc "Atom holding the localStorage key the filter-persistence
         layer reads / writes. Default
         `\"re-frame2.causa.filters.v1\"`. Hosts mount multiple
         Causa instances (Story testbeds) override for isolation."}
  filters-storage-key
  (atom "re-frame2.causa.filters.v1"))

(defn set-filters-storage-key!
  "Replace the localStorage key Causa uses for filter persistence.
  `nil` resets to the default. The CLJS-side `filters.persistence`
  ns reads this atom directly at every load / save so the round-trip
  always honours the current setting — no separate sync call."
  [k]
  (reset! filters-storage-key
          (or k "re-frame2.causa.filters.v1"))
  nil)

(defn get-filters-storage-key
  "Return the current localStorage key for filter persistence."
  []
  @filters-storage-key)

;; ---- configure! convenience ---------------------------------------------

(defn configure!
  "Top-level Causa configuration. Accepts:

    `{:editor <kw>}` — Causa's 'Open in editor' preference (rf2-evgf5).
    `{:project-root <string>}` — on-disk root prepended to the source-
       coord's classpath-relative `:file` slot before the editor URI
       ships (rf2-5m5n2). Default `nil`. Nil / blank clears the slot;
       an absent key leaves the current value untouched. Hosts whose
       source-paths are already absolute can leave this unset.
    `{:layout/host-selector <css-selector>}` — app-provided true-inline
       layout host for the default shell. Defaults to
       `[data-rf-causa-host]`.
    `{:launch/auto-open? <bool>}` — whether the preload auto-opens
       the default true-inline shell after `rf/init!`. Defaults to
       `true`. Story/tool pages that deliberately run without an app
       layout host may set this to `false` before `rf/init!`; explicit
       open!/toggle! still diagnose a missing host.
    `{:launch.keybinding/enabled? <bool>}` — whether `keybinding/attach!`
       installs Causa's global window-level keydown listener
       (Ctrl+Shift+C / Cmd/Ctrl+K / spine bindings). Defaults to
       `true` — standalone Causa needs the listener. Embed hosts
       (Story mounts Causa as RHS) set `false` so their own global
       keybindings — typically `Cmd/Ctrl+K` for the host's command
       palette — are not swallowed by Causa's capture-phase listener.
       Per rf2-4eyik (rf2-q7who Thread A). MUST be set BEFORE the
       Causa preload runs.
    `{:trace/show-sensitive? <bool>}` — privacy gate for `:sensitive?
       true` trace events per Spec 009 §Privacy (rf2-azls9). Defaults
       to `false` — Causa's trace collector drops sensitive events
       and the shell's bottom rail surfaces a `[● REDACTED N]` hint.
       Set to `true` while debugging redaction policy to see the raw
       cascade.
    `{:settings <map>}` — bulk-replace the Settings popup state map
       (rf2-9poxq). Shape mirrors `default-settings`. The popup's
       event surface (`:rf.causa/settings-update`) is the normal
       per-knob write path; this key is the bulk-set escape hatch
       (e.g. host wants to ship its own default theme).
    `{:filters <{:in [...] :out [...]}>}` — host-supplied seed pill
       set the registry hydrates `:active-filters` with on FIRST
       install (when localStorage is empty). Default `nil` per
       spec/018 §7 'Empty defaults' — first-session honesty beats
       first-session quietness (rf2-ak4ms). Story testbeds use this
       to inject a known starting point for reproducibility.
    `{:filters/storage-key <string>}` — localStorage key the filter
       persistence layer reads / writes. Default
       `\"re-frame2.causa.filters.v1\"`. Hosts that run multiple
       Causa instances (Story testbeds) override for isolation.

  Future phases extend this with theme / buffer / placement keys.

  Hosts typically call this once at boot:

      (require '[day8.re-frame2-causa.config :as causa-config])
      (causa-config/configure! {:editor       :cursor
                                :project-root \"C:/Users/me/code/my-app\"
                                :layout/host-selector \"#causa\"
                                :launch/auto-open? true
                                :filters {:out [{:pattern \":mouse-move\"}]}})

  Returns nothing."
  [{:keys [editor project-root settings]
    host-selector-opt :layout/host-selector
    auto-open-opt :launch/auto-open?
    keybinding-opt :launch.keybinding/enabled?
    show-sensitive-opt :trace/show-sensitive?
    filters-opt :filters
    filters-key-opt :filters/storage-key
    :as opts}]
  (when (some? editor)
    (set-editor! editor))
  (when (contains? opts :project-root)
    (set-project-root! project-root))
  (when (contains? opts :layout/host-selector)
    (set-layout-host-selector! host-selector-opt))
  (when (contains? opts :launch/auto-open?)
    (set-auto-open! auto-open-opt))
  (when (contains? opts :launch.keybinding/enabled?)
    (set-keybinding-enabled! keybinding-opt))
  (when (contains? opts :trace/show-sensitive?)
    (set-show-sensitive! show-sensitive-opt))
  ;; NB: `settings` here is the destructured bulk-config map; the
  ;; in-namespace defonce atom is reached via the fully-qualified
  ;; symbol (`day8.re-frame2-causa.config/settings`) to disambiguate.
  ;; rf2-jh9ws: legacy `:telemetry` keys in the bulk-config map are
  ;; silently dropped — the per-section merge here only knows about
  ;; known slots.
  (when (contains? opts :settings)
    (when (map? settings)
      (reset! day8.re-frame2-causa.config/settings
              (-> default-settings
                  (update :general merge (:general settings))
                  (assoc  :theme  (or (:theme settings)
                                      (:theme default-settings)))
                  (update :diff      merge (:diff settings))))
      #?(:cljs (write-storage!))))
  ;; Filter seed + storage key (rf2-ak4ms). Storage key sets BEFORE
  ;; seed so a host that overrides both in one call gets the seed
  ;; persisted under the right key.
  (when (contains? opts :filters/storage-key)
    (set-filters-storage-key! filters-key-opt))
  (when (contains? opts :filters)
    (set-filter-seed! filters-opt))
  nil)

;; ---- pass-through: editor-uri --------------------------------------------

(defn editor-uri
  "Build an 'Open in editor' URI for `source-coord` using Causa's
  configured editor + configured project-root (rf2-5m5n2). Thin
  wrapper around `re-frame.source-coords.editor-uri/editor-uri` that
  reads the current preference from the atom AND threads the
  configured project-root through the helper's 3-arg form. Returns a
  string URI, or nil when the source-coord has no `:file`."
  [source-coord]
  (editor-uri/editor-uri (get-editor) source-coord
                         {:project-root (get-project-root)}))
