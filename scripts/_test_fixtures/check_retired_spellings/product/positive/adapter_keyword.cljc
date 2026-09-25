;; POSITIVE fixture (rule g) — the retired adapter keyword.
;;
;; The adapter value is `:rf.adapter/fresco`. Unlike the reserved
;; `:rf.adapter/ui` and `:rf.adapter/freehand`, the retired value is NOT
;; reserved, so nothing defensively refuses it at runtime — which is exactly
;; why the static ratchet has to.
;;
;; The header does not spell the retired keyword, for the reason given in
;; `namespace_require.cljc`: unmasked raw lines mean a comment counts.
(def config {:rf/adapter :rf.adapter/hicasso})
