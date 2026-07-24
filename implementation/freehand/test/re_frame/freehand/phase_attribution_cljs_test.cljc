(ns re-frame.freehand.phase-attribution-cljs-test
  "The error PHASE is public diagnostic data and part of the failure
  fingerprint: `:render` means a declared view BODY threw, `:normalize` means
  the emitter's walk of what the body returned threw (a lazy child realised, a
  refused prop). A normalization defect reported as `:render` mislabels the
  failure and — because the fingerprint derives from the view id and the phase
  — collapses a body defect and a normalization defect in the SAME view onto
  one correlation token.

  This suite proves the discriminator on the host-neutral structural walk (it
  is `.cljc`, so it runs on the JVM and in ClojureScript). The browser
  realisation of the same law rides the React class boundary and is proven
  beside the behavior-seam scenario in `behavior-throw-dom-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.tree :as tree]))

;; ONE declared view with two ways to fail, selected by a prop so the two
;; failures share a view id and differ ONLY in phase. Each failure throws a
;; FRESH exception: the attribution relay is keyed by the thrown value's
;; identity and is first-writer-wins, so a single shared object reused across
;; both renders would carry the first render's note into the second.
(v/defview flaky
  "Its body throws when asked (a `:render` failure), or it returns markup
  whose lazy child throws only when the walk realises it (a `:normalize`
  failure)."
  [props]
  (if (:in-body? props)
    (throw (ex-info "flaky body threw" {}))
    [:div.node (map (fn [_] (throw (ex-info "flaky walk threw" {}))) [:row])]))

(defn- contained-summary
  "The safe summary of the failure `form`'s walk produces under a fresh
  boundary. The boundary can only supply a DEFAULT phase (`:render`); the
  occurrence seam's observed phase is what must win."
  [form]
  (let [b (eb/boundary eb/boundary-view-id :rk)]
    (:summary (eb/contain b #(tree/render form) {:phase :render :frame-id nil}))))

(deftest a-view-body-throw-is-render-and-a-walk-throw-is-normalize
  (testing "The same declared view fails two ways. Calling the body and having
            it throw is a `:render` failure; the body returning markup whose
            lazy child throws when the walk realises it is a `:normalize`
            failure. The boundary passes `:render` as its default, so a summary
            that reads `:render` for the walk case would be the boundary's
            guess, not the seam's observation."
    (let [body-summary (contained-summary [flaky {:in-body? true}])
          walk-summary (contained-summary [flaky {:in-body? false}])]
      (is (= :render (:phase body-summary))
          "the body threw as it was called")
      (is (= :normalize (:phase walk-summary))
          "the body returned; the walk realising the lazy child threw")
      (is (contains? eb/phases (:phase body-summary)))
      (is (contains? eb/phases (:phase walk-summary))))))

(deftest the-same-view-fingerprints-apart-by-phase
  (testing "Because the fingerprint derives from the failing view id AND the
            phase, a body defect and a normalization defect in ONE view carry
            two correlation tokens. Were the phase hardcoded, they would
            collapse onto a single token and a reader could not tell the two
            failures apart."
    (let [body-summary (contained-summary [flaky {:in-body? true}])
          walk-summary (contained-summary [flaky {:in-body? false}])]
      (is (= (:view-id body-summary) (:view-id walk-summary))
          "the same declared view failed both times")
      (is (not= (:fingerprint body-summary) (:fingerprint walk-summary))
          "yet the two failures fingerprint apart, because :phase is part of
           the token")
      (is (= (eb/fingerprint (:view-id walk-summary) :normalize)
             (:fingerprint walk-summary))
          "and the normalization token names the normalization phase"))))

(deftest non-vacuity-the-body-throws-when-called-and-the-walk-throws-when-realised
  (testing "Non-vacuity: the `:render` variant really throws when the body is
            CALLED, while the `:normalize` variant's body returns markup
            without throwing and it is REALISING the returned lazy child that
            throws — so the phases above are the two genuinely distinct moments
            and not one moment relabelled."
    (is (thrown? #?(:clj Throwable :cljs :default)
                 ((descriptor/render-body flaky) {:in-body? true}))
        "calling the body throws directly")
    (let [form ((descriptor/render-body flaky) {:in-body? false})]
      (is (vector? form)
          "the walk variant's body returns markup without throwing")
      (is (thrown? #?(:clj Throwable :cljs :default)
                   (doall (last form)))
          "and realising the returned lazy child is what throws"))))
