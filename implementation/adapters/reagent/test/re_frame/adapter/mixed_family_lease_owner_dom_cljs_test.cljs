(ns re-frame.adapter.mixed-family-lease-owner-dom-cljs-test
  "Mixed-adapter resource-lease OWNER regression (rf2-qdkt8y) — the P1 the
  centralization fixes.

  Before the fix each adapter family kept its OWN `(atom 0)` lease-token
  counter (stock Reagent, reagent-slim, and the shared React-hook helper each
  minted from zero), so the FIRST lease taken by any two families in one
  mixed-adapter process collided on the identical owner `[:lease 1]`. Because
  `:rf.resource/release-owner` drops an owner from EVERY indexed resource,
  unmounting ONE family's component then cross-released ANOTHER family's
  still-live resource — a silent liveness bug.

  This test mounts BOTH families against ONE frame's REAL resources runtime and
  proves the property directly on `:active-owners`:

    1. SIMULTANEOUS mounts get DISTINCT owners — the Reagent-family
       `with-resource-lease` and the React-hook-family `use-resource-lease`
       leases carry different `[:lease …]` owners (under the old per-family
       counters both would be `[:lease 1]`).
    2. UNMOUNTING ONE preserves the OTHER's lease — after the Reagent component
       unmounts (its `release-owner` fires against the REAL handler), the
       React-hook component's resource still lists its owner as active (under
       the old colliding owner, that same `release-owner [:lease 1]` would drop
       the hook family's owner too, leaving its resource owner-free).

  The React-hook family here is `re-frame.adapter.resource-lease/
  use-resource-lease` called inside a bare React function component — the EXACT
  Var UIx and Helix re-export verbatim (`uix.cljs` / `helix.cljs` bind
  `use-resource-lease` to `resource-lease/use-resource-lease`), so this is a
  faithful mixed Reagent/UIx regression without pulling the UIx library onto the
  Reagent adapter's test classpath. Both leases pin an explicit `:frame` so the
  test isolates the OWNER-identity bug from frame resolution.

  Browser-only — the Reagent lifecycle methods + the hook effect only fire under
  a real React mount. `-dom-cljs-test$` opts it into `:browser-test`;
  `:node-test` loads it too and the DOM body gates on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; The shared React-hook lease Var — the very one UIx / Helix
            ;; re-export. Exercised directly = the UIx/Helix family's behaviour.
            [re-frame.adapter.resource-lease :as resource-lease]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]
            ;; load-bearing side-effecting requires: register the
            ;; :rf.resource/* events + the managed-HTTP fx surface (overridden
            ;; by a no-op stub below so ensure never fetches) + schema support.
            [re-frame.resources]
            [re-frame.resources.state :as state]
            [re-frame.http.managed]
            [re-frame.schemas]))

;; ---- fixture (MAP-form, async-safe) ---------------------------------------
;; A function fixture would tear down mid-async (its `(f)` returns before the
;; async body's `done`); the map `{:before :after}` form brackets the async
;; completion. Installs the Reagent adapter + a REAL global resource whose
;; transport is stubbed to a no-op, so ensure writes the :loading entry + owner
;; deterministically without a live fetch.

(def ^:private lease-frame :rf/default)
(def ^:private resource-id :rf.mixed-lease/feed)

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (substrate-adapter/install-adapter! reagent-adapter/adapter)
  (frame/ensure-default-frame!)
  ;; No real fetch — ensure only needs to write the :loading entry + attach the
  ;; owner for the owner-index / release-owner assertions.
  (rf/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource resource-id
    {:scope         :rf.scope/global
     :params-schema [:map [:page :int]]
     :tags          (fn [_p _v] #{[:mixed-feed]})}
    (fn [{:keys [page]} _ctx]
      {:request {:method :get :url "/feed" :params {:page page}}})))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- descriptors + runtime readers ----------------------------------------

(def ^:private reagent-descriptor
  {:resource resource-id :scope :rf.scope/global :params {:page 0}})
(def ^:private hook-descriptor
  {:resource resource-id :scope :rf.scope/global :params {:page 1}})

(defn- entry [scoped-key]
  (get-in (:rf.db/runtime (rf/frame-state-value lease-frame))
          (state/entry-path scoped-key)))

(defn- active-owners
  "The set of live lease owners on the resource entry for `params` (nil / #{} if
  the entry is owner-free or GC'd)."
  [params]
  (:active-owners (entry (state/scoped-resource-key :rf.scope/global resource-id params))))

;; ---- DOM helpers (mirroring the sibling with-resource-lease DOM suite) ------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

;; Wait two macrotasks so the router's next-tick dispatch drain has run.
(defn- after-drain [k] (js/setTimeout (fn [] (js/setTimeout k 0)) 0))

(defn- make-hook-lease-component
  "A bare React function component that takes a lease via the shared
  `use-resource-lease` hook — the UIx / Helix family's exact code path.
  Variadic arglist so React may invoke it with props (+ legacy 2nd arg)."
  [descriptor frame-id]
  (fn hook-lease [& _props]
    (resource-lease/use-resource-lease descriptor {:frame frame-id})
    (React/createElement "div" nil "hook-leased")))

;; ---- the regression --------------------------------------------------------

(deftest mixed-family-mounts-get-distinct-owners-and-are-independently-released
  (testing "a Reagent-family lease and a React-hook-family (UIx/Helix) lease,
            mounted simultaneously against one frame, get DISTINCT owners;
            unmounting the Reagent one preserves the hook one's lease (rf2-qdkt8y)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (async done
            (binding [frame/*current-frame* nil]
              (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
              (let [reagent-node (.createElement js/document "div")
                    hook-node    (.createElement js/document "div")
                    reagent-root (rdc/create-root reagent-node)
                    hook-root    (rdc/create-root hook-node)
                    hook-comp    (make-hook-lease-component hook-descriptor lease-frame)]
                ;; Mount BOTH families, each pinned to the same frame.
                (act-fn
                  (fn []
                    (rdc/render reagent-root
                      [reagent-adapter/with-resource-lease
                       reagent-descriptor {:frame lease-frame}
                       (fn [] [:div "reagent-leased"])])
                    (rdc/render hook-root [:> hook-comp])))
                (after-drain
                  (fn []
                    ;; ---- Phase 1: simultaneous mounts → DISTINCT owners ----
                    (let [owner-r (first (active-owners {:page 0}))
                          owner-h (first (active-owners {:page 1}))]
                      (is (= 1 (count (active-owners {:page 0})))
                          "the Reagent-family resource holds exactly its own lease")
                      (is (= 1 (count (active-owners {:page 1})))
                          "the hook-family resource holds exactly its own lease")
                      (is (= :lease (first owner-r)) "Reagent owner is a [:lease …]")
                      (is (= :lease (first owner-h)) "hook owner is a [:lease …]")
                      (is (not= owner-r owner-h)
                          "the two families minted DISTINCT owners (the core bug: old
                           per-family counters both minted [:lease 1])")
                      ;; ---- Phase 2: unmount Reagent → hook's lease survives ----
                      (act-fn (fn [] (rdc/unmount reagent-root)))
                      (after-drain
                        (fn []
                          (is (not (contains? (set (active-owners {:page 0})) owner-r))
                              "the unmounted Reagent-family lease was released")
                          (is (contains? (set (active-owners {:page 1})) owner-h)
                              "the STILL-MOUNTED hook-family lease is PRESERVED — the
                               Reagent release did NOT cross-release it (the rf2-qdkt8y
                               fix; under the colliding [:lease 1] owner it would have)")
                          (act-fn (fn [] (rdc/unmount hook-root)))
                          (done))))))))))))))
