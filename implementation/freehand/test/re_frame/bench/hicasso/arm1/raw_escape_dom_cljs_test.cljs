(ns re-frame.bench.hicasso.arm1.raw-escape-dom-cljs-test
  "THE `[:>]` RAW ESCAPE AGAINST A REAL REACT (HD-011, rf2-2rtt6.103).

  The element-level contract — the carrier, the prop walk, the value
  roster, `:&`, `:key`, refs, the mis-parse regression — is
  [[re-frame.bench.hicasso.front.codec-cljs-test]]'s, where it can be
  read off the element without a DOM. This file carries the three claims
  that a mounted React is the only honest witness for:

  1. **SSR is hard `:client-only`, and hydration's first pass agrees.**
     The escape carries no declaration, so it carries no `:ssr` policy
     and there is no spelling for one. Server-absent and
     first-pass-absent are not two facts kept in step — they are ONE
     fact, because React reads the gate's SERVER snapshot under
     `renderToString` and again on hydration's first client pass, then
     re-renders with the client snapshot once adoption completes. The
     row therefore asks REACT whether it found a mismatch, through
     `onRecoverableError` and a console/window capture; a row that only
     read the settled DOM would pass over a mismatch React repaired.

  2. **The canonical DOM is not reduced at all.** HD-011's ruled phrase
     is *reduced structural identity*, and the reduction is exactly one
     slot of the hiccup vector (slot 1 holds a JS value compared by
     identity). The DOM is not reduced, and that is provable rather than
     asserted: the gate contributes no node of its own and
     [[re-frame.bench.hicasso.lane/canonical]] serialises element and
     text nodes only, so a nil-rendering gate is invisible to it. Same
     component, same props, one through `[:>]` and one through `defhost`
     — byte-equal.

  3. **Children lower in the WRITING boundary's render window.** An
     intent closure in a `[:>]` child captures that boundary's
     frame-locked dispatch at lowering time and fires into it however
     much later the foreign component renders it — a portal, a
     virtualised window, a `Suspense` fallback. Two frames are live so
     that \"the right frame\" is distinguishable from \"any frame\".

  ## The mutation witnesses

  Make the escape render its component server-side — `gate-unadopted`
  answering `true`, or `raw-element` creating the component's element
  directly — and [[the-escape-hydrates-with-nothing-there-and-mounts-after-adoption]]
  goes red on React's own mismatch report. Give the gate a wrapper node
  of its own and [[the-escape-and-the-door-produce-the-same-dom]] goes
  red on the canonical bytes. Lower children lazily, or forward them
  without the owner's frame, and [[a-child-intent-fires-into-the-frame-that-wrote-the-crossing]]
  goes red on the recorder that should have been empty.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The `renderToString` rows need no DOM and run under
  `:node-test` too."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview defhost]]))

(def ^:private frame-id ::raw-escape)

(rf/reg-sub :raw/title (fn [db _] (:title db)))

(rf/reg-event :raw/seed (fn [_ _] {:db {:title "quarterly"}}))

(rf/reg-event :raw/picked
              (fn [{:keys [db]} [_ what]]
                {:db (update db :picked (fnil conj []) what)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration row waits on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- skip! [why] (is true (str "a [:>] DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:raw/seed]))
  frame-id)

(defn- db [] (rf/app-db-value frame-id))

;; ---------------------------------------------------------------------------
;; One foreign component, reached two ways
;; ---------------------------------------------------------------------------

(def ^:private !renders
  "How many times the foreign component's body ran. The server row's
  strongest assertion: a `[:>]` crossing is not merely absent from the
  HTML, its component was never INVOKED — which is the property an
  author relies on when the reason they reached for the escape is a
  widget that touches `window`."
  (atom 0))

(defn- widget
  "A stand-in for the library's component. It renders REAL markup with
  real attributes so that a canonical comparison has something to
  compare, and it renders its children so the forwarding claim is
  visible in the DOM."
  [^js props]
  (swap! !renders inc)
  (react/createElement "div"
    #js {:className "widget" :data-live "yes" :data-variant (.-variant props)}
    (react/createElement "span" #js {:className "widget-label"} (.-label props))
    (.-children props)))

(defhost declared-widget
  "The DOOR, on the same component and under the same default policy —
  the control that makes every parity row below a fact about the
  crossing rather than about the page."
  widget)

;; ---------------------------------------------------------------------------
;; The pages
;; ---------------------------------------------------------------------------

(defview escape-page
  "A native sibling beside the crossing, so \"the escape rendered
  nothing\" stays distinguishable from \"nothing rendered at all\"."
  [_]
  [:div.page
   [:h1.title (rt/sub [:raw/title])]
   [:> widget {:label (rt/sub [:raw/title]) :variant "compact"}
    [:span.kid "slotted"]]])

(defview door-page
  "[[escape-page]] through `defhost`, byte for byte the same authored
  shape."
  [_]
  [:div.page
   [:h1.title (rt/sub [:raw/title])]
   [declared-widget {:label (rt/sub [:raw/title]) :variant "compact"}
    [:span.kid "slotted"]]])

(defview intent-child-page
  "A crossing whose CHILD carries an intent. The child's handler is
  lowered here, in this body's render window, and invoked much later —
  after every render extent has unwound — from the click."
  [_]
  [:div.page
   [:> widget {:label "rows" :variant "list"}
    [:button.pick {:on-click [:raw/picked "child"]} "pick"]]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- server-html
  "The page as a server render — the same runtime, under React's server
  renderer."
  [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(defn- q [root sel] (.querySelector root sel))

(defn- watch-errors!
  "Everything React has to say about a hydration, from all three
  channels it uses. `console.error` carries the mismatch diff;
  `onRecoverableError` carries the recovery; the `window` listener is
  here because React routes a throw during a commit to `reportError`,
  where `cljs.test` cannot see it and a row would read 0 failures over a
  live exception."
  []
  (let [seen     (atom [])
        original (.-error js/console)
        on-error (fn [e] (swap! seen conj (str "window: " (.-message e))))]
    (set! (.-error js/console)
          (fn [& args] (swap! seen conj (str "console.error: " (pr-str (vec args))))))
    (.addEventListener js/window "error" on-error)
    {:seen    seen
     :restore (fn []
                (set! (.-error js/console) original)
                (.removeEventListener js/window "error" on-error))}))

;; ---------------------------------------------------------------------------
;; 1 — the server render: nothing, and the component never ran
;; ---------------------------------------------------------------------------

(deftest the-escape-renders-nothing-on-the-server-and-cannot-say-otherwise
  (fresh!)
  (testing "hard `:client-only`. The escape carries no declaration, so it
            carries no `:ssr` policy and there is no inline spelling for
            one — a fallback is POLICY, and policy lives on declarations"
    (reset! !renders 0)
    (let [html (server-html [escape-page {}])]
      (is (re-find #"quarterly" html)
          (str "the page rendered — the sibling native node is there: " html))
      (is (not (re-find #"class=\"widget\"" html))
          (str "and the crossing rendered NOTHING: " html))
      (is (not (re-find #"slotted" html))
          (str "including its children, which lower eagerly and are then
                discarded on the server: " html))
      (is (zero? @!renders)
          "the foreign component's body never ran on the server, which is
           the property an author reaching for the escape is relying on")))
  (testing "and the DOOR under its own default answers identically — the
            escape inherits `:client-only`, it does not invent it"
    (reset! !renders 0)
    (let [html (server-html [door-page {}])]
      (is (not (re-find #"class=\"widget\"" html)) (str "absent at the door too: " html))
      (is (zero? @!renders)))))

;; ---------------------------------------------------------------------------
;; 2 — a fresh mount, and the canonical DOM
;; ---------------------------------------------------------------------------

(deftest a-fresh-mount-renders-the-component-on-its-first-pass
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "a `createRoot` mount never consults a server snapshot, so
                the gate costs no placeholder pass — asserted on the line
                after `root!` returns, which is inside its flushSync"
        (let [h (mount/root! (mount/fresh-container!) frame-id [escape-page {}])]
          (try
            (is (some? (q (:container h) ".widget"))
                "the component mounted immediately")
            (is (= "yes" (.getAttribute (q (:container h) ".widget") "data-live"))
                "the real one, not a placeholder")
            (is (= "quarterly" (.-textContent (q (:container h) ".widget-label")))
                "a prop reached the foreign component through the gate")
            (is (= "compact" (.getAttribute (q (:container h) ".widget") "data-variant"))
                "and so did the second one")
            (is (some? (q (:container h) ".widget .kid"))
                "and the children, in the component's own slot — forwarded
                 by the gate as createElement's third argument")
            (finally (mount/release! h))))))))

(deftest the-escape-and-the-door-produce-the-same-dom
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "HD-011's *reduced structural identity* is a claim about the
                hiccup data lane — slot 1 holds a JS value compared by
                identity — and about the fiber lane's crossing name. It is
                NOT a claim about the DOM, and this row is why that can be
                stated rather than hedged: the gate contributes no node of
                its own, and `lane/canonical` serialises element and text
                nodes and ignores comments, so a nil-rendering gate is
                invisible to it"
        (let [a (mount/root! (mount/fresh-container!) frame-id [escape-page {}])
              b (mount/root! (mount/fresh-container!) frame-id [door-page {}])]
          (try
            (let [via-escape (lane/canonical (:container a))
                  via-door   (lane/canonical (:container b))]
              (is (re-find #"class=\"widget\"" via-escape)
                  (str "precondition — the fixture renders something to
                        compare, so the row cannot pass on two empty
                        strings: " via-escape))
              (is (= via-door via-escape)
                  (str "BYTE-EQUAL. escape: " via-escape " / door: " via-door)))
            (finally (mount/release! a) (mount/release! b)))))
      (testing "and the crossing's own fiber is named by the CONSTANT, not
                by the component — one greppable frame naming the form the
                author wrote, with the component naming itself one level
                down at zero cost"
        (let [e (codec/as-element [:> widget {}])]
          (is (= "[:>]" (.-displayName (.-type e)))))))))

;; ---------------------------------------------------------------------------
;; 3 — hydration: the first client pass matches, and adoption swaps
;; ---------------------------------------------------------------------------

(deftest the-escape-hydrates-with-nothing-there-and-mounts-after-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (reset! !renders 0)
        (let [hiccup    [escape-page {}]
              html      (server-html hiccup)
              container (mount/fresh-container!)
              {:keys [seen restore]} (watch-errors!)]
          (set! (.-innerHTML container) html)
          (let [root (react-dom-client/hydrateRoot
                       container
                       (mount/provider frame-id (codec/root-element frame-id hiccup))
                       #js {:onRecoverableError
                            (fn [err _info]
                              (swap! seen conj (str "onRecoverableError: " (ex-message err))))})]
            (js/setTimeout
              (fn []
                (try
                  (is (not (re-find #"class=\"widget\"" html))
                      (str "the markup hydrated FROM has no crossing in it — the
                            row's own restatement of the policy, so a gate that
                            stopped gating is red HERE and not only in the
                            server-render row: " html))
                  (is (empty? @seen)
                      (str "REACT FOUND NOTHING TO RECONCILE. The client's first
                            pass rendered what the server did — nothing — because
                            both read the SAME snapshot, so there was no mismatch
                            to repair: " (pr-str @seen)))
                  (is (some? (q container ".widget"))
                      "and after adoption the foreign component is mounted")
                  (is (some? (q container ".widget .kid"))
                      "with its children")
                  (is (some? (q container ".title"))
                      "and the server's own markup still in place around it —
                       adoption, not a re-render of the page")
                  (is (pos? @!renders)
                      "the component ran on the client, and only on the client")
                  (finally
                    (restore)
                    (.unmount root)
                    (when-some [p (.-parentNode container)] (.removeChild p container))
                    (rt/reset-runtime!)
                    (done))))
              150)))))))

;; ---------------------------------------------------------------------------
;; 4 — children capture the WRITING boundary's frame
;; ---------------------------------------------------------------------------

(deftest a-child-intent-fires-into-the-frame-that-wrote-the-crossing
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (testing "children lower EAGERLY at the crossing, inside the render
                window of the boundary that wrote it, so a child's intent
                closure captures that boundary's frame-locked dispatch at
                lowering time — and fires into it however much later the
                foreign component renders it. Two frames are live and the
                page is mounted in ONE of them, so \"the right frame\" is
                distinguishable from \"any frame\""
        (let [other ::a-second-frame]
          (fresh!)
          (rf/make-frame {:id other})
          (rf/with-frame other (rf/dispatch-sync [:raw/seed]))
          (let [h (mount/root! (mount/fresh-container!) frame-id [intent-child-page {}])]
            (try
              (mount/settle!)
              (is (some? (q (:container h) ".widget .pick"))
                  "precondition: the child is mounted BENEATH the foreign
                   component, so its handler really did cross")
              (.click (q (:container h) ".pick"))
              (mount/settle!)
              (is (= ["child"] (:picked (db)))
                  "the click dispatched into the frame that wrote the crossing")
              (is (nil? (:picked (rf/app-db-value other)))
                  "and nothing reached the other frame, which is what makes
                   the ownership assertion non-vacuous")
              (finally (mount/release! h)))))))))
