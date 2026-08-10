(ns re-frame.hicasso.first-registration-cljs-test
  "THE OTHER REGISTRY TRANSITION — an id that had **no** handler and got
  one (rf2-wjag).

  The registry axis of `impl.generation/commit-basis` has two transitions,
  and they reach this arm by two different routes because they are two
  different events.

  A **replacement** — an id that already had a handler and got another —
  arrives as a disposal: the sub-cache evicts the query's entry and
  disposes its reaction, and `impl.collector/invalidate-cell!` rides that
  event. `hmr_registry_cljs_test` witnesses that route in full, from the
  synchronous drop to the microtask rewire, under the name a developer
  knows it by: a save. **This file does not restate any of it.**

  A **first** registration arrives as nothing at all.
  `registrar/add-replacement-hook!` fires only when a previous handler
  existed, so a first `reg-sub` for a query evicts nothing and disposes
  nothing, and an arm listening for disposals alone would hear about it by
  no route whatsoever.

  ## Why a boundary can hold the miss

  That would matter to nobody if a boundary could not hold an unregistered
  query, and the substrate is careful that it should not: a subscribe to
  an unregistered query emits `:rf.error/no-such-sub`, recovers to a
  nil-yielding reaction, and **deliberately does not cache it**, precisely
  so that a later registration is observed by the next `subscribe`.

  This arm has exactly one property that breaks that assumption. A cell
  holds its reaction for the life of every boundary reading the key, and
  never subscribes again — so the recovery the substrate declined to cache
  is cached anyway, in a cell, where nothing evicts it. Measured before
  the repair existed: the boundary painted nil for the life of the mount,
  the first `reg-sub` for the query changed nothing, and no later write
  ever notified it — on a query that was by then perfectly well
  registered. That is the shape a lazily loaded module hits, and it is
  the shape of a page that renders correctly, errors nowhere, and is
  simply frozen.

  ## What is asserted, and what already is elsewhere

  The transition has two halves. A boundary inside the render→commit gap
  holds no cell, so the registration scan reaches nothing on its behalf;
  it is repaired by the `registry-epoch` term of `commit-basis`, and
  `hmr_registry_cljs_test`'s `one-save-is-invisible-to-a-mounted-boundary-and-visible-to-an-in-flight-one`
  is that half's witness. The bill that pairs with it — a first
  registration of an id no cell holds must disturb a mounted boundary by
  nothing at all — is that file's
  `an-unrelated-namespaces-save-disturbs-no-mounted-boundary`.

  **The HELD half is what has never been asserted**, in either direction:
  that a cell holding an unregistered id's recovery is found by the scan,
  invalidated, rewired against the real registration, and notified by
  writes thereafter. That is this file, and it is one row plus the control
  that makes the row attributable."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::first-registration)

;; `:firstreg/late` is REGISTERED BY THE ROW, not by this file: an id that
;; already has a handler at mount time is the replacement case, which is
;; the case this file exists not to be.
(rf/reg-event :firstreg/seed (fn [_ [_ db]] {:db db}))
(rf/reg-event :firstreg/bump (fn [{:keys [db]} _] {:db (update db :late inc)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(def ^:private late-key [frame-id [:firstreg/late]])

(defn- seeded!
  []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:firstreg/seed {:late 5}]))
  frame-id)

(defn- read-late!
  "One body run reading the late-registered key, and what it saw."
  []
  (let [!seen (volatile! ::unread)]
    (collector/render-body frame-id
                           (fn [_] (vreset! !seen (collector/sub [:firstreg/late])) [:p])
                           {})
    @!seen))

(defn- mount-late!
  "React's place at the commit seam, for a boundary whose body reads the
  late-registered key."
  []
  (let [!seen   (volatile! ::unread)
        _       (collector/render-body
                  frame-id
                  (fn [_] (vreset! !seen (collector/sub [:firstreg/late])) [:p])
                  {})
        entry    (collector/last-reads)
        !notified (volatile! 0)
        release  (collector/commit-boundary! entry (fn [] (vswap! !notified inc)))]
    {:value @!seen :entry entry :notified !notified :release release}))

(deftest a-cell-holding-an-unregistered-ids-recovery-is-repaired-by-the-first-registration
  (async done
    (seeded!)
    (let [{:keys [value notified release]} (mount-late!)]

      (testing "the premise, and it is the whole defect. The boundary
                committed against a query nobody had registered, so the
                cell holds the substrate's nil-recovery — the very thing
                the substrate declined to cache, cached anyway, where
                nothing evicts it"
        (is (nil? value))
        (is (some? (inventory/cell-reaction late-key))))

      ;; NEGATIVE CONTROL, taken FIRST, so the drop measured below is
      ;; attributable to registering THIS id rather than to the mere fact
      ;; that some registration happened. The scan is narrowed to cells
      ;; holding the id being registered, and this is the assertion that
      ;; makes that narrowing a measured property.
      (rf/reg-sub :firstreg/unrelated (fn [db _] (:late db)))
      (is (some? (inventory/cell-reaction late-key))
          "an unrelated first registration leaves this cell's reaction in place")

      (let [notified-before @notified]

        ;; THE FIRST REGISTRATION. Nothing is evicted, nothing is disposed,
        ;; and the registrar's replacement hook does not fire — so every
        ;; assertion below is reached by the registration hook alone.
        (rf/reg-sub :firstreg/late (fn [db _] (:late db)))

        (testing "synchronously the held recovery is dropped — the repair's
                  first phase, which is all a correct READ needs"
          (is (nil? (inventory/cell-reaction late-key))))

        (testing "and a body run inside the window already answers with the
                  real handler, because a cell with no reaction takes the
                  cold probe and the probe resolves the registration that
                  is live now"
          (is (= 5 (read-late!))))

        (support/at-the-checkpoint
          #(some? (inventory/cell-reaction late-key))
          "the first-registration repair"
          done
          (fn [_turns]
            (testing "at the microtask checkpoint the cell is wired against
                      the real registration, and the boundary has been told
                      to correct itself — it painted nil, and a correction
                      that arrived in a later task could arrive after the
                      paint"
              (is (some? (inventory/cell-reaction late-key)))
              (is (> @notified notified-before)))

            (testing "and the property the repair exists for: a LATER write
                      reaches it. This is the assertion the pre-repair
                      runtime failed — the cell was deaf for the life of
                      the mount, so the page rendered, painted, errored
                      nowhere, and never moved again"
              (let [before @notified]
                (collector/dispatch! frame-id [:firstreg/bump])
                (is (= (inc before) @notified))))

            (testing "reading through the repaired cell answers the moved
                      value, so the rewire attached to the live handler and
                      not to a second recovery"
              (is (= 6 (read-late!))))

            (release)))))))
