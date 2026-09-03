(ns re-frame.join-dispatch-override-intercept-cljs-test
  "rf2-ulsbgr — canonical dispatch OVERRIDES intercept `:spawn-all` join
  completions.

  Merged PR #5881 rewrites an authored join-completion `[:dispatch completion]`
  / `:dispatch-later` effect into the private `:rf.machine/join-dispatch` effect
  BEFORE core effect override resolution, so `do-fx` looked up per-call
  `:fx-overrides` under the PRIVATE rewritten id rather than the canonical
  authored `:dispatch` / `:dispatch-later` id. A caller using
  `{:fx-overrides {:dispatch capture-fn}}` inherited that override in the
  envelope but never got to intercept the completion — the reserved transport
  called `child-dispatch!` and folded the parent anyway.

  The fix (`join-dispatch-fx`) resolves the CANONICAL override FIRST against the
  SAME effective override map `do-fx` used (per-frame ⋈ per-call), routing an
  overridden completion back through the shared core seam
  `re-frame.fx/handle-one-fx`. The override captures / redirects it, queues
  nothing, folds nothing, and NO private join authority is minted. Only an
  UNOVERRIDDEN completion is lowered to the recordable transport (authority
  attached).

  MUTATION TOOTH: reverting the fix so `join-dispatch-fx` always
  `child-dispatch!`es (resolving only under the private `:rf.machine/join-
  dispatch` id) makes every capture assertion fail — the override never fires
  and the parent folds instead."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- mk-child
  "A dispatching child: on `:go` transitions to a terminal and dispatches its
  completion back to `parent-id` via `:dispatch` (through its OWN boundary, so
  the runtime attaches the recordable `:rf.machine/join-attempt`)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- mk-child-delayed
  "Like `mk-child` but completes through a `:dispatch-later` with delay `ms`."
  [parent-id ms]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch-later {:ms    ms
                                                      :event [parent-id [:child/done (:id data)]]}]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- reg-parent!
  "A two-child `:all` join parent (children `child-a-kw` / `child-b-kw`)."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}}))

;; ---------------------------------------------------------------------------
;; (1) fn-value :dispatch override intercepts the completion — capture, no fold.
;; ---------------------------------------------------------------------------

(deftest fn-value-dispatch-override-intercepts-join-completion
  (testing "rf2-ulsbgr — a per-call `{:fx-overrides {:dispatch capture-fn}}`
            intercepts a `:spawn-all` child's completion: the fn captures the
            UNCHANGED public completion event exactly once, queues nothing, and
            the parent join does NOT fold / resolve. Before the fix the override
            was looked up under the private `:rf.machine/join-dispatch` id and
            never fired — the transport folded the parent regardless."
    (rf/reg-machine :ulsbgr.fn/ca (mk-child :ulsbgr.fn/rp))
    (rf/reg-machine :ulsbgr.fn/cb (mk-child :ulsbgr.fn/rp))
    (reg-parent! :ulsbgr.fn/rp :ulsbgr.fn/ca :ulsbgr.fn/cb)
    (rf/dispatch-sync [:ulsbgr.fn/rp [:start]])
    (let [captured (atom [])
          a        (get-in (join-state :ulsbgr.fn/rp) [:children :a])]
      (rf/dispatch-sync [a [:go]]
                        {:fx-overrides {:dispatch (fn [_ctx args] (swap! captured conj args))}})
      (is (= [[:ulsbgr.fn/rp [:child/done :a]]] @captured)
          "the :dispatch override captured the UNCHANGED public completion event exactly once")
      (is (= #{} (:done (join-state :ulsbgr.fn/rp)))
          "the parent join folded NOTHING — the override pre-empted the transport (queues nothing)")
      (is (not (true? (:resolved? (join-state :ulsbgr.fn/rp))))
          "the join did not resolve on the captured (un-folded) completion"))))

;; ---------------------------------------------------------------------------
;; (2) keyword-redirect :dispatch override intercepts the completion.
;; ---------------------------------------------------------------------------

(deftest keyword-redirect-dispatch-override-intercepts-join-completion
  (testing "rf2-ulsbgr — an id-redirect `{:fx-overrides {:dispatch :test/capture}}`
            resolves through the ordinary core seam: the registered redirect
            target runs in place of the completion `:dispatch`, capturing it,
            and the parent does not fold."
    (let [captured (atom [])]
      (rf/reg-fx :ulsbgr.kw/capture (fn [_ctx args] (swap! captured conj args)))
      (rf/reg-machine :ulsbgr.kw/ca (mk-child :ulsbgr.kw/rp))
      (rf/reg-machine :ulsbgr.kw/cb (mk-child :ulsbgr.kw/rp))
      (reg-parent! :ulsbgr.kw/rp :ulsbgr.kw/ca :ulsbgr.kw/cb)
      (rf/dispatch-sync [:ulsbgr.kw/rp [:start]])
      (let [a (get-in (join-state :ulsbgr.kw/rp) [:children :a])]
        (rf/dispatch-sync [a [:go]]
                          {:fx-overrides {:dispatch :ulsbgr.kw/capture}})
        (is (= [[:ulsbgr.kw/rp [:child/done :a]]] @captured)
            "the keyword-redirect target captured the completion event")
        (is (= #{} (:done (join-state :ulsbgr.kw/rp)))
            "the parent join folded nothing under the redirect")))))

;; ---------------------------------------------------------------------------
;; (3) :dispatch-later completion is intercepted under the :dispatch-later key.
;; ---------------------------------------------------------------------------

(deftest dispatch-later-override-intercepts-delayed-join-completion
  (testing "rf2-ulsbgr — a completion authored as `:dispatch-later` is
            intercepted by a `{:fx-overrides {:dispatch-later capture-fn}}`
            override under its CANONICAL `:dispatch-later` id: the capture
            observes the canonical delayed payload (`{:ms _ :event _}`),
            schedules no timer, and the parent does not fold. Covers both
            `:ms 0` and a positive delay."
    (doseq [ms [0 500]]
      (let [captured (atom [])
            rp       (keyword "ulsbgr.dl" (str "rp" ms))
            ca       (keyword "ulsbgr.dl" (str "ca" ms))
            cb       (keyword "ulsbgr.dl" (str "cb" ms))]
        (rf/reg-machine ca (mk-child-delayed rp ms))
        (rf/reg-machine cb (mk-child-delayed rp ms))
        (reg-parent! rp ca cb)
        (rf/dispatch-sync [rp [:start]])
        (let [a (get-in (get-in (rf.machines.test-support/runtime-db)
                                [:rf.runtime/machines :spawned rp [:racing]])
                        [:children :a])]
          (rf/dispatch-sync [a [:go]]
                            {:fx-overrides {:dispatch-later (fn [_ctx args] (swap! captured conj args))}})
          (is (= [{:ms ms :event [rp [:child/done :a]]}] @captured)
              (str ":dispatch-later completion captured with the canonical delayed payload (ms=" ms ")"))
          (is (= #{} (:done (get-in (rf.machines.test-support/runtime-db)
                                    [:rf.runtime/machines :spawned rp [:racing]])))
              (str "the parent join folded nothing under the :dispatch-later override (ms=" ms ")")))))))

;; ---------------------------------------------------------------------------
;; (4) control — WITHOUT an override the completion folds normally (authority
;;     path intact). Also: an override of an UNRELATED effect does NOT divert
;;     the completion (only the canonical dispatch id is consulted).
;; ---------------------------------------------------------------------------

(deftest unoverridden-completion-folds-and-unrelated-override-does-not-divert
  (testing "rf2-ulsbgr — the fix is surgical: WITHOUT a `:dispatch` override the
            completion follows the normal recordable transport and folds; and a
            `:fx-overrides` for an UNRELATED effect does NOT divert the
            completion (only the canonical `:dispatch` / `:dispatch-later` id is
            consulted), so the authority path is untouched."
    (rf/reg-machine :ulsbgr.ctl/ca (mk-child :ulsbgr.ctl/rp))
    (rf/reg-machine :ulsbgr.ctl/cb (mk-child :ulsbgr.ctl/rp))
    (reg-parent! :ulsbgr.ctl/rp :ulsbgr.ctl/ca :ulsbgr.ctl/cb)
    (rf/dispatch-sync [:ulsbgr.ctl/rp [:start]])
    (let [a (get-in (join-state :ulsbgr.ctl/rp) [:children :a])]
      ;; No override at all — folds via the recordable transport.
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :ulsbgr.ctl/rp)))
          "the unoverridden completion folded :a through the normal transport")
      ;; Child :b completes carrying an override of an UNRELATED inert effect —
      ;; the completion must still fold (only :dispatch/:dispatch-later divert).
      (let [b (get-in (join-state :ulsbgr.ctl/rp) [:children :b])]
        (rf/reg-fx :ulsbgr.ctl/inert (fn [_ _] nil))
        (rf/dispatch-sync [b [:go]]
                          {:fx-overrides {:ulsbgr.ctl/inert :ulsbgr.ctl/inert}})
        (is (= #{:a :b} (:done (join-state :ulsbgr.ctl/rp)))
            "an override of an unrelated effect did NOT divert the completion — :b folded")
        (is (true? (:resolved? (join-state :ulsbgr.ctl/rp)))
            "both completions folded → the :all join resolved")))))
