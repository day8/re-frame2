(ns re-frame.routing-sub-prev-value-classification-test
  "A held route read sub's `:rf.sub/prev-value` keeps the classification of
  the route it was computed under.

  A route's `:sensitive` / `:large` claims are lowered into the frame's elision
  registry while the route is active, and a navigation REPLACES them with the
  entering route's. So when a held `[:rf/route]` / `[:rf.route/params]` /
  `[:rf.route/query]` sub recomputes after a navigation, its `:rf.sub/run`
  trace carries the leaving route's slice as `:rf.sub/prev-value` while the
  registry already holds the entering route's claims. That prior value is
  redacted by the route it belongs to, so a secret declared by the leaving
  route never reaches the trace.

  The in-process reads stay raw: classification applies at egress only.

  Every assertion read off the trace bus sits inside a
  `(when rf.interop/debug-enabled? …)` arm: `trace/emit!` is dev
  instrumentation, and the negative `(not (.contains …))` assertions would pass
  vacuously with no trace to read."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.interop :as rf.interop]
            [re-frame.privacy :as rf.privacy]
            [re-frame.routing]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(def ^:private param-secret "kuyza-param-secret")
(def ^:private query-secret "kuyza-query-secret")

(def ^:private secret-url (str "/secret/" param-secret "?token=" query-secret))

(def ^:private classified-subs
  "The route read subs whose values project the classified route slice."
  [[:rf/route] [:rf.route/params] [:rf.route/query]])

(def ^:private other-subs
  "The remaining route read subs. They carry no classifiable slot, and are held
  so that the whole-window census covers their prior values too."
  [[:rf.route/id] [:rf.route/transition] [:rf.route/error]
   [:rf.route/fragment] [:rf.route/chain] [:rf/pending-navigation]])

(defn- reg-routes!
  "`:route/secret` declares its `:secret` path param and its `:token` query key
  sensitive (the `:query` schema promotes `:token` to a keyword slot);
  `:route/whole` declares its whole projection sensitive; `:route/plain`
  declares nothing."
  []
  (rf/reg-route :route/secret
                {:sensitive [[:params :secret] [:query :token]]
                 :query     [:map [:token :string]]}
                "/secret/:secret")
  (rf/reg-route :route/whole {:sensitive [[]]} "/whole/:secret")
  (rf/reg-route :route/plain {} "/plain"))

(defn- visit!
  [url]
  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]))

(defn- route-claims
  "The frame's `:sensitive` declarations under the route slice."
  []
  (filterv #(= [:rf.runtime/routing :current] (subvec (vec %) 0 (min 2 (count %))))
           (keys (rf.elision/sensitive-declarations :rf/default))))

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

(defn- run-tags
  "The tags of the single `:rf.sub/run` trace for `query-v` in `events`."
  [query-v events]
  (let [runs (filterv #(and (= :rf.sub/run (:operation %))
                            (= query-v (get-in % [:tags :rf.sub/query-v])))
                      events)]
    (is (= 1 (count runs)) (str "one recompute of " query-v " across the navigation"))
    (:tags (first runs))))

(defn- held-across
  "Visit `from`, hold and deref every route read sub, then visit `to` and deref
  them again inside a trace capture. Returns the reads either side, the
  captured events, and the route claims either side."
  [from to]
  (visit! from)
  (let [held    (into {} (map (fn [qv] [qv (rf/subscribe qv)])) (concat classified-subs other-subs))
        read!   #(into {} (map (fn [[qv r]] [qv @r])) held)
        before  (read!)
        claims  (route-claims)
        after   (atom nil)
        events  (capture-traces (fn [] (visit! to) (reset! after (read!))))]
    {:before        before
     :after         @after
     :events        events
     :claims-before claims
     :claims-after  (route-claims)}))

(deftest leaving-a-sensitive-route-keeps-the-held-subs-prev-values-classified
  (reg-routes!)
  (let [{:keys [before after events claims-before claims-after]}
        (held-across secret-url "/plain")]
    (testing "the in-process reads stay raw"
      (is (= param-secret (get-in before [[:rf/route] :params :secret])))
      (is (= query-secret (get-in before [[:rf/route] :query :token])))
      (is (= param-secret (:secret (get before [:rf.route/params]))))
      (is (= query-secret (:token (get before [:rf.route/query]))))
      (is (= :route/plain (get-in after [[:rf/route] :route-id]))))
    (testing "the navigation drops the leaving route's claims from the registry"
      (is (seq claims-before) "control: the sensitive route's claims are in the registry")
      (is (empty? claims-after)))
    (when rf.interop/debug-enabled?
      (testing "[:rf/route]: the prior slice keeps its route's classification"
        (let [prev (:rf.sub/prev-value (run-tags [:rf/route] events))]
          (is (= :route/secret (:route-id prev)) "an unclassified slot rides verbatim")
          (is (= rf.privacy/redacted-sentinel (get-in prev [:params :secret])))
          (is (= rf.privacy/redacted-sentinel (get-in prev [:query :token])))))
      (testing "[:rf.route/params]: the prior params keep the leaving route's classification"
        (let [prev (:rf.sub/prev-value (run-tags [:rf.route/params] events))]
          (is (= rf.privacy/redacted-sentinel (:secret prev)))))
      (testing "[:rf.route/query]: the prior query keeps the leaving route's classification"
        (let [prev (:rf.sub/prev-value (run-tags [:rf.route/query] events))]
          (is (= rf.privacy/redacted-sentinel (:token prev)))))
      (testing "no secret appears on any trace the navigation emits"
        (is (not (.contains (pr-str events) param-secret)))
        (is (not (.contains (pr-str events) query-secret)))))))

(deftest leaving-a-whole-projection-route-keeps-the-held-subs-prev-values-classified
  (testing "a `[]` declaration covers the whole slice, so it governs each
            projection of it — including the params map, whose storage
            position sits below the declared one"
    (reg-routes!)
    (let [{:keys [events claims-before claims-after]}
          (held-across (str "/whole/" param-secret) "/plain")]
      (is (seq claims-before) "control: the whole-projection claim is in the registry")
      (is (empty? claims-after))
      (when rf.interop/debug-enabled?
        (is (= rf.privacy/redacted-sentinel
               (:rf.sub/prev-value (run-tags [:rf/route] events))))
        (is (= rf.privacy/redacted-sentinel
               (:rf.sub/prev-value (run-tags [:rf.route/params] events))))
        (is (not (.contains (pr-str events) param-secret))
            "no secret appears on any trace the navigation emits")))))

(def ^:private audit-secret "audit-prior-route-private-953")

(defn- held-across-re-registration
  "Visit `:audit/secret` with a secret param, hold and deref `[:rf/route]` and
  `[:rf.route/params]`, re-register `:audit/secret` with no declaration at the
  same pattern, then navigate to `/plain` and deref both inside a trace
  capture."
  []
  (rf/reg-route :audit/secret {:sensitive [[:params :secret]]} "/secret/:secret")
  (rf/reg-route :audit/plain {} "/plain")
  (visit! (str "/secret/" audit-secret))
  (let [held   (into {} (map (fn [qv] [qv (rf/subscribe qv)])) [[:rf/route] [:rf.route/params]])
        read!  #(into {} (map (fn [[qv r]] [qv @r])) held)
        before (read!)
        _      (rf/reg-route :audit/secret {} "/secret/:secret")
        after  (atom nil)
        events (capture-traces (fn [] (visit! "/plain") (reset! after (read!))))]
    {:before before :after @after :events events}))

(deftest re-registering-the-leaving-route-keeps-the-held-subs-prev-values-classified
  (testing "the prior value is classified by the declaration it was computed
            under, so re-registering the route without it before navigating
            away does not declassify it"
    (let [{:keys [before after events]} (held-across-re-registration)]
      (is (= audit-secret (get-in before [[:rf/route] :params :secret]))
          "the in-process read stays raw")
      (is (= :audit/plain (get-in after [[:rf/route] :route-id])))
      (when rf.interop/debug-enabled?
        (is (= rf.privacy/redacted-sentinel
               (get-in (run-tags [:rf/route] events) [:rf.sub/prev-value :params :secret])))
        (is (= rf.privacy/redacted-sentinel
               (get-in (run-tags [:rf.route/params] events) [:rf.sub/prev-value :secret])))
        (is (not (.contains (pr-str events) audit-secret))
            "no secret appears on any trace the navigation emits")))))

(deftest entering-a-sensitive-route-classifies-the-new-value
  (testing "control, the reverse direction: the prior value is the plain
            slice and rides verbatim, while the new value is classified by the
            registry the entering route installed"
    (reg-routes!)
    (let [{:keys [after events claims-after]} (held-across "/plain" secret-url)]
      (is (seq claims-after) "the entering route's claims are in the registry")
      (is (= param-secret (get-in after [[:rf/route] :params :secret]))
          "the in-process read stays raw")
      (when rf.interop/debug-enabled?
        (let [route  (run-tags [:rf/route] events)
              params (run-tags [:rf.route/params] events)
              query  (run-tags [:rf.route/query] events)]
          (is (= :route/plain (get-in route [:rf.sub/prev-value :route-id])))
          (is (= {} (:rf.sub/prev-value params)))
          (is (= rf.privacy/redacted-sentinel (get-in route [:rf.sub/value :params :secret])))
          (is (= rf.privacy/redacted-sentinel (get-in route [:rf.sub/value :query :token])))
          (is (= rf.privacy/redacted-sentinel (get-in params [:rf.sub/value :secret])))
          (is (= rf.privacy/redacted-sentinel (get-in query [:rf.sub/value :token]))))
        ;; The window dispatches the secret URL itself, so the census is scoped
        ;; to the sub runs: the URL-change event carries the URL it was given.
        (let [runs (filterv #(= :rf.sub/run (:operation %)) events)]
          (is (seq runs))
          (is (not (.contains (pr-str runs) param-secret)))
          (is (not (.contains (pr-str runs) query-secret))))))))
