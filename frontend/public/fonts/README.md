# Self-hosted fonts

The setup wizard's "Hello" moment uses **Homemade Apple** (Apache 2.0, by
Font Diner) for that handwritten feel. We self-host it so a fresh Picsou
install makes zero outbound requests to Google Fonts or any CDN — aligned
with the project's privacy/OSS posture.

## What to drop here

`HomemadeApple-Regular.woff2` — ~23 kB. Fetch once and commit:

```bash
# From https://fonts.google.com/specimen/Homemade+Apple/license
# Download the family zip, convert the TTF to woff2 if needed:
#   brew install woff2
#   woff2_compress HomemadeApple-Regular.ttf
cp HomemadeApple-Regular.woff2 frontend/public/fonts/
```

## Graceful fallback

If the woff2 is missing, the CSS declares a fallback stack
(`'Segoe Script', 'Snell Roundhand', cursive`) so the wizard still renders
something legible — just less special. The page will NOT try to download
the font from Google as a backup; that would defeat the point.
