(ns re-frame.http-encoding-test
  "Direct unit coverage for the pure-fn helpers in `re-frame.http-encoding`
  (rf2-9dro2; follow-on from rf2-q1z1u F2).

  Extended under rf2-ohwgm (http test-coverage audit) with the request-side
  encoding pipeline — `url-encode`, `params->query`, `merge-params`,
  `encode-body` — and the default `run-accept` normalisation. These run on
  every request / response yet had no direct test before.

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
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [re-frame.http-encoding :as encoding]
            [re-frame.util-json :as util-json]))

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

;; ===========================================================================
;; rf2-ohwgm — request-side encoding pipeline (G3) + default `run-accept` (G2)
;;
;; Per the http test-coverage audit (ai/findings/2026-05-21-testcov-http.md):
;; `encode-body` / `params->query` / `merge-params` / `url-encode` run on
;; EVERY request via `run-attempt!` (http_transport.cljc:749,754) yet had
;; zero direct test. The default `:accept` (`run-accept` with a nil
;; accept-fn) was likewise only exercised incidentally. Both are pure /
;; host-agnostic → the fast JVM layer.
;; ===========================================================================

;; ---- url-encode — Spec 014 §Body encoding (query escaping) ----------------

(deftest url-encode-escapes-reserved-characters
  (testing "rf2-ohwgm — url-encode percent-escapes reserved query
            characters so a value never breaks out of its key=value slot"
    (is (= "hello%20world" (encoding/url-encode "hello world"))
        "JVM maps the URLEncoder `+` to `%20` (space) per the source")
    (is (= "a%26b" (encoding/url-encode "a&b"))
        "ampersand escaped so it can't be read as a param separator")
    (is (= "a%3Db" (encoding/url-encode "a=b"))
        "equals escaped so it can't be read as a key/value delimiter")
    (is (= "a%2Bb" (encoding/url-encode "a+b"))
        "literal plus escaped to %2B (not collapsed with the space encoding)"))
  (testing "non-string args are coerced via (str ...) before encoding"
    (is (= "42" (encoding/url-encode 42)))
    (is (= "true" (encoding/url-encode true)))))

;; ---- params->query — keyword keys, escaping, joining ----------------------

(deftest params->query-encodes-keyword-keys-and-escapes-values
  (testing "rf2-ohwgm — params->query renders keyword keys via `name`,
            escapes values, and joins pairs with `&` (no leading `?`)"
    (is (= "page=2" (encoding/params->query {:page 2}))
        "keyword key → name; numeric value coerced via url-encode")
    (is (= "q=a%20b" (encoding/params->query {:q "a b"}))
        "value with a space is percent-escaped")
    (is (= "q=a%26b" (encoding/params->query {:q "a&b"}))
        "value with an ampersand is escaped so it can't forge a new param"))
  (testing "string keys pass through, keyword keys lose their colon"
    (is (= "limit=10" (encoding/params->query {"limit" 10}))))
  (testing "an empty params map renders an empty string"
    (is (= "" (encoding/params->query {})))))

(deftest params->query-multi-valued-uses-repeat-key
  (testing "rf2-mag59 — a sequential value (vector / seq / list) encodes
            as one repeated k=v pair per element (repeat-key idiom),
            NOT a single (str coll) blob"
    (is (= "tag=a&tag=b"
           (encoding/params->query {:tag ["a" "b"]}))
        "a vector value repeats the key per element — not tag=%5B%22a%22...")
    (is (= "id=1&id=2&id=3"
           (encoding/params->query {:id [1 2 3]}))
        "numeric elements coerce via url-encode like scalar values")
    (is (= "id=1&id=2"
           (encoding/params->query {:id (list 1 2)}))
        "a list (seq) value is treated the same as a vector")
    (is (= "q=a%20b&q=c%26d"
           (encoding/params->query {:q ["a b" "c&d"]}))
        "each element is independently percent-escaped"))
  (testing "an empty sequential value contributes no pair"
    (is (= "" (encoding/params->query {:tag []}))
        "an empty vector value emits nothing")
    (is (= "page=2"
           (encoding/params->query {:page 2 :tag []}))
        "an empty seq value drops out, scalar siblings still encode"))
  (testing "a single-element sequential still uses the key once"
    (is (= "tag=only"
           (encoding/params->query {:tag ["only"]}))))
  (testing "a set value (also sequential? false) is NOT repeat-keyed — only
            ordered seqs are; a set falls through to scalar (str ...)"
    ;; sets are unordered so repeat-key has no stable shape; treat as scalar.
    (is (clojure.string/starts-with?
          (encoding/params->query {:tag #{"a"}})
          "tag=")
        "a set value encodes via the scalar path (no defined repeat order)")))

;; ---- merge-params — `?` vs `&` separator selection ------------------------

(deftest merge-params-selects-question-mark-or-ampersand
  (testing "rf2-ohwgm — merge-params appends the query string with `?`
            when the URL has none, and `&` when the URL already carries a
            `?` (http_encoding.cljc:60-67)"
    (is (= "/items?page=2"
           (encoding/merge-params "/items" {:page 2}))
        "no existing `?` → join with `?`")
    (is (= "/items?sort=asc&page=2"
           (encoding/merge-params "/items?sort=asc" {:page 2}))
        "existing `?` → join with `&`"))
  (testing "no params (empty or nil) returns the URL unchanged"
    (is (= "/items" (encoding/merge-params "/items" {})))
    (is (= "/items" (encoding/merge-params "/items" nil)))))

;; ---- encode-body — Spec 014 §Body encoding --------------------------------
;;
;; Returns a tuple [encoded-body content-type]; content-type may be nil
;; (the caller decides whether to set the header). One assertion per
;; branch (nil / :json / :form / :text / explicit-MIME / coll-heuristic /
;; pass-through).

(deftest encode-body-nil-body-emits-no-content-type
  (testing "rf2-ohwgm — a nil body encodes to [nil nil] (no body, no
            Content-Type header)"
    (is (= [nil nil] (encoding/encode-body nil :json))
        "nil body short-circuits regardless of the requested content-type")
    (is (= [nil nil] (encoding/encode-body nil nil)))))

(deftest encode-body-json-request-content-type
  (testing "rf2-ohwgm — :request-content-type :json JSON-stringifies the
            body and returns application/json"
    (let [[body ct] (encoding/encode-body {:a 1 :b "two"} :json)]
      (is (= "application/json" ct))
      (is (= {:a 1 :b "two"} (util-json/json-parse body))
          "the body round-trips through json-parse (stable across key order)"))))

(deftest encode-body-form-request-content-type
  (testing "rf2-ohwgm — :request-content-type :form URL-encodes the map as
            a form body and returns application/x-www-form-urlencoded"
    (let [[body ct] (encoding/encode-body {:q "a b" :page 2} :form)]
      (is (= "application/x-www-form-urlencoded" ct))
      ;; form body is `params->query` of the map — assert each escaped pair
      ;; is present (map iteration order is not guaranteed).
      (is (clojure.string/includes? body "q=a%20b")
          "form value space-escaped")
      (is (clojure.string/includes? body "page=2")))))

(deftest encode-body-text-request-content-type
  (testing "rf2-ohwgm — :request-content-type :text stringifies the body
            and returns text/plain"
    (is (= ["hello" "text/plain"] (encoding/encode-body "hello" :text)))
    (is (= ["42" "text/plain"] (encoding/encode-body 42 :text))
        "non-string body coerced via (str ...)")))

(deftest encode-body-explicit-mime-string-request-content-type
  (testing "rf2-ohwgm — an explicit MIME-string :request-content-type
            stringifies the body and returns that exact MIME unchanged"
    (is (= ["<x/>" "application/xml"]
           (encoding/encode-body "<x/>" "application/xml")))))

(deftest encode-body-coll-heuristic-defaults-to-json
  (testing "rf2-ohwgm — with no explicit :request-content-type, a raw
            Clojure coll (map / sequential / set) is JSON-encoded and
            tagged application/json (the heuristic at http_encoding.cljc:97)"
    (let [[mbody mct] (encoding/encode-body {:a 1} nil)]
      (is (= "application/json" mct))
      (is (= {:a 1} (util-json/json-parse mbody))))
    (let [[vbody vct] (encoding/encode-body [1 2 3] nil)]
      (is (= "application/json" vct))
      (is (= [1 2 3] (util-json/json-parse vbody))))
    (let [[_ sct] (encoding/encode-body #{1 2 3} nil)]
      (is (= "application/json" sct)
          "a set also trips the coll heuristic"))))

(deftest encode-body-passthrough-string-no-content-type
  (testing "rf2-ohwgm — a non-coll body with no :request-content-type (a
            pre-encoded string / opaque value) passes through unchanged
            with a nil content-type (the caller sets no header)"
    (is (= ["already-encoded" nil]
           (encoding/encode-body "already-encoded" nil))
        "a bare string is pass-through: body kept, content-type nil")))

;; ---- run-accept — Spec 014 §`:accept` default normalisation (G2) ----------
;;
;; Per rf2-7iji6 the default `:accept` (nil accept-fn) is unconditionally
;; {:ok decoded}. The only call site (http-transport/handle-response!)
;; reaches run-accept exclusively inside the 2xx branch — status
;; classification (4xx / 5xx / non-2xx-else) runs BEFORE decode per Spec
;; 014 §Failure categories — so the default never sees a non-2xx status.
;; The earlier non-2xx arm ({:failure {:kind :http-status ...}}) was dead
;; on the live cascade and off the closed `:rf.http/*` taxonomy; it has
;; been removed. The user-fn branch is exercised end-to-end in
;; http_managed_test (accept-failure round-trip); here we pin the DEFAULT
;; and the simple user-fn pass-through.

(deftest run-accept-default-is-ok
  (testing "rf2-7iji6 — with no :accept fn, the decoded value is wrapped
            unconditionally as {:ok decoded} (run-accept only ever runs
            against an already-classified 2xx response)"
    (is (= {:ok {:title "hello"}}
           (encoding/run-accept nil {:title "hello"})))
    (is (= {:ok nil}
           (encoding/run-accept nil nil))
        "a nil decoded body still wraps as {:ok nil}")))

(deftest run-accept-user-fn-overrides-default
  (testing "rf2-ohwgm — a supplied :accept fn is invoked with the decoded
            value and its return ({:ok ..} or {:failure ..}) is used
            verbatim, overriding the default"
    (let [accept (fn [decoded]
                   (if (:valid? decoded)
                     {:ok (:data decoded)}
                     {:failure {:kind :domain :reason :invalid}}))]
      (is (= {:ok 42}
             (encoding/run-accept accept {:valid? true :data 42})))
      (is (= {:failure {:kind :domain :reason :invalid}}
             (encoding/run-accept accept {:valid? false}))
          "the user :accept can fail a 2xx response (domain-level rejection)"))))
