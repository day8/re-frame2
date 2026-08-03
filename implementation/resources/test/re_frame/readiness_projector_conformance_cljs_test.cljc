(ns re-frame.readiness-projector-conformance-cljs-test
  "The drift guard between the TWO route-readiness projectors (rf2-6k8tj,
  part 3 of rf2-wzqtu). Spec 012 §Route readiness is a resource projection.

  Route readiness has ONE table and TWO implementations:

    - `re-frame.routing.readiness/project-at-commit` — the COMMIT-TIME half,
      seeding the stored slice from a freshly built plan, in ROUTING
      vocabulary (`{:plan-error … :blocking {<key-id> <scoped-key>}}`);
    - `re-frame.resources.route/reconcile-readiness` — the REPLY-DRIVEN half,
      re-projecting from live resource facts on every settle / adoption /
      fresh-skip / hydration / restore, in SPEC 016 vocabulary (requirements
      reading `:failed` / `:pending` / `:ready` / `:inert`).

  THE DUPLICATION IS DELIBERATE AND STAYS. `implementation/resources/deps.edn`
  holds routing as a TEST-ONLY dep and the routing integration is late-bound,
  so resources cannot `:require` routing; publishing a late-bind hook for a
  three-line `cond` would cost more than the duplication removes. rf2-wzqtu
  retracted the 'ONE projector' claim on both sides for exactly this reason.
  This namespace does NOT unify them and must not be read as a step toward
  unifying them — it pins that they AGREE, so a divergence fails a test rather
  than surfacing in a later slice.

  WHY IT LIVES HERE. The assertion has to require BOTH namespaces. Routing's
  own test tree cannot reach resources; `implementation/resources/test/` can
  reach routing (test-only dep), so this is the only tree where the two halves
  meet.

  HOW A ROW WORKS. Each row of `readiness-conformance-table` names an INPUT
  CLASS from the Spec 012 table and carries TWO encodings of that same class —
  one per projector, each in its own vocabulary — plus the single
  `:transition` / error-ness pair both must produce. The encodings are not the
  same data and cannot be: the halves answer the same question about different
  facts, which is precisely the drift this guards. What is shared is the
  ANSWER, and the precedence that produces it: error beats loading beats idle.

  Error VALUES are deliberately not compared — routing carries the planning
  error, resources builds a `:rf.error/resource-route-blocking` envelope. Only
  error-NESS is a shared claim.

  Named `*_cljs_test.cljc` so both the JVM runner and the shadow-cljs
  `:node-test` build discover it. Both projectors are pure, so there is no
  fixture: no frame, no adapter, no registrar."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [re-frame.resources.route :as res-route]
   [re-frame.resources.state :as state]
   [re-frame.routing.readiness :as readiness]))

;; ---- fixtures for the two vocabularies ------------------------------------

(def ^:private req-a
  (state/scoped-resource-key* :rf.scope/global :article/by-slug {:slug "a"}))

(def ^:private req-b
  (state/scoped-resource-key* :rf.scope/global :article/by-slug {:slug "b"}))

(defn- blocking-map
  "The byte-keyed blocking carrier `{<key-id> <scoped-key>}` both slots hold
  (rf2-btdl1)."
  [& ks]
  (into {} (map (juxt state/key-id identity)) ks))

(def ^:private plan-error
  {:rf.error/id :rf.error/resource-route-plan
   :reason      "params failed their schema"})

;; Durable cache entries, one per `requirement-state` outcome. The classifier
;; (`res-route/requirement-state`) is the authority for what each shape means;
;; `requirement-states-are-what-this-table-thinks-they-are` below re-derives
;; that rather than trusting these names.
(def ^:private entry-ready   {:resource/id :article/by-slug :status :loaded  :data {:x 1} :attempt 1})
(def ^:private entry-pending {:resource/id :article/by-slug :status :loading :data nil    :attempt 1})
(def ^:private entry-failed  {:resource/id :article/by-slug :status :error   :data nil    :attempt 1
                              :error       {:kind :rf.http/server :status 503}})
(def ^:private entry-inert   {:resource/id :article/by-slug :status :idle    :data nil    :attempt 1})

(defn- runtime-db-with
  "A runtime-db carrying a route slice at `nav-token` committed as
  `transition` / `error`, a blocking slot naming every key in
  `entries-by-key`, and those durable cache entries. `entries-by-key` nil
  writes NO blocking slot at all — the structural no-op case."
  [nav-token transition error entries-by-key]
  (cond-> {:rf.runtime/routing   {:current {:route-id   :route/article
                                            :nav-token  nav-token
                                            :transition transition
                                            :error      error}}
           :rf.runtime/resources {:entries (into {}
                                                 (map (fn [[k e]] [(state/key-id k) e]))
                                                 entries-by-key)}}
    entries-by-key
    (assoc-in [:rf.runtime/routing :resource-blocking nav-token]
              (apply blocking-map (keys entries-by-key)))))

;; ---- the shared table -----------------------------------------------------

(def ^:private readiness-conformance-table
  "One row per Spec 012 input class. `:plan` is the routing half's input (nil
  models the Resources artefact being absent); `:entries` is the resources
  half's (nil models no blocking slot for the live nav-token). `:committed` is
  what the slice already carries when the reply-driven half runs — it is what
  the commit-time half just wrote, which is how the two halves compose."
  [{:class      "planning failure — error beats a pending blocking requirement"
    :transition :error
    :error?     true
    ;; routing: a plan error alongside a non-empty blocking set. The plan
    ;; error must win, or a route whose plan could not be formed would report
    ;; itself as merely loading.
    :plan       {:plan-error plan-error :blocking (blocking-map req-a)}
    ;; resources: a committed planning failure writes :error on the slice and
    ;; NO blocking slot, so reconciliation is a structural no-op that must
    ;; preserve it.
    :committed  [:error plan-error]
    :entries    nil}

   {:class      "a blocking first load failed — error beats a pending sibling"
    :transition :error
    :error?     true
    ;; routing: at commit nothing has settled, so this class reaches the
    ;; commit-time half only as a plan that could not be formed. Its error leg
    ;; is `:plan-error`; that leg outranking `:blocking` is the same
    ;; precedence claim.
    :plan       {:plan-error plan-error :blocking (blocking-map req-a req-b)}
    :committed  [:loading nil]
    ;; resources: one `:failed` requirement beside one `:pending` one.
    :entries    {req-a entry-failed req-b entry-pending}}

   {:class      "a blocking first load pending, none failed — loading beats idle"
    :transition :loading
    :error?     false
    :plan       {:plan-error nil :blocking (blocking-map req-a)}
    :committed  [:idle nil]
    ;; a ready sibling is pruned; the pending one still holds the route.
    :entries    {req-a entry-pending req-b entry-ready}}

   {:class      "all blocking have usable data — idle"
    :transition :idle
    :error?     false
    ;; routing: the planner records only requirements without usable data at
    ;; commit, so 'all ready' arrives as an empty blocking set.
    :plan       {:plan-error nil :blocking {}}
    :committed  [:loading nil]
    :entries    {req-a entry-ready req-b entry-ready}}

   {:class      "an aborted blocking first load un-blocks — idle, no spurious error"
    :transition :idle
    :error?     false
    :plan       {:plan-error nil :blocking {}}
    :committed  [:loading nil]
    :entries    {req-a entry-inert}}

   {:class      "no blocking requirements at all — idle"
    :transition :idle
    :error?     false
    :plan       {:plan-error nil :blocking {}}
    :committed  [:idle nil]
    :entries    {}}

   {:class      "Resources artefact absent — idle"
    :transition :idle
    :error?     false
    ;; routing: the `:routing/on-route-entry` hook returns nil when no
    ;; Resources artefact is loaded.
    :plan       nil
    :committed  [:idle nil]
    ;; resources: nothing to reconcile — no blocking slot for the live token.
    :entries    nil}])

;; ---- the projections, in each vocabulary ----------------------------------

(defn- routing-projection
  "Run the commit-time half on a row and return `[transition error?]`."
  [{:keys [plan]}]
  (let [{:keys [transition error]} (readiness/project-at-commit plan)]
    [transition (some? error)]))

(defn- resources-projection
  "Run the reply-driven half on a row and return `[transition error?]`.
  `:emit-error? false` keeps this a projection test — the error TRACE is
  edge-triggered and pinned separately in `resources-route-cljs-test`."
  [{:keys [committed entries]}]
  (let [[transition error] committed
        rdb  (res-route/reconcile-readiness
               (runtime-db-with "nav-1" transition error entries)
               {:emit-error? false})
        cur  (get-in rdb [:rf.runtime/routing :current])]
    [(:transition cur) (some? (:error cur))]))

;; ---- the assertion --------------------------------------------------------

(deftest both-readiness-projectors-agree-on-the-spec-012-table
  ;; THE drift guard. Two implementations, one table: every input class must
  ;; fall out of BOTH halves as the same transition with the same error-ness.
  ;; A precedence change made on one side and not the other reds here.
  (doseq [{:keys [class transition error?] :as row} readiness-conformance-table]
    (testing class
      (is (= [transition error?] (routing-projection row))
          "re-frame.routing.readiness/project-at-commit (commit-time half)")
      (is (= [transition error?] (resources-projection row))
          "re-frame.resources.route/reconcile-readiness (reply-driven half)"))))

(deftest requirement-states-are-what-this-table-thinks-they-are
  ;; The rows above are written in terms of `:failed` / `:pending` / `:ready`
  ;; / `:inert`, but they carry raw cache entries. Re-derive the classification
  ;; from the classifier so a change to `requirement-state` cannot quietly turn
  ;; a row into a test of something else — the guard above would stay green
  ;; while pinning the wrong classes.
  (is (= :ready   (res-route/requirement-state entry-ready)))
  (is (= :pending (res-route/requirement-state entry-pending)))
  (is (= :failed  (res-route/requirement-state entry-failed)))
  (is (= :inert   (res-route/requirement-state entry-inert))))
