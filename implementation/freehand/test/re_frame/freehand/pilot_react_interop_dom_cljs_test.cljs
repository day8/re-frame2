(ns re-frame.freehand.pilot-react-interop-dom-cljs-test
  "F5i, the MOUNTED half — a React library really on a real page, and the
  exact count of what is left behind when it goes.

  The headless sibling proves which host SHAPES an adopter can REACH. This
  file proves what those shapes — and the child path that needs none — do
  against a real `react-dom/client` commit:

    * a SpreadJS-class imperative widget, connected once from a committed
      render, reconciled from config, commanded through the bounded channel
      by the semantic id its use site authored, and released totally;
    * a REAL third-party React component (`@xyflow/react`, already a
      dependency of this repo) on the page through a nested React root
      inside the node an `{:opaque true}` behavior owns — the shape for a
      widget you want that node to own;
    * the ABI that nested root can carry: props, children, a forwarded ref,
      an imperative handle reached through a command, and a `window`
      listener that must come back off;
    * a React component reached the SIMPLER way — as an ordinary child value
      in one shared tree, no nested root, synchronous teardown — measured
      beside the nested root above;
    * the emitter's refusal for a foreign component at a vector HEAD, the
      one place a React component may NOT go (it enters as a child value,
      not as a view head).

  Every cleanup claim is an exact integer measured against a CONTROL mount
  of the same markup with no behavior at all, so a zero cannot be a counter
  that was never written.

  Mounting goes through the PUBLIC door — `v/mount` / `v/unmount!` — because
  the pilot's question is what an adopter's own code looks like.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no DOM to mount
  and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            ;; The event plane, for the ONE negative control that has to name
            ;; what a roster carrier IS (rf2-c1vvn) — a `v/event` value handed
            ;; to a foreign element's raw props, which no walk ever reaches.
            [re-frame.freehand.events :as events]
            [re-frame.freehand.pilot-react-interop :as pilot]
            [re-frame.freehand.pilot-react-interop-compiled :as compiled]
            [re-frame.freehand.pilot-react-interop-react :as rpilot]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            ;; The REACT substrate adapter, not `plain-atom`. A subscription
            ;; that moves has to repaint a mounted occurrence, and only the
            ;; React adapter schedules that — a pilot on the atom adapter
            ;; would assert reconciliation against a tree that never
            ;; re-rendered.
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (pilot/reset-ledger!)
                      (rpilot/reset-ledger!)
                      (behaviors/reset-connections!)
                      (root/reset-registry!)
                      (fr/reset-boundaries!))}))

(def ^:private fid :dom/pilot-react-interop)

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the commit
  AND its flushed effects rather than racing them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- setup!
  "The frame the pilot's page runs in. Created here rather than owned by the
  root so a test can seed app-db BEFORE the first render, and so two mounts
  in one test share one application."
  ([] (setup! nil))
  ([db]
   (live-frame/make-frame {:id fid})
   (when db (frame/replace-app-db! fid db))
   nil))

(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- read* [query] (rf/subscribe-once query {:frame fid}))

(defn- mount!
  "Mount `form` into `container` through the PUBLIC door, scoping the frame
  the pilot already made."
  [container form]
  (v/mount form container {:frame fid}))

(defn- teardown! [container mounted]
  (v/unmount! mounted)
  (.remove container)
  nil)

(defn- q [container selector] (.querySelector container selector))

(defn- tick
  "One microtask turn. Needed ONLY around the nested-React-root workaround,
  whose teardown React forces to be asynchronous — see
  `pilot-react-interop-react/close-root!`. Every release the SUBSTRATE
  performs is synchronous and is asserted without this."
  []
  (js/Promise. (fn [resolve] (js/queueMicrotask #(resolve nil)))))

(defn- unmount-outside-act!
  "Unmount `mounted` in the CURRENT synchronous turn, with no `act` boundary
  around it.

  `act` awaits, and awaiting drains the microtask queue — which is exactly
  the queue the nested root's deferred `.unmount()` is sitting in. So every
  other assertion in this file runs after the gap has already closed, and
  the gap is the thing one test here has to measure. Stepping outside `act`
  is the only way to read the instant BETWEEN the substrate's release and
  React's, and `IS_REACT_ACT_ENVIRONMENT` is lowered across the call so
  React does not report the missing boundary it was deliberately denied."
  [mounted]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (try
    (v/unmount! mounted)
    (finally
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))
  nil)

;; ===========================================================================
;; 1 — the SpreadJS-class widget: THE behavior shape, used as designed
;; ===========================================================================

(deftest a-spreadjs-class-widget-connects-once-from-a-committed-render
  (testing "The integration an adopter would actually write: an opaque,
            mutable, listener-owning host widget over one node. `:connect`
            runs once from the commit with the live node in hand, the widget
            paints itself, and the config it was given came out of an
            ordinary subscription. Nothing in the view holds an instance and
            nothing in app-db does either."
    (if-not (browser?)
      (skip! "the browser job runs the mounted widget assertions")
      (async done
        (setup! {:invoice {:rows [["Widget" 10] ["Gasket" 4]]}})
        (let [container (host-node!)]
          (-> (act #(mount! container [pilot/invoice-page {}]))
              (.then (fn [mounted]
                       (is (= 1 (pilot/live-instances))
                           "exactly one widget instance is live")
                       (is (= 1 (pilot/live-listeners))
                           "holding exactly one host listener")
                       (is (= 1 (behaviors/connection-count)))
                       (is (= #{:invoice/sheet} (behaviors/target-ids))
                           "claiming the semantic id the use site authored")
                       (is (some? (q container "table.acme-sheet"))
                           "and the widget really painted into the node it owns")
                       (is (some? (q container "button.export"))
                           "beside ordinary Freehand markup in the same tree")
                       (act #(teardown! container mounted))))
              (.then (fn [_]
                       (is (= 0 (pilot/live-instances)))
                       (is (= 0 (behaviors/connection-count)))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest the-widget-reconciles-from-config-and-nothing-else
  (testing "Desired state reaches the host through `:config` and `:update`,
            and `rf=`-equal config is not movement. An adopter integrating a
            grid gets edge detection for free — no sticky props, no manual
            diffing, no `componentDidUpdate` comparison."
    (if-not (browser?)
      (skip! "the browser job runs the reconcile assertions")
      (async done
        (setup! {:invoice {:rows [["a" 1]]}})
        (let [container (host-node!)]
          (-> (act #(mount! container [pilot/invoice-sheet {}]))
              (.then (fn [mounted]
                       (is (= 1 (.-length (.querySelectorAll container "table.acme-sheet tr")))
                           "one row painted")
                       (act (fn [] (send! [:invoice/seeded [["a" 1] ["b" 2]]]) mounted))))
              (.then (fn [_]
                       (is (= 2 (.-length (.querySelectorAll container "table.acme-sheet tr")))
                           "a moved config reconciles the host in place")
                       (is (= 1 (pilot/live-instances))
                           "WITHOUT reconstructing the widget — one instance throughout")
                       (is (= 1 (:constructed (pilot/ledger-snapshot)))
                           "constructed exactly once")
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest a-command-reaches-the-widget-and-its-result-comes-back-as-an-event
  (testing "The one-shot imperative operation — export — travels the whole
            real path: a view's `:on-click` intent, an ordinary `reg-event`
            handler returning the reserved command effect as DATA, the
            channel resolving the caller-authored semantic id against the
            LIVE connection, the widget's registered operation running
            synchronously, and its RESULT returning as an ordinary event
            through the behavior's generation-fenced dispatch.

            Nothing in that chain is a handle. No application value ever
            carries the widget."
    (if-not (browser?)
      (skip! "the browser job runs the command assertions")
      (async done
        (setup! {:invoice {:rows [["Widget" 10] ["Gasket" 4]]}})
        (let [container (host-node!)]
          (-> (act #(mount! container [pilot/invoice-sheet {}]))
              (.then (fn [mounted]
                       (act (fn [] (send! [:invoice/export-requested "|"]) mounted))))
              (.then (fn [mounted]
                       (is (= "Widget|10\nGasket|4" (read* [:invoice/last-export]))
                           "the command ran on the live widget and its result came
                            back as ordinary application state")
                       (act (fn [] (send! [:invoice/cell-focus-requested [1 0]]) mounted))))
              (.then (fn [mounted]
                       (is (some? (q container "td[data-cursor]"))
                           "a second operation on the same roster reached the host")
                       (act #(teardown! container mounted))))
              ;; The rejection handler sits UPSTREAM of the single trailing
              ;; `done` (rf2-qpns): `done` runs the whole remainder of the run
              ;; synchronously, so a `.catch` after it claims a foreign throw
              ;; as this row's and fires `done` a second time.
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        nil))
              (.then (fn [_] (done)))))))))

(deftest a-command-addresses-one-live-widget-and-not-its-neighbour
  (testing "Two live instances of the SAME registered behavior under DISTINCT
            semantic ids. If addressing were derived from render position or
            from the behavior id, the decoy would be reachable — so the
            assertion is symmetric."
    (if-not (browser?)
      (skip! "the browser job runs the addressing assertions")
      (async done
        (setup!)
        (let [container (host-node!)]
          (-> (act #(mount! container [pilot/two-sheets {}]))
              (.then (fn [mounted]
                       (is (= 2 (pilot/live-instances)))
                       (is (= #{:invoice/sheet :invoice/draft} (behaviors/target-ids)))
                       (act (fn [] (send! [:invoice/cell-focus-requested [0 0]]) mounted))))
              (.then (fn [mounted]
                       (is (some? (q container "[data-id='primary'] td[data-cursor]"))
                           "the named target performed the host work")
                       (is (nil? (q container "[data-id='decoy'] td[data-cursor]"))
                           "and the live DECOY was untouched")
                       (act #(teardown! container mounted))))
              (.then (fn [_]
                       (is (= 0 (pilot/live-instances)))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest widget-teardown-is-total-measured-against-a-control
  (testing "Acceptance for this pilot is an EXACT retained count after
            unmount, and a zero only means something measured against a
            mount that never allocated. The control renders the same markup
            with no behavior; then the real page mounts, allocates, and is
            torn down through `v/unmount!`."
    (if-not (browser?)
      (skip! "the browser job runs the teardown assertions")
      (async done
        (setup!)
        (let [control (host-node!)
              live    (host-node!)]
          (-> (act #(mount! control [pilot/control-page {}]))
              (.then (fn [mounted]
                       (is (= {:instances 0 :listeners 0 :constructed 0 :destroyed 0}
                              (pilot/ledger-snapshot))
                           "the CONTROL allocates nothing")
                       (is (some? (q control "div.sheet-host"))
                           "and really did mount the same node")
                       (act #(teardown! control mounted))))
              (.then (fn [_] (act #(mount! live [pilot/invoice-sheet {}]))))
              (.then (fn [mounted]
                       (is (= 1 (pilot/live-instances)))
                       (is (= 1 (pilot/live-listeners)))
                       (act #(teardown! live mounted))))
              (.then (fn [_]
                       (is (= {:instances 0 :listeners 0 :constructed 1 :destroyed 1}
                              (pilot/ledger-snapshot))
                           "after teardown: no instance, no listener, and the
                            cumulative totals prove the release RAN rather than
                            the counters never having been written")
                       (is (= 0 (behaviors/connection-count)))
                       (is (= #{} (behaviors/target-ids)))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; 2 — a foreign component at a vector head, in the BROWSER
;; ===========================================================================

(deftest the-react-emitter-crosses-a-declared-host-head
  (testing "The headless suite proved the STRUCTURAL emitter crosses a
            declared host. The React emitter crosses it too — same
            declaration, same three planes — so an adopter meets exactly one
            answer on both hosts. Both emitters used to refuse it and name a
            slice that had not landed; `v/defhost` (D022) is that slice, and
            it is deliberately the sole door: a second spelling beside it
            would give one crossing two sets of laws to keep in step.

            The crossing is orthogonal to the child path, where a finished
            React element is an ordinary child VALUE rather than a
            descriptor at a head — that escape survives, honestly weaker."
    (let [el (fr/element [pilot/chart-host {:spec "bar"}])]
      (is (some? el) "a declared host head produces a real React element"))
    (let [d (try (fr/element [{:re-frame.freehand/host true} {:spec "bar"}]) nil
                 (catch :default e (ex-data e)))]
      (is (= :rf.error/view-bad-head (:rf.error/id d))
          "and the pre-door marker map is an ordinary map — an error head"))))

;; ===========================================================================
;; 3 — a real React component through the nested-root workaround
;; ===========================================================================

(deftest a-nested-react-root-carries-the-whole-react-abi
  (testing "The NESTED-ROOT path, exercised against a component built the
            way a real library builds one: value props, React children, a
            forwarded ref, an imperative handle, and a mount effect that
            installs a real `window` listener. This is the path for a
            genuinely imperative widget you want an `{:opaque true}` node to
            own; a component that is itself React takes the child path (see
            `a-third-party-react-component-is-an-ordinary-child…`).

            All of it works. What it COSTS relative to the child path is
            that the component's subtree is a SEPARATE React tree — no
            shared context, no shared event path, no Suspense participation,
            no SSR — and the boundary is one-way: the `{:opaque true}` rule
            that makes the nested root safe is the same rule that forbids
            interleaving Freehand content into the foreign component's
            children.

            And it costs one more thing, asserted below rather than described:
            the nested root's TEARDOWN cannot be synchronous, where the child
            path's is. React refuses to unmount a root from inside a render,
            and a behavior's `:disconnect` runs inside the outer tree's
            unmount, so the second React tree is still open at the instant
            the substrate's own release has finished."
    (if-not (browser?)
      (skip! "the browser job runs the nested-root assertions")
      (async done
        (setup!)
        (let [container (host-node!)]
          (-> (act #(mount! container [rpilot/widget-page {:label "alpha" :tick 1}]))
              (.then (fn [mounted]
                       (is (= 1 (rpilot/live-roots))
                           "one nested React root, inside the node the behavior owns")
                       (is (= 1 (rpilot/live-probe-mounts))
                           "the foreign component's mount effect ran")
                       (is (= 1 (rpilot/live-probe-listeners))
                           "and it installed its window listener")
                       (is (= "alpha" (.getAttribute (q container ".acme-widget")
                                                     "data-label"))
                           "value props crossed")
                       (is (= "react children" (.-textContent (q container ".acme-widget em")))
                           "and so did React's own children")
                       ;; The imperative handle a React library expects a
                       ;; caller to hold is reached through the BOUNDED
                       ;; command channel. It never leaves the behavior's
                       ;; private memory.
                       (act (fn [] (send! [:acme/ping-requested]) mounted))))
              (.then (fn [mounted]
                       (is (= "ping:alpha" (read* [:acme/last-ping]))
                           "the imperative handle answered, and its result came
                            back as an ordinary event")
                       (act #(teardown! container mounted))))
              (.then (fn [_]
                       (is (= 0 (behaviors/connection-count))
                           "the substrate's own release is synchronous")
                       ;; The nested root's is NOT: React refuses a
                       ;; synchronous unmount from inside a render, so
                       ;; `close-root!` defers it a microtask. `act` drains
                       ;; microtasks, so the intermediate open state is not
                       ;; observable from here — the deferral is evidenced
                       ;; instead by the ABSENCE of React's
                       ;; `Attempted to synchronously unmount a root while
                       ;; React was already rendering` error, which the
                       ;; browser runner fails the whole lane on.
                       (tick)))
              (.then (fn [_]
                       (is (= 0 (rpilot/live-roots))
                           "and the nested root has closed")
                       (is (= 0 (rpilot/live-probe-mounts))
                           "the foreign component's cleanup ran")
                       (is (= 0 (rpilot/live-probe-listeners))
                           "and its window listener came back off — total release
                            through a boundary the substrate never sees inside")
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest a-real-third-party-react-component-renders-and-releases
  (testing "`@xyflow/react` — a genuine third-party React component library,
            already a dependency of this repo and adapted in no way for this
            pilot — mounted from Freehand through the nested root, rendering
            its own DOM, and released completely on unmount.

            The nested root is ONE way to put React Flow on the page, and
            the right one when you want an `{:opaque true}` node to own the
            widget. But React Flow IS a React component, so its simplest
            path is as an ordinary child in one shared tree — the child-path
            row proves that — and this row is the more expensive alternative
            measured beside it, not the only answer to `can I use a
            third-party React component?`."
    (if-not (browser?)
      (skip! "the browser job runs the React Flow mount")
      (async done
        (setup!)
        (let [container (host-node!)
              nodes     [{:id "a" :position {:x 0 :y 0} :data {:label "A"}}
                         {:id "b" :position {:x 120 :y 60} :data {:label "B"}}]
              edges     [{:id "a-b" :source "a" :target "b"}]]
          (-> (act #(mount! container [rpilot/flow-page {:nodes nodes :edges edges}]))
              (.then (fn [mounted]
                       (is (= 1 (rpilot/live-roots)))
                       (is (some? (q container ".react-flow"))
                           "React Flow really rendered its own DOM")
                       (is (= 2 (.-length (.querySelectorAll container ".react-flow__node")))
                           "with the two nodes the config named")
                       (act #(teardown! container mounted))))
              (.then (fn [_] (tick)))
              (.then (fn [_]
                       (is (= 0 (rpilot/live-roots))
                           "and the nested root closed on unmount (one microtask
                            later — see the ABI case above)")
                       (is (nil? (q container ".react-flow"))
                           "leaving no React Flow DOM behind")
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest the-nested-roots-release-is-the-one-that-is-not-synchronous
  (testing "The workaround's sharpest edge, MEASURED rather than described.

            React 19 refuses to unmount a root from inside a render, and a
            behavior's `:disconnect` runs from the outer tree's own unmount —
            so `close-root!` defers the `.unmount()` by a microtask. That
            makes the nested root the ONE resource in the substrate whose
            release is not synchronously assertable, and an application
            counting live instances (or a leak check in CI) has to know to
            wait a tick for exactly this one kind of resource.

            This test reads the instant BETWEEN the two releases, which the
            rest of the file cannot: `act` awaits, awaiting drains the
            microtask queue, and the gap is closed by the time any `.then`
            runs. So the unmount happens outside `act`, in one synchronous
            turn, and both halves are read before control returns to the
            event loop.

            WHAT THIS CAN ASSERT: that the substrate's own absence is TOTAL
            and SYNCHRONOUS at the instant of unmount, and that the author's
            nested root — with the foreign component's `window` listener
            still on it — is measurably still open at that same instant, and
            closed one microtask later.

            WHAT IT CANNOT ASSERT for the nested root: that the foreign
            component was released synchronously. It was not — that is the
            nested root's cost. It EVAPORATES on the child path, which
            renders inside the Freehand tree's own React root and has no
            second root to close: the child-path row unmounts outside `act`
            and reads a listener count of zero in the same turn."
    (if-not (browser?)
      (skip! "the browser job runs the deferred-release measurement")
      (async done
        (setup!)
        (let [container (host-node!)]
          (-> (act #(mount! container [rpilot/widget-page {:label "alpha"}]))
              (.then (fn [mounted]
                       (is (= 1 (rpilot/live-roots))
                           "the nested root is open before the unmount")
                       (is (= 1 (behaviors/connection-count))
                           "and the substrate holds its one connection")
                       ;; ONE synchronous turn. Nothing awaits between the
                       ;; unmount and the two reads below, so the microtask
                       ;; `close-root!` queued has not run yet.
                       (unmount-outside-act! mounted)
                       (is (= 0 (behaviors/connection-count))
                           "the SUBSTRATE's release is synchronous — the
                            connection table is empty the instant the node goes")
                       (is (= 1 (rpilot/live-roots))
                           "and the nested React root is NOT: it is still open
                            in the same turn, because React refuses a
                            synchronous unmount from inside a render")
                       (is (= 1 (rpilot/live-probe-listeners))
                           "so the foreign component's window listener is still
                            installed at the moment the substrate's own absence
                            is already true — this is the leak a naive check
                            would report")
                       (tick)))
              (.then (fn [_]
                       (is (= 0 (rpilot/live-roots))
                           "one microtask later the nested root has closed")
                       (is (= 0 (rpilot/live-probe-listeners))
                           "and the foreign listener came back off — the release
                            is total, it is simply one tick behind")
                       (.remove container)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; 3b — THE CHILD PATH: a React component as an ordinary child value
;; ===========================================================================
;;
;; The finding the nested-root rows above are measured against. A raw React
;; element in a child position passes the fold's total `react/isValidElement`
;; arm straight into the Freehand tree's OWN React root — so a third-party
;; React component is an ordinary child value, not a second tree.

(deftest a-third-party-react-component-is-an-ordinary-child-in-one-shared-tree
  (testing "THE CHILD PATH, mounted. A raw React element in a child position
            renders in the Freehand tree's own React root, and this row
            proves the four things the nested root cannot give: ONE React
            tree (no root opened), a context Provider reaching its consumer,
            interleaving in BOTH directions, and SYNCHRONOUS teardown.

            It is the row that makes the nested-root cost above a comparison
            rather than an only-option: the same real React component that
            reaches the page through a second tree in section 3 reaches it
            here as a child, in the shared tree, for none of those costs."
    (if-not (browser?)
      (skip! "the browser job runs the child-path mount")
      (async done
        (setup!)
        (let [container (host-node!)]
          (-> (act #(mount! container [rpilot/react-child-page {:label "beta"}]))
              (.then (fn [mounted]
                       (is (= 0 (rpilot/live-roots))
                           "NO nested React root was opened — the component renders in
                            the Freehand tree's own root, one tree")
                       (is (= 1 (rpilot/live-probe-mounts))
                           "and the React component really mounted, as a child")
                       (is (= "beta" (.getAttribute (q container ".acme-widget") "data-label"))
                           "its value props crossed")
                       (is (= "from freehand"
                              (.-textContent (q container ".acme-widget .fh-badge")))
                           "and Freehand content, exported with v/->react, rendered INSIDE
                            the React component's children — interleaving both directions")
                       (is (= "outer" (.-textContent (q container ".theme-probe")))
                           "a React context Provider reached its consumer — 'outer', not
                            the context default, which only holds inside ONE React tree")
                       ;; SYNCHRONOUS teardown, in one turn and with NO
                       ;; microtask — the cost the nested root pays (see the
                       ;; deferred-release row above) and the child path does
                       ;; not.
                       (unmount-outside-act! mounted)
                       (is (nil? (q container ".acme-widget"))
                           "unmount removed the component's DOM in the SAME turn")
                       (is (= 0 (rpilot/live-probe-listeners))
                           "and its window listener came off SYNCHRONOUSLY — no tick, unlike
                            the nested root's deferred release")
                       (is (= 0 (behaviors/connection-count))
                           "and nothing connected — the child path opened no behavior")
                       (.remove container)
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; 3c — THE INWARD HALF of the child path: how a foreign component calls BACK
;; ===========================================================================
;;
;; rf2-c1vvn. Section 3b proved values crossing OUTWARD into the component. The
;; other direction is what an integration actually lives or dies by, and the
;; pilot used to leave it unexercised while `acme-widget` advertised an `onPing`
;; prop — so the shape nobody had run was the shape three guide pages
;; prescribed, and it could never have worked.
;;
;; A raw `createElement` prop map is the LIBRARY's ABI. The child fold's
;; `react/isValidElement` arm is total, so the finished element passes through
;; untouched and no Freehand machinery ever looks inside `#js {…}`. The roster
;; forms (`v/event`, `v/handler`, …) are site-owned — they are materialised only
;; at prop positions Freehand itself walks — so a carrier placed in raw props
;; reaches the library exactly as authored, and a call on it deliberately cannot
;; SUCCEED. Since rf2-yn5nj it cannot fail mutely either: the call raises the
;; typed diagnostic that names this position and this recovery.
;;
;; What DOES work is an ordinary closure over `(rf/capture-frame)`'s `:dispatch`:
;; the render scope has unwound by the time the component calls back, so ambient
;; `rf/dispatch` would raise `:rf.error/no-frame-context`, while the captured
;; bundle is fenced to the exact frame incarnation it was taken from. Both halves
;; are asserted — the live path in a real browser, and the dead one wherever this
;; file runs, because a negative control that needs a DOM is a negative control
;; that mostly does not run.

(deftest a-roster-carrier-in-raw-js-props-can-never-be-called
  (testing "The NEGATIVE control, and the whole of rf2-c1vvn's claim. `v/event`
            answers a roster CARRIER, deliberately not the function it wraps, so
            that a foreign API handed one fails at the call rather than silently
            doing the wrong thing. Nothing materialises a carrier found inside a
            foreign element's raw props — the child fold hands the element
            through IDENTICALLY, which is asserted rather than described — so
            `props.onPing(…)` reaches a value that cannot answer it. This is why
            the inward path is a closure and not a roster form.

            What that failure SAYS is rf2-yn5nj's half: the carrier carries the
            host call protocol in order to THROW, so a call that goes THROUGH
            that protocol raises the typed `:rf.error/view-bad-event` naming the
            form, the position and the recovery — where it used to raise the
            host's own `cb.call is not a function`, a message that named none of
            them. Every ClojureScript caller goes through it; a native
            JavaScript one does not, and the deftest below is where that is
            asked rather than assumed."
    (let [carrier (.-onPing rpilot/dead-carrier-props)]
      (is (events/callback? carrier)
          "the authored value is a roster carrier")
      (is (= :event (events/callback-role carrier)))
      (is (not (fn? carrier))
          "and it is NOT a JS function, so a library cannot call it")
      (is (ifn? carrier)
          "it DOES carry the host call protocol — in order to THROW, exactly as a
           declared view does (rf2-yn5nj) — so `ifn?` is not a classification and
           the `callback?` above is what answers what the value IS")
      (is (= :rf.error/view-bad-event
             (:rf.error/id (ex-data (try (carrier "click:dead")
                                         nil
                                         (catch :default e e)))))
          "and invoking it FROM CLOJURESCRIPT raises the TYPED diagnostic rather
           than the host's own `cb.call is not a function`, which named neither
           the carrier nor the fix")
      (let [message (try (carrier "click:dead")
                         nil
                         (catch :default e (ex-message e)))]
        (is (string/includes? message "v/event")
            "the message names the roster form that was invoked")
        (is (string/includes? message "createElement")
            "and the position class that produces this mistake — these props")
        (is (string/includes? message "capture-frame")
            "and the closure the section below then proves live"))
      (let [el      (react/createElement rpilot/acme-widget rpilot/dead-carrier-props)
            ^js kid (fr/element el)]
        (is (identical? el kid)
            "the child fold passes a finished React element through IDENTICALLY")
        (is (identical? carrier (unchecked-get (.-props kid) "onPing"))
            "so the carrier is still the carrier on the other side — there is no
             materialisation point on this path at all")))))

(deftest a-native-javascript-call-is-outside-the-carrier-diagnostic
  (testing "rf2-yn5nj / `MERGED-PR AUDIT #7034` — THE BOUNDARY, asked rather
            than assumed. The typed diagnostic is reached THROUGH the host's
            call protocol, and in ClojureScript `IFn` is a protocol rather than
            the language's call mechanism: a `deftype` carrying it gains
            `-invoke` arities and `.call` / `.apply` prototype shims, never the
            `Function` internal call slot. So the ClojureScript call in the
            deftest above lands on the diagnostic, while an actual JavaScript
            component running `props.onPing(…)` — the position that message
            names, executed the way that position really executes — finds an
            object and gets the host's own TypeError.

            The caller is authored in JAVASCRIPT for exactly that reason. A
            ClojureScript function application cannot express the call under
            test, because the compiler emits the protocol dispatch a native
            caller is defined by skipping — which is why `acme-widget`, itself
            written in ClojureScript, could not prove this and reads as though
            it had.

            What this row does NOT ask for is a natively-callable carrier.
            `(fn? carrier)` is false on purpose and every nominal reader
            depends on it staying false; the law rf2-c1vvn established is that
            the call must never SUCCEED, and it does not here either. So Spec
            004's guarantee at a raw `createElement` prop is the failure, the
            didactic message rides as far as the host protocol reaches, and the
            captured-frame closure below is the contract rather than the
            stylistic preference it would be if the diagnostic were total."
    (let [carrier   (.-onPing rpilot/dead-carrier-props)
          ;; A genuinely JavaScript-authored caller: its body is a literal
          ;; native call expression, so nothing in it can route through the
          ;; ClojureScript call protocol the way `(carrier "…")` does.
          js-caller (js/Function. "f" "return f('click:dead');")
          thrown    (try (js-caller carrier) ::no-throw (catch :default e e))]
      (is (= "object" (goog/typeOf carrier))
          "the carrier is a plain OBJECT — no [[Call]] slot — which is the same
           fact `(not (fn? carrier))` reports one deftest up, stated the way
           JavaScript sees it")
      (is (not= ::no-throw thrown)
          "a native call on it still cannot SUCCEED — the law that matters is
           intact by this route too")
      (is (instance? js/TypeError thrown)
          "and what it raises is the HOST's own TypeError")
      (is (nil? (:rf.error/id (ex-data thrown)))
          "NOT `:rf.error/view-bad-event` — a native call never reaches
           `carrier-called-directly!` at all, so the typed diagnostic covers
           ClojureScript-authored callers and the JVM's whole call protocol,
           and this one position is where it cannot follow"))))

(deftest a-foreign-component-calls-back-through-a-captured-frame
  (testing "The LIVE inward path, mounted. A real DOM click on the third-party
            component's own button invokes the `onPing` prop it advertises, that
            prop is an ordinary closure over `(rf/capture-frame)`'s `:dispatch`,
            and the event lands in THIS page's frame — through the raw-child
            path, with no nested root and no behavior anywhere. The frame is the
            load-bearing half: the render scope is long gone when React fires
            the handler, so a callback closing over ambient `rf/dispatch` would
            raise `:rf.error/no-frame-context` instead of dispatching."
    (if-not (browser?)
      (skip! "the browser job runs the inward-callback mount")
      (async done
        (setup!)
        (let [container (host-node!)]
          (-> (act #(mount! container [rpilot/react-child-page {:label "beta"}]))
              (.then (fn [mounted]
                       (is (nil? (read* [:acme/last-ping]))
                           "nothing has pinged yet")
                       (-> (act #(.click (q container ".acme-widget-ping")))
                           (.then (fn [_]
                                    (is (= "click:beta" (read* [:acme/last-ping]))
                                        "the foreign component's own click handler called
                                         the closure, and the dispatch landed in this
                                         page's frame")
                                    (is (= 0 (rpilot/live-roots))
                                        "with no nested React root")
                                    (is (= 0 (behaviors/connection-count))
                                        "and no behavior connection — this is the child
                                         path, not the nested-root one")
                                    (teardown! container mounted)
                                    (done))))))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

;; ===========================================================================
;; 4 — a COMPILED page containing the integration, on a real page
;; ===========================================================================
;;
;; Newly answerable: compiled views became browser-mountable. The headless
;; sibling proves the compiled tier REFUSES to attach the integration and
;; that its recovery type-checks; this proves the recovery actually runs.

(deftest a-compiled-page-hosts-the-interpreted-integration-in-one-react-tree
  (testing "The shape an adopter ends up with once they meet the compiled
            tier's refusal: a `{:compiled true}` page whose hot markup is
            lowered, mounting the interpreted view that owns the widget as
            an ordinary declared child.

            It is ONE React tree — the compiled parent's lowered elements
            and the interpreted child's boundary render into the same root,
            the widget connects from the same commit, and a command
            addressed to the child's semantic target reaches it from an
            event dispatched by the COMPILED parent's own button. Nothing
            about the crossing is visible at runtime, which is the promise."
    (if-not (browser?)
      (skip! "the browser job runs the compiled-crossing mount")
      (async done
        (setup! {:invoice {:rows [["Widget" 10] ["Gasket" 4]]}})
        (let [container (host-node!)]
          (-> (act #(mount! container [compiled/hot-list {:title "Invoices"}]))
              (.then (fn [mounted]
                       (is (= "Invoices" (.-textContent (q container "h1.title")))
                           "the COMPILED parent's own markup is on the page")
                       (is (some? (q container "table.acme-sheet"))
                           "and the interpreted child's widget really connected")
                       (is (= 1 (pilot/live-instances)))
                       (is (= #{:invoice/sheet} (behaviors/target-ids)))
                       ;; the compiled parent's button, the interpreted
                       ;; child's widget, one command between them
                       (act (fn [] (send! [:invoice/export-requested ","]) mounted))))
              (.then (fn [mounted]
                       (is (= "Widget,10\nGasket,4" (read* [:invoice/last-export]))
                           "a command dispatched from the compiled parent reached
                            the widget the interpreted child owns")
                       (act #(teardown! container mounted))))
              (.then (fn [_]
                       (is (= 0 (pilot/live-instances))
                           "and teardown across the crossing is still total")
                       (is (= 0 (behaviors/connection-count)))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

(deftest the-nested-root-is-not-free-and-the-cost-is-named
  (testing "The honest cost accounting, asserted rather than asserted away,
            and named RELATIVE to the child path — which opens no second
            tree at all. The nested root is opened by the behavior at COMMIT
            — so a candidate React abandons opens none — and closed at
            disconnect. The `roots-opened` / `roots-closed` totals after a
            mount/unmount cycle are the proof that the second tree's lifetime
            is exactly the behavior's, which is the one guarantee that makes
            the extra tree tolerable when a widget genuinely needs it."
    (if-not (browser?)
      (skip! "the browser job runs the nested-root lifetime assertions")
      (async done
        (setup!)
        (let [container (host-node!)]
          (-> (act #(mount! container [rpilot/control-widget-page {}]))
              (.then (fn [mounted]
                       (is (= {:roots 0 :probe-mounts 0 :probe-listeners 0
                               :roots-opened 0 :roots-closed 0}
                              (rpilot/ledger-snapshot))
                           "the CONTROL opens no nested root at all")
                       (act #(teardown! container mounted))))
              (.then (fn [_] (act #(mount! (host-node!) [rpilot/widget-page {}]))))
              (.then (fn [mounted]
                       (is (= 1 (:roots-opened (rpilot/ledger-snapshot))))
                       (act #(v/unmount! mounted))))
              (.then (fn [_] (tick)))
              (.then (fn [_]
                       (let [l (rpilot/ledger-snapshot)]
                         (is (= 1 (:roots-opened l)))
                         (is (= 1 (:roots-closed l))
                             "opened once, closed once — the second React tree's
                              lifetime is exactly the behavior's connection")
                         (is (= 0 (:roots l))))
                       (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))
