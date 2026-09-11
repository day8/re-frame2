(ns day8.re-frame2-xray.resize-handle-boundary-dom-cljs-test
  "Real-DOM witnesses for the inline panel-width handle as a Fresco
  BOUNDARY behind the shipped `Handle` bridge (rf2-po5r, audit of
  rf2-k97c.3).

  ## Why a browser row at all — neither existing lane executes this

  `resize_handle_cljs_test` drives `handle-tree`, the boundary's PURE
  inner fn, through a test-owned read helper, and asserts
  `host-asserts-own-handle?` on its own. Both are correct and neither
  runs `handle-view`: a `rf.fresco/sub` is legal only inside a render
  window, so the node lane CANNOT call the boundary, and that file's own
  comment says so — \"the composition of the two is the browser lane's
  subject\". This file is that browser lane; before it there was none.

  `shell_fresco_boundary_dom_cljs_test` does mount the real shell, which
  heads `Handle`, but every row there keys off the selected tab and the
  chrome's aggregate node counts. It never selects the handle and never
  reads a width, so `handle-view` returning nil, ignoring the host's
  resize opt-out, or freezing its width evades it.

  TWO FURTHER MEASURED GAPS this file closes, neither of them visible
  from a green run of either lane:

  * The node lane has NO `js/document` (no jsdom under `:node-test`), so
    every `(when (exists? js/document) …)` yield row in
    `resize_handle_cljs_test` is INERT there — and that file's name does
    not end `-dom-cljs-test`, so it is absent from the `:browser-test`
    build. The yield predicate therefore had no lane that executed it at
    all. [[w3-the-yield-branch-is-the-hosts-resize-declaration]] is the
    first row anywhere that runs it.
  * `expand-tree` INVOKES a fn head, so the node lane cannot grade head
    legality either: `[x …]` and `(x …)` expand identically. Only a
    committed DOM separates a bridge that mounts from one that merely
    type-checks.

  ## The mount is the SHIPPED mount

  `shell.cljs`'s `shell-view-tree` — still a Reagent tree — heads
  `[resize-handle/Handle mode]`, and `Handle` answers
  `[:> handle-component {}]`, Fresco's `as-component` door, under the
  `rf/frame-provider` that same tree wraps its children in. [[mount!]]
  reproduces exactly that two-level form and nothing else: the provider,
  a marker `<div>`, and `Handle` called with `:inline`. Mounting the
  whole shell would witness the same crossing through several hundred
  nodes of unrelated chrome; the bead asks for a small witness through
  the bridge, so this is the bridge.

  Nothing below ever calls a view a second time. Every assertion after a
  mount reads `container.querySelector…` — the DOM React committed on
  its own.

  ## The three rows

  W1 asks whether the handle PAINTS and whether its `aria-valuenow` is
  LIVE, with a second live frame as the deaf lever. W2 presses a REAL
  key on the committed node, so the dispatcher the boundary CAPTURED is
  exercised rather than bypassed by the row's own `dispatch-sync`, and
  then resets through the same door. W3 is the yield branch, and it is a
  PAIR of mounts differing only in the host's `resize` declaration — a
  `handle-view` that returned nil unconditionally would satisfy the
  yield half on its own, so the half that renders is what makes the
  absence a claim about the gate rather than about the bridge.

  ## Substrate: the Reagent adapter, deliberately

  The parent the bridge exists FOR is a Reagent tree. Mounting under any
  other substrate would witness a crossing that ships nowhere.

  ## Node-lane behaviour

  This ns matches the `:browser-test` build's `-dom-cljs-test$` regex and
  also loads under `:node-test`, where every row short-circuits through
  [[browser?]] and reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- the frames ----------------------------------------------------------

(def ^:private handle-frame
  "The frame this suite's handle instance is mounted at. NOT `:rf/xray`:
  the production singleton is shared with every other suite on the page,
  and naming a private frame is what makes the deaf-lever halves below
  mean something."
  ::handle)

(def ^:private other-frame
  "A second live frame the rows write into as their DEAF LEVER. A write
  here must move nothing the committed handle announces."
  ::other)

;; ---- the widths ----------------------------------------------------------
;;
;; Spelled as LITERALS rather than derived from the sub the rows are
;; testing. A row that compared the announced width against a width it
;; read the same way would agree with itself under a plant that broke
;; both, and would go green on the defect.

(def ^:private seeded-width-px
  "The width W1 and W2 write into [[handle-frame]] before asserting
  anything. Distinct from `config/default-panel-width-px` so the first
  paint's value and the seeded value are different strings, and
  comfortably inside the [320px, viewport×0.9] clamp at any sane
  headless viewport."
  500)

(def ^:private deaf-width-px
  "The DISTINCT value [[other-frame]] is held at. Not the default and not
  [[seeded-width-px]], so `unchanged` names a specific value rather than
  one every frame answers with anyway."
  420)

(def ^:private width-after-one-arrow-px
  "What [[seeded-width-px]] becomes after ONE unmodified `ArrowLeft`:
  +8px, the documented fine step (`keyboard-step-px`, spec/007-UX-IA.md
  §Keyboard). Written out rather than computed from the private step so
  the row fails if the step changes silently."
  508)

;; ---- browser gate --------------------------------------------------------

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns and has no `js/document` to mount React into — and
  no jsdom either, which is why the yield rows in the node-lane suite
  never execute."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- settling ------------------------------------------------------------
;;
;; NO `flush-render!` HELPER HERE, for the reason the shell suite records:
;; a Fresco boundary is not in Reagent's render queue — the collector
;; schedules its update through React — so draining Reagent's queue commits
;; nothing of this boundary's. Mount is committed with `flushSync` (React's
;; own door) and everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the ABSENCE assertions: an absence asserted immediately after the
  world moves is a race, and an absence asserted after this is a
  decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- poll-until
  "Resolve as soon as `pred` answers truthy, or after `budget-ms`. The
  resolution value is `pred`'s last answer, so a caller asserts on the
  value rather than on the fact that polling ended."
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

;; ---- the layout host -----------------------------------------------------

(defn- remove-stub-hosts!
  "Detach every `[data-rf-xray-host]` on the page. Plural on purpose: the
  selector is what `host-asserts-own-handle?` queries, and a SECOND host
  left standing would be the one `querySelector` answers with."
  []
  (when (and (exists? js/document) (.-querySelectorAll js/document))
    (doseq [el (array-seq (.querySelectorAll js/document "[data-rf-xray-host]"))]
      (when (.-parentNode el)
        (.removeChild (.-parentNode el) el))))
  nil)

(defn- attach-stub-host!
  "Attach an `<aside data-rf-xray-host>` carrying an explicit `resize`
  declaration — the consumer's own element, at the selector
  `config/get-layout-host-selector` publishes. `resize-value` nil attaches
  a host that declares nothing, which is the zero-config consumer.

  The declaration goes on the inline style because that is what
  `getComputedStyle` — the probe's actual read — resolves; a class would
  need a stylesheet this suite does not own."
  [resize-value]
  (remove-stub-hosts!)
  (let [host (.createElement js/document "aside")]
    (.setAttribute host "data-rf-xray-host" "")
    (when resize-value
      (set! (-> host .-style .-resize) resize-value))
    (.appendChild (.-body js/document) host)
    host))

;; ---- fixture -------------------------------------------------------------
;;
;; Declared AFTER [[remove-stub-hosts!]] on purpose: a ClojureScript `ns` is
;; read top to bottom and the fixture's `:init-fn` closes over that var.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      ;; `reset-runtime!` rather than `reset-all!` — it
                      ;; clears the PERSISTED settings, and the panel width
                      ;; lives in that process-global map as well as in each
                      ;; frame's `app-db`. Without it a width written by one
                      ;; row is the next row's first paint.
                      (xray-test-support/reset-runtime!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about.
                      (rf.fresco.impl.collector/reset-runtime!)
                      ;; A stub layout host left attached by W3 would make
                      ;; every later row yield and read as a missing handle.
                      ;; Cheaper to guarantee the page is clean than to rely
                      ;; on a row's own teardown.
                      (remove-stub-hosts!))}))

;; ---- the mount -----------------------------------------------------------

(def ^:private anchor-testid
  "A marker node mounted as the handle's SIBLING, inside the same
  provider. It is what makes an ABSENCE assertion non-vacuous: React
  commits a tree in one pass, so an anchor in the DOM says the position
  the handle occupies was rendered and the boundary answered nil — as
  against the row having read the container before React got to it at
  all."
  "rf-xray-resize-witness-anchor")

(defn- setup!
  "Register Xray's handlers — which is what registers the
  `:rf.xray/panel-width-px` sub and the two write events the handle
  reaches — and make the frames.

  `:rf/xray` is made as well as the two this suite names: it is
  `shell/default-frame-id`, several Xray registrations reach it by that
  name regardless of which frame an instance is mounted at, and a missing
  frame is a loud re-frame refusal that inside a React render surfaces
  only as `an error occurred in <…>`."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id handle-frame})
  (rf/make-frame {:id other-frame})
  nil)

(defn- mount!
  "Mount the handle the way `shell-view-tree` mounts it — the enclosing
  `rf/frame-provider` included:

      [rf/frame-provider {:frame …} … [resize-handle/Handle :inline]]

  THAT WRAPPER IS LOAD-BEARING. `handle-view` resolves its frame from
  React context, which the provider writes; at a bare root there is none
  and the boundary's first frame-scoped call refuses. The provider frame
  is the instance frame, exactly as `shell-view-tree` passes its
  `:frame-id` through.

  `:inline` is passed positionally and stays a KEYWORD on this side of the
  crossing, which is the whole reason `Handle` survives as a bridge:
  `as-component` round-trips prop names but not prop values.

  Committed synchronously — React 19's `root.render` is otherwise async
  and the first assertion would read an empty container."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [:div {:data-testid anchor-testid}
                           [resize-handle/Handle :inline]]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads anything. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

;; ---- reading the committed DOM ------------------------------------------

(defn- testid [container id]
  (.querySelector container (str "[data-testid=\"" id "\"]")))

(defn- handle-node
  "The committed handle `<div>`, or nil. The testid is the documented
  contract `handle-tree` carries."
  [container]
  (testid container "rf-xray-resize-handle"))

(defn- announced-width
  "What the committed handle ANNOUNCES as its current width — the live
  `aria-valuenow` attribute, read off the DOM rather than off the sub.

  Deliberately NOT `.clientWidth`: the handle is a 6px absolutely
  positioned strip whose measured box says nothing about the panel width,
  and a headless container measures 0 anyway. `aria-valuenow` is the
  value the boundary READ and the value assistive tech is given, which is
  the one the bead is about."
  [container]
  (some-> (handle-node container) (.getAttribute "aria-valuenow")))

(defn- width-in
  "One frame's current panel width, read WITHOUT taking a reference.
  `subscribe-once` subscribes, derefs and unsubscribes, so a row may read
  as many frames as it likes without disturbing the boundary's own
  edges."
  [frame-id]
  (rf/subscribe-once [:rf.xray/panel-width-px] {:frame frame-id}))

(defn- press!
  "Dispatch a real bubbling `keydown` on a committed node, or FAIL THIS
  ROW rather than aborting the lane.

  A raw `.dispatchEvent` on nil throws a `TypeError` out of the async
  block and `cljs.test/run-block` has no try/catch — under a plant that
  removed the handle that would take the WHOLE browser lane down with no
  summary, and every namespace scheduled after it would never run. A row
  whose subject has vanished should redden; it must not silence its
  neighbours.

  `bubbles` so React's root-level delegation sees it; `cancelable` so the
  handler's own `preventDefault` is a real call rather than a no-op on an
  uncancelable event."
  [node key-str label]
  (if (some? node)
    (do (.dispatchEvent node (js/KeyboardEvent. "keydown"
                                                #js {:key        key-str
                                                     :bubbles    true
                                                     :cancelable true}))
        true)
    (do (is false (str "cannot press " key-str " on " label
                       ": it is not in the committed DOM, so this row's "
                       "subject is already gone"))
        false)))

;; ===========================================================================
;; W1 — the bridge COMMITS the real handle, and its announced width is LIVE
;; ===========================================================================

(deftest w1-the-bridge-commits-a-handle-whose-width-is-live
  (testing "rf2-po5r — mounting `Handle :inline` under an explicit frame
            commits the shipped handle node, and the width it announces
            follows a write into THAT frame while a write into a second
            live frame moves nothing.

            THIS IS WHAT THE NODE LANE GAVE UP. `handle-tree` is driven
            there with values the test itself supplies, so a green row
            says the markup composes; it cannot say the boundary's body
            ran, that `rf.fresco/sub` resolved, or that the value reaching
            `aria-valuenow` came from a frame rather than from the test.
            A `handle-view` returning nil passes every node-lane row and
            fails the first assertion here."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount! handle-frame)
              !first (atom nil)]
          (-> (poll-until #(handle-node container))
              (.then
                (fn [_]
                  (is (some? (handle-node container))
                      "the `as-component` bridge mounted and the boundary
                       committed a real node — the composition the node
                       lane cannot reach")
                  (is (= "separator" (some-> (handle-node container)
                                             (.getAttribute "role")))
                      (str "and it is the shipped separator, not some other "
                           "node that happens to carry the testid. Got: "
                           (pr-str (some-> (handle-node container)
                                           (.getAttribute "role")))))
                  (reset! !first (announced-width container))
                  (is (= (str config/default-panel-width-px) @!first)
                      (str "PRECONDITION: first paint announces the DEFAULT "
                           "width, so the change below is the write's doing. "
                           "Got: " (pr-str @!first)))
                  (rf/dispatch-sync [:rf.xray/set-panel-width-px seeded-width-px]
                                    {:frame handle-frame})
                  (poll-until #(= (str seeded-width-px)
                                  (announced-width container)))))
              (.then
                (fn [_]
                  (is (= (str seeded-width-px) (announced-width container))
                      (str "the committed handle followed a real write into "
                           "the frame it was mounted at — the read is LIVE, "
                           "not a value frozen at first paint. Was "
                           (pr-str @!first) ", now "
                           (pr-str (announced-width container))))
                  ;; The DEAF LEVER: the same event, into a live frame this
                  ;; mount never named.
                  (rf/dispatch-sync [:rf.xray/set-panel-width-px deaf-width-px]
                                    {:frame other-frame})
                  (settle)))
              (.then
                (fn [_]
                  (is (= deaf-width-px (width-in other-frame))
                      (str "CONTROL: the deaf lever really moved the second "
                           "frame, so the unchanged reading below is about "
                           "ROUTING rather than about a write that never "
                           "happened. Got: " (pr-str (width-in other-frame))))
                  (is (= (str seeded-width-px) (announced-width container))
                      (str "and the handle did NOT follow it. A boundary that "
                           "resolved its read ambiently would announce "
                           (pr-str (str deaf-width-px)) ". Got: "
                           (pr-str (announced-width container))))
                  (teardown! root container)
                  (done)))
              (.catch (fn [e]
                        (is false (str "W1 never settled: " (.-message e)))
                        (done)))))))))

;; ===========================================================================
;; W2 — a REAL KEY PRESS on the committed handle, through the dispatcher the
;;      boundary CAPTURED, into the frame the provider named
;; ===========================================================================
;;
;; W1 moves the world with `rf/dispatch-sync` and watches the handle follow.
;; That is a reactivity claim and it is deliberately silent about the
;; boundary's OTHER half: `handle-view` does not merely READ through the
;; collector, it CAPTURES a dispatcher with `(:dispatch (rf/capture-frame))`
;; and threads it into `handle-tree`, which closes over it in three separate
;; handlers. Nothing above makes it capture one.
;;
;; THE KEYBOARD IS THE RIGHT LEVER HERE, not the pointer drag. A drag routes
;; through `start-drag!`, which stashes the captured dispatcher in a
;; process-global `defonce` atom and installs document-level listeners — a
;; surface the node-lane suite already drives end to end with its simulate-*
;; seams. The keydown path has no such seam: `handle-keydown!`'s
;; `dispatch-fn` reaches it only through the committed node's `:on-key-down`
;; closure, so a capture lost anywhere between the boundary body and that
;; closure is invisible until a real key is pressed on a real node.
;;
;; AND BOTH HALVES OF THE KEY SURFACE ARE EXERCISED — an arrow that RESIZES
;; and an Enter that RESETS — because they are different events
;; (`:rf.xray/set-panel-width-px` directly, versus `:rf.xray/reset-panel-width`
;; whose `:fx` re-dispatches). One of them landing proves little about the
;; other.

(deftest w2-a-real-key-press-routes-through-the-captured-dispatcher
  (testing "rf2-po5r — pressing a real `ArrowLeft` on the committed handle
            widens the panel in the frame the enclosing `frame-provider`
            NAMED, and leaves a second live frame exactly where it was;
            `Enter` then resets it through the same captured door.

            THIS ROW IS NOT A SECOND W1. W1's lever is the row's own
            `dispatch-sync`, which supplies the frame from the OUTSIDE;
            here the frame comes from the boundary's capture and nothing
            in the row names it at the moment of dispatch. That difference
            is the whole subject."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount! handle-frame)]
          (-> (poll-until #(handle-node container))
              (.then
                (fn [_]
                  (is (some? (handle-node container))
                      "PRECONDITION: there is a real control to press")
                  ;; Seed BOTH frames, the deaf one at a distinct value, so
                  ;; `unchanged` below is a claim about a real value rather
                  ;; than about the default every frame answers with.
                  (rf/dispatch-sync [:rf.xray/set-panel-width-px seeded-width-px]
                                    {:frame handle-frame})
                  (rf/dispatch-sync [:rf.xray/set-panel-width-px deaf-width-px]
                                    {:frame other-frame})
                  (poll-until #(= (str seeded-width-px)
                                  (announced-width container)))))
              (.then
                (fn [_]
                  (is (= (str seeded-width-px) (announced-width container))
                      (str "PRECONDITION: the handle announces the seeded "
                           "width before the key press. Got: "
                           (pr-str (announced-width container))))
                  (is (= deaf-width-px (width-in other-frame))
                      (str "PRECONDITION: while the second live frame holds a "
                           "DISTINCT value. Got: "
                           (pr-str (width-in other-frame))))
                  ;; ---- the act: a REAL key press on a REAL node ----
                  (if (press! (handle-node container) "ArrowLeft"
                              "the committed resize handle")
                    (poll-until #(= (str width-after-one-arrow-px)
                                    (announced-width container)))
                    (js/Promise.resolve nil))))
              (.then
                (fn [_]
                  (is (= (str width-after-one-arrow-px)
                         (announced-width container))
                      (str "the key press reached the `:on-key-down` closure, "
                           "the dispatcher `handle-view` captured delivered "
                           "the event, the clamp ran, the read was "
                           "invalidated and the boundary recommitted on the "
                           "new width — the whole round trip, in a browser. "
                           "Got: " (pr-str (announced-width container))))
                  (is (= width-after-one-arrow-px (width-in handle-frame))
                      (str "the write landed in the frame the provider named. "
                           "Got: " (pr-str (width-in handle-frame))))
                  (is (= deaf-width-px (width-in other-frame))
                      (str "CROSS-FRAME CONTROL: and NOT in the second live "
                           "frame, which still holds " (pr-str deaf-width-px)
                           ". A dispatcher that had lost its capture would "
                           "write here, or nowhere. Got: "
                           (pr-str (width-in other-frame))))
                  ;; ---- and the RESET half, a different event entirely ----
                  (if (press! (handle-node container) "Enter"
                              "the committed resize handle")
                    (poll-until #(= (str config/default-panel-width-px)
                                    (announced-width container)))
                    (js/Promise.resolve nil))))
              (.then
                (fn [_]
                  (is (= (str config/default-panel-width-px)
                         (announced-width container))
                      (str "`Enter` reset the panel to the default through the "
                           "same captured dispatcher — `:rf.xray/reset-panel-"
                           "width` and the `:fx` re-dispatch it fans out to. "
                           "Got: " (pr-str (announced-width container))))
                  (is (= deaf-width-px (width-in other-frame))
                      (str "and the reset did not reach the second frame "
                           "either. Got: " (pr-str (width-in other-frame))))
                  (teardown! root container)
                  (done)))
              (.catch (fn [e]
                        (is false (str "W2 never settled: " (.-message e)))
                        (done)))))))))

;; ===========================================================================
;; W3 — the YIELD branch: the host's own `resize` declaration decides
;; ===========================================================================
;;
;; Per rf2-70u8q the auto-inject contract is a silent yield: a consumer who
;; declares `resize: horizontal` (or `:both`) on the layout host has asserted
;; their own browser-native handle, and `handle-view` must render nil so the
;; page does not carry two.
;;
;; THIS IS A PAIR OF MOUNTS AND IT HAS TO BE. An absence on its own is
;; satisfied by a `handle-view` that returns nil for any reason at all — the
;; bead's own counterexample. The two mounts differ in ONE inline CSS
;; declaration on an element neither React root contains, so what separates
;; them is the gate and nothing else.
;;
;; AND IT IS THE FIRST EXECUTION OF THE GATE ANYWHERE. The node-lane rows for
;; `host-asserts-own-handle?` are wrapped in `(when (exists? js/document) …)`
;; and `:node-test` has no jsdom, so they no-op; their file's name does not
;; end `-dom-cljs-test`, so the browser build never loads it. Measured, not
;; inferred: a deliberate failure planted inside those `when` bodies leaves
;; `npm run test:cljs` green.

(deftest w3-the-yield-branch-is-the-hosts-resize-declaration
  (testing "rf2-po5r / rf2-70u8q — with a layout host declaring
            `resize: vertical` the boundary commits its handle; with the
            same host declaring `resize: horizontal` it commits none. One
            CSS declaration is the only difference between the two
            mounts."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        ;; ---- half one: a host that does NOT assert its own handle ----
        (attach-stub-host! "vertical")
        (let [{c1 :container r1 :root} (mount! handle-frame)]
          (-> (poll-until #(testid c1 anchor-testid))
              (.then (fn [_] (settle)))
              (.then
                (fn [_]
                  (is (some? (testid c1 anchor-testid))
                      "CONTROL: the tree committed, so both halves below are
                       reading a rendered position rather than an empty
                       container")
                  (is (some? (handle-node c1))
                      (str "a host declaring `resize: vertical` is NOT "
                           "asserting a horizontal handle, so Xray renders "
                           "its own — the half that makes the absence below "
                           "a claim about the GATE rather than about the "
                           "bridge"))
                  (teardown! r1 c1)
                  ;; ---- half two: the consumer asserts their own ----
                  (attach-stub-host! "horizontal")
                  (let [{c2 :container r2 :root} (mount! handle-frame)]
                    (-> (poll-until #(testid c2 anchor-testid))
                        (.then (fn [_] (settle)))
                        (.then
                          (fn [_]
                            (is (some? (testid c2 anchor-testid))
                                "CONTROL: this second tree committed too")
                            (is (nil? (handle-node c2))
                                (str "and the boundary yielded: the host "
                                     "declares `resize: horizontal`, so the "
                                     "consumer owns the affordance and the "
                                     "page carries ONE handle, not two"))
                            (teardown! r2 c2)
                            (remove-stub-hosts!)
                            (done)))))))
              (.catch (fn [e]
                        (is false (str "W3 never settled: " (.-message e)))
                        (remove-stub-hosts!)
                        (done)))))))))
