(ns re-frame.story-mcp.tools.lifecycle
  "Shared variant-lifecycle execution for `run-variant` and
  `preview-variant`.

  This namespace owns blocking invocation, the common timeout, and
  canonical exception normalization through `story/run-result`. That
  boundary guarantees settled and error outcomes carry the same evidence
  slots. Each tool still owns its projection, egress scrubbing,
  annotations, indicators, and wire envelope."
  (:require [re-frame.story       :as story]
            [re-frame.story.async :as async]))

(defn error-outcome
  "Assemble the ONE canonical error run-result for a synchronous throw /
  timeout out of `story/run-variant`, through the SAME `story/run-result`
  boundary a settled run uses (spec/017 §Run result). Pure given the
  variant key `vk` + the thrown `e`.

  Routing the failure through `story/run-result` fills every evidence
  slot with `[]`, so the wire projection never emits a bare `nil` where
  the run-result schema requires a sequence. Two extra slots are stamped
  so both consumers keep the fields they read off the outcome:

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
