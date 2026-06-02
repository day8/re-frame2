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
            [clojure.string :as str]
            [day8.re-frame2-xray.panels.epoch.badge :as badge]
            [day8.re-frame2-xray.panels.epoch.format :as fmt]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            ;; rf2-ugdas / rf2-e7yhv — the canonical issue-projection
            ;; predicate (`issue-event?`) + the L2 pink-wash predicate
            ;; (`cascade-has-issue?`) the no-op MUST NOT trip and the
            ;; `:*`-action throw MUST trip (the contrast).
            [day8.re-frame2-xray.panels.issues-ribbon-helpers :as issues]
            [day8.re-frame2-xray.panels.l2-timeline :as l2]
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
(def ^:private db-pending-ev        teb/db-pending-ev)
(def ^:private db-pending-post-flow-ev teb/db-pending-post-flow-ev)
(def ^:private sub-run-ev           teb/sub-run-ev)
(def ^:private view-render-ev       teb/view-rendered-ev)
(def ^:private view-unmounted-ev    teb/view-unmounted-ev)
(def ^:private sub-dispose-ev       teb/sub-dispose-ev)
(def ^:private machine-transition-ev   teb/machine-transition-ev)
(def ^:private machine-guard-ev        teb/machine-guard-ev)
(def ^:private machine-action-ev       teb/machine-action-ev)
(def ^:private machine-timer-cancel-ev teb/machine-timer-cancel-ev)
(def ^:private machine-unhandled-no-op-ev teb/machine-unhandled-no-op-ev)
(def ^:private machine-action-exception-ev teb/machine-action-exception-ev)
(def ^:private schema-violation-ev     teb/schema-violation-ev)
(def ^:private schema-hot-reload-ev    teb/schema-hot-reload-ev)
(def ^:private handler-exception-ev    teb/handler-exception-ev)
(def ^:private fx-handler-exception-ev teb/fx-handler-exception-ev)
(def ^:private coeffect-exception-ev   teb/coeffect-exception-ev)
(def ^:private interceptor-exception-ev teb/interceptor-exception-ev)

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
    (let [event [:checkout/worker [:rf.machine.spawn/spawned]]
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

  rf2-sp0n9 — the prior `:db-diff` Editscript flat-row slot is gone
  (the view re-derived its own diff and discarded the projection's);
  the HANDLER `:db` is now rendered from `:db-post-handler` diffed
  against `:db-before` by the view's edn-inspector."
    (let [r (proj/handler-row [(db-changed-ev [[[:counter] 5 6 :modified]])]
                              :counter-inc)]
      (is (= :handler (:step r)))
      (is (= :HANDLER (:badge r)))
      (is (= :reg-event-db (:flavour r)))
      (is (= :counter-inc (:event-id r)))
      (is (not (contains? r :db-diff))
          "rf2-sp0n9 — no precomputed :db-diff slot on the handler row")
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

(deftest handler-row-machine-transition-no-action-test
  (testing "rf2-eue07 — a real macrostep that fires NO `:rf.machine/action-ran`
            (an entry-cascade-only / pure-state-move transition — the
            framework does NOT emit `action-ran` for `:entry` actions, see
            rf2-n9f4z) is STILL a machine cascade. The substrate's
            `:rf.machine/transition` summary rides the macrostep
            UNCONDITIONALLY (commit-or-finalize · lifecycle_fx ·
            registration.cljc), so `:rf.machine/transition` is the
            authoritative macrostep marker. It MUST classify `:reg-machine`
            (render the machine section), NOT `:reg-event-fx` / `:reg-event-db`
            (the raw `:db` diff of the snapshot write).

  RED before the fix: handler-flavour saw only the do-fx (the machine handler
  always rides one) → fell through to `:reg-event-fx`; `:machine` slot absent."
    (let [snap-before {:state [:off]      :data {}}
          snap-after  {:state [:running]  :data {}}
          ;; A machine handler ALWAYS rides a `:rf.fx/do-fx` (the snapshot
          ;; write). The pre-fix classifier let that do-fx win → :reg-event-fx.
          evs [(do-fx-ev {:db {:hvac/controller {:state {:climate [:running]}}}})
               (machine-transition-ev :hvac/controller snap-before snap-after
                                       [:hvac/power-cycle] 2)
               (db-changed-ev [[[:hvac/controller] {} {} :modified]])]
          r   (proj/handler-row evs :hvac/power-cycle)
          m   (:machine r)]
      (is (= :reg-machine (:flavour r))
          "a transition with NO action-ran still classifies :reg-machine")
      (is (some? m)
          "the machine section is populated, not the raw :db diff")
      (is (= :hvac/controller (-> m :transition :machine-id))
          "the transition row threads through into the machine block"))))

(deftest handler-row-bootstrap-initial-entry-transition-test
  (testing "rf2-eue07 — the post-carve-out bootstrap macrostep (rf2-t4582:
            bootstrap runs `:initial-entry`, never a no-op) emits a
            `:rf.machine/transition` summary. Even were its `:initial-entry`
            actions untraced, the transition marks it a machine cascade →
            `:reg-machine`, so the EVENT HANDLER renders the machine section
            (the `[INITIAL]` bootstrap), not a raw `:db` diff."
    (let [snap-before {:state nil          :data {}}
          snap-after  {:state [:off]       :data {}}
          evs [(do-fx-ev {:db {:hvac/controller {:state {:climate [:off]}}}})
               (machine-transition-ev :hvac/controller snap-before snap-after
                                       [:rf.machine/bootstrap] 1)]
          r   (proj/handler-row evs :hvac/controller)]
      (is (= :reg-machine (:flavour r))
          "the bootstrap transition classifies :reg-machine, not :reg-event-fx")
      (is (some? (:machine r))
          "the bootstrap renders the machine section"))))

(deftest handler-flavour-negative-guards-test
  (testing "rf2-eue07 NEGATIVE GUARD — a genuine reg-event-fx (a do-fx with
            NO machine trace at all) STILL classifies :reg-event-fx; the new
            transition predicate must not over-claim"
    (let [evs [(do-fx-ev {:db {} :navigate "/x"})
               (db-changed-ev [])]
          r   (proj/handler-row evs :navigate-to)]
      (is (= :reg-event-fx (:flavour r))
          "no machine trace → plain reg-event-fx, unchanged")
      (is (nil? (:machine r))
          "no machine section on a plain fx handler")))

  (testing "rf2-eue07 NEGATIVE GUARD — a genuine reg-event-db (no fx, no
            machine trace) STILL classifies :reg-event-db"
    (let [r (proj/handler-row [(db-changed-ev [[[:counter] 5 6 :modified]])]
                              :counter-inc)]
      (is (= :reg-event-db (:flavour r))
          "no fx, no machine trace → reg-event-db, unchanged")
      (is (nil? (:machine r))))))

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

(deftest machine-cascade-rows-canonical-phase-order-test
  (testing "rf2-tjqd8 — `machine-cascade-rows` returns rows in CANONICAL
            phase order (guard → exit → TRANSITION → entry → always →
            after-action → timer), with a STABLE sort that preserves
            intra-phase emit order. The substrate emits the
            :rf.machine/transition LAST, so the panel re-sorts it ahead
            of the entry/always actions."
    (let [;; Emit order: guard, exit, transition-phase action, entry,
          ;; always, transition-LAST, after-action, timer. Two entry
          ;; actions exercise intra-phase stability.
          evs [(machine-guard-ev :ready? :pass)
               (machine-action-ev :clear-buffer :exit :ok)
               (machine-action-ev :open-socket :transition :ok)
               (machine-action-ev :arm-heartbeat :entry :ok)
               (machine-action-ev :seed-cache :entry :ok)
               (machine-action-ev :pulse :always :ok)
               (machine-transition-ev :ws/conn [:idle] [:connecting]
                                       [:ws/start] 1)
               (machine-timer-cancel-ev :ws/conn [:idle] 500 :on-exit)]
          cascade (proj/machine-cascade-rows evs)]
      (is (= 8 (count cascade))
          "one cascade row per substrate emit")
      ;; Canonical: guard(0) exit(1) transition-phase-action(2) +
      ;; transition-kind(2) [stable: action emitted first] entry(3) ×2
      ;; always(4) timer(6).
      (is (= [:guard :action :action :transition :action :action :action :timer]
             (mapv :kind cascade))
          "rf2-tjqd8 — rows in canonical (kind,phase) rank order")
      (is (= [nil :exit :transition nil :entry :entry :always nil]
             (mapv :phase cascade))
          "the TRANSITION (nil phase) lands between transition-phase and entry actions")
      (is (= [:ready? :clear-buffer :open-socket nil :arm-heartbeat :seed-cache :pulse nil]
             (mapv #(or (:guard-id %) (:action-id %)) cascade))
          "intra-phase emit order preserved (arm-heartbeat before seed-cache)")
      (is (= [1 2 3 4 5 6 7 8] (mapv :step cascade))
          ":step renumbered 1..N over the FINAL canonical order"))))

(deftest machine-cascade-row-fields-test
  (testing "rf2-u69j7 / rf2-tjqd8 — each cascade row exposes the
            substrate-canonical slots the view layer consumes; rows are
            CANONICALLY ORDERED (guard → transition → entry-action →
            timer), not in raw emit order. The substrate emits the
            :rf.machine/transition LAST; the panel re-sorts the ENTRY
            action AFTER the transition."
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
          ;; Emit order: guard, ENTRY action, transition, timer. The
          ;; canonical sort moves the entry action AFTER the transition.
          rows (proj/machine-cascade-rows [g a t tm])]
      (is (= [:guard :transition :action :timer] (mapv :kind rows))
          "rf2-tjqd8 — canonical order: guard → TRANSITION → entry action → timer")
      (is (= [1 2 3 4] (mapv :step rows))
          ":step renumbered 1..N over the canonical order")
      ;; Guard row
      (let [r (nth rows 0)]
        (is (= :guard (:kind r)))
        (is (= :form-valid? (:guard-id r)))
        (is (= :fail (:outcome r))))
      ;; Transition row — now BEFORE the entry action (rf2-tjqd8)
      (let [r (nth rows 1)]
        (is (= :transition (:kind r)))
        (is (= :ws/conn (:machine-id r)))
        (is (= [:idle]   (:from-state r))
            ":from-state is hoisted off the :before snapshot")
        (is (= [:active] (:to-state r))
            ":to-state is hoisted off the :after snapshot")
        (is (= {:n 0} (:data-before r)))
        (is (= {:n 1} (:data-after r)))
        (is (= [:ws/start] (:event r))))
      ;; Entry action row — now AFTER the transition (rf2-tjqd8)
      (let [r (nth rows 2)]
        (is (= :action (:kind r)))
        (is (= :open-socket (:action-id r)))
        (is (= :entry (:phase r)))
        (is (false? (:threw? r)))
        (is (= 1 (count (:fx r)))
            "per-action fx attribution is hoisted onto the row")
        (is (= {:n 1} (:data-write r))
            "per-action data delta (outcome :data) is hoisted onto the row")
        (is (= {} (:data-before r))
            "rf2-5hjb5 — input :data hoisted as the diff pre-image"))
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

;; ---- rf2-ugdas — the benign unhandled-event no-op -----------------------

(deftest machine-cascade-rows-unhandled-no-op-test
  (testing "rf2-ugdas — a :rf.machine.event/unhandled-no-op trace projects
            to a :no-op cascade row carrying machine-id / event / state"
    (let [evs  [(machine-unhandled-no-op-ev :door/main [:door/insert-coin] :alarming)]
          rows (proj/machine-cascade-rows evs)
          r    (first rows)]
      (is (= 1 (count rows)))
      (is (= :no-op (:kind r)))
      (is (= :door/main (:machine-id r)))
      (is (= [:door/insert-coin] (:event r)))
      (is (= :alarming (:state r)))
      (is (= 1 (:step r)))))

  (testing "rf2-iu3no — a SINGLE-machine genuine no-op collapses to the
            CONSEQUENCE only: '[NO OP] staying in {state}'. The `NO OP`
            kind-pill is the sole marker; the verb is just 'staying in
            <state>' — no 'no-op —' prefix, no 'received [event]' echo
            (the focused-epoch Event header names it), no ', no transition'
            suffix, no machine name (the lone machine is named above), and
            NO 'ignored' outcome chip."
    (let [r (first (proj/machine-cascade-rows
                     [(machine-unhandled-no-op-ev :door/main
                                                  [:door/insert-coin] :alarming)]))
          verb (fmt/cascade-row-label r)]
      ;; RED before rf2-iu3no: verb == "no-op — :door/main received
      ;; [:door/insert-coin] in :alarming, no transition" (the four-way
      ;; restatement) + a non-nil "ignored" outcome label.
      (is (= "staying in :alarming" verb))
      (is (= "NO OP" (badge/cascade-kind-label :no-op))
          "the pill is the sole marker — `NO OP` (space, not hyphen)")
      (is (not (str/includes? verb "no-op"))   "no 'no-op —' prefix")
      (is (not (str/includes? verb "received")) "no 'received [event]' echo")
      (is (not (str/includes? verb "transition")) "no ', no transition' suffix")
      (is (not (str/includes? verb ":door/main"))
          "single-machine case drops the machine name")
      (is (false? (:show-machine-name? r))
          ":show-machine-name? false for the single-machine no-op")
      (is (nil? (fmt/cascade-outcome-label r))
          "no outcome chip — the pill + verb are the whole notice")))

  (testing "rf2-iu3no — a MULTI-MACHINE epoch (broadcast event / parallel
            regions) keeps the machine name on each no-op row so the
            operator can tell WHICH machine stood pat:
            '[NO OP] :hvac/controller staying in {state}'"
    (let [rows (proj/machine-cascade-rows
                 [(machine-unhandled-no-op-ev :hvac/controller
                                              [:hvac/power-cycle] [:off])
                  (machine-unhandled-no-op-ev :hvac/fan
                                              [:hvac/power-cycle] [:idle])])
          by-id (into {} (map (juxt :machine-id identity)) rows)]
      (is (= 2 (count rows)))
      (is (every? :show-machine-name? rows)
          ">1 distinct machine-id in play → surface the name on every no-op")
      (is (= ":hvac/controller staying in [:off]"
             (fmt/cascade-row-label (by-id :hvac/controller)))
          "the multi-machine no-op verb leads with the machine name")
      (is (= ":hvac/fan staying in [:idle]"
             (fmt/cascade-row-label (by-id :hvac/fan))))))

  (testing "rf2-ugdas — a cascade whose ONLY machine activity is the no-op
            is still :reg-machine flavour, so the EVENT HANDLER machine
            section renders the notice (no action ran)"
    (let [r (proj/handler-row
              [(machine-unhandled-no-op-ev :door/main [:door/insert-coin] :alarming)]
              :door/main)]
      (is (= :reg-machine (:flavour r)))
      (is (some? (:machine r)))
      (is (= [:no-op] (mapv :kind (-> r :machine :cascade)))))))

;; ---- rf2-e6q97 — a no-op suppresses the spurious {X}→{X} transition row ---

(deftest no-op-suppresses-spurious-transition-row-test
  (testing "rf2-e6q97 — on a genuine unknown-user-event no-op the substrate
            emits BOTH the benign :rf.machine.event/unhandled-no-op AND its
            unconditional :rf.machine/transition summary ({X}→{X}, 0
            microsteps). Rendered side-by-side those CONTRADICT. The cascade
            must show the :no-op row ALONE — the spurious transition row is
            dropped."
    ;; rf2-iu3no — the live repro fixture was a `[:rf.machine/bootstrap]`
    ;; no-op, but rf2-t4582 (#2846) carved the bootstrap OUT of the no-op
    ;; classification (bootstrap runs :initial-entry, never a no-op). The
    ;; dedup MECHANISM this asserts is independent of which event no-op'd, so
    ;; the fixture is rebased onto a genuine unknown USER event (the only
    ;; thing that still produces an unhandled-no-op post-t4582).
    (let [state {:vehicle :red :pedestrian :walk}
          ;; A user dispatched [:traffic/unknown] in a state with no matching
          ;; transition: unhandled-no-op (event matched no transition)
          ;; FOLLOWED BY the unconditional commit transition with
          ;; :before == :after and :microsteps 0.
          no-op (machine-unhandled-no-op-ev :traffic/light
                                            [:traffic/unknown] state)
          tx    (machine-transition-ev :traffic/light
                                       {:state state :data {}}
                                       {:state state :data {}}
                                       [:traffic/unknown] 0)
          rows  (proj/machine-cascade-rows [no-op tx])]
      ;; RED before the fix: rows == [:no-op :transition] (the contradiction).
      ;; GREEN after: the :transition row is gone.
      (is (= [:no-op] (mapv :kind rows))
          "only the :no-op row survives — no spurious {X}→{X} transition row")
      (is (= 1 (count rows)))
      (is (not-any? #(= :transition (:kind %)) rows)
          "the spurious 0-microstep no-change transition row is suppressed")
      (is (= 1 (:step (first rows)))
          ":step renumbered 1..N over the deduped cascade")))

  (testing "rf2-e6q97 — emit-order is irrelevant: even if the transition emit
            precedes the no-op emit, the transition row is still dropped"
    (let [state :stable
          tx    (machine-transition-ev :traffic/light
                                       {:state state} {:state state}
                                       [:traffic/unknown] 0)
          no-op (machine-unhandled-no-op-ev :traffic/light
                                            [:traffic/unknown] state)
          rows  (proj/machine-cascade-rows [tx no-op])]
      (is (= [:no-op] (mapv :kind rows)))))

  (testing "rf2-e6q97 — the no-op suppression flows through handler-row into
            the :machine :cascade slot the view renders (the live surface)"
    (let [state {:vehicle :red :pedestrian :walk}
          evs   [(machine-unhandled-no-op-ev :traffic/light
                                             [:traffic/unknown] state)
                 (machine-transition-ev :traffic/light
                                        {:state state :data {}}
                                        {:state state :data {}}
                                        [:traffic/unknown] 0)]
          r     (proj/handler-row evs :traffic/light)]
      (is (= :reg-machine (:flavour r)))
      (is (= [:no-op] (mapv :kind (-> r :machine :cascade)))
          "the view's :cascade carries the no-op row alone"))))

(deftest genuine-self-transition-keeps-its-row-test
  (testing "rf2-e6q97 NEGATIVE GUARD — a genuine EXTERNAL self-transition
            (:target :same-state; :exit + :entry FIRE, microsteps > 0) is a
            REAL transition (Spec 005 L291-296). It has a real `match`, so the
            unhandled-no-op branch is never reached — NO :no-op row fires —
            and the suppression MUST NOT touch its transition row."
    (let [state [:active]
          ;; exit + entry actions fired (the external self-transition
          ;; semantics) and a microstep ran — a real transition, NO no-op.
          evs   [(machine-action-ev :on-exit  :exit  :ok)
                 (machine-action-ev :on-entry :entry :ok)
                 (machine-transition-ev :traffic/light
                                        {:state state} {:state state}
                                        [:traffic/tick] 1)]
          rows  (proj/machine-cascade-rows evs)]
      (is (= #{:action :transition} (set (mapv :kind rows))))
      (is (= 1 (count (filterv #(= :transition (:kind %)) rows)))
          "the self-transition row is PRESERVED — no no-op row to trigger suppression")
      (let [tx (first (filterv #(= :transition (:kind %)) rows))]
        (is (= state (:from-state tx)))
        (is (= state (:to-state tx)))
        (is (= 1 (:microsteps tx))
            "microsteps > 0 — a real transition, not a no-op"))))

  (testing "rf2-e6q97 NEGATIVE GUARD — an INTERNAL self-transition (omit
            :target; action runs, no exit/entry) likewise has a real match,
            so no :no-op row — its transition row is preserved per semantics"
    (let [state :idle
          evs   [(machine-action-ev :tick-counter :transition :ok)
                 (machine-transition-ev :traffic/light
                                        {:state state} {:state state}
                                        [:traffic/tick] 0)]
          rows  (proj/machine-cascade-rows evs)]
      (is (some #(= :transition (:kind %)) rows)
          "internal self-transition row preserved (no no-op row present)")
      (is (= 1 (count (filterv #(= :transition (:kind %)) rows)))))))

(deftest unhandled-no-op-is-not-an-issue-test
  (testing "rf2-ugdas — the no-op trace's op-type is :rf.machine (NOT a
            severity), so issue-event? returns FALSE — NO pink wash, NO
            ribbon entry, for free (the bead's automatic consequence)"
    (let [no-op (machine-unhandled-no-op-ev :door/main [:door/insert-coin] :alarming)]
      (is (= :rf.machine (:op-type no-op)))
      (is (false? (issues/issue-event? no-op))
          "issue-event? FALSE for the benign no-op")
      (is (false? (l2/cascade-has-issue? {:other [no-op]}))
          "cascade-has-issue? FALSE — no pink wash for a no-op-only cascade"))))

;; ---- rf2-e7yhv — the :* wildcard-action throw (the inverse) --------------

(deftest machine-action-exception-is-an-issue-test
  (testing "rf2-e7yhv — a :rf.error/machine-action-exception IS an issue
            (op-type :error) — issue-event? + cascade-has-issue? TRUE
            (pink); the inverse of the benign no-op above"
    (let [exc (machine-action-exception-ev
                {:machine-id :fuse/box :action-id :blow-fuse
                 :event [:fuse/short-circuit] :message "unhandled machine event"
                 :via-wildcard? true})]
      (is (= :error (:op-type exc)))
      (is (true? (issues/issue-event? exc))
          "issue-event? TRUE for the real exception")
      (is (true? (l2/cascade-has-issue? {:other [exc]}))
          "cascade-has-issue? TRUE — the event row goes pink"))))

(deftest machine-action-exception-row-attributes-wildcard-test
  (testing "rf2-e7yhv — exception-row lifts the machine attribution +
            the :rf/via-wildcard? flag off the :transition slot so the
            EXCEPTION card can name a :* wildcard-action throw"
    (let [exc  (machine-action-exception-ev
                 {:machine-id :fuse/box :action-id :blow-fuse
                  :event [:fuse/short-circuit] :message "unhandled machine event"
                  :via-wildcard? true})
          rows (proj/exception-rows [exc])
          r    (first rows)]
      (is (= 1 (count rows)))
      (is (= :rf.error/machine-action-exception (:operation r)))
      (is (= :fuse/box (:machine-id r)))
      (is (= :blow-fuse (:action-id r)))
      (is (= [:fuse/short-circuit] (:event r)))
      (is (= "unhandled machine event" (:message r)))
      (is (true? (:via-wildcard? r))
          "the :* wildcard attribution flag rides through")))

  (testing "rf2-e7yhv — a NAMED-transition action throw is NOT flagged as
            a wildcard (:rf/via-wildcard? absent from the transition slot)"
    (let [exc (machine-action-exception-ev
                {:machine-id :fuse/box :action-id :named-action
                 :event [:fuse/inspect] :message "boom"
                 :via-wildcard? false})
          r   (first (proj/exception-rows [exc]))]
      (is (false? (:via-wildcard? r))
          "named-transition throw is not attributed to the wildcard"))))

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

(deftest machine-logical-state-test
  (testing "rf2-iwy0c — `machine-logical-state` projects a snapshot to
            `{:state :tags}` ONLY, excluding `:data`, `:meta`, and the
            framework `:rf/*` bookkeeping slots."
    (is (= {:state :locked :tags #{:locked}}
           (proj/machine-logical-state
             {:state :locked
              :tags #{:locked}
              :data {:tries 0}
              :meta {:created 1}
              :rf/spawn-counter {}}))
        "select-keys [:state :tags] drops :data / :meta / :rf/* by construction")
    (testing "parallel machine — the region→state map + tag-union survive verbatim"
      (is (= {:state {:vehicle :red :pedestrian :dont-walk}
              :tags #{:vehicle-stop :ped-stop}}
             (proj/machine-logical-state
               {:state {:vehicle :red :pedestrian :dont-walk}
                :tags #{:vehicle-stop :ped-stop}
                :data {:cycles 0}}))))
    (testing "a snapshot with no :tags projects just :state"
      (is (= {:state :idle}
             (proj/machine-logical-state {:state :idle :data {}}))))
    (is (nil? (proj/machine-logical-state nil))
        "nil snapshot → nil (caller elides)")))

(deftest machine-logical-state-changed?-test
  (testing "rf2-iwy0c — `machine-logical-state-changed?` keys the delta-box
            elision: true iff `{:state :tags}` differs across before/after."
    (is (true? (proj/machine-logical-state-changed?
                 {:state :locked :tags #{:locked} :data {:n 0}}
                 {:state :open   :tags #{:open}   :data {:n 0}}))
        ":state changed → changed")
    (is (true? (proj/machine-logical-state-changed?
                 {:state :open :tags #{:open}}
                 {:state :open :tags #{:open :held}}))
        ":tags changed (same :state) → changed")
    (testing "self/internal transition — :state + :tags unchanged, only
              :data + :rf/* moved → NOT changed (delta box elides)"
      (is (false? (proj/machine-logical-state-changed?
                    {:state :open :tags #{:open} :data {:n 1} :rf/spawn-counter {}}
                    {:state :open :tags #{:open} :data {:n 2} :rf/spawn-counter {:a 1}}))))))

(deftest project-machine-populates-cascade-slot-test
  (testing "rf2-u69j7 / rf2-tjqd8 — `(handler-row …)` populates `:machine
            :cascade` with the CANONICALLY-ORDERED cascade the view
            consumes. The entry action emits before the transition but
            renders AFTER it (canonical guard → TRANSITION → entry)."
    (let [evs [(dispatched-ev [:ws/start] :ui nil)
               (machine-guard-ev :ready? :pass)
               (machine-action-ev :open-socket :entry :ok)
               (machine-transition-ev :ws/conn [:idle] [:connecting])]
          r   (proj/handler-row evs :ws/start)
          c   (-> r :machine :cascade)]
      (is (vector? c) ":cascade is a vector")
      (is (= 3 (count c))
          "one row per substrate emit (guard + action + transition)")
      (is (= [:guard :transition :action] (mapv :kind c))
          "rf2-tjqd8 — canonical order: guard → TRANSITION → entry action"))))

;; ---- rf2-52u5n — STRUCTURED transition cascade ------------------------
;;
;; The `:rf.machine/transition` trace carries a structured `:cascade` step
;; vector (rf2-n9f4z) — the ordered exit/action/entry/microstep steps that
;; explain HOW the macrostep reached its after-state. The projection threads
;; it through the transition row + groups it per-region for the view.
;;
;; The HVAC `[:hvac/power-cycle]` cascade below is the contract shape the
;; instrumentation test (`re-frame.machine-cascade-instrumentation-test`)
;; pins: a parallel machine, climate region (deep compound, exits :idle →
;; action @ LCA → 3-level entry descent) + fan region (exit :off → action →
;; single entry).

(def ^:private hvac-power-cycle-cascade
  [{:kind :exit   :state [:idle]   :region :climate :action nil :data-delta {}}
   {:kind :action :state [:idle]   :region :climate :action :enter-running       :data-delta {:trail [:action:power-on]}}
   {:kind :entry  :state [:running] :region :climate :action :enter-running-level :data-delta {:trail [:action:power-on :entry:running]}}
   {:kind :entry  :state [:running :conditioning] :region :climate :action :enter-conditioning :data-delta {:trail [:action:power-on :entry:running :entry:conditioning]}}
   {:kind :entry  :state [:running :conditioning :heating] :region :climate :action :enter-heating :data-delta {:trail [:action:power-on :entry:running :entry:conditioning :entry:heating]}}
   {:kind :exit   :state [:off]    :region :fan :action nil :data-delta {}}
   {:kind :action :state [:off]    :region :fan :action :fan-on        :data-delta {:trail [:action:power-on :entry:running :entry:conditioning :entry:heating :action:fan-on]}}
   {:kind :entry  :state [:on]     :region :fan :action :enter-fan-on  :data-delta {:trail [:action:power-on :entry:running :entry:conditioning :entry:heating :action:fan-on :entry:fan-on]}}])

(def ^:private flat-go-cascade
  [{:kind :exit   :state [:a] :region nil :action nil     :data-delta {}}
   {:kind :action :state [:a] :region nil :action :go-act :data-delta {:went true}}
   {:kind :entry  :state [:b] :region nil :action nil     :data-delta {}}])

(def ^:private always-quiz-cascade
  [{:kind :action :state [:asking] :region nil :action :count :data-delta {:correct 10}}
   {:kind :microstep :region nil :microstep-index 0 :from :asking :to :winner
    :steps [{:kind :exit   :state [:asking] :region nil :action nil  :data-delta {}}
            {:kind :action :state [:asking] :region nil :action :win  :data-delta {:won true}}
            {:kind :entry  :state [:winner] :region nil :action nil  :data-delta {}}]}])

(deftest machine-transition-row-threads-structured-cascade-test
  (testing "rf2-52u5n — `machine-transition-row` threads the structured
            `:cascade` step vector off the `:rf.machine/transition` trace
            so the view can render the step-by-step entry/exit cascade.
            RED before this bead: the row had only `{:before :after
            :microsteps :event}` — no `:cascade`."
    (let [snap-before {:state {:climate :idle :fan :off} :data {}}
          snap-after  {:state {:climate [:running :conditioning :heating] :fan :on} :data {}}
          evs [(machine-transition-ev :hvac/controller snap-before snap-after
                                       [:hvac/power-cycle] 0 hvac-power-cycle-cascade)
               (machine-action-ev :enter-heating :entry :ok)]
          r   (proj/handler-row evs :hvac/controller)
          mt  (-> r :machine :transition)
          tx  (first (filterv #(= :transition (:kind %)) (-> r :machine :cascade)))]
      (is (= hvac-power-cycle-cascade (:cascade mt))
          "the summary transition row carries the structured cascade")
      (is (= hvac-power-cycle-cascade (:cascade tx))
          "the transition CASCADE row threads the structured cascade for the view"))))

(deftest cascade-regions-groups-parallel-per-region-test
  (testing "rf2-52u5n — `cascade-regions` groups the LCA-cascade steps by
            `:region`, preserving first-encounter (declaration) order, so
            the view renders climate before fan. Microsteps are excluded."
    (let [regions (proj/cascade-regions hvac-power-cycle-cascade)]
      (is (= [:climate :fan] (mapv :region regions))
          "regions in declaration order (climate first, then fan)")
      (let [climate (:steps (first regions))
            fan     (:steps (second regions))]
        ;; climate: exit :idle (no action) → action @ LCA → 3-level entry
        (is (= [:exit :action :entry :entry :entry] (mapv :kind climate))
            "climate: action-free :idle exit → transition action → initial-descent")
        (is (= [[:idle] [:idle] [:running] [:running :conditioning]
                [:running :conditioning :heating]]
               (mapv :state climate))
            "climate state paths in cascade order (region-relative)")
        (is (= [nil :enter-running :enter-running-level :enter-conditioning :enter-heating]
               (mapv :action climate))
            "climate action-ids (leading nil = action-free :idle exit)")
        ;; fan: exit :off (no action) → action → single entry
        (is (= [:exit :action :entry] (mapv :kind fan)))
        (is (= [nil :fan-on :enter-fan-on] (mapv :action fan)))))))

(deftest cascade-regions-flat-machine-single-nil-region-test
  (testing "rf2-52u5n — a flat/compound machine's steps all carry `:region
            nil`, so `cascade-regions` returns ONE group keyed nil (the view
            renders one ungrouped column, no region label)."
    (let [regions (proj/cascade-regions flat-go-cascade)]
      (is (= 1 (count regions)))
      (is (= [nil] (mapv :region regions)))
      (is (= [:exit :action :entry] (mapv :kind (:steps (first regions))))
          "minimal flat cascade: exit → action → entry")
      (is (false? (proj/parallel-cascade? flat-go-cascade))
          "a single-region cascade is not parallel"))))

(deftest cascade-microsteps-extracts-and-orders-test
  (testing "rf2-52u5n — `cascade-microsteps` extracts the `:always`
            microstep steps, ordered by `:microstep-index`, each carrying
            its nested `:steps`; structural steps are excluded."
    (let [ms (proj/cascade-microsteps always-quiz-cascade)]
      (is (= 1 (count ms)))
      (is (= 0 (:microstep-index (first ms))))
      (is (= :asking (:from (first ms))))
      (is (= :winner (:to (first ms))))
      (is (some #(= :win (:action %)) (:steps (first ms)))
          "the eventless transition's :win action is explainable inside the microstep")
      ;; the headline event-driven action stays out of the microstep stream.
      (is (= [] (proj/cascade-microsteps flat-go-cascade))
          "a non-:always cascade has no microsteps"))))

(deftest parallel-cascade-and-step-count-test
  (testing "rf2-52u5n — `parallel-cascade?` is true only for >1 region;
            `cascade-step-count` totals structural steps incl. nested
            microstep steps."
    (is (true? (proj/parallel-cascade? hvac-power-cycle-cascade))
        "two regions → parallel")
    (is (= 8 (proj/cascade-step-count hvac-power-cycle-cascade))
        "8 structural steps in the power-cycle cascade")
    (is (= 4 (proj/cascade-step-count always-quiz-cascade))
        "1 top-level action + 3 nested microstep steps")
    (is (nil? (proj/cascade-step-count nil))
        "nil cascade → nil count (view elides the chip)")
    (is (nil? (proj/cascade-step-count []))
        "empty cascade → nil count")))

(deftest transition-row-without-cascade-falls-back-test
  (testing "rf2-52u5n negative guard — a `:rf.machine/transition` trace with
            NO structured `:cascade` (older trace / 5-arg fixture) leaves the
            transition row's `:cascade` nil, so the view falls back to the
            `{from}→{to}` summary. The cascade-grouping helpers degrade to
            empty / nil."
    (let [evs [(machine-transition-ev :ws/conn
                                       {:state [:idle]   :data {}}
                                       {:state [:active] :data {}}
                                       [:ws/start] 1) ;; 5-arg: NO :cascade
               (machine-action-ev :open-socket :entry :ok)]
          r   (proj/handler-row evs :ws/start)
          mt  (-> r :machine :transition)
          tx  (first (filterv #(= :transition (:kind %)) (-> r :machine :cascade)))]
      (is (nil? (:cascade mt))
          "no structured cascade on the summary row → fall back to summary")
      (is (nil? (:cascade tx))
          "no structured cascade on the transition cascade row")
      (is (= [] (proj/cascade-regions nil)))
      (is (= [] (proj/cascade-microsteps nil)))
      (is (false? (proj/parallel-cascade? nil))))))

(deftest cascade-row-label-test
  (testing "rf2-u69j7 — `cascade-row-label` renders a human verb per kind"
    (is (= "guard :ready?"
           (fmt/cascade-row-label {:kind :guard :guard-id :ready?})))
    ;; rf2-nhovk — the ACTION verb is JUST the action-id; the kind-pill +
    ;; phase chip already convey kind + phase, so the redundant
    ;; "{phase} action " prefix is dropped (empty for an anonymous action).
    (is (= ":open-socket"
           (fmt/cascade-row-label {:kind :action :action-id :open-socket
                                    :phase :entry})))
    (is (= ""
           (fmt/cascade-row-label {:kind :action :phase :exit}))
        "anonymous action → empty verb; pill + chip + source body carry it")
    (is (= "timer [:idle] · on-exit"
           (fmt/cascade-row-label {:kind :timer :state [:idle]
                                    :reason :on-exit})))
    ;; rf2-ge6uj ISSUE 3 — the transition label is JUST the state change
    ;; `<from> → <to>`; the redundant "transition" word + machine-name
    ;; echo are dropped (the KIND pill + cascade context carry those).
    (is (= "[:idle] → [:connecting]"
           (fmt/cascade-row-label {:kind :transition
                                    :machine-id :ws/conn
                                    :from-state [:idle]
                                    :to-state   [:connecting]})))))

(deftest cascade-row-source-key-test
  (testing "rf2-u69j7 — `cascade-row-source-key` returns the spec-path
            tuple for source-coord lookup (named cases)"
    (is (= [:actions :open-socket]
           (fmt/cascade-row-source-key
             {:kind :action :action-id :open-socket})))
    (is (= [:guards :ready?]
           (fmt/cascade-row-source-key
             {:kind :guard :guard-id :ready?})))
    (is (nil? (fmt/cascade-row-source-key {:kind :transition}))
        "transitions with no state/event context → nil")
    (is (nil? (fmt/cascade-row-source-key {:kind :timer}))
        "timers with no state context → nil")))

;; ---- rf2-wwc3j — inline-fn / transition / timer source-key extensions -----

(deftest cascade-row-source-key-inline-entry-action-test
  (testing "rf2-wwc3j — inline-fn `:entry` action resolves to its
            target-state's `[:states <s> :entry]` slot"
    (let [inline-fn (fn [_] {})]
      (is (= [:states :connected :entry]
             (fmt/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :entry
                :target-state :connected}))
          "flat machine: entry action under target-state slot")
      (is (= [:states :connected :entry]
             (fmt/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :entry
                :target-state [:connected]}))
          "vector target-state coerces to the same path")
      (is (= [:states :outer :states :inner :entry]
             (fmt/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :entry
                :target-state [:outer :inner]}))
          "hierarchical target-state expands to nested :states path"))))

(deftest cascade-row-source-key-inline-exit-action-test
  (testing "rf2-wwc3j — inline-fn `:exit` action resolves to its
            source-state's `[:states <s> :exit]` slot"
    (let [inline-fn (fn [_] {})]
      (is (= [:states :idle :exit]
             (fmt/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :exit
                :source-state :idle})))
      (is (= [:states :idle :exit]
             (fmt/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :destroy-exit
                :source-state :idle}))
          ":destroy-exit phase also maps to the :exit slot"))))

(deftest cascade-row-source-key-inline-transition-action-test
  (testing "rf2-wwc3j — inline-fn transition `:action` resolves to
            `[:states <src> :on <event> :action]`"
    (let [inline-fn (fn [_] {})]
      (is (= [:states :idle :on :submit :action]
             (fmt/cascade-row-source-key
               {:kind :action :action-id inline-fn :phase :transition
                :source-state :idle :event-id :submit}))))))

(deftest cascade-row-source-key-inline-guard-test
  (testing "rf2-wwc3j — inline-fn `:guard` resolves to
            `[:states <src> :on <event> :guard]`"
    (let [inline-fn (fn [_] true)]
      (is (= [:states :idle :on :submit :guard]
             (fmt/cascade-row-source-key
               {:kind :guard :guard-id inline-fn
                :source-state :idle :event-id :submit}))
          "inline guard on a state's :on transition")
      (is (nil? (fmt/cascade-row-source-key
                  {:kind :guard :guard-id inline-fn
                   :source-state :idle}))
          "missing event-id → nil (the source-key cannot be built)"))))

(deftest cascade-row-source-key-transition-row-test
  (testing "rf2-wwc3j — `:transition` row resolves to `[:states <src>
            :on <event>]` so the click-through opens the transition map
            literal in the spec"
    (is (= [:states :idle :on :submit]
           (fmt/cascade-row-source-key
             {:kind :transition :source-state :idle :event-id :submit})))
    (is (= [:states :outer :states :inner :on :go]
           (fmt/cascade-row-source-key
             {:kind :transition :source-state [:outer :inner]
              :event-id :go}))
        "hierarchical from-state expands to nested :states path")
    (is (nil? (fmt/cascade-row-source-key
                {:kind :transition :source-state :idle}))
        "missing event-id → nil")))

(deftest cascade-row-source-key-timer-row-test
  (testing "rf2-wwc3j — `:timer` row resolves to `[:states <state>]`
            (D1 minimum-viable: parent state's source-coord chip)"
    (is (= [:states :idle]
           (fmt/cascade-row-source-key
             {:kind :timer :state :idle})))
    (is (= [:states :idle]
           (fmt/cascade-row-source-key
             {:kind :timer :state [:idle]})))
    (is (nil? (fmt/cascade-row-source-key {:kind :timer}))
        "missing state → nil")))

(deftest cascade-row-source-key-named-shadows-inline-test
  (testing "rf2-wwc3j — a named action-id keyword always wins over the
            inline derivation (the existing definition-site path covers
            the named case end-to-end)"
    (is (= [:actions :open-socket]
           (fmt/cascade-row-source-key
             {:kind :action :action-id :open-socket :phase :entry
              :target-state :connected}))
        ":action-id keyword → definition-site path, ignores :phase / :target-state")
    (is (= [:guards :ready?]
           (fmt/cascade-row-source-key
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

(deftest machine-cascade-rows-post-transition-row-falls-back-to-prior-test
  (testing "rf2-w6yfq — when rows trail BEHIND the last transition
            (post-commit timer-cancels), `enrich-cascade-rows` falls
            back to the most recent preceding transition's
            :from-state / :to-state / :event. Pins the two-pass
            (right-to-left then left-to-right) shape that replaced the
            O(n²) forward-scan."
    (let [evs [(machine-guard-ev :ready? :pass)
               (machine-transition-ev :ws/conn
                                       {:state :idle :data {}}
                                       {:state :connected :data {}}
                                       [:ws/start] 0)
               ;; Post-commit timer-cancel — no transition ahead.
               (machine-timer-cancel-ev :ws/conn [:idle] 250 :on-exit)]
          rows (proj/machine-cascade-rows evs)]
      ;; Pre-transition guard row → next-ahead supplies the transition
      ;; states.
      (is (= :idle      (-> rows (nth 0) :source-state)))
      (is (= :connected (-> rows (nth 0) :target-state)))
      ;; The transition row itself stamps from its own slots.
      (is (= :idle      (-> rows (nth 1) :source-state)))
      (is (= :connected (-> rows (nth 1) :target-state)))
      ;; Post-transition timer-cancel — no next-ahead transition;
      ;; falls back to `prior` (the preceding transition row). This
      ;; is the exact path rf2-w6yfq tightened from O(n²) to O(n).
      (is (= :idle      (-> rows (nth 2) :source-state))
          "post-transition row inherits :source-state from the preceding transition (prior fallback)")
      (is (= :connected (-> rows (nth 2) :target-state))
          "post-transition row inherits :target-state from the preceding transition (prior fallback)")
      (is (= :ws/start  (-> rows (nth 2) :event-id))
          "post-transition row inherits :event-id from the preceding transition"))))

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

(deftest state-node-source-coords-test
  (testing "rf2-vqja2 — `state-node-source-coords` reads the co-located
            `:source-coords` off the MAP node at a spec-path, and walks UP
            to the nearest enclosing map for inline-fn slot keys (which
            hold a value, not a map)"
    (let [spec {:initial :active
                :states  {:active {:source-coords {:file "a.cljs" :line 10}
                                   :on {:go {:target :done
                                             :action (fn [_] {})
                                             :source-coords {:file "a.cljs" :line 12}}}
                                   :states {:auth {:source-coords {:file "a.cljs" :line 20}}}}
                          :done   {:source-coords {:file "a.cljs" :line 30}}}}]
      ;; State-node: direct hit on its co-located coord.
      (is (= {:file "a.cljs" :line 10}
             (proj/state-node-source-coords spec [:states :active]))
          "state-node coord read directly off the node")
      ;; Nested state-node: direct hit at the recursive path.
      (is (= {:file "a.cljs" :line 20}
             (proj/state-node-source-coords spec [:states :active :states :auth]))
          "nested state-node coord read at the recursive path")
      ;; Transition map: direct hit on its co-located coord.
      (is (= {:file "a.cljs" :line 12}
             (proj/state-node-source-coords spec [:states :active :on :go]))
          "transition map coord read directly off the transition map")
      ;; Inline-fn slot key (`:action` holds a fn): walk UP to the
      ;; enclosing transition map's coord.
      (is (= {:file "a.cljs" :line 12}
             (proj/state-node-source-coords spec [:states :active :on :go :action]))
          "inline-fn :action slot resolves to its enclosing transition coord")
      ;; A state-node with no coord (e.g. an inline-fn :entry slot directly
      ;; on a node with no coord) walks up; here :done has its own coord.
      (is (= {:file "a.cljs" :line 30}
             (proj/state-node-source-coords spec [:states :done :entry]))
          "inline slot on a coord-bearing state-node resolves to that node")
      ;; No coord anywhere on the path → nil (graceful degrade).
      (is (nil? (proj/state-node-source-coords {:states {:x {}}} [:states :x :entry]))
          "no co-located coord on any path node → nil")
      (is (nil? (proj/state-node-source-coords spec nil)) "nil path → nil")
      (is (nil? (proj/state-node-source-coords nil [:states :active])) "nil spec → nil"))))

(deftest cascade-outcome-label-test
  (testing "rf2-u69j7 — `cascade-outcome-label` renders kind-specific
            outcome strings"
    (is (= "pass"  (fmt/cascade-outcome-label {:kind :guard :outcome :pass})))
    (is (= "fail"  (fmt/cascade-outcome-label {:kind :guard :outcome :fail})))
    (is (= "threw" (fmt/cascade-outcome-label {:kind :guard :outcome :threw})))
    (is (= "ok"    (fmt/cascade-outcome-label {:kind :action :outcome :ok})))
    (is (= "threw" (fmt/cascade-outcome-label
                     {:kind :action :threw? true :outcome :rf.error/action-threw})))
    (is (= "1 microstep"
           (fmt/cascade-outcome-label {:kind :transition :microsteps 1})))
    (is (= "3 microsteps"
           (fmt/cascade-outcome-label {:kind :transition :microsteps 3})))
    (is (= "cancelled (on-exit)"
           (fmt/cascade-outcome-label {:kind :timer :reason :on-exit})))))

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

;; ---- t1 / t2 db attribution (rf2-4wywy) ---------------------------------
;;
;; standard-epochs button 5 (`:standard-epochs/increment-flow`): the handler bumps
;; `:base`; the `:standard-epochs/derived` flow then recomputes `:derived =
;; 2 × :base` into app-db AFTER the handler. The HANDLER step's `:db` must
;; reflect ONLY the handler's change (`:base` bumped, NO `:derived`); the
;; FLOW step must show the flow's OWN contribution (`:derived` recomputed)
;; as a SEPARATE `:db` diff. The two must not be conflated.
;;
;; The fix reads the t1 (`:rf.event/db-pending`, post-handler/pre-flow) +
;; t2 (`:rf.event/db-pending-post-flow`, post-flow) snapshots off the trace
;; stream (rf2-ta0y7). The epoch record's `:db-after` is the FINAL
;; post-flow state — reading it for the HANDLER step was the bug.

(deftest db-pending-t1-t2-readers-test
  (testing "rf2-4wywy — t1 reader pulls the post-handler db off
            `:rf.event/db-pending`'s `:rf.event/db` tag"
    (is (= {:base 2 :baseline 1}
           (proj/db-pending-t1 [(db-pending-ev {:base 2 :baseline 1})])))
    (is (nil? (proj/db-pending-t1 []))
        "absent t1 → nil (caller falls back to record :db-after)"))
  (testing "rf2-4wywy — t2 reader pulls the post-flow db off
            `:rf.event/db-pending-post-flow`'s `:rf.event/db` tag"
    (is (= {:base 2 :baseline 1 :derived 4}
           (proj/db-pending-t2 [(db-pending-post-flow-ev {:base 2 :baseline 1 :derived 4})])))
    (is (nil? (proj/db-pending-t2 []))
        "absent t2 → nil (no flow changed :db this epoch)")))

(deftest handler-step-db-reflects-post-handler-not-post-flow-test
  (testing "rf2-4wywy ACCEPTANCE — the HANDLER step's `:db` reflects ONLY
            the handler's change (post-handler / t1). The epoch record's
            `:db-after` carries the FLOW-augmented `:derived` slot, but the
            HANDLER step must NOT surface it — `:db-post-handler` (t1) is
            the authoritative HANDLER `:db`."
    (let [t1     {:base 2 :baseline 1 :derived 2}  ; post-handler: :base/:baseline bumped, :derived UNTOUCHED (still 2)
          t2     {:base 2 :baseline 1 :derived 4}  ; post-flow: :derived recomputed 2 → 4
          record {:event-id     :standard-epochs/increment-flow
                  :db-before    {:base 1 :baseline 0 :derived 2}
                  ;; the record's :db-after is the FINAL post-flow state
                  :db-after     t2
                  :trace-events [(dispatched-ev [:standard-epochs/increment-flow])
                                 (db-pending-ev t1)
                                 (flow-recomputed-ev :standard-epochs/derived [:derived] 2 4)
                                 (db-pending-post-flow-ev t2)
                                 (run-end-ev 0.3)]}
          steps  (proj/project record)
          h      (first (filter #(= :handler (:step %)) steps))]
      (is (some? h) "HANDLER step present")
      (is (= t1 (:db-post-handler h))
          "HANDLER `:db-post-handler` is the t1 (post-handler / pre-flow) db")
      (is (= 2 (:derived (:db-post-handler h)))
          "the HANDLER step's :derived is the PRE-flow value (2), NOT the
           flow's recomputed value (4) — the handler did not touch it")
      ;; rf2-sp0n9 — the view diffs `:db-post-handler` (t1) against
      ;; `:db-before`, so the HANDLER step shows ONLY the handler's :base
      ;; bump; the flow's :derived recompute belongs to the FLOW step.
      (is (= 2 (:base (:db-post-handler h)))
          "the HANDLER step's effective post-handler db carries the
           handler's :base bump (1 → 2)"))))

(deftest flow-step-carries-its-own-db-diff-snapshots-test
  (testing "rf2-4wywy ACCEPTANCE — the FLOW step carries the t1 (pre-flow)
            + t2 (post-flow) db snapshots so the view renders the flow's
            OWN `:db` diff (`:derived` recomputed) separately from the
            handler's change."
    (let [t1     {:base 2 :baseline 1 :derived 2}  ; pre-flow: :derived still 2
          t2     {:base 2 :baseline 1 :derived 4}  ; post-flow: :derived recomputed
          record {:event-id     :standard-epochs/increment-flow
                  :db-before    {:base 1 :baseline 0 :derived 2}
                  :db-after     t2
                  :trace-events [(dispatched-ev [:standard-epochs/increment-flow])
                                 (db-pending-ev t1)
                                 (flow-recomputed-ev :standard-epochs/derived [:derived] 2 4)
                                 (db-pending-post-flow-ev t2)
                                 (run-end-ev 0.3)]}
          steps  (proj/project record)
          flows  (filter #(= :flow (:step %)) steps)
          f      (first flows)]
      (is (= 1 (count flows)) "one FLOW step for the single recompute")
      (is (= :standard-epochs/derived (:flow-id f)))
      (is (= [:derived] (:path f)))
      (is (= t1 (:db-pre-flow f))
          "FLOW step carries t1 (pre-flow) so the view diff's `:before` =
           the db BEFORE this flow's write")
      (is (= t2 (:db-post-flow f))
          "FLOW step carries t2 (post-flow) so the view diff's value =
           the db WITH this flow's write")
      ;; The t1→t2 reshape IS the flow's contribution: :derived 2 → 4.
      (is (= 2 (:derived (:db-pre-flow f)))
          "pre-flow db carries :derived at its PRE-recompute value (2)")
      (is (= 4 (:derived (:db-post-flow f)))
          "post-flow db carries :derived = 2 × :base = 4"))))

(deftest handler-step-db-falls-back-to-record-when-no-t1-test
  (testing "rf2-4wywy — graceful fallback: when no t1 fired (handler
            returned no `:db`, or a pre-rf2-ta0y7 runtime) the HANDLER
            step carries no `:db-post-handler`; the view falls back to
            the record's `:db-after` and diffs it against `:db-before`."
    (let [record {:event-id     :legacy/no-t1
                  :db-before    {:counter 1}
                  :db-after     {:counter 2}
                  :trace-events [(dispatched-ev [:legacy/no-t1])
                                 (run-end-ev 0.2)]}
          steps  (proj/project record)
          h      (first (filter #(= :handler (:step %)) steps))]
      (is (some? h))
      (is (nil? (:db-post-handler h))
          "no t1 on the stream → :db-post-handler absent (view falls
           back to the record's :db-after)"))))

(deftest flow-step-falls-back-to-scalar-when-no-snapshots-test
  (testing "rf2-4wywy — when no t1/t2 snapshots rode the stream (pre-
            rf2-ta0y7 fixture) the FLOW step carries no `:db-pre-flow` /
            `:db-post-flow`; the view renders the legacy scalar
            before→after line. Projection-side: the slots are simply
            absent."
    (let [record {:event-id     :counter/inc
                  :trace-events [(dispatched-ev [:counter/inc])
                                 (flow-recomputed-ev :total-parity [:total] 5 6)]}
          steps  (proj/project record)
          f      (first (filter #(= :flow (:step %)) steps))]
      (is (some? f))
      (is (not (contains? f :db-pre-flow))
          "no t1 → :db-pre-flow absent (view falls back to scalar line)")
      (is (not (contains? f :db-post-flow))
          "no t2 → :db-post-flow absent")
      ;; the per-path scalar slots survive for the fallback rendering
      (is (= [:total] (:path f)))
      (is (= 5 (:before f)))
      (is (= 6 (:after f))))))

;; ---- no-`:db`-effect-but-has-flow edge case (rf2-48oc4) -----------------
;;
;; A handler can return NO `:db` effect yet still trigger a flow. The
;; substrate then stamps NO t1 (`:rf.event/db-pending` fires only
;; `(when has-db?)` — router `flows-after-interceptor`, rf2-ta0y7) but
;; DOES stamp t2 (`:rf.event/db-pending-post-flow`) with the flow-augmented
;; db (a flow synthesised one from app-db and changed it). The post-handler
;; db here equals `db-before` (the handler wrote nothing). The HANDLER step
;; must show NO `:db` change; the FLOW step must diff the flow's
;; contribution against `db-before` — NOT fall back to the scalar line, and
;; NOT attribute the flow's change to the handler.

(deftest no-db-effect-with-flow-discriminator-test
  (testing "rf2-48oc4 — `no-db-effect-with-flow?` is true iff no t1 but a
            t2 fired (handler wrote no `:db`, a flow synthesised + changed
            one)"
    (is (true? (proj/no-db-effect-with-flow?
                 [(db-pending-post-flow-ev {:a 1 :derived 2})]))
        "no t1 + t2 present → true")
    (is (false? (proj/no-db-effect-with-flow?
                  [(db-pending-ev {:a 1})
                   (db-pending-post-flow-ev {:a 1 :derived 2})]))
        "t1 present → false (handler DID return :db; standard case)")
    (is (false? (proj/no-db-effect-with-flow? []))
        "neither t1 nor t2 → false (no flow / pre-rf2-ta0y7)")))

(deftest effective-post-handler-db-resolution-test
  (testing "rf2-48oc4 — `effective-post-handler-db` resolution order"
    (is (= {:a 1} (proj/effective-post-handler-db
                    [(db-pending-ev {:a 1})] {:a 0}))
        "t1 present → t1 (handler-supplied db), regardless of db-before")
    (is (= {:a 0} (proj/effective-post-handler-db
                    [(db-pending-post-flow-ev {:a 0 :derived 9})] {:a 0}))
        "no t1 + t2 (no-:db-with-flow) → db-before (the actual
         post-handler db; handler wrote nothing)")
    (is (nil? (proj/effective-post-handler-db [] {:a 0}))
        "neither t1 nor t2 → nil (caller falls back to record :db-after);
         the pre-rf2-ta0y7 / no-flow path is left to the legacy fallback")))

(deftest handler-step-shows-no-db-change-when-handler-wrote-no-db-test
  (testing "rf2-48oc4 ACCEPTANCE (b) — when the handler returned NO `:db`
            but a flow fired, the HANDLER step shows NO `:db` change: the
            effective post-handler db equals `db-before` (NOT the
            flow-augmented post-flow state), so the view's diff against
            `:db-before` is empty."
    (let [db-before {:base 1 :derived 2}
          ;; handler wrote no :db → t1 absent. The flow recomputes
          ;; :derived from :base into app-db → t2 fires with the augmented db.
          t2        {:base 1 :derived 4}
          record    {:event-id     :synthetic/flow-only
                     :db-before    db-before
                     :db-after     t2
                     :trace-events [(dispatched-ev [:synthetic/flow-only])
                                    ;; NO db-pending-ev — the handler
                                    ;; returned no :db effect.
                                    (flow-recomputed-ev :synthetic/derived [:derived] 2 4)
                                    (db-pending-post-flow-ev t2)
                                    (run-end-ev 0.2)]}
          steps     (proj/project record)
          h         (first (filter #(= :handler (:step %)) steps))]
      (is (some? h) "HANDLER step present")
      (is (= db-before (:db-post-handler h))
          "the HANDLER step's effective post-handler db = db-before (the
           handler wrote nothing); NOT the post-flow t2")
      (is (= 2 (:derived (:db-post-handler h)))
          "rf2-sp0n9 — the flow's :derived recompute (2 → 4) does NOT leak
           into the HANDLER step's effective db; it stays at the pre-flow 2"))))

(deftest flow-step-diffs-against-db-before-when-handler-wrote-no-db-test
  (testing "rf2-48oc4 ACCEPTANCE (a) — when the handler returned NO `:db`
            but a flow fired, the FLOW step's diff baseline is the ACTUAL
            post-handler db (= db-before), threaded as `:db-pre-flow`; the
            POST endpoint is t2 (`:db-post-flow`). The step renders a real
            `:db` diff rather than the scalar fallback."
    (let [db-before {:base 1 :derived 2}
          t2        {:base 1 :derived 4}
          record    {:event-id     :synthetic/flow-only
                     :db-before    db-before
                     :db-after     t2
                     :trace-events [(dispatched-ev [:synthetic/flow-only])
                                    (flow-recomputed-ev :synthetic/derived [:derived] 2 4)
                                    (db-pending-post-flow-ev t2)
                                    (run-end-ev 0.2)]}
          steps     (proj/project record)
          f         (first (filter #(= :flow (:step %)) steps))]
      (is (some? f) "FLOW step present")
      (is (= :synthetic/derived (:flow-id f)))
      (is (= [:derived] (:path f)))
      (is (= db-before (:db-pre-flow f))
          "FLOW diff PRE endpoint = the actual post-handler db (db-before)
           — NOT nil, so the view renders a real :db diff, not the scalar
           fallback")
      (is (= t2 (:db-post-flow f))
          "FLOW diff POST endpoint = t2 (what the flow returned)")
      ;; The t1(=db-before)→t2 reshape IS the flow's contribution.
      (is (= 2 (:derived (:db-pre-flow f)))
          "pre-flow :derived = its value before the flow recomputed it")
      (is (= 4 (:derived (:db-post-flow f)))
          "post-flow :derived = the flow's recomputed value"))))

;; ---- SIDE EFFECTS step — flat ledger (rf2-j630b) ------------------------
;;
;; The rf2-kt6js 3-tier `:db` / `:fx` / other sub-step presentation became
;; a FLAT per-effect ledger (rf2-j630b): `proj/side-effects-step` returns
;; ONE `:rows` vec in EXECUTION order — synthesised `:db` row first (when
;; present), then the `:fx`-vector rows in order, then `other` rows. NO
;; `:sub-kinds` slot. The single badge status is `proj/side-effects-badge-
;; status` (AND-of-rows; SKIPPED neutral); each row keeps the rf2-ahhgn
;; `:status`. See the projection ns's SIDE EFFECTS settle-first note for
;; what's recorded vs derived.

(defn- ids-of
  "The `:fx-id`s of a projected `side-effects-step`'s flat `:rows`, in
  ledger (execution) order."
  [step]
  (mapv :fx-id (:rows step)))

(defn- row-with-id
  "The flat ledger row whose `:fx-id` = `id`, or nil."
  [step id]
  (some #(when (= id (:fx-id %)) %) (:rows step)))

(deftest side-effects-step-conditional-test
  (testing "no side effect at all → step is OMITTED"
    (is (nil? (proj/side-effects-step []))))

  (testing "rf2-j630b — :fx-vector entries → SIDE EFFECTS step with one
            flat row per fx in execution order (no :db row when no commit)"
    (let [s (proj/side-effects-step
              [(fx-handled-ev :http/post {:url "/x"} 12.0)
               (fx-handled-ev :navigate {:to :home} 0.4)])]
      (is (= :side-effects (:step s)))
      (is (= :SIDE-EFFECTS (:badge s)))
      (is (= [:http/post :navigate] (ids-of s)) "flat rows in :fx order")
      (is (not-any? #{:db} (ids-of s)) "no :db commit → no :db row")
      (is (= :ok (proj/side-effects-badge-status (:rows s)))
          "all fx ran → badge :ok")
      (is (= :ok (-> s :rows first :status))))))

;; ---- :db row — FIRST, pass / schema-fail (rf2-j630b) --------------------

(deftest side-effects-db-row-first-and-pass-test
  (testing "rf2-j630b — a bare reg-event-db (only :db, NO :fx) STILL shows
            the SIDE EFFECTS step with a passing :db row, FIRST in the
            ledger. The :db commit is keyed off `:rf.event/db-changed` —
            the ALWAYS-APPEARS contract."
    (let [s (proj/side-effects-step [(db-changed-ev [[[:counter] 0 1 :edit]])])]
      (is (= :side-effects (:step s)) "SIDE EFFECTS step present on a bare :db")
      (is (= [:db] (ids-of s)) "the only row is the :db row")
      (is (= :ok (-> (row-with-id s :db) :status)) ":db committed → ✓")
      (is (= :ok (proj/side-effects-badge-status (:rows s)))) ))

  (testing "rf2-j630b — :db row leads, then the :fx rows in order"
    (let [s (proj/side-effects-step
              [(do-fx-ev {:db {:n 1} :fx [[:http/post {}] [:navigate {}]]})
               (db-changed-ev [[[:n] 0 1 :edit]])
               (fx-handled-ev :http/post {} 1.0)
               (fx-handled-ev :navigate {} 0.2)])]
      (is (= [:db :http/post :navigate] (ids-of s))
          ":db first, then :fx vector in execution order"))))

(deftest side-effects-db-row-schema-fail-only-test
  (testing "rf2-j630b — a :db schema-fail (pre-commit transactional)
            rolls back BEFORE any :fx ran (atomicity): the ledger carries
            just the :db CROSS row + badge cross, NO fx rows"
    (let [s (proj/side-effects-step
              [(db-changed-ev [])
               (schema-violation-ev :app-db :counter/inc [:counter] -3 true)])]
      (is (= [:db] (ids-of s)) ":db-only ledger on a rolled-back commit")
      (is (= :error (-> (row-with-id s :db) :status)) ":db row ✗ on schema-fail")
      (is (= :error (proj/side-effects-badge-status (:rows s)))
          "badge cross when the :db row failed"))))

;; ---- per-row glyph + badge AND-of-rows (rf2-j630b) ----------------------

(deftest side-effects-per-row-status-test
  (testing "rf2-j630b — each :fx row carries a per-effect status:
            :ok ran / :error threw / :overridden / :skipped on-platform.
            Per-fx success is ALREADY RECORDED on the trace stream."
    (let [s  (proj/side-effects-step
               [(fx-handled-ev :http/post {} 1.0)
                (ev :rf.fx :rf.fx/override-applied {:rf.fx/id :metrics})
                (ev :warning :rf.fx/skipped-on-platform {:rf.fx/id :clipboard})
                (ev :error :rf.error/fx-handler-exception {:rf.fx/id :bad-fx})])
          by (into {} (map (juxt :fx-id :status) (:rows s)))]
      (is (= 4 (count (:rows s))))
      (is (= :ok         (:http/post by)) ":rf.fx/handled → :ok")
      (is (= :overridden (:metrics   by)) ":rf.fx/override-applied → :overridden")
      (is (= :skipped    (:clipboard by)) ":rf.fx/skipped-on-platform → :skipped")
      (is (= :error      (:bad-fx    by)) "fx-handler-exception → :error")
      (is (= 1 (:threw s)) "threw count = the one fx that threw"))))

(deftest side-effects-badge-and-of-rows-test
  (testing "rf2-j630b — the badge is the AND of the present rows: cross
            iff ≥1 real failure; SKIPPED rows are NEUTRAL (don't trip it)"
    ;; all ✓ → :ok
    (is (= :ok (proj/side-effects-badge-status
                 (:rows (proj/side-effects-step
                          [(fx-handled-ev :http/post {} 1.0)])))))
    ;; a SKIPPED row alongside an ✓ row stays :ok (skipped is neutral)
    (is (= :ok (proj/side-effects-badge-status
                 (:rows (proj/side-effects-step
                          [(fx-handled-ev :http/post {} 1.0)
                           (ev :warning :rf.fx/skipped-on-platform
                               {:rf.fx/id :clipboard})]))))
        "a skipped row is neutral — badge stays ✓")
    ;; one ✗ row trips the badge to cross even alongside ✓ + skipped rows
    (is (= :error (proj/side-effects-badge-status
                    (:rows (proj/side-effects-step
                             [(fx-handled-ev :http/post {} 1.0)
                              (ev :warning :rf.fx/skipped-on-platform
                                  {:rf.fx/id :clipboard})
                              (ev :error :rf.error/fx-handler-exception
                                  {:rf.fx/id :bad-fx})])))))
    ;; an attached exception (post-build) lifts the badge even if the row
    ;; status itself was :ok at build time
    (is (= :error (proj/side-effects-badge-status
                    [{:fx-id :http/get :status :ok
                      :errors [{:operation :rf.error/fx-handler-exception}]}]))
        "an attached :errors vec lifts the badge to cross")))

(deftest side-effects-fx-reads-canonical-elapsed-ms-test
  (testing "rf2-ipaza — :fx row duration resolves through the canonical
            `:rf.fx/elapsed-ms`; legacy `:duration-ms` is a fallback"
    (let [s (proj/side-effects-step
              [{:op-type :rf.fx :operation :rf.fx/handled
                :tags {:rf.fx/id :http/post :rf.fx/elapsed-ms 3.4}}])]
      (is (= 3.4 (-> s :rows first :duration-ms))))
    (let [s (proj/side-effects-step
              [{:op-type :rf.fx :operation :rf.fx/handled
                :tags {:rf.fx/id :http/get :duration-ms 7.7}}])]
      (is (= 7.7 (-> s :rows first :duration-ms))
          "legacy :duration-ms fallback retained for older fixtures"))))

(deftest side-effects-fx-attribution-from-machine-actions-test
  (testing "rf2-uffov — when a machine action's outcome :fx emits a
            fx-id, the corresponding :fx ledger row carries :attributed-to"
    (let [evs [(ev :rf.machine :rf.machine/action-ran
                   {:action-id :open-socket
                    :phase     :entry
                    :outcome   {:fx [[:http/get {:url "/x"}]]}
                    :input     {:data {} :event nil}})
               (fx-handled-ev :http/get {:url "/x"} 5.0)]
          row (row-with-id (proj/side-effects-step evs) :http/get)]
      (is (= :http/get (:fx-id row)))
      (is (= :open-socket (-> row :attributed-to :action-id)))
      (is (= :entry       (-> row :attributed-to :phase)))))

  (testing "rf2-uffov — pure reg-event-fx cascades have no per-action
            attribution; the slot stays absent"
    (let [row (-> (proj/side-effects-step [(fx-handled-ev :http/post {} 0.1)])
                  :rows first)]
      (is (not (contains? row :attributed-to))))))

;; ---- other (dropped top-level) rows (rf2-j630b) -------------------------

(deftest side-effects-other-rows-test
  (testing "rf2-j630b — a top-level effect key beyond :db/:fx on the
            handler's returned map is surfaced as a :skipped (not-run)
            DIAGNOSTIC row at the END of the ledger. re-frame2's effect
            map is the closed {:db :fx} shape — `run-fx-effects!` reads
            only :fx — so any other key is DROPPED (never executed)."
    (let [s    (proj/side-effects-step
                 [(do-fx-ev {:db {:n 1}
                             :fx [[:http/post {}]]
                             :legacy/persist {:to :disk}})
                  (db-changed-ev [])
                  (fx-handled-ev :http/post {} 1.0)])
          row  (row-with-id s :legacy/persist)]
      (is (= [:db :http/post :legacy/persist] (ids-of s))
          ":db first, :fx next, dropped `other` effect last")
      (is (= :skipped (:status row)) "dropped effect = :skipped")
      (is (= {:to :disk} (:value row)))
      (is (= :ok (proj/side-effects-badge-status (:rows s)))
          "a dropped (skipped) `other` row is neutral — badge stays ✓")))

  (testing "rf2-j630b — the canonical {:db :fx} shape yields NO `other`
            rows (the common case)"
    (let [s (proj/side-effects-step
              [(do-fx-ev {:db {:n 1} :fx [[:http/post {}]]})
               (db-changed-ev [])
               (fx-handled-ev :http/post {} 1.0)])]
      (is (= [:db :http/post] (ids-of s))
          "closed {:db :fx} shape → no `other` rows"))))

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

(deftest subscriptions-row-carries-first-run-flag-test
  (testing "rf2-fyd8u — projection lifts `:rf.sub/first-run?` onto
            each sub-run row as `:first-run?` so the view-side
            renderer can pick `:added` chrome (first-cache-entry) vs
            `← was X` annotation (value change against an existing
            cache entry) for leaf-scalar subs"
    (testing "first-run? true on the run that created the cache slot"
      (let [row (-> (proj/subscriptions-step
                      [(ev :rf.sub :rf.sub/run
                           {:rf.sub/id             :counter/last-clicked
                            :rf.sub/query-v        [:counter/last-clicked]
                            :rf.sub/value-changed? true
                            :rf.sub/first-run?     true
                            :rf.sub/prev-value     nil
                            :rf.sub/value          1779972561856})])
                    :rows first)]
        (is (true? (:first-run? row))
            ":first-run? is read from `:rf.sub/first-run?` (true case)")
        (is (true? (:changed? row))
            ":changed? still carries the value-changed signal")
        (is (= 1779972561856 (:after row)))))
    (testing "first-run? false on a subsequent recompute (existing slot)"
      (let [row (-> (proj/subscriptions-step
                      [(ev :rf.sub :rf.sub/run
                           {:rf.sub/id             :counter/value
                            :rf.sub/query-v        [:counter/value]
                            :rf.sub/value-changed? true
                            :rf.sub/first-run?     false
                            :rf.sub/prev-value     0
                            :rf.sub/value          1})])
                    :rows first)]
        (is (false? (:first-run? row))
            ":first-run? is read from `:rf.sub/first-run?` (false case)")
        (is (true? (:changed? row)))
        (is (= 0 (:before row)))
        (is (= 1 (:after row)))))
    (testing "absent `:rf.sub/first-run?` tag (legacy / pure compute-sub
              path) → row defaults to first-run? false"
      (let [row (-> (proj/subscriptions-step
                      [(sub-run-ev [:counter/total] true 5 6)])
                    :rows first)]
        (is (false? (:first-run? row))
            "no flag stamped → defaults to false (legacy path falls back
             to the value-change shape)")))))

(deftest subscriptions-row-carries-cause-event-id-test
  (testing "rf2-1cc03 — projection lifts `:rf.sub/cause-event-id` onto
            each sub-run row as `:cause-event-id` so the view-side
            renderer can paint a `caused by <event-id>` chrome
            attributing the sub-run to the dispatching cascade."
    (testing "cause-event-id PRESENT on a sub run inside an in-flight cascade"
      (let [row (-> (proj/subscriptions-step
                      [(ev :rf.sub :rf.sub/run
                           {:rf.sub/id              :counter/value
                            :rf.sub/query-v         [:counter/value]
                            :rf.sub/value-changed?  true
                            :rf.sub/prev-value      0
                            :rf.sub/value           1
                            :rf.sub/cause-event-id  :counter/inc})])
                    :rows first)]
        (is (= :counter/inc (:cause-event-id row))
            ":cause-event-id is read from `:rf.sub/cause-event-id` tag")
        (is (true? (:changed? row))
            ":changed? still carries the value-changed signal")
        (is (= 1 (:after row))
            "other slots ride alongside the new attribution slot")))
    (testing "cause-event-id ABSENT (post-settle reactive flush, no
              live cascade) → row slot is OMITTED (not nil-bearing)"
      (let [row (-> (proj/subscriptions-step
                      [(ev :rf.sub :rf.sub/run
                           {:rf.sub/id             :counter/value
                            :rf.sub/query-v        [:counter/value]
                            :rf.sub/value-changed? true
                            :rf.sub/prev-value     0
                            :rf.sub/value          1})])
                    :rows first)]
        (is (not (contains? row :cause-event-id))
            ":cause-event-id key is ABSENT when the trace tag was omitted
             (parity with the rf2-okz1u OMIT-vs-nil semantics — the row
             stays minimal so consumers can `(some? (:cause-event-id row))`
             cleanly)")
        (is (true? (:changed? row))
            "the sub-run row still projects (the absence is in the
             attribution slot only, not the whole row)")))))

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
      (is (= ::counter-view (-> s :rows first :view-id)))
      (is (= :rendered (-> s :rows first :status))
          "rf2-3b9w4 — a rendered row carries :status :rendered"))))

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

(deftest render-cause-classifier-test
  (testing "rf2-bhi3t — `render-cause` classifies WHY a view rendered
            purely from the substrate's `:rf.view/mount?` +
            `:rf.view/triggered-by` slots"
    (testing "first render → :mount (mount? true wins even with a
              triggered-by present)"
      (is (= :mount (proj/render-cause true nil)))
      (is (= :mount (proj/render-cause true :counter/total))))

    (testing "re-render whose deref'd sub changed value → {:kind :sub
              :sub-id <id>}"
      (is (= {:kind :sub :sub-id :counter/total}
             (proj/render-cause false :counter/total)))
      (is (= {:kind :sub :sub-id [:counter/total 7]}
             (proj/render-cause false [:counter/total 7]))))

    (testing "re-render with NO own sub change → :props (the orthogonal
              :rf/props channel — the view re-rendered anyway)"
      (is (= :props (proj/render-cause false nil)))
      ;; nil mount? (absent slot) defaults to the re-render branch
      (is (= :props (proj/render-cause nil nil))))))

(deftest views-step-attributes-render-cause-test
  (testing "rf2-bhi3t — each view-row carries a `:cause` attributing the
            re-render to a sub-change vs a props-change. A view re-renders
            for exactly one of two reasons (a deref'd sub changed, or its
            props changed); the row makes that the first-class answer."
    (let [s    (proj/views-step
                 [;; Child A: re-rendered because :child-a/value changed
                  (view-render-ev :app/ChildA [[:child-a/value]] 0.4
                                  {:triggered-by :child-a/value})
                  ;; Child B: re-rendered with NO own sub change → props
                  (view-render-ev :app/ChildB [[:child-b/label]] 0.3 {})
                  ;; Fresh mount → :mount (not a re-render cause)
                  (view-render-ev :app/ChildC [] 0.2 {:mount? true})])
          rows (:rows s)
          [a b c] rows]
      (is (= 3 (count rows)))
      (is (= {:kind :sub :sub-id :child-a/value} (:cause a))
          "sub-driven re-render attributes to the cause sub")
      (is (= :props (:cause b))
          "props-driven re-render (no own sub changed) attributes to props")
      (is (= :mount (:cause c))
          "fresh mount carries :mount, not a re-render cause"))))

(deftest unmounted-views-rows-test
  (testing "rf2-gmw1i / rf2-3b9w4 — `unmounted-views-rows` projects each
            `:rf.view/unmounted` trace event into a row with `:view-id`,
            `:instance`, `:frame`, and the rf2-3b9w4 `:status :unmounted`
            + `:unmounted? true` markers so it can ride the same
            views-table as the rendered rows (red strikethrough)"
    (let [rows (proj/unmounted-views-rows
                 [(view-unmounted-ev :app/Counter [:Counter 0] :rf/default)
                  (view-unmounted-ev :app/Sidebar [:Sidebar 0] :rf/default)])]
      (is (= 2 (count rows)))
      (is (= :app/Counter (-> rows first :view-id)))
      (is (= [:Counter 0] (-> rows first :instance)))
      (is (= :rf/default (-> rows first :frame)))
      (is (= :unmounted (-> rows first :status)))
      (is (true? (-> rows first :unmounted?)))
      (is (= [] (-> rows first :subs-read))
          "an unmounted instance dereffed nothing this cascade")
      (is (= :app/Sidebar (-> rows second :view-id)))))

  (testing "rf2-gmw1i — no `:rf.view/unmounted` events → empty vec"
    (is (= [] (proj/unmounted-views-rows
                [(view-render-ev :app/Counter [])])))))

(deftest views-step-folds-unmounted-into-rows-test
  (testing "rf2-3b9w4 (SUPERSEDES rf2-gmw1i :unmounted-rows sub-section) —
            `views-step` folds unmounted rows into the SAME `:rows`
            (rendered first, unmounted following) + carries
            `:unmounted-count`"
    (let [s    (proj/views-step
                 [(view-render-ev :app/Counter [])
                  (view-unmounted-ev :app/SidebarItem [:SidebarItem 0]
                                     :rf/default)])
          rows (:rows s)]
      (is (= 2 (count rows)) "rendered + unmounted ride in one collection")
      (is (= :rendered (-> rows first :status)) "rendered rows come first")
      (is (= :unmounted (-> rows second :status)) "unmounted rows follow")
      (is (= :app/SidebarItem (-> rows second :view-id)))
      (is (= 1 (:unmounted-count s)) "tail count for the header verb")
      (is (not (contains? s :unmounted-rows))
          "the separate :unmounted-rows slot is RETIRED")))

  (testing "rf2-3b9w4 — unmount-only cascade (no renders) → step still
            present; `:rows` is the unmounted rows; `:unmounted-count`
            equals the row count"
    (let [s (proj/views-step
              [(view-unmounted-ev :app/Tooltip [:Tooltip 0] :rf/default)])]
      (is (some? s) "step rendered even when no re-renders fired")
      (is (= :views (:step s)))
      (is (= 1 (count (:rows s))))
      (is (= :unmounted (-> s :rows first :status)))
      (is (= 1 (:unmounted-count s)))))

  (testing "rf2-3b9w4 — no view events at all → step OMITTED"
    (is (nil? (proj/views-step []))))

  (testing "rf2-3b9w4 — only re-renders, no unmounts → `:unmounted-count`
            slot ABSENT (omit-by-absence)"
    (let [s (proj/views-step [(view-render-ev :app/Counter [])])]
      (is (not (contains? s :unmounted-count))
          "absent slot conveys absence")
      (is (= 1 (count (:rows s)))))))

(deftest views-row-sub-status-join-test
  (testing "rf2-3b9w4 — `view-rows` joins each dereffed sub against the
            epoch's `subscription-rows` to colour-code col-3: :new
            (first-run this epoch) / :changed (value changed) /
            :unchanged. Keyed by the SAME value the cell renders."
    (let [events [;; a fresh-cache sub (first-run) → :new
                  (assoc-in (sub-run-ev [:counter/total] false nil 5)
                            [:tags :rf.sub/first-run?] true)
                  ;; a recomputed-changed sub → :changed
                  (sub-run-ev [:counter/parity] true 0 1)
                  ;; a ran-but-unchanged sub → :unchanged
                  (sub-run-ev [:counter/label] false "n" "n")
                  ;; the view dereffed all three
                  (view-render-ev :app/Counter
                                  [[:counter/total] [:counter/parity]
                                   [:counter/label]])]
          idx    (proj/sub-status-index events)]
      (is (= :new       (get idx [:counter/total])))
      (is (= :changed   (get idx [:counter/parity])))
      (is (= :unchanged (get idx [:counter/label])))
      ;; indexed under the bare sub-id too
      (is (= :new       (get idx :counter/total)))
      (let [row (-> (proj/views-step events) :rows first)]
        (is (= :new       (get-in row [:sub-status [:counter/total]])))
        (is (= :changed   (get-in row [:sub-status [:counter/parity]])))
        (is (= :unchanged (get-in row [:sub-status [:counter/label]]))))))

  (testing "rf2-3b9w4 — a sub the view read but that ran outside the
            captured run-set is absent from `:sub-status` (the cell
            defaults it to grey/unchanged)"
    (let [row (-> (proj/views-step
                    [(view-render-ev :app/Counter [[:counter/orphan]])])
                  :rows first)]
      (is (not (contains? (:sub-status row) [:counter/orphan]))))))

(deftest views-row-render-args-diff-test
  (testing "rf2-u3lii — `view-rows` carries the col-2 render-args DIFF
            slots: `:render-args` (THIS render's args, consumed AS-IS
            from the already-elided `:rf.view/render-args` slot) +
            `:prev-render-args` (the SAME view INSTANCE's previous render
            args this cascade, keyed by `:rf.view/render-key`)."
    (testing "first render of an instance → `:render-args` present,
              `:prev-render-args` ABSENT (renders plain, no diff)"
      (let [row (-> (proj/views-step
                      [(view-render-ev :app/Item [] 0.1
                                       {:render-key  [:Item 0]
                                        :render-args [{:label "a" :n 1}]})])
                    :rows first)]
        (is (= [{:label "a" :n 1}] (:render-args row))
            "this render's args present")
        (is (not (contains? row :prev-render-args))
            "no previous render this cascade → :prev-render-args absent")))

    (testing "a re-rendered SAME instance with CHANGED args → the
              re-render row's `:prev-render-args` is the FIRST render's
              args (so the view diffs against ITS previous, not a
              neighbour's)"
      (let [rows (-> (proj/views-step
                       [(view-render-ev :app/Item [] 0.1
                                        {:render-key  [:Item 0]
                                         :render-args [{:label "a" :n 1}]})
                        (view-render-ev :app/Item [] 0.1
                                        {:render-key  [:Item 0]
                                         :render-args [{:label "a" :n 2}]})])
                     :rows)
            [r1 r2] rows]
        (is (= 2 (count rows)))
        (is (not (contains? r1 :prev-render-args)) "first render: no prev")
        (is (= [{:label "a" :n 2}] (:render-args r2)) "re-render's own args")
        (is (= [{:label "a" :n 1}] (:prev-render-args r2))
            "re-render diffs against the SAME instance's previous render")))

    (testing "two DIFFERENT instances of the same view → each keys its
              previous-args by ITS OWN render-key (no cross-instance
              bleed)"
      (let [rows (-> (proj/views-step
                       [(view-render-ev :app/Item [] 0.1
                                        {:render-key  [:Item 0]
                                         :render-args [{:n 0}]})
                        (view-render-ev :app/Item [] 0.1
                                        {:render-key  [:Item 1]
                                         :render-args [{:n 1}]})])
                     :rows)
            [a b] rows]
        ;; both are FIRST renders of their respective instances —
        ;; neither inherits the other's args.
        (is (not (contains? a :prev-render-args)))
        (is (not (contains? b :prev-render-args))
            "instance [:Item 1] does NOT diff against instance [:Item 0]")))

    (testing "a no-arg render → `:render-args` ABSENT (reads `(no args)`
              in the view)"
      (let [row (-> (proj/views-step
                      [(view-render-ev :app/Plain [] 0.1
                                       {:render-key [:Plain 0]})])
                    :rows first)]
        (is (not (contains? row :render-args)))))))

;; ---- top-level project --------------------------------------------------

(deftest project-minimal-test
  (testing "minimal epoch (dispatch + handler + a :db write, no
            cofx/flow/user-fx/sub/view).

  rf2-kt6js — the SIDE EFFECTS step ALWAYS appears when a `:db` commit
  happened, INCLUDING a bare reg-event-db with no `:fx` (`db-commit?`
  keys off `:rf.event/db-changed`). Pre-rf2-kt6js a plain reg-event-db
  surfaced NO side-effects step at all (the FX step keyed off a
  non-existent fx-id-less `:rf.fx/handled`). rf2-j630b — the minimal
  :db-writing cascade is :dispatch + :handler + :side-effects (a flat
  ledger with the single :db row)."
    (let [rec   (record [(dispatched-ev [:counter-inc] :ui nil)
                         (db-changed-ev [[[:counter] 5 6 :modified]])])
          steps (proj/project rec)
          se    (some #(when (= :side-effects (:step %)) %) steps)]
      (is (= 3 (count steps)))
      (is (= [:dispatch :handler :side-effects] (mapv :step steps)))
      (is (some? se) "SIDE EFFECTS step present on a bare :db write")
      (is (= [:db] (mapv :fx-id (:rows se)))
          "the flat ledger carries the single :db row — no :fx, no other"))))

(deftest project-no-db-no-fx-omits-side-effects-test
  (testing "rf2-kt6js — a cascade with NO :db commit and NO :fx (e.g. a
            handler that returned nothing) omits the SIDE EFFECTS step
            entirely — silence is correct when nothing happened"
    (let [rec   (record [(dispatched-ev [:noop] :ui nil)
                         (run-end-ev 0.3)])
          steps (proj/project rec)]
      (is (not-any? #(= :side-effects (:step %)) steps)
          "no side effect → no SIDE EFFECTS step"))))

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
      (is (= [:dispatch :coeffect :handler :flow :side-effects
              :subscriptions :views]
             kws)
          "rf2-kt6js — the :fx step is now the :side-effects step")
      (is (= 7 (count steps))))))

(deftest project-numbered-test
  (testing "number-steps assigns sequential 1..N regardless of omissions.
            rf2-kt6js — the `:db` write surfaces the SIDE EFFECTS step
            between HANDLER and SUBSCRIPTIONS."
    (let [rec   (record [(dispatched-ev [:counter-inc] :ui nil)
                         (db-changed-ev [])
                         (sub-run-ev [:total] true 1 2)])
          steps (proj/project-numbered rec)]
      (is (= 4 (count steps)))
      (is (= [1 2 3 4] (mapv :step-number steps)))
      (is (= [:dispatch :handler :side-effects :subscriptions]
             (mapv :step steps))))))

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
      (is (= 9 (count proj/badge-set))
          "rf2-sc3r1 7 + rf2-xgeag (SCHEMA-HOT-RELOAD, renamed from
           SCHEMA-VIOLATIONS) + rf2-yz57h (INTERCEPTOR) = 9 badges.
           rf2-btt0s deleted :CHILD-DISPATCHES + :APP-DB-DIFF (retired
           steps the projection can no longer emit).")
      ;; rf2-btt0s — guard against step-level drift: badge-set must NOT
      ;; advertise a badge no step can produce. The conditional steps
      ;; (FLOW / SIDE-EFFECTS / SCHEMA-HOT-RELOAD / INTERCEPTOR) are
      ;; covered by their own dedicated projection tests; here we pin the
      ;; two RETIRED badges are gone so the dead-badge class is CI-visible.
      (is (not (contains? proj/badge-set :CHILD-DISPATCHES))
          "retired CHILD-DISPATCHES badge removed from badge-set")
      (is (not (contains? proj/badge-set :APP-DB-DIFF))
          "retired APP-DB-DIFF badge removed from badge-set"))))

;; ---- formatting helpers --------------------------------------------------

(deftest format-duration-ms-test
  (testing "duration formatting"
    (is (= "0.1ms" (fmt/format-duration-ms 0.1)))
    (is (= "9.5ms" (fmt/format-duration-ms 9.5)))
    (is (= "12ms"  (fmt/format-duration-ms 12)))
    (is (= "1.2s"  (fmt/format-duration-ms 1234)))
    (is (nil? (fmt/format-duration-ms nil)))))

(deftest ns-keyword-test
  (testing "id rendering"
    (is (= ":foo"       (fmt/ns-keyword :foo)))
    (is (= ":my/foo"    (fmt/ns-keyword :my/foo)))
    (is (= "non-kw"     (fmt/ns-keyword "non-kw")))))

(deftest truncate-test
  (testing "truncate keeps short strings + ellipsises long ones"
    (is (= "abc"  (fmt/truncate "abc" 5)))
    (is (= "abcd…" (fmt/truncate "abcdefg" 4)))))

(deftest phase-label-test
  (testing "phase labels render every closed-set member"
    (is (= "exit"            (fmt/phase-label :exit)))
    (is (= "transition"      (fmt/phase-label :transition)))
    (is (= "entry"           (fmt/phase-label :entry)))
    (is (= "always"          (fmt/phase-label :always)))
    (is (= "after-action"    (fmt/phase-label :after-action)))
    (is (= "initial-entry"   (fmt/phase-label :initial-entry)))
    (is (= "destroy-exit"    (fmt/phase-label :destroy-exit)))))

(deftest timer-reason-label-test
  (testing "timer-cancelled reasons render every closed-set member"
    (is (= "on-exit"          (fmt/timer-reason-label :on-exit)))
    (is (= "on-destroy"       (fmt/timer-reason-label :on-destroy)))
    (is (= "on-resolution"    (fmt/timer-reason-label :on-resolution)))
    (is (= "on-supersede"     (fmt/timer-reason-label :on-supersede)))
    (is (= "on-frame-destroy" (fmt/timer-reason-label :on-frame-destroy)))))

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
  (testing "rf2-8resu / rf2-kt6js — `:app-db` violation attaches to the
            SIDE EFFECTS step's `:db` row (the handler's app-db write).
            The schema violation belongs on the row representing the
            failed commit, not on HANDLER (which describes what the
            handler RETURNED). HANDLER + DISPATCH stay clean."
    (let [steps  [{:step :dispatch :badge :DISPATCH}
                  {:step :handler  :badge :HANDLER}
                  {:step :side-effects :badge :SIDE-EFFECTS
                   :rows [{:fx-id :db :status :error}]}]
          rows   [{:where :app-db :failing-id :counter/inc :rollback? true}]
          out    (proj/attach-violations steps rows)
          fx     (nth out 2)]
      (is (nil? (:violations (nth out 0)))
          "DISPATCH step untouched")
      (is (nil? (:violations (nth out 1)))
          "HANDLER step untouched — the violation no longer attaches here")
      (is (nil? (:violations fx))
          "SIDE EFFECTS step-level :violations untouched — the violation
           routes into the :db row, not the step")
      (is (= 1 (count (:violations (first (:rows fx)))))
          "SIDE EFFECTS :db row carries the attached violation"))))

(deftest attach-violations-fx-row-test
  (testing "rf2-xgeag / rf2-kt6js — `:fx-args` violation attaches to the
            SIDE EFFECTS row whose `:fx-id` matches `:failing-id`"
    (let [steps  [{:step :side-effects :badge :SIDE-EFFECTS
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
  (testing "rf2-8resu / rf2-kt6js — rollback flags every step AFTER the
            SIDE EFFECTS step (not after HANDLER). The SIDE EFFECTS step
            itself is NOT muted — its `:db` row IS the visible rollback
            indicator (red ✗ + violation sub-block); muting the entire
            step would hide the signal. DISPATCH + HANDLER are upstream
            of the failed commit so they stay unmuted too — they ran for
            real."
    (let [steps [{:step :dispatch} {:step :handler}
                 {:step :side-effects} {:step :subscriptions}
                 {:step :views}]
          rows  [{:where :app-db :rollback? true}]
          out   (proj/mark-rolled-back-downstream steps rows)]
      (is (nil? (:rolled-back? (nth out 0)))
          "DISPATCH untouched (upstream of the commit)")
      (is (nil? (:rolled-back? (nth out 1)))
          "HANDLER untouched (described what it RETURNED; that ran)")
      (is (nil? (:rolled-back? (nth out 2)))
          "SIDE EFFECTS step itself NOT muted — its :db row is the signal")
      (is (true? (:rolled-back? (nth out 3)))
          "SUBSCRIPTIONS downstream of SIDE EFFECTS gets muted")
      (is (true? (:rolled-back? (nth out 4)))
          "VIEWS downstream of SIDE EFFECTS gets muted")))

  (testing "rf2-xgeag — no rollback → no `:rolled-back?` flags"
    (let [steps [{:step :dispatch} {:step :handler} {:step :side-effects}]
          rows  [{:where :sub-return :rollback? true}]
          out   (proj/mark-rolled-back-downstream steps rows)]
      (is (every? #(nil? (:rolled-back? %)) out)))))

;; ---- rf2-zkiu5 — retired cascade steps (APP-DB DIFF + CHILD DISPATCHES) --
;;
;; The standalone APP-DB DIFF (rf2-rrykz) and CHILD-DISPATCHES
;; (rf2-yx1ae) steps were retired pair-debug 2026-05-26 — both redundant
;; with existing steps (HANDLER `:db` surfaces the post-handler diff; the
;; FX step surfaces every dispatch-family fx entry). rf2-btt0s deleted
;; the dead projection / view / badge code that lingered after the
;; cascade-emit was dropped (`child-dispatch-rows`, `child-dispatches-step`,
;; `find-child-epoch`, the `:CHILD-DISPATCHES` / `:APP-DB-DIFF` badge-set
;; members) along with their tests. The surviving parent-epoch
;; correlation (`find-parent-epoch` / `dispatch-id->epoch-id-index`) is
;; a DIFFERENT, live concern — the DISPATCH step's `:fx-dispatch`
;; parent-link resolver — and keeps its tests below.

(deftest find-parent-epoch-by-dispatch-id-test
  (testing "rf2-5qp4g — find-parent-epoch resolves a parent epoch's
            `:epoch-id` from its `:dispatch-id`: looks up the supplied
            parent-dispatch-id in a precomputed
            `{dispatch-id → epoch-id}` index (rf2-x25e0; built once
            per render via `dispatch-id->epoch-id-index`), returning
            the matched record's `:epoch-id`. The view layer uses
            this to wire the `from fx · parent epoch #N` chrome on
            `:fx-dispatch` / `:fx-dispatch-later` DISPATCH steps."
    (let [history [{:epoch-id 41 :dispatch-id 9000 :trigger-event [:root]}
                   {:epoch-id 42 :dispatch-id 9001 :trigger-event [:parent]}
                   {:epoch-id 43 :dispatch-id 9002 :trigger-event [:child]
                    :parent-dispatch-id 9001}]
          index   (proj/dispatch-id->epoch-id-index history)]
      (is (= 41 (proj/find-parent-epoch index 9000))
          "matches the first-class :dispatch-id slot")
      (is (= 42 (proj/find-parent-epoch index 9001))
          "matches a sibling parent's :dispatch-id")
      (is (nil? (proj/find-parent-epoch index 99999))
          "no match → nil")
      (is (nil? (proj/find-parent-epoch nil 9001))
          "nil index → nil")
      (is (nil? (proj/find-parent-epoch index nil))
          "nil parent-dispatch-id → nil")
      (is (nil? (proj/find-parent-epoch {} 9001))
          "empty index → nil"))))

(deftest dispatch-id->epoch-id-index-test
  (testing "rf2-x25e0 — `dispatch-id->epoch-id-index` builds the
            O(1) lookup map the view threads through `ctx`. Each
            record contributes via its first-class `:dispatch-id`
            slot (rf2-rly4a) AND via `dispatch-id-of-epoch`'s trace-
            walk fallback (for restored fixtures lacking the slot)."
    (let [history [{:epoch-id 41 :dispatch-id 9000 :trigger-event [:root]}
                   {:epoch-id 42 :dispatch-id 9001 :trigger-event [:parent]}
                   {:epoch-id 43 :dispatch-id 9002 :trigger-event [:child]
                    :parent-dispatch-id 9001}]
          index   (proj/dispatch-id->epoch-id-index history)]
      (is (= 41 (get index 9000)))
      (is (= 42 (get index 9001)))
      (is (= 43 (get index 9002)))
      (is (nil? (get index 99999))
          "absent key → nil")
      (is (= {} (proj/dispatch-id->epoch-id-index []))
          "empty history → empty index")
      (is (= {} (proj/dispatch-id->epoch-id-index nil))
          "nil history → empty index"))))

(deftest project-attaches-app-db-violation-to-fx-db-row-test
  (testing "rf2-8resu / rf2-kt6js / rf2-j630b — top-level `project`
            attaches `:app-db` boundary violations to the SIDE EFFECTS
            step's `:db` row (the handler's app-db write, leading the flat
            ledger). The step is synthesised when a `:db` commit was
            attempted (here both a `:rf.event/db-changed` AND a `:where
            :app-db` rollback fire). The `:db` row carries `:status
            :error` (✗ on the schema-fail rollback) + the attached
            violation, and is the ONLY row (atomicity — no fx ran).
            HANDLER stays clean — it describes what the handler RETURNED;
            the commit outcome is the SIDE EFFECTS `:db` row's concern."
    (let [rec     (record [(dispatched-ev [:counter/inc] :ui nil)
                           (db-changed-ev [[[:count] 0 "boom" :modified]])
                           (schema-violation-ev :app-db :counter/inc
                                                [:count] "boom" true)])
          steps   (proj/project rec)
          handler (some #(when (= :handler (:step %)) %) steps)
          se      (some #(when (= :side-effects (:step %)) %) steps)
          db-row  (some #(when (= :db (:fx-id %)) %) (:rows se))]
      (is (some? handler))
      (is (nil? (:violations handler))
          "HANDLER step carries no violations — routing moved to the :db row")
      (is (some? se)
          "SIDE EFFECTS step is synthesised when a :where :app-db rollback
           fires even with no user-emitted fx")
      (is (= [:db] (mapv :fx-id (:rows se)))
          ":db-only flat ledger on a rolled-back commit")
      (is (= :error (proj/side-effects-badge-status (:rows se)))
          "badge :error on rollback — the attached violation lifts it")
      (is (some? db-row)
          "the SIDE EFFECTS step's flat ledger carries the :db row")
      (is (= :error (:status db-row))
          "the :db row's status reflects the schema-fail rollback")
      (is (= 1 (count (:violations db-row)))
          "the :app-db violation attached to the :db row flows through to the
           rendered ledger row (single-source-of-truth :rows slot)")
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

;; ---- rf2-ahhgn — inline exception attachment + per-step status ----------
;;
;; The live button-15 (`:standard-epochs/throw-handler`) scenario: the handler
;; threw, the router caught it (db rolled back), the epoch settled with the
;; framework `:outcome :ok` (by spec — the reference runtime recovers + does
;; NOT emit `:halted-handler-exception`) and a `:rf.error/handler-exception`
;; trace landed under `:trace-events`. Pre-rf2-ahhgn the Epoch panel surfaced
;; NONE of it. These tests pin (a) the message + coord projection, (b) the
;; per-step `:status` primitive, (c) the tool-side `epoch-outcome :error`,
;; and (d) the top-level `project` attachment end-to-end.

(deftest exception-row-reads-message-and-coord-test
  (testing "rf2-ahhgn — `exception-row` lifts the message off
            `:exception-message` (NOT `:message` — the bead's probe checked
            the wrong key) and the source-coord off the hoisted
            `:rf.trace/trigger-handler :source-coord`"
    (let [ev  (handler-exception-ev
                :standard-epochs/throw-handler
                "standard-epochs / handler (intentional — exercises the handler error surface)"
                {:file "standard_epochs/core.cljs" :line 322})
          row (proj/exception-row ev)]
      (is (= :rf.error/handler-exception (:operation row)))
      (is (= "standard-epochs / handler (intentional — exercises the handler error surface)"
             (:message row))
          "message resolves through :exception-message")
      (is (= {:file "standard_epochs/core.cljs" :line 322} (:coord row))
          "coord resolves through :rf.trace/trigger-handler :source-coord")
      (is (= :standard-epochs/throw-handler (:failing-id row)))
      (is (= :no-recovery (:recovery row)))))

  (testing "rf2-oqi0c — the `:reason` CATEGORY boilerplate is NO LONGER
            surfaced as the card message: when the throw carried no real
            `:exception-message`, `:message` is nil (the card shows only
            the position + 'Exception Thrown' heading, no boilerplate line).
            nil-safe coord when neither trigger-handler nor call-site
            carries a file."
    (let [ev  (handler-exception-ev :foo/bar nil)
          row (proj/exception-row ev)]
      ;; handler-exception-ev stamps `:reason "Event handler threw."` —
      ;; rf2-oqi0c drops the :reason fallback, so :message resolves nil.
      (is (nil? (:message row))
          "no :exception-message → :message nil (the :reason boilerplate is dropped)")
      (is (nil? (:coord row)))))

  (testing "rf2-wnvid — `exception-row` lifts the raw `:exception` object
            off `[:tags :exception]` (the view's collapsible details read
            the stack / ex-data off it)"
    (let [boom (ex-info "boom" {:surface :handler-exception})
          ev   (handler-exception-ev :e "boom" nil nil boom)
          row  (proj/exception-row ev)]
      (is (identical? boom (:exception row))
          "the raw exception object rides the row")))

  (testing "rf2-wnvid — no `:exception` tag → `:exception` slot is nil"
    (let [row (proj/exception-row (handler-exception-ev :e "boom"))]
      (is (nil? (:exception row))))))

(deftest handler-wrote-db?-test
  (testing "rf2-wnvid — true when t1 (`:rf.event/db-pending`) fired"
    (is (true? (proj/handler-wrote-db?
                 [(db-pending-ev {:count 1})]))))
  (testing "rf2-wnvid — true when a `:rf.event/db-changed` commit fired"
    (is (true? (proj/handler-wrote-db?
                 [(db-changed-ev [[[:count] 0 1 :modified]])]))))
  (testing "rf2-wnvid — FALSE for the handler-threw shape (button-15: no
            t1, no db-changed — handler threw before returning a :db)"
    (is (false? (proj/handler-wrote-db?
                  [(dispatched-ev [:standard-epochs/throw-handler] :ui nil)
                   (handler-exception-ev :standard-epochs/throw-handler "boom" nil)
                   (run-end-ev 1)]))))
  (testing "rf2-wnvid — FALSE for a reg-event-fx that returned only :fx"
    (is (false? (proj/handler-wrote-db?
                  [(do-fx-ev {:fx [[:navigate "/x"]]})
                   (run-end-ev 1)])))))

(deftest handler-row-db-write?-slot-test
  (testing "rf2-wnvid — the HANDLER row carries `:db-write?` so the view's
            `:db` sub-section chooses the no-write placeholder over the
            phantom full-app-db fallback"
    (let [threw (proj/handler-row
                  [(dispatched-ev [:standard-epochs/throw-handler] :ui nil)
                   (handler-exception-ev :standard-epochs/throw-handler "boom" nil)
                   (run-end-ev 1)]
                  :standard-epochs/throw-handler
                  {:n 1} {:n 1})]
      (is (false? (:db-write? threw))
          "handler that threw before returning a :db → db-write? false"))
    (let [wrote (proj/handler-row
                  [(db-pending-ev {:count 1})
                   (db-changed-ev [[[:count] 0 1 :modified]])
                   (run-end-ev 1)]
                  :counter/inc {:count 0} {:count 1})]
      (is (true? (:db-write? wrote))
          "handler that wrote a :db → db-write? true"))))

(deftest exception-rows-harvests-cascade-exceptions-test
  (testing "rf2-ahhgn — `exception-rows` harvests the `cascade-exception-ops`
            subset (handler / fx exceptions) and ignores non-exception traces"
    (let [events [(dispatched-ev [:e] :ui nil)
                  (handler-exception-ev :e "boom" nil)
                  (run-end-ev 1)
                  (fx-handler-exception-ev :http/post "fx boom" nil)]
          rows   (proj/exception-rows events)]
      (is (= 2 (count rows)))
      (is (= #{:rf.error/handler-exception :rf.error/fx-handler-exception}
             (set (map :operation rows))))))

  (testing "rf2-ahhgn — empty vec when no exception traces fired"
    (is (= [] (proj/exception-rows
                [(dispatched-ev [:e] :ui nil) (run-end-ev 1)])))))

(deftest attach-exceptions-handler-test
  (testing "rf2-ahhgn — a `:rf.error/handler-exception` attaches to the
            HANDLER step's `:errors` + stamps `:status :error`"
    (let [steps [{:step :dispatch :badge :DISPATCH}
                 {:step :handler  :badge :HANDLER}
                 {:step :side-effects :badge :SIDE-EFFECTS :rows []}]
          rows  [(proj/exception-row
                   (handler-exception-ev :e "boom" {:file "a.cljs" :line 1}))]
          out   (proj/attach-exceptions steps rows)
          h     (nth out 1)]
      (is (= 1 (count (:errors h))) "HANDLER carries the exception")
      (is (= :error (:status h)) "HANDLER stamped :status :error")
      (is (nil? (:errors (nth out 0))) "DISPATCH untouched")
      (is (nil? (:status (nth out 0))) "DISPATCH not flagged"))))

(deftest attach-exceptions-fx-row-test
  (testing "rf2-ahhgn / rf2-kt6js — a `:rf.error/fx-handler-exception`
            attaches to the SIDE EFFECTS step's matching `:fx-id` row +
            stamps the step `:status :error`"
    (let [steps [{:step :side-effects :badge :SIDE-EFFECTS
                  :rows [{:fx-id :db :status :ok}
                         {:fx-id :http/post :status :error}]}]
          rows  [(proj/exception-row
                   (fx-handler-exception-ev :http/post "fx boom" nil))]
          out   (proj/attach-exceptions steps rows)
          fx    (first out)]
      (is (= :error (:status fx)))
      (is (nil? (:errors (first (:rows fx)))) ":db row untouched")
      (is (= 1 (count (:errors (second (:rows fx)))))
          "http/post row carries the exception")))

  (testing "rf2-ahhgn — an fx exception with no matching row falls back to
            the SIDE EFFECTS step-level `:errors`"
    (let [steps [{:step :side-effects :badge :SIDE-EFFECTS :rows [{:fx-id :db}]}]
          rows  [(proj/exception-row
                   (fx-handler-exception-ev :unknown/fx "boom" nil))]
          out   (proj/attach-exceptions steps rows)]
      (is (= 1 (count (:errors (first out)))))))

  (testing "rf2-ahhgn — empty rows leaves steps unchanged"
    (let [steps [{:step :handler}]]
      (is (= steps (proj/attach-exceptions steps []))))))

(deftest step-status-test
  (testing "rf2-ahhgn — `:ok` for a clean step, `:error` when the step (or
            a row) carries an exception or violation"
    (is (= :ok    (proj/step-status {:step :handler})))
    (is (= :error (proj/step-status {:step :handler :status :error})))
    (is (= :error (proj/step-status {:step :handler :errors [{:message "x"}]})))
    (is (= :error (proj/step-status {:step :handler :violations [{:where :app-db}]})))
    (is (= :error (proj/step-status {:step :side-effects :rows [{:fx-id :db :errors [{}]}]}))
        "row-level :errors lift the step to :error")
    (is (= :error (proj/step-status {:step :side-effects :rows [{:fx-id :db :violations [{}]}]}))
        "row-level :violations lift the step to :error")
    (is (= :ok (proj/step-status {:step :side-effects :rows [{:fx-id :db}]}))
        "clean rows keep :ok")))

(deftest epoch-outcome-test
  (testing "rf2-ahhgn — `:error` when ANY step errored, else `:ok`"
    (is (= :ok    (proj/epoch-outcome [{:step :dispatch} {:step :handler}])))
    (is (= :error (proj/epoch-outcome [{:step :dispatch}
                                       {:step :handler :status :error}])))
    (is (= :error (proj/epoch-outcome [{:step :handler
                                        :violations [{:where :app-db}]}]))
        "schema violations (no :status stamp) still read :error")
    (is (= :ok (proj/epoch-outcome [])) "empty step vec → :ok")))

(deftest project-attaches-handler-exception-end-to-end-test
  (testing "rf2-ahhgn — the live button-15 scenario: a handler-exception
            trace under `:trace-events` surfaces on the HANDLER step inline
            (message + coord) AND the projected cascade reads `:error`. The
            handler step also carries `:status :error` so the view paints ✗."
    (let [rec   (record [(dispatched-ev [:standard-epochs/throw-handler] :ui
                                        {:file "core.cljs" :line 481})
                         (handler-exception-ev
                           :standard-epochs/throw-handler
                           "standard-epochs / handler (intentional — exercises the handler error surface)"
                           {:file "standard_epochs/core.cljs" :line 322})]
                        :standard-epochs/throw-handler)
          steps   (proj/project rec)
          handler (some #(when (= :handler (:step %)) %) steps)]
      (is (some? handler))
      (is (= :error (proj/step-status handler))
          "HANDLER step reads :error")
      (is (= :error (:status handler))
          "HANDLER step carries the stamped :status :error")
      (is (= 1 (count (:errors handler)))
          "the handler-exception attached as an inline error")
      (let [err (first (:errors handler))]
        (is (= "standard-epochs / handler (intentional — exercises the handler error surface)"
               (:message err)))
        (is (= {:file "standard_epochs/core.cljs" :line 322} (:coord err)))
        ;; rf2-s6oqd — the live button-16 handler threw before returning,
        ;; so NO :db committed AND nothing rolled back. The exception row's
        ;; cascade-level `:db-rolled-back?` is false → the view omits the
        ;; spurious 'Rolled back' chip.
        (is (false? (:db-rolled-back? err))
            "no rollback (pre-commit handler throw) → :db-rolled-back? false (no spurious chip)"))
      ;; rf2-wnvid — the HANDLER step carries :db-write? false (it threw
      ;; before producing a :db), so the view shows 'no :db (handler
      ;; threw)' rather than the phantom full app-db.
      (is (false? (:db-write? handler))
          "no :db write → :db-write? false (no phantom :db)")
      ;; rf2-wnvid — no schema rollback fired, so NO step is marked
      ;; rolled-back (the downstream-mute path is correctly inert).
      (is (every? #(nil? (:rolled-back? %)) steps)
          "no schema rollback → no :rolled-back? flags (no spurious rollback chrome)")
      (is (= :error (proj/epoch-outcome steps))
          "the epoch outcome reflects the exception (NOT :ok)")))

  (testing "rf2-ahhgn — a clean cascade reads :ok with no attached errors"
    (let [rec   (record [(dispatched-ev [:counter/inc] :ui nil)
                         (db-changed-ev [[[:count] 0 1 :modified]])
                         (run-end-ev 1)]
                        :counter/inc)
          steps (proj/project rec)]
      (is (= :ok (proj/epoch-outcome steps)))
      (is (every? #(nil? (:errors %)) steps))
      (is (every? #(= :ok (proj/step-status %)) steps)))))

;; ---- rf2-s6oqd — 'Rolled back' chip gates on ACTUAL rollback ------------
;;
;; fx are POST-COMMIT / best-effort (the FX atomicity asymmetry): a throwing
;; fx leaves the `:db` committed (the baseline bump survives) — nothing
;; rolled back. The chip's predicate is `:db-rolled-back?` (a real
;; `:where :app-db` schema-fail rollback), NOT mere commit. So:
;;   - post-commit fx throw (button-20 `:standard-epochs/boom`) → committed,
;;     NOT rolled back → NO chip.
;;   - :db schema-fail rollback (button-23) → committed AND rolled back →
;;     chip.
;;   - pre-commit handler throw (button-16) → no commit, no rollback → no
;;     chip (covered above).

(deftest fx-exception-stamps-db-rolled-back-false-test
  (testing "rf2-s6oqd — a POST-COMMIT fx throw (`:standard-epochs/boom`)
            leaves the :db committed; the cascade did NOT roll back, so the
            attached exception row carries `:db-rolled-back? false` → the
            view omits the spurious 'Rolled back' chip"
    (let [rec   (record [(dispatched-ev [:standard-epochs/throw-fx] :ui nil)
                         ;; the handler committed a :db (baseline bumped)
                         ;; BEFORE the post-commit fx walk
                         (db-pending-ev {:baseline 1})
                         (db-changed-ev [[[:baseline] 0 1 :modified]])
                         (run-end-ev 1)
                         (do-fx-ev {:fx [[:standard-epochs/boom {}]]})
                         ;; the post-commit fx threw (best-effort; :db stays)
                         (fx-handler-exception-ev :standard-epochs/boom
                                                  "standard-epochs / boom fx threw")]
                        :standard-epochs/throw-fx)
          steps (proj/project rec)
          se    (some #(when (= :side-effects (:step %)) %) steps)
          ;; the fx exception attaches to the SIDE EFFECTS step's :boom row
          err   (or (first (:errors se))
                    (->> (:rows se) (mapcat :errors) first))]
      (is (some? err) "the fx exception attached to the SIDE EFFECTS step")
      (is (false? (:db-rolled-back? err))
          "post-commit fx throw → :db-rolled-back? false (NO spurious 'Rolled back' chip)")
      ;; the cascade did commit (the baseline bump survives) — but nothing
      ;; was reverted, which is the whole point.
      (is (true? (proj/db-commit? (:trace-events rec)))
          "the :db DID commit (baseline survives)")
      (is (false? (proj/db-rolled-back? (:trace-events rec)))
          "but nothing rolled back"))))

(deftest schema-rollback-stamps-db-rolled-back-true-test
  (testing "rf2-s6oqd — a `:where :app-db` schema-fail rollback DID revert
            the commit, so an exception row this cascade carries
            `:db-rolled-back? true` → the 'Rolled back' chip DOES paint.
            (The schema-fail rolled back AND a handler exception fired.)"
    (let [rec   (record [(dispatched-ev [:standard-epochs/set-bad-auth] :ui nil)
                         (db-changed-ev [[[:auth :token] "ok" 42 :modified]])
                         ;; the app-db schema check rejected the write +
                         ;; flagged the cascade rolled back
                         (schema-violation-ev :app-db :standard-epochs/set-bad-auth
                                              [:auth :token] 42 true)
                         (handler-exception-ev :standard-epochs/set-bad-auth
                                               "post-rollback handler note")]
                        :standard-epochs/set-bad-auth)
          steps (proj/project rec)
          errs  (mapcat :errors steps)
          err   (first errs)]
      (is (true? (proj/db-rolled-back? (:trace-events rec)))
          "the :app-db schema-fail flagged the cascade rolled back")
      (is (some? err) "an exception row attached this cascade")
      (is (true? (:db-rolled-back? err))
          ":db-rolled-back? true → the 'Rolled back' chip paints (correct)"))))

;; ---- rf2-yz57h — per-step exception placement + INTERCEPTOR step --------
;;
;; rf2-mszrz split the blanket `:rf.error/handler-exception` into three
;; component-attributed ops; rf2-yz57h places each under the step where it
;; actually occurred (coeffect → COEFFECT, interceptor → INTERCEPTOR,
;; handler → HANDLER) and renders an upstream-skipped HANDLER / SIDE
;; EFFECTS step as SKIPPED rather than 'ran, returned no :db'.

(deftest exception-op->step-covers-new-ops-test
  (testing "rf2-yz57h — the new component-attributed ops are in the
            exception set"
    (is (contains? proj/cascade-exception-ops :rf.error/coeffect-exception))
    (is (contains? proj/cascade-exception-ops :rf.error/interceptor-exception))
    (is (contains? proj/cascade-exception-ops :rf.error/handler-exception))))

;; -- COEFFECT placement (button-19) --------------------------------------

(deftest attach-coeffect-exception-to-matching-step-test
  (testing "rf2-yz57h — a `:rf.error/coeffect-exception` attaches to the
            COEFFECT step whose :id matches :failing-id (not HANDLER)"
    (let [steps [{:step :coeffect :badge :COEFFECT :id :other/cofx}
                 {:step :coeffect :badge :COEFFECT :id :app/session}
                 {:step :handler  :badge :HANDLER}]
          rows  [(proj/exception-row
                   (coeffect-exception-ev :app/session "cofx boom"))]
          out   (proj/attach-exceptions steps rows)]
      (is (nil? (:errors (nth out 0))) "non-matching COEFFECT untouched")
      (is (= 1 (count (:errors (nth out 1)))) "matching COEFFECT carries it")
      (is (= :error (:status (nth out 1))) "matching COEFFECT stamped :error")
      (is (nil? (:errors (nth out 2))) "HANDLER does NOT carry it")))

  (testing "rf2-yz57h — falls back to the FIRST COEFFECT step when no :id
            matches (e.g. the throwing cofx produced no :rf.cofx/run)"
    (let [steps [{:step :coeffect :badge :COEFFECT :id :app/session}
                 {:step :handler  :badge :HANDLER}]
          rows  [(proj/exception-row
                   (coeffect-exception-ev :app/missing "cofx boom"))]
          out   (proj/attach-exceptions steps rows)]
      (is (= 1 (count (:errors (nth out 0)))))
      (is (nil? (:errors (nth out 1)))))))

(deftest project-synthesises-coeffect-placeholder-on-throwing-cofx-test
  (testing "rf2-yz57h — a coeffect-exception with NO matching :rf.cofx/run
            synthesises a placeholder COEFFECT step (no value), carries the
            exception, and marks the HANDLER + SIDE EFFECTS skipped"
    (let [rec   (record [(dispatched-ev [:standard-epochs/throw-cofx] :ui nil)
                         (coeffect-exception-ev :standard-epochs/throwing-cofx
                                                "cofx boom")]
                        :standard-epochs/throw-cofx)
          steps (proj/project rec)
          cofx  (some #(when (= :coeffect (:step %)) %) steps)
          h     (some #(when (= :handler (:step %)) %) steps)]
      (is (some? cofx) "a COEFFECT step exists for the throwing cofx")
      (is (= :standard-epochs/throwing-cofx (:id cofx)))
      (is (true? (:no-value? cofx)) "placeholder carries :no-value? (no resolved value)")
      (is (= 1 (count (:errors cofx))) "the exception lands under COEFFECT")
      (is (= :error (proj/step-status cofx)) "COEFFECT reads :error")
      (is (some? h) "HANDLER step still present")
      (is (= :skipped (proj/step-status h))
          "HANDLER reads :skipped (it never ran — upstream cofx threw)")
      (is (empty? (:errors h)) "HANDLER carries NO exception (it's under COEFFECT)")
      (is (= :error (proj/epoch-outcome steps))
          "epoch outcome reflects the coeffect exception"))))

;; -- INTERCEPTOR step (button-17 :before / button-18 :after) -------------

(deftest interceptor-step-projection-test
  (testing "rf2-yz57h — `interceptor-step` is nil when no interceptor threw"
    (is (nil? (proj/interceptor-step [(dispatched-ev [:x] :ui nil)
                                      (run-end-ev 1)] :before)))
    (is (nil? (proj/interceptor-step [(dispatched-ev [:x] :ui nil)
                                      (run-end-ev 1)] :after))))

  (testing "rf2-vew2n — `interceptor-step` is PHASE-FILTERED: the :before
            step carries only :before throws, the :after step only :after"
    (let [events [(interceptor-exception-ev :app/auth :before "intc boom")]
          before (proj/interceptor-step events :before)
          after  (proj/interceptor-step events :after)]
      (is (= :interceptor (:step before)))
      (is (= :INTERCEPTOR (:badge before)))
      (is (= :before (:phase before)) "the step carries its own :phase")
      (is (= 1 (count (:rows before))))
      (is (= :app/auth (:interceptor-id (first (:rows before)))))
      (is (= :before   (:phase (first (:rows before)))))
      (is (nil? after) "no :after throw → no :after interceptor step")))

  (testing "rf2-vew2n — an :after throw populates ONLY the :after step"
    (let [events [(interceptor-exception-ev :app/audit :after "intc boom")]
          before (proj/interceptor-step events :before)
          after  (proj/interceptor-step events :after)]
      (is (nil? before) "no :before throw → no :before interceptor step")
      (is (= :after (:phase after)))
      (is (= 1 (count (:rows after))))
      (is (= :app/audit (:interceptor-id (first (:rows after)))))))

  (testing "rf2-siheh — a macro-captured :source-coord on the trace lifts
            onto the INTERCEPTOR row's :coord (the slot the view's
            jump-to-source chip reads)"
    (let [coord  {:ns 'app.icpt :file "/abs/app/icpt.cljs" :line 42}
          events [(interceptor-exception-ev :app/auth :before "boom" nil coord)]
          before (proj/interceptor-step events :before)
          row    (first (:rows before))]
      (is (= :app/auth (:interceptor-id row)))
      (is (= coord (:coord row))
          "the row carries the interceptor's definition-site coord")))

  (testing "rf2-siheh — no :source-coord on the trace → the row's :coord is
            nil (the ->interceptor* fn / framework-interceptor path; the
            view's chip drops out cleanly)"
    (let [events [(interceptor-exception-ev :app/auth :before "boom")]
          before (proj/interceptor-step events :before)]
      (is (nil? (:coord (first (:rows before)))))))

  (testing "rf2-siheh — a coord lacking :file is treated as no-coord
            (defensive — the chip needs :file to resolve a URI)"
    (let [events [(interceptor-exception-ev :app/auth :before "boom" nil
                                            {:ns 'app.icpt :line 7})]
          before (proj/interceptor-step events :before)]
      (is (nil? (:coord (first (:rows before))))
          "no :file → :coord nil"))))

(deftest attach-interceptor-exception-to-interceptor-step-test
  (testing "rf2-yz57h — a `:rf.error/interceptor-exception` attaches to the
            INTERCEPTOR step (not HANDLER)"
    (let [steps [{:step :interceptor :badge :INTERCEPTOR :phase :before
                  :rows [{:interceptor-id :app/auth :phase :before}]}
                 {:step :handler :badge :HANDLER}]
          rows  [(proj/exception-row
                   (interceptor-exception-ev :app/auth :before "intc boom"))]
          out   (proj/attach-exceptions steps rows)]
      (is (= 1 (count (:errors (nth out 0)))) "INTERCEPTOR carries the exception")
      (is (= :error (:status (nth out 0))) "INTERCEPTOR stamped :error")
      (is (nil? (:errors (nth out 1))) "HANDLER untouched")))

  (testing "rf2-vew2n — with TWO phase-split INTERCEPTOR steps, an exception
            routes to the step whose :phase matches (NOT the first one)"
    (let [steps [{:step :interceptor :badge :INTERCEPTOR :phase :before
                  :rows [{:interceptor-id :app/before :phase :before}]}
                 {:step :handler :badge :HANDLER}
                 {:step :interceptor :badge :INTERCEPTOR :phase :after
                  :rows [{:interceptor-id :app/after :phase :after}]}]
          rows  [(proj/exception-row
                   (interceptor-exception-ev :app/after :after "after boom"))]
          out   (proj/attach-exceptions steps rows)]
      (is (nil? (:errors (nth out 0)))
          "the :before step (first) is NOT the target — the :after throw skips it")
      (is (= 1 (count (:errors (nth out 2))))
          "the :after step (after HANDLER) carries the :after throw")
      (is (= :error (:status (nth out 2))) "the :after step is stamped :error"))))

(deftest project-interceptor-before-end-to-end-test
  (testing "rf2-yz57h — button-17 live scenario: a `:before` interceptor
            threw → INTERCEPTOR step present, carries the exception, HANDLER
            skipped"
    (let [rec   (record [(dispatched-ev [:standard-epochs/throw-interceptor] :ui nil)
                         (interceptor-exception-ev
                           :standard-epochs/throwing-interceptor :before
                           "interceptor :before boom")]
                        :standard-epochs/throw-interceptor)
          steps (proj/project rec)
          intc  (some #(when (= :interceptor (:step %)) %) steps)
          h     (some #(when (= :handler (:step %)) %) steps)]
      (is (some? intc) "INTERCEPTOR step present")
      (is (= 1 (count (:errors intc))) "exception under INTERCEPTOR")
      (is (= :error (proj/step-status intc)))
      (is (= :skipped (proj/step-status h))
          "HANDLER skipped — a :before interceptor threw on the way in")
      (is (empty? (:errors h)) "HANDLER carries no exception")
      ;; cascade-position: INTERCEPTOR sits BEFORE HANDLER
      (let [step-kws (mapv :step steps)
            i-idx    (.indexOf step-kws :interceptor)
            h-idx    (.indexOf step-kws :handler)]
        (is (< i-idx h-idx) "INTERCEPTOR renders before HANDLER"))
      (is (= :error (proj/epoch-outcome steps))))))

(deftest project-interceptor-after-end-to-end-test
  (testing "rf2-yz57h / rf2-vew2n — button-18 live scenario: an `:after`
            interceptor threw → INTERCEPTOR step present, HANDLER NOT skipped
            (it ran first; the throw fired on the way out), and the
            INTERCEPTOR step renders AFTER the EVENT HANDLER step (the
            rf2-vew2n bug fix — it used to land at position 2, before the
            handler)"
    (let [rec   (record [(dispatched-ev [:standard-epochs/throw-interceptor-after] :ui nil)
                         ;; the handler ran + committed a :db on the way in
                         (db-pending-ev {:n 1})
                         (db-changed-ev [[[:n] 0 1 :modified]])
                         (run-end-ev 1)
                         (interceptor-exception-ev
                           :standard-epochs/throwing-interceptor-after :after
                           "interceptor :after boom")]
                        :standard-epochs/throw-interceptor-after)
          steps (proj/project rec)
          intc  (some #(when (= :interceptor (:step %)) %) steps)
          h     (some #(when (= :handler (:step %)) %) steps)]
      (is (some? intc) "INTERCEPTOR step present")
      (is (= :after (:phase intc)) "the step itself carries the :after phase")
      (is (= :after (:phase (first (:rows intc)))) "the row records the :after phase")
      (is (= 1 (count (:errors intc))) "exception under INTERCEPTOR")
      (is (not= :skipped (proj/step-status h))
          "HANDLER NOT skipped — it ran before the :after interceptor threw")
      (is (true? (:db-write? h)) "the handler DID write a :db (it ran)")
      ;; rf2-vew2n — cascade-position: the :after INTERCEPTOR sits AFTER
      ;; the EVENT HANDLER (the bug: it used to render at position 2).
      (let [step-kws (mapv :step steps)
            i-idx    (.indexOf step-kws :interceptor)
            h-idx    (.indexOf step-kws :handler)]
        (is (> i-idx h-idx) "INTERCEPTOR (:after) renders AFTER HANDLER"))
      (is (= :error (proj/epoch-outcome steps)))))

  (testing "rf2-vew2n — BOTH a :before and an :after interceptor throw in the
            same cascade → TWO INTERCEPTOR steps, one on each side of HANDLER"
    (let [rec   (record [(dispatched-ev [:multi/intc] :ui nil)
                         (interceptor-exception-ev :app/before :before "before boom")
                         (db-changed-ev [[[:n] 0 1 :modified]])
                         (run-end-ev 1)
                         (interceptor-exception-ev :app/after :after "after boom")]
                        :multi/intc)
          steps (proj/project rec)
          step-kws (mapv :step steps)
          h-idx    (.indexOf step-kws :handler)
          intc-steps (filterv #(= :interceptor (:step %)) steps)]
      (is (= 2 (count intc-steps)) "two INTERCEPTOR steps (one per phase)")
      (is (= #{:before :after} (set (map :phase intc-steps))))
      ;; the :before step precedes HANDLER; the :after step follows it
      (let [before-idx (.indexOf (mapv (juxt :step :phase) steps) [:interceptor :before])
            after-idx  (.indexOf (mapv (juxt :step :phase) steps) [:interceptor :after])]
        (is (< before-idx h-idx) ":before INTERCEPTOR precedes HANDLER")
        (is (> after-idx h-idx)  ":after INTERCEPTOR follows HANDLER"))
      ;; each exception lands on the step of its own phase
      (let [before-step (some #(when (and (= :interceptor (:step %)) (= :before (:phase %))) %) steps)
            after-step  (some #(when (and (= :interceptor (:step %)) (= :after (:phase %))) %) steps)]
        (is (= :app/before (:failing-id (first (:errors before-step)))))
        (is (= :app/after  (:failing-id (first (:errors after-step)))))))))

;; -- SKIPPED-step marking -------------------------------------------------

(deftest mark-skipped-handler-test
  (testing "rf2-yz57h — a coeffect throw marks HANDLER + SIDE EFFECTS skipped"
    (let [steps  [{:step :handler} {:step :side-effects} {:step :subscriptions}]
          events [(coeffect-exception-ev :app/session "boom")]
          out    (proj/mark-skipped-handler steps events)]
      (is (= :skipped (:status (nth out 0))) "HANDLER skipped")
      (is (= :skipped (:status (nth out 1))) "SIDE EFFECTS skipped")
      (is (nil? (:status (nth out 2))) "SUBSCRIPTIONS not stamped here")))

  (testing "rf2-yz57h — a :before interceptor throw marks the handler skipped"
    (let [out (proj/mark-skipped-handler
                [{:step :handler}]
                [(interceptor-exception-ev :app/auth :before "boom")])]
      (is (= :skipped (:status (first out))))))

  (testing "rf2-yz57h — an :after interceptor throw does NOT mark skipped
            (the handler ran)"
    (let [out (proj/mark-skipped-handler
                [{:step :handler}]
                [(interceptor-exception-ev :app/auth :after "boom")])]
      (is (nil? (:status (first out))))))

  (testing "rf2-yz57h — a plain handler throw does NOT mark skipped (the
            handler ran, then threw)"
    (let [out (proj/mark-skipped-handler
                [{:step :handler}]
                [(handler-exception-ev :e "boom")])]
      (is (nil? (:status (first out))))))

  (testing "rf2-yz57h — a clean cascade leaves steps untouched"
    (let [steps [{:step :handler} {:step :side-effects}]]
      (is (= steps (proj/mark-skipped-handler steps [(run-end-ev 1)]))))))

(deftest step-status-skipped-test
  (testing "rf2-yz57h — `:skipped` status reads through `step-status`"
    (is (= :skipped (proj/step-status {:step :handler :status :skipped})))
    (is (= :ok (proj/step-status {:step :handler})))
    ;; a step both skipped AND carrying an error reads :error (error wins)
    (is (= :error (proj/step-status {:step :handler :status :skipped
                                     :errors [{:message "x"}]})))))

(deftest skipped-handler-not-flagged-error-test
  (testing "rf2-yz57h — the SKIPPED handler does NOT inflate the epoch
            outcome (the failing COEFFECT/INTERCEPTOR step is the :error
            signal; a skip is neutral)"
    (let [;; clean handler step that was skipped + no real exception on it
          steps [{:step :coeffect :badge :COEFFECT :id :c :status :error
                  :errors [{:message "boom"}]}
                 {:step :handler :status :skipped}]
          out   (proj/epoch-outcome steps)]
      (is (= :error out) "the coeffect error drives the outcome")
      (is (= :skipped (proj/step-status (second steps)))
          "the skipped handler reads :skipped, not :error"))))

(deftest interceptor-badge-in-badge-set-test
  (testing "rf2-yz57h — :INTERCEPTOR is a valid badge"
    (is (proj/valid-badge? :INTERCEPTOR))))
