(ns re-frame.machine-view-unmount-teardown-cljs-test
  "Headless direct-emitter companion to the mounted proof in
  `machine_view_unmount_teardown_mounted_dom_cljs_test.cljs` (Cross-Spec
  Interaction 22): a view-unmount teardown marker leaves a live machine
  untouched, and an explicit destroy is exactly one fx-channel
  `:rf.machine/destroyed :explicit` with no lifecycle-channel destroy. CLJS
  fires the production `re-frame.views/emit-view-unmounted!`; the JVM, where
  views.cljs does not exist, replays the identical emit through the same
  `:trace/emit!` late-bind hook."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.events :as rf.events]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   [re-frame.registrar :as rf.registrar]
   [re-frame.trace.tooling :as rf.trace.tooling]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]
              [re-frame.views :as rf.views]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- capture! [k]
  (let [a (atom [])]
    (rf.trace.tooling/register-listener! k (fn [ev] (swap! a conj ev)))
    a))

(defn- fire-view-unmounted!
  [view-id render-key frame-id]
  #?(:cljs (rf.views/emit-view-unmounted! view-id render-key frame-id)
     :clj  (when-let [emit! (rf.late-bind/get-fn-cached :trace/emit!)]
             (emit! :rf.view :rf.view/unmounted
                    {:rf.view/render-key render-key
                     :rf.view/id         view-id
                     :frame              frame-id}))))

(deftest view-unmount-leaves-machine-live
  (rf/reg-machine :vut/session
    {:initial :active
     :data    {:user "alice"}
     :states  {:active {}}})
  (rf/dispatch-sync [:vut/session [:rf.machine/start]])
  (let [before (snapshot :vut/session)
        traces (capture! ::vut)]
    (fire-view-unmounted! :vut/some-view [:vut/some-view ::instance-1] :rf/default)
    (is (= 1 (count (filter #(= :rf.view/unmounted (:operation %)) @traces)))
        "the unmount marker fired, so the check below is not vacuous")
    (is (= [:active before] [(:state before) (snapshot :vut/session)])
        "the live machine's snapshot is unchanged by the unmount")))

(deftest explicit-destroy-emits-exactly-one-fx-explicit
  ;; Machines dispatches `:rf.resource/release-owner` by name; the stub
  ;; records the owner it is asked to release.
  (let [released (atom [])]
    (rf.events/reg-event :rf.resource/release-owner
      (fn [_ [_ {:keys [owner]}]] (swap! released conj owner) {}))
    (rf/reg-machine :ed/session
      {:initial :active :data {} :states {:active {}}})
    (rf/dispatch-sync [:ed/session [:rf.machine/start]])
    (rf/reg-event ::destroy (fn [_ _] {:fx [[:rf.machine/destroy :ed/session]]}))
    (let [traces (capture! ::ed)]
      (rf/dispatch-sync [::destroy])
      (is (= [[:rf.machine/destroyed :explicit]]
             (->> @traces
                  (filter #(#{:rf.machine/destroyed :rf.machine.lifecycle/destroyed} (:operation %)))
                  (map (juxt :operation (comp :reason :tags)))))
          "exactly one destroy, on the fx channel with :reason :explicit; none on the lifecycle channel")
      (is (= [nil true [[:machine :ed/session]]]
             [(snapshot :ed/session)
              (some? (rf.registrar/lookup :event :ed/session))
              @released])
          "the instance and its [:machine id] resource owner are released; the DEFINITION stands"))))
