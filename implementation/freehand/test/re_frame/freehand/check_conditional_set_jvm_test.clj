(ns re-frame.freehand.check-conditional-set-jvm-test
  "A green from the checker must mean the target can READ the branch — sets
  included.

  The checker reads with conditionals preserved and selects each target's
  branch itself, then rebuilds each collection. For a SET that rebuild used
  `into`, which coalesces: two members distinct as authored can select the SAME
  value for one target, and `#{#?(:clj :a :cljs :b) :b}` — which reads as
  `#{:a :b}` for `:clj` and does not read at all for `:cljs` — was normalized
  to `#{:b}` and the view reported `:compile-eligible? true` with no findings.
  That is the map's duplicate-key bug in the adjacent arm, and the same false
  green: a report about a set nobody wrote.

  Getting this right means pinning BOTH directions, because a rule that only
  reproduces the refusals is a rule nothing has contradicted. A set is more
  permissive than a map, and a naive \"any conditional that elides is
  suspicious\" rule would have shipped a FALSE RED on `#{#?(:clj :a) :b}`,
  which reads perfectly well as `#{:b}` for `:cljs`. So the load-bearing
  assertion here is an EQUIVALENCE against an independent reader
  (`clojure.tools.reader`, which honours `:features` exactly and has no
  unconditional platform feature): over a table of shapes, checker and reader
  must agree on WHETHER each target can read the set and, when it can, on WHICH
  set — and the table must contain a substantial number of each verdict.

  Five things are asserted:

    1. checker selection and a real target reader agree in both directions,
       over a table that is refused eleven times and accepted nineteen;
    2. `check-file` — the public surface — refuses the ordinary and the
       SPLICING collision rather than reporting eligible, naming the file, the
       set, the colliding member, the target and the recovery;
    3. valid conditional sets stay eligible, and resolution still answers a set
       with its metadata intact;
    4. the refusal is target-symmetric, and a member that selects nothing is
       DROPPED rather than refused, because that is what the real reader does
       with it;
    5. the checker is still read-only, including after a REFUSED run.

  The colliding fixtures are written to a temp file by this suite rather than
  committed: their `:cljs` branch is source the browser build's reader refuses,
  so a committed fixture carrying one could not be compiled for CLJS."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.check-conditional-set-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private uniform-path
  (.getPath (io/file (io/resource "re_frame/freehand/check_conditional_set_views.cljc"))))

(def ^:private resolve-conditionals
  "The checker's branch selection, reached directly — the set arm answers per
  target, and a refusal raised here carries no file yet (`check-file` adds it),
  so the bare sentence is what these assertions see."
  #'check/resolve-conditionals)

(defn- thrown-by [f]
  (try (f) nil (catch Throwable t t)))

;; ---------------------------------------------------------------------------
;; 1 — the boundary is the target reader's own, in both directions
;; ---------------------------------------------------------------------------

(def ^:private shapes
  "Set literals spanning what conditional selection can do to a set: collide
  with a sibling, collide out of a splice, collide with itself inside one
  splice, collide on the JVM instead of the browser, collide through
  `:default`, collide nested inside another set, collide inside a declaration's
  attribute map — and, just as load-bearing, the shapes that collide on NO
  target: a member that elides, a divergent member that lands somewhere free, a
  splice of a different size per target, two one-armed conditionals that select
  the same value for DIFFERENT targets (so only ever one of them survives), and
  a set with no conditional in it at all."
  ["#{#?(:clj :a :cljs :b) :b}"
   "#{#?@(:clj [:a] :cljs [:b]) :b}"
   "#{#?@(:clj [:a :b] :cljs [:c]) :c}"
   "#{#?@(:clj [] :cljs [:b]) :b}"
   "#{#?(:cljs :a :clj :b) :b}"
   "#{#?@(:clj [:a :a])}"
   "#{:a #?(:default :a)}"
   "#{#?@(:cljs [:x :y]) :x}"
   "#{#?(:clj #{:a} :cljs #{:b}) #{:b}}"
   "[:div {:class #{#?(:clj :a :cljs :b) :b}}]"
   "#{#?(:clj :a) :b}"
   "#{#?(:clj :a :cljs :b) :c}"
   "#{#?@(:clj [:a] :cljs [:a :b])}"
   "#{#?(:clj :a) #?(:cljs :a)}"
   "#{:plain :members}"])

(defn- read-for
  "What a REAL reader told to read for `features` makes of `src` — `{:read …}`
  or `{:refused message}`. `clojure.tools.reader` honours `:features` exactly,
  splices before checking for duplicates, and has no unconditional platform
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
            (str src " for " features ": the same verdict, a different set")))))

  (testing "and the table is not one-sided — a rule that only reproduced the
            refusals would pass a table with no acceptances in it, and a rule
            that refused nothing would pass a table with no refusals"
    (is (= 11 (count (filter (comp :refused :reader) verdicts))))
    (is (= 19 (count (filter (comp #(contains? % :read) :reader) verdicts))))))

;; ---------------------------------------------------------------------------
;; 2 — check-file refuses instead of normalizing
;; ---------------------------------------------------------------------------

(def ^:private colliding
  "The two ways a set collides for one target only, as the bead reproduced them.
  Both `:clj` branches read — `#{:a :b}` — which is why the namespace loads;
  the refusal is about the `:cljs` branch alone."
  {"ordinary-member" "[:div {:title (str #{#?(:clj :a :cljs :b) :b})} \"x\"]"
   "spliced-member"  "[:div {:title (str #{#?@(:clj [:a] :cljs [:b]) :b})} \"x\"]"})

(defn- write-temp-view
  "Write a one-view `.cljc` namespace whose body is `colliding`'s `nm`, LOAD it
  so heads resolve, and answer its path."
  [nm]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "check-conditional-set"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (io/file dir (str nm ".cljc"))]
    (.deleteOnExit dir)
    (.deleteOnExit f)
    (spit f (str "(ns re-frame.freehand." nm "-temp-set-views\n"
                 "  (:require [re-frame.freehand :as v]))\n"
                 "\n"
                 "(v/defview subject\n"
                 "  [_]\n"
                 "  " (get colliding nm) ")\n"))
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

(deftest check-file-refuses-a-set-the-target-cannot-read
  (testing "a conditional member that selects a member the set already has is a
            duplicate for that target, and the checker refuses rather than
            coalescing it — this reported :compile-eligible? true, no findings"
    (let [path (temp-view "ordinary-member")]
      (is (nil? (read-file-for path #{:clj})) "the JVM branch reads: #{:a :b}")
      (is (re-find #"Duplicate key: :b" (str (read-file-for path #{:cljs}))))

      (let [ex (thrown-by #(check/check-file path))]
        (is (instance? clojure.lang.ExceptionInfo ex) "a refusal, not a report")
        (let [msg (ex-message ex)]
          (is (re-find #"^re-frame\.freehand check: " msg))
          (is (.contains msg path))
          (is (.contains msg "two members the same value, :b") "which member collided")
          (is (.contains msg ":cljs target") "and for which target")
          (is (.contains msg "#{:b #?(:clj :a :cljs :b)}") "the set, as the checker preserved it")
          (is (.contains msg "around the WHOLE set") "the recovery is the payload"))
        (is (= :cljs (:target (ex-data ex))))
        (is (= path (:file (ex-data ex))))
        (is (= :b (:member (ex-data ex))))
        (is (some reader-conditional? (:set (ex-data ex)))
            "the offending set rides in the data exactly as authored, its
             conditional intact, so a tool can render it without re-reading"))))

  (testing "and a SPLICING conditional collides the same way — the target reader
            splices before it checks for duplicates, so the check has to be on
            the spliced members and not on the conditional that produced them"
    (let [path (temp-view "spliced-member")]
      (is (nil? (read-file-for path #{:clj})) "the JVM branch reads: #{:a :b}")
      (is (re-find #"Duplicate key: :b" (str (read-file-for path #{:cljs}))))

      (let [ex (thrown-by #(check/check-file path))]
        (is (instance? clojure.lang.ExceptionInfo ex))
        (is (.contains (ex-message ex) "two members the same value, :b"))
        (is (.contains (ex-message ex) ":cljs target"))
        (is (= :cljs (:target (ex-data ex)))))))

  (testing "a duplicate WITHIN one splice is the same refusal, for whichever
            target the splice lands on"
    (is (re-find #"two members the same value, :a"
                 (:refused (select-for "#{#?@(:clj [:a :a])}" #{:clj}))))
    (is (= #{} (:read (select-for "#{#?@(:clj [:a :a])}" #{:cljs}))))))

;; ---------------------------------------------------------------------------
;; 3 — the ordinary case is untouched
;; ---------------------------------------------------------------------------

(deftest a-readable-conditional-set-stays-eligible
  (testing "sets whose conditionals collide on no target keep every selected
            member and the view stays eligible — refusing the unreadable must
            not cost the readable"
    (is (= [{:view-id           :re-frame.freehand.check-conditional-set-views/conditional-set-uniform
             :compile-eligible? true
             :findings          []}]
           (mapv #(select-keys % [:view-id :compile-eligible? :findings])
                 (check/check-file uniform-path)))))

  (testing "and resolution still answers a SET, with the metadata the reader
            anchored on it — a finding's coordinates ride that metadata"
    (let [form (vary-meta (read-string {:read-cond :preserve} "#{#?(:clj :a :cljs :b) :c}")
                          assoc ::marker true)]
      (is (set? (resolve-conditionals form #{:cljs})))
      (is (= #{:a :c} (resolve-conditionals form #{:clj})))
      (is (= #{:b :c} (resolve-conditionals form #{:cljs})))
      (is (= {::marker true} (meta (resolve-conditionals form #{:cljs})))
          "the set's metadata rides through the rebuild"))))

;; ---------------------------------------------------------------------------
;; 4 — symmetry, and the member that legitimately disappears
;; ---------------------------------------------------------------------------

(deftest the-refusal-follows-the-target
  (testing "nothing here is :cljs-specific — the JVM target is refused by the
            same rule when the conditional is the one that collides for it"
    (let [src "#{#?(:cljs :a :clj :b) :b}"]
      (is (= #{:a :b} (:read (select-for src #{:cljs}))))
      (is (re-find #"cannot be read for the :clj target" (:refused (select-for src #{:clj}))))))

  (testing "and a member whose conditional selects NOTHING contributes nothing
            to the target, exactly as it contributes nothing to the real reader
            — dropping it is not normalizing, it is agreeing. Refusing it would
            be the false RED that hides behind a rule checked only against the
            refusals it was written for"
    (let [src "#{#?(:clj :a) :b}"]
      (is (= #{:b}    (:read (select-for src #{:cljs}))))
      (is (= #{:b}    (:read (read-for   src #{:cljs}))) "independently, per clojure.tools.reader")
      (is (= #{:a :b} (:read (select-for src #{:clj}))))
      (is (= #{:a :b} (:read (read-for   src #{:clj})))))

    (testing "including when every member elides and the target's set is empty"
      (is (= #{} (:read (select-for "#{#?(:clj :a) #?(:clj :b)}" #{:cljs}))))
      (is (= #{} (:read (read-for   "#{#?(:clj :a) #?(:clj :b)}" #{:cljs})))))))

;; ---------------------------------------------------------------------------
;; 5 — read-only
;; ---------------------------------------------------------------------------

(deftest the-checker-never-writes
  (testing "refusing a set is still read-only — the source pointed at is
            byte-identical afterwards, refused run included"
    (doseq [path [uniform-path (temp-view "ordinary-member") (temp-view "spliced-member")]]
      (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
            _      (thrown-by #(check/check-file path))
            after  (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
        (is (pos? (alength before)) (str path " is not empty"))
        (is (java.util.Arrays/equals ^bytes before ^bytes after))))))
