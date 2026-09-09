(ns re-frame.hicasso.evidence-schema-cljs-test
  "THE ENVELOPE DOOR — `re-frame.hicasso.evidence/envelope` stamps a
  coherent read and refuses every shape in which a read would claim more
  than it knows.

  Each refusal asserts the problem the door NAMED, not merely that it
  threw: four shapes throw from one door, and a witness that only knew
  *something threw* would stay green if the wrong check fired. The
  positive control beside them proves the door can go green, so a refusal
  is the check firing and not the door being broken for everything."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.hicasso.evidence :as rf.hicasso.evidence]))

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
  (and (= :incoherent-envelope (:re-frame.hicasso.evidence/defect (:refused o)))
       (some #(str/includes? % fragment) (:problems (:refused o)))))

;; ---------------------------------------------------------------------------
;; The positive controls — the door can go green
;; ---------------------------------------------------------------------------

(deftest a-coherent-envelope-is-stamped-over-its-body
  (let [e (rf.hicasso.evidence/envelope :mounted-boundaries true nil {:boundaries [] :generation 3})]
    (is (= rf.hicasso.evidence/schema (:schema e)) "the version pin rides on every envelope")
    (is (= rf.hicasso.evidence/producer (:producer e)))
    (is (= :mounted-boundaries (:read e)))
    (is (true? (:complete? e)))
    (is (nil? (:loss e)))
    (is (= [] (:boundaries e)) "the body survives the stamp")
    (is (= 3 (:generation e)))))

(deftest a-capped-envelope-carries-its-loss
  (let [e (rf.hicasso.evidence/envelope :intents false {:reason :cap :dropped rf.hicasso.evidence/unknown} {:intents []})]
    (is (false? (:complete? e)))
    (is (= {:reason :cap :dropped rf.hicasso.evidence/unknown} (:loss e)))
    (testing "and a counted drop is legal too"
      (is (contains? (outcome #(rf.hicasso.evidence/envelope :intents false {:reason :cap :dropped 4} {}))
                     :emitted)))))

;; ---------------------------------------------------------------------------
;; The refusals — each names its problem
;; ---------------------------------------------------------------------------

(deftest a-read-outside-the-vocabulary-is-refused
  (let [o (outcome #(rf.hicasso.evidence/envelope :whatever true nil {}))]
    (is (refused-with o ":read :whatever") (str (:message o)))))

(deftest a-loss-must-name-a-reason-and-size-its-drop
  (testing "an absent :dropped is the shape in which unknown looks like none"
    (is (refused-with (outcome #(rf.hicasso.evidence/envelope :intents false {:reason :cap} {}))
                      "absent :dropped")))
  (testing "a reason outside the closed vocabulary"
    (is (refused-with (outcome #(rf.hicasso.evidence/envelope :intents false {:reason :probably :dropped 3} {}))
                      ":loss")))
  (testing "a loss that is not a map"
    (is (refused-with (outcome #(rf.hicasso.evidence/envelope :intents false :cap {})) ":loss"))))

(deftest completeness-and-loss-cannot-both-be-claimed
  (let [o (outcome #(rf.hicasso.evidence/envelope :mounted-boundaries true {:reason :cap :dropped 4} {}))]
    (is (refused-with o "claims completeness and also reports loss") (str (:message o)))))

(deftest completeness-is-a-boolean
  (is (refused-with (outcome #(rf.hicasso.evidence/envelope :mounted-boundaries nil nil {})) ":complete?"))
  (is (refused-with (outcome #(rf.hicasso.evidence/envelope :mounted-boundaries :yes nil {})) ":complete?")))

(deftest a-refusal-names-every-problem-at-once
  (let [o (outcome #(rf.hicasso.evidence/envelope :nope true {:reason :cap} {}))]
    (is (= 3 (count (:problems (:refused o))))
        "the read, the loss's missing :dropped and the completeness clash are all named")))

;; ---------------------------------------------------------------------------
;; The vocabulary is closed
;; ---------------------------------------------------------------------------

(deftest the-vocabulary-is-closed
  (is (= :unknown rf.hicasso.evidence/unknown))
  (is (= #{:cap :opaque :host-opaque :uncorrelated} rf.hicasso.evidence/loss-reasons))
  (is (= #{:mounted-boundaries :read-attribution :intents :explain-render} rf.hicasso.evidence/reads))
  (is (= :re-frame.hicasso.evidence/v3 rf.hicasso.evidence/schema)
      "the wire shape and the stamp move together, or the pin is nominal"))
