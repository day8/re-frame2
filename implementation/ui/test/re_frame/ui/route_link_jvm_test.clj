(ns re-frame.ui.route-link-jvm-test
  "JVM/SSR side of the compiled `ui/route-link` defview (rf2-vxgfnd.95.5).

  route-link is an ORDINARY compiled `defview` over the routing-owned
  substrate-neutral link seam (`:routing/link-model` / `:routing/activate-link!`),
  NOT a compiler intrinsic and NOT a `re-frame.ui → routing` static dependency.
  These tests pin the JVM structural render (the SSR shell): a handler-free
  path-form `<a href>` with children + passthrough attrs (no raw host event
  enters the server tree), arbitrary attribute passthrough, the ordinary
  view-pipeline compilation, and the fail-loud `:rf.error/routing-artefact-missing`
  contract when the seam is unbound.

  routing rides the TEST classpath here (deps.edn :test alias) so its late-bind
  hooks publish; production `re-frame.ui` never requires it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]
            ;; Loading routing publishes :routing/link-model + friends at ns-load.
            [re-frame.routing]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (frames/reset-installed-plans!)
    (rf/reg-route :route/home {} "/home")
    (rf/reg-route :route/user {} "/users/:id")
    (t)
    (frames/reset-installed-plans!)))

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; A wrapper view PROVES the call site is an ordinary internal-view component —
;; no route-link intrinsic exists at the head position, route-link embeds like
;; any other defview.
(defview nav
  [{:keys [label]}]
  [:nav [ui/route-link {:to :route/home :class "nav-link"} label]])

;; ---------------------------------------------------------------------------
;; Ordinary view pipeline (acceptance: compiles through the ordinary pipeline,
;; no intrinsic)
;; ---------------------------------------------------------------------------

(deftest route-link-is-an-ordinary-registered-defview
  (testing "route-link is a normal compiled view Var (Q5 discrimination meta), not an intrinsic"
    (is (true? (:rf.ui/view (meta #'ui/route-link)))
        "the public var carries the defview view meta — it went through the ordinary defview macro")
    (is (= :re-frame.ui/route-link (:rf.ui/view-id (meta #'ui/route-link)))
        "default id derivation, like every other defview")))

(deftest route-link-embeds-as-an-ordinary-component-child
  (testing "a parent view embeds [ui/route-link ...] with no special-casing"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f (uit/render [nav {:label "Home"}]))
          a    (some #(when (= :a (:tag %)) %) (tree-seq map? :children tree))]
      (is (some? a) "the route-link rendered a real <a> inside the wrapper")
      (is (= "Home" (uit/text a)))
      (is (= "/home" (:href (uit/attrs a))))
      (is (= "nav-link" (:class (uit/attrs a)))
          "passthrough attr from inside a wrapper view"))))

;; ---------------------------------------------------------------------------
;; The SSR shell — path-form href, children, passthrough, NO handler
;; ---------------------------------------------------------------------------

(deftest jvm-renders-a-handler-free-path-form-anchor
  (testing "the JVM/SSR tree is a real <a> with the path-form href and children, no on-click"
    (let [f     (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree  (rf/with-frame f (uit/render [ui/route-link {:to :route/home} "Home"]))
          a     (some #(when (= :a (:tag %)) %) (tree-seq map? :children tree))]
      (is (= :a (:tag a)) "renders a real anchor element")
      (is (= "/home" (:href (uit/attrs a))) "path-form (identity encode) href on the SSR shell")
      (is (= "Home" (uit/text a)) "children pass through")
      (is (not (contains? (uit/attrs a) :on-click))
          "no on-click — the SSR shell is handler-free; the raw host event never enters the server tree")
      (is (nil? (:events a)) "no event sites on the JVM node"))))

(deftest jvm-encodes-route-params
  (testing "path params synthesise into the path-form href"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render [ui/route-link {:to :route/user :params {:id "42"}} "User"]))]
      (is (= "/users/42" (:href (uit/attrs (some #(when (= :a (:tag %)) %) (tree-seq map? :children tree)))))))))

(deftest jvm-forwards-arbitrary-passthrough-attrs
  (testing "class / aria / data / title / target / download reach the anchor (mechanical passthrough — MIG-32)"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render [ui/route-link {:to :route/home
                                             :class "btn"
                                             :title "Go home"
                                             :aria-label "Home"
                                             :data-testid "home-link"
                                             :target "_blank"} "Home"]))
          at   (uit/attrs (some #(when (= :a (:tag %)) %) (tree-seq map? :children tree)))]
      (is (= "/home" (:href at)))
      (is (= "btn" (:class at)))
      (is (= "Go home" (:title at)))
      (is (= "Home" (:aria-label at)))
      (is (= "home-link" (:data-testid at)))
      (is (= "_blank" (:target at))
          "native-handling attr renders on the anchor so the browser owns the click"))))

(deftest jvm-strips-prefetch-and-installs-no-intent-handler
  (testing ":prefetch is a routing control key, not markup — prefetch=\"intent\" is not HTML —
            so it never reaches the anchor; and the SSR shell installs no intent handler,
            for the same reason it installs no click handler"
    (let [f    (rf/make-frame {:initial-events [[:rf/set-db {}]]})
          tree (rf/with-frame f
                 (uit/render [ui/route-link {:to :route/user
                                             :params {:id "42"}
                                             :prefetch :intent
                                             :class "title"} "User"]))
          a    (some #(when (= :a (:tag %)) %) (tree-seq map? :children tree))
          at   (uit/attrs a)]
      (is (= "/users/42" (:href at)) "the opt does not change where the link points")
      (is (= "title" (:class at)) "the passthrough attrs beside it are untouched")
      (is (not (contains? at :prefetch)) ":prefetch never reaches the anchor")
      (doseq [k [:on-mouse-enter :on-focus :on-touch-start]]
        (is (not (contains? at k))
            (str k " — the server shell observes no intent")))
      (is (nil? (:events a)) "no event sites on the JVM node"))))

;; ---------------------------------------------------------------------------
;; Artefact-missing — the fail-loud contract when routing is absent
;; ---------------------------------------------------------------------------

(defn- with-hook-as-nil [hook-key f]
  (let [original (late-bind/get-fn hook-key)]
    (try (late-bind/set-fn! hook-key nil) (f)
         (finally (late-bind/set-fn! hook-key original)))))

(deftest route-link-fails-loud-when-routing-artefact-missing
  (testing "rendering ui/route-link without the :routing/link-model seam raises :rf.error/routing-artefact-missing"
    (with-hook-as-nil :routing/link-model
      (fn []
        (let [f (rf/make-frame {:initial-events [[:rf/set-db {}]]})]
          (is (= :rf.error/routing-artefact-missing
                 (err-id #(rf/with-frame f (uit/render [ui/route-link {:to :route/home} "Home"]))))
              "the ui view fails loud at the link site, not a half-formed anchor")))))
  (testing "the thrown message names the missing routing artefact + Maven coord"
    (with-hook-as-nil :routing/link-model
      (fn []
        (let [f   (rf/make-frame {:initial-events [[:rf/set-db {}]]})
              msg (try (rf/with-frame f (uit/render [ui/route-link {:to :route/home} "Home"]))
                       ""
                       (catch clojure.lang.ExceptionInfo e (.getMessage e)))]
          (is (str/includes? msg "day8/re-frame2-routing"))
          (is (str/includes? msg "[:rf.error/routing-artefact-missing]")))))))
