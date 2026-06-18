(ns re-frame.sub-parametric-inputs-test
  "Tests for parametric subscription inputs — the two-function
  `(reg-sub id input-fn computation-fn)` form (rf2-7brl74, EP
  docs/EP/EP-0004-subscription-inputs.md §Test Plan; Spec 006 §Subscription
  input producers).

  The `input-fn` is a PURE function from the outer `query-v` to a vector
  of input query-vectors (data, NOT live reactions). The runtime
  resolves each in the same frame as the outer subscription and passes
  the resolved values (in producer order) — as a VECTOR — to the
  computation fn.

  Coverage:
    - parse + metadata: `:input-kind` discriminator (:db / :static /
      :parametric), `:input-fn` slot
    - `normalize-sub-inputs` grammar: accepts a vector of query-vectors,
      rejects scalar / bare-keyword / map / mixed / reaction / derefable
    - the input-fn receives the full outer query-v
    - vector-of-query-vectors resolves to a vector of input values in
      producer order (single + multi)
    - static `:<-` behaviour unchanged (bare-value single, vector multi)
    - `compute-sub` ↔ `subscribe-once` agree for parametric subs
    - hot-reload invalidates parametric cache entries (input-fn re-runs)
    - disposal releases realized upstream subscriptions
    - the realized-inputs cache shape (`:inputs` = realized query-vectors)
    - multi-frame: every realized input resolves in the OUTER frame
    - the 3 error ids fire loudly (reg-sub-bad-args /
      sub-input-fn-exception / sub-input-fn-bad-return)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.trace :as trace]
            ;; load the tooling sibling so the late-bind hooks behind the
            ;; public listener API resolve (rf2-qwm0a).
            [re-frame.trace.tooling]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`,
  ;; and ambient subscribe / dispatch now require a carried frame stamp.
  ;; These parametric-input tests run against a single conventional app
  ;; frame, so register `:rf/default` explicitly and pin it as the
  ;; established scope for the whole body via `with-frame`.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(defn- capture-errors!
  "Register a one-shot trace listener that collects every emitted error
  event whose `:operation` is `error-kw` into the returned atom. Returns
  `[errs-atom unregister-fn]`."
  [error-kw]
  (let [errs (atom [])
        k    (keyword "rf2-7brl74" (str (name error-kw) "-" (gensym)))]
    (rf/register-listener! :trace k
                           (fn [ev]
                             (when (= error-kw (:operation ev))
                               (swap! errs conj ev))))
    [errs #(rf/unregister-listener! :trace k)]))

(use-fixtures :each reset-runtime)

(defn- sub-meta [id] (registrar/lookup :sub id))

(defn- entry [frame-id query-v]
  (get-in @(:sub-cache (frame/frame frame-id)) [query-v]))

(defn- cache-keys [frame-id]
  (set (keys @(:sub-cache (frame/frame frame-id)))))

;; ---- parse + metadata: :input-kind discriminator -------------------------

(deftest layer-1-registers-input-kind-db
  (testing "a layer-1 app-db reader registers :input-kind :db, no :input-fn"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [m (sub-meta :n)]
      (is (= :db (:input-kind m)))
      (is (= [] (:input-signals m)))
      (is (not (contains? m :input-fn))))))

(deftest static-chain-registers-input-kind-static
  (testing "a :<- chain registers :input-kind :static with the literal query-vectors"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :n2 :<- [:n] (fn [n _] (* 2 n)))
    (let [m (sub-meta :n2)]
      (is (= :static (:input-kind m)))
      (is (= [[:n]] (:input-signals m)))
      (is (not (contains? m :input-fn))))))

(deftest two-function-form-registers-input-kind-parametric
  (testing "the two-function form registers :input-kind :parametric + :input-fn"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (let [m (sub-meta :item/title)]
      (is (= :parametric (:input-kind m)))
      (is (fn? (:input-fn m)))
      (is (= [] (:input-signals m))
          "parametric subs carry no static :input-signals"))))

;; ---- normalize-sub-inputs grammar ----------------------------------------

(deftest normalize-accepts-vector-of-query-vectors
  (testing "a vector of query vectors normalizes to {:queries [...]}"
    (is (= {:queries [[:a 1] [:b]]} (subs/normalize-sub-inputs [[:a 1] [:b]])))
    (is (= {:queries [[:x :y]]}     (subs/normalize-sub-inputs [[:x :y]]))
        "single input is still a vector OF query vectors")
    (is (= {:queries []}            (subs/normalize-sub-inputs []))
        "empty is unusual but valid")))

(deftest normalize-rejects-scalar-query-vector
  (testing "a scalar query vector [:x :y] is rejected (ambiguous: arg vs two inputs)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs [:x :y])))))

(deftest normalize-rejects-bare-keyword
  (testing "a bare keyword is rejected — no shorthand"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs :viewer/current)))))

(deftest normalize-rejects-map
  (testing "a map return is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs {:article [:article/by-id 1]})))))

(deftest normalize-rejects-mixed-vector
  (testing "a vector with a non-query-vector element is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs [[:article/by-id 1] :viewer])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs [[:a] [42 :b]]))
        "a vector whose head is not a keyword is not a query vector")))

(deftest normalize-rejects-reaction-and-derefable
  (testing "a reaction / derefable return is rejected (not a vector)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs (atom [[:a]]))))
    ;; A vector CONTAINING a derefable element is also rejected — an atom
    ;; is not a query vector (not a vector with a keyword head).
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sub-input-fn-bad-return"
          (subs/normalize-sub-inputs [(atom [:a])])))))

;; ---- the input-fn receives the full outer query-v ------------------------

(deftest input-fn-receives-full-outer-query-v
  (testing "the input-fn is called with the complete outer query vector"
    (let [seen (atom nil)]
      (rf/reg-sub :leaf (fn [db [_ id]] (get-in db [:by-id id])))
      (rf/reg-sub :wrap
                  (fn [query-v]
                    (reset! seen query-v)
                    [[:leaf (second query-v)]])
                  (fn [[v] _] v))
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:by-id {:a 1 :b 2}}}))
      (rf/dispatch-sync [:seed])
      (is (= 1 (rf/subscribe-once [:wrap :a])))
      (is (= [:wrap :a] @seen)
          "input-fn saw the full outer query-v, including the arg"))))

;; ---- vector-of-query-vectors resolves to a vector of values --------------

(deftest single-parametric-input-delivers-a-vector
  (testing "a single parametric input is delivered as a one-element VECTOR
            (NOT the bare-value :<- convention) — EP §Single input"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))   ;; destructures [item]
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items {:x {:title "Hello"}}}}))
    (rf/dispatch-sync [:seed])
    (is (= "Hello" (rf/subscribe-once [:item/title :x])))))

(deftest multi-parametric-inputs-resolve-in-producer-order
  (testing "multiple parametric inputs resolve to a vector of values in order"
    (rf/reg-sub :article/by-id (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :comments/for  (fn [db [_ id]] (get-in db [:comments id])))
    (rf/reg-sub :viewer        (fn [db _] (:viewer db)))
    (rf/reg-sub :article/page
                (fn [[_ id]]
                  [[:article/by-id id]
                   [:comments/for id]
                   [:viewer]])
                (fn [[article comments viewer] [_ id]]
                  {:id id :article article :comments comments :viewer viewer}))
    (rf/reg-event :seed
                     (fn [{:keys [db]} _]
                       {:db {:articles {:a1 {:title "T"}}
                        :comments {:a1 ["c1" "c2"]}
                        :viewer   {:name "Mike"}}}))
    (rf/dispatch-sync [:seed])
    (is (= {:id :a1
            :article  {:title "T"}
            :comments ["c1" "c2"]
            :viewer   {:name "Mike"}}
           (rf/subscribe-once [:article/page :a1])))))

(deftest parametric-recomputes-reactively-on-upstream-change
  (testing "a materialized parametric node follows ordinary layer-2+
            semantics — an upstream value change recomputes it"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (rf/reg-event :seed   (fn [{:keys [db]} _]        {:db {:items {:x {:title "v1"}}}}))
    (rf/reg-event :rename (fn [{:keys [db]} [_ t]]   {:db (assoc-in db [:items :x :title] t)}))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:item/title :x])]
      (is (= "v1" @r))
      (rf/dispatch-sync [:rename "v2"])
      (is (= "v2" @r) "the parametric node recomputed on the upstream change")
      (rf/unsubscribe [:item/title :x]))))

;; ---- static :<- behaviour unchanged --------------------------------------

(deftest static-single-input-still-delivers-bare-value
  (testing "static single :<- still delivers the BARE value (unchanged)"
    (rf/reg-sub :n  (fn [db _] (:n db)))
    (rf/reg-sub :n2 :<- [:n] (fn [n _] (* 2 n)))    ;; bare value, not [n]
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/dispatch-sync [:seed])
    (is (= 10 (rf/subscribe-once [:n2])))))

(deftest static-multi-input-still-delivers-vector
  (testing "static multi :<- still delivers a vector (unchanged)"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum :<- [:a] :<- [:b] (fn [[a b] _] (+ a b)))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:a 2 :b 3}}))
    (rf/dispatch-sync [:seed])
    (is (= 5 (rf/subscribe-once [:sum])))))

;; ---- compute-sub ↔ subscribe-once agreement ------------------------------

(deftest compute-sub-and-subscribe-once-agree-for-parametric
  (testing "compute-sub and subscribe-once compute the same value for a parametric sub"
    (rf/reg-sub :article/by-id (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :viewer        (fn [db _] (:viewer db)))
    (rf/reg-sub :article/page
                (fn [[_ id]] [[:article/by-id id] [:viewer]])
                (fn [[article viewer] [_ id]]
                  {:id id :article article :can-edit? (= id (:owns viewer))}))
    (rf/reg-event :seed
                     (fn [{:keys [db]} _]
                       {:db {:articles {:a1 {:title "T"}}
                        :viewer   {:owns :a1}}}))
    (rf/dispatch-sync [:seed])
    (let [db (rf/app-db-value :rf/default)]
      (is (= (rf/subscribe-once [:article/page :a1])
             (rf/compute-sub [:article/page :a1] db))
          "the pure compute-sub path agrees with the reactive subscribe-once path")
      (is (= {:id :a1 :article {:title "T"} :can-edit? true}
             (rf/compute-sub [:article/page :a1] db))))))

(deftest compute-sub-single-parametric-input-delivers-vector
  (testing "compute-sub delivers a single parametric input as a vector too"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (let [db {:items {:x {:title "Hi"}}}]
      (is (= "Hi" (rf/compute-sub [:item/title :x] db))))))

;; ---- the realized-inputs cache shape (bead 3 reads this) ------------------

(deftest realized-inputs-stored-on-cache-entry
  (testing ":inputs on a parametric cache entry holds the REALIZED query-vectors
            for the concrete outer query-v (not containers); key-set is the
            spec-pinned {:reaction :inputs :ref-count}"
    (rf/reg-sub :article/by-id (fn [db [_ id]] (get-in db [:articles id])))
    (rf/reg-sub :comments/for  (fn [db [_ id]] (get-in db [:comments id])))
    (rf/reg-sub :viewer        (fn [db _] (:viewer db)))
    (rf/reg-sub :article/page
                (fn [[_ id]] [[:article/by-id id] [:comments/for id] [:viewer]])
                (fn [[a c v] _] [a c v]))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:articles {} :comments {} :viewer nil}}))
    (rf/dispatch-sync [:seed])
    (rf/subscribe [:article/page :a1])
    (let [e (entry :rf/default [:article/page :a1])]
      (is (= #{:reaction :inputs :ref-count} (set (keys e)))
          "entry key-set is exactly the spec-006 shape")
      (is (= [[:article/by-id :a1] [:comments/for :a1] [:viewer]]
             (:inputs e))
          ":inputs holds the realized parametric query-vectors for THIS entry"))
    (rf/unsubscribe [:article/page :a1])))

(deftest distinct-outer-query-vs-get-distinct-realized-inputs
  (testing "two concrete cache entries realize their own parametric edges"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items {:x {:title "X"} :y {:title "Y"}}}}))
    (rf/dispatch-sync [:seed])
    (rf/subscribe [:item/title :x])
    (rf/subscribe [:item/title :y])
    (is (= [[:item/by-id :x]] (:inputs (entry :rf/default [:item/title :x]))))
    (is (= [[:item/by-id :y]] (:inputs (entry :rf/default [:item/title :y]))))
    (rf/unsubscribe [:item/title :x])
    (rf/unsubscribe [:item/title :y])))

;; ---- disposal releases realized upstreams --------------------------------

(deftest disposal-releases-realized-upstreams
  (testing "disposing a parametric node releases its realized upstream subscriptions"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items {:x {:title "X"}}}}))
    (rf/dispatch-sync [:seed])
    (rf/subscribe [:item/title :x])
    ;; The realized upstream [:item/by-id :x] is now cached + ref-counted.
    (is (contains? (cache-keys :rf/default) [:item/title :x]))
    (is (contains? (cache-keys :rf/default) [:item/by-id :x])
        "the realized parametric input was subscribed")
    (is (= 1 (:ref-count (entry :rf/default [:item/by-id :x]))))
    ;; Drop the parent — disposal must cascade to the realized upstream.
    (rf/unsubscribe [:item/title :x])
    (is (not (contains? (cache-keys :rf/default) [:item/title :x])))
    (is (not (contains? (cache-keys :rf/default) [:item/by-id :x]))
        "the realized upstream was released synchronously on parent disposal")))

;; ---- hot-reload invalidates parametric cache entries ---------------------

(deftest hot-reload-invalidates-parametric-entry
  (testing "re-registering a parametric sub evicts its cache entries — the
            input-fn re-runs on the next subscribe"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items {:x {:title "Orig"}}}}))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:item/title :x])]
      (is (= "Orig" @r))
      (is (contains? (cache-keys :rf/default) [:item/title :x]))
      ;; Re-register with a new computation fn (hot-reload).
      (rf/reg-sub :item/title
                  (fn [[_ id]] [[:item/by-id id]])
                  (fn [[item] _] (str "Title: " (:title item))))
      (is (not (contains? (cache-keys :rf/default) [:item/title :x]))
          "the parametric cache entry was invalidated on re-registration")
      (rf/unsubscribe [:item/title :x]))
    ;; A fresh subscribe rebuilds against the new body.
    (is (= "Title: Orig" (rf/subscribe-once [:item/title :x])))))

(deftest hot-reload-invalidates-downstream-of-realized-upstream
  (testing "re-registering a realized upstream invalidates the parametric
            entry that realized it (transitive dependent closure)"
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:items {:x {:title "X" :name "n"}}}}))
    (rf/dispatch-sync [:seed])
    (rf/subscribe [:item/title :x])
    (is (contains? (cache-keys :rf/default) [:item/title :x]))
    ;; Re-register the upstream :item/by-id — the parametric entry that
    ;; realized [:item/by-id :x] depends on it and must be evicted.
    (rf/reg-sub :item/by-id (fn [db [_ id]] (assoc (get-in db [:items id]) :hot true)))
    (is (not (contains? (cache-keys :rf/default) [:item/title :x]))
        "the parametric entry whose realized input was re-registered is evicted")
    (rf/unsubscribe [:item/title :x])))

;; ---- multi-frame: realized inputs resolve in the OUTER frame --------------

(deftest multi-frame-realized-inputs-resolve-in-outer-frame
  (testing "every realized parametric input resolves in the same (outer)
            frame as the outer subscription — composes with frame-target
            resolution. Two isolated frames hold different state; each
            parametric read resolves its inputs in its OWN frame."
    (rf/reg-frame :frame-a {:doc "tenant a"})
    (rf/reg-frame :frame-b {:doc "tenant b"})
    (rf/reg-sub :item/by-id (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :item/title
                (fn [[_ id]] [[:item/by-id id]])
                (fn [[item] _] (:title item)))
    (rf/reg-event :seed (fn [{:keys [db]} [_ title]] {:db {:items {:x {:title title}}}}))
    (rf/dispatch-sync [:seed "A-title"] {:frame :frame-a})
    (rf/dispatch-sync [:seed "B-title"] {:frame :frame-b})
    ;; Each frame's parametric read resolves [:item/by-id :x] in its OWN
    ;; frame, so the values differ by frame state.
    (is (= "A-title" (rf/subscribe-once :frame-a [:item/title :x])))
    (is (= "B-title" (rf/subscribe-once :frame-b [:item/title :x])))
    ;; The realized upstream is cached IN the outer frame, not :rf/default.
    (rf/subscribe :frame-a [:item/title :x])
    (is (contains? (cache-keys :frame-a) [:item/by-id :x])
        "the realized input is cached in the outer frame")
    (is (not (contains? (cache-keys :rf/default) [:item/by-id :x]))
        "the realized input did NOT leak into :rf/default")
    (rf/unsubscribe :frame-a [:item/title :x])))

;; ---- error contract: the 3 ids fire loudly -------------------------------

(deftest reg-sub-bad-args-rejects-and-emits
  (testing ":rf.error/reg-sub-bad-args — a malformed registration shape is
            rejected (thrown) AND emits a structured dev trace"
    (let [[errs unreg] (capture-errors! :rf.error/reg-sub-bad-args)]
      (try
        ;; Three trailing args (input-fn + 2 fns) — not an accepted shape.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reg-sub-bad-args"
              (rf/reg-sub :bad (fn [_] [[:a]]) (fn [a _] a) (fn [x _] x))))
        ;; A leading :<- with no query-vector.
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reg-sub-bad-args"
              (rf/reg-sub :bad2 :<- (fn [x _] x))))
        (is (seq @errs) "a :rf.error/reg-sub-bad-args trace was emitted")
        (is (some #(= :bad (get-in % [:tags :rf.sub/id])) @errs)
            "the trace carries the offending sub id")
        (finally (unreg))))))

(deftest sub-input-fn-exception-fires-and-recovers
  (testing ":rf.error/sub-input-fn-exception — an input-fn that throws emits
            loudly (compute-sub path) and recovers the sub to nil"
    (let [[errs unreg] (capture-errors! :rf.error/sub-input-fn-exception)]
      (try
        (rf/reg-sub :leaf (fn [db _] (:leaf db)))
        (rf/reg-sub :boom
                    (fn [_] (throw (ex-info "input-fn boom" {})))
                    (fn [[v] _] v))
        ;; compute-sub path.
        (is (nil? (rf/compute-sub [:boom] {:leaf 1}))
            "the sub recovers to nil when its input-fn throws")
        (is (some #(= :compute-sub (get-in % [:tags :where])) @errs)
            "the compute-sub path emitted :rf.error/sub-input-fn-exception")
        ;; reactive path.
        (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:leaf 1}}))
        (rf/dispatch-sync [:seed])
        (is (nil? (rf/subscribe-once [:boom]))
            "the reactive path also recovers to nil")
        (is (some #(= :reactive (get-in % [:tags :where])) @errs)
            "the reactive path emitted :rf.error/sub-input-fn-exception")
        (finally (unreg))))))

(deftest sub-input-fn-bad-return-fires-and-recovers
  (testing ":rf.error/sub-input-fn-bad-return — an input-fn returning a bad
            shape emits loudly (reactive + compute-sub) and recovers to nil;
            it is NEVER silently treated as no inputs"
    (let [[errs unreg] (capture-errors! :rf.error/sub-input-fn-bad-return)]
      (try
        (rf/reg-sub :leaf (fn [db _] (:leaf db)))
        ;; Returns a scalar query vector — the classic ambiguous shape.
        (rf/reg-sub :bad-shape
                    (fn [_] [:leaf :extra])     ;; scalar — rejected
                    (fn [[v] _] v))
        ;; compute-sub path.
        (is (nil? (rf/compute-sub [:bad-shape] {:leaf 1})))
        ;; reactive path.
        (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:leaf 1}}))
        (rf/dispatch-sync [:seed])
        (is (nil? (rf/subscribe-once [:bad-shape])))
        (let [wheres (set (map #(get-in % [:tags :where]) @errs))]
          (is (contains? wheres :compute-sub))
          (is (contains? wheres :reactive)))
        ;; The error carries the outer query-v + sub id.
        (is (some #(= [:bad-shape] (get-in % [:tags :rf.sub/query-v])) @errs))
        (finally (unreg))))))

(deftest bad-return-not-treated-as-no-inputs
  (testing "a bad input return does NOT silently feed the body an empty input
            set — the sub recovers to nil rather than running the body"
    (let [body-ran (atom false)]
      (rf/reg-sub :leaf (fn [db _] (:leaf db)))
      (rf/reg-sub :bad
                  (fn [_] :viewer)                       ;; bare keyword — rejected
                  (fn [_ _] (reset! body-ran true) :ran))
      (is (nil? (rf/compute-sub [:bad] {:leaf 1})))
      (is (false? @body-ran)
          "the computation fn was NOT run with a silently-empty input set"))))

;; ---- handler-detection robustness (rf2-280fmh) ----------------------------
;;
;; The fn-or-Var handler test + the two-trailing-fn parametric-form
;; recognition replaced the original `(= 1 (count remaining))` /
;; bare-`ifn?` parser. Downstream consumers (ssr / xray / pair-mcp) build
;; subs through registration shapes the original parametric feature test
;; did NOT cover — chiefly the meta-map-prefixed `(reg-sub id meta-map
;; computation-fn)` form (ssr/core conformance corpora use exactly this),
;; and Var-valued handlers. These lock that the parser classifies those
;; shapes correctly: a meta-map is consumed as `:meta` (never mistaken for
;; the first fn of a two-fn parametric form), and a Var passes `handler?`.
;; This is the core-level gate that would have caught a real
;; misclassification — the gap that made the parser the plausible (but
;; ultimately not actual) suspect for the cross-artefact regressions.

(deftest meta-map-prefixed-layer-1-classifies-as-db
  (testing "(reg-sub id meta-map computation-fn) — the meta-map is consumed as
            :meta, leaving a single trailing fn → :db (NOT a 2-fn parametric)"
    (rf/reg-sub :m1 {:doc "a layer-1 sub"} (fn [db _] (:n db)))
    (let [m (sub-meta :m1)]
      (is (= :db (:input-kind m)))
      (is (= [] (:input-signals m)))
      (is (not (contains? m :input-fn))
          "a meta-map + single fn is NOT misread as input-fn + computation-fn")
      (is (= "a layer-1 sub" (:doc m)) "the meta-map survived onto the registration"))
    (rf/reg-event :seed-m1 (fn [{:keys [db]} _] {:db {:n 7}}))
    (rf/dispatch-sync [:seed-m1])
    (is (= 7 (rf/subscribe-once [:m1])))))

(deftest meta-map-prefixed-static-chain-classifies-as-static
  (testing "(reg-sub id meta-map :<- [...] computation-fn) — meta-map + :<- chain
            classifies as :static, the chain intact"
    (rf/reg-sub :base (fn [db _] (:n db)))
    (rf/reg-sub :m2 {:doc "doubled"} :<- [:base] (fn [n _] (* 2 n)))
    (let [m (sub-meta :m2)]
      (is (= :static (:input-kind m)))
      (is (= [[:base]] (:input-signals m)))
      (is (not (contains? m :input-fn)))
      (is (= "doubled" (:doc m))))
    (rf/reg-event :seed-m2 (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/dispatch-sync [:seed-m2])
    (is (= 10 (rf/subscribe-once [:m2])))))

(defn- a-var-layer-1-handler [db _] (:v db))

(deftest var-valued-handler-is-accepted
  (testing "a Var (callable IFn, not fn?) is a valid handler — `handler?` is
            fn-or-Var, not bare `fn?`; HoF / requiring-resolve call sites
            register with a Var"
    (rf/reg-sub :vh #'a-var-layer-1-handler)
    (let [m (sub-meta :vh)]
      (is (= :db (:input-kind m)))
      (is (var? (:handler-fn m)) "the Var handler is stored as-is"))
    (rf/reg-event :seed-vh (fn [{:keys [db]} _] {:db {:v 42}}))
    (rf/dispatch-sync [:seed-vh])
    (is (= 42 (rf/subscribe-once [:vh])))))

(deftest meta-map-prefixed-parametric-still-recognised
  (testing "(reg-sub id meta-map input-fn computation-fn) — a meta-map BEFORE a
            genuine two-fn parametric pair still classifies as :parametric"
    (rf/reg-sub :leaf2 (fn [db [_ id]] (get-in db [:by-id id])))
    (rf/reg-sub :p1 {:doc "parametric with meta"}
                (fn [[_ id]] [[:leaf2 id]])
                (fn [[v] _] v))
    (let [m (sub-meta :p1)]
      (is (= :parametric (:input-kind m)))
      (is (fn? (:input-fn m)))
      (is (= "parametric with meta" (:doc m))))
    (rf/reg-event :seed-p1 (fn [{:keys [db]} _] {:db {:by-id {:a 99}}}))
    (rf/dispatch-sync [:seed-p1])
    (is (= 99 (rf/subscribe-once [:p1 :a])))))
