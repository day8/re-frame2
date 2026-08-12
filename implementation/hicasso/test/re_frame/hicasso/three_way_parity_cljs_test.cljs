(ns re-frame.hicasso.three-way-parity-cljs-test
  "THREE-WAY PARITY — Hicasso-native against BOTH handwritten React and
  UIx (rf2-hic-034).

  > A native island should be within 5% or 1 ms of the same component
  > mounted directly through its chosen React route, excluding the single
  > explicit crossing. **The Hicasso-native surface is co-instrumented
  > against both handwritten React construction and UIx so the
  > convenience layer cannot define its own floor.**
  >
  > — `docs/design/hicasso/product/specification.md` §6

  The second sentence is why there are three routes and not two. A native
  tier measured only against UIx would be measured against a floor UIx
  itself sets: UIx is a convenience layer, it pays a per-render price for
  that convenience, and matching it proves nothing about whether the
  native tier costs what React costs. Handwritten `react/createElement`
  is the only arm with no convenience in it, so it is the arm that
  decides.

  ## The corpus, and why the rows are thunks

  Every row of [[corpus]] is ONE subject written three times. The three
  spellings are the whole content of a row — the rows below assert
  equalities BETWEEN them and never against a literal, because a literal
  is a fourth spelling that can drift from all three at once.

  They are thunks rather than values because two of the three are macro
  forms whose expansion is the thing under test: `n/$` lowers a literal
  props map at expansion and `uix/$` compiles its attributes at
  expansion, so a row evaluated once at namespace load would hide which
  arm did what and when.

  ## What each row proves, and what it deliberately does not

  | claim | how |
  |---|---|
  | same DOM output, same bytes | `react-dom/server` over the three arms — one string compared three ways |
  | same element shape | `.-type`, `.-key`, and the props object read as a map |
  | keys | a keyed seq, where React's own child reconciliation reads the slot |
  | SVG and custom elements | two heads whose attribute rules are not the ordinary ones |
  | dynamic props | the marked operand against a hand-built object |
  | children shapes | React's three — none, one, many — the shapes rf2-hic-032 repaired the outward bridge for |
  | component identity | the element type each route hands React |
  | same-frame reads | one key read through all three doors in one tree |
  | hook count | React's own dispatcher, through `hook-probe` |

  **Refs, cleanup and hydration are NOT here.** Each is a property of a
  fiber and a real document, and the node lane has neither: this file
  runs under `:node-test`, where a DOM claim degrades to a stated skip.
  They are `three_way_parity_dom_cljs_test`'s, which the browser lane
  decides.

  ## The island band, and the row this file does not pretend to be

  The bead's island-performance deliverable is `C7` in
  `docs/design/hicasso/product/budgets.md` §9, and it is `UNPINNED` with
  its authority recorded as `rf2-hic-071`. That page's §9.2 says why in
  terms: the 5% rule has no same-instrument anchor until the ladder is
  re-pinned, no package-resident clock instrument exists, and §7 forbids
  converting a distributional row into a pull-request threshold at all.
  §9.2 also names *this* bead as one of the three that supply C7's
  POPULATION — the landed islands the rule is stated over — which is the
  same statement from the other side.

  So this file lands the population and the DETERMINISTIC half of the
  band, which is the half a hosted runner is allowed to decide:
  [[a-native-component-is-the-authors-own-function-and-costs-no-wrapper]]
  and [[the-convenience-layer-pays-a-per-render-price-the-native-tier-does-not]]
  read the two routes' construction cost as a structural fact rather
  than as a clock. That is a stronger reading than a timing, not a weaker
  one: a native island and a handwritten React component are the SAME
  element type with the SAME props object, so there is no interposed work
  for a stopwatch to find. No number is transcribed into the ledger here;
  the figures are this file's own, and the ledger row is `rf2-hic-089`'s
  to transcribe, as D10–D13 were."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.hook-probe :as probe]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.test-support :as test-support]
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::parity)

(rf/reg-sub ::price (fn [db _] (:price db)))

(rf/reg-event ::seed (fn [_ [_ db]] {:db db}))

(rf/reg-event ::typed (fn [{:keys [db]} [_ typed]] {:db (assoc db :price typed)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (support/leave-act-environment!)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The three routes, as components
;; ---------------------------------------------------------------------------
;;
;; One subject: a cell that paints a label. Written once per route, with
;; nothing in any of them that the other two do not have.

(n/defcomponent native-cell
  "The native route. The ABI is one raw JavaScript props object and the
  body reads it by name.

  `{:server :render}` for the reason [[uix-host]] states about its own
  crossing, one tier down: since rf2-hic-046 the native tier READS its
  `:server` declaration, and the default is Client-only — a gate that
  contributes nothing to a server render and is not the author's own
  function. Both halves of this file need the declared arm: the markup
  rows below server-render this cell, and the identity rows assert that
  the element type IS the author's function, which is true of `:render`
  and false of the gate. Declaring it is what makes the three routes
  comparable, exactly as declaring the UIx crossing is."
  {:server :render}
  [^js props]
  (n/$ :span {:class "cell"} (.-label props)))

(defn react-cell
  "The handwritten route — an ordinary React function component, which is
  what `n/defcomponent` compiles to and the arm the band is measured
  against."
  [^js props]
  (react/createElement "span" #js {:className "cell"} (.-label props)))

(defui uix-cell
  "The UIx route. Its ABI is a ClojureScript props MAP, which is the
  convenience — and the price."
  [{:keys [label]}]
  (uix/$ :span {:class "cell"} label))

;; ---------------------------------------------------------------------------
;; The corpus
;; ---------------------------------------------------------------------------

(def ^:private corpus
  "Matched triples. Every row is one subject in three spellings, and
  `:proves` names the equality the row exists to establish rather than
  describing the markup — a row whose only claim is *these render the
  same* is a row that would survive all three arms being wrong together."
  [{:name   "intrinsic element, literal props, one text child"
    :proves "the ordinary case: a ClojureScript props map lowers to the same
             React slots on all three routes, so `:class` is `className`
             wherever it is written"
    :native #(n/$ :span {:class "cell" :id "c1"} "42")
    :react  #(react/createElement "span" #js {:className "cell" :id "c1"} "42")
    :uix    #(uix/$ :span {:class "cell" :id "c1"} "42")}

   {:name   "a keyed sequence of children"
    :proves "the `:key` slot is React's own on every route — the one prop React
             itself reads, and the one a route that invented its own child
             identity would get wrong"
    :native #(n/$ :ul nil
                  (n/$ :li {:key "a"} "a")
                  (n/$ :li {:key "b"} "b"))
    :react  #(react/createElement "ul" nil
                                  (react/createElement "li" #js {:key "a"} "a")
                                  (react/createElement "li" #js {:key "b"} "b"))
    :uix    #(uix/$ :ul nil
                    (uix/$ :li {:key "a"} "a")
                    (uix/$ :li {:key "b"} "b"))}

   {:name   "an SVG element with a dashed attribute"
    :proves "SVG's attribute rules are React's, not the substrate's: `stroke-width`
             becomes `strokeWidth` and the element lands in the SVG namespace on
             all three routes"
    :native #(n/$ :svg {:viewBox "0 0 8 8"}
                  (n/$ :circle {:cx 4 :cy 4 :r 3 :stroke-width 2}))
    :react  #(react/createElement "svg" #js {:viewBox "0 0 8 8"}
                                  (react/createElement "circle" #js {:cx 4 :cy 4 :r 3 :strokeWidth 2}))
    :uix    #(uix/$ :svg {:view-box "0 0 8 8"}
                    (uix/$ :circle {:cx 4 :cy 4 :r 3 :stroke-width 2}))}

   {:name   "a custom element, spelled as a string head"
    :proves "a head with a dash is a custom element on every route and its
             attributes pass through unrenamed — the case where React's own
             rule CHANGES, so a route carrying its own attribute table would
             diverge here and nowhere else"
    :native #(n/$ "my-widget" #js {:data-size "lg"} "w")
    :react  #(react/createElement "my-widget" #js {:data-size "lg"} "w")
    :uix    #(uix/$ :my-widget {:data-size "lg"} "w")}

   {:name   "a dynamic props operand"
    :proves "a props map built at runtime reaches the same React slots as the
             literal one — on the native route through the explicit `n/props`
             marker, which is the whole of what the marker does"
    :native #(let [m {:class "cell" :id "c1"}] (n/$ :span (n/props m) "42"))
    :react  #(let [o #js {}]
               (unchecked-set o "className" "cell")
               (unchecked-set o "id" "c1")
               (react/createElement "span" o "42"))
    :uix    #(let [m {:class "cell" :id "c1"}] (uix/$ :span m "42"))}

   {:name   "no children"
    :proves "React's first children shape. rf2-hic-032 repaired the outward
             bridge over exactly these three, because React's `children` slot
             is absent, a bare value and an array in turn — and a route that
             read it as one shape got the other two wrong"
    :native #(n/$ :span {:class "cell"})
    :react  #(react/createElement "span" #js {:className "cell"})
    :uix    #(uix/$ :span {:class "cell"})}

   {:name   "exactly one child"
    :proves "React's second children shape — the slot holds the child itself,
             not a one-element array"
    :native #(n/$ :span {:class "cell"} (n/$ :b nil "one"))
    :react  #(react/createElement "span" #js {:className "cell"}
                                  (react/createElement "b" nil "one"))
    :uix    #(uix/$ :span {:class "cell"} (uix/$ :b nil "one"))}

   {:name   "many children, mixed element and text"
    :proves "React's third children shape, with the mixture that makes it
             interesting: text and elements interleaved, where a route that
             normalised children would show it"
    :native #(n/$ :p nil "a" (n/$ :b nil "b") "c" (n/$ :i nil "d"))
    :react  #(react/createElement "p" nil "a"
                                  (react/createElement "b" nil "b") "c"
                                  (react/createElement "i" nil "d"))
    :uix    #(uix/$ :p nil "a" (uix/$ :b nil "b") "c" (uix/$ :i nil "d"))}

   {:name   "a component head"
    :proves "the routes agree about a COMPONENT and not only about intrinsic
             elements — three different element types, three different props
             ABIs, one rendering"
    :native #(n/$ native-cell #js {:label "42"})
    :react  #(react/createElement react-cell #js {:label "42"})
    :uix    #(uix/$ uix-cell {:label "42"})}])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- markup
  "The server markup for an already-constructed React element."
  [el]
  (react-dom-server/renderToString el))

(defn- props-map
  "`el`'s props object read as a ClojureScript map, with `children`
  dropped.

  Children are compared by the markup rows; what is compared here is the
  PROP surface, and a nested element in the map would compare React
  element objects by identity and never be equal across two routes."
  [^js el]
  (let [p (.-props el)]
    (into {}
          (comp (remove #(= "children" %))
                (map (fn [k] [k (unchecked-get p k)])))
          (js/Object.keys p))))

(defn- scalar-props
  "[[props-map]] keeping only the entries a cross-route comparison can
  decide — strings, numbers, booleans and nil. A function or an object
  prop is compared by identity, which two independently written arms can
  never satisfy and which no row here is about."
  [el]
  (into {} (remove (fn [[_ v]] (or (fn? v) (object? v)))) (props-map el)))

(defn- refusal
  "Run `f` and answer the refusal's ex-data — or a marker saying what
  happened instead, so a failing row reports WHICH of the two ways it
  failed rather than throwing out of the assertion. The shape
  `native_grammar_cljs_test` uses, for the same reason."
  [f]
  (try
    (f)
    {::outcome :returned-without-refusing}
    (catch :default e
      (or (ex-data e) {::outcome :threw-without-ex-data ::message (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; 0. The instrument can tell two markups apart
;; ---------------------------------------------------------------------------
;;
;; Every row below is of the form *these three are equal*, and equality is
;; trivially satisfied by an instrument that answers the same thing to
;; everything. So the first row drives a deliberate MISMATCH and asserts
;; the instrument reports it, exactly as `hook_budget_cljs_test` counts to
;; three before asserting two.

(deftest the-instrument-reports-a-difference-when-there-is-one
  (let [a (markup (react/createElement "span" #js {:className "cell"} "42"))
        b (markup (react/createElement "span" #js {:className "cell"} "43"))]

    (testing "the premise: the renderer produced real markup rather than an
              empty string, which every equality below would also satisfy"
      (is (seq a))
      (is (some? (re-find #"<span" a))))

    (testing "and one changed character is seen — so `=` below is a reading
              taken by an instrument known to discriminate"
      (is (not= a b)))

    (testing "the same for the element reader: two different classes are two
              different prop maps"
      (is (not= (scalar-props (react/createElement "span" #js {:className "one"}))
                (scalar-props (react/createElement "span" #js {:className "two"})))))))

;; ---------------------------------------------------------------------------
;; 1. The three routes render the same bytes
;; ---------------------------------------------------------------------------

(deftest the-three-routes-render-the-same-server-bytes
  (doseq [{:keys [name proves native react uix]} corpus]
    (testing (str name " — " proves)
      (let [handwritten (markup (react))]

        (testing "the premise: the handwritten arm rendered something"
          (is (seq handwritten)))

        (testing "Hicasso-native against handwritten React, which is the
                  comparison that decides — no convenience layer sets this
                  floor"
          (is (= handwritten (markup (native)))))

        (testing "and UIx against the same handwritten arm, so the three are
                  one subject rather than two agreeing pairs"
          (is (= handwritten (markup (uix)))))))))

;; ---------------------------------------------------------------------------
;; 2. The three routes build the same element
;; ---------------------------------------------------------------------------
;;
;; Markup is the output; this is the CONSTRUCTION. A route could reach the
;; same bytes through a different element shape — an extra wrapper, a
;; normalised child array, a key moved into props — and the rows above
;; would not see it.

(deftest the-three-routes-build-the-same-element-shape
  (doseq [{:keys [name proves native react uix]} corpus]
    (testing (str name " — " proves)
      (let [^js r (react)
            ^js n (native)
            ^js u (uix)]

        (testing "the element TYPE. For an intrinsic element it is the tag
                  string, identically on all three routes; for a COMPONENT
                  head the three types are three different functions — one
                  per route — which is not a divergence but the subject:
                  three components rendering one output is what parity means
                  here, and a row asserting they were the same object would
                  be asserting the corpus had only one arm"
          (if (string? (.-type r))
            (do (is (= (.-type r) (.-type n)))
                (is (= (.-type r) (.-type u))))
            (do (is (fn? (.-type r)))
                (is (fn? (.-type n)))
                (is (fn? (.-type u)))
                (is (= 3 (count (distinct [(.-type r) (.-type n) (.-type u)])))
                    "three arms, not two aliases of one"))))

        (testing "the `key` slot, which React reads itself"
          (is (= (.-key r) (.-key n)))
          (is (= (.-key r) (.-key u))))

        (testing "and the scalar props, slot by slot — the native route's
                  lowering is `impl/slot`'s and produces React's names"
          (is (= (scalar-props r) (scalar-props n)))
          (when (string? (.-type r))
            (is (= (scalar-props r) (scalar-props u))
                "UIx too, for an intrinsic element, where all three carry
                 React's own props object. A COMPONENT head is the one place
                 the three ABIs differ, and that difference is its own row
                 rather than an exception hidden here")))))))

;; ---------------------------------------------------------------------------
;; 3. Component identity — the native route's zero wrapper
;; ---------------------------------------------------------------------------
;;
;; This is the deterministic half of the island band. See the namespace
;; docstring: C7 is UNPINNED and its clock is rf2-hic-071's, but the
;; structural fact underneath it is decidable here and is the stronger
;; statement — there is no interposed work for a stopwatch to find.

(deftest a-native-component-is-the-authors-own-function-and-costs-no-wrapper
  (testing "`n/defcomponent` answers a FUNCTION, and it is the element type
            React reconciles on — not a wrapper holding one"
    (is (fn? native-cell))
    (is (identical? native-cell (.-type (n/$ native-cell #js {:label "42"})))))

  (testing "so the props React hands the component carry the author's own
            slot and nothing else. Stated as a KEY SET rather than by
            identity, because `React.createElement` copies its config into
            a fresh props object on every route and always has — a row
            asserting identity would be asserting a fact about React that
            is not true of any of the three arms"
    (is (= ["label"] (vec (js/Object.keys (.-props (n/$ native-cell #js {:label "42"})))))))

  (testing "and the body runs when the function is CALLED — no fiber, no
            hook, no shell. This is what `a native island costs what React
            costs` means as a structural fact rather than a timing"
    (let [el (native-cell #js {:label "42"})]
      (is (= "span" (.-type el)))
      (is (= "42" (.-children (.-props el))))))

  (testing "the handwritten arm is the same three sentences, which is the
            point: the two routes are not close, they are identical in
            shape, and the band excludes only the one explicit crossing"
    (is (fn? react-cell))
    (is (identical? react-cell (.-type (react/createElement react-cell #js {:label "42"}))))
    (is (= ["label"] (vec (js/Object.keys (.-props (react/createElement react-cell #js {:label "42"}))))))))

(deftest the-convenience-layer-pays-a-per-render-price-the-native-tier-does-not
  (testing "UIx's element carries a CARRIER object rather than the props the
            author wrote — `#js {:argv <the map>}` — so the props a UIx
            component receives are not the props at the call site"
    (let [^js el (uix/$ uix-cell {:label "42"})]
      (is (some? (.-argv (.-props el)))
          "the carrier slot is there")
      (is (= {:label "42"} (.-argv (.-props el)))
          "and it holds the ClojureScript map the author wrote")))

  (testing "read as a figure, which is what makes it comparable: the props
            object React carries has ONE slot on every route, and on two of
            them it is the author's `label` while on UIx it is a carrier the
            author never wrote. That is one unwrapping hop per render, per
            component, on the UIx route and ZERO on the other two — the
            convenience being paid for, and exactly why §6 requires the
            native tier to be co-instrumented against handwritten React as
            well: a floor set by UIx would have this hop inside it"
    (let [slots (fn [^js el] (vec (js/Object.keys (.-props el))))]
      (is (= ["label"] (slots (n/$ native-cell #js {:label "42"}))))
      (is (= ["label"] (slots (react/createElement react-cell #js {:label "42"}))))
      (is (= ["argv"]  (slots (uix/$ uix-cell {:label "42"})))))

    (let [^js uix-el    (uix/$ uix-cell {:label "42"})
          ^js native-el (n/$ native-cell #js {:label "42"})]
      (is (nil? (.-label (.-props uix-el)))
          "UIx's element does not carry the prop at its own name")
      (is (= "42" (.-label (.-props native-el)))
          "the native element does — the body reads `.-label` off the props
           object React built from the very map the call site wrote")))

  (testing "and the two routes' element types are different KINDS of thing:
            the native tier's is the author's own function, UIx's is a
            generated component around a body the author wrote separately"
    (is (identical? native-cell (.-type (n/$ native-cell #js {:label "42"}))))
    (is (true? (.-uix-component? uix-cell))
        "UIx stamps its own components, which is how it recognises one — and
         the native tier's function carries no such stamp because it is not
         wrapped")))

;; ---------------------------------------------------------------------------
;; 4. One frame, read through all three doors
;; ---------------------------------------------------------------------------

(n/defcomponent island
  "The native route's read: `n/use-sub`, a real React hook.
  `{:server :render}` for [[native-cell]]'s reason — the page below is
  server-rendered and a Client-only island would leave the premise row
  reading two arms and calling it three."
  {:server :render}
  [_props]
  (n/$ :b {:class "island"} (str (n/use-sub [::price]))))

(defui uix-reader
  "The UIx route's read, through the adapter's own hook, in the EXPLICIT
  two-argument form.

  Explicit and not ambient, and the reason is a property of the lane
  rather than a shortcut. The spine's one-argument form resolves the
  frame through `frame/require-current-frame!`, which reaches the
  React-context tier by reading `context._currentValue` — a client-render
  fact. Under `react-dom/server` that read finds nothing and the hook
  refuses with `:rf.error/no-frame-context`, whether or not the adapter's
  own `frame-provider` is above it (measured: it was, and it did).
  Spec 002's own ladder names the explicit `{:frame <id>}` as the third
  of three ways to establish a scope, so this arm takes it.

  What that costs the row is nothing: the claim is that all three routes
  read ONE frame and agree, not that all three discover it by the same
  mechanism — they demonstrably do not, since the Hicasso arms resolve
  through `impl/collector`'s own context read. The ambient UIx form is
  the browser lane's to exercise, and `re-frame.adapter.uix`'s own
  `uix_use_subscribe_dom_cljs_test` is where it already is."
  [_]
  (uix/$ :u {:class "uix"} (str (uix-adapter/use-subscribe frame-id [::price]))))

(defn uix-arm
  "The crossing into the UIx tree, and it is a PLAIN React component
  deliberately.

  UIx's ABI is a carrier object its own `uix/$` builds — `#js {:argv m}`
  — so a `defui` reached through any door but `uix/$` receives props it
  cannot read. A plain React component is therefore the honest shim, and
  it is what the crossing looks like in an application too."
  [_props]
  (uix/$ uix-reader))

(h/defhost uix-host
  "The UIx arm's crossing into the tree, through the named door.

  `{:server :render}` and not the default, and the reason belongs in the
  file: `[:>]` and an undeclared `defhost` are `:client-only`, which is a
  policy about the SERVER — the gate withholds the foreign component and
  renders its fallback. That is correct conduct and it is measured in
  `hook_budget_cljs_test`; here it would delete the arm this row exists
  to compare, and the first draft of this file read two arms agreeing and
  called it three. The premise row below is what caught it."
  uix-arm
  {:server :render})

(h/defview boundary
  "The interpreted route's read: `h/sub`, the ambient collector."
  [_]
  [:i.boundary (str (h/sub [::price]))])

(h/defview page
  "One tree holding all three, so the reads are of the same frame in the
  same render rather than three separate renders that happen to agree."
  [_]
  [:div.page
   [boundary {}]
   (n/$ island nil)
   [uix-host {}]])

(defn- seeded!
  []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed {:price 191}]))
  frame-id)

(defn- server-render!
  "Render `hiccup` under the frame through React's own server renderer,
  answering the markup and the hook names React was asked for, in call
  order."
  [hiccup]
  (let [!html (volatile! nil)
        hooks (probe/record!
                (fn []
                  (vreset! !html
                           (react-dom-server/renderToString
                             (mount/provider frame-id
                                             (codec/root-element frame-id hiccup))))))]
    {:html @!html :hooks hooks}))

(deftest the-three-routes-read-one-frame-and-agree
  (seeded!)
  (is (true? (probe/install!))
      "React's client-internals dispatcher slot was not found — the hook
       counts below are UNWITNESSED, not satisfied")
  (let [{:keys [html hooks]} (server-render! [page {}])]

    (testing "the premise: all three arms rendered, so the agreement below is
              between three readings and not between one and two absences"
      (is (some? (re-find #"boundary" html)))
      (is (some? (re-find #"island" html)))
      (is (some? (re-find #"uix" html))))

    (testing "and all three painted the SAME value — one key, one frame, three
              doors. An island is a place in the application, not a second
              application"
      (is (= 3 (count (re-seq #">191<" html)))))

    (testing "the six hooks Hicasso owns, in the order React ran them: the
              page's own shell, the nested boundary's shell, and the
              island's `n/use-sub`. THREE readers, TWO hooks each — and the
              island's pair is indistinguishable from a boundary shell's,
              which is the parity statement in its sharpest form: crossing
              into the native tier does not change what a read costs"
      (is (= ["useContext" "useSyncExternalStore"
              "useContext" "useSyncExternalStore"
              "useContext" "useSyncExternalStore"]
             (vec (take 6 hooks)))))

    (testing "I9 holds at two, read off the ledger the shell declares — the
              three-route tree above added a native island and a foreign
              UIx subtree and moved it by nothing"
      (is (= 2 (count collector/shell-hook-ledger))))

    (testing "and neither `useRef` nor `useState` is among them: HD-020(b)'s
              two named prohibitions, over every hook Hicasso spent on this
              page. The UIx arm below DOES call `useRef` — three times — and
              that is its own affair and not charged here, exactly as
              `hook_budget_cljs_test` establishes for a hosted component's
              own roster"
      (is (= [] (filterv #{"useRef" "useState"} (vec (take 6 hooks))))))

    (testing "the crossing itself cost no hook: `{:server :render}` mints no
              gate, so everything past the sixth is the foreign subtree's
              own and the door contributed nothing to the count"
      (is (seq (drop 6 hooks))
          "the premise — the UIx arm really ran hooks of its own, so the
           statement above is about a populated tail rather than an empty
           one"))))

;; ---------------------------------------------------------------------------
;; 5. The leakage matrix — the mix the fence exists for
;; ---------------------------------------------------------------------------
;;
;; The bead names four ambiguous mixes. Three are already witnessed and are
;; NOT re-asserted here, because a second copy of a row is a second thing to
;; drift: nested hiccup in a native child and an intent vector at a native
;; callback are `native_grammar_cljs_test`'s
;; (`a-hiccup-vector-in-child-position-refuses`,
;; `an-event-vector-at-a-native-callback-slot-refuses`), and dynamic heads
;; and props are its `heads-are-classified-syntactically` and
;; `a-dynamic-element-in-props-position-is-a-child`.
;;
;; The fourth has no witness anywhere, and it is the one with the most to
;; go wrong: a CONTROLLED FIELD written inside a native subtree. Every part
;; of the interpreted controlled-field law — I15's converge-in-turn, the
;; caret, the revision marker, the shadow component — is installed by
;; `impl/controlled` from the CODEC, and the codec never runs past the
;; fence. So the same source shape means one thing on each side, and the
;; row below is that pair measured in one breath.

(h/defview interpreted-field
  "The controlled field as the interpreted tier means it: `:value` is a
  subscription and `:on-input` is an event vector."
  [_]
  [:input#interpreted {:type     "text"
                       :value    (str (h/sub [::price]))
                       :on-input [::typed ::h/value]}])

(deftest a-controlled-field-means-one-thing-in-hiccup-and-refuses-past-the-fence
  (seeded!)

  (testing "in hiccup the field is repaired: the codec routes a convergeable
            input through `controlled/install!`, so what React receives is
            the shadow component and not the bare tag"
    (let [{:keys [html]} (server-render! [interpreted-field {}])]
      (is (some? (re-find #"id=\"interpreted\"" html)))
      (is (some? (re-find #"value=\"191\"" html)))))

  (testing "past the fence the SAME source shape refuses, and it refuses on
            the intent rather than on the value — `:on-input` is a React
            callback slot, nothing lowers an event vector there, and React
            would otherwise be handed the vector itself"
    (let [data (refusal #(n/props {:type "text" :value "191" :on-input [::typed ::h/value]}))]
      (is (= :rf.error/hicasso-native-intent-in-prop (:rf.error/id data)))
      (is (= "onInput" (:slot data))
          "the CONTROLLED-field slot specifically. `native_grammar_cljs_test`
           drives `onClick`; the field family reaches the same rule by the
           same route, and this row is what says the two are one rule")
      (is (= 're-frame.hicasso.native/props (:where data))
          "and it names the form that refused, which is the recovery the
           author needs")))

  (testing "so there is no silent third behaviour: the value prop alone is
            legal past the fence and passes by identity, which is React's
            own read-only controlled input — the author's to complete with a
            handler, and never repaired behind their back"
    (let [^js el (n/$ :input (n/props {:type "text" :value "191"}))]
      (is (= "input" (.-type el)))
      (is (= "191" (.-value (.-props el))))
      (is (nil? (.-onChange (.-props el)))
          "nothing was installed — past the fence there is no controlled
           repair, which is the fence's whole content"))))
