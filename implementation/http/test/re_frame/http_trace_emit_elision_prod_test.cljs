(ns re-frame.http-trace-emit-elision-prod-test
  "Runtime production elision of the managed-HTTP trace surface (Spec 009
  §Production builds): under `:advanced` + `goog.DEBUG=false` a registered
  listener observes no `:rf.http/*` event, while the host work behind the emit
  still runs. `scripts/check-elision.cjs` greps the bundle for surviving keyword
  literals; this pins the behaviour.

  Only the `:browser-test-prod-elision` build runs `-elision-prod-test.cljs`
  files; under `goog.DEBUG=true` this test would fail, since the trace surface
  delivers in dev."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; Require every gated emit-site host ns so the reachability
            ;; graph includes their compiled bodies — DCE only proves the
            ;; gated branches dead from a reachable module.
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport]
            [re-frame.http.decode]
            ;; Listener mutation belongs to the tooling namespace.
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     ;; the in-flight indexes are defonce'd; a handle leaked by one test must
     ;; not reach the next
     :init-fn (fn [] (rf.http.managed/clear-all-in-flight!))}))

(defn- listener-fixture
  "Install a listener recording EVERY trace event, run `body-fn`, and return
  what it saw, so any leak surfaces."
  [body-fn]
  (let [seen   (atom [])
        cb-key (keyword (str "elision-prod-" (gensym)))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

(deftest abort-on-actor-destroy-emits-no-trace-under-prod
  (testing "`abort-on-actor-destroy` fires every in-flight handle's `:abort-fn`
            but emits no `:rf.http/aborted-on-actor-destroy` trace"
    (let [actor-id    :prod-elision/actor
          aborts-seen (atom 0)
          handle      {:abort-fn    (fn [_reason] (swap! aborts-seen inc))
                       :url         "https://prod-elision.example/test"
                       :sensitive?  false}
          _           (rf.http.registry/record-in-flight!
                        :prod-elision/req-id actor-id handle)
          seen        (listener-fixture
                        (fn []
                          (rf.http.managed/abort-on-actor-destroy actor-id)))]
      (is (= 1 @aborts-seen)
          ":abort-fn ran exactly once — host side-effect not elided")
      (is (empty? seen)
          "no trace events delivered under :advanced + goog.DEBUG=false"))))
