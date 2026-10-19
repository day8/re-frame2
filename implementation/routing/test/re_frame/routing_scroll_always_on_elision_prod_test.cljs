(ns re-frame.routing-scroll-always-on-elision-prod-test
  "An unsupported `:rf.nav/scroll` strategy still surfaces in a release build
  on a host without the optional schemas artefact.

  `:rf.nav/scroll` is a public fx id, so an app can emit it with any
  `:strategy`. `reg-route` and the navigate gate check a `:scroll` where it is
  written, and the schemas artefact's `:fx-args` gate refuses a bad emitted
  one, but that artefact is optional and its gate is dev-only. Without it the
  value reaches the handler, which throws, and the throw surfaces as the
  ordinary `:rf.error/fx-handler-exception` on the always-on error axis — the
  one axis that survives `:advanced` + `goog.DEBUG=false`, which only a build
  like this one can show.

  The `:schemas/validate-fx!` hook is unpublished for the dispatch, because the
  prod-elision bundle is shared across suites and a sibling's require of
  `re-frame.schemas` would otherwise publish it process-wide.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, `:ns-regexp \"-elision-prod-test$\"`, runner
  `re-frame.prod-elision-runner`). The default `:browser-test` / `:node-test`
  runners use regexes that do NOT match this suffix, so these tests run only
  under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.routing]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                ;; The always-on listener registry is a `defonce` atom.
                (rf.error-emit/clear-error-listeners!)
                (rf.routing.scroll/reset-cache!))}))

(deftest unsupported-strategy-is-one-fx-handler-exception-in-prod-without-schemas
  (testing "under `:advanced` + `goog.DEBUG=false`, with no schemas gate, a
            directly emitted `[:rf.nav/scroll {:strategy :bogus}]` fans exactly
            one always-on `:rf.error/fx-handler-exception` record naming the fx"
    (let [records      (atom [])
          validate-fx! (rf.late-bind/get-fn :schemas/validate-fx!)]
      (rf.error-emit/register-error-listener! :prod.scroll/recorder
                                              (fn [record] (swap! records conj record)))
      (rf/reg-event :prod.scroll/bogus
                    (fn [_ _] {:fx [[:rf.nav/scroll {:strategy :bogus}]]}))
      (try
        (rf.late-bind/set-fn! :schemas/validate-fx! nil)
        (rf/dispatch-sync [:prod.scroll/bogus])
        (finally (rf.late-bind/set-fn! :schemas/validate-fx! validate-fx!)))
      (is (= [[:rf.error/fx-handler-exception :rf.nav/scroll]]
             (mapv (juxt :rf.error/id :failing-id) @records))))))
