# Hey Claude für Android (Samsung S24)

Sag „Hey Claude“ → Piepton → Frage stellen → Claude antwortet als Popup unten am Bildschirm und liest die Antwort vor.

## APK bauen
1. Den Inhalt dieses Ordners in ein neues GitHub-Repo hochladen (inkl. `.github/workflows/build.yml` und `app/debug.keystore`).
2. Unter **Actions** läuft „APK bauen“ automatisch (oder manuell starten).
3. Nach ca. 5 Minuten unter dem Lauf → **Artifacts** → `HeyClaude-APK` herunterladen, entpacken, `app-debug.apk` installieren.

## Einrichten in der App
1. Anthropic API-Key eintragen (console.anthropic.com → API Keys). Wird separat vom Claude-Abo abgerechnet.
2. **Start** tippen und Mikrofon, Benachrichtigungen und „Über anderen Apps anzeigen“ erlauben.
   Beim ersten Start wird die Offline-Erkennung für „Hey Claude“ (ca. 40 MB) geladen.
3. **Akku: nicht einschränken** tippen. Bei Samsung zusätzlich: Einstellungen → Akku → Hintergrundnutzung-Limits → „Nie in Standby versetzte Apps“ → Hey Claude hinzufügen.
4. Optional: **Als Assistent für die Seitentaste festlegen** → dann startet langes Drücken der Seitentaste den Dialog.

## Zwei Modi
- **Standard (freihändig):** Android-Spracherkennung nimmt die Frage auf, kurz still sein → wird gesendet.
- **Wispr Flow:** Schalter in der App. Nach „Hey Claude“ geht die Tastatur auf, du tippst auf die Wispr-Blase und sprichst. Sobald der Text drinsteht, wird er automatisch gesendet.

## Gut zu wissen
- „Hey Claude“ wird offline am Handy erkannt; es geht nichts an einen Server, bevor du fragst.
- Während der Dienst läuft, siehst du eine dauerhafte Benachrichtigung (Android-Pflicht). Sie hat auch einen „Fragen“-Knopf.
- Nach einem Neustart des Handys die App einmal öffnen und **Start** tippen (Android erlaubt Mikrofon-Dienste nicht automatisch beim Hochfahren).
- In der App unter „Zuletzt gehört“ siehst du, was erkannt wurde – hilft beim Einstellen der Empfindlichkeit.
