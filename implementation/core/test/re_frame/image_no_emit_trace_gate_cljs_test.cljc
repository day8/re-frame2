(ns re-frame.image-no-emit-trace-gate-cljs-test
  "The enqueue-time `:rf.trace/no-emit?` gate (Spec 009 §Trace-emission
  opt-out) resolves the handler's meta through the target frame's image
  generation. It runs outside the `call-with-frame-resolution` binding around
  `process-event!`, so a bare registrar lookup would miss an image-inline
  handler and emit `:rf.event/dispatched` into the stream it opted out of.

  The trace assertions are dev-only: under `-Dre-frame.debug=false` nothing is
  emitted, and the negative would pass over an empty stream."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core           :as rf]
            [re-frame.image          :as rf.image]
            [re-frame.interop        :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support   :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter        rf.substrate.plain-atom/adapter
                                            :ambient-frame  nil}))

(defn- dispatched-trace?
  "Dispatch `event` to `fid`; true when an `:rf.event/dispatched` trace names it."
  [fid event]
  (let [recorded (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! recorded conj ev)))
    (try
      (rf/dispatch-sync event {:frame fid})
      (boolean (some #(and (= :rf.event/dispatched (:operation %))
                           (= event (get-in % [:tags :rf.event/v])))
                     @recorded))
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(deftest image-inline-no-emit-flag-suppresses-the-enqueue-trace
  ;; both handlers live only in the frame's image, never in the registrar
  (rf/make-frame
    {:id     :img/main
     :images [(rf.image/image
                {:id :img/no-emit
                 :registrations
                 {:reg-event [[:bookkeeping/internal {:rf.trace/no-emit? true}
                               (fn [{:keys [db]} _] {:db (assoc db :bookkeeping/ran? true)})]
                              [:normal/event
                               (fn [{:keys [db]} _] {:db (assoc db :normal/ran? true)})]]}})]}
    [])
  (let [flagged (dispatched-trace? :img/main [:bookkeeping/internal])
        normal  (dispatched-trace? :img/main [:normal/event])]
    (is (= {:bookkeeping/ran? true :normal/ran? true}
           (select-keys (rf/app-db-value :img/main) [:bookkeeping/ran? :normal/ran?]))
        "both image-inline handlers resolved and ran")
    (when rf.interop/debug-enabled?
      (is (= [false true] [flagged normal])
          "the flag, not the image, suppresses the trace"))))
