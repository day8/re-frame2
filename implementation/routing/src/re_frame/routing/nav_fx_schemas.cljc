(ns re-frame.routing.nav-fx-schemas
  "Malli args schemas for the four standard `:rf.nav/*` navigation fx.
  Per Spec 012 §Multi-frame routing §Scroll restoration and
  [Spec-Schemas §Standard fx args schemas], which states normatively
  that 'the standard fx ship with `:schema` set to the corresponding
  schema above'. These are the `:rf.fx.nav/*-args` schemas the spec
  names as registered.

  The schemas here are **plain Malli forms** — vectors, keywords, and
  `clojure.core` predicates; no Malli code dependency. The routing
  artefact does NOT depend on Malli / the schemas artefact in
  production (Spec 006 §Adapter shipping convention — schemas is an
  optional sibling). The `:schema` value a `reg-fx` carries is consumed
  by whatever validator the (optional) schemas artefact has installed —
  `re-frame.fx` resolves the `:schemas/validate-fx!` late-bind hook and
  calls it only when the registration meta actually carries a
  `:schema`, per Spec 010 §Validation order step 5. With schemas /
  Malli absent the hook is unbound and fx-args validation soft-passes;
  these vars are inert data that still travels with the registration so
  the `:schema` boundary EXISTS the moment a validator is present.

  Attaching these to the registrations makes malformed navigation args
  surface at the `:fx-args` boundary — and, per Spec 010 §Per-step
  recovery row 5, skip the offending fx — while the dispatching event
  is still in scope, BEFORE the handler mutates browser history, the
  scroll position, or the host-side scroll-position cache.

  Spec ↔ var map (per [Spec-Schemas §Standard fx args schemas]):

    :rf.fx.nav/push-url-args       — `push-url-args`
    :rf.fx.nav/replace-url-args    — `replace-url-args`
    :rf.fx.nav/scroll-args         — `scroll-args`
    :rf.fx.nav/capture-scroll-args — `capture-scroll-args`

  These are SHAPE checks — the structural contract on the fx args. The
  same-origin / open-redirect gate (`re-frame.routing.url/safe-in-app-url?`
  at the nav-event sinks) and the CLJS `run-history-mutation!` try/catch
  are SEPARATE, complementary defence layers an args schema cannot
  express (a cross-origin URL is still a `:string`).

  Internal namespace; the public facade is `re-frame.routing`, which
  owns the `fx/reg-fx` calls these schemas ride on.")

;; ---- :rf.nav/push-url / :rf.nav/replace-url -------------------------------

(def push-url-args
  "Args of `:rf.nav/push-url` — `:rf.fx.nav/push-url-args`.
  The PATH-FORM app URL to push onto the browser history. Every emit
  site (`navigate/…`, `can-leave/…`, `url-change/…`) threads a
  `registry/route-url` reconstruction or the requested URL string, so a
  bare `:string` is the exact shape. The strategy `:encode` that maps
  path-form → final href runs INSIDE the handler, after this gate."
  :string)

(def replace-url-args
  "Args of `:rf.nav/replace-url` — `:rf.fx.nav/replace-url-args`.
  Same path-form app URL string as `:rf.nav/push-url`; the fx differs
  only in driving `replaceState` rather than `pushState`."
  :string)

;; ---- :rf.nav/scroll -------------------------------------------------------

(def scroll-args
  "Args of `:rf.nav/scroll` — `:rf.fx.nav/scroll-args`. Per Spec 012
  §Scroll restoration §`:rf.nav/scroll` integration the shape is
  `{:strategy :from :to :saved-pos :fragment}`, which is exactly what
  `re-frame.routing.scroll/scroll-fx-entry` assembles.

  `:strategy` is REQUIRED — `scroll-fx-entry` always seeds it, and a
  strategy-less scroll has no defensible interpretation. It is either
  one of the three standard keywords or a MAP (the host-extensible
  form, which the handler passes to its default no-op branch). A bare
  non-standard KEYWORD is deliberately NOT admitted: Spec 012 offers
  the map form for host extension, and the handler's `nil` default is
  defence-in-depth rather than a documented extension point.

  `:saved-pos` members are `number?`, not `:int`. The position is read
  straight off `window.scrollX` / `window.scrollY`, which are
  FRACTIONAL at non-100% browser zoom and on HiDPI displays — an
  integer-only tuple would reject valid captured positions. `number?`
  also admits the plain integer pairs the JVM-side planning tests
  thread.

  `:from` / `:to` are the `{:id :params :query}` route descriptors
  `scroll/route-descriptor*` builds — purely DIAGNOSTIC carriers the
  handler ignores (hence their EP-0015 `:sensitive` path-marks on the
  registration). Both are optional: the initial navigation has no
  `:from`, and `scroll-fx-entry` omits either when nil."
  [:map
   [:strategy  [:or
                [:enum :top :restore :preserve]
                :map]]                                                  ;; map form is host-extensible (post-v1)
   [:from      {:optional true} [:map
                                 [:id     :keyword]
                                 [:params {:optional true} :map]
                                 [:query  {:optional true} :map]]]
   [:to        {:optional true} [:map
                                 [:id     :keyword]
                                 [:params {:optional true} :map]
                                 [:query  {:optional true} :map]]]
   [:saved-pos {:optional true} [:tuple number? number?]]               ;; fractional at non-100% zoom / HiDPI
   [:fragment  {:optional true} :string]])                              ;; `#fragment` of the target URL

;; ---- :rf.nav/capture-scroll ----------------------------------------------

(def capture-scroll-args
  "Args of `:rf.nav/capture-scroll` — `:rf.fx.nav/capture-scroll-args`.
  Save the leaving route's scroll position into the host-side per-frame
  transient scroll-position cache, keyed by that route's reconstructed
  URL. `re-frame.routing.scroll/capture-scroll-fx-entry` emits exactly
  `{:url <leaving-url>}`, so `:url` is REQUIRED — it is the cache key,
  and a capture without one saves nothing. (The handler's `when url`
  nil-guard is defence-in-depth, not a documented graceful-degradation
  path of the kind `:rf.server/redirect`'s target-less case is.)

  The handler also honours an optional `:position` override in place of
  reading `window.scrollX/Y`. That slot is an INTERNAL / TEST INJECTION
  SEAM, not public API: no framework path emits it and Spec 012 does
  not offer it, so it is deliberately absent from this public args
  shape. Malli maps are open by default, so a test that injects
  `:position` still validates — it just is not a promise."
  [:map
   [:url :string]])                                                     ;; the leaving route's reconstructed URL (cache key)
