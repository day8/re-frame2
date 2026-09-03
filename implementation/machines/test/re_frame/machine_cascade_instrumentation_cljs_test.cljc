(ns re-frame.machine-cascade-instrumentation-cljs-test
  "The `:rf.machine/transition` trace carries a structured `:cascade` field:
  the ordered exit / transition-action / entry steps (+ initial-descent +
  `:always` microsteps + per-region structure) that explain HOW a transition
  reached its after-state, so tooling (Xray's epoch panel) can render the
  cascade rather than only `{from}->{to} + {n} microstep(s)`.

  The cascade step shape (the contract tooling renders) is a vector of
  self-describing step maps in execution order:

    {:kind   :exit | :action | :entry | :microstep
     :state  <state-path-vector>          ;; LCA-relative for :action
     :region <region-name-or-nil>          ;; parallel region; nil flat/compound
     :action <action-id-or-nil>            ;; nil = boundary with no declared action
     :data-delta {<k> <new-v>}}            ;; this step's :data contribution

  `:microstep` steps add `:microstep-index` / `:from` / `:to` / `:steps`
  (the microstep's own nested exit/action/entry cascade).

  The HVAC fixture mirrors the live machine-epochs testbed
  (`:hvac/controller`) whose own `:data :trail` records the cascade order
  — that trail is the ORACLE these tests cross-check the emitted cascade
  against.

  Dual-target (`.cljc`): the JVM runner selects it on `.*-test$`, Shadow's
  `:node-test` build on `cljs-test$`. The `-cljs-test` suffix is therefore
  load-bearing — a `.cljc` test whose ns ends in a plain `-test` compiles
  nowhere but the JVM and reads as covered (rf2-dn6v7, rf2-lgozq)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  ;; `rf.machines.test-support/with-trace-capture` is a `#?(:clj (defmacro …))` in a `.cljc`
  ;; support ns, so the CLJS analyzer needs it required as a MACRO ns under
  ;; the same alias; a plain `:require` leaves the call compiling to a
  ;; function call on an undefined var. This is the JVM-only assumption the
  ;; rf2-lgozq rename exposed — all seven tests here errored on the CLJS lane
  ;; with `No protocol method IDeref.-deref defined for type undefined` until
  ;; this line existed.
  #?(:cljs (:require-macros [re-frame.machines.test-support :as rf.machines.test-support])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Routed through the shared `rf.machines.test-support/with-trace-capture`
;; — guaranteed unregister in a `finally`.
(defn- record-traces! [body-fn]
  (rf.machines.test-support/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

(defn- the-transition
  "The single `:rf.machine/transition` trace produced by the body — the
  outer macrostep trace whose `:cascade` we assert on. (Bootstrap +
  user-event in separate dispatches each produce one; callers pick.)"
  [evs]
  (last (ops evs :rf.machine/transition)))

(defn- cascade-of [evs] (-> (the-transition evs) :tags :cascade))

;; A `:trail`-appending action: conj's `label` onto `[:data :trail]`, so
;; the post-macrostep trail is the cascade order made visible — the same
;; oracle the live `:hvac/controller` testbed uses.
(defn- trail-action [label]
  (fn [{data :data}]
    {:data (update data :trail (fnil conj []) label)}))

;; =====================================================================
;; HVAC fixture — a faithful copy of the live machine-epochs testbed's
;; :hvac/controller (parallel, deep-compound :climate region + flatter
;; :fan region). The oracle: dispatching [:hvac/power-cycle] from the
;; rest configuration produces the trail
;;   [:action:power-on :entry:running :entry:conditioning :entry:heating
;;    :action:fan-on :entry:fan-on]
;; =====================================================================

(defn- reg-hvac! []
  (rf/reg-machine :hvac/controller
    {:type :parallel
     :data {:trail []}
     :regions
     {:climate
      {:initial :idle
       :states
       {:idle    {:on {:hvac/power-cycle {:target :running :action :enter-running}}}
        :running {:initial :conditioning
                  :entry   :enter-running-level
                  :exit    :exit-running-level
                  :on      {:hvac/power-cycle {:target :idle :action :back-to-idle}}
                  :states
                  {:conditioning
                   {:initial :heating
                    :entry   :enter-conditioning
                    :exit    :exit-conditioning
                    :states
                    {:heating {:entry :enter-heating
                               :exit  :exit-heating
                               :on    {:hvac/mode-toggle {:target :cooling :action :swap-mode}}}
                     :cooling {:entry :enter-cooling
                               :exit  :exit-cooling
                               :on    {:hvac/mode-toggle {:target :heating :action :swap-mode}}}}}}}}}
      :fan
      {:initial :off
       :states
       {:off {:on {:hvac/power-cycle {:target :on :action :fan-on}}}
        :on  {:entry :enter-fan-on
              :exit  :exit-fan-on
              :on    {:hvac/power-cycle {:target :off :action :fan-off}
                      :hvac/nudge {:target :same-state :reenter? true :action :nudge-fan}
                      :hvac/tweak {:action :tweak-fan}}}}}}
     :actions
     {:enter-running       (trail-action :action:power-on)
      :enter-running-level (trail-action :entry:running)
      :exit-running-level  (trail-action :exit:running)
      :back-to-idle        (trail-action :action:power-off)
      :enter-conditioning  (trail-action :entry:conditioning)
      :exit-conditioning   (trail-action :exit:conditioning)
      :enter-heating       (trail-action :entry:heating)
      :exit-heating        (trail-action :exit:heating)
      :enter-cooling       (trail-action :entry:cooling)
      :exit-cooling        (trail-action :exit:cooling)
      :swap-mode           (trail-action :action:swap-mode)
      :fan-on              (trail-action :action:fan-on)
      :fan-off             (trail-action :action:fan-off)
      :enter-fan-on        (trail-action :entry:fan-on)
      :exit-fan-on         (trail-action :exit:fan-on)
      :nudge-fan           (trail-action :action:nudge)
      :tweak-fan           (trail-action :action:tweak)}}))

(defn- boot-hvac! []
  (rf/dispatch-sync [:hvac/controller [:rf.machine/start]]))

(defn- trail-of []
  (get-in @(rf/subscribe [:rf/machine :hvac/controller]) [:data :trail]))

;; ---- the deep compound + parallel cascade (the headline case) -------------

(deftest power-cycle-emits-ordered-cascade-matching-the-trail-oracle
  (testing "[:hvac/power-cycle] emits a :rf.machine/transition whose :cascade
   lists the ordered exit/action/entry steps matching the engine's actual
   cascade — cross-checked against the testbed's :data :trail oracle"
    (reg-hvac!)
    (boot-hvac!)
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]])))
          tr      (the-transition evs)
          cascade (-> tr :tags :cascade)
          trail   (trail-of)]
      ;; RED guard: the field exists and is a non-empty structured vector.
      (is (some? tr) "a :rf.machine/transition trace fired")
      (is (vector? cascade) ":cascade is a vector (RED: was absent)")
      (is (seq cascade) ":cascade is non-empty (RED: only before/after+count)")

      ;; The trail oracle for power-cycle from rest (climate region first,
      ;; then fan region — declaration order).
      (is (= [:action:power-on :entry:running :entry:conditioning :entry:heating
              :action:fan-on :entry:fan-on]
             trail)
          "trail oracle confirms the cascade order")

      ;; Every cascade step that RAN an action must, in order, project to
      ;; that action's trail label — i.e. the cascade is the trail made
      ;; structured. (Boundaries with no action carry nil :action and an
      ;; empty :data-delta; they don't append to the trail.)
      (let [acting (filterv :action (remove #(= :microstep (:kind %)) cascade))
            ;; each action's :data-delta :trail is the WHOLE trail-so-far;
            ;; the step's contribution is its LAST element.
            labels (mapv #(last (get-in % [:data-delta :trail])) acting)]
        (is (= trail labels)
            "the ordered acting-step labels reconstruct the trail exactly"))

      ;; Per-step structure: kinds + states + regions are self-describing.
      ;; The cascade is a COMPLETE configuration walk: :idle / :off are
      ;; EXITED (no :exit action declared → :action nil, empty delta) ahead
      ;; of the action + entry boundaries. The trail oracle only captures
      ;; the action-bearing boundaries; the cascade additionally records the
      ;; action-free exits so the geometry is complete.
      (let [climate (filterv #(= :climate (:region %)) cascade)
            fan     (filterv #(= :fan (:region %)) cascade)]
        (is (= [:exit :action :entry :entry :entry] (mapv :kind climate))
            ":climate — exit :idle (no action) -> transition action @ LCA -> 3-level entry/initial-descent")
        (is (= [[:idle] [:idle] [:running] [:running :conditioning]
                [:running :conditioning :heating]]
               (mapv :state climate))
            ":climate step :state paths are region-relative (exit @ [:idle], action @ decl-path, entries shallowest-first)")
        (is (= [:exit :action :entry] (mapv :kind fan))
            ":fan — exit :off (no action) -> action -> single entry")
        (is (= [nil :enter-running :enter-running-level :enter-conditioning :enter-heating]
               (mapv :action climate))
            ":climate action-ids in cascade order (leading nil = action-free :idle exit)")
        (is (= [nil :fan-on :enter-fan-on] (mapv :action fan))
            ":fan action-ids in cascade order (leading nil = action-free :off exit)")))))

;; ---- the LCA cascade: exit (deepest-first) -> action -> entry -------------

(deftest mode-toggle-emits-exit-action-entry-across-the-lca
  (testing ":hvac/mode-toggle on :heating crosses the :conditioning LCA to
   :cooling — exit :heating (deepest-first) -> swap-mode @ LCA -> entry
   :cooling (shallowest-first); the cascade carries all three in order"
    (reg-hvac!)
    (boot-hvac!)
    (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]]) ;; land on :heating
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/mode-toggle]])))
          cascade (cascade-of evs)
          climate (filterv #(= :climate (:region %)) cascade)]
      (is (= [:exit :action :entry] (mapv :kind climate))
          "exit -> action -> entry order")
      (is (= [:exit-heating :swap-mode :enter-cooling] (mapv :action climate)))
      ;; trail oracle for the toggle leg only (it appends after power-cycle).
      (is (= [:exit:heating :action:swap-mode :entry:cooling]
             (take-last 3 (trail-of)))
          "trail oracle: exit:heating -> action:swap-mode -> entry:cooling"))))

;; ---- self-transitions (external = exit+entry; internal = action only) -----

(deftest external-self-transition-shows-exit-and-entry
  (testing ":hvac/nudge (external self-transition, :target :same-state +
   :reenter? true) on :fan :on re-enters itself: exit :fan-on -> nudge ->
   entry :fan-on"
    (reg-hvac!)
    (boot-hvac!)
    (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]]) ;; :fan -> :on
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/nudge]])))
          cascade (cascade-of evs)
          fan     (filterv #(= :fan (:region %)) cascade)]
      (is (= [:exit :action :entry] (mapv :kind fan))
          "external self-transition fires exit + action + entry")
      (is (= [:exit-fan-on :nudge-fan :enter-fan-on] (mapv :action fan))))))

(deftest internal-self-transition-shows-action-only
  (testing ":hvac/tweak (internal self-transition, no :target) on :fan :on
   fires the action ONLY — no exit, no entry"
    (reg-hvac!)
    (boot-hvac!)
    (rf/dispatch-sync [:hvac/controller [:hvac/power-cycle]]) ;; :fan -> :on
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:hvac/controller [:hvac/tweak]])))
          cascade (cascade-of evs)
          fan     (filterv #(= :fan (:region %)) cascade)]
      (is (= [:action] (mapv :kind fan))
          "internal self-transition is action-only — no exit/entry boundary")
      (is (= [:tweak-fan] (mapv :action fan))))))

;; ---- flat single-step transition: minimal correct cascade -----------------

(deftest flat-transition-emits-minimal-cascade
  (testing "a flat single-step transition emits a minimal correct cascade:
   one exit boundary (no action), the transition action, one entry boundary
   (no action)"
    (rf/reg-machine :casc/flat
      {:initial :a
       :actions {:go-act (fn [{d :data}] {:data (assoc d :went true)})}
       :states  {:a {:on {:go {:target :b :action :go-act}}}
                 :b {}}})
    (rf/dispatch-sync [:casc/flat [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/flat [:go]])))
          cascade (cascade-of evs)]
      (is (= [{:kind :exit  :state [:a] :region nil :action nil  :data-delta {}}
              {:kind :action :state [:a] :region nil :action :go-act :data-delta {:went true}}
              {:kind :entry :state [:b] :region nil :action nil  :data-delta {}}]
             cascade)
          "flat cascade: exit[:a] (no action) -> action @ decl-path -> entry[:b] (no action)"))))

;; ---- :always / eventless microsteps appear in the cascade -----------------

(deftest always-microstep-appears-in-cascade
  (testing "an :always-driven cascade appends a :microstep step carrying the
   eventless transition's own nested exit/action/entry :steps, so microsteps
   are explainable (not just counted)"
    (rf/reg-machine :casc/quiz
      {:initial :asking
       :data    {:correct 9}
       :guards  {:enough? (fn [{d :data}] (>= (:correct d) 10))}
       :actions {:count (fn [{d :data}] {:data {:correct (inc (:correct d))}})
                 :win   (fn [{d :data}] {:data (assoc d :won true)})}
       :states  {:asking {:always [{:guard :enough? :target :winner :action :win}]
                          :on     {:answer {:action :count}}}
                 :winner {}}})
    (rf/dispatch-sync [:casc/quiz [:rf.machine/start]])
    (let [evs       (record-traces!
                      (fn [] (rf/dispatch-sync [:casc/quiz [:answer]])))
          cascade   (cascade-of evs)
          microstep (first (filterv #(= :microstep (:kind %)) cascade))]
      ;; the :answer transition's action (:count) is the headline step
      (is (some #(= :count (:action %)) cascade)
          "the event-driven :count action is in the cascade")
      ;; the :always step that flipped to :winner rides as a :microstep
      (is (some? microstep) "a :microstep step is present in the cascade")
      (is (= 0 (:microstep-index microstep)))
      (is (= :asking (:from microstep)))
      (is (= :winner (:to microstep)))
      (is (vector? (:steps microstep)) ":microstep carries its own nested :steps")
      (is (some #(= :win (:action %)) (:steps microstep))
          "the eventless transition's :win action is explainable inside the microstep"))))

;; ---- raised (internal) events appear as their own cascade boundary --------
;;
;; rf2-nb8nj — a same-macrostep raised event selects a REAL transition whose
;; exit/action/entry geometry belongs to THAT event, not to the dispatched
;; one. Before this fix the flat/compound drain discarded those rows outright
;; (the loop recurred with the cascade unchanged) and the parallel parent
;; queue flattened them in with no boundary, so the cascade could say
;; `:idle -> :done` while explaining only `:idle -> :working`.
;;
;; The record is one nested `:kind :raised-transition` wrapper per HANDLED
;; dequeue, in actual FIFO order, carrying the internal `:event`, its
;; `:from`/`:to`, and the nested ordered `:steps`.

(defn- raised-steps
  "The `:kind :raised-transition` wrappers off a cascade, in cascade order."
  [cascade]
  (filterv #(= :raised-transition (:kind %)) cascade))

(defn- reg-settle! []
  ;; The item's own reproduction: external `:go` moves :idle -> :working and
  ;; its action raises [:settle]; :working handles :settle with :target :done
  ;; plus entry/exit actions.
  (rf/reg-machine :casc/settle
    {:initial :idle
     :data    {:trail []}
     :actions {:start   (fn [{d :data}]
                          {:data (update d :trail (fnil conj []) :action:go)
                           :fx   [[:raise [:settle]]]})
               :settled (trail-action :action:settle)
               :exit-working (trail-action :exit:working)
               :enter-done   (trail-action :entry:done)}
     :states  {:idle    {:on {:go {:target :working :action :start}}}
               :working {:exit :exit-working
                         :on   {:settle {:target :done :action :settled}}}
               :done    {:entry :enter-done}}}))

(deftest raised-event-transition-is-recorded-as-its-own-cascade-boundary
  (testing "external :go raises [:settle], which moves :working -> :done in the
   SAME macrostep. The committed snapshot is :done (raise FIFO semantics
   already worked), and the single :rf.machine/transition cascade explains the
   WHOLE walk: the :go geometry, then one :raised-transition wrapper carrying
   the raised event and its own ordered exit/action/entry steps"
    (reg-settle!)
    (rf/dispatch-sync [:casc/settle [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/settle [:go]])))
          tr      (the-transition evs)
          cascade (cascade-of evs)
          raised  (raised-steps cascade)]
      ;; Premise: the FOLD is already correct — only the record was lossy.
      (is (= :done (:state (rf.machines.test-support/snapshot :casc/settle)))
          "the committed snapshot is :done — FIFO raise semantics")
      (is (= :idle (get-in tr [:tags :before :state])))
      (is (= :done (get-in tr [:tags :after :state]))
          "the headline trace spans the WHOLE macrostep")

      ;; The external event's own geometry stays the headline, un-nested.
      (let [outer (remove #(= :raised-transition (:kind %)) cascade)]
        (is (= [:exit :action :entry] (mapv :kind outer))
            "the external :go transition's own exit/action/entry stay at top level")
        (is (= [nil :start nil] (mapv :action outer))
            "the top-level rows are the :go transition's, not the raise's"))

      ;; The raised event's rows are neither discarded nor flattened.
      (is (= 1 (count raised))
          "exactly one :raised-transition wrapper (RED before rf2-nb8nj: the
           flat drain recurred with the cascade unchanged, so this was 0)")
      (let [w (first raised)]
        (is (= [:settle] (:event w)) "the wrapper names the internal event")
        (is (= :working (:from w)) "from-state is where the raise was dequeued")
        (is (= :done    (:to w))   "to-state is where the raised transition landed")
        (is (nil? (:region w))     "flat/compound machines carry :region nil")
        (is (vector? (:steps w))   "the wrapper carries its own nested steps")
        (is (= [:exit :action :entry] (mapv :kind (:steps w)))
            "the raised transition's own LCA walk rides INSIDE the wrapper")
        (is (= [:exit-working :settled :enter-done] (mapv :action (:steps w)))
            "exit :working -> :settled @ LCA -> entry :done"))

      ;; The wrapper sits AFTER the external rows — execution order.
      (is (= :raised-transition (:kind (last cascade)))
          "the raised boundary is appended in execution order")

      ;; Trail oracle: the flattened action order the cascade must explain.
      (is (= [:action:go :exit:working :action:settle :entry:done]
             (get-in @(rf/subscribe [:rf/machine :casc/settle]) [:data :trail]))
          "trail oracle for the whole macrostep"))))

(deftest raised-wrappers-follow-actual-fifo-dequeue-order
  (testing "two raises emitted by one action are dequeued FIFO, and the
   cascade's :raised-transition wrappers appear in that same order"
    (rf/reg-machine :casc/fifo
      {:initial :a
       :actions {:fan-out (fn [_] {:fx [[:raise [:first]] [:raise [:second]]]})
                 :noop    (fn [_] {})}
       :states  {:a {:on {:go {:target :b :action :fan-out}}}
                 :b {:on {:first {:target :c :action :noop}}}
                 :c {:on {:second {:target :d :action :noop}}}
                 :d {}}})
    (rf/dispatch-sync [:casc/fifo [:rf.machine/start]])
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:casc/fifo [:go]])))
          raised (raised-steps (cascade-of evs))]
      (is (= :d (:state (rf.machines.test-support/snapshot :casc/fifo))))
      (is (= [[:first] [:second]] (mapv :event raised))
          "wrappers are ordered by actual FIFO dequeue order")
      (is (= [[:b :c] [:c :d]] (mapv (juxt :from :to) raised))
          "each wrapper's from/to is its own hop, not the macrostep's span"))))

(deftest always-enabled-by-a-raised-transition-rides-inside-its-wrapper
  (testing "an :always transition enabled by a raised transition settles
   INSIDE that raise's nested cascade, so the microstep is attributed to the
   internal event that enabled it rather than to the dispatched event"
    (rf/reg-machine :casc/raise-always
      {:initial :a
       :data    {:n 0}
       :actions {:kick  (fn [_] {:fx [[:raise [:tick]]]})
                 :count (fn [{d :data}] {:data {:n (inc (:n d))}})
                 :win   (fn [{d :data}] {:data (assoc d :won true)})}
       :states  {:a {:on {:go {:target :b :action :kick}}}
                 :b {:on {:tick {:target :c :action :count}}}
                 :c {:always [{:target :d :action :win}]}
                 :d {}}})
    (rf/dispatch-sync [:casc/raise-always [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/raise-always [:go]])))
          cascade (cascade-of evs)
          raised  (raised-steps cascade)
          w       (first raised)]
      (is (= :d (:state (rf.machines.test-support/snapshot :casc/raise-always))))
      (is (= 1 (count raised)))
      (is (empty? (filterv #(= :microstep (:kind %)) cascade))
          "the :always did NOT settle at top level — it was enabled by the raise")
      (let [nested-micro (filterv #(= :microstep (:kind %)) (:steps w))]
        (is (= 1 (count nested-micro))
            "the :always microstep rides inside the raised boundary")
        (is (= :c (:from (first nested-micro))))
        (is (= :d (:to   (first nested-micro))))
        (is (some #(= :win (:action %)) (:steps (first nested-micro)))
            "the eventless transition's action is explainable inside the nest")))))

(deftest ignored-and-guard-blocked-raises-fabricate-no-wrapper
  (testing "a raised event no state handles, and a raised event whose only
   candidate is guard-blocked, each contribute NO :raised-transition wrapper —
   an unhandled internal event is not a transition"
    (rf/reg-machine :casc/ignored
      {:initial :a
       :data    {:open? false}
       :guards  {:open? (fn [{d :data}] (:open? d))}
       :actions {:raise-unknown (fn [_] {:fx [[:raise [:nobody-handles-this]]]})
                 :raise-guarded (fn [_] {:fx [[:raise [:blocked]]]})
                 :noop          (fn [_] {})}
       :states  {:a {:on {:go      {:target :b :action :raise-unknown}
                          :guarded {:target :b :action :raise-guarded}}}
                 :b {:on {:blocked {:guard :open? :target :c :action :noop}}}
                 :c {}}})
    (rf/dispatch-sync [:casc/ignored [:rf.machine/start]])
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:casc/ignored [:go]])))]
      (is (= :b (:state (rf.machines.test-support/snapshot :casc/ignored))))
      (is (empty? (raised-steps (cascade-of evs)))
          "an IGNORED raised event fabricates no handled-transition wrapper"))
    ;; reset to :a is not needed — re-register a fresh machine for the guard leg
    (rf/reg-machine :casc/guarded
      {:initial :a
       :data    {:open? false}
       :guards  {:open? (fn [{d :data}] (:open? d))}
       :actions {:kick (fn [_] {:fx [[:raise [:blocked]]]})
                 :noop (fn [_] {})}
       :states  {:a {:on {:go {:target :b :action :kick}}}
                 :b {:on {:blocked {:guard :open? :target :c :action :noop}}}
                 :c {}}})
    (rf/dispatch-sync [:casc/guarded [:rf.machine/start]])
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:casc/guarded [:go]])))]
      (is (= :b (:state (rf.machines.test-support/snapshot :casc/guarded)))
          "the guard blocked the raised transition — the machine rests at :b")
      (is (empty? (raised-steps (cascade-of evs)))
          "a GUARD-BLOCKED raised event fabricates no handled-transition wrapper"))))

(deftest targetless-raised-transition-records-its-action-boundary
  (testing "a handled raised transition with NO :target still records its
   boundary — the wrapper is present with :from equal to :to and the action
   step nested inside it"
    (rf/reg-machine :casc/targetless
      {:initial :a
       :data    {:hits 0}
       :actions {:kick (fn [_] {:fx [[:raise [:ping]]]})
                 :bump (fn [{d :data}] {:data {:hits (inc (:hits d))}})}
       :states  {:a {:on {:go {:target :b :action :kick}}}
                 :b {:on {:ping {:action :bump}}}}})
    (rf/dispatch-sync [:casc/targetless [:rf.machine/start]])
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:casc/targetless [:go]])))
          raised (raised-steps (cascade-of evs))
          w      (first raised)]
      (is (= 1 (count raised))
          "a targetless HANDLED raise still records its boundary")
      (is (= [:ping] (:event w)))
      (is (= (:from w) (:to w))
          ":from equals :to for an internal (targetless) raised transition")
      (is (= [:action] (mapv :kind (:steps w)))
          "action-only — no exit/entry boundary")
      (is (= [:bump] (mapv :action (:steps w)))))))

(deftest synthetic-done-state-signal-uses-the-same-raised-boundary
  (testing "the runtime's synthetic [:rf.machine/done <path>] completion event
   is raised through the SAME internal-event queue, so it is recorded by the
   SAME mechanism — one :raised-transition wrapper, no special dialect"
    (rf/reg-machine :casc/done
      {:initial :work
       :data    {}
       :actions {:finish (fn [{d :data}] {:data (assoc d :finished true)})}
       :states  {:work {:initial :step
                        :on      {:rf.machine/done {:target :wrapped :action :finish}}
                        :states  {:step {:on {:go {:target :end}}}
                                  :end  {:final? true}}}
                 :wrapped {}}})
    (rf/dispatch-sync [:casc/done [:rf.machine/start]])
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:casc/done [:go]])))
          raised (raised-steps (cascade-of evs))
          w      (first raised)]
      (is (= :wrapped (:state (rf.machines.test-support/snapshot :casc/done)))
          "the done signal advanced the enclosing compound")
      (is (= 1 (count raised))
          "the synthetic done signal rides the same raised-transition boundary")
      (is (= :rf.machine/done (first (:event w)))
          "the wrapper names the synthetic completion event")
      (is (some #(= :finish (:action %)) (:steps w))
          "the done-driven transition's action is explainable inside the wrapper"))))

;; ---- root-parallel: raised rebroadcast is GROUPED, not flattened ----------

(deftest parallel-raised-rebroadcast-is-grouped-under-its-internal-event
  (testing "a raised event re-broadcast across a parallel root's regions
   contributes its rows INSIDE one :raised-transition wrapper, so they are no
   longer indistinguishable from the external event's rows. Before rf2-nb8nj
   the parallel parent queue flattened them straight into the accumulator,
   which made Xray read them as evidence that :b's region handled :go."
    (rf/reg-machine :casc/par
      {:type :parallel
       :data {:trail []}
       :regions
       {:left  {:initial :l0
                :states  {:l0 {:on {:go {:target :l1 :action :left-go}}}
                          :l1 {}}}
        :right {:initial :r0
                :states  {:r0 {:on {:settle {:target :r1 :action :right-settle}}}
                          :r1 {}}}}
       :actions {:left-go      (fn [{d :data}]
                                 {:data (update d :trail (fnil conj []) :action:left-go)
                                  :fx   [[:raise [:settle]]]})
                 :right-settle (trail-action :action:right-settle)}})
    (rf/dispatch-sync [:casc/par [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/par [:go]])))
          cascade (cascade-of evs)
          raised  (raised-steps cascade)
          w       (first raised)]
      (is (= {:left :l1 :right :r1} (:state (rf.machines.test-support/snapshot :casc/par)))
          "both regions moved — :go in :left, the raised :settle in :right")
      (is (= 1 (count raised))
          "the rebroadcast contributes ONE boundary (RED before rf2-nb8nj:
           its rows were flattened into the accumulator with no boundary)")
      (is (= [:settle] (:event w)))
      (is (some #(= :right-settle (:action %)) (:steps w))
          ":right's raised-transition rows ride inside the wrapper")

      ;; The misattribution the item names: the top-level (non-wrapper,
      ;; non-microstep) rows must name ONLY the region that handled :go.
      (let [outer-regions (into #{}
                                (comp (remove #(#{:microstep :raised-transition} (:kind %)))
                                      (keep :region))
                                cascade)]
        (is (= #{:left} outer-regions)
            ":right must NOT appear at top level — it declined :go and moved
             only on the raised :settle (RED before rf2-nb8nj: #{:left :right})")))))

(deftest parallel-raise-then-enabled-always-round-appends-in-execution-order
  (testing "root-parallel: :go takes :main :r0 -> :r1 and raises [:settle];
   :settle takes :r1 -> :r2; :r2's :always then advances to :r3. The PARENT
   loop owns the eventless round, so a round a raise ENABLED is the NEXT
   TOP-LEVEL :kind :microstep step after the wrapper — it does NOT nest inside
   it the way the flat/compound drain's nested machine-transition-single
   nests it. The cascade is a faithful record in EXECUTION order either way,
   which is the contract a consumer reads (Spec 005 §The structured transition
   cascade)."
    (rf/reg-machine :casc/par-chain
      {:type :parallel
       :data {:trail []}
       :regions
       {:main {:initial :r0
               :states  {:r0 {:on {:go {:target :r1 :action :kick}}}
                         :r1 {:on {:settle {:target :r2 :action :settled}}}
                         :r2 {:always [{:target :r3 :action :advance}]}
                         :r3 {}}}
        :aux  {:initial :a0
               :states  {:a0 {}}}}
       :actions {:kick    (fn [{d :data}]
                            {:data (update d :trail (fnil conj []) :action:kick)
                             :fx   [[:raise [:settle]]]})
                 :settled (trail-action :action:settled)
                 :advance (trail-action :action:advance)}})
    (rf/dispatch-sync [:casc/par-chain [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/par-chain [:go]])))
          tr      (the-transition evs)
          cascade (cascade-of evs)
          w       (first (raised-steps cascade))
          round   (first (filterv #(= :microstep (:kind %)) cascade))]
      (is (= {:main :r3 :aux :a0} (:state (rf.machines.test-support/snapshot :casc/par-chain)))
          "the event, the raise it emitted, and the round the raise enabled all
           settle inside ONE macrostep")
      (is (= {:main :r0 :aux :a0} (get-in tr [:tags :before :state])))
      (is (= {:main :r3 :aux :a0} (get-in tr [:tags :after :state]))
          "the headline trace collapses the whole three-hop walk into one pair")

      ;; THE ORDERED SHAPE. Both continuation kinds sit at top level, wrapper
      ;; first — this is what a consumer must read in ORDER rather than by
      ;; preferring one kind over the other.
      (is (= [:raised-transition :microstep]
             (filterv #{:raised-transition :microstep} (mapv :kind cascade)))
          "the raise's boundary, then the round it enabled — execution order")

      (is (= [:settle] (:event w)))
      (is (= {:main :r1 :aux :a0} (:from w))
          "the wrapper's :from is the whole composite region-map at dequeue")
      (is (= {:main :r2 :aux :a0} (:to w))
          "…and its :to is where the raised transition landed, BEFORE the round")
      (is (empty? (filterv #(= :microstep (:kind %)) (:steps w)))
          "the enabled round is NOT nested inside the wrapper on the parallel
           parent queue — a round is selected as one frozen CROSS-REGION set
           against the whole configuration, so it can co-select regions the
           raise never touched and cannot belong to the internal event")

      (is (= :main (:region round)) "the round step carries its own region")
      (is (= :r2 (:from round)))
      (is (= :r3 (:to round))
          "the round starts where the RAISE left off, not where :go did")

      (is (= [:action:kick :action:settled :action:advance]
             (get-in @(rf/subscribe [:rf/machine :casc/par-chain]) [:data :trail]))
          "trail oracle for the whole macrostep"))))

;; ---- privacy / size: no source-literal or large-payload leak --------------

(deftest cascade-carries-only-deltas-not-whole-data
  (testing "each step's :data-delta carries ONLY the keys that step changed
   — not the whole (possibly large) :data map — and carries no source
   literals / fn objects; the cascade is keyword/state/delta data only"
    (rf/reg-machine :casc/delta
      {:initial :a
       :data    {:big (vec (range 1000)) :n 0}  ;; a large pre-existing slot
       :actions {:bump (fn [{d :data}] {:data {:n (inc (:n d))}})}
       :states  {:a {:on {:go {:target :b :action :bump}}}
                 :b {}}})
    (rf/dispatch-sync [:casc/delta [:rf.machine/start]])
    (let [evs     (record-traces!
                    (fn [] (rf/dispatch-sync [:casc/delta [:go]])))
          cascade (cascade-of evs)
          act     (first (filterv #(= :action (:kind %)) cascade))]
      (is (= {:n 1} (:data-delta act))
          ":data-delta is the changed key only — the large :big slot does NOT leak in")
      (is (not (contains? (:data-delta act) :big))
          "unchanged large slot is absent from every step delta")
      ;; no fn objects / source strings anywhere in the cascade
      (is (every? (fn [step]
                    (every? (fn [[_ v]] (not (fn? v)))
                            (:data-delta step)))
                  (remove #(= :microstep (:kind %)) cascade))
          "no fn objects ride the cascade :data-delta"))))
