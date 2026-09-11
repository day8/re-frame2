(ns day8.re-frame2-xray.panels.fresco-live-panel-dom-cljs-test
  "The Fresco tab is LIVE under real React — the running panel's own DOM
  (rf2-r98a, merged-PR audit of #7881) — AND, since rf2-k97c.3, the
  boundary witness for this panel's migration onto Fresco.

  ## This file is the panel's `*_fresco_boundary_dom_cljs_test`

  The five migrated siblings each ship one; this panel's sits here under
  an older and better name, because it already mounted this panel into a
  real `reagent.dom.client` root before the migration existed. Extending
  it was cheaper and better than standing a near-duplicate fixture up
  beside it, and it keeps ONE file answering \"what does this panel do
  under a real React commit\".

  The rows below the original one are written against
  `module_view_fresco_boundary_dom_cljs_test`, which is the template the
  remaining panels are migrated against. Four of the epic's six
  behavioural criteria are answerable at a panel's own boundary, and a
  FIFTH is answerable here and nowhere yet:

    1 FIRST DISPLAY                 — W0 phase 1, and W1
    2 UPDATES ON A REAL CHANGE      — W0 phase 3, with W0 phase 2's deaf
                                      control
    3 XRAY'S OWN INTERACTIONS       — W2. The template says in terms that
                                      it has no row for criterion 3
                                      because that panel dispatches
                                      nothing, and that \"the panels that
                                      DO carry interactions are migrated
                                      after this one and bring their own
                                      rows\". This is one of those panels:
                                      the sub-strip is its only dispatch.
    4 FRAME TARGETING               — W1, read off the frame's OWN
                                      sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                      — W0 phase 3 for the PRODUCTION
                                      SINGLETON (the row count is the
                                      assertion), and W4 for a
                                      NON-DEFAULT shell frame, in both
                                      directions. It matters more here
                                      than anywhere: this panel IS the
                                      Fresco census, so a boundary of its
                                      own appearing in its own roster
                                      would be the tool reporting itself
                                      as the application.
    6 CLEAN TEARDOWN                — W3

  THE W-NUMBERS ARE ROW ORDER, NOT CRITERION ORDER. This list read
  `5 → W3, 6 → W4` before rf2-bgol and was wrong in both halves: W3 is the
  teardown row and there was no W4 at all, criterion 5 living inside W0's
  third phase. W4 exists now and carries the half W0 cannot — W0 mounts the
  tool under `:rf/xray`, which is exactly what the defect rf2-bgol fixed
  was blind to.

  ## THE MOUNT IS THE SHELL'S MOUNT, TAKEN FROM THE REGISTRY

  `shell/detail-panel` mounts the active tab as the hiccup head
  `[(:panel tab)]` inside the shell's `[rf/frame-provider {:frame …}]`.
  [[mount-panel!]] does exactly that, and reaches `:panel` THROUGH
  `panel-registry/tab-by-id` rather than naming the var — so the bridge
  the registry actually holds is the thing under test, and a bridge that
  regressed to something the shell cannot mount reddens here rather than
  in a browser.

  ## `flush-render!` IS NOT THE INSTRUMENT ANY MORE, AND THAT IS THE ONE
  ## THING A READER OF THE OLD FILE MUST NOT CARRY OVER

  A Fresco boundary is NOT in Reagent's render queue, so the adapter's
  `:flush-render!` — exactly the right instrument for the `reg-view`
  panels beside this one, and what this file used before rf2-k97c.3 —
  does not commit this panel's update. Used unchanged it reads a DOM
  that has not moved, which presents as the panel being DEAD. Every row
  below therefore drives the WORLD synchronously (a `flushSync` mount,
  or a `dispatch-sync`) and POLLS the committed DOM afterwards; an
  absence is asserted only after [[settle]], so it is a decision and not
  a race.

  ## The claim this row exists to carry, and the one it replaces

  `fresco_cljs_test/the-populated-roster-arrives-on-the-TRACE-TICK-and-not-on-a-cache-clear`
  proves that `:rf.xray.fresco/data` INVALIDATES and RECOMPUTES on a
  `:rf.xray/trace-buffer` tick. That is real and it stays. But it reaches
  the panel by CALLING `fresco/Panel` a second time itself and reading the
  hiccup that comes back: no React root is mounted, nothing commits, and
  no DOM is asserted. A panel wired to a live subscription and a panel
  whose sub happens to recompute when something calls it are not the same
  panel, and only the second was witnessed. The audit of #7881 named the
  gap; this row closes it.

  Here the panel is mounted ONCE, into a real `reagent.dom.client` root,
  wrapped in `[rf/frame-provider {:frame :rf/xray}]` exactly as
  `shell/shell-view` wraps it in production. **Nothing in this file ever
  calls the panel again** — and since rf2-k97c.3 it could not: `Panel` is
  an `rf.fresco/defview`, a React component whose body runs only inside a
  render window. Every later assertion reads
  `container.querySelector…` — the DOM React committed on its own.

  ## W0's three phases, and which one is the control

  1. **Mounted, empty.** The committed DOM carries
     `rf-xray-fresco-empty-mounted`, so the panel really did render its
     empty arm before anything moved.
  2. **A real boundary mounts, and the panel does NOT move.** This is the
     load-bearing control. Fresco's tables are process-global rather than
     part of Xray's app-db, so a mount invalidates nothing the panel's
     read watches — a tab wired to no tick at all would sit on an
     empty roster forever while the application it inspects mounts
     boundaries. A full [[settle]] window is given here, the same one the
     positive phase below is allowed to poll within, so the staleness
     asserted is a read that never invalidated and not a commit that had
     not happened yet.
  3. **One trace tick, and the roster arrives in the DOM.** The tick is
     the collector's own seam — `refresh-trace-rings!` dispatches
     `:rf.xray/sync-trace-buffer` on every coalesced drain (rf2-43koh) —
     and after it the committed DOM carries a boundary ROW naming the
     read the boundary really holds.

  The row testid, not the section wrapper: `rf-xray-fresco-mounted` is
  what `mounted-view` renders in EVERY arm, with the empty note inside it,
  so a selector for the wrapper would match the stale empty roster this
  row exists to catch.

  ## Why the `<section>` identity is asserted

  Phase 3 also pins that the panel's root `<section>` is the SAME DOM node
  it was in phase 1. React reconciled the live tree in place; the roster
  did not arrive because something remounted the panel from scratch, which
  is the one other way a fresh roster could reach the screen and is not
  liveness. W2 pins the same identity across a CLICK, where a remount is
  the specific failure a bridge minting its component per render causes.

  ## Substrate

  The Reagent adapter, because a real React commit is the whole point and
  because Fresco's cell wiring calls `add-watch` on the substrate's
  derived value — under the ratom family that value IS a
  `reagent.ratom/Reaction` (`impl/collector.cljs` §`wire-cell!`), while
  plain-atom's is not `IWatchable`.

  `:ambient-frame nil` is load-bearing: the panel's `rf.fresco/sub` calls
  must resolve `:rf/xray` through the React-context tier the
  `frame-provider` establishes, the way the shipped shell resolves them.
  The fixture's default ambient `:rf/default` scope is still in effect
  during a synchronous `flushSync`, and would shadow that tier at tier 1 —
  the panel would then read `:rf/default`'s app-db and the test would be
  about a frame the shell never renders in.

  ## Test target

  The ns ends in `-dom-cljs-test` so it runs under the `:browser-test`
  build (real DOM / React via Chromium) per
  `implementation/shadow-cljs.edn` — the existing browser lane, which
  already carries `tools/xray/test` on `:source-paths`. No new deck, no
  new build id, no `:dev-http` port. The `:node-test` build's `cljs-test$`
  regex also matches the ns, so it loads under Node too, where the body
  short-circuits via `(browser?)`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as string]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.tool :as rf.fresco.tool]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame ::fresco-live-app)

(def ^:private custom-shell-frame
  "A NON-DEFAULT Xray shell frame. 008 §Parameterized shell frame-id: the
  shell frame is a `:frame-id` opt, not a hard singleton, and a testbed
  mounting N shells side by side gives each cell a distinct one. W4 is
  the row that mounts this panel under such a frame."
  ::fresco-live-custom-shell)

(defn- data-q
  "The panel's evidence read, as the panel issues it inside `frame`.

  The FRAME IS THE QUERY'S ARGUMENT since rf2-bgol — `Panel` reads
  `rf/current-frame-id` in its render and passes it, because the
  self-exclusion filter has to know which frames are the tool's and a sub
  computation runs under no frame scope to read one from. The sub-cache
  is keyed by the query vector itself (`re-frame.subs/cache-key` is
  identity), so this value IS the cache key, and a row keying off the
  bare vector would read a ref-count of 0 against a perfectly healthy
  mount."
  [frame]
  [:rf.xray.fresco/data frame])

(def ^:private view-q
  "The panel's OTHER read — the selected sub-view. Rostered beside
  [[data-q]] because this panel takes TWO reads where the migrated
  siblings take one, and a row asserting only the first would pass on a
  boundary that had lost the second."
  [:rf.xray.fresco/view])

(rf/reg-sub :hlive/left (fn [db _] (:left db)))
(rf/reg-event :hlive/seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because every row below W0 is an `async` one, and
     ;; `cljs.test` refuses a FUNCTION fixture in any namespace carrying
     ;; one — "Async tests require fixtures to be specified as maps.
     ;; Testing aborted." ABORTED is the word that matters: it stops the
     ;; whole page, so every namespace after this one never runs and the
     ;; log still looks plausible. The flag is what makes
     ;; `make-reset-runtime-fixture` hand back the `{:before :after}` map
     ;; form. rf2-k97c.3 turned this file async; before it, the function
     ;; form was correct here.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's tables are process-global `defonce`s that
                      ;; the core fixture knows nothing about; without this a
                      ;; neighbour's boundary is still in the entry cache and
                      ;; the empty arm of phase 1 is not empty.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask.

  rf2-k97c.3 REPLACED the adapter's `:flush-render!` here. That slot
  commits Reagent's own render queue, and a Fresco boundary is not in it,
  so after the migration it returned a DOM that had not moved — which
  would have been reported as the panel being dead. An absence asserted
  immediately after an event is a race; an absence asserted after this is
  a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  ;; The non-default shell W4 mounts under. Seated for every row so the
  ;; fixture has ONE shape — an unused frame costs a record and nothing
  ;; else, and every other row still names `:rf/xray` explicitly.
  (rf/make-frame {:id custom-shell-frame})
  (rf/make-frame {:id app-frame})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:hlive/seed {:left 1}]))
  nil)

(defn- mount-panel!
  "Mount the Fresco tab the way `shell/detail-panel` mounts it: the
  REGISTRY's `:panel` value as a hiccup head, inside a `frame-provider`
  scoping `frame`. Committed synchronously — React 19's `root.render` is
  otherwise async and phase 1 would assert against an empty container.

  Reaching `:panel` through `panel-registry/tab-by-id` rather than naming
  the var is deliberate: after rf2-k97c.3 the registry holds a BRIDGE
  (`rf.fresco/as-component` behind a callable), and it is the bridge the
  shell will actually mount that these rows are about. A bridge that
  regressed to something the shell cannot mount reddens here."
  ([] (mount-panel! :rf/xray))
  ([frame]
   (let [container (.createElement js/document "div")
         root      (rdc/create-root container)
         tab       (panel-registry/tab-by-id :dynamic :fresco)]
     (.appendChild (.-body js/document) container)
     (react-dom/flushSync
       (fn []
         (rdc/render root [rf/frame-provider {:frame frame}
                           [(:panel tab)]])))
     {:container container :root root :tab tab})))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads the sub-cache. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in the row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when the
  entry is absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- mount-boundary!
  "A real Fresco boundary, rendered and committed through the runtime's
  own seam — the same `subscribe` closure React calls. Returns the
  release fn."
  []
  (rf.fresco.impl.collector/render-body app-frame (fn [_] (rf.fresco/sub [:hlive/left]) nil) {})
  (rf.fresco.impl.collector/commit-boundary! (rf.fresco.impl.collector/last-reads) (fn [])))

(defn- tick-trace!
  "One trace-buffer tick, delivered the way the collector delivers it:
  `trace-collector/refresh-trace-rings!` dispatches
  `:rf.xray/sync-trace-buffer` with its snapshot on every coalesced task
  drain, and `:rf.xray/trace-buffer` reads the slot that dispatch writes
  (rf2-43koh).

  The frame arity is W4's: the tick has to land in the app-db the shell
  under test is reading, and that shell is not `:rf/xray`."
  ([] (tick-trace! :rf/xray))
  ([frame]
   (rf/dispatch-sync [:rf.xray/sync-trace-buffer
                      [{:id 1 :op-type :rf.event
                        :operation :rf.event/dispatched :tags {}}]]
                     {:frame frame})))

(defn- q [container sel] (.querySelector container sel))

(defn- boundary-rows
  "The boundary ROWS in the committed DOM.

  `li[…]`, not a bare prefix match: the row's two loss chips render under
  testids that EXTEND the row's own (`…-boundary-<slug>-view-loss-…`), so
  an unqualified prefix selector would count one boundary three times."
  [container]
  (vec (js/Array.from
         (.querySelectorAll container "li[data-testid^=\"rf-xray-fresco-boundary-\"]"))))

;; ===========================================================================
;; W0 — the original row: the mounted panel is LIVE (criteria 1 and 2)
;; ===========================================================================

(deftest w0-the-mounted-panel-picks-up-a-new-boundary-on-the-trace-tick
  (testing "rf2-r98a — a REAL React root holding the Fresco tab re-renders
            itself on a `:rf.xray/trace-buffer` tick and commits the
            populated roster to the DOM. Nothing here calls the panel a second
            time; the roster arrives because the panel is live. Reddens if
            `:rf.xray.fresco/data` stops composing off `:rf.xray/trace-buffer`.

            rf2-k97c.3 kept every claim and changed the INSTRUMENT: the panel
            is a Fresco boundary now, which is not in Reagent's render queue,
            so `flush-render!` no longer commits its update and both phases
            below poll instead."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-panel!)
              release (volatile! nil)
              roster? (fn [] (seq (boundary-rows container)))
              finish  (fn []
                        (when-some [r @release] (r))
                        (teardown! root container)
                        (done))
              ;; Read BEFORE anything moves: phase 3 asserts this is
              ;; still the very same DOM node.
              section (q container "[data-testid=\"rf-xray-fresco\"]")]
          ;; ---- phase 1: mounted, and rendering its empty arm --------------
          (is (some? section)
              "the Fresco panel committed a real DOM root under React — a
               Fresco boundary mounted through Reagent's `:>` from the
               registry entry the shell holds")
          (is (some? (q container "[data-testid=\"rf-xray-fresco-empty-mounted\"]"))
              "the live panel rendered the EMPTY mounted census — nothing is
               mounted yet, so the roster this test drives in cannot already
               be on screen")
          (is (empty? (boundary-rows container))
              "NON-VACUITY: no boundary row is in the DOM before one mounts")

          ;; ---- phase 2: a real boundary mounts, and the panel is deaf ---
          (vreset! release (mount-boundary!))
          (-> (settle)
              (.then
                (fn [_]
                  (is (some? (q container "[data-testid=\"rf-xray-fresco-empty-mounted\"]"))
                      "CONTROL: given a full settling window, a real mount
                       still moves nothing the panel's read watches —
                       Fresco's tables are process-global, not Xray app-db —
                       so the committed DOM shows the empty note yet. Without
                       this the next phase would pass on a panel that had
                       simply never rendered before the tick")

                  ;; ---- phase 3: one tick, and the roster is on screen ----
                  (tick-trace!)
                  (rf.test-support/poll-until roster?
                    {:label "the trace tick commits the roster"})))
              (.then
                (fn [_]
                  (let [rows (boundary-rows container)
                        ;; `some->`, so an empty roster reds the row below as
                        ;; a clean assertion failure rather than throwing on
                        ;; nil and reporting one defect twice.
                        row-text (str (some-> (first rows) .-textContent))]
                    (is (= 1 (count rows))
                        (str "the trace tick re-fired the live panel and ONE "
                             "boundary row committed to the DOM — with no "
                             "cache clear and no second call to the panel "
                             "anywhere in this test.\n"
                             "IT IS ALSO THE SELF-EXCLUSION WITNESS (epic "
                             "criterion 5). The panel is ITSELF a Fresco "
                             "boundary now, and Fresco's census walks the "
                             "collector's process-global entry table with no "
                             "frame filter — so a panel reporting its own "
                             "two `:rf/xray` reads as application evidence "
                             "reads TWO rows here, not one. DOM: "
                             (.-textContent container)))
                    (is (string/includes? row-text "[:hlive/left]")
                        (str "and the row names the read the boundary really "
                             "holds, so the assertion above cannot pass on a "
                             "row projected from nothing. row text: "
                             (pr-str row-text))))
                  (is (nil? (q container "[data-testid=\"rf-xray-fresco-empty-mounted\"]"))
                      "the empty note is gone from the DOM — the roster
                       REPLACED it rather than rendering beside it")
                  (is (identical? section (q container "[data-testid=\"rf-xray-fresco\"]"))
                      "and it is the SAME <section> node — React reconciled
                       the live tree in place, so the roster did not arrive by
                       the panel being remounted from scratch, which would not
                       be liveness")))
              (.catch (fn [e]
                        (is false (str "W0 poll timed out: " (.-message e)
                                       " DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_] (finish)))))))))

;; ===========================================================================
;; W1 — first display, and the reads land in the frame the tree named
;; ===========================================================================

(deftest w1-panel-paints-and-its-reads-land-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated Fresco tab commits real DOM through the
            registry entry the shell mounts, and its two `rf.fresco/sub` reads
            resolve against the frame the enclosing `frame-provider` named
            rather than the ambient one. Epic criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half below is
            ;; measured with an instrument demonstrably able to see an entry
            ;; in that frame's cache.
            _probe (rf/subscribe [:hlive/left] {:frame app-frame})
            {:keys [container root]} (mount-panel! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-fresco\"]"))
              "the panel committed a real DOM root under React")
          (is (some? (q container "[data-testid=\"rf-xray-fresco-sub-strip\"]"))
              "and the sub-strip rendered, so the body ran rather than
               short-circuiting to nil")

          ;; ---- criterion 4: the reads are where the tree said ------------
          (is (pos? (ref-count-of :rf/xray (data-q :rf/xray)))
              (str "the panel's evidence read holds a reference in :rf/xray's "
                   "sub-cache — the frame the enclosing frame-provider named. "
                   "Cache keys: " (pr-str (keys (cache-of :rf/xray)))))
          (is (pos? (ref-count-of :rf/xray view-q))
              "and so does its sub-view read — BOTH reads are targeted, not
               just the one a single-read panel would have")
          (is (zero? (ref-count-of app-frame (data-q :rf/xray)))
              "and NOT in the application frame's — a foreign root that
               inherited the ambient scope instead of reading React context
               would put it here")
          (is (pos? (ref-count-of app-frame [:hlive/left]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zero
               above is an absence and not a broken reader")
          (finally
            ;; Release the imperative probe explicitly: an imperative
            ;; subscriber must not rely on a view's reaction lifecycle.
            (rf/unsubscribe [:hlive/left] {:frame app-frame})
            (teardown! root container)))))))

;; ===========================================================================
;; W2 — Xray's OWN interaction: the sub-strip click (criterion 3)
;; ===========================================================================

(deftest w2-the-sub-strip-click-switches-the-view-through-the-boundary
  (testing "rf2-k97c.3 — clicking a sub-strip tab dispatches through the
            FRAME THE BOUNDARY CARRIES and the panel commits the other view.
            Epic criterion 3, which the migrated siblings have no row for:
            `module_view_fresco_boundary_dom_cljs_test` says in terms that it
            omits criterion 3 because that panel dispatches nothing, and that
            the panels which DO carry interactions bring their own rows. This
            is one of them — the sub-strip is this tab's only dispatch.

            WHAT WOULD BREAK IT, precisely. `reg-view` LEXICALLY INJECTED a
            frame-bound `dispatch`; a `defview` binds no name inside a body,
            so the panel builds one from `rf/current-frame-id`. A bare global
            `rf/dispatch` in its place fires after render unwinds, when the
            ambient frame is gone, and lands on `:rf/default` — where
            `:rf.xray.fresco/set-view` writes a `:fresco-view` nobody reads,
            and the DOM below never changes."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-panel!)
              section  (q container "[data-testid=\"rf-xray-fresco\"]")
              intents? (fn [] (q container "[data-testid=\"rf-xray-fresco-intents\"]"))
              finish   (fn [] (teardown! root container) (done))]
          (is (some? (q container "[data-testid=\"rf-xray-fresco-mounted\"]"))
              "the panel opens on the Mounted view — the default
               `normalise-sub-mode` answers for an unset slot")
          (is (nil? (intents?))
              "NON-VACUITY: the Intents view is NOT on screen before the click")
          (let [btn (q container "[data-testid=\"rf-xray-fresco-sub-intents\"]")]
            (is (some? btn)
                "the Intents sub-tab button is in the committed DOM — a plain
                 fn at an `:on-click` prop crosses Fresco's codec untouched, so
                 the control itself survived the migration")
            (.click btn)
            (-> (rf.test-support/poll-until intents?
                  {:label "the click committed the Intents view"})
                (.then
                  (fn [_]
                    (is (some? (intents?))
                        "the click switched the sub-view and the panel committed
                         it — so the dispatch reached :rf/xray, the frame the
                         boundary carries, and not :rf/default")
                    (is (nil? (q container "[data-testid=\"rf-xray-fresco-mounted\"]"))
                        "and the Mounted view is gone — the views REPLACED each
                         other rather than both rendering")
                    (is (identical? section (q container "[data-testid=\"rf-xray-fresco\"]"))
                        "and the root <section> is the SAME node: React
                         reconciled in place. A bridge minting its component
                         type per render would remount the panel here instead,
                         which is the specific failure `Panel-component` being
                         a top-level def prevents")))
                (.catch (fn [e]
                          (is false (str "W2 poll timed out — the click did not "
                                         "reach the panel's frame: "
                                         (.-message e)))
                          nil))
                (.then (fn [_] (finish))))))))))

;; ===========================================================================
;; W3 — clean teardown: the reads are released, and reopening is not growth
;; ===========================================================================

(defn- released?
  "Both of the panel's reads are fully released from `:rf/xray`'s cache."
  []
  (and (zero? (ref-count-of :rf/xray (data-q :rf/xray)))
       (zero? (ref-count-of :rf/xray view-q))))

(deftest w3-unmount-releases-the-reads-and-reopen-does-not-grow-them
  (testing "rf2-k97c.3 — unmounting the panel releases BOTH subscription
            references completely, and mounting it again returns to the SAME
            counts rather than higher ones. Epic criterion 6, and the number
            the spike caught the rejected design on: with a four-call interop
            binding the `:rf/xray` ref-count climbed across renders and never
            fell on unmount.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, so this row polls rather
            than reading once: `impl.collector`'s cell reapers give a cell
            whose last reader unmounts one macrotask of grace, so that a keyed
            reorder which unmounts and remounts within a turn reuses the
            reaction instead of rebuilding it. A synchronous assertion would
            report a LEAK against a collector behaving exactly as documented."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        ;; Polled rather than asserted: a neighbouring row's teardown grace
        ;; may still be in flight when this one begins.
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (let [{:keys [container root]} (mount-panel!)
                      mounted-data (ref-count-of :rf/xray (data-q :rf/xray))
                      mounted-view (ref-count-of :rf/xray view-q)]
                  (is (pos? mounted-data)
                      "the mount took a reference for the evidence read —
                       otherwise the release below is vacuous")
                  (is (pos? mounted-view)
                      "and one for the sub-view read")
                  (teardown! root container)
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released both reads"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released BOTH completely, within "
                                   "the collector's grace macrotask. Cache: "
                                   (pr-str (keys (cache-of :rf/xray)))))
                          (let [{c2 :container r2 :root} (mount-panel!)
                                again-data (ref-count-of :rf/xray (data-q :rf/xray))
                                again-view (ref-count-of :rf/xray view-q)]
                            (is (= [mounted-data mounted-view]
                                   [again-data again-view])
                                (str "reopening returns to the SAME reference "
                                     "counts " (pr-str [mounted-data mounted-view])
                                     " rather than accumulating — accumulation "
                                     "across open/close cycles is the signature "
                                     "of a release the substrate's own reaction "
                                     "lifecycle cannot see. Got: "
                                     (pr-str [again-data again-view])))
                            (teardown! r2 c2)
                            (rf.test-support/poll-until released?
                              {:label "the second unmount released them too"}))))))))
            (.then (fn [_] (is (released?)
                               "and the second unmount releases them too")))
            (.catch (fn [e] (is false (str "W3 poll timed out: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; W4 — a NON-DEFAULT shell does not report ITSELF (criterion 5)
;; ===========================================================================
;;
;; rf2-bgol. The self-exclusion filter landed asking `(= :rf/xray frame)`,
;; which is right for the production singleton and blind to every other
;; shell the embedding contract permits: 008 §Parameterized shell frame-id
;; makes the shell frame a `:frame-id` OPT, `mount/ensure-xray-frame!` takes
;; one, and a testbed mounting N shells side by side gives each cell a
;; distinct id. Under such a shell this tab's own boundary, its two reads
;; and its explanation were seated in a frame the filter did not know, rode
;; through all four rosters, and the tool was presented as application
;; evidence in Mounted, Reads, Why, Advisor and Causal.
;;
;; THE WITNESSES THAT EXISTED COULD NOT SEE IT, and that is the whole reason
;; this row exists rather than an assertion added to one of them: every one
;; of them mounts the tool under `:rf/xray`, so every one passes against the
;; literal-only filter.
;;
;; IT IS A REAL REGISTERED PANEL UNDER A REAL REACT ROOT, not `panel-tree`
;; driven with hand-made values — the `:panel` the L4 registry holds,
;; mounted inside a `frame-provider` naming the custom shell, exactly as
;; `shell/detail-panel` mounts it. That is what makes the frame reach the
;; subscription the way production reaches it: `Panel` reads
;; `rf/current-frame-id` in its own render and passes it as the query's
;; argument.

(deftest w4-a-non-default-shell-omits-its-OWN-evidence-and-keeps-the-apps
  (testing "rf2-bgol — epic criterion 5 under a shell frame that is not
            `:rf/xray`. Both directions: the producer's door still carries
            the shell's own boundary, and what the panel COMMITTED does not,
            while the application's boundary is on the page in both."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (let [release   (mount-boundary!)
              {:keys [container root]} (mount-panel! custom-shell-frame)
              finish    (fn []
                          (release)
                          (teardown! root container)
                          (done))
              roster?   (fn [] (seq (boundary-rows container)))
              door      (fn [] (mapv :frame
                                     (:boundaries
                                       (rf.fresco.tool/read-mounted-boundaries))))]
          ;; The tick is what invalidates the panel's read; without it the
          ;; roster never arrives and every assertion below would be about
          ;; an empty page. W0 is what proves the tick is the mechanism.
          (tick-trace! custom-shell-frame)
          (-> (rf.test-support/poll-until roster?
                {:label "the roster reached the DOM under the custom shell"})
              (.then
                (fn [_]
                  (let [frames (door)
                        text   (.-textContent container)]
                    (is (some #{custom-shell-frame} frames)
                        (str "NON-VACUITY, AND THE DOOR HALF: the runtime "
                             "really did seat this panel's own boundary in "
                             "the custom shell frame, and the producer's own "
                             "answer still carries it — so the absence below "
                             "is a filter and not a runtime that never "
                             "recorded the row. Door frames: " (pr-str frames)))
                    (is (some #{app-frame} frames)
                        "NON-VACUITY: and the application's boundary is in the
                         door's answer too")

                    (is (not (string/includes? text (str custom-shell-frame)))
                        (str "THE COMMITTED PAGE DOES NOT NAME THE SHELL'S OWN "
                             "FRAME. This is the assertion that reddens "
                             "against the literal-only filter — under it the "
                             "Mounted view committed a second row reading "
                             "`…panels.fresco/Panel · frame "
                             (str custom-shell-frame) " · 2 reads`. DOM: "
                             text))
                    (is (not (string/includes? text "panels.fresco/Panel"))
                        (str "and it does not name the panel's own VIEW "
                             "either — the row is gone rather than merely "
                             "printing a different frame. DOM: " text))
                    (is (string/includes? text (str "frame " app-frame))
                        (str "and the APPLICATION's boundary is on the page, "
                             "so the drop is the tool's own frame and not a "
                             "roster that emptied. DOM: " text)))))
              (.catch (fn [e]
                        (is false (str "W4 poll timed out: " (.-message e)
                                       " DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_] (finish)))))))))
