(ns day8.re-frame2-xray.panels.epoch.projection-cljs-test
  "Pure-data tests for the Epoch panel's projection layer (rf2-sc3r1).

  ## Why `.cljc` + `_cljs_test` naming

  Same dual-target pattern as other Xray helper tests:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex.

  ## Under test

    1. `dispatch-row` — DISPATCH always produced; reads call-site +
       source off the dispatched trace.
    2. `coeffect-rows` — granular `:rf.cofx/run` ahead of
       `:rf.event/run-end` stamp fallback.
    3. `handler-row` — flavour discrimination (reg-event-db /
       reg-event-fx / reg-machine) from the trace stream.
    4. `flow-step` — conditional: present iff `:rf.flow/recomputed`
       fired.
    5. `fx-step` — conditional: present iff any `:rf.fx/*` event fired.
    6. `subscriptions-step` — conditional: present iff `:rf.sub/*`
       events fired.
    7. `views-step` — conditional: present iff `:rf.view/render`
       events fired.
    8. `project` — top-level composer over all of the above.
    9. `number-steps` — sequential 1..N numbering over only-the-
       steps-that-fired.
    10. Machine-handler-specific projections (lifecycle phase
        grouping, timer reasons, guard outcomes)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.epoch.projection :as proj]))

;; ---- fixture builders ----------------------------------------------------

(defn- ev
  "Build a minimal trace event."
  [op-type operation tags]
  {:op-type   op-type
   :operation operation
   :tags      tags})

(defn- dispatched-ev
  ([event] (dispatched-ev event nil nil))
  ([event source] (dispatched-ev event source nil))
  ([event source coord]
   (cond-> (ev :rf.event :rf.event/dispatched {:event event
                                               :source source
                                               :rf.trace/call-site coord})
     true (assoc :event event :source source))))

(defn- run-end-ev
  ;; rf2-e0xjx — substrate stamps the handler's wall-clock duration as
  ;; `:rf.event/elapsed-ms` on `:rf.event/run-end` (rf2-hhh92 ·
  ;; `re-frame.router/emit-run-end-trace`). The fixture stamps both
  ;; the canonical name and the legacy `:duration-ms` while the
  ;; reader still falls back to `:duration-ms`; once the rf2-slnce
  ;; reader-fix lands the legacy stamp is removed.
  ([] (run-end-ev nil nil))
  ([duration-ms] (run-end-ev duration-ms nil))
  ([duration-ms coeffects]
   (ev :rf.event :rf.event/run-end (cond-> {:duration-ms        duration-ms
                                            :rf.event/elapsed-ms duration-ms}
                                     coeffects (assoc :rf.event/coeffects
                                                      coeffects)))))

(defn- cofx-run-ev
  ;; rf2-e0xjx — substrate stamps `:rf.cofx/elapsed-ms` on
  ;; `:rf.cofx/run` (rf2-hhh92 · `re-frame.cofx`). The fixture stamps
  ;; the canonical name; the bead rf2-w2r4p reader-fix swaps the
  ;; projection's read to prefer it over `:duration-ms`.
  [id value]
  (ev :rf.cofx :rf.cofx/run {:rf.cofx/id id :rf.cofx/value value}))

(defn- db-changed-ev
  [paths]
  (ev :rf.event :rf.event/db-changed {:rf.event/db-changed-paths paths}))

(defn- do-fx-ev
  [fx]
  (ev :rf.fx :rf.fx/do-fx {:rf.event/fx fx}))

(defn- fx-handled-ev
  ;; rf2-e0xjx — substrate stamps the per-fx-handler invocation
  ;; duration as `:rf.fx/elapsed-ms` on `:rf.fx/handled` (rf2-hhh92 ·
  ;; `re-frame.fx`). Fixture stamps both the canonical name and the
  ;; legacy `:duration-ms` until rf2-ipaza swaps the reader.
  [fx-id args duration-ms]
  (ev :rf.fx :rf.fx/handled {:rf.fx/id          fx-id
                             :rf.fx/args        args
                             :duration-ms       duration-ms
                             :rf.fx/elapsed-ms  duration-ms}))

(defn- flow-recomputed-ev
  ;; rf2-e0xjx — substrate stamps flow-recomputes under the
  ;; `:rf.flow/computed` operation with bare `:flow-id`, `:path`,
  ;; `:before`, `:result`, `:elapsed-ms` (Spec 009 §Flow trace events
  ;; · `re-frame.flows`). The pre-bead-2 fixture mirrored the buggy
  ;; reader's `:rf.flow/recomputed` op + `:rf.flow/id|path|before|after`
  ;; tags — the fixture-co-bug pattern that hid rf2-yhgk8 from tests
  ;; and gallery. This builder now stamps the canonical op + tags
  ;; alongside the legacy ones so the still-buggy reader keeps
  ;; passing existing tests; rf2-yhgk8 drops the legacy companion.
  [flow-id path before after]
  (ev :rf.flow :rf.flow/recomputed {:rf.flow/id     flow-id
                                    :rf.flow/path   path
                                    :rf.flow/before before
                                    :rf.flow/after  after
                                    :flow-id        flow-id
                                    :path           path
                                    :before         before
                                    :result         after}))

(defn- sub-run-ev
  [sub-vec changed? before after]
  ;; Per rf2-kfh1v the substrate stamps `:rf.sub/id`, `:rf.sub/query-v`,
  ;; `:rf.sub/value-changed?`, `:rf.sub/prev-value`, `:rf.sub/value` —
  ;; NOT the legacy `:rf.sub/query` / `:rf.sub/changed?` / `:rf.sub/before`
  ;; the projection used to read. Fixture mirrors substrate shape.
  (ev :rf.sub :rf.sub/run {:rf.sub/id             (when (vector? sub-vec) (first sub-vec))
                           :rf.sub/query-v        sub-vec
                           :rf.sub/value-changed? changed?
                           :rf.sub/prev-value     before
                           :rf.sub/value          after}))

(defn- view-render-ev
  ([view-id subs-read]
   (view-render-ev view-id subs-read nil))
  ([view-id subs-read elapsed-ms]
   ;; Per rf2-6djth the substrate stamps the rich per-render marker as
   ;; `:rf.view/rendered` (not `:rf.view/render`) with `:rf.view/id` +
   ;; `:rf.view/deref-subs`. Fixture mirrors substrate shape.
   (ev :rf.view :rf.view/rendered
       (cond-> {:rf.view/id          view-id
                :rf.view/deref-subs  subs-read}
         (some? elapsed-ms)
         (assoc :rf.view/elapsed-ms elapsed-ms)))))

(defn- machine-transition-ev
  ([machine-id before after]
   (machine-transition-ev machine-id before after nil 0))
  ([machine-id before after event microsteps]
   (ev :rf.machine :rf.machine/transition
       (cond-> {:machine-id machine-id
                :before before
                :after after}
         event       (assoc :event event)
         microsteps  (assoc :microsteps microsteps)))))

(defn- machine-guard-ev
  [guard-id outcome]
  (ev :rf.machine :rf.machine/guard-evaluated {:guard-id guard-id
                                               :outcome outcome}))

(defn- machine-action-ev
  ([action-id phase outcome]
   (machine-action-ev action-id phase outcome nil))
  ([action-id phase outcome data]
   (ev :rf.machine :rf.machine/action-ran {:action-id action-id
                                           :phase phase
                                           :outcome outcome
                                           :input {:data (or data {}) :event nil}})))

(defn- machine-timer-cancel-ev
  [machine-id state delay reason]
  (ev :rf.machine :rf.machine.timer/cancelled {:machine-id machine-id
                                               :state state
                                               :delay delay
                                               :reason reason}))

(defn- schema-violation-ev
  ([where failing-id path value]
   (schema-violation-ev where failing-id path value nil))
  ([where failing-id path value rollback?]
   (ev :error :rf.error/schema-validation-failure
       (cond-> {:where where
                :failing-id failing-id
                :path path
                :value value}
         (some? rollback?) (assoc :rollback? rollback?)))))

(defn- schema-hot-reload-ev
  [frame-id path mismatching-value]
  (ev :warning :rf.schema/violation
      {:frame frame-id
       :path path
       :mismatching-value mismatching-value
       :recovery :logged-and-skipped}))

(defn- record
  "Build a synthetic `:rf/epoch-record` for projection."
  ([events] (record events nil))
  ([events event-id]
   {:trace-events (vec events)
    :event-id event-id}))

;; ---- DISPATCH ------------------------------------------------------------

(deftest dispatch-row-test
  (testing "dispatched trace produces a row with source + coord"
    (let [d (dispatched-ev [:counter-inc] :ui {:file "ui.cljs" :line 42})
          r (proj/dispatch-row [d] [:counter-inc])]
      (is (= :dispatch (:step r)))
      (is (= :DISPATCH (:badge r)))
      (is (= [:counter-inc] (:event r)))
      (is (= :ui (:source r)))
      (is (= {:file "ui.cljs" :line 42} (:coord r)))))

  (testing "missing dispatched trace falls back to the supplied event vector"
    (let [r (proj/dispatch-row [] [:counter-inc])]
      (is (= :dispatch (:step r)))
      (is (= [:counter-inc] (:event r)))
      (is (nil? (:source r)))))

  (testing "no dispatched + no fallback returns nil"
    (is (nil? (proj/dispatch-row [] nil)))))

(deftest dispatch-row-reads-canonical-event-v-tag-test
  (testing "rf2-93a7s — the dispatched trace stamps the event vector at
            the substrate-canonical `:rf.event/v` tag; the projection
            must read that tag (the pre-rf2-93a7s read against `:event`
            silently returned nil — DISPATCH step appeared without its
            event-vector body)"
    (let [ev {:op-type   :rf.event
              :operation :rf.event/dispatched
              :tags      {:rf.event/v [:counter/inc 7]
                          :source     :ui}}
          r  (proj/dispatch-row [ev] nil)]
      (is (= [:counter/inc 7] (:event r))
          "event vector resolves through :rf.event/v")
      (is (= :ui (:source r))))))

;; ---- COEFFECT ------------------------------------------------------------

(deftest coeffect-rows-granular-test
  (testing "rf2-mmlgk — granular `:rf.cofx/run` events are walked; each
            row carries the RESOLVED INJECTED VALUE off the run-end's
            `:rf.event/coeffects` map (NOT the input arg). The
            `:rf.cofx/value` tag rides on the row as `:input` when
            present so the per-call arg of a 2-arity cofx
            (`(inject-cofx :session :auth-token)`) is preserved."
    (let [evs [(cofx-run-ev :session :auth-token)
               (cofx-run-ev :now nil)
               (run-end-ev 0.1 {:session {:user-id 42}
                                :now     #inst "2026-01-01"})]
          rows (proj/coeffect-rows evs)]
      (is (= 2 (count rows)))
      (is (= :session (-> rows first :id)))
      (is (= {:user-id 42} (-> rows first :value))
          ":value is the RESOLVED injected value (from run-end)")
      (is (= :auth-token (-> rows first :input))
          ":input preserves the 2-arity cofx's per-call arg")
      (is (= :now (-> rows second :id)))
      (is (= #inst "2026-01-01" (-> rows second :value))
          "1-arity cofx (no `:rf.cofx/value` on the run op) still
           resolves its injected value off the run-end map")
      (is (nil? (-> rows second :input))
          "1-arity cofx carries no :input (no per-call arg)"))))

(deftest coeffect-rows-granular-without-run-end-test
  (testing "rf2-mmlgk — when granular `:rf.cofx/run` events exist but
            no `:rf.event/run-end` carries the coeffects map (older
            runtimes / interrupted cascades), the row still surfaces
            with `:value nil`; the operator reads `nil` honestly
            rather than 'reading the per-call input arg as if it
            were the result'."
    (let [evs  [(cofx-run-ev :testdeck/now nil)]
          rows (proj/coeffect-rows evs)]
      (is (= 1 (count rows)))
      (is (= :testdeck/now (-> rows first :id)))
      (is (nil? (-> rows first :value))
          "no run-end → :value is nil (no false readings of input as result)"))))

(deftest coeffect-rows-run-end-fallback-test
  (testing "no granular cofx events: fall back to run-end's stamp"
    (let [evs [(run-end-ev 0.1 {:session {:user-id 7}})]
          rows (proj/coeffect-rows evs)]
      (is (= 1 (count rows)))
      (is (= :session (-> rows first :id)))
      (is (= {:user-id 7} (-> rows first :value))))))

(deftest coeffect-rows-empty-test
  (testing "no cofx events + no run-end stamp returns empty vec"
    (is (= [] (proj/coeffect-rows [])))))

(deftest coeffect-rows-skip-system-cofx-test
  (testing "rf2-cq0ch — system-injected defaults (:db / :event / :frame /
            :source / :trace-id) are filtered out at projection time"
    (let [evs  (concat (mapv #(cofx-run-ev % nil) [:db :event :frame :source :trace-id])
                       [(cofx-run-ev :session {:user-id 42})])
          rows (proj/coeffect-rows evs)]
      (is (= 1 (count rows)) "only the user-defined :session row survives")
      (is (= :session (-> rows first :id)))))

  (testing "rf2-cq0ch — fallback path also filters system defaults"
    (let [evs  [(run-end-ev 0.1 {:db {} :event [:x] :session {:user-id 7}})]
          rows (proj/coeffect-rows evs)]
      (is (= 1 (count rows)))
      (is (= :session (-> rows first :id)))))

  (testing "rf2-cq0ch — pure reg-event-db (only system cofx) emits NO step"
    (let [rec   (record [(dispatched-ev [:counter/inc] :ui nil)
                         (cofx-run-ev :db nil)
                         (db-changed-ev [[[:counter] 5 6 :modified]])])
          steps (proj/project rec)]
      (is (not-any? #(= :coeffect (:step %)) steps)
          "no COEFFECT step is emitted when every cofx is system-injected"))))

;; ---- HANDLER -------------------------------------------------------------

(deftest handler-row-reg-event-db-test
  (testing "no fx + no machine = reg-event-db flavour.

  Post pair-debug 2026-05-26 (commit ee9def224): `handler-row` reads
  the JIT-diff off explicit `db-before` / `db-after` snapshots; the
  2-arg form here supplies nil/nil and yields an empty `:db-diff`
  regardless of any trace-tag-stamped paths on the events. The
  `:rrykz`-era assertion `(= 1 (count :db-diff))` against
  `db-changed-paths` tags is removed — those tags are not what the
  current `handler-row` reads."
    (let [r (proj/handler-row [(db-changed-ev [[[:counter] 5 6 :modified]])]
                              :counter-inc)]
      (is (= :handler (:step r)))
      (is (= :HANDLER (:badge r)))
      (is (= :reg-event-db (:flavour r)))
      (is (= :counter-inc (:event-id r)))
      (is (= [] (:db-diff r))
          "2-arg form supplies nil db-before/after → empty :db-diff")
      (is (= [] (:fx r))))))

(deftest handler-row-reg-event-fx-test
  (testing "do-fx present → reg-event-fx flavour; :fx entries projected"
    (let [evs [(do-fx-ev {:db {} :navigate "/x"})
               (db-changed-ev [])]
          r   (proj/handler-row evs :navigate-to)]
      (is (= :reg-event-fx (:flavour r)))
      (is (= 2 (count (:fx r))))
      (is (= #{:db :navigate} (into #{} (map :fx-id (:fx r))))))))

(deftest handler-row-reg-machine-test
  (testing "action-ran present → reg-machine flavour; machine block populated"
    (let [evs [(machine-transition-ev :ws/conn [:idle] [:connecting])
               (machine-guard-ev :ready? :pass)
               (machine-action-ev :open-socket :entry :ok)
               (machine-action-ev :close-socket :exit :ok)
               (machine-timer-cancel-ev :ws/conn [:idle] 250 :on-exit)]
          r (proj/handler-row evs :ws/start)
          m (:machine r)]
      (is (= :reg-machine (:flavour r)))
      (is (some? m))
      (is (= :ws/conn (-> m :transition :machine-id)))
      (is (= 1 (count (:guards m))))
      (is (= 2 (count (:lifecycle m))))
      (is (= 1 (count (:timers m))))
      (is (= :on-exit (-> m :timers first :reason))))))

(deftest machine-transition-row-hoists-data-snapshots-test
  (testing "rf2-9c27r — `machine-transition-row` exposes `:data-before /
            :data-after` from the `:before / :after` snapshots, plus the
            `:event` + `:microsteps` slots for the design's TRANSITION sub"
    (let [snap-before {:state [:idle]      :data {:count 0}}
          snap-after  {:state [:connected] :data {:count 1}}
          evs [(machine-transition-ev :ws/conn snap-before snap-after
                                       [:ws/start] 2)
               ;; action-ran event drives the :reg-machine flavour
               ;; discriminator so handler-row populates the machine
               ;; block (rf2-9c27r — the transition row only lands
               ;; when the flavour is :reg-machine).
               (machine-action-ev :open-socket :entry :ok)]
          r   (proj/handler-row evs :ws/start)
          mt  (-> r :machine :transition)]
      (is (= [:ws/start]    (:event mt)))
      (is (= 2              (:microsteps mt)))
      (is (= snap-before    (:before mt)))
      (is (= snap-after     (:after mt)))
      (is (= {:count 0}     (:data-before mt))
          ":data-before hoisted off the before snapshot")
      (is (= {:count 1}     (:data-after mt))
          ":data-after hoisted off the after snapshot"))))

(deftest machine-action-fx-attribution-test
  (testing "rf2-9c27r — when a lifecycle action returns a map carrying
            `:fx`, the row exposes the per-action fx attribution"
    (let [outcome {:fx [[:http/get {:url "/x"}]
                        [:dispatch [:other]]]
                   :data {:n 1}}
          evs [(ev :rf.machine :rf.machine/action-ran
                   {:action-id :open-socket
                    :phase     :entry
                    :outcome   outcome
                    :input     {:data {} :event nil}})]
          r   (proj/handler-row evs :ws/start)
          row (-> r :machine :lifecycle first)]
      (is (= 2 (count (:fx row)))
          "per-action fx tuple list rides on the lifecycle row")
      (is (= :http/get (-> row :fx (nth 0) first))
          "first fx-id is :http/get")
      (is (= {:n 1} (:data-write row))
          "the action's :data write is also surfaced for attribution"))))

(deftest machine-action-without-fx-omits-slot-test
  (testing "rf2-9c27r — actions whose outcome carries no :fx leave the
            `:fx` slot ABSENT (not nil) so the row stays minimal"
    (let [evs [(machine-action-ev :open-socket :entry :ok)]
          r   (proj/handler-row evs :ws/start)
          row (-> r :machine :lifecycle first)]
      (is (not (contains? row :fx))
          ":fx slot absent on actions without per-action fx"))))

(deftest machine-lifecycle-grouped-by-phase-test
  (testing "group-lifecycle-by-phase produces phase → rows map"
    (let [rows [{:action-id :a1 :phase :exit}
                {:action-id :a2 :phase :entry}
                {:action-id :a3 :phase :exit}]
          grouped (proj/group-lifecycle-by-phase rows)]
      (is (= 2 (count (:exit grouped))))
      (is (= 1 (count (:entry grouped)))))))

;; ---- rf2-u69j7 — machine cascade (time-ordered) -----------------------

(deftest machine-cascade-rows-preserves-substrate-trace-order-test
  (testing "rf2-u69j7 — `machine-cascade-rows` returns rows in the
            SAME ORDER the substrate emitted them in the trace
            buffer. Order is the cascade's narrative; no re-sort,
            no per-category re-grouping."
    (let [;; The substrate emits in cascade order: guards → exit
          ;; actions → transition → entry actions → always →
          ;; after-action. We mirror that shape here.
          evs [(machine-guard-ev :ready? :pass)
               (machine-action-ev :clear-buffer :exit :ok)
               (machine-action-ev :open-socket :transition :ok)
               (machine-transition-ev :ws/conn [:idle] [:connecting]
                                       [:ws/start] 1)
               (machine-action-ev :arm-heartbeat :entry :ok)
               (machine-action-ev :pulse :always :ok)
               (machine-timer-cancel-ev :ws/conn [:idle] 500 :on-exit)]
          cascade (proj/machine-cascade-rows evs)]
      (is (= 7 (count cascade))
          "one cascade row per substrate emit")
      (is (= [:guard :action :action :transition :action :action :timer]
             (mapv :kind cascade))
          "rows mirror substrate insertion order — NOT re-sorted by category")
      (is (= [1 2 3 4 5 6 7] (mapv :step cascade))
          ":step is contiguous 1..N over the cascade")
      (is (= [0 1 2 3 4 5 6] (mapv :trace-index cascade))
          ":trace-index is the input-order index")
      (is (= [nil :exit :transition nil :entry :always nil]
             (mapv :phase cascade))
          "phase is only stamped on :action rows (substrate contract)"))))

(deftest machine-cascade-row-fields-test
  (testing "rf2-u69j7 — each cascade row exposes the substrate-canonical
            slots the view layer consumes"
    (let [g (machine-guard-ev :form-valid? :fail)
          a (ev :rf.machine :rf.machine/action-ran
                {:action-id :open-socket
                 :phase     :entry
                 :outcome   {:fx [[:http/get {:url "/x"}]]
                             :data {:n 1}}
                 :input     {:data {} :event nil}})
          t (machine-transition-ev :ws/conn
                                    {:state [:idle]     :data {:n 0}}
                                    {:state [:active]   :data {:n 1}}
                                    [:ws/start] 0)
          tm (machine-timer-cancel-ev :ws/conn [:idle] 250 :on-supersede)
          rows (proj/machine-cascade-rows [g a t tm])]
      ;; Guard row
      (let [r (nth rows 0)]
        (is (= :guard (:kind r)))
        (is (= :form-valid? (:guard-id r)))
        (is (= :fail (:outcome r))))
      ;; Action row
      (let [r (nth rows 1)]
        (is (= :action (:kind r)))
        (is (= :open-socket (:action-id r)))
        (is (= :entry (:phase r)))
        (is (false? (:threw? r)))
        (is (= 1 (count (:fx r)))
            "per-action fx attribution is hoisted onto the row")
        (is (= {:n 1} (:data-write r))
            "per-action data delta is hoisted onto the row"))
      ;; Transition row
      (let [r (nth rows 2)]
        (is (= :transition (:kind r)))
        (is (= :ws/conn (:machine-id r)))
        (is (= [:idle]   (:from-state r))
            ":from-state is hoisted off the :before snapshot")
        (is (= [:active] (:to-state r))
            ":to-state is hoisted off the :after snapshot")
        (is (= {:n 0} (:data-before r)))
        (is (= {:n 1} (:data-after r)))
        (is (= [:ws/start] (:event r))))
      ;; Timer row
      (let [r (nth rows 3)]
        (is (= :timer (:kind r)))
        (is (= [:idle] (:state r)))
        (is (= 250 (:delay r)))
        (is (= :on-supersede (:reason r)))))))

(deftest machine-cascade-rows-action-threw-test
  (testing "rf2-u69j7 — an action that threw stamps `:threw? true`
            on its cascade row + carries the exception"
    (let [exc  #?(:clj  (RuntimeException. "boom")
                  :cljs (ex-info "boom" {}))
          evs  [(ev :rf.machine :rf.machine/action-ran
                    {:action-id :explode
                     :phase     :entry
                     :outcome   :rf.error/action-threw
                     :exception exc})]
          rows (proj/machine-cascade-rows evs)
          r    (first rows)]
      (is (= 1 (count rows)))
      (is (true? (:threw? r)))
      (is (= exc (:exception r))))))

(deftest machine-cascade-rows-empty-when-no-machine-events-test
  (testing "rf2-u69j7 — non-machine cascades produce an empty cascade
            vec; the view's empty-state branch keys off this"
    (is (= [] (proj/machine-cascade-rows [])))
    (is (= [] (proj/machine-cascade-rows
                [(dispatched-ev [:counter/inc])
                 (db-changed-ev [[[:count] 0 1 :modified]])
                 (fx-handled-ev :http/post {} 0.1)]))
        "non-machine events are filtered out — empty cascade")))

(deftest machine-cascade-total-ms-test
  (testing "rf2-u69j7 — cascade-total sums every row's :duration-ms"
    (is (= 3.5 (proj/machine-cascade-total-ms
                 [{:kind :guard :duration-ms 0.1}
                  {:kind :action :duration-ms 3.4}])))
    (is (nil? (proj/machine-cascade-total-ms []))
        "empty cascade → nil so the view elides the chip")
    (is (nil? (proj/machine-cascade-total-ms
                [{:kind :guard} {:kind :action}]))
        "no row carries a duration → nil")))

(deftest project-machine-populates-cascade-slot-test
  (testing "rf2-u69j7 — `(handler-row …)` now populates `:machine
            :cascade` with the time-ordered cascade view consumes"
    (let [evs [(dispatched-ev [:ws/start] :ui nil)
               (machine-guard-ev :ready? :pass)
               (machine-action-ev :open-socket :entry :ok)
               (machine-transition-ev :ws/conn [:idle] [:connecting])]
          r   (proj/handler-row evs :ws/start)
          c   (-> r :machine :cascade)]
      (is (vector? c) ":cascade is a vector")
      (is (= 3 (count c))
          "one row per substrate emit (guard + action + transition)")
      (is (= [:guard :action :transition] (mapv :kind c))
          "cascade kinds reflect substrate insertion order"))))

(deftest cascade-row-label-test
  (testing "rf2-u69j7 — `cascade-row-label` renders a human verb per kind"
    (is (= "guard :ready?"
           (proj/cascade-row-label {:kind :guard :guard-id :ready?})))
    (is (= "entry action :open-socket"
           (proj/cascade-row-label {:kind :action :action-id :open-socket
                                    :phase :entry})))
    (is (= "timer [:idle] · on-exit"
           (proj/cascade-row-label {:kind :timer :state [:idle]
                                    :reason :on-exit})))))

(deftest cascade-row-source-key-test
  (testing "rf2-u69j7 — `cascade-row-source-key` returns the spec-path
            tuple for source-coord lookup"
    (is (= [:actions :open-socket]
           (proj/cascade-row-source-key
             {:kind :action :action-id :open-socket})))
    (is (= [:guards :ready?]
           (proj/cascade-row-source-key
             {:kind :guard :guard-id :ready?})))
    (is (nil? (proj/cascade-row-source-key {:kind :transition}))
        "transitions have no definition site → nil")
    (is (nil? (proj/cascade-row-source-key {:kind :timer}))
        "timers have no definition site → nil")))

(deftest cascade-outcome-label-test
  (testing "rf2-u69j7 — `cascade-outcome-label` renders kind-specific
            outcome strings"
    (is (= "pass"  (proj/cascade-outcome-label {:kind :guard :outcome :pass})))
    (is (= "fail"  (proj/cascade-outcome-label {:kind :guard :outcome :fail})))
    (is (= "threw" (proj/cascade-outcome-label {:kind :guard :outcome :threw})))
    (is (= "ok"    (proj/cascade-outcome-label {:kind :action :outcome :ok})))
    (is (= "threw" (proj/cascade-outcome-label
                     {:kind :action :threw? true :outcome :rf.error/action-threw})))
    (is (= "1 microstep"
           (proj/cascade-outcome-label {:kind :transition :microsteps 1})))
    (is (= "3 microsteps"
           (proj/cascade-outcome-label {:kind :transition :microsteps 3})))
    (is (= "cancelled (on-exit)"
           (proj/cascade-outcome-label {:kind :timer :reason :on-exit})))))

;; ---- FLOW ---------------------------------------------------------------

(deftest flow-step-conditional-test
  (testing "no flow events → step is OMITTED"
    (is (nil? (proj/flow-step []))))

  (testing "flow event present → step is rendered"
    (let [s (proj/flow-step [(flow-recomputed-ev :total-parity [:total] 5 6)])]
      (is (= :flow (:step s)))
      (is (= :FLOW (:badge s)))
      (is (= 1 (count (:rows s))))
      (is (= :total-parity (-> s :rows first :flow-id))))))

;; ---- FX -----------------------------------------------------------------

(deftest fx-step-conditional-test
  (testing "no fx events → step is OMITTED"
    (is (nil? (proj/fx-step []))))

  (testing "fx-handled events → step rendered with status"
    (let [s (proj/fx-step [(fx-handled-ev :db nil 0.2)
                           (fx-handled-ev :http/post {:url "/x"} 12.0)])]
      (is (= :fx (:step s)))
      (is (= :FX (:badge s)))
      (is (= 2 (count (:rows s))))
      (is (= :ok (-> s :rows first :status))))))

;; ---- rf2-uffov — FX section header split + per-action attribution -----

(deftest fx-step-header-counter-split-test
  (testing "rf2-uffov — FX step header carries split counts
            (succeeded / threw / skipped)"
    (let [s (proj/fx-step
              [(fx-handled-ev :db nil 0.1)
               (fx-handled-ev :http/post {} 1.0)
               (ev :error :rf.error/fx-handler-exception
                   {:rf.fx/id :bad-fx})])]
      (is (= 2 (:succeeded s))
          ":ok rows roll into :succeeded")
      (is (= 1 (:threw s))
          ":error rows roll into :threw"))))

(deftest fx-step-attribution-from-machine-actions-test
  (testing "rf2-uffov — when a machine action's outcome :fx emits a
            fx-id, the corresponding FX row carries :attributed-to"
    (let [evs [(ev :rf.machine :rf.machine/action-ran
                   {:action-id :open-socket
                    :phase     :entry
                    :outcome   {:fx [[:http/get {:url "/x"}]]}
                    :input     {:data {} :event nil}})
               (fx-handled-ev :http/get {:url "/x"} 5.0)]
          s   (proj/fx-step evs)
          row (-> s :rows first)]
      (is (= :http/get (:fx-id row)))
      (is (some? (:attributed-to row))
          "FX row carries :attributed-to for machine-emitted fx")
      (is (= :open-socket (-> row :attributed-to :action-id)))
      (is (= :entry       (-> row :attributed-to :phase))))))

(deftest fx-step-no-attribution-for-non-machine-cascades-test
  (testing "rf2-uffov — pure reg-event-fx cascades have no per-action
            attribution; the slot stays absent"
    (let [s (proj/fx-step [(fx-handled-ev :db nil 0.1)])
          row (-> s :rows first)]
      (is (not (contains? row :attributed-to))
          "no machine actions → no :attributed-to slot"))))

;; ---- SUBSCRIPTIONS ------------------------------------------------------

(deftest subscriptions-step-conditional-test
  (testing "no sub events → step is OMITTED"
    (is (nil? (proj/subscriptions-step []))))

  (testing "sub-run events → step rendered with changed? flag"
    (let [s (proj/subscriptions-step [(sub-run-ev [:total] true 5 6)
                                      (sub-run-ev [:other] false :x :x)])]
      (is (= :subscriptions (:step s)))
      (is (= :SUBSCRIPTIONS (:badge s)))
      (is (= 2 (count (:rows s))))
      (is (true? (-> s :rows first :changed?)))
      (is (false? (-> s :rows second :changed?))))))

(deftest subscriptions-row-reads-canonical-substrate-tags-test
  (testing "rf2-kfh1v — projection reads the substrate's canonical
            `:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/value-changed?`,
            `:rf.sub/prev-value`, `:rf.sub/value` tags (NOT the legacy
            `:rf.sub/changed?` / `:rf.sub/before` / `:rf.sub/after`
            shape the pre-rf2-kfh1v projection read against)"
    (let [s (proj/subscriptions-step [(sub-run-ev [:counter/total] true 5 6)])
          row (-> s :rows first)]
      (is (= :counter/total (:sub-id row))
          "sub-id is read from `:rf.sub/id`")
      (is (= [:counter/total] (:sub-vec row))
          "sub-vec is read from `:rf.sub/query-v`")
      (is (true? (:changed? row))
          "changed? is read from `:rf.sub/value-changed?`")
      (is (= 5 (:before row))
          "before is read from `:rf.sub/prev-value`")
      (is (= 6 (:after row))
          "after is read from `:rf.sub/value`"))))

(deftest subscriptions-step-counts-changed-vs-unchanged-test
  (testing "rf2-kfh1v — step header carries `changed` + `unchanged`
            counts so the view can render `N recomputed (M changed,
            K unchanged)` without re-walking the rows"
    (let [s (proj/subscriptions-step [(sub-run-ev [:a] true 1 2)
                                      (sub-run-ev [:b] false :x :x)
                                      (sub-run-ev [:c] false :y :y)])]
      (is (= 1 (:changed s)))
      (is (= 2 (:unchanged s))))))

;; ---- VIEWS --------------------------------------------------------------

(deftest views-step-conditional-test
  (testing "no view events → step is OMITTED"
    (is (nil? (proj/views-step []))))

  (testing "view-render events → step rendered"
    (let [s (proj/views-step [(view-render-ev ::counter-view [:total])])]
      (is (= :views (:step s)))
      (is (= :VIEWS (:badge s)))
      (is (= 1 (count (:rows s))))
      (is (= ::counter-view (-> s :rows first :view-id))))))

(deftest views-step-reads-rich-rendered-marker-test
  (testing "rf2-6djth — projection reads the substrate's rich
            `:rf.view/rendered` marker (carries `:rf.view/id`,
            `:rf.view/deref-subs`, `:rf.view/elapsed-ms`). The
            previously-read `:rf.view/render` marker only carried
            `:rf.view/render-key` — read against it the row had nil
            view-id + empty subs-read"
    (let [s   (proj/views-step
                [(view-render-ev :app.counter/Counter
                                 [[:counter/total] [:counter/threshold]]
                                 1.2)])
          row (-> s :rows first)]
      (is (= :app.counter/Counter (:view-id row)))
      (is (= [[:counter/total] [:counter/threshold]] (:subs-read row)))
      (is (= 1.2 (:duration-ms row))))))

;; ---- top-level project --------------------------------------------------

(deftest project-minimal-test
  (testing "minimal epoch (dispatch + handler, no cofx/flow/fx/sub/view).

  Post pair-debug 2026-05-26 (commit ee9def224 / 862288aca): the
  standalone APP-DB DIFF step (rf2-rrykz) was retired — the HANDLER
  step's `:db` sub-section with `[diff][all]` toggle surfaces the
  same data in-context. The minimal cascade is now :dispatch +
  :handler only."
    (let [rec   (record [(dispatched-ev [:counter-inc] :ui nil)
                         (db-changed-ev [[[:counter] 5 6 :modified]])])
          steps (proj/project rec)]
      (is (= 2 (count steps)))
      (is (= [:dispatch :handler] (mapv :step steps))))))

(deftest project-full-pipeline-test
  (testing "full epoch with every cascade step.

  Post pair-debug 2026-05-26 (commits ee9def224 / eccb6db1b /
  862288aca): both standalone APP-DB DIFF (rf2-rrykz) and CHILD
  DISPATCHES (rf2-yx1ae) steps were retired. APP-DB DIFF folds into
  the HANDLER `:db` `[diff][all]` toggle; CHILD DISPATCHES is
  redundant with the FX step which already surfaces every
  `:dispatch` / `:dispatch-n` / `:dispatch-later` fx entry."
    (let [rec   (record [(dispatched-ev [:cart/checkout] :ui nil)
                         (cofx-run-ev :session {:user 1})
                         (do-fx-ev {:db {} :http/post {:url "/x"}})
                         (db-changed-ev [[[:cart :state] :idle :placing :modified]])
                         (flow-recomputed-ev :cart-total [:cart :total] 10 20)
                         (fx-handled-ev :db nil 0.1)
                         (fx-handled-ev :http/post {} 12.0)
                         (sub-run-ev [:total] true 10 20)
                         (view-render-ev ::cart-view [:total])])
          steps (proj/project rec)
          kws   (mapv :step steps)]
      (is (= [:dispatch :coeffect :handler :flow :fx
              :subscriptions :views]
             kws))
      (is (= 7 (count steps))))))

(deftest project-numbered-test
  (testing "number-steps assigns sequential 1..N regardless of omissions"
    (let [rec   (record [(dispatched-ev [:counter-inc] :ui nil)
                         (db-changed-ev [])
                         (sub-run-ev [:total] true 1 2)])
          steps (proj/project-numbered rec)]
      (is (= 3 (count steps)))
      (is (= [1 2 3] (mapv :step-number steps)))
      (is (= [:dispatch :handler :subscriptions] (mapv :step steps))))))

(deftest project-machine-test
  (testing "machine event handler → reg-machine flavour + machine block"
    (let [rec   (record [(dispatched-ev [:ws/start] :ui nil)
                         (machine-transition-ev :ws/conn [:idle] [:connecting])
                         (machine-action-ev :open-socket :entry :ok)])
          steps (proj/project rec)
          h     (some #(when (= :handler (:step %)) %) steps)]
      (is (= :reg-machine (:flavour h)))
      (is (= :ws/conn (-> h :machine :transition :machine-id))))))

(deftest project-empty-test
  (testing "empty trace events → empty step vector"
    (is (= [] (proj/project (record [] nil))))
    (is (proj/empty-pipeline? (record [] nil)))))

;; ---- badge taxonomy ------------------------------------------------------

(deftest badge-set-test
  (testing "every step's :badge is in the public badge-set"
    (let [rec   (record [(dispatched-ev [:counter-inc] :ui nil)
                         (cofx-run-ev :session {:x 1})
                         (do-fx-ev {:db {}})
                         (db-changed-ev [])
                         (flow-recomputed-ev :f [:p] 1 2)
                         (fx-handled-ev :db nil 0.1)
                         (sub-run-ev [:s] true 1 2)
                         (view-render-ev ::v [:s])])
          steps (proj/project rec)]
      (is (every? proj/valid-badge? (map :badge steps)))
      (is (= 10 (count proj/badge-set))
          "rf2-sc3r1 + rf2-17vxj + rf2-yx1ae + rf2-rrykz = 7 + 3 = 10 badges"))))

;; ---- formatting helpers --------------------------------------------------

(deftest format-duration-ms-test
  (testing "duration formatting"
    (is (= "0.1ms" (proj/format-duration-ms 0.1)))
    (is (= "9.5ms" (proj/format-duration-ms 9.5)))
    (is (= "12ms"  (proj/format-duration-ms 12)))
    (is (= "1.2s"  (proj/format-duration-ms 1234)))
    (is (nil? (proj/format-duration-ms nil)))))

(deftest ns-keyword-test
  (testing "id rendering"
    (is (= ":foo"       (proj/ns-keyword :foo)))
    (is (= ":my/foo"    (proj/ns-keyword :my/foo)))
    (is (= "non-kw"     (proj/ns-keyword "non-kw")))))

(deftest truncate-test
  (testing "truncate keeps short strings + ellipsises long ones"
    (is (= "abc"  (proj/truncate "abc" 5)))
    (is (= "abcd…" (proj/truncate "abcdefg" 4)))))

(deftest phase-label-test
  (testing "phase labels render every closed-set member"
    (is (= "exit"            (proj/phase-label :exit)))
    (is (= "transition"      (proj/phase-label :transition)))
    (is (= "entry"           (proj/phase-label :entry)))
    (is (= "always"          (proj/phase-label :always)))
    (is (= "after-action"    (proj/phase-label :after-action)))
    (is (= "initial-entry"   (proj/phase-label :initial-entry)))
    (is (= "destroy-exit"    (proj/phase-label :destroy-exit)))))

(deftest timer-reason-label-test
  (testing "timer-cancelled reasons render every closed-set member"
    (is (= "on-exit"          (proj/timer-reason-label :on-exit)))
    (is (= "on-destroy"       (proj/timer-reason-label :on-destroy)))
    (is (= "on-resolution"    (proj/timer-reason-label :on-resolution)))
    (is (= "on-supersede"     (proj/timer-reason-label :on-supersede)))
    (is (= "on-frame-destroy" (proj/timer-reason-label :on-frame-destroy)))))

;; ---- rf2-nqt3d — per-step elapsed time + cascade total ------------------

(deftest long-step-threshold-test
  (testing "rf2-nqt3d — 16ms = one display frame at 60Hz; the threshold
            documents the long-step warning boundary"
    (is (= 16 proj/long-step-threshold-ms))))

(deftest long-step-predicate-test
  (testing "rf2-nqt3d — `long-step?` is true iff duration > 16ms"
    (is (false? (proj/long-step? {:duration-ms 0.1})))
    (is (false? (proj/long-step? {:duration-ms 16})))
    (is (true?  (proj/long-step? {:duration-ms 16.1})))
    (is (true?  (proj/long-step? {:duration-ms 250})))
    (is (false? (proj/long-step? {:duration-ms nil}))
        "nil duration is NOT a long step (the chip elides instead)")
    (is (false? (proj/long-step? {}))
        "missing duration returns false")))

(deftest cascade-total-ms-test
  (testing "rf2-nqt3d — sum of every step's :duration-ms"
    (is (= 12.5 (proj/cascade-total-ms [{:duration-ms 0.5}
                                        {:duration-ms 12}])))
    (is (nil? (proj/cascade-total-ms []))
        "empty step vec returns nil so the view can elide the chip")
    (is (nil? (proj/cascade-total-ms [{:step :dispatch} {:step :handler}]))
        "no step carries a duration → nil")
    (is (= 5 (proj/cascade-total-ms [{:step :dispatch}
                                     {:duration-ms 5}
                                     {:step :views}]))
        "mixed presence: missing durations skipped, sum returned")))

(deftest long-step-count-test
  (testing "rf2-nqt3d — count of steps over the 16ms threshold"
    (is (= 0 (proj/long-step-count [])))
    (is (= 0 (proj/long-step-count [{:duration-ms 0.1}
                                    {:duration-ms 10}])))
    (is (= 2 (proj/long-step-count [{:duration-ms 18}
                                    {:duration-ms 1}
                                    {:duration-ms 50}])))))

;; ---- rf2-17vxj — schema-violations step ---------------------------------

(deftest schema-violations-step-conditional-test
  (testing "rf2-17vxj — no violation events → step is OMITTED"
    (is (nil? (proj/schema-violations-step []))))

  (testing "rf2-17vxj — `:rf.error/schema-validation-failure` event
            surfaces a row"
    (let [s (proj/schema-violations-step
              [(schema-violation-ev :app-db :counter/inc [:count]
                                    "not-an-int" true)])]
      (is (= :schema-violations (:step s)))
      (is (= :SCHEMA-VIOLATIONS (:badge s)))
      (is (= 1 (count (:rows s))))
      (is (= 1 (:rollbacks s))
          ":rollbacks counts rows where :rollback? is true")
      (let [r (-> s :rows first)]
        (is (= :app-db (:where r)))
        (is (= :counter/inc (:failing-id r)))
        (is (= [:count] (:path r)))
        (is (= "not-an-int" (:value r)))
        (is (true? (:rollback? r)))
        (is (= :rf.error/schema-validation-failure (:kind r)))))))

(deftest schema-violations-hot-reload-event-test
  (testing "rf2-17vxj — `:rf.schema/violation` event (hot-reload drift)
            also produces a row; `:where` defaults to `:hot-reload`"
    (let [s (proj/schema-violations-step
              [(schema-hot-reload-ev :rf/default [:count]
                                     "not-an-int")])]
      (is (= 1 (count (:rows s))))
      (let [r (-> s :rows first)]
        (is (= :hot-reload (:where r)))
        (is (= :rf.schema/violation (:kind r)))
        (is (= [:count] (:path r)))
        (is (= "not-an-int" (:value r)))
        (is (= :logged-and-skipped (:recovery r)))))))

(deftest schema-violations-mixed-rows-test
  (testing "rf2-17vxj — runtime + hot-reload events project into the
            same row schema"
    (let [s (proj/schema-violations-step
              [(schema-violation-ev :sub-return :counter/total nil
                                    {:bad :data})
               (schema-hot-reload-ev :rf/default [:counter :n]
                                     "boom")])]
      (is (= 2 (count (:rows s)))
          "both events project into rows")
      (is (= 0 (:rollbacks s))
          "no rollback-true rows in this fixture"))))

;; ---- rf2-rrykz — app-db diff section — RETIRED 2026-05-26 -------------
;;
;; The standalone APP-DB DIFF step + `proj/app-db-diff-step` fn were
;; removed in commit 862288aca / ee9def224. The HANDLER step's `:db`
;; `[diff][all]` toggle surfaces the same data in-context. Tests for
;; the retired surface are deleted; HANDLER `:db` coverage rides on
;; `handler-row-reg-event-db-test` + `view_cljs_test.cljs` HANDLER
;; tests (rf2-93436 — `:db diff` sub-section always-present).

;; ---- rf2-yx1ae — child dispatches section -----------------------------
;;
;; The standalone CHILD-DISPATCHES step was removed from the top-level
;; cascade in commit eccb6db1b (redundant with FX which already
;; surfaces every `:dispatch` / `:dispatch-n` / `:dispatch-later` fx
;; entry). The pure-data fns (`child-dispatch-rows`,
;; `child-dispatches-step`, `find-child-epoch`) survive as low-level
;; helpers — their tests remain. The `project-includes-child-
;; dispatches-step-test` (which asserted the step is part of the
;; cascade) is retired.

(deftest child-dispatch-rows-from-dispatch-test
  (testing "rf2-yx1ae — `:dispatch [:e/x]` projects one row"
    (let [rows (proj/child-dispatch-rows
                 [(do-fx-ev {:dispatch [:e/x 7]})])]
      (is (= 1 (count rows)))
      (is (= [:e/x 7]   (-> rows first :event)))
      (is (= :dispatch  (-> rows first :via)))
      (is (nil? (-> rows first :delay-ms))))))

(deftest child-dispatch-rows-from-dispatch-n-test
  (testing "rf2-yx1ae — `:dispatch-n [[:a] [:b]]` projects one row each"
    (let [rows (proj/child-dispatch-rows
                 [(do-fx-ev {:dispatch-n [[:a] [:b 1]]})])]
      (is (= 2 (count rows)))
      (is (= [:a]   (-> rows (nth 0) :event)))
      (is (= [:b 1] (-> rows (nth 1) :event)))
      (is (every? #(= :dispatch-n (:via %)) rows)))))

(deftest child-dispatch-rows-from-dispatch-later-test
  (testing "rf2-yx1ae — `:dispatch-later {:ms 250 :dispatch [:retry]}`
            projects one row with `:delay-ms`"
    (let [rows (proj/child-dispatch-rows
                 [(do-fx-ev {:dispatch-later {:ms 250 :dispatch [:retry]}})])]
      (is (= 1 (count rows)))
      (is (= [:retry] (-> rows first :event)))
      (is (= 250 (-> rows first :delay-ms)))
      (is (= :dispatch-later (-> rows first :via)))))

  (testing "rf2-yx1ae — `:dispatch-later` accepts a vec form too"
    (let [rows (proj/child-dispatch-rows
                 [(do-fx-ev {:dispatch-later
                             [{:ms 100 :dispatch [:a]}
                              {:ms 500 :dispatch [:b]}]})])]
      (is (= 2 (count rows)))
      (is (= 100 (-> rows (nth 0) :delay-ms)))
      (is (= 500 (-> rows (nth 1) :delay-ms))))))

(deftest child-dispatches-step-conditional-test
  (testing "rf2-yx1ae — no dispatch fx → step OMITTED"
    (is (nil? (proj/child-dispatches-step
                [(do-fx-ev {:http/post {:url "/x"}})]))))

  (testing "rf2-yx1ae — dispatch fx present → step rendered"
    (let [s (proj/child-dispatches-step
              [(do-fx-ev {:dispatch [:e/x]})])]
      (is (= :child-dispatches (:step s)))
      (is (= :CHILD-DISPATCHES (:badge s)))
      (is (= 1 (count (:rows s)))))))

(deftest find-child-epoch-by-parent-dispatch-id-test
  (testing "rf2-yx1ae — find-child-epoch matches on `:parent-dispatch-id`"
    (let [history [{:epoch-id 11 :parent-dispatch-id 1 :trigger-event [:other]}
                   {:epoch-id 12 :parent-dispatch-id 1 :trigger-event [:e/x 7]}
                   {:epoch-id 13 :parent-dispatch-id 2 :trigger-event [:e/x 7]}]]
      (is (= 12 (proj/find-child-epoch history 1 [:e/x 7]))
          "exact trigger-event + parent-id match wins")
      (is (= 11 (proj/find-child-epoch history 1 [:other]))
          "exact match on a different sibling")
      (is (nil? (proj/find-child-epoch history 99 [:e/x 7]))
          "no parent-id match → nil")
      (is (nil? (proj/find-child-epoch nil 1 [:e/x 7]))
          "nil history → nil")
      (is (nil? (proj/find-child-epoch history nil [:e/x 7]))
          "nil parent-id → nil"))))

;; `project-includes-child-dispatches-step-test` retired in rf2-xu5iv
;; (commit eccb6db1b dropped the CHILD-DISPATCHES step from the
;; top-level cascade). See comment header above the child-dispatch
;; helpers section.

(deftest project-includes-schema-violations-step-test
  (testing "rf2-17vxj — top-level project emits SCHEMA-VIOLATIONS at
            the END of the cascade when violations fired"
    (let [rec   (record [(dispatched-ev [:counter/inc] :ui nil)
                         (db-changed-ev [[[:count] 0 "boom" :modified]])
                         (schema-violation-ev :app-db :counter/inc
                                              [:count] "boom" true)])
          steps (proj/project rec)]
      (is (= :schema-violations (-> steps last :step))
          "SCHEMA-VIOLATIONS rides at the end of the cascade")))

  (testing "rf2-17vxj — no violations → step absent"
    (let [rec   (record [(dispatched-ev [:counter/inc] :ui nil)
                         (db-changed-ev [[[:count] 0 1 :modified]])])
          steps (proj/project rec)]
      (is (not-any? #(= :schema-violations (:step %)) steps)))))
