(ns re-frame.trace-cascade-captured-test
  "Per rf2-931pm — pins the three new substrate-level trace ops:

    1. `:rf.sub/skip` emitted by the memo wrappers on a memo-hit.
    2. `:rf.flow/skip` carries `:rf.sub/input-paths-unchanged` (additive tag).
    3. `:rf.cascade/captured` aggregator fires end-of-epoch ONLY when
       the focus predicate matches; bounded at 50 subs / 100 views.

  Pure JVM coverage — the sub memo + flow trace emits + cascade
  aggregation all run identically on JVM and CLJS (the production
  elision gate is shared; bundle-isolation lives in its own gate).

  ## Posture split (rf2-d2841)

  Every claim in this file about `:rf.sub/skip`, `:rf.flow/skip`,
  `:rf.cascade/captured` and the `:rf.sub/run` attribution tags is read off
  the DEV TRACE, and there is no production channel that carries any of them
  — checked, not assumed. `:rf.sub/*` and `:rf.cascade/*` are not error
  categories, so `error-emit/emit-error-both!` never lifts them, and nothing
  in `re-frame.observability` promotes a sub-recompute onto the always-on
  `:errors` stream. So the trace reads are guarded.

  What keeps this OFF the class-2 (100%-dev-instrumentation) list is that the
  three `aggregate-cascade-*` deftests drive `cascade/aggregate-cascade` over
  SYNTHETIC event maps — a pure function of its argument, always-on in both
  postures — and that every trace-reading deftest here has an always-on
  counterpart for the thing the trace was REPORTING ON. A `:rf.sub/skip` emit
  reports a memo hit; the memo hit itself is production behaviour and is now
  witnessed by counting how many times the sub body actually ran. A
  `:rf.sub/run` value-change tag reports a recompute; the recomputed value is
  production state and is now witnessed by dereferencing the reaction. A
  `:rf.sub/cause-event-id` reports that a recompute happened INSIDE an
  in-flight dispatch; that is witnessed by an fx-handler that derefs during
  the drain and records what it saw. Under the gate those run for the first
  time.

  FIVE VACUOUS PASSES CAME OFF, in two classes.
  class 1 (a negative over an empty ring):
  `cascade-captured-does-not-fire-when-no-focus`'s `(is (empty? caps))`,
  which certified \"the default focus predicate suppresses the aggregator\"
  over a stream that carried no events of any kind.
  class 4 (absence of a key the gate elides wholesale) — four `(nil? …)` /
  `(not (contains? …))` negatives read off a NIL tag map, in
  `sub-run-value-changed-attribution`,
  `sub-run-first-run-flag-true-on-cache-slot-creation`,
  `sub-run-layer-1-no-cause-sub` and
  `sub-run-cause-event-id-absent-outside-dispatch`.

  AND TWO ZERO-ASSERTION DEFTESTS, the shape pass 5 named: the inner claims
  of `sub-run-cause-event-id-stamped-inside-dispatch` and
  `sub-run-cause-event-id-layer-2-cascade` sit inside a `(when (seq runs) …)`
  / `(when (and n-run d-run) …)`, so under the gate the guard was false and
  those `is` forms never ran at all. Both are now inside the posture guard
  where the precondition genuinely holds, and both deftests carry an
  always-on witness that the in-cascade recompute they are about really
  happened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; The cascade-captured aggregator hooks into `re-frame.epoch/
            ;; settle!` via the late-bind seam — without an epoch
            ;; producer on the classpath there is no settle-time emit
            ;; site and `:rf.cascade/captured` never fires (per
            ;; `core_epoch.cljc` the optional artefact's hooks are
            ;; absent-degrading). The require is here for explicitness.
            [re-frame.epoch]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.cascade :as cascade]
            [re-frame.trace.tooling :as trace-tooling]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (cascade/clear-focus-predicate!)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (try (rf/with-frame :rf/default (test-fn))
       (finally
         (cascade/clear-focus-predicate!))))

(use-fixtures :each reset-runtime)

(defn- collect-trace
  "Register a listener that captures every trace event into the
  returned atom while `body-fn` runs. Returns the captured vector."
  [body-fn]
  (let [captured (atom [])
        k        ::collect]
    (trace-tooling/register-listener!
      k
      (fn [ev] (swap! captured conj ev)))
    (try (body-fn)
         (finally
           (trace-tooling/unregister-listener! k)))
    @captured))

;; ---- :rf.sub/skip ---------------------------------------------------------

(deftest layer-1-memo-hit-emits-sub-skip
  (testing "a layer-1 sub deref against an unchanged db emits :rf.sub/skip"
    ;; ALWAYS-ON (rf2-d2841): `body-runs` counts how many times the sub's own
    ;; body executed. That is the memo hit itself — production behaviour the
    ;; `:rf.sub/skip` emit was merely REPORTING — so it is asserted outside
    ;; the posture guard.
    (let [body-runs (atom 0)
          seen      (atom [])]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
      (rf/reg-sub :n (fn [db _] (swap! body-runs inc) (:n db)))
      (rf/dispatch-sync [:seed])
      (let [events (collect-trace
                     (fn []
                       (let [r (rf/subscribe [:n])]
                         ;; Two derefs against the unchanged db. The
                         ;; first call (post-construction) is a memo hit
                         ;; because the reaction's body fires on initial
                         ;; deref but Reagent's reaction may invoke the
                         ;; wrapper additional times on identical input.
                         (swap! seen conj @r)
                         (swap! seen conj @r))))
            skips  (filter #(= :rf.sub/skip (:operation %)) events)]
        (is (= [7 7] @seen)
            "both derefs project the committed value in this posture")
        (is (= 1 @body-runs)
            "the memo the :rf.sub/skip emit reports actually held — two derefs,
             one body run, in BOTH postures")
        (when interop/debug-enabled?
          ;; At least one memo-hit emit must fire on the second deref.
          (is (seq skips)
              "expected at least one :rf.sub/skip emit on memo-hit")
          (let [skip (first skips)]
            (is (= :rf.sub (:op-type skip)))
            (is (= :n (get-in skip [:tags :rf.sub/id])))
            (is (= [:n] (get-in skip [:tags :rf.sub/query-v])))
            (is (= :input-value-equal (get-in skip [:tags :rf.sub/reason])))
            (is (= [] (get-in skip [:tags :rf.sub/input-paths-unchanged]))
                "layer-1 has no upstream subs so :rf.sub/input-paths-unchanged is empty")))))))

(deftest layer-2-memo-hit-emits-sub-skip-with-upstream
  (testing "layer-2 sub on memo-hit names its upstream input(s) in :rf.sub/input-paths-unchanged"
    ;; MERGED-PR AUDIT #7250 (rf2-d2841): the always-on half used to be
    ;; `(= [6 6] @seen)` alone, which a BROKEN memo passes — recomputing
    ;; `:doubled` on the second deref returns 6 again. Mirror the layer-1 test
    ;; above and COUNT the derived body: the memo hit the `:rf.sub/skip` emit
    ;; reports is one body execution across two derefs, and that is production
    ;; behaviour whatever the posture.
    (let [body-runs (atom 0)]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 3}}))
      (rf/reg-sub :n (fn [db _] (:n db)))
      (rf/reg-sub :doubled
        :<- [:n]
        (fn [n _] (swap! body-runs inc) (* 2 n)))
      (rf/dispatch-sync [:seed])
      (let [seen   (atom [])
            events (collect-trace
                     (fn []
                       (let [r (rf/subscribe [:doubled])]
                         (swap! seen conj @r)
                         (swap! seen conj @r))))
            skips  (filter #(and (= :rf.sub/skip (:operation %))
                                 (= :doubled (get-in % [:tags :rf.sub/id])))
                           events)]
        ;; ALWAYS-ON (rf2-d2841): the layer-2 projection the skip emit reports
        ;; on is production state — both derefs see the same committed value.
        (is (= [6 6] @seen)
            "the layer-2 sub projects 2*3 on both derefs in this posture")
        (is (= 1 @body-runs)
            "the memo the :rf.sub/skip emit reports actually held — two derefs,
             one derived body run, in BOTH postures")
        (when interop/debug-enabled?
          (is (seq skips)
              "expected at least one :rf.sub/skip emit for the layer-2 sub")
          (let [skip (first skips)]
            (is (= [[:n]] (get-in skip [:tags :rf.sub/input-paths-unchanged]))
                "input-paths-unchanged names the upstream sub vector")))))))

;; ---- :rf.flow/skip carries :rf.sub/input-paths-unchanged -------------------------

(deftest flow-skip-emits-input-paths-unchanged
  (testing ":rf.flow/skip carries :rf.sub/input-paths-unchanged naming the flow's input paths"
    (rf/reg-event :seed   (fn [{:keys [db]} _]      {:db {:x 0 :y 0}}))
    (rf/reg-event :bump-z (fn [{:keys [db]} _]     {:db (assoc db :z (inc (or (:z db) 0)))}))
    ;; ALWAYS-ON (rf2-d2841): `flow-runs` counts the flow fn's own
    ;; executions. "The inputs were stable so the flow did not recompute" is
    ;; the production fact the `:rf.flow/skip` emit reports; count it.
    (let [flow-runs (atom 0)]
      (rf/reg-flow :sum {:inputs [[:x] [:y]] :output-path [:derived :sum]}
                   (fn [x y] (swap! flow-runs inc) (+ x y)))
      (rf/dispatch-sync [:seed])
      ;; First non-seed dispatch — flow recomputes. Second — inputs stable,
      ;; flow emits :rf.flow/skip.
      (let [runs-after-seed @flow-runs
            events (collect-trace
                     (fn []
                       (rf/dispatch-sync [:bump-z])
                       (rf/dispatch-sync [:bump-z])))
            skips  (filter #(= :rf.flow/skip (:operation %)) events)]
        ;; MERGED-PR AUDIT #7250 (rf2-d2841): the two always-on assertions
        ;; below say what did NOT happen — the flow fn did not re-run, the
        ;; durable output did not move. Both stay green if the DRIVER never
        ;; ran: delete the two `:bump-z` dispatches and nothing here notices,
        ;; because a flow that was never given the chance to skip also does
        ;; not run and also leaves its output alone. Only the guarded dev
        ;; trace sees the missing skips. So prove the driver landed first,
        ;; exactly — two dispatches, `:z` = 2 — and the two negatives below
        ;; become claims about a cascade that demonstrably happened.
        (is (= 2 (:z (rf/app-db-value :rf/default)))
            "both :bump-z dispatches landed — the epochs the flow skipped in
             are real epochs")
        (is (= runs-after-seed @flow-runs)
            "neither :bump-z touched [:x] or [:y], so the flow fn did not run
             again — the skip is real in BOTH postures")
        (is (= 0 (get-in (rf/app-db-value :rf/default) [:derived :sum]))
            "the flow's durable output survives the two skipped epochs")
        (when interop/debug-enabled?
          (is (seq skips) "expected at least one :rf.flow/skip emit")
          (let [skip (first skips)]
            (is (= [[:x] [:y]] (get-in skip [:tags :input-paths-unchanged])))
            (is (= :inputs-value-equal (get-in skip [:tags :reason])))))))))

;; ---- :rf.cascade/captured -------------------------------------------------

(deftest cascade-captured-does-not-fire-when-no-focus
  (testing "default focus-predicate returns false → no :rf.cascade/captured emits"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [seen   (atom [])
          events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:n])]
                       (swap! seen conj @r)
                       (rf/dispatch-sync [:inc])
                       (swap! seen conj @r))))
          caps   (filter #(= :rf.cascade/captured (:operation %)) events)]
      ;; ALWAYS-ON (rf2-d2841): the cascade whose absence-of-capture is the
      ;; claim genuinely ran. VACUOUS PASS REMOVED (class 1) — under the gate
      ;; `events` is empty for EVERY cascade, focused or not, so
      ;; `(empty? caps)` certified the focus predicate's suppression over a
      ;; stream that never carried anything. It only discriminates against
      ;; `cascade-captured-fires-when-focused` below, which needs the ring.
      (is (= [0 1] @seen)
          "the cascade really ran — the sub recomputed 0 -> 1 in this posture")
      (when interop/debug-enabled?
        (is (empty? caps)
            "no :rf.cascade/captured when focus predicate returns false")))))

(deftest cascade-captured-fires-when-focused
  (testing "installed focus-predicate matching the cascade → :rf.cascade/captured emits"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (cascade/set-focus-predicate!
      (fn [_frame _epoch _event] true))
    (rf/dispatch-sync [:seed])
    (let [seen   (atom [])
          events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:n])]
                       (swap! seen conj @r)
                       (rf/dispatch-sync [:inc])
                       (swap! seen conj @r))))
          caps   (filter #(= :rf.cascade/captured (:operation %)) events)]
      ;; ALWAYS-ON (rf2-d2841): installing a focus predicate is an
      ;; instrumentation-only act, but it must not perturb the cascade it
      ;; observes — the same 0 -> 1 recompute as the unfocused case above.
      (is (= [0 1] @seen)
          "the focus predicate does not perturb the cascade it observes")
      (when interop/debug-enabled?
        (is (seq caps) "expected at least one :rf.cascade/captured emit under focus")
        (let [cap (first caps)]
          (is (= :rf.cascade (:op-type cap)))
          (is (contains? (:tags cap) :frame))
          (is (contains? (:tags cap) :rf.epoch/id))
          (is (vector? (get-in cap [:tags :subs-recomputed])))
          (is (vector? (get-in cap [:tags :subs-skipped])))
          (is (vector? (get-in cap [:tags :flows-computed])))
          (is (vector? (get-in cap [:tags :flows-skipped])))
          (is (vector? (get-in cap [:tags :views-rendered])))
          (is (boolean? (get-in cap [:tags :sub-cap-truncated?])))
          (is (boolean? (get-in cap [:tags :view-cap-truncated?]))))))))

;; ---- bounds ---------------------------------------------------------------

(deftest aggregate-cascade-honours-bounds
  (testing "aggregate-cascade caps subs at 50 and stamps :sub-cap-truncated?"
    (let [events (for [i (range 60)]
                   {:operation :rf.sub/run
                    :op-type   :rf.sub/run
                    :tags      {:rf.sub/id (keyword (str "s" i))
                                :rf.sub/query-v [(keyword (str "s" i))]}})
          dag    (cascade/aggregate-cascade events)]
      (is (= 50 (count (:subs-recomputed dag))))
      (is (true? (:sub-cap-truncated? dag)))
      (is (false? (:view-cap-truncated? dag)))))

  (testing "aggregate-cascade caps views at 100 and stamps :view-cap-truncated?"
    (let [events (for [i (range 120)]
                   {:operation :rf.view/render
                    :op-type   :rf.view
                    :tags      {:rf.view/render-key   [:v (str "k" i)]
                                :triggered-by :db-change}})
          dag    (cascade/aggregate-cascade events)]
      (is (= 100 (count (:views-rendered dag))))
      (is (true? (:view-cap-truncated? dag)))
      (is (false? (:sub-cap-truncated? dag))))))

(deftest aggregate-cascade-truncated-flag-only-when-exceeding-cap
  (testing "rf2-x76af2.31 — the truncation flag fires ONLY when an entry is
            genuinely elided. `conj-bounded` already caps growth, so the prior
            `>=` post-conj check over-flagged at EXACTLY the cap (nothing
            dropped). EXACTLY the cap retains all entries and is NOT truncated;
            one MORE elides one and sets the flag."
    (let [run (fn [i] {:operation :rf.sub/run
                       :tags {:rf.sub/id      (keyword (str "s" i))
                              :rf.sub/query-v [(keyword (str "s" i))]}})]
      (testing "exactly sub-cap subs recomputed → not truncated (none elided)"
        (let [dag (cascade/aggregate-cascade (map run (range cascade/sub-cap)))]
          (is (= cascade/sub-cap (count (:subs-recomputed dag))))
          (is (false? (:sub-cap-truncated? dag))
              "a cascade with EXACTLY sub-cap subs elides nothing")))
      (testing "one OVER sub-cap → truncated (the cap+1-th sub is dropped)"
        (let [dag (cascade/aggregate-cascade (map run (range (inc cascade/sub-cap))))]
          (is (= cascade/sub-cap (count (:subs-recomputed dag))))
          (is (true? (:sub-cap-truncated? dag)))))))

  (testing "the boundary holds for :rf.sub/skip too (shares :sub-cap-truncated?)"
    (let [skip (fn [i] {:operation :rf.sub/skip
                        :tags {:rf.sub/id                    (keyword (str "k" i))
                               :rf.sub/query-v               [(keyword (str "k" i))]
                               :rf.sub/reason                :input-value-equal
                               :rf.sub/input-paths-unchanged []}})]
      (is (false? (:sub-cap-truncated?
                    (cascade/aggregate-cascade (map skip (range cascade/sub-cap))))))
      (is (true? (:sub-cap-truncated?
                   (cascade/aggregate-cascade (map skip (range (inc cascade/sub-cap)))))))))

  (testing "the boundary holds for :rf.view/render (:view-cap-truncated?)"
    (let [view (fn [i] {:operation :rf.view/render
                        :tags {:rf.view/render-key [:v (str "v" i)]
                               :triggered-by       :db-change}})
          at   (cascade/aggregate-cascade (map view (range cascade/view-cap)))
          over (cascade/aggregate-cascade (map view (range (inc cascade/view-cap))))]
      (is (= cascade/view-cap (count (:views-rendered at))))
      (is (false? (:view-cap-truncated? at))
          "exactly view-cap views elides nothing")
      (is (= cascade/view-cap (count (:views-rendered over))))
      (is (true? (:view-cap-truncated? over))
          "the view-cap+1-th view is dropped → truncated"))))

;; ---- :rf.sub/run value-change + cascade attribution (rf2-l1jz8) --------------
;;
;; The reactive recompute path (subs.memo/validate-and-trace) enriches the
;; `:rf.sub/run` tag with value-change + cascade attribution so Xray's
;; Reactive panel can populate "SUBS WHOSE VALUE CHANGED" / "SUBS THAT
;; CASCADED". These tests pin the emitted tags directly off the trace
;; stream (the structured projection threading is covered by the epoch +
;; aggregate-cascade pins).

(defn- sub-runs
  "Filter a captured trace stream to `:rf.sub/run` events for `sub-id`."
  [events sub-id]
  (filter #(and (= :rf.sub/run (:operation %))
                (= sub-id (get-in % [:tags :rf.sub/id])))
          events))

(deftest sub-run-value-changed-attribution
  (testing "a layer-1 recompute whose value CHANGED stamps :rf.sub/value-changed? true + :prev/:value, :rf.sub/cascade? false, :rf.sub/cause-sub nil"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          before @r ;; force first recompute (value 1)
          after  (atom nil)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc]) ;; n 1 -> 2, layer-1 recompute
                     (reset! after @r)))
          runs   (sub-runs events :n)]
      ;; ALWAYS-ON (rf2-d2841): the value change the trace tag REPORTS is
      ;; production state — read it off the reaction. VACUOUS PASS REMOVED
      ;; (class 4): `(nil? (:rf.sub/cause-sub t))` was true for free under the
      ;; gate, where `t` is nil and every keyword lookup returns nil.
      (is (= 1 before) "the sub projected 1 before the dispatch")
      (is (= 2 @after) "the sub projects 2 after the dispatch, in this posture")
      (when interop/debug-enabled?
        (is (seq runs) "expected a :rf.sub/run for :n on the value-changing recompute")
        (let [t (:tags (first runs))]
          (is (true? (:rf.sub/value-changed? t)) "value changed 1 -> 2")
          (is (= 1 (:rf.sub/prev-value t)) ":rf.sub/prev-value is the prior computed value")
          (is (= 2 (:rf.sub/value t)) ":value is the freshly computed value")
          (is (false? (:rf.sub/cascade? t)) "layer-1 sub is app-db-driven, not a cascade")
          (is (nil? (:rf.sub/cause-sub t)) "layer-1 has no upstream sub to attribute"))))))

(deftest sub-run-value-unchanged-attribution
  (testing "a recompute whose value did NOT change stamps :rf.sub/value-changed? false"
    ;; Prove the false case genuinely exists: a layer-1 sub that projects
    ;; the SAME value out of a CHANGED db. The memo wrapper compares db
    ;; identity (layer-1 reads app-db directly), so a db write to an
    ;; unrelated key forces the body to re-run, but the body returns a
    ;; `=`-equal value for this sub.
    (rf/reg-event :seed   (fn [{:keys [db]} _] {:db {:n 5 :other 0}}))
    (rf/reg-event :bump-other (fn [{:keys [db]} _] {:db (update db :other inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          before @r ;; first recompute, value 5
          after  (atom nil)
          events (collect-trace
                   (fn []
                     ;; db changes (whole-map identity changes), :n body
                     ;; re-runs, but :n's value stays 5.
                     (rf/dispatch-sync [:bump-other])
                     (reset! after @r)))
          runs   (sub-runs events :n)]
      ;; ALWAYS-ON (rf2-d2841): the false case the tag reports has a
      ;; production shape — the db DID change and :n's projection did NOT.
      (is (= 5 before))
      (is (= 5 @after) ":n's value is unchanged across the write, in this posture")
      (is (= 1 (:other (rf/app-db-value :rf/default)))
          "the db really did change — the unchanged-value case is not vacuous")
      (when interop/debug-enabled?
        (is (seq runs) "expected a :rf.sub/run for :n on the re-run (db identity changed)")
        (let [t (:tags (first runs))]
          (is (false? (:rf.sub/value-changed? t)) ":n re-ran but its value stayed 5")
          (is (= 5 (:rf.sub/prev-value t)))
          (is (= 5 (:rf.sub/value t))))))))

(deftest sub-run-cascade-attribution-layer-2
  (testing "a layer-2 sub recomputed by an upstream sub change stamps :rf.sub/cascade? true + :rf.sub/cause-sub naming the upstream"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 2}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :doubled
      :<- [:n]
      (fn [n _] (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:doubled])
          before @r ;; first recompute, value 4
          after  (atom nil)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc]) ;; :n 2->3, :doubled cascades 4->6
                     (reset! after @r)))
          runs   (sub-runs events :doubled)]
      ;; ALWAYS-ON (rf2-d2841): the cascade itself — an upstream write
      ;; propagating through a layer-2 projection — is production behaviour.
      (is (= 4 before))
      (is (= 6 @after) ":doubled cascaded 4 -> 6 in this posture")
      (when interop/debug-enabled?
        (is (seq runs) "expected a :rf.sub/run for :doubled on the cascade")
        (let [t (:tags (first runs))]
          (is (true? (:rf.sub/value-changed? t)) ":doubled changed 4 -> 6")
          (is (= 4 (:rf.sub/prev-value t)))
          (is (= 6 (:rf.sub/value t)))
          (is (true? (:rf.sub/cascade? t)) "layer-2 recompute is a cascade")
          (is (= [:n] (:rf.sub/cause-sub t))
              ":rf.sub/cause-sub names the upstream sub query-vector that changed"))))))

(deftest sub-run-cascade-attribution-layer-2-multi-input
  (testing "a multi-input layer-2 sub names the SPECIFIC upstream that changed"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:a 1 :b 10}}))
    (rf/reg-event :inc-b (fn [{:keys [db]} _] {:db (update db :b inc)}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:seed])
    (let [r-a    (rf/subscribe [:a])
          r      (rf/subscribe [:sum])
          before @r ;; first recompute, value 11
          after  (atom nil)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc-b]) ;; :b 10->11, :a stable
                     (reset! after @r)))
          runs   (sub-runs events :sum)]
      ;; ALWAYS-ON (rf2-d2841): the asymmetry the `:rf.sub/cause-sub` tag
      ;; ATTRIBUTES is production state — :b moved, :a did not, and :sum
      ;; followed :b.
      (is (= 11 before))
      (is (= 12 @after) ":sum followed :b's change in this posture")
      (is (= 1 @r-a) ":a is genuinely stable across the write")
      (when interop/debug-enabled?
        (is (seq runs))
        (let [t (:tags (first runs))]
          (is (true? (:rf.sub/cascade? t)))
          (is (= [:b] (:rf.sub/cause-sub t))
              ":rf.sub/cause-sub names :b (the changed input), not :a (stable)"))))))

;; ---- :rf.sub/first-run? (rf2-fyd8u) -------------------------------------

(deftest sub-run-first-run-flag-true-on-cache-slot-creation
  (testing "rf2-fyd8u — the run that creates a sub's cache slot
            stamps :rf.sub/first-run? true on :rf.sub/run. Disambiguates
            a value-change row (`← was X`) from a fresh-cache-entry row
            (`:added`) for the Xray SUBSCRIPTIONS leaf-scalar renderer."
    ;; ALWAYS-ON (rf2-d2841): `body-runs` witnesses the slot allocation the
    ;; flag reports — the FIRST deref runs the body, the second does not,
    ;; which is exactly what "this run created the cache slot" means.
    (let [body-runs (atom 0)]
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-sub :n (fn [db _] (swap! body-runs inc) (:n db)))
      (rf/dispatch-sync [:seed])
      ;; First subscribe + deref → this is the run that creates the
      ;; cache slot. The memo wrapper's `prev-value` is the `::unset`
      ;; sentinel here, so `:rf.sub/first-run?` must stamp true.
      (let [first-value (atom nil)
            events (collect-trace
                     (fn []
                       (let [r (rf/subscribe [:n])]
                         (reset! first-value @r))))
            runs   (sub-runs events :n)]
        ;; VACUOUS PASS REMOVED (class 4): `(nil? (:rf.sub/prev-value t))` was
        ;; true for free under the gate, where `t` is nil.
        (is (= 1 @first-value) "the cache-slot-creating run projects 1")
        (is (= 1 @body-runs)
            "exactly one body run allocated the slot, in BOTH postures")
        (when interop/debug-enabled?
          (is (seq runs)
              "expected a :rf.sub/run on the cache-slot-creating recompute")
          (let [t (:tags (first runs))]
            (is (true? (:rf.sub/first-run? t))
                ":rf.sub/first-run? is true on the run that allocated the slot")
            (is (true? (:rf.sub/value-changed? t))
                "first-run is also a value-change (no prior value to compare,
                 per Spec 009 §:rf.sub/run :value-changed? semantics)")
            (is (nil? (:rf.sub/prev-value t))
                ":rf.sub/prev-value is nil on the first recompute (the
                 ::unset sentinel projects to nil per the emit-site cond)")))))))

(deftest sub-run-first-run-flag-false-on-recompute-against-existing-slot
  (testing "rf2-fyd8u — every subsequent recompute (against an
            already-existing cache slot) stamps :rf.sub/first-run? false.
            Pairs with the true-case test above — the boolean must
            actually flip on the second run, not stay true."
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          before @r ;; force the first recompute (creates the slot, value 1)
          after  (atom nil)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc]) ;; n 1 -> 2, existing-slot recompute
                     (reset! after @r)))
          runs   (sub-runs events :n)]
      ;; ALWAYS-ON (rf2-d2841): "the slot already existed" is witnessed by
      ;; the prior deref having produced a value at all — this recompute is
      ;; the second, and it still tracks the write.
      (is (= 1 before) "the slot was already allocated by this deref")
      (is (= 2 @after) "the second recompute tracks the write, in this posture")
      (when interop/debug-enabled?
        (is (seq runs)
            "expected a :rf.sub/run for :n on the value-changing recompute")
        (let [t (:tags (first runs))]
          (is (false? (:rf.sub/first-run? t))
              ":rf.sub/first-run? flips to false on a subsequent recompute")
          (is (true? (:rf.sub/value-changed? t))
              ":n changed 1 -> 2 — value-changed? still true")
          (is (= 1 (:rf.sub/prev-value t))
              ":rf.sub/prev-value carries the real prior value")
          (is (= 2 (:rf.sub/value t))))))))

(deftest sub-run-first-run-flag-true-on-layer-2-cache-creation
  (testing "rf2-fyd8u — layer-2 subs (cascade path) also stamp
            :rf.sub/first-run? true on the run that allocated their
            cache slot. The discriminator is universal across all
            memo wrappers (layer-1, layer-n-1, layer-n)."
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 2}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :doubled
      :<- [:n]
      (fn [n _] (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [first-value (atom nil)
          events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:doubled])]
                       (reset! first-value @r))))
          runs   (sub-runs events :doubled)]
      ;; ALWAYS-ON (rf2-d2841): the layer-2 slot-creating recompute produced
      ;; a value — production state, not a trace tag.
      (is (= 4 @first-value)
          "the layer-2 cache-slot-creating run projects 2*2 in this posture")
      (when interop/debug-enabled?
        (is (seq runs))
        (let [t (:tags (first runs))]
          (is (true? (:rf.sub/first-run? t))
              "layer-2 sub: first-run? true on cache-slot creation")
          (is (= 4 (:rf.sub/value t))))))))

(deftest sub-run-layer-1-no-cause-sub
  (testing "a layer-1 sub never carries a :rf.sub/cause-sub (app-db-driven, not a cascade)"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          before @r
          after  (atom nil)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc])
                     (reset! after @r)))
          runs   (sub-runs events :n)]
      ;; ALWAYS-ON (rf2-d2841): "app-db-driven, not a cascade" is a claim
      ;; about WHERE this sub reads from, and that is production-visible —
      ;; the sub tracks a direct app-db write with no upstream sub involved.
      ;; VACUOUS PASS REMOVED (class 4): `(nil? (:rf.sub/cause-sub t))` was
      ;; true for free under the gate, where `t` is nil.
      (is (= 0 before))
      (is (= 1 @after) "the layer-1 sub tracks the app-db write directly")
      (when interop/debug-enabled?
        (is (seq runs))
        (let [t (:tags (first runs))]
          (is (false? (:rf.sub/cascade? t)))
          (is (nil? (:rf.sub/cause-sub t))))))))

(deftest sub-run-base-shape-still-emitted
  (testing "the :rf.sub/run op-type vocabulary is unchanged — the base tags still ride"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          before @r
          after  (atom nil)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc])
                     (reset! after @r)))
          runs   (sub-runs events :n)]
      ;; ALWAYS-ON (rf2-d2841): the recompute the base shape describes.
      (is (= 1 before))
      (is (= 2 @after) "the recompute the :rf.sub/run row describes really happened")
      (when interop/debug-enabled?
        (is (seq runs))
        (let [ev (first runs)]
          (is (= :rf.sub (:op-type ev)))
          (is (= :n (get-in ev [:tags :rf.sub/id])))
          (is (= [:n] (get-in ev [:tags :rf.sub/query-v])))
          (is (contains? (:tags ev) :frame)))))))

;; ---- :rf.sub/cause-event-id (rf2-okz1u) ----------------------------------
;;
;; The reactive recompute path also stamps `:rf.sub/cause-event-id` (when
;; the optional `re-frame.epoch` artefact is on the classpath and the sub
;; runs inside an in-flight event run): the head of the event vector that
;; kicked off the dispatching drain. Mirrors `:rf.view/cause-event-id`
;; (per rf2-25zo2) — same `:epoch/run-cause` late-bind hook source.
;;
;; The Mike-ruled posture is "option b" attribution-only (no behavioural
;; change to the reactive flush). The tag carries which event invalidated
;; this sub's input so consumers (Xray's Epoch panel) can credit each
;; sub-run to the right epoch row — even when a chained event's drain
;; would otherwise misattribute the run to itself.

(deftest sub-run-cause-event-id-stamped-inside-dispatch
  (testing "rf2-okz1u — a sub-run that fires INSIDE an in-flight event run
            carries :rf.sub/cause-event-id naming the dispatching event.
            The plain-atom JVM path recomputes on deref (no cached
            reaction); land the recompute inside the run window by
            using an fx-handler that derefs — fx runs after :db-changed
            commits, while the event's handler-scope is still bound and
            the in-flight run buffer holds the :rf.event/run-start
            the :epoch/run-cause lookup consumes. Mirrors the
            views-side precedent at view_rendered_op_cljs_test/
            rf-view-rendered-carries-cause-event-id-in-cascade."
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r     (rf/subscribe [:n])
          _warm @r]
      ;; The fx-handler signature is `(fn [ctx args])` per Spec 002
      ;; §The binary fx-handler signature — ctx carries `:frame`,
      ;; `:event`, `:envelope`; args is the value from the `:fx` vector.
      ;; ALWAYS-ON (rf2-d2841): `in-cascade` records what the fx-handler saw
      ;; when it dereferenced DURING the drain. That the sub recomputed
      ;; inside the in-flight run is precisely what `:rf.sub/cause-event-id`
      ;; attributes, and it is production behaviour — the fx observing the
      ;; committed 99 rather than the pre-dispatch 0 IS the in-cascade
      ;; recompute.
      (let [in-cascade (atom ::not-run)]
        (rf/reg-fx :deref-fx (fn [_ctx _args] (reset! in-cascade @r)))
        (rf/reg-event :bump-and-deref
          (fn [_ _]
            ;; The event handler returns the canonical `:db` / `:fx` shape
            ;; (re-frame2 rejects arbitrary top-level keys per
            ;; `events.cljc/police-effect-map-shape!`).
            {:db {:n 99}
             :fx [[:deref-fx true]]}))
        (let [events (collect-trace
                       (fn []
                         (rf/dispatch-sync [:bump-and-deref])))
              runs   (sub-runs events :n)]
          (is (= 99 @in-cascade)
              "the fx-handler dereferenced INSIDE the drain and saw the
               committed value — the in-cascade recompute happened in this
               posture")
          ;; ZERO-ASSERTION DEFTEST FIXED (rf2-d2841): the claim below used to
          ;; sit under `(when (seq runs) …)`. Under the gate `runs` is empty,
          ;; so the `when` was false and NO assertion ran inside it — the
          ;; deftest reported a pass having executed nothing but the failing
          ;; precondition. The precondition and the claim it licenses now
          ;; live together inside the posture guard.
          (when interop/debug-enabled?
            (is (seq runs)
                "expected a :rf.sub/run for :n on the in-cascade fx-deref")
            (let [t (:tags (first runs))]
              (is (= :bump-and-deref (:rf.sub/cause-event-id t))
                  ":rf.sub/cause-event-id names the dispatching event,
                   not the sub-id and not the :seed event"))))))))

(deftest sub-run-cause-event-id-absent-outside-dispatch
  (testing "rf2-okz1u — a sub-run that fires OUTSIDE any in-flight
            dispatch omits :rf.sub/cause-event-id entirely. The slot is
            absent (key not present), not nil, so consumers can read
            `(contains? tags :rf.sub/cause-event-id)` to discriminate
            in-cascade vs no-cascade recomputes."
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    ;; The seed dispatch has settled. The subscribe + deref below runs
    ;; OUTSIDE any in-flight run — the in-flight buffer is empty so
    ;; the `:epoch/run-cause` hook returns no `:cause-event-id`. The
    ;; tag MUST be absent from the emitted `:rf.sub/run` tags.
    (let [outside (atom nil)
          events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:n])]
                       (reset! outside @r))))
          runs   (sub-runs events :n)]
      ;; ALWAYS-ON (rf2-d2841): the recompute really did happen with no
      ;; dispatch in flight — the value is the settled one. VACUOUS PASS
      ;; REMOVED (class 4): `(not (contains? t :rf.sub/cause-event-id))` was
      ;; true for free under the gate, where `t` is nil and `contains?` of nil
      ;; is false for EVERY key.
      (is (= 7 @outside)
          "the out-of-cascade recompute projects the settled value")
      (when interop/debug-enabled?
        (is (seq runs)
            "expected a :rf.sub/run for :n on the cache-creating recompute")
        (let [t (:tags (first runs))]
          (is (not (contains? t :rf.sub/cause-event-id))
              ":rf.sub/cause-event-id is OMITTED (key absent) outside a cascade"))))))

(deftest sub-run-cause-event-id-layer-2-cascade
  (testing "rf2-okz1u — layer-2 sub recomputed inside the cascade
            also carries :rf.sub/cause-event-id. The cause-event-id is
            the SAME for every sub in the cascade (the dispatching
            event) — distinct from :rf.sub/cause-sub (the upstream sub
            that propagated the change, which differs per sub in the
            chain). Two-sub fixture proves the slots are complementary."
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 2}}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :doubled
      :<- [:n]
      (fn [n _] (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [r-n       (rf/subscribe [:n])
          r-doubled (rf/subscribe [:doubled])
          _         @r-n
          _         @r-doubled]
      ;; ALWAYS-ON (rf2-d2841): both subs were invalidated by the SAME write
      ;; and both recomputed inside the SAME drain — that shared causation is
      ;; what the two `:rf.sub/cause-event-id` tags record, and the fx-handler
      ;; observing 3 and 6 together witnesses it without the trace.
      (let [in-cascade (atom ::not-run)]
        (rf/reg-fx :deref-both-fx (fn [_ctx _args] (reset! in-cascade [@r-n @r-doubled])))
        (rf/reg-event :bump-and-deref-both
          (fn [_ _]
            {:db {:n 3}
             :fx [[:deref-both-fx true]]}))
        (let [events (collect-trace
                       (fn []
                         (rf/dispatch-sync [:bump-and-deref-both])))
              n-run  (first (sub-runs events :n))
              d-run  (first (sub-runs events :doubled))]
          (is (= [3 6] @in-cascade)
              "both layers recomputed inside the one drain — the shared
               causation the two cause-event-id tags record")
          ;; ZERO-ASSERTION DEFTEST FIXED (rf2-d2841): the three claims below
          ;; used to sit under `(when (and n-run d-run) …)`, which is false
          ;; under the gate — the deftest ran no assertion inside it at all.
          (when interop/debug-enabled?
            (is (some? n-run))
            (is (some? d-run))
            (is (= :bump-and-deref-both
                   (get-in n-run [:tags :rf.sub/cause-event-id]))
                "layer-1 sub :n carries the dispatching event-id")
            (is (= :bump-and-deref-both
                   (get-in d-run [:tags :rf.sub/cause-event-id]))
                "layer-2 sub :doubled carries the SAME cause-event-id,
                 because both subs were invalidated by the same
                 dispatching event")
            (is (= [:n] (get-in d-run [:tags :rf.sub/cause-sub]))
                ":rf.sub/cause-sub on :doubled still names the upstream
                 sub — the two attribution slots are complementary,
                 not redundant")))))))

(deftest aggregate-cascade-shape-pin
  (testing "aggregate-cascade splits subs by :rf.sub/run vs :rf.sub/skip"
    (let [events [{:operation :rf.sub/run :tags {:rf.sub/id :a :rf.sub/query-v [:a]}}
                  {:operation :rf.sub/skip
                   :tags {:rf.sub/id :b :rf.sub/query-v [:b]
                          :rf.sub/reason :input-value-equal
                          :rf.sub/input-paths-unchanged [[:a]]}}
                  {:operation :rf.flow/computed
                   :tags {:flow-id :f :path [:p]}}
                  {:operation :rf.flow/skip
                   :tags {:flow-id :g :input-paths-unchanged [[:x]]}}
                  {:operation :rf.view/render
                   :tags {:rf.view/render-key [:v :k] :triggered-by :db-change}}]
          dag    (cascade/aggregate-cascade events)]
      ;; Per rf2-l1jz8 the `:subs-recomputed` projection threads value-
      ;; change + cascade attribution; this fixture event carries no
      ;; attribution tags so the slots are nil. The projection RECORD keys
      ;; stay bare (nested record-map carve-out — Spec 009 §`:tags`).
      ;; rf2-okz1u — `:cause-event-id` joins the projection (the
      ;; dispatching cascade's event-id, threaded from
      ;; `:rf.sub/cause-event-id` on the trace tag).
      ;; Assert only the load-bearing identity keys — pinning the whole
      ;; nil-padded record by `=` is brittle (an additive projection key
      ;; would break this with no behaviour change to catch).
      (is (= [{:sub-id :a :query-v [:a]}]
             (mapv #(select-keys % [:sub-id :query-v]) (:subs-recomputed dag))))
      (is (= [{:sub-id :b :query-v [:b]
               :reason :input-value-equal
               :input-paths-unchanged [[:a]]}]
             (:subs-skipped dag)))
      (is (= [{:flow-id :f :path [:p]}] (:flows-computed dag)))
      (is (= [{:flow-id :g :input-paths-unchanged [[:x]]}]
             (:flows-skipped dag)))
      (is (= [{:render-key [:v :k] :triggered-by :db-change}]
             (:views-rendered dag)))
      (is (false? (:sub-cap-truncated? dag)))
      (is (false? (:view-cap-truncated? dag))))))
