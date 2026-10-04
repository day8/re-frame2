(ns reagent2.impl.diag-cljs-test
  "Coverage + mirror-parity pin for `reagent2.impl.diag/value-summary`,
  the day8/reagent-slim EP-0015 diagnostic REDACTION primitive (Spec 015
  §Data-Classification).

  `value-summary` exists so a hiccup head / child vector / Form-3 spec baked
  into a framework error message or ex-data slot carries only a SHAPE summary
  (type, and the size of a counted collection) — never the app-owned value —
  before off-box capture (console, error boundary, host log, SSR error
  handler) can grab the raw value the record projector never got to classify.

  The summary is content-free BY CONSTRUCTION — every value it carries is
  a closed-vocabulary `:type` keyword or an integer count. There is no
  `:head` (a printed prefix of the value carries content, and has no safe
  bound for keywords/symbols) and no map `:keys` (every top-level key is
  app-controlled and unbounded in number). So this suite asserts that
  grammar rather than a truncation quality.

  Two properties are pinned here:

    1. HOSTILE INPUT — a value whose `toString` throws still summarises.
       The parity corpus below cannot carry one, so it is pinned on its own.

    2. MIRROR PARITY — `value-summary` is a hand-copied 'content-byte-for-byte
       mirror' of `re-frame.error/diag-value-summary` (replicated INLINE
       because the slim bundle-isolation gate forbids the production build
       `:require`-ing re-frame.*). A hand-replicated mirror with no parity
       test silently drifts, so this asserts the two agree over a value
       corpus that reaches every `cond` arm, and
       `re-frame.diag-value-summary-cljs-test` pins each arm's redaction on
       the original. Bundle-isolation binds only PRODUCTION builds; this test-only
       ns may require both — the slim bundle-isolation gate
       (check-reagent-slim-bundle-isolation.cjs) inspects shipped bundles,
       not the test classpath.

  Pure data — no runtime state; rides `npm run test:cljs` via the `cljs-test$`
  ns-regexp."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.diag :as diag]
            [re-frame.error :as rf.error]))

;; ---- REDACTION: a string discloses its SIZE and nothing else --------------

;; ---- REDACTION: a map discloses its CARDINALITY and nothing else ----------

;; ---- a hostile toString cannot throw OUT of the diagnostic ---------------

(deftest value-summary-survives-a-throwing-tostring
  (testing "no leg calls `(str v)` on a value the framework knows nothing
            about, so a hostile `toString` cannot throw out of the
            summariser and destroy the failure it is describing"
    (let [boom (js-obj)]
      (set! (.-toString boom) (fn [] (throw (js/Error. "boom"))))
      (is (= {:type :scalar} (diag/value-summary boom))
          "an unknown host object summarises to its bare shape tag")
      (is (= {:type :map :count 2} (diag/value-summary {boom :v :ok 1}))
          "and cannot ride out through a map's key list either"))))

;; ---- MIRROR PARITY vs re-frame.error/diag-value-summary -------------------

(def ^:private parity-corpus
  "A value corpus spanning every `value-summary` branch, including strings
  either side of 24 chars, sentinel-bearing dynamic map
  keys and a hiccup-shaped vector. Each value is fed to BOTH summariser
  twins; the summaries must be `=`."
  [nil
   {}
   {:a 1 :b {:nested "v"} :c/d 2}
   {"SENTINELSENTINELSENTINEL" 1}                          ;; dynamic string key
   {(keyword "SENTINELSENTINELSENTINEL") 1}                ;; dynamic keyword key
   [:div {:class "x"} "leaf text over twenty-four characters long here"]
   #{:x :y :z}
   ""
   "short"
   "boundary-exactly-24-chrs"                              ;; exactly 24 chars
   "a string that is definitely longer than twenty-four characters"
   :ws.app/request
   'reagent2.template/as-element
   true
   false
   0
   -12345678901234567890                                   ;; long numeric printed form
   3.14159
   '(1 2 3)
   (map inc [1 2 3])
   (fn [] :x)                                              ;; :fn arm
   #js [1 2 3]                                             ;; seqable? arm (not seq?)
   (js/Date. 0)])                                          ;; final :scalar arm

(deftest value-summary-mirrors-re-frame-error-diag-value-summary
  (testing "the slim mirror agrees with re-frame.error/diag-value-summary
            byte-for-byte over the corpus (drift guard for the hand-copy)"
    (doseq [v parity-corpus]
      (is (= (rf.error/diag-value-summary v) (diag/value-summary v))
          (str "mirror drift for value: " (pr-str v))))))
