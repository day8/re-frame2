(ns day8.re-frame2-xray.panels.issues-ribbon-helpers-cljs-test
  "Pure-data tests for Xray's Issues panel helpers.

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `schema_violation_timeline_helpers_cljs_
  test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **issue-event?** — classifies trace events by severity: the
       `:error` and `:warning` op-types are issues; every other op-type
       (`:info` among them) is not.
    2. **category-prefix / category-label** — project `:operation`'s
       keyword namespace + unqualified name (the Figma row cell).
    3. **project-issue** — projects raw trace events onto row cells.
    4. **project-feed** — top-level composite over a focused epoch
       record; empty-kind classifier (:no-focus, :epoch-evicted,
       :no-issues branches per spec/021 §10.7). No filtering
       (pure rows per the Figma design).
    5. **the sub call-site composition** — `resolve-focus-status` →
       `find-epoch-record` → `project-feed`. The resolver the two
       aliases re-export is pinned in `shared/focus_resolver_cljs_test`."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]))

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

(defn- info-ev
  "An `:op-type :info` row — ACTIVITY, not an issue."
  ([id operation]
   (info-ev id operation {}))
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
   :op-type   :rf.event
   :operation :rf.event/dispatched
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

;; ---- (1) issue-event? -------------------------------------------------

(deftest issue-event?-classification
  (testing "every issue op-type is an issue"
    (is (true? (h/issue-event? (error-ev    1 :rf.error/handler-exception))))
    (is (true? (h/issue-event? (warning-ev  2 :rf.warning/recoverable)))))
  (testing "non-issue op-types are NOT issues"
    (is (false? (h/issue-event? (info-ev 3 :rf.info/note))))
    (is (false? (h/issue-event? (non-issue-ev 1))))
    (is (false? (h/issue-event? {:id 1 :op-type :rf.fx})))
    (is (false? (h/issue-event? {:id 1 :op-type :rf.frame})))
    (is (false? (h/issue-event? {:id 1 :op-type :rf.sub})))))

;; ---- (2) category-prefix ----------------------------------------------

(deftest category-prefix-projects-keyword-namespace
  (testing "category-prefix is the operation's keyword namespace"
    (is (= "rf.error"   (h/category-prefix (error-ev 1 :rf.error/handler-exception))))
    (is (= "rf.warning" (h/category-prefix (warning-ev 2 :rf.warning/recoverable))))
    (is (= "rf.ssr"     (h/category-prefix (warning-ev 3 :rf.ssr/hydration-mismatch))))
    (is (= "rf.info"    (h/category-prefix (info-ev 4 :rf.info/note))))
    (is (= "rf.route.nav-token"
           (h/category-prefix (error-ev 5 :rf.route.nav-token/rejected)))))
  (testing "category-prefix returns nil when operation has no namespace"
    (is (nil? (h/category-prefix {:operation "literal-string"})))
    (is (nil? (h/category-prefix {:operation nil})))))

;; ---- (3) project-issue ------------------------------------------------

(deftest project-issue-builds-row-shape
  (testing "a projected issue carries every cell the row needs"
    (let [row (h/project-issue (error-ev 7 :rf.error/handler-exception
                                         {:time 9999
                                          :tags {:reason "kaboom"}}))]
      (is (= 7                          (:id row)))
      (is (= 9999                       (:time row)))
      (is (= :error                     (:severity row)))
      (is (= :error                     (:op-type row)))
      (is (= :rf.error/handler-exception    (:operation row)))
      (is (= "handler-exception"        (:category row))
          "the muted category cell is the unqualified op name")
      (is (= "rf.error"                 (:category-prefix row)))
      (is (re-find #"kaboom"            (:description row)))
      (is (some?                        (:raw row))))))

;; ---- (4) category-label / category-prefix ----------------------------
;;
;; There are no chip-filter helpers and no `distinct-prefixes`
;; enumeration: the Issues panel renders pure rows with no filtering, per
;; the Figma design (spec/021 §8.2). The Figma row's muted `category` cell
;; is the unqualified op name; `category-prefix` carries the domain
;; provenance (used for the row's title affordance).

(deftest category-label-is-unqualified-op-name
  (testing "the muted category cell is the operation's unqualified name"
    (is (= "handler-exception"
           (h/category-label (error-ev 1 :rf.error/handler-exception))))
    (is (= "hydration-mismatch"
           (h/category-label (warning-ev 2 :rf.ssr/hydration-mismatch))))
    (is (= "note"
           (h/category-label (info-ev 3 :rf.info/note)))))
  (testing "falls back to the literal string for a non-keyword op"
    (is (= "literal-string"
           (h/category-label {:operation "literal-string"}))))
  (testing "nil operation yields nil"
    (is (nil? (h/category-label {:operation nil})))))

;; ---- (5) project-feed top-level composite ---------------------------

(deftest project-feed-names-the-empty-state-for-each-focus-status
  ;; `:empty-kind` is the discriminator the view branches on (spec/021
  ;; §10.7); an evicted epoch renders the canonical placeholder.
  (are [record status expected] (= expected (h/project-feed record status))
    nil                  :no-focus      {:issues [] :total 0 :rendered 0 :epoch-id nil :empty-kind :no-focus}
    nil                  :epoch-evicted {:issues [] :total 0 :rendered 0 :epoch-id nil :empty-kind :epoch-evicted}
    (epoch-record 42 []) :focused       {:issues [] :total 0 :rendered 0 :epoch-id 42 :empty-kind :no-issues}))

(deftest project-feed-renders-issues-from-trace-events
  (testing "the focused epoch's :trace-events feed the projection;
            non-issue traces — the `:info` activity row among them — are
            silently dropped"
    (let [record (epoch-record 42
                   [(error-ev   1 :rf.error/handler-exception)
                    (non-issue-ev 2)
                    (warning-ev 3 :rf.warning/recoverable)
                    (non-issue-ev 4)
                    (info-ev 5 :rf.info/note)])
          feed   (h/project-feed record :focused)]
      (is (= 2 (:total feed)))
      (is (= 2 (:rendered feed)))
      (is (= #{1 3} (set (map :id (:issues feed)))))
      (is (nil? (:empty-kind feed)))
      (is (= 42 (:epoch-id feed))))))

(deftest project-feed-head-fallback-end-to-end
  (testing "exercise the panel's sub call-site shape: when
            :rf.xray/focus carries no :epoch-id but :rf.xray/epoch-
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
          feed             (h/project-feed record focus-status)]
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
          feed   (h/project-feed record :focused)]
      (is (= [3 2 1] (mapv :id (:issues feed)))))))

;; ---- (7) an :info lifecycle row is activity, never an issue
;;
;; The runtime emits `:rf.http/issued` at `:info` inside the issuing fx
;; handler on EVERY managed request, so it lands in the issuing bundle —
;; and if `:info` classed as an `:advisory` issue, every healthy
;; HTTP-issuing epoch would put one issue on the ribbon (tripping the
;; auto-open-on-error watcher's empty→non-empty edge) and wash its L2
;; row pink beside a green status. The row below is the producer's shape.

(deftest info-lifecycle-row-is-not-an-issue
  (testing "a healthy managed-HTTP epoch — the producer's `:rf.http/issued`
            `:info` row beside its `:rf.fx/handled` — projects NO issue"
    (let [issued (assoc (teb/http-issued-ev :app/load "/api/load") :id 32)
          record (epoch-record 7 [issued
                                  (assoc (teb/fx-handled-ev :rf.http/managed {} 1) :id 33)])
          feed   (h/project-feed record :focused)]
      (is (false? (h/issue-event? issued)))
      (is (= [] (h/project-issues [issued])))
      (is (= 0 (:total feed)))
      (is (= :no-issues (:empty-kind feed))
          "the ribbon stays EMPTY, so auto-open-on-error has no edge to fire on")))
  (testing "CONTROLS — the two issue tiers do project, so the drop above is
            about :info and not about the projection having gone blank"
    (let [warn  (teb/ev :warning :rf.fx/skipped-on-platform {:rf.fx/id :app/clip})
          err   (teb/handler-exception-ev :app/load "boom")
          feed  (h/project-feed (epoch-record 7 [(assoc (teb/http-issued-ev :app/load "/api/load") :id 1)
                                                 (assoc warn :id 2)
                                                 (assoc err :id 3)])
                                :focused)]
      (is (= [:error :warning] (mapv :severity (:issues feed))))
      (is (= 2 (:total feed))))))

;; ---- (8) short-description ---------------------------------------

(deftest short-description-uses-priority-order
  (testing "reason is preferred when present"
    (is (re-find #"specific because"
                 (h/short-description
                   (error-ev 1 :rf.error/no-such-handler
                             {:tags {:reason "specific because" :rf.event/v [:x]}})))))
  (testing "exception-message is used when no reason"
    (is (re-find #"boom"
                 (h/short-description
                   (error-ev 1 :rf.error/handler-exception
                             {:tags {:exception-message "boom"}})))))
  (testing "event vector is used when neither reason nor exception is set"
    (is (re-find #"counter/inc"
                 (h/short-description
                   (error-ev 1 :rf.error/no-such-handler
                             {:tags {:rf.event/v [:counter/inc]}})))))
  (testing "fallback is the operation keyword alone"
    (is (= ":rf.error/handler-exception"
           (h/short-description
             (error-ev 1 :rf.error/handler-exception {:tags {}}))))))

(deftest short-description-surfaces-no-such-sub-under-spec-009-shape
  ;; The `:rf.error/no-such-sub` emit tags are spec/009's
  ;; `{:rf.sub/id _ :unresolved-input _ :resolved-inputs _ :frame _}`
  ;; (the `re-frame.subs` emit site); there is no `:rf.sub/query-v` slot.
  ;; The ribbon's description reader reads `:unresolved-input` so the row
  ;; surfaces WHICH sub failed to resolve, rather than the bare op keyword.
  (testing "the failing sub's query-vector is lifted from :unresolved-input"
    (let [ev   (error-ev 1 :rf.error/no-such-sub
                         {:tags {:rf.sub/id        :cart/total
                                 :unresolved-input [:cart/total]
                                 :resolved-inputs  []
                                 :frame            :rf/default}})
          desc (h/short-description ev)]
      (is (re-find #":cart/total" desc)
          "the unresolved sub query-vector reads into the description")
      (is (re-find #":rf.error/no-such-sub" desc)
          "the operation keyword leads the line")))
  (testing "a :rf.sub/query-v slot is NOT read — nothing emits it, so a
            description carrying ONLY that slot falls through to the
            bare-op fallback"
    (is (= ":rf.error/no-such-sub"
           (h/short-description
             (error-ev 1 :rf.error/no-such-sub
                       {:tags {:rf.sub/query-v [:cart/total]}})))))
  (testing "project-issue round-trips a no-such-sub error into a row whose
            description names the unresolved sub"
    (let [row (h/project-issue
                (error-ev 7 :rf.error/no-such-sub
                          {:tags {:rf.sub/id        :cart/items
                                  :unresolved-input [:cart/items]
                                  :resolved-inputs  []}}))]
      (is (= :error (:severity row)))
      (is (= "no-such-sub" (:category row)))
      (is (re-find #":cart/items" (:description row))))))

;; ---- (9) source-coord ------------------------------------------

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

;; ---- (10) the effect-map refusal reads on the GENERIC row ---------------
;;
;; The refusal ships NO bespoke Xray UI, and that is the claim under test: an
;; operator diagnosing a refused event reads the ordinary issue row and gets
;; the category, the offending KEY, and the source of the handler that wrote
;; it. If this row ever went blank in the middle cell, the operator would see
;; that an event was refused without being told which key did it — and the
;; whole point of the refusal is to name the mistake.

(deftest effect-map-shape-reads-on-the-generic-issue-row
  (testing "a refused effect-map projects category / offending key / source
            onto the generic row — no bespoke panel needed"
    (let [row (h/project-issue
                (assoc
                  (error-ev 11 :rf.error/effect-map-shape
                            {:recovery :fix-effect
                             :tags {:failing-id        :boot/arm
                                    :rf.trace/event-id :boot/arm
                                    :rf.event/v        [:boot/arm]
                                    :offending-key     :dispatch-later
                                    :value             {:ms 5000 :event [:boot/fire]}
                                    :reason            (str "Effect-map for `:boot/arm` returned top-level key "
                                                            "`:dispatch-later`; the effect-map is closed.")}})
                  :rf.trace/trigger-handler {:source-coord {:file "src/app/boot.cljs"
                                                            :line 88}}))]
      (is (= :error (:severity row))
          "an effect-map refusal is an ERROR row")
      (is (= "effect-map-shape" (:category row))
          "the category cell names the category")
      (is (= :fix-effect (:recovery row))
          "the recovery cell says the event aborts until the effect is fixed")
      (is (re-find #":dispatch-later" (:description row))
          "the description NAMES THE OFFENDING KEY — the one fact the operator
           needs and the only discriminator between refusals")
      (is (re-find #":boot/arm" (:description row))
          "and it names the handler that wrote it")
      (is (= "src/app/boot.cljs:88" (:source-coord row))
          "the source cell points at the handler's own line"))))
