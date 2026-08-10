(ns re-frame.hicasso.evidence-schema-cljs-test
  "THE DOOR THAT REFUSES — every shape in which an evidence projection
  would claim more than it knows, driven through
  [[re-frame.hicasso.evidence/projection]] and shown to be refused
  (rf2-hic-023).

  ## Why the whole file is negative controls

  `lanes/testing-xray.md`: *unknown is never encoded as an empty
  collection*. That is a claim about what the producer CANNOT emit, and a
  claim of that shape has exactly one honest witness — drive the forbidden
  value in and watch the door throw. A suite that only checked well-formed
  projections would pass unchanged against a door with no checks in it at
  all, which is the fail-open class this bead exists to close.

  So every row here is the sabotage, and the positive control sits beside
  it: [[a-well-formed-projection-passes-unchanged]] proves the door can go
  green, so a refusal below is the CHECK firing and not the door being
  broken for everything.

  ## The refusals assert an IDENTITY, not a throw

  `(is (thrown? …))` is worth very little here. Four of these shapes throw
  from the same door with different messages, and a test that only knows
  *something threw* would stay green if the wrong invariant fired — which
  is precisely how a sibling bead's refusal witness stayed green while the
  read threw from another layer entirely. Each row therefore asserts the
  ex-data map: the defect id, the defect kind, and — for a field defect —
  the key it is about."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.hicasso.evidence :as evidence]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private good
  "A well-formed projection: complete for a named scope, on a basis that
  can see, with nothing dropped. Every row below is this map, perturbed at
  exactly one point."
  {:scope     :mounted-boundaries
   :basis     :observation
   :complete? true
   :loss      nil
   :boundaries [{:boundary {:parent nil :key [[:app/main [:todo 7]]]}}]})

(defn- outcome
  "Run `thunk` and report which of the two things happened,
  distinguishably: `{:emitted v}` when the door let it through,
  `{:refused <ex-data>}` when it did not.

  A map with two possible keys rather than a predicate, because the two
  failure modes a refusal witness actually has are *nothing was checked*
  and *something else threw*, and both of those look like success to a
  bare `thrown?`."
  [thunk]
  (try {:emitted (thunk)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- defect-ids
  "The `{:defect :key}` pairs a refusal named — the assertable identity of
  a refusal, with the prose projected out. `:message`-bearing incoherence
  defects have no key, so they answer `{:defect :incoherent}`."
  [o]
  (mapv #(select-keys % [:defect :key]) (:defects (:refused o))))

(defn- refusal-id [o] (:re-frame.hicasso.evidence/defect (:refused o)))

;; ---------------------------------------------------------------------------
;; The positive control — the door can go green
;; ---------------------------------------------------------------------------

(deftest a-well-formed-projection-passes-unchanged
  (testing "the door returns its argument, identically, when every axis holds"
    (let [o (outcome #(evidence/projection good))]
      (is (nil? (:refused o)) (str "the control was refused: " (:message o)))
      (is (identical? good (:emitted o))
          "the door must answer the value it was given, not a rebuilt copy"))))

(deftest the-refusal-witness-answers-both-ways
  (testing "outcome reports :emitted for a pass and :refused for a throw"
    (is (contains? (outcome #(evidence/projection good)) :emitted))
    (is (contains? (outcome #(evidence/projection {})) :refused))))

;; ---------------------------------------------------------------------------
;; Field defects — the four axes are each required
;; ---------------------------------------------------------------------------

(deftest every-axis-is-required
  (testing "each of the four axes, removed one at a time, is named missing"
    (doseq [{:keys [key]} evidence/projection-fields]
      (let [o (outcome #(evidence/projection (dissoc good key)))]
        (is (= :incomplete-projection (refusal-id o))
            (str "dropping " key " must be refused as an incomplete projection"))
        (is (= [{:defect :missing :key key}] (defect-ids o))
            (str "dropping " key " must name exactly that key as missing"))))))

(deftest an-empty-scope-is-refused
  (testing "{} says nothing, so completeness relative to it is completeness about everything"
    (let [o (outcome #(evidence/projection (assoc good :scope {})))]
      (is (= [{:defect :invalid :key :scope}] (defect-ids o))))))

(deftest a-loss-without-a-dropped-count-is-refused
  (testing "an absent :dropped is the shape in which unknown looks like none"
    (let [o (outcome #(evidence/projection (assoc good
                                                  :complete? false
                                                  :loss {:reason :cap})))]
      (is (= [{:defect :invalid :key :loss}] (defect-ids o)))))
  (testing "a reason outside the closed vocabulary is refused"
    (let [o (outcome #(evidence/projection (assoc good
                                                  :complete? false
                                                  :loss {:reason :probably :dropped 3})))]
      (is (= [{:defect :invalid :key :loss}] (defect-ids o)))))
  (testing "the explicit :unknown IS a legal dropped count"
    (is (contains? (outcome #(evidence/projection
                               (assoc good :complete? false
                                      :loss {:reason :cap :dropped evidence/unknown})))
                   :emitted))))

;; ---------------------------------------------------------------------------
;; The cross-field invariants
;; ---------------------------------------------------------------------------

(deftest completeness-and-loss-cannot-both-be-claimed
  (testing "a truncated roster reported as total is the failure this schema prevents"
    (let [o (outcome #(evidence/projection
                        (assoc good :loss {:reason :cap :dropped 4})))]
      (is (= [{:defect :incoherent}] (defect-ids o))))))

(deftest a-basis-that-cannot-see-cannot-claim-completeness
  (testing "both unseeing bases are refused a completeness claim"
    (doseq [basis evidence/unseeing-bases]
      (let [o (outcome #(evidence/projection (assoc good :basis basis :boundaries evidence/unknown)))]
        (is (= [{:defect :incoherent}] (defect-ids o))
            (str basis " must not be able to claim completeness"))))))

(deftest an-unenforced-declaration-cannot-claim-completeness
  (testing "a declaration is proof only where something enforces it"
    (let [o (outcome #(evidence/projection (assoc good :basis :declaration)))]
      (is (= [{:defect :incoherent}] (defect-ids o)))))
  (testing "naming the enforcer makes the same claim legal"
    (is (contains? (outcome #(evidence/projection
                               (assoc good :basis :declaration :enforced-by :the-macro)))
                   :emitted))))

;; ---------------------------------------------------------------------------
;; THE CLAUSE THE WHOLE SCHEMA EXISTS FOR
;; ---------------------------------------------------------------------------

(deftest an-empty-roster-on-an-unseeing-basis-is-refused
  (testing "`{:basis :opaque … :boundaries []}` is unknown wearing none's clothes"
    (doseq [basis evidence/unseeing-bases]
      (let [p {:scope     :mounted-boundaries
               :basis     basis
               :complete? false
               :loss      {:reason :opaque :dropped evidence/unknown}
               :boundaries []}
            o (outcome #(evidence/projection p))]
        (is (= [{:defect :incoherent}] (defect-ids o))
            (str basis " with an empty roster must be refused"))
        (is (re-find #"unknown comes to look like none" (:message o))
            "the refusal must say WHY, in the reader's terms"))))

  (testing "the same projection stating :unknown instead is legal — that is the remedy"
    (is (contains? (outcome #(evidence/projection
                               {:scope      :mounted-boundaries
                                :basis      :opaque
                                :complete?  false
                                :loss       {:reason :opaque :dropped evidence/unknown}
                                :boundaries evidence/unknown}))
                   :emitted)))

  (testing "an empty roster on a basis that CAN see is legal — it is a survey result"
    (is (contains? (outcome #(evidence/projection (assoc good :boundaries [])))
                   :emitted)))

  (testing "the invariant reads rosters, not axes: nil loss and a map scope are not rosters"
    (is (contains? (outcome #(evidence/projection
                               {:scope      {:frame :app/main}
                                :basis      :host-opaque
                                :complete?  false
                                :loss       {:reason :host-opaque :dropped evidence/unknown}
                                :commit     evidence/unknown}))
                   :emitted))))

;; ---------------------------------------------------------------------------
;; capped — the truncation and the loss account are one expression
;; ---------------------------------------------------------------------------

(deftest capping-flips-completeness-and-records-the-drop
  (let [p (assoc good :boundaries [:a :b :c :d :e])]
    (testing "under the limit, nothing changes and the projection is still validated"
      (is (= p (evidence/capped p :boundaries 5))))
    (testing "over the limit, the roster truncates and the loss is exact"
      (let [c (evidence/capped p :boundaries 2)]
        (is (= [:a :b] (:boundaries c)))
        (is (false? (:complete? c)))
        (is (= {:reason :cap :dropped 3} (:loss c)))))))

;; ---------------------------------------------------------------------------
;; The envelope's own three axes
;; ---------------------------------------------------------------------------

(def ^:private good-envelope
  (merge good {:schema   evidence/schema
               :producer evidence/producer
               :read     :mounted-boundaries}))

(deftest an-envelope-names-schema-producer-and-read
  (testing "the control passes"
    (is (contains? (outcome #(evidence/envelope good-envelope)) :emitted)))
  (testing "each identity axis, removed one at a time, is named missing"
    (doseq [{:keys [key]} evidence/envelope-fields]
      (let [o (outcome #(evidence/envelope (dissoc good-envelope key)))]
        (is (= :incomplete-envelope (refusal-id o)))
        (is (= [{:defect :missing :key key}] (defect-ids o))))))
  (testing "a foreign schema, producer or read is refused rather than parsed"
    (doseq [[key v] [[:schema :re-frame.hicasso.evidence/v2]
                     [:producer :re-frame/somebody-else]
                     [:read :whatever]]]
      (let [o (outcome #(evidence/envelope (assoc good-envelope key v)))]
        (is (= [{:defect :invalid :key key}] (defect-ids o))))))
  (testing "an envelope is ALSO a projection — the four axes are checked too"
    (let [o (outcome #(evidence/envelope (dissoc good-envelope :loss)))]
      (is (= :incomplete-projection (refusal-id o)))
      (is (= [{:defect :missing :key :loss}] (defect-ids o))))))

;; ---------------------------------------------------------------------------
;; The vocabularies are closed, and the bead's five states are all present
;; ---------------------------------------------------------------------------

(deftest the-five-loss-states-are-all-expressible
  (testing "unknown / opaque / host-opaque / cap / uncorrelated, in the two vocabularies"
    (is (= :unknown evidence/unknown))
    (is (= #{:cap :opaque :host-opaque :uncorrelated} (set (keys evidence/loss-reasons))))
    (is (= #{:opaque :host-opaque} evidence/unseeing-bases))
    (is (every? #(contains? evidence/basis-kinds %) evidence/unseeing-bases)
        "an unseeing basis must also BE a basis"))
  (testing "no :static-proof — this producer interprets and can never prove statically"
    (is (not (contains? evidence/basis-kinds :static-proof))))
  (testing "the four reads are the four views"
    (is (= #{:mounted-boundaries :read-attribution :intents :explain-render}
           (set (keys evidence/reads))))))

(deftest retention-names-spec-009-and-no-second-knob
  (is (= {:owner        :spec-009
          :mechanism    :per-frame-retained-event-ring
          :knob         :rf.trace/events-retained
          :configure-at [:trace-buffer :events-retained]}
         evidence/retention)))
