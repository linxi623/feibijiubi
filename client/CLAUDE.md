# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project scope

Vue 3 SPA client for the 菲比啾比 (feibijiubi) video platform — a fork of the teriteri client, now
targeting the **feibijiubi Spring Boot backend** (default `http://localhost:8080`). Connected features:
auth, user profile/avatar/password, category tree, cursor-paginated feed, video detail + interactions
(like/coin/collect/share/play-count/progress), and video upload via **direct-to-Tencent-COS** upload.
Danmaku, comments, IM/messages, search, favorites-folders, and space work-lists have **no backend yet**
and are stubbed or degrade gracefully.

See `PROJECT_STRUCTURE.md` for the directory layout, endpoint mapping, the upload flow, and the list of
stubbed features. `api.md` in the repo root is a synced copy of the backend API reference — the backend
repo (`D:/feibijiubi/backend/docs/api.md`) is the source of truth.

## Commands

Run from the `teriteri-client/` directory.

```bash
npm install       # includes cos-js-sdk-v5 (added); spark-md5 was removed
npm run serve     # dev server on http://localhost:8787
npm run build
npm run lint
```

No test runner or test files exist. Node 16.x recommended. The feibijiubi backend must be running for
most functionality.

The dev server proxies `/api/*` to `http://localhost:8080` **without stripping the prefix** — backend
paths themselves start with `/api`. (The original teriteri setup stripped `/api` and targeted 7070; do
not reintroduce that.) The WS URLs in `.env.development` are legacy and currently unused.

## Backend contract (feibijiubi)

- Envelope `{code, message, data}`; business errors usually HTTP 200 with non-200 `code`.
- Auth: `Authorization: Bearer <token>` from `localStorage.teri_token`. GET for reads, POST for all
  writes (no PUT/DELETE). Interaction endpoints take query params (`?islike=&isSet=`), not bodies.
- Login returns only `{token}`; after storing it, dispatch `getPersonalInfo` (GET /api/users/me) and
  `loadChannels` (GET /api/category — requires login).
- Video upload: POST `/api/videos/upload-url` → STS credentials → `cos-js-sdk-v5` `uploadFile` direct
  to COS (pause/resume/cancel via task API) → POST `/api/videos/cover` → POST `/api/videos` (JSON with
  `tempVideoKey`/`tempCoverKey`). Limits: video ≤ 2GB (mp4/3gp/mpeg), cover ≤ 2MB (jpg/jpeg/png).
- Video detail (GET /api/videos/{vid}) with a token also returns the caller's interaction state
  (`liked/coin/collected/playTime`); interaction writes return no state — update locally.

## Architecture notes

- **`src/utils/adapter.js` is the shape-adaptation boundary** (added during the migration): `adaptUser`
  (UserVO → legacy `uid`/`avatar_url`/count fields), `adaptVideoItem` (flat VO → legacy
  `{video, user, stats}` wrapper), `adaptChannels` (children/rcmTags → scList/descr/rcmTag). Legacy
  templates expect teriteri-era names (`user.uid`, `user.avatar_url`, `video.descr`) — always adapt at
  the fetch boundary instead of editing templates.
- `src/network/request.js`: `$get`/`$post` wrappers, `baseURL: '/api'`, 30s timeout, toast on non-200.
- `src/store/index.js`: auth/user, channels, `attitudeToVideo`, danmu list, unread counts (stubbed to
  0), loading state. Actions `getPersonalInfo` / `loadChannels` / `logout` are wired to the backend;
  `getMsgUnread` is an intentional no-op.
- `PlayerWrapper.vue` reports play count on start and saves progress (POST
  `/api/videos/{vid}/progress`) on pause and beforeUnmount (logged-in users only).
- Stubbed methods are marked with `【菲比啾比后端暂未实现…】` comments — keep the stubs rather than
  deleting call sites, so the features can be re-wired when the backend adds them.
- Router guard checks only `teri_token` presence for `meta.requestAuth` routes.
- Some legacy code mutates Vuex state directly — match surrounding conventions.
