(ns re-frame.hicasso.examples.typeahead.surface-cljs-test
  "THE IMPORT DISCIPLINE, MECHANICALLY (rf2-hic-044).

  This application is the evidence `rf2-hic-050` decides the flagship
  resource experiment on. It is worth exactly as much as the claim that it
  was written on the PUBLIC DOOR — a witness that reached inside the
  runtime to make its ceremony smaller would be measuring something no
  consumer can write.

  So the claim is read off the ClojureScript analyzer's own dependency
  graph: what the build was built from, including the edges a `:refer`, a
  `:use` or a `:require-macros` establishes and an `ns`-form regex would
  miss. `h/defview` is a MACRO, so the macro edge is not a hypothetical.
  See [[re-frame.hicasso.examples.require-graph]] for the mechanism and
  for why the emitted graph cannot go stale.

  ## Three claims, and they are different

  [[the-app-names-no-private-namespace]] is the FENCE — no `impl`, no
  benchmark tree, no tool, no test kit, anywhere in application code. It
  is a set of predicates, so a namespace minted tomorrow is fenced the day
  it lands, and [[every-fence-predicate-fires]] is its sabotage control.

  [[the-app-names-exactly-these-doors]] is the ROSTER — the exact set of
  foreign namespaces the whole application depends on. It is the one that
  reds when the witness quietly grows a dependency, which a fence cannot
  see.

  [[the-model-tier-names-no-view-substrate]] is the sharpest. Every
  ceremony row this witness publishes is in the MODEL tier or in one view
  expression, and the model tier's rows are ordinary re-frame2 that could
  not tell you a view substrate existed. That is what lets `l0-cljs-test`
  assert the whole resource story with `=` and a map, and it is the
  property that would rot first if somebody reached for `h/sub` inside a
  handler to find out whether a read was live — which is precisely the
  reach the flagship experiment exists to make unnecessary.

  [[the-witness-registers-no-route]] is an absent edge, here for the same
  reason its two siblings carry one: route PATHS are plain strings in a
  process-global registry and this repository's node bundle loads every
  application in the tree into one process (rf2-hic-025 finding 8, filed
  as rf2-wqnl)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            ;; Required so the analyzer has analysed them when the macro
            ;; below expands — and, just as load-bearing, so shadow-cljs
            ;; recompiles THIS namespace when any of them changes.
            [re-frame.hicasso.examples.typeahead.app]
            [re-frame.hicasso.examples.typeahead.db]
            [re-frame.hicasso.examples.typeahead.events]
            [re-frame.hicasso.examples.typeahead.service]
            [re-frame.hicasso.examples.typeahead.subs]
            [re-frame.hicasso.examples.typeahead.views])
  (:require-macros [re-frame.hicasso.examples.require-graph :as rg]))

(def ^:private app-namespaces
  "Every namespace this APPLICATION is made of, read off the package
  directory at macro-expansion time rather than typed (rf2-ccuw). The
  test suites are deliberately absent: a test may reach past a door the
  application may not — `demand-dom-cljs-test` mounts through the test
  kit and wraps the screen in `React.StrictMode` — which is the whole
  reason the two are held to different rules, and the exclusion is the
  build's own `cljs-test$`. `census.clj` is a JVM macro namespace and no
  part of the compiled application, so no ClojureScript walk sees it."
  (rg/emit-application-namespaces re-frame.hicasso.examples.typeahead))

(def ^:private graph
  "Same population, other instrument: a namespace the analyzer never saw
  is absent here and present in [[app-namespaces]]."
  (rg/emit-dependency-graph re-frame.hicasso.examples.typeahead))

(def ^:private own? (set app-namespaces))

(def ^:private foreign-edges
  "Every dependency the application has on a namespace outside itself, as
  `[from to]` pairs so a failure names the file to open."
  (vec (for [[from tos] graph
             to         tos
             :when      (not (own? to))]
         [from to])))

;; ---------------------------------------------------------------------------
;; The instrument moves — asserted before anything is asserted with it
;; ---------------------------------------------------------------------------

(deftest the-graph-is-populated
  (testing "the analyzer answered for every application namespace"
    ;; The DIRECTORY on the left, the ANALYZER on the right. A namespace
    ;; the analyzer never saw is absent on the right and fenced by
    ;; nothing — every check below would pass over it VACUOUSLY.
    (is (= (set app-namespaces) (set (keys graph)))
        "a source file under `examples/typeahead/` is not on this
         namespace's `:require` list, so the analyzer has no record of it
         and its imports are fenced by nothing. Add it to the `ns` form
         above, or take the file out of the package"))

  (testing "the reads that matter are present"
    (is (some #{"re-frame.hicasso"} (get graph "re-frame.hicasso.examples.typeahead.views"))
        "views depends on the public door")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.typeahead.events"))
        "events depends on core")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.typeahead.service"))
        "the stand-in service depends on core, which is the whole of what
         an async effect needs from the runtime")
    (is (some #{"re-frame.adapter.uix"} (get graph "re-frame.hicasso.examples.typeahead.app"))
        "the entry point installs an adapter")))

(deftest the-model-tier-names-no-view-substrate
  ;; Not a control — the claim this witness's whole L0 suite rests on.
  (doseq [ns' ["re-frame.hicasso.examples.typeahead.db"
               "re-frame.hicasso.examples.typeahead.events"
               "re-frame.hicasso.examples.typeahead.service"
               "re-frame.hicasso.examples.typeahead.subs"]]
    (is (not (some #{"re-frame.hicasso"} (get graph ns')))
        (str ns' " reached the view door. Every OWNERSHIP row of the
              ceremony census is in this tier, and the point of the census
              is that the tier CANNOT see a read: a handler that reached
              for `h/sub` to find out whether a read was live would be
              reaching for the very thing the flagship experiment exists
              to supply, and the count would stop meaning anything"))))

;; ---------------------------------------------------------------------------
;; The fence
;; ---------------------------------------------------------------------------

(def ^:private forbidden
  "The four families app code may not name, each with the reason a failure
  should print. Predicates rather than a list, so a namespace minted
  tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "this application is the evidence rf2-hic-050 decides on; a
             reach past the public door makes the evidence worth nothing"
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
  ;; THE SABOTAGE CONTROL. The fence below is green because the
  ;; application is clean, and it would be just as green if a predicate
  ;; had been written wrong — `starts-with? "re-frame.hicasso.impl"`
  ;; without the trailing dot, say. So each predicate is shown one name it
  ;; MUST catch and two it must not, and the rows that matter are the
  ;; second and third: `re-frame.hicasso` is the public door and
  ;; `re-frame.core` is the framework, and a fence that swallowed either
  ;; would fail every application rather than protect one.
  (let [breaches {"a Hicasso internal" "re-frame.hicasso.impl.collector"
                  "the benchmark tree" "re-frame.bench.hicasso.front.codec"
                  "a development tool" "re-frame.xray.mount"
                  "the test kit"       "re-frame.hicasso.test.mounted"}]
    (is (= (set (map :label forbidden)) (set (keys breaches)))
        "every fenced family needs a sabotage row, and a sabotage row with
         no family is a row that proves nothing")
    (doseq [{:keys [label match?]} forbidden]
      (is (true? (boolean (match? (get breaches label))))
          (str "the " label " predicate does not catch "
               (pr-str (get breaches label)) " — it would let one through"))
      (is (false? (boolean (match? "re-frame.hicasso")))
          (str "the " label " predicate catches the PUBLIC DOOR"))
      (is (false? (boolean (match? "re-frame.core")))
          (str "the " label " predicate catches core")))))

(deftest the-app-names-no-private-namespace
  (doseq [{:keys [label why match?]} forbidden]
    (is (= [] (filterv (fn [[_ to]] (match? to)) foreign-edges))
        (str "app code names " label ". " why "."))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def ^:private expected-doors
  "Every foreign namespace this application depends on, and why it is
  allowed to.

  FOUR, the same four the slice needed, and that is the sentence this
  witness most wants on the record: a typeahead with two async resources,
  a debounce, supersession, refresh-with-data and cancellation is built on
  the same four doors a static form is. Nothing in the resource story
  required a fifth."
  {"clojure.string"       "trim and lower-case — in the model's term
                           normalisation and in the stand-in service's query"
   "re-frame.core"        "events, subscriptions, and the framework-shipped
                           :dispatch-later the debounce is written on"
   "re-frame.hicasso"     "the public door — defview, sub, root!, render!,
                           and the ::h/value / ::h/revision markers"
   "re-frame.adapter.uix" "the reactive adapter, installed once at boot
                           (Spec 006 §Adapter selection at boot)"})

(deftest the-app-names-exactly-these-doors
  (let [named    (into (sorted-set) (map second) foreign-edges)
        expected (into (sorted-set) (keys expected-doors))]
    ;; One equality rather than two subset checks, because the failure a
    ;; reader has to act on is the DIFFERENCE and `=` prints it.
    (is (= expected named)
        "the application's foreign dependencies moved. Add the new door to
         `expected-doors` WITH ITS REASON, or take the dependency back
         out — this roster is the claim that a resource-heavy application
         needs no more of the surface than a static one")))

(deftest every-door-carries-its-reason
  (is (= [] (filterv (fn [[_ why]] (str/blank? why)) expected-doors))
      "a roster whose entries carry no reason is a list, and a list is
       what this file exists not to be"))

;; ---------------------------------------------------------------------------
;; The absent edge
;; ---------------------------------------------------------------------------

(deftest the-witness-registers-no-route
  (testing "no application namespace reaches the routing artefact"
    (is (= [] (filterv (fn [[_ to]] (= "re-frame.routing" to)) foreign-edges))
        "the typeahead reached `re-frame.routing`. Route PATHS are plain
         strings in a PROCESS-GLOBAL registry and this repository's node
         bundle loads every application in the tree into one process, so
         an unprefixed path here silently changes which route another
         suite's URLs resolve to (rf2-hic-025 finding 8, rf2-wqnl).
         Nothing about a resource witness needs a URL; if one is genuinely
         wanted, prefix every path and add the door above"))

  (testing "the instrument can see a routing edge when there is one"
    ;; The sabotage. The row above is green because the edge is absent,
    ;; and it would be equally green if the filter answered nothing at
    ;; all — so it is shown a graph in which the edge IS present.
    (is (= [["x" "re-frame.routing"]]
           (filterv (fn [[_ to]] (= "re-frame.routing" to))
                    [["x" "re-frame.core"] ["x" "re-frame.routing"]]))
        "the routing filter does not match a routing edge")))
