(ns re-frame.ssr.ring-draintime-error-test
  "Per rf2-fn41e (ssr-ring senior-dev review 5tkld) — close the
  ssr-ring handler-level e2e gap for the DRAIN-TIME error → projected
  non-200 status path, the path rf2-7d30s (the `:frame`-stamp audit)
  actually touched.

  ## The gap this closes

  ssr-ring's existing error coverage splits two ways, and BOTH miss the
  drain-time routing/schema categories:

    - `ring_test.clj` (`handler-render-error-*`) exercises only the
      RENDER-TIME path: a `:root-view` that THROWS inside
      `render-to-string`, caught by `pipeline/build-full-response`'s
      try/catch, routed through `ssr/project-render-exception!` (the
      `:frame` is passed explicitly, synchronously). No drain involved.

    - `ring_e2e_validator_test.clj` exercises ONE drain-time category —
      a CRLF-bearing cookie / header value that throws an
      `:rf.error/fx-handler-exception` during the `:on-create` drain —
      and asserts the projected status is 500. But 500 is the default
      projector's GENERIC FALLBACK (`fallback-public-error`): it is the
      status the response would carry if projection ran AND the status
      the catch-all arm produces, so it does not discriminate the
      *specific* projector arm that fired.

  The categories rf2-7d30s's audit actually stamped `[:tags :frame]` on
  inside the routing drain — `:rf.error/no-such-handler` /
  `:rf.error/no-such-route` (→ 404) and `:rf.error/schema-validation-
  failure` (→ 400) — had NO ssr-ring handler-level coverage. These map
  to DISCRIMINATING non-default statuses (404 / 400, not the generic
  500), so a regression that dropped or mis-stamped `:frame` for a
  routing drain-time category would silently ship a 200 for what should
  be a 4xx — exactly the bug rf2-7d30s fixed — and no ssr-ring test
  would catch it. (A 200, not the existing 500: with no routable
  `:frame` the projector no-ops and the accumulator keeps its default
  200; see `error-listener/candidate-frame-for-error`.)

  The ssr ARTEFACT proves the drain-time + per-frame-attribution
  contract directly (`ssr_end_to_end_test/ssr-default-error-projector-
  no-such-handler` → 404; `ssr_error_two_frame_attribution_test` → the
  navigate-reject 400 on the emitting frame only). ssr-ring DEPENDS on
  that contract but never proved the end-to-end WIRE status flows through
  ITS handler. This namespace closes that loop:

    1. `draintime-no-such-route-projects-404-on-the-wire` — an
       `:on-create` that dispatches `:rf.route/handle-url-change` to an
       unmatched URL emits a drain-time `:rf.error/no-such-handler`
       (`:kind :route`), buffered by the always-on
       `error-emit-projection-listener`, projected to 404 by
       `ssr/get-response` (the read at `ring.clj:343`). Asserted on the
       wire (Jetty + `java.net.http`) AND via a direct in-process
       handler call — the 404 is the DISCRIMINATING status that proves
       the routing projector arm rode the wire, not the generic 500
       fallback.

    2. `draintime-navigate-reject-projects-400-on-the-wire` — an
       `:on-create` that fires a `:rf.route/navigate` whose `:params`
       schema rejects emits a drain-time `:rf.error/schema-validation-
       failure` (`:where :event`), projected to 400. Mirrors the ssr-
       artefact two-frame regression's mechanism, driven through the
       ring handler. The page still renders (status-only error per
       Spec 011 §Server error projection — the 4xx owns the wire status
       but the body is the rendered root-view).

    3. `two-concurrent-frames-attribute-drain-time-404-per-frame` — the
       two-concurrent-server-frame attribution case AT THE ssr-ring
       BOUNDARY. Many simultaneous per-request frames is the canonical
       SSR shape (the same shape `concurrency_stress_test` drives, but
       that test is all-200 happy-path). Here HALF the concurrent
       requests route to an unmatched URL (→ drain-time 404) and half to
       a registered route (→ 200). The invariant: EVERY 404 request gets
       a 404 and EVERY 200 request gets a 200 — no cross-frame bleed.
       With >1 server frame live this can only hold if each drain-time
       trace carries its emitting frame's `:frame` (the removed rf2-7d30s
       single-frame fallback would have returned nil under >1 frame and
       shipped a 200 for the should-be-404).

  TEST-ONLY: no source change. This proves an existing contract holds
  through the ring layer. The Jetty + `java.net.http` harness mirrors
  `concurrency_stress_test.clj` / `ring_e2e_validator_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.test-support :as ts])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

;; rf2-i3qc0 / rf2-09iktm — canonical reset-runtime fixture; same shape
;; every ssr-ring JVM test uses. Each test starts from a reset registrar
;; (snapshot/restore baseline) with the SSR adapter installed, so
;; `:rf.route/handle-url-change`, `:rf.route/navigate`, the
;; `:rf.server/request` cofx, and the always-on error-emit-projection-
;; listener are all live between tests.
(use-fixtures :each ts/reset-runtime)

;; ===========================================================================
;; Jetty + java.net.http harness
;; ===========================================================================
;;
;; rf2-l1qgjw — the ephemeral Jetty host + `java.net.http` client / GET
;; helper now live in `re-frame.ssr.ring.test-support` (aliased `ts`),
;; shared with the other live-host test namespaces. The 30s read timeout
;; (these drain-time + concurrency-split tests complete slower than the
;; 10s single-request streaming tests) stays an explicit `ts/http-get`
;; argument at each call site.

(def ^:private read-timeout-secs 30)

(defn- http-get
  "Issue a real HTTP GET and return `{:status :body}` observed on the
  wire (this suite's 30s read-timeout pinned via the shared helper)."
  [client port path]
  (ts/http-get client port path read-timeout-secs))

;; ===========================================================================
;; Stub validator — interpret a `:params` schema as a Clojure predicate
;; `(fn [v] truthy?)`, mirroring the ssr-artefact two-frame attribution
;; test's `with-stub-validator` so the navigate-reject path runs without
;; dragging Malli onto the classpath.
;; ===========================================================================

(defn- with-stub-validator []
  (let [snap     (schemas/snapshot-schema-fns)
        ;; rf2-ps05ug: the stub is process-global, so while installed EVERY
        ;; schema-validating boundary uses it — including the routing
        ;; recordable allocation cofx, whose `:schema` is a real Malli VECTOR
        ;; (`[:map [:token :string] [:counter :int]]`). A Malli vector is not
        ;; a callable predicate (calling it as `(schema value)` index-lookups
        ;; for an integer value and throws for a map), so a naive call would
        ;; throw and the fail-closed seam would coerce it to FALSE, spuriously
        ;; rejecting the well-formed generated nav-allocation. The stub
        ;; therefore only adjudicates ACTUAL fn schemas (the predicate schemas
        ;; these tests install) and PASSES any other (Malli vector / map)
        ;; schema. Mirrors routing_test_support's `with-stub-validator`.
        validate (fn [schema value]
                   (if (fn? schema)
                     (boolean (schema value))
                     true))
        explain  (fn [schema value]
                   (when (fn? schema)
                     {:reason :stub-explainer :value value}))]
    (schemas/set-schema-fns! {:validate validate :explain explain})
    (fn [] (schemas/restore-schema-fns! snap))))

;; ===========================================================================
;; Test 1 — drain-time :rf.error/no-such-handler → projected 404 on the wire
;; ===========================================================================
;;
;; This is the discriminating-status proof. `:on-create` dispatches
;; `:rf.route/handle-url-change` to an unmatched URL. `url-change-fx`
;; (routing/url_change.cljc) threads the drain's `:frame` cofx and emits
;; `:rf.error/no-such-handler` (`:kind :route`) tagged with that frame —
;; one of the exact emit sites rf2-7d30s audited. The always-on
;; `error-emit-projection-listener` buffers it; `ssr/get-response` (the
;; read at ring.clj:343, BEFORE render) flushes the buffer through the
;; default projector, which maps `:no-such-handler` → 404, and stamps it
;; onto the response accumulator. `build-full-response` then materialises
;; that 404 onto the Ring response status. 404 (not the generic 500) is
;; the load-bearing assertion: it proves the ROUTING projector arm — the
;; one fed by the rf2-7d30s `:frame` stamp — reached the wire.

(deftest draintime-no-such-route-projects-404-on-the-wire
  (testing "rf2-fn41e: a drain-time :rf.error/no-such-handler (unmatched
            route in :on-create) is projected to 404 by get-response and
            rides the wire status through the ring handler — the routing
            drain-time path rf2-7d30s touched, asserted at the ssr-ring
            boundary (ssr_end_to_end_test proves it at the ssr layer; this
            proves the WIRE status through the ring layer)."
    ;; A registered route so a registry exists; the request URL below
    ;; matches NONE of them, so url-change-fx falls back to not-found and
    ;; emits the drain-time :rf.error/no-such-handler.
    (rf/reg-route :route/home {} "/")
    (rf/reg-event :init/route-to-missing
      {:platforms #{:server}}
      (fn [_ _]
        ;; Drain-time error: dispatch a URL-change to an unmatched URL.
        ;; The :rf.route/handle-url-change handler threads the frame cofx
        ;; into url-change-fx, which emits :rf.error/no-such-handler with
        ;; :frame stamped (rf2-7d30s). The :dispatch fx keeps this inside
        ;; the SAME on-create drain so the error buffers against this
        ;; per-request frame.
        {:fx [[:dispatch [:rf.route/handle-url-change "/no-such-page"]]]}))
    (rf/reg-view* :pages/not-found
      (fn [] [:div.not-found "Not found page renders"]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/route-to-missing]
                     :root-view [:pages/not-found]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      (testing "direct in-process handler call — the projected 404 rides
                the Ring response :status (the get-response → 404 path)"
        (let [response (handler {:uri "/no-such-page" :request-method :get})]
          (is (= 404 (:status response))
              "drain-time :no-such-handler → default projector's 404
               stamped on :rf/response by get-response → ring :status.
               A regression that dropped the :frame stamp would no-op the
               projector and ship the default 200 here.")
          ;; Status-only error per Spec 011 §Server error projection — the
          ;; 4xx owns the wire status, the body is still the rendered
          ;; root-view (not an error template; the drain-time path does NOT
          ;; route through build-full-response's render-time catch).
          (is (str/includes? (:body response) "Not found page renders")
              "the root-view still renders — the 404 is a status-only
               response (the drain-time projector stamps :status, it does
               not replace the body the way the render-time path does)")))

      (testing "bytes-on-the-wire through Jetty — a real HTTP server
                preserves the projected 404 status"
        (ts/with-jetty [port handler]
          (let [client (ts/new-http-client)
                {:keys [status body]} (http-get client port "/no-such-page")]
            (is (= 404 status)
                "the projected 404 survives the full Jetty round-trip")
            (is (str/includes? body "Not found page renders")
                "the root-view body rides the wire alongside the 404")))))))

;; ===========================================================================
;; Test 2 — drain-time :rf.error/schema-validation-failure → projected 400
;; ===========================================================================
;;
;; The navigate-reject path: a `:rf.route/navigate` whose target route's
;; `:params` predicate rejects raises inside `route-url`; navigate.cljc
;; catches it and emits `:rf.error/schema-validation-failure` (`:where
;; :event`) with the drain's `:frame` stamped (rf2-7d30s — the exact
;; audit site). The default projector maps it to 400. Same mechanism as
;; the ssr-artefact two-frame attribution regression, driven through the
;; ring handler. 400 is the discriminating status (distinct from both the
;; default 200 AND the generic-fallback 500).

(deftest draintime-navigate-reject-projects-400-on-the-wire
  (testing "rf2-fn41e: a drain-time :rf.error/schema-validation-failure
            (navigate-reject in :on-create) is projected to 400 and rides
            the wire status through the ring handler — mirrors the ssr-
            artefact navigate-reject regression at the ssr-ring boundary."
    (let [restore (with-stub-validator)]
      (try
        (rf/reg-route :route/home {} "/")
        ;; A route whose :params predicate rejects any :id not starting
        ;; "a" — navigating with a bad :id makes route-url throw →
        ;; navigate.cljc emits :rf.error/schema-validation-failure.
        (rf/reg-route :route/article
                      {:params (fn [{:keys [id]}]
                                 (str/starts-with? (or id "") "a"))} "/articles/:id")
        ;; Server-platform push-url so the navigate fx assembly resolves
        ;; on a :platform :server frame (no-op sink — the reject path
        ;; never pushes).
        (rf/reg-fx :rf.nav/push-url
                   {:platforms #{:server :client}}
                   (fn [_ _url] nil))
        (rf/reg-event :init/navigate-bad-param
          {:platforms #{:server}}
          (fn [_ _]
            ;; Drain-time error: a navigate whose :id ("zoo") fails the
            ;; route's :params predicate → reject → schema-validation-
            ;; failure, buffered against this per-request frame.
            {:fx [[:dispatch [:rf.route/navigate :route/article {:id "zoo"}]]]}))
        (rf/reg-view* :pages/article
          (fn [] [:div.article "Article page renders"]))

        (let [handler (ssr-ring/ssr-handler
                        {:on-create [:init/navigate-bad-param]
                         :root-view [:pages/article]
                         :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                     :dev-error-detail? false}
                         :payload :rf.ssr.payload/whole-app-db})]
          (testing "direct in-process handler call — projected 400 on :status"
            (let [response (handler {:uri "/articles/zoo" :request-method :get})]
              (is (= 400 (:status response))
                  "drain-time :schema-validation-failure → default
                   projector's 400 stamped on :rf/response by get-response
                   → ring :status. A dropped :frame stamp would no-op the
                   projector and ship the default 200.")
              (is (str/includes? (:body response) "Article page renders")
                  "the root-view still renders — status-only 400 (the
                   navigate-reject is a drain-time status stamp, not a
                   render-time body replacement)")))

          (testing "bytes-on-the-wire through Jetty — 400 survives the round-trip"
            (ts/with-jetty [port handler]
              (let [client (ts/new-http-client)
                    {:keys [status body]} (http-get client port "/articles/zoo")]
                (is (= 400 status)
                    "drain-time :schema-validation-failure → default
                     projector's 400 rides the wire status. A dropped
                     :frame stamp would ship the default 200.")
                (is (str/includes? body "Article page renders")
                    "the root-view still renders — status-only 400")))))
        (finally (restore))))))

;; ===========================================================================
;; Test 3 — two concurrent server frames: per-frame drain-time attribution
;; ===========================================================================
;;
;; The canonical concurrent-SSR shape: many per-request server frames
;; live simultaneously. HALF the concurrent requests route to an
;; unmatched URL (→ drain-time :rf.error/no-such-handler → 404), HALF to a
;; registered route (→ 200 happy path). The invariant — every 404-request
;; gets a 404 AND every 200-request gets a 200 — can ONLY hold under >1
;; live server frame if each drain-time error trace carries its EMITTING
;; frame's `:frame` (rf2-7d30s). The removed single-frame fallback would
;; have returned nil with >1 frame live and shipped a 200 for the
;; should-be-404 (the exact regression rf2-7d30s fixed). Mismatches in
;; EITHER direction (a 200-request getting a 404, or a 404-request getting
;; a 200) are bleed; zero is the contract.

(deftest two-concurrent-frames-attribute-drain-time-404-per-frame
  (testing "rf2-fn41e: under N concurrent server frames, a drain-time
            :rf.error/no-such-handler in a request stamps 404 on THAT
            request's response only — sibling concurrent requests to a
            valid route stay 200. No cross-frame bleed (the two-frame
            attribution regression, at the ssr-ring boundary)."
    (rf/reg-route :route/home {} "/")
    ;; The :on-create reads the request URI via the :rf.server/request
    ;; cofx and routes a URL-change to it. A `/missing/*` URI matches no
    ;; route → drain-time 404; a `/` URI matches :route/home → 200.
    (rf/reg-event :init/route-from-uri
      {:platforms        #{:server}
       :rf.cofx/requires [:rf.server/request]}
      (fn [{request :rf.server/request} _]
        {:fx [[:dispatch [:rf.route/handle-url-change (or (:uri request) "/")]]]}))
    (rf/reg-view* :pages/concurrent-root
      (fn [] [:main "concurrent root"]))

    (let [handler (ssr-ring/ssr-handler
                    {:on-create [:init/route-from-uri]
                     :root-view [:pages/concurrent-root]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      (ts/with-jetty [port handler]
        (let [client       (ts/new-http-client)
              n-threads     8
              n-per-thread  10
              latch         (CountDownLatch. 1)
              ;; Bleed counters. A "miss" request expects 404; a "hit"
              ;; request expects 200. Any deviation is cross-frame bleed.
              miss-wrong    (AtomicLong. 0)
              hit-wrong     (AtomicLong. 0)
              completed     (AtomicLong. 0)
              ;; Per-thread record of the first few anomalies for the
              ;; failure message.
              anomalies     (atom [])
              futures
              (vec
                (for [thread-idx (range n-threads)]
                  (future
                    (.await latch)
                    (dotimes [iter-idx n-per-thread]
                      ;; Alternate miss / hit requests so both categories
                      ;; are in-flight concurrently against the SAME
                      ;; handler — the >1-live-server-frame shape.
                      (let [miss? (even? (+ thread-idx iter-idx))
                            path  (if miss?
                                    (str "/missing/t" thread-idx "-i" iter-idx)
                                    "/")
                            {:keys [status]} (http-get client port path)
                            expected (if miss? 404 200)]
                        (.incrementAndGet completed)
                        (when (not= expected status)
                          (if miss?
                            (.incrementAndGet miss-wrong)
                            (.incrementAndGet hit-wrong))
                          (swap! anomalies
                                 (fn [a]
                                   (cond-> a
                                     (< (count a) 8)
                                     (conj {:path     path
                                            :expected expected
                                            :actual   status})))))))))) ]
          (.countDown latch)
          (doseq [f futures]
            (let [v (deref f 120000 ::timeout)]
              (is (not= ::timeout v)
                  "every concurrent worker completed within 120s")))

          (is (= (* n-threads n-per-thread) (.get completed))
              "every request produced a response (no drops)")

          (is (zero? (.get miss-wrong))
              (str "per-frame attribution broken — " (.get miss-wrong)
                   " unmatched-route requests did NOT get a 404 (they got"
                   " the default 200). Under >1 live server frame this is"
                   " the rf2-7d30s regression: a drain-time error whose"
                   " :frame was dropped no-ops the projector and ships 200."
                   " First anomalies: " (pr-str (take 8 @anomalies))))

          (is (zero? (.get hit-wrong))
              (str "cross-frame bleed — " (.get hit-wrong) " valid-route"
                   " requests got a non-200 status (a sibling frame's"
                   " drain-time 404 bled onto a 200 request's response)."
                   " First anomalies: " (pr-str (take 8 @anomalies)))))))))
