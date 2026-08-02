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
  So every write React makes lands the caret at the end.

  ## The half the arm adds, and where it lives (rf2-fki5d)

  Neither shipped implementation gives both same-turn convergence and the
  caret where the edit left it: React converges in-turn and throws the
  caret to the end, UIx's port gets the caret right an animation frame
  late. rf2-n3dxw recorded that residue and this arm closes it, in the
  **element path** — `front.controlled/install!` wraps the change handler
  `front.codec` was about to emit, and at the end of that handler, still
  inside the discrete event and still ahead of React's own restore, it
  flushes the synchronous door's commit, writes the value the element
  renders if the field disagrees, and puts the caret back by offset from
  the END of the string.

  Two consequences the rows below turn on. Writing the value first makes
  React's later `updateInput` a no-op, because it only assigns when the
  two differ — which is what lets the restored caret survive the restore.
  And the converge is on the CHANGE path only: an out-of-band correction
  fires no change event, so a write arriving from a timer is still
  React's to converge, with React's caret.

  Nothing was added to the cell, to `defview`, or to the boundary shell.
  The grid below is the same grid, `grid.cljs` is unchanged, and HD-020's
  ≤2-hook budget is untouched — `arm1_hook_ledger_dom_cljs_test` still
  reads the same ledger.

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
            [re-frame.bench.hicasso.front.controlled :as controlled]
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

(deftest a-normalising-model-keeps-the-caret-after-the-character-just-typed
  (testing "the classic controlled-input caret jump, and the row that says
           this arm does not have it. The model uppercases what was typed,
           so a write happens — and every write React makes lands the
           caret at the end of the string. The converge puts it back
           before the event returns, by offset from the END, so the user
           carries on typing where they were (rf2-fki5d).

           Measured against the same keystroke on the shipped paths:
           React alone leaves [5 5], and UIx's port reaches [3 3] one
           animation frame later"
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
            (is (= [3 3] (caret n))
                "with the caret still after the character just typed, on
                 the line after `dispatchEvent`")))))))

(deftest a-grouping-model-keeps-the-caret-after-the-digit-just-typed
  (testing "1,234 + \"5\" becomes 12,345 — one character longer than what
           was typed, so every absolute offset in the string moved. This
           row was green before the converge existed, because the edit was
           at the END of the field and that is where React's write leaves
           the caret anyway; it stays green for the better reason, which
           is that the offset is taken from the end"
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
           the selection to a cursor at the end of the new string.

           The converge does not reach this row and is not meant to: an
           out-of-band write fires no change event, so there is no handler
           to run at the end of. Restoring a RANGE is a second algorithm
           besides — two offsets rather than one — and rf2-n3dxw keeps it.
           Nothing here pretends otherwise"
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
;; What is NOT asserted HERE, and where it is: the **value** path during
;; composition. Synthetic `compositionstart` / `compositionend` events do
;; not exercise React's composition plugin — or the browser's composition
;; range — so the real-composition harness owns it instead (rf2-o27h3):
;; `bench/hicasso/ime_run.cjs` drives trusted CDP composition against this
;; arm's element path (converge included), plain React and the port, and
;; asserts the fence there. Its one open residue — every implementation
;; rewrites a refused/normalised value mid-composition, destroying the
;; exchange — is rf2-digtt. The two rows below stay: they witness the
;; gate's two signals through React's keydown plumbing cheaply, on every
;; PR, in-page — while the events they build are exactly the synthetic
;; kind the harness exists to go beyond, which is why the harness, not
;; these rows, is what establishes the fence.

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

(deftest a-refused-keystroke-mid-string-converges-with-the-caret-where-it-was
  (testing "THE ROW NEITHER SHIPPED IMPLEMENTATION GETS RIGHT, and the
           reason rf2-fki5d exists. React removes the refused character
           in the same turn and throws the cursor to the end of the field
           — so a user correcting the middle of a number is dumped at the
           end of it on every keystroke the model refuses. UIx's port
           gets the caret right and arrives an animation frame late.

           Here both halves land before `dispatchEvent` returns"
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
            (is (= [2 2] (caret n))
                "and the caret is at the position before it, not at the
                 end of the field")))))))

;; ---------------------------------------------------------------------------
;; 5b — the whole family, in one turn, and the record underneath it
;; ---------------------------------------------------------------------------

(deftest the-family-converges-in-one-turn-with-the-caret-where-the-edit-left-it
  (testing "every row of validation.md's controlled family, on one mounted
           grid, in one turn, through the arm's ordinary authoring
           surface — a `:value` and an `:on-input` intent, no ref, no
           effect, no escape hatch, and nothing in the boundary shell.

           The fifth row is not a formality. It is the one the naive
           form regresses: a keystroke the model takes VERBATIM looks
           exactly like a refusal from inside the handler, and a converge
           that writes the value its closure holds deletes the character
           the user just typed. `front/controlled_dom_cljs_test`
           reproduces that deletion; this row is what says it does not
           happen here"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [reject (cell-input handle 11)
                upper  (cell-input handle 13)
                group  (cell-input handle 17)
                plain  (cell-input handle 7)]
            (testing "a refused keystroke at the end of the field"
              (set-model! 11 "12")
              (.focus reject)
              (.setSelectionRange reject 2 2)
              (type-into! reject "a")
              (is (= "12" (.-value reject)))
              (is (= [2 2] (caret reject))))
            (testing "a refused keystroke MID-STRING — the row React alone loses"
              (set-model! 11 "12345")
              (.focus reject)
              (.setSelectionRange reject 2 2)
              (type-into! reject "z")
              (is (= "12345" (.-value reject)))
              (is (= [2 2] (caret reject))
                  "the caret is at the position before the refused
                   character, in the same turn"))
            (testing "a length-preserving normalisation"
              (set-model! 13 "ABCD")
              (.focus upper)
              (.setSelectionRange upper 2 2)
              (type-into! upper "x")
              (is (= "ABXCD" (.-value upper)))
              (is (= [3 3] (caret upper))))
            (testing "a length-CHANGING normalisation"
              (set-model! 17 "1,234")
              (.focus group)
              (.setSelectionRange group 5 5)
              (type-into! group "5")
              (is (= "12,345" (.-value group)))
              (is (= [6 6] (caret group))))
            (testing "and an ordinary accepted keystroke is not disturbed"
              (set-model! 7 "abcd")
              (.focus plain)
              (.setSelectionRange plain 2 2)
              (type-into! plain "X")
              (is (= "abXcd" (.-value plain)))
              (is (= "abXcd" (model-value 7)) "the model has it too")
              (is (= [3 3] (caret plain))))
            (testing "the model and the field agree everywhere afterwards"
              (is (= ["12345" "ABXCD" "12,345" "abXcd"]
                     [(model-value 11) (model-value 13)
                      (model-value 17) (model-value 7)]))
              (is (= [(.-value reject) (.-value upper)
                      (.-value group) (.-value plain)]
                     [(model-value 11) (model-value 13)
                      (model-value 17) (model-value 7)])))))))))

(deftest the-record-is-reacts-own-mirror-and-is-not-the-handlers-closure
  (testing "THE ONE THING THE CONVERGE DEPENDS ON, pinned against a live
           React tree.

           The converge has to know what the element renders NOW, and the
           value a change handler closed over is one render behind the
           moment its own `flushSync` commits. The record it reads
           instead is `node.defaultValue` — React's mirror of the
           committed `value` prop, written by `initInput` on mount and by
           `updateInput` on every commit of a genuinely controlled field.

           This row asserts the mirror directly, and asserts that it
           MOVES when the rendered value moves. If React ever stopped
           writing it, this goes red by name instead of five caret rows
           going quietly wrong"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (with-grid
        (fn [handle]
          (let [n (cell-input handle 7)]
            (set-model! 7 "abcd")
            (is (= "abcd" (controlled/last-rendered n))
                "the record is what the last render put on the element")
            (is (= (.-value n) (controlled/last-rendered n))
                "and the field agrees with it before anything is typed")
            (set-model! 7 "wxyz")
            (is (= "wxyz" (controlled/last-rendered n))
                "a re-render moves the record with it — which is exactly
                 what a closure minted by the PREVIOUS render cannot do")
            (testing "and typing does not disturb it, because the value
                     IDL setter sets the value and the dirty flag, never
                     the content attribute the record reflects"
              (.focus n)
              (.setSelectionRange n 4 4)
              (set-native-value! n "wxyzQ")
              (is (= "wxyz" (controlled/last-rendered n))
                  "still the value the element rendered, not the value
                   the field shows"))))))))

;; ---------------------------------------------------------------------------
;; 5c — the flush is a render, and the guards are one render old after it
;; ---------------------------------------------------------------------------

(defview type-flipping-cell
  "A controlled field whose TYPE is derived from the model, so ONE
  accepted keystroke re-renders the same `<input>` from `text` to
  `number` — and does it inside the converge's own `flushSync`, against
  a wrapper the `text` render minted."
  [{:keys [i]}]
  (let [v (rt/sub [:agrid/cell i])]
    [:input.flip {:type     (if (= "" v) "text" "number")
                  :value    v
                  :on-input [:agrid/edit i :re-frame.hicasso/value]}]))

(defn- reported-errors
  "Run `f` and return the messages the page reported while it ran.

  React does not let a throw from inside a discrete event escape
  `dispatchEvent`: it hands it to `reportError`, which dispatches an
  `error` event on `window`. So a `try`/`catch` around the keystroke sees
  NOTHING, and a row that relied on one would be green over a live
  exception — the browser runner's uncaught-pageerror gate would fail the
  build (`rf2-mwx08`) while every assertion here passed. A listener is
  what makes the throw this row's own to assert."
  [f]
  (let [!errs (atom [])
        on-err (fn [e]
                 (swap! !errs conj (or (some-> (.-error e) (.-message))
                                       (.-message e))))]
    (.addEventListener js/window "error" on-err)
    (try (f) (finally (.removeEventListener js/window "error" on-err)))
    @!errs))

(deftest a-type-change-inside-the-flush-leaves-the-converge-inert
  (testing "THE GUARD THAT HAS TO BE TAKEN TWICE (PR #7371 audit).

           `front.controlled/install!` decides an element is
           caret-bearing from the props that MINT the wrapper. Step 1 of
           the converge is a `flushSync`, which is a render — so by the
           time the caret is restored those props can be one render old.
           A synchronous handler that re-renders the same `<input>` from
           `text` to `number` is the case that separates them: React
           keeps the node and updates the attribute, so the wrapper from
           the `text` render goes on running against an element that no
           longer has a caret, and `setSelectionRange` throws
           `InvalidStateError` on such a type.

           Reading the caret a SECOND time, after the flush, is what makes
           the element behave exactly as it would have had it been minted
           a `number` field to begin with.

           Measured with the post-flush reading deleted: `InvalidStateError:
           Failed to execute 'setSelectionRange' on 'HTMLInputElement': The
           input element's type ('number') does not support selection.`"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (pin! :react)
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id
                                  [type-flipping-cell {:i 31}])]
          (try
            (let [before (.querySelector (:container handle) ".flip")]
              (is (= "text" (.-type before))
                  "it mounts as a text field — which is what installs the
                   wrapper in the first place")
              (is (= "" (.-value before)))
              (.focus before)
              (.setSelectionRange before 0 0)
              (is (= [0 0] (caret before)) "there really is a caret to lose")

              ;; The keystroke. The model takes it (cell 31 is `:plain`),
              ;; so the boundary re-renders inside the converge's flush and
              ;; the type changes under the running wrapper.
              (let [errs (reported-errors #(type-into! before "5"))
                    after (.querySelector (:container handle) ".flip")]
                (is (= [] errs)
                    "NOTHING THREW — the row. Without the post-flush
                     reading this carries the InvalidStateError quoted
                     above")
                (is (identical? before after)
                    "React kept the NODE and updated the attribute — the
                     premise the whole row turns on, asserted rather than
                     assumed")
                (is (= "5" (model-value 31)) "the model took the keystroke")
                (is (= "number" (.-type after))
                    "and the re-render landed inside the converge's own
                     flush, so the type changed before the caret restore
                     would have run")
                (is (nil? (.-selectionStart after))
                    "the node has no caret any more — which is both why
                     the restore would throw and why there is nothing it
                     could legitimately restore")
                (is (= "5" (.-value after))
                    "and the field still shows the keystroke that caused
                     the flip")
                (is (= "" (controlled/last-rendered after))
                    "THE RECORD IS STALE HERE, and this is the second
                     reason to be inert rather than merely careful with
                     the caret: `setDefaultValue` skips a FOCUSED number
                     field (`react-dom@19.2.0:1738`), so React's mirror
                     still holds what the TEXT render wrote. It is no
                     longer the value this element renders, so there is
                     nothing here the converge could correctly write")))
            (finally (mount/release! handle) (unpin!))))))))

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
  (testing "one hundred controlled boundaries, typed into, then unmounted.

           **The census is read after React's unmount and BEFORE the arm's
           teardown door**, and that ordering is the whole of it.
           `mount/release!` calls `runtime/reset-runtime!`, which disposes
           every cell and empties the index by fiat, so a residue reading
           taken after it is all zeros whatever the teardown released —
           measured, by deleting `make-subscribe`'s cleanup
           `release-cell!`: the reading below goes red, and the same
           reading taken after `release!` stays green.

           Three counts, because those three are exact the instant the
           unmount returns; `:cells` and `:entries` are the reaper's, one
           macrotask later, and asserting them here would assert a
           schedule rather than a release."
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
          (is (= (inc grid/cells) (:boundaries (rt/stats)))
              "a hundred cell registrations and the grid's own are really
               standing before the teardown — the enclosing `grid` is a
               `defview` too, so it is a boundary like any other")
          (react-dom/flushSync (fn [] (.unmount (:root handle))))
          (let [census (select-keys (rt/residue) [:cell-refs :boundaries :edges])]
            (mount/release! (assoc handle :root nil))
            (unpin!)
            (is (= {:cell-refs 0 :boundaries 0 :edges 0} census)
                "zero leaked subscription ref-counts, zero boundaries and zero
                 edges the moment React's unmount returned")))))))
