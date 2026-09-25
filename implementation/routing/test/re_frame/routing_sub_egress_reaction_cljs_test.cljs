(ns re-frame.routing-sub-egress-reaction-cljs-test
  "The route-sub egress projection stays OUT of the reactive graph.

  With tracing on, every `:rf.sub/run` of `:rf.route/query` and
  `:rf.route/params` passes through the routing egress projector, which reads
  the frame's elision registry out of runtime-db to redact the route's
  `:sensitive` query / params. That read happens INSIDE the leaf's reaction
  compute. On a ratom substrate a capturing read there would register the whole
  runtime-db projection as an undeclared input of a leaf whose one declared
  input is `[:rf/route]`, so an unrelated runtime-db write would wake the leaf.

  The suite stands an eager consumer over both leaves (an auto-running
  `ratom/run!` reaction, the stand-in for a mounted view) under the Reagent
  adapter and pins:

    * the leaf reactions watch `[:rf/route]` and NOT the runtime-db
      projection;
    * an unrelated runtime-db write wakes neither leaf (no `:rf.sub/run`, no
      `:rf.sub/skip`);
    * a genuine route change still updates both leaves, and their
      `:rf.sub/run` traces redact the `:sensitive` slots in BOTH `:rf.sub/value`
      and `:rf.sub/prev-value`.

  The ns ends in -cljs-test so the always-on :node-test build runs it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.routing :as rf.routing]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.routing/reset-counters!}))

(def ^:private leaf-ids #{:rf.route/query :rf.route/params})

(defn- leaf-sub-traces
  "The `:rf.sub/run` / `:rf.sub/skip` traces the two route leaves emitted —
  one per reaction wake."
  [events]
  (filterv (fn [ev]
             (and (#{:rf.sub/run :rf.sub/skip} (:operation ev))
                  (contains? leaf-ids (get-in ev [:tags :rf.sub/id]))))
           events))

(defn- watches?
  "True when Reagent reaction `rx` currently lists `source` among the reactive
  sources it captured on its last run."
  [rx source]
  (boolean (some #(identical? % source) (array-seq (.-watching ^clj rx)))))

(deftest route-leaves-do-not-capture-the-runtime-db-projection
  (let [f       (rf.frame/make-anon-frame-record! {:doc "route-sub egress reaction frame"})
        traces  (atom [])
        rxs     (atom {})
        visit!  (fn [url]
                  (rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                                    {:frame f}))]
    (rf/reg-route :route.sub-egress/vault
                  {:sensitive [[:query :token] [:params :secret]]
                   :query     [:map [:token :string]]}
                  "/vault/:secret")
    (visit! "/vault/first-secret?token=first-token")
    (rf/register-listener! :trace ::leaf-traces (fn [ev] (swap! traces conj ev)))
    (let [driver (ratom/run!
                   (let [q (rf/subscribe [:rf.route/query] {:frame f})
                         p (rf/subscribe [:rf.route/params] {:frame f})]
                     (reset! rxs {:query  q
                                  :params p
                                  :route  (rf/subscribe [:rf/route] {:frame f})})
                     [@q @p]))]
      (try
        (ratom/flush!)
        (let [{:keys [query params route]} @rxs
              runtime-db                   (rf.frame/runtime-db-container f)]
          (testing "the leaves read the route in-process RAW"
            (is (= {:token "first-token"} @query))
            (is (= {:secret "first-secret"} @params)))

          (testing "each leaf watches its declared input and not the runtime-db projection"
            (is (watches? query route) ":rf.route/query watches [:rf/route]")
            (is (watches? params route) ":rf.route/params watches [:rf/route]")
            (is (not (watches? query runtime-db))
                ":rf.route/query carries no runtime-db dependency")
            (is (not (watches? params runtime-db))
                ":rf.route/params carries no runtime-db dependency"))

          (testing "an unrelated runtime-db write wakes neither leaf"
            (reset! traces [])
            (rf.frame/swap-runtime-db! f assoc ::unrelated 1)
            (ratom/flush!)
            (is (= [] (mapv :operation (leaf-sub-traces @traces)))
                "no :rf.sub/run or :rf.sub/skip from either route leaf"))

          (testing "a genuine route change updates both leaves and redacts their traces"
            (reset! traces [])
            (visit! "/vault/second-secret?token=second-token")
            (ratom/flush!)
            (is (= {:token "second-token"} @query))
            (is (= {:secret "second-secret"} @params))
            (let [runs (into {}
                             (keep (fn [ev]
                                     (when (= :rf.sub/run (:operation ev))
                                       [(get-in ev [:tags :rf.sub/id]) (:tags ev)])))
                             (leaf-sub-traces @traces))]
              (is (= #{:rf.route/query :rf.route/params} (set (keys runs)))
                  "both leaves recompute on the route change")
              (is (= rf.privacy/redacted-sentinel
                     (get-in runs [:rf.route/query :rf.sub/value :token])))
              (is (= rf.privacy/redacted-sentinel
                     (get-in runs [:rf.route/query :rf.sub/prev-value :token])))
              (is (= rf.privacy/redacted-sentinel
                     (get-in runs [:rf.route/params :rf.sub/value :secret])))
              (is (= rf.privacy/redacted-sentinel
                     (get-in runs [:rf.route/params :rf.sub/prev-value :secret])))))

          (testing "after a genuine route change the leaves still ignore unrelated runtime-db writes"
            (reset! traces [])
            (rf.frame/swap-runtime-db! f assoc ::unrelated 2)
            (ratom/flush!)
            (is (= [] (mapv :operation (leaf-sub-traces @traces))))
            (is (not (watches? query runtime-db)))
            (is (not (watches? params runtime-db)))))
        (finally
          (rf/unregister-listener! :trace ::leaf-traces)
          (ratom/dispose! driver))))))
