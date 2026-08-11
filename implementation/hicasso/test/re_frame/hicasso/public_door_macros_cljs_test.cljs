(ns re-frame.hicasso.public-door-macros-cljs-test
  "THE PUBLIC DOOR'S MACRO SHAPES, pinned by contract rather than by donor
  digest (rf2-0xgk).

  `arm1/lang.clj` defines the three macros a consumer writes against, and
  until rf2-hic-009 split the runtime its row in `frozen-sources.edn` held
  each of them to its donor. The split made that row inexpressible —
  `defview`'s expansion target moved from `impl.runtime/mint-view!` to
  `impl.collector/mint-view!`, and `:renames` is a one-to-one table that
  cannot say which of six modules a call site should now name — so the row
  was retired, and retiring a row drops the donor digest along with the
  comparison. The other two macros were collateral: `hfn` and `defhost`
  still match their donor exactly, but a row is judged whole, and the
  alternative — a per-shape exemption — is the one thing that gate must
  never grow.

  So the AUTHORING SURFACE, the part of the package a consumer actually
  writes against, briefly became the least-checked thing in it. This file
  is the replacement instrument, and it is deliberately a different KIND
  of check: the freeze gate answers *is the package the prototype, moved*,
  which stopped being answerable for a file whose namespace legitimately
  moved. What a consumer needs answered instead is *does the door still
  hand back what it promises* — a contract, not a provenance diff. Byte-
  for-byte expansion snapshots were considered and rejected: incidental
  symbol and def-layout detail would make safe refactoring noisy for no
  consumer confidence.

  Witnesses A, B and C cover the three macros; D and E (rf2-3f11) are a
  second claim about `defhost`, this time about the SHAPE of the form
  rather than the value it hands on. `defview`'s round-trip — a boundary
  minted through the door, asserted `boundary-head?`, rendering a live
  subscription read — is [[re-frame.hicasso.smoke-cljs-test]]'s and stays
  there. Witness C below is a different claim about the same macro, and
  it is here because it is about the EXPANSION rather than about the
  runtime: what `defview` names inside the fn it emits (rf2-jan2).

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

  The spellings asserted here are the prototype's — `defview`, `hfn`,
  `defhost`. The naming review recommends `h/event` for the callback form;
  when that lands, the sweep renames these witnesses with everything else."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.mount :as mount]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::public-door)

;; ---------------------------------------------------------------------------
;; Witness A — `h/hfn`, the one callback form
;; ---------------------------------------------------------------------------

(deftest hfn-mints-an-ordinary-function-that-a-position-can-recognise
  (let [picked (h/hfn [e] [:door/picked (.-value e)])]

    (testing "the value is an ordinary function — no carrier object, nothing
              that can fail to be callable where Hicasso does not walk"
      (is (fn? picked)))

    (testing "and calling it returns what the author wrote, unwrapped"
      (is (= [:door/picked "todo"] (picked #js {:value "todo"}))))

    (testing "the mark the expansion applies is on it, so a walked position
              can impose its contract"
      (is (true? (intent/callback? picked))))

    (testing "and the witness discriminates rather than restating `fn?`: a
              plain `fn` written the same way is NOT the callback form"
      (is (false? (intent/callback? (fn [e] [:door/picked (.-value e)])))))))

;; ---------------------------------------------------------------------------
;; Witness B — `h/defhost`, the interop door
;; ---------------------------------------------------------------------------

(defn- badge-component
  "A foreign React component: not a boundary, not hiccup, nothing Hicasso
  minted. The crossing is the only thing that can put its markup on the
  page."
  [^js props]
  (react/createElement "b" #js {"className" "badge"} (.-label props)))

(h/defhost badge badge-component {:ssr :render})

(defn- html
  [hiccup]
  (react-dom-server/renderToString (codec/root-element frame-id hiccup)))

(deftest defhost-mints-a-host-head-that-carries-the-option-it-was-declared-with
  (testing "the door hands back a minted host head, not the component the
            author named"
    (is (true? (codec/host-head? badge)))
    (is (not (identical? badge badge-component))))

  (testing "the `opts` argument reaches the declaration: the policy read back
            off the head is the one written at the call site, and it is the
            only reason the server render below produces anything at all —
            `:client-only`, the default a dropped argument would leave,
            renders no host region on the server"
    (is (= :render (codec/host-ssr badge))))

  (testing "and the minted var is a legal hiccup head inside an ordinary tree,
            with the crossing rendering the foreign component's own markup"
    (let [markup (html [:div.crossing [badge {:label "hicasso"}]])]
      (is (re-find #"<b[^>]*class=\"badge\"[^>]*>hicasso</b>" markup)))))

;; ---------------------------------------------------------------------------
;; Witness C — `h/defview`, and the name its emitted fn does NOT bind
;; ---------------------------------------------------------------------------
;;
;; rf2-jan2. The macro used to name the fn it emits `<sym>-body`, and a
;; NAMED `fn` binds its own name inside its body — so the ordinary
;; extract-a-helper spelling below expanded to
;; `(fn ticket-body [p] (ticket-body p))` and recursed until the stack
;; overflowed. Under React the symptom was `Maximum call stack size
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

(h/defview ticket [props] (ticket-body props))

(defn- rendered
  "One boundary, server-rendered under a frame — React running the body
  for real, which is the level this collision bit at as hard as it bit at
  the kit's."
  [hiccup]
  ;; `renderToString` is not inside React's act queue, and the flag is set
  ;; outright for the reason the package smoke gives: the helper that
  ;; carries it lives in the bench tree, which this package may not import.
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(deftest the-fn-defview-emits-binds-no-name-a-body-could-reach
  (testing "the door hands back a minted boundary, so what renders below is
            the expansion's own product"
    (is (true? (codec/boundary-head? ticket))))

  (testing "a body that calls a helper named after its view resolves the
            AUTHOR's helper: React runs the body once and the markup is the
            helper's, where the emitted fn's self-binding recursed forever"
    (is (re-find #"<li[^>]*class=\"ticket\"[^>]*>ticket 7</li>"
                 (rendered [ticket {:id 7}]))))

  (testing "and the identifier the expansion DOES decide is unchanged — the
            `\"<ns>/<sym>\"` name React DevTools shows and Spec 009 keys
            `rf:render:<name>` on"
    (is (= "re-frame.hicasso.public-door-macros-cljs-test/ticket"
           (unchecked-get ticket "displayName")))))

;; ---------------------------------------------------------------------------
;; Witnesses D and E — `h/defhost`'s two shapes, and what is outside them
;; (rf2-3f11)
;; ---------------------------------------------------------------------------
;;
;; The door's arity is part of its contract, and it was the one part
;; nothing checked. `[component opts]` is a fixed-width destructure over a
;; variadic tail, so a second options map minted as if absent — the head
;; read back consistent (it simply had no slots) and the markup written at
;; a declared slot could never arrive. `mint-host!` refuses an option key
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
  (testing "a second options map is not merged and never was — it is
            DISCARDED, so the declaration is refused at the door rather than
            minting a head whose declared slots silently do not exist"
    (let [data (error-data
                 #(h/defhost two-options-host badge-component
                    {:ssr :render}
                    {:slots #{:title}}))]
      (is (= :rf.error/hicasso-host-extra-form (:rf.error/id data))
          (str "the tail was refused and named. Raised: " (pr-str data)))
      (is (= ['{:slots #{:title}}] (:extra data))
          "and the refusal carries the FORM that would have been dropped,
           quoted rather than evaluated")
      (is (= "re-frame.hicasso.public-door-macros-cljs-test/two-options-host"
             (:host data))
          "named by the declaration it belongs to")))

  (testing "THE NEAR MISS, and it is the whole reason this guard has to be
            exact: the legal three-form shape — docstring, component, options
            — is one form longer than the refused two-form one and must still
            mint, keep its `opts`, and carry its docstring"
    (is (true? (codec/host-head? badge))
        "the two-argument shape, declared at the top of this file")
    (is (= :render (codec/host-ssr badge))
        "with its options intact")))

(deftest defhost-refuses-options-that-are-not-a-map
  (testing "`mint-host!` went straight from the nil-component check to
            `(keys opts)`, so a non-map reached `keys` and whatever that
            raised was not a declaration refusal. `h/reg-state` has had this
            guard since it was written; this is the same guard on the
            comparable surface"
    (let [data (error-data #(codec/mint-host! "doc/in-the-wrong-place"
                                              badge-component
                                              "a docstring in the wrong place"))]
      (is (= :rf.error/hicasso-host-bad-options (:rf.error/id data))
          (str "refused, and from the door rather than from inside `keys`. "
               "Raised: " (pr-str data)))
      (is (= "a docstring in the wrong place" (:options data))
          "carrying the value it was given")))

  (testing "and every other non-map is refused the same way"
    (is (= :rf.error/hicasso-host-bad-options
           (error-id #(codec/mint-host! "bad/vec" badge-component [:ssr :render]))))
    (is (= :rf.error/hicasso-host-bad-options
           (error-id #(codec/mint-host! "bad/kw" badge-component :render))))
    (is (= :rf.error/hicasso-host-bad-options
           (error-id #(codec/mint-host! "bad/set" badge-component #{:ssr})))))

  (testing "THE NEAR MISS. `nil` is *no options*, which is exactly what the
            two-arity call means, so a guard that refused it would refuse the
            door's own commonest shape. All three legal spellings still mint"
    (is (true? (codec/host-head? (codec/mint-host! "ok/nil" badge-component nil))))
    (is (true? (codec/host-head? (codec/mint-host! "ok/empty" badge-component {}))))
    (is (true? (codec/host-head? (codec/mint-host! "ok/arity2" badge-component))))
    (is (= :render (codec/host-ssr (codec/mint-host! "ok/opts" badge-component
                                                     {:ssr :render})))
        "and a real options map still reaches the declaration")))
