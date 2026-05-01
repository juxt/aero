# Aero — Findings

## [high] likely_bug — src/aero/core.cljc:46

In ClojureScript, get-env passes the raw symbol s into gobj/get, but JS object key lookup expects a string; #env will return undefined/nil for any variable in CLJS.

**Spec:** resolve_env  
**Fix:** Coerce s to a string before gobj/get, e.g. (gobj/get js/process.env (str s)).

## [high] likely_bug — src/aero/core.cljc:249

#hostname JVM fallback reads environment variable HOSTNAME, which is a bash shell builtin and not exported by default on Linux/macOS, so the fallback is usually nil rather than the host's hostname.

**Spec:** resolve_hostname  
**Fix:** On JVM, fall back to InetAddress/getLocalHost.getHostName instead of the HOSTNAME env var.

## [medium] brittle_code — src/aero/core.cljc:255

#user falls back to the USER env var instead of the OS-reported current user; on Windows USER is unset (USERNAME is used) so #user resolves to nil.

**Spec:** resolve_user  
**Fix:** Use System/getProperty "user.name" on JVM (and equivalent on CLJS) rather than the USER env var.

## [medium] brittle_code — src/aero/core.cljc:116

relative-resolver silently substitutes a {:aero/missing-include path} sentinel when the include file is absent, masking misconfiguration instead of raising a clear error.

**Spec:** resolve_include  
**Fix:** Throw an ex-info with the missing path or require an explicit :missing-include policy in opts.

## [high] logic_ambiguity — src/aero/core.cljc:381

On unresolvable #ref, resolve-tagged-literals prints a WARNING and silently dissocs/nils the offending location; this behaviour is not described in the spec and can hide real config errors.

**Spec:** resolve_ref, no_unresolved_tags  
**Fix:** Throw or surface unresolved refs by default; gate the warn-and-drop behaviour behind an explicit option.

## [medium] likely_bug — src/aero/core.cljc:65

#long / #double / #boolean coercions throw raw NumberFormatException / unchecked errors with no Aero-level context (path, tag, value), making coercion failures hard to diagnose.

**Spec:** resolve_coercion  
**Fix:** Wrap parse calls in try/catch and throw ex-info with {:tag :value :path} for diagnostics.

## [low] logic_ambiguity — src/aero/core.cljc:97

#read-edn uses edn/read-string with no :readers map, so any Aero or data-reader tags inside the embedded EDN string are not recognised; the spec rule does not specify whether nested tags should resolve.

**Spec:** resolve_read_edn  
**Fix:** Either document that nested tags are not resolved, or pass an Aero-aware :readers map to edn/read-string.

## [low] business_question — src/aero/core.cljc:248

eval-tagged-literal 'hostname reads (:hostname opts), but the spec's ReadOptions does not list a hostname option; either the spec is missing this field or the option is dead/undocumented.

**Spec:** ReadOptions, resolve_hostname  
**Fix:** Add hostname (and user override) fields to the ReadOptions spec entity to match the implementation.

## [low] brittle_code — src/aero/core.cljc:400

resolve-tagged-literals' attempt-counter logic resets to 0 whenever a ref is dropped, so pathological configs with cascading unresolvable refs can spin through many warnings before the > 1 throw fires (or fail to terminate clearly).

**Spec:** resolve_ref, no_unresolved_tags  
**Fix:** Increment attempts unconditionally on ref-drop, or cap total drops; make termination criteria explicit.

## [low] likely_bug — src/aero/core.cljc:66

CLJS implementation of #long uses (js/parseInt s) without an explicit radix, so values like "08" or "0x10" parse inconsistently across runtimes.

**Spec:** resolve_coercion  
**Fix:** Pass radix 10 explicitly to js/parseInt or use Number/JS BigInt with validation.
