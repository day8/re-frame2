(ns re-frame.core-contract-guards-test
  "Guards on internal seams: the registrar's closed kind set, registration
  and replacement hook firing and isolation, `frame-disposed-for-drain?`'s
  record-state branches, and reg-sub's malformed-form rejection.

  The hook atoms are private with no public clear, so the fixture snapshots
  and restores them."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]))

(defn isolate-registrar-state [test-fn]
  (let [reg-hooks-before  @(deref #'rf.registrar/registration-hooks)
        repl-hooks-before @(deref #'rf.registrar/replacement-hooks)
        frames-before     @rf.frame/frames]
    (rf.registrar/clear-kind! :sub)
    (rf.registrar/clear-kind! :event)
    (try
      (test-fn)
      (finally
        (reset! (deref #'rf.registrar/registration-hooks) reg-hooks-before)
        (reset! (deref #'rf.registrar/replacement-hooks)  repl-hooks-before)
        (reset! rf.frame/frames frames-before)
        (rf.registrar/clear-kind! :sub)
        (rf.registrar/clear-kind! :event)))))

(use-fixtures :each isolate-registrar-state)

(deftest register!-throws-on-an-unknown-kind
  (let [before @(deref #'rf.registrar/kind->id->metadata)
        data   (try
                 (rf.registrar/register! :bogus-kind :some/id {:handler-fn identity})
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= before @(deref #'rf.registrar/kind->id->metadata))
        "the throw precedes the write")
    (is (= {:rf.error/id :rf.error/unknown-registry-kind
            :where       'rf/register-handler
            :recovery    :fix-registration
            :kind        :bogus-kind
            :id          :some/id}
           (select-keys data [:rf.error/id :where :recovery :kind :id])))
    (is (string? (:reason data)))))

(deftest registration-hook-fires-on-first-time-and-re-registration
  (let [calls (atom [])]
    (rf.registrar/add-registration-hook! (fn [m] (swap! calls conj m)))
    (rf.registrar/register! :sub :s/one {:handler-fn (fn [] :v1)})
    (rf.registrar/register! :sub :s/one {:handler-fn (fn [] :v2)})
    (is (= [false true] (mapv (comp some? :was) @calls))
        "fires both times; :was carries the previous metadata only on re-registration")))

(deftest replacement-hook-fires-only-on-re-registration
  (let [calls (atom [])]
    (rf.registrar/add-replacement-hook! (fn [m] (swap! calls conj m)))
    (rf.registrar/register! :sub :s/two {:handler-fn (fn [] :a)})
    (is (empty? @calls))
    (rf.registrar/register! :sub :s/two {:handler-fn (fn [] :b)})
    (is (= [true] (mapv :different-fn? @calls)))))

(deftest throwing-registration-hook-is-swallowed
  (let [reached (atom false)]
    (rf.registrar/add-registration-hook! (fn [_] (throw (ex-info "bad hook" {}))))
    ;; installed after the throwing hook: fires only if throws are isolated per hook
    (rf.registrar/add-registration-hook! (fn [_] (reset! reached true)))
    (is (= {:was nil :now {:handler-fn :the-fn}}
           (rf.registrar/register! :sub :s/iso {:handler-fn :the-fn})))
    (is (true? @reached))
    (is (= {:handler-fn :the-fn} (rf.registrar/lookup :sub :s/iso)))))

(deftest throwing-replacement-hook-is-swallowed
  (rf.registrar/add-replacement-hook! (fn [_] (throw (ex-info "bad repl hook" {}))))
  (rf.registrar/register! :sub :s/repl {:handler-fn (fn [] :first)})
  (let [result (rf.registrar/register! :sub :s/repl {:handler-fn (fn [] :second)})]
    (is (some? (:was result)))
    (is (some? (rf.registrar/lookup :sub :s/repl)))))

(deftest frame-disposed-for-drain?-distinguishes-record-state-branches
  (reset! rf.frame/frames
          {:f/live      {:lifecycle {:destroyed? false}}
           :f/destroyed {:lifecycle {:destroyed? true}}})
  (is (= [false true true]
         (mapv rf.frame/frame-disposed-for-drain? [:f/live :f/destroyed :f/never-registered]))
      "live, destroyed-but-present, absent"))

(deftest reg-sub-rejects-trailing-args-after-the-handler
  (let [data (try
               (rf.subs/reg-sub :sub/malformed (fn [db _] db) :unexpected-extra)
               (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:rf.error/id :rf.error/reg-sub-bad-args
            :where       'rf/reg-sub
            :recovery    :fix-registration
            :id          :sub/malformed}
           (select-keys data [:rf.error/id :where :recovery :id])))
    (is (nil? (rf.registrar/lookup :sub :sub/malformed)))))

(deftest reg-sub-rejects-a-retired-arrow-whatever-follows-it
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/reg-sub-bad-args"
        (rf.subs/reg-sub :sub/dangling-arrow :<- :not-a-vector (fn [v _] v))))
  (is (nil? (rf.registrar/lookup :sub :sub/dangling-arrow))))
