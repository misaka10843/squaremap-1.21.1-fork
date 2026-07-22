import { P } from './Squaremap.js';

class UICoordinates {
    constructor(json, show) {
        this.showCoordinates = show;
        this.enabled = json.enabled !== false;
        const Location = L.Control.extend({
            options: {position: 'bottomleft'},
            onAdd: () => {
                const element = L.DomUtil.create('div', 'map-location-bar');
                element.innerHTML = `
                    <span class="location-icon"><svg viewBox="0 0 24 24"><path d="M12 2a7 7 0 0 0-7 7c0 5.2 7 13 7 13s7-7.8 7-13a7 7 0 0 0-7-7Zm0 9.5A2.5 2.5 0 1 1 12 6a2.5 2.5 0 0 1 0 5.5Z"/></svg></span>
                    <span class="location-group"><small>位置</small><strong class="location-coords">—, —</strong></span>
                    <span class="location-divider"></span>
                    <span class="location-group"><small>区块</small><strong class="location-chunk">—, —</strong></span>
                    <button type="button" class="location-copy" title="复制当前位置" aria-label="复制当前位置"><svg viewBox="0 0 24 24"><path d="M8 7V3h13v13h-4v5H3V7h5Zm2 0h7v7h2V5h-9v2Zm5 2H5v10h10V9Z"/></svg></button>`;
                element.style.display = show && this.enabled ? '' : 'none';
                L.DomEvent.disableClickPropagation(element);
                element.querySelector('.location-copy').addEventListener('click', () => this.copy());
                this.element = element;
                this.coordsElement = element.querySelector('.location-coords');
                this.chunkElement = element.querySelector('.location-chunk');
                return element;
            }
        });
        this.coords = {x: '---', z: '---'};
        this.control = new Location();
        P.map.addControl(this.control);
        P.map.on('mousemove', event => this.update(P.toPoint(event.latlng)));
        P.map.on('mouseout', () => this.update(P.toPoint(P.map.getCenter())));
        P.map.on('moveend', () => {
            if (this.coords.x === '---') this.update(P.toPoint(P.map.getCenter()));
        });
        this.update(null);
    }

    update(point) {
        this.coords.x = point == null ? '---' : Math.floor(point.x);
        this.coords.z = point == null ? '---' : Math.floor(point.y);
        if (!this.coordsElement) return;
        this.coordsElement.textContent = `${this.coords.x}, ${this.coords.z}`;
        this.chunkElement.textContent = point == null ? '—, —' : `${Math.floor(this.coords.x / 16)}, ${Math.floor(this.coords.z / 16)}`;
    }

    async copy() {
        if (this.coords.x === '---') return;
        const text = `X: ${this.coords.x}, Z: ${this.coords.z}`;
        let copied = false;
        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(text);
                copied = true;
            }
        } catch (_) {
            // HTTP-hosted maps may not expose the Clipboard API.
        }
        if (!copied) {
            const input = document.createElement('textarea');
            input.value = text;
            input.style.position = 'fixed';
            input.style.opacity = '0';
            document.body.appendChild(input);
            input.select();
            copied = document.execCommand('copy');
            input.remove();
        }
        if (!copied) return;
        const button = this.element.querySelector('.location-copy');
        button.classList.add('copied');
        window.setTimeout(() => button.classList.remove('copied'), 900);
    }
}

export { UICoordinates };
