(ns re-frame.ssr-flush-response-result-test
  "rf2-oytx7j — runtime-side acceptance for the projected-error pre-commit
  contract's two runtime pieces:

    1. `ssr/flush-response-result!` — the one-drain read that returns BOTH
       the resolved response accumulator AND the projected `:public-error`,
       so a host adapter classifies the drain-time outcome (4xx app arm vs
       5xx error arm) WITHOUT re-inferring projection from `(:status resp)`.
       Pins: it returns the projected map; a SECOND flush returns
       `:public-error nil` (already consumed); two concurrent server frames
       return their OWN public-error with no bleed; redirect precedence
       suppresses the status stamp but still returns the map.

    2. `:rf/public-error` validation tightening — `public-error-shape?` (via
       the public `ssr/project-error`) now enforces a CLOSED four-key set and
       an HTTP error status in 400..599, so a projector that returns an extra
       key (incl. a caller-supplied `:details`) or an out-of-range status
       takes the locked generic-500 fallback. Only the runtime appends
       `:details`, AFTER validation, under `:dev-error-detail?`.

  The ssr-ring HTTP-wire acceptance for the same contract lives in
  `re-frame.ssr.ring-draintime-error-view-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.trace :as rf.trace]))

;; rf2-lwtlk — ORDER MATTERS. The wholesale `clear-error-listeners!` used to
;; run INSIDE `reset-runtime`'s body, i.e. AFTER it. `reset-runtime` re-installs
;; the `re-frame.ssr` façade's own ALWAYS-ON `::error-projection` listener (via
;; `(require 're-frame.ssr :reload)`), and that listener IS the production
;; status-projection path — clearing it afterwards left the always-on axis
;; unarmed for every test here, so the namespace could only ever observe the
;; dev bus. Clear FIRST (drop whatever a prior namespace left), then let the
;; reset arm the projector.
(use-fixtures :each
  (fn [t]
    (rf.error-emit/clear-error-listeners!)
    (rf.ssr.test-fixture/reset-runtime t)))

(defn- make-server-frame
  "A `:server`-platform frame using the default projector (no-such-handler →
  404, handler-exception → 500)."
  [id]
  (rf/make-frame {:id id :platform :server
                  :ssr      {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
  id)

(defn- buffer-error!
  "Buffer a projecting error trace against `frame-id` exactly as a drain-time
  error would. The settle-point drain in `flush-response-result!` then
  projects it.

  BOTH AXES, deliberately (rf2-lwtlk). This helper used to fire
  `trace/emit-error!` alone — the DEV bus, whose emit sites sit inside the
  load-time `interop/debug-enabled?` gate. Under `-Dre-frame.debug=false`
  nothing was ever buffered, so twelve assertions below observed a clean
  200 / a nil public-error and this namespace was rostered known-red: the
  drain-classification contract had never executed in the posture that
  ships. It is not a dev contract. `flush-response-result!` is the surface
  a HOST ADAPTER calls on a production JVM to decide the 4xx app arm from
  the 5xx error arm.

  Both categories used here are on the ALWAYS-ON error axis in production —
  `:rf.error/handler-exception` through `dispatch-on-error!`, and
  `:rf.error/no-such-handler {:kind :route}` through
  `dispatch-error-record!` since rf2-ov56u. So the honest fix is a
  production-reachable channel, not a `(when interop/debug-enabled? …)`
  guard: emit on both, the way a real always-on-catalogued site does
  (`emit-error-both!`), and the whole namespace becomes
  posture-independent. In dev both listeners buffer and last-write-wins
  makes the duplicate benign — the same benign duplication
  `error-projection-listener`'s own docstring records.

  `extra-tags` carries any discriminator the default projector's arm is
  GATED on — for `:rf.error/no-such-handler` that is `:kind :route`
  (rf2-ov56u): the 404 arm fires only for the URL-driven miss, so a
  kind-less synthetic trace projects the locked 500 and would silently
  turn a 404 assertion into a false negative. It rides `:tags` on the dev
  trace and a flat slot on the always-on record; the always-on projection
  listener lifts every flat slot back onto `:tags`, so the projector sees
  the same event either way."
  ([frame-id operation] (buffer-error! frame-id operation nil))
  ([frame-id operation extra-tags]
   (let [tags (merge {:frame frame-id :recovery :for-test} extra-tags)]
     (rf.trace/emit-error! operation tags)
     (rf.error-emit/dispatch-error-record!
       (merge {:error operation :time (rf.interop/now-ms)} tags)))))

;; ===========================================================================
;; flush-response-result! — returns the projected public-error alongside resp
;; ===========================================================================

(deftest flush-response-result-returns-response-and-public-error
  (testing "a buffered drain-time :rf.error/handler-exception is projected to
            500 and returned as :public-error, with :status stamped on
            :response — one drain, both facts."
    (let [fid (make-server-frame :ssr/frr-500)]
      (buffer-error! fid :rf.error/handler-exception)
      (let [{:keys [response public-error]} (rf.ssr/flush-response-result! fid)]
        (is (= 500 (:status public-error))
            "the projected public-error is returned for classification")
        (is (= :internal-error (:code public-error)))
        (is (= 500 (:status response))
            "the same 500 is stamped on the response accumulator")))))

(deftest flush-response-result-4xx-classifies-as-app-arm
  (testing "a projected 404 (routing miss) returns a 4xx public-error — the
            host keeps the app arm; only 500..599 diverts to the error page."
    (let [fid (make-server-frame :ssr/frr-404)]
      (buffer-error! fid :rf.error/no-such-handler {:kind :route})
      (let [{:keys [public-error]} (rf.ssr/flush-response-result! fid)]
        (is (= 404 (:status public-error)))
        (is (<= 400 (:status public-error) 499)
            "a 404 is the client-fault / app-renderable arm")))))

(deftest flush-response-result-no-error-returns-nil-public-error
  (testing "no buffered error → :public-error nil and the default 200 response.
            A host that reads a 200 with nil public-error stays on the app arm
            (status alone is not proof of a projection)."
    (let [fid (make-server-frame :ssr/frr-clean)
          {:keys [response public-error]} (rf.ssr/flush-response-result! fid)]
      (is (nil? public-error) "no projection fired")
      (is (= 200 (:status response))))))

(deftest second-flush-returns-nil-public-error
  (testing "the projection is consumed ONCE — a second flush returns
            :public-error nil (the pending trace was already drained) while
            the response keeps the stamped status."
    (let [fid (make-server-frame :ssr/frr-consume)]
      (buffer-error! fid :rf.error/handler-exception)
      (let [first-flush (rf.ssr/flush-response-result! fid)]
        (is (= 500 (:status (:public-error first-flush)))
            "first flush projects the buffered error"))
      (let [second-flush (rf.ssr/flush-response-result! fid)]
        (is (nil? (:public-error second-flush))
            "second flush finds the buffer empty → nil public-error")
        (is (= 500 (:status (:response second-flush)))
            "the response still carries the status the first flush stamped")))))

(deftest two-frames-return-own-public-error-no-bleed
  (testing "two concurrent server frames each project + return THEIR OWN
            public-error — a 500 on one frame does not bleed onto the other's
            404 (per-frame attribution, the concurrent-SSR invariant)."
    (let [f500 (make-server-frame :ssr/frr-a-500)
          f404 (make-server-frame :ssr/frr-b-404)]
      (buffer-error! f500 :rf.error/handler-exception)
      (buffer-error! f404 :rf.error/no-such-handler {:kind :route})
      (let [a (rf.ssr/flush-response-result! f500)
            b (rf.ssr/flush-response-result! f404)]
        (is (= 500 (:status (:public-error a))) "frame A keeps its 500")
        (is (= 404 (:status (:public-error b))) "frame B keeps its 404")
        (is (= 500 (:status (:response a))))
        (is (= 404 (:status (:response b))))))))

(deftest redirect-precedence-suppresses-status-stamp-still-returns-error
  (testing "when the response carries a :redirect, the projected status is NOT
            stamped (redirect precedence) — but flush-response-result! still
            returns the public-error; the host branches on :redirect FIRST."
    (let [fid (make-server-frame :ssr/frr-redirect)]
      ;; Accumulate a redirect (302) via the fx handler, then buffer an error.
      ((requiring-resolve 're-frame.ssr.response/redirect-fx)
       {:frame fid} {:location "/login"})
      (buffer-error! fid :rf.error/handler-exception)
      (let [{:keys [response public-error]} (rf.ssr/flush-response-result! fid)]
        (is (some? (:redirect response)) "the redirect stands")
        (is (= 302 (:status response))
            "redirect status locked through — the 500 did NOT overwrite it")
        (is (= 500 (:status public-error))
            "the projected map is still returned (the host ignores it under a
             redirect, but the datum is available)")))))

;; ===========================================================================
;; :rf/public-error validation tightening — closed key-set + 400..599 range
;; ===========================================================================

(defn- project-with
  "Register `projector-fn` and project a `:rf.error/handler-exception` event
  through a server frame configured to use it. Returns the public-error map."
  ([projector-fn] (project-with projector-fn false))
  ([projector-fn dev-detail?]
   (rf/reg-error-projector :test/shape-projector projector-fn)
   (let [f (rf.frame/make-anon-frame-record!
             {:platform :server
              :ssr      {:public-error-id   :test/shape-projector
                         :dev-error-detail? dev-detail?}})]
     (rf.ssr/project-error f {:op-type   :error
                           :operation :rf.error/handler-exception
                           :tags      {:frame f}}))))

(deftest conforming-4xx-and-5xx-projections-pass
  (testing "a projector returning EXACTLY the four keys with a status in
            400..599 conforms and is honoured (401, 503)."
    (is (= 401 (:status (project-with (fn [_] {:status 401 :code :unauthorised
                                               :message "Sign in" :retryable? false}))))
        "a 401 is a valid public error")
    (is (= 503 (:status (project-with (fn [_] {:status 503 :code :unavailable
                                               :message "Try later" :retryable? true}))))
        "a 503 is a valid server-fault public error")))

(deftest out-of-range-status-falls-back-to-locked-500
  (testing "a projector returning a status OUTSIDE 400..599 (a 200) is not a
            well-formed error projection → locked generic-500 fallback."
    (let [public (project-with (fn [_] {:status 200 :code :ok
                                        :message "fine" :retryable? false}))]
      (is (= 500 (:status public)) "out-of-range 200 → fallback 500")
      (is (= :internal-error (:code public)))
      (is (= "Something went wrong" (:message public))))))

(deftest extra-key-including-details-falls-back-to-locked-500
  (testing "a projector that returns an EXTRA key (incl. a caller-supplied
            :details) fails the closed-key check → locked generic-500. The
            redaction boundary is real: a projector cannot smuggle its own
            :details past the four-key gate."
    (let [with-details (project-with (fn [_] {:status 500 :code :internal-error
                                              :message "boom" :retryable? false
                                              :details {:secret "leak"}}))]
      (is (= 500 (:status with-details)))
      (is (not (contains? with-details :details))
          "the projector's own :details was rejected, not passed through")
      (is (= "Something went wrong" (:message with-details))
          "the locked fallback message replaced the non-conforming output"))
    (let [with-extra (project-with (fn [_] {:status 400 :code :bad-request
                                            :message "bad" :retryable? false
                                            :internal-note "x"}))]
      (is (= 500 (:status with-extra))
          "any stray key → fallback (not honoured as a 400)"))))

(deftest dev-detail-appends-details-from-runtime-only
  (testing "with :dev-error-detail? true the runtime appends :details AFTER
            validation (the trace event) — the projector's conforming
            four-key output is unchanged; prod (default) has no :details."
    (let [dev  (project-with (fn [_] {:status 500 :code :internal-error
                                      :message "boom" :retryable? false}) true)
          prod (project-with (fn [_] {:status 500 :code :internal-error
                                      :message "boom" :retryable? false}) false)]
      (is (contains? dev :details)
          "dev-detail appends the runtime-owned :details after validation")
      (is (not (contains? prod :details))
          "prod default: exactly the four locked keys, no :details"))))
