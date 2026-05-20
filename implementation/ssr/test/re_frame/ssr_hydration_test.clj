(ns re-frame.ssr-hydration-test
  "Per rf2-pxb7t · Wave 3 of rf2-tglku (Migration-Audit §ssr_basic).

  The pre-migration Playwright spec at `testbeds/ssr_basic/spec.cjs`
  drove the SSR hydration baseline through a real browser load: read
  the baked `<script id=\"__rf_payload\">`, dispatch `:rf/hydrate`,
  render, observe seeded state + post-hydrate dispatch interactivity
  + the per-request `:rf/response` round-trip + trace-bus emission
  patterns.

  Every load-bearing assertion is platform-neutral — the contract
  surface (the `:rf/hydrate` handler, the `:rf/hydration` metadata,
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
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; The payload the testbed's `<script id=\"__rf_payload\">` bakes verbatim
;; (testbeds/ssr_basic/index.html lines 58-73). Pinning the literal here
;; keeps the JVM-side migration anchored to the wire shape the (now
;; deleted) Playwright spec observed.
(def ^:private baseline-payload
  {:rf/version     1
   :rf/frame-id    :rf/default
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
  (rf/reg-event-db ::inc
    (fn [db _ev] (update db :count (fnil inc 0))))
  (rf/reg-event-db ::set-title
    (fn [db [_ t]] (assoc db :title t)))
  (rf/reg-sub :count       (fn [db _] (or (:count db) 0)))
  (rf/reg-sub :title       (fn [db _] (or (:title db) "untitled")))
  (rf/reg-sub :server-resp (fn [db _] (:server-response db)))
  (rf/reg-sub :hydrated?   (fn [db _] (boolean (:rf/hydration db)))))

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
    (rf/register-trace-listener! cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-trace-listener! cb-id)))
    @traces))

;; ===========================================================================
;; spec.cjs §(2)+(3) → hydrated marker + seeded state from payload
;; ===========================================================================

(deftest hydration-baseline-replaces-app-db-and-stashes-metadata
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertions #3-#5.
            :rf/hydrate replaces app-db with the payload's :rf/app-db
            (Spec 011 §The :rf/hydrate event — `:replace-app-db` policy),
            stashes the version + nil server-hash under :rf/hydration,
            and the :hydrated? / :count / :title subs read the
            post-hydrate values via subscribe-once (no view re-render
            machinery needed; the contract is the app-db state)."
    (register-baseline-handlers!)
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          ":hydrated? reads true once :rf/hydration metadata lands in app-db")
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          "seeded :count from payload's :rf/app-db wins")
      (is (= "seeded" (rf/subscribe-once client-frame [:title]))
          "seeded :title from payload's :rf/app-db wins")
      ;; Lock the :rf/hydration metadata shape (the testbed's view
      ;; doesn't read these slots, but downstream tooling — Causa /
      ;; the late-bind compatibility-check fxs — does).
      (let [db (rf/get-frame-db client-frame)]
        (is (= 1 (get-in db [:rf/hydration :version]))
            ":rf/version rides on the :rf/hydration metadata block")
        (is (not (contains? (:rf/hydration db) :server-hash))
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
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
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
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
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
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
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

(deftest hydration-baseline-no-mismatch-trace-when-server-hash-nil
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertion #13.
            Per Spec 011 §Hydration-mismatch detection:
            verify-hydration! short-circuits when the server hash is
            nil — there is nothing to compare against. No
            :rf.ssr/hydration-mismatch trace fires on the baseline
            surface (its payload's :rf/render-hash is nil)."
    (register-baseline-handlers!)
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
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
