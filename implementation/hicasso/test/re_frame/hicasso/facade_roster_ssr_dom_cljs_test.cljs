(ns re-frame.hicasso.facade-roster-ssr-dom-cljs-test
  "**The facade names that had no inventory row** — dispositions.md
  §2.1 row HS-40 and §2.2 row HS-42.

  CHECKPOINT 4 read §3's second constraint — *a surface
  that reaches the facade without a row has escaped the inventory, and
  the Phase 4 exit silently stops meaning anything* — against
  `re-frame.hicasso`'s own alias block, and three public names came back
  with no row anywhere: `h/route-link`, `h/use-subs` and `h/reg-state`.
  This file is what those rows now point at. The middle one, HS-41, was
  the grouped read door; it was removed under rf2-6c12m.15 and its rows
  went with it.

  ## The roster, derived MECHANICALLY

  The facade's public roster is its three macros plus its alias block,
  and both are greppable, so no future re-run needs to read the file by
  eye. From the repository root:

      grep -nE '^   \\(defmacro [a-z]' \\
        implementation/hicasso/src/re_frame/hicasso.cljc
      grep -nE '^ +[a-z][a-zA-Z0-9!?*<>=-]* +impl-[a-z-]+/[a-zA-Z0-9!?*<>=-]+\\)+$' \\
        implementation/hicasso/src/re_frame/hicasso.cljc

  That is 3 + 12 = 15 names as of this bead. A name added tomorrow shows
  up in the second grep the day it lands, and §3's constraint says it
  owes a row.

  ## Why each of the two is here rather than covered by a neighbour

  **`h/route-link` (HS-40)** is a node OF the rendered tree with real
  server bytes — a plain function answering `[:a {:href …}]`, where the
  href is routing's own synthesis. `requirements-mine.md`'s census counts
  106 sites and licenses them to stay href-real and visible to the server
  renderer. No hicasso suite had ever server-rendered one.

  **`h/reg-state` (HS-42)** is §2.2's, not §2.1's, and this file's one
  row for it says why in the only way that is not an assumption: the
  sugar mints no node, and the value it puts on a page arrives through
  `h/sub` — so what a server render measures about it is HS-02's
  behaviour, not a behaviour of its own.

  ## What these rows deliberately do NOT claim

  A hydrating root carries the adoption closer as a SIBLING of the app
  subtree and this file's hand-rolled server path emits no counterpart,
  so a tree containing a `useId` hydrates into an id mismatch — HS-11's
  obstruction 2, measured in `identifier-prefix-ssr-dom-cljs-test` and
  unrepaired.
  **No surface in this file mints a `useId`**: `route-link` is a plain
  function that adds no hook, and `reg-state` mints registry entries
  rather than elements. Every row below is unaffected and none of them
  repairs it.

  Runtime: `-dom-cljs-test`. Sections 1 and 2 need no DOM and run under
  `:node-test` as well; section 3 says so and skips there. **The node
  lane is the one that decides the server claims** — a green browser lane
  says nothing about `renderToString`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.routing :as routing]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::roster)
(def ^:private other-frame-id ::roster-other)

(def ^:private route-id :hicasso.facade-roster/profile)

(def ^:private panel-concern
  "`reg-state`'s concern for this file. Namespaced to this suite, because
  `reg-state` refuses a re-registration under a DIFFERENT `:default` and
  the concern is a process-wide key in three registries at once."
  :hicasso.facade-roster/panel-open?)

;; ---------------------------------------------------------------------------
;; The request state
;; ---------------------------------------------------------------------------

(rf/reg-sub :hicasso.facade-roster/author (fn [db _] (:author db)))

(rf/reg-event :hicasso.facade-roster/seed
              (fn [_ [_ author]]
                {:db {:author (or author "jane")}}))

(rf/reg-event :hicasso.facade-roster/rename
              (fn [{:keys [db]} [_ a]] {:db (assoc db :author a)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

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
   (routing/reg-route route-id {} "/profile/:username")
   (h/reg-state panel-concern {:default false})
   (rf/make-frame {:id kw})
   (rf/with-frame kw (rf/dispatch-sync [:hicasso.facade-roster/seed author]))
   kw))

;; ---------------------------------------------------------------------------
;; The views — one surface per view, so a red row names a surface
;; ---------------------------------------------------------------------------

(h/defview byline
  "HS-40. One census route-link inside the boundary a link always lives
  in — the form is a plain CALL, not a hiccup head, because `route-link`
  mints no boundary and adds no hook. Its `:class` is an ordinary
  passthrough attribute, which is the control for the click-decision row:
  something on this props map does reach the bytes."
  [_]
  (let [who (h/sub [:hicasso.facade-roster/author])]
    [:p.byline
     (h/route-link {:to route-id :params {:username who} :class "author"}
                   who)]))

(h/defview panel
  "HS-42's read half. `reg-state` itself contributes nothing to a tree;
  what reaches a page is the parametric subscription it minted, read
  through the ordinary door. So the only server-observable fact about
  the sugar is that its value comes out of the request's own `app-db` —
  which is HS-02's property, measured here on an artefact `reg-state`
  registered."
  [_]
  [:div.panel
   [:span.p-state (str (h/sub [panel-concern "p1"]))]])

(h/defview page
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
  `re-frame.hicasso.server/render`; this harness sits beside it, not
  instead of it."
  ([hiccup] (server-html frame-id hiccup))
  ([kw hiccup]
   (react-dom-server/renderToString
     (mount/provider kw (codec/root-element kw hiccup)))))

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
        container (sup/stamp-server-nodes! (sup/server-dom! html))
        {:keys [seen stop!]} (sup/watch-mismatches!)
        handle    (mount/hydrate-root! container frame-id hiccup)]
    (js/setTimeout
      (fn []
        (stop!)
        (try
          (after container @seen html)
          (finally
            (mount/release! handle)
            (collector/reset-runtime!)
            (done))))
      200)))

;; ---------------------------------------------------------------------------
;; 1 — HS-40, `h/route-link` in the server bytes (no DOM)
;; ---------------------------------------------------------------------------

(deftest a-route-link-renders-a-real-anchor-with-routings-own-href
  (testing "HS-40, and §2.4's first clause. The census licenses these 106
            sites to stay href-real and visible to the server renderer,
            and this is where that stops being a licence and becomes a
            measurement: two renders of one snapshot are the same bytes,
            and the href in them is routing's synthesis rather than a
            hand-built URL. Narrowing caught: a link that deferred its
            href to a click handler, which ships an anchor a crawler and
            a middle-click cannot follow"
    (fresh!)
    (let [a (server-html [byline {}])
          b (server-html [byline {}])]
      (is (= a b) (str "two renders, one snapshot, identical bytes: " a))
      ;; Attribute ORDER is the serializer's, not the author's, so each
      ;; attribute is asserted on its own — byte-exact per attribute,
      ;; order-free between them. Measured: React emits `class` first
      ;; here although `:href` is assoc'd last.
      (is (re-find #"<a\b" a)
          (str "a REAL anchor, not a span waiting for a click: " a))
      (is (re-find #"href=\"/profile/jane\"" a)
          (str "carrying routing's own href: " a))
      (is (re-find #"class=\"author\"" a)
          (str "and the passthrough attribute reached the anchor too,
                which is the control for the row below — something on
                this props map does survive into the bytes: " a))
      (is (re-find #">jane</a>" a)
          (str "with the child inside the anchor: " a)))))

(deftest a-route-links-click-decision-is-absent-from-the-bytes
  (testing "HS-40's second half, witnessed by ABSENCE beside the href
            that IS there. The click is carried as a `[navigate-head {…}]`
            vector so `=` can see it, and none of that vocabulary is
            markup. Narrowing caught: a lowering that serialised the
            decision — which would ship the application's routing
            payload, its frame keyword and its veto to the browser as an
            attribute"
    (fresh!)
    (let [html (server-html [byline {}])]
      (is (not (re-find #"onclick|onClick" html))
          (str "no DOM event attribute on the anchor: " html))
      (is (not (re-find #"re-frame\.hicasso" html))
          (str "and no reserved keyword of any kind survived: " html))
      (is (not (re-find #"rf\.route" html))
          (str "nor the routing event the payload names: " html)))))

(deftest a-links-address-comes-from-the-request-it-was-rendered-for
  (testing "HS-40 under §2.4's snapshot clause. The frame is captured at
            RENDER and the address is computed from that request's own
            state, so two frames seeded to two requests produce two
            hrefs and neither leaks. Narrowing caught: a link resolving
            through a process-global current-frame, which renders both
            requests identically and is the exact defect a server is
            unusable with"
    (fresh! frame-id "jane")
    (fresh! other-frame-id "mary")
    (let [a (server-html frame-id [byline {}])
          b (server-html other-frame-id [byline {}])]
      (is (re-find #"/profile/jane" a) (str "frame A's request: " a))
      (is (re-find #"/profile/mary" b) (str "frame B's request: " b))
      (is (not (re-find #"mary" a)) (str "and neither leaked: " a))
      (is (not (re-find #"jane" b)) (str "in either direction: " b)))))

;; ---------------------------------------------------------------------------
;; 2 — HS-42, `h/reg-state` (no DOM)
;; ---------------------------------------------------------------------------

(deftest a-reg-state-concern-reaches-a-page-only-through-the-read-door
  (testing "HS-42, and it is a §2.2 row rather than a §2.1 one. The sugar
            mints one parametric sub and one setter event and NOTHING
            ELSE — no node, no declaration site in a rendered tree, no
            fallback arm — so it has no server-render behaviour of its
            own to disposition. What a server can observe is the artefact
            it registered, read through `h/sub`, answering the request's
            own `app-db`: the default for an instance nothing has
            written, and the written value once the request has one.
            That is HS-02's property measured on a `reg-state` artefact,
            which is exactly the claim this row makes"
    (fresh!)
    (let [before (server-html [panel {}])]
      (is (re-find #"class=\"p-state\">false</span>" before)
          (str "the unwritten instance reads the registered default: " before))
      (rf/with-frame frame-id
        (rf/dispatch-sync [panel-concern "p1" true]))
      (let [after (server-html [panel {}])]
        (is (re-find #"class=\"p-state\">true</span>" after)
            (str "and the written one reads the request's own value: " after))
        (is (not= before after)
            "so the value is the request's rather than the registration's")))))

;; ---------------------------------------------------------------------------
;; 3 — adoption, acquisition and cleanup (DOM)
;; ---------------------------------------------------------------------------

(deftest the-page-adopts-the-servers-own-nodes
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
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
            (is (sup/every-server-node? container "a")
                "the anchor is the SERVER'S node, still carrying the
                 expando — adoption, not a re-render that looks the same")
            (is (sup/every-server-node? container ".p-state")
                "as is the reg-state panel's")
            (is (= "/profile/jane"
                   (.getAttribute (query-node container "a") "href"))
                "the settled anchor keeps routing's href")
            (is (= "false" (.-textContent (query-node container ".p-state")))
                "and the concern its registered default")))))))

(deftest an-adopted-read-is-acquired-exactly-once
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (hydration-row
        [page {}]
        done
        (fn [_container _seen _html]
          (testing "§2.4's acquisition clause for the link's own `h/sub`
                    read: one reader after adoption, not two, so the
                    server render registered NONE and only the adoption
                    acquired"
            (is (= 1 (sup/readers-of [frame-id [:hicasso.facade-roster/author]]))
                (str "the link's read acquired exactly once; cells: "
                     (pr-str (sup/cell-keys))))))))))

(deftest a-deliberate-mismatch-is-attributed-to-the-root-that-owns-it
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html [page {}])
              container (sup/server-dom! html)
              {:keys [seen stop!]} (sup/watch-mismatches!)
              ;; MANUFACTURED here and asserted on here — the only shape
              ;; of call site at which swallowing an uncaught error is
              ;; not a fail-open.
              {:keys [captured close!]} (sup/open-console-capture!
                                          {:swallow-uncaught? true})]
          ;; The request the client renders is not the request the server
          ;; rendered, and the divergence lands on the LINK'S OWN
          ;; ATTRIBUTE — an href, which is the half of a route-link a
          ;; text-only mismatch check would miss.
          (rf/with-frame frame-id
            (rf/dispatch-sync [:hicasso.facade-roster/rename "mary"]))
          (let [handle (mount/hydrate-root! container frame-id [page {}])]
            (js/setTimeout
              (fn []
                (close!)
                (stop!)
                (try
                  (testing "§2.4's third clause for these rows: a
                            deliberate divergence is DETECTED and
                            ATTRIBUTED to the door that owns the
                            adoption, with the recovery React performed.
                            Narrowing caught: a diagnostic that fires
                            per-boundary rather than per-root, which
                            would read a count other than one here"
                    (is (re-find #"/profile/jane" html)
                        (str "the server bytes carried the first
                              request's href: " html))
                    (is (seq (filterv #(re-find #"Hydration failed" %) @captured))
                        (str "React itself complained: " (pr-str @captured)))
                    (is (= 1 (count @seen))
                        (str "the framework's Spec 011 diagnostic fired
                              exactly once, for this one root; got "
                             (pr-str (mapv (comp :error sup/tags-of) @seen))))
                    (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                           (:where (sup/tags-of (first @seen))))
                        "attributed to the door that owns the adoption")
                    (is (= :warned-and-replaced
                           (:recovery (sup/tags-of (first @seen))))
                        "with the recovery React had already performed")
                    (is (= "/profile/mary"
                           (.getAttribute (query-node container "a") "href"))
                        "and the repaired DOM carries the CLIENT's href,
                         which is what 'warned and replaced' means"))
                  (finally
                    (mount/release! handle)
                    (collector/reset-runtime!)
                    (done))))
              300)))))))

(deftest two-overlapping-roots-adopt-under-distinct-prefixes
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh! frame-id "jane")
        (fresh! other-frame-id "mary")
        (let [html-a (server-html frame-id [page {}])
              html-b (server-html other-frame-id [page {}])
              ca     (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb     (sup/stamp-server-nodes! (sup/server-dom! html-b))
              {:keys [seen stop!]} (sup/watch-mismatches!)
              ha     (mount/hydrate-root! ca frame-id [page {}]
                                          {:identifier-prefix "roster-a-"})
              hb     (mount/hydrate-root! cb other-frame-id [page {}]
                                          {:identifier-prefix "roster-b-"})]
          (js/setTimeout
            (fn []
              (stop!)
              (try
                (testing "§2.4's fourth clause for these rows: two roots
                          adopt at once, each under its own stable
                          `identifierPrefix` and its own frame, and
                          neither disturbs the other. Narrowing caught: a
                          process-global adoption window or a page-wide
                          current-frame — either renders both roots from
                          one request and this row reads the same href
                          twice"
                  (is (empty? @seen)
                      (str "neither adoption had anything to reconcile: "
                           (pr-str @seen)))
                  (is (= "/profile/jane" (.getAttribute (query-node ca "a") "href"))
                      "root A's link settled on its own request")
                  (is (= "/profile/mary" (.getAttribute (query-node cb "a") "href"))
                      "root B's on its own")
                  (is (sup/every-server-node? ca "a")
                      "root A adopted the server's anchor")
                  (is (sup/every-server-node? cb "a")
                      "and so did root B, concurrently")
                  (is (= 1 (sup/readers-of [frame-id [:hicasso.facade-roster/author]]))
                      (str "and each frame holds its own single edge
                            rather than one shared cell; cells: "
                           (pr-str (sup/cell-keys))))
                  (is (= 1 (sup/readers-of [other-frame-id [:hicasso.facade-roster/author]]))
                      (str "one each, in both directions; cells: "
                           (pr-str (sup/cell-keys)))))
                (finally
                  (mount/release! ha)
                  (mount/release! hb)
                  (collector/reset-runtime!)
                  (done))))
            300))))))

(deftest an-adopted-page-releases-exactly-what-it-acquired
  (if-not (mount/browser?)
    (sup/skip! ":node-test has no DOM")
    (do
      (fresh!)
      (collector/reset-runtime!)
      (testing "§2.4's last clause for these rows: exact cleanup.
                Narrowing caught: a teardown that empties the runtime's
                tables rather than releasing the subscriptions — it
                answers zero whether it released anything or not"
        (let [handle (mount/root! (mount/fresh-container!) frame-id [page {}])]
          (is (= 1 (sup/readers-of [frame-id [:hicasso.facade-roster/author]]))
              (str "the link's edge is held while mounted; cells: "
                   (pr-str (sup/cell-keys))))
          (mount/unmount! handle)
          (is (zero? (sup/readers-of [frame-id [:hicasso.facade-roster/author]]))
              (str "and it does not survive the PUBLIC teardown door; cells: "
                   (pr-str (sup/cell-keys)))))))))
