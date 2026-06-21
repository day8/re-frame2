(ns re-frame.ssr-hydration-test
  "Per rf2-pxb7t · Wave 3 of rf2-tglku (Migration-Audit §ssr_basic).

  The pre-migration Playwright spec at `testbeds/ssr_basic/spec.cjs`
  drove the SSR hydration baseline through a real browser load: read
  the baked `<script id=\"__rf_payload\">`, dispatch `:rf/hydrate`,
  render, observe seeded state + post-hydrate dispatch interactivity
  + the per-request `:rf/response` round-trip + trace-bus emission
  patterns.

  Every load-bearing assertion is platform-neutral — the contract
  surface (the `:rf/hydrate` handler, the [:rf.runtime/ssr :hydration] metadata,
  the compatibility-check fxs, `verify-hydration!`, the
  `:rf/response` shape) lives in `re-frame.ssr.hydrate` and
  surrounding sub-namespaces, which are `.cljc`. Per the migration
  audit's (A) classification the 11 substantive assertions migrate
  to this JVM test using the JVM SSR-test conventions
  (`tf/reset-runtime` + `rf/make-frame` + `rf/dispatch-sync` +
  `rf/subscribe-once` for synchronous reads).

  ## Migration map (Migration-Audit.md §ssr_basic)

    spec.cjs assertion #3 (hydrated = 'hydrated')
      → hydration-baseline-replaces-app-db-and-stashes-metadata
    spec.cjs #4 (count = '7' seeded)
      → hydration-baseline-replaces-app-db-and-stashes-metadata
    spec.cjs #5 (title = 'seeded')
      → hydration-baseline-replaces-app-db-and-stashes-metadata
    spec.cjs #6 (post-inc click: count = '8')
      → hydration-baseline-post-hydrate-dispatch-mutates-seeded-db
    spec.cjs #7 (post-set-title click: title = 'hydrated')
      → hydration-baseline-post-hydrate-dispatch-mutates-seeded-db
    spec.cjs #8-11 (resp-status/ct/cookies-count/cookie-name)
      → hydration-baseline-rf-response-slice-round-trips-via-payload
    spec.cjs #12 (:rf.ssr/compatibility-check-skipped trace)
      → hydration-baseline-emits-compatibility-check-skipped-trace
    spec.cjs #13 (no :rf.ssr/hydration-mismatch on baseline)
      → hydration-baseline-no-mismatch-trace-when-server-hash-nil

  Assertions #1-#2 (`expectVisible(ssr-basic)` + `expectVisible(hydrated)`)
  are pure DOM-mount probes — the Migration-Audit classifies them (C);
  per the rf2-pxb7t bead the whole `spec.cjs` is dropped and those
  two assertions retire alongside (substrate mount is already covered
  by the 3 adapter smokes per the audit's §Drop-or-keep recommendation)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; The payload the testbed's `<script id=\"__rf_payload\">` bakes verbatim
;; (testbeds/ssr_basic/index.html lines 58-73). Pinning the literal here
;; keeps the JVM-side migration anchored to the wire shape the (now
;; deleted) Playwright spec observed.
;; rf2-nv3mua: NO `:rf/frame-id` key. The pre-fix literal stamped
;; `:rf/frame-id :rf/default`, but these JVM tests dispatch the payload into a
;; freshly-`make-frame`'d `client-frame` (NOT `:rf/default`). Now that the
;; `:rf/hydrate` handler fails CLOSED on a present-and-different `:rf/frame-id`
;; (the bug fix), a literal `:rf/default` stamp against a synthetic
;; `client-frame` would (correctly) be rejected as a frame-id mismatch. An
;; absent `:rf/frame-id` is the documented no-conflict shape — the dispatch
;; target stands — which is what these baseline tests intend (the testbed
;; itself hydrated `:rf/default` and matched; the synthetic-frame migration is
;; what introduced the latent mismatch the bug had been masking). The frame-id
;; mismatch + match paths are covered explicitly by the dedicated tests below
;; and in ssr_hydration_mismatch_test.
(def ^:private baseline-payload
  {:rf/version     1
   :rf/render-hash nil
   :rf/app-db      {:count 7 :title "seeded"}
   :rf/response    {:status   200
                    :headers  {"content-type" "text/html; charset=utf-8"
                               "x-request-id" "test-req-1"}
                    :cookies  [{:name      "session"
                                :value     "abc123"
                                :http-only true
                                :secure    true
                                :same-site :lax
                                :path      "/"}]
                    :redirect nil}})

;; ----------------------------------------------------------------------------
;; Shared registrations — mirrors testbeds/ssr_basic/core.cljs lines 98-109
;; ----------------------------------------------------------------------------

(defn- register-baseline-handlers! []
  (rf/reg-event ::inc
    (fn [{:keys [db]} _ev] {:db (update db :count (fnil inc 0))}))
  (rf/reg-event ::set-title
    (fn [{:keys [db]} [_ t]] {:db (assoc db :title t)}))
  (rf/reg-sub :count       (fn [db _] (or (:count db) 0)))
  (rf/reg-sub :title       (fn [db _] (or (:title db) "untitled")))
  (rf/reg-sub :server-resp (fn [db _] (:server-response db)))
  ;; EP-0001 (rf2-vzld77): the SSR hydration metadata is durable runtime-db
  ;; state, so :hydrated? is a runtime-db sub.
  (subs/reg-runtime-sub :hydrated? (fn [rt _] (boolean (get-in rt [:rf.runtime/ssr :hydration])))))

(defn- materialise-response
  "Mirror of testbeds/ssr_basic/core.cljs's `materialise-response` —
  the testbed's client-side hoist of the payload's `:rf/response`
  slice onto `[:server-response]` in app-db so the view can read it
  through a sub. Per Spec 011 §Response storage substrate the server-
  side runtime keeps `:rf/response` in a side-channel atom (not in
  app-db); the hoist is the test surface's bridge from the wire to
  the view layer."
  [payload]
  (cond-> payload
    (and (map? payload) (:rf/response payload))
    (update :rf/app-db assoc :server-response (:rf/response payload))))

(defn- capture-traces!
  "Run f under a trace listener; return the captured event vector."
  [f]
  (let [traces (atom [])
        cb-id  (gensym "::ssr-hydration-capture-")]
    (rf/register-listener! :trace cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace cb-id)))
    @traces))

;; ===========================================================================
;; spec.cjs §(2)+(3) → hydrated marker + seeded state from payload
;; ===========================================================================

(deftest hydration-baseline-replaces-app-db-and-stashes-metadata
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertions #3-#5.
            :rf/hydrate replaces app-db with the payload's :rf/app-db
            (Spec 011 §The :rf/hydrate event — `:replace-app-db` policy),
            stashes the version + nil server-hash under
            [:rf.runtime/ssr :hydration],
            and the :hydrated? / :count / :title subs read the
            post-hydrate values via subscribe-once (no view re-render
            machinery needed; the contract is the app-db state)."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          ":hydrated? reads true once hydration metadata lands at [:rf.runtime/ssr :hydration]")
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          "seeded :count from payload's :rf/app-db wins")
      (is (= "seeded" (rf/subscribe-once client-frame [:title]))
          "seeded :title from payload's :rf/app-db wins")
      ;; Lock the [:rf.runtime/ssr :hydration] metadata shape (the
      ;; testbed's view doesn't read these slots, but downstream tooling
      ;; — Xray / the late-bind compatibility-check fxs — does).
      ;; EP-0001 (rf2-vzld77): the hydration metadata is durable runtime-db state.
      (let [rt (rf/runtime-db-value client-frame)]
        (is (= 1 (get-in rt [:rf.runtime/ssr :hydration :version]))
            ":rf/version rides on the hydration metadata block")
        (is (not (contains? (get-in rt [:rf.runtime/ssr :hydration]) :server-hash))
            "nil :rf/render-hash is pruned from the metadata block
             (rf2-asmj1 Q9 / cluster rf2-sljs1)")))))

;; ===========================================================================
;; spec.cjs §(4) → reactive substrate is live post-hydrate
;; ===========================================================================

(deftest hydration-baseline-post-hydrate-dispatch-mutates-seeded-db
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertions #6-#7.
            The post-hydrate dispatch path (event → db → sub) is live —
            ::inc bumps the seeded :count, ::set-title overwrites the
            seeded :title. Proves the six-domino loop survives the
            hydration handoff intact (the testbed's Playwright spec
            observed the same via DOM re-render; subscribe-once reads
            the post-drain app-db directly)."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      ;; ::inc — bumps the seeded :count 7 → 8
      (rf/dispatch-sync [::inc] {:frame client-frame})
      (is (= 8 (rf/subscribe-once client-frame [:count]))
          "post-hydrate ::inc bumps the seeded :count via the live
           event-handler → db-update → sub-recompute pipeline")

      ;; ::set-title — overwrites the seeded :title slot
      (rf/dispatch-sync [::set-title "hydrated"] {:frame client-frame})
      (is (= "hydrated" (rf/subscribe-once client-frame [:title]))
          "post-hydrate ::set-title overwrites the seeded :title"))))

;; ===========================================================================
;; spec.cjs §(5) → per-request :rf/response slice round-trips through the payload
;; ===========================================================================

(deftest hydration-baseline-rf-response-slice-round-trips-via-payload
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertions #8-#11.
            Per Spec 011 §The hydration payload: the payload may
            carry an optional :rf/response slice (status, headers,
            cookies, redirect). The testbed's client-side
            `materialise-response` hoists it into app-db at
            [:server-response] for the view layer. The contract
            asserted here: payload → app-db → sub round-trip is loss-
            less for the four fields the testbed's view renders
            (resp-status, resp-ct, resp-cookies-count,
            resp-cookie-name)."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
      (let [resp (rf/subscribe-once client-frame [:server-resp])]
        (is (= 200 (:status resp))
            "status round-trips verbatim")
        (is (= "text/html; charset=utf-8"
               (get-in resp [:headers "content-type"]))
            "content-type header round-trips verbatim")
        (is (= 1 (count (:cookies resp)))
            "the payload's one cookie lands in :cookies")
        (is (= "session" (:name (first (:cookies resp))))
            "the cookie's :name slot round-trips verbatim")))))

;; ===========================================================================
;; spec.cjs §(6) → :rf.ssr/compatibility-check-skipped trace fires
;; ===========================================================================

(deftest hydration-baseline-emits-compatibility-check-skipped-trace
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertion #12.
            The baseline surface registers no :rf2/runtime-version
            late-bind hook (per testbed core.cljs — no host-version
            stamp wired). The :rf.ssr/check-version fx dispatched by
            :rf/hydrate emits :rf.ssr/compatibility-check-skipped
            (Spec 011 §The :rf/hydrate event — best-effort
            compatibility check; absence of the hook is degraded-
            but-running, never crash)."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)
          traces       (capture-traces!
                         (fn []
                           (rf/dispatch-sync [:rf/hydrate payload]
                                             {:frame client-frame})))
          skipped      (filter #(= :rf.ssr/compatibility-check-skipped
                                   (:operation %))
                               traces)]
      (is (seq skipped)
          (str "expected at least one :rf.ssr/compatibility-check-skipped "
               "trace (the baseline surface registers no "
               ":rf2/runtime-version hook); saw operations: "
               (pr-str (mapv :operation traces))))
      (let [ev (first skipped)]
        (is (= :rf.ssr/check-version (-> ev :tags :check))
            "tag :check identifies the originating compatibility-check fx")
        (is (= 1 (-> ev :tags :expected))
            "tag :expected carries the payload's :rf/version verbatim")))))

;; ===========================================================================
;; spec.cjs §(7) → NO mismatch trace on the baseline
;; ===========================================================================

;; ===========================================================================
;; rf2-7bcn0 — server-side :rf/hydrate skips the client-only check fxs
;; ===========================================================================

(deftest hydration-on-server-platform-skips-client-only-check-fxs
  (testing "Per rf2-7bcn0: when :rf/hydrate runs on a frame whose resolved
            platform is :server (test harness, isomorphic loopback), the
            handler MUST NOT enqueue the :rf.ssr/check-version /
            :rf.ssr/check-schema-digest fxs. The fxs themselves carry
            :platforms #{:client}, so if the handler enqueued them
            unconditionally the fx-platform-gate would fire a
            :rf.fx/skipped-on-platform warning per check on every server-
            side hydrate. The handler-level gate prevents that noise."
    (register-baseline-handlers!)
    (let [server-frame (frame/make-anon-frame-record! {:doc "ssr-basic server frame"
                                       :platform :server})
          ;; Add a :rf/schema-digest so BOTH checks would fire if the
          ;; handler enqueued them; otherwise only :rf.ssr/check-version
          ;; would and the test couldn't distinguish "gated by handler"
          ;; from "absent because no schema-digest".
          payload      (-> baseline-payload
                           (assoc :rf/schema-digest "test-digest-abc")
                           materialise-response)
          traces       (capture-traces!
                         (fn []
                           (rf/dispatch-sync [:rf/hydrate payload]
                                             {:frame server-frame})))
          skipped-checks
          (filter (fn [ev]
                    (and (= :rf.fx/skipped-on-platform (:operation ev))
                         (#{:rf.ssr/check-version :rf.ssr/check-schema-digest}
                          (-> ev :tags :rf.fx/id))))
                  traces)]
      (is (empty? skipped-checks)
          (str "expected zero :rf.fx/skipped-on-platform traces for the two "
               ":rf.ssr/check-* fxs on a :server-platform :rf/hydrate; saw: "
               (pr-str (mapv (juxt :operation #(-> % :tags :rf.fx/id))
                             skipped-checks))))
      ;; Sanity: the handler still landed the app-db swap + metadata —
      ;; the gate skipped only the check-fx dispatches, not the rest.
      (is (= 7 (rf/subscribe-once server-frame [:count]))
          ":rf/app-db still applied on the server-side run"))))

(deftest hydration-on-client-platform-still-dispatches-check-fxs
  (testing "Per rf2-7bcn0 (counter-test to the server-side skip): on a
            :client-platform frame the handler MUST still enqueue the
            check fxs so legitimate client-side mismatches surface via
            :rf.ssr/compatibility-check-skipped (when the late-bind
            hook is absent) or :rf.ssr/version-mismatch (when present
            and differing). Without this counter-assertion the
            server-side gate could silently strip both code paths."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)
          traces       (capture-traces!
                         (fn []
                           (rf/dispatch-sync [:rf/hydrate payload]
                                             {:frame client-frame})))
          skipped (filter #(= :rf.ssr/compatibility-check-skipped
                              (:operation %))
                          traces)]
      ;; Same trace as hydration-baseline-emits-compatibility-check-skipped-trace —
      ;; the fx STILL fires on the client frame because no
      ;; :rf2/runtime-version hook is registered. Asserts the
      ;; rf2-7bcn0 gate did NOT over-skip on :client.
      (is (seq skipped)
          (str "on :client the :rf.ssr/check-version fx still fires and "
               "emits :rf.ssr/compatibility-check-skipped (no runtime-"
               "version hook registered); saw operations: "
               (pr-str (mapv :operation traces)))))))

(deftest hydration-baseline-no-mismatch-trace-when-server-hash-nil
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertion #13.
            Per Spec 011 §Hydration-mismatch detection:
            verify-hydration! short-circuits when the server hash is
            nil — there is nothing to compare against. No
            :rf.ssr/hydration-mismatch trace fires on the baseline
            surface (its payload's :rf/render-hash is nil)."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      (let [traces (capture-traces!
                     (fn []
                       ;; Simulate the testbed's post-render
                       ;; verify-hydration! call. The resolved tree is
                       ;; opaque here — we pass a synthetic 8-hex
                       ;; "client hash" to mirror the call shape; the
                       ;; nil server-hash on the metadata block makes
                       ;; the call a no-op regardless of the client
                       ;; value (Spec 011 — `(when (and server-hash
                       ;; client-hash ...) ...)` short-circuits).
                       (ssr/verify-hydration! client-frame "abcdef01")))]
        (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
            (str "no :rf.ssr/hydration-mismatch on the baseline (server-"
                 "hash was nil); saw: "
                 (pr-str (mapv :operation traces))))))))

;; ===========================================================================
;; rf2-gro94 — fail CLOSED on a malformed / untrusted hydration payload
;; ===========================================================================
;;
;; The payload is a DESERIALISED, UNTRUSTED transport input (the server's
;; `pr-str`'d EDN). `:replace-app-db` is the locked merge policy, so a
;; non-map payload — or a present-but-non-map app-db slice — would
;; otherwise be installed as the ENTIRE client app-db (a fail-OPEN, the
;; same class the schemas / routing sweeps closed). The handler now
;; REJECTS it: existing app-db unchanged + a
;; `:rf.error/malformed-hydration-payload` diagnostic. This drives the
;; rejection through the REAL `dispatch-sync` router (end-to-end), the
;; companion to the direct-handler invariant in
;; `re-frame.security.fail-closed-invariant-security-cljs-test`.

(deftest malformed-hydration-payload-fails-closed-through-router
  (testing "rf2-gro94 — a non-map payload, or a present-but-non-map app-db
            slice, dispatched as [:rf/hydrate …] through the router does
            NOT replace app-db: the pre-hydration client state survives and
            a :rf.error/malformed-hydration-payload trace fires."
    (register-baseline-handlers!)
    (doseq [bad-payload [nil
                         "a string payload"
                         42
                         [:not :a :map]
                         {:rf/app-db "slice-is-a-string"}
                         {:rf/app-db [:slice :is :a :vector]}
                         {:rf/app-db 99}]]
      (let [client-frame (frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                         :platform :client})]
        ;; Seed a recognisable pre-hydration client slice so we can prove
        ;; it SURVIVES (was not replaced by the malformed payload).
        (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
        (rf/dispatch-sync [::inc] {:frame client-frame})  ;; count 0 → 1
        (let [traces (capture-traces!
                       (fn []
                         (rf/dispatch-sync [:rf/hydrate bad-payload]
                                           {:frame client-frame})))]
          (is (= "pre-hydration" (rf/subscribe-once client-frame [:title]))
              (str (pr-str bad-payload)
                   " must NOT replace the client :title (fail closed)"))
          (is (= 1 (rf/subscribe-once client-frame [:count]))
              (str (pr-str bad-payload)
                   " must NOT replace the client :count (fail closed)"))
          (is (false? (rf/subscribe-once client-frame [:hydrated?]))
              (str (pr-str bad-payload)
                   " must NOT stash hydration metadata (rejected, not applied)"))
          (is (some #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
              (str (pr-str bad-payload)
                   " must emit :rf.error/malformed-hydration-payload; saw: "
                   (pr-str (mapv :operation traces)))))))))

(deftest wellformed-hydration-payload-still-applies-through-router
  (testing "rf2-gro94 — the fail-closed guard is precise: a well-formed
            payload (map with a map :rf/app-db slice) still replaces app-db
            through the router, and a no-slice map payload preserves the
            existing client slice (the documented client-only fallback) —
            neither emits the malformed diagnostic."
    (register-baseline-handlers!)
    ;; (a) full server slice → replaces app-db, no diagnostic.
    (let [client-frame (frame/make-anon-frame-record! {:doc "client frame a" :platform :client})
          traces       (capture-traces!
                         (fn []
                           (rf/dispatch-sync [:rf/hydrate {:rf/app-db {:count 7 :title "seeded"}}]
                                             {:frame client-frame})))]
      (is (= 7 (rf/subscribe-once client-frame [:count])) "server slice installed")
      (is (= "seeded" (rf/subscribe-once client-frame [:title])))
      (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
          "no malformed diagnostic on a well-formed payload"))
    ;; (b) map payload with no app-db slice → existing client data survives.
    (let [client-frame (frame/make-anon-frame-record! {:doc "client frame b" :platform :client})]
      (rf/dispatch-sync [::set-title "kept"] {:frame client-frame})
      (let [traces (capture-traces!
                     (fn []
                       (rf/dispatch-sync [:rf/hydrate {:rf/version 1}]
                                         {:frame client-frame})))]
        (is (= "kept" (rf/subscribe-once client-frame [:title]))
            "no-slice payload preserves the existing client slice")
        (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
            "a no-slice map payload is the legitimate client-only fallback, not malformed")))))

;; ===========================================================================
;; rf2-g00l2t — fail CLOSED on a present-but-non-map :rf/runtime-db slice
;; ===========================================================================
;;
;; EP-0001 (rf2-vzld77): hydration installs a coherent FRAME-STATE —
;; `:rf/app-db` becomes the app-db partition AND `:rf/runtime-db` becomes
;; the runtime-db partition (machine snapshots, route slice, SSR metadata).
;; Before rf2-g00l2t the guard validated ONLY the app-db slice; a present-
;; but-non-map `:rf/runtime-db` (a corrupt / hostile / version-skewed
;; payload) was silently coerced to nil and dropped, then the handler still
;; installed a new app-db + hydration metadata — a partial hydration that
;; violates the spec's coherent-frame-state, fail-closed boundary (Spec 011
;; §The :rf/hydrate event — "Both partitions validate fail-closed before
;; installation"). The guard now rejects it the SAME way as a non-map
;; app-db slice: both partitions left unchanged, no compatibility-check
;; fxs fire, and `:rf.error/malformed-hydration-payload` is emitted.

(deftest non-map-runtime-db-slice-fails-closed-through-router
  (testing "rf2-g00l2t — a payload carrying a present-but-non-map
            :rf/runtime-db slice (even with a perfectly valid :rf/app-db)
            is REJECTED through the router: neither the app-db partition
            NOR the runtime-db partition changes, no hydration metadata is
            stashed, and :rf.error/malformed-hydration-payload fires. This
            is the runtime-db counterpart to the app-db fail-closed guard."
    (register-baseline-handlers!)
    ;; Framework-authority test event that seeds a recognisable runtime-db
    ;; slice via the reserved `:rf.db/runtime` effect — so we can prove the
    ;; runtime partition SURVIVES a malformed-payload rejection. Registered
    ;; inside the test (the `tf/reset-runtime` fixture clears registrations
    ;; before each test, so a load-time registration would be wiped).
    (rf/reg-event ::seed-runtime
      (fn [{:keys [db] rt :rf.db/runtime} _]
        {:db db
         :rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/machines :snapshots]
                                  {:m {:value :idle}})}))
    (subs/reg-runtime-sub :machine-snapshots
      (fn [rt _] (get-in rt [:rf.runtime/machines :snapshots])))
    (doseq [bad-rt ["runtime-is-a-string"
                    [:runtime :is :a :vector]
                    42
                    false]]
      (let [client-frame (frame/make-anon-frame-record! {:doc "non-map-runtime-db client frame"
                                         :platform :client})]
        ;; Seed recognisable pre-hydration state in BOTH partitions.
        (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
        (rf/dispatch-sync [::inc] {:frame client-frame})       ;; count 0 → 1
        (rf/dispatch-sync [::seed-runtime] {:frame client-frame})
        (let [bad-payload {:rf/app-db   {:count 99 :title "would-replace"}
                           :rf/runtime-db bad-rt}
              traces (capture-traces!
                       (fn []
                         (rf/dispatch-sync [:rf/hydrate bad-payload]
                                           {:frame client-frame})))]
          ;; app-db partition unchanged — the valid :rf/app-db slice must
          ;; NOT land because the runtime-db slice made the payload malformed.
          (is (= "pre-hydration" (rf/subscribe-once client-frame [:title]))
              (str (pr-str bad-rt)
                   " runtime-db slice must NOT let the app-db slice replace :title"))
          (is (= 1 (rf/subscribe-once client-frame [:count]))
              (str (pr-str bad-rt)
                   " runtime-db slice must NOT let the app-db slice replace :count"))
          ;; runtime-db partition unchanged — seeded machine snapshot survives.
          (is (= {:m {:value :idle}}
                 (rf/subscribe-once client-frame [:machine-snapshots]))
              (str (pr-str bad-rt)
                   " must leave the runtime-db partition (machine snapshot) unchanged"))
          ;; no hydration metadata stashed (rejected, not applied).
          (is (false? (rf/subscribe-once client-frame [:hydrated?]))
              (str (pr-str bad-rt)
                   " must NOT stash hydration metadata (rejected, not applied)"))
          ;; the malformed diagnostic fires.
          (is (some #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
              (str (pr-str bad-rt)
                   " must emit :rf.error/malformed-hydration-payload; saw: "
                   (pr-str (mapv :operation traces))))
          ;; no compatibility-check fxs fire on the rejected path.
          (is (not-any? #(#{:rf.ssr/version-mismatch
                            :rf.ssr/schema-digest-mismatch
                            :rf.ssr/compatibility-check-skipped}
                          (:operation %)) traces)
              (str (pr-str bad-rt)
                   " must NOT fire compatibility-check fxs on the rejected path")))))))

(deftest wellformed-runtime-db-slice-still-installs-through-router
  (testing "rf2-g00l2t — the runtime-db guard is precise: a well-formed map
            :rf/runtime-db slice still installs the runtime-db partition
            through the router, and a wholly-absent :rf/runtime-db key is
            the legitimate no-server-runtime fallback (neither is malformed)."
    (register-baseline-handlers!)
    (subs/reg-runtime-sub :route-current
      (fn [rt _] (get-in rt [:rf.runtime/routing :current])))
    ;; (a) map runtime-db slice → installs the runtime-db partition.
    (let [client-frame (frame/make-anon-frame-record! {:doc "rt-ok client frame" :platform :client})
          payload {:rf/app-db     {:count 7 :title "seeded"}
                   :rf/runtime-db {:rf.runtime/routing {:current {:route-id :home}}}}
          traces  (capture-traces!
                    (fn []
                      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})))]
      (is (= 7 (rf/subscribe-once client-frame [:count])) "app-db slice installed")
      (is (= {:route-id :home} (rf/subscribe-once client-frame [:route-current]))
          "the runtime-db route slice rode the payload and installed")
      (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
          "no malformed diagnostic on a well-formed two-partition payload"))
    ;; (b) wholly-absent :rf/runtime-db key → no-server-runtime fallback.
    (let [client-frame (frame/make-anon-frame-record! {:doc "rt-absent client frame" :platform :client})
          traces (capture-traces!
                   (fn []
                     (rf/dispatch-sync [:rf/hydrate {:rf/app-db {:count 3}}]
                                       {:frame client-frame})))]
      (is (= 3 (rf/subscribe-once client-frame [:count])) "app-db slice installed")
      (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
          "an absent :rf/runtime-db key is the no-server-runtime fallback, not malformed"))))

;; ===========================================================================
;; rf2-1qem4q — the retired plain :app-db hydration alias stays DEAD
;; ===========================================================================
;;
;; The hydrate handler reads ONLY `:rf/app-db` (re-frame.ssr.hydrate line
;; `new-db (or (:rf/app-db payload) db)`). A plain `:app-db` key never had
;; alias status here. This negative regression pins that: a future worker
;; who reintroduced an `(:app-db payload)` fallback (the v1-era unqualified
;; spelling) would turn this test red. Without it the current happy-path
;; tests would stay green — none dispatch a `:app-db`-keyed payload — so the
;; alias could silently come back. The asserted behaviour: under the
;; documented open-map / no-slice semantics, a `{:app-db {…}}` payload is an
;; UNKNOWN no-slice payload (the `:rf/app-db` key is absent, so app-db is
;; left unchanged), NOT an alias that replaces app-db. It is also not
;; malformed — the payload is a map with no `:rf/app-db` key, which is the
;; legitimate client-only fallback shape.

(deftest plain-app-db-key-is-not-a-hydration-alias
  (testing "rf2-1qem4q — [:rf/hydrate {:app-db {…}}] must NOT replace app-db.
            The handler reads only :rf/app-db; the unqualified :app-db key is
            a retired alias that stays dead. It behaves as an unknown no-slice
            payload (app-db unchanged, client-only fallback), not as an alias."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "alias-dead client frame"
                                       :platform :client})]
      ;; Seed a recognisable pre-hydration client slice so we can prove it
      ;; SURVIVES (was not replaced by the :app-db-keyed payload).
      (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
      (rf/dispatch-sync [::inc] {:frame client-frame})  ;; count 0 → 1
      (let [traces (capture-traces!
                     (fn []
                       (rf/dispatch-sync
                         [:rf/hydrate {:app-db {:count 99 :title "legacy"}}]
                         {:frame client-frame})))]
        (is (= 1 (rf/subscribe-once client-frame [:count]))
            "the plain :app-db key did NOT replace :count (alias stays dead —
             the 99 from {:app-db {…}} must not land)")
        (is (= "pre-hydration" (rf/subscribe-once client-frame [:title]))
            "the plain :app-db key did NOT replace :title (alias stays dead —
             \"legacy\" must not land)")
        (is (false? (rf/subscribe-once client-frame [:hydrated?]))
            "no hydration metadata stashed — the :rf/render-hash / :rf/version
             keys are absent, so the no-slice payload installs no metadata")
        (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) traces)
            (str "a {:app-db {…}} payload is a map with no :rf/app-db key — the "
                 "legitimate client-only no-slice fallback, NOT malformed; saw: "
                 (pr-str (mapv :operation traces))))))))

;; ===========================================================================
;; rf2-lq2ou — client-side hydration boot helper (ssr/hydrate!)
;;
;; The symmetric client-side counterpart of `re-frame.ssr.ring/ssr-handler`.
;; `hydrate!` fuses the read → dispatch `:rf/hydrate` → `verify-hydration!`
;; ordering Spec 011 §Client flow mandates. These tests drive it on the JVM
;; with an EXPLICIT `:payload` (no DOM to read from server-side) on a
;; `:client`-platform frame, exercising the full server-`build-payload` →
;; `hydrate!` → post-hydrate-sub round-trip without a browser.
;; ===========================================================================

(defn- build-server-payload
  "Mirror the server-side payload build: project app-db per the policy and
  assemble the canonical `:rf/hydration-payload` — the SAME
  `re-frame.ssr.payload-policy/build-payload` path
  `re-frame.ssr.ring.payload/build-payload` uses. `render-hash` is the
  FNV-1a hash of the render-tree the server stringified."
  [frame-id app-db render-hash policy-opts]
  (payload-policy/build-payload
    frame-id
    (payload-policy/apply-policy app-db policy-opts)
    render-hash
    policy-opts))

(deftest boot-hydrate-round-trips-server-payload-into-app-db
  (testing "rf2-lq2ou: ssr/hydrate! with an explicit :payload dispatches
            :rf/hydrate against the target client frame, and a post-hydrate
            sub reflects the seeded slice — the server-build → hydrate! →
            sub round-trip. hydrate! returns the applied payload."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "boot-helper client frame"
                                       :platform :client})
          ;; Server side: build the payload from the authoritative app-db
          ;; slice via the same payload-policy path the Ring adapter uses.
          server-app-db {:count 7 :title "seeded"
                         :server-only/auth "SECRET"}
          ;; EP-0002 (rf2-acjknb): the server stamps the payload's
          ;; :rf/frame-id with the SAME frame the client hydrates into, and
          ;; hydrate! VALIDATES the two agree. Build the payload under
          ;; `client-frame` so server + client carry one frame stamp.
          payload       (build-server-payload
                          client-frame server-app-db "deadbeef"
                          {:version 1 :payload [:count :title]})
          ;; Client side: the symmetric boot call.
          returned      (ssr/hydrate! {:frame   client-frame
                                       :payload payload})]
      (is (= payload returned)
          "hydrate! returns the applied payload so the caller can branch
           on `was this server-rendered?`")
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          "post-hydrate :count sub reflects the server-built payload slice")
      (is (= "seeded" (rf/subscribe-once client-frame [:title]))
          "post-hydrate :title sub reflects the server-built payload slice")
      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          ":hydrated? true once hydration metadata lands")
      ;; The allowlist policy used to build the payload dropped the
      ;; server-only key — the round-trip carries only the permitted slice.
      (is (nil? (:server-only/auth (rf/app-db-value client-frame)))
          ":payload allowlist kept the server-only key off the wire +
           thus out of the hydrated client app-db"))))

(deftest boot-hydrate-nil-payload-is-client-only-noop
  (testing "rf2-lq2ou: ssr/hydrate! with no payload (nil — the client-only
            first-load shape) does NOT dispatch :rf/hydrate and returns
            nil. The caller renders against the empty app-db."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "boot-helper client-only frame"
                                       :platform :client})
          returned     (ssr/hydrate! {:frame client-frame :payload nil})]
      (is (nil? returned)
          "nil payload → hydrate! returns nil (client-only first load)")
      (is (false? (rf/subscribe-once client-frame [:hydrated?]))
          "no hydration metadata stashed — :rf/hydrate was never dispatched")
      (is (= 0 (rf/subscribe-once client-frame [:count]))
          "app-db is the empty default; the :count sub's fallback applies"))))

(deftest boot-hydrate-verify-step-fires-mismatch-on-divergent-render
  (testing "rf2-lq2ou: hydrate!'s VERIFY step runs verify-hydration!
            against the :render-tree-fn SYNCHRONOUSLY, immediately after
            dispatching :rf/hydrate and before any host render (the
            seed-and-synchronously-compute-tree contract — rf2-3w6dmy
            finding 1). When the client render-tree hash != the server hash
            carried on the payload, :rf.ssr/hydration-mismatch fires — the
            boot helper wires mismatch detection symmetric with the
            server's :emit-hash? marker."
    (register-baseline-handlers!)
    (rf/reg-view* ::boot-root (fn [] [:div.app [:span "client-render"]]))
    (let [client-frame (frame/make-anon-frame-record! {:doc "boot-helper verify frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          ;; Server hash is a DELIBERATELY divergent value so the verify
          ;; step's comparison fails — proving the verify step actually ran.
          ;; EP-0002 (rf2-acjknb): payload :rf/frame-id == the client target.
          payload       (build-server-payload
                          client-frame {:count 7 :title "seeded"}
                          "server00"                 ;; != the client tree hash
                          {:version 1 :payload [:count :title]})
          traces        (capture-traces!
                          (fn []
                            (ssr/hydrate!
                              {:frame          client-frame
                               :payload        payload
                               :render-tree-fn (fn [] [:div.app [:span "client-render"]])})))]
      (is (some #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
          (str "verify step fired a :rf.ssr/hydration-mismatch (server hash "
               "'server00' != client render-tree hash); saw: "
               (pr-str (mapv :operation traces)))))))

(deftest boot-hydrate-verify-step-silent-on-matching-render
  (testing "rf2-lq2ou: when the client render-tree hash MATCHES the server
            hash, the verify step is silent — no spurious mismatch on a
            successful hydration. Counter-test to the divergent-render case
            so the verify step can't be a false-positive generator."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "boot-helper verify-match frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          client-tree   [:div.app [:span "client-render"]]
          ;; Compute the server hash from the SAME tree so the round-trip
          ;; hashes agree — the happy path.
          matched-hash  (ssr/render-tree-hash client-tree)
          ;; EP-0002 (rf2-acjknb): payload :rf/frame-id == the client target.
          payload       (build-server-payload
                          client-frame {:count 7 :title "seeded"}
                          matched-hash
                          {:version 1 :payload [:count :title]})
          traces        (capture-traces!
                          (fn []
                            (ssr/hydrate!
                              {:frame          client-frame
                               :payload        payload
                               :render-tree-fn (fn [] client-tree)})))]
      (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
          (str "matching hashes → no :rf.ssr/hydration-mismatch; saw: "
               (pr-str (mapv :operation traces))))
      ;; Sanity: the seed still landed (the verify step doesn't gate hydrate).
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          ":rf/hydrate still applied the seeded slice"))))

;; ===========================================================================
;; rf2-acjknb — EP-0002: hydrate! requires :frame; payload :rf/frame-id is
;; VALIDATED against the explicit target (no :rf/default-from-absence, no
;; silent side-pick on a frame-id conflict).
;; ===========================================================================

(deftest boot-hydrate-absent-frame-raises-no-frame-context
  (testing "rf2-acjknb (EP-0002): the client hydration target is carried —
            :frame is REQUIRED. Calling hydrate! with no :frame raises
            :rf.error/no-frame-context rather than synthesising :rf/default.
            The malformed-payload guard never runs (the absence fails first
            at the boundary)."
    (register-baseline-handlers!)
    (let [ex (try (ssr/hydrate! {:payload {:rf/app-db {:count 1}}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "hydrate! with no :frame must throw")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
          "an absent :frame surfaces :rf.error/no-frame-context"))))

(deftest boot-hydrate-frame-id-mismatch-raises-structured-error
  (testing "rf2-acjknb (EP-0002): the payload's :rf/frame-id is validated
            against the explicit :frame target. A present-and-different
            frame-id (the server rendered under a DIFFERENT frame than the
            client is installing into) raises a structured
            :rf.error/hydration-frame-id-mismatch — the runtime never
            silently picks a side, and app-db is NOT replaced."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "mismatch client frame"
                                       :platform :client})
          ;; Payload stamped with a DIFFERENT frame id than the client target.
          other-frame  (frame/make-anon-frame-record! {:doc "the server's other frame"
                                       :platform :server})
          payload      (build-server-payload
                         other-frame {:count 7 :title "seeded"} "deadbeef"
                         {:version 1 :payload [:count :title]})
          ex           (try (ssr/hydrate! {:frame client-frame :payload payload})
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a payload/target frame-id conflict must throw")
      (is (= :rf.error/hydration-frame-id-mismatch (:rf.error/id (ex-data ex)))
          "the conflict surfaces a structured :rf.error/hydration-frame-id-mismatch")
      (is (= client-frame (:target-frame (ex-data ex)))
          "the error carries the explicit client target frame")
      (is (= other-frame (:payload-frame-id (ex-data ex)))
          "the error carries the payload's (server) frame-id")
      ;; The conflict halts BEFORE :rf/hydrate dispatches — app-db untouched.
      (is (= 0 (rf/subscribe-once client-frame [:count]))
          "the mismatch is surfaced before the app-db replace; no slice landed"))))

(deftest boot-hydrate-absent-payload-frame-id-no-conflict
  (testing "rf2-acjknb (EP-0002): a payload carrying NO :rf/frame-id is not a
            conflict — there is nothing to disagree with, so the explicit
            client target stands and hydration proceeds normally."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "no-payload-frame-id client"
                                       :platform :client})
          ;; A hand-built payload deliberately WITHOUT :rf/frame-id.
          payload      {:rf/version 1 :rf/app-db {:count 7 :title "seeded"}}
          returned     (ssr/hydrate! {:frame client-frame :payload payload})]
      (is (= payload returned) "hydration proceeded (no frame-id to conflict)")
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          "the seeded slice landed — an absent payload :rf/frame-id is no conflict"))))

;; ===========================================================================
;; rf2-nv3mua — the :rf/hydrate HANDLER enforces frame-id validation too, so
;; the direct-dispatch split path (`hydrate!`'s documented post-mount-verify
;; escape hatch) cannot bypass it. `hydrate!` validates+throws pre-dispatch
;; (covered above); these cover the handler boundary reached by a direct
;; `dispatch-sync [:rf/hydrate payload] {:frame target}`.
;; ===========================================================================

(deftest direct-dispatch-frame-id-mismatch-fails-closed
  (testing "rf2-nv3mua: a direct dispatch of [:rf/hydrate payload] whose
            present :rf/frame-id names a DIFFERENT frame than the dispatch
            target leaves app-db AND runtime-db unchanged and emits
            :rf.error/hydration-frame-id-mismatch — the handler will not
            silently install a server slice rendered for another frame.
            This is the bypass the bead names: hydrate! throws pre-dispatch,
            but a direct dispatch hits ONLY the handler."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "nv3mua direct-dispatch client"
                                       :platform :client})]
      ;; Seed a recognisable pre-hydration client slice so we can prove it
      ;; SURVIVES (was not replaced by the wrong-frame payload).
      (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
      (rf/dispatch-sync [::inc] {:frame client-frame})  ;; count 0 → 1
      (let [payload {:rf/frame-id    :some/other-frame
                     :rf/app-db      {:count 42 :title "wrong-frame slice"}
                     :rf/runtime-db  {:rf.runtime/machines {:snapshots {:m :installed}}}
                     :rf/render-hash "deadbeef"}
            traces  (capture-traces!
                      (fn []
                        (rf/dispatch-sync [:rf/hydrate payload]
                                          {:frame client-frame})))]
        ;; app-db partition untouched (fail closed).
        (is (= 1 (rf/subscribe-once client-frame [:count]))
            "app-db :count survives — the wrong-frame slice was NOT installed")
        (is (= "pre-hydration" (rf/subscribe-once client-frame [:title]))
            "app-db :title survives — the wrong-frame slice was NOT installed")
        ;; runtime-db partition untouched (no hydration metadata, no machine
        ;; snapshots from the rejected payload).
        (is (false? (rf/subscribe-once client-frame [:hydrated?]))
            "no hydration metadata stashed — the runtime-db partition is left unchanged")
        (is (nil? (get-in (rf/runtime-db-value client-frame)
                          [:rf.runtime/machines :snapshots]))
            "the payload's runtime-db slice did NOT land — runtime-db untouched")
        ;; the structured mismatch surfaced, carrying the two frames.
        (let [mismatch (first (filter #(= :rf.error/hydration-frame-id-mismatch
                                          (:operation %))
                                      traces))]
          (is (some? mismatch)
              (str "must emit :rf.error/hydration-frame-id-mismatch; saw: "
                   (pr-str (mapv :operation traces))))
          (when mismatch
            (is (= client-frame (-> mismatch :tags :target-frame))
                ":target-frame is the dispatch target frame")
            (is (= :some/other-frame (-> mismatch :tags :payload-frame-id))
                ":payload-frame-id is the payload's (server) frame stamp")))))))

;; ===========================================================================
;; rf2-7qbxbm / rf2-mrtis6 census B5 — the always-on corpus leg of the
;; frame-id-mismatch rejection routes the UNTRUSTED deserialised
;; :payload-frame-id through project-egress, so a frame that declares it
;; sensitive does NOT ship it raw to off-box corpus listeners (Sentry /
;; Datadog). The dev trace (DCE'd in production) keeps the raw value.
;; ===========================================================================

(deftest mismatch-always-on-record-redacts-sensitive-payload-frame-id
  (testing "rf2-7qbxbm/B5: when the rejected frame declares the
            :payload-frame-id path :sensitive, the ALWAYS-ON corpus record
            (the production-surviving off-box-shipper leg) carries the value
            REDACTED — it routes through project-egress — even though the dev
            trace keeps it raw. The sensitive deserialised payload value never
            fans out to a corpus listener raw."
    (register-baseline-handlers!)
    ;; EP-0025 B4-ssr follow-on (rf2-ux7983): the rejected client frame declares
    ;; the untrusted `:payload-frame-id` slot :sensitive through the post-purge
    ;; mechanism — a B3 COMMIT-PLANE `:sensitive` effect the frame's init event
    ;; returns alongside `:db` (EP-0025 §How it works / §Examples) — writing it
    ;; into the per-frame `[:rf.runtime/elision]` registry. So project-egress
    ;; redacts it on the off-box leg, replacing the retired
    ;; `:sensitive {:app-db}` durable annotation (deleted by the B1b purge).
    (rf/reg-event :rf.b5/classify
      (fn [_ _] {:sensitive [[:payload-frame-id]]}))
    (let [;; the rejected client frame declares the untrusted payload slot
          ;; :sensitive — so project-egress redacts it on the off-box leg.
          client-frame (frame/make-anon-frame-record!
                         {:doc            "B5 sensitive payload-frame-id client"
                          :platform       :client
                          :initial-events [[:rf.b5/classify]]})
          ;; the corpus-listener stand-in (the off-box shipper) records the
          ;; ALWAYS-ON union record.
          corpus       (atom [])
          _            (rf/register-listener! :errors ::b5-corpus
                         (fn [record] (swap! corpus conj record)))
          payload      {:rf/frame-id    :secret/other-frame
                        :rf/app-db      {:count 42}
                        :rf/render-hash "deadbeef"}
          dev-traces   (capture-traces!
                         (fn []
                           (rf/dispatch-sync [:rf/hydrate payload]
                                             {:frame client-frame})))]
      (try
        (let [record   (first (filter #(= :rf.error/hydration-frame-id-mismatch
                                          (:error %))
                                      @corpus))
              dev-trace (first (filter #(= :rf.error/hydration-frame-id-mismatch
                                           (:operation %))
                                       dev-traces))]
          (is (some? record)
              (str "the frame-id-mismatch fanned out on the ALWAYS-ON axis; "
                   "saw: " (pr-str (mapv :error @corpus))))
          (when record
            (testing "the ALWAYS-ON corpus record redacts the untrusted payload value"
              (is (= :rf/redacted (:payload-frame-id record))
                  (str ":payload-frame-id is redacted on the always-on record "
                       "(routed through project-egress); got "
                       (pr-str (:payload-frame-id record))))
              (is (not= :secret/other-frame (:payload-frame-id record))
                  "the raw deserialised payload frame-id does NOT ride the corpus record")
              (is (not (re-find #"secret/other-frame" (pr-str record)))
                  "no raw :secret/other-frame value survives anywhere in the corpus record")))
          (when dev-trace
            (testing "the dev trace (DCE'd in production) keeps the raw value for local fidelity"
              (is (= :secret/other-frame (-> dev-trace :tags :payload-frame-id))
                  "the dev-trace tags carry the raw payload frame-id (the leak is off-box, not local)"))))
        (finally
          (rf/unregister-listener! :errors ::b5-corpus))))))

(deftest direct-dispatch-matching-frame-id-hydrates-normally
  (testing "rf2-nv3mua: a direct dispatch whose :rf/frame-id MATCHES the
            dispatch target installs the slice normally (the validation is
            precise — it rejects only present-and-DIFFERENT, never a match)."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "nv3mua matching-frame client"
                                       :platform :client})
          ;; Stamp the payload's :rf/frame-id with the SAME frame we hydrate
          ;; into — server + client agree, so the slice lands. A render-hash
          ;; is carried so hydration metadata is stashed (the :hydrated? proof).
          payload      {:rf/frame-id    client-frame
                        :rf/app-db      {:count 7 :title "seeded"}
                        :rf/render-hash "deadbeef"}]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          "matching frame-id → the server slice installed")
      (is (= "seeded" (rf/subscribe-once client-frame [:title]))
          "matching frame-id → :title seeded from the payload")
      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          "hydration metadata stashed — the hydrate proceeded"))))

(deftest direct-dispatch-absent-frame-id-hydrates-normally
  (testing "rf2-nv3mua: a direct dispatch whose payload carries NO
            :rf/frame-id is no conflict — the dispatch target stands and the
            slice installs (the documented client-only / no-server-slice
            fallback shape). This is the path the baseline tests rely on."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "nv3mua absent-frame-id client"
                                       :platform :client})
          ;; Deliberately NO :rf/frame-id key.
          payload      {:rf/app-db {:count 5 :title "no-frame-id"}}]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
      (is (= 5 (rf/subscribe-once client-frame [:count]))
          "absent frame-id → the slice installed (no conflict)")
      (is (= "no-frame-id" (rf/subscribe-once client-frame [:title]))
          "absent frame-id → :title seeded"))))

(deftest boot-hydrate-render-tree-fn-is-synchronous-and-post-seed
  (testing "rf2-3w6dmy finding 1: hydrate!'s VERIFY contract is
            seed-and-synchronously-compute-tree — :render-tree-fn is called
            SYNCHRONOUSLY, BEFORE hydrate! returns, and AFTER :rf/hydrate
            seeded app-db (so the pure client tree it computes reflects the
            hydrated slice). It is NOT a post-mount/post-render callback: no
            host render happens between :rf/hydrate and the call. This locks
            the chosen contract in maintainer-visible terms."
    (register-baseline-handlers!)
    (let [client-frame (frame/make-anon-frame-record! {:doc "boot-helper sync-contract frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          ;; Records WHEN render-tree-fn ran + WHAT app-db it saw.
          called?       (atom false)
          seen-count    (atom ::not-called)
          ;; EP-0002 (rf2-acjknb): stamp the payload's :rf/frame-id with the
          ;; SAME frame the client hydrates into, so the carried-frame
          ;; validation agrees (a :rf/default stamp would now raise
          ;; :rf.error/hydration-frame-id-mismatch against client-frame).
          payload       (build-server-payload
                          client-frame {:count 11 :title "seeded"}
                          "server00"
                          {:version 1 :payload [:count :title]})
          returned      (ssr/hydrate!
                          {:frame          client-frame
                           :payload        payload
                           :render-tree-fn (fn []
                                             (reset! called? true)
                                             ;; The fn reads the frame's
                                             ;; app-db — which :rf/hydrate has
                                             ;; ALREADY seeded by the time the
                                             ;; verify step calls it.
                                             (reset! seen-count
                                                     (:count (rf/app-db-value client-frame)))
                                             [:div.app [:span "client-render"]])})]
      ;; SYNCHRONOUS: render-tree-fn ran during the hydrate! call, so the
      ;; flag is already true once hydrate! has returned — no deferred
      ;; tick, no host render boundary in between.
      (is (true? @called?)
          ":render-tree-fn was called synchronously within hydrate! (not deferred)")
      ;; POST-SEED: it observed the HYDRATED app-db, proving the call lands
      ;; after :rf/hydrate (step 2) — the pure client tree it computes is
      ;; the projection of the server's slice the host is about to mount.
      (is (= 11 @seen-count)
          ":render-tree-fn ran AFTER :rf/hydrate seeded app-db (saw :count 11)")
      ;; The helper returned the applied payload as documented.
      (is (= payload returned)
          "hydrate! returned the applied payload"))))
