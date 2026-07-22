(ns re-frame.ui.preflight-frame-wiring-dom-cljs-test
  "S2c (rf2-vxgfnd.9) — the LIVE preflight ENSURE + frame-provider scope
  through a REAL React root (react-dom/client 19.x). The host-agnostic
  executor + ambient-chain unit arms live in the node twin
  (`re-frame.ui.preflight-frame-wiring-cljs-test`); this file pins the
  mount-integration behaviour Node cannot fake:

    - mount runs the root descriptor's frame plans (create + :initial-events
      drain) BEFORE first render;
    - an idempotent re-mount finds the frame live and does NOT re-seed
      (the HMR / reload non-reseed guarantee);
    - Q49: a preflight failure fails the mount LOUDLY with the container
      untouched — no React root, no live-root registration, no render;
    - a compiled `frame-provider` renders its children transparently,
      scoping the live frame through the shared React context.

  Browser-only bodies — the `-dom-cljs-test$` suffix opts this file into
  the `:browser-test` build; under `:node-test` every DOM body gates on
  `(browser?)` and exits early."
  (:require [cljs.test :refer [deftest is use-fixtures async]]
            [clojure.string :as str]
            ["react" :as React]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root frame-provider]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]))

(defn- browser? [] (exists? js/document))

(defn- strip-view-evidence
  "Drop the DEV host-root view-evidence annotations from committed markup: the
  compiler's static `data-rf2-source-coord` / `data-rf-view` (rf2-hac8p) and the
  substrate's commit-time `data-rf-render-key` (rf2-ny34u). These pins assert the
  frame-provider's transparent scoping / rendered template shape, not the
  annotations — whose own coverage is the emit-annotation tests, the parity
  corpus, the render-key DOM-stamp tests, and test:elision."
  [html]
  (str/replace html #"\s+data-rf(?:2-source-coord|-view|-render-key)=\"[^\"]*\"" ""))

;; ---------------------------------------------------------------------------
;; The canonical React act() helper (rf2-vxgfnd.89 template; rf2-powx4d sweep)
;;
;; The repository's established native-React act pattern — the same
;; get-act / enable-react-act-env! shape as core's shared React suite
;; (react_shared_suite.cljs `with-browser-act`) and the frame-scope keystone
;; fixture. It uses React's OWN `act()`, NOT UIx/Reagent machinery, so
;; this fixture stays native re-frame.ui + plain-atom.
;;
;; `flushSync` is NOT an act substitute: React's test contract treats any
;; update committed outside an act scope as "not wrapped in act(...)". The
;; `:browser-test` build runs EVERY `-dom-cljs-test` namespace on one shared
;; page, and sibling suites set IS_REACT_ACT_ENVIRONMENT=true and never reset
;; it — so a flushSync-only mount here emits that warning once the flag has
;; leaked true. Wrapping every committing mount in `act` — with the act env
;; enabled per-test in the fixture — makes the fixture clean under act rather
;; than silencing anything.
(defn- get-act
  "React's act() — React 19 promotes it to the React namespace proper
  (react-dom/test-utils on 18)."
  []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act!
  "Run `thunk` inside React `act()`, returning the thunk's value. The mount
  work commits synchronously under IS_REACT_ACT_ENVIRONMENT (enabled per-test
  in the fixture), so the callback runs eagerly and we capture its result."
  [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

(defn- flush-without-act!
  "Run `thunk` inside `flushSync` with the act env toggled OFF, restoring the
  prior flag after. For a mount that DELIBERATELY throws (`require-scope-frame!`
  aborting a top-region provider with an absent frame): React `act` would
  surface the intended error at the act boundary, and the best-effort teardown
  commit during rollback would warn under the leaked act env. The canonical
  'real flushSync commit path' (react_shared_suite.cljs) — the throw still
  propagates for the surrounding `thrown-id`, and the clean retry commits
  through the same act-env-off path."
  [thunk]
  (let [prior (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (try (react-dom/flushSync thunk)
         (finally (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)))))

(def ^:private prior-act-env (atom nil))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil
                                            :async? true})
  {:before (fn []
             ;; Map form keeps the fixture installed until an async test calls
             ;; `done`; the rollback claim settles on a host microtask.
             (reset! prior-act-env
                     (when (browser?) (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
             (enable-react-act-env!)
             (client/reset-live-roots!)
             (frames/reset-installed-plans!))
   :after  (fn []
             (client/reset-live-roots!)
             (frames/reset-installed-plans!)
             (when (browser?)
               (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))})

(defn- container [] (js/document.createElement "div"))

(defn- thrown-id [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- reg-events! []
  (rf/reg-event :test/set-db (fn [_ [_ db]] {:db db}))
  (rf/reg-event :test/inc    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})))

(defview mini [{:keys [label]}] [:div.mini label])

;; ---------------------------------------------------------------------------
;; mount executes plans BEFORE first render
;; ---------------------------------------------------------------------------

(deftest mount-runs-preflight-ensure-before-render
  (when (browser?)
    (reg-events!)
    (let [c (container)]
      (act!
       #(ui/mount [frame-root {:id :app/shop :initial-events [[:test/set-db {:n 10}]]}
                   [mini {:label "shop"}]]
                  c {:root-id :dom-pf/shop}))
      (is (some? (frame/frame :app/shop)) "the frame was ensured at preflight")
      (is (= 10 (:n (rf/app-db-value :app/shop)))
          ":initial-events drained before the render")
      (let [entry (frames/installed-plan-entry :app/shop)]
        (is (= :dom-pf/shop (:installed-by entry)) "recorded under the mounting root")
        (is (re-find #"^cf1-" (:config-fingerprint entry))
            "the plan's config fingerprint is recorded"))
      (is (re-find #"shop" (.-innerHTML c)) "the scoped subtree rendered"))))

(deftest idempotent-remount-does-not-reseed
  (when (browser?)
    (reg-events!)
    (let [c (container)
          mount! #(ui/mount [frame-root {:id :app/keep
                                         :initial-events [[:test/set-db {:n 1}]]}
                             [mini {:label "keep"}]]
                            c {:root-id :dom-pf/keep})]
      (act! mount!)
      (rf/dispatch-sync [:test/inc] {:frame :app/keep})
      (is (= 2 (:n (rf/app-db-value :app/keep))))
      ;; same root-id + same container -> re-preflight finds the frame live
      (act! mount!)
      (is (= 2 (:n (rf/app-db-value :app/keep)))
          "the reload path re-runs preflight but does NOT re-seed"))))

;; ---------------------------------------------------------------------------
;; Q49 — a preflight failure fails the mount loudly, container untouched
;; ---------------------------------------------------------------------------

(deftest conflicting-plan-fails-mount-with-zero-residue
  (when (browser?)
    (reg-events!)
    ;; root A installs :app/shared with one config
    (act!
     #(ui/mount [frame-root {:id :app/shared :initial-events [[:test/set-db {:n 1}]]}
                 [mini {:label "a"}]]
                (container) {:root-id :dom-pf/a}))
    ;; root B (different root, different container) declares :app/shared with a
    ;; DIFFERING config -> preflight conflict, BEFORE any React work
    (let [cb (container)
          id (thrown-id
              #(react-dom/flushSync
                (fn []
                  (ui/mount [frame-root {:id :app/shared
                                         :initial-events [[:test/set-db {:n 999}]]}
                             [mini {:label "b"}]]
                            cb {:root-id :dom-pf/b}))))]
      (is (= :rf.error/frame-payload-conflict id) "the mount failed loudly")
      (is (= "" (.-innerHTML cb)) "the container is untouched — no React root, no render")
      (is (not (contains? (client/live-root-ids) :dom-pf/b))
          "no live-root registration for the failed mount")
      (is (= 1 (:n (rf/app-db-value :app/shared)))
          "the installed frame is untouched — no last-wins overwrite")
      (is (= :dom-pf/a (:installed-by (frames/installed-plan-entry :app/shared)))
          "root A's install survives; retry = the host re-calls mount"))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.139 — evidence bound to the exact root attempt + host commit
;; boundary. These use a REAL live-root registry + React root (what the node
;; executor twin cannot fake): a fresh install whose HOST render fails must not
;; read as a completed install, and a committed root's failed refresh must
;; preserve its prior committed Root/DOM.
;; ---------------------------------------------------------------------------

(deftest fresh-mount-host-failure-marks-mount-incomplete-not-committed
  ;; Counterexample 2: preflight INSTALLS :frame/installed139 (create +
  ;; :initial-events drain), then the host boundary (element thunk) throws. The
  ;; mount rolls back — NO live root survives — yet the record must not read as
  ;; a completed install. It is :mount-incomplete: no root scopes it.
  (when (browser?)
    (reg-events!)
    (let [c     (container)
          info  {:root-id :dom139/fresh :provenance :authored}
          boom  (fn [] (throw (ex-info "host render boom" {:tag :host})))
          plans (fn [] [{:frame-id :frame/installed139
                         :config {:initial-events [[:test/set-db {:n 1}]]}
                         :config-fingerprint "cf1-installed13911"}])
          threw (try (flush-without-act!
                      #(client/mount* info c boom
                                      (client/root-options nil nil nil nil) plans))
                     false (catch :default _ true))]
      (is threw "the host render throw propagated out of mount*")
      (is (some? (frame/frame :frame/installed139))
          "the frame is live — its :initial-events fired at preflight (irreversible)")
      (is (true? (:tearing-down? (client/live-root-entry :dom139/fresh)))
          "the failed attempt keeps its exact claim while host cleanup settles")
      (is (true? (:mount-incomplete (frames/installed-plan-entry :frame/installed139)))
          "a fresh install whose host render failed is :mount-incomplete — no root scopes it")
      (is (not (:committed (frames/installed-plan-entry :frame/installed139)))
          "and it is NOT recorded as a committed root scope")
      ;; a later conflicting root must be told the mount is incomplete.
      (let [msg (try (frames/execute-frame-plans!
                      :dom139/other [{:frame-id :frame/installed139 :config {}
                                      :config-fingerprint "cf1-other139222222"}])
                     nil (catch cljs.core/ExceptionInfo e (ex-message e)))]
        (is (some? (re-find #"did NOT complete" msg))
            "the conflict flags the phantom installer's incomplete mount")))))

(deftest successful-mount-commits-the-frame-record
  ;; The happy path: a real ui/mount whose host render commits records the
  ;; committed root scope (:committed), with no failed-attempt evidence.
  (when (browser?)
    (reg-events!)
    (let [c (container)]
      (act!
       #(ui/mount [frame-root {:id :frame/committed139
                               :initial-events [[:test/set-db {:n 7}]]}
                   [mini {:label "ok"}]]
                  c {:root-id :dom139/ok}))
      (is (re-find #"ok" (.-innerHTML c)) "sanity: the render committed")
      (let [entry (frames/installed-plan-entry :frame/committed139)]
        (is (true? (:committed entry))
            "a successful mount's host render commits the record (rf2-vxgfnd.139)")
        (is (nil? (:mount-incomplete entry)) "no incomplete-mount evidence")
        (is (nil? (:preflight-attempt-failed entry)) "no failed-attempt evidence")))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.248 — evidence finalizes only after a REAL React commit. The
;; element thunk SUCCEEDS (React receives an element), then the component THROWS
;; during React's initial render; React routes it to `onUncaughtError` and ABORTS
;; the commit AFTER `.render` returned normally. The RED-BEFORE-FIX shape: the
;; pre-fix runtime finalized immediately after `.render` returned, recording a
;; committed root scope for a render that never committed. The commit reporter
;; (rf2-vxgfnd.248) binds finalization to a real commit, so the aborted render
;; publishes NO committed evidence. A mutation moving finalization back to
;; immediately after `.render` deterministically fails this fixture.
;; ---------------------------------------------------------------------------

(defn- throwing-during-render
  "A React function component that succeeds construction but THROWS while React
  renders it — the exact shape `.render` cannot report through its return value."
  [_props]
  (throw (ex-info "render-phase boom (rf2-vxgfnd.248)" {:tag :render248})))

(deftest render-abort-after-render-returns-does-not-finalize-committed
  (when (browser?)
    (reg-events!)
    (let [c        (container)
          captured (atom nil)
          info     {:root-id :dom248/fresh :provenance :authored}
          ;; element thunk RETURNS successfully — React receives a real element —
          ;; whose component then throws during React's render.
          element-thunk (fn [] (React/createElement throwing-during-render #js {}))
          plans    (fn [] [{:frame-id :frame/commit248
                            :config {:initial-events [[:test/set-db {:n 1}]]}
                            :config-fingerprint "cf1-commit248aaaa"}])
          ;; our own onUncaughtError REPLACES React's default (reportError), so
          ;; the deliberate render throw is captured, not surfaced as an uncaught
          ;; window error the browser runner rejects (rf2-mwx08).
          opts     (client/root-options
                    nil (fn [error _info] (reset! captured error)) nil nil)
          ;; flushSync + act env OFF: React commits synchronously, and the
          ;; render-phase throw is owned by onUncaughtError (act would surface it).
          _        (flush-without-act!
                    #(client/mount* info c element-thunk opts plans))]
      (is (some? @captured)
          (str "onUncaughtError fired — the component threw during React's render; "
               "`.render` returned normally (mount* did not throw)"))
      (is (some? (frame/frame :frame/commit248))
          "preflight installed the frame, so an attempt receipt exists to finalize")
      (let [entry (frames/installed-plan-entry :frame/commit248)]
        (is (not (:committed entry))
            (str "the ABORTED render must NOT be recorded as a committed root "
                 "scope — finalization binds to a real React commit, not the "
                 "`.render` return (rf2-vxgfnd.248)")))
      ;; the root stays registered after an aborted commit (rf2-vxgfnd.87) — own
      ;; and release its claim so no live-root leaks into the next test.
      (act! #(some-> (:root (client/live-root-entry :dom248/fresh))
                     client/unmount!*)))))

(deftest committed-root-failed-refresh-preserves-committed-and-marks-attempt-failed
  ;; A genuinely committed root's failed REFRESH/re-render: the prior Root + its
  ;; last committed DOM are preserved (Q49), and only the failed attempt is
  ;; recorded — the committed scope is NEVER relabelled :mount-incomplete.
  (when (browser?)
    (reg-events!)
    (let [c    (container)
          info {:root-id :dom139/live :provenance :authored}
          ok   (fn [] (React/createElement "div" #js {:className "live139"} "live"))
          boom (fn [] (throw (ex-info "rerender boom" {})))
          v1   (fn [] [{:frame-id :frame/live139
                        :config {:initial-events [[:test/set-db {:n 1}]]}
                        :config-fingerprint "cf1-live139v1aaaa"}])
          v2   (fn [] [{:frame-id :frame/live139
                        :config {:initial-events [[:test/set-db {:n 2}]]}
                        :config-fingerprint "cf1-live139v2bbbb"}])]
      ;; first mount COMMITS the frame + root
      (act! #(client/mount* info c ok (client/root-options nil nil nil nil) v1))
      (is (true? (:committed (frames/installed-plan-entry :frame/live139)))
          "sanity: the first mount committed a root scope")
      (is (re-find #"live" (.-innerHTML c)) "sanity: the committed render is in the DOM")
      ;; a re-mount REFRESHES the frame (v2) but its re-render throws
      (let [threw (try (flush-without-act!
                        #(client/mount* info c boom
                                        (client/root-options nil nil nil nil) v2))
                       false (catch :default _ true))]
        (is threw "the failed re-render throws"))
      ;; Q49: the existing root stays registered + its last committed DOM intact
      (is (contains? (client/live-root-ids) :dom139/live)
          "the committed root stays registered on a failed re-render")
      (is (re-find #"live" (.-innerHTML c))
          "its last committed render / DOM is UNTOUCHED (Q49)")
      (let [entry (frames/installed-plan-entry :frame/live139)]
        (is (true? (:committed entry)) "the committed root scope is PRESERVED")
        (is (nil? (:mount-incomplete entry))
            "a committed root's failed refresh is NEVER mount-incomplete")
        (is (true? (:preflight-attempt-failed entry))
            "only the failed refresh ATTEMPT is recorded (rf2-vxgfnd.139)"))
      (act! #(client/unmount!* (:root (client/live-root-entry :dom139/live)))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.264 — TERMINALLY settle an exact preflight receipt when React
;; ABORTS or ABANDONS the render before the commit reporter can finalize it.
;;
;; The rf2-vxgfnd.248 commit reporter binds finalization to a REAL commit, so an
;; aborted render publishes no COMMITTED evidence — but left alone the receipt
;; LINGERS FOREVER, neither committed nor failed (the .248 fixture above asserts
;; only `not :committed`, accepting that permanent-provisional evidence). .264
;; gives each live Root ONE exact pending render-attempt owner, seated before
;; `.render`; the host-error and unmount edges terminally settle it as
;; :mount-incomplete for a fresh install.
;; ---------------------------------------------------------------------------

(defn- no-act!
  "Run `thunk` with React's act env toggled OFF and NO flush — the initial render
  is SCHEDULED but not committed (drives the unmount-before-commit edge). Restores
  the prior flag."
  [thunk]
  (let [prior (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (try (thunk)
         (finally (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)))))

(deftest host-abort-terminally-settles-a-fresh-receipt-mount-incomplete
  ;; The CORE .264 defect: the element thunk succeeds (React receives an element),
  ;; the component throws during React's render, and `onUncaughtError` ABORTS the
  ;; commit AFTER `.render` returned — the reporter's layout effect never runs. The
  ;; .248 fixture proves the receipt is NOT falsely :committed; .264 additionally
  ;; requires it be TERMINALLY settled :mount-incomplete, never left provisional.
  ;; RED before fix: the record is neither :committed NOR :mount-incomplete.
  (when (browser?)
    (reg-events!)
    (let [c        (container)
          captured (atom nil)
          info     {:root-id :dom264/host :provenance :authored}
          element-thunk (fn [] (React/createElement throwing-during-render #js {}))
          plans    (fn [] [{:frame-id :frame/abort264
                            :config {:initial-events [[:test/set-db {:n 1}]]}
                            :config-fingerprint "cf1-abort264aaaa"}])
          ;; an AUTHORED onUncaughtError — it MUST still be delegated after the
          ;; receipt abort (rf2-vxgfnd.264), and capturing the deliberate throw keeps
          ;; it off the window as an uncaught error (rf2-mwx08).
          opts     (client/root-options
                    nil (fn [error _info] (reset! captured error)) nil nil)
          _        (flush-without-act!
                    #(client/mount* info c element-thunk opts plans))]
      (is (some? @captured)
          "the authored onUncaughtError is still delegated after the receipt abort")
      (is (some? (frame/frame :frame/abort264))
          "preflight installed the frame — a receipt exists to settle")
      (let [entry (frames/installed-plan-entry :frame/abort264)]
        (is (true? (:mount-incomplete entry))
            "the host-aborted FRESH render is TERMINALLY settled :mount-incomplete (rf2-vxgfnd.264)")
        (is (not (:committed entry))
            "and never a phantom committed root scope (rf2-vxgfnd.248 preserved)"))
      ;; the root stays registered after an aborted commit (`.render` returned) —
      ;; release its claim so no live-root leaks into the next test.
      (act! #(some-> (:root (client/live-root-entry :dom264/host)) client/unmount!*)))))

(deftest unmount-before-commit-terminally-settles-a-fresh-receipt-mount-incomplete
  ;; A fresh async render, SCHEDULED but never committed, followed by an immediate
  ;; unmount: the seated receipt is terminally settled :mount-incomplete rather than
  ;; left forever provisional. RED before fix: unmount never settled the receipt.
  (when (browser?)
    (reg-events!)
    (let [c     (container)
          info  {:root-id :dom264/unmount :provenance :authored}
          ok    (fn [] (React/createElement "div" #js {:className "u264"} "u264"))
          plans (fn [] [{:frame-id :frame/unmount264
                         :config {:initial-events [[:test/set-db {:n 1}]]}
                         :config-fingerprint "cf1-unmount264aa"}])]
      ;; mount schedules the initial render (act env off, no flush → uncommitted),
      ;; then unmount BEFORE it can commit.
      (no-act!
       #(let [root (client/mount* info c ok (client/root-options nil nil nil nil) plans)]
          (client/unmount!* root)))
      (is (some? (frame/frame :frame/unmount264))
          "preflight installed the frame — a receipt exists to settle")
      (let [entry (frames/installed-plan-entry :frame/unmount264)]
        (is (true? (:mount-incomplete entry))
            "the unmounted-before-commit FRESH render is TERMINALLY settled :mount-incomplete (rf2-vxgfnd.264)")
        (is (not (:committed entry))
            "and never a phantom committed root scope"))
      (is (not (contains? (client/live-root-ids) :dom264/unmount))
          "the unmounted root is released"))))

;; ---------------------------------------------------------------------------
;; frame-provider — scope a live frame through the compiled template
;; ---------------------------------------------------------------------------

(defview scoping-view
  [{:keys [frame-id]}]
  [:div.wrap
   [frame-provider {:frame frame-id}
    [mini {:label "inside"}]]])

(deftest frame-provider-renders-children-transparently
  (when (browser?)
    (rf/make-frame {:id :app/live :doc "scope target"})
    (let [c (container)]
      (act!
       #(ui/mount [scoping-view {:frame-id :app/live}] c
                  {:root-id :dom-pf/scoped}))
      (is (re-find #"<div class=\"wrap\"><div class=\"mini\">inside</div></div>"
                   (strip-view-evidence (.-innerHTML c)))
          "the frame-provider scopes transparently — children render through it"))))

;; NOTE: the absent-frame fail-loud (`require-scope-frame!` throwing
;; `:rf.error/frame-provider-frame-absent`) is pinned synchronously in the
;; node twin. A NESTED provider (inside a view, like `scoping-view` above)
;; throws during React render, where React captures it via its error-
;; boundary path (console-reported, not synchronously rethrown out of
;; flushSync) — React's render-error handling, not a re-frame contract
;; surface, so it is asserted at the unit level, not here. A TOP-REGION
;; provider is the distinct case (rf2-vxgfnd.34): the compiler emits
;; `provider-scope-element` DIRECTLY into the element thunk, so the throw
;; IS synchronous out of `(element-thunk)` — the fixture below pins that
;; it rolls back cleanly inside mount*'s rf2-vxgfnd.18 boundary.

;; ---------------------------------------------------------------------------
;; top-region frame-provider with an absent frame — clean mount rollback
;; (rf2-vxgfnd.34; VERDICT: already covered by rf2-vxgfnd.18's mount* boundary)
;; ---------------------------------------------------------------------------

(deftest top-region-provider-absent-frame-rolls-back-cleanly
  ;; A TOP-REGION `frame-provider` naming an ABSENT frame: `emit-inline`
  ;; lowers it to `provider-scope-element` directly in the element thunk
  ;; (NOT deferred into a component render), so `require-scope-frame!`
  ;; throws `:rf.error/frame-provider-frame-absent` SYNCHRONOUSLY when
  ;; mount* calls `(element-thunk)` — inside the first-render rollback
  ;; try/catch that rf2-vxgfnd.18 wraps around `(.render root (thunk))`.
  ;; So .18 ALREADY covers this throw source: no phantom claimed React
  ;; root, no lingering live-root registration, and a retry mounts clean.
  (async done
    (if-not (browser?)
      (done)
      (let [c (container)
            mount-provider!
            (fn []
              (ui/mount [frame-provider {:frame :dom-absent/never}
                         [mini {:label "scoped"}]]
                        c {:root-id :dom-absent/root}))]
        (is (= :rf.error/frame-provider-frame-absent
               (thrown-id #(flush-without-act! mount-provider!)))
            "the absent-frame throw propagates synchronously out of mount")
        (is (true? (:tearing-down? (client/live-root-entry :dom-absent/root)))
            "the failed attempt keeps its exact claim while cleanup settles")
        (is (= "" (.-innerHTML c)) "nothing committed to the container")
        ;; The retry becomes admissible only after the no-reporter cleanup has
        ;; terminally settled on the next FIFO microtask.
        (rf/make-frame {:id :dom-absent/never :doc "now live"})
        (is (= :rf.error/duplicate-root-id (thrown-id mount-provider!))
            "a same-stack retry cannot overtake the failed incarnation")
        (js/queueMicrotask
         (fn []
           (try
             (is (nil? (client/live-root-entry :dom-absent/root))
                 "normal rollback releases the exact claim after settlement")
             (let [root (flush-without-act! mount-provider!)]
               (is (some? root))
               (is (contains? (client/live-root-ids) :dom-absent/root)
                   "the settled root-id + container accept a clean retry")
               (is (re-find #"<div class=\"mini\">scoped</div>"
                            (strip-view-evidence (.-innerHTML c)))
                   "the provider scopes the now-live frame transparently")
               (act! #(ui/unmount! root)))
             (catch :default e
               (is false (str "async rollback settlement threw: " e)))
             (finally
               (done)))))))))
