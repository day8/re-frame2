# Post-publish coordinates

The coordinate shape once `day8/re-frame2*` resolves on Clojars. **Not usable today**: re-frame2 is not published yet, so a project today takes the `:local/root` or `:git/sha` route in [`deps-versions.md` §Choosing the coordinate](deps-versions.md#choosing-the-coordinate-publication-state-decides-the-shape). Load this leaf only when Clojars answers for the version you want.

## The shape (NOT usable until the coordinates resolve on Clojars)

Once `day8/re-frame2*` is published, every framework artefact is a single shared `:mvn/version` — the shape the scaffold already ships:

```clojure
;; AFTER PUBLICATION ONLY — these coords 404 on Clojars today.
day8/re-frame2         {:mvn/version "<VERSION>"}
day8/re-frame2-reagent {:mvn/version "<VERSION>"}
```

Verify it resolves on Clojars first. The tools (`-xray`, `-story`) flip to `:mvn/version` on **different** tags, so check Clojars per artefact rather than assuming the framework release brought the tools with it. In particular, the scaffold's Story entry is a sibling `:local/root`, not a Maven coordinate: replace it with an absolute reviewed checkout path or the Git coordinate in [`deps-versions.md` §The `:git/sha` route](deps-versions.md#the-gitsha-route-pre-publish-no-checkout-on-disk) until the matching Story version is published. Once it resolves, use `day8/re-frame2-story {:mvn/version "<VERSION>"}` in `:aliases :dev :extra-deps`.
