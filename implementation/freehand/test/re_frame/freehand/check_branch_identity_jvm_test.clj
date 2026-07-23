(ns re-frame.freehand.check-branch-identity-jvm-test
  "A report must be true of the declaration it names.

  `check-conditional-placement-jvm-test` pins that a `v/defview` written under
  a top-level reader conditional is DISCOVERED. Discovery assumed the two
  branches were one declaration and settled identity by preferring the `:clj`
  branch — which is right when the branches agree about which view they
  declare, and produces the worst answer a checker can give when they do not:
  a finding drawn from the CLJS branch's body, labelled with the CLJ branch's
  view id, anchored at the CLJ branch's line. The declaration that failed
  never appeared; the one that was named is fine. That is not an unsupported
  shape reported as unsupported — it is confident misattribution, and an
  author reading it edits the wrong view.

  Four things are asserted:

    1. a real reader (`clojure.tools.reader`, independent of the code under
       test) really does see two DIFFERENT declarations here, one per target —
       so the refusal is about the source and not about the checker's walk;
    2. `check-file` refuses the divergent identity, naming both view ids and
       the recovery, and never emits a report labelled with one branch's id
       carrying the other's body;
    3. the recovery works: split into two one-armed conditionals, each view is
       discovered on the target that declares it and reported as ITSELF —
       including the browser-only view the JVM reader elides;
    4. identity is the view id AND the declared lowering, because a report
       carries one `:current-lowering` too;

  and, for the same-name declarations that are still one declaration, that a
  finding from the CLJS branch anchors at the CLJS branch's line rather than
  the CLJ branch's."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.check-branch-identity-split-views]
            [re-frame.freehand.check-branch-identity-views]
            [re-frame.freehand.check-conditional-placement-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private divergent-resource
  "re_frame/freehand/check_branch_identity_views.cljc")

(def ^:private divergent-path
  (.getPath (io/file (io/resource divergent-resource))))

(def ^:private split-path
  (.getPath (io/file (io/resource "re_frame/freehand/check_branch_identity_split_views.cljc"))))

(def ^:private placement-path
  (.getPath (io/file (io/resource "re_frame/freehand/check_conditional_placement_views.cljc"))))

(defn- thrown-by [f]
  (try (f) nil (catch Throwable t t)))

(defn- report-for [view-id reports]
  (first (filter #(= view-id (:view-id %)) reports)))

;; ---------------------------------------------------------------------------
;; 1 — the source really does declare two different views
;; ---------------------------------------------------------------------------

(defn- declared-names-for
  "The `v/defview` names a REAL reader finds in `resource`, reading it for
  `features`. `clojure.tools.reader` honours `:features` exactly — it has no
  unconditional platform feature — so this is what each target's compile
  actually declares, established without the checker."
  [resource features]
  (let [r (rt/indexing-push-back-reader (slurp (io/resource resource)))]
    (loop [acc []]
      (let [form (rdr/read {:eof ::eof :read-cond :allow :features features} r)]
        (cond
          (= ::eof form)                               acc
          (and (seq? form) (= 'v/defview (first form))) (recur (conj acc (second form)))
          :else                                        (recur acc))))))

(deftest the-branches-declare-different-views
  (testing "one form, two declarations — the JVM build gets jvm-view and the
            browser build gets browser-view, and no single report names both"
    (is (= '[jvm-view]     (declared-names-for divergent-resource #{:clj})))
    (is (= '[browser-view] (declared-names-for divergent-resource #{:cljs})))))

;; ---------------------------------------------------------------------------
;; 2 — check-file refuses rather than misattributing
;; ---------------------------------------------------------------------------

(deftest a-divergent-identity-is-refused
  (let [ex (thrown-by #(check/check-file divergent-path))]
    (is (instance? clojure.lang.ExceptionInfo ex)
        "a refusal — not a report. Before this, check-file answered one report:
         :.../jvm-view, ineligible, offending form [tag \"browser view\"] — a
         body jvm-view does not have, at a line browser-view is not on")

    (testing "the sentence names the checker, the file, what diverged, and the
              recovery"
      (let [msg (ex-message ex)]
        (is (re-find #"^re-frame\.freehand check: " msg))
        (is (.contains msg divergent-path))
        (is (.contains msg ":view-id") "what diverged is named")
        (is (.contains msg "check-branch-identity-views/jvm-view"))
        (is (.contains msg "check-branch-identity-views/browser-view")
            "and BOTH declarations are named — the browser one was previously
             invisible in every answer the checker gave about this file")
        (is (.contains msg "own top-level conditional") "the recovery is the payload")))

    (testing "and the data carries each branch as itself, so a tool can render
              both without re-reading the file"
      (is (= :re-frame.freehand.check-branch-identity-views/jvm-view
             (get-in (ex-data ex) [:clj :view-id])))
      (is (= :re-frame.freehand.check-branch-identity-views/browser-view
             (get-in (ex-data ex) [:cljs :view-id])))
      (is (= 25 (get-in (ex-data ex) [:clj :source :line])))
      (is (= 31 (get-in (ex-data ex) [:cljs :source :line]))
          "the browser declaration's own line, which no previous answer carried")
      (is (= divergent-path (:file (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; 3 — the recovery the refusal names actually answers
;; ---------------------------------------------------------------------------

(deftest split-declarations-are-each-reported-as-themselves
  (let [reports (check/check-file split-path)]
    (is (= [:re-frame.freehand.check-branch-identity-split-views/jvm-only
            :re-frame.freehand.check-branch-identity-split-views/browser-only]
           (mapv :view-id reports))
        "both declarations, in declaration order, each under its own id —
         including the browser-only one the JVM reader elides entirely")

    (testing "the JVM-only view is inside the grammar and says nothing"
      (is (= {:compile-eligible? true :findings []}
             (select-keys (report-for :re-frame.freehand.check-branch-identity-split-views/jvm-only
                                      reports)
                          [:compile-eligible? :findings]))))

    (testing "and the browser-only view is refused as ITSELF, at its own line,
              for its own body — the answer the divergent form could not give"
      (is (= {:view-id           :re-frame.freehand.check-branch-identity-split-views/browser-only
              :source            {:file split-path :line 26 :column 4}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? false
              :findings          [{:id       :rf.ui.compile/dynamic-head
                                   :source   {:line 26 :column 4}
                                   :form     '[tag "browser only"]
                                   :reason   :head-is-a-runtime-value
                                   :recovery [:use-a-literal-head
                                              :extract-declared-child
                                              :keep-interpreted]}]}
             (report-for :re-frame.freehand.check-branch-identity-split-views/browser-only
                         reports))))))

;; ---------------------------------------------------------------------------
;; 4 — identity is the id AND the declared lowering
;; ---------------------------------------------------------------------------

(def ^:private declaration-for
  "Discovery for one top-level form, reached directly: a lowering divergence
  refuses for the same reason a name divergence does, and a report cannot
  carry two `:current-lowering` values any more than it can carry two ids."
  #'check/declaration-for)

(deftest a-divergent-lowering-is-refused-too
  (let [ns-obj (find-ns 're-frame.freehand.check-branch-identity-views)
        form   (read-string {:read-cond :preserve}
                            (str "#?(:clj  (v/defview thing {:compiled true} [_] [:div \"x\"])"
                                 "   :cljs (v/defview thing [_] [:div \"x\"]))"))
        ex     (thrown-by #(declaration-for "identity.cljc" 'app.identity ns-obj form))]
    (is (instance? clojure.lang.ExceptionInfo ex))
    (is (.contains (ex-message ex) ":compiled? true vs false")
        "the report would otherwise claim :current-lowering :compiled for a
         browser branch that declares nothing of the sort"))

  (testing "and agreement is not refused: same id, same lowering, different
            body is one declaration and checks fine"
    (let [ns-obj (find-ns 're-frame.freehand.check-branch-identity-views)
          form   (read-string {:read-cond :preserve}
                              (str "#?(:clj  (v/defview thing [_] [:div \"jvm\"])"
                                   "   :cljs (v/defview thing [_] [:span \"spa\"]))"))
          decl   (declaration-for "identity.cljc" 'app.identity ns-obj form)]
      (is (= :app.identity/thing (:view-id decl)))
      (is (= '([:span "spa"]) (get-in decl [:cljs-branch :body]))))))

;; ---------------------------------------------------------------------------
;; The same-name case: one declaration, two branches, truthful anchors
;; ---------------------------------------------------------------------------

(deftest a-cljs-finding-anchors-at-the-cljs-branch
  (testing "branches that agree about identity ARE one declaration, and stay
            one report — but a finding from the CLJS branch is anchored where
            that branch is written (line 69), not where the report identity
            comes from (line 65). Sending an author to the branch that did not
            fail is the same misdirection in a smaller form"
    (let [report (report-for :re-frame.freehand.check-conditional-placement-views/declaration-divergent
                             (check/check-file placement-path))]
      (is (= 65 (-> report :source :line)) "the declaration's identity anchor")
      (is (= 69 (-> report :findings first :source :line))
          "and the CLJS branch's own line for the CLJS branch's refusal")
      (is (= '[tag {} "the CLJS branch has a dynamic head - ineligible"]
             (-> report :findings first :form))))))

;; ---------------------------------------------------------------------------
;; Read-only
;; ---------------------------------------------------------------------------

(deftest the-checker-never-writes
  (testing "refusing and reporting are both read-only — every source pointed
            at is byte-identical afterwards"
    (doseq [path [divergent-path split-path]]
      (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
            _      (thrown-by #(check/check-file path))
            after  (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
        (is (pos? (alength before)) (str path " is not empty"))
        (is (java.util.Arrays/equals ^bytes before ^bytes after))))))
