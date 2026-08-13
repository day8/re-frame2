(ns re-frame.hicasso.examples.slice.surface-cljs-test
  "THE IMPORT DISCIPLINE, MECHANICALLY (rf2-hic-025).

  The slice is evidence about the DOOR. It is worth exactly as much as
  the claim that it was written on the public surface, and that claim is
  not one a reader can make by looking at seven `ns` forms — a reviewer
  reads them once, and the next commit is unreviewed.

  So it is read off the ClojureScript analyzer's own dependency graph:
  what the build was built from, including the edges a `:refer`,
  a `:use` or a `:require-macros` establishes and an `ns`-form regex
  would miss. See
  [[re-frame.hicasso.examples.require-graph]] for the mechanism and
  for why the emitted graph cannot go stale.

  ## Two claims, and they are different

  [[the-slice-names-no-private-namespace]] is the FENCE — no `impl`, no
  benchmark tree, no tool, no test kit, anywhere in app code. It is a
  predicate, so a namespace added tomorrow is covered the day it lands.

  [[the-slice-names-exactly-these-doors]] is the ROSTER — the exact set
  of foreign namespaces the whole application depends on. It is a
  pinned list, and it is the one that goes red when the slice quietly
  grows a dependency: a fence cannot see a new PUBLIC door being reached
  for, and the facade freeze at rf2-hic-026 wants to know about that
  more than about anything else in this file.

  ## And the population is asked for too (rf2-ccuw)

  Both claims are only worth the set of namespaces they are made over,
  and that set used to be typed here — twice, as a roster literal and as
  the macro's argument. A predicate over a hand-written population
  covers a namespace added tomorrow only if somebody remembers to type
  it, which is the reviewer problem this file opens by rejecting. It was
  not hypothetical: a `slice/http.cljs` reaching `re-frame.hicasso.impl`
  compiled, loaded, ran, and left every row below green.

  So [[app-namespaces]] is read off the package DIRECTORY and
  [[graph]] off the ANALYZER, and [[the-graph-is-populated]] compares
  them. A file in the package the analyzer never saw is the difference,
  and it names itself."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            ;; Required so the analyzer has analysed them when the macro
            ;; below expands — and, just as load-bearing, so shadow-cljs
            ;; recompiles THIS namespace when any of them changes.
            [re-frame.hicasso.examples.slice.app]
            [re-frame.hicasso.examples.slice.db]
            [re-frame.hicasso.examples.slice.events]
            [re-frame.hicasso.examples.slice.i18n]
            [re-frame.hicasso.examples.slice.routes]
            [re-frame.hicasso.examples.slice.subs]
            [re-frame.hicasso.examples.slice.views])
  (:require-macros [re-frame.hicasso.examples.require-graph :as rg]))

(def ^:private app-namespaces
  "Every namespace the slice's APPLICATION is made of, read off the
  package directory at macro-expansion time rather than typed. The test
  suites are deliberately absent: a test may reach past a door the
  application may not, which is the whole reason the two are held to
  different rules — and the exclusion is the build's own `cljs-test$`,
  not a second rule kept in step by hand."
  (rg/emit-application-namespaces re-frame.hicasso.examples.slice))

(def ^:private graph
  "`{namespace [dependency …]}` for the slice's application namespaces,
  read off the analyzer at macro-expansion time. Same population, other
  instrument: a namespace the analyzer never saw is absent here and
  present in [[app-namespaces]]."
  (rg/emit-dependency-graph re-frame.hicasso.examples.slice))

(def ^:private own?
  "Is this dependency one of the slice's own namespaces?"
  (set app-namespaces))

(def ^:private foreign-edges
  "Every dependency the application has on a namespace outside itself,
  as `[from to]` pairs so a failure names the file to open."
  (vec (for [[from tos] graph
             to         tos
             :when      (not (own? to))]
         [from to])))

;; ---------------------------------------------------------------------------
;; The instrument moves — asserted before anything is asserted WITH it
;; ---------------------------------------------------------------------------

(deftest the-graph-is-populated
  (testing "the analyzer answered for every application namespace"
    ;; Two instruments over one population: the DIRECTORY on the left,
    ;; the ANALYZER on the right. A namespace the analyzer never saw is
    ;; absent on the right and fenced by nothing — every check below
    ;; would pass over it VACUOUSLY — so the two are compared first, and
    ;; `=` prints the file to open.
    (is (= (set app-namespaces) (set (keys graph)))
        "a source file under `examples/slice/` is not on this namespace's
         `:require` list, so the analyzer has no record of it and its
         imports are fenced by nothing. Add it to the `ns` form above, or
         take the file out of the package"))

  (testing "the reads that matter are present"
    ;; Positive controls, because a fence over an empty graph is green
    ;; having checked nothing. Each names an edge that must be there and
    ;; would be there whatever else moved.
    (is (some #{"re-frame.hicasso"} (get graph "re-frame.hicasso.examples.slice.views"))
        "views depends on the public door")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.slice.events"))
        "events depends on core")
    (is (some #{"re-frame.routing"} (get graph "re-frame.hicasso.examples.slice.routes"))
        "routes depends on the routing artefact")
    (is (some #{"re-frame.adapter.uix"} (get graph "re-frame.hicasso.examples.slice.app"))
        "the entry point installs an adapter"))

  (testing "the strings and the theme tokens depend on NOTHING"
    ;; Not a control — a claim. Specification §7 says i18n and theming
    ;; need no subsystem, and the sharpest form of that sentence is that
    ;; the namespace holding both has an empty dependency set: not
    ;; `re-frame.core`, not the Hicasso door, not a formatting library.
    ;; The subscriptions that project it are ordinary subs in `subs`.
    (is (= [] (get graph "re-frame.hicasso.examples.slice.i18n"))
        "i18n grew a dependency; §7's claim is that it needs none")))

;; ---------------------------------------------------------------------------
;; The fence
;; ---------------------------------------------------------------------------

(def ^:private forbidden
  "The four families app code may not name, each with the reason a
  failure should print. Predicates rather than a list, so a namespace
  minted tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "the slice is evidence about the public door; a reach past it
             makes the evidence worth nothing"
    :match? #(str/starts-with? % "re-frame.hicasso.impl.")}
   {:label  "the benchmark tree"
    :why    "`re-frame.bench.*` is the measured prototype the package was
             moved out of; a consumer has no access to it"
    :match? #(str/starts-with? % "re-frame.bench.")}
   {:label  "a development tool"
    :why    "`tools/` is bundle-isolated from production builds — nothing
             in `implementation/` may require it, and an application is
             not an exception"
    :match? #(or (str/starts-with? % "re-frame.xray")
                 (str/starts-with? % "re-frame.story")
                 (str/starts-with? % "re-frame.machines-viz"))}
   {:label  "the test kit"
    :why    "`re-frame.hicasso.test` and its mounted facade are dev/test
             surfaces off the artefact's published `:paths`; an
             application that required one would not ship"
    :match? #(or (= % "re-frame.hicasso.test")
                 (str/starts-with? % "re-frame.hicasso.test."))}])

(deftest every-fence-predicate-fires
  ;; THE SABOTAGE CONTROL. The fence below is green today because the
  ;; slice is clean, and it would be just as green if a predicate had
  ;; been written wrong — `starts-with? "re-frame.hicasso.impl"` without
  ;; the trailing dot, say, or a family whose four spellings all miss.
  ;; So each predicate is shown one name it MUST catch and one it must
  ;; not, and the row that matters is the second: `re-frame.hicasso`
  ;; itself is the public door, and a fence that swallowed it would fail
  ;; every application rather than protect one.
  (let [breaches {"a Hicasso internal"   "re-frame.hicasso.impl.collector"
                  "the benchmark tree"   "re-frame.bench.hicasso.front.codec"
                  "a development tool"   "re-frame.xray.mount"
                  "the test kit"         "re-frame.hicasso.test.mounted"}]
    (doseq [{:keys [label match?]} forbidden]
      (is (contains? breaches label)
          (str "the fence grew a family with no sabotage row: " label))
      (is (true? (boolean (match? (get breaches label))))
          (str "the " label " predicate does not catch "
               (pr-str (get breaches label)) " — it would let one through"))
      (is (false? (boolean (match? "re-frame.hicasso")))
          (str "the " label " predicate catches the PUBLIC DOOR"))
      (is (false? (boolean (match? "re-frame.core")))
          (str "the " label " predicate catches core")))))

(deftest the-slice-names-no-private-namespace
  (doseq [{:keys [label why match?]} forbidden]
    (let [breaches (filterv (fn [[_ to]] (match? to)) foreign-edges)]
      (is (= [] breaches)
          (str "app code names " label ". " why ".")))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def ^:private expected-doors
  "Every foreign namespace the slice's application depends on, and why it
  is allowed to.

  Pinned, and the pin is the point: the fence above cannot see a new
  PUBLIC dependency arriving, and a slice that quietly grew one would
  stop being the small honest thing rf2-hic-026 freezes a facade from."
  {"re-frame.core"        "events, subscriptions, effects, frames"
   "re-frame.hicasso"     "the public door — defview, sub, use-subs, boundary,
                           route-link, reg-state, root!, render!"
   "re-frame.routing"     "reg-route; the navigate event and the route subs are
                           reached by id, not by dependency"
   "re-frame.adapter.uix" "the reactive adapter, installed once at boot
                           (Spec 006 §Adapter selection at boot)"})

(deftest the-slice-names-exactly-these-doors
  (let [named    (into (sorted-set) (map second) foreign-edges)
        expected (into (sorted-set) (keys expected-doors))]
    (testing "the roster and the graph agree, in both directions"
      ;; One equality rather than two subset checks, because the failure
      ;; a reader has to act on is the DIFFERENCE and `=` prints it. A
      ;; new name on the left is a dependency the slice grew; a name left
      ;; on the right is a roster entry that has rotted.
      (is (= expected named)
          "the slice's foreign dependencies moved. Add the new door to
           `expected-doors` WITH ITS REASON, or take the dependency back
           out — rf2-hic-026 reads this roster as the statement of what an
           ordinary Hicasso application needs, and four doors is the
           claim."))))

(deftest every-door-carries-its-reason
  (testing "no roster entry is a bare name"
    (is (= [] (filterv (fn [[_ why]] (str/blank? why)) expected-doors))
        "a roster whose entries carry no reason is a list, and a list is
         what this file exists not to be")))
