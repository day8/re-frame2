(ns re-frame.machines.error-emit
  "Always-on emission for `:rf.error/machine-action-exception`.

  This namespace owns only the production-surviving structural record, routed
  through the late-bound error-emission substrate because the optional machines
  artefact cannot require core's emitter.

  Dev traces remain direct `trace/emit-error!` calls at each call site. Under
  `goog.DEBUG=false` those calls and their diagnostic prose fold away; routing
  their maps through this always-on helper would retain that prose in the
  production bundle.

  The always-on record may carry `:actor-id`, `:failing-id`, `:state`, `:phase`,
  `:recovery`, `:frame`, and the raw `:exception`. Value-bearing `:event`,
  `:input`, `:exception-data`, and free-text diagnostics belong only in the
  privacy-gated dev trace. Per Spec 009 §Error event catalogue."
  (:require [re-frame.interop   :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(defn emit-machine-action-exception!
  "Emit the structural `:rf.error/machine-action-exception` record on the
  always-on channel. Returns nil.

  `always-on` is caller-built from the structural fields described in the
  namespace docstring. This function stamps `:error` and `:time` before
  dispatch; it must not receive free-text prose or value-bearing slots.

  Dev `trace/emit-error!` calls must remain direct at their call sites so their
  maps can be eliminated from production bundles."
  [always-on]
  ;; Late-bound because this optional artefact cannot require the core emitter.
  (when-let [dispatch-record! (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-record! (assoc always-on
                             :error :rf.error/machine-action-exception
                             :time  (rf.interop/now-ms))))
  nil)
