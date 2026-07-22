import { Fieldset } from "./util/Fieldset.js";
import { MarkerBrowser } from "./MarkerBrowser.js";
import { P } from './Squaremap.js';

class Sidebar {
    constructor(json, show) {
        this.showSidebar = show;
        this.sidebar = P.createElement("aside", "sidebar", this);
        this.sidebar.className = "map-drawer";
        this.sidebar.innerHTML = `
            <header class="drawer-header">
                <div class="drawer-brand">
                    <span class="drawer-brand-icon" aria-hidden="true">
                        <svg viewBox="0 0 24 24"><path d="m3 6 5-3 8 3 5-3v15l-5 3-8-3-5 3V6Zm6-.3v10.6l6 2.25V7.95L9 5.7Zm8-1v13.05l2-1.2V4.7l-2 1.2ZM5 7.2v10.1l2-1.2V6L5 7.2Z"/></svg>
                    </span>
                    <span><strong>World map</strong><small>Explore & navigate</small></span>
                </div>
                <button class="drawer-close" type="button" title="收起侧栏" aria-label="收起侧栏">
                    <svg viewBox="0 0 24 24"><path d="m15.4 7.4-1.4-1.4-6 6 6 6 1.4-1.4-4.6-4.6 4.6-4.6Z"/></svg>
                </button>
            </header>
            <div class="drawer-scroll"></div>`;
        document.body.appendChild(this.sidebar);
        this.scrim = document.createElement('div');
        this.scrim.className = 'drawer-scrim';
        this.scrim.addEventListener('click', () => this.show(false));
        document.body.appendChild(this.scrim);

        this.scroll = this.sidebar.querySelector('.drawer-scroll');
        this.worlds = new Fieldset("worlds", json.world_list_label || "世界");
        this.worlds.element.classList.add('drawer-section', 'drawer-worlds');
        this.scroll.appendChild(this.worlds.element);

        this.layers = new Fieldset("drawer-layers", "地图图层");
        this.layers.element.classList.add('drawer-section');
        this.layerList = document.createElement('div');
        this.layerList.className = 'drawer-layer-list';
        this.layers.element.appendChild(this.layerList);
        this.scroll.appendChild(this.layers.element);

        this.markerBrowser = new MarkerBrowser();
        this.scroll.appendChild(this.markerBrowser.element);

        this.players = new Fieldset("players", (json.player_list_label || "玩家 ({cur}/{max})")
            .replace(/{cur}/g, 0).replace(/{max}/g, 0));
        this.players.element.classList.add('drawer-section', 'drawer-players');
        this.scroll.appendChild(this.players.element);

        this.toggle = document.createElement('button');
        this.toggle.id = 'sidebar-toggle';
        this.toggle.type = 'button';
        this.toggle.title = '打开地图菜单';
        this.toggle.setAttribute('aria-label', '打开地图菜单');
        this.toggle.innerHTML = `<svg viewBox="0 0 24 24"><path d="m3 6 5-3 8 3 5-3v15l-5 3-8-3-5 3V6Zm6-.3v10.6l6 2.25V7.95L9 5.7Zm8-1v13.05l2-1.2V6L17 7.2ZM5 7.2v10.1l2-1.2V6L5 7.2Z"/></svg>`;
        document.body.appendChild(this.toggle);

        this.sidebar.querySelector('.drawer-close').addEventListener('click', () => this.show(false));
        this.toggle.addEventListener('click', () => this.show(true));
        this.sidebar.addEventListener("click", () => P.playerList?.followPlayerMarker(null));
        if (!show || json.pinned === 'hide') {
            this.sidebar.style.display = 'none';
            this.toggle.style.display = 'none';
        } else {
            document.body.classList.add('sidebar-enabled');
            this.show(json.pinned === 'pinned' || window.innerWidth > 980);
        }

        P.layerControl.onChanged(entries => this.renderLayers(entries));
        document.addEventListener('keydown', event => {
            if (event.key === 'Escape') this.show(false);
        });
    }

    show(show) {
        this.sidebar.classList.toggle('show', show);
        this.toggle.classList.toggle('drawer-open', show);
        this.toggle.setAttribute('aria-expanded', String(show));
        this.scrim.classList.toggle('show', show);
        this.sidebar.setAttribute('aria-hidden', String(!show));
        document.body.classList.toggle('sidebar-open', show);
        document.body.classList.toggle('drawer-visible', show && window.innerWidth <= 760);
    }

    renderLayers(entries) {
        this.layerList.replaceChildren();
        if (entries.length === 0) {
            const empty = document.createElement('p');
            empty.className = 'drawer-empty';
            empty.textContent = '当前世界暂无可选图层';
            this.layerList.appendChild(empty);
            return;
        }
        entries.sort((a, b) => (a.layer.order ?? 0) - (b.layer.order ?? 0));
        for (const entry of entries) {
            const label = document.createElement('label');
            label.className = 'drawer-layer-row';
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.checked = P.map.hasLayer(entry.layer);
            checkbox.addEventListener('change', () => {
                if (checkbox.checked) entry.layer.addTo(P.map);
                else entry.layer.remove();
            });
            const indicator = document.createElement('span');
            indicator.className = 'layer-indicator';
            const text = document.createElement('span');
            text.className = 'layer-name';
            text.textContent = this.plain(entry.name);
            label.append(checkbox, indicator, text);
            this.layerList.appendChild(label);
        }
    }

    plain(value) {
        const node = document.createElement('div');
        node.innerHTML = value ?? '';
        return node.textContent?.trim() || '未命名图层';
    }
}

export { Sidebar };
