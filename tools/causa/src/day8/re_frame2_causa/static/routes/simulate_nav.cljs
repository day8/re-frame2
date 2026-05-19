(ns day8.re-frame2-causa.static.routes.simulate-nav
  "Hermetic 'Simulate navigation' preview for Causa's Static Routes
  tab (rf2-o5f5f.3).

  ## Purpose

  Per the parent epic decision (option (a): two-verbs-two-homes) and
  the findings doc §4.3 option (a): the per-row 'Simulate navigation'
  button is HERMETIC — NO real dispatch into the host app, NO fx, NO
  app-db mutation. It surfaces a preview of what would land:

    - matched params (derived from the row's pattern + the optional
      URL from the row's simulate-URL input);
    - the registered `:on-match` event vector;
    - the expected app-db slot (`[:rf/route ...]`) shape.

  The hermetic posture is the spec point — Causa is a lens, not a
  remote control. Real navigation lives behind `:rf.route/navigate`
  + `rf.route/url-requested` and is reachable from the host UI.

  ## Pure helper, view-thin

  The pure-data projection lives in
  `panels/routing-helpers/simulate-navigation-preview` — JVM-portable
  so the unit-test target covers the cascade. This ns is the view
  layer only."
  (:require [day8.re-frame2-causa.panels.routing-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack]]))

(defn- field-row
  "One `<label> : <value>` row in the preview grid."
  [{:keys [label value testid mono?]}]
  [:<>
   [:span {:style {:color       (:text-tertiary tokens)
                   :font-family sans-stack
                   :font-size   "10px"
                   :text-transform "uppercase"
                   :letter-spacing "0.4px"}}
    label]
   [:span {:data-testid testid
           :style       {:color       (:text-primary tokens)
                         :font-family (if mono? mono-stack sans-stack)
                         :font-size   "12px"
                         :white-space "pre-wrap"
                         :word-break  "break-word"}}
    value]])

(defn preview
  "Render the hermetic Simulate-navigation preview for `route-id`. The
  optional `url` is the row's local Simulate-URL input — when present
  the preview shows matched params (when the URL actually matches the
  row's pattern); when absent the preview shows the route's
  registered shape (path / on-match / db-slot).

  `routes-map` is the registered-routes map passed in from the panel
  so this view stays pure (no subscribes; the panel composes against
  the registry sub and threads the data down)."
  [routes-map route-id url]
  (let [pv (h/simulate-navigation-preview routes-map route-id url)]
    [:div {:data-testid (str "rf-causa-static-routes-sim-nav-"
                             (subs (pr-str route-id) 1))
           :style       {:padding       "10px 16px"
                         :margin        "8px 0 4px 24px"
                         :background    (:bg-1 tokens)
                         :border-left   (str "2px solid " (:cyan tokens))
                         :border-radius "2px"
                         :display       "grid"
                         :grid-template-columns "100px 1fr"
                         :row-gap       "6px"
                         :column-gap    "12px"
                         :font-size     "12px"}}
     (if (:unknown? pv)
       [:span {:data-testid "rf-causa-static-routes-sim-nav-unknown"
               :style       {:color       (:red tokens)
                             :font-family sans-stack
                             :font-size   "11px"
                             :grid-column "1 / -1"}}
        "Route-id " (pr-str route-id) " is not registered."]
       [:<>
        [:span {:style {:grid-column "1 / -1"
                        :color       (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size   "10px"
                        :font-style  "italic"
                        :margin-bottom "4px"}}
         "Hermetic preview — nothing is dispatched. Shows what would land "
         "in app-db if the host navigated to this route."]
        (field-row {:label  "Path"
                    :value  (or (:path pv) "—")
                    :mono?  true
                    :testid "rf-causa-static-routes-sim-nav-path"})
        (field-row {:label  "URL"
                    :value  (cond
                              (nil? url)       "—"
                              (= "" url)       "—"
                              (:matched? pv)   (str url "  (matched)")
                              :else            (str url "  (no match)"))
                    :mono?  true
                    :testid "rf-causa-static-routes-sim-nav-url"})
        (field-row {:label  "Params"
                    :value  (if (and (:matched? pv) (seq (:params pv)))
                              (pr-str (:params pv))
                              "—")
                    :mono?  true
                    :testid "rf-causa-static-routes-sim-nav-params"})
        (field-row {:label  ":on-match"
                    :value  (if (some? (:on-match pv))
                              (pr-str (:on-match pv))
                              "—")
                    :mono?  true
                    :testid "rf-causa-static-routes-sim-nav-on-match"})
        (field-row {:label  "DB slot"
                    :value  (pr-str (:db-slot pv))
                    :mono?  true
                    :testid "rf-causa-static-routes-sim-nav-db-slot"})
        (field-row {:label  "Slot shape"
                    :value  (with-out-str
                              (binding [*print-length* nil]
                                (println (pr-str (:slot-shape pv)))))
                    :mono?  true
                    :testid "rf-causa-static-routes-sim-nav-slot-shape"})])]))
