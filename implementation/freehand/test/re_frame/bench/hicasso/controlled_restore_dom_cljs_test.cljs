(ns re-frame.bench.hicasso.controlled-restore-dom-cljs-test
  "WHAT CORRECT MEANS FOR A CONTROLLED INPUT, on React (rf2-m6if4,
  rf2-n3dxw).

  Arm 2 (the PATCH renderer) is retired — Mike ruled on 2026-07-31 that
  Hicasso is an adapter for React — and this file is what its hard gate
  left behind. Arm 2's `:controlled/grid-100` witness was the clearest
  statement in the repo of what a store-backed controlled input has to
  do, and the statement is worth keeping even though the renderer that
  provoked it is not.

  The model is Arm 2's, unchanged, because the model was never the
  renderer's business: 100 cells, one event, one subscription, and a
  per-cell policy in `app-db`.

  | policy | what the model does with the typed value |
  |---|---|
  | `:plain` | takes it |
  | `:digits` | **refuses** it unless it is all digits — the model does not move |
  | `:upper`  | normalises it to upper case |
  | `:group`  | normalises `12345` to `12,345` — the length changes |

  ## There are TWO input implementations, and the BUNDLE used to pick

  UIx chooses, per element and at element-creation time, between plain
  React and a port of Reagent's controlled-input workaround —
  `uix.compiler.aot/create-uix-input` branches on
  `uix.compiler.input/should-use-reagent-input?` (uix.core 1.4.4). That
  selector is not a decision the application makes. Left unset, it
  answers *\"is Reagent loaded, and not in `*non-reactive*` mode?\"*
  (`uix/compiler/input.cljs:15-21`). So a UIx-only app and a UIx app that
  also carries Reagent get **different input implementations, with
  different timing and a different caret** — and every bundle in this
  repo's `:browser-test` carries Reagent, because the Reagent adapter is
  first-class and ships with tests.

  Every row below therefore **pins** the implementation it measures
  rather than inheriting whichever one the bundle happened to select.
  Both are witnessed, because the difference between them IS the finding.

  ### `:react` — the element stays controlled

  React's own end-of-discrete-event state restore fires, and it fires
  even when nothing re-rendered. Reading react-dom 19.2.0's
  `cjs/react-dom-client.development.js`:
  `createAndAccumulateChangeEvent` records the event target for
  restoration (`:3512-3523`); the `finally` of `batchedUpdates$1`
  (`:3251-3272`) flushes sync work and then calls `restoreStateOfTarget`
  (`:3178`), which hands the props React last committed to `updateInput`
  (`:1644`); `updateInput` assigns `element.value` whenever it differs
  from the prop (`:1661-1667`). **So a refused character comes off the
  screen inside the discrete event, with nothing re-rendered** — measured
  below beside a body-run count of zero.

  What React does not do is put the caret back. Assigning `value` moves
  the text entry cursor to the end of the control (HTML standard, the
  `value` IDL attribute setter), and React restores a selection only
  around a commit in which focus MOVED. So every write React makes —
  a rejection, an uppercasing, a regrouping — lands the caret at the end
  of the string.

  ### `:uix-reagent-input` — the element is made uncontrolled

  `value` is deleted from the props, `defaultValue` is set and a `ref` is
  installed (`uix/compiler/input.cljs:124-127`), so React's restore has
  nothing to restore: `updateInput` skips the write entirely when the
  `value` prop is absent. UIx drives the value itself, and it schedules
  that work on `reagent.impl.batching/do-after-render`, a queue drained
  from `requestAnimationFrame` (`reagent/impl/batching.cljs:16-25`,
  `:57-59`). Value and caret both come back correct — by offset from the
  END of the string, which is the algorithm Arm 2 used — but **one
  animation frame later, never inside the discrete event.**

  ## What rf2-n3dxw actually is

  The bead recorded `:unchanged-model-rejection` as a React defect: the
  refused character stayed on the screen. **It is not React's.** The row
  was measured in the full `:browser-test` bundle, which carries Reagent,
  so what it measured was `:uix-reagent-input`'s one-frame lag. On React
  the character is gone before `dispatchEvent` returns, and that is now
  asserted.

  The bead stays open for the half that is real: **neither implementation
  gives both same-turn convergence and a caret at the position before the
  refused character.** React converges in-turn and puts the caret at the
  end; UIx's port puts the caret in the right place a frame late.

  ## The door is a variable too

  `re-frame.core/dispatch` is asynchronous by design: the router's drain
  rides `interop/next-tick`, a **macrotask** (Spec 002 §Drain
  scheduling). So a controlled field written through the queued door
  cannot echo inside the discrete event — the model is still the old one
  when the event returns. The synchronous door (`dispatch-sync`, which
  `use-frame` publishes beside `dispatch`) is what buys the same-turn
  echo. Both are witnessed here.

  ## Typing is simulated the way the browser does it

  [[type-into!]] mutates the field **first** and fires `input`
  **second**, because that is the whole difficulty: by the time any
  handler runs, the character is already on screen. It writes through
  `HTMLInputElement.prototype`'s own `value` setter — React patches the
  *instance* setter to maintain its change tracker, and a plain `set!`
  updates the tracker too, after which React discards the `input` event
  as a no-op change and nothing under test ever runs.

  Every synchronous assertion below runs on the line after
  `dispatchEvent` returns. There is no `act()` anywhere in this file.
  `flushSync` appears in the mount/teardown door and in [[settle!]],
  which lets an already-scheduled sync-lane `useSyncExternalStore`
  notification land after an OUT-OF-BAND dispatch — never inside the
  discrete-event path an assertion reads. The one exception is
  [[converge!]], where a `flushSync` inside the discrete event is not
  scaffolding but the mechanism being measured, and it is used by that
  row alone.

  ## What is asserted elsewhere, and what is still not

  - `:ime-composition-commits-nothing` — **established, and asserted by
    the real-composition harness rather than here** (rf2-o27h3):
    `bench/hicasso/ime_run.cjs` drives CDP `Input.imeSetComposition` /
    `insertText` / `dispatchKeyEvent` — trusted composition events, a
    real composition range, real mid-composition keydowns — against
    three pages, one implementation each: plain React, the port, and
    Arm 1's element path with its converge. The commit fence holds on
    `isComposing` and on keyCode-229 independently; a model-agreeing
    exchange survives to `compositionend`; a cancelled exchange leaves
    field and model exactly as before. It stays out of THIS file
    because an `Event` dispatched from page script exercises neither
    React's composition plumbing nor the browser's composition state —
    the very reason the row sat unasserted so long — and `:browser-test`
    runs in-page. What the harness also measured: every implementation
    (React's own restore included) rewrote a refused/normalised value
    mid-composition and silently destroyed the exchange — rf2-digtt.
    The operator ruled the carve-out IN on 2026-08-03, and it landed in
    **Arm 1's element path only**: the two implementations THIS file
    measures are the ones that still abort, which is what makes the
    divergence a divergence. Nothing here changes.
  - `teardown-leaves-no-boundary-and-no-edge` — dropped as a duplicate.
    Arm 1's dogfood suite already asserts zero residue after unmount
    (`:cells :cell-refs :boundaries :edges :entries` all 0), and one
    assertion gets one home."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uixa]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [uix.core :refer [$ defui]]
            [uix.compiler.input]
            ;; Required so `:uix-reagent-input` can be SELECTED rather
            ;; than merely inherited: UIx's port reaches Reagent's
            ;; after-render queue through the `reagent.impl.batching`
            ;; global, which only exists once that namespace is loaded.
            [reagent.impl.batching]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]))

(def cells
  "The witness size validation.md names for the controlled grid."
  100)

(def frame-id ::grid)

;; ---------------------------------------------------------------------------
;; The model — Arm 2's, unchanged
;; ---------------------------------------------------------------------------

(defn group-digits
  "`\"12345\"` → `\"12,345\"`. Non-digits are dropped, which is what makes
  this a normalisation rather than a rejection."
  [s]
  (let [ds (str/replace (str s) #"[^0-9]" "")
        n  (count ds)]
    (if (<= n 3)
      ds
      (->> (reverse ds)
           (partition-all 3)
           (map (comp str/join reverse))
           reverse
           (str/join ",")))))

(defn apply-policy
  "What the model does with a typed value. `old` is what it holds now,
  which is the whole of a rejection: the model returns itself."
  [policy old v]
  (case policy
    :digits (if (re-matches #"[0-9]*" v) v old)
    :upper  (str/upper-case v)
    :group  (group-digits v)
    v))

(rf/reg-sub :cgrid/cell (fn [db [_ i]] (get-in db [:cells i] "")))

(rf/reg-event :cgrid/seed
  (fn [_ [_ n]]
    {:db {:cells    (into {} (map (fn [i] [i ""])) (range n))
          :policies {11 :digits 13 :upper 17 :group}}}))

(rf/reg-event :cgrid/edit
  (fn [{:keys [db]} [_ i v]]
    (let [policy (get-in db [:policies i] :plain)
          old    (get-in db [:cells i] "")]
      {:db (assoc-in db [:cells i] (apply-policy policy old v))})))

;; The door an out-of-band correction arrives through — a server
;; normalisation, a debounce, a validation that resolves late.
(rf/reg-event :cgrid/set
  (fn [{:keys [db]} [_ i v]] {:db (assoc-in db [:cells i] v)}))

;; ---------------------------------------------------------------------------
;; The page — one React boundary per cell, on the shipped UIx adapter
;; ---------------------------------------------------------------------------

(def ^:private !body-runs
  "How many boundary bodies have run. The per-keystroke budget is counted
  here rather than asserted about."
  (atom 0))

(def ^:private !rendered
  "Cell index → the value that cell's LAST render put on the element. It
  stands in for the per-instance storage the [[converge!]] prototype
  below would need, and naming it is half of that option's price."
  (atom {}))

(defn- converge!
  "THE PROPOSED THIRD BEHAVIOUR, PROTOTYPED FOR MEASUREMENT ONLY.

  Not an authoring pattern and not a proposed API: in a real adapter this
  is the element path's business, wrapping `:on-change` where the user
  never sees it. It sits in the view here only because neither Hicasso
  nor the UIx adapter mints its own `:input` element today — UIx does.

  Run at the end of the change handler, i.e. still inside the discrete
  event and still ahead of React's own end-of-event restore:

  1. `flushSync` so the synchronous door's commit lands NOW rather than
     in the `finally` of React's `batchedUpdates$1`;
  2. if the field still disagrees with what the element renders, write
     the rendered value — which also makes React's later `updateInput`
     a no-op, because it only assigns when the two differ;
  3. put the caret back by offset from the END of the string.

  Step 2 needs to know what the element renders NOW, which is why
  [[!rendered]] exists: the handler's own closure carries the value from
  the render that MINTED it, and after a re-render that value is stale —
  the trap that makes `(= (.-value node) dom-value)` alone insufficient,
  because it cannot tell a refusal from a keystroke the model took
  verbatim."
  [i e]
  (let [node      (.-target e)
        dom-value (.-value node)
        caret-was (.-selectionStart node)]
    (react-dom/flushSync (fn [] nil))
    (let [rendered (get @!rendered i "")]
      (when-not (= (.-value node) rendered)
        (set! (.-value node) rendered))
      (let [offset (- (count dom-value) caret-was)
            c      (max 0 (- (count (.-value node)) offset))]
        (.setSelectionRange node c c)))))

(defui cell-view [{:keys [i door converge?]}]
  (swap! !body-runs inc)
  (let [v                                (uixa/use-subscribe [:cgrid/cell i])
        {:keys [dispatch dispatch-sync]} (uixa/use-frame)
        send                             (if (= :queued door) dispatch dispatch-sync)]
    (swap! !rendered assoc i v)
    ($ :div.cell
       ($ :input.inp {:id        (str "c" i)
                      :type      "text"
                      :value     v
                      :on-change (fn [e]
                                   (send [:cgrid/edit i (.. e -target -value)])
                                   (when converge? (converge! i e)))}))))

(defui grid-view [{:keys [n door converge?]}]
  ($ :div.grid {:role "group"}
     (for [i (range n)]
       ($ cell-view {:key i :i i :door door :converge? converge?}))))

;; ---------------------------------------------------------------------------
;; Pinning the input implementation
;; ---------------------------------------------------------------------------

(def input-implementations
  "The two things `uix.compiler.aot/create-uix-input` can build, and the
  value of `uix.compiler.input/*use-reagent-input-enabled?*` that selects
  each. `nil` — UIx's OWN unset default — is not a third option; it is
  *whichever of these two the bundle's contents imply*, which is what
  this file exists to stop measuring by accident. Since rf2-heqwo,
  `re-frame.adapter.uix` pins the var to `false` at load, so `nil` is no
  longer what a re-frame2 UIx app runs; it is reachable here only by
  deliberately clearing the pin."
  {:react             false
   :uix-reagent-input true})

(def adapter-default-implementation
  "What `re-frame.adapter.uix` pins at load (rf2-heqwo). Rows restore THIS,
  not `nil`: leaving the var cleared would hand the choice back to the
  bundle for every later namespace in the same test build — the exact
  accident this file exists to stop."
  :react)

(defn- pin-input-implementation! [impl]
  (set! uix.compiler.input/*use-reagent-input-enabled?*
        (get input-implementations impl)))

(defn- unpin-input-implementation! []
  (pin-input-implementation! adapter-default-implementation))

(defn- with-uix-raw-default
  "Run `f` with the adapter's pin CLEARED, so UIx's own classpath sniff can
  be observed, and restore the pin however `f` ends."
  [f]
  (set! uix.compiler.input/*use-reagent-input-enabled?* nil)
  (try (f) (finally (unpin-input-implementation!))))

;; ---------------------------------------------------------------------------
;; The harness
;; ---------------------------------------------------------------------------

(def ^:private off-browser
  "no DOM on this runtime — the claim is a caret in a real text field,
  and :browser-test is where it is asked")

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- settle!
  "Let an already-scheduled sync-lane notification commit — the empty
  `flushSync`, used only after an OUT-OF-BAND dispatch. Not `act`: `act`
  diverts work to a queue that is not the browser's."
  []
  (react-dom/flushSync (fn [] nil))
  nil)

(defn- after-a-frame
  "Give Reagent's after-render queue the animation frame it is scheduled
  on, then a macrotask for anything that frame scheduled in turn."
  [f]
  (js/requestAnimationFrame (fn [] (js/setTimeout f 0))))

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- mount!
  ([container n] (mount! container n {}))
  ([container n {:keys [door converge?]}]
   (rf/make-frame {:id frame-id :initial-events [[:cgrid/seed n]]})
   (reset! !rendered {})
   (let [root (react-dom-client/createRoot container)]
     (react-dom/flushSync
      (fn [] (.render root ($ uixa/frame-provider {:frame frame-id}
                              ($ grid-view {:n n :door door :converge? converge?})))))
     root)))

(defn- release! [root c]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove c)
  (unpin-input-implementation!)
  nil)

(defn- with-grid
  "Mount the grid with `impl` PINNED, run `f` with the container, and tear
  it down whatever happens. Synchronous tests only — an async body
  returns before its timer fires, and a grid torn down under a pending
  callback dispatches into a frame that is gone."
  {:arglists '([impl f] [impl opts f])}
  ([impl f] (with-grid impl {} f))
  ([impl opts f]
   (pin-input-implementation! impl)
   (let [c    (container!)
         root (mount! c cells opts)]
     (try (f c)
          (finally (release! root c))))))

(defn- cell-input [container i] (.querySelector container (str "#c" i)))

(defn- model-value
  "What app-db says cell `i` holds — the other half of every agreement
  assertion, read from the frame rather than from the renderer."
  [i]
  (get-in (rf/app-db-value frame-id) [:cells i] ""))

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

(defn- set-model!
  "Put a value in the model out of band — the setup door, and also the
  async-normalisation door."
  [i v]
  (rf/dispatch-sync [:cgrid/set i v] {:frame frame-id})
  (settle!)
  nil)

;; The fixture snapshots the registrar at the moment THIS form is
;; evaluated, so it has to sit below the `reg-*` calls above — a
;; `use-fixtures` placed at the top of the file strands every one of
;; them and the whole grid renders empty.
(use-fixtures :each (test-support/make-reset-runtime-fixture
                     {:adapter uixa/adapter :ambient-frame nil :async? true}))

;; ---------------------------------------------------------------------------
;; The selector itself — the reason every row below names an implementation
;; ---------------------------------------------------------------------------

(deftest the-input-implementation-is-the-adapters-choice-not-the-bundles
  (testing "UIx's own unset answer is a fact about the classpath, and this
           bundle carries Reagent — so unset, a UIx `:input` here would be
           the port. `re-frame.adapter.uix` pins it to React (rf2-heqwo), so
           it is not."
    (is (some? reagent.impl.batching/do-after-render)
        "Reagent's after-render queue is in this bundle — which is exactly
         what makes UIx's own unset answer `true`")
    (with-uix-raw-default
      (fn []
        (is (true? (uix.compiler.input/should-use-reagent-input?))
            "clear the adapter's pin and this bundle's contents choose:
             a UIx `:input` here would not be a plain React controlled
             input at all")))
    (is (false? (uix.compiler.input/should-use-reagent-input?))
        "the adapter's load-time pin holds — React's own implementation,
         whatever else the bundle carries")
    (pin-input-implementation! :react)
    (is (false? (uix.compiler.input/should-use-reagent-input?)))
    (pin-input-implementation! :uix-reagent-input)
    (is (true? (uix.compiler.input/should-use-reagent-input?))
        "the explicit opt-in still reaches the port")
    (unpin-input-implementation!)))

;; ---------------------------------------------------------------------------
;; :same-turn-echo — and the per-keystroke budget
;; ---------------------------------------------------------------------------

(deftest the-field-and-the-model-agree-before-the-event-returns
  (testing "a keystroke reaches app-db and comes back to the DOM inside the
           discrete event — no act, no flushSync, no yield"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 7)]
            (.focus n)
            (type-into! n "hello")
            (is (= "hello" (model-value 7)) "the model took the keystroke")
            (is (= "hello" (.-value n))
                "and the field shows it, on the next line — which is the
                 synchronous door's whole purchase")))))))

(deftest the-echo-is-one-body-run-not-one-hundred
  (testing "the per-keystroke budget: a keystroke in cell 7 re-runs cell 7"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 7)]
            (.focus n)
            (reset! !body-runs 0)
            (type-into! n "x")
            (is (= 1 @!body-runs)
                (str "one boundary body ran, not " cells))))))))

;; ---------------------------------------------------------------------------
;; :mid-string-caret
;; ---------------------------------------------------------------------------

(deftest editing-mid-string-leaves-the-caret-where-the-typing-put-it
  (if-not (browser?)
    (is true off-browser)
    (with-grid :react
      (fn [c]
        (let [n (cell-input c 7)]
          (set-model! 7 "abcd")
          (.focus n)
          (.setSelectionRange n 2 2)
          (type-into! n "X")
          (is (= "abXcd" (model-value 7)))
          (is (= "abXcd" (.-value n)))
          (is (= [3 3] (caret n))
              "the caret did not jump to the end — the model agreed with the
               field, so React wrote nothing"))))))

;; ---------------------------------------------------------------------------
;; Normalisation — where the two implementations part company
;; ---------------------------------------------------------------------------

(deftest a-normalising-model-moves-the-caret-to-the-end-on-react
  (testing "the model uppercases what was typed, so React WRITES, and every
           write React makes lands the caret at the end of the string —
           the classic controlled-input caret jump, not a re-frame2 defect"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 13)]
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

(deftest the-uix-port-keeps-the-caret-mid-string-through-a-normalisation
  (testing "the same keystroke on the other implementation: UIx's port owns
           the write, and restores the caret by offset from the END of the
           string — Arm 2's algorithm, and Reagent's before it"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :uix-reagent-input
        (fn [c]
          (let [n (cell-input c 13)]
            (set-model! 13 "ABCD")
            (.focus n)
            (.setSelectionRange n 2 2)
            (type-into! n "x")
            (is (= "ABXCD" (model-value 13)))
            (is (= "ABXCD" (.-value n)))
            (is (= [3 3] (caret n))
                "still after the character just typed")))))))

(deftest a-grouping-model-keeps-the-caret-after-the-digit-just-typed
  (testing "1,234 + \"5\" becomes 12,345 — one character longer than what the
           user typed. The caret survives on React here only because the
           edit was at the END of the field, which is where React's write
           puts it anyway"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 17)]
            (set-model! 17 "1,234")
            (.focus n)
            (.setSelectionRange n 5 5)
            (type-into! n "5")
            (is (= "12,345" (model-value 17)))
            (is (= "12,345" (.-value n)))
            (is (= [6 6] (caret n))
                "the caret is still after the 5")))))))

;; ---------------------------------------------------------------------------
;; :selection-preserved
;; ---------------------------------------------------------------------------

(deftest a-converge-elsewhere-leaves-this-fields-selection-alone
  (testing "a commit that changes a DIFFERENT cell writes nothing here, so
           nothing here moves"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 7)]
            (set-model! 7 "abcdef")
            (.focus n)
            (.setSelectionRange n 1 4)
            (set-model! 23 "elsewhere")
            (is (= [1 4] (caret n)))))))))

(deftest a-range-does-not-survive-a-converge-that-writes-on-either-implementation
  (testing "Arm 2 restored both ends of a selection by distance from the end
           of the string and required [2 5]. Neither shipped implementation
           does: React resets the cursor to the end of the new value, and
           UIx's port restores ONE offset into both `selectionStart` and
           `selectionEnd` by construction (uix/compiler/input.cljs:68-69).
           A range collapses. rf2-n3dxw records it; nothing here pretends
           otherwise"
    (if-not (browser?)
      (is true off-browser)
      (do
        (with-grid :react
          (fn [c]
            (let [n (cell-input c 7)]
              (set-model! 7 "abcdef")
              (.focus n)
              (.setSelectionRange n 1 4)
              (set-model! 7 "Xabcdef")
              (is (= "Xabcdef" (.-value n)))
              (is (= [7 7] (caret n))
                  "React put the cursor at the end of the value it wrote"))))
        (with-grid :uix-reagent-input
          (fn [c]
            (let [n (cell-input c 7)]
              (set-model! 7 "abcdef")
              (.focus n)
              (.setSelectionRange n 1 4)
              (set-model! 7 "Xabcdef")
              (is (= "Xabcdef" (.-value n)))
              (is (= [2 2] (caret n))
                  "the port kept the offset from the end — as a cursor, not
                   as a range"))))))))

;; ---------------------------------------------------------------------------
;; :unchanged-model-rejection — the row Arm 2 called decisive (rf2-n3dxw)
;; ---------------------------------------------------------------------------

(deftest a-refused-keystroke-moves-no-model-and-re-runs-no-boundary
  (testing "cell 11 takes digits only. The browser has already shown the
           letter; the model does not move, so NOTHING re-renders"
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 11)]
            (set-model! 11 "12")
            (.focus n)
            (.setSelectionRange n 2 2)
            (reset! !body-runs 0)
            (type-into! n "a")
            (is (= "12" (model-value 11)) "the model refused the letter")
            (is (zero? @!body-runs)
                "and no boundary body ran at all — the value the model holds
                 did not change, so there was nothing for React to re-render")))))))

(deftest a-refused-keystroke-is-taken-off-the-screen-inside-the-event
  (testing "THE ROW rf2-n3dxw WAS OPENED FOR, and React meets it.

           Nothing re-rendered — the test above counts zero body runs on
           this exact keystroke — so the write below cannot have come from
           a render. It comes from React's own controlled-state restore:
           the change event enqueued this node
           (`react-dom-client.development.js:3512-3523`), and the `finally`
           of `batchedUpdates$1` (`:3251-3272`) handed the committed props
           to `updateInput` (`:1644`), which assigned `value` because it
           differed (`:1661-1667`). All of it before `dispatchEvent`
           returned.

           The caret lands at 2 because that is where React's write leaves
           it — the end of the string — which happens to be the position
           before the refused character when the edit was AT the end. The
           test below shows what that costs mid-string."
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 11)]
            (set-model! 11 "12")
            (.focus n)
            (.setSelectionRange n 2 2)
            (reset! !body-runs 0)
            (type-into! n "a")
            (is (= "12" (.-value n))
                "the refused character is off the screen, on the next line")
            (is (= "12" (model-value 11))
                "and the field and the model agree")
            (is (zero? @!body-runs)
                "with nothing re-rendered to do it")
            (is (= [2 2] (caret n))
                "caret at the position before the refused character")))))))

(deftest a-refused-keystroke-mid-string-converges-with-the-caret-at-the-end
  (testing "RECORDED BEHAVIOUR, NOT DESIRED BEHAVIOUR (rf2-n3dxw).

           This is the residue after the headline row was corrected. The
           refused character IS removed, in the same turn — but React's
           write moves the cursor to the end of the control, so a user
           editing mid-string is thrown to the end of the field on every
           refused keystroke. The assertion is written to the MEASURED
           value deliberately: the day something puts the caret back at 2
           this goes red, and whoever makes it go red is exactly the person
           who should be reading rf2-n3dxw."
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react
        (fn [c]
          (let [n (cell-input c 11)]
            (set-model! 11 "12345")
            (.focus n)
            (.setSelectionRange n 2 2)
            (type-into! n "z")
            (is (= "12345" (.-value n))
                "the refused character is gone")
            (is (= "12345" (model-value 11)))
            (is (= [5 5] (caret n))
                "but the caret is at the end of the field, not at 2")))))))

(deftest the-uix-port-takes-the-refused-character-off-one-frame-late
  (testing "RECORDED BEHAVIOUR, NOT DESIRED BEHAVIOUR (rf2-n3dxw).

           The other implementation gets the caret right and the timing
           wrong. Its convergence rides `do-after-render`, whose queue is
           drained from `requestAnimationFrame`, so on the line after
           `dispatchEvent` the refused character is still on the screen —
           which is what the bead originally recorded, and attributed to
           React."
    (if-not (browser?)
      (is true off-browser)
      (async done
        (pin-input-implementation! :uix-reagent-input)
        (let [c    (container!)
              root (mount! c cells)
              n    (cell-input c 11)]
          (set-model! 11 "12345")
          (.focus n)
          (.setSelectionRange n 2 2)
          (type-into! n "z")
          (is (= "12z345" (.-value n))
              "still on the screen when the discrete event returns")
          (is (= "12345" (model-value 11)) "though the model refused it")
          (after-a-frame
           (fn []
             (try
               (is (= "12345" (.-value n))
                   "one animation frame later the port has converged")
               (is (= [2 2] (caret n))
                   "and the caret is at the position before the refused
                    character — the half React does not give")
               (catch :default e
                 (is false (str "the deferred converge threw: " (ex-message e)))))
             (release! root c)
             (done))))))))

;; ---------------------------------------------------------------------------
;; The priced option — both halves, in one turn (rf2-n3dxw)
;; ---------------------------------------------------------------------------

(deftest a-same-turn-converge-can-have-both-halves-at-a-stated-price
  (testing "MEASUREMENT OF A PROPOSAL, not of anything that ships.

           Neither implementation above gives same-turn convergence AND a
           caret at the position before the refused character. [[converge!]]
           does, on every row of the family at once, on the synchronous
           door. What it costs is stated rather than hidden: one
           `flushSync` per keystroke on a controlled element, and a
           per-instance record of the value that element last rendered
           ([[!rendered]] here). It costs no user-visible ceremony — no
           ref, no effect, nothing in the view but the ordinary
           `:value`/`:on-change` pair — and no hook in the boundary shell.

           The one thing it cannot do is the queued door: `dispatch`
           drains on a macrotask, so at the end of the change handler the
           model has not moved yet and there is nothing to converge to."
    (if-not (browser?)
      (is true off-browser)
      (with-grid :react {:converge? true}
        (fn [c]
          (let [reject (cell-input c 11)
                upper  (cell-input c 13)
                group  (cell-input c 17)
                plain  (cell-input c 7)]
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
                  "the caret is at the position before the refused character,
                   in the same turn"))
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
              (is (= [3 3] (caret plain))))))))))

;; ---------------------------------------------------------------------------
;; :async-normalisation, and the queued door
;; ---------------------------------------------------------------------------

(deftest a-correction-that-arrives-later-still-converges
  (testing "the same restore driven by a timer instead of a keystroke — the
           shape a server normalisation or a debounced validation takes"
    (if-not (browser?)
      (is true off-browser)
      (async done
        ;; NOT `with-grid`: its `finally` runs the moment the body returns,
        ;; and the body of an async test returns before its timer fires.
        (pin-input-implementation! :react)
        (let [c    (container!)
              root (mount! c cells)
              n    (cell-input c 17)]
          (set-model! 17 "1234")
          (.focus n)
          (.setSelectionRange n 4 4)
          (js/setTimeout
           (fn []
             (try
               (set-model! 17 (group-digits "1234"))
               (is (= "1,234" (.-value n)) "the late correction reached the field")
               (is (= [5 5] (caret n))
                   "and the caret is still after the last digit")
               (catch :default e
                 (is false (str "the late correction threw: " (ex-message e)))))
             (release! root c)
             (done))
           0))))))

(deftest the-queued-door-converges-one-macrotask-later
  (testing "the same field on `dispatch` instead of `dispatch-sync`. The
           router's drain is a macrotask by design (Spec 002 §Drain
           scheduling), so the model cannot have moved when the discrete
           event returns — and this is the contrast that makes the door,
           not the renderer, the variable"
    (if-not (browser?)
      (is true off-browser)
      (async done
        (pin-input-implementation! :react)
        (let [c    (container!)
              root (mount! c cells {:door :queued})
              n    (cell-input c 3)]
          (.focus n)
          (type-into! n "ab")
          (is (= "" (model-value 3))
              "the model has NOT moved inside the discrete event")
          (js/setTimeout
           (fn []
             (settle!)
             (is (= "ab" (model-value 3)) "one macrotask later the drain has run")
             (is (= "ab" (.-value n)) "and the field agrees with it")
             (release! root c)
             (done))
           0))))))
