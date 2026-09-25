(ns re-frame.http-decode-test
  "Direct unit coverage for the response-body decode pipeline in
  `re-frame.http.decode`.

  Per Spec 014 §Decoding / §`:auto`, these tests cover Malli decode,
  coercion and validation, plus end-to-end keyword-cap propagation.

  These fns are pure / host-agnostic, so they belong on the fast JVM
  `clojure -M:test` layer. Malli is on the http test classpath via the
  `day8/re-frame2-schemas` test-dep (its transitive `metosin/malli`),
  so `requiring-resolve` of `malli.core/decode` / `malli.core/validate`
  / `malli.transform/json-transformer` succeeds at runtime — the
  schema branch exercises the real Malli decode + coerce + validate."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; `decode-response-body` is public; `malli-decode` is private — reach it
;; via #' so we can pin the lowest-level decode+validate behaviour without
;; widening the public surface.
(def ^:private malli-decode @#'rf.http.decode/malli-decode)

;; Sanity-pin: Malli really is resolvable on this test classpath. If a
;; future deps change drops the schemas test-dep, every schema test below
;; would silently degrade to the Malli-absent no-op fall-through
;; (`malli-decode` returns the parsed value un-coerced, un-validated) and
;; the coerce / validation-failure assertions would mislead. This guard
;; turns that into an explicit failure.
(deftest malli-is-on-the-test-classpath
  (testing "the schema-decode tests below require Malli to be
            resolvable; assert the precondition so a deps regression that
            removes it fails loudly rather than degrading to the no-op
            Malli-absent branch"
    (is (some? (requiring-resolve 'malli.core/decode)))
    (is (some? (requiring-resolve 'malli.core/validate)))
    (is (some? (requiring-resolve 'malli.transform/json-transformer)))))

;; ---- malli-decode: coerce success -----------------------------------------

(deftest malli-decode-coerces-with-json-transformer
  (testing "malli-decode runs the schema's decode with the
            JSON transformer, applying the canonical JSON coercions (the
            classic case is string→keyword and string→enum, since JSON
            has no keyword type) — proving the transformer arg is actually
            wired (a plain validate-only path would reject the string)
            (Spec 014 §Decoding 'the canonical form')"
    ;; The json-transformer coerces a JSON string into a keyword against a
    ;; :keyword schema, and into an enum member against an [:enum ...]
    ;; schema. This is the JSON-shaped coercion (numbers already arrive as
    ;; numbers from the JSON parse, so :int coercion is a no-op).
    (is (= :foo (malli-decode :keyword "foo"))
        "JSON string coerced to keyword via the json-transformer")
    (is (= :a (malli-decode [:enum :a :b] "a"))
        "JSON string coerced to an enum member via the json-transformer")
    (testing "a map schema coerces nested string values and keeps numbers as-is"
      (is (= {:id 7 :status :ok}
             (malli-decode [:map [:id :int] [:status :keyword]]
                           {:id 7 :status "ok"}))
          "string :status coerced to keyword; numeric :id kept (JSON
           already parsed it to a number)"))))

(deftest malli-decode-passes-through-already-valid-value
  (testing "a value that already matches the schema decodes to
            itself and validates clean"
    (is (= {:title "hello" :id 42}
           (malli-decode [:map [:title :string] [:id :int]]
                         {:title "hello" :id 42})))))

;; ---- malli-decode: validation failure -------------------------------------

(deftest malli-decode-throws-canonical-ex-info-on-validation-failure
  (testing "when the coerced value still fails the schema,
            malli-decode throws an ex-info carrying the canonical
            discriminator `:rf.error/id :rf.error/http-schema-validation-failed`
            (Spec 009) so the transport classifies it as
            :rf.http/decode-failure :schema-validation-failure? true"
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/http-schema-validation-failed"
                   ;; :int schema, value is a non-coercible string — the
                   ;; json-transformer can't turn \"notanumber\" into an int,
                   ;; so validate fails.
                   (malli-decode :int "notanumber")))
          d  (ex-data ex)]
      (is (= :rf.error/http-schema-validation-failed (:rf.error/id d))
          "carries the canonical discriminator the transport keys on")
      (is (= :no-recovery (:recovery d)))
      (is (= 'rf.http/decode-response-body (:where d)))
      (is (= :int (:schema d))
          "the offending schema rides the ex-data for diagnosis")
      (is (contains? d :value)
          "the rejected (decoded) value rides the ex-data for diagnosis"))))

(deftest malli-decode-map-schema-missing-required-key-throws
  (testing "a map missing a required key fails validation"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/http-schema-validation-failed"
          (malli-decode [:map [:id :int] [:name :string]]
                        {:id 1})))))

;; ---- decode-response-body — schema branch end-to-end ----------------------

(deftest decode-response-body-schema-success-parses-then-coerces
  (testing "passing a Malli schema as :decode JSON-parses the
            body-text then runs the schema decode+coerce, returning the
            coerced Clojure value (Spec 014 §Decoding). The JSON parse
            yields a number for id (no coercion needed) and a string for
            status which the json-transformer coerces to a keyword."
    (is (= {:title "hello" :id 42 :status :active}
           (rf.http.decode/decode-response-body
             {:body-text "{\"title\":\"hello\",\"id\":42,\"status\":\"active\"}"
              :headers   {"content-type" "application/json"}
              :decode    [:map [:title :string] [:id :int] [:status :keyword]]}))
        "string :status \"active\" is coerced to keyword :active by the schema decode")))

(deftest decode-response-body-schema-validation-failure-throws-canonical
  (testing "a body that parses as JSON but fails schema
            validation surfaces the canonical
            `:rf.error/http-schema-validation-failed` ex-info, which the
            transport maps to :rf.http/decode-failure
            :schema-validation-failure? true"
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/http-schema-validation-failed"
                   (rf.http.decode/decode-response-body
                     {:body-text "{\"id\":\"not-an-int\"}"
                      :headers   {"content-type" "application/json"}
                      :decode    [:map [:id :int]]})))]
      (is (= :rf.error/http-schema-validation-failed
             (:rf.error/id (ex-data ex)))))))

;; ---- keyword-cap threaded e2e through the schema branch --------------------
;;
;; The :rf.http/max-decoded-keys cap is tested at the JSON-reader layer
;; (http_json_test.clj); these pin it end-to-end through the decoder as a
;; thrown :too-many-keys. The schema branch is the critical path: it must
;; RE-RAISE the cap-throw rather than swallow it behind a Malli rejection.

(deftest decode-response-body-schema-branch-reraises-too-many-keys
  (testing "the schema branch threads
            :max-decoded-keys into json-parse and re-raises the
            `:rf.error/malformed-json :cause :too-many-keys` cap-throw
            rather than masking it behind a Malli rejection. This is the
            security-relevant signal the transport classifies as
            :rf.http/decode-failure."
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/malformed-json"
                   (rf.http.decode/decode-response-body
                     ;; three unique object keys, cap of 2 — the JSON
                     ;; reader throws :too-many-keys before any Malli work.
                     {:body-text "{\"a\":1,\"b\":2,\"c\":3}"
                      :headers   {"content-type" "application/json"}
                      :decode    [:map-of :keyword :int]
                      :max-decoded-keys 2})))
          d  (ex-data ex)]
      (is (= :rf.error/malformed-json (:rf.error/id d))
          "the malformed-json discriminator survives — NOT remapped to a
           schema-validation failure")
      (is (= :too-many-keys (:cause d))
          "the keyword-interning DoS cause is preserved end-to-end")
      (is (= 2 (:limit d))
          "the per-call cap is threaded through to the reader"))))

;; ---- malformed JSON under a schema :decode must NEVER fall
;; back to raw body-text ------------------------------------------------
;;
;; An ordinary JSON syntax error is UNTAGGED (only the keyword-cap overflow
;; carries `:rf.error/malformed-json`), so a schema-branch `json-parse`
;; catch that fell back to the raw `body-text` for any untagged throw would
;; send malformed JSON down that fallback as the common case. Malformed JSON
;; would then spuriously VALIDATE under a string-like schema (the raw text
;; just IS a string), and misclassify as a `:schema-validation-failure?`
;; under a map schema instead of a plain decode failure. Per Spec 014
;; §Decoding, schema decode must JSON-parse
;; first and classify a malformed 2xx payload as a decode failure — not a
;; degenerate "successful" decode, and not a schema-validation failure.

;; Genuinely-malformed JSON per Cheshire/Jackson (see
;; `cheshire-rejects-malformed-input-cleanly` in http_json_test.clj):
;; Jackson is tolerant of some shapes by design (trailing commas,
;; missing close-braces fall through to its end-of-stream handler
;; rather than throwing), so these pick inputs definitively rejected —
;; an invalid token and a misspelt literal.

(deftest decode-response-body-schema-branch-malformed-json-throws-not-string-schema
  (testing "a :string schema would happily validate the raw
            malformed body-text (it IS a string) under a raw-text
            fallback; it must instead propagate the raw JSON-parse exception
            (a Cheshire/Jackson `JsonParseException`, not an ex-info) so
            the caller classifies the response as :rf.http/decode-failure,
            NOT a successful string decode"
    (let [thrown (try (rf.http.decode/decode-response-body
                         {:body-text "tru" ; truncated `true` — invalid token
                          :headers   {"content-type" "application/json"}
                          :decode    :string})
                       ::no-throw
                       (catch Exception e e))]
      (is (not= ::no-throw thrown)
          "malformed JSON under a :string schema must throw, not decode
           to the raw text as if it were a valid string value")
      (is (not= :rf.error/http-schema-validation-failed (:rf.error/id (ex-data thrown)))
          "the throw must be the raw JSON-parse failure, not a (masking)
           schema-validation-failed — there is no valid value to fail
           validation against"))))

(deftest decode-response-body-schema-branch-malformed-json-throws-not-schema-validation-failure
  (testing "a :map schema over malformed JSON must surface as
            a plain decode failure (an unparseable body), NOT get
            misclassified as :schema-validation-failure? true (which would
            wrongly imply a well-formed-but-wrong-shaped payload)"
    (let [thrown (try (rf.http.decode/decode-response-body
                         {:body-text "{\"id\":nul}" ; misspelt `null`
                          :headers   {"content-type" "application/json"}
                          :decode    [:map [:id :int]]})
                       ::no-throw
                       (catch Exception e e))]
      (is (not= ::no-throw thrown)
          "malformed JSON under a :map schema must throw")
      (is (not= :rf.error/http-schema-validation-failed (:rf.error/id (ex-data thrown)))
          "must not be misclassified as a schema-validation failure — the
           body never parsed to a value in the first place"))))

(deftest decode-response-body-json-branch-also-reraises-too-many-keys
  (testing "the plain :json branch likewise threads the cap
            (the cap-throw originates in the reader, so :json surfaces it
            directly without a re-raise wrapper)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/malformed-json"
          (rf.http.decode/decode-response-body
            {:body-text "{\"a\":1,\"b\":2,\"c\":3}"
             :headers   {"content-type" "application/json"}
             :decode    :json
             :max-decoded-keys 2})))))

;; ---- +json vendor media types (RFC 6839 suffix) ---------------------------
;;
;; The JSON gate (`json-content-type?` for schema eligibility + `sniff-decoder`
;; for `:auto`) accepts subtype "json" OR the "+json" structured-syntax
;; suffix (RFC 6839 / IANA), parameter-stripped. A bare
;; `(str/includes? ct "application/json")` substring would wrongly reject
;; (schema path) or mis-sniff to :blob (:auto path) mainstream vendor JSON
;; types — application/vnd.api+json (JSON:API), application/ld+json
;; (JSON-LD), application/vnd.github+json — even though the body is valid JSON.

(deftest schema-decode-accepts-vendor-plus-json-media-types
  (testing "a schema :decode over a body whose Content-Type
            carries the RFC 6839 `+json` suffix (vnd.api+json, ld+json,
            vnd.github+json) decodes as JSON rather than being rejected
            as `:rf.error/http-schema-non-json-content-type`"
    (doseq [ct ["application/vnd.api+json"
                "application/ld+json"
                "application/vnd.github+json"
                "application/vnd.api+json; charset=utf-8"]]
      (is (= {:title "hello" :id 42}
             (rf.http.decode/decode-response-body
               {:body-text "{\"title\":\"hello\",\"id\":42}"
                :headers   {"content-type" ct}
                :decode    [:map [:title :string] [:id :int]]}))
          (str "vendor +json media type should decode as JSON: " ct)))))

(deftest schema-decode-still-rejects-genuine-non-json-content-type
  (testing "the present-non-JSON reject guard holds: a
            schema :decode over an application/edn / text/plain response
            raises the clear MIME-mismatch error (not a misleading
            Malli string-validation failure)"
    (doseq [ct ["application/edn" "text/plain" "application/xml"]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf.error/http-schema-non-json-content-type"
            (rf.http.decode/decode-response-body
              {:body-text "{\"title\":\"hello\"}"
               :headers   {"content-type" ct}
               :decode    [:map [:title :string]]}))
          (str "genuine non-JSON media type must still reject: " ct)))))

(deftest auto-sniff-resolves-plus-json-to-json
  (testing ":auto sniffing of a `+json` suffix Content-Type
            resolves to :json (parses the body) rather than mis-sniffing
            to :blob"
    (doseq [ct ["application/vnd.api+json"
                "application/ld+json"
                "application/vnd.github+json; charset=utf-8"]]
      (is (= {:ok true}
             (rf.http.decode/decode-response-body
               {:body-text        "{\"ok\":true}"
                :headers          {"content-type" ct}
                :decode           :auto}))
          (str "vendor +json media type should auto-sniff to :json: " ct)))))

(deftest json-media-type-predicate-edge-cases
  (testing "the JSON media-type predicate accepts json subtype
            + +json suffix (parameter-stripped) and rejects genuine non-JSON"
    (let [json-media-type? @#'rf.http.decode/json-media-type?]
      (is (true?  (json-media-type? "application/json")))
      (is (true?  (json-media-type? "application/json; charset=utf-8")))
      (is (true?  (json-media-type? "APPLICATION/JSON")))
      (is (true?  (json-media-type? "text/json")))
      (is (true?  (json-media-type? "application/vnd.api+json")))
      (is (true?  (json-media-type? "application/ld+json")))
      (is (true?  (json-media-type? "application/vnd.github+json; charset=utf-8")))
      (is (false? (json-media-type? "application/edn")))
      (is (false? (json-media-type? "text/plain")))
      (is (false? (json-media-type? "application/xml")))
      ;; a subtype that merely CONTAINS "json" but is not json / +json
      (is (false? (json-media-type? "application/jsonrequest")))
      (is (nil?   (json-media-type? nil))))))

;; ---- trace-capture helper -------------------------------------------------

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    ::http-decode-test-cap]
    (try
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (rf.trace.tooling/unregister-listener! cb-id)))))

;; ---- Malli-absent degradation warning -------------------------------------
;;
;; The "no silent fallback" contract per Spec 014 §JSON decoder hardening:
;; when a real `:decode` schema rides the request but Malli is NOT on the
;; classpath, the resolve delays fall to nil, schema validation is SKIPPED,
;; and the parsed value flows to `:accept` UNCHECKED. This must NOT be a
;; silent no-op: a one-shot `:rf.warning/http-malli-absent` trace fires so
;; the degraded path is observable. These tests pin the Malli-ABSENT
;; branch + its one-shot latch, so a regression that silences it (or
;; fires it per response, flooding the trace surface) fails.
;;
;; The Malli resolve vars are `defonce`d delays already realised WITH Malli
;; present on this test classpath (`malli-is-on-the-test-classpath` above
;; pins that), so the absent path can't be reached through the live
;; `decode-response-body` here. We exercise the contract at its source: reach
;; the three resolve delays + the one-shot latch via `#'`, rebind the delays
;; to `(delay nil)` (the classpath-absent shape) under `with-redefs`, reset
;; the latch, and assert the documented degradation behaviour directly. This
;; is deterministic and host-agnostic.

(def ^:private malli-decode-fn      @#'rf.http.decode/malli-decode-fn)
(def ^:private malli-transformer-fn @#'rf.http.decode/malli-transformer-fn)
(def ^:private malli-validate-fn    @#'rf.http.decode/malli-validate-fn)
(def ^:private malli-absent-warned? @#'rf.http.decode/malli-absent-warned?)

(defn- with-malli-absent
  "Run `body-fn` with the three Malli resolve delays rebound to the
  classpath-absent shape (`(delay nil)`) and the one-shot warn latch
  reset to false, so the Malli-absent degradation path is exercised
  deterministically regardless of what's actually on the classpath.
  Restores the latch afterwards."
  [body-fn]
  (let [prior-latch @malli-absent-warned?]
    (try
      (reset! malli-absent-warned? false)
      (with-redefs [rf.http.decode/malli-decode-fn      (delay nil)
                    rf.http.decode/malli-transformer-fn (delay nil)
                    rf.http.decode/malli-validate-fn    (delay nil)]
        (body-fn))
      (finally
        (reset! malli-absent-warned? prior-latch)))))

(deftest malli-decode-absent-returns-value-unvalidated
  (testing "when Malli is absent (decode AND validate both
            resolve to nil), malli-decode returns the parsed value
            UNCHANGED and does NOT throw — even for a value that WOULD
            fail the schema were Malli present. The degradation is a
            pass-through, not a rejection (Spec 014 §JSON decoder
            hardening: 'unchecked data flows to :accept')."
    (with-malli-absent
      (fn []
        ;; :int schema + a string value: with Malli present this throws
        ;; :rf.error/http-schema-validation-failed (see
        ;; malli-decode-throws-canonical-ex-info-on-validation-failure).
        ;; With Malli ABSENT the value must pass through verbatim.
        (is (= "notanumber" (malli-decode :int "notanumber"))
            "schema-violating value passes through untouched when Malli is absent")
        (is (= {:id 1} (malli-decode [:map [:id :int] [:name :string]] {:id 1}))
            "a map missing a required key also passes through (no validation runs)")))))

(deftest malli-decode-absent-emits-degradation-warning-with-schema
  (testing "the Malli-absent fall-through emits a
            `:rf.warning/http-malli-absent` trace carrying the offending
            schema + a human :reason sentence, so the dropped validation
            is observable rather than silent."
    (with-malli-absent
      (fn []
        (with-trace-capture
          (fn [captured]
            (malli-decode [:map [:id :int]] {:id 1})
            (let [warns (filter #(= :rf.warning/http-malli-absent (:operation %))
                                @captured)]
              (is (seq warns)
                  (str "expected a :rf.warning/http-malli-absent trace; captured: "
                       (pr-str (mapv :operation @captured))))
              (let [w (first warns)]
                (is (= :warning (:op-type w)))
                (is (= [:map [:id :int]] (get-in w [:tags :schema]))
                    "the offending schema rides the trace for diagnosis")
                (is (string? (get-in w [:tags :reason]))
                    "a human-readable :reason sentence explains the skipped validation")))))))))

(deftest malli-decode-absent-warning-is-one-shot-per-runtime
  (testing "the degradation warning fires AT MOST ONCE per
            runtime (the `malli-absent-warned?` compare-and-set! latch).
            A Malli-less app's degraded decode is steady-state; a
            per-response trace would flood the surface. Multiple decodes
            after the first must NOT re-emit."
    (with-malli-absent
      (fn []
        (with-trace-capture
          (fn [captured]
            ;; Three decodes back-to-back; only the first may warn.
            (malli-decode [:map [:id :int]] {:id 1})
            (malli-decode :keyword "foo")
            (malli-decode [:enum :a :b] "a")
            (let [warns (filter #(= :rf.warning/http-malli-absent (:operation %))
                                @captured)]
              (is (= 1 (count warns))
                  (str "the one-shot latch must collapse repeated Malli-absent "
                       "decodes to a single warning; saw " (count warns))))))))))
