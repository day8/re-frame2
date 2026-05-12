;;;; tests/runtime/multi_frame_test.clj
;;;;
;;;; Babashka-runnable verification of the multi-frame operating-frame
;;;; semantics in `preload/re_frame_pair2/runtime.cljs` — `current-frame` ambiguity
;;;; resolution, the `:ambiguous-frame` refusal path used by
;;;; `pair-dispatch-sync!`, and `subs-sample` frame-threading.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame_pair2/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It depends on the live re-frame2 frame
;;;;   registry (`re-frame.core/frame-ids`, `register-epoch-cb`, ...)
;;;;   so it can't run under bb directly. The real shadow-cljs test
;;;;   build (planned per `docs/TESTING.md` §1) will exercise the
;;;;   `.cljs` source in place under Node — that's the canonical home
;;;;   for these tests once the build is wired up.
;;;;
;;;;   Until then, this file mirrors the resolver, the subscribe
;;;;   threading, and the dispatch-sync refusal path with stub frame
;;;;   APIs and asserts behaviour against the contracts at
;;;;   `SKILL.md:76`, `docs/initial-spec.md:205`, and
;;;;   `docs/capabilities.md:88`. KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs.
;;;;
;;;; Run:    bb tests/runtime/multi_frame_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns multi-frame-test
  (:require [clojure.test :refer [deftest is run-tests testing use-fixtures]]))

;; ---------------------------------------------------------------------------
;; Stub frame registry — stands in for `re-frame.core/frame-ids` and
;; `re-frame.core/subscribe`. The CLJS runtime calls these directly;
;; under bb we model their observable shape so the resolver and the
;; refusal path can be exercised end-to-end.
;; ---------------------------------------------------------------------------

(def frame-registry
  "Mutable set of registered frame-ids. The CLJS runtime reads this
   via `(rf/frame-ids)` — set semantics, no order contract."
  (atom #{}))

(def frame-dbs
  "frame-id -> atom holding the frame's app-db. Stand-in for the real
   container behind `rf/get-frame-db` and the targets of `rf/subscribe`."
  (atom {}))

(defn register-frame!
  ([frame-id] (register-frame! frame-id {}))
  ([frame-id initial-db]
   (swap! frame-registry conj frame-id)
   (swap! frame-dbs assoc frame-id (atom initial-db))
   frame-id))

(defn unregister-frame! [frame-id]
  (swap! frame-registry disj frame-id)
  (swap! frame-dbs dissoc frame-id))

(defn frame-ids [] @frame-registry)

(defn get-frame-db [frame-id]
  (some-> (get @frame-dbs frame-id) deref))

(defn subscribe
  "Mirrors `re-frame.core/subscribe`'s 2-arity form `(subscribe
   frame-id query-v)`. The CLJS runtime calls this in `subs-sample`.
   Returns a deref-able stand-in (an atom whose value derives from
   the frame-db) so `@(subscribe frame-id query-v)` works."
  [frame-id query-v]
  (let [container (get @frame-dbs frame-id)]
    (when container
      ;; Project the query against the frame-db. For the test we
      ;; treat query-v as a path into the db.
      (atom (get-in @container query-v)))))

;; ---------------------------------------------------------------------------
;; Mirror of preload/re_frame_pair2/runtime.cljs `selected-frame`, `select-frame!`,
;; and `current-frame`.
;;
;; KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs lines 60-86. Logic must be
;; identical to the CLJS version: explicit override -> session pin ->
;; sole registered frame -> nil (ambiguous).
;; ---------------------------------------------------------------------------

(def selected-frame (atom nil))

(defn select-frame! [frame-id]
  (reset! selected-frame frame-id)
  {:ok? true :frame frame-id})

(defn current-frame
  ([] (current-frame nil))
  ([override]
   (or override
       @selected-frame
       (let [fids (frame-ids)]
         (when (= 1 (count fids))
           (first fids))))))

;; ---------------------------------------------------------------------------
;; Mirror of `subs-sample` — threads the resolved frame through
;; `subscribe` and refuses on :ambiguous-frame.
;;
;; KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs lines 194-218.
;; ---------------------------------------------------------------------------

(defn subs-sample
  ([query-v] (subs-sample query-v (current-frame)))
  ([query-v frame-id]
   (cond
     (nil? frame-id)
     {:ok? false :reason :ambiguous-frame
      :hint "Multi-frame session with no selected frame — pass `frame-id` or call `select-frame!` first."}

     :else
     (try
       @(subscribe frame-id query-v)
       (catch Throwable e
         {:ok? false :reason :sub-error :message (.getMessage e) :frame frame-id})))))

;; ---------------------------------------------------------------------------
;; Mirror of the `pair-dispatch-sync!` refusal path. We don't model the
;; full dispatch loop — just the entry guard at runtime.cljs:454-456,
;; which is the contract under test (mutating ops refuse on ambiguous
;; frame).
;;
;; KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs lines 443-479.
;; ---------------------------------------------------------------------------

(defn pair-dispatch-sync!
  ([event-v] (pair-dispatch-sync! event-v {}))
  ([event-v opts]
   (let [frame-id (or (:frame opts) (current-frame))]
     (when-not frame-id
       (throw (ex-info "ambiguous frame" {:reason :ambiguous-frame})))
     ;; Real impl runs `dispatch-sync` and walks epoch-history; for the
     ;; test the guard is the contract — return a success shape so the
     ;; non-ambiguous case still asserts something meaningful.
     {:ok? true :event event-v :frame frame-id})))

;; ---------------------------------------------------------------------------
;; Per-test reset. Each test owns its frame-registry state.
;; ---------------------------------------------------------------------------

(defn reset-state! []
  (reset! frame-registry #{})
  (reset! frame-dbs {})
  (reset! selected-frame nil))

(use-fixtures :each (fn [t] (reset-state!) (t) (reset-state!)))

;; ---------------------------------------------------------------------------
;; current-frame resolution
;; ---------------------------------------------------------------------------

(deftest current-frame-no-frames
  (testing "no frames registered, no selection, no override -> nil"
    (is (nil? (current-frame)))))

(deftest current-frame-single-frame-default
  (testing "single :rf/default frame registered -> :rf/default"
    (register-frame! :rf/default {:counter 0})
    (is (= :rf/default (current-frame)))))

(deftest current-frame-single-non-default-frame
  (testing "single non-default frame registered -> that frame"
    (register-frame! :stories {})
    (is (= :stories (current-frame)))))

(deftest current-frame-ambiguous-no-selection
  (testing "multiple frames registered, no selection -> nil (ambiguous)"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (is (nil? (current-frame))
        "must NOT silently fall back to :rf/default just because it's registered")))

(deftest current-frame-ambiguous-three-frames
  (testing "three frames, no selection -> nil"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (register-frame! :sandbox {})
    (is (nil? (current-frame)))))

(deftest current-frame-session-pin-resolves-ambiguity
  (testing "select-frame! resolves ambiguity -> selected frame"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (select-frame! :stories)
    (is (= :stories (current-frame)))))

(deftest current-frame-explicit-override-beats-session-pin
  (testing "explicit override takes precedence over session pin"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (select-frame! :stories)
    (is (= :rf/default (current-frame :rf/default)))))

(deftest current-frame-explicit-override-with-no-frames-registered
  (testing "explicit override returns even if frame isn't registered (resolver is naive)"
    (is (= :ghost (current-frame :ghost)))))

;; ---------------------------------------------------------------------------
;; pair-dispatch-sync! refuses on :ambiguous-frame
;; ---------------------------------------------------------------------------

(deftest pair-dispatch-sync-refuses-on-ambiguous-frame
  (testing "ambiguous multi-frame session: pair-dispatch-sync! raises :ambiguous-frame"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (try
      (pair-dispatch-sync! [:cart/apply-coupon "SPRING25"])
      (is false "expected ex-info :ambiguous-frame to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :ambiguous-frame (:reason (ex-data e))))))))

(deftest pair-dispatch-sync-succeeds-on-single-frame
  (testing "single-frame session: pair-dispatch-sync! succeeds against :rf/default"
    (register-frame! :rf/default {:counter 0})
    (let [result (pair-dispatch-sync! [:counter/inc])]
      (is (:ok? result))
      (is (= :rf/default (:frame result))))))

(deftest pair-dispatch-sync-succeeds-after-select-frame
  (testing "ambiguous session, then select-frame! -> dispatch lands on selected"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (select-frame! :stories)
    (let [result (pair-dispatch-sync! [:demo/click])]
      (is (:ok? result))
      (is (= :stories (:frame result))))))

(deftest pair-dispatch-sync-explicit-frame-opt-wins
  (testing "explicit :frame opt routes the dispatch even in ambiguous session"
    (register-frame! :rf/default {})
    (register-frame! :stories {})
    (let [result (pair-dispatch-sync! [:demo/click] {:frame :stories})]
      (is (:ok? result))
      (is (= :stories (:frame result))))))

;; ---------------------------------------------------------------------------
;; subs-sample threads the operating frame
;; ---------------------------------------------------------------------------

(deftest subs-sample-refuses-on-ambiguous-frame
  (testing "ambiguous session: subs-sample returns :ambiguous-frame instead of silently reading :rf/default"
    (register-frame! :rf/default {:counter 99})
    (register-frame! :stories {:counter 1})
    (let [result (subs-sample [:counter])]
      (is (false? (:ok? result)))
      (is (= :ambiguous-frame (:reason result))))))

(deftest subs-sample-reads-selected-frame
  (testing "select-frame! steers subs-sample to the chosen frame"
    (register-frame! :rf/default {:counter 99})
    (register-frame! :stories {:counter 1})
    (select-frame! :stories)
    ;; Stories has :counter 1, default has :counter 99. Reading the
    ;; right frame is the only way to land on 1.
    (is (= 1 (subs-sample [:counter]))
        "subs-sample must read from the selected frame, not :rf/default")))

(deftest subs-sample-explicit-frame-arg
  (testing "explicit frame-id arg overrides session pin"
    (register-frame! :rf/default {:counter 99})
    (register-frame! :stories {:counter 1})
    (select-frame! :stories)
    (is (= 99 (subs-sample [:counter] :rf/default)))))

(deftest subs-sample-single-frame-session
  (testing "single-frame session: subs-sample reads from that frame"
    (register-frame! :rf/default {:counter 7})
    (is (= 7 (subs-sample [:counter])))))

;; ---------------------------------------------------------------------------
;; Cross-cutting — the contract together
;; ---------------------------------------------------------------------------

(deftest contract-mutating-refuses-reads-thread
  (testing "ambiguous session: mutating refuses; selecting a frame steers reads"
    (register-frame! :rf/default {:greeting "hello"})
    (register-frame! :stories {:greeting "world"})
    ;; Mutating refuses.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ambiguous"
                          (pair-dispatch-sync! [:noop])))
    ;; Read refuses too (no silent default fallback).
    (is (= :ambiguous-frame (:reason (subs-sample [:greeting]))))
    ;; Select a frame; reads now resolve.
    (select-frame! :stories)
    (is (= "world" (subs-sample [:greeting])))
    ;; And mutating succeeds.
    (let [result (pair-dispatch-sync! [:noop])]
      (is (:ok? result))
      (is (= :stories (:frame result))))))

;; ---------------------------------------------------------------------------
;; Run.
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'multi-frame-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
