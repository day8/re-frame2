(ns re-frame.ssr.ring-draintime-error-test
  "ssr-ring handler-level e2e coverage for the DRAIN-TIME error → projected
  non-200 status path, where the drain's error sites stamp the emitting
  `:frame`.

  ## What this covers

  ssr-ring's other error coverage splits two ways, and BOTH miss the
  drain-time routing/schema categories:

    - `ring_test.clj` (`handler-render-error-*`) exercises only the
      RENDER-TIME path: a `:root-view` that THROWS inside
      `render-to-string`, caught by `pipeline/build-full-response`'s
      try/catch, routed through `ssr/project-render-exception!` (the
      `:frame` is passed explicitly, synchronously). No drain involved.

    - `ring_e2e_validator_test.clj` exercises ONE drain-time category —
      a CRLF-bearing cookie / header value that throws an
      `:rf.error/fx-handler-exception` during the `:initial-events` drain —
      and asserts the projected status is 500. But 500 is the default
      projector's GENERIC FALLBACK (`fallback-public-error`): it is the
      status the response would carry if projection ran AND the status
      the catch-all arm produces, so it does not discriminate the
      *specific* projector arm that fired.

  The categories stamped with `[:tags :frame]`
  inside the routing drain — `:rf.error/no-such-handler` /
  `:rf.error/no-such-route` (→ 404) and `:rf.error/schema-validation-
  failure` (→ 400) — map
  to DISCRIMINATING non-default statuses (404 / 400, not the generic
  500), so a regression that dropped or mis-stamped `:frame` for a
  routing drain-time category would silently ship a 200 for what should
  be a 4xx. (A 200, not the 500: with no routable
  `:frame` the projector no-ops and the accumulator keeps its default
  200; see `error-listener/candidate-frame-for-error`.)

  The ssr ARTEFACT proves the drain-time + per-frame-attribution
  contract directly (its `ssr-error-known-mapping` conformance fixture
  drives an unmatched URL to the default projector's 404,
  `ssr_end_to_end_test/default-error-projector-fn-maps-all-enumerated-categories`
  pins that projector arm, and `ssr_error_two_frame_attribution_test` →
  the navigate-reject 400 on the emitting frame only). Both 4xx
  categories leave ssr-ring through the same app arm, which
  `ring_draintime_error_view_test/draintime-4xx-keeps-root-and-payload-never-error-view`
  pins in-process. ssr-ring DEPENDS on that contract; this namespace
  proves the per-frame WIRE status flows through ITS handler:

    `two-concurrent-frames-attribute-drain-time-404-per-frame` — the
       two-concurrent-server-frame attribution case AT THE ssr-ring
       BOUNDARY. Many simultaneous per-request frames is the canonical
       SSR shape (the same shape `concurrency_stress_test` drives, but
       that test is all-200 happy-path). Here HALF the concurrent
       requests route to an unmatched URL (→ drain-time 404) and half to
       a registered route (→ 200). The invariant: EVERY 404 request gets
       a 404 and EVERY 200 request gets a 200 — no cross-frame bleed.
       With >1 server frame live this can only hold if each drain-time
       trace carries its emitting frame's `:frame` (a
       single-frame fallback would return nil under >1 frame and
       ship a 200 for the should-be-404).

  The Jetty + `java.net.http` harness is the shared one
  `concurrency_stress_test.clj` / `ring_e2e_validator_test.clj` use."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

;; Canonical reset-runtime fixture; same shape
;; every ssr-ring JVM test uses. Each test starts from a reset registrar
;; (snapshot/restore baseline) with the SSR adapter installed, so
;; `:rf.route/handle-url-change`, `:rf.route/navigate`, the
;; `:rf.server/request` cofx, and the always-on error-emit-projection-
;; listener are all live between tests.
(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

;; ===========================================================================
;; Jetty + java.net.http harness
;; ===========================================================================
;;
;; The ephemeral Jetty host + `java.net.http` client / GET
;; helper live in `re-frame.ssr.ring.test-support`,
;; shared with the other live-host test namespaces. The 30s read timeout
;; (these drain-time + concurrency-split tests complete slower than the
;; 10s single-request streaming tests) is the explicit timeout argument
;; this ns's `http-get` passes to the shared helper.

(def ^:private read-timeout-secs 30)

(defn- http-get
  "Issue a real HTTP GET and return `{:status :body}` observed on the
  wire (this suite's 30s read-timeout pinned via the shared helper)."
  [client port path]
  (rf.ssr.ring.test-support/http-get client port path read-timeout-secs))

;; ===========================================================================
;; Two concurrent server frames: per-frame drain-time attribution
;; ===========================================================================
;;
;; The canonical concurrent-SSR shape: many per-request server frames
;; live simultaneously. HALF the concurrent requests route to an
;; unmatched URL (→ drain-time :rf.error/no-such-handler → 404), HALF to a
;; registered route (→ 200 happy path). The invariant — every 404-request
;; gets a 404 AND every 200-request gets a 200 — can ONLY hold under >1
;; live server frame if each drain-time error trace carries its EMITTING
;; frame's `:frame`. A single-frame fallback would
;; return nil with >1 frame live and ship a 200 for the
;; should-be-404. Mismatches in
;; EITHER direction (a 200-request getting a 404, or a 404-request getting
;; a 200) are bleed; zero is the contract.

(deftest two-concurrent-frames-attribute-drain-time-404-per-frame
  (testing "under N concurrent server frames, a drain-time
            :rf.error/no-such-handler in a request stamps 404 on THAT
            request's response only — sibling concurrent requests to a
            valid route stay 200. No cross-frame bleed (the two-frame
            attribution regression, at the ssr-ring boundary)."
    (rf/reg-route :route/home {} "/")
    ;; The :initial-events reads the request URI via the :rf.server/request
    ;; cofx and routes a URL-change to it. A `/missing/*` URI matches no
    ;; route → drain-time 404; a `/` URI matches :route/home → 200.
    (rf/reg-event :init/route-from-uri
      {:platforms        #{:server}
       :rf.cofx/requires [:rf.server/request]}
      (fn [{request :rf.server/request} _]
        {:fx [[:dispatch [:rf.route/handle-url-change (or (:uri request) "/")]]]}))
    (rf/reg-view* :pages/concurrent-root
      (fn [] [:main "concurrent root"]))

    (let [handler (rf.ssr.ring/ssr-handler
                    {:initial-events [[:init/route-from-uri]]
                     :root-view [(rf/view :pages/concurrent-root)]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      (rf.ssr.ring.test-support/with-jetty [port handler]
        (let [client       (rf.ssr.ring.test-support/new-http-client)
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
                   " a per-frame attribution regression: a drain-time error whose"
                   " :frame was dropped no-ops the projector and ships 200."
                   " First anomalies: " (pr-str (take 8 @anomalies))))

          (is (zero? (.get hit-wrong))
              (str "cross-frame bleed — " (.get hit-wrong) " valid-route"
                   " requests got a non-200 status (a sibling frame's"
                   " drain-time 404 bled onto a 200 request's response)."
                   " First anomalies: " (pr-str (take 8 @anomalies)))))))))
