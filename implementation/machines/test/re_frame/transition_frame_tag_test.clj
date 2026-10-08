(ns re-frame.transition-frame-tag-test
  "`:rf.machine/transition` MUST carry the dispatching frame's id as its
  `:frame` tag: `re-frame.epoch.capture/capture-event!` admits a trace event
  into the epoch history the Xray Machine Inspector reads only when its tags
  carry `:frame`. The machine's other trace sites are swept by
  `machine_trace_frame_tag_sweep_test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest transition-frame-matches-explicit-dispatch-frame
  (rf/make-frame {:id :rf2-hwuki/frame-A})
  (rf/reg-machine :rf2-hwuki/tl
    {:initial :red
     :states  {:red {:on {:tick :green}} :green {}}})
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec #(swap! seen conj %))
    (try (rf/dispatch-sync [:rf2-hwuki/tl [:tick]] {:frame :rf2-hwuki/frame-A})
         (finally (rf/unregister-listener! :trace ::rec)))
    (is (= :rf2-hwuki/frame-A
           (->> @seen (filter #(= :rf.machine/transition (:operation %))) first :tags :frame)))))
