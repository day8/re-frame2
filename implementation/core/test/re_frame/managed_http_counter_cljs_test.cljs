(ns re-frame.managed-http-counter-cljs-test
  "The managed-HTTP counter example's public cancel wiring (Spec 014 example E).
  The +1 request carries `:request-id :http-counter/+1` and Cancel is
  `[:rf.http/managed-abort :http-counter/+1]`: two halves in two handlers that
  drift apart silently, since an abort naming the wrong id resolves nothing.
  The `:cancelled` reply arm matters more than a no-op would: a status the
  handler's `cond` does not enumerate falls through to the initiation arm and
  re-issues the request."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.reply :as rf.reply]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [managed-http-counter.core]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

;; The two managed-HTTP effects are swapped for capturing stubs via
;; `:fx-overrides`, so a test reads back what the handler asked for: no network,
;; no registry, no timing.
(def ^:private captured
  "Vector of `[fx-id effect-value]`, newest last, cleared per frame."
  (atom []))

(rf/reg-fx :test.http-counter/capture-managed
  (fn [_frame-ctx args-map]
    (swap! captured conj [:rf.http/managed args-map])
    nil))

(rf/reg-fx :test.http-counter/capture-abort
  (fn [_frame-ctx request-id]
    (swap! captured conj [:rf.http/managed-abort request-id])
    nil))

(defn- capture-frame!
  "A fresh anon frame whose managed-HTTP effects are captured rather than
  executed, with the capture log cleared. Returns the frame."
  []
  (reset! captured [])
  (rf.frame/make-anon-frame-record!
    {:doc          "managed-http-counter public-cancel test frame"
     :fx-overrides {:rf.http/managed       :test.http-counter/capture-managed
                    :rf.http/managed-abort :test.http-counter/capture-abort}}))

(defn- effects-for
  "The effect values captured for `fx-id`, in order."
  [fx-id]
  (->> @captured (filter #(= fx-id (first %))) (mapv second)))

;; A deftest name may not start `+1-`: the reader reads it as a number.
(deftest plus-one-request-carries-the-public-cancel-handle
  (let [f (capture-frame!)]
    (rf/dispatch-sync [:http-counter/+1] {:frame f})
    (is (= [[:http-counter/+1 [:http-counter/+1]]]
           (mapv (juxt :request-id :reply-to) (effects-for :rf.http/managed)))
        "one request, carrying the id Cancel aborts by, replying to the handler that issued it")
    (is (= :loading (:http-counter/status (rf/app-db-value f)))
        "the UI parks in :loading while that request is live")))

(deftest cancel-aborts-the-same-id-the-plus-one-request-carries
  (let [f (capture-frame!)]
    (rf/dispatch-sync [:http-counter/cancel] {:frame f})
    (is (= [:http-counter/+1] (effects-for :rf.http/managed-abort))
        "exactly one abort, naming the +1 request's id")))

(deftest cancelled-reply-settles-idle-records-the-abort-and-never-re-issues
  (let [cancelled-reply {:status                 :cancelled
                         :cancelled?             true
                         :rf.reply/cancel-reason :user
                         :error                  {:kind       :rf.http/aborted
                                                  :request-id :http-counter/+1
                                                  :reason     :user}}
        f               (capture-frame!)]
    (is (rf.reply/valid-reply? cancelled-reply)
        "the fixture is a reply the framework can actually deliver, so it cannot drift from the real envelope")
    (rf/dispatch-sync [:http-counter/+1 cancelled-reply] {:frame f})
    (is (= [:idle (:error cancelled-reply)]
           [(:http-counter/status (rf/app-db-value f)) (:http-counter/error (rf/app-db-value f))])
        "the UI returns to idle and records the classified abort")
    (is (empty? (effects-for :rf.http/managed))
        "and no new request: an unenumerated status would re-issue the one just cancelled")))
