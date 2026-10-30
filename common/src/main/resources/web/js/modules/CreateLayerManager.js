import { P } from './Squaremap.js';
import { DeltaCollection } from './util/DeltaCollection.js';
import { compressionQuery, listen } from './util/SseMessages.js';

class CreateLayerManager {
    constructor() {
        this.available = false;
        this.initialized = false;
        this.tickCount = 0;
        this.lastWorld = null;
        this.networkVersion = null;
        this.networkRevision = null;
        this.status = null;
        this.lastSignalUpdate = null;
        this.lastBlockUpdate = null;
        this.lastTrainUpdate = null;
        this.network = null;
        this.signalData = [];
        this.blockData = [];
        this.trainData = [];
        this.signalMarkers = new Map();
        this.stationMarkers = new Map();
        this.trainMarkers = new Map();
        this.blockGeometry = new Map();
        this.dynamicRefreshInFlight = false;
        this.refreshRequestInFlight = false;
        this.dynamicTimer = null;
        this.dynamicInterval = 1000;
        this.blockSignature = null;
        this.eventSource = null;
        this.dynamicEventSource = null;
        this.sseConnected = false;
        this.dynamicSseConnected = false;
        this.collections = new Map();
        this.serverClockOffset = 0;
        document.addEventListener('visibilitychange', () => {
            if (document.visibilityState === 'visible' && !P.staticMode) {
                this.connectEvents();
                this.syncDynamicEvents();
                this.checkStatus();
                P.worldList?.curWorld?.pollChanges();
            } else if (document.visibilityState !== 'visible') {
                this.closeEvents();
            }
        });
        this.checkStatus();
        this.connectEvents();
    }

    connectEvents() {
        if (P.staticMode || document.visibilityState !== 'visible' || !window.EventSource) return;
        const world = P.worldList?.curWorld?.name ?? '';
        if (this.eventSource != null && this.subscriptionWorld === world) return;
        this.eventSource?.close();
        this.sseConnected = false;
        P.playerList?.resetStream();
        this.subscriptionWorld = world;
        const source = new EventSource(`api/events?protocol=2&topics=base,players-roster,players-positions&world=${encodeURIComponent(world)}${compressionQuery()}`);
        this.eventSource = source;
        const current = () => this.eventSource === source;
        const failed = () => {
            if (!current()) return;
            source.close(); this.eventSource = null; this.sseConnected = false; P.playerList.streamConnected = false;
            window.setTimeout(() => this.connectEvents(), 1000);
        };
        source.addEventListener('ready', event => {
            if (this.eventSource !== source) return;
            this.sseConnected = true;
            P.playerList.streamConnected = this.parseEvent(event)?.protocol === 2;
            P.worldList?.curWorld?.pollChanges();
            this.checkStatus();
            this.syncDynamicEvents();
        });
        listen(source, 'create-status', data => this.applyStatus(data), current, failed);
        listen(source, 'create-network', () => this.refreshNetwork(), current, failed);
        for (const topic of ['players-roster', 'players-positions']) {
            listen(source, topic, data => { if (!P.playerList.applyStream(topic, data)) failed(); }, current, failed);
        }
        listen(source, 'tiles-invalidated', data => {
            if (data?.world === P.worldList?.curWorld?.name && document.visibilityState === 'visible') {
                this.invalidateVisibleTiles(data.tiles ?? []);
            }
        }, current, failed);
        source.onerror = () => {
            if (this.eventSource !== source) return;
            this.sseConnected = false;
            P.playerList.streamConnected = false;
            // EventSource reconnects automatically. Existing polling is the fallback
            // for external static web servers where /api/events does not exist.
        };
    }

    closeEvents() {
        this.eventSource?.close();
        this.dynamicEventSource?.close();
        this.eventSource = this.dynamicEventSource = null;
        this.sseConnected = this.dynamicSseConnected = false;
        P.playerList.streamConnected = false;
    }

    worldChanged() {
        this.connectEvents();
        this.syncDynamicEvents();
        if (this.initialized) { this.redrawAll(); if (this.available) this.refreshNetwork(); }
    }

    syncDynamicEvents() {
        const topics = !P.staticMode && this.available && this.initialized && document.visibilityState === 'visible'
            ? ['signals', 'blocks', 'trains'].filter(kind => P.map.hasLayer(this.layers[kind])).map(kind => `create-${kind}`) : [];
        const wanted = topics.length > 0;
        const world = P.worldList?.curWorld?.name ?? '';
        const subscription = `${world}:${topics.join(',')}`;
        if (!wanted) {
            if (this.dynamicEventSource != null) this.dynamicEventSource.close();
            this.dynamicEventSource = null;
            this.dynamicSseConnected = false;
            return;
        }
        if (!window.EventSource || (this.dynamicEventSource != null && this.dynamicSubscription === subscription)) return;
        this.dynamicEventSource?.close();
        this.dynamicSseConnected = false;
        this.dynamicSubscription = subscription;
        this.collections.clear();
        this.signalData = []; this.blockData = []; this.trainData = [];
        const source = new EventSource(`api/events?protocol=2&topics=${topics.join(',')}&world=${encodeURIComponent(world)}${compressionQuery()}`);
        this.dynamicEventSource = source;
        const current = () => this.dynamicEventSource === source;
        const failed = () => {
            if (!current()) return;
            source.close(); this.dynamicEventSource = null; this.dynamicSseConnected = false;
            window.setTimeout(() => this.syncDynamicEvents(), 1000);
        };
        source.addEventListener('ready', async event => {
            if (this.dynamicEventSource !== source) return;
            // Older servers retain the full JSON snapshot path.
            if (this.parseEvent(event)?.protocol !== 2) { await this.refreshDynamic(); return; }
            this.dynamicSseConnected = true;
        });
        for (const kind of ['signals', 'blocks', 'trains']) listen(source, `create-${kind}`, data => {
            if (data == null) return;
            let rows;
            if (Number.isSafeInteger(data.seq)) {
                const collection = this.collections.get(kind) ?? new DeltaCollection();
                this.collections.set(kind, collection);
                if (!collection.apply(data)) {
                    failed(); return;
                }
                rows = [...collection.items.values()];
            } else rows = data[kind] ?? [];
            if (kind === 'signals') { this.signalData = rows; this.renderSignals(); }
            if (kind === 'blocks') { this.blockData = rows; this.renderBlocks(); }
            if (kind === 'trains') { this.trainData = rows; this.renderTrains(); }
            this.updateStatusPanel();
        }, current, failed);
        source.onerror = () => {
            if (this.dynamicEventSource !== source) return;
            this.dynamicSseConnected = false;
        };
    }

    parseEvent(event) {
        try { return JSON.parse(event.data); } catch (_) { return null; }
    }

    invalidateVisibleTiles(changed) {
        P.layerControl.invalidateTiles(changed);
    }

    tick() {
        this.tickCount++;
        if (document.visibilityState !== 'visible') return;
        this.updateStatusPanel();
        if (!P.staticMode && !this.sseConnected && (this.tickCount % 5 === 0 || !this.initialized)) {
            this.checkStatus();
        }
        if (!this.available || !P.worldList?.curWorld) {
            return;
        }

        const world = P.worldList.curWorld.name;
        if (world !== this.lastWorld) {
            this.lastWorld = world;
            this.redrawAll();
        }
    }

    async refreshNetwork() {
        const world = P.worldList?.curWorld?.name;
        if (!world) return;
        if (this.networkRequest != null && this.networkRequestWorld === world) return;
        this.networkRequestWorld = world;
        const request = this.fetchJson(`tiles/create/network.json?world=${encodeURIComponent(world)}`);
        this.networkRequest = request;
        const json = await request;
        if (this.networkRequest === request) this.networkRequest = null;
        if (P.worldList?.curWorld?.name !== world) return;
        if (json == null) return;
        const revision = `${json.version}:${json.updated_at ?? 0}:${world}`;
        if (this.networkRevision !== revision || this.network == null) {
            this.networkVersion = json.version;
            this.networkRevision = revision;
            this.network = json;
            this.blockSignature = null;
            this.renderNetwork();
            this.renderSignals();
            this.renderBlocks();
            this.updateStatusPanel();
        }
    }

    async refreshDynamic() {
        if (this.dynamicSseConnected) return;
        if (!this.available || !this.initialized || !P.worldList?.curWorld
            || !this.dynamicLayersVisible() || this.dynamicRefreshInFlight) {
            return;
        }
        this.dynamicRefreshInFlight = true;
        try {
            const requests = [];
            if (P.map.hasLayer(this.layers.signals)) requests.push(this.refreshSignalData());
            if (P.map.hasLayer(this.layers.blocks)) requests.push(this.refreshBlockData());
            if (P.map.hasLayer(this.layers.trains)) requests.push(this.refreshTrainData());
            await Promise.all(requests);
            this.updateStatusPanel();
        } finally {
            this.dynamicRefreshInFlight = false;
        }
    }

    async refreshSignalData() {
        const data = await this.fetchJson('tiles/create/signals.json');
        if (this.dynamicSseConnected) return;
        if (data == null || data.updated_at === this.lastSignalUpdate) return;
        this.lastSignalUpdate = data.updated_at;
        this.signalData = data.signals ?? [];
        this.renderSignals();
    }

    async refreshBlockData() {
        const data = await this.fetchJson('tiles/create/blocks.json');
        if (this.dynamicSseConnected) return;
        if (data == null || data.updated_at === this.lastBlockUpdate) return;
        this.lastBlockUpdate = data.updated_at;
        this.blockData = data.blocks ?? [];
        this.renderBlocks();
    }

    async refreshTrainData() {
        const data = await this.fetchJson('tiles/create/trains.json');
        if (this.dynamicSseConnected) return;
        if (data == null || data.updated_at === this.lastTrainUpdate) return;
        this.lastTrainUpdate = data.updated_at;
        this.trainData = data.trains ?? [];
        this.renderTrains();
    }

    async checkStatus() {
        if (this.statusRequestInFlight) return;
        this.statusRequestInFlight = true;
        const status = await this.fetchJson('tiles/create/status.json');
        this.statusRequestInFlight = false;
        if (status == null) return;
        this.applyStatus(status);
    }

    applyStatus(status) {
        if (status == null) return;
        const wasAvailable = this.available;
        this.status = status;
        if (Number.isFinite(status.server_time)) this.serverClockOffset = status.server_time - Date.now();
        const present = status.available === true;
        this.available = present && status.enabled !== false;

        if (present && !this.initialized && P.layerControl != null) {
            this.initializeLayers();
        }
        if (wasAvailable && !this.available && this.initialized) {
            this.clearRenderedData();
        }
        if (this.available) {
            const interval = Math.max(750, Math.min(5000, Number(status.update_interval_ticks ?? 20) * 50));
            if (!this.dynamicSseConnected) this.scheduleDynamicRefresh(interval);
            else if (this.dynamicTimer != null) {
                window.clearInterval(this.dynamicTimer);
                this.dynamicTimer = null;
            }
            const revision = `${status.network_version}:${status.network_revision ?? 0}:${P.worldList?.curWorld?.name}`;
            if (this.network == null || this.networkRevision !== revision) {
                this.refreshNetwork();
            }
        } else if (this.dynamicTimer != null) {
            window.clearInterval(this.dynamicTimer);
            this.dynamicTimer = null;
        }

        if (status.error) console.error('Create map integration:', status.error);
        this.syncDynamicEvents();
        this.updateStatusPanel();
    }

    scheduleDynamicRefresh(interval) {
        if (P.staticMode) return;
        if (this.dynamicTimer != null && this.dynamicInterval === interval) return;
        if (this.dynamicTimer != null) window.clearInterval(this.dynamicTimer);
        this.dynamicInterval = interval;
        this.dynamicTimer = window.setInterval(() => {
            if (document.visibilityState === 'visible' && this.dynamicLayersVisible()) this.refreshDynamic();
        }, interval);
    }

    dynamicLayersVisible() {
        if (!this.initialized || this.layers == null) return false;
        return [this.layers.blocks, this.layers.signals, this.layers.trains]
            .some(layer => P.map.hasLayer(layer));
    }

    initializeLayers() {
        this.ensurePanes();
        this.layers = {
            tracks: this.layer('create_tracks', 20, 'Create · 轨道', false),
            blocks: this.layer('create_blocks', 21, 'Create · 闭塞区间', true),
            signals: this.layer('create_signals', 22, 'Create · 信号机', true),
            portals: this.layer('create_portals', 23, 'Create · 维度入口', false),
            stations: this.layer('create_stations', 24, 'Create · 车站', false),
            trains: this.layer('create_trains', 25, 'Create · 列车', false)
        };

        this.blockLines = {
            free: L.polyline([], {
                pane: 'create-blocks',
                className: 'create-block free',
                color: '#78b899',
                weight: 4,
                opacity: 0.42,
                lineCap: 'round',
                lineJoin: 'round',
                interactive: false,
                smoothFactor: 1.5
            }).addTo(this.layers.blocks),
            reserved: L.polyline([], {
                pane: 'create-blocks',
                className: 'create-block reserved',
                color: '#ffb454',
                weight: 6,
                opacity: 0.82,
                lineCap: 'round',
                interactive: false,
                smoothFactor: 1.5
            }).addTo(this.layers.blocks),
            occupied: L.polyline([], {
                pane: 'create-blocks',
                className: 'create-block occupied',
                color: '#ff5d6c',
                weight: 6,
                opacity: 0.84,
                lineCap: 'round',
                interactive: false,
                smoothFactor: 1.5
            }).addTo(this.layers.blocks)
        };

        this.statusControl = L.control({position: 'topright'});
        this.statusControl.onAdd = () => {
            const element = L.DomUtil.create('div', 'create-map-status leaflet-control');
            element.innerHTML = `
                <div class="create-status-header">
                    <span class="create-refresh-ring" aria-hidden="true">
                        <i></i>
                        <span class="create-status-countdown"></span>
                    </span>
                    <strong class="create-status-title">Create Railway</strong>
                    <button class="create-refresh-button" type="button" title="立即刷新静态铁路">
                        <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M12 4V1L8 5l4 4V6c3.31 0 6 2.69 6 6 0 1.01-.25 1.97-.7 2.8l1.46 1.46C19.54 15.03 20 13.57 20 12c0-4.42-3.58-8-8-8zm0 14c-3.31 0-6-2.69-6-6 0-1.01.25-1.97.7-2.8L5.24 7.74C4.46 8.97 4 10.43 4 12c0 4.42 3.58 8 8 8v3l4-4-4-4v3z"/></svg>
                    </button>
                    <button class="create-toggle-button" type="button" title="折叠面板" aria-label="折叠面板" aria-expanded="true">
                        <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg>
                    </button>
                </div>
                <div class="create-status-content">
                    <div class="create-status-summary">正在连接…</div>
                    <span class="create-status-meta">等待状态…</span>
                </div>`;
            L.DomEvent.disableClickPropagation(element);
            L.DomEvent.disableScrollPropagation(element);
            this.statusElement = element;
            this.statusSummaryElement = element.querySelector('.create-status-summary');
            this.statusMetaElement = element.querySelector('.create-status-meta');
            this.statusRingElement = element.querySelector('.create-refresh-ring');
            this.statusCountdownElement = element.querySelector('.create-status-countdown');
            this.refreshButton = element.querySelector('.create-refresh-button');
            this.toggleButton = element.querySelector('.create-toggle-button');
            const setCollapsed = collapsed => {
                element.classList.toggle('collapsed', collapsed);
                const label = collapsed ? '展开面板' : '折叠面板';
                this.toggleButton.title = label;
                this.toggleButton.setAttribute('aria-label', label);
                this.toggleButton.setAttribute('aria-expanded', String(!collapsed));
            };
            
            this.refreshButton.addEventListener('click', () => this.requestStaticRefresh());
            this.toggleButton.addEventListener('click', (e) => {
                e.stopPropagation();
                setCollapsed(!element.classList.contains('collapsed'));
            });
            element.addEventListener('click', () => {
                if (element.classList.contains('collapsed')) {
                    setCollapsed(false);
                }
            });
            return element;
        };
        this.statusControl.addTo(P.map);
        P.map.on('overlayadd', event => {
            if ([this.layers.blocks, this.layers.signals, this.layers.trains].includes(event.layer)) {
                this.refreshDynamic();
                this.syncDynamicEvents();
            }
        });
        P.map.on('overlayremove', event => {
            if ([this.layers.blocks, this.layers.signals, this.layers.trains].includes(event.layer)) {
                this.syncDynamicEvents();
            }
        });
        this.initialized = true;
        this.syncDynamicEvents();
        if (this.available) {
            this.refreshNetwork();
            this.refreshDynamic();
        }
    }

    clearRenderedData() {
        if (!this.initialized) return;
        for (const layer of Object.values(this.layers ?? {})) layer.clearLayers();
        this.signalMarkers.clear();
        this.stationMarkers.clear();
        this.trainMarkers.clear();
        this.blockGeometry.clear();
        this.signalData = [];
        this.blockData = [];
        this.trainData = [];
        this.lastSignalUpdate = null;
        this.lastBlockUpdate = null;
        this.lastTrainUpdate = null;
        this.blockLines?.free?.setLatLngs([]).addTo(this.layers.blocks);
        this.blockLines?.occupied?.setLatLngs([]).addTo(this.layers.blocks);
        this.blockLines?.reserved?.setLatLngs([]).addTo(this.layers.blocks);
        this.network = null;
        this.networkRevision = null;
        this.blockSignature = null;
        P.sidebar?.markerBrowser?.clear('create');
    }

    layer(id, order, label, hidden) {
        const layer = L.layerGroup();
        layer.id = id;
        layer.order = order;
        layer.setZIndex(order);
        P.layerControl.addOverlay(label, layer, hidden);
        return layer;
    }

    ensurePanes() {
        const panes = {
            'create-tracks': 430,
            'create-blocks': 435,
            'create-signals': 445,
            'create-portals': 446,
            'create-stations': 447,
            'create-trains': 455
        };
        for (const [name, zIndex] of Object.entries(panes)) {
            if (P.map.getPane(name) == null) {
                P.map.createPane(name).style.zIndex = zIndex;
            }
        }
    }

    redrawAll() {
        if (!this.initialized) return;
        this.blockSignature = null;
        this.renderNetwork();
        this.renderSignals();
        this.renderBlocks();
        this.renderTrains();
        this.updateStatusPanel();
    }

    renderNetwork() {
        if (!this.initialized || this.network == null || !P.worldList?.curWorld) return;
        const world = P.worldList.curWorld.name;
        this.layers.tracks.clearLayers();
        this.layers.portals.clearLayers();
        this.layers.stations.clearLayers();
        this.stationMarkers.clear();
        this.blockGeometry.clear();

        // One multi-polyline is significantly cheaper than one Leaflet layer
        // for every Create edge on large railway networks.
        const trackPaths = (this.network.tracks ?? [])
            .filter(track => track.world === world)
            .map(track => this.latLngs(track.points));
        if (trackPaths.length > 0) {
            L.polyline(trackPaths, {
                pane: 'create-tracks',
                className: 'create-track',
                color: '#dce6ef',
                weight: 3,
                opacity: 0.92,
                lineCap: 'round',
                lineJoin: 'round',
                interactive: false,
                smoothFactor: 1.35
            }).addTo(this.layers.tracks);
        }

        const freeBlockPaths = [];
        for (const block of this.network.signal_blocks ?? []) {
            const segments = block.segments ?? [];
            this.blockGeometry.set(block.id, segments);
            for (const segment of segments) {
                if (segment.world === world) freeBlockPaths.push(this.latLngs(segment.points));
            }
        }
        this.blockLines?.free?.setLatLngs(freeBlockPaths);

        const seenStations = new Set();
        for (const station of this.network.stations ?? []) {
            if (station.world !== world || seenStations.has(station.id)) continue;
            seenStations.add(station.id);
            const marker = L.marker(P.toLatLng(station.x, station.z), {
                pane: 'create-stations',
                icon: this.stationIcon(station.assembling),
                rotationAngle: station.angle ?? 0
            });
            marker.bindTooltip(this.escape(station.name), {
                className: 'create-tooltip',
                direction: 'top',
                offset: [0, -11]
            });
            marker.addTo(this.layers.stations);
            this.stationMarkers.set(`${station.id}:${world}`, marker);
        }

        for (const portal of this.network.portals ?? []) {
            const sides = [[portal.from, portal.to], [portal.to, portal.from]];
            for (const [from, to] of sides) {
                if (from.world !== world) continue;
                const marker = L.marker(P.toLatLng(from.x, from.z), {
                    pane: 'create-portals',
                    icon: this.portalIcon()
                });
                marker.bindTooltip(`前往 ${this.escape(to.dimension)}`, {className: 'create-tooltip'});
                marker.on('click', () => {
                    if (P.worldList.worlds.has(to.world)) {
                        P.worldList.showWorld(to.world, () => P.centerOn(to.x, to.z, P.map.getZoom()));
                    }
                });
                marker.addTo(this.layers.portals);
            }
        }
        this.updateCreateSearch();
    }

    renderSignals() {
        if (!this.initialized || this.network == null || !P.worldList?.curWorld) return;
        const world = P.worldList.curWorld.name;
        const states = new Map(this.signalData.map(signal => [signal.id, signal]));
        const active = new Set();

        for (const geometry of this.network.signals ?? []) {
            if (geometry.world !== world) continue;
            const state = states.get(geometry.id);
            this.updateSignalSide(geometry, geometry.forward, state?.forward, 'forward', active);
            this.updateSignalSide(geometry, geometry.reverse, state?.reverse, 'reverse', active);
        }

        for (const [key, marker] of this.signalMarkers) {
            if (!active.has(key)) {
                this.layers.signals.removeLayer(marker);
                this.signalMarkers.delete(key);
            }
        }
    }

    updateSignalSide(signal, geometry, state, sideName, active) {
        if (geometry == null) return;
        const key = `${signal.id}:${sideName}`;
        active.add(key);
        const stateName = String(state?.state ?? 'INVALID').toLowerCase();
        const chain = (state?.type ?? geometry.type) === 'CROSS_SIGNAL';
        const signature = `${stateName}:${chain}:${geometry.angle ?? 0}`;
        let marker = this.signalMarkers.get(key);

        if (marker == null) {
            marker = L.marker(P.toLatLng(signal.x, signal.z), {
                pane: 'create-signals',
                interactive: false,
                icon: this.signalIcon(stateName, chain),
                rotationAngle: Number(geometry.angle) || 0
            }).addTo(this.layers.signals);
            marker._createSignature = signature;
            this.signalMarkers.set(key, marker);
        } else if (marker._createSignature !== signature) {
            marker.setIcon(this.signalIcon(stateName, chain));
            marker.setRotationAngle(Number(geometry.angle) || 0);
            marker._createSignature = signature;
            if (!this.layers.signals.hasLayer(marker)) marker.addTo(this.layers.signals);
        }
    }

    signalIcon(state, chain, leftSide = false) {
        const path = chain
            ? `<path class="frame" d="m25.168 3.074.117.21.108.21L38.99 31.368a6 6 0 0 1-5.168 8.627l-.225.004H22v20h18v4H0v-4h18V40H6.402a6 6 0 0 1-5.49-8.422l.097-.209L14.607 3.493a6 6 0 0 1 2.508-2.63L17.37.73l.21-.097a6 6 0 0 1 7.589 2.44Zm-3.37 2.173a2 2 0 0 0-3.596 0L4.604 33.123A2 2 0 0 0 6.402 36h27.196a2 2 0 0 0 1.798-2.877Z"/><path class="light" d="m21.798 5.247 13.598 27.876A2 2 0 0 1 33.598 36H6.402a2 2 0 0 1-1.798-2.877L18.202 5.247a2 2 0 0 1 3.596 0Z"/>`
            : `<path class="frame" d="M20 0c11.046 0 20 8.954 20 20 0 10.37-7.893 18.897-17.999 19.901L22 60h18v4H0v-4h18V39.901C7.893 38.898 0 30.371 0 20 0 8.954 8.954 0 20 0Zm0 4C11.163 4 4 11.163 4 20s7.163 16 16 16 16-7.163 16-16S28.837 4 20 4Z"/><path class="light" d="M20 4C11.163 4 4 11.163 4 20s7.163 16 16 16 16-7.163 16-16S28.837 4 20 4Z"/>`;
        return L.divIcon({
            className: `create-signal-marker signal-icon ${state} ${chain ? 'chain' : 'entry'}`,
            html: `<svg xmlns="http://www.w3.org/2000/svg" width="10" height="16" viewBox="0 0 40 64">${path}</svg>`,
            iconSize: [10, 16],
            iconAnchor: [leftSide ? 14 : -4, 16]
        });
    }

    renderBlocks() {
        if (!this.initialized || !P.worldList?.curWorld || this.blockLines == null) return;
        const world = P.worldList.curWorld.name;
        const activeBlocks = this.blockData;
        const signature = `${world}|` + activeBlocks
            .map(block => `${block.id}:${block.occupied ? 2 : block.reserved ? 1 : 0}`)
            .sort()
            .join(',');
        if (signature === this.blockSignature) return;
        this.blockSignature = signature;

        const occupiedPaths = [];
        const reservedPaths = [];
        for (const block of activeBlocks) {
            const target = block.occupied ? occupiedPaths : reservedPaths;
            for (const segment of this.blockGeometry.get(block.id) ?? []) {
                if (segment.world === world) target.push(this.latLngs(segment.points));
            }
        }
        this.blockLines.occupied.setLatLngs(occupiedPaths);
        this.blockLines.reserved.setLatLngs(reservedPaths);
    }

    renderTrains() {
        if (!this.initialized || !P.worldList?.curWorld) return;
        const world = P.worldList.curWorld.name;
        const active = new Set();

        for (const train of this.trainData) {
            const positions = (train.positions ?? []).filter(entry => entry.world === world);
            const position = positions.find(entry => entry.front === true) ?? positions[0];
            if (position == null) continue;

            const key = `${train.id}:${world}`;
            active.add(key);
            let marker = this.trainMarkers.get(key);
            const tooltip = this.trainTooltip(train);

            if (marker == null) {
                marker = L.marker(P.toLatLng(position.x, position.z), {
                    pane: 'create-trains',
                    icon: this.trainIcon(train, position.angle ?? 0, position.front !== false)
                }).bindTooltip(tooltip, {
                    className: 'create-tooltip create-train-tooltip',
                    direction: 'right',
                    offset: [18, 0]
                }).addTo(this.layers.trains);
                marker._createTooltip = tooltip;
                marker._createIconSignature = this.trainIconSignature(train, position.angle ?? 0, position.front !== false);
                this.trainMarkers.set(key, marker);
            } else {
                marker.setLatLng(P.toLatLng(position.x, position.z));
                if (marker._createTooltip !== tooltip) {
                    marker.setTooltipContent(tooltip);
                    marker._createTooltip = tooltip;
                }
                if (!this.layers.trains.hasLayer(marker)) marker.addTo(this.layers.trains);
            }
            this.updateTrainElement(marker, train, position.angle ?? 0, position.front !== false);
        }

        for (const [key, marker] of this.trainMarkers) {
            if (!active.has(key)) {
                this.layers.trains.removeLayer(marker);
                this.trainMarkers.delete(key);
            }
        }
        this.updateCreateSearch();
    }

    updateCreateSearch() {
        if (!this.initialized || !P.worldList?.curWorld) return;
        const world = P.worldList.curWorld.name;
        const records = [];
        const seenStations = new Set();

        for (const station of this.network?.stations ?? []) {
            if (station.world !== world || seenStations.has(station.id)) continue;
            seenStations.add(station.id);
            const name = String(station.name || '未命名车站');
            const x = Math.floor(Number(station.x));
            const z = Math.floor(Number(station.z));
            records.push({
                key: `create-station:${station.id}`,
                layerId: 'create_stations',
                layerName: 'Create · 车站',
                name,
                type: 'station',
                x,
                z,
                group: this.layers.stations,
                leaflet: this.stationMarkers.get(`${station.id}:${world}`) ?? null,
                search: `${name} Create 车站 station ${x} ${z}`.toLocaleLowerCase()
            });
        }

        for (const train of this.trainData) {
            const positions = (train.positions ?? []).filter(entry => entry.world === world);
            const position = positions.find(entry => entry.front === true) ?? positions[0];
            if (position == null) continue;
            const name = String(train.name || '未命名列车');
            const x = Math.floor(Number(position.x));
            const z = Math.floor(Number(position.z));
            records.push({
                key: `create-train:${train.id}`,
                layerId: 'create_trains',
                layerName: 'Create · 列车',
                name,
                type: 'train',
                x,
                z,
                group: this.layers.trains,
                leaflet: this.trainMarkers.get(`${train.id}:${world}`) ?? null,
                search: `${name} Create 列车 train ${x} ${z}`.toLocaleLowerCase()
            });
        }

        P.sidebar?.markerBrowser?.update(records, 'create');
    }

    trainColor(colorIndex) {
        const palette = [
            '#f9fafb', '#f59e0b', '#c084fc', '#60a5fa',
            '#facc15', '#84cc16', '#fb7185', '#6b7280',
            '#d1d5db', '#22d3ee', '#a855f7', '#2563eb',
            '#92400e', '#15803d', '#dc2626', '#111827'
        ];
        return palette[Math.abs(Number(colorIndex) || 0) % palette.length];
    }

    trainIconSignature(train, angle, front = true) {
        return `${train.name}:${train.color}:${train.derailed}:${train.stopped}:${front}:${Number(angle).toFixed(2)}`;
    }

    trainIcon(train, angle, front = true) {
        const color = this.trainColor(train.color);
        const classes = `create-train-marker${train.derailed ? ' derailed' : ''}${train.stopped ? ' stopped' : ''}${front ? '' : ' rear-section'}`;
        return L.divIcon({
            className: classes,
            html: `<span class="create-train-body" style="--train-color:${color};--train-angle:${Number(angle) || 0}deg"><i></i></span>` +
                `<b class="create-train-label">${this.escape(train.name)}</b>`,
            iconSize: [30, 16],
            iconAnchor: [15, 8]
        });
    }

    updateTrainElement(marker, train, angle, front = true) {
        const signature = this.trainIconSignature(train, angle, front);
        const element = marker.getElement();
        if (element == null) {
            if (marker._createIconSignature !== signature) {
                marker.setIcon(this.trainIcon(train, angle, front));
                marker._createIconSignature = signature;
            }
            return;
        }
        const color = this.trainColor(train.color);
        element.style.setProperty('--train-color', color);
        element.style.setProperty('--train-angle', `${Number(angle) || 0}deg`);
        element.style.setProperty('--train-transition-ms', `${Math.max(450, this.dynamicInterval * 0.92)}ms`);
        element.classList.toggle('derailed', train.derailed === true);
        element.classList.toggle('stopped', train.stopped === true);
        element.classList.toggle('rear-section', front !== true);
        const body = element.querySelector('.create-train-body');
        if (body != null) {
            body.style.setProperty('--train-color', color);
            body.style.setProperty('--train-angle', `${Number(angle) || 0}deg`);
        }
        const label = element.querySelector('.create-train-label');
        if (label != null && label.textContent !== String(train.name ?? '')) {
            label.textContent = String(train.name ?? '');
        }
        marker._createIconSignature = signature;
    }

    portalIcon() {
        return L.divIcon({
            className: 'create-portal-marker portal-icon',
            html: `<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 64 64"><path class="outline" d="M56 0a8 8 0 0 1 8 8v48a8 8 0 0 1-8 8H8a8 8 0 0 1-8-8V8a8 8 0 0 1 8-8h48Z"/><path class="fill" d="M56 4H8a4 4 0 0 0-3.995 3.8L4 8v48a4 4 0 0 0 3.8 3.995L8 60h48a4 4 0 0 0 3.995-3.8L60 56V8a4 4 0 0 0-3.8-3.995L56 4Z"/><path class="outline" d="m29.287 18.469.127.117 12 12a2 2 0 0 1 .17 2.635l-.114.136-12 13a2 2 0 0 1-3.051-2.582l.111-.132L35.431 34H10a2 2 0 0 1-.15-3.995L10 30h25.171l-8.585-8.586a2 2 0 0 1-.117-2.701l.117-.127a2 2 0 0 1 2.701-.117Z"/><path class="outline" d="M49 9a6 6 0 0 1 5.996 5.775L55 15v34a6 6 0 0 1-5.775 5.996L49 55h-8a6 6 0 0 1-5.996-5.775L35 49v-3a2 2 0 0 1 3.995-.15L39 46v3a2 2 0 0 0 1.85 1.995L41 51h8a2 2 0 0 0 1.995-1.85L51 49V15a2 2 0 0 0-1.85-1.995L49 13h-8a2 2 0 0 0-1.995 1.85L39 15v4a2 2 0 0 1-3.995.15L35 19v-4a6 6 0 0 1 5.775-5.996L41 9h8Z"/></svg>`,
            iconSize: [24, 24],
            iconAnchor: [12, 12]
        });
    }

    stationIcon(assembling) {
        return L.divIcon({
            className: `create-station-marker station-icon${assembling ? ' assembling' : ''}`,
            html: `<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 64 64"><path class="outline" d="M56 0a8 8 0 0 1 8 8v48a8 8 0 0 1-8 8H8a8 8 0 0 1-8-8V8a8 8 0 0 1 8-8h48Z"/><path class="fill" d="M56 4H8a4 4 0 0 0-3.995 3.8L4 8v48a4 4 0 0 0 3.8 3.995L8 60h48a4 4 0 0 0 3.995-3.8L60 56V8a4 4 0 0 0-3.8-3.995L56 4Z"/><path class="outline" d="m33.287 16.466.127.116 14 14 .117.128a2 2 0 0 1 0 2.574l-.117.127-.127.117a2 2 0 0 1-2.574 0l-.127-.117-10.587-10.586L34 49.997l-.005.149a2 2 0 0 1-3.99 0l-.005-.15V22.826L19.413 33.411l-.127.117a2 2 0 0 1-2.818-2.818l.117-.128 14-14 .127-.116a2 2 0 0 1 2.574 0ZM50.36 10l.149.005a2 2 0 0 1 .003 3.99l-.149.005-36 .033-.15-.005a2 2 0 0 1-.003-3.99l.15-.005 36-.033Z"/></svg>`,
            iconSize: [18, 18],
            iconAnchor: [9, 9]
        });
    }

    trainTooltip(train) {
        const speedPerTick = Math.abs(Number(train.speed ?? 0));
        const speedPerSecond = (speedPerTick * 20).toFixed(1);
        const state = train.derailed ? '脱轨' : train.stopped ? '停靠/静止' : '运行中';
        const owner = train.owner ? `<br>车主 ${this.escape(train.owner)}` : '';
        return `<strong>${this.escape(train.name)}</strong><br>` +
            `${Number(train.carriages ?? 0)} 节 · ${state}<br>` +
            `速度 ${speedPerSecond} 格/秒${owner}`;
    }

    latLngs(points) {
        return (points ?? []).map(point => P.toLatLng(point.x, point.z));
    }

    updateStatusPanel() {
        if (this.statusElement == null) return;
        const world = P.worldList?.curWorld?.name;
        const tracks = world == null ? 0 : (this.network?.tracks ?? []).filter(track => track.world === world).length;
        const stations = world == null ? 0 : new Set(
            (this.network?.stations ?? []).filter(station => station.world === world).map(station => station.id)
        ).size;
        const trains = world == null ? 0 : this.trainData.filter(train =>
            (train.positions ?? []).some(position => position.world === world)
        ).length;
        const blocks = world == null ? 0 : (this.network?.signal_blocks ?? []).filter(block =>
            (block.segments ?? []).some(segment => segment.world === world)
        ).length;
        const signalHeads = world == null ? 0 : (this.network?.signals ?? [])
            .filter(signal => signal.world === world)
            .reduce((count, signal) => count + (signal.forward ? 1 : 0) + (signal.reverse ? 1 : 0), 0);
        if (this.statusSummaryElement != null) {
            this.statusSummaryElement.innerHTML = `
                <span class="create-status-badge">轨道 ${tracks}</span>
                <span class="create-status-badge">闭塞 ${blocks}</span>
                <span class="create-status-badge">信号 ${signalHeads}</span>
                <span class="create-status-badge">车站 ${stations}</span>
                <span class="create-status-badge">列车 ${trains}</span>
            `;
        }

        const status = this.status ?? {};
        const progress = Math.max(0, Math.min(1, Number(status.refresh_progress ?? 0)));
        const refreshing = status.refreshing === true;
        const sleeping = status.sleeping === true;
        if (this.statusRingElement != null) {
            this.statusRingElement.style.setProperty('--refresh-progress', `${progress * 360}deg`);
            this.statusRingElement.classList.toggle('refreshing', refreshing);
            this.statusRingElement.classList.toggle('sleeping', sleeping);
        }

        const now = Date.now() + this.serverClockOffset;
        let meta;
        let countdownSec = null;
        if (status.enabled === false) {
            meta = '集成已关闭，可使用 /squaremap create enable 开启';
        } else if (refreshing) {
            meta = `正在刷新 ${Math.round(progress * 100)}% · ${status.refresh_reason ?? '处理中'}`;
        } else if (sleeping) {
            meta = '网页无人查看，Create 采样已休眠';
        } else if (!this.dynamicLayersVisible() || status.dynamic_sleeping === true) {
            const next = Math.max(0, Number(status.next_static_refresh ?? 0) - now);
            if (next > 0) countdownSec = Math.ceil(next / 1000);
            meta = `动态采样已暂停${next > 0 ? '' : ' · 静态铁路等待校验'}`;
        } else if (Number(status.last_static_refresh ?? 0) > 0) {
            const last = this.relativeTime(now - Number(status.last_static_refresh));
            const next = Math.max(0, Number(status.next_static_refresh ?? 0) - now);
            if (next > 0) countdownSec = Math.ceil(next / 1000);
            meta = `上次 ${last}${next > 0 ? '' : ' · 等待校验'}`;
        } else {
            meta = '等待首次静态铁路刷新';
        }
        
        if (status.error) {
            meta = `<span class="create-status-error">错误：${status.error}</span>`;
        } else {
            const sampleMicros = Math.max(
                Number(status.last_static_slice_micros ?? 0),
                Number(status.last_dynamic_capture_micros ?? 0)
            );
            if (sampleMicros > 0) meta += ` · 主线程 ${(sampleMicros / 1000).toFixed(2)}ms`;
        }
        
        if (this.statusMetaElement != null) this.statusMetaElement.innerHTML = meta;

        if (this.statusCountdownElement != null) {
            if (countdownSec !== null && !refreshing && !sleeping) {
                this.statusCountdownElement.textContent = countdownSec;
                this.statusRingElement.classList.add('has-countdown');
            } else {
                this.statusCountdownElement.textContent = '';
                this.statusRingElement.classList.remove('has-countdown');
            }
        }

        if (this.refreshButton != null) {
            const cooldown = Number(status.refresh_cooldown_until ?? 0) - now;
            this.refreshButton.disabled = refreshing || this.refreshRequestInFlight || cooldown > 0 || status.enabled === false;
            if (refreshing || this.refreshRequestInFlight) {
                this.refreshButton.classList.add('loading');
            } else {
                this.refreshButton.classList.remove('loading');
            }
            this.refreshButton.title = cooldown > 0
                ? `${Math.ceil(cooldown / 1000)} 秒后可再次刷新`
                : '立即刷新静态铁路';
        }
    }

    async requestStaticRefresh() {
        if (this.refreshRequestInFlight || this.refreshButton?.disabled) return;
        this.refreshRequestInFlight = true;
        this.updateStatusPanel();
        try {
            await this.fetchJson(`tiles/create/refresh.json?t=${Date.now()}`);
            window.setTimeout(() => this.checkStatus(), 250);
        } finally {
            window.setTimeout(() => {
                this.refreshRequestInFlight = false;
                this.updateStatusPanel();
            }, 700);
        }
    }

    relativeTime(milliseconds) {
        const seconds = Math.max(0, Math.floor(milliseconds / 1000));
        if (seconds < 10) return '刚刚';
        if (seconds < 60) return `${seconds} 秒前`;
        const minutes = Math.floor(seconds / 60);
        if (minutes < 60) return `${minutes} 分钟前`;
        return `${Math.floor(minutes / 60)} 小时前`;
    }

    countdown(milliseconds) {
        const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
        if (seconds < 60) return `${seconds} 秒`;
        const minutes = Math.ceil(seconds / 60);
        return `${minutes} 分钟`;
    }

    async fetchJson(url) {
        try {
            const response = await fetch(url, {cache: 'no-cache'});
            if (!response.ok) return null;
            return await response.json();
        } catch (_) {
            return null;
        }
    }

    escape(value) {
        const element = document.createElement('span');
        element.textContent = value == null ? '' : String(value);
        return element.innerHTML;
    }
}

export { CreateLayerManager };
