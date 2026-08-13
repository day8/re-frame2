(ns re-frame.hicasso.examples.ledger.surface-cljs-test
  "THE LEDGER'S IMPORT DISCIPLINE, MECHANICALLY (rf2-hic-047).

  Every application under `examples/` carries this file, and its two
  standing claims — the fence and the roster — are `todo.surface-cljs-test`'s,
  written the same way and for the same reason. Read that file's
  docstring for the mechanism; it is not restated here.

  What IS this file's own is the third claim, and it is the one the
  serious-vendor screen exists to make.

  ## The vendor is foreign, and the analyzer says so

  A recipe for reaching a third-party React component is worth nothing if
  the component was quietly written against Hicasso. So
  [[the-vendor-knows-nothing-about-us]] reads
  `examples.ledger.vendor`'s own dependency edges and asserts they are
  `react` and only `react` — no public door, no core, no native tier, no
  test kit. The virtualizer could be lifted out of this repository into
  an npm package without changing a character, which is exactly the claim
  a consumer needs before believing that `h/defhost` is all their own
  virtualizer will need.

  The other direction is the roster: `examples.ledger.vendor` appears
  there as a foreign door of the application, because from the
  application's side that is precisely what it is — the package it
  installed."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            ;; Required so the analyzer has analysed them when the macro
            ;; below expands — and, just as load-bearing, so shadow-cljs
            ;; recompiles THIS namespace when any of them changes.
            [re-frame.hicasso.examples.ledger.app]
            [re-frame.hicasso.examples.ledger.events]
            [re-frame.hicasso.examples.ledger.subs]
            [re-frame.hicasso.examples.ledger.vendor]
            [re-frame.hicasso.examples.ledger.views])
  (:require-macros [re-frame.hicasso.examples.require-graph :as rg]))

(def ^:private vendor-ns
  "The virtualizer. Deliberately NOT one of the application's own
  namespaces below: it stands in for an npm package, so from the
  application's side it is a foreign door and is held to the roster like
  any other."
  "re-frame.hicasso.examples.ledger.vendor")

(def ^:private package-namespaces
  "Every ClojureScript namespace under `examples/ledger/`, read off the
  package directory at macro-expansion time rather than typed (rf2-ccuw).
  Test suites are deliberately absent — a test may reach past a door the
  application may not, which is the whole reason the two are held to
  different rules, and the exclusion is the build's own `cljs-test$`."
  (rg/emit-application-namespaces re-frame.hicasso.examples.ledger))

(def ^:private app-namespaces
  "The package MINUS the vendor: the namespaces the application itself is
  made of. The one subtraction is spelled out above, and it is the only
  name this file still holds by hand — everything else arrives from the
  directory, so a namespace added to the ledger tomorrow is fenced the
  day it lands."
  (vec (remove #{vendor-ns} package-namespaces)))

(def ^:private graph
  "`{namespace [dependency …]}`, read off the analyzer at
  macro-expansion time. The vendor is included so that its OWN edges can
  be asserted; it is excluded from `app-namespaces` so that the
  application's edge onto it is counted as foreign. Same population as
  [[package-namespaces]], other instrument: a namespace the analyzer
  never saw is absent here and present there."
  (rg/emit-dependency-graph re-frame.hicasso.examples.ledger))

(def ^:private own? (set app-namespaces))

(def ^:private foreign-edges
  "Every dependency the application has on a namespace outside itself, as
  `[from to]` pairs so a failure names the file to open. The vendor's own
  edges are not the application's and are excluded here; they are the
  subject of [[the-vendor-knows-nothing-about-us]]."
  (vec (for [[from tos] graph
             :when      (own? from)
             to         tos
             :when      (not (own? to))]
         [from to])))

;; ---------------------------------------------------------------------------
;; The instrument moves — asserted before anything is asserted WITH it
;; ---------------------------------------------------------------------------

(deftest the-graph-is-populated
  (testing "the analyzer answered for every namespace asked about"
    ;; The DIRECTORY on the left, the ANALYZER on the right. A namespace
    ;; the analyzer never saw is absent on the right and fenced by
    ;; nothing — every check below would pass over it VACUOUSLY.
    (is (= (set package-namespaces) (set (keys graph)))
        "a source file under `examples/ledger/` is not on this namespace's
         `:require` list, so the analyzer has no record of it and its
         imports are fenced by nothing. Add it to the `ns` form above, or
         take the file out of the package")

    (is (some #{vendor-ns} package-namespaces)
        "the vendor is the one name this file subtracts by hand, and a
         subtraction of a name that is no longer there would quietly stop
         subtracting anything"))

  (testing "the reads that matter are present"
    ;; Positive controls, because a fence over an empty graph is green
    ;; having checked nothing.
    (is (some #{"re-frame.hicasso"} (get graph "re-frame.hicasso.examples.ledger.views"))
        "the views depend on the public door — `defview`, `defhost`, `hfn`,
         `as-element`, `sub`")
    (is (some #{vendor-ns} (get graph "re-frame.hicasso.examples.ledger.views"))
        "and on the virtualizer, which is the whole point of the screen")
    (is (some #{"re-frame.core"} (get graph "re-frame.hicasso.examples.ledger.events"))
        "the model depends on core")
    (is (some #{"re-frame.adapter.uix"} (get graph "re-frame.hicasso.examples.ledger.app"))
        "the entry point installs an adapter"))

  (testing "the model tier depends on NO view substrate"
    ;; A claim, not a control, and the sharpest in this file after the
    ;; vendor row. Virtualization is a RENDERING strategy: the model
    ;; behind a ten-thousand-row screen could not tell you a view
    ;; substrate existed, let alone a windowing one.
    (doseq [ns' ["re-frame.hicasso.examples.ledger.events"
                 "re-frame.hicasso.examples.ledger.subs"]]
      (is (not (some #{"re-frame.hicasso"} (get graph ns')))
          (str ns' " reached the view door. The model tier is `re-frame.core`
                and pure functions; a read or a marker in a handler puts the
                substrate on the L0 suite's classpath and ends its claim"))
      (is (not (some #{vendor-ns} (get graph ns')))
          (str ns' " reached the VIRTUALIZER. A model that knows how its
                rows are windowed is a model that would have to change if
                they stopped being windowed")))))

;; ---------------------------------------------------------------------------
;; The vendor is foreign — this file's own claim
;; ---------------------------------------------------------------------------

(deftest the-vendor-knows-nothing-about-us
  (let [edges (set (get graph vendor-ns))]
    (is (= [] (filterv #(str/starts-with? % "re-frame.") (vec edges)))
        "THE CLAIM. Not one edge onto anything of ours — no public door, no
         core, no native tier, no test kit — which is what makes the
         virtualizer evidence about `h/defhost` rather than a component
         written to suit it. It could be lifted out of this repository
         into an npm package without changing a character, and that is
         exactly what a consumer needs to believe before believing a
         declaration is all their own virtualizer will need.")
    (is (= #{"shadow.js.shim.module$react"} edges)
        "and its whole dependency set is React. The spelling is
         shadow-cljs's: a string require — `[\"react\" :as react]` — is
         recorded by the analyzer under the module shim namespace it
         mints, not under the package name. Pinned rather than pattern-
         matched, because a SECOND npm package arriving here is a fact
         about the stand-in worth reviewing.")))

;; ---------------------------------------------------------------------------
;; The fence
;; ---------------------------------------------------------------------------

(def ^:private forbidden
  "The four families app code may not name, each with the reason a
  failure should print. Predicates rather than a list, so a namespace
  minted tomorrow is fenced the day it lands."
  [{:label  "a Hicasso internal"
    :why    "this application is evidence about the public door; a reach
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
  ;; had been written wrong — `starts-with? "re-frame.hicasso.impl"`
  ;; without the trailing dot, say.
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
      (is (false? (boolean (match? "re-frame.core")))
          (str "the " label " predicate catches core"))
      (is (false? (boolean (match? vendor-ns)))
          (str "the " label " predicate catches the VENDOR, which is a
                foreign package and a legal dependency")))))

(deftest the-app-names-no-private-namespace
  (doseq [{:keys [label why match?]} forbidden]
    (let [breaches (filterv (fn [[_ to]] (match? to)) foreign-edges)]
      (is (= [] breaches)
          (str "app code names " label ". " why ".")))))

;; ---------------------------------------------------------------------------
;; The roster
;; ---------------------------------------------------------------------------

(def ^:private expected-doors
  "Every foreign namespace this application depends on, and why.

  FOUR, and three of them are the same three the 100-cell grid names.
  That is the screen's headline read as a table: **a serious foreign
  component costs one line on this roster.** No wrapper library, no
  adapter namespace, no interop shim — the fourth entry is the vendor
  itself, reached through a declaration."
  {"re-frame.core"        "events and subscriptions"
   "re-frame.hicasso"     "the public door — defview, defhost, hfn, as-element,
                           sub, root!, render!, and the ::h/value marker"
   "re-frame.adapter.uix" "the reactive adapter, installed once at boot
                           (Spec 006 §Adapter selection at boot)"
   "re-frame.hicasso.examples.ledger.vendor"
   "the blessed virtualizer, standing in for the npm package a consumer
    would install"})

(deftest the-app-names-exactly-these-doors
  (let [named    (into (sorted-set) (map second) foreign-edges)
        expected (into (sorted-set) (keys expected-doors))]
    ;; One equality rather than two subset checks, because the failure a
    ;; reader has to act on is the DIFFERENCE and `=` prints it.
    (is (= expected named)
        "the application's foreign dependencies moved. Add the new door to
         `expected-doors` WITH ITS REASON, or take the dependency back out
         — four doors, one of them a third-party component, is this
         screen's central claim.")))

(deftest every-door-carries-its-reason
  (is (= [] (filterv (fn [[_ why]] (str/blank? why)) expected-doors))
      "a roster whose entries carry no reason is a list, and a list is what
       this file exists not to be"))

(deftest neither-half-registers-a-route
  ;; Same fact, same reason, as every other application in this tree:
  ;; route paths are plain strings in a PROCESS-GLOBAL registry and this
  ;; repository's node bundle loads every application in the tree into one
  ;; process (rf2-hic-025 finding 8).
  (testing "no application namespace reaches the routing artefact"
    (is (= [] (filterv (fn [[_ to]] (= "re-frame.routing" to)) foreign-edges))))
  (testing "the instrument can see a routing edge when there is one"
    (is (= [["x" "re-frame.routing"]]
           (filterv (fn [[_ to]] (= "re-frame.routing" to))
                    [["x" "re-frame.core"] ["x" "re-frame.routing"]]))
        "the routing filter does not match a routing edge")))
