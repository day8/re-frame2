(ns re-frame.freehand.errors-tree-cljs-test
  "FH-ERROR-001 (the containment half) — `v/error-boundary` on the structural
  host.

  On the JVM structural walk (and identically in ClojureScript, which is what
  makes this a cross-host claim) a boundary IS a try around the child's walk:
  a render-class throw below the boundary is CONTAINED — the boundary node
  holds the fallback subtree — while every SIBLING node the surrounding walk
  built keeps its place. The browser realises the same law through a React
  class boundary; here it is proven as a pure structural value."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.errors :as eb]
            [re-frame.freehand.tree :as tree]))

(def error-001 (conf/fixture :FH-ERROR-001))

;; Two test-local declared children: one that renders, one that throws when
;; its body runs — the render-class failure a boundary exists to contain.
(def ^:private ok-child
  (descriptor/declare-view
    {:view-id :app/ok :lowering :interpreted :children-policy :optional
     :render (fn [_] [:span "ok"])}))

(def ^:private throwing-child
  (descriptor/declare-view
    {:view-id :app/throwing :lowering :interpreted :children-policy :optional
     :render (fn [_] (throw (ex-info "a child render threw" {})))}))

;; A SECOND throwing descendant, and a healthy wrapper that mounts one of
;; them — the shapes attribution has to tell apart.
(def ^:private other-throwing-child
  (descriptor/declare-view
    {:view-id :app/other-throwing :lowering :interpreted :children-policy :optional
     :render (fn [_] (throw (ex-info "a different child render threw" {})))}))

(def ^:private wrapper
  (descriptor/declare-view
    {:view-id :app/wrapper :lowering :interpreted :children-policy :optional
     :render (fn [_] [:div [throwing-child {}]])}))

(defn- boundary-node
  "The single error-boundary node under the rendered `:div` root."
  [tree]
  (first (:children tree)))

(defn- sibling-node
  [tree]
  (second (:children tree)))

(deftest fh-error-001-a-boundary-passes-a-healthy-child-through
  (testing "Per FH-ERROR-001: with no throw, the boundary is transparent —
            its node holds the child's own subtree, and the sibling renders
            beside it as usual."
    (let [{:keys [boundary-view-id sibling-tag sibling-text]} (:contained error-001)
          tree (tree/render
                 [:div
                  [v/error-boundary {:fallback (:fallback error-001)} [ok-child {}]]
                  (:sibling error-001)])
          bnode (boundary-node tree)]
      (is (= boundary-view-id (:view-id bnode)))
      (is (= [{:tag :span :children ["ok"]}]
             (:children (first (:children bnode))))
          "the child rendered inside the boundary node")
      (is (= sibling-tag (:tag (sibling-node tree))))
      (is (= [sibling-text] (:children (sibling-node tree)))))))

(deftest fh-error-001-a-throwing-child-is-contained-and-siblings-keep-rendering
  (testing "Per FH-ERROR-001: a child that throws is CONTAINED — the boundary
            node holds the FALLBACK subtree instead of the child's — and the
            SIBLING keeps rendering. The throw never reaches the surrounding
            `:div`, so the whole tree still renders."
    (let [{:keys [boundary-view-id fallback-tag fallback-text sibling-tag sibling-text]}
          (:contained error-001)
          tree (tree/render
                 [:div
                  [v/error-boundary {:fallback (:fallback error-001)} [throwing-child {}]]
                  (:sibling error-001)])
          bnode (boundary-node tree)]
      (is (= boundary-view-id (:view-id bnode))
          "the boundary node is present")
      (is (= [{:tag fallback-tag :attrs {:class "fallback"} :children [fallback-text]}]
             (:children bnode))
          "and it holds the fallback subtree, not the child's")
      (is (= sibling-tag (:tag (sibling-node tree)))
          "the sibling kept rendering")
      (is (= [sibling-text] (:children (sibling-node tree)))))))

(deftest fh-error-001-a-changed-reset-key-re-mounts-and-retries-the-child
  (testing "Per FH-ERROR-001: a fresh structural render with the child no
            longer throwing renders the child again — the structural analogue
            of a reset re-mounting and retrying the child. (The stateful
            reset gate itself is proven by the error-boundary law suite.)"
    (let [failing (tree/render
                    [v/error-boundary {:fallback (:fallback error-001) :reset-key :r1}
                     [throwing-child {}]])
          healthy (tree/render
                    [v/error-boundary {:fallback (:fallback error-001) :reset-key :r2}
                     [ok-child {}]])]
      (is (= :p (:tag (first (:children failing)))) "first the fallback")
      (is (= [{:tag :span :children ["ok"]}]
             (:children (first (:children healthy))))
          "then, retried, the child"))))

;; ===========================================================================
;; FH-ERROR-003 (attribution) — the summary names the view that THREW.
;; ===========================================================================

(defn- contained-summary
  "The safe summary of the failure `child`'s walk produces under a fresh
  boundary — the structural host's realisation of what a mounted boundary
  reports."
  [child]
  (let [b (eb/boundary eb/boundary-view-id :rk)]
    (:summary (eb/contain b #(tree/render child) {:phase :render :frame-id nil}))))

(deftest fh-error-003-a-failure-is-attributed-to-the-view-that-threw
  (testing "Per FH-ERROR-003: `:view-id` is the DECLARED VIEW THAT THREW and
            `:boundary-view-id` is the catcher — two fields because they are
            two facts. A report that put the boundary in the failing slot
            would send a reader to the wrong file, and it would do so however
            deep the real failure was: the guarded child, an element under
            it, or a view several occurrences down."
    (doseq [[why child expected]
            [["the guarded child itself threw"
              [throwing-child {}]           :app/throwing]
             ["the throw is one plain element below the guarded child"
              [:div [throwing-child {}]]    :app/throwing]
             ["a healthy declared wrapper mounts the view that threw"
              [wrapper {}]                  :app/throwing]]]
      (let [summary (contained-summary child)]
        (is (= expected (:view-id summary)) why)
        (is (= eb/boundary-view-id (:boundary-view-id summary))
            (str why " — and the catcher keeps its own separate field"))))))

(deftest fh-error-003-two-descendants-of-one-boundary-fingerprint-apart
  (testing "Per FH-ERROR-003: the fingerprint is a correlation token derived
            from the failing view and the phase, so two DIFFERENT views
            failing under one boundary must fingerprint APART. Attributing
            to the catcher — or to the guarded child a deeper failure passed
            through — collapses every bug under a boundary onto one token,
            and two unrelated failures become indistinguishable."
    (let [a (contained-summary [throwing-child {}])
          b (contained-summary [other-throwing-child {}])]
      (is (= :app/throwing (:view-id a)))
      (is (= :app/other-throwing (:view-id b)))
      (is (not= (:fingerprint a) (:fingerprint b))
          "two failing descendants of one boundary carry two fingerprints"))))

(deftest fh-error-003-an-unobservable-thrower-is-reported-as-unknown
  (testing "Per FH-ERROR-003: when no occurrence observed which declared view
            threw — a raw throw inside the guarded region, a foreign
            component's failure — the record says exactly that. The view id
            is the reserved unknown marker and the evidence is incomplete
            with a `:loss` naming why. What it must never be is the
            boundary's own id, which reads like an identification."
    (let [b       (eb/boundary eb/boundary-view-id :rk)
          summary (:summary (eb/contain b
                                        #(throw (ex-info "raw" {}))
                                        {:phase :render :frame-id nil}))]
      (is (= eb/unknown-view-id (:view-id summary))
          "truthful ignorance, not the catcher's id")
      (is (not= eb/boundary-view-id (:view-id summary)))
      (is (false? (:complete? (:evidence summary)))
          "and the evidence states that it is incomplete")
      (is (= {:reason :failing-view-unobserved} (:loss (:evidence summary)))
          "naming why"))))

(deftest fh-error-003-an-abandoned-throw-cannot-attribute-a-later-failure
  (testing "Per FH-ERROR-003: attribution belongs to the FAILURE, not to the
            boundary that happens to catch next. A throw that passed an
            occurrence seam and was then swallowed — retry logic, a probe, a
            body that catches its own child — is abandoned: nothing will ever
            report it. The DIFFERENT failure that reaches the boundary
            afterwards must still name its own thrower. Reading the abandoned
            note here names an innocent view and, because the fingerprint
            derives from it, files the new bug under the old one's
            correlation token."
    (let [b       (eb/boundary eb/boundary-view-id :rk)
          summary (:summary
                    (eb/contain
                      b
                      (fn []
                        ;; Abandoned: seen by :app/throwing's seam, swallowed here.
                        (try (tree/render [throwing-child {}])
                             (catch #?(:clj Throwable :cljs :default) _ nil))
                        ;; The failure that actually reaches the boundary.
                        (tree/render [other-throwing-child {}]))
                      {:phase :render :frame-id nil}))]
      (is (= :app/other-throwing (:view-id summary))
          "the view that threw THIS failure, not the abandoned one")
      (is (= (eb/fingerprint :app/other-throwing :render) (:fingerprint summary))
          "and the correlation token follows the real thrower"))))

(deftest fh-error-003-an-uncontained-throw-leaves-nothing-a-later-boundary-reads
  (testing "Per FH-ERROR-003: a throw that NOTHING contains — it escaped the
            root — was still seen by an occurrence seam on the way out. A
            boundary mounted afterwards, catching an entirely unrelated and
            unobservable failure, must still report the truthful unknown: the
            escaped throw's identity belongs to the escaped throw."
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (tree/render [:div [throwing-child {}]]))
        "the throw escapes with no boundary to contain it")
    (let [b       (eb/boundary eb/boundary-view-id :rk)
          summary (:summary (eb/contain b
                                        #(throw (ex-info "raw" {}))
                                        {:phase :render :frame-id nil}))]
      (is (= eb/unknown-view-id (:view-id summary))
          "the later boundary reports what IT observed — nothing")
      (is (= {:reason :failing-view-unobserved} (:loss (:evidence summary)))))))

;; ===========================================================================
;; The exact-one-child grammar — the interpreted half of the table the
;; compiled analyzer already refuses (`analyze-reject-cljs-test`
;; `error-boundary-grammar`).
;; ===========================================================================

(deftest an-error-boundary-guards-exactly-one-child
  (testing "`v/error-boundary` guards ONE region, so the interpreted mount
            refuses zero children and refuses several — it never keeps the
            first and discards the rest. A dropped subtree is the failure
            mode that produces a program which looks like it worked: nothing
            on screen and nothing in the console says a declared child was
            thrown away. The compiled analyzer refuses the same zero / one /
            many table, so a declaration means the same thing in both modes."
    ;; ZERO — the declared children policy is the one that speaks first.
    (is (= :rf.error/view-children-policy
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback (:fallback error-001)}])))
        "a boundary guarding nothing is refused")
    ;; ONE — accepted, and the guarded child renders.
    (let [tree (tree/render
                 [v/error-boundary {:fallback (:fallback error-001)} [ok-child {}]])]
      (is (= [{:tag :span :children ["ok"]}]
             (:children (first (:children tree))))
          "exactly one guarded child is the accepted shape"))
    ;; MANY — refused, naming how many were declared.
    (is (= :rf.error/error-boundary-bad-args
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback (:fallback error-001)}
                            [:span "first"] [:span "second"]])))
        "a second declared child is REFUSED, not dropped")
    (is (= :rf.error/error-boundary-bad-args
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback (:fallback error-001)}
                            [ok-child {}] [ok-child {}] [ok-child {}]])))
        "and so is a third")))

(deftest the-rest-of-the-error-boundary-grammar-is-refused-arm-by-arm
  (testing "The other arms of the same closed grammar, each driven so the
            refusal that owns it actually RUNS.

            The compiled analyzer's counterpart table is asserted arm by arm
            (`analyze-reject-cljs-test` `error-boundary-grammar`); the
            interpreted tier's was asserted for the child count alone. So
            three of `read-opts`' four refusals, and the closed-roster
            refusal the props schema owns, were code that no test in the
            corpus executed — the same shape of hole as an occurrence seam
            whose catch nothing ever entered. Two modes that must agree about
            one declaration cannot be checked for agreement on arms only one
            of them ever runs, and a refusal nothing runs is a refusal that
            can be broken silently.

            The ids differ by arm because the ROSTER is the schema's to close
            and the SHAPE rules are the boundary's own, and each is pinned as
            it actually fires rather than as a reader might assume."
    ;; The option roster is CLOSED — a one-character typo must not produce a
    ;; boundary that quietly means something other than it says.
    (is (= :rf.error/view-bad-props
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback (:fallback error-001)
                                              :catch    true}
                            [ok-child {}]])))
        "an option outside the roster is refused, not ignored")
    ;; A boundary with no fallback would show NOTHING on failure — the one
    ;; outcome worse than the failure itself.
    (is (= :rf.error/error-boundary-bad-args
           (conf/caught-id #(tree/render [v/error-boundary {} [ok-child {}]])))
        "a boundary with no :fallback is refused")
    ;; `:on-error` is ONE event prefix. The safe summary is appended to it and
    ;; dispatched, so a value that is not an event vector has nowhere to go.
    (is (= :rf.error/error-boundary-bad-args
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback (:fallback error-001)
                                              :on-error "telemetry/failed"}
                            [ok-child {}]])))
        "an :on-error that is not a vector is refused")
    (is (= :rf.error/error-boundary-bad-args
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback (:fallback error-001)
                                              :on-error ["telemetry/failed"]}
                            [ok-child {}]])))
        "and so is a vector whose head is not the event id keyword")
    ;; The accepted shape, so the four refusals above are refusals of
    ;; something and not of everything.
    (is (= conf/no-throw
           (conf/caught-id
             #(tree/render [v/error-boundary {:fallback  (:fallback error-001)
                                              :reset-key :r1
                                              :on-error  [:telemetry/failed]}
                            [ok-child {}]])))
        "the whole roster, correctly spelled, is accepted")))

;; ===========================================================================
;; Non-vacuity probe — the containment is doing REAL work.
;; ===========================================================================

(deftest non-vacuity-without-a-boundary-the-throw-propagates
  (testing "Non-vacuity: the throwing child, rendered WITHOUT a boundary
            above it, propagates its throw — so the contained result above is
            real containment, not a child that quietly rendered nothing. And
            the FH-ERROR-001 fixture carries real expectations."
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (tree/render [:div [throwing-child {}]]))
        "the throw escapes when nothing contains it")
    (is (map? (:contained error-001)) "the FH-ERROR-001 fixture is non-empty")
    ;; And the boundary really is a declared boundary, not an ordinary view.
    (is (true? (descriptor/error-boundary? v/error-boundary))
        "v/error-boundary carries the error-boundary marker")))
