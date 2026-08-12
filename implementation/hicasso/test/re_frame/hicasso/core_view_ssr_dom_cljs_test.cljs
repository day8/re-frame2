(ns re-frame.hicasso.core-view-ssr-dom-cljs-test
  "**The interpreted tier under `react-dom/server`** — dispositions.md
  §2.1 rows HS-01 to HS-09, which is every core view surface an
  application writes before it reaches a host, an escape or a module.

  Those nine rows all carry a **Render** target and all read
  *Client-only — refusal until rf2-hic-046* in the operative column, and
  they read that for one reason: no witness had ever server-rendered a
  `h/defview`. This file is that witness. It runs the surfaces through
  `react-dom/server`'s own `renderToString` — which per rf2-ggnp's
  census is the only server path this package has, and is exactly the
  call a consumer makes — and then hydrates those same bytes through the
  product door.

  ## What a row here has to show, and why the list is not negotiable

  §2.4 fixes the upgrade: deterministic bytes from an immutable request
  snapshot, matching hydration, a deliberate mismatch attributed to
  source, two simultaneous hydrating roots with a stable
  `identifierPrefix`, and exact cleanup on unmount — plus, for a surface
  that reads, no duplicate acquisition. Sections 1 to 3 below are that
  list in order, and no row in this file asserts a policy the list has
  not been walked for.

  ## The one thing these rows deliberately do NOT claim

  A hydrating root's tree is `Fragment[closer, adoption-provider[…]]`
  (`impl.mount/tree`, rf2-6tmu) and the server path emits no counterpart
  to that fork, so a tree containing a `useId` hydrates into an id
  mismatch — HS-11's obstruction 2, measured in
  `identifier-prefix-ssr-dom-cljs-test` and unrepaired. **No surface in
  this file mints a `useId`**, so every row below is unaffected and none
  of them repairs it either. That obstruction is HS-11/HS-14's and it
  stays open; what these rows establish is that the interpreted tier's
  own bytes and its own adoption are sound, which is a different claim
  and a smaller one.

  Runtime: `-dom-cljs-test`. Sections 0 to 1 need no DOM and run under
  `:node-test` as well; sections 2 and 3 say so and skip there. **The
  node lane is the one that decides the server claims** — a green
  browser lane says nothing about `renderToString`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::core-ssr)
(def ^:private other-frame-id ::core-ssr-other)

;; ---------------------------------------------------------------------------
;; The request state
;; ---------------------------------------------------------------------------

(rf/reg-sub :hicasso.core-ssr/title (fn [db _] (:title db)))
(rf/reg-sub :hicasso.core-ssr/draft (fn [db _] (:draft db)))
(rf/reg-sub :hicasso.core-ssr/done? (fn [db _] (:done? db)))

;; Read ONLY from the branch a `:done?` of false does not take. The
;; acquisition rows need a key whose reader count answers *did that
;; branch run*, and a key the page reads unconditionally cannot.
(rf/reg-sub :hicasso.core-ssr/badge (fn [db _] (:badge db)))

(rf/reg-event :hicasso.core-ssr/seed
              (fn [_ [_ title]]
                {:db {:title (or title "quarterly")
                      :draft "draft-a"
                      :done? false
                      :badge "shipped"}}))

(rf/reg-event :hicasso.core-ssr/retitle
              (fn [{:keys [db]} [_ t]] {:db (assoc db :title t)}))

(rf/reg-event :hicasso.core-ssr/finish
              (fn [{:keys [db]} _] {:db (assoc db :done? true)}))

(rf/reg-event :hicasso.core-ssr/edit (fn [{:keys [db]} _] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

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
   (rf/with-frame kw (rf/dispatch-sync [:hicasso.core-ssr/seed title]))
   kw))

;; ---------------------------------------------------------------------------
;; The views — one surface per view, so a red row names a surface
;; ---------------------------------------------------------------------------

(h/defview article
  "HS-01 and HS-02. A boundary whose whole content is subscription
  reads, one of them behind a `when` — because §2.4's snapshot clause is
  about *conditional* reads as much as unconditional ones, and a page
  that only ever reads straight-line has not shown that a branch not
  taken contributes nothing."
  [_]
  [:article.article
   [:h1.title (h/sub [:hicasso.core-ssr/title])]
   (when (h/sub [:hicasso.core-ssr/done?])
     [:span.badge (h/sub [:hicasso.core-ssr/badge])])])

(h/defview chrome
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

(h/defview slots
  "HS-06 and HS-15. One canonical slot per value however the key was
  written — kebab keyword, camel keyword and string all name the same
  React prop — and the caller's remainder merged under `:&`, where the
  literal key written in the map wins (HD-023). The merge helper has no
  separate public spelling, so HS-15 is witnessed here, at the `:&`
  seam, which is the only place it is reachable from."
  [{:keys [extra]}]
  [:div.slots
   [:label {:htmlFor "field" :class "lbl"} "label"]
   [:input {:id "field" :type "text" :readOnly true
            :defaultValue "typed"
            :& extra}]
   [:span {:style {:font-weight 700 :margin-top 4}} "styled"]])

(h/defview intents
  "HS-03 and HS-07. Every intent spelling the grammar has — a literal
  vector, the `::h/prevent` decorator head, and the `::h/value` and
  `::h/checked` placeholders — plus `::h/revision`, the controlled
  element's reset trigger. **None of them is an attribute**, and the
  server bytes are where that stops being a claim about the client: a
  lowering that let any of these through would ship the application's
  event vocabulary to the browser as markup."
  [_]
  [:form.intents {:on-submit [::h/prevent [:hicasso.core-ssr/edit]]}
   ;; CONTROLLED, and it has to be: `::h/revision` re-baselines a
   ;; controlled field to its model, so `impl.controlled/install!`
   ;; refuses it on a field with no `:value` to re-baseline TO. Measured
   ;; here first as an uncaught refusal, and
   ;; [[a-revision-on-an-uncontrolled-field-is-refused-at-source]] is
   ;; that refusal kept as a row rather than merely designed around.
   [:input.text {:type        "text"
                 :value       (h/sub [:hicasso.core-ssr/draft])
                 :on-change   [:hicasso.core-ssr/edit ::h/value]
                 ::h/revision 7}]
   [:input.check {:type "checkbox" :defaultChecked true
                  :on-change [:hicasso.core-ssr/edit ::h/checked]}]
   [:a.link {:href "#x" :on-click [::h/prevent [:hicasso.core-ssr/finish]]} "veto"]
   [:button.go {:on-click [:hicasso.core-ssr/finish]} "go"]])

(h/defview bad-revision
  "`::h/revision` on an UNCONTROLLED field — `:defaultValue`, so there is
  no model to re-baseline to. The refusal this shape draws is HS-07's
  own, and it fires during the SERVER render, which is the half worth
  pinning: a reserved key whose validation ran on the client only would
  ship this page and fail at adoption."
  [_]
  [:div.bad
   [:input {:type "text" :defaultValue "x" ::h/revision 7}]])

(h/defview controls
  "HS-08 as a class. Section 2.3 dispositions each control type for the
  controlled-field law; this row is the *server* half §2.1 owns — the
  value a control carries has to be IN the bytes, or the page paints
  empty and fills in after adoption, which is the flash the whole
  contract exists to prevent."
  [_]
  [:div.controls
   [:input.c-text {:type "text" :value (h/sub [:hicasso.core-ssr/draft])
                   :on-change [:hicasso.core-ssr/edit ::h/value]}]
   [:input.c-check {:type "checkbox" :checked true
                    :on-change [:hicasso.core-ssr/edit ::h/checked]}]
   [:textarea.c-area {:value "area-text"
                      :on-change [:hicasso.core-ssr/edit ::h/value]}]
   [:select.c-select {:value "b" :on-change [:hicasso.core-ssr/edit ::h/value]}
    [:option {:value "a"} "A"]
    [:option {:value "b"} "B"]]])

(h/defview guarded
  "HS-09, the succeeding arm: `h/error-boundary` around a child that
  does not throw contributes its child's output and no wrapper element
  of its own."
  [_]
  [:div.guarded
   [h/error-boundary {:fallback [:p.fellback "fell back"]}
    [:p.kid (h/sub [:hicasso.core-ssr/title])]]])

(h/defview exploding
  "HS-09, the throwing arm. React is explicit that a CLIENT error
  boundary does not catch a SERVER rendering error, so the declared
  `:fallback` is not what stands in the bytes — the render fails. A row
  that expected the fallback would be asserting a React behaviour that
  does not exist."
  [_]
  [:div.guarded
   [h/error-boundary {:fallback [:p.fellback "fell back"]}
    [:p.kid (throw (js/Error. "server render exploded"))]]])

(h/defview page
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
     (mount/provider kw (codec/root-element kw hiccup)))))

(defn- q [root sel] (.querySelector root sel))

(defn- hydration-row
  "Bake the page on the server, adopt THOSE BYTES through the product
  door, and hand `after` the container, what the framework reported, and
  the HTML that was hydrated from.

  **The row owns `done`; `after` is assertion-only.** `cljs.test`'s
  `run-block` continues the remainder of the run synchronously from the
  `done` call, so a row that called `done` from inside its assertions
  would leave this root and this container standing under every
  namespace that followed and run its teardown only when that whole
  continuation returned. Merged-PR audit #7966 found exactly that in the
  native tier's suite; the order here — assert, tear down, `done` last —
  is the shape that repair settled on.

  Adoption is read through an EXPANDO stamped before hydration
  (`sup/stamp-server-nodes!`), which does not round-trip through
  `innerHTML`: a node still answering to it is the very node the server
  markup produced, rather than a replacement that looks identical in the
  DOM and in the assertion."
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
;; 0 — deterministic bytes from an immutable request snapshot (no DOM)
;; ---------------------------------------------------------------------------

(deftest a-defview-boundary-renders-deterministic-bytes
  (testing "HS-01, and §2.4's first clause. Two renders of one snapshot
            are the same bytes — which is the property a response cache,
            an ETag and a byte-comparison test all rest on. Narrowing
            caught: any per-render identity in the output (a generated
            key, a counter, a `useId`), which would make the two strings
            differ while both looked plausible in isolation"
    (fresh!)
    (let [a (server-html [article {}])
          b (server-html [article {}])]
      (is (= a b) (str "two renders, one snapshot, identical bytes: " a))
      (is (re-find #"<article class=\"article\">" a)
          (str "the boundary contributed its own element and no wrapper
                around it: " a))
      (is (re-find #"quarterly" a)
          (str "carrying the request's value: " a)))))

(deftest a-sub-read-answers-the-request-snapshot
  (testing "HS-02. The read is of the frame the render names, so two
            frames seeded to two different requests produce two different
            responses — and the branch a snapshot does not take
            contributes nothing to the bytes. Narrowing caught: a read
            resolving through a process-global current-frame, which
            renders both requests identically and is the exact defect a
            server is unusable with"
    (fresh! frame-id "quarterly")
    (fresh! other-frame-id "annual")
    (let [a (server-html frame-id [article {}])
          b (server-html other-frame-id [article {}])]
      (is (re-find #"quarterly" a) (str "frame A's request: " a))
      (is (re-find #"annual" b) (str "frame B's request: " b))
      (is (not (re-find #"annual" a)) (str "and neither leaked: " a))
      (is (not (re-find #"quarterly" b)) (str "in either direction: " b))
      (is (not (re-find #"class=\"badge\"" a))
          (str "the `when` was false, so its subtree is ABSENT rather
                than empty — a branch not taken contributes no element
                and no read: " a)))))

(deftest a-conditional-read-appears-when-its-branch-does
  (testing "the control for the row above: the same body under a snapshot
            whose `:done?` is true. Without this, `no badge in the bytes`
            is equally consistent with a body that never renders a badge
            at all"
    (fresh!)
    (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.core-ssr/finish]))
    (let [html (server-html [article {}])]
      (is (re-find #"class=\"badge\"" html)
          (str "the taken branch is in the bytes: " html))
      (is (re-find #"shipped" html)
          (str "with the value only that branch reads: " html)))))

(deftest no-cross-request-state-survives-a-server-render
  (testing "HS-02's third server clause. Render A, render B, render A
            again: A's second response is byte-identical to its first.
            Narrowing caught: any state a render leaves behind in the arm
            — a memo keyed on nothing, a retained snapshot, a collector
            table that accumulates — which shows up as the SECOND
            identical request answering differently from the first"
    (fresh! frame-id "quarterly")
    (fresh! other-frame-id "annual")
    (let [a1 (server-html frame-id [article {}])
          _  (server-html other-frame-id [article {}])
          a2 (server-html frame-id [article {}])]
      (is (= a1 a2)
          (str "the same request answered the same way after an unrelated
                one ran between: " a1 " vs " a2)))))

;; ---------------------------------------------------------------------------
;; 1 — the grammar in the bytes (no DOM)
;; ---------------------------------------------------------------------------

(deftest the-intrinsic-heads-render-html-svg-and-custom-elements
  (testing "HS-04 and HS-05. Three head shapes and a fragment, measured
            in the bytes rather than in a DOM that has already
            normalised them — case, namespacing and attribute spelling
            are all decided by the serializer and all invisible after
            parsing"
    (fresh!)
    (let [html (server-html [chrome {}])]
      ;; Attribute ORDER is the serializer's, not the author's — the
      ;; `.mark` shorthand folds onto the emitted object and lands last —
      ;; so each attribute is asserted on its own. Byte-exact per
      ;; attribute, order-free between them.
      (is (re-find #"<svg viewBox=\"0 0 10 10\"" html)
          (str "SVG keeps React's camelCase `viewBox` — HTML would have
                lowercased it, and a lowercased one is a different
                attribute that SVG ignores: " html))
      (is (re-find #"stroke-width=\"2\"" html)
          (str "and the kebab SVG presentation attribute is emitted
                kebab: " html))
      (is (re-find #"<my-widget data-kind=\"custom\" aria-label=\"widget\">inside</my-widget>" html)
          (str "a custom element passes its dashed attributes through
                verbatim, which is the whole contract for a head React
                does not know: " html))
      (is (not (re-find #"<\w+[^>]*>\s*<p class=\"adjacent\"" html))
          (str "the fragment contributed no wrapper element of its own: "
               html)))))

(deftest adjacent-text-carries-react-s-own-separator
  (testing "§2.4's adjacent-text clause, and it is a hydration property
            rather than a cosmetic one: two sibling strings are two text
            NODES on the client, one run of characters in the bytes, and
            React writes a comment marker between them so the adoption
            can find the seam. Narrowing caught: a serializer that
            concatenated them — bytes that look right, hydrate wrong, and
            are only visible as a mismatch at run time"
    (fresh!)
    (let [html (server-html [chrome {}])]
      (is (re-find #"alpha<!-- -->beta" html)
          (str "the separator is there, between the two strings and
                nowhere else: " html)))))

(deftest one-canonical-slot-however-the-key-was-written
  (testing "HS-06 and HS-15. `:htmlFor` reaches the bytes as `for`, a
            style MAP as a serialized declaration, and the caller's `:&`
            remainder merges under the law that the literal key written
            in the map wins"
    (fresh!)
    (let [html (server-html [slots {:extra {:class "from-remainder"
                                           :data-extra "yes"}}])]
      (is (re-find #"<label for=\"field\"" html)
          (str "`:htmlFor` emits the HTML attribute `for`: " html))
      (is (re-find #"style=\"font-weight:700;margin-top:4px\"" html)
          (str "the style map serialized, with React's own px default on
                the length and none on the unitless weight: " html))
      (is (re-find #"data-extra=\"yes\"" html)
          (str "a key only the remainder carries reaches the bytes: " html))
      (is (re-find #"<input class=\"from-remainder\"" html)
          (str "and the input, which writes no literal class, takes the
                one the remainder alone names: " html))
      (is (re-find #"id=\"field\"" html)
          (str "beside the literal keys, untouched by the merge: " html)))))

(deftest the-literal-key-beats-the-remainder
  (testing "HD-023's merge law, on the server side of it. The remainder
            supplies `class` and the literal map does not, above; here
            both do, and the literal wins. Two rows because one of them
            passing tells you nothing about the other"
    (fresh!)
    (let [html (server-html [slots {:extra {:class "loser"}}])]
      (is (re-find #"class=\"lbl\"" html)
          (str "the label's literal class stands: " html))
      (is (re-find #"class=\"loser\"" html)
          (str "and the input, which writes no literal class, takes the
                remainder's: " html)))))

(deftest the-intent-vocabulary-never-reaches-the-bytes
  (testing "HS-03 and HS-07, and the strongest single reason the server
            half of this tier needed a witness at all. Intents are DATA
            — vectors, placeholder keywords and a decorator head — and
            data is exactly the kind of thing a serializer emits. Every
            spelling the grammar has is in [[intents]]; none of them may
            appear in the response, as an attribute or as text.
            Narrowing caught: a lowering that ran on the client only,
            leaving the raw vectors to be stringified on the server"
    (fresh!)
    (let [html (server-html [intents {}])]
      (is (re-find #"<form class=\"intents\">" html)
          (str "the form is there, so the row is about what was OMITTED
                from a real render: " html))
      (is (not (re-find #"(?i)onclick|onsubmit|oninput|onchange" html))
          (str "no DOM event attribute — React never emits handler props,
                and an intent must not sneak in as one: " html))
      (is (not (re-find #"hicasso" html))
          (str "and no reserved keyword survived into the markup:
                `::h/value`, `::h/checked`, `::h/prevent` and
                `::h/revision` all carry the `re-frame.hicasso`
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
  (testing "HS-07's refusal arm, and it was found rather than designed:
            the first draft of [[intents]] put `::h/revision` on a
            `:defaultValue` field and this refusal is what came back. It
            is kept as a row because the reserved vocabulary's validation
            has to run on the SERVER too — one that ran on the client
            alone would let this page be baked and would fail only at
            adoption, which is the most expensive place to find it"
    (fresh!)
    (let [e (try (server-html [bad-revision {}]) nil
                 (catch :default e e))
          d (ex-data e)]
      (is (some? e) "the render refused rather than emitting the field")
      (is (= :rf.error/hicasso-revision-not-controlled (:rf.error/id d))
          (str "with the id that names the mistake: " (pr-str d)))
      (is (= :put-the-revision-on-a-controlled-input-or-textarea
             (:recovery d))
          "carrying the recovery, which is what makes it a refusal rather
           than a crash")
      (is (= 're-frame.hicasso.core-view-ssr-dom-cljs-test/bad-revision
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
                option, not as an attribute on the select: " html))
      (is (not (re-find #"<select[^>]*value=" html))
          (str "the select itself carries no `value` attribute, which is
                the half a serializer gets wrong by writing the React
                prop straight out: " html)))))

;; ---------------------------------------------------------------------------
;; 1b — the error boundary's two server arms (no DOM)
;; ---------------------------------------------------------------------------

(deftest an-error-boundary-contributes-its-child-and-no-wrapper
  (testing "HS-09's succeeding arm"
    (fresh!)
    (let [html (server-html [guarded {}])]
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
        "the throw reaches the caller of `renderToString`")
    (is (some? (server-html [guarded {}]))
        "and the arm still renders afterwards, so the failure left no
         wedged state behind it")))

;; ---------------------------------------------------------------------------
;; 2 — hydration (DOM)
;; ---------------------------------------------------------------------------

(deftest the-page-adopts-the-servers-own-nodes
  (async done
    (if-not (mount/browser?)
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
          (is (sup/every-server-node? container ".title")
              "and the title is the SERVER'S node, still carrying the
               expando — adoption, not a re-render that looks the same")
          (is (sup/every-server-node? container ".adjacent")
              "as is the adjacent-text paragraph, which is the node the
               comment separator exists for")
          (is (sup/every-server-node? container "my-widget")
              "and the custom element, whose head React does not know")
          (is (= "quarterly" (.-textContent (q container ".title")))
              "carrying the request's value"))))))

(deftest a-deliberate-mismatch-is-attributed-to-the-root-that-owns-it
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html [page {}])
              container (sup/server-dom! html)
              {:keys [seen stop!]} (sup/watch-mismatches!)
              ;; MANUFACTURED here and asserted on here — the only shape
              ;; of call site at which swallowing an uncaught error is
              ;; not the fail-open rf2-mwx08 forbids.
              {:keys [captured close!]} (sup/open-console-capture!
                                          {:swallow-uncaught? true})]
          ;; The request the client renders is not the request the server
          ;; rendered — the divergence a stale cache or a clock produces.
          (rf/with-frame frame-id
            (rf/dispatch-sync [:hicasso.core-ssr/retitle "annual"]))
          (let [handle (mount/hydrate-root! container frame-id [page {}])]
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
                             (pr-str (mapv (comp :error sup/tags-of) @seen))))
                    (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                           (:where (sup/tags-of (first @seen))))
                        "attributed to the door that owns the adoption")
                    (is (= :warned-and-replaced
                           (:recovery (sup/tags-of (first @seen))))
                        "with the recovery React had already performed")
                    (is (= "annual" (.-textContent (q container ".title")))
                        "and the repaired DOM carries the CLIENT's value,
                         which is what 'warned and replaced' means"))
                  (finally
                    (mount/release! handle)
                    (collector/reset-runtime!)
                    (done))))
              300)))))))

(deftest two-overlapping-roots-adopt-under-distinct-prefixes
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh! frame-id "quarterly")
        (fresh! other-frame-id "annual")
        (let [html-a (server-html frame-id [page {}])
              html-b (server-html other-frame-id [page {}])
              ca     (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb     (sup/stamp-server-nodes! (sup/server-dom! html-b))
              {:keys [seen stop!]} (sup/watch-mismatches!)
              ha     (mount/hydrate-root! ca frame-id [page {}]
                                          {:identifier-prefix "pfx-a-"})
              hb     (mount/hydrate-root! cb other-frame-id [page {}]
                                          {:identifier-prefix "pfx-b-"})]
          (js/setTimeout
            (fn []
              (stop!)
              (try
                (testing "§2.4's fourth clause for this tier: two roots
                          adopt at once, each under its own stable
                          `identifierPrefix` and its own frame, and
                          neither disturbs the other. The prefixes are
                          the option rf2-hic-046's pass-through added;
                          the frames are what makes the two responses
                          different in the first place. Narrowing
                          caught: a process-global adoption window or a
                          page-wide current-frame — either renders both
                          roots from one request and this row reads the
                          same string twice"
                  (is (empty? @seen)
                      (str "neither adoption had anything to reconcile: "
                           (pr-str @seen)))
                  (is (= "quarterly" (.-textContent (q ca ".title")))
                      "root A settled on its own request")
                  (is (= "annual" (.-textContent (q cb ".title")))
                      "root B on its own")
                  (is (sup/every-server-node? ca ".title")
                      "root A adopted the server's nodes")
                  (is (sup/every-server-node? cb ".title")
                      "and so did root B, concurrently"))
                (finally
                  (mount/release! ha)
                  (mount/release! hb)
                  (collector/reset-runtime!)
                  (done))))
            300))))))

;; ---------------------------------------------------------------------------
;; 3 — acquisition and cleanup (DOM)
;; ---------------------------------------------------------------------------

(deftest a-server-render-acquires-no-reader-and-an-adoption-acquires-one
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [page {}]
        done
        (fn [_container _seen _html]
          (is (= 1 (sup/readers-of [frame-id [:hicasso.core-ssr/title]]))
              (str "exactly ONE reader after adoption. The server render
                    ran the same body and registered NONE — there is no
                    subscription to release on a server, and a tier that
                    acquired one there would leak a cell per request —
                    so a count of two would mean both halves acquired
                    and the server's was never released; cells: "
                   (pr-str (sup/cell-keys))))
          (is (zero? (sup/readers-of [frame-id [:hicasso.core-ssr/badge]]))
              (str "and the key only the untaken branch reads has no
                    reader at all, on either side: "
                   (pr-str (sup/cell-keys)))))))))

(deftest an-adopted-page-releases-exactly-what-it-acquired
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (collector/reset-runtime!)
      (testing "§2.4's last clause: exact cleanup. Narrowing caught:
                a teardown that empties the runtime's tables rather than
                releasing the subscriptions — it answers zero whether it
                released anything or not, which is a gate that cannot go
                red (`impl.mount/unmount!`, rf2-2rtt6.48)"
        (let [h (mount/root! (mount/fresh-container!) frame-id [page {}])]
          (is (= 1 (sup/readers-of [frame-id [:hicasso.core-ssr/title]]))
              (str "one reader while mounted; cells: "
                   (pr-str (sup/cell-keys))))
          (mount/unmount! h)
          (is (zero? (sup/readers-of [frame-id [:hicasso.core-ssr/title]]))
              (str "and none after the PUBLIC teardown door, which
                    touches nothing the runtime holds; cells: "
                   (pr-str (sup/cell-keys)))))))))
