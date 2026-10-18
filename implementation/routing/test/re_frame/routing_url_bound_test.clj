(ns re-frame.routing-url-bound-test
  "Multi-frame URL-ownership tests for re-frame.routing: which frame
  `rf.routing/url-owner-frame-id` reports, and the registration preflight that
  refuses a second live `:url-bound? true` frame with
  `:rf.error/duplicate-url-binding`. The production push gate built on the
  owner is driven against a browser stub in `routing_history_cljs_test`.

  Ownership and the refusal are production-real and carry no posture guard:
  they run in the ordinary `clojure -M:test` suite AND in
  `clojure -M:test:prod-gate` (the `-Dre-frame.debug=false` lane)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest non-default-frame-without-url-bound-does-not-collide
  (testing "a frame WITHOUT :url-bound? true — the story / devcard /
            test-fixture default — claims nothing, so it registers beside the
            owner"
    (rf/make-frame {:id :story/variant-A})
    (rf/make-frame {:id :test/fixture :url-bound? false})
    (is (= :rf/default (rf.routing/url-owner-frame-id)))))

(deftest single-non-default-frame-owns-url-when-default-opts-out
  (testing "with :rf/default opted OUT (:url-bound? false), the lone
            :url-bound? true non-default frame owns the URL — the step-deck
            ownership contract (Spec 012 §Multi-frame routing)"
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :step-deck :url-bound? true})
    (is (= :step-deck (rf.routing/url-owner-frame-id)))))

(deftest a-second-url-bound-frame-is-refused-at-preflight
  (testing "while :rf/default owns the URL, a second :url-bound? true frame
            throws :rf.error/duplicate-url-binding and is never stored"
    (is (= {:rf.error/id     :rf.error/duplicate-url-binding
            :existing-frame  :rf/default
            :offending-frame :aaa-early}
           (select-keys (thrown-data #(rf/make-frame {:id :aaa-early :url-bound? true}))
                        [:rf.error/id :existing-frame :offending-frame])))
    (is (nil? (rf.frame/frame-meta :aaa-early)))
    (is (= :rf/default (rf.routing/url-owner-frame-id))))
  (testing "a live frame re-registering with :url-bound? true is refused the
            same way and keeps the config it had"
    (rf/make-frame {:id :other})
    (is (= :rf.error/duplicate-url-binding
           (:rf.error/id (thrown-data #(rf/make-frame {:id :other :url-bound? true})))))
    (is (not (:url-bound? (rf.frame/frame-meta :other)))))
  (testing "re-registering the owner is not a second binding"
    (rf/make-frame {:id :rf/default :url-bound? true :doc "Re-registered owner."})
    (is (= :rf/default (rf.routing/url-owner-frame-id)))))

(deftest destroying-the-owner-leaves-no-owner
  (testing "with the owner destroyed nothing owns the URL — there is no
            successor — until a frame declares :url-bound? true"
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :owner/a :url-bound? true})
    (thrown-data #(rf/make-frame {:id :owner/b :url-bound? true}))
    (rf/destroy-frame! :owner/a)
    (is (nil? (rf.routing/url-owner-frame-id)))
    (rf/make-frame {:id :owner/c :url-bound? true})
    (is (= :owner/c (rf.routing/url-owner-frame-id)))))
