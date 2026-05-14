(ns day8.re-frame2-causa.panels.mcp-server-helpers
  "Pure-data helpers for Causa's MCP Server panel (Phase 5, rf2-81qjj,
  parent rf2-5aw5v).

  ## Why a separate `.cljc` ns

  Same dual-target pattern as the other Causa panel helpers (e.g.
  `issues_ribbon_helpers.cljc`, `trace_helpers.cljc`): the *view* in
  `mcp_server.cljs` paints hiccup and dispatches into the Causa frame;
  the *logic* — filter the trace buffer to events tagged
  `:origin :causa-mcp`, project each event into a flat row shape,
  classify the empty state — is pure data → data. Splitting the
  algebra into `.cljc` so it runs under the JVM unit-test target
  (`clojure -M:test`) is required by the standing rule
  `feedback_jvm_interop_must_work.md`.

  ## Substrate (per `tools/causa/spec/010-MCP-Server.md` §Origin tagging
  + `tools/causa-mcp/spec/Principles.md` §Origin tagging is the
  convention)

  The causa-mcp jar tags every side-effect it performs on the trace
  bus with `:tags :origin :causa-mcp`. The panel reads these tagged
  events out of Causa's trace-buffer and surfaces them as a read-only
  feed so the user can see at-a-glance what the AI agent is doing in
  their app.

  Closed-set `:origin` vocabulary per Spec 009 §Origin axis:

      :app         — application code (user clicks, timers, normal dispatch)
      :pair        — tools/pair2-mcp/ (editor-side AI workflows)
      :causa-mcp   — tools/causa-mcp/ (debugger-side AI workflows)
      :story       — Tool-Pair stories playing back in the browser
      :test        — test runs (cljs.test, kaocha, etc.)

  This panel filters to `:causa-mcp` exclusively; the Trace panel
  remains the all-origins surface.

  ## INFERENTIAL DECISIONS (rf2-81qjj — spec-deficient bead)

  The bead flagged three open questions. This helper records the
  inferential calls made under §Phase 5 of rf2-5aw5v; each is a
  candidate for refinement via a follow-on bead.

  **(a) Sidebar panel y/n** — yes, a dedicated `:mcp-server` panel.
      Rationale: parallels every other Phase 5 panel (Issues, Trace,
      Performance, …) and gives the user a single entry point for
      'what is the agent doing.' Settings-only would force users to
      hunt across the Trace panel with the `:origin` filter manually
      set every session.

  **(b) Origin colour for `:causa-mcp`** — `cyan` `#06B6D4`.
      Rationale: spec/007-UX-IA.md §Colour system locks `:pair` to
      indigo `#5570FF`; `:story` / `:test` already share cyan
      `#43C3D0` (light-cyan). `:causa-mcp` gets a deeper / more
      saturated cyan `#06B6D4` (Tailwind cyan-500) to distinguish from
      the lighter `:story` cyan without re-using indigo. The colour
      token registers under `:causa-mcp-cyan` in the panel; a
      follow-on bead can promote it to spec/007-UX-IA.md §Colour
      system + the shared shell token map.

  **(c) Bidirectional Causa→agent surface** — OUT OF SCOPE for v1.
      Rationale: the causa-mcp jar is the agent→host direction;
      anything the panel would 'expose back' (e.g. session attention
      hints) is a causa-mcp implementation concern, not a Causa
      panel concern. Filed as follow-on if Mike wants it.

  ## Filter axes (panel-local)

  The panel always pre-filters to `:origin :causa-mcp`. On top of
  that, two view-toggleable axes:

      :op-type-set     — which op-types to include (default: all
                         seen, presented as toggleable chips)
      :since-ms        — relative time window in ms (nil = all)

  Each axis is independent; empty filter sets / nil since-ms
  disable the axis.

  ## Pull-only — no probe

  The panel does NOT probe for the causa-mcp jar's session-id
  sentinel. Detection runs implicitly: if any event tagged
  `:origin :causa-mcp` has landed in the buffer the agent IS
  attached. Empty buffer (no causa-mcp events) → 'No agent
  activity observed' empty state. This is simpler than a separate
  detector loop and matches the trace-bus-is-truth posture of
  every other Causa panel."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa.panels.common-helpers :as common]))

;; ---- the inferential origin colour --------------------------------------

(def causa-mcp-origin-colour
  "Hex colour for `:origin :causa-mcp`. v1 inferential pick — Tailwind
  cyan-500 `#06B6D4`. Distinct from `:pair`'s indigo `#5570FF` (locked
  in spec/007-UX-IA.md §Colour system) and from `:story` / `:test`'s
  lighter cyan `#43C3D0` (the existing `:cyan` token in shell.cljs).

  Follow-on bead candidate: promote into spec/007-UX-IA.md §Colour
  system + the shared shell token map so every panel that renders an
  `:origin` swatch reads the same source of truth."
  "#06B6D4")

(def causa-mcp-origin-tag
  "The closed-set origin keyword Causa-MCP stamps on every side-effect
  it performs. Documented in `tools/causa/spec/010-MCP-Server.md`
  §Origin tagging + `tools/causa-mcp/spec/Principles.md` §Origin
  tagging is the convention."
  :causa-mcp)

;; ---- defaults -----------------------------------------------------------

(def default-since-ms
  "Default 'since' window in ms. The panel shows agent events from
  this many ms ago to now. 10 minutes — long enough that a programmer
  stepping back from the screen can return and still see the burst,
  parity with the Issues ribbon."
  600000)

;; ---- origin classification ----------------------------------------------

(defn origin-of
  "Project the dispatch-origin slot per Spec 009 §Origin tagging
  (`:tags :origin`). Defensive against absence — returns nil. Mirrors
  `trace_helpers.cljc/origin-of` so the two panels are in lockstep."
  [ev]
  (get-in ev [:tags :origin]))

(defn causa-mcp-event?
  "True iff `ev` carries `:tags :origin :causa-mcp`. The single
  classifier the panel uses to slice the buffer. Pure data → bool;
  JVM-testable."
  [ev]
  (= causa-mcp-origin-tag (origin-of ev)))

;; ---- source-coord projection --------------------------------------------

(defn source-coord
  "Extract a `file:line` string from `:rf.trace/trigger-handler`'s
  `:source-coord` slot. Per Spec 009 every emit inside a dispatch
  carries this slot when handler scope is bound (per rf2-3nn8 /
  rf2-lf84g). Pure data → string-or-nil; JVM-testable. Mirrors the
  helpers in issues_ribbon_helpers.cljc / trace_helpers.cljc."
  [ev]
  (when-let [trigger (:rf.trace/trigger-handler ev)]
    (let [{:keys [file line]} (:source-coord trigger)]
      (when file
        (cond-> file
          line (str ":" line))))))

;; ---- short description --------------------------------------------------

(defn short-description
  "Build the per-row one-line description. Reads (in priority order):

    1. `[:tags :event]`               — dispatched event vector
    2. `[:tags :tool]`                — which causa-mcp tool ran
    3. `[:tags :reason]`              — error categories
    4. `[:tags :exception-message]`   — handler / fx exceptions
    5. `[:tags :sub-id]`              — sub-related ops
    6. `[:tags :fx-id]`               — fx invocations
    7. `(str operation)` only         — fallback

  Pure data → string; JVM-testable. The `:tool` slot is the canonical
  causa-mcp annotation per the spec/010-MCP-Server.md catalogue (each
  tool name lands as a tag so the buffer carries the agent-action
  provenance)."
  [{:keys [operation tags] :as _ev}]
  (let [op-str (if operation (str operation) "(unknown)")
        detail (or (when (vector? (:event tags))
                     (try (pr-str (:event tags))
                          (catch #?(:clj Throwable :cljs :default) _ nil)))
                   (when (some? (:tool tags))
                     (str (:tool tags)))
                   (:reason tags)
                   (:exception-message tags)
                   (when (some? (:sub-id tags))
                     (str (:sub-id tags)))
                   (when (some? (:fx-id tags))
                     (str (:fx-id tags))))]
    (if (and detail (not (str/blank? (str detail))))
      (str op-str " — " detail)
      op-str)))

;; ---- per-row projection -------------------------------------------------

(defn project-row
  "Project one raw trace event into the panel's row shape:

      {:id              <int>
       :time            <ms>
       :op-type         <kw>
       :operation       <kw>
       :origin          <kw>          ;; always :causa-mcp by construction
       :tool            <kw-or-nil>   ;; lifted off :tags :tool
       :description     <string>
       :source-coord    <string-or-nil>
       :dispatch-id     <int-or-nil>
       :raw             <trace-event>}

  Pure data → data; JVM-testable. Returns nil when `ev` is not a
  causa-mcp-tagged event so callers can `keep` over a mixed stream."
  [{:keys [id time op-type operation tags] :as ev}]
  (when (causa-mcp-event? ev)
    {:id              id
     :time            time
     :op-type         op-type
     :operation       operation
     :origin          causa-mcp-origin-tag
     :tool            (:tool tags)
     :description     (short-description ev)
     :source-coord    (source-coord ev)
     :dispatch-id     (or (:dispatch-id tags)
                          (get-in ev [:tags :dispatch-id]))
     :raw             ev}))

(defn project-rows
  "Filter `events` to the causa-mcp subset and project each one.
  Returns a vector in chronological order (oldest first). Pure data →
  data; JVM-testable."
  [events]
  (into []
        (keep project-row)
        events))

;; Re-export `now-ms` so existing callers (registry.cljs thunks the
;; `:rf.causa/mcp-server-feed` sub via `(mcp-helpers/now-ms)`) keep
;; working without churn. Body lives in `common-helpers`.
(def now-ms common/now-ms)

;; ---- filter application -------------------------------------------------

(defn passes-op-type?
  "True iff `row`'s op-type is in the active filter set, or if the
  filter set is empty (= no op-type restriction). Pure data → bool;
  JVM-testable."
  [active-op-types {:keys [op-type] :as _row}]
  (or (empty? active-op-types)
      (contains? active-op-types op-type)))

(defn passes-since?
  "True iff `row`'s `:time` is within `since-ms` of `now`. Pure data →
  bool; JVM-testable. Mirrors issues_ribbon_helpers/passes-since?

  `nil` `since-ms` disables the axis. `nil` `:time` falls back to
  'in window' so events without a stamp still surface — defensive
  against malformed trace events."
  [now since-ms {:keys [time] :as _row}]
  (or (nil? since-ms)
      (nil? time)
      (and (number? time)
           (number? now)
           (>= time (- now since-ms)))))

(defn apply-filters
  "Apply the two filter axes to `rows`. Pure data → vector;
  JVM-testable. Each axis is independent — empty filter sets / nil
  since-ms disable the axis.

  `filters` is `{:op-types #{...} :since-ms <ms>}`. `now` is the
  wall-clock 'now' to test :time against (helper-injected so tests
  don't depend on the system clock)."
  [rows filters now]
  (let [{:keys [op-types since-ms]} filters]
    (filterv
      (fn [row]
        (and (passes-op-type? op-types row)
             (passes-since? now since-ms row)))
      rows)))

;; ---- op-type enumeration -------------------------------------------------

(defn distinct-op-types
  "Return the distinct op-types present in `rows` in first-seen order.
  The view uses this to populate the op-type chip row with only the
  op-types that have at least one row — empty chips would be noise.
  Pure data → vector; JVM-testable."
  [rows]
  (into []
        (comp (map :op-type)
              (remove nil?)
              (distinct))
        rows))

(defn op-type-counts
  "Histogram of op-type → count over `rows`. Drives chip badge counts.
  Pure data → map; JVM-testable."
  [rows]
  (reduce
    (fn [acc {:keys [op-type]}]
      (if (nil? op-type)
        acc
        (update acc op-type (fnil inc 0))))
    {}
    rows))

;; ---- detection ----------------------------------------------------------

(defn agent-attached?
  "True iff at least one causa-mcp-tagged event has landed in the
  buffer. The pull-only proxy for 'is the agent attached' — the
  trace-bus is the truth, no separate sentinel probe required.

  Pure data → bool; JVM-testable."
  [events]
  (boolean (some causa-mcp-event? events)))

;; ---- composite projection (the panel reads this) ------------------------

(defn project-feed
  "Top-level projection — produces every slot the view needs. Pure
  data → data; JVM-testable.

  Walks the trace buffer once via `project-rows`, applies the filter
  pass, and computes the op-type histogram + distinct-axis off the
  projection. Cost is bounded by the trace ring depth (Spec 009
  §Trace bus). The single-pass collapse is deferred until row caps
  land and measurable residual cost remains (rf2-60vcu).

  Returns:

      {:rows               [<row> ...]       ;; post-filter, newest first
       :total              <int>             ;; pre-filter count
       :rendered           <int>             ;; post-filter count
       :op-type-counts     {op-type count}
       :distinct-op-types  [<op-type> ...]
       :filters            <pass-through>
       :agent-attached?    <bool>
       :empty-kind         <:no-activity / :no-matches / nil>}

  `events` is the raw trace-buffer. `filters` is the current filter
  state. `now` is wall-clock ms.

  `:empty-kind` discriminates:

      :no-activity — no causa-mcp events at all (agent not attached
                     OR has done nothing this session).
      :no-matches  — events exist but the active filters hide them
                     all. The view paints 'No matches' with a
                     clear-filters affordance.
      nil          — at least one event passed; render the feed."
  [events filters now]
  (let [all              (project-rows events)
        filtered         (apply-filters all filters now)
        ;; Newest first for display — parity with the Issues ribbon's
        ;; feed orientation.
        sorted-display   (vec (reverse filtered))
        op-type-counts'  (op-type-counts all)
        empty-kind       (cond
                           (empty? all)      :no-activity
                           (empty? filtered) :no-matches
                           :else             nil)]
    {:rows              sorted-display
     :total             (count all)
     :rendered          (count filtered)
     :op-type-counts    op-type-counts'
     :distinct-op-types (distinct-op-types all)
     :filters           filters
     :agent-attached?   (boolean (seq all))
     :empty-kind        empty-kind}))

;; ---- selection ----------------------------------------------------------

(defn find-row
  "Look up a projected row by `:id` in `rows`. Returns nil when not
  found. Pure data → row-or-nil; JVM-testable."
  [rows row-id]
  (some (fn [v] (when (= row-id (:id v)) v)) rows))

;; ---- formatting ---------------------------------------------------------

(defn format-time
  "Render `t` (ms-since-epoch) as `HH:MM:SS.mmm`. Matches the Issues
  ribbon / Trace panel's format so the three feeds share a visual
  rhythm. Pure-ish — uses the platform Date constructor."
  [t]
  (when (number? t)
    #?(:clj  (let [^java.time.Instant inst (java.time.Instant/ofEpochMilli (long t))
                   ^java.time.LocalTime lt (.toLocalTime
                                             (.atZone inst (java.time.ZoneId/systemDefault)))]
               (format "%02d:%02d:%02d.%03d"
                       (.getHour lt)
                       (.getMinute lt)
                       (.getSecond lt)
                       (long (mod t 1000))))
       :cljs (let [d   (js/Date. t)
                   pad (fn [n w]
                         (let [s (str n)]
                           (if (< (count s) w)
                             (str (apply str (repeat (- w (count s)) "0")) s)
                             s)))]
               (str (pad (.getHours d) 2) ":"
                    (pad (.getMinutes d) 2) ":"
                    (pad (.getSeconds d) 2) "."
                    (pad (.getMilliseconds d) 3))))))
