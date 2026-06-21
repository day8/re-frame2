(ns re-frame.ssr.ring.facade-examples-test
  "Per rf2-09iktm — pin that the PUBLIC docstring EXAMPLES on the
  `re-frame.ssr.ring` facade stay aligned with the runtime contract.

  `facade-metadata-test` proves the facade vars carry a non-empty `:doc`
  + `:arglists`; it does NOT look INSIDE the docstring at the example
  code. After the fail-closed `:payload` change (rf2-gtgf9) and the
  curried-middleware shape, the worked examples drifted: the
  `ssr-handler` example omitted `:payload` (a copy-paste would throw
  `:rf.error/ssr-missing-payload-policy` at construction) and the
  `ssr-middleware` example used the wrong, uncurried `(-> handler
  (ssr-middleware opts) ...)` shape (the public surface is
  `(ssr-middleware opts) -> (handler -> wrapped)`). REPL `doc`, editor
  hover, and the JVM api-manifest surface these examples, so they are
  user-facing.

  This test is the drift tripwire. It reads the live docstring, extracts
  the worked example forms, and asserts the structural invariants a
  copyable example must hold:

    1. The `ssr-handler` example opts map carries a `:payload` policy,
       AND that opts map actually passes `validate-construction-opts!`
       (so the example is constructible, not merely textually present).
    2. The `ssr-middleware` example is the CURRIED shape — the
       `(ssr-middleware opts)` call is itself applied to the fallback
       handler — AND its opts map carries `:payload`.

  Reads from the FACADE var docstring (not the source file text) so the
  test follows wherever the canonical doc lives."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.lifecycle :as lifecycle]))

(defn- example-block
  "Return the text following the `Example:` marker in `docstring`, i.e.
  the worked-example region of a facade fn doc."
  [docstring]
  (let [idx (str/index-of docstring "Example:")]
    (when idx
      (subs docstring (+ idx (count "Example:"))))))

(defn- read-forms
  "Read all top-level Clojure forms from `s`, ignoring trailing garbage.
  Used to recover the example forms embedded in a docstring."
  [s]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. s))
        eof (Object.)]
    (loop [acc []]
      (let [form (try (read rdr false eof) (catch Exception _ eof))]
        (if (= form eof)
          acc
          (recur (conj acc form)))))))

(defn- find-call
  "Depth-first search `forms` for the first list whose head symbol's NAME
  is `head-name` (namespace-insensitive, so `ssr-ring/ssr-handler` and a
  bare `ssr-handler` both match)."
  [head-name forms]
  (letfn [(walk [form]
            (cond
              (and (seq? form)
                   (symbol? (first form))
                   (= head-name (name (first form))))
              form

              (coll? form)
              (some walk (seq form))

              :else nil))]
    (some walk forms)))

(deftest ssr-handler-docstring-example-includes-payload-and-constructs
  (testing "rf2-09iktm: the ssr-handler facade example carries a :payload
            policy and the example opts map passes
            validate-construction-opts! (so a copy-paste is constructible,
            not a fail-closed boot throw)"
    (let [doc   (-> #'ssr-ring/ssr-handler meta :doc)
          forms (read-forms (example-block doc))
          call  (find-call "ssr-handler" forms)
          opts  (second call)]
      (is (some? call) "the docstring Example: shows an `ssr-handler` call")
      (is (map? opts) "the ssr-handler example passes a literal opts map")
      (is (contains? opts :payload)
          (str "the ssr-handler example opts map MUST include a :payload "
               "policy (rf2-gtgf9 fail-closed) — saw keys: "
               (pr-str (keys opts))))
      ;; Stronger than textual presence: the example opts map must pass
      ;; the live construction validator (with placeholder event/view
      ;; registered) — proving the example is actually constructible.
      (rf/reg-event :init/facade-example {:platforms #{:server}} (fn [_ _] {}))
      (rf/reg-view* :pages/facade-example (fn [] [:div "facade example"]))
      (is (= (assoc opts :initial-events [[:init/facade-example]]
                         :root-view [:pages/facade-example])
             (lifecycle/validate-construction-opts!
               (assoc opts :initial-events [[:init/facade-example]]
                           :root-view [:pages/facade-example])))
          "the ssr-handler example opts map passes validate-construction-opts!"))))

(deftest ssr-middleware-docstring-example-is-curried-and-includes-payload
  (testing "rf2-09iktm: the ssr-middleware facade example uses the correct
            CURRIED composition shape — `((ssr-middleware opts) handler)` —
            and its opts map carries :payload"
    (let [doc   (-> #'ssr-ring/ssr-middleware meta :doc)
          block (example-block doc)
          forms (read-forms block)
          call  (find-call "ssr-middleware" forms)
          opts  (second call)]
      (is (some? call) "the docstring Example: shows an `ssr-middleware` call")
      (is (map? opts) "the ssr-middleware example passes a literal opts map")
      (is (contains? opts :payload)
          (str "the ssr-middleware example opts map MUST include a :payload "
               "policy (rf2-gtgf9 fail-closed) — saw keys: "
               (pr-str (keys opts))))
      ;; Curried-shape invariant: the `(ssr-middleware opts)` form must be
      ;; APPLIED — i.e. it appears in head (callee) position of an outer
      ;; list, `((ssr-middleware opts) <handler>)`. The pre-fix, broken
      ;; example used `(-> handler (ssr-middleware opts) ...)`, where the
      ;; `(ssr-middleware opts)` form is an ARGUMENT, never a callee.
      (letfn [(applied? [form]
                (cond
                  (seq? form)
                  (or (and (seq? (first form))
                           (= call (first form)))
                      (some applied? (seq form)))
                  (coll? form) (some applied? (seq form))
                  :else nil))]
        (is (some applied? forms)
            "the ssr-middleware example applies `(ssr-middleware opts)` to a
             fallback handler — the curried shape `((ssr-middleware opts) h)`
             — rather than threading it as a plain `(-> h (ssr-middleware ...))`
             arg")))))
