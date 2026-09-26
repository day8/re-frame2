(ns re-frame.fresco.evidence-schema-cljs-test
  "THE ENVELOPE DOOR — `re-frame.fresco.evidence/envelope` stamps a
  coherent read and refuses every shape in which a read would claim more
  than it knows.

  Each refusal asserts the problem the door NAMED, not merely that it
  threw: four shapes throw from one door, and a witness that only knew
  *something threw* would stay green if the wrong check fired. The
  positive control beside them proves the door can go green, so a refusal
  is the check firing and not the door being broken for everything."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.fresco.evidence :as rf.fresco.evidence]))

(defn- outcome
  "`{:emitted v}` when the door let it through, `{:refused <ex-data>
  :message …}` when it did not — distinguishable, because *nothing was
  checked* and *something else threw* both look like success to a bare
  `thrown?`."
  [thunk]
  (try {:emitted (thunk)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- refused-with
  "True when `o` was refused by THIS door and one of its named problems
  contains `fragment`."
  [o fragment]
  (and (= :incoherent-envelope (:re-frame.fresco.evidence/defect (:refused o)))
       (some #(str/includes? % fragment) (:problems (:refused o)))))

;; ---------------------------------------------------------------------------
;; The positive controls — the door can go green
;; ---------------------------------------------------------------------------

(deftest a-coherent-envelope-is-stamped-over-its-body
  (let [e (rf.fresco.evidence/envelope :mounted-boundaries true nil {:boundaries [] :generation 3})]
    (is (= rf.fresco.evidence/schema (:schema e)) "the version pin rides on every envelope")
    (is (= rf.fresco.evidence/producer (:producer e)))
    (is (= :mounted-boundaries (:read e)))
    (is (true? (:complete? e)))
    (is (nil? (:loss e)))
    (is (= [] (:boundaries e)) "the body survives the stamp")
    (is (= 3 (:generation e)))))

(deftest a-capped-envelope-carries-its-loss
  (let [e (rf.fresco.evidence/envelope :intents false {:reason :cap :dropped rf.fresco.evidence/unknown} {:intents []})]
    (is (false? (:complete? e)))
    (is (= {:reason :cap :dropped rf.fresco.evidence/unknown} (:loss e)))
    (testing "and a counted drop is legal too"
      (is (contains? (outcome #(rf.fresco.evidence/envelope :intents false {:reason :cap :dropped 4} {}))
                     :emitted)))))

;; ---------------------------------------------------------------------------
;; The refusals — each names its problem
;; ---------------------------------------------------------------------------

(deftest each-incoherent-shape-is-refused-naming-its-problem
  ;; One row per shape: the envelope's `read`, `complete?` and `loss`
  ;; arguments, and the fragment the door's named problem must carry.
  (doseq [[shape [read complete? loss] fragment]
          [["a read outside the vocabulary"
            [:whatever true nil] ":read :whatever"]
           ["a loss with an absent :dropped — the shape in which unknown looks like none"
            [:intents false {:reason :cap}] "absent :dropped"]
           ["a loss reason outside the closed vocabulary"
            [:intents false {:reason :probably :dropped 3}] ":loss"]
           ["a loss that is not a map"
            [:intents false :cap] ":loss"]
           ["completeness claimed beside a reported loss"
            [:mounted-boundaries true {:reason :cap :dropped 4}] "claims completeness and also reports loss"]
           ["a nil completeness"
            [:mounted-boundaries nil nil] ":complete?"]
           ["a non-boolean completeness"
            [:mounted-boundaries :yes nil] ":complete?"]]]
    (let [o (outcome #(rf.fresco.evidence/envelope read complete? loss {}))]
      (is (refused-with o fragment) (str shape " — " (:message o))))))

(deftest a-refusal-names-every-problem-at-once
  (let [o (outcome #(rf.fresco.evidence/envelope :nope true {:reason :cap} {}))]
    (is (= 3 (count (:problems (:refused o))))
        "the read, the loss's missing :dropped and the completeness clash are all named")))

;; ---------------------------------------------------------------------------
;; The vocabulary is closed
;; ---------------------------------------------------------------------------

(deftest the-vocabulary-is-closed
  (is (= :unknown rf.fresco.evidence/unknown))
  (is (= #{:cap :opaque :host-opaque :uncorrelated} rf.fresco.evidence/loss-reasons))
  (is (= #{:mounted-boundaries :read-attribution :intents :explain-render} rf.fresco.evidence/reads))
  (is (= :re-frame.fresco.evidence/v3 rf.fresco.evidence/schema)
      "the wire shape and the stamp move together, or the pin is nominal"))
