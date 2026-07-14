(ns re-frame.join-recordable-transport-cljs-test
  "rf2-t154jx — carry `:spawn-all` join authority through REPLAY and DELAYED
  dispatch.

  #5839 placed the only exact-attempt credential in METADATA on the inner
  completion event vector. That authority did not survive two supported delivery
  paths:

    - Recorded strict replay: event metadata is NOT part of the event/coeffect
      recording contract; an EDN round-trip drops it, so replaying a real
      completion silently no-op'd instead of reproducing the fold — violating
      replay-faithfulness (rf2-nvxehu's exact-authority promise).
    - Live delayed dispatch: `stamp-fx-entry` handled `:dispatch` /
      `:dispatch-n` but NOT the reserved `:dispatch-later` `{:ms n :event event}`
      shape, so a child completing through `:dispatch-later` reached the parent
      unauthenticated and was suppressed, hanging an `:all` join forever.

  The fix carries the exact authority on the RECORDABLE `:rf.cofx` causal-
  envelope fact `:rf.machine/join-auth` (via the `:rf.machine/join-dispatch`
  transport `stamp-fx-entry` rewrites both `:dispatch` and `:dispatch-later`
  completions into), and reads it back through `carrier-auth`. The public event
  value and closed grammar are unchanged; missing / tampered authority is
  validated through the existing typed stale policy, never a silent no-op."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:clj  [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

(defn- edn-roundtrip
  "Serialize + re-read `x` through EDN — the same value-only round-trip a
  Tool-Pair strict replay applies to a recorded event + its causal coeffects.
  Event-vector METADATA does NOT survive this; `:rf.cofx` map facts DO."
  [x]
  (edn/read-string (pr-str x)))

(defn- join-state [parent-id]
  (get-in (mtest/runtime-db) [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (mtest/events-of :rf.machine.spawn-all/stale-completion)))

(defn- mk-child
  "A dispatching child: on `:go` transitions to a plain terminal and dispatches
  its completion back to `parent-id` via `:dispatch` (through its OWN handler
  boundary, so the runtime attaches the recordable `:rf.machine/join-auth`)."
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

(defn- auth-for
  "Build the exact-authority tuple a live child :a completion carries, from the
  live join-state."
  [parent-kw]
  (let [j (join-state parent-kw)]
    {:parent-id  parent-kw
     :invoke-id  [:racing]
     :child-id   :a
     :spawned-id (get-in j [:children :a])
     :attempt    (:rf/attempt j)}))

;; ---------------------------------------------------------------------------
;; replay-faithful recordable transport
;; ---------------------------------------------------------------------------

(deftest recorded-authority-survives-edn-roundtrip-and-folds
  (testing "rf2-t154jx — the exact authority rides the RECORDABLE :rf.cofx fact
            :rf.machine/join-auth, so an EDN-roundtripped recorded event + causal
            coeffects strict-replays into the SAME fold. Removing the recordable
            authority fact makes the replay a fail-closed stale drop, never a
            silent replay-success no-op."
    (rf/reg-machine :jt/ca (mk-child :jt/rp))
    (rf/reg-machine :jt/cb (mk-child :jt/rp))
    (reg-parent! :jt/rp :jt/ca :jt/cb)
    (rf/dispatch-sync [:jt/rp [:start]])
    (let [auth        (auth-for :jt/rp)
          rec-event   (edn-roundtrip [:jt/rp [:child/done :a]])
          rec-cofx    (edn-roundtrip {:rf.machine/join-auth auth})]
      (mtest/reset-captured!)
      ;; Strict replay: redispatch the recorded event PLUS its recorded causal
      ;; coeffects (the `:rf.cofx` map, EDN-roundtripped).
      (rf/dispatch-sync rec-event {:rf.cofx rec-cofx})
      (is (= #{:a} (:done (join-state :jt/rp)))
          "the EDN-roundtripped completion + recorded cofx folded :a (replay-faithful)")
      (is (empty? (stale-reasons)) "no stale suppression for the faithful replay")
      ;; Remove the recordable authority fact → fail-closed stale drop.
      (mtest/reset-captured!)
      (rf/dispatch-sync (edn-roundtrip [:jt/rp [:child/done :b]])
                        {:rf.cofx (edn-roundtrip {})})
      (is (= #{:a} (:done (join-state :jt/rp)))
          ":b did NOT fold — the completion with its recordable authority removed is suppressed")
      (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
          "stripping the recordable authority fact is a typed stale drop, not a silent no-op"))))

(deftest metadata-only-carrier-is-dropped-on-replay
  (testing "rf2-t154jx — a metadata-only carrier (what #5839 produced) does NOT
            survive the EDN round-trip: replaying it (metadata dropped, no
            recordable cofx) is suppressed :attempt-unverified rather than
            silently folding or no-op'ing."
    (rf/reg-machine :jt/ma (mk-child :jt/mp))
    (rf/reg-machine :jt/mb (mk-child :jt/mp))
    (reg-parent! :jt/mp :jt/ma :jt/mb)
    (rf/dispatch-sync [:jt/mp [:start]])
    (let [auth        (auth-for :jt/mp)
          ;; The #5839 wire shape: authority on inner-event METADATA.
          meta-event  [:jt/mp (with-meta [:child/done :a] {:rf/join-auth auth})]
          replayed    (edn-roundtrip meta-event)]  ;; EDN drops metadata
      (mtest/reset-captured!)
      (rf/dispatch-sync replayed)
      (is (= #{} (:done (join-state :jt/mp)))
          "the metadata-stripped replay folded nothing")
      (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
          "the replay is a fail-closed stale drop"))))

;; ---------------------------------------------------------------------------
;; live delayed dispatch
;; ---------------------------------------------------------------------------

(deftest zero-delay-dispatch-later-completion-authenticates-and-folds
  (testing "rf2-t154jx — a real child completing through a zero-delay
            :dispatch-later authenticates (the delayed path #5839's metadata
            stamp never covered) and folds + resolves the join exactly once."
    (rf/reg-machine :jt/da (mk-child-delayed :jt/dp))
    (rf/reg-machine :jt/db (mk-child-delayed :jt/dp))
    (reg-parent! :jt/dp :jt/da :jt/db)
    (rf/dispatch-sync [:jt/dp [:start]])
    (let [j (join-state :jt/dp)
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      (mtest/reset-captured!)
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :jt/dp)))
          ":a folded via the zero-delay :dispatch-later completion")
      (is (empty? (stale-reasons)) "no stale suppression for the delayed completion")
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jt/dp)))
          "the second delayed completion resolves the :all join"))))

(deftest old-attempt-delayed-completion-across-reentry-is-superseded
  (testing "rf2-t154jx — an old-attempt completion arriving across re-entry with
            attempt-1 authority on the recordable cofx is classified
            :attempt-superseded and cannot fold into the successor join."
    (rf/reg-machine :jt/oa (mk-child :jt/op))
    (rf/reg-machine :jt/ob (mk-child :jt/op))
    (reg-parent! :jt/op :jt/oa :jt/ob)
    (rf/dispatch-sync [:jt/op [:start]])
    (let [attempt1-auth (auth-for :jt/op)]
      ;; Re-enter: attempt 2.
      (rf/dispatch-sync [:jt/op [:abort]])
      (rf/dispatch-sync [:jt/op [:start]])
      (let [j2 (join-state :jt/op)]
        (is (not= (:attempt attempt1-auth) (:rf/attempt j2)) "attempt 2 minted a new token")
        (mtest/reset-captured!)
        ;; The stale straggler delivered with attempt-1 authority on the cofx.
        (rf/dispatch-sync [:jt/op [:child/done :a]]
                          {:rf.cofx {:rf.machine/join-auth attempt1-auth}})
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
