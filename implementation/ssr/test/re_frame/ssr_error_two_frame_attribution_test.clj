(ns re-frame.ssr-error-two-frame-attribution-test
  "Per rf2-7d30s — the two-server-frame error-attribution regression.

  Context. `re-frame.ssr.error-listener/candidate-frame-for-error`
  routes an error trace to a response accumulator by the frame named in
  the trace's `[:tags :frame]`. It USED to carry a fallback: when the
  trace lacked `:frame`, it guessed `the single active server frame if
  exactly one exists`. Under concurrent SSR there are MANY simultaneous
  server frames (the canonical request shape — see ssr-ring's
  `concurrency_stress_test`), so the fallback silently returned nil with
  >1 frame live: no projector ran, the public-error `:status` was never
  stamped, and the response defaulted to 200 for a request that should
  have been a 4xx. The single-frame fallback ALSO masked this in every
  existing test, since each test ran exactly one frame.

  The fix (rf2-7d30s) stamps `[:tags :frame]` at every error-emit site
  reachable inside a server-frame drain (here: the navigate-reject
  `:rf.error/schema-validation-failure` at routing/navigate.cljc, which
  the in-flight cascade's `:frame` cofx attributes), THEN removes the
  fallback so an unroutable trace no-ops EXPLICITLY rather than guessing.

  This suite drives the canonical concurrent shape — TWO live server
  frames — and proves:

    1. A navigate-reject in ONE frame stamps the projected 4xx on THAT
       frame's response accumulator only; the sibling stays clean. With
       >1 server frame live this can only succeed if the trace carries
       the emitting frame's `:frame` (the removed fallback would have
       returned nil and stamped nothing).

    2. The emitted error trace carries `[:tags :frame]` = the emitting
       frame — the precondition `re-frame.epoch.capture/capture-event!`
       gates on (capture.cljc skips frame-less traces), so the violation
       is now visible in the emitting frame's epoch / Xray rather than
       silently dropped. Asserted at the trace-tag level so the suite
       does not pull the epoch artefact onto the ssr test classpath.

  Companion suites:
    - `re-frame.ssr-end-to-end-test` — single-frame default-projector
      coverage (no-such-handler → 404, handler-exception → 500).
    - `re-frame.ssr-error-projector-substrate-test` — the always-on
      error-emit substrate install under production hardening.
    - `re-frame.ssr.ring.concurrency-stress-test` — the live-host
      concurrent-frame stress shape this regression's two-frame setup
      mirrors at the unit level.

  ## Posture split (rf2-lwtlk)

  Tests (1)-(3) drive the attribution contract through ONE trigger — the
  navigate-reject — and that trigger does not exist in a production build.
  `route-url`'s `:params` check is boundary validation, and every
  `validate-*!` body returns `true` unconditionally under
  `-Dre-frame.debug=false` (Spec 010 §Production builds), so no reject
  fires, no `:rf.error/schema-validation-failure` is emitted, and the
  responses stay 200. They are kept VERBATIM inside
  `(when interop/debug-enabled? …)` arms. Test (3) is doubly dev-scoped:
  it reads the DEV trace bus and its subject is epoch / Xray capture.

  GUARDING THEM ALONE WOULD HAVE BEEN A FALSE GREEN. Per-frame error
  attribution is not a dev contract — it is the invariant that stops one
  concurrent request's failure stamping another's response, and it is
  worth most on a production server. So test (4) pins the SAME contract
  through a trigger that survives the gate: a real throwing handler, whose
  `:rf.error/handler-exception` rides the always-on axis through
  `dispatch-on-error!` and is routed by `error-emit-projection-listener`'s
  own `(:frame record)` attribution — a DIFFERENT code path from the dev
  listener's `candidate-frame-for-error`, and one nothing else pinned with
  a sibling server frame live."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; Stub validator — interpret a `:params` schema as a Clojure predicate
;; `(fn [v] truthy?)`, mirroring routing_test's `with-stub-validator` so
;; the navigate-reject path runs without dragging in Malli.
;; ---------------------------------------------------------------------------

(defn- with-stub-validator []
  (let [snap     (rf.schemas/schema-fns)
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
    (rf.schemas/set-schema-fns! {:validate validate :explain explain})
    (fn [] (rf.schemas/set-schema-fns! snap))))

(def ^:private frame-a :ssr/req-a)
(def ^:private frame-b :ssr/req-b)

(defn- register-routes-and-fx! []
  (rf/reg-route :route/home {} "/")
  ;; A route whose `:params` predicate rejects any `:id` not starting "a".
  ;; A navigate with a bad `:id` makes `route-url` throw → navigate.cljc
  ;; catches it → emits `:rf.error/schema-validation-failure :where :event`.
  (rf/reg-route :route/article
                {:params (fn [{:keys [id]}]
                           (str/starts-with? (or id "") "a"))} "/articles/:id")
  ;; Server-platform push-url so the navigate fx assembly resolves on a
  ;; `:platform :server` frame (no-op sink — the reject path never pushes).
  (rf.fx/reg-fx :rf.nav/push-url
             {:platforms #{:server :client}}
             (fn [_ _url] nil)))

(defn- make-server-frame [frame-id]
  ;; An EXPLICIT `:id` so the two frames carry STABLE, named ids (an
  ;; id-less `make-frame` gensyms an anonymous one). Returns the ID —
  ;; these tests compare trace `[:tags :frame]` stamps and read the
  ;; per-frame response accumulator by id. Both are
  ;; `:platform :server` so `error-projector/server-frame?` recognises
  ;; them as the live server frames the (removed) fallback enumerated.
  (rf/make-frame {:id frame-id :platform :server
                  :ssr      {:public-error-id   :rf.ssr/default-error-projector
                             :dev-error-detail? false}})
  frame-id)

;; ===========================================================================
;; (1) Two live server frames — a navigate-reject in ONE stamps 400 on THAT
;;     frame only; the sibling stays clean.
;; ===========================================================================

(deftest two-server-frames-navigate-reject-stamps-only-the-emitting-frame
  (testing "rf2-7d30s: with TWO live server frames, a navigate-reject
            (`:rf.error/schema-validation-failure :where :event`) in
            frame-a stamps the default projector's 400 on frame-a's
            response accumulator ONLY — frame-b is untouched. Proves the
            `:frame` stamp routes per-frame and the removed single-frame
            fallback does not regress (the fallback returned nil with >1
            server frame, stamping nothing)."
    ;; rf2-lwtlk — DEV ARM. The navigate-reject trigger is production-elided
    ;; (Spec 010 §Production builds): under the gate the params validate
    ;; vacuously, nothing is rejected, and both frames would sit at 200. The
    ;; production-posture pin for this same contract is test (4).
    (when rf.interop/debug-enabled?
      (let [restore (with-stub-validator)]
        (try
          (register-routes-and-fx!)
          (let [fa (make-server-frame frame-a)
                fb (make-server-frame frame-b)]
            ;; BOTH frames are live + registered server frames — the exact
            ;; >1-server-frame shape the removed fallback could not handle.
            ;; Caller bug routed to frame-a only: `:id "zoo"` fails the
            ;; route's `:params` predicate → reject → schema-validation-failure.
            (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}]
                              {:frame fa})

            (is (= 400 (:status (rf.ssr/get-response fa)))
                "frame-a's navigate-reject is projected to 400 on frame-a's
                 response — the `:frame` stamp routed the trace to the
                 emitting frame even with a sibling server frame live")
            (is (= 200 (:status (rf.ssr/get-response fb)))
                "frame-b's response stays at the default 200 (Spec 011
                 §Status defaults) — the error did not bleed onto the
                 sibling. The removed single-frame fallback would have
                 no-op'd with >1 server frame, masking the per-frame
                 contract this asserts."))
          (finally (restore)))))))

;; ===========================================================================
;; (2) The other frame can ALSO reject independently — symmetry check that
;;     attribution is genuinely per-frame, not first-frame-wins.
;; ===========================================================================

(deftest two-server-frames-navigate-reject-attributes-each-frame-independently
  (testing "rf2-7d30s: a reject in frame-b stamps frame-b's 400 while
            frame-a stays clean — the mirror of test (1), proving
            attribution follows the EMITTING frame in both directions
            (not a fixed/first-registered server frame)."
    ;; rf2-lwtlk — DEV ARM, same reason as test (1). Test (4) mirrors this
    ;; symmetry check on the always-on axis.
    (when rf.interop/debug-enabled?
      (let [restore (with-stub-validator)]
        (try
          (register-routes-and-fx!)
          (let [fa (make-server-frame frame-a)
                fb (make-server-frame frame-b)]
            (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}]
                              {:frame fb})
            (is (= 400 (:status (rf.ssr/get-response fb)))
                "frame-b (the emitting frame) gets the projected 400")
            (is (= 200 (:status (rf.ssr/get-response fa)))
                "frame-a stays at the default 200 — clean"))
          (finally (restore)))))))

;; ===========================================================================
;; (3) Epoch-visibility precondition — the navigate-reject trace now carries
;;     `[:tags :frame]`, the key `re-frame.epoch.capture/capture-event!`
;;     gates on. Without it the violation is invisible to epoch / Xray.
;; ===========================================================================

(deftest navigate-reject-trace-carries-frame-for-epoch-capture
  (testing "rf2-7d30s: the navigate-reject `:rf.error/schema-validation-
            failure` trace carries `[:tags :frame]` = the emitting frame.
            `re-frame.epoch.capture/capture-event!` buffers a trace into
            the in-flight cascade ONLY when its tags carry the cascade's
            `:frame`; pre-fix this trace was unframed and silently dropped
            from the per-frame epoch record (and so invisible to the Xray
            Issues / Schema-timeline lens). Asserted at the trace-tag
            level so this suite does not pull the epoch artefact onto the
            ssr test classpath."
    ;; rf2-lwtlk — DEV ARM, doubly so: the trigger is production-elided AND
    ;; the assertions read the DEV trace bus, whose subject here (epoch /
    ;; Xray capture) is dev tooling. Nothing about this test has a
    ;; production counterpart, and that is correct rather than a gap.
    (when rf.interop/debug-enabled?
      (let [restore (with-stub-validator)
            traces  (atom [])]
        (try
          (register-routes-and-fx!)
          (let [fa (make-server-frame frame-a)]
            (rf/register-listener! :trace ::cap (fn [ev] (swap! traces conj ev)))
            (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}]
                              {:frame fa})
            (rf/unregister-listener! :trace ::cap)
            (let [err (first (filter #(= :rf.error/schema-validation-failure
                                         (:operation %))
                                     @traces))]
              (is (some? err)
                  ":rf.error/schema-validation-failure emitted on the reject")
              (is (= :event (-> err :tags :where))
                  "the navigate-reject path tags :where :event")
              (is (= fa (-> err :tags :frame))
                  "the trace carries [:tags :frame] = the emitting frame —
                   the precondition epoch capture gates on, so the violation
                   lands in frame-a's epoch record (visible to Xray) rather
                   than being dropped")))
          (finally (restore)))))))

;; ===========================================================================
;; (4) THE PRODUCTION-POSTURE PIN (rf2-lwtlk). The same per-frame
;;     attribution contract, driven by a trigger that survives
;;     `-Dre-frame.debug=false`: a handler that throws. Its
;;     `:rf.error/handler-exception` rides the always-on axis via
;;     `dispatch-on-error!`, and `error-emit-projection-listener` routes it
;;     by the record's own `:frame` slot — a different attribution path
;;     from the dev listener's `candidate-frame-for-error`, exercised here
;;     with a sibling server frame live in both directions.
;; ===========================================================================

(deftest always-on-handler-exception-attributes-each-frame-independently
  (testing "rf2-7d30s / rf2-lwtlk: with TWO live server frames, a throwing
            handler dispatched to frame-a projects 500 on frame-a's
            response ONLY — frame-b keeps its default 200 — and the mirror
            holds when frame-b is the one that throws. This is the
            concurrent-SSR invariant on the axis that actually ships: with
            no per-record `:frame`, or with a first-registered-frame
            fallback, one request's crash would stamp a sibling request's
            response."
    (register-routes-and-fx!)
    (rf/reg-event :boom/throw (fn [_ _] (throw (ex-info "handler boom" {}))))
    (let [fa (make-server-frame frame-a)
          fb (make-server-frame frame-b)]
      (rf/dispatch-sync [:boom/throw] {:frame fa})
      (is (= 500 (:status (rf.ssr/get-response fa)))
          "frame-a's handler exception is projected to 500 on frame-a's
           response — the always-on record carried the emitting frame")
      (is (= 200 (:status (rf.ssr/get-response fb)))
          "frame-b's response stays at the default 200 — no bleed onto the
           concurrent sibling")

      (rf/dispatch-sync [:boom/throw] {:frame fb})
      (is (= 500 (:status (rf.ssr/get-response fb)))
          "and the mirror: frame-b now carries its OWN 500, so attribution
           follows the emitting frame in both directions rather than a
           fixed / first-registered server frame"))))
