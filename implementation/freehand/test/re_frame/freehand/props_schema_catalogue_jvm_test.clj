(ns re-frame.freehand.props-schema-catalogue-jvm-test
  "THE PUBLISHED SURFACE CARRIES A SCHEMA — `FH-PROPS-005`.

  Props schemas are optional in the grammar, and the whole point of that is
  that the mandate falls somewhere else: on the surfaces where a contract
  crosses a boundary. A published, reusable view is read by callers its
  author will never meet — through a catalogue entry, an editor completion,
  an agent authoring a call site — and for a shipped control the declaration
  IS the contract. A rule that is policy and nothing else is a rule that
  erodes, so this is a gate.

  The roster it governs is the views the public door itself publishes, and
  it is discovered from the LIVE surface rather than listed: a var that
  holds a view descriptor is a published view whatever it is called. The
  fixture then pins that roster, so adding a door view is a deliberate edit
  to the fixture and never a quiet inheritance of the exemption — a new
  published view with no fixture row fails as surely as one with a row and
  no schema.

  ## The control

  A gate is worth exactly what its discrimination is worth. `unschemad` is
  applied to the real catalogue AND to a roster carrying a deliberately
  schema-less view, and the second must NAME it. Without that, an empty
  result means \"nothing to report\" and \"the probe cannot see\" equally
  well — and this programme has already shipped two rows that could not tell
  those apart.

  JVM-only because `ns-publics` is a JVM reflection API; the door's
  published surface is one declaration set, so a schema declared there is
  declared for both hosts."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.props-schema :as props-schema]))

(def props-005 (conf/fixture :FH-PROPS-005))

(v/defview unschemad-control
  "NOT a published view — the control. A deliberately schema-less
  declaration, fed to the gate's own rule so its silence on the real
  catalogue is a discrimination rather than a blind spot."
  [{:keys [label]}]
  [:span label])

(defn- published-views
  "view-id -> descriptor, for every view the public door publishes.

  Discovered, not listed: any published var whose value is a view
  descriptor is a published view, whatever it happens to be named. A
  name-independent probe cannot be defeated by renaming the thing it looks
  for."
  []
  (into {}
        (keep (fn [[_ var']]
                (when-not (:macro (meta var'))
                  (let [value @var']
                    (when (descriptor/view? value)
                      [(:view-id (v/describe value)) value])))))
        (ns-publics 're-frame.freehand)))

(defn- unschemad
  "THE RULE. The view-ids in `catalogue` that declare no props schema,
  sorted — so a failure names them rather than reporting a count."
  [catalogue]
  (into (sorted-set)
        (keep (fn [[id view]]
                (when-not (contains? (v/describe view) :props-schema) id)))
        catalogue))

(deftest the-rule-names-a-schema-less-view
  (testing "The control, run BEFORE the gate is trusted. A roster carrying a
            deliberately schema-less declaration must come back naming it; an
            empty answer on the real catalogue means nothing otherwise."
    (is (= #{:re-frame.freehand.props-schema-catalogue-jvm-test/unschemad-control}
           (unschemad {:re-frame.freehand.props-schema-catalogue-jvm-test/unschemad-control
                       unschemad-control}))
        "a schema-less view is NAMED, not merely counted")
    (is (= #{:re-frame.freehand.props-schema-catalogue-jvm-test/unschemad-control}
           (unschemad (assoc (published-views)
                             :re-frame.freehand.props-schema-catalogue-jvm-test/unschemad-control
                             unschemad-control)))
        "and it is still named when it hides among views that DO carry one")))

(deftest the-published-catalogue-is-exactly-the-fixture-roster
  (testing "Per FH-PROPS-005: the roster is discovered from the live surface
            and pinned by the fixture, in that order. A view added to the door
            without a fixture row fails here — which is the point: the
            mandate must reach a surface the day it becomes one, not the day
            someone remembers it."
    (is (= (set (keys (:catalogue props-005)))
           (set (keys (published-views))))
        "every published view has a fixture row and every row a published view")
    (is (seq (published-views))
        "non-vacuous: the door really does publish views to govern")))

(deftest every-published-view-declares-a-props-schema
  (testing "Per FH-PROPS-005: the gate itself. A shipped reusable view carries
            a props schema, and the failure NAMES the view that does not —
            because the recovery is to write the contract that view was
            already making implicitly."
    (is (= #{} (unschemad (published-views)))
        "published views declaring no props schema (must be none)")))

(deftest declaring-a-schema-and-closing-it-are-two-decisions
  (testing "Per FH-PROPS-005: the mandate is the first decision only. The
            honest answer differs across the three — `v/route-link` forwards
            every unrecognised key to its `<a>`, so a closed roster there
            would be a contract it breaks on its first `:aria-label`. It
            declares itself OPEN, which is a statement, not an omission."
    (doseq [[id {:keys [closed? why]}] (:catalogue props-005)]
      (let [schema (:props-schema (v/describe (get (published-views) id)))]
        (is (some? schema) (str id " — declares a schema"))
        (is (= closed? (some? (props-schema/closing-keys schema)))
            (str id " — " (if closed? "closes" "is open") ": " why))))))
