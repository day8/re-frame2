(ns re-frame.ssr-machine-snapshot-projection-test
  "rf2-jm2u63 — durable machine snapshots must NOT hydrate raw classified
  `:data`.

  EP-0025 (rf2-398kql): durable machine `:data` egress classification is
  FRAME-OWNED — the frame declares the machine snapshot's `:data` path sensitive
  / large via `reg-frame` `:sensitive` / `:large {:app-db …}` (the absolute
  runtime-db path `[:rf.runtime/machines :snapshots <actor-id> :data …]`, the
  sole app-db mechanism). Hydration is a serialized-state egress boundary
  projected under `:rf.egress/ssr-hydration`. The SSR `:rf/runtime-db` payload
  ships `:rf.runtime/machines` so the client re-materialises actors — but the
  prior `project-runtime-db` copied the machines slice WHOLESALE, so a snapshot
  whose `:data` the frame classifies sensitive/large shipped that field RAW.

  This pins the fix end-to-end on the ACTUAL SSR projection path
  (`re-frame.ssr.payload-policy/project-runtime-db` →
  `re-frame.ssr.payload-policy/build-payload`), with a real `reg-machine` (whose
  `:data-schema` still VALIDATES `:data`) and a FRAME-declared classification of
  the snapshot `:data` path; the machines artefact is loaded so the late-bound
  `:machines/project-ssr-runtime-db` hook is bound. (EP-0025 removed the EP-0005
  `:data-schema`→marks SSR-classification bridge; classification is frame-side.)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading machines publishes :machines/project-ssr-runtime-db.
            [re-frame.machines]
            ;; marks/add-marks declares the frame-owned snapshot classification.
            [re-frame.marks :as marks]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(def ^:private auth-id :rf.ssr-machine/auth)

(def ^:private auth-schema
  "A machine `:data-schema` — VALIDATION ONLY (EP-0025: props no longer
  classify). The frame, not the schema, classifies the snapshot `:data` path."
  [:map
   [:retries :int]
   [:token   [:maybe :string]]
   [:blob    [:maybe :string]]])

(defn- reg-auth-machine! []
  (rf/reg-machine auth-id
    {:initial     :anon
     :data        {:retries 0 :token nil :blob nil}
     :data-schema auth-schema
     :states      {:anon   {:on {:login :authed}}
                   :authed {}}}))

(defn- declare-frame-marks!
  "Declare the machine snapshot's `:data` token slot SENSITIVE and blob slot
  LARGE on the ambient `:rf/default` frame (the frame-owned classification, the
  sole app-db mechanism) by its absolute runtime-db snapshot path."
  []
  (marks/add-marks :rf/default
    {[:rf.runtime/machines :snapshots auth-id :data :token] :sensitive
     [:rf.runtime/machines :snapshots auth-id :data :blob]  :large}))

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
  (testing "a frame-declared sensitive :data path inside a durable machine
            snapshot is redacted to :rf/redacted in the SSR :rf/runtime-db
            projection; the large path elides; the plain sibling rides verbatim"
    (reg-auth-machine!)
    (declare-frame-marks!)
    (let [slice    (payload-policy/project-runtime-db
                     (runtime-db-with-secret-snapshot))
          snapshot (get-in slice [:rf.runtime/machines :snapshots auth-id])]
      (is (= :rf/redacted (get-in snapshot [:data :token]))
          "frame-declared token redacted in the hydration runtime-db slice")
      (is (contains? (get-in snapshot [:data :blob]) :rf.size/large-elided)
          "frame-declared blob elided to the size marker")
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
    (declare-frame-marks!)
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

(deftest undeclared-machine-snapshot-rides-verbatim
  (testing "a machine whose frame declares nothing ships its snapshot :data
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
