(ns re-frame.ui.binding-plan-host-faithful-jvm-test
  "rf2-vxgfnd.283 — the ONE host-faithful binding plan. The analyzer's scope
  walk and the CLJS header emitter must derive their binding order from a single
  plan that reproduces the host `destructure` `bes` transformation, so neither
  drifts from the other or from what the JVM native destructuring (the JVM
  emitter) and the CLJS lowering actually bind.

  These fixtures compare the plan against REAL `clojure.core/destructure`
  expansion (macro-level, JVM). The CLJS `destructure` is byte-for-byte the same
  transformation run on the same JVM maps at macro-expansion time, so a match
  here is a match on both hosts — the `.cljc` reject table pins the CLJS path
  end to end."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.emit-cljs :as emit-cljs]
            [re-frame.ui.compiler.header :as header]))

(defn- real-local-order
  "The user locals `clojure.core/destructure` binds for map `pat`, in order —
  the ground truth the plan must reproduce (gensym scaffolding dropped)."
  [pat]
  (->> (destructure [pat 'the-map])
       (partition 2)
       (map first)
       (remove #(re-find #"^(map__|vec__|seq__|first__|p__|the-map)" (name %)))
       vec))

(defn- plan-order
  "The shared binding plan's local order for map `pat` (`:as` excluded — it
  binds first, before the `bes` walk)."
  [pat]
  (ana/header-binding-order pat))

(defn- emit-local-order
  "The locals the CLJS header emitter binds, in emission order."
  [argv]
  (->> (header/parse-header argv)
       emit-cljs/header-bindings
       (partition 2)
       (map first)
       vec))

;; ---------------------------------------------------------------------------
;; The plan reproduces the host bes order
;; ---------------------------------------------------------------------------

(deftest plan-matches-real-clojure-destructure
  (testing "explicit + group, array-map regime (source order)"
    (doseq [pat '[{:keys [a b c d e f g h]}                 ; 8 = array-map
                  {ex1 :e1 :keys [ka kb] ex2 :e2}           ; explicit around a group
                  {p1 :k1 p2 :k2 :keys [a b] :strs [s1 s2] :syms [y1 y2]}]]
      (is (= (real-local-order pat) (plan-order pat))
          (str "plan reproduces host order for " (pr-str pat)))))
  (testing "mixed :keys/:strs/:syms follow SOURCE group order (not a fixed rank)"
    (let [pat '{:syms [y1 y2] :keys [k1 k2] :strs [s1 s2]}]
      (is (= (real-local-order pat) (plan-order pat)))
      (is (= '[y1 y2 k1 k2 s1 s2] (plan-order pat))
          "syms-first source order, not keys<strs<syms rank")
      (is (not= '[k1 k2 s1 s2 y1 y2] (plan-order pat))
          "the invented rank order would silently regress this")))
  (testing "the 8→9 entry map-representation threshold"
    (is (= '[a b c d e f g h] (plan-order '{:keys [a b c d e f g h]}))
        "8 entries stay a PersistentArrayMap in source order")
    (let [nine '{:keys [a b c d e f g h i]}]
      (is (= (real-local-order nine) (plan-order nine))
          "9 entries promote to a PersistentHashMap — reproduce that order")
      (is (not= '[a b c d e f g h i] (plan-order nine))
          "the 9-entry order is hash-driven, NOT source order")))
  (testing "the bead counterexample binds target before its sub default"
    (let [pat '{:keys [sub a b c d e f g h target] :or {target sub}}]
      (is (= (real-local-order pat) (plan-order pat)))
      (is (< (.indexOf ^clojure.lang.PersistentVector (plan-order pat) 'target)
             (.indexOf ^clojure.lang.PersistentVector (plan-order pat) 'sub))
          "target is bound before sub, so its default reaches the outer var"))))

;; ---------------------------------------------------------------------------
;; The CLJS header emitter binds in the same host order — :as FIRST
;; ---------------------------------------------------------------------------

(deftest cljs-header-emission-is-host-faithful
  (testing "entries land in host destructure order, not parse order"
    (doseq [argv '[[{:keys [a] x :foo}]
                   [{:keys [a] x :foo :or {x 0}}]
                   [{:keys [a b c d e f g h i]}]]]
      (is (= (real-local-order (first argv)) (emit-local-order argv))
          (str "CLJS header binds in host order for " (pr-str argv)))))
  (testing ":as binds the whole props map FIRST (never after the entries)"
    (let [order (emit-local-order '[{:keys [a b] :as whole}])]
      (is (= '[whole a b] order))
      (is (= 'whole (first order))
          "moving :as after the entries would let an :as-dependent default escape")
      (is (= (real-local-order '{:keys [a b] :as whole}) order)))))

;; ---------------------------------------------------------------------------
;; The JVM emitter delegates to the host, so the two emitters agree
;; ---------------------------------------------------------------------------

(deftest jvm-and-cljs-emitters-share-the-order
  ;; The JVM emitter emits `(let [<raw pattern> props] …)` and lets the host
  ;; destructure natively; the CLJS emitter now orders its property reads by the
  ;; same plan. So for every header pattern the two emitters bind in one order —
  ;; a dependent :or default resolves to the same symbol on both hosts.
  (doseq [argv '[[{:keys [a] x :foo :or {x 0}}]
                 [{:keys [a b] :as whole}]
                 [{:keys [a b c d e f g h i] :as whole}]]]
    (let [host (real-local-order (first argv))
          cljs (emit-local-order argv)]
      (is (= host cljs)
          (str "JVM host order == CLJS emission order for " (pr-str argv))))))
