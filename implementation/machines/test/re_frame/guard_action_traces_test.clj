(ns re-frame.guard-action-traces-test
  "Per rf2-2nwfd: the machines substrate emits two cascade-discoverable
  traces around every transition:

    :rf.machine/guard-evaluated
      {:guard-id <kw-or-fn>
       :input    {:data <data> :event <event-vec>}
       :outcome  :pass | :fail}

    :rf.machine/action-ran
      {:action-id <kw-or-fn>
       :input     {:data <data> :event <event-vec>}
       :outcome   <action-return> | :ok | :rf.error/action-threw}

  Both traces ride the standard trace bus, so `*handler-scope*`
  auto-stamps `:dispatch-id` under `:tags` — downstream cascade
  correlation (Causa's `:rf.causa/machine-transitions-for-focused-event`
  sub) groups them with the originating event without explicit
  threading from the substrate.

  Locked invariants exercised here:
    - one guard-evaluated trace per user-declared guard evaluation
      (no trace for the synthesised always-true when no `:guard` is set)
    - first-fail short-circuits guard evaluation (no trace for unreached
      candidate guards), but the failing one IS observed
    - one action-ran trace per user-declared action invocation, in
      cascade order (exit → action → entry)
    - `:dispatch-id` matches the originating event's dispatch-id across
      both trace operations, enabling Causa to group by cascade
    - exceptional path: the throwing action emits `action-ran` with
      `:outcome :rf.error/action-threw` AND carries the exception"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- record-traces!
  "Register a trace listener for the duration of `body-fn`, returning
  the captured trace vec."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-trace-listener! ::rec)))
    @seen))

(defn- ops [evs op]
  (filterv #(= op (:operation %)) evs))

;; ---- guard-evaluated: pass + fail outcomes --------------------------------

(deftest guard-evaluated-pass-and-fail-outcomes
  (testing "user-declared guard fires once per evaluation; outcome marker
   matches the guard's boolean return"
    (rf/reg-machine :ga/guard-outcomes
      {:initial :idle
       :data    {:ready? false}
       :guards  {:ready? (fn [data _ev] (:ready? data))}
       :states  {:idle  {:on {:go [{:guard :ready? :target :done}]}}
                 :done  {}}})
    ;; First dispatch: :ready? is false → guard fails.
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/guard-outcomes [:go]])))
          gs  (ops evs :rf.machine/guard-evaluated)]
      (is (= 1 (count gs)) "exactly one guard-evaluated trace")
      (let [g (first gs)]
        (is (= :ready? (-> g :tags :guard-id)) "guard-id is the keyword ref")
        (is (= :fail   (-> g :tags :outcome))  ":fail outcome marker")
        (is (= {:ready? false} (-> g :tags :input :data))
            "input :data carries the snapshot's :data slot")
        (is (= [:go] (-> g :tags :input :event))
            "input :event carries the originating event vec")))))

(deftest guard-evaluated-pass-outcome
  (testing "guard returning true emits :pass outcome and the transition fires"
    (rf/reg-machine :ga/guard-pass
      {:initial :idle
       :data    {:ready? true}
       :guards  {:ready? (fn [data _ev] (:ready? data))}
       :states  {:idle  {:on {:go [{:guard :ready? :target :done}]}}
                 :done  {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/guard-pass [:go]])))
          gs  (ops evs :rf.machine/guard-evaluated)]
      (is (= 1 (count gs)) "exactly one guard-evaluated trace")
      (is (= :pass (-> gs first :tags :outcome)) ":pass outcome marker"))))

;; ---- guard short-circuit: failed guard observed; unreached ones silent ----

(deftest guard-evaluation-short-circuits-on-first-pass
  (testing "the deepest-wins first-pass walker stops at the first guard
   that returns true; later candidates are NOT traced (they did not run)"
    (rf/reg-machine :ga/short-circuit
      {:initial :idle
       :data    {:a? false :b? true :c? true}
       :guards  {:a? (fn [d _] (:a? d))
                 :b? (fn [d _] (:b? d))
                 :c? (fn [d _] (:c? d))}
       :states  {:idle {:on {:go [{:guard :a? :target :A}
                                  {:guard :b? :target :B}
                                  {:guard :c? :target :C}]}}
                 :A    {}
                 :B    {}
                 :C    {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/short-circuit [:go]])))
          gs  (ops evs :rf.machine/guard-evaluated)
          ids (mapv #(-> % :tags :guard-id) gs)
          outs (mapv #(-> % :tags :outcome) gs)]
      (is (= [:a? :b?] ids)
          ":a? failed, :b? passed, :c? never ran (no trace for unreached)")
      (is (= [:fail :pass] outs)
          ":a? :fail, :b? :pass — outcome markers reflect short-circuit semantics"))))

;; ---- guard with no :guard slot: no trace (synthesised always-true) --------

(deftest guard-evaluated-skipped-for-no-guard-clause
  (testing "a transition with no `:guard` is the synthesised always-true —
   not a user-declared evaluation, so no trace fires"
    (rf/reg-machine :ga/no-guard
      {:initial :idle
       :states  {:idle {:on {:go {:target :done}}}
                 :done {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/no-guard [:go]])))
          gs  (ops evs :rf.machine/guard-evaluated)]
      (is (empty? gs)
          "no guard-evaluated trace when the transition omits `:guard`"))))

;; ---- action-ran: success path with :ok marker ------------------------------

(deftest action-ran-success-with-ok-marker
  (testing "a transition's :action that returns nil emits action-ran with :outcome :ok"
    (let [calls (atom 0)]
      (rf/reg-machine :ga/action-ok
        {:initial :idle
         :actions {:tap (fn [_data _ev] (swap! calls inc) nil)}
         :states  {:idle {:on {:go {:target :done :action :tap}}}
                   :done {}}})
      (let [evs (record-traces!
                  (fn [] (rf/dispatch-sync [:ga/action-ok [:go]])))
            as  (ops evs :rf.machine/action-ran)]
        (is (= 1 @calls) "action ran exactly once")
        (is (= 1 (count as)) "exactly one action-ran trace")
        (let [a (first as)]
          (is (= :tap (-> a :tags :action-id)) ":action-id is the keyword ref")
          (is (= :ok  (-> a :tags :outcome))   ":ok marker for nil-returning action")
          (is (= [:go] (-> a :tags :input :event)) ":input :event present"))))))

;; ---- action-ran: cascade order — exit → action → entry --------------------

(deftest action-ran-cascade-order
  (testing "exit cascade → transition :action → entry cascade — action-ran
   traces fire in cascade order"
    (rf/reg-machine :ga/cascade
      {:initial :idle
       :actions {:exit-idle  (fn [_ _] nil)
                 :do-go      (fn [_ _] nil)
                 :enter-done (fn [_ _] nil)}
       :states  {:idle {:exit :exit-idle
                        :on   {:go {:target :done :action :do-go}}}
                 :done {:entry :enter-done}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/cascade [:go]])))
          as  (ops evs :rf.machine/action-ran)
          ids (mapv #(-> % :tags :action-id) as)]
      (is (= [:exit-idle :do-go :enter-done] ids)
          "three action-ran traces in exit → action → entry order"))))

;; ---- action-ran: exception path ------------------------------------------

(deftest action-ran-exception-path
  (testing "throwing action emits action-ran with :outcome :rf.error/action-threw
   AND carries the exception in :tags"
    (rf/reg-machine :ga/throws
      {:initial :idle
       :actions {:boom (fn [_ _] (throw (ex-info "boom" {})))}
       :states  {:idle {:on {:go {:target :done :action :boom}}}
                 :done {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/throws [:go]])))
          as  (ops evs :rf.machine/action-ran)]
      (is (= 1 (count as)) "exactly one action-ran trace for the throwing action")
      (let [a (first as)]
        (is (= :boom (-> a :tags :action-id)) ":action-id captured")
        (is (= :rf.error/action-threw (-> a :tags :outcome))
            ":outcome carries the throw marker")
        (is (instance? Throwable (-> a :tags :exception))
            ":exception slot carries the thrown Throwable")))))

;; ---- cascade correlation: :dispatch-id rides both traces -----------------

(deftest cascade-correlation-dispatch-id-rides-both-traces
  (testing "guard-evaluated and action-ran both ride the standard trace bus,
   so `*handler-scope*` auto-stamps `:dispatch-id` under `:tags` — Causa's
   per-event grouping works without any explicit threading"
    (rf/reg-machine :ga/correlate
      {:initial :idle
       :data    {:ready? true}
       :guards  {:ready? (fn [d _] (:ready? d))}
       :actions {:tap (fn [_ _] nil)}
       :states  {:idle {:on {:go [{:guard :ready? :target :done :action :tap}]}}
                 :done {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/correlate [:go]])))
          g   (first (ops evs :rf.machine/guard-evaluated))
          a   (first (ops evs :rf.machine/action-ran))
          ;; :event/dispatched is the cascade anchor; its :dispatch-id is
          ;; the canonical id that flows through `*handler-scope*` into
          ;; every nested emit. Cross-reference it directly here.
          disp (first (ops evs :event/dispatched))
          cascade-id (-> disp :tags :dispatch-id)]
      (is (some? cascade-id) "the originating cascade has a dispatch-id")
      (is (= cascade-id (-> g :tags :dispatch-id))
          "guard-evaluated picks up the cascade dispatch-id from *handler-scope*")
      (is (= cascade-id (-> a :tags :dispatch-id))
          "action-ran picks up the same cascade dispatch-id"))))
