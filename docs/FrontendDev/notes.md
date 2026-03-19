# FrontendDev — Design Notes

## Stack: Babashka on Vercel

Using `vercel-babashka` runtime — Babashka scripts in `api/` served as Serverless Functions. Dashboard HTML generated server-side with **hiccup**, no JS framework.

### Architecture

```
Vercel project
├── api/
│   └── dashboard.clj      # Serverless fn: fetch state → hiccup → HTML
├── public/
│   ├── style.css           # Mobile-first CSS
│   └── chart.js            # Tiny JS for sparklines (optional)
└── vercel.json             # Routes + babashka runtime
```

**Flow:**
1. Discord bot sends: `https://ems.vercel.app/api/dashboard?token=<daily-token>&date=2026-03-19`
2. Babashka fn fetches state from cloud store → renders hiccup → returns HTML
3. No client-side API calls needed

### Why Server-Side Hiccup

- Zero JS deps for core render
- Entire dashboard in one `.clj` file
- Faster mobile load (no fetch-then-render)
- Tiny JS only for optional sparklines

## Page Layout

```
┌─────────────────────────────┐
│  EMS Dashboard — 2026-03-19 │
├─────────────────────────────┤
│  ⚡ Energy    72/100  🟡    │
│  ⏱ Time      3.5h    🟡    │
│  😊 Mood      85/100  😊    │
├─────────────────────────────┤
│  Timeline                   │
│  08:00  晨间校准             │
│         E: =100  M: =80     │
│  09:30  #Sprint 深度写作     │
│         E: -18   M: +8      │
├─────────────────────────────┤
│  💡 推荐: 高难度创造         │
└─────────────────────────────┘
```

## API Data Shape Needed

```clojure
{:date "2026-03-19"
 :current {:energy {:value 72 :status "good" :emoji "🟡"}
           :time   {:available 3.5 :status "moderate" :emoji "🟡"}
           :mood   {:value 85 :status "pleasant" :emoji "😊"}}
 :timeline [{:time "08:00"
             :event "morning_calibration"
             :label "晨间校准"
             :deltas {:energy 0 :mood 0}
             :snapshot {:energy 100 :mood 80}}
            {:time "09:30"
             :event "#Sprint"
             :label "深度写作 20min"
             :deltas {:energy -18 :mood 8}
             :snapshot {:energy 82 :mood 88}}]
 :recommendation {:task-type "高难度创造"
                  :suggestion "系统架构设计、深度写作"}}
```

## Open Questions

1. **Cloud relay** — Where does state live so Vercel can read it? (Vercel KV / S3 / R2?) Need SystemArchitect.
2. **Auth** — Proposal: daily HMAC token in URL, validated by dashboard fn. Need EngineBuilder + DiscordDev agreement.
3. **Sparklines** — Need hourly snapshots or event-only? Will interpolate if event-only.
4. **Scope** — v0 = today only. Week/month views deferred to v1.

## Dependencies on Other Agents

| From | Need |
|------|------|
| SystemArchitect | Cloud relay / data store decision |
| EngineBuilder | Confirm data shape + hourly snapshots vs event-only |
| DiscordDev | URL format + token scheme |
| QAEngineer | Test cases: empty state, expired token, timezone edges |
