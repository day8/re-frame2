(ns re-frame.resources-route-plan-recovery-cljs-test
  "rf2-ma8r — WHEN a `:url-bound?` frame plans its route's resources, and what
  a FAILED plan can be recovered by.

  The bead that produced this file was filed as a live-frame reprojection bug:
  a consumer workspace whose `init!` did `(rf/make-frame {:id :rf/default
  :url-bound? true})` FIRST and registered its resources / mutations / fx
  AFTERWARDS saw the frame 'never pick up anything registered after
  make-frame'. That reading is refuted — late registration IS reprojected — and
  what is actually going on is an ORDERING fact about `:url-bound?` frames plus
  a recovery door that the report did not reach for. This file pins both, so
  neither has to be re-derived.

  ## 1. A `:url-bound?` frame syncs the CURRENT URL at CONSTRUCTION (CLJS)

  `frame/upsert-frame!` fires `:routing/on-frame-registered!`, whose body
  reconciles the browser URL listener, and installing that listener performs an
  INITIAL SYNC — `[:rf.route/handle-url-change <current url> {:rf.route/cause
  :initial}]`, dispatched SYNCHRONOUSLY (Spec 012 §URL changes are events).
  So a route matching the URL the page is already on is entered, and its
  `:resources` are planned, INSIDE the `make-frame` call. A resource the app
  registers on the next line is registered too late for that plan — not because
  the frame cannot see it, but because the plan already ran.

  This is CLJS-only: the hook body's listener reconcile is `#?(:cljs …)`, so on
  the JVM a `:url-bound?` `make-frame` performs no URL sync at all and the
  ordering question does not arise. Hence the two `#?(:cljs …)` deftests below.

  ## 2. A FAILED plan is STICKY under identical navigation — and REPAIRABLE

  Navigating to the route the app is already on is deliberately a no-op (Spec
  012 §Navigation is an event, rule 3), so a `[:rf.route/navigate {:to <same
  route>}]` issued after the missing resource IS registered does NOT re-plan:
  the slice keeps its `:rf.error/resource-route-plan`. The recovery is not a
  navigation at all — it is `[:rf.route/replan-resources {:cause …}]` (Spec 012
  §Replanning the active route's resources / Spec 016 §Route-plan replan), the
  same-token command whose stated purpose is that a successful replan CLEARS an
  earlier `:rf.error/resource-route-plan`. It repairs a failed ACTIVATION the
  same way it repairs a failed replan: the token's plan slot is absent after a
  committed failed activation, so every identity is `added`.

  The `re-frame.resources-route-replan-cljs-test` sibling pins replan's
  reconciliation semantics against an unresolved SCOPE. What is pinned HERE is
  the unregistered-RESOURCE failure — the one a consumer hits by ordering
  `make-frame` before `reg-resource` — and the same-route-navigate no-op that
  makes the error look permanent.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via `cljs-test$`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as fx]
   ;; load-bearing side-effecting requires: register the resources + routing
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as route]
   [re-frame.resources.test-support]
   [re-frame.routing :as routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as registrar]
   [re-frame.test-support :as core-test-support]
   ;; The node-runtime window/history/location stub — CLJS only; the two
   ;; construction-time tests set `location.pathname` through it so the URL
   ;; under test is OWNED by this suite rather than by whichever co-loaded
   ;; sibling happens to register a route at "/".
   #?(:cljs [re-frame.routing-browser-test-support :refer [with-window-stub-fixture]])
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; ---- fixture --------------------------------------------------------------

(def ^:private board-path
  "A path this suite owns outright. Deliberately NOT \"/\": in the consolidated
  `:node-test` bundle several co-loaded apps register a route at \"/\", so a
  suite that leans on \"/\" measures whichever sibling won rather than its own
  subject."
  "/ma8r-board")

(def ^:private requests
  "Every managed-HTTP request lowered during a test, in order."
  (atom []))

(defn- init!
  "Per-test setup. Registers the ROUTE but deliberately NOT the resource it
  declares: each test decides where in the sequence `reg-resource` lands, which
  is the whole variable under study. No frame is made here either — the
  fixture's own `:rf/default` is NOT `:url-bound?`, so nothing syncs a URL
  until a test asks for it."
  []
  (routing/reset-counters!)
  (route/install-routing-integration!)
  (registrar/clear-kind! :resource-scope)
  (reset! requests [])
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! requests conj args) nil))
  (fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-route :ma8r/board
    {:resources [{:resource :ma8r/board-data :blocking? true}]}
    board-path))

(use-fixtures :each
  #?@(:cljs [with-window-stub-fixture])
  (core-test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(defn- runtime-db [] (:rf.db/runtime (rf/frame-state-value :rf/default)))
(defn- slice [] (get-in (runtime-db) [:rf.runtime/routing :current]))
(defn- token [] (:nav-token (slice)))
(defn- slice-error [] (:error (slice)))

(defn- reg-board-resource! []
  (rf/reg-resource :ma8r/board-data
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_ _] {:request {:method :get :url "/api/board"}})))

(defn- unregistered-resource-plan-failure?
  "The slice carries the failed route plan whose CAUSE is the missing
  registration — the exact shape a consumer sees when `make-frame` runs before
  `reg-resource`."
  []
  (let [e (slice-error)]
    (and (= :rf.error/resource-route-plan (:rf.error/id e))
         (= :ma8r/board-data (:resource-id e))
         (= :rf.error/resource-not-registered (:rf.error/id (:cause e)))
         (= :fix-registration (:recovery e)))))

#?(:cljs
   (defn- set-url! [path]
     (set! (.-pathname (.-location js/window)) path)))

;; ===========================================================================
;; 1. the failed plan is STICKY under identical navigation, and REPAIRED by
;;    an explicit replan — no navigation involved
;; ===========================================================================

(deftest same-route-navigate-does-not-replan-a-failed-plan-but-replan-resources-repairs-it
  (testing "a route whose blocking resource is unregistered fails its plan"
    (rf/dispatch-sync [:rf.route/navigate {:to :ma8r/board}])
    (is (= :ma8r/board (:route-id (slice))))
    (is (= :error (:transition (slice))))
    (is (unregistered-resource-plan-failure?)
        "the plan fails with :rf.error/resource-route-plan caused by :rf.error/resource-not-registered")
    (is (empty? @requests) "a failed plan issues no request"))

  (let [tok (token)]
    (testing "registering the resource afterwards does not, by itself, re-plan"
      (reg-board-resource!)
      (is (some? (registrar/lookup :resource :ma8r/board-data))
          "control: the resource IS registered and visible now")
      (rf/dispatch-sync [:rf.route/navigate {:to :ma8r/board}])
      (is (= tok (token))
          "identical navigation is a deliberate no-op — no new nav-token")
      (is (= :error (:transition (slice))))
      (is (unregistered-resource-plan-failure?)
          "so the stale planning error survives a same-route navigate")
      (is (empty? @requests) "and still no request"))

    (testing "[:rf.route/replan-resources] repairs it under the SAME token"
      (rf/dispatch-sync [:rf.route/replan-resources {:cause [:ma8r/resource-registered]}])
      (is (= tok (token)) "a replan is not a navigation — the token is preserved")
      (is (nil? (slice-error)) "the planning error is CLEARED")
      (is (= :loading (:transition (slice))))
      (is (= 1 (count @requests)) "and the blocking read is finally requested")
      (is (= "/api/board" (get-in (first @requests) [:request :url]))))))

;; ===========================================================================
;; 2. WHERE the plan actually runs: inside `make-frame`, for a `:url-bound?`
;;    frame whose current URL matches a registered route (CLJS only — the
;;    initial URL sync is `#?(:cljs …)`)
;; ===========================================================================

#?(:cljs
   (deftest url-bound-make-frame-plans-the-current-url-route-at-construction
     (set-url! board-path)
     (is (nil? (slice)) "no route has been entered before the frame is made")
     (testing "make-frame itself enters the URL's route and plans its resources"
       (rf/make-frame {:id :rf/default :url-bound? true})
       (is (= :ma8r/board (:route-id (slice)))
           "the initial URL sync ran INSIDE make-frame — no navigate was dispatched")
       (is (= :error (:transition (slice))))
       (is (unregistered-resource-plan-failure?)
           "so a resource registered on the NEXT line is already too late for this plan"))
     (testing "the recovery is the same replan door"
       (let [tok (token)]
         (reg-board-resource!)
         (rf/dispatch-sync [:rf.route/replan-resources {:cause [:ma8r/registered-after-make-frame]}])
         (is (= tok (token)))
         (is (nil? (slice-error)))
         (is (= :loading (:transition (slice))))
         (is (= 1 (count @requests)))))))

#?(:cljs
   (deftest registering-the-resource-before-make-frame-plans-cleanly
     ;; The CONTROL for the test above: same URL, same route, same frame
     ;; config — the ONE variable changed is that `reg-resource` now precedes
     ;; `make-frame`. This is the ordering the published consumer baseline
     ;; uses, and it is the difference between a red suite and a green one.
     (set-url! board-path)
     (reg-board-resource!)
     (rf/make-frame {:id :rf/default :url-bound? true})
     (is (= :ma8r/board (:route-id (slice))))
     (is (nil? (slice-error)) "nothing failed to plan")
     (is (= :loading (:transition (slice))))
     (is (= 1 (count @requests))
         "the blocking read was requested by the construction-time plan itself")))
