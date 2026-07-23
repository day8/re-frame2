(ns re-frame.freehand.check-splice-branch-jvm-test
  "A green from the checker must mean the target can READ the branch — a `#?@`
  splice included.

  The checker reads with conditionals preserved and selects each target's
  branch itself, then splices a matched `#?@` by sequencing its branch. But a
  real reader splices ONLY a list or vector: it copies the branch's members
  into the surrounding form and requires the branch to implement
  `java.util.List`, refusing a set or map with \"Spliced form list in
  read-cond-splicing must implement java.util.List.\" The shared splice arm
  called `(seq branch)` regardless — sequencing a set into its members and a
  map into its entries — so a declaration whose `:cljs` `#?@` selects a set or
  map was resolved, analyzed, and reported `:compile-eligible? true` even though
  the browser build's reader cannot read the file.

  Getting this right means pinning BOTH directions, because a rule that only
  reproduces the refusals is a rule nothing has contradicted: a `#?@` whose
  selected branch is a list or vector must stay spliced, and one whose OTHER
  branch is a set or map — the one this target does not select — reads perfectly
  well. So the load-bearing assertion here is an EQUIVALENCE against an
  independent reader (`clojure.tools.reader`, which honours `:features` exactly,
  splices before it validates, and has no unconditional platform feature): over
  a table of shapes, checker and reader must agree on WHETHER each target can
  read the splice and, when it can, on WHAT it reads — and the table must
  contain a substantial number of each verdict.

  Five things are asserted:

    1. checker selection and a real target reader agree in both directions,
       over a table that is refused eight times and accepted sixteen;
    2. `check-file` — the public surface — refuses the SET-valued and the
       MAP-valued selected splice from the bead rather than reporting eligible,
       naming the file, the branch, the target and the recovery, and an
       independent reader proves the same verdict;
    3. valid list/vector splices stay eligible;
    4. the refusal is target-symmetric, and an invalid branch the target does
       NOT select reads, because that is what the real reader does with it;
    5. the checker is still read-only, including after a REFUSED run.

  The refused fixtures are written to a temp file by this suite rather than
  committed: their `:cljs` branch is source the browser build's reader refuses,
  so a committed fixture carrying one could not be compiled for CLJS."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.check-splice-branch-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private uniform-path
  (.getPath (io/file (io/resource "re_frame/freehand/check_splice_branch_views.cljc"))))

(def ^:private resolve-conditionals
  "The checker's branch selection, reached directly — the splice arm answers per
  target, and a refusal raised here carries no file yet (`check-file` adds it),
  so the bare sentence is what these assertions see."
  #'check/resolve-conditionals)

(defn- thrown-by [f]
  (try (f) nil (catch Throwable t t)))

;; ---------------------------------------------------------------------------
;; 1 — the boundary is the target reader's own, in both directions
;; ---------------------------------------------------------------------------

(def ^:private shapes
  "Splice literals spanning what conditional selection can do to a `#?@`: select
  a SET or a MAP the reader cannot splice, select an invalid branch on the JVM
  instead of the browser, select an invalid branch nested inside an attribute
  set, hit an invalid branch through `:default` — and, just as load-bearing, the
  shapes that read on every target: a divergent vector, a divergent list, a
  vector spliced into a set, a one-armed splice that simply elides for the other
  target, and a collection with no splice in it at all."
  ["#{#?@(:clj [:a] :cljs #{:b :c})}"
   "[:x #?@(:clj [:a] :cljs {:b 1})]"
   "[#?@(:clj #{:a} :cljs [:b])]"
   "[#?@(:clj {:a 1} :cljs [:b])]"
   "[:div {:class #{#?@(:clj [:a] :cljs #{:b})}}]"
   "[#?@(:clj [:a :b] :cljs [:c])]"
   "[#?@(:clj (:a :b) :cljs (:c))]"
   "[#?@(:clj [:a] :cljs #{:b})]"
   "#{#?@(:clj [:a] :cljs [:b :c])}"
   "[#?@(:default #{:a})]"
   "[#?@(:clj [:a])]"
   "[:plain :members]"])

(defn- read-for
  "What a REAL reader told to read for `features` makes of `src` — `{:read …}`
  or `{:refused message}`. `clojure.tools.reader` honours `:features` exactly,
  splices before it validates the branch, and has no unconditional platform
  feature, so this is the independent statement of what each target sees."
  [src features]
  (try {:read (rdr/read-string {:read-cond :allow :features features} src)}
       (catch Throwable t {:refused (ex-message t)})))

(defn- select-for
  "The same, through the checker: read with conditionals PRESERVED, then select
  `features`'s branch the way the checker does."
  [src features]
  (try {:read (resolve-conditionals (read-string {:read-cond :preserve} src) features)}
       (catch Throwable t {:refused (ex-message t)})))

(def ^:private verdicts
  (for [src shapes, features [#{:clj} #{:cljs}]]
    {:src src :features features :reader (read-for src features) :checker (select-for src features)}))

(deftest selection-agrees-with-a-real-reader-in-both-directions
  (testing "every shape, for every target: the checker refuses exactly what the
            target's own reader refuses, and reads exactly what it reads"
    (doseq [{:keys [src features reader checker]} verdicts]
      (is (= (contains? reader :read) (contains? checker :read))
          (str src " for " features ": reader says "
               (if (contains? reader :read) "READABLE" (str "REFUSED — " (:refused reader)))
               ", checker says "
               (if (contains? checker :read) "READABLE" (str "REFUSED — " (:refused checker)))))
      (when (and (contains? reader :read) (contains? checker :read))
        (is (= (:read reader) (:read checker))
            (str src " for " features ": the same verdict, a different form")))))

  (testing "and the table is not one-sided — a rule that only reproduced the
            refusals would pass a table with no acceptances in it, and a rule
            that spliced everything would pass a table with no refusals"
    (is (= 8  (count (filter (comp :refused :reader) verdicts))))
    (is (= 16 (count (filter (comp #(contains? % :read) :reader) verdicts))))))

;; ---------------------------------------------------------------------------
;; 2 — check-file refuses instead of sequencing
;; ---------------------------------------------------------------------------

(def ^:private refused
  "The two ways a `#?@` selects a branch the reader cannot splice, as the bead
  reproduced them. Both `:clj` branches read — the vector `[:a]` splices — which
  is why the namespace loads; the refusal is about the `:cljs` branch alone."
  {"set-branch" "[:div {:title (str #{#?@(:clj [:a] :cljs #{:b :c})})} \"x\"]"
   "map-branch" "[:div {:title (str [#?@(:clj [:a] :cljs {:b 1})])} \"x\"]"})

(defn- write-temp-view
  "Write a one-view `.cljc` namespace whose body is `refused`'s `nm`, LOAD it so
  heads resolve, and answer its path."
  [nm]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "check-splice-branch"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (io/file dir (str nm ".cljc"))]
    (.deleteOnExit dir)
    (.deleteOnExit f)
    (spit f (str "(ns re-frame.freehand." nm "-temp-splice-views\n"
                 "  (:require [re-frame.freehand :as v]))\n"
                 "\n"
                 "(v/defview subject\n"
                 "  [_]\n"
                 "  " (get refused nm) ")\n"))
    (load-file (.getPath f))
    (.getPath f)))

(def ^:private temp-view
  "Each shape is written and loaded ONCE, however many assertions point at it."
  (memoize write-temp-view))

(defn- read-file-for
  "Read every form of the file at `path` with a real reader told to read for
  `features`, and answer nil or the exception it threw."
  [path features]
  (let [r (rt/indexing-push-back-reader (slurp path))]
    (thrown-by
     #(loop []
        (when-not (= ::eof (rdr/read {:eof ::eof :read-cond :allow :features features} r))
          (recur))))))

(deftest check-file-refuses-a-splice-the-target-cannot-read
  (testing "a #?@ whose :cljs conditional selects a SET cannot be spliced — the
            reader wants java.util.List — and the checker refuses rather than
            sequencing the set's members. This reported :compile-eligible? true,
            no findings"
    (let [path (temp-view "set-branch")]
      (is (nil? (read-file-for path #{:clj})) "the JVM branch reads: the vector splices")
      (is (re-find #"must implement java.util.List" (str (read-file-for path #{:cljs})))
          "and an independent reader refuses the :cljs branch for the same reason")

      (let [ex (thrown-by #(check/check-file path))]
        (is (instance? clojure.lang.ExceptionInfo ex) "a refusal, not a report")
        (let [msg (ex-message ex)]
          (is (re-find #"^re-frame\.freehand check: " msg))
          (is (.contains msg path) "the refusal names the file")
          (is (.contains msg "selects a SET") "and which kind of branch stopped it")
          (is (.contains msg ":cljs target") "and for which target")
          (is (.contains msg "list or vector") "the recovery is the payload")
          (is (.contains msg "around the WHOLE collection") "and the other recovery too"))
        (is (= :cljs (:target (ex-data ex))))
        (is (= path (:file (ex-data ex))))
        (is (set? (:branch (ex-data ex)))
            "the offending branch rides in the data as authored, so a tool can
             render it without re-reading"))))

  (testing "and a MAP-valued selected splice is refused the same way — a map is
            no more a java.util.List than a set is"
    (let [path (temp-view "map-branch")]
      (is (nil? (read-file-for path #{:clj})) "the JVM branch reads: the vector splices")
      (is (re-find #"must implement java.util.List" (str (read-file-for path #{:cljs}))))

      (let [ex (thrown-by #(check/check-file path))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (.contains (ex-message ex) "selects a MAP"))
        (is (.contains (ex-message ex) ":cljs target"))
        (is (= :cljs (:target (ex-data ex))))
        (is (map? (:branch (ex-data ex)))))))

  (testing "a duplicate is not what stopped it — the branch is refused for its
            KIND, before any member is ever looked at"
    (is (re-find #"selects a SET" (:refused (select-for "[#?@(:clj #{:a :b})]" #{:clj}))))))

;; ---------------------------------------------------------------------------
;; 3 — the valid splice is untouched
;; ---------------------------------------------------------------------------

(deftest a-list-or-vector-splice-stays-eligible
  (testing "splices whose selected branch is a list or vector on every target
            keep splicing and the view stays eligible — refusing the unspliceable
            must not cost the spliceable"
    (is (= [{:view-id           :re-frame.freehand.check-splice-branch-views/splice-branch-uniform
             :compile-eligible? true
             :findings          []}]
           (mapv #(select-keys % [:view-id :compile-eligible? :findings])
                 (check/check-file uniform-path)))))

  (testing "and resolution still splices a vector and a list, member for member"
    (is (= [:a :b] (resolve-conditionals (read-string {:read-cond :preserve} "[#?@(:clj [:a :b])]") #{:clj})))
    (is (= [:a :b] (resolve-conditionals (read-string {:read-cond :preserve} "[#?@(:clj (:a :b))]") #{:clj})))))

;; ---------------------------------------------------------------------------
;; 4 — symmetry, and the branch that is not selected
;; ---------------------------------------------------------------------------

(deftest the-refusal-follows-the-target
  (testing "nothing here is :cljs-specific — the JVM target is refused by the
            same rule when the branch it selects is the set"
    (let [src "[#?@(:clj #{:a} :cljs [:b])]"]
      (is (= [:b] (:read (select-for src #{:cljs}))) "the :cljs vector splices")
      (is (re-find #"selects a SET" (:refused (select-for src #{:clj})))
          "and the :clj set is refused for :clj")))

  (testing "and an invalid branch the target does NOT select is not the target's
            problem — the checker reads it, exactly as the real reader does,
            because that set is elided for this target and never spliced"
    (let [src "[#?@(:clj #{:a} :cljs [:b])]"]
      (is (= [:b] (:read (select-for src #{:cljs}))))
      (is (= [:b] (:read (read-for   src #{:cljs}))) "independently, per clojure.tools.reader"))))

;; ---------------------------------------------------------------------------
;; 5 — read-only
;; ---------------------------------------------------------------------------

(deftest the-checker-never-writes
  (testing "refusing a splice is still read-only — the source pointed at is
            byte-identical afterwards, refused run included"
    (doseq [path [uniform-path (temp-view "set-branch") (temp-view "map-branch")]]
      (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
            _      (thrown-by #(check/check-file path))
            after  (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
        (is (pos? (alength before)) (str path " is not empty"))
        (is (java.util.Arrays/equals ^bytes before ^bytes after))))))
