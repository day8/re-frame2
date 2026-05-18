;;;; tests/runtime/raw_state_tap_test.clj
;;;;
;;;; Babashka-runnable structural verification of the rf2-c2dtu raw-state
;;;; tap-elide path in `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; What we verify:
;;;;
;;;;   1. `configure-raw-state!` is defined and accepts an
;;;;      `:allow-raw-state?` keyword. The MCP server's
;;;;      `signal-runtime!` (tools/re-frame2-pair-mcp/src/.../tools/raw_state.cljs)
;;;;      calls this once per build per server lifetime.
;;;;   2. `app-db-reset!`'s `tap>` emission wraps both `:previous` and
;;;;      `:next` slots through `maybe-elide-for-tap` (or
;;;;      `rf/elide-wire-value` directly) — verbatim payloads only ride
;;;;      when the gate is explicitly opted in.
;;;;   3. `raw-state-config` defaults to `:allow-raw-state? true` so a
;;;;      bare REPL session (no re-frame2-pair-mcp attached) sees the legacy
;;;;      behaviour. The MCP-server boot flips it to `false` via
;;;;      `configure-raw-state!`.
;;;;
;;;; Run:    bb tests/runtime/raw_state_tap_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns raw-state-tap-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [clojure.walk :as walk]))

(def ^:private runtime-cljs-path
  (some (fn [p] (when (.exists (io/file p)) p))
        ["preload/re_frame2_pair/runtime.cljs"
         "skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs"
         "../preload/re_frame2_pair/runtime.cljs"]))

(when-not runtime-cljs-path
  (binding [*out* *err*]
    (println "ERROR: cannot locate preload/re_frame2_pair/runtime.cljs from"
             (System/getProperty "user.dir")))
  (System/exit 2))

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

(defn- find-top-form
  "Find the top-level form whose head matches `head-sym` and whose first
  symbol slot matches `name-sym`. Returns nil when absent."
  [head-sym name-sym]
  (some (fn [form]
          (when (and (seq? form)
                     (= head-sym (first form))
                     (= name-sym (second form)))
            form))
        all-forms))

(def ^:private configure-raw-state-form
  (find-top-form 'defn 'configure-raw-state!))

(def ^:private app-db-reset-form
  (find-top-form 'defn 'app-db-reset!))

(def ^:private raw-state-config-form
  (find-top-form 'defonce 'raw-state-config))

(def ^:private maybe-elide-form
  ;; `defn-` because the helper is private.
  (find-top-form 'defn- 'maybe-elide-for-tap))

;; ---------------------------------------------------------------------------
;; Tree-walk helpers.
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

(deftest configure-raw-state-form-is-defined
  (testing "configure-raw-state! is defined and exported"
    (is (some? configure-raw-state-form)
        "the defn form is present in the source")
    (is (form-contains? (fn [n] (= :allow-raw-state? n)) configure-raw-state-form)
        ":allow-raw-state? appears in the destructuring / opts")))

(deftest raw-state-config-defaults-to-allow-true
  (testing "raw-state-config defaults to {:allow-raw-state? true} so a
            bare REPL session (no re-frame2-pair-mcp attached) sees verbatim
            payloads — the re-frame2-pair-mcp server flips to false via
            configure-raw-state! at first state-emitting tool call."
    (is (some? raw-state-config-form)
        "the defonce form is present")
    (is (form-contains?
         (fn [node]
           (and (map? node)
                (= true (get node :allow-raw-state?))))
         raw-state-config-form)
        "{:allow-raw-state? true} literal seeds the atom")))

(deftest maybe-elide-for-tap-routes-through-elide-wire-value
  (testing "maybe-elide-for-tap calls rf/elide-wire-value when the gate
            is OFF — the framework primitive that applies the same
            large/sensitive predicates the wire path uses"
    (is (some? maybe-elide-form)
        "the defn form is present")
    (is (calls? 'rf/elide-wire-value maybe-elide-form)
        "(rf/elide-wire-value v opts) appears in the body")))

(deftest app-db-reset-wraps-tap-payload-through-elide
  (testing "app-db-reset!'s tap> emission routes both :previous and
            :next slots through the elide wrapper — verbatim payloads
            ride only when the gate is opted in via
            configure-raw-state!"
    (is (form-contains?
         (fn [node]
           (and (seq? node)
                (= 'maybe-elide-for-tap (first node))))
         app-db-reset-form)
        "(maybe-elide-for-tap ...) appears in the body")))

;; ---------------------------------------------------------------------------
;; Run.
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'raw-state-tap-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
