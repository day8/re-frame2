(ns re-frame.sub-arg-fragmentation-warn-test
  "Per rf2-re5a98: a dev-only cache-fragmentation guardrail on `subscribe`.

  The per-frame sub-cache keys by query-vector identity (`subs.cljc`'s
  `cache-key` = `=`) — correct, the reserved chokepoint. But it exposes the
  standard footgun: an app that re-subscribes the SAME sub-id every render
  while passing a query-vector arg that is value-EQUAL but a FRESH object
  each render (a `{…}` map / collection / record built inline in the render
  body) mints a DISTINCT cache key every render — unbounded cache growth,
  zero reuse, silently. The React-hook `use-subscribe` path defends its
  deps-array identity; this is the read-side analogue for subscribe's cache
  keying.

  This file pins the heuristic's behaviour: it FIRES (once) on the
  fresh-but-equal non-primitive signature, and STAYS SILENT on every
  benign shape — first use, an `identical?` value-stable arg, a
  genuinely-varying `not=` arg, and a primitive arg. The warning rides the
  diagnostic channel as `:rf.warning/sub-arg-cache-fragmentation` (Spec 009
  §Error event catalogue) via `trace/emit-error!`, dev-only
  (`interop/debug-enabled?`-gated, DCE'd in production)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.subs :as subs]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  (frame/ensure-default-frame!)
  ;; Wipe the dev-only fragmentation-guardrail state between cases so a
  ;; sibling test's first-encounter warning cannot silently swallow a
  ;; later same-sub-id warning. The private clear-fn is the same thunk the
  ;; canonical `:adapter/clear-warn-once-caches!` chain fires in the
  ;; standard reset fixture; reaching it via the var keeps this bespoke
  ;; fixture honest.
  (@#'subs/clear-fragmenting-arg-warnings!)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- fragmentation-events [traces]
  (filterv #(= :rf.warning/sub-arg-cache-fragmentation (:operation %)) traces))

;; Build a FRESH-but-equal map each call. A bare `{…}` literal in a loop body
;; is constant-folded by the compiler to a single hoisted object (all
;; iterations would be `identical?` — the GOOD case), which would NOT exercise
;; the fragmentation signature. Constructing through `hash-map` / `into`
;; defeats the fold so each call returns a distinct object that is `=` but not
;; `identical?` to the previous — exactly the inline-render-body footgun.
(defn- fresh-opts []
  (into {} (hash-map :filter :all :page 1)))

;; ---------------------------------------------------------------------------
;; FIRES — the fresh-but-equal non-primitive signature
;; ---------------------------------------------------------------------------

(deftest fresh-equal-map-arg-warns-once
  (testing "re-subscribing the same sub-id with a value-equal but FRESH
            map arg each time emits exactly ONE
            :rf.warning/sub-arg-cache-fragmentation, carrying the
            diagnostic tags (sub-id, query-v, recovery, hint)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:items {:a 1}}}))
    (rf/reg-sub :sub/param (fn [db [_ _opts]] (:items db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::fresh-map)]
      (try
        ;; Three renders, each building a value-EQUAL but distinct map.
        (dotimes [_ 3]
          (rf/subscribe [:sub/param (fresh-opts)]))
        (let [warns (fragmentation-events @acc)]
          (is (= 1 (count warns))
              "exactly one warning despite three fresh-but-equal subscribes")
          (let [[ev] warns
                tags (:tags ev)]
            ;; Emitted via `trace/emit-error!` → `build-event :error`, so the
            ;; envelope op-type is `:error` and the warning category rides
            ;; `:operation` + `[:tags :category]` (mirrors the
            ;; `:rf.warning/sub-input-dispose-exception` precedent).
            (is (= :rf.warning/sub-arg-cache-fragmentation (:operation ev)))
            (is (= :rf.warning/sub-arg-cache-fragmentation (:category tags))
                ":category carries the warning id (build-event :error merge)")
            (is (= :sub/param (:rf.sub/id tags))
                ":rf.sub/id is the fragmenting sub-id")
            (is (= [:sub/param {:filter :all :page 1}] (:rf.sub/query-v tags))
                ":rf.sub/query-v is the offending query-vector (value-equal)")
            (is (string? (:hint tags))
                ":hint carries the human fix sentence")
            ;; `:recovery` is hoisted to the TOP LEVEL by `build-event`.
            (is (= :ignored (:recovery ev))
                ":recovery :ignored — the subscribe succeeds; warning is diagnostic")))
        (finally
          (rf/unregister-listener! :trace ::fresh-map))))))

(deftest fresh-equal-vector-arg-warns
  (testing "a fresh-but-equal VECTOR arg (also a non-primitive) triggers
            the same one-shot warning"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/reg-sub :sub/v (fn [db [_ _path]] (:n db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::fresh-vec)]
      (try
        ;; `(vec (list …))` builds a fresh-but-equal vector each call
        ;; (defeats literal-vector constant-folding).
        (rf/subscribe [:sub/v (vec (list :a :b :c))])
        (rf/subscribe [:sub/v (vec (list :a :b :c))])
        (is (= 1 (count (fragmentation-events @acc)))
            "value-equal fresh vectors fragment the cache — one warning")
        (finally
          (rf/unregister-listener! :trace ::fresh-vec))))))

(deftest distinct-sub-ids-warn-independently
  (testing "the warn-once dedupe is keyed by sub-id: two different
            fragmenting sub-ids each warn once"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1 :b 2}}))
    (rf/reg-sub :sub/one (fn [db [_ _o]] (:a db)))
    (rf/reg-sub :sub/two (fn [db [_ _o]] (:b db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::two-ids)]
      (try
        (rf/subscribe [:sub/one (into {} (hash-map :k 1))])
        (rf/subscribe [:sub/one (into {} (hash-map :k 1))])
        (rf/subscribe [:sub/two (into {} (hash-map :k 1))])
        (rf/subscribe [:sub/two (into {} (hash-map :k 1))])
        (let [warns (fragmentation-events @acc)
              ids   (set (map #(-> % :tags :rf.sub/id) warns))]
          (is (= 2 (count warns)) "one warning per fragmenting sub-id")
          (is (= #{:sub/one :sub/two} ids)))
        (finally
          (rf/unregister-listener! :trace ::two-ids))))))

;; ---------------------------------------------------------------------------
;; STAYS SILENT — every benign shape (false-positive aversion)
;; ---------------------------------------------------------------------------

(deftest first-subscribe-does-not-warn
  (testing "the FIRST subscribe of a sub-id never warns (no prior arg to
            compare against) — even with a non-primitive arg"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1}}))
    (rf/reg-sub :sub/param (fn [db [_ _o]] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::first-only)]
      (try
        (rf/subscribe [:sub/param {:opts true}])
        (is (empty? (fragmentation-events @acc))
            "a single subscribe is never the fragmentation footgun")
        (finally
          (rf/unregister-listener! :trace ::first-only))))))

(deftest value-stable-identical-arg-does-not-warn
  (testing "a HOISTED / value-stable arg (the SAME object passed every
            render — `identical?`) reuses the cache slot and must NOT warn"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1}}))
    (rf/reg-sub :sub/param (fn [db [_ _o]] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc        (collect-traces! ::stable)
          stable-arg {:filter :all :page 1}]   ;; built ONCE, reused
      (try
        (dotimes [_ 3]
          (rf/subscribe [:sub/param stable-arg]))
        (is (empty? (fragmentation-events @acc))
            "an identical?-stable arg is the GOOD case — no warning")
        (finally
          (rf/unregister-listener! :trace ::stable))))))

(deftest genuinely-varying-args-do-not-warn
  (testing "legitimately-DISTINCT args (`not=` to each other) SHOULD
            fragment the cache — that is correct keying, not a footgun —
            so no warning fires"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:items {1 :a 2 :b 3 :c}}}))
    (rf/reg-sub :sub/item (fn [db [_ k]] (get-in db [:items k])))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::varying)]
      (try
        ;; Non-primitive but genuinely-different args every time.
        (rf/subscribe [:sub/item {:id 1}])
        (rf/subscribe [:sub/item {:id 2}])
        (rf/subscribe [:sub/item {:id 3}])
        (is (empty? (fragmentation-events @acc))
            "genuinely-varying args are correct fragmentation, not a footgun")
        (finally
          (rf/unregister-listener! :trace ::varying))))))

(deftest primitive-arg-does-not-warn
  (testing "a fresh-each-time PRIMITIVE arg (keyword / number / string) is
            value-stable enough that a re-mint is not the hazard — even a
            repeated value-equal primitive must NOT warn"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1}}))
    (rf/reg-sub :sub/kw (fn [db [_ _k]] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::primitive)]
      (try
        ;; Keyword, string, and number args — all primitives — repeated.
        (rf/subscribe [:sub/kw :all])
        (rf/subscribe [:sub/kw :all])
        (rf/subscribe [:sub/kw (str "page-" 1)])
        (rf/subscribe [:sub/kw (str "page-" 1)])
        (rf/subscribe [:sub/kw (+ 1 1)])
        (rf/subscribe [:sub/kw (+ 1 1)])
        (is (empty? (fragmentation-events @acc))
            "primitive args are not the fragmentation hazard")
        (finally
          (rf/unregister-listener! :trace ::primitive))))))

(deftest zero-arg-sub-does-not-warn
  (testing "a plain `[:sub-id]` (no arg) can never fragment — the
            guardrail only inspects exactly `[:sub-id arg]`"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1}}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::zero-arg)]
      (try
        (dotimes [_ 3] (rf/subscribe [:sub/a]))
        (is (empty? (fragmentation-events @acc))
            "an arg-less subscription has no cache-key-fragmentation surface")
        (finally
          (rf/unregister-listener! :trace ::zero-arg))))))

;; ---------------------------------------------------------------------------
;; JVM dev-path pin (the prod-elision shape is pinned by the CLJS elision
;; probe `npm run test:elision`; on the JVM `debug-enabled?` is true)
;; ---------------------------------------------------------------------------

(deftest warns-under-jvm-debug-enabled
  (testing "interop/debug-enabled? is true on the JVM test runtime — the
            guardrail's emit lands in the trace stream (the dev path is
            wired). Production CLJS elision is pinned by the elision probe."
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:a 1}}))
    (rf/reg-sub :sub/param (fn [db [_ _o]] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::jvm-pin)]
      (try
        (rf/subscribe [:sub/param (into {} (hash-map :x 1))])
        (rf/subscribe [:sub/param (into {} (hash-map :x 1))])
        (is (seq (fragmentation-events @acc))
            "a warning landed — JVM dev path is wired")
        (finally
          (rf/unregister-listener! :trace ::jvm-pin))))))
