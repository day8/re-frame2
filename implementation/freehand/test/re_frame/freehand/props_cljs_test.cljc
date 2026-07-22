(ns re-frame.freehand.props-cljs-test
  "FH-PROPS-001, FH-PROPS-002 and FH-PROPS-003 — one props map, the
  reserved `:children`, and the stripped `:key`.

  Both emitters share `normalize-call`, so both execution modes deliver
  the same props map for the same call by construction rather than by
  convention. These rows run on the JVM and in ClojureScript from one
  fixture apiece."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]))

(v/defview panel [{:keys [title children]}] [:section.panel title children])
(v/defview leaf     {:children-policy :none}     [_] [:hr])
(v/defview wrapper  {:children-policy :required} [{:keys [children]}] [:div children])
(v/defview flexible {:children-policy :optional} [{:keys [children]}] [:div children])

(def ^:private by-policy
  {:none leaf :required wrapper :optional flexible})

;; ---------------------------------------------------------------------------
;; FH-PROPS-001 — one props map
;; ---------------------------------------------------------------------------

(def props-001 (conf/fixture :FH-PROPS-001))

(deftest fh-props-001-one-map-no-positional-args
  (testing "Per FH-PROPS-001: a boundary call carries exactly one props
            map. `{}` is the spelling when a view needs nothing — the
            uniform grammar is what lets the compiled analyzer read a
            call site without a special case."
    (is (seq (:accepted props-001)) "the fixture's accepted table loaded")
    (doseq [{:keys [args props]} (:accepted props-001)]
      (is (= props (:props (descriptor/normalize-call panel args)))
          (str "props for " (pr-str args))))))

(deftest fh-props-001-a-non-map-props-slot-is-rejected
  (testing "Per FH-PROPS-001: a missing or non-map props slot raises
            `:rf.error/view-bad-props`. Without it the mistake surfaces
            far downstream, as a confusing failure inside a render."
    (is (seq (:rejected props-001)) "the fixture's rejected table loaded")
    (doseq [{:keys [args error-id]} (:rejected props-001)]
      (is (= error-id (conf/caught-id #(descriptor/normalize-call panel args)))
          (str "rejects " (pr-str args))))))

;; ---------------------------------------------------------------------------
;; FH-PROPS-002 — the reserved :children
;; ---------------------------------------------------------------------------

(def props-002 (conf/fixture :FH-PROPS-002))

(deftest fh-props-002-trailing-children-arrive-under-children
  (testing "Per FH-PROPS-002: trailing forms arrive as the reserved
            `:children` vector, and the key is ABSENT when the call
            supplied none — so a childless call yields the smallest props
            map that can compare equal."
    (is (seq (:accepted props-002)))
    (doseq [{:keys [args children]} (:accepted props-002)]
      (let [props (:props (descriptor/normalize-call flexible args))]
        (if (= :absent children)
          (is (not (contains? props :children))
              (str ":children absent for " (pr-str args)))
          (is (= children (:children props))
              (str ":children for " (pr-str args))))))))

(deftest fh-props-002-caller-authored-children-is-rejected
  (testing "Per FH-PROPS-002: there is ONE way for children to arrive.
            A `:children` key written into the props map would give a
            second, silently-shadowing path."
    (is (seq (:rejected props-002)))
    (doseq [{:keys [args error-id]} (:rejected props-002)]
      (is (= error-id (conf/caught-id #(descriptor/normalize-call flexible args)))
          (str "rejects " (pr-str args))))))

(deftest fh-props-002-the-declared-children-policy-holds
  (testing "Per FH-PROPS-002: a view declares whether it accepts children,
            and the declaration is enforced at the call. A policy nobody
            checks is documentation, not a contract."
    (is (seq (:policy props-002)))
    (doseq [{:keys [policy args error-id expect]} (:policy props-002)]
      (let [view (get by-policy policy)]
        (is (some? view) (str "the suite declares a " policy " view"))
        (if error-id
          (is (= error-id (conf/caught-id #(descriptor/normalize-call view args)))
              (str policy " rejects " (pr-str args)))
          (is (= [:accepted conf/no-throw]
                 [expect (conf/caught-id #(descriptor/normalize-call view args))])
              (str policy " accepts " (pr-str args))))))))

;; ---------------------------------------------------------------------------
;; FH-PROPS-003 — the stripped :key
;; ---------------------------------------------------------------------------

(def props-003 (conf/fixture :FH-PROPS-003))

(deftest fh-props-003-key-is-stripped-and-returned-separately
  (testing "Per FH-PROPS-003: `:key` selects sibling identity for
            reconciliation and the view body never sees it."
    (is (seq (:cases props-003)))
    (doseq [{:keys [args] :as case-row} (:cases props-003)]
      (let [{:keys [key props]} (descriptor/normalize-call flexible args)]
        (is (= (:key case-row) key) (str "key for " (pr-str args)))
        (is (= (:props case-row) props) (str "props for " (pr-str args)))
        (is (not (contains? props :key))
            (str ":key is stripped from " (pr-str args)))))))

(deftest fh-props-003-key-is-outside-props-equality
  (testing "Per FH-PROPS-003: two sibling calls differing ONLY by key
            deliver props that compare equal. That is what makes `:key` a
            reconciliation fact rather than a prop the boundary re-renders
            on — and it is the reason the key is returned beside the map
            rather than left inside it."
    (let [{:keys [a b props-equal keys-differ]} (:equality props-003)
          call-a (descriptor/normalize-call flexible a)
          call-b (descriptor/normalize-call flexible b)]
      (is (= props-equal (= (:props call-a) (:props call-b))))
      (is (= keys-differ (not= (:key call-a) (:key call-b)))))))
