(ns re-frame2-pair-mcp.sensitive-filter-test
  "The spec/009 §Privacy default-drop of sensitive trace events and epoch
  records at the pair-MCP wire. A stamp is classified fail-closed: any
  truthy non-boolean drops too, so a transport bug cannot leak an event."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(deftest strip-sensitive-drops-true-and-malformed-truthy-stamps
  (with-redefs [js/console (clj->js {:warn (fn [& _])})] ; absorb the drift warning
    (is (= [[{:id 1 :sensitive? false} {:id 3}] 2]
           (sensitive/strip-sensitive [{:id 1 :sensitive? false}
                                       {:id 2 :sensitive? true}
                                       {:id 3}
                                       {:id 4 :sensitive? "true"}]
                                      false)))))

(deftest snapshot-scrubber-drops-sensitive-items-and-nothing-else
  ;; Only `:traces` / `:epochs` items drop, epochs on their
  ;; `:rf.epoch/sensitive?` rollup. `:app-db` / `:sub-cache` / `:machines`
  ;; arrive already projected server-side and pass verbatim, even when they
  ;; carry sensitive-looking shapes.
  (let [snap {:rf/default {:app-db    {:password "still-here" :sensitive? true}
                           :sub-cache {:user/profile {:sensitive? true :data "x"}}
                           :machines  {:auth {:state :idle}}
                           :traces    [{:id 1 :sensitive? false} {:id 2 :sensitive? true} {:id 3}]
                           :epochs    [{:event-id :foo}
                                       {:event-id :auth/sign-in :rf.epoch/sensitive? true}]}
              :stories    {:app-db {} :traces [{:id 10 :sensitive? true}]}}]
    (is (= [(-> snap
                (assoc-in [:rf/default :traces] [{:id 1 :sensitive? false} {:id 3}])
                (assoc-in [:rf/default :epochs] [{:event-id :foo}])
                (assoc-in [:stories :traces] []))
            3]
           (sensitive/scrub-snapshot-sensitive snap false)))
    (is (= [snap 0] (sensitive/scrub-snapshot-sensitive snap true)))))

(deftest sensitive-epoch?-truth-table
  ;; The qualified rollup is authoritative (the runtime never writes an
  ;; unqualified `:sensitive?` on a record) and goes through the shared
  ;; fail-closed classifier; a sensitive constituent trace event drops the
  ;; record whatever the rollup says, which covers a runtime with no rollup.
  (with-redefs [js/console (clj->js {:warn (fn [& _])})]
    (doseq [[record sensitive? note]
            [[{:rf.epoch/sensitive? true} true "the rollup alone (schema-derived sensitivity)"]
             [{:rf.epoch/sensitive? false} false "a false rollup, no constituents"]
             [{:sensitive? true} false "an unqualified :sensitive? is not the rollup"]
             [{:rf.epoch/sensitive? "true"} true "a malformed-truthy rollup fails closed"]
             [{:trace-events [{:operation :rf.event/run-start}
                              {:operation :rf.event/run-end :sensitive? true}]}
              true "no rollup, a constituent carries the stamp"]
             [{:trace-events [{:operation :rf.event/run-start}
                              {:operation :rf.event/run-end}]}
              false "no stamps anywhere"]
             [{:rf.epoch/sensitive? false :trace-events [{:sensitive? true}]}
              true "a false rollup never overrules a sensitive constituent"]]]
      (is (= sensitive? (boolean (sensitive/sensitive-epoch? record))) note))))

;; The `:epoch-vector` arm is the path trace-window and watch-epochs ship
;; epoch pages through, so these drive the filter the way the tools do.

(deftest wire-pipeline-epoch-vector-drops-sensitive-records
  ;; Either signal drops the record, and nothing of a dropped record (event
  ;; id, timing, outcome) reaches the wire.
  (let [run-end {:operation :rf.event/run-end :tags {:rf.trace/phase :run-end}}
        epochs  [{:epoch-id 1 :event-id :auth/sign-in
                  :trace-events [(assoc run-end :sensitive? true)]}
                 {:epoch-id 2 :event-id :auth/recover :rf.epoch/sensitive? true
                  :rf.epoch/redacted-modified-paths-count 1 :outcome :rf.epoch/committed
                  :trace-events [run-end]}
                 {:epoch-id 3 :event-id :cart/add :rf.epoch/sensitive? false
                  :trace-events [run-end]}
                 {:epoch-id 4 :event-id :nav/route}]
        {:keys [value indicators]}
        (wp/run-wire-pipeline epochs {:kind :epoch-vector :incl? false :mode :diff :dedup? false})]
    (is (= [3 4] (mapv :epoch-id value)))
    (is (= 2 (:dropped indicators)))))

(deftest wire-pipeline-epoch-vector-include-sensitive-passes-rollup-record
  (let [{:keys [value indicators]}
        (wp/run-wire-pipeline [{:epoch-id 1 :rf.epoch/sensitive? true}]
                              {:kind :epoch-vector :incl? true :mode :diff :dedup? false})]
    (is (= [[1] 0] [(mapv :epoch-id value) (:dropped indicators)]))))
