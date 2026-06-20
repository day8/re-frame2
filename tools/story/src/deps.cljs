;; Story-side deps.cljs.
;;
;; Per shadow-cljs's npm interop docs, a deps.cljs at the root of a
;; source tree is read at build time so consumers of this artefact pick
;; up npm requirements without re-declaring them.
;;
;; Story declares no npm-deps from this file. Variant sharing rides on
;; the variant URL itself — it is already the browser's address bar, so
;; Cmd-L / Cmd-A / Cmd-C copies it without any separate affordance or
;; bundled encoder.
;;
;; axe-core is intentionally NOT vendored. The shadow-cljs / Closure
;; :advanced strict ECMAScript parser trips on axe-core's UMD wrapper.
;; Until shadow's Closure is upgraded (or axe-core ships an ESM build),
;; the a11y panel loads axe-core from the public CDN only after explicit
;; dev opt-in, pinned to a specific version + SRI hash for tamper-
;; detection. See `re-frame.story.ui.a11y` for the consent-prompt UI.

{:npm-deps {}}
