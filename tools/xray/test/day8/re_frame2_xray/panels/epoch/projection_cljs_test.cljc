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
