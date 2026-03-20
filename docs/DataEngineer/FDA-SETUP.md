# Full Disk Access Setup (macOS)

The Screen Time collector reads `~/Library/Application Support/Knowledge/knowledgeC.db`. macOS requires Full Disk Access for the terminal process that runs `bb`.

## Steps

1. Open **System Settings → Privacy & Security → Full Disk Access**
2. Click **+**, navigate to `/Applications/Utilities/` and add **Terminal.app** (or your terminal: iTerm2, Alacritty, Warp, etc.)
3. **Quit and reopen** your terminal

## Verify

```bash
ls -la ~/Library/Application\ Support/Knowledge/knowledgeC.db
```

If you see the file listing (not "Operation not permitted"), FDA is working.

## Notes

- This is a one-time setup per terminal app.
- If you run `bb` via launchd, the launchd agent itself needs FDA — add `/usr/local/bin/bb` (or wherever `bb` lives) to the Full Disk Access list.
- FDA grants read access only. The collector opens knowledgeC.db in read-only mode.
