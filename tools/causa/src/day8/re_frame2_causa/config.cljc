(ns day8.re-frame2-causa.config
  "Compile-time and runtime configuration for Causa.

  Phase 1 held a single config concern: the 'Open in editor'
  preference (rf2-evgf5). Causa now also exposes the default inline
  layout-host selector and default auto-open switch used by the dev
  preload (rf2-eehov). Future phases extend this with theme defaults,
  buffer depth, etc.

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
            #?@(:cljs [[re-frame.core :as rf]
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
;; 420px)`. Overriding the property anywhere up the cascade (`:root`,
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

      :root { --rf-causa-inline-width: 560px; }

  Causa never reads this property — sizing is owned by the host's
  layout rule (per spec/011-Launch-Modes.md §Layout host contract).
  The constant is published so tooling (story-mode chrome, docs
  generators, the AI co-pilot's snippet helper) can refer to the
  exact spelling without forking the string."
  "--rf-causa-inline-width")

(def default-layout-host-width
  "Default value Causa recommends for `--rf-causa-inline-width` when the
  host does not override it. Matches the historical fixed-pixel default
  from the pre-rf2-um813 testbed snippets so existing pages render
  identically after adopting the variable."
  "420px")

(def default-layout-host-snippet
  ;; DOM order is `<main>` first, host `<aside>` second — flex flow
  ;; lays the aside on the right of the app column (per spec/011-
  ;; Launch-Modes.md §Layout host contract, rf2-e07yk). CSS uses
  ;; `var(--rf-causa-inline-width, 420px)` so a host that pastes the
  ;; snippet verbatim gets:
  ;;   - identical default geometry (420px flex-basis, 320px floor)
  ;;   - a single one-line override path:
  ;;       :root { --rf-causa-inline-width: 560px; }
  ;;   - a user-draggable resize handle via the browser-native
  ;;     `resize: horizontal` + `overflow: auto` on the host
  ;;   - app content to the left stays in normal flex flow
  ;;     (no overlay, no body padding).
  ;;
  ;; `box-sizing: border-box` is required so the documented width
  ;; (the `var(--rf-causa-inline-width, 420px)` value) is the actual
  ;; rendered width INCLUDING the 1px `border-left` separator. Without
  ;; it, the host renders one pixel wider than the documented value —
  ;; an off-by-one that surfaces in any pixel-exact contract (see
  ;; tools/causa/testbeds/inline_resize/spec.cjs).
  "<div class=\"app-shell\">
  <main id=\"app\"></main>
  <aside data-rf-causa-host></aside>
</div>

<style>
  body { margin: 0; }
  .app-shell { display: flex; min-height: 100vh; }
  [data-rf-causa-host] {
    flex: 0 0 var(--rf-causa-inline-width, 420px);
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
  story-mcp, pair2-mcp, causa-mcp) composes against ONE framework
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
    `{:trace/show-sensitive? <bool>}` — privacy gate for `:sensitive?
       true` trace events per Spec 009 §Privacy (rf2-azls9). Defaults
       to `false` — Causa's trace collector drops sensitive events
       and the shell's bottom rail surfaces a `[● REDACTED N]` hint.
       Set to `true` while debugging redaction policy to see the raw
       cascade.

  Future phases extend this with theme / buffer / placement keys.

  Hosts typically call this once at boot:

      (require '[day8.re-frame2-causa.config :as causa-config])
      (causa-config/configure! {:editor       :cursor
                                :project-root \"C:/Users/me/code/my-app\"
                                :layout/host-selector \"#causa\"
                                :launch/auto-open? true})

  Returns nothing."
  [{:keys [editor project-root]
    host-selector-opt :layout/host-selector
    auto-open-opt :launch/auto-open?
    show-sensitive-opt :trace/show-sensitive?
    :as opts}]
  (when (some? editor)
    (set-editor! editor))
  (when (contains? opts :project-root)
    (set-project-root! project-root))
  (when (contains? opts :layout/host-selector)
    (set-layout-host-selector! host-selector-opt))
  (when (contains? opts :launch/auto-open?)
    (set-auto-open! auto-open-opt))
  (when (contains? opts :trace/show-sensitive?)
    (set-show-sensitive! show-sensitive-opt))
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
