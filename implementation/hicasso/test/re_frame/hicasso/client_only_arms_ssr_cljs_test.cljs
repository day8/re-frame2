(ns re-frame.hicasso.client-only-arms-ssr-cljs-test
  "**The Client-only rows that had never been server-rendered** —
  dispositions.md §2.1 rows HS-31 (optional forms module), HS-32
  (optional overlay module) and HS-23 (Activity-hosted subtree).

  CHECKPOINT 4 read §2.1's own note 3 — *a Client-only
  row still owes a witness; the refusal must be shown to fire, at
  source, with its recovery — an unproved refusal is not a
  disposition* — against the tree, and four rows came back Client-only
  with nothing behind them. This file is what three of those rows now
  point at.

  ## What this file found, and it is not what the rows claimed

  What was expected was the cheap half of a Client-only row: server-render
  the surface, watch the refusal fire, name its recovery. **Two of the
  three surfaces do not refuse at all**, and that is the finding rather
  than a gap in this file:

  - **HS-31, forms.** `forms/buffered-field` is an `h/defview` boundary
    around an ordinary controlled `<input>`, and every door underneath
    it is already dispositioned **Render** — the boundary (HS-01), the
    `h/sub` read (HS-02), the props map (HS-06) and the controlled
    field's server half (HS-08). So the module emits its live field into
    the server bytes, filled, deterministically. Nothing refuses,
    nothing is omitted, and no fallback stands in — because none is
    needed.
  - **HS-32, overlays.** The module's whole client mechanism is one ref
    callback (`impl.overlay/make-cell`), and React does not call refs
    during `renderToString`. What that omits is the *top-layer entry*,
    not the markup: an open overlay's panel and all of its children ARE
    in the server bytes. They are invisible there because a `<dialog>`
    without `open` and an unshown `[popover]` are both `display:none` by
    the UA stylesheet — the PLATFORM's doing, not a refusal at any
    declaration source.
  - **HS-23, Activity.** This one splits by route, and the split is the
    point. Through `h/defhost` the Client-only refusal is real and fires
    at the declaration source, with both of the policy's arms. Through
    raw React construction — the route
    `docs/design/hicasso/product/lanes/react-compatibility-notes.md`
    tells an author to take — there is no refusal and a VISIBLE
    Activity's subtree reaches the response.

  ## And one measured defect, now repaired

  An anchored `overlay/popover` USED TO bake a CSS anchor name into its
  `style` attribute, and that name is minted from
  `impl.overlay/!anchor-seq` — a page-wide `defonce` counter. On a
  client that counter is right, and deliberately so (`globals.md`: a CSS
  anchor name lives in one namespace per DOCUMENT, not one per React
  root). On a server it was scoped to nothing: two
  renders of ONE immutable request snapshot produced
  `position-anchor:--rf-overlay-5` and `position-anchor:--rf-overlay-6`,
  and a long-lived process drifted further from a fresh client's counter
  with every request it served. §2.4's first upgrade clause —
  deterministic server bytes from an immutable request snapshot — was
  unreachable for the anchored arm by construction, and the attribute it
  failed on is one hydration must match.

  The repair was NOT to make the counter deterministic.
  The ident is a client lifecycle token — its whole job is telling two
  overlays that share a trigger apart at teardown — so the repair stops
  SERIALIZING it: the panel's `position-anchor` is now claimed in the
  ref callback, beside the trigger's `anchor-name` that it was always
  the other half of. The bytes are deterministic by construction rather
  than by keeping a counter honest, and section 2 below asserts that
  PROPERTY rather than any particular name, so it survives the naming
  scheme changing again.

  ## HS-34 is NOT in this file, and its absence is the finding

  HS-34 is the *optional routing-integration module*, and there is no
  such module to render. `implementation/hicasso/src/re_frame/hicasso/`
  holds six optional-module namespaces and routing is not among them;
  `hicasso/scripts/check_optional_module_reachability.py`'s `MODULES`
  roster names those same six (`motion`, `overlay`, `native`, `forms`,
  `server`, `substrate`) — a roster that has GROWN THREE TIMES without
  admitting routing, so the count evidences the absence more sharply
  than when this paragraph was written and it read three;
  `docs/design/hicasso/product/naming-ledger.md` row 6 carries
  `re-frame.hicasso.routing` as a PROVISIONAL recommendation and NOT
  under an open question — that ledger's publication note says every
  row is dispositioned and none is open, and its sibling
  `naming-packet.md`'s own row 6 glosses the marker in terms,
  *provisional — the namespace does not exist yet*, a better citation
  for THERE IS NO MODULE than an open question ever was; and
  `implementation/hicasso/spec/invariants.md`'s route-link
  row states the move in the FUTURE tense. `h/route-link`
  lives on the core facade today and is witnessed there as HS-40,
  Render, in `facade-roster-ssr-dom-cljs-test`. There is no declaration
  source at which a refusal could fire and no bytes to measure, so this
  file writes no HS-34 section rather than writing an empty one.

  ## Runtime

  `-cljs-test`, not `-dom-cljs-test`: every row here is a
  `react-dom/server` render and none of them touches a document, so the
  browser lane would add cost and decide nothing. `:node-test` runs it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.forms :as forms]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.overlay :as overlay]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.test.forms :as tf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::client-only-arms)

(def ^:private control
  "One buffered field's address, spelled once so the draft the module
  writes and the draft this file reads cannot drift apart."
  [:todo 7 :title])

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :hicasso.arms/title (fn [db _] (:title db)))
(rf/reg-sub :hicasso.arms/revision (fn [db _] (:revision db)))
(rf/reg-sub :hicasso.arms/open? (fn [db _] (:open? db)))

(rf/reg-event :hicasso.arms/seed
  (fn [_ [_ open?]] {:db {:title "milk" :revision 3 :open? open?}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- fresh!
  "A frame seeded to one immutable request snapshot.

  `forms/drafts` is re-registered here rather than at the top level
  because the runtime reset between rows clears the state registry; a
  repeat under the SAME `:default` is the no-op refresh `reg-state`
  documents."
  [open?]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (h/reg-state :re-frame.hicasso.forms/drafts {:default nil})
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.arms/seed open?]))
  frame-id)

(defn- server-html
  "The page as a REAL server render, through `react-dom/server`'s own
  `renderToString`, called by hand — the smallest thing that produces
  server bytes, so what these rows read is the arm's own server
  behaviour and nothing else. The package's own server door is
  `re-frame.hicasso.server/render`; this harness sits beside it, not
  instead of it.

  Spec 006's two dev-mode view annotations are taken out before the rows
  read the bytes. Every scan below is for vocabulary that must NOT be in
  the markup — `#\"hicasso\"`, `#\"popover\"`, `#\"dialog\"` — and each of
  those matches a view's own name, which the annotation puts in the
  bytes on purpose. Left in, they would turn a real guard into a row that
  reds on the framework doing exactly what Spec 006 requires.
  `rf.hicasso.roots-frames-support/without-view-annotations` carries the argument in full."
  [hiccup]
  (rf.hicasso.roots-frames-support/without-view-annotations
    (react-dom-server/renderToString
      (mount/provider frame-id (codec/root-element frame-id hiccup)))))

;; ---------------------------------------------------------------------------
;; The subjects — one surface per view, so a red row names a surface
;; ---------------------------------------------------------------------------

(h/defview field-page
  "HS-31's subject: the optional forms module's one view."
  []
  [:div.owner
   [forms/buffered-field
    {:control     control
     :value       (h/sub [:hicasso.arms/title])
     ::h/revision (h/sub [:hicasso.arms/revision])
     :on-commit   [:todo/committed 7]
     :placeholder "What needs doing?"}]])

(h/defview anchored-popover-page
  "HS-32's subject with an `:anchor`, which is the arm that bakes a
  generated CSS anchor name into the `style` attribute."
  []
  [:div.owner
   [:button {:id "trigger"} "Filter"]
   [overlay/popover {:open?      (h/sub [:hicasso.arms/open?])
                     :on-dismiss [:menu/dismissed]
                     :anchor     "trigger"
                     :placement  :bottom-start}
    [:ul {:role "menu"} [:li.choice "Unread"]]]])

(h/defview unanchored-popover-page
  "The same surface with no `:anchor` — the control that keeps §5 a fact
  about the anchor ident rather than about popovers."
  []
  [:div.owner
   [overlay/popover {:open?      (h/sub [:hicasso.arms/open?])
                     :on-dismiss [:menu/dismissed]}
    [:ul {:role "menu"} [:li.choice "Unread"]]]])

(h/defview modal-page
  "HS-32's other door. A modal takes no `:anchor`, so it mints no ident."
  []
  [:div.owner
   [overlay/modal {:open?      (h/sub [:hicasso.arms/open?])
                   :on-dismiss [:invoice/cancelled]
                   :label      "Confirm deletion"}
    [:h2.title "Delete this invoice?"]
    [:button.confirm "Delete"]]])

(h/defview undismissable-modal-page
  "The `closedby` ladder's no-dismissal arm: with no `:on-dismiss` there
  is nowhere to route a close request, so the dialog is told to honour
  none and the open flag cannot acquire a second owner."
  []
  [:div.owner
   [overlay/modal {:open? (h/sub [:hicasso.arms/open?])
                   :label "Processing"}
    [:p.wait "Do not close this tab."]]])

(h/defview light-dismiss-modal-page
  "The ladder's opt-in arm: `:light-dismiss?` widens `closedby` to the
  platform's `any`, so a backdrop click can dismiss."
  []
  [:div.owner
   [overlay/modal {:open?          (h/sub [:hicasso.arms/open?])
                   :on-dismiss     [:invoice/cancelled]
                   :light-dismiss? true
                   :label          "Quick filter"}
    [:p.hint "Click anywhere outside to dismiss."]]])

(h/defview manual-popover-page
  "The popover half of the same choice: no `:on-dismiss` means `manual` —
  in the top layer, dismissing for nothing."
  []
  [:div.owner
   [overlay/popover {:open? (h/sub [:hicasso.arms/open?])}
    [:ul {:role "menu"} [:li.choice "Unread"]]]])

(h/defview author-placed-popover-page
  "An author's `:style {:position-area …}` written beside the
  `:placement` that also produces one — `element-attrs`' documented
  precedence subject."
  []
  [:div.owner
   [overlay/popover {:open?      (h/sub [:hicasso.arms/open?])
                     :on-dismiss [:menu/dismissed]
                     :placement  :bottom-start
                     :style      {:position-area "block-start"}}
    [:ul {:role "menu"} [:li.choice "Unread"]]]])

(h/defview island
  "The Hicasso boundary that sits UNDER the Activity host, so that a row
  measuring bytes is measuring a real read rather than a literal."
  []
  [:p.inner (str "title=" (h/sub [:hicasso.arms/title]))])

(def ^:private Activity
  "React 19.2's own `<Activity>`, reached by interop. It is a symbol
  rather than a component, which is why every route below has to say
  which door it goes through."
  (.-Activity react))

(h/defhost activity-bare Activity)

(h/defhost activity-with-fallback Activity
  {:fallback [:div.activity-skeleton "loading"]})

(h/defhost activity-render Activity
  {:server :render})

(h/defview activity-host-page
  "HS-23 through `h/defhost` — the third of the three routes the lane
  note names, and the only one that carries a server policy at all."
  [{:keys [head mode]}]
  [:div.owner
   [head {:mode mode}
    [island {}]]])

(h/defview activity-native-page
  "HS-23 through raw React construction, which the lane note recommends
  first and which the client witness uses: a boundary body returning the
  `<Activity>` element itself, with the hosted subtree crossed through
  `codec/as-element`."
  [{:keys [mode]}]
  [:div.owner
   (react/createElement Activity #js {:mode mode}
                        (codec/as-element [island {}]))])

;; ---------------------------------------------------------------------------
;; 1 — HS-31, the optional forms module
;; ---------------------------------------------------------------------------

(deftest the-forms-module-does-not-refuse-and-emits-its-live-field
  (testing "HS-31's cell said *Client-only, awaiting a first witness*, and
            the measurement says the surface never refuses. Every door
            under `buffered-field` is already Render — the boundary
            (HS-01), the `h/sub` read (HS-02), the props map (HS-06) and
            the controlled field's server half (HS-08) — so the module
            emits the live `<input>`, carrying the committed value. There
            is no refusal here to witness and no fallback standing in for
            an omitted region, because nothing is omitted"
    (fresh! true)
    (let [html (server-html [field-page {}])]
      (is (re-find #"<input[^>]*/>" html)
          (str "the field itself is in the response: " html))
      (is (re-find #"<input[^>]*value=\"milk\"" html)
          (str "carrying the COMMITTED value, so the page paints filled
                rather than empty-then-filled: " html))
      (is (re-find #"<input[^>]*placeholder=\"What needs doing\?\"" html)
          (str "and a pass-through attribute reaches the element, which is
                what says the module's own prop split ran: " html))
      (is (not (re-find #"hicasso" html))
          (str "no reserved keyword survives into the bytes — the module
                writes `::h/revision` and three intent vectors, and none
                of them is markup: " html)))))

(deftest two-server-renders-of-one-forms-snapshot-are-byte-identical
  (testing "the determinism §2.4's first upgrade clause asks for, measured
            here rather than assumed. It was not free for every optional
            module — the overlay's anchored arm failed exactly this
            assertion until rf2-9zz0y, and section 2 below is where that
            is now held"
    (fresh! true)
    (let [a (server-html [field-page {}])
          b (server-html [field-page {}])]
      (is (= a b) (str "two renders of one snapshot: " a " / " b)))))

(deftest a-live-draft-in-the-request-snapshot-is-what-the-server-renders
  (testing "D016's eligibility rule holds on the SERVER too, and this is
            the row that says the module's arrangement is not client-only
            machinery: a record whose revision matches renders `:draft`,
            and the committed `:value` is what a caller would have got
            without one. Driven through the module's own public event id
            rather than by writing `app-db` directly, so the row measures
            the protocol and not this file's idea of it"
    (fresh! true)
    (rf/with-frame frame-id
      (rf/dispatch-sync [tf/edit-id control 3 "half-typed"]))
    (let [html (server-html [field-page {}])]
      (is (re-find #"<input[^>]*value=\"half-typed\"" html)
          (str "the draft, not the committed value: " html))
      (is (not (re-find #"value=\"milk\"" html))
          (str "and the committed value is not also in the bytes: " html))))
  (testing "a record whose revision does NOT match is ineligible, and the
            committed value stands — the same fence that makes a stale
            commit a no-op, answered while producing bytes"
    (fresh! true)
    (rf/with-frame frame-id
      (rf/dispatch-sync [tf/edit-id control 2 "stale"]))
    (let [html (server-html [field-page {}])]
      (is (re-find #"<input[^>]*value=\"milk\"" html)
          (str "the committed value, because revision 2 is not 3: " html)))))

;; ---------------------------------------------------------------------------
;; 2 — HS-32, the optional overlay module
;; ---------------------------------------------------------------------------

(deftest a-closed-overlay-contributes-nothing-and-that-is-the-open-flag
  (testing "`:open?` false renders nil, so a closed overlay has no element
            and no children in the response. Recorded as what it is: the
            APPLICATION's open flag answering, not a server refusal. The
            same nil is what a client render produces, and a row that read
            this as the Client-only arm would have mistaken an app-db
            value for a policy"
    (fresh! false)
    (let [pop-html   (server-html [anchored-popover-page {}])
          modal-html (server-html [modal-page {}])]
      (is (not (re-find #"popover" pop-html))
          (str "no popover element: " pop-html))
      (is (not (re-find #"choice" pop-html))
          (str "and none of its children: " pop-html))
      (is (not (re-find #"dialog" modal-html))
          (str "no dialog element: " modal-html))
      (is (not (re-find #"Delete" modal-html))
          (str "and none of its children: " modal-html)))))

(deftest an-open-overlay-puts-its-whole-panel-in-the-server-bytes
  (testing "HS-32's cell said *Client-only, awaiting a first witness*, and
            the measurement says the module does not refuse either. The
            module's whole client mechanism is one ref callback and React
            does not call refs during `renderToString`, so what the server
            omits is the TOP-LAYER ENTRY — not the markup. Panel and
            children alike reach the response. They are invisible there
            because a `<dialog>` without `open` and an unshown
            `[popover]` are `display:none` by the UA stylesheet, which is
            the platform's doing and not a declaration-source refusal"
    (fresh! true)
    (let [html (server-html [anchored-popover-page {}])]
      (is (re-find #"<div popover=\"auto\"" html)
          (str "the panel is in the bytes, carrying the platform's own
                dismissal word: " html))
      (is (re-find #"class=\"choice\"" html)
          (str "and so are its children: " html))
      (is (not (re-find #"open" html))
          (str "with nothing that would show it — the show is the ref's,
                and refs do not run on the server: " html))))
  (testing "the modal is the same finding at the other door"
    (fresh! true)
    (let [html (server-html [modal-page {}])]
      (is (re-find #"<dialog closedby=\"closerequest\"" html)
          (str "the dialog is in the bytes: " html))
      (is (re-find #"aria-label=\"Confirm deletion\"" html)
          (str "with its accessible name: " html))
      (is (re-find #"Delete this invoice\?" html)
          (str "and its children: " html))
      (is (not (re-find #"<dialog[^>]* open" html))
          (str "and no `open` attribute, so the engine paints none of it
                until `showModal` runs on the client: " html)))))

(deftest the-dismissal-word-ladder-is-complete-in-the-server-bytes
  (testing "the rows above pin the DEFAULT arms — `closerequest` on the
            modal and `auto` on the popover. These are the other three,
            which nothing had ever exercised: the whole dismissal policy
            is one platform attribute, so each arm is decidable as server
            bytes, and an arm that rots is a desync between the app's
            open flag and the platform's own close behaviour"
    (testing "a modal with no :on-dismiss honours no close request"
      (fresh! true)
      (let [html (server-html [undismissable-modal-page {}])]
        (is (re-find #"<dialog closedby=\"none\"" html)
            (str "nowhere to route a dismissal, so none is honoured: " html))))
    (testing "the :light-dismiss? opt-in is the platform's `any`"
      (fresh! true)
      (let [html (server-html [light-dismiss-modal-page {}])]
        (is (re-find #"<dialog closedby=\"any\"" html)
            (str "the author asked for backdrop dismissal: " html))))
    (testing "a popover with no :on-dismiss is `manual` — in the top
              layer, dismissing for nothing"
      (fresh! true)
      (let [html (server-html [manual-popover-page {}])]
        (is (re-find #"<div popover=\"manual\"" html)
            (str "no dismissal route, so no light dismiss either: " html))))))

(deftest an-authors-position-area-beats-the-placement-that-produced-one
  (testing "`element-attrs`' documented precedence, measured in the bytes
            where both candidates land in the same attribute: the author's
            `:style` keys merge LAST, so a hand-written `:position-area`
            wins over the `:placement` table's answer"
    (fresh! true)
    (let [html (server-html [author-placed-popover-page {}])]
      (is (re-find #"position-area:block-start" html)
          (str "the author's word is in the bytes: " html))
      (is (not (re-find #"block-end span-inline-end" html))
          (str "and the placement's is NOT — which is what merge order
                means, and what a subject without :placement could never
                decide: " html)))))

(deftest an-anchored-popover-produces-deterministic-server-bytes
  (testing "**THE PROPERTY, and it is the property rather than a name.**
            Two renders of ONE immutable request snapshot are byte-
            identical — §2.4's first upgrade clause, asserted for the arm
            that could not reach it before `rf2-9zz0y`.

            What it used to do is worth keeping, because the repair is
            shaped by it. `impl.overlay/make-cell` mints the panel's CSS
            anchor name from `!anchor-seq`, a page-wide `defonce`
            counter, and the name was written into the `style`
            attribute — so two renders of one snapshot answered
            `position-anchor:--rf-overlay-5` and
            `position-anchor:--rf-overlay-6`. Page-wide is CORRECT on a
            client and deliberate (`globals.md`, `rf2-hic-017`: a CSS
            anchor name lives in one namespace per DOCUMENT). The defect
            was that a server has no document to be page-wide with
            respect to and no request scope to fall back on, so the
            counter advanced across renders that are independent.

            **The repair does not make the counter deterministic — it
            stops serializing it.** The panel's `position-anchor` is
            claimed in the ref callback beside the trigger's
            `anchor-name`, which it was always the other half of.
            Asserting EQUALITY rather than a particular ident is the
            point: this row stays true if the naming scheme changes
            again, and it would red for any future non-determinism from
            any source rather than only for this one"
    (fresh! true)
    (let [a (server-html [anchored-popover-page {}])
          b (server-html [anchored-popover-page {}])]
      (is (= a b)
          (str "two renders of one snapshot: " a " / " b))))
  (testing "and the MECHANISM, without which the row above could be a
            counter that merely happened not to move between two
            adjacent renders. `make-cell` still runs per instance on the
            server and the counter still advances there — what changed is
            that nothing carries its value into the response"
    (fresh! true)
    (let [html (server-html [anchored-popover-page {}])]
      (is (not (re-find #"position-anchor" html))
          (str "no anchor name is serialized at all: " html))
      (is (not (re-find #"--rf-overlay-" html))
          (str "and no generated ident reaches any other position in the
                bytes either: " html))))
  (testing "while everything the anchored arm is FOR is still in the
            response. The repair took out a client lifecycle token and
            not the placement — `:placement` resolves through the
            module's own table to a static string, so it stays
            declarative and was never the non-deterministic half"
    (fresh! true)
    (let [html (server-html [anchored-popover-page {}])]
      (is (re-find #"<div popover=\"auto\"" html)
          (str "the panel is still in the bytes: " html))
      (is (re-find #"position-area:block-end span-inline-end" html)
          (str "carrying the placement `:bottom-start` means: " html))
      (is (re-find #"class=\"choice\"" html)
          (str "and its children: " html))))
  (testing "the unanchored arm is unchanged, and it is still the control
            that keeps the rows above a fact about the anchored arm
            rather than about popovers in general"
    (fresh! true)
    (let [a (server-html [unanchored-popover-page {}])
          b (server-html [unanchored-popover-page {}])]
      (is (not (re-find #"position-anchor" a))
          (str "no anchor name is baked: " a))
      (is (= a b) (str "so the bytes are deterministic: " a " / " b))))
  (testing "a modal takes no `:anchor` at all, so it is deterministic for
            the same reason and by construction"
    (fresh! true)
    (let [a (server-html [modal-page {}])
          b (server-html [modal-page {}])]
      (is (= a b) (str "two renders of one snapshot: " a " / " b)))))

;; ---------------------------------------------------------------------------
;; 3 — HS-23, the Activity-hosted subtree, route by route
;; ---------------------------------------------------------------------------

(deftest a-defhost-declared-activity-refuses-at-the-declaration-source
  (testing "HS-23's Client-only refusal, and this is the route on which it
            is real. `h/defhost` with no `:server` written is the default
            policy, and the gate renders nothing where the host sits —
            regardless of the Activity's own mode, which is what makes
            this the DECLARATION's refusal rather than React's omission of
            a hidden subtree. This is the bare arm the canonical matrix
            asks for: absent from the server bytes, with nothing there to
            hydrate"
    (fresh! true)
    (doseq [mode ["visible" "hidden"]]
      (let [html (server-html [activity-host-page {:head activity-bare
                                                   :mode mode}])]
        (is (not (re-find #"class=\"inner\"" html))
            (str "mode " mode ": the hosted subtree is absent: " html))
        (is (not (re-find #"title=" html))
            (str "mode " mode ": and its read never reached the response: "
                 html)))))
  (testing "the SECOND arm — a declared `:fallback` stands in the bytes at
            the tree position instead. Both of the policy's arms, which is
            what §2.1 note 3 and the canonical matrix's last paragraph ask
            of a Client-only row"
    (fresh! true)
    (let [html (server-html [activity-host-page {:head activity-with-fallback
                                                 :mode "visible"}])]
      (is (re-find #"class=\"activity-skeleton\"" html)
          (str "the declared placeholder is what stands there: " html))
      (is (not (re-find #"class=\"inner\"" html))
          (str "and the live subtree still is not: " html))))
  (testing "the CONTROL, without which the two rows above measure Activity
            rather than the gate: the same host declared `:server :render`
            puts the subtree in the response when the Activity is visible.
            So the omission above is the declaration's, and this row is
            what proves it"
    (fresh! true)
    (let [html (server-html [activity-host-page {:head activity-render
                                                 :mode "visible"}])]
      (is (re-find #"class=\"inner\"" html)
          (str "the hosted subtree renders: " html))
      (is (re-find #"title=milk" html)
          (str "and its `h/sub` read answers the frame the render names: "
               html)))))

(deftest a-hidden-activity-is-reacts-own-omission-and-not-a-refusal
  (testing "under `:server :render` the mode decides, and React decides it:
            a hidden `<Activity>` contributes nothing to the response. No
            Hicasso declaration source is consulted and no recovery is
            carried, so this is React's behaviour rather than this
            product's refusal — a distinction HS-23's cell has to make,
            because only one of the two is a disposition"
    (fresh! true)
    (let [html (server-html [activity-host-page {:head activity-render
                                                 :mode "hidden"}])]
      (is (not (re-find #"class=\"inner\"" html))
          (str "nothing of the hidden subtree reaches the bytes: " html)))))

(deftest through-raw-react-a-visible-activity-subtree-reaches-the-response
  (testing "**The finding on this row.**
            `lanes/react-compatibility-notes.md` closes its Activity
            section by telling an author to reach Activity through native
            React construction, and `rf2-9ywe`'s client witness takes that
            route through `react/createElement`. On that route there is no
            declaration and therefore no policy to consult: a VISIBLE
            Activity's hosted subtree is server-rendered, read and all. So
            HS-23's Client-only disposition is honest for the `defhost`
            route and false for the one the product recommends first"
    (fresh! true)
    (let [html (server-html [activity-native-page {:mode "visible"}])]
      (is (re-find #"class=\"inner\"" html)
          (str "the hosted subtree is in the response: " html))
      (is (re-find #"title=milk" html)
          (str "and its read answered: " html))))
  (testing "and on the same route a hidden Activity contributes nothing —
            React's omission again, which is why the pair is written
            together: one route, two modes, and neither of them a refusal
            that names a source or carries a recovery"
    (fresh! true)
    (let [html (server-html [activity-native-page {:mode "hidden"}])]
      (is (not (re-find #"class=\"inner\"" html))
          (str "nothing of the hidden subtree reaches the bytes: " html)))))
