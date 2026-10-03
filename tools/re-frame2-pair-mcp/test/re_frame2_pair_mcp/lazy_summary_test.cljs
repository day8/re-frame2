(ns re-frame2-pair-mcp.lazy-summary-test
  "Unit tests for the lazy-summary default across every rich snapshot
  slice.

  A `{:rf.mcp/summary ...}` marker is the default for every rich slice
  in the snapshot response — `:app-db`, `:sub-cache`, `:machines`,
  `:epochs`, `:traces` — so a discovery snapshot ('I don't know which
  slice carries the answer') stays under the wire cap by construction.

  Tests pin the public helpers directly:
  `tools.args/parse-mode-arg`, `tools.args/parse-modes-arg`,
  `tools.snapshot-pipeline/resolve-slice-mode`,
  `tools.snapshot-pipeline/summarise-other-slices-in-snapshot`,
  `tools.summary/tree-summary`. A rename or signature drift surfaces
  as a failing test rather than silent contract drift.

  Wire-byte / token-budget assertions appear at the end — the
  discovery-snapshot scenario MUST fit the 5,000-token cap."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.snapshot-pipeline :as pipeline]
            [re-frame2-pair-mcp.tools.summary :as summary]))

;; ---------------------------------------------------------------------------
;; parse-mode-arg — the input contract for the global `:mode` MCP arg.
;; ---------------------------------------------------------------------------

(deftest parse-mode-arg-resolution
  ;; Budget-sensitive: an unknown value MUST default to the cheaper
  ;; mode rather than expand by accident.
  (doseq [[input expected note]
          [[nil :summary "absent ⇒ summary"]
           ["" :summary "blank ⇒ summary"]
           ["full" :full "string full"]
           [:full :full "keyword full"]
           ["summary" :summary "string summary"]
           [:summary :summary "keyword summary"]
           ["garbage" :summary "an unknown string defaults to summary"]
           [:nope :summary "an unknown keyword defaults to summary"]]]
    (is (= expected (args/parse-mode-arg input)) note)))

;; ---------------------------------------------------------------------------
;; parse-modes-arg — per-slice override map.
;; ---------------------------------------------------------------------------

(deftest parse-modes-arg-resolution
  ;; Unknown mode values fall through to the global default — the slice
  ;; doesn't appear in the override map at all.
  (doseq [[input expected note]
          [[nil {} "absent ⇒ no overrides"]
           [{:app-db :full :epochs :summary} {:app-db :full :epochs :summary}
            "a CLJS map passes through"]
           [#js {"app-db" "full" "sub-cache" "summary"} {:app-db :full :sub-cache :summary}
            "string keys coerce to slice keywords"]
           [#js {":app-db" "full"} {:app-db :full}
            "EDN-shaped string keys strip the leading colon"]
           [#js {"app-db" "full" "garbage" "full"} {:app-db :full} "unknown slices drop"]
           [#js {"app-db" "garbage"} {} "an unknown mode value drops its slice"]
           [#js {"app-db" "garbage" "epochs" "summary"} {:epochs :summary}
            "an unknown mode value drops only its own slice"]
           ["scalar" {} "a string is not an override map"]
           [42 {} "a number is not an override map"]
           [true {} "a boolean is not an override map"]]]
    (is (= expected (args/parse-modes-arg input)) note)))

;; ---------------------------------------------------------------------------
;; resolve-slice-mode — per-slice precedence resolution.
;; ---------------------------------------------------------------------------

(deftest resolve-slice-mode-precedence
  (doseq [[slice overrides global expected note]
          [[:app-db {} :summary :summary "nothing pins the slice ⇒ summary"]
           [:sub-cache {} :summary :summary "nothing pins the slice ⇒ summary"]
           [:epochs {} nil :summary "no global mode ⇒ summary"]
           [:app-db {} :full :full "the global mode applies to every slice"]
           [:sub-cache {} :full :full "the global mode applies to every slice"]
           [:epochs {} :full :full "the global mode applies to every slice"]
           [:traces {} :full :full "the global mode applies to every slice"]
           [:app-db {:app-db :full} :summary :full "per-slice :full beats global :summary"]
           [:epochs {:epochs :summary} :full :summary "per-slice :summary beats global :full"]]]
    (is (= expected (pipeline/resolve-slice-mode slice overrides global)) note)))

;; ---------------------------------------------------------------------------
;; summarise-other-slices-in-snapshot — the load-bearing pipeline step.
;; ---------------------------------------------------------------------------

(def ^:private fixture-snapshot
  ;; Mirrors the path-slicing-test fixture shape. The :app-db slice is
  ;; already a summary marker here because slice-app-db-in-snapshot
  ;; runs upstream in the real pipeline; the function under test
  ;; skips :app-db.
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
        rf-default (:rf/default snapshot)]
    (testing "resolved-modes echoes the per-slice mode"
      (is (= {:sub-cache :summary :machines :summary
              :epochs    :summary :traces   :summary}
             resolved-modes)))
    (testing ":app-db slice is left alone (handled by upstream slicer)"
      (is (= {:rf.mcp/summary {:type :map :keys [:user :cart] :count 2 :bytes 100}}
             (:app-db rf-default))))
    (testing "every rich slice is a {:rf.mcp/summary ...} marker whose type matches the value shape"
      (is (= :map    (-> rf-default :sub-cache :rf.mcp/summary :type)))
      (is (= :map    (-> rf-default :machines  :rf.mcp/summary :type)))
      (is (= :vector (-> rf-default :epochs    :rf.mcp/summary :type)))
      (is (= :vector (-> rf-default :traces    :rf.mcp/summary :type))))
    (testing "vector counts surface for drill-down"
      (is (= 2 (-> rf-default :epochs :rf.mcp/summary :count)))
      (is (= 2 (-> rf-default :traces :rf.mcp/summary :count))))
    (testing "map keys surface for drill-down"
      (is (= [:ids :state]
             (-> rf-default :machines :rf.mcp/summary :keys))))))

(deftest full-mode-leaves-every-slice-untouched
  (let [{:keys [snapshot resolved-modes]}
        (pipeline/summarise-other-slices-in-snapshot fixture-snapshot {} :full)]
    (is (= {:sub-cache :full :machines :full
            :epochs    :full :traces   :full}
           resolved-modes))
    (is (= (:rf/default fixture-snapshot)
           (:rf/default snapshot))
        "Snapshot under :full mode is unchanged")))

(deftest per-slice-override-beats-global-mode
  (testing "global :summary, per-slice :full on :epochs"
    (let [{:keys [snapshot resolved-modes]}
          (pipeline/summarise-other-slices-in-snapshot fixture-snapshot
                                                       {:epochs :full}
                                                       :summary)
          rf-default (:rf/default snapshot)]
      (is (= :full    (:epochs    resolved-modes)))
      (is (= :summary (:sub-cache resolved-modes)))
      (is (= 2 (count (:epochs rf-default)))
          ":epochs ships full because per-slice override wins")
      (is (some? (-> rf-default :sub-cache :rf.mcp/summary))
          ":sub-cache stays summarised under global :summary")))
  (testing "global :full, per-slice :summary on :traces"
    (let [{:keys [snapshot resolved-modes]}
          (pipeline/summarise-other-slices-in-snapshot fixture-snapshot
                                                       {:traces :summary}
                                                       :full)
          rf-default (:rf/default snapshot)]
      (is (= :summary (:traces    resolved-modes)))
      (is (= :full    (:sub-cache resolved-modes)))
      (is (some? (-> rf-default :traces :rf.mcp/summary))
          ":traces is summarised because per-slice override wins")
      (is (= 2 (count (:sub-cache rf-default)))
          ":sub-cache ships full under global :full"))))

(deftest empty-slices-stay-empty
  ;; The :stories frame in the fixture has empty maps / vectors.
  ;; Summarising an empty map yields {:type :map :count 0 :keys []
  ;; :bytes ~3} — the marker is small but distinguishable from the raw
  ;; empty value. Skip the marker for nil to avoid noise.
  (let [{:keys [snapshot]} (pipeline/summarise-other-slices-in-snapshot
                             fixture-snapshot {} :summary)
        stories (:stories snapshot)]
    (is (= 0 (-> stories :sub-cache :rf.mcp/summary :count)))
    (is (= 0 (-> stories :epochs    :rf.mcp/summary :count)))
    (is (= 0 (-> stories :traces    :rf.mcp/summary :count)))))

(deftest summary-skips-slices-not-in-include-set
  ;; When the caller's `:include` filter excludes a slice, the frame
  ;; map has no entry for that slice. The summariser MUST NOT add one.
  (let [partial-snap {:rf/default {:app-db    {:k 1}
                                    :sub-cache {[:q] {:value 1}}}}
        {:keys [snapshot]} (pipeline/summarise-other-slices-in-snapshot
                             partial-snap {} :summary)
        rf-default (:rf/default snapshot)]
    (is (not (contains? rf-default :machines)))
    (is (not (contains? rf-default :epochs)))
    (is (not (contains? rf-default :traces)))
    (is (some? (-> rf-default :sub-cache :rf.mcp/summary)))))

(deftest summary-passes-through-non-map-values
  ;; A pathological frame value (scalar where a map was expected)
  ;; passes through unchanged rather than crashing.
  (let [weird {:rf/default :not-a-map}
        {:keys [snapshot]} (pipeline/summarise-other-slices-in-snapshot
                             weird {} :summary)]
    (is (= :not-a-map (:rf/default snapshot)))))

;; ---------------------------------------------------------------------------
;; Wire-byte assertions — the load-bearing property.
;; ---------------------------------------------------------------------------

(defn- make-fat-snapshot
  "Build a fixture snapshot with realistically heavy slices: a 1MB
  app-db, a 100-entry sub-cache, 10 epoch records each carrying a full
  app-db `:db-before`, and a 200-entry trace ring buffer. Mirrors the
  cap-blowing shape the lazy-summary default must tame."
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
  ;; The discovery snapshot ('I don't know which slice carries the
  ;; answer') is the worst-case wire blow. With the lazy-summary
  ;; default, every rich slice collapses to a marker — the entire
  ;; response fits the 5,000-token cap.
  (let [fat (make-fat-snapshot)
        ;; In the real pipeline, slice-app-db-in-snapshot runs upstream
        ;; and turns :app-db into a summary marker. Simulate that here.
        with-app-db-summary
        (update-in fat [:rf/default :app-db] summary/tree-summary)
        {:keys [snapshot]} (pipeline/summarise-other-slices-in-snapshot
                             with-app-db-summary {} :summary)
        wire (pr-str snapshot)
        tokens (tu/token-estimate wire)]
    (is (< tokens 5000)
        (str "Discovery snapshot under :summary mode MUST fit the 5k-token cap. "
             "Got " tokens " tokens, " (count wire) " chars."))))
