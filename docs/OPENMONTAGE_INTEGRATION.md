# Rumin AI 3.5 — OpenMontage Integration Design

Status: design and source audit only. No OpenMontage source has been copied or executed in this repository.

## Goal

Integrate the real OpenMontage production engine with Rumin AI 3.5 while preserving the existing Android app, HTML/CSS/JavaScript interface, native bridge, live voice, accessibility service, and build workflows.

## Audited upstream project

- Upstream: https://github.com/OpenMontageInc/OpenMontage
- License reported by GitHub: GNU Affero General Public License v3.0 (AGPL-3.0).
- Upstream default branch at audit: main.
- Upstream main commit observed on 2026-11-12: `fecb9a1e3cc23ef84de30b272059457a427f16f8` (Pika H3 Flex hosted-provider change). This is an audit reference only, not yet an approved production pin.
- Main stack observed: Python tool registry and pipeline definitions, FastAPI/uvicorn Backlot components, Remotion/React composer, FFmpeg-based media processing, provider integrations.
- Upstream prerequisite declarations observed: Python 3.10+, FFmpeg, and Node.js 22.23.4+, 24.22.1+, or 26.12.1+ for the Remotion composer.
- Relevant pipeline definitions observed: clip-factory, documentary-montage, animated-explainer, localization-dub, podcast-repurpose, screen-demo, and talking-head.
- Relevant tool families observed: video generation/search/reframing, transcription/analysis, subtitles, TTS/audio, graphics, and publishing/export.

The upstream repository is large and changes frequently. Before vendoring or deploying it, pin a reviewed upstream commit or release and re-run license, dependency, security, and compatibility checks.

## Architecture decision

Do not bundle the OpenMontage runtime inside the Android APK. Android remains the client and control surface; a separately deployed managed backend runs the production engine.

Proposed request path:

1. Rumin Android/Web client authenticates to the Rumin backend.
2. Backend validates the user, request size, media ownership/rights declaration, allowed pipeline, and budget.
3. Backend creates a durable production job and returns a job ID.
4. Worker runs the selected OpenMontage pipeline in an isolated workspace/container.
5. Human approval gates are returned to the client when required.
6. Worker validates outputs (media probe, duration, dimensions, audio/subtitle checks) and stores them in private object storage.
7. Client polls or receives job status and downloads an authorized, expiring output URL.

## Proposed backend API contract

- `POST /api/v1/video/jobs` — create a job using authenticated `multipart/form-data`: `pipeline`, `prompt`, `output_format`, `subtitle_language`, and one `file` upload (Android client currently caps uploads at 500 MB). Return JSON with a non-empty `job_id` (or `id`) and initial status.
- `GET /api/v1/video/jobs/{job_id}` — read status and progress; return JSON with status/progress and, when ready, authorized artifact metadata/URLs.
- `GET /api/v1/video/jobs/{job_id}` — read status and progress.
- `POST /api/v1/video/jobs/{job_id}/approval` — approve/reject a human checkpoint.
- `POST /api/v1/video/jobs/{job_id}/cancel` — request cancellation.
- `GET /api/v1/video/jobs/{job_id}/artifacts` — list authorized output artifacts.
- `GET /api/v1/health` — non-sensitive health check.

The API must not expose arbitrary shell commands, filesystem paths, provider credentials, or unrestricted URLs. Uploads must use size/type limits and private storage; output URLs should expire.

## First integration slices

1. Clip factory: upload a long video, transcribe/analyze, propose short clips, approve, render vertical MP4 with captions.
2. Bangla localization: transcription, Bengali translation/review, Bangla TTS provider selection, subtitle timing, dubbed output.
3. Animated explainer: script, storyboard, approved assets, Remotion composition and MP4 render.
4. Documentary montage and other pipelines after the first slices are stable.

## Android client adapter status

The feature branch now contains an Android Video Studio screen and native upload/status client. It supports the pipeline identifiers listed above, requires an HTTPS backend URL and bearer token entered by the user, and sends video uploads to `POST /api/v1/video/jobs`. The client does not store the token in browser local storage and does not bundle provider credentials.

This is a client-side integration slice only. No Rumin backend, durable queue, object storage, or OpenMontage worker has been deployed or connected yet. The screen cannot render a video until a compatible authenticated backend implements this contract. The backend must validate file type and actual size independently; the Android-side 500 MB cap is not a server-side security boundary.

## Security and reliability requirements

- Keep every provider key and backend secret in server-side secret storage; never commit secrets or embed them in the APK or browser JavaScript.
- Authenticate every job and artifact request; enforce per-user ownership.
- Isolate each render job and enforce CPU, memory, disk, wall-time, and output-size limits.
- Treat prompts, uploaded files, transcripts, generated assets, and external provider responses as untrusted data.
- Restrict network egress where practical; do not let user prompts select arbitrary tools or invoke shell commands.
- Add rate limits, request IDs, audit logs, cancellation, retries, and per-job cost ceilings.
- Obtain user confirmation before paid generation, voice cloning, or publishing.
- Verify rights/consent for uploaded media, cloned voices, stock footage, music, and generated assets.
- Generate an SBOM and scan pinned dependencies and container images before public deployment.

## AGPL-3.0 and Play Store release gate

GitHub currently identifies OpenMontage as AGPL-3.0. A network-accessible modified OpenMontage service can trigger AGPL section 13 source-offer obligations for users interacting with it. Distribution of modified covered code also carries source and notice obligations. The exact compliance model must be reviewed before public launch.

Before Play Store publication:
- Pin upstream source to a reviewed commit and preserve copyright/license notices.
- Track every upstream file copied or modified and every dependency's separate license.
- Publish the corresponding source and required notices for covered modifications.
- Include third-party notices in the website and Android app where appropriate.
- Review Google Play policies, user-data disclosures, account deletion, media permissions, content rights, and billing for paid generation.
- Do not imply that the Rumin Android app itself contains the full rendering engine.

## Current blockers

- No managed backend or worker deployment is present in the existing Android repository.
- No private object storage, durable queue, authentication contract, or provider secret store is configured here.
- OpenMontage has not yet been pinned, installed, executed, or tested against a Rumin adapter.
- No backend URL, credentials, or deployment environment has been supplied.
- This document is a design artifact, not a working integration.

## Acceptance criteria for a first usable release

- Existing Rumin app features pass regression checks.
- Authenticated user can upload a permitted source video and create a clip-factory job.
- Job progress and approval gates are visible in Rumin.
- Worker produces a playable MP4 and subtitle artifact.
- Output ownership, access control, cancellation, and cost ceiling are tested.
- License notices and corresponding source obligations are satisfied.
