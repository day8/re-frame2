(ns re-frame.adapter.uix-frame-provider-branches-cljs-test
  "Per rf2-9i2du — pin the `frame-provider`'s two branching slots that
  no test was exercising:

    (1) `:frame` missing or `nil` — falls through to `:rf/default`
        (per rf2-sixo).
    (2) `:children` single value vs. sequential — single child must
        not throw; sequential renders all children.

  The two branches live in `re-frame.substrate.spine/frame-provider`:

      (defn frame-provider
        [{:keys [frame children]}]
        (let [frame-kw (or frame :rf/default)]
          (apply adapter-context/provider-element frame-kw
                 (if (sequential? children) children [children]))))

  Both branches are reachable from every React-shaped adapter; this
  file pins the contract through the UIx adapter's user-facing
  `frame-provider` re-export.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up.
  Headless assertions only — no React render is required; we inspect
  the returned React element's `_currentValue`-equivalent (the
  `provider-element`'s `.-props.-value` slot) directly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

(defn- provider-element-frame-kw
  "Pull the `:value` prop off a React element returned by
  `frame-provider` — this is the frame keyword the surrounding
  Context.Provider will hand down to `use-context` consumers."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "value")))

(defn- provider-element-children
  "Pull the `children` prop off the React element returned by
  `frame-provider`. React normalises a single-element children to
  the element directly; multi-element children come through as a
  JS array."
  [el]
  (when (and el (.-props el))
    (aget (.-props el) "children")))

;; ---- (1) nil / missing :frame falls through to :rf/default ----------------

(deftest frame-provider-missing-frame-falls-through-to-default
  (testing "rf2-9i2du: (frame-provider {:children [...]}) — no :frame at
            all — falls through to :rf/default per rf2-sixo. The
            provider-element's `:value` slot carries :rf/default."
    (let [el (uix-adapter/frame-provider {:children [:fake-child-a :fake-child-b]})]
      (is (some? el) "frame-provider returned a React element")
      (is (= :rf/default (provider-element-frame-kw el))
          "missing :frame defaulted to :rf/default"))))

(deftest frame-provider-nil-frame-falls-through-to-default
  (testing "rf2-9i2du: (frame-provider {:frame nil :children [...]}) —
            explicit nil :frame — falls through to :rf/default. The
            `(or frame :rf/default)` clause covers both the missing-key
            and nil-value cases."
    (let [el (uix-adapter/frame-provider {:frame nil :children [:fake-child]})]
      (is (some? el))
      (is (= :rf/default (provider-element-frame-kw el))
          "nil :frame defaulted to :rf/default"))))

(deftest frame-provider-named-frame-preserved
  (testing "rf2-9i2du: a supplied :frame keyword is preserved on the
            provider element's value slot. Sanity-check counterpart to
            the default-fallback assertions."
    (let [el (uix-adapter/frame-provider {:frame :tenant-a :children [:fake-child]})]
      (is (= :tenant-a (provider-element-frame-kw el))
          ":frame :tenant-a flows through to the provider's value slot"))))

;; ---- (2) :children single value vs sequential ----------------------------

(deftest frame-provider-single-child-coerced-to-vector
  (testing "rf2-9i2du: (frame-provider {:frame :session :children child-a})
            — a single child (NOT a vector) — does not throw and is
            coerced to a one-element children sequence by the
            `(if (sequential? children) children [children])` branch.
            Pins the spine's frame-provider single-vs-sequential coercion."
    (let [single-child :fake-single-child-marker
          el (uix-adapter/frame-provider {:frame :session :children single-child})]
      (is (some? el) "frame-provider didn't throw on a non-sequential :children")
      (is (= :session (provider-element-frame-kw el)))
      ;; React normalises single-element children to the element value;
      ;; the marker survives the coercion regardless of normalisation.
      (let [kids (provider-element-children el)]
        (is (or (= single-child kids)
                (and (some? kids)
                     (or (not (.-length kids))
                         (= 1 (.-length kids)))))
            "single :children produced a one-element children slot")))))

(deftest frame-provider-sequential-children-preserved
  (testing "rf2-9i2du: a sequential :children vector flows through the
            spine's coercion branch unchanged — multiple children are
            handed to the Provider as separate args."
    (let [a :child-a
          b :child-b
          el (uix-adapter/frame-provider {:frame :session :children [a b]})]
      (is (some? el))
      (is (= :session (provider-element-frame-kw el)))
      (let [kids (provider-element-children el)]
        (is (some? kids))
        (is (= 2 (.-length kids))
            "sequential :children produced a two-element children slot")))))
