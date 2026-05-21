(ns re-frame.http-encoding-test
  "Direct unit coverage for the pure-fn helpers in `re-frame.http-encoding`
  (rf2-9dro2; follow-on from rf2-q1z1u F2).

  Specifically pins `compute-backoff-ms` against Spec 014 §Retry and
  backoff at the function boundary. The fn is currently exercised
  only indirectly via the managed-HTTP retry integration tests in
  `http_managed_test.clj`; a regression that bumped the jitter
  constant, the clamp threshold, or the exponent base would not
  surface at the helper's own test name today.

  Per Spec 014 §Retry and backoff:
   - default :base-ms 250, :factor 2, :max-ms 5000 (exponential)
   - attempt is 1-based; raw delay = base-ms × factor^(attempt-1)
   - clamp: raw is clamped to :max-ms before any jitter
   - jitter (when true): ±25% offset uniformly distributed,
     floor of zero (never negative)"
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http-encoding :as encoding]))

;; ---- attempt → delay (deterministic, jitter off) -------------------------

(deftest compute-backoff-ms-defaults-exponential-curve
  (testing "rf2-9dro2 — default config (base-ms 250, factor 2,
            max-ms 5000) produces the documented exponential curve:
            250, 500, 1000, 2000, 4000, then clamped to 5000."
    (is (= 250  (encoding/compute-backoff-ms {} 1))
        "attempt 1 = base-ms × factor^0 = 250 × 1 = 250")
    (is (= 500  (encoding/compute-backoff-ms {} 2))
        "attempt 2 = 250 × 2 = 500")
    (is (= 1000 (encoding/compute-backoff-ms {} 3))
        "attempt 3 = 250 × 4 = 1000")
    (is (= 2000 (encoding/compute-backoff-ms {} 4))
        "attempt 4 = 250 × 8 = 2000")
    (is (= 4000 (encoding/compute-backoff-ms {} 5))
        "attempt 5 = 250 × 16 = 4000")
    (is (= 5000 (encoding/compute-backoff-ms {} 6))
        "attempt 6 = 250 × 32 = 8000, clamped to max-ms 5000")
    (is (= 5000 (encoding/compute-backoff-ms {} 10))
        "attempt 10 = 250 × 512 = 128000, clamped to max-ms 5000")
    (is (= 5000 (encoding/compute-backoff-ms {} 20))
        "attempt 20 = very large, clamped to max-ms 5000")))

(deftest compute-backoff-ms-custom-base-and-factor
  (testing "rf2-9dro2 — caller-supplied :base-ms and :factor override
            the defaults"
    (is (= 100 (encoding/compute-backoff-ms {:base-ms 100 :factor 3} 1))
        "attempt 1 with base=100, factor=3 → 100 × 3^0 = 100")
    (is (= 300 (encoding/compute-backoff-ms {:base-ms 100 :factor 3} 2))
        "attempt 2 with base=100, factor=3 → 100 × 3 = 300")
    (is (= 900 (encoding/compute-backoff-ms {:base-ms 100 :factor 3} 3))
        "attempt 3 with base=100, factor=3 → 100 × 9 = 900")
    (is (= 2700 (encoding/compute-backoff-ms {:base-ms 100 :factor 3} 4))
        "attempt 4 with base=100, factor=3 → 100 × 27 = 2700")))

(deftest compute-backoff-ms-linear-when-factor-is-one
  (testing "rf2-9dro2 — :factor 1 produces a LINEAR (constant) backoff:
            every attempt waits :base-ms. Spec 014 §Retry config
            allows :factor 1 as the linear escape hatch."
    (let [cfg {:base-ms 500 :factor 1 :max-ms 10000}]
      (is (= 500 (encoding/compute-backoff-ms cfg 1)))
      (is (= 500 (encoding/compute-backoff-ms cfg 2)))
      (is (= 500 (encoding/compute-backoff-ms cfg 5)))
      (is (= 500 (encoding/compute-backoff-ms cfg 100))
          "factor=1 produces a constant base-ms delay regardless of
           attempt number — never grows, never clamps"))))

(deftest compute-backoff-ms-max-ms-clamp
  (testing "rf2-9dro2 — :max-ms is the upper clamp on the per-attempt
            delay; once raw exceeds :max-ms the result is exactly
            :max-ms (not bigger, not jittered)"
    (let [cfg {:base-ms 1000 :factor 2 :max-ms 3000}]
      (is (= 1000 (encoding/compute-backoff-ms cfg 1)))
      (is (= 2000 (encoding/compute-backoff-ms cfg 2)))
      (is (= 3000 (encoding/compute-backoff-ms cfg 3))
          "raw 4000 clamped to max 3000")
      (is (= 3000 (encoding/compute-backoff-ms cfg 4))
          "raw 8000 clamped to max 3000")
      (is (= 3000 (encoding/compute-backoff-ms cfg 50))
          "raw 1000 × 2^49 clamped to max 3000"))))

(deftest compute-backoff-ms-handles-low-attempt-numbers
  (testing "rf2-9dro2 — attempt 0 / negative is guarded by `(max 0
            (dec attempt))` in the exponent so it does not produce a
            negative exponent / fractional delay"
    (is (= 250 (encoding/compute-backoff-ms {} 0))
        "attempt 0 → exponent (max 0 -1) = 0 → 250 × 1 = 250
         (same as attempt 1; the floor is documented in the source)")
    (is (= 250 (encoding/compute-backoff-ms {} -5))
        "negative attempt → same floor at 250")))

(deftest compute-backoff-ms-returns-long-integer
  (testing "rf2-9dro2 — the return type is `long` (the source coerces
            via `(long jittered)`), suitable for direct use as a
            timeout argument"
    (let [result (encoding/compute-backoff-ms {} 3)]
      (is (integer? result))
      (is (instance? Long result)))))

;; ---- jitter — bounded range probe ----------------------------------------
;;
;; The jitter offset is `±25% × capped`, uniformly distributed. Pinning
;; the EXACT result is not stable (rand-driven), but pinning the
;; expected interval IS — `[0.75 × capped, 1.25 × capped]` with a
;; floor of zero per the source.

(deftest compute-backoff-ms-jitter-stays-within-spec-window
  (testing "rf2-9dro2 — when :jitter true, the result sits in the
            ±25% window around the capped raw value across many
            samples"
    (let [cfg     {:base-ms 1000 :factor 2 :max-ms 5000 :jitter true}
          attempt 3
          ;; Expected pre-jitter capped value: base × factor^(attempt-1)
          ;; = 1000 × 4 = 4000 (no clamp at attempt 3 with max 5000).
          capped  4000.0
          low     (* capped 0.75)
          high    (* capped 1.25)
          samples (repeatedly 200 #(encoding/compute-backoff-ms cfg attempt))]
      (doseq [s samples]
        (is (and (<= low s) (<= s high))
            (str "jittered sample " s " sits in ±25% window ["
                 low ", " high "]")))
      ;; And the samples are not all identical — sanity check that
      ;; jitter is actually being applied (catches a regression where
      ;; `:jitter true` silently fell through to the un-jittered branch).
      (is (> (count (set samples)) 1)
          "jitter produces variance across samples (catches a regression
           where the `:jitter true` arm was unreachable)"))))

(deftest compute-backoff-ms-jitter-never-negative
  (testing "rf2-9dro2 — the source caps jittered output at 0 via
            `(max 0 ...)`. With max-ms small enough that 25% offset
            could go negative, the floor protects the caller from a
            negative timeout"
    (let [cfg     {:base-ms 100 :factor 1 :max-ms 100 :jitter true}
          samples (repeatedly 200 #(encoding/compute-backoff-ms cfg 1))]
      (doseq [s samples]
        (is (<= 0 s)
            (str "jittered sample " s " is non-negative (floor at 0)"))))))

(deftest compute-backoff-ms-jitter-respects-clamp
  (testing "rf2-9dro2 — clamp happens BEFORE jitter is applied (per
            the source: `capped = min raw max-ms`, then jitter scales
            `capped`). So a long-running retry at the clamp still
            jitters around max-ms, not the raw-uncapped value."
    (let [cfg     {:base-ms 1000 :factor 2 :max-ms 2000 :jitter true}
          ;; attempt 10 — raw = 1000 × 512 = 512000, clamped to 2000.
          ;; Jittered samples should sit in [1500, 2500] (±25% of 2000).
          samples (repeatedly 200 #(encoding/compute-backoff-ms cfg 10))]
      (doseq [s samples]
        (is (and (<= 1500 s) (<= s 2500))
            (str "post-clamp jittered sample " s " sits in ±25% window
                  around clamp (1500..2500)"))))))

;; ---- build-reply-event — Spec 014 §Reply addressing -----------------------
;;
;; The four branches: explicit nil (silenced), explicit vector (append
;; payload), default/omitted (back to originator with :rf/reply merged),
;; and — per rf2-smqkq — an explicitly supplied non-vector non-nil value
;; (malformed) which must throw rather than silently default-merge.

(def ^:private reply-payload {:kind :success :value 42})

(deftest build-reply-event-explicit-nil-is-silenced
  (testing "explicit :on-success nil silences the reply"
    (is (nil? (encoding/build-reply-event
                {:origin-event  [:items/load {:page 1}]
                 :explicit-on   {:supplied? true :value nil}
                 :reply-payload reply-payload})))))

(deftest build-reply-event-explicit-vector-appends-payload
  (testing "explicit event vector gets the reply payload appended as last arg"
    (is (= [:items/loaded reply-payload]
           (encoding/build-reply-event
             {:origin-event  [:items/load {:page 1}]
              :explicit-on   {:supplied? true :value [:items/loaded]}
              :reply-payload reply-payload})))
    (testing "extra args on the supplied vector are preserved before the payload"
      (is (= [:items/loaded 7 reply-payload]
             (encoding/build-reply-event
               {:origin-event  [:items/load]
                :explicit-on   {:supplied? true :value [:items/loaded 7]}
                :reply-payload reply-payload}))))))

(deftest build-reply-event-default-merges-into-originator
  (testing "omitted handler routes back to the originating event-id with
            :rf/reply merged into the original message map"
    (is (= [:items/load {:page 1 :rf/reply reply-payload}]
           (encoding/build-reply-event
             {:origin-event  [:items/load {:page 1}]
              :explicit-on   {:supplied? false :value nil}
              :reply-payload reply-payload})))
    (testing "a non-map original message yields a fresh {:rf/reply ...} map"
      (is (= [:items/load {:rf/reply reply-payload}]
             (encoding/build-reply-event
               {:origin-event  [:items/load]
                :explicit-on   {:supplied? false :value nil}
                :reply-payload reply-payload}))))))

(deftest build-reply-event-non-vector-explicit-throws
  (testing "rf2-smqkq — an explicitly supplied non-vector non-nil reply
            target (keyword / map) is malformed per Spec 014 §Reply
            addressing ('event vector or nil') and must throw rather than
            silently fall through to the default-merge branch"
    ;; bare keyword
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/http-bad-reply-target"
                   (encoding/build-reply-event
                     {:origin-event  [:items/load {:page 1}]
                      :explicit-on   {:supplied? true :value :items/loaded}
                      :reply-payload reply-payload})))]
      (is (= :items/loaded (:value (ex-data ex)))
          "the rejected value is surfaced on the ex-data for diagnosis")
      (is (= :no-recovery (:recovery (ex-data ex)))))
    ;; map
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/http-bad-reply-target"
          (encoding/build-reply-event
            {:origin-event  [:items/load]
             :explicit-on   {:supplied? true :value {:dispatch :items/loaded}}
             :reply-payload reply-payload})))
    (testing "the malformed value is NOT silently re-routed to the originator"
      (is (not= [:items/load {:page 1 :rf/reply reply-payload}]
                (try (encoding/build-reply-event
                       {:origin-event  [:items/load {:page 1}]
                        :explicit-on   {:supplied? true :value :items/loaded}
                        :reply-payload reply-payload})
                     (catch clojure.lang.ExceptionInfo _ ::threw)))))))
