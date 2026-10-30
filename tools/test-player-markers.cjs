const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');

const markers = new Map();
let pans = 0;
const P = {
    playerList: { markers },
    worldList: {
        curWorld: { name: 'overworld', player_tracker: { enabled: true, nameplates: { enabled: false } } },
        worlds: new Map([['overworld', {}]]),
        showWorld: (_, callback) => callback()
    },
    layerControl: { playersLayer: { removeLayer() {} } },
    map: { removeLayer() {}, panTo() { pans++; } },
    toLatLng(x, z) { assert.ok(Number.isFinite(x) && Number.isFinite(z)); return { x, z }; }
};
const L = {
    tooltip: () => ({ setContent() {} }),
    icon: options => options,
    marker: () => ({ addTo() {}, remove() {}, bindTooltip() {}, setLatLng() {}, setRotationAngle() {} })
};
const document = { getElementById: () => null };
function load(file, name) {
    const code = readFileSync(path.join(__dirname, '../common/src/main/resources/web/js/modules', file), 'utf8')
        .replace(/^import .*;\s*$/gm, '').replace(/^export .*;\s*$/gm, '');
    return runInNewContext(`${code}\n${name}`, { P, L, document });
}
const Player = load('util/Player.js', 'Player');
const PlayerList = load('PlayerList.js', 'PlayerList');
const data = { uuid: 'test', name: 'test', world: 'overworld' };
const player = new Player(data);
assert.equal(player.marker, null);
assert.equal(markers.size, 0);
player.update({ ...data, x: 10, z: 20, yaw: 30 });
assert.notEqual(player.marker, null);
assert.equal(markers.size, 1);
player.update(data);
assert.equal(player.marker, null);
assert.equal(markers.size, 0);
player.removeMarker();
const list = Object.create(PlayerList.prototype);
list.players = new Map([['test', player]]);
list.following = 'test';
assert.equal(list.showPlayer('missing'), false);
list.showPlayer('test');
assert.equal(pans, 0);
player.x = 10; player.z = 20;
list.showPlayer('test');
assert.equal(pans, 1);
list.removeFromList(player);
assert.equal(list.following, null);
assert.equal(list.players.size, 0);
console.log('PASS: hidden/visible player markers, missing players, following, logout');
