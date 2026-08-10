(ns re-frame.hicasso.native-fence-cljs-test
  "THE TWO-LANGUAGES FENCE, AND `n/defcomponent` (rf2-hic-030).

  `[...]` is always interpreted hiccup; `n/$` is always native React;
  neither form ever changes the other's meaning. The fence is the whole
  of the native tier's architecture ([native-boundary law
  2](../../../../../docs/design/hicasso/product/lanes/design-laws.md)),
  and the way to witness it is not to assert that a macro is well
  behaved but to write the SAME SOURCE SHAPE on both sides and show
  that it means opposite things.

  [[an-intent-vector-means-opposite-things-on-the-two-sides]] is that
  pair, and it is the row this file exists for: `{:on-click [:fence/go]}`
  is ordinary Hicasso — lowered, mounted, rendered — and the identical
  map entry in a native props operand refuses. An implementation in
  which either language had leaked into the other fails one half or the
  other.

  The remaining fence rows are structural: two languages side by side in
  ONE `defview` body, each producing its own output, and a body that
  returns a native element outright (rung 3). Both would break under a
  native macro that analysed its enclosing body — which it does not do,
  and cannot, because it reads only its own form."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.error :as error]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::native-fence)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The consumer's source — one namespace writing BOTH languages
;; ---------------------------------------------------------------------------

(h/defview hiccup-side
  "Ordinary Hicasso. `:on-click` carries an event VECTOR, which is the
  product's default event representation and is lowered by the codec."
  [_]
  [:div {:on-click [:fence/go]} [:span "hiccup"]])

(h/defview both-languages
  "Two languages in one body: an interpreted hiccup child beside a
  natively constructed one. Nothing here rewrites anything there."
  [_]
  [:div [:span "hiccup"] (n/$ :b nil "native")])

(h/defview native-return
  "Rung 3 — the body returns an already-constructed React element
  instead of hiccup, so the hiccup walk is skipped for this result."
  [_]
  (n/$ :div nil "native return"))

(n/defcomponent probe
  "A native island with no declaration map, so its server policy is the
  Client-only default."
  [^js props]
  (n/$ :i nil (.-label props)))

(n/defcomponent server-rendered
  {:server :render}
  [^js props]
  (n/$ :em nil (.-label props)))

(n/defcomponent abi-probe
  "Reports what the ABI actually handed it, so the one-props-map claim
  is measured rather than asserted in prose."
  [^js props]
  (n/$ :span nil
       (str "map?=" (map? props)
            " label=" (.-label props)
            " children=" (pr-str (.-children props)))))

(n/defcomponent hooked
  "Ordinary React hooks, reached by direct interop — no wrapper, no
  Hicasso hook library."
  [^js _props]
  (let [[n' _] (react/useState 41)]
    (n/$ :output nil (str (inc n')))))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- render-hiccup!
  "Server-render `hiccup` under a live frame. The server renderer runs
  bodies for real, which is what makes a body-scoped result reachable
  without a DOM."
  [hiccup]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (react-dom-server/renderToStaticMarkup
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(defn- render-native!
  "Server-render a native element. No frame is involved: an island that
  reads nothing needs none, and saying so keeps this harness from
  quietly proving the frame rather than the tier."
  [element]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (react-dom-server/renderToStaticMarkup element))

(defn- refusal
  [f]
  (try
    (f)
    {::outcome :returned-without-refusing}
    (catch :default e
      (or (ex-data e) {::outcome :threw-without-ex-data ::message (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; 1. The fence
;; ---------------------------------------------------------------------------

(deftest an-intent-vector-means-opposite-things-on-the-two-sides
  (testing "on the hiccup side `{:on-click [:fence/go]}` is the product's
            ordinary event representation: it is lowered and the view
            renders. Narrowing caught: native semantics leaking into the
            codec, which would refuse this"
    (is (= "<div><span>hiccup</span></div>" (render-hiccup! [hiccup-side {}]))))

  (testing "the IDENTICAL map entry in a native props operand refuses,
            because past the fence nothing lowers it. Narrowing caught:
            intent lowering leaking into the native tier, which would
            hand React a vector and render a broken listener"
    (let [data (refusal #(n/props {:on-click [:fence/go]}))]
      (is (= :rf.error/hicasso-native-intent-in-prop (:rf.error/id data)))
      (is (= [:fence/go] (:value data))))))

(deftest the-two-languages-compose-without-either-rewriting-the-other
  (testing "one body, one interpreted child and one natively constructed
            child, each producing its own output. Narrowing caught: a
            native macro that analysed its enclosing body — it would have
            to see the hiccup sibling to leave it alone, and the fence's
            claim is that it never sees anything but its own form"
    (is (= "<div><span>hiccup</span><b>native</b></div>"
           (render-hiccup! [both-languages {}]))))

  (testing "rung 3 — a body may return a native element outright, and the
            boundary is unchanged around it. Narrowing caught: a codec
            that insisted a body answer hiccup"
    (is (= "<div>native return</div>" (render-hiccup! [native-return {}])))))

(deftest hiccup-in-a-native-child-position-refuses-with-a-door-to-cross-by
  (testing "the other direction of the same fence: brackets have no
            meaning inside `n/$`, and the recovery names the explicit
            conversion rather than leaving the author to guess"
    (let [row  [:span "hiccup"]
          data (refusal #(n/$ :div row))]
      (is (= :rf.error/hicasso-native-hiccup-child (:rf.error/id data)))
      (is (= :nest-n-dollar-or-convert-with-h-as-element (:recovery data)))
      (is (= row (:child data))))))

;; ---------------------------------------------------------------------------
;; 2. `n/defcomponent`
;; ---------------------------------------------------------------------------

(deftest a-native-component-carries-its-name-its-policy-and-the-marker
  (testing "displayName is the `<ns>/<sym>` string — the same identifier
            `defview` stamps, so a tool naming one boundary names both.
            Narrowing caught: stamping the bare symbol, which collides
            across namespaces"
    (is (= "re-frame.hicasso.native-fence-cljs-test/probe" (.-displayName probe))))

  (testing "the marker is present and reports the declared policy.
            Narrowing caught: a `defcomponent` that returned the author's
            fn untouched — it would still render, and every ABI helper
            and embedding seam downstream would silently lose the tier"
    (let [m (n/marker probe)]
      (is (some? m))
      (is (= "re-frame.hicasso.native-fence-cljs-test/probe" (.-name m)))))

  (testing "the server policy DEFAULTS to Client-only when no declaration
            map is written. Narrowing caught: defaulting to :render,
            which is the unsafe direction — it asserts server-safety the
            author never claimed"
    (is (= "client-only" (.-server (n/marker probe)))))

  (testing "and an explicit `{:server :render}` is recorded. Narrowing
            caught: ignoring the declaration map entirely, which would
            pass the default row above"
    (is (= "render" (.-server (n/marker server-rendered)))))

  (testing "a plain function carries no marker, which is what makes the
            marker a discriminator rather than a decoration"
    (is (nil? (n/marker (fn [_]))))))

(deftest the-declaration-captures-its-source-coordinate
  (testing "rf2-hic-007's coordinate, captured by the macro from its own
            form and resolvable by component name — so a refusal raised
            while this declaration is minted can say which file and line
            is wrong. Narrowing caught: a `defcomponent` that never
            opened a declaration extent"
    (let [coord (error/source-of "re-frame.hicasso.native-fence-cljs-test/probe")]
      (is (some? (:ns coord)))
      (is (string? (:file coord)))
      (is (pos-int? (:line coord)))
      (is (pos-int? (:column coord))))))

(deftest the-abi-is-one-raw-javascript-props-object
  (testing "the body receives React's own props object — not a
            ClojureScript map, and not a copy. Narrowing caught: a
            `js->clj` in the expansion, which is the ergonomic-looking
            wrong answer and the one the tier explicitly refuses to
            allocate for"
    (is (= "<span>map?=false label=L children=nil</span>"
           (render-native! (n/$ abi-probe #js {"label" "L"})))))

  (testing "children arrive at `.-children`, on the same object — one
            channel, no second ABI. Narrowing caught: an expansion that
            passed children as a separate argument"
    (is (= "<span>map?=false label=L children=&quot;kid&quot;</span>"
           (render-native! (n/$ abi-probe #js {"label" "L"} "kid"))))))

(deftest ordinary-react-hooks-are-legal-inside-a-native-component
  (testing "`react/useState` by direct interop, with no wrapper. This is
            the rung-4 reason for the tier to exist at all: a hook is
            illegal in a `defview` body, whose control flow is dynamic,
            and legal here, where React's own rules apply"
    (is (= "<output>42</output>" (render-native! (n/$ hooked nil))))))

(deftest minting-is-allocation-and-never-a-lookup-by-name
  (testing "the HMR contract (rf2-hic-015), reduced to the property it
            rests on. A reload re-evaluates the module and re-runs this
            mint, so a component's element TYPE changes and React
            replaces the subtree — a clean remount, never preservation.
            Narrowing caught: caching by name to 'preserve identity
            across a reload', which would render identically, pass every
            other row in this file, and quietly contradict the recorded
            contract"
    (let [a (n/component "app/same-name" :client-only (fn [_]))
          b (n/component "app/same-name" :client-only (fn [_]))]
      (is (not (identical? a b)))
      (is (= (.-displayName a) (.-displayName b)))))

  (testing "and the name is an ADDRESS: both mints answer to it"
    (is (= "app/same-name"
           (.-name (n/marker (n/component "app/same-name" :client-only (fn [_]))))))))

;; ---------------------------------------------------------------------------
;; 3. The tier sentinel (rf2-hic-034 plants its proof on this)
;; ---------------------------------------------------------------------------

(deftest the-tier-sentinel-is-reachable-rather-than-a-dead-literal
  (testing "the sentinel is the marker PROPERTY NAME, so a build that
            carries a native component carries the string. A bare
            constant could be elided by the optimiser while the tier
            stayed in, and the bundle proof would then be measuring
            nothing"
    (is (= "rf2:hicasso-native-tier" n/tier-sentinel))
    (is (some? (unchecked-get probe n/tier-sentinel)))))
