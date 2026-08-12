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

  ## The law this file proves — FH-BEHAVIOR-005

  Adding no law means having none of its own, and for a while that left
  this file with no identity: it was reachable from the guide and from the
  bead that commissioned it, and from no ledger. It is rostered
  (rf2-drpa3.182.7) as the SECOND mounted projection of `FH-BEHAVIOR-005` —
  teardown releases a behavior totally, `:disconnect` exactly once per
  committed connection, both substrate books empty afterwards — because
  that is precisely the law the cases below stress. `behaviors-dom-cljs-test`
  proves it on the ordinary synchronous path; this file proves the same law
  where it is hardest, with the handle arriving from a Promise after the
  owner may already be gone.

  Minting a `FH-…` id of its own was considered and declined: a second id
  for one law splits the identity the roster exists to keep single, and an
  id must resolve to a paragraph of normative spec — which a recipe that
  adds no contract does not have.
  See `spec/conformance/freehand/fixtures/fh-behavior-005.edn`.

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
  `dispose!` that must run exactly once and that can be told to FAIL, and a
  book of undisposed instances that is the leak assertion. No
  `AbortController` and no npm module: every settle in this file is an
  explicit call at a chosen moment.

  Crucially, `set-spec!` and `dispose!` THROW when the handle is already
  disposed. Real libraries do (a destroyed Vega view, a released workbook),
  and it is what turns a lifecycle defect into a visible failure instead of
  a silent no-op — a throw inside a `.then` becomes an unhandled rejection,
  and the browser lane fails a run on ANY uncaught page error even when
  every assertion passes.

  ## The seven cases, and which one is the point

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
  6. THE FINALIZER ITSELF FAILS — the FIRST release throws inside the
     library, at unmount, on a host that was live. DC-02 names finalizer
     failure and the recipe says it cannot reopen the owner; a non-idempotent
     `dispose!` only ever catches a SECOND release, which is a different
     claim. This case makes the first one fail.
  7. THE SAME FAILURE, ON THE LATE-SUCCESS PATH — a handle arriving after
     teardown, whose finalization throws. Its outcome DIFFERS from case 6
     and the difference is the finding: the two finalizer sites answer to
     different error channels, and only one of them still notifies.

  Case 2 is why the file exists. Cases 1 and 3 pass against a naive recipe;
  case 2 is where a naive one leaks a handle, case 4 is where it crashes
  the lane, and cases 6 and 7 are where the HOST fails and the question is
  what the owner is left holding.

  ## What cases 6 and 7 report, and what they cannot

  A finalizer failure is the host's, not the recipe's, so the honest
  deliverable is a boundary rather than a clean bill:

    THE OWNER'S SIDE IS CLEAN, always. `:disconnect` fences to `:closed`
    BEFORE it calls the finalizer, and `behaviors/disconnect!` claims and
    REMOVES the connection record before it runs `:disconnect` at all — so
    a throwing finalizer leaves the connection table and the target index
    empty, the phase terminal, and the owner unreachable. There is no
    retry, because nothing holds a reference with which to retry.

    THE HOST'S SIDE IS NOT, and cannot be. The library was asked exactly
    once, refused, and still holds the instance. No recipe can release a
    handle its owner will not release. These cases ASSERT that leak rather
    than pretend it away — a passing run here means the framework did
    everything available to it, not that nothing was lost.

  The two cases differ in WHERE the failure surfaces, and that is measured
  rather than assumed — each installs both doors and asserts which one
  reads:

    case 6, at unmount    the throw comes straight back out of the unmount
                          call. React neither swallows it nor routes it: the
                          root's own uncaught-error handler never fires.
    case 7, on the late   the throw is inside a `.then`, so it escapes as an
    path                  UNHANDLED REJECTION — and the announcement that
                          followed the `dispose!` never runs.

  Case 7 is the one that costs an author something, because a silent
  missing notification is harder to notice than a loud unmount.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where there is no DOM to
  mount and it says so rather than passing quietly.

  Task boundaries (`setTimeout 0`) appear in case 7 and nowhere else, and
  they are not a timing observation: the browser posts its
  unhandled-rejection notification as its own task, so hopping to that task
  is the only way to read the door at all. Every settle in this file is
  still an explicit call."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true
     ;; Scope the shared lifecycle ledger to this case. Bookkeeping only —
     ;; it forgets what the previous case mounted and tears nothing down,
     ;; which is what lets `ms/residue-clean!` report a retained root AFTER
     ;; a case rather than a reset swallowing it before the next.
     :init-fn       (fn [] (ms/reset-ledger!))}))

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
;;   - RELEASE ITSELF CAN FAIL, on the first attempt, leaving the instance
;;     alive — a GL context already lost, a workbook released underneath
;;     you, a view whose teardown hit the network. This is the arm cases 6
;;     and 7 exist for, and it is not the same thing as the non-idempotent
;;     second release above;
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

(def ^:private brittle
  "Instance ids whose release FAILS. `dispose!` counts the attempt and then
  throws, and the instance stays in `live` — because it does. A case arms
  this after the handle exists, so the failure lands on the FIRST release
  and not on a second one."
  (atom #{}))

(def ^:private dispose-attempts
  "id -> how many times the library was ASKED to release it, successful or
  not. `ops` records only what SUCCEEDED, so this is the separate book that
  lets `exactly one attempt, and it failed` say something — a retry would
  read 2 here while `ops` stayed empty."
  (atom {}))

(def ^:private escapes
  "Where a finalizer failure surfaced, in order. `[door value]`. Cases 6 and
  7 install the doors themselves — React's root-level error callback and
  the page's `unhandledrejection` — because the browser lane fails a run on
  any uncaught page error, so an UNOBSERVED throw would red the suite
  instead of being measured by it."
  (atom []))

(defn- reset-host! []
  (reset! ops []) (reset! live {}) (reset! acquisitions []) (reset! next-id 0)
  (reset! brittle #{}) (reset! dispose-attempts {}) (reset! escapes [])
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
  library reports rather than absorbs, so a double teardown cannot hide.

  A release can also simply FAIL (`brittle`), which is a different fault
  and the one DC-02 names. The attempt is counted BEFORE the throw and the
  instance is left in `live`: the caller asked, the library refused, and
  the handle is still out there."
  [handle]
  (let [id (:id handle)]
    (when-not (contains? @live id)
      (throw (ex-info "dispose! on an ALREADY-RELEASED instance" {:id id})))
    (swap! dispose-attempts update id (fnil inc 0))
    (when (contains? @brittle id)
      (throw (ex-info "dispose! FAILED inside the library" {:id id})))
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

(def ^:private cells
  "Every owner cell `:connect` established, in order.

  The SECOND deliberate divergence from the guide's sample, and it earns its
  place the same way the first one does: cases 6 and 7 claim the owner was
  fenced `:closed` BEFORE the finalizer was called, and an owner nothing can
  read is a claim nothing can falsify. Publishing the cell changes no
  behaviour — the recipe still writes only its own memory, and the test only
  ever READS what it publishes."
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
       (swap! cells conj cell)                 ; published for READING only
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
                ;; The OBSERVED phase rides on the event, which is the one
                ;; divergence from the guide's sample and it is deliberate:
                ;; without it, `:closed` being terminal is a claim nothing
                ;; here could falsify. A cell that let `:failed` overwrite
                ;; `:closed` reds this line and nothing else would notice.
                (announce! dispatch [:chart/failed (:phase @cell)])))
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

;; The mounted LIFECYCLE — the `act` boundary, the `[container root]` pair
;; and the teardown — comes from `re-frame.freehand.mount-support`, the one
;; facade the browser tier shares (rf2-n9rzw). This file used to carry its
;; own byte-identical copy of each.

(defn- mount!
  "A root through the shared lifecycle. With `watch-errors?` it carries its
  OWN error callbacks, so a throw React would otherwise hand to
  `reportError` — which the browser lane counts as an uncaught page error
  and fails the run on — arrives in `escapes` where a case can assert on it
  instead.

  Only the callbacks are this suite's; the root, the container and the
  enrolment in the residue ledger are the facade's."
  ([] (mount! false))
  ([watch-errors?]
   (ms/create-root!
     (when watch-errors?
       {:on-uncaught-error (fn [e _info]
                             (swap! escapes conj [:react/uncaught (ex-message e)]))
        :on-caught-error   (fn [e _info]
                             (swap! escapes conj [:react/caught (ex-message e)]))}))))

(defn- watch-rejections!
  "Open the page's `unhandledrejection` door for one case and answer the
  closer. `preventDefault` is the point: without it the rejection is
  reported to the page and the lane fails the run on it, so the door both
  MEASURES the escape and keeps it from masquerading as a regression."
  []
  (let [handler (fn [e]
                  (.preventDefault e)
                  (swap! escapes conj [:promise/unhandled (ex-message (.-reason e))])
                  nil)]
    (.addEventListener js/window "unhandledrejection" handler)
    (fn [] (.removeEventListener js/window "unhandledrejection" handler))))

(defn- task-boundary
  "A promise that settles at the next TASK, not the next microtask.
  `unhandledrejection` is dispatched there and nowhere earlier, so this is
  the only way to read that door. A boundary, never a duration."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

(defn- await-escape
  "Hop task boundaries until the rejection door reads, up to `n` of them.

  One hop is not enough and the reason is worth writing down: the browser
  posts its unhandled-rejection notification as its OWN task, queued after
  the microtask checkpoint that observed the rejection — so a `setTimeout`
  scheduled from inside the promise chain can be dispatched first and see
  nothing. This waits for an EVENT, never for a duration; the bound exists
  so a broken run fails instead of hanging."
  [n]
  (if (or (seq @escapes) (zero? n))
    (js/Promise.resolve nil)
    (.then (task-boundary) (fn [_] (await-escape (dec n))))))

(defn- element [form]
  (shell/provide-frame frame-id (fr/element form)))

(defn- setup! []
  (behaviors/reset-connections!)
  (reset-host!)
  (reset! dispatches [])
  (reset! cells [])
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

(defn- owner-released!
  "The FRAMEWORK's two books, which must read empty whatever the host did.
  Split out from [[nothing-survived!]] because cases 6 and 7 need exactly
  this half: a finalizer that throws still leaves the connection table and
  the target index clean, and saying so requires an assertion that does not
  also demand the library let go."
  [where]
  (is (= 0 (behaviors/connection-count)) (str where " — the connection table is empty"))
  (is (= #{} (behaviors/target-ids))     (str where " — no target claim survives")))

(defn- nothing-survived!
  "The leak assertion, said in one place because every case ends with it.
  Three independent books must all read empty: the library's own instance
  ledger, the substrate's connection table, and its live target index."
  [where]
  (is (= {} @live)          (str where " — the library holds NO undisposed instance"))
  (owner-released! where))

(defn- released!
  "[[nothing-survived!]]'s three books, read through the SHARED lifecycle's
  residue assertion — the terminal form of every case that unmounts.

  It reads the same three absences, and adds the one no per-suite teardown
  can make: that every ROOT this case created was torn down and left its
  container empty and detached. That is the leak which contaminates every
  later suite in the process rather than only this one, and it is the
  assertion FH-BEHAVIOR-005's `:residue :none` rests on (rf2-n9rzw)."
  [where]
  (ms/residue-clean! where
                     [["the library's undisposed-instance ledger" #(deref live)]
                      ["the connection table"  #(behaviors/connection-count)]
                      ["the live target index" #(behaviors/target-ids)]]))

(defn- owner-phase
  "The phase of the one owner cell this case's `:connect` established."
  []
  (:phase @(first @cells)))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[cc croot]       (mount!)
              [container root] (mount!)]
          (-> (ms/act #(.render croot (element [control-panel {}])))
              (.then (fn [_]
                       (is (= [] @ops) "the CONTROL mount asks the library for nothing")
                       (nothing-survived! "control mounted")
                       (ms/act #(ms/destroy-root! cc croot))))

              (.then (fn [_] (ms/act #(.render root (element [chart-panel {:series [1 2]}])))))
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
                       (ms/act #(ms/destroy-root! container root))))

              (.then (fn [_]
                       (is (= [[:acquire 1 [1 2]] [:set-spec 1 [1 2]] [:dispose 1]] @ops)
                           "unmount released it, exactly once")
                       (released! "FH-BEHAVIOR-005 — after teardown")))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (ms/act #(.render root (element [chart-panel {:series [7]}])))
              (.then (fn [_]
                       (is (= [[:acquire 1 [7]]] @ops))
                       (is (= 1 (behaviors/connection-count)))
                       ;; UNMOUNT while the acquisition is still in flight.
                       (ms/act #(ms/destroy-root! container root))))

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
                       (released! "FH-BEHAVIOR-005 — after the late arrival")))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [series] (ms/act #(.render root (element [chart-panel {:series series}]))))]
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
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (= [:dispose 1] (last @ops)) "and it still releases once")
                       (released! "FH-BEHAVIOR-005 — after teardown")))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (ms/act #(.render root (element [chart-panel {:series [4]}])))
              (.then (fn [_] (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_] (settle! 0 :fail)))
              (.then (fn [_]
                       (is (= [[:acquire 1 [4]]] @ops)
                           "a failed acquisition performed no host work at all")
                       (is (= [[:chart/failed :closed]] (events))
                           "the failure arm ran — the rejection was HANDLED — and it
                            observed the cell STILL CLOSED, because a teardown that
                            has already settled the connection is terminal")
                       (is (= [false] (accepted))
                           "and its dispatch was refused, because the view is gone")
                       (released! "FH-BEHAVIOR-005 — after the late rejection")))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

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
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        (let [[container root] (mount!)]
          (-> (ms/act #(.render root (element [chart-panel {:series [5]}])))
              (.then (fn [_]
                       (ms/act #(rf/dispatch-sync [:chart/command {:target :chart/main
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
                       (ms/act #(rf/dispatch-sync [:chart/command {:target :chart/main
                                                                :op     :export}]
                                               {:frame frame-id}))))
              (.then (fn [_]
                       (is (= [:export 1] (last @ops))
                           "once ready the very same command reaches the host")
                       (is (= [:chart/exported "png:1"] (last (events))))
                       (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (= [:dispose 1] (last @ops)))
                       (released! "FH-BEHAVIOR-005 — after teardown")))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; 6 — THE FINALIZER FAILS, on the first release
;; ===========================================================================

(deftest a-throwing-finalizer-closes-the-owner-and-leaks-only-the-host
  (testing "DC-02 requires finalizer failure, and the recipe says a failed
            finalizer cannot reopen the owner. A `dispose!` that refuses a
            SECOND release does not test that: the second release is a
            double-teardown defect and it never happens on a correct path.
            Here the FIRST release fails, on a host that was live, at
            unmount.

            The owner's side must be perfect, and it is perfect by
            ORDERING rather than by handling. `:disconnect` writes
            `:closed` before it touches the host, and
            `behaviors/disconnect!` claims and REMOVES the connection
            record before it runs `:disconnect` at all — so by the time the
            library refuses, there is no record to retry from and no
            reachable owner to reopen. Nothing catches the throw and
            nothing needs to.

            The host's side is a leak, and the case ASSERTS the leak
            instead of pretending it away. The library was asked exactly
            once, said no, and still holds the instance. That is the
            boundary the recipe can honestly claim: everything the owner
            controls is released; a handle the library will not release is
            not one of those things."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        ;; BOTH doors open, so "which one read" is a measurement.
        (let [close-door       (watch-rejections!)
              [container root] (mount! :watch-errors)]
          (-> (ms/act #(.render root (element [chart-panel {:series [6]}])))
              (.then (fn [_] (settle! 0 :ok)))
              (.then (fn [_]
                       (is (= [[:acquire 1 [6]] [:set-spec 1 [6]]] @ops)
                           "the host is live and configured before anything is armed")
                       (is (= {1 [6]} @live))
                       (is (= :ready (owner-phase)))
                       ;; ARM the failure only now, so it lands on the FIRST release.
                       (reset! brittle #{1})
                       (-> (ms/act #(ms/destroy-root! container root))
                           (.catch (fn [e]
                                     (swap! escapes conj [:act/rejected (ex-message e)])
                                     nil)))))

              (.then (fn [_]
                       (is (= :closed (owner-phase))
                           "the owner was FENCED before the finalizer was called —
                            `:disconnect` writes :closed first and releases second, so
                            a throw cannot leave it in a live phase")
                       (is (= {1 1} @dispose-attempts)
                           "the library was asked to release EXACTLY once: no retry")
                       (is (= [[:acquire 1 [6]] [:set-spec 1 [6]]] @ops)
                           "and no :dispose is recorded, because none succeeded")
                       (is (= {1 [6]} @live)
                           "HOST-ONLY LEAK, reported rather than pretended away — the
                            library still holds the instance it refused to release")
                       (owner-released! "after a throwing finalizer")
                       (is (= [:act/rejected] (mapv first @escapes))
                           "and the failure is LOUD, out of the UNMOUNT CALL itself —
                            exactly one door reads, so the root's own uncaught-error
                            handler never saw it: React let the throw back out rather
                            than routing or swallowing it")
                       (is (= [[:chart/ready :main]] (events))
                           "and the teardown announced nothing of its own — the only
                            event is the one the successful install sent")))
              (.catch (fn [e]
                        (is false (str "case rejected: " e))
                        nil))
              (.then (fn [_]
                       (close-door)
                       (done)))))))))

;; ===========================================================================
;; 7 — the same failure, on the LATE-SUCCESS finalizer
;; ===========================================================================

(deftest a-throwing-finalizer-on-the-late-path-escapes-as-a-rejection
  (testing "Case 2's ordering — teardown first, handle second — with case 6's
            failure. The recipe finalises the late handle where it was born,
            and here that finalisation throws.

            The outcome DIFFERS from case 6, and the difference is the
            finding rather than a detail. The late finaliser runs inside a
            `.then`, so its throw rejects a promise nothing is awaiting: it
            escapes as an UNHANDLED REJECTION rather than through React,
            and the `[:chart/abandoned …]` line that follows it never runs.
            An author reading Pattern E should know that the notification
            after a late `dispose!` is not guaranteed — a host that refuses
            to be released takes the announcement with it.

            The owner's side is unchanged and still perfect: `:closed`
            before the handle ever arrived, connection table and target
            index already empty, exactly one release attempted."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the acquisition cases")
      (async done
        (setup!)
        ;; BOTH doors open, so "which one read" is a measurement.
        (let [close-door       (watch-rejections!)
              [container root] (mount! :watch-errors)]
          (-> (ms/act #(.render root (element [chart-panel {:series [7]}])))
              (.then (fn [_] (ms/act #(ms/destroy-root! container root))))
              (.then (fn [_]
                       (is (= :closed (owner-phase))
                           "torn down while pending — the owner is already terminal")
                       (is (= {} @dispose-attempts)
                           "and nothing was released, because there was nothing yet")
                       ;; ARM, then let the library answer.
                       (reset! brittle #{1})
                       (settle! 0 :ok)))
              (.then (fn [_] (await-escape 20)))

              (.then (fn [_]
                       (is (= {1 1} @dispose-attempts)
                           "the late handle was finalised EXACTLY once")
                       (is (= [[:acquire 1 [7]]] @ops)
                           "and never configured, and never successfully released")
                       (is (= {1 ::unconfigured} @live)
                           "HOST-ONLY LEAK: the library handed over an instance and then
                            refused to take it back")
                       (is (= :closed (owner-phase))
                           "a failed finalizer cannot reopen the owner")
                       (owner-released! "after a throwing late finalizer")
                       (is (= [:promise/unhandled] (mapv first @escapes))
                           "and THIS is where it differs from case 6 — a throw inside the
                            continuation is an unhandled rejection, not a React error")
                       (is (= [] (events))
                           "so the :chart/abandoned announcement AFTER the dispose never
                            ran: a refusing host takes the notification with it")))
              (.catch (fn [e]
                        (is false (str "case rejected: " e))
                        nil))
              (.then (fn [_]
                       (close-door)
                       (done)))))))))
