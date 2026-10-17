(ns re-frame.flows-destroy-frame-teardown-test
  "`destroy-frame!` and per-frame flow state. The memo release is pinned by
  the `flow-frame-destroy-teardown` conformance fixture; this namespace pins
  what that fixture cannot observe: a flow's output marks vanish with the
  frame, and the definitions outlive it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- reg-scratch-flows! []
  (rf/reg-flow :creds {:inputs [[:n]] :output-path [:derived :creds] :sensitive [[:secret]]}
    (fn [n] {:secret n}))
  (rf/reg-flow :blob {:inputs [[:n]] :output-path [:derived :blob] :large? true}
    (fn [n] {:bytes n}))
  (rf/reg-event :seed (fn [_ _] {:db {:n 1}})))

(deftest destroy-frame-drops-flow-output-marks
  ;; The marks live in the frame's own elision registry, inside the state
  ;; container the destroy drops, so teardown needs no per-flow scrub.
  (let [marks #(vector (rf.elision/sensitive-declarations :fc/scratch)
                       (rf.elision/declarations :fc/scratch))]
    (reg-scratch-flows!)
    (rf/make-frame {:id :fc/scratch :flows [:creds :blob]})
    (rf/dispatch-sync [:seed] {:frame :fc/scratch})
    (is (= [[[:derived :creds :secret]] [[:derived :blob]]]
           (mapv (comp vec keys) (marks))))
    (rf.frame/destroy-frame! :fc/scratch)
    (is (= [{} {}] (marks)))))

(deftest destroy-frame-drops-the-memo-and-leaves-the-definitions
  ;; A frame later made under the same id starts with an empty memo and runs
  ;; whatever its own config names.
  (reg-scratch-flows!)
  (rf/make-frame {:id :fc/scratch :flows [:creds :blob]})
  (rf/dispatch-sync [:seed] {:frame :fc/scratch})
  (is (= #{:creds :blob} (set (keys (rf.flows.registry/frame-last-inputs-snapshot :fc/scratch)))))
  (rf.frame/destroy-frame! :fc/scratch)
  (is (= [{} #{:creds :blob}]
         [(rf.flows.registry/frame-last-inputs-snapshot :fc/scratch)
          (set (keys (rf/registrations {:source :store :kind :flow})))]))
  (rf/make-frame {:id :fc/scratch :flows [:blob]})
  (rf/dispatch-sync [:seed] {:frame :fc/scratch})
  (is (= [{:n 1 :derived {:blob {:bytes 1}}} #{:blob}]
         [(rf/app-db-value :fc/scratch)
          (set (keys (rf.flows.registry/frame-last-inputs-snapshot :fc/scratch)))])))
