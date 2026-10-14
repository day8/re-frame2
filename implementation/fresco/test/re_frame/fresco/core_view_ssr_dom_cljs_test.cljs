(ns re-frame.fresco.core-view-ssr-dom-cljs-test
  "**The interpreted tier under `react-dom/server`** — dispositions.md
  §2.1 rows HS-01 to HS-09, which is every core view surface an
  application writes before it reaches a host, an escape or a module.

  Those nine rows all carry a **Render** disposition, and nothing else
  server-renders a `h/defview`: this file is the witness each of them
  cites. It runs the surfaces through `react-dom/server`'s own
  `renderToString`, called by hand — the smallest thing that produces
  server bytes, so what these rows read is the surface's own server
  behaviour and nothing else — and then hydrates those same bytes
  through the product door. The package's own server door is
  `re-frame.fresco.server/render`; this harness sits beside it, not
  instead of it.

  ## What a row here has to show, and why the list is not negotiable

  §2.4 fixes the upgrade: deterministic bytes from an immutable request
  snapshot, matching hydration, a deliberate mismatch attributed to
  source, two simultaneous hydrating roots with a stable
  `identifierPrefix`, and exact cleanup on unmount — plus, for a surface
  that reads, no duplicate acquisition. Sections 1 to 3 below are that
  list in order, except the two-root and exact-cleanup clauses, which
  `identifier-prefix-ssr-dom-cljs-test`'s two-root row holds; no row in
  this file asserts a policy the list has not been walked for.

  ## The one thing these rows deliberately do NOT claim

  A hydrating root's tree is `Fragment[closer, adoption-provider[…]]`
  (`impl.mount/tree`) and this file's hand-rolled server path emits no
  counterpart to that fork, so a tree containing a `useId` hydrates into
  an id mismatch — HS-11's obstruction 2, measured in
  `server-render-ssr-dom-cljs-test` §4b-3 and repaired by
  `re-frame.fresco.server/render`, whose bytes carry the fork. **No
  surface in this file mints a `useId`**, so every row below is
  unaffected by it. What these rows establish is that the interpreted
  tier's own bytes and its own adoption are sound, which is a different
  claim and a smaller one.

  Runtime: `-dom-cljs-test`. Sections 0 to 1 need no DOM and run under
  `:node-test` as well; sections 2 and 3 say so and skip there. **The
  node lane is the one that decides the server claims** — a green
  browser lane says nothing about `renderToString`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::core-ssr)
(def ^:private other-frame-id ::core-ssr-other)

;; ---------------------------------------------------------------------------
;; The request state
;; ---------------------------------------------------------------------------

(rf/reg-sub :fresco.core-ssr/title (fn [db _] (:title db)))
(rf/reg-sub :fresco.core-ssr/draft (fn [db _] (:draft db)))
(rf/reg-sub :fresco.core-ssr/done? (fn [db _] (:done? db)))

;; Read ONLY from the branch a `:done?` of false does not take. The
;; acquisition rows need a key whose reader count answers *did that
;; branch run*, and a key the page reads unconditionally cannot.
(rf/reg-sub :fresco.core-ssr/badge (fn [db _] (:badge db)))

(rf/reg-event :fresco.core-ssr/seed
              (fn [_ [_ title]]
                {:db {:title (or title "quarterly")
                      :draft "draft-a"
                      :done? false
                      :badge "shipped"}}))

(rf/reg-event :fresco.core-ssr/retitle
              (fn [{:keys [db]} [_ t]] {:db (assoc db :title t)}))

(rf/reg-event :fresco.core-ssr/finish
              (fn [{:keys [db]} _] {:db (assoc db :done? true)}))

(rf/reg-event :fresco.core-ssr/edit (fn [{:keys [db]} _] {:db db}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "a hydration claim needs a real React DOM — " why)))

(defn- fresh!
  "A frame seeded to a known request. `title` is the request's one
  variable, so two frames can be seeded to two different requests and a
  row can say which bytes came from which."
  ([] (fresh! frame-id "quarterly"))
  ([kw title]
   (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
   (rf/make-frame {:id kw})
   (rf/with-frame kw (rf/dispatch-sync [:fresco.core-ssr/seed title]))
   kw))

;; ---------------------------------------------------------------------------
;; The views — one surface per view, so a red row names a surface
;; ---------------------------------------------------------------------------

(rf.fresco/defview article
  "HS-01 and HS-02. A boundary whose whole content is subscription
  reads, one of them behind a `when` — because §2.4's snapshot clause is
  about *conditional* reads as much as unconditional ones, and a page
  that only ever reads straight-line has not shown that a branch not
  taken contributes nothing."
  [_]
  [:article.article
   [:h1.title (rf.fresco/sub [:fresco.core-ssr/title])]
   (when (rf.fresco/sub [:fresco.core-ssr/done?])
     [:span.badge (rf.fresco/sub [:fresco.core-ssr/badge])])])

(rf.fresco/defview chrome
  "HS-04 and HS-05. The intrinsic head in its three shapes — an HTML
  element, an SVG subtree, and a custom element — under a fragment head
  that must contribute no wrapper of its own. The two bare strings are
  §2.4's *adjacent text* clause: React separates them with a comment
  marker so that hydration can find the boundary between two text nodes,
  and a serializer that concatenated them would produce bytes that
  hydrate into a mismatch."
  [_]
  [:<>
   [:p.adjacent "alpha" "beta"]
   [:svg.mark {:viewBox "0 0 10 10" :xmlns "http://www.w3.org/2000/svg"}
    [:path {:d "M0 0 L10 10" :stroke-width 2 :stroke "currentColor"}]]
   [:my-widget {:data-kind "custom" :aria-label "widget"} "inside"]])

(rf.fresco/defview intents
  "HS-03 and HS-07. Every intent spelling the grammar has — a literal
  vector, the `::h/prevent` decorator head, and the `::h/value` and
  `::h/checked` placeholders — plus `::h/revision`, the controlled
  element's reset trigger. **None of them is an attribute**, and the
  server bytes are where that stops being a claim about the client: a
  lowering that let any of these through would ship the application's
  event vocabulary to the browser as markup."
  [_]
  [:form.intents {:on-submit [::rf.fresco/prevent [:fresco.core-ssr/edit]]}
   ;; CONTROLLED, and it has to be: `::h/revision` re-baselines a
   ;; controlled field to its model, so `impl.controlled/install!`
   ;; refuses it on a field with no `:value` to re-baseline TO;
   ;; [[a-revision-on-an-uncontrolled-field-is-refused-at-source]] pins
   ;; that refusal as a row.
   [:input.text {:type        "text"
                 :value       (rf.fresco/sub [:fresco.core-ssr/draft])
                 :on-change   [:fresco.core-ssr/edit ::rf.fresco/value]
                 ::rf.fresco/revision 7}]
   [:input.check {:type "checkbox" :defaultChecked true
                  :on-change [:fresco.core-ssr/edit ::rf.fresco/checked]}]
   [:a.link {:href "#x" :on-click [::rf.fresco/prevent [:fresco.core-ssr/finish]]} "veto"]
   [:button.go {:on-click [:fresco.core-ssr/finish]} "go"]])

(rf.fresco/defview bad-revision
  "`::h/revision` on an UNCONTROLLED field — `:defaultValue`, so there is
  no model to re-baseline to. The refusal this shape draws is HS-07's
  own, and it fires during the SERVER render, which is the half worth
  pinning: a reserved key whose validation ran on the client only would
  ship this page and fail at adoption."
  [_]
  [:div.bad
   [:input {:type "text" :defaultValue "x" ::rf.fresco/revision 7}]])

(rf.fresco/defview controls
  "HS-08 as a class. Section 2.3 dispositions each control type for the
  controlled-field law; this row is the *server* half §2.1 owns — the
  value a control carries has to be IN the bytes, or the page paints
  empty and fills in after adoption, which is the flash the whole
  contract exists to prevent."
  [_]
  [:div.controls
   [:input.c-text {:type "text" :value (rf.fresco/sub [:fresco.core-ssr/draft])
                   :on-change [:fresco.core-ssr/edit ::rf.fresco/value]}]
   [:input.c-check {:type "checkbox" :checked true
                    :on-change [:fresco.core-ssr/edit ::rf.fresco/checked]}]
   [:textarea.c-area {:value "area-text"
                      :on-change [:fresco.core-ssr/edit ::rf.fresco/value]}]
   [:select.c-select {:value "b" :on-change [:fresco.core-ssr/edit ::rf.fresco/value]}
    [:option {:value "a"} "A"]
    [:option {:value "b"} "B"]]])

(rf.fresco/defview guarded
  "HS-09, the succeeding arm: `h/error-boundary` around a child that
  does not throw contributes its child's output and no wrapper element
  of its own."
  [_]
  [:div.guarded
   [rf.fresco/error-boundary {:fallback [:p.fellback "fell back"]}
    [:p.kid (rf.fresco/sub [:fresco.core-ssr/title])]]])

(rf.fresco/defview exploding
  "HS-09, the throwing arm. React is explicit that a CLIENT error
  boundary does not catch a SERVER rendering error, so the declared
  `:fallback` is not what stands in the bytes — the render fails. A row
  that expected the fallback would be asserting a React behaviour that
  does not exist."
  [_]
  [:div.guarded
   [rf.fresco/error-boundary {:fallback [:p.fellback "fell back"]}
    [:p.kid (throw (js/Error. "server render exploded"))]]])

(rf.fresco/defview page
  "The hydration rows' page: the reading boundary, the intrinsic chrome
  and the intent grammar in one tree, so ONE adoption covers HS-01 to
  HS-07 rather than leaving most of them witnessed on the server side
  alone."
  [_]
  [:div.page
   [article {}]
   [chrome {}]
   [intents {}]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- server-html
  "The page as a REAL server render, through `react-dom/server`'s own
  `renderToString`."
  ([hiccup] (server-html frame-id hiccup))
  ([kw hiccup]
   (react-dom-server/renderToString
     (rf.fresco.impl.mount/provider kw (rf.fresco.impl.codec/root-element kw hiccup)))))

(defn- page-bytes
  "`server-html` with Spec 006's dev-mode view annotations taken out — for
  the rows whose subject is the PAGE: an exact opening tag, or vocabulary
  that must not reach the markup.

  A separate door rather than a change to `server-html` above, because
  the hydration rows must keep reading the REAL bytes: they adopt what
  they baked, and a client render annotates too, so stripping one side
  would manufacture the mismatch those rows exist to rule out."
  [hiccup]
  (rf.fresco.roots-frames-support/without-view-annotations (server-html hiccup)))

(defn- query-node [root selector] (.querySelector root selector))

(defn- hydration-row
  "Bake the page on the server, adopt THOSE BYTES through the product
  door, and hand `after` the container, what the framework reported, and
  the HTML that was hydrated from.

  **The row owns `done`; `after` is assertion-only.** `cljs.test`'s
  `run-block` continues the remainder of the run synchronously from the
  `done` call, so a row that called `done` from inside its assertions
  would leave this root and this container standing under every
  namespace that followed and run its teardown only when that whole
  continuation returned. The order here — assert, tear down, `done` last
  — is the shape that avoids it, and the native tier's suite follows the
  same one.

  Adoption is read through an EXPANDO stamped before hydration
  (`sup/stamp-server-nodes!`), which does not round-trip through
  `innerHTML`: a node still answering to it is the very node the server
  markup produced, rather than a replacement that looks identical in the
  DOM and in the assertion."
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
;; 0 — deterministic bytes from an immutable request snapshot (no DOM)
;; ---------------------------------------------------------------------------

(deftest a-sub-read-answers-the-request-snapshot
  (testing "HS-02. The read is of the frame the render names, so two
            frames seeded to two different requests produce two different
            responses, and a render leaves nothing behind for the next.
            Narrowing caught: a read resolving through a process-global
            current-frame, or state a render leaves in the arm (a memo
            keyed on nothing, a retained snapshot)"
    (fresh! frame-id "quarterly")
    (fresh! other-frame-id "annual")
    (let [a (server-html frame-id [article {}])
          b (server-html other-frame-id [article {}])]
      (is (re-find #"quarterly" a) (str "frame A's request: " a))
      (is (re-find #"annual" b) (str "frame B's request: " b))
      (is (not (re-find #"annual" a)) (str "and neither leaked: " a))
      (is (not (re-find #"quarterly" b)) (str "in either direction: " b))
      (is (= a (server-html frame-id [article {}]))
          "request A answers byte-identically after request B ran between"))))

;; ---------------------------------------------------------------------------
;; 1 — the grammar in the bytes (no DOM)
;; ---------------------------------------------------------------------------

(deftest the-intent-vocabulary-never-reaches-the-bytes
  (testing "HS-03 and HS-07, and the strongest single reason the server
            half of this tier needs a witness at all. Intents are DATA
            — vectors, placeholder keywords and a decorator head — and
            data is exactly the kind of thing a serializer emits. Every
            spelling the grammar has is in [[intents]]; none of them may
            appear in the response, as an attribute or as text.
            Narrowing caught: a lowering that ran on the client only,
            leaving the raw vectors to be stringified on the server"
    (fresh!)
    (let [html (page-bytes [intents {}])]
      (is (re-find #"<form class=\"intents\">" html)
          (str "the form is there, so the row is about what was OMITTED
                from a real render: " html))
      (is (not (re-find #"(?i)onclick|onsubmit|oninput|onchange" html))
          (str "no DOM event attribute — React never emits handler props,
                and an intent must not sneak in as one: " html))
      (is (not (re-find #"fresco" html))
          (str "and no reserved keyword survived into the markup:
                `::h/value`, `::h/checked`, `::h/prevent` and
                `::h/revision` all carry the `re-frame.fresco`
                namespace, so one match here catches any of them: " html))
      (is (not (re-find #"on-click|on-input|on-submit|on-change" html))
          (str "nor did the authoring spelling reach the bytes as an
                attribute name: " html))
      (is (not (re-find #"revision" html))
          (str "`::h/revision` is read off the author's own pre-merge map
                and is never an attribute: " html))
      (is (re-find #"href=\"#x\"" html)
          (str "while an ordinary attribute beside a vetoed intent is
                untouched — the control that keeps every assertion above
                from passing on an empty string: " html)))))

(deftest a-revision-on-an-uncontrolled-field-is-refused-at-source
  (testing "HS-07's refusal arm: `::h/revision` on a `:defaultValue`
            field is refused. It is a row because the reserved
            vocabulary's validation has to run on the SERVER too — one
            that ran on the client alone would let this page be baked and
            would fail only at adoption, which is the most expensive place
            to find it"
    (fresh!)
    (let [e (try (server-html [bad-revision {}]) nil
                 (catch :default e e))
          d (ex-data e)]
      (is (some? e) "the render refused rather than emitting the field")
      (is (= :rf.error/fresco-revision-not-controlled (:rf.error/id d))
          (str "with the id that names the mistake: " (pr-str d)))
      (is (= 're-frame.fresco.core-view-ssr-dom-cljs-test/bad-revision
             (symbol (:view d)))
          (str "attributed to the AUTHOR'S boundary — not to the codec
                that noticed — which is the whole of `at source`: "
               (pr-str (:view d)))))))

(deftest the-controlled-fields-carry-their-values
  (testing "HS-08's server half. A control's value is in the response, so
            the page paints filled rather than empty-then-filled.
            Narrowing caught: emitting the controlled value as a DOM
            property only — invisible on the server, and the flash the
            contract exists to prevent"
    (fresh!)
    (let [html (server-html [controls {}])]
      (is (re-find #"class=\"c-text\"[^>]*value=\"draft-a\"" html)
          (str "the text input's value: " html))
      (is (re-find #"class=\"c-check\"[^>]*checked=\"\"" html)
          (str "the checkbox's checked state, as HTML's valueless
                attribute: " html))
      (is (re-find #"area-text</textarea>" html)
          (str "the textarea's value as its CHILD, which is where HTML
                puts it and where React's server renderer puts it: "
               html))
      (is (re-find #"<option value=\"b\" selected=\"\">B</option>" html)
          (str "and the select's value as `selected` on the chosen
                option, not as an attribute on the select: " html)))))

;; ---------------------------------------------------------------------------
;; 1b — the error boundary's two server arms (no DOM)
;; ---------------------------------------------------------------------------

(deftest an-error-boundary-contributes-its-child-and-no-wrapper
  (testing "HS-09's succeeding arm"
    (fresh!)
    (let [html (page-bytes [guarded {}])]
      (is (re-find #"<div class=\"guarded\"><p class=\"kid\">quarterly</p></div>" html)
          (str "the child's output, immediately inside the boundary's
                parent — the boundary is a component and contributes no
                element: " html))
      (is (not (re-find #"fellback" html))
          (str "and the fallback is nowhere near the bytes: " html)))))

(deftest a-server-render-error-is-not-caught-by-the-client-boundary
  (testing "HS-09's throwing arm, and it is a REFUSAL row rather than a
            fallback row. React is explicit that client error boundaries
            do not catch server rendering errors, so the declared
            `:fallback` is not what stands in the response: the render
            fails and the caller — the consumer's own `renderToString`
            call — is where it surfaces. A row that asserted the fallback
            would be asserting a React behaviour that does not exist, and
            would have shipped a page whose error path was never the one
            it was tested on"
    (fresh!)
    (is (thrown-with-msg? js/Error #"server render exploded"
                          (server-html [exploding {}]))
        "the throw reaches the caller of `renderToString`")))

;; ---------------------------------------------------------------------------
;; 2 — hydration (DOM)
;; ---------------------------------------------------------------------------

(deftest the-page-adopts-the-servers-own-nodes
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [page {}]
        done
        (fn [container seen html]
          (is (re-find #"class=\"title\"" html)
              (str "the markup hydrated FROM carried the boundary's
                    output, so a row that read only the settled DOM
                    could not tell adoption from a fresh mount: " html))
          (is (empty? seen)
              (str "**REACT FOUND NOTHING TO RECONCILE.** The client's
                    first pass rendered what the server did, so the two
                    agreed by construction: " (pr-str seen)))
          (is (rf.fresco.roots-frames-support/every-server-node? container ".title")
              "and the title is the SERVER'S node, still carrying the
               expando — adoption, not a re-render that looks the same")
          (is (rf.fresco.roots-frames-support/every-server-node? container ".adjacent")
              "as is the adjacent-text paragraph, which is the node the
               comment separator exists for")
          (is (rf.fresco.roots-frames-support/every-server-node? container "my-widget")
              "and the custom element, whose head React does not know")
          (is (= "quarterly" (.-textContent (query-node container ".title")))
              "carrying the request's value"))))))

(deftest a-deliberate-mismatch-is-attributed-to-the-root-that-owns-it
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html [page {}])
              container (rf.fresco.roots-frames-support/server-dom! html)
              {:keys [seen stop!]} (rf.fresco.roots-frames-support/watch-mismatches!)
              ;; MANUFACTURED here and asserted on here — the only shape
              ;; of call site at which swallowing an uncaught error is
              ;; not the fail-open the pageerror rule forbids.
              {:keys [captured close!]} (rf.fresco.roots-frames-support/open-console-capture!
                                          {:swallow-uncaught? true})]
          ;; The request the client renders is not the request the server
          ;; rendered — the divergence a stale cache or a clock produces.
          (rf/with-frame frame-id
            (rf/dispatch-sync [:fresco.core-ssr/retitle "annual"]))
          (let [handle (rf.fresco.impl.mount/hydrate-root! container frame-id [page {}])]
            (js/setTimeout
              (fn []
                (close!)
                (stop!)
                (try
                  (testing "§2.4's third clause for the interpreted tier:
                            a deliberate divergence is DETECTED and
                            ATTRIBUTED to the door that owns the
                            adoption, with the recovery React performed.
                            Narrowing caught: a diagnostic that fires
                            per-boundary rather than per-root, which
                            would read a count other than one here"
                    (is (re-find #"quarterly" html)
                        (str "the server bytes carried the first
                              request's value: " html))
                    (is (seq (filterv #(re-find #"Hydration failed" %) @captured))
                        (str "React itself complained: " (pr-str @captured)))
                    (is (= 1 (count @seen))
                        (str "the framework's Spec 011 diagnostic fired
                              exactly once, for this one root; got "
                             (pr-str (mapv (comp :error rf.fresco.roots-frames-support/tags-of) @seen))))
                    (is (= 're-frame.fresco.impl.mount/hydrate-root!
                           (:where (rf.fresco.roots-frames-support/tags-of (first @seen))))
                        "attributed to the door that owns the adoption")
                    (is (= :warned-and-replaced
                           (:recovery (rf.fresco.roots-frames-support/tags-of (first @seen))))
                        "with the recovery React had already performed")
                    (is (= "annual" (.-textContent (query-node container ".title")))
                        "and the repaired DOM carries the CLIENT's value,
                         which is what 'warned and replaced' means"))
                  (finally
                    (rf.fresco.impl.mount/release! handle)
                    (rf.fresco.impl.collector/reset-runtime!)
                    (done))))
              300)))))))

;; ---------------------------------------------------------------------------
;; 3 — acquisition (DOM)
;; ---------------------------------------------------------------------------

(deftest a-server-render-acquires-no-reader-and-an-adoption-acquires-one
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [page {}]
        done
        (fn [_container _seen _html]
          (is (= 1 (rf.fresco.roots-frames-support/readers-of [frame-id [:fresco.core-ssr/title]]))
              (str "exactly ONE reader after adoption. The server render
                    ran the same body and registered NONE — there is no
                    subscription to release on a server, and a tier that
                    acquired one there would leak a cell per request —
                    so a count of two would mean both halves acquired
                    and the server's was never released; cells: "
                   (pr-str (rf.fresco.roots-frames-support/cell-keys))))
          (is (zero? (rf.fresco.roots-frames-support/readers-of [frame-id [:fresco.core-ssr/badge]]))
              (str "and the key only the untaken branch reads has no
                    reader at all, on either side: "
                   (pr-str (rf.fresco.roots-frames-support/cell-keys)))))))))
