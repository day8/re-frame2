(ns re-frame.unforced-seq-projection-test
  "A projection never realises a seq the app has not forced, neither by walking
  it nor by measuring it for a large marker (Spec 015 §`elide-wire-value`). A frame that classifies anything walks a view's render
  args for its `:rf.view/rendered` trace, and a walk that realised an endless
  seq there would never return, so the render would hang.

  Each endless seq here is GUARDED: it counts the elements computed and throws
  past a bound, so a walk that forces it fails at once instead of hanging.
  The host's own endless seqs cannot be guarded that way, so they are
  projected only once a guarded seq has come back unrealised, and on the JVM
  under a timeout as well. No endless seq is ever an argument of an `is`
  form, whose failure report would print it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def ^:private this-ns "re-frame.unforced-seq-projection-test")

(rf/reg-classification ::classified {:sensitive [[:auth :token]]})

;; `[:log]` is a large node holding a sensitive descendant, so its marker is
;; measured after that descendant redacts.
(rf/reg-classification ::large {:large [[:items] [:log]] :sensitive [[:log :token]]})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private redacted rf.privacy/redacted-sentinel)

(defn- guarded-endless
  "An endless unchunked lazy seq 0, 1, 2, … that records in `computed` how
  many elements have been computed, and throws when asked for the
  ten-thousandth."
  [computed]
  (letfn [(step [i]
            (lazy-seq
              (when (>= i 10000)
                (throw (ex-info "realised past the guard" {:i i})))
              (swap! computed max (inc i))
              (cons i (step (inc i)))))]
    (step 0)))

(defn- classified-frame!
  "A frame whose image selects this namespace alone, so the one
  classification above is the whole of its policy."
  []
  (rf/make-frame {:id     ::classified
                  :images [(rf/image {:id ::classified :select-ns {:include [this-ns]}})]})
  ::classified)

(defn- project-render-args
  "The `:rf.view/render-args` a `:rf.view/rendered` trace in `frame` carries
  once the trace chokepoint has projected it, or `::forced` when the walk
  tripped a guard."
  [frame args]
  (try
    (get-in (rf.classification/project-trace-event
              {:operation :rf.view/rendered
               :tags      {:frame frame :rf.view/render-args args}})
            [:tags :rf.view/render-args])
    (catch #?(:clj Exception :cljs :default) _ ::forced)))

(defn- within-time
  "`(f)`, or `::timed-out` once five seconds pass on the JVM. A CLJS walk
  cannot be pre-empted, which is why the guarded cases gate the unguarded ones."
  [f]
  #?(:clj  (deref (future (f)) 5000 ::timed-out)
     :cljs (f)))

(deftest an-endless-render-arg-projects-as-redacted-and-stays-unrealised
  (let [frame    (classified-frame!)
        computed (atom 0)
        partly   (atom 0)
        p        (guarded-endless partly)
        _        (doall (take 3 p))]
    (testing "an endless seq nothing has realised"
      (is (= [[redacted] 0]
             [(project-render-args frame [(guarded-endless computed)]) @computed])))
    (testing "an endless seq the render body realised a prefix of: the walk
              computes no further"
      (is (= [[redacted] 3] [(project-render-args frame [p]) @partly])))
    (testing "the host's own endless seqs"
      (if (zero? @computed)
        (doseq [[label g] [["range" (range)] ["iterate" (iterate inc 0)]
                           ["repeat" (repeat :x)] ["cycle" (cycle [1 2])]
                           ["map over range" (map inc (range))]]]
          (is (= [redacted] (within-time #(project-render-args frame [g])))
              label))
        (is false "not projected: the walk realised a guarded seq, so these would hang")))))

(deftest a-seq-no-declaration-reaches-ships-as-found
  (let [frame    (classified-frame!)
        computed (atom 0)
        s        (guarded-endless computed)
        [arg]    (project-render-args frame [{:feed s :auth {:token "t"}}])
        same?    (identical? s (:feed arg))]
    (is same? "the same seq, unwalked")
    (is (= redacted (get-in arg [:auth :token])) "while a declared path beside it still redacts")
    (is (zero? @computed) "neither the walk nor the dev size probe computed an element")))

(deftest a-forced-seq-is-walked-and-redacted-as-before
  (let [frame (classified-frame!)
        rows  [{:auth {:token "t1"}} {:auth {:token "t2"}}]]
    (doseq [[label s] [["a realised lazy seq" (doall (map identity rows))]
                       ["a list" (apply list rows)]
                       ["a seq over a vector" (seq rows)]]]
      (is (= [[{:auth {:token redacted}} {:auth {:token redacted}}]]
             (project-render-args frame [s]))
          label))))

(deftest a-boundary-that-substitutes-nothing-ships-it-as-found
  (let [frame     (classified-frame!)
        computed  (atom 0)
        s         (guarded-endless computed)
        raw       (rf.elision/elide-wire-value
                    s {:frame                        frame
                       :rf.egress/include-sensitive? true
                       :rf.egress/include-large?     true})
        frameless (rf.elision/elide-wire-value
                    s {:frame nil :rf.egress/include-sensitive? true})
        raw-same?       (identical? s raw)
        frameless-same? (identical? s frameless)]
    (is raw-same? "a trusted-local raw read")
    (is frameless-same? "a frameless read that waived redaction: no policy applies")
    (is (= redacted (rf.elision/elide-wire-value s {:frame frame}))
        "control: the default boundary fails closed on the same seq")
    (is (zero? @computed))))

(deftest a-large-value-holding-an-unforced-seq-projects-as-redacted
  (let [frame    (classified-frame!)
        computed (atom 0)
        g        #(guarded-endless computed)]
    (testing "a render arg at a declared-large path"
      (is (= [[{:items redacted}] 0]
             [(project-render-args frame [{:items (g)}]) @computed])))
    (testing "a large container holding one"
      (is (= [[{:items redacted}] 0]
             [(project-render-args frame [{:items {:rows (g)}}]) @computed])))
    (testing "a large node measured after its sensitive descendant redacts: the
              seq inside redacts in that walk, so the marker measures it unrealised"
      (let [[arg] (project-render-args frame [{:log {:token "t" :rows (g)}}])]
        (is (= [true 0] [(rf.elision/marker? (:log arg)) @computed]))))
    (testing "a direct read at, inside and below a large path"
      (is (= [redacted redacted redacted redacted 0]
             [(rf.elision/elide-wire-value (g) {:frame frame :path [:items]})
              (rf.elision/elide-wire-value {:rows (g)} {:frame frame :path [:items]})
              (rf.elision/elide-wire-value (g) {:frame frame :path [:items :rows]})
              (rf.elision/elide-wire-value (g) {:frame frame :path [:log :rows]})
              @computed])))
    (testing "projecting the result again changes nothing"
      (is (= {:items redacted}
             (rf.elision/elide-wire-value {:items redacted} {:frame frame}))))
    (testing "a forced value at a large path is still marked and measured"
      (is (= [7 7]
             (mapv #(get-in (project-render-args frame [{:items %}])
                            [0 :items :rf.size/large-elided :bytes])
                   [[1 2 3] (doall (map inc [0 1 2]))]))))
    (testing "the host's own endless seqs"
      (if (zero? @computed)
        (doseq [[label arg] [["range" {:items (range)}]
                             ["iterate in a large container" {:items {:rows (iterate inc 0)}}]]]
          (let [ok? (= [{:items redacted}] (within-time #(project-render-args frame [arg])))]
            (is ok? label)))
        (is false "not projected: a large marker realised a guarded seq, so these would hang")))))

(deftest the-registration-classification-walk-marks-no-unforced-seq-large
  (let [computed (atom 0)
        g        #(guarded-endless computed)]
    (is (= [{:items redacted} {:items redacted} {:items redacted} 0]
           [(rf.classification/redact-with-paths {:items (g)} [] [[:items]])
            (rf.classification/redact-with-paths {:items {:rows (g)}} [] [[:items]])
            (rf.classification/redact-with-paths {:items {:token "t" :rows (g)}}
                                                 [[:items :token]] [[:items]])
            @computed]))
    (is (rf.elision/marker? (:items (rf.classification/redact-with-paths {:items [1 2 3]} [] [[:items]])))
        "control: a forced large value is still marked")))

(deftest the-registration-classification-walk-follows-the-same-rule
  (let [computed (atom 0)
        s        (guarded-endless computed)
        beside   (rf.classification/redact-with-paths {:password "p" :items s} [[:password]] [])
        same?    (identical? s (:items beside))]
    (is (= redacted (:password beside)))
    (is same? "no declared path reaches the seq: it ships as found")
    (is (= {:items redacted}
           (rf.classification/redact-with-paths {:items s} [[:items 0 :token]] []))
        "a declared path reaches inside it: it fails closed")
    (is (zero? @computed))))
