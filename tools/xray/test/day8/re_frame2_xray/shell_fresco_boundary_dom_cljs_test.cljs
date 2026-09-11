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

  ## The mount is the PRODUCTION mount

  `mount.cljs` renders `[rf/frame-provider {:frame …} [shell/shell-view
  {…}]]` through the installed adapter's `:render`. `shell-view` is still
  an `rf/reg-view` — severing that call is the parent epic's coupling (1)
  and a later slice — and it reaches the Fresco tree through ONE private
  `as-component` bridge. So mounting that same two-level form into a
  Reagent root IS the shipped path, crossing included, and nothing here
  reproduces it. [[mount-shell!]] records why the outer provider is not
  decoration.

  Nothing below ever calls a view a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed
  on its own.

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
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
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
  "Mount the shell the way `mount.cljs` mounts it — the OUTER
  `frame-provider` included:

      [rf/frame-provider {:frame …} [shell/shell-view {…}]]

  THAT WRAPPER IS LOAD-BEARING AND THIS ROW MEASURED IT. `shell-view` is
  itself an `rf/reg-view`, and a `reg-view`'s frame-aware wrapper takes
  its scope from React context; at a bare root there is none, so the
  first frame-scoped call in its body refuses with
  `:rf.error/no-frame-context` and NOTHING commits. `mount.cljs:396`
  carries the provider for its own documented reason (rf2-uu3lp — so the
  shell's render trace resolves to the trace-disabled `:rf/xray` frame
  rather than leaking into the inspected app's epoch), and a suite that
  drops it is not mounting what production mounts.

  The provider frame and the shell's `:frame-id` are the same value here,
  as they are in production.

  Committed synchronously — React 19's `root.render` is otherwise async
  and the first assertion would read an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [shell/shell-view {:frame-id frame}]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads anything. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
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
