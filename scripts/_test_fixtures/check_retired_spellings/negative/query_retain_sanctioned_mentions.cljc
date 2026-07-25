(ns fixture.negative.query-retain-sanctioned-mentions
  "NEGATIVE fixture: the retirement NOTES that name :query-retain as retired.
  Reproduces the framework-source mentions verbatim — two `;;` comments and
  one docstring passage from
  implementation/routing/src/re_frame/routing/{registry,navigate}.cljc plus
  the classification advisory note. Must stay GREEN: a rule that fires on the
  notes explaining the retirement would make the retirement undocumentable.")

;; EP-0037 R5: route `:query-retain` is RETIRED with no alias — a route
;; declaring it is now rejected as an unknown bare key. Carrying query
;; state across routes is an APPLICATION policy spelled as an ordinary
;; pure function over the destination address (Spec 012 §Carrying query
;; state across routes); the framework no longer folds ambient current
;; query into a destination the caller authored.
(def ^:private reserved-route-keys
  #{:doc :path :params :query :query-defaults
    :tags :parent :on-match :scroll :can-leave :can-enter
    :sensitive :large
    :head})

;; EP-0037 R5 shrank the promotion vocabulary from three sources to two: the
;; retired `:query-retain` no longer promotes anything, so a key that was only
;; keyword-promoted through it now WARNS until the route declares it.
(defn- promoted-query-keys [metadata]
  (into #{}
        (mapcat (fn [slot] (keys (get metadata slot))))
        [:query :query-defaults]))

(defn coerce-query
  "Authors who want keyword keys declare them via `:query` / `:query-defaults`
  — author-named intent is the trust boundary. EP-0037 R5 made those the ONLY
  two slots — a key that used to be keyword-promoted solely because the retired
  `:query-retain` named it must now be declared in `:query` or
  `:query-defaults` to keep its keyword form."
  [metadata query]
  (select-keys query (promoted-query-keys metadata)))
