(ns re-frame.mcp-base.diff-encode-test
  "Tests for the path-keyed structural diff used at the MCP wire
  boundary (rf2-1wdzp, shared across the triplet via rf2-vw4sq)."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [re-frame.mcp-base.diff-encode :as de]))

;; ---------------------------------------------------------------------------
;; collect-patches — direct cases.
;; ---------------------------------------------------------------------------

(deftest collect-patches-empty-when-equal
  (is (= [] (de/collect-patches {:a 1} {:a 1} []))))

(deftest collect-patches-handles-added-key
  (is (= [[[:b] :assoc 2]]
         (de/collect-patches {:a 1} {:a 1 :b 2} []))))

(deftest collect-patches-handles-removed-key
  (is (= [[[:b] :dissoc]]
         (de/collect-patches {:a 1 :b 2} {:a 1} []))))

(deftest collect-patches-handles-changed-leaf
  (is (= [[[:a] :assoc 3]]
         (de/collect-patches {:a 1} {:a 3} []))))

(deftest collect-patches-recurses-into-nested-maps
  (let [a {:user {:name "ada" :age 30}}
        b {:user {:name "ada" :age 31}}]
    (is (= [[[:user :age] :assoc 31]]
           (de/collect-patches a b [])))))

(deftest collect-patches-leaf-replacement-for-shape-mismatch
  (is (= [[[] :assoc [1 2 3]]]
         (de/collect-patches {:a 1} [1 2 3] [])))
  (is (= [[[:a] :assoc [1 2 3]]]
         (de/collect-patches {:a {:b 1}} {:a [1 2 3]} []))))

;; ---------------------------------------------------------------------------
;; apply-patches — round-trips collect-patches.
;; ---------------------------------------------------------------------------

(deftest apply-patches-reverses-collect-patches
  (testing "trivial"
    (let [a {:a 1}
          b {:a 1 :b 2}
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p)))))
  (testing "nested + delete + change"
    (let [a {:user {:name "ada" :age 30} :session :idle}
          b {:user {:name "ada" :age 31 :role :admin}}
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p)))))
  (testing "shape change at root"
    (let [a {:a 1}
          b [1 2 3]
          p (de/collect-patches a b [])]
      (is (= b (de/apply-patches a p))))))

(deftest apply-patches-dissoc-at-root-via-direct-path
  (is (= {:a 1} (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))

;; ---------------------------------------------------------------------------
;; diff-encode-db-after / decode-db-after — round-trip.
;; ---------------------------------------------------------------------------

(deftest diff-encode-db-after-emits-diff-shape
  (let [epoch    {:db-before {:a 1 :b 2}
                  :db-after  {:a 1 :b 3}}
        encoded  (de/diff-encode-db-after epoch)]
    (is (= :db-before (get-in encoded [:db-after :rf.mcp/diff-from])))
    (is (vector? (get-in encoded [:db-after :patches])))
    (is (= [[[:b] :assoc 3]] (get-in encoded [:db-after :patches])))))

(deftest diff-encode-then-decode-restores-original
  (let [epoch   {:db-before {:user {:name "ada" :age 30}
                             :session :idle}
                 :db-after  {:user {:name "ada" :age 31 :role :admin}}
                 :event     [:user/birthday]}
        encoded (de/diff-encode-db-after epoch)
        decoded (de/decode-db-after encoded)]
    (is (= epoch decoded))))

(deftest diff-encode-db-after-passes-through-when-missing-halves
  (testing "missing db-before"
    (let [epoch {:db-after {:x 1}}]
      (is (= epoch (de/diff-encode-db-after epoch)))))
  (testing "missing db-after"
    (let [epoch {:db-before {:x 1}}]
      (is (= epoch (de/diff-encode-db-after epoch)))))
  (testing "non-map epoch"
    (is (= [1 2 3] (de/diff-encode-db-after [1 2 3])))))

(deftest decode-db-after-passes-through-when-not-a-diff
  ;; Already-full epoch (no marker) decodes to itself.
  (let [epoch {:db-before {:a 1} :db-after {:a 1 :b 2}}]
    (is (= epoch (de/decode-db-after epoch)))))

;; ---------------------------------------------------------------------------
;; diff-encode-epochs — vector form + mode toggle.
;; ---------------------------------------------------------------------------

(deftest diff-encode-epochs-diff-mode-encodes-each-record
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}
                {:db-before {:b 1} :db-after {:b 2}}]
        out    (de/diff-encode-epochs epochs :diff)]
    (is (= 2 (count out)))
    (is (= :db-before (get-in (first out) [:db-after :rf.mcp/diff-from])))
    (is (= :db-before (get-in (second out) [:db-after :rf.mcp/diff-from])))))

(deftest diff-encode-epochs-full-mode-is-passthrough
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}]]
    (is (= epochs (de/diff-encode-epochs epochs :full)))))

(deftest diff-encode-epochs-each-record-self-contained
  ;; The slice can be reordered, paginated, or filtered without
  ;; breaking decode — every record carries its own :db-before so it
  ;; round-trips standalone.
  (let [epochs [{:db-before {:a 1} :db-after {:a 2}}
                {:db-before {:b 1} :db-after {:b 2}}]
        out    (de/diff-encode-epochs epochs :diff)
        reversed (vec (reverse out))]
    (doseq [enc reversed]
      (let [dec (de/decode-db-after enc)]
        (is (= (dissoc enc :db-after :patches)
               (dissoc dec :db-after))
            "non-:db-after fields unchanged on decode")))))

;; ---------------------------------------------------------------------------
;; Patch grammar — Malli schema pin (rf2-rgg7d).
;;
;; The schema is published as `de/patch-schema` (single tuple) and
;; `de/patches-schema` (sequential of tuples). The encoder boundary
;; (`diff-encode-db-after`) validates emissions against it and throws
;; `:rf.error/bad-diff-patches` ex-info on mismatch.
;;
;; These tests pin the grammar with positive and negative cases so a
;; future encoder refactor that drifts the tuple shape — and a future
;; consumer (causa-mcp's Malli-based decoder per spec/004-Wire-Pipeline.md,
;; the cross-MCP wire-vocab conformance test) that re-states the
;; grammar — both trip this gate before reaching the wire.
;; ---------------------------------------------------------------------------

(deftest patch-schema-accepts-well-formed-tuples
  (testing "the two canonical 2- and 3-element tuple shapes"
    (is (true? (m/validate de/patch-schema [[:a] :assoc 1]))
        "assoc with scalar value")
    (is (true? (m/validate de/patch-schema [[:a :b] :assoc {:x 1}]))
        "assoc with map value")
    (is (true? (m/validate de/patch-schema [[] :assoc {:whole :db}]))
        "assoc at root (empty path)")
    (is (true? (m/validate de/patch-schema [[:a] :dissoc]))
        "dissoc at depth 1")
    (is (true? (m/validate de/patch-schema [[:a :b :c] :dissoc]))
        "dissoc at depth 3"))
  (testing "values can be of any type"
    (is (true? (m/validate de/patch-schema [[:a] :assoc nil]))
        "assoc nil leaf")
    (is (true? (m/validate de/patch-schema [[:a] :assoc [1 2 3]]))
        "assoc vector leaf")
    (is (true? (m/validate de/patch-schema [[:a] :assoc #{:tag}]))
        "assoc set leaf")))

(deftest patch-schema-rejects-malformed-tuples
  (testing "wrong op keyword — only :assoc / :dissoc allowed"
    (is (false? (m/validate de/patch-schema [[:a] :replace 1]))
        ":replace is not in the grammar")
    (is (false? (m/validate de/patch-schema [[:a] "assoc" 1]))
        "string ops are rejected"))
  (testing "wrong arity"
    (is (false? (m/validate de/patch-schema [[:a] :assoc]))
        ":assoc without value is invalid")
    (is (false? (m/validate de/patch-schema [[:a] :dissoc :extra]))
        ":dissoc with a trailing value is invalid")
    (is (false? (m/validate de/patch-schema [[:a]]))
        "missing op"))
  (testing "path must be a vector"
    (is (false? (m/validate de/patch-schema ['(:a) :assoc 1]))
        "list path is rejected")
    (is (false? (m/validate de/patch-schema [:a :assoc 1]))
        "keyword path is rejected")
    (is (false? (m/validate de/patch-schema [nil :dissoc]))
        "nil path is rejected"))
  (testing "non-tuple shapes"
    (is (false? (m/validate de/patch-schema {:path [:a] :op :assoc :v 1}))
        "map-shaped patch is rejected")
    (is (false? (m/validate de/patch-schema nil))
        "nil patch is rejected")))

(deftest collect-patches-output-conforms-to-schema
  ;; The factory's emission MUST validate against patches-schema for
  ;; every shape combination the encoder is expected to produce. If a
  ;; future refactor of collect-map-patches starts emitting a new
  ;; tuple shape (e.g. an :update op), this test fails BEFORE the
  ;; emission reaches a consumer.
  (testing "added key, removed key, changed leaf, nested recursion, shape mismatch"
    (let [cases [;; trivial equality — empty patches
                 [{:a 1}                {:a 1}]
                 ;; added
                 [{:a 1}                {:a 1 :b 2}]
                 ;; removed
                 [{:a 1 :b 2}           {:a 1}]
                 ;; changed leaf
                 [{:a 1}                {:a 3}]
                 ;; nested
                 [{:u {:name "ada" :age 30}}
                  {:u {:name "ada" :age 31 :role :admin}}]
                 ;; root-level shape change
                 [{:a 1}                [1 2 3]]
                 ;; map → map containing vector leaf
                 [{:a {:b 1}}           {:a [1 2 3]}]
                 ;; many keys with mixed actions
                 [{:keep 1 :change 2 :remove 3}
                  {:keep 1 :change 99 :add 4}]]]
      (doseq [[a b] cases]
        (let [patches (de/collect-patches a b [])]
          (is (true? (m/validate de/patches-schema patches))
              (str "patches conform for case " (pr-str [a b])
                   " — produced " (pr-str patches))))))))

(deftest diff-encode-db-after-emits-schema-conformant-patches
  ;; The encoder-boundary entry point. Whatever collect-patches
  ;; produced flows out through diff-encode-db-after; its validation
  ;; gate (`validate-patches!`) must accept the emission silently.
  (let [epoch   {:db-before {:user {:name "ada" :age 30}
                             :session :idle}
                 :db-after  {:user {:name "ada" :age 31 :role :admin}
                             :flags #{:beta}}}
        encoded (de/diff-encode-db-after epoch)
        patches (get-in encoded [:db-after :patches])]
    (is (true? (m/validate de/patches-schema patches))
        "encoded patches conform to patches-schema")
    (is (pos? (count patches))
        "non-trivial encode produced at least one patch")))

(deftest diff-encode-db-after-throws-when-validation-disabled-elsewhere-noop
  ;; The validation gate is `validate-patches!`. Calling it directly
  ;; with malformed input throws; well-formed input is a silent no-op.
  ;; This pins the boundary contract independently of the public
  ;; encoder entry point.
  (testing "well-formed patches return nil"
    (is (nil? (#'de/validate-patches! [[[:a] :assoc 1]
                                       [[:b] :dissoc]]))))
  (testing "malformed patches throw :rf.error/bad-diff-patches"
    (let [bad [[[:a] :replace 1]]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"diff-encode patch grammar violated"
            (#'de/validate-patches! bad)))
      (try
        (#'de/validate-patches! bad)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/bad-diff-patches
                 (:rf.error/code (ex-data e)))
              "ex-info carries the reserved :rf.error/* code"))))))
