(ns re-frame.core-flows
  "Public-API wrappers for the optional flows artefact (Spec 013).
  Implementation ships in `day8/re-frame2-flows` (`re-frame.flows`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [artefact-info defwrapper]]
                                        :cljs [:refer        [artefact-info]
                                               :refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private flows-artefact (artefact-info :flows))

(defwrapper clear-flow
  "Per Spec 013 §Re-registration: remove the flow definition `id`; each frame
  running it vacates its output on its next event. Late-bound via
  :flows/clear-flow.

  Not a public name of its own — the public door is `(rf/clear :flow id)`,
  which is why `:where` names it."
  {:hook :flows/clear-flow :artefact flows-artefact :on-absent :throw
   :where 'rf/clear
   :ex-data {:flow-id id}}
  ([id] :delegate))

(defwrapper reg-flow
  "Fn-form delegate that performs the late-bind lookup for `reg-flow`.
  The `re-frame.core/reg-flow` macro (JVM) and the CLJS `def`-alias both
  route here, so the late-bind logic and the missing-artefact error
  message live in one place.

  Per the canonical Spec 001 3-slot grammar: `(reg-flow
  flow-id metadata derive-fn)` — the pure `:derive` fn is the THIRD slot,
  the middle slot is the reflection-config metadata map (`:inputs`,
  `:output-path`, `:doc`, `:schema`, EP-0025 classification keys). A
  single 3-slot arity only — `:inputs` /
  `:output-path` are mandatory, so (like `reg-route` / `reg-resource`)
  there is no 2-arity."
  {:hook :flows/reg-flow :artefact flows-artefact :on-absent :throw
   :arglists '([flow-id metadata derive-fn])}
  ([flow-id metadata derive-fn] :delegate))
