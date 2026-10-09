(ns re-frame.flows-destroy-frame-teardown-test
  "`destroy-frame!` and per-frame flow state. The registry and dirty-check
  release is pinned by the `flow-frame-destroy-teardown` conformance fixture;
  this namespace pins what that fixture cannot observe: a flow's output marks
  vanish with the frame, and a destroyed frame refuses a registration."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest destroy-frame-drops-flow-output-marks
  ;; The marks live in the frame's own elision registry, inside the state
  ;; container the destroy drops, so teardown needs no per-flow scrub.
  (let [marks #(vector (rf.elision/sensitive-declarations :fc/scratch)
                       (rf.elision/declarations :fc/scratch))]
    (rf/make-frame {:id :fc/scratch})
    (rf/reg-flow :creds {:frame :fc/scratch :inputs [[:n]] :output-path [:derived :creds]
                         :sensitive [[:secret]]}
      (fn [n] {:secret n}))
    (rf/reg-flow :blob {:frame :fc/scratch :inputs [[:n]] :output-path [:derived :blob] :large? true}
      (fn [n] {:bytes n}))
    (is (= [[[:derived :creds :secret]] [[:derived :blob]]]
           (mapv (comp vec keys) (marks))))
    (rf.frame/destroy-frame! :fc/scratch)
    (is (= [{} {}] (marks)))))

(deftest reg-flow-against-destroyed-frame-rejects-and-mutates-nothing
  ;; A non-live frame runs the serialized thunk in line, so without the
  ;; refusal a registration would leave a row a later same-id frame inherits.
  (rf/make-frame {:id :fc/scratch})
  (rf.frame/destroy-frame! :fc/scratch)
  (let [before [(rf.flows/flows-snapshot) (rf.flows/last-inputs-snapshot)]]
    (is (= {:rf.error/id :rf.error/flow-frame-not-live :frame :fc/scratch}
           (select-keys (ex-data (try (rf/reg-flow :leak/probe {:frame :fc/scratch :inputs [[:n]] :output-path [:out]}
                                                   identity)
                                      nil
                                      (catch clojure.lang.ExceptionInfo e e)))
                        [:rf.error/id :frame])))
    (is (= before [(rf.flows/flows-snapshot) (rf.flows/last-inputs-snapshot)]))))
