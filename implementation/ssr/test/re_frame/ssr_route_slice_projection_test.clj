(ns re-frame.ssr-route-slice-projection-test
  "rf2-4xut98 — the SSR hydration `:rf/runtime-db` payload must NOT ship a
  route's classified `:query` / `:params` RAW.

  EP-0025 §Subsystem matrix (`reg-route` row) + Spec 012 §Route data
  classification + Spec 015 §SSR: a route declares its sensitive / large
  `:query` / `:params` slots PROJECTION-RELATIVE
  (`(rf/reg-route :route/oauth-callback {:sensitive [[:query :token]]} …)`).
  At route activation those declarations are RE-ROOTED under
  `[:rf.runtime/routing :current …]` and lowered into the per-frame elision
  registry (`re-frame.routing.classification/apply-route-classification`,
  `:source :route`). Hydration is a serialized-state egress boundary projected
  under `:rf.egress/ssr-hydration`. The SSR `:rf/runtime-db` payload ships the
  durable `:current` route slice so the client reconstitutes the active route —
  but the prior `project-runtime-db` copied that slice via a bare `select-keys`,
  so a route whose `:query` / `:params` the frame classifies sensitive / large
  shipped that value RAW.

  This is the IDENTICAL leak class machines fixed under rf2-jm2u63 (durable
  snapshots shipped `:data` raw before `:machines/project-ssr-runtime-db`) and
  app-db fixed under rf2-bt9kct (`project-app-db-egress`). The fix runs the
  allowlisted routing slice through `project-routing-egress` (the
  `:rf.egress/ssr-hydration` profile, seeded at the offset
  `:path [:rf.runtime/routing]` so the registry's absolute re-rooted route paths
  match), mirroring the machines hook.

  This pins the fix end-to-end on the ACTUAL SSR projection path
  (`re-frame.ssr.payload-policy/project-runtime-db` →
  `re-frame.ssr.payload-policy/build-payload`), driving a genuine route
  `:sensitive` / `:large` declaration through the route classification lowering
  (`re-frame.routing.classification`, `:source :route`) installed into the live
  request frame's elision registry. The existing
  `payload_policy_cljs_test/project-runtime-db-ships-durable-omits-transient`
  uses a NON-sensitive sample route, so the leak was invisible there — this is
  the sensitive-route regression that gap called for (rf2-ugoxyv)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            ;; Loading routing publishes the route classification machinery
            ;; and the routing fxs; the reset fixture reloads it.
            [re-frame.routing]
            [re-frame.routing.classification :as route-class]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.ssr.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---- the route under test -------------------------------------------------
;;
;; An OAuth-callback-shaped route: the `:query :token` carries a live secret
;; (classify SENSITIVE → redact), the `:params :payload` carries a large blob
;; (classify LARGE → elide to the size marker), and `:query :return-to` is a
;; plain navigation breadcrumb (classified by nothing → rides verbatim). The
;; declaration is PROJECTION-RELATIVE to the route's `{:query … :params …}`
;; current-state projection, exactly as Spec 012 documents.

(def ^:private route-id :route/oauth-callback)

(def ^:private route-classification
  {:sensitive [[:query :token]]
   :large     [[:params :payload]]})

(defn- install-route-classification!
  "Lower `route-id`'s projection-relative `:sensitive` / `:large` classification
  (`validate+extract` → `apply-route-classification`, the genuine `:source
  :route` lowering re-rooting each path under `[:rf.runtime/routing :current
  …]`) into the LIVE `:rf/default` frame's per-frame elision registry — the same
  registry the `:rf.egress/ssr-hydration` egress walk reads at SSR projection
  time. Mirrors the machines test's commit-plane-effect install, but exercises
  the real route classification path."
  []
  (let [lowered (route-class/apply-route-classification
                  {}
                  (route-class/validate+extract route-id route-classification))
        reg     (get lowered :rf.runtime/elision)]
    ;; Write the lowered route-sourced registry into the live frame's runtime-db
    ;; partition — the canonical durable home of `[:rf.runtime/elision]`.
    (elision/swap-elision-slot! :rf/default (constantly reg))))

(defn- runtime-db-with-secret-route
  "A runtime-db carrying the durable `:current` route slice the SSR hydration
  payload would ship — a live secret token in `:query`, a large blob in
  `:params`, plus a plain `:return-to` breadcrumb and the `:route-id`. The
  transient `:pending-navigation` sibling is included to prove the allowlist
  still strips it (fail-closed)."
  []
  {:rf.runtime/routing
   {:current            {:route-id  route-id
                         :query     {:token     "secret-oauth-token"
                                     :return-to "/dashboard"}
                         :params    {:payload "huge-callback-blob-value"}}
    :pending-navigation {:id "pn-1" :reason :can-leave}}})

;; ---- the leak regression (project-runtime-db) -----------------------------

(deftest sensitive-route-query-redacted-in-hydration-projection
  (testing "a route-declared sensitive :query path is redacted to :rf/redacted
            in the SSR :rf/runtime-db projection; the large :params path elides;
            the plain :query sibling rides verbatim; the transient
            :pending-navigation is stripped"
    (install-route-classification!)
    (let [slice   (payload-policy/project-runtime-db (runtime-db-with-secret-route))
          current (get-in slice [:rf.runtime/routing :current])]
      (is (= :rf/redacted (get-in current [:query :token]))
          "route-declared sensitive :query :token redacted in the hydration slice")
      (is (contains? (get-in current [:params :payload]) :rf.size/large-elided)
          "route-declared large :params :payload elided to the size marker")
      (is (= "/dashboard" (get-in current [:query :return-to]))
          "the unclassified :query sibling rides the wire verbatim")
      (is (= route-id (:route-id current))
          ":route-id (durable structural fact) rides verbatim")
      (is (not (contains? (:rf.runtime/routing slice) :pending-navigation))
          "transient :pending-navigation is stripped by the durable allowlist")
      (is (not (.contains (pr-str slice) "secret-oauth-token"))
          "no raw token survives anywhere in the projected runtime-db slice")
      (is (not (.contains (pr-str slice) "huge-callback-blob-value"))
          "no raw large value survives in the projected runtime-db slice"))))

(deftest full-hydration-payload-redacts-route-slice
  (testing "the full :rf/hydration-payload's :rf/runtime-db carries the
            redacted/elided route :query / :params, not the raw classified
            values"
    (install-route-classification!)
    (let [rt-slice (payload-policy/project-runtime-db (runtime-db-with-secret-route))
          payload  (payload-policy/build-payload
                     :rf/default {:public/page :callback} "h1"
                     {:version 1 :runtime-db rt-slice})
          current  (get-in payload [:rf/runtime-db :rf.runtime/routing :current])]
      (is (= :rf/redacted (get-in current [:query :token])))
      (is (contains? (get-in current [:params :payload]) :rf.size/large-elided))
      (is (not (.contains (pr-str payload) "secret-oauth-token"))
          "the hydration blob the client receives carries no raw secret")
      (is (not (.contains (pr-str payload) "huge-callback-blob-value"))))))

(deftest unclassified-route-slice-rides-verbatim
  (testing "a route whose frame declares no classification ships its :current
            slice verbatim — the projection is precise, not a blanket scrub"
    ;; No install-route-classification! — the frame carries no route decls.
    (let [rt      {:rf.runtime/routing
                   {:current {:route-id :route/home
                              :query    {:token "still-here"}}}}
          slice   (payload-policy/project-runtime-db rt)
          current (get-in slice [:rf.runtime/routing :current])]
      (is (= "still-here" (get-in current [:query :token]))
          "an unclassified route query rides the hydration wire verbatim")
      (is (= :route/home (:route-id current))))))

;; ---- confirm the route classification really reaches the registry ---------

(deftest route-classification-lowers-absolute-rerooted-paths
  (testing "the route lowering installs ABSOLUTE re-rooted runtime-db paths
            (`[:rf.runtime/routing :current :query :token]`) into the live
            frame's elision registry — the path the SSR egress walk matches"
    (install-route-classification!)
    (let [reg (get (frame/frame-runtime-db-value :rf/default) :rf.runtime/elision)]
      (is (contains? (:sensitive-declarations reg)
                     [:rf.runtime/routing :current :query :token])
          "sensitive :query :token re-rooted to its absolute runtime-db path")
      (is (contains? (:declarations reg)
                     [:rf.runtime/routing :current :params :payload])
          "large :params :payload re-rooted to its absolute runtime-db path"))))
