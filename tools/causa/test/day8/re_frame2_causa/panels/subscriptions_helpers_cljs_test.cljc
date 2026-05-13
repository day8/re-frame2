(ns day8.re-frame2-causa.panels.subscriptions-helpers-cljs-test
  "Pure-data tests for Causa's Subscriptions panel helpers
  (Phase 5, rf2-x0f5v).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `causality_graph_cljs_test.cljc` and
  `time_travel_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **compute-status** — the five-status state machine that
       classifies one cache entry against the just-settled cascade's
       sub-run record. Tests cover every branch + the precedence rules
       (errors win over re-running; re-running wins over fresh).
    2. **project-rows** — the fold over sub-cache + sub-runs + error
       cache. Asserts row shape, ordering (errors first), counts.
    3. **filter-by-status** — the chip-toggle filter; empty set =
       identity, non-empty = restriction.
    4. **compute-chain** — the invalidation-chain walk. Asserts
       focused-sub row, inputs filtering (drops inputs that did not
       re-run), missing-sub branch, app-db-path attribution.
    5. **status-counts** — the sidebar-badge feed."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.subscriptions-helpers :as h]))

;; ---- (1) compute-status -------------------------------------------------

(deftest compute-status-error-wins-over-everything
  (testing "an error on a sub overrides every other signal"
    (is (= :error (h/compute-status {:ref-count 1 :rerunning? true
                                     :invalidated? true}
                                    {:recomputed? true}
                                    true)))))

(deftest compute-status-re-running-wins-over-recomputed
  (testing ":re-running takes precedence over a fresh recompute — the
            cache flag is the runtime's mid-recompute signal"
    (is (= :re-running (h/compute-status {:ref-count 1 :rerunning? true}
                                         {:recomputed? true}
                                         false)))))

(deftest compute-status-recomputed-this-epoch-is-fresh
  (testing "a sub that re-ran this cascade and produced a value is :fresh"
    (is (= :fresh (h/compute-status {:ref-count 1}
                                    {:recomputed? true}
                                    false)))))

(deftest compute-status-invalidated-but-no-recompute
  (testing "an :invalidated? cache entry whose sub didn't re-run is
            :invalidated — inputs changed but no watcher drove the recompute"
    (is (= :invalidated (h/compute-status {:ref-count 0 :invalidated? true}
                                          nil
                                          false)))))

(deftest compute-status-cached-no-watcher
  (testing "ref-count zero + no invalidation surfaces :cached-no-watcher"
    (is (= :cached-no-watcher (h/compute-status {:ref-count 0}
                                                nil
                                                false)))))

(deftest compute-status-default-fresh
  (testing "a stably cached entry with a watcher and no signal of
            invalidation is :fresh (the panel displays the cached
            value as current)"
    (is (= :fresh (h/compute-status {:ref-count 2}
                                    nil
                                    false)))))

(deftest compute-status-nil-ref-count-is-cached-no-watcher
  (testing "an entry without a :ref-count slot defaults to
            :cached-no-watcher rather than throwing"
    (is (= :cached-no-watcher (h/compute-status {} nil false)))))

;; ---- (2) project-rows ---------------------------------------------------

(deftest project-rows-empty-cache-is-empty
  (testing "no sub-cache => empty rows"
    (is (= [] (h/project-rows nil nil nil)))
    (is (= [] (h/project-rows {} nil nil)))))

(deftest project-rows-emits-row-per-cache-entry
  (testing "one row per cache entry, status filled from compute-status"
    (let [cache    {[:cart/total]   {:value 42 :ref-count 1 :layer 3
                                     :input-subs [[:cart/items]]}
                    [:cart/items]   {:value [] :ref-count 1 :layer 2
                                     :input-subs [[:cart/items-raw]]}
                    [:cart/items-raw] {:value [] :ref-count 1 :layer 1}}
          rows     (h/project-rows cache nil nil)
          ids      (set (map :sub-id rows))]
      (is (= 3 (count rows)))
      (is (= #{:cart/total :cart/items :cart/items-raw} ids)))))

(deftest project-rows-marks-recomputed-from-sub-runs
  (testing "a query-v present in :sub-runs with :recomputed? true gets
            :fresh + :recomputed? true on the row"
    (let [cache    {[:cart/total] {:ref-count 1 :layer 3}}
          sub-runs [{:sub-id :cart/total :query-v [:cart/total]
                     :recomputed? true}]
          row      (first (h/project-rows cache sub-runs nil))]
      (is (= :fresh (:status row)))
      (is (true? (:recomputed? row))))))

(deftest project-rows-marks-errors
  (testing "an entry whose query-v lands in error-cache gets :error
            regardless of other signals"
    (let [cache    {[:cart/total] {:ref-count 1 :rerunning? true}}
          err      {[:cart/total] (ex-info "boom" {})}
          row      (first (h/project-rows cache nil err))]
      (is (= :error (:status row)))
      (is (some? (:error row))))))

(deftest project-rows-sorts-errors-first
  (testing "the canonical sort order — errors first, then re-running,
            invalidated, fresh, cached-no-watcher — per spec
            §Filtering and grouping"
    (let [cache    {[:a/fresh]    {:ref-count 1}
                    [:b/cached]   {:ref-count 0}
                    [:c/error]    {:ref-count 1}
                    [:d/invalid]  {:invalidated? true :ref-count 0}
                    [:e/running]  {:rerunning? true :ref-count 1}}
          err      {[:c/error] (ex-info "boom" {})}
          rows     (h/project-rows cache nil err)
          statuses (map :status rows)]
      (is (= [:error :re-running :invalidated :fresh :cached-no-watcher]
             statuses)))))

(deftest project-rows-includes-layer-and-input-subs
  (testing "layer + input-subs are propagated onto the row so the view
            can render the L1/L2/L3 pill and the chain affordance"
    (let [cache {[:cart/total] {:ref-count 1 :layer 3
                                :input-subs [[:cart/items]]}}
          row   (first (h/project-rows cache nil nil))]
      (is (= 3 (:layer row)))
      (is (= [[:cart/items]] (:input-subs row))))))

;; ---- (3) filter-by-status -----------------------------------------------

(deftest filter-by-status-empty-set-returns-all
  (testing "the empty filter shows all rows (per spec)"
    (let [rows [{:status :fresh}
                {:status :error}
                {:status :invalidated}]]
      (is (= rows (h/filter-by-status rows nil)))
      (is (= rows (h/filter-by-status rows #{}))))))

(deftest filter-by-status-restricts-to-set
  (testing "a non-empty filter keeps only matching rows"
    (let [rows [{:status :fresh}
                {:status :error}
                {:status :invalidated}
                {:status :fresh}]]
      (is (= [{:status :error}]
             (h/filter-by-status rows #{:error})))
      (is (= [{:status :fresh} {:status :fresh}]
             (h/filter-by-status rows #{:fresh}))))))

;; ---- (4) compute-chain --------------------------------------------------

(deftest compute-chain-missing-sub-marks-missing
  (testing "computing a chain for a sub not in the cache returns
            :missing? true with empty inputs"
    (let [chain (h/compute-chain [:cart/total] {} nil nil nil)]
      (is (true? (:missing? chain)))
      (is (= [] (:inputs chain))))))

(deftest compute-chain-includes-focused-row
  (testing "compute-chain emits the focused sub's projected row at :focused"
    (let [cache  {[:cart/total] {:ref-count 1 :layer 3
                                  :input-subs [[:cart/items]]}
                  [:cart/items] {:ref-count 1 :layer 2}}
          chain  (h/compute-chain [:cart/total] cache nil nil nil)
          f      (:focused chain)]
      (is (false? (:missing? chain)))
      (is (= [:cart/total] (:query-v f)))
      (is (= 3 (:layer f)))
      (is (= [[:cart/items]] (:input-subs f))))))

(deftest compute-chain-drops-inputs-that-did-not-rerun
  (testing "inputs that did not re-run this cascade are not part of the
            chain (per spec §How the chain is computed step 2)"
    (let [cache    {[:cart/total]  {:ref-count 1 :layer 3
                                     :input-subs [[:a] [:b]]}
                    [:a]           {:ref-count 1 :layer 2}
                    [:b]           {:ref-count 1 :layer 2}}
          ;; Only :a re-ran this cascade.
          sub-runs [{:sub-id :cart/total :query-v [:cart/total]
                     :recomputed? true}
                    {:sub-id :a :query-v [:a] :recomputed? true}]
          chain    (h/compute-chain [:cart/total] cache sub-runs nil nil)
          input-qs (set (map :query-v (:inputs chain)))]
      (is (= #{[:a]} input-qs)
          "only :a appears as an input — :b did not re-run, so it's dropped"))))

(deftest compute-chain-keeps-errored-inputs
  (testing "an input that errored is part of the chain even if it didn't
            re-run — the panel surfaces the error link"
    (let [cache    {[:cart/total]  {:ref-count 1 :layer 3
                                     :input-subs [[:a]]}
                    [:a]           {:ref-count 1 :layer 2}}
          err      {[:a] (ex-info "boom" {})}
          chain    (h/compute-chain [:cart/total] cache nil err nil)
          input    (first (:inputs chain))]
      (is (some? input))
      (is (= :error (:status input))))))

(deftest compute-chain-attributes-layer-1-paths
  (testing "layer-1 inputs' :paths land in :app-db-paths, intersected
            with the cascade's changed-paths when present"
    (let [cache    {[:cart/total]    {:ref-count 1 :layer 3
                                       :input-subs [[:cart/items]]}
                    [:cart/items]    {:ref-count 1 :layer 1
                                       :paths [[:cart :items]
                                               [:cart :coupon]]}}
          sub-runs [{:sub-id :cart/total :query-v [:cart/total] :recomputed? true}
                    {:sub-id :cart/items :query-v [:cart/items] :recomputed? true}]
          chain    (h/compute-chain [:cart/total] cache sub-runs nil
                                    #{[:cart :items]})]
      (is (= [[:cart :items]] (:app-db-paths chain))
          "only the changed path appears in :app-db-paths"))))

(deftest compute-chain-no-changed-paths-returns-all-layer-1-paths
  (testing "when changed-paths is nil/empty, every layer-1 input path
            surfaces in :app-db-paths"
    (let [cache    {[:cart/total]    {:ref-count 1 :layer 3
                                       :input-subs [[:cart/items]]}
                    [:cart/items]    {:ref-count 1 :layer 1
                                       :paths [[:cart :items]
                                               [:cart :coupon]]}}
          chain    (h/compute-chain [:cart/total] cache nil nil nil)]
      (is (= #{[:cart :items] [:cart :coupon]}
             (set (:app-db-paths chain)))))))

;; ---- (5) status-counts --------------------------------------------------

(deftest status-counts-tallies-by-status
  (testing "status-counts returns a frequency map keyed by status"
    (let [rows [{:status :fresh}
                {:status :fresh}
                {:status :error}
                {:status :invalidated}]]
      (is (= {:fresh 2 :error 1 :invalidated 1}
             (h/status-counts rows))))))

;; ---- (6) taxonomy invariants --------------------------------------------

(deftest every-status-has-glyph-colour-tooltip
  (testing "every status in the canonical vocabulary has a glyph,
            colour token, and tooltip — per spec §Colour is never alone"
    (doseq [s h/statuses]
      (is (some? (get h/status->glyph s))   (str "glyph for " s))
      (is (some? (get h/status->token s))   (str "token for " s))
      (is (some? (get h/status->tooltip s)) (str "tooltip for " s)))))

(deftest statuses-canonical-order
  (testing "the spec's default sort order — errors first, then
            re-running, invalidated, fresh, cached-no-watcher"
    (is (= [:error :re-running :invalidated :fresh :cached-no-watcher]
           h/statuses))))

(deftest taxonomy-has-exactly-five
  (testing "spec §The five statuses — exactly five; not four, not six"
    (is (= 5 (count h/statuses)))
    (is (= 5 (count h/status->glyph)))
    (is (= 5 (count h/status->token)))
    (is (= 5 (count h/status->tooltip)))))

;; ---- (7) formatters -----------------------------------------------------

(deftest format-query-v-prints-edn
  (testing "format-query-v pr-strs the vector"
    (is (= "[:cart/total]" (h/format-query-v [:cart/total])))
    (is (= "[:cart/sub 42]" (h/format-query-v [:cart/sub 42])))))

(deftest format-sub-id-keyword-keeps-colon
  (testing "format-sub-id renders a keyword with its colon prefix"
    (is (= ":cart/total" (h/format-sub-id :cart/total)))))
