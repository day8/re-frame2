;; Story-side deps.cljs (rf2-20w5i).
;;
;; Declares the npm dependency that the Story bundle requires at the
;; CLJS level. Per shadow-cljs's npm interop docs, a deps.cljs at the
;; root of a source tree is read at build time so consumers of this
;; artefact pick up the npm requirement without re-declaring it.
;;
;; `qrcode-generator` is vendored per the rf2-20w5i security audit:
;; pre-fix the per-variant share popover sourced a QR image from a
;; third-party service with the share URL embedded as a query
;; parameter — every author-typed `:cell-overrides` value rode in
;; that URL, reaching the QR service's logs and any intermediary the
;; dev session traversed. Post-fix the QR is encoded locally via this
;; library; no network request fires when the popover opens.
;;
;; axe-core is NOT vendored here. The audit's preferred fix
;; (static `:require [\"axe-core\" ...]`) trips Closure :advanced's
;; strict ECMAScript parser on axe-core's UMD wrapper. Until shadow's
;; Closure is upgraded (or axe-core ships an ESM build), the a11y
;; panel takes the alternative fallback the audit allows: load from
;; the public CDN only after an explicit dev opt-in, pinned to a
;; specific version + SRI hash for tamper-detection. See
;; `re-frame.story.ui.a11y` for the consent-prompt UI.
;;
;; `qrcode-generator` ships under MIT (zero deps, ~52 KB unpacked)
;; and reaches the bundle only when Story is enabled — under
;; `:advanced` + `:rf.story/enabled?` false, Closure DCE drops it.
;; Verified by `scripts/check-bundle-isolation.cjs` against
;; `examples/counter`.

{:npm-deps {"qrcode-generator" "2.0.4"}}
