(ns re-frame.plain-atom-dispose-cljs-test
  "CLJS coverage for the plain-atom adapter's participation in the
  sub-cache disposal / ref-count contract (rf2-uatcy).

  On the JVM the plain-atom adapter rides `re-frame.interop`'s (the .clj)
  direct `add-on-dispose!` / `dispose!` implementation, so the layer-2+
  input-release path already works (pinned by the JVM
  `re-frame.sub-cache-test`). On CLJS `re-frame.interop` routes those calls
  through the `:adapter/add-on-dispose!` / `:adapter/dispose!` late-bind
  hooks — which the plain-atom adapter did NOT publish, and its derived
  value reified no disposal protocol. The consequence was a monotonic
  leak: a layer-2+ sub's `:<-` input ref-counts never decremented on slot
  evict, pinning the inputs in the cache until `clear-sub-cache!`.

  These tests run a CLJS-plain-atom host (the SSR / headless-on-CLJS
  shape) and pin the symmetric input-release contract per Spec 006
  §Reference counting and disposal. ns ends in -cljs-test so shadow-cljs's
  :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.subs :as subs]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; A single `use-fixtures :each` (cljs.test REPLACES on repeat calls, it
;; does not compose) that (a) resets the runtime + installs plain-atom and
;; (b) restores the default grace-period afterwards — the tests set
;; grace-period 0 for synchronous disposal, and a sibling suite sharing the
;; JS heap must not inherit that.
(def ^:private reset-runtime
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(use-fixtures :each
  (fn [test-fn]
    (reset-runtime
      (fn []
        (try (test-fn)
             (finally (subs-cache/configure! {:grace-period-ms 50})))))))

(defn- cache-keys []
  (set (keys @(:sub-cache (frame/frame :rf/default)))))

(defn- entry-ref-count [query-v]
  (get-in @(:sub-cache (frame/frame :rf/default)) [query-v :ref-count]))

(deftest layer-2-disposal-decrements-input-ref-counts-on-cljs-plain-atom
  (testing "rf2-uatcy — disposing a layer-2 sub on the CLJS-plain-atom
            adapter decrements ref-counts on every :<- input and cascades
            their disposal, mirroring the JVM contract"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])

    ;; Subscribe to the parent layer-2 sub — recursively subscribes both
    ;; inputs, bumping each to ref-count 1. Use the explicit-frame
    ;; `subs/subscribe` so the test drives the reactive cache path
    ;; directly (the macro `rf/subscribe` resolves a render-time frame
    ;; context that headless node tests do not establish).
    (let [r (subs/subscribe :rf/default [:sum])]
      (is (= 5 @r))
      (is (= 1 (entry-ref-count [:sum])) "parent ref-count = 1")
      (is (= 1 (entry-ref-count [:a])) "input :a ref-count = 1 after layer-2 build")
      (is (= 1 (entry-ref-count [:b])) "input :b ref-count = 1 after layer-2 build"))

    ;; Dispose the parent (sole subscriber drops → grace=0 sync dispose).
    ;; Pre-fix the input-release callback never registered (no published
    ;; :adapter/dispose! hook, no IDisposable on the derived value) so
    ;; :a / :b leaked here.
    (subs/unsubscribe :rf/default [:sum])

    (is (not (contains? (cache-keys) [:sum])) "parent disposed")
    (is (not (contains? (cache-keys) [:a]))
        "input :a disposed via cascade (ref-count → 0) — no leak")
    (is (not (contains? (cache-keys) [:b]))
        "input :b disposed via cascade (ref-count → 0) — no leak")))

(deftest layer-2-disposal-respects-shared-inputs-on-cljs-plain-atom
  (testing "rf2-uatcy — a shared input is decremented by exactly one when
            one of its layer-2 holders disposes; it survives while another
            holder remains"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3 :c 4}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    (rf/reg-sub :ab :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/reg-sub :ac :<- [:a] :<- [:c] (fn [[a c] _] (+ a c)))
    (rf/dispatch-sync [:init])

    (subs/subscribe :rf/default [:ab])
    (subs/subscribe :rf/default [:ac])
    (is (= 2 (entry-ref-count [:a])) "shared input :a has ref-count 2")
    (is (= 1 (entry-ref-count [:b])))
    (is (= 1 (entry-ref-count [:c])))

    (subs/unsubscribe :rf/default [:ab])
    (is (not (contains? (cache-keys) [:ab])) ":ab disposed")
    (is (contains? (cache-keys) [:a]) ":a survives — still referenced by :ac")
    (is (= 1 (entry-ref-count [:a]))
        "shared input ref-count dropped by exactly 1 (now 1)")
    (is (not (contains? (cache-keys) [:b]))
        ":b was held only by :ab — disposed via cascade")

    (subs/unsubscribe :rf/default [:ac])
    (is (not (contains? (cache-keys) [:a]))
        ":a finally disposed after the last layer-2 holder dropped")
    (is (not (contains? (cache-keys) [:c]))
        ":c disposed via cascade")))

(deftest layer-3-disposal-cascades-on-cljs-plain-atom
  (testing "rf2-uatcy — disposal cascades recursively through a layer-3
            chain on the CLJS-plain-atom adapter"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2}))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :a*2 :<- [:a]   (fn [a _] (* 2 a)))
    (rf/reg-sub :a*4 :<- [:a*2] (fn [a2 _] (* 2 a2)))
    (rf/dispatch-sync [:init])

    (let [r (subs/subscribe :rf/default [:a*4])]
      (is (= 8 @r))
      (is (= 1 (entry-ref-count [:a*4])))
      (is (= 1 (entry-ref-count [:a*2])))
      (is (= 1 (entry-ref-count [:a]))))

    (subs/unsubscribe :rf/default [:a*4])
    (is (not (contains? (cache-keys) [:a*4])))
    (is (not (contains? (cache-keys) [:a*2])))
    (is (not (contains? (cache-keys) [:a])))))
