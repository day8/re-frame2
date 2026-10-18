(ns realworld-http.ssr
  "RealWorld-specific SSR helpers.

   The general how-SSR-works walkthrough lives in
   `examples/capabilities/ssr/ssr/core.cljc`. This file is the app-specific half of the
   story — the part every real app has to write for itself:
   - decide which slices are safe to ship in the hydration payload
   - keep the session's credential out of it
   - offer a small client-side bootstrap for `:rf/hydrate`

   The projection itself is the framework's: `rf.ssr/hydration-payload` applies
   the allowlist, redacts what the frame classifies `:sensitive`, and ships only
   the durable runtime-db slices — the route and the machine snapshots — so the
   client wakes up on the server's route with any machines still mid-flow.
   Splitting it this way keeps the reusable SSR machinery in the generic
   example, while this one shows where a larger app draws its own payload
   boundary — which is to say, what it's willing to send down the wire."
  (:require [re-frame.core :as rf]
            ;; The payload builder. Requiring it also registers the
            ;; `:rf/hydrate` handler that `hydrate-client!` dispatches.
            [re-frame.ssr :as rf.ssr]
            #?(:cljs [cljs.reader :as reader])))

(def ssr-app-slice-keys
  "The top-level app-db slices the SSR payload ships. Application data only —
  the framework's own subsystem trees (routing, machines, elision) live in the
  separate runtime-db partition, whose durable slices the framework ships under
  :rf/runtime-db.

  Two deliberate absentees, since the hydrate policy is
  `:replace-frame-state` and an omission here is an absence on the client:
  `:profile.follow-pending`, the profiles with a follow/unfollow in flight,
  and `:favorite-pending`, the articles with a favourite POST/DELETE in
  flight. Both are facts about one browser tab's outstanding writes, not about
  the server's render — the server issues no mutations, so either would ship
  empty every time. profile.cljs's and favorites.cljs's writers are total
  against their absence for exactly this reason."
  [:auth
   :articles
   :article
   :comments
   :feed
   :profile
   :profile.articles
   :profile.favorites
   :editor
   :comment-form])

(defn hydration-payload
  "Build the hydration payload for the settled SSR frame `frame` (its id, or the
  frame value `make-frame` returns), before the server destroys it. This is
  what the server embeds and `hydrate-client!` reads back."
  [frame]
  ;; No secrets in the SSR payload — full stop. The bearer JWT sits at
  ;; [:auth :token], and baking it into server-rendered HTML would spill a live
  ;; credential into the page source: visible in view-source, logged by
  ;; proxies, possibly cached by a CDN. The frame classifies that path
  ;; `:sensitive` (`:auth/classify-token`, core.cljs), but a classification
  ;; REDACTS: the payload would carry `:rf/redacted` in the token's place, and
  ;; the payload's app-db becomes the client's whole app-db, so the client would
  ;; boot holding a keyword where AuthSlice wants `[:maybe :string]` — and the
  ;; bearer interceptor would send it. So the key is dropped outright. That
  ;; costs nothing, because the client reads [:auth :token] back out of its own
  ;; localStorage at boot (`:auth/initialise` → `:auth.session/load` →
  ;; `:auth/session-read`, auth.cljs).
  (let [payload (rf.ssr/hydration-payload frame {:payload ssr-app-slice-keys})]
    (cond-> payload
      (map? (get-in payload [:rf/app-db :auth]))
      (update-in [:rf/app-db :auth] dissoc :token))))

#?(:cljs
   (defn read-server-payload []
     (when-let [el (.getElementById js/document "__rf_payload")]
       (reader/read-string (.-textContent el)))))

#?(:cljs
   ;; The caller names which frame to hydrate. Being explicit about it means a
   ;; non-default or multi-frame client can hydrate exactly the right frame,
   ;; just by passing its own frame-id.
   (defn hydrate-client! [frame-id]
     (when-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload] {:frame frame-id})
       payload)))

