(ns re-frame.freehand.classification-cljs-test
  "FH-CALL-002 — vector-head classification is total.

  Table-driven from the shared fixture, on both hosts. The table's job is
  to make the FOURTH case impossible: every illegal head named here is a
  shape some plausible implementation would have quietly accepted — a
  bare function (the permissive model), a plain map (IFn on both hosts,
  and the shape a descriptor would take if it were data), a symbol, and
  the scalars a hiccup walker is tempted to stringify."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]))

(v/defview declared [_] [:div])

(def call-002 (conf/fixture :FH-CALL-002))

(def ^:private heads
  "The fixture names heads symbolically — EDN cannot carry a live
  descriptor or a function. This is the one place those names become
  values."
  {:fixture/declared-view   declared
   :fixture/element-keyword :div
   :fixture/custom-element  :my-widget
   :fixture/host-descriptor {:re-frame.freehand/host true :component "Chart"}
   :fixture/plain-function  (fn [_] [:div])
   :fixture/string          "cart-badge"
   :fixture/nil             nil
   :fixture/number          42
   :fixture/vector          [:div]
   :fixture/plain-map       {:title "Details"}
   :fixture/symbol          'cart-badge})

(deftest fh-call-002-the-table-is-not-empty
  (testing "A table-driven law whose table failed to load passes
            vacuously — the worst failure mode available. Pin the shape
            of the fixture before trusting any row of it."
    (is (= "FH-CALL-002" (:fh/id call-002)))
    (is (<= 10 (count (:cases call-002)))
        "the fixture carries the full head roster, not a truncated read")
    (is (every? #(contains? heads (:head %)) (:cases call-002))
        "every head the fixture names is one this suite can construct")))

(deftest fh-call-002-classification-is-total
  (testing "Per FH-CALL-002: three legal heads and one error arm, in one
            order, with no heuristic fallback. Each row asserts the exact
            classification or the exact diagnostic id."
    (doseq [{:keys [head expect error-id]} (:cases call-002)]
      (let [value (get heads head)]
        (if (= :error expect)
          (is (= error-id (conf/caught-id #(v/classify-head value)))
              (str head " is not a legal head"))
          (is (= expect (v/classify-head value))
              (str head " classifies as " expect)))))))

(deftest fh-call-002-the-error-names-the-three-legal-forms
  (testing "Per FH-CALL-002: the diagnostic names all three legal forms,
            so the recovery is in the message rather than in the docs.
            The message is stable in MEANING, not bytes — this asserts
            the concepts it must carry, never its exact text."
    (let [message (conf/caught-message #(v/classify-head "cart-badge"))]
      (is (string? message))
      (doseq [phrase (:message-must-name call-002)]
        (is (str/includes? message phrase)
            (str "the diagnostic names " (pr-str phrase)))))))

(deftest fh-call-002-a-callable-head-gets-the-helper-recovery
  (testing "Per FH-CALL-002 and D002: the mistake this arm actually
            catches in the field is a plain `defn` used as a vector head.
            That head gets the extra recovery the sharp convention owes
            it — declare it, or call it with parentheses."
    (let [message (conf/caught-message #(v/classify-head (fn [_] [:div])))]
      (doseq [phrase (:callable-message-must-name call-002)]
        (is (str/includes? message phrase)
            (str "a callable head's diagnostic names " (pr-str phrase)))))))

(deftest fh-call-002-ex-data-carries-the-machine-facing-slots
  (testing "Per FH-CALL-002: tools branch on ex-data, never on the
            message. The offending head rides as a SHAPE summary, never
            as the value itself (Spec 015 §Data-Classification)."
    (let [data (try (v/classify-head "cart-badge")
                    nil
                    (catch #?(:clj Throwable :cljs :default) e (ex-data e)))]
      (doseq [k (:ex-data-keys call-002)]
        (is (contains? data k) (str "ex-data carries " k)))
      (is (= [:declared-view :element-keyword :host-descriptor] (:legal-heads data)))
      (is (= {:type :string :count 10 :head "cart-badge"} (:head data))
          "the head rides as a bounded shape summary, not as the value"))))

(deftest host-descriptor-marker-is-exact
  (testing "The host arm keys off the reserved marker, not off map-ness.
            A plain map — the shape a descriptor would take if it were
            data — is NOT a host boundary; it is an error, which is what
            keeps the classification total rather than duck-typed."
    (is (v/host-descriptor? {:re-frame.freehand/host true}))
    (is (not (v/host-descriptor? {:re-frame.freehand/host false})))
    (is (not (v/host-descriptor? {:host true})))
    (is (not (v/host-descriptor? {})))
    (is (not (v/host-descriptor? declared)))))
