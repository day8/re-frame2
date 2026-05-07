(ns example.realworld.favorites
  "Favorite/unfavorite articles, plus the 'Your Feed' (followed-authors)
   variant of the article list.

   STATUS: stub. The full implementation is pending and tracked under
   bead rf2-kq2z. See examples/realworld/README.md for the scope of this
   feature.

   TODO — full implementation:
   - :article/toggle-favorite event — optimistic update of an article's
     :favorited and :favoritesCount fields. POST /articles/:slug/favorite
     to favorite, DELETE /articles/:slug/favorite to unfavorite.
   - Roll back on error — capture the prior {:favorited :favoritesCount}
     pair in the rollback dispatch (per Pattern-RemoteData §Optimistic
     updates).
   - :feed/load event — GET /articles/feed (the followed-authors feed).
     This is a distinct slice from :articles (which is the global feed)
     so the home page can toggle between them without throwing away data.
   - Toggle UI on the home page — 'Your Feed' tab shown when authed,
     'Global Feed' always present.
   - Headless tests: optimistic favorite + rollback on failure;
     feed-load + render.

   Pattern references:
   - docs/specification/Pattern-RemoteData.md §Optimistic updates

   API endpoints (from the RealWorld spec):
   - POST   /articles/:slug/favorite     — favorite
   - DELETE /articles/:slug/favorite     — unfavorite
   - GET    /articles/feed               — followed-authors feed (auth required)")

(def ^:private stub :stub)
