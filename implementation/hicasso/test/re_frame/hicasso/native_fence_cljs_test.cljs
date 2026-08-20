(ns re-frame.hicasso.native-fence-cljs-test
  "THE TWO-LANGUAGES FENCE, AND `n/defcomponent`.

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
  and cannot, because it reads only its own form.

  ## Where the refusal says it came from

  [[a-grammar-refusal-inside-an-island-body-is-source-located]] is the
  second thing this file now pins. The checklist asks for a *source-located*
  refusal of Hiccup and intent semantics, and an island body was the one
  place a grammar refusal could not say where it was: `n/component` opened
  no declaration extent, so `impl.error`'s ambient pair was absent by
  construction and nothing noticed. The row drives two islands, at two
  lines, through two different refusal doors, with the same refusal raised
  outside every island as its control."
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
  is measured rather than asserted in prose.

  `{:server :render}` because [[render-native!]] is the server renderer
  and the policy is now READ: a Client-only island's body
  does not run there. The ABI is the same under either policy — the gate
  forwards its own props object untouched — so the declaration buys the
  instrument its body back and changes nothing this row is about."
  {:server :render}
  [^js props]
  (n/$ :span nil
       (str "map?=" (map? props)
            " label=" (.-label props)
            " children=" (pr-str (.-children props)))))

(n/defcomponent hooked
  "Ordinary React hooks, reached by direct interop — no wrapper, no
  Hicasso hook library. `{:server :render}` for [[abi-probe]]'s reason."
  {:server :render}
  [^js _props]
  (let [[n' _] (react/useState 41)]
    (n/$ :output nil (str (inc n')))))

(n/defcomponent hiccup-child-island
  "An island that writes its child as a RUNTIME value, so the fence's
  child check runs where React calls the body. A literal vector in that
  position refuses at macro expansion instead, and is witnessed there
  (`re-frame.hicasso.native-grammar-cljs-test`, the expansion section).

  `{:server :render}` so the head is the body itself rather than a
  Client-only gate, which on a server render contributes nothing and
  would never reach the body at all."
  {:server :render}
  [^js props]
  (n/$ :div nil (.-row props)))

(n/defcomponent slot-collision-island
  "The props twin of [[hiccup-child-island]], declared at its own line so
  the two coordinates can be compared. A dynamic operand carrying two
  spellings of one React slot refuses inside the body."
  {:server :render}
  [^js props]
  (n/$ :div (n/props (.-attrs props))))

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

  (testing "and an explicit `{:server :render}` is RECORDED — and the
            recording is the whole of what THIS row claims. The EFFECT —
            bytes emitted or withheld — is
            `re-frame.hicasso.native-ssr-dom-cljs-test`'s subject since
            rf2-hic-046 made the field decide it; this row keeps the
            narrower one, because a marker a tool reads and a policy a
            renderer honours are two claims and only one of them is about
            data. Narrowing caught: ignoring the declaration map
            entirely, which would pass the default row above"
    (is (= "render" (.-server (n/marker server-rendered)))))

  (testing "a plain function carries no marker, which is what makes the
            marker a discriminator rather than a decoration"
    (is (nil? (n/marker (fn [_]))))))

(deftest the-declaration-map-is-checked-against-a-key-roster-and-a-value-roster
  (testing "THE KEY ROSTER. `{:ssr :render}` is the spelling `defhost`
            carried until rf2-mo4o, and a plausible mistake for anyone
            reading older material. It used to mint clean here and stamp
            Client-only — `mint-host!`'s own message is the ruling
            this repairs:
            *reading past an option it does not know is how a policy comes
            to be set and never applied*"
    (let [d (refusal #(n/declared-server "app/x" {:ssr :render}))]
      (is (= :rf.error/hicasso-native-unknown-option (:rf.error/id d)))
      (is (= :declare-the-server-policy (:recovery d)))
      (is (= :ssr (:option d)))
      (is (= #{:server} (:options d))
          "and the message hands back the roster rather than describing it")))

  (testing "a typo in the key is the same fault and takes the same
            refusal, which is the point of a roster over a spell-check"
    (is (= :rf.error/hicasso-native-unknown-option
           (:rf.error/id (refusal #(n/declared-server "app/x" {:sever :render})))))
    (is (= :rf.error/hicasso-native-unknown-option
           (:rf.error/id (refusal #(n/declared-server "app/x"
                                                      {:server :render :fallback [:div]}))))
        "an EXTRA key beside a legal one still vanished silently before"))

  (testing "THE VALUE ROSTER. `{:server :rendr}` was stamped as the string
            \"rendr\" and read back by `n/marker` as \"rendr\" — a policy
            no reader could ever match, recorded with no complaint"
    (let [d (refusal #(n/declared-server "app/x" {:server :rendr}))]
      (is (= :rf.error/hicasso-native-bad-server-policy (:rf.error/id d)))
      (is (= :declare-client-only-or-render (:recovery d)))
      (is (= :rendr (:server d)))))

  (testing "an explicit nil is a VALUE and not an absence — the roster
            reads it as one, exactly as the host codec's own
            `declared-server` does one tier down, because inferring the
            default from nil is how a typo becomes a policy"
    (is (= :rf.error/hicasso-native-bad-server-policy
           (:rf.error/id (refusal #(n/declared-server "app/x" {:server nil}))))))

  (testing "and `defhost`'s sibling `:fallback` has no counterpart here,
            as a value or as a key. Both doors gate the Client-only arm
            the same way; what differs is the authoring site — a native
            island is reached from hiccup, so markup for its region is
            written where it renders rather than declared"
    (is (= :rf.error/hicasso-native-bad-server-policy
           (:rf.error/id (refusal #(n/declared-server "app/x"
                                                      {:server {:fallback [:div]}}))))))

  (testing "the two legal shapes are untouched, and an absent map is the
            conservative default rather than an error"
    (is (= :client-only (n/declared-server "app/x" nil)))
    (is (= :client-only (n/declared-server "app/x" {})))
    (is (= :client-only (n/declared-server "app/x" {:server :client-only})))
    (is (= :render (n/declared-server "app/x" {:server :render})))))

(deftest the-macro-reaches-the-roster-rather-than-reading-past-it
  (testing "the fault this whole bead is about is a check nothing calls,
            so the routing is asserted rather than assumed: a declaration
            written through `n/defcomponent` itself refuses, at LOAD, from
            inside the extent `declaring!` opened — which is what puts the
            author's own file and line on the refusal"
    (let [d (refusal (fn [] (n/defcomponent minted-with-a-bad-option
                              {:ssr :render}
                              [^js _props]
                              nil)))]
      (is (= :rf.error/hicasso-native-unknown-option (:rf.error/id d)))
      (is (= 're-frame.hicasso.native/defcomponent (:where d)))
      (is (= "re-frame.hicasso.native-fence-cljs-test/minted-with-a-bad-option"
             (:view d))
          "the ambient view comes from the ledger the emitted `declaring!`
           wrote, so the refusal names the declaration rather than the
           macro")
      (is (pos-int? (:line (:source d)))
          "and the coordinate with it — a refusal raised at macroexpansion
           would carry neither"))))

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

(deftest a-grammar-refusal-inside-an-island-body-is-source-located
  (testing "the checklist's required result ends *source-located refusal of
            Hiccup/intent semantics*, and until rf2-dva6 it was structurally
            unreachable: `n/component` did not open a declaration extent, so
            a refusal raised while React ran the body carried neither `:view`
            nor `:source`. It now names the island"
    (let [data (refusal #(render-native! (n/$ hiccup-child-island
                                              #js {"row" [:span "hiccup"]})))]
      (is (= :rf.error/hicasso-native-hiccup-child (:rf.error/id data)))
      (is (= "re-frame.hicasso.native-fence-cljs-test/hiccup-child-island"
             (:view data)))
      (is (string? (:file (:source data))))
      (is (pos-int? (:line (:source data))))
      (is (= (error/source-of
               "re-frame.hicasso.native-fence-cljs-test/hiccup-child-island")
             (:source data))
          "and the coordinate is the DECLARATION's, resolved through the same
           ledger row `n/defcomponent` wrote — the tier's macros read no body,
           so no line inside one is theirs to name")))

  (testing "the props path is located too, which matters because it is a
            different door: this refusal comes from `prop-slots` under
            `props*`, where the one above comes from `check-child!` under
            `el`. Narrowing caught: wrapping only one of the two"
    (let [data (refusal #(render-native! (n/$ slot-collision-island
                                              #js {"attrs" {:class     "a"
                                                            "className" "b"}})))]
      (is (= :rf.error/hicasso-native-slot-collision (:rf.error/id data)))
      (is (= "re-frame.hicasso.native-fence-cljs-test/slot-collision-island"
             (:view data)))
      (is (pos-int? (:line (:source data))))))

  (testing "NON-MEMBER 1: the coordinate is not a constant. Two islands
            declared at two lines refuse with two coordinates — a row that
            drove one island could not tell a resolved coordinate from a
            hardcoded one"
    (let [a (refusal #(render-native! (n/$ hiccup-child-island
                                          #js {"row" [:span "hiccup"]})))
          b (refusal #(render-native! (n/$ slot-collision-island
                                          #js {"attrs" {:class     "a"
                                                        "className" "b"}})))]
      (is (not= (:view a) (:view b)))
      (is (not= (:line (:source a)) (:line (:source b))))))

  (testing "NON-MEMBER 2: the IDENTICAL refusal raised outside every island
            carries no coordinate and no view, which is `impl.error`'s
            documented absence rather than a placeholder. This row runs
            AFTER the ones above on purpose: it also proves the extent is
            closed on the way out, so a caught island refusal does not leave
            its name ambient for whatever refuses next"
    (let [row  [:span "hiccup"]
          data (refusal #(n/$ :div row))]
      (is (= :rf.error/hicasso-native-hiccup-child (:rf.error/id data)))
      (is (not (contains? data :view)))
      (is (not (contains? data :source)))))

  (testing "NON-MEMBER 3: an island minted by calling `n/component` directly
            names itself and stops there. There is no ledger row for a name
            no `n/defcomponent` declared, and the honest answer is the
            absent field rather than an invented coordinate"
    (let [head (n/component "app/handmade" :render
                            (fn [^js p] (n/$ :div nil (.-row p))))
          data (refusal #(render-native! (n/$ head #js {"row" [:span "hiccup"]})))]
      (is (= :rf.error/hicasso-native-hiccup-child (:rf.error/id data)))
      (is (= "app/handmade" (:view data)))
      (is (not (contains? data :source))))))

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
