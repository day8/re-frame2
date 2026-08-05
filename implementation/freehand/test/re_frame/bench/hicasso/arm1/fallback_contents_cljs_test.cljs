(ns re-frame.bench.hicasso.arm1.fallback-contents-cljs-test
  "WHAT A `defhost` `:ssr` FALLBACK MAY CONTAIN — MEASURED, AND **UNRULED**
  (rf2-nv07k).

  [[re-frame.bench.hicasso.front.codec/mint-host-gate!]] used to state the
  rule the guide teaches: *\"a fallback is inert markup — it is not a body,
  so a subscription or an intent written there is the same loud error it
  would be anywhere outside a boundary.\"* Both named cases hold, and
  [[a-fallback-refuses-what-the-mint-time-walk-can-see]] pins them. The
  CONCLUSION does not: a `defview` head written in a fallback is refused by
  nothing, and it renders a live boundary in the server response.

  ## The rule that is actually enforced is about the WALK, not the content

  The fallback is walked into an element ONCE, at the declaration, outside
  any frame. So what is refused is exactly *what that walk can evaluate*:

  | Written in a fallback | At the declaration | In the server render |
  |---|---|---|
  | an intent vector | refused — `:rf.error/hicasso-intent-outside-boundary` | — |
  | a `sub` call in the form | refused — `:rf.error/hicasso-sub-outside-render` | — |
  | a `defview` head | **mints** | a LIVE boundary, subscription read included |
  | a `defhost` head | **mints** | that host's own gate, and its own fallback |
  | a FRAME-FED `defview` head | **mints** | throws `:rf.error/no-frame-prop` |

  A boundary head is neither an intent nor a subscription: it is a React
  element whose body runs later. The walk never looks inside it, so every
  refusal it carries is deferred past the declaration — which is the one
  thing the mint-time walk exists to prevent. The asymmetry is therefore
  not a narrow rule and not a documented middle ground; it is the ABSENCE
  of a rule, and rf2-nv07k is where it gets one.

  ## Two facts that decide it, and neither was on the record

  1. **The declared placeholder is not a value.**
     [[the-declared-placeholder-is-therefore-not-one]] renders ONE
     declaration in two isolated frames and again after a write, and gets
     three different documents. `mint-host-gate!`'s own justification for
     walking once — *\"a placeholder that differs per site is not a
     placeholder\"* — is falsified by what it permits. The ELEMENT is
     immutable; what it renders is not.

  2. **It does not survive the arm's own other boundary variant.** This arm
     ships two: [[re-frame.bench.hicasso.arm1.runtime/mint-view!]] (context-fed,
     what `defview` mints) and
     [[re-frame.bench.hicasso.arm1.runtime/mint-frame-prop-view!]]
     (rf2-2rtt6.39, which frees a hook slot in a ≤2 budget that is fully
     spent). A frame-fed head reads `intent/*frame*` when its ELEMENT is
     created — which in a fallback is mint time, where the var is `nil` —
     so it bakes `nil` in and throws one render into the server response
     ([[a-frame-fed-boundary-head-fails-inside-the-server-response]]).
     Whether a boundary head in a fallback works at all is therefore a
     property of which mint the head came from, which is not a rule an
     author can hold.

  ## What this namespace is NOT

  It is not a contract. Every row here that is not a refusal is written as
  a record of TODAY, so that whichever way rf2-nv07k is ruled the change is
  visible as an edit here rather than as a silence. `rf2-l0wfx` (P1, open)
  currently has no recovery but the workaround these rows describe — write
  the subtree a second time as the declaration's fallback — so the ruling
  is not this namespace's to take.

  ## The mutation witnesses

  Bind a dispatch around `mint-host-gate!`'s `as-element` call — a fallback
  as a LIVE region, the shape rf2-nv07k's option (a) implies — and
  [[a-fallback-refuses-what-the-mint-time-walk-can-see]] goes red on the
  intent that is no longer refused. Force that function's `placeholder` to
  `nil` and both boundary-head rows go red on markup missing from the
  server HTML. Make `boundary-element` refuse a frame-fed head minted with
  no ambient frame — the repair option (b) implies — and
  [[a-frame-fed-boundary-head-fails-inside-the-server-response]] goes red
  because the throw moved to the declaration. Each was run.

  Runtime: `-cljs-test`, not `-dom-`. Every claim is a `renderToString`, so
  there is nothing here a DOM would add."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview defhost]]))

(def ^:private frame-a ::fallback-contents-a)
(def ^:private frame-b ::fallback-contents-b)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :fb/title (fn [db _] (:title db)))

(rf/reg-event :fb/seed (fn [_ [_ title]] {:db {:title title}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- fresh!
  "Two frames holding different values, because frame isolation is what
  the placeholder rows are read against."
  []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [:fb/seed "ALPHA"]))
  (rf/with-frame frame-b (rf/dispatch-sync [:fb/seed "BRAVO"])))

;; ---------------------------------------------------------------------------
;; The component behind the door, and the things a fallback might contain
;; ---------------------------------------------------------------------------

(def ^:private theme-context
  "A context PROVIDER is the host used throughout, because it is the shape
  that makes the fallback matter (rf2-l0wfx): a transparent wrapper
  contributes no markup of its own, so an unadopted crossing renders the
  fallback and nothing else."
  (react/createContext "unset"))

(defview fallback-view
  "A `defview` head written into a fallback — the whole question."
  [_]
  [:section.fb-view [:h2.sub (rt/sub [:fb/title])]])

(def ^:private frame-fed-view
  "The SAME body, minted through the arm's other boundary variant
  (rf2-2rtt6.39). Minted directly rather than through `defview` because
  `lang.clj`'s macro mints the context-fed one — which is exactly the
  point: the two are indistinguishable in hiccup and disagree here."
  (rt/mint-frame-prop-view!
    "fallback-contents/frame-fed-view"
    (fn frame-fed-view-body [_]
      [:section.fb-frame-fed [:h2.sub (rt/sub [:fb/title])]])))

(defn- inner-component [_props]
  (react/createElement "i" #js {:className "inner"} "INNER"))

(defhost inner-host
  "A `defhost` head written into a fallback — a second deferring head, so
  the hole is not a `defview` fact."
  inner-component
  {:ssr {:fallback [:em.inner-fallback "INNER-FALLBACK"]}})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

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
;; 1 — what the mint-time walk CAN see, it refuses
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
                                          [:div (rt/sub [:fb/title])])))))
  (testing "and hiccup that is not hiccup, which is the property the walk
            was moved to the declaration FOR"
    (is (= :rf.error/hicasso-empty-vector
           (error-id #(host-with-fallback "fb/empty" []))))))

;; ---------------------------------------------------------------------------
;; 2 — what it cannot see, it does not refuse — AND THIS IS UNRULED
;; ---------------------------------------------------------------------------

(deftest a-boundary-head-in-a-fallback-is-refused-by-nothing
  (fresh!)
  (testing "a `defview` head mints — it is neither an intent nor a
            subscription, it is an element whose body runs later, and the
            walk does not look inside it"
    (let [head (host-with-fallback "fb/view" [fallback-view {}])
          html (server-html frame-a [:div.page [head {:value "dark"}]])]
      (is (re-find #"class=\"fb-view\"" html)
          (str "UNRULED (rf2-nv07k): the fallback rendered a LIVE boundary "
               "in the server response: " html))
      (is (re-find #"ALPHA" html)
          (str "and its subscription READ — this is a body, not markup: "
               html))))
  (testing "and so does a `defhost` head, so the hole is about DEFERRAL and
            not about `defview` — any head whose content the walk cannot
            reach crosses the same way"
    (let [head (host-with-fallback "fb/host" [inner-host {}])
          html (server-html frame-a [:div.page [head {:value "dark"}]])]
      (is (re-find #"INNER-FALLBACK" html)
          (str "a gate inside a placeholder, rendering its own placeholder: "
               html)))))

(deftest the-declared-placeholder-is-therefore-not-one
  (fresh!)
  (testing "ONE declaration, walked ONCE into ONE element — and three
            different server documents. `mint-host-gate!` walks once
            because \"a placeholder that differs per site is not a
            placeholder\"; a boundary head makes it differ per site and per
            write, which is the reason the asymmetry is a defect and not a
            feature nobody wrote down"
    (let [head (host-with-fallback "fb/live" [fallback-view {}])
          in-a (server-html frame-a [:div.page [head {:value "dark"}]])
          in-b (server-html frame-b [:div.page [head {:value "dark"}]])]
      (is (re-find #"ALPHA" in-a) (str "frame A's value: " in-a))
      (is (re-find #"BRAVO" in-b)
          (str "the SAME declared placeholder in frame B — frame-correct, "
               "and not a constant: " in-b))
      (rf/with-frame frame-a (rf/dispatch-sync [:fb/seed "ALPHA-TWO"]))
      (let [after (server-html frame-a [:div.page [head {:value "dark"}]])]
        (is (re-find #"ALPHA-TWO" after)
            (str "and a write moved it, with nothing re-declared: " after))))))

;; ---------------------------------------------------------------------------
;; 3 — and it does not survive the arm's other boundary variant
;; ---------------------------------------------------------------------------

(deftest a-frame-fed-boundary-head-fails-inside-the-server-response
  (fresh!)
  (testing "the frame-fed variant reads `intent/*frame*` when its ELEMENT is
            created. Everywhere else that is inside an ancestor body or
            `root-element`; in a fallback it is MINT TIME, where the var is
            nil — so the declaration mints happily"
    (is (some? (host-with-fallback "fb/frame-fed" [frame-fed-view {}]))
        "no refusal at the declaration, which is where the author's stack is"))
  (testing "and then throws one render into the server response — the exact
            failure the mint-time walk exists to prevent"
    (let [head (host-with-fallback "fb/frame-fed-render" [frame-fed-view {}])]
      (is (= :rf.error/no-frame-prop
             (error-id #(server-html frame-a [:div.page [head {:value "dark"}]]))))))
  (testing "while the SAME view inside an ordinary body renders — so this is
            a property of the fallback position, not of the view"
    (let [html (server-html frame-a [:div.page [frame-fed-view {}]])]
      (is (re-find #"ALPHA" html) (str "control: " html)))))
