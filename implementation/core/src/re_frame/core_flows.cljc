(ns re-frame.core-flows
  "Public-API wrappers for the optional flows artefact (Spec 013).

  Per rf2-tfw3 the flows implementation ships in the
  `day8/re-frame2-flows` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.flows]` — that would pull the per-frame
  flow registry, the topological-sort engine, and the dirty-check
  `last-inputs` map onto every consumer's classpath even when no flow
  is registered.

  The fns in this namespace look the flows API up through the late-bind
  hook table at call time, which the flows artefact populates from its
  own ns-load.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them as `rf/reg-flow`, `rf/clear-flow`) so `core.cljc` is not
  cluttered with optional-artefact glue. The single-import contract is
  preserved: users continue to write `rf/reg-flow` after
  `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]))

(defn clear-flow
  "Per Spec 013 §Lifecycle: clear a flow from a frame's registry and
  vacate its output path. Late-bound via :flows/clear-flow."
  ([id] (clear-flow id {}))
  ([id opts]
   (if-let [f (late-bind/get-fn :flows/clear-flow)]
     (f id opts)
     (throw (ex-info ":rf.error/flows-artefact-missing"
                     {:where    'clear-flow
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
                     {:where    'reg-flow
                      :recovery :no-recovery
                      :reason   "rf/reg-flow requires day8/re-frame2-flows on the classpath; add it to deps and require re-frame.flows at app boot."})))))
