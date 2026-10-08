(ns re-frame2-pair-mcp.lazy-summary-test
  "The lazy-summary default: every rich snapshot slice ships as a
  `{:rf.mcp/summary ...}` marker unless the global `mode` or a per-slice
  `modes` override asks for `:full`, so a discovery snapshot fits the
  wire cap by construction."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.summary :as summary]))

(deftest parse-mode-arg-resolution
  ;; Budget-sensitive: an unknown value MUST default to the cheaper mode.
  (doseq [[input expected note]
          [[nil :summary "absent ⇒ summary"]
           ["full" :full "string full"]
           ["garbage" :summary "an unknown string defaults to summary"]]]
    (is (= expected (args/parse-mode-arg input)) note)))

(deftest parse-modes-arg-resolution
  ;; Unknown mode values fall through to the global default — the slice
  ;; doesn't appear in the override map at all.
  (doseq [[input expected note]
          [[nil {} "absent ⇒ no overrides"]
           [{:app-db :full :epochs :summary} {:app-db :full :epochs :summary}
            "a CLJS map passes through"]
           [#js {"app-db" "full" "sub-cache" "garbage" "garbage" "full"} {:app-db :full}
            "string keys coerce; an unknown mode drops only its own slice; unknown slices drop"]
           [#js {":epochs" "summary"} {:epochs :summary}
            "EDN-shaped string keys strip the leading colon"]
           ["scalar" {} "a non-map is not an override map"]]]
    (is (= expected (args/parse-modes-arg input)) note)))

(def ^:private fixture-snapshot
  ;; :app-db is already a summary marker: slice-app-db-in-snapshot runs
  ;; upstream, and the function under test skips it.
  {:rf/default {:app-db    {:rf.mcp/summary {:type :map :keys [:user :cart] :count 2 :bytes 100}}
                :sub-cache {[:user/email] {:value "a@b" :ref-count 1}
                            [:cart/total] {:value 42   :ref-count 3}}
                :machines  {:ids [:auth-fsm] :state {:auth-fsm {:state :idle}}}
                :epochs    [{:event-id :foo :db-after {:rf.mcp/diff-from :db-before :sections []}}
                            {:event-id :bar :db-after {:rf.mcp/diff-from :db-before :sections []}}]
                :traces    [{:operation :rf.event/dispatched :event-id :foo}
                            {:operation :rf.sub/run          :sub-id  :bar}]}
   :stories    {:app-db    {:rf.mcp/summary {:type :map :keys [:story-id] :count 1 :bytes 12}}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    []
                :traces    []}})

(deftest summary-default-replaces-every-rich-slice
  (let [{:keys [snapshot resolved-modes]}
        (pipeline/summarise-other-slices-in-snapshot fixture-snapshot {} :summary)
        marker-shape (fn [frame]
                       (update-vals (dissoc frame :app-db) #(dissoc (:rf.mcp/summary %) :bytes)))]
    (is (= {:sub-cache :summary :machines :summary :epochs :summary :traces :summary}
           resolved-modes))
    (is (= (update-vals fixture-snapshot :app-db) (update-vals snapshot :app-db))
        ":app-db is left to the upstream slicer")
    (is (= {:rf/default {:sub-cache {:type :map :keys [[:user/email] [:cart/total]] :count 2}
                         :machines  {:type :map :keys [:ids :state] :count 2}
                         :epochs    {:type :vector :count 2}
                         :traces    {:type :vector :count 2}}
            :stories    {:sub-cache {:type :map :keys [] :count 0}
                         :machines  {:type :map :keys [:ids :state] :count 2}
                         :epochs    {:type :vector :count 0}
                         :traces    {:type :vector :count 0}}}
           (update-vals snapshot marker-shape)))))

(deftest full-mode-leaves-every-slice-untouched
  (is (= {:snapshot       fixture-snapshot
          :resolved-modes {:sub-cache :full :machines :full :epochs :full :traces :full}}
         (pipeline/summarise-other-slices-in-snapshot fixture-snapshot {} :full))))

(deftest per-slice-override-beats-global-mode
  (let [rf-default (:rf/default fixture-snapshot)]
    (testing "global :summary, per-slice :full on :epochs"
      (let [{:keys [snapshot resolved-modes]}
            (pipeline/summarise-other-slices-in-snapshot fixture-snapshot {:epochs :full} :summary)]
        (is (= {:sub-cache :summary :machines :summary :epochs :full :traces :summary}
               resolved-modes))
        (is (= (:epochs rf-default) (-> snapshot :rf/default :epochs)))
        (is (some? (-> snapshot :rf/default :sub-cache :rf.mcp/summary)))))
    (testing "global :full, per-slice :summary on :traces"
      (let [{:keys [snapshot resolved-modes]}
            (pipeline/summarise-other-slices-in-snapshot fixture-snapshot {:traces :summary} :full)]
        (is (= {:sub-cache :full :machines :full :epochs :full :traces :summary}
               resolved-modes))
        (is (some? (-> snapshot :rf/default :traces :rf.mcp/summary)))
        (is (= (:sub-cache rf-default) (-> snapshot :rf/default :sub-cache)))))))

(deftest summary-skips-slices-not-in-include-set
  ;; A slice the caller's `:include` filter left out MUST NOT be added.
  (let [{:keys [snapshot]} (pipeline/summarise-other-slices-in-snapshot
                             {:rf/default {:app-db {:k 1} :sub-cache {[:q] {:value 1}}}} {} :summary)]
    (is (= #{:app-db :sub-cache} (set (keys (:rf/default snapshot)))))))

(defn- make-fat-snapshot
  "Cap-blowing slices: a 1MB app-db, a 100-entry sub-cache, 30 machines
  each carrying the app-db, 10 epochs each carrying it twice, and a
  200-entry trace buffer."
  []
  (let [big-map (apply hash-map
                       (mapcat (fn [i] [(keyword (str "k" i))
                                        (apply str (repeat 1024 "x"))])
                               (range 1024)))]
    {:rf/default
     {:app-db    big-map
      :sub-cache (zipmap (map #(vector (keyword "q" (str "s" %))) (range 100))
                         (map #(hash-map :value % :ref-count %) (range 100)))
      :machines  {:ids   (mapv #(keyword (str "m" %)) (range 30))
                  :state (zipmap (map #(keyword (str "m" %)) (range 30))
                                 (map (fn [_] (hash-map :state :idle :context big-map)) (range 30)))}
      :epochs    (vec (for [i (range 10)]
                        {:event-id   (keyword (str "e" i))
                         :db-before  big-map
                         :db-after   big-map}))
      :traces    (vec (for [i (range 200)]
                        {:operation :rf.event/dispatched :event-id (keyword (str "t" i))
                         :timestamp i}))}}))

(deftest discovery-snapshot-fits-the-wire-cap
  (let [fat (make-fat-snapshot)
        ;; In the real pipeline slice-app-db-in-snapshot summarises :app-db upstream.
        {:keys [snapshot]} (pipeline/summarise-other-slices-in-snapshot
                             (update-in fat [:rf/default :app-db] summary/tree-summary) {} :summary)
        wire   (pr-str snapshot)
        tokens (tu/token-estimate wire)]
    (is (< tokens 5000)
        (str "Discovery snapshot under :summary mode MUST fit the 5k-token cap. "
             "Got " tokens " tokens, " (count wire) " chars."))))
