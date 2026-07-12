(ns day8.re-frame2-machines-viz.grammar-validation-cljs-test
  "RECURSIVE grammar-validation tests (rf2-j538f7.18).

  `grammar/valid-definition?` / `grammar/definition-defect` recursively enforce
  the runtime-relevant STRUCTURAL PROJECTABILITY contract — no longer the
  pre-fix SHALLOW minimum-shape check that validated only the root (or
  parallel-region roots) and therefore blessed
  structurally-invalid-but-shallowly-ok definitions.

  These tests pin the recursive walker directly:

    - the three deterministic false positives from the bead report are now
      REJECTED with the CANONICAL `:rf.error/machine-*` defect category;
    - recursive coverage over root / nested-compound / parallel-region initial
      presence + type, empty / non-map state bodies, unknown bare node / spawn
      keys (namespaced keys pass), malformed / dangling keyword AND vector
      targets, history / choice / timeout / spawn / final-state structural
      constraints;
    - VALID flat / compound / parallel / history / timeout / choice /
      spawn / spawn-all / namespaced-extension definitions still PROJECT;
    - the defect diagnostics are VALUE-FREE (never a `:data` slot's live
      values, an action / guard, or an LLM response).

  Engine-parity (the same accept/reject as `validate-machine!`) is pinned
  separately in `engine-grammar-parity-test` (which alone `:require`s the
  test-only engine dep)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.grammar :as g]))

(defn- category [definition]
  (:category (g/definition-defect definition)))

;; ---------------------------------------------------------------------------
;; The three deterministic false positives (bead report §DETERMINISTIC
;; REPRODUCTION). Pre-fix each returned `valid-definition? => true`; each is
;; now rejected with the SAME `:rf.error/machine-*` id the runtime
;; `validate-machine!` raises.

(deftest deterministic-false-positives-now-rejected
  (testing "nested compound missing :initial => machine-compound-state-missing-initial"
    (let [d {:initial :outer :states {:outer {:states {:inner {}}}}}]
      (is (false? (g/valid-definition? d)))
      (is (= :rf.error/machine-compound-state-missing-initial (category d)))
      (is (= [:outer] (:path (g/definition-defect d))))))

  (testing "dangling transition target => machine-unresolved-target"
    (let [d {:initial :idle :states {:idle {:on {:go :missing}}}}]
      (is (false? (g/valid-definition? d)))
      (is (= :rf.error/machine-unresolved-target (category d)))
      (is (= [:idle] (:path (g/definition-defect d))))))

  (testing "unknown bare node key => machine-unknown-node-key"
    (let [d {:initial :idle :states {:idle {:on-entry :oops}}}]
      (is (false? (g/valid-definition? d)))
      (is (= :rf.error/machine-unknown-node-key (category d)))
      (is (= [:idle] (:path (g/definition-defect d))))
      (is (= [:on-entry] (:keys (g/definition-defect d)))))))

;; ---------------------------------------------------------------------------
;; Root / nested-compound / parallel-region initial presence + type + resolution

(deftest compound-initial-presence-is-recursive
  (testing "a DEEPLY-nested compound missing :initial is rejected (the shallow
            check never descended past the root / region roots)"
    (is (= :rf.error/machine-compound-state-missing-initial
           (category {:initial :a
                      :states  {:a {:initial :b
                                    :states  {:b {:states {:c {}}}}}}}))))
  (testing "a compound missing :initial INSIDE a parallel region is rejected"
    (is (= :rf.error/machine-compound-state-missing-initial
           (category {:type    :parallel
                      :regions {:r {:initial :a
                                    :states  {:a {:states {:b {}}}}}}})))))

(deftest parallel-region-initial-type-and-presence
  (testing "a region whose :initial is a STRING (not a keyword) is rejected"
    (is (= :rf.error/machine-parallel-bad-shape
           (category {:type :parallel :regions {:r {:initial "x" :states {:x {}}}}}))))
  (testing "a region with no :initial is rejected"
    (is (= :rf.error/machine-parallel-bad-shape
           (category {:type :parallel :regions {:r {:states {:x {}}}}}))))
  (testing ":type :parallel mutually exclusive with :initial / :states"
    (is (= :rf.error/machine-parallel-bad-shape
           (category {:type :parallel :initial :a
                      :regions {:r {:initial :a :states {:a {}}}}})))
    (is (= :rf.error/machine-parallel-bad-shape
           (category {:type :parallel :states {:a {}}
                      :regions {:r {:initial :a :states {:a {}}}}}))))
  (testing "an empty / non-map :regions is rejected"
    (is (= :rf.error/machine-parallel-bad-shape (category {:type :parallel :regions {}})))
    (is (= :rf.error/machine-parallel-bad-shape (category {:type :parallel :regions 5}))))
  (testing "a nested parallel region is rejected"
    (is (= :rf.error/machine-parallel-nested-not-supported
           (category {:type    :parallel
                      :regions {:r {:initial :a
                                    :states  {:a {:type    :parallel
                                                  :regions {:x {:initial :y :states {:y {}}}}}}}}})))))

;; ---------------------------------------------------------------------------
;; Empty / non-map state bodies

(deftest empty-and-non-map-state-bodies
  (testing "a non-map definition is rejected"
    (is (= :rf.error/machine-bad-definition (category 42)))
    (is (= :rf.error/machine-bad-definition (category "not-a-machine"))))
  (testing "a flat root with a non-keyword / missing :initial is rejected"
    (is (= :rf.error/machine-missing-initial (category {:states {:a {}}})))
    (is (= :rf.error/machine-missing-initial (category {:initial "idle" :states {:idle {}}}))))
  (testing "a flat root with empty / non-map :states is rejected"
    (is (= :rf.error/machine-missing-states (category {:initial :a :states {}})))
    (is (= :rf.error/machine-missing-states (category {:initial :a :states 7})))))

;; ---------------------------------------------------------------------------
;; Unknown bare keys — with NAMESPACED-extension acceptance

(deftest unknown-bare-keys-vs-namespaced-extension
  (testing "an unknown BARE node key is rejected (XState's :on-entry / :invoke)"
    (is (= :rf.error/machine-unknown-node-key (category {:initial :a :states {:a {:on-entry :x}}})))
    (is (= :rf.error/machine-unknown-node-key (category {:initial :a :states {:a {:invoke {:src :x}}}}))))
  (testing "an unknown BARE key on the machine ROOT is rejected"
    (is (= :rf.error/machine-unknown-node-key (category {:initial :a :innitial :a :states {:a {}}}))))
  (testing "a NAMESPACED key is the open extension carve-out and PASSES"
    (is (nil? (g/definition-defect {:initial :a :states {:a {:my.app/note "meta"}}})))
    (is (nil? (g/definition-defect {:initial :a :my.tool/x 1 :states {:a {}}}))))
  (testing ":meta is the sanctioned bare free slot and PASSES"
    (is (nil? (g/definition-defect {:initial :a :states {:a {:meta {:doc "x"}}}}))))
  (testing "an unknown BARE :spawn key is rejected; namespaced passes"
    (is (= :rf.error/machine-unknown-spawn-key
           (category {:initial :a :states {:a {:spawn {:machine-id :m :bogus 1}}}})))
    (is (nil? (g/definition-defect {:initial :a :states {:a {:spawn {:machine-id :m :my/x 1}}}})))))

;; ---------------------------------------------------------------------------
;; Malformed / dangling keyword AND vector targets

(deftest transition-target-shape-and-resolution
  (testing "a dangling KEYWORD target (a top-level state from a nested state
            needs a vector) is unresolved"
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :o :states {:o {:initial :i :states {:i {:on {:up :top}}}} :top {}}}))))
  (testing "a dangling VECTOR-PATH target is unresolved"
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :a :states {:a {:on {:go [:nope]}}}}))))
  (testing "an EMPTY vector target is a malformed shape"
    (is (= :rf.error/machine-bad-target (category {:initial :a :states {:a {:on {:go []}}}}))))
  (testing "a non-keyword / non-vector :target map value is a malformed shape"
    (is (= :rf.error/machine-bad-target (category {:initial :a :states {:a {:on {:go {:target 42}}}}}))))
  (testing "the target check covers :after / :always / :on-done / :spawn :on-error, not just :on"
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :a :states {:a {:after {1000 {:target :nope}}}}})))
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :a :states {:a {:always [{:target :nope}]}}})))
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :a :states {:a {:initial :b
                                               :states {:b {:final? true}}
                                               :on-done {:target :nope}}}})))
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :a :states {:a {:spawn {:machine-id :m :on-error {:target :nope}}}}}))))
  (testing "a VALID vector-path / sibling / :same-state target is accepted"
    (is (nil? (g/definition-defect {:initial :o :states {:o {:initial :i :states {:i {:on {:up [:top]}}}} :top {}}})))
    (is (nil? (g/definition-defect {:initial :a :states {:a {:on {:go :b}} :b {}}})))
    (is (nil? (g/definition-defect {:initial :a :states {:a {:on {:loop :same-state}}}})))))

;; ---------------------------------------------------------------------------
;; History pseudo-state structural constraints

(deftest history-pseudo-state-constraints
  (testing "a history node with no owning compound is misplaced"
    (is (= :rf.error/machine-history-misplaced
           (category {:initial :a :states {:a {} :hist {:type :history}}}))))
  (testing "a history node carrying a non-history key is extra-keys"
    (is (= :rf.error/machine-history-extra-keys
           (category {:initial :o :states {:o {:initial :s
                                               :states {:s {} :h {:type :history :on {:x :s}}}}}}))))
  (testing "two history pseudo-states under one compound is a duplicate"
    (is (= :rf.error/machine-history-duplicate
           (category {:initial :o :states {:o {:initial :s
                                               :states {:s {} :h1 {:type :history} :h2 {:type :history}}}}}))))
  (testing "a :default-target that resolves is accepted; a dangling one is rejected"
    (is (nil? (g/definition-defect {:initial :o :states {:o {:initial :s
                                                             :states {:s {} :h {:type :history :default-target :s}}}}})))
    (is (= :rf.error/machine-history-bad-default-target
           (category {:initial :o :states {:o {:initial :s
                                               :states {:s {} :h {:type :history :default-target :nope}}}}})))))

;; ---------------------------------------------------------------------------
;; Final-state / tags / after-delay / spawn structural constraints

(deftest final-state-constraints
  (is (= :rf.error/machine-final-state-compound
         (category {:initial :a :states {:a {:final? true :states {:b {}}}}})))
  (is (= :rf.error/machine-final-state-has-transitions
         (category {:initial :a :states {:a {:final? true :on {:x :b}} :b {}}})))
  (is (= :rf.error/machine-output-key-without-final
         (category {:initial :a :states {:a {:output-key :foo}}})))
  (is (= :rf.error/machine-error-flag-without-final
         (category {:initial :a :states {:a {:error? true}}})))
  (testing "a :final? leaf may carry :output-key / :error? / :entry / :exit"
    (is (nil? (g/definition-defect {:initial :a :states {:a {:on {:go :b}}
                                                          :b {:final? true :output-key :out :error? true}}})))))

(deftest tags-constraint
  (is (= :rf.error/machine-bad-tags (category {:initial :a :states {:a {:tags [:x]}}})))
  (is (= :rf.error/machine-bad-tags (category {:initial :a :states {:a {:tags :busy}}})))
  (is (= :rf.error/machine-bad-tags (category {:initial :a :states {:a {:tags #{:ok "bad"}}}})))
  (is (nil? (g/definition-defect {:initial :a :states {:a {:tags #{:busy :loading}}}}))))

(deftest after-delay-key-constraint
  (is (= :rf.error/machine-bad-after-delay (category {:initial :a :states {:a {:after {0 :b}} :b {}}})))
  (is (= :rf.error/machine-bad-after-delay (category {:initial :a :states {:a {:after {"soon" :b}} :b {}}})))
  (is (nil? (g/definition-defect {:initial :a :states {:a {:after {1000 :b}} :b {}}}))))

(deftest spawn-xor-constraint
  (testing "a :spawn declaring NEITHER :machine-id nor :definition is rejected"
    (is (= :rf.error/machine-spawn-bad-shape (category {:initial :a :states {:a {:spawn {}}}}))))
  (testing "a :spawn declaring BOTH is rejected"
    (is (= :rf.error/machine-spawn-bad-shape
           (category {:initial :a :states {:a {:spawn {:machine-id :m
                                                       :definition {:initial :x :states {:x {}}}}}}}))))
  (testing "a :spawn declaring EXACTLY ONE is accepted"
    (is (nil? (g/definition-defect {:initial :a :states {:a {:spawn {:machine-id :m}}}})))))

;; ---------------------------------------------------------------------------
;; Choice / timeout are validated on their LOWERED (desugared) shape

(deftest choice-and-timeout-validated-on-lowered-shape
  (testing "a :type :choice whose candidate targets a MISSING state is rejected
            (validated on the lowered :always form)"
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :g :states {:g {:type :choice :choice [{:target :nope}]} :a {}}}))))
  (testing "a VALID :type :choice projects"
    (is (nil? (g/definition-defect {:initial :g :states {:g {:type :choice
                                                             :choice [{:target :a} {:target :b}]}
                                                          :a {} :b {}}}))))
  (testing "a state :timeout whose :on-timeout targets a MISSING state is rejected
            (validated on the lowered :after form)"
    (is (= :rf.error/machine-unresolved-target
           (category {:initial :a :states {:a {:timeout 1000 :on-timeout :nope}}}))))
  (testing "a VALID state :timeout projects"
    (is (nil? (g/definition-defect {:initial :a :states {:a {:timeout 1000 :on-timeout :b} :b {}}})))))

;; ---------------------------------------------------------------------------
;; VALID definitions continue to PROJECT (rf2-j538f7.18 acceptance #6)

(def ^:private valid-definitions
  {:flat       {:initial :idle :states {:idle {:on {:go :done}} :done {:final? true}}}
   :compound   {:initial :o :states {:o {:initial :i :states {:i {:on {:up :sib}} :sib {}}} :top {}}}
   :vec-target {:initial :o :states {:o {:initial :i :states {:i {:on {:esc [:top]}}}} :top {}}}
   :parallel   {:type :parallel :regions {:r1 {:initial :a :states {:a {:on {:x :b}} :b {}}}
                                          :r2 {:initial :p :states {:p {}}}}}
   :history    {:initial :o :states {:o {:initial :s :states {:s {:on {:g :s2}} :s2 {} :h {:type :history :deep? true}}}}}
   :timeout    {:initial :a :states {:a {:timeout 1000 :on-timeout :b} :b {}}}
   :choice     {:initial :g :states {:g {:type :choice :choice [{:target :a} {:target :b}]} :a {} :b {}}}
   :spawn      {:initial :a :states {:a {:spawn {:machine-id :child} :on {:go :b}} :b {}}}
   :spawn-all  {:initial :a :states {:a {:spawn-all {:children [{:id :c1 :machine-id :m}]
                                                     :on-child-done :cd :on-child-error :ce
                                                     :on-all-complete [:done]}
                                         :on {:go :b}} :b {}}}
   :namespaced {:initial :a :states {:a {:my.app/note "x"}}}})

(deftest valid-definitions-accepted
  (doseq [[label d] valid-definitions]
    (is (true? (g/valid-definition? d)) (str label " is accepted"))
    (is (nil? (g/definition-defect d))  (str label " carries no defect"))))

;; ---------------------------------------------------------------------------
;; Value-FREE diagnostics (rf2-8nzxib / EP-0015): the defect + summary carry
;; STRUCTURAL facts only — never a :data slot's live values, an action / guard,
;; or any raw value.

(deftest defect-diagnostics-are-value-free
  (let [definition {:initial :a
                    :data    {:secret "TOP-SECRET-TOKEN" :password "hunter2"}
                    :states  {:a {:on-entry (fn secret-action [_] :SECRET-RETURN)}}}
        defect     (g/definition-defect definition)
        summary    (g/definition-summary definition)]
    (testing "the definition is rejected (unknown bare :on-entry key)"
      (is (= :rf.error/machine-unknown-node-key (:category defect))))
    (testing "the defect carries only structural facts (category / path / keys)"
      (is (= [:a] (:path defect)))
      (is (= [:on-entry] (:keys defect))))
    (testing "no live :data value, password, or action return leaks into the diagnostics"
      (doseq [needle ["TOP-SECRET-TOKEN" "hunter2" "SECRET-RETURN"]]
        (is (not (str/includes? (pr-str defect) needle))
            (str "defect must not leak " needle))
        (is (not (str/includes? (pr-str summary) needle))
            (str "summary must not leak " needle))))
    (testing "definition-summary embeds the canonical defect category so every
              surface that stashes it carries the same category"
      (is (= :rf.error/machine-unknown-node-key (get-in summary [:defect :category]))))))

;; ---------------------------------------------------------------------------
;; rf2-qgtcvy — injective id codec: fixed-width escape, reversible + collision-
;; free across the whole UTF-16 code-unit range (the pre-fix `_<var-hex>` was
;; neither self-delimiting nor reversible above 0xFF).

(deftest escape-id-segment-fixed-width-and-injective
  (testing "≤ U+00FF keeps the 2-hex `_XX` form (Latin-1 golden ids unchanged)"
    (is (= "_2f" (g/escape-id-segment "/")))
    (is (= "_2d" (g/escape-id-segment "-")))
    (is (= "_5f" (g/escape-id-segment "_")))
    (is (= "_3f" (g/escape-id-segment "?")))
    (is (= "_11" (g/escape-id-segment (str (char 0x11))))))
  (testing "> U+00FF uses the self-delimiting `_u<4-hex>` form"
    (is (= "_u5f00" (g/escape-id-segment "开")))      ;; 开
    (is (= "_u59cb" (g/escape-id-segment "始")))      ;; 始
    (is (= "_u0111" (g/escape-id-segment "đ"))))     ;; đ
  (testing "the pre-fix collision is gone: đ (U+0111) vs \\u0011 + \"1\""
    (is (not= (g/escape-id-segment "đ")
              (g/escape-id-segment (str (char 0x11) "1")))
        "distinct inputs must mint distinct ids"))
  (testing "no two consecutive underscores, so the `__`/`___`/`_2f` markers
            can never arise from segment content"
    (is (not (str/includes? (g/escape-id-segment "开始") "__")))
    (is (not (str/includes? (g/escape-id-segment "đ") "__"))))
  (testing "distinct segment strings always mint distinct escapes (injective)"
    (let [segs ["/" "-" "_" "?" (str (char 0x11)) "đ" "开" "始"
                (str (char 0x11) "1") "a-b" "a/b" "a_b"]]
      (is (= (count segs) (count (distinct (map g/escape-id-segment segs))))))))

;; rf2-qgtcvy — a non-MAP `:on` / `:after` (e.g. `{:on :retry}` from an LLM)
;; must return the clean slot-specific defect, NOT throw an uncaught ISeq
;; exception (which bypassed the emit paths' `:invalid-definition` promise).

(deftest non-map-on-after-rejected-cleanly
  (testing "a non-map `:on` yields :rf.error/machine-bad-on-clause (no throw)"
    (is (= :rf.error/machine-bad-on-clause
           (category {:initial :a :states {:a {:on :retry}}})))
    (is (= :rf.error/machine-bad-on-clause
           (category {:initial :a :states {:a {:on [:retry]}}}))))
  (testing "a non-map `:after` yields :rf.error/machine-bad-after-spec (no throw)"
    (is (= :rf.error/machine-bad-after-spec
           (category {:initial :a :states {:a {:after :later}}}))))
  (testing "`valid-definition?` returns false (rather than throwing) for both"
    (is (false? (g/valid-definition? {:initial :a :states {:a {:on :retry}}})))
    (is (false? (g/valid-definition? {:initial :a :states {:a {:after 500}}}))))
  (testing "a well-formed MAP `:on` / `:after` is still accepted"
    (is (nil? (g/definition-defect {:initial :a :states {:a {:on {:go :b}} :b {}}})))
    (is (nil? (g/definition-defect {:initial :a :states {:a {:after {500 :b}} :b {}}})))))

;; rf2-bj3sxo — the SAME slot-shape rule must cover the FALLBACK `:on` /
;; `:after` at every root scope (flat root, region root, parallel root), not
;; only ordinary state nodes. Pre-fix `root-on-target-defect` iterated the flat
;; root `:on` with NO shape guard, so `{:initial :a :states {:a {}} :on :retry}`
;; threw an uncaught ISeq exception out of `valid-definition?` / the emitters
;; instead of returning the catalogued defect.

(deftest non-map-root-fallback-on-rejected-cleanly
  (testing "a malformed FLAT-ROOT `:on` (`{… :on :retry}`) returns
            :rf.error/machine-bad-on-clause at path [] — NOT a host exception"
    (let [d {:initial :a :states {:a {}} :on :retry}]
      (is (= :rf.error/machine-bad-on-clause (category d)))
      (is (= [] (:path (g/definition-defect d))))
      (is (false? (g/valid-definition? d)))))
  (testing "a malformed flat-root `:after` returns :rf.error/machine-bad-after-spec"
    (is (= :rf.error/machine-bad-after-spec
           (category {:initial :a :states {:a {}} :after :later}))))
  (testing "a malformed REGION-ROOT `:on` returns the same defect at the region path"
    (let [d {:type :parallel
             :regions {:r {:initial :a :states {:a {}} :on :retry}}}]
      (is (= :rf.error/machine-bad-on-clause (category d)))
      (is (= [:r] (:path (g/definition-defect d))))))
  (testing "a malformed PARALLEL-ROOT `:on` returns the same defect at path []"
    (let [d {:type :parallel
             :on :retry
             :regions {:r {:initial :a :states {:a {}}}}}]
      (is (= :rf.error/machine-bad-on-clause (category d)))
      (is (= [] (:path (g/definition-defect d))))))
  (testing "the defect is VALUE-FREE — the malformed application value never leaks"
    ;; a NON-map `:on` carrying a secret payload rejects with only the
    ;; structural category + path; the offending value never rides the defect.
    (let [defect (g/definition-defect {:initial :a :states {:a {}}
                                       :on [:secret-token "leak-me-42"]})]
      (is (= :rf.error/machine-bad-on-clause (:category defect)))
      (is (= #{:category :path} (set (keys defect)))
          "the defect carries ONLY :category + :path — no offending value")
      (is (not (some #(and (string? %) (str/includes? % "leak-me-42"))
                     (tree-seq coll? seq defect)))
          "the malformed application value must not appear anywhere in the defect")))
  (testing "a well-formed MAP root fallback `:on` / `:after` still PROJECTS
            (handler normalization + ancestor-fallback precedence preserved)"
    ;; flat root `:on` fallback (every state inherits :logout)
    (is (nil? (g/definition-defect {:initial :a :on {:logout :a}
                                    :states {:a {:on {:go :b}} :b {}}})))
    ;; parallel root `:on` fallback (region-qualified target grammar)
    (is (nil? (g/definition-defect {:type :parallel
                                    :on {:one [:a :two]}
                                    :regions {:a {:initial :one :states {:one {} :two {}}}
                                              :b {:initial :one :states {:one {} :two {}}}}})))))
