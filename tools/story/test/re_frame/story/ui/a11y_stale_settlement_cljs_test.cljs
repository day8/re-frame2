(ns re-frame.story.ui.a11y-stale-settlement-cljs-test
  "rf2-2amkm — STALE SETTLEMENT of an a11y scan, in both axe-core panels.

  `run-axe!` is asynchronous twice over: it awaits the CDN load, then
  awaits the scan. Across either gap the surface it is scanning can stop
  being its own — the variant frame torn down (`drop-frame-state!`), the
  panel reset (`reset-state!`), or a newer `run-axe!` claiming the same
  slot. Every mutation in the `.then` / `.catch` callbacks used to run
  unconditionally, so a settlement landed on whatever occupied the slot
  by then.

  WHAT THAT COSTS, and why it is not a crash. The mutation does not
  throw under teardown: `(swap! run-state assoc frame-id :done)` over a
  map the frame was dissoc'd from RESURRECTS the entry rather than
  failing — the a11y analogue of the `(inc nil)` resurrection rf2-6pfpt
  measured on the play-runner's `record-result!` path (#6405). The
  phantom slot reads `:done`, and the resurrected `violations-by-frame`
  entry beside it is what the below-the-UI executor seam
  (`browser/register-a11y-reader!`) serves to `:rf.assert/a11y`. A scan
  of a frame that is GONE reporting a clean verdict: a fabricated green,
  not a hang and not an exception. `no-throw-and-no-resurrection` below
  asserts BOTH halves — that it does not throw is exactly why the defect
  was invisible.

  DETERMINISM. Every test here places its own interleaving. The fake
  axe-core's `run` is called synchronously by the code under test, so a
  test can perform the takeover INSIDE the scan — 'the frame was torn
  down while the scan was in flight' is positioned exactly, never raced.
  Assertions are chained off the promise `run-axe!` returns (the tail of
  the whole callback chain) and, where a second run is involved, off a
  signal the second run's scan fires — so nothing depends on counting
  microtask turns.

  Pure `.cljs`: the panels are CLJS-only, and the `async` tests below
  need cljs.test MAP fixtures, which a `.cljc` may not use
  (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.ui.a11y :as a11y]
            [re-frame.story.ui.chrome-a11y :as chrome-a11y]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------
;;
;; `ensure-axe-loaded!` resolves immediately when `js/window.axe` is
;; already present, so installing a fake axe there drives the REAL
;; `run-axe!` end to end — the CDN branch is never reached and no
;; `with-redefs` routes around the code under test. The node test runtime
;; has no `window`; the fixture installs one and restores whatever was
;; there, because this process runs every other CLJS namespace too and a
;; leaked `window` would flip browser-detection branches elsewhere.

(def ^:private saved-window (atom nil))

(defn- install-axe!
  "Point the fake axe-core's `run` at `run-fn`, which receives the scan
  context and returns the results promise. Re-installable mid-flight so
  a test can give a second run different scan behaviour."
  [run-fn]
  (gobj/set (gobj/get js/globalThis "window") "axe"
            #js {"run" (fn [ctx] (run-fn ctx))})
  nil)

(defn- setup! []
  (reset! saved-window (gobj/get js/globalThis "window"))
  (gobj/set js/globalThis "window" #js {})
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter) (catch :default _ nil))
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (a11y/reset-state!)
  (chrome-a11y/reset-state!)
  ;; `ensure-axe-loaded!` latches this once it has seen a global axe;
  ;; clear it so each test re-reads the fake installed for it.
  (reset! a11y/axe-loaded? false)
  (a11y/set-cdn-opt-in! true))

(defn- teardown! []
  (a11y/set-cdn-opt-in! false)
  (reset! a11y/axe-loaded? false)
  (a11y/reset-state!)
  (chrome-a11y/reset-state!)
  (gobj/set js/globalThis "window" @saved-window))

(use-fixtures :each {:before setup! :after teardown!})

;; A context object that is `nodeType`-bearing (so `run-axe!` treats it
;; as the overlay scope) and selector-inert (its `querySelector` finds
;; nothing, so the overlay decorator is a no-op).
(defn- ctx [] #js {:nodeType 1 "querySelector" (fn [_] nil)})

(defn- violation
  "An axe-core-shaped violation carrying `id`, with no nodes — enough for
  the panel to store and for `emit-warning-for-violation` to read."
  [id]
  #js {:id id :impact "serious" :help (str "help for " id) :nodes #js []})

(defn- results
  "An axe-core results object listing violations with the given ids."
  [& ids]
  #js {:violations (apply array (map violation ids))})

(defn- never-settles
  "A promise that never resolves — a scan still in flight when the test
  makes its assertions, so the run holding the slot demonstrably still
  holds it."
  []
  (js/Promise. (fn [_ _] nil)))

(defn- signal
  "A promise plus the fn that resolves it. Lets a test wait for a
  specific point in a SECOND run to be reached, instead of counting
  microtask turns to guess when it happened."
  []
  (let [resolve-fn (atom nil)
        p          (js/Promise. (fn [res _] (reset! resolve-fn res)))]
    {:promise p :fire! (fn [] (@resolve-fn true))}))

(defn- violation-ids
  "The violation ids the variant panel currently holds for `frame-id`."
  [frame-id]
  (mapv #(gobj/get % "id") (get @a11y/violations-by-frame frame-id [])))

(defn- chrome-violation-ids []
  (mapv #(gobj/get % "id") @chrome-a11y/violations))

(def ^:private frame-id :story.a11y-fence/variant)

;; ===========================================================================
;; The variant panel (ui/a11y) — a per-frame slot
;; ===========================================================================

(deftest an-owning-run-records-its-scan
  (testing "POSITIVE CONTROL, and the premise the refusals rest on. A
            fence that simply stopped recording settled scans would pass
            every staleness test below while breaking every real scan.
            The run that still owns the slot records exactly as before"
    (async done
      (let [scans (atom 0)]
        (install-axe! (fn [_] (swap! scans inc)
                        (js/Promise.resolve (results "color-contrast"))))
        (-> (a11y/run-axe! frame-id (ctx))
            (.then (fn [_]
                     (is (= 1 @scans) "the scan really ran")
                     (is (= :done (a11y/status-for frame-id))
                         "the owning run reached its terminal status")
                     (is (= ["color-contrast"] (violation-ids frame-id))
                         "and its violations were recorded")
                     (done))))))))

(deftest no-throw-and-no-resurrection
  (testing "THE BUG (rf2-2amkm): the frame is torn down while the scan is
            in flight. Settling must not throw — and the reason this
            defect was invisible is that it never did: `assoc` over a map
            the frame was dissoc'd from RESURRECTS the entry, fabricating
            a `:done` slot and a violations bag for a frame that is gone,
            which the executor seam then serves to `:rf.assert/a11y` as a
            clean verdict"
    (async done
      (install-axe!
        (fn [_]
          ;; The takeover, placed exactly: the run has claimed the slot
          ;; and reached `:running`, and the scan is now in flight.
          (a11y/drop-frame-state! frame-id)
          (js/Promise.resolve (results "color-contrast"))))
      (-> (a11y/run-axe! frame-id (ctx))
          (.then (fn [_]
                   (is (not (contains? @a11y/run-state frame-id))
                       "no phantom run-state entry — the slot stayed torn down")
                   (is (not (contains? @a11y/violations-by-frame frame-id))
                       "and no phantom violations bag beside it: this is the
                        entry `browser/register-a11y-reader!` would have
                        served to :rf.assert/a11y as a verdict")
                   (is (= :idle (a11y/status-for frame-id))
                       "a frame with no slot reads :idle, NOT :done")
                   nil))
          ;; Reports; it does NOT finish. `done` hands `cljs.test/run-block` a
          ;; continuation that runs the WHOLE remainder of the run
          ;; synchronously, so a rejection handler downstream of the step that
          ;; finished the row claims whatever a LATER namespace throws, prints
          ;; it against this row's label, and fires `done` a second time
          ;; (rf2-e8kc).
          (.catch (fn [e]
                    (is false (str "settling after teardown threw: " e))
                    nil))
          ;; The single `done`, with nothing after it.
          (.then (fn [_] (done)))))))

(deftest stale-settlement-does-not-clobber-the-replacement-run
  (testing "THE OTHER WAY TO LOSE THE SLOT: run A parks on its scan, a
            newer run B claims the same frame, and A THEN settles. A's
            findings must not land in B's slot — the panel would show A's
            verdict over a scan B is still performing"
    (async done
      (let [b-scanning (signal)]
        (install-axe!
          (fn [_]
            ;; B claims the slot mid-A-scan and parks in its own scan, so
            ;; B demonstrably still holds the slot at assertion time.
            (install-axe! (fn [_] ((:fire! b-scanning)) (never-settles)))
            (a11y/run-axe! frame-id (ctx))
            (js/Promise.resolve (results "from-run-a"))))
        (-> (js/Promise.all #js [(a11y/run-axe! frame-id (ctx))
                                 (:promise b-scanning)])
            (.then (fn [_]
                     (is (= :running (a11y/status-for frame-id))
                         "B still owns the slot and is still scanning — A's
                          settlement did not write its terminal :done over it")
                     (is (= [] (violation-ids frame-id))
                         "and A's findings were not attributed to B's scan")
                     (done))))))))

(deftest the-fence-does-not-latch
  (testing "THE SEQUENCE, not just the cases. A fence can be correct on
            every single transition and still be broken as a machine: it
            may latch shut after its first refusal, or latch open after
            its first admission. Four transitions on ONE frame through
            ONE fence, each asserted: ADMIT, REFUSE, ADMIT again (the
            refusal did not latch it shut), REFUSE again (the admission
            did not latch it open)"
    (async done
      (letfn [(admit! [id k]
                (install-axe! (fn [_] (js/Promise.resolve (results id))))
                (.then (a11y/run-axe! frame-id (ctx)) k))
              (refuse! [id k]
                (let [b (signal)]
                  (install-axe!
                    (fn [_]
                      (install-axe! (fn [_] ((:fire! b)) (never-settles)))
                      (a11y/run-axe! frame-id (ctx))
                      (js/Promise.resolve (results id))))
                  (.then (js/Promise.all #js [(a11y/run-axe! frame-id (ctx))
                                              (:promise b)])
                         k)))]
        (admit!
          "v1"
          (fn [_]
            (is (= ["v1"] (violation-ids frame-id)) "1/4 ADMITTED")
            (is (= :done (a11y/status-for frame-id)))
            (refuse!
              "v2"
              (fn [_]
                (is (= ["v1"] (violation-ids frame-id))
                    "2/4 REFUSED — the superseded run left v1 standing")
                (is (= :running (a11y/status-for frame-id)))
                (admit!
                  "v3"
                  (fn [_]
                    (is (= ["v3"] (violation-ids frame-id))
                        "3/4 ADMITTED AGAIN — the refusal did not latch the
                         fence shut")
                    (is (= :done (a11y/status-for frame-id)))
                    (refuse!
                      "v4"
                      (fn [_]
                        (is (= ["v3"] (violation-ids frame-id))
                            "4/4 REFUSED AGAIN — the admission did not latch
                             the fence open")
                        (is (= :running (a11y/status-for frame-id)))
                        (done)))))))))))))

(deftest a-superseded-run-declines-to-scan
  (testing "A run that lost the slot across the CDN-load gap does not
            scan at all. There is nothing to learn from axe-core about a
            torn-down tree, and a superseded run's findings belong to
            nobody — so the fence is consulted before the scan, not only
            before the record"
    (async done
      (let [scans (atom 0)]
        (install-axe! (fn [_] (swap! scans inc) (js/Promise.resolve (results))))
        ;; Claim the slot and tear it down before the load settles — the
        ;; whole of `run-axe!`'s first `.then` is still ahead of us.
        (let [p (a11y/run-axe! frame-id (ctx))]
          (a11y/drop-frame-state! frame-id)
          (-> p
              (.then (fn [_]
                       (is (= 0 @scans)
                           "axe-core was never invoked for the abandoned run")
                       (is (not (contains? @a11y/run-state frame-id))
                           "and nothing was resurrected")
                       (done)))))))))

(deftest a-superseded-run-does-not-report-its-error
  (testing "The `.catch` mutates run-state too, and sits on the same far
            side of the await. A superseded run whose scan REJECTS must
            not stamp `:error` over the run that now owns the slot —
            otherwise a failed abandoned scan discredits a healthy one"
    (async done
      (let [b-scanning (signal)]
        (install-axe!
          (fn [_]
            (install-axe! (fn [_] ((:fire! b-scanning)) (never-settles)))
            (a11y/run-axe! frame-id (ctx))
            (js/Promise.reject (js/Error. "axe blew up"))))
        (-> (js/Promise.all #js [(a11y/run-axe! frame-id (ctx))
                                 (:promise b-scanning)])
            (.then (fn [_]
                     (is (= :running (a11y/status-for frame-id))
                         "B's in-flight scan was not overwritten with A's :error")
                     (done))))))))

(deftest a-torn-down-frame-does-not-resurrect-on-error
  (testing "The same obligation under teardown: a rejected scan for a
            frame that is gone must leave the slot gone, not resurrect it
            carrying `:error`"
    (async done
      (install-axe!
        (fn [_]
          (a11y/drop-frame-state! frame-id)
          (js/Promise.reject (js/Error. "axe blew up"))))
      (-> (a11y/run-axe! frame-id (ctx))
          (.then (fn [_]
                   (is (not (contains? @a11y/run-state frame-id))
                       "no phantom :error slot for a torn-down frame")
                   (done)))))))

;; ===========================================================================
;; The chrome panel (ui/chrome-a11y) — the same fence over a singleton slot
;; ===========================================================================
;;
;; The chrome slot is one atom that always exists, so there is no
;; teardown-resurrection to refuse here — only supersession, and
;; `reset-state!`, which is this panel's analogue of teardown: it returns
;; the panel to `:idle` and must not be undone by a scan still in flight.

(deftest chrome-owning-run-records-its-scan
  (testing "POSITIVE CONTROL for the chrome panel"
    (async done
      (install-axe! (fn [_] (js/Promise.resolve (results "region"))))
      (-> (chrome-a11y/run-axe! (ctx))
          (.then (fn [_]
                   (is (= :done (chrome-a11y/status)))
                   (is (= ["region"] (chrome-violation-ids)))
                   (done)))))))

(deftest chrome-reset-is-not-undone-by-a-pending-scan
  (testing "rf2-2amkm — the panel is reset while a scan is in flight.
            The settlement must not reinstate its verdict over the
            cleared panel: the operator would be shown violations for a
            scan they had already dismissed, and a `:done` status for a
            run that no longer exists"
    (async done
      (install-axe!
        (fn [_]
          (chrome-a11y/reset-state!)
          (js/Promise.resolve (results "region"))))
      (-> (chrome-a11y/run-axe! (ctx))
          (.then (fn [_]
                   (is (= :idle (chrome-a11y/status))
                       "the reset stands — the stale settlement did not
                        restore :done over it")
                   (is (= [] (chrome-violation-ids))
                       "and no violations were reinstated")
                   (done)))))))

(deftest chrome-stale-settlement-does-not-clobber-the-replacement-run
  (testing "rf2-2amkm — run A parks, run B claims the chrome scan, A
            settles late. A's findings must not land in B's slot"
    (async done
      (let [b-scanning (signal)]
        (install-axe!
          (fn [_]
            (install-axe! (fn [_] ((:fire! b-scanning)) (never-settles)))
            (chrome-a11y/run-axe! (ctx))
            (js/Promise.resolve (results "from-run-a"))))
        (-> (js/Promise.all #js [(chrome-a11y/run-axe! (ctx))
                                 (:promise b-scanning)])
            (.then (fn [_]
                     (is (= :running (chrome-a11y/status))
                         "B still owns the scan")
                     (is (= [] (chrome-violation-ids))
                         "and A's findings were not attributed to it")
                     (done))))))))

(deftest the-chrome-fence-does-not-latch
  (testing "THE SEQUENCE for the chrome fence: ADMIT, REFUSE, ADMIT
            again, REFUSE again — the same four transitions the variant
            fence is held to above, for the same reason"
    (async done
      (letfn [(admit! [id k]
                (install-axe! (fn [_] (js/Promise.resolve (results id))))
                (.then (chrome-a11y/run-axe! (ctx)) k))
              (refuse! [id k]
                (let [b (signal)]
                  (install-axe!
                    (fn [_]
                      (install-axe! (fn [_] ((:fire! b)) (never-settles)))
                      (chrome-a11y/run-axe! (ctx))
                      (js/Promise.resolve (results id))))
                  (.then (js/Promise.all #js [(chrome-a11y/run-axe! (ctx))
                                              (:promise b)])
                         k)))]
        (admit!
          "c1"
          (fn [_]
            (is (= ["c1"] (chrome-violation-ids)) "1/4 ADMITTED")
            (refuse!
              "c2"
              (fn [_]
                (is (= ["c1"] (chrome-violation-ids))
                    "2/4 REFUSED — the superseded run left c1 standing")
                (admit!
                  "c3"
                  (fn [_]
                    (is (= ["c3"] (chrome-violation-ids))
                        "3/4 ADMITTED AGAIN — the fence did not latch shut")
                    (refuse!
                      "c4"
                      (fn [_]
                        (is (= ["c3"] (chrome-violation-ids))
                            "4/4 REFUSED AGAIN — the fence did not latch open")
                        (done)))))))))))))
