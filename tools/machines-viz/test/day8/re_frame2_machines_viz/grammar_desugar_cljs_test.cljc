(ns day8.re-frame2-machines-viz.grammar-desugar-cljs-test
  "EP-0029 grammar-desugar + ISO-8601 duration coverage for the shared
  emitter ingestion boundary (rf2-7j6gtc).

  `grammar.cljc` re-states, bundle-isolated from the runtime `machines`
  artefact, the EP-0029 named-intent desugars every emitter (chart /
  mermaid / SCXML) MUST lower before walking a machine-def:

    - `:timeout` / `:on-timeout` → `:after` (A4), via `desugar-timeouts`,
      whose duration resolver `resolve-timeout-ms` mirrors
      `re-frame.machines.timeout/resolve-duration-ms` (positive-integer ms
      OR ISO-8601 STRING only — the XState `\"5s\"` shorthand is REJECTED);
    - `:type :choice` / `:choice` → `:always` (A5), via `desugar-choices`;
    - `desugar-grammar` = the single seam applying both, in the order the
      engine applies them (timeouts then choices).

  `chart.layout/project-definition` calls `g/desugar-grammar` as the
  shared ingestion boundary for ALL THREE emitters, so a regression here
  silently omits / mis-renders every `:timeout` and `:choice` form across
  every surface. These are the EXACT-SHAPE unit assertions that pin the
  desugar output; the drift-vs-engine parity pair lives in
  `engine-grammar-parity-test`.

  Pure `.cljc` → the JVM corpus + the `cljs-test$` node-test build both
  pin it (no engine dep — these are isolation unit tests)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.grammar :as g]))

;; ===========================================================================
;; resolve-timeout-ms — the ISO-8601 / integer-ms duration resolver (A4)
;; ===========================================================================
;;
;; Hand-copied non-trivial regex + Y/M/W/D/H/Min/Sec arithmetic + a
;; Math/round on fractional seconds. These pin the EXACT ms each shape
;; resolves to (so a drift in the arithmetic — a dropped component, a wrong
;; ms-per constant, a rounding bug — is caught here regardless of the
;; engine), plus the reject-to-nil contract on every non-duration.

(deftest resolve-timeout-ms-positive-integer-is-literal-ms
  (testing "a positive integer literal resolves to itself (ms)"
    (is (= 5000 (g/resolve-timeout-ms 5000)))
    (is (= 1    (g/resolve-timeout-ms 1)))))

(deftest resolve-timeout-ms-iso-8601-components
  (testing "each ISO-8601 component resolves to the right ms with the
            fixed 365-day / 30-day year/month convention"
    (is (= 5000     (g/resolve-timeout-ms "PT5S"))    "seconds")
    (is (= 120000   (g/resolve-timeout-ms "PT2M"))    "minutes")
    (is (= 3600000  (g/resolve-timeout-ms "PT1H"))    "hours")
    (is (= 5400000  (g/resolve-timeout-ms "PT1H30M")) "hours + minutes")
    (is (= 86400000 (g/resolve-timeout-ms "P1D"))     "days")
    (is (= 604800000     (g/resolve-timeout-ms "P1W")) "weeks = 7 days")
    (is (= (* 30 86400000) (g/resolve-timeout-ms "P1M")) "month = 30 days")
    (is (= (* 365 86400000) (g/resolve-timeout-ms "P1Y")) "year = 365 days")
    (is (= 90061000  (g/resolve-timeout-ms "P1DT1H1M1S"))
        "combined date+time components sum")))

(deftest resolve-timeout-ms-fractional-seconds-round-to-nearest-ms
  (testing "fractional seconds are rounded to the nearest integer ms"
    (is (= 500  (g/resolve-timeout-ms "PT0.5S")))
    (is (= 1500 (g/resolve-timeout-ms "PT1.5S")))
    (is (= 250  (g/resolve-timeout-ms "PT0.25S")))
    (is (= 1    (g/resolve-timeout-ms "PT0.001S")) "1ms floor via round")))

(deftest resolve-timeout-ms-is-case-insensitive
  (testing "the ISO grammar is case-insensitive (the (?i) flag)"
    (is (= 5000 (g/resolve-timeout-ms "pt5s")))
    (is (= 86400000 (g/resolve-timeout-ms "p1d")))))

(deftest resolve-timeout-ms-invalid-durations-are-nil
  (testing "every non-duration resolves to nil (the caller fails loud)"
    ;; non-positive / non-integer numbers
    (is (nil? (g/resolve-timeout-ms 0))    "zero is meaningless")
    (is (nil? (g/resolve-timeout-ms -5))   "negative is invalid")
    (is (nil? (g/resolve-timeout-ms 1.5))  "a non-integer number is not a literal ms")
    ;; XState shorthand — explicitly rejected (EP-0029 A4 divergence)
    (is (nil? (g/resolve-timeout-ms "5s"))  "XState \"5s\" shorthand rejected")
    (is (nil? (g/resolve-timeout-ms "10ms")) "XState \"10ms\" shorthand rejected")
    (is (nil? (g/resolve-timeout-ms "2m")))
    ;; degenerate / malformed ISO
    (is (nil? (g/resolve-timeout-ms "P"))   "bare P has no component")
    (is (nil? (g/resolve-timeout-ms "PT"))  "bare PT has no component")
    (is (nil? (g/resolve-timeout-ms "PT0S")) "a zero-total duration is non-positive")
    (is (nil? (g/resolve-timeout-ms "P0D"))  "P0D resolves to 0ms → nil")
    (is (nil? (g/resolve-timeout-ms "soon")))
    (is (nil? (g/resolve-timeout-ms ""))    "empty string")
    ;; wrong TYPES entirely
    (is (nil? (g/resolve-timeout-ms nil)))
    (is (nil? (g/resolve-timeout-ms [1000])) "a vector is not a duration")
    (is (nil? (g/resolve-timeout-ms :pt5s))  "a keyword is not a duration")))

;; ===========================================================================
;; desugar-timeouts — :timeout / :on-timeout → :after (A4)
;; ===========================================================================

(deftest desugar-timeouts-state-level-lowers-to-after
  (testing "a state-level :timeout / :on-timeout becomes an :after entry
            keyed by the resolved ms, dropping both timeout keys, and
            coexisting with the state's other slots"
    (let [in  {:initial :a
               :states  {:a {:timeout 1000 :on-timeout :b :on {:x :c}}
                         :b {} :c {}}}
          out (g/desugar-timeouts in)
          a   (get-in out [:states :a])]
      (is (= {:on {:x :c} :after {1000 :b}} a)
          "the state's :after carries {resolved-ms on-timeout}")
      (is (not (contains? a :timeout))    "no :timeout key survives")
      (is (not (contains? a :on-timeout)) "no :on-timeout key survives"))))

(deftest desugar-timeouts-resolves-iso-duration-key
  (testing "an ISO-8601 :timeout resolves its ms as the :after delay-key"
    (let [out (g/desugar-timeouts
                {:initial :a :states {:a {:timeout "PT2S" :on-timeout :done} :done {}}})]
      (is (= {:after {2000 :done}} (get-in out [:states :a]))))))

(deftest desugar-timeouts-merges-into-existing-after-existing-wins
  (testing "the synthetic entry MERGES into an existing :after; on a
            delay-key collision the EXPLICIT :after entry wins (it was
            authored at that delay)"
    (let [out (g/desugar-timeouts
                {:initial :a
                 :states  {:a {:after {2000 :explicit}
                               :timeout "PT2S" :on-timeout :from-timeout}}})]
      ;; 2000ms collides: the explicit :after target survives.
      (is (= {:after {2000 :explicit}} (get-in out [:states :a]))
          "explicit :after entry wins the merge on a colliding key"))))

(deftest desugar-timeouts-spawn-level-anchors-on-state-after
  (testing "a :spawn-level :timeout / :on-timeout desugars onto the STATE's
            :after (anchored to the state's entry) and strips the spawn's
            timeout keys, leaving the rest of the spawn spec intact"
    (let [out (g/desugar-timeouts
                {:initial :a
                 :states  {:a {:spawn {:machine :child :timeout 500 :on-timeout :fallback}
                               :on    {:x :b}}
                           :b {}}})
          a   (get-in out [:states :a])]
      (is (= {:machine :child} (:spawn a)) "spawn spec keeps :machine, loses timeout keys")
      (is (= {500 :fallback} (:after a))   "spawn timeout anchors on the state :after")
      (is (= {:x :b} (:on a))              "the state's own :on is untouched"))))

(deftest desugar-timeouts-root-level-anchors-on-root-after
  (testing "a root-level (whole-machine) :timeout desugars onto the root
            :after, dropping the root timeout keys, leaving :states intact"
    (let [out (g/desugar-timeouts
                {:initial :a :timeout 3000 :on-timeout :expired
                 :states  {:a {}}})]
      (is (= {3000 :expired} (:after out)) "root :after carries the whole-machine deadline")
      (is (not (contains? out :timeout)))
      (is (not (contains? out :on-timeout)))
      (is (= {:a {}} (:states out)) ":states unchanged"))))

(deftest desugar-timeouts-walks-nested-compound-states
  (testing "the walk descends nested :states so a deep state's :timeout
            still lowers"
    (let [out (g/desugar-timeouts
                {:initial :outer
                 :states  {:outer {:initial :inner
                                   :states  {:inner {:timeout 1000 :on-timeout :done}
                                             :done  {}}}}})]
      (is (= {:after {1000 :done}}
             (get-in out [:states :outer :states :inner]))
          "the deep :inner state's timeout lowered onto its :after"))))

(deftest desugar-timeouts-walks-parallel-regions
  (testing "the walk descends :regions so a region-substate's :timeout lowers"
    (let [out (g/desugar-timeouts
                {:type    :parallel
                 :regions {:r1 {:initial :x
                                :states  {:x {:timeout 1000 :on-timeout :y}
                                          :y {}}}}})]
      (is (= {:after {1000 :y}}
             (get-in out [:regions :r1 :states :x]))
          "the region substate's timeout lowered onto its :after"))))

(deftest desugar-timeouts-timeout-free-machine-unchanged
  (testing "a machine with no :timeout anywhere is returned value-equal
            (no spurious keys introduced)"
    (let [m {:initial :a :states {:a {:on {:x :b}} :b {:after {500 :a}}}}]
      (is (= m (g/desugar-timeouts m))))))

;; ===========================================================================
;; desugar-choices — :type :choice / :choice → :always (A5)
;; ===========================================================================

(deftest desugar-choices-lowers-choice-to-always
  (testing "a :type :choice transient state becomes an ordinary state
            carrying its :choice candidate vector under :always, dropping
            the :type / :choice keys"
    (let [cands [{:guard :g1 :target :a} {:target :b}]
          out   (g/desugar-choices
                  {:initial :gate
                   :states  {:gate {:type :choice :choice cands}
                             :a {} :b {}}})
          gate  (get-in out [:states :gate])]
      (is (= {:always cands} gate) "candidate vector moves under :always")
      (is (not (contains? gate :type))   "no :type key survives")
      (is (not (contains? gate :choice)) "no :choice key survives"))))

(deftest desugar-choices-walks-nested-and-regions
  (testing "the walk lowers a choice state nested in a compound AND one
            inside a parallel region"
    (let [cands [{:target :a}]
          out   (g/desugar-choices
                  {:initial :outer
                   :states  {:outer {:initial :gate
                                     :states  {:gate {:type :choice :choice cands}
                                               :a    {}}}}
                   :regions {:r1 {:initial :rgate
                                  :states  {:rgate {:type :choice :choice cands}
                                            :a     {}}}}})]
      (is (= {:always cands} (get-in out [:states :outer :states :gate]))
          "nested compound choice lowered")
      (is (= {:always cands} (get-in out [:regions :r1 :states :rgate]))
          "region-nested choice lowered"))))

(deftest desugar-choices-choice-free-machine-unchanged
  (testing "a machine with no :type :choice anywhere is returned value-equal"
    (let [m {:initial :a :states {:a {:always [{:target :b}]} :b {}}}]
      (is (= m (g/desugar-choices m))))))

;; ===========================================================================
;; desugar-grammar — the combined ingestion seam (A4 then A5)
;; ===========================================================================

(deftest desugar-grammar-applies-both-desugars
  (testing "the shared seam lowers BOTH a :timeout state AND a :choice
            state in one pass (the exact ingestion boundary all three
            emitters share)"
    (let [out (g/desugar-grammar
                {:initial :a
                 :states  {:a    {:timeout 1000 :on-timeout :b}
                           :gate {:type :choice :choice [{:target :a}]}
                           :b    {}}})]
      (is (= {:after {1000 :b}} (get-in out [:states :a]))
          ":timeout lowered to :after")
      (is (= {:always [{:target :a}]} (get-in out [:states :gate]))
          ":choice lowered to :always")
      (is (= {} (get-in out [:states :b]))))))

(deftest desugar-grammar-is-idempotent
  (testing "re-running the desugar on already-lowered output is a no-op"
    (let [m   {:initial :a
               :states  {:a    {:timeout "PT1S" :on-timeout :b}
                         :gate {:type :choice :choice [{:target :a}]}
                         :b    {}}}
          one (g/desugar-grammar m)]
      (is (= one (g/desugar-grammar one)) "second pass changes nothing"))))

(deftest desugar-grammar-nil-safe
  (testing "every desugar is nil-safe / non-map-safe (a non-map is returned
            unchanged — an emitter can call the seam unconditionally)"
    (is (nil? (g/desugar-grammar nil)))
    (is (nil? (g/desugar-timeouts nil)))
    (is (nil? (g/desugar-choices nil)))
    (is (= :not-a-machine (g/desugar-grammar :not-a-machine)))
    (is (= 42 (g/desugar-timeouts 42)))))
