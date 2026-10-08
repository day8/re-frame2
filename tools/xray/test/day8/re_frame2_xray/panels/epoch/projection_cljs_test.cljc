(ns day8.re-frame2-xray.panels.epoch.projection-cljs-test
  "Pure-data tests for the Epoch panel's projection layer (and the
  `format` display strings it feeds).

  Dual-target: Cognitect's test-runner (CLJ) picks this file up via the
  default `.*-test$` regex, and shadow's `:node-test` build via the
  `cljs-test$` regex, so a green run is a two-host agreement."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [day8.re-frame2-xray.panels.epoch.format :as fmt]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            ;; Canonical trace-event builders shared with the panel-gallery
            ;; synth fixtures: one name set, so per-call-site copies cannot
            ;; drift from the substrate's emit shape.
            [day8.re-frame2-xray.test-helpers.trace-event-builders :as teb]))

;; ---- local fixture aliases ----------------------------------------------

(def ^:private ev                   teb/ev)
(def ^:private dispatched-ev        teb/dispatched-ev)
(def ^:private run-end-ev           teb/run-end-ev)
(def ^:private cofx-run-ev          teb/cofx-run-ev)
(def ^:private db-changed-ev        teb/db-changed-ev)
(def ^:private db-noop-ev           teb/db-noop-ev)
(def ^:private frame-state-changed-ev teb/frame-state-changed-ev)
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
(def ^:private machine-microstep-ev    teb/machine-microstep-ev)
(def ^:private machine-guard-ev        teb/machine-guard-ev)
(def ^:private machine-action-ev       teb/machine-action-ev)
(def ^:private machine-timer-cancel-ev teb/machine-timer-cancel-ev)
(def ^:private machine-unhandled-no-op-ev teb/machine-unhandled-no-op-ev)
(def ^:private machine-started-ev      teb/machine-started-ev)
(def ^:private machine-action-exception-ev teb/machine-action-exception-ev)
(def ^:private machine-history-restored-ev  teb/machine-history-restored-ev)
(def ^:private machine-history-recorded-ev  teb/machine-history-recorded-ev)
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
  (testing "the dispatched trace produces a row with source + coord"
    (is (= {:step :dispatch :badge :DISPATCH :event [:counter-inc] :source :ui
            :coord {:file "ui.cljs" :line 42} :duration-ms nil}
           (proj/dispatch-row [(dispatched-ev [:counter-inc] :ui {:file "ui.cljs" :line 42})]
                              [:counter-inc]))))
  (testing "with no dispatched trace the supplied event vector is the
            fallback, and with neither there is no row"
    (is (= {:step :dispatch :badge :DISPATCH :event [:counter-inc]}
           (proj/dispatch-row [] [:counter-inc])))
    (is (nil? (proj/dispatch-row [] nil)))))

(deftest dispatch-row-source-enrichment-test
  (testing "each source kind builds its own `:source-enrichment` off the event
            vector read at `:rf.event/v`; a kind or shape it cannot build one
            from keeps its `:source` and carries none, so the view renders the
            kind label alone"
    (doseq [[src event extra-tags expected]
            [[:after-timer [:ws/connection [:rf.machine.timer/after-elapsed 250 42 [:active :authenticating]]] {}
              {:machine-id :ws/connection :delay-ms 250 :source-state-path [:active :authenticating]}]
             ;; a scalar slot-3 invoke-id fails soft rather than throwing on `(vec 42)`
             [:after-timer [:m [:rf.machine.timer/after-elapsed 250 1 42]] {} nil]
             [:machine-spawn [:checkout/worker [:rf.machine.spawn/spawned]] {}
              {:spawned-actor-id :checkout/worker}]
             [:fx-dispatch [:cart/add :apple] {:rf.trace/parent-dispatch-id 9001}
              {:parent-dispatch-id 9001}]
             [:fx-dispatch-later [:checkout/retry-prompt]
              {:rf.trace/parent-dispatch-id 9001 :rf.event/source-detail {:ms 500}}
              {:parent-dispatch-id 9001 :delay-ms 500}]
             ;; a root cascade carries no parent id, so no parent-epoch link
             [:fx-dispatch [:cart/add :apple] {} nil]
             [:ui [:counter/inc] {} nil]]]
      (let [ev {:op-type   :rf.event
                :operation :rf.event/dispatched
                :tags      (merge {:rf.event/v event :source src} extra-tags)}]
        (is (= [src expected]
               ((juxt :source :source-enrichment) (proj/dispatch-row [ev] nil)))
            (str src " " (pr-str event)))))))

;; ---- RECORDABLE COEFFECTS (EP-0010 · EP-0017 §9) -------------------------
;;
;; PRIVACY: `:rf/time-ms` is always safe and rides verbatim; every other leaf
;; is value-bearing and redacts by default through `resources-helpers/summarize`,
;; so a row carries a summary, never a raw value.

(defn- dispatched-with-cofx
  "A `:rf.event/dispatched` trace event carrying a flat `:rf.cofx` map
  under `:tags` (the substrate-canonical placement)."
  [event cofx]
  {:op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.event/v event
               :source     :ui
               :rf.cofx    cofx}})

(deftest recordable-cofx-row-time-ms-only-test
  (testing "a :rf.cofx map carrying only :rf/time-ms surfaces the time fact
            verbatim and carries no :inputs slot"
    (is (= {:step :recordable-cofx :badge :RECORDABLE-COFX :time-ms 1781078400123}
           (proj/recordable-cofx-row
             [(dispatched-with-cofx [:counter/inc] {:rf/time-ms 1781078400123})])))))

(deftest recordable-cofx-row-value-bearing-leaves-summarized-test
  (testing "value-bearing leaves keep their owner-qualified id verbatim but
            carry a summarize shape, never the raw value; sorted by leaf id"
    (let [r (proj/recordable-cofx-row
              [(dispatched-with-cofx [:todo/create]
                                     {:rf/time-ms        1781078400123
                                      :counter/delta     {:roll 4}
                                      :rf.route/location {:path "/todos"}})])]
      (is (= 1781078400123 (:time-ms r)))
      (is (= [[:counter/delta "map"] [:rf.route/location "map"]]
             (mapv (juxt :key (comp :type :value)) (:inputs r)))))))

(deftest recordable-cofx-row-filters-to-declared-recordables-test
  (testing "with a DECLARED recordable set only the handler's declared leaves
            survive — the lens must not claim the handler consumed a fact it
            never declared — while :rf/time-ms is always recordable"
    (let [r (proj/recordable-cofx-row
              [(dispatched-with-cofx [:counter/inc] {:rf/time-ms    1781078400123
                                                     :counter/delta {:roll 4}
                                                     :app/extra     {:leak "me"}})]
              #{:counter/delta})]
      (is (= 1781078400123 (:time-ms r)))
      (is (= [:counter/delta] (mapv :key (:inputs r)))))))

(deftest project-threads-declared-recordables-resolver-test
  (let [rec     (record [(dispatched-with-cofx [:counter/inc] {:rf/time-ms    1781078400123
                                                               :counter/delta {:roll 4}
                                                               :app/extra     {:leak "me"}})])
        keys-of (fn [steps]
                  (->> steps
                       (some #(when (= :recordable-cofx (:step %)) %))
                       :inputs
                       (mapv :key)
                       set))]
    (testing "`project` threads `:resolve-event-recordables` through to the
              RECORDABLE COEFFECTS step"
      (is (= #{:counter/delta}
             (keys-of (proj/project rec {:resolve-event-recordables
                                         (fn [event-id]
                                           (when (= :counter/inc event-id)
                                             #{:counter/delta}))})))))
    (testing "with no resolver every non-time leaf surfaces"
      (is (= #{:counter/delta :app/extra} (keys-of (proj/project rec)))))))

(deftest recordable-cofx-row-redacted-value-stays-sentinel-test
  (testing "a value redacted UPSTREAM keeps its sentinel through summarize:
            the row renders [redacted], never the raw value"
    (let [row (first (:inputs (proj/recordable-cofx-row
                                [(dispatched-with-cofx [:prefs/load]
                                                       {:rf/time-ms  1781078400123
                                                        :prefs/theme :rf/redacted})])))]
      (is (true? (:redacted? (:value row))))
      (is (= "[redacted]" (:preview (:value row)))))))

(deftest recordable-cofx-row-absent-when-no-map-test
  (testing "silent by default: no :rf.cofx tag, or an empty map, → no row"
    (is (nil? (proj/recordable-cofx-row [(dispatched-ev [:counter/inc] :ui)])))
    (is (nil? (proj/recordable-cofx-row [(dispatched-with-cofx [:counter/inc] {})])))))

;; ---- generated recordable coeffects (EP-0017 slice B.7 · spec/009 §277) --
;;
;; A generator mints an absent declared fact at PROCESSING-START and emits
;; `:rf.cofx/generated`; the enqueue-time `:rf.cofx` map predates it, so the
;; lens reads the generated fact off the trace op.

(defn- generated-cofx-ev
  "A `:rf.cofx/generated` trace event (spec/009 §277) carrying the produced
  recordable fact under `:tags`."
  [id value]
  (ev :rf.cofx :rf.cofx/generated
      {:rf.cofx/id id :rf.cofx/value value :frame :rf/default}))

(deftest generated-cofx-rows-unit-test
  (testing "the declared-recordable filter applies to generated rows too"
    (is (= [:session/id]
           (mapv :key (proj/generated-cofx-rows [(generated-cofx-ev :session/id "sess-7")
                                                 (generated-cofx-ev :other/x 1)]
                                                #{:session/id}))))))

(deftest recordable-cofx-row-surfaces-generated-test
  (testing "a generated fact follows the supplied leaves, marked :generated?
            and summarized"
    (is (= [[:counter/delta nil "map"] [:session/id true "string"]]
           (mapv (juxt :key :generated? (comp :type :value))
                 (:inputs (proj/recordable-cofx-row
                            [(dispatched-with-cofx [:auth/login] {:rf/time-ms    1781078400123
                                                                  :counter/delta {:roll 4}})
                             (generated-cofx-ev :session/id "sess-7")]))))))
  (testing "a generated fact alone renders the step when the token carried no
            :rf.cofx map at all"
    (is (= [:session/id]
           (mapv :key (:inputs (proj/recordable-cofx-row
                                 [(dispatched-ev [:auth/login] :ui)
                                  (generated-cofx-ev :session/id "sess-7")]))))))
  (testing "a SUPPLIED value wins over a stray generated op for the same key,
            with no duplicate row"
    (is (= [[:session/id nil]]
           (mapv (juxt :key :generated?)
                 (:inputs (proj/recordable-cofx-row
                            [(dispatched-with-cofx [:auth/login] {:session/id "supplied-9"})
                             (generated-cofx-ev :session/id "would-have-generated")])))))))

(deftest project-places-recordable-cofx-after-dispatch-test
  (testing "RECORDABLE COEFFECTS sits right after DISPATCH as its own numbered step"
    (is (= [[:dispatch 1] [:recordable-cofx 2]]
           (->> (proj/project-numbered
                  (record [(dispatched-with-cofx [:counter/inc] {:rf/time-ms 1781078400123})]))
                (take 2)
                (mapv (juxt :step :step-number)))))))

;; ---- COEFFECT ------------------------------------------------------------

(deftest coeffect-rows-granular-test
  (testing "granular `:rf.cofx/run` rows carry the PRODUCED value, and a
            parameterized cofx's requirement arg (`:rf.cofx/arg`) as `:input`"
    (is (= [{:step :coeffect :badge :COEFFECT :id :session :value {:user-id 42}
             :duration-ms nil :input :auth-token}
            {:step :coeffect :badge :COEFFECT :id :now :value #inst "2026-01-01"
             :duration-ms nil}]
           (proj/coeffect-rows [(cofx-run-ev :session {:user-id 42} {:arg :auth-token})
                                (cofx-run-ev :now #inst "2026-01-01")
                                (run-end-ev 0.1 {:session {:user-id 42}
                                                 :now     #inst "2026-01-01"})])))))

(deftest project-threads-cofx-row-fields-through-cofx-steps-test
  (testing "`project` carries each cofx row's `:input` and `:duration-ms` onto
            its COEFFECT step, and omits each when the row has none"
    (is (= [{:step :coeffect :badge :COEFFECT :id :session :value {:user-id 42}
             :duration-ms 18.5 :input :auth-token}
            {:step :coeffect :badge :COEFFECT :id :now :value #inst "2026-01-01"}]
           (filterv #(= :coeffect (:step %))
                    (proj/project
                      (record [(dispatched-ev [:auth/login] :ui nil)
                               (cofx-run-ev :session {:user-id 42} {:arg :auth-token :duration-ms 18.5})
                               (cofx-run-ev :now #inst "2026-01-01")
                               (run-end-ev 0.1 {:session {:user-id 42}
                                                :now     #inst "2026-01-01"})])))))))

(deftest coeffect-rows-granular-without-run-end-test
  (testing "with no run-end coeffects map (an interrupted cascade) the row
            falls back to the run op's produced `:rf.cofx/value`"
    (is (= [{:step :coeffect :badge :COEFFECT :id :testdeck/now
             :value #inst "2026-02-02" :duration-ms nil}]
           (proj/coeffect-rows [(cofx-run-ev :testdeck/now #inst "2026-02-02")])))))

(deftest coeffect-rows-run-end-fallback-test
  (testing "no granular cofx events: fall back to run-end's stamp"
    (is (= [{:step :coeffect :badge :COEFFECT :id :session :value {:user-id 7}}]
           (proj/coeffect-rows [(run-end-ev 0.1 {:session {:user-id 7}})])))))

(deftest coeffect-rows-skip-system-cofx-test
  (testing "system-injected cofx (:db / :event / :frame / :source / :trace-id)
            are filtered on both the granular and the run-end path"
    (is (= [:session]
           (mapv :id (proj/coeffect-rows
                       (conj (mapv #(cofx-run-ev % nil) [:db :event :frame :source :trace-id])
                             (cofx-run-ev :session {:user-id 42}))))))
    (is (= [:session]
           (mapv :id (proj/coeffect-rows
                       [(run-end-ev 0.1 {:db {} :event [:x] :session {:user-id 7}})]))))))

;; ---- HANDLER -------------------------------------------------------------

(deftest handler-row-reads-canonical-elapsed-ms-test
  (testing "the HANDLER duration reads the substrate's canonical
            `:rf.event/elapsed-ms` on `:rf.event/run-end` (spec 009 §238)"
    (is (= 4.2 (:duration-ms (proj/handler-row [(run-end-ev 4.2)] :counter-inc))))))

(deftest handler-row-db-only-flavour-test
  (testing "no fx + no machine = the :db-only flavour: no machine section and
            no precomputed diff (the view diffs `:db-post-handler`)"
    (is (= {:step :handler :badge :HANDLER :flavour :db-only :event-id :counter-inc
            :duration-ms nil :db-post-handler nil :db-write? true :fx [] :fx-vec nil}
           (proj/handler-row [(db-changed-ev [[[:counter] 5 6 :modified]])] :counter-inc)))))

;; ---- :fx-vec — the PRODUCER shape ---------------------------------------

(deftest handler-row-fx-vec-producer-shape-test
  (testing "`:fx-vec` is the PRODUCER's vector-of-vectors verbatim
            (`re-frame.fx/do-fx` stamps `(:fx effects)`), and the do-fx marker
            classifies :effectful. A map-carrier fixture cannot catch a
            `(map? fx)` guard that would leave the slot nil on every real cascade."
    (let [fx-vector [[:http/post {:url "/x"}] [:navigate {:to :home}]]]
      (is (= [:effectful fx-vector]
             ((juxt :flavour :fx-vec)
              (proj/handler-row [(do-fx-ev fx-vector) (db-changed-ev [])] :cart/add)))))))

(deftest handler-row-machine-transition-no-action-test
  (testing "a macrostep that fires no action is still :reg-machine: the
            `:rf.machine/transition` summary marks it, although the machine
            handler's snapshot write always rides a do-fx"
    (let [r (proj/handler-row
              [(do-fx-ev {:db {:hvac/controller {:state {:climate [:running]}}}})
               (machine-transition-ev :hvac/controller {:state [:off] :data {}}
                                      {:state [:running] :data {}} [:hvac/power-cycle] 2)
               (db-changed-ev [[[:hvac/controller] {} {} :modified]])]
              :hvac/power-cycle)]
      (is (= :reg-machine (:flavour r)))
      (is (= [:transition] (mapv :kind (-> r :machine :cascade)))))))

;; ---- machine cascade (time-ordered) -----------------------------------

(deftest machine-cascade-rows-canonical-phase-order-test
  (testing "rows re-sort into canonical phase order (guard → exit → TRANSITION
            → entry → always → after-action → timer) with a STABLE sort, and
            :step renumbers over that order. The substrate emits the transition
            LAST, so it has to be lifted ahead of the entry actions."
    (is (= [[:guard nil :ready? 1]
            [:action :exit :clear-buffer 2]
            [:action :transition :open-socket 3]
            [:transition nil nil 4]
            [:action :entry :arm-heartbeat 5]
            [:action :entry :seed-cache 6]
            [:action :always :pulse 7]
            [:timer nil nil 8]]
           (mapv (juxt :kind :phase #(or (:guard-id %) (:action-id %)) :step)
                 (proj/machine-cascade-rows
                   [(machine-guard-ev :ready? :pass)
                    (machine-action-ev :clear-buffer :exit :ok)
                    (machine-action-ev :open-socket :transition :ok)
                    (machine-action-ev :arm-heartbeat :entry :ok)
                    (machine-action-ev :seed-cache :entry :ok)
                    (machine-action-ev :pulse :always :ok)
                    (machine-transition-ev :ws/conn [:idle] [:connecting] [:ws/start] 1)
                    (machine-timer-cancel-ev :ws/conn [:idle] 500 :on-exit)]))))))

(deftest machine-cascade-row-fields-test
  (testing "each row kind hoists the slots the view reads off its trace"
    (is (= [{:kind :guard :guard-id :form-valid? :outcome :fail :machine-id nil}
            {:kind :transition :machine-id :ws/conn :event [:ws/start] :microsteps 0
             :before {:state [:idle] :data {:n 0}} :after {:state [:active] :data {:n 1}}
             :from-state [:idle] :to-state [:active] :data-before {:n 0} :data-after {:n 1}}
            {:kind :action :action-id :open-socket :phase :entry :threw? false :machine-id nil
             :outcome {:fx [[:http/get {:url "/x"}]] :data {:n 1}}
             :fx [[:http/get {:url "/x"}]] :data-write {:n 1} :data-before {}}
            {:kind :timer :machine-id :ws/conn :state [:idle] :delay 250 :reason :on-supersede}]
           (mapv #(select-keys % [:kind :guard-id :outcome :machine-id :event :microsteps
                                  :before :after :from-state :to-state :data-before
                                  :data-after :action-id :phase :threw? :fx :data-write
                                  :state :delay :reason])
                 (proj/machine-cascade-rows
                   [(machine-guard-ev :form-valid? :fail)
                    (ev :rf.machine :rf.machine/action-ran
                        {:action-id :open-socket
                         :phase     :entry
                         :outcome   {:fx [[:http/get {:url "/x"}]] :data {:n 1}}
                         :input     {:data {} :event nil}})
                    (machine-transition-ev :ws/conn
                                           {:state [:idle] :data {:n 0}}
                                           {:state [:active] :data {:n 1}}
                                           [:ws/start] 0)
                    (machine-timer-cancel-ev :ws/conn [:idle] 250 :on-supersede)]))))))

;; ---- parent-owned parallel `:always` ROUND rows -------------------------
;;
;; A `:type :parallel` macrostep commits ONE aggregate `:rf.machine/transition`
;; but emits one `:rf.machine.microstep/transition` per selected regional
;; round. The transition row carries no nested body, so each round is harvested
;; as a first-class `:microstep` row; a single-active `:always` microstep
;; carries no `:region` and produces no row.

(deftest machine-cascade-rows-parallel-always-round-projects-microstep-rows-test
  (testing "a co-selected regional round projects one :microstep row per
            region, in region order, sharing the parent actor and round-index,
            beside the one aggregate transition row"
    (let [rows (proj/machine-cascade-rows
                 [(machine-transition-ev :par/round
                                         {:state {:a :idle :b :idle} :data {}}
                                         {:state {:a :done :b :done} :data {}}
                                         [:go] 1)
                  (machine-microstep-ev :par/round :a :staged :done 0)
                  (machine-microstep-ev :par/round :b :staged :done 0)])]
      (is (= [:transition :microstep :microstep] (mapv :kind rows)))
      (is (= [{:region :a :round-index 0 :machine-id :par/round
               :from-state :staged :to-state :done :source :always}
              {:region :b :round-index 0 :machine-id :par/round
               :from-state :staged :to-state :done :source :always}]
             (mapv #(select-keys % [:region :round-index :machine-id :from-state :to-state :source])
                   (rest rows)))))))

(deftest machine-cascade-rows-single-active-microstep-produces-no-row-test
  (testing "a region-less (single-active) :always microstep rides the
            transition row's structured cascade, so it produces no row"
    (is (= [:transition]
           (mapv :kind (proj/machine-cascade-rows
                         [(machine-transition-ev :flat/quiz
                                                 {:state [:asking] :data {}}
                                                 {:state [:winner] :data {}}
                                                 [:answer] 1)
                          (ev :rf.machine :rf.machine.microstep/transition
                              {:actor-id :flat/quiz :from :asking :to :winner
                               :microstep-index 0})]))))))

(deftest machine-cascade-rows-action-threw-test
  (testing "an action that threw stamps `:threw? true` and carries the exception"
    (let [exc  #?(:clj  (RuntimeException. "boom")
                  :cljs (ex-info "boom" {}))]
      (is (= [[true exc]]
             (mapv (juxt :threw? :exception)
                   (proj/machine-cascade-rows
                     [(ev :rf.machine :rf.machine/action-ran
                          {:action-id :explode
                           :phase     :entry
                           :outcome   :rf.error/action-threw
                           :exception exc})])))))))

;; ---- the benign unhandled-event no-op -----------------------------------

(deftest machine-cascade-rows-unhandled-no-op-test
  (testing "an unhandled-no-op trace projects to a :no-op row, and a lone
            machine's verb is the consequence only — '[NO OP] staying in
            {state}', no machine name"
    (let [rows (proj/machine-cascade-rows
                 [(machine-unhandled-no-op-ev :door/main [:door/insert-coin] :alarming)])]
      (is (= [{:kind :no-op :machine-id :door/main :event [:door/insert-coin]
               :state :alarming :step 1 :show-machine-name? false}]
             (mapv #(dissoc % :trace-index) rows)))
      (is (= "staying in :alarming" (fmt/cascade-row-label (first rows))))))
  (testing "a multi-machine epoch names WHICH machine stood pat on each row"
    (is (= [":hvac/controller staying in [:off]" ":hvac/fan staying in [:idle]"]
           (mapv fmt/cascade-row-label
                 (proj/machine-cascade-rows
                   [(machine-unhandled-no-op-ev :hvac/controller [:hvac/power-cycle] [:off])
                    (machine-unhandled-no-op-ev :hvac/fan [:hvac/power-cycle] [:idle])])))))
  (testing "a cascade whose only machine activity is the no-op is :reg-machine,
            so the EVENT HANDLER machine section renders it"
    (let [r (proj/handler-row
              [(machine-unhandled-no-op-ev :door/main [:door/insert-coin] :alarming)]
              :door/main)]
      (is (= [:reg-machine [:no-op]] [(:flavour r) (mapv :kind (-> r :machine :cascade))])))))

;; ---- a GUARD-BLOCKED no-op surfaces the blocking guard ------------------
;;
;; A failed guard emits `:rf.machine/guard-evaluated` during the candidate walk
;; and then `:rf.machine.event/unhandled-no-op`; both ride the cascade, so the
;; shared projection (Epoch panel AND Machine Inspector) must not degrade to a
;; guard-blind bare `[NO OP]`.

(deftest guard-blocked-no-op-surfaces-blocking-guard-test
  (testing "the failing :guard row leads the :no-op row, naming the blocking
            guard and its outcome"
    (let [rows (proj/machine-cascade-rows
                 [(machine-guard-ev :may-close? :fail)
                  (machine-unhandled-no-op-ev :door/main [:door/close] :open)])]
      (is (= [:guard :no-op] (mapv :kind rows)))
      (is (= [":may-close?" "fail"]
             ((juxt fmt/cascade-row-label fmt/cascade-outcome-label) (first rows)))))))

;; ---- the machine's [START] badge -----------------------------------------

(deftest machine-started-projects-to-start-row-test
  (testing "a :rf.machine/started trace projects to a :start row carrying the
            initial state + data + cause, and its verb names the machine"
    (let [rows (proj/machine-cascade-rows
                 [(machine-started-ev :door/main :locked {:attempts 0} :explicit)])]
      (is (= [{:kind :start :machine-id :door/main :state :locked :data {:attempts 0}
               :cause :explicit :step 1}]
             (mapv #(dissoc % :trace-index) rows)))
      (is (= ":door/main started in :locked" (fmt/cascade-row-label (first rows))))))
  (testing "a LAZY start folds into the first real event's epoch and still
            leads the cascade, whatever the emit order"
    (is (= [[:start 1] [:transition 2]]
           (mapv (juxt :kind :step)
                 (proj/machine-cascade-rows
                   [(machine-transition-ev :door/main {:state :locked :data {}}
                                           {:state :open :data {}} [:door/unlock] 0)
                    (machine-started-ev :door/main :locked {} :lazy)])))))
  (testing "the cause tag, with :lazy flagged as the ordering smell"
    (is (= "lazy" (fmt/start-cause-label :lazy)))
    (is (true? (fmt/start-cause-smell? :lazy)))
    (is (false? (fmt/start-cause-smell? :explicit))))
  (testing "a pure start is :reg-machine although its snapshot write rides a do-fx"
    (let [r (proj/handler-row
              [(machine-started-ev :door/main :locked {:attempts 0} :explicit)
               (do-fx-ev {:db {}})]
              :door/main)]
      (is (= [:reg-machine [:start]] [(:flavour r) (mapv :kind (-> r :machine :cascade))])))))

(deftest genuine-self-transition-keeps-its-row-test
  (testing "a genuine self-transition (from = to; exit + entry fired) had a
            real match, so it keeps its transition row"
    (is (= [:action :transition :action]
           (mapv :kind (proj/machine-cascade-rows
                         [(machine-action-ev :on-exit :exit :ok)
                          (machine-action-ev :on-entry :entry :ok)
                          (machine-transition-ev :traffic/light {:state [:active]}
                                                 {:state [:active]} [:traffic/tick] 1)]))))))

(deftest machine-action-exception-row-attributes-wildcard-test
  (testing "exception-row lifts the machine attribution and the :* wildcard
            flag off the :transition slot, so the card can name a wildcard throw"
    (is (= {:operation :rf.error/machine-action-exception :machine-id :fuse/box
            :action-id :blow-fuse :event [:fuse/short-circuit]
            :message "unhandled machine event" :via-wildcard? true}
           (select-keys (first (proj/exception-rows
                                 [(machine-action-exception-ev
                                    {:machine-id :fuse/box :action-id :blow-fuse
                                     :event [:fuse/short-circuit]
                                     :message "unhandled machine event"
                                     :via-wildcard? true})]))
                        [:operation :machine-id :action-id :event :message :via-wildcard?])))))

(deftest machine-cascade-rows-empty-when-no-machine-events-test
  (testing "a non-machine cascade projects an empty cascade; the view's
            plain-handler branch keys off it"
    (is (= [] (proj/machine-cascade-rows
                [(dispatched-ev [:counter/inc])
                 (db-changed-ev [[[:count] 0 1 :modified]])
                 (fx-handled-ev :http/post {} 0.1)])))))

(deftest machine-logical-state-test
  (testing "a snapshot projects to `{:state :tags}` only; nil stays nil"
    (is (= {:state :locked :tags #{:locked}}
           (proj/machine-logical-state {:state            :locked
                                        :tags             #{:locked}
                                        :data             {:tries 0}
                                        :meta             {:created 1}
                                        :rf/spawn-counter {}})))
    (is (nil? (proj/machine-logical-state nil)))))

(deftest machine-logical-state-changed?-test
  (testing "true iff `{:state :tags}` differs, so a self transition that moved
            only :data / :rf/* elides the delta box"
    (is (true? (proj/machine-logical-state-changed?
                 {:state :locked :tags #{:locked} :data {:n 0}}
                 {:state :open   :tags #{:open}   :data {:n 0}})))
    (is (false? (proj/machine-logical-state-changed?
                  {:state :open :tags #{:open} :data {:n 1} :rf/spawn-counter {}}
                  {:state :open :tags #{:open} :data {:n 2} :rf/spawn-counter {:a 1}})))))

;; ---- STRUCTURED transition cascade ------------------------------------
;;
;; The `:rf.machine/transition` trace carries a structured `:cascade` step
;; vector. The HVAC `[:hvac/power-cycle]` cascade below is the contract shape
;; the instrumentation test (`re-frame.machine-cascade-instrumentation-cljs-test`)
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

(deftest transition-cascade-row-threads-structured-cascade-test
  (testing "the transition row threads the structured `:cascade` verbatim"
    (is (= [hvac-power-cycle-cascade]
           (mapv :cascade
                 (proj/machine-cascade-rows
                   [(machine-transition-ev :hvac/controller
                                           {:state {:climate :idle :fan :off} :data {}}
                                           {:state {:climate [:running :conditioning :heating] :fan :on}
                                            :data  {}}
                                           [:hvac/power-cycle] 0 hvac-power-cycle-cascade)]))))))

(deftest cascade-regions-groups-parallel-per-region-test
  (testing "`cascade-regions` groups the steps per region in first-encounter
            (declaration) order, so the view renders climate before fan"
    (is (= [{:region :climate :steps (subvec hvac-power-cycle-cascade 0 5)}
            {:region :fan     :steps (subvec hvac-power-cycle-cascade 5)}]
           (proj/cascade-regions hvac-power-cycle-cascade)))))

(deftest cascade-row-label-test
  (testing "`cascade-row-label` renders each kind's verb. The kind pill and
            phase chip already name the kind, so the verb carries no prefix; an
            inline fn id renders a legible placeholder, never the fn object."
    (are [row label] (= label (fmt/cascade-row-label row))
      {:kind :guard :guard-id :ready?}                       ":ready?"
      {:kind :action :action-id :open-socket :phase :entry}  ":open-socket"
      {:kind :action :phase :exit}                           ""
      {:kind :guard :guard-id (fn [_] true)}                 "⟨inline⟩"
      {:kind :action :action-id (fn [_] {}) :phase :entry}   "⟨inline⟩"
      {:kind :timer :state [:idle] :reason :on-exit}         "timer [:idle] · on-exit"
      {:kind :transition :machine-id :ws/conn
       :from-state [:idle] :to-state [:connecting]}          "[:idle] → [:connecting]")))

(deftest cascade-guard-for-state-test
  (testing "a guard gates its transition's :source-state, falling back to
            :target-state when no source was stamped"
    (is (= :open (fmt/cascade-guard-for-state {:kind :guard :source-state :open :target-state :closed})))
    (is (= :closed (fmt/cascade-guard-for-state {:kind :guard :target-state :closed})))))

;; ---- cascade-row source keys ---------------------------------------------
;;
;; A carried exact `:spec-path`, or the substrate's `:transition-slot`
;; spec-path discriminator, wins over reconstructing the key from the row's
;; phase + enriched states; a keyword id is a named handler.

(deftest cascade-row-source-key-test
  (let [inline (fn [_] {})]
    (are [row k] (= k (fmt/cascade-row-source-key row))
      ;; reconstructed from phase + state
      {:kind :action :action-id inline :phase :entry :target-state :connected}
      [:states :connected :entry]
      {:kind :action :action-id inline :phase :exit :source-state :idle}
      [:states :idle :exit]
      ;; index-free, like a single-map `:always`; the view also probes index 0
      {:kind :action :action-id inline :phase :always :source-state :a}
      [:states :a :always :action]
      {:kind :action :action-id inline :phase :transition :source-state :idle :event-id :submit}
      [:states :idle :on :submit :action]
      {:kind :action :action-id inline :phase :after-action :source-state :idle}
      [:states :idle :after :action]
      {:kind :guard :guard-id inline :source-state :idle :event-id :submit}
      [:states :idle :on :submit :guard]
      {:kind :transition :source-state :idle :event-id :submit}
      [:states :idle :on :submit]
      {:kind :timer :state :idle}
      [:states :idle]
      ;; the carried discriminator names the candidate index / delay-key
      {:kind :action :action-id inline :phase :transition :source-state :idle :event-id :submit
       :transition-slot {:slot :on :event-key :submit :decl-path [:idle] :candidate-idx 2}}
      [:states :idle :on :submit 2 :action]
      {:kind :action :action-id inline :phase :after-action :source-state :idle
       :transition-slot {:slot :after :delay-key 1000 :decl-path [:idle] :candidate-idx nil}}
      [:states :idle :after 1000 :action]
      {:kind :guard :guard-id inline :source-state :idle :event-id :submit
       :spec-path [:states :idle :on :submit 1 :guard]}
      [:states :idle :on :submit 1 :guard]
      ;; a keyword id is a named handler: its definition site
      {:kind :action :action-id :open-socket :phase :entry :target-state :connected}
      [:actions :open-socket]
      {:kind :guard :guard-id :ready? :source-state :idle :event-id :submit}
      [:guards :ready?])))

(deftest transition-slot->spec-prefix-test
  (testing "the discriminator → spec-path prefix for the selection forms the
            source-key rows above do not reach"
    (are [slot prefix] (= prefix (fmt/transition-slot->spec-prefix slot))
      ;; a root / parallel-root :on lives OUTSIDE :states
      {:slot :on :event-key :logout :decl-path [] :root? true}  [:on :logout]
      {:slot :always :decl-path [:loading] :candidate-idx nil}  [:states :loading :always]
      {}                                                        nil)))

(deftest machine-cascade-rows-enriches-rows-with-states-test
  (testing "`machine-cascade-rows` stamps `:source-state` /
            `:target-state` / `:event-id` onto every row from the
            surrounding transition emit, so inline-fn source-key lookup can
            resolve spec-path tuples. A row AHEAD of the transition takes the
            next transition's slots; a row BEHIND the last transition (a
            post-commit timer-cancel) falls back to the preceding one; with
            no transition at all the slots stay nil."
    (let [tx    (machine-transition-ev :ws/conn
                                       {:state :idle :data {}}
                                       {:state :connected :data {}}
                                       [:ws/start] 0)
          slots (fn [evs]
                  (mapv (juxt :kind :source-state :target-state :event-id)
                        (proj/machine-cascade-rows evs)))]
      (are [evs expected] (= expected (slots evs))
        ;; rows come back in canonical phase order: guard → exit →
        ;; TRANSITION → entry
        [(machine-guard-ev :ready? :pass)
         (machine-action-ev :clear-buffer :exit :ok)
         (machine-action-ev :open-socket :entry :ok)
         tx]
        [[:guard      :idle :connected :ws/start]
         [:action     :idle :connected :ws/start]
         [:transition :idle :connected :ws/start]
         [:action     :idle :connected :ws/start]]

        ;; the timer-cancel trails the last transition → prior fallback
        [(machine-guard-ev :ready? :pass)
         tx
         (machine-timer-cancel-ev :ws/conn [:idle] 250 :on-exit)]
        [[:guard      :idle :connected :ws/start]
         [:transition :idle :connected :ws/start]
         [:timer      :idle :connected :ws/start]]

        ;; a guard-only failed cascade fires no transition
        [(machine-guard-ev :ready? :fail)]
        [[:guard nil nil nil]]))))

(deftest state-spec-path-prefix-test
  (testing "a state form coerces into the `[:states …]` spec-path prefix the
            macro's source-coord index uses"
    (are [state prefix] (= prefix (proj/state-spec-path-prefix state))
      :idle           [:states :idle]
      [:outer :inner] [:states :outer :states :inner]
      nil             nil)))

(deftest state-node-source-coords-test
  (testing "reads the co-located `:source-coords` off the MAP node at a
            spec-path, walking UP from an inline-fn slot (a value, not a map)
            to its enclosing node"
    (let [spec {:initial :active
                :states  {:active {:source-coords {:file "a.cljs" :line 10}
                                   :on {:go {:target        :done
                                             :action        (fn [_] {})
                                             :source-coords {:file "a.cljs" :line 12}}}}}}]
      (is (= {:file "a.cljs" :line 10} (proj/state-node-source-coords spec [:states :active])))
      (is (= {:file "a.cljs" :line 12}
             (proj/state-node-source-coords spec [:states :active :on :go :action])))
      (is (nil? (proj/state-node-source-coords {:states {:x {}}} [:states :x :entry]))))))

(deftest cascade-outcome-label-test
  (testing "kind-specific outcome strings"
    (is (= "ok"    (fmt/cascade-outcome-label {:kind :action :outcome :ok})))
    (is (= "threw" (fmt/cascade-outcome-label
                     {:kind :action :threw? true :outcome :rf.error/action-threw})))
    (is (= "cancelled (on-exit)"
           (fmt/cascade-outcome-label {:kind :timer :reason :on-exit}))))
  (testing "a transition, a no-op and a start carry NO outcome chip: the verb
            and the pill already tell the whole story"
    (is (= [nil nil nil]
           (mapv fmt/cascade-outcome-label
                 [{:kind :transition :microsteps 3} {:kind :no-op} {:kind :start}])))))

;; ---- FLOW ---------------------------------------------------------------

(deftest flow-steps-event-bundle-shape-test
  (testing "N flow events → N first-class FLOW steps in substrate order. With
            no t1/t2 snapshots on the stream the steps carry no `:db-pre-flow`
            / `:db-post-flow`, so the view renders the scalar before → after line."
    (is (= [{:step :flow :badge :FLOW :flow-id :cart/total :frame nil
             :path [:cart :total] :before 120 :after 195}
            {:step :flow :badge :FLOW :flow-id :cart/n-items :frame nil
             :path [:cart :n] :before 2 :after 3}]
           (filterv #(= :flow (:step %))
                    (proj/project
                      (record [(dispatched-ev [:checkout/begin])
                               (flow-recomputed-ev :cart/total [:cart :total] 120 195)
                               (flow-recomputed-ev :cart/n-items [:cart :n] 2 3)])))))))

(deftest flow-rows-reads-canonical-substrate-shape-test
  (testing "the reader takes the substrate's `:rf.flow/computed` op with BARE
            `:flow-id` / `:path` / `:before` / `:result` / `:elapsed-ms` tags;
            the view-side `:after` is the substrate's `:result`"
    (is (= [{:flow-id :cart/total :frame nil :path [:cart :total]
             :before 120 :after 195 :duration-ms 0.7}]
           (proj/flow-rows [(ev :rf.flow :rf.flow/computed
                                {:flow-id    :cart/total
                                 :path       [:cart :total]
                                 :before     120
                                 :result     195
                                 :elapsed-ms 0.7})])))))

;; ---- t1 / t2 db attribution ---------------------------------------------
;;
;; The HANDLER step's `:db` must reflect ONLY the handler's change and the FLOW
;; step only the flow's own. The projection reads the t1
;; (`:rf.event/db-pending`, post-handler/pre-flow) + t2
;; (`:rf.event/db-pending-post-flow`, post-flow) snapshots off the trace stream;
;; the record's `:db-after` is the FINAL post-flow state.

(deftest handler-and-flow-steps-each-carry-their-own-db-change-test
  (let [t1    {:base 2 :baseline 1 :derived 2}  ; post-handler: :derived untouched
        t2    {:base 2 :baseline 1 :derived 4}  ; post-flow: :derived recomputed 2 → 4
        steps (proj/project {:event-id     :standard-epochs/increment-flow
                             :db-before    {:base 1 :baseline 0 :derived 2}
                             :db-after     t2
                             :trace-events [(dispatched-ev [:standard-epochs/increment-flow])
                                            (db-pending-ev t1)
                                            (flow-recomputed-ev :standard-epochs/derived [:derived] 2 4)
                                            (db-pending-post-flow-ev t2)
                                            (run-end-ev 0.3)]})]
    (testing "HANDLER's `:db` is the t1 snapshot, not the flow-augmented :db-after"
      (is (= t1 (:db-post-handler (some #(when (= :handler (:step %)) %) steps)))))
    (testing "FLOW carries the t1 → t2 pair, so the view diffs the flow's own change"
      (is (= [[:standard-epochs/derived t1 t2]]
             (->> steps
                  (filter #(= :flow (:step %)))
                  (mapv (juxt :flow-id :db-pre-flow :db-post-flow))))))))

;; ---- no-`:db`-effect-but-has-flow edge case -----------------------------
;;
;; A handler can return NO `:db` yet still trigger a flow: the substrate stamps
;; no t1 but does stamp t2. The post-handler db is then `db-before`.

(deftest handler-wrote-no-db-but-a-flow-fired-test
  (let [db-before {:base 1 :derived 2}
        t2        {:base 1 :derived 4}
        steps     (proj/project {:event-id     :synthetic/flow-only
                                 :db-before    db-before
                                 :db-after     t2
                                 :trace-events [(dispatched-ev [:synthetic/flow-only])
                                                (flow-recomputed-ev :synthetic/derived [:derived] 2 4)
                                                (db-pending-post-flow-ev t2)
                                                (run-end-ev 0.2)]})]
    (testing "HANDLER's effective db is db-before: the flow's recompute does not
              leak into the handler's change"
      (is (= db-before (:db-post-handler (some #(when (= :handler (:step %)) %) steps)))))
    (testing "FLOW diffs t2 against that db-before baseline, not the scalar fallback"
      (is (= [[db-before t2]]
             (->> steps
                  (filter #(= :flow (:step %)))
                  (mapv (juxt :db-pre-flow :db-post-flow))))))))

;; ---- SIDE EFFECTS step — flat ledger ------------------------------------
;;
;; `proj/side-effects-step` returns ONE flat `:rows` vec in EXECUTION order:
;; the synthesised `:db` row first (when present), then `:rf.db/runtime`, then
;; the `:fx`-vector rows. Each row keeps its own `:status`; the single badge
;; status is `proj/side-effects-badge-status` (AND-of-rows; SKIPPED neutral).

(deftest side-effects-step-conditional-test
  (testing "no side effect at all → the step is OMITTED"
    (is (nil? (proj/side-effects-step []))))
  (testing ":fx entries → one flat row per fx, in execution order"
    (is (= {:step :side-effects :badge :SIDE-EFFECTS :threw 0
            :rows [{:fx-id :http/post :status :ok :args {:url "/x"} :duration-ms 12.0}
                   {:fx-id :navigate  :status :ok :args {:to :home}  :duration-ms 0.4}]}
           (proj/side-effects-step [(fx-handled-ev :http/post {:url "/x"} 12.0)
                                    (fx-handled-ev :navigate {:to :home} 0.4)])))))

(deftest side-effects-db-row-first-and-pass-test
  (testing "a bare :db commit (no :fx) still shows the SIDE EFFECTS step, with
            one passing :db row — keyed off `:rf.event/db-changed`"
    (is (= [{:fx-id :db :status :ok}]
           (:rows (proj/side-effects-step [(db-changed-ev [[[:counter] 0 1 :edit]])]))))))

(deftest side-effects-db-row-schema-fail-only-test
  (testing "a :db schema-fail rollback carries just the :db ✗ row"
    (is (= [{:fx-id :db :status :error}]
           (:rows (proj/side-effects-step
                    [(db-changed-ev [])
                     (schema-violation-ev :app-db :counter/inc [:counter] -3 true)]))))))

(deftest side-effects-db-row-noop-test
  (testing "an unchanged-db commit (`:rf.event/db-noop`) still surfaces the :db
            row, as :noop, so the event visibly ran and committed nothing — and
            :noop is neutral, never a failure"
    (let [rows (:rows (proj/side-effects-step [(db-noop-ev)]))]
      (is (= [{:fx-id :db :status :noop}] rows))
      (is (= :ok (proj/side-effects-badge-status rows))))))

(deftest side-effects-per-row-status-test
  (testing "each :fx row carries its own status, read off the trace ops"
    ;; The override is in the PRODUCER's shape: override-applied carries only
    ;; `:rf.fx/from` / `:rf.fx/to`, never `:rf.fx/id`, and the replacement's own
    ;; `:rf.fx/handled` follows it.
    (let [s (proj/side-effects-step
              [(fx-handled-ev :http/post {} 1.0)
               (teb/fx-override-applied-ev :metrics :re-frame.fx/fn-value)
               (fx-handled-ev :metrics {} 0.2)
               (ev :warning :rf.fx/skipped-on-platform {:rf.fx/id :clipboard})
               (ev :error :rf.error/fx-handler-exception {:rf.fx/id :bad-fx})])]
      (is (= [[:http/post :ok] [:metrics :overridden] [:clipboard :skipped] [:bad-fx :error]]
             (mapv (juxt :fx-id :status) (:rows s)))
          "the override row is provenance, never a row of its own")
      (is (= 1 (:threw s))))))

;; ---- `↺` overridden, off override PROVENANCE only ------------------------
;;
;; Every fixture is the producer's shape: a function override emits
;; `:rf.fx/override-applied {:rf.fx/from X :rf.fx/to :re-frame.fx/fn-value}`
;; just before the function fires, then `:rf.fx/handled {:rf.fx/id X}`; a
;; keyword redirect emits `{:rf.fx/from X :rf.fx/to Y}`, then
;; `:rf.fx/handled {:rf.fx/id Y :rf.fx/from X}`.

(deftest side-effects-overridden-fx-reads-overridden-test
  (testing "REGRESSION — a function override paints ↺ on the emitted id, and
            names the replacement, rather than reading ✓ like the real handler"
    (is (= [{:fx-id :http/post :status :overridden :override-to :re-frame.fx/fn-value}]
           (mapv #(select-keys % [:fx-id :status :override-to])
                 (proj/fx-effect-rows
                   [(teb/fx-override-applied-ev :http/post :re-frame.fx/fn-value)
                    (fx-handled-ev :http/post {:url "/x"} 0.1)])))))
  (testing "REGRESSION — a keyword redirect is keyed on the id the handler
            EMITTED, the target as detail, rather than reading `✓ <target>`
            with the emitted id gone from the ledger"
    (is (= [{:fx-id :http/post :status :overridden :override-to :http/fake}]
           (mapv #(select-keys % [:fx-id :status :override-to])
                 (proj/fx-effect-rows
                   [(teb/fx-override-applied-ev :http/post :http/fake)
                    (fx-handled-ev :http/fake {:url "/x"} 0.1 :http/post)])))))
  (testing "a function override that delegated to the real handler is still
            overridden — rows the replacement emitted in between do not break
            the pairing"
    (is (= [:overridden]
           (mapv :status (proj/fx-effect-rows
                           [(teb/fx-override-applied-ev :rf.http/managed :re-frame.fx/fn-value)
                            (teb/http-issued-ev :app/load "/api/load")
                            (fx-handled-ev :rf.http/managed {} 1)]))))))

(deftest side-effects-override-never-hides-a-failure-test
  (testing "a replacement that THREW reads ✗, not ↺"
    (is (= [:error]
           (mapv :status (:rows (proj/side-effects-step
                                  [(teb/fx-override-applied-ev :http/post :re-frame.fx/fn-value)
                                   (ev :error :rf.error/fx-handler-exception
                                       {:rf.fx/id :http/post})])))))))

(deftest side-effects-override-controls-test
  (testing "CONTROL — an override of a DIFFERENT fx does not mark this one,
            and a later handled row for the overridden id outside the window
            is not marked either"
    (is (= [:ok :ok]
           (mapv :status
                 (proj/fx-effect-rows
                   [(teb/fx-override-applied-ev :app/other :re-frame.fx/fn-value)
                    (fx-handled-ev :http/post {} 0.1)
                    (fx-handled-ev :app/other {} 0.1)]))))))

(deftest side-effects-badge-and-of-rows-test
  (testing "the badge is the AND of the present rows: :error iff any row is a
            real failure, with SKIPPED rows neutral and an attached `:errors`
            vec counting"
    (are [rows status] (= status (proj/side-effects-badge-status rows))
      [{:status :ok} {:status :skipped}]                                     :ok
      [{:status :ok} {:status :skipped} {:status :error}]                    :error
      [{:status :ok :errors [{:operation :rf.error/fx-handler-exception}]}]  :error)))

(deftest side-effects-fx-attribution-from-machine-actions-test
  (testing "an fx a machine action emitted carries `:attributed-to` that action"
    (is (= [{:action-id :open-socket :phase :entry}]
           (mapv :attributed-to
                 (:rows (proj/side-effects-step
                          [(ev :rf.machine :rf.machine/action-ran
                               {:action-id :open-socket
                                :phase     :entry
                                :outcome   {:fx [[:http/get {:url "/x"}]]}
                                :input     {:data {} :event nil}})
                           (fx-handled-ev :http/get {:url "/x"} 5.0)])))))))

;; ---- NO `other` tier ----------------------------------------------------
;;
;; A top-level effect key outside `re-frame.events/closed-effect-map-keys` is
;; refused pre-commit (it surfaces through `attach-unclassified-errors`), and
;; every key inside it is legal — so an `other` tier has no true positive and
;; would accuse the runtime of ignoring effects it applied.

(deftest side-effects-no-other-tier-test
  (testing "a handler returning an EP-0025 classification effect (`:sensitive`)
            beside :db and :fx gets NO ledger row for it: the framework applies
            it with the :db write. Written against the MAP carrier, the shape an
            `other` reader would consume."
    (is (= [:db :http/post]
           (mapv :fx-id (:rows (proj/side-effects-step
                                 [(do-fx-ev {:db        {:n 1}
                                             :sensitive [[:creds :password]]
                                             :fx        [[:http/post {}]]})
                                  (db-changed-ev [])
                                  (fx-handled-ev :http/post {} 1.0)])))))))

;; ---- runtime-db (`:rf.db/runtime`) state effect — EP-0001 --------------

(deftest side-effects-runtime-db-row-test
  (testing "a runtime-ONLY commit shows a first-class :rf.db/runtime ✓ row,
            keyed off the partition-tagged `:rf.event/frame-state-changed` (a
            runtime-only commit emits no `:rf.event/db-changed`)"
    (is (= [{:fx-id :rf.db/runtime :status :ok}]
           (:rows (proj/side-effects-step
                    [(do-fx-ev {:rf.db/runtime {:machines {:foo {:state [:idle]}}}})
                     (frame-state-changed-ev #{:runtime-db})])))))
  (testing "the ledger reads :db, then :rf.db/runtime, then :fx — the runtime
            write an APPLIED state effect, never a skipped / `other` row"
    (is (= [[:db :ok] [:rf.db/runtime :ok] [:http/post :ok]]
           (mapv (juxt :fx-id :status)
                 (:rows (proj/side-effects-step
                          [(do-fx-ev {:db {:n 1} :rf.db/runtime {:machines {}} :fx [[:http/post {}]]})
                           (db-changed-ev [])
                           (frame-state-changed-ev #{:app-db :runtime-db})
                           (fx-handled-ev :http/post {} 1.0)]))))))
  (testing "a :machine-data schema-fail rollback paints the runtime-db row ✗"
    (is (= [{:fx-id :rf.db/runtime :status :error}]
           (:rows (proj/side-effects-step
                    [(do-fx-ev {:rf.db/runtime {:machines {}}})
                     (frame-state-changed-ev #{:runtime-db})
                     (schema-violation-ev :machine-data :some/machine
                                          [:machines] {:bad true} true)]))))))

(deftest runtime-db-machine-data-violation-attaches-to-row-test
  (testing "a :where :machine-data violation attaches to the SIDE EFFECTS
            step's :rf.db/runtime row"
    (let [violation (schema-violation-ev :machine-data :some/machine
                                         [:machines] {:bad true} true)
          [out]     (proj/attach-violations
                      [(proj/side-effects-step [(do-fx-ev {:rf.db/runtime {:machines {}}})
                                                (frame-state-changed-ev #{:runtime-db})
                                                violation])]
                      (proj/schema-violation-rows [violation]))]
      (is (= [[:rf.db/runtime 1]]
             (mapv (juxt :fx-id (comp count :violations)) (:rows out)))))))

;; ---- SUBSCRIPTIONS ------------------------------------------------------

(deftest subscriptions-step-conditional-test
  (testing "no sub events → the step is OMITTED"
    (is (nil? (proj/subscriptions-step []))))
  (testing "sub-run rows read the substrate's canonical `:rf.sub/*` tags; a run
            with no first-run flag, cause, window or inputs carries the
            defaults and omits the optional slots"
    (is (= {:step :subscriptions :badge :SUBSCRIPTIONS :changed 1 :unchanged 1
            :rows [{:sub-id :total :sub-vec [:total] :inputs nil :changed? true
                    :first-run? false :before 5 :after 6 :cascade? false :duration-ms nil}
                   {:sub-id :other :sub-vec [:other] :inputs nil :changed? false
                    :first-run? false :before :x :after :x :cascade? false :duration-ms nil}]}
           (proj/subscriptions-step [(sub-run-ev [:total] true 5 6)
                                     (sub-run-ev [:other] false :x :x)])))))

(deftest false-valued-tags-survive-projection-test
  (testing "a false sub value projects `false`, not nil, and is not shadowed
            by a fixture fallback key"
    (is (= [false false]
           ((juxt :before :after)
            (first (proj/subscription-rows
                     [(ev :rf.sub :rf.sub/run
                          {:rf.sub/id         :user/flag
                           :rf.sub/value      false
                           :rf.sub/after      :legacy
                           :rf.sub/prev-value false
                           :rf.sub/before     :legacy})]))))))
  (testing "a schema violation's false :value survives"
    (is (false? (:value (first (proj/schema-violation-rows
                                 [(ev :error :rf.error/schema-validation-failure
                                      {:where :sub-return :failing-id :user/flag
                                       :value false :mismatching-value :legacy})])))))))

(deftest subscriptions-row-carries-first-run-flag-test
  (testing "`:rf.sub/first-run?` lifts onto the row, so the view paints
            `:added` for a fresh cache entry rather than `← was X`"
    (is (true? (:first-run? (first (proj/subscription-rows
                                     [(ev :rf.sub :rf.sub/run
                                          {:rf.sub/id             :counter/last-clicked
                                           :rf.sub/value-changed? true
                                           :rf.sub/first-run?     true
                                           :rf.sub/value          1779972561856})])))))))

(deftest subscriptions-row-carries-cause-event-id-test
  (testing "`:rf.sub/cause-event-id` lifts onto the row for the view's
            `caused by <event-id>` chrome"
    (is (= :counter/inc (:cause-event-id (first (proj/subscription-rows
                                                  [(ev :rf.sub :rf.sub/run
                                                       {:rf.sub/id             :counter/value
                                                        :rf.sub/cause-event-id :counter/inc})])))))))

(deftest subscriptions-row-carries-epoch-window-test
  (testing "a run recorded after its cascade settled carries `:epoch-window`,
            and its label names every epoch in it"
    (is (= [5 7] (:epoch-window (first (proj/subscription-rows
                                         [(ev :rf.sub :rf.sub/run
                                              {:rf.sub/id           :runner/step
                                               :rf.sub/epoch-window [5 7]})])))))
    (is (= "recomputed after epochs #5..#7" (fmt/epoch-window-label [5 7])))
    (is (= "recomputed after epoch #7" (fmt/epoch-window-label [7 7])))))

(deftest subscriptions-row-wraps-cause-sub-as-query-vector-test
  (testing "`:inputs` is always a vector OF query-vectors: the single
            `:rf.sub/cause-sub` is wrapped, so a parameterized cause renders as
            ONE input, while `:rf.sub/inputs` passes through as-is"
    (are [tags inputs] (= inputs (:inputs (first (proj/subscription-rows
                                                   [(ev :rf.sub :rf.sub/run
                                                        (assoc tags :rf.sub/id :report/x))]))))
      {:rf.sub/cascade? true :rf.sub/cause-sub [:article/by-id :a1]}  [[:article/by-id :a1]]
      {:rf.sub/inputs [[:sales] [:costs]]}                            [[:sales] [:costs]])))

(deftest subscriptions-step-counts-changed-vs-unchanged-test
  (testing "the step header carries the changed / unchanged split"
    (is (= [1 2]
           ((juxt :changed :unchanged)
            (proj/subscriptions-step [(sub-run-ev [:a] true 1 2)
                                      (sub-run-ev [:b] false :x :x)
                                      (sub-run-ev [:c] false :y :y)]))))))

(deftest subscriptions-step-surfaces-disposed-rows-test
  (testing "a dispose-only cascade still renders the step, with each cache
            eviction under `:disposed-rows`"
    (is (= {:step :subscriptions :badge :SUBSCRIPTIONS :rows [] :changed 0 :unchanged 0
            :disposed-rows [{:sub-id :cart/items :query [:cart/items]
                             :reason :no-more-derefers :frame :rf/default}]}
           (proj/subscriptions-step [(sub-dispose-ev [:cart/items] :no-more-derefers)])))))

;; ---- VIEWS --------------------------------------------------------------

(deftest views-step-conditional-test
  (testing "no view events → the step is OMITTED"
    (is (nil? (proj/views-step []))))
  (testing "a `:rf.view/rendered` marker → a :rendered row carrying the
            view-id, the subs it dereffed and its duration; with no
            render-args the args slots are absent"
    (is (= {:step :views :badge :VIEWS
            :rows [{:view-id :app.counter/Counter :instance nil
                    :subs-read [[:counter/total] [:counter/threshold]] :sub-status {}
                    :status :rendered :mount? nil :triggered-by nil :cause :props
                    :duration-ms 1.2}]}
           (proj/views-step [(view-render-ev :app.counter/Counter
                                             [[:counter/total] [:counter/threshold]]
                                             1.2)])))))

(deftest render-cause-classifier-test
  (testing "a first render is :mount even when a triggered-by is present"
    (is (= :mount (proj/render-cause true :counter/total)))))

(deftest views-step-attributes-render-cause-test
  (testing "each view row attributes its render: a changed sub, props (no own
            sub changed), or a mount"
    (is (= [{:kind :sub :sub-id :child-a/value} :props :mount]
           (mapv :cause (:rows (proj/views-step
                                 [(view-render-ev :app/ChildA [[:child-a/value]] 0.4
                                                  {:triggered-by :child-a/value})
                                  (view-render-ev :app/ChildB [[:child-b/label]] 0.3 {})
                                  (view-render-ev :app/ChildC [] 0.2 {:mount? true})])))))))

(deftest views-step-folds-unmounted-into-rows-test
  (testing "unmounted rows ride the SAME `:rows` after the rendered ones, with
            a tail count for the header verb"
    (let [s (proj/views-step [(view-render-ev :app/Counter [])
                              (view-unmounted-ev :app/SidebarItem [:SidebarItem 0] :rf/default)])]
      (is (= [[:rendered :app/Counter] [:unmounted :app/SidebarItem]]
             (mapv (juxt :status :view-id) (:rows s))))
      (is (= 1 (:unmounted-count s)))))
  (testing "an unmount-only cascade still renders the step"
    (is (= {:step :views :badge :VIEWS :unmounted-count 1
            :rows [{:view-id :app/Tooltip :instance [:Tooltip 0] :frame :rf/default
                    :subs-read [] :sub-status {} :status :unmounted :unmounted? true}]}
           (proj/views-step [(view-unmounted-ev :app/Tooltip [:Tooltip 0] :rf/default)])))))

(deftest views-row-sub-status-join-test
  (testing "each sub a view dereffed joins to how it behaved this epoch — :new
            (first run), :changed, :unchanged — keyed by the query-vector the
            cell renders, and indexed under the bare sub-id too"
    (let [events [(assoc-in (sub-run-ev [:counter/total] false nil 5)
                            [:tags :rf.sub/first-run?] true)
                  (sub-run-ev [:counter/parity] true 0 1)
                  (sub-run-ev [:counter/label] false "n" "n")
                  (view-render-ev :app/Counter
                                  [[:counter/total] [:counter/parity] [:counter/label]])]]
      (is (= {[:counter/total] :new [:counter/parity] :changed [:counter/label] :unchanged}
             (:sub-status (first (:rows (proj/views-step events))))))
      (is (= :new (get (proj/sub-status-index events) :counter/total))))))

(deftest views-row-render-args-diff-test
  (testing "a re-rendered instance diffs against ITS OWN previous render-args,
            keyed by render-key; a first render has none"
    (is (= [[[{:label "a" :n 1}] nil] [[{:label "a" :n 2}] [{:label "a" :n 1}]]]
           (mapv (juxt :render-args :prev-render-args)
                 (:rows (proj/views-step
                          [(view-render-ev :app/Item [] 0.1 {:render-key  [:Item 0]
                                                             :render-args [{:label "a" :n 1}]})
                           (view-render-ev :app/Item [] 0.1 {:render-key  [:Item 0]
                                                             :render-args [{:label "a" :n 2}]})]))))))
  (testing "a different instance of the same view never inherits another's args"
    (is (= [nil nil]
           (mapv :prev-render-args
                 (:rows (proj/views-step
                          [(view-render-ev :app/Item [] 0.1 {:render-key [:Item 0] :render-args [{:n 0}]})
                           (view-render-ev :app/Item [] 0.1 {:render-key [:Item 1] :render-args [{:n 1}]})])))))))

;; ---- top-level project --------------------------------------------------

(deftest project-full-pipeline-test
  (testing "a full epoch projects every cascade step, in cascade order"
    (is (= [:dispatch :coeffect :handler :flow :side-effects :subscriptions :views]
           (mapv :step (proj/project
                         (record [(dispatched-ev [:cart/checkout] :ui nil)
                                  (cofx-run-ev :session {:user 1})
                                  (do-fx-ev {:db {} :http/post {:url "/x"}})
                                  (db-changed-ev [[[:cart :state] :idle :placing :modified]])
                                  (flow-recomputed-ev :cart-total [:cart :total] 10 20)
                                  (fx-handled-ev :db nil 0.1)
                                  (fx-handled-ev :http/post {} 12.0)
                                  (sub-run-ev [:total] true 10 20)
                                  (view-render-ev ::cart-view [:total])])))))))

(deftest project-numbered-test
  (testing "number-steps numbers 1..N over only the steps that fired"
    (is (= [[:dispatch 1] [:handler 2] [:side-effects 3] [:subscriptions 4]]
           (mapv (juxt :step :step-number)
                 (proj/project-numbered (record [(dispatched-ev [:counter-inc] :ui nil)
                                                 (db-changed-ev [])
                                                 (sub-run-ev [:total] true 1 2)])))))))

(deftest project-empty-test
  (testing "an empty record → an empty step vector (the view's empty state)"
    (is (= [] (proj/project (record [] nil))))))

;; ---- badge taxonomy ------------------------------------------------------

(deftest badge-set-test
  (testing "every projected step's :badge is in the public badge-set"
    (is (every? proj/badge-set
                (map :badge (proj/project (record [(dispatched-ev [:counter-inc] :ui nil)
                                                   (cofx-run-ev :session {:x 1})
                                                   (do-fx-ev {:db {}})
                                                   (db-changed-ev [])
                                                   (flow-recomputed-ev :f [:p] 1 2)
                                                   (fx-handled-ev :db nil 0.1)
                                                   (sub-run-ev [:s] true 1 2)
                                                   (view-render-ev ::v [:s])])))))
    (is (every? proj/badge-set [:RECORDABLE-COFX :INTERCEPTOR])
        "the conditional RECORDABLE COEFFECTS step and the exception-only
         INTERCEPTOR step, which this fixture does not project"))
  ;; Only the resolver opts reach the authored INTERCEPTORS step, so the
  ;; fixture above cannot see a badge missing for it.
  (testing "an AUTHORED interceptor chain's INTERCEPTORS step badge is in badge-set"
    (let [steps (proj/project
                  (record [(dispatched-ev [:cart/add] :ui nil)
                           (db-changed-ev [[[:cart] 0 1 :modified]])
                           (run-end-ev 1)]
                          :cart/add)
                  {:resolve-event-interceptors
                   (fn [event-id]
                     (when (= event-id :cart/add)
                       {:entries         [:auth/required
                                          {:id :rf/event-handler :rf/default? true}]
                        :resolve-meta-fn (fn [_id]
                                           {:rf/interceptor-descriptor
                                            {:before identity}})}))})]
      (is (= :INTERCEPTORS (some #(when (= :interceptors (:step %)) (:badge %)) steps)))
      (is (every? proj/badge-set (map :badge steps))))))

;; ---- formatting helpers --------------------------------------------------

(deftest format-duration-ms-test
  (testing "duration formatting"
    (is (= "0.1ms" (fmt/format-duration-ms 0.1)))
    (is (= "12ms"  (fmt/format-duration-ms 12)))
    (is (= "1.2s"  (fmt/format-duration-ms 1234)))
    (is (nil? (fmt/format-duration-ms nil)))))

(deftest ns-keyword-test
  (testing "id rendering"
    (is (= ":foo"       (fmt/ns-keyword :foo)))
    (is (= ":my/foo"    (fmt/ns-keyword :my/foo)))
    (is (= "non-kw"     (fmt/ns-keyword "non-kw")))))

(deftest machine-event-orientation-test
  (testing "the EVENT HANDLER orientation triple is projected off
            the cascade: the inner TRIGGER vector, the MACHINE id, and the
            PRE-transition STATE."
    (let [rows [{:kind :action :step 1 :phase :exit :machine-id :door/main
                 :action-id :clear-hold}
                {:kind :transition :step 2 :machine-id :door/main
                 :event [:door/close] :from-state :open :to-state :closed
                 :before {:state :open} :after {:state :closed}}
                {:kind :action :step 3 :phase :entry :machine-id :door/main
                 :action-id :count-open :data-write {:opened-count 1}}]]
      (is (= {:trigger [:door/close] :machine-id :door/main :state :open}
             (proj/machine-event-orientation rows)))))
  (testing "a guarded-BLOCKED / unhandled event produces a :no-op
            row (no transition); the orientation reads off it."
    (let [rows [{:kind :guard :step 1 :machine-id :door/main :guard-id :may-close? :outcome :fail}
                {:kind :no-op :step 2 :machine-id :door/main :event [:door/close] :state :open}]]
      (is (= {:trigger [:door/close] :machine-id :door/main :state :open}
             (proj/machine-event-orientation rows)))))
  (testing "the machine-id arg backstops a row that stamped none."
    (let [rows [{:kind :transition :step 1 :event [:tick] :from-state :red :to-state :green
                 :before {:state :red} :after {:state :green}}]]
      (is (= :traffic/light (:machine-id (proj/machine-event-orientation rows :traffic/light))))))
  (testing "nil for a cascade with no transition / no-op row — a pure :start
            creation kick carries only a :start row"
    (is (nil? (proj/machine-event-orientation
                [{:kind :start :step 1 :machine-id :door/main :cause :explicit}])))))

(deftest orientation-value-test
  (testing "orientation VALUES render code-formatted"
    (is (= "[:door/close 42]" (fmt/orientation-value [:door/close 42])))
    (is (= ":door/main"      (fmt/orientation-value :door/main)))
    (is (= "—"               (fmt/orientation-value nil))
        "nil renders the muted em-dash placeholder")))

(deftest elide-large-render-args-test
  (let [small-arg {:label "a" :n 1}
        ;; a fat props map well over the 512-byte budget
        big-arg   (into {} (map (fn [i] [(keyword (str "k" i))
                                         {:idx i :label (str "step-" i)
                                          :note "padding to clear the byte budget"}])
                                (range 40)))]
    (testing "an under-budget arg vector is returned untouched, and the nil of
              a no-arg render stays nil, so the view reads `(no args)`"
      (is (= [small-arg] (fmt/elide-large-render-args [small-arg])))
      (is (nil? (fmt/elide-large-render-args nil))))
    (testing "an over-budget arg collapses to the framework's single-key
              `:rf.size/large-elided` size marker, carrying its positional path"
      (let [out  (fmt/elide-large-render-args [big-arg])
            body (:rf.size/large-elided (first out))]
        (is (= [{:rf.size/large-elided body}] out))
        (is (= {:path [0] :type :map :reason :size :handle [:rf.elision/at [0]]}
               (dissoc body :bytes :hint)))))
    (testing "per element: a small arg beside a fat one stays inline"
      (let [out (fmt/elide-large-render-args [small-arg big-arg])]
        (is (= small-arg (first out)))
        (is (= [1] (get-in out [1 :rf.size/large-elided :path])))))))

;; The budget is UTF-8 BYTES on both hosts: a CLJS `(count s)` would count
;; UTF-16 code units, which agree with bytes only on ASCII. These fixtures make
;; code units, code points and bytes three different numbers, written as
;; `\uXXXX` escapes so no encoding hop can perturb them.

(deftest render-args-budget-counts-utf8-bytes-not-code-units-test
  (let [;; U+2014 EM DASH: 1 UTF-16 code unit, 3 UTF-8 bytes
        dashes       (apply str (repeat 200 "\u2014"))
        ;; U+1D11E G CLEF: 2 code units (a surrogate pair), 4 UTF-8 bytes
        score        (apply str (repeat 130 "\uD834\uDD1E"))
        elided-bytes (fn [v]
                       (:bytes (:rf.size/large-elided
                                 (first (fmt/elide-large-render-args [v])))))]
    (testing "an arg under the budget in code units but over it in bytes is
              elided, and the published figure is the byte count"
      (is (= 602 (elided-bytes dashes))
          "200 * 3 bytes + 2 quote chars; a code-unit ruler says 202 and does not elide")
      (is (= 522 (elided-bytes score))
          "130 * 4 bytes + 2 quote chars; a code-unit ruler says 262 and does not elide"))))

;; ---- schema violations --------------------------------------------------
;;
;; A schema violation attaches inline to the step it belongs to; there is
;; no trailing aggregate SCHEMA-VIOLATIONS step.

(deftest schema-violation-rows-basic-test
  (testing "a `:rf.error/schema-validation-failure` projects one row"
    (is (= [{:kind :rf.error/schema-validation-failure :where :app-db :path [:count]
             :failing-id :counter/inc :value "not-an-int" :explain nil
             :explain-humanized nil :rollback? true :recovery nil :sensitive? false}]
           (proj/schema-violation-rows
             [(schema-violation-ev :app-db :counter/inc [:count] "not-an-int" true)])))))

(deftest schema-violation-rows-hot-reload-test
  (testing "a `:rf.schema/violation` (hot-reload drift) projects a row too,
            with `:where :hot-reload`"
    (is (= [{:kind :rf.schema/violation :where :hot-reload :path [:count]
             :failing-id :rf/default :frame :rf/default :value "not-an-int"
             :explain nil :explain-humanized nil :rollback? false
             :recovery :logged-and-skipped :sensitive? false
             :pre-reload-schema nil :post-reload-schema nil}]
           (proj/schema-violation-rows
             [(schema-hot-reload-ev :rf/default [:count] "not-an-int")])))))

(deftest decode-malli-explain-summarises-the-first-error-test
  (testing "the first error's :schema + :value, and how many more errors rode
            in, for the `(+N more)` chip"
    (is (= {:expected :int :got "x" :more-errors 2}
           (proj/decode-malli-explain
             {:schema [:map [:a :int] [:b :int]]
              :value  {:a "x" :b "y"}
              :errors [{:path [:a] :schema :int :value "x"}
                       {:path [:b] :schema :int :value "y"}
                       {:path [:c] :schema :int :value :extra}]}))))
  (testing "a first error without :value reads :got off the explain's root"
    (is (= {:expected :int :got 42 :more-errors 0}
           (proj/decode-malli-explain
             {:schema :int :value 42 :errors [{:path [] :schema :int}]})))))

(deftest decode-malli-explain-non-malli-returns-nil-test
  (testing "an explain without a non-empty sequential `:errors` (a non-Malli
            validator) decodes to nil, so the view drops the row cleanly"
    (is (nil? (proj/decode-malli-explain {:errors []})))
    (is (nil? (proj/decode-malli-explain {:errors :not-a-vec})))))

(deftest schema-violation-row-stamps-decoded-test
  (testing "`schema-violation-rows` stamps the projected
            `:decoded {:expected :got :more-errors}` summary onto a row
            whose `:explain` is a canonical Malli map, and omits the
            slot when the explain is non-Malli / absent (so the view's
            decomposition block drops cleanly)."
    (let [malli-row (first
                      (proj/schema-violation-rows
                        [(schema-violation-ev :app-db :counter/inc [:count]
                                              "not-an-int" true
                                              {:schema :int
                                               :value "not-an-int"
                                               :errors [{:path [:count]
                                                         :schema :int
                                                         :value "not-an-int"}]})]))
          plain-row (first
                      (proj/schema-violation-rows
                        [(schema-violation-ev :app-db :counter/inc [:count]
                                              "not-an-int" true)]))]
      (is (= {:expected :int :got "not-an-int" :more-errors 0}
             (:decoded malli-row))
          "canonical Malli explain → :decoded summary stamped on the row")
      (is (not (contains? plain-row :decoded))
          "no explain → :decoded slot omitted (view drops the block)"))))

(deftest attach-violations-test
  (testing "each `:where` attaches to its owning step or row, leaving the
            rest untouched"
    (are [steps violation expected] (= expected (proj/attach-violations steps [violation]))
      [{:step :dispatch} {:step :handler}]
      {:where :event :failing-id :counter/inc}
      [{:step :dispatch :violations [{:where :event :failing-id :counter/inc}]}
       {:step :handler}]

      [{:step :coeffect :id :session} {:step :coeffect :id :session/now}]
      {:where :cofx :failing-id :session/now}
      [{:step :coeffect :id :session}
       {:step :coeffect :id :session/now :violations [{:where :cofx :failing-id :session/now}]}]

      [{:step :side-effects :rows [{:fx-id :http/post} {:fx-id :db}]}]
      {:where :fx-args :failing-id :http/post}
      [{:step :side-effects
        :rows [{:fx-id :http/post :violations [{:where :fx-args :failing-id :http/post}]}
               {:fx-id :db}]}]

      [{:step :subscriptions :rows [{:sub-id :user/profile} {:sub-id :cart/total}]}]
      {:where :sub-return :failing-id :cart/total}
      [{:step :subscriptions
        :rows [{:sub-id :user/profile}
               {:sub-id :cart/total :violations [{:where :sub-return :failing-id :cart/total}]}]}])))

(deftest cascade-rolled-back?-test
  (testing "a state-partition violation rolls the cascade back only when it
            carries `:rollback? true`, and :machine-data counts like :app-db"
    (is (false? (proj/cascade-rolled-back? [{:where :app-db :rollback? false}])))
    (is (true? (proj/cascade-rolled-back? [{:where :machine-data :rollback? true}])))))

(deftest mark-rolled-back-downstream-test
  (testing "a rollback mutes every step AFTER SIDE EFFECTS; SIDE EFFECTS keeps
            its ✗ :db row as the visible signal, and the steps upstream of the
            commit ran for real"
    (is (= [nil nil nil true true]
           (mapv :rolled-back?
                 (proj/mark-rolled-back-downstream
                   [{:step :dispatch} {:step :handler} {:step :side-effects}
                    {:step :subscriptions} {:step :views}]
                   [{:where :app-db :rollback? true}])))))
  (testing "a rollback outside the state partitions mutes nothing"
    (let [steps [{:step :dispatch} {:step :handler} {:step :side-effects} {:step :views}]]
      (is (= steps (proj/mark-rolled-back-downstream
                     steps [{:where :sub-return :rollback? true}]))))))

;; ---- parent-epoch correlation ------------------------------------------

(deftest parent-epoch-index-test
  (let [history [{:epoch-id 41 :dispatch-id 9000 :trigger-event [:root]}
                 {:epoch-id 42 :dispatch-id 9001 :trigger-event [:parent]}
                 {:epoch-id 43 :dispatch-id 9002 :trigger-event [:child]
                  :parent-dispatch-id 9001}]]
    (testing "maps each requested dispatch-id to its record's epoch-id"
      (is (= {9000 41 9001 42 9002 43}
             (proj/parent-epoch-index history [9000 9001 9002]))))
    ;; The index is a SUB value: carrying only the requested ids keeps it `=`
    ;; across a settle that appends an unrelated epoch, so the panel does not
    ;; re-render on every host event (spec/006 §Invalidation algorithm).
    (testing "it carries only the requested ids, so it is `=` across a settle
              that appends an unrelated epoch"
      (is (= {9001 42}
             (proj/parent-epoch-index history [9001])
             (proj/parent-epoch-index (conj history {:epoch-id 44 :dispatch-id 9003
                                                     :trigger-event [:unrelated]})
                                      [9001]))))
    (testing "an empty request answers {} — the common parentless cascade"
      (is (= {} (proj/parent-epoch-index history []))))))

(deftest parent-dispatch-ids-test
  (testing "the DISPATCH steps' distinct parent ids in cascade order — the
            value-keyed query arg of the parent-epoch-index sub, so it must be
            an `=`-stable vector"
    (is (= [] (proj/parent-dispatch-ids [{:step :dispatch :source :ui}])))
    (is (= [9001 9002]
           (proj/parent-dispatch-ids
             [{:step :dispatch :source-enrichment {:parent-dispatch-id 9001}}
              {:step :dispatch :source-enrichment {:parent-dispatch-id 9002}}
              {:step :dispatch :source-enrichment {:parent-dispatch-id 9001}}])))))

(deftest project-attaches-app-db-violation-to-fx-db-row-test
  (testing "`project` attaches an :app-db violation to the SIDE EFFECTS :db
            row — the failed commit — not to HANDLER, which describes what the
            handler returned; the rolled-back ledger is the :db ✗ row alone"
    (let [steps (proj/project (record [(dispatched-ev [:counter/inc] :ui nil)
                                       (db-changed-ev [[[:count] 0 "boom" :modified]])
                                       (schema-violation-ev :app-db :counter/inc
                                                            [:count] "boom" true)]))]
      (is (nil? (:violations (some #(when (= :handler (:step %)) %) steps))))
      (is (= [[:db :error [true]]]
             (->> steps
                  (some #(when (= :side-effects (:step %)) %))
                  :rows
                  (mapv (juxt :fx-id :status #(mapv :rollback? (:violations %)))))))))
  (testing "hot-reload drift does not surface as a cascade tail step"
    (is (not-any? #(= :schema-hot-reload (:step %))
                  (proj/project (record [(dispatched-ev [:counter/inc] :ui nil)
                                         (schema-hot-reload-ev :rf/default
                                                               [:counter :n] "boom")]))))))

;; ---- inline exception attachment + per-step status ----------------------
;;
;; A recovered handler exception settles the framework record `:outcome :ok`
;; by spec, so the panel's outcome is derived from the trace stream instead.

(deftest exception-row-reads-message-and-coord-test
  (testing "`exception-row` lifts the message off `:exception-message` and the
            coord off the hoisted `:rf.trace/trigger-handler :source-coord`"
    (is (= {:operation :rf.error/handler-exception :message "boom"
            :coord {:file "standard_epochs/core.cljs" :line 322}
            :failing-id :standard-epochs/throw-handler :phase nil
            :recovery :no-recovery :exception nil}
           (dissoc (proj/exception-row
                     (handler-exception-ev :standard-epochs/throw-handler "boom"
                                           {:file "standard_epochs/core.cljs" :line 322}))
                   :raw))))
  (testing "with no `:exception-message` the `:reason` boilerplate is NOT the
            card's message, and with no coord on the trace there is none"
    (is (= [nil nil]
           ((juxt :message :coord) (proj/exception-row (handler-exception-ev :foo/bar nil))))))
  (testing "the raw `:exception` rides the row for the card's details"
    (let [boom (ex-info "boom" {:surface :handler-exception})]
      (is (identical? boom (:exception (proj/exception-row
                                         (handler-exception-ev :e "boom" nil nil boom))))))))

(deftest handler-wrote-db?-test
  (testing "the handler wrote a :db when t1, a db-changed commit, or a db-noop
            (an unchanged :db was still RETURNED) fired"
    (is (true? (proj/handler-wrote-db? [(db-pending-ev {:count 1})])))
    (is (true? (proj/handler-wrote-db? [(db-changed-ev [[[:count] 0 1 :modified]])])))
    (is (true? (proj/handler-wrote-db? [(db-noop-ev)])))))

(deftest exception-rows-harvests-cascade-exceptions-test
  (testing "`exception-rows` harvests the `cascade-exception-ops` subset, in
            trace order"
    (is (= [:rf.error/handler-exception :rf.error/fx-handler-exception]
           (mapv :operation (proj/exception-rows
                              [(dispatched-ev [:e] :ui nil)
                               (handler-exception-ev :e "boom" nil)
                               (run-end-ev 1)
                               (fx-handler-exception-ev :http/post "fx boom" nil)]))))))

(deftest attach-exceptions-fx-row-test
  (testing "an fx exception lands on its matching SIDE EFFECTS row and stamps
            the step :error"
    (let [[fx] (proj/attach-exceptions
                 [{:step :side-effects :rows [{:fx-id :db :status :ok}
                                              {:fx-id :http/post :status :error}]}]
                 [(proj/exception-row (fx-handler-exception-ev :http/post "fx boom" nil))])]
      (is (= :error (:status fx)))
      (is (= [0 1] (mapv (comp count :errors) (:rows fx))))))
  (testing "with no matching row it falls back to the step-level `:errors`"
    (let [[fx] (proj/attach-exceptions
                 [{:step :side-effects :rows [{:fx-id :db}]}]
                 [(proj/exception-row (fx-handler-exception-ev :unknown/fx "boom" nil))])]
      (is (= 1 (count (:errors fx)))))))

(deftest step-status-test
  (testing "`:ok` for a clean step, `:skipped` when the step never ran,
            `:error` when the step (or a row) carries an exception or
            violation — and error wins over skipped"
    (are [step expected] (= expected (proj/step-status step))
      {:step :handler}                                          :ok
      {:step :handler :status :skipped}                         :skipped
      {:step :handler :status :error}                           :error
      {:step :handler :errors [{:message "x"}]}                 :error
      {:step :handler :violations [{:where :app-db}]}           :error
      ;; row-level :errors / :violations lift the step to :error
      {:step :side-effects :rows [{:fx-id :db :errors [{}]}]}     :error
      {:step :side-effects :rows [{:fx-id :db :violations [{}]}]} :error
      {:step :handler :status :skipped :errors [{:message "x"}]} :error)))

(deftest epoch-outcome-test
  (testing "`:error` when ANY step errored, else `:ok` — a SKIPPED step is
            neutral"
    (are [steps outcome] (= outcome (proj/epoch-outcome steps))
      [{:step :dispatch} {:step :handler}]                 :ok
      [{:step :dispatch} {:step :handler :status :error}]  :error
      [{:step :dispatch} {:step :handler :status :skipped}
       {:step :side-effects :status :skipped}]             :ok)))

(deftest project-attaches-handler-exception-end-to-end-test
  (testing "a handler-exception trace lands inline on HANDLER — message,
            coord, and no spurious 'Rolled back' chip — and HANDLER shows no
            phantom :db (it threw before returning one)"
    (let [handler (->> (proj/project
                         (record [(dispatched-ev [:standard-epochs/throw-handler] :ui
                                                 {:file "core.cljs" :line 481})
                                  (handler-exception-ev
                                    :standard-epochs/throw-handler "boom"
                                    {:file "standard_epochs/core.cljs" :line 322})]
                                 :standard-epochs/throw-handler))
                       (some #(when (= :handler (:step %)) %)))]
      (is (= :error (:status handler)))
      (is (= [{:message "boom" :coord {:file "standard_epochs/core.cljs" :line 322}
               :db-rolled-back? false}]
             (mapv #(select-keys % [:message :coord :db-rolled-back?]) (:errors handler))))
      (is (false? (:db-write? handler))))))

;; ---- 'Rolled back' chip gates on ACTUAL rollback ------------------------
;;
;; fx are post-commit and best-effort: a throwing fx leaves the `:db`
;; committed, so the chip keys on a real `:where :app-db` schema-fail
;; rollback, never on a mere commit.

(deftest fx-exception-stamps-db-rolled-back-false-test
  (testing "a POST-COMMIT fx throw left the :db committed and reverted nothing,
            so its card carries `:db-rolled-back? false`"
    (let [se (->> (proj/project
                    (record [(dispatched-ev [:standard-epochs/throw-fx] :ui nil)
                             (db-pending-ev {:baseline 1})
                             (db-changed-ev [[[:baseline] 0 1 :modified]])
                             (run-end-ev 1)
                             (do-fx-ev {:fx [[:standard-epochs/boom {}]]})
                             (fx-handler-exception-ev :standard-epochs/boom
                                                      "standard-epochs / boom fx threw")]
                            :standard-epochs/throw-fx))
                  (some #(when (= :side-effects (:step %)) %)))]
      (is (= [false]
             (mapv :db-rolled-back? (concat (:errors se) (mapcat :errors (:rows se)))))))))

(deftest schema-rollback-stamps-db-rolled-back-true-test
  (testing "a `:where :app-db` schema-fail rollback DID revert the commit, so
            an exception card this cascade carries `:db-rolled-back? true`"
    (is (= [true]
           (mapv :db-rolled-back?
                 (mapcat :errors
                         (proj/project
                           (record [(dispatched-ev [:standard-epochs/set-bad-auth] :ui nil)
                                    (db-changed-ev [[[:auth :token] "ok" 42 :modified]])
                                    (schema-violation-ev :app-db :standard-epochs/set-bad-auth
                                                         [:auth :token] 42 true)
                                    (handler-exception-ev :standard-epochs/set-bad-auth
                                                          "post-rollback handler note")]
                                   :standard-epochs/set-bad-auth))))))))

;; ---- per-step exception placement + INTERCEPTOR step --------------------
;;
;; A cascade exception lands under the step where it occurred (coeffect →
;; COEFFECT, interceptor → INTERCEPTOR, handler → HANDLER), and an
;; upstream-skipped HANDLER / SIDE EFFECTS step reads SKIPPED rather than
;; 'ran, returned no :db'.

(deftest attach-coeffect-exception-to-matching-step-test
  (testing "a coeffect exception lands on the COEFFECT step whose :id matches
            — not on HANDLER — and stamps it :error"
    (is (= [[0 nil] [1 :error] [0 nil]]
           (mapv (juxt (comp count :errors) :status)
                 (proj/attach-exceptions
                   [{:step :coeffect :id :other/cofx}
                    {:step :coeffect :id :app/session}
                    {:step :handler}]
                   [(proj/exception-row (coeffect-exception-ev :app/session "cofx boom"))])))))
  (testing "with no :id match it falls back to the FIRST COEFFECT step"
    (is (= [1 0]
           (mapv (comp count :errors)
                 (proj/attach-exceptions
                   [{:step :coeffect :id :app/session} {:step :handler}]
                   [(proj/exception-row (coeffect-exception-ev :app/missing "cofx boom"))]))))))

(deftest project-synthesises-coeffect-placeholder-on-throwing-cofx-test
  (testing "a cofx that threw before any `:rf.cofx/run` gets a placeholder
            COEFFECT step (no value) carrying the exception, and HANDLER reads
            SKIPPED — it never ran"
    (let [steps (proj/project (record [(dispatched-ev [:standard-epochs/throw-cofx] :ui nil)
                                       (coeffect-exception-ev :standard-epochs/throwing-cofx
                                                              "cofx boom")]
                                      :standard-epochs/throw-cofx))
          step  (fn [k] (some #(when (= k (:step %)) %) steps))]
      (is (= [:standard-epochs/throwing-cofx true 1]
             ((juxt :id :no-value? (comp count :errors)) (step :coeffect))))
      (is (= :skipped (proj/step-status (step :handler)))))))

;; -- INTERCEPTOR step (button-17 :before / button-18 :after) -------------

(deftest interceptor-step-projection-test
  (testing "no interceptor threw → no INTERCEPTOR step"
    (is (nil? (proj/interceptor-step [(dispatched-ev [:x] :ui nil) (run-end-ev 1)] :before))))
  (testing "the step is PHASE-FILTERED: a :before throw builds only the
            :before step"
    (let [events [(interceptor-exception-ev :app/auth :before "intc boom")]]
      (is (= {:step :interceptor :badge :INTERCEPTOR :phase :before
              :rows [{:interceptor-id :app/auth :phase :before :coord nil}]}
             (proj/interceptor-step events :before)))
      (is (nil? (proj/interceptor-step events :after)))))
  (testing "a captured :source-coord lifts onto the row's :coord, the slot the
            view's jump-to-source chip reads"
    (let [coord {:ns 'app.icpt :file "/abs/app/icpt.cljs" :line 42}]
      (is (= coord (-> (proj/interceptor-step
                         [(interceptor-exception-ev :app/auth :before "boom" nil coord)]
                         :before)
                       :rows first :coord))))))

(deftest project-interceptor-after-end-to-end-test
  (testing "a :before and an :after interceptor throw → one INTERCEPTOR step on
            each side of HANDLER (the :after throw fired on the way out, after
            the handler ran), each carrying its own phase's exception"
    (let [steps (proj/project
                  (record [(dispatched-ev [:multi/intc] :ui nil)
                           (interceptor-exception-ev :app/before :before "before boom")
                           (db-changed-ev [[[:n] 0 1 :modified]])
                           (run-end-ev 1)
                           (interceptor-exception-ev :app/after :after "after boom")]
                          :multi/intc))]
      (is (= [[:dispatch nil] [:interceptor :before] [:handler nil]
              [:interceptor :after] [:side-effects nil]]
             (mapv (juxt :step :phase) steps)))
      (is (= [[:app/before] [:app/after]]
             (->> steps
                  (filter #(= :interceptor (:step %)))
                  (mapv #(mapv :failing-id (:errors %)))))))))

;; -- INTERCEPTORS step (authored / resolved chain) ------------------------

(deftest interceptor-ref-row-test
  (testing "a bare-keyword authored ref resolves its descriptor, doc and coord"
    (is (= {:interceptor-id :auth/required :authored :auth/required :arg nil
            :coord {:file "auth.cljs" :line 12} :doc "auth gate"
            :before? true :after? false :factory? false}
           (proj/interceptor-ref-row
             :auth/required
             (fn [id]
               (when (= id :auth/required)
                 {:doc "auth gate" :file "auth.cljs" :line 12
                  :rf/interceptor-descriptor {:before identity}}))))))
  (testing "an [id arg] factory ref keeps the vector and its arg"
    (is (= {:interceptor-id :rf.interceptor/path :authored [:rf.interceptor/path [:cart]]
            :arg [:cart] :doc nil :before? false :after? false :factory? true}
           (proj/interceptor-ref-row
             [:rf.interceptor/path [:cart]]
             (fn [id]
               (when (= id :rf.interceptor/path)
                 {:rf/interceptor-descriptor {:factory identity}}))))))
  (testing "an UNREGISTERED ref is flagged :missing-ref?, not dropped"
    (is (= {:interceptor-id :nope/unregistered :authored :nope/unregistered :arg nil
            :missing-ref? true}
           (proj/interceptor-ref-row :nope/unregistered (constantly nil)))))
  (testing "a stale inline interceptor value surfaces under :inline?"
    (is (= {:interceptor-id :legacy/inline :authored nil :inline? true :before? true}
           (proj/interceptor-ref-row {:id :legacy/inline :before identity}
                                     (constantly nil))))))

(deftest authored-interceptors-step-test
  (testing "nil when only the framework wrapper is present — the common case"
    (is (nil? (proj/authored-interceptors-step
                :evt [{:id :rf/event-handler :rf/default? true}] (constantly nil)))))
  (testing "an INTERCEPTORS step over the authored refs in order, wrapper
            filtered, carrying no :status — it is informational, never an error"
    (let [step (proj/authored-interceptors-step
                 :cart/add
                 [:auth/required
                  [:rf.interceptor/path [:cart]]
                  {:id :rf/event-handler :rf/default? true}]
                 (fn [_id] {:rf/interceptor-descriptor {:before identity}}))]
      (is (= {:step :interceptors :badge :INTERCEPTORS :event-id :cart/add}
             (dissoc step :rows)))
      (is (= [:auth/required :rf.interceptor/path] (mapv :interceptor-id (:rows step)))))))

(deftest authored-interceptors-step-override-summary-test
  (let [resolve-fn (fn [_id] {:rf/interceptor-descriptor {:before identity}})
        overrides  (fn [step] (into {} (map (juxt :interceptor-id :override)) (:rows step)))]
    (testing "the per-dispatch override-summary marks replaced / removed rows
              and leaves the rest unstamped"
      (is (= {:auth/required :removed :auth/audit :replaced :auth/untouched nil}
             (overrides (proj/authored-interceptors-step
                          :cart/add [:auth/required :auth/audit :auth/untouched] resolve-fn
                          {:matched  [:auth/required :auth/audit]
                           :replaced [:auth/audit]
                           :removed  [:auth/required]
                           :count    2})))))
    (testing "an [id arg]-authored row matches the summary's bare head id"
      (is (= {:rf.interceptor/path :removed}
             (overrides (proj/authored-interceptors-step
                          :cart/add [[:rf.interceptor/path [:cart]]] resolve-fn
                          {:matched  [:rf.interceptor/path]
                           :replaced []
                           :removed  [:rf.interceptor/path]
                           :count    1})))))))

(deftest project-authored-interceptors-end-to-end-test
  (let [opts {:resolve-event-interceptors
              (fn [event-id]
                (when (= event-id :cart/add)
                  {:entries [:auth/required :auth/audit
                             {:id :rf/event-handler :rf/default? true}]
                   :resolve-meta-fn
                   (fn [_id] {:rf/interceptor-descriptor {:before identity}})}))}]
    (testing "the resolver opts inject the INTERCEPTORS step BEFORE HANDLER"
      (is (= [:dispatch :interceptors :handler :side-effects]
             (mapv :step (proj/project (record [(dispatched-ev [:cart/add] :ui nil)
                                                (db-changed-ev [[[:cart] 0 1 :modified]])
                                                (run-end-ev 1)]
                                               :cart/add)
                                       opts)))))
    (testing "`project` reads :rf.interceptor/override-summary off the
              run-start trace and stamps the affected rows"
      (is (= {:auth/required :removed :auth/audit nil}
             (->> (proj/project
                    (record [(dispatched-ev [:cart/add] :ui nil)
                             (ev :rf.event :rf.event/run-start
                                 {:rf.event/v [:cart/add]
                                  :frame      :rf/default
                                  :rf.interceptor/override-summary
                                  {:matched  [:auth/required]
                                   :replaced []
                                   :removed  [:auth/required]
                                   :count    1}})
                             (db-changed-ev [[[:cart] 0 1 :modified]])
                             (run-end-ev 1)]
                            :cart/add)
                    opts)
                  (some #(when (= :interceptors (:step %)) %))
                  :rows
                  (into {} (map (juxt :interceptor-id :override)))))))))

;; -- SKIPPED-step marking -------------------------------------------------

(deftest mark-skipped-handler-test
  (testing "an upstream :before-chain throw — a coeffect or a :before
            interceptor — skips HANDLER + SIDE EFFECTS, and only those"
    (is (= [:skipped :skipped nil]
           (mapv :status (proj/mark-skipped-handler
                           [{:step :handler} {:step :side-effects} {:step :subscriptions}]
                           [(coeffect-exception-ev :app/session "boom")]))))
    (is (= [:skipped]
           (mapv :status (proj/mark-skipped-handler
                           [{:step :handler}]
                           [(interceptor-exception-ev :app/auth :before "boom")])))))
  (testing "the handler RAN, so is not skipped, after an :after interceptor
            throw or an :app-db write refused at the commit"
    (are [trace] (nil? (:status (first (proj/mark-skipped-handler [{:step :handler}] [trace]))))
      (interceptor-exception-ev :app/auth :after "boom")
      (ev :error :rf.error/schema-validation-failure {:where :app-db :rollback? true}))))

;; -- HALTED-DEPTH record ---------------------------------------------------

(defn- halted-depth-record
  "A `:halted-depth` record in the shape the producer commits, read off a
  real `dispatch-sync` runaway (`:drain-depth 5`): the record's `:outcome`,
  the flat `:halt-reason` descriptor, the halting event pinned as
  `:event-id` / `:trigger-event`, equal db snapshots, and a trace carrying
  ONLY the halting event's `:rf.event/dispatched` marker — it never ran."
  []
  {:epoch-id      6
   :outcome       :halted-depth
   :halt-reason   {:operation     :rf.error/drain-depth-exceeded
                   :depth         5
                   :queue-size    1
                   :last-event-id :user/loop}
   :event-id      :user/loop
   :trigger-event [:user/loop]
   :db-before     {:n 5}
   :db-after      {:n 5}
   :trace-events  [(dispatched-ev [:user/loop] :ui nil)]})

(deftest halted-depth-record-projects-the-halt-test
  (testing "a drain-depth halt reads as refused: one card on DISPATCH, worded
            from the descriptor with its ids kept off the card, and HANDLER
            SKIPPED with the halt as its reason"
    (let [by (into {} (map (juxt :step identity)) (proj/project (halted-depth-record)))]
      (is (= [{:operation :rf.error/drain-depth-exceeded
               :message   "Drain depth limit (5) exceeded — this event never ran; 1 queued event(s) dropped."}]
             (:errors (:dispatch by))))
      (is (= [:skipped :halted-depth] ((juxt :status :skip-reason) (:handler by))))))
  (testing "mark-halted stamps HANDLER + SIDE EFFECTS only, and keys on the
            record's :outcome"
    (let [steps [{:step :dispatch} {:step :handler}
                 {:step :side-effects} {:step :subscriptions}]]
      (is (= [:error :skipped :skipped :ok]
             (mapv proj/step-status (proj/mark-halted steps (halted-depth-record)))))
      (is (= steps (proj/mark-halted steps {:outcome :ok}))))))

;; ============================================================================
;; HISTORY restore / record projection (spec/009 §History trace)
;; ============================================================================

(deftest machine-cascade-rows-stamps-history-on-transition-test
  (testing "the transition row carries its machine's history restore and
            record records — the spec/009 tag bags — keyed by machine-id"
    (is (= {:history-restored [{:machine-id      :media/deep
                                :compound-path   [:player]
                                :kind            :deep
                                :source          :recorded
                                :fallback        nil
                                :restored-config [:player :playing :mid-track]
                                :resolved-leaf   [:player :playing :mid-track]}]
            :history-recorded [{:machine-id      :media/deep
                                :compound-path   [:player]
                                :kind            :deep
                                :recorded-config [:player :paused]
                                :prev-config     [:player :playing :mid-track]}]}
           (-> (proj/machine-cascade-rows
                 [(machine-transition-ev :media/deep {:state [:player :stopped] :data {}}
                                         {:state [:player :playing :mid-track] :data {}}
                                         [:insert] 0)
                  (machine-history-restored-ev
                    {:machine-id :media/deep :compound-path [:player] :kind :deep
                     :source :recorded :restored-config [:player :playing :mid-track]
                     :resolved-leaf [:player :playing :mid-track]})
                  (machine-history-recorded-ev
                    {:machine-id :media/deep :compound-path [:player] :kind :deep
                     :recorded-config [:player :paused]
                     :prev-config [:player :playing :mid-track]})])
               first
               (select-keys [:history-restored :history-recorded]))))))

(deftest history-restored-headline-test
  (testing "the restored headline reads the recorded config → resolved leaf
            and NAMES the kind; on :default it names the fallback"
    (is (= "restored [:player] from DEEP history · [:player :playing :mid-track] → [:player :playing :mid-track]"
           (fmt/history-restored-headline
             {:compound-path [:player] :kind :deep :source :recorded
              :restored-config [:player :playing :mid-track]
              :resolved-leaf [:player :playing :mid-track]})))
    (is (= "restored [:player] from DEFAULT (no recording) via :default-target → [:player :playing :at-start]"
           (fmt/history-restored-headline
             {:compound-path [:player] :kind :deep :source :default
              :fallback :default-target
              :resolved-leaf [:player :playing :at-start]})))))

(deftest history-recorded-headline-test
  (testing "the recorded headline reads 'advanced from X to Y'
            on an overwrite, 'recorded = Y' on the first-ever write"
    (is (= "history recorded [:player] = [:player :playing :mid-track]"
           (fmt/history-recorded-headline
             {:compound-path [:player] :kind :deep
              :recorded-config [:player :playing :mid-track]})))
    (is (= "history advanced [:player] from [:player :playing :at-start] to [:player :playing :mid-track]"
           (fmt/history-recorded-headline
             {:compound-path [:player] :kind :deep
              :recorded-config [:player :playing :mid-track]
              :prev-config [:player :playing :at-start]})))))

;; ============================================================================
;; GENERIC error catch-all
;; ============================================================================
;;
;; Every cascade `:rf.error/*` trace outside `cascade-exception-ops` goes
;; through the generic catch-all, so it never vanishes with the epoch reading
;; `:ok`. The fixture is the PRODUCER's emit shape
;; (`router/emit-effect-map-shape!`'s tag map).

(defn- effect-map-shape-ev
  "`:rf.error/effect-map-shape` trace — the router's FINAL-effects boundary
  REFUSING a malformed effect-map envelope. `:offending-key` is the
  structural discriminator, `:reason` the prose naming it, and there is NO
  `:exception-message`, because nothing threw."
  [event-id offending-key]
  (assoc (ev :error :rf.error/effect-map-shape
             {:failing-id        event-id
              :rf.trace/event-id event-id
              :rf.event/v        [event-id]
              :offending-key     offending-key
              :value             1
              :reason            (str "Effect map carries foreign top-level key "
                                      offending-key ".")})
         :recovery :fix-effect))

(deftest unclassified-error-surfaces-on-side-effects-test
  (testing "an effect-map refusal lands on a SYNTHESISED, empty SIDE EFFECTS
            step — not on HANDLER, which may not have authored the key — and
            its card names the offending key"
    (let [se (->> (proj/project (record [(dispatched-ev [:cart/add] :ui nil)
                                         (effect-map-shape-ev :cart/add :bogus-fx)]
                                        :cart/add))
                  (some #(when (= :side-effects (:step %)) %)))]
      (is (= [true [] :error [:rf.error/effect-map-shape]]
             ((juxt :synthesised? :rows :status #(mapv :operation (:errors %))) se)))
      (is (re-find #":bogus-fx" (:message (first (:errors se)))))))
  (testing "an existing SIDE EFFECTS step carries the refusal, with no
            synthesised sibling"
    (is (= [[nil 1]]
           (->> (proj/project (record [(dispatched-ev [:cart/add] :ui nil)
                                       (db-changed-ev [[[:cart] 0 1 :modified]])
                                       (effect-map-shape-ev :cart/add :bogus-fx)]
                                      :cart/add))
                (filter #(= :side-effects (:step %)))
                (mapv (juxt :synthesised? (comp count :errors)))))))
  (testing "an op with NO placement entry lands on HANDLER rather than
            vanishing — the framework's error vocabulary grows"
    (is (= [:rf.error/some-future-op]
           (->> (proj/project (record [(dispatched-ev [:cart/add] :ui nil)
                                       (db-changed-ev [[[:cart] 0 1 :modified]])
                                       (run-end-ev 1)
                                       (ev :error :rf.error/some-future-op
                                           {:failing-id        :cart/add
                                            :exception-message "invented for this test"})]
                                      :cart/add))
                (some #(when (= :handler (:step %)) %))
                :errors
                (mapv :operation))))))

(deftest cascade-error-event-discrimination-test
  (testing "`unclassified-error-rows` takes only what `attach-exceptions`
            leaves, so a throw is never double-reported as two cards"
    (is (= [:rf.error/effect-map-shape]
           (mapv :operation (proj/unclassified-error-rows
                              [(dispatched-ev [:cart/add] :ui nil)
                               (handler-exception-ev :cart/add "boom")
                               (effect-map-shape-ev :cart/add :bogus-fx)]))))))

(deftest violation-catch-all-test
  (let [project-with       (fn [violation]
                             (proj/project (record [(dispatched-ev [:cart/add] :ui nil)
                                                    (db-changed-ev [[[:cart] 0 1 :modified]])
                                                    (run-end-ev 1)
                                                    violation]
                                                   :cart/add)))
        handler-violations (fn [steps]
                             (count (:violations (some #(when (= :handler (:step %)) %) steps))))]
    (testing "a violation whose owning step is absent (:sub-return with no
              SUBSCRIPTIONS step) or whose :where is unrecognised falls back
              to HANDLER instead of vanishing"
      (is (= 1 (handler-violations
                 (project-with (schema-violation-ev :sub-return :cart/total
                                                    [:cart :total] "nope" false)))))
      (is (= 1 (handler-violations
                 (project-with (schema-violation-ev :some-future-where :cart/add
                                                    [:cart] "nope" false))))))
    (testing ":hot-reload drift is the ONE kind dropped — it is not a cascade
              event — so neither this pass nor the generic error sweep turns
              the cascade :error"
      (is (= :ok (proj/epoch-outcome
                   (project-with (schema-violation-ev :hot-reload :cart/add
                                                      [:cart] "drift" false))))))))
