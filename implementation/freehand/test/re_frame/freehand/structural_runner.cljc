(ns re-frame.freehand.structural-runner
  "The conformance RUNNER for structural-render laws (EP-0036 F1e; Spec 008
  §How a conformance fixture is executed).

  A Freehand law named in the conformance index
  ([`spec/conformance/freehand/conformance-index.md`](../../../../../spec/conformance/freehand/conformance-index.md))
  is proven by running its fixture. This runner executes the STRUCTURAL
  shape — a fixture carrying `:cases` of `{:form <markup> :tree <expected
  root>}` and `:rejected` of `{:form <markup> :error-id <id>}` — through the
  PUBLIC structural test surface [[re-frame.freehand.test/render]], on
  whichever host is running (the JVM lane via `clojure -M:test`, the
  ClojureScript lane via `npm run test:freehand`), and answers a status
  report that NAMES the fixture's `FH` id.

  Status FLOWS BACK: a mismatch is a `:failures` entry carrying the id, the
  case note, the expected value and what `render` actually produced (or the
  error it raised), so a red row is attributable to its law without a
  lookup. A run that asserts nothing is the worst failure a table-driven
  gate can have, so `:ran` is reported and a caller checks it is non-zero.

  Only the structural-render shape runs here — the tier the F1e surface can
  execute headlessly. A row proven by mounting, by SSR emission, or across a
  qualified host boundary is that tier's, per the host/mode matrix in Spec
  008."
  (:require [clojure.walk :as walk]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]))

(defn- realize
  "Substitute a real function for the fixture's `:fh/fn` stand-in — EDN
  carries no functions, and a handler site's classification turns on
  whether the value IS one (Spec 004B §Element fields)."
  [form]
  (walk/postwalk #(if (= :fh/fn %) (fn [_] nil) %) form))

(defn- render-case
  "Render one `:cases` row and answer nil on a match, or a `:failure` map
  naming `id` on a mismatch or a render that threw."
  [id {:keys [note form tree]}]
  (let [actual (try (t/render (realize form))
                    (catch #?(:clj Throwable :cljs :default) e
                      {::threw (or (ex-message e) (str e))}))]
    (when-not (= tree actual)
      {:fh/id id :note note :expected tree :actual actual})))

(defn- reject-case
  "Render one `:rejected` row and answer nil when it raised the expected
  `:error-id`, or a `:failure` map naming `id` otherwise."
  [id {:keys [note form error-id]}]
  (let [actual (conf/caught-id #(t/render (realize form)))]
    (when-not (= error-id actual)
      {:fh/id id :note note :expected error-id :actual actual})))

(defn run-structural-fixture
  "Execute the structural `fixture` through [[re-frame.freehand.test/render]]
  and return a status report:

      {:fh/id    \"FH-STRUCT-001\"
       :ran      12          ; total cases + rejections executed
       :passed   12
       :failures []}         ; each entry NAMES the FH id

  Every `:failure` carries `:fh/id`, the offending `:note`, the `:expected`
  value, and the `:actual` render (or `{::threw <message>}` when the render
  threw where a tree was expected). An empty `:cases`+`:rejected` fixture
  reports `:ran 0`, which a caller treats as a failure — a law proven by an
  empty table is proven by nothing."
  [fixture]
  (let [id       (:fh/id fixture)
        failures (into (vec (keep #(render-case id %) (:cases fixture)))
                       (keep #(reject-case id %) (:rejected fixture)))
        ran      (+ (count (:cases fixture)) (count (:rejected fixture)))]
    {:fh/id    id
     :ran      ran
     :passed   (- ran (count failures))
     :failures failures}))

(defn run-all
  "Run every fixture in `fixtures` and answer `{:ran :passed :failures}`
  totalled across them. The aggregate a suite asserts a non-zero `:ran` and
  an empty `:failures` on."
  [fixtures]
  (let [reports (map run-structural-fixture fixtures)]
    {:ran      (reduce + 0 (map :ran reports))
     :passed   (reduce + 0 (map :passed reports))
     :failures (vec (mapcat :failures reports))}))
