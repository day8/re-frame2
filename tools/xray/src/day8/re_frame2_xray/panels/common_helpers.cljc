(ns day8.re-frame2-xray.panels.common-helpers
  "Shared pure-data helpers used by every Xray panel-helper ns.

  ## What lives here

    - `now-ms`   — host-clock abstraction (testable via with-redefs).
    - `tag-of`   — defensive trace-event tag reader.
    - `panel-row-cap` + `cap-rows` — the canonical 200-row rendering
      cap (per `tools/xray/spec/007-UX-IA.md` §Performance budget).
      One source of truth; panels apply it at their row-rendering
      boundary so DOM mount count stays bounded regardless of how
      deep the underlying derivation grows.
    - `format-time-hms` — render ms-since-epoch as `HH:MM:SS.mmm`;
      shared across the trace / routes / issues-ribbon / mcp-server
      ribbons so all four feeds share an identical visual clock.
    - `dispatch-id-of-epoch` — resolve an `:rf/epoch-record`'s settling
      cascade-id by walking its `:trace-events`. Shared by
      time-travel-helpers; previously duplicated as
      `dispatch-id-from-epoch` / `dispatch-id-of-epoch` with
      identical algebra.

  ## Why a shared cap

  The 200-row budget is pinned in
  `test/.../perf_budget_cljs_test.cljc:88-92` as a hard contract but
  was historically enforced only in `machine_inspector_helpers/
  cap-transitions`. Eight long-list panels silently iterated whole
  row vectors with `for`, exploding DOM mount + React-reconciliation
  cost once the trace ring filled. Promoting the cap to a shared
  helper closes that gap — every long-list panel applies the same
  cap at the same boundary, with the same `:over-cap?` /
  `:hidden-count` shape so the view can render a consistent overflow
  affordance."
  (:refer-clojure :exclude [cap-rows]))

(defn now-ms
  "Return host-clock time in ms. Pure-ish — abstracted so test
  fixtures can stub via `with-redefs`. Cross-platform via
  `#?(:clj ... :cljs ...)`."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

(defn tag-of
  "Pull a tag value off a trace event. Trace events nest per-event
  metadata under `:tags`; the helper reads the slot defensively so
  test fixtures that supply a flat shape (no `:tags`) also work.
  Canonical tag-reader shared across every panel-helper that walks
  the Xray trace buffer."
  [ev k]
  (or (get-in ev [:tags k])
      (get ev k)))

;; ---- 200-row rendering cap ----------------------------------------------

(def panel-row-cap
  "The 200-row-per-panel rendering cap pinned in
  `tools/xray/spec/007-UX-IA.md` §Performance budget L611-612 and
  asserted by `test/.../perf_budget_cljs_test.cljc:88-92`. Every
  long-list panel applies this cap at its row-rendering boundary
  before handing rows to the view, so DOM mount count is bounded
  regardless of how deep the underlying derivation grows."
  200)

(defn cap-rows
  "Apply the panel-row cap. Returns `[capped over-cap? hidden-count]`:

      capped       — the first `n` rows (vector). Empty when `rows`
                     is nil / empty.
      over-cap?    — true iff the cap dropped at least one row.
      hidden-count — `(count rows) - n` when over-cap?, else 0.

  Default cap is `panel-row-cap` (200). Panels splat the result and
  use `over-cap?` + `hidden-count` to drive an overflow indicator
  (e.g. `+N rows hidden — narrow the filter to see more`). Pure fn;
  JVM-runnable.

  Mirrors `machine_inspector_helpers/cap-transitions` shape with the
  caller-visible overflow metadata folded in. Callers that only want
  the capped vector can `(first (cap-rows rows))`."
  ([rows] (cap-rows rows panel-row-cap))
  ([rows n]
   (let [v     (if (vector? rows) rows (vec (or rows [])))
         total (count v)]
     (if (<= total n)
       [v false 0]
       [(subvec v 0 n) true (- total n)]))))

;; ---- formatting ---------------------------------------------------------

(defn pluralize
  "Return `noun` with its plural suffix appended iff `n` is not exactly 1.

  The canonical English-count pluralizer shared across every panel
  helper that renders a `<count> <noun>` summary — `1 descriptor` vs
  `2 descriptors`, `1 row` vs `0 rows`. Returns the NOUN only (no count),
  so callers compose it as `(str n \" \" (pluralize n \"descriptor\"))`.

  The 2-arg form appends `\"s\"`. The 3-arg form takes an explicit
  `plural-suffix` for irregular nouns (e.g. `(pluralize n \"child\"
  \"ren\")` → `child` / `children`). Pure; JVM-runnable."
  ([n noun] (pluralize n noun "s"))
  ([n noun plural-suffix]
   (str noun (when (not= 1 n) plural-suffix))))

(defn format-time-hms
  "Render `t` (ms-since-epoch) as `HH:MM:SS.mmm`. Pure-ish — uses the
  platform Date constructor. Canonical shared formatter — the trace,
  routes, issues-ribbon and mcp-server feeds all share this clock so
  the four ribbons read with an identical visual rhythm. JVM-testable
  iff the caller passes a stable time (the runtime clock differs by
  JVM vs. browser locale but the algebra is identical).

  Returns nil when `t` is not a number, so views can render an em-dash
  on missing timestamps without guarding the call site."
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

;; ---- epoch → dispatch-id resolution -------------------------------------

(defn dispatch-id-of-epoch
  "Return an `:rf/epoch-record`'s settling cascade `:dispatch-id`, or nil
  when none is known (synthetic epochs from `replace-app-db!`, a rejected
  dispatch that never reached run-start).

  Per rf2-rly4a the record carries `:dispatch-id` as a first-class slot
  (pinned in `re-frame.epoch.assembly/build-record` from the settling
  event's `:rf.event/run-start` tags) — read it directly. This is the stable
  link between the epoch ring (epoch-id space) and the raw trace stream's
  cascade list (dispatch-id space) that Xray's `:rf.xray/focus`
  correlation pivots on, and it survives both `:trace-events-keep`
  elision on older records and the post-settle reactive back-fill (which
  pads `:trace-events` with nil-`:dispatch-id` sub-run / render events).

  Falls back to a `:trace-events` walk for records produced before the
  slot existed (or restored fixtures that omit it) — the first cascade-
  root `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` tag. The fallback is nil-
  safe: a record with neither the slot nor a dispatch-id-bearing trace
  yields nil.

  Pure data → cascade-id-or-nil. Used by Xray's `:rf.xray/focus`
  epoch-id correlation and the time-travel panel (when building a fresh
  pin or deciding chip presentation)."
  [epoch-record]
  (or (:dispatch-id epoch-record)
      (some (fn [ev]
              (or (get-in ev [:tags :rf.trace/dispatch-id])
                  (get-in ev [:tags :rf.trace/parent-dispatch-id])))
            (:trace-events epoch-record))))
