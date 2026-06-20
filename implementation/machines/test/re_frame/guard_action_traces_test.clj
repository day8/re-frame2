(ns re-frame.guard-action-traces-test
  "The machines substrate emits two cascade-discoverable traces around every
  transition:

    :rf.machine/guard-evaluated
      {:guard-id <kw-or-fn>
       :input    {:data <data> :event <event-vec>}
       :state    <active-state>   ;; the active state the guard ran against
                                  ;; (source disambiguation)
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
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- record-traces!
  "Register a trace listener for the duration of `body-fn`, returning
  the captured trace vec. Routed through the shared
  `mtest/with-trace-capture` — guaranteed unregister in a `finally`."
  [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
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
            "input :event carries the originating event vec")
        ;; the active state the guard ran against is stamped
        ;; so a consumer can disambiguate which state's edge a block
        ;; belongs to (two states reusing the same event + guard id).
        (is (= :idle (-> g :tags :state))
            ":state carries the active state the guard was evaluated against")))))

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

;; ---- the throw-on-boot machine (machine-epochs :fuse/box) ------------------
;;
;; The machine-epochs testbed's `:fuse/box` exercises a machine-action
;; exception ON BOOT. The start marker is a PURE init-kick that STOPS after
;; initial-entry — it is NEVER re-fed into the transition step.
;;
;; The throw is carried by a real initial-`:entry` action on `:armed`: it
;; fires inside the initial-entry cascade itself, on ANY boot (eager
;; `:rf.machine/start` kick OR lazy first-real-event). Three facts pin it:
;;
;;   1. An eager `[:fuse/box [:rf.machine/start]]` kick runs the initial-entry
;;      cascade, whose `:armed` `:entry` action `:blow-fuse` THROWS on boot.
;;   2. A machine NEVER explicitly started STILL throws on its first real
;;      event: it lazily boots (`needs-bootstrap?` fires when no snapshot
;;      exists) and the same `:entry` action throws during that boot — before
;;      the event is ever processed.
;;   3. A machine whose initial state has no throwing `:entry` boots cleanly,
;;      so only the throwing-entry shape trips the exception.

(defn- fuse-machine-spec
  "An `:armed`-at-birth machine whose initial `:entry` action throws on boot."
  []
  {:initial :armed
   :data    {}
   :actions {:blow-fuse (fn [{:keys [event]}]
                          (throw (ex-info "fuse blown on boot"
                                          {:event event :where :fuse-entry})))}
   :states  {:armed {:entry :blow-fuse}}})

(deftest fuse-start-marker-throws-via-initial-entry
  (testing "rf2-4yrr6 / rf2-gl588 — an eager `[:rf.machine/start]` kick runs
            the initial-entry cascade, whose `:armed` `:entry` action throws ON
            BOOT. (Pre-F‴ this was the start marker re-triggering a throwing
            `:*` wildcard; F‴ re-vehicles the throw to a real `:entry`.)"
    (rf/reg-machine :ga/fuse-start (fuse-machine-spec))
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:ga/fuse-start [:rf.machine/start]])))
          errs (ops evs :rf.error/machine-action-exception)]
      (is (= 1 (count errs))
          "the eager start kick fires the throwing initial `:entry` action")
      (is (= :blow-fuse (-> errs first :tags :action-id))
          "attributed to the initial `:entry`'s `:blow-fuse` action"))))

(deftest fuse-throws-on-boot-via-lazy-first-event
  (testing "rf2-4yrr6 / rf2-gl588 — with NO explicit start, the FIRST real
            event still throws: the machine lazily boots (`needs-bootstrap?`
            fires when no snapshot exists) and the initial `:entry` action
            throws DURING that boot, before the event is processed."
    (rf/reg-machine :ga/fuse-lazy (fuse-machine-spec))
    ;; No `[:rf.machine/start]` dispatch first — straight to a real event
    ;; against a never-started machine; the boot `:entry` throws.
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:ga/fuse-lazy [:fuse/short-circuit]])))
          errs (ops evs :rf.error/machine-action-exception)]
      (is (= 1 (count errs))
          "the first real event lazily boots `:armed`; its `:entry` throws")
      (is (= :blow-fuse (-> errs first :tags :action-id))
          "attributed to the initial `:entry`'s `:blow-fuse` action")
      ;; On boot the cascade-threaded `:event` is the synthetic start marker —
      ;; the throw fires inside initial-entry, before the real event is run.
      (is (= [:rf.machine/start] (-> errs first :tags :event))
          "the throw rides the synthetic creation marker (boot-time)"))))

(deftest fuse-clean-initial-entry-does-not-throw
  (testing "rf2-4yrr6 / rf2-gl588 — a machine whose initial state has no
            throwing `:entry` boots cleanly; only the throwing-entry shape
            trips the on-boot exception."
    (rf/reg-machine :ga/fuse-clean
      {:initial :armed
       :data    {}
       :actions {:note-inspect (fn [{data :data}]
                                 {:data (update data :inspections (fnil inc 0))})}
       :states  {:armed {:on {:fuse/inspect {:action :note-inspect}}}}})
    (let [evs  (record-traces!
                 (fn [] (rf/dispatch-sync [:ga/fuse-clean [:fuse/inspect]])))
          errs (ops evs :rf.error/machine-action-exception)]
      (is (zero? (count errs))
          "a non-throwing initial `:entry` boots cleanly"))))
