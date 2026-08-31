---
name: project-nul-artifact
description: Untracked "NUL" file in repo root is a Windows redirect artifact, not versioned data
metadata:
  type: project
---

Im Repo-Wurzelverzeichnis liegt eine untracked Datei namens `NUL`
(Windows-Artefakt, vermutlich durch eine Umleitung wie `> NUL` in einer
Bash/PowerShell-Mischsitzung entstanden). Sie ist nicht in Git versioniert
(`git ls-files` findet sie nicht) und taucht bei jedem `git status` als
untracked auf.

**Why:** Der Director hat den Archivist explizit angewiesen, sie nicht zu
loeschen, sondern nur zu melden, ob sie versioniert ist (Stand
2026-08-30: nein).

**How to apply:** Bei jedem `sync-out`/`status` in diesem Repo die Datei
ignorieren (nicht committen, nicht loeschen) und im Bericht nur erwaehnen,
falls sich ihr Tracking-Status aendert oder sie neu auftaucht. Ein
`.gitignore`-Eintrag fuer `/NUL` waere sinnvoll, aber das ist ein
Vorschlag an den Director/Nutzer, keine eigene Aktion.
