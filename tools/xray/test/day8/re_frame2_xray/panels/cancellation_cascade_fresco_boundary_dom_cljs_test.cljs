(ns day8.re-frame2-xray.panels.cancellation-cascade-fresco-boundary-dom-cljs-test
  "THE CANCELLATION-CASCADE POPOVER RE-AUTHORED IN THE RE-FRAME-NATIVE
  VIEW LAYER, read off a real React commit (rf2-k97c.3, step 2).

  `cancellation-cascade/PopoverView` is now an `rf.fresco/defview`
  reading through Fresco's shipped collector rather than an
  `rf/reg-view` reading through whatever view build the installed
  substrate adapter supplies. This file is the behavioural evidence for
  that swap, following the merged template
  (`module_view_fresco_boundary_dom_cljs_test`).

  ## What the epic asked for, and which row answers it

  This panel is the first migrated one that DISPATCHES, so unlike the
  template it can answer criterion 3 — and W5 is the whole reason this
  file is worth more than a copy of the template:

    1 FIRST DISPLAY               — W1
    2 UPDATES ON A REAL CHANGE    — W2 (with the deaf control that makes
                                    the update mean liveness)
    3 XRAY'S OWN INTERACTIONS     — W5, a real browser click on a real
                                    button, with the cross-frame control
    4 FRAME TARGETING             — W1's second half, read off the frame's
                                    OWN sub-cache rather than off the DOM
    5 TOOL ACTIVITY NEVER
      MASQUERADING AS APPLICATION
      EVIDENCE                    — W3, in BOTH directions
    6 CLEAN TEARDOWN              — W4

  ## Why W5 matters beyond this panel

  The template panel dispatches nothing, so increment 1 left open the
  question of how a dispatch behaves inside a boundary. The answer was
  read out of Fresco's own shipped code before a line moved:
  `re-frame.fresco.impl.intent/with-frame` — which
  `collector/run-once` wraps every body in — binds core's refusal tier,
  so an AMBIENT dispatch refuses, while an explicitly carried
  `{:frame <id>}` still answers, because that tier deletes the ambient
  FIND and not the carrying; and `rf/current-frame-id` answers the
  declared frame, because it neither reads nor dispatches. Plus
  `defview`'s own contract that a PLAIN FUNCTION at an `on-*` prop is
  passed through untouched.

  W5 is that reasoning put in front of a browser. It clicks the real
  expander button and asserts the event landed in the frame the tree
  NAMED and nowhere else. If the reasoning above were wrong, the
  handler would either refuse at the click or write into the wrong
  frame, and one of W5's two halves would say so.

  ## The mount is the SHELL's mount

  `shell.cljs` mounts this popover as the hiccup head
  `[cancellation-cascade/Popover]` at the shell root, inside the
  shell's `[rf/frame-provider {:frame :rf/xray}]`. [[mount-popover!]]
  does exactly that — same head, same wrapper — so the bridge the shell
  actually holds is the thing under test.

  Nothing below ever calls the view a second time. Every assertion
  after the mount reads `container.querySelector…` — the DOM React
  committed on its own.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build's
  `cljs-test$` regex also matches, so it LOADS under Node — where every
  row short-circuits through [[browser?]] and reports the skip rather
  than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cc]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ORDINARY application frame — the thing Xray inspects. Three rows
  need one that is not `:rf/xray`: W1 reads it as the negative half of
  the frame-targeting claim, W3 mounts INSIDE it, and W5 asserts the
  click's event did NOT land in it."
  ::app)

(def ^:private open?-q
  "The popover's GATE read — the one subscription a closed popover
  costs. Named once because three rows key off it; the sub-cache is
  keyed by the query vector itself (`re-frame.subs/cache-key` is
  identity), so this value IS the cache key."
  [:rf.xray/cancellation-cascade-popover-open?])

(def ^:private expanded?-q
  [:rf.xray/cancellation-cascade-expanded?])

(def ^:private focus-dispatch-id 7)

(defn- abort-event
  [n]
  {:id        (+ 10 n)
   :operation :rf.http/aborted-on-actor-destroy
   :op-type   :rf.http
   :severity  :info
   :time      (+ 1020 n)
   :tags      {:request-id           (keyword (str "r" n))
               :url                  (str "/api/x" n)
               :actor-id             :user-session
               :rf.trace/dispatch-id focus-dispatch-id
               :frame                :rf/default}})

(def ^:private cascade-buffer
  "One decision + one cancellation-anchor + TWELVE aborts. Twelve
  rather than two because W5 clicks the collapse expander, which only
  renders once the abort list is over the collapse threshold — a
  fixture with two aborts would give W5 no control to press."
  (into [{:id 1 :operation :rf.event/dispatched :op-type :rf.event
          :time 1000
          :tags {:rf.event/v           [:auth/logout]
                 :rf.trace/dispatch-id focus-dispatch-id
                 :frame                :rf/default}}
         {:id 2 :operation :rf.machine/destroyed :op-type :rf.machine
          :time 1010
          :tags {:machine-id           :user-session
                 :reason               :explicit
                 :rf.trace/dispatch-id focus-dispatch-id
                 :frame                :rf/default}}]
        (map abort-event (range 1 13))))

;; ---- a probe event, and the `reg-view` W3 measures the panel against ------

(rf/reg-event ::bump
  (fn [{:keys [db]} [_ n]] {:db (assoc db ::n n)}))

(rf/reg-sub ::n (fn [db _] (::n db)))

(rf/reg-view ProbeRegView
  "The POSITIVE CONTROL for W3, and nothing else. An ordinary
  `reg-view` rendered by the installed adapter in the same root, in the
  same frame, in the same commit as the popover — so the only variable
  between it and the popover is which view layer authored the body."
  []
  [:div {:data-testid "rf-xray-probe-reg-view"} (str @(rf/subscribe [::n]))])

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W2, W4 and W5 are `async` rows, and
     ;; `cljs.test` refuses a FUNCTION fixture in any namespace that
     ;; carries one.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing
                      ;; about; a neighbour's boundary left in the entry
                      ;; cache would make W4's release row read a
                      ;; residue that is not this popover's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than
;; an omission — a Fresco boundary is NOT in Reagent's render queue, so
;; draining that queue commits nothing of this popover's update and a row
;; written that way reads a DOM that has not moved and reports a live view
;; as dead. Mount is committed with `flushSync`; everything after it is a
;; bounded poll of the committed DOM.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It
  exists for the CONTROL in W2: an absence asserted immediately after a
  write is a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- setup!
  "Register Xray's handlers and make the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed-cascade!
  "Put a real cancellation cascade in Xray's trace buffer and focus it."
  []
  (rf/dispatch-sync [:rf.xray/sync-trace-buffer cascade-buffer]
                    {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                     {:kind :dispatch-id :id focus-dispatch-id}]
                    {:frame :rf/xray}))

(defn- mount-popover!
  "Mount the popover the way `shell.cljs` mounts it: the var as a hiccup
  head at the root of a `frame-provider` scoping `frame`. Committed
  synchronously — React 19's `root.render` is otherwise async and phase
  1 would assert against an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [cc/Popover]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads the sub-cache."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

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

(defn- aborts-shown
  "How many abort rows the committed body says it is showing. The panel
  stamps this on its own root as `data-aborts-shown`, so the number is
  read off the DOM rather than counted from a query."
  [container]
  (some-> (q container "[data-testid=\"rf-xray-cancellation-cascade-body\"]")
          (.getAttribute "data-aborts-shown")
          js/parseInt))

;; ===========================================================================
;; W1 — first display, and the read lands in the frame the tree named
;; ===========================================================================

(deftest w1-popover-paints-and-its-reads-land-in-the-named-frame
  (testing "rf2-k97c.3 — the migrated cancellation-cascade popover commits
            real DOM through the head `shell.cljs` mounts, and its
            `rf.fresco/sub` reads resolve against the frame the enclosing
            `frame-provider` named rather than the ambient one. Epic
            criteria 1 and 4."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (seed-cascade!)
            ;; A live read in the OTHER frame, so the negative half of the
            ;; targeting claim below is measured with an instrument that is
            ;; demonstrably able to see an entry in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-popover! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-cancellation-cascade-popover-dialog\"]"))
              "the dialog committed a real DOM root under React — a Fresco
               boundary mounted through Reagent's `:>` from the shell's head")
          (is (some? (q container "[data-testid=\"rf-xray-cancellation-cascade-decision-row\"]"))
              "and the waterfall's decision row rendered, so the body ran
               against a real cascade rather than an empty state")

          ;; ---- criterion 4: the reads are where the tree said ----
          (is (pos? (ref-count-of :rf/xray open?-q))
              (str "the popover's GATE read holds a reference in :rf/xray's "
                   "sub-cache — the frame the enclosing frame-provider named. "
                   "Cache keys: " (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame open?-q))
              "and NOT in the application frame's — a foreign root that
               inherited the ambient scope instead of reading React context
               would put it here")
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the zero
               above is an absence and not a broken reader")
          (finally
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! root container)))))))

;; ===========================================================================
;; W2 — it updates on a real dependency change, and the control is deaf
;; ===========================================================================

(deftest w2-popover-updates-on-a-real-dependency-change
  (testing "rf2-k97c.3 — the mounted popover re-renders itself and commits
            new DOM when a read's value really changes, and does NOT when
            nothing it watches moved. Epic criterion 2, with the control
            that makes the update mean liveness rather than a commit that
            simply had not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-cascade!)
        (let [{:keys [container root]} (mount-popover! :rf/xray)
              dialog?  (fn [] (some? (q container "[data-testid=\"rf-xray-cancellation-cascade-popover-dialog\"]")))
              gone?    (fn [] (not (dialog?)))]
          (is (dialog?)
              "PRECONDITION: the dialog is on screen, so its disappearance
               below is a real transition and not a panel that never
               rendered")

          ;; ---- phase 2: the world moves, and the popover is deaf ---------
          ;; A real app-db write into the SAME frame the popover reads,
          ;; under a key none of its four subscriptions computes over. Every
          ;; db-derived sub recomputes and answers the value it answered
          ;; before, so nothing the boundary watches is invalidated. A
          ;; binding that repainted on db MOVEMENT rather than on VALUE
          ;; CHANGE fires here.
          (rf/dispatch-sync [::bump 1] {:frame :rf/xray})
          (-> (settle)
              (.then
                (fn [_]
                  (is (dialog?)
                      "CONTROL: given a full settling window, the deaf write
                       moved nothing — the dialog is still exactly where it
                       was. A popover that re-rendered its way out of
                       existence here would make phase 3 pass for a reason
                       that is not liveness")
                  ;; ---- phase 3: the gate flips, DOM follows --------------
                  (rf/dispatch-sync [:rf.xray/cancellation-cascade-close]
                                    {:frame :rf/xray})
                  (is (false? @(rf/subscribe open?-q {:frame :rf/xray}))
                      "PRECONDITION: the underlying gate value really did
                       flip — so a dialog still on screen below is the
                       popover failing to re-render, not the event failing
                       to land")
                  (rf.test-support/poll-until gone?
                    {:label "the popover committed its own disappearance"})))
              (.then
                (fn [_]
                  (is (gone?)
                      "the popover re-rendered on a real invalidation of its
                       gate read and committed the closed state")
                  ;; ---- phase 4: and it comes back, so the close was not
                  ;; a one-way teardown of the boundary itself -------------
                  (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                                     {:kind :dispatch-id :id focus-dispatch-id}]
                                    {:frame :rf/xray})
                  (rf.test-support/poll-until dialog?
                    {:label "the popover re-opened"})))
              (.then
                (fn [_]
                  (is (dialog?)
                      "and re-opening commits the dialog again — the gate is
                       live in both directions, not a latch")))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (teardown! root container)
                       (done)))))))))

;; ===========================================================================
;; W3 — the tool's own render is not application view evidence
;; ===========================================================================

(deftest w3-the-boundarys-render-emits-no-view-trace
  (testing "rf2-k97c.3 / rf2-tqlmq — rendering the migrated popover
            contributes NOTHING to the substrate's view-trace stream, even
            when it is mounted INSIDE an application frame. Epic criterion
            5, proven structurally rather than by the `:rf/xray` frame gate:
            a Fresco boundary is not a substrate view render, so there is no
            event to gate. The control is an ordinary `reg-view` in the same
            root, the same frame and the same commit."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_       (setup!)
            traces  (atom [])
            view-op? #(and (keyword? (:operation %))
                           (= "rf.view" (namespace (:operation %))))]
        ;; The popover's gate lives in the frame it is MOUNTED in, so seed
        ;; the application frame here rather than `:rf/xray` — otherwise
        ;; the subject renders nil and its zero is vacuous, which is exactly
        ;; what the precondition below refuses to let happen quietly.
        (rf/dispatch-sync [:rf.xray/sync-trace-buffer cascade-buffer]
                          {:frame app-frame})
        (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                           {:kind :dispatch-id :id focus-dispatch-id}]
                          {:frame app-frame})
        (rf/register-listener! :trace ::collect (fn [ev] (swap! traces conj ev)))
        (try
          ;; ---- the subject: the popover, mounted in an APPLICATION frame --
          (let [{:keys [container root]} (mount-popover! app-frame)
                subject-views (filterv view-op? @traces)]
            (try
              (is (some? (q container "[data-testid=\"rf-xray-cancellation-cascade-popover-dialog\"]"))
                  "precondition: the popover really did render in this commit
                   — an empty container would make the zero below vacuous")
              (is (zero? (count subject-views))
                  (str "the popover's render put NO :rf.view/* op in the trace "
                       "stream while rendering inside an application frame. "
                       "Ops seen: " (pr-str (mapv :operation subject-views))))
              (finally (teardown! root container))))

          ;; ---- the control: a reg-view, same root shape, same frame -------
          (reset! traces [])
          (let [container (.createElement js/document "div")
                root      (rdc/create-root container)]
            (.appendChild (.-body js/document) container)
            (try
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame app-frame}
                                    [ProbeRegView]])))
              (let [control-views (filterv view-op? @traces)]
                (is (some? (q container "[data-testid=\"rf-xray-probe-reg-view\"]"))
                    "precondition: the control really did render")
                (is (pos? (count control-views))
                    (str "CONTROL FIRES: an ordinary reg-view rendered the same "
                         "way DOES emit a :rf.view/* op, so the subject's zero "
                         "is a property of the boundary and not of a dead "
                         "instrument. Ops seen: "
                         (pr-str (mapv :operation control-views)))))
              (finally (teardown! root container))))
          (finally
            (rf/unregister-listener! :trace ::collect)))))))

;; ===========================================================================
;; W4 — clean teardown: the reads are released, and reopening is not growth
;; ===========================================================================

(defn- released?
  "The popover's gate read is fully released from `:rf/xray`'s sub-cache."
  []
  (zero? (ref-count-of :rf/xray open?-q)))

(deftest w4-unmount-releases-the-reads-and-reopen-does-not-grow-them
  (testing "rf2-k97c.3 — unmounting the popover releases its subscription
            references completely, and mounting it again returns to the SAME
            count rather than a higher one. Epic criterion 6, and the number
            the spike caught the rejected design on: with a four-call interop
            binding the `:rf/xray` ref-count climbed 22 → 25 → 32 across
            renders and never fell on unmount.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, and this row polls rather
            than reading once. `impl.collector`'s `cell-reapers` gives a cell
            whose last reader unmounts ONE MACROTASK OF GRACE, so that a keyed
            reorder which unmounts and remounts a row within a single turn
            reuses the reaction instead of rebuilding it. A synchronous
            assertion would therefore report a LEAK against a collector
            behaving exactly as documented."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-cascade!)
        (-> (rf.test-support/poll-until released?
              {:label "no reference held before the first mount"})
            (.then
              (fn [_]
                (let [{:keys [container root]} (mount-popover! :rf/xray)
                      gate    (ref-count-of :rf/xray open?-q)
                      body    (ref-count-of :rf/xray expanded?-q)]
                  (is (pos? gate)
                      "the mount took a reference on the gate read —
                       otherwise the release below is vacuous")
                  (is (pos? body)
                      "AND on a read made INSIDE the open branch, so this row
                       is about the conditional reads too and not only the
                       gate")
                  (teardown! root container)
                  (-> (rf.test-support/poll-until released?
                        {:label "the first unmount released the reads"})
                      (.then
                        (fn [_]
                          (is (released?)
                              (str "the unmount released the gate COMPLETELY, "
                                   "within the collector's grace macrotask. "
                                   "Cache: " (pr-str (keys (cache-of :rf/xray)))))
                          (is (zero? (ref-count-of :rf/xray expanded?-q))
                              "and the conditional read too — a branch-taken
                               edge is released on the same lifecycle as the
                               gate, not leaked because it was conditional")
                          ;; ---- reopen: the same count, not a higher one ----
                          (let [{c2 :container r2 :root} (mount-popover! :rf/xray)
                                remounted (ref-count-of :rf/xray open?-q)]
                            (is (= gate remounted)
                                (str "reopening returns to the SAME reference "
                                     "count (" gate ") rather than "
                                     "accumulating — accumulation across "
                                     "open/close cycles is the signature of a "
                                     "release the substrate's own reaction "
                                     "lifecycle cannot see. Got: " remounted))
                            (teardown! r2 c2)
                            (rf.test-support/poll-until released?
                              {:label "the second unmount released it too"}))))))))
            (.then (fn [_] (is (released?)
                               "and the second unmount releases it too")))
            (.catch (fn [e] (is false (str "poll timed out: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ===========================================================================
;; W5 — CRITERION 3: a real click inside the boundary dispatches, and it
;;      dispatches into the frame the tree NAMED
;; ===========================================================================
;;
;; This is the row the template could not carry, and it is the reason this
;; file exists rather than a second copy of module_view's. See the ns
;; docstring for the contract it is putting in front of a browser.

(deftest w5-a-click-inside-the-boundary-dispatches-into-the-named-frame
  (testing "rf2-k97c.3 — clicking the collapse expander inside the migrated
            popover dispatches `:rf.xray/cancellation-cascade-toggle-expand`,
            the DOM follows, and the event lands in the frame the enclosing
            `frame-provider` NAMED rather than in the ambient one or in a
            neighbouring application frame. Epic criterion 3."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-cascade!)
        (let [{:keys [container root]} (mount-popover! :rf/xray)
              toggle (q container "[data-testid=\"rf-xray-cancellation-cascade-expand-toggle\"]")
              before (aborts-shown container)]
          (is (some? toggle)
              "PRECONDITION: the collapse expander rendered — the fixture
               carries more aborts than the collapse threshold, so there is
               a real control to press")
          (is (= 5 before)
              (str "PRECONDITION: the body opens COLLAPSED, showing the "
                   "default five of twelve abort rows. Got: " before))
          (is (false? @(rf/subscribe expanded?-q {:frame :rf/xray}))
              "PRECONDITION: and the expand flag is off in :rf/xray")
          (is (false? @(rf/subscribe expanded?-q {:frame app-frame}))
              "PRECONDITION: and off in the application frame, so the
               cross-frame control below starts from a real zero")

          ;; ---- the act: a REAL browser click on a REAL button -------------
          (when toggle (.click toggle))

          (-> (rf.test-support/poll-until
                #(= 12 (aborts-shown container))
                {:label "the popover committed the expanded abort list"})
              (.then
                (fn [_]
                  (is (= 12 (aborts-shown container))
                      "the click reached the handler, the dispatch landed, the
                       read was invalidated and the boundary committed the
                       expanded list — the whole round trip through a Fresco
                       boundary, in a browser")
                  (is (true? @(rf/subscribe expanded?-q {:frame :rf/xray}))
                      "and the flag flipped in :rf/xray — the frame the
                       enclosing frame-provider named")
                  (is (false? @(rf/subscribe expanded?-q {:frame app-frame}))
                      "CROSS-FRAME CONTROL: and NOT in the application frame.
                       A handler that had lost its captured frame and fallen
                       back to an ambient or default one would write here")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — aborts-shown: "
                                       (pr-str (aborts-shown container))))
                        nil))
              (.then (fn [_]
                       (rf/unsubscribe expanded?-q {:frame :rf/xray})
                       (rf/unsubscribe expanded?-q {:frame app-frame})
                       (teardown! root container)
                       (done)))))))))
