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
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

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
;; (a) `{:on {:logout {}}}` on a child BLOCKS the parent's inherited :logout
;;     (the existing empty-map block — internal no-op, state unchanged)
;; ---------------------------------------------------------------------------

(deftest empty-map-child-entry-blocks-parent-inherited-transition
  (testing "child `{:on {:logout {}}}` blocks the parent's inherited :logout (no-op)"
    (let [machine
          {:initial :authenticated
           :data    {}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:logout [:unauthenticated]}     ;; factored to the parent
             :states
             ;; :modal opts OUT of the inherited :logout with an empty map.
             {:dashboard {:on {:open-modal :modal}}
              :modal     {:on {:logout {}              ;; FORBIDDEN — empty map
                               :close  :dashboard}}}}
            :unauthenticated {}}}]
      (rf/reg-machine :forbid/empty machine)
      ;; Reposition into :modal where the block lives.
      (seed-snapshot! :forbid/empty {:state [:authenticated :modal] :data {}})
      (rf/dispatch-sync [:forbid/empty [:logout]])
      (is (= [:authenticated :modal] (:state (snapshot :forbid/empty)))
          "the empty-map child entry matched + halted the walk — parent :logout NOT inherited; state unchanged"))))

;; ---------------------------------------------------------------------------
;; (b) `{:on {:logout nil}}` ALSO blocks (a present nil is the XState
;;     `undefined` analogue)
;; ---------------------------------------------------------------------------

(deftest nil-child-entry-also-blocks-parent-inherited-transition
  (testing "child `{:on {:logout nil}}` ALSO blocks (rf2-16gxd nil-blocks — was a fallthrough)"
    (let [machine
          {:initial :authenticated
           :data    {}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:logout [:unauthenticated]}
             :states
             ;; :modal opts OUT with a PRESENT nil value — the XState
             ;; `LOGOUT: undefined` analogue. A present nil blocks the
             ;; parent's :logout (it does not fall through).
             {:dashboard {:on {:open-modal :modal}}
              :modal     {:on {:logout nil             ;; FORBIDDEN — present nil
                               :close  :dashboard}}}}
            :unauthenticated {}}}]
      (rf/reg-machine :forbid/nil machine)
      (seed-snapshot! :forbid/nil {:state [:authenticated :modal] :data {}})
      (rf/dispatch-sync [:forbid/nil [:logout]])
      (is (= [:authenticated :modal] (:state (snapshot :forbid/nil)))
          "the present-nil child entry matched + halted the walk — parent :logout NOT inherited; state unchanged"))))

;; ---------------------------------------------------------------------------
;; (c) a child with NO :logout entry STILL INHERITS the parent's (regression)
;;     — absence ≠ block. This is the case the present-nil must NOT regress.
;; ---------------------------------------------------------------------------

(deftest absent-child-entry-still-inherits-parent-transition
  (testing "child with NO :logout key inherits the parent's :logout (absence ≠ block)"
    (let [machine
          {:initial :authenticated
           :data    {}
           :states
           {:authenticated
            {:initial :dashboard
             :on      {:logout [:unauthenticated]}
             :states
             ;; :dashboard declares NO :logout — it must inherit the parent's.
             {:dashboard {:on {:open-modal :modal}}
              :modal     {:on {:logout nil :close :dashboard}}}}
            :unauthenticated {}}}]
      (rf/reg-machine :forbid/absent machine)
      ;; Rest at :dashboard (no :logout key) and dispatch :logout.
      (seed-snapshot! :forbid/absent {:state [:authenticated :dashboard] :data {}})
      (rf/dispatch-sync [:forbid/absent [:logout]])
      (is (= [:unauthenticated] (:state (snapshot :forbid/absent)))
          "absent child :logout key → walk continues to the parent → inherited :logout fired"))))

;; ---------------------------------------------------------------------------
;; (d) the blocking child INTERNAL transition runs its :action then halts
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
;; (e) compound coverage — the block lives on a deeper leaf and shadows a
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
;; (f) forbidden block vs :* / :ns/* fallthrough — the headline semantic.
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
  (testing "CONTRAST: a GUARD-BLOCKED exact DOES fall through to :* (rf2-icj9t) — proving the block is the difference"
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
          "guard-blocked exact is NOT enabled → falls through to the same-level :* (rf2-icj9t)")
      (is (not (some #{:blocked} @log))
          "the guard-blocked action did not run"))))
