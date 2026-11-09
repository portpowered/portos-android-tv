# Changelog

## 0.9.0

- Generative UI: the TV holds a WebSocket to the device gateway and renders A2UI v1.0 surfaces (Text, Image, Icon,
  Row, Column, List, Card, Divider, Timer) with an own Compose for TV renderer. Design tokens come from the website's
  generated `PortosTokens.kt` (`make sync-tokens`).
- Out-of-order surface messages are held for 5 seconds, then the connection resumes from `lastSequence` (with
  `surfaceId` and `showId`).
- Reports, command outcomes, screenshots and `get-surface-response` go through `POST /messages` with the user's access
  token and an `Idempotency-Key`; the signed JWS installation webhook is no longer used.
- The app registers its display capability and refreshes its token after pairing; a refused refresh shows "pair again".
- Screenshot tests (Roborazzi) and a lint rule that bans raw colors and sizes in the renderer.
