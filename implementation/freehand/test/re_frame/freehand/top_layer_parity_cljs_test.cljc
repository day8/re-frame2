(ns re-frame.freehand.top-layer-parity-cljs-test
  "FH-TOPLAYER-001 / FH-TOPLAYER-002 — what the desired-state pair MEANS,
  proven where meaning lives: in the structural tree and in the refusals.

  The browser rows (FH-TOPLAYER-003 / FH-TOPLAYER-004) prove what the host
  DOES. These two prove the half that has nothing to do with a browser:
  that the pair leaves `:attrs` and becomes the reserved `:rf.ui/top-layer`
  fact, that a nil value expresses nothing, and that each property is legal
  only where its browser operation exists.

  Both hosts and both modes. The interpreted declarations in
  [[re-frame.freehand.top-layer-views]] and their `{:compiled true}` twins
  render against ONE fixture table, so a promoted declaration is proven to
  denote the same node — which is what \"compiler-recognised common
  semantics\" has to mean if it is to mean anything."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.top-layer :as top-layer]
            [re-frame.freehand.top-layer-views :as views]
            [re-frame.freehand.top-layer-views-compiled :as compiled]
            [re-frame.freehand.tree :as tree]))

(def toplayer-001 (conf/fixture :FH-TOPLAYER-001))
(def toplayer-002 (conf/fixture :FH-TOPLAYER-002))

(def ^:private interpreted-ns "re-frame.freehand.top-layer-views")
(def ^:private compiled-ns "re-frame.freehand.top-layer-views-compiled")

(defn- as-compiled-ids
  "Rewrite the fixture's expected `:view-id`s onto the compiled twins'
  namespace — the only difference promotion is allowed to make, and it is
  not one promotion makes at all: it is one that living in another file
  makes."
  [tree]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= interpreted-ns (namespace x)))
        (keyword compiled-ns (name x))
        x))
    tree))

;; ===========================================================================
;; FH-TOPLAYER-001 — the structural fact
;; ===========================================================================

(deftest fh-toplayer-001-the-pair-projects-as-a-reserved-fact
  (testing "Per FH-TOPLAYER-001: a desired-state property leaves `:attrs`
            and projects as `:rf.ui/top-layer` on the semantic element —
            the same value from an interpreted declaration and its compiled
            twin, on this host."
    (is (seq (:cases toplayer-001)) "the fixture's case table loaded")
    (doseq [{:keys [note view args tree]} (:cases toplayer-001)]
      (let [interpreted (get views/by-name view)
            promoted    (get compiled/by-name view)
            call        (into [] args)]
        (is (some? interpreted) (str "the fixture names an interpreted view: " view))
        (is (some? promoted) (str "the fixture names a promoted twin: " view))
        (is (= tree (tree/render (into [interpreted] call)))
            (str "interpreted — " note))
        (is (= (as-compiled-ids tree) (tree/render (into [promoted] call)))
            (str "compiled — " note))))))

(deftest fh-toplayer-001-no-qualified-property-survives-into-attrs
  (testing "Per FH-TOPLAYER-001: the properties are vocabulary, not
            attributes. An emitter reads an attribute by its name and drops
            the namespace, so one that survived into `:attrs` would reach
            the DOM as a garbage prop — the tree must carry none."
    (let [fact-key (:fact-key toplayer-001)
          nodes    (->> (:cases toplayer-001)
                        (mapcat (fn [{:keys [view args]}]
                                  (tree-seq map? :children
                                            (tree/render (into [(get views/by-name view)]
                                                               (into [] args))))))
                        (filter map?))]
      (is (= :rf.ui/top-layer fact-key) "the fixture names the reserved fact key")
      (is (seq nodes) "the walk reached nodes")
      (doseq [node nodes]
        (is (empty? (filter #(= "re-frame.freehand.web" (namespace %))
                            (filter keyword? (keys (:attrs node)))))
            (str "no web-qualified key in :attrs of " (or (:tag node) (:view-id node)))))
      ;; And the fact is where it should be, on exactly the nodes that
      ;; declared one — three of the four fixture rows.
      (is (= 3 (count (filter #(contains? % fact-key) nodes)))
          "exactly the declaring elements carry the fact"))))

;; ===========================================================================
;; FH-TOPLAYER-002 — the closed pair and its validity rules
;; ===========================================================================

(deftest fh-toplayer-002-each-half-is-legal-only-where-its-operation-exists
  (testing "Per FH-TOPLAYER-002: `popover-open?` needs a valid `:popover`
            mode, `modal-open?` needs `<dialog>`, both together are refused,
            and the value is a boolean or nothing — every refusal the same
            id, raised at render on this host."
    (let [error-id (:error-id toplayer-002)]
      (is (seq (:accepted toplayer-002)) "the fixture's acceptance table loaded")
      (is (seq (:rejected toplayer-002)) "the fixture's rejection table loaded")
      (doseq [{:keys [note markup]} (:accepted toplayer-002)]
        (is (= conf/no-throw (conf/caught-id #(tree/render markup)))
            (str "accepted — " note)))
      (doseq [{:keys [note markup]} (:rejected toplayer-002)]
        (is (= error-id (conf/caught-id #(tree/render markup)))
            (str "rejected — " note))))))

(deftest fh-toplayer-002-recognition-is-two-lookups-and-a-fact
  (testing "Per FH-TOPLAYER-002: the common recognition surface answers the
            desired state as DATA — the value the structural fact and the
            host call are both derived from — and answers nothing for an
            element that declares none."
    (is (nil? (top-layer/desired :div {:popover :auto}))
        "a popover with no desired state expresses none")
    (is (nil? (top-layer/desired :div {:class "x"}))
        "an ordinary element expresses none")
    (is (= {:popover-open? true}
           (top-layer/desired :div {:popover :auto
                                    :re-frame.freehand.web/popover-open? true}))
        "the popover half")
    (is (= {:modal-open? false}
           (top-layer/desired :dialog {:re-frame.freehand.web/modal-open? false}))
        "the modal half, desired closed")
    (is (= {:popover "auto"}
           (top-layer/without {:popover "auto"
                               :re-frame.freehand.web/popover-open? true
                               :re-frame.freehand.web/modal-open? false}))
        "the pair is withheld from whatever an emitter spells")))
