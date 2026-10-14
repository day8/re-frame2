(ns re-frame.fresco.fallback-contents-cljs-test
  "WHAT A `defhost` `:fallback` MAY CONTAIN — **THE CONTRACT**.

  [[re-frame.fresco.impl.codec/mint-host-gate!]] states the rule
  the guide teaches: *\"a fallback is inert markup — it is not a body, so
  a subscription or an intent written there is the same loud error it
  would be anywhere outside a boundary.\"* These rows are what enforces
  it, and the structural refusal beside the walk is why they can.

  ## Why a mint-time WALK is not enough on its own

  The fallback is walked into an element ONCE, at the declaration,
  outside any frame, so a walk alone refuses exactly *what it can
  evaluate* — an intent vector, a `sub` call in the form, hiccup that is
  not hiccup. A boundary head is none of those: it is a React element
  whose body runs LATER, so a walk never looks inside it and every
  refusal it carries is deferred past the declaration, which is the one
  thing a mint-time walk exists to prevent. The rule the fallback needs
  is therefore STRUCTURAL, about the declared form, and not a property of
  the walk.

  ## Why the declared placeholder has to be a value

  `mint-host-gate!` walks once and reuses the element everywhere, and
  its stated reason is *\"a placeholder that differs per site is not a
  placeholder\"*. One declaration carrying a boundary head would render
  `ALPHA` in one frame, `BRAVO` in another and `ALPHA-TWO` after a
  write — the justification falsified by what it permitted. The refusal
  is walk-scoped and asks the
  boundary MARKER rather than the mint, so it holds for every head the
  mint door produces.

  ## The refusal, and the recovery it points at

  `:rf.error/fresco-host-fallback-boundary-head`, at the declaration,
  naming the host, the offending head and its position in the declared
  form. The recovery is not to write a provider's subtree a second time
  as the declaration's fallback: `:server :render` renders the real
  subtree on the server, with the real context value and no duplication
  ([[re-frame.fresco.host-ssr-dom-cljs-test]]).

  ## The mutation witnesses — both directions

  **Remove the refusal** (delete `mint-host-gate!`'s call to
  `refuse-deferring-heads-in-fallback!`) and every row in §2 goes red:
  the heads mint again.

  **Over-refuse** (make `deferring-head-kind` answer a kind for anything
  non-nil, the shape a walk that confused \"an element\" with \"a
  deferring head\" would have) and §3 goes red: a legal fallback no longer
  mints. §1 stays green under the first mutation, which is why it is
  separate: it is about the walk's own evaluation and not about this
  refusal at all. (The second reaches its intent row too, because the
  structural refusal runs ahead of the walk and would claim that form
  first.)

  Runtime: `-cljs-test`, not `-dom-`. Every claim is a declaration or a
  `renderToString`, so there is nothing here a DOM would add."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.checkpoint-support :as rf.fresco.checkpoint-support]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.test-support :as rf.test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-a ::fallback-contents-a)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :fresco.fb/title (fn [db _] (:title db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(defn- fresh!
  []
  (rf.fresco.checkpoint-support/leave-act-environment!)
  (rf/make-frame {:id frame-a}))

;; ---------------------------------------------------------------------------
;; The component behind the door, and the things a fallback might contain
;; ---------------------------------------------------------------------------

(def ^:private theme-context
  "A context PROVIDER is the host used throughout, because it is the shape
  that makes the fallback matter: a transparent wrapper
  contributes no markup of its own, so an unadopted crossing renders the
  fallback and nothing else."
  (react/createContext "unset"))

(rf.fresco/defview fallback-view
  "A `defview` head written into a fallback — the whole question."
  [_]
  [:section.fb-view [:h2.sub (rf.fresco.impl.collector/sub [:fresco.fb/title])]])

(defn- inner-component [_props]
  (react/createElement "i" #js {:className "inner"} "INNER"))

(rf.fresco/defhost inner-host
  "A `defhost` head written into a fallback — a second deferring head, so
  the rule is not a `defview` fact."
  inner-component
  {:fallback [:em.inner-fallback "INNER-FALLBACK"]})

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
  (rf.fresco.impl.codec/mint-host! host-name (.-Provider theme-context)
                    {:fallback fallback}))

(defn- server-html [frame hiccup]
  (react-dom-server/renderToString
    (rf.fresco.impl.mount/provider frame (rf.fresco.impl.codec/root-element frame hiccup))))

;; ---------------------------------------------------------------------------
;; 1 — what the mint-time WALK can see, it refuses
;;
;; This row is about `as-element`'s own evaluation and not about the
;; structural refusal beside it. It is green with the refusal removed (the
;; ns docstring's first mutation) — which is why it is a separate deftest.
;; ---------------------------------------------------------------------------

(deftest a-fallback-refuses-what-the-mint-time-walk-can-see
  (fresh!)
  (testing "an intent vector — the fallback is walked outside any frame, so
            there is no frame-locked dispatch to lower it against, and it
            is the same loud error it would be anywhere outside a boundary"
    (is (= :rf.error/fresco-intent-outside-boundary
           (error-id #(host-with-fallback "fb/intent"
                                          [:button {:on-click [:x/y]} "go"]))))))

;; ---------------------------------------------------------------------------
;; 2 — and what it CANNOT see is refused structurally, ahead of it
;; ---------------------------------------------------------------------------

(deftest a-deferring-head-in-a-fallback-is-refused-at-the-declaration
  (fresh!)
  (testing "a bare head, with no vector around it at all — a fallback is a
            hiccup FORM, so the refusal is written against the form and
            not against head position"
    (is (= :rf.error/fresco-host-fallback-boundary-head
           (error-id #(host-with-fallback "fb/bare" fallback-view)))))
  (testing "and a head inside a seq child, because \"inert markup\" is a
            claim about the whole form"
    (is (= :rf.error/fresco-host-fallback-boundary-head
           (error-id #(host-with-fallback
                        "fb/seq"
                        [:ul (for [i (range 2)]
                               [:li {:key i} [fallback-view {}]])]))))))

(deftest the-refusal-names-the-host-the-head-and-the-position
  (fresh!)
  (testing "a message that says only \"a boundary head\" leaves an author
            grepping a fallback they wrote three screens ago. All three
            facts are in the ex-data"
    (let [data (error-data #(host-with-fallback
                              "fb/named"
                              [:div.skeleton [:span [fallback-view {}]]]))]
      (is (= {:rf.error/id :rf.error/fresco-host-fallback-boundary-head
              :host        "fb/named"
              :head        "re-frame.fresco.fallback-contents-cljs-test/fallback-view"
              :kind        "defview"
              :position    [1 1 0]}
             (select-keys data [:rf.error/id :host :head :kind :position])))))
  (testing "the `defhost` case names its own door"
    (let [data (error-data #(host-with-fallback "fb/named-host" [inner-host {}]))]
      (is (= {:kind     "defhost"
              :head     "re-frame.fresco.fallback-contents-cljs-test/inner-host"
              :position [0]}
             (select-keys data [:kind :head :position]))))))

;; ---------------------------------------------------------------------------
;; 3 — a legal fallback still mints and renders
;;
;; A refusal is only as good as what it leaves alone: tag, props, nested
;; vector, string and seq child take every branch of the walk.
;; ---------------------------------------------------------------------------

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
