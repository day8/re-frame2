(ns day8.re-frame2-xray.static.machines.hydrate-restore-cljs-test
  "The Static Machines selection + per-machine sub-mode RESTORE across a
  reload, driven through the real production boot path.

  `panel/install!` calls `persistence/hydrate!` from
  `registry/register-xray-handlers!` — orchestrator time, before
  `mount/ensure-xray-frame!` registers `:rf/xray`. `dispatch` does not
  queue an event for a frame that does not exist yet: an unguarded
  hydrate is refused with a promoted `:rf.error/frame-destroyed` and
  dropped, so the selection never restores and every dev page load logs
  a console error. `hydrate!` therefore guards on the frame, and
  `mount.cljs`'s `::hydrate-static-machines` first-mount hook lands the
  restore. These tests boot through `register-xray-handlers!` +
  `ensure-xray-frame!` and never call `hydrate!` themselves.

  The error listener is a TEST observer on the always-on error channel,
  not an Xray listener.

  Node-test has no jsdom, so this ns installs a minimal in-memory
  `js/window.localStorage` stub, which lets a test seed a prior session's
  choices before boot."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.machines.persistence :as persistence]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; -------------------------------------------------------------------------
;; in-memory localStorage stub (node-test has no jsdom)
;; -------------------------------------------------------------------------

(defn- make-local-storage []
  (let [store (atom {})]
    #js {:getItem    (fn [k] (get @store k nil))
         :setItem    (fn [k v] (swap! store assoc k (str v)) js/undefined)
         :removeItem (fn [k] (swap! store dissoc k) js/undefined)
         :clear      (fn [] (reset! store {}) js/undefined)}))

(defn- install-local-storage! []
  (when-not (exists? js/globalThis.window)
    (set! (.-window js/globalThis) #js {}))
  (set! (.-localStorage js/globalThis.window) (make-local-storage)))

(defn- uninstall-local-storage! []
  ;; Drop the whole window stub we installed so we don't leak a
  ;; localStorage into sibling test namespaces.
  (js-delete js/globalThis "window"))

(def ^:private runtime-fixture
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (persistence/clear!)
                   (static-persistence/clear!)
                   (config/set-filter-seed! nil))}))

(defn- with-local-storage-stub [test-fn]
  (install-local-storage!)
  (try
    (runtime-fixture test-fn)
    (finally
      (uninstall-local-storage!)
      (rf.error-emit/clear-error-listeners!))))

(use-fixtures :each with-local-storage-stub)

;; -------------------------------------------------------------------------
;; boot harness
;; -------------------------------------------------------------------------

(defn- boot!
  "The full production boot: register the handlers (orchestrator time,
  where `panel/install!`'s eager `hydrate!` runs), then
  `ensure-xray-frame!`, which registers `:rf/xray` and walks the
  first-mount hook table."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- capture-errors!
  "Attach a test observer to the always-on error channel and return an
  atom collecting every record emitted from here on."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener!
      ::hydrate-restore-observer
      (fn [record] (swap! seen conj record)))
    seen))

(defn- hydrate-refusals
  "The `:rf.error/frame-destroyed` records attributable to the Static
  Machines hydrate — the console line these tests guard against."
  [records]
  (filterv (fn [{:keys [error event-id]}]
             (and (= :rf.error/frame-destroyed error)
                  (= :rf.xray.static.machines/hydrate event-id)))
           records))

;; A prior session's choices, as localStorage would hold them.
(def ^:private prior-selection :checkout.flow/payment)
(def ^:private prior-sub-modes {:checkout.flow/payment :sim
                                :auth/login            :instances})

;; -------------------------------------------------------------------------
;; tests
;; -------------------------------------------------------------------------

(deftest persisted-slots-restore-on-boot-without-a-refusal
  (persistence/save-selected-id! prior-selection)
  (persistence/save-sub-mode-by-id! prior-sub-modes)
  (let [seen (capture-errors!)]
    (boot!)
    (is (= [prior-selection prior-sub-modes]
           [(frame-sub [:rf.xray.static.machines/selected-id])
            (frame-sub [:rf.xray.static.machines/sub-mode-by-id])])
        "both slots restore on the production boot path")
    (is (empty? (hydrate-refusals @seen))
        "and neither the orchestrator-time hydrate nor the first-mount hook
         emitted a frame-destroyed refusal")))

;; The guard is `either slot has content`, not `selection is present`.
(deftest sub-mode-only-slot-still-restores
  (persistence/save-sub-mode-by-id! prior-sub-modes)
  (boot!)
  (is (= prior-sub-modes
         (frame-sub [:rf.xray.static.machines/sub-mode-by-id]))))
