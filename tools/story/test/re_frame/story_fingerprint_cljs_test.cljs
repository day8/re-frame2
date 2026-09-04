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
            [re-frame.story.fingerprint :as rf.story.fingerprint]))

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
    (let [h (rf.story.fingerprint/content-hash {:a 1})]
      (is (string? h))
      (is (= 8 (count h)))
      (is (re-matches #"[0-9a-f]{8}" h)))))

(deftest order-insensitive-on-cljs
  (testing "map key order does not affect the CLJS hash"
    (is (= (rf.story.fingerprint/content-hash {:a 1 :b 2}) (rf.story.fingerprint/content-hash {:b 2 :a 1}))))
  (testing "set element order does not affect the CLJS hash"
    (is (= (rf.story.fingerprint/content-hash #{:x :y :z}) (rf.story.fingerprint/content-hash #{:z :y :x})))))

(deftest volatile-equivalence-on-cljs
  (testing "volatile-only differences canonicalize = and run-hash equal"
    (is (= (rf.story.fingerprint/canonicalize base) (rf.story.fingerprint/canonicalize volatile-twin)))
    (is (= (rf.story.fingerprint/run-hash base) (rf.story.fingerprint/run-hash volatile-twin)))))

(deftest semantic-difference-on-cljs
  (testing "a semantic app-db difference perturbs the run-hash on CLJS"
    (is (not= (rf.story.fingerprint/run-hash base)
              (rf.story.fingerprint/run-hash (assoc-in base [:app-db :n] 2))))))

(deftest determinism-stamp-strip-on-cljs
  (testing "epoch records differing only in per-run stamps canonicalize = on CLJS
            (rf2-5x1wt.8): :epoch-id / :frame / :committed-at / :schema-digest"
    (let [rec (fn [eid frame]
                {:epoch-id eid :frame frame :committed-at 1 :schema-digest "d"
                 :outcome :ok :db-after {:n 1} :trace-events [] :effects []})]
      (is (= (rf.story.fingerprint/canonicalize (rec 5 :rf.test.replay/frame-a))
             (rf.story.fingerprint/canonicalize (rec 90 :rf.test.replay/frame-z))))
      (is (= (rf.story.fingerprint/canonical-hash (rec 5 :rf.test.replay/frame-a))
             (rf.story.fingerprint/canonical-hash (rec 90 :rf.test.replay/frame-z))))
      (is (not= (rf.story.fingerprint/canonicalize (rec 5 :rf.test.replay/frame-a))
                (rf.story.fingerprint/canonicalize (assoc (rec 5 :rf.test.replay/frame-a)
                                        :db-after {:n 2})))
          "a real db-after difference still perturbs the canonical value")))

  (testing "trace events differing only in :id / :time / volatile tags
            canonicalize = on CLJS; the event-id tag is semantic"
    (let [ev (fn [id frame]
               {:operation :rf.event/run-start :op-type :event :id id :time id
                :tags {:rf.trace/event-id :foo :frame frame
                       :rf.trace/dispatch-id id}})]
      (is (= (rf.story.fingerprint/canonicalize {:trace-events [(ev 1 :rf.test.replay/frame-a)]})
             (rf.story.fingerprint/canonicalize {:trace-events [(ev 9 :rf.test.replay/frame-z)]})))
      (is (not= (rf.story.fingerprint/canonicalize {:trace-events [(ev 1 :rf.test.replay/frame-a)]})
                (rf.story.fingerprint/canonicalize
                  {:trace-events [(assoc-in (ev 1 :rf.test.replay/frame-a)
                                            [:tags :rf.trace/event-id] :bar)]}))
          "the event-id tag is behavioural, not a stamp")))

  (testing ":id / :time / :frame as plain app-db values are NOT stripped on CLJS"
    (is (not= (rf.story.fingerprint/canonicalize {:status :pass :app-db {:id 1 :time 10 :frame :l}})
              (rf.story.fingerprint/canonicalize {:status :pass :app-db {:id 2 :time 20 :frame :r}}))
        "semantic app-db data on common keys survives canonicalization")))

(deftest collection-types-do-not-collide-on-cljs
  (testing "map / set / vector type tags keep the kinds distinct on CLJS
            (rf2-lvrqa) — host-portable structural tagging"
    (is (not= (rf.story.fingerprint/content-hash {}) (rf.story.fingerprint/content-hash [])))
    (is (not= (rf.story.fingerprint/content-hash #{}) (rf.story.fingerprint/content-hash [])))
    (is (not= (rf.story.fingerprint/content-hash {:k 1}) (rf.story.fingerprint/content-hash [:k 1])))
    (is (not= (rf.story.fingerprint/content-hash #{:k}) (rf.story.fingerprint/content-hash [:k])))
    (is (not= (rf.story.fingerprint/canonical-hash {:effects [{:k 1}]})
              (rf.story.fingerprint/canonical-hash {:effects [[:k 1]]})))))

(deftest fn-slot-deterministic-on-cljs
  (testing "a fn folds to the opaque sentinel on CLJS (rf2-4gwja) — a JS fn
            in a hashed slot hashes stably, keywords/colls are not folded"
    (is (= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form (fn [] 1))))
    (is (= (rf.story.fingerprint/run-hash {:status :pass :app-db {:cb (fn [] 1)}})
           (rf.story.fingerprint/run-hash {:status :pass :app-db {:cb (fn [] 1)}})))
    (is (not= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form :kw)))
    (is (not= rf.story.fingerprint/opaque-fn (rf.story.fingerprint/canonical-form #{:a})))))

(deftest snapshot-fold-strip-free-on-cljs
  (testing "content-hash keeps :variant-id sensitivity; canonical-hash strips it"
    (let [tuple {:variant-id :story.x/v :variant {:a 1}}]
      (is (not= (rf.story.fingerprint/content-hash tuple)
                (rf.story.fingerprint/content-hash (assoc tuple :variant-id :story.y/v))))
      (is (= (rf.story.fingerprint/canonical-hash tuple)
             (rf.story.fingerprint/canonical-hash (assoc tuple :variant-id :story.y/v)))))))

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
    (is (= "211a4621" (rf.story.fingerprint/content-hash 42)))
    (is (= "3409cbf2" (rf.story.fingerprint/content-hash "hello")))
    (is (= "3ac20368" (rf.story.fingerprint/content-hash :foo/bar)))
    (is (= "234450cb" (rf.story.fingerprint/content-hash [1 2 3])))
    (is (= "418d9acd" (rf.story.fingerprint/content-hash {:a 1 :b "x" :c :k})))
    (is (= "405ea2f0" (rf.story.fingerprint/content-hash #{:a :b :c})))
    (is (= "98b520a4" (rf.story.fingerprint/content-hash {:status :pass :app-db {:n 1 :items [{:sku "A"}]}})))))

(deftest doubles-and-ratios-canonicalize-host-portably-on-cljs
  (testing "the double CLJS reads `1/3` as canonicalises to the SAME bit form +
            hash the JVM Ratio `1/3` does (cross-host equivalence)"
    (is (= [rf.story.fingerprint/double-tag "3fd5555555555555"] (rf.story.fingerprint/canonical-form (/ 1.0 3.0))))
    (is (= "ca619b05" (rf.story.fingerprint/content-hash (/ 1.0 3.0)))
        "matches the JVM `(rf.story.fingerprint/content-hash 1/3)` literal"))
  (testing "an integer-valued double IS the integer on CLJS — matches the JVM
            fold of `1.0` → `1`"
    (is (= 1   (rf.story.fingerprint/canonical-form 1.0)))
    (is (= 100 (rf.story.fingerprint/canonical-form 100.0)))
    (is (= (rf.story.fingerprint/content-hash 1.0) (rf.story.fingerprint/content-hash 1))))
  (testing "a fractional double folds to the SAME `[:rf/double <hex>]` + hash
            the JVM `1.5` produces"
    (is (= [rf.story.fingerprint/double-tag "3ff8000000000000"] (rf.story.fingerprint/canonical-form 1.5)))
    (is (= "6ef2b29e" (rf.story.fingerprint/content-hash 1.5))
        "matches the JVM `(rf.story.fingerprint/content-hash 1.5)` literal")
    (is (not= (rf.story.fingerprint/canonical-form 1.5) (rf.story.fingerprint/canonical-form 1))))
  (testing "an integer-valued double beyond the IEEE-754 safe-integer range
            takes the bit path — matches the JVM `1e21` form"
    (is (= [rf.story.fingerprint/double-tag "444b1ae4d6e2ef50"] (rf.story.fingerprint/canonical-form 1e21)))
    (is (= "66b23237" (rf.story.fingerprint/content-hash 1e21)))))

(deftest large-integers-canonicalize-host-portably-on-cljs
  (testing "an integer beyond the IEEE-754 safe-integer range takes the lossy
            bit-double path on CLJS (rf2-7w1vp) — CLJS has no exact integer
            past 2^53, so `1e20` (what CLJS reads `100000000000000000000` as)
            canonicalises to the SAME `[:rf/double <hex>]` form + hash the JVM
            large bigint reaches. This pairing IS the cross-host proof."
    (is (= [rf.story.fingerprint/double-tag "4415af1d78b58c40"] (rf.story.fingerprint/canonical-form 1e20)))
    (is (= "8a4b7ac2" (rf.story.fingerprint/content-hash 1e20))
        "matches the JVM `(rf.story.fingerprint/content-hash (bigint 100000000000000000000))`"))
  (testing "an integer AT max-safe-integer passes through verbatim on CLJS —
            no golden rebase for safe-range integers"
    (is (= rf.story.fingerprint/max-safe-integer (rf.story.fingerprint/canonical-form rf.story.fingerprint/max-safe-integer)))
    (is (= "9f16836d" (rf.story.fingerprint/content-hash rf.story.fingerprint/max-safe-integer))
        "matches the JVM `(rf.story.fingerprint/content-hash 9007199254740991)` literal")))

(deftest nan-and-inf-canonicalize-host-portably-on-cljs
  (testing "NaN folds to the `:rf/nan` sentinel — matches the JVM, hashes stably"
    (is (= rf.story.fingerprint/nan-tag (rf.story.fingerprint/canonical-form js/NaN)))
    (is (= "0cf774cf" (rf.story.fingerprint/content-hash js/NaN))
        "matches the JVM `(rf.story.fingerprint/content-hash (Double/NaN))` literal")
    (is (= (rf.story.fingerprint/canonical-hash {:x js/NaN}) (rf.story.fingerprint/canonical-hash {:x js/NaN}))))
  (testing "±Inf canonicalise to the SAME bit-double forms + hashes the JVM
            produces, mutually distinct"
    (is (= [rf.story.fingerprint/double-tag "7ff0000000000000"] (rf.story.fingerprint/canonical-form js/Infinity)))
    (is (= [rf.story.fingerprint/double-tag "fff0000000000000"] (rf.story.fingerprint/canonical-form (- js/Infinity))))
    (is (= "026c4383" (rf.story.fingerprint/content-hash js/Infinity)))
    (is (= "69fa37b4" (rf.story.fingerprint/content-hash (- js/Infinity))))
    (is (not= (rf.story.fingerprint/canonical-form js/Infinity) (rf.story.fingerprint/canonical-form (- js/Infinity))))))

(deftest nan-set-and-opaque-fn-tiebreak-stable-on-cljs
  (testing "a NaN-bearing set hashes stably across builds on CLJS (the NaN
            ordering hole is closed — every NaN is the `:rf/nan` sentinel)"
    (is (= (rf.story.fingerprint/content-hash (set [js/NaN :a 1]))
           (rf.story.fingerprint/content-hash (set [1 :a js/NaN])))))
  (testing "a set of two distinct JS fns (both fold to `:rf/opaque-fn`, equal
            `pr-str`) hashes stably across builds via `stable-canon-order`"
    (let [build (fn [] #{(fn [] 1) (fn [] 2) :marker})]
      (is (= (rf.story.fingerprint/content-hash (build)) (rf.story.fingerprint/content-hash (build)))))))
