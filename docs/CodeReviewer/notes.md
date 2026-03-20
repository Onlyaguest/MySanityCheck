# CodeReviewer — Review Notes

Reviewed: 2026-03-20

Scope: all source files in `src/ems/`, `bb.edn`, `config.edn`, all agent notes.

---

## 1. Per-File Findings

### bb.edn

- **Pod version mismatch (P0).** Declares `org.babashka/go-sqlite3 {:version "0.3.1"}`, but `screentime.clj` calls `(pods/load-pod 'org.babashka/go-sqlite3 "0.3.13")`. One of these will fail at runtime. Pick one version and use it everywhere.
- **Missing dep: `babashka.http-client`.** `roam.clj` requires `babashka.http-client` — this is built into Babashka, so OK. But `org.httpkit.server` used in `core.clj` is also a bb built-in. Confirm the bb version supports both.
- **Missing dep: `http-kit`.** `core.clj` requires `org.httpkit.server`. http-kit is built into Babashka, but verify the target bb version includes it.
- **No test task.** QAEngineer designed a test runner config, but `bb.edn` has no `test` task. Not a blocker for v0 run, but needed soon.

### config.edn

- **Rates defined but never read.** `config.edn` has full `:energy :rates`, `:mood :rates`, `:screen-decay` maps, but `engine/rates.clj` hardcodes all values. The config is dead weight right now. Not a P0, but misleading — either read from config or remove the duplication.
- **Rate key mismatch.** Config uses `+` prefix (`+5`, `+10`) which are valid Clojure longs, but the event type keywords differ from `rates.clj`. Config has `:meeting-per-hour`, `:context-switch`, `:task-complete`, `:positive-feedback`, `:family-conflict`, `:work-setback`, `:social-pressure`, `:body-discomfort`. `rates.clj` has `:meeting`, `:task-done`, `:feedback-pos`, `:family-fight`, `:sick`. These will never align if someone tries to load config rates.
- **`secrets-file` path is relative.** Works only if CWD is the project root. Fine for `bb run` from project dir, fragile under launchd. Consider resolving relative to config file location.

### core.clj

- **`config` is a top-level def with side effects (P0).** `(clojure.edn/read-string (slurp "config.edn"))` runs at namespace load time. If the file is missing or malformed, the entire process crashes with an opaque error. Wrap in a function or add try/catch.
- **`clojure.edn/read-string` without readers (P1).** Config contains sets (`#{...}`). `clojure.edn/read-string` handles sets natively, so this is OK. But if anyone adds tagged literals later, it'll break silently. Consider passing `{:readers {}}` explicitly to fail fast on unknown tags.
- **`/state` endpoint returns static placeholder.** It returns `{:status "ok"}` instead of actual engine state. The engine is never called. This is the biggest gap — there's no wiring between collectors → engine → API.
- **No scheduler.** The TODO comment says it all. Without a scheduler, collectors never run, engine never computes, state is never updated. The daemon starts and serves a static JSON response forever.
- **`@(promise)` to block.** Idiomatic for bb daemons, fine.
- **No graceful shutdown.** http-kit's `run-server` returns a stop function. It's discarded. Minor for v0.

### db.clj

- **Double pod loading (P0).** Both `db.clj` and `screentime.clj` call `(pods/load-pod ...)` independently, with different versions (`"0.3.1"` vs `"0.3.13"`). Loading the same pod twice may error or cause undefined behavior. Load the pod once in `core.clj` or a shared init namespace.
- **`screentime` table schema vs collector output mismatch (P1).** Table has `pickups` column, but collector output uses `:unlock-count`. Table has `context_switches`, collector uses `:app-switches`. Table has `app_distribution` (TEXT), collector outputs `:app-usage` (map). Nobody writes to this table anyway (no insert code exists), but when someone does, the names won't match without a mapping layer.
- **No insert functions.** `db.clj` provides generic `query` and `execute!`, but no domain-specific insert helpers for events, screentime, or daily_state. Every caller will need to write raw SQL.
- **`db-path` is a hardcoded relative path.** Same CWD concern as config. Under launchd, CWD may not be the project root.

### engine.clj

- **`compute-state` signature differs from all contracts (P1).** Actual: `(compute-state screen-time-data roam-data config now)` — four separate args. EngineBuilder's notes proposed `(compute-state collected-data config now)` with a single collected-data map. SystemArchitect proposed `(compute-state db as-of)`. The implementation works, but callers (core.clj, tests) need to know the actual signature. Document it or align.
- **`apply-screen-decay` uses wrong keys from `rates.clj` (P0).** Calls `(:heavy rates/screen-decay)`, `(:moderate rates/screen-decay)`, `(:light rates/screen-decay)`. But `rates.clj` doesn't define these — it defines `:heavy`, `:moderate`, `:light`. Wait — actually it does: `{:heavy -5 :moderate -3 :light -1}`. OK, this is fine. But the engine also references `rates/switch-penalty` and `rates/natural-decay` which do exist. ✓ Verified correct.
- **`evaluate-alerts` takes `time-quality-ratio` but callers pass `(:ratio tq)`.** The ratio is a double 0.0–1.0. `fragmentation-threshold` is 0.3. The comparison `(< time-quality-ratio 0.3)` means "alert when less than 30% deep work." This is correct but the parameter name is confusing — it's a deep-work ratio, not a fragmentation ratio. High fragmentation = low ratio. The logic is right, the naming is inverted.
- **`recommend-task` uses `case` on a vector (P1).** `(case [hi-e hi-t hi-m] [true true true] ...)` — in Clojure, `case` compiles to a lookup table and works with vectors of constants. This is fine in JVM Clojure. **Verify this works in Babashka.** If bb's `case` doesn't support vector dispatch, this silently falls through to nil (no default clause). Add a `:else` default.
- **No `case` default clause.** If none of the 8 combos match (shouldn't happen with booleans, but defensive coding), `recommend-task` returns nil. Add a default.
- **`parse-time` only extracts hour:minute, ignores timezone.** The regex `#"T(\d{2}):(\d{2})"` works for ISO strings but treats all times as local. Since the system runs on one Mac in one timezone, this is acceptable for v0.
- **`hours-since-morning` hardcodes 08:00 wake time.** Config has `:sleep-hours 8` but no wake time. If the user wakes at 06:00 or 10:00, decay calculation is wrong. Minor for v0 if user's schedule is consistent.
- **Mood regression not implemented.** `rates.clj` defines `mood-regression-rate 2` (mood drifts toward baseline 80 over time), but `compute-state` never applies it. Mood only changes via events. This means mood stays at 80 all day if no mood-affecting events occur — which may be intentional for v0 but diverges from the spec.
- **`calc-trend` uses `nth` without bounds check.** `(nth vals (- (count vals) 2))` is safe because the `(< (count vals) 2)` guard runs first. ✓ OK.
- **`:date` extraction assumes string length.** `(subs (str now) 0 10)` — if `now` is nil, `(str nil)` = `""`, and `subs` throws StringIndexOutOfBoundsException. The `(when now ...)` guard handles this. ✓ OK.

### engine/rates.clj

- **Clean, no issues.** Constants are well-organized. Event rate keys are consistent internally.
- **`mood-regression-rate` defined but unused** (see engine.clj note above).

### engine/complaint.clj

- **Duplicate keyword set.** `complaint.clj` defines 13 keywords. `config.edn` defines 9 keywords. `roam.clj` defines its own set of ~15 keywords (including English ones). Three sources of truth for the same concept. Only `complaint.clj` is used by the engine. The others are dead code or will cause inconsistency if someone "fixes" the wrong one.
- **`complaint?` returns `nil` for nil/blank input, not `false`.** The `when` form returns nil if the guard fails. Callers use it in boolean context (`if has-complaint`), so nil works as falsy. But `(boolean (complaint? nil))` = false, while `(complaint? "ok")` could return `false` (from the `boolean` call when no keyword matches). Semantically fine, just be aware nil vs false.

### collector/screentime.clj

- **Pod version mismatch with bb.edn (P0).** Loads `"0.3.13"`, bb.edn declares `"0.3.1"`. See bb.edn note.
- **`Math/round` returns long.** `(Math/round (/ ... 60.0))` — fine, `:total-minutes` will be a long. Engine expects numeric, works.
- **No error handling for missing DB or permission denied (P1).** If knowledgeC.db doesn't exist or FDA isn't granted, `sqlite/query` will throw. The whole process crashes. Need try/catch returning empty vec or nil with a log warning.
- **No `:since` validation.** If `:since` is nil, `unix->cd` computes `(- nil 978307200)` → NPE. Callers must always provide `:since`.
- **Column name casing.** SQLite query returns `ZVALUESTRING`, `start_ts`, `end_ts` (mixed case due to aliases). The `bucket-app-usage` destructures `{:keys [ZVALUESTRING start_ts end_ts]}` — this works because go-sqlite3 pod returns keyword keys matching the column names/aliases. ✓ Verified.

### collector/roam.clj

- **Duplicate complaint detection (P1).** Defines its own `complaint?` fn and `complaint-keywords` set, separate from `engine/complaint.clj`. The Roam collector returns `:morning-complaint?` (boolean), but the engine ignores it and re-checks `:morning-text` via its own `complaint/complaint?`. The Roam version includes English keywords ("tired", "bad", etc.) that the engine version doesn't. Pick one source of truth.
- **`roam-date-title` ordinal suffix bug (P1).** The suffix logic: `(<= 11 day 13)` → "th" is correct (11th, 12th, 13th). But `(= 1 (mod day 10))` catches day 1, 11, 21, 31. Day 11 is already caught by the first branch. Day 21 → "21st", day 31 → "31st" — correct. ✓ Actually fine on closer inspection.
- **`block->time` uses `:create/time` with namespace.** The destructuring `{:keys [create/time]}` — in Clojure, `(:create/time m)` works for namespaced keys. The `{:keys [create/time]}` destructuring binds the local name `time`, shadowing `clojure.core/time`. Not a bug but a smell. The Roam API may or may not return `:create/time` — depends on the pull pattern. If missing, `time` is nil, `block->time` returns nil. Events get `:time nil`. Engine's `parse-time` handles nil. ✓ Graceful.
- **`concat` returns lazy seq, not vector.** `:events` in the return map uses `(concat ...)` which returns a lazy seq. Engine's `apply-events` calls `(reduce apply-event state events)` — reduce works on lazy seqs. ✓ OK, but `(vec (concat ...))` would be more consistent with the rest of the output (which uses `mapv`).
- **No retry/backoff on Roam API failure.** DataEngineer's notes mention this as a TODO. `roam-post` catches all exceptions and returns nil. `collect` will return a map with nil/empty values. Engine handles this gracefully (empty events, nil morning-text → no complaint → defaults). Acceptable for v0.
- **API token in config.** Roam graph + token come from `secrets.edn` (per config.edn `:secrets-file`). Good — not hardcoded. But `roam.clj` doesn't read config itself; it expects the caller to pass `{:graph :token :date}`. The wiring in `core.clj` doesn't exist yet.

### discord.clj

- **`format` may not exist in Babashka (P1).** `(format "%.1f" ...)` — Babashka supports `format` (it delegates to Java's `String/format`). ✓ Should work.
- **No Ed25519 signature verification (P1).** Discord requires verifying interaction signatures. `handle-interaction` returns a response map but there's no verification middleware. Without it, Discord will reject the Interactions Endpoint URL during registration, or anyone can forge slash command requests. DiscordDev flagged this — still unresolved.
- **`handle-interaction` doesn't parse Discord's POST body.** It takes `state` and `dashboard-url` directly. There's no code to extract these from an actual Discord interaction HTTP request. The handler in `core.clj` doesn't route to Discord at all.
- **Webhook URL not configured.** `send-alert!` and `send-summary!` take `webhook-url` as a parameter. Good — no hardcoding. But nobody calls these functions yet.
- **No error handling on webhook POST.** If Discord returns 429 (rate limit) or 5xx, the error propagates up. Should catch and log.

---

## 2. Cross-Module Contract Mismatches

### Engine ↔ Collectors

| Issue | Detail |
|-------|--------|
| **`compute-state` expects 4 args, no caller exists** | Engine wants `(compute-state screen-time-data roam-data config now)`. Core.clj never calls it. When wired, the caller must split collector outputs into screen-time vec and roam map separately. |
| **Roam collector `:events` types vs engine rates** | Roam emits `:aha`, `:friction`, `:sprint`. Engine's `event-rates` has these. ✓ Match. But Roam doesn't emit `:social-drain`, `:family-time`, `:solo-rest`, `:outdoor`, `:nap`, `:deep-convo`, `:meeting`, etc. These events have no collection source — they'd need manual Roam tagging or another input. |
| **Calendar data missing** | `compute-time-quality` takes `calendar` param (expects `{:meeting-minutes N}`). No collector produces this. Will always be nil → `(get nil :meeting-minutes 0)` → 0. Works but time quality ignores meetings entirely. |

### Engine ↔ Discord

| Issue | Detail |
|-------|--------|
| **`format-state` expects engine output shape** | Accesses `:energy :value`, `:energy :emoji`, `:mood :value`, `:mood :status`, `:time-quality :available-hours`, `:time-quality :emoji`, `:recommendation :task-type`. Engine's `compute-state` output matches this. ✓ |
| **`handle-interaction` expects `(:date state)`** | Engine outputs `:date` at top level. ✓ Match. |

### Engine ↔ Frontend (API contract)

| Issue | Detail |
|-------|--------|
| **FrontendDev expects different key structure** | Frontend notes show `{:current {:energy {...} :time {...} :mood {...}} :timeline [...]}`. Engine outputs `{:energy {...} :mood {...} :time-quality {...} :events [...]}`. Keys differ: `:time` vs `:time-quality`, `:timeline` vs `:events`, nested `:current` wrapper vs flat. Needs a transformation layer in the API handler. |
| **Event shape mismatch** | Frontend expects `:event` and `:label` keys. Engine outputs `:tag` and `:label`. Minor rename needed. |

### Engine ↔ SystemArchitect API spec

| Issue | Detail |
|-------|--------|
| **API returns JSON with snake_case** | SystemArchitect's spec shows `energy_status`, `time_available`, `time_quality`. Engine outputs camelCase/kebab-case Clojure keywords. The JSON serialization layer needs key transformation. |

---

## 3. Missing Pieces for v0 to Actually Run

1. **No scheduler / orchestration loop.** Core.clj starts an HTTP server and blocks. Nobody calls collectors or engine on any interval. This is the #1 missing piece.

2. **No wiring: collectors → DB → engine → API.** The pieces exist in isolation but are never connected. Need:
   - Scheduler calls `screentime/collect` and `roam/collect` on intervals
   - Results stored in DB (insert functions missing) or passed directly to engine
   - Engine `compute-state` called with collected data
   - Result cached (atom or DB) for API to serve

3. **No DB read/write for collector data.** `db.clj` has schema and generic helpers but no insert/select functions for screentime or events. Collectors return EDN maps but nobody persists them.

4. **`/state` API returns placeholder.** Needs to call engine and return real state.

5. **Pod loading strategy.** Two files load the same pod with different versions. Need single load point.

6. **Secrets loading.** `core.clj` loads `secrets.edn` into config, but Roam collector and Discord bot need specific keys extracted and passed to them. No code does this.

7. **Discord slash command registration.** One-time setup via Discord REST API. No script or instructions exist.

8. **Ed25519 verification.** Required for Discord interactions endpoint. No implementation.

9. **launchd plist.** SystemArchitect mentions it, no file exists.

---

## 4. Priority Fixes

### P0 — Blocks Running

| # | Issue | File(s) |
|---|-------|---------|
| 1 | **Pod version mismatch** — bb.edn says 0.3.1, screentime.clj loads 0.3.13. Will fail at pod load. | `bb.edn`, `screentime.clj`, `db.clj` |
| 2 | **Double pod loading** — db.clj and screentime.clj both call `load-pod`. Load once in core.clj. | `db.clj`, `screentime.clj`, `core.clj` |
| 3 | **Top-level side-effecting `def` in core.clj** — config load at ns-load time. If file missing → crash before anything starts. | `core.clj` |
| 4 | **No orchestration loop** — collectors and engine are never invoked. System starts and does nothing. | `core.clj` |

### P1 — Should Fix Before Shipping

| # | Issue | File(s) |
|---|-------|---------|
| 5 | **`/state` returns placeholder** — wire engine output to API response. | `core.clj` |
| 6 | **No error handling in screentime collector** — missing DB or no FDA → unhandled exception → process crash. | `screentime.clj` |
| 7 | **Duplicate complaint detection** — three separate keyword sets. Consolidate to one source. | `complaint.clj`, `roam.clj`, `config.edn` |
| 8 | **DB column names don't match collector output keys** — `pickups` vs `unlock-count`, `context_switches` vs `app-switches`. | `db.clj`, `screentime.clj` |
| 9 | **Frontend API contract mismatch** — engine output shape ≠ what FrontendDev expects. Need transform layer. | `core.clj` (future `api.clj`) |
| 10 | **`case` on vector in `recommend-task`** — verify bb support, add default clause. | `engine.clj` |
| 11 | **Config rates never read** — config.edn rates are ignored; rates.clj hardcodes everything. Remove duplication or wire config. | `config.edn`, `rates.clj` |
| 12 | **No Discord Ed25519 verification** — interactions endpoint won't work without it. | `discord.clj` |
| 13 | **Mood regression not implemented** — spec says mood drifts toward baseline; code doesn't do it. | `engine.clj` |
| 14 | **Relative file paths** — `config.edn`, `secrets.edn`, `ems.db` all relative. Fragile under launchd. | `core.clj`, `db.clj` |

---

*End of review. Happy to discuss any finding or help prioritize the fix order.*
