(ns re-frame.hicasso.examples.forms.surface-cljs-test
  "THE FORMS RECIPES' IMPORT DISCIPLINE, MECHANICALLY (rf2-hic-051).

  The recipes are evidence about the DOOR, and they are worth exactly
  what that claim is worth. So the claim is read off the ClojureScript
  analyzer's own dependency graph rather than off four `ns` forms —
  including the edges a `:refer`, a `:use` or a `:require-macros`
  establishes and an `ns`-form regex would miss. See
  [[re-frame.hicasso.examples.require-graph]] for the mechanism, and for
  why the emitted graph cannot go stale.

  ## The ROSTER is the row that matters here

  These recipes name a door no other Hicasso witness names:
  `re-frame.resources`, for `reg-mutation`, `:rf.mutation/execute` and
  the passive `[:rf/mutation {:instance …}]` read. That is the whole of
  the mutation-status recipe and it is a real dependency an application
  takes on — the specification's §7 async row owns it, and the facade
  freeze wants it stated rather than discovered.

  ## And one ABSENCE

  [[the-recipes-register-no-route]]. Route PATHS are process-global
  strings and this repository's node bundle loads every witness into one
  process; rf2-hic-025's finding 8 cost twelve assertions and warned
  nobody. A form needs no routing to be evidence about forms, so it
  reaches none — and this row makes that mechanical rather than
  observed."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            ;; Required so the analyzer has analysed them when the macro
            ;; below expands — and, just as load-bearing, so shadow-cljs
            ;; recompiles THIS namespace when any of them changes.
            [re-frame.hicasso.examples.forms.db]
            [re-frame.hicasso.examples.forms.events]
            [re-frame.hicasso.examples.forms.subs]
            [re-frame.hicasso.examples.forms.views])
  (:require-macros [re-frame.hicasso.examples.require-graph :as rg]))

(def ^:private app-namespaces
  "Every namespace the recipes' APPLICATION is made of. Its suites are
  deliberately absent: a test may reach past a door the application may
  not — the mounted suite reaches the test kit — which is the whole
  reason the two are held to different rules."
  ["re-frame.hicasso.examples.forms.db"
   "re-frame.hicasso.examples.forms.events"
   "re-frame.hicasso.examples.forms.subs"
   "re-frame.hicasso.examples.forms.views"])

(def ^:private graph
  (rg/emit-dependency-graph
    '[re-frame.hicasso.examples.forms.db
      re-frame.hicasso.examples.forms.events
      re-frame.hicasso.examples.forms.subs
      re-frame.hicasso.examples.forms.views]))

(def ^:private own? (set app-namespaces))

(def ^:private foreign-edges
  "Every dependency on a namespace outside the application, as `[from to]`
  so a failure names the file to open."
  (vec (for [[from tos] graph
             to         tos
             :when      (not (own? to))]
         [from to])))

;; ---------------------------------------------------------------------------
;; The instrument moves — asserted before anything is asserted WITH it
;; ---------------------------------------------------------------------------

(deftest the-graph-is-populated
  (testing "the analyzer answered for every application namespace"
    (is (= (set app-namespaces) (set (keys graph)))
        "a namespace the analyzer has not analysed answers an EMPTY edge
         set, and an empty edge set passes every check below vacuously —
         so the roster and the graph's key set are compared first"))
  (testing "the reads that matter are present"
    (is (some #{"re-frame.hicasso"} (get graph "re-frame.hicasso.examples.forms.views"))
        "views depends on the public door")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.forms.events"))
        "events depends on core")
    (is (some #{"re-frame.resources"} (get graph "re-frame.hicasso.examples.forms.events"))
        "and on the resources artefact, which is where the mutation door
         lives — the one dependency these recipes add over every other
         Hicasso witness")))

;; ---------------------------------------------------------------------------
;; The fence
;; ---------------------------------------------------------------------------

(def ^:private forbidden
  "The families application code may not name. Predicates rather than a
  list, so a namespace minted tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "these recipes are evidence about the public door; a reach
             past it makes the evidence worth nothing"
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
  ;; application is clean, and it would be just as green if a predicate
  ;; had been written wrong. So each is shown one name it MUST catch and
  ;; two it must not — and the second half is the one nothing else would
  ;; notice, because a predicate that swallowed `re-frame.hicasso` itself
  ;; would fail every application rather than protect one.
  (let [breaches {"a Hicasso internal" "re-frame.hicasso.impl.collector"
                  "the benchmark tree" "re-frame.bench.hicasso.front.codec"
                  "a development tool" "re-frame.xray.mount"
                  "the test kit"       "re-frame.hicasso.test.mounted"}]
    (doseq [{:keys [label match?]} forbidden]
      (is (contains? breaches label)
          (str "the fence grew a family with no sabotage row: " label))
      (is (true? (boolean (match? (get breaches label))))
          (str "the " label " predicate does not catch "
               (pr-str (get breaches label)) " — it would let one through"))
      (is (false? (boolean (match? "re-frame.hicasso")))
          (str "the " label " predicate catches the PUBLIC DOOR"))
      (is (false? (boolean (match? "re-frame.resources")))
          (str "the " label " predicate catches the resources artefact")))))

(deftest the-recipes-name-no-private-namespace
  (doseq [{:keys [label why match?]} forbidden]
    (is (= [] (filterv (fn [[_ to]] (match? to)) foreign-edges))
        (str "app code names " label ". " why "."))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def ^:private expected-doors
  "Every foreign namespace this application depends on, and why."
  {"clojure.string"    "blank? and trim, in the validation rules"
   "re-frame.core"     "events, subscriptions, reg-mutation"
   "re-frame.hicasso"  "the public door — defview, sub, reg-state, and the
                        ::h/value and ::h/revision markers"
   "re-frame.resources" "the mutation door: :rf.mutation/execute and the
                         passive [:rf/mutation {:instance …}] read"})

(deftest the-recipes-name-exactly-these-doors
  (let [named    (into (sorted-set) (map second) foreign-edges)
        expected (into (sorted-set) (keys expected-doors))]
    (is (= expected named)
        "the application's foreign dependencies moved. Add the new door to
         `expected-doors` WITH ITS REASON, or take the dependency back out
         — a forms layer that quietly grew a fifth is no longer evidence
         that a form needs four")))

(deftest every-door-carries-its-reason
  (is (= [] (filterv (fn [[_ why]] (str/blank? why)) expected-doors))
      "a roster whose entries carry no reason is a list, and a list is what
       this file exists not to be"))

(deftest the-recipes-register-no-route
  (is (= [] (filterv (fn [[_ to]] (= "re-frame.routing" to)) foreign-edges))
      "route PATHS are process-global strings and the node bundle loads
       every witness into one process. A form needs no routing to be
       evidence about forms — see the namespace docstring"))
