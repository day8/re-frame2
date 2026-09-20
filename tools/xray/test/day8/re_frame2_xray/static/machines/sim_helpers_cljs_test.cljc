(ns day8.re-frame2-xray.static.machines.sim-helpers-cljs-test
  "Pure-data tests for the Static Machines Sim sub-mode helpers
  (rf2-r4nao rehost; engine originally rf2-v869p Phase 2, parent
  rf2-2tkza). Algebra is unchanged — only the ns moved.

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern every other Xray helper test uses:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. `initial-snapshot`        — seed shape derived from definition
    2. `event-id-suggestions`    — autocomplete source
    3. `available-transitions`   — picker source for the current state
    4. `parse-event-vector`      — input-string parser
    5. `make-sim-state` /
       `reset-sim-state` /
       `append-audit-row` /
       `record-error` /
       `clear-error`             — sim-state lifecycle ops
    6. `step-sim`                — fold an
                                   `re-frame.machines/machine-transition`
                                   result (the Spec 005 §Level 1 map)
                                   into sim-state
    7. `format-state-display` /
       `format-event-display`    — UI-facing formatters"
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [re-frame.machines :as rf.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [day8.re-frame2-xray.static.machines.sim-helpers :as sim-h]))

;; ---- the engine, not a hand-written stand-in ----------------------------
;;
;; rf2-y8doi.21: the machines artefact is a top-level dependency of
;; tools/xray (`deps.edn` → `day8/re-frame2-machines`) and `machines/src`
;; is on the consolidated `:node-test` source-paths, so BOTH hosts can
;; call the real engine. Every fixture below that describes an engine
;; result is OBTAINED from `rf.machines/machine-transition` rather than
;; written by hand — the shapes this file used to fabricate were shapes
;; the engine never returns.

(defn- engine-seed
  "The seeder `sim.cljs` hands `make-sim-state` in production."
  [definition]
  (rf.machines.parallel/build-initial-snapshot definition {:bootstrap-pending? false}))

;; ---- fixtures ------------------------------------------------------------

(def ^:private flat-definition
  "Minimal flat machine for the picker / step exercises."
  {:initial :idle
   :data    {:counter 0}
   :states  {:idle    {:on {:start :authing
                            :reset :idle}}
             :authing {:on {:ok  {:target :done}
                            :err {:target :failed :guard :can-retry?}}}
             :done    {:final? true}
             :failed  {:final? true :on {:retry :idle}}}})

(def ^:private hierarchical-definition
  "Compound-state definition to exercise vector :state paths."
  {:initial :auth
   :states  {:auth {:initial :form
                    :states  {:form   {:on {:submit :loading}}
                              :loading {:on {:ok :done}}}}
             :done {:final? true}}})

(def ^:private parallel-definition
  "A `:type :parallel` root. Parallel machines declare `:regions`, not
  `:states`, and carry NO root `:initial` — which is exactly why the
  shallow seed reads nil for them."
  {:type    :parallel
   :data    {}
   :regions {:form {:initial :editing
                    :states  {:editing {:on {:submit :submitted}}
                              :submitted {}}}
             :net  {:initial :idle
                    :states  {:idle {} :busy {}}}}})

(def ^:private guarded-definition
  "A guard that reads the snapshot's `:data`, so the sim can drive it
  both ways from the SAME definition."
  {:initial :locked
   :data    {:key? false}
   :guards  {:has-key? (fn [{:keys [data]}] (boolean (:key? data)))}
   :states  {:locked   {:on {:open {:target :unlocked :guard :has-key?}}}
             :unlocked {}}})

(defn- engine-step
  "The `runtime-fn` `sim.cljs` hands `step-sim` in production — the real
  pure engine closed over the sim's definition + snapshot."
  [definition snapshot]
  (fn [event] (rf.machines/machine-transition definition snapshot event)))

;; ---- (1) initial-snapshot -----------------------------------------------

(deftest initial-snapshot-builds-seed-from-flat-definition
  (let [snap (sim-h/initial-snapshot flat-definition)]
    (is (= :idle (:state snap)))
    (is (= {:counter 0} (:data snap)))))

(deftest initial-snapshot-defaults-data-to-empty-map
  (let [snap (sim-h/initial-snapshot {:initial :a :states {:a {}}})]
    (is (= :a (:state snap)))
    (is (= {} (:data snap))
        "missing :data slot defaults to {}")))

(deftest initial-snapshot-returns-nil-for-bad-input
  (is (nil? (sim-h/initial-snapshot nil)))
  (is (nil? (sim-h/initial-snapshot {}))
      "no :initial slot → nil")
  (is (nil? (sim-h/initial-snapshot "not a map"))))

;; ---- (1b) seeding THROUGH THE ENGINE (rf2-y8doi.21) ---------------------
;;
;; The shallow read is right only for a FLAT machine. A compound root is
;; not a state the machine can rest in, and a parallel root has no
;; `:initial` at all — so the sim used to open stuck at a compound node
;; (every later step recording a phantom `:auth → :auth`) or with a nil
;; snapshot. The engine already computes the right answer; the sim asks
;; it rather than re-deriving it.

(deftest initial-snapshot-seeds-a-compound-root-at-the-engine-leaf
  (testing "a compound :initial descends its own :initial chain to a leaf
            path — the value the ENGINE seeds, not the compound node"
    (let [snap (sim-h/initial-snapshot hierarchical-definition engine-seed)]
      (is (= [:auth :form] (:state snap))
          "descends :auth → :form rather than resting on the compound :auth")
      (is (= (:state (engine-seed hierarchical-definition)) (:state snap))
          "identical to the engine's own initial snapshot"))))

(deftest initial-snapshot-seeds-a-parallel-root-with-a-region-map
  (testing "a :type :parallel root seeds a region→state map, not nil"
    (let [snap (sim-h/initial-snapshot parallel-definition engine-seed)]
      (is (= {:form :editing :net :idle} (:state snap))
          "one entry per declared region, each at its own cascaded initial")
      (is (= (:state (engine-seed parallel-definition)) (:state snap))
          "identical to the engine's own initial snapshot"))))

(deftest initial-snapshot-keeps-the-engines-own-snapshot-slots
  (testing "the seed is the engine's whole snapshot — `:rf/spawn-counter`
            included — so the first step runs against what the runtime
            would have had, not a reconstruction of it"
    (let [snap (sim-h/initial-snapshot hierarchical-definition engine-seed)]
      (is (= (engine-seed hierarchical-definition) snap)))))

(deftest initial-snapshot-without-a-seeder-keeps-the-shallow-read
  (testing "the seeder is optional: with none supplied the helper stays a
            pure `:initial` read, so the JVM target drives it with no
            machines artefact at all"
    (is (= :auth (:state (sim-h/initial-snapshot hierarchical-definition))))
    (is (nil? (sim-h/initial-snapshot parallel-definition))
        "no root :initial → nil, which is why a seeder is wanted")))

(deftest make-sim-state-seeds-through-the-supplied-seeder
  (testing "the production path — `sim.cljs`'s :sim-start hands the engine
            seeder straight through"
    (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)]
      (is (= [:auth :form] (sim-h/current-sim-state s0)))
      (is (= (engine-seed hierarchical-definition) (:snapshot s0))))))

(deftest reset-sim-state-restores-the-seed-it-opened-with
  (testing "reset rewinds to the snapshot the slot was SEEDED with, so a
            seeded sim does not silently fall back to the shallow read"
    (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)
          s1 (assoc s0 :snapshot {:state [:auth :loading] :data {}}
                       :audit-trail [{:from [:auth :form] :to [:auth :loading]}])
          s2 (sim-h/reset-sim-state s1)]
      (is (= (:snapshot s0) (:snapshot s2)))
      (is (= [:auth :form] (sim-h/current-sim-state s2)))
      (is (= [] (:audit-trail s2))))))

;; ---- (2) event-id-suggestions -------------------------------------------

(deftest event-id-suggestions-aggregates-all-on-keys
  (let [suggestions (sim-h/event-id-suggestions flat-definition)]
    (is (= [:err :ok :reset :retry :start] suggestions)
        "all distinct :on keys, sorted by string")))

(deftest event-id-suggestions-walks-compound-states
  (let [suggestions (sim-h/event-id-suggestions hierarchical-definition)]
    (is (= #{:submit :ok} (set suggestions))
        "nested :states are walked")))

(deftest event-id-suggestions-handles-nil-and-empty
  (is (= [] (sim-h/event-id-suggestions nil)))
  (is (= [] (sim-h/event-id-suggestions {})))
  (is (= [] (sim-h/event-id-suggestions {:initial :a :states {:a {}}}))))

;; ---- (3) available-transitions ------------------------------------------

(deftest available-transitions-lists-outgoing-from-current-leaf
  (let [snap {:state :authing :data {}}
        ts   (sim-h/available-transitions flat-definition snap)
        events (set (map :event ts))]
    (is (= #{:ok :err} events))
    (is (= true (-> (some #(when (= :err (:event %)) %) ts) :guard?))
        ":err carries a :guard slot")))

(deftest available-transitions-from-keyword-target
  (let [snap {:state :idle :data {}}
        ts   (sim-h/available-transitions flat-definition snap)]
    (is (= #{:start :reset} (set (map :event ts))))
    (is (every? (complement :guard?) ts)
        "no :guard? on :idle's transitions")))

(deftest available-transitions-empty-for-final-state
  (let [snap {:state :done :data {}}]
    (is (= [] (sim-h/available-transitions flat-definition snap)))))

(deftest available-transitions-empty-for-unknown-state
  (is (= [] (sim-h/available-transitions flat-definition {:state :nonexistent}))))

(deftest available-transitions-nil-safe
  (is (= [] (sim-h/available-transitions nil nil)))
  (is (= [] (sim-h/available-transitions flat-definition nil))))

;; ---- (3b) `:after` timer rows (rf2-pzuqw) -------------------------------
;;
;; The rail lists each `:after` timer declared on the ACTIVE PATH as a
;; MANUAL TIMEOUT TRIGGER, and firing one sends the engine's own synthetic
;; `[:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]`. The
;; epoch is read back off the `:rf.machine/after-schedule` fx `step-sim`
;; already stores on every audit row, so the sim re-derives NONE of the
;; engine's timer-addressing rules (region prefixes, per-node epoch slots).
;;
;; Every fixture below is driven through the REAL engine (`engine-seed` /
;; `engine-step`), so no test here can pin a shape the engine never returns.

(def ^:private timer-definition
  "Flat machine with one `:after` on a NON-initial state, so entering it
  produces a real after-schedule fx with a non-zero epoch."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:after {5000 :timeout} :on {:loaded :ready}}
             :timeout {}
             :ready   {}}})

(def ^:private seed-timer-definition
  "`:after` on the INITIAL state. The seed runs no step, so no fx has ever
  been stored and the epoch must fall back to 0 — which is exactly what
  the engine's own `node-epoch` reads for an absent node."
  {:initial :loading
   :states  {:loading {:after {5000 :timeout}}
             :timeout {}}})

(def ^:private ancestor-timer-definition
  "`:after` declared on a COMPOUND ancestor while its leaf is active. A
  leaf-only lookup lists nothing here, which is what makes T3 bite."
  {:initial :auth
   :states  {:auth    {:initial :form
                       :after   {9000 :expired}
                       :states  {:form    {:on {:submit :loading}}
                                 :loading {}}}
             :expired {}}})

(def ^:private parallel-timer-definition
  "`:after` inside ONE region of a parallel root. The engine prefixes the
  decl-path with the region name before the result leaves it, so the sim
  never has to know that rule."
  {:type    :parallel
   :data    {}
   :regions {:net  {:initial :idle
                    :states  {:idle    {:on {:go :busy}}
                              :busy    {:after {3000 :timeout}}
                              :timeout {}}}
             :form {:initial :editing
                    :states  {:editing {} :submitted {}}}}})

(def ^:private declined-timer-definition
  "A timer whose guard always declines — an ordinary machine outcome, not
  an error."
  {:initial :loading
   :data    {:ok? false}
   :guards  {:never (fn [_] false)}
   :states  {:loading {:after {5000 {:target :timeout :guard :never}}}
             :timeout {}}})

(defn- step!
  "One `step-sim` fold through the REAL engine against `sim-state`'s own
  current snapshot — the production call shape."
  [sim-state event definition]
  (sim-h/step-sim sim-state event
                  (engine-step definition (:snapshot sim-state))))

(defn- stored-epochs
  "Every after-schedule epoch the sim has stored, oldest-first."
  [sim-state decl-path delay-key]
  (->> (:audit-trail sim-state)
       (mapcat :fx)
       (filter #(and (vector? %) (= :rf.machine/after-schedule (first %))))
       (map second)
       (filter #(and (= decl-path (:rf/invoke-id %))
                     (= delay-key (:delay-key %))))
       (mapv :epoch)))

(deftest available-after-transitions-lists-a-flat-timer-and-fires-it
  (testing "T1 — a timer on the active leaf lists, and its event carries the
            epoch the engine itself handed the sim"
    (let [s0   (sim-h/make-sim-state :t timer-definition engine-seed)
          s1   (step! s0 [:start] timer-definition)
          rows (sim-h/available-after-transitions (:definition s1) (:snapshot s1))
          row  (first rows)]
      (is (= 1 (count rows)))
      (is (= 5000 (:delay-key row)))
      (is (= :timeout (:target row)))
      (is (= [:loading] (:decl-path row)) "the engine's own decl-path")
      (is (false? (:guard? row)))
      (let [fx-epoch (last (stored-epochs s1 [:loading] 5000))
            event    (sim-h/after-elapsed-event s1 row)]
        (is (some? fx-epoch) "the step really did store an after-schedule fx")
        (is (not= 0 fx-epoch)
            "THE CONTROL — a stored epoch is NOT the 0 default, so this test
             would still bite if the fold silently found nothing")
        (is (= [:rf.machine.timer/after-elapsed 5000 fx-epoch [:loading]] event))
        (testing "and the engine honours it"
          (let [s2 (step! s1 event timer-definition)]
            (is (= :timeout (sim-h/current-sim-state s2)))
            (is (= 2 (count (:audit-trail s2))))
            (is (= event (-> s2 :audit-trail last :event)))))))))

(deftest after-elapsed-event-with-a-stale-epoch-is-the-no-change-diagnostic
  (testing "T1 control — the engine is the arbiter: a stale epoch moves
            nothing and reads as the EXISTING amber 'No change', not as a
            new diagnostic"
    (let [s0 (sim-h/make-sim-state :t timer-definition engine-seed)
          s1 (step! s0 [:start] timer-definition)
          s2 (step! s1 [:rf.machine.timer/after-elapsed 5000 99 [:loading]]
                    timer-definition)]
      (is (= :loading (sim-h/current-sim-state s2)) "snapshot unchanged")
      (is (= 1 (count (:audit-trail s2))) "no row appended")
      (is (= :rf.xray.static.machines.sim/no-change
             (-> s2 :last-error :info :kind))))))

(deftest available-after-transitions-lists-a-timer-on-the-seed-state
  (testing "T2 — a timer on the initial state lists before any step, its
            event carries epoch 0, and the engine fires it"
    (let [s0   (sim-h/make-sim-state :t seed-timer-definition engine-seed)
          rows (sim-h/available-after-transitions (:definition s0) (:snapshot s0))
          row  (first rows)]
      (is (= 1 (count rows)))
      (is (= [:loading] (:decl-path row)))
      (is (= [] (:audit-trail s0)) "nothing has been stored yet")
      (let [event (sim-h/after-elapsed-event s0 row)]
        (is (= [:rf.machine.timer/after-elapsed 5000 0 [:loading]] event)
            "the seed default is 0, which is what `node-epoch` reads")
        (let [s1 (step! s0 event seed-timer-definition)]
          (is (= :timeout (sim-h/current-sim-state s1)))
          (is (= 1 (count (:audit-trail s1)))))))))

(deftest available-after-transitions-walks-compound-ancestors
  (testing "T3 — an `:after` on a COMPOUND ancestor lists while its LEAF is
            active, and firing it exits the compound"
    (let [s0   (sim-h/make-sim-state :t ancestor-timer-definition engine-seed)
          s1   (step! s0 [:submit] ancestor-timer-definition)
          rows (sim-h/available-after-transitions (:definition s1) (:snapshot s1))
          row  (first rows)]
      (is (= [:auth :loading] (sim-h/current-sim-state s1))
          "the leaf is active and declares NO :after of its own")
      (is (= [] (sim-h/available-after-transitions
                  {:states (select-keys (:states ancestor-timer-definition) [:expired])}
                  (:snapshot s1)))
          "THE CONTROL — with the ancestor removed there is nothing to list,
           so the row below really does come from the ancestor walk")
      (is (= 1 (count rows)))
      (is (= 9000 (:delay-key row)))
      (is (= :expired (:target row)))
      (is (= [:auth] (:decl-path row)) "the ANCESTOR's path, not the leaf's")
      (let [event (sim-h/after-elapsed-event s1 row)
            s2    (step! s1 event ancestor-timer-definition)]
        (is (= [:rf.machine.timer/after-elapsed 9000 0 [:auth]] event))
        (is (= :expired (sim-h/current-sim-state s2))
            "firing the ancestor's timer exits the compound")))))

(deftest available-after-transitions-region-prefixes-a-parallel-timer
  (testing "T4 — a timer inside one parallel region lists with the engine's
            own region-prefixed decl-path, and firing it moves THAT region
            only"
    (let [s0   (sim-h/make-sim-state :t parallel-timer-definition engine-seed)
          s1   (step! s0 [:go] parallel-timer-definition)
          rows (sim-h/available-after-transitions (:definition s1) (:snapshot s1))
          row  (first rows)]
      (is (= {:net :busy :form :editing} (sim-h/current-sim-state s1)))
      (is (= 1 (count rows)))
      (is (= [:net :busy] (:decl-path row))
          "region-prefixed — the sim reads it off the fx, it does not derive it")
      (is (= [[:net :busy]]
             (->> (:audit-trail s1)
                  (mapcat :fx)
                  (filter #(= :rf.machine/after-schedule (first %)))
                  (mapv (comp :rf/invoke-id second))))
          "and that IS the path the engine put on its own fx")
      (let [event (sim-h/after-elapsed-event s1 row)
            s2    (step! s1 event parallel-timer-definition)]
        (is (= [:rf.machine.timer/after-elapsed 3000 1 [:net :busy]] event))
        (is (= {:net :timeout :form :editing} (sim-h/current-sim-state s2))
            "the other region is untouched")))))

(deftest available-after-transitions-keeps-listing-a-guard-declined-timer
  (testing "T5 — a declined guard is ordinary machine behaviour: no row, the
            existing no-change diagnostic, and the timer STAYS listed (the
            sim keeps no clock and reaps nothing)"
    (let [s0 (sim-h/make-sim-state :t declined-timer-definition engine-seed)
          r0 (sim-h/available-after-transitions (:definition s0) (:snapshot s0))
          ev (sim-h/after-elapsed-event s0 (first r0))
          s1 (step! s0 ev declined-timer-definition)]
      (is (= 1 (count r0)))
      (is (true? (:guard? (first r0))) "the row is tagged as guarded")
      (is (= :never (:guard (first r0))))
      (is (= :loading (sim-h/current-sim-state s1)) "snapshot unchanged")
      (is (= [] (:audit-trail s1)) "no row appended")
      (is (= :rf.xray.static.machines.sim/no-change
             (-> s1 :last-error :info :kind)))
      (is (= 1 (count (sim-h/available-after-transitions
                        (:definition s1) (:snapshot s1))))
          "and it is still listed afterwards"))))

(deftest after-elapsed-event-takes-the-newest-epoch-on-re-entry
  (testing "re-entering an `:after` node bumps the engine's epoch, so the
            fold must take the NEWEST stored fx or the row fires stale"
    (let [d  {:initial :idle
              :states  {:idle    {:on {:start :loading}}
                        :loading {:after {5000 :timeout} :on {:back :idle}}
                        :timeout {}}}
          s0 (sim-h/make-sim-state :t d engine-seed)
          s1 (step! s0 [:start] d)
          s2 (step! s1 [:back] d)
          s3 (step! s2 [:start] d)
          epochs (stored-epochs s3 [:loading] 5000)
          row    (first (sim-h/available-after-transitions (:definition s3)
                                                           (:snapshot s3)))]
      (is (= 2 (count epochs)) "two entries were stored")
      (is (apply not= epochs)
          "THE CONTROL — the two epochs genuinely differ, so newest-vs-oldest
           is a distinction this test can see")
      (is (= (last epochs) (nth (sim-h/after-elapsed-event s3 row) 2)))
      (is (= :timeout (sim-h/current-sim-state
                        (step! s3 (sim-h/after-elapsed-event s3 row) d)))))))

(deftest available-after-transitions-is-empty-where-nothing-is-declared
  (testing "negative controls — no `:after` on the active path, and nil input"
    (is (= [] (sim-h/available-after-transitions flat-definition
                                                 {:state :idle :data {}}))
        "flat-definition declares no :after anywhere")
    (is (= [] (sim-h/available-after-transitions nil nil)))
    (is (= [] (sim-h/available-after-transitions timer-definition nil)))
    (is (= [] (sim-h/available-after-transitions timer-definition
                                                 {:state :nonexistent}))))
  (testing "and the positive control on the SAME instrument, so an empty
            reading means absence rather than a broken walk"
    (is (= 1 (count (sim-h/available-after-transitions
                      timer-definition {:state :loading :data {}}))))))

;; ---- (4) parse-event-vector ---------------------------------------------

(deftest parse-event-vector-accepts-keyword-string
  (is (= [:foo/bar] (sim-h/parse-event-vector ":foo/bar"))))

(deftest parse-event-vector-accepts-vector-form
  (is (= [:foo/bar {:x 1}]
         (sim-h/parse-event-vector "[:foo/bar {:x 1}]"))))

(deftest parse-event-vector-trims-whitespace
  (is (= [:foo/bar] (sim-h/parse-event-vector "  :foo/bar  "))))

(deftest parse-event-vector-rejects-empty
  (is (= {:error "empty"} (sim-h/parse-event-vector nil)))
  (is (= {:error "empty"} (sim-h/parse-event-vector "")))
  (is (= {:error "empty"} (sim-h/parse-event-vector "   "))))

(deftest parse-event-vector-rejects-non-keyword-head
  (let [r (sim-h/parse-event-vector "[\"foo\" 1]")]
    (is (map? r))
    (is (:error r))))

(deftest parse-event-vector-rejects-malformed-edn
  (let [r (sim-h/parse-event-vector "[:foo {bad")]
    (is (map? r))
    (is (:error r))))

;; ---- (5) sim-state lifecycle --------------------------------------------

(deftest make-sim-state-builds-active-slot
  (let [s (sim-h/make-sim-state :auth/login flat-definition)]
    (is (= :auth/login (:machine-id s)))
    (is (true? (:active? s)))
    (is (= flat-definition (:definition s)))
    (is (= :idle (get-in s [:snapshot :state])))
    (is (= [] (:audit-trail s)))
    (is (nil? (:last-error s)))
    (is (= "" (:pending-event s)))
    (is (= "" (:pending-data s)))))

(deftest reset-sim-state-rewinds-snapshot-clears-trail
  (let [s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (-> s0
               (assoc :snapshot {:state :done :data {:counter 5}})
               (sim-h/append-audit-row {:from :idle :to :done :event [:start]})
               (sim-h/record-error [:bad] {} "something went wrong"))
        s2 (sim-h/reset-sim-state s1)]
    (is (= :idle (get-in s2 [:snapshot :state]))
        "snapshot rewound to initial")
    (is (= [] (:audit-trail s2))
        "trail cleared")
    (is (nil? (:last-error s2))
        "error cleared")
    (is (true? (:active? s2))
        "still in sim mode")))

(deftest append-audit-row-grows-trail
  (let [s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/append-audit-row s0 {:from :idle :to :authing :event [:start]})
        s2 (sim-h/append-audit-row s1 {:from :authing :to :done :event [:ok]})]
    (is (= 2 (count (:audit-trail s2))))
    (is (= :idle (-> s2 :audit-trail first :from))
        "insertion order preserved (oldest first)")
    (is (= :done (-> s2 :audit-trail last :to)))))

(deftest record-and-clear-error-flips-error-slot
  (let [s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/record-error s0 [:bad] {:reason :unknown} "rejected")]
    (is (= {:event [:bad] :info {:reason :unknown} :reason "rejected"}
           (:last-error s1)))
    (is (nil? (:last-error (sim-h/clear-error s1))))))

;; ---- (6) step-sim ----------------------------------------------------------
;;
;; Stub results in the Spec 005 §Level 1 public shape
;; `re-frame.machines/machine-transition` returns — plain keys, no
;; machines artefact on the test classpath.

(def ^:private ok-result
  {:status :ok
   :snapshot {:state :authing :data {:counter 1}}
   :fx []})

;; rf2-y8doi.21: this used to be a HAND-WRITTEN
;; `{:status :error :error {:kind … :reason :no-matching-transition}}`.
;; The engine never returns that shape twice over: `:no-matching-transition`
;; appears nowhere in the machines artefact, and an event no transition
;; matched is `:status :ok` with the snapshot unchanged
;; (`machines.cljc` — "An event no transition matched is `:status :ok`
;; with the snapshot unchanged and `:fx []`"). A REAL `:status :error` is
;; the engine's own failed macrostep, so we obtain one by making an
;; action throw.

(def ^:private throwing-definition
  {:initial :a
   :data    {}
   :actions {:boom (fn [_] (throw (ex-info "boom" {:why :fixture})))}
   :states  {:a {:on {:go {:target :b :action :boom}}}
             :b {}}})

(def ^:private fail-result
  "An ACTUAL engine `:status :error`, obtained from the producer."
  (rf.machines/machine-transition throwing-definition
                                  (engine-seed throwing-definition)
                                  [:go]))

(deftest fail-result-fixture-really-is-an-engine-error
  (testing "the fixture is the engine's own shape, not a hand-written one"
    (is (= :error (:status fail-result)))
    (is (= :rf.error/machine-action-exception (get-in fail-result [:error :kind])))
    (is (nil? (get-in fail-result [:error :reason]))
        "the engine's :error map carries no :reason — the old fixture invented one")))

(deftest step-sim-ok-advances-snapshot-and-trail
  (let [s0       (sim-h/make-sim-state :auth/login flat-definition)
        runtime  (constantly ok-result)
        s1       (sim-h/step-sim s0 [:start] runtime)]
    (is (= :authing (get-in s1 [:snapshot :state]))
        "snapshot advanced")
    (is (= {:counter 1} (get-in s1 [:snapshot :data])))
    (is (= 1 (count (:audit-trail s1))))
    (is (= :idle (-> s1 :audit-trail last :from)))
    (is (= :authing (-> s1 :audit-trail last :to)))
    (is (= [:start] (-> s1 :audit-trail last :event)))
    (is (nil? (:last-error s1)))))

(deftest step-sim-fail-leaves-snapshot-and-stamps-error
  (let [s0      (sim-h/make-sim-state :auth/login flat-definition)
        runtime (constantly fail-result)
        s1      (sim-h/step-sim s0 [:bad] runtime)]
    (is (= :idle (get-in s1 [:snapshot :state]))
        "snapshot unchanged on fail")
    (is (= 0 (count (:audit-trail s1)))
        "trail unchanged on fail")
    (is (= [:bad] (-> s1 :last-error :event)))
    (is (= "transition failed" (-> s1 :last-error :reason)))))

(deftest step-sim-fail-then-ok-clears-prior-error
  (let [s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/step-sim s0 [:bad] (constantly fail-result))
        s2 (sim-h/step-sim s1 [:start] (constantly ok-result))]
    (is (some? (:last-error s1)))
    (is (nil? (:last-error s2))
        "the next OK step clears the prior error")
    (is (= 1 (count (:audit-trail s2))))))

(deftest step-sim-non-result-treated-as-fail
  (let [s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/step-sim s0 [:start] (constantly "not a result"))]
    (is (= :idle (get-in s1 [:snapshot :state])))
    (is (some? (:last-error s1)))
    (is (= "engine returned a non-result value" (-> s1 :last-error :reason)))))

;; ---- (6b) a step the engine DECLINED is not a transition ----------------
;;
;; rf2-y8doi.21. A guard-blocked or unhandled event comes back
;; `:status :ok` with the snapshot UNCHANGED and `:fx []` — the engine
;; returns the same three shapes for "stale", "guard-suppressed" and "no
;; match" (`transition.cljc`'s `apply-preselected-transition`). Folding
;; every `:ok` as a transition invented a self-transition the framework
;; never made: a `#N :open → :open` audit row with an animated from=to
;; edge and no diagnostic at all.
;;
;; Every fixture below is the REAL engine result, so the test cannot pin
;; a shape the engine does not produce.

;; `guarded-definition` is FLAT, so the shallow seed is already the right
;; one for it. These three therefore exercise the `step-sim` fold ALONE,
;; with no seeder in the picture — a clean value-level red rather than an
;; arity one, and the control below shares their exact shape.

(deftest step-sim-guard-blocked-appends-no-row-and-says-so
  (testing "a guard that declines leaves the snapshot put, appends NO audit
            row, and stamps the rejection"
    (let [s0 (sim-h/make-sim-state :door guarded-definition)
          s1 (sim-h/step-sim s0 [:open] (engine-step guarded-definition (:snapshot s0)))]
      (is (= :locked (sim-h/current-sim-state s1))
          "snapshot unchanged — the engine is right")
      (is (= [] (:audit-trail s1))
          "NO phantom :locked → :locked row")
      (is (nil? (sim-h/last-transition s1))
          "and nothing for the chart to animate")
      (is (= [:open] (-> s1 :last-error :event)))
      (is (= :rf.xray.static.machines.sim/no-change
             (-> s1 :last-error :info :kind))))))

(deftest step-sim-unhandled-event-appends-no-row-and-says-so
  (testing "an event the machine declares nowhere is the SAME engine
            shape as a guard block, and is treated the same way"
    (let [s0 (sim-h/make-sim-state :door guarded-definition)
          s1 (sim-h/step-sim s0 [:no-such-event]
                             (engine-step guarded-definition (:snapshot s0)))]
      (is (= :locked (sim-h/current-sim-state s1)))
      (is (= [] (:audit-trail s1)))
      (is (= :rf.xray.static.machines.sim/no-change
             (-> s1 :last-error :info :kind))))))

(deftest step-sim-guard-passing-still-appends-a-row
  (testing "THE CONTROL — the same definition, the same event, the same
            call shape, a guard that PASSES: the row must still be
            appended, or the no-change branch has swallowed a real
            transition"
    (let [s0   (sim-h/make-sim-state :door guarded-definition)
          open (assoc s0 :snapshot {:state :locked :data {:key? true}})
          s1   (sim-h/step-sim open [:open]
                               (engine-step guarded-definition (:snapshot open)))]
      (is (= :unlocked (sim-h/current-sim-state s1))
          "the snapshot advanced")
      (is (= 1 (count (:audit-trail s1)))
          "the real transition IS recorded")
      (is (= {:from :locked :to :unlocked :event [:open]}
             (sim-h/last-transition s1)))
      (is (nil? (:last-error s1))
          "a successful step clears the prior rejection"))))

(deftest step-sim-compound-seed-no-longer-phantom-steps
  (testing "the two halves of this item together: seeded at the engine's
            leaf, a declared event is a REAL transition — where the shallow
            seed left the sim stuck at the compound node recording
            `:auth → :auth` for ever"
    (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)
          s1 (sim-h/step-sim s0 [:submit]
                             (engine-step hierarchical-definition (:snapshot s0)))]
      (is (= [:auth :loading] (sim-h/current-sim-state s1)))
      (is (= 1 (count (:audit-trail s1))))
      (is (= {:from [:auth :form] :to [:auth :loading] :event [:submit]}
             (sim-h/last-transition s1))))))

(deftest step-sim-audit-trail-order-newest-last
  "Each step appends — the trail is insertion-ordered so the view can
  render either direction. We pin the contract here so a downstream
  view-test can rely on insertion order."
  (let [results [{:status :ok
                  :snapshot {:state :authing :data {}}
                  :fx []}
                 {:status :ok
                  :snapshot {:state :done :data {}}
                  :fx []}]
        s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/step-sim s0 [:start] (constantly (first results)))
        s2 (sim-h/step-sim s1 [:ok]    (constantly (second results)))
        trail (:audit-trail s2)]
    (is (= 2 (count trail)))
    (is (= [:start] (-> trail first :event)))
    (is (= [:ok]    (-> trail last :event)))))

;; ---- (7) on-chart binding helpers (rf2-u422r) ---------------------------
;;
;; The on-chart simulator binds the topology chart to this same engine:
;; `current-sim-state` drives the active-state highlight, `last-transition`
;; drives the focused-edge animation, and `edge-click->event` coerces an
;; on-chart edge click into a step-event. Pure data → data, so pinned here
;; at the cheap JVM layer.

(deftest current-sim-state-reads-snapshot-state
  (testing "current-sim-state returns the snapshot's :state for the chart
            active-state highlight"
    (let [s0 (sim-h/make-sim-state :auth/login flat-definition)]
      (is (= :idle (sim-h/current-sim-state s0))))))

(deftest current-sim-state-is-nil-safe
  (testing "nil sim-state / missing snapshot → nil (no highlight)"
    (is (nil? (sim-h/current-sim-state nil)))
    (is (nil? (sim-h/current-sim-state {})))))

(deftest current-sim-state-tracks-vector-paths
  (testing "a hierarchical :state path surfaces unchanged for the chart"
    ;; rf2-y8doi.21: this used to pin `:auth` — the COMPOUND node — and
    ;; its comment recorded the defect as if it were the contract. A
    ;; compound root is not a state the machine can rest in, so seeded
    ;; through the engine the sim opens at the leaf PATH, which is what
    ;; the chart's active-state highlight wants.
    (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)]
      (is (= [:auth :form] (sim-h/current-sim-state s0))))
    (testing "and the shallow read (no seeder) still surfaces whatever
              `:initial` declared, unchanged"
      (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition)]
        (is (= :auth (sim-h/current-sim-state s0)))))))

(deftest last-transition-nil-before-any-step
  (testing "no step taken yet → nil (no edge to animate)"
    (let [s0 (sim-h/make-sim-state :auth/login flat-definition)]
      (is (nil? (sim-h/last-transition s0)))
      (is (nil? (sim-h/last-transition nil))))))

(deftest last-transition-projects-most-recent-audit-row
  (testing "last-transition projects the newest audit row into the chart's
            focused-event lens {:from :to :event}"
    (let [ok1 {:status :ok
               :snapshot {:state :authing :data {}}
               :fx []}
          ok2 {:status :ok
               :snapshot {:state :done :data {}}
               :fx []}
          s0  (sim-h/make-sim-state :auth/login flat-definition)
          s1  (sim-h/step-sim s0 [:start] (constantly ok1))
          s2  (sim-h/step-sim s1 [:ok]    (constantly ok2))
          lt  (sim-h/last-transition s2)]
      (is (= :authing (:from lt)) "from = the second step's prior state")
      (is (= :done    (:to lt))   "to = the second step's landing state")
      (is (= [:ok]    (:event lt))))))

(deftest last-transition-unchanged-by-failed-step
  (testing "a failed step does not append a row, so last-transition still
            reflects the last SUCCESSFUL transition (the chart keeps the
            prior edge lit while the guard error toasts)"
    (let [ok  {:status :ok
               :snapshot {:state :authing :data {}}
               :fx []}
          s0  (sim-h/make-sim-state :auth/login flat-definition)
          s1  (sim-h/step-sim s0 [:start] (constantly ok))
          s2  (sim-h/step-sim s1 [:bad]   (constantly fail-result))
          lt  (sim-h/last-transition s2)]
      (is (= :idle    (:from lt)))
      (is (= :authing (:to lt)))
      (is (= [:start] (:event lt))))))

(deftest edge-click->event-coerces-keyword-to-vector
  (testing "a fireable event-id keyword → a `[event-id]` step vector"
    (is (= [:start] (sim-h/edge-click->event :start)))
    (is (= [:auth/login] (sim-h/edge-click->event :auth/login)))))

(deftest edge-click->event-nil-for-non-fireable
  (testing "a nil event-id (an inert :after / :always auto edge, or a
            non-keyword) → nil so the click is a no-op step"
    (is (nil? (sim-h/edge-click->event nil)))
    (is (nil? (sim-h/edge-click->event "start")))
    (is (nil? (sim-h/edge-click->event 42)))))

;; ---- (8) format helpers --------------------------------------------------

(deftest format-state-display-handles-shapes
  (is (= "(none)" (sim-h/format-state-display nil)))
  (is (= ":idle"  (sim-h/format-state-display :idle)))
  (is (= "[:auth :form]" (sim-h/format-state-display [:auth :form]))))

(deftest format-event-display-pr-strs
  (is (= "" (sim-h/format-event-display nil)))
  (is (= "[:foo]" (sim-h/format-event-display [:foo])))
  (is (= "[:foo {:x 1}]" (sim-h/format-event-display [:foo {:x 1}]))))
