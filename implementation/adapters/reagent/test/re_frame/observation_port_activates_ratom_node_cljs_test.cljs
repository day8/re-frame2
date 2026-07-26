(ns re-frame.observation-port-activates-ratom-node-cljs-test
  "rf2-8cnxg — the observation port puts a ratom-family subscription node on
  the substrate's PUSH path itself, with NO eager consumer standing behind it.

  THE DEFECT THIS PINS. `build-node-handle!` used to install its per-handle
  `add-watch` on the stated assumption that \"a watched reaction is live on
  the reactive hosts\". On the ratom family that is false, and silently so: a
  `reagent.ratom/Reaction` learns its sources ONLY through `deref-capture`,
  and a plain `deref` taken outside `*ratom-context*` with no `auto-run`
  takes the non-reactive branch — it runs the body raw and leaves `watching`
  nil. `_handle-change` is then never called, `_queued-run` short-circuits on
  `(some? watching)`, and even `reagent.core/flush` moves nothing. The port
  held a watch on a node that COULD NOT FIRE, so every ViewCell over a
  Reagent-hosted subscription rendered once and never again.

  It went undetected because a Reagent COMPONENT supplies the capture as a
  side effect of rendering, and because the two suites that exercise this
  channel — `re-frame.observation-port-watchable-host-cljs-test` and its
  mounted `-dom-` sibling — each stood a `ratom/run!` DRIVER, an auto-running
  reaction that derefs the sub, to \"keep the lazy reaction on the push
  path\". That driver was not a test convenience: it was the missing runtime
  step, hand-supplied. Those files no longer stand one, and this file exists
  so the fact has a home of its own where the driver was never present to
  begin with.

  WHAT EACH ARM IS FOR. The first proves the port alone drives the channel.
  The rest are the adversarial half, because \"make it notify\" has two cheap
  wrong answers that a movement-only test would rate green:

    - `:auto-run true` on `make-derived-value`, which fixes notification by
      recomputing EVERY subscription under this substrate synchronously
      inside the app-db `reset!`. `an-unobserved-subscription-is-never-…`
      counts compute-fn invocations to reject it: a node no handle observes
      must not recompute at all.
    - Activating on every acquire regardless of state, which re-runs a live
      node for nothing and can fan a phantom note at its existing owners.
      `activation-is-idempotent-…` pins one note per handle per movement
      across two owners of one node.

  Plus the totality arms: the op must be silent on a BASE container and on
  objects that are not this substrate's at all, since a ratom-installed app
  can hold a spine-produced derived value inherited through a cross-substrate
  test bundle (rf2-jicu2).

  CLJS-only (Reagent is CLJS); the `-cljs-test` suffix enrols it in the
  consolidated `:node-test` build — no DOM and no React needed, because the
  claim is about the notification channel, not about a render. The mounted
  counterpart, where a real Freehand ViewCell repaints under this adapter,
  is `re-frame.freehand-cell-under-ratom-adapter-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(def ^:private fid :rf/default)

(defn- node-reaction [query]
  (:reaction (get @(:sub-cache (frame/frame fid)) query)))

;; White-box, and deliberately only ever a COROBORATING assertion: `watching`
;; is the Reagent field that says "this reaction is subscribed to its
;; sources". Read directly it would go vacuously nil against any object that
;; is not a Reaction, so every arm that consults it also asserts the
;; behaviour it is supposed to explain.
(defn- capturing? [rx]
  (some? (.-watching rx)))

(def ^:private computes (atom 0))

(defn- register! []
  (reset! computes 0)
  (rf/reg-sub :obs/n (fn [db _] (:n db)))
  (rf/reg-sub :obs/counted (fn [db _] (swap! computes inc) (:n db)))
  (rf/reg-event :obs/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
  (rf/reg-event :obs/set-other (fn [{:keys [db]} [_ v]] {:db (assoc db :other v)})))

(defn- target [query]
  (obs/resolve-target {:frame fid :query-v query}))

(defn- acquire-recording! [query]
  (let [notes  (atom [])
        handle (obs/acquire! (target query) (fn [ev] (swap! notes conj ev)))]
    [handle notes]))

;; ===========================================================================
;; the port alone drives the channel
;; ===========================================================================

(deftest acquire-alone-puts-a-ratom-node-on-the-push-path
  (testing "a bare acquire! — no driver, no component, nothing deref'ing the
            sub inside a reactive context — leaves the Reagent node CAPTURING
            its sources, so a later movement reaches the handle's on-change"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [[handle notes] (acquire-recording! [:obs/n])
          rx             (node-reaction [:obs/n])]
      (try
        (is (obs/handle? handle))
        (is (satisfies? IWatchable rx)
            "precondition — the node IS watchable, so a silent channel here
             would be the port's fault and not the host's")
        (is (capturing? rx)
            "acquire! ACTIVATED the node: it is subscribed to its sources.
             Before rf2-8cnxg this was nil — watchable, watched, and unable
             to notify")
        (is (empty? @notes)
            "activation itself fans nothing at the acquiring handle — it runs
             BEFORE the watch is installed, so there is no priming note")

        (rf/dispatch-sync [:obs/set-n 2])
        (ratom/flush!)

        (is (= 1 (count @notes))
            "the movement reached the handle EXACTLY once — the repaint
             channel a ViewCell rides")
        (let [ev (first @notes)]
          (is (= :subscription (:cause ev)))
          (is (= (target [:obs/n]) (:target ev))))
        (is (= 2 (:value (obs/read handle)))
            "and the port reads the moved value back off the node")
        (finally
          (obs/release! handle))))))

;; ===========================================================================
;; the adversarial half — the cheap wrong answers
;; ===========================================================================

(deftest an-unobserved-subscription-is-never-made-eager
  (testing "activation is per-ACQUIRE, not global. A subscription no handle
            observes must not recompute when app-db moves — which is what
            `:auto-run true` on make-derived-value (the one-line 'fix') would
            do to every subscription in the process, synchronously inside the
            app-db reset!"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [sub (rf/subscribe [:obs/counted])
          rx  (node-reaction [:obs/counted])]
      (is (some? sub) "the sub-cache node exists — the arm is not vacuous")
      (is (not (capturing? rx))
          "subscribe alone did NOT put it on the push path")
      (let [before @computes]
        (rf/dispatch-sync [:obs/set-n 2])
        (ratom/flush!)
        (is (= before @computes)
            "an UNOBSERVED subscription did not recompute on an app-db write"))

      (testing "…and the same node, once acquired, does recompute — proving
                the counter is a live instrument and not a stuck zero"
        (let [[handle _] (acquire-recording! [:obs/counted])
              after-acq  @computes]
          (try
            (is (capturing? rx) "the acquire activated THIS node")
            (is (pos? after-acq) "activation ran the body once, to capture")
            (rf/dispatch-sync [:obs/set-n 3])
            (ratom/flush!)
            (is (> @computes after-acq)
                "and now a movement DOES recompute it")
            (finally
              (obs/release! handle))))))))

(deftest activation-is-idempotent-across-two-owners-of-one-node
  (testing "the second ViewCell to acquire the same cached node must not
            re-run a node that is already capturing: no phantom note at the
            first owner, and one note per handle per movement"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [[h1 notes1] (acquire-recording! [:obs/n])]
      (try
        (is (capturing? (node-reaction [:obs/n])))
        (let [[h2 notes2] (acquire-recording! [:obs/n])]
          (try
            (is (empty? @notes1)
                "the SECOND acquire fanned nothing at the first owner —
                 re-running a live node would have")
            (is (empty? @notes2))
            (rf/dispatch-sync [:obs/set-n 2])
            (ratom/flush!)
            (is (= 1 (count @notes1)) "one note for owner 1")
            (is (= 1 (count @notes2)) "one note for owner 2 — not two, not none")
            (finally
              (obs/release! h2))))
        (finally
          (obs/release! h1))))))

(deftest a-no-movement-write-stays-silent-on-an-activated-node
  (testing "activation must not make the channel chatty: a write that leaves
            the observed value where it was fans nothing, and a write to a key
            the sub does not read fans nothing either"
    (register!)
    (rf/dispatch-sync [:obs/set-n 7])
    (let [[handle notes] (acquire-recording! [:obs/n])]
      (try
        (rf/dispatch-sync [:obs/set-n 7])
        (ratom/flush!)
        (is (empty? @notes)
            "an equal re-write moved nothing, so nothing was fanned")

        (rf/dispatch-sync [:obs/set-other :anything])
        (ratom/flush!)
        (is (empty? @notes)
            "a write to an unrelated app-db key moved nothing this sub reads")

        (testing "positive control — the channel really is armed"
          (rf/dispatch-sync [:obs/set-n 8])
          (ratom/flush!)
          (is (= 1 (count @notes))
              "so the two silences above are silences, not a dead channel"))
        (finally
          (obs/release! handle))))))

(deftest a-released-handle-hears-nothing-while-the-node-stays-live
  (testing "releasing one owner removes ITS watch without taking the node off
            the push path for the owner that remains"
    (register!)
    (rf/dispatch-sync [:obs/set-n 1])
    (let [[h1 notes1] (acquire-recording! [:obs/n])
          [h2 notes2] (acquire-recording! [:obs/n])]
      (try
        (obs/release! h1)
        (rf/dispatch-sync [:obs/set-n 2])
        (ratom/flush!)
        (is (empty? @notes1) "the released handle heard nothing")
        (is (= 1 (count @notes2))
            "the surviving owner still hears the movement — release did not
             disarm the node")
        (finally
          (obs/release! h2))))))

;; ===========================================================================
;; totality — the op is silent on everything that is not one of ITS reactions
;; ===========================================================================

(deftest activate-derived-value-is-a-total-no-op-off-its-own-derived-values
  (testing "a ratom-installed app can be handed a BASE container, a
            spine-produced derived value from a cross-substrate bundle
            (rf2-jicu2), or plain junk. None of those is this substrate's
            reaction, and the op must return quietly rather than throw or
            mutate"
    (let [base (r/atom {:untouched true})]
      (is (nil? (interop/activate-derived-value! base))
          "a base r/atom is push-based already — nothing to do")
      (is (= {:untouched true} @base)
          "…and it was not disposed, reset, or otherwise disturbed")
      (is (nil? (interop/activate-derived-value! nil)))
      (is (nil? (interop/activate-derived-value! #js {:not "a reaction"})))
      (is (nil? (interop/activate-derived-value! (reify IDeref (-deref [_] 1))))
          "a bare IDeref test double — the shape the plain-atom port suites
           use — is left alone"))))

(deftest activate-derived-value-runs-a-reagent-reaction-exactly-once
  (testing "the op's own contract, read off a raw Reagent reaction with no
            re-frame around it: one capture run, and the second call is a
            no-op because the reaction is already capturing"
    (let [source (r/atom 1)
          runs   (atom 0)
          rx     (ratom/make-reaction (fn [] (swap! runs inc) @source))]
      (try
        (is (not (capturing? rx)) "a fresh reaction captures nothing")
        (is (zero? @runs) "…and has not run")
        (interop/activate-derived-value! rx)
        (is (capturing? rx) "activation captured the source")
        (is (= 1 @runs) "exactly one run")
        (interop/activate-derived-value! rx)
        (is (= 1 @runs) "the second activation ran nothing — idempotent")

        (let [heard (atom [])]
          (add-watch rx ::probe (fn [_ _ old nu] (swap! heard conj [old nu])))
          (reset! source 2)
          (ratom/flush!)
          (is (= [[1 2]] @heard)
              "and the activated reaction NOTIFIES on a source move — the whole
               point, and the thing a plain deref could never buy"))
        (finally
          (remove-watch rx ::probe)
          (ratom/dispose! rx))))))
