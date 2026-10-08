(ns re-frame.on-error-elision-prod-test
  "Each always-on `:rf.error/*` category below reaches the corpus-wide
  `register-error-listener!` registry from its real emit site in a production
  bundle — the release-build proof Spec 009's always-on catalogue promises for
  a CLJS-reachable site (TESTING.md §Always-on error categories). The dev
  trace is compiled away here, so a site that emitted only to it would fail.

  Loaded only by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, ns-regexp `-elision-prod-test$`, runner
  `re-frame.prod-elision-runner`). The record shapes are pinned in both
  postures by `re-frame.on-error-cljs-test`, `re-frame.fx-test` and
  `re-frame.cofx-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.error-emit/clear-error-listeners!}))

(defn- recording-listener!
  "Register a listener that conjs every always-on record onto a fresh atom,
  and return the atom."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :prod/recorder #(swap! seen conj %))
    seen))

(defn- first-of [category seen]
  (some #(when (= category (:error %)) %) @seen))

(deftest error-emit-listener-fires-under-prod
  (testing "a handler throw reaches the always-on listener as one tight record,
            with an integer :elapsed-ms and a :source-coord that has lost only
            its dev-only :column"
    (let [seen (recording-listener!)]
      (rf/reg-event :prod/err-throw (fn [_ _] (throw (ex-info "kaboom" {}))))
      (rf/dispatch-sync [:prod/err-throw])
      (is (= 1 (count @seen)))
      (let [{sc :source-coord :as r} (first @seen)]
        (is (= {:error :rf.error/handler-exception :event [:prod/err-throw]
                :event-id :prod/err-throw :frame :rf/default}
               (select-keys r [:error :event :event-id :frame])))
        (is (number? (:time r)))
        (is (integer? (:elapsed-ms r)))
        (is (and (symbol? (:ns sc)) (integer? (:line sc)) (string? (:file sc))))
        (is (not (contains? sc :column)))))))

(deftest frame-destroyed-dispatch-listener-survives-prod
  (testing "dispatch, dispatch-sync and subscribe into an unknown frame each
            recover and emit one :rf.error/frame-destroyed record. A dispatched
            event is payload and fails closed under the unresolvable frame; a
            query vector is identity and egresses raw, stamped :op :subscribe"
    (doseq [[label op expected]
            [["dispatch"
              #(rf/dispatch [:whatever] {:frame :gone/frame})
              {:error :rf.error/frame-destroyed :frame :gone/frame :event :rf/redacted :event-id :whatever}]
             ["dispatch-sync"
              #(rf/dispatch-sync [:whatever] {:frame :gone/frame})
              {:error :rf.error/frame-destroyed :frame :gone/frame}]
             ["subscribe"
              #(rf/subscribe-once [:any-sub] {:frame :gone/frame})
              {:error :rf.error/frame-destroyed :frame :gone/frame :event [:any-sub] :op :subscribe}]]]
      (testing label
        (let [seen (recording-listener!)]
          (is (nil? (op)))
          (is (= [expected] (mapv #(select-keys % (keys expected)) @seen))))))))

(deftest no-such-handler-listener-survives-prod
  (let [seen (recording-listener!)]
    (rf/dispatch-sync [:no/handler-here])
    (is (= {:event-id :no/handler-here :frame :rf/default}
           (select-keys (first-of :rf.error/no-such-handler seen) [:event-id :frame])))))

(deftest no-such-sub-listener-survives-prod
  (let [seen (recording-listener!)]
    (is (nil? (rf/subscribe-once [:no/such-sub-here] {:frame :rf/default})))
    (is (= {:event-id :no/such-sub-here :frame :rf/default}
           (select-keys (first-of :rf.error/no-such-sub seen) [:event-id :frame])))))

(deftest compute-sub-exception-listener-survives-prod
  (testing "a compute-sub body throw recovers to nil and still emits, so an SSR
            harness driving subs through compute-sub cannot answer a silent 200"
    (let [seen (recording-listener!)]
      (rf/reg-sub :kjf3m/throwing (fn [_db _q] (throw (ex-info "compute-boom" {}))))
      (is (nil? (rf/compute-sub [:kjf3m/throwing] {})))
      (is (some? (:exception (first-of :rf.error/sub-exception seen)))))))

(deftest fx-handler-exception-listener-survives-prod
  (let [seen (recording-listener!)]
    (rf/reg-fx :goum9x/prod-throwing-fx (fn [_ _] (throw (ex-info "fx-boom" {}))))
    (rf/reg-event :goum9x/prod-run-throwing-fx (fn [_ _] {:fx [[:goum9x/prod-throwing-fx]]}))
    (rf/dispatch-sync [:goum9x/prod-run-throwing-fx])
    (let [r (first-of :rf.error/fx-handler-exception seen)]
      (is (= {:event-id :goum9x/prod-run-throwing-fx :frame :rf/default}
             (select-keys r [:event-id :frame])))
      (is (some? (:exception r))))))

(deftest no-such-fx-listener-survives-prod
  (testing "the record names the unknown fx-id, which otherwise rides only the
            compiled-away dev trace"
    (let [seen (recording-listener!)]
      (rf/reg-event :goum9x/prod-unknown-fx (fn [_ _] {:fx [[:goum9x/prod-never {}]]}))
      (rf/dispatch-sync [:goum9x/prod-unknown-fx])
      (is (= {:event-id :goum9x/prod-unknown-fx :frame :rf/default :failing-id :goum9x/prod-never}
             (select-keys (first-of :rf.error/no-such-fx seen) [:event-id :frame :failing-id]))))))

(deftest override-fallthrough-listener-survives-prod
  (let [seen (recording-listener!)]
    (rf/reg-fx :goum9x/prod-real-fx (fn [_ _] nil))
    (rf/reg-event :goum9x/prod-bad-override (fn [_ _] {:fx [[:goum9x/prod-real-fx]]}))
    (rf/dispatch-sync [:goum9x/prod-bad-override]
                      {:fx-overrides {:goum9x/prod-real-fx :goum9x/prod-missing}})
    (is (= :rf/default (:frame (first-of :rf.error/override-fallthrough seen))))))

(deftest reserved-fx-override-listener-survives-prod
  (testing "with the dev per-call reject compiled away, the router's production
            strip of a reject-tier override is the emit site"
    (let [seen (recording-listener!)]
      (rf/reg-event :uh5ic5/install-flow
                    (fn [_ _] {:fx [[:rf.fx/reg-flow [:uh5ic5/prod-flow
                                                      {:inputs [[:uh5ic5 :seed]] :output-path [:uh5ic5 :out]}
                                                      (fn [_] 1)]]]}))
      (rf/dispatch-sync [:uh5ic5/install-flow]
                        {:fx-overrides {:rf.fx/reg-flow (fn [_ _] :should-not-fire)}})
      (is (= {:event-id :uh5ic5/install-flow :frame :rf/default}
             (select-keys (first-of :rf.error/reserved-fx-override seen) [:event-id :frame]))))))

(deftest unregistered-cofx-listener-survives-prod
  (let [seen (recording-listener!)]
    (rf/reg-event :goum9x/prod-unknown-cofx
                  {:rf.cofx/requires [:goum9x/prod-no-cofx]}
                  (fn [_ _] {}))
    (try (rf/dispatch-sync [:goum9x/prod-unknown-cofx])
         (catch :default _ nil))
    (is (= :rf/default (:frame (first-of :rf.error/unregistered-cofx seen))))))
