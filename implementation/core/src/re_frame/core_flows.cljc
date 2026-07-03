(ns re-frame.core-flows
  "Public-API wrappers for the optional flows artefact (Spec 013).
  Implementation ships in `day8/re-frame2-flows` (`re-frame.flows`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private flows-artefact
  {:error-keyword :rf.error/flows-artefact-missing
   :maven         "day8/re-frame2-flows"
   :require-ns    "re-frame.flows"})

(defwrapper clear-flow
  "Per Spec 013 §Lifecycle: clear a flow from a frame's registry and
  vacate its output path. Late-bound via :flows/clear-flow."
  {:hook :flows/clear-flow :artefact flows-artefact :on-absent :throw
   :ex-data {:flow-id id}}
  ([id]      [id {}])
  ([id opts] :delegate))

(defwrapper reg-flow
  "Fn-form delegate that performs the late-bind lookup for `reg-flow`.
  The `re-frame.core/reg-flow` macro (JVM) and the CLJS `def`-alias both
  route here, so the late-bind logic and the missing-artefact error
  message live in one place.

  Per the canonical Spec 001 3-slot grammar (rf2-bqstzr): `(reg-flow
  flow-id metadata derive-fn)` — the pure `:derive` fn is the THIRD slot,
  the middle slot is the reflection-config metadata map (`:inputs`,
  `:output-path`, `:doc`, `:schema`, EP-0025 classification keys, and the
  `:frame` mounting key). The 2-arg `(reg-flow flow-id derive-fn)` recurses
  with an empty metadata map (which then fails validation for the required
  `:inputs` / `:output-path`)."
  {:hook :flows/reg-flow :artefact flows-artefact :on-absent :throw
   :arglists '([flow-id metadata derive-fn] [flow-id derive-fn])}
  ([flow-id derive-fn]          [flow-id {} derive-fn])
  ([flow-id metadata derive-fn] :delegate))
