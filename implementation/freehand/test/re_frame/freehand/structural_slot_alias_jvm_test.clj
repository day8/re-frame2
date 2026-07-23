(ns re-frame.freehand.structural-slot-alias-jvm-test
  "An ALIASED attribute key that projects onto one of React's structural
  slots, ruled — and ruled two different ways, which is why it is proved
  here rather than assumed.

  Three authored keys reduce to a slot the walks own rather than to an
  ordinary attribute: `:key`, `:class` and `:style`. Every representation
  of those names reaches the SAME slot, because both emitters classify an
  attribute key by its `name` — so `:x/class`, `\"class\"` and `'class` are
  one key spelled three ways, and a guard or a router that compared the raw
  map key would see three.

  The ruling SPLITS them, because they are not the same kind of thing:

    - `:key` is REFUSED, in every representation but the exact one. React's
      key is not a prop — the reconciler consumes it and it never reaches
      the DOM — so an alias routed into it would not misspell an attribute,
      it would change which element React considers the same element across
      renders. Wrong element reuse is `:children`'s hazard class, not a
      misspelled attribute's.
    - `:class` and `:style` are ROUTED, canonicalized to the slot they
      project onto, because they are ordinary props that reach the DOM and
      `v/spread-safe` already accepts an aliased spelling of an accepted key
      and routes it. Refusing them on the direct path would make it stricter
      than the spread path for the same key.

  Routing `:class` carries the obligation this file exists to hold down: the
  routed value must COMPOSE into the class string beside the tag shorthand,
  never assign over it. A router that assigned would silently drop
  `:div.a.b`'s classes — a worse defect than the one being fixed, and an
  invisible one, because the element still renders.

  Every row is asserted in BOTH modes from one declaration: interpreted
  through [[re-frame.freehand.tree/render]], compiled through the emitted
  lowering itself, evaluated and called. A rule that held in one mode would
  be the cross-mode divergence the substrate exists to rule out."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.tree :as tree]))

(def ^:private params
  "The one binding the dynamic rows read, so a computed attribute value is a
  real runtime value in BOTH modes rather than a literal in disguise."
  '[{:keys [flag]}])

(defn- compiled-tree
  "The structural tree the COMPILED front end answers for `body` — the
  emitted lowering, evaluated and called, rather than a claim about the
  form it emitted. `props` supplies the one binding the dynamic rows read."
  ([body] (compiled-tree body {}))
  ([body props]
   (let [lowering (compiler/compile-structural-view
                    {:form            (list 'v/defview 'subject params body)
                     :menv            nil
                     :ns-sym          're-frame.freehand.structural-slot-alias-jvm-test
                     :vname           'subject
                     :view-id         ::subject
                     :params          params
                     :body            [body]
                     :children-policy :optional})]
     ((eval (:body lowering)) props))))

(defn- interpreted-tree
  "The structural tree the INTERPRETED walk answers for the same body, with
  the root's version stripped so the two modes' values are comparable. The
  compiled front end binds `flag` through its params; the interpreted walk
  has no params, so the same value is substituted into the form."
  ([body] (interpreted-tree body {}))
  ([body props]
   (dissoc (tree/render (walk/postwalk-replace
                          (into {} (map (fn [[k x]] [(symbol (name k)) x])) props)
                          body))
           :rf.ui/tree-version)))

;; ---------------------------------------------------------------------------
;; The routed slots — `:class` and `:style`
;; ---------------------------------------------------------------------------

(def routed-rows
  "Declarations whose attribute key is an ALIAS of a slot-owning key, and
  the ONE tree both modes answer.

  The `.class` rows are the load-bearing ones. Each carries a tag shorthand
  AND an aliased class, so a router that assigned into the class slot rather
  than composing into it answers `\"c\"` where the contract says `\"a b c\"`
  — the shorthand silently gone, on an element that still renders."
  [{:note  "a namespaced :class composes into the shorthand, exactly as :class does"
    :body  '[:div.a.b {:x/class "c"}]
    :tree  {:tag :div :attrs {:class "a b c"}}}

   {:note  "and it keeps the whole class grammar — a flag map still renders its truthy entries in lexicographic order"
    :body  '[:div.a.b {:x/class {:open true :busy false}}]
    :tree  {:tag :div :attrs {:class "a b open"}}}

   {:note  "a vector value keeps vector order beneath the shorthand"
    :body  '[:span.tag {:x/class ["c" "d"]}]
    :tree  {:tag :span :attrs {:class "tag c d"}}}

   {:note  "a namespaced :style reaches the CSS grammar, so numbers still gain px"
    :body  '[:div {:x/style {:color "red" :width 4}}]
    :tree  {:tag :div :attrs {:style {:color "red" :width "4px"}}}}

   {:note  "an aliased class and an aliased style on one element"
    :body  '[:section.panel {:x/class "open" :x/style {:z-index 3}}]
    :tree  {:tag :section :attrs {:class "panel open" :style {:z-index "3"}}}}])

(deftest an-alias-of-a-slot-owning-key-routes-to-that-slot
  (testing "Per the rf2-drpa3.93 ruling: `:class` and `:style` are ordinary
            props that reach the DOM, and the substrate already routes an
            aliased spelling of an accepted key to that key's slot
            (`v/spread-safe`). So an alias on the DIRECT attribute path is
            routed too — refusing it there would make the direct path
            stricter than the spread path for the same key, which an author
            would experience as arbitrary."
    (is (<= 4 (count routed-rows)) "the table is not vacuously small")
    (doseq [{:keys [note body tree]} routed-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))

(deftest a-routed-class-composes-with-the-shorthand-rather-than-replacing-it
  (testing "The correctness obligation the routing carries, stated on its
            own so it cannot be lost inside a table. The tag shorthand and
            an authored class are MERGED — a routed alias that assigned
            would drop `.a.b` silently, and the element would still render,
            which is why this is asserted rather than assumed."
    (doseq [[mode tree] [["interpreted" (interpreted-tree '[:div.a.b {:x/class "c"}])]
                         ["compiled"    (compiled-tree    '[:div.a.b {:x/class "c"}])]]]
      (let [classes (set (str/split (get-in tree [:attrs :class]) #" "))]
        (is (contains? classes "a") (str mode " — the shorthand's first class survived"))
        (is (contains? classes "b") (str mode " — and its second"))
        (is (contains? classes "c") (str mode " — and the aliased class arrived"))))))

(def dynamic-rows
  "The same routing where the VALUE is only known at render. A compiled
  declaration settles a literal class into a constant and hands a computed
  one to the shared class rule instead, so these rows walk the other half of
  the compiled path — and the shared rule is the interpreted walk's own."
  [{:note  "a computed aliased class still composes beneath the shorthand"
    :body  '[:div.a.b {:x/class flag}]
    :props {:flag "c"}
    :tree  {:tag :div :attrs {:class "a b c"}}}

   {:note  "and a flag map whose entry is computed keeps the one lexicographic order"
    :body  '[:div.a.b {:x/class {:open flag}}]
    :props {:flag true}
    :tree  {:tag :div :attrs {:class "a b open"}}}

   {:note  "a computed style value normalizes through the CSS grammar"
    :body  '[:div {:x/style {:width flag}}]
    :props {:flag 4}
    :tree  {:tag :div :attrs {:style {:width "4px"}}}}])

(deftest a-routed-alias-composes-at-render-time-too
  (testing "The literal and the computed paths through a routed alias are
            the SAME rule, reached from two directions: a build-time
            constant in one mode, the shared class and style rules at render
            in both. A router that only composed the literal case would look
            correct in every table above."
    (doseq [{:keys [note body props tree]} dynamic-rows]
      (is (= tree (interpreted-tree body props)) (str note " — interpreted"))
      (is (= tree (compiled-tree body props)) (str note " — compiled")))))

;; ---------------------------------------------------------------------------
;; The refused slot — `:key`
;; ---------------------------------------------------------------------------

(def key-aliases
  "Every representation of React's key an author can write in an attribute
  map. All three reduce to the same emitted slot, so all three are refused."
  ['[:div {:x/key "k"}]
   '[:div {"key" "k"}]
   '[:div {key "k"}]])

(deftest an-alias-of-react-s-key-is-refused-in-the-interpreted-walk
  (testing "Per the rf2-drpa3.93 ruling: React's key is NOT a prop. The
            reconciler consumes it and it never reaches the DOM, so an
            alias silently routed into it would change reconciliation
            IDENTITY — preserved DOM state landing on the wrong row, or a
            remount where none was intended. That is a structural
            divergence, the `:children` hazard class, so `:key` keeps its
            one spelling and every alias of it is refused."
    (doseq [body key-aliases]
      (let [ex (try (tree/render body)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str (pr-str body) " is refused"))
        (when ex
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              (str (pr-str body) " — the walk's existing diagnostic id, not a new one"))
          (is (str/includes? (ex-message ex) "reconciler")
              (str (pr-str body) " — the message says WHY a key is different from an attribute")))))))

(deftest an-alias-of-react-s-key-is-refused-in-compiled-mode-too
  (testing "The same law at the other tier, through the macro an author
            actually writes. A declaration the interpreted walk refuses and
            the compiler accepted would be exactly the two-answers-for-one-
            declaration split the refusal exists to close."
    ;; `*ns*` is bound explicitly because `macroexpand` resolves the head
    ;; against the namespace it runs IN, and a test runs inside the runner's
    ;; namespace where `v` names nothing — the form would come back
    ;; unexpanded and the assertion below would pass vacuously.
    (let [ex (try (binding [*ns* (the-ns 're-frame.freehand.structural-slot-alias-jvm-test)]
                    (macroexpand
                      '(v/defview subject
                         {:compiled true}
                         [_]
                         [:div {:x/key "k"}])))
                  nil
                  (catch Exception e e))]
      (is (some? ex) ":x/key is refused at macroexpansion")
      (let [data (ex-data (or (ex-cause ex) ex))]
        (is (= :rf.ui.compile/rejected-prop-spelling (:rf.ui.compile/error data))
            "and it reuses the rejected-spelling id rather than minting a new one")))
    ;; The control that makes the refusal above mean something: the same
    ;; expansion, of a declaration carrying a ROUTED alias, succeeds. A
    ;; macroexpansion that failed for an unrelated reason would look
    ;; identical from the catch block.
    (is (seq? (binding [*ns* (the-ns 're-frame.freehand.structural-slot-alias-jvm-test)]
                (macroexpand
                  '(v/defview subject
                     {:compiled true}
                     [_]
                     [:div.a.b {:x/class "c" :x/style {:color "red"}}]))))
        "an aliased :class and :style expand — the refusal above is about :key alone")
    (is (map? (compiler/compile-structural-view
                {:form            (list 'v/defview 'subject params '[:div {:key "k"}])
                 :menv            nil
                 :ns-sym          're-frame.freehand.structural-slot-alias-jvm-test
                 :vname           'subject
                 :view-id         ::subject
                 :params          params
                 :body            ['[:div {:key "k"}]]
                 :children-policy :optional}))
        "while the exact :key spelling still compiles — the refusal is about the ALIAS")))

;; ---------------------------------------------------------------------------
;; The scope fence
;; ---------------------------------------------------------------------------

(def canonical-rows
  "The declarations the ruling must NOT have touched. A regression here
  would be worse than the bug being fixed: `:key`, `:class` and `:style`
  spelled exactly are the ordinary way every view in the corpus is written."
  [{:note "the exact :class still composes with the shorthand"
    :body '[:div.a.b {:class "c"}]
    :tree {:tag :div :attrs {:class "a b c"}}}

   {:note "the exact :style still normalizes through the CSS grammar"
    :body '[:div {:style {:color "red" :width 4}}]
    :tree {:tag :div :attrs {:style {:color "red" :width "4px"}}}}

   {:note "the exact :key is still lifted onto the node and is never an attribute"
    :body '[:div {:key "k"}]
    :tree {:tag :div :key "k"}}

   {:note "a shorthand with no authored class is untouched"
    :body '[:div.a.b]
    :tree {:tag :div :attrs {:class "a b"}}}

   {:note "and a namespaced ORDINARY attribute stays in author space, namespace intact"
    :body '[:div {:x/title "ok" :x/tab-index 3}]
    :tree {:tag :div :attrs {:x/title "ok" :x/tab-index "3"}}}])

(deftest the-canonical-spellings-are-unchanged
  (testing "The ruling is about the ALIAS shape only. `:key`, `:class` and
            `:style` written exactly keep precisely the behaviour they had,
            and a qualified key that projects onto an ORDINARY prop is not
            canonicalized at all — the structural tree carries authored
            names, so rewriting one would edit the tree for no gain."
    (doseq [{:keys [note body tree]} canonical-rows]
      (is (= tree (interpreted-tree body)) (str note " — interpreted"))
      (is (= tree (compiled-tree body)) (str note " — compiled")))))
