(ns day8.re-frame2-causa.panels.trace-helpers-cljs-test
  "Pure-data tests for Causa's Trace panel helpers (Phase 5, rf2-argrj;
  epoch-scoped rework rf2-td380 + rf2-gkczt).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `issues_ribbon_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **Per-row projection** — `project-row` populates every axis
       slot from raw trace events (Spec 009 §Filter vocabulary).
    2. **Epoch-scoped feed** — `project-feed-from-epoch` projects the
       focused epoch record's `:trace-events` into the view shape and
       classifies the empty state across the focus-resolver statuses
       (rf2-td380)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.trace-helpers :as h]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

;; ---- fixture builders ---------------------------------------------------

(defn- ev
  "Build a Spec 009-shaped trace event for the tests. The axis slots
  pull from both top-level slots and `:tags` — the fixture surfaces
  every slot the projection documents."
  [{:keys [id op-type operation time source origin frame event-id
           handler-id dispatch-id tags coord]
    :or {time 1000 tags {}}}]
  (cond-> {:id        id
           :op-type   op-type
           :operation operation
           :time      time
           :tags      (cond-> tags
                       origin       (assoc :rf.event/origin origin)
                       frame        (assoc :frame frame)
                       event-id     (assoc :rf.trace/event-id event-id)
                       handler-id   (assoc :handler-id handler-id)
                       dispatch-id  (assoc :rf.trace/dispatch-id dispatch-id))}
    source (assoc :source source)
    coord  (assoc :rf.trace/trigger-handler {:source-coord coord})))

;; ---- (1) per-row projection --------------------------------------------

(deftest project-row-populates-every-axis-slot
  (let [e (ev {:id 7 :op-type :rf.event :operation :rf.event/dispatched
               :time 500 :source :ui :origin :app
               :frame :rf/default :event-id :counter/inc
               :handler-id :counter/inc-handler
               :dispatch-id 42
               :tags {:rf.event/v [:counter/inc]}
               :coord {:file "src/foo.cljs" :line 12}})
        row (h/project-row e)]
    (is (= 7 (:id row)))
    (is (= 500 (:time row)))
    (is (= :rf.event (:op-type row)))
    (is (= :rf.event/dispatched (:operation row)))
    (is (= :ui (:source row)))
    (is (= :app (:origin row)))
    (is (= :rf/default (:frame row)))
    (is (= :counter/inc (:event-id row)))
    (is (= :counter/inc-handler (:handler-id row)))
    (is (= 42 (:dispatch-id row)))
    (is (= "src/foo.cljs:12" (:source-coord row)))
    (is (= e (:raw row)))))

(deftest project-row-severity-derived-from-op-type
  (testing ":severity is the Spec 009 synonym axis"
    (is (= :error   (:severity (h/project-row
                                  (ev {:id 1 :op-type :error
                                       :operation :rf.error/x})))))
    (is (= :warning (:severity (h/project-row
                                  (ev {:id 1 :op-type :warning
                                       :operation :rf.warning/x})))))
    (is (= :info    (:severity (h/project-row
                                  (ev {:id 1 :op-type :info
                                       :operation :rf.http/x})))))
    (is (nil? (:severity (h/project-row
                           (ev {:id 1 :op-type :rf.event
                                :operation :rf.event/dispatched})))))))

(deftest project-rows-preserves-chronological-order
  (let [evs  [(ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched :time 100})
              (ev {:id 2 :op-type :rf.fx    :operation :rf.fx/handled    :time 200})
              (ev {:id 3 :op-type :rf.sub :operation :rf.sub/run        :time 300})]
        rows (h/project-rows evs)]
    (is (= [1 2 3] (mapv :id rows))
        "project-rows keeps the events' oldest-first order")))

;; ---- (2) epoch-scoped feed projection — rf2-td380 ----------------------
;;
;; Per spec/018 §6 the Trace tab is a lens on the spine's focused event:
;; it reads the FOCUSED EPOCH's `:trace-events` (the per-frame settling
;; epoch record's raw trace slice — both the synchronous event-side rows
;; AND the async nil-dispatch-id reactive rows of the complete domino
;; trail). `project-feed-from-epoch` takes the looked-up epoch record +
;; the focus-resolver status and produces the view shape.

(defn- domino-trail-epoch
  "A fixture `:rf/epoch-record` whose `:trace-events` carry the COMPLETE
  domino trail for one event — folding both the synchronous event-side
  rows (dispatch-id N) AND the async reactive rows (`:rf.sub/run` /
  `:rf.view/render`, nil dispatch-id) that fire post-cascade. This is the
  exact shape the OLD dispatch-id-scoped feed dropped (it filtered to
  the dispatch-id, losing the nil-dispatch-id reactive tail)."
  []
  {:epoch-id 17
   :trace-events
   [;; ---- synchronous event side (dispatch-id 42) ----
    (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
         :time 100 :dispatch-id 42 :event-id :counter/inc
         :tags {:rf.event/v [:counter/inc]}})
    (ev {:id 2 :op-type :rf.event :operation :rf.event/run-end
         :time 101 :dispatch-id 42})
    (ev {:id 3 :op-type :rf.event :operation :rf.event/db-changed
         :time 102 :dispatch-id 42})
    (ev {:id 4 :op-type :rf.fx :operation :rf.fx/handled
         :time 103 :dispatch-id 42})
    ;; ---- async reactive tail (nil dispatch-id) ----
    (ev {:id 5 :op-type :rf.sub :operation :rf.sub/run
         :time 110 :tags {:rf.sub/id :app/counter}})
    (ev {:id 6 :op-type :rf.sub :operation :rf.sub/run
         :time 111 :tags {:rf.sub/id :app/derived}})
    (ev {:id 7 :op-type :rf.view :operation :rf.view/render
         :time 120})]})

(deftest project-feed-from-epoch-folds-the-complete-domino-trail
  (testing "rf2-td380 headline: scoping by the focused epoch's
            `:trace-events` renders BOTH the synchronous event-side
            rows (dispatch-id 42) AND the async nil-dispatch-id reactive
            tail (subs ran + view rendered) — the complete domino trail.
            The OLD dispatch-id-scoped feed dropped the nil-dispatch-id
            tail (3 reactive rows here), rendering 4 rows instead of 7."
    (let [epoch (domino-trail-epoch)
          feed  (h/project-feed-from-epoch epoch :focused)]
      (is (= 7 (:total feed))
          ":total = every trace event in the focused epoch")
      (is (= 7 (:rendered feed))
          ":rendered = :total (no filtering, rf2-gkczt)")
      (is (= #{1 2 3 4 5 6 7} (set (map :id (:rows feed))))
          "rows are the WHOLE trail — including the nil-dispatch-id
           reactive rows the dispatch-id scope would have dropped")
      (is (some #(and (nil? (:dispatch-id %)) (= :rf.sub (:op-type %)))
                (:rows feed))
          "the async :rf.sub/run rows (nil dispatch-id) are present")
      (is (some #(= :rf.view (:op-type %)) (:rows feed))
          "the async :rf.view/render row is present")
      (is (= 17 (:epoch-id feed)))
      (is (nil? (:empty-kind feed))))))

(deftest project-feed-from-epoch-rows-newest-first
  (testing "rows render newest-first for display parity with the issues
            ribbon (the epoch's :trace-events are oldest-first)"
    (let [epoch (domino-trail-epoch)
          feed  (h/project-feed-from-epoch epoch :focused)]
      (is (= [7 6 5 4 3 2 1] (mapv :id (:rows feed)))))))

(deftest project-feed-from-epoch-no-events
  (testing "a focused epoch with empty :trace-events → :no-events"
    (let [feed (h/project-feed-from-epoch {:epoch-id 3 :trace-events []}
                                          :focused)]
      (is (= 0 (:total feed)))
      (is (= 0 (:rendered feed)))
      (is (= [] (:rows feed)))
      (is (= 3 (:epoch-id feed)))
      (is (= :no-events (:empty-kind feed))))))

(deftest project-feed-from-epoch-no-focus
  (testing "focus-status :no-focus → :no-focus empty-kind, no rows
            (the record is irrelevant — there is no focused epoch)"
    (let [feed (h/project-feed-from-epoch nil :no-focus)]
      (is (= :no-focus (:empty-kind feed)))
      (is (= 0 (:total feed)))
      (is (= 0 (:rendered feed)))
      (is (= [] (:rows feed)))
      (is (nil? (:epoch-id feed))))))

(deftest project-feed-from-epoch-epoch-evicted
  (testing "focus-status :epoch-evicted → :epoch-evicted empty-kind,
            no rows (the focused epoch aged out of the ring buffer)"
    (let [feed (h/project-feed-from-epoch nil :epoch-evicted)]
      (is (= :epoch-evicted (:empty-kind feed)))
      (is (= 0 (:total feed)))
      (is (= 0 (:rendered feed)))
      (is (= [] (:rows feed))))))

(deftest project-feed-from-epoch-ignores-record-unless-focused
  (testing "rf2-td380: only :focused status reads the record's
            :trace-events — a non-:focused status renders empty even
            when a stray record is passed (defensive: the sub never
            does this, but the contract is total)"
    (let [epoch (domino-trail-epoch)]
      (is (= 0 (:total (h/project-feed-from-epoch epoch :no-focus))))
      (is (= 0 (:total (h/project-feed-from-epoch epoch :epoch-evicted)))))))

(deftest project-feed-from-epoch-shape-keys
  (testing "rf2-gkczt: the feed shape carries NO filtering keys —
            :filters / :any-filter? / :distinct / :counts /
            :active-filters / :cascade-dispatch-id are all gone"
    (let [feed (h/project-feed-from-epoch (domino-trail-epoch) :focused)]
      (is (contains? feed :rows))
      (is (contains? feed :total))
      (is (contains? feed :rendered))
      (is (contains? feed :epoch-id))
      (is (contains? feed :empty-kind))
      (doseq [k [:filters :any-filter? :distinct :counts
                 :active-filters :cascade-dispatch-id]]
        (is (not (contains? feed k))
            (str "removed filtering key " k " must not be in the feed shape"))))))

;; ---- (3) format-time ---------------------------------------------------

(deftest format-time-renders-hms-with-millis
  (testing "format-time returns nil on non-numeric input"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not a number"))))
  (testing "format-time returns a HH:MM:SS.mmm-shaped string"
    (let [s (h/format-time 12345)]
      (is (string? s))
      (is (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}$" s)))))

;; ---- (4) find-row ------------------------------------------------------

(deftest find-row-by-id
  (let [rows [{:id 1} {:id 2} {:id 3}]]
    (is (= {:id 2} (h/find-row rows 2)))
    (is (nil? (h/find-row rows 99)))))

;; ---- (5) op-type-colour ------------------------------------------------

(deftest op-type-colour-mapping
  ;; Post rf2-on4cm `op-type-colour` returns CSS-variable strings —
  ;; the active theme class on the shell root decides whether the dark
  ;; or light hex resolves at paint time. Compare against the var-map
  ;; (`tokens/tokens`) so the test pins the indirection rather than a
  ;; specific palette's hex.
  (is (= (:red    tokens/tokens)  (h/op-type-colour :error)))
  (is (= (:yellow tokens/tokens)  (h/op-type-colour :warning)))
  (is (= (:accent-static tokens/tokens) (h/op-type-colour :info)))
  (is (= (:accent tokens/tokens) (h/op-type-colour :rf.event)))
  (is (= (:green  tokens/tokens)  (h/op-type-colour :rf.fx)))
  (is (= (:magenta tokens/tokens) (h/op-type-colour :rf.view/render)))
  ;; Defensive fallback.
  (is (= (:text-secondary tokens/tokens) (h/op-type-colour :totally-unknown))))

;; ---- (6) source-coord -------------------------------------------------

(deftest source-coord-projection
  (testing "source-coord pulls file:line from :rf.trace/trigger-handler"
    (is (= "src/foo.cljs:42"
           (h/source-coord
             {:id 1 :op-type :rf.event
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"
                                                        :line 42}}}))))
  (testing "missing trigger-handler returns nil"
    (is (nil? (h/source-coord {:id 1 :op-type :rf.event}))))
  (testing "missing :line returns just the file"
    (is (= "src/foo.cljs"
           (h/source-coord
             {:id 1 :op-type :rf.event
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"}}})))))

;; ---- (7) short-description --------------------------------------------

(deftest short-description-priority-order
  (testing "event vector is preferred"
    (is (re-find #"counter/inc"
                 (h/short-description
                   (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                        :tags {:rf.event/v [:counter/inc]}})))))
  (testing "reason is used when no event vec"
    (is (re-find #"because"
                 (h/short-description
                   (ev {:id 1 :op-type :error :operation :rf.error/x
                        :tags {:reason "because"}})))))
  (testing "exception-message is used when no reason"
    (is (re-find #"boom"
                 (h/short-description
                   (ev {:id 1 :op-type :error :operation :rf.error/x
                        :tags {:exception-message "boom"}})))))
  (testing "sub-id is used for sub events"
    (is (re-find #"app/counter"
                 (h/short-description
                   (ev {:id 1 :op-type :rf.sub :operation :rf.sub/run
                        :tags {:rf.sub/id :app/counter}})))))
  (testing "fallback is the operation keyword alone"
    (is (= ":rf.event/dispatched"
           (h/short-description
             (ev {:id 1 :op-type :rf.event :operation :rf.event/dispatched
                  :tags {}}))))))

;; ---- (8) row-key — rf2-z4fza (sibling of rf2-kgn0c) -------------------
;;
;; Per rf2-z4fza: the trace ribbon's React `:key` must be a stable
;; per-trace-event identity (the framework's monotonic `:id`). The
;; earlier shape mixed in the row's positional index inside the
;; visible viewport, so every new trace push shifted every key and
;; React remounted the entire viewport — the dominant frame cost
;; under burst event rate. Same discipline class as rf2-kgn0c's
;; `v:<variant-id>` keys in the story workspace.

(deftest row-key-uses-stable-trace-id
  (testing "row-key reads only :id — the framework's monotonic trace
            id is stable per emit, so the key is stable across pushes"
    (is (= "t:7" (h/row-key {:id 7})))
    (is (= "t:42" (h/row-key {:id 42}))))
  (testing "row-key is unique per distinct trace id"
    (let [ids   (range 1 200)
          keys  (mapv #(h/row-key {:id %}) ids)]
      (is (= (count ids) (count (distinct keys)))
          "every trace id maps to a unique React key"))))

(deftest row-key-ignores-row-index-and-position
  (testing "rf2-z4fza acceptance: row-key MUST NOT consume :row-index
            (the slot is gone from rows; any future regression that
            re-adds it would silently destabilise keys)"
    (let [row-a (h/project-row {:id 5 :op-type :rf.event :operation :rf.event/dispatched
                                :time 100})
          row-b (assoc row-a :row-index 0)
          row-c (assoc row-a :row-index 99)]
      (is (= (h/row-key row-a) (h/row-key row-b))
          "row-key with :row-index 0 equals row-key without :row-index")
      (is (= (h/row-key row-a) (h/row-key row-c))
          "row-key with :row-index 99 equals row-key without :row-index"))))

(deftest project-feed-from-epoch-rows-carry-no-row-index-slot
  (testing "rf2-z4fza acceptance: rows MUST NOT carry a :row-index slot
            — its mere presence on the row was a footgun inviting
            positional React keys"
    (let [feed (h/project-feed-from-epoch (domino-trail-epoch) :focused)]
      (doseq [row (:rows feed)]
        (is (not (contains? row :row-index))
            (str "row " (:id row) " must not carry :row-index"))))))
