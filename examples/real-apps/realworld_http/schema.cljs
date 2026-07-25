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
   `:profile`) have no use for it."
  [:map
   [:status         [:enum :idle :loading :fetching :loaded :error]]
   [:data           {:default nil} :any]
   [:error          {:default nil} [:maybe :any]]
   [:loaded-at      {:default nil} [:maybe :int]]
   [:attempt        {:default 0}   :int]
   [:articles-count {:optional true} :int]
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
   ;; flow's first computation only lands one event after :editor/initialise.
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
;; One wrinkle: `reg-app-schemas` is frame-local, so it needs a frame in
;; context — call it bare at ns-load and you'd get :rf.error/no-frame-context.
;; `with-frame` names the target, `:rf/default`, so these register against that
;; frame at load time, ready for the frame-provider in core.cljs to pick up
;; when it creates `:rf/default`.
(with-frame :rf/default
 (rf/reg-app-schemas
  {[:auth]                          AuthSlice
   [:articles]                      RequestSlice
   [:articles :data]                [:vector ws/Article]
   [:article]                       RequestSlice
   [:article :data]                 [:maybe ws/Article]
   [:profile]                       RequestSlice
   [:profile :data]                 [:maybe ws/Profile]
   [:profile.articles]              RequestSlice
   [:profile.articles :data]        [:vector ws/Article]
   [:profile.favorites]             RequestSlice
   [:profile.favorites :data]       [:vector ws/Article]
   [:comments]                      RequestSlice
   [:comments :data]                [:vector ws/Comment]
   [:feed]                          RequestSlice
   [:feed :data]                    [:vector ws/Article]
   [:comment-form]                  FormSlice
   [:auth :login-form]              FormSlice
   [:auth :register-form]           FormSlice
   [:editor]                        EditorSlice}))
