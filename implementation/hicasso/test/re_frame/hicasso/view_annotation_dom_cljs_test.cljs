(ns re-frame.hicasso.view-annotation-dom-cljs-test
  "SPEC 006'S TWO DEV-MODE DOM ANNOTATIONS, ON A HICASSO BOUNDARY (rf2-c5w1).

  Spec 006 §Source-coord annotation requires `data-rf2-source-coord` on
  the rendered root DOM element of each registered view, §View tagging
  contract requires `data-rf-view` beside it on the same element and the
  same gate, and §Cross-host binds every in-scope React-binding adapter to
  both. Hicasso is one — Mike's 2026-07-31 ruling, and its own
  `re-frame.hicasso.substrate/adapter` — and a `rf.hicasso/defview` is a registered
  view: `rf.hicasso.impl.collector/publish-view-alias!` writes it into re-frame's `:view`
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
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::view-annotation)

(def ^:private view-ns
  "This namespace's name, which is the `<ns>` half of every id below. Taken
  as a literal rather than read off a var, so a rename reddens the rows
  instead of following them."
  "re-frame.hicasso.view-annotation-dom-cljs-test")

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The declarations — one per shape Spec 006 distinguishes
;; ---------------------------------------------------------------------------

(rf.hicasso/defview plain-root
  "A DOM-tag root with NO attrs map: the splice branch."
  [_]
  [:p.plain "plain"])

(rf.hicasso/defview attrs-root
  "A DOM-tag root that already carries attrs: the merge branch."
  [_]
  [:p {:class "attrs" :id "keep-me"} "attrs"])

(rf.hicasso/defview author-wins
  "A body that writes `:data-rf-view` itself; the author's value stands."
  [_]
  [:p {:class "wins" :data-rf-view "author-set"} "wins"])

(rf.hicasso/defview inner-view
  "The inner half of a boundary-rooted pair — it tags its OWN root."
  [_]
  [:p.inner "inner"])

(rf.hicasso/defview boundary-root
  "A root that is another declared view: Spec 006's component-head
  exemption, and Hicasso's most ordinary composition."
  [_]
  [inner-view {}])

(rf.hicasso/defview fragment-root
  "A fragment root: Spec 006's fragment exemption — no element to carry an
  attribute."
  [_]
  [:<> [:p.frag "fragment"]])

(rf.hicasso/defview screen
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
  (rf.hicasso.checkpoint-support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  frame-id)

(defn- q [handle sel] (.querySelector (:container handle) sel))

(defn- attr [handle sel a] (some-> (q handle sel) (.getAttribute a)))

(defn- view-attr [handle sel] (attr handle sel "data-rf-view"))

(defn- coord-attr [handle sel] (attr handle sel "data-rf2-source-coord"))

(defn- mounted! []
  (fresh!)
  (rf.hicasso.impl.mount/root! (rf.hicasso.impl.mount/fresh-container!) frame-id [screen {}]))

;; ---------------------------------------------------------------------------
;; 1 — the annotated shapes
;; ---------------------------------------------------------------------------

(deftest a-dom-rooted-boundary-carries-both-annotations
  (if-not (rf.hicasso.impl.mount/browser?)
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
        (finally (rf.hicasso.impl.mount/release! handle))))))

(deftest the-author-wins-a-collision
  (if-not (rf.hicasso.impl.mount/browser?)
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
        (finally (rf.hicasso.impl.mount/release! handle))))))

;; ---------------------------------------------------------------------------
;; 2 — the exemptions, asserted as absences
;; ---------------------------------------------------------------------------

(deftest a-boundary-rooted-body-is-exempt-and-its-child-tags-itself
  (if-not (rf.hicasso.impl.mount/browser?)
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
        (finally (rf.hicasso.impl.mount/release! handle))))))

(deftest a-fragment-rooted-body-is-exempt
  (if-not (rf.hicasso.impl.mount/browser?)
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
        (finally (rf.hicasso.impl.mount/release! handle))))))

;; ---------------------------------------------------------------------------
;; 3 — the forward/backward join a tool actually walks
;; ---------------------------------------------------------------------------

(deftest the-attribute-resolves-back-through-the-view-registrar
  (if-not (rf.hicasso.impl.mount/browser?)
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
        (finally (rf.hicasso.impl.mount/release! handle))))))
