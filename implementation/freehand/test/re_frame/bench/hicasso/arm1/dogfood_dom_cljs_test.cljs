(ns re-frame.bench.hicasso.arm1.dogfood-dom-cljs-test
  "THE DOGFOOD SCREEN, MOUNTED — AND THE SIX-WEEK CLOCK (rf2-2rtt6.9).

  **HD-014 starts the K7 clock at the first Hicasso-arm commit that
  mounts the dogfood screen. This is that mount.** The operator is on
  record accepting it (rf2-2rtt6, 2026-07-31); K7 is never extended
  silently.

  validation.md asks for one list, one controlled field and subscription
  reads, written in three renderings — the collector surface, the grouped
  `use-subs` surface, and raw UIx — \"judged on diff and preference by
  its authors\". The written judgement is
  `docs/design/hicasso/studio/arm1-lean-react-dogfood-judgement.md`. What
  is asserted here is the half a judgement cannot supply: that the three
  renderings build **the same page**, so the comparison is about the
  reading surface and nothing else.

  Four claims:

  1. **Canonical-DOM parity** across all three, with attribute names
     sorted, and a control that proves the comparison can answer false.
  1c. **Intent parity on the two live renderings** (rf2-2rtt6.67): one
     real-DOM interaction script driven at the collector and at raw UIx,
     the dispatched event vectors captured at the substrate's own
     `:events` listener stream, and both captures asserted equal to the
     script's STATED expectation — including two composing keystrokes
     that must dispatch nothing. The script reaches **every handler site
     on the screen and both branches of both key-maps**; the coverage map
     is on [[interaction-steps]], which is where a reader checks that the
     claim and the script are the same size. DOM parity proves the
     renderings build the same page; this is the half that proves the
     page MEANS the same thing when a user touches it.

     **And the comparator PINS the controlled-input implementation it is
     compared against (rf2-2rtt6.75).** The script types into two UIx
     `:input`s (`dogfood_uix.cljs`'s `.new-input` and `.draft`), and
     `uix.compiler.aot/create-uix-input` can build either React's
     controlled input or UIx's port of Reagent's rAF-driven one. Which
     one was INHERITED here — correct, but only because
     `re-frame.adapter.uix` pins it at load (rf2-heqwo), never because
     this row asked. Measured, not inferred: with that pin cleared every
     claim in this namespace stayed GREEN while those two fields were the
     Reagent port, so the gate was silent about which product it was
     comparing against (rf2-2rtt6.41 reproduced exactly that). The
     `:each` fixture now pins [[adapter-default-implementation]] going in
     and witnesses it coming out ([[pin-is-adapters-default!]]), so the
     comparison names its own subject.
  2. **The narrow write touches one row.** A toggle re-renders the row it
     names and no other, on both Hicasso renderings — which is the index
     doing its job through React rather than a claim about it.
  3. **The controlled field echoes in the caller's turn** (HD-019's
     synchronous door), and the collector's conditional read means a
     completed row holds one edge where the grouped rendering holds two.

  No clock is read and no bar row is published: the clock gate lines are
  being re-taken (rf2-b0tz5) and this arm's rows are scored against the
  restated bar later.

  Runtime: `-dom-cljs-test`; under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.dogfood-collector :as collector]
            [re-frame.bench.hicasso.arm1.dogfood-grouped :as grouped]
            [re-frame.bench.hicasso.arm1.dogfood-uix :as raw-uix]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [uix.compiler.input]
            [uix.core :refer [$ defui]]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

;; ---------------------------------------------------------------------------
;; Pinning the input implementation
;; ---------------------------------------------------------------------------

(def input-implementations
  "The two things `uix.compiler.aot/create-uix-input` can build, and the
  value of `uix.compiler.input/*use-reagent-input-enabled?*` that selects
  each. `nil` — UIx's OWN unset default — is not a third option; it is
  *whichever of these two the bundle's contents imply*, which is exactly
  the thing a parity gate must not be deciding by accident."
  {:react false :uix-reagent-input true})

(def adapter-default-implementation
  "What `re-frame.adapter.uix` pins at load (rf2-heqwo) — i.e. what a
  re-frame2 UIx app actually renders `:input` with — and therefore what
  the comparator is held to. NOT `nil`: `cljs.test` runs every namespace
  in one shared JS runtime, so leaving the var cleared would hand the
  `:input` choice back to the bundle for every namespace scheduled after
  this one. That this literal is the adapter's real default is
  `controlled_grid_dom_cljs_test`'s claim to make, not this file's; here
  it is the subject the parity gate names."
  :react)

(defn- pin! [impl]
  (set! uix.compiler.input/*use-reagent-input-enabled?*
        (get input-implementations impl)))

(defn- pin-is-adapters-default!
  "The witness for a fault that is invisible where it is caused. A row
  that leaves the pin somewhere other than the adapter's own value breaks
  LATER namespaces, not itself — its own assertions stay green and the
  suite that reds is someone else's. So the check cannot live in a row
  (it would have to be scheduled last to mean anything, and `cljs.test`
  promises no order within a namespace); it lives in the `:each`
  teardown, where it reads the var after every row in this file. It
  OBSERVES and never repairs — a teardown that re-pinned would make
  itself true."
  []
  (is (= (get input-implementations adapter-default-implementation)
         uix.compiler.input/*use-reagent-input-enabled?*)
      "a row in this namespace has just left
       `uix.compiler.input/*use-reagent-input-enabled?*` somewhere other
       than the adapter's own pin — a cleared pin re-arms UIx's classpath
       sniff for every namespace scheduled after this one in the same
       build"))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; `:ambient-frame nil` is load-bearing, not tidiness. The fixture's
     ;; default leaves a dynamic-var frame stamp in scope, and the
     ;; carried-invariant chain resolves that tier BEFORE React context —
     ;; so the comparator's ambient `use-subscribe` would read the
     ;; ambient frame's app-db while `use-current-frame` reported the
     ;; provider's, and a parity miss would look like a rendering
     ;; difference. Caught by the frame probe below, which is why the
     ;; probe stays.
     :ambient-frame nil
     ;; The map shape, because the teardown claims are `async`: the cell
     ;; and entry reapers are macrotasks, so the residue a React unmount
     ;; leaves is not readable inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rt/reset-runtime!))}))

(use-fixtures :each
  {:before (fn []
             ((:before runtime-fixture))
             (pin! adapter-default-implementation))
   :after  (fn []
             ((:after runtime-fixture))
             (pin-is-adapters-default!))})

(def ^:private frame-id ::arm1-dogfood)
(def ^:private todo-count 6)

(defn- skip! [why] (is true (str "the dogfood screen needs a real React DOM — " why)))

(defn- fresh!
  "A frame holding exactly the seeded page, whatever a previous mount in
  this test did to it. Both halves are needed and neither is redundant:
  `make-frame!` is idempotent, so it will NOT replay its initial events
  for an id that already exists, and `reseed!` is the front half's own
  door for returning a live frame to its seeded state."
  []
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-id todo-count)
  (dogfood/reseed! frame-id todo-count)
  frame-id)

;; ---------------------------------------------------------------------------
;; The three mounts
;; ---------------------------------------------------------------------------

(defn- hicasso-mount!
  "Either Hicasso rendering, on the arm's own root."
  [screen]
  (mount/root! (mount/fresh-container!) frame-id [screen {}]))

(defn- uix-mount!
  "The comparator, on its own React root and its own `frame-provider`.
  Deliberately NOT the arm's root: the control must not borrow the
  candidate's plumbing, or a plumbing bug would hide inside the parity
  gate it is supposed to expose."
  []
  (let [container (mount/fresh-container!)
        root      (react-dom-client/createRoot container)]
    (react-dom/flushSync (fn [] (.render root (raw-uix/root frame-id))))
    {:root root :container container :uix? true}))

(defn- release-uix! [handle]
  (react-dom/flushSync (fn [] (.unmount (:root handle))))
  (when-some [p (.-parentNode (:container handle))] (.removeChild p (:container handle)))
  nil)

;; ---------------------------------------------------------------------------
;; 0 — the comparator resolves the same frame this test seeded
;; ---------------------------------------------------------------------------
;;
;; A parity miss between a candidate and its control is only informative
;; if the control was reading the same app-db. This asks that question on
;; its own, so a failure names the plumbing rather than the rendering.

(defui frame-probe [_props]
  ($ :div.probe {:data-frame (str (uix-adapter/use-current-frame))
                 :data-ambient (str (uix-adapter/use-subscribe [:dogfood/remaining]))
                 :data-explicit (str (uix-adapter/use-subscribe frame-id [:dogfood/remaining]))}))

(deftest the-comparator-reads-the-frame-this-test-seeded
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (is (= todo-count (rf/with-frame frame-id (deref (rf/subscribe [:dogfood/remaining]))))
          "the frame itself is seeded")
      (let [container (mount/fresh-container!)
            root      (react-dom-client/createRoot container)]
        (react-dom/flushSync
          (fn [] (.render root ($ uix-adapter/frame-provider {:frame frame-id}
                                  ($ frame-probe)))))
        (let [probe (.querySelector container ".probe")]
          (is (= (str frame-id) (.getAttribute probe "data-frame"))
              "the comparator's provider scopes the frame this test seeded")
          (is (= (str todo-count) (.getAttribute probe "data-explicit"))
              "an explicitly-framed read sees that frame's app-db")
          (is (= (str todo-count) (.getAttribute probe "data-ambient"))
              "and so does the ambient read the comparator is written with"))
        (react-dom/flushSync (fn [] (.unmount root)))
        (when-some [pn (.-parentNode container)] (.removeChild pn container))))))

;; ---------------------------------------------------------------------------
;; 1 — the same page, three ways
;; ---------------------------------------------------------------------------

(deftest the-three-renderings-build-the-same-page
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    ;; The comparator goes FIRST, on the freshest possible frame. If it
    ;; went last, a parity miss could be read either as a rendering
    ;; difference or as residue from the two mounts before it, and the
    ;; gate would not be able to tell a reader which.
    (let [_ (fresh!)
          c (uix-mount!)
          c-dom (lane/canonical (:container c))
          _ (release-uix! c)
          _ (fresh!)
          a (hicasso-mount! collector/screen)
          a-dom (lane/canonical (:container a))
          _ (mount/release! a)
          _ (fresh!)
          b (hicasso-mount! grouped/screen)
          b-dom (lane/canonical (:container b))
          _ (mount/release! b)]
      (is (= a-dom b-dom) "collector and grouped build the same page")
      (is (= a-dom c-dom) "and so does raw UIx")
      (testing "the comparison can answer false, so it is not passing vacuously"
        (fresh!)
        (let [d     (hicasso-mount! collector/screen)
              _     (mount/dispatch! d [:dogfood/toggle 0])
              moved (lane/canonical (:container d))]
          (mount/release! d)
          (is (not= a-dom moved))))
      (testing "and the page is the screen validation.md asked for — a list
               and a controlled field"
        (is (re-find #"<ul class=\"list\"" a-dom))
        (is (re-find #"class=\"new-input\"" a-dom))))))

;; ---------------------------------------------------------------------------
;; 1b — the lazy `for`, through a real React commit
;; ---------------------------------------------------------------------------
;;
;; A Surface B property (see the node suite for the argument). `for` is
;; lazy, so a collector that closed at the body's return would register no
;; row edges and the DOM would freeze after the first paint while looking
;; correct. Here it is asserted where it actually matters — after a real
;; commit, against real nodes.

(defview lazy-rows
  "Every read is inside the lazy seq. Nothing outside it reads a row."
  [_]
  [:ul.lazy
   (for [id (rt/sub [:dogfood/visible-ids])]
     [:li.lazy-row {:key id :data-id id}
      (str (:title (rt/sub [:dogfood/todo id])))])])

(deftest reads-inside-a-lazy-for-update-the-dom-on-a-later-write
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [lazy-rows {}])]
        (try
          (let [cell (fn [id] (some-> (.querySelector (:container handle)
                                                      (str ".lazy-row[data-id=\"" id "\"]"))
                                      (.-textContent)))]
            (is (= todo-count (.-length (.querySelectorAll (:container handle) ".lazy-row"))))
            (is (= "todo 2" (cell 2)) "the first paint is right — which proves nothing on its own")
            (rt/dispatch! frame-id [:dogfood/edit-draft 2 "ignored"])
            (mount/settle!)
            (rt/dispatch! frame-id [:dogfood/commit 2])
            (mount/settle!)
            (is (= "ignored" (cell 2))
                "and a later write to a query the lazy seq produced reaches the
                 DOM — the half a first render cannot tell you")
            (is (= "todo 1" (cell 1)) "while its neighbour is untouched"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 1c — the same intents on the same interactions (rf2-2rtt6.67)
;; ---------------------------------------------------------------------------
;;
;; The other half of "the same page". DOM parity proves the two live
;; renderings BUILD the same page; it cannot prove that touching the page
;; means the same thing — two screens could render identically and
;; dispatch different intents, and a preference judged between them would
;; be judging two applications. So the same interaction script is driven
;; at both renderings through real DOM events (a real click, a
;; prototype-setter keystroke, a real `keydown`), the frame's processed
;; events are captured at the substrate's own `:events` listener stream
;; (Spec 009 — the public observation port, so the witness holds no hook
;; into either rendering), and both captures are asserted equal to the
;; script's STATED expectation. Stating the expectation rather than only
;; comparing the two captures is what lets the gate answer false against
;; a drift BOTH renderings share.
;;
;; The script's SIZE is part of the claim. An equivalence published over
;; "eight event positions" while the script drove five of them is a claim
;; larger than its evidence, which is what the merged-PR audit of #7395
;; found. [[interaction-steps]] now carries a site-by-step coverage map
;; and drives all eight, both key-map branches, and all three filters.
;;
;; Two of the keystrokes are composing — the modern signal (`isComposing`,
;; which React's synthetic keyboard event DROPS, so a gate that reads the
;; synthetic event is deaf to it) and the legacy one (`keyCode` 229) —
;; and the expectation for both is silence. That is authoring.md's
;; composing-Enter law, asserted on each rendering's own gate: the data
;; key-map's central one on the collector, the hand-written one on raw
;; UIx.

(defn- q1 [handle sel] (.querySelector (:container handle) sel))

(defn- set-native-value!
  "Write `v` through `HTMLInputElement.prototype`'s OWN value setter,
  bypassing React's per-instance change tracker — the same door
  `arm1_controlled_grid_dom_cljs_test` documents. Assigning through the
  instance property updates the tracker too, and React then dedupes the
  `input` event as a no-change echo."
  [node v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) node v)))

(defn- type-into!
  "Append `text` and fire the `input` event, the way a browser orders the
  two: field first, event second."
  [node text]
  (set-native-value! node (str (.-value node) text))
  (.dispatchEvent node (js/Event. "input" #js {:bubbles true}))
  nil)

(defn- keydown!
  "Fire a real `keydown`. `:composing?` and `:key-code` ride the NATIVE
  event, which is where both renderings' gates read them."
  [node {:keys [key composing? key-code]}]
  (.dispatchEvent node (js/KeyboardEvent. "keydown"
                                          #js {:key         key
                                               :bubbles     true
                                               :isComposing (boolean composing?)
                                               :keyCode     (or key-code 0)}))
  nil)

(def ^:private interaction-intents
  "What the script is EXPECTED to dispatch, written out rather than
  computed from either rendering. The two composing keystrokes appear
  nowhere in it — their expectation is silence."
  [[:dogfood/toggle 1]
   [:dogfood/set-filter :done]
   [:dogfood/set-filter :active]
   [:dogfood/set-filter :all]
   [:dogfood/edit-draft dogfood/new-draft-key "milk"]
   [:dogfood/cancel dogfood/new-draft-key]
   [:dogfood/edit-draft dogfood/new-draft-key "milk"]
   [:dogfood/create]
   [:dogfood/edit-draft 0 "x"]
   [:dogfood/cancel 0]
   [:dogfood/edit-draft 3 "renamed"]
   [:dogfood/commit 3]
   [:dogfood/remove 2]
   [:dogfood/edit-draft dogfood/new-draft-key "bread"]
   [:dogfood/create]])

(defn- interaction-steps
  "The one script, as thunks over `handle`'s container. Steps are run one
  per macrotask ([[run-steps!]]) because the comparator's `dispatch` is
  the router's asynchronous door — the drain is a next-turn task — while
  the collector's is HD-019's synchronous one. The script must not care
  which it is driving, and a step's target can be a node the previous
  step's drain revealed.

  **Every handler site the screen has, and every branch of both key-maps,
  is driven here** (rf2-2rtt6.67, merged-PR audit of #7395 — the first
  cut left three unexercised while the page claimed equivalence over all
  of them). The eight sites against the steps that reach them:

  | Handler site | Steps |
  |---|---|
  | toggle click | 1 |
  | filter click | 2, 3, 4 — all three filters, so no branch of `visible-ids` is untaken |
  | new-item `:on-input` | 5, 9, 16 |
  | new-item keys | 6, 7 (composing, silent), 8 (Escape), 10 (Enter) |
  | row `:on-input` | 11, 13 |
  | row keys | 12 (Escape), 14 (Enter) |
  | remove click | 15 |
  | form submit | 17 |

  `:dogfood/move` has no affordance in the markup, so it is correctly
  absent rather than missing."
  [handle]
  [#(.click (q1 handle "[data-id=\"1\"] .toggle"))         ; 1  the narrow write
   #(.click (q1 handle ".filter[data-filter=\"done\"]"))   ; 2  the broad write…
   #(.click (q1 handle ".filter[data-filter=\"active\"]")) ; 3  …its other branch…
   #(.click (q1 handle ".filter[data-filter=\"all\"]"))    ; 4  …and back
   #(type-into! (q1 handle ".new-input") "milk")           ; 5
   ;; 6, 7 — the two composing keystrokes: the law is that NEITHER commits.
   #(keydown! (q1 handle ".new-input") {:key "Enter" :composing? true  :key-code 13})
   #(keydown! (q1 handle ".new-input") {:key "Enter" :composing? false :key-code 229})
   ;; 8 — the new-item field's OTHER key branch: Escape discards the draft,
   ;; which is why step 9 can retype it from empty.
   #(keydown! (q1 handle ".new-input") {:key "Escape" :composing? false :key-code 27})
   #(type-into! (q1 handle ".new-input") "milk")           ; 9
   #(keydown! (q1 handle ".new-input") {:key "Enter" :composing? false :key-code 13}) ; 10
   #(type-into! (q1 handle "[data-id=\"0\"] .draft") "x")  ; 11
   #(keydown! (q1 handle "[data-id=\"0\"] .draft") {:key "Escape" :composing? false :key-code 27}) ; 12
   ;; 13, 14 — a row draft taken all the way to a COMMIT, the row key-map's
   ;; branch the first cut never drove.
   #(type-into! (q1 handle "[data-id=\"3\"] .draft") "renamed")
   #(keydown! (q1 handle "[data-id=\"3\"] .draft") {:key "Enter" :composing? false :key-code 13})
   #(.click (q1 handle "[data-id=\"2\"] .remove"))         ; 15
   #(type-into! (q1 handle ".new-input") "bread")          ; 16
   #(.click (q1 handle ".add"))])                          ; 17 a real form submission

(defn- run-steps!
  "Run each thunk, then yield a macrotask — room for the router's drain —
  and settle React's sync lane before the next step reads the page."
  [steps k]
  (if (empty? steps)
    (k)
    (do ((first steps))
        (js/setTimeout (fn [] (mount/settle!) (run-steps! (rest steps) k)) 8))))

(defn- capture-run!
  "Mount via `mount-fn`, run the script with the `:events` listener
  armed, and hand `k` `{:intents [...] :dom canonical}` — the two halves
  parity is asserted on. The listener is registered after the mount and
  removed before the release, so neither the seed nor teardown can leak
  into the capture."
  [mount-fn release-fn k]
  (fresh!)
  (let [handle (mount-fn)
        log    (atom [])]
    (rf/register-listener! :events ::intent-parity
                           (fn [record]
                             (when (= frame-id (:frame record))
                               (swap! log conj (:event record)))))
    (run-steps!
      (interaction-steps handle)
      (fn []
        (let [result {:intents @log :dom (lane/canonical (:container handle))}]
          (rf/unregister-listener! :events ::intent-parity)
          (release-fn handle)
          (k result))))))

(deftest the-two-live-renderings-dispatch-the-same-intents-on-the-same-interactions
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    ;; The control first, on the freshest frame — same reasoning as the
    ;; DOM-parity gate above.
    (async done
      (capture-run!
        uix-mount! release-uix!
        (fn [uix-run]
          (capture-run!
            (fn [] (hicasso-mount! collector/screen)) mount/release!
            (fn [collector-run]
              (is (= interaction-intents (:intents uix-run))
                  "raw UIx dispatches exactly the stated intents — and
                   nothing for the two composing keystrokes")
              (is (= interaction-intents (:intents collector-run))
                  "and so does the collector, so the two renderings mean
                   the same page as well as building it")
              (is (= (:dom uix-run) (:dom collector-run))
                  "and after the whole script the two pages are still
                   canonically identical — parity held through the
                   interactions, not just at mount")
              (done))))))))

;; ---------------------------------------------------------------------------
;; 2 — the narrow write touches one row
;; ---------------------------------------------------------------------------

(defn- row-text [handle id]
  (some-> (.querySelector (:container handle) (str "[data-id=\"" id "\"] .label"))
          (.-textContent)))

(defn- row-done [handle id]
  (some-> (.querySelector (:container handle) (str "[data-id=\"" id "\"]"))
          (.getAttribute "data-done")))

(deftest a-toggle-moves-exactly-the-row-it-names
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (doseq [[label screen] [["collector" collector/screen] ["grouped" grouped/screen]]]
      (fresh!)
      (let [handle (hicasso-mount! screen)]
        (try
          (is (= "false" (row-done handle 1)) label)
          (mount/dispatch! handle [:dogfood/toggle 1])
          (is (= "true" (row-done handle 1)) (str label ": the named row moved"))
          (is (= "false" (row-done handle 0)) (str label ": and its neighbour did not"))
          (is (= "false" (row-done handle 2)) (str label ": nor the one after"))
          (finally (mount/release! handle)))))))

(deftest a-broad-write-rebuilds-the-list
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (hicasso-mount! collector/screen)]
        (try
          (mount/dispatch! handle [:dogfood/toggle 1])
          (is (= todo-count (.-length (.querySelectorAll (:container handle) ".row"))))
          (mount/dispatch! handle [:dogfood/set-filter :done])
          (is (= 1 (.-length (.querySelectorAll (:container handle) ".row")))
              "the filter is a broad write: the visible-ids subscription
               moved and the list rebuilt")
          (is (= "todo 1" (row-text handle 1)))
          (finally (mount/release! handle)))))))

(deftest keyed-rows-keep-their-identity-across-a-reorder
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (hicasso-mount! collector/screen)]
        (try
          (let [node-before (.querySelector (:container handle) "[data-id=\"3\"]")]
            (mount/dispatch! handle [:dogfood/move 3 0])
            (let [rows (array-seq (.querySelectorAll (:container handle) ".row"))]
              (is (= "3" (.getAttribute (first rows) "data-id"))
                  "the moved row is first")
              (is (identical? node-before (.querySelector (:container handle) "[data-id=\"3\"]"))
                  "and it is the SAME node — identity follows the key")))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3 — the controlled field, and what the collector's conditional read buys
;; ---------------------------------------------------------------------------

(defn- new-input [handle] (.querySelector (:container handle) ".new-input"))

(deftest the-controlled-field-echoes-in-the-callers-turn
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (hicasso-mount! collector/screen)]
        (try
          (let [input (new-input handle)]
            (is (= "" (.-value input)))
            ;; The intent's own path: dispatch through the arm's
            ;; synchronous door, exactly as the lowered `:on-input`
            ;; closure does, and read the DOM on the next line.
            (rt/dispatch! frame-id [:dogfood/edit-draft dogfood/new-draft-key "milk"])
            (mount/settle!)
            (is (= "milk" (.-value (new-input handle)))
                "the echo landed without waiting for a later turn"))
          (finally (mount/release! handle)))))))

(deftest the-submit-intent-creates-and-clears-the-draft
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (hicasso-mount! collector/screen)]
        (try
          (rt/dispatch! frame-id [:dogfood/edit-draft dogfood/new-draft-key "milk"])
          (mount/settle!)
          (rt/dispatch! frame-id [:dogfood/create])
          (mount/settle!)
          (is (= (inc todo-count) (.-length (.querySelectorAll (:container handle) ".row"))))
          (is (= "milk" (row-text handle todo-count)))
          (is (= "" (.-value (new-input handle)))
              "and the draft cleared by explicit caller revision, never by
               value equality")
          (finally (mount/release! handle)))))))

(deftest the-collectors-conditional-read-costs-fewer-edges-than-the-declaration
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [collector-edges (let [h (hicasso-mount! collector/screen)]
                              (mount/dispatch! h [:dogfood/toggle 0])
                              (mount/dispatch! h [:dogfood/toggle 1])
                              (let [e (:edges (rt/stats))] (mount/release! h) e))
            _ (fresh!)
            grouped-edges   (let [h (hicasso-mount! grouped/screen)]
                              (mount/dispatch! h [:dogfood/toggle 0])
                              (mount/dispatch! h [:dogfood/toggle 1])
                              (let [e (:edges (rt/stats))] (mount/release! h) e))]
        (is (< collector-edges grouped-edges)
            (str "two completed rows drop their draft edge under the collector "
                 "and keep it under the declaration (" collector-edges " vs "
                 grouped-edges ") — the same page, two edge counts, which is "
                 "the surface difference stated as a number rather than a "
                 "preference"))))))

;; ---------------------------------------------------------------------------
;; Teardown — **React's** teardown, read while the runtime still holds it
;; ---------------------------------------------------------------------------
;;
;; The reading is taken between the root unmount and any reset, because
;; `mount/release!` resets the runtime and a reading taken after that is a
;; reading of an emptied table — zero however badly teardown went. That
;; ordering is what made these two gates unable to fail (rf2-2rtt6.48),
;; and `the-residue-reading-can-answer-false` below is the demonstration
;; that the repaired reading is live at the point the gates take it.
;;
;; The macrotask wait is not slack: a cell whose last reader unmounts and
;; an entry whose last boundary unmounts are both reaped one macrotask
;; later, deliberately, so a keyed reorder reuses them.

(def ^:private reaper-horizon-ms
  "One macrotask, with room — `arm1/runtime` arms both reapers at 0 ms."
  8)

(def ^:private nothing-retained
  {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0})

(defn- assert-react-teardown-releases-everything!
  "Mount `screen`, drive it, and let **React** tear it down. What survives
  the reaper horizon is what React's own cleanup failed to release."
  [label screen done]
  (fresh!)
  (let [handle (hicasso-mount! screen)]
    (mount/dispatch! handle [:dogfood/toggle 0])
    (mount/dispatch! handle [:dogfood/set-filter :all])
    (is (pos? (:cell-refs (rt/stats)))
        (str label ": the mounted screen holds references, so the reading "
             "below is a reading of something"))
    (mount/unmount! handle)
    (js/setTimeout (fn []
                     (is (= nothing-retained (rt/residue))
                         (str label ": React's own cleanup released every edge, "
                              "reference and cached entry"))
                     (rt/reset-runtime!)
                     (done))
                   reaper-horizon-ms)))

(deftest the-collector-screen-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done (assert-react-teardown-releases-everything!
                  "collector" collector/screen done))))

(deftest the-grouped-screen-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done (assert-react-teardown-releases-everything!
                  "grouped" grouped/screen done))))

(deftest the-residue-reading-can-answer-false
  (testing "two roots, one unmounted: the reading the gates above take is
           NOT zero, because the other root's boundaries are still holding
           what they acquired. This is the property the pre-repair gates
           lacked — `release!` resets the runtime, so it would report zero
           here too, with a whole screen still mounted"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (async done
        (fresh!)
        (let [survivor (hicasso-mount! collector/screen)
              doomed   (hicasso-mount! collector/screen)]
          (mount/unmount! doomed)
          (js/setTimeout (fn []
                           (is (not= nothing-retained (rt/residue))
                               "a live screen is visible to the residue reading")
                           (is (pos? (:cell-refs (rt/residue)))
                               "and it is visible as held references, which is
                                the quantity the gates assert to be zero")
                           (mount/release! survivor)
                           (done))
                         reaper-horizon-ms))))))
