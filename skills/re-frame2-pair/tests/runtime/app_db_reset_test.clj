;;;; tests/runtime/app_db_reset_test.clj
;;;;
;;;; Babashka-runnable structural verification of `app-db-reset!` from
;;;; `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; Why this test exists:
;;;;
;;;; `app-db-reset!` MUST delegate to the canonical Tool-Pair write
;;;; surface `rf/replace-frame-state!` (Tool-Pair §Pair-tool writes;
;;;; rf2-t3lftq — API-shrink #3 consolidated the former
;;;; `rf/replace-app-db!` into an app-only partial map,
;;;; `{:rf.db/app v}`), so the reset mutates app-db AND appends the
;;;; synthetic epoch that `restore-epoch` depends on. Reaching into
;;;; `(rf/handler-meta :frame frame-id)` for an `:app-db` key instead
;;;; could return `{:ok? true}` without mutating state or recording the
;;;; epoch — this test forbids that shape.
;;;;
;;;; Why a structural test rather than a runtime test:
;;;;
;;;; `preload/re_frame2_pair/runtime.cljs` is CLJS-only — loaded into
;;;; the consumer app via shadow-cljs `:devtools :preloads` — so it
;;;; can't run under bb directly. The semantic contract
;;;; of `rf/replace-frame-state!` (mutates app-db, appends a synthetic
;;;; `:rf.epoch/db-replaced` epoch, schema-validates, drain-checks,
;;;; emits trace, fires listeners) is already covered by the JVM
;;;; tests at
;;;; implementation/epoch/test/re_frame/epoch_test.clj
;;;; replace-frame-state-app-only-replaces-container
;;;; replace-frame-state-app-only-records-undo-epoch (also covers the
;;;; restore-epoch-rewinds-past-injection case)
;;;; replace-frame-state-app-only-emits-trace (`:rf.epoch/db-replaced`)
;;;; replace-frame-state-app-only-fires-listeners
;;;; replace-frame-state-app-only-failure-unknown-frame
;;;; replace-frame-state-app-only-failure-during-drain
;;;; replace-frame-state-app-only-failure-schema-mismatch
;;;;
;;;; What we MUST verify here is that re-frame2-pair's `app-db-reset!` actually
;;;; delegates to that surface — not to some other API that won't
;;;; record the epoch. This file parses `preload/re_frame2_pair/runtime.cljs`,
;;;; locates the `app-db-reset!` defn form, and asserts the structural
;;;; contract:
;;;;
;;;; 1. The body invokes `rf/replace-frame-state!` (the canonical
;;;; Tool-Pair write surface — guarantees app-db mutation +
;;;; synthetic-epoch append per).
;;;; 2. The body does NOT reach into `rf/handler-meta` to grab
;;;; an `:app-db` key (the forbidden no-mutation shape).
;;;; 3. The success branch returns `{:ok? true ...}`, the
;;;; soft-failure branch returns `{:ok? false :reason
;;;; :reset-rejected ...}`.
;;;; 4. The body still tap>s the change so the human sees it
;;;; (existing safety guardrail per docs/capabilities.md:86).
;;;;
;;;; Run: bb tests/runtime/app_db_reset_test.clj
;;;; Exit: 0 = pass, non-zero = fail.

(load-file (str (.getParent (java.io.File. *file*)) "/_support.clj"))

(ns app-db-reset-test
 (:require [clojure.test :refer [deftest is run-tests testing]]
 [runtime-support :as rt]))

;; Shared locate+parse+walk scaffold lives in tests/runtime/_support.clj.
;; Alias the vars the assertions below use.
(def ^:private form-contains? rt/form-contains?)

(def ^:private app-db-reset-form (rt/defn-named 'app-db-reset!))

(defn- calls? [sym form]
 (form-contains?
 (fn [node] (and (seq? node) (= sym (first node))))
 form))

;; ---------------------------------------------------------------------------
;; Structural assertions
;; ---------------------------------------------------------------------------

(deftest app-db-reset-form-is-defined
 (testing "preload/re_frame2_pair/runtime.cljs defines app-db-reset!"
 (is (some? app-db-reset-form)
 "the defn form is present in the source")))

(deftest delegates-to-canonical-tool-pair-surface
 (testing "app-db-reset! delegates to rf/replace-frame-state! — the canonical
 Tool-Pair §Pair-tool writes surface that mutates
 app-db, appends a synthetic :rf.epoch/db-replaced epoch,
 schema-validates, and drain-checks (rf2-t3lftq — API-shrink #3
 consolidated the former rf/replace-app-db! into an app-only partial
 map)"
 (is (calls? 'rf/replace-frame-state! app-db-reset-form)
 "(rf/replace-frame-state! frame-id {:rf.db/app v}) appears in the body")))

(deftest does-not-reach-through-handler-meta
 (testing "app-db-reset! does NOT use the buggy `(rf/handler-meta :frame
 frame-id) :app-db` path that returned {:ok? true} without
 mutating state or recording an epoch — that was 's
 offending pattern"
 (is (not (form-contains?
 (fn [node]
 (and (seq? node)
 (= 'rf/handler-meta (first node))
 (some #{:frame} node)))
 app-db-reset-form))
 "no `(rf/handler-meta :frame ...)` lookup")
 (is (not (form-contains?
 (fn [node]
 (and (seq? node)
 (= 'reset! (first node))
 ;; reset! container — the only reset! the previous
 ;; impl did was on the frame container ref. Any
 ;; reset! at all here would be suspicious.
))
 app-db-reset-form))
 "no `(reset! container ...)` — delegating to rf/replace-frame-state!
 means the container is replaced inside that surface, not here")))

(deftest preserves-tap-log-guardrail
 (testing "the human-visible tap> log stays in place per
 docs/capabilities.md §Safety / guardrails — Previous + next
 + timestamp tap'd so the human sees what the agent changed"
 (is (calls? 'tap> app-db-reset-form)
 "tap> call survives in the body")
 (is (form-contains?
 (fn [node] (= :re-frame2-pair/op node))
 app-db-reset-form)
 ":re-frame2-pair/op tag in the tap> payload")
 (is (form-contains?
 (fn [node] (= :app-db/reset node))
 app-db-reset-form)
 ":app-db/reset op id in the tap> payload")))

(deftest success-shape
 (testing "success branch returns {:ok? true :frame frame-id}"
 (is (form-contains?
 (fn [node]
 (and (map? node)
 (= true (get node :ok?))
 (contains? node :frame)))
 app-db-reset-form)
 "{:ok? true :frame ...} literal in the body")))

(deftest soft-failure-shape
 (testing "soft-failure branch surfaces the rejection rather than
 silently claiming success — {:ok? false :reason :reset-rejected ...}"
 (is (form-contains?
 (fn [node]
 (and (map? node)
 (= false (get node :ok?))
 (= :reset-rejected (get node :reason))))
 app-db-reset-form)
 "{:ok? false :reason :reset-rejected ...} literal in the body")))

(deftest catches-throw
 (testing "the :rf.error/epoch-artefact-missing throw from
 rf/replace-frame-state! (when the day8/re-frame2-epoch artefact
 isn't loaded) is caught and surfaced — the caller sees the
 failure rather than a stack trace"
 (is (calls? 'try app-db-reset-form)
 "(try ...) wraps the delegating call")
 (is (form-contains?
 (fn [node] (and (seq? node) (= 'catch (first node))))
 app-db-reset-form)
 "(catch ...) clause is present")))

;; ---------------------------------------------------------------------------
;; Run.
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'app-db-reset-test)]
 (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
