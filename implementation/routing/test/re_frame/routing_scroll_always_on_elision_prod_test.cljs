(ns re-frame.routing-scroll-always-on-elision-prod-test
  "The ACCEPTANCE probe for the unsupported-scroll-strategy
  rejection, under the one build configuration that can actually falsify it.

  ## The gap this file guards

  `:rf.nav/scroll`'s args schema admits no map form, and
  `scroll-fx-handler`'s default branch is loud rather than returning
  nil. Spec 012 and the source both call that default branch \"the
  ALWAYS-ON leg\" — the thing standing between the author and silence when
  the OPTIONAL schemas artefact is absent and the Spec 010 §step-5 `:fx-args`
  gate therefore soft-passes.

  Emitting through `trace/emit-error!` alone would not be always-on: its whole
  body is wrapped in `interop/debug-enabled?` and constant-folds away under
  `:advanced` + `goog.DEBUG=false`. Compose the two optional-ness conditions
  and the rejection would disappear exactly where it is needed:

    schemas present  + dev   → schema gate fires (rejection never needed)
    schemas present  + prod  → schema gate fires (rejection never needed)
    schemas ABSENT   + dev   → handler fires, dev trace delivers   ← the only
                                                                     leg the
                                                                     dev suite
                                                                     covers
    schemas ABSENT   + prod  → handler fires, trace DCE'd, NOTHING ← the gap

  The bottom row is a production app that loaded routing but not schemas: it
  would perform no scroll, emit no production-surviving record, and return
  nil — the accepted-and-ignored outcome the closed vocabulary rules out,
  reproduced for the consumers least likely to notice it.

  The handler therefore fans the category through the two-channel seam
  (`rf.error-emit/emit-error-both!`). Axis 1 — the `dispatch-on-error!`
  listener registry — is NOT gated on `interop/debug-enabled?`, so the record
  survives here.

  ## Why the dev suite cannot prove this

  The dev-mode legs in `re-frame.routing-scroll-record-bounded-cljs-test` assert the
  always-on listener fires, but they run with the trace surface LIVE. They
  cannot distinguish a record that rides the always-on axis from one that
  rides the dev trace — under `goog.DEBUG=true` both deliver. Only a
  `goog.DEBUG=false` build separates them, which is what this file is.
  (Exactly the argument `re-frame.teardown-always-on-elision-prod-test`
  makes for the EP-0008 rows.)

  ## Why calling the handler directly IS the schemas-absent path

  `re-frame.schemas` publishes `:schemas/validate-fx!` through `late-bind` at
  ns-load time, and the prod-elision bundle is shared across suites — so a
  sibling suite's require would install the gate process-wide and no
  `:fx`-driven dispatch here could honestly represent a schemas-less host.
  Invoking `scroll-fx-handler` directly is the established idiom for that
  configuration in this repo (see
  `scroll-handler-emits-the-unsupported-strategy-error-directly` in the dev
  suite): it
  reaches the handler with the fx-args gate bypassed, which is precisely what
  a soft-pass does.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked up
  ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, `:ns-regexp \"-elision-prod-test$\"`, runner
  `re-frame.prod-elision-runner`). The default `:browser-test` / `:node-test`
  runners use regexes that do NOT match this suffix, so these tests run only
  under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                ;; The always-on listener registry is a `defonce` atom.
                (rf.error-emit/clear-error-listeners!)
                (rf.routing.scroll/reset-cache!))}))

(deftest unsupported-strategy-record-survives-prod-without-schemas
  (testing "under `:advanced` + `goog.DEBUG=false`, with the `:fx-args`
            schema gate soft-passed (schemas-less host), an unsupported
            `:rf.nav/scroll` strategy fans EXACTLY ONE structural
            `:rf.error/unsupported-scroll-strategy` record out through the
            always-on `register-error-listener!` substrate. A branch emitting
            only through the DCE'd `trace/emit-error!` would leave zero records"
    (let [records (atom [])]
      (rf.error-emit/register-error-listener! :prod.scroll/recorder
                                              (fn [record] (swap! records conj record)))
      (rf.routing.scroll/scroll-fx-handler {:frame :prod.scroll/frame}
                                           {:strategy {:to :element :selector "#article"}})
      (is (= [{:supported     [:top :restore :preserve]
               :recovery      :no-scroll
               :strategy-type :map
               :reason        rf.routing.scroll/unsupported-strategy-reason
               :frame         :prod.scroll/frame}]
             (->> @records
                  (filter #(= :rf.error/unsupported-scroll-strategy (:error %)))
                  (map #(select-keys % [:supported :recovery :strategy-type :reason :frame]))))))))
