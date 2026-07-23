(ns re-frame.freehand.behavior-throw-cljs-test
  "The STRUCTURAL half of the same scenario: a `v/behavior` whose decorated
  element's children throw, contained on the host that has no lifecycle.

  The browser file beside this one (`behavior-throw-dom-cljs-test`) pins the
  behavior's OWN occurrence seam, because in React the decorated element is
  walked inside the behavior component's render. The structural host reaches
  the identical declaration by a different route, and the difference is worth
  stating rather than assuming: a boundary's trailing children are lowered
  BEFORE the boundary is mounted, so the decorated element's walk belongs to
  the ENCLOSING declared occurrence and the behavior — an inert marker here,
  with nothing to connect — never sees the throw at all.

  So the cell this file covers is not the behavior seam. It is the claim that
  a behavior in the path changes NOTHING about containment or attribution on
  the structural host: the failure is still contained, still attributed to a
  real declared view rather than to `unknown-view` or to the catcher, and the
  safe summary is still the closed public envelope. A marker that disturbed
  any of those would be a marker that made a page behave differently in a
  server render than in a browser."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.set :as set]
            [clojure.string :as string]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.tree :as tree]))

(def ^:private error-001 (conf/fixture :FH-ERROR-001))
(def ^:private error-003 (conf/fixture :FH-ERROR-003))

;; ---------------------------------------------------------------------------
;; The declarations
;; ---------------------------------------------------------------------------

(def ^:private connects
  "How many times the behavior's `:connect` ran. On the structural host
  nothing may ever run it — that is the inert-marker claim — so this stays at
  zero whether the render succeeds or fails."
  (atom 0))

(v/defbehavior marker
  "The smallest registered behavior that can legally be attached. Its
  `:connect` only counts, so a structural render that ran it would be visible
  rather than merely suspected."
  {:timing  :passive
   :connect (fn [_] (swap! connects inc) nil)})

(def ^:private decorated-child-failure
  "The ONE exception the decorated element's children throw, carrying a
  marked secret in its `ex-data`: a summary that leaked host detail would
  carry it too."
  (ex-info "the decorated element's children threw" {:secret "must-not-leak"}))

(defn- throwing-run
  "A child run that throws WHEN THE WALK REALISES IT, and not while the view
  body is still building the form."
  []
  (map (fn [_] (throw decorated-child-failure)) [:row]))

(v/defview decorated-child-throws
  "A behavior over an ordinary element whose children throw as they are
  walked."
  [_]
  [v/behavior {:use marker :target :probe/decorated :config {:label "panel"}}
   [:div.node (throwing-run)]])

(v/defview decorated-child-renders
  "The same declaration with a child that does not throw — the control that
  makes the behavior's presence in the path a fact rather than an assumption."
  [_]
  [v/behavior {:use marker :target :probe/decorated :config {:label "panel"}}
   [:div.node "content"]])

(defn- id-of [view] (:view-id (descriptor/describe view)))

(defn- behavior-node
  "The behavior boundary node anywhere in `tree`, or nil."
  [tree]
  (first (filter #(= behaviors/behavior-view-id (:view-id %))
                 (tree-seq map? :children tree))))

(defn- contained-summary
  "The safe summary of the failure `form`'s walk produces under a fresh
  boundary — the structural host's realisation of what a mounted boundary
  reports."
  [form]
  (let [b (eb/boundary eb/boundary-view-id :rk)]
    (:summary (eb/contain b #(tree/render form) {:phase :render :frame-id nil}))))

(defn- deep-keys
  "Every map key anywhere in `x`, so a forbidden slot cannot hide nested."
  [x]
  (cond
    (map? x)  (into (set (keys x)) (mapcat deep-keys (vals x)))
    (coll? x) (into #{} (mapcat deep-keys x))
    :else     #{}))

;; ===========================================================================
;; The control — the behavior really is in the path, and really inert
;; ===========================================================================

(deftest the-behavior-is-in-the-path-and-connects-nothing
  (testing "The control mount: the same declaration with a child that does
            not throw records the behavior boundary in the tree with the
            decorated element as its child, and nothing connects. Without it,
            a contained failure below could be a failure of a declaration the
            behavior was never part of."
    (reset! connects 0)
    (let [node (behavior-node (tree/render [decorated-child-renders {}]))]
      (is (some? node)
          "the boundary node is recorded")
      (is (= :div (:tag (first (:children node))))
          "with the author's own element as its child")
      (is (zero? @connects)
          "and no lifecycle ran — the structural host owns no node to connect to"))))

;; ===========================================================================
;; Containment and attribution with a behavior in the path
;; ===========================================================================

(deftest a-throw-under-a-behavior-is-contained-and-attributed-to-a-real-view
  (testing "The decorated element's children throw. On the structural host
            the boundary's children are lowered before the boundary is
            mounted, so the throw unwinds through the ENCLOSING declared
            view's occurrence seam — and that is the view the report names.
            What it must not be is `unknown-view`, which would say the throw
            was never observed, or the catcher's own id, which reads like an
            identification and sends a reader to the wrong file."
    (reset! connects 0)
    (let [summary (contained-summary [decorated-child-throws {}])]
      (is (= (id-of decorated-child-throws) (:view-id summary))
          "the declared occurrence the walk was inside when it threw")
      (is (not= eb/unknown-view-id (:view-id summary))
          "not the truthful-ignorance marker — an occurrence did observe it")
      (is (not= behaviors/behavior-view-id (:view-id summary))
          "and not the inert marker, which never entered the walk")
      (is (= eb/boundary-view-id (:boundary-view-id summary))
          "the catcher keeps its own separate field")
      (is (true? (:complete? (:evidence summary)))
          "the evidence is complete")
      (is (zero? @connects)
          "and a failed structural render still connects nothing"))))

(deftest the-contained-failure-still-reports-the-closed-public-envelope
  (testing "A behavior in the path changes nothing about the report's shape:
            the safe summary carries exactly the closed public field set and
            nothing host-shaped — no exception, no `ex-data`, no props, no
            app-db and no event history — so what an application may put in
            app-db or dispatch is bounded whichever declaration failed."
    (let [summary (contained-summary [decorated-child-throws {}])]
      (is (= (:safe-summary-keys error-003) (set (keys summary)))
          "exactly the closed public field set")
      (is (= (:diagnostic-id error-003) (:diagnostic-id summary)))
      (is (= (:phase error-003) (:phase summary)))
      (is (= (:basis error-003) (:basis (:evidence summary))))
      (is (empty? (set/intersection (:forbidden-keys error-003) (deep-keys summary)))
          "no exception, ex-data, props, app-db or event history")
      (is (not (string/includes? (pr-str summary) "must-not-leak"))
          "and the thrown value's ex-data never reaches the public envelope"))))

(deftest a-sibling-of-the-guarded-behavior-keeps-rendering
  (testing "Per FH-ERROR-001, containment is local: the boundary shows its
            fallback and the sibling subtree beside it is untouched. Proving
            it with a behavior under the boundary is what says the marker
            does not widen the blast radius of a failure below it."
    (let [tree (tree/render
                 [:div
                  [v/error-boundary {:fallback (:fallback error-001)}
                   [decorated-child-throws {}]]
                  (:sibling error-001)])
          {:keys [fallback-tag sibling-tag sibling-text]} (:contained error-001)]
      (is (= fallback-tag (:tag (first (:children (first (:children tree))))))
          "the boundary holds the fallback subtree")
      (is (= sibling-tag (:tag (second (:children tree))))
          "and the sibling kept rendering")
      (is (= [sibling-text] (:children (second (:children tree))))))))

;; ===========================================================================
;; Non-vacuity — the run really throws
;; ===========================================================================

(deftest non-vacuity-the-run-throws-when-it-is-realised
  (testing "Non-vacuity: the decorated element's child run really throws when
            realised, and the enclosing view body really does return without
            throwing — so the failures above are the WALK's, contained where
            the tests say they are, and not a body that failed before the
            behavior was ever reached."
    (is (thrown? #?(:clj Throwable :cljs :default) (doall (throwing-run)))
        "realising the run throws")
    (is (vector? ((descriptor/render-body decorated-child-throws) {}))
        "while the view body itself returns markup")))
