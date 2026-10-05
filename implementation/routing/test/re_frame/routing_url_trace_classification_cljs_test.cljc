(ns re-frame.routing-url-trace-classification-cljs-test
  "A route's `:sensitive` declaration reaches the URL STRINGS and navigation
  REQUESTS a navigation puts on the trace bus: the `:url` of the
  `:rf.route/planned` trace, the URL argument of the
  `[:rf.route/handle-url-change url …]` event vector, and the request of the
  `[:rf.route/navigate {request}]` / `[:rf.route/url-requested {request}]`
  event vectors on every event-bearing trace. A request's `:url` projects as a
  URL; the `:params` / `:query` of a `:to` request carry the `:rf/redacted`
  sentinel for each declared value.

  A declared path param's segment and a declared query key's value are
  replaced by the `rf/redacted` sentinel string (percent-encoded in a path
  segment, so the URL keeps its segment structure). Undeclared segments,
  query keys and values ride verbatim on the event vector; `:rf.route/planned`
  additionally scrubs every query value and the fragment, as it does for any
  route. The in-process value stays raw: the slice and the dispatched event
  are untouched, and only the egress projection redacts.

  Every assertion read off the trace bus sits inside a
  `(when rf.interop/debug-enabled? …)` arm: `trace/emit!` is dev
  instrumentation, and the negative census assertions would pass vacuously
  with no trace to read.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex, Shadow's `:node-test` build via the `cljs-test$` regex,
  so the URL projector's span arithmetic is graded on both hosts."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   [re-frame.interop :as rf.interop]
   [re-frame.routing :as rf.routing]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

(def ^:private param-secret "p2p7fo-param-secret")
(def ^:private query-secret "p2p7fo-query-secret")

(defn- reg-routes!
  "`:route/acct` declares its `:secret` path param and its `:token` query key
  sensitive; its `:other` param and `:page` query key are undeclared.
  `:route/plain` declares nothing."
  []
  (rf/reg-route :route/acct
                {:sensitive [[:params :secret] [:query :token]]
                 :query     [:map [:token :string] [:page {:optional true} :string]]}
                "/acct/:secret/:other")
  (rf/reg-route :route/plain {} "/plain/:id"))

(defn- capture-traces
  "Run `f` and return every trace event emitted meanwhile, always unregistering
  the listener."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try
      (f)
      @captured
      (finally
        (rf/unregister-listener! :trace ::capture)))))

(defn- planned-url
  "The `:url` of the single `:rf.route/planned` trace in `events`."
  [events]
  (let [planned (filterv #(= :rf.route/planned (:operation %)) events)]
    (is (= 1 (count planned)) "one plan per door commit")
    (get-in (first planned) [:tags :url])))

(defn- url-change-urls
  "The URL argument of every `:rf.event/v` carrying a URL-change event."
  [events]
  (into []
        (keep (fn [ev]
                (let [v (get-in ev [:tags :rf.event/v])]
                  (when (and (vector? v) (= :rf.route/handle-url-change (first v)))
                    (second v)))))
        events))

(defn- visit!
  [url]
  (capture-traces
    #(rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}])))

(deftest a-url-change-redacts-the-declared-path-param-and-query-value
  (reg-routes!)
  (let [url    (str "/acct/" param-secret "/visible?token=" query-secret "&page=2")
        events (visit! url)
        route  @(rf/subscribe [:rf/route])]
    (testing "the in-process slice stays raw"
      (is (= :route/acct (:route-id route)))
      (is (= param-secret (get-in route [:params :secret])))
      (is (= query-secret (get-in route [:query :token]))))
    (when rf.interop/debug-enabled?
      (testing "the URL-change event vector: declared parts redact, undeclared ride"
        (let [urls (url-change-urls events)]
          (is (seq urls) "control: the window carries the URL-change event")
          (is (every? #{"/acct/rf%2Fredacted/visible?token=rf/redacted&page=2"} urls))))
      (testing ":rf.route/planned's :url: the declared path segment redacts too"
        (is (= "/acct/rf%2Fredacted/visible?token=rf/redacted&page=rf/redacted"
               (planned-url events))))
      (testing "no trace the navigation emits carries either secret"
        (is (not (str/includes? (pr-str events) param-secret)))
        (is (not (str/includes? (pr-str events) query-secret)))))))

(deftest a-programmatic-navigation-plans-a-redacted-url
  (testing "the programmatic door's planned :url is built by route-url and is
            projected by the same declaration"
    (reg-routes!)
    (let [events (capture-traces
                   #(rf/dispatch-sync [:rf.route/navigate {:to     :route/acct
                                                           :params {:secret param-secret :other "visible"}
                                                           :query  {:token query-secret}}]))]
      (is (= param-secret (get-in @(rf/subscribe [:rf/route]) [:params :secret]))
          "the in-process slice stays raw")
      (when rf.interop/debug-enabled?
        (is (= "/acct/rf%2Fredacted/visible?token=rf/redacted" (planned-url events)))))))

(defn- event-args
  "The first argument of every `:rf.event/v` carrying an `event-id` event."
  [event-id events]
  (into []
        (keep (fn [ev]
                (let [v (get-in ev [:tags :rf.event/v])]
                  (when (and (vector? v) (= event-id (first v)))
                    (second v)))))
        events))

(deftest a-url-request-redacts-its-routes-declared-parts
  (testing "[:rf.route/url-requested {:url …}]: the link door's request projects
            its URL, and the URL-change event it synthesises does too"
    (reg-routes!)
    (let [url    (str "/acct/" param-secret "/visible?token=" query-secret "&page=2")
          events (capture-traces #(rf/dispatch-sync [:rf.route/url-requested {:url url}]))]
      (is (= param-secret (get-in @(rf/subscribe [:rf/route]) [:params :secret]))
          "the in-process slice stays raw")
      (when rf.interop/debug-enabled?
        (let [requests (event-args :rf.route/url-requested events)]
          (is (seq requests))
          (is (every? #{{:url "/acct/rf%2Fredacted/visible?token=rf/redacted&page=2"}}
                      requests)))
        (let [urls (url-change-urls events)]
          (is (seq urls) "control: the link door synthesised the URL-change event")
          (is (every? #{"/acct/rf%2Fredacted/visible?token=rf/redacted&page=2"} urls)))))))

(deftest an-in-place-request-rides-verbatim-because-it-names-no-route
  (testing "an in-place request edits its frame's CURRENT route, and the
            event-vector projection sees the request but never the frame the
            event targets, so it has no route to read a declaration off: a
            declared query value rides verbatim (Spec 012 §Lowering and
            re-rooting). A :to request naming that same route redacts it."
    (reg-routes!)
    (visit! (str "/acct/" param-secret "/visible?token=before&page=1"))
    (let [in-place {:query-merge {:token query-secret :page "2"}}
          events   (capture-traces #(rf/dispatch-sync [:rf.route/navigate in-place]))]
      (is (= query-secret (get-in @(rf/subscribe [:rf/route]) [:query :token]))
          "the in-place edit landed: the slice holds the merged value, raw")
      (when rf.interop/debug-enabled?
        (let [requests (event-args :rf.route/navigate events)]
          (is (seq requests) "control: the window carries the navigate event")
          (is (every? #(identical? in-place %) requests)
              "the request rides verbatim, its declared query value included: no route, no declaration"))))
    (testing "control: the same key, on the same route, named with :to, redacts"
      (let [named  {:to     :route/acct
                    :params {:secret param-secret :other "visible"}
                    :query  {:token query-secret :page "3"}}
            events (capture-traces #(rf/dispatch-sync [:rf.route/navigate named]))]
        (when rf.interop/debug-enabled?
          (let [requests (event-args :rf.route/navigate events)]
            (is (seq requests))
            (is (every? #{{:to     :route/acct
                           :params {:secret :rf/redacted :other "visible"}
                           :query  {:token :rf/redacted :page "3"}}}
                        requests))))))))

(deftest a-navigate-request-to-an-undeclared-route-rides-verbatim
  (testing "control: a route declaring nothing leaves the request as it was"
    (reg-routes!)
    (let [request {:to :route/plain :params {:id "abc"} :query {:x "1"}}
          events  (capture-traces #(rf/dispatch-sync [:rf.route/navigate request]))]
      (when rf.interop/debug-enabled?
        (let [requests (event-args :rf.route/navigate events)]
          (is (seq requests))
          (is (every? #(identical? request %) requests)))))))

(deftest a-route-declaring-nothing-keeps-its-url-verbatim
  (testing "control: an undeclared route's URL rides the event vector verbatim,
            and :rf.route/planned applies only its usual query scrub"
    (reg-routes!)
    (let [url    "/plain/abc?x=1"
          events (visit! url)]
      (is (= "abc" (get-in @(rf/subscribe [:rf/route]) [:params :id])))
      (when rf.interop/debug-enabled?
        (let [urls (url-change-urls events)]
          (is (seq urls))
          (is (every? #(identical? url %) urls)))
        (is (= "/plain/abc?x=rf/redacted" (planned-url events)))))))

(deftest a-url-matching-no-route-rides-verbatim
  (testing "control: a route miss has no declaration to apply"
    (reg-routes!)
    (let [url    (str "/nowhere/" param-secret)
          events (visit! url)]
      (when rf.interop/debug-enabled?
        (let [urls (url-change-urls events)]
          (is (seq urls) "control: the window carries the URL-change event")
          (is (every? #(= url %) urls)))))))
