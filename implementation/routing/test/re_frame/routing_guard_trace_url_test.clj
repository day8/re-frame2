(ns re-frame.routing-guard-trace-url-test
  "A `:can-leave` that blocks and a `:can-enter` that denies each emit one
  trace, `:rf.route/navigation-blocked` or `:rf.route/entry-denied`, carrying
  the URL the guard stopped as `:requested-url`. That URL is projected by the
  `:sensitive` declaration of the route it resolves to and then has every query
  and fragment carrier value redacted, so a declared path param never rides
  the trace raw, through any door, while an undeclared one stays readable
  (Spec 012 §Navigation blocking — the leave-only pending protocol, §Entry is
  terminal).

  The trace reads sit behind `trace/emit!`, gated on
  `rf.interop/debug-enabled?`, so each is inside a
  `(when rf.interop/debug-enabled? …)` arm; that the guard held is read off
  the route slice outside it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private secret "PATH_SECRET_9")

(defn- register-routes!
  "`:probe/home`; `:probe/editor`, whose `:can-leave` blocks; and two routes
  whose `:can-enter` denies — `:probe/reset`, declaring its `token` path param
  sensitive, and `:probe/open`, whose `slug` is undeclared."
  []
  (rf/reg-sub :probe/refuse (fn [_ _] false))
  (rf/reg-route :probe/home {} "/home")
  (rf/reg-route :probe/editor {:can-leave [:probe/refuse]} "/editor")
  (rf/reg-route :probe/reset {:sensitive [[:params :token]] :can-enter [:probe/refuse]}
                "/reset/:token")
  (rf/reg-route :probe/open {:can-enter [:probe/refuse]} "/open/:slug")
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _] nil))
  (rf.fx/reg-fx :rf.nav/replace-url {:platforms #{:server :client}} (fn [_ _] nil)))

(defn- current-route-id []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
          [:rf.runtime/routing :current :route-id]))

(defn- requested-urls
  "Dispatch `event`, returning the `:requested-url` of every `op` trace it emits."
  [op event]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::traces #(swap! traces conj %))
    (try
      (rf/dispatch-sync event)
      (finally (rf/unregister-listener! :trace ::traces)))
    (into [] (keep #(when (= op (:operation %)) (get-in % [:tags :requested-url]))) @traces)))

(def ^:private stopped-navigations
  "A navigation to `:probe/reset` through each door, with the URL its guard
  trace must carry."
  [["the URL-driven door"
    [:rf.route/handle-url-change (str "/reset/" secret "?plain=x")]
    "/reset/rf%2Fredacted?plain=rf/redacted"]
   ["the programmatic {:url} door"
    [:rf.route/navigate {:url (str "/reset/" secret "?plain=x")}]
    "/reset/rf%2Fredacted?plain=rf/redacted"]
   ["the programmatic {:to} door"
    [:rf.route/navigate {:to :probe/reset :params {:token secret}}]
    "/reset/rf%2Fredacted"]
   ["control: an undeclared path param stays readable"
    [:rf.route/handle-url-change "/open/kept-slug?plain=x"]
    "/open/kept-slug?plain=rf/redacted"]])

(defn- check-guard-traces
  "Make each stopped navigation from `start-url` and check that the `op` trace
  carries its projected URL and the route stays `start-route`."
  [op start-url start-route]
  (register-routes!)
  (rf/dispatch-sync [:rf.route/handle-url-change start-url])
  (doseq [[door event expected] stopped-navigations]
    (testing door
      (let [urls (requested-urls op event)]
        (is (= start-route (current-route-id)) "the guard holds")
        (when rf.interop/debug-enabled?
          (is (= [expected] urls)))))))

(deftest a-blocked-navigation-trace-projects-the-url-by-the-route-declaration
  (check-guard-traces :rf.route/navigation-blocked "/editor" :probe/editor))

(deftest an-entry-denied-trace-projects-the-url-by-the-route-declaration
  (check-guard-traces :rf.route/entry-denied "/home" :probe/home))
