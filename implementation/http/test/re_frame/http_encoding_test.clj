(ns re-frame.http-encoding-test
  "Direct unit coverage for the pure-fn helpers in `re-frame.http.encoding`.

  Covers the request-side encoding pipeline — `url-encode`,
  `params->query`, `merge-params`, `encode-body` — and the default
  `run-accept` normalisation. These run on every request / response, so
  each gets a direct test.

  Specifically pins `compute-backoff-ms` against Spec 014 §Retry and
  backoff at the function boundary. The managed-HTTP retry integration
  tests in `http_managed_test.clj` exercise the fn only indirectly, so
  without these a regression that bumped the jitter constant, the clamp
  threshold, or the exponent base would not surface at the helper's own
  test name.

  Per Spec 014 §Retry and backoff:
   - default :base-ms 250, :factor 2, :max-ms 5000 (exponential)
   - attempt is 1-based; raw delay = base-ms × factor^(attempt-1)
   - clamp: raw is clamped to :max-ms before any jitter
   - jitter (when true): ±25% offset uniformly distributed,
     floor of zero (never negative)"
  (:require [clojure.string]
            [clojure.test :refer [are deftest is testing]]
            [re-frame.http.encoding :as rf.http.encoding]
            [re-frame.http.json :as rf.http.json]))

;; ---- attempt → delay (deterministic, jitter off) -------------------------

(deftest compute-backoff-ms-without-jitter
  (testing "`default-backoff` holds the Spec 014 §Retry and backoff defaults
            (base-ms 250, factor 2, max-ms 5000) that `compute-backoff-ms`
            draws its `:or` defaults from"
    (is (= {:base-ms 250 :factor 2 :max-ms 5000} rf.http.encoding/default-backoff)))
  (testing "the default curve: base-ms × factor^(attempt-1) = 250, 500, 1000,
            2000, 4000, then clamped to max-ms 5000"
    (are [attempt ms] (= ms (rf.http.encoding/compute-backoff-ms {} attempt))
      1  250
      2  500
      3  1000
      4  2000
      5  4000
      6  5000
      10 5000
      20 5000))
  (testing "a caller's :base-ms and :factor override the defaults
            (100 × 3^(attempt-1))"
    (are [attempt ms] (= ms (rf.http.encoding/compute-backoff-ms {:base-ms 100 :factor 3} attempt))
      1 100
      2 300
      3 900
      4 2700))
  (testing ":factor 1 is the linear escape hatch Spec 014 §Retry config
            allows: every attempt waits :base-ms, never growing and never
            clamping"
    (are [attempt] (= 500 (rf.http.encoding/compute-backoff-ms {:base-ms 500 :factor 1 :max-ms 10000} attempt))
      1
      2
      5
      100))
  (testing "a caller's :max-ms clamps the delay to exactly :max-ms once the
            raw delay exceeds it"
    (are [attempt ms] (= ms (rf.http.encoding/compute-backoff-ms {:base-ms 1000 :factor 2 :max-ms 3000} attempt))
      1  1000
      2  2000
      3  3000
      4  3000
      50 3000))
  (testing "attempt 0 or below floors the exponent at 0 (`(max 0 (dec
            attempt))`), so the delay is base-ms, never a fractional one"
    (are [attempt] (= 250 (rf.http.encoding/compute-backoff-ms {} attempt))
      0
      -5)))

;; ---- jitter — bounded range probe ----------------------------------------
;;
;; The jitter offset is `±25% × capped`, uniformly distributed. Pinning
;; the EXACT result is not stable (rand-driven), but pinning the
;; expected interval IS — `[0.75 × capped, 1.25 × capped]` with a
;; floor of zero per the source.

(deftest compute-backoff-ms-jitter-stays-within-spec-window
  (testing "when :jitter true, the result sits in the
            ±25% window around the capped raw value across many
            samples"
    (let [cfg     {:base-ms 1000 :factor 2 :max-ms 5000 :jitter true}
          attempt 3
          ;; Expected pre-jitter capped value: base × factor^(attempt-1)
          ;; = 1000 × 4 = 4000 (no clamp at attempt 3 with max 5000).
          capped  4000.0
          low     (* capped 0.75)
          high    (* capped 1.25)
          samples (repeatedly 200 #(rf.http.encoding/compute-backoff-ms cfg attempt))]
      (doseq [s samples]
        (is (and (<= low s) (<= s high))
            (str "jittered sample " s " sits in ±25% window ["
                 low ", " high "]")))
      ;; And the samples are not all identical — sanity check that
      ;; jitter is actually being applied (catches a regression where
      ;; `:jitter true` silently falls through to the un-jittered branch).
      (is (> (count (set samples)) 1)
          "jitter produces variance across samples (catches a regression
           where the `:jitter true` arm is unreachable)"))))

(deftest compute-backoff-ms-jitter-respects-clamp
  (testing "clamp happens BEFORE jitter is applied (per
            the source: `capped = min raw max-ms`, then jitter scales
            `capped`). So a long-running retry at the clamp still
            jitters around max-ms, not the raw-uncapped value."
    (let [cfg     {:base-ms 1000 :factor 2 :max-ms 2000 :jitter true}
          ;; attempt 10 — raw = 1000 × 512 = 512000, clamped to 2000.
          ;; Jittered samples should sit in [1500, 2500] (±25% of 2000).
          samples (repeatedly 200 #(rf.http.encoding/compute-backoff-ms cfg 10))]
      (doseq [s samples]
        (is (and (<= 1500 s) (<= s 2500))
            (str "post-clamp jittered sample " s " sits in ±25% window
                  around clamp (1500..2500)")))
      ;; Clamping AFTER jitter would pin every sample at max-ms: the raw
      ;; value's jitter window [384000, 640000] lies wholly above the clamp.
      ;; Jittering the clamped value spreads samples both sides of it.
      (is (some #(> % 2000) samples)
          "some samples sit ABOVE max-ms — jitter applies to the clamped value")
      (is (some #(< % 2000) samples)
          "and some sit below it"))))

;; ---- build-reply-event — Spec 014 §Reply addressing -----------------------
;;
;; The branches: explicit nil (silenced), explicit vector (append payload),
;; NOT-supplied (nil — there is no co-located default;
;; a wholly-unaddressed request fails loud upstream at
;; `validate-reply-target!`, so an unsupplied branch reaching here is the
;; partial-addressing silence case), and an explicitly
;; supplied non-vector non-nil value (malformed) which must throw.

;; build-reply-event is payload-shape-agnostic; it appends /
;; merges whatever reply map it is given. The payload here is the CANONICAL
;; reply envelope (`{:status :ok :value …}`).
(def ^:private reply-payload {:status :ok :value 42})

(deftest build-reply-event-explicit-nil-is-silenced
  (testing "explicit :on-success nil silences the reply"
    (is (nil? (rf.http.encoding/build-reply-event
                {:origin-event  [:items/load {:page 1}]
                 :explicit-on   {:supplied? true :value nil}
                 :reply-payload reply-payload})))))

(deftest build-reply-event-explicit-vector-appends-payload
  (testing "explicit event vector gets the reply payload appended as last arg"
    (is (= [:items/loaded reply-payload]
           (rf.http.encoding/build-reply-event
             {:origin-event  [:items/load {:page 1}]
              :explicit-on   {:supplied? true :value [:items/loaded]}
              :reply-payload reply-payload})))
    (testing "extra args on the supplied vector are preserved before the payload"
      (is (= [:items/loaded 7 reply-payload]
             (rf.http.encoding/build-reply-event
               {:origin-event  [:items/load]
                :explicit-on   {:supplied? true :value [:items/loaded 7]}
                :reply-payload reply-payload}))))))

(deftest build-reply-event-unsupplied-is-nil
  (testing "an UNSUPPLIED branch (:supplied? false) yields nil
            (no event dispatched): there is no co-located default (no reply
            merged under :rf/reply back to the originating event).
            A wholly-unaddressed request fails loud upstream at
            validate-reply-target!; an unsupplied branch reaching here is
            the partial-addressing silence case."
    (is (nil? (rf.http.encoding/build-reply-event
                {:origin-event  [:items/load {:page 1}]
                 :explicit-on   {:supplied? false :value nil}
                 :reply-payload reply-payload})))))

(deftest build-reply-event-non-vector-explicit-throws
  (testing "an explicitly supplied non-vector non-nil reply
            target (keyword / map) is malformed per Spec 014 §Reply
            addressing ('event vector or nil') and must throw rather than
            silently re-route to the originator"
    ;; bare keyword
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/http-bad-reply-target"
                   (rf.http.encoding/build-reply-event
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
          (rf.http.encoding/build-reply-event
            {:origin-event  [:items/load]
             :explicit-on   {:supplied? true :value {:dispatch :items/loaded}}
             :reply-payload reply-payload})))))

;; ===========================================================================
;; Request-side encoding pipeline + default `run-accept`
;;
;; `encode-body` / `params->query` / `merge-params` / `url-encode` run on
;; EVERY request via `run-attempt!`, and the default `:accept` (`run-accept`
;; with a nil accept-fn) on every 2xx response, so each gets a direct test.
;; Both are pure / host-agnostic → the fast JVM layer.
;; ===========================================================================

;; ---- url-encode — Spec 014 §Body encoding (query escaping) ----------------

(deftest url-encode-escapes-reserved-characters
  (testing "url-encode percent-escapes reserved query
            characters so a value never breaks out of its key=value slot"
    (is (= "hello%20world" (rf.http.encoding/url-encode "hello world"))
        "JVM maps the URLEncoder `+` to `%20` (space) per the source")
    (is (= "a%26b" (rf.http.encoding/url-encode "a&b"))
        "ampersand escaped so it can't be read as a param separator")
    (is (= "a%3Db" (rf.http.encoding/url-encode "a=b"))
        "equals escaped so it can't be read as a key/value delimiter")
    (is (= "a%2Bb" (rf.http.encoding/url-encode "a+b"))
        "literal plus escaped to %2B (not collapsed with the space encoding)"))
  (testing "non-string args are coerced via (str ...) before encoding"
    (is (= "42" (rf.http.encoding/url-encode 42)))
    (is (= "true" (rf.http.encoding/url-encode true)))))

;; ---- params->query — keyword keys, escaping, joining ----------------------

(deftest params->query-encodes-keyword-keys-and-escapes-values
  (testing "params->query renders keyword keys via `name`,
            escapes values, and joins pairs with `&` (no leading `?`)"
    (is (= "page=2" (rf.http.encoding/params->query {:page 2}))
        "keyword key → name; numeric value coerced via url-encode")
    (is (= "q=a%20b" (rf.http.encoding/params->query {:q "a b"}))
        "value with a space is percent-escaped")
    (is (= "q=a%26b" (rf.http.encoding/params->query {:q "a&b"}))
        "value with an ampersand is escaped so it can't forge a new param"))
  (testing "string keys pass through, keyword keys lose their colon"
    (is (= "limit=10" (rf.http.encoding/params->query {"limit" 10}))))
  (testing "an empty params map renders an empty string"
    (is (= "" (rf.http.encoding/params->query {})))))

(deftest params->query-writes-keyword-values-without-the-colon
  (testing "a keyword VALUE is written as its colon-less qualified name — the
            spelling a JSON body gives it — never as `%3A…`"
    (is (= "status=active" (rf.http.encoding/params->query {:status :active}))
        "a bare keyword loses only its colon")
    (is (= "sort=sort%2Fasc" (rf.http.encoding/params->query {:sort :sort/asc}))
        "a qualified keyword keeps its namespace")
    (is (= "tag=a&tag=b%2Fc" (rf.http.encoding/params->query {:tag [:a :b/c]}))
        "each element of a sequential value is written the same way")
    (is (= "/items?status=active#top"
           (rf.http.encoding/merge-params "/items#top" {:status :active}))
        "and the URL the request is sent to carries it")))

(deftest params->query-multi-valued-uses-repeat-key
  (testing "a sequential value (vector / seq / list) encodes
            as one repeated k=v pair per element (repeat-key idiom),
            NOT a single (str coll) blob"
    (is (= "tag=a&tag=b"
           (rf.http.encoding/params->query {:tag ["a" "b"]}))
        "a vector value repeats the key per element — not tag=%5B%22a%22...")
    (is (= "id=1&id=2&id=3"
           (rf.http.encoding/params->query {:id [1 2 3]}))
        "numeric elements coerce via url-encode like scalar values")
    (is (= "id=1&id=2"
           (rf.http.encoding/params->query {:id (list 1 2)}))
        "a list (seq) value is treated the same as a vector")
    (is (= "q=a%20b&q=c%26d"
           (rf.http.encoding/params->query {:q ["a b" "c&d"]}))
        "each element is independently percent-escaped"))
  (testing "an empty sequential value contributes no pair"
    (is (= "" (rf.http.encoding/params->query {:tag []}))
        "an empty vector value emits nothing")
    (is (= "page=2"
           (rf.http.encoding/params->query {:page 2 :tag []}))
        "an empty seq value drops out, scalar siblings still encode"))
  (testing "a single-element sequential still uses the key once"
    (is (= "tag=only"
           (rf.http.encoding/params->query {:tag ["only"]}))))
  (testing "a set value (also sequential? false) is NOT repeat-keyed — only
            ordered seqs are; a set falls through to scalar (str ...)"
    ;; sets are unordered so repeat-key has no stable shape; treat as scalar.
    (is (clojure.string/starts-with?
          (rf.http.encoding/params->query {:tag #{"a"}})
          "tag=")
        "a set value encodes via the scalar path (no defined repeat order)")))

;; ---- merge-params — `?` vs `&` separator selection ------------------------

(deftest merge-params-selects-question-mark-or-ampersand
  (testing "merge-params appends the query string with `?`
            when the URL has none, and `&` when the URL already carries a
            `?`"
    (is (= "/items?page=2"
           (rf.http.encoding/merge-params "/items" {:page 2}))
        "no existing `?` → join with `?`")
    (is (= "/items?sort=asc&page=2"
           (rf.http.encoding/merge-params "/items?sort=asc" {:page 2}))
        "existing `?` → join with `&`"))
  (testing "no params (empty or nil) returns the URL unchanged"
    (is (= "/items" (rf.http.encoding/merge-params "/items" {})))
    (is (= "/items" (rf.http.encoding/merge-params "/items" nil)))))

(deftest merge-params-splices-before-fragment
  (testing "params are spliced BEFORE a `#fragment`, never after.
            Query text after a `#` is fragment text — real HTTP clients send
            the fragment to nobody, so appending the query AFTER the `#` (a
            `(str url sep qs)` shape) would silently drop the params."
    (is (= "/items?page=2#frag"
           (rf.http.encoding/merge-params "/items#frag" {:page 2}))
        "no existing `?` → the query is inserted with `?` BEFORE `#frag`,
         and the fragment is reattached verbatim AFTER the query")
    (is (= "/items?sort=asc&page=2#frag"
           (rf.http.encoding/merge-params "/items?sort=asc#frag" {:page 2}))
        "existing `?` in the pre-fragment part → join with `&`, fragment
         stays at the very end")))

(deftest merge-params-no-encoded-pairs-leaves-url-unchanged
  (testing "when the params map encodes to NO query pairs (e.g.
            `{:tag []}` per params->query's empty-sequential rule) the URL is
            returned UNCHANGED — no dangling `?` / `&`. The decision keys off
            the ENCODED query string, not `(seq params)`."
    (is (= "/items"
           (rf.http.encoding/merge-params "/items" {:tag []}))
        "an all-empty-sequential params map yields no pairs → no dangling `?`")
    (is (= "/items?sort=asc"
           (rf.http.encoding/merge-params "/items?sort=asc" {:tag []}))
        "an existing query is preserved with no dangling `&`")
    (is (= "/items#frag"
           (rf.http.encoding/merge-params "/items#frag" {:tag []}))
        "a fragment-only URL with no encodable params is returned verbatim")
    (testing "a mix of empty + non-empty still encodes the non-empty pairs"
      (is (= "/items?page=2#frag"
             (rf.http.encoding/merge-params "/items#frag" {:tag [] :page 2}))
          "the empty-sequential drops out; the scalar sibling still splices
           before the fragment"))))

;; ---- encode-body — Spec 014 §Body encoding --------------------------------
;;
;; Returns a tuple [encoded-body content-type]; content-type may be nil
;; (the caller decides whether to set the header). One assertion per
;; branch (nil / :json / :form / :text / explicit-MIME / coll-heuristic /
;; pass-through).

(deftest encode-body-nil-body-emits-no-content-type
  (testing "a nil body encodes to [nil nil] (no body, no
            Content-Type header)"
    (is (= [nil nil] (rf.http.encoding/encode-body nil :json))
        "nil body short-circuits regardless of the requested content-type")
    (is (= [nil nil] (rf.http.encoding/encode-body nil nil)))))

(deftest encode-body-json-request-content-type
  (testing ":request-content-type :json JSON-stringifies the
            body and returns application/json"
    (let [[body ct] (rf.http.encoding/encode-body {:a 1 :b "two"} :json)]
      (is (= "application/json" ct))
      (is (= {:a 1 :b "two"} (rf.http.json/json-parse body))
          "the body round-trips through json-parse (stable across key order)"))))

(deftest encode-body-form-request-content-type
  (testing ":request-content-type :form URL-encodes the map as
            a form body and returns application/x-www-form-urlencoded"
    (let [[body ct] (rf.http.encoding/encode-body {:q "a b" :page 2} :form)]
      (is (= "application/x-www-form-urlencoded" ct))
      ;; form body is `params->query` of the map — assert each escaped pair
      ;; is present (map iteration order is not guaranteed).
      (is (clojure.string/includes? body "q=a%20b")
          "form value space-escaped")
      (is (clojure.string/includes? body "page=2")))))

(deftest encode-body-text-request-content-type
  (testing ":request-content-type :text stringifies the body
            and returns text/plain"
    (is (= ["hello" "text/plain"] (rf.http.encoding/encode-body "hello" :text)))
    (is (= ["42" "text/plain"] (rf.http.encoding/encode-body 42 :text))
        "non-string body coerced via (str ...)")))

(deftest encode-body-explicit-mime-string-request-content-type
  (testing "an explicit MIME-string :request-content-type
            stringifies the body and returns that exact MIME unchanged"
    (is (= ["<x/>" "application/xml"]
           (rf.http.encoding/encode-body "<x/>" "application/xml")))))

(deftest encode-body-coll-heuristic-defaults-to-json
  (testing "with no explicit :request-content-type, a raw
            Clojure coll (map / sequential / set) is JSON-encoded and
            tagged application/json (the coll heuristic in `encode-body`)"
    (let [[mbody mct] (rf.http.encoding/encode-body {:a 1} nil)]
      (is (= "application/json" mct))
      (is (= {:a 1} (rf.http.json/json-parse mbody))))
    (let [[vbody vct] (rf.http.encoding/encode-body [1 2 3] nil)]
      (is (= "application/json" vct))
      (is (= [1 2 3] (rf.http.json/json-parse vbody))))
    (let [[_ sct] (rf.http.encoding/encode-body #{1 2 3} nil)]
      (is (= "application/json" sct)
          "a set also trips the coll heuristic"))))

(deftest encode-body-passthrough-string-no-content-type
  (testing "a non-coll body with no :request-content-type (a
            pre-encoded string / opaque value) passes through unchanged
            with a nil content-type (the caller sets no header)"
    (is (= ["already-encoded" nil]
           (rf.http.encoding/encode-body "already-encoded" nil))
        "a bare string is pass-through: body kept, content-type nil")))

;; ---- run-accept — Spec 014 §`:accept` default normalisation ---------------
;;
;; The default `:accept` (nil accept-fn) is unconditionally
;; {:ok decoded}. The only call site (http-transport/handle-response!)
;; reaches run-accept exclusively inside the 2xx branch — status
;; classification (4xx / 5xx / non-2xx-else) runs BEFORE decode per Spec
;; 014 §Failure categories — so the default never sees a non-2xx status.
;; It has no non-2xx arm: a {:failure {:kind :http-status ...}} default
;; would be dead on the live cascade and off the closed `:rf.http/*`
;; taxonomy. The user-fn branch is exercised end-to-end in
;; http_managed_test (accept-failure round-trip); here we pin the DEFAULT
;; and the simple user-fn pass-through.

(deftest run-accept-default-is-ok
  (testing "with no :accept fn, the decoded value is wrapped
            unconditionally as {:ok decoded} (run-accept only ever runs
            against an already-classified 2xx response)"
    (is (= {:ok {:title "hello"}}
           (rf.http.encoding/run-accept nil {:title "hello"})))
    (is (= {:ok nil}
           (rf.http.encoding/run-accept nil nil))
        "a nil decoded body still wraps as {:ok nil}")))

(deftest run-accept-default-never-produces-http-status-rf2-xmp74u
  (testing "conformance guard: the default `:accept` (nil
            accept-fn) NEVER returns a `:failure` and NEVER the off-taxonomy
            `:kind :http-status`. A non-2xx
            `{:failure {:kind :http-status ...}}` default branch would
            contradict the closed `:rf.http/*` failure set + the status-
            before-decode classification order (a non-2xx never reaches
            accept). This pins the default as `{:ok ...}` across every decoded
            shape so a port can't introduce that dead branch."
    (doseq [decoded [nil
                     {}
                     {:title "hello"}
                     {:error "server blew up" :status 500}
                     [1 2 3]
                     "raw text"
                     42]]
      (let [result (rf.http.encoding/run-accept nil decoded)]
        (is (contains? result :ok)
            (str "default accept yields {:ok ...} for " (pr-str decoded)))
        (is (not (contains? result :failure))
            "default accept NEVER yields a :failure, so never an off-taxonomy :kind :http-status")))))

(deftest run-accept-user-fn-overrides-default
  (testing "a supplied :accept fn is invoked with the decoded
            value and its return ({:ok ..} or {:failure ..}) is used
            verbatim, overriding the default"
    (let [accept (fn [decoded]
                   (if (:valid? decoded)
                     {:ok (:data decoded)}
                     {:failure {:kind :domain :reason :invalid}}))]
      (is (= {:ok 42}
             (rf.http.encoding/run-accept accept {:valid? true :data 42})))
      (is (= {:failure {:kind :domain :reason :invalid}}
             (rf.http.encoding/run-accept accept {:valid? false}))
          "the user :accept can fail a 2xx response (domain-level rejection)"))))

;; ---- valid-accept-return? — Spec 014 §`:accept` shape validation ----------

(deftest valid-accept-return-recognises-ok-and-failure
  (testing "a map carrying EXACTLY one of :ok / :failure is the
            recognised accept-return shape"
    (is (rf.http.encoding/valid-accept-return? {:ok 42}))
    (is (rf.http.encoding/valid-accept-return? {:ok nil})
        "{:ok nil} is valid — the key presence is what matters, not the value")
    (is (rf.http.encoding/valid-accept-return? {:failure {:kind :domain}}))
    (is (rf.http.encoding/valid-accept-return? {:ok 1 :extra :ignored})
        "extra keys alongside the single recognised key are tolerated")))

(deftest valid-accept-return-rejects-malformed-shapes
  (testing "nil, non-maps, and maps without exactly one of
            :ok/:failure are MALFORMED (accepted unvalidated, they would
            strand the request with no reply)"
    (is (not (rf.http.encoding/valid-accept-return? nil))
        "nil return is malformed")
    (is (not (rf.http.encoding/valid-accept-return? {}))
        "an empty map carries neither key")
    (is (not (rf.http.encoding/valid-accept-return? {:status :good}))
        "a map without :ok/:failure is malformed")
    (is (not (rf.http.encoding/valid-accept-return? {:ok 1 :failure {}}))
        "a map carrying BOTH keys is ambiguous → rejected")
    (is (not (rf.http.encoding/valid-accept-return? :ok))
        "a bare keyword is not a map")
    (is (not (rf.http.encoding/valid-accept-return? [:ok 1]))
        "a vector is not a map")
    (is (not (rf.http.encoding/valid-accept-return? "ok"))
        "a string is not a map")))

;; ---- normalize-header-pairs — Spec 014 §Request envelope (multi-valued) ---

(deftest normalize-header-pairs-scalar-yields-one-pair
  (testing "a scalar header value yields exactly one [name value]
            wire pair, stringified"
    (is (= [["Accept" "application/json"]]
           (rf.http.encoding/normalize-header-pairs {"Accept" "application/json"})))
    (is (= [["X-Count" "42"]]
           (rf.http.encoding/normalize-header-pairs {"X-Count" 42}))
        "a non-string scalar value is stringified per element")))

(deftest normalize-header-pairs-vector-yields-pair-per-element
  (testing "a vector/seq header value yields ONE wire pair per
            element (the HTTP multi-valued idiom = repeat the name), NOT a
            single pair carrying the vector"
    (is (= [["Accept" "text/html"] ["Accept" "application/json"]]
           (rf.http.encoding/normalize-header-pairs {"Accept" ["text/html" "application/json"]}))
        "each element gets its own [name value] pair, in order")
    (is (= [["X-Tag" "1"] ["X-Tag" "2"] ["X-Tag" "3"]]
           (rf.http.encoding/normalize-header-pairs {"X-Tag" [1 2 3]}))
        "numeric elements are stringified per element")
    (is (= [["X-Tag" "a"] ["X-Tag" "b"]]
           (rf.http.encoding/normalize-header-pairs {"X-Tag" (list "a" "b")}))
        "a seq value is treated like a vector"))
  (testing "an empty sequential value contributes NO pair (header absent)"
    (is (= [] (rf.http.encoding/normalize-header-pairs {"X-Empty" []})))
    (is (= [["Accept" "application/json"]]
           (rf.http.encoding/normalize-header-pairs {"X-Empty" [] "Accept" "application/json"}))
        "the empty-sequential drops out; scalar siblings still emit")))
