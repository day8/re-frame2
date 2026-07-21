(ns re-frame.ui.test-guide08-fixture-jvm-test
  "Guide 08 §Tier 1 examples as fixtures (07 §7: every guide example
  compiles and runs as a fixture; an example needing internals explained
  is an API defect). The code block + the prose one-liners lift with the
  2026-07-12 respelling — the intent assertion reads through the
  ui.test/attrs projection, never direct keyword lookup on the node.

  With the S2 reactive core shipped, the 'drive state with real events'
  Tier-1 loop is lifted for real: `toggle-card` reads its cart membership
  through the compiled `(sub …)` path — no prop injection, no manual
  app-db read in the view — so a dispatched write drains, the re-render's
  subscription observes it, and the rendered tree changes. This is the
  dispatch → sub → re-render loop the chapter teaches, on the first-party
  reactive path (the same one the library tests itself with in
  `test_render_jvm_test.clj` `sub-read-against-a-frame`).

  Enrolled here: the Tier-1 code block (`add-button-carries-intent`), the
  intent-through-attrs respelling, the dispatch → sub → re-render loop, a
  seeded-state render, and the sub-override door. Presence's fake-clock
  behaviour is the CLJS host's (`presence_dom_cljs_test`) — the JVM
  structural render has no lifecycle, so the JVM `flush-presence!` no-op is
  gone (rf2-n7jtp.4). The chapter's literal-root fences and its
  Tier-2/Tier-3 examples are NOT enrolled by this file (README +
  guide-fixture-pipeline draft track the exact per-chapter coverage)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :refer [defview sub]]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil}))

(defn- runtime-footprint
  "The live-frame and per-frame sub-cache baseline guarded by the copied recipe."
  []
  (into {}
        (map (fn [frame-id]
               [frame-id
                (if-let [sub-cache (:sub-cache (frame/frame frame-id))]
                  (set (keys @sub-cache))
                  #{})]))
        (frame/frame-ids)))

;; ---------------------------------------------------------------------------
;; Fixture app surface — .cljc-honest (the guide's own Tier-1 ground rule):
;; the sub + events run on the JVM here, so they carry no host-only forms.
;; ---------------------------------------------------------------------------

(def fixture-catalog
  {42 {:id 42 :name "Hat" :price 9.99}})

(defn product [id] (get fixture-catalog id))

(defn- reg-cart!
  "Register the cart dataflow the Tier-1 loop drives: membership as a
  parameterized sub, add/remove as ordinary events. The reset-runtime
  fixture wipes the registry per test, so each test that reads the sub
  re-registers first."
  []
  (rf/reg-sub :cart/contains?
    (fn [db [_ id]] (contains? (:cart db) id)))
  (rf/reg-event :cart/add
    (fn [{:keys [db]} [_ id]] {:db (update db :cart (fnil conj #{}) id)}))
  (rf/reg-event :cart/remove
    (fn [{:keys [db]} [_ id]] {:db (update db :cart disj id)})))

(defview product-card
  [{:keys [product]}]
  [:button {:on-click [:cart/add (:id product)]} "Add to cart"])

(defview toggle-card
  "The 'assert the button now reads Remove' card — membership is read
  through the compiled `sub` path (03 §3), so a dispatched write to the
  cart re-renders this button's text and intent. No prop injection."
  [{:keys [product]}]
  (if (sub [:cart/contains? (:id product)])
    [:button {:on-click [:cart/remove (:id product)]} "Remove"]
    [:button {:on-click [:cart/add (:id product)]} "Add to cart"]))

;; ---------------------------------------------------------------------------
;; The guide-09 Tier-1 code block, verbatim shape
;; ---------------------------------------------------------------------------

(deftest add-button-carries-intent
  (let [baseline (runtime-footprint)]
    (rf/with-new-frame
      [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{} :catalog fixture-catalog}]]})]
      (let [tree (uit/render [product-card {:product (product 42)}])
            button #(some (fn [n] (when (= :button (:tag n)) n))
                          (tree-seq map? :children %))]
        (is (= [:cart/add 42] (-> tree button uit/attrs :on-click)))
        (is (= "Add to cart"  (-> tree button uit/text)))))
    (is (= baseline (runtime-footprint))
        "the copied with-new-frame form restores the live-frame/cache baseline")))

;; "Assert structure and intent, not pixels: … because handlers are event
;; vectors, 'what does this button do' is an equality check."
(deftest intent-assertion-respelled-through-attrs
  (let [tree (uit/render [product-card {:product (product 42)}])]
    (is (= [:cart/add 42]
           (:on-click (uit/attrs (some #(when (= :button (:tag %)) %)
                                       (tree-seq map? :children tree)))))
        "the tree-contract respelling — same read, prefix form")))

;; "Drive state with real events: (rf/dispatch-sync [:cart/add 42] {:frame frame}),
;; re-render, assert the button now reads 'Remove'. The whole loop, sub reads
;; included, runs on main today."
(deftest drive-state-through-the-real-sub
  (reg-cart!)
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{} :catalog fixture-catalog}]]})]
    (let [button #(some (fn [n] (when (= :button (:tag n)) n))
                        (tree-seq map? :children %))]
      ;; before: the sub reads an empty cart → the button offers Add
      (is (= "Add to cart"
             (-> (uit/render [toggle-card {:product (product 42)}]) button uit/text))
          "the compiled (sub …) reads an empty cart on the first render")
      (rf/dispatch-sync [:cart/add 42] {:frame frame})
      ;; after: the dispatched write has drained; the re-render's subscription
      ;; observes the new membership and the rendered tree changes.
      (let [tree (uit/render [toggle-card {:product (product 42)}])]
        (is (= "Remove" (-> tree button uit/text))
            "dispatch → write drain → (sub …) read → the re-render reads Remove")
        (is (= [:cart/remove 42]
               (-> tree button uit/attrs :on-click))
            "…and the changed tree carries the new intent")))))

;; "Loading and error states are just app-db values you install." — seed the
;; membership directly (no dispatch); the sub-reading view renders against it.
(deftest seeded-membership-renders-without-a-dispatch
  (reg-cart!)
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{42} :catalog fixture-catalog}]]})]
    (let [tree (uit/render [toggle-card {:product (product 42)}])]
      (is (= "Remove" (uit/text (some #(when (= :button (:tag %)) %)
                                      (tree-seq map? :children tree))))
          "install a state, render against it — the (sub …) reads the seeded
           cart with no mocking layer and no dispatch"))))

;; "Stubbing a sub is the explicit option:
;;  (ui.test/render [view] {:sub-overrides {[:cart/locked?] true}})."
(deftest stubbing-a-sub-is-the-explicit-option
  (reg-cart!)
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{} :catalog fixture-catalog}]]})]
    (let [tree (uit/render [toggle-card {:product (product 42)}]
                           {:sub-overrides {[:cart/contains? 42] true}})]
      (is (= "Remove" (uit/text (some #(when (= :button (:tag %)) %)
                                      (tree-seq map? :children tree))))
          "the override door pins the membership read — the button reads Remove
           over an empty real cart, the read never touching app-db"))))

;; §Presence transitions — the JVM structural host has no lifecycle, so the
;; JVM `flush-presence!` no-op is gone (rf2-n7jtp.4: false portability — a public
;; no-op is not parity). The fake-clock BEHAVIOUR (retention, :timeout-ms removal,
;; exactly-once cleanup) is the CLJS host's and is proven in `presence_dom_cljs_test`.

(deftest caller-owned-frame-releases-live-frame-and-sub-cache-after-throw
  (reg-cart!)
  (let [baseline (runtime-footprint)
        failure  (try
                   (rf/with-new-frame
                     [owned (rf/make-frame
                              {:initial-events [[:rf/set-db {:cart #{42}}]]})]
                     ;; Deliberately retain a subscription pin. destroy-frame! must
                     ;; release both this cache entry and the anonymous frame.
                     (rf/subscribe [:cart/contains? 42] {:frame owned})
                     (let [during (runtime-footprint)]
                       (is (not= baseline during) "the owned frame is live in the body")
                       (is (some #(contains? % [:cart/contains? 42]) (vals during))
                           "the body materialised a real per-frame sub-cache entry"))
                     (throw (ex-info "guide body failed" {:kind ::guide-body})))
                   (catch Exception error
                     (:kind (ex-data error))))]
    (is (= ::guide-body failure) "the body's failure remains primary")
    (is (= baseline (runtime-footprint))
        "throw cleanup restores both live-frame and subscription-cache state")))
