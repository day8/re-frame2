(ns re-frame.bench.hicasso.arm1.controlled-grid-dom-cljs-test
  "THE 100-CELL CONTROLLED GRID, ON ARM 1 (K4, HD-019's full door —
  rf2-2rtt6.41).

  rf2-2rtt6.9 witnessed two of validation.md's six `:controlled/grid-100`
  claims, on the dogfood screen's own single field: the same-turn echo and
  the explicit-revision reset. The other four are here, at the witness's
  stated size, on the arm's own element path —
  **`:mid-string-caret`, `:selection-preserved`,
  `:ime-composition-commits-nothing`, `:unchanged-model-rejection`** and
  **`:async-normalisation`**.

  Every synchronous assertion runs on the line after `dispatchEvent`
  returns. There is **no `act` anywhere in this file**, and no `flushSync`
  inside a discrete-event path an assertion reads: `mount/settle!` appears
  only after an OUT-OF-BAND dispatch, which is the setup door and never
  the claim.

  ## THE THING THIS FILE EXISTS TO NOT MEASURE BY ACCIDENT

  UIx picks its controlled-input implementation **from the classpath**:
  `uix.compiler.aot/create-uix-input` branches on
  `uix.compiler.input/should-use-reagent-input?`, which — left alone —
  answers *\"is Reagent loaded?\"*. Every `:browser-test` bundle in this
  repo carries Reagent, so a UIx `:input` here is **not** a plain React
  controlled input: it is a port of Reagent's workaround that makes the
  element uncontrolled and drives it from a `requestAnimationFrame`
  queue, one frame late. Two rows were once green while measuring that
  port and reading it as React (rf2-n3dxw), which is the whole reason
  `*use-reagent-input-enabled?*` exists to pin with.

  **Arm 1 does not go through UIx at all**, and the first row below proves
  it rather than asserting it. `front.codec` emits `[:input …]` with
  `react/createElement` against the tag string, so the selector has
  nothing to select: pinned either way, in the same bundle, in the same
  turn, the arm's cell behaves identically — while a UIx cell mounted
  beside it on the same frame changes behaviour with the pin. That is the
  measurement that licenses every row after it to say \"React\" and mean it.

  ## What React's own restore does, and what it does not

  React's end-of-discrete-event state restore fires even when nothing
  re-rendered: the change plugin records the target
  (`react-dom-client.development.js`, `createAndAccumulateChangeEvent`
  banks `restoreTarget` **before** it looks for an `onChange` listener, so
  an `:on-input`-only element is restored too), and the `finally` of
  `batchedUpdates$1` hands the committed props to `updateInput`, which
  assigns `element.value` whenever it differs. **So a refused character
  comes off the screen inside the discrete event, with nothing
  re-rendered.**

  What it does not do is put the caret back. Assigning `value` moves the
  cursor to the end of the control (the HTML `value` IDL setter), and
  React restores a selection only around a commit in which focus MOVED.
  So every write React makes lands the caret at the end. Neither shipped
  implementation gives both same-turn convergence and the caret where the
  edit left it — React converges in-turn and throws the caret to the end,
  UIx's port gets the caret right a frame late. rf2-n3dxw records it and
  rf2-fki5d prices the third behaviour that would have both. **The rows
  below assert the MEASURED value, not the desired one**: the day
  something puts the caret back, they go red, and whoever makes them red
  is exactly the person who should be reading those two beads.

  ## Typing is simulated the way the browser does it

  [[type-into!]] mutates the field **first** and fires `input` **second**,
  because that is the whole difficulty: by the time any handler runs, the
  character is already on screen. It writes through
  `HTMLInputElement.prototype`'s own `value` setter, because React patches
  the *instance* setter to maintain its change tracker and a plain `set!`
  would update the tracker too — after which React discards the `input`
  event as a no-op change and nothing under test ever runs.

  Runtime: `-dom-cljs-test`; under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.grid :as grid]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [uix.core :refer [$ defui]]
            [uix.compiler.input]
            ;; Required so `:uix-reagent-input` can be SELECTED rather than
            ;; merely inherited: UIx's port reaches Reagent's after-render
            ;; queue through the `reagent.impl.batching` global, which only
            ;; exists once that namespace is loaded.
            [reagent.impl.batching]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview hfn]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; `:ambient-frame nil` is load-bearing: the fixture's default leaves
     ;; a dynamic-var frame stamp in scope and the carried-invariant chain
     ;; resolves that tier BEFORE React context, so the UIx comparator
     ;; would read the ambient frame's app-db while the arm read the
     ;; provider's.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-grid)

(defn- skip! [why]
  (is true (str "a caret in a real text field needs a real DOM — " why)))

;; ---------------------------------------------------------------------------
;; Pinning the input implementation
;; ---------------------------------------------------------------------------

(def input-implementations
  "The two things `uix.compiler.aot/create-uix-input` can build, and the
  value of `uix.compiler.input/*use-reagent-input-enabled?*` that selects
  each. `nil` — the shipped default — is not a third option; it is
  *whichever of these two the bundle's contents imply*."
  {:react false :uix-reagent-input true})

(defn- pin! [impl]
  (set! uix.compiler.input/*use-reagent-input-enabled?*
        (get input-implementations impl)))

(defn- unpin! [] (set! uix.compiler.input/*use-reagent-input-enabled?* nil))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(defn- fresh!
  "A frame holding exactly the seeded grid. Both halves are needed:
  `make-frame!` is idempotent and will not replay its initial events for
  an id that already exists, and `reseed!` is the model's own door for
  returning a live frame to its seeded state."
  []
  (lane/leave-act-environment!)
  (grid/make-frame! frame-id grid/cells)
  (grid/reseed! frame-id grid/cells)
  frame-id)

(defn- arm-mount!
  "The arm's grid, on the arm's own root."
  []
  (mount/root! (mount/fresh-container!) frame-id [grid/grid {:n grid/cells}]))

(defn- cell-input [handle i] (.querySelector (:container handle) (str "#c" i)))

(defn- model-value
  "What app-db says cell `i` holds — the other half of every agreement
  assertion, read from the frame rather than from the renderer."
  [i]
  (get-in (rf/app-db-value frame-id) [:cells i] ""))

(defn- committed-value [i] (get-in (rf/app-db-value frame-id) [:committed i]))

(defn- caret [node] [(.-selectionStart node) (.-selectionEnd node)])

(defn- set-native-value!
  "Write `v` through `HTMLInputElement.prototype`'s OWN `value` setter,
  bypassing React's per-instance change tracker. See the ns docstring."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) node v)))

(defn- type-into!
  "Type `text` at the caret, the way a browser does it: the field changes
  first, the `input` event fires second."
  [node text]
  (let [start (.-selectionStart node)
        end   (.-selectionEnd node)
        v     (.-value node)]
    (set-native-value! node (str (subs v 0 start) text (subs v end)))
    (let [c (+ start (count text))]
      (.setSelectionRange node c c))
    (.dispatchEvent node (js/Event. "input" #js {:bubbles true}))
    nil))

(defn- key-event
  "Build a `keydown` the way an IME's host browser sends one. Returned
  rather than dispatched so a caller can ASSERT the signal it thinks it
  is sending: `keyCode` and `isComposing` are legacy/extension members of
  `KeyboardEventInit`, and a row whose event silently carried neither
  would be a green gate over an unexercised fence."
  [{:keys [key composing? key-code]}]
  (js/KeyboardEvent. "keydown"
                     #js {:key         (or key "Enter")
                          :bubbles     true
                          :isComposing (boolean composing?)
                          :keyCode     (or key-code 13)}))

(defn- set-model!
  "Put a value in the model out of band — the setup door, and also the
  async-normalisation door. OUT of the discrete-event path, so this is
  where `settle!` is legitimate."
  [i v]
  (rt/dispatch! frame-id [:agrid/set i v])
  (mount/settle!)
  nil)

(defn- after-a-frame
  "Give Reagent's after-render queue the animation frame it is scheduled
  on, then a macrotask for anything that frame scheduled in turn."
  [f]
  (js/requestAnimationFrame (fn [] (js/setTimeout f 0))))

(defn- with-grid
  "Mount the arm's grid with `impl` pinned, run `f` with the handle, and
  tear it down whatever happens. Synchronous bodies only — an async body
  returns before its timer fires, and a grid torn down under a pending
  callback dispatches into a frame that is gone."
  ([f] (with-grid :react f))
  ([impl f]
   (pin! impl)
   (fresh!)
   (let [handle (arm-mount!)]
     (try (f handle)
          (finally (mount/release! handle) (unpin!))))))

;; ---------------------------------------------------------------------------
;; The UIx comparator — its own root, its own provider
;; ---------------------------------------------------------------------------
;;
;; Deliberately NOT the arm's root: the control must not borrow the
;; candidate's plumbing, or a plumbing bug would hide inside the very
;; comparison that is supposed to expose it. Same frame, same model, same
;; keystroke — the ONLY variable is which library minted the element.

(defui uix-cell [{:keys [i]}]
  (let [v                        (uix-adapter/use-subscribe [:agrid/cell i])
        {:keys [dispatch-sync]}  (uix-adapter/use-frame)]
    ($ :input {:id        (str "u" i)
               :type      "text"
               :value     v
               :on-change (fn [e] (dispatch-sync [:agrid/edit i (.. e -target -value)]))})))

(defn- uix-mount! [i]
  (let [container (mount/fresh-container!)
        root      (react-dom-client/createRoot container)]
    (react-dom/flushSync
      (fn [] (.render root ($ uix-adapter/frame-provider {:frame frame-id}
                              ($ uix-cell {:i i})))))
    {:root root :container container}))

(defn- uix-release! [{:keys [root container]}]
  (react-dom/flushSync (fn [] (.unmount root)))
  (when-some [p (.-parentNode container)] (.removeChild p container))
  nil)

;; ---------------------------------------------------------------------------
;; 0 — the arm's element path is React's own, and the classpath cannot
;;     reach it
;; ---------------------------------------------------------------------------

(deftest the-selector-is-live-in-this-bundle
  (testing "UIx's default answer is a fact about the classpath, and this
           bundle carries Reagent — so an unpinned UIx `:input` here is not
           a plain React controlled input at all"
    (is (some? reagent.impl.batching/do-after-render)
        "Reagent's after-render queue is in this bundle, which is exactly
         what makes UIx's default answer `true`")
    (unpin!)
    (is (true? (uix.compiler.input/should-use-reagent-input?)))
    (pin! :react)
    (is (false? (uix.compiler.input/should-use-reagent-input?)))
    (pin! :uix-reagent-input)
    (is (true? (uix.compiler.input/should-use-reagent-input?)))
    (unpin!)))

(deftest the-arms-input-is-reacts-own-whatever-the-selector-says
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      ;; THE PIN THAT WOULD CHANGE A UIx CELL. Both elements are minted in
      ;; the same turn, on the same frame, against the same model, with
      ;; the port SELECTED. If the arm went through UIx, its refused
      ;; character would still be on screen when the event returned.
      (do
        (pin! :uix-reagent-input)
        (fresh!)
        (let [arm   (arm-mount!)
              ctrl  (uix-mount! 11)
              a     (cell-input arm 11)
              u     (.querySelector (:container ctrl) "#u11")]
          (set-model! 11 "12")
          (mount/settle!)
          (.focus a)
          (.setSelectionRange a 2 2)
          (type-into! a "a")
          (is (= "12" (.-value a))
              "ARM: the refused character is off the screen on the line
               after `dispatchEvent` — React's own restore, inside the
               discrete event, with the port pinned ON")
          (is (= "12" (model-value 11)))
          (.focus u)
          (.setSelectionRange u 2 2)
          (type-into! u "a")
          (is (= "12a" (.-value u))
              "UIx, SAME PIN, SAME TURN: still on the screen — the port
               deleted `value` from the props and drives the element from
               an animation-frame queue, so React's restore has nothing to
               restore. This is the difference the pin makes, and the arm
               does not have it")
          (after-a-frame
            (fn []
              (try
                (is (= "12" (.-value u))
                    "one animation frame later the port has converged —
                     which is the timing rf2-n3dxw originally recorded as
                     React's")
                (catch :default e
                  (is false (str "the deferred converge threw: " (ex-message e)))))
              (uix-release! ctrl)
              (mount/release! arm)
              (unpin!)
              (done))))))))

(deftest the-arm-reads-the-same-on-both-pins
  (testing "the selector cannot reach `react/createElement \"input\"`, so
           the arm's cell is byte-identical under both pins — which is what
           licenses every row below to name React and mean it"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (let [reading (fn [impl]
                      (with-grid impl
                        (fn [handle]
                          (let [n (cell-input handle 11)]
                            (set-model! 11 "12")
                            (.focus n)
                            (.setSelectionRange n 2 2)
                            (type-into! n "a")
                            {:value (.-value n) :caret (caret n) :model (model-value 11)}))))]
        (is (= {:value "12" :caret [2 2] :model "12"} (reading :react)))
        (is (= (reading :react) (reading :uix-reagent-input))
            "same reading with the port pinned ON — the arm never asked")))))

;; ---------------------------------------------------------------------------
;; 1 — :same-turn-echo, at the witness's stated size, and the per-keystroke
;;     budget
;; ---------------------------------------------------------------------------

(deftest a-keystroke-echoes-inside-the-discrete-event
  (testing "100 cells mounted; a real `input` event on one of them reaches
           app-db and comes back to the DOM before the event returns — no
           act, no flushSync, no yield"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (is (= grid/cells (.-length (.querySelectorAll (:container handle) ".cell")))
              "the witness is at its stated size")
          (let [n (cell-input handle 7)]
            (.focus n)
            (type-into! n "hello")
            (is (= "hello" (model-value 7)) "the model took the keystroke")
            (is (= "hello" (.-value n))
                "and the field shows it, on the next line — HD-019's
                 synchronous door, through the arm's own dispatch")))))))

(deftest the-echo-is-one-body-run-not-one-hundred
  (testing "the per-keystroke budget: a keystroke in cell 7 re-runs cell 7"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (.focus n)
            (reset! grid/!body-runs 0)
            (type-into! n "x")
            (is (= 1 @grid/!body-runs)
                (str "one boundary body ran, not " grid/cells))))))))

;; ---------------------------------------------------------------------------
;; 2 — :mid-string-caret
;; ---------------------------------------------------------------------------

(deftest editing-mid-string-leaves-the-caret-where-the-typing-put-it
  (testing "the model agreed with the field, so React wrote nothing and the
           cursor stayed where the character went in"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (set-model! 7 "abcd")
            (.focus n)
            (.setSelectionRange n 2 2)
            (type-into! n "X")
            (is (= "abXcd" (model-value 7)))
            (is (= "abXcd" (.-value n)))
            (is (= [3 3] (caret n))
                "the caret did not jump to the end")))))))

(deftest a-normalising-model-moves-the-caret-to-the-end
  (testing "RECORDED BEHAVIOUR, NOT DESIRED BEHAVIOUR (rf2-n3dxw,
           rf2-fki5d). The model uppercases what was typed, so React
           WRITES — and every write React makes lands the caret at the end
           of the string. This is the classic controlled-input caret jump,
           and it belongs to React's restore rather than to anything this
           arm does"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 13)]
            (set-model! 13 "ABCD")
            (.focus n)
            (.setSelectionRange n 2 2)
            (type-into! n "x")
            (is (= "ABXCD" (model-value 13)) "the model normalised the case")
            (is (= "ABXCD" (.-value n)) "and the field followed, in the same turn")
            (is (= [5 5] (caret n))
                "but the caret is at the END, not after the character just
                 typed — React restores a selection only around a commit in
                 which focus MOVED, and focus did not move")))))))

(deftest a-grouping-model-keeps-the-caret-after-the-digit-just-typed
  (testing "1,234 + \"5\" becomes 12,345 — one character longer than what
           was typed. The caret survives here only because the edit was at
           the END of the field, which is where React's write puts it
           anyway"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 17)]
            (set-model! 17 "1,234")
            (.focus n)
            (.setSelectionRange n 5 5)
            (type-into! n "5")
            (is (= "12,345" (model-value 17)))
            (is (= "12,345" (.-value n)))
            (is (= [6 6] (caret n)) "the caret is still after the 5")))))))

;; ---------------------------------------------------------------------------
;; 3 — :selection-preserved
;; ---------------------------------------------------------------------------

(deftest a-write-to-another-cell-leaves-this-fields-selection-alone
  (testing "a commit that moves a DIFFERENT cell's subscription writes
           nothing here, so nothing here moves — the index's narrowness
           read as a caret rather than as a re-render count"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (set-model! 7 "abcdef")
            (.focus n)
            (.setSelectionRange n 1 4)
            (is (= [1 4] (caret n)) "the range is really there before the write")
            (set-model! 23 "elsewhere")
            (is (= [1 4] (caret n)) "and it survived a commit elsewhere")
            (is (= "abcdef" (.-value n)))))))))

(deftest a-range-collapses-when-the-restore-writes-this-field
  (testing "RECORDED BEHAVIOUR, NOT DESIRED BEHAVIOUR (rf2-n3dxw). Arm 2
           restored both ends of a selection by distance from the end of
           the string. React does not: it assigns `value`, which collapses
           the selection to a cursor at the end of the new string. Nothing
           here pretends otherwise"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (set-model! 7 "abcdef")
            (.focus n)
            (.setSelectionRange n 1 4)
            (set-model! 7 "Xabcdef")
            (is (= "Xabcdef" (.-value n)) "the new value reached the field")
            (is (= [7 7] (caret n))
                "and the range is a cursor at the end of what React wrote")))))))

;; ---------------------------------------------------------------------------
;; 4 — :ime-composition-commits-nothing
;; ---------------------------------------------------------------------------
;;
;; The claim authoring.md pins, and the one the arm actually owns: **a
;; composing Enter commits nothing**. The gate is
;; `front.intent/composing?`, written over the WHOLE key-map rather than
;; over Enter alone, because during composition every keystroke belongs to
;; the IME and a per-key exception list is a second place for the law to
;; rot.
;;
;; What is NOT asserted, and why, so the absence reads as a decision: the
;; **value** path during composition. Synthetic `compositionstart` /
;; `compositionend` events do not exercise React's composition plugin, and
;; a fence asserted without being demonstrated is worse than no assertion.
;; `bench/hicasso/controlled_restore_dom_cljs_test` carries the same
;; carve-out and rf2-n3dxw carries the bead.

(def ^:private !probe (atom []))

(defview compose-probe
  "An `h/fn` at a key position, so the row can read what React actually
  hands a keydown handler."
  [_]
  [:input.probe {:type        "text"
                 :on-key-down (hfn [e]
                                (swap! !probe conj
                                       {:synthetic (.-isComposing e)
                                        :native    (.-isComposing (.-nativeEvent e))
                                        :key-code  (.-keyCode e)})
                                nil)}])

(deftest reacts-synthetic-keyboard-event-drops-is-composing
  (testing "MEASURED, and the reason `composing?` reads the NATIVE event.
           React's `KeyboardEventInterface` enumerates key, code, location,
           the modifier flags, repeat, locale, charCode, keyCode and which
           — and `createSyntheticEvent` copies THAT LIST and nothing else.
           `isComposing` is not on it, so a gate reading it off the
           synthetic event is dead on React and only the legacy keyCode
           half ever fires"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (reset! !probe [])
        (let [handle (mount/root! (mount/fresh-container!) frame-id [compose-probe {}])]
          (try
            (let [node (.querySelector (:container handle) ".probe")
                  e    (key-event {:key "Enter" :composing? true :key-code 229})]
              (is (true? (.-isComposing e))
                  "the event this row sends really does carry isComposing")
              (is (= 229 (.-keyCode e))
                  "and really does carry the legacy signal")
              (.dispatchEvent node e)
              (let [seen (first @!probe)]
                (is (some? seen) "the handler ran")
                (is (nil? (:synthetic seen))
                    "React's synthetic keyboard event does NOT carry
                     isComposing — this is the finding")
                (is (true? (:native seen))
                    "the native event does, and that is where the gate has
                     to read it")
                (is (= 229 (:key-code seen))
                    "while the legacy signal survives the synthetic
                     interface, because keyCode IS on React's list")))
            (finally (mount/release! handle))))))))

(deftest a-composing-enter-commits-nothing
  (testing "both signals, each on its own, through a real React keydown on
           a real cell"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (set-model! 7 "shi")
            (.focus n)
            (testing "isComposing alone — the modern signal"
              (let [e (key-event {:key "Enter" :composing? true :key-code 13})]
                (is (true? (.-isComposing e)))
                (.dispatchEvent n e))
              (is (nil? (committed-value 7))
                  "the IME's Enter selected a candidate; it did not commit
                   the field"))
            (testing "keyCode 229 alone — all some IMEs on some browsers send"
              (let [e (key-event {:key "Enter" :composing? false :key-code 229})]
                (is (false? (.-isComposing e)))
                (is (= 229 (.-keyCode e)))
                (.dispatchEvent n e))
              (is (nil? (committed-value 7))
                  "the legacy signal gates the same map"))
            (testing "and an ordinary Enter DOES commit, so the gate is a
                     gate and not an off switch"
              (.dispatchEvent n (key-event {:key "Enter" :composing? false :key-code 13}))
              (is (= "shi" (committed-value 7))))
            (testing "the model never moved through any of it"
              (is (= "shi" (model-value 7)))
              (is (= "shi" (.-value n))))))))))

;; ---------------------------------------------------------------------------
;; 5 — :unchanged-model-rejection
;; ---------------------------------------------------------------------------

(deftest a-refused-keystroke-moves-no-model-and-re-runs-no-boundary
  (testing "cell 11 takes digits only. The browser has already shown the
           letter; the model does not move, so NOTHING re-renders"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 11)]
            (set-model! 11 "12")
            (.focus n)
            (.setSelectionRange n 2 2)
            (reset! grid/!body-runs 0)
            (type-into! n "a")
            (is (= "12" (model-value 11)) "the model refused the letter")
            (is (zero? @grid/!body-runs)
                "and no boundary body ran at all — the value the model holds
                 did not change, so there was nothing for React to
                 re-render")))))))

(deftest a-refused-keystroke-is-taken-off-the-screen-inside-the-event
  (testing "the row rf2-n3dxw was opened for, taken on the arm. Nothing
           re-rendered — the row above counts zero body runs on this exact
           keystroke — so the write below cannot have come from a render.
           It is React's own controlled-state restore, and all of it lands
           before `dispatchEvent` returns"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 11)]
            (set-model! 11 "12")
            (.focus n)
            (.setSelectionRange n 2 2)
            (reset! grid/!body-runs 0)
            (type-into! n "a")
            (is (= "12" (.-value n))
                "the refused character is off the screen, on the next line")
            (is (= "12" (model-value 11)) "and the field and the model agree")
            (is (zero? @grid/!body-runs) "with nothing re-rendered to do it")
            (is (= [2 2] (caret n))
                "caret at the position before the refused character —
                 because the edit was AT the end, which is where React's
                 write leaves it anyway")))))))

(deftest a-refused-keystroke-mid-string-converges-with-the-caret-at-the-end
  (testing "RECORDED BEHAVIOUR, NOT DESIRED BEHAVIOUR (rf2-n3dxw,
           rf2-fki5d). The residue after the headline row is met: the
           refused character IS removed, in the same turn — but React's
           write moves the cursor to the end, so a user editing mid-string
           is thrown to the end of the field on every refused keystroke"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 11)]
            (set-model! 11 "12345")
            (.focus n)
            (.setSelectionRange n 2 2)
            (type-into! n "z")
            (is (= "12345" (.-value n)) "the refused character is gone")
            (is (= "12345" (model-value 11)))
            (is (= [5 5] (caret n))
                "but the caret is at the end of the field, not at 2")))))))

;; ---------------------------------------------------------------------------
;; 6 — :async-normalisation
;; ---------------------------------------------------------------------------

(deftest a-correction-that-arrives-later-still-converges
  (testing "the same restore driven by a timer instead of a keystroke — the
           shape a server normalisation or a debounced validation takes.
           Nothing about the arm's door is synchronous here, and it still
           lands"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        ;; NOT `with-grid`: its `finally` runs the moment the body returns,
        ;; and the body of an async test returns before its timer fires.
        (do
          (pin! :react)
          (fresh!)
          (let [handle (arm-mount!)
                n      (cell-input handle 17)]
            (set-model! 17 "1234")
            (.focus n)
            (.setSelectionRange n 4 4)
            (is (= "1234" (.-value n)) "the field starts where the model does")
            (js/setTimeout
              (fn []
                (try
                  (set-model! 17 (grid/group-digits "1234"))
                  (is (= "1,234" (model-value 17)) "the late correction reached the model")
                  (is (= "1,234" (.-value n)) "and the field")
                  (is (= [5 5] (caret n))
                      "with the caret still after the last digit")
                  (catch :default e
                    (is false (str "the late correction threw: " (ex-message e)))))
                (mount/release! handle)
                (unpin!)
                (done))
              0)))))))

;; ---------------------------------------------------------------------------
;; Teardown
;; ---------------------------------------------------------------------------

(deftest the-grid-leaves-no-residue
  (testing "one hundred controlled boundaries, typed into, then released"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (pin! :react)
        (fresh!)
        (let [handle (arm-mount!)
              n      (cell-input handle 7)]
          (.focus n)
          (type-into! n "abc")
          (is (= "abc" (model-value 7)))
          (mount/release! handle)
          (unpin!)
          (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                 (rt/residue))
              "zero leaked subscription ref-counts after teardown"))))))
