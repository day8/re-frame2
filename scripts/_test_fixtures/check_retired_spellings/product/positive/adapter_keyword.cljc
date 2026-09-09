;; POSITIVE fixture (rule g) — the retired adapter keyword.
;;
;; The adapter value became `:rf.adapter/fresco`. Unlike the retired
;; `:rf.adapter/ui` and `:rf.adapter/freehand`, it is NOT reserved afterwards,
;; so nothing defensively refuses the old value at runtime — which is exactly
;; why the static ratchet has to.
;;
;; The header does not spell the retired keyword, for the reason given in
;; `namespace_require.cljc`: unmasked raw lines mean a comment counts.
(def config {:rf/adapter :rf.adapter/hicasso})
