(ns re-frame.freehand.check-public-verb-jvm-test
  "`v/check` — the checker's PUBLISHED door, and the question a published
  checker has to survive: can it fail?

  `re-frame.freehand.check-jvm-test` proves the analysis. This suite
  proves the verb, and it is a different obligation. A checker is the one
  kind of tool whose failure mode is silence: a green that could never
  have been red reads exactly like a green that was earned, and an author
  who trusted it would carry that trust into a build that then refuses.
  So nothing here asserts that the checker is right about a body — that
  is next door — and everything here asserts that the PUBLISHED verb
  cannot quietly become vacuous.

  Four claims, and each is a way the verb could lie:

  1. **It is thin.** `v/check` answers EXACTLY what the checker answers.
     A projection that re-derived any part of a verdict would be a second
     checker, free to drift from the one the analysis lives in.
  2. **It can fail.** Over one real file the verb produces BOTH verdicts,
     in exact counts — so a verb that could only ever green, only ever
     red, or green over nothing at all, reds here.
  3. **It agrees with the BUILD.** The refusal `v/check` reports for a
     body is the id the compiler refuses that same body with, and the
     body it calls eligible is one the compiler accepts. A preflight that
     disagreed with the flight is worse than no preflight.
  4. **It refuses rather than greens.** A source with no answer reaches
     the caller as a refusal, never as an empty vector — which is what an
     eligible file also looks like.

  Claim 3 reads the fixture's declarations back OUT of the file rather
  than copying them here, because \"the same source through two front
  doors\" is the whole of the claim and a copy is a different source."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.check-fixture-views]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.check :as check])
  (:import [clojure.lang LineNumberingPushbackReader]))

(def ^:private fixture-ns
  "The namespace whose declarations are the subject. Named literally: heads
  are classified by resolution against the namespace a declaration LIVES
  in, never against the runner's."
  're-frame.freehand.check-fixture-views)

(def ^:private fixture-path
  "Located through the classpath so no path is written down."
  (.getPath (io/file (io/resource "re_frame/freehand/check_fixture_views.cljc"))))

(defn- report-for [view-id reports]
  (first (filter #(= view-id (:view-id %)) reports)))

;; ---------------------------------------------------------------------------
;; 1 — the projection is thin
;; ---------------------------------------------------------------------------

(deftest the-published-verb-is-the-checker-and-not-a-second-one
  (testing "`v/check` answers EXACTLY what `check-file` answers, whole. The
            door publishes the checker; it does not summarise, re-rank or
            re-derive it, and a second judgment on this surface would be a
            second grammar to keep in step."
    (is (= (check/check-file fixture-path) (v/check fixture-path)))))

(deftest the-verb-is-published-as-a-supported-surface
  (let [var' (resolve 're-frame.freehand/check)]
    (is (some? var') "`v/check` resolves on the door")
    (is (not (:macro (meta var'))) "a function, so a tool can pass it around")
    (is (not (:no-doc (meta var')))
        "and it is DOCUMENTED public, not an internal carve-out — the
         api-manifest generator skips `^:no-doc`, so this is also what puts
         the verb on the published roster")
    (is (= '([path]) (:arglists (meta var')))
        "one arity: a source file. The discovery unit is the FILE, because
         `which of these views are already inside the grammar` is the
         question an author actually has")
    (is (string? (:doc (meta var'))))))

;; ---------------------------------------------------------------------------
;; 2 — a pass can fail
;; ---------------------------------------------------------------------------
;;
;; The counts are EXACT and both verdicts are required. A checker greened
;; over zero declarations, one that greens everything, and one that reds
;; everything all produce an "empty problem list" a caller cannot tell from
;; an earned one — so the fixture's shape is asserted, not just its silence.

(deftest the-verb-produces-both-verdicts-over-one-real-file
  (let [reports (v/check fixture-path)]
    (is (= 2 (count reports))
        "non-vacuous: the subject file really does carry declarations, and a
         run that found none would otherwise report an empty problem list")
    (is (= 1 (count (filter :compile-eligible? reports)))
        "exactly one view is inside the grammar — a verb that reds
         everything fails here")
    (is (= 1 (count (remove :compile-eligible? reports)))
        "and exactly one is outside it — a verb that greens everything fails
         here, which is the failure a checker cannot self-report")))

(deftest a-refusal-carries-everything-an-author-acts-on
  (testing "the finding is the payload, and its recovery ladder is the part
            an author can actually do something with"
    (let [report (report-for (keyword (str fixture-ns) "roster") (v/check fixture-path))
          [finding & more] (:findings report)]
      (is (false? (:compile-eligible? report)))
      (is (empty? more)
          "one refusal at a time — the analyzer stops where the build stops")
      (is (qualified-keyword? (:id finding)) "a stable machine discriminator")
      (is (integer? (:line (:source finding)))
          "located at the offending form, not merely somewhere in the file")
      (is (some? (:form finding)) "and the form itself, as read")
      (is (simple-keyword? (:reason finding)))
      (is (seq (:recovery finding)) "the ladder is never empty")
      (is (= :keep-interpreted (last (:recovery finding)))
          "and its last rung is always available: declining to compile is a
           first-class answer, because the body already runs interpreted"))))

;; ---------------------------------------------------------------------------
;; 3 — the preflight agrees with the flight
;; ---------------------------------------------------------------------------

(defn- fixture-declarations
  "Every `v/defview` in the fixture file, as `{:view-id :vname :params
  :body :form}`.

  The suite's own read, and deliberately trivial — no branch selection, no
  discovery rules, no classification. What it is for is that claim 3 says
  ONE source text answers the same way through two front doors, and a body
  copied into this file would be a second text that could drift from the
  one `v/check` was pointed at."
  []
  (with-open [rdr (LineNumberingPushbackReader. (io/reader (io/file fixture-path)))]
    (binding [*read-eval* false]
      (loop [out []]
        (let [form (read {:eof ::eof :read-cond :allow} rdr)]
          (if (= ::eof form)
            out
            (recur
             (if (and (seq? form) (= 'v/defview (first form)))
               (let [[_ vname & more] form
                     {:keys [params body]} (v/parse-defview-args more)]
                 (conj out {:view-id (keyword (str fixture-ns) (str vname))
                            :vname   vname
                            :params  params
                            :body    body
                            :form    form}))
               out))))))))

(defn- build-verdict
  "What the COMPILER makes of `declaration` — `:accepted`, or the
  `:rf.ui.compile/*` id it refuses the body with. The real front end, the
  one `{:compiled true}` runs, driven exactly as the macro drives it."
  [{:keys [vname view-id params body form]}]
  (try
    (compiler/compile-structural-view
     {:form            form
      :menv            nil
      :ns-sym          fixture-ns
      :vname           vname
      :view-id         view-id
      :params          params
      :body            body
      :children-policy :optional})
    :accepted
    (catch clojure.lang.ExceptionInfo ex
      (or (:rf.ui.compile/error (ex-data ex))
          (throw ex)))))

(deftest the-verb-and-the-build-agree-on-the-same-source
  (testing "for every declaration in one file, the answer `v/check` gives
            BEFORE the marker is added is the answer the build gives after
            it. A preflight that disagreed with the flight would send an
            author to refactor a body the compiler would have taken, or
            promise one it then refuses."
    (let [declarations (fixture-declarations)
          reports      (v/check fixture-path)]
      (is (= 2 (count declarations))
          "non-vacuous: the read found the declarations to compare")
      (is (= (mapv :view-id declarations) (mapv :view-id reports))
          "and the two doors are looking at the same declarations, in the
           same order")
      (doseq [{:keys [view-id] :as declaration} declarations]
        (let [report  (report-for view-id reports)
              verdict (build-verdict declaration)]
          (if (:compile-eligible? report)
            (is (= :accepted verdict)
                (str view-id " — reported eligible, and the build takes it"))
            (is (= (:id (first (:findings report))) verdict)
                (str view-id " — reported ineligible, and the build refuses"
                     " it with the SAME id, not merely with some id"))))))))

;; ---------------------------------------------------------------------------
;; 4 — a source with no answer refuses, and the door still never writes
;; ---------------------------------------------------------------------------

(deftest a-source-with-no-answer-refuses-through-the-door
  (testing "an empty findings vector is what an ELIGIBLE file looks like, so
            a source the checker cannot answer for must not produce one.
            The refusal reaches the caller as a throw."
    (let [tmp (java.io.File/createTempFile "fh-public-check" ".cljs")]
      (try
        (spit tmp "(ns re-frame.freehand.check-public-verb-probe)\n")
        (let [ex (try (v/check (.getPath tmp))
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (instance? clojure.lang.ExceptionInfo ex)
              "a .cljs source is refused, never reported as `[]`")
          (is (= :cljs (:target (ex-data ex)))
              "and the refusal is the CLJS-target one, discriminable as data"))
        (finally (.delete tmp))))))

(deftest the-published-verb-never-writes
  (testing "read-only is a property to be proved, not asserted — and it is
            the published door that has to prove it, since that is the one
            an author points at source they have not decided to change"
    (let [file   (io/file fixture-path)
          before (java.nio.file.Files/readAllBytes (.toPath file))
          _      (v/check fixture-path)
          after  (java.nio.file.Files/readAllBytes (.toPath file))]
      (is (pos? (alength before)) "the subject file is not empty")
      (is (java.util.Arrays/equals ^bytes before ^bytes after)))))
