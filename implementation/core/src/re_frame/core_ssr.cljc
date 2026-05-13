(ns re-frame.core-ssr
  "Public-API wrappers for the optional SSR artefact (Spec 011).
  Implementation ships in `day8/re-frame2-ssr` (`re-frame.ssr` ns)
  per rf2-uo7v.

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention) — wrappers
  look the producing fns up via the late-bind hook table at call time;
  consumers reach the surfaces through `re-frame.core` re-exports.

  Per-feature carve-out: the SSR artefact pulls the hiccup → HTML
  emitter, the FNV-1a render-tree-hash machinery, the per-request
  `[:rf/response]` accumulator, the six `:rf.server/*` fxs, the
  `reg-error-projector` registry kind plus its default, the
  `:rf/hydrate` event, and every `:rf.ssr/*` / `:rf.server/*` keyword
  string — none of which appear on a consumer's classpath when this
  wrapper's hooks are unregistered."
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
                     {:where    'rf/render-to-string
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
                    {:where    'rf/render-tree-hash
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
                     {:where    'rf/reg-error-projector
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
                    {:where    'rf/project-error
                     :frame    frame-id
                     :recovery :no-recovery
                     :reason   "rf/project-error requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))

;; ---- Head/meta contract — rf2-4dra9 --------------------------------------
;;
;; Per Spec 011 §Head/meta contract. `re-frame.ssr.head` ships the impl;
;; the wrappers below look the producing fns up through the late-bind hook
;; table so core never statically requires re-frame.ssr.head. Apps that
;; don't pull `day8/re-frame2-ssr` see `:rf.error/ssr-artefact-missing`
;; when these surfaces are called.

(defn -reg-head
  "Internal helper — prefer `reg-head` from public callers. This is the
  fn-form delegate the public macro / CLJS alias forward to. Late-bound
  via :ssr/reg-head."
  ([id head-fn]
   (-reg-head id {} head-fn))
  ([id metadata head-fn]
   (if-let [f (late-bind/get-fn :ssr/reg-head)]
     (f id metadata head-fn)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'rf/reg-head
                      :id       id
                      :recovery :no-recovery
                      :reason   "rf/reg-head requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))

(defn render-head
  "Apply the head fn registered under `head-id` against a frame's
  app-db and active route. Returns the produced `:rf/head-model`. Per
  Spec 011 §Head/meta contract. Late-bound via :ssr/render-head.

  Two opt shapes:

    (render-head head-id frame-id)
    (render-head head-id {:frame frame-id :route route})"
  [head-id opts]
  (if-let [f (late-bind/get-fn :ssr/render-head)]
    (f head-id opts)
    (throw (ex-info ":rf.error/ssr-artefact-missing"
                    {:where    'rf/render-head
                     :head-id  head-id
                     :recovery :no-recovery
                     :reason   "rf/render-head requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))

(defn active-head
  "Look up the active route's `:head` metadata; if set, call
  `render-head` and return the model. Otherwise return the
  default head per Spec 011 §Default head. Late-bound via
  :ssr/active-head.

  Arities:

    (active-head)            — uses the default frame `:rf/default`.
    (active-head frame-id)"
  ([]
   (if-let [f (late-bind/get-fn :ssr/active-head)]
     (f)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'rf/active-head
                      :recovery :no-recovery
                      :reason   "rf/active-head requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."}))))
  ([frame-id]
   (if-let [f (late-bind/get-fn :ssr/active-head)]
     (f frame-id)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'rf/active-head
                      :frame    frame-id
                      :recovery :no-recovery
                      :reason   "rf/active-head requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))

(defn head-model->html
  "Render a `:rf/head-model` map to its inner-head HTML fragment in
  canonical order. Per Spec 011 §Default flow step 4. Late-bound via
  :ssr/head-model-html (the hook key drops the user-facing fn's
  `->` decoration for late-bind naming hygiene)."
  ([head-model]
   (head-model->html head-model {}))
  ([head-model opts]
   (if-let [f (late-bind/get-fn :ssr/head-model-html)]
     (f head-model opts)
     (throw (ex-info ":rf.error/ssr-artefact-missing"
                     {:where    'rf/head-model->html
                      :recovery :no-recovery
                      :reason   "rf/head-model->html requires day8/re-frame2-ssr on the classpath; add it to deps and require re-frame.ssr at app boot."})))))
