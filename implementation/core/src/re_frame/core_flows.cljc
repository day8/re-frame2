(ns re-frame.core-flows
  "Public-API wrappers for the optional flows artefact (Spec 013).
  Implementation ships in `day8/re-frame2-flows` (`re-frame.flows` ns).

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
  message live in one place."
  {:hook :flows/reg-flow :artefact flows-artefact :on-absent :throw}
  ([flow]      [flow {}])
  ([flow opts] :delegate))
