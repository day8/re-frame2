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
  correlation (Xray's `:rf.xray/machine-transitions-for-focused-event`
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
      both trace operations, enabling Xray to group by cascade
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
    (rf/register-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! ::rec)))
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
       :guards  {:ready? (fn [{data :data}] (:ready? data))}
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
       :guards  {:ready? (fn [{data :data}] (:ready? data))}
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
       :guards  {:a? (fn [{d :data}] (:a? d))
                 :b? (fn [{d :data}] (:b? d))
                 :c? (fn [{d :data}] (:c? d))}
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
         :actions {:tap (fn [_] (swap! calls inc) nil)}
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
       :actions {:exit-idle  (fn [_] nil)
                 :do-go      (fn [_] nil)
                 :enter-done (fn [_] nil)}
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
       :actions {:boom (fn [_] (throw (ex-info "boom" {})))}
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
   so `*handler-scope*` auto-stamps `:dispatch-id` under `:tags` — Xray's
   per-event grouping works without any explicit threading"
    (rf/reg-machine :ga/correlate
      {:initial :idle
       :data    {:ready? true}
       :guards  {:ready? (fn [{d :data}] (:ready? d))}
       :actions {:tap (fn [_] nil)}
       :states  {:idle {:on {:go [{:guard :ready? :target :done :action :tap}]}}
                 :done {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:ga/correlate [:go]])))
          g   (first (ops evs :rf.machine/guard-evaluated))
          a   (first (ops evs :rf.machine/action-ran))
          ;; :rf.event/dispatched is the cascade anchor; its
          ;; :rf.trace/dispatch-id is the canonical id that flows through
          ;; `*handler-scope*` into every nested emit. Cross-reference it
          ;; directly here.
          disp (first (ops evs :rf.event/dispatched))
          cascade-id (-> disp :tags :rf.trace/dispatch-id)]
      (is (some? cascade-id) "the originating cascade has a dispatch-id")
      (is (= cascade-id (-> g :tags :rf.trace/dispatch-id))
          "guard-evaluated picks up the cascade dispatch-id from *handler-scope*")
      (is (= cascade-id (-> a :tags :rf.trace/dispatch-id))
          "action-ran picks up the same cascade dispatch-id"))))

;; ---- rf2-4yrr6 — the `:*`-wildcard-throws machine (machine-epochs :fuse/box)
;; ----------------------------------------------------------------------------
;;
;; The machine-epochs testbed's `:fuse/box` is `:armed` with a `:*` wildcard
;; whose `:blow-fuse` action throws (the xstate-v5 'fail loudly on unknown'
;; idiom). Two facts pin the testbed fix:
;;
;;   1. The `[:rf.machine/bootstrap]` EVENT, processed as an inner machine
;;      event, hits the `:*` wildcard (no `:on :rf.machine/bootstrap` entry)
;;      and THROWS. This is why the testbed's reset handler must NOT dispatch
;;      `[:fuse/box [:rf.machine/bootstrap]]` — it would throw ON BOOT.
;;   2. A machine that was NEVER explicitly bootstrapped STILL throws on its
;;      first real unhandled event: it lazily bootstraps (needs-bootstrap?
;;      fires when no snapshot exists) then the wildcard fires. So dropping the
;;      explicit bootstrap from reset keeps Button 11 working.

(defn- fuse-machine-spec []
  {:initial :armed
   :data    {}
   :actions {:note-inspect (fn [{data :data}]
                             {:data (update data :inspections (fnil inc 0))})
             :blow-fuse    (fn [{:keys [event]}]
                             (throw (ex-info "unhandled machine event"
                                             {:event event :where :fuse-wildcard})))}
   :states  {:armed {:on {:fuse/inspect {:action :note-inspect}
                          :*            {:action :blow-fuse}}}}})

(deftest fuse-bootstrap-event-hits-throwing-wildcard
  (testing "rf2-4yrr6 — a `[:rf.machine/bootstrap]` inner event has no `:on`
            entry on `:armed`, so it hits the `:*` wildcard and THROWS. This is
            the on-boot throw the testbed's reset handler caused by explicitly
            dispatching `[:fuse/box [:rf.machine/bootstrap]]` — and why that
            dispatch was dropped."
    (rf/reg-machine :ga/fuse-bootstrap (fuse-machine-spec))
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:ga/fuse-bootstrap [:rf.machine/bootstrap]])))
          errs (ops evs :rf.error/machine-action-exception)]
      (is (= 1 (count errs))
          "the explicit bootstrap dispatch fires the throwing `:*` wildcard")
      (is (= :blow-fuse (-> errs first :tags :action-id))
          "attributed to the wildcard's `:blow-fuse` action"))))

(deftest fuse-unhandled-event-throws-via-lazy-bootstrap
  (testing "rf2-4yrr6 — with NO explicit bootstrap dispatch, the FIRST real
            unhandled event still throws: the machine lazily bootstraps then
            the `:*` wildcard fires. Button 11 keeps working after the testbed
            dropped the explicit `[:fuse/box [:rf.machine/bootstrap]]` from
            reset."
    (rf/reg-machine :ga/fuse-lazy (fuse-machine-spec))
    ;; No `[:rf.machine/bootstrap]` dispatch first — straight to the
    ;; Button-11-style unhandled event against a never-bootstrapped machine.
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:ga/fuse-lazy [:fuse/short-circuit]])))
          errs (ops evs :rf.error/machine-action-exception)]
      (is (= 1 (count errs))
          "the unhandled event lazily boots `:armed` then fires the wildcard")
      (is (= :blow-fuse (-> errs first :tags :action-id))
          "attributed to the wildcard's `:blow-fuse` action")
      (is (= [:fuse/short-circuit] (-> errs first :tags :event))
          "the triggering unhandled event rides the trace"))))

(deftest fuse-explicit-inspect-does-not-throw
  (testing "rf2-4yrr6 — the `:fuse/inspect` event matches the explicit `:on`
            entry (a normal `:note-inspect` action), so it does NOT hit the
            throwing wildcard — only otherwise-unhandled events do."
    (rf/reg-machine :ga/fuse-inspect (fuse-machine-spec))
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:ga/fuse-inspect [:fuse/inspect]])))
          errs (ops evs :rf.error/machine-action-exception)]
      (is (zero? (count errs))
          "an explicitly-handled event does not fire the throwing wildcard"))))
