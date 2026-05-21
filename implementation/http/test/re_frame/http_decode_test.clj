(ns re-frame.http-decode-test
  "Direct unit coverage for the response-body decode pipeline in
  `re-frame.http-decode` (rf2-ohwgm; follow-on from the http test-coverage
  audit `ai/findings/2026-05-21-testcov-http.md`).

  Per Spec 014 §Decoding / §`:auto`, schema-driven decode is the
  canonical form. Before this file the entire Malli decode + coerce +
  validate path (`malli-decode`, the schema branch of
  `decode-response-body`) had ZERO test coverage, the keyword-cap was
  never threaded through the decoder end-to-end as a thrown
  `:too-many-keys`, and the `:rf.warning/decode-defaulted` dev emission
  (Spec 014 §`:auto`) was never asserted to actually fire.

  These fns are pure / host-agnostic, so they belong on the fast JVM
  `clojure -M:test` layer. Malli is on the http test classpath via the
  `day8/re-frame2-schemas` test-dep (its transitive `metosin/malli`),
  so `requiring-resolve` of `malli.core/decode` / `malli.core/validate`
  / `malli.transform/json-transformer` succeeds at runtime — the
  schema branch exercises the real Malli decode + coerce + validate."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.http-decode :as decode]
            [re-frame.trace :as trace]))

;; `decode-response-body` is public; `malli-decode` is private — reach it
;; via #' so we can pin the lowest-level decode+validate behaviour without
;; widening the public surface.
(def ^:private malli-decode @#'decode/malli-decode)

;; Sanity-pin: Malli really is resolvable on this test classpath. If a
;; future deps change drops the schemas test-dep, every schema test below
;; would silently degrade to the Malli-absent no-op fall-through
;; (`malli-decode` returns the parsed value un-coerced, un-validated) and
;; the coerce / validation-failure assertions would mislead. This guard
;; turns that into an explicit failure.
(deftest malli-is-on-the-test-classpath
  (testing "rf2-ohwgm — the schema-decode tests below require Malli to be
            resolvable; assert the precondition so a deps regression that
            removes it fails loudly rather than degrading to the no-op
            Malli-absent branch"
    (is (some? (requiring-resolve 'malli.core/decode)))
    (is (some? (requiring-resolve 'malli.core/validate)))
    (is (some? (requiring-resolve 'malli.transform/json-transformer)))))

;; ---- G1: malli-decode — coerce success ------------------------------------

(deftest malli-decode-coerces-with-json-transformer
  (testing "rf2-ohwgm — malli-decode runs the schema's decode with the
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
  (testing "rf2-ohwgm — a value that already matches the schema decodes to
            itself and validates clean"
    (is (= {:title "hello" :id 42}
           (malli-decode [:map [:title :string] [:id :int]]
                         {:title "hello" :id 42})))))

;; ---- G1: malli-decode — validation failure --------------------------------

(deftest malli-decode-throws-canonical-ex-info-on-validation-failure
  (testing "rf2-ohwgm — when the coerced value still fails the schema,
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
  (testing "rf2-ohwgm — a map missing a required key fails validation"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/http-schema-validation-failed"
          (malli-decode [:map [:id :int] [:name :string]]
                        {:id 1})))))

;; ---- G1: decode-response-body — schema branch end-to-end ------------------

(deftest decode-response-body-schema-success-parses-then-coerces
  (testing "rf2-ohwgm — passing a Malli schema as :decode JSON-parses the
            body-text then runs the schema decode+coerce, returning the
            coerced Clojure value (Spec 014 §Decoding). The JSON parse
            yields a number for id (no coercion needed) and a string for
            status which the json-transformer coerces to a keyword."
    (is (= {:title "hello" :id 42 :status :active}
           (decode/decode-response-body
             {:body-text "{\"title\":\"hello\",\"id\":42,\"status\":\"active\"}"
              :headers   {"content-type" "application/json"}
              :decode    [:map [:title :string] [:id :int] [:status :keyword]]
              :decode-supplied? true}))
        "string :status \"active\" is coerced to keyword :active by the schema decode")))

(deftest decode-response-body-schema-validation-failure-throws-canonical
  (testing "rf2-ohwgm — a body that parses as JSON but fails schema
            validation surfaces the canonical
            `:rf.error/http-schema-validation-failed` ex-info, which the
            transport maps to :rf.http/decode-failure
            :schema-validation-failure? true (http_transport.cljc:721-726)"
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/http-schema-validation-failed"
                   (decode/decode-response-body
                     {:body-text "{\"id\":\"not-an-int\"}"
                      :headers   {"content-type" "application/json"}
                      :decode    [:map [:id :int]]
                      :decode-supplied? true})))]
      (is (= :rf.error/http-schema-validation-failed
             (:rf.error/id (ex-data ex)))))))

;; ---- G1: keyword-cap threaded e2e through the schema branch ----------------
;;
;; The audit (G1) calls out that the :rf.http/max-decoded-keys cap is
;; tested at the JSON-reader layer (util_json_test.clj:33) but NOT threaded
;; end-to-end through the decoder as a thrown :too-many-keys. The schema
;; branch is the critical path: per rf2-wu1n5 it must RE-RAISE the
;; cap-throw rather than swallow it behind a Malli rejection.

(deftest decode-response-body-schema-branch-reraises-too-many-keys
  (testing "rf2-ohwgm / rf2-wu1n5 — the schema branch threads
            :max-decoded-keys into json-parse and re-raises the
            `:rf.error/malformed-json :cause :too-many-keys` cap-throw
            rather than masking it behind a Malli rejection. This is the
            security-relevant signal the transport classifies as
            :rf.http/decode-failure."
    (let [ex (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #":rf.error/malformed-json"
                   (decode/decode-response-body
                     ;; three unique object keys, cap of 2 — the JSON
                     ;; reader throws :too-many-keys before any Malli work.
                     {:body-text "{\"a\":1,\"b\":2,\"c\":3}"
                      :headers   {"content-type" "application/json"}
                      :decode    [:map-of :keyword :int]
                      :decode-supplied? true
                      :max-decoded-keys 2})))
          d  (ex-data ex)]
      (is (= :rf.error/malformed-json (:rf.error/id d))
          "the malformed-json discriminator survives — NOT remapped to a
           schema-validation failure")
      (is (= :too-many-keys (:cause d))
          "the keyword-interning DoS cause is preserved end-to-end")
      (is (= 2 (:limit d))
          "the per-call cap is threaded through to the reader"))))

(deftest decode-response-body-json-branch-also-reraises-too-many-keys
  (testing "rf2-ohwgm — the plain :json branch likewise threads the cap
            (the cap-throw originates in the reader, so :json surfaces it
            directly without a re-raise wrapper)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf.error/malformed-json"
          (decode/decode-response-body
            {:body-text "{\"a\":1,\"b\":2,\"c\":3}"
             :headers   {"content-type" "application/json"}
             :decode    :json
             :decode-supplied? true
             :max-decoded-keys 2})))))

;; ---- G4: :rf.warning/decode-defaulted dev emission ------------------------
;;
;; Spec 014 §`:auto`. The warning fires when the user did NOT supply
;; `:decode` and the decoder fell back to auto-sniffing. The audit (G4)
;; flags that only the prod-elision *absence* test referenced it; no
;; dev-mode test asserted it actually fires with its tags.

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    ::http-decode-test-cap]
    (try
      (trace/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (trace/unregister-listener! cb-id)))))

(deftest decode-defaulted-warning-fires-when-decode-omitted
  (testing "rf2-ohwgm — when :decode is omitted (`:decode-supplied? false`)
            and the decoder falls back to :auto, `decode-response-body`
            emits `:rf.warning/decode-defaulted` carrying the resolved
            decoder + content-type tags (Spec 014 §`:auto`)"
    (with-trace-capture
      (fn [captured]
        (let [v (decode/decode-response-body
                  {:body-text        "{\"ok\":true}"
                   :headers          {"content-type" "application/json"}
                   :decode           nil
                   :decode-supplied? false
                   :request-id       :req-1
                   :url              "https://example.test/x"})
              warns (filter #(= :rf.warning/decode-defaulted (:operation %))
                            @captured)]
          (is (= {:ok true} v)
              "the auto-sniffed :json path still decodes the body")
          (is (seq warns)
              (str "expected a :rf.warning/decode-defaulted trace; captured: "
                   (pr-str (mapv :operation @captured))))
          (let [w (first warns)]
            (is (= :warning (:op-type w)))
            (let [tags (:tags w)]
              (is (= :json (:resolved-decoder tags))
                  "the resolved decoder (sniffed from the JSON content-type) rides the tags")
              (is (= "application/json" (:content-type tags))
                  "the response content-type rides the tags")
              (is (= :req-1 (:request-id tags))
                  "the request-id rides the tags for correlation")
              (is (= "https://example.test/x" (:url tags))
                  "the (privacy-prepared) url rides the tags"))))))))

(deftest decode-defaulted-warning-not-fired-when-decode-supplied
  (testing "rf2-ohwgm — when the user explicitly supplies :decode the
            warning is suppressed (the default-to-auto signal only fires
            on omission)"
    (with-trace-capture
      (fn [captured]
        (decode/decode-response-body
          {:body-text        "{\"ok\":true}"
           :headers          {"content-type" "application/json"}
           :decode           :json
           :decode-supplied? true
           :request-id       :req-2
           :url              "https://example.test/y"})
        (is (empty? (filter #(= :rf.warning/decode-defaulted (:operation %))
                            @captured))
            "an explicit :decode must NOT emit decode-defaulted")))))

(deftest decode-defaulted-warning-resolved-decoder-text-for-text-ct
  (testing "rf2-ohwgm — the :resolved-decoder tag reflects the sniffed
            decoder, e.g. :text for a text/* content-type"
    (with-trace-capture
      (fn [captured]
        (decode/decode-response-body
          {:body-text        "plain words"
           :headers          {"content-type" "text/plain"}
           :decode           :auto
           :decode-supplied? false
           :request-id       :req-3
           :url              "https://example.test/z"})
        (let [w (first (filter #(= :rf.warning/decode-defaulted (:operation %))
                               @captured))]
          (is (= :text (get-in w [:tags :resolved-decoder]))
              "text/* content-type sniffs to :text"))))))
