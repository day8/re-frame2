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
  [[settle!]] before you assert.

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

  ## Scope

  Dev/test only, and a browser tier: every door needs a real document.
  Like `re-frame.hicasso.test` it lives in the kit's own source root
  (`hicasso/test_kit/src`), outside the artefact's published `:paths`, so
  nothing in a production bundle can reach it."
  (:require [clojure.string :as str]
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
  instant and the advance would not terminate."
  [repeat? f delay args]
  (let [d (if (and (number? delay) (pos? delay)) delay 0)]
    (:seq (swap! !clock
                 (fn [c]
                   (let [id (inc (:seq c))]
                     (-> c
                         (assoc :seq id)
                         (assoc-in [:pending id]
                                   {:id     id
                                    :f      f
                                    :args   args
                                    :due    (+ (:now c) d)
                                    :period (when repeat? (max 1 d))}))))))))

(defn- disarm!
  "Drop the virtual timer `id`, and answer whether there was one. False
  means the id is not this clock's — a timer armed before the install —
  and the caller hands it to the platform's own clearer, which is what
  keeps a `clearTimeout` across the boundary honest."
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
        (doseq [e (sort-by :id (vals pending))]
          (if-some [p (:period e)]
            (.apply (:set-interval real) g (to-array (list* (:f e) p (:args e))))
            (.apply (:set-timeout real) g
                    (to-array (list* (:f e) (max 0 (- (:due e) now)) (:args e)))))))))
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
                   (sort-by (juxt :due :id))
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
                       [[unmount!]].

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
  it: no frame registered, no container appended, no counter moved. So
  `(try (hm/mount! form) (catch :default e (ex-data e)))` is the whole of
  a refusal witness, and there is no handle to be given because there is
  nothing left to tear down.

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
   (when clock (install-clock!))
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
           (when clock (release-clock!))
           (throw refusal))
       (handle-for root ordinal baseline (boolean clock))))))

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
  door: a `user-event` sequence, a raw `.click`, a resolved promise, a
  timer you fired yourself. After it returns the DOM is the one a user
  would be looking at.

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

  Requires `{:clock true}` on the [[mount!]] that made `handle`, and
  throws without it. That is not decoration: an advance with no clock
  under it would move nothing and assert nothing, and the row would go
  green for the reason it was written to rule out.

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
                             ": mount! was not given {:clock true}.")
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
