(ns day8.re-frame2-xray.filters.error-override-cljs-test
  "Pure-data contract for the error-override filter bypass (spec/018 §7
  Error overrides): an errored event a filter would hide is surfaced
  anyway."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.filters.error-override :as eo]))

;; A bundle is errored iff its `:other` bucket carries an error trace —
;; `:op-type :error`, or any `:rf.error/*` operation.

(def ^:private errored-out
  {:event [:auth/login {}] :dispatch-id 1 :other [{:op-type :error}]})

(def ^:private clean-kept
  {:event [:cart/add {}] :dispatch-id 2 :other []})

(def ^:private clean-dropped
  {:event [:mouse/move {}] :dispatch-id 3 :other []})

(def ^:private errored-in-non-match
  {:event [:order/retry {}] :dispatch-id 4 :other [{:operation :rf.error/handler-threw}]})

(deftest re-adds-an-errored-bundle-an-OUT-pill-dropped
  (testing "every errored bundle a filter dropped comes back tagged, in its
            scoped position; a clean drop stays hidden and a kept bundle is
            never tagged"
    (is (= [(assoc errored-out :rf.xray/filter-bypassed? true)
            clean-kept
            (assoc errored-in-non-match :rf.xray/filter-bypassed? true)]
           (eo/apply-error-overrides
             [errored-out clean-kept clean-dropped errored-in-non-match]
             [clean-kept]
             true)))))

(deftest disabled-is-a-passthrough-noop
  (testing "with the bypass off the filtered list passes through unchanged"
    (is (= [clean-kept]
           (eo/apply-error-overrides [errored-out clean-kept] [clean-kept] false)))))

(deftest all-kept-is-identity-in-value
  (testing "an errored bundle the filters KEPT was never bypassed, so it is
            not tagged"
    (is (= [errored-out clean-kept]
           (eo/apply-error-overrides [errored-out clean-kept]
                                     [errored-out clean-kept]
                                     true)))))
