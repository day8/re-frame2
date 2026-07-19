(ns reagent2.impl.template-reserved-head-elision-prod-test
  "The reserved-`:rf/*`-head reject SURVIVES production (rf2-01zvu).

  WHY THIS FILE EXISTS. rf2-2hkfy is the cautionary precedent: a rejection
  described as \"always-on\" turned out to be `goog.DEBUG`-gated and was
  therefore ABSENT from the build users actually ship — the guard existed
  only in dev, which is precisely where it was least needed. A fail-loud
  guard that elides in production is worse than none, because the dev run
  is green and the shipped app paints the phantom silently.

  So this contract is MEASURED, not assumed, and measured on the build
  that ships. Files ending `-elision-prod-test.cljs` are picked up ONLY by
  the `:browser-test-prod-elision` shadow-cljs build — `:advanced` with
  `:closure-defines {goog.DEBUG false}` — so `goog.DEBUG` is
  constant-folded away before these assertions run. The default
  `:node-test` / `:browser-test` builds use `cljs-test$` /
  `-dom-cljs-test$` and do NOT match this ns.

  WHAT IS ASSERTED — the observable OUTCOME, not merely \"an exception\".
  rf2-1b0po found dispatching into a destroyed frame is a silent no-op
  rather than a throw, and rf2-6pfpt / rf2-2amkm found this whole class
  routinely fails to throw on CLJS at all (`(inc nil)` is `1`). So the
  load-bearing assertion is the one below that NO element is produced for a
  reserved head — the phantom is what the bead is about. The thrown
  payload is checked as well, but it is the phantom's ABSENCE that states
  the contract.

  The sibling `reagent2.impl.template-reserved-head-cljs-test` pins the
  same behaviour under dev; this file pins that production agrees."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.template :as template]))

(defn- render-outcome
  "{:painted <el-or-nil> :data <ex-data-or-nil>} for `form` — the OUTCOME,
  so a silent no-op and a silent paint are distinguishable from a reject."
  [form]
  (try
    {:painted (template/as-element form) :data nil}
    (catch cljs.core/ExceptionInfo ex
      {:painted nil :data (ex-data ex)})))

(deftest reserved-head-still-rejected-under-advanced-with-debug-false
  (testing "the harness really is the production build"
    (is (false? ^boolean js/goog.DEBUG)
        "goog.DEBUG must be constant-folded false here, or this file proves nothing"))

  (testing "no phantom element is produced for a reserved head"
    ;; THE load-bearing assertion. A guard that elided in production would
    ;; return a React element for <suspense-boundry> and this would fail.
    (let [{:keys [painted]} (render-outcome [:rf/suspense-boundry {:id 1}])]
      (is (nil? painted)
          "a reserved head must paint NOTHING in production")))

  (testing "the reject carries its catalogued identity in production"
    (let [{:keys [data]} (render-outcome [:rf/suspense-boundry {:id 1}])]
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data)))
      (is (= :use-a-recognised-reserved-head-or-an-unreserved-keyword
             (:recovery data))
          "the recovery token is not dev-only prose — it rides production")))

  (testing "the diagnostic prose survives :advanced"
    ;; Not a bundle grep — the string is READ BACK from the production
    ;; runtime, so it cannot pass vacuously against a stale or empty bundle.
    (let [{:keys [data]} (render-outcome [:rf/nope {}])]
      (is (re-find #"framework-reserved" (str (:reason data)))
          "the author-facing explanation must reach production users"))))

(deftest ordinary-heads-unaffected-in-production
  (testing "unreserved heads still paint under :advanced"
    (is (some? (:painted (render-outcome [:div "x"]))))
    (is (some? (:painted (render-outcome [:my-element "x"])))))

  (testing "the cache-collision guard holds in production too"
    (is (some? (:painted (render-outcome [:button "ordinary"]))))
    (is (nil? (:painted (render-outcome [:rf/button {}])))
        "an unreserved twin must not disarm the guard in the shipped build"))

  (testing "the string-aliased cache-hit guard holds in production too (rf2-sgbna)"
    ;; A STRING head "rf/x" and the reserved keyword `:rf/x` share the cache
    ;; key "rf/x"; seeding the string form must not let the keyword ride the
    ;; cache-HIT path and paint a phantom in the shipped :advanced build.
    (is (some? (:painted (render-outcome ["rf/prod-string-twin" "ordinary"])))
        "seed the cache under the aliased string key")
    (is (nil? (:painted (render-outcome [:rf/prod-string-twin {:id 1}])))
        "the string-seeded reserved twin must paint NOTHING in production")
    (is (= :rf.error/invalid-hiccup-head
           (:rf.error/id (:data (render-outcome [:rf/prod-string-twin {:id 1}]))))
        "and carry its catalogued identity from the cache-hit path")))
