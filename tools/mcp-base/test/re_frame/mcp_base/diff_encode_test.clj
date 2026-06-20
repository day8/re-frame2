(ns re-frame.mcp-base.diff-encode-test
  "Tests for the path-keyed structural diff used at the MCP wire
  boundary, shared across both servers."
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
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
;; collect-patches — same-length vectors diff structurally.
;; ---------------------------------------------------------------------------

(deftest collect-patches-diffs-vector-element-map-update
  ;; The headline case: a single item update inside a vector yields an
  ;; index-headed item-level patch, NOT a whole-vector replacement.
  (is (= [[[:items 0 :qty] :assoc 2]]
         (de/collect-patches {:items [{:qty 1}]} {:items [{:qty 2}]} []))))

(deftest collect-patches-diffs-same-length-scalar-vector
  (is (= [[[:xs 1] :assoc 9]]
         (de/collect-patches {:xs [1 2 3]} {:xs [1 9 3]} []))))

(deftest collect-patches-diffs-multiple-vector-elements
  (is (= [[[:xs 0] :assoc 10]
          [[:xs 2] :assoc 30]]
         (de/collect-patches {:xs [1 2 3]} {:xs [10 2 30]} []))))

(deftest collect-patches-recurses-nested-vectors
  (is (= [[[:grid 1 0] :assoc 9]]
         (de/collect-patches {:grid [[1 2] [3 4]]}
                             {:grid [[1 2] [9 4]]} []))))

(deftest collect-patches-vector-nil-element-changes
  (testing "nil → value at an index"
    (is (= [[[:xs 1] :assoc 5]]
           (de/collect-patches {:xs [1 nil 3]} {:xs [1 5 3]} []))))
  (testing "value → nil at an index"
    (is (= [[[:xs 1] :assoc nil]]
           (de/collect-patches {:xs [1 2 3]} {:xs [1 nil 3]} [])))))

(deftest collect-patches-vector-length-change-is-whole-leaf
  (testing "growth → whole-vector :assoc (length delta, no element diff)"
    (is (= [[[:xs] :assoc [1 2 3 4]]]
           (de/collect-patches {:xs [1 2 3]} {:xs [1 2 3 4]} []))))
  (testing "shrink → whole-vector :assoc"
    (is (= [[[:xs] :assoc [1 2]]]
           (de/collect-patches {:xs [1 2 3]} {:xs [1 2]} [])))))

(deftest collect-patches-equal-vector-emits-nothing
  (is (= [] (de/collect-patches {:xs [1 2 3]} {:xs [1 2 3]} []))))

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

(deftest apply-patches-round-trips-vector-diffs
  ;; Every same-length-vector diff shape round-trips through
  ;; the existing assoc-in replay (integer index paths), and every
  ;; length-change whole-leaf replacement does too.
  (doseq [[label a b]
          [["vector element map update"   {:items [{:qty 1} {:qty 5}]} {:items [{:qty 2} {:qty 5}]}]
           ["nested vector update"        {:grid [[1 2] [3 4]]}        {:grid [[1 2] [9 4]]}]
           ["nil vector element → value"  {:xs [1 nil 3]}              {:xs [1 5 3]}]
           ["value → nil vector element"  {:xs [1 2 3]}               {:xs [1 nil 3]}]
           ["same-length scalar update"   {:xs [1 2 3]}               {:xs [1 9 3]}]
           ["multiple element updates"    {:xs [1 2 3]}               {:xs [10 2 30]}]
           ["vector growth (whole-leaf)"  {:xs [1 2 3]}               {:xs [1 2 3 4]}]
           ["vector shrink (whole-leaf)"  {:xs [1 2 3]}               {:xs [1 2]}]
           ["vector of maps, length grew" {:items [{:id 1}]}          {:items [{:id 1} {:id 2}]}]
           ["root vector element update"  [1 2 3]                     [1 9 3]]]]
    (testing label
      (let [p (de/collect-patches a b [])]
        (is (= b (de/apply-patches a p))
            (str label " must round-trip"))))))

(deftest apply-patches-dissoc-at-root-via-direct-path
  (is (= {:a 1} (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))

(deftest apply-patches-applies-in-order-for-same-path
  ;; Regression pin: when two patches target
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

(deftest apply-patches-nested-dissoc-missing-or-scalar-parent-is-noop
  ;; `[<path> :dissoc]` is a no-op when the key does not
  ;; exist (per the spec). The naive `(update-in acc parent dissoc k)`
  ;; violated this: a MISSING parent manufactured nil branches, and a
  ;; SCALAR parent threw a host ClassCastException at the decoder
  ;; boundary. `apply-patches` is the public wire decoder; a malformed /
  ;; corrupt / third-party diff replayed against a mismatched base must
  ;; not corrupt the base into a shape neither side emitted, nor crash.
  (testing "missing direct parent ⇒ no-op (no nil-branch manufacture)"
    (is (= {} (de/apply-patches {} [[[:missing :leaf] :dissoc]]))
        "was {:missing nil} before the fix"))
  (testing "missing deeper parent ⇒ no-op"
    (is (= {:a {}} (de/apply-patches {:a {}} [[[:a :b :c] :dissoc]]))
        "was {:a {:b nil}} before the fix"))
  (testing "scalar parent ⇒ no-op (no host ClassCastException)"
    (is (= {:a 1} (de/apply-patches {:a 1} [[[:a :b] :dissoc]]))
        "was a thrown ClassCastException before the fix"))
  (testing "valid nested dissoc still removes the key"
    (is (= {:a {:c 2}} (de/apply-patches {:a {:b 1 :c 2}} [[[:a :b] :dissoc]]))))
  (testing "root-key dissoc unchanged"
    (is (= {:a 1} (de/apply-patches {:a 1 :b 2} [[[:b] :dissoc]]))))
  (testing "dissoc of an absent root key ⇒ no-op"
    (is (= {:a 1} (de/apply-patches {:a 1} [[[:z] :dissoc]])))))

(deftest apply-patches-nested-assoc-into-scalar-parent-is-structured-error
  ;; The `:assoc` PEER of the dissoc guard.
  ;; A grammar-valid patch like `[[[:a :b] :assoc 2]]` against base
  ;; `{:a 1}` used to delegate straight to `assoc-in`, which throws a
  ;; raw host `ClassCastException` with nil ex-data at the wire decoder
  ;; boundary. `apply-patches` is the public wire decoder; a malformed /
  ;; corrupt / third-party / mismatched-base diff must surface a
  ;; DOCUMENTED structured failure, never leak a raw host exception.
  ;; Policy: a non-associative intermediate parent is a base/patch
  ;; MISMATCH, surfaced as `:rf.error/bad-diff-replay` (not a silent
  ;; no-op — that would drop the requested write; not a clobber — that
  ;; would corrupt the base into a shape neither side emitted).
  (testing "scalar intermediate parent ⇒ structured :rf.error/bad-diff-replay (no host ClassCastException)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-replay"
          (de/apply-patches {:a 1} [[[:a :b] :assoc 2]]))
        "was a raw host ClassCastException before the fix")
    (try
      (de/apply-patches {:a 1} [[[:a :b] :assoc 2]])
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :rf.error/bad-diff-replay (:rf.error/id d))
              "carries the reserved :rf.error/* code")
          (is (= 'mcp-base/apply-patches (:where d))
              "decode-side boundary (rf2-4ypau)")
          (is (= :no-recovery (:recovery d)))
          (is (= [:a :b] (:patch-path d)) "reports the offending patch path")
          (is (= [:a] (:at d)) "reports the prefix where traversal hit the non-associative node")
          ;; value-free: the ex-data names the parent's TYPE, never its value.
          (is (not (nil? (:parent-type d))) "carries a value-free parent-type tag")))))
  (testing "deeper scalar intermediate parent ⇒ structured error naming the deep prefix"
    (try
      (de/apply-patches {:a {:b 1}} [[[:a :b :c] :assoc 5]])
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= [:a :b] (:at (ex-data e)))))))
  (testing "vector intermediate parent reached by a non-integer key ⇒ structured error (no host IllegalArgumentException)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-replay"
          (de/apply-patches {:a [1 2]} [[[:a :b] :assoc 9]]))))
  (testing "vector intermediate parent reached by an out-of-range index ⇒ structured error (no host IndexOutOfBounds)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-replay"
          (de/apply-patches {:items [1 2]} [[[:items 5] :assoc 9]]))))
  (testing "MISSING / nil intermediate parent still auto-vivifies (create-if-absent grammar preserved)"
    (is (= {:a {:b 2}} (de/apply-patches {} [[[:a :b] :assoc 2]]))
        "missing parent ⇒ map auto-vivified, not an error")
    (is (= {:a {:b {:c 9}}} (de/apply-patches {} [[[:a :b :c] :assoc 9]]))
        "deep missing parents ⇒ chain auto-vivified")
    (is (= {:a {:b 2}} (de/apply-patches {:a nil} [[[:a :b] :assoc 2]]))
        "nil parent ⇒ map auto-vivified"))
  (testing "valid nested :assoc into a map parent still sets the slot"
    (is (= {:a {:x 1 :y 2}} (de/apply-patches {:a {:x 1}} [[[:a :y] :assoc 2]]))))
  (testing "valid :assoc into a vector index (in-bounds + tail-grow) still works"
    (is (= {:items [{:qty 2}]} (de/apply-patches {:items [{:qty 1}]} [[[:items 0 :qty] :assoc 2]])))
    (is (= {:items [10 20]} (de/apply-patches {:items [10]} [[[:items 1] :assoc 20]]))))
  (testing "leaf-level :assoc OVERWRITING a scalar is fine (only intermediate parents are guarded)"
    (is (= {:a 2} (de/apply-patches {:a 1} [[[:a] :assoc 2]]))))
  (testing "root :assoc whole-value replacement still works"
    (is (= {:y 9} (de/apply-patches {:x 1} [[[] :assoc {:y 9}]])))))

(deftest decode-db-after-nested-assoc-mismatched-base-is-structured-error
  ;; The same guard reached via the section-decoder hot
  ;; path. `decode-db-after` replays through the non-validating
  ;; `apply-patches*`; a section whose `:patches` assoc into a path that
  ;; is non-associative in THIS record's `:db-before` (a corrupt /
  ;; mismatched-base diff) must surface the structured failure naming
  ;; the `decode-db-after` boundary — NOT a raw host exception.
  (let [epoch {:db-before {:a 1}
               :db-after  {:rf.mcp/diff-from :db-before
                           :sections [{:section-path [:a]
                                       :section-kind :modified
                                       :patches      [[[:a :b] :assoc 2]]}]}}]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #":rf\.error/bad-diff-replay"
          (de/decode-db-after epoch)))
    (try
      (de/decode-db-after epoch)
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/bad-diff-replay (:rf.error/id (ex-data e))))
        (is (= 'mcp-base/decode-db-after (:where (ex-data e)))
            "boundary attributes the section decoder, not apply-patches (rf2-4ypau)"))))
  (testing "a well-formed diff against a matching base still round-trips"
    (let [encoded (de/diff-encode-db-after {:db-before {:a {:x 1}}
                                            :db-after  {:a {:x 1 :y 2}}})]
      (is (= {:a {:x 1 :y 2}} (:db-after (de/decode-db-after encoded)))))))

;; ---------------------------------------------------------------------------
;; diff-encode-db-after / decode-db-after — round-trip.
;; ---------------------------------------------------------------------------

(deftest diff-encode-db-after-emits-sections-shape
  ;; The encoder emits path-headed cluster sections, not a flat patch
  ;; list.
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

(deftest diff-encode-db-after-classifies-modified-not-false-added
  ;; The encoder threads :db-before into section
  ;; classification so an all-:assoc direct-child cluster is :added ONLY
  ;; when its container was genuinely absent before. Patch shape alone
  ;; can't tell an insert from a change (both are :assoc), so an existing
  ;; parent whose direct child changed was mislabelled :added — a false
  ;; skim signal to the agent.
  ;;
  ;; Note on collect-patches shapes: a GENUINELY-new multi-key container
  ;; is emitted as a single whole-subtree `[[:user] :assoc {...}]` patch
  ;; (collect-patches doesn't recurse into an absent key), which heads as
  ;; a singleton → :modified. The all-:assoc DIRECT-CHILD shape over the
  ;; collect-patches pipeline therefore ALWAYS means the container
  ;; ALREADY existed and its children changed — i.e. the exact case the
  ;; old patch-only rule falsely tagged :added. The genuine direct-child
  ;; :added shape only arises from an advanced consumer supplying a
  ;; synthetic patch list (pinned at the group-patches-into-sections
  ;; level in section_grouping_test).
  (testing "existing container, child changed + sibling added ⇒ :modified (NOT a false :added)"
    (let [epoch    {:db-before {:user {:name "bob"}}
                    :db-after  {:user {:name "ada" :email "ada@example.com"}}}
          sections (get-in (de/diff-encode-db-after epoch) [:db-after :sections])
          user-s   (first (filter #(= [:user] (:section-path %)) sections))]
      (is (some? user-s) "a [:user] section was produced")
      (is (= :modified (:section-kind user-s))
          "[:user] existed in db-before; the cluster is a modification, not an addition")))
  (testing "genuinely new multi-key container ⇒ one whole-subtree :modified section"
    (let [epoch    {:db-before {:session :idle}
                    :db-after  {:session :idle
                                :user {:name "ada" :email "ada@example.com"}}}
          sections (get-in (de/diff-encode-db-after epoch) [:db-after :sections])
          user-s   (first (filter #(= [:user] (:section-path %)) sections))]
      (is (some? user-s) "a [:user] section was produced")
      (is (= :modified (:section-kind user-s))
          "a brand-new key is a whole-subtree singleton assoc — heads :modified, never a false :added")
      (is (= [[[:user] :assoc {:name "ada" :email "ada@example.com"}]] (:patches user-s)))))
  (testing "round-trip still reconstructs db-after regardless of cosmetic :section-kind"
    (doseq [epoch [{:db-before {:user {:name "bob"}}
                    :db-after  {:user {:name "ada" :email "x"}}}
                   {:db-before {:session :idle}
                    :db-after  {:session :idle :user {:name "ada" :email "x"}}}]]
      (is (= epoch (de/decode-db-after (de/diff-encode-db-after epoch)))
          (str "encode→decode round-trips for " (pr-str epoch))))))

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
;; Patch grammar — Malli schema pin.
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
;; Sanitized validation diagnostics (EP-0015 egress-policy).
;;
;; The diff-encode wire boundary carries epoch :db-before/:db-after
;; payloads, so the *value* side of an `:assoc` patch (and any map a
;; section's :patches carries) can be an app-db leaf the egress policy
;; expects to stay projected / redacted. A malformed patch/section that
;; trips the Malli gate must not smuggle that value back out through the
;; exception — neither in the message, the ex-data, nor the pr-str of the
;; thrown data. The replacement diagnostic carries value-FREE shape only
;; (error id, boundary, recovery, reason, schema identity, count, and the
;; first bad index / path / op / element type), mirroring `assoc-in-safe`
;; which reports path + parent TYPE rather than the parent VALUE.
;; ---------------------------------------------------------------------------

(defn- secret-absent?
  "True when `secret` appears nowhere in the thrown ExceptionInfo's
  message, the pr-str of its ex-data, or the pr-str of any individual
  ex-data value — the three egress surfaces a diagnostic can leak from."
  [^clojure.lang.ExceptionInfo e secret]
  (let [data (ex-data e)]
    (and (not (clojure.string/includes? (str (.getMessage e)) secret))
         (not (clojure.string/includes? (pr-str data) secret))
         (every? (fn [[_ v]] (not (clojure.string/includes? (pr-str v) secret)))
                 data))))

(deftest validate-patches!-diagnostics-omit-raw-patch-values
  ;; A malformed patch whose VALUE side is an obvious secret must not
  ;; appear anywhere reachable from the thrown exception.
  (let [secret "s3cr3t-token-DO-NOT-LEAK"
        ;; Malformed: 3-element :dissoc tuple is rejected by the grammar,
        ;; but the trailing element carries the secret payload.
        bad    [[[:user :api-key] :dissoc secret]]]
    (try
      (#'de/validate-patches! bad 'mcp-base/diff-encode-db-after)
      (is false "expected the grammar gate to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/bad-diff-patches (:rf.error/id (ex-data e))))
        (is (secret-absent? e secret)
            "the raw patch value must not leak via message / ex-data / pr-str")
        ;; Value-free shape info is retained for triage.
        (let [data (ex-data e)]
          (is (= 'mcp-base/diff-encode-db-after (:where data)))
          (is (= :no-recovery (:recovery data)))
          (is (string? (:reason data)))
          (is (nil? (:patches data)) "the raw :patches slot is gone")
          (is (= 1 (:count data)) "value-free count of the offending batch")
          (is (= 0 (:bad-index data)) "first bad element index"))))))

(deftest validate-patches!-diagnostics-omit-secret-map-leaf
  ;; The :assoc VALUE can itself be a map of secrets; even a well-typed
  ;; tuple that fails for an unrelated reason (bad op) must not carry it.
  (let [secret "pw-9f3a-LEAK"
        bad    [[[:session] :replace {:password secret :token secret}]]]
    (try
      (#'de/validate-patches! bad 'mcp-base/apply-patches)
      (is false "expected throw")
      (catch clojure.lang.ExceptionInfo e
        (is (secret-absent? e secret)
            "a secret map carried as the patch value must not leak")
        (is (= 'mcp-base/apply-patches (:where (ex-data e))))))))

(deftest validate-sections!-diagnostics-omit-raw-section-values
  ;; A section's nested :patches can carry secret values; a malformed
  ;; section that trips the sections gate must not leak them.
  (let [secret "section-secret-XYZ-LEAK"
        bad    [{:section-path :not-a-vector ;; malformed → trips the gate
                 :section-kind :modified
                 :patches      [[[:creds :password] :assoc secret]]}]]
    (try
      (#'de/validate-sections! bad 'mcp-base/decode-db-after)
      (is false "expected the sections gate to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/bad-diff-sections (:rf.error/id (ex-data e))))
        (is (secret-absent? e secret)
            "the raw section/patch value must not leak via message / ex-data / pr-str")
        (let [data (ex-data e)]
          (is (nil? (:sections data)) "the raw :sections slot is gone")
          (is (= 1 (:count data)))
          (is (= 0 (:bad-index data))))))))

;; ---------------------------------------------------------------------------
;; Decoder-boundary validation.
;;
;; `apply-patches` is the wire-decoder entry point. A malformed patch
;; reaching this fn is a contract violation. The gate mirrors the
;; encoder boundary's: validate against `patches-schema` and throw
;; `:rf.error/bad-diff-patches`. Without it the offending tuple would
;; silently no-op (fall through the `cond` to `:else acc` and drop the
;; corrupted patch without a peep).
;; ---------------------------------------------------------------------------

(deftest apply-patches-rejects-malformed-tuples
  (testing "missing op (2-element tuple with no op) throws"
    ;; Without the gate this would silently no-op: the destructure puts
    ;; `nil` in `op` and the cond falls through to `:else acc`. The
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
;; Decoder-boundary SECTION validation.
;;
;; `decode-db-after` validates the `:sections` vector via
;; `validate-sections!` BEFORE flattening + replaying — encoder/decoder
;; symmetry. This pins the SYMMETRIC decode-side gate: a marker carrying
;; malformed `:sections` reaching `decode-db-after` MUST throw
;; `:rf.error/bad-diff-sections` rather than slip cosmetic
;; `:section-kind` / `:section-path` slots through to an agent-host UI
;; that paints them as truth.
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

(deftest decode-db-after-rejects-malformed-sections-slot
  ;; A diff marker whose `:sections` slot is `nil` or `false` must not
  ;; decode to a no-op. Coercing it via `(or sections [])` would let an
  ;; empty seq satisfy `[:sequential section-schema]`, so the gate would
  ;; never trip and the epoch's real `:db-after` change would be silently
  ;; ERASED back to `:db-before`. The raw slot is validated, so a PRESENT
  ;; non-sequential `:sections` trips `:rf.error/bad-diff-sections`
  ;; (Malli present). `:db-before` carries a real change so a regression
  ;; (silent no-op) would observably hand back `{:a 1}` instead of
  ;; throwing.
  ;;
  ;; A MISSING `:sections` key is a marker-BODY shape violation (the
  ;; closed two-key contract), so it trips the structural
  ;; `:rf.error/bad-diff-marker` gate FIRST (see
  ;; `decode-db-after-rejects-malformed-marker-body`). A PRESENT-but-falsey
  ;; `:sections` still satisfies the closed key set, so the body gate passes
  ;; and `validate-sections!` owns the `bad-diff-sections` verdict below.
  (testing ":sections nil throws"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections nil}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-sections"
            (de/decode-db-after epoch)))))
  (testing ":sections false throws"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections false}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-sections"
            (de/decode-db-after epoch)))))
  (testing "the present-but-falsey-slot throw names the DECODE-side boundary"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections nil}}]
      (try
        (de/decode-db-after epoch)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/bad-diff-sections (:rf.error/id (ex-data e))))
          (is (= 'mcp-base/decode-db-after (:where (ex-data e)))))))))

(deftest decode-db-after-rejects-malformed-marker-body
  ;; The decoder recognizes a diff marker on the PRESENCE of
  ;; the `:rf.mcp/diff-from` key (intent), then enforces the CLOSED
  ;; marker-body contract (mcp-conformance `DiffFromBody`): EXACTLY
  ;; `{:rf.mcp/diff-from :db-before, :sections [...]}` — two top-level keys,
  ;; the marker value restricted to `:db-before`. A pure STRUCTURAL gate
  ;; (no Malli), so it fires regardless of Malli presence. It closes two
  ;; cases a `:sections`-only check would miss:
  ;;   - an extra sibling key would slip past (only `:sections` checked);
  ;;   - an unsupported marker value would be treated as 'not a diff' and
  ;;     pass THROUGH unchanged, letting a corrupt/third-party marker
  ;;     survive.
  (testing "an extra sibling top-level key rejects (closed two-key contract)"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections []
                             :sneaky   :key}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-marker"
            (de/decode-db-after epoch)))))
  (testing "a missing :sections key rejects (the closed pair is incomplete)"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-marker"
            (de/decode-db-after epoch)))))
  (testing "an unsupported :rf.mcp/diff-from value rejects (not a silent passthrough)"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-later  ;; only :db-before is conformant
                             :sections []}}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/bad-diff-marker"
            (de/decode-db-after epoch)))))
  (testing "the marker-body throw is value-free + names the DECODE-side boundary"
    (let [epoch {:db-before {:a 1}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections []
                             :secret   "sensitive-payload"}}]
      (try
        (de/decode-db-after epoch)
        (is false "expected throw")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :rf.error/bad-diff-marker (:rf.error/id data)))
            (is (= 'mcp-base/decode-db-after (:where data)))
            (is (= :no-recovery (:recovery data)))
            (is (= #{:rf.mcp/diff-from :sections} (:expected-keys data)))
            (is (= #{:rf.mcp/diff-from :sections :secret} (:actual-keys data)))
            ;; value-free (EP-0015): no smuggled app-db payload anywhere in
            ;; ex-data or the message.
            (is (not (clojure.string/includes? (pr-str data) "sensitive-payload"))
                "the offending value MUST NOT leak into the diagnostic"))))))
  (testing "the canonical two-key marker still decodes (the gate is a guard, not a blanket reject)"
    (let [epoch   {:db-before {:user {:name "ada" :age 30}}
                   :db-after  {:user {:name "ada" :age 31}}}
          encoded (de/diff-encode-db-after epoch)]
      (is (= epoch (de/decode-db-after encoded))
          "a well-formed {:rf.mcp/diff-from :db-before :sections [...]} round-trips")))
  (testing "a no-marker full :db-after still passes through unchanged"
    (let [epoch {:db-before {:a 1} :db-after {:a 1 :b 2}}]
      (is (= epoch (de/decode-db-after epoch))))))

(deftest decode-db-after-explicit-empty-sections-is-valid-no-change
  ;; Companion: an EXPLICIT `:sections []` is a legitimate
  ;; no-change diff (db-before == db-after) and MUST still validate and
  ;; replay to `:db-before` unchanged — the gate guards malformed shape,
  ;; it does not reject a real empty diff.
  (testing "encoder emits :sections [] for an unchanged epoch and decode round-trips"
    (let [epoch   {:db-before {:a 1} :db-after {:a 1}}
          encoded (de/diff-encode-db-after epoch)]
      (is (= [] (get-in encoded [:db-after :sections]))
          "no-change epoch encodes to an explicit empty section vector")
      (is (= epoch (de/decode-db-after encoded)))))
  (testing "a hand-built :sections [] marker decodes to :db-before without throwing"
    (let [epoch {:db-before {:a 1 :b 2}
                 :db-after  {:rf.mcp/diff-from :db-before
                             :sections []}}]
      (is (= {:db-before {:a 1 :b 2} :db-after {:a 1 :b 2}}
             (de/decode-db-after epoch))))))

(deftest decode-db-after-well-formed-sections-round-trip
  ;; The positive companion: well-formed sections decode silently and
  ;; reconstruct :db-after — proving the decode gate is a guard, not a
  ;; blanket reject.
  (let [epoch   {:db-before {:user {:name "ada" :age 30}}
                 :db-after  {:user {:name "ada" :age 31}}}
        encoded (de/diff-encode-db-after epoch)
        decoded (de/decode-db-after encoded)]
    (is (= epoch decoded))))
