(ns re-frame-2.core
  "Public API surface for re-frame2. Per docs/specification/API.md.

  Users `(:require [re-frame-2.core :as rf])` and call `rf/dispatch`,
  `rf/reg-event-fx`, etc. Internal namespaces are not part of the
  contract.

  This namespace re-exports the canonical surface; per-namespace docs
  carry the design rationale."
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.frame :as frame]
            [re-frame-2.router :as router]
            [re-frame-2.events :as events]
            [re-frame-2.fx :as fx]
            [re-frame-2.cofx :as cofx]
            [re-frame-2.subs :as subs]
            [re-frame-2.interceptor :as interceptor]
            [re-frame-2.std-interceptors :as std-interceptors]
            [re-frame-2.schemas :as schemas]
            [re-frame-2.flows :as flows]
            [re-frame-2.machines :as machines]
            [re-frame-2.routing :as routing]
            [re-frame-2.trace :as trace]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.substrate.plain-atom :as plain-atom]))

;; ---- registration ---------------------------------------------------------

(def reg-event-db    events/reg-event-db)
(def reg-event-fx    events/reg-event-fx)
(def reg-event-ctx   events/reg-event-ctx)
(def reg-sub         subs/reg-sub)
(def reg-fx          fx/reg-fx)
(def reg-cofx        cofx/reg-cofx)
(def reg-frame       frame/reg-frame)

(defn reg-view
  "Register a view by id. The render-fn is `(fn [args...] hiccup-tree)`.

  This is the JVM-runnable / SSR-friendly form. CLJS apps using Reagent
  should prefer `re-frame-2.views/reg-view` (a macro that also defs the
  local var) for client-side use."
  ([id render-fn]
   (reg-view id {} render-fn))
  ([id metadata render-fn]
   (registrar/register! :view id (assoc metadata :handler-fn render-fn))
   id))

(defn get-view
  "Return the render fn for a registered view by id, or nil if not
  registered. The wrapped fn called with the view's invocation args
  yields the hiccup tree."
  [id]
  (when-let [meta (registrar/lookup :view id)]
    (:handler-fn meta)))

(defn render-to-string
  "Render a hiccup tree to an HTML string. Per Spec 011 §The render-tree
  → HTML emitter. The plain-atom adapter dispatches through to
  re-frame-2.ssr/render-to-string. opts may carry :doctype? to prepend
  '<!DOCTYPE html>'."
  ([render-tree] (render-to-string render-tree {}))
  ([render-tree opts]
   ;; Lazy-resolve so the SSR ns isn't required up-front.
   (let [r2s (requiring-resolve 're-frame-2.ssr/render-to-string)]
     (when r2s ((deref r2s) render-tree opts)))))
(def make-frame      frame/make-frame)
(def reset-frame     frame/reset-frame!)
(def destroy-frame   frame/destroy-frame!)
(def reg-flow        flows/reg-flow)
(def clear-flow      flows/clear-flow)
(def reg-route       routing/reg-route)
(def reg-app-schema  schemas/reg-app-schema)
(def reg-machine     machines/reg-machine)

;; ---- clearing -------------------------------------------------------------

(def clear-event events/clear-event)
(def clear-sub   subs/clear-sub)
(def clear-fx    fx/unregister-fx)
(def clear-subscription-cache! subs/clear-subscription-cache!)

;; ---- dispatch and subscribe -----------------------------------------------

(def dispatch       router/dispatch!)
(def dispatch-sync  router/dispatch-sync!)
(def subscribe      subs/subscribe)
(def subscribe-value subs/subscribe-value)
(def unsubscribe    subs/unsubscribe)

;; ---- frame-aware closures (runtime side) ---------------------------------
;;
;; Per Spec 002 §View ergonomics and Spec 004 §Affordance for plain fns.
;; *current-frame* is the JVM-and-CLJS-shared dynamic var that with-frame
;; binds. CLJS adds a React-context bridge in re-frame-2.views; this lets
;; plain fns (tests, JVM SSR, REPL) capture-and-bind a frame without
;; Reagent.

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Return the active frame keyword. Resolution: dynamic var → :rf/default.
  CLJS extends this with a React-context lookup; the JVM stops at the
  dynamic var."
  []
  (or *current-frame* :rf/default))

(defn dispatcher
  "Return a fn that dispatches an event under the current frame.
  Captures the frame at call time, so closures do not need to thread it.

  Per Spec 004 §Affordance for plain fns:
    (let [d (rf/dispatcher)] [:button {:on-click #(d [:inc])} ...])"
  []
  (let [frame (current-frame)]
    (fn dispatch-fn
      ([event] (dispatch event {:frame frame}))
      ([event opts] (dispatch event (assoc opts :frame frame))))))

(defn subscriber
  "Return a fn that subscribes under the current frame. Captures the
  frame at call time. The returned fn delegates to subscribe."
  []
  (let [frame (current-frame)]
    (fn subscribe-fn
      [query-v]
      (subscribe frame query-v))))

(defn with-frame
  "Run `thunk` with *current-frame* bound to `frame-id`. The macro form
  in re-frame-2.views wraps an expression in a thunk; this fn variant
  is JVM-friendly for tests / SSR / REPL."
  [frame-id thunk]
  (binding [*current-frame* frame-id]
    (thunk)))

(defn bound-dispatcher
  "Capture the current frame and return a frame-bound dispatch fn that
  is safe to call from async callbacks where dynamic-var binding is no
  longer in scope. Per Spec 002 §bound-fn / bound-dispatcher."
  []
  (dispatcher))

(defn bound-subscriber
  "As bound-dispatcher, for subscribe."
  []
  (subscriber))

;; ---- view ergonomics (CLJS only) ------------------------------------------
;; reg-view, frame-provider, h, with-frame live in re-frame-2.views.cljs;
;; users `:require [re-frame-2.views :as v]` for those.

;; ---- routing helpers ------------------------------------------------------

(def match-url routing/match-url)
(def route-url routing/route-url)

;; ---- machine helpers ------------------------------------------------------

(def create-machine-handler machines/create-machine-handler)
(def machine-transition     machines/machine-transition)

;; ---- introspection (per Spec 002 §The public registrar query API) -------

(def handlers     registrar/handlers)
(def handler-meta registrar/handler-meta)
(def frames       (fn [] (frame/frame-ids)))
(def frame-meta   frame/frame-meta)
(def get-frame-db frame/frame-app-db-value)

;; ---- interceptors ---------------------------------------------------------

(def ->interceptor   interceptor/->interceptor)
(def get-coeffect    interceptor/get-coeffect)
(def assoc-coeffect  interceptor/assoc-coeffect)
(def get-effect      interceptor/get-effect)
(def assoc-effect    interceptor/assoc-effect)
(def inject-cofx     cofx/inject-cofx)
(def path            std-interceptors/path)
(def unwrap          std-interceptors/unwrap)

;; ---- trace ----------------------------------------------------------------

(def register-trace-cb! trace/register-trace-cb!)
(def remove-trace-cb!   trace/remove-trace-cb!)
(def emit-trace!        trace/emit!)

;; ---- substrate adapter ----------------------------------------------------

(def install-adapter!  adapter/install-adapter!)
(def dispose-adapter!  adapter/dispose-adapter!)

;; ---- boot -----------------------------------------------------------------

(defn init!
  "Idempotent boot. Installs an adapter (defaulting to plain-atom for JVM /
  headless / SSR — CLJS browser apps should pass the Reagent adapter
  explicitly). Ensures :rf/default frame is present."
  ([] (init! plain-atom/adapter))
  ([adapter]
   (when-not (adapter/current-adapter)
     (adapter/install-adapter! adapter))
   (frame/ensure-default-frame!)
   nil))

;; ---- self-registration of framework subs ----------------------------------

(reg-sub :rf/route routing/route-sub-fn)
(reg-sub :rf.route/id     :<- [:rf/route] (fn [route _] (:id route)))
(reg-sub :rf.route/params :<- [:rf/route] (fn [route _] (:params route)))
(reg-sub :rf.route/query  :<- [:rf/route] (fn [route _] (:query route)))
(reg-sub :rf.route/transition :<- [:rf/route] (fn [route _] (:transition route)))
(reg-sub :rf.route/error  :<- [:rf/route] (fn [route _] (:error route)))
