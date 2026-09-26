(ns re-frame.http-managed-carriers-refusal-test
  "A malformed `:carriers` block on the `:rf.http/managed` registration is
  loud without taking the runtime down (Spec 009 `:rf.error/bad-classification`,
  Spec 014 §HTTP carriers).

  The block is validated where it is read, so the next managed request is
  refused: `managed-handler` raises `:rf.error/bad-classification`, which the
  fx walk reports as that effect's `:rf.error/fx-handler-exception`. The core
  trace projector reads the same block through the
  `:http/project-managed-fx-args` hook inside trace emission; that hook never
  throws, so the error trace is delivered, the event's sibling effects still
  run, the drain does not throw to the host, and the next queued event is
  processed.

  Deterministic: `rf.interop/next-tick` is replaced by a recorder and the
  recorded drain callbacks are run by hand."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private fid :carriers-refusal/f)

(defn- thrown-id
  "Run `thunk`; return the `:rf.error/id` of what it throws, or `:no-throw`."
  [thunk]
  (try (thunk) :no-throw
       (catch Throwable t (or (:rf.error/id (ex-data t)) (class t)))))

(defn- run-ticks!
  "Run every recorded `next-tick` callback, including any a callback records,
  until none remain."
  [ticks]
  (loop []
    (when-let [f (first @ticks)]
      (swap! ticks subvec 1)
      (f)
      (recur))))

(defn- register-app!
  "`:carriers-refusal/fetch` issues one managed request, then queues
  `:carriers-refusal/sibling` from the same effect vector."
  [log]
  (rf/reg-event :carriers-refusal/fetch
    (fn [_ _]
      (swap! log conj :fetch)
      {:fx [[:rf.http/managed {:request  {:method  :get
                                          :url     "http://127.0.0.1:9/x"
                                          :headers {"X-Team-Key" "s3cret-team-key"}}
                               :reply-to [:carriers-refusal/reply]}]
            [:dispatch [:carriers-refusal/sibling]]]}))
  (rf/reg-event :carriers-refusal/sibling (fn [_ _] (swap! log conj :sibling) {}))
  (rf/reg-event :carriers-refusal/next    (fn [_ _] (swap! log conj :next) {}))
  (rf/reg-event :carriers-refusal/reply   (fn [_ _] (swap! log conj :reply) {})))

(deftest a-malformed-carriers-block-refuses-the-request-and-the-router-keeps-draining
  (let [log      (atom [])
        ticks    (atom [])
        captured (atom [])]
    ;; A keyword where a header-name string belongs.
    (rf/reg-fx :rf.http/managed
      {:carriers {:headers [:X-Team-Key]}}
      rf.http.managed/managed-handler)
    (register-app! log)
    (rf/make-frame {:id fid})
    (rf.trace.tooling/register-listener! ::capture #(swap! captured conj %))
    (with-redefs [rf.interop/next-tick (fn [f] (swap! ticks conj f) nil)]
      (rf/dispatch [:carriers-refusal/fetch] {:frame fid})
      (rf/dispatch [:carriers-refusal/next] {:frame fid})
      (is (= 1 (count @ticks)) "the two dispatches armed one drain")
      (let [drain (first @ticks)]
        (swap! ticks subvec 1)
        (is (= :no-throw (thrown-id drain))
            "the refusal does not escape trace emission or the drain"))
      (run-ticks! ticks))
    (is (= [:fetch :next :sibling] @log)
        "the router processed the queued event and the refused effect's sibling")
    (is (empty? (rf.http.managed/in-flight-snapshot))
        "the request was refused, never issued")
    (let [fx-errors (filterv #(and (= :rf.error/fx-handler-exception (:operation %))
                                   (= :rf.http/managed (get-in % [:tags :rf.fx/id])))
                             @captured)
          ev        (first fx-errors)]
      (is (= 1 (count fx-errors))
          "the refusal is reported once, as the managed effect's handler exception")
      (is (= :rf.error/bad-classification
             (:rf.error/id (ex-data (get-in ev [:tags :exception]))))
          "the reported exception is the named :rf.error/bad-classification")
      (is (= :rf/redacted (get-in ev [:tags :rf.fx/args :request]))
          "the trace projects the request fail-closed while its carriers are unreadable")
      (is (= [:carriers-refusal/reply] (get-in ev [:tags :rf.fx/args :reply-to]))
          "the reply address still projects normally"))
    (is (not (str/includes? (pr-str @captured) "s3cret-team-key"))
        "the header the malformed block named appears nowhere in the trace")
    (testing "a later dispatch-sync on the same frame is not poisoned"
      (is (= :no-throw (thrown-id #(rf/dispatch-sync [:carriers-refusal/next] {:frame fid}))))
      (is (= [:fetch :next :sibling :next] @log)))))
