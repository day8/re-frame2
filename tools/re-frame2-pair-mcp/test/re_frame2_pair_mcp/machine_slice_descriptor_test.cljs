(ns re-frame2-pair-mcp.machine-slice-descriptor-test
  "EP-0015 drift gate for the `snapshot` tool's `:machines`-slice description.

  THE CONTRACT. The generated MCP catalogue is what clients and skills read.
  Machine snapshots live in the durable runtime-db partition, which fails
  closed to `:rf/redacted` off-box by default (snapshot.cljs
  `redact-runtime-db?`). The descriptor must reflect that posture so
  clients/tests treat machine `:data` (durable sensitive runtime state) as
  INSIDE EP-0015 projection — not as a raw pass-through.

  THE GATE. The published `snapshot` descriptor must (a) carry NO
  `redact-interceptor` wording and NOT claim the `:machines` slice passes
  through unchanged, and (b) describe the EP-0015 posture: the runtime-db
  `:machines` slice fails closed to `:rf/redacted` off-box by default and the
  trusted-local gate threads through projection rather than bypassing it. An
  edit that re-introduces the raw-pass-through claim trips this test.

  Behavioural redaction of the `:machines` slice itself (the server-side eval
  form's `redact-runtime-db?`) is covered in `sensitive_filter_test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.descriptors-data :as data]))

(defn- snapshot-description []
  (:description data/snapshot))

(deftest snapshot-descriptor-has-no-retired-redact-interceptor-wording
  (testing "the snapshot descriptor names no retired `redact-interceptor` and
            does not claim the :machines slice passes through unchanged"
    (let [d (snapshot-description)]
      (is (string? d) "snapshot descriptor carries a :description string")
      (is (not (str/includes? d "redact-interceptor"))
          "the retired EP-0015 `redact-interceptor` is not named")
      (is (not (str/includes? d "`:machines` slice passes through unchanged"))
          "the raw-pass-through claim for the :machines slice is gone")
      (is (not (str/includes? d "passes through unchanged"))
          "no raw-pass-through claim remains anywhere in the descriptor"))))

(deftest snapshot-descriptor-describes-ep0015-machine-posture
  (testing "the snapshot descriptor describes the EP-0015 fail-closed posture
            for the :machines runtime-db slice"
    (let [d (snapshot-description)]
      ;; the slice fails closed to the framework sentinel off-box
      (is (str/includes? d ":rf/redacted")
          "the :machines slice fail-closed sentinel is documented")
      (is (or (str/includes? d "FAILS CLOSED")
              (str/includes? d "fails closed"))
          "the fail-closed posture is named")
      ;; schema-first classification (no top-level :sensitive/:large key)
      (is (str/includes? d ":data-schema")
          "schema-first :data classification (:data-schema props) is referenced")
      ;; the trusted-local gate threads THROUGH projection, not around it
      (is (str/includes? d "include-sensitive")
          "the trusted-local opt-in knob is named")
      (is (or (str/includes? d "off-box")
              (str/includes? d "Off-box"))
          "the off-box egress posture is named"))))
