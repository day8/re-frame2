(ns fixture.negative.query-retain-lookalikes
  "NEGATIVE fixture: the surviving query vocabulary plus every near-miss
  spelling. Must stay GREEN — the rule matches the retired keyword TOKEN
  `:query-retain` exactly, so a namespaced or suffixed lookalike is a
  different key and none of them is retired.")

(defn register! [rf]
  (rf/reg-route :route/cart
                {;; the surviving two-slot promotion vocabulary
                 :query          [:map [:sort {:optional true} :keyword]]
                 :query-defaults {:sort :recent}
                 ;; app-namespaced keys are always accepted (open map for
                 ;; NAMESPACED keys), and none of these is the retired key
                 :myapp/query-retain-policy :shell-keys
                 :myapp/query-retains       #{:locale}}
                "/cart"))

;; Suffixed / qualified spellings that CONTAIN the retired token's characters
;; but are not the token: the rule's trailing boundary rejects both.
(def ^:private not-the-retired-key
  {:query-retains     #{:locale}
   :query-retain/mode :shell-keys})

;; The in-place request keys — the causal primitive for "same page, different
;; query" — are untouched by the retirement.
(defn shell-nav [current-query]
  {:to          :route/cart
   :query-merge (select-keys current-query [:locale :tenant])})
