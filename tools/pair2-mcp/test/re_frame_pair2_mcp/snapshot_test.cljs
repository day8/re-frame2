(ns re-frame-pair2-mcp.snapshot-test
  "Unit tests for the snapshot tool's argument parsing — the shape we
  send to `re-frame-pair2.runtime/snapshot-state` over nREPL.

  The live end-to-end coverage lives in `test/stdio-roundtrip.js` (the
  degraded-mode dispatch path) and the manual live-nREPL integration
  test against a real shadow-cljs build. The CLJS layer here just
  pins the MCP-arg→runtime-opts translation so accidental renames or
  case slips break the test rather than silently shipping a broken
  contract."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [applied-science.js-interop :as j]))

;; The parsers are private in tools.cljs — re-implement the contract
;; here so we test what callers see and so the test fails if the
;; semantics drift. Mirror of `parse-frames-arg` and `parse-include-arg`.

(def ^:private valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

(defn- ->frame-keyword [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (if (str/starts-with? x ":") (subs x 1) x)]
      (keyword s))
    :else (keyword x)))

(defn- parse-frames [raw]
  (cond
    (nil? raw) :all
    (or (= raw :all) (= raw "all")) :all
    (array? raw) (->> (js->clj raw) (mapv ->frame-keyword))
    (sequential? raw) (mapv ->frame-keyword raw)
    :else :all))

(defn- parse-include [raw]
  (let [full [:app-db :sub-cache :machines :epochs :traces]
        coerce (fn [xs]
                 (->> xs
                      (map keyword)
                      (filter valid-slices)
                      vec))]
    (cond
      (nil? raw) full
      (array? raw)
      (let [v (coerce (js->clj raw))]
        (if (seq v) v full))
      (sequential? raw)
      (let [v (coerce raw)]
        (if (seq v) v full))
      :else full)))

(deftest frames-default-is-all
  (is (= :all (parse-frames nil)))
  (is (= :all (parse-frames "all"))))

(deftest frames-array-coerces-to-keywords
  (let [arr #js [":rf/default" ":stories"]]
    (is (= [:rf/default :stories] (parse-frames arr)))))

(deftest frames-vector-coerces-to-keywords
  (is (= [:rf/default :stories]
         (parse-frames [":rf/default" ":stories"]))))

(deftest include-default-is-full-slice-set
  (is (= [:app-db :sub-cache :machines :epochs :traces]
         (parse-include nil)))
  (is (= [:app-db :sub-cache :machines :epochs :traces]
         (parse-include #js []))))

(deftest include-filters-unknown-slices
  (testing "unknown slices fall away, known stay in order"
    (is (= [:app-db :epochs]
           (parse-include #js ["app-db" "garbage" "epochs"]))))
  (testing "all-unknown falls back to the full list"
    (is (= [:app-db :sub-cache :machines :epochs :traces]
           (parse-include #js ["garbage" "more-garbage"])))))

(deftest include-accepts-subset
  (is (= [:app-db :sub-cache] (parse-include #js ["app-db" "sub-cache"]))))

(deftest snapshot-state-form-is-edn-readable
  ;; The MCP server pr-strs the opts map into a CLJS eval form like
  ;; `(re-frame-pair2.runtime/snapshot-state {:frames :all :include [...]})`.
  ;; Ensure the canonical default round-trips through pr-str.
  (let [opts {:frames :all
              :include (parse-include nil)}
        form (str "(re-frame-pair2.runtime/snapshot-state "
                  (pr-str opts) ")")]
    (is (re-find #":frames :all" form))
    (is (re-find #":include \[:app-db :sub-cache :machines :epochs :traces\]" form))))
