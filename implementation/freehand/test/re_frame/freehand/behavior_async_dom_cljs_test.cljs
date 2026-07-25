(ns re-frame.freehand.behavior-async-dom-cljs-test
  "EVIDENCE — does the SHIPPING `v/defbehavior` own a host that is acquired
  through a Promise, or does it not?

  Every behavior in this corpus so far owns a host it can construct
  SYNCHRONOUSLY: `new ResizeObserver(…)`, a GSAP timeline, a DOM write. A
  large and ordinary class of libraries cannot be constructed that way —
  `vegaEmbed(el, spec)`, a Maps `loader.load()`, a workbook whose
  `.ready` is a Promise — and for those the handle simply does not exist at
  the moment `:connect` runs. That gap is where a lifecycle contract either
  holds or quietly leaks, and nothing here had mounted it.

  This file is a MEASUREMENT, not a mechanism. It adds no verb, no runtime,
  no scheduling policy and no new law. It takes the two settled rulings the
  substrate already carries, writes the one recipe they imply, and mounts it
  against a DETERMINISTIC surrogate host so the answer is reproducible:

    THE MEMORY LAW (rf2-wj1ao)  `:connect` ESTABLISHES the connection's
                                private memory and nothing else ever writes
                                it. So when the handle is not ready yet,
                                what `:connect` returns is a MUTABLE CELL —
                                and the deferred continuation, `:update`, a
                                command and `:disconnect` all move that one
                                cell in place. There is no second writer to
                                race, which is precisely why no new
                                mechanism is needed.

    THE FENCE (FH-BEHAVIOR-006)  a behavior context's `:dispatch` resolves
                                its connection AT FIRING TIME, so a callback
                                the host kept past teardown is inert and
                                SAYS SO by answering `false`. A deferred
                                acquisition's continuation is exactly such a
                                callback.

  ## The surrogate, and why it is not Vega

  A third-party chart library would make this evidence a test of that
  library's scheduling. The surrogate below is the same SHAPE with none of
  the nondeterminism: an async constructor answering a Promise that settles
  ONLY when a case says so, a handle with a VOID-returning mutator
  (`set-spec!` — the ordinary JS shape the memory law exists for), a
  `dispose!` that must run exactly once, and a book of undisposed instances
  that is the leak assertion. No timer, no `AbortController`, no npm
  module: every settle in this file is an explicit call at a chosen moment.

  Crucially, `set-spec!` and `dispose!` THROW when the handle is already
  disposed. Real libraries do (a destroyed Vega view, a released workbook),
  and it is what turns a lifecycle defect into a visible failure instead of
  a silent no-op — a throw inside a `.then` becomes an unhandled rejection,
  and the browser lane fails a run on ANY uncaught page error even when
  every assertion passes.

  ## The five cases, and which one is the point

  1. NORMAL — acquire, install once, apply the spec, dispose once on
     unmount, and leave nothing behind.
  2. UNMOUNT BEFORE RESOLVE — the headline. Teardown runs while the
     acquisition is still in flight, and the handle arrives afterwards to a
     connection that is already gone. It must be finalised where it was
     born and NEVER installed, and the fenced dispatch must refuse.
  3. UPDATE WHILE PENDING — two config movements arrive before the handle
     does. The host must be configured ONCE, with the LATEST spec: a
     pending `:update` writes desired state, it does not queue host calls.
  4. LATE REJECTION AFTER UNMOUNT — a failure that arrives after teardown is
     EVIDENCE ONLY. It must not reopen the closed cell and must not raise.
  5. A COMMAND WHILE PENDING — refused at the recipe, and never replayed
     when the handle later arrives. Commands do not queue.

  Case 2 is why the file exists. Cases 1 and 3 pass against a naive recipe;
  case 2 is where a naive one leaks a handle, and case 4 is where it
  crashes the lane.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where there is no DOM to
  mount and it says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private frame-id :dom/behavior-async)

;; ===========================================================================
;; The surrogate host library
;; ===========================================================================
;;
;; Deliberately tiny, deliberately hostile, and deliberately deterministic.
;; It is a stand-in for `vegaEmbed` / a Maps loader / a workbook `.ready`,
;; and every one of its behaviours is chosen because a real library has it:
;;
;;   - construction is ASYNC and answers a Promise of a handle;
;;   - the handle's configuration call MUTATES and answers NOTHING;
;;   - the handle's dispose is not idempotent — a second one is a fault;
;;   - a call on a disposed handle THROWS;
;;   - the library itself knows which instances are still alive, which is
;;     what makes "no handle survived" an assertion rather than a hope.

(def ^:private ops
  "Every host-visible operation, in order. The library's own transcript —
  the behavior never writes it, so it cannot flatter itself."
  (atom []))

(def ^:private live
  "The library's book of instances it has handed out and not been asked to
  release: `id -> spec`. The LEAK ASSERTION reads this and nothing else."
  (atom {}))

(def ^:private acquisitions
  "Every outstanding acquisition, in the order `:connect` asked for it.
  Each is `{:id :promise :resolve! :reject!}`. A case settles one BY HAND;
  there is no timer anywhere in this file."
  (atom []))

(def ^:private next-id (atom 0))

(defn- reset-host! []
  (reset! ops []) (reset! live {}) (reset! acquisitions []) (reset! next-id 0)
  nil)

(defn- record! [op] (swap! ops conj op) nil)

(defn- acquire!
  "The library's async constructor — `vegaEmbed(el, spec)` in miniature.
  Answers a Promise of a handle that settles only when a case settles it."
  [node spec]
  (let [id       (swap! next-id inc)
        resolve* (atom nil)
        reject*  (atom nil)
        promise  (js/Promise. (fn [res rej] (reset! resolve* res) (reset! reject* rej)))]
    (record! [:acquire id (:series spec)])
    (swap! acquisitions conj
           {:id       id
            :promise  promise
            :resolve! (fn []
                        (swap! live assoc id ::unconfigured)
                        (@resolve* {:id id :node node}))
            :reject!  (fn [] (@reject* (js/Error. (str "acquisition " id " failed"))))})
    promise))

(defn- set-spec!
  "Reconfigure a live instance. VOID-returning, exactly like
  `chart.update(spec)` and `map.setOptions(…)` — the shape the memory law
  exists for. THROWS on a released instance, exactly like a real one."
  [handle spec]
  (let [id (:id handle)]
    (when-not (contains? @live id)
      (throw (ex-info "set-spec! on a RELEASED instance" {:id id})))
    (swap! live assoc id (:series spec))
    (record! [:set-spec id (:series spec)])
    nil))

(defn- dispose!
  "Release an instance. Not idempotent — a second release is a fault the
  library reports rather than absorbs, so a double teardown cannot hide."
  [handle]
  (let [id (:id handle)]
    (when-not (contains? @live id)
      (throw (ex-info "dispose! on an ALREADY-RELEASED instance" {:id id})))
    (swap! live dissoc id)
    (record! [:dispose id])
    nil))

(defn- export
  "A read the host can only answer while it is alive — the thing a command
  is for."
  [handle]
  (record! [:export (:id handle)])
  (str "png:" (:id handle)))

;; ---------------------------------------------------------------------------
;; Settling, deterministically
;; ---------------------------------------------------------------------------

(defn- settle!
  "Settle the `n`th acquisition and answer a promise that is done once the
  recipe's continuation has run.

  Registration order is the whole mechanism: the behavior attached its
  handler inside `:connect`, so a handler attached HERE is queued strictly
  after it. One further tick covers the continuation's own tail. No timer,
  no polling, and no wall-clock anywhere — which is what makes this
  evidence reproducible rather than merely observed once."
  [n outcome]
  (let [{:keys [promise resolve! reject!]} (nth @acquisitions n)]
    (if (= :ok outcome) (resolve!) (reject!))
    (-> promise
        (.then (fn [_] nil) (fn [_] nil))
        (.then (fn [_] nil)))))

;; ===========================================================================
;; THE RECIPE — the one under measurement
;; ===========================================================================
;;
;; Read the four moves, because they are the entire answer and there is no
;; fifth:
;;
;;   1. `:connect` returns a MUTABLE CELL, synchronously. The handle is not
;;      ready; the MEMORY is, because memory is the connection's and is
;;      established once with it (rf2-wj1ao). Everything later moves this
;;      one cell in place, and nothing ever writes the memory again.
;;
;;   2. The continuation closes over that cell — over the local, NOT over
;;      `(:memory ctx)`, which is still `nil` while `:connect` is running.
;;
;;   3. `:disconnect` FENCES FIRST. It flips the phase to `:closed` before
;;      it releases anything, so an acquisition still in flight finds a
;;      closed cell and finalises the handle where it was born instead of
;;      installing it into a connection that is gone.
;;
;;   4. `:closed` is TERMINAL. A late success and a late failure both check
;;      it and neither may move out of it — otherwise a rejection arriving
;;      after teardown reopens a cell the teardown already settled.
;;
;; Note the two-argument `.then`: the failure arm is the ACQUISITION's, and
;; routing it through a trailing `.catch` would let a throw from the success
;; arm masquerade as an acquisition failure.

(def ^:private dispatches
  "Every outward dispatch the recipe attempted, paired with the boolean the
  fenced context answered. `[event accepted?]` — the `false` is the
  evidence that a callback outliving its node is inert."
  (atom []))

(defn- announce! [dispatch event]
  (swap! dispatches conj [event (dispatch event)])
  nil)

(defn- closed? [cell] (= :closed (:phase @cell)))

(v/defbehavior async-chart
  "A Promise-acquired imperative host, owned with the mechanism that ships."
  {:timing  :passive
   :connect
   (fn [{:keys [node config dispatch]}]
     ;; The memory is established HERE and never again. It is a cell
     ;; because the thing it will hold does not exist yet.
     (let [cell (atom {:phase :pending :handle nil :spec config})]
       (.then (acquire! node config)
              (fn [handle]
                (if (closed? cell)
                  ;; LATE SUCCESS — the connection is gone. Finalise the
                  ;; handle where it was born; never install it.
                  (do (dispose! handle)
                      (announce! dispatch [:chart/abandoned (:id config)]))
                  (do (swap! cell assoc :phase :ready :handle handle)
                      ;; the LATEST desired spec, not the one :connect saw
                      (set-spec! handle (:spec @cell))
                      (announce! dispatch [:chart/ready (:id config)]))))
              (fn [_err]
                ;; `:closed` is TERMINAL — a late failure is evidence only.
                (swap! cell (fn [m] (cond-> m (not= :closed (:phase m))
                                            (assoc :phase :failed))))
                (announce! dispatch [:chart/failed (:id config)])))
       cell))

   :update
   (fn [{:keys [config memory]}]
     ;; The return is DISCARDED, so this writes the cell in place. While
     ;; pending it records DESIRED state only: no host call is queued,
     ;; because a queue is a scheduling policy and there is none here.
     (swap! memory assoc :spec config)
     (when (= :ready (:phase @memory))
       (set-spec! (:handle @memory) config)))

   :disconnect
   (fn [{:keys [memory]}]
     ;; FENCE FIRST, then release. The order is the contract.
     (let [{:keys [phase handle]} @memory]
       (swap! memory assoc :phase :closed)
       (when (= :ready phase) (dispose! handle))))

   :commands
   {:export (fn [{:keys [memory dispatch]}]
              (let [{:keys [phase handle]} @memory]
                (if (= :ready phase)
                  (announce! dispatch [:chart/exported (export handle)])
                  ;; Refused, and NOT remembered. A command that queued
                  ;; would fire into a host the user has stopped looking at.
                  (announce! dispatch [:chart/export-refused phase]))))}})

;; ---------------------------------------------------------------------------
;; Views. Module-level — a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview chart-panel
  [{:keys [series]}]
  [:section.host
   [v/behavior {:use    async-chart
                :target :chart/main
                :config {:id :main :series series}}
    [:div.node]]])

(v/defview control-panel
  "The CONTROL: the same markup with no behavior at all, so a zero after
  teardown is the substrate's release rather than a counter nothing wrote."
  [_]
  [:section.host
   [:div.node]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit AND its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- element [form]
  (shell/provide-frame frame-id (fr/element form)))

(defn- setup! []
  (behaviors/reset-connections!)
  (reset-host!)
  (reset! dispatches [])
  (live-frame/make-frame {:id frame-id})
  (rf/reg-event :chart/ready          (fn [db _] db))
  (rf/reg-event :chart/failed         (fn [db _] db))
  (rf/reg-event :chart/abandoned      (fn [db _] db))
  (rf/reg-event :chart/exported       (fn [db _] db))
  (rf/reg-event :chart/export-refused (fn [db _] db))
  (rf/reg-event :chart/command (fn [_ [_ cmd]] {:fx [[behaviors/command-fx-id cmd]]}))
  nil)

(defn- events [] (mapv first @dispatches))
(defn- accepted [] (mapv second @dispatches))

(defn- nothing-survived!
  "The leak assertion, said in one place because every case ends with it.
  Three independent books must all read empty: the library's own instance
  ledger, the substrate's connection table, and its live target index."
  [where]
  (is (= {} @live)          (str where " — the library holds NO undisposed instance"))
  (is (= 0 (behaviors/connection-count)) (str where " — the connection table is empty"))
  (is (= #{} (behaviors/target-ids))     (str where " — no target claim survives")))

;; ===========================================================================
;; 1 — the ordinary case
;; ===========================================================================

(deftest an-acquired-host-installs-once-and-releases-once
  (testing "The baseline every other case is measured against. `:connect`
            asks the library to construct and returns a cell immediately;
            when the handle arrives it is installed ONCE and configured
            with the desired spec; unmount releases it EXACTLY once. The
            CONTROL mount first proves the counters read zero for markup
            that never connected, so the zero at the end is a release."
    (if-not (browser?)
      (skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[cc croot]       (mount!)
              [container root] (mount!)]
          (-> (act #(.render croot (element [control-panel {}])))
              (.then (fn [_]
                       (is (= [] @ops) "the CONTROL mount asks the library for nothing")
                       (nothing-survived! "control mounted")
                       (act #(teardown! cc croot))))

              (.then (fn [_] (act #(.render root (element [chart-panel {:series [1 2]}])))))
              (.then (fn [_]
                       (is (= [[:acquire 1 [1 2]]] @ops)
                           "the commit asked for ONE construction and nothing more")
                       (is (= 1 (behaviors/connection-count)))
                       (is (= {} @live)
                           "and no instance exists yet — the handle is still in flight")
                       (is (= [] (events)) "so nothing has been announced")
                       (settle! 0 :ok)))

              (.then (fn [_]
                       (is (= [[:acquire 1 [1 2]] [:set-spec 1 [1 2]]] @ops)
                           "the arriving handle is installed once and configured once")
                       (is (= {1 [1 2]} @live) "one live instance")
                       (is (= [[:chart/ready :main]] (events)))
                       (is (= [true] (accepted))
                           "and the LIVE connection's fenced dispatch was accepted")
                       (act #(teardown! container root))))

              (.then (fn [_]
                       (is (= [[:acquire 1 [1 2]] [:set-spec 1 [1 2]] [:dispose 1]] @ops)
                           "unmount released it, exactly once")
                       (nothing-survived! "after teardown")
                       (done)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

;; ===========================================================================
;; 2 — THE HEADLINE: unmount BEFORE the Promise resolves
;; ===========================================================================

(deftest a-handle-arriving-after-teardown-is-finalised-and-never-installed
  (testing "The ordering an async host exists to survive: the user navigates
            away while the chart is still loading. `:disconnect` runs with
            the cell still `:pending`, so there is nothing to release yet —
            and then the library hands over a real instance to a connection
            that no longer exists.

            Three things must hold, and each fails differently if they do
            not. The handle must be DISPOSED (or the library leaks it
            forever). It must NEVER be INSTALLED — no `set-spec!` — because
            configuring a released node is both wrong and, against a real
            library, a throw inside a `.then` and therefore an unhandled
            rejection the browser lane fails the whole run on. And the
            outward dispatch must be REFUSED, because the frame's view is
            gone.

            `:disconnect` still ran exactly once: a pending connection is a
            connection."
    (if-not (browser?)
      (skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [chart-panel {:series [7]}])))
              (.then (fn [_]
                       (is (= [[:acquire 1 [7]]] @ops))
                       (is (= 1 (behaviors/connection-count)))
                       ;; UNMOUNT while the acquisition is still in flight.
                       (act #(teardown! container root))))

              (.then (fn [_]
                       (is (= [[:acquire 1 [7]]] @ops)
                           "teardown of a PENDING connection releases nothing — there
                            is nothing yet to release")
                       (nothing-survived! "torn down while pending")
                       ;; ... and NOW the library answers.
                       (settle! 0 :ok)))

              (.then (fn [_]
                       (is (= [[:acquire 1 [7]] [:dispose 1]] @ops)
                           "the late handle was FINALISED where it was born, and
                            never configured — no :set-spec ran")
                       (is (= {} @live)
                           "so the library holds no undisposed instance")
                       (is (= [[:chart/abandoned :main]] (events))
                           "the continuation took the abandoned arm")
                       (is (= [false] (accepted))
                           "and its outward dispatch was REFUSED — the context is
                            fenced to a generation that is gone")
                       (nothing-survived! "after the late arrival")
                       (done)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

;; ===========================================================================
;; 3 — config that moves while the acquisition is in flight
;; ===========================================================================

(deftest updates-while-pending-collapse-to-the-latest-desired-spec
  (testing "Config moves twice before the handle arrives. A recipe that
            queued the host calls would replay two stale configurations
            against a chart the user never saw in those states; a recipe
            that dropped them would install the config `:connect` happened
            to see. Neither is right. The pending `:update` writes DESIRED
            state into the cell, and the arriving handle is configured ONCE
            with the latest — which is a fact about the cell, not a
            scheduling policy."
    (if-not (browser?)
      (skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [series] (act #(.render root (element [chart-panel {:series series}]))))]
          (-> (render [1])
              (.then (fn [_] (render [1 2])))
              (.then (fn [_] (render [1 2 3])))
              (.then (fn [_]
                       (is (= [[:acquire 1 [1]]] @ops)
                           "two config movements while pending performed NO host work")
                       (is (= 1 (count @acquisitions))
                           "and asked for no second construction")
                       (settle! 0 :ok)))

              (.then (fn [_]
                       (is (= [[:acquire 1 [1]] [:set-spec 1 [1 2 3]]] @ops)
                           "the handle is configured ONCE, with the LATEST spec")
                       ;; and an update AFTER readiness reaches the host directly
                       (render [9])))
              (.then (fn [_]
                       (is (= [[:acquire 1 [1]] [:set-spec 1 [1 2 3]] [:set-spec 1 [9]]] @ops)
                           "once ready, a movement is an ordinary host call")
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (is (= [:dispose 1] (last @ops)) "and it still releases once")
                       (nothing-survived! "after teardown")
                       (done)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

;; ===========================================================================
;; 4 — a failure that arrives after teardown
;; ===========================================================================

(deftest a-rejection-after-teardown-is-evidence-only-and-cannot-reopen-the-cell
  (testing "The acquisition fails, but only after the view is gone. There is
            no host to release and nothing to report to a screen nobody is
            looking at, so the ONLY correct outcome is that nothing happens:
            the cell stays `:closed`, the outward dispatch is refused, and
            the rejection is HANDLED — an unhandled one is a page error, and
            the browser lane fails a run on any page error even when every
            assertion passes.

            `:closed` being TERMINAL is what makes this true. A cell that
            let `:failed` overwrite it would have a torn-down connection
            re-enter a live phase, and the next thing to read the phase
            would believe the connection was merely broken rather than
            gone."
    (if-not (browser?)
      (skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [chart-panel {:series [4]}])))
              (.then (fn [_] (act #(teardown! container root))))
              (.then (fn [_] (settle! 0 :fail)))
              (.then (fn [_]
                       (is (= [[:acquire 1 [4]]] @ops)
                           "a failed acquisition performed no host work at all")
                       (is (= [[:chart/failed :main]] (events))
                           "the failure arm ran — the rejection was HANDLED")
                       (is (= [false] (accepted))
                           "and its dispatch was refused, because the view is gone")
                       (nothing-survived! "after the late rejection")
                       (done)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

;; ===========================================================================
;; 5 — a command while the host is still pending
;; ===========================================================================

(deftest a-command-issued-while-pending-is-refused-and-never-replays
  (testing "A command is a one-shot host operation, and while the host is
            pending there is no host. The substrate DELIVERS it — the target
            is claimed by a live connection, which it genuinely is — and the
            recipe refuses it, visibly, rather than remembering it. The
            second half is the assertion that matters: when the handle
            arrives, the refused export must NOT run. A queue here would fire
            an export the user asked for and gave up on."
    (if-not (browser?)
      (skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (element [chart-panel {:series [5]}])))
              (.then (fn [_]
                       (act #(rf/dispatch-sync [:chart/command {:target :chart/main
                                                                :op     :export}]
                                               {:frame frame-id}))))
              (.then (fn [_]
                       (is (= [[:acquire 1 [5]]] @ops)
                           "the refused command performed NO host work")
                       (is (= [[:chart/export-refused :pending]] (events))
                           "and the refusal is visible, naming the phase")
                       (is (= [:delivered] (mapv :outcome (behaviors/command-log)))
                           "the SUBSTRATE delivered it — the refusal is the recipe's,
                            because a live connection did claim the target")
                       (settle! 0 :ok)))

              (.then (fn [_]
                       (is (= [[:acquire 1 [5]] [:set-spec 1 [5]]] @ops)
                           "the arriving handle is configured — and the refused export
                            did NOT replay")
                       (is (= [[:chart/export-refused :pending] [:chart/ready :main]]
                              (events)))
                       ;; the same command, now that there IS a host
                       (act #(rf/dispatch-sync [:chart/command {:target :chart/main
                                                                :op     :export}]
                                               {:frame frame-id}))))
              (.then (fn [_]
                       (is (= [:export 1] (last @ops))
                           "once ready the very same command reaches the host")
                       (is (= [:chart/exported "png:1"] (last (events))))
                       (act #(teardown! container root))))
              (.then (fn [_]
                       (is (= [:dispose 1] (last @ops)))
                       (nothing-survived! "after teardown")
                       (done)))
              (.catch (fn [e] (is false (str "mount rejected: " e)) (done)))))))))
