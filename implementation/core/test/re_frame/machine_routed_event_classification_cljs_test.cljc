(ns re-frame.machine-routed-event-classification-cljs-test
  "The machine trace projector redacts the ROUTED inner event a machine echoes
  into its trace slots — top-level `:event` (`:rf.machine/transition`,
  `:rf.machine/event-received`) and `[:input :event]`
  (`:rf.machine/guard-evaluated`, `:rf.machine/action-ran`) — as well as the
  durable `:data` snapshot (Spec 005 §Trace events). The generic event projector
  keys off the inner event-id, which is typically unregistered.

  Two classification channels share one path union and stay disjoint by root:
  the machine spec's `:data`-rooted paths classify the snapshot, and the
  `reg-machine` opts `:sensitive` (event-vector-rooted, e.g. `[[1 :password]]`)
  classifies the echoed event. An integer index never matches a `:data` key.

  The projector tests are pure and run in every posture. The live test's trace
  assertions are dev-only; its no-leak negative travels with the `:email`
  positive, which proves the stream is live and the redaction path-precise."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loaded so `rf/reg-machine` resolves through the machines artefact.
            [re-frame.machines]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Unique sentinels that must never appear in a projected trace echo slot.
(def ^:private pw-sentinel "rf2-ghgbqi-PW-4f3a91")
(def ^:private token-sentinel "rf2-ghgbqi-JWT-8c17be")
(def ^:private email "user@example.test")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(def ^:private mid :rf.ghgbqi/auth)

;; `[1 :password]` is rooted at the routed inner event's arg-map;
;; `[:data :token]` is a snapshot path, inert against an event vector.
(defn- register-machine-event-classification! []
  (rf.registrar/register! :event mid
                       {:sensitive [[1 :password] [:data :token]]}))

(defn- inner-event [] [:auth/login {:email email :password pw-sentinel}])

(def ^:private redacted-inner-event
  [:auth/login {:email email :password rf.privacy/redacted-sentinel}])

(defn- project [ev] (:tags (rf.classification/project-trace-event ev)))

(deftest transition-echoed-event-slots-redacted
  (register-machine-event-classification!)
  (let [raw (inner-event)
        t   (project {:operation :rf.machine/transition
                      :tags {:actor-id mid
                             :frame    :rf/default
                             :event    raw
                             :input    {:data {:token token-sentinel} :event raw}
                             :before   {:state :idle :data {:token token-sentinel}}
                             :after    {:state :done :data {:token token-sentinel}}}})]
    (is (= redacted-inner-event (:event t) (get-in t [:input :event]))
        "both echo slots redact the password and keep the non-secret :email")
    (is (= rf.privacy/redacted-sentinel (get-in t [:before :data :token])))
    (is (not (leaks? token-sentinel t))
        "the durable token redacts in :before, :after and [:input :data]")))

(deftest event-received-echoed-event-redacted
  ;; `:rf.machine/event-received` names its machine under `:machine-id`, not `:actor-id`.
  (register-machine-event-classification!)
  (is (= redacted-inner-event
         (:event (project {:operation :rf.machine/event-received
                           :tags {:machine-id mid
                                  :frame      :rf/default
                                  :event      (inner-event)}})))))

(deftest guard-and-action-input-event-redacted
  (register-machine-event-classification!)
  (doseq [op [:rf.machine/guard-evaluated :rf.machine/action-ran]]
    (let [t (project {:operation op
                      :tags {:actor-id mid
                             :frame    :rf/default
                             :input    {:data {:token token-sentinel} :event (inner-event)}}})]
      (is (= redacted-inner-event (get-in t [:input :event])) (str op))
      (is (not (leaks? token-sentinel t)) (str op " leaks no [:input :data] token")))))

(deftest disjoint-roots-and-noop-precision
  (testing "precision: the two path roots are disjoint, and an
            unclassified machine leaves the echoed event untouched"
    ;; (1) A machine that declares ONLY a durable :data path does NOT touch
    ;;     the echoed event vector (a :data-prefixed key never indexes a vector).
    (rf.registrar/register! :event mid {:sensitive [[:data :token]]})
    (let [t (project {:operation :rf.machine/transition
                      :tags {:actor-id mid :frame :rf/default
                             :event    (inner-event)
                             :before   {:state :idle :data {:token token-sentinel}}}})]
      (is (leaks? pw-sentinel (:event t))
          "a :data-only machine does not redact the event (event path undeclared — fail-open)")
      (is (not (leaks? token-sentinel t))
          "…but the durable :data token redacts"))
    ;; (2) A machine that declares ONLY an event path does NOT touch the
    ;;     durable snapshot (an integer index never matches a snapshot key).
    (rf.registrar/register! :event mid {:sensitive [[1 :password]]})
    (let [t (project {:operation :rf.machine/transition
                      :tags {:actor-id mid :frame :rf/default
                             :event    (inner-event)
                             :before   {:state :idle :data {:token token-sentinel}}}})]
      (is (= rf.privacy/redacted-sentinel (get-in t [:event 1 :password]))
          "the event-path machine redacts the echoed event password")
      (is (leaks? token-sentinel (:before t))
          "…and leaves the durable snapshot untouched (no cross-root bleed)"))
    ;; (3) An UNCLASSIFIED machine (no :event registration) is a clean no-op.
    (rf.registrar/clear-all!)
    (let [raw (inner-event)
          t   (project {:operation :rf.machine/transition
                        :tags {:actor-id :rf.ghgbqi/unclassified
                               :frame    :rf/default
                               :event    raw}})]
      (is (= raw (:event t))
          "an unclassified machine's echoed event rides through untouched"))))

(deftest routed-sensitive-event-redacts-in-live-machine-trace
  (testing "routing a :sensitive event through a live machine: the action reads
            the raw password, while every emitted trace ships it redacted"
    (let [captured (atom ::none)
          seen     (atom [])]
      (rf/reg-machine mid
        {:sensitive [[1 :password]]}
        {:initial :idle
         :actions {:capture!
                   ;; `:event` is the routed inner event `[:auth/login {…}]`.
                   (fn [{:keys [event]}]
                     (reset! captured (get-in event [1 :password]))
                     nil)}
         :states  {:idle {:on {:auth/login {:target :done :action :capture!}}}
                   :done {}}})
      (rf/dispatch-sync [mid [:rf.machine/start]])
      (rf/register-listener! :trace ::ghgbqi (fn [ev] (swap! seen conj ev)))
      (rf/dispatch-sync [mid [:auth/login {:email email :password pw-sentinel}]])
      (is (= pw-sentinel @captured)
          "the machine action read the raw routed password")
      ;; One claim about a live channel: split apart, the no-leak negative
      ;; passes over an empty `@seen` for the wrong reason.
      (when rf.interop/debug-enabled?
        (is (contains? (into #{} (map :operation) @seen) :rf.machine/transition)
            "a :rf.machine/transition trace was emitted for the routed event")
        (is (not (some #(leaks? pw-sentinel %) @seen))
            "no emitted trace event leaks the routed password sentinel")
        (is (some #(leaks? email %) @seen)
            "the non-secret :email survives in the trace stream"))
      (rf/unregister-listener! :trace ::ghgbqi))))
