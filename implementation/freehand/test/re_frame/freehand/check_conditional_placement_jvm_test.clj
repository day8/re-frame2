(ns re-frame.freehand.check-conditional-placement-jvm-test
  "The checker must answer for the browser build's branch wherever the
  conditional was WRITTEN, not only where it is easiest to walk.

  `check-host-divergent-jvm-test` pins the selection itself: the checker
  reads with conditionals preserved and picks the `:cljs` branch past the
  JVM reader's unconditional `:clj` platform feature. This suite pins its
  REACH. A conditional is legal anywhere a form is, and two placements are
  invisible to a walk that knows only maps, vectors and seqs — inside a set,
  and around the whole declaration. Both were false-greens of the same kind
  the selection exists to prevent, in new syntactic positions:

    * a conditional inside a set survived the walk intact, so the analyzer
      saw a reader object where the browser build has `#{v/sub}`, and the
      view reported ELIGIBLE;
    * a declaration written under `#?(:clj (v/defview …) :cljs (v/defview
      …))` is not a seq once preserved, so discovery skipped it and the file
      reported NOTHING about it — the loudest false-green there is, since an
      author reads silence as \"no findings\".

  Four things are asserted:

    1. every placement is discovered and checked, whole report per
       declaration, in declaration order;
    2. the refusal names the CLJS-branch form — proof the branch was
       analyzed rather than the one the JVM reader elides to — and each
       placement's UNIFORM control stays eligible, so covering a placement
       cannot manufacture a false NO;
    3. the selected branches are what a REAL reader told to read for each
       target produces (`clojure.tools.reader` honours `:features` exactly,
       unlike the JVM reader), so the hand-selection is checked against an
       independent oracle rather than against itself;
    4. resolution rebuilds a set AS a set, with its metadata;
    5. the fixture is byte-identical after a run — the read-only law holds
       for the widened walk too."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.check-conditional-placement-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private fixture-resource
  "re_frame/freehand/check_conditional_placement_views.cljc")

(def ^:private fixture-path
  (.getPath (io/file (io/resource fixture-resource))))

(defn- report-for [view-id reports]
  (first (filter #(= view-id (:view-id %)) reports)))

;; ---------------------------------------------------------------------------
;; 1 — every placement is discovered and checked
;; ---------------------------------------------------------------------------

(deftest every-placement-is-discovered-and-checked
  (let [reports (check/check-file fixture-path)]
    (is (= [:re-frame.freehand.check-conditional-placement-views/set-divergent
            :re-frame.freehand.check-conditional-placement-views/set-splice-divergent
            :re-frame.freehand.check-conditional-placement-views/set-uniform
            :re-frame.freehand.check-conditional-placement-views/declaration-divergent
            :re-frame.freehand.check-conditional-placement-views/declaration-uniform]
           (mapv :view-id reports))
        "one report per declaration, in declaration order — INCLUDING the two
         written under a top-level reader conditional, which a discovery test
         applied to the preserved form skips entirely")

    (testing "a bare v/sub selected out of a set is a reactive read the manifest
              cannot own, so the view is ineligible"
      (is (= {:view-id           :re-frame.freehand.check-conditional-placement-views/set-divergent
              :source            {:file fixture-path :line 41 :column 1}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? false
              :findings          [{:id       :rf.ui.compile/unsupported-form
                                   :source   {:line 41 :column 1}
                                   :form     'v/sub
                                   :reason   :form-outside-the-grammar
                                   :recovery [:make-template-visible
                                              :pass-computed-value
                                              :extract-declared-child
                                              :keep-interpreted]}]}
             (report-for :re-frame.freehand.check-conditional-placement-views/set-divergent
                         reports))))

    (testing "and the same through a SPLICING conditional, which contributes its
              branch's forms as siblings rather than as one form"
      (is (= {:view-id           :re-frame.freehand.check-conditional-placement-views/set-splice-divergent
              :source            {:file fixture-path :line 47 :column 1}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? false
              :findings          [{:id       :rf.ui.compile/unsupported-form
                                   :source   {:line 47 :column 1}
                                   :form     'v/sub
                                   :reason   :form-outside-the-grammar
                                   :recovery [:make-template-visible
                                              :pass-computed-value
                                              :extract-declared-child
                                              :keep-interpreted]}]}
             (report-for :re-frame.freehand.check-conditional-placement-views/set-splice-divergent
                         reports))))

    (testing "a declaration written under a top-level conditional is reported,
              and its CLJS branch's head is bound from THAT branch's params —
              analyzing it against the :clj branch's params would refuse it as
              an unresolved head, which is the wrong diagnosis. The finding
              anchors at the CLJS branch's own line 69, not the :clj branch's
              65 that the report identity carries"
      (is (= {:view-id           :re-frame.freehand.check-conditional-placement-views/declaration-divergent
              :source            {:file fixture-path :line 65 :column 10}
              :current-lowering  :interpreted
              :target-grammar    :re-frame.freehand/v1
              :compile-eligible? false
              :findings          [{:id       :rf.ui.compile/dynamic-head
                                   :source   {:line 69 :column 10}
                                   :form     '[tag {} "the CLJS branch has a dynamic head - ineligible"]
                                   :reason   :head-is-a-runtime-value
                                   :recovery [:use-a-literal-head
                                              :extract-declared-child
                                              :keep-interpreted]}]}
             (report-for :re-frame.freehand.check-conditional-placement-views/declaration-divergent
                         reports))))))

;; ---------------------------------------------------------------------------
;; 2 — the CLJS branch is what was analyzed, and no placement invents a NO
;; ---------------------------------------------------------------------------

(deftest covering-a-placement-never-manufactures-a-refusal
  (testing "each placement's control — branches that read DIFFERENTLY yet are
            both inside the grammar — stays eligible with nothing to say"
    (let [reports (check/check-file fixture-path)]
      (doseq [view-id [:re-frame.freehand.check-conditional-placement-views/set-uniform
                       :re-frame.freehand.check-conditional-placement-views/declaration-uniform]]
        (is (= {:compile-eligible? true :findings []}
               (select-keys (report-for view-id reports) [:compile-eligible? :findings]))
            (str view-id " is genuinely eligible on both targets"))))))

(deftest the-refusal-names-the-cljs-branch-form
  (testing "non-vacuity: every refusal here names a form that exists ONLY in the
            `:cljs` branch. Before the walk covered these placements the set
            views reported eligible with no findings at all, and the two
            declarations under a conditional were not reported at all"
    (let [reports (check/check-file fixture-path)]
      (is (= ['v/sub 'v/sub '[tag {} "the CLJS branch has a dynamic head - ineligible"]]
             (->> reports
                  (remove :compile-eligible?)
                  (mapv #(-> % :findings first :form))))
          "the CLJS-branch forms — never the :clj branch's 1, #{1 2} or [:div …]")
      (is (= 5 (count reports))
          "and silence is not an answer: every declaration in the file is
           reported, however it was written"))))

;; ---------------------------------------------------------------------------
;; 3 — the selection agrees with a real reader
;; ---------------------------------------------------------------------------

(defn- declarations-read-for
  "Every `v/defview` form in the fixture, as a REAL reader reads the file for
  `features`. `clojure.tools.reader` honours the `:features` it is handed
  exactly — it has no unconditional platform feature — so it produces the
  `:cljs` forms the browser build compiles, and is an oracle independent of
  the checker's own hand-selection."
  [features]
  (let [r (rt/indexing-push-back-reader (slurp (io/resource fixture-resource)))]
    (loop [acc []]
      (let [form (rdr/read {:eof ::eof :read-cond :allow :features features} r)]
        (cond
          (= ::eof form)                             acc
          (and (seq? form) (= 'v/defview (first form))) (recur (conj acc form))
          :else                                      (recur acc))))))

(defn- sets-in [form]
  (filterv set? (tree-seq coll? seq form)))

(defn- named [vname forms]
  (first (filter #(= vname (second %)) forms)))

(deftest the-selected-branches-are-the-readers-own
  (let [clj-forms  (declarations-read-for #{:clj})
        cljs-forms (declarations-read-for #{:cljs})]

    (testing "the real reader finds the same five declarations on either target,
              resolving the top-level conditionals into plain declarations —
              which is what the checker's discovery must match"
      (is (= '[set-divergent set-splice-divergent set-uniform
               declaration-divergent declaration-uniform]
             (mapv second clj-forms)
             (mapv second cljs-forms))))

    (testing "the sets the reader builds per target are the sets the checker
              analyzed: constants on the JVM, the bare v/sub the checker
              refused in the browser"
      (is (= [#{1}] (sets-in (named 'set-divergent clj-forms))))
      (is (= ['#{v/sub}] (sets-in (named 'set-divergent cljs-forms))))
      (is (= [#{1 2}] (sets-in (named 'set-splice-divergent clj-forms))))
      (is (= ['#{v/sub}] (sets-in (named 'set-splice-divergent cljs-forms))))
      (is (= [#{1} #{3 4}] (sets-in (named 'set-uniform clj-forms))))
      (is (= [#{2} #{5}] (sets-in (named 'set-uniform cljs-forms)))))

    (testing "and a declaration under a conditional is TWO declarations to the
              reader — different params, different body — which is why the CLJS
              branch is analyzed against its own params"
      (let [clj-decl  (named 'declaration-divergent clj-forms)
            cljs-decl (named 'declaration-divergent cljs-forms)]
        (is (= '[_] (nth clj-decl 3)))
        (is (= '[{:keys [tag]}] (nth cljs-decl 3)))
        (is (= '[tag {} "the CLJS branch has a dynamic head - ineligible"]
               (last cljs-decl))
            "the form the checker reported is the one this reader produced")))))

;; ---------------------------------------------------------------------------
;; 4 — a set comes back a set
;; ---------------------------------------------------------------------------

(def ^:private resolve-conditionals
  "The checker's branch selection, reached directly: set-ness and metadata
  survive resolution, which no report can observe because both readings are
  inside the grammar."
  #'check/resolve-conditionals)

(deftest resolution-rebuilds-a-set-as-a-set
  (let [form (vary-meta (read-string {:read-cond :preserve}
                                     "#{#?(:clj 1 :cljs 2) #?@(:clj [3] :cljs [4 5])}")
                        assoc ::marker true)]
    (is (= #{1 3} (resolve-conditionals form #{:clj})))
    (is (= #{2 4 5} (resolve-conditionals form #{:cljs}))
        "the splicing conditional contributes its branch's forms as members")
    (is (set? (resolve-conditionals form #{:cljs}))
        "a set, not the vector the sibling walk assembles")
    (is (= {::marker true} (meta (resolve-conditionals form #{:cljs})))
        "and the collection's metadata rides through the rebuild")))

;; ---------------------------------------------------------------------------
;; 5 — read-only
;; ---------------------------------------------------------------------------

(deftest the-checker-never-writes
  (testing "the widened walk is still read-only — the source it was pointed at
            is byte-identical after a run"
    (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-path)))
          _      (check/check-file fixture-path)
          after  (java.nio.file.Files/readAllBytes (.toPath (io/file fixture-path)))]
      (is (pos? (alength before)) "the fixture file is not empty")
      (is (java.util.Arrays/equals ^bytes before ^bytes after)))))
