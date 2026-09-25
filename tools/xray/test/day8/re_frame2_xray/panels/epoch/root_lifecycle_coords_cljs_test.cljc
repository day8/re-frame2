(ns day8.re-frame2-xray.panels.epoch.root-lifecycle-coords-cljs-test
  "A machine root's own `:entry` / `:exit` cascade row resolves its source
  coord against the root's `:source-coords`, and a `[:states …]` key whose
  path carries no coord resolves to nothing rather than to the root's.

  Every row comes from the producer: a literal machine is registered through
  the macro, runs, and its traces for one dispatch project through
  `proj/machine-cascade-rows`. `fmt/cascade-row-source-key` builds each row's
  key and `proj/state-node-source-coords` resolves it.

  The CLJS reader positions map literals, so there the macro stamps the
  root's own coord and the CLJS-only assertions resolve against it. Clojure's
  LispReader positions no map literal, so the assertions shared by both
  dialects place a coord on the registered root themselves.

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

(defn- registered
  "The registered spec of `machine-id` — the metadata Xray resolves keys
  against."
  [machine-id]
  (:rf/machine (rf/handler-meta {:source :store :kind :event :id machine-id})))

(defn- key-of-row-running
  "The source key of the `:action` cascade row whose action is `f`."
  [rows f]
  (some #(when (and (= :action (:kind %)) (identical? f (:action-id %)))
           (fmt/cascade-row-source-key %))
        rows))

(def ^:private placed-coord
  "A root coord the dialect-neutral assertions place on the registered spec."
  {:ns 'placed.root :file "placed/root.cljc" :line 7 :column 3})

(defn- without-state-coords
  "`spec` with every top-level state-node's `:source-coords` removed."
  [spec]
  (update spec :states
          (fn [states]
            (reduce-kv (fn [acc id node] (assoc acc id (dissoc node :source-coords)))
                       {} states))))

(deftest root-lifecycle-rows-resolve-the-root-coord
  (rf/reg-machine :rlc/flat
    {:initial :a
     :entry   (fn [_] {:data {:at :root-in}})
     :exit    (fn [_] {:data {:at :root-out}})
     :states  {:a    {:entry (fn [_] {:data {:at :a-in}})
                      :on    {:fin :done}}
               :done {:final? true}}})
  (let [spec   (registered :rlc/flat)
        placed (assoc spec :source-coords placed-coord)
        born   (cascade-of [:rlc/flat [:rf.machine/start]])
        entry  (key-of-row-running born (:entry spec))
        a-in   (key-of-row-running born (get-in spec [:states :a :entry]))
        exit   (key-of-row-running (cascade-of [:rlc/flat [:fin]]) (:exit spec))]
    (testing "the root's own :entry row"
      (is (= [:entry] entry))
      (is (= placed-coord (proj/state-node-source-coords placed entry)))
      #?(:cljs (do (is (map? (:source-coords spec))
                       "the macro stamps the root's own coord")
                   (is (= (:source-coords spec)
                          (proj/state-node-source-coords spec entry))))))
    (testing "the root's own :exit row, at teardown"
      (is (= [:exit] exit))
      (is (= placed-coord (proj/state-node-source-coords placed exit)))
      #?(:cljs (is (= (:source-coords spec)
                      (proj/state-node-source-coords spec exit)))))
    (testing "a state's row whose path carries no coord does not take the root's"
      (is (= [:states :a :entry] a-in))
      (is (nil? (proj/state-node-source-coords (without-state-coords placed) a-in))))))
