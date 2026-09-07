(ns re-frame.core-ssr
  "Public-API wrappers for the optional SSR artefact's two REGISTRARS
  (Spec 011). Implementation ships in `day8/re-frame2-ssr`
  (`re-frame.ssr` / `re-frame.ssr.head`).

  Only `reg-error-projector` and `reg-head` ride the façade. The SSR
  QUERY surface (`render-to-string`, `render-tree-hash`, `project-error`,
  `render-head`, `active-head`, `head-model->html`) is NOT re-exported:
  loading `re-frame.ssr` is what installs the SSR runtime, so every SSR
  app already names the artefact namespace and reaches those reads at
  home (rf2-kuky.44 / rf2-kuky.87).

  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private ssr-artefact
  {:error-keyword :rf.error/ssr-artefact-missing
   :maven         "day8/re-frame2-ssr"
   :require-ns    "re-frame.ssr"})

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

;; ---- Head/meta contract — rf2-4dra9 --------------------------------------
;;
;; Per Spec 011 §Head/meta contract. `re-frame.ssr.head` ships the impl;
;; the registrar below looks the producing fn up through the late-bind
;; hook table so core never statically `:require`s `re-frame.ssr.head`.
;; Apps that don't pull `day8/re-frame2-ssr` see
;; `:rf.error/ssr-artefact-missing` when this surface is called. The head
;; READS live on `re-frame.ssr.head` and are reached there directly.

(defwrapper -reg-head
  "Internal helper — prefer `reg-head` from public callers. This is the
  fn-form delegate the public macro / CLJS alias forward to. Late-bound
  via :ssr/reg-head."
  {:hook :ssr/reg-head :where 'rf/reg-head
   :artefact ssr-artefact :on-absent :throw :ex-data {:id id}}
  ([id head-fn]          [id {} head-fn])
  ([id metadata head-fn] :delegate))
