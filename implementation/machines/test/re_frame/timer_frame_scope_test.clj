(ns re-frame.timer-frame-scope-test
  "`re-frame.machines.timer/after-timers` partitions by frame-id: a state
  exit's `after-cancel-fx` and `destroy-frame!`'s `:machines/on-frame-destroyed!`
  hook each touch only their own frame's timers, while the 0-arity
  `reset-timers!` (the fixture-teardown shape) clears every frame."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- frames-with-timers []
  (set (keys @rf.machines.timer/after-timers)))

(deftest timer-cleanup-is-frame-scoped
  ;; One machine on four frames arms the same inner key in each; every cleanup
  ;; below must leave the other frames' entries alone.
  (rf/reg-machine :fs/m {:initial :idle
                         :data    {}
                         :states  {:idle    {:on {:fetch :loading}}
                                   :loading {:after {3600000 :timeout}
                                             :on    {:loaded :ready}}
                                   :timeout {}
                                   :ready   {}}})
  (doseq [f [:fs/a :fs/b :fs/c :fs/d]]
    (rf/make-frame {:id f})
    (rf/dispatch-sync [:fs/m [:fetch]] {:frame f}))
  (is (= #{:fs/a :fs/b :fs/c :fs/d} (frames-with-timers))
      "precondition: every frame armed its :after timer")
  (rf/dispatch-sync [:fs/m [:loaded]] {:frame :fs/a})
  (is (= #{:fs/b :fs/c :fs/d} (frames-with-timers))
      "a state exit cancels only its own frame's timer")
  (rf/destroy-frame! :fs/b)
  (is (= #{:fs/c :fs/d} (frames-with-timers))
      "destroy-frame! releases only the destroyed frame's timers")
  (rf.machines/reset-timers!)
  (is (= {} @rf.machines.timer/after-timers)
      "the 0-arity reset-timers! clears every frame"))
