(ns re-frame.story.error
  "The ONE Throwable→error-map projection + `:rf.error/exception`
  assertion-record builder, shared across the Story runtime.

  Lives at the leaf of the require graph (depends on `clojure.core` /
  the host runtime only) so any sibling namespace — `runtime`, `frames`,
  `play`, `render` — can `:require` it without cycle risk. Companion leaf
  to `re-frame.story.predicates` (pure data→data) and
  `re-frame.story.late-bind` (run-time fn resolution).

  ## Why this exists (rf2-9kpsq)

  The reader-conditional Throwable→`{:message :stack :data}` projection
  was copy-pasted VERBATIM across five sites in four namespaces, and the
  wrapping `:rf.error/exception` assertion-record was duplicated three
  times. The copies had begun to DRIFT — one site wrapped each accessor
  in a `when`-guard, another dropped `:stack` / `:data` entirely. A
  reader had to diff five near-identical blocks to confirm they agreed.

  Consolidating to one tested projection both removes the drift surface
  and FIXES the two accidental divergences toward the canonical full
  shape: `:stack` and `:data` are always present (nil when unavailable),
  and a nil throwable is tolerated (the trace-event drain path may carry
  a pre-extracted message without the original exception).

  ## The canonical shape

  `throwable->error-map` returns `{:message :stack :data}`:

  - `:message` — `(.getMessage e)` (JVM) / `(str e)` (CLJS); a caller
    MAY supply an explicit `:message` override (the trace-drain path
    carries a pre-extracted `:exception-message` and falls back to it
    when no throwable is in hand);
  - `:stack`   — the rendered stack trace string. On the JVM we MUST
    capture via an explicit `PrintWriter`: the no-arg
    `Throwable.printStackTrace()` writes to `System/err` (a Java stream
    `with-out-str` does NOT intercept — it only rebinds Clojure's
    `*out*`), so capturing the trace requires handing `printStackTrace`
    a writer directly. On CLJS it is `(.-stack e)`. nil when `e` is nil;
  - `:data`    — `(ex-data e)` projected through
    `re-frame.elision/elide-wire-value` so author-keyed `ex-data`
    sourced from path-marked app-db slots records `:rf/redacted`
    rather than the raw value (rf2-294yq5.5; spec/API.md §Error-projection
    records, spec/002 §Error projection §Privacy). nil for a
    non-`ExceptionInfo` throwable and nil for a nil `e` (`ex-data`
    already guards the instance check, so no explicit `instance?` test
    is needed). The `:message` string is NOT auto-walked (author
    responsibility — spec/Security.md §Author guidance).

  `exception-record` wraps that projection in the full
  `:rf.error/exception` assertion record per `002-Runtime.md` §Error projection.

  ## Pipeline-exception projection (rf2-294yq5.2)

  The framework router no longer collapses every interceptor-chain
  failure into `:rf.error/handler-exception` — per
  `implementation/core/src/re_frame/router.cljc` §classify-pipeline-exception
  it classifies the captured throw into the TRUE failing component:

  - a coeffect-injection throw  → `:rf.error/coeffect-exception`
  - a user-interceptor throw    → `:rf.error/interceptor-exception`
  - the event handler throwing  → `:rf.error/handler-exception`

  All three carry `:op-type :error` and the same tag shape
  (`:event` / `:exception` / `:failing-id`). Story's phase-capture
  listeners had drifted: they matched ONLY `:rf.error/handler-exception`,
  so a loader/event/play/teardown path whose cofx injector or user
  interceptor threw was a silent false-green. `pipeline-exception-event?`
  is the ONE projection predicate every capture site now consults —
  any of the three operations targeting the frame is a captured
  failure (spec/009 §Error contract; rf2-294yq5)."
  (:require [re-frame.elision :as elision]
            ;; rf2-7737vq — the canonical RAW trace-event frame reader
            ;; (`re-frame.trace/trace-event-frame`), replacing Story's
            ;; hand-rolled `[:tags :frame]` read. Core framework leaf, so
            ;; the require keeps this ns at the leaf of the cycle graph.
            [re-frame.trace   :as trace])
  (:refer-clojure :exclude [error]))

(def pipeline-exception-operations
  "The framework `:rf.error/*` operations that represent an
  interceptor-chain (event-pipeline) exception Story projects onto the
  variant's `:rf.story/assertions`. Per the router's
  `classify-pipeline-exception` (rf2-mszrz) a chain throw is attributed
  to its true failing component — a cofx injector, a user interceptor,
  or the event handler itself — so Story must capture all three rather
  than only `:rf.error/handler-exception` (rf2-294yq5.2)."
  #{:rf.error/handler-exception
    :rf.error/coeffect-exception
    :rf.error/interceptor-exception})

(defn pipeline-exception-event?
  "True iff trace event `ev` is a pipeline exception (one of
  `pipeline-exception-operations`) targeting `frame-id`. The ONE
  predicate every Story phase-capture site (runtime loaders/events,
  play, frame setup/teardown) consults so a cofx / user-interceptor
  failure is captured with the same fidelity as a handler throw
  (rf2-294yq5.2). Reads the raw event's frame via the canonical
  `re-frame.trace/trace-event-frame` reader (`[:tags :frame]` — spec/009
  §Frame identity on the raw event, rf2-7737vq)."
  [frame-id ev]
  (and (contains? pipeline-exception-operations (:operation ev))
       (= frame-id (trace/trace-event-frame ev))))

(defn- elide-ex-data
  "Project an `ex-data` map through `re-frame.elision/elide-wire-value`
  keyed on `frame-id`, so author-keyed slots sourced from path-marked
  app-db paths record `:rf/redacted` rather than the raw value
  (rf2-294yq5.5; spec/002 §Error projection §Privacy). Tolerant
  (record-don't-throw): any elision error returns the raw value
  unchanged so redaction failure never breaks error recording.

  Elision is FRAME-SCOPED. It applies only when a `frame-id` is in
  hand — that is the variant-frame assertion-record path, the only one
  where author-keyed sensitive app-db values can reach `ex-data`. A nil
  `frame-id` (or a nil `data`) passes the value through UNCHANGED: those
  are the frame-free framework error results (a plan-compilation failure,
  an unknown-variant result, a render-host throw) whose `ex-data` is
  structural framework metadata (`:rf.error/id`, `:where`), not
  app-db-sourced — running the wire-walker frameless would fail closed
  and wrongly redact the whole map. Per-frame elision is reachable only
  when the frame is KNOWN (EP-0002 §Privacy And Egress)."
  [frame-id data]
  (if (or (nil? data) (nil? frame-id))
    data
    (try
      (elision/elide-wire-value data {:frame frame-id})
      (catch #?(:clj Throwable :cljs :default) _ data))))

(defn throwable->error-map
  "Project a (possibly nil) Throwable into the canonical
  `{:message :stack :data}` error map. Reader-conditional, pure on both
  runtimes EXCEPT for the frame-aware `:data` wire-elision (rf2-294yq5.5).

  `opts` (optional):
    :message — an explicit message string used in place of the
               throwable's own message (the trace-drain path supplies a
               pre-extracted `:exception-message`). Falls back to the
               throwable's message when nil/absent.
    :frame   — the Story frame the error belongs to. Threaded to
               `re-frame.elision/elide-wire-value` so the `:data` slot's
               author-keyed sensitive paths substitute `:rf/redacted`
               (spec/002 §Error projection §Privacy). Absent ⇒ frameless
               egress, which fails closed (the whole `ex-data` redacts)."
  ([err] (throwable->error-map err nil))
  ([err {:keys [message frame]}]
   {:message (or message
                 #?(:clj  (when err (.getMessage ^Throwable err))
                    :cljs (when err (str err))))
    ;; `with-out-str` rebinds Clojure's `*out*`, but the no-arg
    ;; `Throwable.printStackTrace()` writes to `System/err` — so the
    ;; trace LEAKED to stderr (trailing every green test run with a bare
    ;; stack trace) while `:stack` captured the empty string (rf2-qk0h9).
    ;; Hand `printStackTrace` an explicit `PrintWriter` so the trace
    ;; lands in `:stack` and nothing escapes to the console.
    :stack   #?(:clj  (when err
                        (let [sw (java.io.StringWriter.)]
                          (.printStackTrace ^Throwable err (java.io.PrintWriter. sw))
                          (str sw)))
                :cljs (when err (.-stack err)))
    ;; `ex-data` already returns nil for a non-ExceptionInfo (and a nil)
    ;; throwable, so no explicit `instance?` guard is needed. The raw
    ;; ex-data is projected through the frame-aware wire-elision walker
    ;; (rf2-294yq5.5) so sensitive author-keyed slots redact before the
    ;; record reaches any observation surface.
    :data    (elide-ex-data frame (ex-data err))}))

(defn exception-record
  "Build the full `:rf.error/exception` assertion record projected when a
  phase throws (`002-Runtime.md` §Error projection). The
  record lands in `:rf.story/assertions` so the variant's last result map
  surfaces the failure; `:passed? false` makes the run-level aggregation
  report a failure.

  `phase` is the originating phase (`:phase-1-loaders`, `:phase-2-events`,
  `:phase-4-play`, `:phase-teardown`, …); `event` is the originating event
  vector (may be nil); `err` is the (possibly nil) Throwable.

  `opts` (optional):
    :message    — pre-extracted message override (the trace-drain path).
    :operation  — the originating framework error operation
                  (`:rf.error/handler-exception` /
                  `:rf.error/coeffect-exception` /
                  `:rf.error/interceptor-exception`), preserved on the
                  record so a consumer can tell a cofx failure from a
                  handler failure (rf2-294yq5.2). Absent ⇒ omitted.
    :failing-id — the true failing component id the router attributed
                  (the cofx id, the interceptor id, or the event id),
                  preserved alongside `:operation`. Absent ⇒ omitted.

  `variant-id` is threaded as the `:frame` for the `:data` wire-elision
  (rf2-294yq5.5) so a Story error record redacts under that frame's
  marks."
  ([variant-id phase event err] (exception-record variant-id phase event err nil))
  ([variant-id phase event err {:keys [operation failing-id] :as opts}]
   (cond-> {:assertion  :rf.error/exception
            :variant-id variant-id
            :phase      phase
            :event      event
            :error      (throwable->error-map err (assoc opts :frame variant-id))
            :passed?    false}
     operation  (assoc :operation operation)
     failing-id (assoc :failing-id failing-id))))
