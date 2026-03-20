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

## tmux Users

FDA is granted to the **parent process** (Terminal.app), not child processes. If your tmux server was started before FDA was granted, bb inside tmux still gets "Operation not permitted" — even after toggling FDA on.

**Workaround:** Launch bb from Terminal.app directly:
```bash
osascript -e 'tell application "Terminal" to do script "cd /Users/yuan/ems && /usr/local/bin/bb run"'
```
This opens a new Terminal.app window (which inherits FDA) and runs bb there.

Alternatively: kill your tmux server (`tmux kill-server`) and restart it from a Terminal.app session that already has FDA.
