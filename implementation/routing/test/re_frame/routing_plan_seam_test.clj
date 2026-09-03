(ns re-frame.routing-plan-seam-test
  "Focused tests for the ONE resolved-target / route-plan seam
  `re-frame.routing.resolve` (EP-0037 R0b).

  Pins the ResolvedTarget fact shape, the route-plan every door builds
  (`:source` / `:cause` / `:target` / `:branch` / `:leaf-plan`), the
  parent-to-leaf branch derivation (shared with the `:rf.route/chain` sub),
  the behaviour-preserving leaf resource plan (the route's `:on-match`
  loaders), and the R0 diagnostic projection. Per Spec 012 §The one planning
  pipeline and §Resolved target and the plan diagnostic projection.

  The pure-constructor tests below prove the seam's SHAPE. The door-wiring
  tests at the foot prove the doors actually reach it — that the link door and
  the commit hop resolve one URL to ONE target, and that the URL-change door
  reports which of its three sub-doors fired. A test that only loops
  `rf.routing.resolve/causes` against the pure constructor cannot fail when a door passes
  the wrong cause or resolves its own target.

  ## Posture split (rf2-o5dbf)

  The SEAM ITSELF is production-real and carries no posture guard. Every pure
  constructor test — `resolved-target`, the nil-query strip, the fragment
  collapse, `:query-defaults`, `route-plan`, the fail-loud `:branch` walk,
  `leaf-plan-of`, `plan-trace-tags` and `url-resolution` —
  runs in the ordinary `clojure -M:test` suite AND in
  `scripts/test-routing-prod-gate.sh` (the `-Dre-frame.debug=false` lane). So
  do the door-wiring tests at the foot: the link/commit agreement, the exact
  no-op, the shared not-found `:reason` vocabulary and `url-change-cause`.

  What IS dev-only is the `:rf.route/planned` TRACE — the one bus the R0
  projection rides — and the two `:rf.warning/*` fail-closed advisories. All
  three go through `trace/emit!` / `trace/emit-error!`, gated on
  `rf.interop/debug-enabled?` and read once at load time. Their assertions are
  kept VERBATIM inside `(when rf.interop/debug-enabled? …)` arms marked
  `rf2-o5dbf`.

  EIGHT assertions in this namespace would have passed VACUOUSLY the moment
  the roster line came off, because under the gate the trace ring is empty and
  `(:tags (first ts))` is nil:

    * `(is (empty? (planned …)))` twice — the two NON-commit branches. A
      negative over an empty ring is green whether or not the door plans.
    * `(is (= [] (:warnings url-driven) (:warnings programmatic)))` — the
      well-formed miss's advisory-quiet leg.
    * three legs of `planned-traces-branch-agrees-with-the-activation`:
      `not-any? #{:route/nowhere}` over a nil `:branch`, `(not (contains?
      tags :branch-error))` over a nil tag map, and the `:branch-error`
      metadata scan over `(pr-str nil)`.
    * and the sharpest pair, in
      `an-executed-navigations-plan-trace-is-not-a-carrier`:
      `(is (not (re-find #\"SECRET100\" (pr-str tags))))` and its `tok-99`
      sibling would have certified that a real navigation's secret query
      value and fragment stayed out of an egress copy THAT WAS NEVER MADE.

  PRODUCTION WITNESSES were added rather than assertions dropped. The
  projection is a PURE function (`rf.routing.resolve/plan-trace-tags`, pinned
  posture-independently above), so the redaction the trace relies on is
  checkable with no gate between the call and the verdict — read it as \"what
  the bus WOULD carry if the emit were running\". The commits themselves are
  runtime-db facts: the slice moves, the `:on-match` leaf plan really
  dispatches, the nav-token really does NOT move on a no-op, and the
  `:routing/on-route-entry` late-bind hook — a FN, not a trace — really does
  receive the fail-loud `:branch-error` the activation composes over. Nothing
  was deleted or weakened."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing.subs :as rf.routing.subs]
            [re-frame.routing.url-change :as rf.routing.url-change]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- ResolvedTarget: facts, not intent ------------------------------------

(deftest resolved-target-reflects-facts-verbatim
  (testing "the ResolvedTarget carries the resolved FACTS (facts say :route-id, intent says :to)"
    (is (= {:route-id :route/article
            :params   {:slug "routing-as-data"}
            :query    {:tab "comments"}
            :fragment "reply-42"
            :url      "/articles/routing-as-data?tab=comments#reply-42"}
           (rf.routing.resolve/resolved-target
             {:route-id :route/article
              :params   {:slug "routing-as-data"}
              :query    {:tab "comments"}
              :fragment "reply-42"
              :url      "/articles/routing-as-data?tab=comments#reply-42"}))))
  (testing ":route-id / :params / :url are reflected verbatim — for a route
            declaring no :query-defaults an empty :query stays {} exactly as the
            door resolved it, and a non-empty fragment passes through"
    (is (= {} (:query (rf.routing.resolve/resolved-target {:route-id :route/home :params {} :query {}}))))))

;; ---- the normalisations only ONE door used to apply (rf2-kqxe6.7) ----------
;;
;; `:query` and `:fragment` are the two fields the seam RESOLVES rather than
;; reflects, and all three of their rules serve one law: a resolved target must
;; describe a place its own canonical URL can spell. `route-url` elides a
;; nil-valued query key and emits no trailing `#` for an empty fragment, so a
;; target that keeps either one commits a slice the address bar contradicts.
;;
;; Both rules were already written down — the nil strip inline in the
;; programmatic handler (rf2-gxq7z1), the fragment collapse in
;; `plan/normalize-fragment`, whose docstring says it exists "to keep the
;; programmatic and URL-driven paths in agreement" — and both were applied at
;; that ONE door. `[:rf.route/prefetch …]` reaches this seam directly and got
;; neither.

(deftest resolved-target-drops-nil-valued-query-keys
  (rf.routing/reg-route :route/page
    {:query-defaults {:tab :overview}} "/p/:slug")
  (rf.routing/reg-route :route/plain {} "/plain/:slug")
  (testing "a nil value is the caller spelling ABSENCE — the target carries the
            absence, because route-url elides the key from the URL"
    (is (= {} (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                 :params   {:slug "x"}
                                                 :query    {:drop nil}}))))
    (is (= {:keep "y"}
           (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                              :params   {:slug "x"}
                                              :query    {:keep "y" :drop nil}})))))
  (testing "the strip runs BEFORE the defaults fill, so a declared default still
            lands on a key the caller nilled out"
    (is (= {:tab :overview}
           (:query (rf.routing.resolve/resolved-target {:route-id :route/page
                                              :params   {:slug "x"}
                                              :query    {:tab nil}})))))
  (testing "a query holding no nil is returned IDENTICALLY — the URL doors'
            query arrives in canonical key order and rebuilding it would throw
            that order away"
    (let [q (array-map :b 2 :a 1)]
      (is (identical? q (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                           :params   {:slug "x"}
                                                           :query    q}))))))
  (testing "and when a nil IS present the survivors keep their order"
    (is (= [:b :a]
           (keys (:query (rf.routing.resolve/resolved-target
                           {:route-id :route/plain
                            :params   {:slug "x"}
                            :query    (array-map :b 2 :drop nil :a 1)}))))))
  (testing "nil / empty queries are untouched"
    (is (nil? (:query (rf.routing.resolve/resolved-target {:route-id :route/plain :params {}}))))
    (is (= {} (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                 :params   {} :query {}}))))))

(deftest resolved-target-collapses-an-empty-fragment-to-nil
  (rf.routing/reg-route :route/page {} "/p/:slug")
  (testing "\"\" is truthy, so an un-normalised empty fragment made the slice say
            :fragment \"\" while route-url emitted /p/x with no trailing #"
    (is (nil? (:fragment (rf.routing.resolve/resolved-target {:route-id :route/page
                                                    :params   {:slug "x"}
                                                    :fragment ""})))))
  (testing "a real fragment and an absent one are untouched"
    (is (= "reply-42" (:fragment (rf.routing.resolve/resolved-target {:route-id :route/page
                                                            :params   {:slug "x"}
                                                            :fragment "reply-42"}))))
    (is (nil? (:fragment (rf.routing.resolve/resolved-target {:route-id :route/page
                                                    :params   {:slug "x"}})))))
  (testing "the collapse is IDEMPOTENT, so the programmatic door's own earlier
            normalisation lowers through unchanged"
    (let [once  (rf.routing.resolve/resolved-target {:route-id :route/page :params {:slug "x"}
                                           :fragment ""})
          twice (rf.routing.resolve/resolved-target once)]
      (is (= (:fragment once) (:fragment twice))))))

(deftest a-bare-trailing-hash-url-resolves-to-no-fragment
  ;; rf2-kqxe6.7 — the deliberate URL-door consequence of moving the collapse to
  ;; the seam, pinned so it is a decision on the record rather than a side
  ;; effect. `match-url` still reports `:fragment ""` for a bare trailing `#`
  ;; (its own contract, unchanged and separately pinned in the registry suite);
  ;; the RESOLVED TARGET built from it now says nil.
  ;;
  ;; That is the agreement `normalize-fragment` was written for and only the
  ;; programmatic door had: `route-url` emits `/page` for this target, so while
  ;; the URL door kept `""` the slice claimed a fragment its own canonical URL
  ;; does not spell — and `/page` -> `/page#` counted as an in-page anchor
  ;; change, emitting `:rf.route/fragment-changed` for a move between two URLs
  ;; that denote the same place. It is now the exact no-op rule 3 already
  ;; describes.
  (rf.routing/reg-route :route/page {} "/page")
  (is (= "" (:fragment (rf.routing/match-url "/page#")))
      "match-url's own contract is untouched")
  (is (nil? (:fragment (rf.routing.resolve/target-of-url "/page#")))
      "but the resolved target carries no fragment")
  (is (= (dissoc (rf.routing.resolve/target-of-url "/page")  :url)
         (dissoc (rf.routing.resolve/target-of-url "/page#") :url))
      "so both spellings resolve to one target, differing only in the requested
       :url each preserves verbatim")
  (is (= "/page" (rf.routing/route-url {:to :route/page}))
      "which is the URL that target derives — slice and address bar agree"))

;; ---- the ONE place `:query-defaults` are filled (rf2-kqxe6.23) -------------
;;
;; `:query` is the one ResolvedTarget field the seam RESOLVES rather than
;; reflects: Spec 012 defines a ResolvedTarget as planner output "after
;; matching, defaults, and validation", and this is the single function every
;; door shapes its target through. Before this, only `match-url` filled
;; defaults, so the named-address doors resolved a different target than the
;; URL doors for one destination.

(deftest resolved-target-fills-the-routes-declared-query-defaults
  (rf.routing/reg-route :route/page
    {:params         [:map [:slug :string]]
     :query          [:map [:tab {:optional true} [:enum :overview :comments]]]
     :query-defaults {:tab :overview}}
    "/p/:slug")
  (rf.routing/reg-route :route/plain {:params [:map [:slug :string]]} "/plain/:slug")
  (testing "an absent declared-default key is filled — the named-address door
            resolves the SAME :query match-url gives the URL doors"
    (is (= {:tab :overview}
           (:query (rf.routing.resolve/resolved-target {:route-id :route/page
                                              :params   {:slug "x"}
                                              :query    {}}))))
    (is (= (:query (rf.routing/match-url "/p/x"))
           (:query (rf.routing.resolve/resolved-target {:route-id :route/page
                                              :params   {:slug "x"}
                                              :query    {}})))
        "the two halves of the prism agree on what an absent key means"))
  (testing "an explicit value WINS over the declared default — the fill is
            membership-only, never a value transform"
    (is (= {:tab :comments}
           (:query (rf.routing.resolve/resolved-target {:route-id :route/page
                                              :params   {:slug "x"}
                                              :query    {:tab :comments}})))))
  (testing "the fill is IDEMPOTENT, so a URL door whose query match-url already
            filled lowers through the seam unchanged"
    (let [once  (rf.routing.resolve/resolved-target {:route-id :route/page :params {:slug "x"} :query {}})
          twice (rf.routing.resolve/resolved-target once)]
      (is (= (:query once) (:query twice)))))
  (testing "a route declaring no defaults is untouched — the common case is a
            passthrough"
    (is (= {} (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                 :params   {:slug "x"}
                                                 :query    {}}))))
    (is (nil? (:query (rf.routing.resolve/resolved-target {:route-id :route/plain
                                                 :params   {:slug "x"}})))
        "an absent :query stays absent rather than being conjured into {}"))
  (testing "an UNREGISTERED target resolves without throwing — the seam reads the
            route table, so a not-found target simply has no defaults to fill"
    (is (= {} (:query (rf.routing.resolve/resolved-target {:route-id :rf.route/not-found
                                                 :params   {:url "/nope"}
                                                 :query    {}}))))))

;; ---- branch + leaf plan ---------------------------------------------------

(defn- plan-for
  "The route plan for a bare `route-id` — the branch / branch-error fields are
  derived from it alone."
  [route-id]
  (rf.routing.resolve/route-plan {:cause  :navigate
                        :source {:to route-id}
                        :target (rf.routing.resolve/resolved-target {:route-id route-id})}))

(deftest plan-branch-is-the-parent-to-leaf-chain
  (rf.routing/reg-route :route/dashboard {} "/dashboard")
  (rf.routing/reg-route :route/reports {:parent :route/dashboard} "/dashboard/reports")
  (rf.routing/reg-route :route/report {:parent :route/reports} "/dashboard/reports/:id")
  (testing "the plan branch is [parent-most … leaf]"
    (is (= [:route/dashboard :route/reports :route/report]
           (:branch (plan-for :route/report))))
    (is (nil? (:branch-error (plan-for :route/report)))))
  (testing "on a chain that RESOLVES it agrees with the :rf.route/chain display
            sub — the display walk is still correct for what it does"
    (is (= (rf.routing.subs/chain-from-meta :route/report)
           (:branch (plan-for :route/report)))))
  (testing "the plan also carries the walk's CONTRIBUTORS (route-id + route-meta
            per segment) — the value commit-navigation hands the resource plan,
            resolved once per navigation rather than re-walked at the commit"
    (is (= [:route/dashboard :route/reports :route/report]
           (mapv :route-id (:branch-contributors (plan-for :route/report)))))
    (is (= (rf.registrar/lookup :route :route/reports)
           (:route-meta (second (:branch-contributors (plan-for :route/report))))))))

;; ---- the plan branch is the FAIL-LOUD walk (rf2-cqyq2) ---------------------
;;
;; Two `:parent` walks exist deliberately. `subs/chain-from-meta` is the DISPLAY
;; walk: defensive, swallows cycles, and INCLUDES an unregistered parent id in
;; the chain it returns. `events/resolve-branch` is the PLANNING walk: fail-loud,
;; reporting `{:branch-error {:kind :unknown-parent | :parent-cycle …}}`. And
;; `events.cljc` says so in as many words — the display sub "swallows cycles
;; defensively and is not a substitute here".
;;
;; The plan's `:branch` used the display walk while `commit-navigation`
;; independently re-walked with the fail-loud one, so the plan REPORTED one
;; branch and EXECUTED another — and they disagreed exactly on the
;; malformed-registration cases where a diagnostic earns its keep.

(deftest plan-branch-never-names-an-unregistered-route
  (rf.routing/reg-route :route/leaf {:parent :route/nowhere} "/leaf")
  (testing "the premise: the two walks answer differently for an unregistered
            :parent, and the display walk is the one that invents a route"
    (is (= [:route/nowhere :route/leaf] (rf.routing.subs/chain-from-meta :route/leaf))
        "the display walk includes an id no route table entry backs")
    (is (= {:kind :unknown-parent :route-id* :route/nowhere}
           (:branch-error (rf.routing.events/resolve-branch :route/leaf)))
        "the planning walk refuses to resolve it"))
  (testing "the PLAN now reports the fail-loud answer — an empty branch plus the
            error, rather than a plausible two-segment branch naming
            :route/nowhere while the activation aborts"
    (let [plan (plan-for :route/leaf)]
      (is (= [] (:branch plan)))
      (is (not-any? #{:route/nowhere} (:branch plan)))
      (is (= {:kind :unknown-parent :route-id* :route/nowhere} (:branch-error plan)))
      (is (empty? (:branch-contributors plan))
          "no contributors resolved, so the resource plan aborts rather than
           composing over a truncated branch"))))

(deftest plan-branch-reports-a-parent-cycle-rather-than-truncating-at-it
  (rf.routing/reg-route :route/ping {:parent :route/pong} "/ping")
  (rf.routing/reg-route :route/pong {:parent :route/ping} "/pong")
  (testing "the display walk terminates silently at the cycle entry — a chain
            that looks perfectly ordinary"
    (is (= [:route/pong :route/ping] (rf.routing.subs/chain-from-meta :route/ping))))
  (testing "the plan reports the cycle"
    (let [plan (plan-for :route/ping)]
      (is (= [] (:branch plan)))
      (is (= :parent-cycle (:kind (:branch-error plan))))
      (is (= :route/ping (:route-id* (:branch-error plan)))))))

(deftest leaf-plan-of-is-the-behaviour-preserving-on-match-loader
  (rf.routing/reg-route :route/article
    {:on-match [[:article/load] [:comments/load]]} "/articles/:slug")
  (rf.routing/reg-route :route/home {} "/")
  (testing "the leaf plan is the route's :on-match loader vector (the loaders that already fire)"
    (is (= [[:article/load] [:comments/load]] (rf.routing.resolve/leaf-plan-of :route/article))))
  (testing "a route with no :on-match has an empty leaf plan"
    (is (= [] (rf.routing.resolve/leaf-plan-of :route/home))))
  (testing "an unregistered / not-found target has an empty leaf plan"
    (is (= [] (rf.routing.resolve/leaf-plan-of :rf.route/not-found)))))

;; ---- the route plan every door builds -------------------------------------

(deftest route-plan-carries-source-cause-target-branch-leaf-plan
  (rf.routing/reg-route :route/section {} "/section")
  (rf.routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load]]} "/section/:slug")
  (let [target (rf.routing.resolve/resolved-target
                 {:route-id :route/article :params {:slug "x"} :query {}
                  :fragment nil :url "/section/x"})
        plan   (rf.routing.resolve/route-plan {:source {:to :route/article :params {:slug "x"}}
                                     :cause  :navigate
                                     :target target})]
    (testing "the plan carries the caller's source and cause"
      (is (= {:to :route/article :params {:slug "x"}} (:source plan)))
      (is (= :navigate (:cause plan))))
    (testing "the plan's :target IS the ResolvedTarget the door commits (load-bearing, not a copy)"
      (is (= target (:target plan))))
    (testing "the plan derives the parent-to-leaf branch and the leaf resource plan from the target"
      (is (= [:route/section :route/article] (:branch plan)))
      (is (= [[:article/load]] (:leaf-plan plan))))))

(deftest route-plan-is-cause-parametric-across-the-doors
  (rf.routing/reg-route :route/home {} "/")
  (let [target (rf.routing.resolve/resolved-target {:route-id :route/home :params {} :query {} :url "/"})]
    (testing "every door builds the plan through the same fn, differing ONLY in cause"
      (doseq [cause rf.routing.resolve/causes]
        (let [plan (rf.routing.resolve/route-plan {:source {:url "/"} :cause cause :target target})]
          (is (= cause (:cause plan)))
          (is (= target (:target plan)))
          (is (= [:route/home] (:branch plan))))))))

;; ---- the projection as trace tags (mayor ruling on rf2-kqxe6.3) ------------
;;
;; The projection was unreachable from an executed navigation: only a tool
;; holding a plan VALUE could read it. The ruled fix is one trace per door
;; commit branch — carrying the URL through the EXISTING `redact-url-tag` path
;; and the params / query KEY SETS rather than their values, because a trace tag
;; is an egress surface the route's `:sensitive` classification (lowered against
;; runtime-db slice PATHS) cannot reach.

(deftest plan-trace-tags-carries-the-projection-without-the-carriers
  (rf.routing/reg-route :route/section {} "/section")
  (rf.routing/reg-route :route/article
    {:parent :route/section :on-match [[:article/load] [:comments/load 7]]}
    "/section/:slug")
  (let [plan (rf.routing.resolve/route-plan
               {:cause  :navigate
                :source {:to :route/article :params {:slug "x"} :query {:invite "SECRET100"}}
                :target (rf.routing.resolve/resolved-target
                          {:route-id :route/article
                           :params   {:slug "x"}
                           :query    {:invite "SECRET100" :tab :comments}
                           :fragment "reply-42"
                           :url      "/section/x?invite=SECRET100&tab=comments#reply-42"})})
        tags (rf.routing.resolve/plan-trace-tags plan)]
    (testing "the non-carrier halves of the projection ride WHOLE"
      (is (= :navigate (:cause tags)))
      (is (= :route/article (:route-id tags)))
      (is (= [:route/section :route/article] (:branch tags)))
      (is (= [:article/load :comments/load] (:leaf-plan-ids tags))
          "the leaf plan's event IDs — not their argument positions"))
    (testing "the URL rides the EXISTING redact-url-tag path — path kept, query
              VALUES and the whole #fragment redacted. No second redaction route
              for the same datum."
      (is (= "/section/x?invite=rf/redacted&tab=rf/redacted#rf/redacted"
             (:url tags))))
    (testing "params / query contribute KEY SETS, not values — that :invite was
              bound is diagnostic; that invite=SECRET100 is the leak"
      (is (= [:slug] (:param-keys tags)))
      (is (= [:invite :tab] (:query-keys tags)))
      (is (not (contains? tags :params)))
      (is (not (contains? tags :query))))
    (testing "and NOTHING in the tag map reproduces a carrier value — not the
              query value, not the fragment, not the caller's :source address"
      (is (not (re-find #"SECRET100" (pr-str tags))))
      (is (not (re-find #"reply-42" (pr-str tags))))
      (is (not (contains? tags :source))))
    (testing "a param-less / query-less target yields empty key sets, never nil"
      (let [bare (rf.routing.resolve/plan-trace-tags
                   (rf.routing.resolve/route-plan
                     {:cause :initial :source {:url "/section"}
                      :target (rf.routing.resolve/resolved-target
                                {:route-id :route/section :params {} :query {}
                                 :url "/section"})}))]
        (is (= [] (:param-keys bare)))
        (is (= [] (:query-keys bare)))
        (is (= [] (:leaf-plan-ids bare)))
        (is (= "/section" (:url bare)) "a bare path is not a carrier — verbatim")))))

;; ---- the runtime-db facts a door commit leaves behind ---------------------
;;
;; rf2-o5dbf: these are what the `:rf.route/planned` trace ANNOUNCES, and
;; unlike the trace they survive `-Dre-frame.debug=false`. The door-wiring
;; section at the foot reads the same slice through the same helpers.

(defn- rdb [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- nav-slice [] (get-in (rdb) [:rf.runtime/routing :current]))
(defn- current-id [] (:route-id (nav-slice)))

(defn- with-route-entry-spy!
  "Install a spy on the `:routing/on-route-entry` late-bind hook — the FN
  `commit-navigation` hands the resolved `:branch` (contributors) and
  `:branch-error` to — run `f`, restore the prior binding, and return the
  recorded contexts.

  rf2-o5dbf: this is the production-visible counterpart of the
  `:rf.route/planned` trace's `:branch` / `:branch-error` tags. A late-bound fn
  is not a trace, so the activation's copy of the fail-loud walk arrives under
  `-Dre-frame.debug=false` exactly as it does in dev."
  [f]
  (let [seen  (atom [])
        prior (rf.late-bind/get-fn :routing/on-route-entry)]
    (rf.late-bind/set-fn! :routing/on-route-entry (fn [ctx] (swap! seen conj ctx) {}))
    (try (f)
         (finally (rf.late-bind/set-fn! :routing/on-route-entry prior)))
    @seen))

(defn- quiet-nav-fx!
  "No-op the host navigation fx so a JVM navigation commits without reaching a
  browser-only handler."
  []
  (doseq [fx-id [:rf.nav/push-url :rf.nav/replace-url
                 :rf.nav/capture-scroll :rf.nav/scroll
                 :rf.server/set-status]]
    (rf.fx/reg-fx fx-id {:platforms #{:server :client}} (fn [_ _] nil))))

(defmacro ^:private planned
  "The `:rf.route/planned` traces emitted while `body` runs."
  [& body]
  `(with-trace-recorder! [traces# {:pred #(= :rf.route/planned (:operation %))}]
     ~@body
     @traces#))

(deftest every-door-commit-branch-emits-one-plan-trace
  (rf.routing/reg-route :route/home {} "/")
  (rf.routing/reg-route :route/article {:on-match [[:article/load]]} "/articles/:slug")
  (quiet-nav-fx!)
  ;; rf2-o5dbf — the LEAF PLAN made production-visible. `:leaf-plan-ids` on the
  ;; trace names the loaders the plan carries; registering that loader turns
  ;; "which ids ride the tag" into "which loaders actually dispatched" — the
  ;; same claim, with no bus between the door and the verdict.
  (let [loaded (atom [])]
    (rf/reg-event :article/load
                  (fn [{:keys [db]} _]
                    (swap! loaded conj (:slug (:params (nav-slice))))
                    {:db db}))

    (testing "the PROGRAMMATIC door — the projection is now reachable from an
              executed navigation, which is the whole completeness obligation"
      (let [ts (planned (rf/dispatch-sync [:rf.route/navigate {:to :route/article
                                                               :params {:slug "a"}}]))]
        ;; SEMANTIC, posture-independent (rf2-o5dbf): the door COMMITTED, and
        ;; the leaf plan the projection names really ran. Without these the
        ;; whole deftest is a statement about a bus production does not run.
        (is (= :route/article (current-id)) "the programmatic door committed its target")
        (is (= {:slug "a"} (:params (nav-slice))))
        (is (= ["a"] @loaded) "the :leaf-plan the projection names really dispatched")
        ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (= 1 (count ts)) "exactly one plan trace per commit")
          (let [{:keys [cause route-id branch leaf-plan-ids frame]} (:tags (first ts))]
            (is (= :navigate cause))
            (is (= :route/article route-id))
            (is (= [:route/article] branch))
            (is (= [:article/load] leaf-plan-ids))
            (is (= :rf/default frame)
                "frame-stamped — epoch capture admits only frame-tagged traces")))))
    (testing "the URL-driven door reports which of its four sub-doors fired"
      ;; A VECTOR of triples, not a map — the slug pairs positionally with the
      ;; cause, and the iteration order is then the written order.
      (doseq [[cause dispatch slug] [[:link     [:rf.route/transitioned "/articles/b"] "b"]
                                     [:popstate [:rf.route/handle-url-change "/articles/c"
                                                 {:rf.route/cause :popstate}] "c"]
                                     [:initial  [:rf.route/handle-url-change "/articles/d"] "d"]]]
        (let [ts (planned (rf/dispatch-sync dispatch))]
          ;; SEMANTIC, posture-independent (rf2-o5dbf): every sub-door really
          ;; commits and really re-runs the leaf plan. Which CAUSE each reports
          ;; has its own always-on witness in
          ;; `executed-url-change-navigation-carries-its-true-cause` below,
          ;; where the cause rides an application `:rf.route/entry-denied`
          ;; payload rather than the trace bus.
          (is (= {:slug slug} (:params (nav-slice)))
              (str cause " committed /articles/" slug))
          (is (= slug (last @loaded))
              (str cause " re-ran the leaf plan for /articles/" slug))
          ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
          (when rf.interop/debug-enabled?
            (is (= 1 (count ts)) (str cause " emitted exactly one plan trace"))
            (is (= cause (:cause (:tags (first ts))))
                (str "a door that hardcoded one cause for four sub-doors fails here"))
            (is (= :rf/default (:frame (:tags (first ts)))))))))
    (testing "the SSR feed reports :ssr off the frame's :platform"
      (let [f  (rf.frame/make-anon-frame-record! {:platform :server})
            ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/e"]
                                          {:frame f}))]
        ;; SEMANTIC, posture-independent (rf2-o5dbf): the ATTRIBUTION the
        ;; `:frame` tag claims is a frame-state fact — the server frame's own
        ;; slice moved and the ambient `:rf/default` one did not.
        (is (= {:slug "e"}
               (get-in (:rf.db/runtime (rf/frame-state-value f))
                       [:rf.runtime/routing :current :params]))
            "the SSR door committed into the SERVER frame")
        (is (= {:slug "d"} (:params (nav-slice)))
            "…and left the ambient :rf/default frame's slice alone")
        ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (= 1 (count ts)))
          (is (= :ssr (:cause (:tags (first ts)))))
          (is (= f (:frame (:tags (first ts))))
              "attributed to the SERVER frame, not the ambient :rf/default"))))
    (testing "the NON-commit branches emit none — an exact no-op and a
              fragment-only anchor change are not plan commits"
      (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f"])
      (let [token (:nav-token (nav-slice))
            loads (count @loaded)]
        ;; SEMANTIC, posture-independent (rf2-o5dbf): "plans nothing" is a
        ;; runtime-db claim before it is a trace claim. Both `(is (empty? …))`
        ;; legs below are NEGATIVES over the trace ring, which the gate empties
        ;; by design — they would have gone green the moment the roster line
        ;; came off, whatever the door did. What a plan commit WOULD leave
        ;; behind is a fresh nav-token and a re-fired leaf plan, so that is
        ;; what the always-on legs deny.
        (let [ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f"]))]
          (is (= token (:nav-token (nav-slice)))
              "an exact no-op allocates no fresh nav-token")
          (is (= loads (count @loaded))
              "an exact no-op re-fires no loader")
          (when rf.interop/debug-enabled?
            (is (empty? ts) "an exact no-op plans nothing")))
        (let [ts (planned (rf/dispatch-sync [:rf.route/handle-url-change "/articles/f#anchor"]))]
          (is (= "anchor" (:fragment (nav-slice)))
              "the fragment-only change DID land — the door ran, it just did not plan")
          (is (= token (:nav-token (nav-slice)))
              "…with no fresh nav-token")
          (is (= loads (count @loaded))
              "…and no leaf-plan re-fire")
          (when rf.interop/debug-enabled?
            (is (empty? ts)
                "a fragment-only transition plans nothing (no nav-token, no re-plan)")))))))

(deftest an-executed-navigations-plan-trace-is-not-a-carrier
  (rf.routing/reg-route :route/home {} "/")
  (rf.routing/reg-route :route/invite {} "/invite/:id")
  (quiet-nav-fx!)
  (testing "a real navigation carrying a secret in its query and fragment emits a
            plan trace that reproduces NEITHER — the projection became reachable
            without becoming a carrier"
    (let [request {:to       :route/invite
                   :params   {:id "acct-42"}
                   :query    {:invite "SECRET100"}
                   :fragment "tok-99"}
          ts   (planned (rf/dispatch-sync [:rf.route/navigate request]))
          tags (:tags (first ts))
          ;; rf2-o5dbf — WHAT THE BUS WOULD CARRY. The two `re-find` legs in
          ;; the arm below are the sharpest vacuous pass in this artefact:
          ;; under `-Dre-frame.debug=false` `ts` is empty, so `tags` is nil,
          ;; `(pr-str nil)` is "nil", and both would certify that a real
          ;; navigation's secret query value and fragment stayed out of an
          ;; egress copy the framework NEVER MADE. `plan-trace-tags` is a pure
          ;; fn the emit site calls, though — pinned posture-independently in
          ;; `plan-trace-tags-carries-the-projection-without-the-carriers` —
          ;; so the redaction is checkable with no gate in between.
          would-carry (rf.routing.resolve/plan-trace-tags
                        (rf.routing.resolve/route-plan
                          {:cause  :navigate
                           :source request
                           :target (rf.routing.resolve/resolved-target
                                     {:route-id :route/invite
                                      :params   {:id "acct-42"}
                                      :query    {:invite "SECRET100"}
                                      :fragment "tok-99"
                                      :url      "/invite/acct-42?invite=SECRET100#tok-99"})}))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the navigation really
      ;; happened, and the projection over its address really does redact.
      (is (= :route/invite (current-id)) "the navigation committed")
      (is (= {:invite "SECRET100"} (:query (nav-slice)))
          "IN PROCESS the carrier rides raw — redaction is an egress rule, not
           a storage rule (the same distinction routing_egress_test pins)")
      (is (= "tok-99" (:fragment (nav-slice))))
      (is (= [:id] (:param-keys would-carry)))
      (is (= [:invite] (:query-keys would-carry)))
      (is (not (re-find #"SECRET100" (pr-str would-carry)))
          "the projection the emit site consults reproduces no query VALUE")
      (is (not (re-find #"tok-99" (pr-str would-carry)))
          "…and no fragment")
      (is (= "/invite/acct-42?invite=rf/redacted#rf/redacted" (:url would-carry))
          "the structured PATH survives — it is what a consumer branches on")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring): the same
      ;; guarantees, read off the bus that actually carried them.
      (when rf.interop/debug-enabled?
        (is (= 1 (count ts)))
        (is (= [:id] (:param-keys tags)))
        (is (= [:invite] (:query-keys tags)))
        (is (not (re-find #"SECRET100" (pr-str tags))))
        (is (not (re-find #"tok-99" (pr-str tags))))
        (is (= "/invite/acct-42?invite=rf/redacted#rf/redacted" (:url tags))
            "the structured PATH survives — it is what a consumer branches on")))))

(deftest planned-traces-branch-agrees-with-the-activation-rf2-cqyq2
  (rf.routing/reg-route :route/home {} "/")
  (rf.routing/reg-route :route/leaf {:parent :route/nowhere} "/leaf")
  (quiet-nav-fx!)
  (testing "a navigation to a route whose :parent is unregistered emits a
            :rf.route/planned whose :branch names NO unregistered route, and
            whose :branch-error is the same fail-loud error the activation
            aborts on. Before this the trace read
            :branch [:route/nowhere :route/leaf] — a plausible two-segment
            branch naming a route that does not exist — with no hint that
            planning had failed on that very chain."
    (let [entries (atom [])
          ts      (planned
                    (reset! entries
                            (with-route-entry-spy!
                              #(rf/dispatch-sync [:rf.route/navigate {:to :route/leaf}]))))
          tags    (:tags (first ts))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf). The trace's whole claim is
      ;; "the failure signal a TOOL reads is the one the ACTIVATION composes
      ;; over", and the activation's copy arrives through the
      ;; `:routing/on-route-entry` late-bind hook — a fn, not a trace. Without
      ;; this half, `not-any? #{:route/nowhere}` over a nil `:branch` is a
      ;; negative over nothing and passes under the gate for free.
      (is (= 1 (count @entries)) "the activation ran its route-entry plan once")
      (is (= [] (:branch (first @entries)))
          "the activation composes over NO branch — not a plausible two-segment
           one naming :route/nowhere")
      (is (= {:kind :unknown-parent :route-id* :route/nowhere}
             (select-keys (:branch-error (first @entries)) [:kind :route-id*]))
          "…and carries the fail-loud error itself")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= 1 (count ts)))
        (is (= [] (:branch tags)))
        (is (not-any? #{:route/nowhere} (:branch tags))
            "a tool reading the trace is not told a route exists that does not")
        (is (= (select-keys (:branch-error (rf.routing.events/resolve-branch :route/leaf))
                            [:kind :route-id*])
               (:branch-error tags))
            "the trace's failure signal IS the activation's"))))
  (testing "and a well-formed branch carries no :branch-error at all — the tag is
            a failure signal, not a slot that is always present"
    (rf.routing/reg-route :route/shell {} "/shell")
    (rf.routing/reg-route :route/child {:parent :route/shell} "/shell/child")
    (let [entries (atom [])
          tags    (:tags (first (planned
                                  (reset! entries
                                          (with-route-entry-spy!
                                            #(rf/dispatch-sync
                                               [:rf.route/navigate {:to :route/child}]))))))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): `(not (contains? tags
      ;; :branch-error))` is true of ANY nil tag map, so the fact that the
      ;; well-formed case genuinely has no error needs its own witness.
      (is (= [:route/shell :route/child] (mapv :route-id (:branch (first @entries))))
          "the activation composes over the parent-to-leaf branch")
      (is (nil? (:branch-error (first @entries)))
          "…and the well-formed walk really produced no error to report")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= [:route/shell :route/child] (:branch tags)))
        (is (not (contains? tags :branch-error))))))
  (testing "the :branch-error tag carries only registration-time identifiers —
            a kind and a route id, never a route-meta map (a cycle's :chain
            rides on the plan value, not on the trace bus)"
    (rf.routing/reg-route :route/ping {:parent :route/pong} "/ping")
    (rf.routing/reg-route :route/pong {:parent :route/ping} "/pong")
    (rf/dispatch-sync [:rf.route/handle-url-change "/"])
    (let [entries (atom [])
          tags    (:tags (first (planned
                                  (reset! entries
                                          (with-route-entry-spy!
                                            #(rf/dispatch-sync
                                               [:rf.route/navigate {:to :route/ping}]))))))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the cycle really is what
      ;; the activation is handed. The trace's REDACTION of it to two keys is
      ;; the dev-only half — and the `(not (re-find …))` scan over
      ;; `(pr-str nil)` would pass under the gate whatever rode the bus.
      (is (= :parent-cycle (:kind (:branch-error (first @entries))))
          "the activation is handed the cycle, not a silently truncated chain")
      (is (= :route/ping (:route-id* (:branch-error (first @entries)))))
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= {:kind :parent-cycle :route-id* :route/ping} (:branch-error tags)))
        (is (not (re-find #":path|:rf.route/compiled|:chain" (pr-str (:branch-error tags))))
            "no route metadata and no meta-bearing :chain reaches the trace")))))

;; ---- the URL -> ResolvedTarget extraction (ONE definition) ----------------

(deftest url-resolution-normalises-every-fallback-to-the-canonical-target
  (rf.routing/reg-route :route/article {} "/articles/:slug")
  (rf.routing/reg-route :route/typed {:params [:map [:id :int]]} "/typed/:id")
  (testing "a match resolves to the matched route's facts"
    (is (= {:route-id :route/article :params {:slug "x"} :query {}
            :fragment nil :url "/articles/x"}
           (:target (rf.routing.resolve/url-resolution "/articles/x")))))
  (testing "a bare miss normalises to the reserved :rf.route/not-found target"
    (let [{:keys [target fallback? matched?]} (rf.routing.resolve/url-resolution "/no-such-thing")]
      (is (= {:route-id :rf.route/not-found
              :params   {:url "/no-such-thing"}
              :query    {}
              :fragment nil
              :url      "/no-such-thing"}
             target)
          "the reserved route-id, the {:url …} params vocabulary, and an EMPTIED query")
      (is fallback?)
      (is (not matched?))))
  (testing "a validation fail is a MATCH that still normalises to not-found, with :reason"
    (let [{:keys [target validation-fail? matched?]} (rf.routing.resolve/url-resolution "/typed/not-an-int")]
      (is (= :rf.route/not-found (:route-id target)))
      (is (= {:url "/typed/not-an-int" :reason :validation} (:params target)))
      (is validation-fail?)
      (is matched? "the pattern matched — the SCHEMA rejected it")))
  (testing "a malformed URL carries :reason :malformed-url and NO fragment"
    (let [{:keys [target malformed?]} (rf.routing.resolve/url-resolution "/articles/%zz#frag")]
      (is (= :rf.route/not-found (:route-id target)))
      (is (= :malformed-url (:reason (:params target))))
      (is (nil? (:fragment target)) "the fragment may itself be the decode-fail site")
      (is malformed?)))
  (testing "target-of-url is url-resolution's :target — one definition, two arities of need"
    (is (= (:target (rf.routing.resolve/url-resolution "/no-such-thing"))
           (rf.routing.resolve/target-of-url "/no-such-thing")))))

;; ===========================================================================
;; Door wiring — the doors REACH the seam
;; ===========================================================================

(defn- register-denying-not-found!
  "A `:home` route plus a registered `:rf.route/not-found` whose `:can-enter`
  DENIES. Returns `[guard-calls denials pushed]` — the guard-invocation
  counter, the captured `:rf.route/entry-denied` payloads, and the URLs handed
  to `:rf.nav/push-url`."
  []
  (let [calls  (atom 0)
        seen   (atom [])
        pushed (atom [])]
    (rf/reg-route :home {} "/home")
    (rf/reg-route :rf.route/not-found {:can-enter [:deny/not-found]} "/not-found")
    (rf/reg-sub :deny/not-found (fn [_ _] (swap! calls inc) false))
    (rf/reg-event :rf.route/entry-denied (fn [_ [_ d]] (swap! seen conj d) {}))
    (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
               (fn [_ url] (swap! pushed conj url)))
    (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
    [calls seen pushed]))

(deftest link-door-decides-the-same-target-the-commit-hop-would-commit
  (testing "a dead LINK resolves through the shared seam, so the reserved
            :rf.route/not-found route's :can-enter is consulted — the link door
            used to decide against a target with a nil :route-id, find no
            guard, and let the second hop commit the denied route"
    (let [[calls seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (reset! calls 0) (reset! seen [])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/missing-link"}])
      (is (= 1 @calls) ":can-enter on :rf.route/not-found evaluated exactly once")
      (is (= 1 (count @seen)) "one :rf.route/entry-denied")
      (is (= :home (current-id)) "the denial is TERMINAL — the slice did not move")))
  (testing "and the equivalent PROGRAMMATIC door agrees — the two doors were
            the pair the audit caught disagreeing"
    (let [[calls seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (reset! calls 0) (reset! seen [])
      (rf/dispatch-sync [:rf.route/navigate {:url "/missing-programmatic"}])
      (is (= 1 @calls))
      (is (= 1 (count @seen)))
      (is (= :home (current-id))))))

(deftest same-unmatched-link-is-an-exact-no-op
  (testing "clicking the link for the ALREADY-ACTIVE not-found URL is an exact
            no-op (Spec 012 §Per-route data loading rule 3): the link door sees
            the no-op only because it resolves the same canonical target the
            slice carries, so no history entry is pushed"
    (rf/reg-route :home {} "/home")
    (let [pushed (atom [])]
      (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}}
                 (fn [_ url] (swap! pushed conj url)))
      (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil))
      ;; Land on the not-found slice for /same-miss.
      (rf/dispatch-sync [:rf.route/handle-url-change "/same-miss"])
      (is (= :rf.route/not-found (current-id)))
      (reset! pushed [])
      (rf/dispatch-sync [:rf.route/url-requested {:url "/same-miss"}])
      (is (empty? @pushed)
          "no :rf.nav/push-url — the exact no-op terminated before history moved")
      (is (= :rf.route/not-found (current-id))))))

;; ---- both URL-bearing doors share ONE reason vocabulary (rf2-teov0) --------
;;
;; `plan.cljc`'s not-found section states the invariant: "The reason vocabulary
;; is SHARED across both entry points … a malformed percent-encoding stamps
;; `:malformed-url` … Encoding the shape once keeps the two paths' fallback
;; params byte-for-byte identical." It did not. The programmatic `{:url …}` door
;; resolved its own URL — `match-url-fail-closed` called directly, the not-found
;; shape re-derived inline — so it never ran the `malformed-url?` scan and
;; hardcoded `:malformed? false` into the SHARED telemetry call. On the same
;; malformed URL the URL-driven door stamped `:reason :malformed-url` and warned;
;; the programmatic door stamped `{:url …}` and said nothing. That is the one
;; door Spec 012 documents as taking user-supplied URLs, and `egress.cljc` names
;; the unmatched URL as the class most likely to carry `?token=` — so the
;; EP-0015 malformed-URL diagnostic was absent exactly where malformed input
;; arrives.

(defn- door-fallback
  "Drive ONE not-found navigation and report what the two surfaces a consumer
  reads actually say: the slice's `:route-id` + `:params`, and the fail-closed
  warning operations the drain emitted."
  [dispatch]
  (with-trace-recorder! [traces {:pred #(contains? #{:rf.warning/malformed-url
                                                     :rf.warning/no-not-found-route}
                                                   (:operation %))}]
    (rf/dispatch-sync dispatch)
    {:route-id (current-id)
     :params   (get-in (rdb) [:rf.runtime/routing :current :params])
     :warnings (mapv :operation @traces)}))

(deftest both-url-bearing-doors-stamp-the-same-not-found-reason
  (rf.routing/reg-route :route/home {} "/home")
  (rf.routing/reg-route :route/typed {:params [:map [:id :int]]} "/typed/:id")
  (rf.routing/reg-route :rf.route/not-found {} "/not-found")
  (quiet-nav-fx!)
  (testing "a BARE miss — the discriminator that already agreed"
    (let [url-driven   (door-fallback [:rf.route/handle-url-change "/miss-a"])
          programmatic (door-fallback [:rf.route/navigate {:url "/miss-b"}])]
      (is (= :rf.route/not-found (:route-id url-driven) (:route-id programmatic)))
      (is (= {:url "/miss-a"} (:params url-driven)))
      (is (= {:url "/miss-b"} (:params programmatic)))
      ;; SEMANTIC, posture-independent (rf2-o5dbf): "not a malformed URL" is
      ;; spelled on the SLICE as the ABSENCE of a `:reason` — and the two
      ;; `{:url …}` equalities above already say exactly that, key-for-key.
      ;; The `(= [] …)` leg below is a negative over the trace ring, which the
      ;; gate empties by design, so it is inside the arm.
      (is (not (contains? (:params url-driven) :reason))
          "a well-formed miss stamps no :reason on the slice")
      (is (not (contains? (:params programmatic) :reason)))
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when rf.interop/debug-enabled?
        (is (= [] (:warnings url-driven) (:warnings programmatic))
            "a well-formed miss is not a malformed URL"))))
  (testing "a MALFORMED percent-encoding — the discriminator that did NOT agree.
            The programmatic door yielded {:url …} and emitted no warning at
            all, so a per-route error UI branching on :reason (Spec 012) and the
            EP-0015 malformed-URL diagnostic both went dark on the one door that
            takes user-supplied URLs."
    (let [url-driven   (door-fallback [:rf.route/handle-url-change "/miss-a/%zz"])
          programmatic (door-fallback [:rf.route/navigate {:url "/miss-b/%zz"}])]
      (is (= :rf.route/not-found (:route-id url-driven) (:route-id programmatic)))
      (is (= :malformed-url (:reason (:params url-driven))))
      (is (= :malformed-url (:reason (:params programmatic)))
          "the two doors stamp the SAME :reason for the same class of URL")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring). The
      ;; per-route error UI branches on the always-on `:reason` above; the
      ;; EP-0015 DIAGNOSTIC rides `trace/emit!` and is dev-only.
      (when rf.interop/debug-enabled?
        (is (= [:rf.warning/malformed-url] (:warnings url-driven)))
        (is (= [:rf.warning/malformed-url] (:warnings programmatic))
            "EP-0015's malformed-URL diagnostic fires on BOTH doors"))))
  (testing "a match-url THROW — the second discriminator that already agreed,
            pinned so the merge onto the shared extraction cannot lose it"
    (with-redefs [rf.routing.registry/match-url
                  (fn [_] (throw (ex-info "simulated hostile-URL parse failure" {})))]
      (let [url-driven   (door-fallback [:rf.route/handle-url-change "/throw-a"])
            programmatic (door-fallback [:rf.route/navigate {:url "/throw-b"}])]
        (is (= :rf.route/not-found (:route-id url-driven) (:route-id programmatic)))
        (is (= {:url "/throw-a" :reason :match-error} (:params url-driven)))
        (is (= {:url "/throw-b" :reason :match-error} (:params programmatic)))
        ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (is (= [:rf.warning/malformed-url] (:warnings url-driven)))
          (is (= [:rf.warning/malformed-url] (:warnings programmatic)))))))
  (testing "a VALIDATION miss is the RATIFIED asymmetry, not a defect: Spec 012's
            resolve-target table and §Validation-error surfacing ratify
            URL-driven-routes-to-not-found vs programmatic-caller-bug-rejects.
            The merge preserves it exactly — the programmatic door takes the
            MATCHED route-id, so `route-url` rejects the caller's bad params
            rather than routing to not-found."
    (let [url-driven (door-fallback [:rf.route/handle-url-change "/typed/not-an-int"])]
      (is (= :rf.route/not-found (:route-id url-driven)))
      (is (= {:url "/typed/not-an-int" :reason :validation} (:params url-driven))))
    (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
    (rf/dispatch-sync [:rf.route/navigate {:url "/typed/also-not-an-int"}])
    (is (= :route/home (current-id))
        "the programmatic door REJECTS a validation miss — slice unchanged")))

;; ---- the URL-change door reports WHICH of its three sub-doors fired --------

(deftest url-change-cause-resolves-the-true-sub-door
  (testing "the framework listener's :rf.route/cause rider wins"
    (is (= :popstate (rf.routing.url-change/url-change-cause :rf/default {:rf.route/cause :popstate})))
    (is (= :initial  (rf.routing.url-change/url-change-cause :rf/default {:rf.route/cause :initial}))))
  (testing "a rider outside the closed cause set cannot invent a sixth cause"
    (is (= :initial (rf.routing.url-change/url-change-cause :rf/default {:rf.route/cause :not-a-cause})))
    (is (= :initial (rf.routing.url-change/url-change-cause :rf/default {:rf.route/cause "popstate"}))))
  (testing "no rider on a CLIENT frame is the initial / direct-URL feed"
    (is (= :initial (rf.routing.url-change/url-change-cause :rf/default {})))
    (is (= :initial (rf.routing.url-change/url-change-cause :rf/default nil))))
  (testing "no rider on a SERVER frame is the SSR request-URL feed"
    (is (= :ssr (rf.routing.url-change/url-change-cause
                  (rf.frame/make-anon-frame-record! {:platform :server}) {})))))

(deftest executed-url-change-navigation-carries-its-true-cause
  (testing "the cause the DOOR passes is observable on the denial payload, so a
            door that hardcodes one cause for three sub-doors fails here"
    (let [[_ seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])

      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-a" {:rf.route/cause :popstate}])
      (is (= [:popstate] (mapv :cause @seen)) "Back/Forward reports :popstate")

      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-b" {:rf.route/cause :initial}])
      (is (= [:initial] (mapv :cause @seen)) "the initial URL sync reports :initial")

      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-c"])
      (is (= [:initial] (mapv :cause @seen))
          "a bare client-frame dispatch is an initial feed, NOT :popstate")))
  (testing "the SSR feed — dispatched by the app's own :initial-events, so it
            carries no rider — reports :ssr off the frame's :platform"
    (let [[_ seen _] (register-denying-not-found!)
          f          (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf.fx/reg-fx :rf.server/set-status {:platforms #{:server :client}} (fn [_ _] nil))
      (reset! seen [])
      (rf/dispatch-sync [:rf.route/handle-url-change "/miss-ssr"] {:frame f})
      (is (= [:ssr] (mapv :cause @seen)))))
  (testing "the forward link/push door still reports :link"
    (let [[_ seen _] (register-denying-not-found!)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/home"])
      (reset! seen [])
      (rf/dispatch-sync [:rf.route/transitioned "/miss-link"])
      (is (= [:link] (mapv :cause @seen))))))
