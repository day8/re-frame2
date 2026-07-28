(ns re-frame.machine-routed-event-classification-cljs-test
  "rf2-ghgbqi (agb5jk item 2) — the machine trace projector redacts the
  ROUTED inner event echoed into the machine trace slots, not only the
  durable `:data` snapshot.

  Spec 005 §Trace events: when an event is ROUTED THROUGH a machine
  (`[:machine-id <inner-event>]`), the trace echoes the routed inner event
  into two slots — top-level `:event` (`:rf.machine/transition`,
  `:rf.machine/event-received`) and `[:input :event]`
  (`:rf.machine/guard-evaluated`, `:rf.machine/action-ran`). Before this
  fix, `re-frame.classification/project-machine-tags` projected only the
  `:data` snapshot slots, so a `:sensitive`-classified payload carried
  THROUGH the machine shipped RAW in those echo slots (the generic
  event projector keys off the INNER event-id, which is typically
  unregistered).

  Two SEPARATE classification channels, kept disjoint by rooting:

    - the machine SPEC's `:sensitive` (`{:data …}`-rooted) classifies the
      DURABLE snapshot `:data` — validated `:data`-rooted at `reg-machine`,
      lowered per actor to the frame elision registry (`:source :machine`).
    - the `reg-machine` OPTS metadata `:sensitive` (event-vector-rooted,
      e.g. `[[1 :password]]`) is the machine's `:event` REGISTRATION
      classification — the TRANSIENT event classification this fix projects
      onto the echoed `:event` / `[:input :event]` slots.

  An event-rooted path (integer index) never matches a `:data`-prefixed
  snapshot key and vice versa, so both path sets ride the same union and
  each bites only the surface it is rooted at.

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`, `cljs-test$` ns-regexp) AND the JVM `clojure -M:test`
  runner both run it.

  ## Posture split (rf2-d2841)

  The DETERMINISTIC PROJECTOR TEETH are pure functions over hand-built trace
  shapes and run under `scripts/test-core-prod-gate.sh` unchanged, as does the
  live round-trip's EGRESS-ONLY claim — the machine action reads the RAW
  password locally, untouched by projection.

  The LIVE TRACE assertions are dev-only: the stream they police does not
  exist under `-Dre-frame.debug=false`. They are kept verbatim inside a
  `(when interop/debug-enabled? …)` arm marked `rf2-d2841`, and the two
  assertions that look like opposites go in TOGETHER. `(not (some #(leaks?
  pw-sentinel %) @seen))` over an empty `@seen` passes because nothing was
  emitted — a redaction suite must never report green on that basis — and its
  precision partner `(some #(leaks? email %) @seen)` is what proves the stream
  is live and the redaction path-precise rather than whole-event. Separated,
  the pair loses exactly the property that makes it meaningful."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as classification]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Boot the optional machines artefact so `rf/reg-machine`
            ;; resolves through the spec-005 implementation (the late-bind
            ;; hooks install on load — see machine_handler_meta_test.clj).
            [re-frame.machines]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; The reset fixture auto-registers + scope-pins a default frame (the ambient
;; scope a bare `dispatch-sync` cascades into), so the live round-trip below
;; runs with a frame context.
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A UNIQUE sentinel that must never appear in any projected trace echo slot.
(def ^:private pw-sentinel "rf2-ghgbqi-PW-4f3a91")
(def ^:private token-sentinel "rf2-ghgbqi-JWT-8c17be")
(def ^:private email "user@example.test")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

;; The machine's OPTS metadata `:sensitive` — the routed-event (transient)
;; classification. `[1 :password]` is rooted at the SECOND element of the
;; routed inner event vector `[:auth/login {…}]` (index 1 = the arg-map),
;; exactly as the generic event projector roots a dispatched-event payload.
;; `[:data :token]` is a durable snapshot path — inert against an event vector.
(def ^:private mid :rf.ghgbqi/auth)

(defn- register-machine-event-classification! []
  (registrar/register! :event mid
                       {:sensitive [[1 :password] [:data :token]]}))

(defn- inner-event [] [:auth/login {:email email :password pw-sentinel}])

(defn- project [ev] (:tags (classification/project-trace-event ev)))

;; =====================================================================
;; A. Deterministic projector teeth — hand-built machine trace shapes
;;    (mirrors machine_spawn_trace_egress_test.clj's methodology).
;; =====================================================================

(deftest transition-echoed-event-slots-redacted
  (testing "rf2-ghgbqi: a :rf.machine/transition echoing a :sensitive routed
            event redacts the password in BOTH echo slots (top-level :event and
            [:input :event]) while the durable :data (token) still redacts and
            the non-secret :email survives"
    (register-machine-event-classification!)
    (let [raw (inner-event)
          ev  {:operation :rf.machine/transition
               :tags {:actor-id mid
                      :frame    :rf/default
                      :event    raw
                      :input    {:data {:token token-sentinel} :event raw}
                      :before   {:state :idle :data {:token token-sentinel}}
                      :after    {:state :done :data {:token token-sentinel}}}}
          t   (project ev)]
      ;; --- the fix: routed-event echo slots redact the password ---
      (is (= privacy/redacted-sentinel (get-in t [:event 1 :password]))
          "top-level :event password redacted by the machine's event classification")
      (is (= privacy/redacted-sentinel (get-in t [:input :event 1 :password]))
          "[:input :event] password redacted too")
      (is (not (leaks? pw-sentinel t))
          "the password sentinel appears NOWHERE in the projected transition trace")
      ;; --- precision: the non-secret field survives ---
      (is (= email (get-in t [:event 1 :email]))
          "non-secret :email is NOT redacted (path-precise, not whole-event)")
      ;; --- durable :data still redacts (unchanged pre-existing behaviour) ---
      (is (not (leaks? token-sentinel t))
          "durable :data token still redacted in :before / :after / [:input :data]")
      (is (= privacy/redacted-sentinel (get-in t [:before :data :token]))
          ":before snapshot data token redacted")
      ;; --- non-destructive: the RAW routed event the machine control flow
      ;;     read locally is untouched (projection is trace-EGRESS only) ---
      (is (= pw-sentinel (get-in raw [1 :password]))
          "projection did not mutate the raw routed event — handlers see the raw value"))))

(deftest event-received-echoed-event-redacted
  (testing "rf2-ghgbqi: :rf.machine/event-received carries the routed event under
            top-level :event; the password redacts"
    (register-machine-event-classification!)
    (let [ev {:operation :rf.machine/event-received
              :tags {:machine-id mid
                     :frame      :rf/default
                     :event      (inner-event)}}
          t  (project ev)]
      (is (= privacy/redacted-sentinel (get-in t [:event 1 :password])))
      (is (= email (get-in t [:event 1 :email])))
      (is (not (leaks? pw-sentinel t))))))

(deftest guard-and-action-input-event-redacted
  (testing "rf2-ghgbqi: :rf.machine/guard-evaluated and :rf.machine/action-ran
            carry the routed event under [:input :event]; the password redacts"
    (register-machine-event-classification!)
    (doseq [op [:rf.machine/guard-evaluated :rf.machine/action-ran]]
      (let [ev {:operation op
                :tags {:actor-id mid
                       :frame    :rf/default
                       :input    {:data {:token token-sentinel} :event (inner-event)}}}
            t  (project ev)]
        (is (= privacy/redacted-sentinel (get-in t [:input :event 1 :password]))
            (str op " [:input :event] password redacted"))
        (is (not (leaks? pw-sentinel t)) (str op " leaks no password"))
        (is (not (leaks? token-sentinel t)) (str op " leaks no [:input :data] token"))))))

(deftest disjoint-roots-and-noop-precision
  (testing "rf2-ghgbqi precision: the two path roots are disjoint, and an
            unclassified machine leaves the echoed event untouched"
    ;; (1) A machine that declares ONLY a durable :data path does NOT touch
    ;;     the echoed event vector (a :data-prefixed key never indexes a vector).
    (registrar/register! :event mid {:sensitive [[:data :token]]})
    (let [t (project {:operation :rf.machine/transition
                      :tags {:actor-id mid :frame :rf/default
                             :event    (inner-event)
                             :before   {:state :idle :data {:token token-sentinel}}}})]
      (is (leaks? pw-sentinel (:event t))
          "a :data-only machine does not redact the event (event path undeclared — fail-open)")
      (is (not (leaks? token-sentinel t))
          "…but the durable :data token still redacts"))
    ;; (2) A machine that declares ONLY an event path does NOT touch the
    ;;     durable snapshot (an integer index never matches a snapshot key).
    (registrar/register! :event mid {:sensitive [[1 :password]]})
    (let [t (project {:operation :rf.machine/transition
                      :tags {:actor-id mid :frame :rf/default
                             :event    (inner-event)
                             :before   {:state :idle :data {:token token-sentinel}}}})]
      (is (= privacy/redacted-sentinel (get-in t [:event 1 :password]))
          "the event-path machine redacts the echoed event password")
      (is (leaks? token-sentinel (:before t))
          "…and leaves the durable snapshot untouched (no cross-root bleed)"))
    ;; (3) An UNCLASSIFIED machine (no :event registration) is a clean no-op.
    (registrar/clear-all!)
    (let [raw (inner-event)
          t   (project {:operation :rf.machine/transition
                        :tags {:actor-id :rf.ghgbqi/unclassified
                               :frame    :rf/default
                               :event    raw}})]
      (is (= raw (:event t))
          "an unclassified machine's echoed event rides through untouched"))))

;; =====================================================================
;; B. Real round-trip — reg-machine + dispatch. Proves the machine
;;    ACTION receives the RAW routed payload while the emitted trace
;;    redacts every echo slot at egress.
;; =====================================================================

(deftest routed-sensitive-event-redacts-in-live-machine-trace
  (testing "rf2-ghgbqi acceptance: routing a :sensitive event THROUGH a live
            machine — the action reads the RAW password, but every emitted
            machine trace echo slot ships it redacted"
    (let [captured (atom ::none)
          seen     (atom [])]
      ;; OPTS (middle slot) :sensitive = the routed-event classification.
      (rf/reg-machine mid
        {:sensitive [[1 :password]]}
        {:initial :idle
         :actions {:capture!
                   ;; Spec 005 §Actions — ctx is {:data :event :state :meta};
                   ;; :event is the routed inner event [:auth/login {…}].
                   (fn [{:keys [event]}]
                     (reset! captured (get-in event [1 :password]))
                     nil)}
         :states  {:idle {:on {:auth/login {:target :done :action :capture!}}}
                   :done {}}})
      (rf/dispatch-sync [mid [:rf.machine/start]])
      (rf/register-listener! :trace ::ghgbqi (fn [ev] (swap! seen conj ev)))
      (rf/dispatch-sync [mid [:auth/login {:email email :password pw-sentinel}]])

      ;; 1. The machine ACTION received the RAW password (control flow is
      ;;    untouched by egress projection).
      (is (= pw-sentinel @captured)
          "the machine action read the RAW routed password locally")

      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      ;; Points 2, 3 and 4 are ONE claim about a live channel and travel
      ;; together: the transition fired, the secret is absent from it, and the
      ;; non-secret survives. Split apart, point 3 passes over an empty
      ;; `@seen` for the wrong reason.
      (when interop/debug-enabled?
        ;; 2. A machine transition trace actually fired (teeth — the echo
        ;;    surface exists to protect).
        (let [ops (into #{} (map :operation) @seen)]
          (is (contains? ops :rf.machine/transition)
              "a :rf.machine/transition trace was emitted for the routed event"))

        ;; 3. EVERY emitted trace ships the password redacted (the transition
        ;;    echo slots, the action-ran [:input :event], AND the outer
        ;;    :rf.event/dispatched vector — all keyed off the same
        ;;    registration classification).
        (is (not (some #(leaks? pw-sentinel %) @seen))
            "no emitted trace event leaks the routed password sentinel")

        ;; 4. Precision — the non-secret :email is still observable somewhere in
        ;;    the trace stream (redaction is path-precise, not whole-event).
        (is (some #(leaks? email %) @seen)
            "the non-secret :email survives in the trace stream"))

      (rf/unregister-listener! :trace ::ghgbqi))))
