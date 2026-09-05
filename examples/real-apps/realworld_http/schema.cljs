(ns realworld-http.schema
  "App-db + machine Malli schemas for the RealWorld (Conduit) example.

   The WIRE shapes — every payload the RealWorld API returns and the seven
   response envelopes — are the transport-neutral contract shared by both
   RealWorld examples, so they live in `realworld-shared.schema` (imported as
   `ws` below) and are consumed directly at each decode site. What stays HERE is
   what's genuinely local to THIS (managed-HTTP) architecture: the shape of each
   app-db slice that stores remote data, and each machine's snapshot `:data`
   slot. Those get wired up for path-based validation via `reg-app-schemas` at
   the bottom. See the schemas how-to:
   ../../../docs/core/how-to/validate-with-schemas.md"
  (:require [re-frame.core :as rf]
            ;; The shared wire contract — User/Profile/Article/Comment + the
            ;; seven response envelopes. The app-db slice schemas below embed
            ;; these shapes directly (e.g. `[:vector ws/Article]`).
            [realworld-shared.schema :as ws]
            ;; Schemas live in their own artefact; we require it to load it,
            ;; which registers the hooks that make `rf/reg-app-schemas` resolve
            ;; at the call site below.
            [re-frame.schemas])
  (:require-macros [re-frame.core :refer [with-frame]]))

;; ============================================================================
;; APP-DB SLICES — the remote-data slice shape, one per resource
;; ============================================================================
;;
;; Every slice that holds remote data wears the same 5-key shape. Learn it
;; once, recognise it everywhere.

(def RequestSlice
  "The standard remote-data lifecycle slice, generic over whatever `:data` it
   holds.

   `:articles-count` is the RealWorld pagination total — the GRAND count of
   articles matching the query (before the limit/offset window), which is what
   the page count is computed from. It's optional because only the paginated
   list slices (`:articles`, `:feed`, `:profile.articles`,
   `:profile.favorites`) carry it; the single-resource slices (`:article`,
   `:profile`) have no use for it.

   `:slug` is the ACTIVE ROUTE IDENTITY a route-keyed slice is loading — the
   correlation fact `reply-for-current-slug?` (comments.cljs) gates every
   article/comments settle that WRITES a slice against, so a late reply for a
   slug the reader has left can't overwrite the current page. (A settle whose
   outcome is a NAVIGATION asks the route instead — `article-route-for-slug?`,
   same file: THIS slug outlives a walk to a non-article page, the route's
   does not.) Optional because only the `/article/:slug`-driven slices
   (`:article`, `:comments`) carry it.

   `:username` is the same fact for the `/profile/:username`-driven slices
   (`:profile`, `:profile.articles`, `:profile.favorites`) — the correlation
   identity `reply-for-current-profile?` (profile.cljs) gates every banner,
   list and follow settle against, so a late reply for a profile the reader has
   left can't overwrite the current one's data, status, error, timestamp or
   machine presentation. Same law as `:slug`, different route param.

   `:follow-pending?` is the follow/unfollow latch. Only `:profile` carries it
   — it is the one slice with a mutation of its own — and the toggle is
   serialised on it: while it is set both handlers refuse a second intent and
   the button disables itself, so a rapid Follow→Unfollow can never leave two
   settles for the SAME profile racing, which the username correlation cannot
   tell apart. See SERIALISING THE TOGGLE in profile.cljs."
  [:map
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:default nil} :any]
   [:error          {:default nil} [:maybe :any]]
   [:loaded-at      {:default nil} [:maybe :int]]
   [:attempt        {:default 0}   :int]
   [:articles-count {:optional true} :int]
   [:slug           {:optional true} [:maybe :string]]
   [:username       {:optional true} [:maybe :string]]
   [:follow-pending? {:optional true} :boolean]
   [:stale-after-ms {:optional true} [:maybe :int]]])

(def AuthSlice
  "The auth slice. :user holds the durable, token-free session user
   (`ws/SessionUser` — the wire User minus its credential, since
   `:auth/store-session` `dissoc`s the token before storing); :token is the JWT
   (or nil). Validating :user against the wire `ws/User` here would demand the
   very :token the durable copy deliberately drops, and post-commit validation
   would roll the login back. The auth machine's own snapshot lives elsewhere —
   over in runtime-db at [:rf.runtime/machines :snapshots :auth/flow].

   :return-to is the breadcrumb: the post-login return target the routing
   `:rf.route/entry-denied` handler drops here (routing.cljs) when the
   `:can-enter` auth gate turns a logged-out user away from a `:requires-auth`
   route. It is the denial payload's `:destination` — a `:rf/route-destination`
   and a valid `:rf.route/navigate` request in its own right — so the return
   lands on the exact URL (path, params, query, and #fragment), not just the
   bare route. `:auth/post-login-redirect` reads and clears it (auth.cljs). It
   only exists for the brief window between that redirect and the next
   successful login."
  [:map
   [:user      [:maybe ws/SessionUser]]
   [:token     [:maybe :string]]
   ;; Mirror `:rf/route-destination` (spec/Spec-Schemas.md) EXACTLY, because that
   ;; is literally what lands here — the denial payload's `:destination`, verbatim.
   ;; Two things it gets right that a hand-drawn four-key map does not:
   ;;   - the address branch is MINIMAL. `destination-of` emits `{:to id}` and adds
   ;;     `:params` / `:query` / `:fragment` only when non-empty, so demanding all
   ;;     four made the commonest denial of all — a bare `/settings` — fail
   ;;     validation and roll the stash back, silently costing the reader their
   ;;     post-login return (rf2-k85nd).
   ;;   - the RAW branch exists. A destination the runtime cannot reify without
   ;;     changing the requested URL stays `{:url …}`, and that must be storable
   ;;     too.
   [:return-to {:optional true}
    [:or
     [:map {:closed true}
      [:to       :keyword]
      [:params   {:optional true} :map]
      [:query    {:optional true} :map]
      [:fragment {:optional true} [:maybe :string]]]
     [:map {:closed true}
      [:url      :string]
      [:fragment {:optional true} [:maybe :string]]]]]])

;; Machine snapshots aren't app-db — they live in the runtime-db partition at
;; [:rf.runtime/machines :snapshots <id>]. And `reg-app-schema` only polices
;; app-db, so you don't validate a snapshot with an app-schema on a runtime
;; path. Instead each machine carries its own `[:schemas :data]` slot
;; describing its snapshot's `:data` map, and the `*Data` schemas below are
;; what get attached there, at each machine's registration site (auth.cljs /
;; tags.cljs / settings.cljs).

(def AuthFlowData
  "The `:data` slot of the `:auth/flow` machine's snapshot."
  [:map
   [:error [:maybe :string]]])

(def TagsData
  "The `:data` slot of the `:realworld/tags` machine's snapshot — the case
   where the whole remote-data lifecycle lives in the machine. The
   state-keyword is the status enum, and `:data` here holds the items, error,
   loaded-at, and attempt that the slice form would otherwise keep in a slice."
  [:map
   [:tags      [:vector :string]]
   [:error     [:maybe :any]]
   [:loaded-at [:maybe :int]]
   [:attempt   :int]])

(def SettingsFormData
  "The `:data` slot of the `:settings/form` machine's snapshot — the case where
   the whole FORM lifecycle lives in the machine. The state-keyword is the
   lifecycle (`:neutral` / `:incorrect` / `:correct` + `:submitting`), and
   `:data` holds the draft, the per-field validation state, and the projected
   submit-error string."
  [:map
   [:draft        :map]
   [:submitted    [:maybe :map]]
   [:errors       [:map-of :keyword [:vector :string]]]
   [:touched      [:set :keyword]]
   [:submit-error [:maybe :string]]
   [:loaded-at    [:maybe :int]]])

(def FormSlice
  [:map
   [:draft :any]
   [:submitted [:maybe :any]]
   [:status :keyword]
   [:errors :map]
   [:touched [:set :keyword]]
   [:submit-attempted? {:optional true} :boolean]
   [:submit-error [:maybe :string]]])

(def EditorSlice
  ;; The state vocabulary (`:mode` = :create/:edit, the lifecycle `:status`)
  ;; lives in the :ui/article-editor machine's snapshot over in runtime-db, NOT
  ;; in this slice — see `editor-slice` in article_editor.cljs. This slice is
  ;; just the data.
  [:map
   [:slug [:maybe :string]]
   [:draft [:map
            [:title :string]
            [:description :string]
            [:body :string]
            [:tagList :string]]]
   [:baseline [:map
               [:title :string]
               [:description :string]
               [:body :string]
               [:tagList :string]]]
   [:submitted [:maybe :any]]
   [:errors :map]
   [:touched [:set :keyword]]
   [:submit-attempted? {:optional true} :boolean]
   ;; Where the :editor/can-submit? flow parks its output. Optional because the
   ;; flow is registered at boot, so the key is absent in any editor state built
   ;; before `:editor/register-flow` runs — not because of any lag afterwards.
   [:can-submit? {:optional true} :boolean]
   [:submit-error [:maybe :string]]])

;; ============================================================================
;; SCHEMA REGISTRATION
;; ============================================================================
;;
;; Attach schemas to app-db paths, and the framework will validate writes to
;; those paths in development — a tripwire for the day a handler starts putting
;; the wrong shape somewhere.
;;
;; We use the bulk plural `rf/reg-app-schemas` here: one `{path -> schema}` map
;; reads far more cleanly than a tall stack of singular `reg-app-schema` calls.
;;
;; Every path below is an app-db path. Machine snapshots are deliberately
;; absent — they're runtime-db, not app-db, and get validated through each
;; machine's own `[:schemas :data]` (the *Data schemas above) instead.
;;
;; WHY EVERY SLICE WEARS A `:maybe` — THE BOOT WINDOW. Validation is not scoped
;; to the paths the committing event touched: at every `:db` commit the runtime
;; walks EVERY registered path with `get-in` over the whole candidate app-db and
;; rejects the entire transaction if any one of them fails (Spec 010 §Per-step
;; recovery row 4 — the candidate is discarded, and `:fx` does not walk either).
;; A path nothing has written yet reads `nil`, and `nil` is not a `[:map …]`.
;; app-db starts empty and each slice is seeded by its OWN feature's
;; `:*/initialise`, fanned out from `:app/initialise` (core.cljs) — separate
;; events, so separate commits. Register these bare and the very first seed is
;; rejected by the eighteen siblings that have not been seeded yet; the same
;; thing happens to every later seed, so app-db never leaves `{}` and the app
;; renders empty everywhere. Silently, and only in a development build, since a
;; production build elides the validator and installs every candidate. The
;; `:maybe` buys exactly the window before a slice's seed lands — once it has,
;; the schema is doing its full job. `examples/patterns/boot/schema.cljs` wears
;; its `:maybe`s for the same reason.
;;
;; One wrinkle: `reg-app-schemas` is frame-local, so it needs a frame in
;; context — call it bare at ns-load and you'd get :rf.error/no-frame-context.
;; `with-frame` names the target, `:rf/default`, so these register against that
;; frame at load time, ready for the frame-provider in core.cljs to pick up
;; when it creates `:rf/default`.

(def app-db-schemas
  "This app's app-db schema registry as a `{path -> schema}` VALUE, so the same
   set can be registered against a frame other than `:rf/default` — registration
   is frame-local, and a harness driving its own frame gets no schemas at all
   unless it asks for these by name."
  {[:auth]                          [:maybe AuthSlice]
   [:articles]                      [:maybe RequestSlice]
   [:articles :data]                [:maybe [:vector ws/Article]]
   [:article]                       [:maybe RequestSlice]
   [:article :data]                 [:maybe ws/Article]
   [:profile]                       [:maybe RequestSlice]
   [:profile :data]                 [:maybe ws/Profile]
   [:profile.articles]              [:maybe RequestSlice]
   [:profile.articles :data]        [:maybe [:vector ws/Article]]
   [:profile.favorites]             [:maybe RequestSlice]
   [:profile.favorites :data]       [:maybe [:vector ws/Article]]
   [:comments]                      [:maybe RequestSlice]
   [:comments :data]                [:maybe [:vector ws/Comment]]
   [:feed]                          [:maybe RequestSlice]
   [:feed :data]                    [:maybe [:vector ws/Article]]
   [:comment-form]                  [:maybe FormSlice]
   [:auth :login-form]              [:maybe FormSlice]
   [:auth :register-form]           [:maybe FormSlice]
   [:editor]                        [:maybe EditorSlice]})

(with-frame :rf/default
  (rf/reg-app-schemas app-db-schemas))
