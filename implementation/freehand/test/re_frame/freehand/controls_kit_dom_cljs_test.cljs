(ns re-frame.freehand.controls-kit-dom-cljs-test
  "FH-CTRL-018 — the first-party controls in a real browser.

  The cross-host rows prove what the kit DECIDES. This one proves what a
  browser does to a live node while the decision is taken, and the two are
  not the same claim. Three of the kit's facts are unreachable from a
  structural render by construction:

  - **the caret.** A controlled input's value round-trips through
    application state, so every keystroke is a chance to put the caret
    back where the last render left it rather than where the user put it.
    A structural tree has no caret to move.
  - **the composing Enter.** `c/key-intent` is pure and its table is
    proven by calling it, but WHICH scalars a host keyboard event yields
    is a browser fact — and the whole failure mode is a browser that
    reports the composition through `keyCode` 229 rather than through
    `isComposing`. A structural test cannot tell a correct reader from one
    that trusts a flag this engine does not set.
  - **node identity across a reset.** A reset that remounted would put the
    right text on the WRONG node, destroying focus and any composition in
    flight, and every structural assertion in the cross-host suite would
    still be green.

  ## What this suite is deliberate about

  **The substrate is the REACTIVE, React-shaped one.** The value is edited
  through a controlled input, so the correction has to reach the host
  INSIDE the event — the same reason the controlled-input suite runs on
  the React-hook spine.

  **Nothing reaches into the controls.** Every value here is read off
  `document`, and every state change is an ordinary dispatch of the
  application's own event.

  **One composition signal per press.** `c/composing?` ORs the standard
  `isComposing` flag together with the `keyCode` 229 fallback, and an event
  carrying BOTH cannot fail for either of them individually — an
  implementation consulting only `isComposing`, which is precisely the one
  that breaks on the engines WebKit bug 165004 shipped, would stay green
  against it. So the two
  withholding rows below press ONE signal each over one identical
  procedure, leaving the other scalar at the plain Enter's value, and each
  is falsifiable on its own.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix, and it also matches the node suites' broader regex, where it has
  no DOM to mount and says so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.controls :as c]
            [re-frame.freehand.form :as form]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
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

(def ctrl-018 (conf/fixture :FH-CTRL-018))

(def ^:private fid       (:frame ctrl-018))
(def ^:private form-path (:form-path ctrl-018))
(def ^:private amount    (:amount-path ctrl-018))

;; ---------------------------------------------------------------------------
;; The application — ordinary re-frame over the pure transitions
;; ---------------------------------------------------------------------------
;;
;; Three registrations for the whole form, and none of them knows anything
;; about a control. That IS the ergonomics claim, exercised rather than
;; asserted: the browser suite writes the same wiring the guide does.

(defn- reg! []
  (rf/reg-sub :editor/field
    (fn [db [_ path]] (form/field (get-in db form-path) path)))

  (rf/reg-event :editor/edited
    (fn [{:keys [db]} [_ path text]]
      {:db (update-in db form-path form/edit path text)}))

  ;; The COMMIT, fenced in the handler against committed state. A commit
  ;; whose generation the caller has replaced produces nothing.
  ;; TWO counters, not one, and that is the whole credibility of the
  ;; composing-Enter assertion below. `::attempts` says the control
  ;; DISPATCHED; `::commits` says the fence let it through. With only the
  ;; second, "a composing Enter commits nothing" is equally green when the
  ;; keydown listener was never wired at all — the shape a browser test
  ;; passes for the wrong reason.
  (rf/reg-event :editor/committed
    (fn [{:keys [db]} [_ path revision]]
      (let [f  (get-in db form-path)
            db (update db ::attempts (fnil inc 0))]
        (if (v/controller-current? revision (form/reset-key f path))
          {:db (-> db
                   (update ::commits (fnil inc 0))
                   (assoc ::committed (get-in f (into [:draft] path))))}
          {:db db}))))

  ;; The caller's REJECTION: an ordinary application event that resets the
  ;; leaf, which restores the baseline and advances the generation.
  (rf/reg-event :editor/rejected
    (fn [{:keys [db]} [_ path]]
      {:db (update-in db form-path form/reset path)})))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview plain-field [_]
  [c/field {:id      "plain"
            :field   (v/sub [:editor/field amount])
            :on-edit [:editor/edited amount]}])

(v/defview committing-field [_]
  [c/buffered-field {:id        "buffered"
                     :field     (v/sub [:editor/field amount])
                     :on-edit   [:editor/edited   amount]
                     :on-commit [:editor/committed amount]}])

;; ---------------------------------------------------------------------------
;; Browser seams
;; ---------------------------------------------------------------------------
;;
;; The mounted LIFECYCLE — the `act` boundary, the `[container root]` pair
;; and the teardown — comes from `re-frame.freehand.mount-support`, the one
;; facade the browser tier shares (rf2-n9rzw). This file used to carry its
;; own byte-identical copy of each, which is how it came to tear down
;; without ever asserting what survived.
;;
;; What stays local is the TYPING, and it stays local because it is not the
;; same as the facade's: an `input` here carries `isComposing false`
;; explicitly, and a keystroke is delivered as an insert AT THE CARET
;; rather than an append, because the caret is one of the three facts this
;; suite exists to measure.

(defn- native-value-setter []
  (.-set (js/Object.getOwnPropertyDescriptor
           (.-prototype js/HTMLInputElement) "value")))

(defn- set-native-value! [node s]
  (.call (native-value-setter) node s))

(defn- fire-input! [node data]
  (.dispatchEvent node (js/InputEvent. "input"
                                       #js {:bubbles     true
                                            :cancelable  false
                                            :data        data
                                            :isComposing false})))

(defn- insert-at!
  "Insert `ch` at offset `at`, the way a browser does it: the text grows at
  the caret, the caret follows it, and then `input` fires."
  [node at ch]
  (let [text (.-value node)]
    (.focus node)
    (.setSelectionRange node at at)
    (set-native-value! node (str (subs text 0 at) ch (subs text at)))
    (.setSelectionRange node (inc at) (inc at))
    (fire-input! node ch)))

(defn- type-string!
  "Append `s` one character at a time, at the end, the way a user produces
  it — so a dropped keystroke shows up as a SHORT STRING rather than as a
  timing observation."
  [node s]
  (doseq [ch s]
    (insert-at! node (count (.-value node)) (str ch))))

(defn- press-enter!
  "A real `keydown` on `node`, carrying the two composition scalars
  INDEPENDENTLY: `:composing?` becomes `isComposing` and `:key-code`
  becomes `keyCode`, with no coupling between them.

  That independence is the point. `c/composing?` ORs the standard
  `isComposing` flag together with the `keyCode` 229 fallback that covers
  the engines which report the candidate-accepting Enter with `isComposing`
  FALSE (WebKit bug 165004), and an event carrying BOTH cannot tell a
  reader of both from a reader of either — so the implementation this row
  exists to catch, the one that consults only `isComposing`, would stay
  green. Every
  press below sets ONE signal and leaves the other at the plain Enter's
  value."
  [node {:keys [composing? key-code]}]
  (.dispatchEvent node (js/KeyboardEvent.
                         "keydown"
                         #js {:bubbles     true
                              :cancelable  true
                              :key         (:commit-key ctrl-018)
                              :isComposing composing?
                              :keyCode     key-code})))

(defn- plain-enter
  "The scalars of an Enter with NO composition in flight: the flag false and
  the ordinary `keyCode` 13. The positive barrier every withholding proof
  below closes with."
  []
  {:composing? false :key-code (:plain-key-code ctrl-018)})

(defn- caret [node] [(.-selectionStart node) (.-selectionEnd node)])

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid (assoc-in {} form-path (form/init (:baseline ctrl-018))))
  fid)

(defn- render! [root view]
  (ms/act #(.render root (shell/provide-frame fid (fr/element [view {}])))))

(defn- app-db [] (frame/frame-app-db-value fid))
(defn- the-form [] (get-in (app-db) form-path))
(defn- commits [] (get (app-db) ::commits 0))
(defn- attempts [] (get (app-db) ::attempts 0))
(defn- committed [] (get (app-db) ::committed))

(defn- settled
  "Wait for `pred` to hold, on a bounded deadline.

  A BATCHED event site lands on a later tick — the synchronous door is
  `onInput`/`onChange` on a controlled node and nothing else — so a
  commit dispatched by a blur or a keydown is not readable on the
  statement after it. `poll-until` waits for the STATE rather than for a
  duration, so the assertion is bounded by a deadline it never normally
  reaches instead of by a sleep somebody has to tune."
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 2000 :interval-ms 5}))

(defn- teardown-clean!
  "The SUCCESS arm's last act: tear the mount down through the shared
  lifecycle, and assert that NOTHING it created survived.

  The residue assertion is what earns the row's claim rather than asserting
  it. It runs AFTER teardown, over the facade's own books — every root this
  test created, and what each left behind — so a root that failed to
  unmount reds HERE instead of contaminating the next suite in the process.

  It does NOT end the row. The single `done` sits at the TAIL of the chain
  with nothing after it, and this is one of the two steps that tail waits
  on; [[report-failure!]] is the other, and says why they cannot be the
  same step."
  [container root]
  (ms/destroy-root! container root)
  (ms/residue-clean! "FH-CTRL-018 — after teardown"))

(defn- report-failure!
  "The REJECTION arm: record the rejection against this row, tear the mount
  down — a rejected row must not leak its root either — and deliberately do
  NOT end the row. The chain's single trailing `done` does that.

  It reads no residue, and that asymmetry against [[teardown-clean!]] is
  load-bearing: a second failure attributed to the leak rule would bury the
  one that actually happened. It is also why the teardown stays written in
  both arms instead of riding the shared trailing step — the two arms do
  different amounts of work, so there is nothing here to hoist.

  **A rejection handler may not sit downstream of the step that calls
  `done`.** [[re-frame.freehand.mount-support/each-mode]] carries the
  long-form account (rf2-qpns): `run-block` hands `done` a continuation
  that runs the whole remainder of the run synchronously, so a `.catch` out
  past it claims a LATER namespace's throw as this row's failure and fires
  `done` a second time.

  This file read as ZERO to the campaign's scanner, which looks for a chain
  step containing a literal `(done)`. Both arms took `done` through a
  helper, so no step here held one (rf2-29ua)."
  [container root]
  (fn [e]
    (is false (str "the browser step rejected: " e))
    (ms/destroy-root! container root)
    nil))

;; ===========================================================================
;; FH-CTRL-018 — typing, the caret, the composing Enter, and the reset
;; ===========================================================================

(deftest fh-ctrl-018-sustained-typing-through-a-field-drops-nothing
  (testing "Per FH-CTRL-018: characters typed one at a time through
            `c/field` reach the form's draft leaf and come back on the
            node, character for character. Each keystroke round-trips
            through application state, so a dropped one is a SHORT
            STRING here rather than a timing observation."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the typing assertions")
      (async done
        (reg!)
        (let [{:keys [baseline typed value-after-typing]} ctrl-018
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root plain-field)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [field (.querySelector container "#plain")]
                    (is (= (:amount baseline) (.-value field))
                        "the control starts on the baseline")
                    (type-string! field typed)
                    (is (= value-after-typing (.-value field))
                        "every character arrived, in order")
                    (is (= value-after-typing (get-in (the-form) (into [:draft] amount)))
                        "and the value on screen IS the form's draft leaf")
                    (is (not= (:amount baseline) value-after-typing)
                        "non-vacuous: the typing really moved the value")
                    (is (= (count value-after-typing) (first (caret field)))
                        "with the caret at the end, where the user left it")
                    (teardown-clean! container root))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

(deftest fh-ctrl-018-a-mid-string-insert-leaves-the-caret-where-the-user-put-it
  (testing "Per FH-CTRL-018: the caret is placed INSIDE the text and one
            character arrives. The caret must follow the insert rather
            than jump to either end — the failure a hand-rolled controlled
            input produces on every keystroke, and one no structural tree
            can show."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the caret assertions")
      (async done
        (reg!)
        (let [{:keys [insert-at insert-ch value-after-insert caret-after-insert]} ctrl-018
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root plain-field)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [field (.querySelector container "#plain")]
                    (insert-at! field insert-at insert-ch)
                    (is (= value-after-insert (.-value field))
                        "the insert landed where the caret was")
                    (is (= [caret-after-insert caret-after-insert] (caret field))
                        "and the caret did not jump on the way through the round trip")
                    (is (not= 0 caret-after-insert)
                        "non-vacuous: the expected caret is not the position a reset
                         to the start would also produce")
                    (is (not= (count value-after-insert) caret-after-insert)
                        "nor the position a reset to the END would produce")
                    (teardown-clean! container root))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

(deftest fh-ctrl-018-a-blur-commits-the-draft
  (testing "Per FH-CTRL-018: the blur commits the draft the user could see.
            The commit site is OUTSIDE the synchronous door by
            construction — the door is `onInput`/`onChange` on a
            controlled node — so it takes the ordinary BATCHED path and
            lands on a later tick. Nothing about it needs to be
            synchronous: the value it commits is already in the frame."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the blur assertions")
      (async done
        (reg!)
        (let [{:keys [insert-at insert-ch value-after-insert commits-after-plain-enter
                      committed-value]} ctrl-018
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root committing-field)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [field (.querySelector container "#buffered")]
                    (insert-at! field insert-at insert-ch)
                    (is (= value-after-insert (.-value field))
                        "non-vacuous: there really is a draft to commit")
                    (is (= 0 (commits)) "and nothing has committed yet")
                    (.blur field)
                    ;; The nested chain is RETURNED into the outer one, so its
                    ;; rejections reach the outer `.catch` and it needs none of
                    ;; its own — one rejection handler and one `done` per row.
                    (-> (settled #(= commits-after-plain-enter (commits)) "the blur commits")
                        (.then (fn [_]
                                 (is (= commits-after-plain-enter (commits))
                                     "the blur committed")
                                 (is (= committed-value (committed))
                                     "carrying the draft the user could see")
                                 (teardown-clean! container root)))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

(defn- exactly-one-commit-attempt!
  "Press a composing Enter carrying `composing-press`'s scalars, then a
  PLAIN Enter, on one buffered node, and assert that EXACTLY ONE commit
  attempt arrives.

  ## Why the plain Enter is not optional

  'A composing Enter commits nothing' is, on its own, equally green when
  the keydown listener was never wired at all — and the batched commit path
  lands on a LATER TICK, so a synchronous read after the composing press
  cannot tell 'declined' from 'not yet'. Pressing the plain Enter
  afterwards and waiting for ITS attempt to land is a positive barrier: the
  composing press had the same queue, the same node and more time, so an
  attempt count of exactly one is a statement about the composing press
  rather than about the clock.

  ## Why this is a shared helper

  Because the two callers differ in EXACTLY ONE thing — which composition
  signal their event carries — and nothing else. `c/composing?` ORs those
  signals together, so a proof that presses both at once cannot fail for
  either of them individually; giving each signal its own press over one
  identical procedure is what makes each OR branch falsifiable on its own."
  [composing-press done]
  (reg!)
  (let [{:keys [insert-at insert-ch value-after-insert commits-after-composing-enter
                value-after-composing-enter commits-after-plain-enter
                committed-value]} ctrl-018
        _ (seed!)
        [container root] (ms/create-root!)]
    (-> (render! root committing-field)
        (.then
          (fn [_]
            (ms/live!)
            (let [field (.querySelector container "#buffered")]
              (insert-at! field insert-at insert-ch)
              (is (= value-after-insert (.-value field))
                  "non-vacuous: there really is a draft to commit or withhold")
              (is (= 0 (attempts)) "and nothing has been dispatched yet")

              ;; THE COMPOSING ENTER — the IME's, not the form's — and then
              ;; the plain one, on the same node.
              (press-enter! field composing-press)
              (press-enter! field (plain-enter))

              (-> (settled #(= commits-after-plain-enter (attempts))
                           "the plain Enter's commit attempt lands")
                  (.then
                    (fn [_]
                      (is (= commits-after-plain-enter (attempts))
                          "EXACTLY ONE attempt arrived from two presses — the
                           composing Enter dispatched nothing at all, rather
                           than dispatching a commit the fence refused")
                      (is (= commits-after-plain-enter (commits))
                          "and the plain Enter's attempt committed")
                      (is (= committed-value (committed))
                          "carrying the draft the user could see")
                      (is (= value-after-composing-enter (.-value field))
                          "and the composing Enter discarded nothing either — a
                           commit withheld is not a value thrown away")
                      (is (not= commits-after-composing-enter
                                commits-after-plain-enter)
                          "non-vacuous: the fixture's two answers really differ")
                      (teardown-clean! container root)))))))
        (.catch (report-failure! container root))
        (.then (fn [_] (done))))))

(deftest fh-ctrl-018-a-composing-enter-commits-nothing-in-a-real-browser
  (testing "Per FH-CTRL-018: an Enter carrying the STANDARD `isComposing`
            flag — and nothing else — commits nothing, while the plain
            Enter behind it does. The pure table is proven by calling
            `c/key-intent`; what THIS proves is that the reader beneath it
            takes that flag off a real host keyboard event as the browser
            and React actually deliver it.

            `keyCode` is the plain Enter's 13, deliberately, so the press
            CANNOT be withheld by the `keyCode` 229 sentinel the sibling
            row owns. The flag is the only thing standing between this
            press and a commit."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the composition assertions")
      (async done
        (exactly-one-commit-attempt!
          {:composing? true :key-code (:plain-key-code ctrl-018)}
          done)))))

(deftest fh-ctrl-018-a-keycode-229-enter-commits-nothing-with-iscomposing-false
  (testing "Per FH-CTRL-018: the COMPATIBILITY FALLBACK, on its own.
            `keyCode` 229 is what the UI Events legacy algorithm assigns
            while an input method is processing a keydown, and `isComposing`
            here is FALSE — on the native event and therefore on the
            synthetic one too. That pairing is WebKit bug 165004, fixed in
            April 2026 (bug 311717); the fallback stays for engines already
            deployed without the fix.

            This is the press the row's compatibility claim was made about
            and the one it did not previously exercise: with both signals
            set on one event, an implementation reading only `isComposing`
            — the implementation the fallback exists to beat — stayed
            green. Here 229 is the only signal there is, so it is the only
            thing that can withhold the commit."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the composition assertions")
      (async done
        (exactly-one-commit-attempt!
          {:composing? false :key-code (:composing-key-code ctrl-018)}
          done)))))

(deftest fh-ctrl-018-a-reset-restores-the-baseline-on-the-same-node
  (testing "Per FH-CTRL-018: the caller rejects the draft by reasserting
            the value it already had and advancing the leaf's generation.
            The baseline comes back on the SAME node, still focused —
            which is exactly what a key-remount destroys and what no
            structural assertion can see — and the commit that follows
            carries the restored value rather than the refused one."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the reset assertions")
      (async done
        (reg!)
        (let [{:keys [insert-at insert-ch value-after-insert reasserted-value
                      next-reset-key value-after-reset]} ctrl-018
              _ (seed!)
              [container root] (ms/create-root!)]
          (-> (render! root committing-field)
              (.then
                (fn [_]
                  (ms/live!)
                  (let [field  (.querySelector container "#buffered")
                        before field]
                    (insert-at! field insert-at insert-ch)
                    (is (= value-after-insert (.-value field))
                        "non-vacuous: the draft is live on screen")
                    (is (= reasserted-value value-after-reset)
                        "non-vacuous: the caller stands by the value it already had,
                         so nothing derived from the VALUE could observe this")

                    (-> (ms/act #(rf/dispatch-sync [:editor/rejected amount] {:frame fid}))
                        (.then
                          (fn [_]
                            (is (= value-after-reset (.-value field))
                                "the baseline is back on screen")
                            (is (not= value-after-insert value-after-reset)
                                "non-vacuous: the draft really did leave the display")
                            (is (identical? before (.querySelector container "#buffered"))
                                "and it came back on the SAME node — restored, not remounted")
                            (is (identical? before (.-activeElement js/document))
                                "which still has focus")
                            (is (= next-reset-key (form/reset-key (the-form) amount))
                                "the leaf's generation advanced")

                            ;; A commit now speaks for the NEW generation, and
                            ;; what it carries is the value on screen.
                            (ms/live!)
                            (press-enter! field (plain-enter))
                            (settled #(some? (committed)) "the post-reset commit lands")))
                        (.then
                          (fn [_]
                            (is (= value-after-reset (committed))
                                "the commit that follows carries the restored value")
                            (is (not= value-after-insert (committed))
                                "non-vacuous: it is not the draft the caller refused")
                            (teardown-clean! container root)))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))
