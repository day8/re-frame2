(ns re-frame.write-after-destroy-always-on-cljs-test
  "`replace-container!` against a nil container — a scheduled drain racing frame
  destruction — drops the write and reports `:rf.error/write-after-destroy` on
  the always-on error-emit axis, which survives the production gate, beside a
  dev-only error trace (Spec 009 §Error event catalogue)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.error-emit/clear-error-listeners!))}))

(deftest nil-container-write-fans-out-on-always-on-axis
  ;; The fixture installs plain-atom, whose own `replace-container!` throws on a
  ;; nil container, so a write forwarded past the guard fails this test.
  (let [seen   (atom [])
        traces (atom [])]
    (rf.error-emit/register-error-listener! :test/recorder
                                            (fn [record] (swap! seen conj record)))
    (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
    (try
      (rf.substrate.adapter/replace-container! nil {:dropped :write})
      (finally
        (rf/unregister-listener! :trace ::rec)))
    (testing "exactly one always-on record, carrying no event, frame or exception"
      (let [reports (filter #(= :rf.error/write-after-destroy (:error %)) @seen)
            r       (first reports)]
        (is (= 1 (count reports)))
        (is (nil? (:event r)))
        (is (nil? (:frame r)))
        (is (nil? (:exception r)))
        (is (number? (:time r)) ":time is a wall-clock millis number")))
    (when rf.interop/debug-enabled?
      (testing "the dev error trace carries :recovery :ignored and a structured :reason"
        (let [errs (filter #(= :rf.error/write-after-destroy (:operation %)) @traces)]
          (is (= 1 (count errs)))
          (is (= :ignored (:recovery (first errs))))
          (is (string? (get-in (first errs) [:tags :reason]))))))))
