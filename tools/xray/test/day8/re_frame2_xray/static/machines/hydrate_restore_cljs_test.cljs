(ns day8.re-frame2-xray.static.machines.hydrate-restore-cljs-test
  "The Static Machines selection + per-machine sub-mode RESTORE across a
  reload, driven through the real production boot path (rf2-qw0o).

  ## The defect these tests pin

  `static/machines/panel.cljs`'s `install!` called
  `persistence/hydrate!`, which dispatched
  `:rf.xray.static.machines/hydrate` at `{:frame :rf/xray}`. But
  `install!` runs from `registry/register-xray-handlers!` —
  ORCHESTRATOR time, well before `mount/ensure-xray-frame!` registers
  the `:rf/xray` frame. `hydrate!`'s docstring asserted that `dispatch`
  QUEUES an event aimed at a not-yet-registered frame and replays it
  once the frame appears. It does not: the dispatch was refused with a
  promoted `:rf.error/frame-destroyed` and DROPPED.

  Two things followed, and both are asserted below:

    1. The persisted selection never reached app-db, so the operator's
       last-inspected machine silently failed to restore on every
       reload — a real state loss, not just log noise.
    2. Every Xray-preloaded dev page load emitted exactly one promoted
       refusal to the console. It was the whole of the residual console
       error count in the Story feature-load gate.

  The fix gives `hydrate!` the frame guard its two siblings
  (`views.resizable-table/hydrate!`, `frame-switcher/hydrate!`) already
  carry, and registers a `::hydrate-static-machines` first-mount hook in
  `mount.cljs` — the seam that knows the frame exists.

  ## Why these tests drive `ensure-xray-frame!`

  The pre-fix `persistence_cljs_test.cljs` suite is comprehensive on the
  localStorage round-trip and stayed green throughout, because it never
  called `hydrate!` — it tested `save!`/`load` directly. The defect lived
  entirely in the seam BETWEEN a correct round-trip and the boot
  sequence, so a test that calls `hydrate!` by hand would route around
  it exactly as the old suite did. These tests therefore go through
  `registry/register-xray-handlers!` + `mount/ensure-xray-frame!` — the
  same walk a real page load performs — and never call `hydrate!`
  themselves.

  ## The error listener is a TEST observer, not an Xray listener

  `rf.error-emit/register-error-listener!` below is registered BY THE TEST
  to observe the always-on error channel, the same way the framework's
  own `*_conformance` suites use it. It is emphatically NOT Xray
  registering an `:errors` listener: rf2-fu75 ruled that Xray does not
  populate that registry (it rides the dev-only trace axis), and nothing
  here changes Xray's production posture. The observer exists so the
  console-error regression has a cheap node-test lever instead of only
  the expensive browser gate.

  Node-test has no jsdom, so this ns installs the same minimal in-memory
  `js/window.localStorage` stub `init_filter_reset_cljs_test` uses. The
  stub is what lets a test SEED a prior session's choices pre-boot and
  then assert they survive into app-db."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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

(defn- register-handlers!
  "Orchestrator time ONLY — the phase in which `panel/install!` (and so
  the pre-fix eager `hydrate!`) runs. Deliberately stops short of
  `ensure-xray-frame!` so the tests can observe this phase on its own."
  []
  (registry/register-xray-handlers!))

(defn- boot!
  "The full production boot: register the handlers, then run
  `ensure-xray-frame!`, which registers `:rf/xray` and walks the
  first-mount hook table. Never calls `hydrate!` directly — landing the
  restore is precisely what is under test."
  []
  (register-handlers!)
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
  Machines hydrate — the exact console line rf2-qw0o is about."
  [records]
  (filterv (fn [{:keys [error event-id]}]
             (and (= :rf.error/frame-destroyed error)
                  (= :rf.xray.static.machines/hydrate event-id)))
           records))

;; A prior session's choices, as localStorage would hold them.
(def ^:private prior-selection :checkout.flow/payment)
(def ^:private prior-sub-modes {:checkout.flow/payment :sim
                                :auth/login            :instances})

(defn- seed-prior-session! []
  (persistence/save-selected-id! prior-selection)
  (persistence/save-sub-mode-by-id! prior-sub-modes))

;; -------------------------------------------------------------------------
;; (0) preconditions — the stub is real
;; -------------------------------------------------------------------------

(deftest local-storage-stub-round-trips
  (testing "the in-memory stub backs save!/load, so the seeding below is
            a real persisted prior session rather than a no-op"
    (seed-prior-session!)
    (is (= prior-selection (persistence/load-selected-id))
        "selection slot round-trips through the stub")
    (is (= prior-sub-modes (persistence/load-sub-mode-by-id))
        "sub-mode slot round-trips through the stub")))

;; -------------------------------------------------------------------------
;; (1) THE ACCEPTANCE — the persisted selection restores after a reload
;; -------------------------------------------------------------------------

(deftest persisted-selection-restores-after-reload
  (testing "rf2-qw0o — a machine selected in a prior session is the
            selected machine after the next boot. This is the user-visible
            loss the bead is about: pre-fix the hydrate was refused and
            dropped, so this slot came up nil on every reload."
    (seed-prior-session!)
    ;; Precondition asserted against localStorage, NOT a pre-boot
    ;; subscribe: subscribing before `:rf/xray` exists is itself a
    ;; recover-but-emit `:rf.error/frame-destroyed`, which would put a
    ;; refusal of our own making on the channel the tests below observe.
    (is (= prior-selection (persistence/load-selected-id))
        "precondition: the prior session's choice is in storage, and no
         frame exists yet to have hydrated it")
    (boot!)
    (is (= prior-selection
           (frame-sub [:rf.xray.static.machines/selected-id]))
        "the prior session's selection IS restored on the real production
         boot path — WITHOUT the test calling hydrate! itself")))

(deftest persisted-sub-mode-map-restores-after-reload
  (testing "rf2-qw0o — the per-machine sub-mode map restores with the
            selection; both slots ride the one hydrate event"
    (seed-prior-session!)
    (boot!)
    (is (= prior-sub-modes
           (frame-sub [:rf.xray.static.machines/sub-mode-by-id]))
        "the whole {machine-id sub-mode} map is restored")
    (is (= :sim
           (frame-sub [:rf.xray.static.machines/sub-mode
                       :checkout.flow/payment]))
        "the effective sub-mode for the restored machine resolves to the
         persisted :sim, not the :topology default")))

(deftest restore-survives-a-second-boot
  (testing "rf2-qw0o — the hook is re-entrant: a second
            `ensure-xray-frame!` for the same frame-id (the popout! path)
            leaves the restored slots intact"
    (seed-prior-session!)
    (boot!)
    (mount/ensure-xray-frame!)
    (is (= prior-selection
           (frame-sub [:rf.xray.static.machines/selected-id]))
        "selection unchanged by the second call")
    (is (= prior-sub-modes
           (frame-sub [:rf.xray.static.machines/sub-mode-by-id]))
        "sub-mode map unchanged by the second call")))

;; -------------------------------------------------------------------------
;; (2) THE CONSOLE ERROR — boot emits no frame-destroyed refusal
;; -------------------------------------------------------------------------

(deftest orchestrator-time-install-emits-no-refusal
  (testing "rf2-qw0o — registering the Xray handlers BEFORE any frame
            exists must not emit a promoted `:rf.error/frame-destroyed`.
            This is the exact pre-fix line: one per Xray-preloaded dev page
            load, and the whole of the Story feature-load gate's residual
            console-error count."
    (seed-prior-session!)
    (let [seen (capture-errors!)]
      (register-handlers!)
      (is (empty? (hydrate-refusals @seen))
          "no frame-destroyed refusal attributable to the Static Machines
           hydrate — the frame guard makes the pre-mount call a clean
           no-op instead of a dropped dispatch"))))

(deftest full-boot-emits-no-refusal
  (testing "rf2-qw0o — and the same holds across the whole boot, so the
            fix does not merely move the refusal to the first-mount hook"
    (seed-prior-session!)
    (let [seen (capture-errors!)]
      (boot!)
      (is (empty? (hydrate-refusals @seen))
          "no frame-destroyed refusal anywhere in the boot sequence")
      (is (= prior-selection
             (frame-sub [:rf.xray.static.machines/selected-id]))
          "and the restore still landed — silence here is not the dispatch
           having been deleted"))))

;; -------------------------------------------------------------------------
;; (3) the empty-storage case stays quiet
;; -------------------------------------------------------------------------

(deftest nothing-persisted-boots-clean-and-quiet
  (testing "rf2-qw0o — a first-ever load (both slots empty) leaves the
            slots at their registry defaults and dispatches nothing. Xray
            inspects the trace ring it would otherwise be writing a
            no-op boot event into."
    (is (nil? (persistence/load-selected-id))
        "precondition: the fixture cleared the selection slot")
    (let [seen (capture-errors!)]
      (boot!)
      (is (nil? (frame-sub [:rf.xray.static.machines/selected-id]))
          "no selection — registry default")
      (is (= {} (frame-sub [:rf.xray.static.machines/sub-mode-by-id]))
          "no sub-modes — registry default empty map")
      (is (empty? (hydrate-refusals @seen))
          "and nothing was refused"))))

(deftest sub-mode-only-slot-still-restores
  (testing "rf2-qw0o — the two slots are independent: a persisted
            sub-mode map with NO selection still hydrates (the guard is
            `either slot has content`, not `selection is present`)"
    (persistence/save-sub-mode-by-id! prior-sub-modes)
    (boot!)
    (is (nil? (frame-sub [:rf.xray.static.machines/selected-id]))
        "no selection was persisted, so none is restored")
    (is (= prior-sub-modes
           (frame-sub [:rf.xray.static.machines/sub-mode-by-id]))
        "the sub-mode map restores on its own")))
