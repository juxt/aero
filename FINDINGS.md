# Aero — Findings vs ratified spec (`specs/aero.allium`)

Review pass over `src/aero/core.cljc` and `src/aero/alpha/core.cljc` reading the implementation through the lens of the ratified Allium spec. Findings are grouped by classification; each one names the spec entity/rule it bears on.

## Likely bugs

### 1. `#hostname` falls back to `$HOSTNAME` on the JVM (`src/aero/core.cljc:249`)
`eval-tagged-literal 'hostname` uses `(or hostname (get-env "HOSTNAME"))`. On Linux/macOS the `HOSTNAME` shell variable is not exported to child processes by default — `System/getenv("HOSTNAME")` typically returns `nil`. The match then silently misses every host key and falls through to `:default`, which contradicts the *intent* of `rule resolve_hostname`. The expected source is `java.net.InetAddress/getLocalHost.getHostName` (with the env var as a manual override).

*Fix:* use `InetAddress` on JVM; keep the env-var override path as an explicit opt.

### 2. `#user` only consults `USER` (`src/aero/core.cljc:255`)
Windows exposes the current user as `USERNAME`, not `USER`. On Windows `eval-tagged-literal 'user` will silently fail to match per-user keys and fall through to `:default`. `rule resolve_user` says "matches options.user (or current OS user)" — the implementation does not honour that on Windows.

*Fix:* try `USER` then `USERNAME`; document the `:user` opt as the portable mechanism.

### 3. Coercion readers leak raw host exceptions (`src/aero/core.cljc:63`)
`#long`/`#double` call `Long/parseLong`/`Double/parseDouble` directly, so malformed input throws `NumberFormatException` rather than an Aero `ex-info` with `{:tag … :value …}` context. `#boolean` uses `Boolean/parseBoolean`, which returns `false` for *any* non-`"true"` string — typos like `"yes"` or `"1"` silently become `false`. This is one of the items called out by the spec's open question on coercion failure behaviour, and answers it pragmatically: at minimum, errors should be wrapped.

## Brittle code

### 4. `relative-resolver` swallows missing includes as in-band data (`src/aero/core.cljc:116`)
When an include path cannot be located, both `relative-resolver` and `resource-resolver` return a `StringReader` containing `(pr-str {:aero/missing-include include})`. The result is that a missing include is silently spliced into the resolved config as a plain map. `rule resolve_include` says nothing about a sentinel; downstream code that doesn't know to look for `:aero/missing-include` will accept a structurally-valid but semantically-wrong config. Should at least be opt-in, and ideally raise.

### 5. `#envf` interpolates `(str nil)` for unset vars (`src/aero/core.cljc:54`)
`(str (get-env (str %)))` turns an unset variable into `""`. The formatted string thus contains an invisible empty segment with no signal to the caller. `rule resolve_envf` is silent on this; in practice it's a footgun, especially for URLs/paths assembled via `#envf`.

### 6. Hardcoded `attempts > 1` ceiling in resolver (`src/aero/core.cljc:400`)
`resolve-tagged-literals` throws `"Max attempts exhausted"` after two non-progressing attempts. The error contains the in-progress map but no specific cause, and the same exception is used both for genuinely cyclic graphs and for cases where the loop has simply been unable to make progress this iteration. Brittle in the sense that the diagnostic loses information just at the moment a user needs it.

### 7. cljs `adaptive-resolver` assumes `source` is a path string (`src/aero/core.cljc:136`)
The cljs branch does `(path/join source ".." include)` and `(fs/existsSync …)`. If a caller passes a Reader or a non-path source (which the JVM branch handles via the `IllegalArgumentException` catch), the cljs branch will either coerce a Reader to `"[object …]"` or throw an opaque `path` error. Asymmetric robustness across the two host platforms.

## Logic ambiguity

### 8. `#env` cannot distinguish unset from empty (`src/aero/core.cljc:48`)
`reader 'env` returns whatever `System/getenv` gives back. Both "unset" and "empty string" reduce to indistinguishable values from the caller's perspective (`nil` vs `""`), and the spec's `rule resolve_env` only says "or nil if unset". This is the spec's first open question; recommend resolving it explicitly (e.g. an option to require-set) rather than leaving it to caller convention.

### 9. Unresolvable `#ref` is downgraded to a warning + nil (`src/aero/core.cljc:378`)
When the resolver gets stuck on a `#ref` it prints `WARNING: Unable to resolve …` to `*err*` and `dissoc`/`assoc`'s `nil` in place. A reasonable strict reading of `invariant no_unresolved_tags` ("all tags either resolved successfully or surfaced as nil/error") allows nil — but the *side channel* is a stderr `println`, which is easy to miss in a server log and surprising for a library.

## Business questions

### 10. `#read-edn` semantics on empty / malformed input (`src/aero/core.cljc:96`)
`(some-> value str edn/read-string)` returns `nil` for `nil` input but throws raw `RuntimeException`/reader exceptions for malformed EDN. Should `#read-edn` of `""` be `nil`, an error, or unspecified? `rule resolve_read_edn` does not say.

## Open questions for follow-up with maintainers

- Should missing includes / unset env vars / coercion failures be normalised to a single error path (`ex-info` with `:aero/...` keys), or remain best-effort?
- Is the `#ref` warning-and-nil behaviour intended as a feature, or a debugging aid that should become an error before 2.0?
- Are the cljs and JVM branches expected to accept the same `source` shapes? Currently they diverge.
