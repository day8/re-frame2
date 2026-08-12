(ns re-frame.freehand.splitter-dom-cljs-test
  "FH-CTRL-019 — the first-party splitter under a real pointer.

  The cross-host rows prove the arithmetic both devices meet at. This one
  proves that a live `PointerEvent` and a live `KeyboardEvent` actually
  reach it, and that what they leave in app-db is the SAME THING. Three
  facts are unreachable from a structural render by construction:

  - **the equality itself.** A structural test can assert that
    `settle(fraction-at(px))` equals `intent-at(key-intent(k))`; it
    cannot assert that a real drag and a real key press leave app-db
    identical, because neither ever happens. The row is an
    accessibility-parity claim, and parity between two paths is only
    proven by walking both of them.
  - **the two clocks.** Offers are what a HOST delivers. Counting them
    against the intents a control accepted needs a host that delivers
    some — three real `pointermove`s producing one dispatch, read off the
    frame rather than off the test's bookkeeping.
  - **the phantom offer.** That a cancelled gesture cannot be moved by a
    move already in flight is a statement about ordering across a real
    event queue and a real React commit.

  ## What this suite is deliberate about

  **Nothing reaches into the control.** Every value asserted is read off
  the frame or off `document`, and every state change is an ordinary
  dispatch of the application's own event.

  **The track is measured, not assumed.** The fixture's pixels are
  OFFSETS into a 400-pixel track; the suite reads the track's real
  `getBoundingClientRect` and adds them. A test that hard-coded
  `clientX` would be asserting about the browser runner's page margins.

  **Capture is asked for and not required.** A synthetic `PointerEvent`
  creates no active pointer, so `setPointerCapture` raises here — which
  is exactly the host declining, the case the control treats as routing
  rather than authority. Every law below therefore runs on the ordinary
  bubbling path, which is the harder arm: nothing is being kept alive by
  a capture the assertions could hide behind.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix, and it also matches the node suites' broader regex, where it
  has no DOM to mount and says so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.splitter :as split]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     ;; Bookkeeping, not cleanup: it forgets the roots the PREVIOUS test
     ;; made so `ms/residue-clean!` reads only this one's, and tears
     ;; nothing down — a teardown here would run before each test and hide
     ;; exactly what that assertion looks for.
     :init-fn       (fn [] (ms/reset-ledger!))}))

(def ctrl-019 (conf/fixture :FH-CTRL-019))

(def ^:private fid        (:frame ctrl-019))
(def ^:private split-path (:split-path ctrl-019))
(def ^:private bs         (:bounds ctrl-019))

;; ---------------------------------------------------------------------------
;; The application — four one-line handlers, and that is the whole wiring
;; ---------------------------------------------------------------------------
;;
;; None of them knows anything about a pointer, a key, an orientation or a
;; pixel. THAT is the ergonomics claim, exercised rather than asserted: the
;; suite writes the same four handlers the namespace docstring shows.
;;
;; The counters are the row's evidence. `::previews` says how many offers
;; the control ACCEPTED, `::commits` how many reached the domain, and
;; `::starts` / `::cancels` bracket the gesture. Counting only the value
;; would make "three offers, one intent" indistinguishable from "three
;; intents that happened to agree".

(defn- reg! []
  (rf/reg-sub :layout/split (fn [db _] (get-in db split-path)))

  (rf/reg-event :layout/split-started
    (fn [{:keys [db]} _]
      {:db (-> db
               (update-in split-path split/start)
               (update ::starts (fnil inc 0)))}))

  (rf/reg-event :layout/split-moved
    (fn [{:keys [db]} [_ at]]
      {:db (-> db
               (update-in split-path split/move at bs)
               (update ::previews (fnil inc 0)))}))

  (rf/reg-event :layout/split-committed
    (fn [{:keys [db]} [_ at]]
      {:db (-> db
               (update-in split-path split/commit at bs)
               (update ::commits (fnil inc 0)))}))

  (rf/reg-event :layout/split-cancelled
    (fn [{:keys [db]} _]
      {:db (-> db
               (update-in split-path split/cancel)
               (update ::cancels (fnil inc 0)))})))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(def ^:private track-style
  "The fixture's track, as CSS. Content-box with no padding or border, so
  the measured width is the declared one."
  {:width "400px" :height "200px" :padding "0" :border "0"})

(v/defview live-pane
  "The everyday call site: preview wired, so the split tracks the pointer."
  [_]
  [:div {:id "track" :style track-style}
   [split/splitter {:id         "sep"
                    :split      (v/sub [:layout/split])
                    :bounds     bs
                    :on-start   [:layout/split-started]
                    :on-preview [:layout/split-moved]
                    :on-commit  [:layout/split-committed]
                    :on-cancel  [:layout/split-cancelled]}]])

(v/defview rtl-pane
  "The same, mirrored."
  [_]
  [:div {:id "track" :style track-style}
   [split/splitter {:id         "sep"
                    :rtl?       true
                    :split      (v/sub [:layout/split])
                    :bounds     bs
                    :on-start   [:layout/split-started]
                    :on-preview [:layout/split-moved]
                    :on-commit  [:layout/split-committed]
                    :on-cancel  [:layout/split-cancelled]}]])

(v/defview deferred-pane
  "The other everyday call site: NO preview. app-db sees nothing until the
  release, which is what a large layout wants."
  [_]
  [:div {:id "track" :style track-style}
   [split/splitter {:id        "sep"
                    :split     (v/sub [:layout/split])
                    :bounds    bs
                    :on-start  [:layout/split-started]
                    :on-commit [:layout/split-committed]}]])

;; ---------------------------------------------------------------------------
;; Browser seams
;; ---------------------------------------------------------------------------
;;
;; The mounted LIFECYCLE — the `act` boundary, the `[container root]` pair
;; and the teardown — comes from `re-frame.freehand.mount-support`, the one
;; facade the browser tier shares. What stays local is the POINTER, for the
;; reason the controls-kit suite keeps its typing local: it is this row's
;; subject rather than shared plumbing.

(def ^:private pointer-id 1)

(defn- pointer!
  "One real, bubbling `PointerEvent` of `kind` at client coordinates."
  ([node kind] (pointer! node kind 0 0))
  ([node kind x y]
   (.dispatchEvent node (js/PointerEvent.
                          kind
                          #js {:bubbles     true
                               :cancelable  true
                               :pointerId   pointer-id
                               :pointerType "mouse"
                               :isPrimary   true
                               :clientX     x
                               :clientY     y}))))

(defn- key!
  "One real, bubbling `keydown` carrying `k`."
  [node k]
  (.dispatchEvent node (js/KeyboardEvent.
                         "keydown"
                         #js {:bubbles true :cancelable true :key k})))

(defn- track-origin
  "The left edge of the real track, and its measured width. The fixture's
  pixels are OFFSETS into that box, so the suite adds rather than assumes
  — a hard-coded `clientX` would be an assertion about the page's margins."
  [container]
  (let [r (.getBoundingClientRect (.querySelector container "#track"))]
    [(.-left r) (.-width r)]))

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid (assoc-in {} split-path (split/init (:baseline ctrl-019))))
  fid)

(defn- render! [root view]
  (ms/act #(.render root (shell/provide-frame fid (fr/element [view {}])))))

(defn- app-db [] (frame/frame-app-db-value fid))
(defn- the-split [] (get-in (app-db) split-path))
(defn- at [] (:at (the-split)))
(defn- counter [k] (get (app-db) k 0))

(defn- settled
  "Wait for `pred` to hold, on a bounded deadline. Every site here is
  OUTSIDE the controlled-input door — the door is `onInput`/`onChange` on
  a controlled node and nothing else — so a pointer or key intent takes
  the ordinary batched path and is not readable on the statement after it."
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 2000 :interval-ms 5}))

(defn- repainted
  "Yield until React has committed what the last dispatch published.

  `settled` waits on the FRAME; a `data-*` attribute and the guard the
  next event will read come from the RENDER that follows it, which is a
  later task. Two yields rather than one because the update arrives
  outside React's own event, through the scheduler."
  []
  (-> (ms/tick!) (.then (fn [_] (ms/tick!)))))

(defn- teardown-clean!
  "The SUCCESS arm's last act: tear the mount down through the shared
  lifecycle, and assert that NOTHING it created survived.

  The residue assertion is what earns the record's `:residue :none`
  rather than asserting it. It runs AFTER teardown, over the facade's own
  books — every root this test created and what each left behind — so a
  root that failed to unmount reds HERE instead of contaminating the next
  suite in the process.

  It does NOT end the row. The single `done` sits at the TAIL of the
  chain with nothing after it, and this is one of the two steps that tail
  waits on; [[report-failure!]] is the other, and says why they cannot be
  the same step."
  [container root]
  (ms/destroy-root! container root)
  (ms/residue-clean! "FH-CTRL-019 — after teardown"))

(defn- report-failure!
  "The REJECTION arm: record the rejection against this row, tear the
  mount down — a rejected row must not leak its root either — and
  deliberately do NOT end the row. The chain's single trailing `done`
  does that.

  It reads no residue, and that asymmetry against [[teardown-clean!]] is
  load-bearing: a second failure attributed to the leak rule would bury
  the one that actually happened. It is also why the teardown stays
  written in both arms instead of riding the shared trailing step — the
  two arms do different amounts of work, so there is nothing here to
  hoist.

  **A rejection handler may not sit downstream of the step that calls
  `done`.** [[re-frame.freehand.mount-support/each-mode]] carries the
  long-form account (rf2-qpns): `run-block` hands `done` a continuation
  that runs the whole remainder of the run synchronously, so a `.catch`
  out past it claims a LATER namespace's throw as this row's failure and
  fires `done` a second time.

  One of these per row, on the OUTERMOST chain. Every nested chain here
  is returned into its parent, so a rejection anywhere inside reaches
  this handler without a handler of its own — which is what keeps the
  count at one rejection handler and one `done` per row (rf2-29ua)."
  [container root]
  (fn [e]
    (is false (str "the browser step rejected: " e))
    (ms/destroy-root! container root)
    nil))

;; ===========================================================================
;; THE ROW: a drag and a keystroke leave app-db identical
;; ===========================================================================

(defn- parity!
  "Drag one step, record where app-db landed; reset; press one key,
  record again; assert the two are EQUAL and that each is the value the
  fixture pinned.

  Both halves run against ONE mount, on one node, through one set of
  handlers — which is what makes the equality a statement about the
  control rather than about two test setups that happened to agree.

  `view` and `k` are the only things the two callers differ in: the
  mirrored call site presses the SAME key at the SAME pixel, and both
  must flip."
  [view k expected done]
  (reg!)
  (let [{:keys [baseline drag-xs drag-offers drag-accepted]} ctrl-019
        _ (seed!)
        [container root] (ms/create-root!)]
    (-> (render! root view)
        (.then
          (fn [_]
            (ms/live!)
            (let [sep      (.querySelector container "#sep")
                  [x0 w]   (track-origin container)
                  px       (fn [x] (+ x0 x))]
              (is (= 400 w)
                  "non-vacuous: the track really is the fixture's 400 pixels,
                   so the offsets below name the fractions it pinned")
              (is (= baseline (at)) "and the split starts at the baseline")

              ;; THE DRAG. Press, three real moves, release at the last.
              (pointer! sep "pointerdown" (px (first drag-xs)) 0)
              (doseq [x drag-xs] (pointer! sep "pointermove" (px x) 0))
              (is (= drag-offers (count drag-xs))
                  "non-vacuous: that many moves really were offered")

              (-> (settled #(= 1 (counter ::starts)) "the press starts the gesture")
                  (.then (fn [_] (repainted)))
                  (.then
                    (fn [_]
                      (is (= "true" (.getAttribute sep "data-dragging"))
                          "the element says a drag is live, which is how a skin
                           highlights without the control owning a class")
                      (pointer! sep "pointerup" (px (last drag-xs)) 0)
                      (settled #(= 1 (counter ::commits)) "the release commits")))
                  (.then (fn [_] (repainted)))
                  (.then
                    (fn [_]
                      (let [by-pointer (at)]
                        (is (= expected by-pointer) "the drag landed where the fixture says")
                        (is (= drag-accepted (counter ::previews))
                            "and THREE offered moves produced exactly ONE accepted
                             intent — the two clocks, delivered as real events")
                        (is (< drag-accepted drag-offers)
                            "non-vacuous: the reduction really reduced something")
                        (is (nil? (.getAttribute sep "data-dragging"))
                            "and the gesture is over")

                        ;; RESET, and then the same target by keyboard.
                        (-> (ms/act
                              #(frame/replace-app-db!
                                 fid (assoc-in {} split-path (split/init baseline))))
                            (.then
                              (fn [_]
                                (is (= baseline (at)) "back at the baseline")
                                (ms/live!)
                                (.focus sep)
                                (key! sep k)
                                (settled #(not= baseline (at)) "the keystroke commits")))
                            (.then
                              (fn [_]
                                (let [by-key (at)]
                                  (is (= by-pointer by-key)
                                      "THE ROW: one drag and one key press left app-db
                                       IDENTICAL")
                                  (is (= expected by-key)
                                      "and it is the split the fixture pinned")
                                  (is (not= baseline by-key)
                                      "non-vacuous: neither path passed by standing still")
                                  (is (= 0 (counter ::starts))
                                      "the keystroke reported no start — a keystroke is a
                                       WHOLE gesture, and that is the one asymmetry")
                                  (is (= 1 (counter ::commits))
                                      "and exactly one commit reached the domain")
                                  (teardown-clean! container root))))))))))))
        (.catch (report-failure! container root))
        (.then (fn [_] (done))))))

(deftest fh-ctrl-019-a-drag-and-a-keystroke-leave-app-db-identical
  (testing "Per FH-CTRL-019: the accessibility claim, walked. A real
            pointer is dragged one step and a real arrow key is pressed
            once, against the same node and the same four handlers, and
            the two are asserted EQUAL to each other rather than each
            equal to a pinned number. A control that quantized the
            keyboard differently from the pointer — the ordinary outcome
            when keyboard support arrives second — passes both halves of
            a pinned pair and fails this."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the pointer assertions")
      (async done (parity! live-pane (:parity-key ctrl-019) (:parity-at ctrl-019) done)))))

(deftest fh-ctrl-019-the-mirror-turns-the-pointer-and-the-arrows-together
  (testing "Per FH-CTRL-019: the sharper half. Under `:rtl?` the SAME
            pixel and the SAME key are used, and both answer the OTHER
            split — because a leading pane on the right shrinks as the
            separator moves right. If only the geometry were mirrored, or
            only the arrows, this row's equality fails while the row
            above still passes."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the pointer assertions")
      (async done (parity! rtl-pane (:parity-key ctrl-019) (:parity-rtl-at ctrl-019) done)))))

;; ===========================================================================
;; The call site that wires no preview
;; ===========================================================================

(deftest fh-ctrl-019-with-no-preview-app-db-sees-nothing-until-the-release
  (testing "Per FH-CTRL-019: the application chooses the stream, by wiring
            or not wiring one prop. With no `:on-preview` the same drag
            produces no intent at all until the pointer is released — and
            the release still commits WHERE THE POINTER WAS, not where it
            started, which is what makes the deferred call site complete
            rather than crippled.

            No mode, no flag, no scheduling verb: the difference between
            the two call sites is one line of the caller's own map."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the pointer assertions")
      (async done
        (reg!)
        (let [{:keys [baseline drag-xs parity-at deferred-previews
                      deferred-commits]} ctrl-019
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root deferred-pane)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [sep    (.querySelector container "#sep")
                        [x0 _] (track-origin container)
                        px     (fn [x] (+ x0 x))]
                    (pointer! sep "pointerdown" (px (first drag-xs)) 0)
                    (doseq [x drag-xs] (pointer! sep "pointermove" (px x) 0))
                    (-> (settled #(= 1 (counter ::starts)) "the press starts the gesture")
                        (.then (fn [_] (repainted)))
                        (.then
                          (fn [_]
                            (is (= deferred-previews (counter ::previews))
                                "not one move reached app-db")
                            (is (= baseline (at))
                                "so the split has not moved at all")
                            (pointer! sep "pointerup" (px (last drag-xs)) 0)
                            (settled #(= deferred-commits (counter ::commits))
                                     "the release commits")))
                        (.then
                          (fn [_]
                            (is (= parity-at (at))
                                "and it commits where the pointer WAS — the same split
                                 the live call site reached, in one event instead of
                                 three")
                            (is (= deferred-previews (counter ::previews))
                                "still no previews, after the whole gesture")
                            (is (not= baseline parity-at)
                                "non-vacuous: the commit really moved the split")
                            (teardown-clean! container root)))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The ending nobody asked for, and the offer behind it
;; ===========================================================================

(deftest fh-ctrl-019-a-cancelled-gesture-cannot-be-moved-by-an-offer-in-flight
  (testing "Per FH-CTRL-019: `pointercancel` is the browser taking the
            pointer — a scroll, a pinch, a palm — and it restores the
            baseline the gesture started from.

            The second half is the one no structural test can reach.
            Liveness is decided in the HANDLER against committed state,
            so a move dispatched just before the cancel legitimately
            lands just after it. Here that move is delivered explicitly,
            AFTER the cancel, naming a different split — and it changes
            nothing. A splitter that guarded liveness during render, or
            that kept a private `dragging?` flag, keeps moving here; it
            is the phantom drag every hand-rolled splitter has."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the pointer assertions")
      (async done
        (reg!)
        (let [{:keys [baseline drag-xs cancel-x cancelled-from-at after-cancelled-at
                      phantom-x phantom-would-be]} ctrl-019
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root live-pane)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [sep    (.querySelector container "#sep")
                        [x0 _] (track-origin container)
                        px     (fn [x] (+ x0 x))]
                    (pointer! sep "pointerdown" (px (first drag-xs)) 0)
                    (pointer! sep "pointermove" (px cancel-x) 0)
                    (-> (settled #(= cancelled-from-at (at)) "the drag reaches its position")
                        (.then (fn [_] (repainted)))
                        (.then
                          (fn [_]
                            (is (= cancelled-from-at (at))
                                "non-vacuous: there really is a moved split to abandon")
                            (is (not= baseline cancelled-from-at))
                            (pointer! sep "pointercancel" (px cancel-x) 0)
                            (settled #(= 1 (counter ::cancels)) "the cancel lands")))
                        (.then (fn [_] (repainted)))
                        (.then
                          (fn [_]
                            (is (= after-cancelled-at (at))
                                "the baseline the gesture started from is back")
                            (is (false? (:dragging? (the-split)))
                                "and no gesture is live")

                            ;; THE PHANTOM. A move naming a DIFFERENT split,
                            ;; delivered after the ending.
                            (let [previews-before (counter ::previews)]
                              (pointer! sep "pointermove" (px phantom-x) 0)
                              (-> (settled #(< previews-before (counter ::previews))
                                           "the phantom offer really was dispatched")
                                  (.then
                                    (fn [_]
                                      (is (= after-cancelled-at (at))
                                          "and it moved NOTHING — the handler decided
                                           against committed state, so the cancel beat
                                           the offer rather than racing it")
                                      (is (not= phantom-would-be after-cancelled-at)
                                          "non-vacuous: that offer names a different
                                           split, so a control that accepted it would
                                           be visibly wrong here")
                                      (is (= 0 (counter ::commits))
                                          "and a cancelled gesture committed nothing")
                                      (teardown-clean! container root)))))))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; The bound, under a real pointer
;; ===========================================================================

(deftest fh-ctrl-019-a-pointer-dragged-past-the-end-stops-at-the-bound
  (testing "Per FH-CTRL-019: the bounds are the caller's, and a pointer
            that leaves the track entirely still names a split inside
            them. The second offer past the bound is the row that
            matters: it is ACCEPTED as nothing, because the settled value
            is the one already on screen — which is the same reduction
            that keeps a 240-hertz drag from being a 240-hertz event
            stream, seen at its edge."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the pointer assertions")
      (async done
        (reg!)
        (let [{:keys [drag-xs beyond-x beyond-at]} ctrl-019
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root live-pane)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [sep    (.querySelector container "#sep")
                        [x0 _] (track-origin container)
                        px     (fn [x] (+ x0 x))]
                    (pointer! sep "pointerdown" (px (first drag-xs)) 0)
                    (pointer! sep "pointermove" (px beyond-x) 0)
                    (-> (settled #(= beyond-at (at)) "the drag pins at the bound")
                        (.then (fn [_] (repainted)))
                        (.then
                          (fn [_]
                            (is (= beyond-at (at)) "clamped to the caller's maximum")
                            (let [accepted (counter ::previews)]
                              ;; Further, and even further. Neither is accepted.
                              (pointer! sep "pointermove" (px (+ beyond-x 200)) 0)
                              (pointer! sep "pointermove" (px (+ beyond-x 400)) 0)
                              (pointer! sep "pointerup" (px (+ beyond-x 400)) 0)
                              (-> (settled #(= 1 (counter ::commits)) "the release commits")
                                  (.then (fn [_] (repainted)))
                                  (.then
                                    (fn [_]
                                      (is (= accepted (counter ::previews))
                                          "two more offers past the bound produced NO
                                           further intent")
                                      (is (= beyond-at (at))
                                          "and the commit carries the bound, not the
                                           pixel")
                                      (is (= beyond-at (:max bs))
                                          "non-vacuous: the fixture's bound really is
                                           the caller's maximum, so the clamp is the
                                           thing being seen")
                                      (is (= (str (Math/round (* 100 beyond-at)))
                                             (.getAttribute sep "aria-valuenow"))
                                          "and the screen reader is told the clamped
                                           value, not the pointer's")
                                      (teardown-clean! container root)))))))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))
