(ns re-frame.ssr-render-state-jvm-test
  "rf2-8arzr.3 — the JVM-only half of the render-state contract's tests.
  The shared corpus, the policy and the restore door are pinned on both
  hosts in `re-frame.ssr.render-state-cljs-test`; this namespace pins what
  only the JVM can:

  - A REAL route slice: `reg-route` with a `:sensitive [[:query :token]]`
    classification, a real URL-driven navigation on a server frame, then
    `project` — the `:current` slice rides with its classified query slot
    redacted, and the fresh frame's `[:rf/route]` sub (the routing
    artefact's own, re-installed by the shared fixture) reads it back after
    `restore!`.
  - The JVM-only values the wire domain refuses — a host object, a
    `java.util.Date` (`#inst` is outside the manifest's domain and so
    outside this one), a ratio, a bigint, a bigdec, a float, an integer
    past 2^53 — each fails AT PROJECTION with
    `:rf.error/ssr-render-state-invalid`, and the same key with an in-domain
    value is the control."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- thrown-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- server-frame! [id]
  (rf/make-frame {:id id :platform :server})
  id)

;; ---------------------------------------------------------------------------
;; A real route slice, classified, projected, restored, subscribed
;; ---------------------------------------------------------------------------

(deftest a-real-route-slice-projects-classified-restores-and-subscribes
  (rf/reg-route :route/article
                {:sensitive [[:query :token]]
                 :query     [:map [:token :string] [:tab :string]]}
                "/article")
  (let [sfid (server-frame! :rf.ssrrs/server-route)]
    (rf/dispatch-sync [:rf.route/handle-url-change "/article?token=secret-query-token&tab=x"]
                      {:frame sfid})
    (let [live (get-in (rf.frame/frame-runtime-db-value sfid) [:rf.runtime/routing :current])]
      (is (= :route/article (:route-id live)) "precondition: the navigation settled")
      (is (= "secret-query-token" (get-in live [:query :token]))
          "precondition: the live frame holds the raw query on the server"))
    (let [projected (rf.ssr.render-state/project sfid {:render-state {:runtime-db [:rf.runtime/routing]}})
          current   (get-in projected [:rf/runtime-db :rf.runtime/routing :current])]
      (is (= :route/article (:route-id current)))
      (is (= :rf/redacted (get-in current [:query :token]))
          "the route's projection-relative :sensitive query slot redacts — derived sensitivity")
      (is (= "x" (get-in current [:query :tab])) "the unclassified query slot rides verbatim")
      (is (= [:current] (keys (get-in projected [:rf/runtime-db :rf.runtime/routing])))
          "only the durable :current slice rides")
      (is (not (str/includes? (pr-str projected) "secret-query-token")))
      (let [cfid (server-frame! :rf.ssrrs/fresh-route)]
        (rf.ssr.render-state/restore! cfid (rf.ssr.render-state/deserialize (rf.ssr.render-state/serialize projected)))
        (is (= current (rf/subscribe-once [:rf/route] {:frame cfid}))
            "the routing artefact's own [:rf/route] sub reads the restored slice on the fresh frame")))))

;; ---------------------------------------------------------------------------
;; The JVM-only values the wire refuses, each with its control
;; ---------------------------------------------------------------------------

(deftest jvm-only-values-fail-at-projection-and-their-in-domain-twins-ride
  (let [sfid (server-frame! :rf.ssrrs/server-values)]
    (doseq [[label bad good] [["a host object"        (Object.)                 "an object, described"]
                              ["a java.util.Date"     (java.util.Date. 0)       0]
                              ["a Thread"             (Thread. "never-started") "thread-name"]
                              ["a ratio"              1/3                       0.3333333333333333]
                              ["a bigint"             9007199254740993N         9007199254740991]
                              ["a bigdec"             1.5M                      1.5]
                              ["a float"              (float 0.1)               0.1]
                              ["an integer past 2^53" 9007199254740993          9007199254740991]
                              ["an atom"              (atom 1)                  1]]]
      (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:value bad}})
      (let [data (thrown-data #(rf.ssr.render-state/project sfid {:render-state {:app-db [:value]}}))]
        (is (= :rf.error/ssr-render-state-invalid (:rf.error/id data)) label)
        (is (= :unserialisable (:invalid data)) label)
        (is (= :value (:key data)) label)
        (is (= :value (:half data)) label))
      ;; CONTROL — the in-domain twin under the same key rides and round-trips.
      (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:value good}})
      (let [projected (rf.ssr.render-state/project sfid {:render-state {:app-db [:value]}})]
        (is (= {:rf/app-db {:value good} :rf/runtime-db {}} projected) label)
        (is (= projected (rf.ssr.render-state/deserialize (rf.ssr.render-state/serialize projected))) label)))))
