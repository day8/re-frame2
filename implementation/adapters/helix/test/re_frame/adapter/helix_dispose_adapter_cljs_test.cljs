(ns re-frame.adapter.helix-dispose-adapter-cljs-test
  "Integration-tier pinning of `dispose-adapter!` semantics on the Helix
  adapter against Spec 006 §Adapter disposal lifecycle.

  Per rf2-c36yr the decision is Option A: every MUST in the spec is
  satisfied. The spine (`re-frame.substrate.spine`) supplies a real
  `dispose-adapter!` covering MUSTs (1)–(3); MUST (4) — subsequent
  calls return `:rf.error/adapter-disposed` — is enforced at the
  delegation layer in `re-frame.substrate.adapter/require-adapter!`
  via the `disposed?` breadcrumb (rf2-6wxys).

  Per rf2-zk7io this test pins the spec-MUST list at the Helix adapter's
  user-facing surface so future refactors (or a spine swap) cannot
  silently drop coverage. Each `testing` block names the MUST it
  pins. Parity sibling: `uix_dispose_adapter_cljs_test.cljs` (rf2-8llol).

  Sibling: `re-frame.substrate.spine-dispose-cljs-test` covers the
  spine factory in isolation; this file covers it through the Helix
  adapter wiring.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.core :as rf]
            [re-frame.disposable :as rf-disposable]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- MUST (3) — discard internal caches -----------------------------------

(deftest dispose-clears-hiccup-emitter-cell
  (testing "Spec 006 §Adapter disposal lifecycle MUST (3): dispose-adapter!
            discards internal caches. After set-hiccup-emitter! →
            dispose, the next render-to-string raises
            :rf.error/no-hiccup-emitter-bound — proving the emitter slot
            was cleared by dispose."
    (helix-adapter/set-hiccup-emitter! (fn [_tree _opts] "<x/>"))
    ;; Trigger the adapter's :dispose-adapter! through the adapter map.
    ;; The runtime-fixture re-installs a fresh adapter on :after.
    ((:dispose-adapter! helix-adapter/adapter))
    (let [render-fn (:render-to-string helix-adapter/adapter)
          thrown    (try (render-fn [:div] {}) nil
                         (catch :default e e))]
      (is (some? thrown)
          "render-to-string threw post-dispose")
      (is (= ":rf.error/no-hiccup-emitter-bound" (.-message thrown))
          "the emitter slot was cleared by dispose-adapter!"))))

(deftest dispose-keeps-clear-warned-non-dom-roots-callable
  (testing "Spec 006 §Adapter disposal lifecycle MUST (3): the adapter's
            warn-once cache is one of the internal caches dispose-adapter!
            must discard (it's part of the spine's drain — see
            spine/make-dispose-adapter!). Detailed cache-emptying
            assertions live in spine-dispose-cljs-test; here we pin
            that the adapter-public clear thunk remains a safe idempotent
            no-op after dispose."
    ((:dispose-adapter! helix-adapter/adapter))
    (is (nil? (helix-adapter/clear-warned-non-dom-roots!))
        "clear-warned-non-dom-roots! is idempotent post-dispose")))

;; ---- MUST (4) — subsequent calls return :rf.error/adapter-disposed -------

(deftest post-dispose-delegation-calls-throw-adapter-disposed
  (testing "Spec 006 §Adapter disposal lifecycle MUST (4): after
            dispose-adapter!, subsequent calls into the adapter surface
            (through the public delegation layer) raise
            :rf.error/adapter-disposed. The breadcrumb is owned by
            substrate-adapter (rf2-6wxys); here we confirm the Helix
            adapter participates correctly."
    (substrate-adapter/dispose-adapter!)
    (is (substrate-adapter/adapter-disposed?)
        "after dispose, the disposed? breadcrumb is true")
    (let [thrown (try
                   (substrate-adapter/make-state-container {})
                   nil
                   (catch :default e e))]
      (is (some? thrown)
          "delegation call after dispose threw")
      (is (= ":rf.error/adapter-disposed" (.-message thrown))
          "the throw shape matches MUST (4)"))
    ;; Reinstall so the fixture's :after teardown lands on a clean state.
    (substrate-adapter/install-adapter! helix-adapter/adapter)))

;; ---- MUST (2) — release host-specific resources (active roots) ------------

(deftest dispose-is-idempotent-with-no-tracked-roots
  (testing "Spec 006 §Adapter disposal lifecycle MUST (2): the Helix adapter
            tracks mounted React roots per rf2-9fdkb; dispose-adapter!
            drains the tracking set so a fresh install starts from an
            empty set. Detailed unmount-fan-out coverage lives in
            re-frame.substrate.spine-dispose-cljs-test; here we pin
            adapter-side idempotence (no real roots are mounted in this
            node-runtime test)."
    (is (nil? ((:dispose-adapter! helix-adapter/adapter)))
        "dispose-adapter! returns nil even when no roots are tracked")
    (is (nil? ((:dispose-adapter! helix-adapter/adapter)))
        "second dispose is idempotent — active-roots set was already drained")))

;; ---- MUST (1) — cancel all in-flight reactive subscriptions ---------------
;;
;; rf2-jcjul pinned this MUST at every React-shaped adapter. The spine's
;; `dispose-frame-sub-caches!` is the single implementation behind
;; Reagent / reagent-slim / UIx / Helix dispose paths; pinning the
;; user-facing slot here guards against a future refactor that
;; accidentally drops the walk from the spine factory.

(deftest dispose-clears-sub-caches-across-live-frames
  (testing "Spec 006 §Adapter disposal lifecycle MUST (1) (rf2-jcjul):
            dispose-adapter! cancels in-flight reactive subscriptions by
            walking every live frame's per-frame sub-cache and disposing
            each cached Reaction. The spine derived-value is an
            re-frame-owned IDisposable; the Helix adapter routes
            `:adapter/dispose!` to rf-disposable's protocol fn (lines
            172-173 of re-frame.adapter.helix). Materialise a sub through
            `rf/subscribe`, then drive the adapter's dispose-adapter! and
            assert the underlying IDisposable fired."
    (rf/reg-frame :helix-walk/a {})
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed] {:frame :helix-walk/a})

    (let [r-a (rf/subscribe :helix-walk/a [:n])]
      (is (= 7 @r-a) "precondition: subscription is live and deref-able")

      (let [cache (:sub-cache (frame/frame :helix-walk/a))
            entries-before @cache]
        (is (>= (count entries-before) 1)
            "precondition: sub-cache holds at least the [:n] entry")
        (let [disposed (atom #{})
              reactions (for [[_ entry] entries-before
                              :let [r (:reaction entry)]
                              :when r]
                          r)]
          (doseq [r reactions]
            (rf-disposable/-add-on-dispose r (fn [] (swap! disposed conj r))))

          ((:dispose-adapter! helix-adapter/adapter))

          (doseq [r reactions]
            (is (contains? @disposed r)
                "every cached reaction fired its dispose hook"))
          (is (= {} @cache)
              "the frame's sub-cache atom was reset to {} by the walk"))))))
