(ns re-frame.story-fingerprint-cljs-test
  "CLJS host-portability companion for the canonical fingerprint primitive
  (rf2-5x1wt.3). The JVM file `re-frame.story-fingerprint-test` carries the
  full adversarial corpus + projection/plan/run-hash coverage; this file
  pins the cross-host invariants that only matter on CLJS:

  - the 8-char-hex hash renders identically (left-padded) on CLJS;
  - volatile-strip equivalence and semantic sensitivity hold under the
    CLJS `hash` + `pr-str`;
  - the snapshot-identity content-hash fold is strip-free on CLJS too;
  - the rf2-5x1wt.8 per-run stamp strip (epoch-record `:frame`, trace-event
    `:id` / `:time` / volatile tags) is host-portable too."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.fingerprint :as fp]
            [re-frame.story.identity    :as ident]))

(def ^:private base
  {:status :pass
   :variant/id :story.x/v
   :runner :headless
   :elapsed-ms 3.0
   :plan-hash "abc"
   :app-db {:n 1 :rf.story/lifecycle :ready}
   :effects [{:effect :rf/db :dispatch-id "d-1"}]
   :assertions [{:assertion :rf.assert/path-equals :status :pass
                 :source "f.cljs:1" :elapsed-ms 0.1}]})

(def ^:private volatile-twin
  (-> base
      (assoc :elapsed-ms 99.0 :runner :dom :plan-hash "zzz")
      (assoc-in [:app-db :rf.story/lifecycle] :error)
      (assoc-in [:effects 0 :dispatch-id] "z-9")
      (assoc-in [:assertions 0 :source] "g.cljs:7")
      (assoc-in [:assertions 0 :elapsed-ms] 42.0)))

(deftest content-hash-is-8-char-hex-on-cljs
  (testing "content-hash renders a left-padded 8-char lowercase hex string"
    (let [h (fp/content-hash {:a 1})]
      (is (string? h))
      (is (= 8 (count h)))
      (is (re-matches #"[0-9a-f]{8}" h)))))

(deftest order-insensitive-on-cljs
  (testing "map key order does not affect the CLJS hash"
    (is (= (fp/content-hash {:a 1 :b 2}) (fp/content-hash {:b 2 :a 1}))))
  (testing "set element order does not affect the CLJS hash"
    (is (= (fp/content-hash #{:x :y :z}) (fp/content-hash #{:z :y :x})))))

(deftest volatile-equivalence-on-cljs
  (testing "volatile-only differences canonicalize = and run-hash equal"
    (is (= (fp/canonicalize base) (fp/canonicalize volatile-twin)))
    (is (= (fp/run-hash base) (fp/run-hash volatile-twin)))))

(deftest semantic-difference-on-cljs
  (testing "a semantic app-db difference perturbs the run-hash on CLJS"
    (is (not= (fp/run-hash base)
              (fp/run-hash (assoc-in base [:app-db :n] 2))))))

(deftest determinism-stamp-strip-on-cljs
  (testing "epoch records differing only in per-run stamps canonicalize = on CLJS
            (rf2-5x1wt.8): :epoch-id / :frame / :committed-at / :schema-digest"
    (let [rec (fn [eid frame]
                {:epoch-id eid :frame frame :committed-at 1 :schema-digest "d"
                 :outcome :ok :db-after {:n 1} :trace-events [] :effects []})]
      (is (= (fp/canonicalize (rec 5 :rf.test.replay/frame-a))
             (fp/canonicalize (rec 90 :rf.test.replay/frame-z))))
      (is (= (fp/canonical-hash (rec 5 :rf.test.replay/frame-a))
             (fp/canonical-hash (rec 90 :rf.test.replay/frame-z))))
      (is (not= (fp/canonicalize (rec 5 :rf.test.replay/frame-a))
                (fp/canonicalize (assoc (rec 5 :rf.test.replay/frame-a)
                                        :db-after {:n 2})))
          "a real db-after difference still perturbs the canonical value")))

  (testing "trace events differing only in :id / :time / volatile tags
            canonicalize = on CLJS; the event-id tag is semantic"
    (let [ev (fn [id frame]
               {:operation :rf.event/run-start :op-type :event :id id :time id
                :tags {:rf.trace/event-id :foo :frame frame
                       :rf.trace/dispatch-id id}})]
      (is (= (fp/canonicalize {:trace-events [(ev 1 :rf.test.replay/frame-a)]})
             (fp/canonicalize {:trace-events [(ev 9 :rf.test.replay/frame-z)]})))
      (is (not= (fp/canonicalize {:trace-events [(ev 1 :rf.test.replay/frame-a)]})
                (fp/canonicalize
                  {:trace-events [(assoc-in (ev 1 :rf.test.replay/frame-a)
                                            [:tags :rf.trace/event-id] :bar)]}))
          "the event-id tag is behavioural, not a stamp")))

  (testing ":id / :time / :frame as plain app-db values are NOT stripped on CLJS"
    (is (not= (fp/canonicalize {:status :pass :app-db {:id 1 :time 10 :frame :l}})
              (fp/canonicalize {:status :pass :app-db {:id 2 :time 20 :frame :r}}))
        "semantic app-db data on common keys survives canonicalization")))

(deftest collection-types-do-not-collide-on-cljs
  (testing "map / set / vector type tags keep the kinds distinct on CLJS
            (rf2-lvrqa) — host-portable structural tagging"
    (is (not= (fp/content-hash {}) (fp/content-hash [])))
    (is (not= (fp/content-hash #{}) (fp/content-hash [])))
    (is (not= (fp/content-hash {:k 1}) (fp/content-hash [:k 1])))
    (is (not= (fp/content-hash #{:k}) (fp/content-hash [:k])))
    (is (not= (fp/canonical-hash {:effects [{:k 1}]})
              (fp/canonical-hash {:effects [[:k 1]]})))))

(deftest fn-slot-deterministic-on-cljs
  (testing "a fn folds to the opaque sentinel on CLJS (rf2-4gwja) — a JS fn
            in a hashed slot hashes stably, keywords/colls are not folded"
    (is (= fp/opaque-fn (fp/canonical-form (fn [] 1))))
    (is (= (fp/run-hash {:status :pass :app-db {:cb (fn [] 1)}})
           (fp/run-hash {:status :pass :app-db {:cb (fn [] 1)}})))
    (is (not= fp/opaque-fn (fp/canonical-form :kw)))
    (is (not= fp/opaque-fn (fp/canonical-form #{:a})))))

(deftest snapshot-fold-strip-free-on-cljs
  (testing "identity content-hash IS the fingerprint content-hash (folded)"
    (is (identical? ident/content-hash fp/content-hash)))
  (testing "content-hash keeps :variant-id sensitivity; canonical-hash strips it"
    (let [tuple {:variant-id :story.x/v :variant {:a 1}}]
      (is (not= (fp/content-hash tuple)
                (fp/content-hash (assoc tuple :variant-id :story.y/v))))
      (is (= (fp/canonical-hash tuple)
             (fp/canonical-hash (assoc tuple :variant-id :story.y/v)))))))

;; ===========================================================================
;; CROSS-HOST SCALAR STABILITY (rf2-vvqeo) — the CLJS side of the equivalence
;; ===========================================================================
;;
;; These canonical-form + content-hash literals are EXACTLY those asserted on
;; the JVM (re-frame.story-fingerprint-test): the JVM Ratio `1/3`, the JVM
;; double `1.5`, `1.0`, `1e21`, and `##NaN` must canonicalise to the same form
;; + hash as the CLJS values here. CLJS reads `1/3` as the double 0.333… and
;; `1.0` as the integer `1` (same JS number), so matching the JVM literals on
;; BOTH hosts is the cross-host-equivalence proof the fingerprint contract
;; rests on. The hex strings are the IEEE-754 bit patterns — host-invariant
;; for a given logical double.

(deftest ordinary-value-canonical-forms-are-unchanged-on-cljs
  (testing "REGRESSION GUARD (rf2-vvqeo): ordinary-value content-hashes match
            the SAME pre-change baseline the JVM pins — no golden rebase, and
            the cross-host hash agreement for ordinary values too"
    (is (= "211a4621" (fp/content-hash 42)))
    (is (= "3409cbf2" (fp/content-hash "hello")))
    (is (= "3ac20368" (fp/content-hash :foo/bar)))
    (is (= "234450cb" (fp/content-hash [1 2 3])))
    (is (= "418d9acd" (fp/content-hash {:a 1 :b "x" :c :k})))
    (is (= "405ea2f0" (fp/content-hash #{:a :b :c})))
    (is (= "98b520a4" (fp/content-hash {:status :pass :app-db {:n 1 :items [{:sku "A"}]}})))))

(deftest doubles-and-ratios-canonicalize-host-portably-on-cljs
  (testing "the double CLJS reads `1/3` as canonicalises to the SAME bit form +
            hash the JVM Ratio `1/3` does (cross-host equivalence)"
    (is (= [fp/double-tag "3fd5555555555555"] (fp/canonical-form (/ 1.0 3.0))))
    (is (= "ca619b05" (fp/content-hash (/ 1.0 3.0)))
        "matches the JVM `(fp/content-hash 1/3)` literal"))
  (testing "an integer-valued double IS the integer on CLJS — matches the JVM
            fold of `1.0` → `1`"
    (is (= 1   (fp/canonical-form 1.0)))
    (is (= 100 (fp/canonical-form 100.0)))
    (is (= (fp/content-hash 1.0) (fp/content-hash 1))))
  (testing "a fractional double folds to the SAME `[:rf/double <hex>]` + hash
            the JVM `1.5` produces"
    (is (= [fp/double-tag "3ff8000000000000"] (fp/canonical-form 1.5)))
    (is (= "6ef2b29e" (fp/content-hash 1.5))
        "matches the JVM `(fp/content-hash 1.5)` literal")
    (is (not= (fp/canonical-form 1.5) (fp/canonical-form 1))))
  (testing "an integer-valued double beyond the IEEE-754 safe-integer range
            takes the bit path — matches the JVM `1e21` form"
    (is (= [fp/double-tag "444b1ae4d6e2ef50"] (fp/canonical-form 1e21)))
    (is (= "66b23237" (fp/content-hash 1e21)))))

(deftest nan-and-inf-canonicalize-host-portably-on-cljs
  (testing "NaN folds to the `:rf/nan` sentinel — matches the JVM, hashes stably"
    (is (= fp/nan-tag (fp/canonical-form js/NaN)))
    (is (= "0cf774cf" (fp/content-hash js/NaN))
        "matches the JVM `(fp/content-hash (Double/NaN))` literal")
    (is (= (fp/canonical-hash {:x js/NaN}) (fp/canonical-hash {:x js/NaN}))))
  (testing "±Inf canonicalise to the SAME bit-double forms + hashes the JVM
            produces, mutually distinct"
    (is (= [fp/double-tag "7ff0000000000000"] (fp/canonical-form js/Infinity)))
    (is (= [fp/double-tag "fff0000000000000"] (fp/canonical-form (- js/Infinity))))
    (is (= "026c4383" (fp/content-hash js/Infinity)))
    (is (= "69fa37b4" (fp/content-hash (- js/Infinity))))
    (is (not= (fp/canonical-form js/Infinity) (fp/canonical-form (- js/Infinity))))))

(deftest nan-set-and-opaque-fn-tiebreak-stable-on-cljs
  (testing "a NaN-bearing set hashes stably across builds on CLJS (the NaN
            ordering hole is closed — every NaN is the `:rf/nan` sentinel)"
    (is (= (fp/content-hash (set [js/NaN :a 1]))
           (fp/content-hash (set [1 :a js/NaN])))))
  (testing "a set of two distinct JS fns (both fold to `:rf/opaque-fn`, equal
            `pr-str`) hashes stably across builds via `stable-canon-order`"
    (let [build (fn [] #{(fn [] 1) (fn [] 2) :marker})]
      (is (= (fp/content-hash (build)) (fp/content-hash (build)))))))
