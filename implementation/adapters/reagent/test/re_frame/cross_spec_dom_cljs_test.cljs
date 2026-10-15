(ns re-frame.cross-spec-dom-cljs-test
  "Cross-spec runtime and real-DOM contracts; synthetic payload writes do not prove boot ordering or hydration."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.frame :as rf.frame]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing]
            [re-frame.machines]
            [re-frame.flows]
            [re-frame.epoch]
            [re-frame.ssr]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.test-support :refer [with-trace-recorder!]]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true}))

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so Reagent's deferred render-reaction
  disposal (which fires `:rf.view/unmounted` on a real unmount) has settled
  before the test completes."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [remaining]
                (if (zero? remaining)
                  (resolve nil)
                  (js/setTimeout #(step (dec remaining)) 4)))]
        (step n)))))

(defn- await-teardown!
  "Unmount `root` through the real host teardown path under `flushSync` (so the
  unmount commits synchronously), then await Reagent's deferred reaction
  disposal so the `:rf.view/unmounted` marker fires WITHIN the caller's window
  rather than leaking into the shared runner after the test ends. Returns a
  Promise after three macrotask turns."
  [root]
  (try (react-dom/flushSync (fn [] (rdc/unmount root))) (catch :default _ nil))
  (settle-macrotasks 3))

(deftest machine-microstep-subscribe
  "#2 Sub-cache hit inside a machine microstep —
   an EXTERNAL subscribe-once queried AFTER the machine event settles sees the
   committed post-cascade snapshot (the single end-of-cascade commit), never an
   intermediate microstep :data. Machine callbacks do NOT read subs in-band — a
   :guard/:action/:entry/:exit MUST NOT call subscribe-once (a mid-action
   ambient read is unrecorded and breaks 005's token-grain replay contract, per
   005 §Causal host facts); external observers see one macrostep per event."
  (let [machine
        {:initial :idle
         :data    {:step :start}
         :guards  {:seeded? (fn [{:keys [data]}] (= :seeded (:step data)))}
         :actions {:seed   (fn [{:keys [data]}] {:data (assoc data :step :seeded)})
                   :settle (fn [{:keys [data]}] {:data (assoc data :step :settled)})}
         :states  {:idle   {:on {:go {:target :acting :action :seed}}}
                   :acting {:always {:guard :seeded? :action :settle :target :done}}
                   :done   {}}}]
    (rf/reg-machine :auth/check machine)
    (rf/dispatch-sync [:auth/check [:go]])
    (let [snap (rf/subscribe-once [:rf/machine :auth/check])]
      (is (= [:done :settled] [(:state snap) (:step (:data snap))])
          "the machine settled on the post-cascade state, and the external sub
           sees ONLY the committed post-cascade :data — never the intermediate
           :seeded microstep value"))))

(deftest routing-in-ssr-nav-fx-skipped-on-server
  "#6 Routing in SSR —
   :rf.nav/push-url and :rf.nav/replace-url are no-ops on :server; the
   route slice itself is just app-db so it hydrates trivially."
  (rf/make-frame {:id :req :platform :server})
  (rf.fx/reg-fx :rf.nav/push-url
    {:platforms #{:client}}
    (fn [_ _] :should-not-run-on-server))
  (rf/reg-event :emit-nav
    (fn [_ _]
      {:fx [[:rf.nav/push-url "/users/42"]]}))
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:emit-nav] {:frame :req})
    (is (some #(and (= :rf.fx/skipped-on-platform (:operation %))
                    (= :rf.nav/push-url (get-in % [:tags :rf.fx/id])))
              @traces)
        ":rf.nav/push-url emits :rf.fx/skipped-on-platform under :server")))

(deftest frame-destroy-during-render
  "#8 Frame disposal during render —
   The current render pass completes against the snapshot it began with;
   after the render commits, the disposal runs (sub-cache disposes; the
   substrate releases the frame-scoped subtree; subsequent dispatch /
   subscribe against the destroyed frame raises :rf.error/frame-destroyed).

   This case is a real browser-runner test. The :node-test target also
   loads this ns (its `cljs-test$` regex matches both `-cljs-test` and
   the `-dom-cljs-test` suffix), so we gate the
   DOM-mounting branch on `(browser?)` and exit early under
   :node-test where `js/document` is absent."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [render-count (atom 0)
          render-error (atom nil)
          target-frame :tenant-render
          mount-node   (make-mount-node!)
          render-fn    (fn []
                         (let [n (swap! render-count inc)
                               container (rf.frame/app-db-container target-frame)
                               db (when container @container)]
                           (when (= 1 n)
                             (rf/destroy-frame! target-frame))
                           [:div.x {:data-render-count n
                                    :data-db          (pr-str db)}
                            "ok"]))]
      (rf/make-frame {:id target-frame :doc "frame destroyed mid-render"})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
      (rf/dispatch-sync [:seed] {:frame target-frame})
      (with-trace-recorder! [traces]
        (let [root (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider
                                  {:frame target-frame}
                                  [render-fn]])))
            (catch :default e
              (reset! render-error (ex-message e))))
          (try
            (is (= [nil true true nil]
                   [@render-error (>= @render-count 1)
                    (boolean (some #(and (= :rf.frame/destroyed (:operation %))
                                         (= target-frame (get-in % [:tags :frame])))
                                   @traces))
                    (rf.frame/frame target-frame)])
                (str "the render ran without throwing mid-destroy, :rf.frame/destroyed fired "
                     "(destroy-frame! ran the disposal pipeline), and the frame is gone from "
                     "the registry; render error: " (pr-str @render-error)))
            (with-trace-recorder! [post-traces]
              (rf/dispatch-sync [:seed] {:frame target-frame})
              (is (some #(= :rf.error/frame-destroyed (:operation %))
                        @post-traces)
                  "subsequent dispatch against the destroyed frame emits :rf.error/frame-destroyed"))
            (finally
              (try (rdc/unmount root) (catch :default _ nil)))))))))

(deftest plain-fn-under-non-default-frame
  "#10 Plain Reagent fn under a frame-provider —
   a plain (non-`reg-view`) Reagent fn lacks the `:contextType` wiring
   `reg-view` attaches, so its `(rf/subscribe ...)` call cannot read the
   surrounding React-context frame.

   EP-0002: the resolution chain has NO `:rf/default` floor.
   A plain fn that cannot read context resolves to nil, and
   `rf/subscribe` (via `frame/require-current-frame!`) raises
   `:rf.error/no-frame-context` — there is no silent fall-through and
   no once-per-pair warning. The sharper no-frame-context
   error IS the diagnostic: the bare reagent fn fails fast rather than
   targeting a conventional default. The recovery is EXPLICIT authority,
   NOT ambient capture from the unregistered component: use `reg-view`
   (which wires `:contextType` so the child reads the provider), or hand
   the plain fn an explicit target — `(rf/capture-frame frame-id)`, an
   explicit `{:frame …}` opt, or a frame api threaded from a registered
   ancestor. Ambient no-arg `(rf/capture-frame)` captures ONLY when a
   genuinely-live scope surrounds the synchronous op (a registered
   ancestor, or the plain fn itself entering `(rf/with-frame frame-id …)`
   around the op) — it does NOT work from a plain fn whose only surrounding
   scope is a provider it cannot read, and a lexical `with-frame` wrapped
   around the RETURNED subtree unwinds before React invokes the child.
   These boundaries are pinned by the deftests below.

   Browser-only — requires a real React render so the React-context
   tier actually pushes the Provider's value."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-plain-fn-warn]
      (rf/make-frame {:id target-frame :doc "non-default frame for plain-fn no-frame-context test"})
      (rf/reg-event :seed-plain-fn (fn [{:keys [db]} _] {:db {:n 7}}))
      (rf/dispatch-sync [:seed-plain-fn] {:frame target-frame})
      (rf/reg-sub :plain-fn-test/n (fn [db _] (:n db)))
      (let [render-error (atom nil)
            plain-fn     (fn plain-fn-impl []
                           (try
                             @(rf/subscribe [:plain-fn-test/n])
                             (catch :default e (reset! render-error e)))
                           [:div.plain "a"])
            mount-node   (make-mount-node!)
            root         (rdc/create-root mount-node)]
        (try
          (binding [rf.frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root
                            [rf/frame-provider {:frame target-frame}
                             [plain-fn]]))))
          (is (= :rf.error/no-frame-context
                 (:rf.error/id (ex-data @render-error)))
              "a plain fn's subscribe (no :contextType) raised :rf.error/no-frame-context rather than falling through to :rf/default")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest plain-fn-with-frame-around-returned-subtree-still-raises
  "Rejected repair A — an unregistered plain fn wraps the RETURNED child
   subtree in lexical `with-frame`. The binding unwinds when the wrapper
   fn returns; React invokes the plain child LATER, outside that dynamic
   extent. The child (no `:contextType`) reads neither the dynamic var nor
   the provider, so its `subscribe` still raises `:rf.error/no-frame-
   context`. Lexical scope does NOT leak across React's later invocation.
   Browser-only — needs a real React render + child scheduling."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-with-frame-return-leak]
      (rf/make-frame {:id target-frame :doc "frame for the with-frame-around-return rejected repair"})
      (rf/reg-event :seed-wf-return (fn [{:keys [db]} _] {:db {:n 3}}))
      (rf/dispatch-sync [:seed-wf-return] {:frame target-frame})
      (rf/reg-sub :wf-return/n (fn [db _] (:n db)))
      (let [child-error (atom nil)
            inner-child (fn inner-child-impl []
                          (try
                            @(rf/subscribe [:wf-return/n])
                            (catch :default e (reset! child-error e)))
                          [:div.inner "child"])
            outer-wrap  (fn outer-wrap-impl []
                          (rf/with-frame target-frame
                            [inner-child]))
            mount-node  (make-mount-node!)
            root        (rdc/create-root mount-node)]
        (try
          (binding [rf.frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target-frame}
                                  [outer-wrap]]))))
          (is (= :rf.error/no-frame-context
                 (:rf.error/id (ex-data @child-error)))
              "the plain child raised :rf.error/no-frame-context, the same typed boundary as the bare case — with-frame around the returned subtree did not leak scope into React's later invocation")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest plain-fn-no-arg-capture-frame-under-provider-raises
  "Rejected repair B — no-arg `(rf/capture-frame)` inside an unregistered
   plain fn under a `frame-provider`. The no-arg HOLD captures the AMBIENT
   scope at creation time; a plain fn (no `:contextType`) cannot read the
   provider, and with the ambient dynamic binding cleared there is no scope
   to capture → `:rf.error/no-frame-context` at the capture call — the same
   absent-target boundary as the bare subscribe. This is specifically the
   provider-only / absent-dynamic-scope case (see the positive control
   below, which runs the SAME call inside a live with-frame and succeeds).
   Browser-only — needs a real React render under a Provider."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-noarg-capture-under-provider]
      (rf/make-frame {:id target-frame :doc "frame for the no-arg capture-frame rejected repair"})
      (rf/reg-event :seed-noarg (fn [{:keys [db]} _] {:db {:n 5}}))
      (rf/dispatch-sync [:seed-noarg] {:frame target-frame})
      (rf/reg-sub :noarg/n (fn [db _] (:n db)))
      (let [capture-error (atom nil)
            plain-fn (fn plain-noarg-impl []
                       (try
                         (rf/capture-frame)
                         (catch :default e (reset! capture-error e)))
                       [:div "noarg"])
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (binding [rf.frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target-frame}
                                  [plain-fn]]))))
          (is (= :rf.error/no-frame-context
                 (:rf.error/id (ex-data @capture-error)))
              "no-arg capture-frame under a provider-only scope raised :rf.error/no-frame-context rather than capturing :rf/default")
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest plain-fn-inner-with-frame-around-ambient-op-succeeds
  "Positive control — the plain fn ITSELF
   enters `(rf/with-frame frame-id …)` around the SYNCHRONOUS ambient
   operation. Here the dynamic binding is STILL LIVE at the op call (unlike
   repair A, where it unwinds before React invokes a descendant), so the
   very same no-arg `(rf/capture-frame)` that fails in repair B
   succeeds and targets the frame. Ambient capture is legal when a genuine
   live scope surrounds the operation — the failure in repair B is NOT a
   general law against lexical scope in unregistered fns.
   Browser-only — needs a real React render."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-inner-with-frame]
      (rf/make-frame {:id target-frame :doc "frame for the inner-with-frame positive control"})
      (rf/reg-event :seed-inner-wf (fn [{:keys [db]} _] {:db {:n 8}}))
      (rf/dispatch-sync [:seed-inner-wf] {:frame target-frame})
      (rf/reg-sub :inner-wf/n (fn [db _] (:n db)))
      (let [captured-frame (atom nil)
            captured-value (atom nil)
            capture-error  (atom nil)
            plain-fn (fn plain-inner-wf-impl []
                       (try
                         (rf/with-frame target-frame
                           (let [handle (rf/capture-frame)]
                             (reset! captured-frame (:frame handle))
                             (reset! captured-value @((:subscribe handle) [:inner-wf/n]))))
                         (catch :default e (reset! capture-error e)))
                       [:div "inner-wf"])
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (binding [rf.frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target-frame}
                                  [plain-fn]]))))
          (is (= [nil target-frame 8] [@capture-error @captured-frame @captured-value])
              (str "no-arg capture inside a live with-frame did NOT raise, targeted the bound "
                   "frame, and its subscribe read that frame's app-db (:n 8); got: "
                   (pr-str (some-> @capture-error ex-data))))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest plain-fn-explicit-target-succeeds
  "AC — at least one deliberately UNREGISTERED explicit path succeeds and
   targets exactly the provider's frame. An unregistered plain fn CAN reach
   a specific frame by receiving EXPLICIT authority: the 1-arity
   `(rf/capture-frame frame-id)` bundle, or an explicit `{:frame …}` opt on
   `subscribe`. Neither needs a surrounding scope — this is the corrected
   recovery recipe for a plain fn (contrast the rejected ambient repairs A
   and B). A distractor frame with a different value proves the reads
   target EXACTLY the named frame, not a leaked default.
   Browser-only — needs a real React render."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (let [target-frame :tenant-explicit-target
          other-frame  :tenant-explicit-other]
      (rf/make-frame {:id target-frame :doc "frame the explicit path must target"})
      (rf/make-frame {:id other-frame  :doc "distractor frame with a different value"})
      (rf/reg-event :seed-explicit (fn [{:keys [db]} _] {:db {:n 21}}))
      (rf/reg-event :seed-other    (fn [{:keys [db]} _] {:db {:n 99}}))
      (rf/dispatch-sync [:seed-explicit] {:frame target-frame})
      (rf/dispatch-sync [:seed-other]    {:frame other-frame})
      (rf/reg-sub :explicit/n (fn [db _] (:n db)))
      (let [captured-frame (atom nil)
            captured-value (atom nil)
            opt-value      (atom nil)
            render-error   (atom nil)
            plain-fn (fn plain-explicit-impl []
                       (try
                         (let [handle (rf/capture-frame target-frame)]
                           (reset! captured-frame (:frame handle))
                           (reset! captured-value @((:subscribe handle) [:explicit/n])))
                         (reset! opt-value @(rf/subscribe [:explicit/n] {:frame target-frame}))
                         (catch :default e (reset! render-error e)))
                       [:div "explicit"])
            mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (binding [rf.frame/*current-frame* nil]
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame target-frame}
                                  [plain-fn]]))))
          (is (= [nil target-frame 21 21] [@render-error @captured-frame @captured-value @opt-value])
              (str "explicit-target reads did NOT raise: (capture-frame frame-id) locked the bundle "
                   "to the named frame, whose subscribe and the explicit {:frame …} opt both read "
                   "the target (:n 21), not the distractor (:n 99); got: "
                   (pr-str (some-> @render-error ex-data))))
          (finally
            (try (rdc/unmount root) (catch :default _ nil))))))))

(deftest subscribe-routes-via-react-context-under-non-default-frame
  "subscribe consults the React-context tier so a reg-view
   inside a non-default `frame-provider` reads the provider's frame
   (not :rf/default)."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (async done
      (let [target-frame :tenant-rf2-d4sf]
        (rf/make-frame {:id target-frame :doc "non-default frame — :v 42"})
        (rf/reg-event :seed-target (fn [{:keys [db]} _] {:db {:v 42}}))
        (rf/reg-event :seed-default (fn [{:keys [db]} _] {:db {:v 7}}))
        (rf/dispatch-sync [:seed-target]  {:frame target-frame})
        (rf/dispatch-sync [:seed-default] {:frame :rf/default})
        (rf/reg-sub :rf2-d4sf/v (fn [db _] (:v db)))
        (let [resolved-frame (atom nil)
              resolved-value (atom nil)]
          (rf/reg-view* :rf.cross-spec-d4sf/probe
                        (fn probe-impl []
                          (reset! resolved-frame (rf/current-frame-id))
                          (reset! resolved-value @(rf/subscribe [:rf2-d4sf/v]))
                          [:div "probe"]))
          (let [unmounts   (atom [])
                render-fn  (rf/view :rf.cross-spec-d4sf/probe)
                mount-node (make-mount-node!)
                root       (rdc/create-root mount-node)
                finish     (fn []
                             (-> (await-teardown! root)
                                 (.then (fn [_]
                                          (rf.trace.tooling/unregister-listener! ::d4sf-probe-unmounts)
                                          (is (= 1 (count @unmounts))
                                              (str "exactly one :rf.view/unmounted fired for "
                                                   ":rf.cross-spec-d4sf/probe WITHIN the awaited window; got "
                                                   (count @unmounts)))
                                          (done)))))]
            (rf.trace.tooling/register-listener! ::d4sf-probe-unmounts
              (fn [ev]
                (when (and (= :rf.view/unmounted (:operation ev))
                           (= :rf.cross-spec-d4sf/probe (-> ev :tags :rf.view/id)))
                  (swap! unmounts conj ev))))
            (try
              (binding [rf.frame/*current-frame* nil]
                (react-dom/flushSync
                  (fn []
                    (rdc/render root [rf/frame-provider {:frame target-frame}
                                      [render-fn]]))))
              (is (= [target-frame 42] [@resolved-frame @resolved-value])
                  "current-frame inside the reg-view reads the surrounding provider's frame, and subscribe routes against it — :v 42 (target) not :v 7 (:rf/default)")
              (finish)
              (catch :default e
                (is false (str "subscribe-routes-via-react-context-under-non-default-frame threw: " (pr-str e)))
                (finish)))))))))

(deftest subscribe-routes-default-without-frame-provider
  "Negative — without a `frame-provider`, subscribe resolves to
   `:rf/default` through this suite's ambient fixture scope (the
   createContext default is the no-provider sentinel, not `:rf/default`).
   The React-context tier must not change that resolution."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (async done
      (rf/reg-event :seed-no-provider (fn [{:keys [db]} _] {:db {:w 99}}))
      (rf/dispatch-sync [:seed-no-provider])
      (rf/reg-sub :rf2-d4sf/w (fn [db _] (:w db)))
      (let [resolved-frame (atom nil)
            resolved-value (atom nil)]
        (rf/reg-view* :rf.cross-spec-d4sf/probe-no-provider
                      (fn probe-no-provider-impl []
                        (reset! resolved-frame (rf/current-frame-id))
                        (reset! resolved-value @(rf/subscribe [:rf2-d4sf/w]))
                        [:div "probe"]))
        (let [unmounts   (atom [])
              render-fn  (rf/view :rf.cross-spec-d4sf/probe-no-provider)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              finish     (fn []
                           (-> (await-teardown! root)
                               (.then (fn [_]
                                        (rf.trace.tooling/unregister-listener! ::d4sf-probe-no-provider-unmounts)
                                        (is (= 1 (count @unmounts))
                                            (str "exactly one :rf.view/unmounted fired for "
                                                 ":rf.cross-spec-d4sf/probe-no-provider WITHIN the awaited window; got "
                                                 (count @unmounts)))
                                        (done)))))]
          (rf.trace.tooling/register-listener! ::d4sf-probe-no-provider-unmounts
            (fn [ev]
              (when (and (= :rf.view/unmounted (:operation ev))
                         (= :rf.cross-spec-d4sf/probe-no-provider (-> ev :tags :rf.view/id)))
                (swap! unmounts conj ev))))
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [render-fn])))
            (is (= [:rf/default 99] [@resolved-frame @resolved-value])
                "no provider in the tree → resolution falls through to :rf/default, and subscribe routes against its app-db")
            (finish)
            (catch :default e
              (is false (str "subscribe-routes-default-without-frame-provider threw: " (pr-str e)))
              (finish))))))))

(deftest adapter-context-current-frame-tolerates-prop-stringified-keyword
  "the function-component-shape React-context-aware
   `current-frame` impl in `re-frame.adapter.context` rounds a
   prop-stringified keyword back to a keyword. Reagent's
   `convert-prop-value` rewrites named values (keywords / symbols) to
   strings when they pass as React props, so `[:> Provider {:value :foo}
   ...]` reaches React with `value=\"foo\"`. The function-component
   path (UIx) reads `_currentValue` directly off the shared
   context object; that read MUST tolerate the stringified shape so a
   UIx subtree embedded under a Reagent `[:> ...]` provider
   sees the right frame."
  (binding [rf.frame/*current-frame* nil]
  (let [original (.-_currentValue ^js rf.adapter.context/frame-context)]
    (try
      (set! (.-_currentValue ^js rf.adapter.context/frame-context) "tenant-prop-converted")
      (is (= :tenant-prop-converted (rf.adapter.context/function-component-current-frame))
          "stringified keyword is round-tripped back to a keyword")
      (finally
        (set! (.-_currentValue ^js rf.adapter.context/frame-context) original))))))

(deftest dispatch-default-frame-routes-via-react-context
  "the dispatch envelope's `:frame` default is built via the
   same `:adapter/current-frame` hook as subscribe, so an event
   dispatched from inside a non-default frame-provider's subtree (with
   no explicit `:frame` opt) routes to the provider's frame."
  (if-not (browser?)
    (is true ":node-test: no DOM — browser-test runner exercises the assertions")
    (async done
      (let [target-frame :tenant-d4sf-dispatch]
        (rf/make-frame {:id target-frame :doc "non-default frame for dispatch routing test"})
        (rf/reg-event :rf2-d4sf/record-here (fn [{:keys [db]} _] {:db (assoc db :stamped :here)}))
        (rf/reg-view* :rf.cross-spec-d4sf/dispatcher-probe
                      (fn dispatcher-probe-impl []
                        (rf/dispatch-sync [:rf2-d4sf/record-here])
                        [:div "probe"]))
        (let [unmounts   (atom [])
              render-fn  (rf/view :rf.cross-spec-d4sf/dispatcher-probe)
              mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)
              finish     (fn []
                           (-> (await-teardown! root)
                               (.then (fn [_]
                                        (rf.trace.tooling/unregister-listener! ::d4sf-dispatcher-probe-unmounts)
                                        (is (= 1 (count @unmounts))
                                            (str "exactly one :rf.view/unmounted fired for "
                                                 ":rf.cross-spec-d4sf/dispatcher-probe WITHIN the awaited window; got "
                                                 (count @unmounts)))
                                        (done)))))]
          (rf.trace.tooling/register-listener! ::d4sf-dispatcher-probe-unmounts
            (fn [ev]
              (when (and (= :rf.view/unmounted (:operation ev))
                         (= :rf.cross-spec-d4sf/dispatcher-probe (-> ev :tags :rf.view/id)))
                (swap! unmounts conj ev))))
          (try
            (binding [rf.frame/*current-frame* nil]
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame target-frame}
                                    [render-fn]]))))
            (is (= [:here false]
                   [(:stamped (rf/app-db-value target-frame)) (= :here (:stamped (rf/app-db-value :rf/default)))])
                "dispatch routed to the provider's frame, whose app-db carries the stamp, and did not fall through to :rf/default")
            (finish)
            (catch :default e
              (is false (str "dispatch-default-frame-routes-via-react-context threw: " (pr-str e)))
              (finish))))))))

(deftest machine-action-throws
  "#11 Machine action throws —
   when an action fn throws the cascade halts; the error is surfaced
   as :rf.error/machine-action-exception (machine-scoped, distinct
   from the generic :rf.error/handler-exception). The pre-action
   machine snapshot is preserved; no :db effect commits."
  (rf/reg-event :seed-state (fn [{:keys [db]} _] {:db {:val :before}}))
  (rf/dispatch-sync [:seed-state])
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle  {:on {:bang {:target :angry :action :boom}}}
                           :angry {}}
                 :actions {:boom (fn [_]
                                   (throw (ex-info "kaboom" {})))}}]
    (rf/reg-machine :test/m machine)
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/m [:bang]])
      (let [errs (filter #(= :rf.error/machine-action-exception (:operation %)) @traces)
            snap (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                         [:rf.runtime/machines :snapshots :test/m])]
        (is (= [[true true true] false :before true]
               [[(boolean (some #(= :test/m (get-in % [:tags :actor-id])) errs))
                 (boolean (some #(= :boom (get-in % [:tags :action-id])) errs))
                 (boolean (some #(= "kaboom" (get-in % [:tags :exception-message])) errs))]
                (boolean (some #(= :rf.error/handler-exception (:operation %)) @traces))
                (:val (rf/app-db-value :rf/default))
                (or (nil? snap) (= :idle (:state snap)))]))))))

(deftest ssr-machine-error
  "#17 Machine error inside SSR —
   composes Interaction 11 (action-throw → snapshot does not commit)
   with Interaction 16 (server projection). The trace fires under the
   request frame; the snapshot is unchanged. As with the non-SSR case
   the machine-scoped :rf.error/machine-action-exception fires, NOT
   the generic :rf.error/handler-exception."
  (rf/make-frame {:id :req :platform :server})
  (let [machine {:initial :idle
                 :data    {}
                 :states  {:idle  {:on {:bang {:target :angry :action :boom}}}
                           :angry {}}
                 :actions {:boom (fn [_]
                                   (throw (ex-info "ssr-bang" {})))}}]
    (rf/reg-machine :test/m machine)
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:test/m [:bang]] {:frame :req})
      (let [errs (filter #(= :rf.error/machine-action-exception (:operation %)) @traces)
            snap (get-in (:rf.db/runtime (rf/frame-state-value :req))
                         [:rf.runtime/machines :snapshots :test/m])]
        (is (= [[true true] false true]
               [[(boolean (some #(= :req (get-in % [:tags :frame])) errs))
                 (boolean (some #(= :test/m (get-in % [:tags :actor-id])) errs))]
                (boolean (some #(= :rf.error/handler-exception (:operation %)) @traces))
                (or (nil? snap) (= :idle (:state snap)))]))))))

(deftest hot-reload-sub-mid-cascade
  "#18 Re-registering a sub mid-cascade —
   re-registering a sub disposes the cache slot and the next subscribe
   sees the new body, even when an active subscriber was holding a
   reaction on the old version."
  (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
  (rf/reg-sub :answer (fn [db _] (:n db)))
  (rf/dispatch-sync [:seed])
  (is (= 7 (rf/subscribe-once [:answer]))
      "the v1 sub computes from app-db")
  (let [pin (rf/subscribe [:answer])]
    (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
    (is (= 700 (rf/subscribe-once [:answer]))
        "after re-registration the new sub body is in effect")
    (rf/unsubscribe pin)))
