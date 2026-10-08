(ns re-frame.resources-example-cljs-test
  "Drives the resources example (`examples/capabilities/resources/resources/`)
   through the causal patterns it teaches: route-driven page load, an
   app-event owner's ensure/release, manual refresh as a cause, and a
   machine-owned resource. The generic resource runtime has its own suites;
   these pin the example's own composition.

   Each test stubs `:rf.http/managed` with a capturing no-op and replays the
   reply through the transport's real reply shape
   (`(conj on-success {:status :ok :value …})`), and stubs the URL push, so
   everything settles synchronously without a browser."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.resources]
            [re-frame.resources.route :as rf.resources.route]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.test-support]
            [re-frame.routing :as rf.routing]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [resources.core])
  (:require-macros [re-frame.core :refer [with-new-frame]]))

(def ^:private last-managed-args (atom nil))

;; The reset fixture's `:resources/reset-resources!` hook clears the
;; `:resource` kind between tests, and CLJS cannot re-require the example, so
;; its ns-load registrations are snapshotted here and reinstalled by `init!`.
(def ^:private resource-kind-snapshot
  (get @rf.registrar/kind->id->metadata :resource))

;; Remove our ids from the shared registrar at load, so another suite's reset
;; never clears them and mirrors the resulting frameless
;; `:rf.registry/handler-cleared` burst into a tooling test's trace collector.
(swap! rf.registrar/kind->id->metadata update :resource
       (fn [m] (apply dissoc m (keys resource-kind-snapshot))))

(defn- init!
  "Reinstall the example's resources, routing integration and fx stubs, then
   make the url-bound frame LAST: it syncs the URL at construction, so a route
   at \"/\" plans its resources inside `make-frame` and would see an empty
   `:resource` kind, recording a sticky route-plan error."
  []
  (reset! last-managed-args nil)
  ;; `register!` writes registrar and source store together, so image-loaded
  ;; frames see the reinstated rows.
  (doseq [[id meta] resource-kind-snapshot]
    (rf.registrar/register! :resource id meta))
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "resources-example default app frame."}))

(defn- isolate-trace-bus-fixture
  "Outermost fixture: clear trace listeners and rings around each test, so the
   reset fixture's frameless `:rf.registry/handler-cleared` burst never reaches
   a tooling test's collector as spurious cascades."
  [f]
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (f)
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!))

(use-fixtures :each
  isolate-trace-bus-fixture
  (rf.test-support/make-reset-runtime-fixture
    ;; Every co-loaded example registers `:rf.route/not-found`; `:app-ns`
    ;; keeps this app's rows out of other suites' baselines.
    {:adapter rf.adapter.reagent/adapter
     :app-ns  "resources."
     :init-fn init!}))

(defn- entry
  ([scoped-key] (entry :rf/default scoped-key))
  ([f scoped-key]
   (get-in (:rf.db/runtime (rf/frame-state-value f)) (rf.resources.state/entry-path scoped-key))))

(defn- owned? [f scoped-key owner]
  (contains? (:active-owners (entry f scoped-key)) owner))

(defn- list-key []
  (rf.resources.state/scoped-resource-key :rf.scope/global :articles/list {}))

(defn- detail-key [slug]
  (rf.resources.state/scoped-resource-key :rf.scope/global :article/by-slug {:slug slug}))

(defn- reply-success! [args data]
  (rf/dispatch-sync (conj (:on-success args) {:status :ok :value data})))

(defn- route-state
  "The passive resource view-model the example's views read."
  [query]
  (rf/compute-sub [:rf/resource query] (rf/frame-state-value :rf/default)))

(defn- preview-frame []
  (rf.frame/make-anon-frame-record! {:url-bound? true
                                     :fx-overrides {:rf.nav/push-url :rf/no-op}}))

(deftest article-detail-route-threads-the-url-slug-into-resource-params
  (rf/dispatch-sync [:rf.route/navigate {:to :resources.app/article-detail :params {:slug "owners-vs-causes"}}])
  (reply-success! @last-managed-args {:slug "owners-vs-causes" :title "Owners vs Causes" :body "..."})
  (is (= "Owners vs Causes"
         (:title (:data (route-state {:resource :article/by-slug :params {:slug "owners-vs-causes"}}))))
      "the per-slug detail read is ensured on entry and carries the right article"))

;; Every Preview button stays live while one Close control reaches only the
;; current slug, so opening B over A must release A's owner or A leaks.
(deftest replacing-a-preview-releases-the-prior-slug-owner-so-neither-leaks
  (with-new-frame [f (preview-frame)]
    (let [owner-a [:resources.app/preview-opened "resources-101"]
          owner-b [:resources.app/preview-opened "owners-vs-causes"]
          dkey-a  (detail-key "resources-101")
          dkey-b  (detail-key "owners-vs-causes")
          slug    #(rf/compute-sub [:resources.app/preview-slug] (rf/frame-state-value f))]
      (rf/dispatch-sync [:resources.app/preview-opened "resources-101"] {:frame f})
      (reply-success! @last-managed-args {:slug "resources-101" :title "R101" :body "..."})
      (is (owned? f dkey-a owner-a) "precondition: A is ensured under its app-event owner")
      (rf/dispatch-sync [:resources.app/preview-opened "owners-vs-causes"] {:frame f})
      (is (= ["owners-vs-causes" false true]
             [(slug) (owned? f dkey-a owner-a) (owned? f dkey-b owner-b)])
          "opening B replaces A: A's owner released, B ensured under its own")
      (rf/dispatch-sync [:resources.app/preview-closed "owners-vs-causes"] {:frame f})
      (is (= [nil false] [(slug) (owned? f dkey-b owner-b)])
          "closing clears the slug and releases B's owner"))))

;; Reopening the open slug must re-ensure, not release and reacquire: a
;; detach of a present owner bumps :revision.
(deftest reopening-the-same-preview-slug-does-not-churn-its-owner
  (with-new-frame [f (preview-frame)]
    (let [owner-a [:resources.app/preview-opened "fresh-skip"]
          dkey-a  (detail-key "fresh-skip")]
      (rf/dispatch-sync [:resources.app/preview-opened "fresh-skip"] {:frame f})
      (reply-success! @last-managed-args {:slug "fresh-skip" :title "Fresh" :body "..."})
      (let [rev-before (:revision (entry f dkey-a))]
        (reset! last-managed-args nil)
        (rf/dispatch-sync [:resources.app/preview-opened "fresh-skip"] {:frame f})
        (is (owned? f dkey-a owner-a))
        (is (= rev-before (:revision (entry f dkey-a))) "no release and reacquire")
        (is (nil? @last-managed-args) "a fresh entry is not refetched")))))

(deftest manual-refresh-is-a-cause-and-forces-a-refetch-keeping-data
  (rf/dispatch-sync [:rf.route/navigate {:to :resources.app/articles}])
  (let [nav-token (get-in (rf/frame-state-value :rf/default)
                          [:rf.db/runtime :rf.runtime/routing :current :nav-token])
        lkey      (list-key)]
    (reply-success! @last-managed-args [{:slug "a" :title "A"}])
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:resources.app/refresh-articles])
    (let [e (entry lkey)]
      (is (= [:fetching [{:slug "a" :title "A"}]] ((juxt :status :data) e))
          "refresh refetches a loaded list and keeps its data while in flight")
      (is (= #{[:route :resources.app/articles nav-token]} (:active-owners e))
          "refresh added no owner; the route remains the sole liveness owner"))
    (reply-success! @last-managed-args [{:slug "a" :title "A2"} {:slug "c" :title "C"}])
    (is (= [:loaded [{:slug "a" :title "A2"} {:slug "c" :title "C"}]]
           ((juxt :status :data) (entry lkey))))))

;; The reader must own its read under the two-part actor-id owner
;; `[:machine :resources.app/reader]`: actor destroy auto-releases only that
;; key (Spec 016 §Release authority is per owner kind), so a three-part
;; `[:machine machine-id instance-id]` owner would leak.
(deftest reader-owns-its-article-under-the-actor-id-owner-released-on-stop
  (let [slug        "resources-101"
        actor-owner [:machine :resources.app/reader]
        dkey        (detail-key slug)
        reader      #(rf/compute-sub [:resources.app/reader] (rf/frame-state-value :rf/default))]
    (rf/dispatch-sync [:resources.app/start-reader slug])
    (is (= {:slug slug :instance-id (str "reader-" slug)} (reader))
        "start-reader records the active reader instance the view's stop affordance reads")
    (is (owned? :rf/default dkey actor-owner))
    (rf/dispatch-sync [:resources.app/stop-reader])
    (is (nil? (reader)) "stop-reader clears the reader slice")
    (is (not (owned? :rf/default dkey actor-owner))
        "stop-reader released the machine owner, so the read is not left pinned")))

(deftest first-slug-projection-layers-over-the-passive-list-resource
  (rf/dispatch-sync [:rf.route/navigate {:to :resources.app/articles}])
  (reply-success! @last-managed-args
                  [{:slug "resources-101" :title "Resources 101"}
                   {:slug "owners-vs-causes" :title "Owners vs Causes"}])
  (is (= "resources-101"
         (rf/compute-sub [:resources.app/first-slug] (rf/frame-state-value :rf/default)))))
