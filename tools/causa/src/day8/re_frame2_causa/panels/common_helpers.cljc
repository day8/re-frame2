(ns day8.re-frame2-causa.panels.common-helpers
  "Shared pure-data helpers used by every Causa panel-helper ns.

  ## What lives here

    - `now-ms`   — host-clock abstraction (testable via with-redefs).
    - `tag-of`   — defensive trace-event tag reader.
    - `panel-row-cap` + `cap-rows` — the canonical 200-row rendering
      cap (per `tools/causa/spec/007-UX-IA.md` §Performance budget).
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
  the Causa trace buffer."
  [ev k]
  (or (get-in ev [:tags k])
      (get ev k)))

;; ---- 200-row rendering cap ----------------------------------------------

(def panel-row-cap
  "The 200-row-per-panel rendering cap pinned in
  `tools/causa/spec/007-UX-IA.md` §Performance budget L611-612 and
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
  "Walk an `:rf/epoch-record`'s `:trace-events` for the first
  cascade-root `:dispatch-id` tag and return it; nil when no
  dispatch-id-bearing event is present (synthetic epochs from
  `reset-frame-db!` record `:trace-events []`).

  Pure data → cascade-id-or-nil. Used by the time-travel panel
  (when building a fresh pin or deciding chip presentation)."
  [epoch-record]
  (some (fn [ev]
          (or (get-in ev [:tags :dispatch-id])
              (get-in ev [:tags :parent-dispatch-id])))
        (:trace-events epoch-record)))
