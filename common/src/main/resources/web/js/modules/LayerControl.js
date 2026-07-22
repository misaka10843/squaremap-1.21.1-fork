import { P } from './Squaremap.js';
import { SquaremapTileLayer } from './SquaremapTileLayer.js';

class LayerControl {
    constructor() {
        this.layers = new Map();
        this.overlayEntries = [];
        this.changeListeners = [];
    }
    init() {
        this.currentLayer = 0;
        this.updateInterval = 60;

        this.playersLayer = new L.LayerGroup();
        this.playersLayer.id = "players_layer";

        this.controls = L.control.layers({}, {}, {
            position: 'topleft',
            collapsed: false, // 禁止默认 hover 展开
            sortLayers: true,
            sortFunction: (a, b) => {
                return a.order - b.order;
            }
        })
        .addTo(P.map);

        // 自定义极致折叠逻辑
        const container = this.controls.getContainer();
        container.classList.add('custom-layer-control', 'collapsed');

        // 添加折叠拨动按钮
        const toggleBtn = document.createElement('div');
        toggleBtn.className = 'custom-layer-toggle';
        toggleBtn.innerHTML = `
            <svg viewBox="0 0 24 24" width="20" height="20" fill="var(--sm-text)">
                <path d="M12 18.17L8.83 15l-1.41 1.41L12 21l4.59-4.59L15.17 15M12 5.83L15.17 9l1.41-1.41L12 3 7.41 7.59 8.83 9 12 5.83zm0 5.84l-4.59 4.59L8.83 17.67 12 14.5l3.17 3.17 1.41-1.41L12 11.67z" />
            </svg>
        `;
        // 图层图标SVG
        const iconDiv = document.createElement('div');
        iconDiv.className = 'custom-layer-icon';
        iconDiv.innerHTML = `
            <svg viewBox="0 0 24 24" width="24" height="24" fill="var(--sm-accent)">
                <path d="M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z"/>
            </svg>
        `;
        container.insertBefore(toggleBtn, container.firstChild);
        container.insertBefore(iconDiv, container.firstChild);

        // 点击展开/收起
        toggleBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            container.classList.toggle('collapsed');
        });
        container.addEventListener('click', () => {
            if (container.classList.contains('collapsed')) {
                container.classList.remove('collapsed');
            }
        });
    }
    addOverlay(name, layer, hide) {
        this.controls.addOverlay(layer, name);
        this.overlayEntries = this.overlayEntries.filter(entry => entry.layer !== layer);
        this.overlayEntries.push({name, layer});
        if (this.shouldHide(layer, hide) !== true) {
            layer.addTo(P.map);
        }
        this.notifyChanged();
    }
    removeOverlay(layer) {
        this.ignoreLayer = layer;
        this.controls.removeLayer(layer);
        this.overlayEntries = this.overlayEntries.filter(entry => entry.layer !== layer);
        layer.remove();
        this.ignoreLayer = null;
        this.notifyChanged();
    }
    shouldHide(layer, def) {
        const value = window.localStorage.getItem(`hide_${layer.id}`);
        return value == null ? def : value === 'true';
    }
    hideLayer(layer) {
        if (layer != this.ignoreLayer) {
            window.localStorage.setItem(`hide_${layer.id}`, 'true');
        }
        this.notifyChanged();
    }
    showLayer(layer) {
        if (layer != this.ignoreLayer) {
            window.localStorage.setItem(`hide_${layer.id}`, 'false');
        }
        this.notifyChanged();
    }
    onChanged(listener) {
        this.changeListeners.push(listener);
        listener([...this.overlayEntries]);
    }
    notifyChanged() {
        for (const listener of this.changeListeners) listener([...this.overlayEntries]);
    }
    setupTileLayers(world) {
        // setup the map tile layers
        // we need 2 layers to swap between for seamless refreshing
        if (this.tileLayer1 != null) {
            P.map.removeLayer(this.tileLayer1);
        }
        if (this.tileLayer2 != null) {
            P.map.removeLayer(this.tileLayer2);
        }
        this.tileLayer1 = this.createTileLayer(world);
        this.tileLayer2 = this.createTileLayer(world);

        // refresh player's control
        this.removeOverlay(this.playersLayer);
        if (world.player_tracker.show_controls) {
            this.addOverlay(world.player_tracker.label,
                this.playersLayer,
                world.player_tracker.default_hidden);
        }
        this.playersLayer.order = world.player_tracker.priority;
        this.playersLayer.setZIndex(world.player_tracker.z_index);
    }
    createTileLayer(world) {
        return new SquaremapTileLayer(`tiles/${world.name}/{z}/{x}_{y}.${world.tile_format ?? 'png'}`, {
            tileSize: 512,
            minNativeZoom: 0,
            maxNativeZoom: world.zoom.max,
            errorTileUrl: 'images/clear.png'
        }).addTo(P.map)
        .addEventListener("load", () => {
            // when all tiles are loaded, switch to this layer
            this.switchTileLayer();
        });
    }
    updateTileLayer() {
        // redraw background tile layer
        if (this.currentLayer == 1) {
            this.tileLayer2.redraw();
        } else {
            this.tileLayer1.redraw();
        }
    }
    switchTileLayer() {
        // swap current tile layer
        if (this.currentLayer == 1) {
            this.tileLayer1.setZIndex(0);
            this.tileLayer2.setZIndex(1);
            this.currentLayer = 2;
        } else {
            this.tileLayer1.setZIndex(1);
            this.tileLayer2.setZIndex(0);
            this.currentLayer = 1;
        }
    }
}

export { LayerControl };
