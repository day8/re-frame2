(ns re-frame.ssr-request-cofx-test
  "Coverage for the :rf.server/request cofx + per-frame request slot.
  Per Spec 011 §Server-only `reg-cofx` for request context.

  The cofx surfaces the active HTTP request map to event handlers as an
  AMBIENT, host-transient read — for NON-DURABLE request reads (branching on
  `:request-method`, reading a header for a non-durable decision). Durable
  request-derived facts use the recordable boundary pattern instead, covered
  by `re-frame.ssr-request-durable-fact-test`. Mechanism:

    1. The host adapter (the Ring adapter, for example) populates
       the per-frame request slot via `re-frame.ssr/set-request!`
       before kicking off the drain.
    2. Event handlers declare `:rf.cofx/requires [:rf.server/request]` and
       read the request map FLAT under `:rf.server/request` in their
       coeffects (EP-0017 — there is no `inject-cofx`; the ambient supplier
       reads the active frame's slot).
    3. After the response is materialised, the host adapter calls
       `clear-request!` (typically as part of frame teardown).

  This test pins the read path, the platforms
  gating (client-side dispatches silently no-op), the frame-isolation
  invariant (two simultaneous per-request frames don't leak), and the
  set-request! seam tests/harnesses use to drive the drain without a host
  adapter.

  ## Posture split

  One dev-only surface is asserted in debug-gated arms here, so the rest
  of the namespace runs in `scripts/test-ssr-prod-gate.sh`.

  The `:rf.cofx/skipped-on-platform` trace is emitted through the gated trace
  bus, so its assertions sit in a dev arm. What it announces is production-real and
  is asserted outside it: on a `:platform :client` frame the cofx does not
  run, so `:rf.server/request` is ABSENT from the coeffect map — the
  behaviour the trace merely narrates."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

;; Shared reset fixture lives in `re-frame.ssr.test-fixture`.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- registration -----------------------------------------------------------
;;
;; The :rf.server/request cofx must be present in the cofx registry at
;; namespace-load time — same model as the :rf.server/* fxs.

;; ---- read path: populated slot ---------------------------------------------
;;
;; The canonical pattern: host adapter writes the request to the per-
;; frame slot before drain; a server-side event handler reads it via
;; :rf.cofx/requires [:rf.server/request].

;; ---- unpopulated slot ------------------------------------------------------
;;
;; If no host adapter populated the slot (e.g. tests that drive the
;; drain directly, or a misconfigured deployment), the cofx injects nil
;; rather than failing — handlers can branch on `(nil? request)`.

(deftest cofx-injects-nil-when-slot-unpopulated
  (testing "cofx returns nil when no host adapter has populated the slot"
    (let [server-frame (rf.frame/make-anon-frame-record! {:platform :server})
          observed     (atom :unset)]
      (rf/reg-event :req-test/read-empty
        {:rf.cofx/requires [:rf.server/request]}
        (fn [{:keys [rf.server/request] :as ctx} _]
          (reset! observed
                  ;; Distinguish between "key absent" and "key present
                  ;; but nil" — the cofx always assoc's the key.
                  (if (contains? ctx :rf.server/request)
                    [:present request]
                    [:absent  request]))
          {}))
      (rf/dispatch-sync [:req-test/read-empty] {:frame server-frame})

      (is (= [:present nil] @observed)
          "the cofx assoc's the key with a nil value when the slot is empty"))))

;; ---- platforms gating ------------------------------------------------------
;;
;; Per Spec 011 §634-642 and cofx.cljc's gate: a :platforms #{:server}
;; cofx is skipped when injected on a client-side frame. The runtime
;; emits :rf.cofx/skipped-on-platform (warning, :recovery :skipped) and
;; the handler chain continues — only the injection is skipped, not
;; the dispatch.

(deftest cofx-is-skipped-on-client-frame
  (testing ":rf.server/request is skipped on a :platform :client frame
            and emits :rf.cofx/skipped-on-platform"
    (let [client-frame (rf.frame/make-anon-frame-record! {:platform :client})
          observed     (atom :unset)]
      (with-trace-recorder! [traces]
        (rf/reg-event :req-test/read-on-client
          {:rf.cofx/requires [:rf.server/request]}
          (fn [{:keys [rf.server/request] :as ctx} _]
            (reset! observed
                    (if (contains? ctx :rf.server/request)
                      [:present request]
                      [:absent  request]))
            {}))
        (rf/dispatch-sync [:req-test/read-on-client] {:frame client-frame})

        ;; SEMANTIC, posture-independent: the platform gate really
        ;; excluded the cofx. `:absent` (not merely nil-valued) is the
        ;; production-visible witness — the coeffect KEY is missing, which is
        ;; exactly what the trace below narrates.
        (is (= [:absent nil] @observed)
            "the cofx did NOT run — :rf.server/request is absent from coeffects")

        ;; Dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %))
                              @traces)]
            (is (= 1 (count skips))
                "exactly one :rf.cofx/skipped-on-platform trace was emitted")
            (let [t (first skips)]
              (is (= :rf.server/request (get-in t [:tags :rf.cofx/id]))
                  ":rf.cofx/id identifies the gated cofx")
              (is (= :client (get-in t [:tags :rf.cofx/platform]))
                  ":rf.cofx/platform carries the active platform that excluded the cofx")
              (is (= #{:server} (get-in t [:tags :rf.cofx/registered-platforms]))
                  ":rf.cofx/registered-platforms surfaces the cofx's declared set")
              (is (= :skipped (:recovery t))
                  ":recovery is :skipped — the runtime declined to act"))))))))

;; ---- frame isolation -------------------------------------------------------
;;
;; The per-frame slot mechanism's whole point: two concurrent per-
;; request frames (the normal SSR shape under load) carry independent
;; request data. If the impl used a single dynamic var or a global
;; atom, request A's data would leak into request B's handler.

(deftest two-frames-carry-independent-request-data
  (testing "two simultaneous per-request frames have isolated request slots"
    (let [frame-a    (rf.frame/make-anon-frame-record! {:platform :server :doc "request A"})
          frame-b    (rf.frame/make-anon-frame-record! {:platform :server :doc "request B"})
          request-a  {:uri "/articles/aaa" :headers {"cookie" "session=user-a"}}
          request-b  {:uri "/articles/bbb" :headers {"cookie" "session=user-b"}}
          observed-a (atom :unset)
          observed-b (atom :unset)]
      (rf.ssr/set-request! frame-a request-a)
      (rf.ssr/set-request! frame-b request-b)

      (rf/reg-event :req-test/read-isolated
        {:rf.cofx/requires [:rf.server/request]}
        ;; The running frame's stamp reaches the event context under
        ;; :rf.frame/id (there is no bare :frame coeffect).
        (fn [{:keys [rf.server/request] frame :rf.frame/id} _]
          (cond
            (= frame frame-a) (reset! observed-a request)
            (= frame frame-b) (reset! observed-b request))
          {}))

      (rf/dispatch-sync [:req-test/read-isolated] {:frame frame-a})
      (rf/dispatch-sync [:req-test/read-isolated] {:frame frame-b})

      (is (= request-a @observed-a)
          "frame A's handler saw frame A's request")
      (is (= request-b @observed-b)
          "frame B's handler saw frame B's request — no leak from A")
      ;; Independent reads via the public surface.
      (is (= request-a (rf.ssr/get-request frame-a)))
      (is (= request-b (rf.ssr/get-request frame-b))))))

(deftest clear-request-removes-the-slot
  (testing "clear-request! removes the per-frame slot — subsequent reads return nil"
    (let [server-frame (rf.frame/make-anon-frame-record! {:platform :server})
          request      {:uri "/x"}]
      (rf.ssr/set-request! server-frame request)
      (is (= request (rf.ssr/get-request server-frame)))
      (rf.ssr/clear-request! server-frame)
      (is (nil? (rf.ssr/get-request server-frame))
          "the slot was cleared"))))
