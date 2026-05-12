;;;; tests/runtime/app_db_reset_test.clj
;;;;
;;;; Babashka-runnable structural verification of `app-db-reset!` from
;;;; `preload/re_frame_pair2/runtime.cljs`.
;;;;
;;;; Why this test exists (rf2-mzn7):
;;;;
;;;;   `app-db-reset!` previously reached into `(rf/handler-meta :frame
;;;;   frame-id)` looking for an `:app-db` key — not the canonical
;;;;   Tool-Pair write surface — and could plausibly return
;;;;   `{:ok? true}` without mutating state or appending the synthetic
;;;;   epoch that `restore-epoch` depends on. The fix delegates to
;;;;   `rf/reset-frame-db!` (Tool-Pair §Pair-tool writes, rf2-zq55).
;;;;
;;;; Why a structural test rather than a runtime test:
;;;;
;;;;   `preload/re_frame_pair2/runtime.cljs` is CLJS-only — loaded into
;;;;   the consumer app via shadow-cljs `:devtools :preloads` — so it
;;;;   can't run under bb directly. The semantic contract
;;;;   of `rf/reset-frame-db!` (mutates app-db, appends a synthetic
;;;;   `:rf.epoch/db-replaced` epoch, schema-validates, drain-checks,
;;;;   emits trace, fires listeners) is already covered by the JVM
;;;;   tests at
;;;;   implementation/epoch/test/re_frame/epoch_test.clj
;;;;     reset-frame-db!-replaces-container
;;;;     reset-frame-db!-records-undo-epoch         (also covers the
;;;;       restore-epoch-rewinds-past-injection case)
;;;;     reset-frame-db!-emits-trace                (`:rf.epoch/db-replaced`)
;;;;     reset-frame-db!-fires-listeners
;;;;     reset-frame-db!-failure-unknown-frame
;;;;     reset-frame-db!-failure-during-drain
;;;;     reset-frame-db!-failure-schema-mismatch
;;;;
;;;;   What we MUST verify here is that pair2's `app-db-reset!` actually
;;;;   delegates to that surface — not to some other API that won't
;;;;   record the epoch. This file parses `preload/re_frame_pair2/runtime.cljs`,
;;;;   locates the `app-db-reset!` defn form, and asserts the structural
;;;;   contract:
;;;;
;;;;     1. The body invokes `rf/reset-frame-db!` (the canonical
;;;;        Tool-Pair write surface — guarantees app-db mutation +
;;;;        synthetic-epoch append per rf2-zq55).
;;;;     2. The body does NOT reach into `rf/handler-meta` to grab
;;;;        an `:app-db` key (the bug we just fixed).
;;;;     3. The success branch returns `{:ok? true ...}`, the
;;;;        soft-failure branch returns `{:ok? false :reason
;;;;        :reset-rejected ...}`.
;;;;     4. The body still tap>s the change so the human sees it
;;;;        (existing safety guardrail per docs/capabilities.md:86).
;;;;
;;;; Run:    bb tests/runtime/app_db_reset_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns app-db-reset-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure.walk :as walk]))

;; ---------------------------------------------------------------------------
;; Locate runtime.cljs relative to this test file. Test runs from the
;; skill root, so the path is preload/re_frame_pair2/runtime.cljs. We try a couple of
;; likely paths to stay robust to where bb is invoked from.
;; ---------------------------------------------------------------------------

(def ^:private runtime-cljs-path
  (some (fn [p] (when (.exists (io/file p)) p))
        ["preload/re_frame_pair2/runtime.cljs"
         "skills/re-frame-pair2/preload/re_frame_pair2/runtime.cljs"
         "../preload/re_frame_pair2/runtime.cljs"]))

(when-not runtime-cljs-path
  (binding [*out* *err*]
    (println "ERROR: cannot locate preload/re_frame_pair2/runtime.cljs from"
             (System/getProperty "user.dir")))
  (System/exit 2))

;; ---------------------------------------------------------------------------
;; Read & parse all top-level forms. Use a CLJS-tolerant reader: the file
;; uses `js/Date.now`, `:default`, etc., which Clojure's reader tolerates
;; as symbols / keywords; we never evaluate the forms.
;; ---------------------------------------------------------------------------

(defn- read-all-forms [^String src]
  (let [pbr (java.io.PushbackReader.
             (java.io.StringReader. src))]
    (loop [acc []]
      (let [form (try (read {:read-cond :allow :features #{:cljs}} pbr)
                      (catch Exception _ ::eof))]
        (if (= ::eof form)
          acc
          (recur (conj acc form)))))))

(def ^:private all-forms
  (read-all-forms (slurp runtime-cljs-path)))

(def ^:private app-db-reset-form
  (some (fn [form]
          (when (and (seq? form)
                     (= 'defn (first form))
                     (= 'app-db-reset! (second form)))
            form))
        all-forms))

;; ---------------------------------------------------------------------------
;; Tree-walk helpers — answer "does this form contain a sub-form
;; matching pred?".
;; ---------------------------------------------------------------------------

(defn- form-contains? [pred form]
  (let [hit? (atom false)]
    (walk/postwalk
     (fn [node]
       (when (pred node) (reset! hit? true))
       node)
     form)
    @hit?))

(defn- calls? [sym form]
  (form-contains?
   (fn [node] (and (seq? node) (= sym (first node))))
   form))

;; ---------------------------------------------------------------------------
;; Structural assertions
;; ---------------------------------------------------------------------------

(deftest app-db-reset-form-is-defined
  (testing "preload/re_frame_pair2/runtime.cljs defines app-db-reset!"
    (is (some? app-db-reset-form)
        "the defn form is present in the source")))

(deftest delegates-to-canonical-tool-pair-surface
  (testing "app-db-reset! delegates to rf/reset-frame-db! — the canonical
            Tool-Pair §Pair-tool writes surface (rf2-zq55) that mutates
            app-db, appends a synthetic :rf.epoch/db-replaced epoch,
            schema-validates, and drain-checks"
    (is (calls? 'rf/reset-frame-db! app-db-reset-form)
        "(rf/reset-frame-db! frame-id v) appears in the body")))

(deftest does-not-reach-through-handler-meta
  (testing "app-db-reset! does NOT use the buggy `(rf/handler-meta :frame
            frame-id) :app-db` path that returned {:ok? true} without
            mutating state or recording an epoch — that was rf2-mzn7's
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
        "no `(reset! container ...)` — delegating to rf/reset-frame-db!
         means the container is replaced inside that surface, not here")))

(deftest preserves-tap-log-guardrail
  (testing "the human-visible tap> log stays in place per
            docs/capabilities.md §Safety / guardrails — Previous + next
            + timestamp tap'd so the human sees what the agent changed"
    (is (calls? 'tap> app-db-reset-form)
        "tap> call survives in the body")
    (is (form-contains?
         (fn [node] (= :re-frame-pair2/op node))
         app-db-reset-form)
        ":re-frame-pair2/op tag in the tap> payload")
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
            rf/reset-frame-db! (when the day8/re-frame2-epoch artefact
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
