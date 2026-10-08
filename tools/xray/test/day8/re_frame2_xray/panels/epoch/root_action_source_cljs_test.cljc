(ns day8.re-frame2-xray.panels.epoch.root-action-source-cljs-test
  "A tree ROOT's own `:entry` cascade row addresses the root's declaration,
  never a child's, in the Epoch panel's machine cascade.

  Every row here comes from the producer: a real machine runs, its traces
  are captured per dispatch (one epoch each), and `proj/machine-cascade-rows`
  projects them. The substrate stamps the declaring node on
  `:rf.machine/action-ran` (`:decl-path`, `[]` for a root, with `:region`
  inside a parallel region), and `fmt/cascade-row-source-key` /
  `fmt/cascade-action-for-state` read it:

  - an inline machine-root `:entry` resolves to `[:entry]` and is labelled
    `:rf/root` even when the birth folds into an event whose transition
    enters a child — never to the state of the surrounding transition;
  - an inline region-body `:entry` is labelled with the region.

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

(deftest lazy-birth-root-entry-addresses-the-root-not-the-transition-target
  (testing "the birth folds into the first event's epoch, whose transition
            enters :b — the root's :entry still resolves to the root"
    (let [[m fns] (root-machine)
          _       (rf/reg-machine :ras/lazy m)
          row     (action-row (cascade-of [:ras/lazy [:go]]) (:root-in fns))]
      (is (= [:entry] (fmt/cascade-row-source-key row)))
      (is (= :rf/root (fmt/cascade-action-for-state row))))))

(deftest region-body-entry-addresses-the-region
  (let [x-in (fn [_] nil)
        m    {:type    :parallel
              :regions {:x {:initial :x1
                            :entry   x-in
                            :states  {:x1 {:on {:go :x2}} :x2 {}}}
                        :y {:initial :y1 :states {:y1 {}}}}}
        _    (rf/reg-machine :ras/par m)
        row  (action-row (cascade-of [:ras/par [:go]]) x-in)]
    (is (= :x (fmt/cascade-action-for-state row)))))
