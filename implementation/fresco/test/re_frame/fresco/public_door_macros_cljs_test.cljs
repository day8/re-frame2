(ns re-frame.fresco.public-door-macros-cljs-test
  "THE PUBLIC DOOR'S MACRO SHAPES, pinned by contract rather than by donor
  digest.

  The bench prototype's `arm1/lang.clj` defines the same three macros,
  but *is the package the prototype, moved* is not answerable for them:
  `defview`'s expansion target is `impl.collector/mint-view!` here where
  the prototype's is `impl.runtime/mint-view!`. So the AUTHORING SURFACE,
  the part of the package a consumer actually writes against, is checked
  by a different KIND of instrument: what a consumer needs answered is
  *does the door still hand back what it promises* — a contract, not a
  provenance diff. Byte-for-byte expansion snapshots would be the wrong
  instrument too: incidental symbol and def-layout detail would make safe
  refactoring noisy for no consumer confidence.

  Witnesses A, B and C cover the three macros; D and E are a
  second claim about `defhost`, this time about the SHAPE of the form
  rather than the value it hands on. `defview`'s round-trip — a boundary
  minted through the door, asserted `boundary-head?`, rendering a live
  subscription read — is [[re-frame.fresco.smoke-cljs-test]]'s and stays
  there. Witness C below is a different claim about the same macro, and
  it is here because it is about the EXPANSION rather than about the
  runtime: what `defview` names inside the fn it emits.

  ## Why these assertions and not others

  Each row below pins one thing the macro EXPANSION decides, chosen so
  that changing the expansion cannot leave the row green:

  - what the expansion targets (`impl.intent/callback`,
    `impl.codec/mint-host!`), read back through the predicate that target
    mints — `callback?` and `host-head?`, one own-property read each;
  - that the author's own value survives the expansion unwrapped;
  - that an argument the author wrote reaches the declaration, which is
    `defhost`'s `opts`;
  - that the fn `defview` emits binds NO name a body could reach, so the
    author's lexical scope is the one the body runs in.

  Reaching to `impl.intent` and `impl.codec` for those two predicates is
  the same reach the package smoke makes for `boundary-head?`: the marker
  is the observable, and there is no other way to ask.

  ## The NODE lane

  This is the node lane, and the server renderer runs both crossings for
  real. Witnesses A and B subscribe, dispatch and mount nothing, so they
  need no DOM, no registered frame and no runtime reset. Witness C runs a
  BODY, and a body binds the ambient frame whether or not it reads
  anything — so it seats a frame of its own and scopes the render with
  `impl.mount/provider`, exactly as the package smoke does. A
  `renderToString` never commits, so it still leaves no runtime state
  behind it.

  `defhost`'s DOM-driven counterpart is the isolation suite's containment
  row; witness B exists because that usage is incidental to a different
  contract and could refactor away without anyone noticing the door had
  gone unchecked.

  ## Naming

  The spellings asserted here are the public door's, and the prototype's
  alike — `defview`, `event`, `defhost`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::public-door)

;; ---------------------------------------------------------------------------
;; Witness A — `h/event`, the one callback form
;; ---------------------------------------------------------------------------

(deftest hfn-mints-an-ordinary-function-that-a-position-can-recognise
  (let [picked (rf.fresco/event [e] [:door/picked (.-value e)])]

    (testing "the value is an ordinary function — no carrier object, nothing
              that can fail to be callable where Fresco does not walk"
      (is (fn? picked)))

    (testing "and calling it returns what the author wrote, unwrapped"
      (is (= [:door/picked "todo"] (picked #js {:value "todo"}))))

    (testing "the mark the expansion applies is on it, so a walked position
              can impose its contract"
      (is (true? (rf.fresco.impl.intent/callback? picked))))

    (testing "and the witness discriminates rather than restating `fn?`: a
              plain `fn` written the same way is NOT the callback form"
      (is (false? (rf.fresco.impl.intent/callback? (fn [e] [:door/picked (.-value e)])))))))

;; ---------------------------------------------------------------------------
;; Witness B — `h/defhost`, the interop door
;; ---------------------------------------------------------------------------

(defn- badge-component
  "A foreign React component: not a boundary, not hiccup, nothing Fresco
  minted. The crossing is the only thing that can put its markup on the
  page."
  [^js props]
  (react/createElement "b" #js {"className" "badge"} (.-label props)))

(rf.fresco/defhost badge badge-component {:server :render})

(defn- html
  [hiccup]
  (react-dom-server/renderToString (rf.fresco.impl.codec/root-element frame-id hiccup)))

(deftest defhost-mints-a-host-head-that-carries-the-option-it-was-declared-with
  (testing "the door hands back a minted host head, not the component the
            author named"
    (is (true? (rf.fresco.impl.codec/host-head? badge)))
    (is (not (identical? badge badge-component))))

  (testing "the `opts` argument reaches the declaration: the policy read back
            off the head is the one written at the call site, and it is the
            only reason the server render below produces anything at all —
            `:client-only`, the default a dropped argument would leave,
            renders no host region on the server"
    (is (= :render (rf.fresco.impl.codec/host-server badge))))

  (testing "and the minted var is a legal hiccup head inside an ordinary tree,
            with the crossing rendering the foreign component's own markup"
    (let [markup (html [:div.crossing [badge {:label "fresco"}]])]
      (is (re-find #"<b[^>]*class=\"badge\"[^>]*>fresco</b>" markup)))))

;; ---------------------------------------------------------------------------
;; Witness C — `h/defview`, and the name its emitted fn does NOT bind
;; ---------------------------------------------------------------------------
;;
;; The emitted fn is ANONYMOUS, and that is the contract this witness
;; holds. A NAMED `fn` binds its own name inside its body, so naming it
;; `<sym>-body` makes the ordinary extract-a-helper spelling below expand
;; to `(fn ticket-body [p] (ticket-body p))` and recurse until the stack
;; overflows. Under React the symptom is `Maximum call stack size
;; exceeded` with no id, no view name and nothing pointing at the macro.
;;
;; The helper is named after the view DELIBERATELY: that is the collision,
;; and a helper named any other way would leave this row green whatever the
;; expansion binds.

(defn- ticket-body
  "The helper a body extracts, spelled the way an author spells it — the
  view's own name with `-body` on the end."
  [{:keys [id]}]
  [:li.ticket (str "ticket " id)])

(rf.fresco/defview ticket [props] (ticket-body props))

(defn- rendered
  "One boundary, server-rendered under a frame — React running the body
  for real, which is the level this collision bites at as hard as it
  bites at the kit's."
  [hiccup]
  ;; `renderToString` is not inside React's act queue, and the flag is set
  ;; outright for the reason the package smoke gives: the helper that
  ;; carries it lives in the bench tree, which this package may not import.
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (react-dom-server/renderToString
    (rf.fresco.impl.mount/provider frame-id (rf.fresco.impl.codec/root-element frame-id hiccup))))

(deftest the-fn-defview-emits-binds-no-name-a-body-could-reach
  (testing "the door hands back a minted boundary, so what renders below is
            the expansion's own product"
    (is (true? (rf.fresco.impl.codec/boundary-head? ticket))))

  (testing "a body that calls a helper named after its view resolves the
            AUTHOR's helper: React runs the body once and the markup is the
            helper's, where a self-binding emitted fn would recurse forever"
    (is (re-find #"<li[^>]*class=\"ticket\"[^>]*>ticket 7</li>"
                 (rendered [ticket {:id 7}]))))

  (testing "and the identifier the expansion DOES decide holds — the
            `\"<ns>/<sym>\"` name React DevTools shows and Spec 009 keys
            `rf:render:<name>` on"
    (is (= "re-frame.fresco.public-door-macros-cljs-test/ticket"
           (unchecked-get ticket "displayName")))))

;; ---------------------------------------------------------------------------
;; Witnesses D and E — `h/defhost`'s two shapes, and what is outside them
;; ---------------------------------------------------------------------------
;;
;; The door's arity is part of its contract. `[component opts]` is a
;; fixed-width destructure over a variadic tail, so unguarded, a second
;; options map would mint as if absent — the head would read back
;; consistent (it simply has no slots) and the markup written at a
;; declared slot could never arrive. `mint-host!` refuses an option key
;; it does not know for exactly that reason, one layer down; this is the
;; same rule at the door.
;;
;; The refusals are driven THROUGH THE MACRO where the fault is in the
;; form, and through `mint-host!` directly where the fault is in a value —
;; which is also the non-macro caller's path, and the one the door-macro
;; witnesses above already use for the same reason.

(defn- error-id
  "The `:rf.error/id` of whatever `f` threw, or nil if it returned."
  [f]
  (try (f) nil (catch :default e (:rf.error/id (ex-data e)))))

(defn- error-data
  [f]
  (try (f) nil (catch :default e (ex-data e))))

(deftest defhost-refuses-a-form-after-its-options-map
  (testing "a second options map is not merged — the destructure would
            DISCARD it, so the declaration is refused at the door rather than
            minting a head whose declared slots silently do not exist"
    (let [data (error-data
                 #(rf.fresco/defhost two-options-host badge-component
                    {:server :render}
                    {:slots #{:title}}))]
      (is (= :rf.error/fresco-bad-host-declaration (:rf.error/id data))
          (str "the tail was refused and named. Raised: " (pr-str data)))
      (is (= ['{:slots #{:title}}] (:extra data))
          "and the refusal carries the FORM that would have been dropped,
           quoted rather than evaluated")
      (is (= "re-frame.fresco.public-door-macros-cljs-test/two-options-host"
             (:host data))
          "named by the declaration it belongs to")))

  (testing "THE NEAR MISS, and it is the whole reason this guard has to be
            exact: the legal three-form shape — docstring, component, options
            — is one form longer than the refused two-form one and must still
            mint, keep its `opts`, and carry its docstring"
    (is (true? (rf.fresco.impl.codec/host-head? badge))
        "the two-argument shape, declared at the top of this file")
    (is (= :render (rf.fresco.impl.codec/host-server badge))
        "with its options intact")))

(deftest defhost-refuses-options-that-are-not-a-map
  (testing "unguarded, a non-map would go straight from `mint-host!`'s
            nil-component check to `(keys opts)`, and whatever `keys`
            raised would not be a declaration refusal. `h/reg-state` carries
            the same guard; this is it on the comparable surface"
    (let [data (error-data #(rf.fresco.impl.codec/mint-host! "doc/in-the-wrong-place"
                                              badge-component
                                              "a docstring in the wrong place"))]
      (is (= :rf.error/fresco-bad-host-declaration (:rf.error/id data))
          (str "refused, and from the door rather than from inside `keys`. "
               "Raised: " (pr-str data)))
      (is (= "a docstring in the wrong place" (:options data))
          "carrying the value it was given")))

  (testing "and every other non-map is refused the same way"
    (is (= :rf.error/fresco-bad-host-declaration
           (error-id #(rf.fresco.impl.codec/mint-host! "bad/vec" badge-component [:server :render]))))
    (is (= :rf.error/fresco-bad-host-declaration
           (error-id #(rf.fresco.impl.codec/mint-host! "bad/kw" badge-component :render))))
    (is (= :rf.error/fresco-bad-host-declaration
           (error-id #(rf.fresco.impl.codec/mint-host! "bad/set" badge-component #{:server})))))

  (testing "THE NEAR MISS. `nil` is *no options*, which is exactly what the
            two-arity call means, so a guard that refused it would refuse the
            door's own commonest shape. All three legal spellings still mint"
    (is (true? (rf.fresco.impl.codec/host-head? (rf.fresco.impl.codec/mint-host! "ok/nil" badge-component nil))))
    (is (true? (rf.fresco.impl.codec/host-head? (rf.fresco.impl.codec/mint-host! "ok/empty" badge-component {}))))
    (is (true? (rf.fresco.impl.codec/host-head? (rf.fresco.impl.codec/mint-host! "ok/arity2" badge-component))))
    (is (= :render (rf.fresco.impl.codec/host-server (rf.fresco.impl.codec/mint-host! "ok/opts" badge-component
                                                     {:server :render})))
        "and a real options map still reaches the declaration")))
