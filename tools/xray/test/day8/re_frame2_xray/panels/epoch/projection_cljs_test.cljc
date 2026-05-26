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
  ([] (run-end-ev nil nil))
  ([duration-ms] (run-end-ev duration-ms nil))
  ([duration-ms coeffects]
   (ev :rf.event :rf.event/run-end (cond-> {:duration-ms duration-ms}
                                     coeffects (assoc :rf.event/coeffects
                                                      coeffects)))))

(defn- cofx-run-ev
  [id value]
  (ev :rf.cofx :rf.cofx/run {:rf.cofx/id id :rf.cofx/value value}))

(defn- db-changed-ev
  [paths]
  (ev :rf.event :rf.event/db-changed {:rf.event/db-changed-paths paths}))

(defn- do-fx-ev
  [fx]
  (ev :rf.fx :rf.fx/do-fx {:rf.event/fx fx}))

(defn- fx-handled-ev
  [fx-id args duration-ms]
  (ev :rf.fx :rf.fx/handled {:rf.fx/id fx-id
                             :rf.fx/args args
                             :duration-ms duration-ms}))

(defn- flow-recomputed-ev
  [flow-id path before after]
  (ev :rf.flow :rf.flow/recomputed {:rf.flow/id flow-id
                                    :rf.flow/path path
                                    :rf.flow/before before
                                    :rf.flow/after after}))

(defn- sub-run-ev
  [sub-vec changed? before after]
  (ev :rf.sub :rf.sub/run {:rf.sub/query sub-vec
                           :rf.sub/changed? changed?
                           :rf.sub/before before
                           :rf.sub/after after}))

(defn- view-render-ev
  [view-id subs-read]
  (ev :rf.view :rf.view/render {:rf.view/id view-id
                                :rf.view/subs subs-read}))

(defn- machine-transition-ev
  [machine-id before after]
  (ev :rf.machine :rf.machine/transition {:machine-id machine-id
                                          :before before
                                          :after after}))

(defn- machine-guard-ev
  [guard-id outcome]
  (ev :rf.machine :rf.machine/guard-evaluated {:guard-id guard-id
                                               :outcome outcome}))

(defn- machine-action-ev
  [action-id phase outcome]
  (ev :rf.machine :rf.machine/action-ran {:action-id action-id
                                          :phase phase
                                          :outcome outcome
                                          :input {:data {} :event nil}}))

(defn- machine-timer-cancel-ev
  [machine-id state delay reason]
  (ev :rf.machine :rf.machine.timer/cancelled {:machine-id machine-id
                                               :state state
                                               :delay delay
                                               :reason reason}))

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

;; ---- COEFFECT ------------------------------------------------------------

(deftest coeffect-rows-granular-test
  (testing "granular :rf.cofx/run events are preferred when present"
    (let [evs [(cofx-run-ev :session {:user-id 42})
               (cofx-run-ev :now #inst "2026-01-01")]
          rows (proj/coeffect-rows evs)]
      (is (= 2 (count rows)))
      (is (= :session (-> rows first :id)))
      (is (= {:user-id 42} (-> rows first :value))))))

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
  (testing "no fx + no machine = reg-event-db flavour"
    (let [r (proj/handler-row [(db-changed-ev [[[:counter] 5 6 :modified]])]
                              :counter-inc)]
      (is (= :handler (:step r)))
      (is (= :HANDLER (:badge r)))
      (is (= :reg-event-db (:flavour r)))
      (is (= :counter-inc (:event-id r)))
      (is (= 1 (count (:db-diff r))))
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

(deftest machine-lifecycle-grouped-by-phase-test
  (testing "group-lifecycle-by-phase produces phase → rows map"
    (let [rows [{:action-id :a1 :phase :exit}
                {:action-id :a2 :phase :entry}
                {:action-id :a3 :phase :exit}]
          grouped (proj/group-lifecycle-by-phase rows)]
      (is (= 2 (count (:exit grouped))))
      (is (= 1 (count (:entry grouped)))))))

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

;; ---- top-level project --------------------------------------------------

(deftest project-minimal-test
  (testing "minimal epoch (dispatch + handler, no cofx/flow/fx/sub/view)"
    (let [rec   (record [(dispatched-ev [:counter-inc] :ui nil)
                         (db-changed-ev [[[:counter] 5 6 :modified]])])
          steps (proj/project rec)]
      (is (= 2 (count steps)))
      (is (= [:dispatch :handler] (mapv :step steps))))))

(deftest project-full-pipeline-test
  (testing "full epoch with every step → 7 steps emitted"
    (let [rec   (record [(dispatched-ev [:cart/checkout] :ui nil)
                         (cofx-run-ev :session {:user 1})
                         (do-fx-ev {:db {} :http/post {:url "/x"}})
                         (db-changed-ev [[[:cart :state] :idle :placing :modified]])
                         (flow-recomputed-ev :cart-total [:cart :total] 10 20)
                         (fx-handled-ev :db nil 0.1)
                         (fx-handled-ev :http/post {} 12.0)
                         (sub-run-ev [:total] true 10 20)
                         (view-render-ev ::cart-view [:total])])
          steps (proj/project rec)]
      (is (= 7 (count steps)))
      (is (= [:dispatch :coeffect :handler :flow :fx :subscriptions :views]
             (mapv :step steps))))))

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
      (is (= 7 (count proj/badge-set))))))

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
