(ns re-frame.freehand.evidence-boundary-jvm-test
  "Two claims about the evidence schema that are claims about ABSENCE, and
  so cannot be made by exercising it.

  1. **Retention is Spec 009's, and there is no second mechanism.** The
     schema names the existing per-frame retained-event ring and keeps
     nothing itself. Checked over the interned vars of every loaded
     Freehand namespace rather than over the text of any file: a store is
     a mutable container, and a mutable container is a value you can look
     at.

  2. **The evidence surface reaches the render path through ONE seam.**
     The emission slice (rf2-3naow) landed: `re-frame.freehand.cell`
     now emits a dev-gated occurrence-record per selected commit, so the
     schema IS reachable from the public door — by construction, and
     through exactly one namespace. Checked as REACHABILITY over the
     require graph, rooted at the door: the schema is reachable, and
     `cell` is the SOLE Freehand namespace whose `ns` form mentions it,
     so the seam is a single controlled entry point rather than a
     scattered dependency.

  On (2) and what it is NOT: this is a require-graph assertion, not a
  bundle proof. It answers \"how does a render reach this code\" and not
  \"did this code survive `:advanced`\". Production ISOLATION — that the
  emission and the schema it reaches DCE out of the shipping bundle — is
  proven separately by a control-build probe, because a require-graph
  walk cannot see what dead-code elimination removed. That probe is the
  automated gate `npm run test:freehand-evidence-elision`
  (`scripts/check-freehand-evidence-elision.cjs`, rf2-drpa3.166): it
  builds `:freehand-release` and its `:freehand-release-control` twin —
  the SAME `:advanced` entry, `goog.DEBUG` the only difference — and greps
  both for the emission doors' own strings (the record door's
  `occurrence-keyed schema (D020)` and the projection door's
  `This evidence projection cannot be emitted`). The seam sits behind
  `re-frame.interop/debug-enabled?` (`goog.DEBUG`), so each string is
  ABSENT under `goog.DEBUG=false` (the branch DCE'd — the schema does not
  ship) and PRESENT under the `goog.DEBUG=true` control (the branch is
  live — the grep's positive control, retaining exactly what the release
  proves absent). Measured ~744K release / ~795K control bytes; the
  fuller shipped-cost probe is rf2-drpa3.52.

  This test USED to assert the schema was unreachable from any render —
  true only while nothing emitted evidence. That assertion was replaced,
  not relaxed, exactly as its own note instructed, the moment the
  dev-gated seam appeared on the path.

  JVM-only because both claims are about the whole source tree, and the
  JVM is where a test can read it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; The public door, required for its LOADING: the corpus walk
            ;; below is over the namespaces this process has loaded, and
            ;; requiring the door is what makes that set the substrate
            ;; rather than whatever a preceding suite happened to touch.
            [re-frame.freehand]
            [re-frame.freehand.evidence :as ev])
  (:import [clojure.lang IAtom IDeref IPending IRef]))

;; ---------------------------------------------------------------------------
;; 1 — no second retention mechanism
;; ---------------------------------------------------------------------------

(defn- store?
  "True when `v` is a mutable container — the shape a retention store has,
  whatever it is called."
  [v]
  (or (instance? IAtom v)
      (instance? IRef v)
      (instance? IPending v)
      (instance? clojure.lang.Volatile v)))

(deftest the-evidence-schema-holds-nothing
  (testing "Not \"holds no retention\" — holds NOTHING. Every public var in
            the schema is a value or a function over values, so there is
            no slot in which a record could survive the call that made it,
            and therefore nothing to disable in production, nothing to
            clear on teardown, and nothing to bound."
    (let [interns (ns-interns 're-frame.freehand.evidence)]
      (is (seq interns) "the namespace has public vars — the walk below is not over an empty set")
      (doseq [[sym var*] interns]
        (is (not (store? (deref var*)))
            (str "re-frame.freehand.evidence/" sym " is a mutable container"))))))

(defn- freehand-namespaces
  []
  (filter #(str/starts-with? (str (ns-name %)) "re-frame.freehand") (all-ns)))

(def ^:private retention-ish
  #"(?i)retain|retention|history|ring-buffer")

(deftest no-freehand-namespace-publishes-a-second-retention-store
  (testing "The corpus half of the same claim, over interned vars rather
            than over file text: any Freehand var whose NAME suggests it
            keeps history is checked to be an immutable value. The one
            that matches is a pointer at Spec 009's knob, which is the
            shape a reference has and not the shape a store has."
    (let [nss     (freehand-namespaces)
          matches (for [n nss
                        [sym var*] (ns-publics n)
                        :when (re-find retention-ish (name sym))]
                    [(symbol (str (ns-name n)) (name sym)) var*])]
      (is (< 10 (count nss))
          "the Freehand tree is loaded — the walk below is not over an empty set")
      (is (seq matches)
          "and at least one var matched, so the predicate above is not vacuous")
      (doseq [[qualified var*] matches]
        (is (not (store? (deref var*)))
            (str qualified " keeps history of its own"))))))

(deftest retention-points-at-re-frames-own-axis
  (testing "The knob the schema names belongs to re-frame's reserved
            `:rf.trace/*` namespace and is applied through re-frame's own
            configurator. Freehand could not have minted it: a
            Freehand-minted knob would be in a Freehand namespace, and
            this one is not."
    (is (= "rf.trace" (namespace (:knob ev/retention)))
        "the knob lives in re-frame's reserved trace namespace")
    (is (not (str/includes? (str (:knob ev/retention)) "freehand"))
        "and not in a Freehand-minted one")
    (is (some? (requiring-resolve 're-frame.core/configure!))
        "the configurator the schema points at exists")
    (is (= 1 (count (first (:arglists (meta (requiring-resolve 're-frame.core/configure!))))))
        "and takes the single nested config map the retention path is expressed against")))

;; ---------------------------------------------------------------------------
;; 2 — the schema is not reachable from the shipping render path
;; ---------------------------------------------------------------------------

(defn- source-root
  "The `src` directory this artefact's namespaces are compiled from,
  located through the classpath rather than through a relative path, so
  the suite does not depend on the directory it was started in."
  []
  (-> (io/resource "re_frame/freehand.cljc") .toURI io/file .getParentFile .getParentFile))

(defn- source-files
  []
  (->> (file-seq (source-root))
       (filter #(.isFile ^java.io.File %))
       (filter #(re-find #"\.clj[cs]?$" (.getName ^java.io.File %)))))

(defn- symbols-in
  "Every symbol in `form`, descending through reader conditionals so a
  require that exists only on one host is still seen."
  [form]
  (cond
    (symbol? form)                                  [form]
    (instance? clojure.lang.ReaderConditional form) (symbols-in (.form ^clojure.lang.ReaderConditional form))
    (coll? form)                                    (mapcat symbols-in form)
    :else                                           []))

(defn- ns-form
  [^java.io.File f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (read {:read-cond :preserve :eof nil} r)))

(defn- require-graph
  "Freehand namespace -> the Freehand namespaces its `ns` form mentions.

  Deliberately over-approximate: every Freehand-shaped symbol anywhere in
  the `ns` form is an edge, whether it sits in a `:require`, an `:import`
  or a docstring's code sample. Over-approximating can only make the
  reachable set LARGER, so a namespace this graph says is unreachable is
  unreachable."
  []
  (into {}
        (keep (fn [f]
                (let [form (ns-form f)]
                  (when (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                    (let [self (second form)]
                      [self (into #{}
                                  (comp (filter #(str/starts-with? (str %) "re-frame.freehand"))
                                        (remove #(= self %)))
                                  (symbols-in (drop 2 form)))])))))
        (source-files)))

(defn- reachable-from
  [graph root]
  (loop [seen #{} queue [root]]
    (if-let [n (first queue)]
      (if (contains? seen n)
        (recur seen (rest queue))
        (recur (conj seen n) (into (vec (rest queue)) (get graph n))))
      seen)))

(defn- freehand-namespaces-mentioning
  "Every Freehand namespace whose `ns` form mentions `target` — the
  over-approximate require-graph edge, so a require on one host, or even a
  docstring code sample, counts. Answers the set of would-be seams."
  [graph target]
  (into #{} (keep (fn [[self edges]] (when (contains? edges target) self))) graph))

(deftest the-evidence-schema-reaches-the-render-path-only-through-the-dev-gated-seam
  (testing "Rooted at `re-frame.freehand`, the substrate's one public door,
            and followed transitively. The emission slice (rf2-3naow) put a
            dev-gated occurrence-record on the render path, so the schema IS
            reachable now — but through exactly ONE seam. This replaces the
            former `not-reachable` assertion (true only while nothing emitted
            evidence), as that assertion's own note instructed: replaced when
            the seam landed, not relaxed. Production ISOLATION is the
            control-build probe's job (see the ns docstring), not this walk's."
    (let [graph     (require-graph)
          reachable (reachable-from graph 're-frame.freehand)]
      (testing "the graph is real — the positive control"
        (is (< 20 (count graph)) "every Freehand source file contributed a node")
        (is (< 10 (count reachable)) "and the door reaches a substantial tree")
        (doseq [on-the-path '[re-frame.freehand.tree
                              re-frame.freehand.descriptor
                              re-frame.freehand.node
                              re-frame.freehand.cell]]
          (is (contains? reachable on-the-path)
              (str on-the-path " is reachable from the door — if it were not, the walk is broken "
                   "and the claims below would be meaningless"))))
      (testing "the schema is reachable — the seam is wired onto the render path"
        (is (contains? reachable 're-frame.freehand.evidence)
            (str "re-frame.freehand.evidence is no longer reachable from the public door. The "
                 "rf2-3naow emission seam is supposed to reach it under the dev gate; if the "
                 "seam was removed, remove this test too — do not leave it asserting a wiring "
                 "that is gone.")))
      (testing "and it reaches the path through exactly one seam"
        (let [seams (freehand-namespaces-mentioning graph 're-frame.freehand.evidence)]
          (is (= '#{re-frame.freehand.cell} seams)
              (str "the evidence schema must reach the render path through cell alone — the one "
                   "dev-gated commit seam. A second Freehand namespace mentioning it would be a "
                   "second, ungated door onto the shipping path. Found: " (pr-str seams))))))))
