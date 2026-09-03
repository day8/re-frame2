(ns re-frame.join-recordable-transport-cljs-test
  "rf2-t154jx — carry the `:spawn-all` join-attempt coordinate through REPLAY and DELAYED
  dispatch.

  #5839 placed the only exact-attempt coordinate in METADATA on the inner
  completion event vector. That coordinate did not survive two supported delivery
  paths:

    - Recorded strict replay: event metadata is NOT part of the event/coeffect
      recording contract; an EDN round-trip drops it, so replaying a real
      completion silently no-op'd instead of reproducing the fold — violating
      replay-faithfulness (rf2-nvxehu's exact-attempt promise).
    - Live delayed dispatch: `stamp-fx-entry` handled `:dispatch` /
      `:dispatch-n` but NOT the reserved `:dispatch-later` `{:ms n :event event}`
      shape, so a child completing through `:dispatch-later` reached the parent
      coordinate-less and was suppressed, hanging an `:all` join forever.

  The fix carries the exact-attempt coordinate on the RECORDABLE `:rf.cofx` causal-
  envelope fact `:rf.machine/join-attempt` (via the `:rf.machine/join-dispatch`
  transport `stamp-fx-entry` rewrites both `:dispatch` and `:dispatch-later`
  completions into), and reads it back through `carrier-attempt`. The public event
  value and closed grammar are unchanged; missing / tampered coordinate is
  validated through the existing typed stale policy, never a silent no-op."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:clj  [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [re-frame.core :as rf]
   [re-frame.interop :as rf.interop]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- edn-roundtrip
  "Serialize + re-read `x` through EDN — the same value-only round-trip a
  Tool-Pair strict replay applies to a recorded event + its causal coeffects.
  Event-vector METADATA does NOT survive this; `:rf.cofx` map facts DO."
  [x]
  (edn/read-string (pr-str x)))

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion)))

(defn- mk-child
  "A dispatching child: on `:go` transitions to a plain terminal and dispatches
  its completion back to `parent-id` via `:dispatch` (through its OWN handler
  boundary, so the runtime attaches the recordable `:rf.machine/join-attempt`)."
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
  "Like `mk-child` but completes through a ZERO-DELAY `:dispatch-later` — the
  delivery path #5839's metadata stamp never covered."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch-later {:ms 0 :event [parent-id [:child/done (:id data)]]}]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done :action :dispatch-done}}}
             :done {}}})

(defn- reg-parent!
  "A re-enterable two-child `:all` join parent (children `child-a-kw` /
  `child-b-kw`). Stays on `:racing` at resolution (no `:on` for `:all/done`) so
  the join slot survives post-resolution probes; `:abort` exits, `:start`
  re-enters (a fresh attempt)."
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

(defn- attempt-for
  "Build the exact-attempt tuple a live child :a completion carries, from the
  live join-state."
  [parent-kw]
  (let [j (join-state parent-kw)]
    {:parent-id  parent-kw
     :invoke-id  [:racing]
     :child-id   :a
     :spawned-id (get-in j [:children :a])
     :attempt    (:rf/attempt j)}))

(defn- with-dispatch-stub
  "Run `body-fn` with `:router/dispatch!` replaced by a RECORDING stub that
  captures each `[event opts]` into `sink` WITHOUT draining — so a deferred
  completion's re-dispatch is observed deterministically on both platforms (no
  async router drain to await). Restores the real hook in a `finally`."
  [sink body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch! (fn [event opts] (swap! sink conj [event opts])))
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

(defn- completion-opts
  "The opts of the FIRST recorded re-dispatch whose event is the completion
  carrier `[parent-id [:child/done child-id]]`, or nil."
  [sink parent-id child-id]
  (some (fn [[event opts]]
          (when (= [parent-id [:child/done child-id]] event) opts))
        @sink))

;; ---------------------------------------------------------------------------
;; replay-faithful recordable transport
;; ---------------------------------------------------------------------------

(deftest recorded-attempt-survives-edn-roundtrip-and-folds
  (testing "rf2-t154jx — the exact-attempt coordinate rides the RECORDABLE :rf.cofx fact
            :rf.machine/join-attempt, so an EDN-roundtripped recorded event + causal
            coeffects strict-replays into the SAME fold. Removing the recordable
            coordinate fact makes the replay a fail-closed stale drop, never a
            silent replay-success no-op."
    (rf/reg-machine :jt/ca (mk-child :jt/rp))
    (rf/reg-machine :jt/cb (mk-child :jt/rp))
    (reg-parent! :jt/rp :jt/ca :jt/cb)
    (rf/dispatch-sync [:jt/rp [:start]])
    (let [attempt     (attempt-for :jt/rp)
          rec-event   (edn-roundtrip [:jt/rp [:child/done :a]])
          rec-cofx    (edn-roundtrip {:rf.machine/join-attempt attempt})]
      (rf.machines.test-support/reset-captured!)
      ;; Strict replay: redispatch the recorded event PLUS its recorded causal
      ;; coeffects (the `:rf.cofx` map, EDN-roundtripped).
      (rf/dispatch-sync rec-event {:rf.cofx rec-cofx})
      (is (= #{:a} (:done (join-state :jt/rp)))
          "the EDN-roundtripped completion + recorded cofx folded :a (replay-faithful)")
      (is (empty? (stale-reasons)) "no stale suppression for the faithful replay")
      ;; Remove the recordable coordinate fact → fail-closed stale drop.
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync (edn-roundtrip [:jt/rp [:child/done :b]])
                        {:rf.cofx (edn-roundtrip {})})
      (is (= #{:a} (:done (join-state :jt/rp)))
          ":b did NOT fold — the completion with its recordable coordinate removed is suppressed")
      (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
          "stripping the recordable coordinate fact is a typed stale drop, not a silent no-op"))))

(deftest metadata-only-carrier-is-dropped-on-replay
  (testing "rf2-t154jx — a metadata-only carrier (what #5839 produced) does NOT
            survive the EDN round-trip: replaying it (metadata dropped, no
            recordable cofx) is suppressed :attempt-unverified rather than
            silently folding or no-op'ing."
    (rf/reg-machine :jt/ma (mk-child :jt/mp))
    (rf/reg-machine :jt/mb (mk-child :jt/mp))
    (reg-parent! :jt/mp :jt/ma :jt/mb)
    (rf/dispatch-sync [:jt/mp [:start]])
    (let [attempt     (attempt-for :jt/mp)
          ;; The #5839 wire shape: coordinate on inner-event METADATA.
          meta-event  [:jt/mp (with-meta [:child/done :a] {:rf/join-attempt attempt})]
          replayed    (edn-roundtrip meta-event)]  ;; EDN drops metadata
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync replayed)
      (is (= #{} (:done (join-state :jt/mp)))
          "the metadata-stripped replay folded nothing")
      (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
          "the replay is a fail-closed stale drop"))))

;; ---------------------------------------------------------------------------
;; live delayed dispatch
;; ---------------------------------------------------------------------------

(deftest zero-delay-dispatch-later-completion-defers-then-folds-on-coordinate
  (testing "rf2-21hsb1 / rf2-t154jx — a real child completing through a
            zero-delay :dispatch-later is DEFERRED on the host clock (it does NOT
            fold synchronously — rf2-21hsb1; the canonical child-dispatch! path
            treats every numeric :ms including zero as host-clock-delayed). Once
            the controlled host-clock callback fires, the re-dispatched
            completion retains :source-detail {:ms 0} + the recordable
            :rf.machine/join-attempt, folds (the delayed path #5839's
            metadata stamp never covered), and folds the join exactly once.
            (This test previously asserted a SYNCHRONOUS fold — the (pos? ms)
            regression rf2-21hsb1 corrects.)"
    (rf/reg-machine :jt/da (mk-child-delayed :jt/dp))
    (rf/reg-machine :jt/db (mk-child-delayed :jt/dp))
    (reg-parent! :jt/dp :jt/da :jt/db)
    (rf/dispatch-sync [:jt/dp [:start]])
    (let [a           (get-in (join-state :jt/dp) [:children :a])
          captured-cb (atom nil)
          sink        (atom [])]
      (rf.machines.test-support/reset-captured!)
      (with-dispatch-stub sink
        (fn []
          (with-redefs [rf.interop/set-timeout!   (fn [f _ms] (reset! captured-cb f) ::handle)
                        rf.interop/clear-timeout! (fn [_] nil)]
            ;; The zero-delay :dispatch-later completion is DEFERRED — armed on
            ;; the host clock, NOT folded synchronously (rf2-21hsb1). Restoring
            ;; the (pos? ms) guard would fold it in THIS drain and fail here.
            (rf/dispatch-sync [a [:go]])
            (is (= #{} (:done (join-state :jt/dp)))
                "the zero-delay :dispatch-later completion did NOT fold synchronously (async boundary)")
            (is (some? @captured-cb) "the completion was armed on the host clock (deferred)")
            (is (empty? @sink) "nothing re-dispatched yet — the completion is pending on the host clock")
            ;; Fire the controlled host-clock callback → the deferred re-dispatch.
            (@captured-cb))))
      (let [opts (completion-opts sink :jt/dp :a)]
        (is (some? opts) "the host-clock callback re-dispatched the completion carrier")
        (is (= {:ms 0} (:source-detail opts)) "retains :source-detail {:ms 0} (rf2-21hsb1)")
        (is (some? (get-in opts [:rf.cofx :rf.machine/join-attempt]))
            "the recordable join-attempt coordinate rode the deferred zero-delay completion")
        ;; Deliver the deferred completion (its recorded event + causal cofx): it
        ;; folds the join exactly once.
        (rf/dispatch-sync [:jt/dp [:child/done :a]] {:rf.cofx (:rf.cofx opts)})
        (is (= #{:a} (:done (join-state :jt/dp)))
            "after the callback fires, the delayed completion folds :a exactly once")
        (is (empty? (stale-reasons)) "no stale suppression for the recordable delayed completion")))))

(deftest old-attempt-delayed-completion-across-reentry-is-superseded
  (testing "rf2-t154jx — an old-attempt completion arriving across re-entry with
            attempt-1 coordinate on the recordable cofx is classified
            :attempt-superseded and cannot fold into the successor join."
    (rf/reg-machine :jt/oa (mk-child :jt/op))
    (rf/reg-machine :jt/ob (mk-child :jt/op))
    (reg-parent! :jt/op :jt/oa :jt/ob)
    (rf/dispatch-sync [:jt/op [:start]])
    (let [attempt1-coord (attempt-for :jt/op)]
      ;; Re-enter: attempt 2.
      (rf/dispatch-sync [:jt/op [:abort]])
      (rf/dispatch-sync [:jt/op [:start]])
      (let [j2 (join-state :jt/op)]
        (is (not= (:attempt attempt1-coord) (:rf/attempt j2)) "attempt 2 minted a new token")
        (rf.machines.test-support/reset-captured!)
        ;; The stale straggler delivered with attempt-1 coordinate on the cofx.
        (rf/dispatch-sync [:jt/op [:child/done :a]]
                          {:rf.cofx {:rf.machine/join-attempt attempt1-coord}})
        (is (= #{} (:done (join-state :jt/op)))
            "the old-attempt delayed completion folded nothing")
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))
            "stable typed evidence: :attempt-superseded")))))

;; ---------------------------------------------------------------------------
;; scope: only completion :dispatch / :dispatch-later are rewritten
;; ---------------------------------------------------------------------------

(deftest non-completion-effects-are-not-traversed
  (testing "rf2-t154jx — the transport rewrites ONLY a member child's own
            completion :dispatch / :dispatch-later; an arbitrary custom effect a
            join child also emits is passed through untouched (not traversed /
            rewritten)."
    (let [custom-fired (atom [])]
      (rf/reg-fx :jt/custom-fx (fn [_ payload] (swap! custom-fired conj payload)))
      (rf/reg-machine :jt/xa
        {:initial :running
         :data    {:id nil}
         :actions {:record-id (fn [{d :data e :event}] {:data (assoc d :id (second e))})
                   :complete  (fn [{d :data}]
                                {:fx [[:jt/custom-fx {:hello (:id d)}]
                                      [:dispatch [:jt/xp [:child/done (:id d)]]]]})}
         :states  {:running {:on {:set-id {:action :record-id}
                                  :go     {:target :done :action :complete}}}
                   :done {}}})
      (rf/reg-machine :jt/xb (mk-child :jt/xp))
      (reg-parent! :jt/xp :jt/xa :jt/xb)
      (rf/dispatch-sync [:jt/xp [:start]])
      (let [a (get-in (join-state :jt/xp) [:children :a])]
        (rf/dispatch-sync [a [:go]])
        (is (= #{:a} (:done (join-state :jt/xp)))
            "the child's completion :dispatch was rewritten + folded")
        (is (= [{:hello :a}] @custom-fired)
            "the child's arbitrary custom effect fired untouched (not traversed)")))))
