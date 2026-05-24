(ns day8.re-frame2-xray.testbeds.testdeck-cofx-cljs-test
  "Regression guard for rf2-gg4dz — the testdeck `:testdeck/now` cofx
  must inject its value under `[:coeffects :testdeck/now]` so the
  `:counter/inc` handler's destructure of `:testdeck/now` binds the
  injected wall-clock time (not nil). If the cofx body ever regresses to
  the wrong shape (assoc at the top of ctx rather than into `:coeffects`)
  the handler binds nil and `[:counter :last-clicked]` stays nil
  silently — no error fires.

  This test installs the cofx ad-hoc using the SAME body shape the
  testbed source carries, then exercises it both ways:
    1. Direct call against the registered handler — asserts the value
       lands at `[:coeffects :testdeck/now]` (the canonical slot).
    2. End-to-end `dispatch-sync` of an event using `inject-cofx` —
       asserts the handler's `:testdeck/now` destructure binds the
       injected value.

  We do NOT `:require` `testdeck.counter` at ns-load time — the
  testbed's `:counter/inc` registration is a per-process side effect
  that collides with the identically-named event in the
  `counter-with-stories` testbed, and the per-test registrar fixture
  cannot roll back a load-time registration. The body shape pinned
  here mirrors `tools/xray/testbeds/testdeck/counter.cljs`; drift
  between the two is the regression rf2-gg4dz documents."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(deftest testdeck-now-cofx-direct-shape-witness
  (testing "calling the registered cofx handler directly puts the value
            under [:coeffects :testdeck/now], NOT at the top of ctx"
    (rf/reg-cofx :testdeck/now
      (fn [ctx]
        (rf/assoc-coeffect ctx :testdeck/now (.getTime (js/Date.)))))
    (let [meta    (rf/handler-meta :cofx :testdeck/now)
          handler (:handler-fn meta)
          result  (handler {:coeffects {}})]
      (is (number? (get-in result [:coeffects :testdeck/now]))
          "value lands at [:coeffects :testdeck/now] (the canonical slot)")
      (is (nil? (get result :testdeck/now))
          "the misshapen-cofx witness — value must NOT land at top of ctx
           (the bug-shape pre-rf2-gg4dz)"))))

(deftest testdeck-now-cofx-reaches-handler-via-dispatch
  (testing "an event handler using `(rf/inject-cofx :testdeck/now)`
            observes the injected value under its `:testdeck/now`
            destructure. End-to-end pin of the cofx-injection contract
            the testdeck counter's `:counter/inc` handler relies on."
    (rf/reg-cofx :testdeck/now
      (fn [ctx]
        (rf/assoc-coeffect ctx :testdeck/now 1234567890)))
    (rf/reg-event-fx ::tick
      [(rf/inject-cofx :testdeck/now)]
      (fn [{:keys [db testdeck/now]} _]
        {:db (assoc db ::last-tick now)}))
    (rf/dispatch-sync [::tick])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 1234567890 (::last-tick db))
          "handler's `:testdeck/now` destructure binds the cofx-injected
           value; nil here means the cofx body regressed (rf2-gg4dz)"))))
