(ns day8.re-frame2-causa.panels.mcp-server-helpers-cljs-test
  "Pure-data tests for Causa's MCP Server panel helpers (Phase 5,
  rf2-81qjj, parent rf2-5aw5v).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as the other Causa panel helper tests:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **origin classification** — `causa-mcp-event?` recognises the
       `:tags :origin :causa-mcp` tag and rejects everything else.
    2. **project-row** — projects raw trace events onto row cells;
       returns nil for non-:causa-mcp events.
    3. **filter axes** — op-type / since-ms are independent; empty
       filters disable the axis.
    4. **project-feed** — top-level composite; empty-kind classifier
       (`:no-activity` vs `:no-matches` vs nil).
    5. **agent-attached?** — pull-only proxy reading the buffer.
    6. **short-description** — priority-ordered field lift."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.mcp-server-helpers :as h]))

;; ---- fixture builders ---------------------------------------------------

(defn- mcp-ev
  "Build a Spec 009-shaped trace event with `:tags :origin :causa-mcp`."
  ([id op-type operation]
   (mcp-ev id op-type operation {}))
  ([id op-type operation {:keys [time tags]
                          :or   {time 1000 tags {}}}]
   {:id        id
    :op-type   op-type
    :operation operation
    :time      time
    :tags      (assoc tags :origin :causa-mcp)}))

(defn- app-ev
  "Build an :origin :app event (the default — not from causa-mcp)."
  [id op-type operation]
  {:id        id
   :op-type   op-type
   :operation operation
   :time      1000
   :tags      {:origin :app}})

(defn- pair-ev
  "Build an :origin :pair event (pair2-mcp, not causa-mcp)."
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      1000
   :tags      {:origin :pair}})

(defn- untagged-ev
  "Build an event with no :origin tag at all."
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      1000
   :tags      {}})

;; ---- (1) origin classification -----------------------------------------

(deftest causa-mcp-event-classifier
  (testing "events tagged :causa-mcp are causa-mcp events"
    (is (true? (h/causa-mcp-event?
                 (mcp-ev 1 :event :event/dispatched))))
    (is (true? (h/causa-mcp-event?
                 (mcp-ev 2 :error :rf.error/handler-exception)))))
  (testing "other-origin events are NOT causa-mcp events"
    (is (false? (h/causa-mcp-event? (app-ev 3 :event :event/dispatched))))
    (is (false? (h/causa-mcp-event? (pair-ev 4)))))
  (testing "untagged events are NOT causa-mcp events"
    (is (false? (h/causa-mcp-event? (untagged-ev 5))))))

(deftest origin-colour-is-locked
  (testing "v1 inferential pick — cyan #06B6D4 (DECISION (b))"
    (is (= "#06B6D4" h/causa-mcp-origin-colour))))

(deftest origin-tag-is-canonical
  (testing "the closed-set keyword matches the spec/010-MCP-Server.md
            §Origin tagging vocabulary"
    (is (= :causa-mcp h/causa-mcp-origin-tag))))

;; ---- (2) project-row ---------------------------------------------------

(deftest project-row-shape
  (let [ev  (mcp-ev 42 :event :event/dispatched
                    {:time 5000
                     :tags {:event       [:counter/inc]
                            :tool        :dispatch
                            :dispatch-id 99}})
        row (h/project-row ev)]
    (testing "every required slot is populated"
      (is (= 42 (:id row)))
      (is (= 5000 (:time row)))
      (is (= :event (:op-type row)))
      (is (= :event/dispatched (:operation row)))
      (is (= :causa-mcp (:origin row)))
      (is (= :dispatch (:tool row)))
      (is (= 99 (:dispatch-id row))))
    (testing "the :raw slot carries the source event"
      (is (= ev (:raw row))))
    (testing "description carries the operation + a terse summary"
      (is (re-find #":event/dispatched" (:description row))))))

(deftest project-row-skips-non-mcp-events
  (is (nil? (h/project-row (app-ev 1 :event :event/dispatched))))
  (is (nil? (h/project-row (pair-ev 2))))
  (is (nil? (h/project-row (untagged-ev 3)))))

(deftest project-rows-filters-and-orders
  (let [stream [(mcp-ev 1 :event :event/dispatched {:time 100})
                (app-ev 2 :event :event/dispatched)
                (mcp-ev 3 :fx :fx/handled {:time 200})
                (untagged-ev 4)
                (mcp-ev 5 :error :rf.error/handler-exception {:time 300})]
        rows   (h/project-rows stream)]
    (is (= 3 (count rows)))
    (is (= [1 3 5] (mapv :id rows)))
    (is (every? #(= :causa-mcp %) (mapv :origin rows)))))

;; ---- (3) filter axes ---------------------------------------------------

(deftest op-type-filter-passes-when-empty
  (let [row {:op-type :event}]
    (is (true? (h/passes-op-type? #{} row)))
    (is (true? (h/passes-op-type? nil row)))))

(deftest op-type-filter-restricts-when-set
  (is (true?  (h/passes-op-type? #{:event} {:op-type :event})))
  (is (false? (h/passes-op-type? #{:event} {:op-type :fx})))
  (is (true?  (h/passes-op-type? #{:event :fx} {:op-type :fx}))))

(deftest since-filter-when-disabled-passes-all
  (let [row {:time 100}]
    (is (true? (h/passes-since? 1000 nil row)))))

(deftest since-filter-restricts-by-time
  (is (true?  (h/passes-since? 1000 500 {:time 600})))
  (is (true?  (h/passes-since? 1000 500 {:time 500})))
  (is (true?  (h/passes-since? 1000 500 {:time 1000})))
  (is (false? (h/passes-since? 1000 500 {:time 400})))
  (is (false? (h/passes-since? 1000 500 {:time 0}))))

(deftest apply-filters-composes-axes
  (let [now  1000
        rows [{:id 1 :op-type :event :time 950}
              {:id 2 :op-type :fx    :time 700}
              {:id 3 :op-type :error :time 200}
              {:id 4 :op-type :event :time 100}]]
    (testing "no filters → everything passes"
      (is (= [1 2 3 4]
             (mapv :id (h/apply-filters rows {} now)))))
    (testing "op-type-only filter restricts"
      (is (= [1 4]
             (mapv :id (h/apply-filters rows
                                        {:op-types #{:event}}
                                        now)))))
    (testing "since-ms filter restricts (now-500ms cutoff)"
      (is (= [1 2]
             (mapv :id (h/apply-filters rows
                                        {:since-ms 500}
                                        now)))))
    (testing "two axes combine intersectively"
      (is (= [1]
             (mapv :id (h/apply-filters
                         rows
                         {:op-types #{:event}
                          :since-ms 500}
                         now)))))))

;; ---- (4) project-feed (top-level composite) ----------------------------

(deftest project-feed-empty-stream
  (let [feed (h/project-feed [] {} 1000)]
    (is (= 0 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (false? (:agent-attached? feed)))
    (is (= :no-activity (:empty-kind feed)))))

(deftest project-feed-stream-with-only-non-mcp-events
  (testing "a stream of pure app-origin events is :no-activity from
            this panel's perspective"
    (let [stream [(app-ev 1 :event :event/dispatched)
                  (untagged-ev 2)
                  (pair-ev 3)]
          feed   (h/project-feed stream {} 1000)]
      (is (= 0 (:total feed)))
      (is (false? (:agent-attached? feed)))
      (is (= :no-activity (:empty-kind feed))))))

(deftest project-feed-no-matches
  (let [stream [(mcp-ev 1 :event :event/dispatched {:time 100})]
        feed   (h/project-feed stream {:op-types #{:fx}} 1000)]
    (is (= 1 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (true? (:agent-attached? feed)))
    (is (= :no-matches (:empty-kind feed)))))

(deftest project-feed-newest-first
  (let [stream [(mcp-ev 1 :event :event/dispatched {:time 100})
                (mcp-ev 2 :fx    :fx/handled       {:time 200})
                (mcp-ev 3 :error :rf.error/x       {:time 300})]
        feed   (h/project-feed stream {} 1000)]
    (testing ":rows is in newest-first order for display"
      (is (= [3 2 1] (mapv :id (:rows feed)))))
    (testing ":empty-kind is nil when there are matches"
      (is (nil? (:empty-kind feed))))
    (testing ":agent-attached? reflects presence of any mcp event"
      (is (true? (:agent-attached? feed))))))

(deftest project-feed-op-type-counts
  (let [stream [(mcp-ev 1 :event :event/dispatched)
                (mcp-ev 2 :event :event/dispatched)
                (mcp-ev 3 :fx    :fx/handled)
                (mcp-ev 4 :error :rf.error/x)]
        feed   (h/project-feed stream {} 1000)]
    (is (= {:event 2 :fx 1 :error 1}
           (:op-type-counts feed)))))

(deftest project-feed-distinct-op-types
  (let [stream [(mcp-ev 1 :event :event/dispatched)
                (mcp-ev 2 :fx    :fx/handled)
                (mcp-ev 3 :event :event/dispatched)
                (mcp-ev 4 :error :rf.error/x)]
        feed   (h/project-feed stream {} 1000)]
    (testing "distinct-op-types is in first-seen order"
      (is (= [:event :fx :error] (:distinct-op-types feed))))))

(deftest project-feed-ignores-non-mcp-events-completely
  (testing "the feed's total + rendered count only mcp events; app /
            pair / untagged events are invisible to this panel"
    (let [stream [(mcp-ev 1 :event :event/dispatched)
                  (app-ev 2 :event :event/dispatched)
                  (mcp-ev 3 :fx    :fx/handled)
                  (pair-ev 4)
                  (mcp-ev 5 :error :rf.error/x)
                  (untagged-ev 6)]
          feed   (h/project-feed stream {} 1000)]
      (is (= 3 (:total feed)))
      (is (= 3 (:rendered feed)))
      (is (= [5 3 1] (mapv :id (:rows feed)))))))

;; ---- (5) agent-attached? ------------------------------------------------

(deftest agent-attached-true-when-any-mcp-event-present
  (is (true? (h/agent-attached?
               [(app-ev 1 :event :event/dispatched)
                (mcp-ev 2 :event :event/dispatched)]))))

(deftest agent-attached-false-when-no-mcp-events
  (is (false? (h/agent-attached?
                [(app-ev 1 :event :event/dispatched)
                 (pair-ev 2)
                 (untagged-ev 3)])))
  (is (false? (h/agent-attached? []))))

;; ---- (6) short-description --------------------------------------------

(deftest short-description-priority
  (testing "event vector is preferred"
    (is (re-find #"counter/inc"
                 (h/short-description
                   (mcp-ev 1 :event :event/dispatched
                           {:tags {:event [:counter/inc]
                                   :tool :dispatch}})))))
  (testing "tool is used when no event"
    (is (re-find #":dispatch"
                 (h/short-description
                   (mcp-ev 1 :event :event/dispatched
                           {:tags {:tool :dispatch}})))))
  (testing "reason fills in when nothing higher-priority"
    (is (re-find #"thing happened"
                 (h/short-description
                   (mcp-ev 1 :error :rf.error/handler-exception
                           {:tags {:reason "thing happened"}})))))
  (testing "fallback is the operation keyword alone"
    (is (= ":event/dispatched"
           (h/short-description
             (mcp-ev 1 :event :event/dispatched {:tags {}}))))))

;; ---- (7) find-row ------------------------------------------------------

(deftest find-row-by-id
  (let [rows [{:id 1 :op-type :event}
              {:id 2 :op-type :fx}
              {:id 3 :op-type :error}]]
    (is (= {:id 2 :op-type :fx} (h/find-row rows 2)))
    (is (nil? (h/find-row rows 99)))))

;; ---- (8) format-time --------------------------------------------------

(deftest format-time-renders-hms-with-millis
  (testing "format-time returns nil on non-numeric input"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not a number"))))
  (testing "format-time returns a HH:MM:SS.mmm-shaped string on numeric input"
    (let [s (h/format-time 12345)]
      (is (string? s))
      (is (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}$" s)))))

;; ---- (9) source-coord -------------------------------------------------

(deftest source-coord-projection
  (testing "source-coord pulls file:line from :rf.trace/trigger-handler"
    (is (= "src/foo.cljs:42"
           (h/source-coord
             {:id 1 :op-type :event :operation :event/dispatched
              :tags {:origin :causa-mcp}
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"
                                                        :line 42}}}))))
  (testing "missing trigger-handler returns nil"
    (is (nil? (h/source-coord (mcp-ev 1 :event :event/dispatched)))))
  (testing "missing :line returns just the file"
    (is (= "src/foo.cljs"
           (h/source-coord
             {:id 1 :op-type :event :operation :event/dispatched
              :tags {:origin :causa-mcp}
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"}}})))))
