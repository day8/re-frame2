(ns re-frame.routing.url-change
  "The ONE navigation pipeline, `navigate*`, and the URL-driven door.

  Per Spec 012 §The one planning pipeline, every navigation door runs stages
  1-7 through `navigate*`: validate, resolve, classify, decide leave, decide
  entry, plan, commit. Each door's handler is a thin wrapper that supplies
  its inputs as data —

    - `:rf.route/navigate` (`re-frame.routing.navigate`) — the caller's
      request, a `route-link` click's `{:url <href>}` among them, cause
      `:navigate`;
    - `:rf.route/handle-url-change` (below) — a URL the address bar has
      ALREADY moved to, standing for four causes (`:link` from a host that
      pushed before dispatching, `:popstate`, `:initial`, `:ssr`), resolved
      per dispatch by `url-change-cause`.

  The inputs are the request (source plus policy), the cause, `url-driven?`
  and the default scroll strategy. `url-driven?` says the address bar has
  already moved: the URL is a location, not a reference, so it is neither
  refused as external nor reduced to an app URL, nothing is pushed after the
  commit, and a blocked or denied transition restores the address bar by
  replace. Stage 2 forks on the request's SOURCE KIND — a named address, a
  raw URL, or an in-place edit — never on which event arrived, so one URL
  string means one thing at every door: a raw URL is user input, and a URL
  that matches a route but fails its `:params` / `:query` schema renders
  `:rf.route/not-found` with `:reason :validation` wherever it arrives.

  Internal namespace; the public facade is `re-frame.routing`. The facade
  owns the `events/reg-event` calls so a `:reload` re-wires them on a fresh
  registrar."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy :as rf.privacy]
            [re-frame.privacy.url :as rf.privacy.url]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing.address :as rf.routing.address]
            [re-frame.routing.decisions :as rf.routing.decisions]
            [re-frame.routing.events :as rf.routing.events]
            [re-frame.routing.plan :as rf.routing.plan]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.resolve :as rf.routing.resolve]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.routing.url :as rf.routing.url]
            [re-frame.trace :as rf.trace]))

;; ---- route-miss telemetry ----------------------------------------------------

(defn- route-miss-tags
  "The `:rf.error/no-such-handler` `:kind :route` tag map for a raw URL that
  resolved to `:rf.route/not-found` — built ONCE and fanned along BOTH error
  axes, so the production record and the dev trace cannot disagree about the
  same miss.

  `:kind :route` discriminates this miss from the event / frame handler
  misses (Spec 009 §Error event catalogue — `:kind` is mandatory on every
  `:rf.error/no-such-handler` emit) and is what the SSR default projector
  gates its `404` arm on (Spec 011 §Default projector). The `:reason` (a
  `match-url` throw pre-empts the malformed scan, so the two are mutually
  exclusive — throw-reason wins) keeps the structured error uniform across
  the trace and the route slice. The `:frame` tag lets the SSR
  error-projection listener attribute the miss per-frame.

  EP-0015: `:rf.error/no-such-handler` is a production-survivable /
  off-box-observable category EP-0015 requires to FAIL CLOSED, and a
  route-miss URL is the class most likely to carry secret carriers
  (`?token=…`, `#access_token=…`, a declared path segment). The URL is
  projected HERE, before the tags reach either axis, so the redaction covers
  the always-on production record exactly as it covers the dev trace.
  `rf.routing.resolve/redact-declared-url` first replaces what the
  `:sensitive` declaration of the route it resolves to names — for an
  ambiguous route, which has no one declaration, every segment its
  registrations capture — and `rf.privacy.url/redact-url-tag` then scrubs
  every query / fragment carrier VALUE, keeping the rest of the path and the
  `:reason`."
  [{:keys [url frame throw-reason malformed?]}]
  (-> (cond-> {:url      url
               :kind     :route
               :recovery :replaced-with-default}
        frame        (assoc :frame frame)
        throw-reason (assoc :reason throw-reason)
        malformed?   (assoc :reason :malformed-url))
      (update :url rf.routing.resolve/redact-declared-url)
      (rf.privacy.url/redact-url-tag :url)))

(defn- emit-route-miss-error!
  "Fan the route miss out on the ALWAYS-ON error axis — the
  production-survivable sibling of the dev `rf.trace/emit-error!` emitted
  alongside it.

  `rf.trace/emit-error!` alone is gated on `rf.interop/debug-enabled?`. Under
  the documented production gate (`-Dre-frame.debug=false`, `:advanced` +
  `goog.DEBUG=false`) nothing would buffer, the SSR error projector would
  have nothing to project, and a request for an unroutable URL would answer
  HTTP 200 — a soft 404. Spec 009's catalogue classifies
  `:rf.error/no-such-handler` as always-on with `:kind :route`.

  The record is the general NON-EVENT union shape
  `{:error <kw> :frame <id-or-nil> :time <ms> + flat category keys}` — this
  is not a dispatched-event failure, so it rides `dispatch-error-record!`
  rather than the event-centric `dispatch-on-error!`. `tags` arrives already
  redacted (see [[route-miss-tags]]): the record carries the category,
  `:kind :route`, `:frame`, `:time`, `:recovery` and the structured
  `:reason` / redacted `:url` — never a raw query / fragment carrier value or
  declared path segment, and never an app-db slice. The SSR
  `error-emit-projection-listener` lifts every non-`:error` slot onto `:tags`
  generically, so `[:tags :kind]` reaches the projector's `404` gate
  identically on both axes.

  Reached through the `:error-emit/dispatch-error-record` late-bind hook —
  routing ships above core's require graph. A no-op when the hook is
  unbound. Returns nil."
  [tags]
  (when-let [dispatch-error-record!
             (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      (assoc tags
             :error :rf.error/no-such-handler
             :time  (rf.interop/now-ms))))
  nil)

(defn- emit-route-miss!
  "Every route-miss signal for a raw URL that resolved to
  `:rf.route/not-found`, whichever door carried it: the always-on
  `:rf.error/no-such-handler` record FIRST (matching
  `error-emit/emit-error-both!`'s axis order), then on the dev trace bus the
  shared fail-closed warnings (`:rf.warning/malformed-url` for a `match-url`
  throw or a malformed %-encoding; `:rf.warning/no-not-found-route` when no
  `:rf.route/not-found` route is registered — Spec 012 §Route-not-found §3)
  and the same `:rf.error/no-such-handler`. One redacted tag map feeds both
  axes, and every warning carries its already-projected URL."
  [{:keys [url frame throw-reason malformed? route-meta]}]
  (let [tags (route-miss-tags {:url          url
                               :frame        frame
                               :throw-reason throw-reason
                               :malformed?   malformed?})]
    (emit-route-miss-error! tags)
    (rf.routing.plan/emit-intents!
      (conj (rf.routing.plan/fallback-telemetry-intents
              {:throw-reason  throw-reason
               :malformed?    malformed?
               :no-not-found? (nil? route-meta)
               :url           (:url tags)
               :frame         frame})
            [:emit-error :rf.error/no-such-handler tags]))))

;; ---- stage 2 — resolve the target, forked on source kind ---------------------

(defn- route-schema-sensitive?
  "True iff the route's `:params` OR `:query` Malli schema declares ANY
  `:sensitive?` slot. Decided by the SAME shared schema-aware seam every
  off-schemas validation-failure emit site routes through
  (`:schemas/redact-validation-tags`): routing the route's schema and an
  empty tag map through it stamps `:sensitive? true` exactly when the schema
  is sensitive. With the schemas artefact absent the hook is unbound and this
  returns false — no schema to walk, nothing to redact."
  [route-meta]
  (boolean
    (when-let [redact (rf.late-bind/get-fn :schemas/redact-validation-tags)]
      (some (fn [slot]
              (when-let [schema (get route-meta slot)]
                (true? (:sensitive? (redact schema {})))))
            [:params :query]))))

(defn- redact-route-error-tags
  "Elide the `:error` slot of a named address's
  `:rf.error/schema-validation-failure` trace when the route's `:params` /
  `:query` schema declares any `:sensitive?` slot (AI/MCP egress + logs
  threat model).

  `:error` carries the `ex-data` of the `route-url` throw — the raw params the
  caller supplied and the Malli explainer reproducing them — and route params
  can be document ids or tokens. The throw is STRUCTURAL (from `route-url`,
  not a per-slot Malli walk at this emit point), so the shared
  `redact-validation-tags` seam cannot path-target it: the WHOLE slot elides
  to `:rf/redacted` and `:sensitive? true` is stamped. `:where` / `:route-id`
  / `:recovery` carry no user value and stay; the SSR error projector keys
  only off the category and never echoes `:error`. A route with no
  `:sensitive?` slot rides `:error` verbatim."
  [tags route-meta]
  (if (and (contains? tags :error)
           (route-schema-sensitive? route-meta))
    (assoc tags :error rf.privacy/redacted-sentinel :sensitive? true)
    tags))

(defn- resolve-address
  "Stage 2 for a NAMED ADDRESS (`:to`) or an IN-PLACE EDIT (neither `:to` nor
  `:url`). Both are caller-authored, so a schema failure is the caller's bug:
  `route-url` validates the resolved facts, and its throw emits
  `:rf.error/schema-validation-failure` (`:where :event`) and rejects —
  `{:effects {}}`, slice unchanged, nothing pushed.

  A destination is taken LITERALLY (EP-0037 R5): omitted `:params` /
  `:query` / `:fragment` are empty and no ambient current-route query folds
  into it — an application that carries global URL state spells it as a pure
  function over the address before dispatch (Spec 012 §Carrying query state
  across routes). An in-place edit PATCHES the current location: route and
  path params carry, `:query` replaces wholesale, `:query-merge` folds its
  deltas over the current query (a nil member removes a key) and an omitted
  `:fragment` carries. Presence, not truthiness, discriminates throughout, so
  a present-but-nil `:query` clears like `{}`. The deltas are SPELLED the way
  the current route's URL spells them before they fold, so a delta overwrites
  or removes the entry it names instead of landing beside it.

  The facts lower through the ResolvedTarget seam
  (`rf.routing.resolve/resolved-target`), which fills the route's
  `:query-defaults`, drops nil-valued query keys and normalises the fragment —
  the same rule every door's target passes through — before the URL is built
  from them, so the guards, stage 3, the plan and the commit read one value.
  Returns `{:source :target}`, the target carrying its `:url`."
  [{:keys [current frame request]}]
  (let [to?        (contains? request :to)
        route-id   (if to? (:to request) (:route-id current))
        route-meta (rf.registrar/lookup :route route-id)
        query      (cond
                     to?                        (:query request)
                     (contains? request :query) (:query request)
                     :else                      (:query current))
        query      (or (if-let [deltas (:query-merge request)]
                         (merge query (rf.routing.registry/canonical-query route-meta deltas))
                         query)
                       {})
        {:keys [params fragment] :as target}
        (rf.routing.resolve/resolved-target
          {:route-id route-id
           :params   (if to? (:params request {}) (or (:params current) {}))
           :query    query
           :fragment (cond
                       (contains? request :fragment) (:fragment request)
                       to?                           nil
                       :else                         (:fragment current))})]
    (try
      {:source (if to?
                 (rf.routing.address/extract-address request)
                 (select-keys request rf.routing.address/edit-keys))
       :target (assoc target :url (rf.routing.registry/route-url
                                    {:to       route-id
                                     :params   params
                                     :query    (:query target)
                                     :fragment fragment}))}
      (catch #?(:clj Throwable :cljs :default) ex
        (rf.trace/emit-error! :rf.error/schema-validation-failure
                              (-> (cond-> {:where    :event
                                           :route-id route-id
                                           :error    (or (ex-data ex)
                                                         {:message (ex-message ex)})
                                           :recovery :no-recovery}
                                    frame (assoc :frame frame))
                                  (redact-route-error-tags route-meta)))
        {:effects {}}))))

(defn- with-fragment
  "`url` with its `#fragment` replaced by `fragment`, percent-encoded exactly
  as `route-url` emits one — or removed when `fragment` is nil."
  [url fragment]
  (let [hash-idx (.indexOf #?(:clj ^String url :cljs ^string url) "#")
        base     (if (neg? hash-idx) url (subs url 0 hash-idx))]
    (if (some? fragment)
      (str base "#" (rf.routing.url/url-encode fragment))
      base)))

(defn- resolve-raw-url
  "Stage 2 for a RAW URL (`{:url …}`) — user input, whichever door carried
  it. Returns `{:effects {}}` for a refused URL, else the shared
  `rf.routing.resolve/url-resolution` (its `:target`, `:fallback?`,
  `:throw-reason` and `:malformed?`) plus the plan `:source`.

  A REQUESTED reference (`url-driven?` false) is first classed by origin: an
  external one fails closed (open-redirect defence, Spec 012 §Open-redirect
  fail-closed classification) — `:rf.route/external-url-requested`, no push,
  no slice rewrite — and an in-app one is reduced to the app URL it names
  (`rf.routing.decisions/request-app-url`; Spec 012 §App references and
  browser addresses). An already-moved URL is the location itself and is
  matched as it stands.

  An explicit `:fragment` overrides the URL-embedded one (nil clears it) on
  that ONE effective URL, so the not-found `:params`, the guards, the history
  push and the slice agree. The URL then resolves through the one extraction
  every raw URL reaches: a match commits its route, and a miss, a malformed
  %-encoding, a `match-url` throw and a `:params` / `:query` schema failure
  each normalise to `:rf.route/not-found` under the shared `:reason`
  vocabulary."
  [{:keys [rdb frame request url-driven?]}]
  (let [requested (:url request)]
    (if (and (not url-driven?) (rf.routing.url/external-url? requested))
      (do
        (rf.trace/emit! :rf.event :rf.route/external-url-requested
                        (cond-> {:url requested}
                          frame (assoc :frame frame)))
        {:effects {}})
      (let [explicit? (contains? request :fragment)
            fragment  (rf.routing.plan/normalize-fragment (:fragment request))
            url       (cond-> (if url-driven?
                                requested
                                (rf.routing.decisions/request-app-url rdb requested))
                        explicit? (with-fragment fragment))]
        (cond-> (assoc (rf.routing.resolve/url-resolution url) :source {:url requested})
          ;; A fallback target carries no fragment of its own.
          explicit? (assoc-in [:target :fragment] fragment))))))

;; ---- stages 6-7 — the two commits --------------------------------------------

(defn- fragment-only-fx
  "Spec 012 §Fragments rules 1-4: the target differs from the current slice
  ONLY in its `#fragment`. Writes `:fragment` alone — every other slice field
  and routing sibling stays byte-for-byte, the standing nav-token stays
  current so an already-running loader remains eligible, no `:on-match`
  re-fires and no resource plan runs — and emits the
  `:rf.route/fragment-changed` op with the `:frame` stamp. Its effects, in
  order, are the capture of the LEAVING scroll position, the door's push /
  replace (none when the address bar has already moved) and the resolved
  `:rf.nav/scroll`, which `pushState` and popstate never perform for a
  fragment natively; `:scroll false` leaves `scroll-fx` nil."
  [{:keys [rdb current fragment frame capture-fx push-fx scroll-fx]}]
  (rf.trace/emit! :rf.event :rf.route/fragment-changed
                  {:route-id      (:route-id current)
                   :prev-fragment (:fragment current)
                   :next-fragment fragment
                   :frame         frame})
  (let [fx (filterv some? [capture-fx push-fx scroll-fx])]
    (cond-> {:rf.db/runtime (assoc-in rdb [:rf.runtime/routing :current :fragment] fragment)}
      (seq fx) (assoc :fx fx))))

(defn- commit-activation
  "Stages 6-7 for a FULL transition. Builds the route plan (EP-0037 R0) — the
  source, the cause, the resolved target, and the fail-loud parent-to-leaf
  branch walked once — reports a not-found raw URL as a route miss, emits ONE
  `:rf.route/planned` trace (the plan's diagnostic projection, its URL
  redacted and its `:params` / `:query` reduced to key sets by
  `rf.routing.resolve/plan-trace-tags`), and hands the plan's target to
  `commit-navigation`: the nav-token publish, the activation traces, the
  resource plan and the effects, with the door's push after the slice write
  (Spec 012 §State-first, URL-second update order is locked)."
  [{:keys [rdb current frame cause resolution route-meta
           capture-fx push-fx scroll-fx nav-allocation app-db]}]
  (let [{:keys [source target fallback? throw-reason malformed?]} resolution
        route-plan (rf.routing.resolve/route-plan {:cause  cause
                                                   :source source
                                                   :target target})]
    (when fallback?
      (emit-route-miss! {:url          (:url target)
                         :frame        frame
                         :throw-reason throw-reason
                         :malformed?   malformed?
                         :route-meta   route-meta}))
    (rf.trace/emit! :rf.event :rf.route/planned
                    (cond-> (rf.routing.resolve/plan-trace-tags route-plan)
                      frame (assoc :frame frame)))
    (rf.routing.events/commit-navigation
      rdb
      ;; EP-0037 R1: `:on-match` never drives readiness. The base transition
      ;; is `:idle`; the commit projects `:loading` / `:error` from the
      ;; resource plan.
      (assoc (:target route-plan) :transition :idle)
      (vec (or (:on-match route-meta) []))
      {:prev-id             (:route-id current)
       :prev-nav-token      (:nav-token current)
       :capture-fx          capture-fx
       :scroll-fx           scroll-fx
       :push-fx             push-fx
       :nav-allocation      nav-allocation
       :frame               frame
       :app-db              app-db
       ;; The plan's already-resolved `:parent` walk — the value its
       ;; `:branch` diagnostic projects, so the trace and the resource
       ;; composition cannot disagree.
       :branch-contributors (:branch-contributors route-plan)
       :branch-error        (:branch-error route-plan)})))

;; ---- the pipeline --------------------------------------------------------------

(defn navigate*
  "Run ONE navigation through Spec 012 §The one planning pipeline and return
  its effects map. Every door's handler calls this with one argument map:

    :rdb                    the frame's runtime-db (`:rf.db/runtime` cofx)
    :frame                  the cascade's validated frame stamp
    :nav-allocation         the recordable `{:token :counter}` allocation a
                            commit publishes
    :pending-nav-allocation the recordable `{:id :counter}` allocation a leave
                            block stores
    :app-db                 the causal app-db, threaded to the resource plan's
                            `{:from-db …}` scope resolution; routing never
                            reads it
    :request                the flat request map — a source (`:to` address,
                            `:url` raw URL, or in-place edit keys) plus the
                            policy keys `:replace?` / `:scroll` /
                            `:bypass-leave?`
    :shape-error            a door's own event-shape rejection, or nil
    :cause                  `:navigate` / `:link` / `:popstate` / `:initial`
                            / `:ssr`
    :url-driven?            true when the address bar has ALREADY moved
    :default-scroll         `:top`, or `:restore` for the causes that return
                            to a saved position

  Named keys rather than positions: the two allocations are same-shaped
  two-key maps, and a transposition would compile, run and mint a nil id far
  from the call site.

  The stages:

    1. VALIDATE — the always-on structural gate (`rf.routing.address/classify`)
       rejects a malformed request through `:rf.error/navigate-bad-request`
       before any guard runs; slice unchanged, nothing pushed.
    2. RESOLVE — forked on source kind (`resolve-address` /
       `resolve-raw-url`) into one ResolvedTarget carrying its `:url`.
    3. CLASSIFY — an exact no-op terminates here, evaluating neither guard
       and creating no pending state; a fragment-only move and a full
       transition go on. A not-found target is never fragment-only: its
       params carry the URL, fragment included.
    4-5. DECIDE — the current route's `:can-leave`, then the target's
       `:can-enter` (`rf.routing.decisions/decide`): a resumable leave block,
       or a terminal entry denial, restoring the address bar by replace when
       it had already moved.
    6-7. COMMIT — the fragment-only write, or the full activation
       (`commit-activation`).

  A door that has not moved the address bar pushes (or, for `:replace?
  true`, replaces) the target's URL after the slice write; one that has
  pushes nothing. The per-call `:scroll` overrides the route's own `:scroll`,
  which overrides `default-scroll`."
  [{:keys [rdb frame request shape-error cause url-driven? default-scroll
           nav-allocation pending-nav-allocation app-db]}]
  (let [rdb     (or rdb {})
        current (get-in rdb [:rf.runtime/routing :current])]
    (if-let [bad (or shape-error
                     (when-not (map? request) {:reason :request-not-a-map :keys []})
                     (rf.routing.address/classify request current))]
      ;; `:rf.error/navigate-bad-request` is a DISTINCT channel from
      ;; `:rf.error/schema-validation-failure`: plain set logic, total over the
      ;; roster and armed on every build.
      (do
        (rf.trace/emit-error! :rf.error/navigate-bad-request
                              (cond-> {:where    :event
                                       :reason   (:reason bad)
                                       :keys     (:keys bad)
                                       :recovery :no-recovery}
                                frame (assoc :frame frame)))
        {})
      (let [stage      {:rdb         rdb
                        :current     current
                        :frame       frame
                        :request     request
                        :url-driven? url-driven?}
            {:keys [effects target fallback?] :as resolution}
            (if (contains? request :url)
              (resolve-raw-url stage)
              (resolve-address stage))
            {:keys [route-id params query fragment url]} target]
        (cond
          effects
          effects

          (rf.routing.plan/identical-route-target? current route-id params query fragment)
          {}

          :else
          (or (rf.routing.decisions/decide
                {:rdb                    rdb
                 :frame                  frame
                 :target                 target
                 :requested-url          url
                 :cause                  cause
                 :policy                 (rf.routing.decisions/normalize-policy request)
                 :bypass-leave?          (:bypass-leave? request)
                 :url-driven?            url-driven?
                 :pending-nav-allocation pending-nav-allocation})
              (let [route-meta (rf.registrar/lookup :route route-id)
                    {:keys [capture-fx scroll-fx]}
                    (rf.routing.plan/scroll-plan
                      {:rdb              rdb
                       ;; Saved scroll positions are a host-side transient
                       ;; cache, threaded in so the planner stays pure.
                       :scroll-cache     (rf.routing.scroll/frame-scroll-cache frame)
                       :route-meta       route-meta
                       :opts             request
                       :default-strategy default-scroll
                       :route-id         route-id
                       :params           params
                       :query            query
                       :fragment         fragment
                       :url              url})
                    push-fx    (when-not url-driven?
                                 [(if (:replace? request) :rf.nav/replace-url :rf.nav/push-url) url])]
                (if (and (not fallback?)
                         (rf.routing.plan/fragment-only? current route-id params query fragment))
                  (fragment-only-fx {:rdb        rdb
                                     :current    current
                                     :fragment   fragment
                                     :frame      frame
                                     :capture-fx capture-fx
                                     :push-fx    push-fx
                                     :scroll-fx  scroll-fx})
                  (commit-activation {:rdb            rdb
                                      :current        current
                                      :frame          frame
                                      :cause          cause
                                      :resolution     resolution
                                      :route-meta     route-meta
                                      :capture-fx     capture-fx
                                      :push-fx        push-fx
                                      :scroll-fx      scroll-fx
                                      :nav-allocation nav-allocation
                                      :app-db         app-db})))))))))

;; ---- the doors -------------------------------------------------------------------

(defn door-inputs
  "The causal inputs every door hands `navigate*` from its coeffects. The
  frame stamp is an EP-0002 carried invariant — every door event is a cascade
  event, so a nil stamp fails as `:rf.error/no-frame-context` rather than
  being synthesised as `:rf/default`. Every door declares both recordable
  allocation cofx: a leave block mints a pending-nav id, a commit a
  nav-token."
  [{frame :rf.frame/id rdb :rf.db/runtime app-db :db
    nav-allocation :rf.route/nav-allocation
    pending-nav-allocation :rf.route/pending-nav-allocation}
   event-id where]
  {:frame                  (rf.frame/require-frame-stamp! frame event-id {:where where})
   :rdb                    rdb
   :app-db                 app-db
   :nav-allocation         nav-allocation
   :pending-nav-allocation pending-nav-allocation})

(defn url-change-cause
  "The true navigation cause for one `:rf.route/handle-url-change` dispatch.
  The event is ONE door standing for FOUR (Spec 012 §URL changes are events —
  a host-pushed link click, popstate, initial page load, and the SSR request
  URL), so it reports which of the four it actually was. The cause also fixes
  the default scroll strategy — `:top` for `:link`, `:restore` otherwise
  (Spec 012 §Scroll restoration).

  Resolution, in order:

  1. the runtime-internal `:rf.route/cause` rider on the door's trailing opts
     map. The `:url-bound?` history listener sets it — `:popstate` from the
     browser-driven `popstate` / `hashchange` callback, `:initial` from the
     same listener's initial URL -> slice sync (Spec 012 §popstate drives the
     URL-owner frame) — and a host that pushed a link's URL before
     dispatching sets `:link`. Only a member of `rf.routing.resolve/causes`
     is honoured, so a stray value cannot invent another cause. It rides the
     trailing opts map, NOT the closed `:rf.route/navigate` request roster
     (Spec 012 §The request grammar);
  2. `:ssr` when the dispatching frame is a server frame — the SSR feed is
     the application's own `:initial-events` dispatch
     (`[:rf.route/handle-url-change url]`, Spec 012 §Server-side rendering
     integration step 2), so it carries no framework rider and is recognised
     by the frame's `:platform :server`, the same read that gates the SSR 403
     floor;
  3. `:initial` otherwise — a rider-free dispatch on a client frame is an
     initial / direct-URL feed."
  [frame opts]
  (let [riden (:rf.route/cause opts)]
    (cond
      (contains? rf.routing.resolve/causes riden) riden
      (rf.routing.decisions/server-frame? frame)  :ssr
      :else                                       :initial)))

(defn handle-url-change-handler
  "`:rf.route/handle-url-change` event handler — the URL-driven door, whose
  URL the address bar has already moved to (a host-pushed link click,
  popstate, initial load, SSR). Registered by the façade so a `:reload`
  re-wires it on a fresh registrar.

  It supplies `navigate*` the URL as a raw-URL request (with the trailing
  opts map's `:bypass-leave?`), the cause `url-change-cause` resolves for
  THIS dispatch, `url-driven? true` — no push, and a blocked or denied URL
  restored by replace — and the default scroll strategy, a pure function of
  that cause: `:top` for `:link`, so a forward click lands at the top of the
  new page, and `:restore` otherwise, so Back/Forward or a reload puts the
  saved position back."
  [cofx [_ url opts]]
  (let [inputs (door-inputs cofx :rf.route/handle-url-change
                            'rf.route/handle-url-change-handler)
        cause  (url-change-cause (:frame inputs) opts)]
    (navigate* (assoc inputs
                      :request        {:url url :bypass-leave? (:bypass-leave? opts)}
                      :cause          cause
                      :url-driven?    true
                      :default-scroll (if (= :link cause) :top :restore)))))
