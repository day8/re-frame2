(ns re-frame.freehand.id-sugar-presence-cljs-test
  "The `#id` shorthand conflict is decided by PRESENCE of an authored key
  that projects onto the emitted `id` slot — never by what that key holds.

  The compiled analyzer scans a literal props map's KEYS, so it has no
  value to consult and always refused `[:div#sugar {:id nil}]`. The
  structural and React walks guarded on `(some? raw)` and accepted the
  same declaration. That is the sharpest shape a mode split can take:
  adding `{:compiled true}` to a working view turns a rendering element
  into a compile error while nothing in the declaration changed.

  The structural half of this claim — interpreted and compiled, on the
  JVM — is `re-frame.freehand.structural-slot-alias-jvm-test`. This is the
  React walk's half, which is a SEPARATE walk and therefore proves the
  rule for itself.

  Normative owner:
  [`spec/004B-UI-Tree-and-Conversion.md`](../../../../../spec/004B-UI-Tree-and-Conversion.md)
  §`.class#id` sugar vs explicit `:class`/`:id`."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [goog.object :as gobj]
            [re-frame.freehand.react :as fr]))

(defn- refusal
  "The `ExceptionInfo` the React walk raised for `form`, or nil when it
  built an element."
  [form]
  (try (fr/element form) nil (catch :default e e)))

(def ^:private nil-rows
  "One id spelled twice, the second time with a nil value. The qualified
  row is here because the guard reads the emitted SLOT: React writes `:id`
  and `:x/id` into one JavaScript property."
  [{:note "the exact spelling"      :form [:div#sugar {:id nil}]}
   {:note "and its aliased twin"    :form [:div#sugar {:x/id nil}]}])

(deftest a-nil-authored-id-beside-sugar-is-refused-by-the-react-walk
  (testing "The authored key IS the second spelling. What it holds is the
            ordinary attribute question — it decides what the id EMITS, and
            it cannot decide whether the element was given an id twice."
    (doseq [{:keys [note form]} nil-rows]
      (let [ex (refusal form)]
        (is (some? ex) (str note " — " (pr-str form) " is refused"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str note " — the walk's existing diagnostic, not a new one"))
          (is (str/includes? (ex-message ex) "twice")
              (str note " — the message says the id is spelled twice")))))))

(deftest a-runtime-nil-id-is-refused-on-the-same-footing
  (testing "The interpreted walk sees a VALUE, not a form, so a nil that
            arrived from application state is the same declaration as a
            literal nil and takes the same verdict. A rule that held only
            for the literal would be a rule about source text."
    (let [missing (:absent {})
          ex      (refusal [:div#sugar {:id missing}])]
      (is (some? ex) "a runtime nil id beside sugar is refused")
      (when ex
        (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex))))))))

(deftest the-refusal-is-scoped-to-the-shorthand-ambiguity
  (testing "With no `#id` sugar there is no second spelling, so a nil id is
            the ordinary nil attribute the generic law drops. A guard that
            refused every nil id would be a worse defect than the bypass it
            closed — a conditional id is an ordinary thing to write."
    (doseq [form [[:div {:id nil}] [:div {:x/id nil}]]]
      (let [el (fr/element form)]
        (is (some? el) (str (pr-str form) " still renders"))
        (is (not (gobj/containsKey (.-props el) "id"))
            (str (pr-str form) " — and carries no id prop"))))
    (is (= "sugar" (gobj/get (.-props (fr/element [:div#sugar])) "id"))
        "the shorthand alone is untouched")
    (is (= "plain" (gobj/get (.-props (fr/element [:div {:id "plain"}])) "id"))
        "and so is an authored id with no shorthand to collide with")))
