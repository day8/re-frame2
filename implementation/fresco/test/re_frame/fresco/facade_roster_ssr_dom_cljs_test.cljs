(ns re-frame.fresco.facade-roster-ssr-dom-cljs-test
  "**Two facade names and their inventory rows** — dispositions.md
  §2.1 row HS-40 (`h/route-link`) and §2.2 row HS-42 (`h/reg-state`).

  §3's second constraint says *a surface that reaches the facade without
  a row has escaped the inventory, and the Phase 4 exit silently stops
  meaning anything*. Read against `re-frame.fresco`'s own alias block,
  these two public names owe rows, and this file is the witness those
  rows point at.

  ## The roster, derived MECHANICALLY

  The facade's public roster is its three macros plus its alias block,
  and both are greppable, so no future re-run needs to read the file by
  eye. From the repository root:

      grep -nE '^   \\(defmacro [a-z]' \\
        implementation/fresco/src/re_frame/fresco.cljc
      grep -nE '^ +[a-z][a-zA-Z0-9!?*<>=-]* +rf\\.fresco\\.impl\\.[a-z-]+/[a-zA-Z0-9!?*<>=-]+\\)+$' \\
        implementation/fresco/src/re_frame/fresco.cljc

  That is 3 + 12 = 15 names. A name added tomorrow shows
  up in the second grep the day it lands, and §3's constraint says it
  owes a row.

  ## Why each of the two is here rather than covered by a neighbour

  **`h/route-link` (HS-40)** is a node OF the rendered tree with real
  server bytes — a plain function answering `[:a {:href …}]`, where the
  href is routing's own synthesis. `requirements-mine.md`'s census counts
  106 sites and licenses them to stay href-real and visible to the server
  renderer, and this file server-renders one.

  **`h/reg-state` (HS-42)** is §2.2's, not §2.1's: the sugar mints no
  node, and the value it puts on a page arrives through `h/sub` — so what
  a server render measures about it is HS-02's behaviour, not a
  behaviour of its own.

  ## What these rows deliberately do NOT claim

  A hydrating root carries the adoption closer as a SIBLING of the app
  subtree and this file's hand-rolled server path emits no counterpart,
  so a tree containing a `useId` hydrates into an id mismatch — HS-11's
  obstruction 2, measured in `server-render-ssr-dom-cljs-test` §4b-3 and
  repaired by `re-frame.fresco.server/render`, whose bytes carry the fork.
  **No surface in this file mints a `useId`**: `route-link` is a plain
  function that adds no hook, and `reg-state` mints registry entries
  rather than elements. Every row below is unaffected by it.

  Runtime: `-dom-cljs-test`. The adoption row needs a real React DOM, so
  `:node-test` says so and skips it."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.routing :as rf.routing]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::roster)

(def ^:private route-id :fresco.facade-roster/profile)

(def ^:private panel-concern
  "`reg-state`'s concern for this file. Namespaced to this suite, because
  `reg-state` refuses a re-registration under a DIFFERENT `:default` and
  the concern is a process-wide key in three registries at once."
  :fresco.facade-roster/panel-open?)

;; ---------------------------------------------------------------------------
;; The request state
;; ---------------------------------------------------------------------------

(rf/reg-sub :fresco.facade-roster/author (fn [db _] (:author db)))

(rf/reg-event :fresco.facade-roster/seed
              (fn [_ [_ author]]
                {:db {:author (or author "jane")}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(defn- fresh!
  "A frame seeded to a known request, with the route table and the state
  concern registered.

  Both registrations sit HERE rather than at the top level because the
  runtime reset between rows clears the routing artefact's own table.
  Re-registering the concern under the SAME `:default` is the no-op
  refresh `reg-state` documents, so the repetition is free."
  ([] (fresh! frame-id "jane"))
  ([kw author]
   (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
   (rf.routing/reg-route route-id {} "/profile/:username")
   (rf.fresco/reg-state panel-concern {:default false})
   (rf/make-frame {:id kw})
   (rf/with-frame kw (rf/dispatch-sync [:fresco.facade-roster/seed author]))
   kw))

;; ---------------------------------------------------------------------------
;; The views — one surface per view, so a red row names a surface
;; ---------------------------------------------------------------------------

(rf.fresco/defview byline
  "HS-40. One census route-link inside the boundary a link always lives
  in — the form is a plain CALL, not a hiccup head, because `route-link`
  mints no boundary and adds no hook."
  [_]
  (let [who (rf.fresco/sub [:fresco.facade-roster/author])]
    [:p.byline
     (rf.fresco/route-link {:to route-id :params {:username who} :class "author"}
                   who)]))

(rf.fresco/defview panel
  "HS-42's read half. `reg-state` itself contributes nothing to a tree;
  what reaches a page is the parametric subscription it minted, read
  through the ordinary door. So the only server-observable fact about
  the sugar is that its value comes out of the request's own `app-db` —
  which is HS-02's property."
  [_]
  [:div.panel
   [:span.p-state (str (rf.fresco/sub [panel-concern "p1"]))]])

(rf.fresco/defview page
  "The hydration rows' page: both surfaces in one tree, so ONE adoption
  covers HS-40 and HS-42 rather than leaving them witnessed on the server
  side alone."
  [_]
  [:div.page
   [byline {}]
   [panel {}]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- server-html
  "The page as a REAL server render, through `react-dom/server`'s own
  `renderToString`, called by hand — the smallest thing that produces
  server bytes, so what these rows read is the surface's own server
  behaviour and nothing else. The package's own server door is
  `re-frame.fresco.server/render`; this harness sits beside it, not
  instead of it."
  ([hiccup] (server-html frame-id hiccup))
  ([kw hiccup]
   (react-dom-server/renderToString
     (rf.fresco.impl.mount/provider kw (rf.fresco.impl.codec/root-element kw hiccup)))))

(defn- query-node [root selector] (.querySelector root selector))

(defn- hydration-row
  "Bake the page on the server, adopt THOSE BYTES through the product
  door, and hand `after` the container, what the framework reported, and
  the HTML that was hydrated from.

  **The row owns `done`; `after` is assertion-only** — `cljs.test`
  continues the run synchronously from the `done` call, so a row that
  called it from inside its assertions would leave this root standing
  under every namespace that followed. Assert, tear down, `done` last."
  [hiccup done after]
  (fresh!)
  (let [html      (server-html hiccup)
        container (rf.fresco.roots-frames-support/stamp-server-nodes! (rf.fresco.roots-frames-support/server-dom! html))
        {:keys [seen stop!]} (rf.fresco.roots-frames-support/watch-mismatches!)
        handle    (rf.fresco.impl.mount/hydrate-root! container frame-id hiccup)]
    (js/setTimeout
      (fn []
        (stop!)
        (try
          (after container @seen html)
          (finally
            (rf.fresco.impl.mount/release! handle)
            (rf.fresco.impl.collector/reset-runtime!)
            (done))))
      200)))

;; ---------------------------------------------------------------------------
;; 1 — adoption (DOM)
;; ---------------------------------------------------------------------------

(deftest the-page-adopts-the-servers-own-nodes
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (rf.fresco.roots-frames-support/skip! ":node-test has no DOM") (done))
      (hydration-row
        [page {}]
        done
        (fn [container seen html]
          (testing "§2.4's second clause for both rows at once: the
                    client's first pass rendered what the server did, so
                    the two agreed by construction and React found
                    nothing to reconcile"
            (is (re-find #"href=\"/profile/jane\"" html)
                (str "the markup hydrated FROM carried the link's own
                      bytes, so a row reading only the settled DOM could
                      not tell adoption from a fresh mount: " html))
            (is (empty? seen)
                (str "**REACT FOUND NOTHING TO RECONCILE**: " (pr-str seen)))
            (is (rf.fresco.roots-frames-support/every-server-node? container "a")
                "the anchor is the SERVER'S node, still carrying the
                 expando — adoption, not a re-render that looks the same")
            (is (rf.fresco.roots-frames-support/every-server-node? container ".p-state")
                "as is the reg-state panel's")
            (is (= "/profile/jane"
                   (.getAttribute (query-node container "a") "href"))
                "the settled anchor keeps routing's href")
            (is (= "false" (.-textContent (query-node container ".p-state")))
                "and the concern its registered default")))))))
