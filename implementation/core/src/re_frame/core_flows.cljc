(ns re-frame.core-flows
  "Public-API wrappers for the optional flows artefact (Spec 013).
  Implementation ships in `day8/re-frame2-flows` (`re-frame.flows`
  ns) per rf2-tfw3.

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention) — wrappers
  look the producing fns up via the late-bind hook table at call time;
  consumers reach the surfaces through `re-frame.core` re-exports.

  Per-feature carve-out: the flows artefact pulls the per-frame flow
  registry, the topological-sort engine, and the dirty-check
  `last-inputs` map — none of which appear on a consumer's classpath
  when this wrapper's hooks are unregistered."
  (:require [re-frame.late-bind :as late-bind]))

(defn clear-flow
  "Per Spec 013 §Lifecycle: clear a flow from a frame's registry and
  vacate its output path. Late-bound via :flows/clear-flow."
  ([id] (clear-flow id {}))
  ([id opts]
   (if-let [f (late-bind/get-fn :flows/clear-flow)]
     (f id opts)
     (throw (ex-info ":rf.error/flows-artefact-missing"
                     {:where    'rf/clear-flow
                      :flow-id  id
                      :recovery :no-recovery
                      :reason   "rf/clear-flow requires day8/re-frame2-flows on the classpath; add it to deps and require re-frame.flows at app boot."})))))

(defn reg-flow
  "Fn-form delegate that performs the late-bind lookup for `reg-flow`.
  The `re-frame.core/reg-flow` macro (JVM) and the CLJS `def`-alias both
  route here, so the late-bind logic and the missing-artefact error
  message live in one place."
  ([flow] (reg-flow flow {}))
  ([flow opts]
   (if-let [f (late-bind/get-fn :flows/reg-flow)]
     (f flow opts)
     (throw (ex-info ":rf.error/flows-artefact-missing"
                     {:where    'rf/reg-flow
                      :recovery :no-recovery
                      :reason   "rf/reg-flow requires day8/re-frame2-flows on the classpath; add it to deps and require re-frame.flows at app boot."})))))
