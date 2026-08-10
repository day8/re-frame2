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

  Three witnesses cover the three macros, and only two of them are here.
  `defview` is already witnessed by
  [[re-frame.hicasso.smoke-cljs-test]] — a boundary minted through the
  door, asserted `boundary-head?`, rendering a live subscription read —
  and that suite is the third witness, unchanged.

  ## Why these assertions and not others

  Each row below pins one thing the macro EXPANSION decides, chosen so
  that changing the expansion cannot leave the row green:

  - what the expansion targets (`impl.intent/callback`,
    `impl.codec/mint-host!`), read back through the predicate that target
    mints — `callback?` and `host-head?`, one own-property read each;
  - that the author's own value survives the expansion unwrapped;
  - that an argument the author wrote reaches the declaration, which is
    `defhost`'s `opts`.

  Reaching to `impl.intent` and `impl.codec` for those two predicates is
  the same reach the package smoke makes for `boundary-head?`: the marker
  is the observable, and there is no other way to ask.

  ## The NODE lane, and no runtime state

  This is the node lane. Nothing here subscribes, dispatches or mounts, so
  nothing here needs a DOM, a registered frame or a runtime reset — the
  server renderer runs the crossing for real and the file leaves no state
  behind it. `defhost`'s DOM-driven counterpart is the isolation suite's
  containment row; this witness exists because that usage is incidental to
  a different contract and could refactor away without anyone noticing the
  door had gone unchecked.

  ## Naming

  The spellings asserted here are the prototype's — `defview`, `hfn`,
  `defhost`. The naming review recommends `h/event` for the callback form;
  when that lands, the sweep renames these witnesses with everything else."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.intent :as intent]
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
