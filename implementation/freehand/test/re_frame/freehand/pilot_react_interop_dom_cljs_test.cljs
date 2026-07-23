(ns re-frame.freehand.pilot-react-interop-dom-cljs-test
  "F5i, the MOUNTED half — a React library really on a real page, and the
  exact count of what is left behind when it goes.

  The headless sibling proves which host shapes an adopter can REACH. This
  file proves what the one reachable shape actually does, against a real
  `react-dom/client` commit:

    * a SpreadJS-class imperative widget, connected once from a committed
      render, reconciled from config, commanded through the bounded channel
      by the semantic id its use site authored, and released totally;
    * a REAL third-party React component (`@xyflow/react`, already a
      dependency of this repo) on the page, reached the only way it can be
      reached today — a nested React root inside the node an `{:opaque
      true}` behavior owns;
    * the ABI that nested root can carry: props, children, a forwarded ref,
      an imperative handle reached through a command, and a `window`
      listener that must come back off;
    * the emitter's refusal for a foreign component at a vector head, which
      is the OTHER half of why the nested root is necessary.

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
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
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
              (.then (fn [_] (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (done)))))))))

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

(deftest the-react-emitter-refuses-a-foreign-component-head
  (testing "The headless suite proved the structural emitter refuses a host
            descriptor. The React emitter refuses it too, and names the same
            missing slice — so an adopter meets exactly one answer on both
            hosts, and the nested-root workaround below is not a preference."
    (let [d (try (fr/element [pilot/foreign-leaf {:spec "bar"}]) nil
                 (catch :default e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id d)))
      (is (re-find #"host boundary and its three host shapes"
                   (or (:reason d) ""))
          (str "the React emitter names the three host shapes as landing "
               "later; got " (pr-str (:reason d)))))))

;; ===========================================================================
;; 3 — a real React component through the nested-root workaround
;; ===========================================================================

(deftest a-nested-react-root-carries-the-whole-react-abi
  (testing "The workaround, exercised against a component built the way a
            real library builds one: value props, React children, a
            forwarded ref, an imperative handle, and a mount effect that
            installs a real `window` listener.

            All of it works. What it costs is that the component's subtree is
            a SEPARATE React tree — no shared context, no shared event path,
            no Suspense participation, no SSR — and the boundary is one-way:
            the `{:opaque true}` rule that makes the nested root safe is the
            same rule that forbids interleaving Freehand content into the
            foreign component's children.

            And it costs one more thing, asserted below rather than described:
            the nested root's TEARDOWN cannot be synchronous. React refuses to
            unmount a root from inside a render, and a behavior's
            `:disconnect` runs inside the outer tree's unmount, so the second
            React tree is still open at the instant the substrate's own
            release has finished."
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

            This is the pilot's answer to `can I use a third-party React
            component?`: yes, today, through one workaround that costs a
            second React tree. It is NOT the qualified leaf the design calls
            for, and this test will need rewriting the day that lands."
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
  (testing "The honest cost accounting, asserted rather than asserted away.
            The nested root is opened by the behavior at COMMIT — so a
            candidate React abandons opens none — and closed at disconnect.
            The `roots-opened` / `roots-closed` totals after a mount/unmount
            cycle are the proof that the second tree's lifetime is exactly
            the behavior's, which is the one guarantee that makes the
            workaround tolerable."
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
