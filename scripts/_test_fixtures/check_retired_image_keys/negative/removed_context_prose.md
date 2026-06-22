# Removed-context discussion of the retired keys (MUST STAY GREEN)

The `:include-ns` and `:exclude-ns` keys are RETIRED — use `:select-ns`.
`:replace` and `:replace-standard` were retired; composition resolves by image
order now. `:rf.image/requires` is no longer accepted; image-declared host
capabilities were removed end-to-end, so there is no `:rf.gen/requires` slot and
no `make-frame :capabilities` key.

A fenced block that names a retired key ONLY in a `;`-comment stays green — the
comment is masked, so it is not live copy-pasteable code:

```clojure
;; :include-ns is RETIRED — use :select-ns {:include [...]} instead.
;; :replace / :replace-standard / :rf.image/requires likewise fail loud.
(rf/image {:id :x :select-ns {:include ["a.b"]}})
```
