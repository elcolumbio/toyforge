# Feedback for the next round

## "Send to printer" button (printer box on the home network)

A small print service ("printer box") can run on a computer on the home network. It takes a ToyForge 3MF, slices
it for the printer, copies the result to the printer's storage and notifies a grown-up. The grown-up starts the
print in Bambu Handy after checking that the plate is clear. **The app never starts a print**, so the printer can
stay in normal cloud mode, without LAN-only mode or developer mode.

### API (plain HTTP on the home Wi-Fi)

- `GET  <box>/health` returns
  `{"ok":true,"printer":{"gcode_state":"IDLE"|"RUNNING"|"FINISH"|…,"mc_percent":…},"can_upload":true,"can_notify":true}`.
  `printer` may be `{}` when the printer didn't answer in time.
- `POST <box>/print`: the body is the raw bytes of the Bambu project 3MF the app already builds
  (`BambuProject.toBytes(..., thumbnailPng = <plate thumbnail>)`), sent as `Content-Type: application/octet-stream`.
  At most 64 MB.
  - It answers `202 {"job":"<12 hex>","status":"/jobs/<id>","name":"<toy name>"}`.
  - It answers `400` if the body isn't a ToyForge 3MF, and `413` if it is too big.
- `GET  <box>/jobs/<id>` returns `{"state":"queued"|"slicing"|"uploading"|"done"|"failed", "print_seconds":12441,
  "grams":74.4, "busy":false, "error":"…" (only when failed)}`.
  A typical toy goes from queued to done in 30–90 s; most of that is the upload to the printer.

### App changes

1. **Grown-ups settings:** add a "Printer box" URL field, empty by default, for example `http://<box-ip>:8790`.
   - Next to it, a "Test" button calls `/health`.
   - It shows "Printer box OK – printer is free", "…busy (73%)" or "can't reach the printer box".
2. **Print dialog:** when the box URL is set, "Send to printer" becomes the first and biggest action, above the
   share and save options. It sends the same project 3MF that "Send to Bambu / Orca" shares, including the thumbnail.
3. **Progress, kid-friendly:** poll `/jobs/<id>` every 2 s, with a 3-minute timeout.
   - While it runs: "Sending to the printer…", animated.
   - On `done`: "Your toy is waiting at the printer! Ask a grown-up to start it." Add confetti, and show
     `print_seconds` as "about 1 h 20 min" and `grams`.
   - On `failed` or timeout: "The printer box had a problem". Put the `error` text behind a grown-ups-only details link.
4. **Printer without AMS:** add a grown-ups setting "Printer has AMS (several colours)", off by default.
   - When it is off, the print dialog picks "One colour (fast)", so the time and grams match what gets printed.
     The box may also force one colour on its side. The same applies to the "make it smaller" offer.
5. **Cleartext HTTP:** targetSdk 35 blocks plain HTTP, and the box is reached on a LAN address.
   - Allow cleartext only where it is needed: a `network_security_config` that permits cleartext. A `domain-config`
     can't follow a user-entered IP.
   - Keep every Claude API call on HTTPS.
6. **Tests:** run a fake box with `HttpTransport` (as the AI tests do).
   - Covers 202 then polling to done, failed, unreachable and timeout.
   - Covers that the POST body is exactly the project 3MF including `Metadata/plate_1.png`.

### Not in scope

The app never talks to the printer directly. The app never starts a print. No Bambu account is involved.
