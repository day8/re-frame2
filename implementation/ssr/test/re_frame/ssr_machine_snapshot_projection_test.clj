(ns re-frame.ssr-machine-snapshot-projection-test
  "rf2-jm2u63 — durable machine snapshots must NOT hydrate raw classified
  `:data`.

  EP-0015 §6/§8: durable machine `:data` classification is owned by the
  machine `:data-schema` `:sensitive?` / `:large?` per-slot props, and
  hydration is a serialized-state egress boundary projected under
  `:rf.egress/ssr-hydration`. The SSR `:rf/runtime-db` payload ships
  `:rf.runtime/machines` so the client re-materialises actors — but the prior
  `project-runtime-db` copied the machines slice WHOLESALE, so a snapshot whose
  `:data-schema` marks a slot `:sensitive?` (an auth token in machine data) or
  `:large?` shipped that field RAW in the hydration blob.

  This pins the fix end-to-end on the ACTUAL SSR projection path
  (`re-frame.ssr.payload-policy/project-runtime-db` →
  `re-frame.ssr.payload-policy/build-payload`), with a real `reg-machine`
  `:data-schema` and the machines artefact loaded (so the late-bound
  `:machines/project-ssr-runtime-db` hook is bound). The schemas artefact
  provides the schema-mark walker the `reg-machine` bridge consults."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading machines publishes :machines/project-ssr-runtime-db +
            ;; the reg-machine schema-mark bridge; schemas + schemas.malli
            ;; provide the schema-mark walker the bridge consults.
            [re-frame.machines]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(def ^:private auth-id :rf.ssr-machine/auth)

(def ^:private auth-schema
  "A machine `:data-schema` with one sensitive slot, one large slot, and a
  plain sibling that must ride the hydration wire verbatim."
  [:map
   [:retries :int]
   [:token   {:sensitive? true} [:maybe :string]]
   [:blob    {:large? true}     [:maybe :string]]])

(defn- reg-auth-machine! []
  (rf/reg-machine auth-id
    {:initial     :anon
     :data        {:retries 0 :token nil :blob nil}
     :data-schema auth-schema
     :states      {:anon   {:on {:login :authed}}
                   :authed {}}}))

(defn- runtime-db-with-secret-snapshot
  "A runtime-db carrying ONE durable machine snapshot whose `:data` holds a
  live secret token + large blob + a plain sibling — the shape the SSR
  hydration payload would ship."
  []
  {:rf.runtime/machines
   {:snapshots {auth-id {:state :authed
                         :data  {:retries 2
                                 :token   "secret-jwt-snapshot"
                                 :blob    "huge-blob-value"}}}
    :system-ids {}
    :spawned    {}}})

;; ---- the leak regression (project-runtime-db) -----------------------------

(deftest sensitive-machine-data-redacted-in-hydration-projection
  (testing "a :sensitive? :data-schema slot inside a durable machine snapshot
            is redacted to :rf/redacted in the SSR :rf/runtime-db projection;
            the :large? slot elides; the plain sibling rides verbatim"
    (reg-auth-machine!)
    (let [slice    (payload-policy/project-runtime-db
                     (runtime-db-with-secret-snapshot))
          snapshot (get-in slice [:rf.runtime/machines :snapshots auth-id])]
      (is (= :rf/redacted (get-in snapshot [:data :token]))
          ":sensitive? token redacted in the hydration runtime-db slice")
      (is (contains? (get-in snapshot [:data :blob]) :rf.size/large-elided)
          ":large? blob elided to the size marker")
      (is (= 2 (get-in snapshot [:data :retries]))
          "plain sibling rides the wire verbatim")
      (is (= :authed (:state snapshot))
          ":state (durable structural fact) rides verbatim")
      (is (not (.contains (pr-str slice) "secret-jwt-snapshot"))
          "no raw token survives anywhere in the projected runtime-db slice")
      (is (not (.contains (pr-str slice) "huge-blob-value"))
          "no raw large value survives in the projected runtime-db slice"))))

(deftest full-hydration-payload-redacts-machine-snapshot-data
  (testing "the full :rf/hydration-payload's :rf/runtime-db carries the
            redacted/elided machine :data, not the raw classified fields"
    (reg-auth-machine!)
    (let [rt-slice (payload-policy/project-runtime-db
                     (runtime-db-with-secret-snapshot))
          payload  (payload-policy/build-payload
                     auth-id {:public/page :dashboard} "h1"
                     {:version 1 :runtime-db rt-slice})
          snap     (get-in payload [:rf/runtime-db :rf.runtime/machines
                                    :snapshots auth-id])]
      (is (= :rf/redacted (get-in snap [:data :token])))
      (is (contains? (get-in snap [:data :blob]) :rf.size/large-elided))
      (is (not (.contains (pr-str payload) "secret-jwt-snapshot"))
          "the hydration blob the client receives carries no raw secret")
      (is (not (.contains (pr-str payload) "huge-blob-value"))))))

(deftest schemaless-machine-snapshot-rides-verbatim
  (testing "a machine with no :data-schema marks ships its snapshot :data
            verbatim — the projection is precise, not a blanket scrub"
    (rf/reg-machine :rf.ssr-machine/plain
      {:initial :idle :data {:public "ok"} :states {:idle {}}})
    (let [rt    {:rf.runtime/machines
                 {:snapshots {:rf.ssr-machine/plain
                              {:state :idle :data {:public "ok"}}}}}
          slice (payload-policy/project-runtime-db rt)
          snap  (get-in slice [:rf.runtime/machines :snapshots
                               :rf.ssr-machine/plain])]
      (is (= "ok" (get-in snap [:data :public]))
          "unclassified machine data rides the hydration wire verbatim"))))
