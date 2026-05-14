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

  rf2-kzvwq / security audit 2026-05-14 §P2.1 — the body MUST NOT leak
  the throwable's message. `.getMessage` carries internal topology that
  has no business reaching the wire: JDBC URLs (host, port, database
  name), file paths under deploy roots, partial SQL fragments, server-
  internal class names. Pre-fix, an attacker who could trigger any
  unhandled JVM exception would see e.g.
  `\"SSR error: Connection refused: jdbc:postgresql://internal-db.svc:5432/auth\"`
  on the public wire — direct topology disclosure.

  We now emit a fixed generic body matching the projector's
  `fallback-public-error` shape. Apps that want dev-mode detail
  override via `:on-error` (the recommended pattern is
  `(if dev? log-and-detail log-only-quietly)`)."
  [_request ^Throwable _t]
  {:status  500
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body    "Internal error"})

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
