(ns re-frame.machine-after-hydration-rearm-cljs-test
  "rf2-jqvgp — a machine hydrated in an `:after`-bearing state gets its
  client timer back.

  The server suppresses `:after` wall-clock timers (Spec 005 §SSR mode)
  and a host-clock handle cannot ride the wire, so the hydration payload
  carries a snapshot with the right `:state`, `:data` and
  `:rf/after-epoch` and NOTHING that schedules. Before this fix the client
  installed exactly that and stopped: correct durable state, no live
  timer, and a machine that could sit in a timed state forever unless some
  unrelated external event moved it. Spec 011 §`:after` is no-op under SSR
  requires the opposite — \"`:after` timers begin running on the client
  (per the snapshot's epoch)\".

  ## These tests FIRE the timer

  Every positive case here captures the host-clock thunk
  (`with-redefs` on `rf.interop/schedule-after!`, the pattern
  `after_fire_reap_cljs_test` established) and INVOKES it, then asserts the
  machine actually transitioned. Asserting that the timer table is
  non-empty would have passed for the whole life of this defect had the
  table been populated with anything inert; only the fire proves the
  reconstructed timer is wired to the epoch, the decl-path and the
  transition.

  ## The server half is measured, not assumed

  The snapshots hydrated here are produced by running the machine on a
  real `:platform :server` frame, and each test asserts that frame armed
  NOTHING. So the fixture cannot drift into hydrating a snapshot the
  server would never have produced, and the server-skip contract is
  re-measured on every run.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under
  `clojure -M:test` from `implementation/machines` (JVM) and under the
  node runner (`npm run test:cljs`). The defect is a CLIENT host-runtime
  defect, so the CLJS arm is the load-bearing one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this shared
  process has used. `:platform` is what both `build-after-fx`'s server-skip
  and the hydration re-arm's own refusal read, so it is the one axis these
  tests vary."
  [platform]
  (let [fid (keyword "rf.hydrearm" (str (name platform) (swap! frame-counter inc)))]
    ;; `make-frame` opts are FLAT — everything but `:id` lands under the
    ;; record's `:config`, which is what `frame-meta` merges. Nesting a
    ;; `{:config {…}}` map here would store `:config {:config {…}}` and the
    ;; platform would silently read as the `:client` default, making the
    ;; server-side negative controls pass for the wrong reason.
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s inner `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run `machine` on a real SERVER frame through `events`, assert it armed
  no host timer, and return the resulting runtime-db value — a genuine
  server-produced hydration slice, not a hand-written literal."
  [machine-id machine events]
  (let [sfid (fresh-frame! :server)]
    (doseq [e events]
      (rf/dispatch-sync [machine-id e] {:frame sfid}))
    (is (empty? (inner sfid))
        "precondition: the SERVER armed no `:after` host timer")
    (rf.frame/frame-runtime-db-value sfid)))

(defn- fire!
  "Run a captured host-clock thunk to completion.

  The thunk dispatches the synthetic
  `[:rf.machine.timer/after-elapsed <delay-key> <epoch> <decl-path>]`
  through the REAL async router (`:router/dispatch!`), whose drain is
  scheduled on `rf.interop/next-tick` — a background executor on the JVM and
  a macrotask in CLJS, so a bare call would race every assertion after it.
  Collapsing `next-tick` to an inline call for the duration is the
  established seam (`core/test/re_frame/drain_test.clj`,
  `cofx_envelope_test.clj`): the whole routing path still runs, including
  the per-decl-path epoch stale-gate that a wrongly-armed timer would
  fail. Nothing here re-implements the timer's own event, which is
  precisely the part under test."
  [thunk]
  (with-redefs [rf.interop/next-tick (fn [f] (f) nil)]
    (thunk)))

(defn- hydrate-into!
  "Install `runtime-db` on a fresh CLIENT frame and run the re-arm seam —
  the machines half of what `:rf/hydrate` does once its runtime-db effect
  has committed. Returns the frame id."
  [runtime-db]
  (let [cfid (fresh-frame! :client)]
    (rf.frame/replace-runtime-db! cfid runtime-db)
    (rf.machines.hydrate/rearm-after-timers! cfid)
    cfid))

;; ---------------------------------------------------------------------------
;; Machines under test
;; ---------------------------------------------------------------------------

(def ^:private flat-machine
  "`:waiting` carries a 5s `:after`. Its `:entry` bumps `:entries`, which is
  the entry-replay control: the server runs it once, and a hydration that
  re-fired entry would run it twice."
  {:initial :idle
   :data    {:entries 0}
   :actions {:bump-entries (fn [{data :data}]
                             {:data (update data :entries inc)})}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:entry :bump-entries
                       :after {5000 {:target :timeout}}}
             :timeout {}}})

(def ^:private compound-machine
  "An ancestor AND its active leaf both declare `:after`. Entry only ever
  schedules NEWLY-entered nodes, so nothing but an ACTIVE-configuration
  walk reconstructs both."
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go [:outer :inner]}}
             :outer {:initial :inner
                     :after   {9000 {:target :outer-fired}}
                     :states  {:inner {:after {4000 {:target :inner-fired}}}
                               :inner-fired {}}}
             :outer-fired {}}})

(def ^:private parallel-machine
  "Two regions, one of them `:after`-bearing, plus a ROOT-owned `:after`
  (decl-path `[]`, epoch at the flat slot, `:rf/parallel-root` state)."
  {:type    :parallel
   :data    {}
   :after   {7000 {:target [:left :left-done]}}
   :regions {:left  {:initial :idle
                     :states  {:idle {:on {:go :ticking}}
                               :ticking {:after {3000 {:target :left-done}}}
                               :left-done {}}}
             :right {:initial :steady
                     :states  {:steady {}}}}})

;; ---------------------------------------------------------------------------
;; The core proof: hydration arms a timer that FIRES
;; ---------------------------------------------------------------------------

(deftest hydrated-after-timer-is-armed-at-the-persisted-epoch-and-fires
  (testing "a machine hydrated while sitting in an `:after`-bearing state
            gets exactly one client timer, armed at the epoch the SNAPSHOT
            carries, whose expiry performs the declared transition"
    (rf/reg-machine :hyd/flat flat-machine)
    (let [thunks (atom [])
          arms   (atom [])
          rt     (server-runtime-db :hyd/flat flat-machine [[:go]])
          snap   (get-in rt [:rf.runtime/machines :snapshots :hyd/flat])
          epoch  (get-in snap [:data :rf/after-epoch [:waiting]])]
      (is (= :waiting (:state snap))
          "precondition: the server settled in the `:after`-bearing state")
      (is (and (integer? epoch) (pos? epoch))
          "precondition: the durable per-decl-path epoch rode the snapshot")

      (let [cfid (with-redefs [rf.interop/schedule-after!
                              (fn [thunk ms]
                                (swap! thunks conj thunk)
                                (swap! arms conj ms)
                                ::handle)]
                   (hydrate-into! rt))
            table (inner cfid)]

        (is (= 1 (count table))
            "hydration armed EXACTLY one timer for the one live `:after`")
        (let [[k entry] (first table)]
          (is (= {:parent :hyd/flat :spawn [:waiting] :delay 5000} k)
              "keyed by the owning actor + the `:after`'s decl-path + delay")
          (is (= epoch (:epoch entry))
              (str "armed at the epoch the hydrated snapshot ALREADY carried "
                   "— re-arm must not bump, or the very snapshot being "
                   "restored would go stale against its own timer"))
          (is (= :waiting (:state entry)))
          (is (= :literal (:delay-source entry))))

        (is (= [5000] @arms)
            (str "armed for the FULL declared delay measured from hydration. "
                 "Nothing durable records a schedule instant — Spec 005 "
                 "§Clock abstraction keeps `:after` replay-sound precisely by "
                 "NOT recording one — so there is no remainder to compute."))

        ;; THE FIRE. Not "a table is populated" — the reconstructed timer
        ;; must actually drive the transition.
        (is (= :waiting (rf.machines.test-support/machine-state cfid :hyd/flat))
            "precondition: still waiting before the clock runs out")
        (fire! (first @thunks))
        (is (= :timeout (rf.machines.test-support/machine-state cfid :hyd/flat))
            (str "the hydrated timer FIRED and performed the declared "
                 "`:after` transition. This is the whole bug: before the fix "
                 "no timer existed, so this state was terminal in practice."))))))

(deftest hydration-does-not-replay-entry
  (testing "re-arming reconstructs host work only — the server's `:entry`
            action is NOT run a second time"
    (rf/reg-machine :hyd/noreplay flat-machine)
    (let [rt   (server-runtime-db :hyd/noreplay flat-machine [[:go]])
          snap (get-in rt [:rf.runtime/machines :snapshots :hyd/noreplay])]
      (is (= 1 (get-in snap [:data :entries]))
          "precondition: the server ran `:entry` exactly once")
      (let [cfid (with-redefs [rf.interop/schedule-after!
                              (fn [_thunk _ms] ::handle)]
                   (hydrate-into! rt))]
        (is (= 1 (:entries (rf.machines.test-support/machine-data cfid :hyd/noreplay)))
            (str "still 1. Re-running entry would have re-armed the timers "
                 "AND re-fired every entry effect the server already "
                 "performed — a different and worse bug than the one fixed."))
        (is (= :waiting (rf.machines.test-support/machine-state cfid :hyd/noreplay))
            "and the durable state is untouched by the re-arm")))))

(deftest hydration-re-arm-is-idempotent
  (testing "a second re-arm over the same snapshots supersedes rather than
            duplicating — one live handle per declaration, always"
    (rf/reg-machine :hyd/idem flat-machine)
    (let [rt (server-runtime-db :hyd/idem flat-machine [[:go]])]
      (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
        (let [cfid (hydrate-into! rt)]
          (is (= 1 (count (inner cfid))))
          (rf.machines.hydrate/rearm-after-timers! cfid)
          (is (= 1 (count (inner cfid)))
              "still one entry — the ordinary timer-table key superseded"))))))

;; ---------------------------------------------------------------------------
;; Hierarchy: an active ANCESTOR's `:after` is as live as the leaf's
;; ---------------------------------------------------------------------------

(deftest hydration-re-arms-every-node-on-a-compound-active-path
  (testing "both the active leaf's `:after` and its still-active ancestor's
            are reconstructed, each at its OWN per-decl-path epoch"
    (rf/reg-machine :hyd/compound compound-machine)
    (let [thunks (atom {})
          rt     (server-runtime-db :hyd/compound compound-machine [[:go]])
          snap   (get-in rt [:rf.runtime/machines :snapshots :hyd/compound])]
      (is (= [:outer :inner] (:state snap))
          "precondition: the server settled on the compound leaf")
      (let [cfid  (with-redefs [rf.interop/schedule-after!
                               (fn [thunk ms] (swap! thunks assoc ms thunk) ::handle)]
                    (hydrate-into! rt))
            table (inner cfid)]
        (is (= #{{:parent :hyd/compound :spawn [:outer]        :delay 9000}
                 {:parent :hyd/compound :spawn [:outer :inner] :delay 4000}}
                (set (keys table)))
            (str "BOTH scheduling nodes on the active path. Entry only "
                 "schedules newly-entered nodes, so an enumeration keyed on "
                 "the leaf alone would silently drop the ancestor's timer."))
        (doseq [[k entry] table]
          (is (= (get-in snap [:data :rf/after-epoch (:spawn k)]) (:epoch entry))
              (str "each node armed at its OWN durable epoch: " (:spawn k))))

        ;; Fire the ANCESTOR's timer — the one an entry-shaped fix misses.
        (fire! (get @thunks 9000))
        (is (= :outer-fired (rf.machines.test-support/machine-state cfid :hyd/compound))
            "the hydrated ANCESTOR timer fired and transitioned")))))

;; ---------------------------------------------------------------------------
;; Parallel: per-region active paths + the root-owned `:after`
;; ---------------------------------------------------------------------------

(deftest hydration-re-arms-region-and-root-parallel-afters
  (testing "a root-parallel machine hydrates with its region's active
            `:after` AND its root-owned `:after` both live"
    (rf/reg-machine :hyd/par parallel-machine)
    (let [thunks (atom {})
          rt     (server-runtime-db :hyd/par parallel-machine [[:go]])
          snap   (get-in rt [:rf.runtime/machines :snapshots :hyd/par])]
      (is (= :ticking (get-in snap [:state :left]))
          "precondition: the `:after`-bearing region is on its timed state")
      (let [cfid  (with-redefs [rf.interop/schedule-after!
                               (fn [thunk ms] (swap! thunks assoc ms thunk) ::handle)]
                    (hydrate-into! rt))
            table (inner cfid)]
        (is (= #{{:parent :hyd/par :spawn [:left :ticking] :delay 3000}
                 {:parent :hyd/par :spawn []              :delay 7000}}
                (set (keys table)))
            (str "the region `:after` under its REGION-PREFIXED invoke-id, "
                 "and the root-owned `:after` at the empty decl-path"))

        (let [region-entry (get table {:parent :hyd/par :spawn [:left :ticking] :delay 3000})
              root-entry   (get table {:parent :hyd/par :spawn [] :delay 7000})]
          (is (= (get-in snap [:data :rf/after-epoch-by-region :left [:ticking]])
                 (:epoch region-entry))
              "the region timer reads the PER-REGION epoch slot")
          (is (= :left (:region region-entry))
              "and records its region so the cancelled row's work-id matches")
          (is (= (get-in snap [:data :rf/after-epoch []]) (:epoch root-entry))
              "the root timer reads the FLAT slot at decl-path []")
          (is (= :rf/parallel-root (:state root-entry))
              "root sentinel, matching what birth-time scheduling stamps"))

        (fire! (get @thunks 3000))
        (is (= :left-done (get-in (rf.machines.test-support/snapshot cfid :hyd/par) [:state :left]))
            "the hydrated REGION timer fired and moved that region alone")
        (is (= :steady (get-in (rf.machines.test-support/snapshot cfid :hyd/par) [:state :right])
               )
            "and left its sibling region untouched")))))

;; ---------------------------------------------------------------------------
;; Dynamic delays resolve on the client
;; ---------------------------------------------------------------------------

(deftest hydration-resolves-a-dynamic-delay-on-the-client
  (testing "a subscription-vector delay is resolved through the ordinary
            scheduling path at hydration time, and its watcher is attached
            and released like any other"
    (let [reaction (atom 2500)
          m        {:initial :idle
                    :data    {}
                    :states  {:idle    {:on {:go :waiting}}
                              :waiting {:after {[:t/dyn] {:target :fired}}}
                              :fired   {}}}
          armed    (atom [])
          thunk    (atom nil)]
      (rf/reg-sub :t/dyn (fn [_db _] @reaction))
      (rf/reg-machine :hyd/dyn m)
      (with-redefs [rf.subs/subscribe   (fn ([_q] reaction) ([_q _o] reaction))
                    rf.subs/unsubscribe (fn ([_] nil) ([_ _] nil))
                    rf.interop/schedule-after! (fn [t ms]
                                              (reset! thunk t)
                                              (swap! armed conj ms)
                                              ::handle)]
        (let [rt    (server-runtime-db :hyd/dyn m [[:go]])
              cfid  (hydrate-into! rt)
              table (inner cfid)]
          (is (= 1 (count table)))
          (let [[k entry] (first table)]
            (is (= [:t/dyn] (:delay k)))
            (is (= :sub (:delay-source entry)))
            (is (= 2500 (:resolved-ms entry))
                "resolved on the CLIENT — the server never resolved it")
            (is (some? (:reaction entry)) "the dynamic-delay reaction is held")
            (is (some? (:sub-watcher-key entry))
                "and its re-resolution watcher is attached"))
          (is (= [2500] @armed))
          (fire! @thunk)
          (is (= :fired (rf.machines.test-support/machine-state cfid :hyd/dyn))
              "the client-resolved dynamic delay fired the transition")
          (is (empty? (inner cfid))
              "and the spent one-shot reaped its entry, releasing the watch"))))))

;; ---------------------------------------------------------------------------
;; Negative controls
;; ---------------------------------------------------------------------------

(deftest a-snapshot-with-no-active-after-arms-nothing
  (testing "hydrating a machine that is NOT in an `:after`-bearing state
            arms no timers — the walk is over the ACTIVE configuration, not
            over the spec"
    (rf/reg-machine :hyd/none flat-machine)
    ;; Birth only: `:idle` declares no `:after`, and `:waiting`'s
    ;; declaration is inert while nothing occupies it.
    (let [rt (server-runtime-db :hyd/none flat-machine [[:noop]])]
      (is (= :idle (get-in rt [:rf.runtime/machines :snapshots :hyd/none :state]))
          "precondition: parked on the `:after`-free initial state")
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        (let [cfid (hydrate-into! rt)]
          (is (empty? (inner cfid))
              "no live declaration, so nothing armed"))))))

(deftest a-server-side-hydrate-arms-nothing
  (testing "the re-arm refuses a `:platform :server` frame — an isomorphic
            loopback or server-side `:rf/hydrate` must not start host clocks"
    (rf/reg-machine :hyd/srv flat-machine)
    (let [rt (server-runtime-db :hyd/srv flat-machine [[:go]])]
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        (let [sfid (fresh-frame! :server)]
          (rf.frame/replace-runtime-db! sfid rt)
          (rf.machines.hydrate/rearm-after-timers! sfid)
          (is (empty? (inner sfid))
              (str "same refusal `build-after-fx` applies, read off the same "
                   "frame `:platform` — the two cannot disagree")))))))

(deftest an-unresolvable-snapshot-arms-nothing
  (testing "a snapshot whose machine type resolves to nothing is skipped,
            and does not stop its siblings from re-arming"
    (rf/reg-machine :hyd/mixed flat-machine)
    (let [rt      (server-runtime-db :hyd/mixed flat-machine [[:go]])
          ;; A wire-shaped snapshot for an actor no registration backs.
          poisoned (assoc-in rt [:rf.runtime/machines :snapshots :hyd/ghost]
                             {:state :waiting :data {} :rf/machine-type :hyd/never-registered})]
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        (let [cfid (hydrate-into! poisoned)]
          (is (= #{{:parent :hyd/mixed :spawn [:waiting] :delay 5000}}
                 (set (keys (inner cfid))))
              (str "the resolvable actor re-armed; the unresolvable one "
                   "contributed nothing and took nothing down with it")))))))

;; ---------------------------------------------------------------------------
;; The trace a hydrated timer leaves
;; ---------------------------------------------------------------------------

(deftest a-hydrated-timer-emits-one-ordinary-scheduled-trace
  (testing "the reconstructed timer emits ONE normal
            `:rf.machine.timer/scheduled` row — not a state-entry replay,
            and not nothing"
    (rf/reg-machine :hyd/trace flat-machine)
    (let [rt (server-runtime-db :hyd/trace flat-machine [[:go]])]
      (rf.machines.test-support/reset-captured!)
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        (let [cfid  (hydrate-into! rt)
              ;; A trace event carries its payload under `:tags`, not at the
              ;; top level (the shape `after_test` reads).
              rows  (filterv #(= :hyd/trace (:actor-id (:tags %)))
                             (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
              epoch (get-in rt [:rf.runtime/machines :snapshots :hyd/trace
                                :data :rf/after-epoch [:waiting]])]
          (is (= 1 (count rows)) "exactly one scheduled row")
          (let [row (:tags (first rows))]
            (is (= :waiting (:state row)))
            (is (= 5000 (:delay row)))
            (is (= :literal (:delay-source row)))
            (is (= epoch (:epoch row))
                "carrying the persisted epoch, so scheduled→fired pairs")
            (is (= cfid (:frame row))))
          (is (empty? (rf.machines.test-support/events-of :rf.machine.timer/skipped-on-server))
              "and no server-skip row: this arm happened on a client"))))))
