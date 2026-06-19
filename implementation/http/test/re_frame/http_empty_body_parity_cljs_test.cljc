(ns re-frame.http-empty-body-parity-cljs-test
  "Cross-host regression for the managed-HTTP decode altitude (Spec 014
  §Decoding). Named `*-cljs-test.cljc` so BOTH cognitect.test-runner (JVM
  `clojure -M:test`) and shadow-cljs (`npm run test:cljs`, `cljs-test$`
  ns-regexp) discover it — the contracts here are host-symmetric BY
  DESIGN (the whole point of these beads is that the two hosts must
  agree), so a single source asserted on both runtimes is the right
  shape.

  rf2-upexd.1 (P1, cross-host parity — the oyw04 contract):
    An empty / whitespace-only 2xx JSON body is a NORMAL HTTP outcome
    (the common 200/204-shaped PUT/DELETE/POST-with-no-content reply),
    NOT a programmer error. Before the fix the two hosts diverged at the
    managed-cascade decode altitude: the JVM Cheshire reader surfaced an
    empty document as end-of-stream → nil → `{:kind :success :value nil}`,
    while CLJS `js/JSON.parse(\"\")` THREW → `:rf.http/decode-failure`.
    The helper-level divergence stays deliberately pinned at the
    `util-json` unit altitude (util_json_*_test); the MANAGED path is now
    normalised in `decode-response-body` so both hosts classify an empty
    2xx JSON body identically as nil.

  rf2-upexd.2 (P3, spec-divergence):
    The Malli `:decode` schema path is JSON-only. A response that DECLARES
    a present, non-JSON Content-Type under a schema `:decode` is rejected
    up-front with a `:rf.error/http-schema-non-json-content-type` ex-info
    (the transport classifies it as `:rf.http/decode-failure`) rather than
    silently JSON-parsing a non-JSON body and surfacing a confusing
    schema-validation failure that masked the real MIME mismatch."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.http.decode :as decode]))

;; ---------------------------------------------------------------------------
;; rf2-upexd.1 — empty / whitespace-only 2xx JSON body → nil on BOTH hosts
;; ---------------------------------------------------------------------------

(deftest empty-2xx-json-body-decodes-to-nil-cross-host
  (testing "rf2-upexd.1 — an EMPTY 2xx `:decode :json` body decodes to nil
            (NOT a thrown decode-failure) on both the JVM and CLJS hosts.
            This is the headline cross-host parity regression: pre-fix,
            CLJS `js/JSON.parse(\"\")` threw while the JVM Cheshire reader
            returned nil. `decode-response-body` now short-circuits the
            empty/blank case before the host JSON reader is reached, so
            the value is nil identically — `run-accept`'s default then
            yields `{:ok nil}` → reply `{:kind :success :value nil}`."
    (is (nil? (decode/decode-response-body
                {:body-text        ""
                 :headers          {"content-type" "application/json"}
                 :decode           :json}))
        "empty 2xx JSON body must decode to nil on the running host")))

(deftest whitespace-only-2xx-json-body-decodes-to-nil-cross-host
  (testing "rf2-upexd.1 — a WHITESPACE-ONLY 2xx `:decode :json` body
            (spaces / newline / tab) likewise decodes to nil on both hosts.
            Whitespace-only is not a valid JSON document per RFC 8259, but
            it is the same empty-success-envelope outcome as `\"\"` — both
            classify as nil, not decode-failure."
    (doseq [s ["   " "\n" "\t" "  \n\t  "]]
      (is (nil? (decode/decode-response-body
                  {:body-text        s
                   :headers          {"content-type" "application/json"}
                   :decode           :json}))
          (str "whitespace-only body " (pr-str s) " must decode to nil")))))

(deftest empty-2xx-auto-json-body-decodes-to-nil-cross-host
  (testing "rf2-upexd.1 — the parity also holds when `:decode` is omitted
            and `:auto` resolves to :json via the `application/json`
            Content-Type (the exact repro from the bead: a 200 with an
            empty body and a JSON content-type). nil on both hosts."
    (is (nil? (decode/decode-response-body
                {:body-text        ""
                 :headers          {"content-type" "application/json; charset=utf-8"}
                 :decode           :auto}))
        "auto-sniffed empty 2xx JSON body must decode to nil")))

(deftest non-empty-json-body-still-parses-cross-host
  (testing "rf2-upexd.1 — the empty-body guard is surgical: a NON-empty
            JSON body still parses normally on both hosts (no regression
            to the happy path)."
    (is (= {:ok true}
           (decode/decode-response-body
             {:body-text        "{\"ok\":true}"
              :headers          {"content-type" "application/json"}
              :decode           :json}))
        "a real JSON body must still parse")))

(deftest empty-2xx-schema-body-parses-to-nil-then-schema-decides-cross-host
  (testing "rf2-upexd.1 — under a Malli schema `:decode`, an empty 2xx
            body parses to nil on BOTH hosts (rather than CLJS throwing in
            the reader). The schema THEN decides whether nil is acceptable:
            a `[:maybe ...]` schema accepts nil → success; this is a
            host-symmetric outcome, not a per-host parse divergence. (Malli
            may be absent on a given test classpath — when absent, the
            decode is a pass-through and nil flows straight out. Either way
            the value is nil cross-host, which is the parity contract under
            test.)"
    (is (nil? (decode/decode-response-body
                {:body-text        ""
                 :headers          {"content-type" "application/json"}
                 :decode           [:maybe [:map [:id :int]]]}))
        "empty 2xx body under a [:maybe ...] schema decodes to nil cross-host")))

;; ---------------------------------------------------------------------------
;; rf2-upexd.2 — schema path is JSON-only; non-JSON Content-Type rejected
;; ---------------------------------------------------------------------------

(defn- thrown-id
  "Run `f` capturing any throw cross-host; return the `:rf.error/id` of the
  ex-data, or ::no-throw when nothing was thrown."
  [f]
  (try (f) ::no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (:rf.error/id (#?(:clj ex-data :cljs ex-data) e)))))

(deftest schema-rejects-non-json-content-type-cross-host
  (testing "rf2-upexd.2 — a schema `:decode` over a response that declares
            a NON-JSON Content-Type (e.g. application/edn) is rejected with
            `:rf.error/http-schema-non-json-content-type` on both hosts,
            rather than silently JSON-parsing the EDN body and surfacing a
            confusing schema-validation failure. The schema path is
            JSON-only (it wires Malli's json-transformer)."
    (is (= :rf.error/http-schema-non-json-content-type
           (thrown-id
             #(decode/decode-response-body
                {:body-text        "{:a 1}"            ;; valid EDN, NOT JSON
                 :headers          {"content-type" "application/edn"}
                 :decode           [:map [:a :int]]})))
        "non-JSON declared MIME under a schema must throw the tagged error")))

(deftest schema-rejects-text-plain-content-type-cross-host
  (testing "rf2-upexd.2 — `text/plain` under a schema `:decode` is also a
            non-JSON MIME and is rejected up-front on both hosts."
    (is (= :rf.error/http-schema-non-json-content-type
           (thrown-id
             #(decode/decode-response-body
                {:body-text        "hello"
                 :headers          {"content-type" "text/plain"}
                 :decode           [:map [:a :int]]})))
        "text/plain under a schema must throw the tagged error")))

(deftest schema-tolerates-absent-content-type-cross-host
  (testing "rf2-upexd.2 — a MISSING/absent Content-Type stays JSON-eligible
            under a schema `:decode` (many JSON APIs omit the header); the
            body is JSON-parsed as before, NOT rejected. Only a PRESENT
            non-JSON MIME is rejected. (Asserts only that the non-JSON
            rejection does NOT fire — the actual decode result depends on
            whether Malli is on the host's test classpath.)"
    (is (not= :rf.error/http-schema-non-json-content-type
              (thrown-id
                #(decode/decode-response-body
                   {:body-text        "{\"a\":1}"
                    :headers          {}                 ;; no content-type
                    :decode           [:map [:a :int]]})))
        "absent Content-Type must NOT trigger the non-JSON schema rejection")))

(deftest schema-accepts-json-content-type-cross-host
  (testing "rf2-upexd.2 — a declared `application/json` Content-Type under a
            schema `:decode` is accepted (the JSON happy path) — the
            non-JSON rejection does not fire."
    (is (not= :rf.error/http-schema-non-json-content-type
              (thrown-id
                #(decode/decode-response-body
                   {:body-text        "{\"a\":1}"
                    :headers          {"content-type" "application/json"}
                    :decode           [:map [:a :int]]})))
        "application/json under a schema must NOT trigger the non-JSON rejection")))
