(ns re-frame.hicasso.foreign-root-bridge-dom-cljs-test
  "WHO OWNS THE ROOT IS NOT THE VARIABLE, AND AN INVALIDATED CELL IS.

  ## What this file was written to settle, and what it found instead

  The report behind it was that a boundary crossed into from a REAGENT
  parent *\"paints once and is then deaf to writes\"*, with `h/mount!` as a
  live control, and it asked whether a UIx parent was deaf too — because
  that would make it the outward bridge generally rather than a Reagent
  story.

  **Neither is deaf.** §1 below drives FIVE mounting routes — Hicasso's
  own root, a Reagent root through each of the two bridge doors, a plain
  `react-dom/client` root, and a UIx `defui` parent under a plain root —
  with one boundary, one subscription, one adapter and one drain, and
  every one of them re-runs its body and moves the DOM on a write into
  its own frame. So a Hicasso view inside a foreign React host is
  reactive, and the answer the bead's first move asks for is *a UIx
  parent repaints*.

  **What IS deaf is a boundary that acquires a cell whose reaction was
  dropped**, and that has nothing to do with the crossing: §2 reproduces
  it under `h/mount!`, where there is no crossing at all. The reported
  measurement varied two things at once — the mounting route AND the
  frame id — and the second was carrying the defect.

  ## The defect, and why the acquire is where it is repaired

  `impl.collector/invalidate-cell!` drops a cell's reaction
  SYNCHRONOUSLY and defers rebuilding the attachment to the microtask
  checkpoint, because it is called from inside the registrar's
  registration and replacement hooks and from frame teardown, and *\"none
  of them is a place to subscribe\"*. Between those two moments the cell
  table holds a cell with no reaction and no watch.

  A boundary that COMMITS in that window is handed that cell by
  `acquire-cell!`, and used to be handed it as it stood. It then rendered
  the right value — a reaction-less cell takes the cold probe — and
  nothing could notify it, because `mark-dirty!` rides the watch the cell
  does not have. A commit is exactly a place to subscribe, so the acquire
  now wires what it reuses; the deferred rewire guards on the reaction
  still being nil, so the two are idempotent with respect to each other.

  The window opens on any first-time `reg-sub` of a query some live cell
  already holds — a lazily loaded module, a hot reload, or a test fixture
  that clears the registrar and re-registers — which is why §2 opens it
  that way.

  ## Two lanes, one file

  `-dom-cljs-test$` puts this namespace in `:browser-test` (real React,
  real DOM) and in `:node-test`, whose `cljs-test$` matches the same
  suffix. Every row here needs a fiber, so each degrades in Node to a
  stated skip rather than to a false green."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.test.runtime :as runtime]
            [re-frame.hicasso.roots-frames-support :as support]
            [re-frame.registrar :as rf-registrar]
            [re-frame.test-support :as test-support]
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(defonce ^:private !runs
  ;; Body runs for [[card]] alone. `test.runtime/body-runs` counts every
  ;; boundary in the process; this file mounts one at a time and wants the
  ;; per-row delta unambiguous. `defonce` takes no docstring.
  (atom 0))

(rf/reg-sub ::counter (fn [db _] (or (:n db) 0)))
(rf/reg-event ::bump (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))

(h/defview card
  "One ordinary boundary, reached by every route below. It reads a
  subscription, so the frame it resolved is observable on screen, and it
  bumps a counter, so a re-render is a NUMBER rather than an appearance —
  a boundary React skipped and a boundary React ran are what separate a
  missing notification from a stale read."
  [props]
  (swap! !runs inc)
  [:article {:data-test "card"} (str (:label props) "/" (h/sub [::counter]))])

(def ^:private bridged
  "THE OUTWARD BRIDGE, minted once at top level beside the view it
  bridges — the law, because it allocates a component."
  (h/as-component card))

(def ^:private memo-bridged (react/memo bridged))

(defui uix-parent
  "The third parent the bridge's docstring names — *`n/defcomponent`, UIx,
  or plain JavaScript* — rendering the same minted bridge, so a
  difference between rows is a difference between PARENTS rather than
  between three bridges that happened to agree."
  [{:keys [label]}]
  (uix/$ :div (uix/$ bridged {:label label})))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (support/leave-act-environment!)
                      (reset! !runs 0)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- drain!
  "The ratom host's drain, and it is two acts rather than one. `r/flush`
  runs the reactions the write enqueued — a re-frame subscription under
  the ratom family IS a bare `reagent.ratom/Reaction`, and running it is
  what fires the watch a Hicasso cell rides. The empty `flushSync` then
  lets the sync-lane `onStoreChange` that raised commit."
  []
  (r/flush)
  (react-dom/flushSync (fn [] nil))
  nil)

(defn- seat! [frame-kw n]
  (rf/make-frame {:id frame-kw})
  (rf/dispatch-sync [::bump n] {:frame frame-kw})
  frame-kw)

(defn- text-of [node]
  (some-> (.querySelector node "[data-test=\"card\"]") .-textContent))

(defn- readers-of [frame-kw]
  (count (runtime/cell-readers [frame-kw [::counter]])))

(defn- wired?
  "Does this frame's cell hold a live reaction? The cell is the only thing
  a watch can hang off, so this is the commit-owned fact a repaint
  depends on — and it is readable before any DOM is."
  [frame-kw]
  (some? (some-> ^js (get @collector/!cells [frame-kw [::counter]]) (.-reaction))))

;; ---------------------------------------------------------------------------
;; The five routes — one boundary, one subscription, one adapter, one drain
;; ---------------------------------------------------------------------------

(defn- hicasso-root
  "THE CONTROL. Hicasso's own root door, and no crossing at all."
  [node frame-kw]
  (let [handle (h/mount! node {:frame frame-kw} [card {:label "ctl"}])]
    (fn [] (try (h/unmount! handle) (catch :default _ nil)))))

(defn- reagent-root-as-element
  "The bead's route 2: a REAGENT root, `rf/frame-provider` above, and the
  boundary spliced in as a React element by `h/as-element`."
  [node frame-kw]
  (let [root (rdc/create-root node)]
    (react-dom/flushSync
      (fn []
        (rdc/render root
                    [rf/frame-provider {:frame frame-kw}
                     [:div.reagent-parent (h/as-element [card {:label "alpha"}])]])))
    (fn [] (try (.unmount root) (catch :default _ nil)))))

(defn- reagent-root-as-component
  "The bead's route 3: the same Reagent root through the OTHER bridge
  door, memoized on the head as the report spells it."
  [node frame-kw]
  (let [root (rdc/create-root node)]
    (react-dom/flushSync
      (fn []
        (rdc/render root
                    [rf/frame-provider {:frame frame-kw}
                     [:div.reagent-parent
                      (react/createElement memo-bridged #js {"label" "brg"})]])))
    (fn [] (try (.unmount root) (catch :default _ nil)))))

(defn- plain-react-root
  "A root the CONSUMER opened with `react-dom/client` itself, with no
  Reagent and no UIx anywhere in the tree — the sharpest reading of *who
  owns the root*, because it differs from [[hicasso-root]] in nothing
  else."
  [node frame-kw]
  (let [root (react-dom-client/createRoot node)]
    (react-dom/flushSync
      (fn []
        (.render root (mount/provider frame-kw
                                      (react/createElement
                                        "div" nil
                                        (h/as-element [card {:label "plain"}]))))))
    (fn [] (try (.unmount root) (catch :default _ nil)))))

(defn- uix-parent-plain-root
  "THE ROW THE BEAD'S FIRST MOVE ASKS FOR: a UIx `defui` parent, under a
  root Hicasso did not open."
  [node frame-kw]
  (let [root (react-dom-client/createRoot node)]
    (react-dom/flushSync
      (fn []
        (.render root (mount/provider frame-kw (uix/$ uix-parent {:label "uix"})))))
    (fn [] (try (.unmount root) (catch :default _ nil)))))

(def ^:private routes
  "Every mounting route §1 drives, with the frame each takes and the label
  its own parent writes. Declared as data so the row cannot quietly stop
  reaching one of them."
  [{:route :root/hicasso     :frame ::r1 :label "ctl"   :mount hicasso-root}
   {:route :crossed/reagent-as-element   :frame ::r2 :label "alpha" :mount reagent-root-as-element}
   {:route :crossed/reagent-as-component :frame ::r3 :label "brg"   :mount reagent-root-as-component}
   {:route :crossed/plain-react-root     :frame ::r4 :label "plain" :mount plain-react-root}
   {:route :crossed/uix-parent           :frame ::r5 :label "uix"   :mount uix-parent-plain-root}])

;; ===========================================================================
;; 1 · A WRITE REPAINTS THROUGH EVERY MOUNTING ROUTE
;; ===========================================================================

(deftest a-write-repaints-through-every-mounting-route
  (testing "rf2-phabt's headline claim, driven rather than argued: *a
            Hicasso view rendered inside any React-shaped host that is not
            a Hicasso root is non-reactive*. It is not. One boundary, one
            subscription, one adapter and one drain; only the root and the
            parent vary, and every route re-runs the body and moves the
            DOM.

            BODY RUNS ARE THE DIAGNOSTIC and the DOM text is the
            corroboration — the report is explicit that the failure it saw
            was a missing NOTIFICATION rather than a stale read, so the
            reading that answers it is the one that says the body was
            invoked again."
    (if-not (mount/browser?)
      (support/skip! ":node-test has no DOM")
      (doseq [{:keys [route frame label mount]} routes]
        (testing (str route)
          (seat! frame 1)
          (let [node (mount/fresh-container!)
                stop (mount node frame)]
            (try
              (is (= (str label "/1") (text-of node))
                  "it painted, reading ITS OWN frame's seeded value — a
                   boundary that resolved some other frame would read 0")
              (is (= 1 (readers-of frame))
                  "and the commit acquired the key, which is the fact a
                   repaint hangs off and is readable before the DOM is")
              (let [runs (deref !runs)]
                (rf/dispatch-sync [::bump 42] {:frame frame})
                (drain!)
                (is (< runs (deref !runs))
                    "THE WRITE RE-RAN THE BODY — the notification reached
                     this boundary")
                (is (= (str label "/42") (text-of node))
                    "and the readout moved with it"))
              (finally (stop)))))))))

;; ===========================================================================
;; 2 · THE CELL THE ACQUIRE USED TO REUSE WITHOUT WIRING
;; ===========================================================================
;;
;; `invalidate-cell!` drops a cell's reaction now and rebuilds the
;; attachment at the microtask checkpoint. A boundary committing inside
;; that window is handed the empty cell, and handing it over as it stands
;; — right value, no watch, no way to be notified — is the defect §2 is
;; about.
;;
;; The window is opened here the way a per-test fixture opens it: the
;; registrar is cleared and the query registered from cold, which is a
;; FIRST registration and reaches `first-registration!`. That is the
;; transition no disposal announces, and it invalidates every live cell
;; holding the id.

(defn- open-the-window!
  "Leave a live cell for `frame-kw`'s read, then invalidate it — and
  answer nothing, because the two `is` in the row below are what say the
  window is genuinely open."
  [frame-kw mount-fn]
  (seat! frame-kw 1)
  (let [node (mount/fresh-container!)
        stop (mount-fn node frame-kw)]
    (stop))
  ;; A first-time `reg-sub` of a query a live cell holds. The registrar
  ;; clear is what makes it first-time; a fixture, a lazily loaded module
  ;; and a hot reload all reach the same hook.
  (rf-registrar/clear-all!)
  (rf/reg-sub ::counter (fn [db _] (or (:n db) 0)))
  (rf/reg-event ::bump (fn [{:keys [db]} [_ n]] {:db (assoc db :n n)}))
  (reset! frame/frames {})
  (frame/ensure-default-frame!)
  (seat! frame-kw 1)
  nil)

(deftest a-boundary-acquiring-an-invalidated-cell-is-notified-in-the-same-turn
  (testing "THE ROW THE BEAD'S MEASUREMENT ACTUALLY TOOK, with the
            crossing held OUT of it. The cell for this frame's read is
            still in the table and its reaction has been dropped; a
            boundary now mounts through Hicasso's OWN root — no bridge, no
            foreign parent, nothing crossed — writes, and drains, all in
            one turn.

            Before rf2-phabt this row read `ctl/1` after a write of 42 and
            the body run count did not move: `acquire-cell!` reused the
            empty cell without wiring it, so `mark-dirty!` had no watch to
            ride and nothing asked the boundary to render again. The value
            on the first paint was RIGHT throughout, which is why a
            witness that stopped at the mount would pass on the broken
            runtime as happily as on the fixed one.

            SAME TURN is the whole claim. The deferred rewire does repair
            the cell at the microtask checkpoint, so a row that yielded
            first would be green either way and would be measuring the
            deferral rather than the acquire."
    (if-not (mount/browser?)
      (support/skip! ":node-test has no DOM")
      (doseq [[label frame mount-fn painted]
              [["h/mount!"         ::w1 hicasso-root           "ctl"]
               ["a Reagent parent" ::w2 reagent-root-as-element "alpha"]]]
        (testing label
          (open-the-window! frame mount-fn)
          (is (contains? (support/cell-keys) [frame [::counter]])
              "PRECONDITION: the cell is still in the table — its reaper is
               a macrotask and nothing has yielded")
          (is (not (wired? frame))
              "PRECONDITION: and its reaction has been dropped. Without
               this the row mounts against an ordinary live cell and
               proves nothing")
          (let [node (mount/fresh-container!)
                stop (mount-fn node frame)]
            (try
              (is (wired? frame)
                  "THE ACQUIRE WIRED WHAT IT REUSED — the commit did not
                   leave a reader attached to an empty cell")
              (is (= (str painted "/1") (text-of node))
                  "it painted the right value, as it always did — a
                   reaction-less cell takes the cold probe")
              (let [runs (deref !runs)]
                (rf/dispatch-sync [::bump 42] {:frame frame})
                (drain!)
                (is (< runs (deref !runs))
                    "so the write re-ran the body, in the same turn")
                (is (= (str painted "/42") (text-of node))
                    "and the readout moved"))
              (finally (stop)))))))))
