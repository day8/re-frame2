(ns re-frame.mcp-base.descriptor-manifest-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [re-frame.mcp-base.descriptor-manifest :as rf.mcp-base.descriptor-manifest]))

(def ^:private sample-descriptors
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

(deftest descriptor->row-projects-stable-shape
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
  (let [bad  {:name        "broken"
              :inputSchema {:type "object"
                            :properties {:known {:type "string"}}
                            :required ["missing"]}}
        data (try
               (rf.mcp-base.descriptor-manifest/descriptor->row bad)
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:tool "broken" :missing ["missing"] :input-keys ["known"]}
           (select-keys data [:tool :missing :input-keys])))))

(def ^:private gated-descriptor
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
  (is (= {:input-keys ["include-sensitive" "max-tokens" "variant-id"] :gated-input-keys ["include-sensitive"]}
         (select-keys (first (:tools (rf.mcp-base.descriptor-manifest/build-manifest
                                       :story-mcp [gated-descriptor] #{"include-sensitive"})))
                      [:input-keys :gated-input-keys])))
  (are [descriptor gated expected]
       (= expected (:gated-input-keys (rf.mcp-base.descriptor-manifest/descriptor->row descriptor gated)))
    (second sample-descriptors) #{"include-sensitive"} []
    gated-descriptor            #{}                    []
    gated-descriptor            nil                    []))

(deftest render-edn-round-trips-as-data
  (let [m      (rf.mcp-base.descriptor-manifest/build-manifest
                 :test (conj sample-descriptors {:name "gamma" :description "d" :inputSchema {:type "object"}}))
        parsed (edn/read-string (rf.mcp-base.descriptor-manifest/render-edn m))]
    (is (= ["alpha" "beta" "gamma"] (mapv :name (:tools parsed))))
    (is (= m parsed))))

(deftest render-edn-one-row-per-line
  (let [text (rf.mcp-base.descriptor-manifest/render-edn
               (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors))]
    (is (not (str/includes? text "\r")))
    (is (= 2 (count (filter #(str/includes? % "{:name ") (str/split-lines text)))))))

(deftest check-tolerates-crlf-committed
  ;; A CRLF working-tree checkout must not read as drift.
  (let [m  (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        lf (rf.mcp-base.descriptor-manifest/render-edn m)]
    (is (= {:ok? true :added [] :removed [] :changed []}
           (rf.mcp-base.descriptor-manifest/check m lf (str/replace lf "\n" "\r\n"))))))

(def ^:private wording
  {:regenerate-line   "Regenerate with: <server command>"
   :missing-file-line "DRIFT: <file> does not exist. Run: <server command>"})

(deftest governed-slots-is-every-row-slot-except-name
  ;; A row slot missing from governed-slots would escape :changed reporting.
  (is (= (disj (set (keys (rf.mcp-base.descriptor-manifest/descriptor->row (first sample-descriptors)))) :name)
         (set rf.mcp-base.descriptor-manifest/governed-slots))))

(deftest drift-report-lines-missing-file
  (let [m   (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        res (rf.mcp-base.descriptor-manifest/check m (rf.mcp-base.descriptor-manifest/render-edn m) nil)]
    (is (= {:ok? false :added ["alpha" "beta"] :removed [] :changed [] :missing-file? true} res))
    (is (= ["DRIFT: <file> does not exist. Run: <server command>"
            "Regenerate with: <server command>"
            "  Tools the registry has that the committed file lacks (new/renamed tool):"
            "    + alpha"
            "    + beta"]
           (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)))))

(deftest drift-report-lines-structurally-broken-committed
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
  (let [res   {:ok? false :added [] :removed [] :changed []}
        lines (rf.mcp-base.descriptor-manifest/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  (tool set identical; the committed file is structurally broken or its provenance banner/meta drifted — regenerate)"]
           lines))))

(deftest drift-report-lines-end-to-end-from-check
  (let [committed-m   (rf.mcp-base.descriptor-manifest/build-manifest :test sample-descriptors)
        committed-edn (rf.mcp-base.descriptor-manifest/render-edn committed-m)
        alpha+        (-> (second sample-descriptors)
                          (assoc-in [:inputSchema :properties :force] {:type "boolean"})
                          (assoc :typicalTokens 999))
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
            "        input-keys: [\"event\"] -> [\"event\" \"force\"]"
            "        typicalTokens: 300 -> 999"]
           lines))))
