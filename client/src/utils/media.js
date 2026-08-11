export const DEFAULT_AVATAR = require('@/assets/img/logo.png');

/**
 * Convert media paths returned by the backend into browser-loadable URLs.
 * In development, non-API relative paths need the backend origin because the
 * Vue dev server only proxies /api.
 */
export function resolveMediaUrl(value) {
    if (!value || typeof value !== 'string') return '';

    const url = value.trim();
    if (!url) return '';
    if (/^(?:https?:|data:|blob:)/i.test(url)) return url;
    if (url.startsWith('//')) return `${window.location.protocol}${url}`;
    if (url.startsWith('/api/')) return url;

    const configuredBase = process.env.VUE_APP_MEDIA_BASE_URL;
    const base = configuredBase
        || (process.env.NODE_ENV === 'development' ? 'http://localhost:8080' : window.location.origin);

    return `${base.replace(/\/$/, '')}/${url.replace(/^\//, '')}`;
}
