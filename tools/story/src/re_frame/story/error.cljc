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
  - `:stack`   — `(.printStackTrace e)` (JVM) / `(.-stack e)` (CLJS);
    nil when `e` is nil;
  - `:data`    — `(ex-data e)`; nil for a non-`ExceptionInfo` throwable
    and nil for a nil `e` (`ex-data` already guards the instance check,
    so no explicit `instance?` test is needed).

  `exception-record` wraps that projection in the full
  `:rf.error/exception` assertion record per IMPL-SPEC §5.5 +
  `002-Runtime.md` §Error projection."
  (:refer-clojure :exclude [error]))

(defn throwable->error-map
  "Project a (possibly nil) Throwable into the canonical
  `{:message :stack :data}` error map. Reader-conditional, pure on both
  runtimes.

  `opts` (optional):
    :message — an explicit message string used in place of the
               throwable's own message (the trace-drain path supplies a
               pre-extracted `:exception-message`). Falls back to the
               throwable's message when nil/absent."
  ([err] (throwable->error-map err nil))
  ([err {:keys [message]}]
   {:message (or message
                 #?(:clj  (when err (.getMessage ^Throwable err))
                    :cljs (when err (str err))))
    :stack   #?(:clj  (when err (with-out-str (.printStackTrace ^Throwable err)))
                :cljs (when err (.-stack err)))
    ;; `ex-data` already returns nil for a non-ExceptionInfo (and a nil)
    ;; throwable, so no explicit `instance?` guard is needed.
    :data    (ex-data err)}))

(defn exception-record
  "Build the full `:rf.error/exception` assertion record projected when a
  phase throws (IMPL-SPEC §5.5 + `002-Runtime.md` §Error projection). The
  record lands in `:rf.story/assertions` so the variant's last result map
  surfaces the failure; `:passed? false` makes the run-level aggregation
  report a failure.

  `phase` is the originating phase (`:phase-1-loaders`, `:phase-2-events`,
  `:phase-4-play`, `:phase-teardown`, …); `event` is the originating event
  vector (may be nil); `err` is the (possibly nil) Throwable.

  `opts` is threaded to `throwable->error-map` (the trace-drain path's
  pre-extracted `:message`)."
  ([variant-id phase event err] (exception-record variant-id phase event err nil))
  ([variant-id phase event err opts]
   {:assertion  :rf.error/exception
    :variant-id variant-id
    :phase      phase
    :event      event
    :error      (throwable->error-map err opts)
    :passed?    false}))
