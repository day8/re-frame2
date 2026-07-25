(ns re-frame.freehand.control-boundary-jvm-test
  "`re-frame.freehand` IS THE SOLE REQUIRER OF `re-frame.freehand.control`
  (rf2-qimh0).

  ## What this law is holding up

  The B5 reachability gate (`npm run test:freehand-reachability`, the
  required `cljs-freehand-reachability` job) proves an UNUSED RUNTIME
  MODULE is absent from the production bundle: it builds
  `:freehand-release` and its strict-superset twin
  `:freehand-release-reachability-control` — same `:advanced`, same
  `goog.DEBUG false`, the control's entry additionally calling
  `v/controller-key` — and greps for the controller's refusal doors,
  ABSENT in production and PRESENT in the control.

  Two `:advanced` builds is real PR time, so the gate is armed on a NARROW
  set of producer surfaces rather than on `implementation/freehand/**`:
  `control.cljc`, the facade, the two build entries, the checker and the
  build-config trio. That narrowness is honest only while a FOURTH
  producer cannot appear silently. A new `:require` of `control` from
  anywhere else in `freehand/src` — a paved path that starts resolving a
  controller record — would root the module in production, falsify the
  reachability claim, and arm none of those surfaces. The nightly would
  catch it, red on main, after the fact.

  So the requirer set is pinned HERE, in the always-armed jvm-freehand
  lane, where a would-be second requirer meets it on its own PR. The
  sibling claim — `cell` is the sole namespace mentioning the evidence
  schema — does the same job for the sibling gate in
  `re-frame.freehand.evidence-boundary-jvm-test`, and
  `implementation/scripts/_changed-surfaces.test.cjs` pins both premises
  against the routing, so relaxing either law reds the classifier tests
  and forces the arm to widen.

  ## Why the walk is here rather than shared with the sibling

  Deliberate. The two laws guard different gates and must be able to fail
  independently; a shared walk would let a change made for one silently
  re-scope the other. This is thirty lines of `ns`-form reading, not a
  dependency framework, and the cost of keeping it separate is lower than
  the cost of coupling two required lanes together.

  ## What it is NOT

  A require-graph assertion, not a bundle proof. It answers \"can
  production code reach the controller\" and never \"did the controller
  survive `:advanced`\" — that is the control-build probe's job, and a
  source walk cannot see what dead-code elimination removed.

  JVM-only because the claim is about the whole source tree, and the JVM
  is where a test can read it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; The public door, required for its LOADING — and because it is
            ;; the subject: the law below says this namespace, and only this
            ;; namespace, reaches the controller.
            [re-frame.freehand]))

(def ^:private controller
  're-frame.freehand.control)

(def ^:private sole-requirer
  're-frame.freehand)

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

(defn- mention-graph
  "Freehand namespace -> the Freehand namespaces its `ns` form mentions.

  Deliberately over-approximate: every Freehand-shaped symbol anywhere in
  the `ns` form is an edge, whether it sits in a `:require`, an `:import`
  or a docstring's code sample. Over-approximating can only make a
  requirer set LARGER, so a set this graph reports as a singleton really
  is one."
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

(defn- namespaces-mentioning
  [graph target]
  (into #{} (keep (fn [[self edges]] (when (contains? edges target) self))) graph))

(deftest re-frame-freehand-is-the-sole-requirer-of-the-controller
  (let [graph (mention-graph)]
    (testing "the walk is real — the positive control. Everything below is a
              claim about a SET being small, and a broken walk answers every
              such claim with the empty set."
      (is (< 20 (count graph))
          "every Freehand source file contributed a node")
      (is (contains? graph controller)
          (str controller " has a source file of its own — if it were renamed or "
               "removed, this whole test is asserting nothing and should go with it"))
      (is (contains? (get graph sole-requirer) controller)
          (str sole-requirer " must still mention " controller " — it owns the single "
               "production call edge (`controller-key` is `def`'d to `control/record-key`), "
               "and a law that held because NOBODY reached the controller would be "
               "guarding an empty claim")))
    (testing "and the controller is reached from exactly one namespace"
      (let [requirers (namespaces-mentioning graph controller)]
        (is (= '#{re-frame.freehand} requirers)
            (str "the controller must be reached from the public door alone. A second "
                 "Freehand namespace requiring it roots the module in production and "
                 "FALSIFIES the B5 reachability claim — and the `freehand_reachability` "
                 "classifier arm does not watch the rest of the tree, so the required PR "
                 "gate would not even run. If the new edge is intended, widen the arm in "
                 "implementation/scripts/_changed-surfaces.cjs to the real requirer set "
                 "and update this law to match — do not relax it. Found: "
                 (pr-str requirers)))))
    (testing "`re-frame.freehand.controlled` is a DIFFERENT namespace, and the
              walk must not confuse the two. Its name extends the controller's
              character for character, so a substring or prefix test would
              report half the tree as requirers and the law would read as
              already-broken — or, matched the other way, would let a real
              `control` require hide behind a `controlled` one."
      (is (contains? graph 're-frame.freehand.controlled)
          "the near-miss namespace exists — this guard is not hypothetical")
      (is (< 1 (count (namespaces-mentioning graph 're-frame.freehand.controlled)))
          (str "and it is required broadly, unlike the controller — so the singleton "
               "above is a real discrimination and not an artefact of exact matching")))))
