(ns reagent2.impl.diag-cljs-test
  "rf2-grd5hd — coverage + mirror-parity pin for `reagent2.impl.diag/value-summary`,
  the day8/reagent-slim EP-0015 diagnostic REDACTION primitive (Spec 015
  §Data-Classification, rf2-uwqale).

  `value-summary` exists so a hiccup head / child vector / Form-3 spec baked
  into a framework error message or ex-data slot carries only a SHAPE summary
  (type, and the size of a counted collection) — never the app-owned value —
  before off-box capture (console, error boundary, host log, SSR error
  handler) can grab the raw value the record projector never got to classify.

  rf2-210uq removed the two legs that made that claim false: the `:head`
  (a raw 24-char prefix of the printed value, and no bound at all for
  keywords/symbols) and a map's `:keys` (every top-level key, uncapped and
  unsanitised). The summary is now content-free BY CONSTRUCTION — every
  value it carries is a closed-vocabulary `:type` keyword or an integer
  count — so this suite asserts that grammar rather than a truncation
  quality.

  Two properties are pinned here:

    1. BRANCH + REDACTION coverage — every `cond` arm of `value-summary`
       (nil / map / vector / set / string / keyword / symbol / boolean /
       number / seq / fn / seqable / scalar) is exercised, each asserting
       the offending VALUE never rides into the summary.

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
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.diag :as diag]
            [re-frame.error :as error]))

;; ---- per-branch shape coverage --------------------------------------------

(deftest value-summary-degenerate-shapes
  (testing "nil / fn / boolean / seq summarise to their bare shape tag"
    (is (= {:type :nil} (diag/value-summary nil)))
    (is (= :fn (:type (diag/value-summary (fn [] nil)))))
    ;; rf2-210uq — a boolean's VALUE is app content and `:type` already says
    ;; everything the diagnostic needs, so no `:head` distinguishes them.
    (is (= {:type :boolean} (diag/value-summary true)))
    (is (= {:type :boolean} (diag/value-summary false)))
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

(deftest value-summary-scalar-shapes-carry-no-head
  (testing "rf2-210uq — keyword / symbol heads were returned with NO length
            bound at all, on the guess that such values are always
            structural. `(keyword user-string)` is not, so the head is gone"
    (is (= {:type :keyword} (diag/value-summary :ws.app/request)))
    (is (= {:type :symbol}  (diag/value-summary 'reagent2.template/as-element)))
    (is (= {:type :number}  (diag/value-summary 42)))
    ;; A keyword built from user input carries none of it.
    (is (= {:type :keyword}
           (diag/value-summary (keyword "SENTINELSENTINELSENTINEL-and-more"))))))

;; ---- REDACTION: a string discloses its SIZE and nothing else --------------

(deftest value-summary-string-discloses-no-content
  (testing "rf2-210uq — the pre-fix `:head` returned any string of 24 chars
            or fewer VERBATIM and a longer one's raw first 24 chars, so a
            short token rode back whole and a bearer token leaked its prefix"
    (let [secret "SENTINELSENTINELSENTINEL-tail-0123456789"
          s      (diag/value-summary secret)]
      (is (= {:type :string :count (count secret)} s)
          "size is shape and stays; nothing else survives")
      (is (not (str/includes? (pr-str s) "SENTINEL"))
          "no raw prefix of the secret reaches the summary")))
  (testing "a SHORT secret — under the old limit, so previously verbatim"
    (let [s (diag/value-summary "SENTINELSENTINEL")]
      (is (= {:type :string :count 16} s))
      (is (not (str/includes? (pr-str s) "SENTINEL"))))))

;; ---- REDACTION: a map discloses its CARDINALITY and nothing else ----------

(deftest value-summary-map-discloses-neither-keys-nor-values
  (testing "rf2-210uq — the pre-fix `:keys` leg returned every top-level key,
            uncapped and unsanitised. Map keys are app-controlled: they carry
            content, and an attacker-sized key set grew the summary unbounded"
    (let [m {:token "secret" :pdf "%PDF-1.4 huge blob" :n 7}
          s (diag/value-summary m)]
      (is (= {:type :map :count 3} s))
      (let [printed (pr-str s)]
        (is (not (str/includes? printed "secret")))
        (is (not (str/includes? printed "token")) "no map KEY either")
        (is (not (str/includes? printed "%PDF")))))
    ;; sentinel-bearing DYNAMIC keys of every key type
    (doseq [m [{"SENTINELSENTINELSENTINEL" 1}
               {(keyword "SENTINELSENTINELSENTINEL") 1}
               {(symbol "SENTINELSENTINELSENTINEL") 1}
               {["SENTINELSENTINELSENTINEL"] 1}
               {"<script>alert('SENTINEL')</script>" 1}]]
      (is (not (str/includes? (pr-str (diag/value-summary m)) "SENTINEL"))
          (str "key content leaked for " (pr-str (keys m))))))
  (testing "a VERY large map summarises to a FIXED size"
    (let [m       (into {} (map (fn [i] [(str "SENTINEL-key-" i) i])) (range 2000))
          printed (pr-str (diag/value-summary m))]
      (is (= "{:type :map, :count 2000}" printed))
      (is (not (str/includes? printed "SENTINEL"))))))

;; ---- a hostile toString no longer throws OUT of the diagnostic ------------

(deftest value-summary-survives-a-throwing-tostring
  (testing "rf2-210uq — `:head` called `(str v)` on values the framework
            knows nothing about, so a hostile `toString` threw out of the
            summariser and destroyed the failure it was describing. No
            `(str v)` remains, on any leg"
    (let [boom (js-obj)]
      (set! (.-toString boom) (fn [] (throw (js/Error. "boom"))))
      (is (= {:type :scalar} (diag/value-summary boom))
          "an unknown host object summarises to its bare shape tag")
      (is (= {:type :map :count 2} (diag/value-summary {boom :v :ok 1}))
          "and cannot ride out through a map's key list either"))))

;; ---- MIRROR PARITY vs re-frame.error/diag-value-summary -------------------

(def ^:private parity-corpus
  "A value corpus spanning every `value-summary` branch, including the
  historic 24-char head boundary (over/under), sentinel-bearing dynamic map
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
   "boundary-exactly-24-chrs"                              ;; exactly the old limit
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
