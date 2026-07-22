import { P } from './Squaremap.js';

class MarkerBrowser {
    constructor() {
        this.sources = new Map();
        this.records = [];
        this.types = new Set();
        this.element = document.createElement('section');
        this.element.id = 'marker-browser';
        this.element.className = 'drawer-section marker-browser';
        this.element.innerHTML = `
            <div class="drawer-section-title"><span>地点与标记</span><span class="marker-count">0</span></div>
            <label class="marker-search">
                <svg viewBox="0 0 24 24"><path d="m15.5 14 5 5-1.5 1.5-5-5v-.8l-.3-.3a6 6 0 1 1 .7-.7l.3.3h.8ZM5 9.5A4.5 4.5 0 1 0 14 9.5a4.5 4.5 0 0 0-9 0Z"/></svg>
                <input type="search" placeholder="搜索名称、坐标或图层" autocomplete="off" />
                <button type="button" title="清空搜索" aria-label="清空搜索">×</button>
            </label>
            <div class="marker-filters" role="group" aria-label="标记类型筛选"></div>
            <div class="marker-results"></div>`;
        this.input = this.element.querySelector('input');
        this.filters = this.element.querySelector('.marker-filters');
        this.results = this.element.querySelector('.marker-results');
        this.count = this.element.querySelector('.marker-count');
        this.input.addEventListener('input', () => this.render());
        this.element.querySelector('.marker-search button').addEventListener('click', () => {
            this.input.value = '';
            this.input.focus();
            this.render();
        });
    }

    clear(source = null) {
        if (source == null) this.sources.clear();
        else this.sources.delete(source);
        this.records = [...this.sources.values()].flat();
        this.types.clear();
        for (const record of this.records) this.types.add(record.type);
        this.renderFilters();
        this.render();
    }

    update(records, source = 'markers') {
        this.sources.set(source, records);
        this.records = [...this.sources.values()].flat();
        this.types = new Set(this.records.map(record => record.type));
        this.renderFilters();
        this.render();
    }

    renderFilters() {
        const selected = new Set(Array.from(this.filters.querySelectorAll('button.active')).map(button => button.dataset.type));
        this.filters.replaceChildren();
        const hasSelectedType = [...selected].some(type => this.types.has(type));
        const all = this.filterButton('全部', 'all', selected.size === 0 || selected.has('all') || !hasSelectedType);
        this.filters.appendChild(all);
        for (const type of [...this.types].sort()) {
            this.filters.appendChild(this.filterButton(this.typeLabel(type), type, selected.has(type)));
        }
    }

    filterButton(label, type, active) {
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = label;
        button.dataset.type = type;
        button.classList.toggle('active', active);
        button.addEventListener('click', () => {
            for (const other of this.filters.children) other.classList.remove('active');
            button.classList.add('active');
            this.render();
        });
        return button;
    }

    render() {
        const query = this.input.value.trim().toLocaleLowerCase();
        const type = this.filters.querySelector('button.active')?.dataset.type ?? 'all';
        const matched = this.records.filter(record => (type === 'all' || record.type === type)
            && (!query || record.search.includes(query))).slice(0, 100);
        this.results.replaceChildren();
        this.count.textContent = String(matched.length);
        if (matched.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'marker-empty';
            empty.innerHTML = `<strong>${this.records.length ? '没有匹配结果' : '暂无标记'}</strong><span>${this.records.length ? '尝试其他关键词或类型' : '当前世界的地点、车站和列车会显示在这里'}</span>`;
            this.results.appendChild(empty);
            return;
        }
        for (const record of matched) {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'marker-result';
            button.innerHTML = `<span class="marker-result-icon">${this.typeIcon(record.type)}</span><span class="marker-result-main"><strong></strong><small></small></span><span class="marker-result-arrow">›</span>`;
            button.querySelector('strong').textContent = record.name;
            button.querySelector('small').textContent = `${record.layerName} · X ${record.x}, Z ${record.z}`;
            button.addEventListener('click', () => {
                if (!P.map.hasLayer(record.group)) record.group.addTo(P.map);
                P.map.flyTo(P.toLatLng(record.x, record.z), Math.max(P.map.getZoom(), 2), {duration: .55});
                window.setTimeout(() => {
                    if (record.leaflet?.openPopup) record.leaflet.openPopup();
                    else if (record.leaflet?.openTooltip) record.leaflet.openTooltip();
                    record.leaflet?.fire?.('click');
                }, 600);
                if (window.innerWidth <= 760) P.sidebar.show(false);
            });
            this.results.appendChild(button);
        }
    }

    typeLabel(type) {
        return ({icon: '图标', circle: '范围', ellipse: '范围', rectangle: '区域', polygon: '区域', polyline: '路线', station: '车站', train: '列车'})[type] || type;
    }

    typeIcon(type) {
        if (type === 'icon') return '●';
        if (type === 'station') return '◆';
        if (type === 'train') return '▰';
        if (type === 'polyline') return '⌁';
        if (type === 'circle' || type === 'ellipse') return '○';
        return '◇';
    }
}

export { MarkerBrowser };
