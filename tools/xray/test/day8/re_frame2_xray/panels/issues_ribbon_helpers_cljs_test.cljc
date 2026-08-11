(ns day8.re-frame2-xray.panels.issues-ribbon-helpers-cljs-test
  "Pure-data tests for Xray's Issues panel helpers (rf2-jio48 rebuild).

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
    3. **category-prefix / category-label** — project `:operation`'s
       keyword namespace + unqualified name (the Figma row cell).
    4. **project-issue** — projects raw trace events onto row cells.
    5. **severity-badge-label** — uppercase ERROR/WARNING/ADVISORY badge.
    6. **project-feed** — top-level composite over a focused epoch
       record; empty-kind classifier (:no-focus, :epoch-evicted,
       :no-issues branches per spec/021 §10.7). No filtering
       (rf2-ad7zx.9 — pure rows per the Figma design).
    7. **resolve-focus-status / find-epoch-record** — focus + history
       resolver.
    8. **epoch-has-issues?** — film-strip filter-fn callback.
    9. **format-time** — renders a stable HH:MM:SS.mmm string."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as h]
            [day8.re-frame2-xray.theme.tokens :as tokens]))

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
    (is (nil? (h/op-type->severity :rf.sub/run)))
    (is (nil? (h/op-type->severity :rf.view/render)))
    (is (nil? (h/op-type->severity nil)))))

(deftest severity-colour-mapping-honours-tokens
  (testing "each severity gets the shell.cljs token-equivalent colour
            (resolved through `theme/tokens` so the rf2-0fr6v
            `:text-tertiary` contrast bump round-trips automatically).
            Drives both the row's 3px left-border and the text badge
            per the Figma design (rf2-ad7zx.9)."
    (is (= (:error    tokens/tokens) (h/severity-colour :error)))
    (is (= (:warning  tokens/tokens) (h/severity-colour :warning)))
    (is (= (:advisory tokens/tokens) (h/severity-colour :advisory)))
    (is (= (:text-tertiary tokens/tokens) (h/severity-colour :unknown)))))

(deftest severity-badge-label-uppercase
  (testing "rf2-ad7zx.9 — the per-row TEXT badge is uppercase
            ERROR / WARNING / ADVISORY per the Figma design
            (design-reference/xray_devtools_reference.cljs, the issues-panel component)"
    (is (= "ERROR"    (h/severity-badge-label :error)))
    (is (= "WARNING"  (h/severity-badge-label :warning)))
    (is (= "ADVISORY" (h/severity-badge-label :advisory)))
    (is (= "UNKNOWN"  (h/severity-badge-label :unknown)))))

;; ---- (2) issue-event? -------------------------------------------------

(deftest issue-event?-classification
  (testing "every issue op-type is an issue"
    (is (true? (h/issue-event? (error-ev    1 :rf.error/handler-exception))))
    (is (true? (h/issue-event? (warning-ev  2 :rf.warning/recoverable))))
    (is (true? (h/issue-event? (advisory-ev 3 :rf.info/note)))))
  (testing "non-issue op-types are NOT issues"
    (is (false? (h/issue-event? (non-issue-ev 1))))
    (is (false? (h/issue-event? {:id 1 :op-type :rf.fx})))
    (is (false? (h/issue-event? {:id 1 :op-type :rf.frame})))
    (is (false? (h/issue-event? {:id 1 :op-type :rf.sub})))))

;; ---- (3) category-prefix ----------------------------------------------

(deftest category-prefix-projects-keyword-namespace
  (testing "category-prefix is the operation's keyword namespace"
    (is (= "rf.error"   (h/category-prefix (error-ev 1 :rf.error/handler-exception))))
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
    (let [row (h/project-issue (error-ev 7 :rf.error/handler-exception
                                         {:time 9999
                                          :tags {:reason "kaboom"}}))]
      (is (= 7                          (:id row)))
      (is (= 9999                       (:time row)))
      (is (= :error                     (:severity row)))
      (is (= :error                     (:op-type row)))
      (is (= :rf.error/handler-exception    (:operation row)))
      (is (= "handler-exception"        (:category row))
          "rf2-ad7zx.9 — the muted category cell is the unqualified op name")
      (is (= "rf.error"                 (:category-prefix row)))
      (is (re-find #"kaboom"            (:description row)))
      (is (some?                        (:raw row))))))

;; ---- (5) category-label / category-prefix ----------------------------
;;
;; rf2-ad7zx.9 — the chip-filter helpers (`passes-severity?`,
;; `passes-category-prefix?`, `apply-filters`) and the
;; `distinct-prefixes` enumeration were removed with the Issues panel's
;; filter-chrome reconcile to the Figma design (pure rows, no filtering
;; — spec/021 §8.2). The Figma row's muted `category` cell is the
;; unqualified op name; `category-prefix` still carries the domain
;; provenance (used for the row's title affordance).

(deftest category-label-is-unqualified-op-name
  (testing "the muted category cell is the operation's unqualified name"
    (is (= "handler-exception"
           (h/category-label (error-ev 1 :rf.error/handler-exception))))
    (is (= "hydration-mismatch"
           (h/category-label (warning-ev 2 :rf.ssr/hydration-mismatch))))
    (is (= "note"
           (h/category-label (advisory-ev 3 :rf.info/note)))))
  (testing "falls back to the literal string for a non-keyword op"
    (is (= "literal-string"
           (h/category-label {:operation "literal-string"}))))
  (testing "nil operation yields nil"
    (is (nil? (h/category-label {:operation nil})))))

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
  (let [hist [(epoch-record 5 [(error-ev 100 :rf.error/handler-exception)])
              (epoch-record 6 [(warning-ev 101 :rf.warning/recoverable)])]]
    (is (= 5 (:epoch-id (h/find-epoch-record 5 hist))))
    (is (= 6 (:epoch-id (h/find-epoch-record 6 hist))))
    (is (nil? (h/find-epoch-record 99 hist)))))

(deftest find-epoch-record-head-fallback
  (testing "rf2-h0120 — focus nil + history non-empty returns the HEAD
            (most-recent) record. epoch-history is oldest-first per
            re-frame.epoch/epoch-history, so the head is the last
            element."
    (let [hist [(epoch-record 5 [(error-ev 100 :rf.error/handler-exception)])
                (epoch-record 6 [(warning-ev 101 :rf.warning/recoverable)])
                (epoch-record 7 [])]]
      (is (= 7 (:epoch-id (h/find-epoch-record nil hist))))))
  (testing "single-record history's head is that single record"
    (let [hist [(epoch-record 42 [(error-ev 1 :rf.error/handler-exception)])]]
      (is (= 42 (:epoch-id (h/find-epoch-record nil hist))))))
  (testing "focus nil AND history empty/nil returns nil"
    (is (nil? (h/find-epoch-record nil [])))
    (is (nil? (h/find-epoch-record nil nil)))))

;; ---- (8) project-feed top-level composite ---------------------------

(deftest project-feed-no-focus-renders-empty
  (let [feed (h/project-feed nil :no-focus)]
    (is (= []  (:issues feed)))
    (is (= 0   (:total feed)))
    (is (= 0   (:rendered feed)))
    (is (= :no-focus (:empty-kind feed)))
    (is (nil? (:epoch-id feed)))))

(deftest project-feed-evicted-renders-canonical-placeholder
  (testing "spec/021 §10.7 — :epoch-evicted is the discriminator the
            view branches on to render the canonical placeholder."
    (let [feed (h/project-feed nil :epoch-evicted)]
      (is (= :epoch-evicted (:empty-kind feed)))
      (is (= 0 (:total feed))))))

(deftest project-feed-no-issues-empty-trace-events
  (testing "focused epoch with empty :trace-events → :no-issues"
    (let [record (epoch-record 42 [])
          feed   (h/project-feed record :focused)]
      (is (= [] (:issues feed)))
      (is (= 0  (:total feed)))
      (is (= :no-issues (:empty-kind feed)))
      (is (= 42 (:epoch-id feed))))))

(deftest project-feed-no-issues-only-non-issue-traces
  (testing "trace-events with no issue ops → :no-issues"
    (let [record (epoch-record 42 [(non-issue-ev 1)
                                   (non-issue-ev 2)])
          feed   (h/project-feed record :focused)]
      (is (= [] (:issues feed)))
      (is (= :no-issues (:empty-kind feed))))))

(deftest project-feed-renders-issues-from-trace-events
  (testing "the focused epoch's :trace-events feed the projection;
            non-issue traces are silently dropped"
    (let [record (epoch-record 42
                   [(error-ev   1 :rf.error/handler-exception)
                    (non-issue-ev 2)
                    (warning-ev 3 :rf.warning/recoverable)
                    (non-issue-ev 4)
                    (advisory-ev 5 :rf.info/note)])
          feed   (h/project-feed record :focused)]
      (is (= 3 (:total feed)))
      (is (= 3 (:rendered feed)))
      (is (= #{1 3 5} (set (map :id (:issues feed)))))
      (is (nil? (:empty-kind feed)))
      (is (= 42 (:epoch-id feed))))))

(deftest project-feed-head-fallback-end-to-end
  (testing "rf2-h0120 — exercise the panel's sub call-site shape: when
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

;; ---- (6b) feed-under-cascade-scope: SSR hydration-mismatch (rf2-djuf3) --
;;
;; Pins the regression the `hydration mismatch debugger` feature-gate
;; scenario surfaces: the Issues panel is the focused-epoch (cascade)
;; lens, so a `:rf.ssr/hydration-mismatch` error that LANDS in a cascade's
;; `:trace-events` MUST project into the feed under that cascade's scope.
;;
;; (When `verify-hydration!` emits the mismatch OUTSIDE any dispatch the
;; framework's epoch capture drops the orphan — rf2-avvwm — so it never
;; reaches an epoch record. That out-of-cascade case is exercised by the
;; orphan-drop epoch-capture tests, not here; this test pins the in-
;; cascade projection that the panel's contract guarantees.)

(defn- hydration-mismatch-ev
  "A Spec 011-shaped `:rf.ssr/hydration-mismatch` error trace event as
  it lands in a cascade's `:trace-events` (op-type :error, recovery
  hoisted, payload in :tags)."
  [id]
  (error-ev id :rf.ssr/hydration-mismatch
            {:time     500
             :recovery :warned-and-replaced
             :tags     {:server-hash "deadbeef"
                        :client-hash "91de1ba6"
                        :failing-id  :rf/hydrate
                        :reason      "Hydration mismatch: server hash 'deadbeef' != client hash '91de1ba6'."}}))

(deftest project-feed-hydration-mismatch-surfaces-under-cascade-scope
  (testing "an in-cascade :rf.ssr/hydration-mismatch error projects into
            the focused epoch's feed under cascade scope"
    (let [record (epoch-record 2 [(non-issue-ev 1)
                                  (hydration-mismatch-ev 9)
                                  (non-issue-ev 2)])
          feed   (h/project-feed record :focused)]
      (is (nil? (:empty-kind feed)) "feed renders, not an empty state")
      (is (= 1 (:total feed)))
      (is (= 1 (:rendered feed)))
      (is (= [9] (mapv :id (:issues feed))))
      (let [row (first (:issues feed))]
        (is (= :error               (:severity row)))
        (is (= "hydration-mismatch" (:category row)))
        (is (= "rf.ssr"             (:category-prefix row)))
        (is (= :rf.ssr/hydration-mismatch (:operation row)))
        (is (re-find #"Hydration mismatch" (:description row))))
      (is (= 2 (:epoch-id feed)) "feed epoch-id reflects the focused cascade"))))

(deftest project-feed-hydration-mismatch-head-fallback-sub-call-site
  (testing "rf2-djuf3 — the panel's sub call-site shape: nil focus +
            non-empty history head-falls-back (rf2-h0120) onto the
            cascade carrying the hydration-mismatch, and the feed
            renders that issue under cascade scope"
    (let [hist           [(epoch-record 1 [])
                          (epoch-record 2 [(hydration-mismatch-ev 9)])]
          focus-epoch-id nil
          focus-status   (h/resolve-focus-status focus-epoch-id hist)
          record         (h/find-epoch-record   focus-epoch-id hist)
          feed           (h/project-feed record focus-status)]
      (is (= :focused focus-status))
      (is (= 2 (:epoch-id record)))
      (is (nil? (:empty-kind feed)))
      (is (= [9] (mapv :id (:issues feed)))))))

(deftest project-feed-always-renders-feed-or-empty-state
  (testing "rf2-djuf3 invariant (rf2-ad7zx.9 — :no-matches dropped with
            the filter chrome) — under EVERY focus-status the panel sub
            yields a renderable shape: either the feed (empty-kind nil)
            or exactly one of the three empty-state discriminators. The
            feature-gate scenario waits on this union; an unhandled
            empty-kind would silently render nothing."
    (let [renderable? #{nil :no-focus :epoch-evicted :no-issues}]
      (testing ":no-focus → :no-focus empty-state"
        (is (= :no-focus (:empty-kind (h/project-feed nil :no-focus)))))
      (testing ":epoch-evicted → :epoch-evicted empty-state"
        (is (= :epoch-evicted
               (:empty-kind (h/project-feed nil :epoch-evicted)))))
      (testing ":focused + no issues → :no-issues empty-state"
        (is (= :no-issues
               (:empty-kind (h/project-feed (epoch-record 1 []) :focused)))))
      (testing ":focused + visible issue → feed (empty-kind nil)"
        (is (nil? (:empty-kind (h/project-feed
                                 (epoch-record 1 [(hydration-mismatch-ev 9)])
                                 :focused)))))
      (testing "every branch's empty-kind is in the view's renderable set"
        (doseq [[record status]
                [[nil :no-focus]
                 [nil :epoch-evicted]
                 [(epoch-record 1 []) :focused]
                 [(epoch-record 1 [(hydration-mismatch-ev 9)]) :focused]]]
          (is (contains? renderable?
                         (:empty-kind (h/project-feed record status)))))))))

(deftest project-feed-newest-first
  (testing "the feed reverses the trace-events stream — newest first"
    (let [record (epoch-record 1 [(error-ev   1 :rf.error/a {:time 100})
                                  (warning-ev 2 :rf.warning/b {:time 200})
                                  (error-ev   3 :rf.error/c {:time 300})])
          feed   (h/project-feed record :focused)]
      (is (= [3 2 1] (mapv :id (:issues feed)))))))

(deftest project-feed-no-filtering-renders-every-issue
  (testing "rf2-ad7zx.9 — the panel renders pure rows with NO filtering;
            every issue in the focused epoch surfaces, :rendered = :total"
    (let [record (epoch-record 1 [(error-ev   1 :rf.error/handler-exception)
                                  (warning-ev 2 :rf.warning/missing-doc)
                                  (advisory-ev 3 :rf.info/note)
                                  (error-ev   4 :rf.ssr/hydration-mismatch)])
          feed   (h/project-feed record :focused)]
      (is (= 4 (:total feed)))
      (is (= 4 (:rendered feed)))
      (is (= #{1 2 3 4} (set (map :id (:issues feed))))))))

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
               (epoch-record 1 [(error-ev 1 :rf.error/handler-exception)])))))

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
  ;; rf2-qn9ss — agpv2.3 (#3107) re-shaped the `:rf.error/no-such-sub`
  ;; emit tags from the legacy `{:rf.sub/query-v _}` to spec/009's
  ;; `{:rf.sub/id _ :unresolved-input _ :resolved-inputs _ :frame _}`
  ;; (verified against the `re-frame.subs` emit site). The ribbon's
  ;; description reader now reads `:unresolved-input` so the row surfaces
  ;; WHICH sub failed to resolve, rather than the bare op keyword.
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
          "the operation keyword still leads the line")))
  (testing "the legacy :rf.sub/query-v slot is NOT read — it is no longer
            emitted post-agpv2.3, so a description carrying ONLY the legacy
            slot falls through to the bare-op fallback (regression guard)"
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

;; ---- (14) the effect-map refusal reads on the GENERIC row (rf2-04tx) -----
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
