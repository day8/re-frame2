(ns re-frame.core-contract-guards-test
  "Guards on internal seams: the registrar's closed kind set, registration
  and replacement hook firing and isolation (a throwing hook leaves a dev
  breadcrumb), `frame-disposed-for-drain?`'s
  record-state branches, and reg-sub's malformed-form rejection.

  The hook atoms are private with no public clear, so the fixture snapshots
  and restores them."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.subs :as rf.subs]
            [re-frame.test-support :as rf.test-support]))

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
  (let [before @rf.source-store/kind->id->ns->descriptor
        data   (try
                 (rf.registrar/register! :bogus-kind :some/id {:handler-fn identity})
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= before @rf.source-store/kind->id->ns->descriptor)
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
    (rf.registrar/register! :sub :s/two {:handler-fn (fn [] :b)})
    (is (= [true] (mapv :different-fn? @calls)))))

(defn- hook-warnings
  "The `:rf.warning/registration-hook-exception` breadcrumbs in `recs`, as
  `[hook kind id thrown?]`, where `thrown?` says the slot carries `boom`."
  [recs boom]
  (for [ev    recs
        :when (= :rf.warning/registration-hook-exception (:operation ev))
        :let  [{:keys [hook kind id exception]} (:tags ev)]]
    [hook kind id (identical? boom exception)]))

(deftest throwing-registration-hook-is-swallowed
  (let [reached (atom false)
        boom    (ex-info "bad hook" {})]
    (rf.registrar/add-registration-hook! (fn [_] (throw boom)))
    ;; installed after the throwing hook: fires only if throws are isolated per hook
    (rf.registrar/add-registration-hook! (fn [_] (reset! reached true)))
    (rf.test-support/with-trace-recorder! [recs]
      (is (= {:was nil :now {:handler-fn :the-fn}}
             (rf.registrar/register! :sub :s/iso {:handler-fn :the-fn})))
      (when rf.interop/debug-enabled?
        (is (= [[:registration :sub :s/iso true]] (hook-warnings @recs boom))
            "the throw leaves one dev breadcrumb naming the hook and the registration")))
    (is (true? @reached))
    (is (= :the-fn (:handler-fn (rf.registrar/lookup :sub :s/iso))))))

(deftest throwing-replacement-hook-is-swallowed
  (let [boom (ex-info "bad repl hook" {})]
    (rf.registrar/add-replacement-hook! (fn [_] (throw boom)))
    (rf.registrar/register! :sub :s/repl {:handler-fn (fn [] :first)})
    (rf.test-support/with-trace-recorder! [recs]
      (let [result (rf.registrar/register! :sub :s/repl {:handler-fn (fn [] :second)})]
        (is (some? (:was result)))
        (is (some? (rf.registrar/lookup :sub :s/repl)))
        (when rf.interop/debug-enabled?
          (is (= [[:replacement :sub :s/repl true]] (hook-warnings @recs boom))
              "only the re-registration runs the replacement hook, and its throw is reported"))))))

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
            :recovery    :no-recovery
            :id          :sub/malformed}
           (select-keys data [:rf.error/id :where :recovery :id])))
    (is (nil? (rf.registrar/lookup :sub :sub/malformed)))))
