(ns re-frame.hicasso.test.mounted
  "`hm` — HICASSO'S MOUNTED TEST FACADE, L3 (rf2-hic-027).

  The rung above `re-frame.hicasso.test`. That namespace runs ONE body and
  reads the hiccup it returned; this one puts a real React root on a real
  page and lets React do everything L2 refuses to pretend about —
  lifecycle, hooks, context, refs, error boundaries, foreign hosts, the
  DOM.

      (:require [re-frame.hicasso.test :as ht]        ;; L1–L2
                [re-frame.hicasso.test.mounted :as hm]) ;; L3

  ## Eight doors and one handle

      (hm/mount! form opts)             → handle
      (hm/hydrate! form opts)           → promise of handle
      (hm/render! handle form)          → handle
      (hm/dispatch-and-settle! h event) → handle
      (hm/settle! handle)               → handle
      (hm/advance-clock! handle ms)     → handle
      (hm/unmount! handle)              → handle
      (hm/assert-clean! handle)         → promise of the residue report

  Every door takes the handle first and answers it, so a whole mounted
  test threads:

      (deftest toggle-reaches-the-real-dom
        (async done
          (let [m (hm/mount! [views/todo-row {:id 7}]
                             {:initial-events [[:todo/seed …]]})]
            (is (some? (tl/getByText (:container m) \"Buy milk\")))
            (hm/dispatch-and-settle! m [:todo/toggle 7])
            (is (true? (.-checked (tl/getByRole (:container m) \"checkbox\"))))
            (-> (hm/unmount! m) (hm/assert-clean!) (.then done)))))

  ## Two readers stand outside that pattern

      (hm/census)        → what the runtime RETAINS, right now
      (hm/bodies-run f)  → how many boundary bodies ran while `f` did

  ## And one door that mounts TWO of them

      (hm/shadow! opts)  → {:status :green :checkpoints 4}

  [[shadow!]] is the migration comparator: the Reagent original and the
  Hicasso port, mounted against isolated copies of the same seeded frame
  and driven by one script, with canonical DOM and the intent stream
  compared at every checkpoint. It takes no handle either — it makes two,
  and a caller who held one could reach across the isolation the door
  exists to keep.

  Neither takes a handle, because neither reads a mount: both are
  page-wide readings of the runtime. They are opposite numbers and a
  witness usually wants a particular one of them — [[census]] is residue,
  what survived, and it is what [[assert-clean!]] compares; [[bodies-run]]
  is work, what happened, and it is what a budget stated in bodies is
  asserted with. A keystroke that ran a hundred bodies and a keystroke
  that ran one leave the same census.

  The handle is the runtime's own root handle (`impl.mount`) with this
  namespace's bookkeeping added beside it, so `(:container m)` and
  `(:frame m)` are the real container node and the real frame id rather
  than accessors over a wrapper. Every door in `impl.mount` takes it
  unchanged, including the one [[mount!]] mints for itself
  ([[catching-root!]]).

  ## `mount!` OWNS its frame, and takes no frame argument

  **Frames are isolated contexts.** A mounted app resolves its frame from
  the root it sits under, through the substrate's single internal React
  context, and never from an argument — so there is no frame id to pass
  down a view, and no helper here that could reach across. This facade
  makes that structural rather than documented: `mount!` MINTS a frame of
  its own for every call, seeds it with `:initial-events`, and destroys it
  in [[assert-clean!]].

  There is deliberately no `:frame` option. Two mounted apps therefore
  cannot see each other's state — not by convention, but because neither
  can be handed the other's frame. Anything a test needs the frame FOR is
  on the handle: `(rf/with-frame (:frame m) (deref (rf/subscribe …)))`
  reads it, and [[dispatch-and-settle!]] writes it.

  (The deliberately shared-frame multi-root case is a claim about the
  RUNTIME rather than about a consumer's app, and it belongs where it
  already lives — `re-frame.hicasso.roots-frames-*` drives it against
  `impl.mount` directly.)

  ## No selector language, and no library dependency

  Querying is Testing Library's job and this facade does not take it back.
  `(:container m)` is a real element attached to `document.body`, so
  `getBy*` / `queryBy*` / `within` and `screen` work on it unchanged, and
  a `user-event` sequence dispatches real DOM events at real nodes. What
  this namespace owns is the other half — mounting, settling and
  cleanliness. Nothing here `:require`s `@testing-library/dom`, so a
  consumer chooses (or omits) it freely.

  After anything that stimulates the page from OUTSIDE a facade door — a
  user-event sequence, a raw `.click`, a timer you fired yourself — call
  [[settle!]] before you assert. It commits what already reached React
  and no more: work the router has merely ENQUEUED — a route-link click,
  any async reply — lands on a later task, and [[settle!]] names what to
  wait on instead.

  ## Settled, not `act`

  Every door here commits through `flushSync` and none of them uses
  React's `act`. `act` diverts React's work onto a queue that is not the
  browser's: right for an effect-ordering test, wrong for a witness that
  reads the page. After a door returns, the next line sees the DOM a user
  would have seen. [[hydrate!]] is the one exception and says why.

  ## Time is OPT-IN, and it is a mount's own window

  A tray that retains an exiting child for 300 ms, a debounce, a
  `:dispatch-later` — anything whose next step is a `setTimeout` — cannot
  be asserted without waiting, and a witness that waits on the wall clock
  is the flake this repo keeps out of its gates. So one mount at a time
  may ask for a **virtual clock**:

      (let [m (hm/mount! [tray] {:clock true})]
        (hm/dispatch-and-settle! m [:toast/dismiss 1])
        (hm/advance-clock! m 300)     ;; 300 ms happen, now
        …)

  Time then moves only when [[advance-clock!]] moves it, and it moves
  exactly as far as it is told. See that door for what advances and —
  just as load-bearing — what deliberately does not.

  **It is the mount's window and nothing wider.** The clock is installed
  by [[mount!]] and taken back down by [[unmount!]], with whatever was
  still armed handed back to the real scheduler, so the residue reading
  that follows is the reading it always was. Nothing outside that window
  is affected, and no mount that did not ask gets one.

  ## Refusals: this namespace mints none, and PROPAGATES the runtime's

  Every other file in the kit refuses loudly, and this one deliberately
  does not, for two reasons that agree.

  A malformed form, a bad handle or a hiccup shape the substrate rejects
  is already refused BY THE RUNTIME, with the runtime's own id, reason and
  recovery — which is the whole discipline `re-frame.hicasso.test`
  established (§Children, and the runtime-parity suite). A guard here
  would paraphrase a refusal that already exists.

  What this facade owes that refusal is DELIVERY. [[mount!]] re-throws it
  and [[hydrate!]] rejects with it, each having put the page back first,
  so a refusal raised inside React's render reaches the caller as data
  instead of as an uncaught page error beside a handle for a root that
  rendered nothing. That was PR #7822's audit finding and it is the one
  thing this section used to overstate: the runtime refused all along;
  the facade swallowed it.

  And a residue finding is not a refusal at all: it is a TEST FAILURE, so
  [[assert-clean!]] reports it through `cljs.test/do-report` rather than
  throwing. That distinction is the useful one — the kit REFUSES misuse of
  the instrument and REPORTS a fact about the code under test — and it is
  also what keeps this file free of new `:rf.error/*` ids.
  (`:rf.error/hicasso-test-residue-after-quiescence` was reserved for a
  raising variant of that report and is now a TOMBSTONE in the complaint
  register: residue is settled as a reported test failure, so the
  spelling is retired and will never be raised.)

  [[shadow!]] does not weaken that. Its OPTIONS are its own surface and
  nothing else refuses them, so it does refuse a malformed one — but it
  reuses the kit's existing `:rf.error/hicasso-test-bad-option`, whose
  meaning is *the kit was given options outside their closed contract*
  and whose two recoveries are already the two arms this door needs. An
  id names the refusal rather than the door (complaints.md, *Rulings this
  catalogue owns*), so no id is minted here and the register is unmoved.
  Everything shadow! learns about the CODE, in contrast, is returned as a
  verdict rather than thrown — see that door on why a script step that
  cannot run is a red and not a raise.

  ## Scope

  Dev/test only, and a browser tier: every DRIVING door needs a real
  document. The two page-wide readers do not — [[census]] and
  [[bodies-run]] read runtime tables — but a reading is only worth what
  built the page it describes, so both belong beside a mount all the same.
  Like `re-frame.hicasso.test` it lives in the kit's own source root
  (`hicasso/test_kit/src`), outside the artefact's published `:paths`, so
  nothing in a production bundle can reach it."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.test :as t]
            [re-frame.core :as rf]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

;; ---------------------------------------------------------------------------
;; Bookkeeping
;; ---------------------------------------------------------------------------

;; `!mount-seq` mints the frame keyword and the ordinal a report names.
;;
;; `!facade-frames` is the frames this facade has minted and not yet
;; destroyed. It is SUBTRACTED from the frame delta, so one mount is never
;; blamed for another's frame: `:frames` exists to name a frame the APP
;; opened and left behind, and a sibling mount's frame is neither the app's
;; nor a leak — it is a peer, with an `assert-clean!` of its own.
;;
;; `!standing` is how many mounts are up and not yet taken down. It is
;; REPORTED — a sibling root's cells are inside a reading whether or not
;; anybody remembered it was there, so the reading says so.
;;
;; `!open` is how many mounts have not yet had a verdict, and it decides
;; ONE thing: whether `assert-clean!` may reset.
;; `impl.collector/reset-runtime!` empties every table by fiat and the
;; tables are page-wide, so a reset performed while ANOTHER mount is still
;; waiting to be read makes that mount's eventual reading vacuous — a
;; census taken after a reset answers zero whether the teardown released
;; anything or not, which is the shape of gate that cannot go red
;; (`impl.mount/unmount!`, rf2-2rtt6.48). Unmounted is NOT the same
;; question: two mounts can both be down and only one of them read.
;;
;; A mount that never gets a verdict leaves the counter above zero and
;; suppresses the reset from then on. That is the benign direction and it
;; is why the gate is spelled this way round: a reset that does not happen
;; costs nothing (every reading is relative to its own baseline, and a test
;; fixture resets between tests anyway), while a reset that happens too
;; early manufactures a green.

(defonce ^:private !mount-seq (atom 0))
(defonce ^:private !facade-frames (atom #{}))
(defonce ^:private !standing (atom 0))
(defonce ^:private !open (atom 0))

(def ^:private state-key ::state)
(def ^:private baseline-key ::baseline)
(def ^:private ordinal-key ::ordinal)
(def ^:private clock-key ::clock)

;; ---------------------------------------------------------------------------
;; The clock — virtual time, for the window of a mount that asked for one
;; ---------------------------------------------------------------------------
;;
;; `!clock` is nil, or the whole of the installation: the platform functions
;; that were replaced, the virtual instant, and the timers armed against it.
;; Page-wide, because the thing it replaces is — so it is HOLDER-COUNTED and
;; the last mount to let go is the one that puts the platform back. Two
;; clocked mounts share one clock rather than one silently restoring the
;; globals out from under the other.
;;
;; `:pending` is id → `{:id :f :args :due :period}`. `:period` is nil for a
;; one-shot and the repeat interval for a `setInterval`, which is the only
;; difference between them: both live in one table so that ONE ordering
;; decides what fires next.

(defonce ^:private !clock (atom nil))

(defn- arm!
  "Record one virtual timer and answer its id. `repeat?` distinguishes
  `setInterval` from `setTimeout`; a non-numeric or negative delay is 0,
  as the platform's own is.

  A repeating timer's period is floored at 1 ms. The platform floors it
  too (and clamps harder still for nested timers); without a floor a
  zero-period interval inside an advance would be due forever at the same
  instant and the advance would not terminate.

  **The id counts DOWN from zero, and that is what keeps the two
  schedulers' handles apart.** A platform handle is positive BY
  DEFINITION — HTML's timer initialisation steps mint \"an
  implementation-defined integer that is greater than zero\" — so a
  negative number is one no browser can ever have issued, and the two id
  domains cannot meet however long the window stays open. Without that,
  they overlap from the first timer: [[disarm!]] decides which scheduler
  an id belongs to purely by looking it up, so a virtual timer numbered
  1 answers to the `clearTimeout` of a real timer numbered 1 that was
  armed before the install — the real one lives on and the virtual one
  dies in its place (rf2-w2e6)."
  [repeat? f delay args]
  (let [d (if (and (number? delay) (pos? delay)) delay 0)]
    (- (:seq (swap! !clock
                    (fn [c]
                      (let [n  (inc (:seq c))
                            id (- n)]
                        (-> c
                            (assoc :seq n)
                            (assoc-in [:pending id]
                                      {:id     id
                                       :f      f
                                       :args   args
                                       :due    (+ (:now c) d)
                                       :period (when repeat? (max 1 d))})))))))))

(defn- arm-ordinal
  "The ordinal a timer was armed with. Ids count down from zero (see
  [[arm!]]), so negating one recovers the order it was armed in — which
  is the order timers due at the same instant fire in, and the order a
  handover gives them back in."
  [e]
  (- (:id e)))

(defn- disarm!
  "Drop the virtual timer `id`, and answer whether there was one. False
  means the id is not this clock's — a timer armed before the install, or
  a virtual one that has already fired — and the caller hands it to the
  platform's own clearer, which is what keeps a `clearTimeout` across the
  boundary honest.

  Honest in BOTH directions, because the id domains are disjoint (see
  [[arm!]]): a live platform handle can never be found in this table, and
  a spent virtual handle passed on to the platform names nothing the
  platform holds."
  [id]
  (if (contains? (:pending @!clock) id)
    (do (swap! !clock update :pending dissoc id) true)
    false))

(defn- install-clock!
  "Replace the platform's timer surface and `Date.now` with this clock's,
  or take a second hold on the one already installed. Answers nil.

  The virtual instant STARTS at the real one, so a deadline computed
  before the install and a deadline computed after are the same kind of
  number."
  []
  (if (some? @!clock)
    (swap! !clock update :holders inc)
    (let [g    js/globalThis
          real {:set-timeout    (.-setTimeout g)
                :clear-timeout  (.-clearTimeout g)
                :set-interval   (.-setInterval g)
                :clear-interval (.-clearInterval g)
                :date-now       (.-now js/Date)}]
      (reset! !clock {:holders 1 :real real :now (js/Date.now) :seq 0 :pending {}})
      (set! (.-setTimeout g)  (fn [f delay & args] (arm! false f delay args)))
      (set! (.-setInterval g) (fn [f delay & args] (arm! true f delay args)))
      (set! (.-clearTimeout g)
            (fn [id] (when-not (disarm! id) (.call (:clear-timeout real) g id)) js/undefined))
      (set! (.-clearInterval g)
            (fn [id] (when-not (disarm! id) (.call (:clear-interval real) g id)) js/undefined))
      (set! (.-now js/Date) (fn [] (:now @!clock)))))
  nil)

(defn- release-clock!
  "Let go of one hold, and — on the last one — put the platform back and
  HAND OVER what is still armed. Answers nil.

  The handover is the half that is easy to leave out and impossible to
  see afterwards. The runtime arms reapers of its own inside a mount's
  window (`impl.collector`'s entry and cell reapers, whose horizon
  `impl.inventory/quiesced!` waits out), and a clock that simply dropped
  its table on the way out would strand them — [[assert-clean!]] would
  then report residue the runtime was about to release, which is a red
  nobody can act on. So every outstanding timer is re-armed on the real
  scheduler with the time it had left.

  **An interval's time left is its NEXT TICK, not its period**, and only
  the ticks after that one are a cadence. So a repeating timer goes back
  as a one-shot for what remained of the tick it was already waiting for,
  and that one-shot arms the platform's own repeat at the original
  period. A `setInterval` for the full period here would hand a timer
  with 1 ms to run a whole fresh 1000 and shift its phase for the rest of
  the page's life (rf2-w2e6). The cadence is armed whether or not that
  first tick THROWS, which is what a real repeating timer does — the
  exception is reported and the timer reinitialised — and the exception
  still escapes to be reported (rf2-w2e6).

  What that does not preserve is the ids: from here on they are the
  platform's, so a `clearTimeout` held across the boundary no longer
  reaches its timer. Nothing in the runtime clears a timer it armed once
  its root is down, and a consumer's cleanup runs inside the window
  (React's teardown is [[unmount!]]'s first act, this release its last)."
  []
  (when-some [{:keys [holders real now pending]} @!clock]
    (if (< 1 holders)
      (swap! !clock update :holders dec)
      (let [g js/globalThis]
        (set! (.-setTimeout g)    (:set-timeout real))
        (set! (.-setInterval g)   (:set-interval real))
        (set! (.-clearTimeout g)  (:clear-timeout real))
        (set! (.-clearInterval g) (:clear-interval real))
        (set! (.-now js/Date)     (:date-now real))
        (reset! !clock nil)
        (doseq [e (sort-by arm-ordinal (vals pending))
                :let [left (max 0 (- (:due e) now))]]
          (if-some [p (:period e)]
            (.call (:set-timeout real) g
                   (fn []
                     ;; `finally`, because a REPEATING timer outlives a
                     ;; throwing tick on the platform: HTML's timer
                     ;; initialisation steps report the exception (9.7) and
                     ;; then reinitialise the still-live timer (9.11). Arming
                     ;; the cadence after the call instead lets one throw
                     ;; retire the interval for the rest of the page's life.
                     ;; And the throw still ESCAPES, or the divergence has only
                     ;; moved: a swallowed exception is a tick the page never
                     ;; hears about. Which of the two happens first is not
                     ;; observable — no script runs between them.
                     (try
                       (.apply (:f e) g (to-array (:args e)))
                       (finally
                         (.apply (:set-interval real) g
                                 (to-array (list* (:f e) p (:args e)))))))
                   left)
            (.apply (:set-timeout real) g
                    (to-array (list* (:f e) left (:args e)))))))))
  nil)

(defn- release-clock-for!
  "Let go of `handle`'s hold, at most once however often this is called.
  Answers nil."
  [handle]
  (when-some [held (get handle clock-key)]
    (when @held
      (vreset! held false)
      (release-clock!)))
  nil)

(defn- fire-due!
  "Run every timer due at or before `target`, earliest first and — at one
  instant — in the order they were armed. Answers how many fired.

  The virtual instant moves to each timer's OWN due time before that
  timer runs, never to the end of the window: a callback that reads
  `Date.now` reads the moment it was scheduled for, which is the whole
  reason a deadline comparison inside one (`impl.presence/expire`) can
  come out right.

  A callback that arms another timer inside the window is fired too, by
  construction — the table is re-read on every pass rather than
  snapshotted."
  [target]
  (loop [fired 0]
    (let [c   @!clock
          due (->> (vals (:pending c))
                   (filter (fn [e] (<= (:due e) target)))
                   (sort-by (juxt :due arm-ordinal))
                   first)]
      (if (nil? due)
        (do (swap! !clock update :now max target) fired)
        (do (swap! !clock
                   (fn [c]
                     (let [c (update c :now max (:due due))]
                       (if-some [p (:period due)]
                         (assoc-in c [:pending (:id due) :due] (+ (:due due) p))
                         (update c :pending dissoc (:id due))))))
            (.apply (:f due) js/globalThis (to-array (:args due)))
            (recur (inc fired)))))))

;; ---------------------------------------------------------------------------
;; The census — what the facade knows how to count
;; ---------------------------------------------------------------------------

(def counted
  "The residue counters [[assert-clean!]] compares, in report order.

  `impl.inventory/residue`'s own five, which are the runtime's own
  numbers rather than a copy of them: live cells, the reader memberships
  that are simultaneously the cell references and the dependency edges,
  the distinct boundaries holding one, and the cached read-set entries.

  Data rather than prose because the report iterates it — a counter the
  runtime grows is picked up by construction rather than by somebody
  remembering to add a line here."
  [:cells :cell-refs :boundaries :edges :entries])

(defn census
  "Everything this facade counts, as ONE map — the five runtime residue
  counters plus `:frames`, the set of registered non-destroyed frame ids.

  `:frames` is a SET and not a count on purpose: a delta then NAMES the
  frame that outlived the mount, and a frame id is the one thing in this
  report a reader can act on directly.

  Public because a witness that wants to read the world at a moment the
  facade does not visit should read it through the same door the report
  does, rather than through a second assembly of the same numbers."
  []
  (assoc (inventory/residue) :frames (set (rf/frame-ids))))

;; ---------------------------------------------------------------------------
;; The work counter — the census's opposite number
;; ---------------------------------------------------------------------------

(defn bodies-run
  "**How many boundary bodies ran while `f` did.** [[census]] answers what
  the page RETAINS; this answers what a change COST.

      (is (= 1 (hm/bodies-run #(do (type-into! n \"a\") (hm/settle! m)))))

  The two are not interchangeable and the difference is the whole reason
  this door exists. A keystroke that ran one body and a keystroke that ran
  a hundred leave the SAME census — residue is what survived, not what
  happened — so a budget stated in bodies (product specification §6,
  *narrow-update body work scales with changed rows rather than all
  mounted rows*) could be asserted by nothing this facade offered.

  It is a DELTA, taken by zeroing the runtime's counter and reading it
  back, because the counter is monotone. `f` is run for its effect and
  its value is discarded.

  ## Settle inside `f`, or the count is short

  A body React has not run yet is not in this number. `f` must therefore
  carry whatever makes the work happen *and* whatever commits it —
  [[settle!]] after a raw DOM event, [[dispatch-and-settle!]] for an
  intent, [[advance-clock!]] for anything with a delay on it. The doors
  in this facade all commit through `flushSync`, so a thunk built out of
  them is settled by construction.

  ## Page-wide, and therefore handle-free

  The runtime keeps ONE integer for the whole process, so this reading
  cannot be attributed to a mount and does not pretend to be: like
  [[census]], and unlike every driving door here, it takes no handle. A
  sibling mount that re-renders while `f` runs is inside the number, and
  the remedy is the one [[assert-clean!]] already asks for — take the
  other mounts down, or do not read across them.

  **Nor is there a per-BOUNDARY form**, and that is a fact about the
  runtime rather than an omission here. Attributing runs to one boundary
  needs a durable per-boundary id, which `impl.collector` states it
  cannot have at this arm's fences: the id would have to survive a
  re-subscribe, the shell has no per-instance storage that is not a React
  hook, and a third hook breaks the ≤2-hook budget (HD-020) that
  `hook-budget-cljs-test` enforces at React's own dispatcher. So
  page-wide is what the counter *is*, and per-boundary attribution is
  done the way both witness applications do it — arithmetic over an exact
  expected total.

  ## What it can and cannot see

  It counts bodies React ACTUALLY RAN. The bump is inside the runtime's
  `run-once`, below React's comparator, so a `React.memo` bail-out shows
  up as an increment that did not happen — which makes this a measurement
  of adoption rather than an inference from the comparator's behaviour.
  React's own end-of-event repair RAISES the count rather than hiding it,
  which is the safe direction: the repair cannot make a spurious run look
  like no run.

  **It cannot see a props compare or an element allocation.** A coarse
  read handing scalar props to N children runs the parent and the one
  child whose props moved — the same two bodies a narrow shape runs —
  while allocating N elements and comparing N props maps. Those are real
  costs and this instrument is blind to them, so a witness that read this
  number as *the* cost of a keystroke would be overstating it. What it
  does see is the shape where nothing bails: a child prop carrying a
  closure minted in the parent's render, which never compares equal.

  ## Why the kit owns it

  The counter is `impl.collector`'s and is always-on by an explicit
  ruling (rf2-2rtt6.84 (6)) — the arm's builds are `:advanced` with
  `goog.DEBUG false`, where a debug-gated instrument is not an instrument
  but dead code. Every consumer of it is a test, so the door belongs at
  the tier its consumers run on rather than on the public door, and this
  namespace is where the thing being measured was mounted. It costs a
  production bundle nothing: the kit sits in `hicasso/test_kit/src`,
  outside the artefact's published `:paths`, so a consumer that never
  writes a test never carries it (rf2-5mxe).

  Spec 009's `rf:render:<view-name>` measures are a different instrument
  and not a substitute. They are compiled out unless
  `re-frame.performance/enabled?`, which no PR-lane build sets; and the
  bracket deliberately spans the generation fence's whole retry loop,
  where this counter bumps once per body INVOCATION — so on a body the
  fence re-ran the two disagree by design."
  [f]
  (collector/reset-body-runs!)
  (f)
  (collector/body-runs))

(defn- leaked
  "What `now` has that `baseline` did not — the report's `:leaked` map, or
  `nil` when there is nothing. Only INCREASES are residue; a count that
  fell is a teardown that released more than this mount ever took, which
  is somebody else's business and never this mount's complaint.

  The frame arm subtracts this facade's OWN live frames as well as the
  baseline's, so what `:frames` can name is a frame the APP opened inside
  the mount's window and did not destroy — never a peer mount's."
  [baseline now]
  (let [numbers (reduce (fn [m k]
                          (let [d (- (get now k 0) (get baseline k 0))]
                            (cond-> m (pos? d) (assoc k d))))
                        {}
                        counted)
        peers   (set @!facade-frames)
        frames  (into #{}
                      (remove (fn [f] (or (contains? (:frames baseline #{}) f)
                                          (contains? peers f))))
                      (:frames now))]
    (not-empty (cond-> numbers (seq frames) (assoc :frames frames)))))

(defn- pad
  [s n]
  (apply str s (repeat (max 0 (- n (count s))) " ")))

(defn- leak-line
  [baseline now k v]
  (if (= :frames k)
    (str "  " (pad ":frames" 12) (pr-str v) " still registered")
    (str "  " (pad (str k) 12) (get baseline k 0) " → " (get now k 0) "  (+" v ")")))

(defn- leak-report
  "The sentence a red carries. It says what leaked, by how much, and when
  the baseline it is measured against was taken — and it says nothing
  else. A residue finding is a bug in the code under test; naming it
  precisely is the whole of this instrument's job, and anything beyond
  that is scolding."
  [{:keys [frame baseline now leaked ordinal standing]}]
  (str "the mount left residue behind after quiescence.\n"
       "The baseline was taken inside mount! #" ordinal ", after this mount's own\n"
       "frame " frame " existed and before its root did; the reading below was\n"
       "taken after unmount! and after the runtime's own reaper horizon.\n\n"
       (str/join "\n" (map (fn [[k v]] (leak-line baseline now k v)) leaked))
       "\n\nSomething outlived the root. A retained subscription, a foreign host that\n"
       "mounts its own root, or a frame the app made and did not destroy."
       (when (pos? standing)
         (str "\n\n" standing " other facade mount(s) were still standing when this reading\n"
              "was taken, so their cells are inside it. Take every mount down before\n"
              "asserting any of them clean."))))

;; ---------------------------------------------------------------------------
;; Mounting
;; ---------------------------------------------------------------------------

(defn- mint-frame!
  "This mount's own frame — a keyword nothing else can name, made live and
  seeded in one call. Answers `[frame-kw ordinal]`.

  `:initial-events` reaches `rf/make-frame` rather than being dispatched
  here afterwards, because that is core's own construction channel: the
  steps run in order and drain to fixed point BEFORE the call returns, so
  the frame the root is about to render against is already at rest. A
  seeding loop written here would be a second mechanism wearing core's
  spelling."
  [initial-events]
  (let [ordinal  (swap! !mount-seq inc)
        frame-kw (keyword "re-frame.hicasso.test.mounted" (str "mount-" ordinal))]
    (rf/make-frame (cond-> {:id frame-kw}
                     (seq initial-events) (assoc :initial-events (vec initial-events))))
    (swap! !facade-frames conj frame-kw)
    [frame-kw ordinal]))

(defn- handle-for
  "The runtime's root handle, made this facade's — and the moment a
  construction BECOMES a mount. Nothing before this line counts against
  the standing or the unread census, which is what lets [[abandon!]]
  restore the page without touching either: a mount that never was is
  not a mount that came down.

  `clock?` puts this mount's HOLD on the virtual clock onto the handle,
  as a volatile the release flips: the hold is a fact about the mount and
  belongs with the mount's other two, so [[unmount!]] and [[residue]] can
  each let go without either of them having to know whether the other
  already did."
  [root ordinal baseline clock?]
  (swap! !standing inc)
  (swap! !open inc)
  (cond-> (assoc root
                 baseline-key baseline
                 ordinal-key  ordinal
                 state-key    (volatile! :mounted))
    clock? (assoc clock-key (volatile! true))))

;; ---------------------------------------------------------------------------
;; Construction is TRANSACTIONAL
;; ---------------------------------------------------------------------------
;;
;; A mount allocates three things before it can be a mount — a frame, a
;; container, a React root — and until PR #7822's merged-PR audit the failure
;; of the third left the first two standing.
;;
;; The audit drove it. A valid registered view whose body holds a plain
;; function child head is a genuine `:rf.error/hicasso-bad-head`; React 19
;; does not re-throw a failed render out of `flushSync`, it hands the error
;; to the root's `onUncaughtError`, whose default reports it globally and
;; returns. So `mount!` answered a handle for a root that had rendered
;; nothing, the page carried an uncaught error a browser runner treats as
;; fatal, and the programmer had neither the refusal's data nor a teardown
;; that would have found the leftovers.
;;
;; `hydrate!` had the same shape twice over: a refusal raised on
;; `hydrate-root!`'s own stack escaped a promise-returning door
;; SYNCHRONOUSLY, past every `.catch` a caller could attach, and its timeout
;; branch rejected without taking the half-adopted root down.
;;
;; So both doors now either complete or leave nothing. [[abandon!]] is the
;; allocation order run backwards, and it is the whole of the machinery:
;; there is no lifecycle here and nothing tracks a resource. Each door knows
;; what it allocated because it allocated it three lines earlier.

(defn- catching-root!
  "`impl.mount/root!`, with THIS root's uncaught render errors handed to
  `catch!` instead of to React's default. Answers the handle either way,
  because a root whose first render failed still has to be taken down.

  Minted here rather than taken from `impl.mount` because the divergence
  is the INSTRUMENT's and not the runtime's. A shipped root wants React's
  default — the error reported at the window, where a host's own reporter
  and the browser console can see it — and a test facade wants it
  returned, so that [[mount!]] can put the page back and re-throw the
  runtime's own refusal on the caller's stack. Installing any
  `onUncaughtError` takes React's default off, which is both halves of
  that: the refusal is captured, and the page never carries the uncaught
  error.

  Two channels reach `catch!` and both are real. A body that throws is
  caught by React and arrives through the root option. A root FORM the
  codec refuses — `[]`, a head it will not classify — throws on this
  call's own stack, before React is handed an element, so the `try` is
  the other half. Joining them here is what lets a caller read one
  volatile."
  [container frame-kw hiccup catch!]
  (let [handle {:root      (react-dom-client/createRoot
                             container
                             #js {:onUncaughtError (fn [error _info] (catch! error))})
                :frame     frame-kw
                :container container}]
    (try (mount/render! handle hiccup)
         (catch :default e (catch! e)))
    handle))

(defn- abandon!
  "Put the page back as the call found it, after a construction that never
  became a mount. Answers nil.

  The allocation order, backwards: the root comes down — which also shuts
  an adoption window whose closer will now never run — a container this
  facade appended is removed, and the frame is destroyed and forgotten.

  A container the CALLER supplied is left exactly where it was, with
  whatever it held. That is [[unmount!]]'s rule and it is the same rule
  for the same reason: a teardown may not delete a node it did not
  create (rf2-31xm).

  Neither counter is here, and that is deliberate rather than an omission
  — see [[handle-for]].

  **Taking down a root that never adopted makes React complain, and it is
  right to.** An update to a root mid-hydration IS a switch to client
  rendering, so React queues a hydration error, which reaches the
  reporter `impl.mount/hydrate-root!` installs and — that reporter always
  delegating to React's default (rf2-mwx08) — the window. Leaving the
  root standing instead would be worse: the hydration is still scheduled,
  and it would commit into a detached container after this call returned.
  So the rollback takes it down and the complaint is the honest residue
  of a hydration that failed."
  [frame-kw node supplied? root]
  (when (some? root) (mount/unmount! root))
  (when-not supplied?
    (when-some [p (.-parentNode node)] (.removeChild p node)))
  (swap! !facade-frames disj frame-kw)
  (rf/destroy-frame! frame-kw)
  nil)

(defn mount!
  "Mount `form` on a fresh React root under a frame of this mount's own,
  and answer the handle.

      (hm/mount! [todo-row {:id 7}]
                 {:initial-events [[:todo/seed [{:id 7 :title \"Buy milk\"}]]]})

  `form` is ordinary hiccup — `[some-view props]`, a `defview` head where
  a call site would write one — and it is handed to the runtime untouched,
  so a form the substrate refuses is refused by the substrate with the
  substrate's own id.

  `opts`:

      :initial-events  a vector of setup steps dispatched into the new
                       frame at construction, in order, draining to fixed
                       point before the mount renders. Core's own
                       `rf/make-frame` vocabulary; seed a literal app-db
                       with `[[:rf/set-db {…}]]`.
      :container       an existing DOM element to render into. The default
                       is a fresh `<div>` appended to `document.body` —
                       attached, so `screen`-scoped Testing Library
                       queries and real focus both work.
      :clock           `true` installs a virtual clock for this mount's
                       window, so a timed transition is driven by
                       [[advance-clock!]] instead of waited on. Installed
                       BEFORE anything else this call does, so a timer
                       armed by `:initial-events` or by the first render's
                       effects is already this clock's; released by
                       [[unmount!]], or by the throw that stops this call
                       ever answering a handle.

  ## What it records before it renders

  The residue BASELINE, taken after this mount's frame exists and before
  its root does. [[assert-clean!]] compares against it rather than against
  zero, which is what makes the reading a statement about THIS mount: a
  page that already held a live root when the mount began is not this
  mount's residue, and a test that opened one is not made to answer for
  it.

  The frame is this facade's — minted, made and destroyed here. See the
  namespace docstring on why there is no `:frame` option.

  ## It either mounts or it THROWS, and a throw leaves nothing behind

  A form the runtime refuses is refused here too, with the runtime's own
  `ex-info` re-thrown unchanged — same id, same reason, same recovery,
  same offending value — and the page put back exactly as this call found
  it: no frame registered, no container appended, no counter moved, and
  no clock left installed. So
  `(try (hm/mount! form) (catch :default e (ex-data e)))` is the whole of
  a refusal witness, and there is no handle to be given because there is
  nothing left to tear down.

  The clock is the one allocation [[abandon!]] cannot undo, because it is
  taken before there is anything to hang it on: a `:initial-events` step
  that fails leaves from `mint-frame!` with core's own
  `:rf.error/initial-events-step-failed`, before a frame, a container, a
  root or a handle exists. So the hold belongs to the CALL until
  [[handle-for]] hands it to the mount, and the `try` below is that
  lifetime — one release, on every escaping path, of exactly the one hold
  this call took (rf2-4mvd). A clocked peer mount therefore keeps the
  clock it is standing on: the release is a decrement, and the failed
  call gives back only its own.

  React 19 is why this needs saying: a body that throws does not re-throw
  out of `flushSync`, so before PR #7822's audit this door answered a
  handle for a root that had rendered nothing and reported the error at
  the window instead. See [[catching-root!]]."
  ([form] (mount! form {}))
  ([form {:keys [initial-events container clock]}]
   ;; The clock is the FIRST allocation and the last release, because the
   ;; seeding below already runs handlers and the render below already
   ;; runs effects — either can arm a timer, and one armed on the platform
   ;; clock is one this mount can never drive.
   ;;
   ;; ONE release covers every way out of this call that is not a handle,
   ;; the render refusal below included — it throws through this catch
   ;; rather than releasing for itself, so no path can decrement twice
   ;; and restore the platform out from under a clocked peer.
   (when clock (install-clock!))
   (try
     (let [[frame-kw ordinal] (mint-frame! initial-events)
           baseline           (census)
           node               (or container (mount/fresh-container!))
           !refusal           (volatile! nil)
           root               (catching-root! node frame-kw form
                                              (fn [error]
                                                (when (nil? @!refusal)
                                                  (vreset! !refusal error))))]
       (if-some [refusal @!refusal]
         (do (abandon! frame-kw node (some? container) root)
             (throw refusal))
         (handle-for root ordinal baseline (boolean clock))))
     (catch :default e
       (when clock (release-clock!))
       (throw e)))))

(defn hydrate!
  "Mount `form` by ADOPTING server-rendered bytes already in the
  container, and answer a **promise** of the handle — resolved once this
  root's own adoption window has shut.

      (-> (hm/hydrate! [app {}] {:html server-bytes})
          (.then (fn [m] … (hm/unmount! m) …)))

  `opts` is [[mount!]]'s, plus one of:

      :html       server bytes; a fresh attached container is created
                  carrying them
      :container  a container you already filled

  `:clock` is honoured, and installed once adoption has completed rather
  than before it — see the resolve branch below for why that is the only
  point at which it can be.

  ## Why this one answers a promise

  `impl.mount/hydrate-root!` deliberately performs no `flushSync`:
  nothing in the runtime forces adoption synchronously, and a flush there
  would manufacture a schedule no shipped caller has. So the call returns
  while the DOM on screen is still the SERVER's, and a facade that handed
  back a bare handle would hand back one whose next line reads the wrong
  page.

  The promise resolves on this root's own adoption window closing — the
  closer component's passive effect, which is the earliest moment that is
  unambiguously after the hydration commit — and then one macrotask
  later, past the entry reaper. A sibling root still adopting cannot
  resolve it: the window belongs to this root and nothing else can shut
  it.

  `budget-ms` (default 3000) bounds the wait. Exceeding it REJECTS rather
  than resolving with a handle whose adoption never happened — a hydration
  that silently timed out and then asserted green is the exact failure
  this door exists to prevent.

  ## Every failure is a REJECTION, and every failure leaves nothing behind

  Both of them: a form the codec refuses while `hydrate-root!` is still
  building its element, which raises on this call's own stack, and an
  adoption that never completes. A promise-returning door that threw
  synchronously would throw past every `.catch` a caller had attached and
  hang the test rather than fail it, so the refusal is handed to the
  promise — the runtime's own `ex-info` unchanged for the first, this
  door's timeout for the second.

  In both cases the page is put back as the call found it: the frame
  destroyed, the root taken down with its adoption window shut, and a
  container this facade minted removed. One the CALLER supplied is left
  where it is (see [[abandon!]])."
  ([form] (hydrate! form {}))
  ([form opts] (hydrate! form opts 3000))
  ([form {:keys [initial-events container html clock]} budget-ms]
   (let [[frame-kw ordinal] (mint-frame! initial-events)
         baseline  (census)
         node      (or container (mount/fresh-container!))
         supplied? (some? container)
         _         (when (some? html) (set! (.-innerHTML node) html))
         !refusal  (volatile! nil)
         root      (try (mount/hydrate-root! node frame-kw form)
                        (catch :default e (vreset! !refusal e) nil))]
     (if-some [refusal @!refusal]
       (do (abandon! frame-kw node supplied? nil)
           (js/Promise.reject refusal))
       (let [window   (:adoption root)
             deadline (+ (js/Date.now) budget-ms)]
         (js/Promise.
           (fn [resolve reject]
             (letfn [(tick []
                       (cond
                         (not (roots/adopting? window))
                         ;; Past the closer AND past the reap horizon, so the
                         ;; handle a caller receives is one whose tables have
                         ;; settled. This is also where the construction
                         ;; becomes a MOUNT — see [[handle-for]].
                         ;;
                         ;; `:clock` is installed HERE and not at the top of
                         ;; this door, unlike [[mount!]]'s. Adoption is
                         ;; React's own concurrent business and this promise
                         ;; is driven by real `setTimeout`s that wait it out;
                         ;; a clock installed before them would freeze the
                         ;; wait it is inside, and the caller would hold a
                         ;; promise that can never resolve. Nothing is lost:
                         ;; an adopted tray is born settled (rf2-2rtt6.84),
                         ;; so the timers a hydrated page arms are armed
                         ;; after this line.
                         (js/setTimeout
                           (fn []
                             (when clock (install-clock!))
                             (resolve (handle-for root ordinal baseline
                                                  (boolean clock))))
                           16)

                         (< deadline (js/Date.now))
                         (do (abandon! frame-kw node supplied? root)
                             (reject (ex-info (str "hydrate! waited " budget-ms
                                                   "ms and this root's adoption window "
                                                   "never shut — the tree was not adopted.")
                                              {:frame frame-kw :budget-ms budget-ms})))

                         :else (js/setTimeout tick 4)))]
               (tick)))))))))

;; ---------------------------------------------------------------------------
;; Driving
;; ---------------------------------------------------------------------------

(defn render!
  "Render `form` into `handle`'s existing root, synchronously, and answer
  the handle. The props-change door: same root, same frame, same DOM
  nodes wherever React can keep them, so identity and effect claims are
  about a re-render rather than about a remount.

  Takes a hydrated handle unchanged."
  [handle form]
  (mount/render! handle form)
  handle)

(defn settle!
  "Let everything React has already scheduled commit, and answer the
  handle. The empty `flushSync` — no work of its own.

  Call it after anything that stimulated the page from outside a facade
  door and whose handlers have ALREADY RUN: a `user-event` sequence, a
  raw `.click` on an element carrying a Hicasso intent, a timer you
  fired yourself. An intent lowered in a body dispatches through the
  arm's frame-locked SYNCHRONOUS door, so by the time the click returns
  the handlers have run and app-db has moved; this commits the echo, and
  the next line reads the DOM a user would be looking at.

  ## It cannot reach work that is merely ENQUEUED

  That promise is about React, and it is only as good as what reached
  React before the flush. **Two ordinary clicks on one page settle
  differently, and nothing at either call site says which is which.**

  - A **route-link** click ends in `re-frame.routing/activate-link!`,
    which ends in `router/dispatch!` — the ASYNC door. The navigation is
    enqueued and the router drains it on `interop/next-tick`, a
    next-turn TASK, so at this line nothing is scheduled in React and
    the flush has nothing to flush.
  - Every **async reply** arrives the same way, and there it is the
    application being ordinary rather than routing being special: an fx
    that replies with `rf/dispatch` after the drain that started it has
    finished — an HTTP response, a `.then`, a `setTimeout` — enqueues
    rather than runs.

  Awaiting a promise does not close the gap either, because the drain is
  a MACROTASK and a `.then` runs at the microtask checkpoint ahead of
  it. Nor does `{:clock true}`: firing a timer only enqueues the reply,
  and [[advance-clock!]] deliberately does not drive the task the drain
  rides on.

  So wait on the CONDITION rather than on this door.
  `re-frame.test-support/poll-until` is the supported condition-poll —
  a published core door, not an internal — and it returns a promise that
  composes with `cljs.test/async`. This facade has no verb of its own
  for *work is enqueued in the router; let it land*, and whether it
  should is **rf2-6m4w**, which owns that question.

  The flush is React's and is therefore page-wide; the handle is taken so
  that every door in this facade reads the same way."
  [handle]
  (mount/settle!)
  handle)

(defn advance-clock!
  "Move this mount's virtual clock forward by `ms`, run everything that
  falls due on the way, and answer the handle.

      (let [m (hm/mount! [tray] {:clock true :initial-events […]})]
        (hm/dispatch-and-settle! m [:toast/dismiss :b])
        (is (= 2 (count (toasts m))))   ;; retained, mid-exit
        (hm/advance-clock! m 300)
        (is (= 1 (count (toasts m)))))  ;; the deadline passed

  Requires `{:clock true}` on the [[mount!]] or [[hydrate!]] that made
  `handle`, and throws without it. That is not decoration: an advance
  with no clock under it would move nothing and assert nothing, and the
  row would go green for the reason it was written to rule out.

  ## What advances

  **`setTimeout`, `setInterval`, and `Date.now` — the three together, in
  lockstep.** The clock is the whole of them or it is a trap: retention
  is a deadline COMPARISON (`impl.presence/expire` takes `now`), so a
  fake timer whose callback reads an unmoved `Date.now` fires on time and
  then decides nothing has expired. The instant moves to each timer's own
  due time before that timer runs, so a callback reads the moment it was
  scheduled for; a callback that arms another timer inside the window is
  fired in its turn.

  ## What deliberately does NOT advance, and why each is right

  - **`requestAnimationFrame`.** A frame is a paint, not a duration, and
    `ms` says nothing about how many of them there were. It is left on
    the platform's own schedule, where it still fires — so a rAF-driven
    witness is not blocked by the clock, it is simply not driven by it.
    (The substrate arms none: presence's whole frame budget is zero rAF
    and zero interval, and `re-frame.hicasso.motion-presence-dom-cljs-test`
    is what says so.)
  - **Microtasks, and therefore promises.** A microtask queue cannot be
    drained from inside a task by anything in userland, so no control
    could honestly claim it. A `.then` still lands where it always did —
    after this door returns, not inside it.
  - **`performance.now`.** React's scheduler reads it to decide whether
    it has time left in the frame; a clock that jumped it forward would
    be telling React it is out of budget on every advance.
  - **The `Date` CONSTRUCTOR.** `(js/Date.)` reads the system clock
    rather than `Date.now`, and is not this window's.
  - **A `setTimeout` somebody CAPTURED before the window opened.** The
    clock replaces the global, so it reaches every call that looks the
    global up when it fires — which is every `(js/setTimeout …)` in
    ClojureScript, and the substrate's presence timers among them. React's
    own scheduler is the deliberate exception and reads the reference it
    took at module load: React keeps its real scheduler, and a flush is
    still a flush.

  ## It does not replace [[settle!]]; it is that flush with work in it

  The due callbacks run INSIDE the same `flushSync` [[settle!]] performs
  empty, so an update a timer schedules is committed before this returns
  and the next line reads the repainted page — the rule every door here
  keeps. Nothing about the runtime's own settling is duplicated or
  bypassed: after stimulating the page from outside a door, [[settle!]]
  is still the call to make, and this one is [[settle!]] for the work
  that had a delay on it."
  [handle ms]
  (let [held (get handle clock-key)]
    (when-not (and (some? held) @held)
      (throw (ex-info (str "advance-clock! moves the virtual clock a mount owns, "
                           "and this handle owns none"
                           (if (some? held)
                             " any longer — it was released when the mount came down."
                             ": mount! or hydrate! was not given {:clock true}.")
                           " An advance with no clock under it would move nothing "
                           "and assert nothing.")
                      {:ms ms :frame (:frame handle)})))
    (react-dom/flushSync (fn [] (fire-due! (+ (:now @!clock) ms))))
    handle))

(defn dispatch-and-settle!
  "Dispatch `event` into this mount's frame, drain it, commit the echo,
  and answer the handle.

  The dispatch is the runtime's own frame-locked synchronous door — the
  same one an intent written in a view reaches — so handlers really run,
  app-db really moves, and the commit React schedules for the store
  notification lands before this returns. Assert the DOM on the next
  line.

  It is not `act`: see the namespace docstring."
  [handle event]
  (mount/dispatch! handle event)
  handle)

(defn unmount!
  "Tear the root down and answer the handle.

  It touches NOTHING the runtime holds. Whatever cells, edges and cached
  closures survive this call are exactly the ones React's own cleanup
  failed to release, which is what makes [[assert-clean!]] able to see
  them — a teardown that reset the tables first would answer clean whether
  the unmount released anything or not.

  **The container stays in the document, emptied** (rf2-31xm). It used to
  be detached, which is harmless for the `<div>` [[mount!]] appends and
  wrong for the one a caller supplies as `:container` — a test's own node,
  deleted out from under it by a teardown that did not create it. React's
  `root.unmount()` empties a container and leaves it where it is, and
  `impl.mount/unmount!` now does no more than that.

  **A `:clock` mount's clock is released here**, after React's teardown
  and not before it: a component's cleanup clears the timers it armed,
  and it has to be able to reach them. What is still armed afterwards
  goes back to the real scheduler with the time it had left — see
  [[advance-clock!]] for the window and `release-clock!` for the
  handover.

  Idempotent: unmounting twice is not an error, and only the first call
  counts against the standing-mount census."
  [handle]
  (mount/unmount! handle)
  (when (= :mounted @(get handle state-key))
    (vreset! (get handle state-key) :unmounted)
    (swap! !standing dec))
  (release-clock-for! handle)
  handle)

;; ---------------------------------------------------------------------------
;; Cleanliness
;; ---------------------------------------------------------------------------

(defn residue
  "**What this mount left behind, as data.** Answers a promise of the
  report; asserts nothing and resets nothing.

      {:clean?         false
       :frame          :re-frame.hicasso.test.mounted/mount-3
       :ordinal        3
       :still-mounted? false
       :standing       0
       :baseline       {:cells 0 :cell-refs 0 … :frames #{…}}
       :now            {:cells 2 :cell-refs 2 … :frames #{…}}
       :leaked         {:cells 2 :cell-refs 2 :boundaries 1 :edges 2}}

  The reading is taken after `impl.inventory/quiesced!` — the runtime's
  OWN settling point, not a macrotask. The entry reaper's horizon sits
  deliberately outside a bare `setTimeout 0`, so a census taken one
  macrotask after an unclaimed render still counts entries the runtime is
  about to drop, and a gate comparing against such a reading fails on
  every arm whose row outlives the horizon.

  `:leaked` is absent when the mount was clean, and only ever names
  INCREASES against the baseline (see [[census]] for what is counted and
  [[mount!]] for when the baseline was taken).

  `:still-mounted?` and `:standing` are the two facts that explain a
  reading rather than being read out of the tables: this mount's own root
  still being up, and how many OTHER facade mounts were up when the census
  was taken. Both are inside the numbers, so both are stated beside them.

  This is [[assert-clean!]]'s other half, and it exists so the instrument
  can have a sabotage control of its own: a witness that deliberately
  leaks must be able to read the verdict rather than fail on it.

  **It lets go of a `:clock` mount's clock first**, and that is a repair
  rather than a courtesy. The reading below waits out the runtime's own
  reaper horizon on a `setTimeout`, so a virtual clock still installed
  here would freeze the wait and this promise would never settle —
  turning the one reported failure this door exists to make loud (a
  reading taken on a mount nobody unmounted) into a hung async test.
  [[unmount!]] normally gets there first; this is the case where it was
  not called at all."
  [handle]
  (release-clock-for! handle)
  (let [baseline (get handle baseline-key)
        mounted? (= :mounted @(get handle state-key))]
    (.then (inventory/quiesced!)
           (fn [_]
             (let [now (census)
                   l   (leaked baseline now)]
               (cond-> {:clean?         (nil? l)
                        :frame          (:frame handle)
                        :ordinal        (get handle ordinal-key)
                        :still-mounted? mounted?
                        :standing       (cond-> @!standing mounted? dec)
                        :baseline       baseline
                        :now            now}
                 (some? l) (assoc :leaked l)))))))

(defn assert-clean!
  "**The cleanliness assertion.** Waits for quiescence, compares the
  residue with this mount's pre-mount baseline, REPORTS, and only then
  resets. Answers a promise of the report [[residue]] describes.

      (-> (hm/unmount! m) (hm/assert-clean!) (.then done))

  ## What it reports

  Through `cljs.test/do-report`, so a residue finding is an ordinary
  `FAIL in (your-test)` naming exactly what leaked — not a thrown refusal
  a `.then` chain would swallow into a hung async test, and not a boolean
  the caller has to remember to assert. It never throws, so the promise
  never rejects and `(.then done)` is the whole of the plumbing.

  Two things fail it:

  - **residue** — a counter above its baseline, or a frame that outlived
    the mount;
  - **a mount still standing** — calling this before [[unmount!]]. Reported
    on its own rather than allowed to present as residue, because a live
    root's cells ARE residue by every number here and the resulting red
    would send a reader hunting a leak that is really a missing teardown.

  ## And only then resets

  `impl.collector/reset-runtime!` runs after the reading, never before —
  a census taken after a reset answers zero whether the teardown released
  anything or not. This mount's frame is destroyed with it.

  The reset is page-wide, so it is held back while any OTHER mount is
  still waiting to be read — not merely while one is still standing, since
  two mounts can both be down and only one of them read, and resetting
  between them would make the second reading vacuous. See `!open`. The
  last mount to be asserted does the clearing."
  [handle]
  (.then (residue handle)
         (fn [report]
           (t/do-report
             (cond
               (:still-mounted? report)
               {:type     :fail
                :message  (str "assert-clean! was called on a mount that is still "
                               "standing. Call unmount! first: this reading is "
                               "about what survives a teardown, and there has "
                               "not been one.")
                :expected '(unmounted? handle)
                :actual   report}

               (:clean? report)
               {:type     :pass
                :message  (str "the mount left no residue: every counter is back "
                               "at its pre-mount baseline")
                :expected '(clean? handle)
                :actual   report}

               :else
               {:type     :fail
                :message  (leak-report report)
                :expected '(clean? handle)
                :actual   (:leaked report)}))
           ;; The verdict is taken ONCE per mount: a second call reports
           ;; again (it is an assertion, and a repeated assertion is
           ;; allowed to speak) but moves no bookkeeping, so `!open`
           ;; cannot be driven below the number of mounts still waiting
           ;; to be read.
           (when (= :unmounted @(get handle state-key))
             (vreset! (get handle state-key) :read)
             (when (zero? (swap! !open dec))
               (collector/reset-runtime!))
             (swap! !facade-frames disj (:frame handle))
             (rf/destroy-frame! (:frame handle)))
           report)))

;; ---------------------------------------------------------------------------
;; Shadow comparison — the dual-render DOM / intent diff (rf2-kyum)
;; ---------------------------------------------------------------------------
;;
;; Every other door in this file is about ONE mount. This one is about the
;; relation between two, and the whole of its difficulty is in making that
;; relation decidable: two mounts that must be isolated from each other, and
;; a comparison that must be fair between them.
;;
;; ISOLATION IS NOT NEGOTIABLE. Subscriptions may not reach across frames, so
;; the two implementations cannot share one — `mount!` mints a frame per call
;; and there is no `:frame` option to defeat that (see the namespace
;; docstring). Each side is seeded from the SAME `:initial-events` vector, so
;; the pair is one application in two isolated copies of one seeded world.
;;
;; AND ISOLATION IS EXACTLY WHAT MAKES THE COMPARISON UNFAIR, at one slot.
;; `capsule-replay-verdict.md` states the general result and names this door
;; while doing it: *the tree a Hicasso body returns is not pure data with
;; respect to the frame it ran in*. A `h/route-link` captures the rendering
;; frame at render time and bakes its keyword into the anchor's navigate
;; intent as ordinary data, so two isolated mounts of the SAME view emit two
;; different intents — a red that is guaranteed, universal, and about nothing
;; the programmer wrote. The census behind that verdict counts 106 route-links
;; across 85 idiomatic files, so this is the common shape and not a corner.
;;
;; So the comparison is taken MODULO EACH MOUNT'S OWN ADDRESS: each side's own
;; frame keyword normalises to [[this-frame]] before anything is compared, in
;; both streams. That is a weaker claim than raw equality and the verdict asks
;; for it to be registered as such rather than slipped in, which is what this
;; comment and [[shadow!]]'s docstring are. It is narrow in the way that
;; matters: only a mount's OWN keyword normalises, so a genuine cross-frame
;; address — one side reaching a frame the other does not — still reddens.

(def this-frame
  "The stand-in each mount's OWN frame keyword is compared as.

  A shadow run compares two mounts that are isolated BY CONSTRUCTION, so
  their frame keywords differ by construction too; an intent or an
  attribute carrying one carries an address rather than a fact about the
  application. Both sides' addresses normalise to this one keyword, so a
  difference reported by [[shadow!]] is never merely the two mounts being
  two mounts. It appears in a red report wherever a normalised slot did."
  ::this-frame)

(def ^:private shadow-options
  "[[shadow!]]'s closed option roster. Closed for `tree`'s reason: an
  option quietly ignored is a setting its author believes is in force,
  and `:seed` — this surface's own earlier spelling for `:initial-events`
  — would otherwise mount two UNSEEDED views and compare them happily."
  #{:reference :candidate :initial-events :script})

(def ^:private step-verbs
  "The script's closed verb roster. A step is one of these keys and
  nothing else. A misspelled verb would drive neither side, both would
  stay exactly where they were, and the checkpoint would go green for a
  step that never happened — the vacuous green this whole door exists to
  be able to fail."
  #{:click :type})

(defn- bad-option!
  "Refuse a malformed shadow option, reusing the kit's own
  `:rf.error/hicasso-test-bad-option`. See the namespace docstring §Refusals
  on why this door refuses at all and why it mints no id to do it."
  [reason recovery extra]
  (throw (ex-info (str reason " [:rf.error/hicasso-test-bad-option]")
                  (merge extra
                         {:rf.error/id :rf.error/hicasso-test-bad-option
                          :where       're-frame.hicasso.test.mounted
                          :reason      reason
                          :recovery    recovery}))))

;; ---------------------------------------------------------------------------
;; The intent stream
;; ---------------------------------------------------------------------------

(defonce ^:private !shadow-seq (atom 0))

(defn- open-log!
  "Register ONE `:events` listener over every frame, and answer
  `{:key … :drain …}` where `drain` empties the buffer and answers what
  was in it as `[frame-kw event-v]` pairs.

  Spec 009's `:events` observation port, which is the same port
  `re-frame.hicasso.test/capture-intents` takes its reading at — this
  variant exists only because it must be armed BEFORE either frame
  exists. `capture-intents` filters to a frame keyword the caller already
  has; a shadow run does not have one until `mount!` has minted it, and
  the intents a seeding step and a first render dispatch are inside the
  window that mint opens. So the listener spans every frame and the
  buckets are taken afterwards, which reads the same records through the
  same door."
  []
  (let [!log (atom [])
        k    (keyword "re-frame.hicasso.test.mounted"
                      (str "shadow-" (swap! !shadow-seq inc)))]
    (rf/register-listener! :events k
                           (fn [record]
                             (swap! !log conj [(:frame record) (:event record)])))
    {:key   k
     :drain (fn [] (let [entries @!log] (reset! !log []) entries))}))

(defn- close-log! [log] (rf/unregister-listener! :events (:key log)))

(defn- for-frame
  "The events one frame dispatched, in order, out of a drained buffer."
  [entries frame-kw]
  (into [] (comp (filter #(= frame-kw (nth % 0))) (map #(nth % 1))) entries))

;; ---------------------------------------------------------------------------
;; The address normalisation
;; ---------------------------------------------------------------------------

(defn- de-address
  "One mount's own frame keyword replaced by [[this-frame]], anywhere
  inside an intent vector. `postwalk` because the keyword is ordinary
  data at an arbitrary depth — `h/route-link` puts it in a map inside the
  vector — and there is no slot to look in."
  [frame-kw x]
  (walk/postwalk #(if (= % frame-kw) this-frame %) x))

(defn- de-address-text
  "The same substitution over TEXT — a DOM attribute value, a text node.
  Both printed spellings of the keyword, qualified and bare-with-colon,
  because an application writes one or the other; the unqualified `name`
  is deliberately NOT replaced, since `mount-3` alone is a string an
  application could legitimately hold."
  [frame-kw s]
  (-> s
      (str/replace (str frame-kw) (str this-frame))
      (str/replace (subs (str frame-kw) 1) (subs (str this-frame) 1))))

;; ---------------------------------------------------------------------------
;; The DOM difference — the FIRST node at which the two pages disagree
;; ---------------------------------------------------------------------------
;;
;; `re-frame.hicasso.test/canonical-dom` answers WHETHER two pages differ; a
;; red owes the programmer WHERE. This walk is that second question, and over
;; MARKUP it is deliberately the same equality: comments are dropped and
;; adjacent text runs join, exactly as they do when `canonical-dom` flattens a
;; subtree to one string, so the two instruments cannot disagree about the
;; bytes of a page.
;;
;; It then compares one thing more, which a serialiser structurally cannot:
;; the LIVE CONTROL STATE below. So the two instruments can disagree after
;; all, in exactly one direction — this walk reddens pairs `canonical-dom`
;; calls equal, never the reverse — and that direction is the repair rather
;; than a drift.

(def ^:private absent
  "What a difference reports where one side has no value at all — a
  missing attribute. Distinct from `nil`, which no attribute has."
  ::absent)

(defn- node-tag [el] (str/lower-case (.-tagName el)))

(defn- attrs-of
  [el frame-kw]
  (persistent!
    (reduce (fn [m a] (assoc! m (.-name a) (de-address-text frame-kw (.-value a))))
            (transient {})
            (array-seq (.-attributes el)))))

;; ---------------------------------------------------------------------------
;; The live control state — the half of a form control that is not in the page
;; ---------------------------------------------------------------------------
;;
;; A form control has TWO values and only one of them is in the page's bytes.
;; The content attribute is the DEFAULT — `value` is `defaultValue`, `checked`
;; is `defaultChecked`, an option's `selected` is `defaultSelected` — and the
;; live one is a property no serialiser can reach. React writes the live one
;; directly, so `HTMLSelectElement.value` moves `option.selected` while
;; `outerHTML` stays byte-identical.
;;
;; Which made this door green for a pair a user could tell apart at a glance:
;; a reference `<select>` showing "Two" beside a candidate showing "One", the
;; same three `<option>`s under each, `{:status :green :checkpoints 1}`. PR
;; #8007's merged-PR audit found it and it is the reason this walk reads
;; properties as well as attributes — a port can select the wrong option with
;; every byte of markup and every intent agreeing.

(def ^:private live-properties
  "The live control state each element kind carries in a PROPERTY, by tag.

  One entry per slot the content attribute does not track:

  - `input` — `value` and `checked` go DIRTY the moment a user touches
    the field, and the attribute stays at whatever the element mounted
    with; `indeterminate` is never an attribute at all, and it is the
    third thing a checkbox can look like on screen.
  - `textarea` — `value`; the markup carries only the text child it was
    born with.
  - `select` — `value`, which is the selected option's, and which is
    written by moving `option.selected` rather than any attribute.
  - `option` — `selected`, because a MULTIPLE select's `value` is only
    its FIRST selected option, and two different selections can share
    one.

  Nothing else is here, and the two exclusions are settled rather than
  pending. A property the content attribute REFLECTS — `<progress value>`,
  `<details open>` — is already compared as an attribute, and reading it
  again would only report it twice. And media playback state
  (`currentTime`, `paused`) moves on the wall clock, so comparing it would
  make a verdict depend on when it was taken.

  Everything the door already declined stays declined: focus, caret, IME,
  layout and paint are not here and are not properties of this kind — see
  [[shadow!]] §What it does not claim."
  {"input"    ["value" "checked" "indeterminate"]
   "textarea" ["value"]
   "select"   ["value"]
   "option"   ["selected"]})

(defn- props-of
  "One element's live control state as `{property value}` — empty for
  everything that is not a control.

  A string value is de-addressed exactly as an attribute's is: a field
  holds whatever the application put in it, a frame keyword included."
  [el frame-kw]
  (persistent!
    (reduce (fn [m p]
              (let [v (unchecked-get el p)]
                (assoc! m p (if (string? v) (de-address-text frame-kw v) v))))
            (transient {})
            (get live-properties (node-tag el)))))

(defn- element-outline
  "How a red NAMES an element: its opening tag with attribute names
  sorted, which is `canonical-dom`'s own fairness rule applied to one
  node."
  [el frame-kw]
  (str "<" (node-tag el)
       (str/join (map (fn [[k v]] (str " " k "=\"" v "\""))
                      (sort-by first (attrs-of el frame-kw))))
       ">"))

(defn- comparable-children
  "A node's children as `[kind value]` pairs, canonicalised the way
  `canonical-dom` canonicalises them: comments dropped, adjacent text
  merged into one run, and an empty run dropped after the merge. Without
  the last two a page that splits one string across two text nodes — which
  is React's ordinary conduct for `[:span a b]` — would differ from a page
  that does not, and `canonical-dom` would call the same pair equal."
  [n]
  (into []
        (remove (fn [[kind v]] (and (= :text kind) (= "" v))))
        (reduce (fn [out c]
                  (case (.-nodeType c)
                    8 out
                    3 (let [i (dec (count out))]
                        (if (and (nat-int? i) (= :text (nth (nth out i) 0)))
                          (update-in out [i 1] str (.-nodeValue c))
                          (conj out [:text (.-nodeValue c)])))
                    1 (conj out [:element c])
                    (conj out [:other c])))
                []
                (array-seq (.-childNodes n)))))

(defn- child-segment
  [[kind v] i]
  (case kind
    :text    (str "#text[" i "]")
    :element (str (node-tag v) "[" i "]")
    (str "#" (.-nodeType v) "[" i "]")))

(defn- child-outline
  [[kind v] frame-kw]
  (case kind
    :text    (pr-str (de-address-text frame-kw v))
    :element (element-outline v frame-kw)
    (str "#" (.-nodeType v))))

(defn- at [path] (if (seq path) (str/join " > " path) "(the mounted root)"))

(declare element-difference)

(defn- attribute-difference
  [ref-el can-el ref-frame can-frame path]
  (let [a (attrs-of ref-el ref-frame)
        b (attrs-of can-el can-frame)]
    (some (fn [k]
            (when (not= (get a k absent) (get b k absent))
              {:kind      :dom
               :reason    :attribute
               :attribute k
               :at        (at path)
               :path      path
               :reference (get a k absent)
               :candidate (get b k absent)}))
          (sort (distinct (concat (keys a) (keys b)))))))

(defn- property-difference
  "The first live-control property at which two elements of the same tag
  disagree, as data — or nil.

  Taken AFTER the attributes and BEFORE the children, and both halves of
  that placement earn their keep. A difference visible in the markup is
  the one a reader can go and look at, so where an element differs both
  ways the attribute is what the red names. And a `<select>` whose value
  moved is named at the SELECT rather than at whichever `<option>`
  changed selectedness underneath it, which is the sentence a programmer
  can act on."
  [ref-el can-el ref-frame can-frame path]
  (let [a (props-of ref-el ref-frame)
        b (props-of can-el can-frame)]
    (some (fn [k]
            (when (not= (get a k absent) (get b k absent))
              {:kind      :dom
               :reason    :property
               :property  k
               :at        (at path)
               :path      path
               :reference (get a k absent)
               :candidate (get b k absent)}))
          (sort (distinct (concat (keys a) (keys b)))))))

(defn- children-difference
  [ref-n can-n ref-frame can-frame path]
  (let [a (comparable-children ref-n)
        b (comparable-children can-n)]
    (loop [i 0]
      (when (< i (max (count a) (count b)))
        (let [x    (get a i)
              y    (get b i)
              seg  (child-segment (or x y) i)
              path (conj path seg)
              diff (cond
                     (nil? x) {:kind :dom :reason :only-in-candidate
                               :at (at path) :path path
                               :reference absent
                               :candidate (child-outline y can-frame)}

                     (nil? y) {:kind :dom :reason :only-in-reference
                               :at (at path) :path path
                               :reference (child-outline x ref-frame)
                               :candidate absent}

                     (not= (nth x 0) (nth y 0))
                     {:kind :dom :reason :node-kind
                      :at (at path) :path path
                      :reference (child-outline x ref-frame)
                      :candidate (child-outline y can-frame)}

                     (= :text (nth x 0))
                     (let [rs (de-address-text ref-frame (nth x 1))
                           cs (de-address-text can-frame (nth y 1))]
                       (when (not= rs cs)
                         {:kind :dom :reason :text
                          :at (at path) :path path
                          :reference rs :candidate cs}))

                     (= :element (nth x 0))
                     (element-difference (nth x 1) (nth y 1) ref-frame can-frame path)

                     :else nil)]
          (or diff (recur (inc i))))))))

(defn- element-difference
  [ref-el can-el ref-frame can-frame path]
  (let [rt (node-tag ref-el)
        ct (node-tag can-el)]
    (if (not= rt ct)
      {:kind :dom :reason :tag :at (at path) :path path
       :reference rt :candidate ct}
      (or (attribute-difference ref-el can-el ref-frame can-frame path)
          (property-difference ref-el can-el ref-frame can-frame path)
          (children-difference ref-el can-el ref-frame can-frame path)))))

(defn- dom-difference
  "The first node at which the two mounted pages disagree, as data — or
  nil when they agree."
  [ref-m can-m]
  (children-difference (:container ref-m) (:container can-m)
                       (:frame ref-m) (:frame can-m) []))

;; ---------------------------------------------------------------------------
;; The intent difference
;; ---------------------------------------------------------------------------

(defn- intent-difference
  "The first position at which the two intent streams disagree, as data —
  or nil. Compared before the DOM at every checkpoint, because an intent
  is a CAUSE and the DOM beside it is that cause's effect: the two
  isolated frames then diverge independently, and reporting the effect
  first would send a reader to the wrong end of it."
  [ref-intents can-intents]
  (loop [i 0]
    (when (< i (max (count ref-intents) (count can-intents)))
      (let [x (get ref-intents i absent)
            y (get can-intents i absent)]
        (or (when (not= x y)
              {:kind      :intent
               :reason    (cond (= x absent) :only-in-candidate
                                (= y absent) :only-in-reference
                                :else        :differs)
               :index     i
               :reference x
               :candidate y})
            (recur (inc i)))))))

;; ---------------------------------------------------------------------------
;; The script
;; ---------------------------------------------------------------------------

(defn- check-step!
  "Refuse a step outside the closed grammar, BEFORE anything is mounted —
  so a typo costs a refusal rather than a green."
  [step]
  (when-not (and (map? step) (= 1 (count step)) (contains? step-verbs (ffirst step)))
    (bad-option! (str "a script step is a one-key map — " (pr-str (vec (sort-by str step-verbs)))
                      " and nothing else. It was " (pr-str step)
                      ". A step nothing recognises would drive neither side, and the "
                      "checkpoint after it would go green for a step that never happened.")
                 :remove-the-unknown-option
                 {:step step :unknown (vec (sort-by str (remove step-verbs (keys (if (map? step) step {})))))}))
  (let [[verb arg] (first step)]
    (case verb
      :click (when-not (string? arg)
               (bad-option! (str ":click takes ONE selector string; it was given "
                                 (pr-str arg) ".")
                            :pass-a-map-of-options
                            {:step step}))
      :type  (when-not (and (vector? arg) (= 2 (count arg))
                            (string? (nth arg 0)) (string? (nth arg 1)))
               (bad-option! (str ":type takes [selector text]; it was given "
                                 (pr-str arg) ".")
                            :pass-a-map-of-options
                            {:step step}))))
  step)

(defn- value-setter
  "The PROTOTYPE's `value` setter for a text field. React patches the
  instance setter to keep its own change tracker, so a plain `set!`
  updates the tracker too and the `input` event that follows reaches no
  handler — the field would change on screen and mean nothing."
  [el]
  (let [proto (if (= "TEXTAREA" (.-tagName el))
                js/HTMLTextAreaElement.prototype
                js/HTMLInputElement.prototype)]
    (.-set (js/Object.getOwnPropertyDescriptor proto "value"))))

(defn- run-step!
  "Apply one step to one mount and settle it. Answers true when the
  step's selector matched, false when it matched nothing — which is the
  fact a checkpoint turns into a red rather than into silence."
  [handle step]
  (let [[verb arg] (first step)
        selector   (if (= :click verb) arg (nth arg 0))
        target     (.querySelector (:container handle) selector)]
    (if (nil? target)
      false
      (do (case verb
            :click (.click target)
            :type  (do (.call (value-setter target) target (nth arg 1))
                       (.dispatchEvent target (js/InputEvent. "input" #js {:bubbles true}))))
          (settle! handle)
          true))))

(defn- script-difference
  "A step that could not run as written, as data — or nil. A selector
  that matches on one side and not the other IS the difference and says
  so; one that matches on neither is the SCRIPT being wrong, and it is
  still a red because a checkpoint reached by a step that did not happen
  proves nothing, and green here means proved."
  [step ran-reference? ran-candidate?]
  (let [selector (let [[verb arg] (first step)]
                   (if (= :click verb) arg (nth arg 0)))]
    (cond
      (and ran-reference? ran-candidate?) nil

      (or ran-reference? ran-candidate?)
      {:kind     :script
       :reason   :selector-matched-one-side
       :selector selector
       :matched  (if ran-reference? :reference :candidate)}

      :else
      {:kind     :script
       :reason   :selector-matched-nothing
       :selector selector
       :matched  :neither})))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- retire!
  "Complete a mount's lifecycle with NO residue reading — `assert-clean!`'s
  bookkeeping half, for a door that makes a different claim.

  It does not reset the runtime, and that asymmetry is the point:
  `assert-clean!` resets AFTER a reading, and a reset performed by
  something that never read would make somebody else's pending reading
  vacuous. The unread counter still comes down, so a shadow run cannot
  suppress a later `assert-clean!`'s reset for the rest of the process."
  [handle]
  (when (= :unmounted @(get handle state-key))
    (vreset! (get handle state-key) :read)
    (swap! !open dec)
    (swap! !facade-frames disj (:frame handle))
    (rf/destroy-frame! (:frame handle)))
  nil)

(defn- take-down!
  [ref-m can-m log]
  (when ref-m (retire! (unmount! ref-m)))
  (when can-m (retire! (unmount! can-m)))
  (close-log! log)
  nil)

(defn- checkpoint
  "One checkpoint's verdict: the intent difference if there is one, else
  the DOM difference, else nil."
  [ref-m can-m entries]
  (or (intent-difference (mapv #(de-address (:frame ref-m) %)
                               (for-frame entries (:frame ref-m)))
                         (mapv #(de-address (:frame can-m) %)
                               (for-frame entries (:frame can-m))))
      (dom-difference ref-m can-m)))

(defn- red
  [n step difference]
  (cond-> {:status      :red
           :checkpoints (inc n)
           :checkpoint  n
           :difference  difference}
    (some? step) (assoc :step step)))

(defn shadow!
  "**Shadow comparison — the dual-render DOM / intent diff.** Mounts a
  reference implementation and a candidate against isolated copies of the
  same seeded frame, drives both with one script, and compares canonical
  DOM and the intent stream at every checkpoint.

      (hm/shadow!
       {:reference      [:> (r/reactify-component old/article-row) {:article-id 7}]
        :candidate      [new/article-row {:article-id 7}]
        :initial-events [[:demo/install-fixture]]
        :script         [{:click \"button.edit\"}
                         {:type  [\"input.title\" \"Better title\"]}
                         {:click \"button.save\"}]})
      ;; => {:status :green :checkpoints 4}

  Green means the two implementations were indistinguishable **for the
  flows in the script**. It does not prove untested paths, so script the
  screen's real behaviour rather than one happy click.

  ## Ten-minute setup

  1. Port one screen by hand. A screen is the unit that gives this door
     something to prove; a single component usually is not.
  2. Write a namespace beside the port with one `deftest`.
  3. `:reference` is the original and `:candidate` is the port, each an
     ordinary hiccup form — the same `[view props]` a call site writes.
  4. `:initial-events` is the seed, in core's own frame vocabulary:
     `[[:rf/set-db {…}]]` for a literal app-db, or the application's own
     setup events. BOTH mounts get it, each into a frame of its own.
  5. `:script` is the flow, as `{:click selector}` and
     `{:type [selector text]}` steps in order.
  6. Assert the verdict — `(is (= :green (:status (hm/shadow! …))))` — and
     then SABOTAGE it: change one prop in the candidate and confirm the
     run goes red at the checkpoint you expect. A comparator that has
     never been seen to fail is not yet evidence of anything.

  ## What is mounted, and how an original that is not Hicasso gets here

  Both sides are handed to the Hicasso runtime by [[mount!]], so a form
  the substrate refuses is refused with the substrate's own id. A Reagent,
  UIx or plain-React original therefore arrives the way every foreign
  component arrives — through the runtime's own crossing, `[:> C props]`
  or a declared `h/defhost` — which is the same door the migration page's
  own translation table already sends it through. Nothing here `:require`s
  a second view library, and the door works for all of them for that
  reason.

  A crossing is also how a foreign original reaches the frame at all. A
  declared callback contract lowers an intent vector into a real function
  closed over the mount's frame, so `props.onSave()` inside the original
  dispatches into the original's OWN frame. There is deliberately no
  option here that hands a component a frame id: frames are isolated
  contexts, and a helper that passed one down a view would defeat the
  isolation this door is built on.

  ## Two frames, and one deliberate blind slot

  Each side mounts under a frame [[mount!]] minted for it, so a write on
  one side cannot reach the other and a divergence propagates
  independently — which is what makes the FIRST red the original cause
  rather than an echo of it.

  The price is that the two frames have two different keywords, and a
  rendered intent can carry one as ordinary data: `h/route-link` captures
  the rendering frame and bakes it into the anchor's navigate vector. So
  every comparison here is taken **modulo each mount's own address** —
  each side's own frame keyword normalises to [[this-frame]] first, in
  the intent stream and in DOM text and attribute values alike. That is a
  narrower claim than raw equality and it is stated rather than assumed;
  `docs/design/hicasso/product/capsule-replay-verdict.md` is where the
  general result was found, and it names this door while stating it. Only
  a mount's OWN keyword normalises, so a genuine cross-frame address still
  reddens.

  ## What a red says

      {:status :red :checkpoints 3 :checkpoint 2
       :step   {:click \"button.save\"}
       :difference {:kind :dom :reason :attribute :attribute \"class\"
                    :at \"div[0] > ul[1] > li[0]\"
                    :reference \"row done\" :candidate \"row\"}}

  `:checkpoint` is which one — 0 is the mount, and each script step adds
  one. `:checkpoints` is how many were taken, which is always
  `(inc :checkpoint)` on a red and `(inc (count :script))` on a green: a
  three-step script that stays green takes four, and one that reddens at
  its FIRST step reports `:checkpoint 1` and `:checkpoints 2`.
  `:difference` names the exact node or intent:

  - `:kind :intent` — `:index` into the checkpoint's stream, with the
    `:reference` and `:candidate` vectors. Reported BEFORE any DOM
    difference at the same checkpoint, because it is the cause.
  - `:kind :dom` — `:at`, a readable path from the mounted root, plus
    `:reason` (`:tag`, `:attribute`, `:property`, `:text`, `:node-kind`,
    `:only-in-reference`, `:only-in-candidate`) and both sides' values.
    `:property` names a **live control** slot and carries `:property`
    beside the values — a `<select>` at a different option, a dirty
    `value` or `checked`, an `indeterminate` checkbox. Those are the
    user-visible state a form control keeps OUT of its markup, so this
    is the one `:reason` a serialiser could never have reached; see
    [[live-properties]] for the closed roster and for what is
    deliberately not in it.
  - `:kind :script` — the step could not run as written. A selector that
    matched one side only IS a difference; one that matched neither is a
    broken script, and it is still red, because a checkpoint reached by a
    step that did not happen has proved nothing.

  ## Scriptless: omit `:script` and both mounts stay live

      (let [s (hm/shadow! {:reference … :candidate …})]
        …                       ;; drive the page by hand
        ((:checkpoint! s))      ;; → {:status :green :checkpoints 1}
        ((:stop! s)))

  Answers `{:status :live :reference handle :candidate handle
  :checkpoint! f :stop! f}`. Each `checkpoint!` call takes a reading and
  numbers it; `stop!` takes both mounts down.

  **A checkpoint per committed render is NOT built, and neither is
  mirroring your clicks from one mount to the other.** Both need a seam
  the runtime does not publish — there is no commit callback, and an
  event mirrored by node path would go wrong exactly when the two trees
  differ, which is when it matters. A reading you ask for is honest; one
  taken automatically at a moment nothing defines would be a green
  arriving mid-flight.

  ## What it does not claim

  Canonical DOM, the live control state named in [[live-properties]],
  and intents. Not focus, caret, IME, layout or paint — those are L4 and
  the browser levels own them. Not residue either: no reading is taken,
  so no reset is performed, and `assert-clean!` remains the door for that
  claim.

  The control state is inside the claim rather than beside it because
  markup cannot carry it and a user can see it: an `<option>`'s
  selectedness, a dirty `value` or `checked`, an `indeterminate`
  checkbox. Comparing attributes alone made this door green for a
  candidate visibly showing the wrong option (PR #8007's merged-PR
  audit), which is the one thing it exists not to do.

  Synchronous. A step's effects are settled through `flushSync` before
  the checkpoint after it, so anything landing in a later turn — a
  `:dispatch-later`, a resolved promise, a router drain — is outside the
  checkpoint that would have seen it.

  The mounts come down on every path out, including a throw."
  [opts]
  (when-not (map? opts)
    (bad-option! (str "shadow!'s options are a map; they were " (pr-str opts) ".")
                 :pass-a-map-of-options
                 {:value opts}))
  (when-let [unknown (seq (remove shadow-options (keys opts)))]
    (bad-option! (str "shadow! accepts "
                      (pr-str (vec (sort-by str shadow-options)))
                      " and nothing else; it was given "
                      (pr-str (vec (sort-by str unknown)))
                      ". An option that is quietly ignored is a setting its "
                      "author believes is in force — `:seed` was this surface's "
                      "own earlier spelling for `:initial-events`, and accepting "
                      "it in silence would compare two UNSEEDED views.")
                 :remove-the-unknown-option
                 {:unknown (vec (sort-by str unknown))}))
  (let [{:keys [reference candidate initial-events script]} opts
        scripted? (contains? opts :script)
        _         (when scripted?
                    (when-not (sequential? script)
                      (bad-option! (str ":script is a sequence of steps; it was "
                                        (pr-str script) ".")
                                   :pass-a-map-of-options
                                   {:value script}))
                    (run! check-step! script))
        log       (open-log!)
        mount-opts (cond-> {} (seq initial-events)
                     (assoc :initial-events (vec initial-events)))
        !ref      (volatile! nil)
        !can      (volatile! nil)]
    (try
      (vreset! !ref (mount! reference mount-opts))
      (vreset! !can (mount! candidate mount-opts))
      (let [ref-m @!ref
            can-m @!can]
        (if scripted?
          (let [verdict
                (if-some [d (checkpoint ref-m can-m ((:drain log)))]
                  (red 0 nil d)
                  (loop [i 0]
                    (if (>= i (count script))
                      {:status :green :checkpoints (inc (count script))}
                      (let [step  (nth script i)
                            ran-r (run-step! ref-m step)
                            ran-c (run-step! can-m step)]
                        (if-some [d (or (script-difference step ran-r ran-c)
                                        (checkpoint ref-m can-m ((:drain log))))]
                          (red (inc i) step d)
                          (recur (inc i)))))))]
            (take-down! ref-m can-m log)
            verdict)
          ;; Scriptless: both mounts stay live and the caller decides when a
          ;; reading is a checkpoint. Nothing is drained here, so the first
          ;; `checkpoint!` is checkpoint 0 — the mount — and it sees the
          ;; intents the seeding and the first render dispatched.
          (let [!n   (atom 0)
                !up? (atom true)]
            {:status      :live
             :reference   ref-m
             :candidate   can-m
             :checkpoint! (fn []
                            (settle! ref-m)
                            (settle! can-m)
                            (let [n (first (swap-vals! !n inc))
                                  d (checkpoint ref-m can-m ((:drain log)))]
                              (if d
                                (red n nil d)
                                {:status :green :checkpoints (inc n)})))
             :stop!       (fn []
                            (when @!up?
                              (reset! !up? false)
                              (take-down! ref-m can-m log))
                            nil)})))
      (catch :default e
        (take-down! @!ref @!can log)
        (throw e)))))
