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
  (:require [cljs.test :refer [deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-root frame-provider]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t]
    (client/reset-live-roots!)
    (frames/reset-installed-plans!)
    (t)
    (client/reset-live-roots!)
    (frames/reset-installed-plans!)))

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
      (react-dom/flushSync
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
      (react-dom/flushSync mount!)
      (rf/dispatch-sync [:test/inc] {:frame :app/keep})
      (is (= 2 (:n (rf/app-db-value :app/keep))))
      ;; same root-id + same container -> re-preflight finds the frame live
      (react-dom/flushSync mount!)
      (is (= 2 (:n (rf/app-db-value :app/keep)))
          "the reload path re-runs preflight but does NOT re-seed"))))

;; ---------------------------------------------------------------------------
;; Q49 — a preflight failure fails the mount loudly, container untouched
;; ---------------------------------------------------------------------------

(deftest conflicting-plan-fails-mount-with-zero-residue
  (when (browser?)
    (reg-events!)
    ;; root A installs :app/shared with one config
    (react-dom/flushSync
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
      (react-dom/flushSync
       #(ui/mount [scoping-view {:frame-id :app/live}] c
                  {:root-id :dom-pf/scoped}))
      (is (re-find #"<div class=\"wrap\"><div class=\"mini\">inside</div></div>"
                   (.-innerHTML c))
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
  (when (browser?)
    (let [c (container)
          mount-provider!
          (fn []
            (react-dom/flushSync
             #(ui/mount [frame-provider {:frame :dom-absent/never}
                         [mini {:label "scoped"}]]
                        c {:root-id :dom-absent/root})))]
      (is (= :rf.error/frame-provider-frame-absent (thrown-id mount-provider!))
          "the absent-frame throw propagates synchronously out of mount")
      (is (not (contains? (client/live-root-ids) :dom-absent/root))
          "no phantom claimed live root remains — mount* rolled back (rf2-vxgfnd.18)")
      (is (nil? (client/live-root-entry :dom-absent/root)))
      (is (= "" (.-innerHTML c)) "nothing committed to the container")
      ;; retry: create the frame, then the SAME mount succeeds cleanly —
      ;; the freed root-id + container prove the rollback was total
      (rf/make-frame {:id :dom-absent/never :doc "now live"})
      (let [root (mount-provider!)]
        (is (some? root))
        (is (contains? (client/live-root-ids) :dom-absent/root)
            "the freed root-id + container accept a clean retry")
        (is (re-find #"<div class=\"mini\">scoped</div>" (.-innerHTML c))
            "the provider scopes the now-live frame transparently")
        (ui/unmount! root)))))
