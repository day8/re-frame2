(ns re-frame.story.egress-test
  "The human-egress reproducibility classifier (spec/022 §3, spec/018 §4
  T4): a shared / exported / copied artifact says honestly whether the
  recipient can reproduce it, and what downgraded it."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.egress :as rf.story.egress]))

(deftest classify-empty-is-full
  (let [r (rf.story.egress/classify {:plan nil :cell-overrides nil :dropped nil})]
    (is (= {:status :full :label "fully reproducible" :reasons []} r))
    (is (true? (rf.story.egress/full? r)))))

(deftest classify-dropped-overrides-is-partial
  (testing "share-URL overrides that no longer apply downgrade to partial"
    (let [r (rf.story.egress/classify {:dropped ["stale-arg:1" "gone:2"]})]
      (is (= [:partial "partially reproducible" [:dropped-overrides]]
             [(:status r) (:label r) (mapv :code (:reasons r))]))
      (is (re-find #"2 overrides" (:detail (first (:reasons r))))))))

(deftest classify-downgrades-by-the-reason-it-finds
  (doseq [[label inputs status code]
          [["a network stub replying via a fn downgrades to partial (route survives)"
            {:plan {:world {:network {[:get "/api/x"] {:reply (fn [_] {})}}}}}
            :partial :network-reply-fn]
           ["a play-script step carrying a fn downgrades to partial"
            {:plan {:script [[:dispatch [:inc]] [:custom (fn [_] nil)]]}}
            :partial :script-fn]
           ["a fn-valued :sub-overrides value makes the artifact view-only"
            {:plan {:world {:render {:sub-overrides {[:my/sub] (fn [] 1)}}}}}
            :view-only :sub-override-fn]
           ;; a regex is not a fn and does not EDN-round-trip to an equal value
           ["a non-fn, non-round-tripping override is partial (dropped, not view-only)"
            {:cell-overrides {:pattern #"x"}}
            :partial :override-non-edn]]]
    (testing label
      (let [r (rf.story.egress/classify inputs)]
        (is (= status (:status r)))
        (is (some #(= code (:code %)) (:reasons r)))))))

(deftest classify-fn-override-is-view-only
  (let [r (rf.story.egress/classify {:cell-overrides {:on-click (fn [_] nil) :label "ok"}})]
    (is (= [:view-only "view-only" [:override-fn]]
           [(:status r) (:label r) (mapv :code (:reasons r))]))))

(deftest classify-mixes-reasons-and-takes-lowest
  (testing "every reason is collected; the status is the lowest any implies"
    (let [r (rf.story.egress/classify {:plan           {:world {:render {:sub-overrides {[:s] (fn [] 1)}}}}
                                       :cell-overrides {:count 5}
                                       :dropped        ["gone:1"]})]
      (is (= [:view-only [:sub-override-fn :dropped-overrides]]
             [(:status r) (mapv :code (:reasons r))])))))

;; EP-0015: human-local egress ships unredacted. The classifier answers
;; "can the recipient reproduce this?", never "is this sensitive?".
(deftest classify-does-not-redact-sensitive-values
  (is (= {:status :full :label "fully reproducible" :reasons []}
         (rf.story.egress/classify {:cell-overrides {:auth/token "BEARER-secret-12345"}}))))
