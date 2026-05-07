(ns example.realworld.profile
  "Profile pages — view your own, view another user's.

   STATUS: stub. The full implementation is pending and tracked under
   bead rf2-kq2z. See examples/realworld/README.md for the scope of this
   feature.

   TODO — full implementation:
   - :profile slice (Pattern-RemoteData shape) holding the current
     :Profile being viewed.
   - :profile.articles slice — articles authored by the profile's user.
   - :profile.favorites slice — articles favorited by the profile's user.
   - :profile/load event — GET /profiles/:username (called from
     :route/profile and :route/profile.favorites :on-match).
   - :profile.articles/load — GET /articles?author=<username>.
   - :profile.favorites/load — GET /articles?favorited=<username>.
   - :profile/follow + :profile/unfollow events — POST/DELETE
     /profiles/:username/follow, optimistic update with rollback.
   - profile-page view: header (user banner + follow/unfollow button) +
     toggle between authored / favorited tabs.
   - Settings page — separate concern for now; the user editing their own
     profile (PUT /user) lives there.
   - Headless tests: load profile; toggle tabs; follow/unfollow with
     optimistic update + rollback.

   Pattern references:
   - docs/specification/Pattern-RemoteData.md   — for each slice
   - docs/specification/012-Routing.md          — :on-match per route

   API endpoints (from the RealWorld spec):
   - GET    /profiles/:username                  — fetch profile
   - POST   /profiles/:username/follow           — follow
   - DELETE /profiles/:username/follow           — unfollow
   - GET    /articles?author=<username>          — articles authored by user
   - GET    /articles?favorited=<username>       — articles favorited by user")

(def ^:private stub :stub)
