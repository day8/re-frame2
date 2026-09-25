(ns day8.re-frame2-xray.panels.epoch.region-transition-action-key-cljs-test
  "A parallel region's inline transition `:action` cascade row keys under its
  region, so it resolves against the source the `reg-machine` macro
  co-locates on the region's transition map at
  `[:regions <region> :states … :on <event>]`.

  Every row comes from the producer: a literal parallel machine is
  registered through the macro, runs, and its traces for one dispatch
  project through `proj/machine-cascade-rows`. The action row carries the
  substrate's region-relative `:transition-slot` beside its `:region`, and
  `fmt/cascade-row-source-key` builds the key from both.

  The transition map's `:source-code` carries the inline fn's source on both
  dialects. Its `:source-coords` exists where the reader positions map
  literals, which the CLJS reader does and Clojure's LispReader does not, so
  the coord assertion is CLJS-only.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM) and
  shadow-cljs's `cljs-test$` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
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

(deftest region-transition-action-keys-under-its-region
  (rf/reg-machine :rtak/parallel
    {:type    :parallel
     :regions {:r1 {:initial :x
                    :states  {:x {:on {:go {:target :y
                                            :action (fn [_] {:data {:at :r1-go}})}}}
                              :y {}}}
               :r2 {:initial :p
                    :states  {:p {}}}}})
  (rf/dispatch-sync [:rtak/parallel [:rf.machine/start]])
  (let [spec (registered :rtak/parallel)
        k    (key-of-row-running (cascade-of [:rtak/parallel [:go]])
                                 (get-in spec [:regions :r1 :states :x :on :go :action]))]
    (is (= [:regions :r1 :states :x :on :go :action] k))
    (is (= "(fn [_] {:data {:at :r1-go}})"
           (get-in spec (conj (pop k) :source-code (peek k))))
        "the key's enclosing node carries the inline action's source")
    #?(:cljs (let [coord (get-in spec [:regions :r1 :states :x :on :go :source-coords])]
               (is (map? coord) "the macro stamps the region's transition map")
               (is (= coord (proj/state-node-source-coords spec k))
                   "the key resolves to the region's transition map coord")))))
