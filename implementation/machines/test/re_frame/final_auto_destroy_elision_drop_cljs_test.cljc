(ns re-frame.final-auto-destroy-elision-drop-cljs-test
  "rf2-3x7nj.9.2 — the `:final?` auto-destroy drops the finishing actor's
  `:sensitive` / `:large` elision claims, and they STAY dropped.

  `finalize-machine` removes the actor's per-instance claims out of band
  (`drop-at-destroy!`, a live elision-registry swap) and then returns the
  teardown runtime-db under `:rf.db/runtime`. That value is built from the
  handler's `:rf.db/runtime` COEFFECT, captured before the drop, and the
  router honours an effect-carried `:rf.runtime/elision` verbatim — so the
  commit re-installed the pre-drop registry and every classified actor that
  finished via `:final?` left a permanent claim behind (unbounded growth for
  spawned `<type>#n` actors). The explicit-destroy path was unaffected, because
  its drop runs in an fx after the event's commit. The boot-commit sibling of
  this clobber is rf2-dr0pfi, pinned in `machine_data_schema_redaction_test`.

  The file is named `*-cljs-test.cljc` so it is discovered by both
  cognitect.test-runner (JVM) and shadow-cljs (the `cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- elision-registry []
  (or (:rf.runtime/elision (rf.machines.test-support/runtime-db)) {}))

(defn- machine-claim-actors
  "Every actor id named by a `:source :machine` claim anywhere in the frame's
  elision registry, across both classification axes."
  []
  (set (for [[_axis decls] (elision-registry)
             :when (map? decls)
             [_path owners] decls
             owner owners
             :when (= :machine (:source owner))]
         (:actor-id owner))))

(def ^:private classified-finisher
  {:sensitive [[:data :token]]
   :large     [[:data :blob]]
   :initial   :idle
   :data      {:token "t" :blob "b"}
   :states    {:idle {:on {:finish :done}}
               :done {:final? true}}})

(deftest final-auto-destroy-drops-a-singletons-claims
  (testing "a classified SINGLETON finishing via :final? on a non-birth event
            leaves no claim behind"
    (rf/reg-machine :fade/single classified-finisher)
    (rf/dispatch-sync [:fade/single [:rf.machine/start]])
    (is (contains? (machine-claim-actors) :fade/single)
        "precondition: the singleton's claim lowered at boot")
    (rf/dispatch-sync [:fade/single [:finish]])
    (is (nil? (snapshot :fade/single)) "the singleton auto-destroyed (D4/D7)")
    (is (not (contains? (machine-claim-actors) :fade/single))
        "the finished singleton's claim was dropped and NOT re-installed by the
         finalize commit")))

(deftest final-auto-destroy-drops-each-spawned-actors-claims
  (testing "three classified SPAWNED actors finishing via :final? leave the
            registry holding no claim for any of them"
    (rf/reg-machine :fade/child classified-finisher)
    (rf/reg-event :fade/spawn
      (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :fade/child}]]}))
    (dotimes [_ 3] (rf/dispatch-sync [:fade/spawn]))
    (is (= #{:fade/child#1 :fade/child#2 :fade/child#3} (machine-claim-actors))
        "precondition: one claim owner per live spawned instance")
    (doseq [id [:fade/child#1 :fade/child#2 :fade/child#3]]
      (rf/dispatch-sync [id [:finish]])
      (is (nil? (snapshot id)) "the spawned actor auto-destroyed"))
    (is (empty? (machine-claim-actors))
        "no dead actor's claim survives its :final? auto-destroy")))
