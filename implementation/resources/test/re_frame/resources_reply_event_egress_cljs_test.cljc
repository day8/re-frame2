(ns re-frame.resources-reply-event-egress-cljs-test
  "A family continuation reply rides its `:reply-to` target event as that
  event's last argument, so every copy of the reply event's run echoes the
  owner's data: the live `:rf.event/v`, the bare `:event` error slot, the
  always-on `:rf.observe/error` record and the epoch record's
  `:trigger-event`. The owner's declaration covers each copy exactly as it
  covers the reply's FX carrier, which is the control, while the app's own
  handler receives the real value — for read and mutation replies, path
  declarations and coarse claims alike. The target events declare nothing, so
  every redaction seen is the owner's. An execute payload's `:reply-to` address
  to a spawned actor takes the actor's machine type classification in the
  event's frame.
  Spec 015 §Subsystem projection-relative classification."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.classification :as rf.classification]
   [re-frame.core :as rf]
   [re-frame.epoch :as rf.epoch]
   [re-frame.fx :as rf.fx]
   [re-frame.machines]
   [re-frame.observability :as rf.observability]
   [re-frame.privacy :as rf.privacy]
   ;; load-bearing side-effecting requires: register the :rf.resource/* /
   ;; :rf.mutation/* events this suite dispatches.
   [re-frame.resources]
   [re-frame.resources.test-support]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.http.managed]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

;; A unique sentinel, so a leak anywhere in a copy is unambiguous.
(def ^:private PW "REPLY-EVENT-SENTINEL-k3v81q")

(def ^:private frame-id ::app)

(def ^:private sunk (atom []))
(def ^:private managed-args (atom nil))
(def ^:private replied (atom nil))

(defn- request [_params _ctx] {:request {:method :post :url "/x"}})

(defn- init! []
  (reset! sunk [])
  (reset! managed-args nil)
  (reset! replied nil)
  (rf.observability/clear-observability-sinks!)
  (rf/register-observability-sink! ::errors (fn [record] (swap! sunk conj record)))
  (rf/make-frame {:id frame-id :observability {:errors [{:sink ::errors}]}})
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (reset! managed-args args) nil))
  (rf.fx/reg-fx :rf.resource/schedule-timers (fn [_ _] nil))
  (rf/reg-mutation :m/path {:params-schema [:map [:slug :string]] :sensitive [[:token]]} request)
  (rf/reg-mutation :m/coarse {:params-schema [:map [:slug :string]] :sensitive? true} request)
  (rf/reg-resource :r/path {:scope :rf.scope/global :params-schema [:map [:slug :string]]
                            :sensitive [[:data :token]]}
                   request)
  (rf/reg-resource :r/coarse {:scope :rf.scope/global :params-schema [:map [:slug :string]]
                              :sensitive? true}
                   request)
  (rf/reg-event :app/replied (fn [_ [_ reply]] (reset! replied reply) {}))
  (rf/reg-event :app/replied-and-failed (fn [_ _] (throw (ex-info "reply target failed" {})))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private owners
  "Each owner, its kind, and how its declaration projects the `{:token PW}`
  value: a path declaration substitutes the sentinel at the token, a coarse
  claim tokenizes the whole value."
  [[:mutation :m/path   {:token rf.privacy/redacted-sentinel}]
   [:mutation :m/coarse ::token]
   [:resource :r/path   {:token rf.privacy/redacted-sentinel}]
   [:resource :r/coarse ::token]])

(defn- projected-as? [expected v]
  (if (= ::token expected)
    (and (map? v) (contains? v :rf/redacted))
    (= expected v)))

(defn- leaks? [x] (str/includes? (pr-str x) PW))

(defn- settle!
  "Start `owner`'s work with a `:reply-to` of `[target]` and settle it with
  `{:token PW}`. Returns the trace rows of the settle drain."
  [kind owner target]
  (rf/dispatch-sync (case kind
                      :mutation [:rf.mutation/execute {:mutation owner :params {:slug "w"}
                                                       :instance owner :reply-to [target]}]
                      :resource [:rf.resource/ensure {:resource owner :params {:slug "w"}
                                                      :owner [:app :reader 1] :reply-to [target]}])
                    {:frame frame-id})
  (reset! sunk [])
  (let [rows (atom [])]
    (rf/register-listener! :trace ::rows (fn [ev] (swap! rows conj ev)))
    (try
      (rf/dispatch-sync (conj (:on-success @managed-args) {:status :ok :value {:token PW}})
                        {:frame frame-id})
      (finally (rf/unregister-listener! :trace ::rows)))
    @rows))

(defn- carrier-reply
  "The family reply inside the rows' FX carrier slots, after the off-box
  projection the epoch tool-pair applies to them: the control copy."
  [rows]
  (let [found (atom nil)
        walk  (fn walk [v]
                (cond
                  (map? v)  (if (#{:resource :mutation} (:rf.reply/work-kind v))
                              (reset! found v)
                              (run! walk (vals v)))
                  (coll? v) (run! walk v)))]
    (doseq [ev   rows
            :let [tags (rf.resources.trace-egress/project-fx-args-egress (:tags ev) frame-id)]
            slot [:rf.fx/args :rf.event/fx]
            :when (contains? tags slot)]
      (walk (get tags slot)))
    @found))

(defn- reply-event-copies
  "The `:rf.event/v` of every row of the `target` event's own run."
  [rows target]
  (->> rows
       (keep #(get-in % [:tags :rf.event/v]))
       (filter #(and (vector? %) (= target (first %))))))

;; ===========================================================================
;; every copy of the reply event's run takes the owner's declaration
;; ===========================================================================

(deftest a-reply-events-own-run-copies-take-the-owners-declaration
  (doseq [[kind owner expected] owners]
    (testing (str owner)
      (let [rows    (settle! kind owner :app/replied)
            control (carrier-reply rows)
            copies  (reply-event-copies rows :app/replied)]
        (is (projected-as? expected (:value control))
            "control: the reply's FX carrier copy is projected by the owner")
        (is (= {:token PW} (:value @replied))
            "the app's own :reply-to handler receives the real value")
        (is (= 3 (count copies)) "dispatched, run-start and run-end echo the reply event")
        (doseq [v copies]
          (is (= (:value control) (get-in v [1 :value]))
              "the live :rf.event/v copy agrees with the carrier copy"))
        (is (not-any? leaks? copies) "no live :rf.event/v copy carries the raw value")
        (let [trigger (:trigger-event (rf/project-egress (last (rf.epoch/epoch-history frame-id))
                                                         {:rf.egress/include-event-args? true}))]
          (is (= :app/replied (first trigger)) "the latest epoch is the reply event's")
          (is (= (:value control) (get-in trigger [1 :value]))
              "the epoch :trigger-event, event args opted in, agrees with the carrier copy"))))))

(deftest a-failing-reply-events-error-copies-take-the-owners-declaration
  (doseq [[kind owner expected] owners]
    (testing (str owner)
      (let [rows    (settle! kind owner :app/replied-and-failed)
            control (carrier-reply rows)
            errored (fn [records]
                      (some #(when (= :rf.error/handler-exception (:rf.error/id %)) (:event %))
                            records))
            trace   (some #(when (= :rf.error/handler-exception (:operation %)) (:tags %)) rows)
            record  (errored @sunk)]
        (is (projected-as? expected (:value control))
            "control: the reply's FX carrier copy is projected by the owner")
        (is (= :app/replied-and-failed (first record))
            "the always-on :rf.observe/error record names the reply event")
        (is (= (:value control) (get-in record [1 :value]))
            "the always-on record's :event, at the default sink profile, agrees with the carrier copy")
        (is (= (:value control) (get-in trace [:event 1 :value]))
            "the live error trace's bare :event agrees with the carrier copy")
        (is (not (leaks? record)) "the always-on record carries no raw value")))))

;; ===========================================================================
;; an execute payload's :reply-to address to a spawned actor
;; ===========================================================================

(deftest an-execute-reply-to-address-to-a-spawned-actor-takes-its-type
  (rf/reg-machine :reply-event/child {:sensitive [[1 :password]]}
    {:initial :idle
     :states  {:idle {:on {:login {:target :done}}} :done {}}})
  (rf/reg-machine :reply-event/parent
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id     :reply-event/child
                                 :fixed-actor-id :reply-event/kid}}}})
  (rf/dispatch-sync [:reply-event/parent [:start]] {:frame frame-id})
  (let [execute  (fn [actor] [:rf.mutation/execute
                              {:mutation :m/path :params {:slug "w"}
                               :reply-to [actor [:login {:user "u" :password PW}]]}])
        reply-to #(get-in % [1 :reply-to 1])]
    (is (= [:login {:user "u" :password rf.privacy/redacted-sentinel}]
           (reply-to (rf.classification/redact-event-by-registration
                       (execute :reply-event/child) frame-id)))
        "control: an address to the type's singleton redacts by its registration")
    (is (= [:login {:user "u" :password rf.privacy/redacted-sentinel}]
           (reply-to (rf.classification/redact-event-by-registration
                       (execute :reply-event/kid) frame-id)))
        "an address to the spawned instance takes the type's classification in the frame")))
