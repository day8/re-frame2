(ns re-frame.hicasso.view-annotation-dom-cljs-test
  "SPEC 006'S TWO DEV-MODE DOM ANNOTATIONS, ON A HICASSO BOUNDARY (rf2-c5w1).

  Spec 006 §Source-coord annotation requires `data-rf2-source-coord` on
  the rendered root DOM element of each registered view, §View tagging
  contract requires `data-rf-view` beside it on the same element and the
  same gate, and §Cross-host binds every in-scope React-binding adapter to
  both. Hicasso is one — Mike's 2026-07-31 ruling, and its own
  `re-frame.hicasso.substrate/adapter` — and a `h/defview` is a registered
  view: `collector/publish-view-alias!` writes it into re-frame's `:view`
  registrar under the id `rf/reg-view` would have derived from the same
  symbol.

  ## Why this witness is taken at the DOM and not at the hiccup

  The Reagent counterpart
  (`implementation/adapters/reagent/test/re_frame/view_id_attr_cljs_test.cljs`)
  reads the annotated hiccup straight back out of `(rf/view id)`, because
  there the registered thing IS a hiccup-returning render fn. A Hicasso
  boundary is a React component and `(rf/view id)` answers nil for it
  deliberately, so there is no hiccup to read back; and the claim worth
  proving is in any case the consumers' one — Xray's hover-highlight and
  Pair's `ui/read` hold a DOM NODE and ask what view painted it. So the
  assertions here mount and query, which also proves the attributes
  survive the codec's prop conversion rather than merely being present in
  the map the collector built.

  ## The exemptions are asserted as ABSENCES, and they are the sharp half

  A test that only checked the annotated shapes would pass just as well
  against a stamp that annotated everything — including the `[:> …]`
  crossings and the foreign components Spec 006 §Documented exemption
  keeps framework strings out of. `boundary-root` and `fragment-root`
  below are the discriminating rows: each renders a real element that must
  NOT carry the outer view's name, and `boundary-root`'s child must carry
  its OWN."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::view-annotation)

(def ^:private view-ns
  "This namespace's name, which is the `<ns>` half of every id below. Taken
  as a literal rather than read off a var, so a rename reddens the rows
  instead of following them."
  "re-frame.hicasso.view-annotation-dom-cljs-test")

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The declarations — one per shape Spec 006 distinguishes
;; ---------------------------------------------------------------------------

(h/defview plain-root
  "A DOM-tag root with NO attrs map: the splice branch."
  [_]
  [:p.plain "plain"])

(h/defview attrs-root
  "A DOM-tag root that already carries attrs: the merge branch."
  [_]
  [:p {:class "attrs" :id "keep-me"} "attrs"])

(h/defview author-wins
  "A body that writes `:data-rf-view` itself; the author's value stands."
  [_]
  [:p {:class "wins" :data-rf-view "author-set"} "wins"])

(h/defview inner-view
  "The inner half of a boundary-rooted pair — it tags its OWN root."
  [_]
  [:p.inner "inner"])

(h/defview boundary-root
  "A root that is another declared view: Spec 006's component-head
  exemption, and Hicasso's most ordinary composition."
  [_]
  [inner-view {}])

(h/defview fragment-root
  "A fragment root: Spec 006's fragment exemption — no element to carry an
  attribute."
  [_]
  [:<> [:p.frag "fragment"]])

(h/defview screen
  "One mount carrying every shape above."
  [_]
  [:div.screen
   [plain-root {}]
   [attrs-root {}]
   [author-wins {}]
   [boundary-root {}]
   [fragment-root {}]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skip! [why]
  (is true (str "a DOM claim needs a real DOM — " why)))

(defn- fresh! []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  frame-id)

(defn- q [handle sel] (.querySelector (:container handle) sel))

(defn- attr [handle sel a] (some-> (q handle sel) (.getAttribute a)))

(defn- view-attr [handle sel] (attr handle sel "data-rf-view"))

(defn- coord-attr [handle sel] (attr handle sel "data-rf2-source-coord"))

(defn- mounted! []
  (fresh!)
  (mount/root! (mount/fresh-container!) frame-id [screen {}]))

;; ---------------------------------------------------------------------------
;; 1 — the annotated shapes
;; ---------------------------------------------------------------------------

(deftest a-dom-rooted-boundary-carries-both-annotations
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (testing "a root with no attrs map gets one spliced in, carrying both"
          (is (= (str ":" view-ns "/plain-root") (view-attr handle ".plain"))
              "`data-rf-view` is (str id) — the leading colon is preserved, and
               the id is the one `publish-view-alias!` registered")
          (is (str/starts-with? (coord-attr handle ".plain")
                                (str view-ns ":plain-root:"))
              "`data-rf2-source-coord` is <ns>:<sym>:<line>:<col>")
          (is (re-find #":\d+:\d+$" (coord-attr handle ".plain"))
              "with REAL line/col — `?:?` would mean the macro's coordinate
               never reached the mint"))

        (testing "a root that already has attrs keeps every one of them"
          (is (= (str ":" view-ns "/attrs-root") (view-attr handle ".attrs")))
          (is (some? (coord-attr handle ".attrs")))
          (is (= "keep-me" (attr handle ".attrs" "id"))
              "the author's own attrs are untouched by the merge"))

        (testing "the mount's own outer view is annotated too — the stamp is
                  per boundary, not per tree"
          (is (= (str ":" view-ns "/screen") (view-attr handle ".screen"))))
        (finally (mount/release! handle))))))

(deftest the-author-wins-a-collision
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (is (= "author-set" (view-attr handle ".wins"))
            "a body that wrote `:data-rf-view` itself keeps its value — the
             framework merges UNDER the author, exactly as the Reagent walk
             does")
        (is (str/starts-with? (coord-attr handle ".wins")
                              (str view-ns ":author-wins:"))
            "and the attribute it did NOT write is still stamped")
        (finally (mount/release! handle))))))

;; ---------------------------------------------------------------------------
;; 2 — the exemptions, asserted as absences
;; ---------------------------------------------------------------------------

(deftest a-boundary-rooted-body-is-exempt-and-its-child-tags-itself
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (testing "the element `boundary-root` produced carries the INNER
                  view's name, because the inner view is what tagged it"
          (is (= (str ":" view-ns "/inner-view") (view-attr handle ".inner"))))
        (testing "and `boundary-root` itself appears nowhere in the DOM — a
                  component head is Spec 006's documented exemption, and
                  stamping it would have put the outer name on the inner
                  view's element"
          (is (nil? (q handle (str "[data-rf-view='" ":" view-ns "/boundary-root']")))
              "no element claims to be boundary-root"))
        (finally (mount/release! handle))))))

(deftest a-fragment-rooted-body-is-exempt
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (testing "a fragment has no element to carry an attribute, so its
                  child is left alone rather than being annotated in its
                  place"
          (is (some? (q handle ".frag")) "the child rendered")
          (is (nil? (view-attr handle ".frag"))
              "and carries no view id — it is the fragment's child, not a
               registered view's root")
          (is (nil? (coord-attr handle ".frag"))))
        (finally (mount/release! handle))))))

;; ---------------------------------------------------------------------------
;; 3 — the forward/backward join a tool actually walks
;; ---------------------------------------------------------------------------

(deftest the-attribute-resolves-back-through-the-view-registrar
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (let [handle (mounted!)]
      (try
        (testing "the id read off the DOM node is the id the registrar
                  answers for — which is the whole point of the annotation:
                  a tool holding a node reaches the declaration"
          (let [raw (view-attr handle ".plain")
                id  (keyword (subs raw 1))]
            (is (= (keyword view-ns "plain-root") id)
                "the walker's documented read-back — `(keyword (subs s 1))`")
            (is (some? (rf/handler-meta :view id))
                "and that id has a `:view` registrar entry, so the coordinate
                 is recoverable even for the exempt shapes above")))
        (finally (mount/release! handle))))))
