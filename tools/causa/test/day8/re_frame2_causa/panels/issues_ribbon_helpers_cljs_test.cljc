(ns day8.re-frame2-causa.panels.issues-ribbon-helpers-cljs-test
  "Pure-data tests for Causa's Issues panel helpers (rf2-jio48 rebuild).

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
    5. **filter axes** — severity / prefix are independent; empty
       filters disable the axis.
    6. **project-feed** — top-level composite over a focused epoch
       record; empty-kind classifier (incl. :no-focus, :epoch-evicted,
       :no-issues, :no-matches branches per spec/021 §10.7).
    7. **resolve-focus-status / find-epoch-record** — focus + history
       resolver.
    8. **epoch-has-issues?** — film-strip filter-fn callback.
    9. **format-time** — renders a stable HH:MM:SS.mmm string."
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
  "A success-path trace event — should never reach the panel."
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      1000
   :tags      {}})

(defn- epoch-record
  "Build a minimal `:rf/epoch-record`-shaped map carrying the supplied
  trace-events. `:epoch-id` defaults to 1."
  ([trace-events]
   (epoch-record 1 trace-events))
  ([epoch-id trace-events]
   {:epoch-id     epoch-id
    :trace-events (vec trace-events)}))

;; ---- (1) op-type → severity mapping ------------------------------------

(deftest op-type-severity-mapping-honours-spec
  (testing "the three issue op-types map to the panel's severity buckets"
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

(deftest severity-glyph-stable
  (is (= "▲" (h/severity-glyph :error)))
  (is (= "●" (h/severity-glyph :warning)))
  (is (= "·" (h/severity-glyph :advisory)))
  (is (= "○" (h/severity-glyph :unknown))))

;; ---- (2) issue-event? -------------------------------------------------

(deftest issue-event?-classification
  (testing "every issue op-type is an issue"
    (is (true? (h/issue-event? (error-ev    1 :rf.error/handler-threw))))
    (is (true? (h/issue-event? (warning-ev  2 :rf.warning/recoverable))))
    (is (true? (h/issue-event? (advisory-ev 3 :rf.info/note)))))
  (testing "non-issue op-types are NOT issues"
    (is (false? (h/issue-event? (non-issue-ev 1))))
    (is (false? (h/issue-event? {:id 1 :op-type :fx})))
    (is (false? (h/issue-event? {:id 1 :op-type :frame})))
    (is (false? (h/issue-event? {:id 1 :op-type :sub/run})))))

;; ---- (3) category-prefix ----------------------------------------------

(deftest category-prefix-projects-keyword-namespace
  (testing "category-prefix is the operation's keyword namespace"
    (is (= "rf.error"   (h/category-prefix (error-ev 1 :rf.error/handler-threw))))
    (is (= "rf.warning" (h/category-prefix (warning-ev 2 :rf.warning/recoverable))))
    (is (= "rf.ssr"     (h/category-prefix (warning-ev 3 :rf.ssr/hydration-mismatch))))
    (is (= "rf.info"    (h/category-prefix (advisory-ev 4 :rf.info/note))))
    (is (= "rf.route.nav-token"
           (h/category-prefix (error-ev 5 :rf.route.nav-token/rejected)))))
  (testing "category-prefix returns nil when operation has no namespace"
    (is (nil? (h/category-prefix {:operation "literal-string"})))
    (is (nil? (h/category-prefix {:operation nil})))))

;; ---- (4) project-issue ------------------------------------------------

(deftest project-issue-returns-nil-for-non-issues
  (is (nil? (h/project-issue (non-issue-ev 1)))))

(deftest project-issue-builds-row-shape
  (testing "a projected issue carries every cell the row needs"
    (let [row (h/project-issue (error-ev 7 :rf.error/handler-threw
                                         {:time 9999
                                          :tags {:reason "kaboom"}}))]
      (is (= 7                          (:id row)))
      (is (= 9999                       (:time row)))
      (is (= :error                     (:severity row)))
      (is (= :error                     (:op-type row)))
      (is (= :rf.error/handler-threw    (:operation row)))
      (is (= "rf.error"                 (:category-prefix row)))
      (is (re-find #"kaboom"            (:description row)))
      (is (some?                        (:raw row))))))

;; ---- (5) filter application -----------------------------------------

(deftest passes-severity-empty-filter-passes-all
  (is (true? (h/passes-severity? #{} {:severity :error}))))

(deftest passes-severity-filters-by-membership
  (is (true?  (h/passes-severity? #{:error} {:severity :error})))
  (is (false? (h/passes-severity? #{:error} {:severity :warning}))))

(deftest passes-category-prefix-empty-filter-passes-all
  (is (true? (h/passes-category-prefix? #{} {:category-prefix "rf.error"}))))

(deftest passes-category-prefix-filters-by-membership
  (is (true?  (h/passes-category-prefix? #{"rf.error"}
                                         {:category-prefix "rf.error"})))
  (is (false? (h/passes-category-prefix? #{"rf.error"}
                                         {:category-prefix "rf.ssr"}))))

(deftest apply-filters-composes-axes-intersectively
  (let [issues [{:id 1 :severity :error    :category-prefix "rf.error"}
                {:id 2 :severity :warning  :category-prefix "rf.error"}
                {:id 3 :severity :error    :category-prefix "rf.ssr"}
                {:id 4 :severity :advisory :category-prefix "rf.info"}]]
    (testing "no filters → every issue passes"
      (is (= 4 (count (h/apply-filters issues {})))))
    (testing "severity only narrows"
      (is (= #{1 3} (set (map :id (h/apply-filters issues
                                                   {:severities #{:error}}))))))
    (testing "prefix only narrows"
      (is (= #{1 2} (set (map :id (h/apply-filters issues
                                                   {:prefixes #{"rf.error"}}))))))
    (testing "severity AND prefix"
      (is (= #{1} (set (map :id (h/apply-filters
                                  issues
                                  {:severities #{:error}
                                   :prefixes   #{"rf.error"}}))))))))

;; ---- (6) distinct-prefixes ----------------------------------------

(deftest distinct-prefixes-first-seen-order
  (let [issues [{:category-prefix "rf.error"}
                {:category-prefix "rf.ssr"}
                {:category-prefix "rf.error"}
                {:category-prefix "rf.warning"}
                {:category-prefix nil}
                {:category-prefix "rf.ssr"}]]
    (is (= ["rf.error" "rf.ssr" "rf.warning"]
           (h/distinct-prefixes issues)))))

;; ---- (7) resolve-focus-status + find-epoch-record -------------------

(deftest resolve-focus-status-no-focus
  (testing "focus nil AND history empty → cold start, :no-focus"
    (is (= :no-focus (h/resolve-focus-status nil [])))
    (is (= :no-focus (h/resolve-focus-status nil nil)))))

(deftest resolve-focus-status-head-fallback
  (testing "rf2-h0120 — focus nil but history non-empty → head-fallback
            (resolves to :focused; the find-epoch-record lookup returns
            the most-recent record). This is the natural debugging UX —
            show the latest unless the operator explicitly picks an
            earlier row."
    (let [hist [(epoch-record 1 []) (epoch-record 2 []) (epoch-record 3 [])]]
      (is (= :focused (h/resolve-focus-status nil hist))))
    (testing "single-record history also resolves to :focused"
      (is (= :focused (h/resolve-focus-status nil [(epoch-record 1 [])]))))))

(deftest resolve-focus-status-focused-match
  (let [hist [(epoch-record 1 []) (epoch-record 2 []) (epoch-record 3 [])]]
    (is (= :focused (h/resolve-focus-status 1 hist)))
    (is (= :focused (h/resolve-focus-status 2 hist)))
    (is (= :focused (h/resolve-focus-status 3 hist)))))

(deftest resolve-focus-status-epoch-evicted
  (testing "focus has :epoch-id but history doesn't carry it → evicted"
    (let [hist [(epoch-record 5 []) (epoch-record 6 []) (epoch-record 7 [])]]
      (is (= :epoch-evicted (h/resolve-focus-status 1 hist)))
      (is (= :epoch-evicted (h/resolve-focus-status 99 hist))))))

(deftest resolve-focus-status-empty-history-with-focus-id
  (testing "focus pins an :epoch-id but the history is empty → evicted"
    (is (= :epoch-evicted (h/resolve-focus-status 1 []))))
  (testing "focus pins an :epoch-id but history is nil → evicted"
    (is (= :epoch-evicted (h/resolve-focus-status 1 nil)))))

(deftest find-epoch-record-returns-match
  (let [hist [(epoch-record 5 [(error-ev 100 :rf.error/handler-threw)])
              (epoch-record 6 [(warning-ev 101 :rf.warning/recoverable)])]]
    (is (= 5 (:epoch-id (h/find-epoch-record 5 hist))))
    (is (= 6 (:epoch-id (h/find-epoch-record 6 hist))))
    (is (nil? (h/find-epoch-record 99 hist)))))

(deftest find-epoch-record-head-fallback
  (testing "rf2-h0120 — focus nil + history non-empty returns the HEAD
            (most-recent) record. epoch-history is oldest-first per
            re-frame.epoch/epoch-history, so the head is the last
            element."
    (let [hist [(epoch-record 5 [(error-ev 100 :rf.error/handler-threw)])
                (epoch-record 6 [(warning-ev 101 :rf.warning/recoverable)])
                (epoch-record 7 [])]]
      (is (= 7 (:epoch-id (h/find-epoch-record nil hist))))))
  (testing "single-record history's head is that single record"
    (let [hist [(epoch-record 42 [(error-ev 1 :rf.error/handler-threw)])]]
      (is (= 42 (:epoch-id (h/find-epoch-record nil hist))))))
  (testing "focus nil AND history empty/nil returns nil"
    (is (nil? (h/find-epoch-record nil [])))
    (is (nil? (h/find-epoch-record nil nil)))))

;; ---- (8) project-feed top-level composite ---------------------------

(deftest project-feed-no-focus-renders-empty
  (let [feed (h/project-feed nil {} :no-focus)]
    (is (= []  (:issues feed)))
    (is (= 0   (:total feed)))
    (is (= 0   (:rendered feed)))
    (is (= :no-focus (:empty-kind feed)))
    (is (nil? (:epoch-id feed)))))

(deftest project-feed-evicted-renders-canonical-placeholder
  (testing "spec/021 §10.7 — :epoch-evicted is the discriminator the
            view branches on to render the canonical placeholder."
    (let [feed (h/project-feed nil {} :epoch-evicted)]
      (is (= :epoch-evicted (:empty-kind feed)))
      (is (= 0 (:total feed))))))

(deftest project-feed-no-issues-empty-trace-events
  (testing "focused epoch with empty :trace-events → :no-issues"
    (let [record (epoch-record 42 [])
          feed   (h/project-feed record {} :focused)]
      (is (= [] (:issues feed)))
      (is (= 0  (:total feed)))
      (is (= :no-issues (:empty-kind feed)))
      (is (= 42 (:epoch-id feed))))))

(deftest project-feed-no-issues-only-non-issue-traces
  (testing "trace-events with no issue ops → :no-issues"
    (let [record (epoch-record 42 [(non-issue-ev 1)
                                   (non-issue-ev 2)])
          feed   (h/project-feed record {} :focused)]
      (is (= [] (:issues feed)))
      (is (= :no-issues (:empty-kind feed))))))

(deftest project-feed-renders-issues-from-trace-events
  (testing "the focused epoch's :trace-events feed the projection;
            non-issue traces are silently dropped"
    (let [record (epoch-record 42
                   [(error-ev   1 :rf.error/handler-threw)
                    (non-issue-ev 2)
                    (warning-ev 3 :rf.warning/recoverable)
                    (non-issue-ev 4)
                    (advisory-ev 5 :rf.info/note)])
          feed   (h/project-feed record {} :focused)]
      (is (= 3 (:total feed)))
      (is (= 3 (:rendered feed)))
      (is (= #{1 3 5} (set (map :id (:issues feed)))))
      (is (nil? (:empty-kind feed)))
      (is (= 42 (:epoch-id feed))))))

(deftest project-feed-head-fallback-end-to-end
  (testing "rf2-h0120 — exercise the panel's sub call-site shape: when
            :rf.causa/focus carries no :epoch-id but :rf.causa/epoch-
            history has records, resolve-focus-status returns :focused,
            find-epoch-record returns the head, and project-feed
            renders the head's issues. This is the natural debugging
            UX the scenarios.cjs schema-violation scenario relies on."
    (let [hist             [(epoch-record 5 [])
                            (epoch-record 6 [(error-ev 1 :rf.error/schema-violation
                                                       {:tags {:path [:user :name]}})])]
          ;; Sub call-site shape from issues_ribbon.cljs:
          focus-epoch-id   nil
          focus-status     (h/resolve-focus-status focus-epoch-id hist)
          record           (h/find-epoch-record   focus-epoch-id hist)
          feed             (h/project-feed record {} focus-status)]
      (is (= :focused focus-status))
      (is (= 6 (:epoch-id record)) "head record is the most-recent epoch")
      (is (nil? (:empty-kind feed))
          "feed renders, not an empty state")
      (is (= 1 (:total feed)))
      (is (= 1 (:rendered feed)))
      (is (= [1] (mapv :id (:issues feed))))
      (is (= 6 (:epoch-id feed)) "feed epoch-id reflects the head"))))

(deftest project-feed-newest-first
  (testing "the feed reverses the trace-events stream — newest first"
    (let [record (epoch-record 1 [(error-ev   1 :rf.error/a {:time 100})
                                  (warning-ev 2 :rf.warning/b {:time 200})
                                  (error-ev   3 :rf.error/c {:time 300})])
          feed   (h/project-feed record {} :focused)]
      (is (= [3 2 1] (mapv :id (:issues feed)))))))

(deftest project-feed-empty-kind-no-matches-when-filters-hide-all
  (testing "issues exist in the focused epoch but the chip filters hide
            them all → :no-matches"
    (let [record (epoch-record 1 [(error-ev 1 :rf.error/handler-threw)])
          feed   (h/project-feed record {:severities #{:advisory}} :focused)]
      (is (= 1 (:total feed)))
      (is (= 0 (:rendered feed)))
      (is (= :no-matches (:empty-kind feed))))))

(deftest project-feed-histograms-are-epoch-scoped
  (testing "severity-counts and distinct-prefixes reflect the focused
            epoch's :trace-events — NOT a global stream"
    (let [record (epoch-record 1 [(error-ev   1 :rf.error/handler-threw)
                                  (warning-ev 2 :rf.warning/recoverable)
                                  (error-ev   3 :rf.ssr/hydration-mismatch)])
          feed   (h/project-feed record {} :focused)]
      (is (= {:error 2 :warning 1} (:severity-counts feed)))
      (is (= ["rf.error" "rf.warning" "rf.ssr"]
             (:distinct-prefixes feed))))))

(deftest project-feed-chip-filter-anding
  (testing "chip filters AND on top of the focused-epoch projection"
    (let [record (epoch-record 1 [(error-ev   1 :rf.error/handler-threw)
                                  (warning-ev 2 :rf.warning/missing-doc)
                                  (error-ev   3 :rf.ssr/hydration-mismatch)])
          feed   (h/project-feed record
                                 {:severities #{:error}
                                  :prefixes   #{"rf.error"}}
                                 :focused)]
      (is (= 3 (:total feed)))
      (is (= 1 (:rendered feed)))
      (is (= [1] (map :id (:issues feed)))))))

;; ---- (9) film-strip filter-fn slot ----------------------------------

(deftest epoch-has-issues?-empty-record
  (is (false? (h/epoch-has-issues? nil)))
  (is (false? (h/epoch-has-issues? {})))
  (is (false? (h/epoch-has-issues? (epoch-record 1 [])))))

(deftest epoch-has-issues?-only-non-issues
  (is (false? (h/epoch-has-issues?
                (epoch-record 1 [(non-issue-ev 1) (non-issue-ev 2)])))))

(deftest epoch-has-issues?-with-issue
  (is (true? (h/epoch-has-issues?
               (epoch-record 1 [(non-issue-ev 1)
                                (warning-ev 2 :rf.warning/recoverable)]))))
  (is (true? (h/epoch-has-issues?
               (epoch-record 1 [(error-ev 1 :rf.error/handler-threw)])))))

;; ---- (10) format-time ---------------------------------------------

(deftest format-time-renders-hms-with-millis
  (testing "format-time returns nil on non-numeric input"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not a number"))))
  (testing "format-time returns a HH:MM:SS.mmm-shaped string on numeric input"
    (let [s (h/format-time 12345)]
      (is (string? s))
      (is (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}$" s)))))

;; ---- (11) find-issue ----------------------------------------------

(deftest find-issue-by-id
  (let [rows [{:id 1 :severity :error}
              {:id 2 :severity :warning}
              {:id 3 :severity :advisory}]]
    (is (= {:id 2 :severity :warning} (h/find-issue rows 2)))
    (is (nil? (h/find-issue rows 99)))))

;; ---- (12) short-description ---------------------------------------

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

;; ---- (13) source-coord ------------------------------------------

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
