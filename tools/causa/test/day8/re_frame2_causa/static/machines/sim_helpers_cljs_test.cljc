(ns day8.re-frame2-causa.static.machines.sim-helpers-cljs-test
  "Pure-data tests for the Static Machines Sim sub-mode helpers
  (rf2-r4nao rehost; engine originally rf2-v869p Phase 2, parent
  rf2-2tkza). Algebra is unchanged — only the ns moved.

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern every other Causa helper test uses:

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
    6. `result-*` shims          — Result destructuring without
                                   reaching into the machines artefact
    7. `step-sim`                — fold an engine Result into sim-state
    8. `format-state-display` /
       `format-event-display`    — UI-facing formatters"
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.static.machines.sim-helpers :as sim-h]))

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

;; ---- (6) Result shims ---------------------------------------------------

(def ^:private ok-result
  {:re-frame.machines.result/tag :ok
   :re-frame.machines.result/snap {:state :authing :data {:counter 1}}
   :re-frame.machines.result/fx []})

(def ^:private fail-result
  {:re-frame.machines.result/tag :fail
   :re-frame.machines.result/info {:reason :no-matching-transition}})

(deftest result-shims-discriminate
  (is (true? (sim-h/result-ok? ok-result)))
  (is (false? (sim-h/result-ok? fail-result)))
  (is (true? (sim-h/result-fail? fail-result)))
  (is (false? (sim-h/result-fail? ok-result))))

(deftest result-shims-read-slots
  (is (= {:state :authing :data {:counter 1}} (sim-h/result-snap ok-result)))
  (is (= [] (sim-h/result-fx ok-result)))
  (is (= {:reason :no-matching-transition} (sim-h/result-info fail-result))))

;; ---- (7) step-sim ----------------------------------------------------------

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
    (is (= "engine returned a non-Result value" (-> s1 :last-error :reason)))))

(deftest step-sim-audit-trail-order-newest-last
  "Each step appends — the trail is insertion-ordered so the view can
  render either direction. We pin the contract here so a downstream
  view-test can rely on insertion order."
  (let [results [{:re-frame.machines.result/tag :ok
                  :re-frame.machines.result/snap {:state :authing :data {}}
                  :re-frame.machines.result/fx []}
                 {:re-frame.machines.result/tag :ok
                  :re-frame.machines.result/snap {:state :done :data {}}
                  :re-frame.machines.result/fx []}]
        s0 (sim-h/make-sim-state :auth/login flat-definition)
        s1 (sim-h/step-sim s0 [:start] (constantly (first results)))
        s2 (sim-h/step-sim s1 [:ok]    (constantly (second results)))
        trail (:audit-trail s2)]
    (is (= 2 (count trail)))
    (is (= [:start] (-> trail first :event)))
    (is (= [:ok]    (-> trail last :event)))))

;; ---- (8) format helpers --------------------------------------------------

(deftest format-state-display-handles-shapes
  (is (= "(none)" (sim-h/format-state-display nil)))
  (is (= ":idle"  (sim-h/format-state-display :idle)))
  (is (= "[:auth :form]" (sim-h/format-state-display [:auth :form]))))

(deftest format-event-display-pr-strs
  (is (= "" (sim-h/format-event-display nil)))
  (is (= "[:foo]" (sim-h/format-event-display [:foo])))
  (is (= "[:foo {:x 1}]" (sim-h/format-event-display [:foo {:x 1}]))))
