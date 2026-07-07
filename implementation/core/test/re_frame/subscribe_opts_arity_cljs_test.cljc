(ns re-frame.subscribe-opts-arity-cljs-test
  "Conformance for the PUBLIC `subscribe` opts-map `{:frame}` arity.

  The spec teaches the ONE public frame-targeted read as `(subscribe query-v
  {:frame target})` (002-Frames §Frame-targeted dispatch and subscribe;
  008-Testing §Reading machine snapshots; API.md §Dispatch and subscribe).
  API-shrink #1 (rf2-csbbwu) DELETED the frame-FIRST `(subscribe frame-id
  query-v)` runtime shape-discrimination entirely — every sig is `[query-v]`
  / `[query-v opts]`, no `vector?` punning on the first arg. Before the
  EP-0024/rf2-bfadc6 reconciliation `(subscribe [:x] {:frame f})` misbound
  the OPTS map as the `query-v` and the query-vector as the frame-id; this
  suite now pins that the DELETED frame-first shape fails loudly rather than
  silently misrouting.

  This suite pins, end-to-end through the public `rf/subscribe` macro:

    1. the opts form targets the named frame;
    2. the ambient 1-arity still resolves the carried `with-frame` scope;
    3. a former frame-first call — `(subscribe frame-id query-v)` — no
       longer resolves the named frame; it fails loudly (never a silent
       misroute to the wrong frame);
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
  (testing "(subscribe query-v {:frame f}) reads f's app-db"
    (setup!)
    (is (= :A @(rf/subscribe [:soa/val] {:frame :soa/t1})))
    (is (= :B @(rf/subscribe [:soa/val] {:frame :soa/t2})))))

(deftest ambient-1-arity-resolves-the-carried-scope
  (testing "(subscribe query-v) still resolves the ambient frame via with-frame"
    (setup!)
    (is (= :A (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val]))))
    (is (= :B (rf/with-frame :soa/t2 @(rf/subscribe [:soa/val]))))))

(deftest former-frame-first-call-no-longer-resolves-a-named-frame
  (testing "(subscribe frame-id query-v) — the DELETED frame-first shape
            (API-shrink #1, rf2-csbbwu) — no longer targets the named frame.
            `frame-id` (a keyword) is now read as `query-v` and `query-v` (a
            vector) as `opts`; `(:frame opts)` on a vector is nil, so it falls
            to the 1-arity ambient path, where `(first query-v)` on the
            keyword throws. It fails LOUDLY rather than silently misrouting
            to the wrong frame or reading a stale value."
    (setup!)
    (is (thrown? #?(:clj Throwable :cljs :default)
                 @(rf/subscribe :soa/t1 [:soa/val]))
        "no ambient scope: throws rather than resolving :soa/t1")
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf/with-frame :soa/t2
                   @(rf/subscribe :soa/t1 [:soa/val])))
        "even under an unrelated ambient scope: throws rather than silently
         reading :soa/t2 (the ambient frame) or :soa/t1 (the intended one)")))

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
