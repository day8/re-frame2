(ns re-frame.route-link-frame-context-dom-cljs-test
  "`[rf/route-link …]` RENDERS, MOUNTED THE WAY AN APPLICATION MOUNTS IT
  (rf2-nvcp).

  ## The gap this file closes

  Every other route-link suite in the tree calls `routing/route-link-render`
  — the bare render fn — directly, and every one of them establishes the
  frame with `rf/with-frame`. That is the DYNAMIC-VAR tier, tier 1 of the
  three-tier chain `frame/resolve-current-frame` reads, and it answers
  whoever asks, from anywhere. So those suites can be green for a component
  that no application can render.

  They were. `rf/route-link` reaches a call site through a `defwrapper`
  (`re-frame.core-routing`), whose body CALLS the value on the
  `:routing/route-link` hook rather than mounting it. The component the
  substrate actually mounted was therefore `rf/route-link` itself — a plain
  fn, carrying no `{:contextType frame-context}` — so `(.-context cmp)` was
  React's empty default, the REACT-CONTEXT tier (tier 2, the only tier a
  real application's `frame-root` establishes) resolved nil, and
  `route-link-render`'s render-time `require-current-frame!` raised
  `:rf.error/no-frame-context` on first render. Three shipped examples
  mounted nothing at all — `#app` innerHTML length 0, a blank page — while
  the unit suites stayed green.

  So the rows below are deliberately NOT unit calls on the render fn:

    * they mount through the Reagent adapter into a real document, so the
      component boundary the bug lives at is genuinely constructed; and
    * they establish the frame ONLY through a React-context boundary — a
      `frame-provider` (SCOPE) in one row, a `frame-root` (ENSURE, the
      applications' own shape) in the other — with `:ambient-frame nil` on
      the fixture so no `with-frame` scope can answer in its place.

  A row that passes because tier 1 answered would prove nothing, which is
  exactly what the pre-existing coverage did.

  ## What a failure looks like

  A route-link that cannot resolve its frame THROWS during render, and
  React discards the whole subtree — so the failure shows up as an absent
  anchor, not a wrong one. Each row therefore reports the container's actual
  innerHTML on failure, and reports any error that escaped the mount,
  because 'the anchor is missing' on its own does not say why.

  Per Spec 012 §Linking from views and Spec 002 §Frame target resolution.
  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers it;
  `:node-test` loads it too, where the rows degrade to a STATED skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.test-support :as test-support]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a route-link render witness needs a real browser — " why)))

;; ---------------------------------------------------------------------------
;; The application under test — one route, one view, one link
;; ---------------------------------------------------------------------------

(def ^:private root-id "rf2-route-link-frame-context-root")

(def ^:private link-testid "rf2-route-link-under-context")

(def articles-route ::articles)

(def ^:private articles-url "/rf2-route-link-ctx/articles")

(defn- register-routes!
  "Register this witness's single route.

  A function rather than an ns-load effect: the reset fixture restores the
  registrar to a baseline captured when `use-fixtures` was evaluated, so a
  route registered at load is rolled back before the first row runs. The
  `/rf2-route-link-ctx` leading segment keeps the path out of every other
  namespace's match table in the shared bundle (TESTING.md §Test authoring
  policy)."
  []
  (routing/reg-route articles-route {:doc "The article list."} articles-url)
  nil)

(rf/reg-view* ::app
  (fn app []
    ;; A route-link and nothing else. The point of the row is the link's own
    ;; render, so anything else on the page would only be somewhere for a
    ;; failure to hide.
    [:main
     [rf/route-link {:to           articles-route
                     :data-testid  link-testid}
      "See the articles"]]))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     ;; THE LOAD-BEARING LINE. With an ambient frame bound, tier 1 answers
     ;; every resolution and both rows below pass without the React-context
     ;; tier ever being consulted — which is precisely the blind spot that
     ;; let the regression ship.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (routing/reset-counters!)
                      (register-routes!))}))

;; ---------------------------------------------------------------------------
;; Mount / teardown
;; ---------------------------------------------------------------------------

(defn- mount!
  "Create a root element, append it to the document, and commit `tree`
  synchronously. Returns `{:container :root :error}` — `:error` holds
  anything the first render threw, so a row can name the cause rather than
  only reporting an absent anchor."
  [tree]
  (let [container (.createElement js/document "div")]
    (.setAttribute container "id" root-id)
    (.appendChild js/document.body container)
    (let [root  (rdc/create-root container)
          error (try
                  (react-dom/flushSync (fn [] (rdc/render root tree)))
                  nil
                  (catch :default e e))]
      {:container container :root root :error error})))

(defn- teardown!
  "Unmount, detach the root, and drop the frame. Runs on the success and
  failure paths alike, so a row that threw cannot make the next row wrong."
  [{:keys [container root]} frame-id]
  (try (.unmount root) (catch :default _ nil))
  (try (.remove container) (catch :default _ nil))
  (try (rf/destroy-frame! frame-id) (catch :default _ nil))
  nil)

(defn- anchor
  "The rendered `<a>`, or nil. Selected by the link's own passthrough
  `:data-testid` rather than by tag, so a stray anchor from anything else on
  the shared test page cannot answer for it."
  [{:keys [container]}]
  (.querySelector container (str "a[data-testid=\"" link-testid "\"]")))

(defn- describe-failure
  "What the container actually holds, plus whatever the mount threw — the
  two facts that turn 'no anchor' into a diagnosis."
  [{:keys [container error]}]
  (str "container innerHTML = " (pr-str (.-innerHTML container))
       (when error
         (str "; the render threw " (pr-str (ex-data error))
              " :: " (.-message error)))))

(defn- assert-link-rendered!
  "The whole contract, asserted the same way for both boundaries: the mount
  did not throw, and the anchor is on the page carrying the route's href."
  [m label]
  (is (nil? (:error m))
      (str label " — the first render must not throw. " (describe-failure m)))
  (let [a (anchor m)]
    (is (some? a)
        (str label " — the route-link must render an <a>. " (describe-failure m)))
    (when (some? a)
      (is (= articles-url (.getAttribute a "href"))
          (str label " — the anchor's href must be the route's URL."))
      (is (= "See the articles" (.-textContent a))
          (str label " — the anchor must carry the link's children.")))))

;; ---------------------------------------------------------------------------
;; Rows
;; ---------------------------------------------------------------------------

(deftest route-link-renders-under-a-frame-provider-rf2-nvcp
  (testing "a route-link inside a frame-provider — the SCOPE boundary — resolves
           its frame from the React-context tier and renders its anchor"
    (if-not (browser?)
      (skip! "frame context is a React-context read on a mounted component")
      (async done
        (let [frame-id ::provider-frame]
          (rf/make-frame {:id frame-id :doc "route-link render witness (SCOPE)."})
          (let [m (mount! [rf/frame-provider {:frame frame-id}
                           [(rf/view ::app)]])]
            (assert-link-rendered! m "frame-provider")
            (teardown! m frame-id)
            (done)))))))

(deftest route-link-renders-under-a-frame-root-rf2-nvcp
  (testing "a route-link inside a frame-root — the ENSURE boundary, and the exact
           shape every failing example mounted — renders its anchor"
    (if-not (browser?)
      (skip! "frame context is a React-context read on a mounted component")
      (async done
        (let [frame-id ::root-frame
              ;; `frame-root` ENSUREs in a client `useLayoutEffect`, so the
              ;; first render emits no descendant subtree and the children
              ;; render on the pass after the commit. Poll for the anchor
              ;; rather than counting frames: a deadline reports which render
              ;; never arrived, a frame count reports only that a line was
              ;; false.
              m        (mount! [rf/frame-root {:id  frame-id
                                               :doc "route-link render witness (ENSURE)."}
                                [(rf/view ::app)]])]
          (-> (test-support/poll-until
                #(some? (anchor m))
                {:label "the route-link's anchor under a frame-root"})
              (.then  (fn [_] (assert-link-rendered! m "frame-root")))
              (.catch (fn [_]
                        ;; The poll timing out IS the regression's signature —
                        ;; report it as the assertion the row is about, with the
                        ;; render's own error when one escaped.
                        (assert-link-rendered! m "frame-root")))
              (.then  (fn [_] (teardown! m frame-id) (done)))))))))
