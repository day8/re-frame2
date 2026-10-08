(ns re-frame.on-destroy-handler-exception-always-on-cljs-test
  "`:rf.error/on-destroy-handler-exception` on the always-on error axis. A
  throwing `:on-destroy` never aborts teardown (Spec 002 §`:on-destroy`
  handler throw semantics), and the dedicated category, which tells a
  teardown failure apart from the router's generic
  `:rf.error/handler-exception`, reaches `register-error-listener!` in every
  posture. Runs on the JVM (both postures) and on `:node-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn rf.error-emit/clear-error-listeners!}))

(defn- recording-listener!
  "Register a listener that conjs every always-on record onto a fresh atom,
  and return the atom."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder #(swap! seen conj %))
    seen))

(defn- on-destroy-records [seen]
  (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen))

(deftest throwing-on-destroy-fans-out-on-always-on-axis
  (testing "a throwing :on-destroy yields exactly one dedicated record, and
            teardown still completes"
    (let [seen (recording-listener!)]
      (rf/reg-event :ondestroy/blow-up
                    (fn [_ _] (throw (ex-info "intentional :on-destroy throw" {}))))
      (rf/make-frame {:id :ondestroy/worker :on-destroy [:ondestroy/blow-up]})
      (rf/destroy-frame! :ondestroy/worker)
      (is (nil? (rf.frame/frame :ondestroy/worker)))
      (let [reports (on-destroy-records seen)]
        (is (= [{:frame :ondestroy/worker :event [:ondestroy/blow-up] :event-id :ondestroy/blow-up}]
               (map #(select-keys % [:frame :event :event-id]) reports)))
        (is (some? (:exception (first reports))))))))

(deftest clean-on-destroy-emits-no-record
  (let [seen (recording-listener!)]
    (rf/reg-event :ondestroy/clean (fn [{:keys [db]} _] {:db db}))
    (rf/make-frame {:id :ondestroy/ok :on-destroy [:ondestroy/clean]})
    (rf/destroy-frame! :ondestroy/ok)
    (is (empty? (on-destroy-records seen)))))

(deftest teardown-cascade-infra-fault-fans-out-on-always-on-axis
  (testing "a fault in the teardown dispatch infrastructure itself, which
            produces no router handler-exception, still fans the dedicated
            record out, and teardown completes"
    (let [seen     (recording-listener!)
          original (rf.late-bind/get-fn :router/run-frame-destroy-event!)]
      (rf/make-frame {:id :ondestroy/infra-fault :on-destroy [:ondestroy/never-reached]})
      (rf.late-bind/set-fn! :router/run-frame-destroy-event!
                            (fn [& _] (throw (ex-info "dispatch infra fault" {}))))
      (try
        (rf/destroy-frame! :ondestroy/infra-fault)
        (finally
          (rf.late-bind/set-fn! :router/run-frame-destroy-event! original)))
      (let [reports (on-destroy-records seen)]
        (is (= [:ondestroy/infra-fault] (map :frame reports)))
        (is (some? (:exception (first reports)))))
      (is (nil? (rf.frame/frame :ondestroy/infra-fault))))))

(deftest nested-destroy-does-not-clobber-outer-on-destroy-capture
  (testing "an outer :on-destroy that destroys another frame before throwing
            yields one dedicated record per frame: each destroy's transient
            capture listener has its own key, so the nested one cannot replace
            the outer's"
    (let [seen (recording-listener!)]
      (rf/reg-event :ondestroy/inner-throw
                    (fn [_ _] (throw (ex-info "inner B :on-destroy threw" {}))))
      (rf/make-frame {:id :ondestroy/inner-B :on-destroy [:ondestroy/inner-throw]})
      (rf/reg-event :ondestroy/outer-throw
                    (fn [_ _]
                      (rf/destroy-frame! :ondestroy/inner-B)
                      (throw (ex-info "outer A :on-destroy threw" {}))))
      (rf/make-frame {:id :ondestroy/outer-A :on-destroy [:ondestroy/outer-throw]})
      (rf/destroy-frame! :ondestroy/outer-A)
      (is (= {:ondestroy/outer-A 1 :ondestroy/inner-B 1}
             (frequencies (map :frame (on-destroy-records seen))))))))
