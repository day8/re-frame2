(ns re-frame.hicasso.host-slots-dom-cljs-test
  "DECLARED ReactNode POSITIONS, AND THE ONE EXPLICIT CONVERSION
  (rf2-hic-035, HD-011).

  A foreign component's props are DATA — that is HD-011's shallow
  default and the reason `[provider {:value :theme/dark}]` hands the
  provider the keyword it was written. But some of a library's props are
  not data at all. A modal's `title`, a compound root's `footer`,
  `Suspense`'s `fallback`: React calls the type `ReactNode`, and what
  belongs there is markup.

  Hiccup written at one of those crossed as `clj->js` DATA, and that is
  the sharpest SILENT trap the door has. `{:title [:h2 \"Tasks\"]}`
  handed the library the JavaScript array `[\"h2\", \"Tasks\"]`; nothing
  threw, because a vector is a legal value at a data position and no
  rule anywhere can tell the two apart — which prop is markup is a fact
  about the foreign ABI, and only the author holds it.

  So the author says it, once, at the declaration:

      (h/defhost panel Panel {:slots #{:title :footer}})

  ## The two claims, and the sabotage that separates them

  1. **A DECLARED slot lowers**, under the render window of the boundary
     that wrote the crossing — the same window the crossing's CHILDREN
     are lowered in. So an intent inside a slot fires into the declaring
     boundary's frame exactly as an intent inside a child does, which is
     [[an-intent-inside-a-declared-slot-fires-into-the-declaring-frame]].
  2. **An UNDECLARED prop does not**, and that is not an omission — it
     is the property the whole mechanism exists to preserve.
     [[an-undeclared-prop-is-still-data]] is the sabotage row: it holds
     the SAME hiccup at the SAME prop of the SAME component, declared
     nowhere, and asserts it reaches the library as an array. Delete the
     slot arm from `host-entry` and row 1 goes red while this one stays
     green; make the codec guess that a vector is hiccup and this one
     goes red while row 1 stays green. Neither row alone says anything
     about the design.

  ## `h/as-element` — the same conversion, per site

  A declaration cannot reach three places, and they are the three the
  explicit conversion is for. A `:render` return crosses UNCONVERTED
  (`render-callback` ends in a bare call), so a returned hiccup vector
  reaches React and is refused there; the `[:>]` escape carries no
  declaration at all; and past the native fence a hiccup vector is
  refused outright. [[a-render-prop-returns-markup-through-as-element]]
  and [[the-escape-crosses-one-element-per-site]] are those.

  ## The compound library

  `panel` / `row-list` / the theme provider below are a vendor family in
  miniature — a provider, two members, two named ReactNode slots and a
  render prop — because that is the shape the deliverable names and the
  shape where each mechanism meets the others. `react/Suspense` is here
  as itself: it is a REAL React compound whose ONE interesting prop is a
  ReactNode, and the recipe the guide teaches for it —
  `{:slots #{:fallback}}` — either mints and renders or it does not.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The declaration rows and the conversion rows need no DOM
  and run under `:node-test` too."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            ["react" :as react]))

(def ^:private frame-id ::host-slots)

(rf/reg-sub :hicasso.slots/rows (fn [db _] (:rows db)))
(rf/reg-sub :hicasso.slots/picked (fn [db _] (:picked db)))

(rf/reg-event :hicasso.slots/seed
  (fn [_ _] {:db {:rows ["alpha" "bravo"] :picked nil}}))

(rf/reg-event :hicasso.slots/pick
  (fn [{:keys [db]} [_ what]] {:db (assoc db :picked what)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The vendor family — a provider, two members, and the two prop KINDS
;; ---------------------------------------------------------------------------
;;
;; Written as ordinary React, because that is what a vendor is. Nothing
;; below knows Hicasso exists, and none of it is reached except through a
;; declaration.

(def ^:private theme-context (react/createContext "unset"))

(defn- panel
  "A compound root with TWO ReactNode props beside its children — the
  shape `:slots` exists for, and the shape whose props a `clj->js`
  crossing corrupts in silence."
  [^js props]
  (react/createElement "section" #js {:className "panel"}
    (react/createElement "header" #js {:className "panel-title"} (.-title props))
    (react/createElement "div" #js {:className "panel-body"} (.-children props))
    (react/createElement "footer" #js {:className "panel-footer"} (.-footer props))))

(defn- row-list
  "A vendor list with a RENDER PROP: `renderRow` is invoked during this
  component's own render, once per item, and its return is markup. The
  `li` and its key are the vendor's, so the callback's whole job is the
  row's content — which is exactly the shape that makes an unconverted
  hiccup return reach React."
  [^js props]
  (react/createElement "ul" #js {:className "rows"}
    (.map (.-items props)
          (fn [item i]
            (react/createElement "li" #js {:className "row" :key i}
              ((.-renderRow props) item i))))))

(defn- theme-badge
  "A consumer BELOW the provider: it reads the context, so a crossing
  that broke the provider's element chain shows up here as the context
  DEFAULT rather than as an exception."
  [_props]
  (react/createElement "span" #js {:className "badge"}
                       (react/useContext theme-context)))

;; ---------------------------------------------------------------------------
;; The declarations
;; ---------------------------------------------------------------------------

(h/defhost panel-host panel {:slots #{:title :footer}})

(h/defhost bare-panel
  "The SAME component, declared with no slots at all — the control the
  sabotage row needs."
  panel)

(h/defhost rows-host row-list {:callbacks {:render-row :render}})

(h/defhost themed (.-Provider theme-context) {:ssr :render})
(h/defhost badge theme-badge {:ssr :render})

(h/defhost suspense
  "React's own compound, declared the way chapter 20 teaches it."
  react/Suspense
  {:slots #{:fallback}})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(defn- skip! [why]
  (is true (str "a ReactNode-slot DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.slots/seed]))
  frame-id)

(defn- db [] (rf/app-db-value frame-id))
(defn- query [handle sel] (.querySelector (:container handle) sel))
(defn- text [handle sel] (some-> (query handle sel) .-textContent))

(defn- crossing-props
  "The props object a crossing BUILT — read off the element the codec
  answered, which is the object the foreign component receives. No DOM
  and no render: the conversion is what is under test, so the assertion
  is taken where the conversion happens."
  [hiccup]
  (.-props (codec/root-element frame-id hiccup)))

;; ---------------------------------------------------------------------------
;; 1 — the declaration (these rows run under :node-test too)
;; ---------------------------------------------------------------------------

(deftest the-declaration-names-its-reactnode-positions
  (testing "a slot is declared however the author spells the prop, and is
            normalised to its canonical slot at MINT — so `:on-empty` and
            a call site writing `:onEmpty` are the one position, which is
            the rule every other roster in this codec already keeps"
    (is (some? (codec/mint-host! "slots/kebab" panel {:slots #{:on-empty}})))
    (is (some? (codec/mint-host! "slots/camel" panel {:slots #{:onEmpty}})))
    (is (some? (codec/mint-host! "slots/string" panel {:slots #{"title"}}))))
  (testing "and it composes with the two options that were already there"
    (is (some? (codec/mint-host! "slots/all" panel
                                 {:callbacks {:on-close :event}
                                  :slots     #{:title}
                                  :ssr       :render})))))

(deftest the-declaration-refuses-a-slots-set-it-cannot-honour
  (testing "not a set. A vector or a map would each have to be read as
            something, and neither reading is one an author could hold"
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/vec" panel {:slots [:title]}))))
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/map" panel {:slots {:title true}}))))
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/kw" panel {:slots :title}))))
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/nil" panel {:slots nil})))
        "an explicit nil is a value and not an absence — the same rule
         `:ssr nil` takes, and for the same reason: inferring the default
         from nil is how a typo becomes a setting"))
  (testing "an entry that names no prop. Normalising a number into some
            slot nobody wrote is how a declaration comes to be inert"
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/num" panel {:slots #{7}}))))
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/nested" panel {:slots #{[:title]}})))))
  (testing "`key` and `ref` in every spelling. They are React's structural
            slots — an identity contract and a node handle — and neither
            carries markup, so a slot declared on one is a policy that can
            never apply"
    (doseq [k [:key :ref "ref" :x/ref]]
      (is (= :rf.error/hicasso-host-bad-slots
             (error-id #(codec/mint-host! (str "slots/" k) panel {:slots #{k}})))
          (str (pr-str k) " must stay refused"))))
  (testing "two spellings of one slot. Whichever the fold reached second
            would silently be the same entry, so the set would carry one
            position the author wrote twice and could not see"
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/dup" panel
                                        {:slots #{:on-empty :onEmpty}})))))
  (testing "and a position declared BOTH a callback and a slot. `:render`
            invokes the value where a slot lowers it, and nothing decides
            which the author meant — so it is refused rather than ordered"
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/both" panel
                                        {:callbacks {:title :render}
                                         :slots     #{:title}}))))
    (is (= :rf.error/hicasso-host-bad-slots
           (error-id #(codec/mint-host! "slots/both-spelled" panel
                                        {:callbacks {:on-empty :event}
                                         :slots     #{:onEmpty}})))
        "and across two spellings of it, because the collision is checked
         on the canonical slot like every other rule here")))

;; ---------------------------------------------------------------------------
;; 2 — the conversion, at the crossing (no DOM needed)
;; ---------------------------------------------------------------------------

(deftest a-declared-slot-crosses-as-a-reactnode
  (let [props (crossing-props [panel-host {:title  [:h2.t "Delete article?"]
                                           :footer "cancel"
                                           :size   "wide"}])]
    (testing "hiccup at a declared slot is an ELEMENT by the time the
              foreign component sees it — which is what the component's
              own ABI says that prop is"
      (is (react/isValidElement (.-title props)))
      (is (= "h2" (.-type (.-title props)))))
    (testing "a string at a declared slot passes as itself; a slot is a
              ReactNode position, and a string is already one"
      (is (= "cancel" (.-footer props))))
    (testing "and an ordinary prop beside them is untouched — declaring a
              slot changes that prop and no other"
      (is (= "wide" (.-size props))))))

(deftest an-undeclared-prop-is-still-data
  ;; THE SABOTAGE ROW. Same component, same prop, same hiccup — with the
  ;; declaration removed. If this ever answers an element, the codec has
  ;; started guessing that a vector is markup, and every foreign ABI that
  ;; legitimately takes a vector of data is broken by the guess.
  (let [props (crossing-props [bare-panel {:title [:h2.t "Delete article?"]}])]
    (testing "at an UNDECLARED prop the vector crosses as data, silently,
              exactly as it did before slots existed. That is not a defect
              left standing: which prop is markup is a fact about the
              foreign ABI, so the runtime never infers it and the author
              declares it"
      (is (not (react/isValidElement (.-title props))))
      (is (array? (.-title props))))))

(deftest an-h-fn-at-a-declared-slot-is-refused
  (testing "the marked form asks the POSITION for a contract, and a
            markup position has none to give — it lowers hiccup. Left to
            cross it would be an ordinary function at a ReactNode prop:
            the silently-dead class every loud error in this codec exists
            to delete"
    (is (= :rf.error/hicasso-host-unclaimed-callback
           (error-id #(crossing-props
                        [panel-host {:title (h/hfn [] [:h2 "no"])}])))))
  (testing "while a PLAIN function at the same prop of an undeclared
            crossing is untouched, because it never asked for anything"
    (is (fn? (.-title (crossing-props [bare-panel {:title (fn [] nil)}]))))))

(deftest a-ref-at-a-crossing-keeps-hd-022s-rules
  ;; Named by this bead and unchanged by it: a slot declaration adds a
  ;; position beside `ref` and does not touch it. The row is here because
  ;; the package had the rule witnessed at a NATIVE tag only, and `ref` is
  ;; the one prop a crossing forwards rather than converts.
  (testing "a callback ref crosses by identity — React 19 carries `ref` as
            an ordinary prop, so the gate forwards the very function the
            author wrote and the identity React re-attaches on is theirs"
    (let [f     (fn [_])
          props (crossing-props [panel-host {:ref f :title [:h2.t "T"]}])]
      (is (identical? f (.-ref props)))))
  (testing "and a VECTOR there is the reserved data spelling, refused at
            the crossing rather than handed to React as an array it would
            ignore in silence (HD-022)"
    (is (= :rf.error/hicasso-ref-vector-reserved
           (error-id #(crossing-props
                        [panel-host {:ref [::autosize {:max-rows 8}]}]))))))

(deftest the-escape-has-no-slots-and-cannot-be-given-any
  (testing "`[:>]` is `defhost` with the declaration erased, so what
            erasing the declaration costs is exactly what the declaration
            carried — here, every ReactNode position. Hiccup at a `[:>]`
            prop is data"
    (let [props (crossing-props [:> panel {:title [:h2.t "Tasks"]}])
          inner (.-p props)]
      (is (array? (.-title inner)))))
  (testing "and the per-site recovery is the explicit conversion, which
            crosses a real element through any prop of any crossing"
    (let [props (crossing-props
                  [:> panel {:title (h/as-element [:h2.t "Tasks"])}])
          inner (.-p props)]
      (is (react/isValidElement (.-title inner)))
      (is (= "h2" (.-type (.-title inner)))))))

(deftest the-suspense-recipe-mints-and-carries-an-element
  (testing "React's own compound, declared as chapter 20 teaches it. The
            fallback is a ReactNode prop, so the recipe is a slot
            declaration and nothing else"
    (let [props (crossing-props [suspense {:fallback [:p.skeleton "loading"]}])]
      (is (react/isValidElement (.-fallback props)))
      (is (= "p" (.-type (.-fallback props)))))))

;; ---------------------------------------------------------------------------
;; 3 — the compound library, mounted
;; ---------------------------------------------------------------------------

(h/defview compound-screen
  "A provider, two members, two named slots and a render prop, in one
  tree — the family exercised the way an application would use it."
  [_]
  (let [rows (h/sub [:hicasso.slots/rows])]
    [themed {:value "dark"}
     [panel-host {:title  [:h2.t "Feed"]
                  :footer [:button.pick {:on-click [:hicasso.slots/pick "footer"]}
                           "pick from the footer"]}
      [badge {}]
      [rows-host
       {:items      (into-array rows)
        :render-row (h/hfn [item _i]
                      (h/as-element
                        [:span.cell {:on-click [:hicasso.slots/pick item]}
                         item]))}]]]))

(deftest the-compound-family-renders-through-one-declared-door
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [compound-screen {}])]
        (try
          (testing "the two declared slots reached the component as markup
                    and it placed them where its own ABI says"
            (is (= "Feed" (text handle ".panel-title .t")))
            (is (= "pick from the footer" (text handle ".panel-footer .pick"))))
          (testing "children still arrive as children — a slot declaration
                    adds a position and moves none"
            (is (some? (query handle ".panel-body .badge"))))
          (testing "and the provider's context flows through the crossing
                    unbroken, which is the claim a compound family is
                    really making: these are REAL React elements"
            (is (= "dark" (text handle ".badge"))))
          (testing "the render prop built one row per item, through the
                    explicit conversion"
            (is (= 2 (.-length (.querySelectorAll (:container handle) ".row"))))
            (is (= "alpha" (text handle ".rows .row .cell"))))
          (finally (mount/release! handle)))))))

(deftest an-intent-inside-a-declared-slot-fires-into-the-declaring-frame
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [compound-screen {}])]
        (try
          (is (nil? (:picked (db))) "the premise")
          (testing "a slot is lowered in the render window of the boundary
                    that WROTE the crossing, so its intents are that
                    boundary's — the same frame the crossing's children
                    get, and the same one an intent in the body gets"
            (.click (query handle ".panel-footer .pick"))
            (mount/settle!)
            (is (= "footer" (:picked (db)))))
          (finally (mount/release! handle)))))))

(deftest a-render-prop-returns-markup-through-as-element
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [compound-screen {}])]
        (try
          (testing "the row was built during the LIBRARY's render, and its
                    intent fires later, on the user's click, into the frame
                    of the boundary that supplied the callback"
            (.click (query handle ".rows .row .cell"))
            (mount/settle!)
            (is (= "alpha" (:picked (db)))))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — adversarial: StrictMode, remount, and a throw inside a slot
;; ---------------------------------------------------------------------------

(h/defview strict-screen [_]
  [:> react/StrictMode {} [compound-screen {}]])

(deftest strict-mode-double-invocation-changes-nothing
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [strict-screen {}])]
        (try
          (testing "React renders every component twice under StrictMode.
                    A slot's conversion is a pure function of the form, so
                    the second pass answers a second element with the same
                    content and the DOM carries ONE of each"
            (is (= 1 (.-length (.querySelectorAll (:container handle) ".panel-title"))))
            (is (= "Feed" (text handle ".panel-title .t")))
            (is (= 2 (.-length (.querySelectorAll (:container handle) ".row")))))
          (testing "and the callbacks the double pass minted twice still
                    reach the one frame"
            (.click (query handle ".panel-footer .pick"))
            (mount/settle!)
            (is (= "footer" (:picked (db)))))
          (finally (mount/release! handle)))))))

(deftest a-remount-rebuilds-the-slots-and-nothing-leaks
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [a (mount/root! (mount/fresh-container!) frame-id [compound-screen {}])]
        (is (= "Feed" (text a ".panel-title .t")))
        (mount/release! a))
      (fresh!)
      (let [b (mount/root! (mount/fresh-container!) frame-id [compound-screen {}])]
        (try
          (testing "a declaration is minted once and a slot is lowered per
                    crossing, so a second mount of the same declaration
                    builds fresh elements and inherits nothing from the
                    first — including its frame"
            (is (= "Feed" (text b ".panel-title .t")))
            (.click (query b ".panel-footer .pick"))
            (mount/settle!)
            (is (= "footer" (:picked (db)))))
          (finally (mount/release! b)))))))

(h/defview thrown-slot-screen
  "A slot whose markup is malformed. The refusal is raised while the
  CROSSING is lowered, which is inside this boundary's own render — so a
  React error boundary above it is what catches, exactly as it would for
  a body that threw."
  [_]
  [panel-host {:title []} "body"])

(def ^:private !caught (atom nil))

(deftest a-refusal-inside-a-slot-escapes-to-the-boundary-above
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (reset! !caught nil)
      (let [handle (mount/root! (mount/fresh-container!) frame-id
                                [h/boundary
                                 {:fallback [:p.escaped "the slot refused"]
                                  :on-error (fn [e] (reset! !caught e))}
                                 [thrown-slot-screen {}]])]
        (try
          (testing "a slot is lowered by the SAME walk the children take,
                    so its refusals are the walk's own and arrive with the
                    walk's own id — never a foreign component's error
                    naming nothing the author wrote"
            (is (some? (query handle ".escaped")))
            (is (= :rf.error/hicasso-empty-vector
                   (:rf.error/id (ex-data @!caught)))))
          (finally (mount/release! handle)))))))
