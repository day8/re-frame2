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
            [day8.re-frame2-causa.panels.trace-helpers :as h]
            [day8.re-frame2-causa.theme.tokens :as tokens]))

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
    (is (not (contains? (:distinct feed) :frame))
        ":frame is not enumerated as a chip-row axis post-rf2-ycoct
         (the Trace tab is cascade-scoped so every visible row
         already shares the focused event's frame)")))

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
  ;; Post rf2-on4cm `op-type-colour` returns CSS-variable strings —
  ;; the active theme class on the shell root decides whether the dark
  ;; or light hex resolves at paint time. Compare against the var-map
  ;; (`tokens/tokens`) so the test pins the indirection rather than a
  ;; specific palette's hex.
  (is (= (:red    tokens/tokens)  (h/op-type-colour :error)))
  (is (= (:yellow tokens/tokens)  (h/op-type-colour :warning)))
  (is (= (:cyan   tokens/tokens)  (h/op-type-colour :info)))
  (is (= (:accent-violet tokens/tokens) (h/op-type-colour :event)))
  (is (= (:green  tokens/tokens)  (h/op-type-colour :fx)))
  (is (= (:magenta tokens/tokens) (h/op-type-colour :view/render)))
  ;; Defensive fallback.
  (is (= (:text-secondary tokens/tokens) (h/op-type-colour :totally-unknown))))

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
            §Filter vocabulary that has a chip-row presentation.

            Note: `:frame` is intentionally NOT in `filter-axes` —
            post-rf2-ycoct the Trace tab is cascade-scoped, so the
            focused event has exactly one frame and a frame chip on
            top of the scope is redundant. The underlying algebra in
            `trace-bus/build-filter-predicate` still accepts `:frame`
            as a vocabulary primitive (covered by `filter-by-frame`
            below); we just don't enumerate it as a chip-row axis."
    (let [axes (set h/filter-axes)]
      (is (contains? axes :op-type))
      (is (contains? axes :severity))
      (is (contains? axes :source))
      (is (contains? axes :origin))
      (is (contains? axes :operation))
      (is (contains? axes :event-id))
      (is (contains? axes :handler-id))
      (is (contains? axes :dispatch-id))
      (is (not (contains? axes :frame))
          ":frame is not a chip-row axis post-rf2-ycoct cascade-scope"))))

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

;; ---- (14) orphan-filter surfacing — rf2-vu0mp --------------------------
;;
;; The chip-row enumeration is built from the events currently in the
;; buffer. When the buffer rotates past the cap and the selected
;; filter value's last instance ages out, `:trace-filters` still
;; carries the selection but the chip row no longer shows that value
;; — user sees `:no-matches` with no visible cue what's narrowing the
;; view. Per rf2-vu0mp the fix surfaces the active value in BOTH the
;; chip row (orphan still rendered, marked) AND the empty state
;; (`:active-filters` strip).

(deftest effective-distinct-passes-through-when-active-is-present
  (testing "no orphan: active filter value is in seen → distinct map
            passes through unchanged"
    (let [distinct-map {:op-type [:event :error]
                        :source  [:ui :timer]}
          seen-map     {:op-type #{:event :error}
                        :source  #{:ui :timer}}]
      (is (= distinct-map
             (h/effective-distinct distinct-map seen-map
                                   {:op-type :error :source :ui}))))))

(deftest effective-distinct-appends-orphan-active-value
  (testing "active filter value NOT in seen → appended to distinct
            so the chip ALWAYS renders for the user's selection"
    (let [distinct-map {:source [:ui]}
          seen-map     {:source #{:ui}}
          result       (h/effective-distinct distinct-map seen-map
                                             {:source :timer})]
      (is (= [:ui :timer] (get result :source))
          "the orphan :timer value lands at the tail of :source"))))

(deftest effective-distinct-handles-axis-absent-from-seen
  (testing "axis missing entirely from seen → still appends orphan"
    (let [distinct-map {}
          seen-map     {}
          result       (h/effective-distinct distinct-map seen-map
                                             {:frame :rf/test})]
      (is (= [:rf/test] (get result :frame))))))

(deftest effective-distinct-skips-no-duplicate-active-value
  (testing "if active value is already in distinct it is not duplicated"
    (let [distinct-map {:source [:timer :ui]}
          seen-map     {:source #{:ui}}  ;; :timer dropped from seen
          result       (h/effective-distinct distinct-map seen-map
                                             {:source :timer})]
      (is (= [:timer :ui] (get result :source))
          "no duplicate — first-seen order preserved"))))

(deftest effective-distinct-tolerates-nil-filters
  (testing "nil / empty filters → pass-through"
    (let [distinct-map {:source [:ui]}
          seen-map     {:source #{:ui}}]
      (is (= distinct-map (h/effective-distinct distinct-map seen-map nil)))
      (is (= distinct-map (h/effective-distinct distinct-map seen-map {}))))))

(deftest active-filters-summary-marks-present-and-orphaned
  (let [seen-map {:op-type #{:event :error}
                  :source  #{:ui}
                  :origin  #{:app}}
        summary  (h/active-filters-summary
                   {:op-type :error
                    :source  :timer    ;; orphan
                    :origin  :app}
                   seen-map)]
    (testing "every active axis has an entry"
      (is (= 3 (count summary))))
    (testing "iteration follows filter-axes order (op-type, severity,
              source, origin, ...)"
      (is (= [:op-type :source :origin] (mapv :axis summary))))
    (testing "present? is true when value is in seen"
      (is (true?  (:present? (some #(when (= :op-type (:axis %)) %) summary))))
      (is (false? (:present? (some #(when (= :source  (:axis %)) %) summary))))
      (is (true?  (:present? (some #(when (= :origin  (:axis %)) %) summary)))))
    (testing "value is preserved verbatim for the empty-state render"
      (is (= :timer (:value (some #(when (= :source (:axis %)) %) summary)))))))

(deftest active-filters-summary-handles-empty-or-nil
  (is (= [] (h/active-filters-summary nil nil)))
  (is (= [] (h/active-filters-summary {} {})))
  (is (= [] (h/active-filters-summary {:source nil} {}))
      "nil-valued filters are dropped by normalise-filters first"))

(deftest project-feed-orphan-filter-distinct-is-effective
  (testing "rf2-vu0mp: filtering on a value not present in the buffer
            still produces a distinct entry for that value so the chip
            row keeps rendering the user's selection"
    (let [events (events-fixture)
          ;; No :source = :timer in the all-:ui-/-:http events besides id=3.
          ;; Filter on an axis where the value DOES NOT exist in buffer:
          feed   (h/project-feed events
                                 {:source :sse})]
      (testing "the orphan value lands in distinct so the chip is rendered"
        (is (some #(= :sse %) (get-in feed [:distinct :source]))
            "orphan :sse appended to :source distinct"))
      (testing ":counts is unchanged — the value has 0 buffered events"
        (is (not (contains? (get-in feed [:counts :source]) :sse))
            "no synthetic count entry — the renderer reads 0 via get-default"))
      (testing ":active-filters surfaces the orphan with :present? false"
        (let [src-pill (some #(when (= :source (:axis %)) %)
                             (:active-filters feed))]
          (is (= :sse (:value src-pill)))
          (is (false? (:present? src-pill)))))
      (testing "empty-kind is :no-matches"
        (is (= :no-matches (:empty-kind feed)))))))

(deftest project-feed-active-filters-empty-when-no-filters
  (let [feed (h/project-feed (events-fixture) {})]
    (is (= [] (:active-filters feed))
        "no active filter → empty pill list")))

;; ---- (15) incremental projection state — rf2-44vzy ---------------------
;;
;; The trace-feed projection now reads a pre-computed snapshot
;; maintained incrementally by `:rf.causa/note-trace-event` rather
;; than re-walking the full buffer on every push. The snapshot is
;; updated in O(axes) per add and O(axes) per evict — bounded by the
;; axis count (13), not the buffer size.
;;
;; These tests pin the incremental algebra against the from-scratch
;; `project-feed` reference: for any sequence of events, building
;; the state event-by-event must produce the same distinct/counts/
;; rows shape as walking the events end-to-end in one shot.

(deftest init-feed-state-has-empty-axis-maps
  (let [state (h/init-feed-state)]
    (is (= 0 (:total state)))
    (is (= [] (:projected-rows state)))
    (doseq [axis h/filter-axes]
      (is (= [] (get-in state [:distinct axis]))
          (str "axis " axis " starts with empty distinct"))
      (is (= #{} (get-in state [:seen axis]))
          (str "axis " axis " starts with empty seen"))
      (is (= {} (get-in state [:counts axis]))
          (str "axis " axis " starts with empty counts")))))

(deftest feed-state+ev-bumps-axis-counts
  (let [e1    (ev {:id 1 :op-type :event :operation :event/dispatched
                   :time 100 :source :ui :origin :app
                   :frame :rf/default})
        e2    (ev {:id 2 :op-type :event :operation :event/dispatched
                   :time 200 :source :ui :origin :app
                   :frame :rf/default})
        state (-> (h/init-feed-state)
                  (h/feed-state+ev e1)
                  (h/feed-state+ev e2))]
    (is (= 2 (:total state)))
    (is (= 2 (count (:projected-rows state))))
    (is (= [:event]      (get-in state [:distinct :op-type])))
    (is (= [:ui]         (get-in state [:distinct :source])))
    (is (= {:event 2}    (get-in state [:counts :op-type])))
    (is (= {:ui 2}       (get-in state [:counts :source])))))

(deftest feed-state-evict-decrements-and-compacts
  (testing "evicting the head row decrements per-axis counts; when a
            count hits zero the value is dropped from seen + distinct
            so the chip row stops offering an aged-out value"
    (let [e1    (ev {:id 1 :op-type :event :operation :event/dispatched
                     :time 100 :source :timer :origin :app
                     :frame :rf/default})
          e2    (ev {:id 2 :op-type :error :operation :rf.error/x
                     :time 200 :source :ui :origin :app
                     :frame :rf/default})
          state (-> (h/init-feed-state)
                    (h/feed-state+ev e1)
                    (h/feed-state+ev e2)
                    h/feed-state-evict)]
      (is (= 1 (:total state)))
      (is (= 1 (count (:projected-rows state))))
      (testing ":source :timer was unique to the evicted row → dropped"
        (is (= [:ui] (get-in state [:distinct :source])))
        (is (not (contains? (get-in state [:seen :source]) :timer)))
        (is (not (contains? (get-in state [:counts :source]) :timer))))
      (testing ":source :ui still present (carried by e2)"
        (is (contains? (get-in state [:seen :source]) :ui))
        (is (= 1 (get-in state [:counts :source :ui])))))))

(deftest feed-state-evict-decrements-shared-value-without-compaction
  (testing "if two rows share a value, evicting one only decrements
            the count — the value stays in distinct / seen"
    (let [e1    (ev {:id 1 :op-type :event :operation :event/dispatched
                     :source :ui :frame :rf/default})
          e2    (ev {:id 2 :op-type :event :operation :event/dispatched
                     :source :ui :frame :rf/default})
          state (-> (h/init-feed-state)
                    (h/feed-state+ev e1)
                    (h/feed-state+ev e2)
                    h/feed-state-evict)]
      (is (= [:ui] (get-in state [:distinct :source])))
      (is (= 1 (get-in state [:counts :source :ui]))))))

(deftest feed-state-evict-noop-on-empty
  (testing "evicting from empty state returns the same shape"
    (let [empty-state (h/init-feed-state)]
      (is (= empty-state (h/feed-state-evict empty-state))))))

(deftest feed-state-push-respects-depth-cap
  (testing "feed-state-push mirrors trace-bus/push's eviction algebra
            — total never exceeds depth, oldest is evicted"
    (let [depth 3
          evs   (mapv #(ev {:id %1 :op-type :event :operation :event/dispatched
                            :source (case (mod %1 3)
                                      0 :ui 1 :timer 2 :http)
                            :frame :rf/default})
                      (range 1 11))
          state (reduce (fn [s e] (h/feed-state-push s depth e))
                        (h/init-feed-state)
                        evs)]
      (is (= depth (:total state)))
      (is (= depth (count (:projected-rows state))))
      (testing "the head row is the (depth)-most-recent"
        (let [retained-ids (mapv :id (:projected-rows state))]
          (is (= [8 9 10] retained-ids)))))))

(deftest rebuild-feed-state-equals-incremental
  (testing "building state from scratch via rebuild-feed-state
            equals folding events one at a time via feed-state+ev"
    (let [evs        (events-fixture)
          rebuilt    (h/rebuild-feed-state evs)
          folded     (reduce h/feed-state+ev (h/init-feed-state) evs)]
      (is (= rebuilt folded)))))

(deftest project-feed-from-state-matches-project-feed-no-filter
  (testing "project-feed-from-state produces the same shape as
            project-feed when no filter is active"
    (let [evs        (events-fixture)
          state      (h/rebuild-feed-state evs)
          from-state (h/project-feed-from-state state {})
          from-raw   (h/project-feed evs {})]
      (is (= (:total from-state)    (:total from-raw)))
      (is (= (:rendered from-state) (:rendered from-raw)))
      (is (= (mapv :id (:rows from-state))
             (mapv :id (:rows from-raw))))
      (is (= (:distinct from-state) (:distinct from-raw)))
      (is (= (:counts from-state)   (:counts from-raw)))
      (is (= (:empty-kind from-state) (:empty-kind from-raw))))))

(deftest project-feed-from-state-matches-project-feed-with-filter
  (testing "project-feed-from-state produces equivalent shape under
            active filters — early-stop filter walk preserves rendered
            count and row order"
    (let [evs        (events-fixture)
          filters    {:dispatch-id 10}
          state      (h/rebuild-feed-state evs)
          from-state (h/project-feed-from-state state filters)
          from-raw   (h/project-feed evs filters)]
      (is (= (:total from-state)    (:total from-raw)))
      (is (= (:rendered from-state) (:rendered from-raw)))
      (is (= (mapv :id (:rows from-state))
             (mapv :id (:rows from-raw))))
      (is (= (:filters from-state)  (:filters from-raw)))
      (is (= (:empty-kind from-state) (:empty-kind from-raw))))))

(deftest project-feed-from-state-orphan-filter-surfaces
  (testing "rf2-vu0mp via incremental path: orphan filter value still
            renders in distinct + active-filters carries :present? false"
    (let [evs   (events-fixture)
          state (h/rebuild-feed-state evs)
          feed  (h/project-feed-from-state state {:source :sse})]
      (is (some #(= :sse %) (get-in feed [:distinct :source])))
      (is (= :no-matches (:empty-kind feed)))
      (let [pill (some #(when (= :source (:axis %)) %)
                       (:active-filters feed))]
        (is (= :sse (:value pill)))
        (is (false? (:present? pill)))))))

(deftest project-feed-from-state-empty-buffer
  (let [feed (h/project-feed-from-state (h/init-feed-state) {})]
    (is (= 0 (:total feed)))
    (is (= 0 (:rendered feed)))
    (is (= :no-events (:empty-kind feed)))))

(deftest incremental-and-from-scratch-equivalence-under-cap-rotation
  (testing "after pushing 5× the cap, the incremental state matches a
            from-scratch rebuild over the same retained window — the
            evict path correctly compacts dropped values"
    (let [depth 5
          all   (mapv #(ev {:id %1 :op-type (case (mod %1 3)
                                              0 :event 1 :error 2 :warning)
                             :operation (case (mod %1 2)
                                          0 :event/dispatched
                                          :rf.error/handler-exception)
                             :time %1
                             :source (case (mod %1 4)
                                       0 :ui 1 :timer 2 :http 3 :sse)
                             :origin :app
                             :frame  :rf/default
                             :dispatch-id (quot %1 2)})
                      (range 1 26))
          state (reduce (fn [s e] (h/feed-state-push s depth e))
                        (h/init-feed-state)
                        all)
          ;; What the buffer should look like post-rotation:
          retained (vec (drop (- (count all) depth) all))
          rebuilt  (h/rebuild-feed-state retained)]
      (is (= (:total state)    (:total rebuilt)))
      (is (= (:projected-rows state) (:projected-rows rebuilt)))
      (is (= (:counts state)   (:counts rebuilt)))
      (is (= (:seen state)     (:seen rebuilt)))
      (testing "distinct may differ in order (incremental preserves
                first-seen-since-eviction order, not all-time order)
                — assert content equality via set, not vector"
        (doseq [axis h/filter-axes]
          (is (= (set (get-in state [:distinct axis]))
                 (set (get-in rebuilt [:distinct axis])))))))))

(deftest project-feed-from-state-active-filters-empty-when-no-filters
  (let [state (h/rebuild-feed-state (events-fixture))
        feed  (h/project-feed-from-state state {})]
    (is (= [] (:active-filters feed)))))

;; ---- (7) cascade-scope (rf2-ycoct) -------------------------------------
;;
;; Mike's call on rf2-ycoct: the Trace tab is cascade-scoped by default
;; (audit findings 2026-05-18). project-feed-from-state's 3-arity takes
;; `{:cascade-dispatch-id <id-or-:ungrouped-or-nil>}` and pre-filters
;; rows to the focused cascade. User chip filters AND on top.

(deftest project-feed-from-state-cascade-scope-narrows-to-cascade
  (testing "rf2-ycoct: cascade-scope filters rows to those whose
            :dispatch-id matches the focused cascade's id"
    (let [evs   (events-fixture)
          state (h/rebuild-feed-state evs)
          ;; Fixture has dispatch-ids 10, 11, 12. Scope to 10 → rows 1, 2, 4.
          feed  (h/project-feed-from-state state {} {:cascade-dispatch-id 10})]
      (is (= 5 (:total feed))
          ":total reflects entire buffer, not the scoped subset")
      (is (= 3 (:rendered feed))
          "three rows belong to cascade 10")
      (is (= #{1 2 4} (set (mapv :id (:rows feed))))
          "exactly cascade 10's events surface")
      (is (= 10 (:cascade-dispatch-id feed))
          ":cascade-dispatch-id rides on the result"))))

(deftest project-feed-from-state-cascade-scope-ands-with-user-filter
  (testing "rf2-ycoct: user chip filter AND-wise with cascade-scope"
    (let [evs    (events-fixture)
          state  (h/rebuild-feed-state evs)
          ;; Cascade 10 has 3 rows (ids 1, 2, 4). Of those, op-type :error
          ;; is only row 2. AND-wise: cascade 10 ∩ op-type :error = {2}.
          feed   (h/project-feed-from-state state
                                            {:op-type :error}
                                            {:cascade-dispatch-id 10})]
      (is (= 1 (:rendered feed)))
      (is (= [2] (mapv :id (:rows feed)))))))

(deftest project-feed-from-state-cascade-scope-no-overlap-empty
  (testing "rf2-ycoct: scope on a cascade not in the buffer renders
            zero rows and reports :no-matches"
    (let [evs   (events-fixture)
          state (h/rebuild-feed-state evs)
          feed  (h/project-feed-from-state state {} {:cascade-dispatch-id 99999})]
      (is (= 0 (:rendered feed)))
      (is (= :no-matches (:empty-kind feed)))
      (is (= 99999 (:cascade-dispatch-id feed))))))

(deftest project-feed-from-state-cascade-scope-ungrouped-matches-nil-dispatch-id
  (testing "rf2-ycoct: scope :ungrouped matches rows whose :dispatch-id
            is nil (the projection's catch-all bucket)"
    (let [evs   [(ev {:id 1 :op-type :event :operation :event/dispatched
                      :time 100 :source :ui})           ; no dispatch-id
                 (ev {:id 2 :op-type :event :operation :event/dispatched
                      :time 200 :source :ui :dispatch-id 10})
                 (ev {:id 3 :op-type :info  :operation :rf/lifecycle
                      :time 300 :source :ui})]          ; no dispatch-id
          state (h/rebuild-feed-state evs)
          feed  (h/project-feed-from-state state {} {:cascade-dispatch-id :ungrouped})]
      (is (= 2 (:rendered feed))
          "rows 1 and 3 have no :dispatch-id → they belong to :ungrouped")
      (is (= #{1 3} (set (mapv :id (:rows feed))))))))

(deftest project-feed-from-state-no-focus-defensive-empty-state
  (testing "rf2-ycoct defensive: opts passed with nil :cascade-dispatch-id
            and a non-empty buffer triggers :no-focus (the default-focus
            rule from rf2-639lc should prevent this in production)"
    (let [evs   (events-fixture)
          state (h/rebuild-feed-state evs)
          feed  (h/project-feed-from-state state {} {:cascade-dispatch-id nil})]
      (is (= :no-focus (:empty-kind feed)))
      (is (= 0 (:rendered feed)))
      (is (= [] (:rows feed)))
      (is (= 5 (:total feed))
          ":total reflects the buffer (the state is broken, not the data)"))))

(deftest project-feed-from-state-2-arity-preserves-global-ribbon-shape
  (testing "rf2-ycoct: the 2-arity (no opts) keeps the pre-rf2-ycoct
            global-ribbon behaviour — no cascade-scope, no :no-focus
            defensive branch. Headless test rigs / callers that pre-
            date the spine wiring continue to work."
    (let [evs   (events-fixture)
          state (h/rebuild-feed-state evs)
          feed  (h/project-feed-from-state state {})]
      (is (= 5 (:rendered feed))
          "every buffered row renders — no cascade-scope applied")
      (is (nil? (:empty-kind feed)))
      (is (nil? (:cascade-dispatch-id feed))
          "the scope key is nil (no scope was passed)"))))

(deftest project-feed-from-state-cascade-scope-empty-buffer
  (testing "rf2-ycoct: with an empty buffer, :no-events wins regardless
            of the cascade-scope value (the buffer is empty FIRST,
            scoping is a no-op)"
    (let [state (h/init-feed-state)
          feed  (h/project-feed-from-state state {} {:cascade-dispatch-id 100})]
      (is (= :no-events (:empty-kind feed))
          ":no-events takes precedence over :no-focus when total = 0"))))
