(ns re-frame.story-mcp.tools.lifecycle
  "The ONE owner of the variant-lifecycle EXECUTION the two lifecycle
  tools share — `run-variant` (`tools.testing`) and `preview-variant`
  (`tools.dev`).

  Both tools invoke the SAME `story/run-variant` lifecycle, block on it
  with the SAME single-threaded-stdio ceiling, catch a synchronous throw /
  timeout, and normalize that failure into a unified run-result. Their
  catch paths had DRIFTED: `preview-variant` hand-built a partial error map
  (`{:status :error :lifecycle :error :assertions […] :checks []}`) while
  `run-variant` assembled the canonical shape through `story/run-result`.
  A hand-mint omits the six `.4` evidence slots (`:schema-violations` /
  `:warnings` / `:effects` / `:sub-runs` / `:renders` / `:narrative`),
  which then read back as ABSENT → nil → a bare `nil` on the wire, failing
  the frozen `[:sequential :any]` schema every settled run satisfies
  (rf2-5r6j96).

  This leaf owns the shared execution — blocking invocation, timeout
  blocking, and ONE canonical exception normalization — so the two tools
  cannot drift again. What stays in the caller namespaces (per the bead's
  boundary) is everything AFTER the outcome: the preview / run-specific
  projection, egress scrubbing, annotations, indicator counts, and wire
  shaping. This leaf produces the settled-or-error OUTCOME; the callers
  shape it."
  (:require [re-frame.story       :as story]
            [re-frame.story.async :as async]))

(defn error-outcome
  "Assemble the ONE canonical error run-result for a synchronous throw /
  timeout out of `story/run-variant`, through the SAME `story/run-result`
  boundary a settled run uses (spec/017 §Run result). Pure given the
  variant key `vk` + the thrown `e`.

  Routing the failure through `story/run-result` (rather than hand-minting
  a partial map) fills every `.4` evidence slot to `[]` — so the wire
  projection always has a sequential to walk and never ships a bare `nil`
  for an absent slot (rf2-5r6j96). Two extra slots are stamped so BOTH
  consumers keep the fields they read off the outcome:

  - `:lifecycle :error` — the loader-lifecycle STATE `preview-variant`
    surfaces (distinct from the `:status` verdict);
  - `:frame vk` — the variant frame id `run-variant` surfaces (so the
    canonical outcome is self-describing rather than relying on each
    caller's `(:frame outcome vk)` fallback)."
  [vk e]
  (assoc (story/run-result
           {:variant/id vk
            :assertions [(story/assertion-record
                           {:assertion :rf.error/run-failed
                            :passed?   false
                            :error     true
                            :reason    (ex-message e)})]})
         :lifecycle :error
         :frame     vk))

(defn run-variant-blocking
  "Invoke `story/run-variant` for `vk` with `opts` and BLOCK for its
  unified run-result, capped at `timeout-ms` (the single-threaded-stdio
  ceiling both lifecycle tools share via `targs/resolve-timeout-ms`). A
  synchronous throw or a timeout-elapsed deref is normalized into the ONE
  canonical `error-outcome` — so `run-variant` and `preview-variant`
  return the SAME settled-or-error vocabulary and can never drift in their
  blocking / failure policy.

  Returns the outcome map (a settled run-result, or the canonical error
  outcome). The caller projects / scrubs / wire-shapes it."
  [vk opts timeout-ms]
  (try
    (async/deref-blocking (story/run-variant vk opts) timeout-ms)
    (catch Throwable e
      (error-outcome vk e))))
