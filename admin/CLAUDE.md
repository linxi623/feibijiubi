# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project scope

This repository is the Vue 3 administrator SPA for the 菲比啾比 (feibijiubi) video platform — a fork of
the teriteri admin UI, now targeting the **feibijiubi Spring Boot backend** (default
`http://localhost:8080`). The implemented workflow is video review (list by status → detail → approve /
reject-with-reason). Content, case, data, and system pages are placeholders.

See `PROJECT_STRUCTURE.md` for the full directory layout, tech stack, and the endpoint mapping table.
`api.md` in the repo root is a synced copy of the backend's API reference — the backend repo
(`D:/feibijiubi/backend/docs/api.md`) is the source of truth; keep both in sync when endpoints change.

## Commands

```bash
npm install       # install dependencies (Node 16.x recommended)
npm run serve     # Vue CLI dev server at http://localhost:8788
npm run build     # production build
npm run lint      # ESLint via Vue CLI
```

No test runner or test suite exists. This is a JavaScript project (no type-check command).

The dev server proxies `/api/*` to `http://localhost:8080` **without stripping the prefix** — the
feibijiubi backend's paths themselves start with `/api`. (The original teriteri setup stripped `/api`
and targeted port 7070; do not reintroduce that.)

## Backend contract (feibijiubi)

- Uniform envelope `{code, message, data}`; business errors usually come back as HTTP 200 with a
  non-200 `code`.
- Auth: `Authorization: Bearer <token>`, token stored in `localStorage.teri_token`.
- HTTP method convention: GET for reads, POST for all writes (no PUT/DELETE).
- Login (`POST /api/auth/login`) returns only `{token}`; user info comes from `GET /api/users/me`,
  after which the frontend enforces `role >= 1` (1 admin, 2 super-admin) before entering the app.
- Review endpoints: `GET /api/admin/videos/page` (flat `AdminVideoListItemVO`, **no total-count
  endpoint** — pagination is prev/next based on page fullness), `GET /api/admin/videos/{vid}` (flat
  `AdminVideoDetailVO`), `POST /api/admin/videos/{vid}/review` with `{result: "APPROVED"|"REJECTED",
  reason}` — reason is mandatory for REJECTED; there is **no permanent-delete action**.
- `GET /api/category` requires login and returns `{mcId, mcName, children[]}`; the store's
  `loadChannels` action adapts it to the legacy `{scList[], descr, rcmTag}` shape components expect.
- Tags are comma-separated; user avatar field is `avatarUrl` (legacy `avatar_url` was renamed in
  templates).

## Architecture notes

- `src/main.js` installs Element Plus (Chinese locale), all icons, router, Vuex, particles; exposes
  `$axios`, `$get`, `$post`.
- `src/network/request.js` wraps Axios (`baseURL: '/api'`, 30s timeout); the response interceptor
  toasts non-200 codes and clears auth + redirects to `/login` on admin-permission errors.
- `src/store/index.js`: `isLoading` / `isLogin` / `user` / `channels`; actions `getPersonalInfo`
  (GET /api/users/me + role check), `loadChannels`, `logout` (POST /api/auth/logout).
- Router guard only checks that `teri_token` exists; real authorization happens via API responses.
- Some legacy code mutates Vuex state directly instead of through mutations — match surrounding
  conventions unless refactoring a whole flow.
- `VideoDetail.vue` renders the description with `v-html` via `linkify` in `src/utils/utils.js`;
  keep XSS in mind when touching either.
