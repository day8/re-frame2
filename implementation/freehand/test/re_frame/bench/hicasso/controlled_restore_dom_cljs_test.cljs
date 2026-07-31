(ns re-frame.bench.hicasso.controlled-restore-dom-cljs-test
  "WHAT CORRECT MEANS FOR A CONTROLLED INPUT, on React (rf2-m6if4).

  Arm 2 (the PATCH renderer) is retired — Mike ruled on 2026-07-31 that
  Hicasso is an adapter for React — and this file is what its hard gate
  left behind. Arm 2's `:controlled/grid-100` witness was the clearest
  statement in the repo of what a store-backed controlled input has to
  do, and the statement is worth keeping even though the renderer that
  provoked it is not. The assertions below are **moved** here, not
  copied: the arm2 tree goes in the same change.

  ## Why the grid moved instead of being deleted

  Arm 2 owned the end-of-event restore because it had no React. The
  reflex is to say React makes the whole family free. **It does not**,
  and this file is the measurement rather than the reflex.

  The model is Arm 2's, unchanged, because the model was never the
  renderer's business: 100 cells, one event, one subscription, and a
  per-cell policy in `app-db`.

  | policy | what the model does with the typed value |
  |---|---|
  | `:plain` | takes it |
  | `:digits` | **refuses** it unless it is all digits — the model does not move |
  | `:upper`  | normalises it to upper case |
  | `:group`  | normalises `12345` to `12,345` — the length changes |

  ## The door is the variable, not the renderer

  `re-frame.core/dispatch` is asynchronous by design: the router's drain
  rides `interop/next-tick`, a **macrotask** (Spec 002 §Drain
  scheduling). So a controlled field written through the queued door
  cannot echo inside the discrete event — the model is still the old one
  when the event returns. The synchronous door (`dispatch-sync`, which
  `use-frame` publishes beside `dispatch`) is what buys the same-turn
  echo. Both are witnessed here, and the contrast is the finding: **on
  React the controlled-input question is a question about the dispatch
  door, not about the differ.**

  ## Typing is simulated the way the browser does it

  [[type-into!]] mutates the field **first** and fires `input`
  **second**, because that is the whole difficulty: by the time any
  handler runs, the character is already on screen. It writes through
  `HTMLInputElement.prototype`'s own `value` setter — React patches the
  *instance* setter to maintain its change tracker, and a plain `set!`
  updates the tracker too, after which React discards the `input` event
  as a no-op change and nothing under test ever runs.

  Every synchronous assertion below runs on the line after
  `dispatchEvent` returns. There is no `act()` anywhere in this file;
  `flushSync` appears only in the mount/teardown door and in
  [[settle!]], which lets an already-scheduled sync-lane
  `useSyncExternalStore` notification land after an OUT-OF-BAND
  dispatch — never inside the discrete-event path an assertion reads.

  ## What was dropped, and why

  Three of Arm 2's rows are not here.

  - `:ime-composition-commits-nothing` — **not established, not
    asserted.** Arm 2 owned a composition fence because it owned the
    writes. Whether React's own path leaves a composing field alone and
    then converges at `compositionend` could not be settled with
    synthetic `Event`s of those two names, and a fence that is asserted
    without being demonstrated is worse than no assertion. Open on
    rf2-n3dxw.
  - `:selection-preserved` across a converge **that writes** — measured
    `[2 2]` where Arm 2 required `[2 5]`; a range does not survive as a
    range. Also on rf2-n3dxw. The half that does hold — an unchanged
    model leaves another field's selection alone — is asserted below.
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

(defui cell-view [{:keys [i door]}]
  (swap! !body-runs inc)
  (let [v                                (uixa/use-subscribe [:cgrid/cell i])
        {:keys [dispatch dispatch-sync]} (uixa/use-frame)
        send                             (if (= :queued door) dispatch dispatch-sync)]
    ($ :div.cell
       ($ :input.inp {:id        (str "c" i)
                      :type      "text"
                      :value     v
                      :on-change (fn [e] (send [:cgrid/edit i (.. e -target -value)]))}))))

(defui grid-view [{:keys [n door]}]
  ($ :div.grid {:role "group"}
     (for [i (range n)]
       ($ cell-view {:key i :i i :door door}))))

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

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- mount!
  ([container n] (mount! container n :sync))
  ([container n door]
   (rf/make-frame {:id frame-id :initial-events [[:cgrid/seed n]]})
   (let [root (react-dom-client/createRoot container)]
     (react-dom/flushSync
      (fn [] (.render root ($ uixa/frame-provider {:frame frame-id}
                              ($ grid-view {:n n :door door})))))
     root)))

(defn- release! [root c]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove c)
  nil)

(defn- with-grid
  "Mount the grid, run `f` with the container, and tear it down whatever
  happens. Synchronous tests only — an async body returns before its
  timer fires, and a grid torn down under a pending callback dispatches
  into a frame that is gone."
  [f]
  (let [c    (container!)
        root (mount! c cells)]
    (try (f c)
         (finally (release! root c)))))

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
;; :same-turn-echo — and the per-keystroke budget
;; ---------------------------------------------------------------------------

(deftest the-field-and-the-model-agree-before-the-event-returns
  (testing "a keystroke reaches app-db and comes back to the DOM inside the
           discrete event — no act, no flushSync, no yield"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
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
      (with-grid
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
    (with-grid
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
;; Normalisation — the length-preserving and the length-changing case
;; ---------------------------------------------------------------------------

(deftest an-uppercasing-model-keeps-the-caret-mid-string
  (if-not (browser?)
    (is true off-browser)
    (with-grid
      (fn [c]
        (let [n (cell-input c 13)]
          (set-model! 13 "ABCD")
          (.focus n)
          (.setSelectionRange n 2 2)
          (type-into! n "x")
          (is (= "ABXCD" (model-value 13)) "the model normalised the case")
          (is (= "ABXCD" (.-value n)) "and the field followed")
          (is (= [3 3] (caret n))
              "the caret is still after the character just typed — React saves
               and restores the selection around a commit that writes"))))))

(deftest a-grouping-model-keeps-the-caret-after-the-digit-just-typed
  (testing "1,234 + \"5\" becomes 12,345 — one character longer than what the
           user typed, which is the case an absolute caret offset gets wrong"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
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
;; :selection-preserved — the half that holds
;; ---------------------------------------------------------------------------

(deftest a-converge-elsewhere-leaves-this-fields-selection-alone
  (testing "a commit that changes a DIFFERENT cell writes nothing here, so
           nothing here moves"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (cell-input c 7)]
            (set-model! 7 "abcdef")
            (.focus n)
            (.setSelectionRange n 1 4)
            (set-model! 23 "elsewhere")
            (is (= [1 4] (caret n)))))))))

;; ---------------------------------------------------------------------------
;; :unchanged-model-rejection — the row Arm 2 called decisive
;; ---------------------------------------------------------------------------

(deftest a-refused-keystroke-moves-no-model-and-re-runs-no-boundary
  (testing "cell 11 takes digits only. The browser has already shown the
           letter; the model does not move, so NOTHING re-renders"
    (if-not (browser?)
      (is true off-browser)
      (with-grid
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

(deftest recorded-a-refused-keystroke-is-not-taken-off-the-screen
  (testing "RECORDED BEHAVIOUR, NOT DESIRED BEHAVIOUR (rf2-n3dxw).

           This is the row Arm 2 met and the React path does not. Arm 2
           converged the focused node at the end of every commit, so the
           refused character came off the screen with the caret intact.
           Here nothing re-renders — see the test above — and React's own
           end-of-discrete-event state restore does not put the field
           back either, so the field and the model disagree and every
           later edit compounds it.

           The assertion is written to the MEASURED value deliberately:
           it is a tripwire. The day the adapter takes the character off
           the screen this test goes red, and whoever makes it go red is
           exactly the person who should be reading rf2-n3dxw."
    (if-not (browser?)
      (is true off-browser)
      (with-grid
        (fn [c]
          (let [n (cell-input c 11)]
            (set-model! 11 "12")
            (.focus n)
            (.setSelectionRange n 2 2)
            (type-into! n "a")
            (is (= "12a" (.-value n))
                "the refused character is still on screen")
            (is (= "12" (model-value 11))
                "while the model says it was never accepted")))))))

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
        (let [c    (container!)
              root (mount! c cells :queued)
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
