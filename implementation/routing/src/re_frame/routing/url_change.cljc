(ns re-frame.routing.url-change
  "URL-driven navigation: the shared full-rewrite path
  (`url-change-fx`) plus the `:rf.route/transitioned` and
  `:rf.route/handle-url-change` events for re-frame2 routing.

  Per Spec 012 §URL changes are events. `:rf.route/transitioned`
  (forward nav, default scroll `:top`) and `:rf.route/handle-url-change`
  (popstate / initial / SSR, default scroll `:restore`) share
  `url-change-fx`. The fragment-only branch (Spec 012 §Fragments rules
  3-4) ALSO lives in `url-change-fx`, so both events honour it —
  popstate / Back-Forward to a same-page anchor must not allocate a new
  nav-token or re-fire `:on-match` (rf2-8oxj6).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `events/reg-event-fx` calls so a `:reload`
  re-wires them on a fresh registrar. Per the rf2-2yabr cohesion split:
  URL-CHANGE-EVENTS seam."
  (:require [re-frame.registrar :as registrar]
            [re-frame.routing.can-leave :as can-leave]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.scroll :as scroll]
            [re-frame.trace :as trace]))

(defn- fragment-only-fx
  "Spec 012 §Fragments rules 1-4: the new URL differs from the current
  `:rf/route` slice ONLY in its `#fragment`. Update `:fragment`, emit the
  `:rf.route/fragment-changed` op trace (rf2-cj9fn), and return the cofx
  map — WITHOUT allocating a fresh nav-token (rule 3) or re-firing
  `:on-match` (rule 4). The canonical op-name says what fires it (only a
  `#fragment` differed) and disambiguates from the runtime event
  `:rf.route/transitioned`, which fires on every URL transition. The
  full URL transition path never emits this op and never coincides with
  a `:rf.route.nav-token/allocated` on the same drain. Consumers carry
  `:prev-fragment` / `:next-fragment` in `:tags`. Scroll-capture (for the
  position the user is leaving) still rides along."
  [db prev next-fragment]
  (trace/emit! :rf.event :rf.route/fragment-changed
               {:route-id      (:id prev)
                :prev-fragment (:fragment prev)
                :next-fragment next-fragment})
  (let [capture-fx (scroll/capture-scroll-fx-entry db)]
    (cond-> {:db (assoc-in db [:rf/route :fragment] next-fragment)}
      capture-fx (assoc :fx [capture-fx]))))

(defn- url-change-fx
  "Pure helper: given db + url + default scroll strategy (+ optional
  `:frame` to carry on the no-such-handler trace), return the cofx map
  `{:db :fx}` for a URL-driven full slice rewrite. Performs the match-url
  lookup, allocates a fresh nav-token, computes the scroll fx entry, and
  emits the trace events (:rf.warning/no-not-found-route,
  :rf.warning/malformed-url, :rf.error/no-such-handler,
  :rf.route.nav-token/allocated).

  Per Spec 012 §URL changes are events §Route-not-found §Per-route data
  loading §Scroll restoration §Multi-frame routing. The slice always
  carries the full seven-key shape (rf2-d60go).

  Three fallback shapes feed `:rf.route/not-found` (rf2-4ic0f):

   - bare miss (`{:url url}`) — `match-url` returned nil and the URL
     percent-encoding decoded cleanly;
   - validation fail (`{:url url :reason :validation}`) — a route's
     pattern matched but its `:params` / `:query` schema rejected the
     parsed values (rf2-ug2m1);
   - malformed URL (`{:url url :reason :malformed-url}`) — any of the
     URL's path captures, query keys/values, or `#fragment` failed to
     %-decode. The `:reason` discriminator lets per-route error UIs
     and SSR projections branch on the cause."
  [db url default-scroll frame]
  (let [m                 (registry/match-url url)
        ;; rf2-4ic0f: when match-url returns nil, discriminate the
        ;; bare-miss case from the malformed-URL case via the public
        ;; `malformed-url?` predicate. The predicate scans the URL
        ;; once; we run it only when match-url already missed (the
        ;; happy path pays nothing).
        malformed?        (and (nil? m) (registry/malformed-url? url))
        ;; Malformed URLs surface no fragment in the slice — the
        ;; fragment was the (or potentially the) decode-fail site.
        fragment          (when-not malformed? (:fragment m))
        ;; Spec 012 §Fragments rules 3-4: when the new URL differs from
        ;; the current slice ONLY in its `#fragment` (same route-id,
        ;; params, query) the runtime updates `:fragment`, emits the
        ;; `:rf.route/fragment-changed` op trace, and short-circuits
        ;; BEFORE allocating a fresh nav-token or re-firing `:on-match`.
        ;; This branch lives in the shared helper so EVERY URL-driven
        ;; event honours it — both forward nav (`:rf.route/transitioned`)
        ;; AND popstate / initial / SSR (`:rf.route/handle-url-change`).
        ;; Back/Forward to a same-page anchor must not re-fetch route
        ;; data (rf2-8oxj6).
        prev              (:rf/route db)
        fragment-only?    (and prev m
                               (= (:id prev)     (:route-id m))
                               (= (:params prev) (:params m))
                               (= (:query prev)  (:query m))
                               (not= (:fragment prev) fragment))
        matched?          (some? m)
        validation-fail?  (:validation-failed? m)
        fallback?         (or (not matched?) validation-fail?)
        route-id          (if fallback? :rf.route/not-found (:route-id m))
        params            (cond
                            malformed?       {:url url :reason :malformed-url}
                            validation-fail? {:url url :reason :validation}
                            (not matched?)   {:url url}
                            :else            (:params m))
        query             (if fallback? {} (:query m))
        ;; Spec 012 §Per-route data loading rule 3: a re-navigation whose
        ;; resolved id/params/query/fragment match the current slice
        ;; exactly is a complete no-op — no new nav-token, no `:on-match`
        ;; re-fire, no scroll. Computed AFTER fallback resolution so two
        ;; identical not-found URLs (or two identical validation misses)
        ;; also skip. Sits alongside `fragment-only?` (the id/params/query
        ;; equal, fragment-changed sibling); the two are mutually
        ;; exclusive (fragment-only requires the fragment to differ).
        identical-nav?    (routing-events/identical-route-target? prev route-id params query fragment)
        route-meta        (registrar/lookup :route route-id)
        on-match-vec      (vec (or (:on-match route-meta) []))
        transition        (if (seq on-match-vec) :loading :idle)
        [db' token]       (routing-events/alloc-nav-token db)
        to-route          (cond-> {:id route-id}
                            (seq params) (assoc :params params)
                            (seq query)  (assoc :query  query))
        strategy          (scroll/resolve-scroll-strategy route-meta nil default-scroll)
        capture-fx        (scroll/capture-scroll-fx-entry db)
        scroll-fx         (scroll/scroll-fx-entry
                            {:strategy  strategy
                             :from      (scroll/route-descriptor (:rf/route db))
                             :to        to-route
                             :saved-pos (when (= :restore strategy)
                                          (scroll/lookup-scroll-position db url))
                             :fragment  fragment})]
    (cond
      ;; Spec 012 §Per-route data loading rule 3: nothing relevant
      ;; changed — skip the dispatch entirely. No nav-token allocation,
      ;; no `:on-match` drain, no scroll fx, no slice rewrite. The
      ;; previously-allocated token stands. This is the "redundant
      ;; navigation to the already-active URL" no-op (clicking the
      ;; current nav link, popstate to the current URL).
      identical-nav?
      {:db db}

      ;; Spec 012 §Fragments rules 3-4 (rf2-8oxj6): short-circuit BEFORE
      ;; the nav-token allocation / on-match drain below. Honoured on
      ;; both `:rf.route/transitioned` and `:rf.route/handle-url-change`
      ;; (popstate) because the branch lives in the shared helper.
      fragment-only?
      (fragment-only-fx db prev fragment)

      :else
      (do
        ;; rf2-4ic0f: structured telemetry for the malformed-URL case so
        ;; SSR error projections, security dashboards, and pair-tools can
        ;; surface the failure independently of the generic miss trace.
        ;; Emitted alongside the regular `:rf.error/no-such-handler` event
        ;; below — the discriminator is the `:reason :malformed-url` slot
        ;; on the slice's `:params`.
        (when malformed?
          (trace/emit! :warning :rf.warning/malformed-url
                       (cond-> {:url url}
                         frame (assoc :frame frame))))
        ;; Spec 012 §Route-not-found §3: emit :rf.warning/no-not-found-route
        ;; when the unmatched-URL path resolves to :rf.route/not-found AND
        ;; no such route is registered. Tools / AI scaffolds key off this.
        (when (and fallback? (nil? route-meta))
          (trace/emit! :warning :rf.warning/no-not-found-route
                       {:url url}))
        ;; :rf.error/no-such-handler discriminates from event / frame
        ;; handler misses by :kind :route. The :frame tag (present when the
        ;; caller threads it in — `:rf.route/handle-url-change`) lets the
        ;; SSR error-projection listener attribute the trace per-frame.
        ;; rf2-4ic0f: include `:reason :malformed-url` when applicable so
        ;; the structured error is uniform across the trace + the slice.
        (when fallback?
          (trace/emit-error! :rf.error/no-such-handler
                             (cond-> {:url url
                                      :kind :route
                                      :recovery :replaced-with-default}
                               frame      (assoc :frame frame)
                               malformed? (assoc :reason :malformed-url))))
        (trace/emit! :rf.event :rf.route.nav-token/allocated
                     {:route-id  route-id
                      :nav-token token})
        ;; Per rf2-dn26r: route lifecycle pair. Fires after the nav-token
        ;; allocation so trace consumers see {allocated → deactivated? →
        ;; activated?} in that order for any cross-route transition.
        (routing-events/emit-activation-traces! (get-in db [:rf/route :id]) route-id)
        ;; Merge slice fields over the existing :rf/route map — preserves
        ;; the per-frame routing-runtime keys nested under :rf/route
        ;; (:scroll-positions / :scroll-positions-order /
        ;; :nav-token-counter / :pending-nav-counter — rf2-3ib8h).
        {:db (update db' :rf/route merge
                     {:id         route-id
                      :params     params
                      :query      query
                      :fragment   fragment
                      :transition transition
                      :error      nil
                      :nav-token  token})
         :fx (vec (concat (when capture-fx [capture-fx])
                          (mapv (fn [ev] [:dispatch ev]) on-match-vec)
                          ;; Per Spec 012 §Per-route data loading §2:
                          ;; settle :loading → :idle after the on-match
                          ;; drain. FIFO order: settle runs after every
                          ;; on-match event already queued above.
                          (when (seq on-match-vec)
                            [[:dispatch [:rf.route.internal/settle-transition token]]])
                          (when scroll-fx [scroll-fx])))}))))

(defn transitioned-handler
  "`:rf.route/transitioned` event-fx handler. Registered by the façade
  so a `:reload` re-wires it on a fresh registrar. Per Spec 012 §URL
  changes are events / §Fragments. Forward nav (link click /
  programmatic push). After the leave-guard check, delegate to the
  shared `url-change-fx`, which distinguishes a fragment-only change
  (update :fragment, emit :rf.route/fragment-changed, no nav-token /
  no :on-match — rf2-cj9fn) from a full slice rewrite. Default scroll
  strategy for forward nav is `:top` per Spec 012 §Scroll restoration;
  popstate / initial / SSR routes through `:rf.route/handle-url-change`
  (default `:restore`)."
  [{:keys [db frame]} [_ url opts :as event-vec]]
  (let [opts    (or opts {})
        blocked (can-leave/maybe-block-navigation
                  db (or frame :rf/default)
                  event-vec url
                  (:bypass-leave-guard? opts))]
    (or blocked
        (url-change-fx db url :top nil))))

(defn handle-url-change-handler
  "`:rf.route/handle-url-change` event-fx handler. Registered by the
  façade so a `:reload` re-wires it on a fresh registrar. Per Spec 012
  §URL changes are events — popstate, initial load, SSR. Delegates to
  the shared `url-change-fx`, which honours the fragment-only
  short-circuit (Spec 012 §Fragments rules 3-4): a Back/Forward to a
  same-page `#fragment` updates :fragment WITHOUT allocating a new
  nav-token or re-firing :on-match (rf2-8oxj6). The default scroll
  strategy is `:restore` so the saved position trumps. `:frame` is
  threaded through so the SSR error-projection listener can attribute
  the :no-such-handler trace per-frame."
  [{:keys [db frame]} [_ url opts :as event-vec]]
  (let [opts    (or opts {})
        blocked (can-leave/maybe-block-navigation
                  db (or frame :rf/default)
                  event-vec url
                  (:bypass-leave-guard? opts))]
    (or blocked
        (url-change-fx db url :restore frame))))
