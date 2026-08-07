(ns re-frame.m11-recipe-reactive-owner-cljs-test
  "rf2-ynved — the PUBLISHED M-11 exceptional imperative-subscription Form-3
  must own its subscription per mount.

  WHAT SHIPPED, AND WHY IT WAS WRONG. The copy-pasteable recipe in
  `skills/re-frame-migration/references/guided-handlers-state.md` §M-11 acquired
  a subscription in `:component-did-mount`, seeded an imperative widget from a
  plain deref of it, and then observed it with `add-watch`. Under this adapter a
  subscription IS a bare `reagent.ratom/Reaction`, built deliberately WITHOUT
  `:auto-run`, and a Reaction learns its sources only through `deref-capture`. A
  deref taken in a lifecycle hook runs outside `*ratom-context*`, so it computes
  the body raw and leaves `watching` nil: the node is in nobody's watcher set and
  can never be told the value moved. The `add-watch` was therefore registered on
  a node that COULD NOT FIRE, and the recipe handed users a widget fed once at
  mount and deaf for the rest of its life — rf2-8cnxg's exact symptom, in
  consumer code, taught by us.

  THE REPAIR (operator ruling, rf2-ynved): a per-mount `r/track!` OWNER created
  in the same hook. Its eager first run is both the seed and the missing
  `deref-capture`; `r/dispose!` at unmount stops it before the cache slot is
  released. No facade export — `activate-derived-value!` stays internal, and this
  file proves the repair uses nothing a consumer does not already have: stock
  `reagent.core/track!` and `reagent.core/dispose!`.

  WHAT GIVES THIS FILE TEETH. `the-add-watch-shape-is-deaf-…` runs the PRE-FIX
  recipe against the same registered sub, the same frame and the same write as
  the repaired one, and shows it never moves. The two arms differ by exactly the
  tracker, so the repaired arm's green is not free: delete `(r/track! …)` from
  `recipe-mount!` and `the-repaired-recipe-feeds-…` fails on its first
  post-mount assertion. An assertion that the subscription merely EXISTS would
  have passed in both worlds, which is precisely how this defect stayed
  invisible for five occurrences.

  CLJS-only (Reagent is CLJS) and DOM-free: the claim is about the notification
  channel, not about a render. The `-cljs-test` suffix enrols it in the
  consolidated `:node-test` build. The mounted counterpart, where a real React
  class mounts and unmounts, is `re-frame.form-3-lifecycle-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private fid ::gauge-frame)
(def ^:private gauge-query [::gauge-value])

(defn- setup! []
  (rf/make-frame {:id fid :doc "M-11 recipe fixture frame"})
  (rf/reg-event ::seed (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (rf/reg-sub ::gauge-value (fn [db _] (:n db)))
  (rf/dispatch-sync [::seed 10] {:frame fid}))

(defn- write! [n]
  (rf/dispatch-sync [::seed n] {:frame fid}))

(defn- ref-count []
  (or (:ref-count (get @(:sub-cache (frame/frame fid)) gauge-query)) 0))

(defn- node-reaction []
  (:reaction (get @(:sub-cache (frame/frame fid)) gauge-query)))

;; White-box, and only ever CORROBORATING: `watching` is the Reagent field that
;; says "this reaction is subscribed to its sources". Every arm that consults it
;; also asserts the behaviour it is supposed to explain.
(defn- capturing? [rx]
  (some? (.-watching rx)))

;; ---------------------------------------------------------------------------
;; The two shapes, lifted out of the recipe with React removed. Both take the
;; handle `(rf/capture-frame fid)` returns from the `reg-view*` outer callable,
;; and both acquire through its captured `subscribe` — that half was never in
;; question (Rule 5b of the migration drift gate already pins it). They differ
;; ONLY in what observes the acquired reaction.
;; ---------------------------------------------------------------------------

(defn- recipe-mount!
  "The REPAIRED `:component-did-mount`: acquire through the captured `subscribe`,
  then own the reaction with a per-mount `r/track!`. The tracker's eager first
  run seeds the widget AND supplies the `deref-capture` that puts the shared
  reaction on the push path."
  [handle feed!]
  (let [{:keys [subscribe]} handle
        reaction (subscribe gauge-query)]
    {:reaction reaction
     :driver   (r/track! (fn [] (feed! @reaction)))}))

(defn- recipe-unmount!
  "The REPAIRED `:component-will-unmount`: stop the owner FIRST, then release the
  cache slot frame-first."
  [{:keys [driver]}]
  (some-> driver r/dispose!)
  (rf/unsubscribe fid gauge-query))

(defn- add-watch-mount!
  "The PRE-FIX `:component-did-mount` exactly as the recipe shipped it: acquire,
  seed from a plain deref, observe with a per-mount `add-watch` key."
  [handle feed!]
  (let [{:keys [subscribe]} handle
        reaction  (subscribe gauge-query)
        watch-key (gensym "gauge-feed-")]
    (feed! @reaction)                                  ; plain deref — no context
    (add-watch reaction watch-key (fn [_ _ _ v] (feed! v)))
    {:reaction reaction :watch-key watch-key}))

(defn- add-watch-unmount! [{:keys [reaction watch-key]}]
  (remove-watch reaction watch-key)
  (rf/unsubscribe fid gauge-query))

(defn- recorder []
  (let [log (atom [])]
    [log (fn [v] (swap! log conj v))]))

;; ===========================================================================
;; The defect — the shape the recipe published
;; ===========================================================================

(deftest the-add-watch-shape-is-deaf-after-mount
  (testing "the PRE-FIX recipe — acquire, seed from a plain lifecycle deref,
            observe with add-watch — leaves the cached reaction capturing
            NOTHING, so a real app-db write reaches the widget never. This is
            rf2-8cnxg's symptom in the shape we shipped to consumers, and it is
            the non-vacuity arm for the repaired test below: same sub, same
            frame, same write, no tracker (rf2-ynved)"
    (setup!)
    (let [handle (rf/capture-frame fid)
          [log feed!] (recorder)
          mounted (add-watch-mount! handle feed!)]
      (try
        (is (= [10] @log) "the plain deref seeded the widget once")
        (is (not (capturing? (node-reaction)))
            "and left the reaction watching nothing — `add-watch` was registered
             on a node that is in no watcher set and cannot be notified")

        (write! 20)
        (r/flush)
        (ratom/flush!)
        (is (= [10] @log)
            "a real app-db write moved nothing — not on dispatch, and not on a
             Reagent flush either. The widget is deaf")

        (write! 30)
        (r/flush)
        (ratom/flush!)
        (is (= [10] @log)
            "and stays deaf for every later write — fed once at mount, forever")
        (finally
          (add-watch-unmount! mounted))))))

;; ===========================================================================
;; The repair — the shape the recipe now publishes
;; ===========================================================================

(deftest the-repaired-recipe-feeds-its-widget-on-every-commit
  (testing "the per-mount `r/track!` owner seeds the widget on its eager first
            run and re-feeds it on every later commit. Delete the tracker from
            `recipe-mount!` and the first post-mount assertion here fails —
            that is what makes this test about liveness and not about the
            subscription merely existing (rf2-ynved)"
    (setup!)
    (let [handle (rf/capture-frame fid)
          [log feed!] (recorder)
          mounted (recipe-mount! handle feed!)]
      (try
        (is (= [10] @log)
            "track!'s EAGER first run is the seed — there is no separate plain
             deref to forget to activate")
        (is (capturing? (node-reaction))
            "…and that same first run supplied the deref-capture: the reaction
             is now subscribed to its sources")

        (write! 20)
        (is (= [10 20] @log)
            "the widget saw the moved value. It arrived on a Reagent FLUSH, not
             from an inline callback inside the app-db write: the owner is
             queued like any other reaction, and this adapter's commit path
             drains that queue itself (`:flush-render!` is `(f) (r/flush)`)")
        (r/flush)
        (is (= [10 20] @log)
            "…and exactly once per moved commit — a second flush re-runs nothing")

        (write! 30)
        (r/flush)
        (is (= [10 20 30] @log) "and every later commit feeds it too")

        (write! 30)
        (r/flush)
        (is (= [10 20 30] @log)
            "a write that does not move the observed value feeds nothing — the
             owner did not make the channel chatty")
        (finally
          (recipe-unmount! mounted)))

      (testing "…and unmount stops it: the disposed owner takes no further feed
                and the cache slot returns to its pre-mount baseline"
        (write! 40)
        (r/flush)
        (is (= [10 20 30] @log) "no feed after the owner was disposed")
        (is (zero? (ref-count)) "the acquire was released frame-first")))))

(deftest two-mounts-of-one-query-need-no-watch-keys
  (testing "equal (frame, query-v) subscriptions share ONE cached reaction, but
            each mount owns its own tracker — so two widgets stay independent
            with no watch key anywhere: both see an update, disposing one leaves
            the other live, and the ref-count balances back to zero. The pre-fix
            recipe needed a per-mount gensym watch key to get this far; the
            repair deletes the whole hazard rather than documenting it
            (rf2-ynved)"
    (setup!)
    (let [handle (rf/capture-frame fid)
          [log-a feed-a!] (recorder)
          [log-b feed-b!] (recorder)
          mount-a (recipe-mount! handle feed-a!)
          mount-b (recipe-mount! handle feed-b!)]
      (is (= (:reaction mount-a) (:reaction mount-b))
          "precondition — one shared cached reaction, so the arm is not vacuous")
      (is (= 2 (ref-count)) "two mounts, two holders of the shared slot")
      (is (= [10] @log-a))
      (is (= [10] @log-b) "both mounts seeded from the shared reaction")

      (write! 20)
      (r/flush)
      (is (= [10 20] @log-a))
      (is (= [10 20] @log-b) "one change feeds BOTH mounts")

      (recipe-unmount! mount-a)
      (is (= 1 (ref-count)) "unmounting one releases exactly one holder")

      (write! 30)
      (r/flush)
      (is (= [10 20] @log-a) "the unmounted mount's owner is gone — it sees nothing")
      (is (= [10 20 30] @log-b)
          "and the survivor keeps updating: disposing a sibling's tracker cannot
           strip this mount's observation, because there is no shared callback
           registry to strip from")

      (recipe-unmount! mount-b)
      (is (zero? (ref-count)) "both releases land — the slot is back to baseline")

      (write! 40)
      (r/flush)
      (is (= [10 20] @log-a))
      (is (= [10 20 30] @log-b) "neither widget is fed after its unmount"))))
