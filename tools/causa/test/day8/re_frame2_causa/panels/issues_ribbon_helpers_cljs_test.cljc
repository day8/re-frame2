(ns day8.re-frame2-causa.panels.issues-ribbon-helpers-cljs-test
  "Pure-data tests for Causa's Issues ribbon helpers (Phase 5, rf2-d1p4o).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `schema_violation_timeline_helpers_cljs_
  test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **op-type → severity mapping** — every issue op-type maps to
       the correct severity bucket; non-issue op-types return nil.
    2. **issue-event?** — classifies trace events.
    3. **category-prefix** — projects `:operation`'s keyword namespace.
    4. **project-issue** — projects raw trace events onto row cells.
    5. **filter axes** — severity / prefix / since-ms are independent;
       empty filters disable the axis.
    6. **project-feed** — top-level composite; empty-kind classifier.
    7. **format-time** — renders a stable HH:MM:SS.mmm string."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- fixture builders ---------------------------------------------------

(defn- error-ev
  "Build a Spec 009-shaped error trace event."
  ([id operation]
   (error-ev id operation {}))
  ([id operation {:keys [time tags recovery]
                  :or {time 1000 tags {} recovery :no-recovery}}]
   {:id        id
    :op-type   :error
    :operation operation
    :time      time
    :recovery  recovery
    :tags      tags}))

(defn- warning-ev
  ([id operation]
   (warning-ev id operation {}))
  ([id operation {:keys [time tags] :or {time 1000 tags {}}}]
   {:id        id
    :op-type   :warning
    :operation operation
    :time      time
    :tags      tags}))

(defn- advisory-ev
  ([id operation]
   (advisory-ev id operation {}))
  ([id operation {:keys [time tags] :or {time 1000 tags {}}}]
   {:id        id
    :op-type   :info
    :operation operation
    :time      time
    :tags      tags}))

(defn- non-issue-ev
  "A success-path trace event — should never reach the ribbon."
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      1000
   :tags      {}})

;; ---- (1) op-type → severity mapping ------------------------------------

(deftest op-type-severity-mapping-honours-spec
  (testing "the three issue op-types map to the ribbon's severity buckets"
    (is (= :error    (h/op-type->severity :error)))
    (is (= :warning  (h/op-type->severity :warning)))
    (is (= :advisory (h/op-type->severity :info))))
  (testing "non-issue op-types return nil"
    (is (nil? (h/op-type->severity :event)))
    (is (nil? (h/op-type->severity :fx)))
    (is (nil? (h/op-type->severity :frame)))
    (is (nil? (h/op-type->severity :sub/run)))
    (is (nil? (h/op-type->severity :view/render)))
    (is (nil? (h/op-type->severity nil)))))

(deftest severity-colour-mapping-honours-tokens
  (testing "each severity gets the shell.cljs token-equivalent colour
            (resolved through `theme/tokens` so the rf2-0fr6v
            `:text-tertiary` contrast bump round-trips automatically)"
    (is (= (:red    tokens/tokens) (h/severity-colour :error)))
    (is (= (:yellow tokens/tokens) (h/severity-colour :warning)))
    (is (= (:cyan   tokens/tokens) (h/severity-colour :advisory)))
    (is (= (:text-tertiary tokens/tokens) (h/severity-colour :unknown)))))

(deftest severity-label-stable
  (is (= "error"    (h/severity-label :error)))
  (is (= "warning"  (h/severity-label :warning)))
  (is (= "advisory" (h/severity-label :advisory))))

;; ---- (2) issue-event? classifier ---------------------------------------

(deftest issue-event-classifier
  (testing "errors / warnings / advisories are issues"
    (is (true? (h/issue-event? (error-ev 1 :rf.error/handler-exception))))
    (is (true? (h/issue-event? (warning-ev 2 :rf.warning/missing-doc))))
    (is (true? (h/issue-event? (advisory-ev 3 :rf.http/retry-attempt)))))
  (testing "success-path / lifecycle events are NOT issues"
    (is (false? (h/issue-event? (non-issue-ev 4))))
    (is (false? (h/issue-event? {:id 5 :op-type :fx :operation :rf.fx/handled})))
    (is (false? (h/issue-event? {:id 6 :op-type :sub/run})))
    (is (false? (h/issue-event? {:id 7 :op-type :view/render})))
    (is (false? (h/issue-event? {:id 8 :op-type :frame})))))

;; ---- (3) category-prefix projection ------------------------------------

(deftest category-prefix-projection
  (testing "the five normative Spec 009 prefixes round-trip"
    (is (= "rf.error"
           (h/category-prefix (error-ev 1 :rf.error/handler-exception))))
    (is (= "rf.warning"
           (h/category-prefix (warning-ev 2 :rf.warning/missing-doc))))
    (is (= "rf.ssr"
           (h/category-prefix (warning-ev 3 :rf.ssr/hydration-mismatch))))
    (is (= "rf.fx"
           (h/category-prefix (warning-ev 4 :rf.fx/skipped-on-platform))))
    (is (= "rf.epoch"
           (h/category-prefix (error-ev 5 :rf.epoch/restore-unknown-epoch)))))
  (testing "non-keyword operations return nil"
    (is (nil? (h/category-prefix {:id 99 :op-type :error :operation nil}))))
  (testing "non-namespaced keywords return nil"
    (is (nil? (h/category-prefix {:id 100 :op-type :error
                                  :operation :bare-keyword})))))

;; ---- (4) project-issue --------------------------------------------------

(deftest project-issue-shape
  (let [ev  (error-ev 42 :rf.error/handler-exception
                      {:time 5000
                       :tags {:event [:counter/inc]
                              :exception-message "boom"
                              :dispatch-id 99}})
        row (h/project-issue ev)]
    (testing "every required slot is populated"
      (is (= 42 (:id row)))
      (is (= 5000 (:time row)))
      (is (= :error (:severity row)))
      (is (= :rf.error/handler-exception (:operation row)))
      (is (= "rf.error" (:category-prefix row)))
      (is (= 99 (:dispatch-id row))))
    (testing "the :raw slot carries the source event"
      (is (= ev (:raw row))))
    (testing "description carries the operation + a terse summary"
      (is (re-find #"rf.error/handler-exception" (:description row)))
      (is (re-find #"boom" (:description row))))))

(deftest project-issue-skips-non-issues
  (is (nil? (h/project-issue (non-issue-ev 1))))
  (is (nil? (h/project-issue {:id 2 :op-type :fx :operation :rf.fx/handled}))))

(deftest project-issues-filters-and-orders
  (let [stream [(error-ev 1 :rf.error/handler-exception {:time 100})
                (non-issue-ev 2)
                (warning-ev 3 :rf.warning/missing-doc {:time 200})
                (non-issue-ev 4)
                (advisory-ev 5 :rf.http/retry-attempt {:time 300})]
        rows   (h/project-issues stream)]
    (is (= 3 (count rows)))
    (is (= [1 3 5] (mapv :id rows)))
    (is (= [:error :warning :advisory] (mapv :severity rows)))))

;; ---- (5) filter axes ---------------------------------------------------

(deftest severity-filter-passes-when-empty
  (let [row {:severity :error}]
    (is (true? (h/passes-severity? #{} row)))
    (is (true? (h/passes-severity? nil row)))))

(deftest severity-filter-restricts-when-set
  (is (true?  (h/passes-severity? #{:error} {:severity :error})))
  (is (false? (h/passes-severity? #{:error} {:severity :warning})))
  (is (true?  (h/passes-severity? #{:error :warning}
                                  {:severity :warning}))))

(deftest prefix-filter-restricts-when-set
  (is (true?  (h/passes-category-prefix? #{} {:category-prefix "rf.error"})))
  (is (true?  (h/passes-category-prefix? #{"rf.error"}
                                         {:category-prefix "rf.error"})))
  (is (false? (h/passes-category-prefix? #{"rf.error"}
                                         {:category-prefix "rf.warning"}))))

(deftest since-filter-when-disabled-passes-all
  (let [row {:time 100}]
    (is (true? (h/passes-since? 1000 nil row)))))

(deftest since-filter-restricts-by-time
  (is (true?  (h/passes-since? 1000 500 {:time 600})))
  (is (true?  (h/passes-since? 1000 500 {:time 500})))
  (is (true?  (h/passes-since? 1000 500 {:time 1000})))
  (is (false? (h/passes-since? 1000 500 {:time 400})))
  (is (false? (h/passes-since? 1000 500 {:time 0}))))

(deftest apply-filters-composes-three-axes
  (let [now 1000
        rows [{:id 1 :severity :error    :category-prefix "rf.error"   :time 950}
              {:id 2 :severity :warning  :category-prefix "rf.warning" :time 700}
              {:id 3 :severity :advisory :category-prefix "rf.http"    :time 200}
              {:id 4 :severity :error    :category-prefix "rf.error"   :time 100}]]
    (testing "no filters → everything passes"
      (is (= [1 2 3 4]
             (mapv :id (h/apply-filters rows {} now)))))
    (testing "severity-only filter restricts"
      (is (= [1 4]
             (mapv :id (h/apply-filters rows
                                        {:severities #{:error}}
                                        now)))))
    (testing "prefix-only filter restricts"
      (is (= [2]
             (mapv :id (h/apply-filters rows
                                        {:prefixes #{"rf.warning"}}
                                        now)))))
    (testing "since-ms filter restricts (now-500ms cutoff)"
      (is (= [1 2]
             (mapv :id (h/apply-filters rows
                                        {:since-ms 500}
                                        now)))))
    (testing "three axes combine intersectively"
      (is (= [1]
             (mapv :id (h/apply-filters
                         rows
                         {:severities #{:error}
                          :prefixes   #{"rf.error"}
                          :since-ms   500}
                         now)))))))

;; ---- (6) project-feed (top-level composite) -----------------------------

(deftest project-feed-empty-stream
  (let [feed (h/project-feed [] {} 1000)]
    (is (= 0 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (= :no-issues (:empty-kind feed)))))

(deftest project-feed-no-matches
  (let [stream [(error-ev 1 :rf.error/handler-exception {:time 100})]
        feed   (h/project-feed stream {:severities #{:warning}} 1000)]
    (is (= 1 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (= :no-matches (:empty-kind feed)))))

(deftest project-feed-newest-first
  (let [stream [(error-ev 1 :rf.error/handler-exception {:time 100})
                (warning-ev 2 :rf.warning/missing-doc   {:time 200})
                (advisory-ev 3 :rf.http/retry-attempt   {:time 300})]
        feed   (h/project-feed stream {} 1000)]
    (testing ":issues is in newest-first order for display"
      (is (= [3 2 1] (mapv :id (:issues feed)))))
    (testing ":empty-kind is nil when there are matches"
      (is (nil? (:empty-kind feed))))))

(deftest project-feed-severity-counts
  (let [stream [(error-ev 1 :rf.error/handler-exception)
                (error-ev 2 :rf.error/no-such-fx)
                (warning-ev 3 :rf.warning/missing-doc)
                (advisory-ev 4 :rf.http/retry-attempt)]
        feed   (h/project-feed stream {} 1000)]
    (is (= {:error 2 :warning 1 :advisory 1}
           (:severity-counts feed)))))

(deftest project-feed-distinct-prefixes
  (let [stream [(error-ev 1 :rf.error/handler-exception)
                (warning-ev 2 :rf.warning/missing-doc)
                (warning-ev 3 :rf.ssr/hydration-mismatch)
                (error-ev 4 :rf.error/no-such-sub)]
        feed   (h/project-feed stream {} 1000)]
    (testing "distinct-prefixes is in first-seen order"
      (is (= ["rf.error" "rf.warning" "rf.ssr"]
             (:distinct-prefixes feed))))))

(deftest project-feed-merges-all-four-bead-contract-sources
  (testing "errors + warnings + schema violations + hydration mismatches
            all surface in one feed per the bead's contract"
    (let [stream [(error-ev 1 :rf.error/handler-exception     {:time 100})
                  (warning-ev 2 :rf.warning/missing-doc       {:time 200})
                  (error-ev 3 :rf.error/schema-validation-failure
                              {:time 300 :tags {:path [:auth :email]}})
                  (warning-ev 4 :rf.ssr/hydration-mismatch    {:time 400})]
          feed   (h/project-feed stream {} 1000)]
      (is (= 4 (:rendered feed)))
      ;; All four sources present, regardless of order.
      (is (= #{:rf.error/handler-exception
               :rf.warning/missing-doc
               :rf.error/schema-validation-failure
               :rf.ssr/hydration-mismatch}
             (set (mapv :operation (:issues feed))))))))

;; ---- (7) format-time ---------------------------------------------------

(deftest format-time-renders-hms-with-millis
  (testing "format-time returns nil on non-numeric input"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not a number"))))
  (testing "format-time returns a HH:MM:SS.mmm-shaped string on numeric input"
    (let [s (h/format-time 12345)]
      (is (string? s))
      (is (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}$" s)))))

;; ---- (8) find-issue ----------------------------------------------------

(deftest find-issue-by-id
  (let [rows [{:id 1 :severity :error}
              {:id 2 :severity :warning}
              {:id 3 :severity :advisory}]]
    (is (= {:id 2 :severity :warning} (h/find-issue rows 2)))
    (is (nil? (h/find-issue rows 99)))))

;; ---- (9) short-description --------------------------------------------

(deftest short-description-uses-priority-order
  (testing "reason is preferred when present"
    (is (re-find #"specific because"
                 (h/short-description
                   (error-ev 1 :rf.error/no-such-handler
                             {:tags {:reason "specific because" :event [:x]}})))))
  (testing "exception-message is used when no reason"
    (is (re-find #"boom"
                 (h/short-description
                   (error-ev 1 :rf.error/handler-exception
                             {:tags {:exception-message "boom"}})))))
  (testing "event vector is used when neither reason nor exception is set"
    (is (re-find #"counter/inc"
                 (h/short-description
                   (error-ev 1 :rf.error/no-such-handler
                             {:tags {:event [:counter/inc]}})))))
  (testing "fallback is the operation keyword alone"
    (is (= ":rf.error/handler-exception"
           (h/short-description
             (error-ev 1 :rf.error/handler-exception {:tags {}}))))))

;; ---- cascade scope (rf2-u6dhp) ------------------------------------------

(deftest project-issue-defaults-dispatch-id-to-ungrouped
  (testing "when an issue's tags carry no :dispatch-id, project-issue
            falls back to the :ungrouped sentinel — same shape that
            group-cascades uses for events outside any cascade so
            cascade-scope filtering is uniform"
    (let [row (h/project-issue (error-ev 1 :rf.error/handler-exception))]
      (is (= :ungrouped (:dispatch-id row))))))

(deftest passes-cascade-disabled-when-no-focus
  (testing "nil focus-dispatch-id disables the cascade axis"
    (is (true? (h/passes-cascade? nil {:dispatch-id 42})))
    (is (true? (h/passes-cascade? nil {:dispatch-id :ungrouped})))
    (is (true? (h/passes-cascade? nil {})))))

(deftest passes-cascade-strict-match
  (testing "with focus set the axis is strict — only issues whose
            :dispatch-id matches pass"
    (is (true?  (h/passes-cascade? 42 {:dispatch-id 42})))
    (is (false? (h/passes-cascade? 42 {:dispatch-id 99})))
    (is (false? (h/passes-cascade? 42 {:dispatch-id :ungrouped})))
    (is (true?  (h/passes-cascade? :ungrouped {:dispatch-id :ungrouped})))))

(deftest project-feed-cascade-scope-narrows-to-focused-dispatch
  (testing "with :focus-dispatch-id set the feed renders only issues
            from that cascade; issues from other cascades drop"
    (let [stream [(error-ev   1 :rf.error/handler-exception
                              {:time 100 :tags {:dispatch-id 7}})
                  (warning-ev 2 :rf.warning/missing-doc
                              {:time 200 :tags {:dispatch-id 7}})
                  (advisory-ev 3 :rf.http/retry-attempt
                               {:time 300 :tags {:dispatch-id 9}})
                  (error-ev   4 :rf.error/no-such-fx
                              {:time 400 :tags {:dispatch-id 9}})]
          feed   (h/project-feed stream {:focus-dispatch-id 7} 1000)]
      (is (= 2 (:total feed))
          "total reflects cascade-scoped count, not global")
      (is (= 2 (:rendered feed)))
      (is (= #{1 2} (set (map :id (:issues feed)))))
      (is (nil? (:empty-kind feed))))))

(deftest project-feed-empty-kind-no-issues-for-event
  (testing "when the global buffer carries issues but the focused
            cascade has none, :empty-kind is :no-issues-for-event —
            distinct from :no-issues (global buffer empty)"
    (let [stream [(error-ev 1 :rf.error/handler-exception
                            {:time 100 :tags {:dispatch-id 7}})
                  (warning-ev 2 :rf.warning/missing-doc
                              {:time 200 :tags {:dispatch-id 7}})]
          feed   (h/project-feed stream {:focus-dispatch-id 99} 1000)]
      (is (= 0 (:total feed)))
      (is (= 0 (:rendered feed)))
      (is (= :no-issues-for-event (:empty-kind feed))))))

(deftest project-feed-cascade-scope-ands-with-chip-filters
  (testing "cascade scope and chip filters compose intersectively —
            an issue must pass both axes to render"
    (let [stream [(error-ev   1 :rf.error/handler-exception
                              {:time 100 :tags {:dispatch-id 7}})
                  (warning-ev 2 :rf.warning/missing-doc
                              {:time 200 :tags {:dispatch-id 7}})
                  (error-ev   3 :rf.error/no-such-fx
                              {:time 300 :tags {:dispatch-id 9}})]
          feed   (h/project-feed stream
                                 {:focus-dispatch-id 7
                                  :severities        #{:error}}
                                 1000)]
      (is (= 2 (:total feed)) "cascade scope: 2 issues in cascade 7")
      (is (= 1 (:rendered feed)) "severity chip narrows to 1")
      (is (= [1] (map :id (:issues feed)))))))

(deftest project-feed-histograms-are-cascade-scoped
  (testing "severity-counts and distinct-prefixes reflect the cascade-
            scoped slice, NOT the global buffer — chips never surface
            for prefixes that aren't in the focused event's cascade"
    (let [stream [(error-ev   1 :rf.error/handler-exception
                              {:time 100 :tags {:dispatch-id 7}})
                  (error-ev   2 :rf.ssr/hydration-mismatch
                              {:time 200 :tags {:dispatch-id 9}})
                  (warning-ev 3 :rf.warning/missing-doc
                              {:time 300 :tags {:dispatch-id 9}})]
          feed   (h/project-feed stream {:focus-dispatch-id 7} 1000)]
      (is (= {:error 1} (:severity-counts feed)))
      (is (= ["rf.error"] (:distinct-prefixes feed))))))

(deftest project-feed-no-focus-falls-through-to-global
  (testing "when :focus-dispatch-id is nil the feed renders the global
            buffer (back-compat for callers / tests that don't seed
            the spine)"
    (let [stream [(error-ev 1 :rf.error/handler-exception
                            {:time 100 :tags {:dispatch-id 7}})
                  (warning-ev 2 :rf.warning/missing-doc
                              {:time 200 :tags {:dispatch-id 9}})]
          feed   (h/project-feed stream {} 1000)]
      (is (= 2 (:total feed)))
      (is (= 2 (:rendered feed))))))

;; ---- :ungrouped escape-hatch lane (rf2-2f40y) ---------------------------

(deftest project-ungrouped-feed-empty-stream
  (testing "an empty stream produces an empty :ungrouped projection"
    (let [feed (h/project-ungrouped-feed [])]
      (is (= [] (:issues feed)))
      (is (= 0  (:total feed))))))

(deftest project-ungrouped-feed-keeps-only-ungrouped
  (testing ":ungrouped lane surfaces only issues whose :dispatch-id is
            the :ungrouped sentinel — issues attached to a real cascade
            stay in the main cascade-scoped feed"
    (let [stream [(error-ev   1 :rf.ssr/hydration-mismatch
                              {:time 100})                 ;; no :dispatch-id → :ungrouped
                  (error-ev   2 :rf.error/handler-exception
                              {:time 200 :tags {:dispatch-id 7}})
                  (warning-ev 3 :rf.warning/missing-doc
                              {:time 300})]                ;; no :dispatch-id → :ungrouped
          feed   (h/project-ungrouped-feed stream)]
      (is (= 2 (:total feed)))
      (is (= #{1 3} (set (map :id (:issues feed))))))))

(deftest project-ungrouped-feed-newest-first
  (testing ":ungrouped lane reverses the buffer so the newest-pushed
            issue lands first — display parity with the main feed,
            which also surfaces newest first."
    ;; Stream is chronological (oldest first; mirrors the trace bus's
    ;; collect order).
    (let [stream [(error-ev   1 :rf.ssr/hydration-mismatch  {:time 100})
                  (error-ev   2 :rf.error/handler-exception {:time 200})
                  (warning-ev 3 :rf.warning/missing-doc     {:time 300})]
          feed   (h/project-ungrouped-feed stream)]
      (is (= [3 2 1] (mapv :id (:issues feed)))))))

(deftest project-ungrouped-feed-ignores-success-traces
  (testing "non-issue trace events never reach the :ungrouped lane —
            success-path traces have their own panels"
    (let [stream [{:id 1 :op-type :event :operation :event/dispatched
                   :time 100 :tags {}}
                  {:id 2 :op-type :fx :operation :rf.fx/handled
                   :time 200 :tags {}}
                  (error-ev 3 :rf.ssr/hydration-mismatch {:time 300})]
          feed   (h/project-ungrouped-feed stream)]
      (is (= 1 (:total feed)))
      (is (= [3] (mapv :id (:issues feed)))))))

;; ---- (10) source-coord ------------------------------------------------

(deftest source-coord-projection
  (testing "source-coord pulls file:line from :rf.trace/trigger-handler"
    (is (= "src/foo.cljs:42"
           (h/source-coord
             {:id 1 :op-type :error
              :operation :rf.error/handler-exception
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"
                                                        :line 42}}}))))
  (testing "missing trigger-handler returns nil"
    (is (nil? (h/source-coord {:id 1 :op-type :error
                               :operation :rf.error/handler-exception}))))
  (testing "missing :line returns just the file"
    (is (= "src/foo.cljs"
           (h/source-coord
             {:id 1 :op-type :error
              :operation :rf.error/handler-exception
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"}}})))))
