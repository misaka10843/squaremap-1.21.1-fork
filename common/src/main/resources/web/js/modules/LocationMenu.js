import { P } from './Squaremap.js';

class LocationMenu {
    constructor() {
        this.point = null;
        this.element = document.createElement('div');
        this.element.className = 'location-menu';
        this.element.setAttribute('role', 'menu');
        this.element.setAttribute('aria-hidden', 'true');
        this.element.innerHTML = `
            <div class="location-menu-heading">
                <span class="location-menu-pin"><svg viewBox="0 0 24 24"><path d="M12 2a7 7 0 0 0-7 7c0 5.2 7 13 7 13s7-7.8 7-13a7 7 0 0 0-7-7Zm0 9.5A2.5 2.5 0 1 1 12 6a2.5 2.5 0 0 1 0 5.5Z"/></svg></span>
                <span><strong>地图位置</strong><small class="location-menu-world"></small></span>
            </div>
            <div class="location-menu-coordinates">
                <span><small>位置</small><strong class="location-menu-position"></strong></span>
                <span><small>区块</small><strong class="location-menu-chunk"></strong></span>
            </div>
            <div class="location-menu-actions">
                <button type="button" class="location-menu-action" data-action="copy"><svg viewBox="0 0 24 24"><path d="M8 7V3h13v13h-4v5H3V7h5Zm2 0h7v7h2V5h-9v2Zm5 2H5v10h10V9Z"/></svg><span>复制坐标</span></button>
                <button type="button" class="location-menu-action" data-action="link"><svg viewBox="0 0 24 24"><path d="M9.6 13.4a1 1 0 0 1 0-1.4l2.4-2.4a4 4 0 1 1 5.7 5.7l-3.4 3.4a4 4 0 0 1-5.7 0 1 1 0 1 1 1.4-1.4 2 2 0 0 0 2.9 0l3.4-3.4a2 2 0 1 0-2.9-2.9L11 13.4a1 1 0 0 1-1.4 0Zm4.8-2.8a1 1 0 0 1 0 1.4L12 14.4a4 4 0 1 1-5.7-5.7l3.4-3.4a4 4 0 0 1 5.7 0A1 1 0 0 1 14 6.7a2 2 0 0 0-2.9 0L7.7 10a2 2 0 1 0 2.9 2.9l2.4-2.4a1 1 0 0 1 1.4 0Z"/></svg><span>复制此处链接</span></button>
                <button type="button" class="location-menu-action" data-action="center"><svg viewBox="0 0 24 24"><path d="M11 2h2v3.1A7 7 0 0 1 18.9 11H22v2h-3.1a7 7 0 0 1-5.9 5.9V22h-2v-3.1A7 7 0 0 1 5.1 13H2v-2h3.1A7 7 0 0 1 11 5.1V2Zm1 5a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0 3a2 2 0 1 1 0 4 2 2 0 0 1 0-4Z"/></svg><span>以此为中心</span></button>
            </div>`;
        document.body.appendChild(this.element);

        this.element.addEventListener('click', event => {
            const button = event.target.closest('[data-action]');
            if (button == null || this.point == null) return;
            this.action(button.dataset.action, button);
        });
        P.map.on('contextmenu', event => this.open(event));
        P.map.on('click movestart zoomstart', () => this.close());
        document.addEventListener('keydown', event => {
            if (event.key === 'Escape') this.close();
        });
    }

    open(event) {
        event.originalEvent?.preventDefault();
        const raw = P.toPoint(event.latlng);
        this.point = {x: Math.floor(raw.x), z: Math.floor(raw.y)};
        const world = P.worldList?.curWorld;
        this.element.querySelector('.location-menu-world').textContent = world?.display_name ?? world?.name ?? '';
        this.element.querySelector('.location-menu-position').textContent = `${this.point.x}, ${this.point.z}`;
        this.element.querySelector('.location-menu-chunk').textContent = `${Math.floor(this.point.x / 16)}, ${Math.floor(this.point.z / 16)}`;
        this.element.classList.add('show');
        this.element.setAttribute('aria-hidden', 'false');

        const source = event.originalEvent;
        const x = source?.clientX ?? 16;
        const y = source?.clientY ?? 16;
        const bounds = this.element.getBoundingClientRect();
        this.element.style.left = `${Math.max(8, Math.min(x, window.innerWidth - bounds.width - 8))}px`;
        this.element.style.top = `${Math.max(8, Math.min(y, window.innerHeight - bounds.height - 8))}px`;
    }

    close() {
        this.element.classList.remove('show');
        this.element.setAttribute('aria-hidden', 'true');
    }

    async action(action, button) {
        if (action === 'center') {
            P.centerOn(this.point.x, this.point.z, P.map.getZoom());
            this.close();
            return;
        }
        const text = action === 'link' ? this.locationUrl() : `X: ${this.point.x}, Z: ${this.point.z}`;
        if (await this.copy(text)) {
            button.classList.add('copied');
            const label = button.querySelector('span');
            const previous = label.textContent;
            label.textContent = '已复制';
            window.setTimeout(() => {
                button.classList.remove('copied');
                label.textContent = previous;
            }, 1000);
        }
    }

    locationUrl() {
        const url = new URL(window.location.href);
        url.searchParams.set('world', P.worldList.curWorld.name);
        url.searchParams.set('zoom', P.map.getZoom());
        url.searchParams.set('x', this.point.x);
        url.searchParams.set('z', this.point.z);
        url.searchParams.delete('uuid');
        return url.toString();
    }

    async copy(text) {
        try {
            if (navigator.clipboard?.writeText) {
                await navigator.clipboard.writeText(text);
                return true;
            }
        } catch (_) {
            // HTTP-hosted maps may not expose the Clipboard API; use the fallback below.
        }
        const input = document.createElement('textarea');
        input.value = text;
        input.style.position = 'fixed';
        input.style.opacity = '0';
        document.body.appendChild(input);
        input.select();
        const copied = document.execCommand('copy');
        input.remove();
        return copied;
    }
}

export { LocationMenu };
