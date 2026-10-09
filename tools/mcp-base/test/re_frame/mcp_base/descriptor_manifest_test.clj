(ns re-frame.mcp-base.descriptor-manifest-test
  "Tests for the shared tool-descriptor manifest serialiser and drift
  check that both MCP servers' generators call. The algorithm is
  platform-agnostic `.cljc`, pinned here on the JVM."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.descriptor-manifest :as rf.mcp-base.descriptor-manifest]))

(def ^:private sample-descriptors
  "Two descriptors in the registry shape both servers emit, covering every
  governed slot."
  [{:name        "beta"
    :description "Second tool."
    :inputSchema {:type "object"
                  :properties {:limit {:type "integer"}
                               :cursor {:type "string"}}}
    :outputSchema {:type "object"}
    :annotations  {:readOnlyHint true}
    :typicalTokens 600}
   {:name        "alpha"
    :description "First tool."
    :inputSchema {:type "object"
                  :properties {:event {:type "string"}}
                  :required ["event"]}
    :annotations  {:destructiveHint true :openWorldHint true}
    :typicalTokens 300}])

;; ---------------------------------------------------------------------------
;; Row projection
;; ---------------------------------------------------------------------------

(deftest descriptor->row-projects-stable-shape
  ;; Keys are stringified and sorted; absent optional slots render empty,
  ;; false or nil so every row has the same shape.
  (are [descriptor row] (= row (rf.mcp-base.descriptor-manifest/descriptor->row descriptor))
    (first sample-descriptors)
    {:name "beta" :description "Second tool." :input-keys ["cursor" "limit"] :gated-input-keys []
     :required [] :output? true :annotations ["readOnlyHint"] :typicalTokens 600}

    {:name "x" :description "d"
     :inputSchema {:type "object" :properties {:b {} :a {}} :required [:b "a"]}}
    {:name "x" :description "d" :input-keys ["a" "b"] :gated-input-keys []
     :required ["a" "b"] :output? false :annotations [] :typicalTokens nil}

    {:name "x" :description "d" :inputSchema {:type "object"}}
    {:name "x" :description "d" :input-keys [] :gated-input-keys []
     :required [] :output? false :annotations [] :typicalTokens nil}))

(deftest descriptor->row-rejects-required-not-subset-of-input-keys
  ;; :required and :input-keys come from independent descriptor slots, so a
  ;; required key missing from :properties is rejected rather than emitted
  ;; as a self-inconsistent row.
  (let [bad  {:name        "broken"
              :description "Names a required arg it does not declare."
              :inputSchema {:type "object"
                            :properties {:known {:type "string"}}
                            :required ["missing"]}}
        data (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                   (rf.mcp-base.descriptor-manifest/descriptor->row bad))))]
    (is (= {:tool "broken" :missing ["missing"] :input-keys ["known"]}
           (select-keys data [:tool :missing :input-keys])))))

(def ^:private gated-descriptor
  "story-mcp's shape: the raw descriptor carries `:include-sensitive`,
  which the default `tools/list` profile strips behind an operator gate."
  {:name        "preview-variant"
   :description "Surfaces a live app-db slice."
   :inputSchema {:type "object"
                 :properties {:variant-id       {:type "string"}
                              :max-tokens       {:type "integer"}
                              :include-sensitive {:type "boolean"}}
                 :required ["variant-id"]}
   :outputSchema {:type "object"}
   :annotations  {:readOnlyHint true}
   :typicalTokens 2000})

(deftest gated-keys-marks-the-gated-subset
  ;; :input-keys stays the full gate-open surface; :gated-input-keys is the
  ;; part the default profile strips, the gated set intersected with the
  ;; keys the tool really has. The manifest builder threads the set through.
  (is (= {:input-keys ["include-sensitive" "max-tokens" "variant-id"] :gated-input-keys ["include-sensitive"]}
         (select-keys (first (:tools (rf.mcp-base.descriptor-manifest/build-manifest
                                       :story-mcp [gated-descriptor] #{"include-sensitive"})))
                      [:input-keys :gated-input-keys])))
  (are [descriptor gated expected]
       (= expected (:gated-input-keys (rf.mcp-base.descriptor-manifest/descriptor->row descriptor gated)))
    (second sample-descriptors) #{"include-sensitive"} []
    gated-descriptor            #{}                    []
    gated-descriptor            nil                    []))

;; ---------------------------------------------------------------------------
;; Deterministic emission
;; ---------------------------------------------------------------------------

(deftest render-edn-round-trips-as-data
  ;; Every row slot, a nil :typicalTokens included, reads back as the value
  ;; rendered, and the rows are sorted by name.
  (let [m      (rf.mcp-base.descriptor-manifest/build-manifest
                 :test (conj sample-descriptors {:name "gamma" :description "d" :inputSchema {:type "object"}}))
        parsed (edn/read-string (rf.mcp-base.descriptor-manifest/render-edn m))]
    (is (= ["alpha" "beta" "gamma"] (mapv :name (:tools parsed))))
    (is (= m parsed))))

(deftest render-edn-one-row-per-line
  ;; One tool row per line keeps diffs surgical; LF endings keep the
  ;; committed file byte-identical on every host.
  (let [text (rf.mcp-base.descriptor-manifest/render-edn
               (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors))]
    (is (not (str/includes? text "\r")))
    (is (= 2 (count (filter #(str/includes? % "{:name ") (str/split-lines text)))))))

;; ---------------------------------------------------------------------------
;; Drift-check
;; ---------------------------------------------------------------------------

(deftest check-passes-when-in-sync
  (let [m   (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        edn (rf.mcp-base.descriptor-manifest/render-edn m)]
    (is (= {:ok? true :added [] :removed [] :changed []}
           (rf.mcp-base.descriptor-manifest/check m edn edn)))))

(deftest check-detects-missing-file
  (let [m (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)]
    (is (= {:ok? false :added ["alpha" "beta"] :removed [] :changed [] :missing-file? true}
           (rf.mcp-base.descriptor-manifest/check m (rf.mcp-base.descriptor-manifest/render-edn m) nil)))))

(deftest check-tolerates-crlf-committed
  ;; A CRLF working-tree checkout must not read as drift.
  (let [m  (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        lf (rf.mcp-base.descriptor-manifest/render-edn m)]
    (is (true? (:ok? (rf.mcp-base.descriptor-manifest/check m lf (str/replace lf "\n" "\r\n")))))))

;; ---------------------------------------------------------------------------
;; Drift-report formatting — the shared, pure diagnostic body. The two
;; server-specific strings are injected by each generator.
;; ---------------------------------------------------------------------------

(def ^:private wording
  {:regenerate-line   "Regenerate with: <server command>"
   :missing-file-line "DRIFT: <file> does not exist. Run: <server command>"})

(deftest governed-slots-is-every-row-slot-except-name
  ;; A row slot missing from governed-slots would escape :changed reporting.
  (is (= (disj (set (keys (rf.mcp-base.descriptor-manifest/descriptor->row (first sample-descriptors)))) :name)
         (set rf.mcp-base.descriptor-manifest/governed-slots))))

(deftest drift-report-lines-missing-file
  ;; A missing committed file reports every generated tool as added, under
  ;; the consumer's missing-file header.
  (let [res   {:ok? false :added ["alpha" "beta"] :removed [] :changed [] :missing-file? true}
        lines (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)]
    (is (= ["DRIFT: <file> does not exist. Run: <server command>"
            "Regenerate with: <server command>"
            "  Tools the registry has that the committed file lacks (new/renamed tool):"
            "    + alpha"
            "    + beta"]
           lines))))

(deftest drift-report-lines-changed-per-slot
  ;; A changed row prints the tool name, then one line per drifting
  ;; governed slot as `<slot>: <pr-str old> -> <pr-str new>`.
  (let [old   {:description "d" :input-keys ["event"] :gated-input-keys []
               :required ["event"] :output? false :annotations [] :typicalTokens 300}
        new   (assoc old :input-keys ["event" "force"] :typicalTokens 999)
        res   {:ok? false :added [] :removed []
               :changed [{:name "alpha" :old old :new new}]}
        lines (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Existing tools whose descriptor catalogue row changed (input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):"
            "    ~ alpha"
            "        input-keys: [\"event\"] -> [\"event\" \"force\"]"
            "        typicalTokens: 300 -> 999"]
           lines))))

(deftest drift-report-lines-structurally-broken-committed
  ;; An unparseable committed file has no readable rows, so every generated
  ;; tool reports as added under the differs header, with nothing changed.
  (let [m     (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        edn   (rf.mcp-base.descriptor-manifest/render-edn m)
        res   (rf.mcp-base.descriptor-manifest/check m edn "this is not { valid edn")
        lines (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Tools the registry has that the committed file lacks (new/renamed tool):"
            "    + alpha"
            "    + beta"]
           lines))))

(deftest drift-report-lines-malformed-fallback
  ;; Same tool set but the byte comparison failed (banner / meta drift):
  ;; the fallback hint fires instead of an empty body.
  (let [res   {:ok? false :added [] :removed [] :changed []}
        lines (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  (tool set identical; the committed file is structurally broken or its provenance banner/meta drifted — regenerate)"]
           lines))))

(deftest drift-report-lines-end-to-end-from-check
  ;; The formatter consumes a real `check` result: alpha gains an input
  ;; key and beta is dropped, reported in canonical block order.
  (let [committed-m   (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        committed-edn (rf.mcp-base.descriptor-manifest/render-edn committed-m)
        alpha+        (assoc-in (second sample-descriptors)
                                [:inputSchema :properties :force] {:type "boolean"})
        gen-m         (rf.mcp-base.descriptor-manifest/build-manifest :test [alpha+])
        gen-edn       (rf.mcp-base.descriptor-manifest/render-edn gen-m)
        res           (rf.mcp-base.descriptor-manifest/check gen-m gen-edn committed-edn)
        lines         (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Tools in the committed file the registry no longer has (removed/renamed tool):"
            "    - beta"
            "  Existing tools whose descriptor catalogue row changed (input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):"
            "    ~ alpha"
            "        input-keys: [\"event\"] -> [\"event\" \"force\"]"]
           lines))))
