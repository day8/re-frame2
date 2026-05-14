(ns re-frame.ssr.ring.handler-defaults
  "Handler default options + caller-opt validation.

  Merged into caller-supplied opts once at handler-construction time
  so the pipeline helpers (`setup-request-frame!`, `build-full-response`)
  can destructure a single uniform `opts` map without re-stating the
  defaults."
  (:require [re-frame.ssr.ring.shell :as shell]))

(set! *warn-on-reflection* true)

(defn default-on-error
  "Minimal 500 response used when the caller doesn't supply `:on-error`.
  The SSR runtime's error projector handles trace-emitted errors
  during drain; this hook covers exceptions the projector can't see
  (Ring-layer throws, render-time CLJ exceptions).

  Falls back to the exception's class name when `getMessage` returns
  nil (some throwable types throw `null` from `.getMessage`) so the
  body never renders as `\"SSR error: null\"` on the wire — audit
  rf2-asmj1 R5 / cluster rf2-sljs1."
  [_request ^Throwable t]
  {:status  500
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    (str "SSR error: " (or (.getMessage t) (.getName (class t))))})

(def handler-defaults
  {:emit-hash?   true
   :html-shell   shell/default-html-shell
   :content-type "text/html; charset=utf-8"
   :on-error     default-on-error})

(defn validate-handler-opts!
  "Throw a structured `:rf.error/ssr-ring-missing-*` ex-info when a
  caller omits a required `ssr-handler` opt. Extracted from the
  handler body per audit rf2-asmj1 R3 / cluster rf2-sljs1 so the body
  of `ssr-handler` reads as the lifecycle wiring rather than a
  validation-then-wire two-step."
  [{:keys [on-create root-view]}]
  (when-not on-create
    (throw (ex-info ":rf.error/ssr-ring-missing-on-create"
                    {:reason "ssr-handler requires :on-create (an event vector)"})))
  (when-not root-view
    (throw (ex-info ":rf.error/ssr-ring-missing-root-view"
                    {:reason "ssr-handler requires :root-view (a hiccup vector or 0-arity fn)"}))))
