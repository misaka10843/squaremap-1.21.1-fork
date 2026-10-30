import { Options, Rectangle, PolyLine, Polygon, Circle, Ellipse, Icon } from "./Markers.js";
import { P } from '../Squaremap.js';

class World {
    constructor(json) {
        this.name = json.name;
        this.order = json.order;
        this.icon = json.icon;
        this.type = json.type;
        this.display_name = json.display_name;
        this.markerLayers = new Map();
        this.player_tracker = {};
        this.marker_update_interval = 5;
        this.tiles_update_interval = 15;
        this.tileChanges = null;
        this.changePollInFlight = false;
        this.changeLogAvailable = false;
        this.lastChangePoll = 0;
        this.staticMarkersRequested = false;
    }
    tick() {
        if (!P.staticMode) {
            const interval = P.createLayerManager?.sseConnected ? 30000 : 1000;
            if (Date.now() - this.lastChangePoll >= interval) this.pollChanges();
            if (!this.changeLogAvailable && !P.createLayerManager?.sseConnected && P.tick_count % this.tiles_update_interval === 0) {
                P.layerControl.updateTileLayer();
            }
        }
        // load and draw markers
        if ((!P.staticMode && P.tick_count % this.marker_update_interval === 0) || (P.staticMode && !this.staticMarkersRequested)) {
            this.staticMarkersRequested = true;
            P.getJSON(`tiles/${this.name}/markers.json`, (json) => {
                if (this === P.worldList.curWorld) {
                    this.markers(json);
                }
            });
        }
    }
    async pollChanges() {
        if (P.staticMode || this.changePollInFlight) return;
        this.changePollInFlight = true;
        this.lastChangePoll = Date.now();
        try {
            const cursor = this.tileChanges?.seq;
            const changes = await P.getJSON(`tiles/${this.name}/changes.json${Number.isSafeInteger(cursor) ? `?since=${cursor}` : ''}`);
            if (changes != null && P.worldList.curWorld === this) {
                this.tileChanges = changes;
                this.changeLogAvailable = true;
                P.layerControl.applyChanges(changes);
            }
        } finally {
            this.changePollInFlight = false;
        }
    }
    unload() {
        P.sidebar?.markerBrowser?.clear();
        P.playerList.clearPlayerMarkers();
        const keys = Array.from(this.markerLayers.keys());
        for (let i = 0; i < keys.length; i++) {
            const layer = this.markerLayers.get(keys[i]);
            P.layerControl.removeOverlay(layer);
            this.markerLayers.delete(keys[i]);
        }
    }
    load(callback) {
        this.loading = true;
        this.staticMarkersRequested = false;
        P.getJSON(`tiles/${this.name}/settings.json`, async (json) => {
            const changes = P.staticMode ? null : await P.getJSON(`tiles/${this.name}/changes.json`);
            if (P.worldList.curWorld !== this) return;
            this.tileChanges = changes;
            this.changeLogAvailable = changes != null;
            this.lastChangePoll = Date.now();
            this.player_tracker = json.player_tracker;
            this.zoom = json.zoom;
            this.spawn = json.spawn;
            this.marker_update_interval = json.marker_update_interval;
            this.tiles_update_interval = json.tiles_update_interval;
            this.tile_format = json.tile_format ?? 'png';

            // set the scale for our projection calculations
            P.setScale(this.zoom.max);

            // set center and zoom
            P.centerOn(this.spawn.x, this.spawn.z, this.zoom.def)
                .setMinZoom(0) // extra zoom out doesn't work :(
                .setMaxZoom(this.zoom.max + this.zoom.extra);

            // update page title
            document.title = P.title
                .replace(/{world}/g, this.display_name);

            // setup background
            document.getElementById("map").style.background = this.getBackground();

            // setup tile layers
            P.layerControl.setupTileLayers(this);

            // force clear player markers
            P.playerList.clearPlayerMarkers();

            // tick now, reset counter
            this.loading = false;
            P.createLayerManager?.redrawAll();
            P.tick_count = 0;
            P.tick();

            // force clear player markers
            P.playerList.clearPlayerMarkers();

            if (callback != null) {
                callback(this);
            }
        });
    }
    getBackground() {
        switch (this.type) {
            case "nether":
                return "url('images/nether_sky.png')";
            case "the_end":
                return "url('images/end_sky.png')";
            case "normal":
            default:
                return "url('images/overworld_sky.png')";
        }
    }
    markers(json) {
        // check if json is iterable
        if (json == null || !(Symbol.iterator in Object(json))) {
            return;
        }
        const markerIndex = [];
        const seenLayers = new Set();
        // iterate layers
        for (const entry of json) {
            seenLayers.add(entry.id);
            // check if layer exists and needs updating
            let layer = this.markerLayers.get(entry.id);
            if (layer != null) {
                if (layer.timestamp === entry.timestamp) {
                    this.indexMarkers(entry, layer, markerIndex);
                    continue; // skip
                }
                // clear existing layer to rebuild
                P.layerControl.removeOverlay(layer);
                // TODO
                // implement marker tracker instead of clearing
                // to reduce possible client side lag
            }

            // setup the layer
            layer = new L.LayerGroup();
            layer.order = entry.order;
            layer.id = entry.id;
            layer.timestamp = entry.timestamp;
            layer._searchLeaflets = new Map();
            layer.setZIndex(entry.z_index);
            this.markerLayers.set(layer.id, layer);

            // setup the layer control
            if (entry.control === true) {
                P.layerControl.addOverlay(entry.name, layer, entry.hide);
            } else {
                layer.addTo(P.map);
            }

            this.indexMarkers(entry, layer, markerIndex);

            // setup the markers
            for (const shape in entry.markers) {
                let marker;
                const opts = new Options(entry.markers[shape]);
                switch(opts.pop("type")) {
                    case "rectangle": marker = new Rectangle(opts); break;
                    case "polyline": marker = new PolyLine(opts); break;
                    case "polygon": marker = new Polygon(opts); break;
                    case "circle": marker = new Circle(opts); break;
                    case "ellipse": marker = new Ellipse(opts); break;
                    case "icon": marker = new Icon(opts); break;
                }
                if (marker != null) {
                    marker.addTo(layer);
                    this.attachLeaflet(markerIndex, entry.id, shape, marker.marker);
                }
            }
        }
        for (const [id, layer] of this.markerLayers) {
            if (seenLayers.has(id)) continue;
            P.layerControl.removeOverlay(layer);
            this.markerLayers.delete(id);
        }
        P.sidebar?.markerBrowser?.update(markerIndex, 'markers');
    }
    indexMarkers(entry, layer, destination) {
        for (const key in entry.markers ?? {}) {
            const marker = entry.markers[key];
            const point = this.markerPoint(marker);
            if (point == null) continue;
            const name = this.markerName(marker, key);
            const type = marker.type ?? 'marker';
            destination.push({
                key: `${entry.id}:${key}`,
                layerId: entry.id,
                layerName: entry.name ?? entry.id,
                name,
                type,
                x: Math.floor(point.x),
                z: Math.floor(point.z),
                group: layer,
                leaflet: null,
                search: `${name} ${entry.name ?? ''} ${type} ${Math.floor(point.x)} ${Math.floor(point.z)}`.toLocaleLowerCase()
            });
            destination[destination.length - 1].leaflet = layer._searchLeaflets?.get(String(key)) ?? null;
        }
    }
    attachLeaflet(index, layerId, key, leaflet) {
        const record = index.find(item => item.key === `${layerId}:${key}`);
        if (record != null) record.leaflet = leaflet;
        this.markerLayers.get(layerId)?._searchLeaflets?.set(String(key), leaflet);
    }
    markerName(marker, fallback) {
        const html = marker.tooltip ?? marker.popup ?? marker.id ?? fallback;
        const node = document.createElement('div');
        node.innerHTML = String(html);
        return node.textContent?.replace(/\s+/g, ' ').trim() || String(fallback);
    }
    markerPoint(marker) {
        if (marker.point != null) return marker.point;
        if (marker.center != null) return marker.center;
        const points = [];
        const collect = value => {
            if (value == null) return;
            if (Array.isArray(value)) {
                for (const child of value) collect(child);
            } else if (Number.isFinite(Number(value.x)) && Number.isFinite(Number(value.z))) {
                points.push(value);
            }
        };
        collect(marker.points);
        if (points.length === 0) return null;
        const total = points.reduce((sum, point) => ({
            x: sum.x + Number(point.x),
            z: sum.z + Number(point.z)
        }), {x: 0, z: 0});
        return {x: total.x / points.length, z: total.z / points.length};
    }
}

export { World };
