(ns re-frame2-pair-mcp.dx-papercuts-test
  "The batch `paths` read on `get-path`, the sampled `:bytes` hint of a
  summary marker, and the record cap on a full-mode `:epochs` slice."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.summary :as summary]
            [re-frame2-pair-mcp.test-utils :as tu]))

;; ===========================================================================
;; Batch read via the plural `paths` arg.
;; ===========================================================================

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app} :resolved-build-id nil)
    conn))

(deftest parse-paths-arg-shapes
  ;; A bare scalar isn't a batch (nil, so the caller falls back to the
  ;; singular `path`), and an explicit empty batch is distinguishable from
  ;; no batch so the tool can answer `:empty-paths`.
  (doseq [[input expected note]
          [[nil nil "absent"]
           ["   " nil "blank string"]
           ["[[:cart :total] [:user :id]]" [[:cart :total] [:user :id]]
            "an EDN string of path vectors"]
           [[[:a :b] [:c]] [[:a :b] [:c]] "a CLJS vector of paths"]
           [#js ["[:cart :total]" "[:user :id]"] [[:cart :total] [:user :id]]
            "a JS array of EDN path strings"]
           [#js [#js [":cart" ":items" "0"] #js [":user" ":id"]]
            [[:cart :items 0] [:user :id]]
            "a JS array whose entries are JS arrays of segment strings"]
           [":foo" nil "a bare scalar EDN value is not a batch"]
           ["[]" [] "an explicit empty EDN batch"]
           [#js [] [] "an explicit empty JS batch"]]]
    (is (= expected (args/parse-paths-arg input)) note)))

(deftest batch-paths-form-quotes-the-callers-paths
  ;; The paths are caller EDN, so they ride quoted into the fold.
  (let [form (get-path/batch-paths-form (ef/rt-call 'snapshot :rf/default)
                                        [[:cart :total] [:user :id]]
                                        ":rf/default"
                                        (elision/egress-opts-edn false false))]
    (is (str/includes? form "(quote [[:cart :total] [:user :id]])"))))

(deftest get-path-batch-usage-errors
  (async done
    (-> (js/Promise.all
          (into-array
            (for [[call-args reason] [[{:path "[:a]" :paths "[[:b]]"} :path-and-paths-both-supplied]
                                      [{:paths "[]"} :empty-paths]]]
              (.then (get-path/get-path-tool (fresh-conn) (tu/args->js call-args))
                     (fn [r]
                       (is (tu/error? r) (pr-str call-args))
                       (is (= reason (:reason (tu/extract-edn r))) (pr-str call-args)))))))
        (.then (fn [_] (done))))))

(deftest get-path-batch-returns-results-map
  (async done
    (let [results {[:cart :total] {:exists? true  :value 42}
                   [:user :id]    {:exists? true  :value "u-1"}
                   [:missing :k]  {:exists? false :value nil}}]
      (-> (tu/with-stubbed-eval! {:ok? true :results results :elided-count 0}
            (fn []
              (get-path/get-path-tool
                (fresh-conn) (tu/args->js {:paths "[[:cart :total] [:user :id] [:missing :k]]"
                                           :frame ":rf/default"}))))
          (.then (fn [r]
                   (let [edn (tu/extract-edn r)]
                     (is (= results (:results edn)))
                     (is (= :rf/default (:frame edn))))
                   (done)))))))

(deftest get-path-blank-runtime-result-is-isError
  ;; `(:ok? nil)` is nil, not false, so a nil envelope from a dead runtime
  ;; would otherwise fall through to ok-text.
  (async done
    (-> (tu/with-stubbed-eval! nil
          (fn []
            (get-path/get-path-tool
              (fresh-conn) (tu/args->js {:path "[:cart :total]" :frame ":rf/default"}))))
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= {:ok? false :reason :blank-eval-result :value nil}
                        (dissoc (tu/extract-edn r) :hint)))
                 (done))))))

;; ===========================================================================
;; The summary marker's `:bytes` hint samples one entry, so a slice of deep
;; entries (an `:epochs` slice) reports its full-expansion cost.
;; ===========================================================================

(defn- marker-bytes [v]
  (-> v summary/tree-summary :rf.mcp/summary :bytes))

(deftest tree-summary-bytes-samples-entry-depth
  (let [fat (zipmap (map #(keyword (str "k" %)) (range 200)) (range 200))]
    (is (> (marker-bytes (vec (repeat 5 fat))) (* 100 (marker-bytes [1 2 3 4 5])))
        "a vector of fat maps estimates far more than a vector of scalars")
    (is (> (marker-bytes (zipmap (range 5) (repeat fat))) (* 10 (marker-bytes (zipmap (range 5) (range 5)))))
        "a map of fat values estimates far more than a map of scalars")))

(deftest tree-summary-bytes-handles-empty-collections
  ;; No entry to sample: a clean 0, never NaN.
  (is (= 0 (marker-bytes [])))
  (is (= 0 (marker-bytes {}))))

;; ===========================================================================
;; Full-mode epoch-history record cap.
;; ===========================================================================

(defn- snapshot-with-n-epochs [n]
  {:rf/default {:app-db {:k 1}
                :epochs (mapv #(hash-map :event-id %) (range n))}})

(deftest full-mode-caps-epoch-history-to-most-recent
  ;; One past the documented default cap of 10 keeps the newest 10 and says
  ;; so in a sibling marker. A per-slice `{:epochs :full}` under a global
  ;; `:summary` resolves to the same cap.
  (let [snap   (snapshot-with-n-epochs 11)
        capped (pipeline/cap-full-epochs-in-snapshot snap {} :full pipeline/full-epochs-cap)
        frame  (:rf/default capped)]
    (is (= (range 1 11) (map :event-id (:epochs frame))))
    (is (= {:shown 10 :total 11 :dropped 1 :kept :most-recent}
           (dissoc (:rf.mcp/epochs-capped frame) :hint)))
    (is (= capped (pipeline/cap-full-epochs-in-snapshot snap {:epochs :full} :summary
                                                        pipeline/full-epochs-cap)))))

(deftest epoch-cap-passes-through-at-the-cap-and-outside-full-mode
  (doseq [[snap global-mode note] [[(snapshot-with-n-epochs 10) :full "at the cap"]
                                   [(snapshot-with-n-epochs 11) :summary "summary mode"]
                                   [{:rf/default {:app-db {:k 1}}} :full "no :epochs slice"]]]
    (is (= snap (pipeline/cap-full-epochs-in-snapshot snap {} global-mode pipeline/full-epochs-cap))
        note)))
