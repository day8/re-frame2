(ns re-frame.subscribe-opts-arity-cljs-test
  "Conformance for the PUBLIC `subscribe` opts-map `{:frame}` arity.

  The spec teaches the ONE public frame-targeted read as `(subscribe query-v
  {:frame target})` (002-Frames §Frame-targeted dispatch and subscribe;
  008-Testing §Reading machine snapshots; API.md §Dispatch and subscribe).
  There is no frame-FIRST `(subscribe frame-id query-v)` shape and no runtime
  shape-discrimination — every sig is `[query-v]` / `[query-v opts]`, no
  `vector?` punning on the first arg. This suite pins that a frame-first call
  fails loudly rather than silently misrouting.

  This suite pins, end-to-end through the public `rf/subscribe` macro:

    1. the opts form targets the named frame;
    2. the ambient 1-arity resolves the carried `with-frame` scope;
    3. a frame-first call — `(subscribe frame-id query-v)` — does not
       resolve the named frame; it fails loudly (never a silent
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
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; `:ambient-frame nil` opts OUT of the fixture's default `:rf/default` ambient
;; scope, so this suite controls the carried scope explicitly: it establishes a
;; scope with `with-frame` where the ambient path is under test, and leaves NO
;; scope where the fail-loud (`:rf.error/no-frame-context`) path is under test.
(use-fixtures :each (rf.test-support/make-reset-runtime-fixture {:adapter       rf.substrate.plain-atom/adapter
                                                    :ambient-frame nil}))

(defn- setup!
  "Register two frames, a seed event, and a layer-1 sub; seed each frame's
  app-db with a distinct value so a read proves WHICH frame it resolved."
  []
  (rf/make-frame {:id :soa/t1 :doc "frame 1"})
  (rf/make-frame {:id :soa/t2 :doc "frame 2"})
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

(deftest ambient-1-arity-no-frame-context-payload-is-fully-attributed
  (testing "the 1-arity builds its `:rf.error/no-frame-context`
            payload LAZILY — the scope reader runs first and the `extra` map is
            constructed only once absence is known. The error a caller sees must
            therefore be identical to the eagerly-built one: the same
            discriminator, the same `:operation`, the same `:where` naming the
            resolving fn, and the same `:event-id` carrying the query's sub-id
            so a frameless subscribe is attributed to the query it held.
            Deferring construction must never degrade the message."
    (setup!)
    (let [data (try @(rf/subscribe [:soa/val]) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e
                      (ex-data e)))]
      (is (some? data) "an ambient subscribe under no scope throws")
      (is (= :rf.error/no-frame-context (:rf.error/id data)))
      (is (= :subscribe (:operation data)))
      (is (= 're-frame.subs/subscribe (:where data))
          ":where still names the resolving fn")
      (is (= :soa/val (:event-id data))
          ":event-id still carries the sub-id off the query the caller held"))))

(deftest opts-map-frame-targets-the-named-frame
  (testing "(subscribe query-v {:frame f}) reads f's app-db"
    (setup!)
    (is (= :A @(rf/subscribe [:soa/val] {:frame :soa/t1})))
    (is (= :B @(rf/subscribe [:soa/val] {:frame :soa/t2})))))

(deftest ambient-1-arity-resolves-the-carried-scope
  (testing "(subscribe query-v) resolves the ambient frame via with-frame"
    (setup!)
    (is (= :A (rf/with-frame :soa/t1 @(rf/subscribe [:soa/val]))))
    (is (= :B (rf/with-frame :soa/t2 @(rf/subscribe [:soa/val]))))))

(deftest former-frame-first-call-no-longer-resolves-a-named-frame
  (testing "(subscribe frame-id query-v) — a frame-first shape — does not
            target the named frame.
            `frame-id` (a keyword) is read as `query-v` and `query-v` (a
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
