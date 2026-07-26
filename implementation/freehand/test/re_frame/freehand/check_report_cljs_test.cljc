(ns re-frame.freehand.check-report-cljs-test
  "The checker's REPORT VOCABULARY, on both hosts.

  The analysis is JVM-only — head classification is resolution, and
  resolution happens on the JVM for both compilation targets. The
  vocabulary a report is written in is not: a ClojureScript tool that
  receives a report over the wire has to render `:reason` and turn a
  `:recovery` ladder into sentences, and it must do so from the same
  rosters the JVM wrote the report with. So this suite runs on both
  hosts and pins the parts that are shared — which is also how a
  `.cljc` claim stops being a claim.

  `re-frame.freehand.check-jvm-test` proves the analysis itself."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.check :as check]
            [re-frame.freehand.compiler.grammar :as grammar]))

(deftest reasons-are-a-closed-unqualified-roster
  (testing "a reason names a KIND of defect, not a second id — so it is a
            plain keyword outside the framework's reserved :rf.* scheme,
            and several ids may share one"
    (doseq [[id reason] check/reasons]
      (is (qualified-keyword? id) (str id " is a diagnostic id"))
      (is (simple-keyword? reason) (str id " -> " reason " is unqualified")))
    (is (contains? (set (vals check/reasons)) :markup-hidden-from-analyzer)
        "the D010 cliff keeps the name D010 gave it")
    (is (< (count (set (vals check/reasons))) (count check/reasons))
        "reasons GROUP ids; a one-to-one table would just be the ids again")))

(deftest reason-is-total
  (is (= :markup-hidden-from-analyzer
         (check/reason :rf.ui.compile/markup-returning-map)))
  (testing "an id nobody has categorised still answers — a new analyzer
            diagnostic must not become a tool outage"
    (is (= check/unclassified-reason (check/reason :rf.ui.compile/not-yet-invented))
        "and the honest general statement is the fallback")))

(deftest every-catalogued-id-offers-a-ladder
  (doseq [id (keys check/reasons)]
    (let [ladder (grammar/recovery id)]
      (is (seq ladder) (str id " offers at least one recovery"))
      (is (= :keep-interpreted (last ladder))
          (str id " ends in the rung that is always available"))
      (doseq [rung ladder]
        (is (string? (get grammar/recoveries rung))
            (str id " — " rung " is a rung of the closed roster"))))))

(deftest a-finding-is-five-fields
  (is (= {:id       :rf.ui.compile/markup-returning-map
          :source   {:line 47 :column 5}
          :form     '(render-person person)
          :reason   :markup-hidden-from-analyzer
          :recovery [:make-template-visible
                     :extract-declared-child
                     :keep-interpreted]}
         (check/finding :rf.ui.compile/markup-returning-map
                        '(render-person person)
                        {:line 47 :column 5}))
      "the id, where, what, why, and the ladder — and nothing else; a
       message would be stable in meaning and never in bytes"))

(deftest eligibility-is-derived-and-cannot-be-asserted
  (testing "`:compile-eligible?` is exactly \"no findings\", computed HERE
            rather than passed in. That is what makes a green mean
            something: a report cannot claim eligibility while carrying a
            reason it is not, because the claim is not a field anyone gets
            to fill in. Host-neutral, so a ClojureScript tool assembling a
            report from facts it received over the wire is bound by the
            same rule the JVM checker is."
    (let [identity' {:view-id  :app.people/people-list
                     :source   {:file "src/app/people.cljc" :line 42 :column 1}
                     :lowering :interpreted}
          finding   (check/finding :rf.ui.compile/markup-returning-map
                                   '(map render-person people)
                                   {:line 47 :column 5})]
      (is (= {:view-id           :app.people/people-list
              :source            {:file "src/app/people.cljc" :line 42 :column 1}
              :current-lowering  :interpreted
              :target-grammar    grammar/version
              :compile-eligible? true
              :findings          []}
             (check/report (assoc identity' :findings [])))
          "no findings — eligible, and the report is six fields, whole")
      (is (false? (:compile-eligible? (check/report (assoc identity' :findings [finding]))))
          "one finding — NOT eligible")
      (is (false? (:compile-eligible?
                   (check/report (assoc identity' :findings [finding]
                                        :compile-eligible? true))))
          "and a caller who tries to say otherwise is ignored: the key is
           derived from the findings, so there is no green to assert past a
           reason")
      (is (= [finding] (:findings (check/report (assoc identity' :findings [finding]))))
          "the findings ride verbatim — a report that dropped them would be
           eligible-shaped without being eligible"))))

(deftest a-report-derives-its-own-eligibility
  (let [source {:file "src/app/people.cljc" :line 42 :column 1}]
    (testing "no findings is what eligible MEANS"
      (is (= {:view-id           :app.people/people-list
              :source            source
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? true
              :findings          []}
             (check/report {:view-id  :app.people/people-list
                            :source   source
                            :lowering :interpreted
                            :findings nil}))))

    (testing "a report cannot claim eligibility while carrying a reason it is not"
      (let [finding (check/finding :rf.ui.compile/markup-returning-map
                                   '(render-person person)
                                   {:line 47 :column 5})]
        (is (= {:view-id           :app.people/people-list
                :source            source
                :current-lowering  :interpreted
                :target-grammar    :re-frame.freehand/v1
                :compile-eligible? false
                :findings          [finding]}
               (check/report {:view-id  :app.people/people-list
                              :source   source
                              :lowering :interpreted
                              :findings [finding]})))))

    (testing "the grammar checked against is the report's own field, never
              something a caller supplies"
      (is (= grammar/version
             (:target-grammar (check/report {:view-id  :app/x
                                             :source   source
                                             :lowering :compiled
                                             :findings []})))))))
