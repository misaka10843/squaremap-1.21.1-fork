const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('../build/diagnostics/node_modules/playwright');

const web = path.resolve(__dirname, '../common/src/main/resources/web');
const counts = new Map();
let sequence = 0;
let eventsEnabled = false;
const eventClients = new Set();
const worldName = 'minecraft_overworld';
const otherWorld = 'minecraft_the_nether';
const rootSettings = {
    worlds: [{ name: worldName, display_name: 'Overworld', order: 0, type: 'normal', icon: '' },
        { name: otherWorld, display_name: 'Nether', order: 1, type: 'nether', icon: '' }],
    ui: { title: 'squaremap test — {world}', sidebar: { pinned: 'unpinned', player_list_label: 'Players {cur}/{max}', world_list_label: 'Worlds' },
        coordinates: { enabled: true, html: '{x}, {z}' }, link: { enabled: true } }
};
const worldSettings = {
    spawn: { x: 0, z: 0 }, zoom: { max: 1, def: 1, extra: 1 }, tile_format: 'png', marker_update_interval: 1, tiles_update_interval: 15,
    player_tracker: { enabled: true, update_interval: 1, show_controls: true, default_hidden: false, label: 'Players', priority: 1, z_index: 100,
        nameplates: { enabled: true, show_heads: false, show_armor: true, show_health: true, heads_url: '' } }
};
const tilePath = /^\/tiles\/minecraft_(?:overworld|the_nether)\/\d+\/-?\d+_-?\d+\.png$/;
const server = http.createServer(async (req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    counts.set(pathname, (counts.get(pathname) ?? 0) + 1);
    const json = value => { res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(value)); };
    if (pathname === '/tiles/settings.json') return json(rootSettings);
    if ([worldName, otherWorld].some(world => pathname === `/tiles/${world}/settings.json`)) return json(worldSettings);
    if (pathname === `/tiles/${worldName}/changes.json`) return json({ epoch: 'test', seq: sequence, dropped: 0, changes: sequence ? [[sequence, 1, 0, 0]] : [] });
    if ([worldName, otherWorld].some(world => pathname === `/tiles/${world}/markers.json`)) {
        return json([{id: 'spawn', name: 'Spawn', timestamp: 1, order: 0, z_index: 0, control: true, hide: false, markers: {}}]);
    }
    if (pathname === '/tiles/players.json') return json({ players: [], max: 20 });
    if (pathname === '/tiles/create/status.json') return json({ available: false, enabled: false });
    if (pathname === '/api/events') {
        if (!eventsEnabled) { res.writeHead(404); return res.end(); }
        res.writeHead(200, {'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
        res.write('event: ready\ndata: {}\n\n');
        eventClients.add(res);
        req.on('close', () => eventClients.delete(res));
        return;
    }
    if (tilePath.test(pathname)) {
        res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
        return res.end(await fs.readFile(path.join(web, 'images/grass.png')));
    }
    const file = path.resolve(web, '.' + (pathname === '/' ? '/index.html' : decodeURIComponent(pathname)));
    if (!file.startsWith(web + path.sep)) { res.writeHead(403); return res.end(); }
    try {
        const data = await fs.readFile(file);
        const mime = { '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.html': 'text/html', '.svg': 'image/svg+xml' }[path.extname(file)] ?? 'application/octet-stream';
        res.writeHead(200, { 'Content-Type': mime }); res.end(data);
    } catch { res.writeHead(404); res.end(); }
});

(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const browser = await chromium.launch({ channel: 'msedge', headless: true });
    const failures = [];
    const ready = page => page.waitForFunction(async () => {
        const { P } = await import('/js/modules/Squaremap.js');
        const tiles = Object.values(P.layerControl.tileLayer1?._tiles ?? {});
        return P.worldList?.curWorld?.zoom?.max === 1 && tiles.length > 0 && tiles.every(tile => tile.loaded);
    }, null, { timeout: 15000 });
    try {
        const staticPage = await browser.newPage({ viewport: { width: 1000, height: 700 } });
        worldSettings.tile_format = 'webp';
        staticPage.on('pageerror', error => failures.push(error.message));
        await staticPage.goto(base + '/?static=true');
        await ready(staticPage);
        await staticPage.waitForTimeout(2500);
        assert.equal(counts.get('/api/events') ?? 0, 0);
        assert.equal(counts.get(`/tiles/${worldName}/changes.json`) ?? 0, 0);
        assert.equal(counts.get('/tiles/players.json'), 1);
        assert.equal(counts.get(`/tiles/${worldName}/markers.json`), 1);
        assert.equal(counts.get('/tiles/create/status.json'), 1);
        assertTrueWebpFallback(counts);
        await checkWorldSwitchingAndCreatePanel(staticPage);
        await fs.mkdir(path.resolve(__dirname, '../build/diagnostics'), { recursive: true });
        await staticPage.screenshot({ path: path.resolve(__dirname, '../build/diagnostics/static-map.png') });
        await staticPage.close();
        worldSettings.tile_format = 'png';
        console.log('PASS: static mode loads once without SSE/polling; legacy PNG fallback remains available');

        counts.clear();
        const page = await browser.newPage({ viewport: { width: 1000, height: 700 } });
        page.on('pageerror', error => failures.push(error.message));
        await page.goto(base);
        await ready(page);
        await page.waitForTimeout(300);
        const before = new Map([...counts].filter(([url]) => tilePath.test(url)));
        sequence = 1;
        await page.waitForFunction(async () => {
            const { P } = await import('/js/modules/Squaremap.js');
            return P.layerControl.tileLayer1._cursor === 1;
        }, null, { timeout: 10000 });
        const changedPath = `/tiles/${worldName}/1/0_0.png`;
        const deadline = Date.now() + 5000;
        while ((counts.get(changedPath) ?? 0) <= (before.get(changedPath) ?? 0) && Date.now() < deadline) {
            await new Promise(resolve => setTimeout(resolve, 25));
        }
        const reloaded = [...counts].filter(([url, count]) => tilePath.test(url) && count > (before.get(url) ?? 0)).map(([url]) => url);
        assert.deepEqual(reloaded, [`/tiles/${worldName}/1/0_0.png`], JSON.stringify({before:[...before], after:[...counts], failures,
            tiles:await page.evaluate(async()=>{const {P}=await import('/js/modules/Squaremap.js');return Object.entries(P.layerControl.tileLayer1._tiles).map(([key,tile])=>({key,coords:tile.coords,reloading:tile.reloading}));})}));
        await page.screenshot({ path: path.resolve(__dirname, '../build/diagnostics/live-map.png') });
        await page.close();

        counts.clear(); sequence = 0; eventsEnabled = true;
        const ssePage = await browser.newPage({viewport:{width:1000,height:700}});
        ssePage.on('pageerror', error => failures.push(error.message));
        await ssePage.goto(base);
        await ready(ssePage);
        await ssePage.waitForFunction(async () => {
            const {P} = await import('/js/modules/Squaremap.js');
            return P.createLayerManager.sseConnected && !P.worldList.curWorld.changePollInFlight;
        });
        const changesPath = `/tiles/${worldName}/changes.json`;
        const logRequests = counts.get(changesPath);
        let tileRequests = counts.get(changedPath) ?? 0;
        sequence = 1;
        for (const client of eventClients) client.write('event: tiles-invalidated\ndata: '+JSON.stringify({world:worldName,tiles:[{z:1,x:0,y:0}]})+'\n\n');
        await waitForRequest(changedPath, tileRequests);
        assert.equal(counts.get(changesPath), logRequests, 'healthy SSE should invalidate tiles without per-second log polling');
        tileRequests = counts.get(changedPath);
        eventsEnabled = false;
        for (const client of eventClients) client.end();
        sequence = 2;
        await waitForRequest(changedPath, tileRequests);
        assert.ok(counts.get(changesPath) > logRequests, 'missed SSE changes must be recovered from the log');
        await ssePage.close();
        assert.deepEqual(failures, []);
        console.log('PASS: changes.json fallback reloads only the changed visible tile; no browser exceptions');
        console.log('PASS: healthy SSE updates tiles directly, and disconnects recover missed changes through changes.json');
    } finally {
        await browser.close();
        server.closeAllConnections();
        await new Promise(resolve => server.close(resolve));
    }
})().catch(error => { console.error(error); server.closeAllConnections(); server.close(); process.exitCode = 1; });

function assertTrueWebpFallback(requests) {
    assert.ok([...requests.keys()].some(url => url.endsWith('.webp')));
    assert.ok([...requests.keys()].some(url => tilePath.test(url)));
}

async function checkWorldSwitchingAndCreatePanel(page) {
    const spawnRows = page.locator('.drawer-layer-row').filter({hasText: /^Spawn$/});
    await spawnRows.first().waitFor();
    for (const world of [otherWorld, worldName, otherWorld, worldName]) {
        await page.locator(`.world-row[id="${world}"]`).click();
        await page.waitForFunction(async name => {
            const {P} = await import('/js/modules/Squaremap.js');
            return P.worldList.curWorld.name === name && P.worldList.curWorld.markerLayers.size === 1;
        }, world);
        await page.waitForFunction(() => [...document.querySelectorAll('.drawer-layer-row .layer-name')].some(row => row.textContent === 'Spawn'));
        assert.equal(await spawnRows.count(), 1, 'switching worlds must remove the previous Spawn drawer row');
        const visibilitySaved = await page.evaluate(() => localStorage.getItem('hide_spawn'));
        assert.notEqual(visibilitySaved, 'true', 'unloading must not persist a user hide preference');
    }
    assert.equal(await page.locator('.world-row img').evaluateAll(images => images.every(image => image.complete && image.naturalWidth > 0)), true);
    await page.evaluate(async () => {
        const {P} = await import('/js/modules/Squaremap.js');
        P.createLayerManager.applyStatus({available: true, enabled: false});
    });
    const panel = page.locator('.create-map-status');
    const toggle = panel.locator('.create-toggle-button');
    await page.mouse.move(10, 650);
    assert.equal(await toggle.evaluate(button => getComputedStyle(button).opacity), '0');
    await panel.hover();
    await toggle.click();
    await page.mouse.move(10, 650);
    await page.waitForTimeout(350);
    assert.equal(await panel.evaluate(element => element.classList.contains('collapsed')), true);
    assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
    assert.equal(await toggle.evaluate(button => getComputedStyle(button).opacity), '0');
    const centered = async selector => {
        const outer = await panel.boundingBox();
        const inner = await panel.locator(selector).boundingBox();
        assert.ok(Math.abs(outer.x + outer.width / 2 - inner.x - inner.width / 2) < 1, selector + ' horizontal center');
        assert.ok(Math.abs(outer.y + outer.height / 2 - inner.y - inner.height / 2) < 1, selector + ' vertical center');
    };
    await centered('.create-refresh-ring');
    await panel.hover();
    assert.equal(await toggle.evaluate(button => getComputedStyle(button).opacity), '1');
    await centered('.create-toggle-button');
    assert.equal(await panel.locator('.create-refresh-ring').evaluate(ring => getComputedStyle(ring).visibility), 'hidden');
    await toggle.click();
    assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
    await page.waitForTimeout(350);
    assert.equal(Math.round((await panel.boundingBox()).width), 320);
    console.log('PASS: repeated world switches retain one Spawn row; world icons load; Create ring and hover button are centered');
}

async function waitForRequest(url, before) {
    const deadline = Date.now() + 7000;
    while ((counts.get(url) ?? 0) <= before && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
    assert.ok((counts.get(url) ?? 0) > before, 'expected a tile reload: '+url);
}
