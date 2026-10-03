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
;; (b) the blocking child INTERNAL transition runs its :action then halts
;;     — for both the empty-map-with-action and... the nil form takes no
;;     action (nil carries none), so :action coverage rides the map form.
;; ---------------------------------------------------------------------------

(deftest forbidden-block-runs-its-action-then-halts
  (testing "a child block carrying an :action runs the action AND blocks the parent (internal, state unchanged)"
    (let [log (atom [])
          machine
          {:initial :authenticated
           :data    {}
           :actions {:warn   (fn [_] (swap! log conj :warn) {})
                     :logout-action (fn [_] (swap! log conj :parent-logout) {})}
           :states
           {:authenticated
            {:initial :dashboard
             ;; parent's :logout carries an action too, so we can prove it
             ;; did NOT run (the child block shadowed it).
             :on      {:logout {:target [:unauthenticated] :action :logout-action}}
             :states
             {:dashboard {:on {:open-modal :modal}}
              :modal     {:on {:logout {:action :warn}   ;; block + action, no :target
                               :close  :dashboard}}}}
            :unauthenticated {}}}]
      (rf/reg-machine :forbid/action machine)
      (seed-snapshot! :forbid/action {:state [:authenticated :modal] :data {}})
      (reset! log [])
      (rf/dispatch-sync [:forbid/action [:logout]])
      (is (= [:warn] @log)
          "the child block's :action ran; the parent's :logout action did NOT")
      (is (= [:authenticated :modal] (:state (snapshot :forbid/action)))
          "internal transition — state unchanged; parent :logout still blocked"))))

;; ---------------------------------------------------------------------------
;; (c) compound coverage — the block lives on a deeper leaf and shadows a
;;     transition factored TWO levels up.
;; ---------------------------------------------------------------------------

(deftest forbidden-block-shadows-grandparent-inherited-transition
  (testing "a deep leaf's `{:on {:logout nil}}` blocks a :logout factored two levels up"
    (let [machine
          {:initial :app
           :data    {}
           :states
           {:app
            {:initial :authenticated
             :on      {:logout [:bye]}                 ;; factored to the ROOT-child :app
             :states
             {:authenticated
              {:initial :checkout
               :states
               ;; :checkout is two levels below the :logout declaration; it
               ;; blocks with a present nil.
               {:checkout {:on {:logout nil :cancel :browsing}}
                :browsing {:on {:checkout :checkout}}}}}}
            :bye {}}}]
      (rf/reg-machine :forbid/compound machine)
      (seed-snapshot! :forbid/compound
                      {:state [:app :authenticated :checkout] :data {}})
      (rf/dispatch-sync [:forbid/compound [:logout]])
      (is (= [:app :authenticated :checkout] (:state (snapshot :forbid/compound)))
          "deep-leaf nil block halted the walk — the :logout factored two levels up was NOT inherited")
      ;; And from a sibling leaf WITHOUT the block, :logout is inherited.
      (seed-snapshot! :forbid/compound
                      {:state [:app :authenticated :browsing] :data {}})
      (rf/dispatch-sync [:forbid/compound [:logout]])
      (is (= [:bye] (:state (snapshot :forbid/compound)))
          ":browsing has no :logout key → walk continues to :app → inherited :logout fired"))))

;; ---------------------------------------------------------------------------
;; (d) forbidden block vs :* / :ns/* fallthrough — the headline semantic.
;;     A forbidden block (enabled internal candidate) does NOT fall to a
;;     same-level wildcard, NOR to a parent wildcard — it is a deliberate
;;     consume-here. This is the OPPOSITE of a GUARD-BLOCKED exact, which
;;     DOES fall through. Test both halves so the distinction is
;;     pinned.
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

(deftest forbidden-block-contrasts-with-guard-blocked-fallthrough
  (testing "CONTRAST: a GUARD-BLOCKED exact DOES fall through to :* — proving the block is the difference"
    (let [log (atom [])
          tag (fn [k] (fn [_] (swap! log conj k) {}))
          machine
          {:initial :authenticated
           :data    {}
           :guards  {:never (fn [_] false)}
           :actions {:leaf-star (tag :leaf-star)
                     :blocked   (tag :blocked)}
           :states
           {:authenticated
            {:initial :modal
             :states
             ;; SAME shape as above but the exact entry is GUARD-BLOCKED
             ;; (not a forbidden block) — it must fall through to the
             ;; same-level :*, firing :leaf-star.
             {:modal {:on {:auth/logout {:guard :never :action :blocked}
                           :*           {:action :leaf-star}}}}}}}]
      (rf/reg-machine :forbid/guard-contrast machine)
      (seed-snapshot! :forbid/guard-contrast {:state [:authenticated :modal] :data {}})
      (reset! log [])
      (rf/dispatch-sync [:forbid/guard-contrast [:auth/logout]])
      (is (= [:leaf-star] @log)
          "guard-blocked exact is NOT enabled → falls through to the same-level :*;
           the guard-blocked action did not run"))))
