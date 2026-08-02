(ns re-frame.bench.hicasso.arm1.controlled-burst-dom-cljs-test
  "THE BURST WITNESS — shape 5's \"zero dropped keystrokes\", as N
  keystrokes rather than one (rf2-2rtt6.55).

  Every echo witness in `arm1/controlled_grid_dom_cljs_test` fires ONE
  synthetic input event carrying the whole value — `(type-into! n
  \"hello\")`, one event, value `\"hello\"`. That proves the door is
  synchronous for *a* keystroke. It does not prove that N keystrokes
  arriving back-to-back all land, which is the failure mode the charter's
  clause names: the one Reagent needed `reagent/impl/input.cljs` for, and
  the one a batching or deferring substrate actually exhibits. Here
  `h`,`e`,`l`,`l`,`o` are FIVE events, dispatched on consecutive lines of
  one synchronous turn, with no yield of any kind between them — and
  every one of them must land, in order, before the next one fires.

  ## What one burst keystroke is

  [[keystroke!]] simulates a keystroke the way the grid file's
  `type-into!` does and the way the browser does: the field mutates FIRST
  (through `HTMLInputElement.prototype`'s own `value` setter, past
  React's per-instance change tracker), the caret moves to after the
  inserted character, and an `input` event fires SECOND. The event is a
  real `InputEvent` carrying what a trusted keystroke's carries —
  `inputType \"insertText\"`, the character as `data`, `bubbles true` —
  and [[the-events-a-burst-carries-are-verified-on-the-native-event]]
  asserts that inventory on the constructed event BEFORE any row trusts a
  green, which is the dead-`isComposing` lesson
  (`reacts-synthetic-keyboard-event-drops-is-composing`) applied to this
  file's own instrument.

  Two divergences from a trusted event, stated rather than hidden:

  - **`cancelable` is `false`, and the bead's word \"cancelable\" is
    answered here.** Per the UI Events / Input Events specs the
    browser's own `input` event is never cancelable — it announces a
    mutation that has already happened; `beforeinput` is the cancelable
    member of the pair. A burst of `cancelable: true` input events would
    be a green over events unlike any the browser sends, so this witness
    dispatches exactly what a browser dispatches and asserts the flag's
    real value.
  - **`isTrusted` is `false`**, necessarily — no synthetic event can
    carry it. That is the same instrument limit every row of the grid
    suite rides; React's root delegation does not consult it on the
    input path, and the first row below shows the signal is live
    end-to-end (the event moves the model and runs the body) rather than
    assuming it.

  ## What a burst reading is, and why reading between events is not a
  ## yield

  [[burst!]] banks, ON THE LINE AFTER each `dispatchEvent` returns, the
  field's value, the model's value and the caret. Those are synchronous
  property reads in the same turn — `dispatchEvent` runs every handler
  before it returns, and between two dispatches there is no task
  boundary, no microtask checkpoint, nothing the browser can interleave.
  The trajectory they form is the ordering claim itself: each keystroke
  observed to land on its predecessor's outcome, so the final value is
  what the SEQUENCE implies and not merely what the last event carried.
  [[a-burst-lands-with-nothing-at-all-between-the-events]] then drops
  even the readings — a bare loop of mutate-and-dispatch — so the
  endpoint claim demonstrably does not depend on the instrument's reads.

  ## Why the interesting bursts are the refusing and normalising ones

  The converge (`front.controlled/install!`, rf2-fki5d) ends every
  change handler by flushing the synchronous door's commit and restoring
  value and caret. A burst is k of those in one turn, each building on
  the last one's restore:

  - a REFUSED keystroke's restore must leave the field exactly where the
    NEXT keystroke in the burst expects it — the model holds only the
    accepted subset, and an accepted keystroke after a refusal lands on
    the restored state, not on the refused character;
  - a NORMALISING model rewrites the field on EVERY keystroke, so the
    caret arithmetic (offset from the END of the string) has to survive
    k successive restores, including the length-changing one
    (`1234` → `1,234` mid-burst);
  - a MID-STRING burst is where a broken converge changes the STRING and
    not just the caret: throw the caret to the end after keystroke one
    and keystroke two lands in the wrong place — `ad` + `b`,`c` at
    position 1 comes out `abdc` instead of `abcd`. That is a dropped
    position, the sequence-not-last-event claim, and the row a
    deferring door cannot fake.

  ## Runtime

  `-dom-cljs-test`: real DOM only; under `:node-test` every claim
  degrades to a stated skip. No `act` anywhere, no `flushSync` in any
  assertion path — every assertion runs on the line after
  `dispatchEvent` returns, in the browser's own turn. Nothing here adds
  to the authored surface: the cells are `arm1/grid`'s ordinary
  `:value` / `:on-input` cells, unchanged, so HD-020's ≤2-hook budget is
  untouched and `arm1_hook_ledger_dom_cljs_test` still gates it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.grid :as grid]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; Same as the grid suite: the fixture's default leaves a dynamic-var
     ;; frame stamp in scope, and the carried-invariant chain resolves that
     ;; tier before React context.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::burst-grid)

(defn- skip! [why]
  (is true (str "a burst into a real text field needs a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; The harness — the grid suite's, on this file's own frame
;; ---------------------------------------------------------------------------
;;
;; No input-implementation pin: every row here mounts the ARM's cells
;; only, and `the-arm-reads-the-same-on-both-pins` (grid suite, row 0)
;; established that the arm's element path is `react/createElement`
;; against the tag string — the UIx selector has nothing to select.

(defn- fresh! []
  (lane/leave-act-environment!)
  (grid/make-frame! frame-id grid/cells)
  (grid/reseed! frame-id grid/cells)
  frame-id)

(defn- arm-mount! []
  (mount/root! (mount/fresh-container!) frame-id [grid/grid {:n grid/cells}]))

(defn- cell-input [handle i] (.querySelector (:container handle) (str "#c" i)))

(defn- model-value [i] (get-in (rf/app-db-value frame-id) [:cells i] ""))

(defn- caret [node] [(.-selectionStart node) (.-selectionEnd node)])

(defn- set-native-value!
  "Write `v` through `HTMLInputElement.prototype`'s OWN `value` setter,
  bypassing React's per-instance change tracker — a plain `set!` would
  update the tracker too, after which React discards the `input` event as
  a no-op change and nothing under test ever runs."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) node v)))

(defn- set-model!
  "The out-of-band setup door — OUT of the discrete-event path, so this
  is where `settle!` is legitimate. Never part of any burst."
  [i v]
  (rt/dispatch! frame-id [:agrid/set i v])
  (mount/settle!)
  nil)

(defn- with-grid
  "Mount the grid on a fresh frame, run `f` with the handle, tear down
  whatever happens. Synchronous bodies only."
  [f]
  (fresh!)
  (let [handle (arm-mount!)]
    (try (f handle)
         (finally (mount/release! handle)))))

;; ---------------------------------------------------------------------------
;; The instrument
;; ---------------------------------------------------------------------------

(defn- burst-event
  "One keystroke's `input` event, built the way the browser builds it:
  `InputEvent`, `inputType \"insertText\"`, the character as `data`,
  bubbling (React's root delegation needs the bubble), NOT cancelable
  (the browser's own `input` never is — see the ns docstring), and not
  composing. Returned rather than dispatched so a row can ASSERT what it
  carries before anything trusts a green taken through it."
  [ch]
  (js/InputEvent. "input" #js {:bubbles    true
                               :data       (str ch)
                               :inputType  "insertText"
                               :composed   true}))

(defn- keystroke!
  "ONE keystroke of a burst: mutate the field at the caret, advance the
  caret past the inserted character, fire the `input` event, and answer
  whether the dispatch was delivered uncancelled. By the time any handler
  runs the character is already on screen — which is the whole
  difficulty, and exactly what a trusted keystroke does."
  [node ch]
  (let [start (.-selectionStart node)
        end   (.-selectionEnd node)
        v     (.-value node)]
    (set-native-value! node (str (subs v 0 start) ch (subs v end)))
    (let [c (inc start)] (.setSelectionRange node c c))
    (.dispatchEvent node (burst-event ch))))

(defn- burst!
  "Fire the characters of `text` into cell `i`'s `node` as SEPARATE
  events, back-to-back in one synchronous turn, and bank a reading ON THE
  LINE AFTER each `dispatchEvent` returns: the field's value, the model's
  value, the caret, and whether the dispatch was delivered. The readings
  are synchronous property reads — no yield, no task boundary, nothing
  between two keystrokes but the previous one's own handlers."
  [node i text]
  (mapv (fn [ch]
          (let [delivered (keystroke! node ch)]
            {:delivered delivered
             :value     (.-value node)
             :model     (model-value i)
             :caret     (caret node)}))
        text))

(defn- bare-burst!
  "The tightest form: mutate-and-dispatch k times with NOTHING between
  the events — not even a reading. What the endpoint rows use to show the
  claim does not depend on [[burst!]]'s instrumentation."
  [node text]
  (doseq [ch text] (keystroke! node ch))
  nil)

;; ---------------------------------------------------------------------------
;; 0 — the instrument itself, verified before anything trusts it
;; ---------------------------------------------------------------------------

(deftest the-events-a-burst-carries-are-verified-on-the-native-event
  (testing "what one burst keystroke actually dispatches, asserted on the
           event object itself — a burst row whose events silently carried
           the wrong signal would be a green gate over an unexercised door
           (the dead-isComposing lesson)"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM, and no InputEvent constructor")
      (do
        (let [e (burst-event "h")]
          (is (= "input" (.-type e)))
          (is (true? (.-bubbles e))
              "React listens at the root, so the event must bubble to be
               delegated at all")
          (is (false? (.-cancelable e))
              "the browser's own `input` event is NEVER cancelable — it
               announces a mutation that already happened; `beforeinput`
               is the cancelable member of the pair. The bead's word is
               answered here rather than silently dropped")
          (is (= "h" (.-data e)) "the character rides `data`")
          (is (= "insertText" (.-inputType e)))
          (is (false? (.-isComposing e))
              "a burst keystroke is not an IME composition")
          (is (false? (.-isTrusted e))
              "the one thing no synthetic event can carry — the stated
               instrument limit, identical to every other row in this
               arm's DOM suites"))
        (testing "and the signal is live end-to-end: one such event moves
                 the model and runs the body — measured, not assumed"
          (with-grid
            (fn [handle]
              (let [n (cell-input handle 7)]
                (.focus n)
                (reset! grid/!body-runs 0)
                (let [[r] (burst! n 7 "x")]
                  (is (true? (:delivered r))
                      "dispatchEvent returned true — nothing cancelled it")
                  (is (= "x" (:model r)) "the event reached the model")
                  (is (= "x" (:value r)) "and the echo landed in the same turn")
                  (is (= 1 @grid/!body-runs) "through exactly one body run"))))))))))

;; ---------------------------------------------------------------------------
;; 1 — the plain burst: k keystrokes, k landings, in order
;; ---------------------------------------------------------------------------

(deftest a-burst-of-five-keystrokes-all-land-in-order
  (testing "h,e,l,l,o as FIVE events in one turn — the trajectory shows
           every keystroke landing on its predecessor's outcome, so the
           final value is what the sequence implies and not merely what
           the last event carried"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (.focus n)
            (reset! grid/!body-runs 0)
            (let [rs (burst! n 7 "hello")]
              (is (every? :delivered rs) "all five dispatches delivered")
              (is (= ["h" "he" "hel" "hell" "hello"] (mapv :value rs))
                  "the field, read on the line after each dispatchEvent —
                   five landings, in order, none dropped")
              (is (= ["h" "he" "hel" "hell" "hello"] (mapv :model rs))
                  "and the model agreed with the field at every step,
                   inside the turn")
              (is (= [[1 1] [2 2] [3 3] [4 4] [5 5]] (mapv :caret rs))
                  "with the caret after the character just typed, every
                   time"))
            (is (= "hello" (model-value 7)) "the model holds the sequence")
            (is (= "hello" (.-value n)) "so does the DOM")
            (is (= [5 5] (caret n)) "caret at 5")
            (is (= 5 @grid/!body-runs)
                "and the body ran exactly k times — one per keystroke,
                 not one for the burst and not a hundred")))))))

(deftest a-burst-lands-with-nothing-at-all-between-the-events
  (testing "the same burst as a bare loop — no readings, no instrument,
           nothing between one dispatchEvent and the next mutate. The
           endpoint alone: if any keystroke had been dropped or deferred,
           the model could not spell the sequence"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (.focus n)
            (reset! grid/!body-runs 0)
            (bare-burst! n "hello")
            (is (= "hello" (model-value 7)))
            (is (= "hello" (.-value n)))
            (is (= [5 5] (caret n)))
            (is (= 5 @grid/!body-runs))))))))

;; ---------------------------------------------------------------------------
;; 2 — refusals interleaved: the next keystroke lands on the restored state
;; ---------------------------------------------------------------------------

(deftest a-burst-with-refusals-interleaved-lands-each-keystroke-on-the-restored-state
  (testing "1,a,2,b,3 into the :digits cell — the model holds only the
           accepted subset, each refusal's restore happens INSIDE its own
           event, and the next keystroke in the burst builds on the
           restored field rather than on the refused character"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 11)]
            (.focus n)
            (reset! grid/!body-runs 0)
            (let [rs (burst! n 11 "1a2b3")]
              (is (= ["1" "1" "12" "12" "123"] (mapv :value rs))
                  "each refused character is off the screen on the line
                   after ITS OWN dispatchEvent — the field never carries
                   a refusal into the next keystroke")
              (is (= ["1" "1" "12" "12" "123"] (mapv :model rs))
                  "the model refused a and b, took 1, 2 and 3")
              (is (= [[1 1] [1 1] [2 2] [2 2] [3 3]] (mapv :caret rs))
                  "and every restore leaves the caret exactly where the
                   next keystroke expects it"))
            (is (= "123" (model-value 11)) "the accepted subset, in order")
            (is (= "123" (.-value n)))
            (is (= 3 @grid/!body-runs)
                "three accepted keystrokes, three body runs — a refusal
                 moves no model and re-renders nothing")))))))

(deftest a-mid-string-burst-with-refusals-keeps-the-caret-through-the-restores
  (testing "z,9,z at position 2 of 12345 in the :digits cell — a refusal,
           an acceptance landing on the refusal's restore, and another
           refusal landing on the acceptance's converge, all mid-string,
           where React alone would have dumped the caret at the end of
           the field on every one of them"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 11)]
            (set-model! 11 "12345")
            (.focus n)
            (.setSelectionRange n 2 2)
            (reset! grid/!body-runs 0)
            (let [rs (burst! n 11 "z9z")]
              (is (= ["12345" "129345" "129345"] (mapv :value rs))
                  "z refused, 9 accepted at the position the restore
                   preserved, z refused again")
              (is (= ["12345" "129345" "129345"] (mapv :model rs)))
              (is (= [[2 2] [3 3] [3 3]] (mapv :caret rs))
                  "the caret never left the edit point — not thrown to
                   the end by the refusal's restore, nor by the
                   acceptance's converge, nor by the second refusal's"))
            (is (= "129345" (model-value 11)))
            (is (= 1 @grid/!body-runs) "one acceptance, one body run")))))))

;; ---------------------------------------------------------------------------
;; 3 — normalising models: the caret arithmetic survives k successive
;;     restores
;; ---------------------------------------------------------------------------

(deftest a-normalising-burst-keeps-the-caret-through-k-successive-restores
  (testing "h,e,l,l,o into the :upper cell — EVERY keystroke is rewritten
           by the model (h → H), so every keystroke costs a converge, and
           the burst is five successive restores each of which the next
           keystroke builds on"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 13)]
            (.focus n)
            (reset! grid/!body-runs 0)
            (let [rs (burst! n 13 "hello")]
              (is (= ["H" "HE" "HEL" "HELL" "HELLO"] (mapv :value rs))
                  "each lowercase keystroke came back uppercased inside
                   its own event")
              (is (= ["H" "HE" "HEL" "HELL" "HELLO"] (mapv :model rs)))
              (is (= [[1 1] [2 2] [3 3] [4 4] [5 5]] (mapv :caret rs))
                  "the caret stayed after the character just typed
                   through five rewrites"))
            (is (= "HELLO" (model-value 13)))
            (is (= "HELLO" (.-value n)))
            (is (= 5 @grid/!body-runs) "five acceptances, five body runs")))))))

(deftest a-grouping-burst-survives-length-changing-normalisation-mid-burst
  (testing "1,2,3,4,5 into the :group cell — the fourth keystroke changes
           the STRING LENGTH (1234 → 1,234), so from that keystroke on
           every absolute offset in the field is wrong and only the
           offset-from-the-end arithmetic can carry the caret through the
           rest of the burst"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 17)]
            (.focus n)
            (reset! grid/!body-runs 0)
            (let [rs (burst! n 17 "12345")]
              (is (= ["1" "12" "123" "1,234" "12,345"] (mapv :value rs))
                  "the grouping landed inside the fourth keystroke's own
                   event, and the fifth built on the grouped string")
              (is (= ["1" "12" "123" "1,234" "12,345"] (mapv :model rs)))
              (is (= [[1 1] [2 2] [3 3] [5 5] [6 6]] (mapv :caret rs))
                  "caret after the digit just typed — 5 and 6 are one
                   PAST the typed count because the comma arrived, which
                   is exactly what offset-from-the-end preserves"))
            (is (= "12,345" (model-value 17)))
            (is (= "12,345" (.-value n)))
            (is (= [6 6] (caret n)))
            (is (= 5 @grid/!body-runs))))))))

;; ---------------------------------------------------------------------------
;; 4 — the ordering claim, where a broken door changes the STRING
;; ---------------------------------------------------------------------------

(deftest a-mid-string-burst-spells-what-the-sequence-implies
  (testing "b,c at position 1 of ad — the row where a door that throws
           the caret to the end after keystroke one puts keystroke two in
           the WRONG PLACE: abdc instead of abcd. A final value equal to
           the last event's payload is not enough; it has to be what the
           SEQUENCE of keystrokes implies, position and all"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (set-model! 7 "ad")
            (.focus n)
            (.setSelectionRange n 1 1)
            (reset! grid/!body-runs 0)
            (let [rs (burst! n 7 "bc")]
              (is (= ["abd" "abcd"] (mapv :value rs))
                  "b landed at position 1; c landed AFTER b, because the
                   converge left the caret there inside b's own event")
              (is (= ["abd" "abcd"] (mapv :model rs)))
              (is (= [[2 2] [3 3]] (mapv :caret rs))))
            (is (= "abcd" (model-value 7))
                "the sequence's spelling — abdc here is the signature of
                 a caret thrown to the end between two keystrokes of one
                 burst")
            (is (= "abcd" (.-value n)))
            (is (= 2 @grid/!body-runs))))))))
