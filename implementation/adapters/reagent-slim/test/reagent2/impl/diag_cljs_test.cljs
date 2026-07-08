(ns reagent2.impl.diag-cljs-test
  "rf2-grd5hd — coverage + mirror-parity pin for `reagent2.impl.diag/value-summary`,
  the day8/reagent-slim EP-0015 diagnostic REDACTION primitive (Spec 015
  §Data-Classification, rf2-uwqale).

  `value-summary` exists so a hiccup head / child vector / Form-3 spec baked
  into a framework error message or ex-data slot carries only a SHAPE summary
  (type / count / sorted keys / bounded head) — never the app-owned value —
  before off-box capture (console, error boundary, host log, SSR error
  handler) can grab the raw value the record projector never got to classify.

  Two properties are pinned here:

    1. BRANCH + REDACTION coverage — every `cond` arm of `value-summary`
       (nil / map / vector / set / string / keyword / symbol / boolean /
       number / seq / fn / seqable / scalar), the 24-char head truncation,
       and the `sort-by str` catch fallback are exercised, each asserting the
       offending VALUE never rides into the summary.

    2. MIRROR PARITY — `value-summary` is a hand-copied 'content-byte-for-byte
       mirror' of `re-frame.error/diag-value-summary` (replicated INLINE
       because the slim bundle-isolation gate forbids the production build
       `:require`-ing re-frame.*). A hand-replicated mirror with no parity
       test silently drifts, so this asserts the two agree over a value
       corpus. Bundle-isolation binds only PRODUCTION builds; this test-only
       ns may require both — the slim bundle-isolation gate
       (check-reagent-slim-bundle-isolation.cjs) inspects shipped bundles,
       not the test classpath.

  Pure data — no runtime state; rides `npm run test:cljs` via the `cljs-test$`
  ns-regexp."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.diag :as diag]
            [re-frame.error :as error]))

;; ---- per-branch shape coverage --------------------------------------------

(deftest value-summary-degenerate-shapes
  (testing "nil / fn / boolean / seq summarise to their bare shape tag"
    (is (= {:type :nil} (diag/value-summary nil)))
    (is (= :fn (:type (diag/value-summary (fn [] nil)))))
    (is (= {:type :boolean :head "true"}  (diag/value-summary true)))
    (is (= {:type :boolean :head "false"} (diag/value-summary false)))
    ;; A lazy-seq (and a plain list) is caught by the `seq?` arm BEFORE the
    ;; catch-all `seqable?` arm — count is intentionally NOT carried (an
    ;; unbounded/lazy seq must not be realised on the failure path).
    (is (= {:type :seq} (diag/value-summary (map inc [1 2 3]))))
    (is (= {:type :seq} (diag/value-summary '(1 2 3))))))

(deftest value-summary-collections-are-shape-only
  (testing "vector / set carry :count but never their elements"
    (is (= {:type :vector :count 3}
           (diag/value-summary [:div {:on-click (fn [] nil)} "child xyzzy"])))
    (is (= :set (:type (diag/value-summary #{1 2 3}))))
    (is (= 3   (:count (diag/value-summary #{1 2 3}))))
    ;; no child content in the printed vector summary
    (is (not (re-find #"xyzzy"
                      (pr-str (diag/value-summary [:div "child xyzzy"])))))))

(deftest value-summary-scalar-heads
  (testing "keyword / symbol / number keep a recognition head"
    (is (= {:type :keyword :head ":ws.app/request"}
           (diag/value-summary :ws.app/request)))
    (is (= {:type :symbol :head "reagent2.template/as-element"}
           (diag/value-summary 'reagent2.template/as-element)))
    (is (= {:type :number :head "42"} (diag/value-summary 42)))))

;; ---- REDACTION: string head is bounded at the 24-char head-limit ----------

(deftest value-summary-string-head-truncates-at-head-limit
  (testing "a long/secret string carries :count + a bounded, ellipsised head —
            the tail of the secret never rides into the summary"
    (let [secret  "super-secret-bearer-token-value-1234567890"
          s       (diag/value-summary secret)]
      (is (= :string (:type s)))
      (is (= (count secret) (:count s)) "the full length is structural, not the value")
      (is (not= secret (:head s)) "the whole secret is NOT the head")
      ;; head-limit is 24; head is 24 chars + a single ellipsis char.
      (is (<= (count (:head s)) 25) "head bounded at head-limit (+ ellipsis)")
      (is (re-find #"…$" (:head s)) "an over-limit head is ellipsis-marked")
      (is (not (re-find #"1234567890" (:head s)))
          "the bounded head truncated before the tail of the secret")))
  (testing "a string AT/under the limit is carried whole with no ellipsis"
    (let [short "short-string"                        ;; 12 chars
          s     (diag/value-summary short)]
      (is (= {:type :string :count 12 :head "short-string"} s))
      (is (not (re-find #"…" (:head s)))))))

;; ---- REDACTION: a map keeps structural keys, drops values -----------------

(deftest value-summary-map-keeps-keys-drops-values
  (testing "a map summary carries sorted structural keys + count, never values"
    (let [m {:token "secret" :pdf "%PDF-1.4 huge blob" :n 7}
          s (diag/value-summary m)]
      (is (= :map (:type s)))
      (is (= 3 (:count s)))
      (is (= [:n :pdf :token] (:keys s)) "keys are sorted-by-str + structural")
      (let [printed (pr-str s)]
        (is (not (re-find #"secret" printed)))
        (is (not (re-find #"%PDF" printed))
            "no map VALUE leaked into the summary's printed form")))))

;; ---- the sort-by str CATCH fallback ---------------------------------------

(deftest value-summary-map-key-sort-throw-falls-back-to-unsorted-keys
  (testing "when `(sort-by str (keys v))` throws, the summary falls back to
            `(vec (keys v))` WITHOUT throwing — the shape still summarises"
    ;; A JS object whose toString throws makes `(str k)` — and therefore the
    ;; `sort-by str` keyfn — throw, exercising the `(catch :default …)` arm.
    (let [boom-key (js-obj)]
      (set! (.-toString boom-key) (fn [] (throw (js/Error. "boom"))))
      (let [m {boom-key :v :ok 1}
            s (diag/value-summary m)]
        (is (= :map (:type s)) "still summarised as a map")
        (is (= 2 (:count s)) "count survives the sort failure")
        (is (vector? (:keys s)) "keys fell back to (vec (keys v))")
        (is (= 2 (count (:keys s))) "both keys retained in the fallback")))))

;; ---- MIRROR PARITY vs re-frame.error/diag-value-summary -------------------

(def ^:private parity-corpus
  "A value corpus spanning every `value-summary` branch, including the head
  boundary (over/under 24 chars) and a hiccup-shaped vector. Each value is
  fed to BOTH summariser twins; the summaries must be `=`."
  [nil
   {}
   {:a 1 :b {:nested "v"} :c/d 2}
   [:div {:class "x"} "leaf text over twenty-four characters long here"]
   #{:x :y :z}
   ""
   "short"
   "boundary-exactly-24-chrs"                              ;; 24 chars, no ellipsis
   "a string that is definitely longer than twenty-four characters"
   :ws.app/request
   'reagent2.template/as-element
   true
   false
   0
   -12345678901234567890                                   ;; long numeric head
   3.14159
   '(1 2 3)
   (map inc [1 2 3])
   #js [1 2 3]                                             ;; seqable? arm (not seq?)
   (js/Date. 0)])                                          ;; final :scalar arm

(deftest value-summary-mirrors-re-frame-error-diag-value-summary
  (testing "the slim mirror agrees with re-frame.error/diag-value-summary
            byte-for-byte over the corpus (drift guard for the hand-copy)"
    (doseq [v parity-corpus]
      (is (= (error/diag-value-summary v) (diag/value-summary v))
          (str "mirror drift for value: " (pr-str v))))))

(deftest value-summary-mirrors-on-fn-and-lambda
  (testing "a fn value summarises to the SAME bare {:type :fn} on both twins"
    (let [f (fn [] :x)]
      (is (= (error/diag-value-summary f) (diag/value-summary f)))
      (is (= {:type :fn} (diag/value-summary f))))))
