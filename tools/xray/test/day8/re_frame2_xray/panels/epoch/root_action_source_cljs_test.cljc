(ns day8.re-frame2-xray.panels.epoch.root-action-source-cljs-test
  "A tree ROOT's own `:entry` / `:exit` cascade row addresses the root's
  declaration, never a child's, in the Epoch panel's machine cascade.

  Every row here comes from the producer: a real machine runs, its traces
  are captured per dispatch (one epoch each), and `proj/machine-cascade-rows`
  projects them. The substrate stamps the declaring node on
  `:rf.machine/action-ran` (`:decl-path`, `[]` for a root, with `:region`
  inside a parallel region), and `fmt/cascade-row-source-key` /
  `fmt/cascade-action-for-state` read it:

  - an inline machine-root `:entry` / `:exit` resolves to `[:entry]` /
    `[:exit]` and is labelled `:rf/root`, on the eager and the lazy birth and
    at teardown — never to the state of the surrounding transition;
  - an inline region-body `:entry` resolves to `[:regions <region> :entry]`
    and is labelled with the region;
  - a named action keeps `[:actions <id>]`, and a child state's inline
    `:entry` / `:exit` keeps its `[:states …]` key.

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

(defn- root-machine
  "A flat machine with inline root `:entry` / `:exit`, distinct inline child
  hooks, a named child `:exit`, and transitions around them. Returns
  `[definition fns]`."
  []
  (let [fns {:root-in  (fn [_] nil)
             :root-out (fn [_] nil)
             :a-in     (fn [_] nil)
             :b-in     (fn [_] nil)
             :b-out    (fn [_] nil)}]
    [{:initial :a
      :entry   (:root-in fns)
      :exit    (:root-out fns)
      :actions {:a-out (fn [_] nil)}
      :states  {:a    {:entry (:a-in fns) :exit :a-out :on {:go :b}}
                :b    {:entry (:b-in fns) :exit (:b-out fns) :on {:fin :done}}
                :done {:final? true}}}
     fns]))

(deftest eager-birth-root-entry-addresses-the-root
  (let [[m fns] (root-machine)
        _       (rf/reg-machine :ras/eager m)
        rows    (cascade-of [:ras/eager [:rf.machine/start]])
        row     (action-row rows (:root-in fns))]
    (is (= [:entry] (fmt/cascade-row-source-key row)))
    (is (identical? (:root-in fns) (get-in m (fmt/cascade-row-source-key row)))
        "the key resolves to the root's own :entry")
    (is (= :rf/root (fmt/cascade-action-for-state row)))))

(deftest lazy-birth-root-entry-addresses-the-root-not-the-transition-target
  (testing "the birth folds into the first event's epoch, whose transition
            enters :b — the root's :entry still resolves to the root"
    (let [[m fns] (root-machine)
          _       (rf/reg-machine :ras/lazy m)
          rows    (cascade-of [:ras/lazy [:go]])
          row     (action-row rows (:root-in fns))]
      (is (= [:entry] (fmt/cascade-row-source-key row)))
      (is (= :rf/root (fmt/cascade-action-for-state row)))
      (testing "a child's inline :entry keeps its [:states …] key"
        (is (= [:states :b :entry]
               (fmt/cascade-row-source-key (action-row rows (:b-in fns)))))
        (is (= :b (fmt/cascade-action-for-state (action-row rows (:b-in fns))))))
      (testing "a named action keeps its [:actions …] key"
        (is (= [[:actions :a-out]]
               (->> rows
                    (filter #(= :a-out (:action-id %)))
                    (mapv fmt/cascade-row-source-key))))))))

(deftest teardown-root-exit-addresses-the-root-not-the-exited-leaf
  (testing "the final transition exits :b; the teardown then runs the root's
            :exit — which resolves to the root, not to :b's :exit"
    (let [[m fns] (root-machine)
          _       (rf/reg-machine :ras/final m)
          _       (rf/dispatch-sync [:ras/final [:go]])
          rows    (cascade-of [:ras/final [:fin]])
          row     (action-row rows (:root-out fns))]
      (is (= :destroy-exit (:phase row)))
      (is (= [:exit] (fmt/cascade-row-source-key row)))
      (is (identical? (:root-out fns) (get-in m (fmt/cascade-row-source-key row))))
      (is (= :rf/root (fmt/cascade-action-for-state row)))
      (testing "the exited leaf's own inline :exit keeps its key"
        (is (= [:states :b :exit]
               (fmt/cascade-row-source-key (action-row rows (:b-out fns)))))))))

(deftest region-body-entry-addresses-the-region
  (let [x-in (fn [_] nil)
        m    {:type    :parallel
              :regions {:x {:initial :x1
                            :entry   x-in
                            :states  {:x1 {:on {:go :x2}} :x2 {}}}
                        :y {:initial :y1 :states {:y1 {}}}}}
        _    (rf/reg-machine :ras/par m)
        rows (cascade-of [:ras/par [:go]])
        row  (action-row rows x-in)]
    (is (= :x (:region row)))
    (is (= [:regions :x :entry] (fmt/cascade-row-source-key row)))
    (is (identical? x-in (get-in m (fmt/cascade-row-source-key row))))
    (is (= :x (fmt/cascade-action-for-state row)))))
