(ns re-frame.ssr-runtime-db-explicit-frame-test
  "rf2-3fc89f.15 — the SSR runtime-db hydration projection must honour the
  EXPLICIT carried frame, never an ambient one.

  `re-frame.ssr.streaming/build-final-payload` carries an explicit `frame-id`
  and reads BOTH partitions of the frame-state container by it. Its app-db
  projection (`project-app-db-egress`) is seeded at that explicit target — but
  the prior `project-runtime-db` (one-arity) discarded the carried target and
  bound the projection frame ambiently via `frame/resolve-current-frame`.

  So a payload stamped `:rf/frame-id A` projected its app-db under A while its
  runtime-db (route / machine / resource-extension slices) projected under
  whatever frame happened to be ambient:

    - OUTSIDE any `rf/with-frame` the ambient is nil → `project-routing-egress`
      fails OPEN (no frame ⇒ no walk) and the classified `:current` route
      `:query` / `:params` ride the hydration blob RAW; the machines projector
      gets nil (no `:data` redaction); the resource extension hook resolves no
      frame.
    - Under a DIFFERENT ambient frame B the runtime-db projects under B's
      (wrong / absent) declarations while app-db projects under A — an
      internally inconsistent payload that can also apply another frame's
      classifications (cross-frame non-determinism).

  A P1 security boundary: the durable route query/params, machine snapshot
  `:data`, and resource-extension state are exactly the classified runtime
  facts EP-0025 / Spec 011 §Off-box redaction forbid shipping raw.

  These regressions drive the ACTUAL public streaming builder
  (`streaming/build-final-payload`) with an explicit non-default server frame,
  once with NO ambient scope and once under a MISMATCHED ambient frame, and
  prove the explicit target's classifications win for every runtime-db slice.
  The pre-existing route / machine regressions
  (`ssr_route_slice_projection_test`, `ssr_machine_snapshot_projection_test`)
  miss this because their fixtures classify + project under the SAME frame that
  is ambient, so ambient == explicit and the bug is invisible."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            ;; Loading routing / machines publishes the route classification
            ;; machinery and the `:machines/project-ssr-runtime-db` hook; the
            ;; reset fixture reloads both.
            [re-frame.machines]
            [re-frame.routing]
            [re-frame.routing.classification :as route-class]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.streaming :as streaming]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---- the target frame + its classified runtime state ----------------------
;;
;; `server-frame` (frame A) is the EXPLICIT SSR target. The fixture pins
;; `:rf/default` (frame B) as the ambient scope, so A is NEVER the ambient
;; frame inside a test body — precisely the seam the bug lives in.

(def ^:private server-frame :review/ssr-target)
(def ^:private route-id      :route/oauth-callback)
(def ^:private machine-id    :review.ssr/auth)

(def ^:private route-classification
  "OAuth-callback-shaped: `:query :token` is a live secret (SENSITIVE → redact),
  `:params :payload` a large blob (LARGE → elide), `:query :return-to` a plain
  breadcrumb (unclassified → verbatim). Projection-relative, per Spec 012."
  {:sensitive [[:query :token]]
   :large     [[:params :payload]]})

(defn- frame-a-runtime-db
  "The runtime-db value seeded into frame A's container. Carries:

    - the per-frame elision registry with (a) the route's re-rooted
      `:source :route` `:current` decls, and (b) `:source :effect` decls for a
      machine-snapshot `:data` path and an app-db `[:secret]` path;
    - the durable `:current` route slice (secret token + large blob);
    - one durable machine snapshot whose `:data` holds a secret + large blob.

  Every classified path is declared ONLY in frame A's registry — frame B
  (`:rf/default`) declares nothing, so a projection run under B leaks."
  []
  (-> (route-class/apply-route-classification
        {} (route-class/validate+extract route-id route-classification))
      (elision/apply-classification-effects
        {:sensitive [[:secret]
                     [:rf.runtime/machines :snapshots machine-id :data :token]]
         :large     [[:rf.runtime/machines :snapshots machine-id :data :blob]]})
      (assoc :rf.runtime/routing
             {:current            {:route-id  route-id
                                   :query     {:token     "secret-oauth-token"
                                               :return-to "/dashboard"}
                                   :params    {:payload "huge-callback-blob-value"}}
              :pending-navigation {:id "pn-1" :reason :can-leave}})
      (assoc :rf.runtime/machines
             {:snapshots  {machine-id {:state :authed
                                       :data  {:retries 2
                                               :token   "secret-jwt-snapshot"
                                               :blob    "huge-blob-value"}}}
              :system-ids {}
              :spawned    {}})))

(def ^:private auth-schema
  [:map [:retries :int] [:token [:maybe :string]] [:blob [:maybe :string]]])

(defn- setup-frame-a!
  "Register frame A as a server frame with a seeded app-db (a `[:secret]` slot
  classified in A's registry), register the machine so the machines hook has a
  live definition, then seed A's runtime-db with the classified slices."
  []
  (rf/reg-event :review.ssr/seed-db (fn [_ [_ db]] {:db db}))
  (rf/reg-frame server-frame
    {:doc            "explicit-frame SSR-target regression frame"
     :platform       :server
     :initial-events [[:review.ssr/seed-db {:public "ok" :secret "app-db-secret"}]]})
  (rf/reg-machine machine-id
    {:initial :anon
     :data    {:retries 0 :token nil :blob nil}
     :schemas {:data auth-schema}
     :states  {:anon {:on {:login :authed}} :authed {}}})
  (frame/swap-runtime-db! server-frame (constantly (frame-a-runtime-db))))

;; ---- AC#1: leak with NO ambient scope -------------------------------------

(deftest build-final-payload-honours-explicit-frame-outside-with-frame
  (testing "streaming/build-final-payload called OUTSIDE any rf/with-frame
            projects the runtime-db under the EXPLICIT frame A — the classified
            route :query / :params redact/elide, no raw secret survives"
    (setup-frame-a!)
    ;; Escape the fixture's `(with-frame :rf/default …)` scope: no ambient frame
    ;; at all. On the buggy one-arity projector `resolve-current-frame` → nil,
    ;; `project-routing-egress` fails OPEN, and the raw token rides the wire.
    (let [payload (binding [frame/*current-frame* nil]
                    (streaming/build-final-payload
                      server-frame "hash"
                      {:payload :rf.ssr.payload/whole-app-db}))
          current (get-in payload [:rf/runtime-db :rf.runtime/routing :current])
          snap    (get-in payload [:rf/runtime-db :rf.runtime/machines
                                   :snapshots machine-id])]
      (is (= server-frame (:rf/frame-id payload))
          "payload names frame A as its target")
      (is (= :rf/redacted (get-in current [:query :token]))
          "route-declared sensitive :query :token redacted under frame A")
      (is (contains? (get-in current [:params :payload]) :rf.size/large-elided)
          "route-declared large :params :payload elided under frame A")
      (is (= "/dashboard" (get-in current [:query :return-to]))
          "the unclassified route sibling rides verbatim")
      (is (= :rf/redacted (get-in snap [:data :token]))
          "machine snapshot :data :token redacted under frame A")
      (is (contains? (get-in snap [:data :blob]) :rf.size/large-elided)
          "machine snapshot :data :blob elided under frame A")
      (is (not (.contains (pr-str payload) "secret-oauth-token"))
          "no raw route token survives anywhere in the payload")
      (is (not (.contains (pr-str payload) "huge-callback-blob-value"))
          "no raw route blob survives")
      (is (not (.contains (pr-str payload) "secret-jwt-snapshot"))
          "no raw machine token survives")
      (is (not (.contains (pr-str payload) "huge-blob-value"))
          "no raw machine blob survives"))))

;; ---- AC#2: explicit frame A wins over a MISMATCHED ambient frame B ---------

(deftest build-final-payload-explicit-frame-wins-over-ambient
  (testing "with frame B (:rf/default) ambient and frame A passed explicitly,
            frame A's classifications win for app-db, route runtime state,
            machine snapshot :data, AND the resource-extension hook — the whole
            payload projects under ONE frame (A), never the ambient B"
    (setup-frame-a!)
    (let [captured-hook-frame (atom :unset)
          orig-hook (late-bind/get-fn :ssr/extend-runtime-db-projection)]
      (try
        ;; A stub resource-extension hook records the frame it resolves
        ;; ambiently — proving the projector reconciles the ambient scope to the
        ;; explicit target A before invoking a hook that resolves its own frame
        ;; (resources isn't on this artefact's classpath, so we stub its seam).
        (late-bind/set-fn! :ssr/extend-runtime-db-projection
                           (fn [_runtime-db]
                             (reset! captured-hook-frame (frame/resolve-current-frame))
                             {}))
        ;; Ambient is frame B (the fixture's `:rf/default` scope); we pass A.
        (is (= :rf/default (frame/resolve-current-frame))
            "sanity: the ambient frame is B (:rf/default), NOT A")
        (let [payload (streaming/build-final-payload
                        server-frame "hash"
                        {:payload :rf.ssr.payload/whole-app-db})
              current (get-in payload [:rf/runtime-db :rf.runtime/routing :current])
              snap    (get-in payload [:rf/runtime-db :rf.runtime/machines
                                       :snapshots machine-id])]
          (is (= server-frame @captured-hook-frame)
              "the resource-extension hook resolves the EXPLICIT target A, not ambient B")
          (is (= :rf/redacted (get-in payload [:rf/app-db :secret]))
              "app-db :secret redacted under frame A's declaration")
          (is (= "ok" (get-in payload [:rf/app-db :public]))
              "app-db unclassified sibling rides verbatim")
          (is (= :rf/redacted (get-in current [:query :token]))
              "route :query :token redacted under frame A, not leaked under B")
          (is (contains? (get-in current [:params :payload]) :rf.size/large-elided)
              "route :params :payload elided under frame A")
          (is (= :rf/redacted (get-in snap [:data :token]))
              "machine snapshot :data :token redacted under frame A")
          (is (contains? (get-in snap [:data :blob]) :rf.size/large-elided)
              "machine snapshot :data :blob elided under frame A")
          (is (not (.contains (pr-str payload) "secret-oauth-token")))
          (is (not (.contains (pr-str payload) "huge-callback-blob-value")))
          (is (not (.contains (pr-str payload) "secret-jwt-snapshot")))
          (is (not (.contains (pr-str payload) "huge-blob-value")))
          (is (not (.contains (pr-str payload) "app-db-secret"))
              "no raw app-db secret survives"))
        (finally
          (late-bind/set-fn! :ssr/extend-runtime-db-projection orig-hook))))))

;; ---- AC#4 (unit): explicit precedence at the projector seam ---------------

(deftest project-runtime-db-explicit-target-beats-ambient
  (testing "the two-arity project-runtime-db projects under its EXPLICIT
            frame-id argument even under a mismatched ambient frame; the
            one-arity falls back to ambient only when no target is carried"
    (setup-frame-a!)
    (let [rt (frame/frame-runtime-db-value server-frame)]
      ;; Ambient is B (:rf/default, no route decls). Explicit A ⇒ redacted.
      (let [slice   (payload-policy/project-runtime-db rt server-frame)
            current (get-in slice [:rf.runtime/routing :current])]
        (is (= :rf/redacted (get-in current [:query :token]))
            "explicit A wins: route token redacted despite ambient B")
        (is (not (.contains (pr-str slice) "secret-oauth-token"))))
      ;; One-arity resolves ambient B (no decls) ⇒ the route slice fails open.
      ;; This documents the sanctioned ambient-fallback path (a caller inside a
      ;; MATCHING with-frame gets correct behaviour); the security-critical
      ;; builders always pass the explicit target.
      (let [slice   (payload-policy/project-runtime-db rt)
            current (get-in slice [:rf.runtime/routing :current])]
        (is (= "secret-oauth-token" (get-in current [:query :token]))
            "one-arity under ambient B applies B's (absent) policy — the
             documented fallback; explicit target is required for correctness")))))
