(ns day8.re-frame2-causa.panels.trace-helpers-cljs-test
  "Pure-data tests for Causa's Trace panel helpers (Phase 5, rf2-argrj).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `issues_ribbon_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **Per-row projection** — `project-row` populates every axis
       slot from raw trace events (Spec 009 §Filter vocabulary).
    2. **Each of the 9 filter axes** filters correctly in isolation:
       `:operation` / `:op-type` / `:since` / `:frame` / `:severity`
       / `:event-id` / `:handler-id` / `:source` / `:origin` /
       `:dispatch-id` / `:since-ms` / `:between` / `:pred`.
    3. **Composition** — axes combine AND-wise.
    4. **`:between` and `:since-ms`** time-range axes work.
    5. **`:pred`** arbitrary predicate axis works.
    6. **`normalise-filters`** drops nil / empty values.
    7. **`distinct-values` + `axis-counts`** populate the chip rows.
    8. **`project-feed`** composite + empty-kind classification."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.trace-helpers :as h]))

;; ---- fixture builders ---------------------------------------------------

(defn- ev
  "Build a Spec 009-shaped trace event for the tests. The 9 filter
  axes pull from both top-level slots and `:tags` — the fixture
  surfaces every slot the vocabulary documents."
  [{:keys [id op-type operation time source origin frame event-id
           handler-id dispatch-id tags coord]
    :or {time 1000 tags {}}}]
  (cond-> {:id        id
           :op-type   op-type
           :operation operation
           :time      time
           :tags      (cond-> tags
                       origin       (assoc :origin origin)
                       frame        (assoc :frame frame)
                       event-id     (assoc :event-id event-id)
                       handler-id   (assoc :handler-id handler-id)
                       dispatch-id  (assoc :dispatch-id dispatch-id))}
    source (assoc :source source)
    coord  (assoc :rf.trace/trigger-handler {:source-coord coord})))

;; ---- (1) per-row projection --------------------------------------------

(deftest project-row-populates-every-axis-slot
  (let [e (ev {:id 7 :op-type :event :operation :event/dispatched
               :time 500 :source :ui :origin :app
               :frame :rf/default :event-id :counter/inc
               :handler-id :counter/inc-handler
               :dispatch-id 42
               :tags {:event [:counter/inc]}
               :coord {:file "src/foo.cljs" :line 12}})
        row (h/project-row e)]
    (is (= 7 (:id row)))
    (is (= 500 (:time row)))
    (is (= :event (:op-type row)))
    (is (= :event/dispatched (:operation row)))
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
                           (ev {:id 1 :op-type :event
                                :operation :event/dispatched})))))))

;; ---- (2) the 9 filter axes — each in isolation -------------------------

(defn- events-fixture
  "A small mixed-vocabulary fixture used across the axis tests. Every
  axis the panel filters on has at least two distinct values so a
  filter actually does work."
  []
  [(ev {:id 1 :op-type :event   :operation :event/dispatched
        :time 100 :source :ui :origin :app
        :frame :rf/default :event-id :user/login
        :dispatch-id 10 :tags {:event [:user/login]}})
   (ev {:id 2 :op-type :error   :operation :rf.error/handler-exception
        :time 200 :source :ui :origin :app
        :frame :rf/default :handler-id :user/login-handler
        :dispatch-id 10
        :tags {:exception-message "boom"}})
   (ev {:id 3 :op-type :warning :operation :rf.warning/missing-doc
        :time 300 :source :timer :origin :pair
        :frame :rf/causa :dispatch-id 11})
   (ev {:id 4 :op-type :sub/run :operation :sub/run
        :time 400 :source :ui :origin :app
        :frame :rf/default :dispatch-id 10
        :tags {:sub-id :app/counter}})
   (ev {:id 5 :op-type :info    :operation :rf.http/retry-attempt
        :time 500 :source :http :origin :app
        :frame :rf/default :dispatch-id 12})])

(deftest filter-by-operation
  (is (= [2]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:operation :rf.error/handler-exception})))))

(deftest filter-by-op-type
  (is (= [2]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:op-type :error}))))
  (is (= [4]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:op-type :sub/run})))))

(deftest filter-by-since-cursor
  (testing ":since is a cursor on :id — strictly greater than"
    (is (= [3 4 5]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:since 2}))))))

(deftest filter-by-frame
  (is (= [3]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:frame :rf/causa}))))
  (is (= [1 2 4 5]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:frame :rf/default})))))

(deftest filter-by-severity
  (testing ":severity is the synonym axis on :op-type restricted to
            the three tiers"
    (is (= [2]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:severity :error}))))
    (is (= [3]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:severity :warning}))))
    (is (= [5]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:severity :info}))))))

(deftest filter-by-event-id
  (is (= [1]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:event-id :user/login})))))

(deftest filter-by-handler-id
  (is (= [2]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:handler-id :user/login-handler})))))

(deftest filter-by-source
  (is (= [3]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:source :timer}))))
  (is (= [5]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:source :http})))))

(deftest filter-by-origin
  (is (= [3]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:origin :pair})))))

(deftest filter-by-dispatch-id
  (is (= [1 2 4]
         (mapv :id (h/apply-filters (events-fixture)
                                    {:dispatch-id 10})))))

(deftest filter-by-since-ms
  (testing ":since-ms restricts events whose :time is strictly greater"
    (is (= [4 5]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:since-ms 300}))))))

(deftest filter-by-between
  (testing ":between is a [t0 t1] inclusive window"
    (is (= [2 3 4]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:between [200 400]}))))))

(deftest filter-by-pred
  (testing ":pred is an arbitrary fn against the raw event map"
    (is (= [3]
           (mapv :id (h/apply-filters
                       (events-fixture)
                       {:pred (fn [e]
                                (= "rf.warning" (some-> e :operation
                                                        namespace)))}))))))

;; ---- (3) axes compose AND-wise -----------------------------------------

(deftest filters-compose-and-wise
  (testing "two axes intersect"
    (is (= [3]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:op-type :warning
                                       :frame   :rf/causa})))))
  (testing "three axes intersect"
    (is (= [4]
           (mapv :id (h/apply-filters (events-fixture)
                                      {:dispatch-id 10
                                       :source      :ui
                                       :op-type     :sub/run}))))))

;; ---- (4) normalise-filters / any-filter? -------------------------------

(deftest normalise-drops-nil-and-empty
  (is (= {} (h/normalise-filters nil)))
  (is (= {} (h/normalise-filters {})))
  (is (= {} (h/normalise-filters {:op-type nil})))
  (is (= {} (h/normalise-filters {:operation nil :source nil})))
  (is (= {:op-type :event}
         (h/normalise-filters {:op-type :event :source nil}))))

(deftest any-filter-active-mirrors-normalise
  (is (false? (h/any-filter-active? nil)))
  (is (false? (h/any-filter-active? {:op-type nil})))
  (is (true?  (h/any-filter-active? {:op-type :event}))))

;; ---- (5) distinct-values + axis-counts ---------------------------------

(deftest distinct-values-first-seen-order
  (let [rows (h/project-rows (events-fixture))]
    (is (= [:event :error :warning :sub/run :info]
           (h/distinct-values rows :op-type)))
    (is (= [:ui :timer :http]
           (h/distinct-values rows :source)))
    (is (= [:app :pair]
           (h/distinct-values rows :origin)))
    (is (= [:rf/default :rf/causa]
           (h/distinct-values rows :frame)))))

(deftest axis-counts-correct-histogram
  (let [rows (h/project-rows (events-fixture))]
    (is (= {:ui 3 :timer 1 :http 1}
           (h/axis-counts rows :source)))
    (is (= {:app 4 :pair 1}
           (h/axis-counts rows :origin)))))

;; ---- (6) project-feed (composite) --------------------------------------

(deftest project-feed-empty-buffer
  (let [feed (h/project-feed [] {})]
    (is (= 0 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (= :no-events (:empty-kind feed)))
    (is (false? (:any-filter? feed)))))

(deftest project-feed-no-matches
  (let [feed (h/project-feed (events-fixture)
                             {:op-type :error :frame :rf/causa})]
    (is (= 5 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (= :no-matches (:empty-kind feed)))
    (is (true?  (:any-filter? feed)))))

(deftest project-feed-rows-newest-first
  (let [feed (h/project-feed (events-fixture) {})]
    (testing ":rows is newest first for display"
      (is (= [5 4 3 2 1] (mapv :id (:rows feed)))))
    (testing ":empty-kind is nil when there are matches"
      (is (nil? (:empty-kind feed))))))

(deftest project-feed-distinct-and-counts-per-axis
  (let [feed (h/project-feed (events-fixture) {})]
    (is (= [:ui :timer :http]
           (get-in feed [:distinct :source])))
    (is (= {:ui 3 :timer 1 :http 1}
           (get-in feed [:counts :source])))
    (is (contains? (:distinct feed) :op-type))
    (is (contains? (:distinct feed) :severity))
    (is (contains? (:distinct feed) :origin))
    (is (contains? (:distinct feed) :frame))))

(deftest project-feed-filters-pass-through-normalised
  (let [feed (h/project-feed (events-fixture)
                             {:op-type :error :source nil})]
    (testing "normalised filters drop the nil axis"
      (is (= {:op-type :error} (:filters feed))))))

;; ---- (7) format-time ---------------------------------------------------

(deftest format-time-renders-hms-with-millis
  (testing "format-time returns nil on non-numeric input"
    (is (nil? (h/format-time nil)))
    (is (nil? (h/format-time "not a number"))))
  (testing "format-time returns a HH:MM:SS.mmm-shaped string"
    (let [s (h/format-time 12345)]
      (is (string? s))
      (is (re-find #"^\d{2}:\d{2}:\d{2}\.\d{3}$" s)))))

;; ---- (8) find-row ------------------------------------------------------

(deftest find-row-by-id
  (let [rows [{:id 1} {:id 2} {:id 3}]]
    (is (= {:id 2} (h/find-row rows 2)))
    (is (nil? (h/find-row rows 99)))))

;; ---- (9) op-type-colour ------------------------------------------------

(deftest op-type-colour-mapping
  (is (= "#F87171" (h/op-type-colour :error)))
  (is (= "#FBBF24" (h/op-type-colour :warning)))
  (is (= "#43C3D0" (h/op-type-colour :info)))
  (is (= "#7C5CFF" (h/op-type-colour :event)))
  (is (= "#4ADE80" (h/op-type-colour :fx)))
  (is (= "#E879F9" (h/op-type-colour :view/render)))
  ;; Defensive fallback.
  (is (= "#A8AEC0" (h/op-type-colour :totally-unknown))))

;; ---- (10) source-coord -------------------------------------------------

(deftest source-coord-projection
  (testing "source-coord pulls file:line from :rf.trace/trigger-handler"
    (is (= "src/foo.cljs:42"
           (h/source-coord
             {:id 1 :op-type :event
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"
                                                        :line 42}}}))))
  (testing "missing trigger-handler returns nil"
    (is (nil? (h/source-coord {:id 1 :op-type :event}))))
  (testing "missing :line returns just the file"
    (is (= "src/foo.cljs"
           (h/source-coord
             {:id 1 :op-type :event
              :rf.trace/trigger-handler {:source-coord {:file "src/foo.cljs"}}})))))

;; ---- (11) short-description --------------------------------------------

(deftest short-description-priority-order
  (testing "event vector is preferred"
    (is (re-find #"counter/inc"
                 (h/short-description
                   (ev {:id 1 :op-type :event :operation :event/dispatched
                        :tags {:event [:counter/inc]}})))))
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
                   (ev {:id 1 :op-type :sub/run :operation :sub/run
                        :tags {:sub-id :app/counter}})))))
  (testing "fallback is the operation keyword alone"
    (is (= ":event/dispatched"
           (h/short-description
             (ev {:id 1 :op-type :event :operation :event/dispatched
                  :tags {}}))))))

;; ---- (12) filter-axes coverage matches the bead's contract -------------

(deftest filter-axes-cover-the-bead-contract
  (testing "the panel surfaces every named axis from Spec 009
            §Filter vocabulary that has a chip-row presentation"
    (let [axes (set h/filter-axes)]
      (is (contains? axes :op-type))
      (is (contains? axes :severity))
      (is (contains? axes :source))
      (is (contains? axes :origin))
      (is (contains? axes :frame))
      (is (contains? axes :operation))
      (is (contains? axes :event-id))
      (is (contains? axes :handler-id))
      (is (contains? axes :dispatch-id)))))

;; ---- (13) row-key — rf2-z4fza (sibling of rf2-kgn0c) -------------------
;;
;; Per rf2-z4fza: the trace ribbon's React `:key` must be a stable
;; per-trace-event identity (the framework's monotonic `:id`). The
;; earlier shape mixed in the row's positional index inside the
;; visible viewport, so every new trace push shifted every key and
;; React remounted the entire viewport — the dominant frame cost
;; under burst event rate. Same discipline class as rf2-kgn0c's
;; `v:<variant-id>` keys in the story workspace.

(deftest project-feed-rows-carry-no-row-index-slot
  (testing "rf2-z4fza acceptance: rows MUST NOT carry a :row-index
            slot — its mere presence on the row was a footgun
            inviting positional React keys"
    (let [feed (h/project-feed (events-fixture) {})]
      (doseq [row (:rows feed)]
        (is (not (contains? row :row-index))
            (str "row " (:id row) " must not carry :row-index"))))))

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
    (let [row-a (h/project-row {:id 5 :op-type :event :operation :event/dispatched
                                :time 100})
          row-b (assoc row-a :row-index 0)
          row-c (assoc row-a :row-index 99)]
      (is (= (h/row-key row-a) (h/row-key row-b))
          "row-key with :row-index 0 equals row-key without :row-index")
      (is (= (h/row-key row-a) (h/row-key row-c))
          "row-key with :row-index 99 equals row-key without :row-index"))))

(deftest row-keys-are-stable-across-trace-pushes
  (testing "rf2-z4fza headline guarantee: appending a new trace event
            does NOT change the React key of any previously-existing
            row — same input row → same key. This is the property
            React's reconciler needs to reuse DOM nodes across pushes
            instead of unmounting+remounting the viewport on every push."
    (let [events-1 (events-fixture)
          ;; Simulate a burst — append three new events to the buffer.
          new-evs  [(ev {:id 6 :op-type :event :operation :event/dispatched
                         :time 600 :source :ui :origin :app
                         :frame :rf/default})
                    (ev {:id 7 :op-type :fx :operation :rf.fx/handled
                         :time 700 :source :ui :origin :app
                         :frame :rf/default})
                    (ev {:id 8 :op-type :error :operation :rf.error/handler-threw
                         :time 800 :source :ui :origin :app
                         :frame :rf/default})]
          events-2 (vec (concat events-1 new-evs))
          feed-1   (h/project-feed events-1 {})
          feed-2   (h/project-feed events-2 {})
          keys-1   (into {} (map (juxt :id h/row-key) (:rows feed-1)))
          keys-2   (into {} (map (juxt :id h/row-key) (:rows feed-2)))]
      (doseq [[id k1] keys-1]
        (is (= k1 (get keys-2 id))
            (str "row id " id ": key must be stable across pushes "
                 "(pre-push=" (pr-str k1) ", post-push=" (pr-str (get keys-2 id))
                 "). rf2-z4fza: positional :row-index in the key would shift "
                 "this on every append and re-mount the entire viewport.")))
      (testing "all post-push keys are unique"
        (let [all-keys (vals keys-2)]
          (is (= (count all-keys) (count (distinct all-keys))))))
      (testing "new events get fresh keys, not collisions"
        (doseq [id [6 7 8]]
          (is (some? (get keys-2 id)))
          (is (not (contains? keys-1 id))))))))
