(ns re-frame.routing.navigate
  "`:rf.route/navigate` event for re-frame2 routing.

  Per Spec 012 §Navigation is an event. Programmatic navigation entry
  point: accepts a route-id (`[:rf.route/navigate :route/cart]`), a
  target-map (`{:url ...}` form), or the URL-string form. Honours
  :can-leave, :params/:query validation, scroll-strategy resolution,
  :query-retain merge, and the rule-3 no-op short-circuit.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `events/reg-event-fx :rf.route/navigate` call so a
  `:reload` re-wires it on a fresh registrar. Per the rf2-2yabr cohesion
  split: NAVIGATE-EVENT seam."
  (:require [re-frame.registrar :as registrar]
            [re-frame.routing.can-leave :as can-leave]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.scroll :as scroll]
            [re-frame.trace :as trace]))

(defn navigate-handler
  "`:rf.route/navigate` event-fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [db frame]} [_ target params opts :as event-vec]]
    ;; Per Spec 012 §Navigation is an event and §Fragments §Programmatic
    ;; navigation with fragments. Fragment may be supplied in opts
    ;; (`{:fragment "x"}`), on the target-map form (`{:url "/x"
    ;; :fragment "y"}`), or — for URL-string targets — embedded in the
    ;; URL itself; match-url surfaces the latter. Opts/target-map win
    ;; over a URL-embedded fragment.
    ;;
    ;; Per Spec 012 §Navigation tokens — stale-result suppression and
    ;; §The route slice: the slice ALWAYS carries :fragment and a
    ;; freshly-allocated :nav-token, and the runtime emits
    ;; :rf.route.nav-token/allocated as the cascade begins (rf2-d60go) —
    ;; the programmatic path matches the URL-driven path so async loaders
    ;; have a token to thread through stale-suppression.
    (let [opts (or opts {})
          {:keys [route-id path-params query-params matched-fragment]}
          (cond
            (keyword? target)
            {:route-id     target
             :path-params  (or params {})
             :query-params (:query opts {})}

            (and (map? target) (:url target))
            (let [m (registry/match-url (:url target))]
              {:route-id         (or (:route-id m) :rf.route/not-found)
               :path-params      (:params m {:url (:url target)})
               :query-params     (:query m {})
               :matched-fragment (:fragment m)}))
          fragment    (or (:fragment opts)
                          (and (map? target) (:fragment target))
                          matched-fragment)
          route-meta  (registrar/lookup :route route-id)
          ;; Per Spec 012 §Query strings and fragments: `:query-retain`
          ;; on the TARGET route names the keys that should be carried
          ;; through from the current `:rf.route/query` slice when the
          ;; caller did not supply them. The merge runs here (rather
          ;; than inside `route-url`, which is documented pure and
          ;; cannot read app-db) so apps that navigate by
          ;; `[:rf.route/navigate :route/cart]` from a search page
          ;; automatically preserve `?theme=dark` / `?locale=en`
          ;; without explicitly threading those keys through every call
          ;; site (rf2-u8t3s). Caller-supplied values always win.
          retain-keys  (:query-retain route-meta)
          retained     (when (seq retain-keys)
                         (select-keys (get-in db [:rf/runtime :routing :current :query])
                                      retain-keys))
          query-params (if (seq retained)
                         (merge retained query-params)
                         query-params)
          ;; Per Spec 012 §Param validation at the call site: the
          ;; event-boundary path `[:rf.route/navigate ...]` runs the
          ;; route's `:params` / `:query` schema BEFORE transitioning;
          ;; on failure the navigation is REJECTED — the route slice
          ;; at [:rf/runtime :routing :current] does not change, no URL
          ;; is pushed — and the runtime
          ;; emits `:rf.error/schema-validation-failure` (`:where
          ;; :event`). `route-url` raises the structured error on a
          ;; caller bug (`:rf.error/route-url-validation` /
          ;; `:rf.error/missing-route-param` / `:rf.error/no-such-route`);
          ;; we catch it, surface the canonical event-boundary error id,
          ;; and reject (the `::reject` sentinel short-circuits the cond
          ;; below). The reject is total — no slice write, no fallback URL
          ;; push — so a caller bug never desyncs the browser URL or
          ;; strands the slice in an invalid state.
          url (try (registry/route-url route-id path-params query-params fragment)
                   (catch #?(:clj Throwable :cljs :default) ex
                     (trace/emit-error! :rf.error/schema-validation-failure
                                        {:where    :event
                                         :route-id route-id
                                         :error    (or (ex-data ex)
                                                       {:message (ex-message ex)})
                                         :recovery :no-recovery})
                     ::reject))
          on-match-vec (vec (or (:on-match route-meta) []))
          ;; Spec 012 §Per-route data loading rule 3: a programmatic
          ;; navigation whose target id/params/query/fragment match the
          ;; current slice exactly is a no-op re-navigation — skip the
          ;; `:on-match` re-fire and the nav-token allocation. Mirrors the
          ;; URL-driven path's `identical-nav?` short-circuit so a
          ;; duplicate `[:rf.route/navigate :route/cart]` doesn't re-fetch
          ;; unchanged data.
          identical-nav? (routing-events/identical-route-target?
                           (get-in db [:rf/runtime :routing :current])
                           route-id path-params query-params fragment)]
      (cond
        ;; Caller-bug schema failure: reject (slice unchanged, no push).
        (= ::reject url)
        {}

        ;; Leave-guard check runs first (mirrors the URL-driven path,
        ;; where `maybe-block-navigation` precedes `url-change-fx`): a
        ;; blocked guard wins even over a rule-3 no-op so the pending-nav
        ;; protocol stays uniform across both entry points.
        :else
        (if-let [blocked (can-leave/maybe-block-navigation
                           db (or frame :rf/default)
                           event-vec url
                           (:bypass-leave-guard? opts))]
          blocked
          (if identical-nav?
            ;; Spec 012 §Per-route data loading rule 3: nothing relevant
            ;; changed — leave the slice and the standing nav-token as-is;
            ;; emit no allocation, fire no loaders, push no URL.
            {}
          (let [push-fx    (if (:replace? opts)
                             [:rf.nav/replace-url url]
                             [:rf.nav/push-url    url])
                ;; Per Spec 012 §Multi-frame routing nav-token allocation
                ;; bumps the per-frame counter; thread the new db through
                ;; the slice write below.
                [db' token] (routing-events/alloc-nav-token db)
                ;; Per Spec 012 §Scroll restoration: forward navigation
                ;; defaults to :top. Resolve from opts → route-meta →
                ;; default.
                to-route   (scroll/route-descriptor* route-id path-params query-params)
                strategy   (scroll/resolve-scroll-strategy route-meta opts :top)
                ;; Per Spec 012 §Multi-frame routing: scroll-position
                ;; lookup reads the per-frame map under
                ;; [:rf/runtime :routing :scroll-positions].
                scroll-fx  (scroll/scroll-fx-entry
                             {:strategy  strategy
                              :from      (scroll/route-descriptor
                                           (get-in db [:rf/runtime :routing :current]))
                              :to        to-route
                              :saved-pos (when (= :restore strategy)
                                           (scroll/lookup-scroll-position db url))
                              :fragment  fragment})
                capture-fx (scroll/capture-scroll-fx-entry db)]
            (trace/emit! :rf.event :rf.route.nav-token/allocated
                         {:route-id  route-id
                          :nav-token token})
            ;; Per rf2-dn26r: route lifecycle pair. Fires after the
            ;; nav-token allocation (the cascade-begin marker) so trace
            ;; consumers see {allocated → deactivated? → activated?} in
            ;; that order for any cross-route transition.
            (routing-events/emit-activation-traces!
              (get-in db [:rf/runtime :routing :current :id]) route-id)
            ;; Merge the new slice fields OVER the existing :current map at
            ;; [:rf/runtime :routing :current]. The sibling routing-runtime
            ;; keys ([:rf/runtime :routing :scroll-positions /
            ;; :scroll-positions-order / :nav-token-counter /
            ;; :pending-nav-counter]) are siblings (not nested under
            ;; :current), so the targeted `update-in` here leaves them
            ;; untouched.
            {:db (update-in db' [:rf/runtime :routing :current] merge
                            {:id         route-id
                             :params     path-params
                             :query      query-params
                             :fragment   fragment
                             :transition (if (seq on-match-vec) :loading :idle)
                             :error      nil
                             :nav-token  token})
             :fx (vec (concat (when capture-fx [capture-fx])
                              [push-fx]
                              (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                              ;; Per Spec 012 §Per-route data loading §2:
                              ;; transition :loading → :idle when the
                              ;; on-match drain completes. FIFO order means
                              ;; the settle dispatch runs after every
                              ;; on-match event already queued above.
                              (when (seq on-match-vec)
                                [[:dispatch [:rf.route.internal/settle-transition token]]])
                              (when scroll-fx [scroll-fx])))}))))))
