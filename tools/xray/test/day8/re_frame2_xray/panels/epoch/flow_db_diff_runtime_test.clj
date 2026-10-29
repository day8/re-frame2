(ns day8.re-frame2-xray.panels.epoch.flow-db-diff-runtime-test
  "A FLOW step's `:db` diff over the record the Epoch panel actually renders:
  a real event runs a real flow, and the record goes through the panel's own
  redaction seam (`local-render-record-state` with large values elided, as
  `epoch_panel` calls it) before `project` builds the step.

  When a classification redacts the flow's output path or an ancestor of it,
  the pre-flow snapshot holds `:rf/redacted` where the subtree stood. There is
  no structure to write the flow's output into, so the step has no diff and
  takes the scalar line, which shows the write as recorded: redacted. A
  classification of the leaf alone leaves the ancestors as maps, and that
  step diffs as any other."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.local-render :as local-render]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            ;; Loaded for their late-bind hooks: without them no flow runs
            ;; and no classification applies.
            [re-frame.flows]
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- login-flow-step
  "Classify `sensitive-paths`, run a flow writing `[:auth :token-len]` from
  `[:auth :token]`, and return the FLOW step and the app-db the event left."
  [sensitive-paths]
  (rf/reg-classification :p/auth {:sensitive sensitive-paths})
  (rf/reg-flow :p/token-len
    {:inputs [[:auth :token]] :output-path [:auth :token-len]}
    (fn [t] (count (or t ""))))
  (rf/make-frame {:id :test/f :flows [:p/token-len]})
  (rf/reg-event :p/login (fn [{:keys [db]} [_ t]] {:db (assoc-in db [:auth :token] t)}))
  (rf/dispatch-sync [:p/login "s3cret"] {:frame :test/f})
  (let [record   (last (rf.epoch/epoch-history :test/f))
        redacted (local-render/local-render-record-state
                   record :test/f {:rf.egress/include-large? false})]
    {:step (some #(when (= :flow (:step %)) %) (proj/project redacted))
     :db   (rf/app-db-value :test/f)}))

(deftest a-flow-write-under-a-redacted-ancestor-takes-the-scalar-line
  (let [{:keys [step db]} (login-flow-step [[:auth]])]
    (is (= {:auth {:token "s3cret" :token-len 6}} db)
        "the flow ran: its output is in app-db")
    (is (= {:auth :rf/redacted} (:db-pre-flow step))
        "the rendered pre-flow snapshot holds the sentinel where the subtree stood")
    (is (some? (:db-post-flow step))
        "both snapshots are present, so only the redacted ancestor decides")
    (is (nil? (proj/flow-db-diff step))
        "no diff: the write lands inside an opaque value")
    (is (= [[:auth :token-len] :rf/redacted] [(:path step) (:after step)])
        "the scalar line's slots show the write at its path, redacted")))

(deftest a-flow-write-beside-a-redacted-leaf-diffs
  (let [{:keys [step]} (login-flow-step [[:auth :token]])]
    (is (= [{:auth {:token :rf/redacted}}
            {:auth {:token :rf/redacted :token-len 6}}]
           (proj/flow-db-diff step))
        "only the leaf is classified, so the diff writes the output beside it")))

(deftest flow-db-diff-reads-every-ancestor
  (let [step (fn [pre path] {:db-pre-flow pre :db-post-flow pre :path path :after 1})]
    (testing "an opaque value at any ancestor, or at the root, has no diff"
      (is (nil? (proj/flow-db-diff (step :rf/redacted [:a :b])))
          "a whole db redacted fail-closed")
      (is (nil? (proj/flow-db-diff (step {:a {:b {:rf.size/large-elided {:bytes 9}}}} [:a :b :c])))
          "a size-elided ancestor")
      (is (nil? (proj/flow-db-diff (step {:a {:rf/redacted {:bytes 9}}} [:a :b])))
          "a sized-redaction ancestor"))
    (testing "absent and ordinary ancestors diff"
      (is (= [{} {:a {:b 1}}] (proj/flow-db-diff (step {} [:a :b])))
          "absent ancestors are created, as the flow's own write creates them")
      (is (= [{:a [{:x 0}]} {:a [{:x 1}]}] (proj/flow-db-diff (step {:a [{:x 0}]} [:a 0 :x])))
          "a vector ancestor is written by index"))))
