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

(deftest apply-patches-applies-in-order-for-same-path
  ;; Regression pin (rf2-cwqc8 / round-2 F18): when two patches target
  ;; the same path, the later one wins — `reduce` over the patch
  ;; sequence applies them in order so a later `:assoc` overrides an
  ;; earlier one, and a `:dissoc` after an `:assoc` clears the value.
  ;; re-frame2-pair-mcp's diff_encode_epochs_test pinned this; the base's own
  ;; test set didn't. Mirror the contract here so any future encoder
  ;; refactor that flips the iteration order trips this gate before
  ;; reaching the consumers.
  (testing "later :assoc overrides earlier :assoc at same path"
    (is (= {:a 99}
           (de/apply-patches {} [[[:a] :assoc 1]
                                 [[:a] :assoc 99]]))))
  (testing ":dissoc after :assoc clears the value"
    (is (= {}
           (de/apply-patches {} [[[:a] :assoc 1]
                                 [[:a] :dissoc]]))))
  (testing ":assoc after :dissoc reinstates the value"
    (is (= {:a 7}
           (de/apply-patches {:a 1} [[[:a] :dissoc]
                                     [[:a] :assoc 7]])))))

;; ---------------------------------------------------------------------------
;; diff-encode-db-after / decode-db-after — round-trip.
;; ---------------------------------------------------------------------------

(deftest diff-encode-db-after-emits-sections-shape
  ;; rf2-qeous: the encoder now emits path-headed cluster sections
  ;; instead of a flat patch list.
  (let [epoch    {:db-before {:a 1 :b 2}
                  :db-after  {:a 1 :b 3}}
        encoded  (de/diff-encode-db-after epoch)
        sections (get-in encoded [:db-after :sections])]
    (is (= :db-before (get-in encoded [:db-after :rf.mcp/diff-from])))
    (is (vector? sections))
    (is (= 1 (count sections)) "single change → one section")
    (let [s (first sections)]
      (is (= [:b] (:section-path s)))
      (is (= :modified (:section-kind s)))
      (is (= [[[:b] :assoc 3]] (:patches s))))))

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
        (is (= (dissoc enc :db-after)
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
;; consumer (the cross-MCP wire-vocab conformance test) that re-states
;; the grammar — both trip this gate before reaching the wire.
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

(deftest diff-encode-db-after-emits-schema-conformant-sections
  ;; The encoder-boundary entry point. Whatever collect-patches +
  ;; group-patches-into-sections produced flows out through
  ;; diff-encode-db-after; its validation gates (`validate-patches!`
  ;; + `validate-sections!`) must accept the emission silently.
  (let [epoch    {:db-before {:user {:name "ada" :age 30}
                              :session :idle}
                  :db-after  {:user {:name "ada" :age 31 :role :admin}
                              :flags #{:beta}}}
        encoded  (de/diff-encode-db-after epoch)
        sections (get-in encoded [:db-after :sections])]
    (is (true? (m/validate de/sections-schema sections))
        "encoded sections conform to sections-schema")
    (is (pos? (count sections))
        "non-trivial encode produced at least one section")
    (doseq [s sections]
      (is (true? (m/validate de/patches-schema (:patches s)))
          "every section's :patches subset conforms to patches-schema"))))

(deftest diff-encode-db-after-throws-when-validation-disabled-elsewhere-noop
  ;; The validation gate is `validate-patches!`. Calling it directly
  ;; with malformed input throws; well-formed input is a silent no-op.
  ;; This pins the boundary contract independently of the public
  ;; encoder entry point.
  (testing "well-formed patches return nil"
    (is (nil? (#'de/validate-patches! [[[:a] :assoc 1]
                                       [[:b] :dissoc]]
                                      'mcp-base/diff-encode-db-after))))
  (testing "malformed patches throw :rf.error/bad-diff-patches"
    (let [bad [[[:a] :replace 1]]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-patches"
            (#'de/validate-patches! bad 'mcp-base/diff-encode-db-after)))
      (try
        (#'de/validate-patches! bad 'mcp-base/diff-encode-db-after)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/bad-diff-patches
                 (:rf.error/id (ex-data e)))
              "ex-info carries the reserved :rf.error/* code")))))
  (testing "the threaded `where` symbol propagates to ex-info :where (rf2-4ypau)"
    (let [bad [[[:a] :replace 1]]]
      (try
        (#'de/validate-patches! bad 'mcp-base/diff-encode-db-after)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'mcp-base/diff-encode-db-after (:where (ex-data e)))
              "encoder caller threads its own site")))
      (try
        (#'de/validate-patches! bad 'mcp-base/apply-patches)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= 'mcp-base/apply-patches (:where (ex-data e)))
              "decoder caller threads its own site"))))))

;; ---------------------------------------------------------------------------
;; Decoder-boundary validation (rf2-8e61v / F17).
;;
;; `apply-patches` is the wire-decoder entry point. A malformed patch
;; reaching this fn is a contract violation — the previous behaviour
;; silently no-op'd on the offending tuple (fell through the `cond` to
;; `:else acc` and dropped the corrupted patch without a peep). Mirror
;; the encoder boundary's gate: validate against `patches-schema` and
;; throw `:rf.error/bad-diff-patches`.
;; ---------------------------------------------------------------------------

(deftest apply-patches-rejects-malformed-tuples
  (testing "missing op (2-element tuple with no op) throws"
    ;; Pre-fix: silently no-op'd because the destructure put `nil` in
    ;; `op` and the cond fell through to `:else acc`. Post-fix: the
    ;; validate-patches! gate trips on the malformed tuple BEFORE the
    ;; reduce starts.
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-patches"
          (de/apply-patches {} [[[:a]]]))))
  (testing "unknown op throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-patches"
          (de/apply-patches {} [[[:a] :replace 1]]))))
  (testing "non-vector path throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-patches"
          (de/apply-patches {} [[:a :assoc 1]]))))
  (testing "ex-info carries reserved :rf.error/bad-diff-patches code + decode-side :where"
    (try
      (de/apply-patches {} [[[:a] :replace 1]])
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/bad-diff-patches
               (:rf.error/id (ex-data e))))
        (is (= 'mcp-base/apply-patches
               (:where (ex-data e)))
            "ex-info names the DECODE-side boundary, not the encoder (rf2-4ypau)")))))

(deftest apply-patches-well-formed-input-passes-validation
  ;; Soft contract: well-formed patches pass the gate silently and
  ;; produce the same output the pre-validation implementation did.
  (is (= {:a 1 :b 2}
         (de/apply-patches {:a 1} [[[:b] :assoc 2]])))
  (is (= {:a 1}
         (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))

(deftest apply-patches-empty-patches-is-identity
  ;; Empty patch list ⇒ base returned unchanged.
  (is (= {:a 1} (de/apply-patches {:a 1} []))))

;; ---------------------------------------------------------------------------
;; Decoder-boundary SECTION validation (rf2-j6oay / rf2-80y2h).
;;
;; `decode-db-after` validates the `:sections` vector via
;; `validate-sections!` BEFORE flattening + replaying — encoder/decoder
;; symmetry per the rf2-8e61v argument. The encoder boundary
;; (`diff-encode-db-after`) was tested negatively
;; (`diff-encode-db-after-emits-schema-conformant-sections` +
;; `validate-patches!` direct), and `validate-sections!` was tested
;; only positively (it never throws on a real encode). This pins the
;; SYMMETRIC decode-side gate: a marker carrying malformed `:sections`
;; reaching `decode-db-after` MUST throw `:rf.error/bad-diff-sections`
;; rather than slip cosmetic `:section-kind` / `:section-path` slots
;; through to an agent-host UI that paints them as truth.
;; ---------------------------------------------------------------------------

(deftest decode-db-after-rejects-malformed-sections
  (testing "section with a non-vector :section-path throws"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections [{:section-path :not-a-vector
                                         :section-kind :modified
                                         :patches      [[[:a] :assoc 2]]}]}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-sections"
            (de/decode-db-after epoch)))))
  (testing "section with an unknown :section-kind throws"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections [{:section-path [:a]
                                         :section-kind :renamed     ;; not in the enum
                                         :patches      [[[:a] :assoc 2]]}]}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-sections"
            (de/decode-db-after epoch)))))
  (testing "section missing the :patches slot throws"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections [{:section-path [:a]
                                         :section-kind :modified}]}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-sections"
            (de/decode-db-after epoch)))))
  (testing "ex-info carries the reserved :rf.error/bad-diff-sections code"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections [{:section-path :bad
                                         :section-kind :modified
                                         :patches      []}]}}]
      (try
        (de/decode-db-after epoch)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/bad-diff-sections
                 (:rf.error/id (ex-data e)))
              "ex-info carries the reserved :rf.error/* code")
          (is (= 'mcp-base/decode-db-after
                 (:where (ex-data e)))
              "ex-info names the DECODE-side boundary, not the encoder (rf2-4ypau)"))))))

(deftest decode-db-after-well-formed-sections-round-trip
  ;; The positive companion: well-formed sections decode silently and
  ;; reconstruct :db-after — proving the decode gate is a guard, not a
  ;; blanket reject.
  (let [epoch   {:db-before {:user {:name "ada" :age 30}}
                 :db-after  {:user {:name "ada" :age 31}}}
        encoded (de/diff-encode-db-after epoch)
        decoded (de/decode-db-after encoded)]
    (is (= epoch decoded))))
