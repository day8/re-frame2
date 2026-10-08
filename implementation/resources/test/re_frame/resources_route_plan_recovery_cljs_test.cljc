(ns re-frame.resources-route-plan-recovery-cljs-test
  "WHEN a `:url-bound?` frame plans its route's resources, and what a FAILED
  plan can be recovered by.

  On CLJS a `:url-bound?` `make-frame` installs the URL listener, whose initial
  sync (Spec 012 §URL changes are events) enters the route matching the current
  URL and plans its `:resources` INSIDE the `make-frame` call; a resource
  registered on the next line is too late for that plan. On the JVM the hook's
  listener reconcile is `#?(:cljs …)`, so no URL sync happens and the ordering
  question does not arise: hence the two CLJS-only deftests.

  A failed plan is sticky under identical navigation (navigating to the current
  route is a no-op, Spec 012 §Navigation is an event, rule 3) and repaired by
  `[:rf.route/replan-resources {:cause …}]` under the same token (Spec 016
  §Route-plan replan), which clears an earlier `:rf.error/resource-route-plan`.
  `re-frame.resources-route-replan-cljs-test` pins replan against an unresolved
  scope; this file pins the unregistered-resource failure."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.fx :as rf.fx]
   ;; load-bearing side-effecting requires: register the resources + routing
   ;; events / subs and resources' late-bound :routing/* integration hooks.
   [re-frame.resources]
   [re-frame.resources.route :as rf.resources.route]
   [re-frame.resources.test-support]
   [re-frame.routing :as rf.routing]
   [re-frame.schemas]
   [re-frame.http.managed]
   [re-frame.registrar :as rf.registrar]
   [re-frame.test-support :as rf.test-support]
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
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.registrar/clear-kind! :resource-scope)
  (reset! requests [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! requests conj args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/reg-route :ma8r/board
    {:resources [{:resource :ma8r/board-data :blocking? true}]}
    board-path))

(use-fixtures :each
  #?@(:cljs [with-window-stub-fixture])
  (rf.test-support/make-reset-runtime-fixture
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
  (rf/dispatch-sync [:rf.route/navigate {:to :ma8r/board}])
  (is (= [:ma8r/board :error true []]
         [(:route-id (slice)) (:transition (slice)) (unregistered-resource-plan-failure?) @requests])
      "a route whose blocking resource is unregistered fails its plan and issues no request")
  (let [tok (token)]
    (reg-board-resource!)
    (is (some? (rf.registrar/lookup :resource :ma8r/board-data)) "FIXTURE — the resource is registered now")
    (rf/dispatch-sync [:rf.route/navigate {:to :ma8r/board}])
    (is (= [tok :error true []]
           [(token) (:transition (slice)) (unregistered-resource-plan-failure?) @requests])
        "identical navigation is a no-op under the same token, so the stale planning error survives")
    (rf/dispatch-sync [:rf.route/replan-resources {:cause [:ma8r/resource-registered]}])
    (is (= [tok nil :loading ["/api/board"]]
           [(token) (slice-error) (:transition (slice)) (mapv #(get-in % [:request :url]) @requests)])
        "the replan clears the error under the same token, and the blocking read is requested")))

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
