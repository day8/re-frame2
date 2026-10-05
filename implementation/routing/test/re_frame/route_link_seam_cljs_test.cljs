(ns re-frame.route-link-seam-cljs-test
  "The substrate-neutral link seam — `rf.routing.link/link-model`, one of the
  two routing-owned late-bound hooks a view artefact's own route-link consumes
  so that artefact reimplements NONE of the routing link law.

  `link-model` (pure) is asserted for href synthesis, dispatch-payload shape,
  and native-anchor detection. The other hook, `activate-link!` (the click
  decision: caller `:on-click` first, modifier / native deferral, dispatch to
  the CAPTURED render frame stamped `:source :router`), is pinned through the
  `:route/link` view in route_link_cljs_test, whose `:on-click` calls it.

  Per Spec 012 §Linking from views."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn rf.routing/reset-counters!}))

;; ---------------------------------------------------------------------------
;; link-model — the pure routing calculation
;; ---------------------------------------------------------------------------

(deftest link-model-synthesises-href-and-payload
  (rf/reg-route :route/cart {} "/cart")
  ;; The plain-route and params + query shapes are pinned by the cross-host
  ;; parity suite (`route_link_ssr_parity_cljs_test.cljc`), which runs on
  ;; this host too.
  ;; The payload is `{:url …}`, so the fragment is pinned
  ;; where it lives — inside the synthesised url — on both surfaces (see
  ;; `plain-left-click-passes-params-query-and-fragment` for the click side).
  (testing "a fragment rides the url the payload carries, as well as the href"
    (let [{:keys [href payload]} (rf.routing.link/link-model {:to :route/cart :fragment "totals"} nil)]
      (is (= "/cart#totals" href))
      (is (= {:url "/cart#totals"} (second payload))))))

(deftest link-model-carries-the-prefetch-pair
  (rf/reg-route :route/cart {} "/cart")
  (rf/reg-route :route/article {:params [:map [:id :string]]
                                :query  [:map [:tab [:enum :summary :details]]]}
                "/articles/:id")
  (testing "a link that opted in carries the minted warm-up
           vector AND the positions it belongs at, so a seam consumer never
           restates routing's position list"
    (let [{:keys [prefetch prefetch-keys]}
          (rf.routing.link/link-model {:to       :route/article
                                       :params   {:id "intro"}
                                       :query    {:tab :summary}
                                       :fragment "notes"
                                       :prefetch :intent}
                                      nil)]
      (is (= [:rf.route/prefetch {:to :route/article :params {:id "intro"}
                                  :query {:tab :summary}}]
             prefetch)
          "the ONE prefetch calculation's output — :fragment excluded, because
           a prefetch is resource-only")
      (is (= rf.routing.link/prefetch-intent-keys prefetch-keys)
          "the positions are routing's own list by identity of value, not a
           copy: a position added there reaches every seam consumer at once")))
  (testing "a passive link carries nil — never a partially-warm one — and the
           positions travel regardless, so a consumer reads one shape"
    (let [{:keys [prefetch prefetch-keys]}
          (rf.routing.link/link-model {:to :route/cart} nil)]
      (is (nil? prefetch) "an ABSENT :prefetch key is the only way to be passive")
      (is (= rf.routing.link/prefetch-intent-keys prefetch-keys))))
  (testing "a PRESENT-but-bad value is refused by the seam itself, so a
           consumer cannot accept a mode routing rejects"
    (doseq [bad [:render true false nil]]
      (is (thrown-with-msg?
            js/Error #"route-link-bad-prefetch"
            (rf.routing.link/link-model {:to :route/cart :prefetch bad} nil))
          (str ":prefetch " (pr-str bad) " is refused at the seam")))))

(deftest link-model-detects-native-anchors
  (rf/reg-route :route/cart {} "/cart")
  (testing "target=_blank / _top / a download name mark the anchor native"
    (is (true? (:native? (rf.routing.link/link-model {:to :route/cart :target "_blank"} nil))))
    (is (true? (:native? (rf.routing.link/link-model {:to :route/cart :target "_top"} nil))))
    (is (true? (:native? (rf.routing.link/link-model {:to :route/cart :download "f.pdf"} nil)))))
  (testing "target=_self / no native attrs stay interceptable"
    (is (false? (:native? (rf.routing.link/link-model {:to :route/cart :target "_self"} nil))))
    (is (false? (:native? (rf.routing.link/link-model {:to :route/cart} nil))))))

