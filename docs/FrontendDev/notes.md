# FrontendDev — Design Notes

**Updated:** 2026-03-20 — P0 contract verification done.

## Stack: Babashka on Vercel

`vercel-babashka` runtime. Dashboard HTML generated server-side with hiccup. No JS framework.

### Architecture

```
Vercel project
├── api/
│   └── dashboard.clj      # Serverless fn: fetch state → hiccup → HTML
├── public/
│   └── style.css           # Mobile-first CSS
└── vercel.json
```

**Flow:** Discord link → Vercel babashka fn → fetch state from cloud store → hiccup → HTML response.

## API Contract — Verified Against Engine

Reviewed `src/ems/engine.clj` `compute-state` output. The engine returns a **flat** map:

```clojure
;; Engine actual output shape:
{:date           "2026-03-19"
 :timestamp      "2026-03-19T14:30:00+08:00"
 :energy         {:value 72 :status "良好" :emoji "🟡" :trend :falling}
 :mood           {:value 85 :status "愉悦" :emoji "😊" :trend :stable}
 :time-quality   {:ratio 0.7 :deep-min 180 :frag-min 45
                  :available-hours 3.5 :status "适中" :emoji "🟡"}
 :recommendation {:task-type "高难度创造" :suggestions ["系统架构设计" "深度写作"]}
 :alerts         [{:type :exhaustion :severity :critical :message "..."}]
 :events         [{:time "09:30" :tag :sprint :label "深度写作 20min"
                   :deltas {:energy -18 :mood 0}
                   :snapshot {:energy 82 :mood 80}}]
 :phase          :action}
```

### Mismatches Found (from my original notes)

| My original expectation | Engine actual | Resolution |
|------------------------|---------------|------------|
| `:current` wrapper around three lines | Flat — `:energy`, `:mood`, `:time-quality` at top level | **I adapt.** Read flat keys directly. |
| `:time` key | `:time-quality` | **I adapt.** Use `:time-quality`. |
| `:timeline` key | `:events` | **I adapt.** Use `:events`. |
| `:event` in timeline items | `:tag` | **I adapt.** Use `:tag`. |
| `:suggestion` (singular string) | `:suggestions` (vec of strings) | **I adapt.** Render as list. |
| No `:alerts` in my original spec | Engine provides `:alerts` vec | **Bonus.** I'll render alerts if present. |
| No `:phase` in my original spec | Engine provides `:phase` | **Bonus.** I can show current phase. |
| No `:trend` in my original spec | Engine provides `:trend` per line | **Bonus.** I'll show trend arrows. |

**Conclusion: No changes needed from EngineBuilder.** All mismatches are on my side. I'll consume the engine output as-is.

## Rendering Plan (updated)

```
┌─────────────────────────────┐
│  EMS — 2026-03-19           │
├─────────────────────────────┤
│  🚨 Alert (if any)          │  ← from :alerts
├─────────────────────────────┤
│  ⚡ Energy  72  🟡 ↘        │  ← :value :emoji :trend
│  ⏱ Time    3.5h 🟡         │  ← :available-hours :emoji
│  😊 Mood    85   😊 →       │
│  Phase: action              │
├─────────────────────────────┤
│  Events                     │
│  09:30 :sprint 深度写作      │  ← :tag :label
│        E:-18  M:0           │  ← :deltas
│  ...                        │
├─────────────────────────────┤
│  💡 高难度创造               │  ← :task-type
│  • 系统架构设计              │  ← :suggestions
│  • 深度写作                  │
└─────────────────────────────┘
```

## Open Questions (updated)

1. **Cloud relay** — Still the biggest blocker. Where does state JSON live for Vercel to read? Need SystemArchitect.
2. **Auth** — Daily HMAC token in URL. Need EngineBuilder + DiscordDev agreement.
3. **Sparklines** — Engine emits snapshots per event. I can plot these. If scheduler runs every 30min, I'll also get periodic snapshots. Sufficient for v0.
4. **Scope** — v0 = today only.

## Dependencies

| From | Need | Status |
|------|------|--------|
| SystemArchitect | Cloud relay decision | ⏳ Waiting |
| EngineBuilder | API shape | ✅ Verified — I adapt to engine output |
| DiscordDev | URL format + token | ⏳ Waiting |
| QAEngineer | Test cases for dashboard | ⏳ Waiting |
