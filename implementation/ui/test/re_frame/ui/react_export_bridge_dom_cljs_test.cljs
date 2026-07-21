(ns re-frame.ui.react-export-bridge-dom-cljs-test
  "rf2-u53yy.2 — `ui/->react`, the OUTWARD interop bridge, proven end to end on a
  REAL foreign react-dom root: a compiled `defview` exported with
  `(ui/->react view)` renders inside a FOREIGN React tree, its `(sub …)` resolves
  the scoped frame, and its declared props / children pass through. The inward
  counterpart is `ui/raw`; both traffic in plain React values.

  The contract pinned here (Spec 004 §The React interop tier; the compat-boundary
  contract §3):

    - the exported view renders inside a foreign React parent it does NOT own,
      creating NO `ui` React root and running NO host preflight
      (`client/live-root-ids` stays empty);
    - a SUPPLIED `frame` prop scopes the subtree without owning it — the frame is
      neither created nor destroyed by the boundary, so it survives unmount;
    - with no `frame` prop the exported view resolves its frame from the ambient
      React-context chain (a foreign frame Provider above it);
    - THE shallow props-conversion rule hands the foreign JS props to the view by
      exact slot name (the `label` prop) and preserves `children`;
    - the exported component is memoised per view identity (repeated
      `(ui/->react view)` returns the IDENTICAL object);
    - a post-mount dispatch drives the exported view's compiled `(sub …)` through
      observation → reactive flush → React rerender, so subs are LIVE across the
      boundary, not a mount-time snapshot.

  The invalid-argument guard and the per-view-identity memoisation are
  host-independent (they build no React tree), so those run cross-runtime —
  including under `:node-test`, where the DOM bodies gate out. The JVM host-op
  behaviour rides `react_interop_jvm_test`.

  Browser-only bodies — `-dom-cljs-test$` opts this file into `:browser-test`;
  under `:node-test` every DOM body gates on `(browser?)` and exits early."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as rdc]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview sub]]
            [re-frame.ui.client :as client]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

;; --- the canonical native-React act helper (rf2-vxgfnd.89 pattern) -----------
(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try (.-act (js/require "react-dom/test-utils")) (catch :default _ nil))))

(defn- enable-react-act-env! []
  (when (browser?)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)))

(defn- act!
  "Run `thunk` inside React `act()`, returning the thunk's value. The commits
  here settle synchronously under IS_REACT_ACT_ENVIRONMENT, so the callback runs
  eagerly and we capture its result."
  [thunk]
  (let [ret    (volatile! nil)
        act-fn (get-act)]
    (act-fn (fn [] (vreset! ret (thunk))))
    @ret))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter ui/adapter
                                            :ambient-frame nil
                                            :async? true})
  (let [prior-act-env (atom nil)]
    {:before
     (fn []
       (when (browser?)
         (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
       (enable-react-act-env!)
       (reactive/reset-scheduler!)
       (client/reset-live-roots!)
       (frames/reset-installed-plans!))
     :after
     (fn []
       (reactive/reset-scheduler!)
       (client/reset-live-roots!)
       (frames/reset-installed-plans!)
       (when (browser?)
         (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))}))

(defn- container [] (js/document.createElement "div"))

(defn- reg! []
  (rf/reg-event :cart/set-qty (fn [_ [_ n]] {:db {:qty n}}))
  (rf/reg-sub :cart/qty (fn [db _] (:qty db))))

;; A compiled view with BOTH a declared prop (`label`) and a `(sub …)` — the
;; canonical outward-export leaf. `cart-row` is an ordinary defview value; the
;; export below wraps it, it is unchanged.
(defview cart-row [{:keys [label]}]
  [:div.cart-row (str label "=" (sub [:cart/qty]))])

;; A children-accepting view — exercises the shallow rule's `children` passthrough.
(defview panel [{:keys [children]}]
  [:section.panel children])

(def CartRow (ui/->react cart-row))
(def Panel   (ui/->react panel))

;; A foreign React parent (a plain function component, NOT a compiled view) that
;; renders the exported CartRow as an ordinary React child.
(defn- foreign-parent [^js props]
  (React/createElement
   "main" #js {:className "foreign"}
   (React/createElement CartRow #js {:frame (.-frame props) :label "milk"})))

;; ---------------------------------------------------------------------------
;; Host-independent: per-view-identity memoisation + the argument guard
;; (build no React tree, so they run under :node-test too)
;; ---------------------------------------------------------------------------

(deftest exported-component-is-memoised-per-view-identity
  (is (identical? CartRow (ui/->react cart-row))
      "repeated (ui/->react view) returns the IDENTICAL component object — a
       foreign parent re-render never remounts the exported subtree")
  (is (not (identical? CartRow Panel))
      "distinct views export distinct components"))

(deftest ->react-rejects-a-non-view
  (testing "a view id keyword is not a compiled view value"
    (is (= :rf.error/ui-tree-malformed
           (try (ui/->react :cart-row) nil
                (catch :default e (:rf.error/id (ex-data e)))))))
  (testing "nil is not a compiled view value"
    (is (= :rf.error/ui-tree-malformed
           (try (ui/->react nil) nil
                (catch :default e (:rf.error/id (ex-data e))))))))

;; ---------------------------------------------------------------------------
;; The reserved `frame` prop is EXACT + SELF-ATTRIBUTING (rf2-01rwd)
;;
;; The exported component is a plain React function component; invoking it
;; directly runs its frame-prop boundary (own-property detection + validation)
;; WITHOUT rendering the view — `jsx2` builds an element, it never mounts — so
;; these controls need no DOM and run cross-runtime (under :node-test too).
;; ---------------------------------------------------------------------------

(defn- frame-boundary-error
  "Invoke the exported component `Comp` with a props object `props` and return
  the `{:rf.error/id … :where …}` of the typed error it throws, or `nil` when it
  returns without throwing."
  [Comp props]
  (try (Comp props) nil
       (catch :default e
         (select-keys (ex-data e) [:rf.error/id :where]))))

(deftest ->react-frame-prop-is-exact-and-self-attributing
  ;; A live frame for the valid + omitted-with-ambient cases; the fixture resets
  ;; the runtime around each test, so nothing else registers `:app/ok`.
  (rf/make-frame {:id :app/ok})

  (testing "OMITTED frame prop is the sole ambient-resolution case — no own
            `frame` key means the boundary does NOT validate and hands the
            element to the ambient chain (no throw here)"
    (is (nil? (frame-boundary-error CartRow #js {:label "x"}))
        "an omitted `frame` prop is never validated at the bridge")
    (is (some? (CartRow #js {:label "x"}))
        "the omitted-frame path returns the exported element for ambient resolution"))

  (testing "an explicit `frame={null}` is an OWN property with a nil value — it
            fails loud with the empty frame-target category instead of silently
            adopting the ambient frame (the rf2-01rwd defect)"
    (is (= {:rf.error/id :rf.error/no-frame-context
            :where       're-frame.ui/->react}
           (frame-boundary-error CartRow #js {:frame nil}))))

  (testing "an explicit `frame={undefined}` own property fails loud identically"
    (let [p (js-obj)]
      (unchecked-set p "frame" js/undefined)
      (is (= {:rf.error/id :rf.error/no-frame-context
              :where       're-frame.ui/->react}
             (frame-boundary-error CartRow p)))))

  (testing "a MALFORMED target (a string, not a frame-id keyword / live frame
            value) is `:rf.error/bad-frame-provider-arg`, self-attributed"
    (is (= {:rf.error/id :rf.error/bad-frame-provider-arg
            :where       're-frame.ui/->react}
           (frame-boundary-error CartRow #js {:frame "app"}))))

  (testing "a keyword target naming NO live frame is
            `:rf.error/frame-provider-frame-absent`, self-attributed"
    (is (= {:rf.error/id :rf.error/frame-provider-frame-absent
            :where       're-frame.ui/->react}
           (frame-boundary-error CartRow #js {:frame :app/ghost}))))

  (testing "a VALID frame id scopes without owning — no throw, returns an element"
    (is (nil? (frame-boundary-error CartRow #js {:frame :app/ok})))
    (is (some? (CartRow #js {:frame :app/ok}))
        "the valid-target path returns the scoped provider element")
    (is (some? (frame/frame :app/ok))
        "SCOPE-only — validating/scoping the target creates or destroys nothing")))

;; ---------------------------------------------------------------------------
;; The mounted proof — export renders inside a FOREIGN react-dom root
;; ---------------------------------------------------------------------------

(deftest exported-view-renders-in-foreign-react-with-supplied-frame
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/cart})
    (rf/dispatch-sync [:cart/set-qty 3] {:frame :app/cart})
    (let [c    (container)
          root (rdc/createRoot c)]
      (try
        (act! #(.render root (React/createElement foreign-parent #js {:frame :app/cart})))
        (is (re-find #"milk=3" (.-innerHTML c))
            (str "the exported view rendered inside a foreign React parent: the "
                 "shallow rule passed the `label` prop through by slot name, and "
                 "the compiled (sub [:cart/qty]) resolved the SUPPLIED frame "
                 "through the shared React frame-context scope"))
        (is (= #{} (client/live-root-ids))
            "the bridge created NO ui React root — it renders inside the host root")
        (finally
          (act! #(.unmount root))))
      (is (some? (frame/frame :app/cart))
          "the boundary SCOPED the frame without owning it — unmount left it live"))))

(deftest exported-view-resolves-frame-from-ambient-context
  ;; No `frame` prop: the exported view reads the ambient React-context chain,
  ;; here a foreign frame Provider above it (the same shared context object a
  ;; legacy `rf/frame-provider` installs — the compat-boundary §3 zero-spelling
  ;; case). `provider-element` is that raw Provider primitive.
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/ambient})
    (rf/dispatch-sync [:cart/set-qty 7] {:frame :app/ambient})
    (let [c    (container)
          root (rdc/createRoot c)]
      (try
        (act! #(.render root
                        (adapter-context/provider-element
                         :app/ambient
                         (React/createElement CartRow #js {:label "eggs"}))))
        (is (re-find #"eggs=7" (.-innerHTML c))
            (str "with NO frame prop the exported view resolved its frame from "
                 "the ambient React-context scope above it — no explicit pin"))
        (finally
          (act! #(.unmount root)))))))

(deftest exported-view-preserves-children
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/panel})
    (let [c    (container)
          root (rdc/createRoot c)]
      (try
        (act! #(.render root
                        (React/createElement
                         Panel #js {:frame :app/panel}
                         (React/createElement "span" #js {:className "kid"} "inner"))))
        (is (re-find #"inner" (.-innerHTML c))
            "React's `children` passed through the shallow rule to the view body")
        (is (re-find #"class=\"kid\"" (.-innerHTML c))
            "the foreign child element rendered verbatim under the exported view")
        (finally
          (act! #(.unmount root)))))))

(deftest exported-view-sub-is-live-across-the-boundary
  ;; A GENUINE post-mount compiled-`sub` update through the exported bridge:
  ;; dispatch a new qty against the already-mounted frame and settle through the
  ;; framework's canonical awaited-act flush idiom (`ui.test/flush!`). This
  ;; proves the exported view's sub is a LIVE dependency (observation → reactive
  ;; flush → useSyncExternalStore → React rerender inside the FOREIGN root), not
  ;; a mount-time snapshot.
  (when (browser?)
    (reg!)
    (rf/make-frame {:id :app/live})
    (rf/dispatch-sync [:cart/set-qty 1] {:frame :app/live})
    (let [c    (container)
          root (rdc/createRoot c)]
      (act! #(.render root (React/createElement foreign-parent #js {:frame :app/live})))
      (is (re-find #"milk=1" (.-innerHTML c)) "mount-time seed rendered")
      (async done
        (-> (js/Promise.resolve)
            (.then (fn [] (uit/flush! #(uit/dispatch! :app/live [:cart/set-qty 5]))))
            (.then
             (fn []
               (is (re-find #"milk=5" (.-innerHTML c))
                   (str "the post-mount dispatch drove the exported view's "
                        "compiled (sub …) through to a React rerender in the "
                        "foreign root — subs are live across the bridge"))))
            (.catch
             (fn [e]
               (is false (str "post-mount flush! rejected: " (some-> e .-message)))))
            (.then
             (fn []
               (act! #(.unmount root))
               (done))))))))
