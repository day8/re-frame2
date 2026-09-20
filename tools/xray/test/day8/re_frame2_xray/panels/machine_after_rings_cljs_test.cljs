(ns day8.re-frame2-xray.panels.machine-after-rings-cljs-test
  "CLJS-side wiring tests for Xray's Machine Inspector `:after`
  countdown rings (rf2-7hwwe).

  Covers:

    1. Registry wires the rings sub family + the tick/hover/now-ms
       event family.
    2. `:rf.xray/active-timers-for-focused-machine` composes trace
       buffer + the FOCUSED-EVENT record's machine + the target frame
       into an active-timers vector. Buffer-keyed since rf2-y8doi.23 —
       the now-keyed eviction is `overlay-tree`'s, and row (5e) is what
       pins it.
    3. `:rf.xray/now-ms` is driven by the timer-tick event AND by the
       test-only override slot.
    4. `:rf.xray/timer-hover` writes / clears the slot.
    5. The rings overlay component (rf2-uv1on xyflow Phase 2) projects
       the trace buffer into ring-specs + delegates positioning + paint
       to the machines-viz `AfterRingsOverlay` (renders nothing without
       active timers; one ring-spec per active timer; hover keys the
       timer-hover slot by node-id).
    6. The rAF tick loop's `needs-ticking?` gate stops the loop when
       no armed timers are present."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]
            [day8.re-frame2-xray.panels.machine-inspector :as machine-inspector]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            ;; rf2-y8doi.23 — `cancelled-retention-ms` is read from the
            ;; helper rather than retyped, so the row below cannot drift
            ;; from the window it is pinning.
            [day8.re-frame2-xray.panels.machine-after-rings-helpers
             :as rings-h]
            [day8.re-frame2-machines-viz.chart.overlays.after-rings
             :as mv-after-rings]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset (plain-atom +
  ;; `:all` tier, which already resets the trace-collector rings the old
  ;; init reset a SECOND time) into one owner; `:post-reset` stops any
  ;; armed rAF tick loop. (`trace-collector` stays required for seeding.)
  (xray-test-support/make-xray-runtime-fixture
    ;; `:async? true` is the map-form `cljs.test/async` shape, needed by
    ;; `hover-dispatch-lands-on-the-render-frame` below (rf2-k97c.3),
    ;; which polls a real async dispatch rather than racing the drain.
    {:async?     true
     :post-reset (fn [] (after-rings/stop-tick!))}))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.xray/set-registered-machines-override-for-test machines]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.xray/set-machine-definitions-override-for-test definitions]))

(defn- pin-now-ms! [ms]
  (rf/dispatch-sync [:rf.xray/set-now-ms-override-for-test ms]))

(defn- focus-machine!
  "Seed a one-epoch history whose cascade carries a
  `:rf.machine/transition` for `machine-id`, so
  `:rf.xray/machine-transitions-for-focused-event` answers a record
  targeting it and the rings sub folds for THAT machine.

  rf2-y8doi.23 — the rows below used to need nothing but
  `override-machines!`, because the rings sub read `:selected-id` off
  `:rf.xray/machine-inspector-data`, whose default is the
  ALPHABETICALLY-FIRST registered machine. That default is precisely the
  defect: the Dynamic panel has bound to the focused event's first
  transition record since rf2-y9xmf and reads no picker, so with two
  machines registered the rings described one machine while the chart
  drew another. The sub now reads the focused record, which is why every
  row that wants a ring has to say which machine is focused.

  The trace shape is the producer's — `:rf.machine/transition` with the
  `:before` / `:after` snapshot pair `commit-or-finalize` emits, which is
  what `transition-record-from-trace` reads. Same fixture shape the
  prev/next rows in `machine_inspector_view_cljs_test` use.

  `dispatch-id` is the seeded epoch's SETTLING event-bundle id, and the
  2-arity exists for exactly one row. `:rf.xray/focus` is the COMPOSED
  focus: in LIVE + unpaused mode `compose-focus` derives `:epoch-id`
  from the head event-bundle's settling epoch and ignores the stored
  slot. A row that also seeds a `:rf.event/dispatched` trace therefore
  gets a head bundle, and unless THIS epoch is the one that bundle
  settles into, `focused-epoch-record` answers nil and the panel sees no
  machine at all. `spine/epoch-id-for-event-bundle` makes the link
  through `common/dispatch-id-of-epoch`, which walks `:trace-events` for
  the first `:rf.trace/dispatch-id` — so the two ids have to agree."
  ([machine-id] (focus-machine! machine-id "d-1"))
  ([machine-id dispatch-id]
   (rf/dispatch-sync
     [:rf.xray/set-epoch-history-for-test
      [{:epoch-id 1
        :trace-events
        [{:id 1 :time 10 :operation :rf.machine/transition
          :tags {:machine-id           machine-id
                 :before               {:state :idle :data {}}
                 :after                {:state :authing :data {}}
                 :event                [:auth/submit]
                 :rf.trace/dispatch-id dispatch-id}}]}]])))

(defn- push-scheduled!
  [id machine-id state delay epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- push-fired!
  [id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/fired
     :tags {:machine-id machine-id
            :state state
            :epoch epoch
            :fired? true}}))

(defn- push-cancelled!
  [id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/cancelled
     :tags {:machine-id machine-id
            :state state
            :epoch epoch
            :reason :on-exit}}))

;; rf2-a28eo — FRAME-STAMPED fixtures. Read off the PRODUCERS rather than
;; composed by hand: `machines/timer.cljc`'s `:rf.machine.timer/scheduled`
;; + `/cancelled` emits and `machines/transition.cljc`'s `/fired`,
;; `/stale-after` and `/skipped-on-server` emits all stamp `:frame
;; frame-id`, and so does `machines/lifecycle_fx/registration.cljc`'s
;; `:rf.machine/transition`. The UNSTAMPED fixtures above omit it
;; DELIBERATELY: that is what a legacy replay looks like, and the
;; no-filter branch must still fold one — `active-timers-still-fold-when-
;; no-frame-can-be-resolved` below is what keeps that honest.

(defn- focus-machine-in!
  "[[focus-machine!]] with the focused transition trace carrying its
  owning FRAME, exactly as the producer's `:rf.machine/transition` emit
  stamps it. That stamp is the DISPLAY coordinate the rings sub resolves
  its frame scope from (rf2-a28eo) — the same record that already names
  which machine the chart is drawing."
  [frame machine-id]
  (rf/dispatch-sync
    [:rf.xray/set-epoch-history-for-test
     [{:epoch-id 1
       :trace-events
       [{:id 1 :time 10 :operation :rf.machine/transition
         :tags {:machine-id           machine-id
                :frame                frame
                :before               {:state :idle :data {}}
                :after                {:state :authing :data {}}
                :event                [:auth/submit]
                :rf.trace/dispatch-id "d-1"}}]}]]))

(defn- push-scheduled-in!
  [frame id machine-id state delay epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/scheduled
     :tags {:machine-id machine-id
            :frame frame
            :state state
            :delay delay
            :delay-source :literal
            :epoch epoch}}))

(defn- push-cancelled-in!
  [frame id machine-id state epoch]
  (trace-collector/seed-trace-for-test!
    {:id id :time id
     :operation :rf.machine.timer/cancelled
     :tags {:machine-id machine-id
            :frame frame
            :state state
            :epoch epoch
            :reason :on-exit}}))

(def ^:private fixture-definition
  {:initial :idle
   :states  {:idle    {:on    {:start :authing}
                       :after {5000 :timeout}}
             :authing {:on {:ok :done}}
             :timeout {:on {:retry :idle}}
             :done    {:final? true}}})

;; ---- (1) registry wiring -----------------------------------------------

(deftest registry-installs-rings-handlers
  (testing "register-xray-handlers! installs the rings sub + event family"
    (registry/register-xray-handlers!)
    (is (some? (rf.registrar/handler :sub :rf.xray/active-timers-for-focused-machine)))
    (is (some? (rf.registrar/handler :sub :rf.xray/now-ms)))
    (is (some? (rf.registrar/handler :sub :rf.xray/timer-hover)))
    (is (some? (rf.registrar/handler :event :rf.xray/timer-tick)))
    (is (some? (rf.registrar/handler :event :rf.xray/timer-hover))))
  (testing "rf2-e8330v — the test-only now-ms override is NOT installed by
            production registration; the test seam installs it"
    (registry/register-xray-handlers!)
    (is (nil? (rf.registrar/handler :event :rf.xray/set-now-ms-override-for-test))
        "production registration installs no -for-test ids")
    (xray-test-support/install-test-overrides!)
    (is (some? (rf.registrar/handler :event :rf.xray/set-now-ms-override-for-test))
        "install-test-overrides! installs the now-ms override event")))

;; ---- (2) active-timers composite ---------------------------------------

(deftest active-timers-empty-when-nothing-is-focused
  ;; rf2-y8doi.23 — was `active-timers-empty-when-no-selection`. There is
  ;; no selection to be empty of any more: the sub reads the focused
  ;; event's record, and an empty epoch history focuses nothing.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines! [])
    (is (= [] @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))))

(deftest active-timers-follow-the-focused-machine-not-the-alphabetical-first
  (testing "rf2-y8doi.23 — TWO machines registered, the timer armed on
            `:auth/main`, and the focused event targets `:checkout`. The
            rings sub must answer for the machine the CHART is drawing —
            `:checkout` — which has no armed timer, so ZERO rings.

            Before the fix it read `(:selected-id mi-data)`, and with no
            picker set that is `pick-selected`'s fallback: the first row
            of an ALPHABETICALLY sorted list, i.e. `:auth/main`. So the
            panel drew `:checkout`'s topology with `:auth/main`'s
            countdown ring swept over it — a ring belonging to a machine
            not on screen, keyed to a state node that happens to share a
            name or, worse, silently mis-anchored."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/main :checkout])
      (override-definitions! {:auth/main fixture-definition
                              :checkout  fixture-definition})
      (pin-now-ms! 2000)
      (push-scheduled! 1000 :auth/main :idle 5000 0)

      (focus-machine! :checkout)
      (is (= [] @(rf/subscribe [:rf.xray/active-timers-for-focused-machine]))
          "the focused machine has no armed timer, so no rings — even
           though `:auth/main` sorts first and does have one")

      (testing "and the control, so the zero is absence rather than a
                broken sub: focus the machine that DOES have the timer"
        (focus-machine! :auth/main)
        (let [active @(rf/subscribe
                        [:rf.xray/active-timers-for-focused-machine])]
          (is (= 1 (count active)))
          (is (= :auth/main (-> active first :machine-id))))))))

;; ---- (2b) rf2-a28eo — display scope vs the collector's target ----------

(deftest active-timers-scope-to-the-focused-frame-with-no-target-selected
  (testing "rf2-a28eo — ONE singleton actor `:m` instantiated in TWO
            frames, with `:rf.xray/target-frame` at its DEFAULT of nil
            (UNSELECTED, EP-0002). Frame A arms at 1000, frame B arms at
            1500, frame A cancels at 2000 — all `:idle`, epoch 0, delay
            5000, so the fold key `(machine-id, state, epoch, delay)` is
            IDENTICAL across the two frames and a singleton's actor-id is
            identical too.

            rf2-y8doi.23 gave the helper a frame filter, but the sub fed
            it `:rf.xray/target-frame` raw — and that slot is nil in the
            posture the panel OPENS in, which takes the no-filter branch.
            So the narrowing was off exactly when it was needed: frame A's
            `/cancelled` closed the record frame B's `/scheduled` had
            opened, and B's LIVE countdown was drawn as a grey crossed
            CANCELLED ring — a timer still armed in another runtime
            reported as torn down.

            The fix supplies a frame rather than removing the guard: the
            focused transition record carries its own `:frame-id`, so the
            coordinate that names the machine on screen names its INSTANCE
            too."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:m])
      (override-definitions! {:m fixture-definition})
      (push-scheduled-in! :rf/a 1000 :m :idle 5000 0)
      (push-scheduled-in! :rf/b 1500 :m :idle 5000 0)
      (push-cancelled-in! :rf/a 2000 :m :idle 0)

      (is (nil? @(rf/subscribe [:rf.xray/target-frame]))
          "the posture under test — an UNSELECTED collector target, which
           is EP-0002's default and not something this row arranges")

      (testing "focused on frame B, B's own timer is still ARMED"
        (focus-machine-in! :rf/b :m)
        (let [active @(rf/subscribe
                        [:rf.xray/active-timers-for-focused-machine])]
          (is (= 1 (count active)))
          (is (= :armed (-> active first :status))
              "pre-fix this read :cancelled — frame A's teardown closing
               frame B's live arm")
          (is (= 1500 (-> active first :armed-at))
              "and it is B's OWN arm at 1500, not A's at 1000")))

      (testing "and the control from the other side, so the row cannot
                pass by ignoring frames altogether: focused on frame A,
                A's timer IS cancelled — by its own teardown"
        (focus-machine-in! :rf/a :m)
        (let [active @(rf/subscribe
                        [:rf.xray/active-timers-for-focused-machine])]
          (is (= 1 (count active)))
          (is (= :cancelled (-> active first :status)))
          (is (= 1000 (-> active first :armed-at)))
          (is (= 2000 (-> active first :closed-at))))))))

(deftest active-timers-fall-back-to-the-target-frame-when-the-record-carries-none
  (testing "rf2-a28eo — `:rf.xray/target-frame` remains a LEGITIMATE
            input, demoted to the FALLBACK. When the focused record
            carries no `:frame-id` (a legacy replay predating the
            producer's `:frame` stamp) the collector's target still
            answers the display question.

            `:rf.xray/set-target-frame` re-seeds `:epoch-history` from the
            framework's per-frame ring, so it must be dispatched BEFORE
            the test override or it wipes the focused record."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:m])
      (override-definitions! {:m fixture-definition})
      (rf/dispatch-sync [:rf.xray/set-target-frame :rf/a])
      (focus-machine! :m)
      (push-scheduled-in! :rf/a 1000 :m :idle 5000 0)
      (push-scheduled-in! :rf/b 1500 :m :idle 5000 0)
      (push-cancelled-in! :rf/a 2000 :m :idle 0)

      (is (= :rf/a @(rf/subscribe [:rf.xray/target-frame]))
          "the collector target is selected; the focused record is the
           UNSTAMPED fixture, so it carries no :frame-id")
      (let [active @(rf/subscribe
                      [:rf.xray/active-timers-for-focused-machine])]
        (is (= 1 (count active)))
        (is (= :cancelled (-> active first :status))
            "scoped to A by the fallback — A's own arm, closed by A's
             own cancel")
        (is (= 1000 (-> active first :armed-at)))))))

(deftest active-timers-still-fold-when-no-frame-can-be-resolved
  (testing "rf2-a28eo — the no-filter branch is KEPT, not removed. With a
            legacy replay stamping no `:frame` anywhere, the focused
            record carries no `:frame-id` AND the collector target is
            unselected, so there is genuinely nothing to disambiguate
            against. Dropping every event would BLANK the rings on the
            default posture, which is the failure that branch exists to
            prevent — and it is still prevented."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (focus-machine!        :auth/login)
      (push-scheduled!       1000 :auth/login :idle 5000 0)
      (is (nil? @(rf/subscribe [:rf.xray/target-frame])))
      (let [active @(rf/subscribe
                      [:rf.xray/active-timers-for-focused-machine])]
        (is (= 1 (count active))
            "the unselected posture still renders a ring; nothing blanked")
        (is (= :armed (-> active first :status)))))))

(deftest active-timers-folds-scheduled-into-armed
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [active @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])]
      (is (= 1 (count active)))
      (is (= :armed (-> active first :status)))
      (is (= :idle  (-> active first :state)))
      (is (= 6000   (-> active first :fires-at))))))

(deftest active-timers-drops-fired
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (empty? @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])))))

;; ---- (3) now-ms surface ------------------------------------------------

(deftest now-ms-override-overrides-tick
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/timer-tick 10000])
    (is (= 10000 @(rf/subscribe [:rf.xray/now-ms])))
    (pin-now-ms! 9999)
    (is (= 9999 @(rf/subscribe [:rf.xray/now-ms]))
        "override slot wins over the tick-bumped value")))

(deftest timer-tick-event-writes-now-ms
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/timer-tick 12345])
    (is (= 12345 @(rf/subscribe [:rf.xray/now-ms])))))

;; ---- (4) timer-hover ---------------------------------------------------

(deftest timer-hover-writes-and-clears
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (is (nil? @(rf/subscribe [:rf.xray/timer-hover])))
    (rf/dispatch-sync [:rf.xray/timer-hover {:machine-id :auth/login
                                              :state :idle
                                              :epoch 0}])
    (is (= {:machine-id :auth/login :state :idle :epoch 0}
           @(rf/subscribe [:rf.xray/timer-hover])))
    (rf/dispatch-sync [:rf.xray/timer-hover nil])
    (is (nil? @(rf/subscribe [:rf.xray/timer-hover])))))

;; ---- (5) overlay view (rf2-uv1on xyflow Phase 2) -----------------------
;;
;; Post-migration the Xray overlay is the DATA owner: it projects the
;; trace buffer into ring-specs + delegates positioning + paint to the
;; machines-viz `AfterRingsOverlay`, which walks the xyflow node DOM.
;; Per rf2-fkpuv the Xray overlay returns
;; `[:div {... :style {:display "contents"}}
;;   [mv-after-rings/AfterRingsOverlay {...}]]` (or nil when no active
;; timers) — the `display: contents` wrapper gives Spec 006
;; §Source-coord annotation a DOM root to stamp `data-rf2-source-coord`
;; on. The helpers below dig past the wrapper to the delegated head +
;; props. The DOM-walk geometry is exercised by the machines-viz
;; overlay's own suite + the geometry helper's JVM tests. (The old
;; SVG-positioned-graph + viewport-transform tests are gone with the
;; elk renderer.)

(defn- overlay-tree
  "Drive `after-rings/overlay-tree` with EXACTLY the reads the
  `AfterRingsOverlay` boundary makes — same four query vectors, same
  order, and the frame taken the same way — so what these rows assert on
  is the tree the mounted boundary actually renders.

  rf2-k97c.3 — the rows below used to call `(overlay-tree)`
  directly. A migrated view is a real React component and cannot be
  called, so the markup moved into `overlay-tree`, which can. `:as-child`
  is left at its `identity` default so the delegated machines-viz child
  stays HICCUP here and every assertion below reads exactly what it read
  before; the boundary passes `reagent.core/as-element` instead, and the
  new `machine_after_rings_fresco_boundary_dom_cljs_test` is what proves
  that half against a real React commit.

  Call inside `(rf/with-frame :rf/xray ...)`, as every row here does.
  `extra` is merged last, so a row can vary ONE input (`:as-child`)
  without drifting the other five away from the boundary's."
  ([] (overlay-tree nil))
  ([extra]
   (after-rings/overlay-tree
     (merge
       {:timers         @(rf/subscribe [:rf.xray/active-timers-for-focused-machine])
        :live-now       @(rf/subscribe [:rf.xray/now-ms])
        :scrub          @(rf/subscribe [:rf.xray/machine-scrubber-position])
        :focused-detail @(rf/subscribe [:rf.xray/focused-event-bundle-detail])
        :frame          (rf/current-frame-id)}
       extra))))

(defn- delegated-child
  "Pull the inner `[mv-after-rings/AfterRingsOverlay {...}]` hiccup
  past the `display: contents` wrapper `:div` (rf2-fkpuv)."
  [tree]
  (when (and (vector? tree) (= :div (first tree)))
    (nth tree 2)))

(defn- delegated-props
  "Pull the props map the Xray overlay hands to the machines-viz
  overlay. The Xray overlay returns
  `[:div {...} [mv-after-rings/AfterRingsOverlay {props}]]`; this
  digs past the wrapper to the props."
  [tree]
  (some-> tree delegated-child second))

(deftest overlay-returns-nil-with-no-active-timers
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 1000)
    (is (nil? (overlay-tree)))))

(deftest overlay-delegates-one-ring-spec-per-active-timer
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [tree  (overlay-tree)
          child (delegated-child tree)
          props (delegated-props tree)
          specs (:ring-specs props)]
      (is (= :div (first tree))
          "root is a `display: contents` wrapper :div so Spec 006
           §Source-coord annotation has a DOM root to stamp (rf2-fkpuv)")
      (is (= mv-after-rings/AfterRingsOverlay (first child))
          "delegates to the machines-viz xyflow overlay")
      (is (= 1 (count specs)))
      (is (= "idle" (-> specs first :node-id))
          "node-id is the string the overlay queries the DOM for")
      (is (= "rf-xray-machine-inspector-after-rings-overlay" (:testid props)))
      (is (= 2000 (:tick props))
          "now-ms threads through as :tick so the overlay re-measures per frame")
      (is (fn? (:on-hover props)))
      (is (fn? (:on-leave props))))))

(deftest overlay-stops-rendering-fired-timers
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 7000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (push-fired!     6000 :auth/login :idle 0)
    (is (nil? (overlay-tree))
        "fired timers are filtered out of the active projection — the
         whole overlay drops out")))

(deftest overlay-delegates-specs-for-multiple-concurrent-timers
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle    5000 0)
    (push-scheduled! 1500 :auth/login :authing 3000 0)
    (let [specs (-> (overlay-tree) delegated-props :ring-specs)]
      (is (= 2 (count specs)))
      (is (= #{"idle" "authing"} (set (map :node-id specs)))))))

;; ---- (5e) cancelled-ring retention (rf2-y8doi.23) ----------------------

(deftest overlay-evicts-a-cancelled-ring-once-its-retention-window-passes
  (testing "rf2-y8doi.23 — a `:cancelled` ring is a MOMENTARY fade +
            diagonal cross. It had no retention at all: the projection
            returned every cancelled record the buffer held, so a state
            the operator entered and left left a PERMANENT grey crossed
            ring, one per visit — and the machines-viz overlay keys a ring
            by its `:node-id` (`^{:key node-id}`), so all of them sat
            under ONE React key.

            The eviction runs in `overlay-tree`, against the SAME anchor
            `resolve-now-ms` hands the ring geometry — so a retrospective
            chart ages its rings at the instant it is frozen at rather
            than at the live clock."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (focus-machine!        :auth/login)
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      (push-cancelled! 2000 :auth/login :idle 0)

      (pin-now-ms! (+ 2000 rings-h/cancelled-retention-ms))
      (let [specs (-> (overlay-tree) delegated-props :ring-specs)]
        (is (= 1 (count specs)) "still on screen at the retention boundary")
        (is (true? (-> specs first :cancelled?))
            "and it renders as the crossed-out treatment"))

      (pin-now-ms! (+ 2000 rings-h/cancelled-retention-ms 1))
      (is (nil? (overlay-tree))
          "one ms later it is gone, and with no other ring the whole
           overlay layer drops out")

      (pin-now-ms! 600000)
      (is (nil? (overlay-tree))
          "and it never returns — bounded, not permanent"))))

(deftest overlay-on-hover-keys-timer-hover-by-node-id
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (focus-machine!        :auth/login)
    (pin-now-ms! 2000)
    (push-scheduled! 1000 :auth/login :idle 5000 0)
    (let [props    (-> (overlay-tree) delegated-props)
          on-hover (:on-hover props)
          on-leave (:on-leave props)
          spec     (-> props :ring-specs first)]
      ;; The overlay hands the bearing node-id back; the host re-resolves
      ;; the timer identity tuple for the hover slot. The dispatch is
      ;; async (production path), so rather than race the router drain we
      ;; assert (a) the spec carries the identity tuple the resolution
      ;; keys on, and (b) the callbacks are wired + a known / unknown
      ;; node-id is handled without throwing.
      (is (= {:machine-id :auth/login :state :idle :epoch 0}
             (select-keys spec [:machine-id :state :epoch]))
          "spec carries the (machine-id, state, epoch) tuple the hover
           handler re-resolves from the bearing node-id")
      (is (fn? on-hover))
      (is (fn? on-leave))
      ;; Exercise both branches — known + unknown node-id — to pin the
      ;; callbacks don't throw on either path. (Slot-value assertions
      ;; live in the dedicated dispatch-sync test above; the production
      ;; callback dispatches async, so racing the router drain here would
      ;; be flaky.)
      (is (nil? (do (on-hover "ghost") nil)) "unknown node-id is a no-op")
      (is (nil? (do (on-hover "idle") (on-leave "idle") nil))
          "known node-id hover + leave run cleanly"))))

;; ---- (5b) retro now-ms anchor (rf2-8i1tg3 · xray/003 §M.2) -------------
;;
;; "Retro mode (scrubber-driven): the ring is static at the elapsed-
;; fraction the timer had reached at the focused-cascade's timestamp."
;; Before this fix the view fed the LIVE `now-ms` into the ring
;; projection regardless of `machine-scrubber-position` — the scrubber
;; only gated whether the rAF loop kept ticking, so leaving `:present`
;; froze the ring wherever the live clock last sat instead of anchoring
;; to the cascade the operator is looking at.

(defn- dispatch-trace-ev
  "Minimal :rf.event/dispatched trace event so the L2 projector builds
  ONE focusable event-bundle carrying a real `:dispatched :time` —
  mirrors the equivalent helper in `shell_cljs_test`."
  [id event-vec time-ms]
  {:id        id
   :time      time-ms
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.event/v          event-vec
               :frame               :rf/default
               :rf.trace/dispatch-id id}})

(deftest overlay-retro-mode-anchors-tick-to-focused-cascade-timestamp
  (testing "rf2-8i1tg3 — scrubber-position leaving :present anchors the
            ring's :tick to the FOCUSED CASCADE's dispatched time, not
            the stale live clock"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      ;; Settle the seeded epoch into the SAME event-bundle the
      ;; dispatched trace below mints (`:rf.trace/dispatch-id 1`), so the
      ;; composed focus's LIVE head-tracking lands on this epoch rather
      ;; than on one that does not exist. See `focus-machine!`.
      (focus-machine!        :auth/login 1)
      (pin-now-ms! 9999)
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      ;; Focus defaults to the head event-bundle when nothing has
      ;; explicitly focused yet — seeding ONE dispatched event gives
      ;; the composite a known, non-live anchor timestamp.
      (trace-collector/seed-trace-for-test! (dispatch-trace-ev 1 [:some/event] 4242))
      (rf/dispatch-sync [:rf.xray/set-scrubber-position 3])
      (let [props (-> (overlay-tree) delegated-props)]
        (is (= 4242 (:tick props))
            "RETRO tick anchors to the focused cascade's dispatched
             time (4242) — NOT the pinned live now-ms (9999), which is
             the exact rf2-8i1tg3 regression"))
      (rf/dispatch-sync [:rf.xray/set-scrubber-position :present])
      (let [props (-> (overlay-tree) delegated-props)]
        (is (= 9999 (:tick props))
            "returning to :present restores the live clock as the tick
             anchor")))))

;; ---- (5c) the substrate seam (rf2-k97c.3) ------------------------------

(defn- crossed-by-identity?
  "True when `props` is reachable from React element `el`'s props object
  WITHOUT conversion — as the same object, at a slot or inside a vector
  or array at a slot.

  Written to the QUESTION rather than to one Reagent version's field
  layout, and that is the point: the first draft of this asserted
  `(aget (.-argv (.-props el)) 1)`, copied from
  `tools/machines-viz`'s `react_chart_cljs_test`, and read nil. The
  copy was wrong in a way worth recording, because the two cases look
  identical and are not — `react_chart.cljs` builds its bridge props by
  hand as `#js {:argv #js [...]}`, a JS ARRAY, so `aget` is right there;
  `reagent.core/as-element` carries the hiccup vector itself, a CLJS
  PersistentVector, on which `aget` answers nil rather than erroring.
  Probing for the object instead of for its address survives both."
  [el props]
  (let [p (.-props el)]
    (boolean
      (when (some? p)
        (some (fn [v]
                (or (identical? v props)
                    (and (vector? v) (boolean (some #(identical? % props) v)))
                    (and (array? v)
                         (boolean (some #(identical? % props) (array-seq v))))))
              (array-seq (js/Object.values p)))))))

(deftest as-child-lifts-the-reagent-delegate-to-a-react-element
  (testing "rf2-k97c.3 — the machines-viz `AfterRingsOverlay` is a REAGENT
            component, so a Fresco body can neither take it as a hiccup
            head (a plain function in head position is a loud error,
            HD-016) nor CALL it (it answers a Reagent CLASS, not hiccup —
            which is why the migration's usual `(mini v 40)` repair does
            not apply). `overlay-tree`'s `:as-child` is the seam:
            `identity` leaves the delegate as hiccup for a Reagent parent
            and for every row above, while `reagent.core/as-element`
            answers a React element, which Fresco's component ABI admits
            as a legal child anywhere.

            The row that matters is the last one: `as-element` carries the
            hiccup vector itself as Reagent's `argv`, so the CLJS props map
            reaches machines-viz BY IDENTITY. That is the whole reason this
            route was taken over `[:> ...]`, whose host walk camelCases the
            top-level key and `clj->js`es a collection value — under which
            `:ring-specs` would arrive as `:ringSpecs` holding a JS array
            and the overlay would destructure nil."
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (focus-machine!        :auth/login)
      (pin-now-ms! 2000)
      (push-scheduled! 1000 :auth/login :idle 5000 0)
      (let [hiccup-child  (delegated-child (overlay-tree))
            props         (second hiccup-child)
            ;; ONE tree, converted two ways. Two separate `overlay-tree`
            ;; calls would answer two EQUAL but distinct props maps, and
            ;; the identity claim below would be false for a reason that
            ;; has nothing to do with the crossing.
            element-child (r/as-element hiccup-child)
            ;; ...and the parameter really is wired through `overlay-tree`,
            ;; which is the half the boundary depends on.
            via-param     (delegated-child (overlay-tree {:as-child r/as-element}))]
        (is (vector? hiccup-child)
            "the default :as-child leaves the delegate as hiccup")
        (is (= mv-after-rings/AfterRingsOverlay (first hiccup-child))
            "and it is the machines-viz overlay")
        (is (not (vector? via-param))
            ":as-child is threaded through overlay-tree, not ignored")
        (is (some? (.-props element-child))
            "as-element answers a real React element carrying React props")
        (is (crossed-by-identity? element-child props)
            "THE CLAIM: the CLJS props map reaches the element AS THE SAME
             OBJECT. Reagent carries the hiccup vector itself and never
             converts it, so :ring-specs stays a CLJS vector of CLJS maps.
             A `[:> ...]` crossing would fail this — its host walk
             camelCases the top-level key and `clj->js`es the value")))))

;; ---- (5d) deferred hover dispatch routing (rf2-nesy9 / rf2-k97c.3) -----
;;
;; Sibling in shape to `reactive_panel_disclosure_dispatch_routing_cljs_test`:
;; pluck the deferred handler off the rendered tree and fire it OUTSIDE any
;; `with-frame`, reproducing the browser reality that a mouseenter fires
;; AFTER render commits and the ambient frame scope has unwound.
;;
;; The rows in (5) above deliberately declined this claim — "the production
;; callback dispatches async, so racing the router drain here would be
;; flaky" — and asserted only that the callbacks are wired and don't throw.
;; Polling rather than racing makes the claim available, and it is the one
;; the migration most needs: `reg-view` used to INJECT a frame-aware
;; `dispatch`, `defview` binds no name inside a body, and this row is what
;; says the replacement targets the same frame.

(deftest hover-dispatch-lands-on-the-render-frame
  (testing "rf2-nesy9 — the hover dispatch lands on the frame the TREE
            named, and does NOT leak to :rf/default, even though it fires
            long after the render extent has unwound. That is the
            'carrying' half of the boundary contract: the dispatcher holds
            an explicit {:frame ...}, so it never has to resolve a frame
            ambiently at click time."
    (setup-xray-frame!)
    (let [on-hover (rf/with-frame :rf/xray
                     (override-machines!    [:auth/login])
                     (override-definitions! {:auth/login fixture-definition})
                     (focus-machine!        :auth/login)
                     (pin-now-ms! 2000)
                     (push-scheduled! 1000 :auth/login :idle 5000 0)
                     (:on-hover (delegated-props (overlay-tree))))]
      (is (fn? on-hover) "the delegate is handed an :on-hover callback")
      ;; Frameless, exactly as a real mouseenter is.
      (on-hover "idle")
      (async done
        (-> (rf.test-support/poll-until
              #(some? (:rings/hover (rf.frame/frame-app-db-value :rf/xray)))
              {:label      ":rings/hover appears on :rf/xray after a frameless hover"
               :timeout-ms 1000})
            (.then (fn [_]
                     (is (= {:machine-id :auth/login :state :idle :epoch 0}
                            (:rings/hover (rf.frame/frame-app-db-value :rf/xray)))
                         "the deferred hover landed on :rf/xray carrying the
                          full (machine-id, state, epoch) identity tuple")
                     (is (nil? (:rings/hover (rf.frame/frame-app-db-value :rf/default)))
                         ":rf/default was NOT polluted — no bare-dispatch leak")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

;; ---- (6) frame isolation ----------------------------------------------

(deftest now-ms-lives-on-xray-frame
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/timer-tick 42]))
  (let [xray-db   (rf.frame/frame-app-db-value :rf/xray)
        default-db (rf.frame/frame-app-db-value :rf/default)]
    (is (= 42 (:rings/now-ms xray-db)))
    (is (nil? (:rings/now-ms default-db))
        "host frame is untouched")))
