(ns re-frame.subscribe-opts-arity-cljs-test
  "Conformance for the PUBLIC `subscribe` opts-map `{:frame}` arity — the
  reconciliation that mirrors the `dispatch*` / EP-0024 frame-opt work.

  The spec teaches the public frame-targeted read as `(subscribe query-v
  {:frame target})` (002-Frames §Frame-targeted dispatch and subscribe;
  008-Testing §Reading machine snapshots; API.md §Dispatch and subscribe).
  The frame-FIRST `(subscribe frame-id query-v)` form is INTERNAL plumbing
  retained for implementation / test / tooling reach (002-Frames §`subscribe*`
  + the frame-first operation arities are internal). Before this reconciliation
  `(subscribe [:x] {:frame f})` misbound the OPTS map as the `query-v` and the
  query-vector as the frame-id.

  This suite pins, end-to-end through the public `rf/subscribe` macro:

    1. the opts form targets the named frame, value-equal to the frame-first
       form on the same frame;
    2. the ambient 1-arity still resolves the carried `with-frame` scope;
    3. the internal frame-first 2-arity still resolves a named frame;
    4. an opts map WITHOUT `:frame` falls to ambient (the `:frame` opt is the
       only frame-targeting key);
    5. malformed (non-map) opts fall to ambient and fail-loud
       `:rf.error/no-frame-context` absent a scope — never a misroute, never a
       host crash binding the opts as a frame.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` AND `clojure -M:test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; `:ambient-frame nil` opts OUT of the fixture's default `:rf/default` ambient
;; scope, so this suite controls the carried scope explicitly: it establishes a
;; scope with `with-frame` where the ambient path is under test, and leaves NO
;; scope where the fail-loud (`:rf.error/no-frame-context`) path is under test.
(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter       plain-atom/adapter
                                                    :ambient-frame nil}))

(defn- setup!
  "Register two frames, a seed event, and a layer-1 sub; seed each frame's
  app-db with a distinct value so a read proves WHICH frame it resolved."
  []
  (rf/reg-frame :soa/t1 {:doc "frame 1"})
  (rf/reg-frame :soa/t2 {:doc "frame 2"})
  (rf/reg-event :soa/seed (fn [{:keys [db]} [_ v]] {:db (assoc db :v v)}))
  (rf/reg-sub   :soa/val  (fn [db _] (:v db)))
  (rf/dispatch-sync [:soa/seed :A] {:frame :soa/t1})
  (rf/dispatch-sync [:soa/seed :B] {:frame :soa/t2}))

(defn- err-id
  "Run `thunk`; return the `:rf.error/id` of the thrown ex-info, or nil. Branches
  on the DISCRIMINATOR, never the message bytes (Spec 009)."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest opts-map-frame-targets-the-named-frame
  (testing "(subscribe query-v {:frame f}) reads f's app-db — and is value-equal
            to the internal frame-first (subscribe f query-v) on the same frame"
    (setup!)
    (is (= :A @(rf/subscribe [:soa/val] {:frame :soa/t1})))
    (is (= :B @(rf/subscribe [:soa/val] {:frame :soa/t2})))
    (is (= @(rf/subscribe :soa/t1 [:soa/val])             ;; frame-first (internal)
           @(rf/subscribe [:soa/val] {:frame :soa/t1}))   ;; opts-map (public)
        "the public opts form is value-equal to the frame-first form")))

(deftest ambient-1-arity-resolves-the-carried-scope
  (testing "(subscribe query-v) still resolves the ambient frame via with-frame"
    (setup!)
    (is (= :A (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val]))))
    (is (= :B (rf/with-frame :soa/t2 @(rf/subscribe [:soa/val]))))))

(deftest internal-frame-first-2-arity-still-works
  (testing "(subscribe frame-id query-v) — the internal frame-first plumbing —
            still resolves the named frame with no scope established"
    (setup!)
    (is (= :A @(rf/subscribe :soa/t1 [:soa/val])))
    (is (= :B @(rf/subscribe :soa/t2 [:soa/val])))))

(deftest opts-without-frame-falls-to-ambient
  (testing "an opts map WITHOUT :frame resolves the AMBIENT frame (the :frame opt
            is the only frame-targeting key); under a scope it reads the carried
            frame, absent a scope it fail-louds :rf.error/no-frame-context"
    (setup!)
    (is (= :A (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val] {:other true})))
        "opts without :frame uses the carried scope — not a misroute")
    (is (= :rf.error/no-frame-context
           (err-id #(deref (rf/subscribe [:soa/val] {:other true}))))
        "absent a scope, the ambient path fail-louds cleanly")))

(deftest malformed-opts-error-cleanly
  (testing "malformed (non-map) opts do NOT misroute — they fall to ambient and,
            absent a scope, raise the clean :rf.error/no-frame-context (never a
            silent read of the wrong frame, never the opts bound as a frame)"
    (setup!)
    (is (= :rf.error/no-frame-context
           (err-id #(deref (rf/subscribe [:soa/val] 42))))
        "a non-map opts under no scope raises the clean ambient error")
    (is (= :A (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val] 42)))
        "under a scope, malformed opts are ignored and the ambient frame is read")))
