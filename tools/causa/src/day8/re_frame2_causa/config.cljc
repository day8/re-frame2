(ns day8.re-frame2-causa.config
  "Compile-time and runtime configuration for Causa.

  Phase 1 holds a single config concern: the 'Open in editor' preference
  (rf2-evgf5). Future phases extend this with theme defaults, buffer
  depth, panel placement, etc.

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
  (:require [re-frame.source-coords.editor-uri :as editor-uri]))

;; ---- editor preference ---------------------------------------------------

(defonce
  ^{:doc "Atom holding Causa's 'Open in editor' preference. Default
         `:vscode`. Accepts the keywords `:vscode` / `:cursor` /
         `:idea` plus the `{:custom \"<uri-template>\"}` form (see
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
  level. Per Spec 009 §Privacy + §Listener filtering semantics — the
  framework stamps `:sensitive? true` on every trace event emitted
  inside a registration that opted in, and consumers branch on this
  flag. Absent/false means non-sensitive."
  [ev]
  (boolean (and (map? ev) (= true (:sensitive? ev)))))

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
  absent — those count under `:global`."
  [frame-id]
  (let [k (or frame-id :global)]
    (swap! suppressed-counters update k (fnil inc 0)))
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
  `trace-bus/clear-buffer!` and from test fixtures."
  ([] (reset! suppressed-counters {}) nil)
  ([frame-id]
   (swap! suppressed-counters dissoc (or frame-id :global))
   nil))

;; ---- configure! convenience ---------------------------------------------

(defn configure!
  "Top-level Causa configuration. Accepts:

    `{:editor <kw>}` — Causa's 'Open in editor' preference (rf2-evgf5).
    `{:trace/show-sensitive? <bool>}` — privacy gate for `:sensitive?
       true` trace events per Spec 009 §Privacy (rf2-azls9). Defaults
       to `false` — Causa's trace collector drops sensitive events
       and the shell's bottom rail surfaces a `[● REDACTED N]` hint.
       Set to `true` while debugging redaction policy to see the raw
       cascade.

  Future phases extend this with theme / buffer / placement keys.

  Hosts typically call this once at boot:

      (require '[day8.re-frame2-causa.config :as causa-config])
      (causa-config/configure! {:editor :cursor})

  Returns nothing."
  [{:keys [editor]
    show-sensitive-opt :trace/show-sensitive?
    :as opts}]
  (when (some? editor)
    (set-editor! editor))
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
