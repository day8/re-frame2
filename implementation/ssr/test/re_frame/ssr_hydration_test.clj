(ns re-frame.ssr-hydration-test
  "The SSR hydration baseline on the JVM: the `testbeds/ssr_basic`
  payload through `:rf/hydrate`, seeded state + post-hydrate dispatch
  interactivity + the per-request `:rf/response` round-trip + trace-bus
  emission patterns.

  Every load-bearing assertion is platform-neutral — the contract
  surface (the `:rf/hydrate` handler, the [:rf.runtime/ssr :hydration] metadata,
  the compatibility-check fxs, `verify-hydration!`, the
  `:rf/response` shape) lives in `re-frame.ssr.hydrate` and
  surrounding sub-namespaces, which are `.cljc` — so these tests use
  the JVM SSR-test conventions
  (`tf/reset-runtime` + `rf/make-frame` + `rf/dispatch-sync` +
  `rf/subscribe-once` for synchronous reads).

  ## Posture split

  The hydration CONTRACT is production-real throughout and is asserted here
  without a posture guard: which slice replaces app-db, which is rejected,
  that a rejected payload leaves BOTH partitions untouched and stashes no
  metadata, that there is no plain `:app-db` alias, that
  `hydrate!` establishes frame scope for its `:render-tree-fn`. All of it
  holds under `-Dre-frame.debug=false`.

  What is posture-dependent is the
  DIAGNOSTIC that accompanies each rejection. `:rf.error/malformed-hydration-
  payload`, `:rf.error/hydration-frame-id-mismatch`,
  `:rf.ssr/hydration-mismatch` and the `:rf.ssr/version-mismatch` family all
  emit behind `interop/debug-enabled?`, read once at namespace-load time, so
  under the gate the framework announces none of it. Fail-CLOSED is the
  contract; the trace is how a developer hears about it. Every such assertion
  sits inside a `(when interop/debug-enabled? …)` arm marked as a
  dev-instrumentation arm.

  The larger half of this split is the NEGATIVE trace assertions, which
  cannot fail under the gate — which is exactly why they sit there. A dozen
  `(is (not-any? … @traces))` / `(is (empty? (filter … @traces)))` forms say,
  variously, that the well-formed payload did NOT trip the malformed
  diagnostic, that matching hashes produced no mismatch, that the server-side
  gate emitted no skipped-on-platform warning. Under the gate the ring is
  empty for every input, so each would be satisfied without distinguishing
  the case it exists to distinguish. They sit in the dev arms with their
  positive counterparts.

  Three claims would be observable ONLY through the trace, so each has a
  production-visible witness instead of being guarded away:

    - whether the `:rf.ssr/check-*` fxs were ENQUEUED. Both the server-side
      skip and the client-side counter-test re-register the two fx ids
      with a recording stub and read the recorded dispatches directly. That
      is a stronger statement than either the absence of a
      `:rf.fx/skipped-on-platform` warning or the presence of a
      `:rf.ssr/version-mismatch`, and it holds in both postures.
    - whether `verify-hydration!` SHORT-CIRCUITS on a nil server hash. The
      test drives that condition on a frame carrying `:ssr {:on-mismatch
      :hard-error}`, where a detected mismatch escalates to a throw — an
      always-on channel, since `hydrate.cljc` builds one shared payload for
      the trace and the throw alike. A nil server hash must not throw.
      Whether `hydrate!`'s verify step RUNS is witnessed the same way, in both
      postures, by `re-frame.ssr-hydrate-frame-value-test`: a divergent hash
      throws there and a faithful one does not.

  `mismatch-always-on-record-redacts-sensitive-payload-frame-id` reads
  the ALWAYS-ON `:errors` axis and guards its dev-trace half with
  `(when dev-trace …)`. It is the shape the rest of this file follows."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; A production-visible probe for "were the compatibility-check
;; fxs ENQUEUED?".  Re-registers the two `:rf.ssr/check-*` fx ids with a
;; DECORATOR that records the dispatch and then delegates to the real
;; handler-fn, so the observation leaves the checks in place: they run and
;; emit their dev traces, and the deftests' dev arms assert them.  The
;; `tf/reset-runtime` fixture restores the untouched registrations between
;; deftests.
;;
;; The point is that the handler's platform gate is a statement about the
;; EFFECT QUEUE, and this reads the queue.  Inferring it from the presence or
;; absence of a `:rf.fx/skipped-on-platform` / `:rf.ssr/version-mismatch`
;; trace cannot tell "never enqueued" from "warning elided", which is
;; precisely the distinction that disappears under `-Dre-frame.debug=false`.
;;
;; Returns the recording atom: a vector of `[fx-id value]`.
(defn- record-check-fx-dispatches! []
  (let [seen (atom [])]
    (doseq [id [:rf.ssr/check-version :rf.ssr/check-schema-digest]]
      (let [slot (rf.registrar/lookup :fx id)
            real (:handler-fn slot)]
        (rf.fx/reg-fx id
                   (select-keys slot [:platforms :schema])
                   (fn [frame-id v]
                     (swap! seen conj [id v])
                     (when real (real frame-id v))))))
    seen))

;; The payload the testbed's `<script id=\"__rf_payload\">` bakes verbatim
;; (testbeds/ssr_basic/index.html lines 58-73). Pinning the literal here
;; keeps these JVM tests anchored to the testbed's wire shape.
;; NO `:rf/frame-id` key, unlike the testbed's `:rf/frame-id :rf/default`:
;; these JVM tests dispatch the payload into a
;; freshly-`make-frame`'d `client-frame` (NOT `:rf/default`), and the
;; `:rf/hydrate` handler fails CLOSED on a present-and-different `:rf/frame-id`,
;; so a literal `:rf/default` stamp against a synthetic
;; `client-frame` would (correctly) be rejected as a frame-id mismatch. An
;; absent `:rf/frame-id` is the documented no-conflict shape — the dispatch
;; target stands — which is what these baseline tests intend (the testbed
;; itself hydrates `:rf/default` and matches). The frame-id
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
  ;; EP-0001: the SSR hydration metadata is durable runtime-db
  ;; state, so :hydrated? is a runtime-db sub.
  (rf.subs/reg-runtime-sub :hydrated? (fn [rt _] (boolean (get-in rt [:rf.runtime/ssr :hydration])))))

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

;; ===========================================================================
;; hydrated marker + seeded state from payload
;; ===========================================================================

(deftest hydration-baseline-replaces-app-db-and-stashes-metadata
  (testing ":rf/hydrate replaces app-db with the payload's :rf/app-db
            (Spec 011 §The :rf/hydrate event — `:replace-app-db` policy),
            stashes the version + nil server-hash under
            [:rf.runtime/ssr :hydration],
            and the :hydrated? / :count / :title subs read the
            post-hydrate values via subscribe-once (no view re-render
            machinery needed; the contract is the app-db state)."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      (is (true? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
          ":hydrated? reads true once hydration metadata lands at [:rf.runtime/ssr :hydration]")
      (is (= 7 (rf/subscribe-once [:count] {:frame client-frame}))
          "seeded :count from payload's :rf/app-db wins")
      (is (= "seeded" (rf/subscribe-once [:title] {:frame client-frame}))
          "seeded :title from payload's :rf/app-db wins")
      ;; Lock the [:rf.runtime/ssr :hydration] metadata shape (the
      ;; testbed's view doesn't read these slots, but downstream tooling
      ;; — Xray / the late-bind compatibility-check fxs — does).
      ;; EP-0001: the hydration metadata is durable runtime-db state.
      (let [rt (:rf.db/runtime (rf/frame-state-value client-frame))]
        (is (= 1 (get-in rt [:rf.runtime/ssr :hydration :version]))
            ":rf/version rides on the hydration metadata block")
        (is (not (contains? (get-in rt [:rf.runtime/ssr :hydration]) :server-hash))
            "nil :rf/render-hash is pruned from the metadata block")))))

;; ===========================================================================
;; reactive substrate is live post-hydrate
;; ===========================================================================

(deftest hydration-baseline-post-hydrate-dispatch-mutates-seeded-db
  (testing "The post-hydrate dispatch path (event → db → sub) is live —
            ::inc bumps the seeded :count, ::set-title overwrites the
            seeded :title. Proves the six-domino loop survives the
            hydration handoff intact (subscribe-once reads
            the post-drain app-db directly)."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      ;; ::inc — bumps the seeded :count 7 → 8
      (rf/dispatch-sync [::inc] {:frame client-frame})
      (is (= 8 (rf/subscribe-once [:count] {:frame client-frame}))
          "post-hydrate ::inc bumps the seeded :count via the live
           event-handler → db-update → sub-recompute pipeline")

      ;; ::set-title — overwrites the seeded :title slot
      (rf/dispatch-sync [::set-title "hydrated"] {:frame client-frame})
      (is (= "hydrated" (rf/subscribe-once [:title] {:frame client-frame}))
          "post-hydrate ::set-title overwrites the seeded :title"))))

;; ===========================================================================
;; per-request :rf/response slice round-trips through the payload
;; ===========================================================================

(deftest hydration-baseline-rf-response-slice-round-trips-via-payload
  (testing "Per Spec 011 §The hydration payload: the payload may
            carry an optional :rf/response slice (status, headers,
            cookies, redirect). The testbed's client-side
            `materialise-response` hoists it into app-db at
            [:server-response] for the view layer. The contract
            asserted here: payload → app-db → sub round-trip is loss-
            less for the four fields the testbed's view renders
            (resp-status, resp-ct, resp-cookies-count,
            resp-cookie-name)."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
      (let [resp (rf/subscribe-once [:server-resp] {:frame client-frame})]
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
;; version check silently matches the SSR constant
;; ===========================================================================

(deftest hydration-baseline-version-matches-ssr-constant-silently
  (testing "There is no late-bind version hook: the SSR artefact owns the
            pattern-protocol version.
            The baseline payload ships :rf/version = the SSR artefact's
            compiled-in pattern-protocol constant, so the :rf.ssr/check-version
            fx the :rf/hydrate handler dispatches resolves the client-side
            'actual' from that SAME constant and silently matches — NO
            :rf.ssr/compatibility-check-skipped
            and NO :rf.ssr/version-mismatch. Best-effort, degraded-but-running,
            never crash (Spec 011 §The :rf/hydrate event)."
    (register-baseline-handlers!)
    ;; The baseline payload's version is the SSR-owned constant (the wire
    ;; shape the testbed baked), so the scalar version check compares equal.
    (is (= rf.ssr.payload-policy/pattern-protocol-version (:rf/version baseline-payload))
        "the baseline payload ships the v1 pattern-protocol version")
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
        ;; Dev-instrumentation arm (see ns docstring). BOTH are
        ;; negatives over the trace ring: vacuous under the gate, where a
        ;; skewed version would look identical to a matching one.
        (when rf.interop/debug-enabled?
          (is (empty? (filter #(= :rf.ssr/compatibility-check-skipped (:operation %)) @traces))
              (str "version check resolves via the SSR constant → never skipped; "
                   "saw operations: " (pr-str (mapv :operation @traces))))
          (is (empty? (filter #(= :rf.ssr/version-mismatch (:operation %)) @traces))
              "payload :rf/version == the SSR constant → silent match"))))))

;; ===========================================================================
;; server-side :rf/hydrate skips the client-only check fxs
;; ===========================================================================

(deftest hydration-on-server-platform-skips-client-only-check-fxs
  (testing "When :rf/hydrate runs on a frame whose resolved
            platform is :server (test harness, isomorphic loopback), the
            handler MUST NOT enqueue the :rf.ssr/check-version /
            :rf.ssr/check-schema-digest fxs. The fxs themselves carry
            :platforms #{:client}, so if the handler enqueued them
            unconditionally the fx-platform-gate would fire a
            :rf.fx/skipped-on-platform warning per check on every server-
            side hydrate. The handler-level gate prevents that noise."
    (register-baseline-handlers!)
    (let [server-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic server frame"
                                       :platform :server})
          ;; Add a :rf/schema-digest so BOTH checks would fire if the
          ;; handler enqueued them; otherwise only :rf.ssr/check-version
          ;; would and the test couldn't distinguish "gated by handler"
          ;; from "absent because no schema-digest".
          payload      (-> baseline-payload
                           (assoc :rf/schema-digest "test-digest-abc")
                           materialise-response)]
      (let [dispatched (record-check-fx-dispatches!)]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:rf/hydrate payload] {:frame server-frame})
          ;; SEMANTIC, posture-independent: the handler-level gate
          ;; is a statement about the EFFECT QUEUE, so read the queue.
          ;; Inferring it from the absence of a dev warning
          ;; cannot tell "never enqueued" from "warning elided".
          (is (= [] @dispatched)
              (str "the handler enqueued NEITHER :rf.ssr/check-* fx on a "
                   ":server-platform :rf/hydrate; saw: " (pr-str @dispatched)))
          ;; Sanity: the handler landed the app-db swap + metadata —
          ;; the gate skipped only the check-fx dispatches, not the rest.
          (is (= 7 (rf/subscribe-once [:count] {:frame server-frame}))
              ":rf/app-db applied on the server-side run")
          ;; Dev-instrumentation arm (see ns docstring).
          (when rf.interop/debug-enabled?
            (let [skipped-checks
                  (filter (fn [ev]
                            (and (= :rf.fx/skipped-on-platform (:operation ev))
                                 (#{:rf.ssr/check-version :rf.ssr/check-schema-digest}
                                  (-> ev :tags :rf.fx/id))))
                          @traces)]
              (is (empty? skipped-checks)
                  (str "expected zero :rf.fx/skipped-on-platform traces for the two "
                       ":rf.ssr/check-* fxs on a :server-platform :rf/hydrate; saw: "
                       (pr-str (mapv (juxt :operation #(-> % :tags :rf.fx/id))
                                     skipped-checks)))))))))))

(deftest hydration-on-client-platform-still-dispatches-check-fxs
  (testing "Counter-test to the server-side skip: on a
            :client-platform frame the handler MUST enqueue the
            check fxs so legitimate client-side mismatches surface. The
            version check's client-side 'actual' is the
            SSR artefact's compiled-in constant, so a payload whose
            :rf/version DIFFERS from it emits :rf.ssr/version-mismatch —
            observable proof the :rf.ssr/check-version fx dispatched on the
            client frame (the server-side gate did NOT over-skip on :client).
            Without this counter-assertion the server-side gate could
            silently strip the client code path."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          ;; Skew the payload version away from the SSR constant so the
          ;; version check has something to report on the client frame.
          payload      (-> baseline-payload
                           (assoc :rf/version (inc rf.ssr.payload-policy/pattern-protocol-version))
                           materialise-response)]
      (let [dispatched (record-check-fx-dispatches!)]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
          ;; SEMANTIC, posture-independent: the counter-test's
          ;; claim is that the server-side gate did NOT over-skip on
          ;; :client — i.e. the fx was ENQUEUED. Read the effect queue, which
          ;; says so directly and in both postures; the mismatch trace is
          ;; only a proxy for it.
          (is (contains? (set (map first @dispatched)) :rf.ssr/check-version)
              (str "on :client the handler still enqueued :rf.ssr/check-version; "
                   "saw: " (pr-str @dispatched)))
          (is (= (inc rf.ssr.payload-policy/pattern-protocol-version)
                 (some (fn [[id v]] (when (= :rf.ssr/check-version id) v)) @dispatched))
              "…carrying the payload's skewed :rf/version as its argument")
          ;; Dev-instrumentation arm (see ns docstring).
          (when rf.interop/debug-enabled?
            (let [mismatch (filter #(= :rf.ssr/version-mismatch (:operation %)) @traces)]
              (is (seq mismatch)
                  (str "on :client the :rf.ssr/check-version fx still fires and "
                       "emits :rf.ssr/version-mismatch on a skewed :rf/version; "
                       "saw operations: " (pr-str (mapv :operation @traces)))))))))))

(deftest hydration-baseline-no-mismatch-trace-when-server-hash-nil
  (testing "Per Spec 011 §Hydration-mismatch detection:
            verify-hydration! short-circuits when the server hash is
            nil — there is nothing to compare against. No
            :rf.ssr/hydration-mismatch trace fires on the baseline
            surface (its payload's :rf/render-hash is nil)."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                       :platform :client})
          payload      (materialise-response baseline-payload)]
      (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})

      (with-trace-recorder! [traces]
        ;; Simulate the testbed's post-render
        ;; verify-hydration! call. The resolved tree is
        ;; opaque here — we pass a synthetic 8-hex
        ;; "client hash" to mirror the call shape; the
        ;; nil server-hash on the metadata block makes
        ;; the call a no-op regardless of the client
        ;; value (Spec 011 — `(when (and server-hash
        ;; client-hash ...) ...)` short-circuits).
        (rf.ssr/verify-hydration! client-frame "abcdef01")
        ;; Dev-instrumentation arm (see ns docstring). A NEGATIVE
        ;; over the trace ring, and this deftest's ONLY assertion: under the
        ;; gate it would have passed without the short-circuit existing.
        (when rf.interop/debug-enabled?
          (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %)) @traces)
              (str "no :rf.ssr/hydration-mismatch on the baseline (server-"
                   "hash was nil); saw: "
                   (pr-str (mapv :operation @traces))))))

      ;; SEMANTIC, posture-independent: drive the SAME nil-server-
      ;; hash condition on a frame that asks for `:ssr {:on-mismatch
      ;; :hard-error}`. A detected mismatch escalates to a throw — always-on,
      ;; since hydrate.cljc builds one shared payload for the trace and the
      ;; throw alike. Nothing thrown means the comparison genuinely
      ;; short-circuited rather than merely failing to announce itself.
      (let [strict-frame (rf.frame/make-anon-frame-record!
                           {:doc      "nil-server-hash strict frame"
                            :platform :client
                            :ssr      {:on-mismatch :hard-error}})]
        (rf/dispatch-sync [:rf/hydrate (materialise-response baseline-payload)]
                          {:frame strict-frame})
        (is (nil? (rf.ssr/verify-hydration! strict-frame "abcdef01"))
            "a nil server-hash short-circuits BEFORE the comparison — even
             :on-mismatch :hard-error has nothing to escalate")))))

;; ===========================================================================
;; fail CLOSED on a malformed / untrusted hydration payload
;; ===========================================================================
;;
;; The payload is a DESERIALISED, UNTRUSTED transport input (the server's
;; `pr-str`'d EDN). `:replace-app-db` is the merge policy, so a
;; non-map payload — or a present-but-non-map app-db slice — would
;; otherwise be installed as the ENTIRE client app-db (a fail-OPEN). The
;; handler REJECTS it: existing app-db unchanged + a
;; `:rf.error/malformed-hydration-payload` diagnostic. This drives the
;; rejection through the REAL `dispatch-sync` router (end-to-end), the
;; companion to the direct-handler invariant in
;; `re-frame.security.fail-closed-invariant-security-cljs-test`.

(deftest malformed-hydration-payload-fails-closed-through-router
  (testing "A non-map payload, or a present-but-non-map app-db
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
      (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-basic client frame"
                                         :platform :client})]
        ;; Seed a recognisable pre-hydration client slice so we can prove
        ;; it SURVIVES (was not replaced by the malformed payload).
        (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
        (rf/dispatch-sync [::inc] {:frame client-frame})  ;; count 0 → 1
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:rf/hydrate bad-payload] {:frame client-frame})
          (is (= "pre-hydration" (rf/subscribe-once [:title] {:frame client-frame}))
              (str (pr-str bad-payload)
                   " must NOT replace the client :title (fail closed)"))
          (is (= 1 (rf/subscribe-once [:count] {:frame client-frame}))
              (str (pr-str bad-payload)
                   " must NOT replace the client :count (fail closed)"))
          (is (false? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
              (str (pr-str bad-payload)
                   " must NOT stash hydration metadata (rejected, not applied)"))
          ;; Dev-instrumentation arm (see ns docstring). FAIL
          ;; CLOSED is the contract and is pinned by the three assertions
          ;; above, all posture-independent; the diagnostic is how a
          ;; developer learns which payload was rejected and why.
          (when rf.interop/debug-enabled?
            (is (some #(= :rf.error/malformed-hydration-payload (:operation %)) @traces)
                (str (pr-str bad-payload)
                     " must emit :rf.error/malformed-hydration-payload; saw: "
                     (pr-str (mapv :operation @traces))))))))))

;; ===========================================================================
;; fail CLOSED on a present-but-non-map :rf/runtime-db slice
;; ===========================================================================
;;
;; EP-0001: hydration installs a coherent FRAME-STATE —
;; `:rf/app-db` becomes the app-db partition AND `:rf/runtime-db` becomes
;; the runtime-db partition (machine snapshots, route slice, SSR metadata).
;; A guard validating ONLY the app-db slice would silently coerce a present-
;; but-non-map `:rf/runtime-db` (a corrupt / hostile / version-skewed
;; payload) to nil and drop it, then install a new app-db + hydration
;; metadata anyway — a partial hydration that
;; violates the spec's coherent-frame-state, fail-closed boundary (Spec 011
;; §The :rf/hydrate event — "Both partitions validate fail-closed before
;; installation"). The guard rejects it the SAME way as a non-map
;; app-db slice: both partitions left unchanged, no compatibility-check
;; fxs fire, and `:rf.error/malformed-hydration-payload` is emitted.

(deftest non-map-runtime-db-slice-fails-closed-through-router
  (testing "A payload carrying a present-but-non-map
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
    (rf.subs/reg-runtime-sub :machine-snapshots
      (fn [rt _] (get-in rt [:rf.runtime/machines :snapshots])))
    (doseq [bad-rt ["runtime-is-a-string"
                    [:runtime :is :a :vector]
                    42
                    false]]
      (let [client-frame (rf.frame/make-anon-frame-record! {:doc "non-map-runtime-db client frame"
                                         :platform :client})]
        ;; Seed recognisable pre-hydration state in BOTH partitions.
        (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
        (rf/dispatch-sync [::inc] {:frame client-frame})       ;; count 0 → 1
        (rf/dispatch-sync [::seed-runtime] {:frame client-frame})
        (let [bad-payload {:rf/app-db   {:count 99 :title "would-replace"}
                           :rf/runtime-db bad-rt}]
          (with-trace-recorder! [traces]
            (rf/dispatch-sync [:rf/hydrate bad-payload] {:frame client-frame})
            ;; app-db partition unchanged — the valid :rf/app-db slice must
            ;; NOT land because the runtime-db slice made the payload malformed.
            (is (= "pre-hydration" (rf/subscribe-once [:title] {:frame client-frame}))
                (str (pr-str bad-rt)
                     " runtime-db slice must NOT let the app-db slice replace :title"))
            (is (= 1 (rf/subscribe-once [:count] {:frame client-frame}))
                (str (pr-str bad-rt)
                     " runtime-db slice must NOT let the app-db slice replace :count"))
            ;; runtime-db partition unchanged — seeded machine snapshot survives.
            (is (= {:m {:value :idle}}
                   (rf/subscribe-once [:machine-snapshots] {:frame client-frame}))
                (str (pr-str bad-rt)
                     " must leave the runtime-db partition (machine snapshot) unchanged"))
            ;; no hydration metadata stashed (rejected, not applied).
            (is (false? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
                (str (pr-str bad-rt)
                     " must NOT stash hydration metadata (rejected, not applied)"))
            ;; Dev-instrumentation arm (see ns docstring). Both
            ;; halves belong here: the malformed diagnostic FAILS under the
            ;; gate, and "no compatibility-check fxs on the rejected path" is
            ;; a negative over the same empty ring, so it would have PASSED
            ;; without the rejection short-circuit existing. The rejection
            ;; itself is pinned by the four partition assertions above.
            (when rf.interop/debug-enabled?
              ;; the malformed diagnostic fires.
              (is (some #(= :rf.error/malformed-hydration-payload (:operation %)) @traces)
                  (str (pr-str bad-rt)
                       " must emit :rf.error/malformed-hydration-payload; saw: "
                       (pr-str (mapv :operation @traces))))
              ;; no compatibility-check fxs fire on the rejected path.
              (is (not-any? #(#{:rf.ssr/version-mismatch
                                :rf.ssr/schema-digest-mismatch
                                :rf.ssr/compatibility-check-skipped}
                              (:operation %)) @traces)
                  (str (pr-str bad-rt)
                       " must NOT fire compatibility-check fxs on the rejected path")))))))))

(deftest wellformed-runtime-db-slice-still-installs-through-router
  (testing "The runtime-db guard is precise: a well-formed map
            :rf/runtime-db slice installs the runtime-db partition
            through the router and is not malformed. A wholly-absent
            :rf/runtime-db key is the no-server-runtime fallback, which
            `hydration-baseline-replaces-app-db-and-stashes-metadata`
            installs."
    (register-baseline-handlers!)
    (rf.subs/reg-runtime-sub :route-current
      (fn [rt _] (get-in rt [:rf.runtime/routing :current])))
    ;; A map runtime-db slice installs the runtime-db partition.
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "rt-ok client frame" :platform :client})
          payload {:rf/app-db     {:count 7 :title "seeded"}
                   :rf/runtime-db {:rf.runtime/routing {:current {:route-id :home}}}}]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
        (is (= 7 (rf/subscribe-once [:count] {:frame client-frame})) "app-db slice installed")
        (is (= {:route-id :home} (rf/subscribe-once [:route-current] {:frame client-frame}))
            "the runtime-db route slice rode the payload and installed")
        ;; Dev-instrumentation arm (see ns docstring). Vacuous
        ;; under the gate; the installed route slice above is the acceptance.
        (when rf.interop/debug-enabled?
          (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) @traces)
              "no malformed diagnostic on a well-formed two-partition payload"))))))

;; ===========================================================================
;; there is no plain :app-db hydration alias
;; ===========================================================================
;;
;; The hydrate handler reads ONLY `:rf/app-db` (re-frame.ssr.hydrate line
;; `new-db (or (:rf/app-db payload) db)`). This negative test pins that an
;; unqualified `:app-db` key is not an alias: an `(:app-db payload)`
;; fallback (the v1-era unqualified spelling) would turn it red, while the
;; happy-path tests — none of which dispatch an `:app-db`-keyed payload —
;; would stay green. The asserted behaviour: under the
;; documented open-map / no-slice semantics, a `{:app-db {…}}` payload is an
;; UNKNOWN no-slice payload (the `:rf/app-db` key is absent, so app-db is
;; left unchanged), NOT an alias that replaces app-db. It is also not
;; malformed — the payload is a map with no `:rf/app-db` key, which is the
;; legitimate client-only fallback shape.

(deftest plain-app-db-key-is-not-a-hydration-alias
  (testing "[:rf/hydrate {:app-db {…}}] must NOT replace app-db.
            The handler reads only :rf/app-db; there is no unqualified
            :app-db alias. It behaves as an unknown no-slice
            payload (app-db unchanged, client-only fallback), not as an alias."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "alias-dead client frame"
                                       :platform :client})]
      ;; Seed a recognisable pre-hydration client slice so we can prove it
      ;; SURVIVES (was not replaced by the :app-db-keyed payload).
      (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
      (rf/dispatch-sync [::inc] {:frame client-frame})  ;; count 0 → 1
      (with-trace-recorder! [traces]
        (rf/dispatch-sync
          [:rf/hydrate {:app-db {:count 99 :title "legacy"}}]
          {:frame client-frame})
        (is (= 1 (rf/subscribe-once [:count] {:frame client-frame}))
            "the plain :app-db key did NOT replace :count (there is no alias —
             the 99 from {:app-db {…}} must not land)")
        (is (= "pre-hydration" (rf/subscribe-once [:title] {:frame client-frame}))
            "the plain :app-db key did NOT replace :title (there is no alias —
             \"legacy\" must not land)")
        (is (false? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
            "no hydration metadata stashed — the :rf/render-hash / :rf/version
             keys are absent, so the no-slice payload installs no metadata")
        ;; Dev-instrumentation arm (see ns docstring). Vacuous
        ;; under the gate; the no-alias claim is pinned by the three
        ;; posture-independent assertions above.
        (when rf.interop/debug-enabled?
          (is (not-any? #(= :rf.error/malformed-hydration-payload (:operation %)) @traces)
              (str "a {:app-db {…}} payload is a map with no :rf/app-db key — the "
                   "legitimate client-only no-slice fallback, NOT malformed; saw: "
                   (pr-str (mapv :operation @traces)))))))))

;; ===========================================================================
;; client-side hydration boot helper (ssr/hydrate!)
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
  (rf.ssr.payload-policy/build-payload
    frame-id
    (rf.ssr.payload-policy/apply-policy app-db policy-opts)
    render-hash
    policy-opts))

(deftest boot-hydrate-nil-payload-is-client-only-noop
  (testing "ssr/hydrate! with no payload (nil — the client-only
            first-load shape) does NOT dispatch :rf/hydrate and returns
            nil. The caller renders against the empty app-db."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "boot-helper client-only frame"
                                       :platform :client})
          returned     (rf.ssr/hydrate! {:frame client-frame :payload nil})]
      (is (nil? returned)
          "nil payload → hydrate! returns nil (client-only first load)")
      (is (false? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
          "no hydration metadata stashed — :rf/hydrate was never dispatched")
      (is (= 0 (rf/subscribe-once [:count] {:frame client-frame}))
          "app-db is the empty default; the :count sub's fallback applies"))))

;; ===========================================================================
;; EP-0002: hydrate! requires :frame; payload :rf/frame-id is
;; VALIDATED against the explicit target (no :rf/default-from-absence, no
;; silent side-pick on a frame-id conflict).
;; ===========================================================================

(deftest boot-hydrate-absent-frame-raises-no-frame-context
  (testing "EP-0002: the client hydration target is carried —
            :frame is REQUIRED. Calling hydrate! with no :frame raises
            :rf.error/no-frame-context rather than synthesising :rf/default.
            The malformed-payload guard never runs (the absence fails first
            at the boundary)."
    (register-baseline-handlers!)
    (let [ex (try (rf.ssr/hydrate! {:payload {:rf/app-db {:count 1}}})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "hydrate! with no :frame must throw")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
          "an absent :frame surfaces :rf.error/no-frame-context"))))

(deftest boot-hydrate-frame-id-mismatch-raises-structured-error
  (testing "EP-0002: the payload's :rf/frame-id is validated
            against the explicit :frame target. A present-and-different
            frame-id (the server rendered under a DIFFERENT frame than the
            client is installing into) raises a structured
            :rf.error/hydration-frame-id-mismatch — the runtime never
            silently picks a side, and app-db is NOT replaced."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "mismatch client frame"
                                       :platform :client})
          ;; Payload stamped with a DIFFERENT frame id than the client target.
          other-frame  (rf.frame/make-anon-frame-record! {:doc "the server's other frame"
                                       :platform :server})
          payload      (build-server-payload
                         other-frame {:count 7 :title "seeded"} "deadbeef"
                         {:version 1 :payload [:count :title]})
          ex           (try (rf.ssr/hydrate! {:frame client-frame :payload payload})
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
      (is (= 0 (rf/subscribe-once [:count] {:frame client-frame}))
          "the mismatch is surfaced before the app-db replace; no slice landed"))))

;; ===========================================================================
;; the :rf/hydrate HANDLER enforces frame-id validation too, so
;; the direct-dispatch split path (`hydrate!`'s documented post-mount-verify
;; escape hatch) cannot bypass it. `hydrate!` validates+throws pre-dispatch
;; (covered above); these cover the handler boundary reached by a direct
;; `dispatch-sync [:rf/hydrate payload] {:frame target}`.
;; ===========================================================================

(deftest direct-dispatch-frame-id-mismatch-fails-closed
  (testing "A direct dispatch of [:rf/hydrate payload] whose
            present :rf/frame-id names a DIFFERENT frame than the dispatch
            target leaves app-db AND runtime-db unchanged and emits
            :rf.error/hydration-frame-id-mismatch — the handler will not
            silently install a server slice rendered for another frame.
            This is the bypass the handler check closes: hydrate! throws pre-dispatch,
            but a direct dispatch hits ONLY the handler."
    (register-baseline-handlers!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "nv3mua direct-dispatch client"
                                       :platform :client})]
      ;; Seed a recognisable pre-hydration client slice so we can prove it
      ;; SURVIVES (was not replaced by the wrong-frame payload).
      (rf/dispatch-sync [::set-title "pre-hydration"] {:frame client-frame})
      (rf/dispatch-sync [::inc] {:frame client-frame})  ;; count 0 → 1
      (let [payload {:rf/frame-id    :some/other-frame
                     :rf/app-db      {:count 42 :title "wrong-frame slice"}
                     :rf/runtime-db  {:rf.runtime/machines {:snapshots {:m :installed}}}
                     :rf/render-hash "deadbeef"}]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
          ;; app-db partition untouched (fail closed).
          (is (= 1 (rf/subscribe-once [:count] {:frame client-frame}))
              "app-db :count survives — the wrong-frame slice was NOT installed")
          (is (= "pre-hydration" (rf/subscribe-once [:title] {:frame client-frame}))
              "app-db :title survives — the wrong-frame slice was NOT installed")
          ;; runtime-db partition untouched (no hydration metadata, no machine
          ;; snapshots from the rejected payload).
          (is (false? (rf/subscribe-once [:hydrated?] {:frame client-frame}))
              "no hydration metadata stashed — the runtime-db partition is left unchanged")
          (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value client-frame))
                            [:rf.runtime/machines :snapshots]))
              "the payload's runtime-db slice did NOT land — runtime-db untouched")
          ;; Dev-instrumentation arm (see ns docstring). The
          ;; BYPASS this deftest names — a direct dispatch reaching only the
          ;; handler — is closed by the four partition assertions above, all
          ;; posture-independent. The always-on fan-out of this same category
          ;; is pinned on the `:errors` axis by
          ;; `mismatch-always-on-record-redacts-sensitive-payload-frame-id`
          ;; below, which holds under the gate.
          (when rf.interop/debug-enabled?
            ;; the structured mismatch surfaced, carrying the two frames.
            (let [mismatch (first (filter #(= :rf.error/hydration-frame-id-mismatch
                                              (:operation %))
                                          @traces))]
              (is (some? mismatch)
                  (str "must emit :rf.error/hydration-frame-id-mismatch; saw: "
                       (pr-str (mapv :operation @traces))))
              (when mismatch
                (is (= client-frame (-> mismatch :tags :target-frame))
                    ":target-frame is the dispatch target frame")
                (is (= :some/other-frame (-> mismatch :tags :payload-frame-id))
                    ":payload-frame-id is the payload's (server) frame stamp")))))))))

;; ===========================================================================
;; The always-on corpus leg of the
;; frame-id-mismatch rejection routes the UNTRUSTED deserialised
;; :payload-frame-id through project-egress, so a frame that declares it
;; sensitive does NOT ship it raw to off-box corpus listeners (Sentry /
;; Datadog). The dev trace (DCE'd in production) keeps the raw value.
;; ===========================================================================

(deftest mismatch-always-on-record-redacts-sensitive-payload-frame-id
  (testing "When the rejected frame declares the
            :payload-frame-id path :sensitive, the ALWAYS-ON corpus record
            (the production-surviving off-box-shipper leg) carries the value
            REDACTED — it routes through project-egress — even though the dev
            trace keeps it raw. The sensitive deserialised payload value never
            fans out to a corpus listener raw."
    (register-baseline-handlers!)
    ;; EP-0025: the rejected client frame declares
    ;; the untrusted `:payload-frame-id` slot :sensitive through
    ;; a B3 COMMIT-PLANE `:sensitive` effect the frame's init event
    ;; returns alongside `:db` (EP-0025 §How it works / §Examples) — writing it
    ;; into the per-frame `[:rf.runtime/elision]` registry. So project-egress
    ;; redacts it on the off-box leg.
    (rf/reg-event :rf.b5/classify
      (fn [_ _] {:sensitive [[:payload-frame-id]]}))
    (let [;; the rejected client frame declares the untrusted payload slot
          ;; :sensitive — so project-egress redacts it on the off-box leg.
          client-frame (rf.frame/make-anon-frame-record!
                         {:doc            "B5 sensitive payload-frame-id client"
                          :platform       :client
                          :initial-events [[:rf.b5/classify]]})
          ;; the corpus-listener stand-in (the off-box shipper) records the
          ;; ALWAYS-ON union record.
          corpus       (atom [])
          payload      {:rf/frame-id    :secret/other-frame
                        :rf/app-db      {:count 42}
                        :rf/render-hash "deadbeef"}]
      ;; The :errors (always-on) corpus listener is a DIFFERENT observation
      ;; stream from the :trace bus the shared recorder brackets — it stays a
      ;; direct register/unregister (this test exercises that surface).
      (rf.error-emit/register-error-listener! ::b5-corpus
        (fn [record] (swap! corpus conj record)))
      (try
        (with-trace-recorder! [dev-traces]
          (rf/dispatch-sync [:rf/hydrate payload] {:frame client-frame})
          (let [record   (first (filter #(= :rf.error/hydration-frame-id-mismatch
                                            (:error %))
                                        @corpus))
                dev-trace (first (filter #(= :rf.error/hydration-frame-id-mismatch
                                             (:operation %))
                                         @dev-traces))]
            (is (some? record)
                (str "the frame-id-mismatch fanned out on the ALWAYS-ON axis; "
                     "saw: " (pr-str (mapv :error @corpus))))
            (when record
              (testing "the ALWAYS-ON corpus record redacts the untrusted payload value"
                (is (= :rf/redacted (:payload-frame-id record))
                    (str ":payload-frame-id is redacted on the always-on record "
                         "(routed through project-egress); got "
                         (pr-str (:payload-frame-id record))))
                (is (not (re-find #"secret/other-frame" (pr-str record)))
                    "no raw :secret/other-frame value survives anywhere in the corpus record")))
            (when dev-trace
              (testing "the dev trace (DCE'd in production) keeps the raw value for local fidelity"
                (is (= :secret/other-frame (-> dev-trace :tags :payload-frame-id))
                    "the dev-trace tags carry the raw payload frame-id (the leak is off-box, not local)")))))
        (finally
          (rf.error-emit/unregister-error-listener! ::b5-corpus))))))
