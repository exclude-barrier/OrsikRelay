# Hermes Android – Netzwerk-Deployment

## Ziel

Die App soll mit selbst betriebenen Hermes-Instanzen funktionieren, ohne an einen bestimmten VPN-Anbieter oder eine bestimmte Serverdomain gebunden zu sein. Sie bleibt ein Thin Client: Hermes und alle privilegierten Tools laufen auf dem Server.

## Verbindungsvertrag

Die App benötigt pro Serverprofil:

- eine absolute HTTPS-Basis-URL;
- einen Hermes-Bearer-Key;
- eine über Android-Systemrouting und System-DNS erreichbare Route.

Vor dem Speichern prüft die App `GET /health` und das authentifizierte `GET /v1/capabilities`. URL und unkritische Profilmetadaten werden getrennt vom Key gespeichert. Der Key wird mit einem AES-256-GCM-Schlüssel aus Android Keystore verschlüsselt; private SharedPreferences enthalten ausschließlich versionierten Ciphertext und IV.

Die App enthält kein WireGuard- oder Tailscale-SDK und erkennt keinen VPN-Anbieter. VPN-Aufbau, Routing und DNS bleiben Aufgabe von Android und der jeweiligen VPN-App. Dadurch funktionieren LAN, WireGuard und später Tailscale über denselben HTTPS-Client.

## Sicherheitsgrenzen

- Kein Klartext-HTTP und kein Trust-All-/TLS-Bypass.
- Aktuell nur Zertifikate, denen Android über den normalen System-Trust-Store vertraut. Private oder selbstsignierte CAs sind im ersten Play-Store-Slice nicht unterstützt.
- Kein direktes Port-Forwarding der Hermes API ins Internet.
- Der Hermes-Bearer bleibt auch hinter VPN oder Reverse Proxy zwingend.
- Reverse Proxies dürfen den `Authorization`-Header nicht entfernen oder an ein anderes Ziel umleiten.
- Keine automatische Bestätigung von Hermes-Approvals.
- Keine Request-/Response-Body-Logs, Schlüssel, Sessioninhalte oder privaten Serverdaten in Telemetrie oder Crashreports.
- Kein automatischer Retry schreibender oder riskanter Requests.

## Unterstützte Deploymentmuster

### 1. LAN mit HTTPS

Der Server ist ausschließlich im lokalen Netz erreichbar. Ein Reverse Proxy stellt eine HTTPS-Domain mit öffentlich vertrauenswürdigem Zertifikat bereit. Die Domain kann über Split-DNS oder einen lokalen Resolver auf die private Adresse zeigen.

Geeignet für: Nutzung ausschließlich im Heim- oder Firmennetz.

### 2. WireGuard zu einem privaten Netz

Das Smartphone baut außerhalb des Standorts einen WireGuard-Tunnel zum privaten Netz auf. Die App verwendet danach dieselbe HTTPS-Domain wie im LAN.

Empfohlene Serverseite:

1. Hermes API bleibt auf Loopback gebunden.
2. Ein interner Bridge-/Socket-Proxy macht sie nur für den Reverse Proxy erreichbar.
3. Der Reverse Proxy terminiert öffentlich vertrauenswürdiges TLS.
4. Eine Quellnetz-Allowlist erlaubt ausschließlich die tatsächlich verwendeten LAN-/WireGuard-Quellnetze und verweigert alle anderen Quellen.
5. Der Hermes-Bearer wird unverändert an die API weitergegeben und dort zusätzlich geprüft.

Geeignet für: aktuelles privates Referenzdeployment.

### 3. Tailscale Serve

Tailscale kann später als alternatives Routingmodell verwendet werden. Tailscale Serve terminiert HTTPS innerhalb eines Tailnets und leitet auf die loopbackgebundene Hermes API weiter. Die Android-App bleibt unverändert; der Nutzer trägt lediglich die HTTPS-Domain des Tailnet-Dienstes ein. Tailscale Funnel ist für die private Hermes API nicht vorgesehen.

Geeignet für: Nutzer, die bereits ein Tailnet betreiben oder keinen eigenen Reverse Proxy konfigurieren möchten.

### 4. Eigenständiges Mobile-/OIDC-Gateway

Optional kann später ein speziell entwickeltes Gateway OIDC/PKCE für mobile Clients anbieten und serverseitig mit Hermes kommunizieren. Authentik Forward Auth direkt vor der Bearer-API ist nicht automatisch kompatibel, weil Browser-Login und Hermes-Bearer zwei unterschiedliche Authentifizierungsverträge sind.

Geeignet für: verwaltete Mehrbenutzerinstallationen; nicht Teil des ersten privaten Slices.

## Nicht unterstützte Muster

- Hermes API direkt auf einer öffentlichen Host-Portweiterleitung;
- öffentliche Tailscale Funnel-Freigabe;
- Klartext-HTTP im LAN oder VPN;
- deaktivierte Zertifikatsprüfung;
- Einbetten einer festen Domain, eines VPN-Anbieters oder eines API-Keys in die App;
- automatische Übernahme von Reverse-Proxy-, VPN- oder OIDC-Administrationsaufgaben durch die App.

## Einrichtungscheckliste für Nutzer

1. Hermes API starten und einen getrennten Bearer-Key konfigurieren.
2. Privaten Netzwerkpfad wählen: LAN, WireGuard oder Tailscale.
3. HTTPS-Reverse-Proxy beziehungsweise Tailscale Serve einrichten.
4. Gültiges, von Android vertrautes Zertifikat prüfen.
5. Sicherstellen, dass die Hermes API nicht direkt öffentlich lauscht.
6. Bei IP-Allowlist die tatsächlichen Quellnetze testen, nicht schätzen.
7. In der App Profilname, HTTPS-Basis-URL und Bearer-Key eingeben.
8. Verbindungstest ausführen; erst nach erfolgreicher Capability-Prüfung wird das Profil gespeichert.

## Verifikation

Positive Tests:

- WLAN/LAN mit gültigem Zertifikat;
- mobiles Netz mit aktivem WireGuard;
- später Tailscale Serve im Tailnet;
- erfolgreicher Health- und Capability-Check;
- Profil wird erst nach Erfolg gespeichert.

Negative Tests:

- HTTP-URL wird lokal abgelehnt;
- unbekanntes oder ungültiges Zertifikat erzeugt einen TLS-Fehler;
- falscher Bearer-Key liefert `401`/`403` und speichert nichts;
- Zugriff ohne erforderlichen VPN-Tunnel schlägt fehl;
- Quelle außerhalb der Reverse-Proxy-Allowlist wird vor dem Backend abgewiesen;
- Redirect wird nicht verfolgt;
- Timeout und Netzwerkfehler werden ohne Server- oder Exceptiondetails angezeigt.

## Rollback

- Reverse-Proxy-Host deaktivieren oder entfernen.
- Interne Bridge stoppen und deaktivieren.
- Hermes API bleibt unverändert auf Loopback und ist danach wieder nur lokal erreichbar.
- App-Profil löschen; dadurch werden Metadaten und Keystore-Credential getrennt entfernt.
- VPN-Konfiguration unabhängig von der App zurücknehmen.

## Play-Store- und Datenschutzanforderungen

- Keine fest eingebaute persönliche Infrastruktur.
- Keine Werbe-, Tracking- oder Analyse-SDKs im ersten Release.
- Keine Übertragung des Bearer-Keys an Dritte.
- Keine sensiblen Inhalte in Logs oder Crashreports.
- Datenschutzerklärung und Play-Data-Safety-Angaben müssen dem tatsächlich gebauten Verhalten entsprechen.
- Release-Signing, Backupregeln und Datenlöschung werden vor Veröffentlichung separat geprüft.
