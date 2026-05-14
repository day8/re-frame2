(ns re-frame.core-ssr
  "Public-API wrappers for the optional SSR artefact (Spec 011).
  Implementation ships in `day8/re-frame2-ssr` (`re-frame.ssr`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private ssr-artefact
  {:error-keyword :rf.error/ssr-artefact-missing
   :maven         "day8/re-frame2-ssr"
   :require-ns    "re-frame.ssr"})

(defwrapper render-to-string
  "Render a hiccup tree to an HTML string. Per Spec 011 §The render-tree
  → HTML emitter. Delegates to the installed substrate adapter's
  :render-to-string slot — for the plain-atom adapter (JVM/SSR) that
  routes through re-frame.ssr; for Reagent it can route through
  reagent.dom.server. opts may carry :doctype? to prepend '<!DOCTYPE html>'
  and :emit-hash? to inject data-rf-render-hash on the root element.
  Late-bound via :ssr/render-to-string."
  {:hook :ssr/render-to-string :artefact ssr-artefact :on-absent :throw}
  ([render-tree]      [render-tree {}])
  ([render-tree opts] :delegate))

(defwrapper render-tree-hash
  "Stable structural hash of a render tree (FNV-1a 32-bit, lowercase
  hex). Identical output on JVM and CLJS for the same canonical-EDN
  representation. Per Spec 011 §Hydration-mismatch detection. Late-bound
  via :ssr/render-tree-hash."
  {:hook :ssr/render-tree-hash :artefact ssr-artefact :on-absent :throw}
  ([render-tree] :delegate))

;; -reg-error-projector / -reg-head carry an explicit `:where` because the
;; wrapper-name leads with `-` (private-helper convention) but the user-
;; facing surface drops it (`rf/reg-error-projector` / `rf/reg-head`); the
;; defwrapper default would otherwise stamp the throw with the `-` form.

(defwrapper -reg-error-projector
  "Internal helper — prefer `reg-error-projector` from public callers.
  This is the fn-form delegate the public macro / CLJS alias forward to.
  Late-bound via :ssr/reg-error-projector."
  {:hook :ssr/reg-error-projector :where 'rf/reg-error-projector
   :artefact ssr-artefact :on-absent :throw :ex-data {:id id}}
  ([id projector-fn]          [id {} projector-fn])
  ([id metadata projector-fn] :delegate))

(defwrapper project-error
  "Apply the active error projector for frame-id to the trace event.
  Returns a :rf/public-error map. Per Spec 011 §Server error projection.
  Late-bound via :ssr/project-error."
  {:hook :ssr/project-error :artefact ssr-artefact :on-absent :throw
   :ex-data {:frame frame-id}}
  ([frame-id trace-event] :delegate))

;; ---- Head/meta contract — rf2-4dra9 --------------------------------------
;;
;; Per Spec 011 §Head/meta contract. `re-frame.ssr.head` ships the impl;
;; the wrappers below look the producing fns up through the late-bind hook
;; table so core never statically requires re-frame.ssr.head. Apps that
;; don't pull `day8/re-frame2-ssr` see `:rf.error/ssr-artefact-missing`
;; when these surfaces are called.

(defwrapper -reg-head
  "Internal helper — prefer `reg-head` from public callers. This is the
  fn-form delegate the public macro / CLJS alias forward to. Late-bound
  via :ssr/reg-head."
  {:hook :ssr/reg-head :where 'rf/reg-head
   :artefact ssr-artefact :on-absent :throw :ex-data {:id id}}
  ([id head-fn]          [id {} head-fn])
  ([id metadata head-fn] :delegate))

(defwrapper render-head
  "Apply the head fn registered under `head-id` against a frame's
  app-db and active route. Returns the produced `:rf/head-model`. Per
  Spec 011 §Head/meta contract. Late-bound via :ssr/render-head.

  Two opt shapes:

    (render-head head-id frame-id)
    (render-head head-id {:frame frame-id :route route})"
  {:hook :ssr/render-head :artefact ssr-artefact :on-absent :throw
   :ex-data {:head-id head-id}}
  ([head-id opts] :delegate))

(defwrapper active-head
  "Look up the active route's `:head` metadata; if set, call
  `render-head` and return the model. Otherwise return the
  default head per Spec 011 §Default head. Late-bound via
  :ssr/active-head.

  Arities:

    (active-head)            — uses the default frame `:rf/default`.
    (active-head frame-id)"
  {:hook :ssr/active-head :artefact ssr-artefact :on-absent :throw
   :ex-data {:frame frame-id}}
  ([]         :delegate)
  ([frame-id] :delegate))

(defwrapper head-model->html
  "Render a `:rf/head-model` map to its inner-head HTML fragment in
  canonical order. Per Spec 011 §Default flow step 4. Late-bound via
  :ssr/head-model-html (the hook key drops the user-facing fn's
  `->` decoration for late-bind naming hygiene)."
  {:hook :ssr/head-model-html :artefact ssr-artefact :on-absent :throw}
  ([head-model]      [head-model {}])
  ([head-model opts] :delegate))
