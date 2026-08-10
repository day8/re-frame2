(ns re-frame.hicasso.test.mounted
  "`hm` — HICASSO'S MOUNTED TEST FACADE, L3 (rf2-hic-027).

  The rung above `re-frame.hicasso.test`. That namespace runs ONE body and
  reads the hiccup it returned; this one puts a real React root on a real
  page and lets React do everything L2 refuses to pretend about —
  lifecycle, hooks, context, refs, error boundaries, foreign hosts, the
  DOM.

      (:require [re-frame.hicasso.test :as ht]        ;; L1–L2
                [re-frame.hicasso.test.mounted :as hm]) ;; L3

  ## Seven doors and one handle

      (hm/mount! form opts)             → handle
      (hm/hydrate! form opts)           → promise of handle
      (hm/render! handle form)          → handle
      (hm/dispatch-and-settle! h event) → handle
      (hm/settle! handle)               → handle
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
  than accessors over a wrapper.

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

  ## Refusals: this namespace mints none

  Every other file in the kit refuses loudly, and this one deliberately
  does not, for two reasons that agree.

  A malformed form, a bad handle or a hiccup shape the substrate rejects
  is already refused BY THE RUNTIME, with the runtime's own id, reason and
  recovery — which is the whole discipline `re-frame.hicasso.test`
  established (§Children, and the runtime-parity suite). A guard here
  would paraphrase a refusal that already exists.

  And a residue finding is not a refusal at all: it is a TEST FAILURE, so
  [[assert-clean!]] reports it through `cljs.test/do-report` rather than
  throwing. That distinction is the useful one — the kit REFUSES misuse of
  the instrument and REPORTS a fact about the code under test — and it is
  also what keeps this file free of new `:rf.error/*` ids.
  (`:rf.error/hicasso-test-residue-after-quiescence` is RESERVED in the
  complaint register for a raising variant; promoting a reservation is a
  deliberate act across `spec/009` and the register, and this bead does
  not take it.)

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
            [re-frame.hicasso.impl.roots :as roots]))

;; ---------------------------------------------------------------------------
;; Bookkeeping
;; ---------------------------------------------------------------------------

(defonce ^:private !mount-seq (atom 0))

(defonce ^:private !standing
  "How many mounts this facade has put up and not yet taken down.

  Read by [[assert-clean!]] for ONE decision: whether its reset is safe.
  `impl.collector/reset-runtime!` empties every table by fiat and the
  tables are page-wide, so a reset performed while a sibling root is still
  standing would both break that root and — worse — make ITS eventual
  cleanliness reading vacuous. A census taken after a reset answers zero
  whether the teardown released anything or not, which is the shape of
  gate that cannot go red (`impl.mount/unmount!`, rf2-2rtt6.48).

  So the reset happens only when the last standing mount has come down.
  A test that forgets to unmount leaves the counter above zero, which is
  correct rather than unfortunate: there IS a live root, and the leak
  shows up in the residue delta of whichever mount asks."
  (atom 0))

(def ^:private mounted-key ::mounted?)
(def ^:private baseline-key ::baseline)
(def ^:private ordinal-key ::ordinal)

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
  is somebody else's business and never this mount's complaint."
  [baseline now]
  (let [numbers (reduce (fn [m k]
                          (let [d (- (get now k 0) (get baseline k 0))]
                            (cond-> m (pos? d) (assoc k d))))
                        {}
                        counted)
        frames  (into #{} (remove (:frames baseline #{})) (:frames now))]
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
  [{:keys [frame baseline now leaked ordinal]}]
  (str "the mount left residue behind after quiescence.\n"
       "The baseline was taken inside mount! #" ordinal ", after this mount's own\n"
       "frame " frame " existed and before its root did; the reading below was\n"
       "taken after unmount! and after the runtime's own reaper horizon.\n\n"
       (str/join "\n" (map (fn [[k v]] (leak-line baseline now k v)) leaked))
       "\n\nSomething outlived the root. A retained subscription, a foreign host that\n"
       "mounts its own root, or a frame the app made and did not destroy."))

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
    [frame-kw ordinal]))

(defn- handle-for
  [root ordinal baseline]
  (swap! !standing inc)
  (assoc root
         baseline-key baseline
         ordinal-key  ordinal
         mounted-key  (volatile! true)))

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

  ## What it records before it renders

  The residue BASELINE, taken after this mount's frame exists and before
  its root does. [[assert-clean!]] compares against it rather than against
  zero, which is what makes the reading a statement about THIS mount: a
  page that already held a live root when the mount began is not this
  mount's residue, and a test that opened one is not made to answer for
  it.

  The frame is this facade's — minted, made and destroyed here. See the
  namespace docstring on why there is no `:frame` option."
  ([form] (mount! form {}))
  ([form {:keys [initial-events container]}]
   (let [[frame-kw ordinal] (mint-frame! initial-events)
         baseline           (census)
         node               (or container (mount/fresh-container!))]
     (handle-for (mount/root! node frame-kw form) ordinal baseline))))

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
  this door exists to prevent."
  ([form] (hydrate! form {}))
  ([form opts] (hydrate! form opts 3000))
  ([form {:keys [initial-events container html]} budget-ms]
   (let [[frame-kw ordinal] (mint-frame! initial-events)
         baseline (census)
         node     (or container (mount/fresh-container!))
         _        (when (some? html) (set! (.-innerHTML node) html))
         handle   (handle-for (mount/hydrate-root! node frame-kw form)
                              ordinal baseline)
         window   (:adoption handle)
         deadline (+ (js/Date.now) budget-ms)]
     (js/Promise.
       (fn [resolve reject]
         (letfn [(tick []
                   (cond
                     (not (roots/adopting? window))
                     ;; Past the closer AND past the reap horizon, so the
                     ;; handle a caller receives is one whose tables have
                     ;; settled.
                     (js/setTimeout (fn [] (resolve handle)) 16)

                     (< deadline (js/Date.now))
                     (reject (ex-info (str "hydrate! waited " budget-ms
                                           "ms and this root's adoption window "
                                           "never shut — the tree was not adopted.")
                                      {:frame frame-kw :budget-ms budget-ms}))

                     :else (js/setTimeout tick 4)))]
           (tick)))))))

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
  "Tear the root down, detach its container, and answer the handle.

  It touches NOTHING the runtime holds. Whatever cells, edges and cached
  closures survive this call are exactly the ones React's own cleanup
  failed to release, which is what makes [[assert-clean!]] able to see
  them — a teardown that reset the tables first would answer clean whether
  the unmount released anything or not.

  Idempotent: unmounting twice is not an error, and only the first call
  counts against the standing-mount census."
  [handle]
  (mount/unmount! handle)
  (when @(get handle mounted-key)
    (vreset! (get handle mounted-key) false)
    (swap! !standing dec))
  handle)

;; ---------------------------------------------------------------------------
;; Cleanliness
;; ---------------------------------------------------------------------------

(defn residue
  "**What this mount left behind, as data.** Answers a promise of the
  report; asserts nothing and resets nothing.

      {:clean?   false
       :frame    :re-frame.hicasso.test.mounted/mount-3
       :ordinal  3
       :baseline {:cells 0 :cell-refs 0 … :frames #{…}}
       :now      {:cells 2 :cell-refs 2 … :frames #{…}}
       :leaked   {:cells 2 :cell-refs 2 :boundaries 1 :edges 2}}

  The reading is taken after `impl.inventory/quiesced!` — the runtime's
  OWN settling point, not a macrotask. The entry reaper's horizon sits
  deliberately outside a bare `setTimeout 0`, so a census taken one
  macrotask after an unclaimed render still counts entries the runtime is
  about to drop, and a gate comparing against such a reading fails on
  every arm whose row outlives the horizon.

  `:leaked` is absent when the mount was clean, and only ever names
  INCREASES against the baseline (see [[census]] for what is counted and
  [[mount!]] for when the baseline was taken).

  This is [[assert-clean!]]'s other half, and it exists so the instrument
  can have a sabotage control of its own: a witness that deliberately
  leaks must be able to read the verdict rather than fail on it."
  [handle]
  (let [baseline (get handle baseline-key)
        frame    (:frame handle)]
    (.then (inventory/quiesced!)
           (fn [_]
             (let [now (census)
                   l   (leaked baseline now)]
               (cond-> {:clean?   (nil? l)
                        :frame    frame
                        :ordinal  (get handle ordinal-key)
                        :baseline baseline
                        :now      now}
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
  still standing; see `!standing`. Which makes the ordinary shape the
  right one: assert each mount clean after it comes down, and the last one
  to come down does the clearing."
  [handle]
  (let [frame (:frame handle)]
    (if @(get handle mounted-key)
      (let [report {:clean? false :frame frame :ordinal (get handle ordinal-key)
                    :still-mounted? true}]
        (t/do-report
          {:type     :fail
           :message  (str "assert-clean! was called on a mount that is still "
                          "standing. Call unmount! first: this reading is about "
                          "what survives a teardown, and there has not been one.")
           :expected '(unmounted? handle)
           :actual   report})
        (js/Promise.resolve report))
      (.then (residue handle)
             (fn [report]
               (t/do-report
                 (if (:clean? report)
                   {:type     :pass
                    :message  (str "the mount left no residue: every counter is "
                                   "back at its pre-mount baseline")
                    :expected '(clean? handle)
                    :actual   report}
                   {:type     :fail
                    :message  (leak-report report)
                    :expected '(clean? handle)
                    :actual   (:leaked report)}))
               (when (zero? @!standing)
                 (collector/reset-runtime!))
               (rf/destroy-frame! frame)
               report)))))
