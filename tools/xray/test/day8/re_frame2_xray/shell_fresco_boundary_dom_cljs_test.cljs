(ns day8.re-frame2-xray.shell-fresco-boundary-dom-cljs-test
  "Real-DOM witnesses for Xray's DYNAMIC chrome as a Fresco tree
  (rf2-k97c.3).

  ## Why a browser row at all — the node lane cannot see this

  Xray's node lane drives the shell's pure `*-tree` fns through
  `test-helpers.dynamic-shell-tree`, which passes `identity` where the
  boundaries pass `reagent.core/as-element`. Under `npm run test:cljs`
  wrapping and NOT wrapping are therefore the same value, BY
  CONSTRUCTION — so a green node lane says nothing about whether the
  chrome paints. Only a committed DOM can answer that, and this file is
  the Dynamic sibling of
  `static/shell_fresco_boundary_dom_cljs_test`, which asks the same two
  questions of the Static surface.

  ## The mount is the PRODUCTION mount, and since rf2-k97c.3 that means
  ## XRAY'S OWN ROOT

  `mount.cljs` no longer calls the installed adapter's `:render` at all.
  It owns a Fresco client root and renders `[rf.fresco/frame-provider
  {:frame …} [shell/ShellView {…}]]` through it, which is the epic's
  coupling (1) severed — and it is exactly the two-level form
  [[mount-shell!]] builds below, so these rows drive the shipped path
  rather than a reproduction of it. The private `as-component` bridge the
  shell used to reach the Fresco tree through is gone: there is no longer
  a crossing to bridge. [[mount-shell!]] records why the outer provider is
  not decoration, and why the re-point is itself this PR's evidence.

  Nothing below ever calls a view a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed
  on its own.

  ## The four rows

  W1 asks whether the chrome PAINTS and W2 whether its reads are LIVE.
  W3 and W4 close the two claims those leave untouched (rf2-fxwj): W3
  presses a real L3 tab button, so the dispatcher the boundary CAPTURES
  is exercised rather than bypassed by the test's own `dispatch-sync`,
  and holds a second live frame as the control that makes it a routing
  claim; W4 MEASURES the teardown W1 and W2 merely call, against the
  frame's sub-cache reference, the collector's cell watch, its reader
  edges and the whole collector census — and reopens once, because a
  release that releases nothing still reads clean on a first mount.

  ## Substrate: the Reagent adapter, deliberately

  A ratom-family adapter, which is the family Xray already supports —
  because the claim being made is that the chrome is INDIFFERENT to it.
  The whole point of the migration is that the chrome no longer paints
  through the adapter's renderer; mounting it under one and watching it
  work is what says so.

  ## Node-lane behaviour

  This ns matches the `:browser-test` build's regex and also loads under
  `:node-test`, where every row short-circuits through [[browser?]] and
  reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels :as panels]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private shell-frame
  "The frame this suite's shell instance owns. NOT `:rf/xray`: the
  production singleton's frame is trace-disabled and shared with every
  other suite on the page, and `shell-view`'s `:frame-id` opt exists
  precisely so N instances can be isolated (rf2-lnluk). Naming a private
  one is what makes W2's negative half mean something."
  ::shell)

(def ^:private other-frame
  "A second live frame W2 dispatches into as its DEAF LEVER — the
  negative control. A write here must move nothing in the shell above."
  ::other)

(def ^:private embed-app-frame
  "W5's INSPECTED APPLICATION frame — the one a Story host owns and paints
  its own tree under. Its epoch ring is what must stay untouched."
  ::embed-app)

(def ^:private embed-xray-frame
  "W5's embed frame — the `:frame` opt `panels/mount-shell!` forwards as
  the shell's own `:frame-id`. Distinct from [[shell-frame]] so W5 cannot
  ride a frame another row seated."
  ::embed-xray)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about;
                      ;; a neighbour's boundary left in the entry cache
                      ;; would make these rows read a residue that is not
                      ;; this shell's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than
;; an omission. A Fresco boundary is NOT in Reagent's render queue — its
;; update is scheduled by the collector through React — so draining
;; Reagent's queue commits nothing of these boundaries', and a row written
;; that way reads a DOM that has not moved and reports a live shell as
;; dead. Mount is committed with `flushSync` (React's own door) and
;; everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after the world
  moves is a race, and an absence asserted after this is a decision."
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
  value rather than on the fact that polling ended."
  ([pred] (poll-until pred 2000))
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

(defn- setup!
  "Register Xray's handlers — which is what registers every Dynamic
  panel's L4 tab entry, so the L3 bar and the L4 mount both read the real
  registry — and make the frames.

  `:rf/xray` is made as well as the two this suite names. It is
  `shell/default-frame-id`, the production singleton, and several Xray
  registrations and panel bodies reach it by that name regardless of
  which frame an instance is mounted at; a missing frame is a loud
  re-frame refusal, and one raised inside a React render surfaces only as
  'an error occurred in <shell-view>' with the message nowhere on
  screen."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id shell/default-frame-id})
  (rf/make-frame {:id shell-frame})
  (rf/make-frame {:id other-frame})
  nil)

(defn- mount-shell!
  "Mount the shell the way `mount.cljs` mounts it SINCE THE ROOT SWAP —
  through XRAY'S OWN Fresco root, with the OUTER `frame-provider`
  included:

      (h/render! handle [h/frame-provider {:frame …} [shell/ShellView {…}]]
                 container)

  ## THIS RE-POINT IS THE PR'S EVIDENCE, NOT A PORT OF IT

  It used to read `(rdc/create-root …)` and `(rdc/render root [rf/frame-
  provider … [shell/shell-view …]])` — a REAGENT root rendering a
  `reg-view`, which is what `mount.cljs` did by calling the installed
  adapter's `:render`. Under the old door that call was the only one that
  worked and this one could not: `shell/ShellView` did not exist, and a
  boundary head is not a legal Reagent hiccup head. Under the new door it
  is the other way round. So the same four rows below, unchanged in what
  they assert, are RED against trunk and GREEN here — which is the epic's
  coupling (1) being severed, measured rather than described.

  THE WRAPPER IS STILL LOAD-BEARING, for a NEW reason with the OPPOSITE
  failure mode. It used to be about a `reg-view`'s render trace; a Fresco
  boundary emits none. What it does now is give `ShellView`'s two ambient
  `rf.fresco/sub` reads their frame — drop it and the shell refuses with
  `:rf.error/no-frame-context` and nothing commits. That is sabotage plant
  1, and it is the direct control on the epic's coupling (2).

  The provider frame and the shell's `:frame-id` are the same value here,
  as they are in production.

  NO `flushSync` OF OUR OWN: `h/render!` renders inside one already
  (`impl/mount.cljs`'s `root!`), and nesting `flushSync` is a React
  warning. The commit is synchronous either way, so the first assertion
  reads a populated container."
  [frame]
  (let [container (.createElement js/document "div")
        handle    (rf.fresco/client-root)]
    (.appendChild (.-body js/document) container)
    (rf.fresco/render! handle
                       [rf.fresco/frame-provider {:frame frame}
                        [shell/ShellView {:frame-id frame}]]
                       container)
    {:container container :root handle}))

(defn- teardown!
  "Release the root through Fresco's own door, so React's cleanup effects
  — which is where the collector releases a boundary's reads — have RUN by
  the time the next line reads anything.

  `h/unmount!` unmounts inside `flushSync` itself, so no bare `.unmount`
  and no wrapper of ours. And it is `unmount!` rather than `release!`
  DELIBERATELY: `release!` ends with `reset-runtime!`, which empties the
  collector's tables, so W4's residue assertions would read ZERO whatever
  the teardown did and could never turn red."
  [root container]
  (rf.fresco/unmount! root)
  (.remove container))

;; ---- the diagnostic ------------------------------------------------------
;;
;; A re-frame refusal raised inside a React render does NOT reach a
;; `try/catch` around `flushSync`: React 19 catches it, reports "an error
;; occurred in <shell-view>" to the console, and re-raises it as an
;; UNCAUGHT window error. Its `ex-message` and `ex-data` — the whole of
;; what re-frame refuses WITH — then appear nowhere a row can read, and
;; every assertion below reddens on a nil testid saying only that the
;; chrome is absent. Capturing the error object is what turns eight
;; uninformative failures into one that names the refusal.

(defonce ^:private !last-uncaught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(defn- uncaught-note
  "A suffix naming the last uncaught error, for a row whose subject is
  missing. Empty when nothing was thrown — in which case the absence is
  the finding rather than a hidden exception."
  []
  (if-some [e (when error-capture-armed? @!last-uncaught)]
    (str " — an uncaught error was raised during render: "
         (:rf.error/id (ex-data e) (ex-message e))
         " · at: "
         ;; The stack's HEAD is the message, which for a re-frame refusal
         ;; is a paragraph. The call frames are at the TAIL.
         (let [s (str (.-stack ^js e))]
           (->> (str/split-lines s)
                (remove #(str/blank? %))
                (filter #(str/includes? % "at "))
                (take 14)
                (str/join " | "))))
    ""))

(defn- q [container sel] (.querySelector container sel))

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(defn- detail-panel-node
  "The committed L4 panel `<div>`, whatever tab it is showing. Its testid
  carries the tab id, which is what W2 watches."
  [container]
  (q container "[data-testid^=\"rf-xray-detail-panel-\"][role=\"tabpanel\"]"))

;; ===========================================================================
;; W1 — first display of the WHOLE Dynamic chrome
;; ===========================================================================

(deftest w1-dynamic-chrome-paints-every-layer-through-the-bridge
  (testing "rf2-k97c.3 — the migrated Dynamic chrome commits all four
            layers plus the events ribbon, through the ONE private
            `as-component` bridge `shell-view` mounts. Epic criterion 1.

            THIS ROW CARRIES WHAT THE NODE LANE GAVE UP. Every chrome
            row in `shell_cljs_test` now drives the shell's pure `*-tree`
            fns rather than the views, because a boundary's body only
            runs inside a React render window. Those rows are evidence
            about COMPOSITION; this one is the only evidence that the
            composition reaches a screen.

            The theme toggle is asserted separately from the ribbon that
            contains it because it is its OWN boundary — a nested
            boundary head inside a boundary body, which is the shape the
            rest of the chrome's regions take under
            `dynamic-chrome`."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-shell! shell-frame)]
          (-> (poll-until #(testid container "rf-xray-ribbon"))
              (.then
                (fn [_]
                  (is (some? (testid container "rf-xray-shell"))
                      (str "the shell envelope commits" (uncaught-note)))
                  (is (some? (testid container "rf-xray-ribbon"))
                      "L1 chrome ribbon commits")
                  (is (some? (testid container "rf-xray-ribbon-nav"))
                      "the ribbon's nav cluster commits — a helper CALLED
                       rather than headed still reaches the DOM")
                  (is (some? (testid container "rf-xray-theme-toggle"))
                      "the theme toggle commits — a boundary head inside
                       a boundary body")
                  (is (some? (testid container "rf-xray-events-ribbon"))
                      "L1.5 events ribbon commits")
                  (is (some? (testid container "rf-xray-event-list"))
                      "L2 event list commits — the chrome's Reagent
                       island, crossed with `as-element`")
                  (is (some? (testid container "rf-xray-tab-bar"))
                      "L3 tab bar commits")
                  (is (some? (detail-panel-node container))
                      "L4 detail panel commits")
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W2 — a real dependency change repaints, and a write to another frame
;;      does not
;; ===========================================================================

(deftest w2-chrome-repaints-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the chrome's reads are LIVE: writing
            `:rf.xray/select-tab` into the frame the tree named moves the
            committed L4 panel, and writing the same event into a
            DIFFERENT live frame moves nothing. Epic criteria 3 and 4 —
            observation, and frame context.

            This is the coupling a first-paint smoke test cannot see: a
            tree that painted once and never re-ran would pass W1 and
            fail here. The negative half is what makes it a frame claim
            rather than merely a reactivity one — `shell-view` is mounted
            at a PRIVATE frame, so a boundary that resolved its read
            ambiently would follow the wrong write."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-shell! shell-frame)
              tab-testid (fn [] (some-> (detail-panel-node container)
                                        (.getAttribute "data-testid")))
              !before    (atom nil)
              step-1     (fn [before]
                           (reset! !before before)
                           (is (some? before)
                               "CONTROL — the L4 panel is committed before the lever")
                           ;; The DEAF LEVER: the same event, into a live
                           ;; frame this tree never named.
                           (rf/dispatch-sync [:rf.xray/select-tab :trace]
                                             {:frame other-frame})
                           (settle))
              step-2     (fn [_]
                           (is (= @!before (tab-testid))
                               (str "a write to another frame moves nothing. "
                                    "Was " (pr-str @!before)
                                    ", now " (pr-str (tab-testid))))
                           (rf/dispatch-sync [:rf.xray/select-tab :trace]
                                             {:frame shell-frame})
                           (poll-until (fn [] (not= @!before (tab-testid)))))
              step-3     (fn [_]
                           (is (= "rf-xray-detail-panel-trace" (tab-testid))
                               (str "the L4 panel followed the write into its "
                                    "OWN frame. Got: " (pr-str (tab-testid))))
                           (teardown! root container)
                           (done))]
          (-> (poll-until tab-testid)
              (.then step-1)
              (.then step-2)
              (.then step-3)))))))

;; ===========================================================================
;; Shared by W3 and W4 — the one read both rows key off, and the controls
;; ===========================================================================

(def ^:private selected-tab-q
  "The read the L3 tab bar and the L4 detail panel BOTH make
  (`shell.cljs`'s `tab-bar` and `detail-panel` boundaries), and the one
  a tab click invalidates. Named once because it is three things at
  once: W3's state assertion, W4's sub-cache key, and — paired with a
  frame — the collector's own cell address."
  [:rf.xray/selected-tab])

(def ^:private clicked-tab
  "The tab W3 presses. `:trace` is a registered Dynamic tab that is NOT
  the default, so the L4 panel moving to it is the click's doing."
  :trace)

(def ^:private deaf-frame-tab
  "The DISTINCT value [[other-frame]] is held at across W3. Not the
  default and not [[clicked-tab]], so `unchanged` names a specific tab
  rather than the value every frame answers with anyway."
  :machines)

(defn- panel-testid-for [tab-id]
  (str "rf-xray-detail-panel-" (name tab-id)))

(defn- tab-node
  "The committed `<button role=\"tab\">` for one Dynamic tab id, or nil.
  NOTE the two ids on that element are different strings: the testid is
  `rf-xray-tab-<id>` and the DOM `id` is `rf-xray-tab-button-<id>`."
  [container tab-id]
  (testid container (str "rf-xray-tab-" (name tab-id))))

(defn- click!
  "Click a committed node, or FAIL THIS ROW rather than aborting the lane.

  A raw `.click` on nil throws a `TypeError` out of the async block, and
  `cljs.test/run-block` has no try/catch — under a plant that emptied the
  chrome that took the WHOLE browser lane down with no cljs.test summary,
  and every namespace scheduled after it never ran. A row whose subject
  has vanished should redden; it must not silence its neighbours."
  [node label]
  (if (some? node)
    (do (.click node) true)
    (do (is false (str "cannot click " label ": it is not in the committed "
                       "DOM, so this row's subject is already gone"
                       (uncaught-note)))
        false)))

(defn- selected-tab-in
  "One frame's current `:rf.xray/selected-tab`, read WITHOUT taking a
  reference. `subscribe-once` subscribes, derefs and unsubscribes, so a
  row may read as many frames as it likes without moving the numbers W4
  measures."
  [frame-id]
  (rf/subscribe-once selected-tab-q {:frame frame-id}))

;; ===========================================================================
;; W3 — a REAL CLICK on the migrated L3 tab bar, through the dispatcher the
;;      boundary CAPTURED, into the frame the tree named
;; ===========================================================================
;;
;; W2 above moves the world with `rf/dispatch-sync` and watches the chrome
;; follow. That is a reactivity claim and it is deliberately silent about the
;; boundary's OTHER half: `tab-bar` does not merely READ through the
;; collector, it CAPTURES a dispatcher with `(:dispatch (rf/capture-frame))`
;; and threads it into every tab button as `:dispatch-fn`. No row above ever
;; makes it do so, which is the gap this one closes — the boundary's whole job
;; is to capture a dispatcher, and nothing was making it capture one.
;;
;; THE NEGATIVE HALF IS WHAT MAKES IT A FRAME CLAIM RATHER THAN A CLICK TEST.
;; `tab-button` ends `(or dispatch-fn rf/dispatch)`, a defensive fallback for
;; node-lane callers that build the button directly. So a capture lost
;; anywhere between the boundary body and the button's props map does not
;; crash — it DEGRADES SILENTLY to the ambient dispatcher, which resolves a
;; frame that is not this shell's. A positive-only row passes on a dispatcher
;; that routes everywhere and on one that routes somewhere else; a second live
;; frame held at a distinct value is the only thing that separates those from
;; a dispatcher that routes HERE.
;;
;; THE THEME TOGGLE CANNOT CARRY THIS ROW, and that is worth recording so the
;; next author does not spend the afternoon finding out. `ribbon-theme-toggle`
;; is the tidier boundary — its own head, one read, one dispatch — but its read
;; is `[:rf.xray/setting :theme nil]`, and `settings/subs.cljs` falls that path
;; through to `config/get-setting`, a PROCESS-GLOBAL atom rather than any
;; frame's `app-db`. Two shells at two frames see one theme, so the toggle can
;; witness that a click reached a handler and nothing whatever about WHERE it
;; landed.

(deftest w3-a-real-tab-click-routes-through-the-captured-dispatcher
  (testing "rf2-fxwj — pressing a real migrated L3 tab button moves the L4
            panel in the frame the enclosing `frame-provider` NAMED, and
            leaves a second live frame exactly where it was. The event
            travels the shipped path end to end: React's own click, the
            `:on-click` closure, the `:dispatch-fn` `tab-bar` captured, the
            handler, the invalidated read, the recommitted boundary.

            THIS ROW IS NOT A SECOND W2. W2's lever is the test's own
            `dispatch-sync`, which supplies the frame from the OUTSIDE; here
            the frame comes from the boundary's capture, and nothing in the
            row names it at the moment of dispatch. That difference is the
            whole subject."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        ;; The deaf frame is loaded BEFORE the mount, so the control below
        ;; compares against a value a misrouted write would have to
        ;; overwrite. Held at the `:epoch` default instead, a dispatcher that
        ;; had fallen through to an ambient frame and written `:epoch` there
        ;; would be indistinguishable from one that never wrote at all.
        (rf/dispatch-sync [:rf.xray/select-tab deaf-frame-tab]
                          {:frame other-frame})
        (let [{:keys [container root]} (mount-shell! shell-frame)
              tab-testid (fn [] (some-> (detail-panel-node container)
                                        (.getAttribute "data-testid")))
              aria-of    (fn [] (some-> (tab-node container clicked-tab)
                                        (.getAttribute "aria-selected")))]
          (-> (poll-until tab-testid)
              (.then
                (fn [_]
                  (let [btn (tab-node container clicked-tab)]
                    (is (some? btn)
                        (str "PRECONDITION: the L3 `" (name clicked-tab)
                             "` tab button is committed, so there is a real "
                             "control to press" (uncaught-note)))
                    (is (= "false" (aria-of))
                        (str "PRECONDITION: and it is not already the selected "
                             "tab, so the flip below is the click's doing. Got: "
                             (pr-str (aria-of))))
                    (is (= (panel-testid-for :epoch) (tab-testid))
                        (str "PRECONDITION: the L4 panel is showing the default "
                             "tab. Got: " (pr-str (tab-testid))))
                    (is (= :epoch (selected-tab-in shell-frame))
                        "PRECONDITION: and the shell's own frame holds the default")
                    (is (= deaf-frame-tab (selected-tab-in other-frame))
                        (str "PRECONDITION: while the second live frame holds a "
                             "DISTINCT value, so `unchanged` below is a claim "
                             "about a real value. Got: "
                             (pr-str (selected-tab-in other-frame))))
                    ;; ---- the act: a REAL browser click on a REAL node ----
                    (if (click! btn (str "the L3 " (name clicked-tab) " tab button"))
                      (poll-until #(= (panel-testid-for clicked-tab) (tab-testid)))
                      (js/Promise.resolve nil)))))
              (.then
                (fn [_]
                  (is (= (panel-testid-for clicked-tab) (tab-testid))
                      (str "the click reached the handler, the dispatcher "
                           "`tab-bar` captured delivered the event, the read "
                           "was invalidated and the L4 boundary recommitted "
                           "on the new tab — the whole round trip, in a "
                           "browser. Got: " (pr-str (tab-testid))
                           (uncaught-note)))
                  (is (= "true" (aria-of))
                      (str "and the pressed button now reports itself selected, "
                           "so the L3 boundary recommitted too rather than only "
                           "the L4 one. Got: " (pr-str (aria-of))))
                  (is (= clicked-tab (selected-tab-in shell-frame))
                      (str "the write landed in the frame the tree named. Got: "
                           (pr-str (selected-tab-in shell-frame))))
                  (is (= deaf-frame-tab (selected-tab-in other-frame))
                      (str "CROSS-FRAME CONTROL: and NOT in the second live "
                           "frame, which still holds "
                           (pr-str deaf-frame-tab) ". A dispatcher that had "
                           "lost its capture and fallen back to the ambient "
                           "one would write here, or nowhere. Got: "
                           (pr-str (selected-tab-in other-frame))))))
              (.catch (fn [e]
                        (is false (str "W3 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)
                                       (uncaught-note)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W4 — unmount returns the chrome's reads to BASELINE, watches and listener
;;      edges included, and reopening does not accumulate
;; ===========================================================================

(def ^:private shell-cell-key
  "The collector's own address for the shell's headline read under this
  suite's frame. `[frame-kw query-v]` — finer than the frame's sub-cache
  key, and what `!cells` is keyed by (`collector/read-key!`)."
  [shell-frame selected-tab-q])

(def ^:private empty-runtime
  "What `test.runtime/residue` reads when the collector holds nothing.
  Spelled out rather than captured, so W4's baseline assertion is a claim
  about ZERO rather than about whatever happened to be standing — a
  baseline taken from a dirty runtime would let a leak hide inside it."
  {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0})

(defn- cache-of
  "The frame's live sub-cache map. Not `some->`-guarded: a nil here means
  the frame is not live, which is a defect in this row's own setup and
  should throw rather than read as an empty cache."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0 when absent."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- chrome-residue
  "Everything that must return to baseline once the chrome is gone, as one
  map, so a failure message names WHICH instrument still sees something.

  FOUR INSTRUMENTS ANSWERING DIFFERENT QUESTIONS, which is the point —
  `teardown!` being CALLED is not one of them.

  - `:ref-count` is the REFERENCE half: what the frame's own sub-cache
    still holds for the shell's headline read.
  - `:live-cell?` is the WATCH half. A live collector cell holds
    `add-watch` on its derived reaction for as long as it exists, so this
    is `no retained watches` read off the runtime rather than inferred.
  - `:reader-edges` is the LISTENER half: one reader slot per boundary
    registration reading that cell — the fused reference AND dependency
    edge.
  - `:runtime` is the WHOLE collector census, which is what makes this a
    claim about the CHROME rather than about one query. The Dynamic chrome
    is seven boundaries reading a dozen-odd subs between them; enumerating
    them here would be the per-control matrix this work is explicitly not,
    and would go stale the first time a region gains a read. The census
    cannot go stale and cannot miss one.

  A release that dropped the reference and left the cell wired answers
  clean to the first and dirty to the other three."
  []
  {:ref-count    (ref-count-of shell-frame selected-tab-q)
   :live-cell?   (some? (rf.fresco.test.runtime/cell-reaction shell-cell-key))
   :reader-edges (count (rf.fresco.test.runtime/cell-readers shell-cell-key))
   :runtime      (rf.fresco.test.runtime/residue)})

(deftest w4-unmount-returns-the-chrome-to-baseline-and-reopen-does-not-accumulate
  (testing "rf2-fxwj — unmounting the Dynamic chrome releases everything it
            took: the frame's sub-cache reference, the collector cell and
            its watch, every reader edge, and the whole collector census
            back to the zero it started from. Then a REOPEN takes the same
            numbers rather than higher ones.

            THE REOPEN IS THE HALF THAT CATCHES A RELEASE THAT RELEASES
            NOTHING. A first mount's teardown can look clean for reasons
            that are not the teardown's — a page that never had anything
            to lose reads zero whatever the code does. Growth across an
            open/close/open cycle is the signature of a release the
            substrate's own reaction lifecycle cannot see, and it is only
            visible on the second mount.

            THE SETTLING POINT IS THE KIT'S OWN `quiesced!`, NOT A
            MACROTASK. The collector gives a cell whose last reader
            unmounts one macrotask of grace, deliberately, so that a keyed
            reorder which unmounts and remounts within one turn reuses the
            reaction instead of rebuilding it; and the entry reaper's
            horizon sits outside a bare `setTimeout 0`. A residue read
            before that point reports a LEAK against a runtime behaving
            exactly as documented.

            THIS IS A VERIFICATION GAP BEING CLOSED, NOT AN ALLEGATION.
            W1 and W2 both CALL `teardown!` and neither measures anything
            after it, and a call is not a measurement. If the numbers come
            back at baseline — and they do — that IS the deliverable."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [!baseline (atom nil)
              !mounted  (atom nil)
              !handles  (atom nil)
              mount!    (fn []
                          (reset! !handles (mount-shell! shell-frame))
                          (poll-until #(detail-panel-node (:container @!handles))))
              unmount!  (fn []
                          (when-some [{:keys [container root]} @!handles]
                            (reset! !handles nil)
                            (teardown! root container)))
              census    (fn [k] (-> @!baseline (get :runtime) (get k)))]
          (-> ;; The BASELINE is taken after the runtime settles, never at the
              ;; top of the row: a neighbouring suite's teardown grace may
              ;; still be in flight, and a baseline read early is a baseline
              ;; that is non-zero for reasons which are not this chrome's.
              (rf.fresco.test.runtime/quiesced!)
              (.then
                (fn [_]
                  (let [b (chrome-residue)]
                    (reset! !baseline b)
                    (is (= empty-runtime (:runtime b))
                        (str "BASELINE: the collector holds nothing before the "
                             "first mount, so `returns to baseline` below is a "
                             "return to ZERO rather than to a residue a leak "
                             "could hide inside. Got: " (pr-str (:runtime b))))
                    (is (zero? (:ref-count b))
                        (str "BASELINE: and the frame holds no sub-cache "
                             "reference for the shell's read. Cache keys: "
                             (pr-str (keys (cache-of shell-frame)))))
                    (is (false? (:live-cell? b))
                        "BASELINE: and no collector cell, so no watch")
                    (is (zero? (:reader-edges b))
                        "BASELINE: and no reader edge, so no listener"))
                  (mount!)))
              (.then
                (fn [_]
                  (let [m (chrome-residue)]
                    (reset! !mounted m)
                    (is (pos? (:ref-count m))
                        (str "NON-VACUITY: the mount TOOK a sub-cache reference "
                             "— otherwise the release below is a claim about "
                             "nothing. Got: " (pr-str m) (uncaught-note)))
                    (is (true? (:live-cell? m))
                        "NON-VACUITY: and a live collector cell, so there is a
                         watch to lose")
                    (is (pos? (:reader-edges m))
                        (str "NON-VACUITY: and reader edges on it, so there are "
                             "listeners to lose. Got: " (:reader-edges m)))
                    (is (pos? (:cells (:runtime m)))
                        (str "NON-VACUITY: and the chrome's SEVEN boundaries "
                             "between them lit the collector up. Census: "
                             (pr-str (:runtime m))))
                    (is (pos? (:boundaries (:runtime m)))
                        "NON-VACUITY: with registrations holding reader slots")
                    (is (pos? (:edges (:runtime m)))
                        "NON-VACUITY: and dependency edges across the whole tree"))
                  (unmount!)
                  (rf.fresco.test.runtime/quiesced!)))
              (.then
                (fn [_]
                  (let [a (chrome-residue)]
                    (is (zero? (:ref-count a))
                        (str "the unmount released the sub-cache reference "
                             "COMPLETELY. Residue: " (pr-str a)
                             " — frame cache keys: "
                             (pr-str (keys (cache-of shell-frame)))))
                    (is (false? (:live-cell? a))
                        (str "NO RETAINED WATCHES: the collector cell for the "
                             "shell's read is gone, so no `add-watch` on a "
                             "derived reaction survives the unmount. Residue: "
                             (pr-str a)))
                    (is (zero? (:reader-edges a))
                        (str "NO RETAINED LISTENERS: and no reader edge survives "
                             "either. Residue: " (pr-str a)))
                    (is (= (:runtime @!baseline) (:runtime a))
                        (str "and the WHOLE collector census is back at baseline "
                             "— every cell, edge, boundary registration and "
                             "cached read-set entry the chrome's seven "
                             "boundaries took. Baseline: "
                             (pr-str (:runtime @!baseline))
                             " — after unmount: " (pr-str (:runtime a)))))
                  ;; ---- reopen: the same numbers, not higher ones ----------
                  (mount!)))
              (.then
                (fn [_]
                  (let [r (chrome-residue)
                        m @!mounted
                        shape (fn [c] (select-keys c [:cells :cell-refs
                                                      :boundaries :edges]))]
                    (is (= (:ref-count m) (:ref-count r))
                        (str "reopening takes the SAME sub-cache reference count "
                             "(" (:ref-count m) ") rather than accumulating. "
                             "Got: " (:ref-count r)))
                    (is (= (:reader-edges m) (:reader-edges r))
                        (str "and the same reader-edge count ("
                             (:reader-edges m) ") — an edge left behind by the "
                             "first teardown would show here as growth. Got: "
                             (:reader-edges r)))
                    (is (= (shape (:runtime m)) (shape (:runtime r)))
                        (str "and the whole census is identical across the "
                             "open/close/open cycle. First mount: "
                             (pr-str (shape (:runtime m)))
                             " — reopen: " (pr-str (shape (:runtime r)))))
                    ;; Read-set entries are CACHED and reaped on their own
                    ;; horizon, so a reopen inside the window may legitimately
                    ;; reuse or rebuild them. What may never happen is growth.
                    (is (<= (:entries (:runtime r)) (:entries (:runtime m)))
                        (str "and the cached read-set entries did not grow. "
                             "First mount: " (:entries (:runtime m))
                             " — reopen: " (:entries (:runtime r)))))
                  (unmount!)
                  (rf.fresco.test.runtime/quiesced!)))
              (.then
                (fn [_]
                  (let [a (chrome-residue)]
                    (is (= (:runtime @!baseline) (:runtime a))
                        (str "and the SECOND unmount returns to baseline too, so "
                             "the release is a property of teardown rather than "
                             "of the first one happening to be clean. Baseline: "
                             (pr-str (:runtime @!baseline))
                             " — after second unmount: " (pr-str (:runtime a))))
                    (is (zero? (:reader-edges a))
                        (str "with no reader edge surviving it either. Residue: "
                             (pr-str a))))))
              (.catch (fn [e]
                        (is false (str "W4 never settled: " (.-message e)
                                       " — baseline cells: " (pr-str (census :cells))
                                       (uncaught-note)))
                        nil))
              (.then (fn [_]
                       ;; Defensive: a row that reddened mid-flight may still
                       ;; hold a mounted root, and a live root leaks into the
                       ;; next namespace's baseline.
                       (unmount!)
                       (done)))))))))

;; ===========================================================================
;; W5 — the EMBED door (`panels/mount-shell!`), and the C5 measurement the
;;      root-swap plan owed
;; ===========================================================================
;;
;; TWO QUESTIONS IN ONE ROW, because they share a mount.
;;
;; (1) THE EMBED DOOR STILL WORKS. `panels/mount-shell!` is the entry a Story
;;     or a custom dev surface uses, and it mounts `[shell/shell-view {…}]`
;;     from a REAGENT tree through the installed adapter's `:render`. After
;;     the root swap `shell-view` is no longer a `reg-view` — it is the public
;;     callable bridge, answering the React element `ShellView` lowers to via
;;     `rf.fresco/as-element`. Nothing in the node lane can see whether that
;;     crossing actually commits: `panels_mount_cljs_test` stubs the adapter
;;     and captures the hiccup `[shell/shell-view {…}]` BEFORE the bridge is
;;     ever called, so its rows stay green whatever the bridge returns. This
;;     row calls it for real.
;;
;;     AND `as-element` RATHER THAN `as-component` IS WHAT THIS ROW GRADES.
;;     Every opt is a KEYWORD, and a Reagent `[:>]` crossing converts
;;     values — `:frame-id ::shell` would arrive as the STRING
;;     "day8.re-frame2-xray.shell-fresco-boundary-dom-cljs-test/shell",
;;     naming a frame that does not exist. The frame assertions below are
;;     what separate a bridge that carried its opts from one that flattened
;;     them.
;;
;; (2) C5'S OPEN QUESTION, ANSWERED BY MEASUREMENT. The root-swap plan raised
;;     `mount-shell!`'s missing outer `frame-provider` as a POSSIBLE third
;;     rf2-tqlmq instance and said so explicitly as a question rather than a
;;     finding — "whether it leaks depends on what frame a Story or custom
;;     host has in scope, which was not measured". This measures it, in the
;;     configuration the question was about: an application that owns a live
;;     frame, its own Reagent root painting under `[rf/frame-provider {:frame
;;     app}]`, and the Xray embed mounted at a node INSIDE that root's DOM.
;;
;;     THE ANSWER TURNS ON A FACT ABOUT REACT RATHER THAN ABOUT XRAY, which
;;     is why reading the source was never going to settle it: `mount-shell!`
;;     goes through `rf.substrate.adapter/render`, which creates its OWN
;;     React root at the node it is given, and REACT CONTEXT DOES NOT CROSS A
;;     ROOT BOUNDARY. DOM nesting is not React nesting. So the host's
;;     `frame-provider` is not in scope inside the embed however deeply the
;;     node is nested, and there is no host frame to fall through TO — which
;;     is what the plan's "depends what the host has in scope" was worried
;;     about.
;;
;;     Since rf2-k97c.3 the point is doubly moot and in the better direction:
;;     the bridge opens its OWN provider around `frame-id`, so the embed is
;;     positively scoped rather than merely unexposed, and a Fresco boundary
;;     emits no view-render trace for a ring to collect in the first place.
;;     The row asserts the outcome — the app's epoch ring is untouched in
;;     COUNT and CONTENTS across a real Xray interaction — so it keeps biting
;;     whichever of those three reasons a future change removes.

(defn- mount-embed!
  "The Story-host shape: an application Reagent root painting under its own
  `[rf/frame-provider {:frame app-frame}]`, with the Xray embed mounted
  through `panels/mount-shell!` at a node INSIDE that root's DOM."
  [app-frame xray-frame]
  (let [app-container (.createElement js/document "div")
        app-root      (rdc/create-root app-container)
        embed-node    (.createElement js/document "div")]
    (.appendChild (.-body js/document) app-container)
    (react-dom/flushSync
      (fn []
        (rdc/render app-root
                    [rf/frame-provider {:frame app-frame}
                     [:div {:data-testid "host-app-surface"} "host app"]])))
    ;; The embed node is a DOM child of the host's painted tree, which is
    ;; exactly the nesting a Story cell has — and exactly the nesting that
    ;; does NOT make it a React descendant.
    (.appendChild app-container embed-node)
    {:app-container app-container
     :app-root      app-root
     :embed-node    embed-node
     ;; `mount-shell!` answers the adapter's own unmount fn; there is no
     ;; `panels/unmount-shell!` facade.
     :unmount       (panels/mount-shell! embed-node {:frame xray-frame
                                                     :mode  :inline})}))

(deftest w5-the-embed-door-mounts-and-never-touches-the-host-frames-ring
  (testing "rf2-k97c.3 — `panels/mount-shell!` still paints the shell after
            the root swap, carries its KEYWORD opts across the bridge
            intact, and a real Xray chrome interaction leaves the inspected
            application frame's epoch history unchanged in count AND
            contents. The C5 measurement, taken rather than assumed."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (rf/make-frame {:id embed-app-frame})
        (rf/reg-event :w5/app-inc (fn [{:keys [db]} _]
                                    {:db (update db :n (fnil inc 0))}))
        ;; One real application event FIRST, so the ring under inspection is
        ;; non-empty and `unchanged` below is a claim about real records.
        (rf/dispatch-sync [:w5/app-inc] {:frame embed-app-frame})
        (let [{:keys [app-container embed-node unmount]}
              (mount-embed! embed-app-frame embed-xray-frame)
              tab-testid (fn [] (some-> (detail-panel-node embed-node)
                                        (.getAttribute "data-testid")))
              !before    (atom nil)]
          (-> (poll-until tab-testid)
              (.then
                (fn [_]
                  (is (some? (testid embed-node "rf-xray-shell"))
                      (str "the EMBED door commits the shell envelope — the "
                           "`shell-view` bridge crossed into Xray's own "
                           "Fresco tree from a Reagent parent"
                           (uncaught-note)))
                  (is (some? (testid embed-node "rf-xray-tab-bar"))
                      "and the migrated L3 tab bar with it")
                  ;; THE KEYWORD-CROSSING CLAIM. If the opts had gone through
                  ;; a Reagent `[:>]` conversion, `:frame-id` would be a
                  ;; string and this frame would hold nothing.
                  (is (= :epoch (selected-tab-in embed-xray-frame))
                      (str "the embed's own frame — named by the KEYWORD the "
                           "bridge carried — holds the shell's state. Got: "
                           (pr-str (selected-tab-in embed-xray-frame))))
                  (reset! !before (vec (rf/epoch-history embed-app-frame)))
                  (is (seq @!before)
                      "instrument control: the host app's ring is NON-EMPTY,
                       so `unchanged` below is a claim about real records")
                  ;; THE ACT: a real click on the embedded chrome.
                  (if (click! (tab-node embed-node clicked-tab)
                              (str "the embedded L3 " (name clicked-tab)
                                   " tab button"))
                    (poll-until #(= (panel-testid-for clicked-tab) (tab-testid)))
                    (js/Promise.resolve nil))))
              (.then
                (fn [_]
                  (is (= (panel-testid-for clicked-tab) (tab-testid))
                      (str "NON-VACUITY: the embedded chrome actually "
                           "RESPONDED to the click, so the untouched ring "
                           "below is about a live shell rather than a dead "
                           "one. Got: " (pr-str (tab-testid))))
                  (is (= clicked-tab (selected-tab-in embed-xray-frame))
                      "and the write landed in the embed's OWN frame")
                  (let [after (vec (rf/epoch-history embed-app-frame))]
                    (is (= (count @!before) (count after))
                        (str "C5 MEASURED: the embed adds NO epoch record to "
                             "the host application's ring. Was "
                             (count @!before) ", now " (count after)))
                    (is (= @!before after)
                        "and leaves every existing record byte-identical —
                         the missing outer provider `mount-shell!` never had
                         is not a leak, and cannot become one"))
                  ;; The ring is LIVE, not merely quiet.
                  (rf/dispatch-sync [:w5/app-inc] {:frame embed-app-frame})
                  (is (not= @!before (vec (rf/epoch-history embed-app-frame)))
                      "control: a genuine APPLICATION event DOES move the
                       same ring — the equality above is Xray being absent,
                       not the instrument being deaf")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       (uncaught-note)))
                        nil))
              (.then (fn [_]
                       (when unmount (unmount))
                       (.remove app-container)
                       (done)))))))))
