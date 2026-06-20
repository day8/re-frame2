# Deleted-namespace read named as a live seam (positive fixture)

This prose names a DELETED namespace as a current read seam. It must FIRE (one
finding): `re-frame.realm` was removed in full by EP-0024 and no longer exists,
so naming `re-frame.realm/realm-registrations` as a live tooling read is drift.

Tooling reads the host registry via the realm-scoped readers
(`re-frame.realm/realm-registrations` and friends) when it needs to bypass the
bound generation.
