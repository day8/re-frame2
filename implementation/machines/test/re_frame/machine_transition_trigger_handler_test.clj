(ns re-frame.machine-transition-trigger-handler-test
  "`:rf.machine/transition` fires inside the machine's event-handler scope, so
  it carries the machine's registration coord in the top-level
  `:rf.trace/trigger-handler` slot (Spec 009 §Trace correlation) — the slot
  Xray's machine inspector reads to jump to the source:

    {:kind :event :id <machine-id> :source-coord {:ns :file :line :column}}

  It also carries the dispatching frame's id as its `:frame` tag, because
  `re-frame.epoch.capture/capture-event!` admits a trace event into the epoch
  history that inspector reads only when its tags carry `:frame`. The
  machine's other trace sites are swept by `machine_trace_frame_tag_sweep_test`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest machine-transition-carries-trigger-handler-and-frame
  (rf/make-frame {:id :rf2-lf84g/frame-A})
  (rf/reg-machine :rf2-lf84g/tl
    {:initial :red
     :states  {:red {:on {:tick :green}} :green {}}})
  (let [coord (select-keys (rf/handler-meta {:source :store :kind :event :id :rf2-lf84g/tl})
                           [:ns :file :line :column])
        [tr]  (rf.machines.test-support/with-trace-capture seen
                (rf/dispatch-sync [:rf2-lf84g/tl [:tick]] {:frame :rf2-lf84g/frame-A})
                (filterv #(= :rf.machine/transition (:operation %)) @seen))]
    (is (and (symbol? (:ns coord)) (string? (:file coord)) (integer? (:line coord)))
        "the registration carries its source coord")
    (is (= {:frame                    :rf2-lf84g/frame-A
            :rf.trace/trigger-handler {:kind :event :id :rf2-lf84g/tl :source-coord coord}}
           {:frame                    (:frame (:tags tr))
            :rf.trace/trigger-handler (:rf.trace/trigger-handler tr)}))))
