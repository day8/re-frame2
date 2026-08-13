(ns re-frame.hicasso.examples.navigation.surface-cljs-test
  "THE NAVIGATION WITNESS'S IMPORT DISCIPLINE, MECHANICALLY (rf2-hcgo;
  rf2-hic-042).

  This application is the evidence specification §7's routing row is
  decided on — deep link, Back/Forward, pending mutation, and the
  dirty-leave / scroll-restoration / focus-on-route recipes. It is worth
  exactly as much as the claim that it was written on the PUBLIC DOOR: a
  witness that reached inside the runtime to make a recipe smaller would
  be showing a consumer something the consumer cannot write.

  So the claim is read off the ClojureScript analyzer's own dependency
  graph — what the build was built from, including the edges a `:refer`,
  a `:use` or a `:require-macros` establishes and an `ns`-form regex
  would miss. `h/defview` is a MACRO, so the macro edge is not a
  hypothetical. See [[re-frame.hicasso.examples.require-graph]] for the
  mechanism and for why the emitted graph cannot go stale.

  ## Why this file exists at all, which is the part worth reading

  Seven of the eight witness packages under `examples/` carried a fence
  and this one did not, for a full day, while two further fences were
  authored alongside it (rf2-hcgo, rf2-689l). rf2-ccuw had just made each
  fence's POPULATION derived so a namespace added inside a fenced package
  could not escape — and that bought nothing here, because a package no
  fence names is fenced by no population at all. Nothing asserted that
  this application stayed off `re-frame.hicasso.impl.*`, the benchmark
  tree, `tools/`, or the test kit.

  ## Three claims, and they are different

  [[the-witness-names-no-private-namespace]] is the FENCE — no `impl`, no
  benchmark tree, no tool, no test kit, anywhere in application code. It
  is a set of predicates, so a namespace minted tomorrow is fenced the
  day it lands, and [[every-fence-predicate-fires]] is its sabotage
  control.

  [[the-witness-names-exactly-these-doors]] is the ROSTER — the exact set
  of foreign namespaces the whole application depends on. It is the one
  that reds when the witness quietly grows a dependency, which a fence
  cannot see. THREE, and that is the sentence this witness most wants on
  the record: two routes, a `:can-leave` guard, a parked-navigation
  region, a scroll story and a focus-on-route recipe written entirely on
  `re-frame.core`, the Hicasso door, and `reg-route`.

  [[the-model-tier-names-no-view-substrate]] is the sharpest. The routing
  conduct this witness measures — the guard's verdict, the `:on-match`
  recipe, the routes themselves — is ordinary re-frame2 that could not
  tell you a view substrate existed. That is what lets a structural
  witness read `events/pane-shown`'s return value with `=` and a map, and
  it is the property that would rot first if somebody reached for `h/sub`
  inside a handler.

  ## And the present edge the siblings only have as an absent one

  Every other witness's fence carries a row asserting it does NOT reach
  `re-frame.routing`, because route PATHS are plain strings in a
  PROCESS-GLOBAL registrar and this repository's node bundle loads every
  application in the tree into one process (rf2-hic-025 finding 8, filed
  as rf2-wqnl). This is the one package where that edge is PRESENT and
  the hazard is therefore live: `routes.cljs` ends on a bare top-level
  `(register!)`, so merely compiling this package into the node bundle
  writes two paths into the registrar every other suite's URLs are
  matched against.

  So [[every-path-this-witness-registers-is-under-its-own-prefix]] is the
  positive counterpart of the siblings' absent-edge row: it enumerates
  the registrar for the ids this package owns and asserts each one's path
  is under `/navigation`. It reads `route-ids` / `route-meta` rather than
  a literal, so a THIRD route added tomorrow is covered the day it lands
  — the same property the derived population buys one level down."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.routing :as routing]
            ;; Required so the analyzer has analysed them when the macro
            ;; below expands — and, just as load-bearing, so shadow-cljs
            ;; recompiles THIS namespace when any of them changes.
            [re-frame.hicasso.examples.navigation.events]
            [re-frame.hicasso.examples.navigation.routes :as routes]
            [re-frame.hicasso.examples.navigation.subs]
            [re-frame.hicasso.examples.navigation.views])
  (:require-macros [re-frame.hicasso.examples.require-graph :as rg]))

(def ^:private app-namespaces
  "Every namespace this APPLICATION is made of, read off the package
  directory at macro-expansion time rather than typed (rf2-ccuw). The
  test suites are deliberately absent: a test may reach past a door the
  application may not — `conduct-dom-cljs-test` mounts through the test
  kit and installs an adapter — which is the whole reason the two are
  held to different rules, and the exclusion is the build's own
  `cljs-test$`."
  (rg/emit-application-namespaces re-frame.hicasso.examples.navigation))

(def ^:private graph
  "Same population, other instrument: a namespace the analyzer never saw
  is absent here and present in [[app-namespaces]]."
  (rg/emit-dependency-graph re-frame.hicasso.examples.navigation))

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
        "a source file under `examples/navigation/` is not on this
         namespace's `:require` list, so the analyzer has no record of it
         and its imports are fenced by nothing. Add it to the `ns` form
         above, or take the file out of the package"))

  (testing "the reads that matter are present"
    ;; Positive controls, because a fence over an empty graph is green
    ;; having checked nothing. Each names an edge that must be there and
    ;; would be there whatever else moved.
    (is (some #{"re-frame.hicasso"} (get graph "re-frame.hicasso.examples.navigation.views"))
        "views depends on the public door")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.navigation.events"))
        "events depends on core — the focus-on-route recipe is a
         `reg-event` and a `reg-fx` and nothing else, which is the whole
         claim that an application can write one")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.navigation.subs"))
        "subs depends on core, including the `:can-leave` guard routing
         itself reads")
    (is (some #{"re-frame.routing"} (get graph "re-frame.hicasso.examples.navigation.routes"))
        "routes depends on the routing artefact")))

(deftest the-model-tier-names-no-view-substrate
  ;; Not a control — the claim the structural half of the conduct witness
  ;; rests on. Every routing decision this application makes is in the
  ;; MODEL tier, and that tier's code is ordinary re-frame2 that could not
  ;; tell you a view substrate existed.
  (doseq [ns' ["re-frame.hicasso.examples.navigation.events"
               "re-frame.hicasso.examples.navigation.routes"
               "re-frame.hicasso.examples.navigation.subs"]]
    (is (not (some #{"re-frame.hicasso"} (get graph ns')))
        (str ns' " reached the view door. The model tier is
              `re-frame.core` and pure functions; a `can-leave` verdict or
              an `:on-match` recipe that needed the substrate would stop
              being something a consumer could read without one"))))

;; ---------------------------------------------------------------------------
;; The fence
;; ---------------------------------------------------------------------------

(def ^:private forbidden
  "The four families app code may not name, each with the reason a
  failure should print. Predicates rather than a list, so a namespace
  minted tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "this application is the evidence §7's routing row is decided
             on; a reach past the public door makes the evidence worth
             nothing"
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

(deftest the-witness-names-no-private-namespace
  (doseq [{:keys [label why match?]} forbidden]
    (is (= [] (filterv (fn [[_ to]] (match? to)) foreign-edges))
        (str "app code names " label ". " why "."))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def ^:private expected-doors
  "Every foreign namespace this application depends on, and why it is
  allowed to.

  THREE, and there is no adapter among them: this witness has no `app.cljs`
  entry point, because its conduct is measured by a suite that installs the
  adapter and mounts it — which is exactly how a consumer's own tests reach
  an application they did not boot."
  {"re-frame.core"    "events, subscriptions, and the `reg-fx` the
                       focus-on-route recipe is written as — the framework
                       ships no focus facility and says so, so this is what
                       an application writing one needs"
   "re-frame.hicasso" "the public door — defview, sub, route-link, and the
                       ::h/value marker"
   "re-frame.routing" "reg-route, and the route metadata (`:on-match`,
                       `:can-leave`) that carries the whole of this
                       application's conduct; the navigate event and the
                       route subs are reached by id, not by dependency"})

(deftest the-witness-names-exactly-these-doors
  (let [named    (into (sorted-set) (map second) foreign-edges)
        expected (into (sorted-set) (keys expected-doors))]
    ;; One equality rather than two subset checks, because the failure a
    ;; reader has to act on is the DIFFERENCE and `=` prints it.
    (is (= expected named)
        "the application's foreign dependencies moved. Add the new door to
         `expected-doors` WITH ITS REASON, or take the dependency back
         out — this roster is the claim that the whole routing story needs
         three doors")))

(deftest every-door-carries-its-reason
  (is (= [] (filterv (fn [[_ why]] (str/blank? why)) expected-doors))
      "a roster whose entries carry no reason is a list, and a list is
       what this file exists not to be"))

;; ---------------------------------------------------------------------------
;; The present edge — the one the siblings only carry as an absent one
;; ---------------------------------------------------------------------------

(deftest every-path-this-witness-registers-is-under-its-own-prefix
  ;; `register!` rather than trusting the load-time call: the reset
  ;; fixtures other suites in this bundle install restore the registrar to
  ;; a baseline captured at their own load, which can roll this package's
  ;; registration back. It is idempotent, so calling it costs nothing.
  (routes/register!)
  (let [ours (filterv #(= (namespace routes/feed) (namespace %))
                      (routing/route-ids))]
    (testing "the enumeration found this package's routes"
      ;; The instrument moves — asserted before anything is asserted with
      ;; it. An empty `ours` would make the loop below green having
      ;; checked nothing, which is the failure mode this whole file is
      ;; written against.
      (is (contains? (set ours) routes/article)
          "the registrar does not hold this package's article route, so
           the prefix loop below would pass over nothing. Either
           `register!` stopped registering or the route ids moved
           namespace"))

    (doseq [id ours]
      (is (str/starts-with? (str (:path (routing/route-meta id))) "/navigation")
          (str "route " id " registers a path outside `/navigation`. Route
                PATHS are plain strings in a PROCESS-GLOBAL registrar and
                this repository's node bundle loads every application in
                the tree into one process, so an unprefixed path here
                silently changes which route another suite's URLs resolve
                to — `reg-route`'s equal-score warning does not fire when
                the ranks differ (rf2-hic-025 finding 8, rf2-wqnl). Give
                every path of this package the same leading segment.")))))
