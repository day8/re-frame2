;; Story-side deps.cljs.
;;
;; Per shadow-cljs's npm interop docs, a deps.cljs at the root of a
;; source tree is read at build time so consumers of this artefact pick
;; up npm requirements without re-declaring them.
;;
;; Story currently declares no npm-deps from this file. The historic
;; `qrcode-generator` entry was vendored to back the per-variant Share
;; popover's local SVG QR encoder (rf2-20w5i security audit — pre-fix
;; the QR rode in from a third-party image service, leaking author-typed
;; `:cell-overrides` off-box). The share popover itself was retired in
;; rf2-ymnfx Issue B because the variant URL is already the browser's
;; address bar — Cmd-L / Cmd-A / Cmd-C copies it without a separate
;; affordance. The npm dep retired with the popover.
;;
;; axe-core is intentionally NOT vendored. The shadow-cljs / Closure
;; :advanced strict ECMAScript parser trips on axe-core's UMD wrapper.
;; Until shadow's Closure is upgraded (or axe-core ships an ESM build),
;; the a11y panel loads axe-core from the public CDN only after explicit
;; dev opt-in, pinned to a specific version + SRI hash for tamper-
;; detection. See `re-frame.story.ui.a11y` for the consent-prompt UI.

{:npm-deps {}}
