(ns re-frame.ssr-hydration-test
  "Per rf2-pxb7t · Wave 3 of rf2-tglku (Migration-Audit §ssr_basic).

  The pre-migration Playwright spec at `testbeds/ssr_basic/spec.cjs`
  drove the SSR hydration baseline through a real browser load: read
  the baked `<script id=\"__rf_payload\">`, dispatch `:rf/hydrate`,
  render, observe seeded state + post-hydrate dispatch interactivity
  + the per-request `:rf/response` round-trip + trace-bus emission
  patterns.

  Every load-bearing assertion is platform-neutral — the contract
  surface (the `:rf/hydrate` handler, the [:rf/runtime :ssr :hydration] metadata,
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
            [re-frame.ssr.payload-policy :as payload-policy]
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
  (rf/reg-sub :hydrated?   (fn [db _] (boolean (get-in db [:rf/runtime :ssr :hydration])))))

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
    (rf/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! cb-id)))
    @traces))

;; ===========================================================================
;; spec.cjs §(2)+(3) → hydrated marker + seeded state from payload
;; ===========================================================================

(deftest hydration-baseline-replaces-app-db-and-stashes-metadata
  (testing "Migrated from testbeds/ssr_basic/spec.cjs assertions #3-#5.
            :rf/hydrate replaces app-db with the payload's :rf/app-db
            (Spec 011 §The :rf/hydrate event — `:replace-app-db` policy),
            stashes the version + nil server-hash under
            [:rf/runtime :ssr :hydration],
            and the :hydrated? / :count / :title subs read the
            post-hydrate values via subscribe-once (no view re-render
            machinery needed; the contract is the app-db state)."
    (register-baseline-handlers!)
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      (is (true? (rf/subscribe-once client-frame [:hydrated?]))
          ":hydrated? reads true once hydration metadata lands at [:rf/runtime :ssr :hydration]")
      (is (= 7 (rf/subscribe-once client-frame [:count]))
          "seeded :count from payload's :rf/app-db wins")
      (is (= "seeded" (rf/subscribe-once client-frame [:title]))
          "seeded :title from payload's :rf/app-db wins")
      ;; Lock the [:rf/runtime :ssr :hydration] metadata shape (the
      ;; testbed's view doesn't read these slots, but downstream tooling
      ;; — Xray / the late-bind compatibility-check fxs — does).
      (let [db (rf/app-db-value client-frame)]
        (is (= 1 (get-in db [:rf/runtime :ssr :hydration :version]))
            ":rf/version rides on the hydration metadata block")
        (is (not (contains? (get-in db [:rf/runtime :ssr :hydration]) :server-hash))
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
    (let [server-frame (rf/make-frame {:doc "ssr-basic server frame"
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
    (let [client-frame (rf/make-frame {:doc "ssr-basic client frame"
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
    (let [client-frame (rf/make-frame {:doc "boot-helper client frame"
                                       :platform :client})
          ;; Server side: build the payload from the authoritative app-db
          ;; slice via the same payload-policy path the Ring adapter uses.
          server-app-db {:count 7 :title "seeded"
                         :server-only/auth "SECRET"}
          payload       (build-server-payload
                          :rf/default server-app-db "deadbeef"
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
    (let [client-frame (rf/make-frame {:doc "boot-helper client-only frame"
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
            against the :render-tree-fn AFTER dispatching :rf/hydrate.
            When the client render-tree hash != the server hash carried on
            the payload, :rf.ssr/hydration-mismatch fires — the boot helper
            wires the post-render mismatch detection symmetric with the
            server's :emit-hash? marker."
    (register-baseline-handlers!)
    (rf/reg-view* ::boot-root (fn [] [:div.app [:span "client-render"]]))
    (let [client-frame (rf/make-frame {:doc "boot-helper verify frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          ;; Server hash is a DELIBERATELY divergent value so the verify
          ;; step's comparison fails — proving the verify step actually ran.
          payload       (build-server-payload
                          :rf/default {:count 7 :title "seeded"}
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
    (let [client-frame (rf/make-frame {:doc "boot-helper verify-match frame"
                                       :platform :client
                                       :ssr {:detect-mismatch? true}})
          client-tree   [:div.app [:span "client-render"]]
          ;; Compute the server hash from the SAME tree so the round-trip
          ;; hashes agree — the happy path.
          matched-hash  (ssr/render-tree-hash client-tree)
          payload       (build-server-payload
                          :rf/default {:count 7 :title "seeded"}
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
