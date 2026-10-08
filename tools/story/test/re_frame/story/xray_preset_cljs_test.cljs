(ns re-frame.story.xray-preset-cljs-test
  "The per-story Xray preset and the cross-host bridges, asserted against
  Xray's REAL config slot, `:active-filters` slot, matcher and tab.

  The pure resolution / lowering surface runs on the JVM in
  `re-frame.story.xray-preset-test`. This lane has no document, so
  `keybinding/attach!` never installs a listener here; the listener half
  of `wire-cross-host!` is asserted in
  `re-frame.story.xray-preset-dom-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.filters.typed-predicates :as xray-typed]
            [day8.re-frame2-xray.mount :as xray-mount]
            [day8.re-frame2-xray.registry :as xray-registry]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.config :as rf.story.config]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.xray-preset :as rf.story.xray-preset]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Self-sufficient setup: the namespace seats its own adapter and canonical
;; vocabulary, so it does not pass only because a neighbour called `init!`.

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ---- Xray-side helpers ---------------------------------------------------
;;
;; `reset-for-test!` clears the registry's idempotency sentinel, which
;; `rf.registrar/clear-all!` would otherwise leave set over an emptied
;; registrar, so the handlers would silently not re-register.

(defn- mount-xray!
  "Model what Xray's `mount-<panel>!` does to the world: register the
  handler set and the `:rf/xray` frame. Does NOT drain the pending
  filter slot."
  []
  (xray-registry/reset-for-test!)
  (xray-registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  nil)

(defn- install-xray-frame!
  "`mount-xray!` plus a clean slate: nothing parked, no pills."
  []
  (mount-xray!)
  (rf.story.xray-preset/flush-pending-filters!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out []}]))
  nil)

(defn- active-filters
  "Read Xray's live `:active-filters` slot through its own sub."
  []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/active-filters])))

(defn- reg-filtered-variant!
  "Register a story + variant carrying `preset` as its `:xray` slot."
  [variant-id preset]
  (rf.story/reg-story :story.filt
    {:doc "filters" :component :Some.view})
  (rf.story/reg-variant variant-id
    {:doc "v" :xray preset})
  variant-id)

(defn- bundle
  "Minimal event-bundle shaped as Xray's matcher reads it."
  [event-id]
  {:event [event-id {}]})

;; ---- :filters ------------------------------------------------------------
;;
;; Asserted on the REAL slot and a real matcher outcome, never on a
;; `configure!` shim, so an inert preset cannot read as covered.

(deftest filters-preset-lands-on-live-active-filters-slot
  (testing "a {:out [:app/noise]} preset lands as Xray's pill shape on the
            live slot and on the config seed `filters/hydrate!` reads"
    (install-xray-frame!)
    (xray-config/set-filter-seed! nil)
    (try
      (rf.story.xray-preset/apply-preset!
        (reg-filtered-variant! :story.filt/out {:filters {:out [:app/noise]}}))
      (is (= {:in [] :out [{:pattern :app/noise}]} (active-filters)))
      (is (= {:in [] :out [{:pattern :app/noise}]} (xray-config/get-filter-seed)))
      (testing "and Xray's matcher reads the landed pill as filtering exactly that event"
        (let [pill (first (:out (active-filters)))]
          (is (true? (xray-typed/event-bundle-matches-pill? (bundle :app/noise) pill)))
          (is (false? (xray-typed/event-bundle-matches-pill? (bundle :app/signal) pill)))))
      (finally (xray-config/set-filter-seed! nil)))))

(deftest filters-preset-parks-then-flushes-once-when-frame-arrives
  (testing "a preset resolved before the RHS panel's first mount created
            :rf/xray parks and lands on the embed's post-mount flush. The
            flush drains the park, so a later panel mount does not re-apply
            pills the user has since cleared through the ribbon."
    (install-xray-frame!)
    (swap! rf.frame/frames dissoc :rf/xray)
    (is (nil? (rf.frame/frame :rf/xray))
        "precondition: the Xray frame does not exist yet")
    (rf.story.xray-preset/apply-preset!
      (reg-filtered-variant! :story.filt/park {:filters {:out [:app/noise]}}))
    (mount-xray!)
    (rf.story.xray-preset/flush-pending-filters!)
    (is (= {:in [] :out [{:pattern :app/noise}]} (active-filters)))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out []}]))
    (rf.story.xray-preset/flush-pending-filters!)
    (is (= {:in [] :out []} (active-filters))
        "the user's cleared slot survives the second mount")))

(deftest only-a-present-filters-map-replaces-the-users-pills
  (testing "a present but empty :filters clears the pills (the story is
            deliberately unfiltered); a preset with no :filters key leaves
            the user's own ribbon pills alone"
    (install-xray-frame!)
    (doseq [[variant-id preset out] [[:story.filt/empty     {:filters {:in [] :out []}} []]
                                     [:story.filt/nofilters {:panel :trace}             [{:pattern :user/pill}]]]]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/hydrate-filters {:in [] :out [{:pattern :user/pill}]}]))
      (rf.story.xray-preset/apply-preset! (reg-filtered-variant! variant-id preset))
      (is (= out (:out (active-filters))) (str variant-id)))))

;; ---- :panel --------------------------------------------------------------
;;
;; Dispatching an unregistered id raises nothing, so this reads the OUTCOME
;; through Xray's own `:rf.xray/selected-tab` sub. The redef routes the
;; preset's async dispatch through `dispatch-sync` in the frame it named.

(defn- selected-tab
  "Read Xray's live selected tab through its own sub."
  []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/selected-tab])))

(deftest panel-preset-selects-the-xray-tab
  (testing "a {:panel :trace} preset moves Xray's selected tab off its
            :epoch default and onto :trace"
    (install-xray-frame!)
    (is (= :epoch (selected-tab)) "baseline: Xray's default tab")
    (let [vid (reg-filtered-variant! :story.filt/panel {:panel :trace})]
      (with-redefs [rf/dispatch (fn [ev & [opts]]
                                  (rf/with-frame (:frame opts)
                                    (rf/dispatch-sync ev))
                                  nil)]
        (rf.story.xray-preset/apply-preset! vid))
      (is (= :trace (selected-tab))
          "the preset's :panel reached a handler Xray actually registers"))))

;; ---- project-root bridge -------------------------------------------------

(deftest propagate-project-root-reaches-xray
  (testing "Story's configure! bridges its project root into Xray's own config slot"
    (rf.story/configure! {:rf.story/project-root "/home/me/code/my-app"})
    (try
      (is (= "/home/me/code/my-app" (xray-config/get-project-root)))
      (finally
        (rf.story/configure! {:rf.story/project-root nil})
        (xray-config/set-project-root! nil)))))

;; ---- static export: the preset drive boundary ----------------------------
;;
;; A deep link arrives with its variant already selected, so it reaches
;; `wire-cross-host!` + `on-variant-selected!` without passing the shell's
;; selection-watcher. These tests pin the static-mode boundary at the
;; namespace entry points (`tools/story/spec/013-Static-Build.md`
;; §Static-mode runtime semantics). Each runs a DEV control, because an
;; over-broad guard that kills the feature in dev too is the failure this
;; class of guard actually produces.

(deftest static-export-wire-cross-host-touches-no-xray-config
  (testing "wire-cross-host! is inert under static-mode?, read off Xray's
            REAL config slot with no shims"
    (xray-config/set-keybinding-enabled! true)
    (try
      (with-redefs [rf.story.config/static-mode? true]
        (rf.story.xray-preset/wire-cross-host!)
        (is (true? (xray-config/keybinding-attach-enabled?))
            "static: the slot is UNTOUCHED — no bridge fired"))
      (rf.story.xray-preset/wire-cross-host!)
      (is (false? (xray-config/keybinding-attach-enabled?))
          "dev control: the bridge still flips the slot to false")
      (finally
        (xray-config/set-keybinding-enabled! true)))))

(deftest static-export-mount-time-preset-drives-nothing
  (testing "under static-mode? the mount-time entry point a preset-bearing
            deep link reaches attempts none of open / panel / filters"
    (reg-filtered-variant! :story.filt/deep-link
                           {:open?   true
                            :panel   :epoch
                            :filters {:out [:app/noise]}})
    (let [opened     (atom 0)
          dispatched (atom [])
          configured (atom [])
          ;; Spies at the Xray surfaces the preset drives, so the dev
          ;; control needs no mounted Xray.
          with-spies (fn [f]
                       (with-redefs [xray-mount/open!       (fn [& _] (swap! opened inc) nil)
                                     rf/dispatch            (fn [ev & _] (swap! dispatched conj ev) nil)
                                     xray-config/configure! (fn [opts] (swap! configured conj opts) nil)]
                         (f)))]
      (try
        (with-spies #(rf.story.xray-preset/on-variant-selected! :story.filt/deep-link))
        (is (= 1 @opened)
            "dev control: :open? true reached Xray's mount/open!")
        (is (some #(= :rf.xray/select-tab (first %)) @dispatched)
            "dev control: :panel reached the :rf.xray/select-tab dispatch")
        (is (some #(contains? % :rf.xray/filters) @configured)
            "dev control: :filters reached Xray's configure! seed")
        (let [open-before       @opened
              dispatched-before (count @dispatched)
              configured-before (count @configured)]
          (with-spies #(with-redefs [rf.story.config/static-mode? true]
                         (rf.story.xray-preset/on-variant-selected! :story.filt/deep-link)
                         ;; apply-preset! too, so no caller can route around the guard
                         (rf.story.xray-preset/apply-preset! :story.filt/deep-link)))
          (is (= open-before @opened)
              "static: mount/open! was NOT reached")
          (is (= dispatched-before (count @dispatched))
              "static: no :rf.xray/* event was dispatched")
          (is (= configured-before (count @configured))
              "static: Xray's configure! was not seeded"))
        (finally
          ;; The dev control parked `:filters` (no :rf/xray frame here);
          ;; seating the frame drains the park for the next test.
          (install-xray-frame!))))))
