(ns re-frame2-pair-mcp.sensitive-filter-test
  "The spec/009 §Privacy default-drop of sensitive trace events and epoch
  records at the pair-MCP wire. A stamp is classified fail-closed: any
  truthy non-boolean drops too, so a transport bug cannot leak an event."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

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
  ;; The rollup goes through the shared fail-closed classifier, and a
  ;; sensitive constituent drops the record whatever the rollup says. The
  ;; plain rollup and constituent arms are driven through the pipeline below.
  (with-redefs [js/console (clj->js {:warn (fn [& _])})] ; absorb the drift warning
    (doseq [[record note]
            [[{:rf.epoch/sensitive? "true"} "a malformed-truthy rollup fails closed"]
             [{:rf.epoch/sensitive? false :trace-events [{:sensitive? true}]}
              "a false rollup never overrules a sensitive constituent"]]]
      (is (sensitive/sensitive-epoch? record) note))))

;; The `:epoch-vector` arm is the path trace-window and watch-epochs ship
;; epoch pages through, so these drive the filter the way the tools do.

(deftest wire-pipeline-epoch-vector-drops-sensitive-records
  ;; Either signal drops the whole record: a constituent stamp with no
  ;; rollup, or the rollup alone. A false rollup and unstamped events pass.
  (let [run-end {:operation :rf.event/run-end}
        epochs  [{:epoch-id 1 :trace-events [(assoc run-end :sensitive? true)]}
                 {:epoch-id 2 :rf.epoch/sensitive? true :trace-events [run-end]}
                 {:epoch-id 3 :rf.epoch/sensitive? false :trace-events [run-end]}
                 {:epoch-id 4}]
        {:keys [value indicators]}
        (wp/run-wire-pipeline epochs {:kind :epoch-vector :incl? false :mode :diff :dedup? false})]
    (is (= [[3 4] 2] [(mapv :epoch-id value) (:dropped indicators)]))))

(deftest wire-pipeline-epoch-vector-include-sensitive-passes-rollup-record
  (let [{:keys [value indicators]}
        (wp/run-wire-pipeline [{:epoch-id 1 :rf.epoch/sensitive? true}]
                              {:kind :epoch-vector :incl? true :mode :diff :dedup? false})]
    (is (= [[1] 0] [(mapv :epoch-id value) (:dropped indicators)]))))
