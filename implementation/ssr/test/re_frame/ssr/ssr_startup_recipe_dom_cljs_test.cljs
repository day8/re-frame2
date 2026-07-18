(ns re-frame.ssr.ssr-startup-recipe-dom-cljs-test
  "DIRECT browser proof of the canonical SSR startup recipe (rf2-byio7).

  ## The gap this closes

  PR #6052 corrected the SSR capability example's client boot
  (`examples/capabilities/ssr/ssr/core.cljc` `run`) to call
  `reagent.dom.client/hydrate-root` when a `__rf_payload` exists and
  `create-root` + `render` only for a client-only load. But every test
  guarding that recipe was INDIRECT — none proved the shipped recipe
  actually ADOPTS the server-rendered DOM and ACTIVATES handlers on it:

    - the Reagent / reagent-slim adapter render suites STUB `hydrate-root`
      via `with-redefs` and only assert call ROUTING
      (`implementation/adapters/reagent/test/re_frame/adapter_render_cljs_test.cljs:111`,
      `implementation/adapters/reagent-slim/test/re_frame/adapter/reagent_slim_render_cljs_test.cljs:107`).
      A stubbed `hydrate-root` never touches a real DOM, so a recipe that
      swapped to `create-root` + `render` (discarding the server markup)
      would still route to *some* root and stay green;
    - the shared React hydrate probe
      (`implementation/core/test/re_frame/adapter/react_shared_suite.cljs:2990`)
      asserts only `(pos? (.-length childNodes))` — a fresh client
      re-render leaves childNodes non-empty too, so it cannot tell
      adoption from replacement;
    - the JVM example tests
      (`implementation/core/test/re_frame/examples_test.clj`
      `ssr-example-client-hydration-*`) exercise payload / hash STATE,
      never the CLJS mount.

  A `create-root` regression in the copyable recipe could therefore
  replace the server DOM while all of the above stayed green.

  ## Why this drives the seam rather than importing `ssr.core`

  The example ns `ssr.core` cannot be `:require`d into a CLJS test: it and
  `examples/core/login/model.cljs` (ns `login.model`, already in the
  consolidated `:node-test` bundle via `example_login_success_token_cljs_test`)
  BOTH register the fx `:auth.session/store`, so co-loading them fails
  image assembly with `:rf.error/image-duplicate-id` and breaks unrelated
  tests bundle-wide. Per the bead's own allowance, this proof instead
  drives the SMALLEST SHARED SEAM the recipe directly executes:
  `run-recipe!` below is a line-for-line mirror of `ssr.core/run`'s mount
  branch, exercising the SAME real machinery — `re-frame.ssr/hydrate!`
  (payload read + state seed), the stock Reagent adapter, and
  `reagent.dom.client/hydrate-root` / `create-root` / `render` — against
  real planted server DOM. Keep this mirror in lock-step with
  `ssr.core/run`.

  The view here is a PLAIN component (not `reg-view`) on the `:rf/default`
  frame — deliberately. In a DEV build a registered-view root carries the
  `data-rf-view` / `data-rf2-source-coord` annotations the reagent CLIENT
  render stamps but no server renderer reproduces (`rf/render-to-string`
  emits only `data-rf2-source-coord`; reagent's SSR renderer emits
  neither), so a registered view's server markup can never byte-match its
  own dev client render — React then treats hydration as a mismatch and
  REGENERATES the tree, masking the adopt-vs-replace distinction under
  test. A plain component carries no such annotations on either side, so
  the markup matches and the adoption signal is clean. (Production elides
  the annotations on both sides; the dev-only asymmetry is a separate
  finding — filed as an rf2 follow-up.) The app is still idiomatic
  re-frame2: app-db + a `::toggle` event + a `::show?` sub, dispatched /
  subscribed on `:rf/default`.

  ## What this proves

    1. `payload-backed-startup-adopts-server-dom-and-activates-handlers`
       — plant matching server markup + a payload script, retain the exact
       `<button>` DOM node, run the recipe, and prove (a) the SAME node
       object is still mounted afterward (`identical?` + `isConnected`) —
       i.e. `hydrate-root` ADOPTED the server DOM rather than a
       `create-root` re-render that would mint a new node — and (b)
       clicking that retained server node fires the wired `:on-click`
       handler (app-db toggles AND the adopted node's text updates in
       place). Swap the payload branch to `create-root`/`render` and both
       the identity assertion and the retained-node click fail.

    2. `client-only-startup-is-a-fresh-root` — plant `#app` WITHOUT a
       payload and prove the no-payload branch is a fresh-root path: a
       pre-existing sentinel node is DISCARDED, the app mounts fresh, and
       exactly one retained root owns the container.

  Browser-only — hydration is a real-DOM operation. The `-dom-cljs-test`
  suffix opts this file into the `:browser-test` build; the `:node-test`
  build loads it too (matches `cljs-test$`) and the assertions gate on
  `(browser?)`, no-opping under Node where `js/document` is absent.

  The recipe uses async `dispatch` (a `goog.async.nextTick` MACROTASK) and
  React commits on its own schedule, so the DOM-observing assertions run
  inside `cljs.test/async` after a `setTimeout` yield. The Playwright
  browser runner does not set `IS_REACT_ACT_ENVIRONMENT`, so renders
  commit naturally — no `act()` wrapper is needed."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [reagent.dom.client :as rdc]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.constants :as constants]
            [re-frame.test-support :as test-support]))

;; ---- the app under the recipe (unique ids ⇒ no bundle collision) -----------
;;
;; A minimal interactive app mirroring the example's shape: a button whose
;; label reads a `:show?` sub (default true ⇒ "Hide bodies") and whose
;; `:on-click` dispatches a toggle — the "prove hydration handed you a LIVE
;; app" interaction `ssr.core` uses. The app runs on `:rf/default` (the
;; example's own `app-frame`). Registered at ns-load so the reset-runtime
;; fixture folds these ids into its stable baseline.

(def ^:private app-frame :rf/default)

(rf/reg-event ::toggle
  (fn [{:keys [db]} _] {:db (update db :show? (fnil not true))}))

(rf/reg-sub ::show?
  (fn [db _] (let [v (:show? db)] (if (nil? v) true v))))

;; A PLAIN component (not `reg-view`) reads/writes through the facade with the
;; frame pinned EXPLICITLY (`{:frame app-frame}`) rather than via the ambient
;; scope: a click handler fires outside any render scope, and the facade
;; `subscribe`/`dispatch` read the dynamic ambient frame (which frame-provider
;; does NOT set — that's a React-context the injected reg-view binding reads),
;; so an explicit frame is what a plain component needs on both paths.
(defn- recipe-view []
  [:div.page
   [:button.toggle-bodies
    {:data-testid "toggle-bodies"
     :on-click    #(rf/dispatch [::toggle] {:frame app-frame})}
    (if @(rf/subscribe [::show?] {:frame app-frame}) "Hide bodies" "Show bodies")]])

;; The server markup a real server would emit for `recipe-view` with the
;; default db (`:show?` absent ⇒ true ⇒ "Hide bodies"). A plain component
;; carries no view annotations, so this hand-authored, React-canonical shape
;; byte-matches the client render ⇒ `hydrate-root` adopts it with zero
;; hydration mismatch.
(def ^:private server-markup
  (str "<div class=\"page\">"
       "<button class=\"toggle-bodies\" data-testid=\"toggle-bodies\">Hide bodies</button>"
       "</div>"))

;; ---- the recipe under test (mirror of ssr.core/run) ------------------------

(defn- run-recipe!
  "Line-for-line mirror of `examples/capabilities/ssr/ssr/core.cljc` `run`'s
  mount branch: install the stock Reagent adapter, stand up the client frame,
  READ+HYDRATE+VERIFY state via `ssr/hydrate!`, then — payload present ⇒ ADOPT
  the server DOM with `hydrate-root`; payload absent ⇒ fresh `create-root` +
  `render`. `root-atom` stands in for the example's retained `react-root`.
  Returns the payload `hydrate!` applied (nil on a client-only load)."
  [root-atom]
  (rf/init! reagent-adapter/adapter)
  (rf/make-frame {:id app-frame :platform :client})
  (let [el      (and (exists? js/document) (js/document.getElementById "app"))
        payload (ssr/hydrate! {:frame app-frame :render-tree-fn (fn [] [recipe-view])})
        tree    [rf/frame-provider {:frame app-frame} [recipe-view]]]
    (when el
      (if payload
        ;; Server-rendered: ADOPT the painted DOM (same nodes, listeners
        ;; attached, no re-paint).
        (reset! root-atom (rdc/hydrate-root el tree))
        ;; No payload: fresh root, mounted from scratch.
        (do
          (reset! root-atom (rdc/create-root el))
          (rdc/render @root-atom tree))))
    payload))

;; Async tests need the map-form fixture (a fn-form fixture's teardown races
;; the async body). `:ambient-frame nil` leaves NO pre-created frame — the
;; recipe stands up its own `:rf/default`. `:adapter` installs the STOCK
;; Reagent adapter the recipe uses (its own `rf/init!` is idempotent).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter :async? true :ambient-frame nil}))

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- DOM + recipe-state scaffolding ---------------------------------------

(defn- clear-stale-apps!
  "Remove any leftover `#app` element from a prior DOM test. The recipe reads
  `document.getElementById(\"app\")`, so a stale `#app` would shadow the one
  this test plants (getElementById returns the FIRST in document order)."
  []
  (doseq [el (array-seq (.querySelectorAll js/document "#app"))]
    (when-let [p (.-parentNode el)] (.removeChild p el))))

;; The shared `:browser-test` runner leaves `IS_REACT_ACT_ENVIRONMENT` set to
;; true from the React-hook suites (they opt in per test but never reset it).
;; With it on, renders outside `act(...)` are deferred and warn — this recipe
;; commits through React's natural schedule (hydrate is sync; the async-dispatch
;; re-render lands on a later tick), so turn it OFF for the duration and restore
;; the prior value on teardown.
(defn- disable-act-env! []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    prev))

(defn- restore-act-env! [prev]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))

(defn- plant-host!
  "Append a `<div id=\"app\">` (seeded with `inner-html`) + optionally the
  `__rf_payload` `<script>` to the live document — the exact shape the recipe
  reads via `document.getElementById(\"app\")` and the bootstrap's
  `document.getElementById(\"__rf_payload\")`. Returns the wrapper host so the
  test can remove it. The payload seeds an EMPTY `:rf/app-db` and carries NO
  `:rf/render-hash` — verify then runs but skips the comparison (server-hash
  nil), keeping the proof about DOM adoption, not hashing."
  [{:keys [inner-html payload?]}]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host)
          (str "<div id=\"app\">" inner-html "</div>"
               (when payload?
                 (str "<script id=\"" constants/payload-script-id
                      "\" type=\"application/edn\">{:rf/version 1 :rf/app-db {}}</script>"))))
    (.appendChild (.-body js/document) host)
    host))

(defn- teardown! [host root-atom act-prev]
  (when-let [root @root-atom]
    (try (.unmount root) (catch :default _ nil)))
  (reset! root-atom nil)
  (when-let [p (.-parentNode host)] (.removeChild p host))
  (restore-act-env! act-prev))

;; ---- (1) payload-backed startup ADOPTS server DOM + ACTIVATES handlers -----

(deftest payload-backed-startup-adopts-server-dom-and-activates-handlers
  (testing "The payload branch of the SSR startup recipe hydrates the
            server-rendered DOM: the exact server `<button>` node survives
            (adopted, not re-created) AND clicking that retained node fires
            the wired `:on-click` handler (rf2-byio7). Causally fails if the
            branch is changed to create-root/render."
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (clear-stale-apps!)
        (let [act-prev   (disable-act-env!)
              root-atom  (atom nil)
              host       (plant-host! {:inner-html server-markup :payload? true})
              app-el     (.getElementById js/document "app")
              ;; The EXACT server-rendered node, captured BEFORE startup.
              server-btn (.querySelector app-el "button.toggle-bodies")]
          (is (some? server-btn) "planted server `<button>` exists pre-startup")
          (is (= "Hide bodies" (.-textContent server-btn))
              "server node shows the server-rendered label")
          ;; Drive the recipe. Payload present ⇒ hydrate-root branch.
          (let [payload (run-recipe! root-atom)]
            (is (some? payload) "hydrate! read the payload ⇒ recipe took the adopt branch"))
          ;; Observe AFTER the render commits. React 18 commits create-root's
          ;; render asynchronously, so a synchronous identity check would still
          ;; see the not-yet-replaced server node and pass under BOTH branches —
          ;; the adopt-vs-replace distinction is only visible post-commit.
          (js/setTimeout
            (fn []
              ;; --- ADOPTION: the retained server node is STILL the mounted
              ;; node (hydrate-root reused it; create-root would have replaced
              ;; it with a fresh node ⇒ identical? false + detached). ---
              (let [after-btn (.querySelector app-el "button.toggle-bodies")]
                (is (identical? server-btn after-btn)
                    (str "hydrate-root ADOPTED the server DOM — the mounted "
                         "button is the SAME node object planted before startup "
                         "(a create-root re-render mints a new node ⇒ identical? "
                         "false). client-html=" (pr-str (.-innerHTML app-el))))
                (is (.-isConnected server-btn)
                    "the retained server node is still connected to the document"))
              ;; --- ACTIVATION: click the RETAINED SERVER NODE itself; the
              ;; wired :on-click must fire on it. dispatch is async (nextTick
              ;; macrotask) + the reactive re-render commits on React's
              ;; schedule, so observe after a further yield. ---
              (.click server-btn)
              (js/setTimeout
                (fn []
                  (is (= false (:show? (rf/app-db-value app-frame)))
                      "clicking the adopted server node fired the wired :on-click
                       handler — ::toggle ran and toggled app-db (handler is
                       attached + live after hydration)")
                  (is (= "Show bodies" (.-textContent server-btn))
                      "the reactive re-render updated the ADOPTED node's text in
                       place (same node, new label) — proof the handler drives
                       the server DOM, not a discarded copy")
                  (teardown! host root-atom act-prev)
                  (done))
                50))
            50))))))

;; ---- (2) client-only startup is a FRESH-ROOT path -------------------------

(deftest client-only-startup-is-a-fresh-root
  (testing "With NO payload, the recipe takes the create-root/render branch:
            a pre-existing sentinel node is DISCARDED, the app mounts fresh,
            and exactly one retained root owns the container (rf2-byio7)."
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (clear-stale-apps!)
        (let [act-prev  (disable-act-env!)
              root-atom (atom nil)
              host      (plant-host!
                         {:inner-html "<div data-testid=\"stale-sentinel\">stale</div>"
                          :payload? false})
              app-el    (.getElementById js/document "app")
              sentinel  (.querySelector app-el "[data-testid=\"stale-sentinel\"]")]
          (is (some? sentinel) "sentinel present in #app pre-startup")
          ;; No payload ⇒ fresh-root branch. A retained root is set SYNCHRONOUSLY
          ;; inside the recipe (`reset! root-atom (create-root …)`).
          (let [payload (run-recipe! root-atom)]
            (is (nil? payload) "no payload ⇒ recipe took the fresh-root branch"))
          (is (some? @root-atom)
              "one retained root owns the container after a client-only boot")
          ;; The fresh render commits on React's schedule — observe after a yield.
          (js/setTimeout
            (fn []
              (is (not (.-isConnected sentinel))
                  "the pre-existing server node was DISCARDED — a fresh root
                   replaced the container content (NOT adoption)")
              (is (some? (.querySelector app-el "button.toggle-bodies"))
                  "the app mounted fresh into the container")
              (teardown! host root-atom act-prev)
              (done))
            50))))))
