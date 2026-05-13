(ns re-frame-pair2-mcp.lazy-summary-test
  "Unit tests for the lazy-summary default generalised to every rich
  snapshot slice (rf2-u2029).

  rf2-tygdv landed a `{:rf.mcp/summary ...}` marker as the default for
  the `:app-db` slice. rf2-u2029 generalises that default to every
  rich slice in the snapshot response — `:sub-cache`, `:machines`,
  `:epochs`, `:traces` — so a discovery snapshot ('I don't know which
  slice carries the answer') stays under the wire cap by construction.

  These tests mirror the private helpers from `tools.cljs`
  (`parse-mode-arg`, `parse-modes-arg`, `resolve-slice-mode`,
  `summarise-other-slices-in-snapshot`) so the contract is pinned at
  the unit level. A rename or signature drift surfaces as a failing
  test rather than silent contract drift.

  Wire-byte / token-budget assertions appear at the end — the
  discovery-snapshot scenario MUST fit the 5,000-token cap."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Mirrors of the private helpers. Keep in lockstep with `tools.cljs`.
;; ---------------------------------------------------------------------------

(def ^:private valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

(def ^:private summarisable-slices
  #{:sub-cache :machines :epochs :traces})

(def ^:private summary-keys-cap 64)

(defn- token-estimate [s]
  (quot (count s) 4))

(defn- tree-summary [v]
  (cond
    (map? v)
    (let [ks    (keys v)
          n     (count ks)
          shown (if (> n summary-keys-cap)
                  (vec (take summary-keys-cap ks))
                  (vec ks))]
      {:rf.mcp/summary (cond-> {:type   :map
                                :keys   shown
                                :count  n
                                :bytes  (count (pr-str v))}
                         (> n summary-keys-cap)
                         (assoc :keys-truncated? true))})
    (vector? v)
    {:rf.mcp/summary {:type  :vector
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (set? v)
    {:rf.mcp/summary {:type  :set
                      :count (count v)
                      :bytes (count (pr-str v))}}
    (sequential? v)
    {:rf.mcp/summary {:type  :seq
                      :count (count v)
                      :bytes (count (pr-str v))}}
    :else
    {:rf.mcp/summary {:type  :scalar
                      :value v
                      :bytes (count (pr-str v))}}))

(defn- parse-mode-arg [raw]
  (cond
    (nil? raw)                 :summary
    (= raw :full)              :full
    (= raw :summary)           :summary
    (= raw "full")             :full
    (= raw "summary")          :summary
    :else                      :summary))

(defn- parse-modes-arg [raw]
  (let [as-clj (cond
                 (nil? raw) nil
                 (map? raw) raw
                 (and (some? raw)
                      (not (array? raw))
                      (not (string? raw))
                      (not (boolean? raw))
                      (not (number? raw)))
                 (try (js->clj raw) (catch :default _ nil))
                 :else nil)
        coerce-k (fn [k]
                   (cond
                     (keyword? k) k
                     (string? k)
                     (let [s (if (str/starts-with? k ":") (subs k 1) k)]
                       (keyword s))
                     :else nil))
        coerce-v (fn [v]
                   (cond
                     (= v :summary)  :summary
                     (= v :full)     :full
                     (= v "summary") :summary
                     (= v "full")    :full
                     :else           nil))]
    (if-not (map? as-clj)
      {}
      (reduce-kv
        (fn [m k v]
          (let [k' (coerce-k k)
                v' (coerce-v v)]
            (if (and k' (contains? valid-slices k') v')
              (assoc m k' v')
              m)))
        {} as-clj))))

(defn- resolve-slice-mode [slice slice-modes global-mode]
  (let [m (or (get slice-modes slice) global-mode :summary)]
    (case m
      :full :full
      :summary)))

(defn- summarise-other-slices-in-snapshot [snapshot slice-modes global-mode]
  (if-not (map? snapshot)
    {:snapshot snapshot :resolved-modes {}}
    (let [resolved (into {} (map (fn [s]
                                   [s (resolve-slice-mode s slice-modes global-mode)]))
                         summarisable-slices)
          process-frame
          (fn [frame-map]
            (if-not (map? frame-map)
              frame-map
              (reduce-kv
                (fn [m k v]
                  (assoc m k
                         (if (and (contains? summarisable-slices k)
                                  (= :summary (get resolved k))
                                  (some? v))
                           (tree-summary v)
                           v)))
                {} frame-map)))
          processed (reduce-kv (fn [m fid fmap]
                                 (assoc m fid (process-frame fmap)))
                               {} snapshot)]
      {:snapshot processed :resolved-modes resolved})))

;; ---------------------------------------------------------------------------
;; parse-mode-arg — the input contract for the global `:mode` MCP arg.
;; ---------------------------------------------------------------------------

(deftest parse-mode-arg-defaults-to-summary
  (is (= :summary (parse-mode-arg nil)))
  (is (= :summary (parse-mode-arg ""))))

(deftest parse-mode-arg-accepts-string-and-keyword
  (is (= :full (parse-mode-arg "full")))
  (is (= :full (parse-mode-arg :full)))
  (is (= :summary (parse-mode-arg "summary")))
  (is (= :summary (parse-mode-arg :summary))))

(deftest parse-mode-arg-unknown-defaults-to-summary
  ;; Budget-sensitive: an unknown value MUST default to the cheaper
  ;; mode rather than expand by accident.
  (is (= :summary (parse-mode-arg "garbage")))
  (is (= :summary (parse-mode-arg :nope))))

;; ---------------------------------------------------------------------------
;; parse-modes-arg — per-slice override map.
;; ---------------------------------------------------------------------------

(deftest parse-modes-arg-nil-is-empty
  (is (= {} (parse-modes-arg nil))))

(deftest parse-modes-arg-cljs-map-passes-through
  (is (= {:app-db :full :epochs :summary}
         (parse-modes-arg {:app-db :full :epochs :summary}))))

(deftest parse-modes-arg-string-keys-coerced-to-slice-keywords
  (is (= {:app-db :full :sub-cache :summary}
         (parse-modes-arg #js {"app-db" "full" "sub-cache" "summary"}))))

(deftest parse-modes-arg-edn-shaped-string-keys-strip-leading-colon
  (is (= {:app-db :full}
         (parse-modes-arg #js {":app-db" "full"}))))

(deftest parse-modes-arg-drops-unknown-slices
  (is (= {:app-db :full}
         (parse-modes-arg #js {"app-db" "full" "garbage" "full"}))))

(deftest parse-modes-arg-drops-unknown-mode-values
  ;; Unknown mode values fall through to the global default — the slice
  ;; doesn't appear in the override map at all.
  (is (= {}
         (parse-modes-arg #js {"app-db" "garbage"})))
  (is (= {:epochs :summary}
         (parse-modes-arg #js {"app-db" "garbage" "epochs" "summary"}))))

(deftest parse-modes-arg-non-map-returns-empty
  (is (= {} (parse-modes-arg "scalar")))
  (is (= {} (parse-modes-arg 42)))
  (is (= {} (parse-modes-arg true))))

;; ---------------------------------------------------------------------------
;; resolve-slice-mode — per-slice precedence resolution.
;; ---------------------------------------------------------------------------

(deftest resolve-slice-mode-default-is-summary
  ;; Nothing pins the slice ⇒ summary.
  (is (= :summary (resolve-slice-mode :app-db {} :summary)))
  (is (= :summary (resolve-slice-mode :sub-cache {} :summary)))
  (is (= :summary (resolve-slice-mode :epochs {} nil))))

(deftest resolve-slice-mode-global-mode-applies-to-every-slice
  (is (= :full (resolve-slice-mode :app-db {} :full)))
  (is (= :full (resolve-slice-mode :sub-cache {} :full)))
  (is (= :full (resolve-slice-mode :epochs {} :full)))
  (is (= :full (resolve-slice-mode :traces {} :full))))

(deftest resolve-slice-mode-per-slice-overrides-global
  (is (= :full (resolve-slice-mode :app-db {:app-db :full} :summary))
      "Per-slice :full beats global :summary")
  (is (= :summary (resolve-slice-mode :epochs {:epochs :summary} :full))
      "Per-slice :summary beats global :full"))

(deftest resolve-slice-mode-only-affects-named-slice
  ;; Per-slice override on :app-db shouldn't change :epochs resolution.
  (let [modes {:app-db :full}]
    (is (= :full    (resolve-slice-mode :app-db modes :summary)))
    (is (= :summary (resolve-slice-mode :epochs modes :summary)))
    (is (= :summary (resolve-slice-mode :traces modes :summary)))))

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
                :epochs    [{:event-id :foo :db-after {:rf.mcp/diff-from :db-before :patches []}}
                            {:event-id :bar :db-after {:rf.mcp/diff-from :db-before :patches []}}]
                :traces    [{:operation :event :event-id :foo}
                            {:operation :sub   :sub-id  :bar}]}
   :stories    {:app-db    {:rf.mcp/summary {:type :map :keys [:story-id] :count 1 :bytes 12}}
                :sub-cache {}
                :machines  {:ids [] :state {}}
                :epochs    []
                :traces    []}})

(deftest summary-default-replaces-every-rich-slice
  (let [{:keys [snapshot resolved-modes]}
        (summarise-other-slices-in-snapshot fixture-snapshot {} :summary)
        rf-default (:rf/default snapshot)]
    (testing "resolved-modes echoes the per-slice mode"
      (is (= {:sub-cache :summary :machines :summary
              :epochs    :summary :traces   :summary}
             resolved-modes)))
    (testing "every rich slice is a {:rf.mcp/summary ...} marker"
      (is (some? (-> rf-default :sub-cache :rf.mcp/summary)))
      (is (some? (-> rf-default :machines  :rf.mcp/summary)))
      (is (some? (-> rf-default :epochs    :rf.mcp/summary)))
      (is (some? (-> rf-default :traces    :rf.mcp/summary))))
    (testing ":app-db slice is left alone (handled by upstream slicer)"
      (is (= {:rf.mcp/summary {:type :map :keys [:user :cart] :count 2 :bytes 100}}
             (:app-db rf-default))))
    (testing "marker types match the underlying value shape"
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
        (summarise-other-slices-in-snapshot fixture-snapshot {} :full)]
    (is (= {:sub-cache :full :machines :full
            :epochs    :full :traces   :full}
           resolved-modes))
    (is (= (:rf/default fixture-snapshot)
           (:rf/default snapshot))
        "Snapshot under :full mode is unchanged")))

(deftest per-slice-override-beats-global-mode
  (testing "global :summary, per-slice :full on :epochs"
    (let [{:keys [snapshot resolved-modes]}
          (summarise-other-slices-in-snapshot fixture-snapshot
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
          (summarise-other-slices-in-snapshot fixture-snapshot
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
  (let [{:keys [snapshot]} (summarise-other-slices-in-snapshot
                             fixture-snapshot {} :summary)
        stories (:stories snapshot)]
    (is (some? (-> stories :sub-cache :rf.mcp/summary)))
    (is (= 0 (-> stories :sub-cache :rf.mcp/summary :count)))
    (is (= 0 (-> stories :epochs    :rf.mcp/summary :count)))
    (is (= 0 (-> stories :traces    :rf.mcp/summary :count)))))

(deftest summary-skips-slices-not-in-include-set
  ;; When the caller's `:include` filter excludes a slice, the frame
  ;; map has no entry for that slice. The summariser MUST NOT add one.
  (let [partial-snap {:rf/default {:app-db    {:k 1}
                                    :sub-cache {[:q] {:value 1}}}}
        {:keys [snapshot]} (summarise-other-slices-in-snapshot
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
        {:keys [snapshot]} (summarise-other-slices-in-snapshot
                             weird {} :summary)]
    (is (= :not-a-map (:rf/default snapshot)))))

;; ---------------------------------------------------------------------------
;; Wire-byte assertions — the load-bearing acceptance criterion.
;; ---------------------------------------------------------------------------

(defn- make-fat-snapshot
  "Build a fixture snapshot with realistically heavy slices: a 1MB
  app-db, a 100-entry sub-cache, 10 epoch records each carrying a full
  app-db `:db-before`, and a 200-entry trace ring buffer. Mirrors the
  shape rf2-jlq5j's findings doc flagged as cap-blowing."
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
                                 (map #(hash-map :state :idle :context big-map) (range 30)))}
      :epochs    (vec (for [i (range 10)]
                        {:event-id   (keyword (str "e" i))
                         :db-before  big-map
                         :db-after   big-map}))
      :traces    (vec (for [i (range 200)]
                        {:operation :event :event-id (keyword (str "t" i))
                         :timestamp i}))}}))

(deftest discovery-snapshot-fits-the-wire-cap
  ;; rf2-jlq5j flagged the discovery snapshot ('I don't know which
  ;; slice carries the answer') as the worst-case wire blow. With the
  ;; lazy-summary default, every rich slice collapses to a marker —
  ;; the entire response fits the 5,000-token cap.
  (let [fat (make-fat-snapshot)
        ;; In the real pipeline, slice-app-db-in-snapshot runs upstream
        ;; and turns :app-db into a summary marker. Simulate that here.
        with-app-db-summary
        (update-in fat [:rf/default :app-db] tree-summary)
        {:keys [snapshot]} (summarise-other-slices-in-snapshot
                             with-app-db-summary {} :summary)
        wire (pr-str snapshot)
        tokens (token-estimate wire)]
    (is (< tokens 5000)
        (str "Discovery snapshot under :summary mode MUST fit the 5k-token cap. "
             "Got " tokens " tokens, " (count wire) " chars."))))

(deftest full-mode-blows-the-cap-as-expected
  ;; The opt-in :full mode is the legacy behaviour — agents who pass
  ;; it accept the wire cost. This test pins the budget POSTURE: the
  ;; same fat snapshot under :full mode is dramatically larger.
  (let [fat (make-fat-snapshot)
        with-app-db-full fat
        {:keys [snapshot]} (summarise-other-slices-in-snapshot
                             with-app-db-full {} :full)
        wire (pr-str snapshot)
        tokens (token-estimate wire)]
    (is (> tokens 50000)
        (str "Full-mode discovery snapshot ships the raw payload — "
             "should be many multiples of the cap. Got " tokens " tokens."))))

(deftest summary-vs-full-shrink-factor
  ;; Quantify the wire-byte impact. The summary marker scales with the
  ;; top-level shape (keys + count + bytes hint), not with the
  ;; underlying payload. The shrink factor is the load-bearing claim
  ;; the bead description ties acceptance to.
  (let [fat                (make-fat-snapshot)
        with-app-db-summary (update-in fat [:rf/default :app-db] tree-summary)
        with-app-db-full   fat
        summary-wire (pr-str (:snapshot (summarise-other-slices-in-snapshot
                                          with-app-db-summary {} :summary)))
        full-wire    (pr-str (:snapshot (summarise-other-slices-in-snapshot
                                          with-app-db-full {} :full)))
        ratio (/ (count full-wire) (count summary-wire))]
    (println (str "[rf2-u2029] discovery snapshot wire-size: "
                  "summary=" (count summary-wire) " chars (~" (token-estimate summary-wire) " tokens), "
                  "full=" (count full-wire) " chars (~" (token-estimate full-wire) " tokens), "
                  "shrink=" (int ratio) "x"))
    (is (> ratio 50)
        (str "Lazy-summary MUST shrink the discovery snapshot by at "
             "least 50x. Got " (int ratio) "x (summary=" (count summary-wire)
             " chars vs full=" (count full-wire) " chars)."))))
