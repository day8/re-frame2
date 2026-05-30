(ns re-frame.story.egress-test
  "JVM tests for the human-egress reproducibility classifier (rf2-ba86n.16,
  spec/022 §3 + spec/018 §4 T4).

  The classifier is pure data → data so the JVM corpus covers it without
  booting Reagent / the runtime. These tests pin the contract the reframe
  load-bears: human egress is NOT privacy-gated, but a shared / exported /
  copied artifact says HONESTLY whether the recipient can reproduce it
  (full / partial / view-only) and WHAT downgraded it."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.egress :as egress]))

;; ---- worse / status fold -------------------------------------------------

(deftest worse-takes-the-lower-fidelity
  (testing "worse returns the lower-fidelity of two statuses"
    (is (= :partial   (egress/worse :full :partial)))
    (is (= :view-only (egress/worse :partial :view-only)))
    (is (= :view-only (egress/worse :full :view-only)))
    (is (= :full      (egress/worse :full :full)))
    (is (= :view-only (egress/worse :view-only :partial))
        "one view-only reason wins regardless of argument order")))

;; ---- contains-fn? / edn-round-trips? -------------------------------------

(deftest contains-fn?-walks-structure
  (testing "contains-fn? finds a fn anywhere in a nested structure"
    (is (false? (egress/contains-fn? {:a 1 :b [2 3] :c #{:x}})))
    (is (true?  (egress/contains-fn? (fn [] 1))))
    (is (true?  (egress/contains-fn? {:a {:b [1 (fn [_] 2)]}})))
    (is (true?  (egress/contains-fn? #{1 (fn [] 1)})))
    (is (true?  (egress/contains-fn? [:keep inc])))
    (is (false? (egress/contains-fn? {:f :not-a-fn :v "inc"}))
        "a keyword / string that LOOKS fn-like is not a fn")))

(deftest edn-round-trips?
  (testing "plain EDN data round-trips; fn-bearing / unreadable values do not"
    (is (true?  (egress/edn-round-trips? {:label "Click me" :count 5})))
    (is (true?  (egress/edn-round-trips? [:a :b {:c #{1 2}}])))
    (is (false? (egress/edn-round-trips? {:on-click (fn [_] nil)})))
    (is (false? (egress/edn-round-trips? (fn [] 1))))))

;; ---- classify: the happy path --------------------------------------------

(deftest classify-empty-is-full
  (testing "an artifact with no downgrade reasons is fully reproducible"
    (let [r (egress/classify {:plan nil :cell-overrides nil :dropped nil})]
      (is (= :full (:status r)))
      (is (= "fully reproducible" (:label r)))
      (is (empty? (:reasons r)))
      (is (true? (egress/full? r))))))

(deftest classify-plain-overrides-is-full
  (testing "serialisable cell-overrides keep the artifact fully reproducible"
    (let [r (egress/classify {:cell-overrides {:label "Hi" :count 9}})]
      (is (= :full (:status r)))
      (is (empty? (:reasons r))))))

;; ---- classify: partial ---------------------------------------------------

(deftest classify-dropped-overrides-is-partial
  (testing "share-URL overrides that no longer apply downgrade to partial"
    (let [r (egress/classify {:dropped ["stale-arg:1" "gone:2"]})]
      (is (= :partial (:status r)))
      (is (= "partially reproducible" (:label r)))
      (is (= 1 (count (:reasons r))))
      (is (= :dropped-overrides (:code (first (:reasons r)))))
      (is (re-find #"2 overrides" (:detail (first (:reasons r))))))))

(deftest classify-network-reply-fn-is-partial
  (testing "a network stub replying via a fn downgrades to partial (route survives)"
    (let [plan {:world {:network {[:get "/api/x"] {:reply (fn [_] {})}}}}
          r    (egress/classify {:plan plan})]
      (is (= :partial (:status r)))
      (is (some #(= :network-reply-fn (:code %)) (:reasons r))))))

(deftest classify-script-fn-is-partial
  (testing "a play-script step carrying a fn downgrades to partial"
    (let [plan {:script [[:dispatch [:inc]] [:custom (fn [_] nil)]]}
          r    (egress/classify {:plan plan})]
      (is (= :partial (:status r)))
      (is (some #(= :script-fn (:code %)) (:reasons r))))))

;; ---- classify: view-only -------------------------------------------------

(deftest classify-fn-override-is-view-only
  (testing "a fn-valued cell-override makes the artifact view-only"
    (let [r (egress/classify {:cell-overrides {:on-click (fn [_] nil) :label "ok"}})]
      (is (= :view-only (:status r)))
      (is (= "view-only" (:label r)))
      (is (some #(= :override-fn (:code %)) (:reasons r))))))

(deftest classify-sub-override-fn-is-view-only
  (testing "a fn-valued :sub-overrides value makes the artifact view-only"
    (let [plan {:world {:render {:sub-overrides {[:my/sub] (fn [] 1)}}}}
          r    (egress/classify {:plan plan})]
      (is (= :view-only (:status r)))
      (is (some #(= :sub-override-fn (:code %)) (:reasons r))))))

(deftest classify-non-edn-override-is-partial-not-view-only
  (testing "a non-fn, non-round-tripping override is partial (dropped, not view-only)"
    ;; A regex is not a fn and does not EDN-round-trip to an equal value.
    (let [r (egress/classify {:cell-overrides {:pattern #?(:clj #"x" :cljs (js/RegExp. "x"))}})]
      (is (= :partial (:status r)))
      (is (some #(= :override-non-edn (:code %)) (:reasons r))))))

;; ---- classify: lowest status wins ----------------------------------------

(deftest classify-mixes-reasons-and-takes-lowest
  (testing "several reasons collect; the overall status is the lowest any implies"
    (let [plan {:world {:render {:sub-overrides {[:s] (fn [] 1)}}}} ; view-only
          r    (egress/classify {:plan           plan
                                 :cell-overrides {:count 5}        ; full
                                 :dropped        ["gone:1"]})]     ; partial
      (is (= :view-only (:status r)) "the view-only sub-override wins")
      (is (<= 2 (count (:reasons r))) "all downgrade reasons are collected"))))

;; ---- status-labels are complete ------------------------------------------

(deftest status-labels-cover-every-status
  (testing "every status has a human label"
    (doseq [s egress/statuses]
      (is (string? (get egress/status-labels s))
          (str "missing label for " s)))))
