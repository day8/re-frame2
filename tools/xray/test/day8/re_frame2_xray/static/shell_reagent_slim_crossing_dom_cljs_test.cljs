(ns day8.re-frame2-xray.static.shell-reagent-slim-crossing-dom-cljs-test
  "rf2-7ds8 — Xray's Fresco boundaries must cross their surviving Reagent
  islands through the INSTALLED ratom build, and this suite measures that
  under **reagent-slim**, the adapter the defect was found on.

  ## The defect these rows stand over

  Xray's boundaries take the hiccup->React-element crossing as an
  `as-child` PARAMETER. Nine sites across six sources passed
  `reagent.core/as-element` — STOCK Reagent's walk, named statically.
  Under reagent-slim the installed build is `reagent2`, and the mismatch
  is quiet rather than loud:

    * React mounts the element, and the `:contextType` the `reg-view`
      head carries IS honoured — `reagent2.impl.component` reads
      `(:contextType (meta f))` exactly as stock's `fn-to-class` does —
      so the frame keyword genuinely reaches the mounted class.
    * But the foreign build renders the subtree with ITS
      in-flight-component slot bound, while `:adapter/current-component`
      routes to the INSTALLED build and answers nil inside it.
    * `re-frame.views.provider/current-frame` reads `(.-context cmp)` off
      that component, finds none, and returns nil — so every ambient
      `subscribe` / `dispatch` beneath raises
      `:rf.error/no-frame-context`, in React's RENDER phase, and React
      takes the subtree down. A blank Xray, not a diagnostic.

  ## Two rows, and they fail in different registers ON PURPOSE

  W1 is the DURABLE PIN. It reads the door itself and needs no DOM, so a
  regression reds ONE row with a precise message. W2 is the PAINT
  witness — the only thing that proves a developer on reagent-slim sees
  a shell rather than a blank panel — and it can only be had from a real
  React commit.

  ## Why W2 mounts under `h/error-boundary`, which is not decoration

  rf2-7ds8's own bead argued this defect could not be pinned at all:
  reproducing it needs an UNCAUGHT RENDER-PHASE THROW, and the browser
  runner fails any run carrying an uncaught `pageerror` independently of
  the `cljs.test` summary — so a naive pin would red the whole lane for
  every unrelated suite and make one real defect look like a broken
  runner. That reasoning is sound about a NAKED mount and is answered
  rather than contradicted here: an error boundary above the crossing
  makes the throw a HANDLED one, so a regression reddens this row on its
  own message and every neighbouring namespace still runs. The bead's
  conclusion was right for the instrument it had in mind.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build's regex
  also matches, so it LOADS under Node — where W2 short-circuits through
  [[browser?]] and reports the skip rather than passing silently. W1
  needs no DOM and runs in both."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent2.core :as slim]
            [reagent2.dom.client :as slim-dom]
            [reagent.core :as stock]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent-slim/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about; a
                      ;; neighbour's boundary left in the entry cache would
                      ;; make this suite read a residue that is not its own.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ===========================================================================
;; W1 — the crossing door is the INSTALLED build's walk
;; ===========================================================================

(deftest w1-as-element-door-is-the-installed-builds-walk
  (testing "rf2-7ds8 — with reagent-slim installed, `:adapter/as-element`
            walks hiccup the way reagent2 does and NOT the way stock
            Reagent does.

            IT IS MEASURED BY EFFECT RATHER THAN BY IDENTITY, and that is
            a finding rather than a convenience: `route-hook!` wraps the
            published fn so the chain can pick the INSTALLED adapter at
            call time, so the door is a `routed-hook` object and an
            `identical?` row against the raw var fails on a perfectly
            healthy wiring. What the crossing needs is not that var but
            its BEHAVIOUR, so that is what is read.

            THE DISCRIMINATOR IS THE COMPONENT TYPE REACT RECEIVES for a
            fn head, and all three readings go through the SAME `probe`
            fn ON PURPOSE. Each build caches the class it mints on the fn
            object under its own property — stock on `.-cljsReactClass`,
            reagent2 on `.-cljsReagentClass-fn` — so one fn can carry
            both and the two answers stay independent. Using a fresh fn
            per build instead would make the negative trivially true (two
            fns always mint two classes) and it would prove nothing.

            BOTH HALVES COMPARE TO LITERALS. `reagent2.core/as-element`
            and `reagent.core/as-element` are two distinct vars named
            independently of anything the door resolves, so the positive
            and the negative cannot degrade together — a door checked
            only against a value derived from the same lookup agrees with
            itself when the subject is broken and goes green ON the
            defect."
    (let [door  (rf.late-bind/get-fn-cached :adapter/as-element)
          probe (fn probe-view [] [:div {:data-testid "rf-slim-crossing-probe"}])]
      (is (some? door)
          "reagent-slim publishes :adapter/as-element (a nil door would make
           both comparisons below vacuous, so this is asserted first)")
      (when (some? door)
        ;; Stock runs FIRST and on this same fn, so if the two builds ever
        ;; collided on one cache property the negative below would catch it
        ;; rather than read a reassuring pass.
        (let [via-stock (stock/as-element [probe])
              via-door  (door [probe])
              via-slim  (slim/as-element [probe])]
          (is (identical? (.-type via-door) (.-type via-slim))
              "the door mints the component type REAGENT2 mints — the build
               whose in-flight component :adapter/current-component routes
               to, which is what lets views/current-frame read a frame off it")
          (is (not (identical? (.-type via-door) (.-type via-stock)))
              "the door does NOT mint stock Reagent's type. That is the defect
               itself: stock's walk renders the island under a build the
               installed adapter cannot see into, so the frame resolves nil
               and the subtree raises :rf.error/no-frame-context"))))))

;; ===========================================================================
;; W2 — the Static ribbon's Reagent island actually PAINTS under slim
;; ===========================================================================

(def ^:private caught
  "The error `h/error-boundary` caught below this row's crossing, or nil.
  Non-nil is the defect returning: the island raised in React's render
  phase and React took the subtree down."
  (atom nil))

(rf.fresco/defview guarded-ribbon
  "The Static L1 ribbon under an error boundary, so a render-phase raise
  from the crossing is HANDLED — this row reddens on its own message
  instead of aborting the lane for every other namespace on the page.
  See the ns docstring."
  [_props]
  [rf.fresco/error-boundary
   {:on-error (fn [error] (reset! caught error))
    :fallback [:div {:data-testid "rf-slim-crossing-fallback"}]}
   [static-shell/ribbon {}]])

(def ^:private guarded-component
  "The React component [[guarded-ribbon]] presents as. Declared once at
  top level, as `rf.fresco/as-component`'s contract requires — deriving
  it per render would mint a new component type every time and remount
  the subtree under test."
  (rf.fresco/as-component guarded-ribbon))

(defn- setup!
  "Register Xray's handlers — which is what registers the sub family the
  ribbon's islands read — and make the frame the tree names."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  nil)

(defn- mount-ribbon!
  "Mount the guarded ribbon through REAGENT-SLIM's own client root, under
  a `frame-provider` scoping `:rf/xray`. Committed synchronously —
  React 19's `root.render` is otherwise async and the assertions would
  read an empty container."
  []
  (let [container (.createElement js/document "div")
        root      (slim-dom/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (slim-dom/render root [rf/frame-provider {:frame :rf/xray}
                               [:> guarded-component {}]])))
    {:container container :root root}))

(defn- teardown! [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- testid
  "The committed node carrying `id`, or nil. Every reader below goes
  through this and is `some?`-tested rather than dereferenced, so a
  regression that empties the chrome reddens the row instead of throwing
  a TypeError out of `cljs.test/run-block` — which has no try/catch and
  would take the whole browser lane down with no summary."
  [container id]
  (some-> container (.querySelector (str "[data-testid=\"" id "\"]"))))

(deftest w2-ribbon-reagent-island-paints-under-reagent-slim
  (testing "rf2-7ds8 — under reagent-slim the Static L1 ribbon's Reagent
            island commits to the DOM, which it can only do if the
            boundary crossed it through the installed build AND the frame
            resolved beneath it.

            `frame-switcher-view` is a `reg-view` whose body makes an
            ambient read. Its node therefore exists ONLY when
            views/current-frame found a component to read a frame off —
            so the node's presence is the frame-context claim, not merely
            a paint claim."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (reset! caught nil)
        (setup!)
        (let [{:keys [container root]} (mount-ribbon!)]
          ;; The ribbon's own chrome — present even if the islands are gone,
          ;; so it separates "the boundary painted nothing at all" from "the
          ;; boundary painted but its island was taken down".
          (is (some? (testid container "rf-xray-static-ribbon"))
              "the Static ribbon boundary committed its own chrome")
          (is (nil? @caught)
              (str "no render-phase error reached the boundary above the "
                   "crossing. A :rf.error/no-frame-context here is rf2-7ds8 "
                   "returning: the island was crossed by a build the "
                   "installed adapter cannot see into. Caught: "
                   (pr-str @caught)))
          (is (nil? (testid container "rf-slim-crossing-fallback"))
              "the error boundary did NOT swap in its fallback")
          (is (some? (testid container "rf-xray-ribbon-frame"))
              (str "frame-switcher-view — a reg-view island the ribbon "
                   "crosses through its as-child seam — is committed under "
                   "reagent-slim. This is the node the defect removed"))
          (is (some? (testid container "rf-xray-ribbon-frame-picker"))
              "the island rendered its frame picker, so its ambient read ran
               rather than raising")
          (teardown! root container)
          (done))))))
