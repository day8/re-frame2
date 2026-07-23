(ns re-frame.freehand.check-conditional-map-jvm-test
  "A green from the checker must mean the target can READ the branch.

  The checker reads with conditionals preserved, so a map arrives already
  PAIRED. The reader that compiles the target pairs its forms AFTER selection,
  and two shapes fall between the two: an entry with one side elided, which
  leaves the target reader an odd number of forms, and two keys distinct as
  authored that select the SAME key, which the target reader refuses as a
  duplicate. Resolving each side independently and rebuilding with `into` did
  neither — it fabricated `{:title nil}` for the first and silently kept one
  entry for the second, and both views came back `:compile-eligible? true`
  with no findings. A branch that does not read at all cannot be eligible;
  that green was about a map nobody wrote.

  Five things are asserted:

    1. an INDEPENDENT target reader (`clojure.tools.reader` at `:features
       #{:cljs}`, which has no unconditional platform feature) refuses each
       shape, and accepts it for `:clj` — the boundary is the reader's, not
       the checker's opinion;
    2. `check-file` refuses each shape rather than reporting eligible, naming
       the file, the map, the target and the recovery;
    3. valid conditional keys and values stay eligible, keep both entries, and
       retain the map's metadata — refusing must not cost the ordinary case;
    4. the refusal is target-symmetric, and an entry whose key and value BOTH
       elide is DROPPED rather than refused, because that is what the real
       reader does with it;
    5. the checker is still read-only.

  The unreadable fixtures are written to a temp file by this suite rather than
  committed: their `:cljs` branch is source the browser build's reader
  refuses, so a committed fixture carrying one could not be compiled for CLJS."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.freehand.check-conditional-map-views]
            [re-frame.freehand.compiler.check :as check]))

(def ^:private uniform-path
  (.getPath (io/file (io/resource "re_frame/freehand/check_conditional_map_views.cljc"))))

(def ^:private resolve-conditionals
  "The checker's branch selection, reached directly — the map arm answers per
  target, and a refusal raised here carries no file yet (`check-file` adds
  it), so the bare sentence is what these assertions see."
  #'check/resolve-conditionals)

(defn- thrown-by [f]
  (try (f) nil (catch Throwable t t)))

(def ^:private shapes
  "The two shapes a target's reader refuses, as the bead reproduced them."
  {"elided-value"  "[:div {:title #?(:clj 1)} \"x\"]"
   "colliding-key" "[:div {#?(:clj :a :cljs :b) 1\n         :b 2} \"x\"]"})

(defn- write-temp-view
  "Write a one-view `.cljc` namespace whose body is `shapes`'s `nm`, LOAD it so
  heads resolve, and answer its path. The `:clj` branch of each shape is
  perfectly readable, which is why the namespace loads — the refusal is about
  the `:cljs` branch alone."
  [nm]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "check-conditional-map"
                      (make-array java.nio.file.attribute.FileAttribute 0)))
        f   (io/file dir (str nm ".cljc"))]
    (.deleteOnExit dir)
    (.deleteOnExit f)
    (spit f (str "(ns re-frame.freehand." nm "-temp-views\n"
                 "  (:require [re-frame.freehand :as v]))\n"
                 "\n"
                 "(v/defview subject\n"
                 "  [_]\n"
                 "  " (get shapes nm) ")\n"))
    (load-file (.getPath f))
    (.getPath f)))

(def ^:private temp-view
  "Each shape is written and loaded ONCE, however many assertions point at it —
  re-loading would redeclare the same view id for no gain."
  (memoize write-temp-view))

;; ---------------------------------------------------------------------------
;; 1 — the boundary is the target reader's own
;; ---------------------------------------------------------------------------

(defn- read-all-for
  "Read every form of the file at `path` with a REAL reader told to read for
  `features`, and answer nil or the exception it threw."
  [path features]
  (let [r (rt/indexing-push-back-reader (slurp path))]
    (thrown-by
     #(loop []
        (when-not (= ::eof (rdr/read {:eof ::eof :read-cond :allow :features features} r))
          (recur))))))

(deftest the-target-reader-refuses-both-shapes
  (testing "an entry whose value elides leaves the target reader an odd number
            of map forms"
    (let [path (temp-view "elided-value")]
      (is (nil? (read-all-for path #{:clj})) "the JVM branch reads: {:title 1}")
      (is (re-find #"even number of forms" (str (read-all-for path #{:cljs}))))))

  (testing "and a conditional key that selects an authored key is a duplicate"
    (let [path (temp-view "colliding-key")]
      (is (nil? (read-all-for path #{:clj})) "the JVM branch reads: {:a 1 :b 2}")
      (is (re-find #"Duplicate key: :b" (str (read-all-for path #{:cljs})))))))

;; ---------------------------------------------------------------------------
;; 2 — check-file refuses instead of normalizing
;; ---------------------------------------------------------------------------

(deftest check-file-refuses-a-map-the-target-cannot-read
  (testing "an elided entry side is refused, not fabricated into {:title nil}"
    (let [path (temp-view "elided-value")
          ex   (thrown-by #(check/check-file path))]
      (is (instance? clojure.lang.ExceptionInfo ex)
          "a refusal — this reported :compile-eligible? true with no findings")
      (let [msg (ex-message ex)]
        (is (re-find #"^re-frame\.freehand check: " msg))
        (is (.contains msg path))
        (is (.contains msg "VALUE") "which side of the entry went missing")
        (is (.contains msg ":cljs target") "and for which target")
        (is (.contains msg "{:title #?(:clj 1)}") "the map, as the checker preserved it")
        (is (.contains msg "around the WHOLE map") "the recovery is the payload"))
      (is (= :cljs (:target (ex-data ex))))
      (is (= path (:file (ex-data ex))))
      (is (= '(:title) (keys (:map (ex-data ex)))))
      (is (reader-conditional? (:title (:map (ex-data ex))))
          "the offending map rides in the data exactly as authored, its
           conditional intact, so a tool can render it without re-reading")))

  (testing "and a duplicate target key is refused, not silently overwritten to
            {:b 2}"
    (let [path (temp-view "colliding-key")
          ex   (thrown-by #(check/check-file path))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (let [msg (ex-message ex)]
        (is (.contains msg "two entries the same key, :b"))
        (is (.contains msg ":cljs target")))
      (is (= :cljs (:target (ex-data ex)))))))

;; ---------------------------------------------------------------------------
;; 3 — the ordinary case is untouched
;; ---------------------------------------------------------------------------

(deftest a-readable-conditional-map-stays-eligible
  (testing "conditionals that select on every target keep both entries and the
            view stays eligible — refusing the unreadable must not cost the
            readable"
    (is (= [{:view-id           :re-frame.freehand.check-conditional-map-views/conditional-map-uniform
             :compile-eligible? true
             :findings          []}]
           (mapv #(select-keys % [:view-id :compile-eligible? :findings])
                 (check/check-file uniform-path)))))

  (testing "and the selected maps are what each target reads, metadata and all"
    (let [form (vary-meta (read-string {:read-cond :preserve}
                                       "{#?(:clj :title :cljs :data-title) \"hover\"
                                         :class #?(:clj \"jvm\" :cljs \"spa\")}")
                          assoc ::marker true)]
      (is (= {:title "hover" :class "jvm"}          (resolve-conditionals form #{:clj})))
      (is (= {:data-title "hover" :class "spa"}     (resolve-conditionals form #{:cljs})))
      (is (= {::marker true} (meta (resolve-conditionals form #{:cljs})))
          "the map's metadata rides through the rebuild"))))

;; ---------------------------------------------------------------------------
;; 4 — symmetry, and the entry that legitimately disappears
;; ---------------------------------------------------------------------------

(deftest the-refusal-follows-the-target
  (testing "nothing here is :cljs-specific — the JVM target is refused by the
            same rule when the conditional is the one that elides for it"
    (let [form (read-string {:read-cond :preserve} "{:title #?(:cljs 1)}")]
      (is (= {:title 1} (resolve-conditionals form #{:cljs})))
      (is (re-find #"cannot be read for the :clj target"
                   (str (ex-message (thrown-by #(resolve-conditionals form #{:clj}))))))))

  (testing "and an entry whose key AND value both elide contributes nothing to
            the target, exactly as it contributes nothing to the real reader —
            dropping it is not normalizing, it is agreeing"
    (let [src  "{#?(:clj :a) #?(:clj 1) :keep 2}"
          form (read-string {:read-cond :preserve} src)]
      (is (= {:keep 2} (resolve-conditionals form #{:cljs})))
      (is (= {:keep 2} (rdr/read-string {:read-cond :allow :features #{:cljs}} src))
          "which is what clojure.tools.reader makes of it, independently")
      (is (= {:a 1 :keep 2} (resolve-conditionals form #{:clj})))
      (is (= {:a 1 :keep 2} (rdr/read-string {:read-cond :allow :features #{:clj}} src))))))

;; ---------------------------------------------------------------------------
;; 5 — read-only
;; ---------------------------------------------------------------------------

(deftest the-checker-never-writes
  (testing "refusing a map is still read-only — the source pointed at is
            byte-identical afterwards"
    (doseq [path [uniform-path (temp-view "elided-value")]]
      (let [before (java.nio.file.Files/readAllBytes (.toPath (io/file path)))
            _      (thrown-by #(check/check-file path))
            after  (java.nio.file.Files/readAllBytes (.toPath (io/file path)))]
        (is (pos? (alength before)) (str path " is not empty"))
        (is (java.util.Arrays/equals ^bytes before ^bytes after))))))
