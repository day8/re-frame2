(ns realworld-http.ssr
  "RealWorld-specific SSR helpers.

   The general how-SSR-works walkthrough lives in
   `examples/capabilities/ssr/ssr/core.cljc`. This file is the app-specific half of the
   story — the part every real app has to write for itself:
   - decide which slices are safe to ship in the hydration payload
   - keep the session's credential out of it

   The projection itself is the framework's: `rf.ssr/hydration-payload` applies
   the allowlist, redacts what the frame classifies `:sensitive`, and ships only
   the durable runtime-db slices — the route and the machine snapshots — so the
   client wakes up on the server's route with any machines still mid-flow. The
   client half is the framework's too: `rf.ssr/hydrate!` reads the embedded
   payload and installs it (Spec 011). Splitting it this way keeps the reusable
   SSR machinery in the generic example, while this one shows where a larger
   app draws its own payload boundary — which is to say, what it's willing to
   send down the wire."
  (:require [re-frame.ssr :as rf.ssr]))

(def ssr-app-slice-keys
  "The app-db slices the SSR payload ships: top-level keys, plus one path.
  Application data only — the framework's own subsystem trees (routing,
  machines, elision) live in the separate runtime-db partition, whose durable
  slices the framework ships under :rf/runtime-db.

  The auth slice ships only `[:auth :user]`, who is signed in. The rest of it
  holds secrets: the bearer JWT at [:auth :token], and the login and register
  drafts, whose passwords the frame classifies `:sensitive` (`:auth.forms`,
  auth.cljs). A classified value ships REDACTED, and the payload's app-db
  becomes the client's whole app-db, so shipping one would install the
  `:rf/redacted` sentinel as the client's token, which the bearer interceptor
  would send, or as a draft password, which a submit would send. Shipping the
  token raw would be worse: a live credential in the page source, visible in
  view-source, logged by proxies, possibly cached by a CDN. Leaving them out
  costs nothing. The client reads its token back out of its own localStorage at
  boot (`:auth/initialise` → `:auth.session/load` → `:auth/session-read`,
  auth.cljs), and a draft password is client-originated, so the client re-seeds
  it after hydration rather than taking it from the server (Spec 011
  §`:rf/app-db` projection).

  Two deliberate absentees, since the hydrate policy is
  `:replace-frame-state` and an omission here is an absence on the client:
  `:profile.follow-pending`, the profiles with a follow/unfollow in flight,
  and `:favorite-pending`, the articles with a favourite POST/DELETE in
  flight. Both are facts about one browser tab's outstanding writes, not about
  the server's render — the server issues no mutations, so either would ship
  empty every time. profile.cljs's and favorites.cljs's writers are total
  against their absence for exactly this reason."
  [[:auth :user]
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
  what the server embeds and `rf.ssr/hydrate!` reads back on the client."
  [frame]
  (rf.ssr/hydration-payload frame {:payload ssr-app-slice-keys}))

