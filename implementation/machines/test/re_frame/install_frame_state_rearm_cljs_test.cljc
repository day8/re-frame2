(ns re-frame.install-frame-state-rearm-cljs-test
  "A machines subtree saved from a live frame and installed with
  `[:rf/install-frame-state saved]` is restored as saved, and its live `:after`
  is re-armed for its full declared delay and fires (Spec 002 §Installing a
  persisted frame-state)."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            #?(:cljs [cljs.reader])
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame! []
  (let [fid (keyword "rf.install" (str "client" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- round-trip
  "What a localStorage save and load does to `v`."
  [v]
  #?(:clj  (read-string (pr-str v))
     :cljs (cljs.reader/read-string (pr-str v))))

(def ^:private kid-machine
  {:initial :active
   :data    {:progress 1}
   :states  {:active {:on {:tick {:action (fn [{data :data}]
                                            {:data (update data :progress inc)})}}}}})

(def ^:private parent-machine
  {:initial :idle
   :data    {}
   :states  {:idle      {:on {:load :loading}}
             :loading   {:spawn {:machine-id :inst/kid}
                         :after {5000 {:target :timed-out}}}
             :timed-out {}}})

(deftest an-installed-machine-is-restored-and-its-after-timer-re-armed
  (rf/reg-machine :inst/kid kid-machine)
  (rf/reg-machine :inst/par parent-machine)
  (let [src    (fresh-frame!)
        _      (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
                 (rf/dispatch-sync [:inst/par [:load]] {:frame src}))
        saved  (round-trip (get-in (rf/frame-state-value src) [:rf.db/runtime :rf.runtime/machines]))
        kid    (get-in saved [:spawned :inst/par [:loading]])
        dst    (fresh-frame!)
        arms   (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk ms] (swap! arms conj [thunk ms]) ::handle)]
      (rf/dispatch-sync [:rf/install-frame-state {:rf.db/runtime {:rf.runtime/machines saved}}]
                        {:frame dst}))
    (rf/dispatch-sync [kid [:tick]] {:frame dst})
    (is (= [:loading 2 [5000]]
           [(rf.machines.test-support/machine-state dst :inst/par)
            (:progress (rf.machines.test-support/machine-data dst kid))
            (mapv second @arms)])
        "parent and child restored, the child answers its own events, and the
         :after is armed once for its full delay")
    ;; The thunk dispatches through the async router; run its drain inline.
    (with-redefs [rf.interop/next-tick (fn [f] (f) nil)]
      ((ffirst @arms)))
    (is (= [:timed-out nil]
           [(rf.machines.test-support/machine-state dst :inst/par)
            (rf.machines.test-support/snapshot dst kid)])
        "the re-armed timer fires at the persisted epoch and leaving :loading tears the child down")))
