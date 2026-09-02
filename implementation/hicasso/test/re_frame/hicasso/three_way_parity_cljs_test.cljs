(ns re-frame.hicasso.three-way-parity-cljs-test
  "TWO-ARM PARITY — handwritten React against UIx, both through
  `h/defhost`.

  > A native island should be within 5% or 1 ms of the same component
  > mounted directly through its chosen React route, excluding the single
  > explicit crossing. **The Hicasso-native surface is co-instrumented
  > against both handwritten React construction and UIx so the
  > convenience layer cannot define its own floor.**
  >
  > — `docs/design/hicasso/product/specification.md` §6

  The native authoring tier this file used to measure as a third arm was
  retired by the rf2-6c12m.3 ruling: an island is now a raw React
  component or a UIx `defui`, mounted through `h/defhost`, using
  `n/use-sub` / `n/use-frame` when it needs Hicasso state. What survives
  here is the FLOOR that ruling kept — handwritten `react/createElement`
  is the only arm with no convenience in it, so it is the arm that
  decides what a crossing costs, and UIx is measured against it rather
  than against itself.

  The file keeps its name because `docs/design/hicasso/product/budgets.md`
  rows D14 and D16 name it as their witness.

  ## The corpus, and why the rows are thunks

  Every row of [[corpus]] is ONE subject written twice. The two
  spellings are the whole content of a row — the rows below assert
  equalities BETWEEN them and never against a literal, because a literal
  is a third spelling that can drift from both at once.

  They are thunks rather than values because `uix/$` compiles its
  attributes at expansion, so a row evaluated once at namespace load
  would hide which arm did what and when.

  ## What each row proves, and what it deliberately does not

  | claim | how |
  |---|---|
  | same DOM output, same bytes | `react-dom/server` over the two arms — one string compared both ways |
  | same element shape | `.-type`, `.-key`, and the props object read as a map |
  | keys | a keyed seq, where React's own child reconciliation reads the slot |
  | SVG and custom elements | two heads whose attribute rules are not the ordinary ones |
  | dynamic props | a hand-built object against a runtime map |
  | children shapes | React's three — none, one, many — the shapes the outward bridge carries |
  | component identity | the element type each route hands React, through the crossing |
  | same-frame reads | one key read through the interpreted door and both foreign doors in one tree |
  | hook count | React's own dispatcher, through `hook-probe` |

  **Refs, cleanup and hydration are NOT here.** Each is a property of a
  fiber and a real document, and the node lane has neither: this file
  runs under `:node-test`, where a DOM claim degrades to a stated skip.
  They are `three_way_parity_dom_cljs_test`'s, which the browser lane
  decides.

  ## The island band, and the row this file does not pretend to be

  The island-performance deliverable is `C7` in
  `docs/design/hicasso/product/budgets.md` §9, and it is `UNPINNED`, its
  authority resting with the budget gates. That page's §9.2 says why in
  terms: the 5% rule has no same-instrument anchor until the ladder is
  re-pinned, no package-resident clock instrument exists, and §7 forbids
  converting a distributional row into a pull-request threshold at all.

  So this file lands the DETERMINISTIC half of the band, which is the
  half a hosted runner is allowed to decide:
  [[a-declared-render-crossing-is-the-authors-own-function-and-costs-no-wrapper]]
  and [[the-convenience-layer-pays-a-per-render-price-handwritten-react-does-not]]
  read the two routes' construction cost as a structural fact rather
  than as a clock. That is a stronger reading than a timing, not a weaker
  one: a `{:server :render}` crossing hands React the author's own
  function as the element type, with the author's own props object, so
  there is no interposed work for a stopwatch to find.

  **The band is stated over the DECLARED arm, and the declaration is what
  makes it comparable.** The `:client-only` default mints a gate rather than
  the author's function, so it costs one fiber and one hook the declared arm
  does not — the ruled price of the conservative default, and not a figure
  about the crossing's construction. Every fixture below therefore writes
  `{:server :render}`.

  No number is transcribed into the ledger here; the figures are this
  file's own, and transcribing the ledger row is the ledger's, as it was
  for D10–D13."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.hook-probe :as probe]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.test.runtime :as runtime]
            [re-frame.hicasso.native :as n]
            [re-frame.test-support :as test-support]
            [uix.core :as uix :refer-macros [defui]]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::parity)

(rf/reg-sub ::price (fn [db _] (:price db)))

(rf/reg-event ::seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (support/leave-act-environment!)
                      (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The two routes, as components
;; ---------------------------------------------------------------------------
;;
;; One subject: a cell that paints a label. Written once per route, with
;; nothing in either of them that the other does not have.

(defn react-cell
  "The handwritten route — an ordinary React function component, and the
  arm the band is measured against. The ABI is one raw JavaScript props
  object and the body reads it by name."
  [^js props]
  (react/createElement "span" #js {:className "cell"} (.-label props)))

(defui uix-cell
  "The UIx route. Its ABI is a ClojureScript props MAP, which is the
  convenience — and the price."
  [{:keys [label]}]
  (uix/$ :span {:class "cell"} label))

(defn uix-cell-arm
  "The plain React shim every crossing into UIx needs: UIx's ABI is a
  carrier object its own `uix/$` builds, so a `defui` reached through any
  other door receives props it cannot read. A plain React component is
  therefore the honest shim, and it is what the crossing looks like in an
  application too."
  [^js props]
  (uix/$ uix-cell {:label (.-label props)}))

(h/defhost react-cell-host react-cell {:server :render})
(h/defhost uix-cell-host uix-cell-arm {:server :render})

;; ---------------------------------------------------------------------------
;; The corpus
;; ---------------------------------------------------------------------------

(def ^:private corpus
  "Matched pairs. Every row is one subject in two spellings, and
  `:proves` names the equality the row exists to establish rather than
  describing the markup — a row whose only claim is *these render the
  same* is a row that would survive both arms being wrong together."
  [{:name   "intrinsic element, literal props, one text child"
    :proves "the ordinary case: a ClojureScript props map lowers to the same
             React slots as a hand-built object, so `:class` is `className`
             wherever it is written"
    :react  #(react/createElement "span" #js {:className "cell" :id "c1"} "42")
    :uix    #(uix/$ :span {:class "cell" :id "c1"} "42")}

   {:name   "a keyed sequence of children"
    :proves "the `:key` slot is React's own on both routes — the one prop React
             itself reads, and the one a route that invented its own child
             identity would get wrong"
    :react  #(react/createElement "ul" nil
                                  (react/createElement "li" #js {:key "a"} "a")
                                  (react/createElement "li" #js {:key "b"} "b"))
    :uix    #(uix/$ :ul nil
                    (uix/$ :li {:key "a"} "a")
                    (uix/$ :li {:key "b"} "b"))}

   {:name   "an SVG element with a dashed attribute"
    :proves "SVG's attribute rules are React's, not the substrate's: `stroke-width`
             becomes `strokeWidth` and the element lands in the SVG namespace on
             both routes"
    :react  #(react/createElement "svg" #js {:viewBox "0 0 8 8"}
                                  (react/createElement "circle" #js {:cx 4 :cy 4 :r 3 :strokeWidth 2}))
    :uix    #(uix/$ :svg {:view-box "0 0 8 8"}
                    (uix/$ :circle {:cx 4 :cy 4 :r 3 :stroke-width 2}))}

   {:name   "a custom element, spelled as a string head"
    :proves "a head with a dash is a custom element on both routes and its
             attributes pass through unrenamed — the case where React's own
             rule CHANGES, so a route carrying its own attribute table would
             diverge here and nowhere else"
    :react  #(react/createElement "my-widget" #js {:data-size "lg"} "w")
    :uix    #(uix/$ :my-widget {:data-size "lg"} "w")}

   {:name   "a dynamic props operand"
    :proves "a props map built at runtime reaches the same React slots as a
             hand-built object"
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
    :react  #(react/createElement "span" #js {:className "cell"})
    :uix    #(uix/$ :span {:class "cell"})}

   {:name   "exactly one child"
    :proves "React's second children shape — the slot holds the child itself,
             not a one-element array"
    :react  #(react/createElement "span" #js {:className "cell"}
                                  (react/createElement "b" nil "one"))
    :uix    #(uix/$ :span {:class "cell"} (uix/$ :b nil "one"))}

   {:name   "many children, mixed element and text"
    :proves "React's third children shape, with the mixture that makes it
             interesting: text and elements interleaved, where a route that
             normalised children would show it"
    :react  #(react/createElement "p" nil "a"
                                  (react/createElement "b" nil "b") "c"
                                  (react/createElement "i" nil "d"))
    :uix    #(uix/$ :p nil "a" (uix/$ :b nil "b") "c" (uix/$ :i nil "d"))}

   {:name   "a component head"
    :proves "the routes agree about a COMPONENT and not only about intrinsic
             elements — two different element types, two different props
             ABIs, one rendering"
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

(defn- slots
  "The names on the props object React carries for `el`, in order."
  [^js el]
  (vec (js/Object.keys (.-props el))))

;; ---------------------------------------------------------------------------
;; 0. The instrument can tell two markups apart
;; ---------------------------------------------------------------------------
;;
;; Every row below is of the form *these two are equal*, and equality is
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
;; 1. The two routes render the same bytes
;; ---------------------------------------------------------------------------

(deftest the-two-routes-render-the-same-server-bytes
  (doseq [{:keys [name proves react uix]} corpus]
    (testing (str name " — " proves)
      (let [handwritten (markup (react))]

        (testing "the premise: the handwritten arm rendered something"
          (is (seq handwritten)))

        (testing "UIx against handwritten React, which is the comparison that
                  decides — no convenience layer sets this floor"
          (is (= handwritten (markup (uix)))))))))

;; ---------------------------------------------------------------------------
;; 2. The two routes build the same element
;; ---------------------------------------------------------------------------
;;
;; Markup is the output; this is the CONSTRUCTION. A route could reach the
;; same bytes through a different element shape — an extra wrapper, a
;; normalised child array, a key moved into props — and the rows above
;; would not see it.

(deftest the-two-routes-build-the-same-element-shape
  (doseq [{:keys [name proves react uix]} corpus]
    (testing (str name " — " proves)
      (let [^js r (react)
            ^js u (uix)]

        (testing "the element TYPE. For an intrinsic element it is the tag
                  string, identically on both routes; for a COMPONENT head
                  the two types are two different functions — one per route
                  — which is not a divergence but the subject: two
                  components rendering one output is what parity means
                  here, and a row asserting they were the same object would
                  be asserting the corpus had only one arm"
          (if (string? (.-type r))
            (is (= (.-type r) (.-type u)))
            (do (is (fn? (.-type r)))
                (is (fn? (.-type u)))
                (is (not (identical? (.-type r) (.-type u)))
                    "two arms, not two aliases of one"))))

        (testing "the `key` slot, which React reads itself"
          (is (= (.-key r) (.-key u))))

        (testing "and the scalar props, slot by slot, for an intrinsic element,
                  where both routes carry React's own props object. A
                  COMPONENT head is the one place the two ABIs differ, and
                  that difference is its own row rather than an exception
                  hidden here"
          (when (string? (.-type r))
            (is (= (scalar-props r) (scalar-props u)))))))))

;; ---------------------------------------------------------------------------
;; 3. Component identity — the declared `:render` crossing's zero wrapper
;; ---------------------------------------------------------------------------
;;
;; This is the deterministic half of the island band. See the namespace
;; docstring: C7 is UNPINNED and its clock is the budget gates', but the
;; structural fact underneath it is decidable here and is the stronger
;; statement — there is no interposed work for a stopwatch to find.
;;
;; The subject is `{:server :render}`, which is what [[react-cell-host]]
;; declares. The `:client-only` default answers a gate instead, and a
;; gate is a wrapper: one fiber and one hook between React and the
;; author's function. Naming the arm is the whole of the qualification —
;; the rows below are unchanged by it.

(deftest a-declared-render-crossing-is-the-authors-own-function-and-costs-no-wrapper
  (testing "a handwritten component is a FUNCTION, and it is the element type
            React reconciles on — not a wrapper holding one"
    (is (fn? react-cell))
    (is (identical? react-cell (.-type (react/createElement react-cell #js {:label "42"})))))

  (testing "so the props React hands the component carry the author's own
            slot and nothing else. Stated as a KEY SET rather than by
            identity, because `React.createElement` copies its config into
            a fresh props object and always has — a row asserting identity
            would be asserting a fact about React that is not true of any
            arm"
    (is (= ["label"] (slots (react/createElement react-cell #js {:label "42"})))))

  (testing "and the body runs when the function is CALLED — no fiber, no
            hook, no shell. This is what `a DECLARED island costs what React
            costs` means as a structural fact rather than a timing"
    (let [el (react-cell #js {:label "42"})]
      (is (= "span" (.-type el)))
      (is (= "42" (.-children (.-props el))))))

  (testing "THE CROSSING, which is the arm the band is stated over: through
            `h/defhost` under `{:server :render}`, the element the codec
            builds has the author's own function as its type and the
            author's own slot as its props — zero wrappers, which is
            budgets.md row D14"
    (let [^js el (codec/as-element [react-cell-host {:label "42"}])]
      (is (identical? react-cell (.-type el)))
      (is (= ["label"] (slots el))))))

(deftest the-convenience-layer-pays-a-per-render-price-handwritten-react-does-not
  (testing "UIx's element carries a CARRIER object rather than the props the
            author wrote — `#js {:argv <the map>}` — so the props a UIx
            component receives are not the props at the call site"
    (let [^js el (uix/$ uix-cell {:label "42"})]
      (is (some? (.-argv (.-props el)))
          "the carrier slot is there")
      (is (= {:label "42"} (.-argv (.-props el)))
          "and it holds the ClojureScript map the author wrote")))

  (testing "read as a figure, which is what makes it comparable: the props
            object React carries has ONE slot on both routes, and on the
            handwritten route it is the author's `label` while on UIx it is
            a carrier the author never wrote. That is one unwrapping hop per
            render, per component, on the UIx route and ZERO on the
            handwritten one — the convenience being paid for, and exactly
            why §6 measures against handwritten React: a floor set by UIx
            would have this hop inside it. budgets.md row D16"
    (is (= ["label"] (slots (react/createElement react-cell #js {:label "42"}))))
    (is (= ["argv"]  (slots (uix/$ uix-cell {:label "42"}))))

    (let [^js uix-el   (uix/$ uix-cell {:label "42"})
          ^js react-el (react/createElement react-cell #js {:label "42"})]
      (is (nil? (.-label (.-props uix-el)))
          "UIx's element does not carry the prop at its own name")
      (is (= "42" (.-label (.-props react-el)))
          "the handwritten element does — the body reads `.-label` off the
           props object React built from the very object the call site
           wrote")))

  (testing "and through the crossing the same hop is visible: the UIx arm's
            host hands React a plain shim as the element type, and it is the
            shim — not the door — that opens `argv` one level down"
    (let [^js el (codec/as-element [uix-cell-host {:label "42"}])]
      (is (identical? uix-cell-arm (.-type el)))
      (is (= ["label"] (slots el)))))

  (testing "and the two routes' element types are different KINDS of thing:
            handwritten React's is the author's own function, UIx's is a
            generated component around a body the author wrote separately"
    (is (identical? react-cell (.-type (react/createElement react-cell #js {:label "42"}))))
    (is (true? (.-uix-component? uix-cell))
        "UIx stamps its own components, which is how it recognises one — and
         the handwritten function carries no such stamp because it is not
         wrapped")))

;; ---------------------------------------------------------------------------
;; 4. One frame, read through the interpreted door and both foreign doors
;; ---------------------------------------------------------------------------

(defn island
  "The raw-React route's read: `n/use-sub`, a real React hook, in an
  ordinary function component. Mounted through `{:server :render}` for
  [[uix-reader-host]]'s reason — the page below is server-rendered and a
  Client-only crossing would leave the premise row reading two arms and
  calling it three."
  [^js _props]
  (react/createElement "b" #js {:className "island"} (str (n/use-sub [::price]))))

(h/defhost island-host island {:server :render})

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

  What that costs the row is nothing: the claim is that all three doors
  read ONE frame and agree, not that all three discover it by the same
  mechanism — they demonstrably do not, since the Hicasso doors resolve
  through `impl/collector`'s own context read. The ambient UIx form is
  the browser lane's to exercise, and `re-frame.adapter.uix`'s own
  `uix_use_subscribe_dom_cljs_test` is where it already is."
  [_]
  (uix/$ :u {:class "uix"} (str (uix-adapter/use-subscribe frame-id [::price]))))

(defn uix-reader-arm
  "The crossing into the UIx tree — a plain React shim, for
  [[uix-cell-arm]]'s reason."
  [_props]
  (uix/$ uix-reader))

(h/defhost uix-reader-host
  "The UIx arm's crossing into the tree, through the named door.

  `{:server :render}` and not the default, and the reason belongs in the
  file: `[:>]` and an undeclared `defhost` are `:client-only`, which is a
  policy about the SERVER — the gate withholds the foreign component and
  renders its fallback. That is correct conduct and it is measured in
  `hook_budget_cljs_test`; here it would delete the arm this row exists
  to compare, and the first draft of this file read two arms agreeing and
  called it three. The premise row below is what caught it."
  uix-reader-arm
  {:server :render})

(h/defview boundary
  "The interpreted route's read: `h/sub`, the ambient collector."
  [_]
  [:i.boundary (str (h/sub [::price]))])

(h/defview page
  "One tree holding all three doors, so the reads are of the same frame
  in the same render rather than three separate renders that happen to
  agree."
  [_]
  [:div.page
   [boundary {}]
   [island-host {}]
   [uix-reader-host {}]])

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

(deftest the-three-doors-read-one-frame-and-agree
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
              into raw React does not change what a read costs"
      (is (= ["useContext" "useSyncExternalStore"
              "useContext" "useSyncExternalStore"
              "useContext" "useSyncExternalStore"]
             (vec (take 6 hooks)))))

    (testing "I9 holds at two, read off the ledger the shell declares — the
              tree above added a raw-React island and a foreign UIx subtree
              and moved it by nothing"
      (is (= 2 (count runtime/shell-hook-ledger))))

    (testing "and neither `useRef` nor `useState` is among them: HD-020(b)'s
              two named prohibitions, over every hook Hicasso spent on this
              page. The UIx arm below DOES call `useRef` — three times — and
              that is its own affair and not charged here, exactly as
              `hook_budget_cljs_test` establishes for a hosted component's
              own roster"
      (is (= [] (filterv #{"useRef" "useState"} (vec (take 6 hooks))))))

    (testing "the crossings themselves cost no hook: `{:server :render}` mints
              no gate, so everything past the sixth is the foreign subtree's
              own and the doors contributed nothing to the count"
      (is (seq (drop 6 hooks))
          "the premise — the UIx arm really ran hooks of its own, so the
           statement above is about a populated tail rather than an empty
           one"))))
