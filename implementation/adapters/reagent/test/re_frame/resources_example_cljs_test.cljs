(ns re-frame.resources-example-cljs-test
  "The resources example preserves route, app-event and machine ownership while refresh remains a cause."
  (:require [cljs.test :refer-macros [deftest use-fixtures is]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
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

(def ^:private resource-kind-snapshot
  (rf.registrar/registrations :resource))

(swap! rf.source-store/kind->id->ns->descriptor update :resource
       (fn [m] (apply dissoc m (keys resource-kind-snapshot))))

;; Reset clears resource rows; reinstall the example before constructing its URL-bound frame.
(defn- init!
  []
  (reset! last-managed-args nil)
  (doseq [[id meta] resource-kind-snapshot]
    (rf.registrar/register! :resource id meta))
  (rf.routing/reset-counters!)
  (rf.resources.route/install-routing-integration!)
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! last-managed-args args) nil))
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf/make-frame {:id :rf/default :url-bound? true
                  :doc "resources-example default app frame."}))

(defn- isolate-trace-bus-fixture
  [f]
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (f)
  (rf.trace.tooling/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!))

(use-fixtures :each
  isolate-trace-bus-fixture
  (rf.test-support/make-reset-runtime-fixture
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
  [query]
  (rf/compute-sub [:rf/resource query] (rf/frame-state-value :rf/default)))

(defn- preview-frame []
  ;; not URL-bound: the fixture's :rf/default owns the URL
  (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.nav/push-url :rf/no-op}}))

(deftest article-detail-route-threads-the-url-slug-into-resource-params
  (rf/dispatch-sync [:rf.route/navigate {:to :resources.app/article-detail :params {:slug "owners-vs-causes"}}])
  (reply-success! @last-managed-args {:slug "owners-vs-causes" :title "Owners vs Causes" :body "..."})
  (is (= "Owners vs Causes"
         (:title (:data (route-state {:resource :article/by-slug :params {:slug "owners-vs-causes"}}))))))

(deftest replacing-a-preview-releases-the-prior-slug-owner-so-neither-leaks
  (with-new-frame [f (preview-frame)]
    (let [owner-a [:resources.app/preview-opened "resources-101"]
          owner-b [:resources.app/preview-opened "owners-vs-causes"]
          dkey-a  (detail-key "resources-101")
          dkey-b  (detail-key "owners-vs-causes")
          slug    #(rf/compute-sub [:resources.app/preview-slug] (rf/frame-state-value f))]
      (rf/dispatch-sync [:resources.app/preview-opened "resources-101"] {:frame f})
      (reply-success! @last-managed-args {:slug "resources-101" :title "R101" :body "..."})
      (is (owned? f dkey-a owner-a))
      (rf/dispatch-sync [:resources.app/preview-opened "owners-vs-causes"] {:frame f})
      (is (= ["owners-vs-causes" false true]
             [(slug) (owned? f dkey-a owner-a) (owned? f dkey-b owner-b)]))
      (rf/dispatch-sync [:resources.app/preview-closed "owners-vs-causes"] {:frame f})
      (is (= [nil false] [(slug) (owned? f dkey-b owner-b)])))))

(deftest reopening-the-same-preview-slug-does-not-churn-its-owner
  (with-new-frame [f (preview-frame)]
    (let [owner-a [:resources.app/preview-opened "fresh-skip"]
          dkey-a  (detail-key "fresh-skip")]
      (rf/dispatch-sync [:resources.app/preview-opened "fresh-skip"] {:frame f})
      (reply-success! @last-managed-args {:slug "fresh-skip" :title "Fresh" :body "..."})
      (let [rev-before (:revision (entry f dkey-a))]
        (reset! last-managed-args nil)
        (rf/dispatch-sync [:resources.app/preview-opened "fresh-skip"] {:frame f})
        (is (= [true rev-before nil]
               [(owned? f dkey-a owner-a)
                (:revision (entry f dkey-a))
                @last-managed-args]))))))

(deftest manual-refresh-is-a-cause-and-forces-a-refetch-keeping-data
  (rf/dispatch-sync [:rf.route/navigate {:to :resources.app/articles}])
  (let [nav-token (get-in (rf/frame-state-value :rf/default)
                          [:rf.db/runtime :rf.runtime/routing :current :nav-token])
        lkey      (list-key)]
    (reply-success! @last-managed-args [{:slug "a" :title "A"}])
    (reset! last-managed-args nil)
    (rf/dispatch-sync [:resources.app/refresh-articles])
    (let [e (entry lkey)]
      (is (= [[:fetching [{:slug "a" :title "A"}]] #{[:route :resources.app/articles nav-token]}]
             [((juxt :status :data) e)
              (:active-owners e)])))
    (reply-success! @last-managed-args [{:slug "a" :title "A2"} {:slug "c" :title "C"}])
    (is (= [:loaded [{:slug "a" :title "A2"} {:slug "c" :title "C"}]]
           ((juxt :status :data) (entry lkey))))))

(deftest reader-owns-its-article-under-the-actor-id-owner-released-on-stop
  (let [slug        "resources-101"
        actor-owner [:machine :resources.app/reader]
        dkey        (detail-key slug)
        reader      #(rf/compute-sub [:resources.app/reader] (rf/frame-state-value :rf/default))]
    (rf/dispatch-sync [:resources.app/start-reader slug])
    (is (= [{:slug slug :instance-id (str "reader-" slug)} true]
           [(reader)
            (owned? :rf/default dkey actor-owner)]))
    (rf/dispatch-sync [:resources.app/stop-reader])
    (is (= [nil true]
           [(reader)
            (not (owned? :rf/default dkey actor-owner))]))))

(deftest first-slug-projection-layers-over-the-passive-list-resource
  (rf/dispatch-sync [:rf.route/navigate {:to :resources.app/articles}])
  (reply-success! @last-managed-args
                  [{:slug "resources-101" :title "Resources 101"}
                   {:slug "owners-vs-causes" :title "Owners vs Causes"}])
  (is (= "resources-101"
         (rf/compute-sub [:resources.app/first-slug] (rf/frame-state-value :rf/default)))))
