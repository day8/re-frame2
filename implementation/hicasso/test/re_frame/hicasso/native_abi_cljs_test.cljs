(ns re-frame.hicasso.native-abi-cljs-test
  "THE ONE ABI, CARRIED (rf2-hic-032) — what can be settled without a
  fiber.

  Native-boundary law clause 5 says element construction, `defcomponent`,
  memo, lazy loading, refs and BOTH embedding directions share one
  props/children ABI, and that there is no separate ergonomic and fast
  one. The characteristic way that clause is broken is not that a wrapper
  stops working — `(react/memo hot-row)` renders perfectly — it is that
  the wrapper QUIETLY DROPS what the boundary is recognised by. So every
  row here reads the marker or the slot, never the pixels.

  ## What each row is for, and the narrowing it is written against

  | row | what it establishes | the one-line narrowing it catches |
  |---|---|---|
  | [[a-decoded-slot-emits-back-into-the-slot-it-came-from]] | the bridge's inverse is a real inverse of the slot rule, over the whole corpus | an inverse that re-splits a name the forward rule never joined — `data-userId` |
  | [[the-taught-spellings-decode-to-themselves]] | `className` comes back as `:class`, not as `:class-name` | a missing rename table, which row 1 passes with because both spellings emit into `className` |
  | [[a-native-parents-props-arrive-as-the-views-own-props-map]] | the outward bridge's whole promise, measured in the body | a bridge that handed the body the raw JavaScript object — the second ABI clause 5 forbids |
  | [[the-outward-bridge-adds-no-refusal-of-its-own]] | a bad head refuses through the codec's own id and coordinate | a bridge that minted a private complaint, which is a spelling no catalogue carries |
  | [[n-memo-keeps-the-marker-where-a-raw-react-memo-loses-it]] | the helper's entire reason to exist, with its own control beside it | a `memo` that forwarded the call and stamped nothing |
  | [[n-lazy-is-a-native-head-from-the-moment-it-is-declared]] | a code-split head is recognisable during the whole window it is waiting | a marker minted inside the loader — nil until the chunk lands |
  | [[a-lazy-region-is-client-only-whatever-its-component-declared]] | the server never sent the chunk, so no declaration can make bytes exist | copying `:server` off the loaded component |
  | [[refs-need-no-helper-because-ref-is-an-ordinary-slot]] | why the helper surface is two and not three | a `forward-ref` helper preserving what nothing took away |

  ## The DOM half

  Identity across a re-render, a bail-out, a remount and StrictMode are
  claims about a fiber, and a wrapper that works on the first render and
  loses the marker on the second is exactly the defect this bead is
  about. Those rows — and the lazy head's ARRIVAL, which only a real
  Suspense render drives — are
  `re-frame.hicasso.native-abi-dom-cljs-test`. What is here is what a
  server render and a pure function can settle, and the server renderer
  runs bodies for real, so the props-arrival row is a measurement rather
  than an assertion about a data structure."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.slot :as slot]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.slot-corpus :as slot-corpus]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::native-abi)

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The consumer's source
;; ---------------------------------------------------------------------------

(defonce ^:private !received
  ;; What the view's body was handed, held whole. A props claim asserted
  ;; against a rendered string can only see what the body chose to print;
  ;; this sees the map.
  (atom nil))

(h/defview article-card
  "An ordinary view. Nothing about it knows a React parent exists — which
  is the outward bridge's entire claim, so the view must not be written
  for one."
  [props]
  (reset! !received props)
  [:article {:class (:class props)} (str "#" (:article-id props))])

(n/defcomponent quote-cell*
  "A pure display island, the sort worth memoising."
  [^js props]
  (n/$ :b nil (.-label props)))

(n/defcomponent server-cell*
  {:server :render}
  [^js props]
  (n/$ :em nil (.-label props)))

(def ^:private memoised (n/memo quote-cell*))
(def ^:private raw-memoised (react/memo quote-cell*))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- render-outward!
  "Server-render `element` — a React parent's element — under a live
  frame provider, and answer the markup. The provider is the ONE root and
  the ONE frame: the bridge's promise is that a native parent needs
  neither of its own."
  [element]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (react-dom-server/renderToStaticMarkup (mount/provider frame-id element)))

(defn- refusal
  [f]
  (try
    (f)
    {::outcome :returned-without-refusing}
    (catch :default e
      (or (ex-data e) {::outcome :threw-without-ex-data ::message (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; 1. The slot rule, read backwards
;; ---------------------------------------------------------------------------

(def ^:private bridge-corpus
  "The corpus rows the shared table has no reason to carry: a
  hyphen-bearing family member with a hump inside it, and the ordinary
  application keys a view's props map is actually made of. `slot-corpus`
  exists to pin the CODEC's emission rule and is right not to grow for
  this."
  {:data-userId     "data-userId"
   :aria-labelledby "aria-labelledby"
   :article-id      "articleId"
   :on-pick         "onPick"
   :children        "children"
   :--brand-hue     "--brand-hue"})

(deftest a-decoded-slot-emits-back-into-the-slot-it-came-from
  (testing "for every authored key in the corpus, decoding its slot and
            re-emitting the result lands on the SAME slot. That is the
            law an inverse of a non-injective rule can actually keep —
            `:class`, `:className`, `\"class\"` and `:x/class` are one
            slot, so the inverse chooses a spelling and what must survive
            is the slot, not the choice.

            Narrowing caught: an inverse that re-splits a name the
            forward rule never joined. `data-userId` keeps its hyphen
            because `data-*` is exempt from camelCasing, so a decoder
            that camel-split it would answer `:data-user-id` and emit
            `data-user-id` — a prop React sends to the DOM under a name
            the author never wrote"
    (doseq [[k expected-slot] (merge slot-corpus/corpus bridge-corpus)]
      (let [slot    (slot/prop-name k)
            decoded (codec/prop-key slot)]
        (is (= expected-slot slot)
            (str "the corpus row itself moved: " (pr-str k)))
        (is (= slot (slot/prop-name decoded))
            (str "decoding " (pr-str slot) " gave " (pr-str decoded)
                 ", which emits into " (pr-str (slot/prop-name decoded))))))))

(deftest the-taught-spellings-decode-to-themselves
  (testing "the keys the guide teaches come back as themselves, so a
            body destructures the keyword its author would have written.

            This row and the one above are BOTH needed and neither
            subsumes the other: `className` decoded to `:class-name`
            passes row 1 whole, because `:class-name` emits back into
            `className` — the rename table is invisible to a
            slot-preservation law and visible only here"
    (doseq [k [:class :for :charset
               :on-click :aria-label :data-index :article-id
               :children :key :ref :x :--brand-hue]]
      (is (= k (codec/prop-key (slot/prop-name k)))
          (str "round trip lost " (pr-str k))))))

;; ---------------------------------------------------------------------------
;; 2. The outward bridge
;; ---------------------------------------------------------------------------

(deftest a-native-parents-props-arrive-as-the-views-own-props-map
  (let [handler (fn [_] :picked)
        card    (h/as-component article-card)
        markup  (render-outward!
                  (react/createElement
                    card
                    #js {"articleId" 7
                         "className" "wide"
                         "onPick"    handler}
                    (react/createElement "span" nil "kid")))
        props   @!received]

    (testing "the view rendered, under the surrounding provider's frame
              and inside the one root the parent already had. Narrowing
              caught: a bridge that mounted its own root — the markup
              would still be right and the frame would not be the
              parent's"
      (is (= "<article class=\"wide\">#7</article>" markup)))

    (testing "and the body was handed an ordinary ClojureScript props
              map, keyed the way its author writes keys. Narrowing
              caught: handing the body the raw JavaScript object, which
              renders identically for a body written against it and is
              precisely the second ABI clause 5 forbids"
      (is (map? props))
      (is (= 7 (:article-id props)))
      (is (= "wide" (:class props))))

    (testing "values cross by identity — nothing is converted, copied or
              deep-walked on the way in, exactly as nothing is on the way
              out"
      (is (identical? handler (:on-pick props))))

    (testing "React's children arrive at `:children`, which is the slot a
              hiccup caller's children land in too — one spelling,
              whichever side of the bridge filled it"
      (is (react/isValidElement (:children props))))

    (testing "and the internal crossing is nowhere in what the body was
              given: the bridge decodes INTO the props map, so no
              spelling of `rfProps` is a key a consumer can see"
      (is (= #{:article-id :class :on-pick :children} (set (keys props)))))))

(deftest the-outward-bridge-adds-no-refusal-of-its-own
  (testing "a head outside the closed set refuses through the codec's own
            complaint, at the codec's own coordinate — because the bridge
            converts props and then calls the same `vec->element` every
            hiccup vector in every body goes through. The minted
            component is an ordinary function, so this needs no React
            between the mistake and the refusal.

            Narrowing caught: a bridge that policed its own argument with
            a private id. It would read better in isolation and be a
            spelling no catalogue carries, at a `:where` naming a var the
            register does not know"
    (let [bad  (h/as-component 42)
          data (refusal #(bad #js {}))]
      (is (= :rf.error/hicasso-bad-head (:rf.error/id data)))
      (is (= 're-frame.hicasso.impl.codec/vec->element (:where data)))
      (is (= 42 (:head data)))))

  (testing "and the component it mints names the view it bridges, so a
            React DevTools tree and an Xray trace agree about which view
            the extra fiber belongs to"
    (is (= "hicasso/as-component(re-frame.hicasso.native-abi-cljs-test/article-card)"
           (.-displayName (h/as-component article-card))))))

;; ---------------------------------------------------------------------------
;; 3. The ABI helpers
;; ---------------------------------------------------------------------------

(deftest n-memo-keeps-the-marker-where-a-raw-react-memo-loses-it
  (testing "`n/memo` answers a real React memo record wrapping the very
            component it was given — the wrapper is React's, not a
            reimplementation of it, and nothing sits between them"
    (is (= (unchecked-get raw-memoised "$$typeof")
           (unchecked-get memoised "$$typeof")))
    (is (identical? quote-cell* (unchecked-get memoised "type"))))

  (testing "and it carries the tier marker and the display name across.
            Narrowing caught: a `memo` that forwarded the call and
            stamped nothing — it renders identically and every seam that
            recognises a native head stops recognising this one"
    (let [m (n/marker memoised)]
      (is (some? m))
      (is (= "re-frame.hicasso.native-abi-cljs-test/quote-cell*"
             (unchecked-get m "name")))
      (is (= "client-only" (unchecked-get m "server")))
      (is (= "re-frame.hicasso.native-abi-cljs-test/quote-cell*"
             (unchecked-get memoised "displayName")))))

  (testing "THE CONTROL, and the reason the helper exists at all: the raw
            wrapper around the same component answers no marker. If this
            assertion ever goes green the helper has become decorative"
    (is (nil? (n/marker raw-memoised))))

  (testing "a component this tier did not mint carries nothing, and the
            helper says so rather than refusing — UIx is a supported
            authoring route, and memoising one of its components here is
            legal and simply has no marker to carry"
    (is (nil? (n/marker (n/memo (fn [_])))))))

(deftest n-lazy-is-a-native-head-from-the-moment-it-is-declared
  (let [head (n/lazy (fn [] (js/Promise.resolve quote-cell*)))]
    (testing "React's own lazy record, so Suspense and the error boundary
              treat it exactly as they treat any other"
      (is (= (unchecked-get (react/lazy (fn [] (js/Promise.resolve #js {}))) "$$typeof")
             (unchecked-get head "$$typeof"))))

    (testing "declared, and already a native head. Narrowing caught:
              stamping the marker on resolve — every seam reading the
              head between declaration and arrival would get nil, and
              that window is the whole of what a code-split head spends
              being looked at"
      (is (some? (n/marker head))))

    (testing "with the one field that cannot be known yet honestly
              absent, rather than guessed from the loader"
      (is (nil? (unchecked-get (n/marker head) "name"))))))

(deftest a-lazy-region-is-client-only-whatever-its-component-declared
  (testing "the component itself declares Render — the control, without
            which the row below cannot tell a policy that was carried
            from one that was never asked for"
    (is (= "render" (unchecked-get (n/marker server-cell*) "server"))))

  (testing "and the lazy head around it is Client-only. The server never
            sent the chunk, so no declaration inside it can make bytes
            exist; copying the component's policy up would have the
            runtime claiming hydration for markup it never emitted"
    (let [head (n/lazy (fn [] (js/Promise.resolve server-cell*)))]
      (is (= "client-only" (unchecked-get (n/marker head) "server"))))))

(deftest refs-need-no-helper-because-ref-is-an-ordinary-slot
  (testing "`:ref` normalises to React's own `ref` slot and its value
            crosses by identity, like every other native prop. React 19
            hands a function component its ref as a prop, so there is
            nothing between the author and the ABI for a helper to
            preserve — which is why the helper surface is two and not
            three"
    (let [cell     #js {}
          js-props (n/props {:ref cell :label "px"})]
      (is (identical? cell (unchecked-get js-props "ref")))
      (is (= "px" (unchecked-get js-props "label")))))

  (testing "and a memo record passes props through untouched, so the same
            slot survives the helper. The DOM half — where the ref
            actually receives a node — is the dom suite's"
    (is (identical? quote-cell* (unchecked-get memoised "type")))))
