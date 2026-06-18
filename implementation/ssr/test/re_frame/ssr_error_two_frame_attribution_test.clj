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
      mirrors at the unit level."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---------------------------------------------------------------------------
;; Stub validator — interpret a `:params` schema as a Clojure predicate
;; `(fn [v] truthy?)`, mirroring routing_test's `with-stub-validator` so
;; the navigate-reject path runs without dragging in Malli.
;; ---------------------------------------------------------------------------

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

(def ^:private frame-a :ssr/req-a)
(def ^:private frame-b :ssr/req-b)

(defn- register-routes-and-fx! []
  (rf/reg-route :route/home {:path "/"})
  ;; A route whose `:params` predicate rejects any `:id` not starting "a".
  ;; A navigate with a bad `:id` makes `route-url` throw → navigate.cljc
  ;; catches it → emits `:rf.error/schema-validation-failure :where :event`.
  (rf/reg-route :route/article
                {:path   "/articles/:id"
                 :params (fn [{:keys [id]}]
                           (str/starts-with? (or id "") "a"))})
  ;; Server-platform push-url so the navigate fx assembly resolves on a
  ;; `:platform :server` frame (no-op sink — the reject path never pushes).
  (rf/reg-fx :rf.nav/push-url
             {:platforms #{:server :client}}
             (fn [_ _url] nil)))

(defn- make-server-frame [frame-id]
  ;; `reg-frame` (not `make-frame`) so the two frames carry STABLE,
  ;; named ids — `make-frame` gensyms an anonymous id. Both are
  ;; `:platform :server` so `error-projector/server-frame?` recognises
  ;; them as the live server frames the (removed) fallback enumerated.
  (rf/reg-frame frame-id
    {:platform :server
     :ssr      {:public-error-id   :rf.ssr/default-error-projector
                :dev-error-detail? false}}))

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
    (let [restore (with-stub-validator)]
      (try
        (register-routes-and-fx!)
        (let [fa (make-server-frame frame-a)
              fb (make-server-frame frame-b)]
          ;; BOTH frames are live + registered server frames — the exact
          ;; >1-server-frame shape the removed fallback could not handle.
          ;; Caller bug routed to frame-a only: `:id "zoo"` fails the
          ;; route's `:params` predicate → reject → schema-validation-failure.
          (rf/dispatch-sync [:rf.route/navigate :route/article {:id "zoo"}]
                            {:frame fa})

          (is (= 400 (:status (ssr/get-response fa)))
              "frame-a's navigate-reject is projected to 400 on frame-a's
               response — the `:frame` stamp routed the trace to the
               emitting frame even with a sibling server frame live")
          (is (= 200 (:status (ssr/get-response fb)))
              "frame-b's response stays at the default 200 (Spec 011
               §Status defaults) — the error did not bleed onto the
               sibling. The removed single-frame fallback would have
               no-op'd with >1 server frame, masking the per-frame
               contract this asserts."))
        (finally (restore))))))

;; ===========================================================================
;; (2) The other frame can ALSO reject independently — symmetry check that
;;     attribution is genuinely per-frame, not first-frame-wins.
;; ===========================================================================

(deftest two-server-frames-navigate-reject-attributes-each-frame-independently
  (testing "rf2-7d30s: a reject in frame-b stamps frame-b's 400 while
            frame-a stays clean — the mirror of test (1), proving
            attribution follows the EMITTING frame in both directions
            (not a fixed/first-registered server frame)."
    (let [restore (with-stub-validator)]
      (try
        (register-routes-and-fx!)
        (let [fa (make-server-frame frame-a)
              fb (make-server-frame frame-b)]
          (rf/dispatch-sync [:rf.route/navigate :route/article {:id "zoo"}]
                            {:frame fb})
          (is (= 400 (:status (ssr/get-response fb)))
              "frame-b (the emitting frame) gets the projected 400")
          (is (= 200 (:status (ssr/get-response fa)))
              "frame-a stays at the default 200 — clean"))
        (finally (restore))))))

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
    (let [restore (with-stub-validator)
          traces  (atom [])]
      (try
        (register-routes-and-fx!)
        (let [fa (make-server-frame frame-a)]
          (rf/register-listener! :trace ::cap (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:rf.route/navigate :route/article {:id "zoo"}]
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
        (finally (restore))))))
