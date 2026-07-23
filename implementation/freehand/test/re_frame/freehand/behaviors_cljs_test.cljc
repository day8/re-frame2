(ns re-frame.freehand.behaviors-cljs-test
  "FH-BEHAVIOR-001 / -002 / -003 — the COMMON half of the behavior contract:
  the closed registration roster, the closed use-site grammar, and the
  structural inert marker.

  Everything here runs on the JVM and in ClojureScript from one source, so
  the closures are one answer rather than two that agree. The mounted half
  — commit-only connection, total cleanup, and the command channel against
  a live node — is `behaviors_dom_cljs_test`, because those are DOM facts
  and nothing else can prove them."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.behavior-views :as bv]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]))

;; ---------------------------------------------------------------------------
;; FH-BEHAVIOR-001 — the registration roster
;; ---------------------------------------------------------------------------

(def ^:private fh-001 (conf/fixture :FH-BEHAVIOR-001))

(defn- realise
  "Substitute a real function for every `:fn` marker in a fixture
  definition. Anything else passes through verbatim, which is what lets the
  non-function arm be exercised with the value the fixture actually names."
  [definition]
  (if-not (map? definition)
    definition
    (reduce-kv (fn [m k x]
                 (assoc m k (cond
                              (= :fn x) (fn [_ctx] nil)
                              (map? x)  (reduce-kv (fn [m2 k2 x2]
                                                     (assoc m2 k2 (if (= :fn x2)
                                                                    (fn [_ctx] nil)
                                                                    x2)))
                                                   {} x)
                              :else     x)))
               {} definition)))

(deftest fh-behavior-001-the-registration-roster-is-closed
  (testing "Per FH-BEHAVIOR-001: a behavior registration is closed in both
            directions. The timing set is the enumerated pair, the definition
            key roster is closed, a lifecycle entry that is not a function is
            refused, and a declaration that could never run is refused. Each
            case names the outcome the fixture pins."
    (is (= (set (:timings fh-001)) behaviors/timings)
        "the closed timing set is exactly the fixture's")
    (is (= (set (:definition-keys fh-001)) behaviors/definition-keys)
        "and the definition roster is exactly the fixture's")
    (doseq [{:keys [note definition outcome]} (:cases fh-001)]
      (let [id  (keyword "re-frame.freehand.behaviors-fixture"
                         (str "case-" (hash note)))
            got (conf/caught-id #(behaviors/register! id (realise definition)))]
        (if (= :registered outcome)
          (do (is (= conf/no-throw got) note)
              (is (= id (behaviors/register! id (realise definition)))
                  (str note " — registration answers the id"))
              (is (contains? (behaviors/registered-ids) id)
                  (str note " — and the behavior is reachable")))
          (do (is (= outcome got) note)
              (is (not (contains? (behaviors/registered-ids) id))
                  (str note " — a refused registration installs nothing"))))))))

(deftest fh-behavior-001-a-declaration-binds-the-registered-id
  (testing "Per FH-BEHAVIOR-001: `v/defbehavior` binds its var to the
            REGISTERED ID — a plain qualified keyword derived from the
            declaring namespace and the declared name — not to the
            implementation. That split is what keeps a use site data: the
            tree records an id, the registry holds the code, and nothing
            serializable ever carries a function."
    (is (= :re-frame.freehand.behavior-views/probe bv/probe))
    (is (qualified-keyword? bv/probe))
    (is (some? (behaviors/definition bv/probe))
        "and the id resolves to the registered definition")
    (is (= :passive (:timing (behaviors/definition bv/probe))))
    (is (= :layout (:timing (behaviors/definition bv/measure)))
        "the declared timing is what the registry holds")))

;; ---------------------------------------------------------------------------
;; FH-BEHAVIOR-002 — the use site
;; ---------------------------------------------------------------------------

(def ^:private fh-002 (conf/fixture :FH-BEHAVIOR-002))

(def ^:private declarations
  "The fixture's `:view` names resolved to the declarations they address."
  {:plain                     bv/plain
   :no-target                 bv/no-target
   :layout-timed              bv/layout-timed
   :opaque-host               bv/opaque-host
   :pair                      bv/pair
   :twins                     bv/twins
   :control-mount             bv/control-mount
   :unknown-option            bv/unknown-option
   :missing-use               bv/missing-use
   :unregistered-use          bv/unregistered-use
   :config-not-map            bv/config-not-map
   :config-carries-a-callback bv/config-carries-a-callback
   :two-children              bv/two-children
   :view-child                bv/view-child
   :fragment-child            bv/fragment-child
   :text-child                bv/text-child
   :opaque-with-children      bv/opaque-with-children})

(deftest fh-behavior-002-the-use-site-is-data-and-owns-one-node
  (testing "Per FH-BEHAVIOR-002: the `[v/behavior {…} node]` option roster is
            closed, `:use` must name a registered behavior, `:config` is data
            at every depth, and the boundary decorates exactly one element.
            Every case is rendered on this host, so acceptance and refusal are
            one answer rather than two."
    (is (= (set (:option-keys fh-002)) behaviors/option-keys)
        "the closed option roster is exactly the fixture's")
    (doseq [{:keys [note view outcome]} (:cases fh-002)]
      (let [declaration (get declarations view)
            _           (is (some? declaration) (str note " — the fixture names a real view"))
            got         (conf/caught-id #(t/render [declaration {}]))]
        (if (= :renders outcome)
          (is (= conf/no-throw got) note)
          (is (= outcome got) note))))))

(deftest fh-behavior-002-a-refusal-names-the-behavior-surface
  (testing "Per FH-BEHAVIOR-002: a refusal is didactic, not a bare id. The
            message names what a behavior's config may hold and what its
            child must be, so the recovery is visible without a debugger."
    (let [config-msg (conf/caught-message #(t/render [bv/config-carries-a-callback {}]))
          child-msg  (conf/caught-message #(t/render [bv/fragment-child {}]))
          use-msg    (conf/caught-message #(t/render [bv/unregistered-use {}]))]
      (is (re-find #"DATA" (str config-msg))
          "the config refusal states the rule it enforces")
      (is (re-find #"\[:limits :on-done\]" (str config-msg))
          "and names the exact site inside the config, not just the config")
      (is (re-find #"ONE element" (str child-msg))
          "the child refusal states the one-node rule")
      (is (re-find #"nowhere/absent" (str use-msg))
          "and an unregistered id names itself"))))

;; ---------------------------------------------------------------------------
;; FH-BEHAVIOR-003 — the structural inert marker
;; ---------------------------------------------------------------------------

(def ^:private fh-003 (conf/fixture :FH-BEHAVIOR-003))

(deftest fh-behavior-003-the-structural-projection-is-an-inert-marker
  (testing "Per FH-BEHAVIOR-003: a structural render records the whole PUBLIC
            fact — the boundary, the registered id, the semantic target and
            the config — with the author's own element unchanged as its
            child, and connects nothing. The tree carries no node, no memory,
            no cleanup handle and no callback, which is exactly why it prints
            and reads back."
    (bv/reset-transcript!)
    (doseq [{:keys [note view args tree]} (:cases fh-003)]
      (is (= tree (t/render (into [(get declarations view)] args))) note))
    (is (= (:lifecycle fh-003) (bv/ops))
        "and NOTHING ran: inert is a claim about what did not happen")))

(deftest fh-behavior-003-the-structural-tree-round-trips
  (testing "Per FH-BEHAVIOR-003: the marker is plain data, so the tree a
            behavior appears in still prints and reads back losslessly. A
            configuration carrying a callback would break exactly this, which
            is why it is refused at the door rather than recorded."
    (let [tree (t/render [bv/plain {}])]
      (is (= tree (edn/read-string (pr-str tree)))))))

(deftest fh-behavior-003-a-command-with-no-live-connection-is-refused
  (testing "Per FH-BEHAVIOR-003: a command crosses into a live host object,
            and no structural render owns one — so the channel REFUSES rather
            than silently doing nothing or retaining the request. On the JVM
            that is categorical (the channel is a browser capability); in a
            node runtime with nothing mounted it is the absent-target arm.
            One diagnostic id either way, which is what a caller branches on."
    (is (= (:outcome (:command fh-003))
           (conf/caught-id #(behaviors/command! (:args (:command fh-003)))))
        "a command that can reach no live connection is refused")
    (is (= (:outcome (:command fh-003))
           (conf/caught-id
             #(behaviors/command! (assoc (:args (:command fh-003)) :op :unknown))))
        "and so is one naming an operation nothing registered")))

;; ---------------------------------------------------------------------------
;; The absence — no neutral hook, ref or effect crossed with the behavior
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest the-door-publishes-no-neutral-imperative-form
     (testing "Per Spec 004 §Absent at the declaration and call surface: the
               behavior slice retires the donor's neutral imperative forms, and
               retiring them means the door does not publish them. The
               api-manifest drift-check pins the whole roster; this is the
               explicit assertion that the specific names did not come back
               alongside the boundary that replaces them. JVM-only, because
               `ns-publics` is a JVM read — and the roster it reads is the same
               one the manifest generator introspects."
       (let [published (set (map name (keys (ns-publics 're-frame.freehand))))]
         (is (contains? published "defbehavior"))
         (is (contains? published "behavior"))
         (doseq [absent ["local" "ref" "use-ref" "effect" "layout-effect" "dispatch-fn"
                         "portal" "on-mount" "on-unmount" "lifecycle" "hook"]]
           (is (not (contains? published absent))
               (str "re-frame.freehand publishes no " absent)))))))

(deftest a-behavior-boundary-is-mounted-never-called
  (testing "Per Spec 004 §A declared view cannot be called: `v/behavior` is a
            declared boundary like any other, so calling it raises rather than
            answering nil."
    (is (v/view? v/behavior))
    (is (= :rf.error/view-called-directly (conf/caught-id #(v/behavior {}))))))
