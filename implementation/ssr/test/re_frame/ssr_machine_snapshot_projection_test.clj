(ns re-frame.ssr-machine-snapshot-projection-test
  "rf2-jm2u63 — durable machine snapshots must NOT hydrate raw classified
  `:data`.

  EP-0025 (rf2-398kql): durable machine `:data` egress classification is
  FRAME-OWNED — the frame classifies the machine snapshot's `:data` path
  sensitive / large by its absolute runtime-db path
  `[:rf.runtime/machines :snapshots <actor-id> :data …]`, the sole app-db
  mechanism (post-purge: a B3 commit-plane `:sensitive` / `:large` effect — see
  the follow-on note below). Hydration is a serialized-state egress boundary
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
  `:data-schema`→marks SSR-classification bridge; classification is frame-side.)

  EP-0025 B4-ssr follow-on (rf2-ux7983): the snapshot `:data` path is classified
  through the post-purge mechanism — a B3 COMMIT-PLANE `:sensitive` / `:large`
  effect returned by a `reg-event` handler alongside `:db` (EP-0025 §How it
  works). It writes the absolute runtime-db snapshot path into the SAME per-frame
  `[:rf.runtime/elision]` registry the `:rf.egress/ssr-hydration` egress walk
  reads (tagged `:source :effect`, unioned at egress-lookup), so the SSR
  projection redacts/elides it exactly as any classified declaration would.
  This replaces BOTH the retired durable `:sensitive`/`:large {:app-db …}`
  frame annotation and the imperative `marks/add-marks` API (deleted by the
  EP-0025 purge); the classification is read ONLY at egress, so it redacts
  whatever value later occupies the path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loading machines publishes :machines/project-ssr-runtime-db.
            [re-frame.machines]
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

;; Classify the snapshot `:data` token slot SENSITIVE and blob slot LARGE on the
;; ambient `:rf/default` frame via a B3 COMMIT-PLANE classification effect
;; (EP-0025 §How it works) — a `reg-event` handler returning `:sensitive` /
;; `:large` alongside `:db`. The effect writes the absolute runtime-db snapshot
;; path into the per-frame `[:rf.runtime/elision]` registry (tagged
;; `:source :effect`), the SAME slot the `:rf.egress/ssr-hydration` egress walk
;; reads — value-independent, read only at egress. This is the post-purge app-db
;; classification mechanism (the imperative `marks/add-marks` API was retired by
;; the EP-0025 B1b purge).

(def ^:private classify-event :rf.ssr-machine/classify-snapshot)

(defn- reg-classify-event! []
  (rf/reg-event classify-event
    (fn [_ _]
      ;; No `:db` change — classification is value-independent; we mark the
      ;; absolute runtime-db snapshot paths BEFORE the projection reads them.
      {:sensitive [[:rf.runtime/machines :snapshots auth-id :data :token]]
       :large     [[:rf.runtime/machines :snapshots auth-id :data :blob]]})))

(defn- declare-frame-marks!
  "Classify the machine snapshot's `:data` token slot SENSITIVE and blob slot
  LARGE on the ambient `:rf/default` frame by its absolute runtime-db snapshot
  path, through a B3 commit-plane classification effect (the post-purge
  frame-owned app-db mechanism)."
  []
  (reg-classify-event!)
  (rf/dispatch-sync [classify-event]))

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
