(ns day8.re-frame2-causa.config
  "Compile-time and runtime configuration for Causa.

  Phase 1 held a single config concern: the 'Open in editor'
  preference (rf2-evgf5). Causa now also exposes the default inline
  layout-host selector used by the dev preload (rf2-eehov). Future
  phases extend this with theme defaults, buffer depth, etc.

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

(def default-layout-host-snippet
  "<div class=\"app-shell\">
  <aside data-rf-causa-host></aside>
  <main id=\"app\"></main>
</div>")

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

(defn set-show-sensitive!
  "Replace the `:trace/show-sensitive?` flag. Causa's `configure!`
  calls this. `nil` resets to the default (`false`)."
  [v]
  (reset! show-sensitive? (boolean v))
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
    `{:layout/host-selector <css-selector>}` — app-provided true-inline
       layout host for the default shell. Defaults to
       `[data-rf-causa-host]`.
    `{:trace/show-sensitive? <bool>}` — privacy gate for `:sensitive?
       true` trace events per Spec 009 §Privacy (rf2-azls9). Defaults
       to `false` — Causa's trace collector drops sensitive events
       and the shell's bottom rail surfaces a `[● REDACTED N]` hint.
       Set to `true` while debugging redaction policy to see the raw
       cascade.

  Future phases extend this with theme / buffer / placement keys.

  Hosts typically call this once at boot:

      (require '[day8.re-frame2-causa.config :as causa-config])
      (causa-config/configure! {:editor :cursor
                                :layout/host-selector \"#causa\"})

  Returns nothing."
  [{:keys [editor]
    host-selector-opt :layout/host-selector
    show-sensitive-opt :trace/show-sensitive?
    :as opts}]
  (when (some? editor)
    (set-editor! editor))
  (when (contains? opts :layout/host-selector)
    (set-layout-host-selector! host-selector-opt))
  (when (contains? opts :trace/show-sensitive?)
    (set-show-sensitive! show-sensitive-opt))
  nil)

;; ---- pass-through: editor-uri --------------------------------------------

(defn editor-uri
  "Build an 'Open in editor' URI for `source-coord` using Causa's
  configured editor. Thin wrapper around
  `re-frame.source-coords.editor-uri/editor-uri` that reads the current
  preference from the atom. Returns a string URI, or nil when the
  source-coord has no `:file`."
  [source-coord]
  (editor-uri/editor-uri (get-editor) source-coord))
