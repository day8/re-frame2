(ns day8.re-frame2-xray.mount-cljs-test
  "Tests for Xray's DOM-side mount state machine: `open!`, `open-overlay!`,
  `close!`, `toggle!`, `popout!` and `teardown!` over the mount singletons,
  the surface transitions between them, and the silent no-op when no
  substrate adapter is installed. Node-test has no `js/document`, so a
  minimal stub is installed per test, and `rf.fresco/render!` is stubbed so
  no React tree is built; the rendered hiccup is covered by the shell tests."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco :as rf.fresco]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(defn- reset-mount-state! []
  (reset! @#'mount/mount-state nil)
  (reset! @#'mount/popout-state nil)
  ;; The host's display snapshot would otherwise match a previous test's
  ;; stale element.
  (reset! @#'mount/host-display-snapshot nil))

;; ---- js/document stub ---------------------------------------------------
;;
;; Covers only the calls `mount.cljs` makes: `createElement`, `appendChild`,
;; `removeChild`, `style.display`, `parentNode`, attributes.

(defn- mk-stub-node []
  (let [attrs (atom {})
        node (js-obj
              "style"     (js-obj "display" "")
              "id"        ""
              "tagName"   "DIV"
              "children"  (array))]
    (set! (.-parentNode node) nil)
    (set! (.-setAttribute node)
          (fn [k v]
            (swap! attrs assoc k v)
            nil))
    (set! (.-getAttribute node)
          (fn [k]
            (get @attrs k)))
    (set! (.-removeAttribute node)
          (fn [k]
            (swap! attrs dissoc k)
            nil))
    (set! (.-appendChild node)
          (fn [child]
            (.push (.-children node) child)
            (set! (.-parentNode child) node)
            child))
    (set! (.-removeChild node)
          (fn [child]
            (let [idx (.indexOf (.-children node) child)]
              (when (>= idx 0)
                (.splice (.-children node) idx 1))
              (set! (.-parentNode child) nil)
              child)))
    node))

(defn- mk-stub-document []
  (let [created (atom [])
        body    (mk-stub-node)]
    (js-obj
     "body"
     body
     "createElement"
     (fn [_tag]
       (let [n (mk-stub-node)]
         (swap! created conj n)
         n))
     "querySelector"
     (fn [selector]
       (when (= selector "[data-rf-xray-host]")
         body))
     "_created"
     created)))

(defn- can-stub-js-document?
  "True iff `set! js/document` takes effect. In a real browser
  `window.document` is a read-only accessor and the write is dropped, so the
  stub-driven rows no-op there and run on node-test."
  []
  (let [marker (js-obj "rf2-higwg-marker" true)
        prior  (when (exists? js/document) js/document)]
    (set! js/document marker)
    (let [installed? (identical? js/document marker)]
      (if prior
        (set! js/document prior)
        (when installed?
          (js-delete js/goog.global "document")))
      installed?)))

(defn- with-stub-document* [f]
  (when (can-stub-js-document?)
    (let [doc       (mk-stub-document)
          had-doc?  (exists? js/document)
          prior     (when had-doc? js/document)]
      (set! js/document doc)
      (try
        (f doc)
        (finally
          (if had-doc?
            (set! js/document prior)
            (js-delete js/goog.global "document")))))))

(defn- with-stub-document [f]
  (with-stub-document* f))

(defn- mk-render-stub
  "Stub for `rf.fresco/render!`, the seam `mount.cljs` paints through.
  Returns `{:render-fn :calls :unmount-calls}`. It writes the live-root map
  into the handle the way `render!` does, so the REAL `rf.fresco/unmount!`
  finds an `:unmount!` to call and every unmount count below means unmount
  was reached through shipped code. The handle is recorded because the
  pop-out must paint through its own root."
  []
  (let [calls         (atom [])
        unmount-calls (atom 0)
        unmount-fn    (fn unmount-stub []
                        (swap! unmount-calls inc)
                        nil)]
    {:render-fn     (fn render-stub [handle tree node]
                      (swap! calls conj {:handle handle :tree tree :node node})
                      (reset! handle {:live?    (fn [] true)
                                      :update!  (fn [_tree] nil)
                                      :unmount! unmount-fn})
                      nil)
     :calls         calls
     :unmount-calls unmount-calls
     :unmount-fn    unmount-fn}))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (registry/register-xray-handlers!)
                   (config/set-auto-open! true)
                   (reset-mount-state!))}))

;; -------------------------------------------------------------------------
;; (1) Open — first call (mount + show)
;; -------------------------------------------------------------------------

(deftest first-open!-creates-dom-node-and-renders
  (testing "first open! appends a fresh #rf-xray-root, paints the shell
            into it once and shows it inline with an explicit
            display:block, so close/open stays a CSS-only transition"
    (with-stub-document
      (fn [doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [rf.fresco/render! render-fn]
            (mount/open!)
            (is (= 1 (count @calls)))
            (let [{:keys [tree node]} (first @calls)]
              (is (vector? tree))
              (is (= "rf-xray-root" (.-id node)))
              (is (identical? node (aget (.-children (.-body doc)) 0))
                  "the painted node is appended to the host"))
            (is (= [true true] [(mount/mounted?) (mount/visible?)]))
            (let [root (:node @@#'mount/mount-state)]
              (is (= ["block" "inline"]
                     [(.-display (.-style root)) (.getAttribute root "data-rf-xray-mode")])))))))))

(deftest open!-without-layout-host-reports-actionable-diagnostic
  (with-stub-document
    (fn [doc]
      (set! (.-querySelector doc) (fn [_selector] nil))
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (let [prior-console (when (exists? js/console) js/console)]
            (set! js/console (js-obj "error" (fn [& _args] nil)))
            (try
              (let [result     (mount/open!)
                    diagnostic (:diagnostic (mount/status))]
                (is (= [:missing-layout-host :missing-layout-host "[data-rf-xray-host]"]
                       [(:reason result) (:reason diagnostic) (:selector diagnostic)]))
                (is (re-find #"data-rf-xray-host" (:snippet diagnostic)))
                (is (= [nil 0] [@@#'mount/mount-state (count @calls)])))
              (finally
                (set! js/console prior-console)))))))))

;; ---- (1b) substrate indifference -----------------------------------------
;;
;; Xray paints through its own Fresco root, so the mount verbs never consult
;; the host adapter's `:render` shape. Each row asserts a POSITIVE mount, so
;; it cannot pass on a verb that stopped doing anything.

(defn- with-warn-counter*
  "Run `f` with js/console replaced by a warn-counting stub; restores the
  prior console in a finally. `f` receives the warn-count atom."
  [f]
  (let [warns         (atom 0)
        prior-console (when (exists? js/console) js/console)]
    (set! js/console (js-obj "warn" (fn [& _args] (swap! warns inc) nil)
                             "error" (fn [& _args] nil)))
    (try
      (f warns)
      (finally
        (set! js/console prior-console)))))

(deftest open!-mounts-on-an-element-shaped-substrate
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render!                    render-fn
                      rf.substrate.adapter/current-adapter (fn [] {:kind :rf.adapter/uix})]
          (with-warn-counter*
            (fn [warns]
              (mount/open!)
              (is (= [true 1 true nil 0]
                     [(mount/mounted?) (count @calls)
                      (:ok? (:diagnostic (mount/status)))
                      (:reason (:diagnostic (mount/status))) @warns])
                  "mounted, painted once, healthy diagnostic, no warning"))))))))

(deftest open-overlay!-mounts-on-an-element-shaped-substrate
  ;; Witnessed separately because the two verbs create their node through
  ;; different paths.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render!                    render-fn
                      rf.substrate.adapter/current-adapter (fn [] {:kind :rf.adapter/uix})]
          (with-warn-counter*
            (fn [warns]
              (let [result (mount/open-overlay!)]
                (is (= [true :overlay 1 0]
                       [(mount/mounted?) (:mode result) (count @calls) @warns]))))))))))

(deftest popout!-does-not-refuse-an-element-shaped-substrate
  ;; With no `js/window` in the node lane the window step answers
  ;; `:popup-blocked`, so this literal pins that execution reached it.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render!                    render-fn
                      rf.substrate.adapter/current-adapter (fn [] {:kind :rf.adapter/fresco})]
          (with-warn-counter*
            (fn [warns]
              (is (= [:popup-blocked 0 0]
                     [(:reason (mount/popout!)) @warns (count @calls)])))))))))

(deftest second-open!-does-not-re-render
  (testing "open! on a mounted shell is a CSS-only show: no second render,
            the same node, display back to block (re-rendering would discard
            shell state and miss the spec 007 toggle budget)"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [rf.fresco/render! render-fn]
            (let [first-node (:node (mount/open!))]
              (mount/close!)
              (let [second-state (mount/open!)]
                (is (= [1 true "block" true]
                       [(count @calls) (identical? first-node (:node second-state))
                        (.-display (.-style first-node)) (:visible? second-state)]))))))))))

(deftest close!-hides-but-retains-mount-state
  (with-stub-document
    (fn [doc]
      (let [{:keys [render-fn calls unmount-calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (let [pre-close (:node @@#'mount/mount-state)]
            (mount/close!)
            (let [post-close @@#'mount/mount-state]
              (is (= [false "none" true 0 1 1]
                     [(:visible? post-close) (.-display (.-style (:node post-close)))
                      (identical? pre-close (:node post-close)) @unmount-calls
                      (count @calls) (.-length (.-children (.-body doc)))])
                  "hidden, same node still attached, no unmount, no render"))))))))

(deftest close!-collapses-layout-host-slot-and-open!-restores-it
  (testing "close! collapses the layout host's slot (so its reserved width
            and chrome leave no residue) and open! restores the host's own
            inline display verbatim"
    (with-stub-document
      (fn [doc]
        (let [host (.-body doc)
              {:keys [render-fn]} (mk-render-stub)]
          (set! (.-display (.-style host)) "flex")
          (with-redefs [rf.fresco/render! render-fn]
            (mount/open!)
            (is (= "flex" (.-display (.-style host))))
            (mount/close!)
            (is (= "none" (.-display (.-style host))))
            (mount/open!)
            (is (= "flex" (.-display (.-style host))))))))))

(deftest close!-on-clean-state-is-safe
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (is (nil? (mount/close!)))
          (is (= [false 0] [(mount/mounted?) (count @calls)])))))))

;; ---- (4b) the `✕` button: :rf.xray/close-shell ---------------------------
;;
;; The event sets the reactive `:close-requested?` flag AND fires
;; `:rf.xray.fx/hide-shell`, which calls `close!`; nothing consumes the flag,
;; so the flag alone would leave the button a no-op.

(deftest close-shell-event-hides-the-shell
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn unmount-calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (rf/with-frame :rf/xray
            (rf/dispatch-sync [:rf.xray/close-shell]))
          (is (= [false "none" true 0 true]
                 [(mount/visible?) (.-display (.-style (:node @@#'mount/mount-state)))
                  (mount/mounted?) @unmount-calls
                  (:close-requested? (rf.frame/frame-app-db-value :rf/xray))])
              "hidden through close!, never torn down, flag set in lock-step"))))))

;; The chrome `⛶` button dispatches `:rf.xray/popout-shell`, whose fx bridge
;; lowers to `mount/popout!`.

(deftest popout-shell-event-fires-popout!
  (testing "dispatching :rf.xray/popout-shell lowers through
            the :rf.xray.fx/popout-shell effect to mount/popout!, the
            same bridge shape as :rf.xray/close-shell → close!"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)
              popout-calls (atom 0)]
          (with-redefs [rf.fresco/render!           render-fn
                        mount/popout! (fn [] (swap! popout-calls inc) nil)]
            (mount/install-fx!)
            (mount/open!)
            (rf/with-frame :rf/xray
              (rf/dispatch-sync [:rf.xray/popout-shell]))
            (is (= 1 @popout-calls)
                "popout-shell event drove mount/popout! exactly once")))))))

(deftest teardown!-invokes-unmount-and-removes-node
  (with-stub-document
    (fn [doc]
      (let [{:keys [render-fn unmount-calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (let [pre-node (:node @@#'mount/mount-state)]
            (mount/teardown!)
            (is (= [1 nil 0 nil]
                   [@unmount-calls (.-parentNode pre-node)
                    (.-length (.-children (.-body doc))) @@#'mount/mount-state])
                "unmounted once, node detached, singleton cleared")))))))

(deftest teardown!-then-open!-is-a-fresh-first-mount
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (let [first-node (:node @@#'mount/mount-state)]
            (mount/teardown!)
            (mount/open!)
            (is (= 2 (count @calls)) "the second open! renders afresh")
            (is (not (identical? first-node (:node @@#'mount/mount-state))))))))))

(deftest teardown!-swallows-unmount-errors
  (with-stub-document
    (fn [doc]
      (with-redefs [rf.fresco/render!
                    (fn [handle _tree _node]
                      (reset! handle
                              {:live?    (fn [] true)
                               :update!  (fn [_tree] nil)
                               :unmount! (fn throwing-unmount []
                                           (throw (ex-info "unmount blew up"
                                                           {:reason :test})))})
                      nil)]
        (mount/open!)
        (is (nil? (mount/teardown!)))
        (is (= [0 nil] [(.-length (.-children (.-body doc))) @@#'mount/mount-state])
            "node removed and singleton cleared despite the throw")))))

(deftest open!-without-adapter-is-silent-no-op
  ;; A preload in a production bundle, or a host that never called
  ;; rf/init!: Ctrl+Shift+C is a no-op until an adapter installs.
  (with-stub-document
    (fn [doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (rf.substrate.adapter/dispose-adapter!)
          (is (= [nil nil 0 0]
                 [(mount/open!) @@#'mount/mount-state (count @calls)
                  (.-length (.-children (.-body doc)))])))))))

(deftest boot-on-runtime-ready!-honours-disabled-launch-config
  (testing "a disabled auto-open suppresses only the preload's default
            open; an explicit open! still diagnoses a missing host"
    (with-stub-document
      (fn [doc]
        (set! (.-querySelector doc) (fn [_selector] nil))
        (config/set-auto-open! false)
        (let [{:keys [render-fn calls]} (mk-render-stub)
              console-calls (atom [])]
          (with-redefs [rf.fresco/render! render-fn]
            (let [prior-console (when (exists? js/console) js/console)]
              (set! js/console (js-obj "error" (fn [& args]
                                                  (swap! console-calls conj args)
                                                  nil)))
              (try
                (mount/boot-on-runtime-ready!)
                (is (= [nil 0 0 :auto-open-disabled]
                       [@@#'mount/mount-state (count @calls) (count @console-calls)
                        (get-in (mount/status) [:diagnostic :reason])]))
                (mount/open!)
                (is (= [:missing-layout-host 1]
                       [(get-in (mount/status) [:diagnostic :reason]) (count @console-calls)]))
                (finally
                  (set! js/console prior-console)
                  (config/set-auto-open! true))))))))))

(deftest open!-recovers-after-adapter-installs-late
  ;; Some hot-reload orderings load the preload before the host's rf/init!.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (rf.substrate.adapter/dispose-adapter!)
          (is (nil? (mount/open!)))
          (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
          (mount/open!)
          (is (= [1 true] [(count @calls) (mount/visible?)])))))))

;; ---- (7) `:rf/xray` frame seating ----------------------------------------
;;
;; The frame cannot be seated at preload load time (no adapter yet), so it
;; rides adapter readiness and every open!; later toggles re-register it
;; surgically, keeping its app-db.

(deftest first-open!-seeds-trace-buffer-mirror
  ;; Events that arrived before the frame existed lift into the slot.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (trace-collector/seed-trace-for-test!
            {:id 1 :op-type :rf.event :operation :rf.test/pre-mount :tags {}})
          (trace-collector/seed-trace-for-test!
            {:id 2 :op-type :rf.event :operation :rf.test/pre-mount :tags {}})
          (mount/open!)
          (rf/with-frame :rf/xray
            (is (= [1 2] (mapv :id @(rf/subscribe [:rf.xray/trace-buffer]))))))))))

;; ---- first-mount seed frame ---------------------------------------------
;;
;; `ensure-xray-frame!` seeds `:target-frame` (and `:epoch-history` with it)
;; from the head focusable cascade's frame, the frame the panels observe.

(defn- pre-mount-dispatch-event
  "A trace event `group-by-event` buckets into a cascade on `frame-id`."
  [id dispatch-id frame-id event-id]
  {:id        id
   :op-type   :rf.event
   :operation :rf.event/dispatched
   :tags      {:rf.trace/dispatch-id dispatch-id
               :frame       frame-id
               :rf.event/v       [event-id]}})

(deftest first-open!-leaves-target-unselected-when-no-pre-mount-cascades
  ;; `:rf/default` is an ordinary id, never an absence-repair fallback.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn
                      rf/epoch-history  (fn [_] [])]
          (mount/open!)
          (rf/with-frame :rf/xray
            (is (nil? @(rf/subscribe [:rf.xray/target-frame])))))))))

(deftest first-open!-ignores-xray-internal-cascades-when-picking-seed-frame
  ;; The seed projection applies the same Xray-internal filter as
  ;; `:rf.xray/event-bundles`, so it never seeds a frame the L2 list hides.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn]} (mk-render-stub)
            cart-records [{:epoch-id :e-cart :frame :cart-frame
                           :db-before {} :db-after {:k 1}
                           :trigger-event [:cart/add] :event-id :cart/add
                           :trace-events []}]]
        (with-redefs [rf.fresco/render! render-fn
                      rf/epoch-history  (fn [frame-id]
                                          (case frame-id
                                            :cart-frame cart-records
                                            []))]
          (trace-collector/seed-trace-for-test!
            (pre-mount-dispatch-event 1 200 :cart-frame :cart/add))
          (trace-collector/seed-trace-for-test!
            (pre-mount-dispatch-event 2 201 :rf/xray :rf.xray/select-tab))
          (mount/open!)
          (rf/with-frame :rf/xray
            (is (= :cart-frame @(rf/subscribe [:rf.xray/target-frame])))))))))

(deftest open!-is-idempotent-on-xray-frame-registration
  ;; Every Ctrl+Shift+C calls open!; re-registration keeps the app-db.
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (let [first-db (rf.frame/app-db-container :rf/xray)]
            (mount/close!)
            (mount/open!)
            (is (identical? first-db (rf.frame/app-db-container :rf/xray)))))))))

;; ---- (7b) ensure-xray-frame! run-once guard ------------------------------
;;
;; `popout!` calls `ensure-xray-frame!` again for the same frame; re-running
;; the seed hook would revert a target the user picked in the L1 switcher.

(deftest ensure-xray-frame-does-not-reseed-target-frame-on-second-call-rf2-n4p5it
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn
                      rf/epoch-history  (fn [frame-id]
                                          (case frame-id
                                            :cart-frame [{:epoch-id :e-cart :frame :cart-frame
                                                          :db-before {} :db-after {:k 1}
                                                          :trigger-event [:cart/add]
                                                          :event-id :cart/add
                                                          :trace-events []}]
                                            []))]
          (trace-collector/seed-trace-for-test!
            (pre-mount-dispatch-event 1 100 :cart-frame :cart/add-item))
          (mount/open!)
          (rf/with-frame :rf/xray
            (is (= :cart-frame @(rf/subscribe [:rf.xray/target-frame]))
                "precondition: the first mount seeded from the head cascade")
            (rf/dispatch-sync [:rf.xray/set-target-frame :other-frame]))
          (mount/ensure-xray-frame!)
          (rf/with-frame :rf/xray
            (is (= :other-frame @(rf/subscribe [:rf.xray/target-frame]))
                "the user's pick survives the second call")))))))

;; ---- (8) teardown covers both mount singletons ---------------------------
;;
;; A leaked popout-state makes the next `popout!` short-circuit on stale
;; state (spec 011 §Mount lifecycle).

(defn- mk-stub-popout-window
  "A fake pop-out window: recorded `addEventListener`, `close`, a `closed`
  getter, and a document with `createElement`, `body` and `getElementById`
  over the body's children."
  []
  (let [listeners (atom {})
        closed?   (atom false)
        body      (mk-stub-node)
        doc       (js-obj "body"          body
                          "title"         ""
                          "createElement" (fn [_tag] (mk-stub-node))
                          "getElementById"
                          (fn [id]
                            (some #(when (= id (.-id %)) %)
                                  (array-seq (.-children body)))))
        win       (js-obj "document" doc)]
    (set! (.-addEventListener win)
          (fn [event-name handler]
            (swap! listeners update event-name (fnil conj []) handler)
            nil))
    (set! (.-close win)
          (fn []
            (reset! closed? true)
            nil))
    (js/Object.defineProperty
      win "closed"
      (js-obj "get" (fn [] @closed?)
              "configurable" true))
    {:window    win
     :listeners listeners
     :closed?   closed?}))

(defn- seed-popout-state!
  "Seat a synthetic popout-state, standing in for what `popout!` installs."
  [{:keys [window unmount-fn keydown-dispose]}]
  (let [node    (mk-stub-node)
        unmount (or unmount-fn (fn [] nil))
        state   (cond-> {:ok? true
                         :window  window
                         :node    node
                         :unmount unmount
                         :mode    :popout}
                  keydown-dispose (assoc :keydown-dispose keydown-dispose))]
    (reset! @#'mount/popout-state state)
    state))

(deftest teardown!-tolerates-already-closed-popout-window
  (with-stub-document
    (fn [_doc]
      (let [{:keys [window closed?]} (mk-stub-popout-window)
            unmount-calls (atom 0)]
        (reset! closed? true)
        (seed-popout-state! {:window     window
                             :unmount-fn (fn [] (swap! unmount-calls inc) nil)})
        (is (nil? (mount/teardown!)))
        (is (= [nil 1] [@@#'mount/popout-state @unmount-calls]))))))

(deftest teardown!-swallows-popout-unmount-errors
  (with-stub-document
    (fn [_doc]
      (let [{:keys [window closed?]} (mk-stub-popout-window)]
        (seed-popout-state!
          {:window     window
           :unmount-fn (fn [] (throw (ex-info "popout unmount blew up" {:reason :test})))})
        (is (nil? (mount/teardown!)))
        (is (= [nil true] [@@#'mount/popout-state @closed?])
            "singleton cleared and window closed despite the throw")))))

;; ---- pop-out keydown listener lifecycle ----------------------------------
;;
;; Key events do not cross realms, so the pop-out has its own listener, and
;; `teardown-popout-state!` disposes it on every exit. The routing half is in
;; keybinding_cljs_test.cljs.

(deftest teardown!-swallows-a-throwing-keydown-disposer
  (with-stub-document
    (fn [_doc]
      (let [{:keys [window closed?]} (mk-stub-popout-window)
            unmount-calls (atom 0)]
        (seed-popout-state!
          {:window          window
           :unmount-fn      (fn [] (swap! unmount-calls inc) nil)
           :keydown-dispose (fn [] (throw (ex-info "disposer blew up" {:reason :test})))})
        (is (nil? (mount/teardown!)))
        (is (= [nil 1 true] [@@#'mount/popout-state @unmount-calls @closed?])
            "singleton cleared, unmount ran and window closed despite the throw")))))

(deftest teardown!-clears-both-singletons-in-one-call
  (with-stub-document
    (fn [_doc]
      (let [{:keys [render-fn unmount-calls]} (mk-render-stub)
            {:keys [window closed?]}          (mk-stub-popout-window)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (seed-popout-state! {:window     window
                               :unmount-fn (fn [] (swap! unmount-calls inc) nil)})
          (mount/teardown!)
          (is (= [nil nil 2 true]
                 [@@#'mount/mount-state @@#'mount/popout-state @unmount-calls @closed?])
              "both singletons cleared, both unmounts ran, the pop-out closed"))))))

;; ---- (9) pop-out external close -----------------------------------------
;;
;; Closing the pop-out window must clear the opener-side singleton through
;; the pagehide/unload listener `popout!` registered, or the next `popout!`
;; short-circuits on a closed window.

(defn- register-popout-cleanup! [win]
  ((deref #'mount/register-popout-unload-cleanup!) win))

(deftest popout-external-close-clears-state-unmounts-and-disposes
  ;; pagehide, and unload for older browsers, both route through the same
  ;; cleanup as teardown!.
  (with-stub-document
    (fn [_doc]
      (doseq [event-type ["pagehide" "unload"]]
        (let [{:keys [window listeners]} (mk-stub-popout-window)
              unmount-calls (atom 0)
              disposals     (atom 0)]
          (seed-popout-state! {:window          window
                               :unmount-fn      (fn [] (swap! unmount-calls inc) nil)
                               :keydown-dispose (fn [] (swap! disposals inc) nil)})
          (register-popout-cleanup! window)
          ((first (get @listeners event-type)) (js-obj "type" event-type))
          (is (= [nil 1 1] [@@#'mount/popout-state @unmount-calls @disposals])
              event-type))))))

(deftest popout-stale-unload-handler-does-not-nuke-fresh-state
  ;; The handler matches its window by identity against the :window slot.
  (with-stub-document
    (fn [_doc]
      (let [{window-a :window listeners-a :listeners} (mk-stub-popout-window)
            {window-b :window}                        (mk-stub-popout-window)]
        (seed-popout-state! {:window window-a})
        (register-popout-cleanup! window-a)
        (reset! @#'mount/popout-state nil)
        (seed-popout-state! {:window window-b})
        ((first (get @listeners-a "unload")) (js-obj "type" "unload"))
        (is (identical? window-b (:window @@#'mount/popout-state)))))))

;; ---- (10) pop-out opener-gone overlay ------------------------------------
;;
;; When the opener closes, the orphaned pop-out shows a plain-DOM 'opener
;; gone' overlay (spec 011 §Pop-out §Constraints); a 500 ms watchdog polls
;; `window.opener.closed` and self-clears after firing.

(defn- mk-stub-opener-window
  "A fake opener window whose `closed` getter reads an atom."
  []
  (let [closed? (atom false)
        win     (js-obj)]
    (js/Object.defineProperty
      win "closed"
      (js-obj "get" (fn [] @closed?)
              "configurable" true))
    {:window  win
     :closed? closed?}))

(defn- mk-stub-popout-window-with-opener
  "A fake pop-out window whose `opener` is `opener-win`."
  [opener-win]
  (let [{:keys [window listeners closed?]} (mk-stub-popout-window)]
    (js/Object.defineProperty
      window "opener"
      (js-obj "get" (fn [] opener-win)
              "configurable" true))
    {:window    window
     :opener    opener-win
     :listeners listeners
     :closed?   closed?}))

(defn- mk-stub-opener-window-with-listeners
  "An opener stub that records its event listeners."
  []
  (let [listeners (atom {})
        {:keys [window closed?]} (mk-stub-opener-window)]
    (set! (.-addEventListener window)
          (fn [event-name handler]
            (swap! listeners update event-name (fnil conj []) handler)
            nil))
    (set! (.-removeEventListener window)
          (fn [event-name handler]
            (swap! listeners update event-name
                   (fn [hs] (vec (remove #(identical? % handler) hs))))
            nil))
    {:window window :listeners listeners :closed? closed?}))

(defn- opener-gone?* [win] ((deref #'mount/opener-gone?) win))
(defn- install-opener-gone-overlay!* [doc] ((deref #'mount/install-opener-gone-overlay!) doc))
(defn- start-opener-gone-watchdog!* [win overlay-node]
  ((deref #'mount/start-opener-gone-watchdog!) win overlay-node))
(defn- register-opener-reload-announcer!* [opener-win win overlay-node]
  ((deref #'mount/register-opener-reload-announcer!) opener-win win overlay-node))

(deftest opener-gone?-is-false-only-for-a-live-opener
  ;; A closed opener, a nil opener slot and an opener read that throws all
  ;; read as gone.
  (let [popout-of        (fn [opener]
                           (:window (mk-stub-popout-window-with-opener opener)))
        {live :window}   (mk-stub-opener-window)
        {closed :window closed-flag :closed?} (mk-stub-opener-window)
        throwing-popout  (js-obj)]
    (reset! closed-flag true)
    (js/Object.defineProperty
      throwing-popout "opener"
      (js-obj "get" (fn [] (throw (ex-info "cross-origin block" {:reason :test})))
              "configurable" true))
    (doseq [[label popout gone?] [["live opener"          (popout-of live)   false]
                                  ["closed opener"        (popout-of closed) true]
                                  ["nil opener"           (popout-of nil)    true]
                                  ["throwing opener read" throwing-popout    true]]]
      (is (= gone? (opener-gone?* popout)) label))))

(deftest install-opener-gone-overlay!-creates-a-hidden-node-with-the-spec-ids
  (let [{popout :window} (mk-stub-popout-window-with-opener nil)
        overlay          (install-opener-gone-overlay!* (.-document popout))]
    (is (= ["rf-xray-popout-opener-gone-overlay" "rf-xray-popout-opener-gone-overlay"
            "popout-opener-gone" "none"]
           [(.-id overlay) (.getAttribute overlay "data-testid")
            (.getAttribute overlay "data-rf-xray-mode") (.-display (.-style overlay))])
        "the spec'd id, testid and mode attribute, hidden until the watchdog fires")))

(deftest start-opener-gone-watchdog!-self-clears-when-popout-state-replaced
  (testing "a watchdog whose pop-out is no longer the registered :window
            clears itself on the next tick instead of revealing the overlay
            against a stale window"
    (let [{opener :window} (mk-stub-opener-window)
          {popout :window} (mk-stub-popout-window-with-opener opener)
          doc              (.-document popout)
          overlay          (install-opener-gone-overlay!* doc)
          intervals        (atom {})
          next-id          (atom 0)
          cleared          (atom #{})
          prior-set        (.-setInterval js/globalThis)
          prior-clear      (.-clearInterval js/globalThis)]
      (reset! @#'mount/popout-state nil)
      (set! (.-setInterval js/globalThis)
            (fn [f _ms]
              (let [id (swap! next-id inc)]
                (swap! intervals assoc id f)
                id)))
      (set! (.-clearInterval js/globalThis)
            (fn [id]
              (swap! cleared conj id)
              (swap! intervals dissoc id)
              nil))
      (try
        (let [wid (start-opener-gone-watchdog!* popout overlay)]
          (when-let [f (get @intervals wid)]
            (f))
          (is (contains? @cleared wid)
              "watchdog self-cleared because popout-state did not reference its window")
          (is (= "none" (.-display (.-style overlay)))
              "overlay untouched — the guard fired before the opener check"))
        (finally
          (set! (.-setInterval js/globalThis) prior-set)
          (set! (.-clearInterval js/globalThis) prior-clear))))))

;; ---- the opener-reload announcer -----------------------------------------
;;
;; A same-origin reload leaves `window.opener` live with `.closed` false, and
;; destroys the watchdog's timer with the opener's realm, so the opener
;; announces at `pagehide` instead. The wiring through `popout!` is pinned in
;; section (d); these rows pin its identity guard and its teardown.

(deftest opener-reload-announcer-guards-on-popout-window-identity
  (testing "a stale announcer whose popout window is no longer the
            registered :window slot must not paint over the popout a fresh
            popout! has since installed — mirrors the watchdog's guard"
    (let [{opener :window} (mk-stub-opener-window-with-listeners)
          {popout :window} (mk-stub-popout-window-with-opener opener)
          doc              (.-document popout)
          overlay          (install-opener-gone-overlay!* doc)]
      ;; popout-state references a DIFFERENT window than the announcer's.
      (seed-popout-state! {:window (:window (mk-stub-popout-window-with-opener opener))})
      (try
        (let [handler (register-opener-reload-announcer!* opener popout overlay)]
          (handler (js-obj "persisted" false))
          (is (= "none" (.-display (.-style overlay)))
              "the identity guard suppressed the reveal on a superseded popout"))
        (finally
          (reset! @#'mount/popout-state nil))))))

(deftest teardown-popout-state!-detaches-the-opener-announcer
  ;; Otherwise repeated pop-out cycles in one opener accumulate handlers.
  (let [{opener :window listeners :listeners} (mk-stub-opener-window-with-listeners)
        {popout :window} (mk-stub-popout-window-with-opener opener)
        overlay          (install-opener-gone-overlay!* (.-document popout))]
    (seed-popout-state! {:window popout})
    (let [handler (register-opener-reload-announcer!* opener popout overlay)]
      (is (= [handler] (get @listeners "pagehide")) "registered before teardown")
      (swap! @#'mount/popout-state assoc
             :opener-window opener
             :opener-pagehide-handler handler)
      ((deref #'mount/teardown-popout-state!))
      (is (empty? (get @listeners "pagehide"))))))

(deftest teardown-popout-state!-clears-watchdog-interval
  (with-stub-document
    (fn [_doc]
      (let [{:keys [window]} (mk-stub-popout-window)
            cleared          (atom #{})
            prior-clear      (.-clearInterval js/globalThis)]
        (set! (.-clearInterval js/globalThis) (fn [id] (swap! cleared conj id) nil))
        (try
          (reset! @#'mount/popout-state
                  {:ok?         true
                   :window      window
                   :node        (mk-stub-node)
                   :unmount     (fn [] nil)
                   :mode        :popout
                   :watchdog-id 12345})
          (mount/teardown!)
          (is (contains? @cleared 12345))
          (finally
            (set! (.-clearInterval js/globalThis) prior-clear)))))))

;; ---- (11) pop-out stylesheet hand-off ------------------------------------
;;
;; The pop-out document must carry Xray's stylesheets and the persisted theme
;; class, or the shell renders unstyled (spec 011 §Pop-out §Styling).

(defn- mk-stub-classlist []
  (let [classes (atom #{})]
    (js-obj "add"      (fn [c] (swap! classes conj c) nil)
            "remove"   (fn [c] (swap! classes disj c) nil)
            "contains" (fn [c] (contains? @classes c))
            "_classes" classes)))

(defn- mk-stub-popout-doc-with-head
  "A pop-out document stub with a `<head>` and an `<html>` classList."
  []
  (let [by-id (atom {})
        head  (js-obj "tagName" "HEAD")
        html  (js-obj "tagName" "HTML")
        clist (mk-stub-classlist)]
    (set! (.-appendChild head)
          (fn [node] (swap! by-id assoc (.-id node) node) node))
    (set! (.-classList html) clist)
    (let [style-node (fn []
                       (let [n (js-obj "id" "" "tagName" "STYLE")]
                         (set! (.-appendChild n) (fn [_child] _child))
                         n))]
      {:doc   (js-obj "head"            head
                      "documentElement" html
                      "createElement"   (fn [_tag] (style-node))
                      "createTextNode"  (fn [css] (js-obj "text" css))
                      "getElementById"  (fn [id] (get @by-id id)))
       :by-id by-id
       :html  html
       :classlist clist})))

(defn- style-popout-document!* [doc]
  ((deref #'mount/style-popout-document!) doc))

(deftest style-popout-document!-injects-stylesheet-and-theme-class
  (let [{:keys [doc by-id classlist]} (mk-stub-popout-doc-with-head)]
    (config/update-setting! :theme nil :dark)
    (try
      (style-popout-document!* doc)
      (is (= [true true true]
             [(some? (get @by-id "rf-xray-themes")) (some? (get @by-id "rf-xray-fonts"))
              ((.-contains classlist) "rf-xray-theme-dark")])
          "theme and font styles injected, persisted :dark theme stamped")
      (finally
        (config/update-setting! :theme nil :light)))))

;; ---- (12) surface transitions: inline <-> overlay ------------------------
;;
;; Inline mounts into the app's `[data-rf-xray-host]`; overlay mounts a fixed
;; modal under `document.body`. A mode change must re-parent AND re-render,
;; or the stored mode would report a surface nothing moved to. These rows
;; use a document whose host is distinct from `body`, with a working
;; `getElementById`, so ownership and the one-root guarantee are observable.

(defn- deep-find-by-id [node id]
  (when (some? node)
    (if (= id (.-id node))
      node
      (let [children (.-children node)]
        (when (some? children)
          (loop [i 0]
            (when (< i (.-length children))
              (or (deep-find-by-id (aget children i) id)
                  (recur (inc i))))))))))

(defn- deep-count-by-id [node id]
  (if (some? node)
    (let [self     (if (= id (.-id node)) 1 0)
          children (.-children node)]
      (+ self
         (if (some? children)
           (loop [i 0 acc 0]
             (if (< i (.-length children))
               (recur (inc i) (+ acc (deep-count-by-id (aget children i) id)))
               acc))
           0)))
    0))

(defn- mk-two-owner-document
  "Stub document whose layout host is a node distinct from `body`.
  Returns `{:doc :body :host}`."
  []
  (let [body (mk-stub-node)
        host (mk-stub-node)]
    (.setAttribute host "data-rf-xray-host" "")
    (.appendChild body host)
    {:doc  (js-obj
             "body"           body
             "createElement"  (fn [_tag] (mk-stub-node))
             "querySelector"  (fn [selector]
                                (when (= selector "[data-rf-xray-host]")
                                  host))
             "getElementById" (fn [id] (deep-find-by-id body id)))
     :body body
     :host host}))

(defn- with-two-owner-document [f]
  (when (can-stub-js-document?)
    (let [{:keys [doc body host]} (mk-two-owner-document)
          had-doc? (exists? js/document)
          prior    (when had-doc? js/document)]
      (set! js/document doc)
      (try
        (f {:doc doc :body body :host host})
        (finally
          (if had-doc?
            (set! js/document prior)
            (js-delete js/goog.global "document")))))))

(defn- shell-props-in
  "The props map the `shell/ShellView` boundary head carries in a recorded
  mount tree, found by WALKING FOR THE HEAD rather than indexing to it, so
  a re-shaped tree answers nil instead of a plausible wrong value."
  [tree]
  (letfn [(walk [node]
            (when (vector? node)
              (if (identical? (first node) shell/ShellView)
                (second node)
                (some walk (rest node)))))]
    (walk tree)))

(defn- shell-view-mode-of
  "The `:mode` prop the shell boundary was rendered with in a recorded call."
  [call]
  (:mode (shell-props-in (:tree call))))

(deftest open-overlay!-from-inline-reparents-to-body-and-rerenders-fixed
  (with-two-owner-document
    (fn [{:keys [body host]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (let [inline-node (:node @@#'mount/mount-state)]
            (is (identical? host (.-parentNode inline-node))
                "precondition: the inline root is owned by the layout host")
            (mount/open-overlay!)
            (let [overlay-node (:node @@#'mount/mount-state)]
              (is (= [2 :overlay :overlay true nil "overlay" 1 true]
                     [(count @calls) (shell-view-mode-of (second @calls))
                      (:mode (mount/status)) (identical? body (.-parentNode overlay-node))
                      (.-parentNode inline-node) (.getAttribute overlay-node "data-rf-xray-mode")
                      (deep-count-by-id body "rf-xray-root") (mount/visible?)])
                  "re-rendered as :overlay, re-parented to body, the inline node
                   evicted, exactly one root, visible"))))))))

(deftest open!-from-overlay-reparents-to-host-and-rerenders-inline
  (with-two-owner-document
    (fn [{:keys [body host]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open-overlay!)
          (let [overlay-node (:node @@#'mount/mount-state)]
            (is (identical? body (.-parentNode overlay-node))
                "precondition: the overlay root is owned by document.body")
            (mount/open!)
            (let [inline-node (:node @@#'mount/mount-state)]
              (is (= [2 :inline :inline true nil "inline" 1 true]
                     [(count @calls) (shell-view-mode-of (second @calls))
                      (:mode (mount/status)) (identical? host (.-parentNode inline-node))
                      (.-parentNode overlay-node) (.getAttribute inline-node "data-rf-xray-mode")
                      (deep-count-by-id body "rf-xray-root") (mount/visible?)])))))))))

(deftest repeated-open-overlay!-same-mode-is-idempotent
  (with-two-owner-document
    (fn [{:keys [body]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open-overlay!)
          (let [first-node (:node @@#'mount/mount-state)]
            (mount/open-overlay!)
            (mount/open-overlay!)
            (is (= [1 true 1 :overlay]
                   [(count @calls) (identical? first-node (:node @@#'mount/mount-state))
                    (deep-count-by-id body "rf-xray-root") (:mode (mount/status))]))))))))

(deftest settings-panel-position-realizes-surface-through-the-bridge
  ;; Driven through the exported browser API `install.cljs` wires, pointing
  ;; at the real mount fns, so the late-bind path is the production one.
  (with-two-owner-document
    (fn [{:keys [body host]}]
      (let [{:keys [render-fn]} (mk-render-stub)
            prior-window        (when (exists? js/window) js/window)
            surface             (fn []
                                  [(:mode (mount/status))
                                   (.-parentNode (:node @@#'mount/mount-state))
                                   (deep-count-by-id body "rf-xray-root")])]
        (with-redefs [rf.fresco/render! render-fn]
          (set! js/window
                (js-obj "day8"
                        (js-obj "re_frame2_xray"
                                (js-obj "open_BANG_"         mount/open!
                                        "open_overlay_BANG_" mount/open-overlay!
                                        "status"             mount/status))))
          (try
            (settings-effects/apply-panel-position! :right-rail)
            (is (= [:inline host 1] (surface)))
            (settings-effects/apply-panel-position! :fullscreen)
            (is (= [:overlay body 1] (surface)))
            (settings-effects/apply-panel-position! :right-rail)
            (is (= [:inline host 1] (surface)))
            (finally
              (if prior-window
                (set! js/window prior-window)
                (js-delete js/goog.global "window")))))))))

(deftest close-then-reopen-after-switch-to-inline-is-coherent
  (with-two-owner-document
    (fn [{:keys [host body]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open-overlay!)
          (mount/open!)
          (let [inline-node (:node @@#'mount/mount-state)]
            (mount/close!)
            (is (= [false :inline] [(mount/visible?) (:mode (mount/status))])
                "close! retains the realized inline mode")
            (mount/open!)
            (is (= [true 2 true true 1]
                   [(mount/visible?) (count @calls)
                    (identical? inline-node (:node @@#'mount/mount-state))
                    (identical? host (.-parentNode inline-node))
                    (deep-count-by-id body "rf-xray-root")])
                "a CSS-only re-show of the same host-owned node")))))))

;; ---- (13) global reopen keeps the realized surface ----------------------
;;
;; `toggle!` (Ctrl+Shift+C) and the palette's hidden-shell show (Cmd/Ctrl+K)
;; reopen whatever surface the shell was last realized on. Routing a hidden
;; overlay through `open!` would re-parent it inline with a host, or strand it
;; hidden behind the missing-host diagnostic without one. The rows drive both
;; `toggle!` and the real `keybinding/handle-keydown`.

(defn- mk-keydown-event
  "Synthetic KeyboardEvent for `keybinding/handle-keydown`."
  [opts]
  (let [{:keys [key code ctrl? shift? meta? alt?]
         :or   {ctrl? false shift? false meta? false alt? false}} opts]
    (js-obj "key"             key
            "code"            code
            "ctrlKey"         ctrl?
            "shiftKey"        shift?
            "metaKey"         meta?
            "altKey"          alt?
            "preventDefault"  (fn [] nil)
            "stopPropagation" (fn [] nil))))

(deftest global-toggle-reopens-hidden-overlay-as-overlay-no-host-rf2-j538f7-41
  (with-stub-document
    (fn [doc]
      (set! (.-querySelector doc) (fn [_selector] nil))
      (let [{:keys [render-fn calls]} (mk-render-stub)
            prior-console (when (exists? js/console) js/console)]
        (set! js/console (js-obj "error" (fn [& _args] nil)))
        (with-redefs [rf.fresco/render! render-fn]
          (try
            (mount/open-overlay!)
            (let [overlay-node (:node @@#'mount/mount-state)]
              (mount/toggle!)
              (is (= [false :overlay "none"]
                     [(mount/visible?) (:mode (mount/status)) (.-display (.-style overlay-node))])
                  "toggle! hides the overlay and it keeps its mode")
              (mount/toggle!)
              (is (= [true :overlay true "block" 1]
                     [(mount/visible?) (:mode (mount/status))
                      (identical? overlay-node (:node @@#'mount/mount-state))
                      (.-display (.-style overlay-node)) (count @calls)])
                  "toggle! re-shows the SAME overlay, CSS-only")
              (is (not= :missing-layout-host (get-in (mount/status) [:diagnostic :reason]))
                  "no inline-host lookup was attempted"))
            (finally
              (set! js/console prior-console))))))))

(deftest global-toggle-reopens-hidden-overlay-as-overlay-host-present-rf2-j538f7-41
  (with-two-owner-document
    (fn [{:keys [body]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (mount/open-overlay!)
          (let [overlay-node (:node @@#'mount/mount-state)]
            (mount/close!)
            (mount/toggle!)
            (is (= [true :overlay 2 true true "overlay" 1]
                   [(mount/visible?) (:mode (mount/status)) (count @calls)
                    (identical? overlay-node (:node @@#'mount/mount-state))
                    (identical? body (.-parentNode overlay-node))
                    (.getAttribute overlay-node "data-rf-xray-mode")
                    (deep-count-by-id body "rf-xray-root")])
                "the OVERLAY reopens CSS-only, not re-parented to the host")))))))

(deftest ctrl-shift-c-handler-reshows-hidden-overlay-rf2-j538f7-41
  (with-stub-document
    (fn [doc]
      (set! (.-querySelector doc) (fn [_selector] nil))
      (let [{:keys [render-fn calls]} (mk-render-stub)
            prior-console (when (exists? js/console) js/console)]
        (set! js/console (js-obj "error" (fn [& _args] nil)))
        (with-redefs [rf.fresco/render! render-fn]
          (try
            (mount/open-overlay!)
            (let [overlay-node (:node @@#'mount/mount-state)
                  evt (mk-keydown-event {:key "C" :code "KeyC" :ctrl? true :shift? true})]
              (#'keybinding/handle-keydown evt)
              (is (false? (mount/visible?)) "the first press hides")
              (#'keybinding/handle-keydown evt)
              (is (= [true :overlay true 1]
                     [(mount/visible?) (:mode (mount/status))
                      (identical? overlay-node (:node @@#'mount/mount-state)) (count @calls)])
                  "the second press re-shows the same overlay, CSS-only"))
            (finally
              (set! js/console prior-console))))))))

(deftest cmd-k-handler-shows-hidden-overlay-before-palette-rf2-j538f7-41
  ;; The handler's `(when-not visible? (toggle!))` runs before it enqueues
  ;; the palette toggle, so the overlay is visible when it returns.
  (with-stub-document
    (fn [doc]
      (set! (.-querySelector doc) (fn [_selector] nil))
      (let [{:keys [render-fn calls]} (mk-render-stub)
            prior-console (when (exists? js/console) js/console)]
        (set! js/console (js-obj "error" (fn [& _args] nil)))
        (with-redefs [rf.fresco/render! render-fn]
          (try
            (mount/open-overlay!)
            (mount/close!)
            (let [overlay-node (:node @@#'mount/mount-state)]
              (#'keybinding/handle-keydown (mk-keydown-event {:key "k" :code "KeyK" :ctrl? true}))
              (is (= [true :overlay true 1]
                     [(mount/visible?) (:mode (mount/status))
                      (identical? overlay-node (:node @@#'mount/mount-state)) (count @calls)])))
            (finally
              (set! js/console prior-console))))))))

(deftest first-ever-toggle-defaults-to-inline-rf2-j538f7-41
  (with-two-owner-document
    (fn [{:keys [host]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/toggle!)
          (is (= [1 :inline true]
                 [(count @calls) (:mode (mount/status))
                  (identical? host (.-parentNode (:node @@#'mount/mount-state)))])))))))

(deftest global-toggle-reopens-hidden-inline-as-inline-rf2-j538f7-41
  (with-two-owner-document
    (fn [{:keys [host body]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!)
          (let [inline-node (:node @@#'mount/mount-state)]
            (mount/toggle!)
            (is (false? (mount/visible?)))
            (mount/toggle!)
            (is (= [true :inline true true 1 1]
                   [(mount/visible?) (:mode (mount/status))
                    (identical? inline-node (:node @@#'mount/mount-state))
                    (identical? host (.-parentNode inline-node))
                    (count @calls) (deep-count-by-id body "rf-xray-root")]))))))))

;; ---- evidence integrity --------------------------------------------------
;;
;; Xray's own activity must never appear in the INSPECTED application's epoch
;; record. Structurally, the tree handed to `render!` is rooted at the shell's
;; own frame-provider, so the boundary's reads never fall through to the host
;; frame. On the event axis, a real Xray chrome event leaves the app frame's
;; epoch ring unchanged in count and contents, beside a control that shows
;; the ring is live. (A Fresco boundary emits no view-render trace, so a
;; render-emit assertion would be about an emit nobody makes.)

(defn- assert-rooted-at-shell-frame-provider! [tree]
  (is (= [true {:frame shell/default-frame-id} true]
         [(identical? rf.fresco/frame-provider (first tree))
          (second tree)
          (identical? shell/ShellView (first (nth tree 2)))])
      "the tree is ROOTED at the provider naming `shell/default-frame-id`, with
       the shell boundary directly inside it"))

(defn- assert-xray-event-leaves-app-ring-alone! [app]
  (rf/dispatch-sync [:app/inc] {:frame app})
  (let [before (vec (rf/epoch-history app))]
    (is (seq before) "control: the ring under inspection is not empty")
    (rf/dispatch-sync [:rf.xray/select-tab :trace] {:frame shell/default-frame-id})
    (is (= before (vec (rf/epoch-history app)))
        "an Xray chrome event leaves the app's epoch ring byte-identical")
    (rf/dispatch-sync [:app/inc] {:frame app})
    (is (not= before (vec (rf/epoch-history app)))
        "control: an application event does move the same ring")))

(deftest xray-shell-render-never-lands-in-inspected-app-epoch
  (with-stub-document
    (fn [_doc]
      (let [app :test/inspected-app
            {:keys [render-fn calls]} (mk-render-stub)]
        ;; The frame-no-emit set is process-sticky.
        (rf.trace/clear-frame-no-emit!)
        (rf/make-frame {:id app})
        (rf/reg-event :app/inc (fn [{:keys [db]} _]
                                 {:db (update db :n (fnil inc 0))}))
        (with-redefs [rf.fresco/render! render-fn]
          (mount/open!))
        (is (= [true false]
               [(rf.trace/frame-trace-disabled? shell/default-frame-id)
                (boolean (rf.trace/frame-trace-disabled? app))])
            "the shell frame is trace-disabled and the inspected app's is not")
        (assert-rooted-at-shell-frame-provider! (:tree (first @calls)))
        (assert-xray-event-leaves-app-ring-alone! app)))))

;; ---- the pop-out, driven through `popout!` itself -------------------------
;;
;; The rows above seed a hand-built popout-state; these execute `popout!`'s
;; own body: the window it opens, the document and React root it paints
;; through, its own copy of the frame-provider wrap, and the watchdog and
;; announcer it wires. `js/window` and the interval timers are stubbed, so
;; the watchdog is captured and drivable; everything between is shipping
;; code.

(defn- with-driven-popout
  "Run `f` in a host where the REAL `popout!` executes to completion, with
  `{:opener :popout :opener-doc :opener-listeners :opener-closed? :opens
  :intervals :cleared}`. Tears down through the shipped
  `teardown-popout-state!` so no stub outlives the row."
  [f]
  (when (can-stub-js-document?)
    (let [{opener :window opener-listeners :listeners opener-closed? :closed?}
          (mk-stub-opener-window-with-listeners)
          {popout :window} (mk-stub-popout-window-with-opener opener)
          opener-doc  (mk-stub-document)
          opens       (atom [])
          intervals   (atom {})
          next-id     (atom 0)
          cleared     (atom #{})
          prior-set   (.-setInterval js/globalThis)
          prior-clear (.-clearInterval js/globalThis)
          had-win?    (exists? js/window)
          prior-win   (when had-win? js/window)
          had-doc?    (exists? js/document)
          prior-doc   (when had-doc? js/document)]
      (set! (.-open opener)
            (fn stub-window-open [url target features]
              (swap! opens conj {:url url :target target :features features})
              popout))
      (set! (.-setInterval js/globalThis)
            (fn [tick _ms]
              (let [id (swap! next-id inc)]
                (swap! intervals assoc id tick)
                id)))
      (set! (.-clearInterval js/globalThis)
            (fn [id]
              (swap! cleared conj id)
              (swap! intervals dissoc id)
              nil))
      (set! js/document opener-doc)
      (set! js/window opener)
      (try
        (f {:opener           opener
            :popout           popout
            :opener-doc       opener-doc
            :opener-listeners opener-listeners
            :opener-closed?   opener-closed?
            :opens            opens
            :intervals        intervals
            :cleared          cleared})
        (finally
          ((deref #'mount/teardown-popout-state!))
          (set! (.-setInterval js/globalThis) prior-set)
          (set! (.-clearInterval js/globalThis) prior-clear)
          (if had-win?
            (set! js/window prior-win)
            (js-delete js/goog.global "window"))
          (if had-doc?
            (set! js/document prior-doc)
            (js-delete js/goog.global "document")))))))

;; The host adapter is element-shaped on purpose: `popout!` is indifferent
;; to it. A helper rather than `with-redefs-fn`, which cljs.core lacks.

(defn- with-popout-seams
  "Run `thunk` with the Fresco root door and the host adapter redefined."
  [render-fn thunk]
  (with-redefs [rf.fresco/render!                    render-fn
                rf.substrate.adapter/current-adapter (fn [] {:kind :rf.adapter/fresco})]
    (thunk)))

;; ---- (a) a separate WINDOW -----------------------------------------------

(deftest popout!-paints-into-the-second-window-not-the-opener
  ;; Never `pr-str` the state map in a message: its live DOM handles form a
  ;; parentNode <-> children cycle that overflows the printer.
  (with-driven-popout
    (fn [{:keys [popout opener-doc opens]}]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-popout-seams render-fn
          (fn []
            (let [state (mount/popout!)
                  {:keys [target features]} (first @opens)
                  node  (:node (first @calls))]
              (is (= [true 1 "rf-xray-popout" true "Xray" 1]
                     [(:ok? state) (count @opens) target (identical? popout (:window state))
                      (.-title (.-document popout)) (count @calls)])
                  "one same-origin named window, titled, painted once")
              (is (nil? (re-find #"noopener|noreferrer" features))
                  "the posture depends on a live `window.opener` (spec 011)")
              (is (= [true "rf-xray-popout-root" "popout" true 0]
                     [(identical? node (:node state)) (.-id node)
                      (.getAttribute node "data-rf-xray-mode")
                      (identical? (.-body (.-document popout)) (.-parentNode node))
                      (.-length (.-children (.-body opener-doc)))])
                  "the node lives in the POP-OUT's body; the opener's gets nothing"))))))))

;; ---- (b) a separate DOM ROOT ---------------------------------------------

(deftest popout!-paints-through-its-own-root-handle-leaving-the-inline-shell-alone
  ;; `render!` binds a handle to its mount-point on first use, so a pop-out
  ;; sharing `xray-root` would re-render the INLINE shell and leave the
  ;; second window blank, silently. Handle identity is the only witness.
  (with-driven-popout
    (fn [_ctx]
      (let [{:keys [render-fn calls]} (mk-render-stub)]
        (with-popout-seams render-fn
          (fn []
            (mount/open!)
            (let [inline-node (:node @@#'mount/mount-state)]
              (is (true? (:ok? (mount/popout!))))
              (let [[inline-call popout-call] @calls]
                (is (= [2 true true true true]
                       [(count @calls)
                        (identical? @#'mount/xray-root (:handle inline-call))
                        (identical? @#'mount/xray-popout-root (:handle popout-call))
                        (identical? inline-node (:node @@#'mount/mount-state))
                        (mount/visible?)])
                    "one more paint, through its own handle; the inline shell
                     keeps its node and stays visible")))))))))

;; ---- (c) evidence integrity on the pop-out path --------------------------

(deftest popout-shell-render-never-lands-in-inspected-app-epoch
  ;; `popout!` carries its OWN copy of the frame-provider wrap, so it needs
  ;; its own row.
  (with-driven-popout
    (fn [_ctx]
      (let [app :test/inspected-app-popout
            {:keys [render-fn calls]} (mk-render-stub)]
        (rf.trace/clear-frame-no-emit!)
        (rf/make-frame {:id app})
        (rf/reg-event :app/inc (fn [{:keys [db]} _]
                                 {:db (update db :n (fnil inc 0))}))
        (with-popout-seams render-fn
          (fn [] (mount/popout!)))
        (is (= [1 :popout] [(count @calls) (shell-view-mode-of (first @calls))])
            "this is the pop-out's own tree")
        (assert-rooted-at-shell-frame-provider! (:tree (first @calls)))
        (assert-xray-event-leaves-app-ring-alone! app)))))

;; ---- (d) the watchdog and the announcer, as `popout!` wires them ---------
;;
;; Each is driven to its EFFECT on the overlay `popout!` created; the guards
;; a wired row cannot reach are in section (10).

(deftest popout!-wires-the-opener-gone-watchdog-to-its-own-overlay
  (with-driven-popout
    (fn [{:keys [popout opener-closed? intervals cleared]}]
      (let [{:keys [render-fn]} (mk-render-stub)]
        (with-popout-seams render-fn
          (fn []
            (let [state   (mount/popout!)
                  overlay (:overlay-node state)
                  wid     (:watchdog-id state)
                  tick    (get @intervals wid)]
              (is (= ["rf-xray-popout-opener-gone-overlay" true "none" true]
                     [(.-id overlay)
                      (identical? (.-body (.-document popout)) (.-parentNode overlay))
                      (.-display (.-style overlay)) (some? tick)])
                  "the spec'd overlay in the POP-OUT's document, hidden, and a
                   watchdog registered under the id the state carries")
              (tick)
              (is (= "none" (.-display (.-style overlay))) "a live opener reveals nothing")
              (reset! opener-closed? true)
              (tick)
              (is (= ["flex" true] [(.-display (.-style overlay)) (contains? @cleared wid)])
                  "a closed opener reveals THIS overlay and the watchdog self-clears"))))))))

(deftest popout!-wires-the-opener-reload-announcer-to-its-own-overlay
  (with-driven-popout
    (fn [{:keys [opener opener-listeners]}]
      (let [{:keys [render-fn]} (mk-render-stub)]
        (with-popout-seams render-fn
          (fn []
            (let [state   (mount/popout!)
                  overlay (:overlay-node state)
                  handler (:opener-pagehide-handler state)]
              (is (= [true true nil nil]
                     [(identical? opener (:opener-window state))
                      (= [handler] (get @opener-listeners "pagehide"))
                      (get @opener-listeners "unload")
                      (get @opener-listeners "beforeunload")])
                  "registered on the opener's pagehide, NEVER unload or
                   beforeunload (either costs the host the bfcache)")
              (handler (js-obj "persisted" true))
              (is (= "none" (.-display (.-style overlay)))
                  "a persisted pagehide is a bfcache freeze: reveal nothing")
              (handler (js-obj "persisted" false))
              (is (= "flex" (.-display (.-style overlay)))
                  "a real opener unload reveals the overlay"))))))))

;; ---- (e) re-popping into a window an opener reload left behind -----------
;;
;; `window.open("", "rf-xray-popout")` returns the old window un-navigated,
;; still holding the dead realm's root and its revealed overlay.

(deftest popout!-evicts-a-dead-realms-shell-and-overlay-from-a-reused-window
  (with-driven-popout
    (fn [{:keys [popout opener-doc]}]
      (let [body          (.-body (.-document popout))
            stale-root    (mk-stub-node)
            stale-overlay (mk-stub-node)
            ids-in-body   (fn [] (mapv #(.-id %) (array-seq (.-children body))))
            {:keys [render-fn]} (mk-render-stub)]
        (set! (.-id stale-root) "rf-xray-popout-root")
        (set! (.-id stale-overlay) "rf-xray-popout-opener-gone-overlay")
        (set! (.-display (.-style stale-overlay)) "flex")
        (.appendChild body stale-root)
        (.appendChild body stale-overlay)
        (with-popout-seams render-fn
          (fn []
            (let [state (mount/popout!)]
              (is (= ["rf-xray-popout-root" "rf-xray-popout-opener-gone-overlay"]
                     (ids-in-body))
                  "exactly one root and one overlay")
              ;; Booleans only: a failing `is` prints its operands, and the
              ;; stubs' parentNode <-> children cycle overflows the printer.
              (is (= [true true true true true "none" 0]
                     [(:ok? state)
                      (nil? (.-parentNode stale-root))
                      (nil? (.-parentNode stale-overlay))
                      (identical? (:node state) (first (array-seq (.-children body))))
                      (identical? (:overlay-node state) (second (array-seq (.-children body))))
                      (.-display (.-style (:overlay-node state)))
                      (.-length (.-children (.-body opener-doc)))])
                  "the dead root and overlay are gone, the live ones remain, the
                   overlay is hidden, the opener untouched"))))))))
