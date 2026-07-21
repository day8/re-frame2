(ns probe.macros
  "S0 carrier-proof macro (rf2-u53yy.1.1). Mirrors the two re-frame.ui carrier
   mechanisms the install/cache-tax re-architecture depends on:

     variant A — def :meta.  re-frame.ui stamps framework metadata onto a
                 generated view Var via `(vary-meta vname merge var-meta)`
                 (compiler/emit_cljs.cljc:1027, var-meta at :984-988).
     variant B — ns-level analyzer data.  re-frame.ui mutates the live analyzer
                 namespace map at expansion via `swap! cljs.env/*compiler*` into
                 [:cljs.analyzer/namespaces <ns> ...] (compiler/emit_cljs.cljc:1004-1007).

   Each expansion appends a WITNESS line at compile time, so a genuine disk-cache
   HIT (the macro is NOT re-run) is provable independently of Shadow's own logs:
   on a cache hit the witness file does not grow, yet the carriers must still be
   present in the restored analyzer data — proving they round-tripped through the
   disk cache rather than being re-stamped."
  (:require [clojure.java.io :as io]
            [cljs.env :as cljs-env]))

(def ^:private witness-file (io/file "target" "s0-witness" "expansions.txt"))

(defmacro defprobe
  "Emits `(def sym ...)` whose Var carries namespaced EDN-only metadata under
   :probe.rf2/descriptor (variant A), and stamps :probe.rf2/ns-descriptor into
   the current namespace's analyzer map (variant B). Both carriers hold ONLY
   plain EDN (keywords, strings, numbers, maps, sets, vectors)."
  [sym descriptor]
  ;; --- recompile witness: fires ONLY when this macro actually expands ---
  (io/make-parents witness-file)
  (spit witness-file
        (str (System/currentTimeMillis) \tab (ns-name *ns*) \/ sym \newline)
        :append true)
  ;; --- variant B: stamp arbitrary namespaced EDN into ns-level analyzer data ---
  (when (some? cljs-env/*compiler*)
    (let [cur-ns (or (some-> &env :ns :name) (ns-name *ns*))]
      (swap! cljs-env/*compiler* assoc-in
             [:cljs.analyzer/namespaces cur-ns :probe.rf2/ns-descriptor]
             {:stamped-for (name sym)
              :schema-version 1
              :members #{:a :b :c}})))
  ;; --- variant A: def carrying namespaced EDN-only :meta ---
  `(def ~(vary-meta sym merge {:probe.rf2/descriptor descriptor})
     ~(str "probe-value-" sym)))
