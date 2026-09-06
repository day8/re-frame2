(ns re-frame.ssr-runtime-db-explicit-frame-test
  "rf2-3fc89f.15 / rf2-f02diw — the SSR runtime-db hydration projection must
  honour the EXPLICIT carried frame, never an ambient one. rf2-f02diw finishes
  the clean break: the `:ssr/extend-runtime-db-projection` resource hook is
  re-signatured `[runtime-db frame-id]` and the projector THREADS the target
  into it as an argument rather than a `binding` rebind of ambient scope — the
  `build-final-payload-explicit-frame-wins-over-ambient` stub below asserts the
  hook receives A as its parameter while ambient stays B.

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
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading routing / machines publishes the route classification
            ;; machinery and the `:machines/project-ssr-runtime-db` hook; the
            ;; reset fixture reloads both.
            [re-frame.machines]
            [re-frame.routing]
            [re-frame.routing.classification :as rf.routing.classification]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.streaming :as rf.ssr.streaming]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

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
  (-> (rf.routing.classification/apply-route-classification
        {} (rf.routing.classification/validate+extract route-id route-classification))
      (rf.elision/apply-classification-effects
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
  (rf/make-frame {:id server-frame :doc            "explicit-frame SSR-target regression frame"
                  :platform       :server
                  :initial-events [[:review.ssr/seed-db {:public "ok" :secret "app-db-secret"}]]})
  (rf/reg-machine machine-id
    {:initial :anon
     :data    {:retries 0 :token nil :blob nil}
     :schemas {:data auth-schema}
     :states  {:anon {:on {:login :authed}} :authed {}}})
  (rf.frame/swap-runtime-db! server-frame (constantly (frame-a-runtime-db))))

;; ---- AC#1: leak with NO ambient scope -------------------------------------

(deftest build-final-payload-honours-explicit-frame-outside-with-frame
  (testing "streaming/build-final-payload called OUTSIDE any rf/with-frame
            projects the runtime-db under the EXPLICIT frame A — the classified
            route :query / :params redact/elide, no raw secret survives"
    (setup-frame-a!)
    ;; Escape the fixture's `(with-frame :rf/default …)` scope: no ambient frame
    ;; at all. On the buggy one-arity projector `resolve-current-frame` → nil,
    ;; `project-routing-egress` fails OPEN, and the raw token rides the wire.
    (let [payload (binding [rf.frame/*current-frame* nil]
                    (rf.ssr.streaming/build-final-payload
                      server-frame "hash"
                      {:payload :rf.ssr.payload/whole-app-db}))
          current (get-in payload [:rf/runtime-db :rf.runtime/routing :current])
          snap    (get-in payload [:rf/runtime-db :rf.runtime/machines
                                   :snapshots machine-id])]
      ;; rf2-lm2yzy — wire :rf/frame-id decoupled from the projection frame;
      ;; no `:client-frame-id` opt ⇒ omitted. Redaction below proves the
      ;; projection targeted the explicit frame A.
      (is (not (contains? payload :rf/frame-id))
          "anonymous per-request frame omits the wire :rf/frame-id")
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
    (let [captured-hook-frame   (atom :unset)
          captured-hook-ambient (atom :unset)
          orig-hook (rf.late-bind/get-fn :ssr/extend-runtime-db-projection)]
      (try
        ;; A stub resource-extension hook records BOTH the frame-id PARAMETER it
        ;; is threaded AND the ambient scope in effect when it runs — proving the
        ;; projector passes the explicit target A as an ARGUMENT (rf2-f02diw
        ;; clean break: the hook is `[runtime-db frame-id]`), never via a
        ;; borrowed ambient rebind (resources isn't on this artefact's classpath,
        ;; so we stub its seam). A one-arity stub here would ARITY-ERROR against
        ;; the consumer's `(extend-fn runtime-db frame-id)` — the two args ARE
        ;; the proof the frame is threaded.
        (rf.late-bind/set-fn! :ssr/extend-runtime-db-projection
                           (fn [_runtime-db frame-id]
                             (reset! captured-hook-frame frame-id)
                             (reset! captured-hook-ambient (rf.frame/resolve-current-frame))
                             {}))
        ;; Ambient is frame B (the fixture's `:rf/default` scope); we pass A.
        (is (= :rf/default (rf.frame/resolve-current-frame))
            "sanity: the ambient frame is B (:rf/default), NOT A")
        (let [payload (rf.ssr.streaming/build-final-payload
                        server-frame "hash"
                        {:payload :rf.ssr.payload/whole-app-db})
              current (get-in payload [:rf/runtime-db :rf.runtime/routing :current])
              snap    (get-in payload [:rf/runtime-db :rf.runtime/machines
                                       :snapshots machine-id])]
          (is (= server-frame @captured-hook-frame)
              "the resource-extension hook is THREADED the EXPLICIT target A as
               its frame-id argument (rf2-f02diw), not ambient B")
          (is (= :rf/default @captured-hook-ambient)
              "the hook's ambient scope is UNTOUCHED (still B :rf/default) — the
               explicit frame arrives as a PARAMETER, not a borrowed ambient
               rebind: the clean break removed the `binding` around the call")
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
          (rf.late-bind/set-fn! :ssr/extend-runtime-db-projection orig-hook))))))

;; ---- AC#4 (unit): explicit precedence at the projector seam ---------------

(deftest project-runtime-db-explicit-target-beats-ambient
  (testing "the two-arity project-runtime-db projects under its EXPLICIT
            frame-id argument even under a mismatched ambient frame; the
            one-arity falls back to ambient only when no target is carried"
    (setup-frame-a!)
    (let [rt (rf.frame/frame-runtime-db-value server-frame)]
      ;; Ambient is B (:rf/default, no route decls). Explicit A ⇒ redacted.
      (let [slice   (rf.ssr.payload-policy/project-runtime-db rt server-frame)
            current (get-in slice [:rf.runtime/routing :current])]
        (is (= :rf/redacted (get-in current [:query :token]))
            "explicit A wins: route token redacted despite ambient B")
        (is (not (.contains (pr-str slice) "secret-oauth-token"))))
      ;; One-arity resolves ambient B (no decls) ⇒ the route slice fails open.
      ;; This documents the sanctioned ambient-fallback path (a caller inside a
      ;; MATCHING with-frame gets correct behaviour); the security-critical
      ;; builders always pass the explicit target.
      (let [slice   (rf.ssr.payload-policy/project-runtime-db rt)
            current (get-in slice [:rf.runtime/routing :current])]
        (is (= "secret-oauth-token" (get-in current [:query :token]))
            "one-arity under ambient B applies B's (absent) policy — the
             documented fallback; explicit target is required for correctness")))))

;; ===========================================================================
;; rf2-j538f7.15 — fail closed when the payload projection loses its frame
;; during teardown.
;;
;; The explicit-frame projectors above assume the carried frame stays LIVE
;; through payload assembly. An async host (disconnect / timeout / writer-error
;; / cancellation cleanup) can destroy — or destroy AND re-register under the
;; same id — the request frame between the caller's state CAPTURE and the
;; PROJECTION. The prior `project-routing-egress` (and the machines / resource
;; hooks) rode the captured, still-classified state VERBATIM whenever the frame
;; was no longer live: the payload was stamped for A yet its policy came from no
;; live frame, so the classified route :query / machine :data rode RAW. These
;; regressions prove the projection now fails CLOSED on a lost / substituted
;; frame while a LIVE frame with no declarations still ships verbatim.
;; ===========================================================================

(deftest project-routing-egress-fails-closed-on-destroyed-frame
  (testing "rf2-j538f7.15 — the route :current slice captured while frame A was
            LIVE fails closed once A is destroyed, and is distinguished from the
            live-frame precise projection and the frameless-nil passthrough"
    (setup-frame-a!)
    (let [slice (select-keys (:rf.runtime/routing (rf.frame/frame-runtime-db-value server-frame))
                             [:current])]
      ;; Acceptance #6, first clause: a LIVE frame with declarations projects
      ;; PRECISELY — the sensitive token redacts, the unclassified sibling rides.
      (let [live (rf.ssr.payload-policy/project-routing-egress slice server-frame)]
        (is (= :rf/redacted (get-in live [:current :query :token]))
            "LIVE frame A: declared sensitive :query :token redacts precisely")
        (is (= "/dashboard" (get-in live [:current :query :return-to]))
            "LIVE frame A: unclassified sibling rides verbatim"))
      ;; Acceptance #6, frameless clause: a NIL frame-id is the frameless
      ;; convenience — no frame policy to lose, so the slice rides verbatim.
      (is (= slice (rf.ssr.payload-policy/project-routing-egress slice nil))
          "NIL frame: frameless passthrough (NOT fail-closed)")
      ;; The teardown race: destroy A, then re-project the ALREADY-captured slice
      ;; under the same EXPLICIT id. Acceptance #1 — fail closed.
      (rf/destroy-frame! server-frame)
      (let [dead (rf.ssr.payload-policy/project-routing-egress slice server-frame)]
        (is (= :rf/redacted dead)
            "DESTROYED explicit frame: the whole :current slice fails closed")
        (is (not (.contains (pr-str dead) "secret-oauth-token"))
            "no raw route token survives the fail-closed projection")))))

(deftest project-runtime-db-fails-closed-on-destroyed-frame
  (testing "rf2-j538f7.15 (acceptance #4) — project-runtime-db on a frame
            destroyed after runtime-db capture fails closed for BOTH the machine
            snapshot :data and the routing :current slice; no raw classified
            value survives, while a LIVE frame still projects them precisely"
    (setup-frame-a!)
    (let [rt (rf.frame/frame-runtime-db-value server-frame)]
      ;; sanity — LIVE A projects machine :data + route precisely
      (let [live (rf.ssr.payload-policy/project-runtime-db rt server-frame)]
        (is (= :rf/redacted (get-in live [:rf.runtime/machines :snapshots machine-id :data :token]))
            "LIVE frame A: machine snapshot :data :token redacts")
        (is (= :rf/redacted (get-in live [:rf.runtime/routing :current :query :token]))
            "LIVE frame A: route :current :query :token redacts"))
      ;; teardown race
      (rf/destroy-frame! server-frame)
      (let [dead (rf.ssr.payload-policy/project-runtime-db rt server-frame)]
        (is (= :rf/redacted (:rf.runtime/machines dead))
            "DESTROYED frame: the machines slice fails closed whole (the hook is
             not invoked with a dead frame)")
        (is (= :rf/redacted (:rf.runtime/routing dead))
            "DESTROYED frame: the routing slice fails closed via project-routing-egress")
        (doseq [secret ["secret-oauth-token" "huge-callback-blob-value"
                        "secret-jwt-snapshot" "huge-blob-value"]]
          (is (not (.contains (pr-str dead) secret))
              (str "no raw classified value (" secret ") survives")))))))

(deftest build-final-payload-fails-closed-on-teardown-race
  (testing "rf2-j538f7.15 (acceptance #2) — the REAL streaming builder: frame A
            is destroyed AFTER its runtime-db is captured but BEFORE projection
            (an async-host teardown race, DR#4). build-final-payload fails closed
            — app-db redacts whole, runtime-db omitted — even though the payload
            is still stamped for A; no classified route / machine / app-db value
            survives."
    (setup-frame-a!)
    (let [orig    rf.frame/frame-runtime-db-value
          payload (with-redefs [rf.frame/frame-runtime-db-value
                                (fn [fid]
                                  ;; DR#4 interposition: capture the live
                                  ;; runtime-db, THEN tear the frame down before
                                  ;; the projection proceeds.
                                  (let [v (orig fid)]
                                    (rf/destroy-frame! server-frame)
                                    v))]
                    (rf.ssr.streaming/build-final-payload
                      server-frame "hash"
                      {:payload :rf.ssr.payload/whole-app-db}))]
      ;; rf2-lm2yzy — wire :rf/frame-id decoupled from the projection frame;
      ;; no `:client-frame-id` opt ⇒ omitted. Fail-closed redaction below still
      ;; proves the projection targeted the explicit frame A.
      (is (not (contains? payload :rf/frame-id))
          "anonymous per-request frame omits the wire :rf/frame-id")
      (is (= :rf/redacted (:rf/app-db payload))
          "app-db fails closed to :rf/redacted (the frame vanished before projection)")
      (is (not (contains? payload :rf/runtime-db))
          "the runtime-db slice is omitted — no durable frame-state under a dead frame")
      (doseq [secret ["secret-oauth-token" "huge-callback-blob-value"
                      "secret-jwt-snapshot" "huge-blob-value" "app-db-secret"]]
        (is (not (.contains (pr-str payload) secret))
            (str "no raw classified value (" secret ") survives the fail-closed payload"))))))

(deftest build-final-payload-fails-closed-on-frame-reregistration
  (testing "rf2-j538f7.15 (acceptance #5) — frame A destroyed AND re-registered
            under the SAME id (a NEW incarnation with an absent policy) between
            capture and projection must NOT substitute the new frame's policy for
            the old frame's captured, classified data. The incarnation-token
            check fails closed rather than shipping A-old's token under A-new's
            wide-open registry."
    (setup-frame-a!)
    (let [orig    rf.frame/frame-runtime-db-value
          payload (with-redefs [rf.frame/frame-runtime-db-value
                                (fn [fid]
                                  (let [v (orig fid)]
                                    ;; destroy A, then re-register a FRESH A that
                                    ;; declares nothing — a wide-open policy that
                                    ;; WOULD ship A-old's secret raw if substituted.
                                    (rf/destroy-frame! server-frame)
                                    (rf/make-frame {:id server-frame :platform :server})
                                    v))]
                    (rf.ssr.streaming/build-final-payload
                      server-frame "hash"
                      {:payload :rf.ssr.payload/whole-app-db}))]
      ;; rf2-lm2yzy — wire :rf/frame-id decoupled from the projection frame;
      ;; no `:client-frame-id` opt ⇒ omitted. Fail-closed redaction below still
      ;; proves the projection targeted the explicit frame A.
      (is (not (contains? payload :rf/frame-id))
          "anonymous per-request frame omits the wire :rf/frame-id")
      (is (= :rf/redacted (:rf/app-db payload))
          "app-db fails closed — the re-registered frame's absent policy is not substituted")
      (is (not (contains? payload :rf/runtime-db))
          "the runtime-db slice is omitted under the substituted incarnation")
      (doseq [secret ["secret-oauth-token" "secret-jwt-snapshot" "app-db-secret"]]
        (is (not (.contains (pr-str payload) secret))
            (str "no raw classified value (" secret ") rides under the new-frame policy"))))))
