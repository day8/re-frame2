(ns re-frame.ui.presence-retention-elision-prod-test
  "rf2-55zsd — G-7 Layer 2's named `ui/presence` gap: RETENTION → terminal
  REMOVAL → teardown, proven in the ADVANCED production bundle.

  This is NOT one of the host-render `cond` wrapper shapes rf2-55zsd enumerated
  (those select the frame/sub/event ViewCell carriage — all five, inert /
  render-frame / render-subs / render-events / render-subs-and-events, are
  covered by `frame-ops-provider-retarget-elision-prod-test` and
  `event-wrapper-shapes-elision-prod-test`). `ui/presence` is ORTHOGONAL: a
  keyed-child retention boundary (`PresenceComponent`, `re-frame.ui.presence-runtime`).
  Its dev proof `presence-dom-cljs-test` drives the machine through
  `ui.test/flush!` / `flush-presence!`, both of which ride React `act` — ELIDED
  from `:advanced` production React. So no fixture yet proved the retention
  machine survives the production bundle.

  It matters because the machine's DEV scaffolding IS `goog.DEBUG`-guarded (the
  `warn-duplicate-identities!` / `warn-keyless-drops!` diagnostics fold away
  under `:advanced`), while the RETENTION machine itself — `reconcile`'s
  `:unmounting` classification, `schedule-exit!` / `advance-clock!` /
  `fire-timer!`, the enter-flip `force!`, and the teardown timer cancel — is
  UNCONDITIONAL and must run identically in production. This fixture pins that: a
  future change that accidentally guarded any retention step behind `goog.DEBUG`
  would redden HERE while the dev test stayed green.

  Runs ONLY in `:browser-test-prod-elision` (`shadow-cljs release` ⇒
  `:optimizations :advanced`, `goog.DEBUG=false`), mounting a real
  `react-dom/client` root. React `act` is elided here, so — like
  `event-wrapper-shapes-elision-prod-test` — this drives the presence PASSIVE
  effects (enter flip, exit scheduling) through real macrotasks (`settle!`) and
  forces the removal's `force!` setState commit through `ReactDOM/flushSync`. The
  child list is prop-driven, not sub-driven, so the retention machine is
  exercised WITHOUT the router/sub async layers `presence-dom-cljs-test` and the
  event fixtures already pin — the compact, deterministic isolation the audit
  asked for (reusing the existing fake presence clock: wall-clock disabled, so
  `advance-clock!` is the sole removal driver).

  Mutation teeth (each reddens an arm below):
    - retention: were `reconcile` to DROP a departed key instead of classifying
      it `:unmounting`, the removed child would vanish immediately — the
      \"2 retained, one `:unmounting`\" assertion goes RED;
    - removal exactly once: were `schedule-exit!` / `advance-clock!` / the
      removal `force!` broken or dev-gated, advancing past `:timeout-ms` would
      NOT drop the child — \"1 child, pending 0, removed=1\" goes RED;
    - teardown: were the `useEffect #js []` timer-cancel cleanup dropped, an
      unmount WHILE retained would leak the pending exit — \"pending 0 after
      unmount\" goes RED."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as ReactDOM]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.presence-runtime :as presence]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; ---------------------------------------------------------------------------
;; Async settling — no React `act` in the :advanced bundle
;; ---------------------------------------------------------------------------

(defn- macrotask [] (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- settle!
  "Drain the presence PASSIVE effects (the enter `:mounting`→`:present` flip and
  the exit `schedule-exit!`) plus the `force!` re-render commits they schedule.
  `ui.test/flush!` rides React `act`, elided from `:advanced` production React,
  so this chains real macrotasks instead — enough rounds for the flip's
  effect→setState→re-render→effect cascade to reach its fixed point."
  []
  (reduce (fn [p _] (.then p macrotask)) (js/Promise.resolve) (range 6)))

;; ---------------------------------------------------------------------------
;; The prod presence boundary — a keyed child list driven by a plain prop
;; ---------------------------------------------------------------------------

(defview presence-card
  "A keyed presence child. `data-phase` publishes its `(ui/presence-phase)` read
  (a React context), so retention (`:unmounting`) is observable off the DOM."
  [{:keys [msg]}]
  [:li {:data-role  "presence-card"
        :data-msg   msg
        :data-phase (name (ui/presence-phase))}
   msg])

(defview prod-presence-list
  "A `(ui/presence …)` boundary over a prop-driven keyed `for`. No sub / event /
  frame-op — the retention machine is the whole subject."
  [{:keys [items]}]
  (ui/presence {:timeout-ms 300}
    (for [it items]
      [presence-card {:key (:id it) :msg (:msg it)}])))

;; ---------------------------------------------------------------------------
;; Fixtures — the async runtime reset PLUS the deterministic presence clock
;; (wall-clock disabled, so advance-clock! is the SOLE removal driver — the
;; existing fake presence clock surface, reused verbatim from the dev test).
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  {:before #(do (presence/reset-clock!) (presence/set-wall-clock! false))
   :after  #(do (presence/reset-clock!) (presence/set-wall-clock! true))})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- cards [container]
  (vec (.querySelectorAll container "[data-role='presence-card']")))

(defn- phase-of [container msg]
  (some #(when (= msg (.getAttribute % "data-msg"))
           (.getAttribute % "data-phase"))
        (cards container)))

(defn- item [id msg] {:id id :msg msg})

;; ---------------------------------------------------------------------------
;; Retention → terminal removal (exactly once)
;; ---------------------------------------------------------------------------

(deftest presence-retains-then-timeout-removes-exactly-once-in-advanced-production
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — the browser job runs it")
    (let [container (js/document.createElement "div")
          _         (.appendChild js/document.body container)
          root      (ui/create-root container {:root-id ::retain-root})
          mounted?  (volatile! true)]
      (async done
        (-> (js/Promise.resolve)
            (.then
             (fn []
               (ReactDOM/flushSync
                #(ui/render! root [prod-presence-list {:items [(item 1 "a") (item 2 "b")]}]))
               (settle!)))                     ; enter flip → :present
            (.then
             (fn []
               (testing "enter: both keyed children present in the advanced bundle"
                 (is (= 2 (count (cards container))))
                 (is (= "present" (phase-of container "a")))
                 (is (= "present" (phase-of container "b"))))
               ;; Logical removal: drop key 2 from the incoming list.
               (ReactDOM/flushSync
                #(ui/render! root [prod-presence-list {:items [(item 1 "a")]}]))
               (settle!)))                     ; exit scheduling (schedule-exit!)
            (.then
             (fn []
               (testing "retention: the removed child stays mounted :unmounting"
                 (is (= 2 (count (cards container))) "b is RETAINED, not dropped")
                 (is (= "present" (phase-of container "a")))
                 (is (= "unmounting" (phase-of container "b")))
                 (is (= 1 (presence/pending-count)) "one exit timer armed"))
               (testing "below :timeout-ms the retention holds"
                 (presence/advance-clock! 100)  ; fire-at is 300 — nothing due
                 (is (= 2 (count (cards container))))
                 (is (= "unmounting" (phase-of container "b")))
                 (is (= 1 (presence/pending-count))))
               (testing "at :timeout-ms the exit fires and removes b EXACTLY ONCE"
                 ;; flushSync so the removal callback's force! setState commits.
                 (let [removed (ReactDOM/flushSync #(presence/advance-clock! 300))]
                   (is (= 1 removed) "advancing past the bound removed exactly one exit")
                   (is (= 1 (count (cards container))) "b is gone")
                   (is (= "a" (some-> (first (cards container)) (.-textContent)))
                       "the surviving child is a")
                   (is (nil? (phase-of container "b")))
                   (is (= 0 (presence/pending-count)) "no retention timer left")
                   (is (= 0 (presence/advance-clock! 10000))
                       "a further advance fires nothing — the removal did not double")))
               (settle!)))
            (.then
             (fn []
               (testing "the removal is terminal — b does not resurrect after settling"
                 (is (= 1 (count (cards container))))
                 (is (nil? (phase-of container "b"))))))
            (.catch (fn [e] (is false (str "presence retention fixture rejected: "
                                           (some-> e ex-message)))))
            (.finally
             (fn []
               (when @mounted?
                 (ReactDOM/flushSync #(ui/unmount! root))
                 (vreset! mounted? false))
               (.remove container)
               (done))))))))

;; ---------------------------------------------------------------------------
;; Teardown cleanup — unmount WHILE retained cancels the pending exit
;; ---------------------------------------------------------------------------

(deftest presence-teardown-cancels-a-pending-exit-in-advanced-production
  (if-not (browser?)
    (is true "mounted presence needs a DOM host — the browser job runs it")
    (let [container (js/document.createElement "div")
          _         (.appendChild js/document.body container)
          root      (ui/create-root container {:root-id ::teardown-root})
          mounted?  (volatile! true)]
      (async done
        (-> (js/Promise.resolve)
            (.then
             (fn []
               (ReactDOM/flushSync
                #(ui/render! root [prod-presence-list {:items [(item 1 "a")]}]))
               (settle!)))                     ; enter flip
            (.then
             (fn []
               (testing "enter: the keyed child is present"
                 (is (= 1 (count (cards container))))
                 (is (= "present" (phase-of container "a"))))
               ;; Remove it and let the exit timer arm, but do NOT advance.
               (ReactDOM/flushSync
                #(ui/render! root [prod-presence-list {:items []}]))
               (settle!)))                     ; schedule-exit!
            (.then
             (fn []
               (testing "the departed child is retained with a pending exit timer"
                 (is (= 1 (count (cards container))) "a is retained :unmounting")
                 (is (= "unmounting" (phase-of container "a")))
                 (is (= 1 (presence/pending-count)) "its exit timer is armed"))
               ;; Unmount WHILE the exit is still pending.
               (ReactDOM/flushSync #(ui/unmount! root))
               (vreset! mounted? false)
               (settle!)))                     ; run the teardown effect cleanup
            (.then
             (fn []
               (testing "teardown: unmount cancelled the pending exit WITHOUT firing it"
                 (is (= 0 (presence/pending-count))
                     "the retained exit timer was cancelled on unmount — no leak")
                 (is (= 0 (count (cards container)))
                     "the tree detached — no orphaned retained child"))))
            (.catch (fn [e] (is false (str "presence teardown fixture rejected: "
                                           (some-> e ex-message)))))
            (.finally
             (fn []
               (when @mounted?
                 (ReactDOM/flushSync #(ui/unmount! root))
                 (vreset! mounted? false))
               (.remove container)
               (done))))))))
