(ns re-frame.hicasso.fallback-contents-cljs-test
  "WHAT A `defhost` `:ssr` FALLBACK MAY CONTAIN — **THE CONTRACT**
  (rf2-nv07k, ruled 2026-08-05).

  [[re-frame.hicasso.impl.codec/mint-host-gate!]] states the rule
  the guide teaches: *\"a fallback is inert markup — it is not a body, so
  a subscription or an intent written there is the same loud error it
  would be anywhere outside a boundary.\"* This namespace used to record
  that half of it was enforced and half was not. It is now enforced, and
  these rows are what enforces it.

  ## What was wrong, and it was the ABSENCE of a rule rather than a narrow one

  The fallback is walked into an element ONCE, at the declaration,
  outside any frame. So what was refused was exactly *what that walk
  could evaluate* — an intent vector, a `sub` call in the form, hiccup
  that is not hiccup. A boundary head is none of those: it is a React
  element whose body runs LATER, so the walk never looked inside it and
  every refusal it carried was deferred past the declaration, which is
  the one thing a mint-time walk exists to prevent. It was not a rule
  about content at all; it was a property of the walk.

  ## Two measurements decided it, and both are kept below

  1. **The declared placeholder was not a value.** `mint-host-gate!`
     walks once and reuses the element everywhere, and its stated reason
     is *\"a placeholder that differs per site is not a placeholder\"*.
     One declaration carrying a boundary head rendered `ALPHA` in one
     frame, `BRAVO` in another and `ALPHA-TWO` after a write — the
     justification falsified by what it permitted.
     [[a-declared-placeholder-is-a-placeholder-again]] keeps that
     measurement, taken now against an INERT fallback, where all three
     documents are the same one.
  2. **It did not survive the arm's own other boundary variant.** This
     arm ships two mints:
     [[re-frame.hicasso.impl.collector/mint-view!]] (context-fed,
     what `defview` mints) and
     [[re-frame.hicasso.impl.collector/mint-frame-prop-view!]]
     (rf2-2rtt6.39). A frame-fed head reads `intent/*frame*` when its
     ELEMENT is created — which in a fallback is mint time, where the
     var is `nil` — so it baked `nil` in, minted happily, and threw
     `:rf.error/no-frame-prop` one render into the server response.
     Whether a boundary head in a fallback worked at all was therefore a
     property of which mint it came from, which is not a rule an author
     can hold. The refusal is walk-scoped and asks the MARKER, so it
     catches that variant for free —
     [[the-frame-fed-variant-is-caught-by-the-same-refusal]].

  ## The ruling, and the recovery it points at

  `:rf.error/hicasso-host-fallback-boundary-head`, at the declaration,
  naming the host, the offending head and its position in the declared
  form. The workaround this deletes — writing a provider's subtree a
  second time as the declaration's fallback, which was `rf2-l0wfx`'s only
  recovery — is SUPERSEDED rather than merely removed: `:ssr :render` now
  renders the real subtree on the server, with the real context value and
  no duplication ([[re-frame.hicasso.host-ssr-dom-cljs-test]]).

  ## The mutation witnesses — both directions

  **Remove the refusal** (delete `mint-host-gate!`'s call to
  `refuse-deferring-heads-in-fallback!`) and every row in §2 and §3 goes
  red: the heads mint again, and the frame-fed one goes back to throwing
  mid-render rather than at the declaration.

  **Over-refuse** (make `deferring-head-kind` answer a kind for anything
  non-nil, the shape a walk that confused \"an element\" with \"a
  deferring head\" would have) and every row in §4 goes red — one row per
  legitimate fallback position, so the failure names which position was
  taken away. §1 stays green under BOTH mutations, which is why it is
  separate: it is about the walk's own evaluation and not about this
  refusal at all.

  Both were run, `codec.cljs` restored from a byte copy after each.

  Runtime: `-cljs-test`, not `-dom-`. Every claim is a declaration or a
  `renderToString`, so there is nothing here a DOM would add."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-a ::fallback-contents-a)
(def ^:private frame-b ::fallback-contents-b)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :hicasso.fb/title (fn [db _] (:title db)))

(rf/reg-event :hicasso.fb/seed (fn [_ [_ title]] {:db {:title title}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- fresh!
  "Two frames holding different values, because frame isolation is what
  the placeholder rows are read against."
  []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [:hicasso.fb/seed "ALPHA"]))
  (rf/with-frame frame-b (rf/dispatch-sync [:hicasso.fb/seed "BRAVO"])))

;; ---------------------------------------------------------------------------
;; The component behind the door, and the things a fallback might contain
;; ---------------------------------------------------------------------------

(def ^:private theme-context
  "A context PROVIDER is the host used throughout, because it is the shape
  that makes the fallback matter (rf2-l0wfx): a transparent wrapper
  contributes no markup of its own, so an unadopted crossing renders the
  fallback and nothing else."
  (react/createContext "unset"))

(h/defview fallback-view
  "A `defview` head written into a fallback — the whole question."
  [_]
  [:section.fb-view [:h2.sub (collector/sub [:hicasso.fb/title])]])

(def ^:private frame-fed-view
  "The SAME body, minted through the arm's other boundary variant
  (rf2-2rtt6.39). Minted directly rather than through `defview` because
  `lang.clj`'s macro mints the context-fed one — which is exactly the
  point: the two are indistinguishable in hiccup and used to disagree
  here."
  (collector/mint-frame-prop-view!
    "fallback-contents/frame-fed-view"
    (fn frame-fed-view-body [_]
      [:section.fb-frame-fed [:h2.sub (collector/sub [:hicasso.fb/title])]])))

(defn- inner-component [_props]
  (react/createElement "i" #js {:className "inner"} "INNER"))

(h/defhost inner-host
  "A `defhost` head written into a fallback — a second deferring head, so
  the rule is not a `defview` fact."
  inner-component
  {:ssr {:fallback [:em.inner-fallback "INNER-FALLBACK"]}})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(defn- error-data [f]
  (try (f) {::did-not-throw true} (catch :default e (ex-data e))))

(defn- host-with-fallback
  "One declaration, made HERE rather than at the top level, so a row that
  is about the declaration can put its own hiccup in it."
  [host-name fallback]
  (codec/mint-host! host-name (.-Provider theme-context)
                    {:ssr {:fallback fallback}}))

(defn- server-html [frame hiccup]
  (react-dom-server/renderToString
    (mount/provider frame (codec/root-element frame hiccup))))

;; ---------------------------------------------------------------------------
;; 1 — what the mint-time WALK can see, it refuses (unchanged by the ruling)
;;
;; These three rows are about `as-element`'s own evaluation and not about
;; the structural refusal beside it. They were green before rf2-nv07k was
;; ruled and they are green after, under both mutations named in the ns
;; docstring — which is why they are a separate deftest.
;; ---------------------------------------------------------------------------

(deftest a-fallback-refuses-what-the-mint-time-walk-can-see
  (fresh!)
  (testing "an intent vector — the fallback is walked outside any frame, so
            there is no frame-locked dispatch to lower it against, and it
            is the same loud error it would be anywhere outside a boundary"
    (is (= :rf.error/hicasso-intent-outside-boundary
           (error-id #(host-with-fallback "fb/intent"
                                          [:button {:on-click [:x/y]} "go"])))))
  (testing "a `sub` call written in the fallback FORM — evaluated where the
            declaration is, which is outside any render"
    (is (= :rf.error/hicasso-sub-outside-render
           (error-id #(host-with-fallback "fb/sub"
                                          [:div (collector/sub [:hicasso.fb/title])])))))
  (testing "and hiccup that is not hiccup, which is the property the walk
            was moved to the declaration FOR"
    (is (= :rf.error/hicasso-empty-vector
           (error-id #(host-with-fallback "fb/empty" []))))))

;; ---------------------------------------------------------------------------
;; 2 — and what it CANNOT see is now refused structurally, ahead of it
;; ---------------------------------------------------------------------------

(deftest a-deferring-head-in-a-fallback-is-refused-at-the-declaration
  (fresh!)
  (testing "a `defview` head — an element whose body runs later, so the
            evaluating walk never looks inside it. The structural walk
            does, and refuses it where the author's stack is"
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(host-with-fallback "fb/view" [fallback-view {}])))))
  (testing "and so does a `defhost` head, so the rule is about DEFERRAL and
            not about `defview` — any head whose content the walk cannot
            reach is refused the same way"
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(host-with-fallback "fb/host" [inner-host {}])))))
  (testing "a bare head, with no vector around it at all — a fallback is a
            hiccup FORM, so the refusal is written against the form and
            not against head position"
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(host-with-fallback "fb/bare" fallback-view)))))
  (testing "and a head nested arbitrarily deep, because \"inert markup\"
            is a claim about the whole form"
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(host-with-fallback
                        "fb/deep"
                        [:div.skeleton
                         [:p "loading"]
                         [:section [:span [fallback-view {}]]]]))))
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(host-with-fallback
                        "fb/seq"
                        [:ul (for [i (range 2)]
                               [:li {:key i} [fallback-view {}]])]))))))

(deftest the-refusal-names-the-host-the-head-and-the-position
  (fresh!)
  (testing "a message that says only \"a boundary head\" leaves an author
            grepping a fallback they wrote three screens ago. All three
            facts are in the ex-data and all three are in the message"
    (let [data (error-data #(host-with-fallback
                              "fb/named"
                              [:div.skeleton [:span [fallback-view {}]]]))]
      (is (= :rf.error/hicasso-host-fallback-boundary-head (:rf.error/id data)))
      (is (= "fb/named" (:host data)) "the host whose declaration is at fault")
      (is (= "re-frame.hicasso.fallback-contents-cljs-test/fallback-view"
             (:head data))
          "the offending head, by the displayName its mint stamped")
      (is (= "defview" (:kind data)) "and which door minted it")
      (is (= [1 1 0] (:position data))
          "the index route into the DECLARED form — the div's child 1 is the
           span, whose child 1 is the offending vector, whose head is at 0")
      (is (= :write-inert-hiccup-or-declare-ssr-render (:recovery data))
          "and the recovery names the supersession, not just the ban")
      (is (re-find #"fb/named" (ex-message (try (host-with-fallback
                                                  "fb/named"
                                                  [:div.skeleton
                                                   [:span [fallback-view {}]]])
                                                (catch :default e e))))
          "and the MESSAGE carries them too — ex-data is for a test, the
           message is for the author")))
  (testing "the `defhost` case names its own door"
    (let [data (error-data #(host-with-fallback "fb/named-host" [inner-host {}]))]
      (is (= "defhost" (:kind data)))
      (is (= "re-frame.hicasso.fallback-contents-cljs-test/inner-host"
             (:head data)))
      (is (= [0] (:position data)) "head position of the fallback itself"))))

(deftest a-declared-placeholder-is-a-placeholder-again
  (fresh!)
  (testing "THE MEASUREMENT THAT DECIDED IT, kept and inverted. One
            declaration is walked ONCE into ONE element and reused at
            every site, and `mint-host-gate!`'s reason for that is \"a
            placeholder that differs per site is not a placeholder\". A
            boundary head made it differ per site AND per write — three
            different documents from one declaration. With the head
            refused, the reason holds: the SAME document in two isolated
            frames and again after a write"
    (let [head (host-with-fallback "fb/inert" [:section.fb-inert "SKELETON"])
          in-a (server-html frame-a [:div.page [head {:value "dark"}]])
          in-b (server-html frame-b [:div.page [head {:value "dark"}]])]
      (is (re-find #"SKELETON" in-a) (str "the placeholder rendered: " in-a))
      (is (= in-a in-b)
          (str "the SAME declared placeholder in a frame holding a different "
               "value — identical bytes, which is what a placeholder is: "
               in-a " vs " in-b))
      (rf/with-frame frame-a (rf/dispatch-sync [:hicasso.fb/seed "ALPHA-TWO"]))
      (let [after (server-html frame-a [:div.page [head {:value "dark"}]])]
        (is (= in-a after)
            (str "and a write moved nothing, because there is nothing there "
                 "to read a frame: " after)))
      ;; The non-vacuity control: those frames really do differ, so the
      ;; equality above is a fact about the placeholder and not about a
      ;; fixture that never varied.
      (is (not= (server-html frame-a [:div.page [fallback-view {}]])
                (server-html frame-b [:div.page [fallback-view {}]]))
          "the same body in an ordinary POSITION still differs per frame —
           the two frames are genuinely distinguishable"))))

;; ---------------------------------------------------------------------------
;; 3 — the frame-fed variant, caught by the same refusal
;; ---------------------------------------------------------------------------

(deftest the-frame-fed-variant-is-caught-by-the-same-refusal
  (fresh!)
  (testing "the frame-fed variant reads `intent/*frame*` when its ELEMENT is
            created. Everywhere else that is inside an ancestor body or
            `root-element`; in a fallback it is MINT TIME, where the var is
            nil — so it used to bake nil in, mint happily, and throw
            `:rf.error/no-frame-prop` one render into the server response.
            The refusal is walk-scoped and asks the boundary MARKER, so it
            covers this variant without knowing it exists"
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(host-with-fallback "fb/frame-fed" [frame-fed-view {}])))))
  (testing "and the throw has MOVED to the declaration, which is the whole
            point — an author's stack now names the line they wrote"
    (is (not= :rf.error/no-frame-prop
              (error-id #(host-with-fallback "fb/frame-fed-2"
                                             [frame-fed-view {}])))))
  (testing "while the SAME view inside an ordinary body still renders — so
            the refusal is about the fallback POSITION and not about the
            view, and the variant is not collateral damage"
    (let [html (server-html frame-a [:div.page [frame-fed-view {}]])]
      (is (re-find #"ALPHA" html) (str "control: " html)))))

;; ---------------------------------------------------------------------------
;; 4 — every legitimate fallback position, proven ONE AT A TIME
;;
;; A refusal is only as good as what it leaves alone. One row per shape a
;; real placeholder is written in, so an over-broad walk fails by NAME
;; rather than as a wall of red.
;; ---------------------------------------------------------------------------

(deftest inert-hiccup-in-every-position-still-mints
  (fresh!)
  (testing "a bare tag"
    (is (some? (host-with-fallback "ok/tag" [:div.skel]))))
  (testing "a tag with a props map"
    (is (some? (host-with-fallback "ok/props" [:div.skel {:data-live "no"} "loading"]))))
  (testing "nested vectors, arbitrarily deep"
    (is (some? (host-with-fallback "ok/nested"
                                   [:div.skel [:p [:span [:em "loading"]]]]))))
  (testing "a seq child — the lazy position, which the walk has to descend
            into by hand because a seq is not a vector"
    (is (some? (host-with-fallback "ok/seq"
                                   [:ul (for [i (range 3)] [:li {:key i} i])]))))
  (testing "a fragment"
    (is (some? (host-with-fallback "ok/fragment" [:<> [:span "a"] [:span "b"]]))))
  (testing "string, number, nil and false children — every scalar
            `as-element` accepts, none of which is a head"
    (is (some? (host-with-fallback "ok/scalars" [:div "text" 42 nil false]))))
  (testing "a bare string, which is legal hiccup and is not a vector at all"
    (is (some? (host-with-fallback "ok/string" "loading"))))
  (testing "an already-built React element, which the walk must pass over
            rather than descend into"
    (is (some? (host-with-fallback
                 "ok/element"
                 (react/createElement "div" #js {:className "skel"} "loading")))))
  (testing "a props map whose VALUES are collections — the walk descends
            through renderable positions, and a props map is not one"
    (is (some? (host-with-fallback "ok/prop-values"
                                   [:div.skel {:class ["a" "b"] :data-n 3}])))))

(deftest an-inert-fallback-still-renders-what-it-always-rendered
  (fresh!)
  (testing "the refusal is a declaration-time check and changes nothing
            about what a legal fallback puts in the server response"
    (let [head (host-with-fallback "ok/render"
                                   [:div.skel {:data-live "no"}
                                    [:span.a "loading"]
                                    (for [i (range 2)] [:i.dot {:key i} "."])])
          html (server-html frame-a [:div.page [head {:value "dark"}]])]
      (is (re-find #"class=\"skel\"" html) (str "the fallback is there: " html))
      (is (re-find #"loading" html))
      (is (= 2 (count (re-seq #"class=\"dot\"" html)))
          (str "including both rows of the seq: " html)))))
