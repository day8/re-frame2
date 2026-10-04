(ns day8.re-frame2-xray.static.machines.sim-helpers-cljs-test
  "Pure-data tests for the Static Machines Sim sub-mode helpers.

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
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test    :refer-macros [are deftest is testing]])
            [re-frame.machines :as rf.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [day8.re-frame2-xray.static.machines.sim-helpers :as sim-h]))

;; ---- the engine, not a hand-written stand-in ----------------------------
;;
;; The machines artefact is a top-level dependency of
;; tools/xray (`deps.edn` → `day8/re-frame2-machines`) and `machines/src`
;; is on the consolidated `:node-test` source-paths, so BOTH hosts can
;; call the real engine. Every fixture below that describes an engine
;; result is OBTAINED from `rf.machines/machine-transition` rather than
;; written by hand, because a hand-written shape can be one the engine
;; never returns.

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

(defn- step!
  "One `step-sim` fold through the REAL engine against `sim-state`'s own
  current snapshot — the production call shape.

  Lives here with the other two harness fns: the `:on` and timer
  exercises both use it, and a helper defined below its first caller is
  an ordering trap for whoever adds the next test."
  [sim-state event definition]
  (sim-h/step-sim sim-state event
                  (engine-step definition (:snapshot sim-state))))

;; ---- (1) initial-snapshot -----------------------------------------------

(deftest initial-snapshot-shallow-read-seeds-state-and-data-or-nil
  ;; Rows: a flat definition; a missing :data slot, which defaults to {};
  ;; then nil, a map with no :initial slot, and a non-map, which all read nil.
  (are [definition snap] (= snap (sim-h/initial-snapshot definition))
    flat-definition               {:state :idle :data {:counter 0}}
    {:initial :a :states {:a {}}} {:state :a :data {}}
    nil                           nil
    {}                            nil
    "not a map"                   nil))

;; ---- (1b) seeding THROUGH THE ENGINE ------------------------------------
;;
;; The shallow read is right only for a FLAT machine. A compound root is
;; not a state the machine can rest in, and a parallel root has no
;; `:initial` at all — so a shallow seed would open the sim stuck at a
;; compound node (every later step recording a phantom `:auth → :auth`) or
;; with a nil snapshot. The engine computes the right answer; the sim asks
;; it rather than re-deriving it.

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

(deftest event-id-suggestions-aggregates-the-on-keys-of-every-state
  ;; Rows: every distinct :on key, sorted by string; the nested :states of a
  ;; compound definition are walked; nil, empty and :on-less definitions
  ;; answer [].
  (are [definition ids] (= ids (sim-h/event-id-suggestions definition))
    flat-definition               [:err :ok :reset :retry :start]
    hierarchical-definition       [:ok :submit]
    nil                           []
    {}                            []
    {:initial :a :states {:a {}}} []))

;; ---- (3) available-transitions ------------------------------------------

(deftest available-transitions-lists-outgoing-from-current-leaf
  (let [snap {:state :authing :data {}}
        ts   (sim-h/available-transitions flat-definition snap)
        events (set (map :event ts))]
    (is (= #{:ok :err} events))
    (is (= true (-> (some #(when (= :err (:event %)) %) ts) :guard?))
        ":err carries a :guard slot")))

(deftest available-transitions-is-empty-for-a-final-unknown-or-missing-state
  (are [definition snapshot] (= [] (sim-h/available-transitions definition snapshot))
    ;; :done is final and declares no :on
    flat-definition {:state :done :data {}}
    flat-definition {:state :nonexistent}
    nil             nil
    flat-definition nil))

(deftest available-transitions-stamps-the-declaring-path-on-every-row
  (testing "every row says where it was DECLARED, so a parallel region's rows
            can be told apart — the same `:decl-path` slot
            `available-after-transitions` already stamps"
    (is (= [:idle] (:decl-path (first (sim-h/available-transitions
                                        flat-definition {:state :idle})))))
    (is (= [:auth :form] (:decl-path (first (sim-h/available-transitions
                                              hierarchical-definition
                                              {:state [:auth :form]}))))
        "a compound leaf's own path, not the root's")))

;; ---- (3c) parallel region-maps ------------------------------------------
;;
;; A `:type :parallel` snapshot's `:state` is a MAP of region → state.
;; Handed to `normalise-path` as one state value, that shape would fall
;; through to `[]`, the lookup would run against an empty path, and the
;; picker would render "No outgoing transitions declared on this state."
;; for EVERY parallel machine — a positive claim about the user's
;; definition, and a false one.
;;
;; Every fixture below is seeded and driven through the REAL engine, so no
;; test here can pin a configuration the engine never produces.

(def ^:private parallel-on-definition
  "A `:type :parallel` root whose regions BOTH declare `:on`, plus a root
  `:on` ancestor fallback. `:net`'s `:busy` declares an `:after` as well, so
  one snapshot exercises both halves of the rail at once."
  {:type    :parallel
   :data    {}
   :on      {:abort [:net :timeout]}
   :regions {:form {:initial :editing
                    :states  {:editing   {:on {:submit :submitted}}
                              :submitted {:on {:edit :editing}}}}
             :net  {:initial :idle
                    :states  {:idle    {:on {:go :busy}}
                              :busy    {:after {3000 :timeout}
                                        :on    {:done :idle}}
                              :timeout {}}}}})

(deftest available-transitions-lists-every-parallel-region-leaf
  (testing "P1 — each region's OWN leaf contributes its `:on` rows,
            region-prefixed, rather than the whole picker coming back empty"
    (let [snap (engine-seed parallel-on-definition)
          rows (sim-h/available-transitions parallel-on-definition snap)]
      (is (map? (:state snap))
          "THE PRECONDITION — this snapshot's :state really is a region map")
      (is (= #{:submit :go} (set (map :event rows)))
          "both regions answer, not #{}")
      (is (= {:submit [:form :editing]
              :go     [:net :idle]}
             (into {} (map (juxt :event :decl-path)) rows))
          "region-prefixed, matching what the engine puts on its own fx")
      (is (= {:submit :submitted :go :busy}
             (into {} (map (juxt :event :target)) rows))))))

(deftest available-transitions-excludes-the-parallel-root-on-fallback
  (testing "P4 — a `:type :parallel` ROOT's own `:on` is the engine's ancestor
            fallback, and the picker does NOT list it, for the same reason a
            flat machine's machine-root `:on` has never been listed: both are
            parent inheritance, and this fn is leaf-only by policy"
    (let [snap (engine-seed parallel-on-definition)
          rows (sim-h/available-transitions parallel-on-definition snap)]
      (is (= {:abort [:net :timeout]} (:on parallel-on-definition))
          "THE PRECONDITION — the root really does declare :abort")
      (is (not (contains? (set (map :event rows)) :abort))
          "root :on is excluded")
      (is (= [] (sim-h/available-transitions
                  {:initial :idle
                   :on      {:abort :stopped}
                   :states  {:idle {} :stopped {}}}
                  {:state :idle}))
          "THE CONTROL — the same exclusion already applied to a FLAT
           machine's root :on, so parallel is consistent rather than special"))))

(deftest available-transitions-resolves-a-compound-region-leaf
  (testing "P5 — a region whose own state is hierarchical resolves to its
            LEAF, with the region prefixed onto the full in-region path"
    (let [definition {:type    :parallel
                      :data    {}
                      :regions {:auth {:initial :login
                                       :states  {:login {:initial :form
                                                         :states  {:form    {:on {:submit :loading}}
                                                                   :loading {}}}}}
                                :net  {:initial :idle
                                       :states  {:idle {:on {:go :busy}} :busy {}}}}}
          snap       (engine-seed definition)
          rows       (sim-h/available-transitions definition snap)]
      (is (= [:login :form] (get-in snap [:state :auth]))
          "THE PRECONDITION — the engine seeded the region at a compound leaf")
      (is (= [:auth :login :form]
             (some #(when (= :submit (:event %)) (:decl-path %)) rows))
          "region prefix + the whole in-region path")
      (is (= #{:submit :go} (set (map :event rows)))))))

;; ---- (3a-ii) targetless / action-only `:on` candidates ------------------
;;
;; A row filter requiring a target (`:when (some? t)` in `on-rows-at`) would
;; drop every legal targetless candidate: the rail would list nothing and
;; the user could not fire it, while the engine handles the very same event
;; correctly when it is typed into the input by hand.
;;
;; Spec 005 §Self-transitions makes targetless the ONLY geometry the runtime
;; flags `internal?` — the `:action` runs, `:exit` / `:entry` do not, active
;; descendants are preserved — and the forbidden-transition idiom is spelled
;; exactly this way, so these are first-class declared transitions rather
;; than a degenerate corner.
;;
;; Every fixture here runs through the REAL engine.

(def ^:private bump-action
  "One action, shared by every fixture below, so a data change is always
  attributable to the transition under test."
  {:bump (fn [{:keys [data]}] {:data (update data :n inc)})})

(def ^:private parallel-on-action-only-definition
  "A parallel region leaf whose only handler is action-only, beside an
  untouched second region."
  {:type    :parallel
   :data    {:n 0}
   :actions bump-action
   :regions {:work  {:initial :idle
                     :states  {:idle {:on {:ping {:action :bump}}}
                               :done {}}}
             :other {:initial :sleeping
                     :states  {:sleeping {}}}}})

(deftest available-transitions-lists-a-targetless-action-only-on
  (testing "A1 — an action-only `:on` on a parallel region leaf LISTS, with a
            nil :target and its action named, and firing it runs the action
            while the configuration stands still"
    (let [s0   (sim-h/make-sim-state :t parallel-on-action-only-definition
                                     engine-seed)
          rows (sim-h/available-transitions (:definition s0) (:snapshot s0))
          row  (first rows)]
      (is (= 1 (count rows)))
      (is (= :ping (:event row)))
      (is (nil? (:target row)) "targetless — and NOT handed an invented target")
      (is (= :bump (:action row))
          "the action rides on the row, so the rail can name what it does")
      (is (= [:work :idle] (:decl-path row)) "the engine's own region prefix")
      (is (false? (:guard? row)))
      (testing "and it is FIREABLE — by its event id, which never needed a target"
        (let [s1 (step! s0 [:ping] parallel-on-action-only-definition)]
          (is (= 1 (count (:audit-trail s1))) "the step really was recorded")
          (is (nil? (:last-error s1)) "not the no-change diagnostic")
          (is (= 1 (:n (get-in s1 [:snapshot :data]))) "the action ran")
          (is (= {:work :idle :other :sleeping} (sim-h/current-sim-state s1))
              "and the configuration is unchanged, which is the whole point")
          (is (= 1 (count (sim-h/available-transitions (:definition s1)
                                                        (:snapshot s1))))
              "still listed afterwards"))))))

(deftest available-transitions-lists-the-forbidden-transition-idiom
  (testing "A3 — `{:help {}}` is targetless AND actionless: Spec 005's
            forbidden-transition idiom, a deliberate event CONSUMER. It
            LISTS, because it is a declared handler the user may want to
            fire, and firing it comes back as the amber no-change — the
            same answer a guard-declined timer gets (T5), and
            the honest one, since consuming an event no ancestor was going
            to handle really does change nothing"
    (let [d    {:initial :idle :data {:n 0} :states {:idle {:on {:help {}}}}}
          s0   (sim-h/make-sim-state :t d engine-seed)
          rows (sim-h/available-transitions (:definition s0) (:snapshot s0))
          s1   (step! s0 [:help] d)]
      (is (= 1 (count rows)))
      (is (= :help (:event (first rows))))
      (is (nil? (:target (first rows))))
      (is (nil? (:action (first rows))))
      (is (= [] (:audit-trail s1)) "nothing moved, so no audit row is invented")
      (is (= :rf.xray.static.machines.sim/no-change
             (-> s1 :last-error :info :kind))))))

(deftest available-transitions-lists-every-candidate-of-a-mixed-vector
  (testing "A4 — one event id declaring a VECTOR of candidates yields a row
            EACH, targeted and targetless alike. Neither the decl-path nor
            the event id tells those two rows apart, which is why the rail's
            React key carries the row index as well"
    (let [d    {:initial :idle
                :data    {:n 0}
                :actions bump-action
                :guards  {:never (fn [_] false)}
                :states  {:idle {:on {:go [{:target :done :guard :never}
                                           {:action :bump}]}}
                          :done {}}}
          rows (sim-h/available-transitions d {:state :idle :data {:n 0}})]
      (is (= 2 (count rows)))
      (is (= [:done nil] (mapv :target rows)))
      (is (= [nil :bump] (mapv :action rows)))
      (is (= [:go :go] (mapv :event rows))
          "THE POINT — the event id does not discriminate them")
      (is (= 1 (count (distinct (map :decl-path rows))))
          "and neither does the decl-path"))))

;; ---- (3b) `:after` timer rows -------------------------------------------
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
        (is (= [:expired] (sim-h/current-sim-state s2))
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
            no-change diagnostic, and the timer STAYS listed (the
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

;; ---- (3c) targetless / action-only `:after` timers ----------------------
;;
;; The same target-requiring row filter on the timer half (`:when (some? t)`
;; in `after-rows-at`) would drop `{5000 {:action :bump}}` — a legal
;; action-only timer — so a machine declaring only such timers would
;; present an EMPTY timer list and could not fire them from the rail, even
;; though the engine fires them correctly when the event is sent. The `:on`
;; sibling is at (3a-ii) above.

(def ^:private action-only-timer-definition
  "An action-only timer on the initial state."
  {:initial :loading
   :data    {:n 0}
   :actions bump-action
   :states  {:loading {:after {5000 {:action :bump}}}
             :done    {}}})

(def ^:private parallel-root-action-only-timer-definition
  "A targetless `:after` on the parallel ROOT, whose decl-path is `[]` — the
  shape Spec 005 §Parallel root `:after` lists first among its three target
  grammars."
  {:type    :parallel
   :data    {:n 0}
   :actions bump-action
   :after   {5000 {:action :bump}}
   :regions {:work  {:initial :idle :states {:idle {} :done {}}}
             :other {:initial :sleeping :states {:sleeping {}}}}})

(deftest available-after-transitions-lists-a-targetless-action-only-timer
  (testing "T6 — an action-only timer LISTS with a nil :target, and firing
            the row's own event runs the action while the state is retained"
    (let [s0   (sim-h/make-sim-state :t action-only-timer-definition engine-seed)
          rows (sim-h/available-after-transitions (:definition s0) (:snapshot s0))
          row  (first rows)]
      (is (= 1 (count rows)))
      (is (= 5000 (:delay-key row)))
      (is (nil? (:target row)) "targetless — and NOT handed an invented target")
      (is (= :bump (:action row)))
      (is (= [:loading] (:decl-path row)))
      (is (false? (:guard? row)))
      (testing "and it is FIREABLE — from :delay-key + :decl-path, neither of
                which ever needed the target"
        (let [ev (sim-h/after-elapsed-event s0 row)
              s1 (step! s0 ev action-only-timer-definition)]
          (is (= [:rf.machine.timer/after-elapsed 5000 0 [:loading]] ev)
              "epoch 0 is the seed answer, exactly as T2 establishes")
          (is (= 1 (count (:audit-trail s1))) "the step really was recorded")
          (is (nil? (:last-error s1)) "not the no-change diagnostic")
          (is (= 1 (:n (get-in s1 [:snapshot :data]))) "the action ran")
          (is (= :loading (sim-h/current-sim-state s1))
              "and the state is RETAINED, as a targetless transition requires")
          (is (= 1 (count (sim-h/available-after-transitions
                            (:definition s1) (:snapshot s1))))
              "still listed afterwards"))))))

(deftest available-after-transitions-lists-a-targetless-parallel-root-timer
  (testing "T7 — a targetless `:after` on the parallel ROOT lists at
            `:decl-path []` and fires there, moving no region"
    (let [s0   (sim-h/make-sim-state :t parallel-root-action-only-timer-definition
                                     engine-seed)
          rows (sim-h/available-after-transitions (:definition s0) (:snapshot s0))
          row  (first rows)]
      (is (= 1 (count rows)))
      (is (nil? (:target row)))
      (is (= :bump (:action row)))
      (is (= [] (:decl-path row))
          "`[]` is the parallel root — a flat or region-root `:after` is
           rejected at registration, so it can mean nothing else")
      (let [ev (sim-h/after-elapsed-event s0 row)
            s1 (step! s0 ev parallel-root-action-only-timer-definition)]
        (is (= [:rf.machine.timer/after-elapsed 5000 0 []] ev))
        (is (= 1 (count (:audit-trail s1))))
        (is (= 1 (:n (get-in s1 [:snapshot :data]))) "the action ran")
        (is (= {:work :idle :other :sleeping} (sim-h/current-sim-state s1))
            "and no region moved")))))

;; ---- (4) parse-event-vector ---------------------------------------------

(deftest parse-event-vector-accepts-keyword-and-vector-forms
  (are [text event] (= event (sim-h/parse-event-vector text))
    ":foo/bar"          [:foo/bar]
    "[:foo/bar {:x 1}]" [:foo/bar {:x 1}]
    ;; surrounding whitespace is trimmed
    "  :foo/bar  "      [:foo/bar]))

(deftest parse-event-vector-rejects-empty-and-malformed-input
  (are [text] (= {:error "empty"} (sim-h/parse-event-vector text))
    nil
    ""
    "   ")
  (are [text] (string? (:error (sim-h/parse-event-vector text)))
    ;; a head that is not a keyword
    "[\"foo\" 1]"
    ;; EDN that does not parse
    "[:foo {bad"))

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

;; ---- (6) step-sim ----------------------------------------------------------
;;
;; Stub results in the Spec 005 §Level 1 public shape
;; `re-frame.machines/machine-transition` returns — plain keys — so these
;; tests pin the `step-sim` fold alone. `fail-result` below is the engine's
;; own.

(def ^:private ok-result
  {:status :ok
   :snapshot {:state :authing :data {:counter 1}}
   :fx []
   :handled? true})

;; A HAND-WRITTEN
;; `{:status :error :error {:kind … :reason :no-matching-transition}}`
;; is a shape the engine never returns, twice over: `:no-matching-transition`
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
;; A guard-blocked or unhandled event comes back
;; `:status :ok` with the snapshot UNCHANGED and `:fx []` — the engine
;; returns the same three shapes for "stale", "guard-suppressed" and "no
;; match" (`transition.cljc`'s `apply-preselected-transition`). Folding
;; every `:ok` as a transition would invent a self-transition the framework
;; never made: a `#N :open → :open` audit row with an animated from=to
;; edge and no diagnostic at all.
;;
;; Every fixture below is the REAL engine result, so the test cannot pin
;; a shape the engine does not produce.

;; `guarded-definition` is FLAT, so the shallow seed is already the right
;; one for it. The guard-blocked test therefore exercises the `step-sim`
;; fold ALONE, with no seeder in the picture — a clean value-level red
;; rather than an arity one.

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
             (-> s1 :last-error :info :kind)))
      (is (false? (-> s1 :last-error :info :handled?))
          "the engine reports the event declined")
      (is (= "no change — the event was declined, or no transition matched it"
             (-> s1 :last-error :reason))))))

(deftest step-sim-accepted-no-op-appends-no-row-and-says-so
  (testing "an event the engine TOOK whose transition changed nothing — a
            targetless consumer, a targetless action that returns nil —
            appends no row, and the rejection says it was accepted"
    (let [d  {:initial :idle
              :data    {:n 0}
              :states  {:idle {:on {:help {}
                                    :ping {:action (fn [_] nil)}}}}}
          s0 (sim-h/make-sim-state :t d engine-seed)]
      (doseq [ev [[:help] [:ping]]
              :let [s1 (sim-h/step-sim s0 ev (engine-step d (:snapshot s0)))]]
        (is (= :idle (sim-h/current-sim-state s1)) (str ev))
        (is (= [] (:audit-trail s1)) (str ev ": no audit row"))
        (is (= :rf.xray.static.machines.sim/no-change
               (-> s1 :last-error :info :kind))
            (str ev))
        (is (true? (-> s1 :last-error :info :handled?))
            (str ev ": the engine reports the event handled"))
        (is (= "no change — the event was accepted, but its transition was a no-op"
               (-> s1 :last-error :reason))
            (str ev))))))

(deftest step-sim-from-a-compound-seed-records-a-real-transition
  (testing "engine seeding and the engine fold together: seeded at the
            engine's leaf, a declared event is a REAL transition — where a
            shallow seed would leave the sim stuck at the compound node
            recording `:auth → :auth` for ever"
    (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)
          s1 (sim-h/step-sim s0 [:submit]
                             (engine-step hierarchical-definition (:snapshot s0)))]
      (is (= [:auth :loading] (sim-h/current-sim-state s1)))
      (is (= 1 (count (:audit-trail s1))))
      (is (= {:from [:auth :form] :to [:auth :loading] :event [:submit]}
             (sim-h/last-transition s1))))))

;; ---- (7) on-chart binding helpers ---------------------------------------
;;
;; The on-chart simulator binds the topology chart to this same engine:
;; `current-sim-state` drives the active-state highlight, `last-transition`
;; drives the focused-edge animation, and `edge-click->event` coerces an
;; on-chart edge click into a step-event. Pure data → data, so pinned here
;; at the cheap JVM layer.

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

(deftest edge-click->event-coerces-only-a-keyword
  (are [event-id event] (= event (sim-h/edge-click->event event-id))
    :start      [:start]
    :auth/login [:auth/login]
    ;; an inert :after / :always auto edge carries a nil event-id, and a
    ;; non-keyword is not fireable either
    nil         nil
    "start"     nil
    42          nil))

;; ---- (8) format helpers --------------------------------------------------

(deftest format-state-display-handles-shapes
  (is (= "(none)" (sim-h/format-state-display nil)))
  (is (= ":idle"  (sim-h/format-state-display :idle)))
  (is (= "[:auth :form]" (sim-h/format-state-display [:auth :form]))))

(deftest format-destination-renders-the-target-or-the-internal-glyph
  (testing "a row with a target renders the arrow and the target. A
            targetless row has no target to show and is not handed a
            fabricated one: it reads as Spec 005's own word for the
            geometry, with the action NAMED where the definition names it,
            because the action is the whole of what such a transition does.
            An absent :target reads the same as an explicit nil; an inline
            fn action has no readable spelling, as `format-delay-key` already
            says of a fn delay key; and a row with neither target nor action
            is the forbidden-transition idiom, a deliberate event consumer"
    (are [row label] (= label (sim-h/format-destination row))
      {:target :done}                "→ :done"
      {:target [:auth :form]}        "→ [:auth :form]"
      {:target :same-state}          "→ :same-state"
      {:target nil :action :bump}    "↻ internal :bump"
      {:action :bump}                "↻ internal :bump"
      {:action (fn [_] nil)}         "↻ internal (fn)"
      {}                             "↻ internal")))

(deftest format-event-display-pr-strs
  (is (= "" (sim-h/format-event-display nil)))
  (is (= "[:foo]" (sim-h/format-event-display [:foo])))
  (is (= "[:foo {:x 1}]" (sim-h/format-event-display [:foo {:x 1}]))))
