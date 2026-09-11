(ns day8.re-frame2-xray.views.edn-inspector-popup-stack-boundary-dom-cljs-test
  "THE POPUP STACK'S LIVE COLLECTOR WITNESS (rf2-7z2u).

  ## What was ungraded, and why no node row can grade it

  rf2-k97c.3 turned `views.edn-inspector-popup/edn-inspector-popup-stack-view`
  into a Fresco BOUNDARY, crossed it into the still-Reagent shell through
  `rf.fresco/as-component`, and claimed a concrete new cost with it: the
  closed stack holds ONE live subscription edge instead of three, because
  `rf.fresco/sub` records its edge WHERE THE READ HAPPENS and the other two
  reads moved inside the `when`.

  Every test that previously called the view now calls `popup-stack-tree`
  with values it composed itself. That is evidence about COMPOSITION and
  about nothing else, BY CONSTRUCTION: it never mounts a boundary, never
  crosses the bridge, never establishes a React-context frame, and never
  asks the collector for anything. Replacing the boundary's body with `nil`,
  reading a wrong frame, or hoisting the two gated reads back out of the
  `when` leaves every one of those rows byte-for-byte green. So does the
  head-kind row, which checks markers on a tree it built.

  This file is that branch's evidence. Nothing below ever calls a view a
  second time: after the mount, every assertion reads
  `container.querySelector` — the DOM React committed on its own — or the
  collector's own tables.

  ## The mount is the PRODUCTION crossing

  `shell.cljs` mounts the stack at its overlay container as the plain
  Reagent hiccup head `[edn-inspector-popup/edn-inspector-popup-stack]`,
  inside `shell-view`'s own `[rf/frame-provider …]`. [[mount-stack!]]
  reproduces exactly that two-level form and nothing else. The bridge
  itself performs no read, so it would not refuse without the provider; the
  BOUNDARY behind it takes its frame from React context, and at a bare root
  there is no context to take.

  ## The four rows

  W1 asks whether the stack PAINTS through the bridge, including the
  embedded widget's FRESCO head — `fresco-inspector` rather than
  `popup-chrome`'s Reagent default — which the node lane cannot distinguish
  because `expand-tree` invokes a fn head and `[x …]` and `(x …)` expand
  identically.

  W2 asks whether the reads are FRAME-ROUTED, with a second live frame as
  the deaf lever and the same open on the popup's own frame as the
  counter-control.

  W3 asks whether all three reads are LIVE — a second `:open` moves the
  stack, its title proves the ENTRIES payload was read rather than copied,
  and a real `:rf.xray/set-modal-positioning` moves the backdrop's
  positioning attribute. It closes the top popup with a REAL CLICK, through
  the close handler the boundary's own render captured.

  W4 is the bead's headline: the collector's reader edges for the three
  queries, read off `!cells` through the kit's runtime door. Closed is ONE
  edge on `stack` and ZERO on the other two — the migration's claim, stated
  as a literal rather than as a comparison against something the same code
  computed. Open is three. Closing returns to one. Unmount releases all
  three within the runtime's own grace, and reopening returns to the same
  numbers rather than higher ones.

  ## THE HEADLESS CONTAINER MEASURES clientWidth 0

  Nothing here asserts a measured width, and that is a finding rather than
  an omission: the embedded inspector's payload container measures 0 in
  this layout, so `measure-and-dispatch!`'s `(pos? w)` guard never fires and
  an EMPTY width slot is consistent with a perfectly healthy mount.

  ## Node-lane behaviour

  This ns matches the `:browser-test` build's regex and also loads under
  `:node-test`, where every row short-circuits through [[browser?]] and
  reports the skip rather than passing silently. A green node run says
  nothing whatever about this file; `npm run test:browser` is its lane."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector-popup :as popup]))

(def ^:private stack-frame
  "The frame this suite's stack instance owns. NOT `:rf/xray`: the
  production singleton is shared with every other suite on the page, and
  naming a private frame is what makes W2's negative half mean something —
  and what makes W4's census a census of THIS boundary, since the
  collector's cell table is keyed `[frame-kw query-v]`."
  ::stack)

(def ^:private other-frame
  "A second live frame W2 and W3 dispatch into as their DEAF LEVER. A write
  here must move nothing in the stack above."
  ::other)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the cell table would
                      ;; make W4 count a residue that is not this stack's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than
;; an omission. A Fresco boundary is NOT in Reagent's render queue — its
;; update is scheduled by the collector through React — so draining
;; Reagent's queue commits nothing of this boundary's, and a row written
;; that way reads a DOM that has not moved and reports a live stack as dead.
;; Mount is committed with `flushSync` (React's own door) and everything
;; after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a real
  chance to commit — two animation frames and a macrotask. It exists for the
  CONTROLS: an absence asserted immediately after the world moves is a race,
  and an absence asserted after this is a decision. It is also what makes
  W4's literal edge counts honest — React registers a boundary's read set in
  a PASSIVE EFFECT, so a census taken in the mounting turn recomputes before
  the registration arrives and comes back green having observed nothing."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- poll-until
  "Resolve as soon as `pred` answers truthy, or after `budget-ms`. The
  resolution value is `pred`'s last answer, so a caller asserts on the value
  rather than on the fact that polling ended."
  ([pred] (poll-until pred 2000))
  ([pred budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (let [v (pred)]
                     (cond
                       v                          (resolve v)
                       (> (js/Date.now) deadline) (resolve v)
                       :else (js/requestAnimationFrame (fn [_] (tick))))))]
           (tick)))))))

;; ---- the world -----------------------------------------------------------

(defn- setup!
  "Register Xray's handlers — which is what installs the popup's subs and
  events (`registry.cljs` calls `edn-inspector-popup/install!`) — and make
  the frames.

  `:rf/xray` is made as well as the two this suite names. It is
  `shell/default-frame-id`, the production singleton, and several Xray
  registrations reach it by that name regardless of which frame an instance
  is mounted at; a missing frame is a loud re-frame refusal, and one raised
  inside a React render surfaces only as 'an error occurred in <…>' with the
  message nowhere on screen."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id shell/default-frame-id})
  (rf/make-frame {:id stack-frame})
  (rf/make-frame {:id other-frame})
  nil)

(defn- mount-stack!
  "Mount the popup stack the way `shell.cljs` mounts it — the enclosing
  `frame-provider` included:

      [rf/frame-provider {:frame …} [edn-inspector-popup/edn-inspector-popup-stack]]

  Committed synchronously — React 19's `root.render` is otherwise async and
  the first assertion would read an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [popup/edn-inspector-popup-stack]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the next
  line reads anything. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- open!
  "Fire the popup stack's PUBLIC programmatic open against `frame`. This is
  the event a context-menu handler dispatches in production."
  [frame mount-id title value]
  (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open mount-id
                     {:value value :opts {:title title}}]
                    {:frame frame})
  nil)

;; ---- the diagnostic ------------------------------------------------------
;;
;; A re-frame refusal raised inside a React render does NOT reach a
;; `try/catch` around `flushSync`: React 19 catches it, reports "an error
;; occurred in <…>" to the console, and re-raises it as an UNCAUGHT window
;; error. Its `ex-message` and `ex-data` — the whole of what re-frame refuses
;; WITH — then appear nowhere a row can read, and every assertion below
;; reddens on a nil testid saying only that the popup is absent.

(defonce ^:private !last-uncaught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(defn- uncaught-note
  "A suffix naming the last uncaught error, for a row whose subject is
  missing. Empty when nothing was thrown — in which case the absence is the
  finding rather than a hidden exception."
  []
  (if-some [e (when error-capture-armed? @!last-uncaught)]
    (str " — an uncaught error was raised during render: "
         (:rf.error/id (ex-data e) (ex-message e))
         " · at: "
         (let [s (str (.-stack ^js e))]
           (->> (str/split-lines s)
                (remove #(str/blank? %))
                (filter #(str/includes? % "at "))
                (take 14)
                (str/join " | "))))
    ""))

;; ---- reading the committed DOM back --------------------------------------

(defn- q [container sel] (.querySelector container sel))

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(def ^:private stack-testid "rf-xray-edn-inspector-popup-stack")

(defn- stack-el [container] (testid container stack-testid))

(defn- popup-count
  "The stack container's own `data-rf-popup-count`, as the string React
  committed, or nil when the boundary rendered nothing at all."
  [container]
  (some-> (stack-el container) (.getAttribute "data-rf-popup-count")))

(defn- dialog-el [container id]
  (testid container (str "rf-xray-edn-inspector-popup-dialog-" id)))

(defn- title-text
  "The committed header label for `id`. It is the `:title` the OPEN event
  put in the entries slot, so reading it back off the DOM is the entries
  read's own witness."
  [container id]
  (some-> (testid container (str "rf-xray-edn-inspector-popup-title-" id))
          (.-textContent)))

(defn- positioning-attr
  "`modal-chrome` stamps the resolved `:rf.xray/modal-positioning` on the
  backdrop as `data-rf-xray-modal-positioning`. It is the positioning read's
  own witness, and the only one of the three that is directly visible in the
  committed markup."
  [container id]
  (some-> (testid container (str "rf-xray-edn-inspector-popup-backdrop-" id))
          (.getAttribute "data-rf-xray-modal-positioning")))

(defn- embedded-widget
  "The embedded edn-inspector's own committed container for `id`. The widget
  stamps `data-rf-mount-id` with the string `fresco-inspector` composed —
  `rf-xray-edn-inspector-popup-<mount-id>` — so finding it is evidence that
  the FRESCO head ran, not merely that a body div exists."
  [container id]
  (q container
     (str "[data-rf-mount-id=\"rf-xray-edn-inspector-popup-" id "\"]")))

;; ---- reading the collector back ------------------------------------------

(def ^:private stack-read
  "The gate read — the ONE read a closed stack performs."
  [popup/stack-slot])

(def ^:private gated-reads
  "The two reads that moved INSIDE the `when` at rf2-k97c.3. A closed stack
  must hold no edge on either; that is the migration's whole claimed cost
  change."
  [[popup/entries-slot] [:rf.xray/modal-positioning]])

(def ^:private boundary-reads
  "Every query the boundary reads, gate first — the order its body reads
  them in."
  (into [stack-read] gated-reads))

(defn- sub-key
  "The collector's own key for one read under `frame`. `[frame-kw query-v]`
  is the address `!cells` is keyed by, so the census below is frame-scoped
  and a neighbouring suite's identical query is a different cell."
  [frame query-v]
  [frame query-v])

(defn- reader-edges
  "The reader slots each of the three cells holds, in `boundary-reads`
  order. One slot per boundary registration reading that cell — the fused
  reference AND dependency edge — so this vector IS the closed/open cost the
  migration claims, read off the runtime rather than argued.

  Answered as a VECTOR rather than a total: a release that freed one cell
  and retained another sums to the same number as the honest case shifted by
  one, and the failure message has to name WHICH read is still held."
  [frame]
  (mapv #(count (rf.fresco.test.runtime/cell-readers (sub-key frame %)))
        boundary-reads))

(defn- live-cells
  "The reads whose collector CELL still holds a live reaction. A cell holds
  an `add-watch` on that reaction for as long as it lives, so this is the
  retained-watch half of the release claim, read directly off the runtime
  rather than inferred from an edge count."
  [frame]
  (filterv #(some? (rf.fresco.test.runtime/cell-reaction (sub-key frame %)))
           boundary-reads))

(defn- census
  "Both instruments as one map, so a failure message names which of them
  still sees something."
  [frame]
  {:reader-edges (reader-edges frame)
   :live-cells   (live-cells frame)})

;; ===========================================================================
;; W1 — the stack PAINTS through the bridge, with the FRESCO head embedded
;; ===========================================================================

(deftest w1-popup-stack-paints-through-the-as-component-bridge
  (testing "rf2-7z2u — `edn-inspector-popup-stack` is the migration bridge
            and the boundary behind it commits a real popup to a real DOM.
            Closed first: the gate now lives INSIDE the boundary rather than
            in the Reagent head, so `nil`-when-empty is a claim about the
            boundary that only a DOM can check.

            THE EMBEDDED HEAD IS THE HALF THE NODE LANE CANNOT REACH.
            `popup-stack-tree` passes `fresco-inspector` rather than
            `popup-chrome`'s Reagent default, and a node-lane expansion
            cannot tell the two apart — `expand-tree` INVOKES a fn head, so
            `[x …]` and `(x …)` expand identically. The committed
            `data-rf-mount-id` is what says the Fresco widget really
            rendered."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-stack! stack-frame)
              id "popupwit-w1"]
          (-> (settle)
              (.then
                (fn [_]
                  (is (nil? (stack-el container))
                      "closed: the boundary commits nothing at all — not an
                       empty container, nothing")
                  (open! stack-frame id "Popupwit W1" {:alpha 1 :beta [:x :y]})
                  (poll-until #(dialog-el container id))))
              (.then
                (fn [dialog]
                  (is (some? dialog)
                      (str "open: the dialog is committed to the DOM"
                           (uncaught-note)))
                  (is (= "1" (popup-count container))
                      (str "the stack container reports ONE open popup. got="
                           (pr-str (popup-count container))))
                  (is (some? (testid container
                                     (str "rf-xray-edn-inspector-popup-backdrop-" id)))
                      "the backdrop is committed")
                  (is (some? (testid container
                                     (str "rf-xray-edn-inspector-popup-body-" id)))
                      "the body is committed")
                  (is (some? (testid container
                                     (str "rf-xray-edn-inspector-popup-close-" id)))
                      "the close affordance is committed")
                  (is (= "Popupwit W1" (title-text container id))
                      (str "the header carries the title the OPEN event put "
                           "in the entries slot — so the payload reached the "
                           "chrome through a real read rather than a copy. "
                           "got=" (pr-str (title-text container id))))
                  (is (some? (embedded-widget container id))
                      (str "the FRESCO inspector head committed its own "
                           "widget container under the composed mount-id — "
                           "an envelope with no body would satisfy every "
                           "assertion above it" (uncaught-note)))
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W2 — the reads are FRAME-ROUTED
;; ===========================================================================

(deftest w2-the-stack-reads-resolve-through-its-own-frame
  (testing "rf2-7z2u — the boundary takes its frame from the React context
            `rf/frame-provider` writes, so an `:open` on a SECOND live frame
            moves nothing here. Without this row the positive half of W1 is
            compatible with a boundary reading ambiently or reading
            `:rf/xray` by literal.

            THE DEAF LEVER IS THE CONTROL AND THE COUNTER-CONTROL IS ITS
            OWN: the identical event on the stack's own frame DOES open a
            popup, so the silence above measured routing rather than a dead
            event."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-stack! stack-frame)
              id "popupwit-w2"]
          (-> (settle)
              (.then
                (fn [_]
                  ;; ---- the deaf lever -------------------------------
                  (open! other-frame id "Deaf lever" {:should :not-appear})
                  (settle)))
              (.then
                (fn [_]
                  (is (nil? (stack-el container))
                      (str "CONTROL: an `:open` on a SECOND live frame "
                           "committed nothing here, so the stack's reads "
                           "resolve through its own frame rather than "
                           "ambiently. count="
                           (pr-str (popup-count container))))
                  (is (nil? (dialog-el container id))
                      "CONTROL: and no dialog for that id appeared")
                  ;; ---- the counter-control --------------------------
                  (open! stack-frame id "Popupwit W2" {:ok true})
                  (poll-until #(dialog-el container id))))
              (.then
                (fn [dialog]
                  (is (some? dialog)
                      (str "COUNTER-CONTROL: the identical event on the "
                           "stack's OWN frame does open a popup, so the deaf "
                           "lever above measured routing rather than a dead "
                           "event" (uncaught-note)))
                  (is (= "Popupwit W2" (title-text container id))
                      "and it is the OWN frame's payload that is on screen,
                       not the deaf lever's")
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W3 — all three reads are LIVE, and a real click closes the top popup
;; ===========================================================================

(deftest w3-stack-entries-and-positioning-are-live-reads
  (testing "rf2-7z2u — one row per read, each driven through the SHIPPED
            public event and observed in the committed DOM.

            STACK — a second `:open` moves `data-rf-popup-count` from 1 to 2.
            ENTRIES — the second popup's own title appears, which it can only
            do if the entries map was RE-READ; a boundary that read entries
            once and cached would show the new stack position with the old
            payload.
            POSITIONING — `:rf.xray/set-modal-positioning` moves the
            backdrop's `data-rf-xray-modal-positioning`, and the same event
            on the deaf frame does not.

            THE CLOSE IS A REAL CLICK, on the real committed button, through
            the handler `popup-chrome` built from the frame the BOUNDARY's
            render captured. A `dispatch-sync` from the test would exercise
            the read path while bypassing that captured frame, which is the
            half a wrong frame would break."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-stack! stack-frame)
              a "popupwit-w3-a"
              b "popupwit-w3-b"]
          (open! stack-frame a "First" {:n 1})
          (-> (poll-until #(dialog-el container a))
              (.then
                (fn [_]
                  (is (= "1" (popup-count container))
                      "baseline: one popup open")
                  (is (= "fixed" (positioning-attr container a))
                      (str "baseline: the backdrop carries the default "
                           ":fixed positioning. got="
                           (pr-str (positioning-attr container a))))
                  ;; ---- the STACK read is live -----------------------
                  (open! stack-frame b "Second" {:n 2})
                  (poll-until #(= "2" (popup-count container)))))
              (.then
                (fn [two?]
                  (is two?
                      (str "STACK IS LIVE: a second `:open` moved the "
                           "committed popup count to 2. got="
                           (pr-str (popup-count container))
                           (uncaught-note)))
                  ;; ---- the ENTRIES read is live ---------------------
                  (is (= "Second" (title-text container b))
                      (str "ENTRIES IS LIVE: the new entry's own payload is "
                           "on screen, so the entries map was re-read rather "
                           "than captured at first render. got="
                           (pr-str (title-text container b))))
                  (is (= "First" (title-text container a))
                      "and the first popup still carries its own payload —
                       a real stack, not a replacement")
                  ;; ---- the POSITIONING read is live ------------------
                  (rf/dispatch-sync [:rf.xray/set-modal-positioning :absolute]
                                    {:frame stack-frame})
                  (poll-until #(= "absolute" (positioning-attr container b)))))
              (.then
                (fn [moved?]
                  (is moved?
                      (str "POSITIONING IS LIVE: a real "
                           "`:rf.xray/set-modal-positioning` moved the "
                           "committed backdrop attribute. got="
                           (pr-str (positioning-attr container b))
                           (uncaught-note)))
                  ;; ---- the deaf lever, on positioning ----------------
                  (rf/dispatch-sync [:rf.xray/set-modal-positioning :fixed]
                                    {:frame other-frame})
                  (settle)))
              (.then
                (fn [_]
                  (is (= "absolute" (positioning-attr container b))
                      (str "CONTROL: the same positioning event on a SECOND "
                           "live frame moved nothing here. got="
                           (pr-str (positioning-attr container b))))
                  ;; ---- a REAL click on the top popup's close --------
                  (.click (testid container
                                  (str "rf-xray-edn-inspector-popup-close-" b)))
                  (poll-until #(= "1" (popup-count container)))))
              (.then
                (fn [back-to-one?]
                  (is back-to-one?
                      (str "a real click on the committed close button "
                           "dispatched through the frame the boundary's own "
                           "render captured, and the stack shrank. got="
                           (pr-str (popup-count container))
                           (uncaught-note)))
                  (is (nil? (dialog-el container b))
                      "the closed popup is gone from the DOM")
                  (is (some? (dialog-el container a))
                      "and the popup beneath it is still standing — the
                       layered-popups contract, on a real DOM")
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W4 — the collector's reader edges: one edge closed, three open, and a
;;      complete release
;; ===========================================================================

(deftest w4-closed-stack-holds-one-edge-and-unmount-releases-all-three
  (testing "rf2-7z2u — the bead's headline claim, measured. rf2-k97c.3 moved
            the entries and positioning reads INSIDE the boundary's `when`,
            and `rf.fresco/sub` records its edge WHERE THE READ HAPPENS, so a
            CLOSED stack must hold ONE reader edge and not three. The
            `reg-view` it replaced read all three unconditionally, so its
            docstring's 'one subscribe + a when' was aspirational — which is
            exactly what makes this a measurement rather than a formality.

            EVERY NUMBER BELOW IS A LITERAL. A row that compared the edge
            count against something the boundary itself computed would agree
            with itself under a revert — both sides go to the same wrong
            number and the row goes green on the defect.

            THE CENSUS IS POLLED, NOT TAKEN IN THE MOUNTING TURN. React
            registers a boundary's read set in a passive effect, so a census
            taken straight after `flushSync` recomputes before the
            registration arrives and reports zero edges for a healthy mount.
            The row polls for its OWN gate edge first, then settles, then
            asserts the literals.

            THE RELEASE IS ASYNCHRONOUS BY DESIGN, so the settling point is
            the kit's own `quiesced!` rather than a bare macrotask: a cell
            whose last reader unmounts is given one macrotask of grace, and
            the entry reaper's horizon sits deliberately outside a
            `setTimeout 0`. A residue read before that point reports a LEAK
            against a runtime behaving exactly as documented."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (-> (rf.fresco.test.runtime/quiesced!)
            (.then
              (fn [_]
                (is (= [0 0 0] (reader-edges stack-frame))
                    (str "BASELINE: nothing holds any of this stack's three "
                         "reads before the mount. " (pr-str (census stack-frame))))
                (let [{:keys [container root]} (mount-stack! stack-frame)
                      id "popupwit-w4"]
                  (-> (poll-until #(= 1 (first (reader-edges stack-frame))))
                      (.then
                        (fn [_]
                          ;; The poll above waits for THIS boundary's own gate
                          ;; edge — the sanctioned way to know the passive
                          ;; effect has landed. Everything asserted after the
                          ;; settle below is a literal.
                          (settle)))
                      (.then
                        (fn [_]
                          (is (= [1 0 0] (reader-edges stack-frame))
                              (str "CLOSED COSTS ONE EDGE: the gate read is "
                                   "held and the two gated reads are not — "
                                   "the migration's claimed cost, measured. "
                                   (pr-str (census stack-frame))
                                   (uncaught-note)))
                          (is (= [stack-read] (live-cells stack-frame))
                              (str "and only the gate read has a live cell, "
                                   "so only one `add-watch` stands. "
                                   (pr-str (census stack-frame))))
                          (open! stack-frame id "Popupwit W4" {:n 4})
                          (poll-until #(dialog-el container id))))
                      (.then
                        (fn [dialog]
                          (is (some? dialog)
                              (str "NON-VACUITY: the popup really opened, so "
                                   "the open-state numbers below are about a "
                                   "boundary that ran its gated arm"
                                   (uncaught-note)))
                          (settle)))
                      (.then
                        (fn [_]
                          (is (= [1 1 1] (reader-edges stack-frame))
                              (str "OPEN COSTS THREE: the gated arm took an "
                                   "edge on entries and on positioning. "
                                   (pr-str (census stack-frame))))
                          (is (= 3 (count (live-cells stack-frame)))
                              (str "and all three reads have live cells. "
                                   (pr-str (census stack-frame))))
                          ;; ---- close: back to the closed cost -------
                          (rf/dispatch-sync
                            [:rf.xray.edn-inspector-popup/close id]
                            {:frame stack-frame})
                          (poll-until #(nil? (stack-el container)))))
                      (.then
                        (fn [_]
                          (is (nil? (stack-el container))
                              "the stack is closed again")
                          (settle)))
                      (.then
                        (fn [_]
                          (is (= [1 0 0] (reader-edges stack-frame))
                              (str "CLOSING RETURNS TO ONE EDGE: the gated "
                                   "reads were RELEASED when the branch "
                                   "stopped being taken, rather than retained "
                                   "for the life of the mount. "
                                   (pr-str (census stack-frame))))
                          ;; ---- reopen: no accumulation --------------
                          (open! stack-frame id "Popupwit W4 again" {:n 44})
                          (poll-until #(dialog-el container id))))
                      (.then
                        (fn [_] (settle)))
                      (.then
                        (fn [_]
                          (is (= [1 1 1] (reader-edges stack-frame))
                              (str "REOPENING DOES NOT ACCUMULATE: the same "
                                   "three edges, not six. An edge left behind "
                                   "by the close would show here as growth. "
                                   (pr-str (census stack-frame))))
                          ;; ---- unmount: full release ---------------
                          (teardown! root container)
                          (rf.fresco.test.runtime/quiesced!)))
                      (.then
                        (fn [_]
                          (is (= [0 0 0] (reader-edges stack-frame))
                              (str "NO RETAINED LISTENERS: the unmount "
                                   "released every reader edge, the gate's "
                                   "included. " (pr-str (census stack-frame))))
                          (is (empty? (live-cells stack-frame))
                              (str "NO RETAINED WATCHES: and every collector "
                                   "cell for this stack's reads is gone, so no "
                                   "`add-watch` on a derived reaction survives "
                                   "the unmount. "
                                   (pr-str (census stack-frame))))))))))
            (.catch (fn [e]
                      (is false (str "W4 never settled: " (.-message e)
                                     " " (pr-str (census stack-frame))))
                      nil))
            (.then (fn [_] (done))))))))
