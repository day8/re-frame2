# Removed-context deleted-substrate prose (negative fixture)

These sentences DISCUSS the realm/app-value substrate deletion — each carries a
removed-context marker, so the deleted-namespace + retained-claim families must
stay GREEN. These mirror the legitimate shapes on spec/Conventions.md, the Xray
tool specs, and spec/api-manifest-metadata.edn.

The substrate that carried it no longer exists — there is no retained-internal
realm machinery to read.

The realm machinery was **deleted in full** by EP-0024: there is no
`re-frame.realm` namespace and no realm-scoped reader to read.

The former MODULES section (per-module provenance read off a realm's installed
app value via `re-frame.realm/installed-app`) and the REALMS section
(`re-frame.realm/realm-ids` × `re-frame.frame/frame-realm`) were **removed** with
the realm substrate.

The per-realm dimension is **gone** with the substrate it read: no installed-app
value, no `re-frame.realm/installed-app` seam.

The EP-0013 -> EP-0023 migration facade exports — `re-frame.migration/migration-map`
and friends — were REMOVED with the atomic substrate deletion. There is no
retained internal substrate.
