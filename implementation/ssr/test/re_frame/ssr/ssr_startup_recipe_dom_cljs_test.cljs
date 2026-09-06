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

  ## How this executes the canonical recipe (rf2-drq8s)

  The load-bearing decision of the recipe — payload present ⇒ the first
  render through the adapter's client root HYDRATES (adopts the server DOM);
  payload absent ⇒ a fresh mount — lives in ONE place: the registration-free
  helper `ssr.mount/mount!`, which passes `{:hydrate? (some? payload)}` to
  `reagent-adapter/render!` (rf2-k5r9t). `ssr.core/run` calls it, and so does
  `run-recipe!` below, so this proof executes the SAME mount code the copyable
  recipe ships — not a copy of it. Regress that decision (drop the `:hydrate?`
  option) and this proof turns red.

  `ssr.mount` is its own namespace rather than a private `defn-` in `ssr.core`
  on purpose: this test must reach the helper WITHOUT `:require`-ing `ssr.core`,
  whose app registrations (`:auth.session/store`, `:articles/loaded`, …) collide
  at image assembly with `examples/core/login` (`login.model`, already in the
  consolidated `:node-test` bundle via `example_login_success_token_cljs_test`)
  and the realworld example (`realworld-http.*`, via `realworld-cljs-test`) —
  `:rf.error/image-duplicate-id`, breaking unrelated tests bundle-wide.
  `ssr.mount` registers nothing, so loading it pulls no example ids into the
  bundle.

  Everything ABOVE the mount call is exercised the same way `ssr.core/run` does
  it — `re-frame.ssr/hydrate!` (payload read + state seed) and the stock Reagent
  adapter — against real planted server DOM.

  The view here is a PLAIN component (not `reg-view`) on the `:rf/default`
  frame — for ISOLATION, so this proof reads the adopt-vs-replace signal
  through the mount branch alone with no annotation reconciliation in the
  mix. (Historically the plain component was also NECESSARY: in a dev
  build the reagent CLIENT render of a registered view stamps
  `data-rf-view` / `data-rf2-source-coord` on the root, and the JVM
  emitter reproduced NEITHER on a callable-head view — so a registered
  view's server markup could not byte-match its dev client render. That
  asymmetry is FIXED (rf2-8vi4q moved annotation to the reg-view
  registration boundary on both hosts; a registered view now adopts
  cleanly, proven in
  `re-frame.ssr-reg-view-hydration-adoption-dom-cljs-test`). Note the
  causal correction the rf2-8vi4q browser probe established: on React
  18.3 / 19.2 an attribute-only mismatch WARNS and is left unpatched but
  does NOT replace the node — the earlier claim that React `regenerates
  the tree` on that mismatch was wrong. The node survives either way;
  what the pre-fix asymmetry cost was the clean, warning-free adoption,
  not the node.) Production elides the annotations on both hosts. The app
  is still idiomatic re-frame2: app-db + a `::toggle` event + a `::show?`
  sub, dispatched / subscribed on `:rf/default`.

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
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            ;; The canonical mount helper `ssr.core/run` itself calls. Requiring
            ;; it — NOT `ssr.core` — is what lets this proof execute the real
            ;; adopt-vs-fresh React-root branch without pulling `ssr.core`'s app
            ;; registrations (and their bundle-wide id collisions) into the test.
            [ssr.mount :as mount]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.test-support :as rf.test-support]))

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

;; ---- the recipe under test: drives the canonical mount helper --------------

(defn- run-recipe!
  "Drives the client boot the way `ssr.core/run` does and hands the mount to the
  SHARED `ssr.mount/mount!` — the single home of the adopt-vs-fresh decision
  `ssr.core/run` itself calls. Install the stock Reagent adapter, stand up the
  client frame, READ+HYDRATE+VERIFY state via `ssr/hydrate!`, then mount via
  `mount!` through the client-root `handle`: payload present ⇒ the first render
  HYDRATES and ADOPTS the server DOM; payload absent ⇒ a fresh mount. `handle`
  stands in for the example's `defonce` `app-root`. Returns the payload
  `hydrate!` applied (nil on a client-only load).

  The tree is a PLAIN component (see the ns docstring), not the recipe's own
  registered `:app/root` view: a plain, annotation-free tree keeps the
  adopt-vs-replace signal isolated from annotation reconciliation while still
  executing the canonical mount branch. (A registered view now also adopts
  cleanly — rf2-8vi4q fixed the dev-mode annotation asymmetry — but that is
  proven separately; here the point is the mount branch, not annotation
  parity.)"
  [handle]
  (rf/init! rf.adapter.reagent/adapter)
  (rf/make-frame {:id app-frame :platform :client})
  (let [el      (and (exists? js/document) (js/document.getElementById "app"))
        payload (rf.ssr/hydrate! {:frame app-frame :render-tree-fn (fn [] [recipe-view])})
        tree    [rf/frame-provider {:frame app-frame} [recipe-view]]]
    (mount/mount! handle el tree payload)
    payload))

;; Async tests need the map-form fixture (a fn-form fixture's teardown races
;; the async body). `:ambient-frame nil` leaves NO pre-created frame — the
;; recipe stands up its own `:rf/default`. `:adapter` installs the STOCK
;; Reagent adapter the recipe uses (its own `rf/init!` is idempotent).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

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
                 (str "<script id=\"" rf.ssr.constants/payload-script-id
                      "\" type=\"application/edn\">{:rf/version 1 :rf/app-db {}}</script>"))))
    (.appendChild (.-body js/document) host)
    host))

(defn- teardown! [host handle act-prev]
  ;; `unmount!` is idempotent and a no-op on a handle that never mounted.
  (try (rf.adapter.reagent/unmount! handle) (catch :default _ nil))
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
              handle     (rf.adapter.reagent/client-root)
              host       (plant-host! {:inner-html server-markup :payload? true})
              app-el     (.getElementById js/document "app")
              ;; The EXACT server-rendered node, captured BEFORE startup.
              server-btn (.querySelector app-el "button.toggle-bodies")]
          (is (some? server-btn) "planted server `<button>` exists pre-startup")
          (is (= "Hide bodies" (.-textContent server-btn))
              "server node shows the server-rendered label")
          ;; Drive the recipe. Payload present ⇒ hydrate-root branch.
          (let [payload (run-recipe! handle)]
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
                  (teardown! host handle act-prev)
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
              handle    (rf.adapter.reagent/client-root)
              host      (plant-host!
                         {:inner-html "<div data-testid=\"stale-sentinel\">stale</div>"
                          :payload? false})
              app-el    (.getElementById js/document "app")
              sentinel  (.querySelector app-el "[data-testid=\"stale-sentinel\"]")]
          (is (some? sentinel) "sentinel present in #app pre-startup")
          ;; No payload ⇒ fresh mount: the client root creates its Root
          ;; SYNCHRONOUSLY inside the recipe; the render commits on React's
          ;; schedule and is observed below.
          (let [payload (run-recipe! handle)]
            (is (nil? payload) "no payload ⇒ recipe took the fresh-root branch"))
          ;; The fresh render commits on React's schedule — observe after a yield.
          (js/setTimeout
            (fn []
              (is (not (.-isConnected sentinel))
                  "the pre-existing server node was DISCARDED — a fresh root
                   replaced the container content (NOT adoption)")
              (is (some? (.querySelector app-el "button.toggle-bodies"))
                  "the app mounted fresh into the container")
              (teardown! host handle act-prev)
              (done))
            50))))))
