(ns re-frame.freehand.behavior-door-projections-cljs-test
  "FH-BEHAVIOR-008, THE DOOR HALF — the two tool projections are reachable
  through the ONE public door, and a tool needs nothing else.

  The projections themselves were proved by `behaviors-dom-cljs-test`,
  against a real mount: which behaviors are connected, what the command
  channel decided, and — the load-bearing half — what neither answers.
  What was NOT proved is the part a TOOL actually depends on. A projection
  published only from `re-frame.freehand.behaviors` obliges every reader to
  require a namespace API governance classifies as unsupported, which makes
  a supported tool plane a supported tool plane in name only.

  So this file is deliberately POOR in requires. It names
  `re-frame.freehand` and nothing else from the substrate, and asserts its
  own poverty structurally — the same door-only discipline
  `controller-surface-cljs-test` applies to the controller verbs. Delete
  the door exports and this namespace stops compiling, which is the
  strongest form the claim has.

  Three claims, one per host where it means something:

    - the door PUBLISHES both projections in ClojureScript, and they answer
      ordinary values (a tool calls them, it is not called back);
    - the door does NOT publish them on the JVM, alongside the mount verbs
      they share a host policy with — a structural render connects nothing,
      so an eternal empty projection there would be present-and-lying where
      absence is honest;
    - and this namespace really does reach only the door, so the first
      claim is about the SUPPORTED surface rather than about whatever
      happens to be on the classpath.

  The CLJS half of the same boundary is additionally reconciled by the
  api-manifest probe, which carries `re-frame.freehand` fully-rowed in both
  directions: a projection renamed away, or a summary accidentally exported
  beside them, reddens there.

  Per [Spec 004 §The tool plane](../../../../spec/004-Views.md#the-tool-plane)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            #?(:clj [clojure.java.io :as io])
            [re-frame.freehand :as v])
  #?(:clj (:import [java.io PushbackReader])))

;; ---------------------------------------------------------------------------
;; The rosters, spelled here
;; ---------------------------------------------------------------------------
;;
;; Spelled locally rather than read from the conformance fixture, because the
;; fixture loader is itself a `re-frame.freehand.*` internal and requiring it
;; would contradict the very claim this file makes. The DOM suite reads the
;; fixture; this one reads the door.

(def ^:private projections
  "The two names the tool plane publishes."
  ["active-connections" "command-log"])

(def ^:private control-verbs
  "Door verbs that ARE on the JVM surface. Without them, every absence
  below would be green for a door that had been deleted."
  ["defbehavior" "behavior" "describe" "render-static"])

(def ^:private mount-verbs
  "The browser-only verbs the projections share a host policy with. They
  are absent on the JVM for the same reason, so asserting them together is
  what makes the claim about a POLICY rather than about two omissions."
  ["mount" "hydrate-root" "unmount!" "->react"])

(def ^:private sanctioned-freehand-namespaces
  "The Freehand namespaces consumer code MAY require (Conventions
  §Freehand — one public namespace). `re-frame.freehand.test` is the
  sanctioned test sibling; this file needs neither it nor anything else."
  '#{re-frame.freehand re-frame.freehand.test})

;; ---------------------------------------------------------------------------
;; ClojureScript — the door publishes them, and they answer values
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest the-door-publishes-both-projections
     (testing "A tool reaches the behavior tool plane through the ONE public
               door. This namespace requires nothing else, so if the exports
               were withdrawn it would not compile — the assertions below are
               about the supported surface by construction."
       (is (fn? v/active-connections)
           "v/active-connections is a callable door verb")
       (is (fn? v/command-log)
           "v/command-log is a callable door verb"))

     (testing "Both answer ORDINARY VALUES, and answer them by being ASKED.
               A tool asks; it is not called back, and nothing on this plane
               dispatches — so calling either outside a mount is a legal,
               total read rather than a subscription that has to be torn
               down."
       (let [connections (v/active-connections)
             traffic     (v/command-log)]
         (is (vector? connections) "the connection projection is a vector")
         (is (vector? traffic)     "the traffic projection is a vector")
         (is (empty? connections)
             "nothing is mounted here, so nothing is connected — the read is
              honest about an empty plane rather than refusing to answer")
         (is (empty? traffic)
             "and nothing has been commanded")))

     (testing "and they are READS: asking twice changes nothing, so a tool
               that polls is not a tool that mutates"
       (is (= (v/active-connections) (v/active-connections)))
       (is (= (v/command-log) (v/command-log))))))

;; ---------------------------------------------------------------------------
;; JVM — the absence IS the host policy
;; ---------------------------------------------------------------------------
;;
;; `ns-publics` is a JVM reflection API, so the surface roster is asserted
;; here; the ClojureScript side of the same boundary is the api-manifest
;; probe's, which holds `re-frame.freehand` fully-rowed in both directions.

#?(:clj
   (defn- door-publics []
     (set (map name (keys (ns-publics 're-frame.freehand))))))

#?(:clj
   (deftest the-projections-are-absent-from-the-jvm-surface
     (testing "A structural render connects nothing, so there is no live
               plane to project. Freehand carries no tier of host operations
               that exist purely to answer emptily on the server, so the
               verbs are honestly ABSENT on the host that cannot support them
               rather than present and answering an eternal `[]`."
       (let [published (door-publics)]
         (is (seq published) "non-vacuous: the door publishes vars to examine")
         (doseq [absent projections]
           (is (not (contains? published absent))
               (str "re-frame.freehand does not publish " absent " on the JVM")))
         (testing "and they are not absent ALONE — the mount verbs they share
                   the reader conditional with are absent for the same
                   reason, which is what makes this a host policy rather than
                   two omissions"
           (doseq [absent mount-verbs]
             (is (not (contains? published absent))
                 (str "re-frame.freehand does not publish " absent " on the JVM"))))))))

#?(:clj
   (deftest the-jvm-surface-is-not-simply-empty
     (testing "The control that makes the absence above mean something:
               behavior verbs that DO cross to the JVM are on this surface —
               a behavior is an inert marker there, and a command is refused
               rather than pretended — so `the probe found nothing` can never
               be why the roster above is green."
       (let [published (door-publics)]
         (doseq [present control-verbs]
           (is (contains? published present)
               (str "re-frame.freehand publishes " present " on the JVM")))))))

;; ---------------------------------------------------------------------------
;; The structural claim — this namespace really is door-only
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- required-namespaces
     "Every namespace symbol the `ns` form at `resource-path` requires, read
     off the SOURCE rather than off `ns-aliases`: an unaliased require is a
     reach into internals exactly as an aliased one is and carries no alias
     to find. `:read-cond :allow` so the `.cljc` reader conditionals parse."
     [resource-path]
     (with-open [r (PushbackReader. (io/reader (io/resource resource-path)))]
       (let [form    (read {:read-cond :allow :eof nil} r)
             clauses (->> form
                          (filter (every-pred seq? #(= :require (first %))))
                          (mapcat rest))]
         (into #{}
               (keep (fn [clause]
                       (cond
                         (symbol? clause)     clause
                         (sequential? clause) (first clause))))
               clauses)))))

#?(:clj
   (defn- door-only?
     "True when no namespace in `required` is a Freehand internal."
     [required]
     (empty? (remove sanctioned-freehand-namespaces
                     (filter #(re-find #"^re-frame\.freehand(\.|$)" (str %))
                             required)))))

#?(:clj
   (deftest a-tool-reads-the-plane-through-the-door-alone
     (testing "The whole point of publishing the projections: a reader needs
               `re-frame.freehand` and nothing else. Before they crossed, the
               only way to read the live behavior plane was to require
               `re-frame.freehand.behaviors` — an implementation namespace
               API governance places off-limits to downstream tools."
       (let [required (required-namespaces
                        "re_frame/freehand/behavior_door_projections_cljs_test.cljc")]
         (testing "non-vacuous: the reader really found this file's ns form"
           (is (contains? required 're-frame.freehand)
               "this suite requires the door"))
         (testing "and the predicate can SEE an internal reach when there is
                   one — otherwise the verdict below would be green for a
                   check that never says no"
           (is (false? (door-only? (conj required 're-frame.freehand.behaviors))))
           (is (false? (door-only? '#{re-frame.freehand.cell}))))
         (is (door-only? required)
             "this suite reaches no Freehand internal, so what it asserts
              about the projections is asserted about the SUPPORTED surface")))))
