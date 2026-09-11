(ns day8.re-frame2-xray.panels.app-db-segment-inspector-fresco-boundary-dom-cljs-test
  "THE APP-DB SEGMENT INSPECTOR POPUP, DRIVEN THROUGH ITS SHIPPED BRIDGE ON A
  REAL REACT COMMIT (rf2-uckc).

  ## Why this file exists

  `PopupView` became an `rf.fresco/defview` boundary under rf2-k97c.3, and the
  migration left its OBSERVATION, FRAME ROUTING and TEARDOWN unwitnessed. That
  is a gap in evidence, not an observed failure — and it is a real gap, not a
  theoretical one, because of what the node-lane rows actually assert.

  `app-db-segment-inspector-cljs-test` and `modals-aria-cljs-test` each hold a
  TEST-OWNED copy of the boundary's open gate and its three inner queries, and
  drive [[segment-inspector/popup-tree]] — the pure markup fn — with the values
  those copies read. `panels-mount-cljs-test` captures the provider/component
  vector without mounting it. So every one of those rows is green for a
  `PopupView` whose body returns `nil`, and green for one that stops reading the
  selected path and value: none of them runs the body. The head-kind rows prove
  the boundary is MARKED, which is a different property and a useful one.

  This file runs the body. It mounts what `shell.cljs` mounts — the public
  [[segment-inspector/Popup]] bridge, and [[segment-inspector/Popup-bridge]] for
  the second frame — inside a `frame-provider` naming an explicit non-default
  frame, and walks ONE lifecycle: open at a seeded path, a later path/value
  change, a real close click, then teardown.

  ## THE NODE LANE STRUCTURALLY CANNOT STAND IN FOR THIS

  `expand-tree` INVOKES a fn head, so `[x …]` and `(x …)` expand identically and
  a green node run says nothing about head legality. It also means a node-lane
  walker cannot enter an open dialog at all without substituting a Reagent twin
  for the boundary head — which is exactly what the sibling file does, and why
  its rows grade the markup rather than the boundary. The browser lane is the
  only instrument that runs `PopupView`'s body through React.

  ## Which claim each row answers

    S1  the bridge commits real dialog + value DOM, and the boundary's reads
        land in the frame the tree NAMED rather than the ambient one
    S2  a later path/value change reaches the committed DOM with no second
        call into the view — with a deaf control, so `it moved` means liveness
    S3  a REAL close click closes only THIS frame's popup; a distinct second
        frame, mounted through the other public name, is unchanged
    S4  closed state retains only the `open?` read — path, value and
        positioning release after quiescence
    S5  unmount returns the collector to baseline, and a reopen does not
        accumulate

  S3 is the row the captured dispatcher exists for. [[segment-inspector/
  popup-tree]] captures `rf/current-frame-id` at render time and carries
  `{:frame …}` on every dispatch, because a click fires AFTER React has popped
  the context tier. An ambient dispatch would raise `:rf.error/no-frame-context`
  (there is no `:rf/default` floor under EP-0002) or, worse, land in whichever
  frame happened to be ambient — and a single-frame row cannot tell those apart
  from correct behaviour. Two frames can.

  ## Instrument notes this file is built on, each one measured elsewhere first

  * A boundary reaches the collector's tables ASYNCHRONOUSLY, so a census taken
    in the mounting turn observes nothing and reports it as clean. S5 polls for
    its own boundary before reading the mounted census.
  * A Fresco boundary is not in Reagent's render queue, so the adapter's
    `:flush-render!` commits nothing of its update: the mount is committed with
    React's own `flushSync` and everything after it is a bounded poll.
  * The collector gives a cell whose last reader unmounts one macrotask of
    grace, and the entry reaper's horizon sits outside a bare `setTimeout 0`.
    `rf.fresco.test.runtime/quiesced!` is the runtime's own settling point and
    the only honest place to take a residue reading.
  * Every expectation below compares against a LITERAL. A door that compared
    what it read against what the code under test also read would agree with
    itself under a revert — both go nil, they match, and the row goes green on
    the defect."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-segment-inspector :as segment-inspector]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- the frames ----------------------------------------------------------

(def ^:private xray-frame
  "The frame the popup renders under — Xray's own, and explicitly NOT
  `:rf/default`. Every read the boundary takes must land here."
  :rf/xray)

(def ^:private other-xray-frame
  "A SECOND tool frame with its own popup, mounted in its own React root.

  It is what makes S3's close a claim about ROUTING rather than about a
  dispatch happening at all: a close that reached every popup on the page,
  or one that reached the ambient frame, is indistinguishable from a correct
  one until there are two frames to tell apart."
  ::xray-2)

(def ^:private app-frame
  "The ordinary application frame Xray is inspecting — the observed target,
  and S1's negative half. A boundary that took the AMBIENT scope instead of
  reading React context would put its reads in a frame like this one."
  ::app)

;; ---- the seeded host db ---------------------------------------------------

(def ^:private host-db
  "A nested host app-db, so the inspected path has something to project at
  more than one depth. `ada` and `42` are the literals the DOM rows compare
  against; neither appears anywhere else in this file's fixtures."
  {:cart {:items [{:id 7 :qty 1} {:id 22 :qty 3}]
          :gross 42}
   :user {:name "ada" :prefs {:theme :dark}}})

;; ---- the boundary's four reads, as query vectors --------------------------
;;
;; The sub-cache is keyed by the query vector itself, so these values ARE the
;; cache keys the reference-count rows read.

(def ^:private open?-q      [:rf.xray/segment-inspector-open?])
(def ^:private path-q       [:rf.xray/segment-inspector-path])
(def ^:private value-q      [:rf.xray/segment-inspector-value])
(def ^:private positioning-q [:rf.xray/modal-positioning])

(def ^:private gated-qs
  "The THREE reads that sit inside `PopupView`'s `when`. The closed-state
  cheapness contract is that these are not held while the popup is shut —
  `rf.fresco/sub` records its edge WHERE THE READ HAPPENS, so a branch not
  taken contributes no edge."
  [path-q value-q positioning-q])

;; ---- this file's own registrations ----------------------------------------
;;
;; THESE MUST SIT ABOVE `use-fixtures`, AND THE ORDER IS LOAD-BEARING RATHER
;; THAN STYLISTIC. `make-reset-runtime-fixture` captures the registrar
;; baseline ONCE, when the `use-fixtures` form is EVALUATED — i.e. at this
;; namespace's load — and folds that baseline back before each test. A
;; `reg-event` written BELOW that form is registered after the snapshot was
;; taken and is therefore stranded by the first reset.
;;
;; It fails quietly and at a distance. Measured on the first run of this file,
;; with all three of these below the fixture: the host seed dispatched into an
;; empty registrar, so the observed frame's db stayed empty, the value sub
;; resolved through `app-db-current+diff` to nil, and the DOM row reported
;; `Body text: "nil"` — which reads exactly like a boundary that had stopped
;; reading its value. The probe subscription went the same way and its
;; NON-VACUITY guard reported a ref-count of 0. One cause, two rows, and both
;; of them pointing at the production code rather than at the fixture.

(rf/reg-event ::seed-host-db (fn [_ [_ db]] {:db db}))

(rf/reg-event ::bump
  (fn [{:keys [db]} [_ n]] {:db (assoc db ::n n)}))

(rf/reg-sub ::n (fn [db _] (::n db)))

;; ---- fixture --------------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `cljs.test` refuses a FUNCTION fixture in a namespace carrying an
     ;; `async` row, and most rows here are async.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test` build
  loads this ns — `cljs-test$` matches it — but has no `js/document` to mount
  React into, so every row no-ops there and says so."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- setup ----------------------------------------------------------------

(defn- point-at-host!
  "Make `frame` a tool frame observing [[app-frame]].

  EP-0002 (rf2-bd4div) — the inspected target no longer defaults to
  `:rf/default`, so the host frame is selected explicitly or the value sub's
  observed-frame fallback projects from nil."
  [frame]
  (rf/with-frame frame
    (rf/dispatch-sync [:rf.xray/set-target-frame app-frame])))

(defn- setup!
  "Register Xray's handler graph, stand up the three frames, and seed the
  host db so the value sub has something real to project."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id xray-frame})
  (rf/make-frame {:id other-xray-frame})
  (rf/make-frame {:id app-frame})
  (rf/with-frame app-frame
    (rf/dispatch-sync [::seed-host-db host-db]))
  (point-at-host! xray-frame)
  (point-at-host! other-xray-frame)
  nil)

(defn- open-at!
  "Open the inspector in `frame` at `path`, synchronously."
  [frame path]
  (rf/with-frame frame
    (rf/dispatch-sync [:rf.xray/open-segment-inspector path])))

;; ---- mounting the SHIPPED bridge ------------------------------------------

(defn- mount-popup!
  "Mount the popup the way `shell.cljs` mounts it: the public callable as a
  Reagent hiccup head inside an `rf/frame-provider` scoping `frame`.

  Committed with `flushSync` because React 19's `root.render` is otherwise
  async and the first assertion would read an empty container.

  The 2-arity names WHICH public name to mount. Both are exercised: `Popup`
  is what the shell writes, `Popup-bridge` is what `panels/mount-segment-
  inspector!` writes, and they are supposed to be indistinguishable."
  ([frame] (mount-popup! frame segment-inspector/Popup))
  ([frame view]
   (let [container (.createElement js/document "div")
         root      (rdc/create-root container)]
     (.appendChild (.-body js/document) container)
     (react-dom/flushSync
       (fn []
         (rdc/render root [rf/frame-provider {:frame frame} [view]])))
     {:container container :root root})))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — where the collector
  releases a boundary's reads — have RUN by the time the next line reads. A
  bare `.unmount` merely schedules them."
  [{:keys [root container]}]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

;; ---- DOM probes -----------------------------------------------------------

(defn- testid [container id]
  (.querySelector container (str "[data-testid=\"" id "\"]")))

(defn- dialog     [c] (testid c "rf-xray-segment-inspector-dialog"))
(defn- title-node [c] (testid c "rf-xray-segment-inspector-title"))
(defn- body-node  [c] (testid c "rf-xray-segment-inspector-body"))
(defn- close-btn  [c] (testid c "rf-xray-segment-inspector-close"))

(defn- text-of [node] (some-> node (.-textContent)))

(defn- click!
  "Click `node`, answering false rather than throwing when it is nil.

  A raw `.click` on nil throws a `TypeError` out of an async block, where it
  surfaces as a timeout rather than as the missing affordance it is."
  [node]
  (if (some? node) (do (.click node) true) false))

(defn- settle
  "A promise resolving once every render pipeline on the page has had a real
  chance to commit — two animation frames and a macrotask. It exists for S2's
  DEAF CONTROL: an absence asserted immediately after an event is a race;
  asserted after this, it is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

;; ---- collector + sub-cache probes -----------------------------------------

(defn- cache-of [frame-id] @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- reader-edges-of
  "The collector's own reader slots for `query-v` in `frame-id`. Finer than the
  frame's sub-cache reference: the cell key is `[frame-kw query-v]`, and one
  reader slot is both the reference and the dependency edge."
  [frame-id query-v]
  (count (rf.fresco.test.runtime/cell-readers [frame-id query-v])))

(def ^:private empty-runtime
  "What the collector census reads when it holds nothing. Spelled out as a
  LITERAL rather than captured from a baseline read, so a runtime that held
  something at both ends cannot satisfy it by agreeing with itself."
  {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0})

;; ===========================================================================
;; S1 — the bridge paints a real dialog, and the reads land in the NAMED frame
;; ===========================================================================

(deftest s1-popup-bridge-paints-the-dialog-and-reads-in-the-named-frame
  (testing "rf2-uckc — mounting the shipped `Popup` bridge under a
            `frame-provider` naming an explicit non-default frame commits the
            real dialog, its header echoes the inspected path, its body renders
            the sliced VALUE, and every read the boundary took is in that frame
            rather than the ambient one."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            ;; A live read in the OTHER frame, so the negative half below is
            ;; measured with an instrument demonstrably able to see an entry
            ;; in that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            _ (open-at! xray-frame [:user])
            handle (mount-popup! xray-frame)
            container (:container handle)]
        (try
          ;; ---- the body RAN: DOM that only PopupView's body can produce ---
          (is (some? (dialog container))
              (str "the `Popup` bridge committed the real dialog through "
                   "`as-component`. A body returning nil — which every "
                   "node-lane row in this family tolerates — has nothing "
                   "here. Container HTML length: "
                   (count (or (.-innerHTML container) ""))))
          (is (some? (body-node container))
              "and the dialog's body node, so the value renderer was reached")
          (is (re-find #":user" (or (text-of (title-node container)) ""))
              (str "the header echoes the SELECTED path, so the boundary read "
                   "`:rf.xray/segment-inspector-path` rather than shipping a "
                   "constant. Title text: "
                   (pr-str (text-of (title-node container)))))
          (is (re-find #"ada" (or (text-of (body-node container)) ""))
              (str "and the body renders the value AT that path, so it read "
                   "`:rf.xray/segment-inspector-value` too. Body text: "
                   (pr-str (text-of (body-node container)))))

          ;; ---- the reads are where the tree SAID they would be ------------
          (is (pos? (ref-count-of xray-frame open?-q))
              (str "the open gate's read holds a reference in " xray-frame
                   "'s sub-cache — the frame the frame-provider NAMED. Cache "
                   "keys: " (pr-str (keys (cache-of xray-frame)))))
          (is (every? #(pos? (ref-count-of xray-frame %)) gated-qs)
              (str "and so does each of the three gated reads. Counts: "
                   (pr-str (mapv #(ref-count-of xray-frame %) gated-qs))))
          (is (every? #(zero? (ref-count-of app-frame %)) gated-qs)
              (str "and NOT in the application frame's — a boundary that took "
                   "the ambient scope instead of reading React context would "
                   "put them here. Counts: "
                   (pr-str (mapv #(ref-count-of app-frame %) gated-qs))))
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the application frame's cache is readable by this
               same instrument and does hold the probe's entry, so the three
               zeros above are an absence rather than a broken reader")
          (finally
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! handle)))))))

;; ===========================================================================
;; S2 — a later path/value change reaches the DOM, and the control is deaf
;; ===========================================================================

(deftest s2-a-later-path-change-reaches-the-committed-dom
  (testing "rf2-uckc — reopening at a NEW path re-renders the SAME mount: the
            header and body follow the change with no second call into the
            view. The control writes a key the popup does not read and the DOM
            does not move, so `it followed` means liveness rather than a commit
            that had simply not happened yet."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (open-at! xray-frame [:user])
        (let [handle    (mount-popup! xray-frame)
              container (:container handle)]
          (is (re-find #":user" (or (text-of (title-node container)) ""))
              "PRECONDITION: the first path is on screen, so the change below
               has somewhere to move from")
          (-> (js/Promise.resolve nil)
              (.then
                (fn [_]
                  ;; ---- the deaf control, FIRST ---------------------------
                  (rf/dispatch-sync [::bump 1] {:frame xray-frame})
                  (settle)))
              (.then
                (fn [_]
                  (is (re-find #":user" (or (text-of (title-node container)) ""))
                      (str "DEAF CONTROL: a write to a key the popup does not "
                           "read left the dialog exactly where it was, after a "
                           "full settle. A DOM that moves for any write at all "
                           "would make the phase below pass for a reason that "
                           "is not liveness. Title: "
                           (pr-str (text-of (title-node container)))))

                  ;; ---- the real change ----------------------------------
                  (open-at! xray-frame [:cart :gross])
                  (rf.test-support/poll-until
                    #(some-> (title-node container) (.-textContent)
                             (->> (re-find #":gross")))
                    {:label "the new path reached the committed dialog header"})))
              (.then
                (fn [_]
                  (is (re-find #":gross"
                               (or (text-of (title-node container)) ""))
                      "the header followed the path change through Fresco's own
                       collector — NOT through the installed adapter's reaction
                       machinery, which does not queue a boundary at all")
                  (is (re-find #"42" (or (text-of (body-node container)) ""))
                      (str "and the BODY followed the value change to the leaf "
                           "at that path, which is the read a body that kept "
                           "rendering stale content would fail. Body text: "
                           (pr-str (text-of (body-node container)))))
                  (is (some? (dialog container))
                      "and it is still the SAME mount rather than a remount,
                       which would not be liveness")))
              (.catch (fn [e]
                        (is false (str "S2 never settled: " (.-message e)
                                       " — dialog text: "
                                       (pr-str (text-of (dialog container)))))
                        nil))
              (.then (fn [_] (teardown! handle) (done)))))))))

;; ===========================================================================
;; S3 — a real close click closes only THIS frame's popup
;; ===========================================================================

(deftest s3-a-real-close-click-closes-only-this-frames-popup
  (testing "rf2-uckc — clicking the dialog's own ✕ closes the popup in the
            frame that rendered it, and leaves a second frame's popup — mounted
            in its own root through the other public name — untouched.

            This is the row the CAPTURED DISPATCHER exists for. The handler
            fires after React has popped the context tier, so `popup-tree`
            carries `{:frame …}` taken from `rf/current-frame-id` at render
            time. A single-frame row cannot distinguish that from an ambient
            dispatch that happened to land somewhere."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (open-at! xray-frame       [:user])
        (open-at! other-xray-frame [:cart])
        (let [subject (mount-popup! xray-frame)
              ;; The OTHER public name, so both bridges are exercised.
              other   (mount-popup! other-xray-frame
                                    segment-inspector/Popup-bridge)
              sc      (:container subject)
              oc      (:container other)]
          (is (some? (dialog sc))
              "PRECONDITION: the subject's dialog is open")
          (is (some? (dialog oc))
              (str "PRECONDITION: and so is the second frame's, mounted "
                   "through `Popup-bridge` — otherwise `unchanged` below is a "
                   "claim about an empty container"))
          (is (re-find #":cart" (or (text-of (title-node oc)) ""))
              "PRECONDITION: and the second frame is at its OWN path, so the
               two popups are reading their own frames' state")
          (-> (js/Promise.resolve nil)
              (.then
                (fn [_]
                  (is (click! (close-btn sc))
                      "the dialog renders a real ✕ button to click")
                  (rf.test-support/poll-until
                    #(nil? (dialog sc))
                    {:label "the close click emptied the subject's container"})))
              (.then
                (fn [_]
                  (is (nil? (dialog sc))
                      "the click closed THIS frame's popup — so the captured
                       dispatcher reached a frame, and reached the right one")
                  (is (some? (dialog oc))
                      (str "and the SECOND frame's popup is untouched. A close "
                           "that reached every popup on the page, or one that "
                           "resolved against an ambient scope, empties this "
                           "container too. Other container HTML length: "
                           (count (or (.-innerHTML oc) ""))))
                  (is (re-find #":cart" (or (text-of (title-node oc)) ""))
                      "and it is still showing its own path, so it was not
                       re-rendered from the subject frame's state either")))
              (.catch (fn [e]
                        (is false (str "S3 never settled: " (.-message e)
                                       " — subject HTML length: "
                                       (count (or (.-innerHTML sc) ""))))
                        nil))
              (.then (fn [_]
                       (teardown! subject)
                       (teardown! other)
                       (done)))))))))

;; ===========================================================================
;; S4 — closed state retains ONLY the open? read
;; ===========================================================================

(deftest s4-closing-releases-the-three-gated-reads
  (testing "rf2-uckc — the closed-state cheapness contract, measured on a live
            mount rather than argued from the source. After a real close the
            boundary still holds its open gate — it is mounted and must notice
            a reopen — and has RELEASED path, value and positioning, which sit
            inside the `when`.

            Read after `quiesced!`: the collector gives a cell whose last
            reader unmounts a macrotask of grace, so a reading taken any
            earlier reports a leak against a runtime behaving as documented."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (open-at! xray-frame [:user])
        (let [handle    (mount-popup! xray-frame)
              container (:container handle)]
          (-> (js/Promise.resolve nil)
              (.then
                (fn [_]
                  (is (some? (dialog container))
                      "PRECONDITION: the dialog is open")
                  (is (every? #(pos? (reader-edges-of xray-frame %)) gated-qs)
                      (str "NON-VACUITY: while OPEN, the collector holds a "
                           "reader edge on each of the three gated reads — "
                           "otherwise the release below is a claim about "
                           "nothing. Edges: "
                           (pr-str (mapv #(reader-edges-of xray-frame %)
                                         gated-qs))))
                  (is (click! (close-btn container))
                      "the dialog renders a real ✕ button to click")
                  (rf.test-support/poll-until
                    #(nil? (dialog container))
                    {:label "the close click emptied the container"})))
              (.then (fn [_] (rf.fresco.test.runtime/quiesced!)))
              (.then
                (fn [_]
                  (is (pos? (ref-count-of xray-frame open?-q))
                      (str "CLOSED: the open gate is STILL read — the popup is "
                           "mounted and has to notice a reopen. A release that "
                           "dropped this would make the popup un-reopenable. "
                           "Count: " (ref-count-of xray-frame open?-q)))
                  (is (every? #(zero? (ref-count-of xray-frame %)) gated-qs)
                      (str "CLOSED: and every one of the three gated reads is "
                           "released from the frame's sub-cache, because "
                           "`rf.fresco/sub` records its edge WHERE THE READ "
                           "HAPPENS and the `when` branch was not taken. "
                           "Counts: "
                           (pr-str (mapv #(ref-count-of xray-frame %)
                                         gated-qs))))
                  (is (every? #(zero? (reader-edges-of xray-frame %)) gated-qs)
                      (str "CLOSED: and no collector reader edge survives on "
                           "them either, so a release that dropped the "
                           "reference and left the cell wired is visible here "
                           "rather than hidden by the count above. Edges: "
                           (pr-str (mapv #(reader-edges-of xray-frame %)
                                         gated-qs))))))
              (.catch (fn [e]
                        (is false (str "S4 never settled: " (.-message e)))
                        nil))
              (.then (fn [_] (teardown! handle) (done)))))))))

;; ===========================================================================
;; S5 — unmount returns the collector to baseline, and a reopen does not grow
;; ===========================================================================

(deftest s5-unmount-returns-the-collector-to-baseline
  (testing "rf2-uckc — unmounting the popup returns every cell, reader edge,
            boundary registration and cached read-set entry it took, and a
            second open/close cycle takes the same numbers rather than higher
            ones.

            THE REOPEN IS THE HALF THAT CATCHES A RELEASE THAT RELEASES
            NOTHING. A first teardown can look clean for a reason that is not
            the teardown's — a page that never had anything to lose reads zero
            whatever the code does. Growth across the cycle is the signature,
            and it is only visible on the second mount."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (open-at! xray-frame [:user])
        (let [!handle  (atom nil)
              !mounted (atom nil)
              mount!   (fn []
                         (reset! !handle (mount-popup! xray-frame))
                         ;; A boundary reaches the collector's tables
                         ;; ASYNCHRONOUSLY. A census taken in the mounting turn
                         ;; observes nothing and reports it as clean, which is
                         ;; how a sabotage run on this epic came back green.
                         (rf.test-support/poll-until
                           #(pos? (:cells (rf.fresco.test.runtime/residue)))
                           {:label "the boundary reached the collector's tables"}))
              unmount! (fn []
                         (when-some [h @!handle]
                           (reset! !handle nil)
                           (teardown! h)))]
          (-> (rf.fresco.test.runtime/quiesced!)
              (.then
                (fn [_]
                  (is (= empty-runtime (rf.fresco.test.runtime/residue))
                      (str "BASELINE: the collector holds nothing before the "
                           "mount, so `back to baseline` below is a return to "
                           "ZERO rather than to a residue a leak could hide "
                           "inside. Got: "
                           (pr-str (rf.fresco.test.runtime/residue))))
                  (mount!)))
              (.then
                (fn [_]
                  (let [m (rf.fresco.test.runtime/residue)]
                    (reset! !mounted m)
                    (is (some? (dialog (:container @!handle)))
                        "NON-VACUITY: the dialog really is open, so the census
                         below is of an open popup rather than a closed one")
                    (is (pos? (:cells m))
                        (str "NON-VACUITY: the mount lit the collector up. "
                             "Census: " (pr-str m)))
                    (is (pos? (:boundaries m))
                        (str "NON-VACUITY: with registrations holding reader "
                             "slots. Census: " (pr-str m)))
                    (is (pos? (:edges m))
                        (str "NON-VACUITY: and dependency edges across the "
                             "subtree. Census: " (pr-str m))))
                  (unmount!)
                  (rf.fresco.test.runtime/quiesced!)))
              (.then
                (fn [_]
                  (is (= empty-runtime (rf.fresco.test.runtime/residue))
                      (str "the unmount returned the WHOLE census to zero — "
                           "every cell, edge, boundary registration and cached "
                           "read-set entry the popup took. Expected "
                           (pr-str empty-runtime) ", got "
                           (pr-str (rf.fresco.test.runtime/residue))))
                  (is (zero? (ref-count-of xray-frame open?-q))
                      (str "and the frame's own sub-cache reference for the "
                           "open gate is released too. Cache keys: "
                           (pr-str (keys (cache-of xray-frame)))))
                  (mount!)))
              (.then
                (fn [_]
                  (let [r     (rf.fresco.test.runtime/residue)
                        m     @!mounted
                        shape #(select-keys % [:cells :cell-refs
                                               :boundaries :edges])]
                    (is (= (shape m) (shape r))
                        (str "REOPEN: the census is identical across the "
                             "open/close/open cycle. First mount: "
                             (pr-str (shape m)) " — reopen: "
                             (pr-str (shape r))))
                    ;; Read-set entries are CACHED and reaped on their own
                    ;; horizon, so a reopen inside the window may legitimately
                    ;; reuse or rebuild them. What may never happen is growth.
                    (is (<= (:entries r) (:entries m))
                        (str "REOPEN: and the cached read-set entries did not "
                             "grow. First mount: " (:entries m) " — reopen: "
                             (:entries r))))
                  (unmount!)
                  (rf.fresco.test.runtime/quiesced!)))
              (.then
                (fn [_]
                  (is (= empty-runtime (rf.fresco.test.runtime/residue))
                      (str "the SECOND unmount returns to zero too, so the "
                           "release is a property of teardown rather than of "
                           "the first one happening to be clean. Got: "
                           (pr-str (rf.fresco.test.runtime/residue))))))
              (.catch (fn [e]
                        (is false (str "S5 never settled: " (.-message e)))
                        nil))
              (.then (fn [_] (unmount!) (done)))))))))
