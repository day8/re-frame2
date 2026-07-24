(ns re-frame.freehand.style-compose-react-cljs-test
  "rf2-8jqw7 — the React walk's half of the `:style` compose.

  An exact `:style` and an alias projecting onto the style slot in one map
  COMPOSE, merged property by property, rather than the last one written
  winning. The structural half of this claim — interpreted and compiled,
  on the JVM — is `re-frame.freehand.structural-slot-alias-jvm-test`. This
  is the React walk's SEPARATE lane (`react-props` collects the style
  slot, `react-style` merges it onto one style object), so it proves the
  rule for itself: a `put-style!` per entry would have last-wins-overwritten
  the browser element's `style` object even where the structural tree
  merged.

  Read off the emitted props object rather than a mounted node — the
  merge is `react-style`'s, and the props object is what React renders —
  so the test needs no DOM."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [re-frame.freehand.react :as fr]))

(defn- style-of
  "The `style` object the React walk emits for `form`, as a Clojure map of
  the React style property name to its value."
  [form]
  (let [s (gobj/get (.-props (fr/element form)) "style")]
    (persistent!
      (reduce (fn [m k] (assoc! m k (gobj/get s k)))
              (transient {})
              (gobj/getKeys s)))))

(deftest an-exact-style-and-an-alias-compose-in-the-react-walk
  (testing "Both properties survive a non-conflict — the exact :style's and
            the alias's — merged onto one style object rather than the
            second write clobbering the first."
    (is (= {"color" "red" "margin" "0"}
           (style-of [:div {:style {:color "red"} :x/style {:margin "0"}}]))
        "distinct properties from both maps are present")
    (is (= {"color" "red" "margin" "0"}
           (style-of [:div {:x/style {:margin "0"} :style {:color "red"}}]))
        "and the source order of the two keys does not change the result")))

(deftest a-style-conflict-resolves-deterministically-in-the-react-walk
  (testing "A genuine conflict resolves to the alias — the exact is the
            base and the alias composes over it — deterministically, in
            either key order, never by props-map iteration order."
    (is (= {"color" "blue"}
           (style-of [:div {:style {:color "red"} :x/style {:color "blue"}}]))
        "the alias wins the shared property")
    (is (= {"color" "blue"}
           (style-of [:div {:x/style {:color "blue"} :style {:color "red"}}]))
        "and reversing the keys does not change that verdict")))

(deftest a-lone-style-is-unchanged-by-the-compose-path
  (testing "Non-vacuity for the collection itself: an element with a single
            style value — exact or aliased — still emits exactly that style,
            so the compose path did not disturb the common case."
    (is (= {"color" "red"} (style-of [:div {:style {:color "red"}}]))
        "a lone exact :style")
    (is (= {"color" "red"} (style-of [:div {:x/style {:color "red"}}]))
        "and a lone aliased style")))

(deftest a-nil-style-is-absent-through-the-compose-path
  (testing "The nil-is-absent law survives the compose collection: a
            conditional style that folds to nil contributes nothing rather
            than rejecting the React element, so the real value beside it
            survives and an all-nil slot writes no style object at all. This
            is the law the per-key walk applied before the collection
            subsumed the style slot."
    (is (= {"color" "red"} (style-of [:div {:style {:color "red"} :x/style nil}]))
        "a nil alias is absent — the exact value survives")
    (is (= {"color" "blue"} (style-of [:div {:style nil :x/style {:color "blue"}}]))
        "a nil exact is absent — the aliased value survives")
    (is (nil? (gobj/get (.-props (fr/element [:div {:style nil}])) "style"))
        "a lone nil style writes no style object — absent, not empty")
    (is (nil? (gobj/get (.-props (fr/element [:div {:style nil :x/style nil}])) "style"))
        "and an all-nil slot writes no style object either")))
