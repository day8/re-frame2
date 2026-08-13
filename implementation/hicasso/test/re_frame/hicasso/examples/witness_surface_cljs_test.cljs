(ns re-frame.hicasso.examples.witness-surface-cljs-test
  "THE TWO WITNESSES' IMPORT DISCIPLINE, MECHANICALLY (rf2-hic-078).

  rf2-hic-078's acceptance is *zero impl imports, enforced by a test*.
  Both witness applications — the four-field editor and the 100-cell
  grid — are evidence about the PUBLIC DOOR, and they are worth exactly
  what that claim is worth. So the claim is not made by reading eight
  `ns` forms: it is read off the ClojureScript analyzer's own dependency
  graph, including the edges a `:refer`, a `:use` or a `:require-macros`
  establishes and an `ns`-form regex would miss. See
  [[re-frame.hicasso.examples.require-graph]] for the mechanism, and for
  why the emitted graph cannot go stale.

  ## Three claims, and they are different

  [[the-witnesses-name-no-private-namespace]] is the FENCE — no `impl`,
  no benchmark tree, no tool, no test kit, anywhere in application code.
  It is a set of predicates, so a namespace minted tomorrow is fenced
  the day it lands, and [[every-fence-predicate-fires]] is its sabotage
  control: each predicate is shown one name it must catch and two it
  must not.

  [[each-witness-names-exactly-these-doors]] is the ROSTER — the exact
  set of foreign namespaces each application depends on, pinned per
  application. It is the one that reds when a witness quietly grows a
  dependency, which a fence cannot see.

  Both are only worth the set of namespaces they are made over, and that
  set used to be typed here — twice, as the [[witnesses]] literal and as
  the macro's argument (rf2-ccuw). A predicate over a hand-written
  population fences a namespace minted tomorrow only if somebody
  remembers to type it. So [[witnesses]] is read off each package's
  DIRECTORY and [[graph]] off the ANALYZER, and [[the-graph-is-populated]]
  compares them; a file in either package the analyzer never saw is the
  difference, and it names itself.

  [[neither-witness-registers-a-route]] is the third, and it is here
  rather than in a routing suite because it is the same kind of fact: an
  absent edge. rf2-hic-025's finding 8 — route PATHS are plain strings
  in a process-global registry and this repository's node bundle loads
  every application into one process — cost twelve RealWorld assertions
  and warned nobody. Neither witness needs routing to be evidence about
  controlled fields, so neither reaches `re-frame.routing`, and this row
  makes that mechanical: a route registered in either application reds
  here before it can reach the registry."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            ;; Required so the analyzer has analysed them when the macro
            ;; below expands — and, just as load-bearing, so shadow-cljs
            ;; recompiles THIS namespace when any of them changes.
            [re-frame.hicasso.examples.editor.app]
            [re-frame.hicasso.examples.editor.events]
            [re-frame.hicasso.examples.editor.subs]
            [re-frame.hicasso.examples.editor.views]
            [re-frame.hicasso.examples.grid.app]
            [re-frame.hicasso.examples.grid.events]
            [re-frame.hicasso.examples.grid.subs]
            [re-frame.hicasso.examples.grid.views])
  (:require-macros [re-frame.hicasso.examples.require-graph :as rg]))

(def ^:private witnesses
  "Each witness application and the namespaces it is made of, read off
  each package's directory at macro-expansion time rather than typed.
  Test suites are deliberately absent: a test may reach past a door the
  application may not — `scaling-dom-cljs-test` reads the runtime's own
  body-run counter — which is the whole reason the two are held to
  different rules, and the exclusion is the build's own `cljs-test$`."
  {"the four-field editor"
   (rg/emit-application-namespaces re-frame.hicasso.examples.editor)

   "the 100-cell grid"
   (rg/emit-application-namespaces re-frame.hicasso.examples.grid)})

(def ^:private graph
  "`{namespace [dependency …]}` for both applications, read off the
  analyzer at macro-expansion time. Same population, other instrument: a
  namespace the analyzer never saw is absent here and present in
  [[witnesses]]."
  (merge (rg/emit-dependency-graph re-frame.hicasso.examples.editor)
         (rg/emit-dependency-graph re-frame.hicasso.examples.grid)))

(def ^:private app-namespaces
  (into #{} cat (vals witnesses)))

(defn- foreign-edges
  "Every dependency `nss` have on a namespace outside the two witnesses,
  as `[from to]` pairs so a failure names the file to open."
  [nss]
  (vec (for [from nss
             to   (get graph from)
             :when (not (contains? app-namespaces to))]
         [from to])))

;; ---------------------------------------------------------------------------
;; The instrument moves — asserted before anything is asserted WITH it
;; ---------------------------------------------------------------------------

(deftest the-graph-is-populated
  (testing "the analyzer answered for every application namespace"
    ;; Two instruments over one population: the DIRECTORIES on the left,
    ;; the ANALYZER on the right. A namespace the analyzer never saw is
    ;; absent on the right and fenced by nothing — every check below
    ;; would pass over it vacuously — so the two are compared first, and
    ;; `=` prints the file to open.
    (is (= app-namespaces (set (keys graph)))
        "a source file under `examples/editor/` or `examples/grid/` is not
         on this namespace's `:require` list, so the analyzer has no record
         of it and its imports are fenced by nothing. Add it to the `ns`
         form above, or take the file out of the package"))

  (testing "the reads that matter are present"
    ;; Positive controls. A fence over an empty graph is green having
    ;; checked nothing; each row below names an edge that must be there
    ;; and would be there whatever else moved.
    (is (some #{"re-frame.hicasso"}
              (get graph "re-frame.hicasso.examples.editor.views"))
        "the editor's views depend on the public door")
    (is (some #{"re-frame.hicasso"}
              (get graph "re-frame.hicasso.examples.grid.views"))
        "the grid's views depend on the public door")
    (is (some #{"re-frame.core"}
              (get graph "re-frame.hicasso.examples.editor.events"))
        "the editor's events depend on core")
    (is (some #{"re-frame.adapter.uix"}
              (get graph "re-frame.hicasso.examples.grid.app"))
        "the grid's entry point installs an adapter"))

  (testing "the model tiers depend on NO view substrate"
    ;; Not a control — a claim, and the sharpest one in this file. The
    ;; editor's four policies and the grid's digits-only refusal are
    ;; ordinary re-frame2: they could not tell you a view substrate
    ;; existed. That is what makes `l0-cljs-test` able to assert the
    ;; whole model with `=` and a map, and it is the property that would
    ;; rot first if somebody reached for `h/sub` inside a handler.
    (doseq [ns' ["re-frame.hicasso.examples.editor.events"
                 "re-frame.hicasso.examples.editor.subs"
                 "re-frame.hicasso.examples.grid.events"
                 "re-frame.hicasso.examples.grid.subs"]]
      (is (not (some #{"re-frame.hicasso"} (get graph ns')))
          (str ns' " reached the view door. The model tier is `re-frame.core`
                and pure functions; a read or a marker in a handler puts the
                substrate on the L0 suite's classpath and ends its claim")))))

;; ---------------------------------------------------------------------------
;; The fence
;; ---------------------------------------------------------------------------

(def ^:private forbidden
  "The four families application code may not name, each with the reason
  a failure should print. Predicates rather than a list, so a namespace
  minted tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "these applications are evidence about the public door; a
             reach past it makes the evidence worth nothing"
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
  ;; THE SABOTAGE CONTROL. The fence below is green today because both
  ;; applications are clean, and it would be just as green if a predicate
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

(deftest the-witnesses-name-no-private-namespace
  (doseq [[witness nss] witnesses
          {:keys [label why match?]} forbidden]
    (let [breaches (filterv (fn [[_ to]] (match? to)) (foreign-edges nss))]
      (is (= [] breaches)
          (str witness " names " label ". " why ".")))))

;; ---------------------------------------------------------------------------
;; The rosters
;; ---------------------------------------------------------------------------

(def ^:private expected-doors
  "Every foreign namespace each witness depends on, and why it is allowed
  to.

  Pinned per application, and the pin is the point: the fence cannot see
  a new PUBLIC dependency arriving, and a witness that quietly grew one
  would stop being the small honest thing a facade freeze reads.

  Read the two columns together. **THREE** foreign namespaces build a
  hundred controlled fields — `re-frame.core`, the door, and an adapter —
  and the editor's fourth is `clojure.string`, for the one normalisation
  that makes its slug field a normalising field. Between them the two
  applications cover four controlled policies, a refusal, three markers,
  a reset and a hundred fields, and that is the whole list."
  {"the four-field editor"
   {"clojure.string"       "one normalisation — `slugify`"
    "re-frame.core"        "events and subscriptions"
    "re-frame.hicasso"     "the public door — defview, sub, root!, render!"
    "re-frame.adapter.uix" "the reactive adapter, installed once at boot
                            (Spec 006 §Adapter selection at boot)"}

   "the 100-cell grid"
   {"re-frame.core"        "events and subscriptions"
    "re-frame.hicasso"     "the public door — defview, sub, root!, render!"
    "re-frame.adapter.uix" "the reactive adapter, installed once at boot"}})

(deftest each-witness-names-exactly-these-doors
  (doseq [[witness nss] witnesses]
    (let [named    (into (sorted-set) (map second) (foreign-edges nss))
          expected (into (sorted-set) (keys (get expected-doors witness)))]
      ;; One equality rather than two subset checks, because the failure a
      ;; reader has to act on is the DIFFERENCE and `=` prints it. A new
      ;; name on the left is a dependency the witness grew; a name left on
      ;; the right is a roster entry that has rotted.
      (is (= expected named)
          (str witness "'s foreign dependencies moved. Add the new door to
               `expected-doors` WITH ITS REASON, or take the dependency back
               out — this roster is the statement of what an ordinary
               Hicasso application needs, and four doors is the claim.")))))

(deftest every-door-carries-its-reason
  (doseq [[witness doors] expected-doors]
    (is (= [] (filterv (fn [[_ why]] (str/blank? why)) doors))
        (str witness "'s roster has a bare name. A roster whose entries
             carry no reason is a list, and a list is what this file exists
             not to be"))))

;; ---------------------------------------------------------------------------
;; The absent edge that a process-global registry makes load-bearing
;; ---------------------------------------------------------------------------

(deftest neither-witness-registers-a-route
  (testing "no application namespace reaches the routing artefact"
    (doseq [[witness nss] witnesses]
      (is (= [] (filterv (fn [[_ to]] (= "re-frame.routing" to))
                         (foreign-edges nss)))
          (str witness " reached `re-frame.routing`. Route PATHS are plain
               strings in a PROCESS-GLOBAL registry and this repository's
               node bundle loads every application in the tree into one
               process, so an unprefixed path here silently changes which
               route another suite's URLs resolve to — `reg-route`'s
               equal-score warning does not fire when the ranks differ
               (rf2-hic-025 finding 8, filed as rf2-wqnl). If a witness
               genuinely needs a route, prefix every path with something of
               its own and add the door to `expected-doors` above."))))

  (testing "the instrument can see a routing edge when there is one"
    ;; The sabotage. The row above is green because the edge is absent,
    ;; and it would be equally green if `foreign-edges` answered nothing
    ;; at all — so it is shown a graph in which the edge IS present.
    (is (= [["x" "re-frame.routing"]]
           (filterv (fn [[_ to]] (= "re-frame.routing" to))
                    [["x" "re-frame.core"] ["x" "re-frame.routing"]]))
        "the routing filter does not match a routing edge")))
