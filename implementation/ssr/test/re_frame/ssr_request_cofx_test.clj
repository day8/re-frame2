(ns re-frame.ssr-request-cofx-test
  "Coverage for the :rf.server/request cofx + per-frame request slot
  (rf2-e825b). Per Spec 011 §Server-only `reg-cofx` for request context.

  The cofx surfaces the active HTTP request map to event handlers so
  setup events can read URL, headers, session cookies, etc. without
  threading the request through as an event arg. Mechanism:

    1. The host adapter (rf2-ny6v7 ships the Ring adapter) populates
       the per-frame request slot via `re-frame.ssr/set-request!`
       before kicking off the drain.
    2. Event handlers use `(rf/inject-cofx :rf.server/request)` and
       read the request map under `:rf.server/request` in their
       coeffects.
    3. After the response is materialised, the host adapter calls
       `clear-request!` (typically as part of frame teardown).

  This test pins the cofx-registration, the read path, the platforms
  gating (client-side dispatches silently no-op), the frame-isolation
  invariant (two simultaneous per-request frames don't leak), and the
  explicit-value override surface (2-arity form for tests/harnesses
  that drive the drain without a host adapter)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

;; Shared reset fixture lives in `re-frame.ssr.test-fixture` (rf2-i3qc0).
(use-fixtures :each tf/reset-runtime)

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that
  accumulates events. Tests must (rf/unregister-trace-listener! id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-trace-listener! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- registration -----------------------------------------------------------
;;
;; The :rf.server/request cofx must be present in the cofx registry at
;; namespace-load time — same model as the :rf.server/* fxs.

(deftest cofx-is-registered-after-namespace-load
  (testing ":rf.server/request resolves in the cofx registry"
    (let [meta (registrar/lookup :cofx :rf.server/request)]
      (is (some? meta)
          "the cofx registry holds an entry under :rf.server/request")
      (is (fn? (:handler-fn meta))
          "the entry carries a :handler-fn")
      (is (= #{:server} (:platforms meta))
          ":platforms #{:server} per Spec 011 §634-642 — server-only")
      (is (string? (:doc meta))
          "the registration carries a :doc string"))))

;; ---- read path: populated slot ---------------------------------------------
;;
;; The canonical pattern: host adapter writes the request to the per-
;; frame slot before drain; a server-side event handler reads it via
;; (inject-cofx :rf.server/request).

(deftest cofx-reads-populated-request
  (testing "(set-request! frame req) → (inject-cofx :rf.server/request) → handler reads req"
    (let [server-frame (rf/make-frame
                         {:doc      "SSR request frame"
                          :platform :server})
          request      {:request-method :get
                        :uri            "/articles/42"
                        :headers        {"accept"   "text/html"
                                         "cookie"   "session=abc123"}
                        :query-string   "preview=1"
                        :server-name    "example.com"
                        :scheme         :https}
          observed     (atom :unset)]
      ;; Host adapter populates the slot.
      (ssr/set-request! server-frame request)
      ;; A server-side handler reads it via the cofx.
      (rf/reg-event-fx :req-test/read
        [(rf/inject-cofx :rf.server/request)]
        (fn [{:keys [rf.server/request]} _]
          (reset! observed request)
          {}))
      (rf/dispatch-sync [:req-test/read] {:frame server-frame})

      (is (= request @observed)
          "the handler saw the request map that was placed in the slot"))))

(deftest get-request-mirrors-cofx
  (testing "ssr/get-request is the public read surface — same value as the cofx"
    (let [server-frame (rf/make-frame {:platform :server})
          request      {:request-method :post
                        :uri            "/api/articles"
                        :body           "{\"title\":\"new\"}"}]
      (ssr/set-request! server-frame request)
      (is (= request (ssr/get-request server-frame))
          "get-request returns the host-supplied map verbatim"))))

;; ---- unpopulated slot ------------------------------------------------------
;;
;; If no host adapter populated the slot (e.g. tests that drive the
;; drain directly, or a misconfigured deployment), the cofx injects nil
;; rather than failing — handlers can branch on `(nil? request)`.

(deftest cofx-injects-nil-when-slot-unpopulated
  (testing "cofx returns nil when no host adapter has populated the slot"
    (let [server-frame (rf/make-frame {:platform :server})
          observed     (atom :unset)]
      (rf/reg-event-fx :req-test/read-empty
        [(rf/inject-cofx :rf.server/request)]
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
    (let [client-frame (rf/make-frame {:platform :client})
          traces       (collect-traces! ::req-client)
          observed     (atom :unset)]
      (rf/reg-event-fx :req-test/read-on-client
        [(rf/inject-cofx :rf.server/request)]
        (fn [{:keys [rf.server/request] :as ctx} _]
          (reset! observed
                  (if (contains? ctx :rf.server/request)
                    [:present request]
                    [:absent  request]))
          {}))
      (rf/dispatch-sync [:req-test/read-on-client] {:frame client-frame})
      (rf/unregister-trace-listener! ::req-client)

      (is (= [:absent nil] @observed)
          "the cofx did NOT run — :rf.server/request is absent from coeffects")

      (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %))
                          @traces)]
        (is (= 1 (count skips))
            "exactly one :rf.cofx/skipped-on-platform trace was emitted")
        (let [t (first skips)]
          (is (= :rf.server/request (get-in t [:tags :cofx-id]))
              ":cofx-id identifies the gated cofx")
          (is (= :client (get-in t [:tags :platform]))
              ":platform carries the active platform that excluded the cofx")
          (is (= #{:server} (get-in t [:tags :registered-platforms]))
              ":registered-platforms surfaces the cofx's declared set")
          (is (= :skipped (:recovery t))
              ":recovery is :skipped — the runtime declined to act"))))))

;; ---- frame isolation -------------------------------------------------------
;;
;; The per-frame slot mechanism's whole point: two concurrent per-
;; request frames (the normal SSR shape under load) carry independent
;; request data. If the impl used a single dynamic var or a global
;; atom, request A's data would leak into request B's handler.

(deftest two-frames-carry-independent-request-data
  (testing "two simultaneous per-request frames have isolated request slots"
    (let [frame-a    (rf/make-frame {:platform :server :doc "request A"})
          frame-b    (rf/make-frame {:platform :server :doc "request B"})
          request-a  {:uri "/articles/aaa" :headers {"cookie" "session=user-a"}}
          request-b  {:uri "/articles/bbb" :headers {"cookie" "session=user-b"}}
          observed-a (atom :unset)
          observed-b (atom :unset)]
      (ssr/set-request! frame-a request-a)
      (ssr/set-request! frame-b request-b)

      (rf/reg-event-fx :req-test/read-isolated
        [(rf/inject-cofx :rf.server/request)]
        (fn [{:keys [rf.server/request frame]} _]
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
      (is (= request-a (ssr/get-request frame-a)))
      (is (= request-b (ssr/get-request frame-b))))))

(deftest clear-request-removes-the-slot
  (testing "clear-request! removes the per-frame slot — subsequent reads return nil"
    (let [server-frame (rf/make-frame {:platform :server})
          request      {:uri "/x"}]
      (ssr/set-request! server-frame request)
      (is (= request (ssr/get-request server-frame)))
      (ssr/clear-request! server-frame)
      (is (nil? (ssr/get-request server-frame))
          "the slot was cleared")

      ;; The cofx now injects nil.
      (let [observed (atom :unset)]
        (rf/reg-event-fx :req-test/read-after-clear
          [(rf/inject-cofx :rf.server/request)]
          (fn [{:keys [rf.server/request]} _]
            (reset! observed request)
            {}))
        (rf/dispatch-sync [:req-test/read-after-clear] {:frame server-frame})
        (is (nil? @observed)
            "the cofx injects nil after clear-request!")))))

;; ---- explicit-value override (2-arity inject-cofx) -------------------------
;;
;; Tests and conformance harnesses that drive the drain without a host
;; adapter can supply the request inline. The 2-arity form bypasses the
;; slot lookup.

(deftest cofx-2-arity-injects-explicit-value
  (testing "(inject-cofx :rf.server/request {...}) injects the explicit value
            even when no host adapter has populated the slot"
    (let [server-frame (rf/make-frame {:platform :server})
          explicit     {:uri "/explicit" :headers {"x-test" "1"}}
          observed     (atom :unset)]
      ;; No set-request! call — the slot is empty.
      (is (nil? (ssr/get-request server-frame)))
      (rf/reg-event-fx :req-test/read-explicit
        [(rf/inject-cofx :rf.server/request explicit)]
        (fn [{:keys [rf.server/request]} _]
          (reset! observed request)
          {}))
      (rf/dispatch-sync [:req-test/read-explicit] {:frame server-frame})

      (is (= explicit @observed)
          "the 2-arity form injects the explicit value, bypassing the slot"))))

;; ---- :ssr-server preset frames --------------------------------------------
;;
;; The :ssr-server preset (frame.cljc §preset-expansion) sets
;; :platform :server — confirm the cofx works under the preset shape
;; the same way it does with an explicit :platform :server.

(deftest cofx-works-under-ssr-server-preset
  (testing "the cofx surfaces the request under a :preset :ssr-server frame"
    (let [server-frame (rf/make-frame {:preset :ssr-server})
          request      {:uri "/preset" :request-method :get}
          observed     (atom :unset)]
      (ssr/set-request! server-frame request)
      (rf/reg-event-fx :req-test/read-preset
        [(rf/inject-cofx :rf.server/request)]
        (fn [{:keys [rf.server/request]} _]
          (reset! observed request)
          {}))
      (rf/dispatch-sync [:req-test/read-preset] {:frame server-frame})

      (is (= request @observed)
          "the cofx flows the request through under the :ssr-server preset"))))
