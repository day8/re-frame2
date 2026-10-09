(ns re-frame.flows-trace-emit-elision-prod-test
  "Spec 009 §Production builds, for the flows trace surface: under
  `:advanced` + `goog.DEBUG=false` the flow lifecycle still registers,
  recomputes, skips, fails and clears, and a trace listener observes nothing.
  Every `:rf.flow/*` emit site, and the wire-value walker that builds its
  tags, sits behind `interop/debug-enabled?`. `scripts/check-elision.cjs`
  greps the bundle for surviving keywords; this pins the behaviour.

  Only the `:browser-test-prod-elision` build picks up
  `-elision-prod-test.cljs` files; under `goog.DEBUG=true` these would fail.
  The always-on `:rf.error/flow-eval-exception` record, which does survive
  production, is pinned by `re-frame.flow-eval-exception-elision-prod-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; Required directly, so every gated emit site is in the
            ;; compiler's reachability graph.
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry]
            ;; A wired validator, so the validation surface is shown to
            ;; elide even when the seam is present.
            [re-frame.schemas :as rf.schemas]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- capture-traces
  "Every trace event delivered while `body-fn` runs."
  [body-fn]
  (let [captured    (atom [])
        listener-id (keyword (str "elision-prod-" (gensym)))]
    (rf.trace.tooling/register-listener! listener-id (fn [event] (swap! captured conj event)))
    (try
      (body-fn)
      @captured
      (finally
        (rf.trace.tooling/unregister-listener! listener-id)))))

(deftest flow-lifecycle-emits-no-trace-under-prod
  ;; Registration, a recompute, a value-equal skip, a clear and a `:derive`
  ;; throw each still happen; `seen` records that they did.
  (let [seen   (atom [])
        area   #(get-in (rf.flows/flows-snapshot) [:rf/default :prod-elision/area])
        traces (capture-traces
                 (fn []
                   (rf/reg-event :prod-elision/set-w
                     (fn [{:keys [db]} [_ w]] {:db (assoc db :w w :h 4)}))
                   (rf/reg-flow :prod-elision/area
                     {:inputs [[:w] [:h]] :output-path [:rect :area]}
                     (fn [w h] (* (or w 0) (or h 0))))
                   (swap! seen conj (some? (area)))
                   (rf/dispatch-sync [:prod-elision/set-w 3])
                   (swap! seen conj (get-in (rf/app-db-value :rf/default) [:rect :area]))
                   (rf/dispatch-sync [:prod-elision/set-w 3])
                   (rf/clear :flow :prod-elision/area)
                   (swap! seen conj (area))
                   (rf/reg-flow :prod-elision/throwing
                     {:inputs [[:w]] :output-path [:prod-elision/result]}
                     (fn [_] (throw (ex-info "prod-elision throw" {}))))
                   (rf/dispatch-sync [:prod-elision/set-w 5])))]
    (is (= [[] [true 12 nil]] [traces @seen]))))

(deftest flow-output-schema-validation-elides-under-prod
  ;; Witnessed at the validator: the failure trace is itself debug-gated, so
  ;; a no-trace read alone could not fail here.
  (let [consulted (atom 0)]
    (rf.schemas/set-schema-fns! {:validate (fn [_ _] (swap! consulted inc) false)})
    (rf/reg-event :prod-elision/seed-validate
      (fn [{:keys [db]} _] {:db (assoc db :w 3 :h 4)}))
    (rf/reg-flow :prod-elision/validated
      {:inputs [[:w] [:h]] :output-path [:prod-elision/area] :schema (fn [_] false)}
      (fn [w h] (* (or w 0) (or h 0))))
    (let [traces (capture-traces #(rf/dispatch-sync [:prod-elision/seed-validate]))]
      (is (= [[] 0 12]
             [traces @consulted (get-in (rf/app-db-value :rf/default) [:prod-elision/area])])))))
