(ns re-frame.story.story-xray-wiring-cljs-test
  "Wiring contract: one variant-selection edge seeds BOTH Xray's
  trace-buffer AND its target-frame.

  Xray on Story's RHS shows the selected variant only when both halves
  work on the same edge: the variant's `:setup` cascade lands in Xray's
  buffer (Xray's trace-bus wiring) and `:rf.xray/target-frame` re-orients
  to the variant (Story's selection-watcher). The unit tests on each side
  pin one half each — Xray's with synthetic bus events, Story's with
  empty `:setup` variants. An empty buffer or a stale target-frame is an
  empty RHS.

  Drives the production `selection-watcher` through the multi-frame
  harness, whose `register-trace-collector!` fans REAL variant dispatches
  through the trace-bus as the preload does. The bus is mirrored into
  `:trace-buffer` synchronously (production coalesces on `next-tick`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.shell :as rf.story.ui.shell]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as xray-e2e]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; The counter handlers register at test time from THIS namespace, so
;; scoping the story's `:images` to it keeps the variant frames off the
;; co-loaded node-test store, whose same-`[kind id]` registrations collide.
(def ^:private app-image
  (rf/image
    {:id        :story-xray-wiring/app
     :select-ns {:include ["re-frame.story.story-xray-wiring-cljs-test"]}}))

;; ---- helpers -------------------------------------------------------------

(defn- install-selection-watcher!
  "Install the shell's production `selection-watcher` — the callback
  `mount-shell!` installs at boot, which runs `ensure-variant-frame!` and
  dispatches `:rf.xray/set-target-frame`."
  []
  ((deref #'rf.story.ui.shell/selection-watcher)))

(defn- remove-selection-watcher!
  []
  ((deref #'rf.story.ui.shell/remove-selection-watcher!)))

(defn- register-counter-host!
  "Register the counter event the variant's `:setup` dispatches, so the
  selection produces a real app-db write, epoch and cascade."
  []
  (rf/reg-event :counter/initialise
    (fn [_ _event] {:db {:counter/value 5}})))

;; Variant-id IS the frame-id per `re-frame.story.frames`.
(def ^:private variant-id :story.counter/loaded)

(def ^:private error-capture-id ::error-capture)

(defn- capture-errors!
  "Attach an always-on `:errors` listener collecting records into an atom."
  []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! error-capture-id (fn [r] (swap! seen conj r)))
    seen))

(defn- frame-destroyed-records [seen]
  (rf.error-emit/unregister-error-listener! error-capture-id)
  (filterv #(= :rf.error/frame-destroyed (:error %)) @seen))

(defn- flush-xray-queue!
  "Drain whatever `:rf/xray`'s router is holding without waiting on the host
  task scheduler. `dispatch-sync!` seeds at the FRONT of the queue and then
  runs the drain loop to fixed point, so a benign chrome event flushes an
  async dispatch already enqueued behind it."
  []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/clear-reset-flash])))

(defn- register-counter-story! []
  (register-counter-host!)
  (rf.story/reg-story :story.counter
    {:doc    "Counter parent story for the Story-Xray wiring contract test."
     :images [app-image]})
  (rf.story/reg-variant variant-id
    {:doc    "Counter seeded at 5 — its :setup cascade must land in
              Xray's trace-buffer on selection."
     :setup [[:counter/initialise]]}))

;; ---- the conjunction contract -------------------------------------------

(deftest switching-variants-re-seeds-both-slots
  (testing "every selection edge — the first and each switch — re-orients
            :target-frame AND lands that variant's :setup cascade in Xray"
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories
       (fn []
         (rf/reg-event :counter/initialise
           (fn [_ _event] {:db {:counter/value 5}}))
         (rf/reg-event :counter/seed-ten
           (fn [_ _event] {:db {:counter/value 10}}))
         (rf.story/reg-story :story.counter {:images [app-image]})
         (rf.story/reg-variant :story.counter/loaded
           {:setup [[:counter/initialise]]})
         (rf.story/reg-variant :story.counter/ten
           {:setup [[:counter/seed-ten]]}))}
      (fn []
        (install-selection-watcher!)
        (try
          (rf.story.test-helpers.e2e-multi-frame/select-variant! :story.counter/loaded)
          (xray-e2e/sync-xray-trace-mirror!)
          (rf/with-frame :rf/xray
            (is (= :story.counter/loaded
                   @(rf/subscribe [:rf.xray/target-frame]))
                "first selection orients Xray on :story.counter/loaded"))
          (is (contains? (into #{} (map :event) (xray-e2e/xray-cascades))
                         [:counter/initialise])
              "the first variant's :setup cascade is observable in Xray")
          ;; `set-target-frame` re-reads the variant frame's epoch ring, the
          ;; slot the App-DB Diff / Views panels render against.
          (rf/with-frame :rf/xray
            (is (pos? (count @(rf/subscribe [:rf.xray/epoch-history])))
                ":epoch-history re-seeds from the variant frame's epoch ring"))

          (rf.story.test-helpers.e2e-multi-frame/select-variant! :story.counter/ten)
          (xray-e2e/sync-xray-trace-mirror!)
          (rf/with-frame :rf/xray
            (is (= :story.counter/ten
                   @(rf/subscribe [:rf.xray/target-frame]))
                "switch re-orients target-frame to :story.counter/ten"))
          (let [events (into #{} (map :event) (xray-e2e/xray-cascades))]
            (is (contains? events [:counter/seed-ten])
                "the second variant's `[:counter/seed-ten]` cascade is
                 observable in Xray after the switch"))
          (finally
            (remove-selection-watcher!)))))))

;; ---- the boot race -------------------------------------------------------

(deftest a-selection-edge-with-xrays-frame-absent-still-lands
  (testing "the shell re-orients through Xray's host-facing facade
            (`core/set-target-frame!`), which SEATS `:rf/xray` before it
            dispatches, so a selection edge landing while Xray's frame is
            absent still lands its intent.

            Xray seats `:rf/xray` from its preload's readiness loop on a
            50ms poll, and Story's boot selects a variant before the next
            tick, so every page's first selection falls in that window. A
            hand-rolled `(rf/with-frame :rf/xray (rf/dispatch-sync …))`
            would recover-but-emit `:rf.error/frame-destroyed` and drop the
            re-orientation. Destroying the harness's frame reproduces that
            window without the poll."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-counter-story!}
      (fn []
        (install-selection-watcher!)
        (try
          (rf/destroy-frame! :rf/xray)
          (is (nil? (rf.frame/frame :rf/xray))
              "precondition: Xray's frame is gone, as it is before the
               preload's seat poll fires")
          (let [seen (capture-errors!)]
            (rf.story.test-helpers.e2e-multi-frame/select-variant! variant-id)
            (is (some? (rf.frame/frame :rf/xray))
                "the selection edge seated Xray's frame through the facade")
            (flush-xray-queue!)
            (is (empty? (frame-destroyed-records seen))
                "and nothing recovered-but-emitted — no
                 `:rf.error/frame-destroyed` reaches the console"))
          (rf/with-frame :rf/xray
            (is (= variant-id @(rf/subscribe [:rf.xray/target-frame]))
                "the re-orientation landed rather than being dropped"))
          (finally
            (remove-selection-watcher!)))))))
