(ns re-frame.mcp-base.descriptor-manifest-test
  "Tests for the shared MCP tool-descriptor manifest serialiser +
  drift-check (rf2-sofwv). The serialiser is consumed by BOTH MCP
  servers' generators; this corpus pins its determinism + diff
  semantics on the JVM (the algorithm is platform-agnostic `.cljc`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.descriptor-manifest :as dm]))

(def ^:private sample-descriptors
  "Two descriptors in the on-the-registry shape both servers emit —
  story-mcp lifts :outputSchema/:annotations via cond->, pair-mcp
  declares them per-tool, but the slot SHAPE is identical."
  [{:name        "beta"
    :description "Second tool."
    :inputSchema {:type "object"
                  :properties {:limit {:type "integer"}
                               :cursor {:type "string"}}}
    :outputSchema {:type "object"}
    :annotations  {:readOnlyHint true}}
   {:name        "alpha"
    :description "First tool."
    :inputSchema {:type "object" :properties {:event {:type "string"}}}
    :annotations  {:destructiveHint true :openWorldHint true}}])

;; ---------------------------------------------------------------------------
;; Row projection
;; ---------------------------------------------------------------------------

(deftest descriptor->row-projects-stable-shape
  (let [row (dm/descriptor->row (first sample-descriptors))]
    (is (= "beta" (:name row)))
    (is (= "Second tool." (:description row)))
    (is (= ["cursor" "limit"] (:input-keys row)) "input-keys sorted, stringified")
    (is (true? (:output? row)))
    (is (= ["readOnlyHint"] (:annotations row)))))

(deftest descriptor->row-handles-missing-optional-slots
  (let [row (dm/descriptor->row {:name "x" :description "d"
                                 :inputSchema {:type "object"}})]
    (is (= [] (:input-keys row)) "no :properties → empty input-keys")
    (is (false? (:output? row)) "no :outputSchema → output? false")
    (is (= [] (:annotations row)) "no :annotations → empty")))

(deftest build-rows-sorts-by-name
  (let [rows (dm/build-rows sample-descriptors)]
    (is (= ["alpha" "beta"] (mapv :name rows))
        "rows sorted by name regardless of input order")))

;; ---------------------------------------------------------------------------
;; Deterministic emission
;; ---------------------------------------------------------------------------

(deftest render-edn-is-byte-stable
  (let [m (dm/build-manifest :test sample-descriptors)
        a (dm/render-edn m)
        b (dm/render-edn m)]
    (is (= a b) "same input → byte-identical output")
    (is (not (re-find #"\r\n" a)) "no CRLF — LF-pinned")
    (is (re-find #"GENERATED" a) "carries the do-not-hand-edit banner")))

(deftest render-edn-round-trips-as-data
  (let [m      (dm/build-manifest :test sample-descriptors)
        parsed (edn/read-string (dm/render-edn m))]
    (is (= :test (-> parsed :meta :server)))
    (is (= 2 (-> parsed :meta :tool-count)))
    (is (= ["alpha" "beta"] (mapv :name (:tools parsed))))))

(deftest render-edn-one-row-per-line
  (let [m     (dm/build-manifest :test sample-descriptors)
        lines (str/split-lines (dm/render-edn m))
        rows  (filter #(str/includes? % "{:name ") lines)]
    (is (= 2 (count rows)) "one tool row per line — surgical diffs")))

;; ---------------------------------------------------------------------------
;; Drift-check
;; ---------------------------------------------------------------------------

(deftest check-passes-when-in-sync
  (let [m   (dm/build-manifest :test sample-descriptors)
        edn (dm/render-edn m)
        res (dm/check m edn edn)]
    (is (true? (:ok? res)))
    (is (empty? (:added res)))
    (is (empty? (:removed res)))
    (is (empty? (:changed res)) "an in-sync manifest reports no changed rows")))

(deftest check-detects-missing-file
  (let [m   (dm/build-manifest :test sample-descriptors)
        edn (dm/render-edn m)
        res (dm/check m edn nil)]
    (is (false? (:ok? res)))
    (is (true? (:missing-file? res)))
    (is (= ["alpha" "beta"] (:added res)))))

(deftest check-detects-added-tool
  (testing "a tool present in the generated manifest but not the committed file is :added"
    (let [committed-m   (dm/build-manifest :test [(second sample-descriptors)]) ; alpha only
          committed-edn (dm/render-edn committed-m)
          gen-m         (dm/build-manifest :test sample-descriptors)            ; alpha + beta
          gen-edn       (dm/render-edn gen-m)
          res           (dm/check gen-m gen-edn committed-edn)]
      (is (false? (:ok? res)))
      (is (= ["beta"] (:added res)) "beta is new vs the committed file")
      (is (empty? (:removed res))))))

(deftest check-detects-removed-tool
  (testing "a tool in the committed file the generator no longer produces is :removed"
    (let [committed-m   (dm/build-manifest :test sample-descriptors)            ; alpha + beta
          committed-edn (dm/render-edn committed-m)
          gen-m         (dm/build-manifest :test [(second sample-descriptors)]) ; alpha only
          gen-edn       (dm/render-edn gen-m)
          res           (dm/check gen-m gen-edn committed-edn)]
      (is (false? (:ok? res)))
      (is (empty? (:added res)))
      (is (= ["beta"] (:removed res)) "beta was dropped"))))

(deftest check-detects-changed-existing-tool
  ;; rf2-y3qpv: when an EXISTING tool's catalogue row drifts (here alpha
  ;; gains an input key) the identity sets stay empty — neither :added
  ;; nor :removed names it. The CI guard previously went red with no
  ;; row-level identity of WHAT changed, forcing a manual whole-manifest
  ;; diff. `:changed` now names the drifted tool and carries old/new rows.
  (testing "an existing tool that gains an input key is :changed, not :added/:removed"
    (let [committed-m   (dm/build-manifest :test sample-descriptors)
          committed-edn (dm/render-edn committed-m)
          ;; alpha gains a :force input property; beta is untouched.
          alpha+        (assoc-in (second sample-descriptors)
                                  [:inputSchema :properties :force]
                                  {:type "boolean"})
          gen-m         (dm/build-manifest :test [(first sample-descriptors) alpha+])
          gen-edn       (dm/render-edn gen-m)
          res           (dm/check gen-m gen-edn committed-edn)]
      (is (false? (:ok? res)))
      (is (= [] (:added res)) "no tool entered the catalogue")
      (is (= [] (:removed res)) "no tool left the catalogue")
      (is (= ["alpha"] (mapv :name (:changed res)))
          "alpha's row drifted; it is named in :changed")
      (let [{:keys [old new]} (first (:changed res))]
        (is (= ["event"] (:input-keys old)) "old row carries the committed input-keys")
        (is (= ["event" "force"] (:input-keys new))
            "new row carries the regenerated input-keys — the maintainer sees the delta")))))

(deftest check-changed-detects-each-drifting-row-shape
  ;; The four catalogue-surface slots the manifest governs each trip
  ;; :changed in isolation (description / output? / annotations, plus the
  ;; input-keys case above). One row mutated per case; beta untouched.
  (let [base (second sample-descriptors)] ; alpha
    (doseq [[label mutate] [["description" #(assoc % :description "Changed prose.")]
                            ["output?"     #(assoc % :outputSchema {:type "object"})]
                            ["annotations" #(assoc-in % [:annotations :readOnlyHint] true)]]]
      (testing (str "a changed :" label " trips :changed")
        (let [committed-m   (dm/build-manifest :test sample-descriptors)
              committed-edn (dm/render-edn committed-m)
              gen-m         (dm/build-manifest :test [(first sample-descriptors) (mutate base)])
              gen-edn       (dm/render-edn gen-m)
              res           (dm/check gen-m gen-edn committed-edn)]
          (is (false? (:ok? res)))
          (is (= [] (:added res)))
          (is (= [] (:removed res)))
          (is (= ["alpha"] (mapv :name (:changed res)))))))))

(deftest check-tolerates-crlf-committed
  (testing "a CRLF working-tree checkout does not trip a spurious drift"
    (let [m       (dm/build-manifest :test sample-descriptors)
          lf-edn  (dm/render-edn m)
          crlf    (str/replace lf-edn "\n" "\r\n")
          res     (dm/check m lf-edn crlf)]
      (is (true? (:ok? res)) "LF-normalised comparison ignores line-ending style"))))
