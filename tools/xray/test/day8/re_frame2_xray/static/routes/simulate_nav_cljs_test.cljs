(ns day8.re-frame2-xray.static.routes.simulate-nav-cljs-test
  "View tests for the hermetic Simulate-navigation preview
  (rf2-o5f5f.3).

  ## Hermetic posture

  The Simulate-navigation preview MUST NOT mutate app-db, dispatch
  navigation, or fire fx. Pure preview — params + handler + db slot
  shape. This file pins the contract.

  Pure-data tests for the projection live in
  `routing_helpers_cljs_test.cljc`.

  Every route slice here is one a real `:rf.route/navigate` wrote
  ([[navigated-slice!]]), and the row-expand rows register through the
  real `reg-route` — the registrar and the router are the producers, so
  no row can pin a shape neither of them emits (rf2-y8doi.22)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.routing]
            [day8.re-frame2-xray.panels.routing-helpers :as h]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.routes.panel :as panel]
            [day8.re-frame2-xray.static.routes.row-expand :as row-expand]
            [day8.re-frame2-xray.static.routes.simulate-nav :as simulate-nav]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; into one owner: plain-atom adapter + the default `:all` reset tier,
  ;; which already includes the trace-collector ring reset the old init
  ;; called a SECOND, redundant time.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers -----------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- text-of [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray}))

(def routes
  {:route/cart    {:path "/cart"
                   :on-match [:cart/load]}
   :route/article {:path     "/articles/:slug"
                   :on-match [:article/load]
                   :params   [:map [:slug :string]]}})

(defn- navigated-slice!
  "The route slice ONE real `:rf.route/navigate` writes into `:rf/default`'s
  runtime-db at `[:rf.runtime/routing :current]`. Registers `route-id` for
  real; the reset fixture rolls it back."
  [route-id pattern request]
  (rf/reg-route route-id {} pattern)
  (rf/dispatch-sync [:rf.route/navigate (assoc request :to route-id)]
                    {:frame :rf/default})
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/routing :current]))

(defn- real-routes
  "The registrar's own entries for `route-ids`, exactly as it holds them."
  [& route-ids]
  (select-keys (rf/registrations {:source :store :kind :route}) route-ids))

;; ---- direct view tests (pure render path) ------------------------------

(deftest preview-unknown-route-renders-the-unknown-block
  (testing "unknown route-id → red unknown block"
    (let [tree (simulate-nav/preview routes :route/nope nil)]
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-unknown"))
          "unknown surface rendered"))))

(deftest preview-registered-no-url
  (testing "registered route, no URL → path + on-match + db-slot + slot-shape"
    (let [tree (simulate-nav/preview routes :route/cart nil)]
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-route/cart")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-path")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-on-match")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-db-slot")))
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-slot-shape"))))))

(deftest preview-renders-hermetic-marker
  (testing "the preview surface labels itself hermetic (no dispatch)"
    (let [tree (simulate-nav/preview routes :route/cart nil)]
      (is (re-find #"Hermetic preview" (text-of tree))
          "the preview surface labels itself 'Hermetic preview'"))))

(deftest preview-with-matching-url-shows-matched-marker
  (testing "URL that matches the route's pattern surfaces (matched) in the URL row"
    (let [tree (simulate-nav/preview routes :route/cart "/cart")]
      (is (some? (find-by-testid tree "rf-xray-static-routes-sim-nav-url"))))))

(deftest preview-slot-shape-row-names-the-slice-navigate-writes-rf2-y8doi-22
  (testing "the rendered 'Slot shape' row carries every key a real navigation
            writes, and none it does not"
    (let [slice (navigated-slice! ::article "/simulate-nav-test/articles/:slug"
                                  {:params {:slug "welcome"}})
          tree  (simulate-nav/preview (real-routes ::article) ::article
                                      "/simulate-nav-test/articles/welcome")
          shown (text-of (find-by-testid tree "rf-xray-static-routes-sim-nav-slot-shape"))]
      (is (= ::article (:route-id slice)) "PRECONDITION: the navigation landed")
      (doseq [k (keys slice)]
        (is (re-find (re-pattern (str (pr-str k) " ")) shown)
            (str "the slot shape names " (pr-str k) "; shown: " shown)))
      (is (nil? (re-find #":path " shown))
          "no `:path` — the slice carries none")
      (is (nil? (re-find #"\{:id " shown))
          "no `:id` — the slice's id key is `:route-id`"))))

;; ---- the row expand the preview opens from ------------------------------

(deftest row-expand-reads-what-reg-route-stores-rf2-y8doi-22
  (testing "a route registered through the real `reg-route` shows its capture
            names (the compiled form's `:names`) and an open-in-editor chip
            off the standard `:file` / `:line` coords. The expand used to read
            `:keys` and `:rf.route/registered-at`, neither of which the
            registrar writes, so both sections were dead."
    (setup-xray-frame!)
    (rf/reg-route ::chapter {} "/simulate-nav-test/books/:book/chapters/:chapter")
    (let [routes (real-routes ::chapter)
          meta   (get routes ::chapter)
          row    (first (h/project-routes routes))
          row-id (subs (pr-str ::chapter) 1)]
      (is (seq (:names (:rf.route/compiled meta)))
          "PRECONDITION: the registrar compiled capture names for the pattern")
      (is (string? (:file meta))
          "PRECONDITION: the registration carries a source coord")
      (rf/with-frame :rf/xray
        (let [tree  (row-expand/render identity row {:sim-open? false :routes-map routes})
              keys* (find-by-testid tree (str "rf-xray-static-routes-keys-" row-id))]
          (is (some? keys*) "the Matched keys section renders")
          (is (some? (find-by-testid tree "xray-open-in-editor"))
              "the source-coord chip renders off the registration's coords"))))))

(deftest row-expand-jump-chip-promises-only-the-lens-rf2-y8doi-22
  (testing "the `→ Dynamic` chip flips to the Dynamic Routing lens and does
            not scope it to the row, so its title does not say it does"
    (let [row  (first (h/project-routes {:route/cart (:route/cart routes)}))
          tree (row-expand/render identity row {:sim-open? false :routes-map routes})
          chip (find-by-testid tree "rf-xray-static-routes-jump-runtime-route/cart")]
      (is (some? chip))
      (is (= "Open the Dynamic Routing lens" (:title (second chip)))))))

;; ---- integration through the panel (hermetic posture) ------------------

(deftest panel-sim-nav-does-not-touch-app-db
  (testing "opening + closing the Simulate-navigation preview leaves the
            current route slice untouched"
    (setup-xray-frame!)
    ;; The live slice is one a real navigation wrote (rf2-y8doi.22), and the
    ;; host frame's runtime-db is read directly below as well as through the
    ;; override seam — so an accidental real navigation would show up in the
    ;; place real navigation writes, not only in a slot the preview never
    ;; touches.
    (let [slice    (navigated-slice! ::old "/simulate-nav-test/old/:slug"
                                     {:params {:slug "old"}})
          host-rdb #(get-in (rf.frame/frame-runtime-db-value :rf/default)
                            [:rf.runtime/routing :current])]
      (is (= ::old (:route-id slice)) "PRECONDITION: the navigation landed")
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/set-registered-routes-override-for-test routes]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray/set-current-route-slice-override-for-test slice]
                          {:frame :rf/xray})
        ;; Expand row + toggle preview for :route/cart — the preview targets
        ;; a different route than the current slice, so a real navigation
        ;; would change the slice. The preview MUST NOT.
        (rf/dispatch-sync [:rf.xray.static.routes/toggle-row :route/cart]
                          {:frame :rf/xray})
        (rf/dispatch-sync [:rf.xray.static.routes/toggle-sim-nav :route/cart]
                          {:frame :rf/xray})
        (is (= slice @(rf/subscribe [:rf.xray/current-route-slice]))
            "current slice unchanged after opening the preview")
        (is (= slice (host-rdb))
            "and the host frame's runtime-db route slice is unchanged")
        ;; Toggle closed — still unchanged.
        (rf/dispatch-sync [:rf.xray.static.routes/toggle-sim-nav :route/cart]
                          {:frame :rf/xray})
        (is (= slice @(rf/subscribe [:rf.xray/current-route-slice]))
            "current slice still unchanged after closing the preview")
        (is (= slice (host-rdb))
            "and the host frame's runtime-db still holds the same slice")))))
