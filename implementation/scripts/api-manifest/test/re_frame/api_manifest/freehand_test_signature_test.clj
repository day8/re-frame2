(ns re-frame.api-manifest.freehand-test-signature-test
  "The JVM (:clj) lane of the `re-frame.freehand.test` HOST-SIGNATURE guard
  (rf2-drpa3.99) — the SIBLING of the `re-frame.ui.test` signature guard
  (rf2-5bcdi / rf2-d7sso), reusing the SAME pure reconciler.

  THE HOLE. `re-frame.freehand.test` is enrolled in the manifest (its
  `freehand-test-enrolment-test` sibling proves a rename / removal / accidental
  export reddens the gate). But the generated row reduces every var to
  `[namespace var tier kind]` — it carries NO arity. So a freehand.test fn or
  macro could keep its NAME and its `:kind` while losing, adding, or reshaping a
  supported arity, and the manifest / `gen --check` / enrolment gates all stayed
  green. The live-generator reproduction changed `render`'s metadata from
  `([form])` to `([form opts])` and every gate stayed green — so `(t/render
  form)` could silently become a compile/runtime break the public-API boundary
  never detected. The namespace's own docstring promises that a signature change
  reddens the gate; before this lane, it did not.

  THE MECHANISM, REUSED. This does NOT build a second reconciler. It reads the
  live JVM surface of the six blessed vars (`ns-publics` — the surface runs
  headless on the JVM, so it is on the generator classpath) and hands it, with
  the committed `:freehand-test-signatures` authority, to the SAME pure
  reconciler the ui.test lane uses — `api-md-check/ui-test-arity-problems` — plus
  the same kind-aware `api-md-check/arglists->arity` normalization. The sidecar's
  declared `:kind` is reconciled against the live Var kind (never trusted to
  select checks); a `:fn` arity reshape / add / removal is `:arity-mismatch` /
  `:var-absent`; a fresh uncontracted export is `:uncontracted-var`; and the one
  macro (`with-render`) has its `:clj` pinned to the live `:arglists` and its
  `:cljs` required equal (`:macro-host-variance`).

  All six names are host-identical `.cljc` (Spec 008) — no CLJS-only (`:clj nil`)
  verb, unlike ui.test's `flush!` / `flush-presence!` — so every var is JVM-live
  and every `:clj` arity is reconciled here. The CLJS half of the same guard
  lives in the api-manifest probe (`probe/test/.../cljs_manifest_probe_cljs_test.cljs`)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.api-md-check :as c]
            [re-frame.api-manifest.gen :as gen]))

(def ^:private freehand-test-ns "re-frame.freehand.test")

(def ^:private blessed-names
  "The six blessed names — the whole public surface (everything beneath them is
   `defn-`). The five queries are `defn`s; `with-render` is the `defmacro`
   bracket."
  #{"render" "find" "find-all" "attrs" "text" "with-render"})

;; ---------------------------------------------------------------------------
;; Live JVM surface — the freehand.test twin of api-md-check/live-ui-test-surface,
;; built by REUSING api-md-check's public kind-aware arity normalization
;; (`arglists->arities`). The `:kind` derivation mirrors gen/kind-of exactly (both
;; gen/kind-of and api-md-check/live-kind-of are private, so the three-line cond
;; is replicated — the arity MECHANISM, the part that matters, is reused verbatim).
;; ---------------------------------------------------------------------------

(defn- kind-of
  "The manifest `:kind` of a live JVM Var (mirrors `gen/kind-of` /
   `api-md-check/live-kind-of` — the AUTHORITATIVE live classification the
   sidecar's declared kind is reconciled against)."
  [v]
  (let [m (meta v)]
    (cond
      (:macro m)                  :macro
      (or (:arglists m) (fn? @v)) :fn
      :else                       :var)))

(defn live-freehand-test-surface
  "Live JVM `{var-name-string {:kind kw :arities #{arity-vector ...}}}` for the
   public, non-`^:no-doc` vars of `re-frame.freehand.test`. `:kind` is the live-Var
   classification; `:arities` is derived KIND-AWARELY via the reused
   `api-md-check/arglists->arities`."
  []
  (let [ns-sym (symbol freehand-test-ns)]
    (require ns-sym)
    (into {}
          (for [[sym v] (ns-publics ns-sym)
                :when (not (:no-doc (meta v)))
                :let  [kind (kind-of v)]]
            [(name sym) {:kind    kind
                         :arities (c/arglists->arities kind (:arglists (meta v)))}]))))

(defn- contract []
  (:vars (:freehand-test-signatures (gen/read-sidecar))))

;; ---------------------------------------------------------------------------
;; In-sync + non-vacuity.
;; ---------------------------------------------------------------------------

(deftest live-jvm-signature-matches-the-committed-contract
  (testing "the committed :freehand-test-signatures contract reconciles clean
            against the LIVE re-frame.freehand.test JVM surface — no live drift.
            All six names are host-identical (every `:clj` non-nil), so the live
            JVM surface is EXACTLY the contract's vars"
    (let [contract (contract)
          surface  (live-freehand-test-surface)]
      (is (= 6 (count contract))
          "the signature authority carries the six blessed vars")
      (is (= blessed-names (set (keys contract)))
          "the contract names exactly the blessed surface")
      (is (every? some? (map :clj (vals contract)))
          "every freehand.test var is host-identical — no CLJS-only (:clj nil) verb")
      (is (= blessed-names (set (keys surface)))
          "the live JVM surface is exactly the six blessed vars")
      (is (= #{[1]} (get-in surface ["render" :arities]))
          "render is live 1-arity — the contract the reproduction ([form opts]) broke")
      (is (= :macro (get-in surface ["with-render" :kind]))
          "with-render is the live defmacro bracket")
      (is (= #{[0 :&]} (get-in surface ["with-render" :arities]))
          "with-render's compiler-internal &form/&env are stripped to the visible [0 :&]")
      (is (empty? (c/ui-test-arity-problems contract surface))
          "live drift: a freehand.test var's JVM signature disagrees with the contract"))))

;; ---------------------------------------------------------------------------
;; The mutation controls — every direction goes RED, restored stays green.
;; Each mutates the LIVE surface (or the contract) and reconciles it through the
;; reused pure reconciler, so name + :kind stay put while the arity/kind drifts.
;; ---------------------------------------------------------------------------

(deftest render-arity-reshape-goes-red
  (testing "THE REPRODUCTION (rf2-drpa3.99): render reshaped from 1-arity to
            2-arity ([form] → [form opts]) fails against :clj #{[1]}, while its
            name + :kind (a :fn) are unchanged — the exact false-green every
            prior gate let through"
    (let [problems (c/ui-test-arity-problems
                    (contract)
                    (assoc-in (live-freehand-test-surface) ["render" :arities] #{[1] [2]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= #{[1]} (:expected (first problems))))
      (is (= #{[1] [2]} (:got (first problems)))))))

(deftest added-jvm-arity-goes-red
  (testing "ADDING a supported arity (text gains a 2-arity) fails — a superset is
            drift, not a pass"
    (let [problems (c/ui-test-arity-problems
                    (contract)
                    (assoc-in (live-freehand-test-surface) ["text" :arities] #{[1] [2]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "text" (:var (first problems)))))))

(deftest binary-finder-arity-drop-goes-red
  (testing "find losing its second parameter (a `[tree pred]` → `[tree]` reshape)
            fails against :clj #{[2]} — the finder call contract the manifest
            cannot see"
    (let [problems (c/ui-test-arity-problems
                    (contract)
                    (assoc-in (live-freehand-test-surface) ["find" :arities] #{[1]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "find" (:var (first problems))))
      (is (= #{[2]} (:expected (first problems))))
      (is (= #{[1]} (:got (first problems)))))))

(deftest with-render-macro-losing-variadic-goes-red
  (testing "with-render reshaped from variadic [0 :&] to a fixed [1] fails — the
            `& body` grammar drift the tier/kind reconcile cannot see, pinned
            against the live macro :arglists"
    (let [problems (c/ui-test-arity-problems
                    (contract)
                    (assoc-in (live-freehand-test-surface) ["with-render" :arities] #{[1]}))]
      (is (= [:arity-mismatch] (map :kind problems)))
      (is (= "with-render" (:var (first problems))))
      (is (= #{[0 :&]} (:expected (first problems))))
      (is (= #{[1]} (:got (first problems)))))))

(deftest with-render-cljs-only-mutation-goes-red
  (testing "with-render is one .cljc defmacro, so its :clj and :cljs halves cannot
            differ — mutating the :cljs half alone is host-variance a single
            defmacro cannot have, and is RED (the live :clj arities still match,
            so ONLY the host-variance fires)"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in (contract) ["with-render" :cljs] #{[3 :&]})
                    (live-freehand-test-surface))]
      (is (= [:macro-host-variance] (map :kind problems)))
      (is (= "with-render" (:var (first problems))))
      (is (= #{[0 :&]} (:expected (first problems))) "the :clj half")
      (is (= #{[3 :&]} (:got (first problems)))      "the mutated :cljs half"))))

(deftest jvm-sidecar-kind-flip-goes-red
  (testing "a sidecar entry whose :kind was flipped :fn→:macro is REJECTED against
            the live JVM Var kind (:fn) — the declared kind is reconciled, never
            trusted (its arities still match, so ONLY the kind mismatch fires)"
    (let [problems (c/ui-test-arity-problems
                    (assoc-in (contract) ["render" :kind] :macro)
                    (live-freehand-test-surface))]
      (is (= [:kind-mismatch] (map :kind problems)))
      (is (= "render" (:var (first problems))))
      (is (= :macro (:declared (first problems))))
      (is (= :fn (:live-kind (first problems)))))))

(deftest removed-var-flagged-absent
  (testing "a contract var whose live Var no longer resolves is :var-absent
            (belt-and-braces alongside the enrolment existence guard)"
    (let [problems (c/ui-test-arity-problems
                    (contract)
                    (dissoc (live-freehand-test-surface) "attrs"))]
      (is (= [:var-absent] (map :kind problems)))
      (is (= "attrs" (:var (first problems)))))))

(deftest new-uncontracted-var-flagged
  (testing "a NEW live blessed var with no signature entry is :uncontracted-var —
            a fresh export cannot escape arity coverage silently"
    (let [problems (c/ui-test-arity-problems
                    (contract)
                    (assoc (live-freehand-test-surface) "click!" {:kind :fn :arities #{[1]}}))]
      (is (= [:uncontracted-var] (map :kind problems)))
      (is (= "click!" (:var (first problems)))))))

(deftest restored-surface-is-green
  (testing "POSITIVE CONTROL: the committed contract reconciles clean against the
            live surface, so the reds above are the mutation talking and not a
            standing failure"
    (is (empty? (c/ui-test-arity-problems (contract) (live-freehand-test-surface))))))
