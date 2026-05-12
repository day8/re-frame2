(ns re-frame.core-ssr
  "Public-API wrappers for the optional SSR artefact (Spec 011).

  Per rf2-uo7v the SSR implementation ships in the
  `day8/re-frame2-ssr` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.ssr]` — that would pull the pure
  hiccup → HTML emitter, the FNV-1a render-tree-hash machinery, the
  per-request `[:rf/response]` accumulator, the six `:rf.server/*`
  server-only fxs, the `reg-error-projector` registry kind plus its
  built-in default, the SSR error-projection trace listener, the
  `:rf/hydrate` event, and every `:rf.ssr/*` / `:rf.server/*` keyword
  string onto every consumer's classpath even when no server-side
  rendering is performed.

  The fns in this namespace look the ssr API up through the late-bind
  hook table at call time, which the ssr artefact populates from its
  own ns-load.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them) so `core.cljc` is not cluttered with optional-artefact glue.
  The single-import contract is preserved: users continue to write
  `rf/render-to-string` after `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]))

(defn render-to-string
  "Render a hiccup tree to an HTML string. Per Spec 011 §The render-tree
  → HTML emitter. Delegates to the installed substrate adapter's
  :render-to-string slot — for the plain-atom adapter (JVM/SSR) that
  routes through re-frame.ssr; for Reagent it can route through
  reagent.dom.server. opts may carry :doctype? to prepend '<!DOCTYPE html>'
  and :emit-hash? to inject data-rf-render-hash on the root element.
  Late-bound via :ssr/render-to-string."
  ([render-tree] (render-to-string render-tree {}))
  ([render-tree opts]
   (if-let [f (late-bind/get-fn :ssr/render-to-string)]
     (f render-tree opts)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'render-to-string
                      :recovery :no-recovery
                      :reason   "rf/render-to-string requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))

(defn render-tree-hash
  "Stable structural hash of a render tree (FNV-1a 32-bit, lowercase
  hex). Identical output on JVM and CLJS for the same canonical-EDN
  representation. Per Spec 011 §Hydration-mismatch detection. Late-bound
  via :ssr/render-tree-hash."
  [render-tree]
  (if-let [f (late-bind/get-fn :ssr/render-tree-hash)]
    (f render-tree)
    (throw (ex-info ":rf.error/ssr-artefact-missing"
                    {:where    'render-tree-hash
                     :recovery :no-recovery
                     :reason   "rf/render-tree-hash requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))

(defn -reg-error-projector
  "Internal helper — prefer `reg-error-projector` from public callers.
  This is the fn-form delegate the public macro / CLJS alias forward to.
  Late-bound via :ssr/reg-error-projector."
  ([id projector-fn]
   (-reg-error-projector id {} projector-fn))
  ([id metadata projector-fn]
   (if-let [f (late-bind/get-fn :ssr/reg-error-projector)]
     (f id metadata projector-fn)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'reg-error-projector
                      :id       id
                      :recovery :no-recovery
                      :reason   "rf/reg-error-projector requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))

(defn project-error
  "Apply the active error projector for frame-id to the trace event.
  Returns a :rf/public-error map. Per Spec 011 §Server error projection.
  Late-bound via :ssr/project-error."
  [frame-id trace-event]
  (if-let [f (late-bind/get-fn :ssr/project-error)]
    (f frame-id trace-event)
    (throw (ex-info ":rf.error/ssr-artefact-missing"
                    {:where    'project-error
                     :frame    frame-id
                     :recovery :no-recovery
                     :reason   "rf/project-error requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))
