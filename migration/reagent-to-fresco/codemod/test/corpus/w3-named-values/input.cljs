(ns app.w3
  "W3 — named prop values (design §4.3), and AMENDMENT (C) at the key
  slot. Reagent answered `(name x)` for any keyword or symbol at every
  prop; Hicasso keeps the named value whole except at HTML-attribute
  slots (the rf2-vrvv9 narrowing)."
  (:require [reagent.core :as r]))

(defn a-plain-keyword-value []
  [:> Btn {:variant :contained} "Save"])

(defn a-quoted-symbol-takes-the-same-arm []
  ;; `named?` is keyword-or-symbol, so the donor answered `(name x)` here too.
  [:> Btn {:variant 'my-sym}])

(defn a-BARE-symbol-is-a-variable-and-is-NEVER-rewritten []
  ;; THE DISTINCTION THAT KEEPS THIS TOOL HONEST. `handler` is a variable
  ;; reference, not a symbol value — reading it as one would write
  ;; `{:on-click "handler"}` and replace a live handler with a string,
  ;; silently. Everything reached through a symbol is invisible to a
  ;; source reader and falls to `:computed-value` (§4.4).
  [:> Btn {:variant chosen-variant :on-click handler}])

(defn a-namespaced-keyword-preserves-a-collision []
  [:> Provider {:theme :theme/dark}])

(defn the-html-attribute-slots-are-fixpoints []
  [:> Btn {:className :wide :id :greeting :role :dialog
           :aria-label :close :data-kind :row}])

(defn the-class-slot-in-any-spelling []
  [:> Btn {:class :theme/dark}])

(defn the-ref-slot-is-reported-never-written []
  [:> Btn {:ref :my-ref}])

(defn the-key-slot-is-excluded-and-reported []
  [:> Row {:key :foo}])
