(ns re-frame.frame-destroyed-subscribe-devtrace-identity-cljs-test
  "rf2-wd4ac (dev-trace arm) — the `:rf.error/frame-destroyed` DEV-TRACE `:event`
  tag carries a raw subscription QUERY VECTOR in the `:subscribe` realm (public
  IDENTITY — rf2-zwgqe / rf2-alk8a / Spec 015), and the classification projector
  must NOT redact it.

  ## The residual this file pins closed

  #6516 (the wd4ac original) fixed the UI PRODUCER: `re-frame.ui.frames`'
  `emit-and-throw-frame-destroyed!` passed the raw query vector on BOTH the
  always-on `:event` slot AND the dev-trace `:event` tag for the `:subscribe`
  realm. That tree was removed on 2026-08-16 (rf2-0yp7w); the live producer of
  the same shape is `router/emit-frame-destroyed!`, reached from
  `capture-frame`'s superseded-`:subscribe` seam (`capture-subscribe!` via
  `emit-captured-frame-superseded!`), which passes the attempted query vector
  as `:event` under `:op :subscribe`. The always-on egress (axis 1) then stays
  raw because
  `error-emit/raw-identity-query-vector-event?` skips elision keyed on
  `(:rf.error/frame-destroyed, :op :subscribe)`.

  But the DEV-TRACE egress (axis 2) flows through
  `re-frame.classification/project-trace-event`, which had NO realm-awareness:
  it unconditionally ran the bare `:event` tag through
  `redact-event-by-registration`, treating EVERY `:event` tag as a dispatched
  event. Events and subscriptions live in SEPARATE registries, so a sub id may
  LEGALLY collide with an event id. When it does, the colliding EVENT
  registration's `:sensitive` paths were applied to the raw subscribe query
  vector — mutating public identity at dev-trace egress. #6516's own dev-trace
  assertion was VACUOUS against this: its query head (`:reinc/n`) was registered
  only as a sub, so `redact-event-by-registration` was a no-op and the raw value
  survived whether or not the projector was realm-aware.

  ## The fix (rf2-wd4ac)

  `project-trace-event` now SKIPS bare-`:event` registration projection for
  `:rf.error/frame-destroyed` + `:op :subscribe` ONLY — mirroring the always-on
  record's `raw-identity-query-vector-event?` skip on the same realm. Every
  other `:event` tag still projects, INCLUDING the `:dispatch` / `:dispatch-sync`
  frame-destroyed realms (whose `:event` IS a dispatched-event payload — they
  KEEP their registration redaction).

  ## Proof discipline (non-vacuity)

  The collision is made REAL: an event registered under the SAME keyword as the
  subscription declares `:token` sensitive, and the control assertion proves the
  SAME registration DOES redact that vector through `redact-event-by-registration`
  (the machinery is live and WOULD have bitten) — so the subscribe realm's raw
  egress is the GUARD's doing, not an absent registration. The dispatch
  counterpaths assert the redaction still lands, and capture stays payload-free.

  Dual-runtime `*_cljs_test.cljc`: the shadow-cljs `:node-test`
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Plain CLJC, no DOM dependency; `=` on keyword operands, never `identical?`
  (#6365)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; A sub id that COLLIDES with an event id (legal — separate registries).
(def ^:private shared-id :audit6516/same-id)
;; The sub's parameterized query vector — public IDENTITY. `:token` is the arg
;; the colliding event registration classifies sensitive.
(def ^:private secret :RAW-IDENTITY)
(def ^:private query-v [shared-id {:token secret}])
;; The value the registration redacts the arg-map's `:token` to — the mutation
;; the subscribe realm must NOT suffer, and the dispatch realm MUST.
(def ^:private redacted [shared-id {:token rf.privacy/redacted-sentinel}])

(defn- register-colliding-event! []
  ;; Register an EVENT under the SAME keyword as the subscription, declaring
  ;; `:token` sensitive. `registration-classification :event shared-id` now
  ;; derives `{:sensitive [[:token]]}`, so `redact-event-by-registration`
  ;; redacts the arg-map's `:token`. Events and subs are separate registries —
  ;; nothing about this makes the keyword a subscription.
  (rf.registrar/register! :event shared-id {:sensitive [[:token]]}))

(defn- frame-destroyed-devtrace
  "The axis-2 dev-trace event `router/emit-frame-destroyed!` fans (router.cljc):
  `{:operation :rf.error/frame-destroyed :tags {:frame … :op … :event … …}}`."
  [op event]
  {:operation :rf.error/frame-destroyed
   :tags {:frame :some-frame :op op :event event :reason :frame-destroyed}})

(defn- project-event
  "Project a frame-destroyed dev-trace through the single dev-trace chokepoint
  and return its `:event` tag as the consumer would receive it."
  [op event]
  (get-in (rf.classification/project-trace-event (frame-destroyed-devtrace op event))
          [:tags :event]))

;; ---------------------------------------------------------------------------
;; 1. Headline — the subscribe realm preserves the raw query vector even with a
;;    colliding, matching event registration (RED before rf2-wd4ac dev arm).
;; ---------------------------------------------------------------------------

(deftest frame-destroyed-subscribe-devtrace-preserves-raw-query-identity
  (testing "a `:rf.error/frame-destroyed` + `:op :subscribe` dev trace ships its
            query vector VERBATIM through project-trace-event even when a
            same-id EVENT registration declares a matching `:sensitive` path."
    (register-colliding-event!)
    ;; Machinery intact: the SAME registration DOES redact this exact vector
    ;; when routed through the event-vector chokepoint — so a raw subscribe
    ;; egress below is the realm GUARD's doing, not an absent registration
    ;; (the non-vacuity the #6516 dev-trace assertion lacked).
    (is (= redacted (rf.classification/redact-event-by-registration query-v))
        "the colliding EVENT registration WOULD redact :token — machinery live")
    ;; THE FIX: the subscribe realm skips bare-event projection.
    (is (= query-v (project-event :subscribe query-v))
        "frame-destroyed :subscribe dev-trace :event is the RAW query vector, VERBATIM")
    (is (not= redacted (project-event :subscribe query-v))
        "the query vector is NOT mutated to the redaction the registration would apply")
    ;; Non-destructive: projection is egress-only — the raw vector is untouched.
    (is (= secret (get-in query-v [1 :token]))
        "projection did not mutate the raw query vector")))

;; ---------------------------------------------------------------------------
;; 2. Dispatch counterpath — the dispatch realms STILL project (WHAT-STAYS): a
;;    dispatched event vector is payload, and keeps its registration redaction.
;; ---------------------------------------------------------------------------

(deftest frame-destroyed-dispatch-devtrace-still-projects
  (testing "the `:dispatch` / `:dispatch-sync` frame-destroyed realms carry a
            dispatched EVENT payload, so their dev-trace :event tag KEEPS the
            registration redaction — the guard is realm-precise, not a blanket
            frame-destroyed skip."
    (register-colliding-event!)
    (is (= redacted (project-event :dispatch query-v))
        "frame-destroyed :dispatch dev-trace :event keeps its registration redaction")
    (is (= redacted (project-event :dispatch-sync query-v))
        "frame-destroyed :dispatch-sync dev-trace :event keeps its registration redaction")))

;; ---------------------------------------------------------------------------
;; 3. Capture — payload-free (WHAT-STAYS): the capture arm ran no op, so its
;;    dev-trace :event is nil; projection leaves it nil.
;; ---------------------------------------------------------------------------

(deftest frame-destroyed-capture-devtrace-stays-payload-free
  (testing "the `:capture` realm carries no op payload — its dev-trace :event is
            nil, and projection is a no-op even with the colliding registration."
    (register-colliding-event!)
    (is (nil? (project-event :capture nil))
        "frame-destroyed :capture dev-trace :event stays payload-free (nil)")))

;; ---------------------------------------------------------------------------
;; 4. Guard scope — the skip requires BOTH conjuncts. A NON-frame-destroyed
;;    operation carrying `:event` still projects, so the guard cannot leak the
;;    raw-identity exemption onto ordinary dispatched-event error traces.
;; ---------------------------------------------------------------------------

(deftest guard-scoped-to-frame-destroyed-operation
  (testing "the raw-identity skip is scoped to :rf.error/frame-destroyed — a
            different error operation carrying the same :event tag still projects
            through its event registration (the operation conjunct is load-bearing)."
    (register-colliding-event!)
    (let [ev {:operation :rf.error/handler-exception
              :tags {:frame :some-frame :op :subscribe :event query-v}}]
      (is (= redacted (get-in (rf.classification/project-trace-event ev) [:tags :event]))
          "a non-frame-destroyed op's :event tag is NOT exempted — still redacted"))))
