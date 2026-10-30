
/**
 * @typedef {{el: HTMLImageElement, coords: L.Coords, loaded?: number, failures?: number, retry?: ReturnType<typeof setTimeout>, stale?: boolean, reloading?: boolean, reloadPending?: boolean}} Tile
 */

// relies on Leaflet internals: _tiles, _tileCoordsToKey, and _tileReady reading _tiles[key].el
export const SquaremapTileLayer = L.TileLayer.extend({
    /**
     * @param options {SquaremapTileLayerOptions}
     */
    initialize: function (options) {
        L.TileLayer.prototype.initialize.call(this, options.url, options.leafletOptions);
        this._revalidate = options.revalidate;
        /** @type {string | null} */
        this._epoch = options.changes?.epoch ?? null;
        this._cursor = options.changes?.seq ?? 0;
        this._baseline = `b${this._cursor}`;
        this._generation = 0;
        this._versions = new Map();
        this._requests = new Map();
    },

    // use the tile's zoom rather than the map's, tiles from the previous zoom can still be on screen
    getTileUrl: function (coords) {
        const url = L.Util.template(this._url, L.Util.extend({ x: coords.x, y: coords.y, z: coords.z }, this.options));
        if (this._epoch == null) return url;
        const changed = this._versions.get(`${coords.x}:${coords.y}:${coords.z}`);
        const version = changed == null ? this._baseline : `c${changed}`;
        return `${url}?v=${encodeURIComponent(this._epoch + '.' + version)}`;
    },

    createTile: function (coords, done) {
        const tile = document.createElement("img");
        // kept here rather than read from tile.src, which Leaflet swaps for a placeholder when it removes a tile
        /** @type {string | null} */
        let objectUrl = null;
        const release = () => {
            if (objectUrl !== null) {
                URL.revokeObjectURL(objectUrl);
                objectUrl = null;
            }
        };
        L.DomEvent.on(tile, "load", () => {
            release();
            this._tileOnLoad(done, tile);
        });
        // not retried, tile files are written atomically so an image that fails to decode stays broken until rewritten
        L.DomEvent.on(tile, "error", (e) => {
            release();
            this._tileOnError(done, tile, e);
        });
        tile.alt = "";
        tile.setAttribute("role", "presentation");

        void this._fetchTile(coords).then((src) => {
            const current = this._tiles[this._tileCoordsToKey(coords)];
            if (current?.el !== tile) {
                // the tile was removed or a reload replaced this element
                if (src !== null) {
                    URL.revokeObjectURL(src);
                }
                return;
            }
            if (src !== null) {
                objectUrl = src;
                tile.src = src;
                return;
            }
            // stay loading, so Leaflet keeps showing other zoom levels' tiles until a retry
            if (!current.reloading) {
                // a running reload is the retry, and schedules its own if it fails
                this._markFailed(current);
            }
        });
        return tile;
    },

    /**
     * Resolves to an image URL, the error tile for missing tiles, or null if the tile should be retried.
     *
     * @param coords {L.Coords}
     * @param cache {RequestCache}
     * @returns {Promise<string | null>}
     */
    _fetchTile: function (coords, cache = this._revalidate && this._epoch == null ? "no-cache" : "default") {
        // fetch rather than img src, so the browser revalidates instead of reusing an image already loaded in this page
        const primary = this.getTileUrl(coords);
        const webp = new URL(primary, document.baseURI).pathname.endsWith('.webp');
        const request = async (url, fallback) => {
            const controller = new AbortController();
            const key = this._tileCoordsToKey(coords);
            const controllers = this._requests.get(key) ?? new Set();
            controllers.add(controller);
            this._requests.set(key, controllers);
            const timer = setTimeout(() => controller.abort(), 10000);
            try {
                const res = await fetch(url, {cache, signal: controller.signal});
                if (res.status === 404) {
                    return fallback ? request(url.replace(/\.webp(?=\?|$)/, '.png'), false) : this.options.errorTileUrl;
                }
                if (!res.ok) {
                    return null;
                }
                // the embedded web server answers missing tiles with an empty 200
                const blob = await res.blob();
                if (blob.size === 0) return fallback ? request(url.replace(/\.webp(?=\?|$)/, '.png'), false) : this.options.errorTileUrl;
                return URL.createObjectURL(blob);
            } catch {
                return null;
            } finally {
                clearTimeout(timer);
                controllers.delete(controller);
                if (controllers.size === 0 && this._requests.get(key) === controllers) this._requests.delete(key);
            }
        };
        return request(primary, webp);
    },

    /**
     * @param json {TileChanges}
     */
    applyChanges: function (json) {
        if (json == null || typeof json.epoch !== 'string' || !Number.isSafeInteger(json.seq) || !Array.isArray(json.changes)) return;
        if (json.epoch === this._epoch && json.seq < this._cursor) return;
        if (json.epoch !== this._epoch) {
            // a new log on the server (restart, map reset) or none known for these tiles, anything may have changed
            this._epoch = json.epoch;
            this._cursor = json.seq;
            this._reloadAll();
            return;
        }
        if (json.dropped > this._cursor) {
            // we missed changes evicted from the log
            this._cursor = json.seq;
            this._reloadAll();
            return;
        }
        this._reloadChanged(json.changes, this._cursor);
        this._cursor = json.seq;
    },

    /**
     * @param changes {TileChanges["changes"]}
     * @param since {number}
     */
    _reloadChanged: function (changes, since) {
        const changed = new Set();
        for (const [seq, z, x, y] of changes) {
            if (seq > since) {
                // same format as _tileCoordsToKey
                changed.add(`${x}:${y}:${z}`);
                this._versions.set(`${x}:${y}:${z}`, seq);
            }
        }
        if (this._versions.size > 8192) {
            this._baseline = `r${Date.now()}.${++this._generation}`;
            this._versions.clear();
        }
        for (const key in this._tiles) {
            if (changed.has(key)) {
                const tile = this._tiles[key];
                // known to have changed, so not revalidated, an external server may answer a same-second rewrite with 304
                tile.stale = true;
                this._reloadTile(tile);
            }
        }
    },

    _reloadAll: function () {
        this._baseline = `r${Date.now()}.${++this._generation}`;
        this._versions.clear();
        for (const key in this._tiles) {
            this._reloadTile(this._tiles[key]);
        }
    },

    /**
     * @param tile {Tile}
     */
    _reloadTile: function (tile) {
        if (tile.reloading) {
            // one reload at a time, the pending one starts after this finishes so it sees later writes
            tile.reloadPending = true;
            return;
        }
        tile.reloading = true;
        // this request replaces any scheduled retry, a new one is only scheduled if it fails
        clearTimeout(tile.retry);
        // taken now rather than cleared on success, so a change during this fetch stays marked for the pending reload
        const stale = tile.stale;
        tile.stale = false;
        const displayed = () => this._tiles[this._tileCoordsToKey(tile.coords)] === tile;
        const reload = async () => {
            const src = await this._fetchTile(tile.coords, stale ? "reload" : "no-cache");
            if (!displayed()) {
                if (src !== null) {
                    URL.revokeObjectURL(src);
                }
                return;
            }
            if (src === null) {
                tile.stale ||= stale;
                // keep showing the old image until a retry
                this._markFailed(tile);
                return;
            }
            tile.failures = 0;
            const next = new Image();
            next.src = src;
            try {
                await next.decode();
            } catch {
                // a broken image isn't retried until the tile is rewritten, so keep the old image,
                // or finish an unfinished tile with the error tile, as when a first load fails to decode
                if (!tile.loaded && displayed()) {
                    tile.el.src = this.options.errorTileUrl;
                }
                return;
            } finally {
                URL.revokeObjectURL(src);
            }
            if (displayed()) {
                this._replaceTileElement(tile, next);
            }
        };
        void reload().finally(() => {
            tile.reloading = false;
            if (tile.reloadPending && displayed()) {
                tile.reloadPending = false;
                this._reloadTile(tile);
            }
        });
    },

    /**
     * Schedules a retry with backoff, so a struggling server isn't asked for the same tile over and over.
     *
     * @param tile {Tile}
     */
    _markFailed: function (tile) {
        clearTimeout(tile.retry);
        tile.failures = (tile.failures ?? 0) + 1;
        tile.retry = setTimeout(
            () => {
                if (this._tiles[this._tileCoordsToKey(tile.coords)] === tile) {
                    this._reloadTile(tile);
                }
            },
            Math.min(60_000, 1000 * 2 ** tile.failures),
        );
    },

    _removeTile: function (key) {
        for (const controller of this._requests.get(key) ?? []) controller.abort();
        this._requests.delete(key);
        clearTimeout(this._tiles[key]?.retry);
        L.TileLayer.prototype._removeTile.call(this, key);
    },

    /**
     * Swaps in a new element rather than changing src, so Leaflet doesn't fade the tile in again.
     *
     * @param tile {Tile}
     * @param next {HTMLImageElement}
     */
    _replaceTileElement: function (tile, next) {
        const old = tile.el;
        // a replacement is always a decoded image, whatever state the old element was left in
        next.className = "leaflet-tile leaflet-tile-loaded";
        next.style.cssText = old.style.cssText;
        next.alt = "";
        next.setAttribute("role", "presentation");
        next.onselectstart = L.Util.falseFn;
        next.onmousemove = L.Util.falseFn;
        old.replaceWith(next);
        tile.el = next;
        if (!tile.loaded) {
            // the first load hasn't finished, so drop it and mark the tile ready with this element
            L.DomEvent.off(old, "load");
            L.DomEvent.off(old, "error");
            URL.revokeObjectURL(old.src);
            this._tileReady(tile.coords, null, next);
        }
    },
});
