(ns day8.re-frame2-xray.acceptance.test-helpers.criteria
  "THE ACCEPTANCE HARNESS for the rf2-k97c epic's SIX BEHAVIOURAL CRITERIA,
  written against TODAY'S Xray so the root-swap PR inherits it rather than
  writing one under its own time pressure (rf2-k97c.3, slice D).

  This namespace holds the six criterion BODIES. It registers no `deftest`
  of its own and its name carries no `-cljs-test` suffix, so neither lane
  discovers it; the per-substrate test namespaces one directory up each wrap
  these six in `deftest` under their own adapter. One body, N substrates —
  which is the whole point, because the claim the swap makes is a claim about
  INDIFFERENCE to the installed adapter.

  ## WHY IT SITS UNDER `test_helpers/` — DO NOT MOVE IT BACK

  `test_helpers/` is the established home for support shared by DOM suites,
  and the surface classifier arms the browser lane on that DIRECTORY:
  `is_story_xray_dom_test_path` in `.github/scripts/report-changed-surfaces.sh`
  matches `tools/{story,xray}/test/*/test_helpers/*.{cljs,cljc}`. A shared
  body file outside it classifies `cljs_browser=false`, so a PR editing only
  this file would run the Node lane — where the DOM suites requiring it find
  no `document` and self-skip — and skip the one lane that mounts them. The
  mirror suite `implementation/scripts/_changed-surfaces.test.cjs` walks the
  require closure of every `*_dom_cljs_test` namespace and refuses exactly
  that, so a move out of here reds `npm run test:scripts` on arrival.

  ## The six, and which fn answers each

  | # | criterion                                                  | fn |
  |---|------------------------------------------------------------|----|
  | 1 | first display                                              | [[c1-first-display!]] |
  | 2 | updates as the app changes                                 | [[c2-updates-as-the-app-changes!]] |
  | 3 | Xray's own interactions                                    | [[c3-xrays-own-interactions!]] |
  | 4 | tool-local state separate from the app, commands targeting the intended frame | [[c4-tool-local-state-and-frame-targeting!]] |
  | 5 | Xray's activity never masquerading as application evidence | [[c5-no-masquerading-as-application-evidence!]] |
  | 6 | clean teardown and reopen without disturbing the host      | [[c6-clean-teardown-and-reopen!]] |

  CRITERION 4 IS COMPLETE AS OF rf2-3j1v and covers BOTH halves — `tool
  state stays out of the application` AND `a tool command reaches the
  application frame it was aimed at`. The second half was unwritable
  while the subject was the Static ribbon alone, because the Static
  surface issues no app-directed command; the widening below put the
  shipped shell on the bench, and with it the Dynamic tab ribbon's
  `Reset` rewind, which is an app-directed command. The gap note
  rf2-t2ke left here is kept as history inside
  [[c4-tool-local-state-and-frame-targeting!]]'s own docstring.

  ## THE SUBJECT: Xray's SHIPPED SHELL, and how it got here

  [[mount-xray!]] mounts `shell/ShellView` — the whole shipped shell, the
  head `mount.cljs` itself renders: the envelope, the frame-provider, the
  resize handle, the seven shell-root modal mounts and the mode composer,
  which paints Xray's Static surface or its Dynamic 4-layer chrome
  according to the live `:rf.xray/mode` lens.

  IT USED TO MOUNT `static.shell/surface` ALONE, and the paragraph that
  stood here explained why: the Dynamic shell was not yet migrated —
  `shell/shell-view` was still an `rf/reg-view` painted through the
  installed adapter's `:render`, the epic's coupling (1) — so the Static
  ribbon was the only piece of Xray whose mount was adapter-INDEPENDENT.
  THAT IS NO LONGER TRUE. rf2-k97c.3 (PR #9708) made the shell a Fresco
  boundary Xray paints through its own root, and the only surviving
  `rf/reg-view` under `tools/xray/src` is `shell/event-list`, which
  cannot migrate because `panel-registry/reg-l4-tab!` carries
  `:pre [(fn? panel)]`.

  THAT PARAGRAPH PROMISED THE WIDENING WOULD COST ONE LINE — *the vector
  [[mount-xray!]] renders* — AND IT COST TWO, which is worth recording
  because the second is easy to miss. `:rf.xray/mode` defaults to
  `:dynamic` (`registry.cljs`), so mounting the shipped shell and
  changing nothing else swaps the Static surface out from under every
  row below, all of which name Static testids. [[setup!]] therefore pins
  the lens to `:static` before the mount, and with that one extra line
  every row below IS unchanged, exactly as promised.

  ## THE MOUNT VERB: Fresco's own root, which IS the post-swap mechanism

  `rf.fresco.impl.mount/root!` creates and owns a React root and reads
  through Fresco's collector. That is precisely what the epic's coupling
  (1) asks `mount.cljs` to do, so a harness written against it is a harness
  the swap PR satisfies by construction rather than by edit.

  Measured at trunk 1bf7106126, this mechanism paints Xray's Static chrome
  under BOTH a ratom-family adapter and an element-shaped one, with the
  HOST'S adapter installed and untouched. That is the forward-looking
  clause of the epic — *a future non-React browser adapter supplying only
  the container quartet plus the listener API should get Xray with no
  Xray-side work* — reduced to something a row can witness today.

  ## THE FRAME: `:rf/xray`, deliberately, and it is load-bearing for two rows

  [[xray-frame]] is `shell/default-frame-id`, which is where production
  mounts the shell and where Xray's tool-local state lives. Mounting the
  chrome into the APPLICATION's frame instead would put Xray's tab
  selection into the app's `app-db` and its dispatches into the app's trace
  ring — which is exactly what criteria 4 and 5 say must not happen, so a
  harness that did it would be asserting against a world it had itself
  arranged to be wrong.

  ## WHAT EVERY ROW COMPARES AGAINST

  LITERALS. Not a value read back out of the surface under test. A door
  compared against the attrs it has just read agrees with itself under a
  revert — both sides nil, the row green ON THE DEFECT — which is how a
  hollow assertion survives its own sabotage run. Every expectation below
  is spelled out: `\"rf-xray-static-detail-panel-flows\"`, `:machines`,
  `0`, `empty-runtime`.

  ## NODE LANE

  Every fn short-circuits through [[browser?]] and reports the skip. The
  `:node-test` build compiles these namespaces (`cljs-test$` matches
  `-dom-cljs-test` too) but has no DOM to commit into."
  (:require [cljs.test :refer-macros [is]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            ;; LOADED FOR ITS HOOKS, NOT FOR A NAME. `rf/restore-epoch!`
            ;; and `rf/epoch-history` are LATE-BOUND (`:epoch/restore-
            ;; epoch!`, `:on-absent :false`), so without the artefact on
            ;; the page the first answers `false` and the second `[]` —
            ;; and `[]` is the shape a clean history has, which is how a
            ;; missing artefact reads as a passing row. Criterion 4's
            ;; rewind arm is the consumer; it asserts a non-empty history
            ;; and a nil failure flash so an absent artefact reddens here
            ;; rather than hiding.
            [re-frame.epoch]
            [re-frame.flows :as rf.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.trace :as rf.trace]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.static.persistence :as static-persistence]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ===========================================================================
;; Frames, queries and the literal expectations every row compares against
;; ===========================================================================

(def xray-frame
  "Where the chrome mounts — `shell/default-frame-id`, i.e. `:rf/xray`.
  Taken from the source rather than spelled `:rf/xray` here so a rename of
  the singleton cannot leave this harness quietly pointing at a frame that
  no longer exists."
  shell/default-frame-id)

(def app-frame
  "THE INSPECTED APPLICATION's frame. Criterion 4 says Xray's state stays
  out of it and criterion 5 says Xray's activity stays out of its
  evidence, so this frame is an instrument in both rows rather than
  scenery."
  ::app)

(def second-app-frame
  "A SECOND INSPECTED APPLICATION, held at a value [[app-frame]] never
  takes.

  Criterion 4 is a claim about two applications, not one: a tool whose
  writes went EVERYWHERE and a tool whose writes went NOWHERE are
  indistinguishable while only one application is on the bench. Two
  applications seeded to different values tell them apart, and make
  `unchanged` name a specific number rather than the value every frame
  happens to answer with anyway.

  DISTINCT FROM [[other-frame]], which is a TOOL lever parked on a Static
  tab. This one is an application: nothing in Xray's chrome names it, and
  nothing in Xray's chrome may write to it."
  ::second-app)

(def second-app-seed
  "What [[second-app-frame]] is seeded with. NOT [[app-frame]]'s `0`,
  deliberately — a write that reached the wrong application shows up as a
  value rather than hiding inside a default the two frames share."
  7)

(def other-frame
  "A SECOND live frame, the DEAF LEVER for criterion 4's routing half. A
  command that had lost its frame capture and fallen through to an ambient
  dispatcher would write here, or nowhere; a frame held at a distinct value
  is the only thing that separates those two from a command that routed
  where it was told."
  ::other)

(def selected-tab-q
  "The read the Static tab bar and the Static detail panel BOTH make, and
  the one a tab click invalidates. Named once because it is three things:
  criterion 3's state assertion, criterion 4's frame probe, and — paired
  with a frame — the collector's own cell address in criterion 6."
  [:rf.xray.static/selected-tab])

(def default-tab
  "The tab the Static surface lands on with nothing selected. Read from
  the source, because criterion 1 asserts the L4 panel's testid contains
  it and criterion 3 asserts the click moved AWAY from it."
  static-shell/default-tab)

(def clicked-tab
  "The tab criterion 3 presses. `:flows` is a registered Static tab that is
  NOT [[default-tab]], so the L4 panel moving to it is the click's doing
  and not the surface's resting state."
  :flows)

(def deaf-frame-tab
  "The DISTINCT value [[other-frame]] is held at. Neither the default nor
  [[clicked-tab]], so `unchanged` names a specific tab rather than the
  value every frame answers with anyway."
  :routes)

(def static-lens
  "The lens [[setup!]] pins the shipped shell to, so the mode composer
  paints the Static surface every row below names by testid. Read the
  widening note in this namespace's docstring for why this is a pin
  rather than a default."
  :static)

(def dynamic-lens
  "The lens criterion 4's REWIND arm switches to. The `Reset` button —
  the one app-directed command Xray ships — lives on the DYNAMIC tab
  ribbon and nowhere else, so the arm has to be looking at that surface
  to press it."
  :dynamic)

(def reset-button-testid
  "The literal testid of the Dynamic tab ribbon's `Reset` rewind button
  (`shell/tab-bar-tree`). Spelled out rather than derived, for the
  reason every expectation here is: a row that derives its selector the
  way the subject builds it agrees with the subject under any defect
  they share."
  "rf-xray-tab-bar-reset")

(def probe-flow-id
  "The flow criterion 2 registers INTO THE APPLICATION at run time — the
  'app changes' half. Its row testid is derived below."
  :rf2-acceptance/probe-flow)

(def probe-flow-row-testid
  "The literal testid the Static Flows panel gives [[probe-flow-id]]'s row.
  `static/flows/panel.cljs` builds it as the prefix plus `(subs (pr-str
  flow-id) 1)` — the printed keyword with its leading colon shaved — so
  this is spelled out rather than recomputed, because a row that derives
  its expectation the same way the subject does agrees with the subject
  under any defect they share."
  "rf-xray-static-flows-row-rf2-acceptance/probe-flow")

(def empty-runtime
  "What the collector census reads when it holds nothing. Spelled out
  rather than captured, so criterion 6's baseline is a claim about ZERO
  rather than about whatever happened to be standing — a baseline taken
  from a dirty runtime lets a leak hide inside it."
  {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0})

;; ---- the application's own handlers ---------------------------------------
;;
;; Registered at ns LOAD, which is ahead of every test ns's `use-fixtures`
;; form being evaluated — and that form is when the reset fixture captures
;; its registrar baseline. A registration written after it is erased before
;; the first row runs.

(rf/reg-sub ::app-count (fn [db _] (:count db 0)))

(rf/reg-event ::app-seed (fn [_ [_ v]] {:db {:count v}}))

(rf/reg-event ::app-bump
              (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))

;; ===========================================================================
;; Harness
;; ===========================================================================

(defn browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (rf.fresco.impl.mount/browser?))

(defonce ^:private !last-uncaught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(defn- uncaught-note
  "A suffix naming the last uncaught error, for a row whose subject is
  missing.

  REACT SWALLOWS A RENDER-PHASE THROW. A re-frame refusal raised inside a
  render does not reach a `try/catch` around the mount — React catches it,
  reports 'an error occurred in <view>' to the console and re-raises it as
  an UNCAUGHT window error — so `ex-message` and `ex-data`, the whole of
  what re-frame refuses WITH, appear nowhere a row can read. Without this,
  every assertion reddens on a nil node saying only that the chrome is
  absent."
  []
  (if-some [e (when error-capture-armed? @!last-uncaught)]
    (str " — an uncaught error was raised during render: "
         (:rf.error/id (ex-data e) (ex-message e)))
    ""))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROLS: an absence asserted immediately after the world moves
  is a race; an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- poll-until
  "Resolve as soon as `pred` answers truthy, or after `budget-ms`. The
  resolution value is `pred`'s last answer, so a caller asserts on the
  VALUE rather than on the fact that polling ended.

  A CENSUS READ IN THE SAME TURN AS A MOUNT IS NOT YET EVIDENCE: a
  boundary reaches the collector's entry table asynchronously, so a
  witness that ticks in the same turn as the mount comes back green having
  observed nothing."
  ([pred] (poll-until pred 4000))
  ([pred budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (let [v (pred)]
                     (cond
                       v                          (resolve v)
                       (> (js/Date.now) deadline) (resolve v)
                       :else (js/requestAnimationFrame (fn [_] (tick))))))]
           (tick)))))))

(defn setup!
  "Register Xray's handlers — which is what registers every Static L4 tab
  entry, so the tab bar and the L4 mount both read the real registry — and
  make the three frames, seeding the application with a known `:count`.

  The application frame is made BEFORE anything mounts, so criterion 5's
  ring assertions are about a frame that was recording all along.

  BOTH applications are seeded, to DIFFERENT values — see
  [[second-app-frame]] for why one application cannot carry criterion 4's
  claim on its own.

  ## THE LENS PIN, AND WHY IT IS NOT TIDINESS

  `:rf.xray/mode` defaults to `:dynamic`, so [[mount-xray!]] mounting
  the shipped shell would paint the Dynamic 4-layer chrome and every
  Static testid below would vanish. Pinning [[static-lens]] BEFORE the
  mount is what keeps the promise the namespace docstring records — the
  widening costs this line and the vector, and every row is otherwise
  unchanged. It is dispatched rather than written into `app-db` because
  `:rf.xray/set-mode` is the shipped door and there is no other.

  AND THE PERSISTED SLOT IS CLEARED AFTER IT, deliberately.
  `:rf.xray/set-mode` carries the `:rf.xray.static/persist-mode` fx,
  which writes localStorage — and `mount.cljs`'s `::hydrate-static-mode`
  first-mount hook READS that slot, so a lens this harness pinned would
  silently decide which surface a LATER suite's `open!` paints
  (`acceptance.substrate-gap-dom-cljs-test` is the one on this page).
  Clearing returns the page to the `:dynamic` default the hook falls back
  to, so this harness leaves no trace outside its own frames — which is
  the posture criteria 4 and 6 assert about Xray and this file should
  not itself break."
  []
  (reset! !last-uncaught nil)
  (registry/register-xray-handlers!)
  (rf/make-frame {:id xray-frame})
  (rf/make-frame {:id app-frame})
  (rf/make-frame {:id second-app-frame})
  (rf/make-frame {:id other-frame})
  (rf/dispatch-sync [:rf.xray/set-mode static-lens] {:frame xray-frame})
  (static-persistence/clear!)
  (rf/dispatch-sync [::app-seed 0] {:frame app-frame})
  (rf/dispatch-sync [::app-seed second-app-seed] {:frame second-app-frame})
  nil)

(defn mount-xray!
  "Mount Xray's SHIPPED SHELL through FRESCO'S OWN ROOT, at
  [[xray-frame]].

  This is the post-swap mount: Xray creates and owns the React root, the
  installed adapter's `:render` is never called, and the frame context is
  established DELIBERATELY by naming the frame rather than inherited from
  whatever the host's adapter happened to put in scope. `root!` commits
  inside `flushSync`, so the first assertion does not read an empty
  container.

  ## THE VECTOR IS `shell/ShellView`, WHICH IS THE WIDENING (rf2-3j1v)

  It was `[static-shell/surface {}]` — the Static ribbon alone — for as
  long as the Dynamic shell was an `rf/reg-view`. rf2-k97c.3 made it a
  Fresco boundary, so the head `mount.cljs` itself renders is now a legal
  vector here and this is it. The mode composer inside paints the Static
  surface while [[setup!]]'s lens pin stands, which is why every row
  written against the narrower subject reads the same DOM it always did;
  criterion 4's rewind arm flips the lens and gets the Dynamic chrome
  from the same mount.

  NO OUTER `frame-provider` OF OUR OWN, and its absence is deliberate
  rather than an omission. `ShellView`'s docstring makes the provider
  above its head part of its contract — both its reads are ambient
  `rf.fresco/sub`s — and `root!` supplies exactly that when handed a
  frame (`impl/mount.cljs`'s `tree`: `(provider frame-kw element)`).
  Adding a second would put a redundant Provider fiber above the shell
  for nothing. `:frame-id` is likewise left unnamed so it defaults to
  `shell/default-frame-id`, which IS [[xray-frame]] — the same
  frame-is-the-default shape production mounts with."
  []
  (let [container (rf.fresco.impl.mount/fresh-container!)]
    (rf.fresco.impl.mount/root! container xray-frame
                                [shell/ShellView {}])))

(defn unmount-xray!
  "Take Xray down the way criterion 6 needs it taken down.

  `rf.fresco.impl.mount/unmount!`, NOT `release!`. `release!` is the
  FIXTURE door and its last act is `reset-runtime!`, which empties the
  collector's tables — so a residue assertion after it reads zero whatever
  the teardown did, and could never turn red. `unmount!` deliberately
  empties nothing, which is what leaves a leak visible. The container is
  removed here because `unmount!` deliberately does not touch the caller's
  node."
  [handle]
  (rf.fresco.impl.mount/unmount! handle)
  (when-some [c (:container handle)]
    (when-some [p (.-parentNode c)] (.removeChild p c)))
  nil)

(defn- q [container sel] (.querySelector container sel))

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(defn- detail-panel-testid
  "The committed L4 panel's own testid, whatever tab it is showing — the
  single string criteria 1 and 3 both read."
  [container]
  (some-> (q container
             "[data-testid^=\"rf-xray-static-detail-panel-\"]")
          (.getAttribute "data-testid")))

(defn- panel-testid-for [tab-id]
  (str "rf-xray-static-detail-panel-" (name tab-id)))

(defn- tab-node [container tab-id]
  (testid container (str "rf-xray-static-tab-" (name tab-id))))

(defn- reset-button
  "The Dynamic tab ribbon's `Reset` rewind button, or nil while the
  Static lens is showing. Criterion 4's rewind arm's one control."
  [container]
  (testid container reset-button-testid))

(defn- reset-armed?
  "Is the `Reset` button committed AND enabled? `shell/tab-bar-tree`
  gates `:disabled` on `can-reset?`, which is `(some? focus-epoch)` — so
  this answers `Xray has an epoch to rewind to and is offering to`,
  which is the precondition the arm presses against. Read off the
  committed DOM rather than off the sub, because the sub is what the
  button is supposed to be reporting."
  [container]
  (when-some [btn (reset-button container)]
    (not (.-disabled btn))))

(defn- click!
  "Click a committed node, or FAIL THIS ROW rather than aborting the lane.

  A raw `.click` on nil throws out of the async block, and
  `cljs.test/run-block` has no try/catch — under a plant that emptied the
  chrome that takes the WHOLE browser lane down with no cljs.test summary,
  and every namespace scheduled after it never runs. A row whose subject
  has vanished should redden; it must not silence its neighbours."
  [node label]
  (if (some? node)
    (do (.click node) true)
    (do (is false (str "cannot click " label ": it is not in the committed "
                       "DOM, so this row's subject is already gone"
                       (uncaught-note)))
        false)))

(defn- selected-tab-in
  "One frame's current Static tab, read WITHOUT taking a reference.
  `subscribe-once` subscribes, derefs and unsubscribes, so a row may read
  as many frames as it likes without moving the numbers criterion 6
  measures."
  [frame-id]
  (rf/subscribe-once selected-tab-q {:frame frame-id}))

(defn- app-db-of [frame-id]
  @(:app-db (rf.frame/frame frame-id)))

(defn- cache-of [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- app-trace-event-ids
  "Every event id the APPLICATION's trace ring recorded, as a vector of
  whatever `:rf.event/v`'s head was. The instrument criterion 5 reads."
  []
  (->> (rf/trace-buffer app-frame {:flat true})
       (keep #(first (get-in % [:tags :rf.event/v])))
       (vec)))

(defn- app-render-records
  "Every `:rf.view/rendered` record the APPLICATION's own trace ring
  holds, RAW.

  THE CHANNEL [[app-trace-event-ids]] CANNOT SEE, AND THE REASON THIS
  EXISTS (rf2-kay8). That projection keeps only records carrying
  `[:tags :rf.event/v]`. A render record carries `:rf.view/render-key`
  and `:frame` and NO event vector at all, so it is discarded before any
  event-id assertion can reach it — and a leaked render contaminates the
  application's evidence while every one of those assertions stays green.
  The row below proves that in so many words.

  `:operation` is `trace-buffer`'s OWN flat-stream filter (Spec 009
  §Filter vocabulary), so this is a bounded read through the shipped
  reader rather than a second filter framework.

  RAW RECORDS RATHER THAN THE EPOCH `:renders` PROJECTION, DELIBERATELY,
  for two reasons. The projection is sourced FROM these emits — the epoch
  artefact back-fills a post-settle render into the causing epoch record
  — so the raw ring is strictly UPSTREAM: contamination cannot reach
  `:renders` without appearing here first. And this arm cannot go
  VACUOUSLY green, where an epoch arm could: `rf/epoch-history` answers
  `[]` when the epoch artefact is merely absent from the build, which is
  the very shape a clean epoch answers with, and this harness loads
  Xray's `registry` rather than the `install` / `preload` namespaces that
  pull `re-frame.epoch` in. The node-lane guard in `mount_cljs_test.cljs`
  (rf2-k97c.5 / rf2-tqlmq) reads `:renders` and is the right instrument
  there, because that suite does load it."
  []
  (rf/trace-buffer app-frame {:flat true :operation :rf.view/rendered}))

(defn- app-render-view-ids
  "The view ids in [[app-render-records]].

  Reads `:rf.view/id`, falling back to the head of
  `:rf.view/render-key`: production stamps both, while the minimal
  post-settle emit the `:renders` projection sources carries only the
  render-key. Taking either means a contaminating record cannot slip past
  by carrying the spelling this reader did not think of."
  []
  (->> (app-render-records)
       (map (fn [ev]
              (let [tags (:tags ev)]
                (or (:rf.view/id tags)
                    (first (:rf.view/render-key tags))))))
       (vec)))

(defn- emit-render-as!
  "Emit ONE `:rf.view/rendered` through the REAL trace emitter, tagged
  with `frame-id` and `view-id`, in the post-settle React-commit shape —
  render-key and frame, and NO `:rf.event/v`.

  The same emit `mount_cljs_test.cljs`'s `ei-emit-render!` makes for the
  node-lane guard on this channel. Criterion 5 uses it for its two
  CONTROLS ONLY: nothing under test is routed through it, and both calls
  happen after every assertion about the untouched world has been made.

  ## WHY THE `:rf.trace/dispatch-id` TAG IS HERE, AND WHY IT IS NOT NOISE

  DELETE IT AND BOTH CONTROLS SILENTLY MEASURE NOTHING. `push-to-ring!`
  retains an event only `(when (and dispatch-id frame-id))` — a frameless
  or uncorrelated emit streams to listeners and is NEVER retained (the
  Spec 009 §B3 ruling). These calls are made from a test body rather than
  from inside a running handler, so no `*handler-scope*` supplies one and
  the record would never reach the ring at all. `stamp-dispatch-id`'s own
  contract is that a `caller-supplied :rf.trace/dispatch-id wins`, so
  stamping it here is the sanctioned way to say `this render belongs to a
  run`, which is what production's handler scope says for it.

  The `:frame` tag is authoritative for the same documented reason:
  `stamp-frame` only ever SUPPLIES the routing tag and never overrides
  one the emit site set — which is what lets the second control attribute
  an Xray-named render to the APPLICATION's frame on purpose.

  Note what is NOT smuggled in: no `:rf.event/v`. The dispatch-id is a
  correlation tag, not an event vector, so [[app-trace-event-ids]] still
  cannot see these records — which is the very thing the last assertion
  of criterion 5 demonstrates."
  [frame-id view-id]
  (rf.trace/emit! :rf.view :rf.view/rendered
                  {:rf.view/render-key      [view-id 0]
                   :frame                   frame-id
                   :rf.trace/dispatch-id    (str "acceptance-c5-" (name view-id))}))

(defn- xray-namespaced?
  "Is `k` a keyword in the `rf.xray` namespace or any `rf.xray.*`
  sub-namespace? The same shape `self-noise/xray-internal-event-id?`
  classifies by, written out here so criterion 5's instrument does not
  route through the very production predicate whose job it is to keep
  these events out of sight — a filter cannot be both the subject and the
  instrument."
  [k]
  (and (keyword? k)
       (when-some [ns* (namespace k)]
         (or (= "rf.xray" ns*)
             (str/starts-with? ns* "rf.xray.")))))

(defn- fail-and-finish
  "Report a rejection against `label`. Does not finish the row — the single
  trailing step owns that, for the arm that never received a handle as much
  as for the one that did."
  [label]
  (fn [e]
    (is false (str label " never settled: " (.-message e) (uncaught-note)))
    nil))

(defn- skip! [n]
  (is true (str "criterion " n " skipped: the :browser-test runner drives "
                "the real React mount; this lane has no DOM")))

;; ===========================================================================
;; CRITERION 1 — first display
;; ===========================================================================

(defn c1-first-display!
  "Xray's Static chrome COMMITS — all three layers plus the L4 panel on its
  default tab — with the host's adapter installed and never called.

  Four literal testids and one literal tab name. The L4 assertion compares
  the panel's whole testid against `\"rf-xray-static-detail-panel-machines\"`
  rather than against the surface's own idea of which tab is selected: a
  panel that had lost its read and committed nothing would agree with a
  derived expectation and disagree with this one."
  [{:keys [label]} done]
  (if-not (browser?)
    (do (skip! 1) (done))
    (do
      (setup!)
      (let [handle    (mount-xray!)
            container (:container handle)]
        (-> (poll-until #(testid container "rf-xray-static-surface"))
            (.then
              (fn [_]
                (is (some? (testid container "rf-xray-static-surface"))
                    (str "[" label "] criterion 1 — the Static surface envelope "
                         "commits" (uncaught-note)))
                (is (some? (testid container "rf-xray-static-ribbon"))
                    (str "[" label "] criterion 1 — the L1 ribbon commits"
                         (uncaught-note)))
                (is (some? (testid container "rf-xray-static-tab-bar"))
                    (str "[" label "] criterion 1 — the L3 tab bar commits"
                         (uncaught-note)))
                (is (= (panel-testid-for default-tab)
                       (detail-panel-testid container))
                    (str "[" label "] criterion 1 — the L4 detail panel commits "
                         "on the default tab. Expected "
                         (pr-str (panel-testid-for default-tab))
                         ", got " (pr-str (detail-panel-testid container))
                         (uncaught-note)))
                (is (some? (tab-node container clicked-tab))
                    (str "[" label "] criterion 1 — and every registered Static "
                         "tab has a real control on screen, "
                         (pr-str clicked-tab) " included, so criterion 3 has "
                         "something to press" (uncaught-note)))))
            (.catch (fail-and-finish (str "[" label "] criterion 1")))
            (.then (fn [_] (unmount-xray! handle) (done))))))))

;; ===========================================================================
;; CRITERION 2 — updates as the app changes
;; ===========================================================================

(defn c2-updates-as-the-app-changes!
  "A REAL change in the inspected application reaches Xray's committed DOM
  through the production observation chain, and a settling window with the
  chain NOT pumped does not.

  The lever is the application registering a flow at run time — an ordinary
  thing a live app does — and the witness is the Static Flows panel growing
  that flow's row. The chain in between is the shipped one: the app's own
  dispatch lands in its per-frame trace ring, `refresh-trace-rings!` mirrors
  the rings into `:rf/xray`'s `:trace-buffer`, and the panel's read — which
  is gated on that buffer — is invalidated and recommits.

  `refresh-trace-rings!` is called explicitly because production reaches it
  through `rf.interop/next-tick`, a task that never fires inside a
  synchronous row.

  THE CONTROL IS THE HALF THAT MAKES THE UPDATE MEAN LIVENESS. After the
  flow exists but before the chain is pumped, a full settling window must
  still show NO row: a panel that repainted there would make the positive
  half pass for a reason that is not observation."
  [{:keys [label]} done]
  (if-not (browser?)
    (do (skip! 2) (done))
    (do
      (setup!)
      (let [handle    (mount-xray!)
            container (:container handle)
            row?      (fn [] (some? (testid container probe-flow-row-testid)))]
        (-> ;; Land on the Flows tab, so the row this criterion watches for is
            ;; on a surface that is actually committed.
            (do (rf/dispatch-sync [:rf.xray.static/select-tab :flows]
                                  {:frame xray-frame})
                (poll-until #(testid container "rf-xray-static-flows")))
            (.then
              (fn [_]
                (is (some? (testid container "rf-xray-static-flows"))
                    (str "[" label "] PRECONDITION: the Static Flows panel is on "
                         "screen, so a missing row below is the panel failing to "
                         "follow the app rather than the panel being absent"
                         (uncaught-note)))
                (is (false? (row?))
                    (str "[" label "] NON-VACUITY: the flow this row drives in is "
                         "NOT on screen before the application registers it"))
                ;; ---- the application changes ----------------------------
                (rf/reg-flow probe-flow-id
                             {:inputs      [[:probe :in]]
                              :output-path [:probe :out]
                              :doc         "criterion 2's run-time registration"
                              :frame       app-frame}
                             (fn [_] 0))
                (is (some? (get-in (rf.flows/flows-snapshot)
                                   [app-frame probe-flow-id]))
                    (str "[" label "] PRECONDITION: the application's own registry "
                         "really carries the new flow — so a missing row below is "
                         "Xray failing to observe, not the flow failing to exist"))
                (settle)))
            (.then
              (fn [_]
                (is (false? (row?))
                    (str "[" label "] CONTROL: given a full settling window with "
                         "the observation chain NOT pumped, the committed DOM "
                         "still does NOT carry the row. A panel that repainted "
                         "here would make the assertion below pass for a reason "
                         "that is not observation"))
                ;; ---- the app does something, and Xray observes it -------
                (rf/dispatch-sync [::app-bump] {:frame app-frame})
                (trace-collector/refresh-trace-rings!)
                (poll-until row?)))
            (.then
              (fn [_]
                (is (true? (row?))
                    (str "[" label "] criterion 2 — a real application change "
                         "reached Xray's committed DOM through the production "
                         "observation chain: the row "
                         (pr-str probe-flow-row-testid) " is on screen"
                         (uncaught-note)))
                (is (= 1 (:count (app-db-of app-frame)))
                    (str "[" label "] and the application really did move — its "
                         "own `:count` is 1, so the pump above had a real event "
                         "to carry. Got: "
                         (pr-str (:count (app-db-of app-frame)))))))
            (.catch (fail-and-finish (str "[" label "] criterion 2")))
            (.then (fn [_] (unmount-xray! handle) (done))))))))

;; ===========================================================================
;; CRITERION 3 — Xray's own interactions
;; ===========================================================================

(defn c3-xrays-own-interactions!
  "A REAL browser click on a REAL Static tab button moves the L4 panel, and
  the pressed tab reports itself selected.

  The whole round trip travels the shipped path: React's own click event,
  the `:on-click` closure the tab button carries, the dispatcher the
  `tab-bar` BOUNDARY captured with `(:dispatch (rf/capture-frame))`, the
  handler, the invalidated read, the recommitted boundary. Nothing here
  dispatches on the surface's behalf — that would exercise the reactivity
  and skip the capture, which is the boundary's other half and the half
  with somewhere to fail.

  Both expectations are literals: `\"rf-xray-static-detail-panel-flows\"`
  and `\"true\"`."
  [{:keys [label]} done]
  (if-not (browser?)
    (do (skip! 3) (done))
    (do
      (setup!)
      (let [handle    (mount-xray!)
            container (:container handle)
            aria-of   (fn [] (some-> (tab-node container clicked-tab)
                                     (.getAttribute "aria-selected")))]
        (-> (poll-until #(detail-panel-testid container))
            (.then
              (fn [_]
                (let [btn (tab-node container clicked-tab)]
                  (is (some? btn)
                      (str "[" label "] PRECONDITION: the "
                           (name clicked-tab) " tab button is committed, so "
                           "there is a real control to press" (uncaught-note)))
                  (is (= "false" (aria-of))
                      (str "[" label "] PRECONDITION: and it is not already the "
                           "selected tab, so the flip below is the click's "
                           "doing. Got: " (pr-str (aria-of))))
                  (is (= (panel-testid-for default-tab)
                         (detail-panel-testid container))
                      (str "[" label "] PRECONDITION: the L4 panel is showing "
                           (pr-str (panel-testid-for default-tab))
                           ". Got: " (pr-str (detail-panel-testid container))))
                  (if (click! btn (str "the Static " (name clicked-tab) " tab"))
                    (poll-until #(= (panel-testid-for clicked-tab)
                                    (detail-panel-testid container)))
                    (js/Promise.resolve nil)))))
            (.then
              (fn [_]
                (is (= (panel-testid-for clicked-tab)
                       (detail-panel-testid container))
                    (str "[" label "] criterion 3 — the click reached the handler "
                         "through the dispatcher the boundary captured, the read "
                         "was invalidated and the L4 boundary recommitted on the "
                         "new tab. Expected "
                         (pr-str (panel-testid-for clicked-tab))
                         ", got " (pr-str (detail-panel-testid container))
                         (uncaught-note)))
                (is (= "true" (aria-of))
                    (str "[" label "] and the pressed button now reports itself "
                         "selected, so the L3 boundary recommitted too rather "
                         "than only the L4 one. Got: " (pr-str (aria-of))))))
            (.catch (fail-and-finish (str "[" label "] criterion 3")))
            (.then (fn [_] (unmount-xray! handle) (done))))))))

;; ===========================================================================
;; CRITERION 4 — tool-local state separate from the app, commands targeting
;;               the intended frame
;; ===========================================================================

(defn c4-tool-local-state-and-frame-targeting!
  "Xray's own state lands in Xray's frame and NOWHERE ELSE.

  Four instruments, answering four different things:

  - the TOOL's frame moved to [[clicked-tab]] — the command arrived;
  - the APPLICATION's `app-db` is IDENTICAL to what it was — the tool
    wrote nothing into the thing it is inspecting;
  - a SECOND INSPECTED APPLICATION, [[second-app-frame]], is still at its
    own distinct seed — so `the tool wrote into no application` is a
    claim about applications in the plural, which is what stops one
    untouched frame reading as a general result;
  - a SECOND live frame, held at [[deaf-frame-tab]] before the mount, is
    still there — the command routed where it was told rather than
    everywhere, or somewhere else.

  THE THIRD IS THE ONE THAT MAKES THIS A ROUTING CLAIM. A dispatcher that
  lost its capture does not crash; it degrades to the ambient one, which
  resolves a frame that is not the tool's. A positive-only row passes on a
  dispatcher that routes everywhere and on one that routes elsewhere.

  THE THEME TOGGLE CANNOT CARRY THIS ROW and is deliberately not used: its
  read falls through `settings/subs.cljs` to `config/get-setting`, a
  PROCESS-GLOBAL atom rather than any frame's `app-db`, so it passes under
  a deliberately wrong frame and witnesses nothing about targeting. The
  Static tab selection is an ordinary `app-db` read, which is why it is the
  lever here.

  ## THE SECOND HALF — AN APP-DIRECTED COMMAND, THROUGH ITS REAL UI PATH

  Criterion 4 has TWO halves: Xray's own state stays out of the
  application, and a tool-issued command reaches the application frame it
  was AIMED at. Everything above is the FIRST half. The REWIND ARM at the
  bottom is the second (rf2-3j1v).

  The command is the Dynamic tab ribbon's `Reset` button — Xray's ONE
  app-directed affordance, and an existing product command rather than
  one written for this row. Its whole path is the shipped one: a real
  browser click on the real `<button data-testid=\"rf-xray-tab-bar-reset\">`,
  the `:on-click` closure it carries, the dispatcher `tab-bar` captured
  with `(:dispatch (rf/capture-frame))`, `:rf.xray/reset-to-epoch` with
  the OBSERVED frame and the focused epoch, the
  `:rf.xray.fx/restore-epoch` effect, and the framework's own
  `rf/restore-epoch!` rewinding that frame's `app-db`. Nothing here
  dispatches on Xray's behalf.

  THREE THINGS IT ASSERTS, AND THEY ARE THE CRITERION'S OWN WORDS:

  - the SELECTED application is rewound to the epoch Xray was pointed
    at — the command reached the frame it was aimed at;
  - the UNSELECTED application, at its own distinct seed, is untouched —
    a command that went everywhere, or to the wrong application, fails
    here and only here;
  - the tool's own frame and the deaf lever are untouched, so tool-local
    isolation survives a command that deliberately does reach outside.

  MISROUTING IT REDDENS THE CASE, which is the criterion's disable
  clause: point [[setup!]]'s target frame at [[second-app-frame]] instead
  and the rewind lands in the wrong application — the selected one stays
  at its post-bump value and the unselected one moves. Both assertions
  turn, from opposite sides.

  ## THE PROHIBITION THAT STOOD HERE, AND WHY IT NO LONGER BINDS
  ## (rf2-t2ke, discharged by rf2-3j1v)

  This docstring used to record that the row covered the first half
  ONLY, that *a regression routing every app-directed Xray command to the
  wrong application frame would leave this row green*, and that the gap
  was deliberate. It was, and the measurement behind it stands: over
  `tools/xray/src/day8/re_frame2_xray/static/**` the only `{:frame …}`
  DISPATCH option anywhere in the Static tree is
  `defaults/default-frame-id`, which IS the Xray frame — the `{:frame …}`
  options in `static/schemas/panel.cljs` being cross-frame READS.
  `static/routes/simulate_nav.cljs` still states the posture in terms —
  *Xray is a lens, not a remote control* — and the machine simulator
  still clones the definition into Xray's OWN `app-db` so the host frame
  is never touched.

  WHAT CHANGED IS THE SUBJECT, NOT THE POSTURE. The Static ribbon issues
  no app-directed command and still issues none; `Reset` is on the
  DYNAMIC ribbon, which this harness could not mount until rf2-k97c.3
  made the shell a Fresco boundary. So no product command was invented —
  the prohibition rf2-t2ke wrote (*the staged Static surface has no such
  command; do not invent one*) was never lifted, it was routed around by
  widening the bench.

  AND `Reset` IS THE ONLY ONE, which is why the arm below is singular
  rather than a sample. The `{:frame frame}` dispatch closures across the
  Dynamic panels all target the SURROUNDING INSTANCE FRAME — Xray's own —
  and `:rf.xray/set-target-frame` writes Xray's own `app-db` too. The
  single Xray affordance whose effect lands in an inspected application
  is this one, and it reaches it through `rf/restore-epoch!` rather than
  through a `{:frame …}` dispatch option at all — which is exactly why
  the census that established the prohibition could not see it."
  [{:keys [label]} done]
  (if-not (browser?)
    (do (skip! 4) (done))
    (do
      (setup!)
      ;; The deaf frame is loaded BEFORE the mount, so the control compares
      ;; against a value a misrouted write would have to OVERWRITE. Held at
      ;; the default instead, a dispatcher that had fallen through to an
      ;; ambient frame and written the default there would be
      ;; indistinguishable from one that never wrote at all.
      (rf/dispatch-sync [:rf.xray.static/select-tab deaf-frame-tab]
                        {:frame other-frame})
      (let [handle    (mount-xray!)
            container (:container handle)
            !app-db   (atom nil)
            ;; The epoch the REWIND ARM aims Xray at — captured below,
            ;; while the application demonstrably still holds its seed.
            !rewind   (atom nil)
            rewind-id (fn [] (:epoch-id @!rewind))
            observed  (fn [] (rf/subscribe-once [:rf.xray/observed-frame]
                                                {:frame xray-frame}))
            focused   (fn [] (rf/subscribe-once [:rf.xray/focus-epoch-id]
                                                {:frame xray-frame}))
            flash     (fn [] (rf/subscribe-once [:rf.xray/reset-flash]
                                                {:frame xray-frame}))]
        (-> (poll-until #(tab-node container clicked-tab))
            (.then
              (fn [_]
                (reset! !app-db (app-db-of app-frame))
                (is (= {:count 0} @!app-db)
                    (str "[" label "] PRECONDITION: the application's db is the "
                         "seeded {:count 0}, so the comparison after the click "
                         "is against a known value. Got: " (pr-str @!app-db)))
                (is (= default-tab (selected-tab-in xray-frame))
                    (str "[" label "] PRECONDITION: the tool's frame holds the "
                         "default tab. Got: "
                         (pr-str (selected-tab-in xray-frame))))
                (is (= deaf-frame-tab (selected-tab-in other-frame))
                    (str "[" label "] PRECONDITION: while the second live frame "
                         "holds " (pr-str deaf-frame-tab) ", so `unchanged` "
                         "below is a claim about a real value. Got: "
                         (pr-str (selected-tab-in other-frame))))
                (is (= {:count second-app-seed}
                       (app-db-of second-app-frame))
                    (str "[" label "] PRECONDITION: and the SECOND inspected "
                         "application holds its own distinct seed {:count "
                         second-app-seed "}, so the two applications are told "
                         "apart by value and not merely by name. Got: "
                         (pr-str (app-db-of second-app-frame))))
                (if (click! (tab-node container clicked-tab)
                            (str "the Static " (name clicked-tab) " tab"))
                  (poll-until #(= clicked-tab (selected-tab-in xray-frame)))
                  (js/Promise.resolve nil))))
            (.then
              (fn [_]
                (is (= clicked-tab (selected-tab-in xray-frame))
                    (str "[" label "] criterion 4 — the command landed in the "
                         "frame the tree NAMED. Expected " (pr-str clicked-tab)
                         ", got " (pr-str (selected-tab-in xray-frame))
                         (uncaught-note)))
                (is (= {:count 0} (app-db-of app-frame))
                    (str "[" label "] criterion 4 — and the INSPECTED "
                         "APPLICATION's db is untouched: Xray's tool-local state "
                         "is not in the app it is inspecting. Expected "
                         "{:count 0}, got " (pr-str (app-db-of app-frame))))
                (is (= deaf-frame-tab (selected-tab-in other-frame))
                    (str "[" label "] criterion 4 — CROSS-FRAME CONTROL: and the "
                         "second live frame still holds "
                         (pr-str deaf-frame-tab) ". A dispatcher that had lost "
                         "its capture and fallen back to the ambient one would "
                         "write here, or nowhere. Got: "
                         (pr-str (selected-tab-in other-frame))))
                (is (= {:count second-app-seed}
                       (app-db-of second-app-frame))
                    (str "[" label "] criterion 4 — and the SECOND inspected "
                         "application is untouched too, at its own distinct "
                         "{:count " second-app-seed "}. One untouched "
                         "application is compatible with a tool that writes to "
                         "exactly one wrong place; two are not. Got: "
                         (pr-str (app-db-of second-app-frame))))
                (is (zero? (ref-count-of app-frame selected-tab-q))
                    (str "[" label "] criterion 4 — and the tool took no "
                         "subscription reference in the application's frame for "
                         "its own read. Application cache keys: "
                         (pr-str (keys (cache-of app-frame)))))
                ;; ---- INSTRUMENT CONTROL, and it is NOT coverage ----------
                ;; Both applications reading `unchanged` is equally true of
                ;; two frames nothing could ever write to, and those two
                ;; readings are indistinguishable from here. This dispatch
                ;; is the HARNESS's own, NOT Xray's: it shows a
                ;; frame-targeted write landing in ONE application and not
                ;; the other, so the assertions above are known to be
                ;; standing over live, separable frames.
                ;;
                ;; IT IS NOT THE MISSING HALF OF THE CRITERION, and must not
                ;; be read as it — the commander here is the test, where the
                ;; criterion wants XRAY. See the COVERAGE note in this fn's
                ;; docstring (rf2-t2ke).
                (rf/dispatch-sync [::app-bump] {:frame second-app-frame})
                (is (= {:count (inc second-app-seed)}
                       (app-db-of second-app-frame))
                    (str "[" label "] INSTRUMENT CONTROL: a frame-targeted "
                         "write DOES land in the application it names — the "
                         "second application moved to {:count "
                         (inc second-app-seed) "}. Without this, `both "
                         "applications unchanged` above could be a statement "
                         "about two dead frames. Got: "
                         (pr-str (app-db-of second-app-frame))))
                (is (= {:count 0} (app-db-of app-frame))
                    (str "[" label "] INSTRUMENT CONTROL: and it landed in that "
                         "application ONLY — the first is still {:count 0}. So "
                         "the instrument can tell the two apart, and "
                         "`untouched` above means untouched rather than "
                         "unreadable. Got: " (pr-str (app-db-of app-frame))))
                ;; ==========================================================
                ;; THE REWIND ARM (rf2-3j1v) — criterion 4's SECOND half
                ;; ==========================================================
                ;;
                ;; Everything above is `the tool wrote into no application`.
                ;; From here the tool is made to write into ONE application
                ;; ON PURPOSE, through the only app-directed command Xray
                ;; ships, and the claim is that it reached the application
                ;; it was AIMED at and no other.
                ;;
                ;; The rewind target is captured HERE rather than at the
                ;; top of the row because the assertion immediately above
                ;; is what licenses it: the application is provably still
                ;; at its seed, so the last epoch on its ring is provably
                ;; the `{:count 0}` state this arm rewinds to. Nothing
                ;; between the two lines can move it.
                (reset! !rewind (last (rf/epoch-history app-frame)))
                (is (some? (rewind-id))
                    (str "[" label "] NON-VACUITY: the application's epoch ring "
                         "carries a recorded epoch to rewind TO. `rf/epoch-"
                         "history` answers [] when the epoch artefact is merely "
                         "absent from the build — the same shape a clean ring "
                         "answers with — so this is the assertion that makes "
                         "the rewind below a claim about Xray rather than about "
                         "a no-op. Ring: "
                         (pr-str (mapv :epoch-id (rf/epoch-history app-frame)))))
                ;; ---- move BOTH applications off the rewind point --------
                ;; The second one is already at (inc second-app-seed) from
                ;; the instrument control above. Moving the first is what
                ;; makes `rewound` a change rather than a coincidence.
                (rf/dispatch-sync [::app-bump] {:frame app-frame})
                (is (= {:count 1} (app-db-of app-frame))
                    (str "[" label "] PRECONDITION: the SELECTED application has "
                         "moved OFF the epoch Xray is about to be pointed at, so "
                         "the rewind below has somewhere to come back from. "
                         "Expected {:count 1}, got "
                         (pr-str (app-db-of app-frame))))
                ;; ---- aim Xray: observed frame + focused epoch ------------
                ;; Both are Xray's own tool-local events writing Xray's own
                ;; app-db — the picker and the epoch pin a user drives from
                ;; the L1 frame switcher and an L2 row. They decide what the
                ;; `Reset` button targets; they are not themselves the
                ;; app-directed command.
                (rf/dispatch-sync [:rf.xray/set-target-frame app-frame]
                                  {:frame xray-frame})
                (rf/dispatch-sync [:rf.xray/select-epoch (rewind-id)]
                                  {:frame xray-frame})
                (is (= app-frame (observed))
                    (str "[" label "] PRECONDITION: Xray is OBSERVING the first "
                         "application, so the button below is aimed at a known "
                         "frame rather than wherever the spine drifted. Expected "
                         (pr-str app-frame) ", got " (pr-str (observed))))
                (is (= (rewind-id) (focused))
                    (str "[" label "] PRECONDITION: and its focused epoch is the "
                         "one captured above — the button carries THIS epoch, "
                         "not a head the spine re-derived. Expected "
                         (pr-str (rewind-id)) ", got " (pr-str (focused))))
                (is (nil? (flash))
                    (str "[" label "] PRECONDITION: and no stale rewind-failure "
                         "flash is standing, so a flash after the click is this "
                         "click's. Got: " (pr-str (flash))))
                ;; ---- show the surface the command lives on --------------
                ;; `Reset` is on the DYNAMIC tab ribbon. The lens is Xray's
                ;; own, tool-local, and the mode composer inside the SAME
                ;; mount swaps the surface — no second root, no remount.
                (rf/dispatch-sync [:rf.xray/set-mode dynamic-lens]
                                  {:frame xray-frame})
                (static-persistence/clear!)
                (poll-until #(reset-armed? container))))
            (.then
              (fn [_]
                (is (true? (reset-armed? container))
                    (str "[" label "] PRECONDITION: the Dynamic ribbon's `Reset` "
                         "button is committed and ENABLED, so there is a real "
                         "app-directed control to press. Node: "
                         (pr-str (some? (reset-button container)))
                         (uncaught-note)))
                (if (click! (reset-button container)
                            "the Dynamic ribbon's Reset button")
                  ;; `:on-click` dispatches ASYNCHRONOUSLY through the
                  ;; captured dispatcher, so the effect runs on a later
                  ;; tick. Poll on the APPLICATION rather than on the
                  ;; click having happened.
                  (poll-until #(= {:count 0} (app-db-of app-frame)))
                  (js/Promise.resolve nil))))
            (.then
              (fn [_]
                (is (= {:count 0} (app-db-of app-frame))
                    (str "[" label "] criterion 4 — AN APP-DIRECTED XRAY COMMAND "
                         "REACHED THE APPLICATION IT WAS AIMED AT: a real click "
                         "on the shipped `Reset` button rewound the SELECTED "
                         "application to the focused epoch, through the "
                         "dispatcher the tab-bar boundary captured and the "
                         "framework's own `restore-epoch!`. Expected {:count 0}, "
                         "got " (pr-str (app-db-of app-frame))
                         " — rewind flash: " (pr-str (flash))
                         (uncaught-note)))
                (is (nil? (flash))
                    (str "[" label "] criterion 4 — and the framework ACCEPTED "
                         "the restore rather than refusing it: no inline failure "
                         "flash. A refused restore leaves the application "
                         "unchanged, which is the one way the assertion above "
                         "could be satisfied by a command that did nothing. Got: "
                         (pr-str (flash))))
                (is (= {:count (inc second-app-seed)}
                       (app-db-of second-app-frame))
                    (str "[" label "] criterion 4 — and the UNSELECTED "
                         "application, at its own distinct value, is UNTOUCHED. "
                         "This is the assertion a misrouted command fails: aim "
                         "the picker at the second application and the rewind "
                         "lands here instead, turning this and the one above "
                         "from opposite sides. Expected {:count "
                         (inc second-app-seed) "}, got "
                         (pr-str (app-db-of second-app-frame))))
                (is (= deaf-frame-tab (selected-tab-in other-frame))
                    (str "[" label "] criterion 4 — and TOOL-LOCAL ISOLATION "
                         "SURVIVES a command that deliberately does reach "
                         "outside: the deaf lever still holds "
                         (pr-str deaf-frame-tab) ". Got: "
                         (pr-str (selected-tab-in other-frame))))
                (is (nil? (:count (app-db-of xray-frame)))
                    (str "[" label "] criterion 4 — and the restored application "
                         "state landed in the APPLICATION rather than in the "
                         "tool's own frame. Xray's `app-db` carries no `:count` "
                         "at all. Got: "
                         (pr-str (:count (app-db-of xray-frame)))))))
            (.catch (fail-and-finish (str "[" label "] criterion 4")))
            (.then (fn [_] (unmount-xray! handle) (done))))))))

;; ===========================================================================
;; CRITERION 5 — Xray's activity never masquerading as application evidence
;; ===========================================================================

(defn c5-no-masquerading-as-application-evidence!
  "Everything Xray does — mounting, rendering, and a real user interaction
  with the tool — contributes NOTHING to the inspected application's
  evidence.

  The instrument is the APPLICATION's OWN per-frame trace ring, read
  through `rf/trace-buffer`. That is the evidence a developer reads and the
  evidence every downstream tool projects, so it is the right place to ask
  the question — and it is upstream of `self-noise`'s display-time filter,
  which means this row cannot be satisfied by Xray merely hiding its own
  noise from its own list.

  THE POSITIVE CONTROL IS LOAD-BEARING. An empty ring satisfies `no Xray
  events` for free, and a ring that records nothing at all is exactly what
  a broken instrument looks like. So the application dispatches its own
  event first, and the row asserts that event IS in the ring before
  asserting that Xray's is not.

  `xray-namespaced?` is written out in this namespace rather than taken
  from `self-noise`: a filter cannot be both the subject and the
  instrument.

  ## TWO CHANNELS, BECAUSE EVENT IDS ARE NOT THE WHOLE EVIDENCE (rf2-kay8)

  `evidence` is not `events`. The row reads the application's ring along
  BOTH of the axes Xray can contaminate:

  - EVENT IDS, via [[app-trace-event-ids]] — the original arm, kept
    intact;
  - RENDER RECORDS, via [[app-render-view-ids]] — records carrying
    `:rf.view/render-key` and `:frame` and NO `:rf.event/v`.

  THE SECOND ARM EXISTS BECAUSE THE FIRST CANNOT SEE IT AT ALL.
  [[app-trace-event-ids]] keeps only records that carry an event vector,
  so a leaked `:rf.view/rendered` is discarded before any assertion
  reaches it — it can contaminate the application's evidence, and the
  epoch `:renders` projection sourced from it, while every event-id
  assertion stays green. That is not hypothetical: it is the shape of
  rf2-tqlmq, the already-realised defect the node-lane guard in
  `mount_cljs_test.cljs` (rf2-k97c.5) pins for the shell's own render.
  This row is the real-browser, two-substrate counterpart.

  THE LAST FOUR ASSERTIONS ARE CONTROLS, and they run in this order for a
  reason. Every assertion about the untouched world is made FIRST; only
  then does the row emit, through the real emitter, a genuine
  application-attributed render (proving the reader is live and the
  channel is not merely empty) and then a deliberately misattributed
  Xray one (proving the detector bites). The closing assertion re-reads
  the EVENT-ID arm over that now-contaminated ring and shows it still
  reporting clean — rf2-kay8's finding, kept as a live assertion rather
  than as a comment somebody can delete without noticing."
  [{:keys [label]} done]
  (if-not (browser?)
    (do (skip! 5) (done))
    (do
      (setup!)
      (let [handle    (mount-xray!)
            container (:container handle)]
        (-> (poll-until #(tab-node container clicked-tab))
            (.then
              (fn [_]
                ;; The application does something of its own, so the ring is
                ;; demonstrably recording.
                (rf/dispatch-sync [::app-bump] {:frame app-frame})
                (settle)))
            (.then
              (fn [_]
                (is (true? (boolean (some #{::app-bump} (app-trace-event-ids))))
                    (str "[" label "] POSITIVE CONTROL: the application's own "
                         "event IS in its trace ring, so the absence asserted "
                         "below is a property of Xray and not of a ring that "
                         "records nothing. Ring: "
                         (pr-str (app-trace-event-ids))))
                ;; ---- Xray does something of its own ---------------------
                (if (click! (tab-node container clicked-tab)
                            (str "the Static " (name clicked-tab) " tab"))
                  (poll-until #(= clicked-tab (selected-tab-in xray-frame)))
                  (js/Promise.resolve nil))))
            (.then
              (fn [_]
                (is (= clicked-tab (selected-tab-in xray-frame))
                    (str "[" label "] NON-VACUITY: Xray's own interaction really "
                         "happened — its tab moved to " (pr-str clicked-tab)
                         " — so the absence below is about an event that was "
                         "genuinely dispatched. Got: "
                         (pr-str (selected-tab-in xray-frame))
                         (uncaught-note)))
                (settle)))
            (.then
              (fn [_]
                (let [ids  (app-trace-event-ids)
                      xray (filterv xray-namespaced? ids)]
                  (is (= [] xray)
                      (str "[" label "] criterion 5 — the application's trace "
                           "ring carries NO `rf.xray`-namespaced event after a "
                           "real Xray mount, render and interaction. Expected "
                           "[], got " (pr-str xray)
                           " — whole ring: " (pr-str ids)))
                  ;; STRONGER THAN THE `rf.xray` FILTER ABOVE, and deliberately
                  ;; so: the set of DISTINCT event ids in the application's ring
                  ;; is exactly the two the application itself dispatched. That
                  ;; catches a foreign event under ANY spelling, where the
                  ;; assertion above catches only one the `rf.xray` namespace
                  ;; happens to name. One dispatch emits several trace ops all
                  ;; carrying the same `:rf.event/v`, so this is a set.
                  (is (= #{::app-seed ::app-bump} (set ids))
                      (str "[" label "] and the ONLY event ids in the "
                           "application's ring are the two the application "
                           "itself dispatched. Expected "
                           (pr-str #{::app-seed ::app-bump}) ", got "
                           (pr-str (set ids)))))
                ;; ---- THE RENDER CHANNEL (rf2-kay8) ----------------------
                ;; Every assertion above reads EVENT IDS. A leaked render
                ;; record carries no `:rf.event/v` at all, so not one of
                ;; them can see one — including the exact-set assertion,
                ;; which is `stronger` only along the axis it shares. The
                ;; controls at the bottom of this row prove that in so many
                ;; words rather than asserting it.
                (let [render-ids (app-render-view-ids)
                      xray-r     (filterv xray-namespaced? render-ids)]
                  (is (= [] xray-r)
                      (str "[" label "] criterion 5 — the application's trace "
                           "ring carries NO `rf.xray`-namespaced RENDER after "
                           "a real Xray mount, render and interaction. "
                           "Expected [], got " (pr-str xray-r)
                           " — whole render channel: " (pr-str render-ids)))
                  ;; STRONGER, and for the same reason the exact-set
                  ;; assertion above is: the application mounts no views in
                  ;; this harness, so its render evidence is empty on a
                  ;; correctly scoped tool WHATEVER a contaminating record
                  ;; happens to be called.
                  (is (= [] render-ids)
                      (str "[" label "] and the application's render evidence "
                           "is EMPTY — it mounts no views here, so ANY render "
                           "record in its ring came from somewhere else, under "
                           "any spelling. Expected [], got "
                           (pr-str render-ids))))
                ;; ---- POSITIVE CONTROL: the channel is not merely empty ---
                ;; An empty render channel satisfies every assertion above
                ;; for free, and an empty channel is NOT isolation — it is
                ;; also exactly what a dead reader looks like. A genuine
                ;; APPLICATION-attributed render, through the same real
                ;; emitter, must be visible.
                (emit-render-as! app-frame ::app-view)
                (is (= [::app-view] (app-render-view-ids))
                    (str "[" label "] POSITIVE CONTROL: a genuine APPLICATION "
                         "render IS visible in the application's own ring, so "
                         "the emptiness above is a property of Xray rather "
                         "than of a reader that can see nothing. Expected "
                         (pr-str [::app-view]) ", got "
                         (pr-str (app-render-view-ids))))
                ;; ---- DETECTOR CONTROL: the check BITES ------------------
                ;; A misattributed Xray render — `rf.xray`-namespaced, tagged
                ;; with the APPLICATION's frame, carrying render-key and
                ;; frame and NO `:rf.event/v`. This is the shape of the
                ;; already-realised defect rf2-tqlmq. The detector must find
                ;; it; if it ever cannot, the assertions above have gone
                ;; blind and this row says so on the spot.
                (emit-render-as! app-frame :rf.xray.static/tab-bar)
                (is (= [:rf.xray.static/tab-bar]
                       (filterv xray-namespaced? (app-render-view-ids)))
                    (str "[" label "] DETECTOR CONTROL: a deliberately "
                         "misattributed Xray render IS detected, so the "
                         "empty result above is a clean ring and not a check "
                         "that cannot fail. Expected "
                         (pr-str [:rf.xray.static/tab-bar]) ", got "
                         (pr-str (filterv xray-namespaced?
                                          (app-render-view-ids)))))
                ;; ---- AND WHY THE EVENT-ID ARM IS NOT ENOUGH -------------
                ;; The ring now DEMONSTRABLY carries a foreign Xray record.
                ;; The event-id projection still reports it clean, because a
                ;; render record has no `:rf.event/v` for it to keep. That
                ;; is rf2-kay8 stated as a passing assertion: delete the
                ;; render arm above and this is the coverage that remains.
                (is (= #{::app-seed ::app-bump} (set (app-trace-event-ids)))
                    (str "[" label "] and the EVENT-ID reader is BLIND to all "
                         "of it — with two foreign render records now in the "
                         "ring it still reports exactly the application's own "
                         "two event ids. This is why criterion 5 reads the "
                         "render channel directly (rf2-kay8). Got: "
                         (pr-str (set (app-trace-event-ids)))))))
            (.catch (fail-and-finish (str "[" label "] criterion 5")))
            (.then (fn [_] (unmount-xray! handle) (done))))))))

;; ===========================================================================
;; CRITERION 6 — clean teardown and reopen without disturbing the host
;; ===========================================================================

(defn- cell-key
  "The collector's own address for the chrome's headline read —
  `[frame-kw query-v]`, finer than the frame's sub-cache key and what
  `!cells` is keyed by."
  []
  [xray-frame selected-tab-q])

(defn- residue
  "Everything that must return to baseline once Xray is gone, as one map,
  so a failure names WHICH instrument still sees something. FIVE
  instruments answering different questions — `unmount-xray!` being CALLED
  is not one of them.

  - `:ref-count`    the frame's own sub-cache reference for the headline read
  - `:live-cell?`   the WATCH half: a live collector cell holds `add-watch`
                    on its derived reaction for as long as it exists
  - `:reader-edges` the LISTENER half: one reader slot per boundary
                    registration reading that cell
  - `:runtime`      the WHOLE collector census, which is what makes this a
                    claim about the CHROME rather than about one query.
                    Enumerating the chrome's boundaries here would go stale
                    the first time a region gains a read; a census cannot.
  - `:body-nodes`   the document's own child count, so a container or a
                    portal left behind is visible

  A release that dropped the reference and left the cell wired answers
  clean to the first and dirty to the next three."
  []
  {:ref-count    (ref-count-of xray-frame selected-tab-q)
   :live-cell?   (some? (rf.fresco.test.runtime/cell-reaction (cell-key)))
   :reader-edges (count (rf.fresco.test.runtime/cell-readers (cell-key)))
   :runtime      (rf.fresco.test.runtime/residue)
   :body-nodes   (.-childElementCount (.-body js/document))})

(defn c6-clean-teardown-and-reopen!
  "Unmounting Xray returns everything it took, the HOST is undisturbed
  across the whole cycle, and reopening takes the same numbers rather than
  higher ones.

  ## Teardown is MEASURED, not asserted

  A row that only checks the chrome is gone passes over a leak. Five
  instruments are read before the mount, while mounted, after the unmount,
  after a reopen and after a second unmount.

  ## THE REOPEN IS THE HALF THAT CATCHES A RELEASE THAT RELEASES NOTHING

  A first mount's teardown can look clean for reasons that are not the
  teardown's — a page that never had anything to lose reads zero whatever
  the code does. Growth across open/close/open is the signature of a
  release the substrate's own lifecycle cannot see, and it is only visible
  on the second mount.

  ## THE HOST HALF IS THE OTHER HALF OF THE CRITERION

  `without disturbing the host` is not a restatement of `clean teardown`.
  The application holds a LIVE SUBSCRIPTION across the entire cycle — a
  real host reference, taken before Xray exists and still held after Xray
  is gone twice — and the row asserts its ref-count, its value and the
  application's `app-db` are exactly what they were. A tool that tore down
  tidily but disposed a reference belonging to the app it was inspecting
  passes every instrument above and fails here.

  ## THE SETTLING POINT IS THE KIT'S OWN `quiesced!`

  The collector gives a cell whose last reader unmounts one macrotask of
  grace, deliberately, and the entry reaper's horizon sits outside a bare
  `setTimeout 0`. A residue read before that point reports a LEAK against
  a runtime behaving exactly as documented."
  [{:keys [label]} done]
  (if-not (browser?)
    (do (skip! 6) (done))
    (do
      (setup!)
      (let [;; The HOST's own live reference, taken before Xray exists.
            host-ref   (rf/subscribe [::app-count] {:frame app-frame})
            host-key   [::app-count]
            !baseline  (atom nil)
            !mounted   (atom nil)
            !handle    (atom nil)
            mount!     (fn []
                         (reset! !handle (mount-xray!))
                         (poll-until #(detail-panel-testid
                                        (:container @!handle))))
            unmount!   (fn []
                         (when-some [h @!handle]
                           (reset! !handle nil)
                           (unmount-xray! h)))
            host-refs  (fn [] (ref-count-of app-frame host-key))]
        (-> (do
              (is (= 0 @host-ref)
                  (str "[" label "] PRECONDITION: the host's own subscription "
                       "reads its seeded value. Got: " (pr-str @host-ref)))
              (rf.fresco.test.runtime/quiesced!))
            (.then
              (fn [_]
                (let [b (residue)]
                  (reset! !baseline b)
                  (is (= empty-runtime (:runtime b))
                      (str "[" label "] BASELINE: the collector holds nothing "
                           "before the first mount, so `returns to baseline` "
                           "below is a return to ZERO rather than to a residue "
                           "a leak could hide inside. Got: "
                           (pr-str (:runtime b))))
                  (is (= 0 (:ref-count b))
                      (str "[" label "] BASELINE: and the tool's frame holds no "
                           "sub-cache reference for the chrome's read. Keys: "
                           (pr-str (keys (cache-of xray-frame)))))
                  (is (= false (:live-cell? b))
                      (str "[" label "] BASELINE: and no collector cell, so no "
                           "watch"))
                  (is (= 0 (:reader-edges b))
                      (str "[" label "] BASELINE: and no reader edge, so no "
                           "listener")))
                (mount!)))
            (.then
              (fn [_]
                (let [m (residue)]
                  (reset! !mounted m)
                  (is (pos? (:ref-count m))
                      (str "[" label "] NON-VACUITY: the mount TOOK a sub-cache "
                           "reference — otherwise the release below is a claim "
                           "about nothing. Got: " (pr-str m) (uncaught-note)))
                  (is (= true (:live-cell? m))
                      (str "[" label "] NON-VACUITY: and a live collector cell, "
                           "so there is a watch to lose"))
                  (is (pos? (:reader-edges m))
                      (str "[" label "] NON-VACUITY: and reader edges on it, so "
                           "there are listeners to lose. Got: "
                           (:reader-edges m)))
                  (is (pos? (:cells (:runtime m)))
                      (str "[" label "] NON-VACUITY: and the chrome's boundaries "
                           "lit the collector up. Census: "
                           (pr-str (:runtime m))))
                  (is (pos? (:boundaries (:runtime m)))
                      (str "[" label "] NON-VACUITY: with registrations holding "
                           "reader slots"))
                  (is (pos? (:edges (:runtime m)))
                      (str "[" label "] NON-VACUITY: and dependency edges across "
                           "the tree")))
                (unmount!)
                (rf.fresco.test.runtime/quiesced!)))
            (.then
              (fn [_]
                (let [a (residue)
                      b @!baseline]
                  (is (= 0 (:ref-count a))
                      (str "[" label "] criterion 6 — the unmount released the "
                           "sub-cache reference COMPLETELY. Residue: "
                           (pr-str a) " — frame cache keys: "
                           (pr-str (keys (cache-of xray-frame)))))
                  (is (= false (:live-cell? a))
                      (str "[" label "] criterion 6 — NO RETAINED WATCHES: the "
                           "collector cell for the chrome's read is gone, so no "
                           "`add-watch` on a derived reaction survives. Residue: "
                           (pr-str a)))
                  (is (= 0 (:reader-edges a))
                      (str "[" label "] criterion 6 — NO RETAINED LISTENERS: and "
                           "no reader edge survives either. Residue: "
                           (pr-str a)))
                  (is (= empty-runtime (:runtime a))
                      (str "[" label "] criterion 6 — and the WHOLE collector "
                           "census is back at ZERO: every cell, edge, boundary "
                           "registration and cached read-set entry the chrome "
                           "took. Expected " (pr-str empty-runtime) ", got "
                           (pr-str (:runtime a))))
                  (is (= (:body-nodes b) (:body-nodes a))
                      (str "[" label "] criterion 6 — and the document is back to "
                           "the node count it had before Xray, so no container "
                           "or portal is stranded. Before: " (:body-nodes b)
                           ", after: " (:body-nodes a))))
                ;; ---- the HOST, mid-cycle -------------------------------
                (is (= 1 (host-refs))
                    (str "[" label "] criterion 6 — HOST UNDISTURBED: the "
                         "application's own live subscription still holds "
                         "exactly the ONE reference it took before Xray "
                         "existed. Got: " (host-refs)))
                (is (= 0 @host-ref)
                    (str "[" label "] criterion 6 — HOST UNDISTURBED: and it "
                         "still derefs to its value rather than to a disposed "
                         "reaction's. Got: " (pr-str @host-ref)))
                ;; ---- reopen: the same numbers, not higher ones ----------
                (mount!)))
            (.then
              (fn [_]
                (let [r (residue)
                      m @!mounted
                      shape (fn [c] (select-keys c [:cells :cell-refs
                                                    :boundaries :edges]))]
                  (is (= (:ref-count m) (:ref-count r))
                      (str "[" label "] criterion 6 — reopening takes the SAME "
                           "sub-cache reference count (" (:ref-count m)
                           ") rather than accumulating. Got: " (:ref-count r)))
                  (is (= (:reader-edges m) (:reader-edges r))
                      (str "[" label "] criterion 6 — and the same reader-edge "
                           "count (" (:reader-edges m) ") — an edge left behind "
                           "by the first teardown shows here as growth. Got: "
                           (:reader-edges r)))
                  (is (= (shape (:runtime m)) (shape (:runtime r)))
                      (str "[" label "] criterion 6 — and the whole census is "
                           "identical across the open/close/open cycle. First "
                           "mount: " (pr-str (shape (:runtime m)))
                           " — reopen: " (pr-str (shape (:runtime r)))))
                  ;; Read-set entries are CACHED and reaped on their own
                  ;; horizon, so a reopen inside the window may legitimately
                  ;; reuse or rebuild them. What may never happen is growth.
                  (is (<= (:entries (:runtime r)) (:entries (:runtime m)))
                      (str "[" label "] criterion 6 — and the cached read-set "
                           "entries did not grow. First mount: "
                           (:entries (:runtime m)) " — reopen: "
                           (:entries (:runtime r)))))
                (unmount!)
                (rf.fresco.test.runtime/quiesced!)))
            (.then
              (fn [_]
                (let [a (residue)]
                  (is (= empty-runtime (:runtime a))
                      (str "[" label "] criterion 6 — the SECOND unmount returns "
                           "to zero too, so the release is a property of "
                           "teardown rather than of the first one happening to "
                           "be clean. Expected " (pr-str empty-runtime)
                           ", got " (pr-str (:runtime a))))
                  (is (= 0 (:reader-edges a))
                      (str "[" label "] criterion 6 — with no reader edge "
                           "surviving it either. Residue: " (pr-str a))))
                ;; ---- the HOST, after the whole cycle -------------------
                (is (= 1 (host-refs))
                    (str "[" label "] criterion 6 — HOST UNDISTURBED: after two "
                         "full Xray lifecycles the application's subscription "
                         "still holds exactly ONE reference. Got: " (host-refs)))
                (is (= 0 @host-ref)
                    (str "[" label "] criterion 6 — HOST UNDISTURBED: and still "
                         "derefs to its seeded value. Got: " (pr-str @host-ref)))
                (is (= {:count 0} (app-db-of app-frame))
                    (str "[" label "] criterion 6 — HOST UNDISTURBED: and the "
                         "application's db is exactly what it was seeded with. "
                         "Expected {:count 0}, got "
                         (pr-str (app-db-of app-frame))))))
            (.catch (fail-and-finish (str "[" label "] criterion 6")))
            (.then (fn [_]
                     ;; Defensive: a row that reddened mid-flight may still hold
                     ;; a mounted root, and a live root leaks into the next
                     ;; namespace's baseline.
                     (unmount!)
                     (rf/unsubscribe [::app-count] {:frame app-frame})
                     (done))))))))
