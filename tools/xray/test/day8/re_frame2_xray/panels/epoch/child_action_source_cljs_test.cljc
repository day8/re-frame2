(ns day8.re-frame2-xray.panels.epoch.child-action-source-cljs-test
  "A state's own `:entry` / `:exit` cascade row names and addresses the state
  that DECLARES the action, never the state of the surrounding transition.

  Every row here comes from the producer: a real machine runs, its traces
  are captured per dispatch (one epoch each), and `proj/machine-cascade-rows`
  projects them. The substrate stamps the declaring node on
  `:rf.machine/action-ran` (`:decl-path`, with `:region` inside a parallel
  region), and `fmt/cascade-row-source-key` / `fmt/cascade-action-for-state`
  read it:

  - a transition from `:a` that enters `:b` and `[:b :c]` gives `:b`'s inline
    `:entry` the key `[:states :b :entry]` and `[:b :c]`'s the key
    `[:states :b :states :c :entry]`, each labelled with its own state — and
    the exits on the way back likewise;
  - the initial descent at birth, where no transition surrounds the rows,
    resolves the same way;
  - inside a parallel region the key is prefixed `[:regions <region>]` and
    the label is `{<region> <state>}`;
  - a named `:entry` keeps its `[:actions <id>]` key and is labelled with its
    declaring state.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and
  shadow-cljs's `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [day8.re-frame2-xray.panels.epoch.format :as fmt]
   [day8.re-frame2-xray.panels.epoch.projection :as proj]
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
   [re-frame.test-support :as rf.test-support]
   [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- cascade-of
  "Dispatch `event` synchronously and return the machine cascade rows the
  producer's traces for that one dispatch project to."
  [event]
  (let [evs (atom [])]
    (rf.trace.tooling/register-listener! ::capture (fn [ev] (swap! evs conj ev)))
    (try
      (rf/dispatch-sync event)
      (finally
        (rf.trace.tooling/unregister-listener! ::capture)))
    (proj/machine-cascade-rows @evs)))

(defn- action-row
  "The cascade row whose action is `f`."
  [rows f]
  (first (filter #(and (= :action (:kind %)) (identical? f (:action-id %))) rows)))

(defn- key-and-label
  "`[source-key label]` of the row whose action is `f`."
  [rows f]
  (let [row (action-row rows f)]
    [(fmt/cascade-row-source-key row) (fmt/cascade-action-for-state row)]))

(defn- nested-machine
  "`:b` is a compound whose initial child is `:c`; both, and `:a`, declare
  inline `:entry` / `:exit`. `:go` enters `:b` then `[:b :c]`; `:back`,
  declared on `:b`, exits `[:b :c]` then `:b`. Returns `[definition fns]`."
  [initial]
  (let [fns {:a-in  (fn [_] nil) :a-out (fn [_] nil)
             :b-in  (fn [_] nil) :b-out (fn [_] nil)
             :c-in  (fn [_] nil) :c-out (fn [_] nil)}]
    [{:initial initial
      :states  {:a {:entry (:a-in fns) :exit (:a-out fns) :on {:go :b}}
                :b {:initial :c
                    :entry   (:b-in fns)
                    :exit    (:b-out fns)
                    :on      {:back :a}
                    :states  {:c {:entry (:c-in fns) :exit (:c-out fns)}}}}}
     fns]))

(deftest entered-states-address-their-own-entry
  (let [[m fns] (nested-machine :a)
        _       (rf/reg-machine :cas/enter m)
        _       (rf/dispatch-sync [:cas/enter [:rf.machine/start]])
        rows    (cascade-of [:cas/enter [:go]])]
    (testing "the compound :b's :entry is :b's, not the entered leaf's"
      (is (= [[:states :b :entry] :b] (key-and-label rows (:b-in fns))))
      (is (identical? (:b-in fns)
                      (get-in m (fmt/cascade-row-source-key (action-row rows (:b-in fns)))))))
    (testing "the leaf [:b :c]'s :entry is its own"
      (is (= [[:states :b :states :c :entry] [:b :c]] (key-and-label rows (:c-in fns))))
      (is (identical? (:c-in fns)
                      (get-in m (fmt/cascade-row-source-key (action-row rows (:c-in fns)))))))
    (testing "the exited :a's :exit is :a's"
      (is (= [[:states :a :exit] :a] (key-and-label rows (:a-out fns)))))))

(deftest exited-states-address-their-own-exit
  (let [[m fns] (nested-machine :a)
        _       (rf/reg-machine :cas/exit m)
        _       (rf/dispatch-sync [:cas/exit [:rf.machine/start]])
        _       (rf/dispatch-sync [:cas/exit [:go]])
        rows    (cascade-of [:cas/exit [:back]])]
    (testing "the transition leaves from [:b :c]; :b's :exit is still :b's"
      (is (= [[:states :b :states :c :exit] [:b :c]] (key-and-label rows (:c-out fns))))
      (is (= [[:states :b :exit] :b] (key-and-label rows (:b-out fns))))
      (is (identical? (:b-out fns)
                      (get-in m (fmt/cascade-row-source-key (action-row rows (:b-out fns)))))))))

(deftest the-initial-descent-addresses-each-declaring-state
  (let [[m fns] (nested-machine :b)
        _       (rf/reg-machine :cas/birth m)
        rows    (cascade-of [:cas/birth [:rf.machine/start]])]
    (is (= :initial-entry (:phase (action-row rows (:b-in fns)))))
    (is (= [[:states :b :entry] :b] (key-and-label rows (:b-in fns))))
    (is (= [[:states :b :states :c :entry] [:b :c]] (key-and-label rows (:c-in fns))))))

(deftest a-region-state-addresses-its-own-entry-within-the-region
  (let [x2-in (fn [_] nil)
        x3-in (fn [_] nil)
        m     {:type    :parallel
               :regions {:x {:initial :x1
                             :states  {:x1 {:on {:go :x2}}
                                       :x2 {:initial :x3
                                            :entry   x2-in
                                            :states  {:x3 {:entry x3-in}}}}}
                         :y {:initial :y1 :states {:y1 {}}}}}
        _     (rf/reg-machine :cas/par m)
        _     (rf/dispatch-sync [:cas/par [:rf.machine/start]])
        rows  (cascade-of [:cas/par [:go]])]
    (is (= [[:regions :x :states :x2 :entry] {:x :x2}] (key-and-label rows x2-in)))
    (is (= [[:regions :x :states :x2 :states :x3 :entry] {:x [:x2 :x3]}]
           (key-and-label rows x3-in)))
    (is (identical? x3-in (get-in m (fmt/cascade-row-source-key (action-row rows x3-in)))))))

(deftest a-named-entry-keeps-its-key-and-names-its-declaring-state
  (let [m    {:initial :a
              :actions {:b-in (fn [_] nil)}
              :states  {:a {:on {:go :b}}
                        :b {:initial :c
                            :entry   :b-in
                            :states  {:c {}}}}}
        _    (rf/reg-machine :cas/named m)
        _    (rf/dispatch-sync [:cas/named [:rf.machine/start]])
        rows (cascade-of [:cas/named [:go]])
        row  (first (filter #(= :b-in (:action-id %)) rows))]
    (is (= [:actions :b-in] (fmt/cascade-row-source-key row)))
    (is (= :b (fmt/cascade-action-for-state row)))))
