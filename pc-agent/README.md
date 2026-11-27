# Rumin AI 3.0 — PC Agent Dashboard

This folder contains the first browser dashboard prototype for the existing Rumin AI 3.0 project.

## Preview locally
Open `index.html` in a modern browser. The navigation works locally. The Files & Photos tab can preview an image selected through the browser file picker; this is not full Windows file-system access.

## Current implementation status
- Dashboard layout and navigation: implemented.
- Browser-side image preview: implemented.
- Phone pairing / QR exchange: not implemented.
- Windows Companion Agent: not implemented.
- Secure relay/API and authenticated device sessions: not implemented.
- Windows file operations, printer control, screen capture, mouse/keyboard control: not implemented.

Controls that depend on missing services are intentionally disabled or marked unavailable. Do not enter secrets or personal files into this prototype.

## Intended architecture
1. This static HTTPS dashboard is the browser UI.
2. A signed Windows 10 Companion runs locally and exposes only an authenticated, allowlisted command interface.
3. The Android Rumin app approves pairing and high-impact actions.
4. A backend provides short-lived pairing, device authentication, and encrypted message relay. Static hosting alone cannot provide this service.

## GitHub Pages
The repository owner/maintainer must enable Pages for this repository and choose GitHub Actions (or an appropriate branch/folder publishing source). After deployment, GitHub displays the actual site URL under **Settings → Pages**. The expected project-site URL format is documented by GitHub, but the live URL is not confirmed until Pages is enabled and deployment succeeds.

Official guide: https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site
