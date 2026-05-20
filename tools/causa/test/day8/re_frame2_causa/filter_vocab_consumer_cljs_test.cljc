(ns day8.re-frame2-causa.filter-vocab-consumer-cljs-test
  "Consumer-side trace-buffer filter vocabulary tests (rf2-qi8au).

  ## Why this suite exists

  rf2-97ah0 extended the trace-buffer filter vocabulary on the
  framework side with nine new axes (`:severity`, `:event-id`,
  `:handler-id`, `:source`, `:origin`, `:dispatch-id`, `:since-ms`,
  `:between`, `:pred`) on top of the pre-existing `:operation`,
  `:op-type`, `:since`, `:frame`. `re-frame.trace-buffer-test`
  (implementation/core) covers every axis end-to-end against the
  framework's ring buffer.

  Causa is the new heaviest consumer of the trace stream:
    - Phase 1 (rf2-n6x4q) shipped Causa's own deeper ring buffer (1000
      events; framework default is 200), wired via
      `register-trace-listener!`. The buffer is process-global, not
      per-frame — `tools/causa/src/day8/re_frame2_causa/trace_bus.cljc`.
    - Phase 2 (rf2-op3bz) added the event-detail hero panel, which
      slices the buffer by selected `:dispatch-id` through the
      `:rf.causa/event-detail` composite subscription.
    - Subsequent panels under rf2-5aw5v (time-travel, app-db diff,
      machine inspector) will slice by `:event-id`, `:severity`,
      `:source`, `:origin`, `:since-ms`, etc.

  This suite locks the **consumer contract** ahead of those panels
  landing: the same filter vocabulary the framework's
  `(rf/trace-buffer opts)` exposes also works against Causa's buffer
  contents through `trace-bus/filter-events`. When a future panel
  begins slicing the buffer by, say, `:dispatch-id`, a regression in
  Causa's filter consumer fails one of these tests rather than shipping
  a broken slice to the UI.

  ## Test strategy

  The buffer-shape contract: Causa receives the framework's emitted
  trace events verbatim (no projection on push). So filtering Causa's
  buffer is filtering the same event shapes the framework filters.
  Each test seeds Causa's buffer with fake events carrying the
  appropriate `:tags` / top-level slots and asserts that
  `trace-bus/filter-events` selects exactly the right subset.

  Pure-data fake events keep the suite JVM-runnable (matching the
  framework-side `re-frame.trace-buffer-test` JVM-only contract).
  Cross-platform via CLJC — both `clojure -M:test` (cognitect.test-
  runner, JVM) and shadow's `:node-test` build (CLJS) pick this file
  up.

  ## Axes covered

  Pre-rf2-97ah0 (4): :operation, :op-type, :since, :frame.
  rf2-97ah0 (9):    :severity, :event-id, :handler-id, :source,
                    :origin, :dispatch-id, :since-ms, :between, :pred.

  Plus AND-wise composition and empty-filter passthrough.

  Per rf2-qi8au."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]])
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures --------------------------------------------------------------
;;
;; Each test starts from an empty Causa buffer. The buffer is process-
;; global; without the reset, events from one test bleed into the
;; next's filter results. Same isolation pattern as
;; `trace_bus_test.clj` (rf2-n6x4q).

(defn- clear-buffer [test-fn]
  (trace-bus/clear-buffer!)
  (test-fn)
  (trace-bus/clear-buffer!))

(use-fixtures :each clear-buffer)

;; ---- fake-event fixture ----------------------------------------------------
;;
;; Same shape the framework's `emit!` produces — :operation / :op-type /
;; :id / :time / :tags / optional :source top-level slot. Constructed by
;; hand so the JVM tests don't need to boot the framework's drain to
;; populate every axis. Mirrors the projection.cljc test fixtures'
;; approach (per rf2-wvzgd) and Causa's own
;; `panels/event_detail_cljs_test.cljs` `cascade-evs` helper.

(defn- ev
  "Build a synthetic trace event. `id` is the auto-incrementing
  per-process counter; `op-type` discriminates the slot; `operation`
  is the named operation; `tags` is the op-type-specific bag. Optional
  `extras` merge into the top-level envelope (for `:source`, `:time`)."
  ([id op-type operation tags]
   (ev id op-type operation tags {}))
  ([id op-type operation tags extras]
   (merge {:id        id
           :op-type   op-type
           :operation operation
           :time      (* 1000 id)   ;; deterministic monotonic clock
           :tags      tags}
          extras)))

(defn- seed!
  "Push the given event vector into Causa's buffer in order via
  `seed-buffer-for-test!` — bypasses every ingest gate
  (`interop/debug-enabled?`, the privacy filter, and the rf2-xs8vu
  self-noise filter that drops `:frame :rf/causa` events) so the
  filter-axis suite can populate the buffer with synthetic shapes
  that wouldn't normally survive the public collector. Pure-data,
  JVM-runnable. The buffer is reset by the fixture before each test,
  so seeded events are the only contents."
  [evs]
  (doseq [e evs]
    (trace-bus/seed-buffer-for-test! e))
  nil)

;; ---- pre-rf2-97ah0 axes (4) ------------------------------------------------

(deftest filter-operation-axis
  (testing ":operation narrows to one operation value"
    (seed! [(ev 1 :event :event/dispatched {:event-id :user/login})
            (ev 2 :event :event/db-changed {:event-id :user/login})
            (ev 3 :event :event/dispatched {:event-id :user/logout})])
    (let [hits (trace-bus/filter-events (trace-bus/buffer)
                                        {:operation :event/dispatched})]
      (is (= 2 (count hits))
          ":operation :event/dispatched keeps the two matching events")
      (is (every? #(= :event/dispatched (:operation %)) hits)
          "every retained event matches the requested :operation"))))

(deftest filter-op-type-axis
  (testing ":op-type narrows to one discriminator"
    (seed! [(ev 1 :event :event/dispatched {})
            (ev 2 :fx    :rf.fx/handled    {:fx-id :db})
            (ev 3 :sub/run :sub/run        {:sub-id :ui/active?})
            (ev 4 :view  :view/render      {:render-key [:app/root nil]})])
    (let [evs (trace-bus/filter-events (trace-bus/buffer) {:op-type :fx})]
      (is (= 1 (count evs)))
      (is (every? #(= :fx (:op-type %)) evs)))))

(deftest filter-since-axis
  (testing ":since keeps events whose :id is strictly greater"
    (seed! [(ev 1 :event :event/dispatched {})
            (ev 2 :event :event/dispatched {})
            (ev 3 :event :event/dispatched {})
            (ev 4 :event :event/dispatched {})])
    (let [evs (trace-bus/filter-events (trace-bus/buffer) {:since 2})]
      (is (= [3 4] (mapv :id evs))
          ":since 2 keeps :id 3 and 4 (strictly greater)"))
    (testing ":since 0 keeps all"
      (is (= 4 (count (trace-bus/filter-events (trace-bus/buffer)
                                               {:since 0})))))
    (testing ":since beyond the last id keeps none"
      (is (= [] (trace-bus/filter-events (trace-bus/buffer)
                                         {:since 99}))))))

(deftest filter-frame-axis
  (testing ":frame matches either top-level :frame or :tags :frame"
    (seed! [(ev 1 :event :event/dispatched {:frame :rf/default})
            (ev 2 :event :event/dispatched {:frame :rf/causa})
            (ev 3 :event :event/dispatched {} {:frame :rf/causa})
            (ev 4 :event :event/dispatched {:frame :rf/default})])
    (let [causa-evs (trace-bus/filter-events (trace-bus/buffer)
                                             {:frame :rf/causa})]
      (is (= 2 (count causa-evs))
          "matches events tagged via :tags :frame AND via top-level :frame")
      (is (every? #(= :rf/causa (or (:frame %)
                                    (get-in % [:tags :frame])))
                  causa-evs)))))

;; ---- rf2-97ah0 extension axes (9) ------------------------------------------

(deftest filter-severity-axis
  (testing ":severity is a synonym for :op-type restricted to the tier"
    (seed! [(ev 1 :event   :event/dispatched          {})
            (ev 2 :error   :rf.error/handler-exception {:handler-id :ev/x})
            (ev 3 :warning :rf.warning/handler-replaced {})
            (ev 4 :info    :rf.info/snapshot-taken     {})])
    (let [errs (trace-bus/filter-events (trace-bus/buffer)
                                        {:severity :error})]
      (is (= 1 (count errs)))
      (is (every? #(= :error (:op-type %)) errs)))
    (let [warns (trace-bus/filter-events (trace-bus/buffer)
                                         {:severity :warning})]
      (is (= 1 (count warns)))
      (is (every? #(= :warning (:op-type %)) warns)))
    (let [infos (trace-bus/filter-events (trace-bus/buffer)
                                         {:severity :info})]
      (is (= 1 (count infos)))
      (is (every? #(= :info (:op-type %)) infos)))))

(deftest filter-event-id-axis
  (testing ":event-id matches :tags :event-id"
    (seed! [(ev 1 :event :event/db-changed {:event-id :ev/alpha})
            (ev 2 :event :event/db-changed {:event-id :ev/beta})
            (ev 3 :event :event/db-changed {:event-id :ev/alpha})])
    (let [alpha (trace-bus/filter-events (trace-bus/buffer)
                                         {:event-id :ev/alpha})]
      (is (= 2 (count alpha)))
      (is (every? #(= :ev/alpha (get-in % [:tags :event-id])) alpha)))))

(deftest filter-handler-id-axis
  (testing ":handler-id matches :tags :handler-id (e.g. on error emits)"
    (seed! [(ev 1 :error :rf.error/handler-exception {:handler-id :ev/boom})
            (ev 2 :error :rf.error/handler-exception {:handler-id :ev/other})
            (ev 3 :error :rf.error/handler-exception {:handler-id :ev/boom})])
    (let [hits (trace-bus/filter-events (trace-bus/buffer)
                                        {:handler-id :ev/boom})]
      (is (= 2 (count hits)))
      (is (every? #(= :ev/boom (get-in % [:tags :handler-id])) hits)))))

(deftest filter-source-axis
  (testing ":source matches the top-level :source slot (hoisted by emit!)"
    (seed! [(ev 1 :event :event/dispatched {} {:source :repl})
            (ev 2 :event :event/dispatched {} {:source :timer})
            (ev 3 :event :event/dispatched {} {:source :repl})
            (ev 4 :event :event/dispatched {} {:source :ui})])
    (let [repl (trace-bus/filter-events (trace-bus/buffer)
                                        {:source :repl})]
      (is (= 2 (count repl)))
      (is (every? #(= :repl (:source %)) repl)))
    (testing "falls back to :tags :source when not hoisted"
      ;; Defensive: some test fixtures synthesize events without going
      ;; through emit!; the filter should still match if :source rides
      ;; under :tags.
      (trace-bus/clear-buffer!)
      (seed! [(ev 1 :event :event/dispatched {:source :http})])
      (let [http (trace-bus/filter-events (trace-bus/buffer)
                                          {:source :http})]
        (is (= 1 (count http))
            ":source falls back to :tags :source when top-level absent")))))

(deftest filter-origin-axis
  (testing ":origin matches :tags :origin (per Spec 002 §Dispatch origin tagging)"
    (seed! [(ev 1 :event :event/dispatched {:origin :app})
            (ev 2 :event :event/dispatched {:origin :pair})
            (ev 3 :event :event/dispatched {:origin :story})
            (ev 4 :event :event/dispatched {:origin :test})
            (ev 5 :event :event/dispatched {:origin :pair})])
    (let [pair (trace-bus/filter-events (trace-bus/buffer)
                                        {:origin :pair})]
      (is (= 2 (count pair)))
      (is (every? #(= :pair (get-in % [:tags :origin])) pair)))
    (let [story (trace-bus/filter-events (trace-bus/buffer)
                                         {:origin :story})]
      (is (= 1 (count story)))
      (is (every? #(= :story (get-in % [:tags :origin])) story)))))

(deftest filter-dispatch-id-axis
  (testing ":dispatch-id narrows to one cascade (rf2-g6ih4 cascade-wide tag)"
    ;; A two-cascade buffer: dispatch-id 100 emits four events; 200
    ;; emits three. The filter should retrieve only the four.
    (seed! [(ev 1 :event   :event/dispatched {:dispatch-id 100})
            (ev 2 :event   :event/db-changed {:dispatch-id 100})
            (ev 3 :fx      :rf.fx/handled    {:dispatch-id 100 :fx-id :db})
            (ev 4 :sub/run :sub/run          {:dispatch-id 100 :sub-id :ui/x})
            (ev 5 :event   :event/dispatched {:dispatch-id 200})
            (ev 6 :event   :event/db-changed {:dispatch-id 200})
            (ev 7 :fx      :rf.fx/handled    {:dispatch-id 200 :fx-id :db})])
    (let [slice (trace-bus/filter-events (trace-bus/buffer)
                                         {:dispatch-id 100})]
      (is (= 4 (count slice))
          "every event in cascade 100 retained")
      (is (every? #(= 100 (get-in % [:tags :dispatch-id])) slice)
          "every retained event carries the same :dispatch-id"))
    (testing "selecting a cascade not in the buffer yields []"
      (is (= [] (trace-bus/filter-events (trace-bus/buffer)
                                         {:dispatch-id 999}))))))

(deftest filter-since-ms-axis
  (testing ":since-ms keeps events whose :time is strictly greater"
    ;; `ev` stamps :time = (* 1000 id) so we can test the boundary
    ;; deterministically without sleeps.
    (seed! [(ev 1 :event :event/dispatched {})    ;; :time 1000
            (ev 2 :event :event/dispatched {})    ;; :time 2000
            (ev 3 :event :event/dispatched {})    ;; :time 3000
            (ev 4 :event :event/dispatched {})])  ;; :time 4000
    (let [evs (trace-bus/filter-events (trace-bus/buffer) {:since-ms 2000})]
      (is (= [3 4] (mapv :id evs))
          "strictly greater than 2000 keeps :id 3 and 4"))
    (testing ":since-ms 0 keeps every event with a numeric :time"
      (is (= 4 (count (trace-bus/filter-events (trace-bus/buffer)
                                               {:since-ms 0})))))
    (testing ":since-ms beyond the last :time keeps none"
      (is (= [] (trace-bus/filter-events (trace-bus/buffer)
                                         {:since-ms 9999}))))))

(deftest filter-between-axis
  (testing ":between [t0 t1] keeps events whose :time falls in the window (inclusive)"
    (seed! [(ev 1 :event :event/dispatched {})    ;; :time 1000
            (ev 2 :event :event/dispatched {})    ;; :time 2000
            (ev 3 :event :event/dispatched {})    ;; :time 3000
            (ev 4 :event :event/dispatched {})    ;; :time 4000
            (ev 5 :event :event/dispatched {})])  ;; :time 5000
    (let [win (trace-bus/filter-events (trace-bus/buffer)
                                       {:between [2000 4000]})]
      (is (= [2 3 4] (mapv :id win))
          "window [2000, 4000] inclusive keeps :id 2 3 4"))
    (testing "a window that excludes everything yields []"
      (is (= [] (trace-bus/filter-events (trace-bus/buffer)
                                         {:between [0 1]}))))
    (testing "boundary inclusivity — exactly at t0 and t1 retained"
      (let [win (trace-bus/filter-events (trace-bus/buffer)
                                         {:between [1000 5000]})]
        (is (= [1 2 3 4 5] (mapv :id win))
            "[1000, 5000] inclusive keeps every event")))))

(deftest filter-pred-axis
  (testing ":pred applies an arbitrary predicate"
    (seed! [(ev 1 :event   :event/dispatched {:event-id :ev/a})
            (ev 2 :error   :rf.error/x        {:event-id :ev/b})
            (ev 3 :sub/run :sub/run           {:event-id :ev/c})
            (ev 4 :event   :event/dispatched  {:event-id :ev/d})])
    (let [evs-or-errors (trace-bus/filter-events
                          (trace-bus/buffer)
                          {:pred (fn [e]
                                   (#{:event :error} (:op-type e)))})]
      (is (= 3 (count evs-or-errors)))
      (is (every? #(#{:event :error} (:op-type %)) evs-or-errors)))
    (testing ":pred composes with named axes"
      (let [evs (trace-bus/filter-events
                  (trace-bus/buffer)
                  {:op-type :event
                   :pred    (fn [e] (= :event/dispatched (:operation e)))})]
        (is (every? #(and (= :event           (:op-type %))
                          (= :event/dispatched (:operation %)))
                    evs))
        (is (= 2 (count evs)))))))

;; ---- AND-wise composition --------------------------------------------------

(deftest filters-compose-and-wise
  (testing "multiple filter axes combine — every key must match"
    (seed! [(ev 1 :event :event/dispatched
                {:event-id :ev/login  :origin :pair :dispatch-id 100}
                {:source :repl})
            (ev 2 :event :event/dispatched
                {:event-id :ev/login  :origin :app  :dispatch-id 101}
                {:source :ui})
            (ev 3 :event :event/db-changed
                {:event-id :ev/login  :origin :pair :dispatch-id 100}
                {:source :repl})
            (ev 4 :event :event/dispatched
                {:event-id :ev/logout :origin :app  :dispatch-id 102}
                {:source :ui})])
    (testing "four axes intersect — only the event matching all four"
      (let [hits (trace-bus/filter-events
                   (trace-bus/buffer)
                   {:op-type   :event
                    :operation :event/dispatched
                    :origin    :pair
                    :source    :repl})]
        (is (= 1 (count hits)))
        (is (= 1 (:id (first hits))))))
    (testing "axes intersect with :event-id + :dispatch-id"
      (let [hits (trace-bus/filter-events
                   (trace-bus/buffer)
                   {:event-id    :ev/login
                    :dispatch-id 100})]
        (is (= 2 (count hits)))
        (is (every? #(and (= :ev/login (get-in % [:tags :event-id]))
                          (= 100       (get-in % [:tags :dispatch-id])))
                    hits))))
    (testing "no events match all axes — empty result"
      (let [hits (trace-bus/filter-events
                   (trace-bus/buffer)
                   {:event-id :ev/login
                    :origin   :story})]
        (is (= [] hits))))))

;; ---- empty-filter degrades to all events -----------------------------------

(deftest empty-filter-returns-all-events
  (testing "no filter / empty filter / nil filter all return the buffer unchanged"
    (seed! [(ev 1 :event :event/dispatched {:event-id :ev/x})
            (ev 2 :event :event/db-changed {:event-id :ev/x})
            (ev 3 :fx    :rf.fx/handled    {:fx-id :db})])
    (let [all (trace-bus/buffer)]
      (is (= 3 (count all)) "seed populated all three events")
      (testing "single-arity (no opts) returns every event"
        (is (= all (trace-bus/filter-events all))
            "(filter-events buffer) == buffer"))
      (testing "empty-map opts returns every event"
        (is (= all (trace-bus/filter-events all {}))
            "{} opts is a no-op"))
      (testing "nil opts returns every event"
        (is (= all (trace-bus/filter-events all nil))
            "nil opts is a no-op")))))

;; ---- pre-rf2-97ah0 filters preserve their behaviour ------------------------

(deftest pre-rf2-97ah0-axes-still-work
  (testing "the four pre-existing filters keep their pre-rf2-97ah0 semantics
            even after the rf2-97ah0 vocab extension"
    (seed! [(ev 1 :event :event/dispatched {:frame :rf/causa})
            (ev 2 :event :event/db-changed {:frame :rf/causa})
            (ev 3 :fx    :rf.fx/handled    {:frame :rf/default :fx-id :db})
            (ev 4 :event :event/dispatched {:frame :rf/default})])
    (testing ":operation still narrows by operation value"
      (is (= [1 4] (mapv :id (trace-bus/filter-events
                                (trace-bus/buffer)
                                {:operation :event/dispatched})))))
    (testing ":op-type still narrows by discriminator"
      (is (= [3] (mapv :id (trace-bus/filter-events
                              (trace-bus/buffer)
                              {:op-type :fx})))))
    (testing ":since still keeps strictly-greater ids"
      (is (= [3 4] (mapv :id (trace-bus/filter-events
                                (trace-bus/buffer)
                                {:since 2})))))
    (testing ":frame still matches :tags :frame"
      (is (= [1 2] (mapv :id (trace-bus/filter-events
                                (trace-bus/buffer)
                                {:frame :rf/causa})))))
    (testing "pre-rf2-97ah0 axes still compose with one another"
      (is (= [1] (mapv :id (trace-bus/filter-events
                              (trace-bus/buffer)
                              {:operation :event/dispatched
                               :frame     :rf/causa
                               :since     0})))))
    (testing "pre-rf2-97ah0 axes compose with rf2-97ah0 axes"
      ;; Compose :frame (pre) with :pred (rf2-97ah0) — same intersection
      ;; semantics regardless of which side of rf2-97ah0 the axis came
      ;; from.
      (is (= [2] (mapv :id (trace-bus/filter-events
                              (trace-bus/buffer)
                              {:frame :rf/causa
                               :pred  #(= :event/db-changed (:operation %))})))))))

;; ---- buffer-level integration smoke ---------------------------------------
;;
;; Lightweight check that the consumer-side filter works against the
;; live Causa buffer (not just against a hand-rolled vector) — i.e.
;; (trace-bus/filter-events (trace-bus/buffer) opts) is the canonical
;; consumer call shape. The previous tests already exercise this path,
;; but this test asserts the call-shape explicitly so a future refactor
;; that breaks `(buffer)` returning a filterable collection trips a
;; failing test rather than only a panel-level regression.

(deftest buffer-plus-filter-events-is-the-canonical-call
  (testing "(filter-events (buffer) opts) is the consumer's canonical slice"
    (seed! [(ev 1 :event :event/dispatched {:dispatch-id 7 :event-id :ev/x})
            (ev 2 :fx    :rf.fx/handled    {:dispatch-id 7 :fx-id :db})
            (ev 3 :event :event/dispatched {:dispatch-id 8 :event-id :ev/y})])
    (let [cascade-7 (trace-bus/filter-events (trace-bus/buffer)
                                             {:dispatch-id 7})]
      (is (= 2 (count cascade-7))
          "live buffer slice by :dispatch-id returns the matching cascade")
      (is (every? #(= 7 (get-in % [:tags :dispatch-id])) cascade-7))
      (is (= [1 2] (mapv :id cascade-7))
          "filter preserves insertion order"))))
