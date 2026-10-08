(ns re-frame.machines-forbidden-transition-cljs-test
  "Per Spec 005 §Transition resolution §Forbidden transitions + §Wildcard
  transitions.

  The forbidden-transition idiom — re-frame2's spelling of XState v5's
  `on: {LOGOUT: undefined}` / an SCXML targetless internal
  `<transition event=\"logout\"/>`: a child `:on` entry that MATCHES but is
  internal halts the deepest-wins leaf→root walk AT the child, and an
  internal transition leaves the configuration unchanged — so a parent's
  inherited transition for the event is NEVER reached. The child has opted
  OUT of the inherited transition.

  Two unified spellings of the matching internal no-op:
    - `{:on {:logout {}}}`   — explicit empty transition map (always blocked);
    - `{:on {:logout nil}}`  — a PRESENT key with a nil value; nil is the
      Clojure analogue of XState `undefined`, so it ALSO blocks.

  The whole idiom turns on PRESENCE: a child with NO `:logout` key still
  INHERITS the parent's (absence ≠ block). And a forbidden block is an
  ENABLED internal candidate, so — unlike a GUARD-BLOCKED candidate, which
  falls through to `:ns/*` / `:*` / the parent — it shadows
  every coarser descriptor AND every ancestor for that event.

  Exercised through `reg-machine` / `dispatch-sync` — the same runtime
  surface real apps use (mirrors machines-wildcard-fallthrough-cljs-test)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.machines.test-support :as rf.machines.test-support]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- seed-snapshot!
  "Force the snapshot for `machine-id` to a known value via a `reg-event`
  seed handler (returning `{:rf.db/runtime …}`) so a
  test can reposition the machine to a non-initial leaf without rebuilding
  the machine, and so the no-op is measured against an INSTALLED state (the
  snapshot is synthesised lazily on first dispatch)."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (namespace machine-id) "-" (name machine-id)))]
    ;; Machine snapshots are durable runtime-db state.
    (rf/reg-event seed-id
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/machines :snapshots machine-id] snap)}))
    (rf/dispatch-sync [seed-id])))

;; ---------------------------------------------------------------------------
;; (a) PRESENCE decides: a child `{:on {:logout {}}}` (the empty-map block)
;;     or `{:on {:logout nil}}` (a present nil, the XState `undefined`
;;     analogue) BLOCKS the parent's inherited :logout as an internal no-op,
;;     while a child with NO :logout key INHERITS it (absence ≠ block).
;; ---------------------------------------------------------------------------

(defn- logout-machine
  "The parent declares :logout; :modal's own :logout entry is `modal-logout`,
  and :dashboard declares none."
  [modal-logout]
  {:initial :authenticated
   :data    {}
   :states
   {:authenticated
    {:initial :dashboard
     :on      {:logout [:unauthenticated]}     ;; factored to the parent
     :states
     {:dashboard {:on {:open-modal :modal}}
      :modal     {:on {:logout modal-logout
                       :close  :dashboard}}}}
    :unauthenticated {}}})

(deftest child-logout-entry-presence-decides-the-inherited-transition
  (doseq [[label machine-id modal-logout resting-leaf expected]
          [["an empty-map child entry matched + halted the walk: parent :logout NOT inherited; state unchanged"
            :forbid/empty {} :modal [:authenticated :modal]]
           ["a present-nil child entry matched + halted the walk: parent :logout NOT inherited; state unchanged"
            :forbid/nil nil :modal [:authenticated :modal]]
           ["an absent child :logout key: the walk continues to the parent and the inherited :logout fires"
            :forbid/absent nil :dashboard [:unauthenticated]]]]
    (rf/reg-machine machine-id (logout-machine modal-logout))
    ;; Reposition into the leaf under test.
    (seed-snapshot! machine-id {:state [:authenticated resting-leaf] :data {}})
    (rf/dispatch-sync [machine-id [:logout]])
    (is (= expected (:state (snapshot machine-id))) label)))

;; ---------------------------------------------------------------------------
;; (d) forbidden block vs :* / :ns/* fallthrough — the headline semantic.
;;     A forbidden block (enabled internal candidate) does NOT fall to a
;;     same-level wildcard, NOR to a parent wildcard — it is a deliberate
;;     consume-here. This is the OPPOSITE of a GUARD-BLOCKED exact, which
;;     DOES fall through (machines_wildcard_fallthrough_cljs_test).
;; ---------------------------------------------------------------------------

(deftest forbidden-block-does-not-fall-through-to-wildcards
  (testing "a forbidden `{:on {:logout {}}}` does NOT fall to a same-level :* / parent :*"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :actions {:leaf-star   (tag :leaf-star)
                     :parent-star (tag :parent-star)
                     :ns-star     (tag :ns-star)}
           :states
           {:authenticated
            {:initial :modal
             :on      {:* {:action :parent-star}}        ;; parent :* present
             :states
             ;; leaf has a FORBIDDEN block on the exact key, AND a same-level
             ;; :ns/* and :* — none of which must fire, because the block is
             ;; an ENABLED candidate that wins the exact tier and halts.
             {:modal {:on {:auth/logout {}              ;; FORBIDDEN block (namespaced exact)
                           :auth/*      {:action :ns-star}
                           :*           {:action :leaf-star}}}}}}}]
      (rf/reg-machine :forbid/wild machine)
      (seed-snapshot! :forbid/wild {:state [:authenticated :modal] :data {}})
      (reset! log [])
      (rf/dispatch-sync [:forbid/wild [:auth/logout]])
      (is (empty? @log)
          "the forbidden exact block fired (a no-op) — NO wildcard at any tier/level ran")
      (is (= [:authenticated :modal] (:state (snapshot :forbid/wild)))
          "state unchanged — the block consumed the event"))))
