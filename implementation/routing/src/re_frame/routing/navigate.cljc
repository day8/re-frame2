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
  (:require [clojure.string :as str]
            [re-frame.registrar :as registrar]
            [re-frame.routing.can-leave :as can-leave]
            [re-frame.routing.events :as routing-events]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.scroll :as scroll]
            [re-frame.trace :as trace]))

;; Per Spec 012 §Navigation is an event — the arity contract:
;;   [:rf.route/navigate target]              ;; no path-params, no opts
;;   [:rf.route/navigate target params]       ;; params 2nd, opts absent
;;   [:rf.route/navigate target params opts]  ;; params 2nd, OPTS THIRD
;; The two trailing maps are positionally ambiguous to a reader
;; (rf2-1os1c): the likely mistake is dropping an OPTS-shaped map into
;; the PARAMS slot — `[:rf.route/navigate :route/x {:replace? true}]`
;; reads as "navigate with these path-params" but the author meant opts.
;; `opts-only-keys` names the keys that ONLY ever belong in the opts map.
(def ^:private opts-only-keys
  "Keys the trailing `opts` map recognises (Spec 012 §Navigation is an
  event) that are NOT path-param names. An occurrence of one of these in
  the PARAMS slot — and not as a declared path-param of the target route
  — is the classic params/opts swap and is rejected (rf2-1os1c)."
  #{:replace? :scroll :fragment :bypass-leave-guard?})

(defn- misplaced-opts-keys
  "Disambiguate the params/opts positional swap (rf2-1os1c). Returns the
  seq of opts-only keys present in the PARAMS slot that the target route
  does NOT declare as path-params — i.e. keys that can only sensibly be
  opts. Empty (falsy via `seq`) when `params` is clean. Route-id form
  only; the `{:url ...}` form has no positional params slot.

  Declared path-param names are read from the compiled pattern's
  `:names` (string capture names), so a route that legitimately captures
  a segment named `:scroll`/`:fragment`/… is never false-flagged."
  [route-meta params]
  (when (and (map? params) (seq params))
    (let [declared (into #{}
                         (map keyword)
                         (:names (:rf.route/compiled route-meta)))]
      (seq (filter (fn [k] (and (contains? opts-only-keys k)
                                (not (contains? declared k))))
                   (keys params))))))

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
          {:keys [route-id path-params query-params matched-fragment unmatched-url]}
          (cond
            (keyword? target)
            {:route-id     target
             :path-params  (or params {})
             :query-params (:query opts {})}

            (and (map? target) (:url target))
            ;; rf2-6t1xb: `match-url` THROWS on the keyword-interning DoS
            ;; guard (`:rf.error/route-too-many-keys`, rf2-3k3o7). Left
            ;; unhandled here the throw escapes the `:rf.route/navigate`
            ;; handler and CRASHES the event drain. `match-url-fail-closed`
            ;; catches it (and any other match-url throw) → NIL match +
            ;; `:throw-reason`, so an over-capped `{:url ...}` target fails
            ;; closed to `:rf.route/not-found` — the same fail-closed path
            ;; as a bare miss, mirroring the URL-driven entry point.
            (let [{:keys [match throw-reason]}
                  (registry/match-url-fail-closed (:url target))]
              ;; Spec 012 §Target form — URL-string (escape hatch): an
              ;; unmatched URL-string resolves to `:rf.route/not-found`
              ;; with the URL in `:params`. `:unmatched-url` flags this
              ;; no-match fallback so the commit below pushes the
              ;; REQUESTED url VERBATIM rather than `route-url` of the
              ;; not-found route — keeping the address bar on the URL the
              ;; caller aimed at, consistent with the URL-driven not-found
              ;; path (`url-change-fx`), which never pushes a fabricated
              ;; `/404` (rf2-0zr2o). nil ⇒ the URL matched a route and the
              ;; canonical `route-url` round-trip drives the push.
              ;;
              ;; rf2-6t1xb: on a throw (or any miss) `:params` carries the
              ;; requested url; a throw also stamps the `:reason`
              ;; discriminator (`:too-many-keys` / `:match-error`),
              ;; uniform with the URL-driven not-found slice.
              {:route-id         (or (:route-id match) :rf.route/not-found)
               :path-params      (if throw-reason
                                   {:url (:url target) :reason throw-reason}
                                   (:params match {:url (:url target)}))
               :query-params     (:query match {})
               :matched-fragment (:fragment match)
               :unmatched-url    (when-not (:route-id match) (:url target))}))
          fragment    (or (:fragment opts)
                          (and (map? target) (:fragment target))
                          matched-fragment)
          route-meta  (registrar/lookup :route route-id)
          ;; rf2-1os1c: catch the params/opts positional swap at the
          ;; event boundary. Route-id form only — the `{:url ...}` form
          ;; has no positional params slot.
          misplaced   (when (keyword? target)
                        (misplaced-opts-keys route-meta params))
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
          ;; Cross-route coercion-class carry (rf2-b3rzz): retained
          ;; values are pulled from the CURRENT route's `:query` slice
          ;; VERBATIM — already coerced to that route's class (a keyword
          ;; via `[:enum ...]`, an int via `:int`, …) — and carried into
          ;; the target unchanged. They are NOT re-coerced against the
          ;; target route's `:query` schema. The target's `:query`
          ;; validator still runs at the call site (below + in
          ;; `route-url`), so a class mismatch (current route typed the
          ;; key `[:enum :a :b]`, target types it `:string`) surfaces as
          ;; a validation failure rather than silently desyncing. The
          ;; contract: authors keep a `:query-retain` key's type
          ;; CONSISTENT across every route that retains it (same trust
          ;; class as the `:query` schema being author-named intent).
          ;; Re-coercion was considered + rejected: retained values are
          ;; runtime values, not URL strings, so re-running
          ;; string→class coercion is ill-typed; verbatim-carry keeps the
          ;; merge a pure `select-keys`. See Spec 012 §Query strings and
          ;; fragments §:query-retain cross-route coercion class.
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
          ;;
          ;; Unmatched URL-string target (rf2-0zr2o): when the `{:url ...}`
          ;; form missed every route, `route-id` fell back to
          ;; `:rf.route/not-found`. We do NOT call `route-url` for that id —
          ;; that would build the not-found route's literal `:path` (`/404`)
          ;; and push it, rewriting the address bar away from the URL the
          ;; caller aimed at (and throwing `:no-such-route` when no
          ;; not-found route is registered). Instead the REQUESTED url is
          ;; pushed verbatim, so the address bar keeps it and the not-found
          ;; view renders — matching the URL-driven not-found path
          ;; (`url-change-fx`), which already keeps the requested URL (it
          ;; changed via link/popstate, so that path emits no push). Per
          ;; Spec 012 §Target form — URL-string.
          url (if unmatched-url
                unmatched-url
                (try (registry/route-url route-id path-params query-params fragment)
                     (catch #?(:clj Throwable :cljs :default) ex
                       ;; rf2-7d30s — stamp the in-flight cascade's `:frame`
                       ;; (destructured from the cofx at the handler top) so
                       ;; this navigate-reject lands in the emitting frame's
                       ;; epoch (epoch capture buffers only frame-tagged
                       ;; traces) AND so the SSR error-projection listener
                       ;; can map it to a 4xx for the correct frame under
                       ;; concurrent server frames — not the single-frame
                       ;; fallback's guess.
                       (trace/emit-error! :rf.error/schema-validation-failure
                                          (cond-> {:where    :event
                                                   :route-id route-id
                                                   :error    (or (ex-data ex)
                                                                 {:message (ex-message ex)})
                                                   :recovery :no-recovery}
                                            frame (assoc :frame frame)))
                       ::reject)))
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
        ;; rf2-1os1c: params/opts positional swap. An opts-only key
        ;; (`:replace?` / `:scroll` / `:fragment` / `:bypass-leave-guard?`)
        ;; sits in the PARAMS slot and is not a declared path-param of
        ;; the target route — the author almost certainly meant to pass
        ;; it as the THIRD `opts` arg. Reject loudly (caller bug; slice
        ;; unchanged, no push) so the swap fails at the event boundary
        ;; rather than navigating with a wrong URL or silently dropping
        ;; the intended opts. Per Spec 012 §Navigation is an event.
        (seq misplaced)
        (do
          (trace/emit-error! :rf.error/navigate-arity-misuse
                             (cond-> {:where    :event
                                      :route-id route-id
                                      :reason   (str "opts-shaped key(s) "
                                                     (str/join ", " (map pr-str misplaced))
                                                     " appeared in the PARAMS slot (2nd arg) of "
                                                     "[:rf.route/navigate " route-id " params opts]. "
                                                     "These belong in the OPTS map (3rd arg): "
                                                     "[:rf.route/navigate " route-id " {} {...opts}]. "
                                                     "Navigation rejected.")
                                      :keys     (vec misplaced)
                                      :recovery :no-recovery}
                               ;; rf2-7d30s — frame-attribute the reject so it
                               ;; lands in the emitting frame's epoch.
                               frame (assoc :frame frame)))
          {})

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
              ;; Spec 012 §Route-not-found rule 3 (rf2-0zr2o): when an
              ;; unmatched URL-string target resolved to
              ;; `:rf.route/not-found` and no such route is registered,
              ;; emit `:rf.warning/no-not-found-route` — mirroring the
              ;; URL-driven path (`url-change-fx`), which warns and still
              ;; commits the not-found slice. The two not-found entry
              ;; points now agree: warn (don't reject), keep the requested
              ;; URL, render the not-found view.
              (when (and unmatched-url (nil? route-meta))
                ;; rf2-7d30s — frame-attribute the warning (mirrors the
                ;; URL-driven sibling in url_change.cljc) so it's visible
                ;; in the emitting frame's epoch / Xray.
                (trace/emit! :warning :rf.warning/no-not-found-route
                             (cond-> {:url unmatched-url}
                               frame (assoc :frame frame))))
              ;; rf2-g8tzb / commit-navigation: nav-token alloc, the
              ;; allocated/activation traces, the slice publish, and the
              ;; fx assembly are the shared commit shape. The programmatic
              ;; path is the only one that drives the browser URL, so it
              ;; passes `push-fx`.
              (routing-events/commit-navigation
                db
                {:id         route-id
                 :params     path-params
                 :query      query-params
                 :fragment   fragment
                 :transition (if (seq on-match-vec) :loading :idle)}
                on-match-vec
                {:prev-id    (get-in db [:rf/runtime :routing :current :id])
                 :capture-fx capture-fx
                 :scroll-fx  scroll-fx
                 :push-fx    push-fx})))))))
