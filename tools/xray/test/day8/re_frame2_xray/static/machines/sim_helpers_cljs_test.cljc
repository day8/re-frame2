(ns day8.re-frame2-xray.static.machines.sim-helpers-cljs-test
  "Pure-data tests for the Static Machines Sim sub-mode helpers.

  `.cljc` with a `cljs-test` ns name so both runners load it: the JVM
  test-runner by its `-test$` regex and Shadow's `:node-test` by
  `cljs-test$`."
  (:require #?(:clj  [clojure.test :refer [are deftest is]]
               :cljs [cljs.test    :refer-macros [are deftest is]])
            [re-frame.machines :as rf.machines]
            [re-frame.machines.parallel :as rf.machines.parallel]
            [day8.re-frame2-xray.static.machines.sim-helpers :as sim-h]))

;; Every engine result and seed below comes from the machines artefact
;; itself, never written by hand: a hand-written shape can be one the
;; engine never returns.

(defn- engine-seed
  "The seeder `sim.cljs` hands `make-sim-state` in production."
  [definition]
  (rf.machines.parallel/build-initial-snapshot definition {:bootstrap-pending? false}))

(defn- step!
  "One `step-sim` fold through the real engine against `sim-state`'s own
  current snapshot — the call `sim.cljs` makes in production."
  [sim-state event definition]
  (sim-h/step-sim sim-state event
                  #(rf.machines/machine-transition definition (:snapshot sim-state) %)))

;; ---- fixtures ------------------------------------------------------------

(def ^:private flat-definition
  {:initial :idle
   :data    {:counter 0}
   :states  {:idle    {:on {:start :authing
                            :reset :idle}}
             :authing {:on {:ok  {:target :done}
                            :err {:target :failed :guard :can-retry?}}}
             :done    {:final? true}
             :failed  {:final? true :on {:retry :idle}}}})

(def ^:private hierarchical-definition
  {:initial :auth
   :states  {:auth {:initial :form
                    :states  {:form   {:on {:submit :loading}}
                              :loading {:on {:ok :done}}}}
             :done {:final? true}}})

;; ---- initial-snapshot -----------------------------------------------------

(deftest initial-snapshot-shallow-read-seeds-state-and-data-or-nil
  ;; Rows: a flat definition; a missing :data slot, which defaults to {};
  ;; a definition with no :initial (a parallel root), which reads nil.
  (are [definition snap] (= snap (sim-h/initial-snapshot definition))
    flat-definition               {:state :idle :data {:counter 0}}
    {:initial :a :states {:a {}}} {:state :a :data {}}
    {}                            nil))

(deftest reset-sim-state-restores-the-seed-it-opened-with
  ;; Reset rewinds to the snapshot the slot was SEEDED with — a compound
  ;; root's engine leaf, not the shallow read — and clears trail and error.
  (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)]
    (is (= s0 (sim-h/reset-sim-state
                (-> s0
                    (assoc :snapshot {:state [:auth :loading] :data {}})
                    (sim-h/append-audit-row {:from [:auth :form] :to [:auth :loading]})
                    (sim-h/record-error [:bad] {} "something went wrong")))))))

;; ---- event-id-suggestions -------------------------------------------------

(deftest event-id-suggestions-aggregates-the-on-keys-of-every-state
  ;; Rows: every distinct :on key, sorted by string; the nested :states of a
  ;; compound definition are walked; a nil definition answers [].
  (are [definition ids] (= ids (sim-h/event-id-suggestions definition))
    flat-definition         [:err :ok :reset :retry :start]
    hierarchical-definition [:ok :submit]
    nil                     []))

;; ---- available-transitions ------------------------------------------------

(deftest available-transitions-lists-outgoing-from-current-leaf
  ;; Rows: a keyword state, its guarded row tagged; a hierarchical path,
  ;; which lists its LEAF's rows stamped with the leaf's own path.
  (are [definition state rows]
       (= rows (set (sim-h/available-transitions definition {:state state})))
    flat-definition :authing
    #{{:event :ok  :target :done   :action nil :guard? false :guard nil         :decl-path [:authing]}
      {:event :err :target :failed :action nil :guard? true  :guard :can-retry? :decl-path [:authing]}}

    hierarchical-definition [:auth :form]
    #{{:event :submit :target :loading :action nil :guard? false :guard nil :decl-path [:auth :form]}}))

(deftest available-transitions-lists-nothing-for-a-final-state-a-root-on-or-no-snapshot
  (are [definition snapshot] (= [] (sim-h/available-transitions definition snapshot))
    ;; :done is final and declares no :on
    flat-definition {:state :done :data {}}
    ;; a machine-root :on is parent inheritance, which the leaf-only picker
    ;; does not list
    {:initial :idle :on {:abort :stopped} :states {:idle {} :stopped {}}} {:state :idle}
    flat-definition nil))

(def ^:private parallel-on-definition
  "A `:type :parallel` root whose regions both declare `:on`, plus a root
  `:on` fallback the leaf-only picker does not list."
  {:type    :parallel
   :data    {}
   :on      {:abort [:net :timeout]}
   :regions {:form {:initial :editing
                    :states  {:editing   {:on {:submit :submitted}}
                              :submitted {}}}
             :net  {:initial :idle
                    :states  {:idle    {:on {:go :busy}}
                              :busy    {}
                              :timeout {}}}}})

(deftest available-transitions-lists-every-parallel-region-leaf
  ;; Each region's own leaf lists, its decl-path region-prefixed as the engine
  ;; prefixes its own fx; the root :abort fallback does not.
  (is (= #{{:event :submit :target :submitted :action nil :guard? false :guard nil :decl-path [:form :editing]}
           {:event :go     :target :busy      :action nil :guard? false :guard nil :decl-path [:net :idle]}}
         (set (sim-h/available-transitions parallel-on-definition
                                           (engine-seed parallel-on-definition))))))

(deftest available-transitions-lists-every-candidate-of-a-mixed-vector
  ;; One event declaring a vector of candidates lists a row EACH, the
  ;; targetless action-only one included with a nil :target and its action
  ;; named. Neither the event id nor the decl-path tells the two apart, which
  ;; is why the rail's row key carries the index too.
  (is (= [{:event :go :target :done :action nil   :guard? true  :guard :never :decl-path [:idle]}
          {:event :go :target nil   :action :bump :guard? false :guard nil    :decl-path [:idle]}]
         (sim-h/available-transitions
           {:initial :idle
            :states  {:idle {:on {:go [{:target :done :guard :never}
                                       {:action :bump}]}}
                      :done {}}}
           {:state :idle}))))

;; ---- `:after` timer rows --------------------------------------------------
;;
;; Firing a timer row sends the engine's own synthetic
;; `[:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]`, its
;; epoch read back off the `:rf.machine/after-schedule` fx `step-sim` stores
;; on each audit row. The engine declines a stale epoch, so a timer step that
;; MOVES proves the row read the live one.

(def ^:private bump-action
  {:bump (fn [{:keys [data]}] {:data (update data :n inc)})})

(def ^:private timer-definition
  "An `:after` on a NON-initial state, which `:back` leaves and `:start`
  re-enters."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:after {5000 :timeout} :on {:back :idle}}
             :timeout {}}})

(def ^:private ancestor-timer-definition
  {:initial :auth
   :states  {:auth    {:initial :form
                       :after   {9000 :expired}
                       :states  {:form {}}}
             :expired {}}})

(def ^:private declined-timer-definition
  {:initial :loading
   :guards  {:never (fn [_] false)}
   :states  {:loading {:after {5000 {:target :timeout :guard :never}}}
             :timeout {}}})

(def ^:private parallel-timer-definition
  {:type    :parallel
   :data    {}
   :regions {:net  {:initial :idle
                    :states  {:idle    {:on {:go :busy}}
                              :busy    {:after {3000 :timeout}}
                              :timeout {}}}
             :form {:initial :editing
                    :states  {:editing {}}}}})

(def ^:private action-only-timer-definition
  {:initial :loading
   :data    {:n 0}
   :actions bump-action
   :states  {:loading {:after {5000 {:action :bump}}}}})

(def ^:private parallel-root-action-only-timer-definition
  {:type    :parallel
   :data    {:n 0}
   :actions bump-action
   :after   {5000 {:action :bump}}
   :regions {:work  {:initial :idle :states {:idle {}}}
             :other {:initial :sleeping :states {:sleeping {}}}}})

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

(deftest available-after-transitions-lists-the-timers-on-the-active-path
  ;; Rows: a compound ANCESTOR's timer while its leaf is active, which a
  ;; leaf-only lookup would miss; a guarded timer, tagged; a timer declared
  ;; off the active path, not listed; and no snapshot.
  (are [definition snapshot rows] (= rows (sim-h/available-after-transitions definition snapshot))
    ancestor-timer-definition (engine-seed ancestor-timer-definition)
    [{:delay-key 9000 :target :expired :action nil :guard? false :guard nil :decl-path [:auth]}]

    declined-timer-definition (engine-seed declined-timer-definition)
    [{:delay-key 5000 :target :timeout :action nil :guard? true :guard :never :decl-path [:loading]}]

    timer-definition {:state :idle}
    []

    timer-definition nil
    []))

(deftest available-after-transitions-region-prefixes-a-parallel-timer
  ;; The row carries the engine's own region-prefixed decl-path, and firing
  ;; it moves that region only.
  (let [s1   (step! (sim-h/make-sim-state :t parallel-timer-definition engine-seed)
                    [:go] parallel-timer-definition)
        rows (sim-h/available-after-transitions (:definition s1) (:snapshot s1))]
    (is (= [{:delay-key 3000 :target :timeout :action nil :guard? false :guard nil :decl-path [:net :busy]}]
           rows))
    (is (= {:net :timeout :form :editing}
           (sim-h/current-sim-state
             (step! s1 (sim-h/after-elapsed-event s1 (first rows)) parallel-timer-definition))))))

(deftest after-elapsed-event-takes-the-newest-epoch-on-re-entry
  (let [s3  (reduce #(step! %1 %2 timer-definition)
                    (sim-h/make-sim-state :t timer-definition engine-seed)
                    [[:start] [:back] [:start]])
        row (first (sim-h/available-after-transitions (:definition s3) (:snapshot s3)))]
    (is (apply not= (stored-epochs s3 [:loading] 5000))
        "the two entries stored different epochs, so newest-vs-oldest is visible")
    (is (= :timeout (sim-h/current-sim-state
                      (step! s3 (sim-h/after-elapsed-event s3 row) timer-definition))))))

(deftest available-after-transitions-lists-a-targetless-action-only-timer
  ;; A targetless timer lists with a nil :target, fires at epoch 0 — the
  ;; engine's own reading of a node no step has entered — and its step is
  ;; recorded although the state stands still.
  (let [s0   (sim-h/make-sim-state :t action-only-timer-definition engine-seed)
        rows (sim-h/available-after-transitions (:definition s0) (:snapshot s0))
        ev   (sim-h/after-elapsed-event s0 (first rows))]
    (is (= [{:delay-key 5000 :target nil :action :bump :guard? false :guard nil :decl-path [:loading]}]
           rows))
    (is (= [:rf.machine.timer/after-elapsed 5000 0 [:loading]] ev))
    (is (= [{:n 1}] (map :data (:audit-trail (step! s0 ev action-only-timer-definition)))))))

(deftest available-after-transitions-lists-a-targetless-parallel-root-timer
  ;; A parallel ROOT's own :after lists at decl-path [] — a flat or
  ;; region-root :after is rejected at registration, so [] means the root —
  ;; and fires there.
  (let [d    parallel-root-action-only-timer-definition
        s0   (sim-h/make-sim-state :t d engine-seed)
        rows (sim-h/available-after-transitions (:definition s0) (:snapshot s0))]
    (is (= [{:delay-key 5000 :target nil :action :bump :guard? false :guard nil :decl-path []}]
           rows))
    (is (= [{:n 1}]
           (map :data (:audit-trail (step! s0 (sim-h/after-elapsed-event s0 (first rows)) d)))))))

;; ---- parse-event-vector ---------------------------------------------------

(deftest parse-event-vector-accepts-keyword-and-vector-forms
  (are [text event] (= event (sim-h/parse-event-vector text))
    ":foo/bar"          [:foo/bar]
    "[:foo/bar {:x 1}]" [:foo/bar {:x 1}]))

(deftest parse-event-vector-rejects-empty-and-malformed-input
  (is (= {:error "empty"} (sim-h/parse-event-vector "   ")))
  (are [text] (string? (:error (sim-h/parse-event-vector text)))
    ;; a head that is not a keyword
    "[\"foo\" 1]"
    ;; EDN that does not parse
    "[:foo {bad"))

;; ---- step-sim -------------------------------------------------------------

(def ^:private ok-result
  "A stub in the Spec 005 §Level 1 shape; `machines-shape-parity-cljs-test`
  pins its keys to the engine's."
  {:status :ok
   :snapshot {:state :authing :data {:counter 1}}
   :fx []
   :handled? true})

(def ^:private throwing-definition
  {:initial :a
   :data    {}
   :actions {:boom (fn [_] (throw (ex-info "boom" {:why :fixture})))}
   :states  {:a {:on {:go {:target :b :action :boom}}}
             :b {}}})

(def ^:private fail-result
  "An actual engine `:status :error` — a failed macrostep, here an action
  that throws. An unmatched event is `:status :ok`, not an error."
  (rf.machines/machine-transition throwing-definition
                                  (engine-seed throwing-definition)
                                  [:go]))

(deftest step-sim-fail-leaves-snapshot-and-stamps-error
  ;; The next OK step clears the stamped error.
  (let [s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/step-sim s0 [:bad] (constantly fail-result))]
    (is (= (assoc s0 :last-error {:event  [:bad]
                                  :info   (:error fail-result)
                                  :reason "transition failed"})
           s1))
    (is (nil? (:last-error (sim-h/step-sim s1 [:start] (constantly ok-result)))))))

(def ^:private guarded-definition
  {:initial :locked
   :guards  {:never (fn [_] false)}
   :states  {:locked   {:on {:open {:target :unlocked :guard :never}}}
             :unlocked {}}})

(deftest step-sim-appends-no-row-for-a-step-that-moved-nothing
  ;; The engine answers :ok with the snapshot unchanged both for a declined
  ;; or unmatched event and for an accepted no-op; folding either as a
  ;; transition would invent a from=to audit row. :handled? tells them apart.
  (are [definition event handled? reason]
       (let [s0 (sim-h/make-sim-state :m definition engine-seed)]
         (= (assoc s0 :last-error {:event  event
                                   :info   {:kind     :rf.xray.static.machines.sim/no-change
                                            :handled? handled?}
                                   :reason reason})
            (step! s0 event definition)))
    guarded-definition [:open] false
    "no change — the event was declined, or no transition matched it"

    ;; a targetless, actionless consumer — Spec 005's forbidden-transition idiom
    {:initial :idle :states {:idle {:on {:help {}}}}} [:help] true
    "no change — the event was accepted, but its transition was a no-op"))

(deftest step-sim-from-a-compound-seed-records-a-real-transition
  ;; Seeded at the engine's leaf, a declared event is a real transition; a
  ;; shallow seed would park the sim on the compound node.
  (let [s0 (sim-h/make-sim-state :auth/login hierarchical-definition engine-seed)
        s1 (step! s0 [:submit] hierarchical-definition)]
    (is (= [:auth :loading] (sim-h/current-sim-state s1)))
    (is (= {:from [:auth :form] :to [:auth :loading] :event [:submit]}
           (sim-h/last-transition s1)))))

;; ---- on-chart binding helpers ---------------------------------------------

(deftest last-transition-projects-most-recent-audit-row
  (is (= {:from :authing :to :done :event [:ok]}
         (sim-h/last-transition
           (reduce #(step! %1 %2 flat-definition)
                   (sim-h/make-sim-state :auth/login flat-definition)
                   [[:start] [:ok]])))))

(deftest edge-click->event-coerces-only-a-keyword
  ;; An inert :after / :always auto edge carries a nil event-id.
  (are [event-id event] (= event (sim-h/edge-click->event event-id))
    :start [:start]
    nil    nil))

;; ---- format helpers -------------------------------------------------------

(deftest format-state-display-handles-shapes
  (is (= "(none)" (sim-h/format-state-display nil)))
  (is (= ":idle"  (sim-h/format-state-display :idle)))
  (is (= "[:auth :form]" (sim-h/format-state-display [:auth :form]))))

(deftest format-destination-renders-the-target-or-the-internal-glyph
  ;; A targetless row is not handed a fabricated target: it reads as Spec
  ;; 005's own word for the geometry, naming the action where it can.
  (are [row label] (= label (sim-h/format-destination row))
    {:target :done}         "→ :done"
    {:target [:auth :form]} "→ [:auth :form]"
    {:action :bump}         "↻ internal :bump"
    {:action (fn [_] nil)}  "↻ internal (fn)"
    {}                      "↻ internal"))
