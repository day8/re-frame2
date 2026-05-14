(ns re-frame.ssr.ring.lifecycle
  "Per-request frame lifecycle helpers for the Ring host adapter.

  Pure helpers used by the request pipeline:

    - `destroy-frame-quietly!` — best-effort frame teardown
    - `resolve-root-view`      — hiccup-vec OR 0-arity fn → hiccup
    - `resolve-head`           — active route's `:head` → map carrying the
                                 rendered fragment plus `:html-attrs` /
                                 `:body-attrs` bags for the host shell
    - `on-create-with-request` — append request to caller's :on-create vec

  Per Spec 011 §Request storage substrate + §Head/meta contract."
  (:require [re-frame.core :as rf]
            [re-frame.trace :as trace]))

(set! *warn-on-reflection* true)

(defn destroy-frame-quietly!
  "Best-effort frame teardown. Exceptions during destroy must not mask
  a real handler error; swallow + emit a `:warning` trace is preferred
  over propagation.

  Per audit rf2-asmj1 R6 / cluster rf2-sljs1: prior to this change the
  catch silently returned nil, so a destroy-time throw vanished entirely
  whenever the trace listener fired before the destroy. Surfacing the
  throwable on the trace bus keeps the error visible to dev tooling
  without escalating to a user-visible 500 (the handler-side error has
  already been materialised by the time this fn runs)."
  [frame-id]
  (try
    (rf/destroy-frame frame-id)
    (catch Throwable t
      (trace/emit! :warning :rf.ssr/destroy-frame-failed
                   {:frame    frame-id
                    :reason   (or (.getMessage t) (.getName (class t)))
                    :ex-class (.getName (class t))
                    :recovery :warned-and-skipped})
      nil)))

(defn resolve-root-view
  "Resolve the caller's `:root-view` opt to a hiccup vector. Accepts
  either a hiccup vector directly OR a 0-arity fn that returns hiccup.
  Per rf2-6t36h this MUST run exactly once per request — the fn-form
  branch is not guaranteed to be idempotent (unsorted-map iteration,
  gensym'd keys, time-of-day props can all vary between calls) and any
  variance between two invocations produces a hash mismatch on the wire
  vs the payload, firing a spurious `:rf.ssr/hydration-mismatch` on a
  perfectly successful hydration. Resolve once, thread the result."
  [root-view]
  (cond
    (vector? root-view) root-view
    (fn?     root-view) (root-view)
    :else
    (throw (ex-info ":rf.error/invalid-root-view"
                    {:reason   "root-view must be a hiccup vector or a 0-arity fn"
                     :received root-view}))))

(defn resolve-head
  "Resolve the active route's `:head` against `frame-id` (or the default
  head when the route doesn't declare one). Returns a map:

    {:head-html  \"<title>…</title><meta …>…\"   ;; inner-head fragment
     :html-attrs {…} or nil                       ;; stamped on <html>
     :body-attrs {…} or nil}                      ;; stamped on <body>

  The two attribute bags ride alongside the rendered fragment because
  `head-model->html` deliberately drops them (Spec 011 §Default flow
  step 4: `:html-attrs` populate `<html>`; `:body-attrs` populate
  `<body>` — the host shell stamps them, not the head emitter).

  Exceptions during resolution degrade gracefully — empty fragment,
  no attrs — so a buggy head fn can't take down the request. The trace
  surface still carries the throw for monitoring.

  Per Spec 011 §Head/meta contract (rf2-4dra9, rf2-h2ujj)."
  [frame-id]
  (try
    (let [model (rf/active-head frame-id)]
      {:head-html  (rf/head-model->html model)
       :html-attrs (:html-attrs model)
       :body-attrs (:body-attrs model)})
    (catch Throwable _
      {:head-html "" :html-attrs nil :body-attrs nil})))

(defn on-create-with-request
  "Conj the Ring request map onto the caller's :on-create event vector
  so handlers can read it as an event arg. The `:rf.server/request`
  cofx (rf2-e825b) is the canonical read path; this is the fallback
  for handlers that don't inject the cofx and want the request as a
  positional arg, matching the worked example in
  examples/reagent/ssr/core.cljc."
  [on-create request]
  (cond
    (nil? on-create)    nil
    (vector? on-create) (conj on-create request)
    :else
    (throw (ex-info ":rf.error/invalid-on-create"
                    {:reason   ":on-create must be a vector (event)"
                     :received on-create}))))
