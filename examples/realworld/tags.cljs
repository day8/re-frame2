(ns example.realworld.tags
  "Tag-based article filtering.

   The popular-tags *list* in the home page sidebar is loaded by
   articles.cljs (it shares the home page's :on-match). This file is for
   the *filter* feature: clicking a tag on the home page activates a
   per-tag filtered view of articles.

   STATUS: stub. The full implementation is pending and tracked under
   bead rf2-kq2z. See examples/realworld/README.md for the scope of this
   feature.

   TODO — full implementation:
   - Tag-as-filter UI on the home page — clicking a tag adds it to the
     URL as a query parameter; the home tab list shows a 'Tag: <name>'
     pill which can be cleared.
   - :route/home extended with :query [:map [:tag {:optional true} :string]]
     so /?tag=clojure activates the filtered view; via :query-retain the
     selected tag persists across in-app navigations.
   - :articles/load reads :route/query :tag — when present, calls
     GET /articles?tag=<tag>; when absent, GET /articles.
   - The articles slice does not need to be sharded by tag — re-fetching
     :articles when the filter changes via :on-match's params/query
     re-fire (per Spec 012 §Per-route data loading) does the right thing.
   - Headless tests: navigate to /?tag=foo asserts the slice reloads with
     the tag-filtered URL; clear the tag and assert it reloads without it.

   Pattern references:
   - docs/specification/012-Routing.md          — :query, :query-retain
   - docs/specification/Pattern-RemoteData.md   — :articles slice

   API endpoint (from the RealWorld spec):
   - GET /articles?tag=<tag>     — tag-filtered articles list")

(def ^:private stub :stub)
