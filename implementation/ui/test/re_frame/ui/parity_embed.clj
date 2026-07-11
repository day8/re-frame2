(ns re-frame.ui.parity-embed
  "The corpus's CROSS-HOST bridge. `embedded-jvm-semantics` expands in
  the CLJS compiler's OWN JVM (macros are ordinary Clojure): it loads
  the corpus fixtures as Clojure (the same .cljc source shadow is
  compiling for the browser side), renders every case through the JVM
  emitter, applies normalization N, and embeds the results as a literal
  `{case-id [semantic-root ...]}` map in the compiled CLJS test.

  The node test then renders the SAME cases through react-dom/server
  and compares in N's space — live parity on every test run, no golden
  files to regenerate, drift on either emitter fails immediately.

  Cache honesty: shadow-cljs invalidates compiled namespaces on macro-
  NAMESPACE file changes; a deep JVM-side change (e.g. emit_jvm.cljc)
  can leave a warm local cache stale until the test file (or this file)
  is touched — the same caveat every defview consumer already carries.
  CI compiles cold, so the gate itself is always live."
  (:require [re-frame.ui.parity-fixtures :as fx]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.tree :as tree]))

(defmacro embedded-jvm-semantics
  "-> literal {case-id [semantic-node ...]} computed by the JVM emitter
  + normalization N at CLJS compile time."
  []
  (into {}
        (map (fn [{:keys [id view props]}]
               (let [view-fn (get fx/views view)]
                 (when-not view-fn
                   (throw (ex-info (str "parity corpus: no view for case " id)
                                   {:case id :view view})))
                 [id (semantic/normalize (tree/render view-fn props))])))
        fx/cases))
