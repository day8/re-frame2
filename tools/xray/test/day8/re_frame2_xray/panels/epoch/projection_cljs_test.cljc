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
    4. `flow-rows` — one row per `:rf.flow/computed` event;
       `project` splats into N first-class FLOW steps in the
       cascade (rf2-xnb1x, mirror of cofx per-step split).
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
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            ;; rf2-tyivx — canonical trace-event builders shared with
            ;; the panel-gallery synth fixtures + any other projection
            ;; test. Pre-rf2-tyivx every `*-ev` helper was duplicated
            ;; per call site; the rf2-e0xjx cluster (rf2-yhgk8 /
            ;; rf2-slnce / rf2-ipaza / rf2-w2r4p) is what happens when
            ;; copies drift in lock-step. ONE canonical name set, one
            ;; ns, one diff to land a substrate-side rename.
            [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]))

;; ---- local fixture aliases ----------------------------------------------
;;
;; Thin aliases over `teb/*` so existing call sites read identically to
;; the pre-rf2-tyivx file. A single re-name lands in one place when the
;; substrate emit shape rotates.

(def ^:private ev                   teb/ev)
(def ^:private dispatched-ev        teb/dispatched-ev)
(def ^:private run-end-ev           teb/run-end-ev)
(def ^:private cofx-run-ev          teb/cofx-run-ev)
(def ^:private db-changed-ev        teb/db-changed-ev)
(def ^:private do-fx-ev             teb/do-fx-ev)
(def ^:private fx-handled-ev        teb/fx-handled-ev)
(def ^:private flow-recomputed-ev   teb/flow-recomputed-ev)
(def ^:private sub-run-ev           teb/sub-run-ev)
(def ^:private view-render-ev       teb/view-rendered-ev)
(def ^:private view-unmounted-ev    teb/view-unmounted-ev)
(def ^:private sub-dispose-ev       teb/sub-dispose-ev)
(def ^:private machine-transition-ev   teb/machine-transition-ev)
(def ^:private machine-guard-ev        teb/machine-guard-ev)
(def ^:private machine-action-ev       teb/machine-action-ev)
(def ^:private machine-timer-cancel-ev teb/machine-timer-cancel-ev)
(def ^:private schema-violation-ev     teb/schema-violation-ev)
(def ^:private schema-hot-reload-ev    teb/schema-hot-reload-ev)

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

;; ---- rf2-5qp4g — per-source-kind enrichment -----------------------------
;;
;; Each closed-set source value from rf2-ejtpd produces a different
;; enrichment payload under `:source-enrichment` so the view layer can
;; render the rich label per kind. Vanilla sources (`:ui`,
;; `:frame-init`, `:test-harness`, `:unknown`) carry no enrichment.

(deftest dispatch-row-after-timer-enrichment-test
  (testing "rf2-5qp4g — `:source :after-timer` enrichment extracts
            delay-ms + source-state-path + machine-id from the
            event-vector shape
            `[<machine-id> [:rf.machine.timer/after-elapsed <delay>
                            <epoch> <invoke-id>]]` (rf2-ejtpd
            timer.cljc stamp site)"
    (let [event [:ws/connection [:rf.machine.timer/after-elapsed
                                 250 42 [:active :authenticating]]]
          ev    {:op-type   :rf.event
                 :operation :rf.event/dispatched
                 :tags      {:rf.event/v event
                             :source     :after-timer}}
          r     (proj/dispatch-row [ev] nil)
          enrich (:source-enrichment r)]
      (is (= :after-timer (:source r)) "source kind is preserved")
      (is (= :ws/connection (:machine-id enrich))
          "machine-id from the event vector's head")
      (is (= 250 (:delay-ms enrich))
          "delay-ms from the inner vector's slot 1")
      (is (= [:active :authenticating] (:source-state-path enrich))
          "source-state-path from the inner vector's slot 3 (invoke-id)"))))

(deftest dispatch-row-after-timer-defensive-degrade-test
  (testing "rf2-5qp4g — when `:source :after-timer` is stamped but the
            event vector doesn't match the canonical timer shape, the
            row carries no enrichment (defensive fall-through; the
            view renders the kind label only)"
    (let [ev {:op-type   :rf.event
              :operation :rf.event/dispatched
              :tags      {:rf.event/v [:some/other-event]
                          :source     :after-timer}}
          r  (proj/dispatch-row [ev] nil)]
      (is (= :after-timer (:source r)))
      (is (nil? (:source-enrichment r))
          "non-canonical timer-event shape → no enrichment"))))

(deftest dispatch-row-machine-spawn-enrichment-test
  (testing "rf2-5qp4g — `:source :machine-spawn` enrichment extracts
            the spawned actor-id (event vector's head) so the renderer
            can label the dispatch as `from machine spawn ·
            :child-actor-id`"
    (let [event [:checkout/worker [:rf.machine/spawned]]
          ev    {:op-type   :rf.event
                 :operation :rf.event/dispatched
                 :tags      {:rf.event/v event
                             :source     :machine-spawn}}
          r     (proj/dispatch-row [ev] nil)
          enrich (:source-enrichment r)]
      (is (= :machine-spawn (:source r)))
      (is (= :checkout/worker (:spawned-actor-id enrich))
          "spawned-actor-id is the first element of the event vector"))))

(deftest dispatch-row-fx-dispatch-enrichment-test
  (testing "rf2-5qp4g — `:source :fx-dispatch` enrichment reads the
            parent-dispatch-id off the dispatched trace's
            `:rf.trace/parent-dispatch-id` tag (already stamped by
            router.cljc `emit-dispatched-trace` per spec/018
            §Dispatch correlation)"
    (let [ev {:op-type   :rf.event
              :operation :rf.event/dispatched
              :tags      {:rf.event/v                 [:cart/add :apple]
                          :source                     :fx-dispatch
                          :rf.trace/parent-dispatch-id 9001}}
          r  (proj/dispatch-row [ev] nil)
          enrich (:source-enrichment r)]
      (is (= :fx-dispatch (:source r)))
      (is (= 9001 (:parent-dispatch-id enrich))
          "parent-dispatch-id from the canonical trace tag")
      (is (nil? (:delay-ms enrich))
          "`:fx-dispatch` carries no delay-ms"))))

(deftest dispatch-row-fx-dispatch-later-enrichment-test
  (testing "rf2-5qp4g — `:source :fx-dispatch-later` enrichment reads
            parent-dispatch-id + optional delay-ms (the original
            scheduled delay) off `:rf.event/source-detail :ms`"
    (let [ev {:op-type   :rf.event
              :operation :rf.event/dispatched
              :tags      {:rf.event/v                  [:checkout/retry-prompt]
                          :source                      :fx-dispatch-later
                          :rf.trace/parent-dispatch-id 9001
                          :rf.event/source-detail      {:ms 500}}}
          r  (proj/dispatch-row [ev] nil)
          enrich (:source-enrichment r)]
      (is (= :fx-dispatch-later (:source r)))
      (is (= 9001 (:parent-dispatch-id enrich)))
      (is (= 500 (:delay-ms enrich))
          "delay-ms surfaces when `:rf.event/source-detail :ms` is present"))))

(deftest dispatch-row-fx-dispatch-later-without-detail-test
  (testing "rf2-5qp4g — when no `:rf.event/source-detail` tag rides on
            the trace (older runtime, no per-fx detail stamping yet),
            `:fx-dispatch-later` still surfaces parent-dispatch-id; the
            delay-ms slot is just absent"
    (let [ev {:op-type   :rf.event
              :operation :rf.event/dispatched
              :tags      {:rf.event/v                  [:checkout/retry-prompt]
                          :source                      :fx-dispatch-later
                          :rf.trace/parent-dispatch-id 9001}}
          r  (proj/dispatch-row [ev] nil)
          enrich (:source-enrichment r)]
      (is (= 9001 (:parent-dispatch-id enrich)))
      (is (nil? (:delay-ms enrich))))))

(deftest dispatch-row-vanilla-source-has-no-enrichment-test
  (testing "rf2-5qp4g — vanilla source kinds (`:ui`, `:frame-init`,
            `:test-harness`, `:unknown`) carry no `:source-enrichment`
            slot; their labels render through the existing pre-rf2-5qp4g
            `from <source>` chrome unchanged"
    (doseq [src [:ui :frame-init :test-harness :unknown]]
      (let [ev {:op-type   :rf.event
                :operation :rf.event/dispatched
                :tags      {:rf.event/v [:counter/inc] :source src}}
            r  (proj/dispatch-row [ev] nil)]
        (is (= src (:source r)))
        (is (nil? (:source-enrichment r))
            (str src " — vanilla source kinds carry no enrichment"))))))

(deftest dispatch-row-fx-dispatch-without-parent-test
  (testing "rf2-5qp4g — when `:source :fx-dispatch` is stamped but the
            trace carries no `:rf.trace/parent-dispatch-id` (root
            cascade / test fixtures that omit dispatch-id correlation),
            the row carries no enrichment (the parent-epoch link is
            simply omitted; the kind label still reads `from fx`)"
    (let [ev {:op-type   :rf.event
              :operation :rf.event/dispatched
              :tags      {:rf.event/v [:cart/add :apple]
                          :source     :fx-dispatch}}
          r  (proj/dispatch-row [ev] nil)]
      (is (= :fx-dispatch (:source r)))
      (is (nil? (:source-enrichment r))
          "no parent-dispatch-id → no enrichment map (graceful degrade)"))))

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

(deftest coeffect-rows-reads-canonical-elapsed-ms-test
  (testing "rf2-w2r4p — substrate stamps the per-cofx invocation
            duration as `:rf.cofx/elapsed-ms` on `:rf.cofx/run`
            (rf2-hhh92 · `re-frame.cofx`; spec 009 §243). The
            pre-rf2-w2r4p reader looked for the never-emitted
            `:duration-ms` — every cofx row showed nil duration."
    (let [cofx-ev  {:op-type   :rf.cofx
                    :operation :rf.cofx/run
                    :tags      {:rf.cofx/id         :session
                                :rf.cofx/value      :auth-token
                                :rf.cofx/elapsed-ms 0.6}}
          rows     (proj/coeffect-rows
                     [cofx-ev (run-end-ev 0.5 {:session {:user-id 1}})])]
      (is (= 0.6 (-> rows first :duration-ms))
          "cofx row duration resolves through canonical :rf.cofx/elapsed-ms"))))

(deftest project-threads-cofx-duration-through-cofx-steps-test
  (testing "rf2-w2r4p — the `cofx-steps` flattening in `project` MUST
            thread the row's `:duration-ms` through to the step map.
            The pre-rf2-w2r4p flattening built each step with only
            `:id` + `:value` and dropped the duration — even with the
            reader stamping the canonical tag, the cascade'"'"'s COEFFECT
            step rendered nil and never crossed the long-step
            threshold."
    (let [rec   (record [(dispatched-ev [:cart/load] :ui nil)
                         (cofx-run-ev :session {:user-id 1} 18.5)
                         (run-end-ev 0.5)])
          steps (proj/project rec)
          cofx  (some #(when (= :coeffect (:step %)) %) steps)]
      (is (some? cofx) "COEFFECT step is present")
      (is (= 18.5 (:duration-ms cofx))
          ":duration-ms threaded from cofx-row into cofx-step")
      (is (true? (proj/long-step? cofx))
          "long-step? predicate now keys off the threaded duration"))))

(deftest project-cofx-step-omits-duration-when-absent-test
  (testing "rf2-w2r4p — cofx with no duration produces a step without
            `:duration-ms` (clean absence vs. explicit nil), matching
            the row's `cond-> (some? duration-ms)` shape"
    (let [rec   (record [(dispatched-ev [:cart/load] :ui nil)
                         (cofx-run-ev :session {:user-id 1})
                         (run-end-ev 0.5)])
          cofx  (some #(when (= :coeffect (:step %)) %) (proj/project rec))]
      (is (not (contains? cofx :duration-ms))
          "no duration on the row → :duration-ms absent on the step"))))

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

(deftest handler-row-reads-canonical-elapsed-ms-test
  (testing "rf2-slnce — substrate stamps the per-handler duration as
            `:rf.event/elapsed-ms` on `:rf.event/run-end` (rf2-hhh92 ·
            `re-frame.router/emit-run-end-trace`; spec 009 §238). The
            pre-rf2-slnce reader looked for the never-emitted
            `:duration-ms` / `:rf.event/duration-ms` — HANDLER duration
            was always nil and the cascade-summary chip total was
            systematically under-counted."
    (let [run-end {:op-type   :rf.event
                   :operation :rf.event/run-end
                   :tags      {:rf.event/elapsed-ms 4.2}}
          r       (proj/handler-row [run-end] :counter-inc)]
      (is (= 4.2 (:duration-ms r))
          "handler duration resolves through canonical :rf.event/elapsed-ms")))

  (testing "rf2-slnce — fixture-compat: a runtime that still stamps
            `:duration-ms` (older or external) falls through the
            preserved fallback chain"
    (let [run-end {:op-type   :rf.event
                   :operation :rf.event/run-end
                   :tags      {:duration-ms 9.9}}
          r       (proj/handler-row [run-end] :counter-inc)]
      (is (= 9.9 (:duration-ms r))
          "legacy :duration-ms fallback retained for older fixtures"))))

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
            tuple for source-coord lookup (named cases)"
    (is (= [:actions :open-socket]
           (proj/cascade-row-source-key
             {:kind :action :action-id :open-socket})))
    (is (= [:guards :ready?]
           (proj/cascade-row-source-key
             {:kind :guard :guard-id :ready?})))
    (is (nil? (proj/cascade-row-source-key {:kind :transition}))
        "transitions with no state/event context → nil")
    (is (nil? (proj/cascade-row-source-key {:kind :timer}))
        "timers with no state context → nil")))

;; ---- rf2-wwc3j — inline-fn / transition / timer source-key extensions -----

(deftest cascade-row-source-key-inline-entry-action-test
  (testing "rf2-wwc3j — inline-fn `:entry` action resolves to its
            target-state's `[:states <s> :entry]` slot"
    (let [inline-fn (fn [_] {})]
      (is (= [:states :connected :entry]
             (proj/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :entry
                :target-state :connected}))
          "flat machine: entry action under target-state slot")
      (is (= [:states :connected :entry]
             (proj/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :entry
                :target-state [:connected]}))
          "vector target-state coerces to the same path")
      (is (= [:states :outer :states :inner :entry]
             (proj/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :entry
                :target-state [:outer :inner]}))
          "hierarchical target-state expands to nested :states path"))))

(deftest cascade-row-source-key-inline-exit-action-test
  (testing "rf2-wwc3j — inline-fn `:exit` action resolves to its
            source-state's `[:states <s> :exit]` slot"
    (let [inline-fn (fn [_] {})]
      (is (= [:states :idle :exit]
             (proj/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :exit
                :source-state :idle})))
      (is (= [:states :idle :exit]
             (proj/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :destroy-exit
                :source-state :idle}))
          ":destroy-exit phase also maps to the :exit slot"))))

(deftest cascade-row-source-key-inline-transition-action-test
  (testing "rf2-wwc3j — inline-fn transition `:action` resolves to
            `[:states <src> :on <event> :action]`"
    (let [inline-fn (fn [_] {})]
      (is (= [:states :idle :on :submit :action]
             (proj/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :transition
                :source-state :idle :event-id :submit}))))))

(deftest cascade-row-source-key-inline-guard-test
  (testing "rf2-wwc3j — inline-fn `:guard` resolves to
            `[:states <src> :on <event> :guard]`"
    (let [inline-fn (fn [_] true)]
      (is (= [:states :idle :on :submit :guard]
             (proj/cascade-row-source-key
               {:kind :guard :guard-id inline-fn
                :source-state :idle :event-id :submit}))
          "inline guard on a state's :on transition")
      (is (nil? (proj/cascade-row-source-key
                  {:kind :guard :guard-id inline-fn
                   :source-state :idle}))
          "missing event-id → nil (the source-key cannot be built)"))))

(deftest cascade-row-source-key-transition-row-test
  (testing "rf2-wwc3j — `:transition` row resolves to `[:states <src>
            :on <event>]` so the click-through opens the transition map
            literal in the spec"
    (is (= [:states :idle :on :submit]
           (proj/cascade-row-source-key
             {:kind :transition :source-state :idle :event-id :submit})))
    (is (= [:states :outer :states :inner :on :go]
           (proj/cascade-row-source-key
             {:kind :transition :source-state [:outer :inner]
              :event-id :go}))
        "hierarchical from-state expands to nested :states path")
    (is (nil? (proj/cascade-row-source-key
                {:kind :transition :source-state :idle}))
        "missing event-id → nil")))

(deftest cascade-row-source-key-timer-row-test
  (testing "rf2-wwc3j — `:timer` row resolves to `[:states <state>]`
            (D1 minimum-viable: parent state's source-coord chip)"
    (is (= [:states :idle]
           (proj/cascade-row-source-key
             {:kind :timer :state :idle})))
    (is (= [:states :idle]
           (proj/cascade-row-source-key
             {:kind :timer :state [:idle]})))
    (is (nil? (proj/cascade-row-source-key {:kind :timer}))
        "missing state → nil")))

(deftest cascade-row-source-key-named-shadows-inline-test
  (testing "rf2-wwc3j — a named action-id keyword always wins over the
            inline derivation (the existing definition-site path covers
            the named case end-to-end)"
    (is (= [:actions :open-socket]
           (proj/cascade-row-source-key
             {:kind :action :action-id :open-socket :phase :entry
              :target-state :connected}))
        ":action-id keyword → definition-site path, ignores :phase / :target-state")
    (is (= [:guards :ready?]
           (proj/cascade-row-source-key
             {:kind :guard :guard-id :ready? :source-state :idle
              :event-id :submit}))
        ":guard-id keyword → definition-site path, ignores :source-state / :event-id")))

(deftest machine-cascade-rows-enriches-rows-with-states-test
  (testing "rf2-wwc3j — `machine-cascade-rows` stamps `:source-state` /
            `:target-state` / `:event-id` onto each non-transition row
            from the surrounding transition emit so inline-fn source-
            key lookup can resolve spec-path tuples"
    (let [evs [(machine-guard-ev :ready? :pass)
               (machine-action-ev :clear-buffer :exit :ok)
               (machine-action-ev :open-socket :entry :ok)
               (machine-transition-ev :ws/conn
                                       {:state :idle :data {}}
                                       {:state :connected :data {}}
                                       [:ws/start] 0)]
          rows (proj/machine-cascade-rows evs)]
      (is (= :idle      (-> rows (nth 0) :source-state))
          "guard row carries the source-state from the surrounding transition")
      (is (= :connected (-> rows (nth 0) :target-state))
          "guard row carries the target-state from the surrounding transition")
      (is (= :ws/start  (-> rows (nth 0) :event-id))
          "guard row carries the event-id (first elem of :event)")
      (is (= :idle      (-> rows (nth 1) :source-state))
          "exit-phase action carries source-state")
      (is (= :connected (-> rows (nth 2) :target-state))
          "entry-phase action carries target-state")
      (is (= :idle      (-> rows (nth 3) :source-state))
          "transition row stamps its own :source-state from :from-state")
      (is (= :connected (-> rows (nth 3) :target-state))
          "transition row stamps its own :target-state from :to-state"))))

(deftest machine-cascade-rows-no-transition-leaves-state-slots-nil-test
  (testing "rf2-wwc3j — when the cascade fires no transition row
            (e.g. a guard-only failed cascade), state-slots remain nil"
    (let [evs [(machine-guard-ev :ready? :fail)]
          rows (proj/machine-cascade-rows evs)]
      (is (nil? (:source-state (first rows)))
          "no surrounding transition → no source-state stamp")
      (is (nil? (:event-id (first rows)))))))

(deftest state-spec-path-prefix-test
  (testing "rf2-wwc3j — `state-spec-path-prefix` coerces a state form
            into the spec-path prefix the macro's source-coord index uses"
    (is (= [:states :idle]
           (proj/state-spec-path-prefix :idle))
        "flat keyword state → [:states <id>]")
    (is (= [:states :idle]
           (proj/state-spec-path-prefix [:idle]))
        "1-element vector state → [:states <id>]")
    (is (= [:states :outer :states :inner]
           (proj/state-spec-path-prefix [:outer :inner]))
        "hierarchical vector → nested :states prefix")
    (is (= [:states :a :states :b :states :c]
           (proj/state-spec-path-prefix [:a :b :c]))
        "deep hierarchical vector → fully-nested :states prefix")
    (is (nil? (proj/state-spec-path-prefix nil))
        "nil state → nil")
    (is (nil? (proj/state-spec-path-prefix []))
        "empty vector → nil")))

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

(deftest flow-steps-cascade-shape-test
  (testing "rf2-xnb1x — no flow events → no FLOW step in the cascade"
    (let [record {:trace-events [{:op-type   :rf.event
                                  :operation :rf.event/dispatched
                                  :tags      {:rf.event/event [:noop]}}]}
          steps  (proj/project record)
          flows  (filter #(= :flow (:step %)) steps)]
      (is (empty? flows)
          "zero flow events → no FLOW step rendered")))

  (testing "rf2-xnb1x — one flow event → ONE FLOW step"
    (let [record {:trace-events [{:op-type   :rf.event
                                  :operation :rf.event/dispatched
                                  :tags      {:rf.event/event [:counter/inc]}}
                                 (flow-recomputed-ev :total-parity [:total] 5 6)]}
          steps  (proj/project record)
          flows  (filter #(= :flow (:step %)) steps)]
      (is (= 1 (count flows)))
      (let [f (first flows)]
        (is (= :flow         (:step f)))
        (is (= :FLOW         (:badge f)))
        (is (= :total-parity (:flow-id f)))
        (is (= [:total]      (:path f)))
        (is (= 5             (:before f)))
        (is (= 6             (:after f))))))

  (testing "rf2-xnb1x — N flow events → N first-class FLOW steps, each
            carrying its own flow-id + path + before/after pair"
    (let [record {:trace-events [{:op-type   :rf.event
                                  :operation :rf.event/dispatched
                                  :tags      {:rf.event/event [:checkout/begin]}}
                                 (flow-recomputed-ev :cart/total [:cart :total] 120 195)
                                 (flow-recomputed-ev :cart/n-items [:cart :n] 2 3)]}
          steps  (proj/project record)
          flows  (filter #(= :flow (:step %)) steps)]
      (is (= 2 (count flows))
          "two flow events → two FLOW steps (mirrors per-cofx COEFFECT split)")
      (is (= [:cart/total :cart/n-items]
             (mapv :flow-id flows))
          "preserves substrate-order"))))

(deftest flow-rows-reads-canonical-substrate-shape-test
  (testing "rf2-yhgk8 — substrate emits `:rf.flow/computed` with BARE
            `:flow-id` / `:path` / `:before` / `:result` / `:elapsed-ms`
            tags (Spec 009 §Flow trace events · `re-frame.flows`). The
            pre-rf2-yhgk8 reader looked for `:rf.flow/recomputed` op +
            `:rf.flow/{id,path,before,after}` tags — every slot
            returned nil and the FLOW step silently dropped. The
            view-side `:after` maps to the substrate's `:result`."
    (let [ev {:op-type   :rf.flow
              :operation :rf.flow/computed
              :tags      {:flow-id    :cart/total
                          :path       [:cart :total]
                          :before     120
                          :result     195
                          :elapsed-ms 0.7}}
          rows (proj/flow-rows [ev])]
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= :cart/total      (:flow-id r)))
        (is (= [:cart :total]   (:path r)))
        (is (= 120              (:before r)))
        (is (= 195              (:after r))
            ":after is the view-side label; substrate stamps `:result`")
        (is (= 0.7              (:duration-ms r))
            "duration reads `:elapsed-ms`")))))

(deftest flow-rows-empty-against-legacy-shape-test
  (testing "rf2-yhgk8 — a trace event under the LEGACY `:rf.flow/recomputed`
            op (pre-canonical fixture shape) produces no flow rows; the
            reader is canonical-only post-fix"
    (let [ev {:op-type   :rf.flow
              :operation :rf.flow/recomputed
              :tags      {:rf.flow/id    :legacy/flow
                          :rf.flow/path  [:x]
                          :rf.flow/after 1}}]
      (is (= [] (proj/flow-rows [ev]))
          "legacy op-name produces zero rows — no silent fallthrough"))))

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

(deftest fx-rows-reads-canonical-elapsed-ms-test
  (testing "rf2-ipaza — substrate stamps the per-fx-handler invocation
            duration as `:rf.fx/elapsed-ms` on `:rf.fx/handled`
            (rf2-hhh92 · `re-frame.fx`; spec 009 §241). The pre-rf2-ipaza
            reader looked for the never-emitted `:duration-ms` — every
            FX row showed nil duration."
    (let [ev {:op-type   :rf.fx
              :operation :rf.fx/handled
              :tags      {:rf.fx/id         :http/post
                          :rf.fx/args       {:url "/x"}
                          :rf.fx/elapsed-ms 3.4}}
          s (proj/fx-step [ev])]
      (is (= 3.4 (-> s :rows first :duration-ms))
          "FX row duration resolves through canonical :rf.fx/elapsed-ms")))

  (testing "rf2-ipaza — fixture-compat: a runtime that still stamps
            `:duration-ms` falls through the preserved fallback"
    (let [ev {:op-type   :rf.fx
              :operation :rf.fx/handled
              :tags      {:rf.fx/id    :http/get
                          :duration-ms 7.7}}
          s (proj/fx-step [ev])]
      (is (= 7.7 (-> s :rows first :duration-ms))
          "legacy :duration-ms fallback retained for older fixtures"))))

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

(deftest disposed-subs-rows-test
  (testing "rf2-wpfjo — `disposed-subs-rows` walks every
            `:rf.sub/dispose` trace event into a row carrying
            `:sub-id`, `:query`, `:reason`, `:frame`"
    (let [rows (proj/disposed-subs-rows
                 [(sub-dispose-ev [:counter/total] :no-more-derefers)
                  (sub-dispose-ev [:counter/label] :hot-reload)
                  (sub-dispose-ev [:cart/items 42] :cache-clear)])]
      (is (= 3 (count rows)))
      (is (= :counter/total (-> rows first :sub-id)))
      (is (= [:counter/total] (-> rows first :query)))
      (is (= :no-more-derefers (-> rows first :reason)))
      (is (= :rf/default (-> rows first :frame)))
      (is (= :hot-reload   (-> rows second :reason)))
      (is (= :cache-clear  (-> rows last :reason)))
      (is (= [:cart/items 42] (-> rows last :query)))))

  (testing "rf2-wpfjo — no `:rf.sub/dispose` events → empty vec"
    (is (= [] (proj/disposed-subs-rows
                [(sub-run-ev [:counter/total] true 5 6)])))))

(deftest subscriptions-step-surfaces-disposed-rows-test
  (testing "rf2-wpfjo — `subscriptions-step` carries `:disposed-rows`
            when `:rf.sub/dispose` events fired alongside the
            recompute rows"
    (let [s (proj/subscriptions-step
              [(sub-run-ev [:a] true 1 2)
               (sub-dispose-ev [:cart/items] :no-more-derefers)])]
      (is (= 1 (count (:rows s))))
      (is (= 1 (count (:disposed-rows s))))
      (is (= :cart/items (-> s :disposed-rows first :sub-id)))
      (is (= :no-more-derefers (-> s :disposed-rows first :reason)))))

  (testing "rf2-wpfjo — dispose-only cascade (no run/skip) → step
            still present; `:rows` empty, `:disposed-rows` populated"
    (let [s (proj/subscriptions-step
              [(sub-dispose-ev [:cart/items] :no-more-derefers)])]
      (is (some? s) "step rendered when only dispose events fired")
      (is (= :subscriptions (:step s)))
      (is (= [] (:rows s)))
      (is (= 1 (count (:disposed-rows s))))))

  (testing "rf2-wpfjo — no sub events at all → step OMITTED"
    (is (nil? (proj/subscriptions-step []))))

  (testing "rf2-wpfjo — only recomputes, no disposals → `:disposed-rows`
            slot ABSENT (omit-by-absence)"
    (let [s (proj/subscriptions-step [(sub-run-ev [:a] true 1 2)])]
      (is (not (contains? s :disposed-rows))
          "absent slot conveys absence, not an empty vec"))))

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

(deftest unmounted-views-rows-test
  (testing "rf2-gmw1i — `unmounted-views-rows` projects each
            `:rf.view/unmounted` trace event into a row with `:view-id`,
            `:instance`, `:frame`"
    (let [rows (proj/unmounted-views-rows
                 [(view-unmounted-ev :app/Counter [:Counter 0] :rf/default)
                  (view-unmounted-ev :app/Sidebar [:Sidebar 0] :rf/default)])]
      (is (= 2 (count rows)))
      (is (= :app/Counter (-> rows first :view-id)))
      (is (= [:Counter 0] (-> rows first :instance)))
      (is (= :rf/default (-> rows first :frame)))
      (is (= :app/Sidebar (-> rows second :view-id)))))

  (testing "rf2-gmw1i — no `:rf.view/unmounted` events → empty vec"
    (is (= [] (proj/unmounted-views-rows
                [(view-render-ev :app/Counter [])])))))

(deftest views-step-surfaces-unmounted-rows-test
  (testing "rf2-gmw1i — `views-step` carries `:unmounted-rows` when
            `:rf.view/unmounted` events fired alongside re-renders"
    (let [s (proj/views-step
              [(view-render-ev :app/Counter [])
               (view-unmounted-ev :app/SidebarItem [:SidebarItem 0]
                                  :rf/default)])]
      (is (= 1 (count (:rows s))))
      (is (= 1 (count (:unmounted-rows s))))
      (is (= :app/SidebarItem (-> s :unmounted-rows first :view-id)))))

  (testing "rf2-gmw1i — unmount-only cascade (no renders) → step still
            present; `:rows` empty, `:unmounted-rows` populated"
    (let [s (proj/views-step
              [(view-unmounted-ev :app/Tooltip [:Tooltip 0] :rf/default)])]
      (is (some? s) "step rendered even when no re-renders fired")
      (is (= :views (:step s)))
      (is (= [] (:rows s)))
      (is (= 1 (count (:unmounted-rows s))))))

  (testing "rf2-gmw1i — no view events at all → step OMITTED"
    (is (nil? (proj/views-step []))))

  (testing "rf2-gmw1i — only re-renders, no unmounts → `:unmounted-rows`
            slot ABSENT (omit-by-absence)"
    (let [s (proj/views-step [(view-render-ev :app/Counter [])])]
      (is (not (contains? s :unmounted-rows))
          "absent slot conveys absence, not an empty vec"))))

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
          "rf2-sc3r1 7 + rf2-yx1ae + rf2-rrykz + rf2-xgeag (SCHEMA-HOT-RELOAD,
           renamed from SCHEMA-VIOLATIONS) = 10 badges"))))

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

;; ---- rf2-17vxj / rf2-xgeag — schema violations -------------------------
;;
;; rf2-xgeag retired the trailing aggregate SCHEMA-VIOLATIONS step in
;; favour of per-step inline attachment + a hot-reload-only tail step.
;; The per-row data shape (`schema-violation-rows`) is unchanged; only
;; the aggregation moved.

(deftest schema-violation-rows-basic-test
  (testing "no violation events → empty rows vec"
    (is (= [] (proj/schema-violation-rows []))))

  (testing "rf2-17vxj — `:rf.error/schema-validation-failure` event
            surfaces a row with canonical fields"
    (let [rows (proj/schema-violation-rows
                 [(schema-violation-ev :app-db :counter/inc [:count]
                                       "not-an-int" true)])]
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= :app-db                                (:where r)))
        (is (= :counter/inc                           (:failing-id r)))
        (is (= [:count]                               (:path r)))
        (is (= "not-an-int"                           (:value r)))
        (is (true?                                    (:rollback? r)))
        (is (= :rf.error/schema-validation-failure    (:kind r)))))))

(deftest schema-violation-rows-hot-reload-test
  (testing "rf2-17vxj — `:rf.schema/violation` event (hot-reload drift)
            also produces a row; `:where` defaults to `:hot-reload`"
    (let [rows (proj/schema-violation-rows
                 [(schema-hot-reload-ev :rf/default [:count]
                                        "not-an-int")])]
      (is (= 1 (count rows)))
      (let [r (first rows)]
        (is (= :hot-reload          (:where r)))
        (is (= :rf.schema/violation (:kind r)))
        (is (= [:count]             (:path r)))
        (is (= "not-an-int"         (:value r)))
        (is (= :logged-and-skipped  (:recovery r)))))))

;; `hot-reload-step-conditional-test` retired in rf2-7gf7v
;; (commit 9b96f9f6a — `refactor(xray/epoch): retire SCHEMA HOT-RELOAD
;; pipeline step + rollback chip wording`). Hot-reload drift is a
;; dev-time event, not a cascade event; rendering it as a standalone
;; pipeline tail step produced an opaque step content lacking the
;; rich context the operator needs (pre/post schema, file:line of
;; re-registration). The Issues panel — which already consumes
;; `:rf.schema/violation` trace events — is its natural home. The
;; `hot-reload-step` defn and its base-steps call site are gone;
;; nothing to test at the projection layer. The runtime-boundary
;; attachment path is covered by `attach-violations-*-test` above
;; + `project-attaches-app-db-violation-to-handler-test` below; the
;; negative assertion `not-any? :schema-hot-reload` in that test
;; pins down that no tail step is appended.

(deftest attach-violations-event-test
  (testing "rf2-xgeag — `:event` violation attaches to the DISPATCH step"
    (let [steps  [{:step :dispatch :badge :DISPATCH}
                  {:step :handler  :badge :HANDLER}]
          rows   [{:where :event :failing-id :counter/inc}]
          out    (proj/attach-violations steps rows)]
      (is (= 1 (count (:violations (first out)))))
      (is (nil? (:violations (second out)))))))

(deftest attach-violations-cofx-by-id-test
  (testing "rf2-xgeag — `:cofx` violation attaches to the COEFFECT step
            whose `:id` matches `:failing-id`"
    (let [steps  [{:step :coeffect :badge :COEFFECT :id :session}
                  {:step :coeffect :badge :COEFFECT :id :session/now}
                  {:step :handler  :badge :HANDLER}]
          rows   [{:where :cofx :failing-id :session/now}]
          out    (proj/attach-violations steps rows)]
      (is (nil? (:violations (nth out 0)))
          "non-matching cofx step untouched")
      (is (= 1 (count (:violations (nth out 1))))
          "matching cofx step attached"))))

(deftest attach-violations-app-db-to-fx-db-row-test
  (testing "rf2-8resu — `:app-db` violation attaches to the FX step's
            `:db` row (the implicit commit fx). The commit IS an fx —
            the framework treats `:db` as the first, implicit fx —
            so the schema violation belongs on the row representing
            the failed commit, not on HANDLER (which describes what
            the handler RETURNED). HANDLER + DISPATCH stay clean."
    (let [steps  [{:step :dispatch :badge :DISPATCH}
                  {:step :handler  :badge :HANDLER}
                  {:step :fx       :badge :FX
                   :rows [{:fx-id :db :status :rollback}]}]
          rows   [{:where :app-db :failing-id :counter/inc :rollback? true}]
          out    (proj/attach-violations steps rows)
          fx     (nth out 2)]
      (is (nil? (:violations (nth out 0)))
          "DISPATCH step untouched")
      (is (nil? (:violations (nth out 1)))
          "HANDLER step untouched — the violation no longer attaches here")
      (is (nil? (:violations fx))
          "FX step-level :violations untouched — the violation routes
           into the :db row, not the step")
      (is (= 1 (count (:violations (first (:rows fx)))))
          "FX :db row carries the attached violation"))))

(deftest attach-violations-fx-row-test
  (testing "rf2-xgeag — `:fx-args` violation attaches to the FX row whose
            `:fx-id` matches `:failing-id`"
    (let [steps  [{:step :fx :badge :FX
                   :rows [{:fx-id :http/post :status :ok}
                          {:fx-id :db        :status :ok}]}]
          rows   [{:where :fx-args :failing-id :http/post}]
          out    (proj/attach-violations steps rows)
          fx     (first out)]
      (is (= 1 (count (:violations (first  (:rows fx)))))
          "http/post fx row has the attached violation")
      (is (nil? (:violations (second (:rows fx))))
          ":db fx row untouched"))))

(deftest attach-violations-sub-row-test
  (testing "rf2-xgeag — `:sub-return` violation attaches to the
            SUBSCRIPTIONS row whose `:sub-id` matches `:failing-id`"
    (let [steps  [{:step :subscriptions :badge :SUBSCRIPTIONS
                   :rows [{:sub-id :user/profile}
                          {:sub-id :cart/total}]}]
          rows   [{:where :sub-return :failing-id :cart/total}]
          out    (proj/attach-violations steps rows)
          subs   (first out)]
      (is (nil? (:violations (first  (:rows subs)))))
      (is (= 1 (count (:violations (second (:rows subs)))))))))

(deftest cascade-rolled-back?-test
  (testing "rf2-xgeag — true iff any `:app-db` violation carries
            `:rollback? true`"
    (is (false? (proj/cascade-rolled-back? [])))
    (is (false? (proj/cascade-rolled-back?
                  [{:where :sub-return :rollback? true}]))
        "non-app-db rollback doesn't count")
    (is (false? (proj/cascade-rolled-back?
                  [{:where :app-db :rollback? false}])))
    (is (true?  (proj/cascade-rolled-back?
                  [{:where :app-db :rollback? true}])))))

(deftest mark-rolled-back-downstream-test
  (testing "rf2-8resu — rollback flags every step AFTER the FX step
            (not after HANDLER). The FX step itself is NOT muted —
            its `:db` row IS the visible rollback indicator (red ✗ +
            violation sub-block); muting the entire FX step would hide
            the signal. DISPATCH + HANDLER are upstream of the failed
            commit so they stay unmuted too — they ran for real."
    (let [steps [{:step :dispatch} {:step :handler}
                 {:step :fx}       {:step :subscriptions}
                 {:step :views}]
          rows  [{:where :app-db :rollback? true}]
          out   (proj/mark-rolled-back-downstream steps rows)]
      (is (nil? (:rolled-back? (nth out 0)))
          "DISPATCH untouched (upstream of the commit)")
      (is (nil? (:rolled-back? (nth out 1)))
          "HANDLER untouched (described what it RETURNED; that ran)")
      (is (nil? (:rolled-back? (nth out 2)))
          "FX step itself NOT muted — its :db row is the visible signal")
      (is (true? (:rolled-back? (nth out 3)))
          "SUBSCRIPTIONS downstream of FX gets muted")
      (is (true? (:rolled-back? (nth out 4)))
          "VIEWS downstream of FX gets muted")))

  (testing "rf2-xgeag — no rollback → no `:rolled-back?` flags"
    (let [steps [{:step :dispatch} {:step :handler} {:step :fx}]
          rows  [{:where :sub-return :rollback? true}]
          out   (proj/mark-rolled-back-downstream steps rows)]
      (is (every? #(nil? (:rolled-back? %)) out)))))

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

(deftest find-parent-epoch-by-dispatch-id-test
  (testing "rf2-5qp4g — find-parent-epoch resolves a parent epoch's
            `:epoch-id` from its `:dispatch-id`. Reverse of
            `find-child-epoch`: walks the epoch-history for a record
            whose `:dispatch-id` matches the supplied
            parent-dispatch-id, returning that record's `:epoch-id`.
            The view layer uses this to wire the
            `from fx · parent epoch #N` chrome on `:fx-dispatch` /
            `:fx-dispatch-later` DISPATCH steps."
    (let [history [{:epoch-id 41 :dispatch-id 9000 :trigger-event [:root]}
                   {:epoch-id 42 :dispatch-id 9001 :trigger-event [:parent]}
                   {:epoch-id 43 :dispatch-id 9002 :trigger-event [:child]
                    :parent-dispatch-id 9001}]]
      (is (= 41 (proj/find-parent-epoch history 9000))
          "matches the first-class :dispatch-id slot")
      (is (= 42 (proj/find-parent-epoch history 9001))
          "matches a sibling parent's :dispatch-id")
      (is (nil? (proj/find-parent-epoch history 99999))
          "no match → nil")
      (is (nil? (proj/find-parent-epoch nil 9001))
          "nil history → nil")
      (is (nil? (proj/find-parent-epoch history nil))
          "nil parent-dispatch-id → nil")
      (is (nil? (proj/find-parent-epoch [] 9001))
          "empty history → nil"))))

;; `project-includes-child-dispatches-step-test` retired in rf2-xu5iv
;; (commit eccb6db1b dropped the CHILD-DISPATCHES step from the
;; top-level cascade). See comment header above the child-dispatch
;; helpers section.

(deftest project-attaches-app-db-violation-to-fx-db-row-test
  (testing "rf2-8resu — top-level `project` attaches `:app-db`
            boundary violations to the FX step's `:db` row (the
            implicit-commit fx). The FX step is synthesised even when
            no user-fx fired — the `:where :app-db` violation alone
            is sufficient signal that a `:db` commit was attempted
            (the framework suppresses user-fx on rollback per Spec
            010, so the `:rf.fx/handled` trace doesn't emit; the
            violation IS the signal). The FX step contains exactly
            one row — the synthesised `:db` row, `:status :rollback`,
            carrying the violation. HANDLER stays clean — it
            describes what the handler RETURNED; the commit outcome
            is the FX step's `:db` row's concern."
    (let [rec     (record [(dispatched-ev [:counter/inc] :ui nil)
                           (db-changed-ev [[[:count] 0 "boom" :modified]])
                           (schema-violation-ev :app-db :counter/inc
                                                [:count] "boom" true)])
          steps   (proj/project rec)
          handler (some #(when (= :handler (:step %)) %) steps)
          fx      (some #(when (= :fx (:step %)) %) steps)
          db-row  (some #(when (= :db (:fx-id %)) %) (:rows fx))]
      (is (some? handler))
      (is (nil? (:violations handler))
          "HANDLER step carries no violations — routing moved to FX :db row")
      (is (some? fx)
          "FX step is synthesised when a :where :app-db rollback fires
           even with no user-emitted fx")
      (is (some? db-row)
          "the FX step's first row is the synthesised :db row")
      (is (= :rollback (:status db-row))
          "the :db row's status reflects the rollback")
      (is (= 1 (count (:violations db-row))))
      (is (true? (-> db-row :violations first :rollback?)))
      (is (not-any? #(= :schema-violations (:step %)) steps)
          "the retired aggregate SCHEMA-VIOLATIONS step never appears")
      (is (not-any? #(= :schema-hot-reload (:step %)) steps)
          "no hot-reload tail step when violation is runtime-boundary")))

  (testing "rf2-xgeag — no violations → no attached `:violations` +
            no `:rolled-back?` flags"
    (let [rec   (record [(dispatched-ev [:counter/inc] :ui nil)
                         (db-changed-ev [[[:count] 0 1 :modified]])])
          steps (proj/project rec)]
      (is (every? #(nil? (:violations %)) steps))
      (is (every? #(nil? (:rolled-back? %)) steps))))

  (testing "rf2-7gf7v — hot-reload drift no longer surfaces as a
            standalone cascade tail step; the Option A SCHEMA-HOT-RELOAD
            step was retired (hot-reload is a dev-time event, not a
            cascade event — Issues panel is its home). The trace
            events still flow through `schema-violation-rows` for
            consumers like the Issues panel; only the projection
            pipeline declines to materialise them as a step."
    (let [rec   (record [(dispatched-ev [:counter/inc] :ui nil)
                         (schema-hot-reload-ev :rf/default
                                               [:counter :n] "boom")])
          steps (proj/project rec)]
      (is (not-any? #(= :schema-hot-reload (:step %)) steps)
          "no SCHEMA-HOT-RELOAD tail step appended"))))
