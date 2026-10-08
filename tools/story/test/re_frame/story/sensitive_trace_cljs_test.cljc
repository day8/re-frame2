(ns re-frame.story.sensitive-trace-cljs-test
  "Spec 009 §Privacy: Story's trace listeners default-suppress
  `:sensitive? true` events under the egress profile resolved for each
  event's frame. Runs on the JVM and in the :node-test build."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story            :as rf.story]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.play       :as rf.story.play]
            [re-frame.story.recorder   :as rf.story.recorder]))

(defn reset-config! [f]
  (rf.story.config/reset-all!)
  (try
    (f)
    (finally
      (rf.story.config/reset-all!))))

(use-fixtures :each reset-config!)

(defn- sensitive-dispatch-event [frame-id event]
  {:op-type    :rf.event
   :operation  :rf.event/dispatched
   :id         1
   :time       1700000000000
   :sensitive? true
   :tags       {:rf.trace/dispatch-id 1
                :frame                frame-id
                :rf.trace/event-id    (first event)
                :rf.event/v           event}})

(defn- plain-dispatch-event [frame-id event]
  {:op-type   :rf.event
   :operation :rf.event/dispatched
   :id        2
   :time      1700000000010
   :tags      {:rf.trace/dispatch-id 2
               :frame                frame-id
               :rf.trace/event-id    (first event)
               :rf.event/v           event}})

(defn- handler-exception-event [frame-id sensitive?]
  (cond-> {:op-type   :error
           :operation :rf.error/handler-exception
           :id        4
           :time      1700000000030
           :tags      {:rf.trace/dispatch-id 4
                       :frame                frame-id
                       :event                [:auth/login]
                       :exception-message    "boom"}}
    sensitive? (assoc :sensitive? true)))

;; ---------------------------------------------------------------------------
;; Profile configuration
;; ---------------------------------------------------------------------------

(deftest configure!-wires-egress-profile
  (testing "rf.story/configure! routes :rf.story/egress-profile to the config atom"
    (rf.story/configure! {:rf.story/egress-profile :rf.egress/local-raw})
    (is (= :rf.egress/local-raw @rf.story.config/session-egress-profile)
        "opt-in flips the profile to the trusted-local boundary")
    (is (true? (rf.story.config/include-sensitive? nil)))
    (rf.story/configure! {:rf.story/egress-profile :rf.egress/local-redacted})
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "passing local-redacted narrows back")
    (is (false? (rf.story.config/include-sensitive? nil))))
  (testing "configure! without the key leaves the profile untouched"
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story/configure! {:rf.story/editor :cursor})
    (is (= :rf.egress/local-raw @rf.story.config/session-egress-profile)
        "the unrelated key didn't reset the profile")))

(deftest configure!-rejects-unknown-egress-profile
  (let [e (try
            (rf.story/configure! {:rf.story/egress-profile :rf.egress/not-a-profile})
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
    (is (= {:rf.error/id :rf.error/unknown-egress-profile :where 'rf.story/configure!}
           (select-keys (ex-data e) [:rf.error/id :where])))
    (is (re-find #"\[:rf.error/unknown-egress-profile\]"
                 #?(:clj (.getMessage ^Exception e) :cljs (ex-message e))))))

(deftest set-egress-profile!-fail-closed-defaults
  (testing "set-egress-profile! resets nil + non-member values to the fail-closed default"
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story.config/set-egress-profile! nil)
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "nil resets to the redacting default")
    (rf.story.config/set-egress-profile! :rf.egress/local-raw)
    (rf.story.config/set-egress-profile! :not-a-profile)
    (is (= :rf.egress/local-redacted @rf.story.config/session-egress-profile)
        "a non-member value coerces fail-closed")
    (is (false? (rf.story.config/include-sensitive? nil)))))

;; ---------------------------------------------------------------------------
;; Suppressed-events counter — the count behind the UI's REDACTED hint
;; ---------------------------------------------------------------------------

(deftest suppressed-counter-counts-per-variant-and-resets
  (rf.story.config/note-suppressed! :story.x/y)
  (rf.story.config/note-suppressed! :story.x/y)
  (rf.story.config/note-suppressed! :story.a/b)
  (rf.story.config/note-suppressed! nil)
  (rf.story.config/note-suppressed! nil)
  (is (= [2 1 0 2 2]
         [(rf.story.config/suppressed-count :story.x/y)
          (rf.story.config/suppressed-count :story.a/b)
          (rf.story.config/suppressed-count :story.never/seen)
          (rf.story.config/suppressed-count)
          (rf.story.config/suppressed-count :global)])
      "per-variant buckets; a nil variant counts under :global")
  (rf.story.config/reset-suppressed-count! :story.x/y)
  (is (= [0 1] [(rf.story.config/suppressed-count :story.x/y)
                (rf.story.config/suppressed-count :story.a/b)])
      "resetting one bucket leaves the others")
  (rf.story.config/reset-suppressed-count!)
  (is (= [0 0] [(rf.story.config/suppressed-count :story.a/b)
                (rf.story.config/suppressed-count :global)])))

;; ---------------------------------------------------------------------------
;; Play listener — the privacy gate runs before handler-exception capture
;; ---------------------------------------------------------------------------

(defn- pending-for [frame-id]
  (get @rf.story.play/pending-exceptions frame-id []))

(deftest play-listener-drops-sensitive-events-at-the-gate-before-exception-capture
  (let [frame-id :story.sensitive/v
        listen   (@#'rf.story.play/listener-for-frame frame-id)]
    (doseq [[label profile ev captured suppressed]
            [["by default a :sensitive? handler-exception is dropped before capture"
              nil (handler-exception-event frame-id true) 0 1]
             ["under :rf.egress/local-raw the gate is open: a sensitive handler-exception is captured"
              :rf.egress/local-raw (handler-exception-event frame-id true) 1 0]
             ["a non-sensitive handler-exception is captured under default settings"
              nil (handler-exception-event frame-id false) 1 0]]]
      (testing label
        (rf.story.config/reset-all!)
        (when profile (rf.story.config/set-egress-profile! profile))
        (swap! rf.story.play/pending-exceptions assoc frame-id [])
        (listen ev)
        (is (= [captured suppressed]
               [(count (pending-for frame-id))
                (rf.story.config/suppressed-count frame-id)])
            "[handler-exceptions captured, suppressed-events counter]")))))

(deftest play-listener-frame-scoped-reveal-rf2-6z4znr
  (testing "revealing frame A's gate does NOT open frame B's play listener"
    (rf.story.config/set-frame-egress-profile! :story.play/a :rf.egress/local-raw)
    (let [build @#'rf.story.play/listener-for-frame]
      (let [listen (build :story.play/a)]
        (swap! rf.story.play/pending-exceptions assoc :story.play/a [])
        (listen (handler-exception-event :story.play/a true))
        (is (= 1 (count (pending-for :story.play/a))) "A revealed → captured"))
      (let [listen (build :story.play/b)]
        (swap! rf.story.play/pending-exceptions assoc :story.play/b [])
        (listen (handler-exception-event :story.play/b true))
        (is (empty? (pending-for :story.play/b)) "B not revealed → dropped")))))

;; ---------------------------------------------------------------------------
;; Recorder listener — sensitive events record as the [:rf/redacted] placeholder
;; ---------------------------------------------------------------------------

(deftest recorder-listener-preserves-temporal-ordering-around-redacted
  (testing "by default the [:rf/redacted] placeholder keeps the row's position
            and drops its payload, and the suppressed-events counter bumps once"
    (rf.story.recorder/clear!)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener]
      (listen (plain-dispatch-event     :story.recorder/sens [:counter/inc]))
      (listen (sensitive-dispatch-event :story.recorder/sens
                                        [:auth/login {:password "x"}]))
      (listen (plain-dispatch-event     :story.recorder/sens [:counter/inc])))
    (is (= [[:counter/inc] [:rf/redacted] [:counter/inc]]
           (rf.story.recorder/recorded-events)))
    (is (= 1 (rf.story.config/suppressed-count :story.recorder/sens)))
    (rf.story.recorder/clear!)))

(deftest recorder-listener-frame-scoped-reveal-rf2-6z4znr
  (testing "revealing the RECORDED frame captures verbatim; revealing a sibling does NOT"
    (rf.story.config/set-frame-egress-profile! :story.recorder/sibling :rf.egress/local-raw)
    (rf.story.recorder/clear!)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener]
      (listen (sensitive-dispatch-event :story.recorder/sens [:auth/login {:password "x"}]))
      (is (= [[:rf/redacted]] (rf.story.recorder/recorded-events))
          "only a sibling was revealed — still redacted"))
    (rf.story.recorder/clear!)
    (rf.story.config/set-frame-egress-profile! :story.recorder/sens :rf.egress/local-raw)
    (rf.story.recorder/start-recording! :story.recorder/sens 0)
    (let [listen @#'rf.story.recorder/trace-listener]
      (listen (sensitive-dispatch-event :story.recorder/sens [:auth/login {:password "x"}]))
      (is (= [[:auth/login {:password "x"}]] (rf.story.recorder/recorded-events))
          "revealing the recording frame captures verbatim"))
    (rf.story.recorder/clear!)))

;; The listener must test `recordable-event?` on the ORIGINAL event before the
;; redact fork: the `[:rf/redacted]` placeholder itself always passes that filter.
(deftest recorder-listener-drops-sensitive-non-recordable-events-rf2-cmjly3
  (testing "a sensitive :rf.assert/* event is DROPPED — no [:rf/redacted] row,
            and no suppressed-count bump for a row that was never recorded"
    (let [frame-id :story.recorder/sens-drop]
      (rf.story.recorder/clear!)
      (rf.story.recorder/start-recording! frame-id 0)
      (@#'rf.story.recorder/trace-listener
       (sensitive-dispatch-event frame-id [:rf.assert/path-equals [:n] 1]))
      (is (= [] (rf.story.recorder/recorded-events)))
      (is (zero? (rf.story.config/suppressed-count frame-id)))
      (rf.story.recorder/clear!))))

;; ---------------------------------------------------------------------------
;; Retroactive scrub: narrowing reveal → redact fires the toggle-off callbacks
;; ---------------------------------------------------------------------------

(deftest toggle-off-callbacks-fire-only-on-reveal-to-redact-rf2-lqmje
  (testing "reveal → reveal and redact → redact are no-ops for the callbacks"
    (let [calls    (atom 0)
          token-id ::scrub-callback-no-transition]
      (rf.story.config/register-toggle-off-callback! token-id (fn [_frame-id] (swap! calls inc)))
      (try
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (is (= 0 @calls))
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
        (is (= 1 @calls))
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
        (is (= 1 @calls))
        (finally
          (rf.story.config/unregister-toggle-off-callback! token-id))))))

(deftest narrowing-profile-callback-failure-isolated-rf2-lqmje
  (testing "one buggy callback does not prevent others from running"
    (let [other-called? (atom false)
          token-bad     ::scrub-callback-bad
          token-good    ::scrub-callback-good]
      (rf.story.config/register-toggle-off-callback!
        token-bad (fn [_frame-id] (throw (ex-info "boom" {}))))
      (rf.story.config/register-toggle-off-callback!
        token-good (fn [_frame-id] (reset! other-called? true)))
      (try
        (rf.story.config/set-egress-profile! :rf.egress/local-raw)
        (rf.story.config/set-egress-profile! :rf.egress/local-redacted)
        (is (true? @other-called?))
        (finally
          (rf.story.config/unregister-toggle-off-callback! token-bad)
          (rf.story.config/unregister-toggle-off-callback! token-good))))))

;; ---------------------------------------------------------------------------
;; Per-frame visibility: revealing one frame never reveals another
;; ---------------------------------------------------------------------------

(deftest frame-egress-isolation-reveal-one-not-the-other
  (testing "revealing frame A to local-raw does NOT reveal frame B"
    (let [a :story.iso/a
          b :story.iso/b]
      (rf.story.config/set-frame-egress-profile! a :rf.egress/local-raw)
      (is (false? (rf.story.config/suppress-sensitive? (sensitive-dispatch-event a [:auth/login]) a)))
      (is (true?  (rf.story.config/suppress-sensitive? (sensitive-dispatch-event b [:auth/login]) b)))
      (testing "the single-arg arity resolves the frame from the event"
        (is (false? (rf.story.config/suppress-sensitive? (sensitive-dispatch-event a [:auth/login]))))
        (is (true?  (rf.story.config/suppress-sensitive? (sensitive-dispatch-event b [:auth/login]))))))))

(deftest frameless-event-fails-closed
  (testing "a sensitive event with no resolvable frame fails closed even while a sibling is raw"
    (rf.story.config/set-frame-egress-profile! :story.iso/a :rf.egress/local-raw)
    (is (false? (rf.story.config/include-sensitive? nil)))
    (is (true? (rf.story.config/suppress-sensitive? {:sensitive? true :tags {}})))))

(deftest narrowing-one-frame-scrubs-only-that-frame
  (testing "set-frame-egress-profile! reveal → redact fires callbacks with THAT frame-id only"
    (let [scrubbed (atom [])
          token    ::per-frame-scrub
          a        :story.iso/a
          b        :story.iso/b]
      (rf.story.config/register-toggle-off-callback! token (fn [frame-id] (swap! scrubbed conj frame-id)))
      (try
        (rf.story.config/set-frame-egress-profile! a :rf.egress/local-raw)
        (rf.story.config/set-frame-egress-profile! b :rf.egress/local-raw)
        (is (= [] @scrubbed) "revealing fires nothing")
        (rf.story.config/set-frame-egress-profile! a :rf.egress/local-redacted)
        (is (= [a] @scrubbed) "narrowing A scrubbed A only")
        (rf.story.config/set-frame-egress-profile! b :rf.egress/local-redacted)
        (is (= [a b] @scrubbed) "B stayed revealed, so narrowing it scrubs it")
        (finally
          (rf.story.config/unregister-toggle-off-callback! token))))))

(deftest per-frame-reveal-wins-over-redacting-session-pin
  (testing "an unoverridden frame inherits the session pin, and a per-frame
            reveal beats a redacting pin"
    (rf.story.config/set-session-egress-profile! :rf.egress/local-raw)
    (is (true? (rf.story.config/include-sensitive? :story.iso/inherits)) "no override → inherits the raw pin")
    (rf.story.config/set-session-egress-profile! rf.story.config/default-egress-profile)
    (rf.story.config/set-frame-egress-profile! :story.iso/raised :rf.egress/local-raw)
    (is (true?  (rf.story.config/include-sensitive? :story.iso/raised)) "explicit reveal wins over the redacting pin")
    (is (false? (rf.story.config/include-sensitive? :story.iso/other)) "an unoverridden frame stays at the redacting pin")))
